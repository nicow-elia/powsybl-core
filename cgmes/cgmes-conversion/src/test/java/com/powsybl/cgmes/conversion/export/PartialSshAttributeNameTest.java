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
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulation;
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
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOCAL_TARGET_Q;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOCAL_TARGET_V;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.OPEN;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.P0;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.PARTICIPATION_FACTOR;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.PHASE_TAP_CHANGER_PREFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.POWER_FACTOR;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.Q0;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REFERENCE_PRIORITY;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REGULATING_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REGULATION_MODE_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REGULATION_VALUE_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.SECTION_COUNT;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TAP_POSITION_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_DEADBAND_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_P;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_Q;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_V;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_VDC;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VOLTAGE_REGULATION_ON;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_MODE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_REGULATING;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TARGET_DEADBAND;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TARGET_VALUE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TERMINAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the attribute names {@link CgmesChangeTranslator} recognises to the names the IIDM implementation
 * reports in its update events.
 *
 * <p>The names are plain strings scattered over the iidm module, so nothing but this test ties the two sides
 * together: a rename there would otherwise turn every change of that attribute into an unsupported one, failing
 * exports under {@code FAIL} and silently dropping the change under {@code IGNORE}.</p>
 *
 * <p>Since the voltage regulation refactoring of IIDM (powsybl-core #3699) a regulation reports its changes under
 * {@code VoltageRegulation.*} and {@code localTarget*}, and the deprecated setters report the same change once more
 * under their historical name: the canonical event first, then the echo that {@link LegacyRegulationKeys} maps or
 * drops. Both are pinned here, in that order.</p>
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
        VoltageRegulation regulation = generator.getVoltageRegulation();
        assertEquals(List.of(TARGET_P), attributesUpdatedBy(network, () -> generator.setTargetP(generator.getTargetP() + 1.0)));
        assertEquals(List.of(LOCAL_TARGET_Q), attributesUpdatedBy(network, () -> generator.setLocalTargetQ(generator.getLocalTargetQ() + 1.0)));
        assertEquals(List.of(LOCAL_TARGET_V), attributesUpdatedBy(network, () -> generator.setLocalTargetV(generator.getLocalTargetV() + 1.0)));
        assertEquals(List.of(VR_REGULATING), attributesUpdatedBy(network, () -> regulation.setRegulating(!regulation.isRegulating())));
        assertEquals(List.of(VR_TARGET_VALUE), attributesUpdatedBy(network, () -> regulation.setTargetValue(25.0)));
    }

    /** The deprecated setters of a generator: the canonical event, then the echo. {@code targetQ} is gone. */
    @Test
    @SuppressWarnings("removal")
    void generatorDeprecatedSetters() {
        Network network = EurostagTutorialExample1Factory.create();
        Generator generator = network.getGenerator("GEN");
        assertEquals(List.of(LOCAL_TARGET_Q), attributesUpdatedBy(network, () -> generator.setTargetQ(generator.getTargetQ() + 1.0)));
        // A local regulation: the local target and no echo
        assertEquals(List.of(LOCAL_TARGET_V), attributesUpdatedBy(network, () -> generator.setTargetV(generator.getTargetV() + 1.0)));
        assertEquals(List.of(VR_REGULATING, "voltageRegulatorOn"),
                attributesUpdatedBy(network, () -> generator.setVoltageRegulatorOn(!generator.isVoltageRegulatorOn())));
    }

    /**
     * The remaining echoes of plan 21 table 3.3 (review 21 finding m12): mode and terminal of a compensator, mode and
     * terminal of a ratio tap changer, the terminal of a generator, the setpoints of both VSC classes and the flag of a
     * detailed converter. The canonical event comes first.
     */
    @Test
    @SuppressWarnings("removal")
    void remainingEchoes() {
        Network svcNetwork = SvcTestCaseFactory.create();
        StaticVarCompensator svc = svcNetwork.getStaticVarCompensator("SVC2");
        svc.setLocalTargetQ(10.0);
        assertEquals(List.of(VR_MODE, "regulationMode"),
                attributesUpdatedBy(svcNetwork, () -> svc.setRegulationMode(RegulationMode.REACTIVE_POWER)));

        Network eurostag = EurostagTutorialExample1Factory.create();
        Generator generator = eurostag.getGenerator("GEN");
        // The bridge re-sets the target together with the terminal (VoltageRegulation.setTerminal(terminal, target))
        assertEquals(List.of(VR_TERMINAL, VR_TARGET_VALUE, "regulatingTerminal"), attributesUpdatedBy(eurostag,
                () -> generator.setRegulatingTerminal(eurostag.getLoad("LOAD").getTerminal())));
        RatioTapChanger ratioTapChanger = eurostag.getTwoWindingsTransformer(EurostagTutorialExample1Factory.NHV2_NLOAD)
                .getRatioTapChanger();
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + "." + VR_TERMINAL, RATIO_TAP_CHANGER_PREFIX + ".regulationTerminal"),
                attributesUpdatedBy(eurostag, () -> ratioTapChanger.setRegulationTerminal(eurostag.getLoad("LOAD").getTerminal())));
        ratioTapChanger.getVoltageRegulation().setRegulating(false);
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + "." + VR_MODE, RATIO_TAP_CHANGER_PREFIX + REGULATION_MODE_SUFFIX),
                attributesUpdatedBy(eurostag, () -> ratioTapChanger.setRegulationMode(RegulationMode.REACTIVE_POWER)));

        Network hvdc = HvdcTestNetwork.createVsc();
        VscConverterStation station = hvdc.getVscConverterStation("C1");
        assertEquals(List.of(LOCAL_TARGET_V, "voltageSetpoint"),
                attributesUpdatedBy(hvdc, () -> station.setVoltageSetpoint(station.getVoltageSetpoint() + 1.0)));
        assertEquals(List.of(LOCAL_TARGET_Q, "reactivePowerSetpoint"),
                attributesUpdatedBy(hvdc, () -> station.setReactivePowerSetpoint(12.0)));

        Network detailed = DcDetailedNetworkFactory.createVscSymmetricalMonopole();
        VoltageSourceConverter vsc = detailed.getVoltageSourceConverterStream().findFirst().orElseThrow();
        // This converter has NO VoltageRegulation: the bridge creates one, which IIDM does not report (gap G1, issue
        // draft voltage-regulation-creation-fires-no-event.md), and reports its own echo alone (review 21 round 2,
        // R2-M3). A converter that has a regulation reports the canonical events first, see below
        assertNull(vsc.getVoltageRegulation());
        assertEquals(List.of("voltageRegulatorOn"),
                attributesUpdatedBy(detailed, () -> vsc.setVoltageRegulatorOn(!vsc.isVoltageRegulatorOn())));
        assertNotNull(vsc.getVoltageRegulation());
        assertEquals(List.of(LOCAL_TARGET_V, "voltageSetpoint"),
                attributesUpdatedBy(detailed, () -> vsc.setVoltageSetpoint(vsc.getVoltageSetpoint() + 1.0)));
    }

    /**
     * A detailed voltage source converter that has a VoltageRegulation (regulating reactive power, as imported from
     * CGMES) reports the canonical events of the deprecated flag before its echo: the mode, then the flag (review 21
     * round 2, R2-M3).
     */
    @Test
    @SuppressWarnings("removal")
    void aDetailedConverterWithARegulationReportsTheCanonicalEventsOfTheFlag() {
        java.util.Properties parameters = new java.util.Properties();
        parameters.put(com.powsybl.cgmes.conversion.CgmesImport.USE_DETAILED_DC_MODEL, "true");
        Network detailed = com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources(parameters,
                "/issues/hvdc/", "mixed_bipole_EQ.xml", "mixed_bipole_SSH.xml");
        VoltageSourceConverter vsc = detailed.getVoltageSourceConverter("VSC_1_2");
        assertEquals(RegulationMode.REACTIVE_POWER, vsc.getVoltageRegulation().getMode());
        assertEquals(List.of("VoltageRegulation.RegulationMode REACTIVE_POWER->VOLTAGE",
                        "VoltageRegulation.isRegulating true->false", "voltageRegulatorOn true->false"),
                describedBy(detailed, () -> vsc.setVoltageRegulatorOn(false)));
    }

    /**
     * Upstream defect F3 (issue draft {@code generator-two-argument-target-v-reports-wrong-values.md}):
     * {@code Generator.setTargetV(v, local)} reports what it did with wrong old and new values. The export does not
     * depend on them (EventCompactor, rule 1; the translator reads the live state), this pins what IIDM reports.
     */
    @Test
    @SuppressWarnings("removal")
    void twoArgumentTargetVReportsWrongValues() {
        Network network = com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources("/update/generator/",
                "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = network.getGenerator("SynchronousMachine");
        assertEquals(405.0, generator.getLocalTargetV());

        // Remote regulation (remote target 410, local 405), local target unchanged: the second localTargetV event
        // carries the old REMOTE target as its old value, the echo the old LOCAL target
        generator.getVoltageRegulation().setTerminal(network.getGenerator("ExternalNetworkInjection").getTerminal(), 410.0);
        assertEquals(List.of("VoltageRegulation.TargetValue 410.0->411.0", "localTargetV 410.0->405.0",
                        "targetV 405.0->411.0"),
                describedBy(network, () -> generator.setTargetV(411.0, 405.0)));
        // Remote regulation, local target changed: the real change of the local target comes first
        assertEquals(List.of("localTargetV 405.0->407.0", "VoltageRegulation.TargetValue 411.0->412.0",
                        "localTargetV 411.0->407.0", "targetV 405.0->412.0"),
                describedBy(network, () -> generator.setTargetV(412.0, 407.0)));

        // Local regulation: the local target is set to v, and then reported once more as the equivalent local target,
        // a value it does not have
        Network local = com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources("/update/generator/",
                "generator_EQ.xml", "generator_SSH.xml");
        Generator localGenerator = local.getGenerator("SynchronousMachine");
        assertEquals(List.of("localTargetV 405.0->406.0", "localTargetV 405.0->300.0"),
                describedBy(local, () -> localGenerator.setTargetV(406.0, 300.0)));
        assertEquals(406.0, localGenerator.getLocalTargetV());
    }

    /** The update events a change causes, as {@code attribute old->new}. */
    private static List<String> describedBy(Network network, Runnable change) {
        return eventsRecordedBy(network, change).stream()
                .filter(UpdateNetworkEvent.class::isInstance)
                .map(UpdateNetworkEvent.class::cast)
                .map(event -> event.attribute() + " " + event.oldValue() + "->" + event.newValue())
                .toList();
    }

    /**
     * Creating a VoltageRegulation with a terminal (gap G1 of plan 21): the creation itself is not reported, the
     * terminal is (review 21 finding m18).
     */
    @Test
    void voltageRegulationCreationWithATerminal() {
        Network network = EurostagTutorialExample1Factory.create();
        Generator generator = network.getGenerator("GEN");
        generator.removeVoltageRegulation();
        assertEquals(List.of(VR_TERMINAL), attributesUpdatedBy(network, () -> generator.newVoltageRegulation()
                .withMode(RegulationMode.REACTIVE_POWER).withTargetValue(10.0)
                .withTerminal(network.getLoad("LOAD").getTerminal()).withRegulating(false).build()));
    }

    /** A generator regulating reactive power remotely: the RemoteReactivePowerControl extension of before #3699. */
    @Test
    void generatorRemoteReactivePowerRegulation() {
        Network network = EurostagTutorialExample1Factory.create();
        Generator generator = network.getGenerator("GEN");
        VoltageRegulation regulation = generator.newVoltageRegulation()
                .withMode(RegulationMode.REACTIVE_POWER)
                .withTargetValue(10.0)
                .withTerminal(network.getLoad("LOAD").getTerminal())
                .withRegulating(true)
                .build();
        assertEquals(List.of(VR_TARGET_VALUE), attributesUpdatedBy(network, () -> regulation.setTargetValue(20.0)));
        assertEquals(List.of(VR_REGULATING), attributesUpdatedBy(network, () -> regulation.setRegulating(false)));
    }

    /**
     * Creating or removing a VoltageRegulation is not reported, except for its terminal and target when a terminal
     * is given (gap G1 of plan 21): a regulation created after the recording started is exported from the live state
     * only.
     */
    @Test
    void voltageRegulationCreationFiresNoAttributeEvent() {
        Network network = EurostagTutorialExample1Factory.create();
        Generator generator = network.getGenerator("GEN");
        generator.removeVoltageRegulation();
        assertNull(generator.getVoltageRegulation());
        assertEquals(List.of(), attributesUpdatedBy(network, () -> generator.newVoltageRegulation()
                .withMode(RegulationMode.VOLTAGE).withRegulating(false).build()));
        assertNotNull(generator.getVoltageRegulation());
        assertEquals(List.of(), attributesUpdatedBy(network, generator::removeVoltageRegulation));
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

    /**
     * The regulation of a tap changer is named after the tap changer, exactly like its position. A ratio tap changer
     * regulates through its VoltageRegulation, whose attributes carry their own dotted suffix.
     */
    @Test
    void tapChangerRegulation() {
        Network withRatioTapChanger = EurostagTutorialExample1Factory.create();
        RatioTapChanger ratioTapChanger = withRatioTapChanger
                .getTwoWindingsTransformer(EurostagTutorialExample1Factory.NHV2_NLOAD).getRatioTapChanger();
        VoltageRegulation ratioRegulation = ratioTapChanger.getVoltageRegulation();
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + "." + VR_TARGET_DEADBAND),
                attributesUpdatedBy(withRatioTapChanger, () -> ratioRegulation.setTargetDeadband(1.0)));
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + "." + VR_TARGET_VALUE),
                attributesUpdatedBy(withRatioTapChanger, () -> ratioRegulation.setTargetValue(159.0)));
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + "." + VR_REGULATING),
                attributesUpdatedBy(withRatioTapChanger, () -> ratioRegulation.setRegulating(!ratioRegulation.isRegulating())));

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
        VoltageRegulation legRegulation = legRatioTapChanger.getVoltageRegulation();
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + "2." + VR_TARGET_DEADBAND),
                attributesUpdatedBy(threeWindings, () -> legRegulation.setTargetDeadband(3.0)));
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + "2." + VR_REGULATING),
                attributesUpdatedBy(threeWindings, () -> legRegulation.setRegulating(!legRegulation.isRegulating())));
    }

    /** The deprecated setters of a ratio tap changer: the canonical event, then the echo. */
    @Test
    @SuppressWarnings("removal")
    void ratioTapChangerDeprecatedSetters() {
        Network network = EurostagTutorialExample1Factory.create();
        RatioTapChanger ratioTapChanger = network
                .getTwoWindingsTransformer(EurostagTutorialExample1Factory.NHV2_NLOAD).getRatioTapChanger();
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + "." + VR_TARGET_DEADBAND, RATIO_TAP_CHANGER_PREFIX + TARGET_DEADBAND_SUFFIX),
                attributesUpdatedBy(network, () -> ratioTapChanger.setTargetDeadband(1.0)));
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + "." + VR_TARGET_VALUE, RATIO_TAP_CHANGER_PREFIX + REGULATION_VALUE_SUFFIX),
                attributesUpdatedBy(network, () -> ratioTapChanger.setRegulationValue(159.0)));
        assertEquals(List.of(RATIO_TAP_CHANGER_PREFIX + "." + VR_REGULATING, RATIO_TAP_CHANGER_PREFIX + REGULATING_SUFFIX),
                attributesUpdatedBy(network, () -> ratioTapChanger.setRegulating(!ratioTapChanger.isRegulating())));
    }

    @Test
    void shuntCompensatorOperatingValues() {
        Network network = ShuntTestCaseFactory.create();
        ShuntCompensator shunt = network.getShuntCompensator("SHUNT");
        VoltageRegulation regulation = shunt.getVoltageRegulation();
        assertEquals(List.of(SECTION_COUNT), attributesUpdatedBy(network, () -> shunt.setSectionCount(0)));
        assertEquals(List.of(VR_TARGET_VALUE), attributesUpdatedBy(network, () -> regulation.setTargetValue(regulation.getTargetValue() + 1.0)));
        assertEquals(List.of(VR_REGULATING), attributesUpdatedBy(network, () -> regulation.setRegulating(!regulation.isRegulating())));
        assertEquals(List.of(VR_TARGET_DEADBAND), attributesUpdatedBy(network, () -> regulation.setTargetDeadband(1.5)));
    }

    /** A shunt regulating its own terminal keeps its target locally (powsybl-core #3699, event added by plan 21 E5). */
    @Test
    void shuntCompensatorLocalTarget() {
        Network network = ShuntTestCaseFactory.create();
        ShuntCompensator shunt = network.getShuntCompensator("SHUNT");
        shunt.setLocalTargetV(404.0);
        shunt.getVoltageRegulation().setTerminal(null, Double.NaN);
        assertEquals(List.of(LOCAL_TARGET_V), attributesUpdatedBy(network, () -> shunt.setLocalTargetV(405.0)));
    }

    /** The deprecated setters of a shunt compensator: the canonical event, then the echo. */
    @Test
    @SuppressWarnings("removal")
    void shuntCompensatorDeprecatedSetters() {
        Network network = ShuntTestCaseFactory.create();
        ShuntCompensator shunt = network.getShuntCompensator("SHUNT");
        assertEquals(List.of(VR_TARGET_VALUE, TARGET_V), attributesUpdatedBy(network, () -> shunt.setTargetV(shunt.getTargetV() + 1.0)));
        assertEquals(List.of(VR_REGULATING, "voltageRegulatorOn"),
                attributesUpdatedBy(network, () -> shunt.setVoltageRegulatorOn(!shunt.isVoltageRegulatorOn())));
        assertEquals(List.of(VR_TARGET_DEADBAND, "targetDeadband"), attributesUpdatedBy(network, () -> shunt.setTargetDeadband(1.5)));
        shunt.setLocalTargetV(404.0);
        shunt.getVoltageRegulation().setTerminal(null, Double.NaN);
        assertEquals(List.of(LOCAL_TARGET_V, TARGET_V), attributesUpdatedBy(network, () -> shunt.setTargetV(406.0)));
    }

    @Test
    void staticVarCompensatorSetpoints() {
        Network network = SvcTestCaseFactory.create();
        StaticVarCompensator svc = network.getStaticVarCompensator("SVC2");
        assertEquals(List.of(LOCAL_TARGET_V), attributesUpdatedBy(network, () -> svc.setLocalTargetV(svc.getLocalTargetV() + 1.0)));
        assertEquals(List.of(LOCAL_TARGET_Q), attributesUpdatedBy(network, () -> svc.setLocalTargetQ(100.0)));
        VoltageRegulation regulation = svc.getVoltageRegulation();
        assertEquals(List.of(VR_REGULATING), attributesUpdatedBy(network, () -> regulation.setRegulating(!regulation.isRegulating())));
    }

    /** The deprecated setters of a static var compensator: the canonical event, then the echo. */
    @Test
    @SuppressWarnings("removal")
    void staticVarCompensatorDeprecatedSetters() {
        Network network = SvcTestCaseFactory.create();
        StaticVarCompensator svc = network.getStaticVarCompensator("SVC2");
        assertEquals(List.of(LOCAL_TARGET_V, "voltageSetpoint"),
                attributesUpdatedBy(network, () -> svc.setVoltageSetpoint(svc.getVoltageSetpoint() + 1.0)));
        assertEquals(List.of(LOCAL_TARGET_Q, "reactivePowerSetpoint"),
                attributesUpdatedBy(network, () -> svc.setReactivePowerSetpoint(100.0)));
        assertEquals(List.of(VR_REGULATING, "regulating"), attributesUpdatedBy(network, () -> svc.setRegulating(!svc.isRegulating())));
    }

    @Test
    void hvdcSetpoints() {
        Network network = HvdcTestNetwork.createVsc();
        assertEquals(List.of(ACTIVE_POWER_SETPOINT),
                attributesUpdatedBy(network, () -> network.getHvdcLine("L").setActivePowerSetpoint(290.0)));

        VscConverterStation voltageRegulating = network.getVscConverterStation("C1");
        assertEquals(List.of(LOCAL_TARGET_V),
                attributesUpdatedBy(network, () -> voltageRegulating.setLocalTargetV(voltageRegulating.getLocalTargetV() + 1.0)));

        VscConverterStation reactivePowerRegulating = network.getVscConverterStation("C2");
        assertEquals(List.of(LOCAL_TARGET_Q),
                attributesUpdatedBy(network, () -> reactivePowerRegulating.setLocalTargetQ(100.0)));
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

    /** The deprecated setter of a converter station switches the mode to voltage first (F5 of plan 21). */
    @Test
    @SuppressWarnings("removal")
    void vscVoltageRegulatorOn() {
        Network network = HvdcTestNetwork.createVsc();
        VscConverterStation converter = network.getVscConverterStation("C2");
        converter.setLocalTargetV(405.0);
        converter.setLocalTargetQ(0.0);
        VoltageRegulation regulation = converter.getVoltageRegulation();
        assertEquals(List.of(VR_MODE), attributesUpdatedBy(network, () -> regulation.setMode(RegulationMode.VOLTAGE)));
        assertEquals(List.of(VR_REGULATING, "voltageRegulatorOn"),
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
        assertEquals(List.of(LOCAL_TARGET_Q), attributesUpdatedBy(network, () -> vsc.setLocalTargetQ(5.0)));
        assertEquals(List.of(LOCAL_TARGET_V), attributesUpdatedBy(network, () -> vsc.setLocalTargetV(vsc.getLocalTargetV() + 1.0)));

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
