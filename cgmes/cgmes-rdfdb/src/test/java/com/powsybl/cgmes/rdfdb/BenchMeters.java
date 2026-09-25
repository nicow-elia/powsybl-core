/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.rdfdb;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import org.junit.jupiter.params.provider.Arguments;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The instruments of the scale benchmarks ({@code Scale*BenchmarkTest}): grid selection, run counts, heap
 * readings and the request meter of the embedded Fuseki.
 *
 * <p>Nothing here is a timer of the code under test: the phase timers of the production code
 * ({@link LoadStatistics}, {@link UpdateStatistics}, {@link SnapshotCatalog.IngestStatistics},
 * {@link VariantLoadResult}) are the source of truth, and what this class adds is only what those cannot say
 * &mdash; how much heap a phase held and how many requests the server saw.</p>
 *
 * <p>Grids are chosen with {@code -Dpowsybl.bench.grids=be,snb,sv1,sv6,sv20,sv40} (default {@code be,sv1}, the
 * small grids the ordinary module build can afford); {@code svN} is Svedala replicated {@code N} times
 * ({@link ReplicatedSvedala}). Run counts are {@code -Dpowsybl.bench.runs} and {@code -Dpowsybl.bench.warmups}.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class BenchMeters {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(BenchMeters.class);

    /** The grids of the ordinary module build. */
    static final String DEFAULT_GRIDS = "be,sv1";

    private BenchMeters() {
    }

    // ------------------------------------------------------------------ grids

    /**
     * One benchmark grid.
     *
     * @param key      {@code be}, {@code snb} or {@code sv<N>}
     * @param replicas the replication factor of a Svedala grid, {@code 0} for the others
     */
    record Grid(String key, int replicas) {

        boolean svedala() {
            return replicas > 0;
        }

        /** Whether this is one of the small grids, which get more runs. */
        boolean small() {
            return replicas <= 1;
        }

        ReadOnlyDataSource dataSource() {
            return switch (key) {
                case "be" -> CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
                case "snb" -> CgmesConformity1Catalog.smallNodeBreaker().dataSource();
                default -> ReplicatedSvedala.cached(replicas);
            };
        }

        @Override
        public String toString() {
            return key;
        }
    }

    /** Whether grids were selected explicitly (a campaign run) rather than taken from the defaults (CI). */
    static boolean explicitGrids() {
        return System.getProperty("powsybl.bench.grids") != null;
    }

    /** The selected grids, restricted to the keys a part can measure. */
    static List<Grid> grids(String... allowed) {
        List<String> accepted = Arrays.asList(allowed);
        List<Grid> grids = new ArrayList<>();
        for (String key : System.getProperty("powsybl.bench.grids", DEFAULT_GRIDS).split(",")) {
            String k = key.trim().toLowerCase(Locale.ROOT);
            if (k.isEmpty() || !accepted.contains(k.startsWith("sv") ? "sv" : k)) {
                continue;
            }
            grids.add(new Grid(k, k.startsWith("sv") ? Integer.parseInt(k.substring(2)) : 0));
        }
        return grids;
    }

    /** Measured runs: the property, else 5 on a small grid and 3 on a replicated one (3 and 1 in CI). */
    static int runs(Grid grid) {
        return Integer.getInteger("powsybl.bench.runs", explicitGrids() ? (grid.small() ? 5 : 3) : 3);
    }

    /** Warm-up runs: the property, else 2 on a small grid and 1 on a replicated one (1 in CI). */
    static int warmups(Grid grid) {
        return Integer.getInteger("powsybl.bench.warmups", explicitGrids() && grid.small() ? 2 : 1);
    }

    /**
     * The backends of {@link Backends}, narrowed by {@code -Dpowsybl.bench.backends=memory} (or {@code fuseki}) for
     * a profiling run that should record one backend only.
     */
    static Stream<Arguments> backends() {
        String only = System.getProperty("powsybl.bench.backends");
        return Backends.backends().filter(a -> only == null || only.isBlank()
                || Arrays.asList(only.split(",")).contains((String) a.get()[0]));
    }

    static Properties params() {
        Properties p = new Properties();
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        return p;
    }

    // ------------------------------------------------------------------ heap

    /** Used heap after three full collections, in bytes. */
    static long heapAfterGc() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /** Reset the peak usage of every heap pool. */
    static void resetPeak() {
        heapPools().forEach(MemoryPoolMXBean::resetPeakUsage);
    }

    /** The sum of the peak usage of every heap pool since the last {@link #resetPeak()}, in bytes. */
    static long peakHeap() {
        return heapPools().stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum();
    }

    private static List<MemoryPoolMXBean> heapPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP)
                .toList();
    }

    static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }

    /** Log what the JVM runs with, once per class: the heap limit is what makes a large grid fit or not. */
    static void logJvm(Class<?> benchmark) {
        LOGGER.info("{}: java {}, max heap {} MB, {} processor(s), grids {}", benchmark.getSimpleName(),
                System.getProperty("java.version"), mb(Runtime.getRuntime().maxMemory()),
                Runtime.getRuntime().availableProcessors(), System.getProperty("powsybl.bench.grids",
                        DEFAULT_GRIDS + " (default)"));
    }

    // ------------------------------------------------------------------ time

    static long millis(Runnable body) {
        long start = System.nanoTime();
        body.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    static <T> T timed(Supplier<T> body, long[] millisOut) {
        long start = System.nanoTime();
        T result = body.get();
        millisOut[0] = (System.nanoTime() - start) / 1_000_000;
        return result;
    }

    static long median(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        return sorted.get(sorted.size() / 2);
    }

    static long max(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    /** Median of {@code runs} measurements after {@code warmups} discarded ones. */
    static long median(int warmups, int runs, Supplier<Long> measurement) {
        for (int i = 0; i < warmups; i++) {
            measurement.get();
        }
        List<Long> values = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            values.add(measurement.get());
        }
        return median(values);
    }

    static String ratio(long numerator, long denominator) {
        return denominator <= 0 ? "-" : String.format(Locale.ROOT, "%.2f", numerator / (double) denominator);
    }

    // ------------------------------------------------------------------ the server

    /**
     * Counts the requests of the embedded Fuseki and sums the server-side milliseconds of their responses.
     *
     * <p>Fuseki logs one line per request ({@code [12] GET http://…}) and one per response
     * ({@code [12] 200 OK (3 ms)}) on {@code org.apache.jena.fuseki.Fuseki} at INFO. The meter raises that
     * logger to INFO, stops it from reaching the console (a sv20 load would otherwise print a line per request of
     * every test) and counts. Installed once per JVM.</p>
     */
    static final class FusekiMeter {

        private static final Pattern REQUEST = Pattern.compile("^\\[\\d+] (GET|POST|PUT|DELETE|HEAD) .*");
        private static final Pattern RESPONSE = Pattern.compile("^\\[\\d+] \\d{3} .*\\((\\d+) ms\\)\\s*$");
        private static final AtomicLong REQUESTS = new AtomicLong();
        private static final AtomicLong SERVER_MS = new AtomicLong();
        private static final AtomicLong RESPONSES = new AtomicLong();
        private static boolean installed;
        private static String firstResponse;

        private FusekiMeter() {
        }

        /** What the server saw since a mark. */
        record Reading(long requests, long serverMs) {
        }

        static synchronized void install() {
            if (installed) {
                return;
            }
            Logger fusekiLog = (Logger) LoggerFactory.getLogger("org.apache.jena.fuseki.Fuseki");
            fusekiLog.setLevel(Level.INFO);
            fusekiLog.setAdditive(false);
            AppenderBase<ILoggingEvent> counter = new AppenderBase<>() {
                @Override
                protected void append(ILoggingEvent event) {
                    String message = event.getFormattedMessage();
                    if (REQUEST.matcher(message).matches()) {
                        REQUESTS.incrementAndGet();
                        return;
                    }
                    Matcher response = RESPONSE.matcher(message);
                    if (response.matches()) {
                        RESPONSES.incrementAndGet();
                        SERVER_MS.addAndGet(Long.parseLong(response.group(1)));
                        if (firstResponse == null) {
                            firstResponse = message;
                            LOGGER.info("Fuseki response line format: '{}'", message);
                        }
                    }
                }
            };
            counter.start();
            fusekiLog.addAppender(counter);
            installed = true;
        }

        static Reading mark() {
            return new Reading(REQUESTS.get(), SERVER_MS.get());
        }

        static Reading since(Reading mark) {
            return new Reading(REQUESTS.get() - mark.requests(), SERVER_MS.get() - mark.serverMs());
        }
    }
}
