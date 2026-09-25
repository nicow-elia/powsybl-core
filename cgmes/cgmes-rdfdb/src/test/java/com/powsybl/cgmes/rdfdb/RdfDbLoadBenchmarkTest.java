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
import com.powsybl.cgmes.model.CgmesModel;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.NetworkFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Is loading CGMES out of an RDF database faster than reading the files?
 *
 * <p>The whole point of splitting a CGMES import into <em>files &rarr; database</em> and <em>database &rarr;
 * IIDM</em> is that the second half runs over and over while the first runs once. It is only worth doing if the
 * second half is at least as fast as reading the files, and that is not obvious: the database path pays for HTTP
 * and for a second serialisation of every statement, while the file path pays for RDF/XML parsing and is strictly
 * sequential. This test measures both, splits the database load into its phases, and fails the build if the
 * database path is more than 1.5 times the file path on the primary backend.</p>
 *
 * <h2>Measured numbers</h2>
 * <p>8 cores, 62 GB RAM, Java 21, loopback Fuseki, medians of ten runs after three warm-ups, milliseconds.
 * {@code a} = {@code Network.read} of the files, split into reading the triple store and converting;
 * {@code u} = uploading the files into a scenario (paid once, not part of a load);
 * {@code b} = the wall clock of {@code RdfDbNetworkLoader.load} with a cold cache, reported alongside the phase
 * split it produces (listing the graphs, fetching and parsing them, filling the local store, describing it and
 * converting);
 * {@code c} = the same load with a warm {@link GraphCache}; {@code r} = the same load in
 * {@link RdfDatabase.QueryMode#REMOTE}.</p>
 *
 * <pre>
 * fixture                            backend         a(file)   a:read   a:conv    u(up)    b(db)  c(warm)      b/a
 * svedala(CGMES3,14MB)               fuseki-txnmem      1126      898      223     2533      430      307     0.38
 * svedala(CGMES3,14MB)               memory             1126      898      223      950      272      236     0.24
 * svedala(CGMES3,14MB)               fuseki-remote      1126        -        -        -     1675        -     1.49
 * smallNodeBreaker(CIM16,11MB)       fuseki-txnmem       462      329      130     1588      310      220     0.67
 * smallNodeBreaker(CIM16,11MB)       memory              462      329      130      372      186      174     0.40
 * smallNodeBreaker(CIM16,11MB)       fuseki-remote       462        -        -        -     1343        -     2.91
 * microGridBaseCaseBE(CIM16,1.9MB)   fuseki-txnmem        47       22       25       75       40       31     0.85
 * microGridBaseCaseBE(CIM16,1.9MB)   memory               47       22       25       20       26       26     0.55
 * microGridBaseCaseBE(CIM16,1.9MB)   fuseki-remote        47        -        -        -      266        -     5.66
 * svedala(CGMES3,14MB)               fuseki-tdb2        1061      901      161     5191      370      235     0.35
 * smallNodeBreaker(CIM16,11MB)       fuseki-tdb2         442      321      120     4548      320      189     0.72
 * microGridBaseCaseBE(CIM16,1.9MB)   fuseki-tdb2          44       22       22     1628       38       29     0.86
 *
 * svedala on fuseki-txnmem, phases: listGraphs 6, fetch 53, parse 360, store 169, describe 15, convert 170
 * </pre>
 *
 * <p>The target &mdash; a database load on loopback no more expensive than a file import &mdash; is met on every
 * fixture, with a factor of nearly three to spare on the largest one. {@code REMOTE} mode is slower, as designed:
 * it trades a handful of bulk transfers for eighty round trips.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbLoadBenchmarkTest {

    private static final int WARMUPS = Integer.getInteger("powsybl.rdfdb.benchmark.warmups", 3);
    private static final int RUNS = Integer.getInteger("powsybl.rdfdb.benchmark.runs", 10);
    private static final int SECONDARY_WARMUPS = 1;
    private static final int SECONDARY_RUNS = 3;
    private static final boolean STRICT = Boolean.getBoolean("powsybl.rdfdb.benchmark.strict");

    /** The hard bound: a database load may not be worse than this multiple of a file import. */
    private static final double HARD_BOUND = 1.5;
    /** Head-room of the warm-against-cold comparison, the fixed cost of a small fixture (see the assertion). */
    private static final long WARM_CACHE_ALLOWANCE_MS = 30;

    /** The sanity bound on the one-off upload, relative to the time a file import spends filling a triple store. */
    private static final long UPLOAD_BOUND = 6L;

    private record Fixture(String name, Supplier<ReadOnlyDataSource> dataSource, boolean targetted) {

        /** A scenario name derived from the fixture name: one scenario per fixture, never shared. */
        String scenario() {
            return name.replaceAll("[^A-Za-z0-9]", "-");
        }
    }

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("svedala(CGMES3,14MB)", () -> Cgmes3Catalog.svedala().dataSource(), true),
            new Fixture("smallNodeBreaker(CIM16,11MB)",
                    () -> CgmesConformity1Catalog.smallNodeBreaker().dataSource(), true),
            new Fixture("microGridBaseCaseBE(CIM16,1.9MB)",
                    () -> CgmesConformity1Catalog.microGridBaseCaseBE().dataSource(), false));

    private static Properties params() {
        Properties p = new Properties();
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        return p;
    }

    @Test
    void benchmark(@org.junit.jupiter.api.io.TempDir Path tempDir) {
        List<String> table = new ArrayList<>();
        List<String> missedTargets = new ArrayList<>();
        table.add(String.format("%-34s %-14s %8s %8s %8s %8s %8s %8s %8s",
                "fixture", "backend", "a(file)", "a:read", "a:conv", "u(up)", "b(db)", "c(warm)", "b/a"));

        try (EmbeddedFuseki txnMem = EmbeddedFuseki.inMemory()) {
            for (Fixture fixture : FIXTURES) {
                FileTimings file = measureFileImport(fixture, WARMUPS, RUNS);

                DbTimings primary = measureDatabase(fixture, txnMem.database(), WARMUPS, RUNS, "fuseki-txnmem");
                table.add(row(fixture, "fuseki-txnmem", file, primary));
                assertHardBound(fixture, "fuseki-txnmem", file, primary);
                checkTarget(fixture, file, primary, missedTargets);

                DbTimings memory = measureDatabase(fixture, RdfDatabase.inMemory("bench-" + fixture.scenario()),
                        SECONDARY_WARMUPS, SECONDARY_RUNS, "memory");
                table.add(row(fixture, "memory", file, memory));

                DbTimings remote = measureRemote(fixture, txnMem.database());
                table.add(String.format("%-34s %-14s %8d %8s %8s %8s %8d %8s %8.2f",
                        fixture.name(), "fuseki-remote", file.total, "-", "-", "-", remote.total, "-",
                        remote.total / (double) file.total));
            }

            Path tdb2Dir = tempDir.resolve("tdb2-bench");
            try (EmbeddedFuseki tdb2 = EmbeddedFuseki.tdb2(tdb2Dir)) {
                for (Fixture fixture : FIXTURES) {
                    FileTimings file = measureFileImport(fixture, SECONDARY_WARMUPS, SECONDARY_RUNS);
                    DbTimings db = measureDatabase(fixture, tdb2.database(), SECONDARY_WARMUPS, SECONDARY_RUNS,
                            "fuseki-tdb2");
                    table.add(row(fixture, "fuseki-tdb2", file, db));
                }
            }
        }

        table.forEach(LOGGER::info);
        if (missedTargets.isEmpty()) {
            LOGGER.info("TARGET MET: a database load on loopback Fuseki is at most as expensive as a file import"
                    + " for every targetted fixture");
        } else {
            missedTargets.forEach(LOGGER::warn);
            if (STRICT) {
                throw new AssertionError("TARGET MISSED: " + missedTargets);
            }
        }
    }

    private void assertHardBound(Fixture fixture, String backend, FileTimings file, DbTimings db) {
        double ratio = db.total / (double) file.total;
        assertTrue(ratio <= HARD_BOUND, () -> String.format(
                "REGRESSION: loading %s from %s took %d ms against %d ms for the file import (ratio %.2f > %.2f)",
                fixture.name(), backend, db.total, file.total, ratio, HARD_BOUND));
        // What a warm cache promises is the fetch and the N-Triples parsing, not the filling of the local
        // store: the CGMES query catalogs need a store, so the statements have to be put into one either way.
        // The allowance is the fixed cost the comparison cannot see on the MicroGrid: the fetch and parse a warm
        // cache saves there are ~20 ms of a 40 ms load, and a whole-module JVM moves either figure by that much
        // (40 vs 38 ms inside verify, reports 12 and 16). The bound stays relative on every larger fixture
        assertTrue(db.warm <= db.total + WARM_CACHE_ALLOWANCE_MS, () -> String.format(
                "A warm cache must be cheaper than a cold one: warm load of %s took %d ms, cold %d ms (allowance %d ms)",
                fixture.name(), db.warm, db.total, WARM_CACHE_ALLOWANCE_MS));
        assertTrue(db.warm <= file.total, () -> String.format(
                "A warm cache must beat a file import: warm load of %s took %d ms, the file import %d ms",
                fixture.name(), db.warm, file.total));
        // The upload is paid once and is not part of a load, so this is only a sanity bound: it is there to
        // catch a regression into per-statement INSERT DATA, which would be two orders of magnitude worse, not
        // to pin the cost of a server parsing and indexing a whole model.
        assertTrue(db.upload <= UPLOAD_BOUND * file.readCgmes + 1500, () -> String.format(
                "Uploading %s took %d ms, more than %d times the %d ms the file import needs to fill a local"
                        + " triple store", fixture.name(), db.upload, UPLOAD_BOUND, file.readCgmes));
    }

    private void checkTarget(Fixture fixture, FileTimings file, DbTimings db, List<String> missed) {
        if (!fixture.targetted()) {
            return;
        }
        double ratio = db.total / (double) file.total;
        if (ratio > 1.0) {
            missed.add(String.format("TARGET MISSED ratio=%.2f for %s (db %d ms vs file %d ms;"
                            + " fetch %d parse %d store %d convert %d)",
                    ratio, fixture.name(), db.total, file.total, db.fetch, db.parse, db.store, db.convert));
        }
    }

    private static String row(Fixture fixture, String backend, FileTimings file, DbTimings db) {
        return String.format("%-34s %-14s %8d %8d %8d %8d %8d %8d %8.2f",
                fixture.name(), backend, file.total, file.readCgmes, file.convert,
                db.upload, db.total, db.warm, db.total / (double) file.total);
    }

    // ------------------------------------------------------------------ measurements

    private record FileTimings(long total, long readCgmes, long convert) {
    }

    private record DbTimings(long upload, long total, long warm, long listGraphs, long fetch, long parse,
                             long store, long describe, long convert) {
    }

    private FileTimings measureFileImport(Fixture fixture, int warmups, int runs) {
        Properties p = params();
        ReadOnlyDataSource ds = fixture.dataSource().get();
        CgmesImport importer = new CgmesImport(com.powsybl.commons.config.PlatformConfig.defaultConfig());
        List<Long> totals = new ArrayList<>();
        List<Long> reads = new ArrayList<>();
        List<Long> converts = new ArrayList<>();
        for (int i = 0; i < warmups + runs; i++) {
            long t0 = System.nanoTime();
            CgmesModel cgmes = importer.readCgmes(ds, p, ReportNode.NO_OP);
            long t1 = System.nanoTime();
            importer.convert(cgmes, ds.getBaseName(), NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            long t2 = System.nanoTime();
            if (i >= warmups) {
                reads.add((t1 - t0) / 1_000_000);
                converts.add((t2 - t1) / 1_000_000);
                totals.add((t2 - t0) / 1_000_000);
            }
        }
        return new FileTimings(median(totals), median(reads), median(converts));
    }

    private DbTimings measureDatabase(Fixture fixture, RdfDatabase database, int warmups, int runs, String backend) {
        Properties p = params();
        ReadOnlyDataSource ds = fixture.dataSource().get();
        String scenario = fixture.scenario();
        List<Long> uploads = new ArrayList<>();
        List<Long> totals = new ArrayList<>();
        Map<String, List<Long>> phases = new LinkedHashMap<>();
        try (RdfDbConnection db = RdfDbConnection.open(database)) {
            for (int i = 0; i < warmups + runs; i++) {
                long t0 = System.nanoTime();
                db.loadCgmes(scenario, ds, null, p, ReportNode.NO_OP);
                long upload = (System.nanoTime() - t0) / 1_000_000;

                long loadStart = System.nanoTime();
                RdfDbNetworkLoader.LoadResult result = RdfDbNetworkLoader.loadWithStatistics(db, scenario,
                        NetworkFactory.findDefault(), p, ReportNode.NO_OP);
                long wallClock = (System.nanoTime() - loadStart) / 1_000_000;
                if (i >= warmups) {
                    uploads.add(upload);
                    LoadStatistics s = result.statistics();
                    // The wall clock, not the sum of the phase timers: a phase that is ever forgotten must not
                    // silently make the reported number smaller than the load really is.
                    totals.add(wallClock);
                    add(phases, "listGraphs", s.listGraphs());
                    add(phases, "fetch", s.fetch());
                    add(phases, "parse", s.parse());
                    add(phases, "store", s.store());
                    add(phases, "describe", s.describe());
                    add(phases, "convert", s.convert());
                }
            }
        }
        long warm = measureWarmCache(fixture, database, scenario + "-warm");
        LOGGER.info("{} / {}: phases (median ms) {}", fixture.name(), backend,
                phases.entrySet().stream()
                        .map(e -> e.getKey() + "=" + median(e.getValue()))
                        .toList());
        return new DbTimings(median(uploads), median(totals), warm,
                median(phases.get("listGraphs")), median(phases.get("fetch")), median(phases.get("parse")),
                median(phases.get("store")), median(phases.get("describe")), median(phases.get("convert")));
    }

    private long measureWarmCache(Fixture fixture, RdfDatabase database, String scenario) {
        Properties p = params();
        ReadOnlyDataSource ds = fixture.dataSource().get();
        GraphCache cache = new GraphCache().trustImmutableGraphs(true);
        try (RdfDbConnection db = RdfDbConnection.open(database.withCache(cache))) {
            db.loadCgmes(scenario, ds, null, p, ReportNode.NO_OP);
            RdfDbNetworkLoader.load(db, scenario, NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            List<Long> totals = new ArrayList<>();
            for (int i = 0; i < SECONDARY_WARMUPS + SECONDARY_RUNS; i++) {
                long t0 = System.nanoTime();
                RdfDbNetworkLoader.load(db, scenario, NetworkFactory.findDefault(), p, ReportNode.NO_OP);
                if (i >= SECONDARY_WARMUPS) {
                    totals.add((System.nanoTime() - t0) / 1_000_000);
                }
            }
            return median(totals);
        }
    }

    private DbTimings measureRemote(Fixture fixture, RdfDatabase database) {
        Properties p = params();
        ReadOnlyDataSource ds = fixture.dataSource().get();
        RdfDatabase remote = database.withQueryMode(RdfDatabase.QueryMode.REMOTE);
        List<Long> totals = new ArrayList<>();
        try (RdfDbConnection db = RdfDbConnection.open(remote)) {
            String scenario = fixture.scenario() + "-remote";
            db.loadCgmes(scenario, ds, null, p, ReportNode.NO_OP);
            for (int i = 0; i < 1 + SECONDARY_RUNS; i++) {
                long t0 = System.nanoTime();
                RdfDbNetworkLoader.load(db, scenario, NetworkFactory.findDefault(), p, ReportNode.NO_OP);
                if (i >= 1) {
                    totals.add((System.nanoTime() - t0) / 1_000_000);
                }
            }
        }
        return new DbTimings(0, median(totals), 0, 0, 0, 0, 0, 0, 0);
    }

    private static void add(Map<String, List<Long>> phases, String name, Duration duration) {
        phases.computeIfAbsent(name, k -> new ArrayList<>()).add(duration.toMillis());
    }

    private static long median(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return sorted.get(sorted.size() / 2);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(RdfDbLoadBenchmarkTest.class);
}
