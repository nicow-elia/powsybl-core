/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conformity.Cgmes3Catalog;
import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.DifferenceModelWriter;
import com.powsybl.commons.datasource.MemDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkEventRecorder;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the response time of applying a CGMES difference model in place.
 *
 * <p>The promise of the in-place route is that applying a change costs what the change costs, not what the network
 * costs: a difference that moves one switch of a 2000 switch model has to be far cheaper than reading that model
 * again. This measures the whole pipeline &mdash; parse, plan, build the synthetic update store, run the CGMES
 * update &mdash; and each phase separately, against reading the model and against the partial steady state
 * hypothesis update of the same change.</p>
 *
 * <p>The numbers below are logged at INFO, which the {@code logback-test.xml} of this module suppresses. To see them,
 * run with {@code -Dlogback.configurationFile=<a file raising this class to info>}.</p>
 *
 * <p>Indicative measurements, best of ten runs after warm up, on the svedala model (2342 switches), 8 cores.
 * One run is one apply, alternating between the difference and its inverse so that it always does real work:</p>
 * <pre>
 *   1 switch change     total   3.7 ms    parse 0.36   plan 0.07   store 1.44   update 3.25
 *   500 switch changes  total  11.6 ms    parse 1.90   plan 2.13   store 4.05   update 5.25
 *   127 mixed changes   total  18.4 ms
 *   partial SSH update of the same 500 changes   15.6 ms
 *   Network.read(svedala)                      1054 ms
 * </pre>
 *
 * <p>The equipment values of work package 5 behave the same way. The limits go through the update workflow like any
 * steady state value; the impedances are applied with IIDM setters, which is the {@code direct} phase and costs
 * almost nothing, because the fixed cost of an apply is the RDF and SPARQL machinery, not the setters:</p>
 * <pre>
 *   18 current limits of the micro grid   total  4.9 ms
 *   90 line impedances of svedala         total  3.6 ms   plan 0.32  store 0.72  update 2.43  direct 0.07
 *   140 mixed equipment and steady state  total  9.8 ms
 * </pre>
 *
 * <p>Applying a difference is thus hundreds of times cheaper than reading the model again, cheaper than the partial
 * steady state hypothesis file of the same change, and an order of magnitude below the 100 ms budget even for 500
 * changes at once.</p>
 *
 * <p>Where the time goes, from a JFR profile of this test ({@code JAVA_TOOL_OPTIONS=-XX:StartFlightRecording=
 * filename=<a directory>/,settings=profile,dumponexit=true}; the file name has to be a <em>directory</em>, because
 * the maven launcher inherits the option too): the samples inside an apply sit in rdf4j, first in the preparation
 * and evaluation of the update queries against the synthetic store ({@code SyntaxTreeBuilderTokenManager},
 * {@code LeftJoinIterator}, {@code StatementPatternQueryEvaluationStep}, {@code MemStatementIterator}) and then in
 * the RDF/XML parse of that store ({@code ParsedIRI}, the xerces scanner). That is a <em>fixed</em> cost, paid once
 * per apply whatever the change, which is why one change and five hundred are only a factor of three apart.</p>
 *
 * <p>Two things keep that fixed cost small, and both were measured here: a scoped update skips the passes over the
 * kinds of equipment the difference does not touch <em>before</em> they query anything, and {@code Context} builds
 * each of its ten update caches on first use instead of up front. Together they took a single change from 8.6 ms to
 * 3.7 ms. Walking the network is not in the numbers either, because the update is restricted to the equipment the
 * difference names ({@code UpdateScope}); that restriction is worth about 4 ms here (10.0 ms scoped against 14.1 ms
 * unscoped for one change, measured during review) and grows with the size of the model.
 * {@code UpdateScopeEquivalenceTest} is what proves it changes no result.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesDiffImportBenchmarkTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(CgmesDiffImportBenchmarkTest.class);

    /** If applying a difference takes longer than this, the in-place route has lost its reason to exist. */
    private static final long MAX_APPLY_TIME_MS = 100;
    /** How much slower than the partial steady state hypothesis update of the same change it may be. */
    private static final double PARTIAL_SSH_FACTOR = 1.25;
    private static final double PARTIAL_SSH_OFFSET_MS = 5;
    /** The phases before the CGMES update itself have to stay negligible. */
    private static final long MAX_PREPARATION_TIME_MS = 20;

    private static final int ONE_CHANGE = 1;
    private static final int MANY_CHANGES = 500;

    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 10;

    private static Properties parameters() {
        Properties parameters = new Properties();
        parameters.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");
        return parameters;
    }

    private static Network svedala() {
        return Network.read(Cgmes3Catalog.svedala().dataSource(), new Properties());
    }

    /** The difference model set of a change, as a receiver gets it: written out and read back. */
    private static DifferenceModelSet differenceOf(Network sender, List<NetworkEvent> events) {
        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(sender, events,
                new CgmesDiffExport.ExportOptions()
                        .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.IGNORE));
        List<DifferenceModel> models = new ArrayList<>();
        result.differences().models().values()
                .forEach(model -> models.add(DifferenceModelParser.parse(DifferenceModelWriter.toString(model))));
        return new DifferenceModelSet(models);
    }

    private static String documentOf(Network sender, List<NetworkEvent> events) {
        return DifferenceModelWriter.toString(CgmesDiffExport.toDifferences(sender, events,
                        new CgmesDiffExport.ExportOptions()
                                .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.IGNORE))
                .differences().get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow());
    }

    @Test
    void applyingADifferenceCostsWhatTheChangeCostsAndNotWhatTheNetworkCosts() {
        Network sender = svedala();
        Network receiver = svedala();
        int switchCount = receiver.getSwitchCount();

        List<NetworkEvent> oneChange = recordSwitchChanges(sender, ONE_CHANGE);
        DifferenceModelSet oneSet = differenceOf(sender, oneChange);
        String oneDocument = documentOf(sender, oneChange);
        List<NetworkEvent> manyChanges = recordSwitchChanges(sender, MANY_CHANGES);
        DifferenceModelSet manySet = differenceOf(sender, manyChanges);
        String manyDocument = documentOf(sender, manyChanges);

        // Every measurement gets its own receiver: the supersedes check makes a network remember which difference
        // it is at, so two different differences cannot be measured alternately on one network
        Phases onePhases = phases(receiver, oneSet, oneDocument);
        Phases manyPhases = phases(svedala(), manySet, manyDocument);

        double oneTotal = bestMillis(alternatingApply(svedala(), oneSet));
        double manyTotal = bestMillis(alternatingApply(svedala(), manySet));
        double partialSsh = bestMillis(partialSshUpdate(svedala(), sender, manyChanges));
        double read = bestMillis(() -> svedala());

        LOGGER.info("Difference model import of svedala ({} switches):", switchCount);
        LOGGER.info("  {} change   total {} ms  parse {}  plan {}  store {}  update {}  direct {}",
                ONE_CHANGE, millis(oneTotal), millis(onePhases.parse), millis(onePhases.plan),
                millis(onePhases.store), millis(onePhases.update), millis(onePhases.direct));
        LOGGER.info("  {} changes total {} ms  parse {}  plan {}  store {}  update {}  direct {}",
                MANY_CHANGES, millis(manyTotal), millis(manyPhases.parse), millis(manyPhases.plan),
                millis(manyPhases.store), millis(manyPhases.update), millis(manyPhases.direct));
        LOGGER.info("  partial SSH update of the same {} changes {} ms", MANY_CHANGES, millis(partialSsh));
        LOGGER.info("  Network.read(svedala) {} ms", millis(read));

        assertTrue(manyPhases.preparation() < MAX_PREPARATION_TIME_MS,
                () -> "parsing, planning and building the store of " + MANY_CHANGES + " changes took "
                        + millis(manyPhases.preparation()) + " ms, more than the " + MAX_PREPARATION_TIME_MS + " ms budget");
        assertTrue(manyTotal < read, () -> "applying " + MANY_CHANGES + " changes took " + millis(manyTotal)
                + " ms, no less than reading the whole model again (" + millis(read) + " ms)");
        assertTrue(manyTotal < PARTIAL_SSH_FACTOR * partialSsh + PARTIAL_SSH_OFFSET_MS,
                () -> "applying " + MANY_CHANGES + " changes as a difference took " + millis(manyTotal)
                        + " ms against " + millis(partialSsh) + " ms as a partial steady state hypothesis file");
        assertTrue(oneTotal < MAX_APPLY_TIME_MS, () -> "applying one change took " + millis(oneTotal)
                + " ms, more than the " + MAX_APPLY_TIME_MS + " ms budget");
        assertTrue(manyTotal < MAX_APPLY_TIME_MS, () -> "applying " + MANY_CHANGES + " changes took "
                + millis(manyTotal) + " ms, more than the " + MAX_APPLY_TIME_MS + " ms budget");
    }

    /**
     * The same measurement over a change set touching every kind of equipment the model holds, so that the cost of
     * the subject resolution and of the group completion is measured as well, not only that of switches.
     */
    @Test
    void mixedEquipmentChangesStayWithinBudget() {
        Network sender = svedala();
        Network receiver = svedala();
        List<NetworkEvent> events = recordMixedChanges(sender);
        assertTrue(events.size() > 50, () -> "the model is expected to give many changes, it gave " + events.size());
        DifferenceModelSet set = differenceOf(sender, events);

        double total = bestMillis(alternatingApply(receiver, set));
        LOGGER.info("Difference model import of svedala, {} mixed changes: {} ms", events.size(), millis(total));
        assertTrue(total < MAX_APPLY_TIME_MS, () -> "applying " + events.size() + " mixed changes took "
                + millis(total) + " ms, more than the " + MAX_APPLY_TIME_MS + " ms budget");
    }

    /**
     * Equipment values: the operational limits of a CGMES 2.4.15 model, which go through the update workflow, and
     * the line impedances, which are applied with IIDM setters afterwards. Both have to stay in the same budget as a
     * steady state difference; the new fixed costs are the index of the CGMES limit identifiers, built once per
     * apply, and the pass of the update over the operational limits groups, which every update already pays.
     */
    @Test
    void equipmentDifferencesStayWithinBudget() {
        Network limitSender = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        Network limitReceiver = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        List<NetworkEvent> limitEvents = record(limitSender, () -> currentLimitsOf(limitSender)
                .forEach(limits -> limits.setPermanentLimit(limits.getPermanentLimit() + 1.0)));
        assertTrue(!limitEvents.isEmpty(), "the micro grid is expected to hold current limits");
        DifferenceModelSet limitSet = differenceOf(limitSender, limitEvents);
        double limitTotal = bestMillis(alternatingApply(limitReceiver, limitSet, false));

        Network impedanceSender = svedala();
        Network impedanceReceiver = svedala();
        List<NetworkEvent> impedanceEvents = record(impedanceSender,
                () -> impedanceSender.getLineStream().forEach(line -> line.setR(line.getR() + 0.01)));
        DifferenceModelSet impedanceSet = differenceOf(impedanceSender, impedanceEvents);
        Phases impedancePhases = phases(svedala(), impedanceSet, null);
        double impedanceTotal = bestMillis(alternatingApply(impedanceReceiver, impedanceSet, false));

        LOGGER.info("Equipment difference import: {} limits of the micro grid {} ms,"
                        + " {} line impedances of svedala {} ms (plan {}, store {}, update {}, direct {})",
                limitEvents.size(), millis(limitTotal), impedanceEvents.size(), millis(impedanceTotal),
                millis(impedancePhases.plan), millis(impedancePhases.store), millis(impedancePhases.update),
                millis(impedancePhases.direct));

        assertTrue(limitTotal < MAX_APPLY_TIME_MS, () -> "applying " + limitEvents.size() + " limit changes took "
                + millis(limitTotal) + " ms, more than the " + MAX_APPLY_TIME_MS + " ms budget");
        assertTrue(impedanceTotal < MAX_APPLY_TIME_MS, () -> "applying " + impedanceEvents.size()
                + " impedance changes took " + millis(impedanceTotal) + " ms, more than the "
                + MAX_APPLY_TIME_MS + " ms budget");
    }

    /** A change set of both profiles: two documents, one update workflow and one direct setter pass. */
    @Test
    void mixedEquipmentAndSteadyStateDifferenceStaysWithinBudget() {
        Network sender = svedala();
        Network receiver = svedala();
        List<NetworkEvent> events = record(sender, () -> {
            sender.getLoadStream().limit(50).forEach(load -> load.setP0(load.getP0() + 1.0));
            sender.getLineStream().forEach(line -> line.setR(line.getR() + 0.01));
        });
        DifferenceModelSet set = differenceOf(sender, events);
        double total = bestMillis(alternatingApply(receiver, set, false));
        LOGGER.info("Mixed equipment and steady state difference import of svedala, {} changes: {} ms",
                events.size(), millis(total));
        assertTrue(total < MAX_APPLY_TIME_MS, () -> "applying " + events.size() + " mixed changes took "
                + millis(total) + " ms, more than the " + MAX_APPLY_TIME_MS + " ms budget");
    }

    private static List<com.powsybl.iidm.network.CurrentLimits> currentLimitsOf(Network network) {
        List<com.powsybl.iidm.network.CurrentLimits> limits = new ArrayList<>();
        network.getBranchStream().forEach(branch -> {
            branch.getCurrentLimits1().ifPresent(l -> limits.add((com.powsybl.iidm.network.CurrentLimits) l));
            branch.getCurrentLimits2().ifPresent(l -> limits.add((com.powsybl.iidm.network.CurrentLimits) l));
        });
        network.getThreeWindingsTransformerStream().forEach(transformer ->
                transformer.getLegs().forEach(leg -> leg.getCurrentLimits().ifPresent(limits::add)));
        network.getBoundaryLineStream().forEach(boundaryLine -> boundaryLine.getCurrentLimits().ifPresent(limits::add));
        return limits;
    }

    /** The phases of one apply, measured once each after a warm up. */
    private record Phases(double parse, double plan, double store, double update, double direct) {
        double preparation() {
            return parse + plan + store;
        }
    }

    private static Phases phases(Network receiver, DifferenceModelSet set, String document) {
        Conversion.Config config = new CgmesImport().config(parameters());
        CgmesDiffImport.Options options = new CgmesDiffImport.Options().setCheckSupersedes(document != null);
        for (int run = 0; run < WARMUP_RUNS; run++) {
            parse(document);
            CgmesDiffImport.applyInternal(receiver, set, config, options, ReportNode.NO_OP, false);
            CgmesDiffImport.applyInternal(receiver, set, config, options, ReportNode.NO_OP, true);
        }
        long bestParse = Long.MAX_VALUE;
        CgmesDiffImport.PhaseTimes best = null;
        for (int run = 0; run < MEASURED_RUNS; run++) {
            long start = System.nanoTime();
            parse(document);
            bestParse = Math.min(bestParse, System.nanoTime() - start);
            CgmesDiffImport.PhaseTimes times =
                    CgmesDiffImport.applyInternal(receiver, set, config, options, ReportNode.NO_OP, false).times();
            CgmesDiffImport.applyInternal(receiver, set, config, options, ReportNode.NO_OP, true);
            if (best == null || total(times) < total(best)) {
                best = times;
            }
        }
        return new Phases(bestParse / 1e6, best.planNs() / 1e6, best.storeNs() / 1e6, best.updateNs() / 1e6,
                best.directNs() / 1e6);
    }

    /** The parse phase, which a measurement of the apply phases alone leaves out. */
    private static void parse(String document) {
        if (document != null) {
            DifferenceModelParser.parse(document);
        }
    }

    private static long total(CgmesDiffImport.PhaseTimes times) {
        return times.planNs() + times.storeNs() + times.updateNs() + times.directNs();
    }

    /**
     * One apply, alternating between the difference and its inverse so that every run does real work: a receiver
     * that is already in the state the difference describes would make the update a no-op and the measurement a
     * lie. One run is therefore one apply, directly comparable with one partial steady state hypothesis update.
     */
    private static Supplier<Object> alternatingApply(Network receiver, DifferenceModelSet set) {
        return alternatingApply(receiver, set, true);
    }

    private static Supplier<Object> alternatingApply(Network receiver, DifferenceModelSet set,
                                                     boolean checkSupersedes) {
        Properties parameters = parameters();
        if (!checkSupersedes) {
            parameters.put(CgmesImport.DIFF_CHECK_SUPERSEDES, "false");
        }
        boolean[] forward = {true};
        return () -> {
            CgmesDiffImport.Decision decision = forward[0]
                    ? CgmesDiffImport.apply(receiver, set, parameters, ReportNode.NO_OP)
                    : CgmesDiffImport.revert(receiver, set, parameters, ReportNode.NO_OP);
            forward[0] = !forward[0];
            return decision;
        };
    }

    /** The same change as a partial steady state hypothesis file, applied through {@code network.update}. */
    private static Supplier<Object> partialSshUpdate(Network receiver, Network sender, List<NetworkEvent> events) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PartialSshExport.write(sender, events, bytes,
                new PartialSshExport.ExportOptions()
                        .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.IGNORE));
        MemDataSource dataSource = new MemDataSource();
        dataSource.putData("partial_SSH.xml", bytes.toByteArray());
        Properties parameters = parameters();
        return () -> {
            receiver.update(dataSource, parameters);
            return receiver;
        };
    }

    private static double bestMillis(Supplier<?> work) {
        for (int run = 0; run < WARMUP_RUNS; run++) {
            work.get();
        }
        long bestNanos = Long.MAX_VALUE;
        for (int run = 0; run < MEASURED_RUNS; run++) {
            long start = System.nanoTime();
            work.get();
            bestNanos = Math.min(bestNanos, System.nanoTime() - start);
        }
        return bestNanos / 1e6;
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

    private static List<NetworkEvent> recordMixedChanges(Network network) {
        return record(network, () -> {
            network.getLoadStream().limit(50).forEach(load -> load.setP0(load.getP0() + 1.0));
            network.getGeneratorStream().limit(50).forEach(g -> g.setTargetP(g.getTargetP() + 1.0));
            network.getTwoWindingsTransformerStream()
                    .filter(t -> t.hasRatioTapChanger()
                            && t.getRatioTapChanger().getTapPosition() < t.getRatioTapChanger().getHighTapPosition())
                    .limit(50)
                    .forEach(t -> t.getRatioTapChanger().setTapPosition(t.getRatioTapChanger().getTapPosition() + 1));
            network.getShuntCompensatorStream()
                    .filter(s -> s.getSectionCount() < s.getMaximumSectionCount())
                    .limit(50)
                    .forEach(s -> s.setSectionCount(s.getSectionCount() + 1));
        });
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

    private static String millis(double millis) {
        return String.format("%.2f", millis);
    }

    @Test
    void theDocumentOfADifferenceIsReadBackIdentically() {
        // A guard for the measurement above: what is applied is really what was exported
        Network sender = svedala();
        List<NetworkEvent> events = recordSwitchChanges(sender, ONE_CHANGE);
        String document = documentOf(sender, events);
        assertEquals(DifferenceModelParser.parse(document),
                DifferenceModelParser.parse(new java.io.ByteArrayInputStream(
                        document.getBytes(StandardCharsets.UTF_8)), null));
    }
}
