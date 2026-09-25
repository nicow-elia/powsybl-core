/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a whole day as variants costs, measured on both backends.
 *
 * <p>The day is ninety-six steady-state snapshots of the MicroGrid base case, one per quarter hour, built by
 * recording a load move on a sender and writing it as a timestep. Loading it as variants is then compared with
 * the two things a user would otherwise do: load each timestep as a network of its own, or walk one network
 * through the day with ninety-six updates.</p>
 *
 * <pre>
 * measurement                what it answers
 * lv(first) / lv(warm)       what loading the day into variants costs
 *   plan / fetch / clone / apply   where that time goes
 * sep                        ninety-six separate loads, the naive alternative
 * walk                       one network, ninety-six updates, which keeps no history
 * heap                       what the variants cost in memory, after a collection
 * </pre>
 *
 * <p>Two days are measured, because the cost of a variant is the cost of <em>its difference</em>: a "thin" day
 * whose timesteps move one load, and a "rich" one whose timesteps move six loads, a generator, a tap changer and
 * a switch &mdash; the shape of a real quarter-hourly schedule. Read the rich row when asking what a day costs
 * and the thin one when asking what the machinery costs.</p>
 *
 * <p>{@code lv(first)} is the first load of the day in this JVM, not a cold one in any other sense: the same JVM
 * has just written the ninety-six timesteps, so its classes are loaded and the server's caches are warm.
 * {@code sep} and {@code walk} are single runs, against a median of five for {@code lv(warm)}.</p>
 *
 * <p>Target: a warm load of the day on the in-process backend under one second. That figure is <em>reported</em>
 * as {@code TARGET MET} or {@code TARGET MISSED} and never asserted, because it is a statement about an idle
 * machine. What {@code -Dpowsybl.rdfdb.benchmark.strict=true} asserts is the ratio the design is about: a day as
 * variants is at least three times cheaper than loading every timestep on its own, with both figures measured in
 * the same run, so that a loaded machine slows them together.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbVariantsBenchmarkTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RdfDbVariantsBenchmarkTest.class);

    private static final String S = "2016-01-01";
    private static final int TIMESTEPS = 96;
    private static final int WARMUPS = 2;
    private static final int RUNS = 5;
    private static final long WARM_TARGET_MS = 1_000;
    /**
     * What the strict gate really asserts: a day as variants has to be several times cheaper than loading every
     * timestep on its own. Both figures come out of the <em>same</em> run, so a busy machine slows them
     * together and the ratio stays what the design says it is.
     */
    private static final int MIN_SPEEDUP_AGAINST_SEPARATE_LOADS = 3;
    private static final long PER_VARIANT_BUDGET_MS = 100;

    private static final boolean STRICT =
            Boolean.getBoolean("powsybl.rdfdb.benchmark.strict");

    static Stream<Arguments> backends() {
        return Backends.backends();
    }

    private static Properties params() {
        Properties p = new Properties();
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        return p;
    }

    private static ReadOnlyDataSource be() {
        return CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
    }

    /** The canonical instants of a day of quarter hours, starting at the base timestep of the fixture. */
    private static List<String> day() {
        List<String> timesteps = new ArrayList<>();
        Instant base = Instant.parse("2014-06-01T10:30:00Z");
        for (int i = 0; i < TIMESTEPS; i++) {
            timesteps.add(base.plus(Duration.ofMinutes(15L * i)).toString().replace(".000", ""));
        }
        return timesteps;
    }

    /** How much of the network one timestep of a day moves. */
    private enum Shape {
        /** One load: the fixed cost of an apply and of a variant slot. */
        THIN,
        /** Six loads, a generator, a tap changer and a switch: the shape of a quarter-hourly schedule. */
        RICH
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void benchmark(String backend) {
        for (Shape shape : Shape.values()) {
            measureDay(backend, shape);
        }
    }

    private void measureDay(String backend, Shape shape) {
        try (RdfDbConnection db = RdfDbConnection.open(Backends.database(backend,
                "variants-bench-" + shape.name().toLowerCase(java.util.Locale.ROOT)))) {
            List<String> timesteps = day();
            build(db, timesteps, shape);

            // (lv) cold, then the medians of five warm runs
            long cold = measure(() -> loadDay(db, timesteps));
            for (int i = 0; i < WARMUPS; i++) {
                loadDay(db, timesteps);
            }
            List<VariantLoadResult> runs = new ArrayList<>();
            long[] warm = new long[RUNS];
            for (int i = 0; i < RUNS; i++) {
                long start = System.nanoTime();
                runs.add(loadDay(db, timesteps));
                warm[i] = System.nanoTime() - start;
            }
            long warmMedian = median(warm);
            VariantLoadResult last = runs.get(RUNS - 1);
            assertThat(last.refused()).isEmpty();
            assertThat(last.bound()).hasSize(TIMESTEPS);

            long[] applies = last.bound().stream().mapToLong(outcome -> outcome.apply().toNanos()).toArray();
            long applyMedian = median(applies.clone());
            long applyMax = java.util.Arrays.stream(applies).max().orElse(0);

            // (sep) the naive alternative: one network per timestep
            long separate = measure(() -> {
                for (String timestep : timesteps) {
                    RdfDbNetworkLoader.load(db, S, "1.0", timestep, null, params(), ReportNode.NO_OP);
                }
                return null;
            });

            // (walk) one network updated timestep by timestep, which keeps no history at all
            long walk = measure(() -> {
                Network network = RdfDbNetworkLoader.load(db, S, "1.0", timesteps.get(0), null, params(),
                        ReportNode.NO_OP);
                for (String timestep : timesteps) {
                    RdfDbNetworkLoader.update(network, db, new SnapshotRef(S, "1.0", timestep),
                            new RdfDbUpdateOptions(), params(), ReportNode.NO_OP);
                }
                return null;
            });

            long heap = heapDeltaOfTheVariants(db, timesteps);
            int statements = last.bound().stream().mapToInt(VariantOutcome::diffCount).sum();

            LOGGER.info(String.format("%-8s %-5s lv(first)=%d lv(warm)=%d [plan %d fetch %d clone %d apply %d,"
                            + " per variant median %d max %d, %d diff(s)] | sep=%d walk=%d"
                            + " | heap(%d variants)=%d MB",
                    backend, shape, ms(cold), ms(warmMedian), last.plan().toMillis(), last.fetch().toMillis(),
                    last.cloning().toMillis(), last.applyTotal().toMillis(), ms(applyMedian), ms(applyMax),
                    statements, ms(separate), ms(walk), TIMESTEPS, heap / (1024 * 1024)));

            boolean met = ms(warmMedian) < WARM_TARGET_MS;
            if (Backends.MEMORY.equals(backend)) {
                // Reported, not asserted: an absolute millisecond figure is a statement about this machine when
                // it is idle, and the strict run has to be usable on a machine that is not
                LOGGER.info("{}: a warm load of {} {} timesteps as variants takes {} ms, target {} ms",
                        met ? "TARGET MET" : "TARGET MISSED", TIMESTEPS, shape, ms(warmMedian), WARM_TARGET_MS);
            }
            if (STRICT) {
                assertThat(warmMedian * MIN_SPEEDUP_AGAINST_SEPARATE_LOADS)
                        .as("a warm load of " + TIMESTEPS + " " + shape + " timesteps as variants (" + backend
                                + ") against " + TIMESTEPS + " separate loads measured in the same run")
                        .isLessThan(separate);
            }
            if (ms(applyMax) > PER_VARIANT_BUDGET_MS) {
                LOGGER.info("BUDGET EXCEEDED on {} ({}): the slowest per-variant apply takes {} ms, budget {} ms",
                        backend, shape, ms(applyMax), PER_VARIANT_BUDGET_MS);
            }
            // Whatever the machine, the day as variants has to beat loading every timestep on its own
            assertThat(warmMedian).as("a day as variants against " + TIMESTEPS + " separate loads")
                    .isLessThan(separate);
        }
    }

    private static VariantLoadResult loadDay(RdfDbConnection db, List<String> timesteps) {
        return RdfDbNetworkLoader.loadVariants(db, S, "1.0", timesteps, new RdfDbVariantLoadOptions(), null,
                params(), ReportNode.NO_OP);
    }

    /**
     * What the ninety-six variants cost in memory.
     *
     * <p>Measured on <em>one</em> network so that nothing but the per-variant arrays differs between the two
     * readings: the day is loaded, the heap is read after a collection, every variant but the primary is removed,
     * and the heap is read again. The network is kept reachable across both.</p>
     */
    private static long heapDeltaOfTheVariants(RdfDbConnection db, List<String> timesteps) {
        VariantLoadResult day = loadDay(db, timesteps);
        Network network = day.network();
        assertThat(network.getVariantManager().getVariantIds()).hasSize(TIMESTEPS + 1);
        long withVariants = usedHeap();
        List<String> toRemove = new ArrayList<>(network.getVariantManager().getVariantIds());
        toRemove.remove(RdfDbProvenance.PRIMARY_VARIANT);
        toRemove.forEach(variant -> network.getVariantManager().removeVariant(variant));
        long withoutVariants = usedHeap();
        assertThat(network.getVariantManager().getVariantIds()).hasSize(1);
        return Math.max(0, withVariants - withoutVariants);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /** Ninety-six steady-state snapshots, one per quarter hour, each one difference from the base. */
    private static void build(RdfDbConnection db, List<String> timesteps, Shape shape) {
        db.clear(S);
        db.snapshots(S).putFull(be(), null, SnapshotRef.of(S, "1.0"), params(), ReportNode.NO_OP);
        Network sender = RdfDbNetworkLoader.load(db, S, "1.0", timesteps.get(0), null, params(),
                ReportNode.NO_OP);
        for (int i = 1; i < timesteps.size(); i++) {
            int step = i;
            List<NetworkEvent> events = Changes.record(sender, n -> move(n, shape, step));
            RdfDbExport.export(sender, events, db, new SnapshotRef(S, "1.0", timesteps.get(i)),
                    new CgmesDiffExport.ExportOptions().setScenarioTime(ZonedDateTime.parse(timesteps.get(i))),
                    ReportNode.NO_OP);
            // Back to the base state, so that every timestep is the same distance from the root
            RdfDbNetworkLoader.update(sender, db, new SnapshotRef(S, "1.0", timesteps.get(0)),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP);
        }
    }

    /** What one timestep of the day moves. */
    private static void move(Network network, Shape shape, int step) {
        if (shape == Shape.THIN) {
            network.getLoad(Changes.LOAD_ID).setP0(network.getLoad(Changes.LOAD_ID).getP0() + step);
            return;
        }
        // A quarter-hourly schedule: the consumption of the day, the units following it and one tap. The
        // MicroGrid BE fixture is bus/breaker and holds no switch, so there is none to toggle
        network.getLoadStream().limit(6).forEach(load -> load.setP0(load.getP0() + step));
        network.getGeneratorStream().limit(2)
                .forEach(generator -> generator.setTargetP(generator.getTargetP() + step));
        Changes.moveTap(network);
    }

    private static long measure(Supplier<?> body) {
        long start = System.nanoTime();
        body.get();
        return System.nanoTime() - start;
    }

    private static long median(long[] values) {
        java.util.Arrays.sort(values);
        return values[values.length / 2];
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
