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
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading several variants while another thread moves two of them.
 *
 * <p>A day as variants is worth having because a caller can run something per variant in parallel, and the
 * promise of this work package is that a variant update only touches its own slot of the per-variant arrays.
 * This is the smoke test for it: the variants are created <em>first</em> &mdash; IIDM grows those arrays when a
 * variant is created, which is not safe while readers are running, and
 * {@code RdfDbVariantLoadOptions.setAllowVariantMultiThreadAccess} exists so that the flag is set at the one
 * moment it can be &mdash; and then four readers pinned to four variants keep summing while a writer moves two
 * others back and forth.</p>
 *
 * <p>What is asserted: no exception anywhere, and every reader sees the same sum from beginning to end.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbVariantThreadingTest {

    private static final String S = "2016-01-01";
    private static final int TIMESTEPS = 8;
    private static final int WRITES = 20;

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
    void readersOnOtherVariantsAreUndisturbed(String backend) throws InterruptedException {
        try (RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "variant-threads"))) {
            db.clear(S);
            db.snapshots(S).putFull(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource(), null,
                    SnapshotRef.of(S, "1.0"), params(), ReportNode.NO_OP);
            List<String> timesteps = new ArrayList<>();
            timesteps.add("2014-06-01T10:30:00Z");
            for (int i = 1; i < TIMESTEPS; i++) {
                String instant = String.format("2014-06-01T%02d:00:00Z", 11 + i);
                db.snapshots(S).putAsDiff(TimestepFixtures.ssh(1 + i % 3, instant, "th" + i), null,
                        new SnapshotRef(S, "1.0", instant), params(), ReportNode.NO_OP);
                timesteps.add(instant);
            }

            VariantLoadResult loaded = RdfDbNetworkLoader.loadVariants(db, S, "1.0", timesteps,
                    new RdfDbVariantLoadOptions().setAllowVariantMultiThreadAccess(true), null, params(),
                    ReportNode.NO_OP);
            assertThat(loaded.refused()).isEmpty();
            Network network = loaded.network();
            List<String> variants = loaded.bound().stream().map(VariantOutcome::variantId).toList();
            assertThat(variants).hasSize(TIMESTEPS);

            List<String> readerVariants = variants.subList(0, 4);
            List<String> writerVariants = variants.subList(4, 6);
            SnapshotRef other = new SnapshotRef(S, "1.0", timesteps.get(6));

            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(readerVariants.size());
            List<Thread> readers = new ArrayList<>();
            List<Double> expected = new ArrayList<>();
            for (String variant : readerVariants) {
                expected.add(sumOf(network, variant));
            }
            for (int i = 0; i < readerVariants.size(); i++) {
                String variant = readerVariants.get(i);
                double sum = expected.get(i);
                Thread reader = new Thread(() -> {
                    network.getVariantManager().setWorkingVariant(variant);
                    started.countDown();
                    while (!stop.get()) {
                        double seen = network.getLoadStream().mapToDouble(Load::getP0).sum();
                        if (Math.abs(seen - sum) > 1e-9) {
                            failure.compareAndSet(null, new IllegalStateException("variant " + variant
                                    + " read " + seen + " instead of " + sum));
                            return;
                        }
                    }
                }, "reader-" + variant);
                // An exception in a reader is the failure this test is looking for, whatever it is
                reader.setUncaughtExceptionHandler((thread, thrown) -> {
                    failure.compareAndSet(null, thrown);
                    started.countDown();
                });
                readers.add(reader);
                reader.start();
            }
            assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

            // Each writer variant alternates between its own timestep and one it does not hold, so that every
            // single write really applies a difference; reading the current timestep back would make all but the
            // first write a NOOP
            Map<String, SnapshotRef> home = new LinkedHashMap<>();
            writerVariants.forEach(variant ->
                    home.put(variant, new SnapshotRef(S, "1.0", timestepOf(network, variant))));
            try {
                for (int i = 0; i < WRITES; i++) {
                    for (String variant : writerVariants) {
                        SnapshotRef there = i % 2 == 0 ? other : home.get(variant);
                        UpdateResult moved = RdfDbNetworkLoader.update(network, db, there,
                                new RdfDbUpdateOptions().setTargetVariant(variant), params(),
                                ReportNode.NO_OP);
                        assertThat(moved.route()).as("write " + i + " of " + variant)
                                .isEqualTo(UpdateResult.Route.DIFF_APPLIED);
                    }
                }
            } finally {
                stop.set(true);
                for (Thread reader : readers) {
                    reader.join(30_000);
                }
            }

            assertThat(failure.get()).isNull();
            for (int i = 0; i < readerVariants.size(); i++) {
                assertThat(sumOf(network, readerVariants.get(i))).isEqualTo(expected.get(i));
            }
            // Every variant still says which snapshot it is
            RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
            variants.forEach(variant ->
                    assertThat(provenance.variantBinding(variant).orElseThrow().snapshotIri()).isNotNull());
        }
    }

    private static String timestepOf(Network network, String variant) {
        return network.getExtension(RdfDbProvenance.class).variantBinding(variant).orElseThrow().timestep();
    }

    private static double sumOf(Network network, String variant) {
        String working = network.getVariantManager().getWorkingVariantId();
        network.getVariantManager().setWorkingVariant(variant);
        try {
            return network.getLoadStream().mapToDouble(Load::getP0).sum();
        } finally {
            network.getVariantManager().setWorkingVariant(working);
        }
    }
}
