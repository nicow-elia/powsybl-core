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
import com.powsybl.cgmes.conversion.diff.CgmesDiffImport;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.CgmesExportContext;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport.UnsupportedChangeBehavior;
import com.powsybl.cgmes.conversion.export.SteadyStateHypothesisExport;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.DifferenceModelWriter;
import com.powsybl.commons.datasource.MemDataSource;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.commons.xml.XmlUtil;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkEventRecorder;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Parts C and D of the scale campaign: the recorder-based exporters and the receiving side, file-based, on grids
 * up to IGM size.
 *
 * <p><b>C</b>, on the file-imported network of each grid ({@code snb} and {@code sv<N>} of
 * {@code -Dpowsybl.bench.grids}), three change sets recorded with {@link NetworkEventRecorder}: <b>rich</b>
 * (every generator {@code targetP}, every load {@code p0}, {@code targetV} where the generator has a regulating
 * control &mdash; the production shape), <b>switches</b> (500&nbsp;&times;&nbsp;N toggles) and <b>mixed</b> (the
 * mixed equipment changes of {@code PartialSshExportBenchmarkTest}, without limit). Best of the measured runs
 * (10/5/3 on 1&times;/6&times;/20&times;, after 3/2/1 warm-ups): {@code PartialSshExport.toString},
 * {@code CgmesDiffExport.toDifferences(...).differences()} (statements, what the database sink uses),
 * {@code CgmesDiffExport.toString} (the document), a full SSH export for scale and
 * {@code new CgmesExportContext(network)} alone. Structural changes are outside the recorder exporters by design
 * (there are no add/remove events).</p>
 *
 * <p><b>D</b>: a receiver imported from the same files; {@link CgmesDiffImport#apply}/{@code revert} alternating
 * with the rich and the switch difference (written and parsed back, as a receiver gets them); {@code
 * Network.update} with the partial SSH file of the rich change, alternating with the file of its inverse
 * ({@code USE_PREVIOUS_VALUES_DURING_UPDATE=true}), so every update does work; one {@code Network.read} as the
 * full-route reference. Supersedes checking is off for the alternation (one receiver, two difference sets).</p>
 *
 * <p>Gates, relative only: partial &lt; full SSH export; diff document {@code <= 3 x} partial; rich partial export
 * {@code sv20/sv6 <= 5}; {@code apply(rich) < Network.read}; {@code apply(rich) <= 1.25 x update + 5 ms};
 * {@code apply(sv20)/apply(sv6) <= 5}. Reported: 100 ms targets for the rich export and apply at {@code sv20}.</p>
 *
 * <h2>Measured numbers</h2>
 * <p>8 cores, Java 21, {@code -Xmx24g}, 2026-09-22, best-of milliseconds (full tables in the campaign report
 * {@code 16-benchmark-campaign.md}):</p>
 * <pre>
 * C grid  set       events  partial  diffStmt  diffDoc  fullSSH  context
 *   sv1   rich         151     2.11      2.12     4.11    10.63     0.05
 *   sv6   rich         906     6.77      6.46    15.00    51.73     0.18
 *   sv20  rich        3020    20.95     20.07    45.80   166.09     0.46
 *   sv20  switches   10000    30.52     27.00    62.02   166.09     0.46
 * D grid   read  apply(rich)  apply(switches)  Network.update(rich)
 *   sv1    1141        12.12            10.26                 14.28
 *   sv6    3129        34.75            39.29                 35.11
 *   sv20  10292        79.10           110.07                 82.68
 * After T6 #5 (buffered writer under the StAX writer; sv6,sv20 runs, median of three back-to-back pairs, the
 * before column re-measured, logs/bench16-t6-5-*.log): sv20 partial rich 24.34 -> 19.71, switches 32.91 -> 27.69,
 * diffDoc rich 48.71 -> 37.74, apply(rich) 90.21 -> 87.95 (noise).
 * </pre>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class ScaleChangeBenchmarkTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScaleChangeBenchmarkTest.class);

    private static final String RC = Conversion.PROPERTY_REGULATING_CONTROL;
    private static final double TARGET_MS = 100;

    private final SoftAssertions softly = new SoftAssertions();

    private static int measuredRuns(BenchMeters.Grid grid) {
        int dflt = grid.replicas() >= 20 ? 3 : grid.replicas() >= 6 ? 5 : 10;
        return Integer.getInteger("powsybl.bench.runs", BenchMeters.explicitGrids() ? dflt : 3);
    }

    private static int warmupRuns(BenchMeters.Grid grid) {
        int dflt = grid.replicas() >= 20 ? 1 : grid.replicas() >= 6 ? 2 : 3;
        return Integer.getInteger("powsybl.bench.warmups", BenchMeters.explicitGrids() ? dflt : 1);
    }

    private static Properties updateParameters() {
        Properties p = BenchMeters.params();
        p.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");
        return p;
    }

    @Test
    void benchmark() {
        BenchMeters.logJvm(ScaleChangeBenchmarkTest.class);
        List<String> cTable = new ArrayList<>();
        cTable.add(String.format(Locale.ROOT, "C %-5s %-8s %6s %7s %9s %9s %9s %9s %9s %6s %6s",
                "grid", "set", "events", "rec", "partial", "diffStmt", "diffDoc", "fullSSH", "context", "d/p",
                "p/full"));
        List<String> dTable = new ArrayList<>();
        dTable.add(String.format(Locale.ROOT, "D %-5s %8s %9s %9s %9s %9s %7s %8s %7s %7s %7s",
                "grid", "read", "apRich", "apSwitch", "updRich", "upd/ap", "rcvMB", "applyPkMB", "evRich", "evSw",
                "route"));
        Map<String, Double> richPartial = new LinkedHashMap<>();
        Map<String, Double> richApply = new LinkedHashMap<>();
        for (BenchMeters.Grid grid : BenchMeters.grids("snb", "sv")) {
            measureGrid(grid, cTable, dTable, richPartial, richApply);
        }
        cTable.forEach(LOGGER::info);
        dTable.forEach(LOGGER::info);
        scaling("rich partial export", richPartial);
        scaling("rich difference apply", richApply);
        Double partial20 = richPartial.get("sv20");
        Double apply20 = richApply.get("sv20");
        if (partial20 != null) {
            LOGGER.info("{}: rich partial SSH export at sv20 {} ms, target {} ms",
                    partial20 < TARGET_MS ? "TARGET MET" : "TARGET MISSED", fmt(partial20), TARGET_MS);
        }
        if (apply20 != null) {
            LOGGER.info("{}: rich difference apply at sv20 {} ms, target {} ms",
                    apply20 < TARGET_MS ? "TARGET MET" : "TARGET MISSED", fmt(apply20), TARGET_MS);
        }
        softly.assertAll();
    }

    private void scaling(String what, Map<String, Double> values) {
        Double six = values.get("sv6");
        Double twenty = values.get("sv20");
        if (six != null && twenty != null) {
            LOGGER.info("{} sv20/sv6 = {}", what, fmt(twenty / six));
            softly.assertThat(twenty / six).as(what + " sv20/sv6").isLessThanOrEqualTo(5.0);
        }
    }

    private void measureGrid(BenchMeters.Grid grid, List<String> cTable, List<String> dTable,
                             Map<String, Double> richPartial, Map<String, Double> richApply) {
        ReadOnlyDataSource ds = grid.dataSource();
        Properties params = BenchMeters.params();
        Network sender = Network.read(ds, params);
        int warmups = warmupRuns(grid);
        int runs = measuredRuns(grid);

        double context = best(warmups, runs, () -> new CgmesExportContext(sender));
        double full = best(warmups, runs, () -> fullExport(sender));

        // rich: record, export, keep what D needs, then back to the base with the inverse recorded too
        Map<String, Double> targetP = new HashMap<>();
        Map<String, Double> targetV = new HashMap<>();
        Map<String, Double> p0 = new HashMap<>();
        sender.getGenerators().forEach(g -> {
            targetP.put(g.getId(), g.getTargetP());
            targetV.put(g.getId(), g.getTargetV());
        });
        sender.getLoads().forEach(l -> p0.put(l.getId(), l.getP0()));
        long[] rec = new long[1];
        List<NetworkEvent> rich = BenchMeters.timed(() -> record(sender, () -> {
            for (Generator g : sender.getGenerators()) {
                g.setTargetP(g.getTargetP() + 1.0);
                if (g.hasProperty(RC) && !Double.isNaN(g.getTargetV())) {
                    g.setTargetV(g.getTargetV() + 0.1);
                }
            }
            for (Load l : sender.getLoads()) {
                l.setP0(l.getP0() + 1.0);
            }
        }), rec);
        double[] richC = exports(sender, rich, warmups, runs);
        cTable.add(cRow(grid, "rich", rich.size(), rec[0], richC, full, context));
        DifferenceModelSet richSet = differenceOf(sender, rich);
        byte[] richPartialFile = partialFile(sender, rich);
        List<NetworkEvent> richUndo = record(sender, () -> {
            for (Generator g : sender.getGenerators()) {
                g.setTargetP(targetP.get(g.getId()));
                if (g.hasProperty(RC) && !Double.isNaN(targetV.get(g.getId()))) {
                    g.setTargetV(targetV.get(g.getId()));
                }
            }
            for (Load l : sender.getLoads()) {
                l.setP0(p0.get(l.getId()));
            }
        });
        byte[] richInverseFile = partialFile(sender, richUndo);

        // switches: 500 x N toggles
        List<Switch> switches = new ArrayList<>();
        sender.getSwitchStream().forEach(switches::add);
        int toggles = Math.min(switches.size(), 500 * Math.max(1, grid.replicas()));
        List<NetworkEvent> switchEvents = BenchMeters.timed(() -> record(sender, () -> {
            for (int i = 0; i < toggles; i++) {
                switches.get(i).setOpen(!switches.get(i).isOpen());
            }
        }), rec);
        double[] switchC = exports(sender, switchEvents, warmups, runs);
        cTable.add(cRow(grid, "switches", switchEvents.size(), rec[0], switchC, full, context));
        DifferenceModelSet switchSet = switchEvents.isEmpty() ? null : differenceOf(sender, switchEvents);
        record(sender, () -> {
            for (int i = 0; i < toggles; i++) {
                switches.get(i).setOpen(!switches.get(i).isOpen());
            }
        });

        // mixed, last: it is not undone
        List<NetworkEvent> mixed = BenchMeters.timed(() -> record(sender, () -> mixedEquipmentChanges(sender)
                .forEach(Runnable::run)), rec);
        double[] mixedC = exports(sender, mixed, warmups, runs);
        cTable.add(cRow(grid, "mixed", mixed.size(), rec[0], mixedC, full, context));
        richPartial.put(grid.key(), richC[0]);

        for (double[] c : List.of(richC, switchC, mixedC)) {
            softly.assertThat(c[0]).as("partial export against the full SSH export of " + grid).isLessThan(full);
            softly.assertThat(c[2]).as("diff document against the partial export of " + grid)
                    .isLessThanOrEqualTo(3 * c[0]);
        }

        // ------------------------------------------------------------ D
        long base = BenchMeters.heapAfterGc();
        Network receiver = Network.read(ds, params);
        long receiverHeap = BenchMeters.heapAfterGc() - base;
        Properties apply = updateParameters();
        apply.put(CgmesImport.DIFF_CHECK_SUPERSEDES, "false");
        CgmesDiffImport.Decision decision = CgmesDiffImport.canApplyInPlace(receiver, richSet);
        BenchMeters.resetPeak();
        double apRich = bestAlternating(warmups, runs, alternatingApply(receiver, richSet, apply));
        long applyPeak = BenchMeters.peakHeap();
        double apSwitch = switchSet == null ? Double.NaN : bestAlternating(warmups, runs,
                alternatingApply(receiver, switchSet, apply));
        double updRich = bestAlternating(warmups, runs, alternatingUpdate(receiver, richPartialFile,
                richInverseFile));
        long[] readMs = new long[1];
        BenchMeters.timed(() -> Network.read(ds, params), readMs);
        richApply.put(grid.key(), apRich);
        dTable.add(String.format(Locale.ROOT, "D %-5s %8d %9s %9s %9s %9s %7d %8d %7d %7d %7s", grid.key(), readMs[0],
                fmt(apRich), fmt(apSwitch), fmt(updRich), fmt(updRich / apRich), BenchMeters.mb(receiverHeap),
                BenchMeters.mb(applyPeak), rich.size(), switchEvents.size(), decision.route()));
        LOGGER.info("D {}: the rich difference is {} ({}); receiver {} MB after GC", grid, decision.route(),
                decision.reasons(), BenchMeters.mb(receiverHeap));
        softly.assertThat(apRich).as("rich apply of " + grid + " against Network.read").isLessThan(readMs[0]);
        softly.assertThat(apRich).as("rich apply of " + grid + " against the partial SSH update (" + fmt(updRich)
                + " ms)").isLessThanOrEqualTo(1.25 * updRich + 5);
    }

    private static String cRow(BenchMeters.Grid grid, String set, int events, long rec, double[] c, double full,
                               double context) {
        return String.format(Locale.ROOT, "C %-5s %-8s %6d %7d %9s %9s %9s %9s %9s %6s %6s", grid.key(), set, events,
                rec, fmt(c[0]), fmt(c[1]), fmt(c[2]), fmt(full), fmt(context), fmt(c[2] / c[0]), fmt(c[0] / full));
    }

    /** Partial SSH, difference statements, difference document: best milliseconds of each. */
    private static double[] exports(Network network, List<NetworkEvent> events, int warmups, int runs) {
        CgmesDiffExport.ExportOptions options = new CgmesDiffExport.ExportOptions()
                .setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE);
        double partial = best(warmups, runs, () -> PartialSshExport.toString(network, events,
                UnsupportedChangeBehavior.IGNORE));
        double statements = best(warmups, runs, () -> CgmesDiffExport.toDifferences(network, events, options)
                .differences());
        double document = best(warmups, runs, () -> CgmesDiffExport.toString(network, events,
                CgmesSubset.STEADY_STATE_HYPOTHESIS, UnsupportedChangeBehavior.IGNORE));
        return new double[] {partial, statements, document};
    }

    private static DifferenceModelSet differenceOf(Network sender, List<NetworkEvent> events) {
        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(sender, events,
                new CgmesDiffExport.ExportOptions().setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE));
        List<DifferenceModel> models = new ArrayList<>();
        result.differences().models().values()
                .forEach(model -> models.add(DifferenceModelParser.parse(DifferenceModelWriter.toString(model))));
        return new DifferenceModelSet(models);
    }

    private static byte[] partialFile(Network sender, List<NetworkEvent> events) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PartialSshExport.write(sender, events, bytes, new PartialSshExport.ExportOptions()
                .setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE));
        return bytes.toByteArray();
    }

    private static Supplier<Object> alternatingApply(Network receiver, DifferenceModelSet set, Properties p) {
        boolean[] forward = {true};
        return () -> {
            CgmesDiffImport.Decision decision = forward[0]
                    ? CgmesDiffImport.apply(receiver, set, p, ReportNode.NO_OP)
                    : CgmesDiffImport.revert(receiver, set, p, ReportNode.NO_OP);
            forward[0] = !forward[0];
            return decision;
        };
    }

    private static Supplier<Object> alternatingUpdate(Network receiver, byte[] change, byte[] inverse) {
        MemDataSource forwardSource = new MemDataSource();
        forwardSource.putData("partial_SSH.xml", change);
        MemDataSource inverseSource = new MemDataSource();
        inverseSource.putData("partial_SSH.xml", inverse);
        Properties p = updateParameters();
        boolean[] forward = {true};
        return () -> {
            receiver.update(forward[0] ? forwardSource : inverseSource, p);
            forward[0] = !forward[0];
            return receiver;
        };
    }

    private static List<Runnable> mixedEquipmentChanges(Network network) {
        List<Runnable> changes = new ArrayList<>();
        network.getLoadStream().forEach(l -> changes.add(() -> l.setP0(l.getP0() + 1.0)));
        network.getGeneratorStream().forEach(g -> {
            changes.add(() -> g.setTargetP(g.getTargetP() + 1.0));
            if (g.hasProperty(RC)) {
                changes.add(() -> g.setTargetV(g.getTargetV() + 1.0));
                changes.add(() -> g.setVoltageRegulatorOn(!g.isVoltageRegulatorOn()));
            }
        });
        network.getTwoWindingsTransformerStream()
                .filter(t -> t.hasRatioTapChanger()
                        && t.getRatioTapChanger().getTapPosition() < t.getRatioTapChanger().getHighTapPosition())
                .forEach(t -> changes.add(() -> t.getRatioTapChanger()
                        .setTapPosition(t.getRatioTapChanger().getTapPosition() + 1)));
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

    /** {@link #best}, then once more if needed so that an alternation ends where it started. */
    private static double bestAlternating(int warmups, int runs, Supplier<?> work) {
        double best = best(warmups, runs, work);
        if ((warmups + runs) % 2 == 1) {
            work.get();
        }
        return best;
    }

    /** Best wall clock of {@code runs} after {@code warmups}, in milliseconds. */
    private static double best(int warmups, int runs, Supplier<?> work) {
        for (int i = 0; i < warmups; i++) {
            work.get();
        }
        long bestNanos = Long.MAX_VALUE;
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            work.get();
            bestNanos = Math.min(bestNanos, System.nanoTime() - start);
        }
        return bestNanos / 1e6;
    }

    private static String fmt(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.2f", value);
    }
}
