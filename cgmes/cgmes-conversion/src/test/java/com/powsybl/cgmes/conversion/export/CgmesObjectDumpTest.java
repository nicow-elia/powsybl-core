/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the export mapping says about the current state of an object, which is how the difference model importer
 * completes a consistency group it was not given in full.
 *
 * <p>The proof is by comparison with the exporter itself: dumping an object of an unchanged network has to give the
 * same statements as the forward direction of a full object difference of a change to that very object, because both
 * describe the state the network is in after the change.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesObjectDumpTest {

    /**
     * Dump the given attribute of the given object after applying a change, and compare with the forward statements
     * of the difference model of that same change.
     */
    private static void assertDumpEqualsForwardDifference(Network network, String identifiableId, String attributeKey,
                                                          Consumer<Network> change) {
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, change);
        DifferenceModel model = CgmesDiffExport.toDifferences(network, events, new CgmesDiffExport.ExportOptions()
                        .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL))
                .differences().models().values().iterator().next();

        Map<String, String> fromExport = byKey(model.forward());
        Map<String, String> fromDump = byKey(new CgmesObjectDump(network).statementsFor(identifiableId, attributeKey));
        assertEquals(fromExport, fromDump);
        assertTrue(!fromDump.isEmpty(), "the dump said nothing at all");
    }

    private static Map<String, String> byKey(List<CgmesStatement> statements) {
        Map<String, String> values = new LinkedHashMap<>();
        statements.stream().filter(statement -> !statement.isType())
                .forEach(statement -> values.put(statement.subjectId() + " " + statement.property(), statement.value()));
        return values;
    }

    @Test
    void loadDumpEqualsTheForwardDifferenceOfALoadChange() {
        Network network = readCgmesResources("/update/load/", "load_EQ.xml", "load_SSH.xml");
        assertDumpEqualsForwardDifference(network, "EnergyConsumer", "p0",
            n -> n.getLoad("EnergyConsumer").setP0(12.5));
    }

    @Test
    void generatorDumpEqualsTheForwardDifferenceOfAGeneratorChange() {
        Network network = readCgmesResources("/update/generator/", "generator_EQ.xml", "generator_SSH.xml");
        assertDumpEqualsForwardDifference(network, "SynchronousMachine", "targetP",
            n -> n.getGenerator("SynchronousMachine").setTargetP(120.0));
    }

    @Test
    void tapChangerDumpEqualsTheForwardDifferenceOfATapChange() {
        Network network = readCgmesResources("/update/transformer/", "transformer_EQ.xml", "transformer_SSH.xml");
        assertDumpEqualsForwardDifference(network, "T2W", "phaseTapChanger.tapPosition",
            n -> n.getTwoWindingsTransformer("T2W").getPhaseTapChanger().setTapPosition(2));
    }

    /**
     * The equipment values of work package 5 go through the same probe: a difference model importer asks the mapping
     * what the receiving network currently says about an impedance, a voltage level limit or an operational limit.
     */
    @Test
    void eqDumpsEqualForwardStatements() {
        Network line = readCgmesResources("/update/line/", "line_EQ.xml", "line_SSH.xml");
        assertDumpEqualsForwardDifference(line, "ACLineSegment", "r", n -> n.getLine("ACLineSegment").setR(1.6));

        Network voltageLevel = readCgmesResources("/update/voltage-level/",
                "voltageLevel_EQ.xml", "voltageLevel_SSH.xml");
        assertDumpEqualsForwardDifference(voltageLevel, "VL_1", "highVoltageLimit",
                n -> n.getVoltageLevel("VL_1").setHighVoltageLimit(405.0));

        // A limit probe describes the whole set of loading limits, while the difference of a change to one of them
        // drops the limits whose two directions say the same thing, so the dump is a superset here
        Network limits = readCgmesResources("/update/line/", "line_EQ.xml", "line_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(limits,
                n -> n.getLine("ACLineSegment").getCurrentLimits1().orElseThrow().setPermanentLimit(850.0));
        DifferenceModel model = CgmesDiffExport.toDifferences(limits, events, new CgmesDiffExport.ExportOptions()
                        .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL))
                .differences().models().values().iterator().next();
        Map<String, String> fromDump = byKey(new CgmesObjectDump(limits).statementsFor("ACLineSegment",
                "limits1_CURRENT.permanentLimit@ACLineSegment-T1-OperationalLimitSet"));
        byKey(model.forward()).forEach((key, value) -> assertEquals(value, fromDump.get(key)));
        assertEquals("1991", fromDump.get("ACLineSegment-T1-OperationalLimitSet-CurrentLimit2 CurrentLimit.value"));
    }

    @Test
    void anAttributeTheMappingRefusesGivesTheReason() {
        Network network = readCgmesResources("/update/load/", "load_EQ.xml", "load_SSH.xml");
        CgmesObjectDump dump = new CgmesObjectDump(network);
        assertEquals(List.of(), dump.statementsFor("EnergyConsumer", "fictitious"));
        assertTrue(dump.dump("EnergyConsumer", "fictitious").fold(statements -> null, reason -> reason) != null,
                "a refused probe has to carry the reason the mapping gave");
    }

    @Test
    void theSameProbeIsTranslatedOnlyOnce() {
        Network network = readCgmesResources("/update/load/", "load_EQ.xml", "load_SSH.xml");
        CgmesObjectDump dump = new CgmesObjectDump(network);
        assertEquals(dump.statementsFor("EnergyConsumer", "p0"), dump.statementsFor("EnergyConsumer", "p0"));
        // The cached answer is the same instance, which is what makes repeated completion cheap
        assertTrue(dump.statementsFor("EnergyConsumer", "p0") == dump.statementsFor("EnergyConsumer", "p0"));
    }
}
