/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the mapping says about the current state of a subject, which is how the difference model importer completes a
 * consistency group it was not given in full ({@link Families#describe}).
 *
 * <p>The proof is by comparison with the exporter itself: the description of a subject of a network after a change
 * states every value the forward direction of a full object difference of that change states, because both describe
 * the state the network is in after the change. The description may say more: it describes every block of the objects
 * of the subject, the difference only the blocks the change touched.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class FamiliesTest {

    /** Describe a subject after a change and compare with the forward statements of the difference of that change. */
    private static Map<String, String> assertDescriptionStatesTheForwardDifference(Network network, String subjectId,
                                                                                  Consumer<Network> change) {
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, change);
        DifferenceModel model = CgmesDiffExport.toDifferences(network, events, new CgmesDiffExport.ExportOptions()
                        .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL))
                .differences().models().values().iterator().next();
        Families families = new Families(network);
        Families.Subject subject = families.resolve(subjectId, null).orElseThrow();
        Map<String, String> described = byKey(families.describe(subject).statements());
        Map<String, String> forward = byKey(model.forward());
        assertTrue(!forward.isEmpty(), "the difference says nothing at all");
        forward.forEach((key, value) -> assertEquals(value, described.get(key), key));
        return described;
    }

    private static Map<String, String> byKey(List<CgmesStatement> statements) {
        Map<String, String> values = new LinkedHashMap<>();
        statements.stream().filter(statement -> !statement.isType())
                .forEach(statement -> values.put(statement.subjectId() + " " + statement.property(), statement.value()));
        return values;
    }

    @Test
    void theDescriptionOfALoadStatesItsChange() {
        Network network = readCgmesResources("/update/load/", "load_EQ.xml", "load_SSH.xml");
        assertDescriptionStatesTheForwardDifference(network, "EnergyConsumer", n -> n.getLoad("EnergyConsumer").setP0(12.5));
    }

    @Test
    void theDescriptionOfAGeneratorStatesItsChange() {
        Network network = readCgmesResources("/update/generator/", "generator_EQ.xml", "generator_SSH.xml");
        assertDescriptionStatesTheForwardDifference(network, "SynchronousMachine",
            n -> n.getGenerator("SynchronousMachine").setTargetP(120.0));
    }

    @Test
    void theDescriptionOfATapChangerStatesItsChange() {
        Network network = readCgmesResources("/update/transformer/", "transformer_EQ.xml", "transformer_SSH.xml");
        String tapChangerId = network.getTwoWindingsTransformer("T2W").getAliasFromType(Conversion.ALIAS_PHASE_TAP_CHANGER1)
                .orElseThrow();
        assertDescriptionStatesTheForwardDifference(network, tapChangerId,
            n -> n.getTwoWindingsTransformer("T2W").getPhaseTapChanger().setTapPosition(2));
    }

    /**
     * The equipment values (limits and impedances) are described the same way: a difference model importer asks the
     * mapping what the receiving network currently says about an impedance, a voltage level limit or an operational
     * limit. A limit describes the whole set of loading limits it belongs to, while the difference of a change to one
     * of them drops the limits whose two directions say the same thing.
     */
    @Test
    void theDescriptionOfAnEquipmentValueStatesItsChange() {
        Network line = readCgmesResources("/update/line/", "line_EQ.xml", "line_SSH.xml");
        assertDescriptionStatesTheForwardDifference(line, "ACLineSegment", n -> n.getLine("ACLineSegment").setR(1.6));

        Network voltageLevel = readCgmesResources("/update/voltage-level/", "voltageLevel_EQ.xml", "voltageLevel_SSH.xml");
        assertDescriptionStatesTheForwardDifference(voltageLevel, "VL_1", n -> n.getVoltageLevel("VL_1").setHighVoltageLimit(405.0));

        Network limits = readCgmesResources("/update/line/", "line_EQ.xml", "line_SSH.xml");
        Map<String, String> described = assertDescriptionStatesTheForwardDifference(limits,
                "ACLineSegment-T1-OperationalLimitSet-CurrentLimit1",
                n -> n.getLine("ACLineSegment").getCurrentLimits1().orElseThrow().setPermanentLimit(850.0));
        assertEquals("1991", described.get("ACLineSegment-T1-OperationalLimitSet-CurrentLimit2 CurrentLimit.value"));
    }

    /**
     * A block the mapping refuses gives its reason: a generator without VoltageRegulation whose import would give it
     * one cannot be described at all, and says why.
     */
    @Test
    void aSubjectTheMappingRefusesGivesTheReason() {
        Network network = readCgmesResources("/update/generator/", "generator_EQ.xml", "generator_SSH.xml");
        network.getGenerator("SynchronousMachine").removeVoltageRegulation();
        Families families = new Families(network);
        Families.Description description = families.describe(families.resolve("SynchronousMachine", null).orElseThrow());
        assertEquals(List.of(), description.statements());
        assertTrue(description.refusal().map(reason -> Refusal.of(reason).orElse(null) == Refusal.IMPORT_GIVES_REGULATION)
                .orElse(false), description::toString);
    }

    /**
     * The blocks of the extensions of a generator are described with it: the participation factor of its
     * GeneratingUnit and its reference priority, which the CGMES update reads only together with the machine block.
     */
    @Test
    void theExtensionBlocksOfAGeneratorAreDescribed() {
        Properties parameters = new Properties();
        parameters.put(CgmesImport.CREATE_ACTIVE_POWER_CONTROL_EXTENSION, "true");
        Network network = readCgmesResources(parameters, "/update/generator/", "generator_EQ.xml", "generator_SSH.xml");
        Families families = new Families(network);
        String unitId = network.getGenerator("SynchronousMachine").getProperty(Conversion.PROPERTY_GENERATING_UNIT);
        Map<String, String> unit = byKey(families.describe(families.resolve(unitId, null).orElseThrow()).statements());
        assertTrue(unit.containsKey(unitId + " GeneratingUnit.normalPF"), unit::toString);
        Map<String, String> machine = byKey(families.describe(families.resolve("SynchronousMachine", null).orElseThrow()).statements());
        assertTrue(machine.containsKey("SynchronousMachine SynchronousMachine.referencePriority"), machine::toString);
    }
}
