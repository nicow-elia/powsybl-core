/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conformity.Cgmes3Catalog;
import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.CgmesExportContext;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport.UnsupportedChangeBehavior;
import com.powsybl.cgmes.conversion.export.SteadyStateHypothesisExport;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import com.powsybl.commons.xml.XmlUtil;
import com.powsybl.iidm.network.CurrentLimits;
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
 * Guards the response time of the difference model export.
 *
 * <p>A difference export runs the change mapping twice, so it is expected to cost roughly twice a partial SSH
 * export of the same changes, and it still has to stay far below a full steady state hypothesis and far below the
 * 100 ms budget of an interactive call.</p>
 *
 * <p>The numbers below are logged at INFO, which the {@code logback-test.xml} of this module suppresses (its root
 * level is {@code error}). To see them, run with
 * {@code -Dlogback.configurationFile=<a file raising this class to info>} or raise the level in that file.</p>
 *
 * <p>Indicative measurements, best of twenty runs after twenty warm-up runs, on the svedala model used here:</p>
 * <pre>
 *   1 switch change, difference             0.3 ms
 *   500 switch changes, difference          6.3 ms
 *   500 switch changes, partial SSH         2.8 ms
 *   500 switch changes, statements only     2.3 ms
 *   500 switch changes, written document    4.0 ms
 *   500 switch changes, export context      0.05 ms
 *   500 switch changes, compaction          0.06 ms
 *   191 mixed equipment changes, difference 3.6 ms
 *   191 mixed equipment changes, partial    1.7 ms
 *   full SSH                                8.4 ms
 * </pre>
 *
 * <p>The equipment values of work package 5 cost the same order of magnitude (micro grid for the CGMES 2.4.15
 * limits, svedala for the impedances):</p>
 * <pre>
 *   1 current limit, equipment difference          0.6 ms
 *   18 current limits, equipment difference        1.4 ms
 *   90 line impedances of svedala                  1.2 ms
 *   291 mixed equipment and steady state changes   2.1 ms
 * </pre>
 *
 * <p>The index of the CGMES limit identifiers is built once per export and shared by both directions, which is why
 * eighteen limits cost roughly twice one limit rather than eighteen times.</p>
 *
 * <p>The cost tracks the change log, not the model: the regulating control index and the export context are built
 * once per export and shared by both directions, which the numbers above confirm (the context is 0.05 ms, the
 * compaction 0.06 ms, neither grows with the number of passes).</p>
 *
 * <p><b>Where the factor of about two and a half against a partial SSH file comes from.</b> Not from the second
 * translation pass: a JFR profile of a 500-change export (run with
 * {@code JAVA_TOOL_OPTIONS="-XX:StartFlightRecording=filename=<dir>/,settings=profile,dumponexit=true"}, which
 * surefire's forked JVM inherits) puts the great majority of the execution samples in
 * {@code ByteArrayOutputStream.write(int)}, called byte by byte by the JDK StAX {@code UTF8OutputStreamWriter} — the
 * same writer the partial SSH export uses, and a buffered stream does not help. What a difference model really pays
 * for is (a) a document about twice the size, because it holds both directions, written through that per-byte
 * writer, and (b) materialising two statement lists, including one {@code URLEncoder.encode} per subject and
 * direction in {@code CgmesExportContext.encode}. The second translation pass itself is a fraction of a millisecond
 * of a 4 ms export. Optimising this means buffering the writer or caching the encoded identifiers, not touching the
 * two passes.</p>
 *
 * <p>The ratio to a partial SSH file is measured, not predicted, and it moves between runs by a few tenths because
 * the two exports share most of their code and the JIT compiles it once for both: measuring the same difference
 * export in two different tests of this class gives 4.0 ms and 6.3 ms. The bound below therefore has room, and the
 * assertion that really guards the response time is the 100 ms budget, which every measurement stays an order of
 * magnitude below.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesDiffExportBenchmarkTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(CgmesDiffExportBenchmarkTest.class);

    /** If the export takes longer than this, something is seriously wrong. */
    private static final long MAX_EXPORT_TIME_MS = 100;

    /**
     * A difference describes two states, so it may cost about twice a partial file. Measured between 2.3 and 2.9
     * depending on how the JIT compiles the code the two exports share, hence the room to three.
     */
    private static final double MAX_RATIO_TO_PARTIAL_SSH = 3.0;

    private static final int ONE_CHANGE = 1;
    private static final int MANY_CHANGES = 500;

    private static final int WARMUP_RUNS = 20;
    private static final int MEASURED_RUNS = 20;

    private static final String REGULATING_CONTROL_PROPERTY = Conversion.PROPERTY_REGULATING_CONTROL;

    @Test
    void diffExportCostTracksTheChangeLog() {
        Network network = Network.read(Cgmes3Catalog.svedala().dataSource(), new Properties());

        List<NetworkEvent> oneEvent = recordSwitchChanges(network, ONE_CHANGE);
        List<NetworkEvent> manyEvents = recordSwitchChanges(network, MANY_CHANGES);
        double[] measured = measure(
                diffExport(network, oneEvent),
                diffExport(network, manyEvents),
                () -> PartialSshExport.toString(network, manyEvents, UnsupportedChangeBehavior.FAIL),
                () -> fullExport(network));
        double oneChange = measured[0];
        double manyChanges = measured[1];
        double partialSsh = measured[2];
        double fullExport = measured[3];

        LOGGER.info("Difference export of svedala ({} switches): {} change {} ms, {} changes {} ms,"
                        + " partial SSH of the same changes {} ms, full SSH {} ms",
                network.getSwitchCount(), ONE_CHANGE, millis(oneChange), MANY_CHANGES, millis(manyChanges),
                millis(partialSsh), millis(fullExport));

        assertTrue(manyChanges < fullExport, () -> "a difference of " + MANY_CHANGES + " changes took "
                + millis(manyChanges) + " ms, no less than the " + millis(fullExport) + " ms of a full export");
        assertTrue(oneChange < manyChanges, () -> "a difference of one change took " + millis(oneChange)
                + " ms, no less than the " + millis(manyChanges) + " ms of " + MANY_CHANGES + " changes");
        assertTrue(manyChanges < MAX_EXPORT_TIME_MS, () -> "a difference of " + MANY_CHANGES + " changes took "
                + millis(manyChanges) + " ms, more than the " + MAX_EXPORT_TIME_MS + " ms budget");
        assertTrue(manyChanges < MAX_RATIO_TO_PARTIAL_SSH * partialSsh,
                () -> "a difference of " + MANY_CHANGES + " changes took " + millis(manyChanges) + " ms, more than "
                        + MAX_RATIO_TO_PARTIAL_SSH + " times the " + millis(partialSsh) + " ms of a partial SSH file");
    }

    /**
     * Generating the statements is the step a database sink uses; serializing them is what a file costs on top.
     *
     * <p>The export context and the compaction are measured next to them, because they are the two things a second
     * translation pass could have duplicated. They cost a twentieth of a millisecond each, so it is the statements
     * and the serialization that a difference model pays for, not any structure rebuilt per pass.</p>
     */
    @Test
    void tripleGenerationAloneIsCheaperThanSerialization() {
        Network network = Network.read(Cgmes3Catalog.svedala().dataSource(), new Properties());
        List<NetworkEvent> events = recordSwitchChanges(network, MANY_CHANGES);

        double[] measured = measure(
                () -> CgmesDiffExport.toDifferences(network, events, new CgmesDiffExport.ExportOptions()).differences(),
                diffExport(network, events),
                () -> new CgmesExportContext(network),
                () -> PartialSshExport.compactEvents(events));
        double statements = measured[0];
        double document = measured[1];
        double context = measured[2];
        double compaction = measured[3];

        LOGGER.info("Difference of {} changes on svedala: statements only {} ms, written document {} ms,"
                        + " export context {} ms, compaction {} ms",
                MANY_CHANGES, millis(statements), millis(document), millis(context), millis(compaction));

        assertTrue(statements < MAX_EXPORT_TIME_MS, () -> "generating the statements of " + MANY_CHANGES
                + " changes took " + millis(statements) + " ms, more than the " + MAX_EXPORT_TIME_MS + " ms budget");
        assertTrue(statements <= document, () -> "generating the statements took " + millis(statements)
                + " ms, more than the " + millis(document) + " ms of generating and writing them");
        // The two things a pass could have duplicated are a small fraction of the export
        assertTrue(context + compaction < statements / 2, () -> "the export context (" + millis(context)
                + " ms) and the compaction (" + millis(compaction) + " ms) are no longer a small part of the "
                + millis(statements) + " ms of generating the statements");
    }

    /**
     * The switches above all map the same way. This spreads the changes over every equipment type the exporter
     * supports and the model holds, which is also what exercises the regulating control index in both directions.
     */
    @Test
    void mixedEquipmentDiffStaysWithinBudget() {
        Network network = Network.read(Cgmes3Catalog.svedala().dataSource(), new Properties());

        List<NetworkEvent> events = record(network, () -> {
            List<Runnable> changes = mixedEquipmentChanges(network);
            assertTrue(changes.size() >= MANY_CHANGES / 10,
                    () -> "the model is expected to exercise many equipment types, it produced " + changes.size() + " changes");
            changes.stream().limit(MANY_CHANGES).forEach(Runnable::run);
        });

        double[] measured = measure(
                diffExport(network, events),
                () -> PartialSshExport.toString(network, events, UnsupportedChangeBehavior.FAIL),
                () -> fullExport(network));
        double mixed = measured[0];
        double partialSsh = measured[1];
        double fullExport = measured[2];

        LOGGER.info("Difference export of svedala, {} changes over every supported type: {} ms,"
                        + " partial SSH {} ms, full SSH {} ms",
                events.size(), millis(mixed), millis(partialSsh), millis(fullExport));

        assertTrue(mixed < MAX_EXPORT_TIME_MS, () -> "a difference of " + events.size() + " mixed changes took "
                + millis(mixed) + " ms, more than the " + MAX_EXPORT_TIME_MS + " ms budget");
        assertTrue(mixed < MAX_RATIO_TO_PARTIAL_SSH * partialSsh,
                () -> "a difference of " + events.size() + " mixed changes took " + millis(mixed) + " ms, more than "
                        + MAX_RATIO_TO_PARTIAL_SSH + " times the " + millis(partialSsh) + " ms of a partial SSH file");
    }

    private static List<Runnable> mixedEquipmentChanges(Network network) {
        List<Runnable> changes = new ArrayList<>();
        network.getLoadStream().forEach(l -> changes.add(() -> l.setP0(l.getP0() + 1.0)));
        network.getGeneratorStream().forEach(g -> {
            changes.add(() -> g.setTargetP(g.getTargetP() + 1.0));
            if (g.hasProperty(REGULATING_CONTROL_PROPERTY)) {
                changes.add(() -> g.setTargetV(g.getTargetV() + 1.0));
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

    /**
     * Operational limit values are equipment data in CGMES 2.4.15, so a limit change of the micro grid produces an
     * equipment difference. The cost has to track the number of changed limits, not the number of limits the model
     * holds, and the index of the CGMES limit identifiers has to be built once for the whole export.
     */
    @Test
    void eqLimitDiffCostTracksTheChangeLog() {
        Network network = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        List<CurrentLimits> limits = currentLimitsOf(network);
        assertTrue(limits.size() > 10, () -> "the micro grid is expected to hold many current limits, it holds "
                + limits.size());

        List<NetworkEvent> oneEvent = record(network,
                () -> limits.get(0).setPermanentLimit(limits.get(0).getPermanentLimit() + 1.0));
        List<NetworkEvent> allEvents = record(network,
                () -> limits.forEach(limit -> limit.setPermanentLimit(limit.getPermanentLimit() + 1.0)));

        double[] measured = measure(
                eqDiffExport(network, oneEvent), eqDiffExport(network, allEvents), () -> fullExport(network));
        double oneLimit = measured[0];
        double allLimits = measured[1];
        double fullSsh = measured[2];

        LOGGER.info("Equipment difference of the micro grid: 1 limit {} ms, {} limits {} ms, full SSH {} ms",
                millis(oneLimit), limits.size(), millis(allLimits), millis(fullSsh));

        assertTrue(allLimits < MAX_EXPORT_TIME_MS, () -> "a difference of " + limits.size() + " limits took "
                + millis(allLimits) + " ms, more than the " + MAX_EXPORT_TIME_MS + " ms budget");
        assertTrue(oneLimit < MAX_EXPORT_TIME_MS, () -> "a difference of one limit took " + millis(oneLimit)
                + " ms, more than the " + MAX_EXPORT_TIME_MS + " ms budget");
    }

    /** Impedances are equipment data in every CIM version, and svedala is the biggest model of this suite. */
    @Test
    void impedanceDiffOfEveryLine() {
        Network network = Network.read(Cgmes3Catalog.svedala().dataSource(), new Properties());
        List<NetworkEvent> events = record(network,
                () -> network.getLineStream().forEach(line -> line.setR(line.getR() + 0.01)));
        assertTrue(!events.isEmpty(), "svedala is expected to hold lines");

        double[] measured = measure(eqDiffExport(network, events));
        LOGGER.info("Equipment difference of svedala, {} line impedances: {} ms", events.size(), millis(measured[0]));
        assertTrue(measured[0] < MAX_EXPORT_TIME_MS, () -> "a difference of " + events.size()
                + " impedances took " + millis(measured[0]) + " ms, more than the " + MAX_EXPORT_TIME_MS + " ms budget");
    }

    /** A change set that touches both profiles produces two documents and still fits in the budget. */
    @Test
    void mixedEqAndSshDiffStaysWithinBudget() {
        Network network = Network.read(Cgmes3Catalog.svedala().dataSource(), new Properties());
        List<NetworkEvent> events = record(network, () -> {
            mixedEquipmentChanges(network).stream().limit(MANY_CHANGES).forEach(Runnable::run);
            network.getLineStream().forEach(line -> line.setR(line.getR() + 0.01));
            network.getVoltageLevelStream().limit(10).forEach(voltageLevel -> {
                double low = voltageLevel.getLowVoltageLimit();
                voltageLevel.setHighVoltageLimit(Double.isNaN(low) ? 420.0 : low + 40.0);
            });
        });

        double[] measured = measure(() -> CgmesDiffExport.toDifferences(network, events,
                new CgmesDiffExport.ExportOptions()
                        .setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE)).differences());
        LOGGER.info("Mixed equipment and steady state difference of svedala, {} changes: {} ms",
                events.size(), millis(measured[0]));
        assertTrue(measured[0] < MAX_EXPORT_TIME_MS, () -> "a mixed difference of " + events.size()
                + " changes took " + millis(measured[0]) + " ms, more than the " + MAX_EXPORT_TIME_MS + " ms budget");
    }

    /** Every set of current limits of a network, on branches, three windings transformer legs and boundary lines. */
    private static List<CurrentLimits> currentLimitsOf(Network network) {
        List<CurrentLimits> limits = new ArrayList<>();
        network.getBranchStream().forEach(branch -> {
            branch.getCurrentLimits1().ifPresent(currentLimits -> limits.add((CurrentLimits) currentLimits));
            branch.getCurrentLimits2().ifPresent(currentLimits -> limits.add((CurrentLimits) currentLimits));
        });
        network.getThreeWindingsTransformerStream().forEach(transformer ->
                transformer.getLegs().forEach(leg -> leg.getCurrentLimits().ifPresent(limits::add)));
        network.getBoundaryLineStream().forEach(boundaryLine -> boundaryLine.getCurrentLimits().ifPresent(limits::add));
        return limits;
    }

    private static Supplier<String> eqDiffExport(Network network, List<NetworkEvent> events) {
        return () -> CgmesDiffExport.toString(network, events, CgmesSubset.EQUIPMENT,
                UnsupportedChangeBehavior.IGNORE);
    }

    private static Supplier<String> diffExport(Network network, List<NetworkEvent> events) {
        return () -> CgmesDiffExport.toString(network, events, CgmesSubset.STEADY_STATE_HYPOTHESIS,
                UnsupportedChangeBehavior.FAIL);
    }

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

    /**
     * The shortest run of each of the given exports, which is the measure least disturbed by the rest of the machine.
     *
     * <p>All of them are warmed up before any of them is measured: they share most of their code, so measuring one
     * before the other has finished warming up compares a compiled export with an interpreted one and inflates the
     * ratio between them.</p>
     */
    private static double[] measure(Supplier<?>... exports) {
        for (int run = 0; run < WARMUP_RUNS; run++) {
            for (Supplier<?> export : exports) {
                export.get();
            }
        }
        double[] best = new double[exports.length];
        for (int i = 0; i < exports.length; i++) {
            long bestNanos = Long.MAX_VALUE;
            for (int run = 0; run < MEASURED_RUNS; run++) {
                long startNanos = System.nanoTime();
                exports[i].get();
                bestNanos = Math.min(bestNanos, System.nanoTime() - startNanos);
            }
            best[i] = bestNanos / 1e6;
        }
        return best;
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

    private static List<NetworkEvent> recordSwitchChanges(Network network, int changeCount) {
        return record(network, () -> {
            List<Switch> switches = new ArrayList<>();
            network.getSwitchStream().forEach(switches::add);
            for (int change = 0; change < changeCount; change++) {
                Switch sw = switches.get(change % switches.size());
                sw.setOpen(!sw.isOpen());
            }
        });
    }

    private static String millis(double millis) {
        return String.format("%.2f", millis);
    }
}
