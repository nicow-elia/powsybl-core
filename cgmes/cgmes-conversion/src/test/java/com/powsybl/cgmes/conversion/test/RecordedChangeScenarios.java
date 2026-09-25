/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.ActivePowerLimits;
import com.powsybl.iidm.network.ApparentPowerLimits;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.CurrentLimits;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.LccConverterStation;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkEventRecorder;
import com.powsybl.iidm.network.OperationalLimitsGroup;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TieLine;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ReferencePriority;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;

/**
 * The recorded change sets both change exporters are characterized against, one per supported kind of equipment.
 *
 * <p>Every scenario is a fixture, a change applied to it and the change that takes the fixture back to where it
 * started. That second half is what makes a scenario usable for a difference model: the forward difference of the
 * change and the reverse difference of its undo describe the very same state, so comparing them validates the
 * previous values a difference export reconstructs from the change log against values read live from a network that
 * really is in that state.</p>
 *
 * <p>The changes are taken from the round trip tests of {@code PartialSshExportTest}, which is where the list of
 * supported changes is maintained. Where the value a fixture starts from is not obvious from its file, a scenario
 * pins it in {@link Scenario#prepare()}, which runs before the recorder is attached and is therefore part of the
 * common base model rather than part of the change.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class RecordedChangeScenarios {

    private static final String SWITCH_DIR = "/update/switch/";
    private static final String LOAD_DIR = "/update/load/";
    private static final String GENERATOR_DIR = "/update/generator/";
    private static final String TRANSFORMER_DIR = "/update/transformer/";
    private static final String SHUNT_DIR = "/update/shunt-compensator/";
    private static final String STATIC_VAR_COMPENSATOR_DIR = "/update/static-var-compensator/";
    private static final String HVDC_DIR = "/update/hvdc/";
    private static final String BOUNDARY_LINE_DIR = "/update/boundary-line/";
    private static final String DC_DIR = "/issues/hvdc/";
    private static final String LINE_DIR = "/update/line/";
    private static final String VOLTAGE_LEVEL_DIR = "/update/voltage-level/";
    private static final String TIE_LINE_DIR = "/update/tie-line/";
    private static final String OPERATIONAL_LIMITS_DIR = "/issues/operational-limits/";

    private static final String[] SWITCH_FILES = {"switch_EQ.xml", "switch_SSH.xml"};
    private static final String[] LOAD_FILES = {"load_EQ.xml", "load_SSH.xml"};
    private static final String[] GENERATOR_FILES = {"generator_EQ.xml", "generator_SSH.xml"};
    private static final String[] TRANSFORMER_FILES = {"transformer_EQ.xml", "transformer_SSH.xml"};
    private static final String[] SHUNT_FILES = {"shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml"};
    private static final String[] SVC_FILES = {"staticVarCompensator_EQ.xml", "staticVarCompensator_SSH.xml"};
    private static final String[] HVDC_FILES = {"hvdc_EQ.xml", "hvdc_SSH.xml"};
    private static final String[] BOUNDARY_LINE_FILES = {"boundaryLine_EQ.xml", "boundaryLine_EQ_BD.xml", "boundaryLine_SSH.xml"};
    private static final String[] DC_FILES = {"mixed_bipole_EQ.xml", "mixed_bipole_SSH.xml"};
    private static final String[] LINE_FILES = {"line_EQ.xml", "line_SSH.xml"};
    private static final String[] VOLTAGE_LEVEL_FILES = {"voltageLevel_EQ.xml", "voltageLevel_SSH.xml"};
    private static final String[] TIE_LINE_FILES = {"tieLine_EQ.xml", "tieLine_EQ_BD.xml", "tieLine_SSH.xml"};
    private static final String[] LOADING_LIMITS_FILES = {"loading_limits.xml"};
    private static final String[] EQUIPMENT_LIMITS_FILES =
        {"limitsets_associated_to_equipments_EQ.xml", "limitsets_EQBD.xml", "limitsets_TPBD.xml"};
    private static final String[] VOLTAGE_LIMITS_FILES = {"voltage_limits.xml"};

    private static final String AC_LINE_SEGMENT = "ACLineSegment";

    private static final String SYNCHRONOUS_MACHINE = "SynchronousMachine";
    private static final String LINEAR_SHUNT = "LinearShuntCompensator";
    private static final String LCC_LINE = "DCLineSegment-Lcc";
    private static final String VSC_LINE = "DCLineSegment-Vsc";
    private static final String T2W = "T2W";
    private static final String T3W = "T3W";

    private RecordedChangeScenarios() {
    }

    /**
     * One characterized change.
     *
     * @param name           the identifier of the scenario, used as the name of its golden files
     * @param importParams   the import parameters the fixture has to be read with
     * @param dir            the resource directory of the fixture
     * @param files          the instance files of the fixture
     * @param prepare        a state the fixture is brought into before the recorder is attached, so that the value
     *                       the change starts from is pinned by this class rather than by the fixture file
     * @param forwardChange  the change under test
     * @param backwardChange the change that takes the network back to the state {@code forwardChange} started from
     */
    public record Scenario(String name, Properties importParams, String dir, String[] files,
                           Consumer<Network> prepare, Consumer<Network> forwardChange,
                           Consumer<Network> backwardChange) {

        /** A fresh copy of the fixture, prepared but unchanged. */
        public Network load() {
            Network network = readCgmesResources(importParams, dir, files);
            prepare.accept(network);
            return network;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Attach a recorder, apply the change, detach it again, and return what was recorded. */
    public static List<NetworkEvent> record(Network network, Consumer<Network> change) {
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        network.addListener(recorder);
        try {
            change.accept(network);
        } finally {
            network.removeListener(recorder);
        }
        return List.copyOf(recorder.getEvents());
    }

    private static Properties detailedDcModel() {
        Properties importParameters = new Properties();
        importParameters.put(CgmesImport.USE_DETAILED_DC_MODEL, "true");
        return importParameters;
    }

    private static Properties activePowerControlExtension() {
        Properties importParameters = new Properties();
        importParameters.put(CgmesImport.CREATE_ACTIVE_POWER_CONTROL_EXTENSION, "true");
        return importParameters;
    }

    private static final Consumer<Network> NOTHING = network -> { };

    private static Scenario scenario(String name, String dir, String[] files,
                                     Consumer<Network> forward, Consumer<Network> backward) {
        return new Scenario(name, new Properties(), dir, files, NOTHING, forward, backward);
    }

    private static Scenario scenario(String name, String dir, String[] files, Consumer<Network> prepare,
                                     Consumer<Network> forward, Consumer<Network> backward) {
        return new Scenario(name, new Properties(), dir, files, prepare, forward, backward);
    }

    private static Scenario scenario(String name, Properties importParams, String dir, String[] files,
                                     Consumer<Network> prepare, Consumer<Network> forward, Consumer<Network> backward) {
        return new Scenario(name, importParams, dir, files, prepare, forward, backward);
    }

    private static VscConverterStation vsc(Network network, int side) {
        HvdcLine line = network.getHvdcLine(VSC_LINE);
        return (VscConverterStation) (side == 1 ? line.getConverterStation1() : line.getConverterStation2());
    }

    private static LccConverterStation lcc(Network network, int side) {
        HvdcLine line = network.getHvdcLine(LCC_LINE);
        return (LccConverterStation) (side == 1 ? line.getConverterStation1() : line.getConverterStation2());
    }

    private static BoundaryLine.Generation generation(Network network) {
        return network.getBoundaryLine("EquivalentBranch").getGeneration();
    }

    // Operational limits, voltage limits and impedances (work package 5)

    private static CurrentLimits currentLimits(Network network, int side) {
        Line line = network.getLine(AC_LINE_SEGMENT);
        return (side == 1 ? line.getCurrentLimits1() : line.getCurrentLimits2()).orElseThrow();
    }

    /** The apparent power limits of the side of the two windings transformer that has some. */
    private static ApparentPowerLimits transformerApparentPowerLimits(Network network) {
        TwoWindingsTransformer transformer = network.getTwoWindingsTransformers().iterator().next();
        return transformer.getApparentPowerLimits2().or(transformer::getApparentPowerLimits1).orElseThrow();
    }

    private static ApparentPowerLimits threeWindingsApparentPowerLimits(Network network) {
        ThreeWindingsTransformer transformer = network.getThreeWindingsTransformers().iterator().next();
        return transformer.getLegStream()
                .map(leg -> leg.getApparentPowerLimits().orElse(null))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
    }

    private static ActivePowerLimits boundaryLineActivePowerLimits(Network network) {
        return network.getBoundaryLineStream()
                .map(boundaryLine -> boundaryLine.getActivePowerLimits().orElse(null))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
    }

    private static ActivePowerLimits tieLineHalfLimits(Network network, int half) {
        TieLine tieLine = network.getTieLines().iterator().next();
        return (half == 1 ? tieLine.getBoundaryLine1() : tieLine.getBoundaryLine2())
                .getActivePowerLimits().orElseThrow();
    }

    /** Replace the whole set of current limits of side 1 of the line, keeping its structure and its names. */
    private static void replaceCurrentLimits(Network network, double permanentLimit, double temporaryLimit) {
        OperationalLimitsGroup group = network.getLine(AC_LINE_SEGMENT).getOperationalLimitsGroups1().iterator().next();
        String name = group.getCurrentLimits().orElseThrow().getTemporaryLimit(900).getName();
        group.newCurrentLimits()
                .setPermanentLimit(permanentLimit)
                .beginTemporaryLimit().setAcceptableDuration(900).setName(name).setValue(temporaryLimit)
                .endTemporaryLimit()
                .add();
    }

    private static Line limitedLine(Network network) {
        return network.getLineStream().filter(line -> line.getCurrentLimits1().isPresent()).findFirst().orElseThrow();
    }

    private static BoundaryLine impedanceBoundaryLine(Network network) {
        return network.getBoundaryLine(AC_LINE_SEGMENT);
    }

    /**
     * The scenarios of the equipment profile: operational limits of a CGMES 2.4.15 model, branch impedances and the
     * voltage level limits of a voltage level that has no {@code VoltageLimit} objects. They are kept apart from
     * {@link #all()} because a partial steady state hypothesis file cannot carry any of them, so the characterization
     * nets of the steady state exporter would have nothing to write for them.
     */
    public static List<Scenario> equipmentChanges() {
        List<Scenario> scenarios = new ArrayList<>();

        // Line impedances
        scenarios.add(scenario("lineResistance", LINE_DIR, LINE_FILES,
                n -> n.getLine(AC_LINE_SEGMENT).setR(1.5),
                n -> n.getLine(AC_LINE_SEGMENT).setR(1.6),
                n -> n.getLine(AC_LINE_SEGMENT).setR(1.5)));
        scenarios.add(scenario("lineAllImpedances", LINE_DIR, LINE_FILES,
                n -> n.getLine(AC_LINE_SEGMENT).setR(1.5).setX(18.35).setG1(0.0).setG2(0.0)
                        .setB1(0.000455).setB2(0.000455),
                n -> n.getLine(AC_LINE_SEGMENT).setR(1.6).setX(18.5).setG1(1e-7).setG2(1e-7)
                        .setB1(0.00091).setB2(0.00091),
                n -> n.getLine(AC_LINE_SEGMENT).setR(1.5).setX(18.35).setG1(0.0).setG2(0.0)
                        .setB1(0.000455).setB2(0.000455)));
        scenarios.add(scenario("seriesCompensatorReactance", LINE_DIR, LINE_FILES,
                n -> n.getLine("SeriesCompensator").setX(10.0),
                n -> n.getLine("SeriesCompensator").setX(11.0),
                n -> n.getLine("SeriesCompensator").setX(10.0)));
        scenarios.add(scenario("equivalentBranchImpedance", LINE_DIR, LINE_FILES,
                n -> n.getLine("EquivalentBranch").setR(0.5).setX(5.0),
                n -> n.getLine("EquivalentBranch").setR(0.6).setX(5.5),
                n -> n.getLine("EquivalentBranch").setR(0.5).setX(5.0)));
        scenarios.add(scenario("boundaryLineImpedance", BOUNDARY_LINE_DIR, BOUNDARY_LINE_FILES,
                n -> impedanceBoundaryLine(n).setR(1.0).setX(10.0).setG(0.0).setB(0.0001),
                n -> impedanceBoundaryLine(n).setR(1.1).setX(10.5).setG(1e-7).setB(0.0002),
                n -> impedanceBoundaryLine(n).setR(1.0).setX(10.0).setG(0.0).setB(0.0001)));

        // Voltage level limits without VoltageLimit objects: the attributes of the VoltageLevel itself
        scenarios.add(scenario("voltageLevelLimitsWithoutVoltageLimitObjects", LINE_DIR, LINE_FILES,
                n -> n.getVoltageLevel("VoltageLevel1").setLowVoltageLimit(380.0).setHighVoltageLimit(420.0),
                n -> n.getVoltageLevel("VoltageLevel1").setHighVoltageLimit(415.0).setLowVoltageLimit(385.0),
                n -> n.getVoltageLevel("VoltageLevel1").setLowVoltageLimit(380.0).setHighVoltageLimit(420.0)));

        // CGMES 2.4.15 operational limits, which are equipment values
        scenarios.add(scenario("cim16ThreeKindsOfLimits", OPERATIONAL_LIMITS_DIR, LOADING_LIMITS_FILES,
                n -> { },
                n -> {
                    Line line = n.getLine("ACL");
                    line.getCurrentLimits1().orElseThrow().setPermanentLimit(110.0);
                    line.getActivePowerLimits1().orElseThrow().setPermanentLimit(111.0);
                    line.getApparentPowerLimits1().orElseThrow().setPermanentLimit(112.0);
                },
                n -> {
                    Line line = n.getLine("ACL");
                    line.getCurrentLimits1().orElseThrow().setPermanentLimit(100.0);
                    line.getActivePowerLimits1().orElseThrow().setPermanentLimit(101.0);
                    line.getApparentPowerLimits1().orElseThrow().setPermanentLimit(102.0);
                }));
        scenarios.add(scenario("cim16EquipmentAttachedLimitBothSides", OPERATIONAL_LIMITS_DIR, EQUIPMENT_LIMITS_FILES,
                // The fixture is an equipment model with no steady state hypothesis at all, so the injection of its
                // boundary line is undefined; the CGMES update defaults an undefined injection to zero for every
                // update it runs, which has nothing to do with the change under test
                n -> n.getBoundaryLine("DL").setP0(0.0).setQ0(0.0),
                n -> {
                    n.getLine("ACL").getCurrentLimits1().orElseThrow().setPermanentLimit(120.0);
                    n.getLine("ACL").getCurrentLimits2().orElseThrow().setPermanentLimit(120.0);
                },
                n -> {
                    n.getLine("ACL").getCurrentLimits1().orElseThrow().setPermanentLimit(100.0);
                    n.getLine("ACL").getCurrentLimits2().orElseThrow().setPermanentLimit(100.0);
                }));
        scenarios.add(scenario("cim16VoltageLimits", OPERATIONAL_LIMITS_DIR, VOLTAGE_LIMITS_FILES,
                n -> { },
                n -> n.getVoltageLevel("VL_1").setHighVoltageLimit(405.0).setLowVoltageLimit(395.0),
                n -> n.getVoltageLevel("VL_1").setHighVoltageLimit(410.0).setLowVoltageLimit(390.0)));

        // One change set touching both profiles
        scenarios.add(scenario("mixedSshAndEq", LINE_DIR, LINE_FILES,
                n -> n.getLine(AC_LINE_SEGMENT).setR(1.5),
                n -> {
                    n.getLine(AC_LINE_SEGMENT).setR(1.6);
                    currentLimits(n, 1).setPermanentLimit(850.0);
                },
                n -> {
                    n.getLine(AC_LINE_SEGMENT).setR(1.5);
                    currentLimits(n, 1).setPermanentLimit(797.0);
                }));

        return List.copyOf(scenarios);
    }

    /** Every characterized change of both profiles, which is what the difference model round trips walk. */
    public static List<Scenario> allChanges() {
        List<Scenario> scenarios = new ArrayList<>(all());
        scenarios.addAll(equipmentChanges());
        return List.copyOf(scenarios);
    }

    /** Every characterized scenario, one per supported kind of equipment and per distinct mapping of it. */
    public static List<Scenario> all() {
        List<Scenario> scenarios = new ArrayList<>();

        // Switches
        scenarios.add(scenario("switchOpening", SWITCH_DIR, SWITCH_FILES,
                n -> n.getSwitch("Breaker").setOpen(true),
                n -> n.getSwitch("Breaker").setOpen(false)));
        scenarios.add(scenario("branchModelledAsSwitchClosing", SWITCH_DIR, SWITCH_FILES,
                n -> n.getSwitch("SeriesCompensator").setOpen(false),
                n -> n.getSwitch("SeriesCompensator").setOpen(true)));
        // The DC switch of this fixture starts open, so closing it is the change and opening it again the undo
        scenarios.add(scenario("dcSwitchClosing", detailedDcModel(), DC_DIR, DC_FILES, NOTHING,
                n -> n.getDcSwitch("DCSW_1_1").setOpen(false),
                n -> n.getDcSwitch("DCSW_1_1").setOpen(true)));

        // Loads
        scenarios.add(scenario("loadActivePower", LOAD_DIR, LOAD_FILES,
                n -> n.getLoad("EnergyConsumer").setP0(12.5),
                n -> n.getLoad("EnergyConsumer").setP0(10.0)));
        scenarios.add(scenario("loadSetpoints", LOAD_DIR, LOAD_FILES,
                n -> n.getLoad("EnergyConsumer").setP0(10.5).setQ0(5.5),
                n -> n.getLoad("EnergyConsumer").setP0(10.0).setQ0(5.0)));
        scenarios.add(scenario("energySourceSetpoints", LOAD_DIR, LOAD_FILES,
                n -> n.getLoad("EnergySource").setP0(-200.5).setQ0(-90.5),
                n -> n.getLoad("EnergySource").setP0(-200.0).setQ0(-90.0)));
        scenarios.add(scenario("asynchronousMachineSetpoints", LOAD_DIR, LOAD_FILES,
                n -> n.getLoad("AsynchronousMachine").setP0(200.5).setQ0(50.5),
                n -> n.getLoad("AsynchronousMachine").setP0(200.0).setQ0(50.0)));

        // Generators
        scenarios.add(scenario("generatorTargets", GENERATOR_DIR, GENERATOR_FILES,
                n -> n.getGenerator(SYNCHRONOUS_MACHINE).setTargetP(165.0).setTargetQ(-5.0).setTargetV(410.0),
                // RotatingMachine.q is 0 in the fixture and IIDM negates it, so the reactive target starts at -0.0
                n -> n.getGenerator(SYNCHRONOUS_MACHINE).setTargetP(160.0).setTargetQ(-0.0).setTargetV(405.0)));
        // A negative target makes the machine a motor, which changes SynchronousMachine.operatingMode as well
        scenarios.add(scenario("generatorTargetPTurnsTheMachineIntoAMotor", GENERATOR_DIR, GENERATOR_FILES,
                n -> n.getGenerator(SYNCHRONOUS_MACHINE).setTargetP(-50.0),
                n -> n.getGenerator(SYNCHRONOUS_MACHINE).setTargetP(160.0)));
        scenarios.add(scenario("generatorVoltageRegulationOff", GENERATOR_DIR, GENERATOR_FILES,
                n -> n.getGenerator(SYNCHRONOUS_MACHINE).setVoltageRegulatorOn(false),
                n -> n.getGenerator(SYNCHRONOUS_MACHINE).setVoltageRegulatorOn(true)));
        scenarios.add(scenario("generatorVoltageTarget", GENERATOR_DIR, GENERATOR_FILES,
                n -> n.getGenerator(SYNCHRONOUS_MACHINE).setTargetV(410.0),
                n -> n.getGenerator(SYNCHRONOUS_MACHINE).setTargetV(405.0)));
        scenarios.add(scenario("externalNetworkInjectionSetpoints", GENERATOR_DIR, GENERATOR_FILES,
                n -> n.getGenerator("ExternalNetworkInjection").setTargetP(45.0).setTargetQ(-12.0),
                n -> n.getGenerator("ExternalNetworkInjection").setTargetP(-0.0).setTargetQ(-0.0)));
        scenarios.add(scenario("equivalentInjectionSetpoints", GENERATOR_DIR, GENERATOR_FILES,
                n -> n.getGenerator("EquivalentInjection").setTargetP(-70.0).setTargetQ(15.0),
                n -> n.getGenerator("EquivalentInjection").setTargetP(-184.0).setTargetQ(-0.0)));
        scenarios.add(scenario("equivalentInjectionRegulation", GENERATOR_DIR, GENERATOR_FILES,
                n -> n.getGenerator("EquivalentInjection")
                        .setProperty(Conversion.PROPERTY_REGULATION_CAPABILITY, "true"),
                n -> n.getGenerator("EquivalentInjection").setTargetV(401.0).setVoltageRegulatorOn(true),
                n -> n.getGenerator("EquivalentInjection").setVoltageRegulatorOn(false).setTargetV(Double.NaN)));
        scenarios.add(scenario("generatorReferencePriority", GENERATOR_DIR, GENERATOR_FILES,
                n -> ReferencePriority.set(n.getGenerator(SYNCHRONOUS_MACHINE), 3),
                n -> ReferencePriority.set(n.getGenerator(SYNCHRONOUS_MACHINE), 0)));
        scenarios.add(scenario("generatorParticipationFactor", activePowerControlExtension(), GENERATOR_DIR,
                GENERATOR_FILES, NOTHING,
                n -> n.getGenerator(SYNCHRONOUS_MACHINE).getExtension(ActivePowerControl.class).setParticipationFactor(0.75),
                n -> n.getGenerator(SYNCHRONOUS_MACHINE).getExtension(ActivePowerControl.class).setParticipationFactor(0.0)));

        // Boundary lines
        scenarios.add(scenario("boundaryLineLoad", BOUNDARY_LINE_DIR, BOUNDARY_LINE_FILES,
                n -> n.getBoundaryLine("ACLineSegment").setP0(310.5).setQ0(-40.5),
                n -> n.getBoundaryLine("ACLineSegment").setP0(284.5).setQ0(70.5)));
        scenarios.add(scenario("boundaryLineGenerationSetpoints", BOUNDARY_LINE_DIR, BOUNDARY_LINE_FILES,
                n -> generation(n).setTargetP(10.0).setTargetQ(5.0),
                n -> generation(n).setTargetP(300.0).setTargetQ(-20.0),
                n -> generation(n).setTargetP(10.0).setTargetQ(5.0)));
        scenarios.add(scenario("boundaryLineGenerationRegulation", BOUNDARY_LINE_DIR, BOUNDARY_LINE_FILES,
                n -> generation(n).setTargetV(402.0).setVoltageRegulationOn(true),
                n -> generation(n).setVoltageRegulationOn(false),
                n -> generation(n).setVoltageRegulationOn(true)));

        // Tap changers
        scenarios.add(scenario("twoWindingsPhaseTapPosition", TRANSFORMER_DIR, TRANSFORMER_FILES,
                n -> n.getTwoWindingsTransformer(T2W).getPhaseTapChanger().setTapPosition(-1),
                n -> n.getTwoWindingsTransformer(T2W).getPhaseTapChanger().setTapPosition(-2)));
        scenarios.add(scenario("threeWindingsRatioTapPosition", TRANSFORMER_DIR, TRANSFORMER_FILES,
                n -> n.getThreeWindingsTransformer(T3W).getLeg2().getRatioTapChanger().setTapPosition(7),
                n -> n.getThreeWindingsTransformer(T3W).getLeg2().getRatioTapChanger().setTapPosition(8)));
        scenarios.add(scenario("phaseTapChangerRegulationValueAndDeadband", TRANSFORMER_DIR, TRANSFORMER_FILES,
                n -> n.getTwoWindingsTransformer(T2W).getPhaseTapChanger().setRegulationValue(50.0).setTargetDeadband(0.5),
                n -> n.getTwoWindingsTransformer(T2W).getPhaseTapChanger().setRegulationValue(55.0).setTargetDeadband(1.5),
                n -> n.getTwoWindingsTransformer(T2W).getPhaseTapChanger().setRegulationValue(50.0).setTargetDeadband(0.5)));
        scenarios.add(scenario("ratioTapChangerRegulationValueAndDeadband", TRANSFORMER_DIR, TRANSFORMER_FILES,
                n -> n.getThreeWindingsTransformer(T3W).getLeg2().getRatioTapChanger().setRegulationValue(225.0).setTargetDeadband(2.0),
                n -> n.getThreeWindingsTransformer(T3W).getLeg2().getRatioTapChanger().setRegulationValue(226.0).setTargetDeadband(3.0),
                n -> n.getThreeWindingsTransformer(T3W).getLeg2().getRatioTapChanger().setRegulationValue(225.0).setTargetDeadband(2.0)));
        scenarios.add(scenario("phaseTapChangerRegulationState", TRANSFORMER_DIR, TRANSFORMER_FILES,
                n -> n.getTwoWindingsTransformer(T2W).getPhaseTapChanger().setRegulating(false),
                n -> n.getTwoWindingsTransformer(T2W).getPhaseTapChanger().setRegulating(true),
                n -> n.getTwoWindingsTransformer(T2W).getPhaseTapChanger().setRegulating(false)));

        // Shunt compensators
        scenarios.add(scenario("shuntSectionCount", SHUNT_DIR, SHUNT_FILES,
                n -> n.getShuntCompensator("NonLinearShuntCompensator").setSectionCount(2),
                n -> n.getShuntCompensator("NonLinearShuntCompensator").setSectionCount(1)));
        scenarios.add(scenario("shuntVoltageTarget", SHUNT_DIR, SHUNT_FILES,
                n -> n.getShuntCompensator(LINEAR_SHUNT).setTargetV(407.0),
                n -> n.getShuntCompensator(LINEAR_SHUNT).setTargetV(405.0)));
        scenarios.add(scenario("shuntVoltageRegulationOn", SHUNT_DIR, SHUNT_FILES,
                n -> n.getShuntCompensator(LINEAR_SHUNT).setVoltageRegulatorOn(true),
                n -> n.getShuntCompensator(LINEAR_SHUNT).setVoltageRegulatorOn(false)));
        scenarios.add(scenario("shuntTargetDeadband", SHUNT_DIR, SHUNT_FILES,
                n -> n.getShuntCompensator(LINEAR_SHUNT).setVoltageRegulatorOn(true).setTargetDeadband(1.0),
                n -> n.getShuntCompensator(LINEAR_SHUNT).setTargetDeadband(2.5),
                n -> n.getShuntCompensator(LINEAR_SHUNT).setTargetDeadband(1.0)));

        // Static var compensators
        scenarios.add(scenario("staticVarCompensatorVoltageSetpoint", STATIC_VAR_COMPENSATOR_DIR, SVC_FILES,
                n -> n.getStaticVarCompensator("StaticVarCompensator-V").setVoltageSetpoint(400.0),
                n -> n.getStaticVarCompensator("StaticVarCompensator-V").setVoltageSetpoint(405.0)));
        scenarios.add(scenario("staticVarCompensatorReactivePowerSetpoint", STATIC_VAR_COMPENSATOR_DIR, SVC_FILES,
                n -> n.getStaticVarCompensator("StaticVarCompensator-Q").setReactivePowerSetpoint(200.0),
                n -> n.getStaticVarCompensator("StaticVarCompensator-Q").setReactivePowerSetpoint(215.0),
                n -> n.getStaticVarCompensator("StaticVarCompensator-Q").setReactivePowerSetpoint(200.0)));
        scenarios.add(scenario("staticVarCompensatorRegulating", STATIC_VAR_COMPENSATOR_DIR, SVC_FILES,
                n -> n.getStaticVarCompensator("StaticVarCompensator-V").setRegulating(false),
                n -> n.getStaticVarCompensator("StaticVarCompensator-V").setRegulating(true)));
        // The setpoint of the mode a compensator is not in has no property in the steady state hypothesis: its
        // RegulatingControl carries the target of the CGMES mode alone. Changed next to the active setpoint, so
        // that the difference is not empty and the inactive value really is left out of both directions.
        scenarios.add(scenario("staticVarCompensatorInactiveReactiveSetpoint", STATIC_VAR_COMPENSATOR_DIR, SVC_FILES,
                n -> n.getStaticVarCompensator("StaticVarCompensator-V").setReactivePowerSetpoint(10.0),
                n -> n.getStaticVarCompensator("StaticVarCompensator-V").setVoltageSetpoint(400.0).setReactivePowerSetpoint(50.0),
                n -> n.getStaticVarCompensator("StaticVarCompensator-V").setVoltageSetpoint(405.0).setReactivePowerSetpoint(10.0)));
        scenarios.add(scenario("staticVarCompensatorInactiveVoltageSetpoint", STATIC_VAR_COMPENSATOR_DIR, SVC_FILES,
                n -> n.getStaticVarCompensator("StaticVarCompensator-Q").setReactivePowerSetpoint(200.0).setVoltageSetpoint(400.0),
                n -> n.getStaticVarCompensator("StaticVarCompensator-Q").setReactivePowerSetpoint(215.0).setVoltageSetpoint(401.0),
                n -> n.getStaticVarCompensator("StaticVarCompensator-Q").setReactivePowerSetpoint(200.0).setVoltageSetpoint(400.0)));

        // HVDC, simplified model
        scenarios.add(scenario("hvdcActivePowerSetpoint", HVDC_DIR, HVDC_FILES,
                n -> n.getHvdcLine(LCC_LINE).setActivePowerSetpoint(300.0),
                n -> n.getHvdcLine(LCC_LINE).setActivePowerSetpoint(350.0),
                n -> n.getHvdcLine(LCC_LINE).setActivePowerSetpoint(300.0)));
        // Zero is a setpoint like any other, and it is where computeConverterState and the mode derivation have
        // their special cases
        scenarios.add(scenario("hvdcActivePowerSetpointToZero", HVDC_DIR, HVDC_FILES,
                n -> n.getHvdcLine(LCC_LINE).setActivePowerSetpoint(300.0),
                n -> n.getHvdcLine(LCC_LINE).setActivePowerSetpoint(0.0),
                n -> n.getHvdcLine(LCC_LINE).setActivePowerSetpoint(300.0)));
        scenarios.add(scenario("hvdcConvertersMode", HVDC_DIR, HVDC_FILES,
                n -> n.getHvdcLine(LCC_LINE).setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_INVERTER_SIDE_2_RECTIFIER),
                n -> n.getHvdcLine(LCC_LINE).setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER),
                n -> n.getHvdcLine(LCC_LINE).setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_INVERTER_SIDE_2_RECTIFIER)));
        scenarios.add(scenario("lccPowerFactor", HVDC_DIR, HVDC_FILES,
                n -> lcc(n, 2).setPowerFactor(0.9f),
                n -> lcc(n, 2).setPowerFactor(0.95f),
                n -> lcc(n, 2).setPowerFactor(0.9f)));
        scenarios.add(scenario("vscVoltageSetpoint", HVDC_DIR, HVDC_FILES,
                n -> vsc(n, 1).setVoltageSetpoint(396.54),
                n -> vsc(n, 1).setVoltageSetpoint(392.54)));
        scenarios.add(scenario("vscReactivePowerSetpointAndRegulation", HVDC_DIR, HVDC_FILES,
                n -> vsc(n, 2).setReactivePowerSetpoint(20.0),
                n -> vsc(n, 2).setVoltageRegulatorOn(false).setReactivePowerSetpoint(30.0),
                n -> vsc(n, 2).setReactivePowerSetpoint(20.0).setVoltageRegulatorOn(true)));

        // HVDC, detailed model
        scenarios.add(scenario("detailedVscReactivePowerSetpoint", detailedDcModel(), DC_DIR, DC_FILES,
                n -> n.getVoltageSourceConverter("VSC_1_2").setReactivePowerSetpoint(20.0),
                n -> n.getVoltageSourceConverter("VSC_1_2").setReactivePowerSetpoint(40.0),
                n -> n.getVoltageSourceConverter("VSC_1_2").setReactivePowerSetpoint(20.0)));
        scenarios.add(scenario("detailedConverterControlMode", detailedDcModel(), DC_DIR, DC_FILES,
                n -> n.getLineCommutatedConverter("CSC_1_1").setTargetVdc(450.0)
                        .setControlMode(AcDcConverter.ControlMode.P_PCC),
                n -> n.getLineCommutatedConverter("CSC_1_1").setTargetVdc(500.0)
                        .setControlMode(AcDcConverter.ControlMode.V_DC),
                n -> n.getLineCommutatedConverter("CSC_1_1").setTargetVdc(450.0)
                        .setControlMode(AcDcConverter.ControlMode.P_PCC)));
        scenarios.add(scenario("detailedLccPowerFactor", detailedDcModel(), DC_DIR, DC_FILES,
                n -> n.getLineCommutatedConverter("CSC_1_1").setPowerFactor(0.9),
                n -> n.getLineCommutatedConverter("CSC_1_1").setPowerFactor(0.92),
                n -> n.getLineCommutatedConverter("CSC_1_1").setPowerFactor(0.9)));

        addCim100OperationalLimitScenarios(scenarios);

        return List.copyOf(scenarios);
    }

    /**
     * The operational limit scenarios of a CGMES 3 model. Their values are steady state data there, so they belong to
     * the steady state characterization nets like every other scenario of {@link #all()}.
     */
    private static void addCim100OperationalLimitScenarios(List<Scenario> scenarios) {
        scenarios.add(scenario("cim100CurrentPatlSide1", LINE_DIR, LINE_FILES,
                n -> currentLimits(n, 1).setPermanentLimit(800.0),
                n -> currentLimits(n, 1).setPermanentLimit(850.0),
                n -> currentLimits(n, 1).setPermanentLimit(800.0)));
        scenarios.add(scenario("cim100CurrentTatlSide2", LINE_DIR, LINE_FILES,
                n -> currentLimits(n, 2).setTemporaryLimitValue(900, 2000.0),
                n -> currentLimits(n, 2).setTemporaryLimitValue(900, 2100.0),
                n -> currentLimits(n, 2).setTemporaryLimitValue(900, 2000.0)));
        scenarios.add(scenario("cim100ApparentPowerLimitTransformer", TRANSFORMER_DIR, TRANSFORMER_FILES,
                n -> transformerApparentPowerLimits(n).setPermanentLimit(500.0),
                n -> transformerApparentPowerLimits(n).setPermanentLimit(520.0),
                n -> transformerApparentPowerLimits(n).setPermanentLimit(500.0)));
        scenarios.add(scenario("cim100ApparentPowerTatlThreeWindings", TRANSFORMER_DIR, TRANSFORMER_FILES,
                n -> threeWindingsApparentPowerLimits(n).setTemporaryLimitValue(900, 600.0),
                n -> threeWindingsApparentPowerLimits(n).setTemporaryLimitValue(900, 640.0),
                n -> threeWindingsApparentPowerLimits(n).setTemporaryLimitValue(900, 600.0)));
        scenarios.add(scenario("cim100ActivePowerLimitBoundaryLine", BOUNDARY_LINE_DIR, BOUNDARY_LINE_FILES,
                n -> boundaryLineActivePowerLimits(n).setPermanentLimit(400.0),
                n -> boundaryLineActivePowerLimits(n).setPermanentLimit(430.0),
                n -> boundaryLineActivePowerLimits(n).setPermanentLimit(400.0)));
        scenarios.add(scenario("cim100TieLineHalfLimits", TIE_LINE_DIR, TIE_LINE_FILES,
                n -> {
                    tieLineHalfLimits(n, 1).setPermanentLimit(400.0);
                    tieLineHalfLimits(n, 2).setPermanentLimit(400.0);
                },
                n -> {
                    tieLineHalfLimits(n, 1).setPermanentLimit(420.0);
                    tieLineHalfLimits(n, 2).setPermanentLimit(430.0);
                },
                n -> {
                    tieLineHalfLimits(n, 1).setPermanentLimit(400.0);
                    tieLineHalfLimits(n, 2).setPermanentLimit(400.0);
                }));
        scenarios.add(scenario("cim100VoltageLimitsMultiId", VOLTAGE_LEVEL_DIR, VOLTAGE_LEVEL_FILES,
                n -> n.getVoltageLevel("VL_1").setHighVoltageLimit(400.0),
                n -> n.getVoltageLevel("VL_1").setHighVoltageLimit(405.0),
                n -> n.getVoltageLevel("VL_1").setHighVoltageLimit(400.0)));
        scenarios.add(scenario("cim100VoltageLimitSingleId", VOLTAGE_LEVEL_DIR, VOLTAGE_LEVEL_FILES,
                n -> n.getVoltageLevel("VL_2").setLowVoltageLimit(390.0),
                n -> n.getVoltageLevel("VL_2").setLowVoltageLimit(385.0),
                n -> n.getVoltageLevel("VL_2").setLowVoltageLimit(390.0)));
        // The name of the temporary limit is the one the import gave it: CGMES carries the name of an
        // OperationalLimit in the equipment model, not in a value change, so a rename cannot travel and would only
        // make this scenario assert a gap it does not test
        scenarios.add(scenario("wholeLimitsReplacedSameStructure", LINE_DIR, LINE_FILES,
                n -> { },
                n -> replaceCurrentLimits(n, 900.0, 2000.0),
                n -> replaceCurrentLimits(n, 797.0, 1991.0)));
    }
}
