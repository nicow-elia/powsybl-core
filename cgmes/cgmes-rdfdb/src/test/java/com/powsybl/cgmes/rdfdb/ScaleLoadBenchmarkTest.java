/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.model.CgmesModel;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.commons.config.PlatformConfig;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Part B and F of the scale campaign: what loading a network out of the database costs, from the MicroGrid to an
 * IGM-sized Svedala replica, and what a day of ninety-six variants costs at that size.
 *
 * <p><b>B, per grid and backend.</b> {@code a} = a file import ({@code CgmesImport.readCgmes} + {@code convert},
 * the split of {@link RdfDbLoadBenchmarkTest}); {@code u} = the one-off {@code loadCgmes} upload; {@code b} = a
 * cold {@link RdfDbNetworkLoader#loadWithStatistics} on a fresh connection, with its phases; {@code c} = the same
 * load with a {@link GraphCache} filled once; heap after GC holding the network of {@code b} and the peak of the
 * JVM during the last {@code b}; Fuseki requests and server milliseconds. Then the versioned side on the same
 * grid: {@code putFull} and a chain of fifty one-load steps ({@code putDiff}), {@code m50} = a warm load of the
 * top (cached connection, median), {@code u50} = an update of a network at the root to the top, {@code ck} =
 * {@link Checkpoint#create}, {@code mck} = the warm load after it.</p>
 *
 * <p><b>F</b> (grids of {@code -Dpowsybl.bench.variants.grids}, default {@code sv6,sv20} in a campaign run and none
 * in the ordinary build): a day of 96 rich SSH timesteps (every generator {@code targetP} and every load
 * {@code p0} moves), each one difference from the base, loaded with {@link RdfDbNetworkLoader#loadVariants}:
 * first and warm, phases, per-variant apply median and max, {@code sep} = 96 separate loads <em>extrapolated from
 * three</em> (a real 96 at IGM size would cost minutes and says nothing three do not), {@code walk} = 96 updates of
 * one network, and the heap the 95 extra variants hold. {@code sv20} and above on {@code memory:} only.</p>
 *
 * <p>Gates, relative only: {@code b <= 1.5 a}, {@code c < b} and {@code c <= a} on Fuseki (in process a cache
 * saves only a local copy, so warm and cold are equal within noise there, as in {@link RdfDbLoadBenchmarkTest});
 * on the small grids (be, snb, sv1) the warm-against-cold gate is the regression bound {@code c <= 1.5 b + 30 ms}
 * and {@code c < b} is reported as a target, because what the cache saves there is a few tens of milliseconds and
 * a whole-module JVM moves a load by more than that (the gate failed once at 1065 vs 765 ms on sv1 inside
 * {@code verify} and passed 322 vs 513 ms alone); {@code b(sv20)/b(sv6)
 * <= 5} and {@code a(sv20)/a(sv6) <= 5}; {@code mck <= 1.5 c + 30 ms} (the
 * catalogue query a versioned load adds is a fixed cost as large as a whole MicroGrid load); for F {@code lv(warm) < sep}, per-variant apply
 * max {@code <= 20 x} median and {@code lv(sv20)/lv(sv6) <= 5}. Reported: {@code m50} against {@code a}.</p>
 *
 * <h2>Measured numbers</h2>
 * <p>8 cores, Java 21, {@code -Xmx24g}, 2026-09-22, milliseconds, medians (full tables in the campaign report
 * {@code 16-benchmark-campaign.md}; {@code parse} is summed over the parallel fetch threads):</p>
 * <pre>
 * grid  backend       a      b  fetch  parse  store   conv      c    m50    ck    mck   b/a
 * sv1   fuseki     1300    395     57    337    154    163    269    313   209    293  0.30
 * sv6   fuseki     3158   2124    173   1641    923    870   1587   1581   684   1638  0.67
 * sv20  fuseki    10910   7471    698   6197   3188   3264   6410   6295  2327   6248  0.68
 * sv1   memory     1339    258      2     18     62    174    238    241    45    237  0.19
 * sv6   memory     3402   1484      9     93    414    961   1452   1480   138   1429  0.44
 * sv20  memory    11463   5191     27    297   1606   3204   5060   5149   443   5170  0.45
 *
 * F (96 rich timesteps)   lv(warm)  apply  per variant med/max   sep (96 x 3 sampled)  walk  heap of 95 variants
 * sv6  fuseki                 8870   2409        25.1 / 33.3                212544  17469                 5 MB
 * sv6  memory                 4015   2361        24.6 / 27.6                135360   4448                 2 MB
 * sv20 memory                13968   7646        79.1 / 156.5               524256  14869                10 MB
 * after T6 #1 (plan built once per variant; logs/bench16-t6-1-after.log) and #2 (step graphs by GSP GETs when
 * they are large; logs/bench16-t6-2-after.log, fetch 4306 -> 530 ms):
 * sv6  fuseki                 4847   2069        21.5 / 37.7                220416   6220                 3 MB
 * sv6  memory                 3674   1983        20.9 / 23.7                135648   4114                 1 MB
 * </pre>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class ScaleLoadBenchmarkTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScaleLoadBenchmarkTest.class);

    private static final String ANCHOR = "2020-12-02T00:00:00Z";
    private static final int CHAIN = 50;
    private static final int TIMESTEPS = 96;
    private static final int SEPARATE_SAMPLE = 3;
    private static final long CATALOGUE_FLOOR_MS = 30;
    private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
    private static final String CIM100 = "http://iec.ch/TC57/CIM100#";
    private static final String SSH16 = "http://entsoe.eu/CIM/SteadyStateHypothesis/1/1";
    private static final String SSH3 = "http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/3.0";

    /** Gates are collected, so that one failing grid does not hide the measurements of the next ones. */
    private final SoftAssertions softly = new SoftAssertions();

    static Stream<Arguments> backends() {
        return BenchMeters.backends();
    }

    /** One grid on one backend, for the scaling gates. */
    private record Measured(long a, long b, long c) {
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void benchmark(String backend) {
        BenchMeters.logJvm(ScaleLoadBenchmarkTest.class);
        BenchMeters.FusekiMeter.install();
        List<String> table = new ArrayList<>();
        table.add(String.format(Locale.ROOT, "%-5s %-7s %7s %6s %6s %7s %7s %5s %6s %6s %6s %5s %6s %7s %6s %6s %5s %6s"
                        + " %5s %5s | %6s %6s %5s %5s %5s %5s %6s %6s %5s",
                "grid", "backend", "a", "a:rd", "a:cv", "u", "b", "lg", "fetch", "parse", "store", "desc", "conv",
                "c", "heapMB", "peakMB", "req", "srvms", "b/a", "c/a",
                "m50", "u50", "u:pl", "u:fe", "u:co", "u:ap", "ck", "mck", "mck/c"));
        Map<String, Measured> measured = new LinkedHashMap<>();
        for (BenchMeters.Grid grid : BenchMeters.grids("be", "snb", "sv")) {
            if (Backends.FUSEKI.equals(backend) && grid.replicas() >= 40) {
                LOGGER.info("{} on {}: skipped, the memory-ceiling grid runs on memory: only", grid, backend);
                continue;
            }
            measured.put(grid.key(), measureGrid(backend, grid, table));
        }
        table.forEach(LOGGER::info);
        scalingGate(backend, measured);

        List<String> variantGrids = variantGrids();
        List<String> variantTable = new ArrayList<>();
        Map<String, Long> lvWarm = new LinkedHashMap<>();
        for (String key : variantGrids) {
            int n = Integer.parseInt(key.substring(2));
            if (Backends.FUSEKI.equals(backend) && n >= 20) {
                LOGGER.info("F on {} / {}: skipped, IGM-scale variants run on memory: only", key, backend);
                continue;
            }
            lvWarm.put(key, measureVariants(backend, key, n, variantTable));
        }
        variantTable.forEach(LOGGER::info);
        if (lvWarm.containsKey("sv6") && lvWarm.containsKey("sv20")) {
            double scaling = lvWarm.get("sv20") / (double) Math.max(1, lvWarm.get("sv6"));
            LOGGER.info("F {}: lv(warm) sv20/sv6 = {}", backend, String.format(Locale.ROOT, "%.2f", scaling));
            softly.assertThat(scaling).as("loadVariants sv20 against sv6 on " + backend).isLessThanOrEqualTo(5.0);
        }
        softly.assertAll();
    }

    private static List<String> variantGrids() {
        String configured = System.getProperty("powsybl.bench.variants.grids",
                BenchMeters.explicitGrids() ? "sv6,sv20" : "");
        List<String> selected = BenchMeters.grids("sv").stream().map(BenchMeters.Grid::key).toList();
        return Arrays.stream(configured.split(",")).map(String::trim).filter(k -> !k.isEmpty())
                .filter(selected::contains).toList();
    }

    // ------------------------------------------------------------------ B

    private Measured measureGrid(String backend, BenchMeters.Grid grid, List<String> table) {
        Properties p = BenchMeters.params();
        ReadOnlyDataSource ds = grid.dataSource();
        int warmups = BenchMeters.warmups(grid);
        int runs = BenchMeters.runs(grid);

        // (a) the file import
        CgmesImport importer = new CgmesImport(PlatformConfig.defaultConfig());
        List<Long> aTotal = new ArrayList<>();
        List<Long> aRead = new ArrayList<>();
        List<Long> aConvert = new ArrayList<>();
        Network fileNetwork = null;
        for (int i = 0; i < warmups + runs; i++) {
            fileNetwork = null;
            long t0 = System.nanoTime();
            CgmesModel cgmes = importer.readCgmes(ds, p, ReportNode.NO_OP);
            long t1 = System.nanoTime();
            fileNetwork = importer.convert(cgmes, ds.getBaseName(), NetworkFactory.findDefault(), p,
                    ReportNode.NO_OP);
            long t2 = System.nanoTime();
            if (i >= warmups) {
                aRead.add((t1 - t0) / 1_000_000);
                aConvert.add((t2 - t1) / 1_000_000);
                aTotal.add((t2 - t0) / 1_000_000);
            }
        }
        long a = BenchMeters.median(aTotal);
        Load chainLoad = fileNetwork.getLoads().iterator().next();
        String loadId = chainLoad.getId();
        String loadClass = chainLoad.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS, "EnergyConsumer");
        LOGGER.info("{}: {} terminals, {} switches, {} loads, {} generators; chain load {} ({})", grid,
                ReplicatedSvedalaTest.terminals(fileNetwork), fileNetwork.getSwitchCount(),
                fileNetwork.getLoadCount(), fileNetwork.getGeneratorCount(), loadId, loadClass);
        long withFileNetwork = BenchMeters.heapAfterGc();
        fileNetwork = null;
        LOGGER.info("{}: the file-imported network holds {} MB after GC", grid,
                BenchMeters.mb(Math.max(0, withFileNetwork - BenchMeters.heapAfterGc())));

        String scenario = "scale-load-" + grid.key() + "-" + backend;
        RdfDatabase database = Backends.database(backend, scenario);
        // An in-process database lives as long as a connection to it is open: this one keeps it for the grid
        try (RdfDbConnection holder = RdfDbConnection.open(database)) {
            return measureDatabase(backend, grid, table, database, scenario, a, BenchMeters.median(aRead),
                    BenchMeters.median(aConvert), loadId, loadClass);
        }
    }

    private Measured measureDatabase(String backend, BenchMeters.Grid grid, List<String> table, RdfDatabase database,
                                     String scenario, long a, long aRead, long aConvert, String loadId,
                                     String loadClass) {
        Properties p = BenchMeters.params();
        ReadOnlyDataSource ds = grid.dataSource();
        int warmups = BenchMeters.warmups(grid);
        int runs = BenchMeters.runs(grid);

        // (u) the upload
        long upload;
        BenchMeters.FusekiMeter.Reading upReq;
        try (RdfDbConnection db = RdfDbConnection.open(database)) {
            db.clear(scenario);
            BenchMeters.FusekiMeter.Reading mark = BenchMeters.FusekiMeter.mark();
            upload = BenchMeters.millis(() -> db.loadCgmes(scenario, ds, null, p, ReportNode.NO_OP));
            upReq = BenchMeters.FusekiMeter.since(mark);
        }

        // (b) cold loads on a fresh connection each
        List<Long> bTotal = new ArrayList<>();
        Map<String, List<Long>> phases = new LinkedHashMap<>();
        List<Long> requests = new ArrayList<>();
        List<Long> serverMs = new ArrayList<>();
        Network held = null;
        long statements = 0;
        for (int i = 0; i < warmups + runs; i++) {
            held = null;
            if (i == warmups + runs - 1) {
                BenchMeters.resetPeak();
            }
            try (RdfDbConnection db = RdfDbConnection.open(database)) {
                BenchMeters.FusekiMeter.Reading mark = BenchMeters.FusekiMeter.mark();
                long start = System.nanoTime();
                RdfDbNetworkLoader.LoadResult result = RdfDbNetworkLoader.loadWithStatistics(db, scenario,
                        NetworkFactory.findDefault(), p, ReportNode.NO_OP);
                long wall = (System.nanoTime() - start) / 1_000_000;
                BenchMeters.FusekiMeter.Reading reading = BenchMeters.FusekiMeter.since(mark);
                held = result.network();
                if (i >= warmups) {
                    LoadStatistics s = result.statistics();
                    bTotal.add(wall);
                    add(phases, "listGraphs", s.listGraphs());
                    add(phases, "fetch", s.fetch());
                    add(phases, "parse", s.parse());
                    add(phases, "store", s.store());
                    add(phases, "describe", s.describe());
                    add(phases, "convert", s.convert());
                    requests.add(reading.requests());
                    serverMs.add(reading.serverMs());
                    statements = s.statements();
                }
            }
        }
        long peak = BenchMeters.peakHeap();
        // The network of the last cold load, held and then released: the difference is what it costs
        long withNetwork = BenchMeters.heapAfterGc();
        String networkId = held.getId();
        held = null;
        long heldHeap = Math.max(0, withNetwork - BenchMeters.heapAfterGc());
        LOGGER.info("{} / {}: cold load holds {} statements; network {} holds {} MB after GC ({} MB used with it)",
                grid, backend, statements, networkId, BenchMeters.mb(heldHeap), BenchMeters.mb(withNetwork));
        long b = BenchMeters.median(bTotal);

        // (c) warm: a graph cache filled once
        RdfDatabase cachedDatabase = database.withCache(new GraphCache().trustImmutableGraphs(true));
        long c;
        try (RdfDbConnection db = RdfDbConnection.open(cachedDatabase)) {
            RdfDbNetworkLoader.load(db, scenario, NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            c = BenchMeters.median(warmups, runs, () -> BenchMeters.millis(() ->
                    RdfDbNetworkLoader.load(db, scenario, NetworkFactory.findDefault(), p, ReportNode.NO_OP)));
            db.clear(scenario);
        }

        // (m) the versioned side
        Versioned v = measureVersioned(backend, grid, database, loadId, loadClass);

        table.add(String.format(Locale.ROOT, "%-5s %-7s %7d %6d %6d %7d %7d %5d %6d %6d %6d %5d %6d %7d %6d %6d %5d"
                        + " %6d %5s %5s | %6d %6d %5d %5d %5d %5d %6d %6d %5s",
                grid.key(), backend, a, aRead, aConvert, upload, b,
                med(phases, "listGraphs"), med(phases, "fetch"), med(phases, "parse"), med(phases, "store"),
                med(phases, "describe"), med(phases, "convert"), c, BenchMeters.mb(heldHeap),
                BenchMeters.mb(peak), BenchMeters.median(requests), BenchMeters.median(serverMs),
                BenchMeters.ratio(b, a), BenchMeters.ratio(c, a), v.m50, v.u50[0], v.u50[1], v.u50[2], v.u50[3],
                v.u50[4], v.checkpoint, v.afterCheckpoint, BenchMeters.ratio(v.afterCheckpoint, c)));
        LOGGER.info("{} / {}: upload {} ms ({} request(s), {} server ms); versioned: m50 {} request(s) {} server ms,"
                        + " u50 {} request(s), mck {} request(s)", grid, backend, upload, upReq.requests(),
                upReq.serverMs(), v.m50Req.requests(), v.m50Req.serverMs(), v.u50Req.requests(),
                v.mckReq.requests());
        if (v.m50 > a) {
            LOGGER.info("TARGET MISSED on {} / {}: a warm versioned load at depth {} takes {} ms against {} ms for"
                    + " a file import", grid, backend, CHAIN, v.m50, a);
        } else {
            LOGGER.info("TARGET MET on {} / {}: a warm versioned load at depth {} takes {} ms against {} ms for a"
                    + " file import", grid, backend, CHAIN, v.m50, a);
        }

        // Gates, relative only
        // The three load gates are the server's, as in RdfDbLoadBenchmarkTest: in process a cache saves only a
        // copy between two local stores, so warm and cold are the same figure within noise there
        if (Backends.FUSEKI.equals(backend)) {
            softly.assertThat((double) b).as("cold database load of " + grid + " against the file import (" + a + " ms)")
                    .isLessThanOrEqualTo(1.5 * a);
            // What the cache saves is the fetch and the N-Triples parse. On a replicated grid that is a quarter of
            // the load and the comparison is safe; on a small grid it is a few tens of milliseconds, which the JVM
            // of a whole module run (a shared in-process Fuseki holding every earlier test's datasets, an old
            // generation full of them) moves around by more than that in either direction. So the strict "warm
            // beats cold" is asserted where it can be measured and, on the small grids, replaced by the regression
            // bound of the versioned side: a warm load that costs more than 1.5 x the cold one plus the floor is
            // broken (reviewer decision, campaign review 16; the strict figure is still reported as a target)
            if (grid.small()) {
                softly.assertThat((double) c).as("warm load of " + grid + " on " + backend + " against the cold one ("
                        + b + " ms, regression bound)").isLessThanOrEqualTo(1.5 * b + CATALOGUE_FLOOR_MS);
                LOGGER.info("TARGET {} on {} / {}: a warm load takes {} ms against {} ms cold", c < b ? "MET" : "MISSED",
                        grid, backend, c, b);
            } else {
                softly.assertThat(c).as("warm load of " + grid + " on " + backend + " against the cold one").isLessThan(b);
            }
            softly.assertThat(c).as("warm load of " + grid + " on " + backend + " against the file import")
                    .isLessThanOrEqualTo(a);
        }
        // A checkpointed versioned load is a plain load plus the catalogue query; the query is a fixed cost of a few
        // tens of milliseconds, which on the MicroGrid is as large as the load itself, hence the absolute floor
        softly.assertThat((double) v.afterCheckpoint).as("warm load after a checkpoint of " + grid + " on " + backend
                + " against the plain warm load (" + c + " ms)").isLessThanOrEqualTo(1.5 * c + CATALOGUE_FLOOR_MS);
        return new Measured(a, b, c);
    }

    private void scalingGate(String backend, Map<String, Measured> measured) {
        Measured six = measured.get("sv6");
        Measured twenty = measured.get("sv20");
        if (six == null || twenty == null) {
            return;
        }
        LOGGER.info("B {}: sv20/sv6 a {} b {} c {}", backend, BenchMeters.ratio(twenty.a, six.a),
                BenchMeters.ratio(twenty.b, six.b), BenchMeters.ratio(twenty.c, six.c));
        softly.assertThat(twenty.b / (double) six.b).as("cold load sv20/sv6 on " + backend).isLessThanOrEqualTo(5.0);
        softly.assertThat(twenty.a / (double) six.a).as("file import sv20/sv6").isLessThanOrEqualTo(5.0);
    }

    /** The versioned side of one grid. */
    private record Versioned(long m50, long[] u50, long checkpoint, long afterCheckpoint,
                             BenchMeters.FusekiMeter.Reading m50Req, BenchMeters.FusekiMeter.Reading u50Req,
                             BenchMeters.FusekiMeter.Reading mckReq) {
    }

    private Versioned measureVersioned(String backend, BenchMeters.Grid grid, RdfDatabase database, String loadId,
                                       String loadClass) {
        Properties p = BenchMeters.params();
        String scenario = "scale-load-v-" + grid.key() + "-" + backend;
        boolean cim16 = !grid.svedala();
        try (RdfDbConnection db = RdfDbConnection.open(database.withCache(
                new GraphCache().trustImmutableGraphs(true)))) {
            db.clear(scenario);
            SnapshotCatalog catalog = db.snapshots(scenario);
            SnapshotInfo head = catalog.putFull(grid.dataSource(), null, SnapshotRef.of(scenario, "1.0"), p,
                    ReportNode.NO_OP);
            SnapshotRef root = head.ref();
            for (int i = 1; i <= CHAIN; i++) {
                head = catalog.putDiff(step(head, i, loadId, loadClass, cim16), SnapshotRef.of(scenario, "1." + i));
            }
            SnapshotRef top = head.ref();
            int warmups = BenchMeters.warmups(grid);
            int runs = BenchMeters.runs(grid);

            RdfDbNetworkLoader.load(db, top, null, p, ReportNode.NO_OP);
            BenchMeters.FusekiMeter.Reading mark = BenchMeters.FusekiMeter.mark();
            RdfDbNetworkLoader.load(db, top, null, p, ReportNode.NO_OP);
            BenchMeters.FusekiMeter.Reading m50Req = BenchMeters.FusekiMeter.since(mark);
            long m50 = BenchMeters.median(warmups, runs, () -> BenchMeters.millis(() ->
                    RdfDbNetworkLoader.load(db, top, null, p, ReportNode.NO_OP)));

            Network client = RdfDbNetworkLoader.load(db, root, null, p, ReportNode.NO_OP);
            mark = BenchMeters.FusekiMeter.mark();
            UpdateResult result = RdfDbNetworkLoader.update(client, db, top, new RdfDbUpdateOptions(), p,
                    ReportNode.NO_OP);
            BenchMeters.FusekiMeter.Reading u50Req = BenchMeters.FusekiMeter.since(mark);
            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            UpdateStatistics s = result.statistics();
            long[] u50 = {s.total().toMillis(), s.plan().toMillis(), s.fetch().toMillis(), s.compose().toMillis(),
                s.apply().toMillis()};

            long checkpoint = BenchMeters.millis(() -> Checkpoint.create(db, top));
            RdfDbNetworkLoader.load(db, top, null, p, ReportNode.NO_OP);
            mark = BenchMeters.FusekiMeter.mark();
            RdfDbNetworkLoader.load(db, top, null, p, ReportNode.NO_OP);
            BenchMeters.FusekiMeter.Reading mckReq = BenchMeters.FusekiMeter.since(mark);
            long afterCheckpoint = BenchMeters.median(warmups, runs, () -> BenchMeters.millis(() ->
                    RdfDbNetworkLoader.load(db, top, null, p, ReportNode.NO_OP)));
            db.clear(scenario);
            return new Versioned(m50, u50, checkpoint, afterCheckpoint, m50Req, u50Req, mckReq);
        }
    }

    /** One step of the chain: one load's {@code p}, as {@link RdfDbVersioningBenchmarkTest} does. */
    static DifferenceModelSet step(SnapshotInfo parent, int index, String loadId, String loadClass, boolean cim16) {
        CgmesSubset ssh = CgmesSubset.STEADY_STATE_HYPOTHESIS;
        DifferenceModelHeader header = DifferenceModelHeader.builder("urn:uuid:scale-ssh-" + index, ssh,
                        cim16 ? CIM16 : CIM100)
                .supersedes(List.of(parent.state().get(ssh)))
                .profiles(List.of(cim16 ? SSH16 : SSH3))
                .build();
        return new DifferenceModelSet(List.of(new DifferenceModel(header,
                List.of(CgmesStatement.literal(loadId, loadClass, "EnergyConsumer.p", String.valueOf(10.0 + index))),
                List.of(CgmesStatement.literal(loadId, loadClass, "EnergyConsumer.p", "0.0")),
                List.of())));
    }

    // ------------------------------------------------------------------ F

    private long measureVariants(String backend, String key, int n, List<String> table) {
        Properties p = BenchMeters.params();
        String scenario = "scale-var-" + key + "-" + backend;
        List<String> timesteps = new ArrayList<>();
        for (int i = 0; i < TIMESTEPS; i++) {
            timesteps.add(SnapshotRef.canonicalTimestep(Instant.parse(ANCHOR).plus(Duration.ofMinutes(15L * i))
                    .atZone(ZoneOffset.UTC)));
        }
        int runs = Integer.getInteger("powsybl.bench.runs", n >= 20 ? 1 : 3);
        try (RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, scenario))) {
            db.clear(scenario);
            long build = BenchMeters.millis(() -> buildDay(db, scenario, n, timesteps));

            BenchMeters.resetPeak();
            long first = BenchMeters.millis(() -> loadDay(db, scenario, timesteps));
            long peak = BenchMeters.peakHeap();
            List<Long> warm = new ArrayList<>();
            VariantLoadResult last = null;
            for (int i = 0; i < runs; i++) {
                last = null;
                long start = System.nanoTime();
                last = loadDay(db, scenario, timesteps);
                warm.add((System.nanoTime() - start) / 1_000_000);
            }
            long lvWarm = BenchMeters.median(warm);
            assertThat(last.refused()).isEmpty();
            assertThat(last.bound()).hasSize(TIMESTEPS);
            long[] applies = last.bound().stream().mapToLong(o -> o.apply().toNanos()).sorted().toArray();
            long applyMedian = applies[applies.length / 2];
            long applyMax = applies[applies.length - 1];
            String phases = String.format(Locale.ROOT, "plan %d fetch %d clone %d apply %d",
                    last.plan().toMillis(), last.fetch().toMillis(), last.cloning().toMillis(),
                    last.applyTotal().toMillis());
            int statements = last.bound().stream().mapToInt(VariantOutcome::diffCount).sum();
            last = null;

            // (sep) separate loads, extrapolated from a sample of three
            List<Long> separate = new ArrayList<>();
            for (int i = 0; i < SEPARATE_SAMPLE; i++) {
                String timestep = timesteps.get(1 + i * 31);
                separate.add(BenchMeters.millis(() -> RdfDbNetworkLoader.load(db, scenario, "1.0", timestep, null, p,
                        ReportNode.NO_OP)));
            }
            long sep = TIMESTEPS * BenchMeters.median(separate);

            // (walk) one network updated timestep by timestep
            long walk = BenchMeters.millis(() -> {
                Network network = RdfDbNetworkLoader.load(db, scenario, "1.0", timesteps.get(0), null, p,
                        ReportNode.NO_OP);
                for (String timestep : timesteps) {
                    RdfDbNetworkLoader.update(network, db, new SnapshotRef(scenario, "1.0", timestep),
                            new RdfDbUpdateOptions(), p, ReportNode.NO_OP);
                }
            });

            long heap = heapDeltaOfTheVariants(db, scenario, timesteps);
            table.add(String.format(Locale.ROOT, "F %-5s %-7s build=%d lv(first)=%d lv(warm)=%d [%s, per variant"
                            + " median %.1f max %.1f ms, %d diff(s)] sep(extrapolated 96x%d)=%d walk=%d"
                            + " heap(95 variants)=%d MB peak(first lv)=%d MB", key, backend, build, first, lvWarm,
                    phases, applyMedian / 1e6, applyMax / 1e6, statements, BenchMeters.median(separate), sep, walk,
                    BenchMeters.mb(heap), BenchMeters.mb(peak)));
            db.clear(scenario);

            softly.assertThat(lvWarm).as("a day as variants of " + key + " on " + backend
                    + " against 96 separate loads (extrapolated)").isLessThan(sep);
            softly.assertThat((double) applyMax).as("the slowest per-variant apply of " + key + " on " + backend
                    + " against the median (" + applyMedian / 1e6 + " ms)").isLessThanOrEqualTo(20.0 * applyMedian);
            return lvWarm;
        }
    }

    private static VariantLoadResult loadDay(RdfDbConnection db, String scenario, List<String> timesteps) {
        return RdfDbNetworkLoader.loadVariants(db, scenario, "1.0", timesteps, new RdfDbVariantLoadOptions(), null,
                BenchMeters.params(), ReportNode.NO_OP);
    }

    /** 95 rich timesteps, each one difference from the base, exported from a network brought back to the base. */
    private static void buildDay(RdfDbConnection db, String scenario, int n, List<String> timesteps) {
        Properties p = BenchMeters.params();
        db.snapshots(scenario).putFull(ReplicatedSvedala.anchor(n, ANCHOR), null, SnapshotRef.of(scenario, "1.0"),
                p, ReportNode.NO_OP);
        Network sender = RdfDbNetworkLoader.load(db, scenario, "1.0", timesteps.get(0), null, p, ReportNode.NO_OP);
        Map<String, Double> targetP = new HashMap<>();
        Map<String, Double> p0 = new HashMap<>();
        sender.getGenerators().forEach(g -> targetP.put(g.getId(), g.getTargetP()));
        sender.getLoads().forEach(l -> p0.put(l.getId(), l.getP0()));
        for (int i = 1; i < timesteps.size(); i++) {
            double delta = 0.1 * i;
            List<NetworkEvent> events = Changes.record(sender, net -> {
                net.getGenerators().forEach(g -> g.setTargetP(targetP.get(g.getId()) + delta));
                net.getLoads().forEach(l -> l.setP0(p0.get(l.getId()) + delta));
            });
            RdfDbExport.export(sender, events, db, new SnapshotRef(scenario, "1.0", timesteps.get(i)),
                    new CgmesDiffExport.ExportOptions().setScenarioTime(ZonedDateTime.parse(timesteps.get(i))),
                    ReportNode.NO_OP);
            // Back to the base through the database, as RdfDbVariantsBenchmarkTest does: the export moved the
            // network's provenance to the timestep, and the next timestep must again be one difference from the base
            RdfDbNetworkLoader.update(sender, db, new SnapshotRef(scenario, "1.0", timesteps.get(0)),
                    new RdfDbUpdateOptions(), p, ReportNode.NO_OP);
        }
    }

    private static long heapDeltaOfTheVariants(RdfDbConnection db, String scenario, List<String> timesteps) {
        Network network = loadDay(db, scenario, timesteps).network();
        long withVariants = BenchMeters.heapAfterGc();
        List<String> toRemove = new ArrayList<>(network.getVariantManager().getVariantIds());
        toRemove.remove(RdfDbProvenance.PRIMARY_VARIANT);
        toRemove.forEach(variant -> network.getVariantManager().removeVariant(variant));
        long withoutVariants = BenchMeters.heapAfterGc();
        assertThat(network.getVariantManager().getVariantIds()).hasSize(1);
        return Math.max(0, withVariants - withoutVariants);
    }

    // ------------------------------------------------------------------ helpers

    private static void add(Map<String, List<Long>> phases, String name, Duration duration) {
        phases.computeIfAbsent(name, k -> new ArrayList<>()).add(duration.toMillis());
    }

    private static long med(Map<String, List<Long>> phases, String name) {
        return BenchMeters.median(phases.get(name));
    }
}
