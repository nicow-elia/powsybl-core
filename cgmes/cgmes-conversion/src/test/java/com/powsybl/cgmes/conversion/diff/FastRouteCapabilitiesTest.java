/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.Family;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.FamilySpec;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.PropertyGroup;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios.Scenario;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.DifferenceModelWriter;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the capability table honest against the documents the change exporter actually writes, and pins the network
 * free decision. The table against the SPARQL update catalogue is {@link UpdateCatalogTest}.
 *
 * <p>A statement the exporter writes that the table does not know would make the round trip of this library's own
 * documents impossible.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class FastRouteCapabilitiesTest {

    static List<Scenario> scenarios() {
        // Both profiles: the drift check has to see the equipment statements too
        return RecordedChangeScenarios.allChanges();
    }

    /**
     * Every statement this library writes has to be one the table knows, with a class the family accepts, and the
     * whole document has to pass the network free check.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void everyStatementTheExporterEmitsIsCapable(Scenario scenario) {
        for (CgmesDiffExport.DiffGranularity granularity : CgmesDiffExport.DiffGranularity.values()) {
            Network network = scenario.load();
            List<NetworkEvent> events = RecordedChangeScenarios.record(network, scenario.forwardChange());
            CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, events,
                    new CgmesDiffExport.ExportOptions()
                            .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL)
                            .setGranularity(granularity));
            for (DifferenceModel model : result.differences().models().values()) {
                // The class hint only exists on a parsed document with typed node elements: the writer of this
                // library emits rdf:Description, so the hint has to be read back rather than taken from the
                // in-process statements, whose className is what the exporter believed
                DifferenceModel parsed = DifferenceModelParser.parse(DifferenceModelWriter.toString(model));
                int hints = 0;
                for (CgmesStatement statement : concat(model.forward(), model.reverse())) {
                    assertTrue(FastRouteCapabilities.isUpdatableProperty(statement.property()),
                            () -> scenario.name() + " writes " + statement.property() + ", which is not in the table");
                    assertTrue(acceptsClass(statement),
                            () -> scenario.name() + " writes " + statement.property() + " on a "
                                    + statement.className() + ", which no family of that property accepts");
                    hints += statement.className() != null ? 1 : 0;
                }
                assertTrue(hints > 0, () -> scenario.name() + " writes no class at all, so the class check above"
                        + " asserted nothing");
                for (CgmesStatement statement : concat(parsed.forward(), parsed.reverse())) {
                    assertTrue(FastRouteCapabilities.isUpdatableProperty(statement.property())
                            || statement.isType(),
                            () -> scenario.name() + " writes " + statement.property() + " into its document");
                }
            }
            assertEquals(CgmesDiffImport.Route.FAST,
                    FastRouteCapabilities.check(result.differences()).route(),
                    () -> scenario.name() + " " + granularity + ": "
                            + FastRouteCapabilities.check(result.differences()).reasons());
        }
    }

    /**
     * A full object difference is complete by construction: every consistency group it touches is stated whole, so
     * no receiver ever has to complete it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void groupsMatchTheExporterConsistencyGroups(Scenario scenario) {
        Network network = scenario.load();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, scenario.forwardChange());
        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, events,
                new CgmesDiffExport.ExportOptions()
                        .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL)
                        .setGranularity(CgmesDiffExport.DiffGranularity.FULL_OBJECT));
        for (DifferenceModel model : result.differences().models().values()) {
            for (String subject : subjects(model.forward())) {
                Set<String> properties = propertiesOf(model.forward(), subject);
                for (FamilySpec spec : familiesOf(properties)) {
                    for (PropertyGroup group : spec.groups()) {
                        if (group.properties().stream().noneMatch(properties::contains)) {
                            continue;
                        }
                        assertTrue(properties.containsAll(group.required()),
                                () -> scenario.name() + ": " + subject + " states " + properties
                                        + ", which touches the group " + group.required() + " incompletely");
                    }
                }
            }
        }
    }

    // Table driven checks of the network free rules

    @Test
    void emptyIsNoop() {
        assertEquals(CgmesDiffImport.Route.NOOP,
                FastRouteCapabilities.check(new DifferenceModelSet(List.of())).route());
        assertEquals(CgmesDiffImport.Route.NOOP,
                FastRouteCapabilities.check(set(List.of(), List.of())).route());
    }

    /** The equipment profile carries the impedances and the limits, and nothing else. */
    @Test
    void theEquipmentProfileIsAccepted() {
        DifferenceModel model = new DifferenceModel(header(CgmesSubset.EQUIPMENT),
                List.of(CgmesStatement.literal("L1", null, "ACLineSegment.r", "2")), List.of(), List.of());
        assertEquals(CgmesDiffImport.Route.FAST,
                FastRouteCapabilities.check(new DifferenceModelSet(List.of(model))).route());
    }

    @Test
    void aSteadyStatePropertyInAnEquipmentModelBlocks() {
        DifferenceModel model = new DifferenceModel(header(CgmesSubset.EQUIPMENT),
                List.of(CgmesStatement.literal("L1", null, "Switch.open", "true")), List.of(), List.of());
        CgmesDiffImport.Decision decision = FastRouteCapabilities.check(new DifferenceModelSet(List.of(model)));
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route());
        assertTrue(decision.reasons().get(0).contains("Switch.open is not a EQ property"),
                decision.reasons().toString());
    }

    @Test
    void anEquipmentPropertyInASteadyStateModelBlocks() {
        CgmesDiffImport.Decision decision = FastRouteCapabilities.check(set(
                List.of(CgmesStatement.literal("L1", null, "ACLineSegment.r", "2")), List.of()));
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route());
        assertTrue(decision.reasons().get(0).contains("ACLineSegment.r is not a SSH property"),
                decision.reasons().toString());
    }

    @Test
    void aProfileWithNoUpdatePathAtAllBlocks() {
        DifferenceModel model = new DifferenceModel(header(CgmesSubset.TOPOLOGY),
                List.of(CgmesStatement.literal("T1", null, "Terminal.TopologicalNode", "TN")), List.of(), List.of());
        CgmesDiffImport.Decision decision = FastRouteCapabilities.check(new DifferenceModelSet(List.of(model)));
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route());
        assertTrue(decision.reasons().get(0).contains("TP profile cannot be updated in place"),
                decision.reasons().toString());
    }

    /**
     * Where a limit value lives is decided by the CIM version: the equipment profile in CGMES 2.4.15, the steady
     * state hypothesis in CGMES 3. A document that puts it in the other one describes something no receiver reads.
     */
    @Test
    void aLimitValueInTheWrongProfileForItsCimVersionBlocks() {
        DifferenceModel cim100InEq = new DifferenceModel(
                DifferenceModelHeader.builder("urn:uuid:m", CgmesSubset.EQUIPMENT,
                        CgmesNamespace.CIM_100_NAMESPACE).build(),
                List.of(CgmesStatement.literal("CL", null, "CurrentLimit.value", "800")), List.of(), List.of());
        CgmesDiffImport.Decision inEq = FastRouteCapabilities.check(new DifferenceModelSet(List.of(cim100InEq)));
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, inEq.route());
        assertTrue(inEq.reasons().get(0).contains("in CGMES 3 limit values are steady state data"),
                inEq.reasons().toString());

        DifferenceModel cim16InSsh = new DifferenceModel(
                DifferenceModelHeader.builder("urn:uuid:m", CgmesSubset.STEADY_STATE_HYPOTHESIS,
                        CgmesNamespace.CIM_16_NAMESPACE).build(),
                List.of(CgmesStatement.literal("CL", null, "CurrentLimit.value", "800")), List.of(), List.of());
        CgmesDiffImport.Decision inSsh = FastRouteCapabilities.check(new DifferenceModelSet(List.of(cim16InSsh)));
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, inSsh.route());
        assertTrue(inSsh.reasons().get(0).contains("in CGMES 2.4.15 limit values are equipment data"),
                inSsh.reasons().toString());
    }

    @Test
    void unknownPropertyBlocks() {
        CgmesDiffImport.Decision decision = FastRouteCapabilities.check(set(
                List.of(CgmesStatement.literal("L1", null, "EnergyConsumer.pfixed", "1")), List.of()));
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route());
        assertTrue(decision.reasons().get(0).contains("not part of the in-place update"), decision.reasons().toString());
    }

    @Test
    void stateVariablePropertyBlocksWithItsReason() {
        CgmesDiffImport.Decision decision = FastRouteCapabilities.check(set(
                List.of(CgmesStatement.literal("T1", null, "SvPowerFlow.p", "1")), List.of()));
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route());
        assertTrue(decision.reasons().get(0).contains("state variable"), decision.reasons().toString());
    }

    @Test
    void reverseOnlyRequiredPropertyBlocks() {
        CgmesDiffImport.Decision decision = FastRouteCapabilities.check(set(
                List.of(CgmesStatement.literal("L1", null, "EnergyConsumer.p", "1")),
                List.of(CgmesStatement.literal("L1", null, "EnergyConsumer.p", "2"),
                        CgmesStatement.literal("L1", null, "EnergyConsumer.q", "3"))));
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route());
        assertTrue(decision.reasons().get(0).contains("removing a property value"), decision.reasons().toString());
    }

    @Test
    void reverseOnlyOptionalPropertyIsTolerated() {
        // EquivalentInjection.regulationTarget is read in a nested optional block, so a state without it is a state
        // a CGMES file can describe
        assertEquals(CgmesDiffImport.Route.FAST, FastRouteCapabilities.check(set(
                List.of(CgmesStatement.literal("E1", null, "EquivalentInjection.p", "1"),
                        CgmesStatement.literal("E1", null, "EquivalentInjection.q", "2")),
                List.of(CgmesStatement.literal("E1", null, "EquivalentInjection.p", "3"),
                        CgmesStatement.literal("E1", null, "EquivalentInjection.q", "4"),
                        CgmesStatement.literal("E1", null, "EquivalentInjection.regulationTarget", "400")))).route());
    }

    @Test
    void structuralStatementsBlock() {
        // A type stated only in the reverse direction is the removal of an object
        CgmesDiffImport.Decision removal = FastRouteCapabilities.check(set(List.of(),
                List.of(CgmesStatement.reference("L1", null, CgmesStatement.RDF_TYPE, "ConformLoad"))));
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, removal.route());
        assertTrue(removal.reasons().get(0).contains("object removal"), removal.reasons().toString());
    }

    @Test
    void typedAttributeChangeIsTolerated() {
        assertEquals(CgmesDiffImport.Route.FAST, FastRouteCapabilities.check(set(
                List.of(CgmesStatement.reference("L1", null, CgmesStatement.RDF_TYPE, "ConformLoad"),
                        CgmesStatement.literal("L1", null, "EnergyConsumer.p", "1"),
                        CgmesStatement.literal("L1", null, "EnergyConsumer.q", "2")),
                List.of(CgmesStatement.reference("L1", null, CgmesStatement.RDF_TYPE, "ConformLoad"),
                        CgmesStatement.literal("L1", null, "EnergyConsumer.p", "3"),
                        CgmesStatement.literal("L1", null, "EnergyConsumer.q", "4")))).route());
    }

    @Test
    void severalValuesForOnePropertyBlock() {
        CgmesDiffImport.Decision decision = FastRouteCapabilities.check(set(
                List.of(CgmesStatement.literal("L1", null, "EnergyConsumer.p", "1"),
                        CgmesStatement.literal("L1", null, "EnergyConsumer.p", "2")), List.of()));
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route());
        assertTrue(decision.reasons().get(0).contains("several values"), decision.reasons().toString());
    }

    @Test
    void familiesOfSharedProperties() {
        assertEquals(Set.of(Family.TERMINAL, Family.DC_TERMINAL),
                FastRouteCapabilities.familiesOf("ACDCTerminal.connected"));
        assertEquals(Set.of(Family.RATIO_TAP_CHANGER, Family.PHASE_TAP_CHANGER),
                FastRouteCapabilities.familiesOf("TapChanger.step"));
        assertEquals(Set.of(), FastRouteCapabilities.familiesOf("Nothing.atAll"));
    }

    private static DifferenceModelSet set(List<CgmesStatement> forward, List<CgmesStatement> reverse) {
        return new DifferenceModelSet(List.of(new DifferenceModel(
                header(CgmesSubset.STEADY_STATE_HYPOTHESIS), forward, reverse, List.of())));
    }

    private static DifferenceModelHeader header(CgmesSubset subset) {
        return DifferenceModelHeader.builder("urn:uuid:test", subset, CgmesNamespace.CIM_16_NAMESPACE).build();
    }

    private static List<CgmesStatement> concat(List<CgmesStatement> a, List<CgmesStatement> b) {
        List<CgmesStatement> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static Set<String> subjects(List<CgmesStatement> statements) {
        Set<String> subjects = new LinkedHashSet<>();
        statements.forEach(statement -> subjects.add(statement.subjectId()));
        return subjects;
    }

    private static Set<String> propertiesOf(List<CgmesStatement> statements, String subject) {
        Set<String> properties = new LinkedHashSet<>();
        statements.stream().filter(statement -> statement.subjectId().equals(subject))
                .forEach(statement -> properties.add(statement.property()));
        return properties;
    }

    /** The families that could carry the given set of properties, that is the ones that know all of them. */
    private static List<FamilySpec> familiesOf(Set<String> properties) {
        return FastRouteCapabilities.table().stream()
                .filter(spec -> spec.properties().containsAll(properties))
                .toList();
    }

    private static boolean acceptsClass(CgmesStatement statement) {
        if (statement.className() == null) {
            return true;
        }
        return FastRouteCapabilities.familiesOf(statement.property()).stream()
                .anyMatch(family -> FastRouteCapabilities.spec(family).rdfTypes().contains(statement.className()));
    }
}
