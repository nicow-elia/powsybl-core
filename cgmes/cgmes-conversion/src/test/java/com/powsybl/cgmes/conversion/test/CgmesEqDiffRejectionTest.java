/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport.ExportOptions;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport.UnsupportedChangeBehavior;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.OperationalLimitsGroup;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The equipment changes of work package 5 that the difference model export refuses, one test per row of the
 * "not supported" table of the specification.
 *
 * <p>Each of them is a change a receiver could not act on: a structural change CGMES models with objects rather than
 * with values, a value the receiving importer would silently drop, or a value whose CGMES spelling is ambiguous.
 * Refusing them is the point &mdash; writing them would produce a document that does not describe the sender.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesEqDiffRejectionTest {

    private static final String LINE_DIR = "/update/line/";
    private static final String[] LINE_FILES = {"line_EQ.xml", "line_SSH.xml"};
    private static final String TRANSFORMER_DIR = "/update/transformer/";
    private static final String[] TRANSFORMER_FILES = {"transformer_EQ.xml", "transformer_SSH.xml"};
    private static final String LIMITS_DIR = "/issues/operational-limits/";
    private static final String AC_LINE_SEGMENT = "ACLineSegment";
    /** The identifier the merged-line fixture carries, written {@code _A+%2B+B} in the CGMES file. */
    private static final String MERGED_LINE_ID = "A + B";

    // Helpers

    /** Export the given change and return the message of the refusal, failing when the change is exported. */
    private static String refusal(Network network, Consumer<Network> change) {
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, change);
        PowsyblException e = assertThrows(PowsyblException.class,
            () -> CgmesDiffExport.toDifferences(network, events,
                    new ExportOptions().setUnsupportedChangeBehavior(UnsupportedChangeBehavior.FAIL)),
            "the change was exported although it cannot be");
        return e.getMessage();
    }

    private static Network lineNetwork() {
        return readCgmesResources(LINE_DIR, LINE_FILES);
    }

    // Operational limits: structural changes

    @Test
    void selectingAnotherGroupIsRefused() {
        Network network = lineNetwork();
        network.getLine(AC_LINE_SEGMENT).newOperationalLimitsGroup1("EMPTY");
        String message = refusal(network, n -> n.getLine(AC_LINE_SEGMENT).setSelectedOperationalLimitsGroup1("EMPTY"));
        assertTrue(message.contains("selecting, creating or removing an operational limits group"), message);
    }

    @Test
    void removingTheSelectedGroupIsRefused() {
        Network network = lineNetwork();
        String message = refusal(network, n -> {
            Line line = n.getLine(AC_LINE_SEGMENT);
            line.removeOperationalLimitsGroup1(line.getSelectedOperationalLimitsGroupId1().orElseThrow());
        });
        assertTrue(message.contains("selecting, creating or removing an operational limits group"), message);
    }

    @Test
    void removingTheLimitsOfAGroupIsRefused() {
        Network network = lineNetwork();
        String message = refusal(network, n -> group1(n).removeCurrentLimits());
        assertTrue(message.contains("adding or removing operational limits is a structural change"), message);
    }

    @Test
    void addingATemporaryLimitThroughAWholeReplacementIsRefused() {
        Network network = lineNetwork();
        String message = refusal(network, n -> group1(n).newCurrentLimits()
                .setPermanentLimit(800.0)
                .beginTemporaryLimit().setAcceptableDuration(900).setName("TATL 900").setValue(2000.0)
                .endTemporaryLimit()
                .beginTemporaryLimit().setAcceptableDuration(60).setName("TATL 60").setValue(2500.0)
                .endTemporaryLimit()
                .add());
        // Whichever of the two fires first: the new temporary limit has no CGMES object at all, and the set of
        // acceptable durations is not the one the change set started from
        assertTrue(message.contains("adding or removing operational limits is a structural change")
                || message.contains("has no CGMES OperationalLimit id"), message);
    }

    @Test
    void aLimitCreatedAfterTheCgmesImportHasNoOperationalLimitId() {
        Network network = lineNetwork();
        String message = refusal(network, n -> n.getLine(AC_LINE_SEGMENT)
                .newOperationalLimitsGroup1("NEW").newCurrentLimits().setPermanentLimit(700.0).add());
        assertTrue(message.contains("has no CGMES OperationalLimit id")
                || message.contains("adding or removing operational limits is a structural change"), message);
    }

    @Test
    void anOperationalLimitSharedByBothSidesCannotBeChangedOnOneSideOnly() {
        Network network = readCgmesResources(LIMITS_DIR, "limitsets_associated_to_equipments_EQ.xml",
                "limitsets_EQBD.xml", "limitsets_TPBD.xml");
        String message = refusal(network,
                n -> n.getLine("ACL").getCurrentLimits1().orElseThrow().setPermanentLimit(120.0));
        assertTrue(message.contains("applies to the whole equipment in CGMES"), message);
    }

    @Test
    void anOperationalLimitSharedByBothSidesIsExportedWhenBothSidesAgree() {
        Network network = readCgmesResources(LIMITS_DIR, "limitsets_associated_to_equipments_EQ.xml",
                "limitsets_EQBD.xml", "limitsets_TPBD.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> {
            n.getLine("ACL").getCurrentLimits1().orElseThrow().setPermanentLimit(120.0);
            n.getLine("ACL").getCurrentLimits2().orElseThrow().setPermanentLimit(120.0);
        });
        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, events,
                new ExportOptions().setUnsupportedChangeBehavior(UnsupportedChangeBehavior.FAIL));
        // One CGMES object, so one subject, although two IIDM limits changed
        assertEquals(1, result.differences().get(CgmesSubset.EQUIPMENT).orElseThrow().forward().size());
    }

    /** The synthesized permanent limit itself has no CGMES object, so a change of it cannot travel. */
    @Test
    void aSynthesizedPermanentLimitCannotBeChanged() {
        Network network = readCgmesResources(LIMITS_DIR, "missing_limits.xml");
        String message = refusal(network,
                n -> n.getLine("ACL").getCurrentLimits1().orElseThrow().setPermanentLimit(90.0));
        assertTrue(message.contains("has no CGMES OperationalLimit id"), message);
    }

    /**
     * A whole replacement that drops a temporary limit is structural, and the check has to hold for the partial
     * steady state hypothesis export too, which reads the live network only: the removal is in the payload of the
     * event, not in a difference between two states.
     */
    @Test
    void aWholeReplacementThatRemovesATemporaryLimitIsRefusedByThePartialSsh() {
        Network network = lineNetwork();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network,
            n -> group1(n).newCurrentLimits().setPermanentLimit(900.0).add());
        PowsyblException e = assertThrows(PowsyblException.class,
            () -> PartialSshExport.toString(network, events, UnsupportedChangeBehavior.FAIL));
        assertTrue(e.getMessage().contains("adding or removing operational limits is a structural change"),
                e.getMessage());
    }

    @Test
    void aWholeReplacementThatRemovesATemporaryLimitIsRefusedBySingleProfileDocuments() {
        Network network = lineNetwork();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network,
            n -> group1(n).newCurrentLimits().setPermanentLimit(900.0).add());
        PowsyblException e = assertThrows(PowsyblException.class, () -> CgmesDiffExport.toString(network, events,
                CgmesSubset.STEADY_STATE_HYPOTHESIS, UnsupportedChangeBehavior.FAIL));
        assertTrue(e.getMessage().contains("adding or removing operational limits is a structural change"),
                e.getMessage());
    }

    /** In CGMES 2.4.15 a limit value is equipment data, so a document of the steady state profile cannot carry it. */
    @Test
    void aCim16LimitChangeDoesNotFitInASteadyStateHypothesisDocument() {
        Network network = readCgmesResources(LIMITS_DIR, "loading_limits.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network,
            n -> n.getLine("ACL").getCurrentLimits1().orElseThrow().setPermanentLimit(110.0));
        PowsyblException e = assertThrows(PowsyblException.class, () -> CgmesDiffExport.toString(network, events,
                CgmesSubset.STEADY_STATE_HYPOTHESIS, UnsupportedChangeBehavior.FAIL));
        assertTrue(e.getMessage().contains("the change belongs to the EQ profile"), e.getMessage());
    }

    // Voltage limits

    @Test
    void aVoltageLimitOutsideTheVoltageLevelRangeIsRefused() {
        Network network = readCgmesResources(LIMITS_DIR, "voltage_limits.xml");
        String message = refusal(network, n -> n.getVoltageLevel("VL_1").setHighVoltageLimit(425.0));
        assertTrue(message.contains("strictly inside the VoltageLevel range"), message);
    }

    @Test
    void aHighVoltageLimitBelowTheLowOneIsRefused() {
        Network network = readCgmesResources(LIMITS_DIR, "voltage_limits.xml");
        String message = refusal(network, n -> n.getVoltageLevel("VL_1").setLowVoltageLimit(385.0)
                .setHighVoltageLimit(385.0));
        assertTrue(message.contains("strictly inside the VoltageLevel range"), message);
    }

    @Test
    void anUndefinedVoltageLimitIsRefused() {
        Network network = readCgmesResources(LIMITS_DIR, "voltage_limits.xml");
        String message = refusal(network, n -> n.getVoltageLevel("VL_1").setHighVoltageLimit(Double.NaN));
        assertTrue(message.contains("voltage limits must be finite"), message);
    }

    /**
     * A {@code VoltageLimit} object that did not narrow the range leaves the IIDM limit equal to the
     * {@code VoltageLevel} attribute itself. Changing it cannot be undone through {@code VoltageLimit} values,
     * because the receiver filters the old value out again, so the change is refused with that reason.
     */
    @Test
    void aVoltageLimitThatDoesNotBindTheVoltageLevelCannotBeChanged() {
        Network network = readCgmesResources(LIMITS_DIR, "voltage_limits.xml");
        assertEquals(420.0, network.getVoltageLevel("VL_2").getHighVoltageLimit(), 1e-9);
        String message = refusal(network, n -> n.getVoltageLevel("VL_2").setHighVoltageLimit(410.0));
        assertTrue(message.contains("is the VoltageLevel attribute itself"), message);
    }

    @Test
    void aMergedVoltageLevelHasSeveralCgmesObjects() {
        Network network = EurostagTutorialExample1Factory.create();
        VoltageLevel voltageLevel = network.getVoltageLevel("VLHV1");
        voltageLevel.addAlias("MERGED_1", Conversion.CGMES_PREFIX_ALIAS_PROPERTIES + "MergedVoltageLevel1");
        String message = refusal(network, n -> n.getVoltageLevel("VLHV1").setHighVoltageLimit(420.0));
        assertTrue(message.contains("merged voltage levels have several CGMES VoltageLevel objects"), message);
    }

    // Impedances

    @Test
    void aTransformerImpedanceCannotBeMappedToCgmesEnds() {
        Network network = readCgmesResources(TRANSFORMER_DIR, TRANSFORMER_FILES);
        String message = refusal(network, n -> n.getTwoWindingsTransformer("T2W").setR(1.0));
        assertTrue(message.contains("transformer impedances cannot be mapped to CGMES ends"), message);
    }

    @Test
    void aThreeWindingsTransformerLegImpedanceCannotBeMappedToCgmesEnds() {
        Network network = readCgmesResources(TRANSFORMER_DIR, TRANSFORMER_FILES);
        String message = refusal(network, n -> n.getThreeWindingsTransformer("T3W").getLeg1().setR(1.0));
        assertTrue(message.contains("transformer impedances cannot be mapped to CGMES ends"), message);
    }

    @Test
    void aZeroImpedanceBranchInsideOneVoltageLevelBecomesASwitch() {
        Network network = lineInsideOneVoltageLevel();
        String message = refusal(network, n -> n.getLine("L").setR(0.0).setX(0.0));
        assertTrue(message.contains("becomes a switch on import"), message);
    }

    /**
     * A tiny network holding a line whose two ends are in one voltage level, which is what the CGMES import turns
     * into a switch when its impedance is zero. No CGMES fixture has one, because such a branch is imported as a
     * switch and never comes back as a line.
     */
    private static Network lineInsideOneVoltageLevel() {
        Network network = Network.create("one-voltage-level", "test");
        VoltageLevel voltageLevel = network.newSubstation().setId("S").add()
                .newVoltageLevel().setId("VL").setNominalV(400.0)
                .setTopologyKind(com.powsybl.iidm.network.TopologyKind.BUS_BREAKER).add();
        voltageLevel.getBusBreakerView().newBus().setId("B1").add();
        voltageLevel.getBusBreakerView().newBus().setId("B2").add();
        network.newLine().setId("L")
                .setVoltageLevel1("VL").setBus1("B1")
                .setVoltageLevel2("VL").setBus2("B2")
                .setR(0.1).setX(1.0).setG1(0.0).setB1(0.0).setG2(0.0).setB2(0.0)
                .add()
                .setProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS, "ACLineSegment");
        return network;
    }

    @Test
    void anAsymmetricShuntAdmittanceIsRefused() {
        Network network = lineNetwork();
        String message = refusal(network, n -> n.getLine(AC_LINE_SEGMENT).setG1(1e-4));
        assertTrue(message.contains("g1 == g2 and b1 == b2 are required"), message);
    }

    /**
     * The symmetry tolerance is relative, not absolute: an absolute tolerance of 1e-9 S would accept a difference in
     * the fifth significant digit of a susceptance of 4.55e-4 S, which the receiver would then split equally and hold
     * a different value from the sender's.
     */
    @Test
    void theShuntAdmittanceSymmetryToleranceIsRelative() {
        Network network = lineNetwork();
        double b = network.getLine(AC_LINE_SEGMENT).getB1();
        // Well inside a relative 1e-9 of 4.55e-4 S, and exported
        List<NetworkEvent> events = RecordedChangeScenarios.record(network,
            n -> n.getLine(AC_LINE_SEGMENT).setB1(b * 2).setB2(b * 2 * (1 + 1e-12)));
        CgmesDiffExport.toDifferences(network, events,
                new ExportOptions().setUnsupportedChangeBehavior(UnsupportedChangeBehavior.FAIL));

        // A relative 1e-6, far below an absolute 1e-9 S at this magnitude, and refused
        Network other = lineNetwork();
        String message = refusal(other, n -> n.getLine(AC_LINE_SEGMENT).setB1(b * 2).setB2(b * 2 * (1 + 1e-6)));
        assertTrue(message.contains("g1 == g2 and b1 == b2 are required"), message);
    }

    /**
     * The import folds the ideal ratio between the two nominal voltages of an {@code EquivalentBranch} into the IIDM
     * parameters, so the IIDM value is not the value the CGMES file holds and cannot be written back.
     */
    @Test
    void anEquivalentBranchBetweenTwoNominalVoltagesIsRefused() {
        Network network = equivalentBranchBetweenTwoNominalVoltages();
        String message = refusal(network, n -> n.getLine("EB").setR(1.0));
        assertTrue(message.contains("transforms EquivalentBranch parameters between nominal voltages"), message);
    }

    private static Network equivalentBranchBetweenTwoNominalVoltages() {
        Network network = Network.create("equivalent-branch", "test");
        VoltageLevel high = network.newSubstation().setId("S").add()
                .newVoltageLevel().setId("VL400").setNominalV(400.0)
                .setTopologyKind(com.powsybl.iidm.network.TopologyKind.BUS_BREAKER).add();
        VoltageLevel low = network.getSubstation("S")
                .newVoltageLevel().setId("VL225").setNominalV(225.0)
                .setTopologyKind(com.powsybl.iidm.network.TopologyKind.BUS_BREAKER).add();
        high.getBusBreakerView().newBus().setId("B400").add();
        low.getBusBreakerView().newBus().setId("B225").add();
        network.newLine().setId("EB")
                .setVoltageLevel1("VL400").setBus1("B400")
                .setVoltageLevel2("VL225").setBus2("B225")
                .setR(0.9).setX(9.0).setG1(0.0).setB1(0.0).setG2(0.0).setB2(0.0)
                .add()
                .setProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS, "EquivalentBranch");
        return network;
    }

    @Test
    void aSeriesCompensatorHasNoShuntAdmittance() {
        Network network = lineNetwork();
        String message = refusal(network, n -> n.getLine("SeriesCompensator").setB1(1e-4));
        assertTrue(message.contains("has no shunt admittance in CGMES"), message);
    }

    @Test
    void aNegativeResistanceIsRefused() {
        Network network = lineNetwork();
        String message = refusal(network, n -> n.getLine(AC_LINE_SEGMENT).setR(-1.0));
        assertTrue(message.contains("impedance values must be finite"), message);
    }

    /**
     * A line whose identifier is a pair of identifiers joined by {@code " + "} names no single CGMES object.
     *
     * <p>Such an identifier reaches a network in two ways: powsybl builds one when one IIDM object stands for two
     * ({@code TieLineUtil.buildMergedId}), and a CGMES model exported from such a network carries it back, written
     * URL-encoded because a space is not allowed in an {@code rdf:ID}. A difference model receiver resolves the
     * identifier of a statement exactly as it is written, so the document would be refused on the receiving side,
     * after it has been handed over. The refusal belongs here, before anything is written.</p>
     */
    @Test
    void aMergedIdentifierHasNoSingleCgmesObjectToCarryAnImpedanceChange() {
        Network network = readCgmesResources("/update/mergedline/", "mergedline_EQ.xml", "mergedline_SSH.xml");
        Line line = network.getLine(MERGED_LINE_ID);
        assertNotNull(line, "the fixture is expected to hold a line with a pair identifier, has "
                + network.getLineStream().map(Line::getId).toList());
        String message = refusal(network, n -> n.getLine(MERGED_LINE_ID).setR(2.0));
        assertTrue(message.contains("is not a single CGMES master resource identifier"), message);
    }

    /** The same change with {@link UnsupportedChangeBehavior#IGNORE} is left out instead of failing. */
    @Test
    void aMergedIdentifierIsLeftOutWhenUnsupportedChangesAreIgnored() {
        Network network = readCgmesResources("/update/mergedline/", "mergedline_EQ.xml", "mergedline_SSH.xml");
        List<NetworkEvent> events =
                RecordedChangeScenarios.record(network, n -> n.getLine(MERGED_LINE_ID).setR(2.0));
        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, events,
                new ExportOptions().setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE));
        assertTrue(result.exportedEvents().isEmpty(), "nothing should have been exported");
        assertTrue(result.differences().isEmpty(), "no difference model should have been produced");
    }

    // The behaviour of IGNORE: the change is left out, the others survive

    @Test
    void ignoredChangesAreLeftOutAndTheOthersSurvive() {
        Network network = lineNetwork();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> {
            n.getLine(AC_LINE_SEGMENT).setG1(1e-4);
            n.getLine(AC_LINE_SEGMENT).setR(1.6);
        });
        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, events,
                new ExportOptions().setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE));
        assertEquals(1, result.exportedEvents().size());
        assertEquals("ACLineSegment.r",
                result.differences().get(CgmesSubset.EQUIPMENT).orElseThrow().forward().get(0).property());
    }

    private static OperationalLimitsGroup group1(Network network) {
        return network.getLine(AC_LINE_SEGMENT).getOperationalLimitsGroups1().iterator().next();
    }
}
