/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.rdfdb.SvedalaTimestepFixtures.Shape;
import com.powsybl.cgmes.rdfdb.SvedalaTimestepFixtures.TimestepFiles;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How long does it take to diff ninety-five timesteps against an anchor and put them into the database?
 *
 * <p>This is the question the ingestion path exists to answer, asked of the largest CGMES model the powsybl test
 * repertoire carries: <strong>Svedala</strong> (CGMES 3, five instance files, 13.7 MiB, 8 397 top-level elements
 * in the equipment model and 6 013 in the steady state hypothesis, file names with spaces). The anchor is written
 * once with {@code putFull} at 00:00; the ninety-five quarter hours 00:15 &hellip; 23:45 are then ingested one by
 * one with {@link SnapshotCatalog#putAsDiff(com.powsybl.commons.datasource.ReadOnlyDataSource,
 * com.powsybl.commons.datasource.ReadOnlyDataSource, SnapshotRef, Properties, ReportNode)}, which is what a TSO
 * actually has: not a network it changed, but ninety-five sets of exported files.</p>
 *
 * <p>Every timestep is a difference against the <em>anchor</em>, not against its predecessor &mdash; a timestep
 * root pins to the base chain &mdash; so the parent state is the same one ninety-five times over, which is the
 * whole reason the graph cache is there and the reason the first timestep is reported apart from the rest.</p>
 *
 * <h2>The three shapes of a day</h2>
 * <ul>
 *   <li><b>thin</b> &mdash; five {@code EnergyConsumer.p} set points move. The floor: what the machinery costs.</li>
 *   <li><b>rich</b> &mdash; every continuous set point of the steady state hypothesis is scaled by the quarter
 *       hour's load factor (all {@code EnergyConsumer.p}/{@code .q}, {@code RotatingMachine.p}/{@code .q} and
 *       {@code RegulatingControl.targetValue}: 322 values, of which 311 come out different). A realistic
 *       quarter-hourly schedule.</li>
 *   <li><b>structural</b> &mdash; the European exchange case: the equipment model of the timestep omits objects
 *       the anchor holds (two {@code ACLineSegment}s with their four terminals, four limit sets and eight limits
 *       &mdash; eighteen equipment objects and the fourteen steady-state objects describing them), a rolling
 *       window so the day both removes and restores them, and every eighth timestep also carries a line the
 *       anchor does not have (nine objects, 99 over the day). Set points move on top.</li>
 * </ul>
 *
 * <h2>Measured numbers</h2>
 * <p>8 cores, 62 GB RAM, Java 21, embedded Fuseki (TxnMem) on loopback and the in-process {@code memory:}
 * backend, milliseconds. {@code anchor} is the {@code putFull} of the 13.7 MiB anchor; {@code total} the whole
 * day of ninety-five {@code putAsDiff} calls; {@code median}/{@code first}/{@code rest}/{@code max} the per
 * timestep wall clock; {@code read1} a full load of 23:45 out of the database; {@code parse}, {@code mat},
 * {@code diff} and {@code write} the four phases {@code lastIngestStatistics} reports, as medians;
 * {@code fwd}/{@code rev} the median statement count of each side of the stored difference.</p>
 *
 * <pre>
 * backend shape           n  anchor    total  median   first    rest     max   read1  parse    mat   diff  write    fwd    rev
 * fuseki  thin           95    1500    61773     645     858     645     858     300     43     89    475     24      5      5
 * fuseki  rich           95    1644    63861     667     783     667     792     291     43     90    474     44    311    311
 * fuseki  structural     95    1442   143761    1507    1629    1507    1633     283    172     87   1206     27      5    175
 * memory  thin           95     600    55771     583     602     583     621     250     43     61    472      2      5      5
 * memory  rich           95     589    55993     586     589     586     626     249     43     60    472      7    311    311
 * memory  structural     95     561   136348    1429    1503    1429    1503     235    170     60   1188      4      5    175
 *
 * read side (memory)        load of 23:45   update walk 00:00 -&gt; 23:45   96 variants of the day   store after the day
 *   thin                              250      6 ms  DIFF_APPLIED                484 ms, 96 bound   172 049 data triples
 *   rich                              249     19 ms  DIFF_APPLIED               1718 ms, 96 bound   230 189 data triples
 *   structural                        235    224 ms  FULL_RELOAD      234 ms, 1 bound 95 refused    189 134 data triples
 * </pre>
 *
 * <p>Two things the table says out loud. First, the day is <em>cheap to keep</em>: ninety-five thin timesteps
 * add about a thousand triples to the roughly 171 000 the anchor brought, and a whole day of rich ones adds
 * 34 %. Second, the cost of an ingestion is the <em>comparison</em>, not the database: {@code diff} is four
 * fifths of a timestep of any shape, on both backends, while the write is 2 to 44 ms. Thin and rich cost the
 * same because the comparison indexes the whole profile either way; a structural timestep costs 2.4 times as
 * much because it compares the equipment model too.</p>
 *
 * <h2>The gate</h2>
 * <p>Relative only, never a millisecond bound: the median ingestion of a timestep must stay under
 * {@link #MEDIAN_OVER_ANCHOR} times the anchor's own {@code putFull} &mdash; both measured in the same run, so a
 * loaded machine slows them together &mdash; and no timestep after the first may be slower than
 * {@link #OUTLIER_OVER_MEDIAN} times the median of the day.</p>
 *
 * <h2>Housekeeping</h2>
 * <p>A day of Svedala is three hundred graphs and close to a quarter of a million triples, and the embedded
 * Fuseki is shared by every test class of this package ({@link Backends}). So every scenario this class creates
 * is dropped again once its numbers are taken: a benchmark must not make the queries of the class that runs
 * after it slower.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbIngestionBenchmarkTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RdfDbIngestionBenchmarkTest.class);

    /** The day, and the anchor at its midnight. Svedala's own scenario time is 19:00 of that day. */
    private static final String ANCHOR = "2020-12-02T00:00:00Z";

    /** 00:15 … 23:45. Overridable, so that a smoke run does not have to be a day. */
    private static final int TIMESTEPS = Integer.getInteger("powsybl.rdfdb.ingest.timesteps", 95);

    /** How many timesteps of the day are also ingested with a drifted equipment model. */
    private static final int DRIFTED = Integer.getInteger("powsybl.rdfdb.ingest.drifted", 5);

    /**
     * The relative gate: the median timestep against the anchor's own {@code putFull}.
     *
     * <p>Five, not one: the anchor parses files and uploads them, while a timestep parses files, materialises the
     * parent state as triples <em>and</em> compares two whole graph sets. A structural timestep, which ships the
     * equipment model as well, legitimately costs a few times a plain upload. What this bound catches is the
     * regression that matters &mdash; an ingestion that stops being proportional to the model and starts being
     * proportional to something else &mdash; and it is relative, so a loaded machine slows both sides together.</p>
     */
    private static final double MEDIAN_OVER_ANCHOR = 5.0;

    /** The outlier gate: no timestep after the first may be this much slower than the median of the day. */
    private static final double OUTLIER_OVER_MEDIAN = 5.0;

    /** Above this, a single {@code putAsDiff} on the in-process backend is worth a flight recording. */
    private static final long PROFILE_ABOVE_MS = 100;

    static Stream<Arguments> backends() {
        return Backends.backends();
    }

    private static Properties params() {
        Properties p = new Properties();
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        return p;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void benchmark(String backend) {
        LOGGER.info("fixture: Svedala, {} equipment object(s), {} steady-state object(s), {} ACLineSegment(s),"
                        + " {} set points rewritten by a rich timestep",
                SvedalaTimestepFixtures.equipmentObjectCount(), SvedalaTimestepFixtures.steadyStateObjectCount(),
                SvedalaTimestepFixtures.lineCount(), SvedalaTimestepFixtures.richSetPointCount());
        List<String> table = new ArrayList<>();
        table.add(header());
        for (Shape shape : Shape.values()) {
            table.add(measureDay(backend, shape));
        }
        measureDrift(backend);
        measureWholeExport(backend);
        table.forEach(LOGGER::info);
    }

    private static String header() {
        return String.format(Locale.ROOT, "%-7s %-11s %5s %7s %8s %7s %7s %7s %7s %7s %6s %6s %6s %6s %6s %6s",
                "backend", "shape", "n", "anchor", "total", "median", "first", "rest", "max", "read1",
                "parse", "mat", "diff", "write", "fwd", "rev");
    }

    // ------------------------------------------------------------------ one day, one shape

    private String measureDay(String backend, Shape shape) {
        String scenario = "ingest-" + shape.name().toLowerCase(Locale.ROOT) + "-" + backend;
        GraphCache cache = new GraphCache().trustImmutableGraphs(true);
        try (RdfDbConnection db = RdfDbConnection.open(
                Backends.database(backend, "ingest-bench").withCache(cache))) {
            db.clear(scenario);
            SnapshotCatalog catalog = db.snapshots(scenario);

            long anchor = millis(() -> catalog.putFull(SvedalaTimestepFixtures.anchor(ANCHOR), null,
                    SnapshotRef.of(scenario, "1.0"), params(), ReportNode.NO_OP));

            Day day = ingestDay(catalog, shape);
            ReadSide read = measureReadSide(db, scenario, shape, day);
            Store store = measureStore(db, scenario);

            LOGGER.info("{} / {}: {} timestep(s) in {} ms; anchor putFull {} ms; per timestep median {} ms"
                            + " (first {} ms, rest {} ms, min {} ms, max {} ms);"
                            + " phases median parse {} materializeParent {} diff {} write {};"
                            + " statements forward median {} max {}, reverse median {} max {};"
                            + " graph cache {} hit(s) {} miss(es)",
                    backend, shape, day.walls.size(), sum(day.walls), anchor, median(day.walls),
                    day.walls.get(0), median(day.walls.subList(1, day.walls.size())), min(day.walls),
                    max(day.walls), median(day.parse), median(day.materialize), median(day.diff),
                    median(day.write), median(day.forward), max(day.forward), median(day.reverse),
                    max(day.reverse), cache.hits(), cache.misses());
            LOGGER.info("{} / {}: store after the day: {} metadata triple(s), {} data triple(s) in {} graph(s);"
                            + " up to {} object(s) omitted per timestep, {} object(s) added over {} timestep(s);"
                            + " slow-route (non fast-predicate) timesteps: {}",
                    backend, shape, store.meta, store.data, store.graphs, day.omitted, day.added,
                    day.addingTimesteps, day.slow);
            LOGGER.info("{} / {}: read side: load of the last timestep {} ms, update walk 00:00 -> {} {} ms"
                            + " (route {}), {} variants of the day {} ms ({} bound, {} refused)",
                    backend, shape, read.lastLoad, day.lastLabel, read.walk, read.route, read.variantCount,
                    read.variants, read.bound, read.refused);

            String row = String.format(Locale.ROOT,
                    "%-7s %-11s %5d %7d %8d %7d %7d %7d %7d %7d %6d %6d %6d %6d %6d %6d",
                    backend, shape.name().toLowerCase(Locale.ROOT), day.walls.size(), anchor, sum(day.walls),
                    median(day.walls), day.walls.get(0), median(day.walls.subList(1, day.walls.size())),
                    max(day.walls), read.lastLoad, median(day.parse), median(day.materialize),
                    median(day.diff), median(day.write), median(day.forward), median(day.reverse));

            // Everything is measured: give the shared server its size back before gating, so that a failing
            // assertion cannot leave a day of Svedala behind for the rest of the suite to query around
            db.clear(scenario);
            gate(backend, shape, anchor, day);
            profileHint(backend, shape, day);
            return row;
        }
    }

    /** What ninety-five {@code putAsDiff} calls cost and produced. */
    private static final class Day {
        private final List<Long> walls = new ArrayList<>();
        private final List<Long> parse = new ArrayList<>();
        private final List<Long> materialize = new ArrayList<>();
        private final List<Long> diff = new ArrayList<>();
        private final List<Long> write = new ArrayList<>();
        private final List<Long> forward = new ArrayList<>();
        private final List<Long> reverse = new ArrayList<>();
        private final List<String> labels = new ArrayList<>();
        private int omitted;
        private int added;
        private int addingTimesteps;
        private int slow;
        private boolean lastFast = true;
        private String lastLabel;
    }

    private Day ingestDay(SnapshotCatalog catalog, Shape shape) {
        Day day = new Day();
        for (int i = 1; i <= TIMESTEPS; i++) {
            String instant = instantOf(i);
            String label = labelOf(i);
            TimestepFiles files = SvedalaTimestepFixtures.timestep(shape, i, instant);
            SnapshotRef target = SnapshotRef.of("1.0", label, catalog);

            long start = System.nanoTime();
            SnapshotInfo written = catalog.putAsDiff(files.dataSource(), null, target, params(),
                    ReportNode.NO_OP);
            long wall = (System.nanoTime() - start) / 1_000_000;

            SnapshotCatalog.IngestStatistics statistics = catalog.lastIngestStatistics();
            day.walls.add(wall);
            day.parse.add(statistics.parse().toMillis());
            day.materialize.add(statistics.materializeParent().toMillis());
            day.diff.add(statistics.diff().toMillis());
            day.write.add(statistics.write().toMillis());
            day.forward.add((long) statements(statistics.forwardStatements()));
            day.reverse.add((long) statements(statistics.reverseStatements()));
            day.labels.add(label);
            day.lastLabel = label;
            day.omitted = Math.max(day.omitted, files.omittedObjects());
            day.added += files.addedObjects();
            if (files.addedObjects() > 0) {
                day.addingTimesteps++;
            }
            day.lastFast = written.fast();
            if (!written.fast()) {
                day.slow++;
            }
            assertThat(day.forward.get(day.forward.size() - 1))
                    .as("the difference of timestep " + label + " carries statements").isPositive();
            if (i == 1 || i == TIMESTEPS) {
                LOGGER.info("{} timestep {}: {} ms, forward {} reverse {} statement(s), fast route per profile {},"
                                + " profiles inherited {}", shape, label, wall,
                        day.forward.get(day.forward.size() - 1), day.reverse.get(day.reverse.size() - 1),
                        statistics.fast(), statistics.ignored());
            }
        }
        return day;
    }

    // ------------------------------------------------------------------ the read side the ingestion enables

    private record ReadSide(long lastLoad, long walk, String route, long variants, int variantCount, int bound,
                            int refused) {
    }

    private ReadSide measureReadSide(RdfDbConnection db, String scenario, Shape shape, Day day) {
        SnapshotRef last = new SnapshotRef(scenario, "1.0", instantOf(TIMESTEPS));
        long lastLoad = millis(() -> RdfDbNetworkLoader.load(db, last, null, params(), ReportNode.NO_OP));

        Network network = RdfDbNetworkLoader.load(db, new SnapshotRef(scenario, "1.0", ANCHOR), null, params(),
                ReportNode.NO_OP);
        long start = System.nanoTime();
        UpdateResult result = RdfDbNetworkLoader.update(network, db, last, new RdfDbUpdateOptions(), params(),
                ReportNode.NO_OP);
        long walk = (System.nanoTime() - start) / 1_000_000;

        // What the planner itself says about the last timestep, independently of the update that just ran
        SnapshotInfo anchorInfo = db.snapshots(scenario).find(new SnapshotRef(scenario, "1.0", ANCHOR))
                .orElseThrow();
        UpdatePlan plan = db.versionGraph(scenario).plan(anchorInfo.iri(), last, new RdfDbUpdateOptions());
        LOGGER.info("{}: the planner routes 00:00 -> {} as {} ({}), the update took the {} route", shape,
                day.lastLabel, plan.kind(), plan.reasons(), result.route());
        if (shape == Shape.STRUCTURAL) {
            assertThat(day.slow).as("every structural timestep states something no update query reads")
                    .isEqualTo(day.walls.size());
        }
        if (day.lastFast) {
            assertThat(plan.kind()).as("a difference of fast predicates is applied in place")
                    .isEqualTo(UpdatePlan.Kind.DIFF);
            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
        } else {
            assertThat(plan.kind()).as("a timestep that adds or removes objects cannot be applied in place")
                    .isEqualTo(UpdatePlan.Kind.FULL);
            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_RELOAD);
        }

        List<String> instants = new ArrayList<>();
        instants.add(ANCHOR);
        for (int i = 1; i <= TIMESTEPS; i++) {
            instants.add(instantOf(i));
        }
        start = System.nanoTime();
        VariantLoadResult variants = RdfDbNetworkLoader.loadVariants(db, scenario, "1.0", instants,
                new RdfDbVariantLoadOptions(), null, params(), ReportNode.NO_OP);
        long variantsMs = (System.nanoTime() - start) / 1_000_000;
        return new ReadSide(lastLoad, walk, result.route().name(), variantsMs, instants.size(),
                variants.bound().size(), variants.refused().size());
    }

    // ------------------------------------------------------------------ two side measurements

    /** What a drifted equipment model costs: the same timestep, with one line renamed. */
    private void measureDrift(String backend) {
        if (DRIFTED <= 0) {
            return;
        }
        String scenario = "ingest-drift-" + backend;
        try (RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "ingest-bench")
                .withCache(new GraphCache().trustImmutableGraphs(true)))) {
            db.clear(scenario);
            SnapshotCatalog catalog = db.snapshots(scenario);
            catalog.putFull(SvedalaTimestepFixtures.anchor(ANCHOR), null, SnapshotRef.of(scenario, "1.0"),
                    params(), ReportNode.NO_OP);
            List<Long> walls = new ArrayList<>();
            List<Long> forward = new ArrayList<>();
            boolean fast = true;
            for (int i = 1; i <= DRIFTED; i++) {
                SnapshotRef target = SnapshotRef.of("1.0", labelOf(i), catalog);
                long start = System.nanoTime();
                SnapshotInfo written = catalog.putAsDiff(SvedalaTimestepFixtures.eqDrift(i, instantOf(i)), null,
                        target, params(), ReportNode.NO_OP);
                walls.add((System.nanoTime() - start) / 1_000_000);
                forward.add((long) statements(catalog.lastIngestStatistics().forwardStatements()));
                fast &= written.fast();
            }
            SnapshotRef last = new SnapshotRef(scenario, "1.0", instantOf(DRIFTED));
            long load = millis(() -> RdfDbNetworkLoader.load(db, last, null, params(), ReportNode.NO_OP));
            LOGGER.info("{} / drift: {} timestep(s) whose equipment model renamed a line: median {} ms,"
                            + " forward median {} statement(s), fast route {}, full-route load of the last one"
                            + " {} ms", backend, DRIFTED, median(walls), median(forward), fast, load);
            db.clear(scenario);
        }
    }

    /** What shipping the whole export costs over shipping only the profiles that changed. */
    private void measureWholeExport(String backend) {
        String scenario = "ingest-whole-" + backend;
        try (RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "ingest-bench")
                .withCache(new GraphCache().trustImmutableGraphs(true)))) {
            db.clear(scenario);
            SnapshotCatalog catalog = db.snapshots(scenario);
            catalog.putFull(SvedalaTimestepFixtures.anchor(ANCHOR), null, SnapshotRef.of(scenario, "1.0"),
                    params(), ReportNode.NO_OP);
            long lean = millis(() -> catalog.putAsDiff(
                    SvedalaTimestepFixtures.timestep(Shape.RICH, 1, instantOf(1)).dataSource(), null,
                    SnapshotRef.of("1.0", labelOf(1), catalog), params(), ReportNode.NO_OP));
            long leanParse = catalog.lastIngestStatistics().parse().toMillis();
            long whole = millis(() -> catalog.putAsDiff(
                    SvedalaTimestepFixtures.fullFileSet(Shape.RICH, 2, instantOf(2)), null,
                    SnapshotRef.of("1.0", labelOf(2), catalog), params(), ReportNode.NO_OP));
            SnapshotCatalog.IngestStatistics statistics = catalog.lastIngestStatistics();
            LOGGER.info("{} / whole export: a timestep shipping only its steady state costs {} ms (parse {} ms),"
                            + " the same timestep shipping all five profiles {} ms (parse {} ms); the extra is"
                            + " parsing {} which the ingestion then inherits", backend, lean, leanParse, whole,
                    statistics.parse().toMillis(), statistics.ignored());
            db.clear(scenario);
        }
    }

    // ------------------------------------------------------------------ the store after the day

    private record Store(long meta, long data, long graphs) {
    }

    private Store measureStore(RdfDbConnection db, String scenario) {
        String meta = RdfDbNames.metaGraph(scenario);
        String prefix = RdfDbNames.scenarioPrefix(scenario);
        long metaTriples = count(db, scenario,
                "SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + meta + "> { ?s ?p ?o } }");
        long allTriples = count(db, scenario, "SELECT (COUNT(*) AS ?n) WHERE { GRAPH ?g { ?s ?p ?o }"
                + " FILTER(STRSTARTS(STR(?g), \"" + prefix + "\")) }");
        long graphs = count(db, scenario, "SELECT (COUNT(DISTINCT ?g) AS ?n) WHERE { GRAPH ?g { ?s ?p ?o }"
                + " FILTER(STRSTARTS(STR(?g), \"" + prefix + "\")) }");
        return new Store(metaTriples, allTriples - metaTriples, graphs - 1);
    }

    private static long count(RdfDbConnection db, String scenario, String query) {
        List<Map<String, Value>> rows = db.sparql(scenario).select(query);
        return rows.isEmpty() ? 0L : Long.parseLong(rows.get(0).get("n").stringValue());
    }

    // ------------------------------------------------------------------ the gate

    private void gate(String backend, Shape shape, long anchor, Day day) {
        long median = median(day.walls);
        assertThat((double) median)
                .as("the median ingestion of a " + shape + " timestep on " + backend + " against the anchor's"
                        + " own putFull (" + anchor + " ms), both measured in this run")
                .isLessThanOrEqualTo(MEDIAN_OVER_ANCHOR * anchor);
        // The first timestep is excluded: it is the one that fills the graph cache with the parent state, and
        // reporting it apart is the point rather than a thing to hide
        List<Long> rest = day.walls.subList(1, day.walls.size());
        long worst = max(rest);
        int worstAt = rest.indexOf(worst) + 1;
        assertThat((double) worst)
                .as("timestep " + day.labels.get(worstAt) + " of the " + shape + " day on " + backend
                        + " against the median of the day (" + median + " ms)")
                .isLessThanOrEqualTo(OUTLIER_OVER_MEDIAN * median);
    }

    private void profileHint(String backend, Shape shape, Day day) {
        if (Backends.MEMORY.equals(backend) && median(day.walls) > PROFILE_ABOVE_MS) {
            LOGGER.info("PROFILE: the median {} ingestion on the in-process backend takes {} ms, over the {} ms"
                            + " that asks for a flight recording; the phase split above says where it goes"
                            + " (parse {} materializeParent {} diff {} write {})", shape, median(day.walls),
                    PROFILE_ABOVE_MS, median(day.parse), median(day.materialize), median(day.diff),
                    median(day.write));
        }
    }

    // ------------------------------------------------------------------ small helpers

    private static String instantOf(int quarterHour) {
        return SnapshotRef.canonicalTimestep(Instant.parse(ANCHOR)
                .plus(Duration.ofMinutes(15L * quarterHour)).atZone(ZoneOffset.UTC));
    }

    private static String labelOf(int quarterHour) {
        return String.format(Locale.ROOT, "%02d:%02d", quarterHour * 15 / 60, quarterHour * 15 % 60);
    }

    private static int statements(Map<CgmesSubset, Integer> perProfile) {
        return perProfile.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static long millis(Supplier<?> body) {
        long start = System.nanoTime();
        body.get();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        return sorted.isEmpty() ? 0L : sorted.get(sorted.size() / 2);
    }

    private static long sum(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).sum();
    }

    private static long min(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).min().orElse(0L);
    }

    private static long max(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).max().orElse(0L);
    }
}
