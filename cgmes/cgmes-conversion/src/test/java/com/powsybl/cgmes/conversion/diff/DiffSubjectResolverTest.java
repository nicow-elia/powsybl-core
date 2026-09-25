/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.diff.DiffSubjectResolver.ResolvedSubject;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.Family;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolution of difference model subjects against a network, one assertion per family.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class DiffSubjectResolverTest {

    private static Properties detailedDc() {
        Properties parameters = new Properties();
        parameters.put(CgmesImport.USE_DETAILED_DC_MODEL, "true");
        return parameters;
    }

    private static ResolvedSubject resolve(Network network, String subjectId, String... properties) {
        Optional<ResolvedSubject> resolved =
                new DiffSubjectResolver(network).resolve(subjectId, Set.of(properties), null);
        assertTrue(resolved.isPresent(), () -> subjectId + " did not resolve");
        return resolved.get();
    }

    private static void assertFamily(Network network, String subjectId, Family family, String rdfType,
                                     String... properties) {
        ResolvedSubject subject = resolve(network, subjectId, properties);
        assertEquals(family, subject.family(), subjectId);
        assertEquals(rdfType, subject.rdfType(), subjectId);
    }

    @Test
    void switchesAndTheirTerminals() {
        Network network = readCgmesResources("/update/switch/", "switch_EQ.xml", "switch_SSH.xml");
        assertFamily(network, "Breaker", Family.SWITCH, "Breaker", "Switch.open");
        assertFamily(network, "Breaker-T1", Family.TERMINAL, "Terminal", "ACDCTerminal.connected");

        // A branch the importer turned into a switch has no Switch.open of its own
        assertTrue(new DiffSubjectResolver(network)
                .resolve("SeriesCompensator", Set.of("Switch.open"), null).isEmpty());
        assertTrue(new DiffSubjectResolver(network)
                .reasonFor("SeriesCompensator", Set.of("Switch.open"), null).contains("carried by its terminals"));
    }

    @Test
    void loadsByTheirOriginalClass() {
        Network network = readCgmesResources("/update/load/", "load_EQ.xml", "load_SSH.xml");
        assertFamily(network, "EnergyConsumer", Family.ENERGY_CONSUMER, "EnergyConsumer", "EnergyConsumer.p");
        assertFamily(network, "EnergySource", Family.ENERGY_SOURCE, "EnergySource", "EnergySource.activePower");
        assertFamily(network, "AsynchronousMachine", Family.ASYNCHRONOUS_MACHINE, "AsynchronousMachine",
                "RotatingMachine.p");
    }

    @Test
    void generatorsAndTheirRegulatingControlsAndUnits() {
        Network network = readCgmesResources("/update/generator/", "generator_EQ.xml", "generator_SSH.xml");
        assertFamily(network, "SynchronousMachine", Family.SYNCHRONOUS_MACHINE, "SynchronousMachine",
                "RotatingMachine.p");
        assertFamily(network, "ExternalNetworkInjection", Family.EXTERNAL_NETWORK_INJECTION,
                "ExternalNetworkInjection", "ExternalNetworkInjection.p");

        String controlId = network.getGenerator("SynchronousMachine")
                .getProperty(Conversion.PROPERTY_REGULATING_CONTROL);
        assertFamily(network, controlId, Family.REGULATING_CONTROL, "RegulatingControl", "RegulatingControl.enabled");
        String unitId = network.getGenerator("SynchronousMachine").getProperty(Conversion.PROPERTY_GENERATING_UNIT);
        assertFamily(network, unitId, Family.GENERATING_UNIT, "GeneratingUnit", "GeneratingUnit.normalPF");
    }

    @Test
    void shuntsAndStaticVarCompensators() {
        Network shunts = readCgmesResources("/update/shunt-compensator/",
                "shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml");
        assertFamily(shunts, "LinearShuntCompensator", Family.SHUNT_COMPENSATOR, "LinearShuntCompensator",
                "ShuntCompensator.sections");
        Network svcs = readCgmesResources("/update/static-var-compensator/",
                "staticVarCompensator_EQ.xml", "staticVarCompensator_SSH.xml");
        assertFamily(svcs, "StaticVarCompensator-V", Family.STATIC_VAR_COMPENSATOR, "StaticVarCompensator",
                "StaticVarCompensator.q");
    }

    @Test
    void tapChangersAndTapChangerControls() {
        Network network = readCgmesResources("/update/transformer/", "transformer_EQ.xml", "transformer_SSH.xml");
        ResolvedSubject phase = resolve(network, "T2W-PhaseTapChanger", "TapChanger.step");
        assertEquals(Family.PHASE_TAP_CHANGER, phase.family());
        assertEquals("T2W", phase.owner().getId());
        assertEquals("phaseTapChanger", phase.ownerAttributePrefix(),
                "a two windings transformer reports its tap changer without an end number");
        assertTrue(FastRouteCapabilities.spec(Family.PHASE_TAP_CHANGER).rdfTypes().contains(phase.rdfType()),
                phase.rdfType());

        ResolvedSubject ratio = resolve(network, "T3W-Winding2-RatioTapChanger", "TapChanger.step");
        assertEquals(Family.RATIO_TAP_CHANGER, ratio.family());
        assertEquals("RatioTapChanger", ratio.rdfType());
        assertEquals("T3W", ratio.owner().getId());
        assertEquals("ratioTapChanger2", ratio.ownerAttributePrefix(),
                "a three windings transformer reports the leg its tap changer belongs to");
    }

    @Test
    void boundaryLineEquivalentInjection() {
        Network network = readCgmesResources("/update/boundary-line/",
                "boundaryLine_EQ.xml", "boundaryLine_EQ_BD.xml", "boundaryLine_SSH.xml");
        String equivalentInjection = network.getBoundaryLine("EquivalentBranch")
                .getProperty(Conversion.PROPERTY_EQUIVALENT_INJECTION);
        assertFamily(network, equivalentInjection, Family.EQUIVALENT_INJECTION, "EquivalentInjection",
                "EquivalentInjection.p");
    }

    @Test
    void convertersOfBothModels() {
        Network simple = readCgmesResources("/update/hvdc/", "hvdc_EQ.xml", "hvdc_SSH.xml");
        ResolvedSubject vsc = resolve(simple, "DCLineSegment-Vsc-VscConverter-1", "VsConverter.pPccControl");
        assertEquals(Family.VS_CONVERTER, vsc.family());
        assertEquals("VsConverter", vsc.rdfType());
        // The setpoints of a converter station of the simplified model live on the HVDC line
        assertTrue(vsc.iidmIds().contains("DCLineSegment-Vsc"), vsc.iidmIds().toString());
        assertFamily(simple, "DCLineSegment-Lcc-CsConverter-1", Family.CS_CONVERTER, "CsConverter",
                "CsConverter.pPccControl");

        Network detailed = readCgmesResources(detailedDc(), "/issues/hvdc/",
                "mixed_bipole_EQ.xml", "mixed_bipole_SSH.xml");
        assertFamily(detailed, "CSC_1_1", Family.CS_CONVERTER, "CsConverter", "CsConverter.pPccControl");
        // A DC switch has no cim:Switch.open: its state is carried by the connected flag of its DC terminals
        assertTrue(new DiffSubjectResolver(detailed).resolve("DCSW_1_1", Set.of("Switch.open"), null).isEmpty());
        ResolvedSubject dcTerminal = resolve(detailed,
                detailed.getDcSwitch("DCSW_1_1").getAliasFromType("CGMES.DCTerminal1").orElseThrow(),
                "ACDCTerminal.connected");
        assertEquals(Family.DC_TERMINAL, dcTerminal.family());
        assertEquals("DCTerminal", dcTerminal.rdfType());
    }

    @Test
    void controlAreas() {
        Network network = readCgmesResources("/update/control-area/",
                "controlArea_EQ.xml", "controlArea_EQ_BD.xml", "controlArea_SSH.xml");
        String areaId = network.getAreaStream().findFirst().orElseThrow().getId();
        assertFamily(network, areaId, Family.CONTROL_AREA, "ControlArea", "ControlArea.netInterchange");
    }

    /** The whole conformity model, to show that the resolution is not tuned to the hand written fixtures. */
    @Test
    void everyKindOfSubjectOfTheConformityModel() {
        Network network = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        DiffSubjectResolver resolver = new DiffSubjectResolver(network);
        network.getLoads().forEach(load -> assertTrue(
                resolver.resolve(load.getId(), Set.of("EnergyConsumer.p"), null).isPresent()
                        || resolver.resolve(load.getId(), Set.of("EnergySource.activePower"), null).isPresent()
                        || resolver.resolve(load.getId(), Set.of("RotatingMachine.p"), null).isPresent(),
                load.getId()));
        network.getGenerators().forEach(generator -> assertTrue(
                resolver.resolve(generator.getId(), Set.of(), null).isPresent(), generator.getId()));
        network.getSwitches().forEach(sw -> assertTrue(
                resolver.resolve(sw.getId(), Set.of("Switch.open"), null).isPresent()
                        || sw.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS) == null
                        || !FastRouteCapabilities.spec(Family.SWITCH).rdfTypes()
                                .contains(sw.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS)),
                sw.getId()));
    }

    @Test
    void unknownSubjectAndWrongFamilyProperty() {
        Network network = readCgmesResources("/update/load/", "load_EQ.xml", "load_SSH.xml");
        DiffSubjectResolver resolver = new DiffSubjectResolver(network);
        assertTrue(resolver.resolve("nothing-like-this", Set.of("EnergyConsumer.p"), null).isEmpty());
        assertTrue(resolver.reasonFor("nothing-like-this", Set.of("EnergyConsumer.p"), null)
                .contains("no object of this network has this identifier"));

        assertTrue(resolver.resolve("EnergyConsumer", Set.of("Switch.open"), null).isEmpty());
        assertTrue(resolver.reasonFor("EnergyConsumer", Set.of("Switch.open"), null)
                .contains("Switch.open is not updatable on a ENERGY_CONSUMER"));
    }

    /** A network whose identifiers are {@code urn:uuid:} URIs, which a difference model states without the scheme. */
    @Test
    void urnUuidIdentifiersResolve() {
        Network network = Network.create("urn-uuid", "test");
        network.newSubstation().setId("urn:uuid:S").add()
                .newVoltageLevel().setId("urn:uuid:VL").setNominalV(400)
                .setTopologyKind(com.powsybl.iidm.network.TopologyKind.BUS_BREAKER).add()
                .getBusBreakerView().newBus().setId("urn:uuid:B").add();
        network.getVoltageLevel("urn:uuid:VL").newLoad().setId("urn:uuid:L").setBus("urn:uuid:B")
                .setP0(1).setQ0(1).add();
        network.getLoad("urn:uuid:L").setProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS, "ConformLoad");

        network.getVoltageLevel("urn:uuid:VL").newGenerator().setId("urn:uuid:G").setBus("urn:uuid:B")
                .setTargetP(1).setTargetQ(0).setMinP(0).setMaxP(10).setVoltageRegulatorOn(false).add();
        network.getGenerator("urn:uuid:G")
                .setProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS, "SynchronousMachine");
        network.getGenerator("urn:uuid:G").setProperty(Conversion.PROPERTY_REGULATING_CONTROL, "urn:uuid:RC");
        network.getGenerator("urn:uuid:G").setProperty(Conversion.PROPERTY_GENERATING_UNIT, "urn:uuid:GU");

        ResolvedSubject subject = resolve(network, "L", "EnergyConsumer.p");
        assertEquals(Family.ENERGY_CONSUMER, subject.family());
        assertEquals("ConformLoad", subject.rdfType());
        assertEquals("urn:uuid:L", subject.about(), "the subject is written the way the network spells it");

        // The secondary index holds the CGMES objects IIDM does not model; a difference states them without the
        // urn:uuid: scheme, exactly as it states equipment
        ResolvedSubject control = resolve(network, "RC", "RegulatingControl.enabled");
        assertEquals(Family.REGULATING_CONTROL, control.family());
        assertEquals("urn:uuid:RC", control.about());
        assertEquals("urn:uuid:G", control.owner().getId());
        ResolvedSubject unit = resolve(network, "GU", "GeneratingUnit.normalPF");
        assertEquals(Family.GENERATING_UNIT, unit.family());
        assertEquals("urn:uuid:GU", unit.about());
    }

    // Equipment values of work package 5

    @Test
    void operationalLimitsResolveToTheirOwnerAndSide() {
        Network network = readCgmesResources("/update/line/", "line_EQ.xml", "line_SSH.xml");
        ResolvedSubject patl = resolve(network, "ACLineSegment-T1-OperationalLimitSet-CurrentLimit1",
                "CurrentLimit.value");
        assertEquals(Family.CURRENT_LIMIT, patl.family());
        assertEquals("CurrentLimit", patl.rdfType());
        assertEquals("ACLineSegment", patl.owner().getId());
        assertEquals(Set.of("ACLineSegment"), patl.iidmIds());
        // The probe key of the limit, which is what describes the whole set of loading limits it belongs to
        assertTrue(patl.ownerAttributePrefix().startsWith("limits1_CURRENT"), patl.ownerAttributePrefix());
    }

    /** A CGMES limit set attached to the equipment of a line lands on both sides, so one identifier has two slots. */
    @Test
    void anEquipmentAttachedLimitResolvesToBothSides() {
        Network network = readCgmesResources("/issues/operational-limits/",
                "limitsets_associated_to_equipments_EQ.xml", "limitsets_EQBD.xml", "limitsets_TPBD.xml");
        ResolvedSubject limit = resolve(network, "CL_ACL", "CurrentLimit.value");
        assertEquals(Family.CURRENT_LIMIT, limit.family());
        assertEquals("ACL", limit.owner().getId());
    }

    @Test
    void voltageLimitsResolveToTheirVoltageLevel() {
        Network network = readCgmesResources("/issues/operational-limits/", "voltage_limits.xml");
        ResolvedSubject high = resolve(network, "VL_H_1", "VoltageLimit.value");
        assertEquals(Family.VOLTAGE_LIMIT, high.family());
        assertEquals("VoltageLimit", high.rdfType());
        assertEquals("VL_1", high.owner().getId());
        assertEquals("highVoltageLimit", high.ownerAttributePrefix());
    }

    @Test
    void branchesAndVoltageLevelsResolveByTheirCgmesClass() {
        Network network = readCgmesResources("/update/line/", "line_EQ.xml", "line_SSH.xml");
        assertFamily(network, "ACLineSegment", Family.AC_LINE_SEGMENT, "ACLineSegment", "ACLineSegment.r");
        assertFamily(network, "SeriesCompensator", Family.SERIES_COMPENSATOR, "SeriesCompensator",
                "SeriesCompensator.x");
        assertFamily(network, "EquivalentBranch", Family.EQUIVALENT_BRANCH, "EquivalentBranch",
                "EquivalentBranch.r");
        assertFamily(network, "VoltageLevel1", Family.VOLTAGE_LEVEL, "VoltageLevel",
                "VoltageLevel.highVoltageLimit");
    }

    @Test
    void aBoundaryLineResolvesByItsCgmesClass() {
        Network network = readCgmesResources("/update/boundary-line/",
                "boundaryLine_EQ.xml", "boundaryLine_EQ_BD.xml", "boundaryLine_SSH.xml");
        assertFamily(network, "ACLineSegment", Family.AC_LINE_SEGMENT, "ACLineSegment", "ACLineSegment.bch");
    }

    @Test
    void aTransformerHasNoImpedanceSubject() {
        Network network = readCgmesResources("/update/transformer/", "transformer_EQ.xml", "transformer_SSH.xml");
        assertTrue(new DiffSubjectResolver(network).resolve("T2W", Set.of("ACLineSegment.r"), null).isEmpty(),
                "a transformer impedance is not updatable in place");
    }
}
