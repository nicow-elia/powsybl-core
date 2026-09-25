/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport.ExportOptions;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport.UnsupportedChangeBehavior;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios.Scenario;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.DifferenceModelWriter;
import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.CurrentLimits;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.OperationalLimitsGroup;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The statements the difference model export writes for the equipment values of work package 5: operational limits,
 * voltage level limits and branch impedances.
 *
 * <p>Every test asserts the exact CGMES subjects and values of both directions, because the whole point of these
 * mappings is that a receiver applies them without knowing anything about IIDM.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesEqDiffExportTest {

    private static final CgmesSubset EQ = CgmesSubset.EQUIPMENT;
    private static final CgmesSubset SSH = CgmesSubset.STEADY_STATE_HYPOTHESIS;

    private static final String LINE_DIR = "/update/line/";
    private static final String[] LINE_FILES = {"line_EQ.xml", "line_SSH.xml"};
    private static final String VOLTAGE_LEVEL_DIR = "/update/voltage-level/";
    private static final String[] VOLTAGE_LEVEL_FILES = {"voltageLevel_EQ.xml", "voltageLevel_SSH.xml"};
    private static final String BOUNDARY_LINE_DIR = "/update/boundary-line/";
    private static final String LIMITS_DIR = "/issues/operational-limits/";
    private static final String[] BOUNDARY_LINE_FILES =
        {"boundaryLine_EQ.xml", "boundaryLine_EQ_BD.xml", "boundaryLine_SSH.xml"};

    private static final String AC_LINE_SEGMENT = "ACLineSegment";
    private static final String CURRENT_LIMIT_VALUE = "CurrentLimit.value";
    private static final String PATL_1 = "ACLineSegment-T1-OperationalLimitSet-CurrentLimit1";
    private static final String TATL_1 = "ACLineSegment-T1-OperationalLimitSet-CurrentLimit2";
    private static final String PATL_2 = "ACLineSegment-T2-OperationalLimitSet-CurrentLimit1";
    private static final String TATL_2 = "ACLineSegment-T2-OperationalLimitSet-CurrentLimit2";

    // Helpers

    private static DifferenceModelSet differences(Network network, Consumer<Network> change) {
        return differences(network, change, new ExportOptions());
    }

    private static DifferenceModelSet differences(Network network, Consumer<Network> change, ExportOptions options) {
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, change);
        return CgmesDiffExport.toDifferences(network, events, options).differences();
    }

    private static DifferenceModel model(DifferenceModelSet set, CgmesSubset subset) {
        return set.get(subset).orElseThrow(() -> new AssertionError("no " + subset + " difference in " + set.models()));
    }

    private static String value(List<CgmesStatement> statements, String subjectId, String property) {
        return statements.stream()
                .filter(s -> s.subjectId().equals(subjectId) && s.property().equals(property))
                .map(CgmesStatement::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + property + " of " + subjectId + " in " + statements));
    }

    private static Set<String> subjects(List<CgmesStatement> statements) {
        return statements.stream().map(CgmesStatement::subjectId).collect(Collectors.toSet());
    }

    private static Network lineNetwork() {
        return readCgmesResources(LINE_DIR, LINE_FILES);
    }

    private static CurrentLimits currentLimits1(Network network) {
        return network.getLine(AC_LINE_SEGMENT).getCurrentLimits1().orElseThrow();
    }

    private static CurrentLimits currentLimits2(Network network) {
        return network.getLine(AC_LINE_SEGMENT).getCurrentLimits2().orElseThrow();
    }

    // CIM version routing

    @Test
    void cim100LimitValuesGoToSsh() {
        Network network = lineNetwork();
        DifferenceModelSet differences = differences(network, n -> currentLimits1(n).setPermanentLimit(850.0));
        assertEquals(Set.of(SSH), differences.models().keySet());
        DifferenceModel ssh = model(differences, SSH);
        assertEquals("850", value(ssh.forward(), PATL_1, CURRENT_LIMIT_VALUE));
        assertEquals("797", value(ssh.reverse(), PATL_1, CURRENT_LIMIT_VALUE));
    }

    @Test
    void cim16LimitValuesGoToEq() {
        Network network = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        Line line = network.getLineStream().filter(l -> l.getCurrentLimits1().isPresent()).findFirst().orElseThrow();
        double before = line.getCurrentLimits1().orElseThrow().getPermanentLimit();
        DifferenceModelSet differences = differences(network,
                n -> n.getLine(line.getId()).getCurrentLimits1().orElseThrow().setPermanentLimit(before + 10));
        assertEquals(Set.of(EQ), differences.models().keySet());
        DifferenceModel eq = model(differences, EQ);
        assertFalse(eq.forward().isEmpty());
        assertTrue(eq.forward().stream().allMatch(s -> s.property().equals(CURRENT_LIMIT_VALUE)));
    }

    // The consistency group is the whole LoadingLimits object

    /**
     * The mapping describes every limit of the {@code LoadingLimits} object, and the difference builder then drops
     * the subjects whose two directions say the same thing, which is the no-op rule of the change export. A CGMES
     * OperationalLimit is a subject of its own, so an unchanged temporary limit of the group does not reach the
     * document; the receiver keeps the value it holds, because a difference is applied with previous values.
     */
    @Test
    void patlChangeEmitsTheChangedLimitsOfTheGroupOnly() {
        Network network = lineNetwork();
        DifferenceModel ssh = model(differences(network, n -> currentLimits1(n).setPermanentLimit(850.0)), SSH);
        assertEquals(Set.of(PATL_1), subjects(ssh.forward()));
        assertEquals("850", value(ssh.forward(), PATL_1, CURRENT_LIMIT_VALUE));
        assertEquals("797", value(ssh.reverse(), PATL_1, CURRENT_LIMIT_VALUE));
    }

    @Test
    void aWholeReplacementChangingSeveralLimitsEmitsAllOfThem() {
        Network network = lineNetwork();
        DifferenceModel ssh = model(differences(network, n -> {
            currentLimits1(n).setPermanentLimit(850.0);
            currentLimits1(n).setTemporaryLimitValue(900, 2100.0);
        }), SSH);
        assertEquals(Set.of(PATL_1, TATL_1), subjects(ssh.forward()));
        assertEquals("850", value(ssh.forward(), PATL_1, CURRENT_LIMIT_VALUE));
        assertEquals("2100", value(ssh.forward(), TATL_1, CURRENT_LIMIT_VALUE));
        assertEquals("797", value(ssh.reverse(), PATL_1, CURRENT_LIMIT_VALUE));
        assertEquals("1991", value(ssh.reverse(), TATL_1, CURRENT_LIMIT_VALUE));
    }

    @Test
    void temporaryLimitChangeKeepsTheOtherLimitsOfTheGroup() {
        Network network = lineNetwork();
        DifferenceModel ssh = model(differences(network,
                n -> currentLimits2(n).setTemporaryLimitValue(900, 2100.0)), SSH);
        assertEquals(Set.of(TATL_2), subjects(ssh.forward()));
        assertEquals("2100", value(ssh.forward(), TATL_2, CURRENT_LIMIT_VALUE));
        assertEquals("1989", value(ssh.reverse(), TATL_2, CURRENT_LIMIT_VALUE));
    }

    @Test
    void bothSidesAreSeparateSubjects() {
        Network network = lineNetwork();
        DifferenceModel ssh = model(differences(network, n -> {
            currentLimits1(n).setPermanentLimit(850.0);
            currentLimits2(n).setPermanentLimit(860.0);
        }), SSH);
        assertEquals(Set.of(PATL_1, PATL_2), subjects(ssh.forward()));
        assertEquals("850", value(ssh.forward(), PATL_1, CURRENT_LIMIT_VALUE));
        assertEquals("860", value(ssh.forward(), PATL_2, CURRENT_LIMIT_VALUE));
        assertEquals("797", value(ssh.reverse(), PATL_1, CURRENT_LIMIT_VALUE));
        assertEquals("795", value(ssh.reverse(), PATL_2, CURRENT_LIMIT_VALUE));
    }

    @Test
    void wholeReplacementBeforeValuesComeFromTheOldObject() {
        Network network = lineNetwork();
        DifferenceModel ssh = model(differences(network, n -> {
            OperationalLimitsGroup group = n.getLine(AC_LINE_SEGMENT).getOperationalLimitsGroups1().iterator().next();
            group.newCurrentLimits()
                    .setPermanentLimit(900.0)
                    .beginTemporaryLimit().setAcceptableDuration(900).setName("TATL 900").setValue(2000.0)
                    .endTemporaryLimit()
                    .add();
        }), SSH);
        assertEquals("900", value(ssh.forward(), PATL_1, CURRENT_LIMIT_VALUE));
        assertEquals("2000", value(ssh.forward(), TATL_1, CURRENT_LIMIT_VALUE));
        // The values of the object the change replaced, not the ones the network holds now
        assertEquals("797", value(ssh.reverse(), PATL_1, CURRENT_LIMIT_VALUE));
        assertEquals("1991", value(ssh.reverse(), TATL_1, CURRENT_LIMIT_VALUE));
    }

    @Test
    void memberChangeThenWholeReplacementUsesTheEarliestOldValue() {
        Network network = lineNetwork();
        DifferenceModel ssh = model(differences(network, n -> {
            currentLimits1(n).setPermanentLimit(810.0);
            OperationalLimitsGroup group = n.getLine(AC_LINE_SEGMENT).getOperationalLimitsGroups1().iterator().next();
            group.newCurrentLimits()
                    .setPermanentLimit(900.0)
                    .beginTemporaryLimit().setAcceptableDuration(900).setName("TATL 900").setValue(2000.0)
                    .endTemporaryLimit()
                    .add();
        }), SSH);
        assertEquals("900", value(ssh.forward(), PATL_1, CURRENT_LIMIT_VALUE));
        // 797, the value the first change of the permanent limit replaced, not the intermediate 810
        assertEquals("797", value(ssh.reverse(), PATL_1, CURRENT_LIMIT_VALUE));
    }

    /**
     * The CGMES import synthesizes a permanent limit for a set that has none, and such a limit is not a CGMES object
     * at all. That must not make the rest of the set unexportable: a change of a temporary limit that <em>does</em>
     * have a CGMES object travels, and the synthesized permanent limit is simply left out, because the receiver
     * derives its own from the same import option.
     */
    @Test
    void aTemporaryLimitOfASetWithoutCgmesPatlIsExported() {
        Network network = readCgmesResources(LIMITS_DIR, "missing_limits.xml");
        CurrentLimits limits = network.getLine("ACL").getCurrentLimits1().orElseThrow();
        int duration = limits.getTemporaryLimits().iterator().next().getAcceptableDuration();
        double before = limits.getTemporaryLimit(duration).getValue();

        DifferenceModelSet differences = differences(network, n -> n.getLine("ACL").getCurrentLimits1().orElseThrow()
                .setTemporaryLimitValue(duration, before + 5));
        DifferenceModel eq = model(differences, EQ);
        assertEquals(1, eq.forward().size(), () -> "only the temporary limit has a CGMES object: " + eq.forward());
        assertEquals(String.valueOf((int) (before + 5)), eq.forward().get(0).value());
        assertEquals(String.valueOf((int) before), eq.reverse().get(0).value());
    }

    // Voltage level limits

    @Test
    void voltageLimitWritesEveryStoredId() {
        Network network = readCgmesResources(VOLTAGE_LEVEL_DIR, VOLTAGE_LEVEL_FILES);
        VoltageLevel voltageLevel = network.getVoltageLevel("VL_1");
        double highBefore = voltageLevel.getHighVoltageLimit();
        DifferenceModelSet differences = differences(network, n -> n.getVoltageLevel("VL_1").setHighVoltageLimit(405.0));
        DifferenceModel ssh = model(differences, SSH);
        assertEquals(Set.of("VL_H_11", "VL_H_12"), subjects(ssh.forward()));
        assertEquals("405", value(ssh.forward(), "VL_H_11", "VoltageLimit.value"));
        assertEquals("405", value(ssh.forward(), "VL_H_12", "VoltageLimit.value"));
        // Both identifiers receive the IIDM aggregate in both directions, which is the value the network holds:
        // 420 was outside the (380, 420) range of the voltage level, so only 395 reached IIDM
        assertEquals("395", value(ssh.reverse(), "VL_H_11", "VoltageLimit.value"));
        assertEquals("395", value(ssh.reverse(), "VL_H_12", "VoltageLimit.value"));
        assertEquals(395.0, highBefore, 1e-9);
    }

    @Test
    void voltageLevelWithoutVoltageLimitObjectsWritesTheVoltageLevelAttribute() {
        Network network = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        VoltageLevel voltageLevel = network.getVoltageLevelStream()
                .filter(vl -> !vl.hasProperty("CGMES.OperationalLimit_highVoltageLimit"))
                .findFirst().orElseThrow();
        double low = voltageLevel.getLowVoltageLimit();
        DifferenceModelSet differences = differences(network,
                n -> n.getVoltageLevel(voltageLevel.getId()).setHighVoltageLimit(Double.isNaN(low) ? 420.0 : low + 30));
        DifferenceModel eq = model(differences, EQ);
        assertEquals(Set.of(voltageLevel.getId()), subjects(eq.forward()));
        assertEquals("VoltageLevel.highVoltageLimit", eq.forward().get(0).property());
    }

    // Impedances

    @Test
    void lineResistanceIsOneEquipmentStatement() {
        Network network = lineNetwork();
        DifferenceModelSet differences = differences(network, n -> n.getLine(AC_LINE_SEGMENT).setR(1.6));
        assertEquals(Set.of(EQ), differences.models().keySet());
        DifferenceModel eq = model(differences, EQ);
        assertEquals(1, eq.forward().size());
        assertEquals("1.6", value(eq.forward(), AC_LINE_SEGMENT, "ACLineSegment.r"));
        assertEquals("1.5", value(eq.reverse(), AC_LINE_SEGMENT, "ACLineSegment.r"));
    }

    @Test
    void lineShuntAdmittanceIsSummed() {
        Network network = lineNetwork();
        Line line = network.getLine(AC_LINE_SEGMENT);
        double b = line.getB1();
        DifferenceModel eq = model(differences(network,
                n -> n.getLine(AC_LINE_SEGMENT).setB1(b * 2).setB2(b * 2)), EQ);
        assertEquals("0.00182", value(eq.forward(), AC_LINE_SEGMENT, "ACLineSegment.bch"));
        assertEquals("0.00091", value(eq.reverse(), AC_LINE_SEGMENT, "ACLineSegment.bch"));
    }

    @Test
    void impedanceLiteralsAreLossless() {
        Network network = lineNetwork();
        DifferenceModel eq = model(differences(network, n -> n.getLine(AC_LINE_SEGMENT).setR(1e-9)), EQ);
        String written = value(eq.forward(), AC_LINE_SEGMENT, "ACLineSegment.r");
        assertEquals(1e-9, Double.parseDouble(written), 0.0);
    }

    @Test
    void seriesCompensatorAndEquivalentBranchUseTheirOwnClass() {
        Network network = lineNetwork();
        DifferenceModel eq = model(differences(network, n -> n.getLine("SeriesCompensator").setX(11.0)), EQ);
        assertEquals("11", value(eq.forward(), "SeriesCompensator", "SeriesCompensator.x"));

        Network other = lineNetwork();
        DifferenceModel eqOther = model(differences(other, n -> n.getLine("EquivalentBranch").setR(0.5)), EQ);
        assertEquals("0.5", value(eqOther.forward(), "EquivalentBranch", "EquivalentBranch.r"));
    }

    @Test
    void boundaryLineAdmittanceIsNotSplit() {
        Network network = readCgmesResources(BOUNDARY_LINE_DIR, BOUNDARY_LINE_FILES);
        BoundaryLine boundaryLine = network.getBoundaryLine(AC_LINE_SEGMENT);
        double b = boundaryLine.getB();
        DifferenceModel eq = model(differences(network,
                n -> n.getBoundaryLine(AC_LINE_SEGMENT).setB(b + 0.001)), EQ);
        assertEquals(String.valueOf(b + 0.001), Double.toString(
                Double.parseDouble(value(eq.forward(), AC_LINE_SEGMENT, "ACLineSegment.bch"))));
    }

    // Mixed change sets and the partial SSH export

    @Test
    void anImpedanceAndALimitChangeOfOneCim100NetworkProduceTwoModels() {
        Network network = lineNetwork();
        DifferenceModelSet differences = differences(network, n -> {
            n.getLine(AC_LINE_SEGMENT).setR(1.6);
            currentLimits1(n).setPermanentLimit(850.0);
        });
        assertEquals(Set.of(EQ, SSH), differences.models().keySet());
        assertEquals("1.6", value(model(differences, EQ).forward(), AC_LINE_SEGMENT, "ACLineSegment.r"));
        assertEquals("850", value(model(differences, SSH).forward(), PATL_1, CURRENT_LIMIT_VALUE));
    }

    @Test
    void mixedChangeSetProducesTwoModelsAndSshDependsOnEqDiff() {
        Network network = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        Line line = network.getLineStream().filter(l -> l.getCurrentLimits1().isPresent()).findFirst().orElseThrow();
        double before = line.getCurrentLimits1().orElseThrow().getPermanentLimit();
        com.powsybl.iidm.network.Load load = network.getLoadStream().filter(l -> !l.isFictitious())
                .findFirst().orElseThrow();
        DifferenceModelSet differences = differences(network, n -> {
            n.getLine(line.getId()).getCurrentLimits1().orElseThrow().setPermanentLimit(before + 10);
            n.getLoad(load.getId()).setP0(load.getP0() + 1);
        });
        assertEquals(Set.of(EQ, SSH), differences.models().keySet());
        DifferenceModel eq = model(differences, EQ);
        DifferenceModel ssh = model(differences, SSH);
        assertTrue(ssh.header().dependentOn().contains(eq.header().id()),
                "the SSH difference has to depend on the EQ difference, not on the source EQ model");
    }

    @Test
    void partialSshExportsCim100LimitValues() {
        Network network = lineNetwork();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network,
                n -> currentLimits1(n).setPermanentLimit(850.0));
        String partialSsh = PartialSshExport.toString(network, events, UnsupportedChangeBehavior.FAIL);
        assertTrue(partialSsh.contains("<cim:CurrentLimit rdf:about=\"#_" + PATL_1 + "\">"), partialSsh);
        assertTrue(partialSsh.contains("<cim:CurrentLimit.value>850</cim:CurrentLimit.value>"), partialSsh);
    }

    @Test
    void theIdOfALimitOfANetworkNotImportedFromCgmesIsTheExportNamingOne() {
        Network network = com.powsybl.iidm.network.test.EurostagTutorialExample1Factory
                .createWithFixedCurrentLimits();
        DifferenceModelSet differences = differences(network,
                n -> n.getLine("NHV1_NHV2_1").getCurrentLimits1().orElseThrow().setPermanentLimit(600.0),
                new ExportOptions().setUnsupportedChangeBehavior(UnsupportedChangeBehavior.FAIL));
        // The network was not imported from CGMES, so the identifiers are the ones a full equipment export writes
        DifferenceModel eq = model(differences, EQ);
        assertFalse(eq.forward().isEmpty());
        assertTrue(eq.forward().stream().allMatch(s -> s.property().equals(CURRENT_LIMIT_VALUE)));
    }

    // Golden documents of the equipment scenarios

    static List<Scenario> equipmentScenarios() {
        return RecordedChangeScenarios.equipmentChanges();
    }

    /**
     * Pins the exact document of every equipment scenario, one per profile it touches. The steady state scenarios are
     * pinned by {@code CgmesDiffExportTest}, which writes the steady state document only.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("equipmentScenarios")
    void differenceModelOfEveryEquipmentScenarioIsUnchanged(Scenario scenario) throws IOException {
        Network network = scenario.load();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, scenario.forwardChange());
        DifferenceModelSet differences = CgmesDiffExport.toDifferences(network, events, goldenOptions())
                .differences();
        assertFalse(differences.models().isEmpty(), () -> scenario.name() + " produced no difference at all");
        for (Map.Entry<CgmesSubset, DifferenceModel> entry : differences.models().entrySet()) {
            String actual = DifferenceModelWriter.toString(entry.getValue()).replace("\r\n", "\n");
            assertEquals(golden(scenario.name(), entry.getKey(), actual), actual,
                    () -> scenario.name() + " " + entry.getKey().getIdentifier());
        }
    }

    private static ExportOptions goldenOptions() {
        ExportOptions options = new ExportOptions()
                .setUnsupportedChangeBehavior(UnsupportedChangeBehavior.FAIL)
                .setScenarioTime(ZonedDateTime.parse("2024-02-21T11:00:00Z"))
                .setCreated(ZonedDateTime.parse("2026-09-17T08:00:00Z"));
        options.header(EQ).setModelId("urn:uuid:00000000-0000-0000-0000-0000000000eq");
        options.header(SSH).setModelId("urn:uuid:00000000-0000-0000-0000-000000000001");
        return options;
    }

    private static String golden(String name, CgmesSubset subset, String actual) throws IOException {
        String fileName = name + "_" + subset.getIdentifier() + "_DIFF.xml";
        String resource = "/diff-golden/" + fileName;
        if (Boolean.getBoolean("diffstacking.regenerate")) {
            Path path = Path.of("src", "test", "resources", "diff-golden", fileName);
            Files.createDirectories(path.getParent());
            Files.writeString(path, actual, StandardCharsets.UTF_8);
        }
        try (InputStream inputStream = CgmesEqDiffExportTest.class.getResourceAsStream(resource)) {
            assertNotNull(inputStream, () -> "Missing golden file " + resource
                    + ", regenerate it with -Ddiffstacking.regenerate=true");
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    @Test
    void anExportRestrictedToTheSteadyStateHypothesisRefusesAnImpedanceChange() {
        Network network = lineNetwork();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> n.getLine(AC_LINE_SEGMENT).setR(1.6));
        PowsyblException e = assertThrows(PowsyblException.class,
            () -> CgmesDiffExport.toString(network, events, SSH, UnsupportedChangeBehavior.FAIL));
        assertTrue(e.getMessage().contains("the change belongs to the EQ profile"), e.getMessage());
    }

    @Test
    void aPartialSteadyStateHypothesisRefusesAnImpedanceChange() {
        Network network = lineNetwork();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> n.getLine(AC_LINE_SEGMENT).setR(1.6));
        PowsyblException e = assertThrows(PowsyblException.class,
            () -> PartialSshExport.toString(network, events, UnsupportedChangeBehavior.FAIL));
        assertTrue(e.getMessage().contains("the change belongs to the EQ profile"), e.getMessage());
    }
}
