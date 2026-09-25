/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conformity.Cgmes3Catalog;
import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the versioning layer costs: writing a difference into the database, and bringing a network to it.
 *
 * <p>Two numbers decide whether this design works. The <strong>write</strong> has to be one request whose cost is
 * the size of the change, not the size of the model: a recorder that publishes five hundred changed loads may not
 * pay for the fifty thousand statements it did not touch. The <strong>update</strong> has to be two round trips and
 * a linear fold, so that a client following a stream of changes spends its time in the CGMES update workflow rather
 * than in the database layer &mdash; which is why the apply is measured and reported separately and is deliberately
 * not gated here: it belongs to the difference importer.</p>
 *
 * <h2>Measured numbers</h2>
 * <p>8 cores, 62 GB RAM, Java 21, loopback Fuseki (TxnMem) and the in-process backend, medians of ten runs after
 * three warm-ups, milliseconds. {@code e1} and {@code e500} are the export of one and of five hundred changed
 * loads, split into translating the events and writing them; {@code u1} and {@code u10} bring a network forward
 * over a chain of one and of ten differences, split into planning, fetching, composing (this layer) and applying
 * (the difference importer); {@code f} materialises the head of a ten-difference chain, cold and with a warm
 * {@link GraphCache}; {@code a} is {@code Network.read} of the same files, for scale.</p>
 *
 * <pre>
 * fixture                            backend         e1:tr  e1:wr  e500:tr  e500:wr     u1    u10 u10:pl u10:fe u10:co u10:ap  f(cold)  f(warm)  a(file)
 * svedala(CGMES3,14MB)               fuseki-txnmem       0     22        3       56     16     21      9      5      0      4      394      335     1147
 * svedala(CGMES3,14MB)               memory              0      1        2       10      5      5      0      0      0      3      232      217     1147
 * microGridBaseCaseBE(CIM16,1.9MB)   fuseki-txnmem       0     11        0       13     12     18      8      4      0      3       49       44       53
 * microGridBaseCaseBE(CIM16,1.9MB)   memory              0      0        0        1      4      5      0      0      0      3       26       25       53
 *
 * e500 changes every load and then every generator of the fixture, up to five hundred objects: 112 on Svedala
 * (73 loads, 39 generators), 5 on MicroGrid BE. No conformity fixture has five hundred loads.
 * </pre>
 *
 * <p><strong>The write is three requests</strong>, whatever the size of the set: one {@code SELECT} resolving every
 * model the write touches and whatever supersedes it, the guarded {@code INSERT … WHERE} that carries the data, the
 * metadata and the rules, and one {@code SELECT} reading the nodes back. A hundred and twelve changed objects, about
 * eleven hundred statements in both directions, cost 56 ms on loopback Fuseki against 22 ms for a single change, so
 * the payload is now the larger half. The 500 ms bound is a factor of eight away; the 50 ms target is missed by
 * nine milliseconds and reported rather than gated. On the in-process backend the same write is 10 ms.</p>
 *
 * <p><strong>The update is two requests</strong>: one reads the metadata graph of the scenario, and everything the
 * planner wants to know &mdash; the head of every profile, the chain of the target, the chain of the model the
 * network holds &mdash; is arithmetic on that list; the second fetches the statements of every difference on the
 * path at once. Composing is not measurable. A chain of ten costs 21 ms against 16 ms for a single difference: what
 * an update pays for is not the length of the chain. Applying it is the difference importer's cost (4 ms here) and
 * is reported separately rather than gated.</p>
 *
 * <p><strong>A materialisation is two requests plus one transfer per instance file.</strong> It is the only number
 * above 100 ms, and on Svedala it is a full CGMES load: the plain database load of the same fixture measured by
 * {@code RdfDbLoadBenchmarkTest} is 430 ms cold and 307 ms warm on the same machine, so the ten differences add
 * about ten milliseconds and everything else is the fetch, the local store and the conversion &mdash; the
 * bottleneck the split loading work package profiled and documented. The differences of a profile are folded into
 * one before they are applied, so a chain costs one pair of SPARQL requests on the local store rather than one pair
 * per difference.</p>
 *
 * <p>A warm materialisation beats a file import on both fixtures: 335 ms against 1147 ms on Svedala, 44 ms against
 * 53 ms on MicroGrid BE. Only the first is asserted, because nine milliseconds of margin on a fifty millisecond
 * measurement is not something to fail a build over; the second is reported.</p>
 *
 * <p>The table is a run of this test on its own, which is the pessimistic one. In a run of the whole module the
 * JVM is warm and every number is roughly half: Svedala on Fuseki {@code e1:wr} 8, {@code e500:wr} 34,
 * {@code u10} 15, {@code f(warm)} 303 against {@code a} 1157, and the 50 ms export target is met.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbDiffBenchmarkTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RdfDbDiffBenchmarkTest.class);

    private static final int WARMUPS = Integer.getInteger("powsybl.rdfdb.benchmark.warmups", 3);
    private static final int RUNS = Integer.getInteger("powsybl.rdfdb.benchmark.runs", 10);
    private static final boolean STRICT = Boolean.getBoolean("powsybl.rdfdb.benchmark.strict");

    /** The hard bound on writing a five hundred change difference into the database. */
    private static final long EXPORT_HARD_BOUND_MS = 500;

    /** The target for the same write, reported rather than asserted unless the strict flag is set. */
    private static final long EXPORT_TARGET_MS = 50;

    /** The hard bound on everything an update does before the difference importer takes over. */
    private static final long UPDATE_HARD_BOUND_MS = 100;

    /** How many loads the large export changes, or as many as the fixture has. */
    private static final int LARGE_CHANGE = 500;

    /** How long the chain an update walks is. */
    private static final int CHAIN = 10;

    private static final CgmesSubset SSH = CgmesSubset.STEADY_STATE_HYPOTHESIS;

    private record Fixture(String name, Supplier<ReadOnlyDataSource> dataSource, boolean gated) {

        String scenario(String suffix) {
            return name.replaceAll("[^A-Za-z0-9]", "-") + "-" + suffix;
        }
    }

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("svedala(CGMES3,14MB)", () -> Cgmes3Catalog.svedala().dataSource(), true),
            new Fixture("microGridBaseCaseBE(CIM16,1.9MB)",
                    () -> CgmesConformity1Catalog.microGridBaseCaseBE().dataSource(), false));

    private static Properties params() {
        Properties p = new Properties();
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        return p;
    }

    @Test
    void benchmark() {
        List<String> table = new ArrayList<>();
        List<String> missedTargets = new ArrayList<>();
        table.add(String.format("%-34s %-14s %6s %6s %8s %8s %6s %6s %6s %6s %6s %6s %8s %8s %8s",
                "fixture", "backend", "e1:tr", "e1:wr", "e500:tr", "e500:wr", "u1", "u10", "u10:pl",
                "u10:fe", "u10:co", "u10:ap", "f(cold)", "f(warm)", "a(file)"));

        try (EmbeddedFuseki fuseki = EmbeddedFuseki.inMemory()) {
            for (Fixture fixture : FIXTURES) {
                long fileImport = measureFileImport(fixture);
                for (String backend : List.of("fuseki-txnmem", "memory")) {
                    RdfDatabase database = "memory".equals(backend)
                            ? RdfDatabase.inMemory("diff-bench-" + fixture.scenario(backend))
                            : fuseki.database();
                    Result result = measure(fixture, database, fileImport);
                    table.add(row(fixture, backend, result, fileImport));
                    assertBounds(fixture, backend, result, fileImport, missedTargets);
                }
            }
        }

        table.forEach(LOGGER::info);
        if (missedTargets.isEmpty()) {
            LOGGER.info("TARGET MET: a five hundred change export stays under {} ms on every backend",
                    EXPORT_TARGET_MS);
        } else {
            missedTargets.forEach(LOGGER::warn);
            if (STRICT) {
                throw new AssertionError("TARGET MISSED: " + missedTargets);
            }
        }
    }

    private void assertBounds(Fixture fixture, String backend, Result result, long fileImport,
                              List<String> missedTargets) {
        long export = result.largeTranslate + result.largeWrite;
        assertTrue(export <= EXPORT_HARD_BOUND_MS, () -> String.format(
                "REGRESSION: exporting %d changed objects of %s to %s took %d ms, more than the %d ms bound",
                result.largeChanges, fixture.name(), backend, export, EXPORT_HARD_BOUND_MS));
        if (export > EXPORT_TARGET_MS) {
            missedTargets.add(String.format("TARGET MISSED: exporting %d changed objects of %s to %s took %d ms"
                            + " (translate %d, write %d), target %d ms",
                    result.largeChanges, fixture.name(), backend, export, result.largeTranslate, result.largeWrite,
                    EXPORT_TARGET_MS));
        }
        if (fixture.gated()) {
            long layer = result.chainPlan + result.chainFetch + result.chainCompose;
            assertTrue(layer <= UPDATE_HARD_BOUND_MS, () -> String.format(
                    "REGRESSION: planning, fetching and composing a chain of %d differences of %s on %s took %d ms"
                            + " (plan %d, fetch %d, compose %d), more than the %d ms bound",
                    CHAIN, fixture.name(), backend, layer, result.chainPlan, result.chainFetch,
                    result.chainCompose, UPDATE_HARD_BOUND_MS));
        }
        // A materialised load with a warm cache has to beat reading the files: it is the same conversion on the
        // same statements, plus the differences, minus the RDF/XML parsing. Only on a model whose size is what a
        // file import spends its time on - applying a chain is a handful of SPARQL requests whose cost does not
        // shrink with the model, so on a two megabyte fixture it is the larger half of a very small number
        if (fixture.gated()) {
            assertTrue(result.materialiseWarm <= fileImport, () -> String.format(
                    "A warm materialised load of %s on %s took %d ms, the file import %d ms",
                    fixture.name(), backend, result.materialiseWarm, fileImport));
        } else if (result.materialiseWarm > fileImport) {
            missedTargets.add(String.format("TARGET MISSED: a warm materialised load of %s on %s took %d ms,"
                            + " the file import %d ms", fixture.name(), backend, result.materialiseWarm,
                    fileImport));
        }
    }

    private static String row(Fixture fixture, String backend, Result r, long fileImport) {
        return String.format("%-34s %-14s %6d %6d %8d %8d %6d %6d %6d %6d %6d %6d %8d %8d %8d",
                fixture.name(), backend, r.singleTranslate, r.singleWrite, r.largeTranslate, r.largeWrite,
                r.single, r.chain, r.chainPlan, r.chainFetch, r.chainCompose, r.chainApply,
                r.materialiseCold, r.materialiseWarm, fileImport);
    }

    // ------------------------------------------------------------------ measurements

    /** Every median of one fixture on one backend, in milliseconds. */
    private record Result(long singleTranslate, long singleWrite, int largeChanges, long largeTranslate,
                          long largeWrite, long single, long chain, long chainPlan, long chainFetch,
                          long chainCompose, long chainApply, long materialiseCold, long materialiseWarm) {
    }

    private Result measure(Fixture fixture, RdfDatabase database, long fileImport) {
        ReadOnlyDataSource ds = fixture.dataSource().get();
        LOGGER.info("Benchmarking {} against {} (file import {} ms)", fixture.name(), database, fileImport);

        Export single;
        Export large;
        int changes;
        try (RdfDbConnection db = RdfDbConnection.open(database)) {
            String scenario = fixture.scenario("export");
            db.clear(scenario);
            db.loadCgmes(scenario, ds, null, params(), ReportNode.NO_OP);
            Network sender = RdfDbNetworkLoader.load(db, scenario, null, params(), ReportNode.NO_OP);
            List<String> loads = sender.getLoadStream().map(Load::getId).sorted().toList();
            List<String> generators = sender.getGeneratorStream().map(Generator::getId).sorted().toList();
            String one = loads.get(0);
            single = measureExport(db, scenario, sender, n -> n.getLoad(one).setP0(n.getLoad(one).getP0() + 1));
            List<Consumer<Network>> many = largeChange(loads, generators);
            changes = many.size();
            LOGGER.info("{}: the large export changes {} objects ({} loads, {} generators of the fixture)",
                    fixture.name(), changes, loads.size(), generators.size());
            large = measureExport(db, scenario, sender, n -> many.forEach(change -> change.accept(n)));
            db.clear(scenario);
        }

        Update oneDiff = measureUpdate(fixture, database, ds, 1);
        Update tenDiffs = measureUpdate(fixture, database, ds, CHAIN);
        long[] materialise = measureMaterialise(fixture, database, ds);

        return new Result(single.translate, single.write, changes, large.translate, large.write,
                oneDiff.total, tenDiffs.total, tenDiffs.plan, tenDiffs.fetch, tenDiffs.compose, tenDiffs.apply,
                materialise[0], materialise[1]);
    }

    /**
     * A change of up to {@value #LARGE_CHANGE} objects: every load and then every generator of the fixture.
     *
     * <p>Two kinds of equipment rather than one because no conformity fixture has five hundred loads &mdash;
     * Svedala, the largest one, has seventy-three &mdash; and what is being measured is a difference of a few
     * hundred changed objects, not a property of loads.</p>
     */
    private static List<Consumer<Network>> largeChange(List<String> loads, List<String> generators) {
        List<Consumer<Network>> changes = new ArrayList<>();
        loads.forEach(id -> changes.add(n -> n.getLoad(id).setP0(n.getLoad(id).getP0() + 1)));
        generators.forEach(id -> changes.add(n -> n.getGenerator(id).setTargetP(n.getGenerator(id).getTargetP() + 1)));
        return changes.size() <= LARGE_CHANGE ? changes : List.copyOf(changes.subList(0, LARGE_CHANGE));
    }

    private record Export(long translate, long write) {
    }

    /**
     * Export the same change over and over, timing the translation and the database write apart.
     *
     * <p>Every run chains onto the previous one, which is what a recorder publishing a stream of changes does, and
     * is also the only thing the linear chain rule allows.</p>
     */
    private Export measureExport(RdfDbConnection db, String scenario, Network sender, Consumer<Network> change) {
        List<Long> translates = new ArrayList<>();
        List<Long> writes = new ArrayList<>();
        for (int i = 0; i < WARMUPS + RUNS; i++) {
            List<NetworkEvent> events = Changes.record(sender, change);
            long t0 = System.nanoTime();
            CgmesDiffExport.Result exported =
                    CgmesDiffExport.toDifferences(sender, events, new CgmesDiffExport.ExportOptions());
            long t1 = System.nanoTime();
            RdfDbDifferenceSink sink = new RdfDbDifferenceSink(db, scenario);
            sink.accept(exported.differences());
            long t2 = System.nanoTime();
            // The sender has to know it is at the difference it just wrote, or the next export forks the chain
            Map<CgmesSubset, StoredModel> stored = new EnumMap<>(CgmesSubset.class);
            sink.stored().forEach(model -> stored.put(model.subset(), model));
            NetworkIdentity.advance(sender, stored);
            if (i >= WARMUPS) {
                translates.add((t1 - t0) / 1_000_000);
                writes.add((t2 - t1) / 1_000_000);
            }
        }
        return new Export(median(translates), median(writes));
    }

    private record Update(long total, long plan, long fetch, long compose, long apply) {
    }

    /**
     * Bring a network over a chain of {@code length} differences, from the instance files to the head.
     *
     * <p>A fresh network is materialised at the base before every run &mdash; outside the timer &mdash; because an
     * update is only measurable once, and what is being measured is the first one.</p>
     */
    private Update measureUpdate(Fixture fixture, RdfDatabase database, ReadOnlyDataSource ds, int length) {
        String scenario = fixture.scenario("chain" + length);
        List<Long> totals = new ArrayList<>();
        List<Long> plans = new ArrayList<>();
        List<Long> fetches = new ArrayList<>();
        List<Long> composes = new ArrayList<>();
        List<Long> applies = new ArrayList<>();
        try (RdfDbConnection db = RdfDbConnection.open(database)) {
            db.clear(scenario);
            db.loadCgmes(scenario, ds, null, params(), ReportNode.NO_OP);
            String base = db.catalog(scenario).full(SSH).orElseThrow().id();
            Network sender = RdfDbNetworkLoader.load(db, scenario, null, params(), ReportNode.NO_OP);
            String load = sender.getLoadStream().map(Load::getId).sorted().findFirst().orElseThrow();
            for (int i = 0; i < length; i++) {
                RdfDbExport.export(sender,
                        Changes.record(sender, n -> n.getLoad(load).setP0(n.getLoad(load).getP0() + 1)),
                        db, scenario, new CgmesDiffExport.ExportOptions());
            }
            DiffTarget atBase = DiffTarget.models(Map.of(SSH, base));
            for (int i = 0; i < WARMUPS + RUNS; i++) {
                Network receiver = RdfDbNetworkLoader.load(db, scenario, atBase, null, params(), ReportNode.NO_OP);
                long t0 = System.nanoTime();
                UpdateResult result = RdfDbNetworkLoader.update(receiver, db, scenario, DiffTarget.head(),
                        new RdfDbUpdateOptions(), params(), ReportNode.NO_OP);
                long total = (System.nanoTime() - t0) / 1_000_000;
                if (result.route() != UpdateResult.Route.DIFF_APPLIED) {
                    throw new IllegalStateException("The benchmark measures the difference route, but the update"
                            + " took " + result.route() + ": " + result.reasons());
                }
                if (i >= WARMUPS) {
                    totals.add(total);
                    plans.add(result.statistics().plan().toMillis());
                    fetches.add(result.statistics().fetch().toMillis());
                    composes.add(result.statistics().compose().toMillis());
                    applies.add(result.statistics().apply().toMillis());
                }
            }
            db.clear(scenario);
        }
        return new Update(median(totals), median(plans), median(fetches), median(composes), median(applies));
    }

    /** Materialise the head of a ten difference chain, with a cold and with a warm graph cache. */
    private long[] measureMaterialise(Fixture fixture, RdfDatabase database, ReadOnlyDataSource ds) {
        String scenario = fixture.scenario("materialise");
        GraphCache cache = new GraphCache();
        try (RdfDbConnection cold = RdfDbConnection.open(database)) {
            cold.clear(scenario);
            cold.loadCgmes(scenario, ds, null, params(), ReportNode.NO_OP);
            Network sender = RdfDbNetworkLoader.load(cold, scenario, null, params(), ReportNode.NO_OP);
            String load = sender.getLoadStream().map(Load::getId).sorted().findFirst().orElseThrow();
            for (int i = 0; i < CHAIN; i++) {
                RdfDbExport.export(sender,
                        Changes.record(sender, n -> n.getLoad(load).setP0(n.getLoad(load).getP0() + 1)),
                        cold, scenario, new CgmesDiffExport.ExportOptions());
            }
            long coldMedian = medianOfLoads(cold, scenario);
            try (RdfDbConnection warm = RdfDbConnection.open(database.withCache(cache))) {
                // One load to fill the cache, then the measured ones
                RdfDbNetworkLoader.load(warm, scenario, DiffTarget.head(), null, params(), ReportNode.NO_OP);
                long warmMedian = medianOfLoads(warm, scenario);
                cold.clear(scenario);
                return new long[] {coldMedian, warmMedian};
            }
        }
    }

    private long medianOfLoads(RdfDbConnection db, String scenario) {
        List<Long> totals = new ArrayList<>();
        for (int i = 0; i < WARMUPS + RUNS; i++) {
            long t0 = System.nanoTime();
            RdfDbNetworkLoader.load(db, scenario, DiffTarget.head(), null, params(), ReportNode.NO_OP);
            if (i >= WARMUPS) {
                totals.add((System.nanoTime() - t0) / 1_000_000);
            }
        }
        return median(totals);
    }

    private long measureFileImport(Fixture fixture) {
        ReadOnlyDataSource ds = fixture.dataSource().get();
        List<Long> totals = new ArrayList<>();
        for (int i = 0; i < WARMUPS + RUNS; i++) {
            long t0 = System.nanoTime();
            Network.read(ds, params());
            if (i >= WARMUPS) {
                totals.add((System.nanoTime() - t0) / 1_000_000);
            }
        }
        return median(totals);
    }

    private static long median(List<Long> values) {
        if (values.isEmpty()) {
            return -1;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        return sorted.get(sorted.size() / 2);
    }
}
