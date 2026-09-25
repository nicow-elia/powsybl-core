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
import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport.DiffGranularity;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport.ExportOptions;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport.UnsupportedChangeBehavior;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios.Scenario;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.DifferenceModelWriter;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.datasource.DataSource;
import com.powsybl.commons.datasource.MemDataSource;
import com.powsybl.commons.datasource.ZipArchiveDataSource;
import com.powsybl.commons.test.AbstractSerDeTest;
import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.LccConverterStation;
import com.powsybl.iidm.network.LineCommutatedConverter;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.events.ExtensionCreationNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ReferencePriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesDiffExportTest extends AbstractSerDeTest {

    private static final CgmesSubset SSH = CgmesSubset.STEADY_STATE_HYPOTHESIS;
    private static final String LOAD_DIR = "/update/load/";
    private static final String GENERATOR_DIR = "/update/generator/";
    private static final String SWITCH_DIR = "/update/switch/";
    private static final String SHUNT_DIR = "/update/shunt-compensator/";
    private static final String TRANSFORMER_DIR = "/update/transformer/";
    private static final String HVDC_DIR = "/update/hvdc/";
    private static final String DC_DIR = "/issues/hvdc/";
    private static final String SYNCHRONOUS_MACHINE = "SynchronousMachine";

    static List<Scenario> scenarios() {
        return RecordedChangeScenarios.all();
    }

    // Helpers

    private static DifferenceModel diff(Network network, List<NetworkEvent> events) {
        return diff(network, events, new ExportOptions());
    }

    private static DifferenceModel diff(Network network, List<NetworkEvent> events, ExportOptions options) {
        return CgmesDiffExport.toDifferences(network, events, options).differences().get(SSH).orElseThrow();
    }

    private static DifferenceModel diffOf(String dir, Consumer<Network> change, String... files) {
        Network network = readCgmesResources(dir, files);
        return diff(network, RecordedChangeScenarios.record(network, change));
    }

    private static String value(List<CgmesStatement> statements, String subjectId, String property) {
        return statements.stream()
                .filter(s -> s.subjectId().equals(subjectId) && s.property().equals(property))
                .map(CgmesStatement::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + property + " of " + subjectId + " in " + statements));
    }

    private static boolean has(List<CgmesStatement> statements, String subjectId, String property) {
        return statements.stream().anyMatch(s -> s.subjectId().equals(subjectId) && s.property().equals(property));
    }

    private static Set<String> subjects(List<CgmesStatement> statements) {
        return statements.stream().map(CgmesStatement::subjectId).collect(java.util.stream.Collectors.toSet());
    }

    // The statements describe the same change as a partial SSH export does

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void statementsDescribeTheChange(Scenario scenario) {
        Network network = scenario.load();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, scenario.forwardChange());
        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, events, new ExportOptions());

        assertEquals(1, result.differences().models().size(), () -> scenario.name() + " touches one profile");
        DifferenceModel model = result.differences().get(SSH).orElseThrow();
        assertFalse(model.forward().isEmpty(), () -> scenario.name() + " produced no forward statement");
        assertEquals(subjects(model.forward()), subjects(model.reverse()),
                () -> scenario.name() + " describes different objects in the two directions");

        // Every forward statement is what a partial SSH export of the same changes writes
        String sshXml = PartialSshExport.toString(network, events, UnsupportedChangeBehavior.FAIL);
        String cimNamespace = model.header().cimNamespace();
        for (CgmesStatement statement : model.forward()) {
            String literal = "<cim:" + statement.property() + ">" + statement.value() + "<";
            String resource = statement.property() + " rdf:resource=\"" + cimNamespace + statement.value() + "\"";
            assertTrue(sshXml.contains(literal) || sshXml.contains(resource),
                    () -> scenario.name() + ": the partial SSH file does not hold " + statement + "\n" + sshXml);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void everyScenarioProducesAStructurallyValidDocument(Scenario scenario) {
        Network network = scenario.load();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, scenario.forwardChange());
        String cimNamespace = diff(network, events).header().cimNamespace();
        String xml = CgmesDiffExport.toString(network, events, SSH, UnsupportedChangeBehavior.FAIL);
        DifferenceModelXmlAssert.assertIsADifferenceModel(xml, cimNamespace);
    }

    /**
     * The reverse statements of a change and the forward statements of its undo describe the very same state, but
     * the first are reconstructed from the change log while the second are read from a network that really is in
     * that state. Comparing them is therefore an independent check of the overlay.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void forwardAndReverseAreSymmetric(Scenario scenario) {
        Network network = scenario.load();
        DifferenceModel forwardDiff = diff(network, RecordedChangeScenarios.record(network, scenario.forwardChange()));
        DifferenceModel backwardDiff = diff(network, RecordedChangeScenarios.record(network, scenario.backwardChange()));

        assertEquals(Set.copyOf(forwardDiff.forward()), Set.copyOf(backwardDiff.reverse()), scenario.name());
        assertEquals(Set.copyOf(forwardDiff.reverse()), Set.copyOf(backwardDiff.forward()), scenario.name());
        DifferenceModel inverted = forwardDiff.inverted(backwardDiff.header());
        assertEquals(Set.copyOf(backwardDiff.forward()), Set.copyOf(inverted.forward()), scenario.name());
        assertEquals(Set.copyOf(backwardDiff.reverse()), Set.copyOf(inverted.reverse()), scenario.name());
    }

    // Explicit values, one per kind of equipment

    @Test
    void loadActivePowerChange() {
        DifferenceModel model = diffOf(LOAD_DIR, n -> n.getLoad("EnergyConsumer").setP0(12.5),
                "load_EQ.xml", "load_SSH.xml");
        assertEquals("10", value(model.reverse(), "EnergyConsumer", "EnergyConsumer.p"));
        assertEquals("5", value(model.reverse(), "EnergyConsumer", "EnergyConsumer.q"));
        assertEquals("12.5", value(model.forward(), "EnergyConsumer", "EnergyConsumer.p"));
        assertEquals("5", value(model.forward(), "EnergyConsumer", "EnergyConsumer.q"));
    }

    @Test
    void switchOpening() {
        DifferenceModel model = diffOf(SWITCH_DIR, n -> n.getSwitch("Breaker").setOpen(true),
                "switch_EQ.xml", "switch_SSH.xml");
        assertEquals("false", value(model.reverse(), "Breaker", "Switch.open"));
        assertEquals("true", value(model.forward(), "Breaker", "Switch.open"));
    }

    @Test
    void branchModelledAsSwitchUsesTerminals() {
        DifferenceModel model = diffOf(SWITCH_DIR, n -> n.getSwitch("SeriesCompensator").setOpen(false),
                "switch_EQ.xml", "switch_SSH.xml");
        assertFalse(has(model.forward(), "SeriesCompensator", "Switch.open"));
        assertEquals("false", value(model.reverse(), "SeriesCompensator-T1", "ACDCTerminal.connected"));
        assertEquals("true", value(model.forward(), "SeriesCompensator-T1", "ACDCTerminal.connected"));
        assertEquals("true", value(model.forward(), "SeriesCompensator-T2", "ACDCTerminal.connected"));
    }

    /** A target that turns a machine into a motor changes its operating mode in both directions. */
    @Test
    void generatorTargetsAndOperatingMode() {
        DifferenceModel model = diffOf(GENERATOR_DIR, n -> n.getGenerator(SYNCHRONOUS_MACHINE).setTargetP(-50.0),
                "generator_EQ.xml", "generator_SSH.xml");
        assertEquals("-160", value(model.reverse(), SYNCHRONOUS_MACHINE, "RotatingMachine.p"));
        assertEquals("50", value(model.forward(), SYNCHRONOUS_MACHINE, "RotatingMachine.p"));
        assertEquals("SynchronousMachineOperatingMode.generator",
                value(model.reverse(), SYNCHRONOUS_MACHINE, "SynchronousMachine.operatingMode"));
        assertEquals("SynchronousMachineOperatingMode.motor",
                value(model.forward(), SYNCHRONOUS_MACHINE, "SynchronousMachine.operatingMode"));
    }

    @Test
    void generatorRegulationWritesMachineAndRegulatingControl() {
        DifferenceModel model = diffOf(GENERATOR_DIR,
                n -> n.getGenerator(SYNCHRONOUS_MACHINE).setVoltageRegulatorOn(false),
                "generator_EQ.xml", "generator_SSH.xml");
        assertEquals("true", value(model.reverse(), SYNCHRONOUS_MACHINE, "RegulatingCondEq.controlEnabled"));
        assertEquals("false", value(model.forward(), SYNCHRONOUS_MACHINE, "RegulatingCondEq.controlEnabled"));
        assertEquals("true", value(model.reverse(), "SynchronousMachine-RegulatingControl", "RegulatingControl.enabled"));
        assertEquals("false", value(model.forward(), "SynchronousMachine-RegulatingControl", "RegulatingControl.enabled"));
    }

    /**
     * A RegulatingControl is shared, so its reverse description has to hold the previous state of every user, not
     * only of the one that changed.
     */
    @Test
    void sharedRegulatingControlReverseUsesOldStateOfAllUsers() {
        Network network = readCgmesResources(SHUNT_DIR, "shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml");
        ShuntCompensator linear = network.getShuntCompensator("LinearShuntCompensator");
        ShuntCompensator nonLinear = network.getShuntCompensator("NonLinearShuntCompensator");
        nonLinear.setProperty(Conversion.PROPERTY_REGULATING_CONTROL,
                linear.getProperty(Conversion.PROPERTY_REGULATING_CONTROL));
        nonLinear.setTargetV(linear.getTargetV());
        linear.setVoltageRegulatorOn(true);
        nonLinear.setVoltageRegulatorOn(true);

        DifferenceModel model = diff(network,
                RecordedChangeScenarios.record(network, n -> linear.setVoltageRegulatorOn(false)));
        // The other shunt still regulates, so the combined state of the shared control is the same in both
        // directions and the control drops out of the difference entirely
        assertEquals(Set.of("LinearShuntCompensator"), subjects(model.forward()));
        assertEquals("true", value(model.reverse(), "LinearShuntCompensator", "RegulatingCondEq.controlEnabled"));
        assertEquals("false", value(model.forward(), "LinearShuntCompensator", "RegulatingCondEq.controlEnabled"));

        // With the other shunt not regulating, the reverse description of the control is the combined previous
        // state of both users, which is enabled although the other one is off
        Network second = readCgmesResources(SHUNT_DIR, "shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml");
        ShuntCompensator secondLinear = second.getShuntCompensator("LinearShuntCompensator");
        ShuntCompensator secondNonLinear = second.getShuntCompensator("NonLinearShuntCompensator");
        secondNonLinear.setProperty(Conversion.PROPERTY_REGULATING_CONTROL,
                secondLinear.getProperty(Conversion.PROPERTY_REGULATING_CONTROL));
        secondNonLinear.setTargetV(secondLinear.getTargetV());
        secondLinear.setVoltageRegulatorOn(true);
        secondNonLinear.setVoltageRegulatorOn(false);

        DifferenceModel alone = diff(second,
                RecordedChangeScenarios.record(second, n -> secondLinear.setVoltageRegulatorOn(false)));
        assertEquals("true", value(alone.reverse(), "LinearShuntCompensator-RegulatingControl", "RegulatingControl.enabled"));
        assertEquals("false", value(alone.forward(), "LinearShuntCompensator-RegulatingControl", "RegulatingControl.enabled"));
    }

    @Test
    void tapPositions() {
        DifferenceModel twoWindings = diffOf(TRANSFORMER_DIR,
                n -> n.getTwoWindingsTransformer("T2W").getPhaseTapChanger().setTapPosition(-1),
                "transformer_EQ.xml", "transformer_SSH.xml");
        assertEquals("-2", value(twoWindings.reverse(), "T2W-PhaseTapChanger", "TapChanger.step"));
        assertEquals("-1", value(twoWindings.forward(), "T2W-PhaseTapChanger", "TapChanger.step"));

        DifferenceModel threeWindings = diffOf(TRANSFORMER_DIR,
                n -> n.getThreeWindingsTransformer("T3W").getLeg2().getRatioTapChanger().setTapPosition(7),
                "transformer_EQ.xml", "transformer_SSH.xml");
        assertEquals("8", value(threeWindings.reverse(), "T3W-Winding2-RatioTapChanger", "TapChanger.step"));
        assertEquals("7", value(threeWindings.forward(), "T3W-Winding2-RatioTapChanger", "TapChanger.step"));
    }

    @Test
    void tapChangerRegulation() {
        Network network = readCgmesResources(TRANSFORMER_DIR, "transformer_EQ.xml", "transformer_SSH.xml");
        network.getTwoWindingsTransformer("T2W").getPhaseTapChanger().setRegulationValue(50.0).setTargetDeadband(0.5);
        DifferenceModel model = diff(network, RecordedChangeScenarios.record(network,
                n -> n.getTwoWindingsTransformer("T2W").getPhaseTapChanger().setRegulationValue(55.0)));
        assertEquals("50", value(model.reverse(), "T2W-PhaseTapChanger-Control", "RegulatingControl.targetValue"));
        assertEquals("55", value(model.forward(), "T2W-PhaseTapChanger-Control", "RegulatingControl.targetValue"));
        assertEquals("0.5", value(model.reverse(), "T2W-PhaseTapChanger-Control", "RegulatingControl.targetDeadband"));
    }

    @Test
    void shuntSectionsAndRegulation() {
        DifferenceModel sections = diffOf(SHUNT_DIR,
                n -> n.getShuntCompensator("NonLinearShuntCompensator").setSectionCount(2),
                "shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml");
        assertEquals("1", value(sections.reverse(), "NonLinearShuntCompensator", "ShuntCompensator.sections"));
        assertEquals("2", value(sections.forward(), "NonLinearShuntCompensator", "ShuntCompensator.sections"));

        DifferenceModel regulation = diffOf(SHUNT_DIR,
                n -> n.getShuntCompensator("LinearShuntCompensator").setVoltageRegulatorOn(true),
                "shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml");
        assertEquals("false", value(regulation.reverse(), "LinearShuntCompensator-RegulatingControl", "RegulatingControl.enabled"));
        assertEquals("true", value(regulation.forward(), "LinearShuntCompensator-RegulatingControl", "RegulatingControl.enabled"));
    }

    @Test
    void staticVarCompensatorSetpointAndRegulating() {
        DifferenceModel model = diffOf("/update/static-var-compensator/",
                n -> n.getStaticVarCompensator("StaticVarCompensator-V").setVoltageSetpoint(400.0),
                "staticVarCompensator_EQ.xml", "staticVarCompensator_SSH.xml");
        assertEquals("405", value(model.reverse(), "StaticVarCompensator-V-RegulatingControl", "RegulatingControl.targetValue"));
        assertEquals("400", value(model.forward(), "StaticVarCompensator-V-RegulatingControl", "RegulatingControl.targetValue"));
    }

    /** The inverter values are recomputed from the old setpoint, not copied from the new one. */
    @Test
    void hvdcSetpointChangesBothConverters() {
        DifferenceModel model = diffOf(HVDC_DIR,
                n -> n.getHvdcLine("DCLineSegment-Lcc").setActivePowerSetpoint(350.0),
                "hvdc_EQ.xml", "hvdc_SSH.xml");
        assertEquals("300", value(model.reverse(), "DCLineSegment-Lcc-CsConverter-2", "ACDCConverter.targetPpcc"));
        assertEquals("350", value(model.forward(), "DCLineSegment-Lcc-CsConverter-2", "ACDCConverter.targetPpcc"));
        // The inverter carries the DC voltage derived from the setpoint, which therefore differs in both directions
        assertNotEquals(value(model.reverse(), "DCLineSegment-Lcc-CsConverter-1", "ACDCConverter.targetUdc"),
                value(model.forward(), "DCLineSegment-Lcc-CsConverter-1", "ACDCConverter.targetUdc"));
    }

    @Test
    void hvdcConvertersModeSwap() {
        DifferenceModel model = diffOf(HVDC_DIR,
                n -> n.getHvdcLine("DCLineSegment-Lcc")
                        .setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER),
                "hvdc_EQ.xml", "hvdc_SSH.xml");
        assertEquals("CsOperatingModeKind.inverter",
                value(model.reverse(), "DCLineSegment-Lcc-CsConverter-1", "CsConverter.operatingMode"));
        assertEquals("CsOperatingModeKind.rectifier",
                value(model.forward(), "DCLineSegment-Lcc-CsConverter-1", "CsConverter.operatingMode"));
    }

    @Test
    void externalNetworkInjectionAndEquivalentInjection() {
        DifferenceModel eni = diffOf(GENERATOR_DIR,
                n -> n.getGenerator("ExternalNetworkInjection").setTargetP(45.0),
                "generator_EQ.xml", "generator_SSH.xml");
        assertEquals("0", value(eni.reverse(), "ExternalNetworkInjection", "ExternalNetworkInjection.p"));
        assertEquals("-45", value(eni.forward(), "ExternalNetworkInjection", "ExternalNetworkInjection.p"));

        DifferenceModel ei = diffOf(GENERATOR_DIR,
                n -> n.getGenerator("EquivalentInjection").setTargetP(-70.0),
                "generator_EQ.xml", "generator_SSH.xml");
        assertEquals("184", value(ei.reverse(), "EquivalentInjection", "EquivalentInjection.p"));
        assertEquals("70", value(ei.forward(), "EquivalentInjection", "EquivalentInjection.p"));
    }

    /** A DCSwitch has no open state in the SSH profile: it is carried by the connection status of its DC terminals. */
    @Test
    void dcSwitchUsesDcTerminals() {
        Network network = readCgmesResources(detailedDcModel(), DC_DIR, "mixed_bipole_EQ.xml", "mixed_bipole_SSH.xml");
        DifferenceModel model = diff(network,
                RecordedChangeScenarios.record(network, n -> n.getDcSwitch("DCSW_1_1").setOpen(false)));

        assertFalse(has(model.forward(), "DCSW_1_1", "Switch.open"));
        assertEquals("false", value(model.reverse(), "T_DCSW_1_1_1", "ACDCTerminal.connected"));
        assertEquals("true", value(model.forward(), "T_DCSW_1_1_1", "ACDCTerminal.connected"));
        assertEquals("true", value(model.forward(), "T_DCSW_1_1_2", "ACDCTerminal.connected"));
    }

    /**
     * The power factor of a line commutated converter is carried by {@code ACDCConverter.p} and {@code q}: the
     * reactive power of the rectifier is {@code p * sqrt(1 - pf^2) / pf} in both directions.
     */
    @Test
    void lccPowerFactor() {
        Network network = readCgmesResources(HVDC_DIR, "hvdc_EQ.xml", "hvdc_SSH.xml");
        LccConverterStation rectifier = (LccConverterStation) network.getHvdcLine("DCLineSegment-Lcc").getConverterStation2();
        rectifier.setPowerFactor(0.9f);
        DifferenceModel model = diff(network,
                RecordedChangeScenarios.record(network, n -> rectifier.setPowerFactor(0.95f)));

        String subject = "DCLineSegment-Lcc-CsConverter-2";
        assertEquals("300", value(model.reverse(), subject, "ACDCConverter.p"));
        assertEquals("300", value(model.forward(), subject, "ACDCConverter.p"));
        assertEquals(reactiveOf(300.0, 0.9), Double.parseDouble(value(model.reverse(), subject, "ACDCConverter.q")), 1e-3);
        assertEquals(reactiveOf(300.0, 0.95), Double.parseDouble(value(model.forward(), subject, "ACDCConverter.q")), 1e-3);
    }

    private static double reactiveOf(double p, double powerFactor) {
        return Math.abs(p * Math.sqrt((1 - powerFactor * powerFactor) / (powerFactor * powerFactor)));
    }

    /**
     * Switching a voltage source converter from voltage to reactive power control moves {@code qPccControl} and
     * carries the reactive target with the sign the import reads it back with.
     */
    @Test
    void vscSetpointsAndRegulationState() {
        Network network = readCgmesResources(HVDC_DIR, "hvdc_EQ.xml", "hvdc_SSH.xml");
        VscConverterStation converter = (VscConverterStation) network.getHvdcLine("DCLineSegment-Vsc").getConverterStation2();
        converter.setReactivePowerSetpoint(20.0);
        DifferenceModel model = diff(network, RecordedChangeScenarios.record(network,
                n -> converter.setVoltageRegulatorOn(false).setReactivePowerSetpoint(30.0)));

        String subject = "DCLineSegment-Vsc-VscConverter-2";
        assertEquals("VsQpccControlKind.voltagePcc", value(model.reverse(), subject, "VsConverter.qPccControl"));
        assertEquals("VsQpccControlKind.reactivePcc", value(model.forward(), subject, "VsConverter.qPccControl"));
        // The import reads the setpoint back as -terminalSign * targetQpcc, so the export negates it
        assertEquals("-20", value(model.reverse(), subject, "VsConverter.targetQpcc"));
        assertEquals("-30", value(model.forward(), subject, "VsConverter.targetQpcc"));
    }

    /**
     * The reference priority lives on the machine, the participation factor on the GeneratingUnit of that machine.
     * Both are extension attributes, and an absent reference priority is a priority of zero.
     */
    @Test
    void referencePriorityAndParticipationFactor() {
        Network network = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        DifferenceModel priority = diff(network, RecordedChangeScenarios.record(network,
                n -> ReferencePriority.set(n.getGenerator(SYNCHRONOUS_MACHINE), 3)));
        assertEquals("0", value(priority.reverse(), SYNCHRONOUS_MACHINE, "SynchronousMachine.referencePriority"));
        assertEquals("3", value(priority.forward(), SYNCHRONOUS_MACHINE, "SynchronousMachine.referencePriority"));

        Properties importParameters = new Properties();
        importParameters.put(CgmesImport.CREATE_ACTIVE_POWER_CONTROL_EXTENSION, "true");
        Network withExtension = readCgmesResources(importParameters, GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        DifferenceModel factor = diff(withExtension, RecordedChangeScenarios.record(withExtension,
                n -> n.getGenerator(SYNCHRONOUS_MACHINE).getExtension(ActivePowerControl.class).setParticipationFactor(0.75)));
        String unit = "SynchronousMachine-GeneratingUnit";
        assertEquals("0", value(factor.reverse(), unit, "GeneratingUnit.normalPF"));
        assertEquals("0.75", value(factor.forward(), unit, "GeneratingUnit.normalPF"));
    }

    /** A converter of the detailed DC model carries its own control mode and DC voltage target. */
    @Test
    void detailedDcConverters() {
        Network network = readCgmesResources(detailedDcModel(), DC_DIR, "mixed_bipole_EQ.xml", "mixed_bipole_SSH.xml");
        LineCommutatedConverter converter = network.getLineCommutatedConverter("CSC_1_1");
        converter.setTargetVdc(450.0).setControlMode(AcDcConverter.ControlMode.P_PCC);
        DifferenceModel model = diff(network, RecordedChangeScenarios.record(network,
                n -> converter.setTargetVdc(500.0).setControlMode(AcDcConverter.ControlMode.V_DC)));

        assertEquals("CsPpccControlKind.activePower", value(model.reverse(), "CSC_1_1", "CsConverter.pPccControl"));
        assertEquals("CsPpccControlKind.dcVoltage", value(model.forward(), "CSC_1_1", "CsConverter.pPccControl"));
        // While the converter controls its active power the DC voltage target has no property, it is zero
        assertEquals("0", value(model.reverse(), "CSC_1_1", "ACDCConverter.targetUdc"));
        assertEquals("500", value(model.forward(), "CSC_1_1", "ACDCConverter.targetUdc"));
    }

    private static Properties detailedDcModel() {
        Properties importParameters = new Properties();
        importParameters.put(CgmesImport.USE_DETAILED_DC_MODEL, "true");
        return importParameters;
    }

    @Test
    void boundaryLineOperatingValues() {
        DifferenceModel model = diffOf("/update/boundary-line/",
                n -> n.getBoundaryLine("ACLineSegment").setP0(310.5),
                "boundaryLine_EQ.xml", "boundaryLine_EQ_BD.xml", "boundaryLine_SSH.xml");
        assertEquals("284.5", value(model.reverse(), "ACLineSegment-EquivalentInjection", "EquivalentInjection.p"));
        assertEquals("310.5", value(model.forward(), "ACLineSegment-EquivalentInjection", "EquivalentInjection.p"));
    }

    // Granularity

    @Test
    void changedOnlyKeepsOnlyChangedAttributes() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> n.getLoad("EnergyConsumer").setP0(12.5));
        DifferenceModel model = diff(network, events, new ExportOptions().setGranularity(DiffGranularity.CHANGED_ONLY));
        assertEquals(1, model.forward().size());
        assertEquals(1, model.reverse().size());
        assertEquals("EnergyConsumer.p", model.forward().get(0).property());
    }

    @Test
    void fullObjectMinimizedEqualsChangedOnly() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> n.getLoad("EnergyConsumer").setP0(12.5));
        DifferenceModel full = diff(network, events);
        Network other = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> otherEvents = RecordedChangeScenarios.record(other, n -> n.getLoad("EnergyConsumer").setP0(12.5));
        DifferenceModel changedOnly = diff(other, otherEvents,
                new ExportOptions().setGranularity(DiffGranularity.CHANGED_ONLY));
        assertEquals(changedOnly.forward(), full.minimized().forward());
        assertEquals(changedOnly.reverse(), full.minimized().reverse());
    }

    // No-op compaction

    @Test
    void changeAndChangeBackYieldsAnEmptyDiff() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> {
            n.getLoad("EnergyConsumer").setP0(12.0);
            n.getLoad("EnergyConsumer").setP0(10.0);
        });
        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, events, new ExportOptions());
        assertTrue(result.differences().isEmpty());
        assertEquals(PartialSshExport.compactEvents(events), result.exportedEvents());
    }

    @Test
    void switchToggledTwiceYieldsAnEmptyDiff() {
        Network network = readCgmesResources(SWITCH_DIR, "switch_EQ.xml", "switch_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> {
            n.getSwitch("Breaker").setOpen(true);
            n.getSwitch("Breaker").setOpen(false);
        });
        assertTrue(CgmesDiffExport.toDifferences(network, events, new ExportOptions()).differences().isEmpty());
    }

    @Test
    void noOpOnOneObjectDoesNotHideChangesOnAnother() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> {
            n.getLoad("EnergyConsumer").setP0(12.0);
            n.getLoad("EnergyConsumer").setP0(10.0);
            n.getLoad("EnergySource").setP0(-201.0);
        });
        DifferenceModel model = diff(network, events);
        assertEquals(Set.of("EnergySource"), subjects(model.forward()));
        assertEquals(Set.of("EnergySource"), subjects(model.reverse()));
    }

    // Unsupported changes

    @Test
    void unsupportedChangeFailsByDefault() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network,
                n -> n.getLoad("EnergyConsumer").setFictitious(true));
        ExportOptions options = new ExportOptions();
        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> CgmesDiffExport.toDifferences(network, events, options));
        assertTrue(exception.getMessage().contains("a CGMES difference model"), exception.getMessage());
    }

    @Test
    void ignoredChangesAreLeftOutOfExportedEvents() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> {
            n.getLoad("EnergySource").setFictitious(true);
            n.getLoad("EnergyConsumer").setP0(12.5);
        });
        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, events,
                new ExportOptions().setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE));
        assertEquals(1, result.exportedEvents().size());
        assertEquals("p0", ((UpdateNetworkEvent) result.exportedEvents().get(0)).attribute());
    }

    /** A change that cannot be described leaves nothing behind, in either direction. */
    @Test
    void anUnexportableChangeLeavesNothingBehindInEitherDirection() {
        Network network = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = network.getGenerator("EquivalentInjection");
        generator.setTargetV(400.0);
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> generator.setVoltageRegulatorOn(true));

        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, events,
                new ExportOptions().setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE));
        assertTrue(result.differences().isEmpty());
        assertTrue(result.exportedEvents().isEmpty());
    }

    /** The values an extension carried before it existed are not recorded anywhere. */
    @Test
    void extensionCreationIsUnsupportedInADifferenceModel() {
        Properties importParameters = new Properties();
        importParameters.put(CgmesImport.CREATE_ACTIVE_POWER_CONTROL_EXTENSION, "true");
        Network network = readCgmesResources(importParameters, GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        // The partial SSH export accepts a created extension, because it only describes the state that follows it
        List<NetworkEvent> events = List.of(
                new ExtensionCreationNetworkEvent(SYNCHRONOUS_MACHINE, ActivePowerControl.NAME));
        assertTrue(PartialSshExport.toString(network, events, UnsupportedChangeBehavior.FAIL)
                .contains("GeneratingUnit.normalPF"));

        ExportOptions options = new ExportOptions();
        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> CgmesDiffExport.toDifferences(network, events, options));
        assertTrue(exception.getMessage().contains("was created by this change set"), exception.getMessage());
    }

    @Test
    void unrecordedOldValueIsUnsupported() {
        Network network = readCgmesResources(SHUNT_DIR, "shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml");
        List<NetworkEvent> events = List.of(new UpdateNetworkEvent("LinearShuntCompensator", "sectionCount",
                VariantManagerConstants.INITIAL_VARIANT_ID, null, 2));
        ExportOptions options = new ExportOptions();
        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> CgmesDiffExport.toDifferences(network, events, options));
        assertTrue(exception.getMessage().contains("was not recorded as a number"), exception.getMessage());

        // A second change of the same attribute must not turn the unrecorded previous value into the intermediate
        // one: the state the change set started from is still unknown
        List<NetworkEvent> twice = List.of(
                new UpdateNetworkEvent("LinearShuntCompensator", "sectionCount",
                        VariantManagerConstants.INITIAL_VARIANT_ID, null, 2),
                new UpdateNetworkEvent("LinearShuntCompensator", "sectionCount",
                        VariantManagerConstants.INITIAL_VARIANT_ID, 2, 1));
        PowsyblException stillUnsupported = assertThrows(PowsyblException.class,
                () -> CgmesDiffExport.toDifferences(network, twice, options));
        assertTrue(stillUnsupported.getMessage().contains("was not recorded as a number"), stillUnsupported.getMessage());
    }

    /**
     * A reference priority that did not exist is a priority of zero, and setting it twice in one change set must
     * still reverse to zero rather than to the value the first change wrote.
     */
    @Test
    void referencePrioritySetTwiceReversesToZero() {
        Network network = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = network.getGenerator(SYNCHRONOUS_MACHINE);
        assertEquals(0, ReferencePriority.get(generator));

        DifferenceModel model = diff(network, RecordedChangeScenarios.record(network, n -> {
            ReferencePriority.set(generator, 3);
            ReferencePriority.set(generator, 5);
        }));
        assertEquals("0", value(model.reverse(), SYNCHRONOUS_MACHINE, "SynchronousMachine.referencePriority"));
        assertEquals("5", value(model.forward(), SYNCHRONOUS_MACHINE, "SynchronousMachine.referencePriority"));
    }

    @Test
    void changeOfAnotherVariantIsRejected() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = List.of(new UpdateNetworkEvent("EnergyConsumer", "p0", "OtherVariant", 10.0, 12.5));
        ExportOptions options = new ExportOptions();
        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> CgmesDiffExport.toDifferences(network, events, options));
        assertTrue(exception.getMessage().contains("was recorded on variant OtherVariant"), exception.getMessage());
    }

    @Test
    void mergedNetworkIsRejected() {
        Network merged = Network.merge(Network.create("first", "test"), Network.create("second", "test"));
        ExportOptions options = new ExportOptions();
        List<NetworkEvent> none = List.of();
        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> CgmesDiffExport.toDifferences(merged, none, options));
        assertTrue(exception.getMessage().contains("A difference model describes a single"), exception.getMessage());
    }

    @Test
    void structuralChangeIsRejected() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = List.of(new com.powsybl.iidm.network.events.CreationNetworkEvent("EnergyConsumer"));
        ExportOptions options = new ExportOptions();
        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> CgmesDiffExport.toDifferences(network, events, options));
        assertTrue(exception.getMessage().contains("only attribute updates can be exported"), exception.getMessage());
    }

    // Header

    @Test
    void headerDefaultsSupersedeSourceModelAndIncrementVersion() {
        DifferenceModel model = diffOf(LOAD_DIR, n -> n.getLoad("EnergyConsumer").setP0(12.5),
                "load_EQ.xml", "load_SSH.xml");
        assertEquals(2, model.header().version());
        assertEquals(List.of("urn:uuid:d400c631-75a0-4c30-8aed-832b0d282e74"), model.header().supersedes());
        assertEquals(SSH, model.header().subset());
        assertEquals(CgmesNamespace.CIM_100_NAMESPACE, model.header().cimNamespace());
        assertTrue(model.header().id().startsWith("urn:uuid:"));
    }

    @Test
    void headerOptionsOverrideDefaults() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> n.getLoad("EnergyConsumer").setP0(12.5));
        ExportOptions options = new ExportOptions()
                .setScenarioTime(ZonedDateTime.parse("2030-01-02T03:04:05Z"))
                .setCreated(ZonedDateTime.parse("2031-01-02T03:04:05Z"));
        options.header(SSH)
                .setModelId("urn:uuid:11111111-1111-1111-1111-111111111111")
                .setDescription("a description")
                .setVersion(42)
                .setModelingAuthoritySet("https://example.com/")
                .clearDependencies()
                .addDependentOn("urn:uuid:dep")
                .setSupersedePreviousModel(false)
                .addSupersedes("urn:uuid:sup");

        DifferenceModel model = diff(network, events, options);
        assertEquals("urn:uuid:11111111-1111-1111-1111-111111111111", model.header().id());
        assertEquals("a description", model.header().description());
        assertEquals(42, model.header().version());
        assertEquals("https://example.com/", model.header().modelingAuthoritySet());
        assertEquals(List.of("urn:uuid:dep"), model.header().dependentOn());
        assertEquals(List.of("urn:uuid:sup"), model.header().supersedes());
        assertEquals(ZonedDateTime.parse("2030-01-02T03:04:05Z").toInstant(), model.header().scenarioTime().toInstant());
        assertEquals(ZonedDateTime.parse("2031-01-02T03:04:05Z").toInstant(), model.header().created().toInstant());
    }

    @Test
    void differenceModelIdDiffersFromPartialSshId() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> n.getLoad("EnergyConsumer").setP0(12.5));
        String diffId = diff(network, events).header().id();
        String sshXml = PartialSshExport.toString(network, events, UnsupportedChangeBehavior.FAIL);
        assertFalse(sshXml.contains(diffId), () -> "the difference model id " + diffId + " collides with the partial SSH id");
        // Two exports of the same changes still produce the same identifier
        assertEquals(diffId, diff(network, events).header().id());
    }

    @Test
    void chainAfterSupersedesPreviousDifference() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> first = RecordedChangeScenarios.record(network, n -> n.getLoad("EnergyConsumer").setP0(12.5));
        DifferenceModel firstDiff = diff(network, first);

        List<NetworkEvent> second = RecordedChangeScenarios.record(network, n -> n.getLoad("EnergyConsumer").setP0(14.0));
        ExportOptions options = new ExportOptions();
        options.header(SSH).chainAfter(firstDiff.header());
        DifferenceModel secondDiff = diff(network, second, options);

        assertEquals(List.of(firstDiff.header().id()), secondDiff.header().supersedes());
        assertEquals(firstDiff.header().version() + 1, secondDiff.header().version());
        assertNotEquals(firstDiff.header().id(), secondDiff.header().id());
    }

    // Output

    @Test
    void writeToPathStringAndStreamAgree() throws IOException {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> n.getLoad("EnergyConsumer").setP0(12.5));
        ZonedDateTime created = ZonedDateTime.parse("2026-09-17T08:00:00Z");

        String asString = CgmesDiffExport.toString(network, events, SSH, UnsupportedChangeBehavior.FAIL);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        CgmesDiffExport.write(network, events, stream, SSH, new ExportOptions().setCreated(created));
        Path file = tmpDir.resolve("diff_SSH_DIFF.xml");
        CgmesDiffExport.write(network, events, file, SSH, new ExportOptions().setCreated(created));

        assertEquals(stream.toString(StandardCharsets.UTF_8), Files.readString(file));
        // Only the creation time differs between the two, everything else has to be identical
        assertEquals(withoutCreated(asString), withoutCreated(stream.toString(StandardCharsets.UTF_8)));
    }

    private static String withoutCreated(String xml) {
        return xml.replaceAll("<md:Model.created>.*?</md:Model.created>", "");
    }

    @Test
    void dataSourceGetsOneFilePerTouchedProfile() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> n.getLoad("EnergyConsumer").setP0(12.5));
        MemDataSource dataSource = new MemDataSource();
        CgmesDiffExport.write(network, events, dataSource, "case", new ExportOptions());
        String written = new String(dataSource.getData("case_SSH_DIFF.xml"), StandardCharsets.UTF_8);
        DifferenceModelXmlAssert.assertIsADifferenceModel(written, CgmesNamespace.CIM_100_NAMESPACE);
    }

    @Test
    void zipDataSourceGetsOneEntryPerTouchedProfile() throws IOException {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> n.getLoad("EnergyConsumer").setP0(12.5));
        DataSource dataSource = new ZipArchiveDataSource(tmpDir, "case");
        CgmesDiffExport.write(network, events, dataSource, "case", new ExportOptions());

        assertTrue(dataSource.exists("case_SSH_DIFF.xml"));
        try (InputStream inputStream = dataSource.newInputStream("case_SSH_DIFF.xml")) {
            DifferenceModelXmlAssert.assertIsADifferenceModel(
                    new String(inputStream.readAllBytes(), StandardCharsets.UTF_8), CgmesNamespace.CIM_100_NAMESPACE);
        }
    }

    @Test
    void sinkReceivesEveryModel() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> n.getLoad("EnergyConsumer").setP0(12.5));
        List<DifferenceModel> received = new ArrayList<>();
        List<NetworkEvent> exported = CgmesDiffExport.export(network, events, received::add, new ExportOptions());
        assertEquals(1, received.size());
        assertEquals(SSH, received.get(0).header().subset());
        assertEquals(1, exported.size());
    }

    @Test
    void anEmptyChangeSetStillWritesADocument() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        String xml = CgmesDiffExport.toString(network, List.of(), SSH, UnsupportedChangeBehavior.FAIL);
        DifferenceModelXmlAssert.assertIsADifferenceModel(xml, CgmesNamespace.CIM_100_NAMESPACE);
        assertFalse(xml.contains("rdf:Description"));
    }

    @Test
    void writingAModelSetOfOneProfileToAStringIsTheSameDocument() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> n.getLoad("EnergyConsumer").setP0(12.5));
        ZonedDateTime created = ZonedDateTime.parse("2026-09-17T08:00:00Z");
        DifferenceModelSet set = CgmesDiffExport.toDifferences(network, events,
                new ExportOptions().setCreated(created)).differences();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        CgmesDiffExport.write(network, events, stream, SSH, new ExportOptions().setCreated(created));
        assertEquals(stream.toString(StandardCharsets.UTF_8),
                DifferenceModelWriter.toString(set.get(SSH).orElseThrow()));
    }

    /**
     * A profile the export does not write turns every change describing it into an unsupported change, rather than
     * silently leaving it out.
     */
    @Test
    void selectingAProfileTurnsOtherProfilesIntoUnsupportedChanges() {
        Network network = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> n.getLoad("EnergyConsumer").setP0(12.5));
        ExportOptions options = new ExportOptions().setSubsets(Set.of(CgmesSubset.EQUIPMENT));
        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> CgmesDiffExport.toDifferences(network, events, options));
        assertTrue(exception.getMessage().contains("belongs to the SSH profile"), exception.getMessage());

        CgmesDiffExport.Result ignored = CgmesDiffExport.toDifferences(network, events,
                new ExportOptions().setSubsets(Set.of(CgmesSubset.EQUIPMENT))
                        .setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE));
        assertTrue(ignored.differences().isEmpty());
        assertTrue(ignored.exportedEvents().isEmpty());
    }

    // Golden documents

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void differenceModelOfEveryScenarioIsUnchanged(Scenario scenario) throws IOException {
        Network network = scenario.load();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, scenario.forwardChange());
        assertEquals(golden(scenario.name(), document(network, events)), document(network, events));
    }

    @Test
    void differenceModelOfTheMicroGridIsUnchanged() throws IOException {
        Network network = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        List<NetworkEvent> events = microGridChanges(network);
        assertEquals(golden("microgrid-be-cim16", document(network, events)), document(network, events));
    }

    /** The document of a change set, with every value a reader could not reproduce pinned. */
    private static String document(Network network, List<NetworkEvent> events) {
        ExportOptions options = new ExportOptions()
                .setScenarioTime(ZonedDateTime.parse("2024-02-21T11:00:00Z"))
                .setCreated(ZonedDateTime.parse("2026-09-17T08:00:00Z"));
        options.header(SSH).setModelId("urn:uuid:00000000-0000-0000-0000-000000000001");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CgmesDiffExport.write(network, events, outputStream, SSH, options);
        return outputStream.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String golden(String name, String actual) throws IOException {
        String resource = "/diff-golden/" + name + "_SSH_DIFF.xml";
        if (Boolean.getBoolean("diffstacking.regenerate")) {
            Path path = Path.of("src", "test", "resources", "diff-golden", name + "_SSH_DIFF.xml");
            Files.createDirectories(path.getParent());
            Files.writeString(path, actual, StandardCharsets.UTF_8);
        }
        try (java.io.InputStream inputStream = CgmesDiffExportTest.class.getResourceAsStream(resource)) {
            assertTrue(inputStream != null, () -> "Missing golden file " + resource
                    + ", regenerate it with -Ddiffstacking.regenerate=true");
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    /** A conformity model exercises the naming, the class variety and the regulating control layout of a real grid. */
    @Test
    void microGridConformityModel() {
        Network network = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        String xml = CgmesDiffExport.toString(network, microGridChanges(network), SSH, UnsupportedChangeBehavior.FAIL);
        DifferenceModelXmlAssert.assertIsADifferenceModel(xml, CgmesNamespace.CIM_16_NAMESPACE);
    }

    /** One change of every kind the micro grid holds, in a fixed order. */
    private static List<NetworkEvent> microGridChanges(Network network) {
        return RecordedChangeScenarios.record(network, n -> {
            Load load = n.getLoadStream().findFirst().orElseThrow();
            load.setP0(load.getP0() + 2.5);
            Generator generator = n.getGeneratorStream()
                    .filter(g -> g.hasProperty(Conversion.PROPERTY_REGULATING_CONTROL))
                    .findFirst().orElseThrow();
            generator.setTargetP(generator.getTargetP() + 5.0).setTargetV(generator.getTargetV() + 1.0);
            ShuntCompensator shunt = n.getShuntCompensatorStream().findFirst().orElseThrow();
            shunt.setSectionCount(shunt.getSectionCount() == 0 ? 1 : 0);
            RatioTapChanger tapChanger = n.getTwoWindingsTransformerStream()
                    .filter(TwoWindingsTransformer::hasRatioTapChanger)
                    .findFirst().orElseThrow().getRatioTapChanger();
            tapChanger.setTapPosition(tapChanger.getTapPosition() + 1);
        });
    }
}
