/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conformity.Cgmes3Catalog;
import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.export.CgmesExportContext;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport.UnsupportedChangeBehavior;
import com.powsybl.cgmes.conversion.export.SteadyStateHypothesisExport;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import com.powsybl.commons.xml.XmlUtil;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkEventRecorder;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLStreamException;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the response time of the partial Steady State Hypothesis export. We expect full export > many changes > one change
 *
 *
 * <p>The numbers below are logged at INFO, which the {@code logback-test.xml} of this module suppresses (its root
 * level is {@code error}). To see them, run with
 * {@code -Dlogback.configurationFile=<a file raising this class to info>} or raise the level in that file.</p>
 *
 * <p>Indicative measurements, best of ten runs after warm up, on the svedala model used here:</p>
 * <pre>
 *   1 switch change             0.22 ms
 *   500 switch changes          2.34 ms
 *   230 mixed equipment changes 3.89 ms
 *   1 regulation change         0.41 ms
 *   85 regulation changes       1.96 ms
 *   full SSH                    9.95 ms
 * </pre>
 *
 * <p>Every partial export stays an order of magnitude below the full one and far below the 100 ms budget. The
 * regulating control index, which is the only part of the exporter that walks the whole network, costs about
 * 0.2 ms on this model and is built once per export, on the first change that needs it: an export changing no
 * regulation never pays for it.</p>
 *
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class PartialSshExportBenchmarkTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(PartialSshExportBenchmarkTest.class);

    /**
     * If the export takes longer than this, something is seriously wrong.
     */
    private static final long MAX_EXPORT_TIME_MS = 100;

    /** The two ends of the range measured here, chosen far apart so the comparison has the most margin. */
    private static final int ONE_CHANGE = 1;
    private static final int MANY_CHANGES = 500;
    /** Enough regulation changes to make the cost of the regulating control index visible if it were rebuilt. */
    private static final int REGULATION_CHANGES = 200;

    private static final String REGULATING_CONTROL_PROPERTY = Conversion.PROPERTY_REGULATING_CONTROL;

    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 10;

    @Test
    void exportCostTracksTheChangeLogAndNotTheModel() {
        Network network = Network.read(Cgmes3Catalog.svedala().dataSource(), new Properties());

        double oneChange = bestMillis(partialExport(network, recordSwitchChanges(network, ONE_CHANGE)));
        double manyChanges = bestMillis(partialExport(network, recordSwitchChanges(network, MANY_CHANGES)));
        double fullExport = bestMillis(() -> fullExport(network));

        LOGGER.info("Partial SSH export of svedala ({} switches): {} change {} ms, {} changes {} ms, full {} ms",
                network.getSwitchCount(), ONE_CHANGE, millis(oneChange), MANY_CHANGES, millis(manyChanges),
                millis(fullExport));

        assertCheaper(manyChanges, MANY_CHANGES + " changes", fullExport, "a full steady state hypothesis");
        assertCheaper(oneChange, ONE_CHANGE + " change", manyChanges, MANY_CHANGES + " changes");
        assertTrue(manyChanges < MAX_EXPORT_TIME_MS, () -> "exporting " + MANY_CHANGES + " changes took "
                + millis(manyChanges) + " ms, more than the " + MAX_EXPORT_TIME_MS + " ms budget");
    }

    /**
     * The switches of the test above all map the same way. This one spreads the changes over every equipment type
     * the exporter supports and that the model holds, so that the cost of the type dispatch, of the class lookups
     * and of the regulating control index is measured as well.
     */
    @Test
    void mixedEquipmentExportStaysWithinBudget() {
        Network network = Network.read(Cgmes3Catalog.svedala().dataSource(), new Properties());

        List<NetworkEvent> events = record(network, () -> {
            List<Runnable> changes = mixedEquipmentChanges(network);
            assertTrue(changes.size() >= MANY_CHANGES / 10,
                    () -> "the model is expected to exercise many equipment types, it produced " + changes.size() + " changes");
            changes.stream().limit(MANY_CHANGES).forEach(Runnable::run);
        });

        double mixed = bestMillis(partialExport(network, events));
        double fullExport = bestMillis(() -> fullExport(network));

        LOGGER.info("Partial SSH export of svedala, {} changes over every supported type: {} ms, full {} ms",
                events.size(), millis(mixed), millis(fullExport));

        assertCheaper(mixed, events.size() + " mixed changes", fullExport, "a full steady state hypothesis");
        assertTrue(mixed < MAX_EXPORT_TIME_MS, () -> "exporting " + events.size() + " mixed changes took "
                + millis(mixed) + " ms, more than the " + MAX_EXPORT_TIME_MS + " ms budget");
    }

    /**
     * The index of the users of every regulating control is built once, on the first change that needs it, by one
     * pass over the regulating equipment of the network. This measures that an export changing no regulation never
     * pays for it, and that one changing many regulations pays for it once.
     */
    @Test
    void regulatingControlIndexIsBuiltOnceAndStaysCheap() {
        Network network = Network.read(Cgmes3Catalog.svedala().dataSource(), new Properties());

        List<NetworkEvent> oneTarget = record(network, () ->
                network.getGeneratorStream().filter(g -> g.hasProperty(REGULATING_CONTROL_PROPERTY))
                        .limit(1)
                        .forEach(g -> g.setTargetV(g.getTargetV() + 1.0)));
        assertTrue(!oneTarget.isEmpty(), "the model is expected to hold a generator with a regulating control");

        List<NetworkEvent> manyRegulations = record(network, () -> {
            List<Runnable> changes = new ArrayList<>();
            network.getGeneratorStream().filter(g -> g.hasProperty(REGULATING_CONTROL_PROPERTY))
                    .forEach(g -> changes.add(() -> g.setTargetV(g.getTargetV() + 1.0)));
            network.getShuntCompensatorStream().filter(s -> s.hasProperty(REGULATING_CONTROL_PROPERTY))
                    .forEach(s -> changes.add(() -> s.setTargetV(s.getTargetV() + 1.0)));
            changes.stream().limit(REGULATION_CHANGES).forEach(Runnable::run);
        });

        double oneChange = bestMillis(partialExport(network, oneTarget));
        double many = bestMillis(partialExport(network, manyRegulations));
        double fullExport = bestMillis(() -> fullExport(network));

        LOGGER.info("Partial SSH export of svedala regulations: 1 change {} ms, {} changes {} ms, full {} ms",
                millis(oneChange), manyRegulations.size(), millis(many), millis(fullExport));

        assertTrue(oneChange < MAX_EXPORT_TIME_MS, () -> "one regulation change took " + millis(oneChange)
                + " ms, more than the " + MAX_EXPORT_TIME_MS + " ms budget");
        assertTrue(many < MAX_EXPORT_TIME_MS, () -> "exporting " + manyRegulations.size()
                + " regulation changes took " + millis(many) + " ms, more than the " + MAX_EXPORT_TIME_MS + " ms budget");
        assertCheaper(many, manyRegulations.size() + " regulation changes", fullExport, "a full steady state hypothesis");
    }

    /**
     * One change of every supported type the model holds: an injection setpoint, a generator target and its
     * regulation, a tap position, a shunt section count and a static var compensator setpoint.
     */
    private static List<Runnable> mixedEquipmentChanges(Network network) {
        List<Runnable> changes = new ArrayList<>();
        network.getLoadStream().forEach(l -> changes.add(() -> l.setP0(l.getP0() + 1.0)));
        network.getGeneratorStream().forEach(g -> {
            changes.add(() -> g.setTargetP(g.getTargetP() + 1.0));
            if (g.hasProperty(REGULATING_CONTROL_PROPERTY)) {
                changes.add(() -> g.setTargetV(g.getTargetV() + 1.0));
                // Toggled once, so that the change is a real one and not a value the network already had
                changes.add(() -> g.setVoltageRegulatorOn(!g.isVoltageRegulatorOn()));
            }
        });
        network.getTwoWindingsTransformerStream()
                .filter(t -> t.hasRatioTapChanger() && t.getRatioTapChanger().getTapPosition() < t.getRatioTapChanger().getHighTapPosition())
                .forEach(t -> changes.add(() -> t.getRatioTapChanger().setTapPosition(t.getRatioTapChanger().getTapPosition() + 1)));
        network.getShuntCompensatorStream()
                .filter(s -> s.getSectionCount() < s.getMaximumSectionCount())
                .forEach(s -> changes.add(() -> s.setSectionCount(s.getSectionCount() + 1)));
        network.getStaticVarCompensatorStream()
                .forEach(s -> changes.add(() -> s.setVoltageSetpoint(s.getVoltageSetpoint() + 1.0)));
        return changes;
    }

    private static List<NetworkEvent> record(Network network, Runnable changes) {
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        network.addListener(recorder);
        try {
            changes.run();
            return List.copyOf(recorder.getEvents());
        } finally {
            network.removeListener(recorder);
        }
    }

    private static void assertCheaper(double millis, String what, double thanMillis, String than) {
        assertTrue(millis < thanMillis, () -> "a partial export of " + what + " took " + millis(millis)
                + " ms, no less than the " + millis(thanMillis) + " ms of " + than);
    }

    /** The shortest run of the given export, which is the measure least disturbed by the rest of the machine. */
    private static double bestMillis(Supplier<String> export) {
        for (int run = 0; run < WARMUP_RUNS; run++) {
            export.get();
        }
        long bestNanos = Long.MAX_VALUE;
        for (int run = 0; run < MEASURED_RUNS; run++) {
            long startNanos = System.nanoTime();
            export.get();
            bestNanos = Math.min(bestNanos, System.nanoTime() - startNanos);
        }
        return bestNanos / 1e6;
    }

    private static Supplier<String> partialExport(Network network, List<NetworkEvent> events) {
        return () -> PartialSshExport.toString(network, events, UnsupportedChangeBehavior.FAIL);
    }

    /**
     * A full steady state hypothesis of the same network, written the same way, so that the comparison is between
     * the two exports and not between two ways of writing a document. The export context is built inside both, as
     * it is part of what a caller pays.
     */
    private static String fullExport(Network network) {
        StringWriter out = new StringWriter();
        try {
            SteadyStateHypothesisExport.write(network, XmlUtil.initializeWriter(true, "    ", out),
                    new CgmesExportContext(network));
        } catch (XMLStreamException e) {
            throw new UncheckedXmlStreamException(e);
        }
        return out.toString();
    }

    /** Toggle the given number of switches and return the changes that were recorded for it. */
    private static List<NetworkEvent> recordSwitchChanges(Network network, int changeCount) {
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        network.addListener(recorder);
        try {
            List<Switch> switches = new ArrayList<>();
            network.getSwitchStream().forEach(switches::add);
            for (int change = 0; change < changeCount; change++) {
                Switch sw = switches.get(change % switches.size());
                sw.setOpen(!sw.isOpen());
            }
            return List.copyOf(recorder.getEvents());
        } finally {
            network.removeListener(recorder);
        }
    }

    private static String millis(double millis) {
        return String.format("%.2f", millis);
    }
}
