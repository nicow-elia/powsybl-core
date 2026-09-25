/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.rdfdb.SvedalaTimestepFixtures.Shape;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.rdf4j.model.Value;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Part A of the scale campaign: ingesting timesteps with {@link SnapshotCatalog#putAsDiff} on Svedala replicated
 * up to IGM size.
 *
 * <p>Per grid ({@code sv<N>} of {@code -Dpowsybl.bench.grids}) and backend: the anchor {@code putFull} (wall
 * clock and peak heap), then {@code T} timesteps ({@code -Dpowsybl.bench.ingest.timesteps}, 8 in a campaign run, 3
 * in the ordinary build) of two shapes of {@link SvedalaTimestepFixtures}, replicated with
 * {@link ReplicatedSvedala} outside every timer: <b>rich</b> (every set point scaled, 311&nbsp;&times;&nbsp;N
 * statements each way, the production shape) and <b>structural</b> (18&nbsp;&times;&nbsp;N equipment objects
 * omitted, N lines added every eighth timestep). Phases come from {@link SnapshotCatalog.IngestStatistics}; the
 * store is counted after the day; heap after GC is read with the catalogue and its graph cache still held (the
 * parent index lives there) and the peak over the day; Fuseki requests and server milliseconds per timestep.</p>
 *
 * <p>Gates, relative only: median {@code putAsDiff} {@code <= 5 x} the anchor's {@code putFull}; median
 * {@code sv20/sv6 <= 5} per shape (linear is 3.3); every difference carries statements; structural timesteps take
 * the slow route and rich ones the fast one. Every scenario is cleared after its day.</p>
 *
 * <h2>Measured numbers</h2>
 * <p>8 cores, Java 21, {@code -Xmx24g}, 2026-09-22, 8 timesteps per shape, milliseconds, medians (full table in
 * the campaign report {@code 16-benchmark-campaign.md}):</p>
 * <pre>
 * grid  backend shape       anchor  median  first  parse  diff  write   fwd   rev
 * sv1   fuseki  rich          3450     207   1091     56    13     92   311   311
 * sv20  fuseki  rich         32380    1441   6957    750   183    481  6220  6220
 * sv20  fuseki  structural   34162    3931  10211   3055   677    182   100  3500
 * sv1   memory  rich          1235     100    400     42     7     40   311   311
 * sv6   memory  rich          3928     389   1371    237    45    101  1866  1866
 * sv20  memory  rich         11738    1626   4219    770   153    700  6220  6220
 * sv20  memory  structural   12727    3846   7463   3181   491    165   100  3500
 * after T6 #3 (two-phase write above 1 000 statements, IRI syntax not verified; 4 timesteps,
 * logs/bench16-t6-3-after.log):
 * sv20  fuseki  rich         31047    1136   5718    576   232    287  6220  6220
 * sv20  fuseki  structural   32951    3234   8913   2392   735     86   100  3500
 * sv20  memory  rich         14577     867   3633    613   203     43  6220  6220
 * sv20  memory  structural   12468    3154   6403   2511   713     21   100  3500
 * </pre>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class ScaleIngestBenchmarkTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScaleIngestBenchmarkTest.class);

    private static final String ANCHOR = "2020-12-02T00:00:00Z";
    private static final double MEDIAN_OVER_ANCHOR = 5.0;
    private static final double SCALING = 5.0;

    private final SoftAssertions softly = new SoftAssertions();

    static Stream<Arguments> backends() {
        return BenchMeters.backends();
    }

    private static int timesteps() {
        return Integer.getInteger("powsybl.bench.ingest.timesteps", BenchMeters.explicitGrids() ? 8 : 3);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void benchmark(String backend) {
        BenchMeters.logJvm(ScaleIngestBenchmarkTest.class);
        BenchMeters.FusekiMeter.install();
        List<String> table = new ArrayList<>();
        table.add(String.format(Locale.ROOT, "%-5s %-7s %-10s %3s %7s %6s %7s %7s %7s %6s %6s %7s %6s %6s %6s %8s %7s"
                        + " %6s %6s %5s %6s %4s",
                "grid", "backend", "shape", "n", "anchor", "anPkMB", "median", "first", "max", "parse", "mat",
                "diff", "write", "fwd", "rev", "data", "meta", "heapMB", "peakMB", "req", "srvms", "slow"));
        Map<String, Long> medians = new LinkedHashMap<>();
        for (BenchMeters.Grid grid : BenchMeters.grids("sv")) {
            for (Shape shape : List.of(Shape.RICH, Shape.STRUCTURAL)) {
                medians.put(grid.key() + "/" + shape, measureDay(backend, grid, shape, table));
            }
        }
        table.forEach(LOGGER::info);
        for (Shape shape : List.of(Shape.RICH, Shape.STRUCTURAL)) {
            Long six = medians.get("sv6/" + shape);
            Long twenty = medians.get("sv20/" + shape);
            if (six != null && twenty != null) {
                LOGGER.info("A {} {}: median sv20/sv6 = {}", backend, shape, BenchMeters.ratio(twenty, six));
                softly.assertThat(twenty / (double) Math.max(1, six)).as("ingestion sv20/sv6 of " + shape + " on "
                        + backend).isLessThanOrEqualTo(SCALING);
            }
        }
        softly.assertAll();
    }

    private long measureDay(String backend, BenchMeters.Grid grid, Shape shape, List<String> table) {
        int n = grid.replicas();
        String scenario = "scale-ingest-" + grid.key() + "-" + shape.name().toLowerCase(Locale.ROOT) + "-" + backend;
        GraphCache cache = new GraphCache().trustImmutableGraphs(true);
        try (RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, scenario).withCache(cache))) {
            db.clear(scenario);
            SnapshotCatalog catalog = db.snapshots(scenario);
            ReadOnlyDataSource[] anchorFiles = {ReplicatedSvedala.anchor(n, ANCHOR)};
            BenchMeters.resetPeak();
            long anchor = BenchMeters.millis(() -> catalog.putFull(anchorFiles[0], null,
                    SnapshotRef.of(scenario, "1.0"), BenchMeters.params(), ReportNode.NO_OP));
            long anchorPeak = BenchMeters.peakHeap();
            anchorFiles[0] = null;

            List<Long> walls = new ArrayList<>();
            List<Long> parse = new ArrayList<>();
            List<Long> mat = new ArrayList<>();
            List<Long> diff = new ArrayList<>();
            List<Long> write = new ArrayList<>();
            List<Long> fwd = new ArrayList<>();
            List<Long> rev = new ArrayList<>();
            List<Long> req = new ArrayList<>();
            List<Long> srv = new ArrayList<>();
            int slow = 0;
            int omitted = 0;
            int added = 0;
            BenchMeters.resetPeak();
            for (int i = 1; i <= timesteps(); i++) {
                String instant = SnapshotRef.canonicalTimestep(Instant.parse(ANCHOR)
                        .plus(Duration.ofMinutes(15L * i)).atZone(ZoneOffset.UTC));
                String label = String.format(Locale.ROOT, "%02d:%02d", i * 15 / 60, i * 15 % 60);
                SvedalaTimestepFixtures.TimestepFiles files = SvedalaTimestepFixtures.timestep(shape, i, instant);
                ReadOnlyDataSource replica = ReplicatedSvedala.replicate(files.dataSource(), n);
                omitted = Math.max(omitted, files.omittedObjects() * n);
                added += files.addedObjects() * n;
                SnapshotRef target = SnapshotRef.of("1.0", label, catalog);

                BenchMeters.FusekiMeter.Reading mark = BenchMeters.FusekiMeter.mark();
                long start = System.nanoTime();
                SnapshotInfo written = catalog.putAsDiff(replica, null, target, BenchMeters.params(),
                        ReportNode.NO_OP);
                long wall = (System.nanoTime() - start) / 1_000_000;
                BenchMeters.FusekiMeter.Reading reading = BenchMeters.FusekiMeter.since(mark);

                SnapshotCatalog.IngestStatistics s = catalog.lastIngestStatistics();
                walls.add(wall);
                parse.add(s.parse().toMillis());
                mat.add(s.materializeParent().toMillis());
                diff.add(s.diff().toMillis());
                write.add(s.write().toMillis());
                fwd.add((long) statements(s.forwardStatements()));
                rev.add((long) statements(s.reverseStatements()));
                req.add(reading.requests());
                srv.add(reading.serverMs());
                if (!written.fast()) {
                    slow++;
                }
                assertThat(fwd.get(fwd.size() - 1)).as("the difference of " + grid + " " + shape + " " + label
                        + " carries statements").isPositive();
            }
            long peak = BenchMeters.peakHeap();
            long[] store = measureStore(db, scenario);
            // The catalogue and its graph cache are still reachable here: what they and the scenario hold is the
            // difference to the heap once the scenario is cleared and the cache emptied
            long withDay = BenchMeters.heapAfterGc();
            db.clear(scenario);
            cache.clear();
            long held = Math.max(0, withDay - BenchMeters.heapAfterGc());
            LOGGER.info("{} / {} / {}: {} timestep(s), walls {}; omitted up to {} object(s), added {} object(s);"
                            + " graph cache {} hit(s) {} miss(es); {} graph(s) in the store; catalogue {} held",
                    grid, backend, shape, walls.size(), walls, omitted, added, cache.hits(), cache.misses(),
                    store[2], catalog.getClass().getSimpleName());

            long median = BenchMeters.median(walls);
            table.add(String.format(Locale.ROOT, "%-5s %-7s %-10s %3d %7d %6d %7d %7d %7d %6d %6d %7d %6d %6d %6d"
                            + " %8d %7d %6d %6d %5d %6d %4d",
                    grid.key(), backend, shape.name().toLowerCase(Locale.ROOT), walls.size(), anchor,
                    BenchMeters.mb(anchorPeak), median, walls.get(0), BenchMeters.max(walls),
                    BenchMeters.median(parse), BenchMeters.median(mat), BenchMeters.median(diff),
                    BenchMeters.median(write), BenchMeters.median(fwd), BenchMeters.median(rev), store[1], store[0],
                    BenchMeters.mb(held), BenchMeters.mb(peak), BenchMeters.median(req), BenchMeters.median(srv),
                    slow));
            db.clear(scenario);

            softly.assertThat((double) median).as("median " + shape + " ingestion of " + grid + " on " + backend
                    + " against the anchor putFull (" + anchor + " ms)").isLessThanOrEqualTo(MEDIAN_OVER_ANCHOR * anchor);
            if (shape == Shape.STRUCTURAL) {
                softly.assertThat(slow).as("every structural timestep takes the slow route").isEqualTo(walls.size());
            } else {
                softly.assertThat(slow).as("every rich timestep takes the fast route").isZero();
            }
            return median;
        }
    }

    /** Metadata triples, data triples, graphs of a scenario (the three counts of the ingestion benchmark). */
    private static long[] measureStore(RdfDbConnection db, String scenario) {
        String meta = RdfDbNames.metaGraph(scenario);
        String prefix = RdfDbNames.scenarioPrefix(scenario);
        long metaTriples = count(db, scenario, "SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + meta + "> { ?s ?p ?o } }");
        long allTriples = count(db, scenario, "SELECT (COUNT(*) AS ?n) WHERE { GRAPH ?g { ?s ?p ?o }"
                + " FILTER(STRSTARTS(STR(?g), \"" + prefix + "\")) }");
        long graphs = count(db, scenario, "SELECT (COUNT(DISTINCT ?g) AS ?n) WHERE { GRAPH ?g { ?s ?p ?o }"
                + " FILTER(STRSTARTS(STR(?g), \"" + prefix + "\")) }");
        return new long[] {metaTriples, allTriples - metaTriples, graphs - 1};
    }

    private static long count(RdfDbConnection db, String scenario, String query) {
        List<Map<String, Value>> rows = db.sparql(scenario).select(query);
        return rows.isEmpty() ? 0L : Long.parseLong(rows.get(0).get("n").stringValue());
    }

    private static int statements(Map<CgmesSubset, Integer> perProfile) {
        return perProfile.values().stream().mapToInt(Integer::intValue).sum();
    }
}
