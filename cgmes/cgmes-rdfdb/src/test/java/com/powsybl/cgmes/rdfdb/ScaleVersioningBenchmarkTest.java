/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Part E of the scale campaign: versioning at depth 50 and 200 with a big catalogue.
 *
 * <p>What scales here is the metadata graph, not the grid, so the grid is the MicroGrid BE ({@code be}); when
 * {@code sv6} is among the selected grids one more row is measured on it, to show that the plan does not depend on
 * the size of the model. A chain of 200 one-load {@code putDiff} steps; the plan query
 * ({@code versionGraph(S).plan(root, target, options)}) to depth 1/10/50/200 with the scenario alone, then with ten
 * synthetic scenarios of 300 snapshot nodes (3&nbsp;000 extra nodes, written straight into their metadata graphs
 * as {@link RdfDbVersioningBenchmarkTest} does) and the scenario alone again, back to back; the composed update of
 * a network at the root to depth 50 and 200 ({@link UpdateStatistics}); the materialisation at depth 200 cold and
 * warm (JIT-warm, no graph cache); {@link Checkpoint#create} at 200 and the plan query afterwards. Fuseki requests
 * per operation: the plan is one request, the update two, the materialisation the graphs plus two.</p>
 *
 * <p>Gates, relative only: plan(200, ten scenarios) {@code <= 1.2 x} plan(200, alone) (the larger of the two
 * readings around it); plan(200)/plan(50) {@code <= 6} (linear is 4); the request counts as designed; plan + fetch
 * + compose at depth 200 {@code <= 6 x} the same at 50 <em>plus 5 ms</em>. Two deliberate departures from the
 * campaign plan, both written down in its report: the plan asked for {@code 3 x} on the assumption that the plan
 * query is flat in depth, but it is a path walk and linear in depth (measured 32 ms at 50 and 113 ms at 200 on
 * Fuseki), and it dominates the three, so the bound is the same {@code 6 x} the plan query itself gets (linear
 * would be 4); the 5 ms is the floor below which one scheduling hiccup is a factor. And the request counts are
 * those {@code RdfDbRequestCountTest} pins for the <em>snapshot</em> routes (update three, load graphs + 5), not
 * the two and graphs + 2 of the unversioned head routes the plan quoted. Reported: the 10 ms plan target.</p>
 *
 * <h2>Measured numbers</h2>
 * <p>8 cores, Java 21, {@code -Xmx24g}, 2026-09-22, milliseconds (full table in the campaign report
 * {@code 16-benchmark-campaign.md}):</p>
 * <pre>
 * grid backend    p1    p10    p50    p200  p200+10  u200 (tot/plan)  m200 cold  ck   p200 after ck
 * be   fuseki   5.16  10.85  36.52  115.01   111.04  151 / 110              248  321  111.56
 * sv6  fuseki   4.04   8.15  25.10   87.43    86.51  143 / 108             2225  871   85.37
 * be   memory   2.74   3.59   6.23   17.72    14.64   21 / 13               138  178   19.04
 * sv6  memory   1.05   1.32   3.04    9.13     9.31   15 / 7               1602  260    8.15
 * </pre>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class ScaleVersioningBenchmarkTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScaleVersioningBenchmarkTest.class);

    private static final int DEPTH = 200;
    private static final int OTHER_SCENARIOS = 10;
    private static final int NODES_PER_SCENARIO = 300;
    private static final long PLAN_TARGET_MS = 10;

    private final SoftAssertions softly = new SoftAssertions();

    static Stream<Arguments> backends() {
        return BenchMeters.backends();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void benchmark(String backend) {
        BenchMeters.logJvm(ScaleVersioningBenchmarkTest.class);
        BenchMeters.FusekiMeter.install();
        List<String> table = new ArrayList<>();
        table.add(String.format(Locale.ROOT, "E %-4s %-7s %6s %6s %6s %6s %7s %7s %7s | %-28s | %-28s | %7s %7s %5s"
                        + " %6s %6s %5s %4s %4s",
                "grid", "backend", "p1", "p10", "p50", "p200", "p200+10", "p200'", "build",
                "u50 (tot pl fe co ap)", "u200 (tot pl fe co ap)", "m200c", "m200w", "mreq", "ck", "p200ck",
                "preq", "ureq", "gr"));
        List<BenchMeters.Grid> grids = new ArrayList<>(List.of(new BenchMeters.Grid("be", 0)));
        if (BenchMeters.grids("sv").stream().anyMatch(g -> g.replicas() == 6)) {
            grids.add(new BenchMeters.Grid("sv6", 6));
        }
        for (BenchMeters.Grid grid : grids) {
            table.add(measure(backend, grid));
        }
        table.forEach(LOGGER::info);
        softly.assertAll();
    }

    private String measure(String backend, BenchMeters.Grid grid) {
        String scenario = "scale-versioning-" + grid.key() + "-" + backend;
        ReadOnlyDataSource ds = grid.dataSource();
        Network file = Network.read(ds, BenchMeters.params());
        Load load = file.getLoads().iterator().next();
        String loadId = load.getId();
        String loadClass = load.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS, "EnergyConsumer");
        file = null;
        int warmups = BenchMeters.warmups(grid);
        int runs = grid.small() ? Math.max(5, BenchMeters.runs(grid)) : BenchMeters.runs(grid);

        try (RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, scenario))) {
            db.clear(scenario);
            List<String> others = new ArrayList<>();
            SnapshotCatalog catalog = db.snapshots(scenario);
            long build = System.nanoTime();
            SnapshotInfo head = catalog.putFull(ds, null, SnapshotRef.of(scenario, "1.0"), BenchMeters.params(),
                    ReportNode.NO_OP);
            List<SnapshotRef> refs = new ArrayList<>();
            refs.add(head.ref());
            for (int i = 1; i <= DEPTH; i++) {
                head = catalog.putDiff(ScaleLoadBenchmarkTest.step(head, i, loadId, loadClass, !grid.svedala()),
                        SnapshotRef.of(scenario, "1." + i));
                refs.add(head.ref());
            }
            long buildMs = (System.nanoTime() - build) / 1_000_000;
            SnapshotInfo root = catalog.find(refs.get(0)).orElseThrow();

            // (p) plan queries, alone
            long p1 = plan(db, scenario, root, refs.get(1), warmups, runs);
            long p10 = plan(db, scenario, root, refs.get(10), warmups, runs);
            long p50 = plan(db, scenario, root, refs.get(50), warmups, runs);
            long p200 = plan(db, scenario, root, refs.get(DEPTH), warmups, runs);
            BenchMeters.FusekiMeter.Reading mark = BenchMeters.FusekiMeter.mark();
            db.versionGraph(scenario).plan(root.iri(), refs.get(DEPTH), new RdfDbUpdateOptions());
            long planRequests = BenchMeters.FusekiMeter.since(mark).requests();

            // ... with ten more scenarios in the database, and alone again
            for (int i = 0; i < OTHER_SCENARIOS; i++) {
                String other = scenario + "-other-" + i;
                others.add(other);
                syntheticScenario(db, other, NODES_PER_SCENARIO);
            }
            long p200many = plan(db, scenario, root, refs.get(DEPTH), warmups, runs);
            long p200again = plan(db, scenario, root, refs.get(DEPTH), warmups, runs);

            // (u) composed updates at 50 and 200
            long[] u50 = update(db, refs.get(0), refs.get(50), warmups, runs);
            mark = BenchMeters.FusekiMeter.mark();
            long[] u200 = update(db, refs.get(0), refs.get(DEPTH), 0, 1);
            long updateRequests = BenchMeters.FusekiMeter.since(mark).requests() - loadRequests(db, refs.get(0));
            u200 = update(db, refs.get(0), refs.get(DEPTH), warmups, runs);

            // (m) the materialisation at 200, cold then warm
            mark = BenchMeters.FusekiMeter.mark();
            long start = System.nanoTime();
            RdfDbNetworkLoader.LoadResult materialised = RdfDbNetworkLoader.loadWithStatistics(db, refs.get(DEPTH),
                    null, BenchMeters.params(), ReportNode.NO_OP);
            long m200cold = (System.nanoTime() - start) / 1_000_000;
            long materialiseRequests = BenchMeters.FusekiMeter.since(mark).requests();
            int graphs = materialised.statistics().graphs();
            long m200warm = BenchMeters.median(warmups, runs, () -> BenchMeters.millis(() ->
                    RdfDbNetworkLoader.load(db, refs.get(DEPTH), null, BenchMeters.params(), ReportNode.NO_OP)));

            // (ck) a checkpoint at 200 and the plan after it
            long checkpoint = BenchMeters.millis(() -> Checkpoint.create(db, refs.get(DEPTH)));
            long p200ck = plan(db, scenario, root, refs.get(DEPTH), warmups, runs);

            others.forEach(db::clear);
            db.clear(scenario);

            String row = String.format(Locale.ROOT, "E %-4s %-7s %6.2f %6.2f %6.2f %6.2f %7.2f %7.2f %7d | %-28s |"
                            + " %-28s | %7d %7d %5d %6d %6.2f %5d %4d %4d",
                    grid.key(), backend, p1 / 1e6, p10 / 1e6, p50 / 1e6, p200 / 1e6, p200many / 1e6,
                    p200again / 1e6, buildMs, joined(u50), joined(u200), m200cold, m200warm, materialiseRequests,
                    checkpoint, p200ck / 1e6, planRequests, updateRequests, graphs);
            LOGGER.info("{} / {}: {} target: plan at depth {} takes {} ms (target {} ms)", grid, backend,
                    p200 / 1e6 <= PLAN_TARGET_MS ? "TARGET MET" : "TARGET MISSED", DEPTH,
                    String.format(Locale.ROOT, "%.2f", p200 / 1e6), PLAN_TARGET_MS);

            softly.assertThat((double) p200many).as("plan at depth 200 with ten scenarios on " + backend + "/" + grid)
                    .isLessThanOrEqualTo(1.2 * Math.max(p200, p200again));
            softly.assertThat(p200 / (double) p50).as("plan depth 200 against depth 50 on " + backend + "/" + grid)
                    .isLessThanOrEqualTo(6.0);
            long ppfc50 = u50[1] + u50[2] + u50[3];
            long ppfc200 = u200[1] + u200[2] + u200[3];
            softly.assertThat(ppfc200).as("plan + fetch + compose at 200 against 50 (" + ppfc50 + " ms) on "
                    + backend + "/" + grid).isLessThanOrEqualTo(6 * ppfc50 + 5);
            if (Backends.FUSEKI.equals(backend)) {
                // The counts RdfDbRequestCountTest pins for the snapshot routes: a plan is one request, an update
                // to a snapshot three (plan, models of the path, statements), a snapshot load the graphs plus five
                softly.assertThat(planRequests).as("requests of a plan").isEqualTo(1);
                softly.assertThat(updateRequests).as("requests of an update").isLessThanOrEqualTo(3);
                softly.assertThat(materialiseRequests).as("requests of a materialisation")
                        .isLessThanOrEqualTo(graphs + 5L);
            }
            return row;
        }
    }

    private static String joined(long[] u) {
        return String.format(Locale.ROOT, "%d %d %d %d %d", u[0], u[1], u[2], u[3], u[4]);
    }

    /** Requests of the plain load of the root that {@link #update} does before the update itself. */
    private static long loadRequests(RdfDbConnection db, SnapshotRef root) {
        BenchMeters.FusekiMeter.Reading mark = BenchMeters.FusekiMeter.mark();
        RdfDbNetworkLoader.load(db, root, null, BenchMeters.params(), ReportNode.NO_OP);
        return BenchMeters.FusekiMeter.since(mark).requests();
    }

    private static long plan(RdfDbConnection db, String scenario, SnapshotInfo root, SnapshotRef target, int warmups,
                             int runs) {
        return BenchMeters.median(warmups, runs, () -> {
            long start = System.nanoTime();
            UpdatePlan plan = db.versionGraph(scenario).plan(root.iri(), target, new RdfDbUpdateOptions());
            long elapsed = System.nanoTime() - start;
            assertThat(plan.kind()).isEqualTo(UpdatePlan.Kind.DIFF);
            return elapsed;
        });
    }

    /** A fresh client at {@code from} brought to {@code target}: median total and the phases of the best run. */
    private static long[] update(RdfDbConnection db, SnapshotRef from, SnapshotRef target, int warmups, int runs) {
        List<Long> totals = new ArrayList<>();
        long[] best = null;
        for (int i = 0; i < warmups + runs; i++) {
            Network client = RdfDbNetworkLoader.load(db, from, null, BenchMeters.params(), ReportNode.NO_OP);
            UpdateResult result = RdfDbNetworkLoader.update(client, db, target, new RdfDbUpdateOptions(),
                    BenchMeters.params(), ReportNode.NO_OP);
            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            if (i < warmups) {
                continue;
            }
            UpdateStatistics s = result.statistics();
            totals.add(s.total().toMillis());
            if (best == null || s.total().toMillis() < best[0]) {
                best = new long[] {s.total().toMillis(), s.plan().toMillis(), s.fetch().toMillis(),
                    s.compose().toMillis(), s.apply().toMillis()};
            }
        }
        best[0] = BenchMeters.median(totals);
        return best;
    }

    /** A scenario of nothing but snapshot nodes, as {@link RdfDbVersioningBenchmarkTest} writes them. */
    private static void syntheticScenario(RdfDbConnection db, String scenario, int snapshots) {
        db.clear(scenario);
        String meta = SparqlText.iri(RdfDbNames.metaGraph(scenario));
        StringBuilder update = new StringBuilder(RdfDbVocabulary.PREFIXES)
                .append("INSERT DATA { GRAPH ").append(meta).append(" { ");
        StringBuilder filler = new StringBuilder();
        String timestep = "2016-01-01T00:00:00Z";
        for (int i = 0; i < snapshots; i++) {
            String iri = RdfDbNames.snapshot(scenario, timestep, "1." + i);
            update.append(SparqlText.iri(iri)).append(" a pdb:Snapshot ; pdb:scenario ")
                    .append(SparqlText.str(scenario)).append(" ; pdb:version ")
                    .append(SparqlText.str("1." + i)).append(" ; pdb:timestep ").append(SparqlText.str(timestep))
                    .append(" ; pdb:depth ").append(SparqlText.integer(i));
            if (i == 0) {
                update.append(" ; pdb:full ").append(SparqlText.iri(iri + "/full"));
                filler.append(SparqlText.iri(iri + "/full")).append(" pdb:subset ")
                        .append(SparqlText.str(CgmesSubset.STEADY_STATE_HYPOTHESIS.getIdentifier())).append(" . ");
            }
            if (i > 0) {
                update.append(" ; pdb:parent ")
                        .append(SparqlText.iri(RdfDbNames.snapshot(scenario, timestep, "1." + (i - 1))))
                        .append(" ; pdb:edge pdb:VersionEdge");
            }
            update.append(" . ");
        }
        db.sparql(scenario).update(update.append(filler).append("} }").toString());
    }
}
