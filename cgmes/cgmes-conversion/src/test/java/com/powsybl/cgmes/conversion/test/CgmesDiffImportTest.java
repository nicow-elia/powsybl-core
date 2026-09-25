/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.diff.CgmesDiffImport;
import com.powsybl.cgmes.conversion.diff.CgmesDiffNotApplicableException;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.extensions.CgmesMetadataModels;
import com.powsybl.cgmes.model.CgmesMetadataModel;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.DifferenceModelWriter;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.serde.NetworkSerDe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public difference model import API: what it decides, what it registers and what it refuses.
 *
 * <p>The round trip of every recorded change is {@link CgmesDiffRoundTripTest}; this class covers the decisions
 * around it &mdash; the metadata of the network after an apply, the supersedes check, the reverse check, the
 * rejections a live network makes possible, and the overloads of the entry point.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesDiffImportTest {

    private static final String LOAD_DIR = "/update/load/";
    private static final String[] LOAD_FILES = {"load_EQ.xml", "load_SSH.xml"};
    private static final String LOAD = "EnergyConsumer";

    private static Properties previousValues() {
        Properties parameters = new Properties();
        parameters.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");
        return parameters;
    }

    private static Network load() {
        return readCgmesResources(LOAD_DIR, LOAD_FILES);
    }

    /** The difference model set of a change, written out and read back, as a receiver would get it. */
    private static DifferenceModelSet differenceOf(Network sender, Consumer<Network> change,
                                                   CgmesDiffExport.DiffGranularity granularity,
                                                   Consumer<CgmesDiffExport.ExportOptions> tune) {
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender, change);
        CgmesDiffExport.ExportOptions options = new CgmesDiffExport.ExportOptions()
                .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL)
                .setGranularity(granularity);
        tune.accept(options);
        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(sender, events, options);
        List<DifferenceModel> models = new ArrayList<>();
        result.differences().models().values()
                .forEach(model -> models.add(DifferenceModelParser.parse(DifferenceModelWriter.toString(model))));
        return new DifferenceModelSet(models);
    }

    private static DifferenceModelSet differenceOf(Network sender, Consumer<Network> change) {
        return differenceOf(sender, change, CgmesDiffExport.DiffGranularity.FULL_OBJECT, options -> { });
    }

    private static Optional<CgmesMetadataModel> sshModel(Network network) {
        CgmesMetadataModels models = network.getExtension(CgmesMetadataModels.class);
        return models == null ? Optional.empty() : models.getModelForSubset(CgmesSubset.STEADY_STATE_HYPOTHESIS);
    }

    // The fast route on a minimal difference

    @Test
    void changedOnlyDiffIsCompletedFromTheReceiver() {
        Network sender = load();
        Network receiver = load();
        double q = receiver.getLoad(LOAD).getQ0();
        DifferenceModelSet set = differenceOf(sender, n -> n.getLoad(LOAD).setP0(12.5),
                CgmesDiffExport.DiffGranularity.CHANGED_ONLY, options -> { });
        // The document really is minimal
        DifferenceModel model = set.get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
        assertEquals(1, model.forward().size());

        CgmesDiffImport.apply(receiver, set, previousValues(), ReportNode.NO_OP);
        assertEquals(12.5, receiver.getLoad(LOAD).getP0(), 1e-9);
        assertEquals(q, receiver.getLoad(LOAD).getQ0(), 1e-9,
                "the reactive power the difference does not mention has to survive the update");
    }

    // Metadata

    @Test
    void metadataAfterApplyMirrorsAnSshUpdate() {
        Network sender = load();
        Network receiver = load();
        String before = sshModel(receiver).orElseThrow().getId();
        DifferenceModelSet set = differenceOf(sender, n -> n.getLoad(LOAD).setP0(12.5));
        DifferenceModelHeader header = set.get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow().header();

        CgmesDiffImport.apply(receiver, set, previousValues(), ReportNode.NO_OP);

        CgmesMetadataModel model = sshModel(receiver).orElseThrow();
        assertEquals(header.id(), model.getId());
        assertEquals(header.version(), model.getVersion());
        assertEquals(header.modelingAuthoritySet(), model.getModelingAuthoritySet());
        assertTrue(model.getSupersedes().contains(before), model.getSupersedes().toString());
        assertEquals(header.scenarioTime(), receiver.getCaseDate());
        // The models of the other profiles are kept
        assertTrue(receiver.getExtension(CgmesMetadataModels.class)
                .getModelForSubset(CgmesSubset.EQUIPMENT).isPresent());
    }

    @Test
    void metadataAfterRevertPointsToThePredecessor() {
        Network sender = load();
        Network receiver = load();
        String before = sshModel(receiver).orElseThrow().getId();
        DifferenceModelSet set = differenceOf(sender, n -> n.getLoad(LOAD).setP0(12.5));

        CgmesDiffImport.apply(receiver, set, previousValues(), ReportNode.NO_OP);
        assertNotEquals(before, sshModel(receiver).orElseThrow().getId());
        CgmesDiffImport.revert(receiver, set, previousValues(), ReportNode.NO_OP);
        assertEquals(before, sshModel(receiver).orElseThrow().getId(),
                "after undoing a difference the network is at the model the difference was applied on");
        assertTrue(sshModel(receiver).orElseThrow().getSupersedes().isEmpty());
    }

    @Test
    void chainOfTwoDifferencesAppliesAndRevertsInOrder() {
        Network sender = load();
        Network receiver = load();
        DifferenceModelSet first = differenceOf(sender, n -> n.getLoad(LOAD).setP0(12.5));
        DifferenceModelHeader firstHeader = first.get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow().header();
        DifferenceModelSet second = differenceOf(sender, n -> n.getLoad(LOAD).setP0(20.0),
                CgmesDiffExport.DiffGranularity.FULL_OBJECT,
            options -> options.header(CgmesSubset.STEADY_STATE_HYPOTHESIS).chainAfter(firstHeader));

        CgmesDiffImport.apply(receiver, first, previousValues(), ReportNode.NO_OP);
        assertEquals(12.5, receiver.getLoad(LOAD).getP0(), 1e-9);
        CgmesDiffImport.apply(receiver, second, previousValues(), ReportNode.NO_OP);
        assertEquals(20.0, receiver.getLoad(LOAD).getP0(), 1e-9);

        CgmesDiffImport.revert(receiver, second, previousValues(), ReportNode.NO_OP);
        assertEquals(12.5, receiver.getLoad(LOAD).getP0(), 1e-9);
        CgmesDiffImport.revert(receiver, first, previousValues(), ReportNode.NO_OP);
        assertEquals(10.0, receiver.getLoad(LOAD).getP0(), 1e-9);
    }

    /**
     * Undoing a difference the network is no longer at is the same silent corruption as applying one on the wrong
     * base: the metadata would name a predecessor while the content is a later model minus this one's reverse.
     */
    @Test
    void revertOutOfOrderIsRejected() {
        Network sender = load();
        Network receiver = load();
        DifferenceModelSet first = differenceOf(sender, n -> n.getLoad(LOAD).setP0(12.5));
        DifferenceModelHeader firstHeader = first.get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow().header();
        DifferenceModelSet second = differenceOf(sender, n -> n.getLoad(LOAD).setP0(20.0),
                CgmesDiffExport.DiffGranularity.FULL_OBJECT,
            options -> options.header(CgmesSubset.STEADY_STATE_HYPOTHESIS).chainAfter(firstHeader));

        CgmesDiffImport.apply(receiver, first, previousValues(), ReportNode.NO_OP);
        CgmesDiffImport.apply(receiver, second, previousValues(), ReportNode.NO_OP);

        // The network is at the second difference, so only the second one may be undone
        CgmesDiffNotApplicableException e = assertThrows(CgmesDiffNotApplicableException.class,
            () -> CgmesDiffImport.revert(receiver, first, previousValues(), ReportNode.NO_OP));
        assertTrue(e.getMessage().contains("cannot be reverted"), e.getMessage());
        assertEquals(20.0, receiver.getLoad(LOAD).getP0(), 1e-9, "nothing may be modified when a check fails");

        // In order it works, and then the first one can be undone too
        CgmesDiffImport.revert(receiver, second, previousValues(), ReportNode.NO_OP);
        CgmesDiffImport.revert(receiver, first, previousValues(), ReportNode.NO_OP);
        assertEquals(10.0, receiver.getLoad(LOAD).getP0(), 1e-9);
    }

    @Test
    void revertOutOfOrderIsAcceptedWhenTheCheckIsDisabled() {
        Network sender = load();
        Network receiver = load();
        DifferenceModelSet first = differenceOf(sender, n -> n.getLoad(LOAD).setP0(12.5));
        DifferenceModelHeader firstHeader = first.get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow().header();
        DifferenceModelSet second = differenceOf(sender, n -> n.getLoad(LOAD).setP0(20.0),
                CgmesDiffExport.DiffGranularity.FULL_OBJECT,
            options -> options.header(CgmesSubset.STEADY_STATE_HYPOTHESIS).chainAfter(firstHeader));
        Properties parameters = previousValues();
        parameters.put(CgmesImport.DIFF_CHECK_SUPERSEDES, "false");

        CgmesDiffImport.apply(receiver, first, parameters, ReportNode.NO_OP);
        CgmesDiffImport.apply(receiver, second, parameters, ReportNode.NO_OP);
        assertEquals(CgmesDiffImport.Route.FAST,
                CgmesDiffImport.revert(receiver, first, parameters, ReportNode.NO_OP).route());
        assertEquals(10.0, receiver.getLoad(LOAD).getP0(), 1e-9);
    }

    /**
     * A difference of a foreign producer that declares no {@code Supersedes} is applied anyway: refusing every such
     * difference would make the check useless rather than safe.
     */
    @Test
    void aDifferenceWithoutSupersedesIsNotChecked() {
        Network sender = load();
        Network receiver = load();
        DifferenceModelSet set = differenceOf(sender, n -> n.getLoad(LOAD).setP0(12.5),
                CgmesDiffExport.DiffGranularity.FULL_OBJECT,
            options -> options.header(CgmesSubset.STEADY_STATE_HYPOTHESIS).setSupersedePreviousModel(false));
        assertEquals(List.of(), set.get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow().header().supersedes());
        assertEquals(CgmesDiffImport.Route.FAST,
                CgmesDiffImport.apply(receiver, set, previousValues(), ReportNode.NO_OP).route());
    }

    @Test
    void differenceOnTheWrongBaseIsRejectedBySupersedes() {
        Network sender = load();
        Network receiver = load();
        DifferenceModelSet set = differenceOf(sender, n -> n.getLoad(LOAD).setP0(12.5),
                CgmesDiffExport.DiffGranularity.FULL_OBJECT,
            options -> options.header(CgmesSubset.STEADY_STATE_HYPOTHESIS)
                        .setSupersedePreviousModel(false).addSupersedes("urn:uuid:some-other-model"));

        CgmesDiffImport.Decision decision = CgmesDiffImport.canApplyInPlace(receiver, set);
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route());
        assertTrue(decision.reasons().get(0).contains("but the network is at"), decision.reasons().toString());
        assertThrows(CgmesDiffNotApplicableException.class,
            () -> CgmesDiffImport.apply(receiver, set, previousValues(), ReportNode.NO_OP));
        assertEquals(10.0, receiver.getLoad(LOAD).getP0(), 1e-9, "nothing may be modified when a check fails");
    }

    @Test
    void differenceOnTheWrongBaseIsAcceptedWhenTheCheckIsDisabled() {
        Network sender = load();
        Network receiver = load();
        DifferenceModelSet set = differenceOf(sender, n -> n.getLoad(LOAD).setP0(12.5),
                CgmesDiffExport.DiffGranularity.FULL_OBJECT,
            options -> options.header(CgmesSubset.STEADY_STATE_HYPOTHESIS)
                        .setSupersedePreviousModel(false).addSupersedes("urn:uuid:some-other-model"));
        Properties parameters = previousValues();
        parameters.put(CgmesImport.DIFF_CHECK_SUPERSEDES, "false");

        assertEquals(CgmesDiffImport.Route.FAST,
                CgmesDiffImport.apply(receiver, set, parameters, ReportNode.NO_OP).route());
        assertEquals(12.5, receiver.getLoad(LOAD).getP0(), 1e-9);
    }

    // The reverse check

    @Test
    void reverseCheckIsOffByDefaultAndFailsOnADriftedReceiver() {
        Network sender = load();
        Network receiver = load();
        DifferenceModelSet set = differenceOf(sender, n -> n.getLoad(LOAD).setP0(12.5));
        receiver.getLoad(LOAD).setP0(99.0);

        assertEquals(CgmesDiffImport.Route.FAST, CgmesDiffImport.canApplyInPlace(receiver, set).route());

        CgmesDiffImport.Options fail = new CgmesDiffImport.Options()
                .setReverseCheck(CgmesDiffImport.ReverseCheck.FAIL);
        CgmesDiffImport.Decision decision = CgmesDiffImport.canApplyInPlace(receiver, set, fail);
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route());
        assertTrue(decision.reasons().stream().anyMatch(reason -> reason.contains("differs from the expected value")),
                decision.reasons().toString());

        CgmesDiffImport.Options warn = new CgmesDiffImport.Options()
                .setReverseCheck(CgmesDiffImport.ReverseCheck.WARN);
        assertEquals(CgmesDiffImport.Route.FAST, CgmesDiffImport.canApplyInPlace(receiver, set, warn).route());
    }

    @Test
    void reverseCheckAcceptsAnotherSpellingOfTheSameNumber() {
        Network sender = load();
        Network receiver = load();
        DifferenceModelSet set = differenceOf(sender, n -> n.getLoad(LOAD).setP0(12.5));
        // The reverse statement says 10, the network says 10.0
        assertEquals(CgmesDiffImport.Route.FAST, CgmesDiffImport.canApplyInPlace(receiver, set,
                new CgmesDiffImport.Options().setReverseCheck(CgmesDiffImport.ReverseCheck.FAIL)).route());
    }

    // Empty and blocked differences leave the network alone

    @Test
    void emptyDifferenceIsANoop() {
        Network receiver = load();
        String before = xiidm(receiver);
        assertEquals(CgmesDiffImport.Route.NOOP,
                CgmesDiffImport.apply(receiver, new DifferenceModelSet(List.of()), previousValues(),
                        ReportNode.NO_OP).route());
        assertEquals(before, xiidm(receiver));
    }

    @Test
    void nothingIsModifiedWhenOneStatementBlocks() {
        Network sender = load();
        Network receiver = load();
        String before = xiidm(receiver);
        DifferenceModel good = differenceOf(sender, n -> n.getLoad(LOAD).setP0(12.5))
                .get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
        List<CgmesStatement> forward = new ArrayList<>(good.forward());
        forward.add(CgmesStatement.literal("does-not-exist", null, "EnergyConsumer.p", "1"));
        forward.add(CgmesStatement.literal("does-not-exist", null, "EnergyConsumer.q", "1"));
        DifferenceModel broken = new DifferenceModel(good.header(), forward, good.reverse(), List.of());

        CgmesDiffNotApplicableException e = assertThrows(CgmesDiffNotApplicableException.class,
            () -> CgmesDiffImport.apply(receiver, broken, previousValues(), ReportNode.NO_OP));
        assertTrue(e.getMessage().contains("does-not-exist"), e.getMessage());
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, e.getDecision().route());
        assertEquals(before, xiidm(receiver));
    }

    // The overloads all mean the same thing

    @Test
    void applyFromStringStreamAndPathAgree(@TempDir Path tempDir) throws IOException {
        Network sender = load();
        DifferenceModel model = differenceOf(sender, n -> n.getLoad(LOAD).setP0(12.5))
                .get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
        String xml = DifferenceModelWriter.toString(model);
        Path file = tempDir.resolve("a_SSH_DIFF.xml");
        Files.writeString(file, xml, StandardCharsets.UTF_8);

        Network fromString = load();
        Network fromStream = load();
        Network fromPath = load();
        Network fromModel = load();
        CgmesDiffImport.apply(fromString, xml, previousValues(), ReportNode.NO_OP);
        CgmesDiffImport.apply(fromStream, new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                previousValues(), ReportNode.NO_OP);
        CgmesDiffImport.apply(fromPath, file, previousValues(), ReportNode.NO_OP);
        CgmesDiffImport.apply(fromModel, model, previousValues(), ReportNode.NO_OP);

        for (Network network : List.of(fromString, fromStream, fromPath, fromModel)) {
            assertEquals(12.5, network.getLoad(LOAD).getP0(), 1e-9);
        }
    }

    @Test
    void inProcessSetWithClassHintsAndParsedSetGiveTheSameResult() {
        Network sender = load();
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender, n -> n.getLoad(LOAD).setP0(12.5));
        DifferenceModelSet inProcess = CgmesDiffExport.toDifferences(sender, events,
                new CgmesDiffExport.ExportOptions()
                        .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL)).differences();
        DifferenceModelSet parsed = new DifferenceModelSet(List.of(DifferenceModelParser.parse(
                DifferenceModelWriter.toString(inProcess.get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow()))));

        Network a = load();
        Network b = load();
        CgmesDiffImport.apply(a, inProcess, previousValues(), ReportNode.NO_OP);
        CgmesDiffImport.apply(b, parsed, previousValues(), ReportNode.NO_OP);
        assertEquals(xiidm(a), xiidm(b));
    }

    // Network aware rejections

    private static DifferenceModelSet handMade(List<CgmesStatement> forward, List<CgmesStatement> reverse) {
        return handMade(CgmesSubset.STEADY_STATE_HYPOTHESIS, CgmesNamespace.CIM_100_NAMESPACE, forward, reverse);
    }

    private static DifferenceModelSet handMade(CgmesSubset subset, String cimNamespace,
                                               List<CgmesStatement> forward, List<CgmesStatement> reverse) {
        return new DifferenceModelSet(List.of(new DifferenceModel(
                DifferenceModelHeader.builder("urn:uuid:hand-made", subset, cimNamespace).build(),
                forward, reverse, List.of())));
    }

    private static List<String> reasons(Network network, DifferenceModelSet set) {
        CgmesDiffImport.Decision decision = CgmesDiffImport.canApplyInPlace(network, set);
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route(), decision.reasons().toString());
        return decision.reasons();
    }

    @Test
    void unknownSubjectIsRejected() {
        Network network = load();
        assertTrue(reasons(network, handMade(
                List.of(CgmesStatement.literal("nope", null, "EnergyConsumer.p", "1"),
                        CgmesStatement.literal("nope", null, "EnergyConsumer.q", "1")), List.of()))
                .get(0).contains("no object of this network has this identifier"));
    }

    @Test
    void objectCreationAndRemovalAreRejected() {
        Network network = load();
        assertTrue(reasons(network, handMade(
                List.of(CgmesStatement.reference("brand-new", null, CgmesStatement.RDF_TYPE, "ConformLoad")),
                List.of())).get(0).contains("object creation"));
        assertTrue(reasons(network, handMade(List.of(),
                List.of(CgmesStatement.reference(LOAD, null, CgmesStatement.RDF_TYPE, "ConformLoad"))))
                .get(0).contains("object removal"));
    }

    @Test
    void typedNodesForAnExistingObjectAreTolerated() {
        Network network = load();
        assertEquals(CgmesDiffImport.Route.FAST, CgmesDiffImport.canApplyInPlace(network, handMade(
                List.of(CgmesStatement.reference(LOAD, null, CgmesStatement.RDF_TYPE, "ConformLoad"),
                        CgmesStatement.literal(LOAD, null, "EnergyConsumer.p", "1"),
                        CgmesStatement.literal(LOAD, null, "EnergyConsumer.q", "1")),
                List.of())).route());
    }

    @Test
    void propertyOfAnotherFamilyIsRejected() {
        Network network = load();
        assertTrue(reasons(network, handMade(
                List.of(CgmesStatement.literal(LOAD, null, "Switch.open", "true")), List.of()))
                .get(0).contains("Switch.open is not updatable on a ENERGY_CONSUMER"));
    }

    @Test
    void branchModelledAsSwitchNeedsItsTerminals() {
        Network network = readCgmesResources("/update/switch/", "switch_EQ.xml", "switch_SSH.xml");
        assertTrue(reasons(network, handMade(
                List.of(CgmesStatement.literal("SeriesCompensator", null, "Switch.open", "true")), List.of()))
                .get(0).contains("carried by its terminals"));
    }

    @Test
    void stateVariableProfileNeedsTheSlowRoute() {
        Network network = load();
        assertTrue(reasons(network, handMade(CgmesSubset.STATE_VARIABLES, CgmesNamespace.CIM_100_NAMESPACE,
                List.of(CgmesStatement.literal("TN", null, "SvVoltage.v", "400")), List.of()))
                .get(0).contains("SV profile cannot be updated in place"));
    }

    /**
     * The equipment profile is accepted for the values work package 5 added, and only for them: the subject still
     * has to exist in the receiving network.
     */
    @Test
    void anEquipmentProfileWithAnUnknownSubjectStillNeedsTheSlowRoute() {
        Network network = load();
        assertTrue(reasons(network, handMade(CgmesSubset.EQUIPMENT, CgmesNamespace.CIM_100_NAMESPACE,
                List.of(CgmesStatement.literal("NoSuchLine", null, "ACLineSegment.r", "2")), List.of()))
                .get(0).contains("no object of this network has this identifier"));
    }

    @Test
    void mixedCimNamespacesAreRejected() {
        Network network = load();
        List<DifferenceModel> models = List.of(
                new DifferenceModel(DifferenceModelHeader.builder("urn:uuid:a",
                        CgmesSubset.STEADY_STATE_HYPOTHESIS, CgmesNamespace.CIM_100_NAMESPACE).build(),
                        List.of(CgmesStatement.literal(LOAD, null, "EnergyConsumer.p", "1"),
                                CgmesStatement.literal(LOAD, null, "EnergyConsumer.q", "1")), List.of(), List.of()),
                new DifferenceModel(DifferenceModelHeader.builder("urn:uuid:b",
                        CgmesSubset.TOPOLOGY, CgmesNamespace.CIM_16_NAMESPACE).build(),
                        List.of(), List.of(), List.of()));
        List<String> reasons = CgmesDiffImport.canApplyInPlace(network, new DifferenceModelSet(models)).reasons();
        assertTrue(reasons.stream().anyMatch(reason -> reason.contains("cannot be updated in place")
                || reason.contains("mix the CIM namespaces")), reasons.toString());
    }

    @Test
    void incompleteGroupThatCannotBeCompletedIsRejected() {
        Network network = readCgmesResources("/update/control-area/",
                "controlArea_EQ.xml", "controlArea_EQ_BD.xml", "controlArea_SSH.xml");
        String areaId = network.getAreaStream().findFirst().orElseThrow().getId();
        // pTolerance alone: the required netInterchange of the same group is missing, and no mapping produces it
        List<String> reasons = reasons(network, handMade(
                List.of(CgmesStatement.literal(areaId, null, "ControlArea.pTolerance", "5")), List.of()));
        assertTrue(reasons.get(0).contains("ControlArea.netInterchange"), reasons.toString());
    }

    // Section 6 of the plan: an update must not reconnect a terminal it says nothing about

    @Test
    void untouchedDisconnectedTerminalStaysDisconnectedInBusBreaker() {
        Network sender = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        Network receiver = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        List<String> loads = new ArrayList<>();
        receiver.getLoads().forEach(load -> loads.add(load.getId()));
        assertTrue(loads.size() >= 2);
        String disconnected = loads.get(0);
        String changed = loads.get(1);
        receiver.getLoad(disconnected).getTerminal().disconnect();
        assertFalse(receiver.getLoad(disconnected).getTerminal().isConnected());

        DifferenceModelSet set = differenceOf(sender, n -> n.getLoad(changed).setP0(42.0));
        CgmesDiffImport.apply(receiver, set, previousValues(), ReportNode.NO_OP);

        assertEquals(42.0, receiver.getLoad(changed).getP0(), 1e-9);
        assertFalse(receiver.getLoad(disconnected).getTerminal().isConnected(),
                "an update that says nothing about a terminal must leave it where it is");

        // The same through the path users hit directly: a partial steady state hypothesis file of the same change
        Network sshReceiver = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        sshReceiver.getLoad(disconnected).getTerminal().disconnect();
        Network partialSender = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        List<NetworkEvent> events = RecordedChangeScenarios.record(partialSender,
            n -> n.getLoad(changed).setP0(42.0));
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        PartialSshExport.write(partialSender, events, bytes, new PartialSshExport.ExportOptions()
                .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL));
        com.powsybl.commons.datasource.MemDataSource dataSource =
                new com.powsybl.commons.datasource.MemDataSource();
        dataSource.putData("partial_SSH.xml", bytes.toByteArray());
        sshReceiver.update(dataSource, previousValues());

        assertEquals(42.0, sshReceiver.getLoad(changed).getP0(), 1e-9);
        assertFalse(sshReceiver.getLoad(disconnected).getTerminal().isConnected(),
                "the partial SSH path has to leave an unmentioned terminal where it is too");
    }

    @Test
    void wholeConformityModel() {
        Network sender = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        Network receiver = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        String load = sender.getLoadStream().map(Identifiable::getId).findFirst().orElseThrow();
        String generator = sender.getGeneratorStream().map(Identifiable::getId).findFirst().orElseThrow();
        // The conformity model is bus/breaker, so a switch of it is not a CGMES switch of its own
        String shunt = sender.getShuntCompensatorStream().map(Identifiable::getId).findFirst().orElseThrow();
        String transformer = sender.getTwoWindingsTransformerStream()
                .filter(t -> t.getOptionalRatioTapChanger().isPresent())
                .map(Identifiable::getId).findFirst().orElseThrow();

        DifferenceModelSet set = differenceOf(sender, n -> {
            n.getLoad(load).setP0(42.0);
            n.getGenerator(generator).setTargetP(11.0);
            n.getShuntCompensator(shunt).setSectionCount(0);
            n.getTwoWindingsTransformer(transformer).getRatioTapChanger().setTapPosition(
                    n.getTwoWindingsTransformer(transformer).getRatioTapChanger().getLowTapPosition());
        });

        assertEquals(CgmesDiffImport.Route.FAST,
                CgmesDiffImport.apply(receiver, set, previousValues(), ReportNode.NO_OP).route());
        assertEquals(SteadyStateFingerprint.of(sender), SteadyStateFingerprint.of(receiver));
    }

    /** The XIIDM of a network, which is the finest grained "nothing changed" assertion available. */
    private static String xiidm(Network network) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        NetworkSerDe.write(network, bytes);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /**
     * A document in the layout OpenCGMES writes &mdash; no model description beyond the {@code Supersedes}, a
     * preconditions container, forward before reverse and typed node elements instead of {@code rdf:Description}
     * &mdash; has to apply exactly like the one this library writes.
     *
     * <p>This is the dependency free half of the OpenCGMES acceptance: it pins the shapes of the standard the two
     * implementations have to agree on, without putting another RDF engine on the test classpath.</p>
     */
    @Test
    void openCgmesStyleDocumentIsApplied() {
        Network canonical = load();
        Network openCgmesStyle = load();
        String supersedes = sshModel(canonical).orElseThrow().getId();

        Network sender = load();
        DifferenceModelSet set = differenceOf(sender, n -> n.getLoad(LOAD).setP0(12.5));
        CgmesDiffImport.apply(canonical, set, previousValues(), ReportNode.NO_OP);

        String document = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"                 xmlns:cim="http://iec.ch/TC57/CIM100#"                 xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"                 xmlns:dm="http://iec.ch/TC57/61970-552/DifferenceModel/1#">
                  <dm:DifferenceModel rdf:about="urn:uuid:open-cgmes-style">
                    <md:Model.Supersedes rdf:resource="%s"/>
                    <dm:preconditions rdf:parseType="Statements">
                      <rdf:Description rdf:about="#_EnergyConsumer">
                        <cim:EnergyConsumer.p>10</cim:EnergyConsumer.p>
                      </rdf:Description>
                    </dm:preconditions>
                    <dm:forwardDifferences rdf:parseType="Statements">
                      <cim:ConformLoad rdf:about="#_EnergyConsumer">
                        <cim:EnergyConsumer.p>12.5</cim:EnergyConsumer.p>
                        <cim:EnergyConsumer.q>5</cim:EnergyConsumer.q>
                      </cim:ConformLoad>
                    </dm:forwardDifferences>
                    <dm:reverseDifferences rdf:parseType="Statements">
                      <cim:ConformLoad rdf:about="#_EnergyConsumer">
                        <cim:EnergyConsumer.p>10</cim:EnergyConsumer.p>
                        <cim:EnergyConsumer.q>5</cim:EnergyConsumer.q>
                      </cim:ConformLoad>
                    </dm:reverseDifferences>
                  </dm:DifferenceModel>
                </rdf:RDF>
                """.formatted(supersedes);

        assertEquals(CgmesDiffImport.Route.FAST, CgmesDiffImport.apply(openCgmesStyle,
                DifferenceModelParser.parse(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)),
                        "x_SSH_DIFF.xml"), previousValues(), ReportNode.NO_OP).route());
        assertEquals(SteadyStateFingerprint.of(canonical), SteadyStateFingerprint.of(openCgmesStyle));
    }

    @Test
    void readOfADataSourceWithTwoModelsOfOneProfileIsRejected() {
        Network sender = load();
        DifferenceModel model = differenceOf(sender, n -> n.getLoad(LOAD).setP0(12.5))
                .get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
        com.powsybl.commons.datasource.MemDataSource dataSource = new com.powsybl.commons.datasource.MemDataSource();
        byte[] document = DifferenceModelWriter.toString(model).getBytes(StandardCharsets.UTF_8);
        dataSource.putData("a_SSH_DIFF.xml", document);
        dataSource.putData("b_SSH_DIFF.xml", document);
        try {
            CgmesDiffImport.read(dataSource);
            throw new AssertionError("two models of one profile have to be rejected");
        } catch (com.powsybl.cgmes.model.CgmesModelException e) {
            assertTrue(e.getMessage().contains("Supersedes order"), e.getMessage());
        } catch (UncheckedIOException e) {
            throw new AssertionError(e);
        }
    }
}
