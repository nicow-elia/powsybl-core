/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.DcSwitch;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.LccConverterStation;
import com.powsybl.iidm.network.LineCommutatedConverter;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkEventRecorder;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.events.ExtensionUpdateNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ActivePowerControlAdder;
import com.powsybl.iidm.network.extensions.ReferencePriority;
import com.powsybl.iidm.network.extensions.RemoteReactivePowerControl;
import com.powsybl.iidm.network.extensions.RemoteReactivePowerControlAdder;
import com.powsybl.iidm.network.test.BoundaryLineNetworkFactory;
import com.powsybl.iidm.network.test.DcDetailedNetworkFactory;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.HvdcTestNetwork;
import com.powsybl.iidm.network.test.PhaseShifterTestCaseFactory;
import com.powsybl.iidm.network.test.ShuntTestCaseFactory;
import com.powsybl.iidm.network.test.SvcTestCaseFactory;
import com.powsybl.iidm.network.test.ThreeWindingsTransformerNetworkFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.ACTIVE_POWER_SETPOINT;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.CONTROL_MODE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.CONVERTERS_MODE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.OPEN;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.P0;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.PARTICIPATION_FACTOR;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.PHASE_TAP_CHANGER_PREFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.POWER_FACTOR;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.Q0;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REACTIVE_POWER_SETPOINT;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REFERENCE_PRIORITY;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REGULATING;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REGULATING_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REGULATION_MODE_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REGULATION_VALUE_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.RRPC_ENABLED;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.RRPC_TARGET_Q;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.SECTION_COUNT;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TAP_POSITION_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_DEADBAND;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_DEADBAND_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_P;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_Q;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_V;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_VDC;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VOLTAGE_REGULATION_ON;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VOLTAGE_REGULATOR_ON;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VOLTAGE_SETPOINT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the attribute names {@link CgmesChangeTranslator} recognises to the names the IIDM implementation
 * reports in its update events.
 *
 * <p>The names are plain strings scattered over the iidm module, so nothing but this test ties the two sides
 * together: a rename there would otherwise turn every change of that attribute into an unsupported one, failing
 * exports under {@code FAIL} and silently dropping the change under {@code IGNORE}.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class PartialSshAttributeNameTest {

    @Test
    void switchState() {
        Network network = HvdcTestNetwork.createVsc();
        Switch breaker = network.getSwitch("BK1");
        assertEquals(List.of(OPEN), attributesUpdatedBy(network, () -> breaker.setOpen(!breaker.isOpen())));
    }

    @Test
    void dcSwitchState() {
        Network network = DcDetailedNetworkFactory.createLccBipoleGroundReturn();
        DcSwitch dcSwitch = network.getDcSwitch("dcSwitchFrPosBypass");
        assertEquals(List.of(OPEN), attributesUpdatedBy(network, () -> dcSwitch.setOpen(!dcSwitch.isOpen())));
    }

    @Test
    void loadSetpoints() {
        Network network = EurostagTutorialExample1Factory.create();
        Load load = network.getLoad("LOAD");
        assertEquals(List.of(P0), attributesUpdatedBy(network, () -> load.setP0(load.getP0() + 1.0)));
        assertEquals(List.of(Q0), attributesUpdatedBy(network, () -> load.setQ0(load.getQ0() + 1.0)));
    }

    @Test
    void generatorTargetsAndRegulation() {
        Network network = EurostagTutorialExample1Factory.create();
        Generator generator = network.getGenerator("GEN");
        assertEquals(List.of(TARGET_P), attributesUpdatedBy(network, () -> generator.setTargetP(generator.getTargetP() + 1.0)));
        assertEquals(List.of(TARGET_Q), attributesUpdatedBy(network, () -> generator.setTargetQ(generator.getTargetQ() + 1.0)));
        assertEquals(List.of(TARGET_V), attributesUpdatedBy(network, () -> generator.setTargetV(generator.getTargetV() + 1.0)));
        assertEquals(List.of(VOLTAGE_REGULATOR_ON),
                attributesUpdatedBy(network, () -> generator.setVoltageRegulatorOn(!generator.isVoltageRegulatorOn())));
    }

    @Test
    void twoWindingsTransformerTapPositions() {
        Network withRatioTapChanger = EurostagTutorialExample1Factory.create();
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + TAP_POSITION_SUFFIX), attributesUpdatedBy(withRatioTapChanger,
                () -> withRatioTapChanger.getTwoWindingsTransformer(EurostagTutorialExample1Factory.NHV2_NLOAD).getRatioTapChanger().setTapPosition(2)));

        Network withPhaseTapChanger = PhaseShifterTestCaseFactory.create();
        assertEquals(List.of(PHASE_TAP_CHANGER_PREFIX + TAP_POSITION_SUFFIX), attributesUpdatedBy(withPhaseTapChanger,
                () -> withPhaseTapChanger.getTwoWindingsTransformer("PS1").getPhaseTapChanger().setTapPosition(2)));
    }

    /** The end number sits between the tap changer prefix and the position suffix, and only there. */
    @Test
    void threeWindingsTransformerTapPositions() {
        Network network = ThreeWindingsTransformerNetworkFactory.create();
        ThreeWindingsTransformer transformer = network.getThreeWindingsTransformer("3WT");
        transformer.getLeg1().newPhaseTapChanger()
                .setTapPosition(0)
                .setRegulating(false)
                .beginStep().setAlpha(0.0).endStep()
                .beginStep().setAlpha(5.0).endStep()
                .add();

        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + "2" + TAP_POSITION_SUFFIX),
                attributesUpdatedBy(network, () -> transformer.getLeg2().getRatioTapChanger().setTapPosition(1)));
        assertEquals(List.of(PHASE_TAP_CHANGER_PREFIX + "1" + TAP_POSITION_SUFFIX),
                attributesUpdatedBy(network, () -> transformer.getLeg1().getPhaseTapChanger().setTapPosition(1)));
    }

    /** The regulation of a tap changer is named after the tap changer, exactly like its position. */
    @Test
    void tapChangerRegulation() {
        Network withRatioTapChanger = EurostagTutorialExample1Factory.create();
        RatioTapChanger ratioTapChanger = withRatioTapChanger
                .getTwoWindingsTransformer(EurostagTutorialExample1Factory.NHV2_NLOAD).getRatioTapChanger();
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + TARGET_DEADBAND_SUFFIX),
                attributesUpdatedBy(withRatioTapChanger, () -> ratioTapChanger.setTargetDeadband(1.0)));
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + REGULATION_VALUE_SUFFIX),
                attributesUpdatedBy(withRatioTapChanger, () -> ratioTapChanger.setRegulationValue(159.0)));
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + REGULATING_SUFFIX),
                attributesUpdatedBy(withRatioTapChanger, () -> ratioTapChanger.setRegulating(!ratioTapChanger.isRegulating())));

        Network withPhaseTapChanger = PhaseShifterTestCaseFactory.create();
        PhaseTapChanger phaseTapChanger = withPhaseTapChanger.getTwoWindingsTransformer("PS1").getPhaseTapChanger();
        assertEquals(List.of(PHASE_TAP_CHANGER_PREFIX + TARGET_DEADBAND_SUFFIX),
                attributesUpdatedBy(withPhaseTapChanger, () -> phaseTapChanger.setTargetDeadband(2.0)));
        assertEquals(List.of(PHASE_TAP_CHANGER_PREFIX + REGULATION_VALUE_SUFFIX),
                attributesUpdatedBy(withPhaseTapChanger, () -> phaseTapChanger.setRegulationValue(180.0)));
        assertEquals(List.of(PHASE_TAP_CHANGER_PREFIX + REGULATION_MODE_SUFFIX),
                attributesUpdatedBy(withPhaseTapChanger, () -> phaseTapChanger.setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)));
        assertEquals(List.of(PHASE_TAP_CHANGER_PREFIX + REGULATING_SUFFIX),
                attributesUpdatedBy(withPhaseTapChanger, () -> phaseTapChanger.setRegulating(!phaseTapChanger.isRegulating())));

        Network threeWindings = ThreeWindingsTransformerNetworkFactory.create();
        RatioTapChanger legRatioTapChanger = threeWindings.getThreeWindingsTransformer("3WT").getLeg2().getRatioTapChanger();
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + "2" + TARGET_DEADBAND_SUFFIX),
                attributesUpdatedBy(threeWindings, () -> legRatioTapChanger.setTargetDeadband(3.0)));
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + "2" + REGULATING_SUFFIX),
                attributesUpdatedBy(threeWindings, () -> legRatioTapChanger.setRegulating(!legRatioTapChanger.isRegulating())));
    }

    @Test
    void shuntCompensatorOperatingValues() {
        Network network = ShuntTestCaseFactory.create();
        ShuntCompensator shunt = network.getShuntCompensator("SHUNT");
        assertEquals(List.of(SECTION_COUNT), attributesUpdatedBy(network, () -> shunt.setSectionCount(0)));
        assertEquals(List.of(TARGET_V), attributesUpdatedBy(network, () -> shunt.setTargetV(shunt.getTargetV() + 1.0)));
        assertEquals(List.of(VOLTAGE_REGULATOR_ON),
                attributesUpdatedBy(network, () -> shunt.setVoltageRegulatorOn(!shunt.isVoltageRegulatorOn())));
    }

    @Test
    void shuntTargetDeadband() {
        Network network = ShuntTestCaseFactory.create();
        ShuntCompensator shunt = network.getShuntCompensator("SHUNT");
        assertEquals(List.of(TARGET_DEADBAND), attributesUpdatedBy(network, () -> shunt.setTargetDeadband(1.5)));
    }

    @Test
    void staticVarCompensatorSetpoints() {
        Network network = SvcTestCaseFactory.create();
        StaticVarCompensator svc = network.getStaticVarCompensator("SVC2");
        assertEquals(List.of(VOLTAGE_SETPOINT), attributesUpdatedBy(network, () -> svc.setVoltageSetpoint(svc.getVoltageSetpoint() + 1.0)));
        assertEquals(List.of(REACTIVE_POWER_SETPOINT), attributesUpdatedBy(network, () -> svc.setReactivePowerSetpoint(100.0)));
    }

    @Test
    void staticVarCompensatorRegulating() {
        Network network = SvcTestCaseFactory.create();
        StaticVarCompensator svc = network.getStaticVarCompensator("SVC2");
        assertEquals(List.of(REGULATING), attributesUpdatedBy(network, () -> svc.setRegulating(!svc.isRegulating())));
    }

    @Test
    void hvdcSetpoints() {
        Network network = HvdcTestNetwork.createVsc();
        assertEquals(List.of(ACTIVE_POWER_SETPOINT),
                attributesUpdatedBy(network, () -> network.getHvdcLine("L").setActivePowerSetpoint(290.0)));

        VscConverterStation voltageRegulating = network.getVscConverterStation("C1");
        assertEquals(List.of(VOLTAGE_SETPOINT),
                attributesUpdatedBy(network, () -> voltageRegulating.setVoltageSetpoint(voltageRegulating.getVoltageSetpoint() + 1.0)));

        VscConverterStation reactivePowerRegulating = network.getVscConverterStation("C2");
        assertEquals(List.of(REACTIVE_POWER_SETPOINT),
                attributesUpdatedBy(network, () -> reactivePowerRegulating.setReactivePowerSetpoint(100.0)));
    }

    @Test
    void hvdcConvertersModeAndPowerFactor() {
        Network network = HvdcTestNetwork.createLcc();
        HvdcLine line = network.getHvdcLine("L");
        assertEquals(List.of(CONVERTERS_MODE), attributesUpdatedBy(network,
                () -> line.setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER)));

        LccConverterStation converter = network.getLccConverterStation("C1");
        assertEquals(List.of(POWER_FACTOR), attributesUpdatedBy(network, () -> converter.setPowerFactor(0.7f)));
    }

    @Test
    void vscVoltageRegulatorOn() {
        Network network = HvdcTestNetwork.createVsc();
        VscConverterStation converter = network.getVscConverterStation("C2");
        converter.setVoltageSetpoint(405.0);
        assertEquals(List.of(VOLTAGE_REGULATOR_ON),
                attributesUpdatedBy(network, () -> converter.setVoltageRegulatorOn(!converter.isVoltageRegulatorOn())));
    }

    /**
     * A boundary line carries the injection at the boundary and, when it has a generation part, its targets. The
     * generation spells the regulation flag differently from every other regulating equipment of IIDM.
     */
    @Test
    void boundaryLineOperatingValues() {
        Network network = BoundaryLineNetworkFactory.createWithGeneration();
        BoundaryLine boundaryLine = network.getBoundaryLine("BL");
        assertEquals(List.of(P0), attributesUpdatedBy(network, () -> boundaryLine.setP0(boundaryLine.getP0() + 1.0)));
        assertEquals(List.of(Q0), attributesUpdatedBy(network, () -> boundaryLine.setQ0(boundaryLine.getQ0() + 1.0)));

        BoundaryLine.Generation generation = boundaryLine.getGeneration();
        assertEquals(List.of(TARGET_P), attributesUpdatedBy(network, () -> generation.setTargetP(generation.getTargetP() + 1.0)));
        assertEquals(List.of(TARGET_Q), attributesUpdatedBy(network, () -> generation.setTargetQ(15.0)));
        assertEquals(List.of(TARGET_V), attributesUpdatedBy(network, () -> generation.setTargetV(generation.getTargetV() + 1.0)));
        assertEquals(List.of(VOLTAGE_REGULATION_ON), attributesUpdatedBy(network,
                () -> generation.setVoltageRegulationOn(!generation.isVoltageRegulationOn())));
    }

    /** The converters of the detailed DC model carry their own control mode and setpoints. */
    @Test
    void detailedDcConverters() {
        Network network = DcDetailedNetworkFactory.createVscSymmetricalMonopole();
        VoltageSourceConverter vsc = network.getVoltageSourceConverterStream().findFirst().orElseThrow();
        AcDcConverter.ControlMode otherMode = vsc.getControlMode() == AcDcConverter.ControlMode.V_DC
                ? AcDcConverter.ControlMode.P_PCC : AcDcConverter.ControlMode.V_DC;
        assertEquals(List.of(TARGET_VDC), attributesUpdatedBy(network, () -> vsc.setTargetVdc(vsc.getTargetVdc() + 1.0)));
        assertEquals(List.of(TARGET_P), attributesUpdatedBy(network, () -> vsc.setTargetP(1.0)));
        assertEquals(List.of(CONTROL_MODE), attributesUpdatedBy(network, () -> vsc.setControlMode(otherMode)));
        assertEquals(List.of(VOLTAGE_REGULATOR_ON),
                attributesUpdatedBy(network, () -> vsc.setVoltageRegulatorOn(!vsc.isVoltageRegulatorOn())));

        Network lccNetwork = DcDetailedNetworkFactory.createLccBipoleGroundReturn();
        LineCommutatedConverter lcc = lccNetwork.getLineCommutatedConverterStream().findFirst().orElseThrow();
        assertEquals(List.of(POWER_FACTOR), attributesUpdatedBy(lccNetwork, () -> lcc.setPowerFactor(0.85)));
    }

    /**
     * The extensions the exporter reads report their changes under the same names their getters carry. Unlike a
     * change of the equipment itself, these arrive as extension update events.
     */
    @Test
    void extensionAttributes() {
        Network network = EurostagTutorialExample1Factory.create();
        Generator generator = network.getGenerator("GEN");
        generator.newExtension(ActivePowerControlAdder.class)
                .withParticipate(true)
                .withDroop(4.0)
                .withParticipationFactor(1.0)
                .add();
        ActivePowerControl<Generator> activePowerControl = generator.getExtension(ActivePowerControl.class);
        assertEquals(List.of(PARTICIPATION_FACTOR),
                extensionAttributesUpdatedBy(network, () -> activePowerControl.setParticipationFactor(2.0)));

        RemoteReactivePowerControl reactivePowerControl = generator.newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(10.0)
                .withRegulatingTerminal(network.getLoad("LOAD").getTerminal())
                .withEnabled(true)
                .add();
        assertEquals(List.of(RRPC_TARGET_Q),
                extensionAttributesUpdatedBy(network, () -> reactivePowerControl.setTargetQ(20.0)));
        assertEquals(List.of(RRPC_ENABLED),
                extensionAttributesUpdatedBy(network, () -> reactivePowerControl.setEnabled(false)));

        ReferencePriority.set(generator, 1);
        assertEquals(List.of(REFERENCE_PRIORITY),
                extensionAttributesUpdatedBy(network, () -> ReferencePriority.set(generator, 2)));
    }

    /** The attribute names of the update events that the given change causes, in the order they were reported. */
    private static List<String> attributesUpdatedBy(Network network, Runnable change) {
        return eventsRecordedBy(network, change).stream()
                .filter(UpdateNetworkEvent.class::isInstance)
                .map(UpdateNetworkEvent.class::cast)
                .map(UpdateNetworkEvent::attribute)
                .toList();
    }

    /** The attribute names of the extension update events that the given change causes. */
    private static List<String> extensionAttributesUpdatedBy(Network network, Runnable change) {
        return eventsRecordedBy(network, change).stream()
                .filter(ExtensionUpdateNetworkEvent.class::isInstance)
                .map(ExtensionUpdateNetworkEvent.class::cast)
                .map(ExtensionUpdateNetworkEvent::attribute)
                .toList();
    }

    private static List<NetworkEvent> eventsRecordedBy(Network network, Runnable change) {
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        network.addListener(recorder);
        try {
            change.run();
        } finally {
            network.removeListener(recorder);
        }
        return List.copyOf(recorder.getEvents());
    }
}
