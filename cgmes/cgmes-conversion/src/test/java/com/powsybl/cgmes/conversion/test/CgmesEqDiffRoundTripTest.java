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
import com.powsybl.cgmes.conversion.diff.CgmesDiffImport;
import com.powsybl.cgmes.conversion.diff.CgmesDiffNotApplicableException;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios.Scenario;
import com.powsybl.cgmes.extensions.CgmesMetadataModels;
import com.powsybl.cgmes.model.CgmesMetadataModel;
import com.powsybl.cgmes.model.CgmesModel;
import com.powsybl.cgmes.model.CgmesModelFactory;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.DifferenceModelWriter;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.datasource.ResourceDataSource;
import com.powsybl.commons.datasource.ResourceSet;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.OperationalLimitsGroup;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.triplestore.api.TripleStoreFactory;
import com.powsybl.triplestore.api.TripleStoreOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.stream.Stream;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The round trip of the equipment values of work package 5: operational limits of a CGMES 2.4.15 model, branch
 * impedances and voltage level limits.
 *
 * <p>The steady state scenarios are covered by {@code CgmesDiffRoundTripTest}, which additionally compares every
 * change with the partial steady state hypothesis file of the same change. That comparison is impossible here: a
 * partial file carries steady state values only, and refusing to write an equipment value is exactly what the export
 * does. What is asserted instead is the property that matters: a receiver that applies the difference ends up where
 * the sender is, and undoing it takes the receiver back to where it started.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesEqDiffRoundTripTest {

    private static final ZonedDateTime SCENARIO_TIME = ZonedDateTime.parse("2024-02-21T11:00:00Z");
    private static final ZonedDateTime CREATED = ZonedDateTime.parse("2026-09-17T08:00:00Z");

    private static final String LINE_DIR = "/update/line/";
    private static final String[] LINE_FILES = {"line_EQ.xml", "line_SSH.xml"};
    private static final String LIMITS_DIR = "/issues/operational-limits/";
    private static final String AC_LINE_SEGMENT = "ACLineSegment";

    static List<Scenario> equipmentScenarios() {
        return RecordedChangeScenarios.equipmentChanges();
    }

    static Stream<Arguments> scenarios() {
        List<Arguments> arguments = new ArrayList<>();
        for (Scenario scenario : RecordedChangeScenarios.equipmentChanges()) {
            for (CgmesDiffExport.DiffGranularity granularity : CgmesDiffExport.DiffGranularity.values()) {
                arguments.add(Arguments.of(scenario, granularity));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("scenarios")
    void equipmentDifferenceRoundTrip(Scenario scenario, CgmesDiffExport.DiffGranularity granularity) {
        Network sender = scenario.load();
        Network receiver = scenario.load();
        SortedMap<String, String> original = SteadyStateFingerprint.of(receiver);
        ZonedDateTime caseDate = receiver.getCaseDate();
        int forecastDistance = receiver.getForecastDistance();
        Optional<String> sshModelBefore = modelId(receiver, CgmesSubset.STEADY_STATE_HYPOTHESIS);

        List<NetworkEvent> events = RecordedChangeScenarios.record(sender, scenario.forwardChange());
        assertTrue(!events.isEmpty(), () -> "scenario " + scenario.name() + " recorded no change");
        DifferenceModelSet parsed = DifferenceRoundTrip.exportAndParse(sender, events, granularity, SCENARIO_TIME, CREATED);

        Properties parameters = applyParameters(scenario);
        assertEquals(CgmesDiffImport.Route.FAST, CgmesDiffImport.canApplyInPlace(receiver, parsed).route(),
                () -> CgmesDiffImport.canApplyInPlace(receiver, parsed).reasons().toString());
        assertEquals(CgmesDiffImport.Route.FAST,
                CgmesDiffImport.apply(receiver, parsed, parameters, ReportNode.NO_OP).route());

        Map<String, String[]> differences =
                SteadyStateFingerprint.diff(SteadyStateFingerprint.of(sender), SteadyStateFingerprint.of(receiver));
        assertEquals(Set.of(), differences.keySet(),
                () -> "the receiver of " + scenario.name() + " does not match the sender: " + describe(differences));

        // An equipment difference carries no dated steady state model, and the update must not invent one
        boolean equipmentOnly = parsed.models().values().stream()
                .noneMatch(model -> model.header().subset() == CgmesSubset.STEADY_STATE_HYPOTHESIS);
        if (equipmentOnly) {
            assertEquals(caseDate, receiver.getCaseDate(), () -> scenario.name() + " lost its case date");
            assertEquals(forecastDistance, receiver.getForecastDistance());
            assertEquals(sshModelBefore, modelId(receiver, CgmesSubset.STEADY_STATE_HYPOTHESIS),
                    () -> scenario.name() + " changed the steady state hypothesis model of the receiver");
        }

        // The equipment difference is what the network is at afterwards
        parsed.get(CgmesSubset.EQUIPMENT).ifPresent(model ->
                assertEquals(Optional.of(model.header().id()), modelId(receiver, CgmesSubset.EQUIPMENT),
                        () -> scenario.name() + " did not register the equipment difference"));

        assertEquals(CgmesDiffImport.Route.FAST,
                CgmesDiffImport.revert(receiver, parsed, parameters, ReportNode.NO_OP).route());
        Map<String, String[]> restored =
                SteadyStateFingerprint.diff(original, SteadyStateFingerprint.of(receiver));
        assertEquals(Set.of(), restored.keySet(),
                () -> "revert did not restore " + scenario.name() + ": " + describe(restored));
    }

    /**
     * A difference whose statements are all refused leaves the network exactly as it was: the plan is built before
     * anything is written, so a refusal costs nothing.
     */
    @Test
    void nothingIsModifiedWhenOneStatementBlocks() {
        Network receiver = readCgmesResources(LINE_DIR, LINE_FILES);
        SortedMap<String, String> before = SteadyStateFingerprint.of(receiver);
        String document = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:cim="http://iec.ch/TC57/CIM100#"
                         xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
                         xmlns:dm="http://iec.ch/TC57/61970-552/DifferenceModel/1#">
                  <dm:DifferenceModel rdf:about="urn:uuid:blocked-eq">
                    <md:Model.profile>http://iec.ch/TC57/ns/CIM/EquipmentCore-EU/3.0</md:Model.profile>
                    <dm:forwardDifferences rdf:parseType="Statements">
                      <rdf:Description rdf:about="#_ACLineSegment">
                        <cim:ACLineSegment.r>1.6</cim:ACLineSegment.r>
                      </rdf:Description>
                      <rdf:Description rdf:about="#_NoSuchObject">
                        <cim:ACLineSegment.x>2.0</cim:ACLineSegment.x>
                      </rdf:Description>
                    </dm:forwardDifferences>
                  </dm:DifferenceModel>
                </rdf:RDF>
                """;
        assertThrows(CgmesDiffNotApplicableException.class,
            () -> CgmesDiffImport.apply(receiver, document, new Properties(), ReportNode.NO_OP));
        assertEquals(Set.of(), SteadyStateFingerprint.diff(before, SteadyStateFingerprint.of(receiver)).keySet());
    }

    /**
     * In CGMES 2.4.15 a limit value is an equipment value, so applying a difference has to move the equipment value
     * the importer remembers as well. Otherwise a later steady state update that carries no value for that limit
     * would quietly restore the value the difference replaced.
     */
    @Test
    void cim16ApplyUpdatesNormalValueProperties() {
        Network sender = readCgmesResources(LIMITS_DIR, "loading_limits.xml");
        Network receiver = readCgmesResources(LIMITS_DIR, "loading_limits.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender,
                n -> n.getLine("ACL").getCurrentLimits1().orElseThrow().setPermanentLimit(110.0));
        DifferenceModelSet parsed = DifferenceRoundTrip.exportAndParse(sender, events,
                CgmesDiffExport.DiffGranularity.FULL_OBJECT, SCENARIO_TIME, CREATED);
        assertEquals(CgmesDiffImport.Route.FAST,
                CgmesDiffImport.apply(receiver, parsed, new Properties(), ReportNode.NO_OP).route());

        OperationalLimitsGroup group =
                receiver.getLine("ACL").getOperationalLimitsGroups1().iterator().next();
        assertEquals(110.0, Double.parseDouble(group.getProperty(Conversion.getOperationalLimitPropertyName(
                "CurrentLimit", true, 0, CgmesNames.NORMAL_VALUE))), 1e-9);
        assertEquals(110.0, receiver.getLine("ACL").getCurrentLimits1().orElseThrow().getPermanentLimit(), 1e-9);
    }

    /**
     * A voltage limit outside the range of the voltage level is refused by the receiving conversion, which is why
     * the export refuses it too. A hand built difference proves that the receiver really behaves that way.
     */
    @Test
    void aVoltageLimitOutsideTheVoltageLevelRangeIsIgnoredByTheReceiver() {
        Network receiver = readCgmesResources("/update/voltage-level/",
                "voltageLevel_EQ.xml", "voltageLevel_SSH.xml");
        double before = receiver.getVoltageLevel("VL_1").getHighVoltageLimit();
        String document = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:cim="http://iec.ch/TC57/CIM100#"
                         xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
                         xmlns:dm="http://iec.ch/TC57/61970-552/DifferenceModel/1#">
                  <dm:DifferenceModel rdf:about="urn:uuid:out-of-range">
                    <md:Model.profile>http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/3.0</md:Model.profile>
                    <dm:forwardDifferences rdf:parseType="Statements">
                      <rdf:Description rdf:about="#_VL_H_11">
                        <cim:VoltageLimit.value>425</cim:VoltageLimit.value>
                      </rdf:Description>
                      <rdf:Description rdf:about="#_VL_H_12">
                        <cim:VoltageLimit.value>425</cim:VoltageLimit.value>
                      </rdf:Description>
                    </dm:forwardDifferences>
                  </dm:DifferenceModel>
                </rdf:RDF>
                """;
        Properties parameters = new Properties();
        parameters.put(CgmesImport.DIFF_CHECK_SUPERSEDES, "false");
        assertEquals(CgmesDiffImport.Route.FAST,
                CgmesDiffImport.apply(receiver, document, parameters, ReportNode.NO_OP).route());
        // 425 is not strictly inside the (380, 420) range the VoltageLevel declares, so the conversion drops it
        assertEquals(before, receiver.getVoltageLevel("VL_1").getHighVoltageLimit(), 1e-9);
    }

    /**
     * A steady state difference and an equipment difference of the same network, applied one after the other and
     * undone in the opposite order, leave the network exactly where it started.
     */
    @Test
    void chainSshDiffThenEqDiffThenRevertBoth() {
        Network sender = readCgmesResources(LINE_DIR, LINE_FILES);
        Network receiver = readCgmesResources(LINE_DIR, LINE_FILES);
        SortedMap<String, String> original = SteadyStateFingerprint.of(receiver);
        Properties parameters = new Properties();
        parameters.put(CgmesImport.DIFF_CHECK_SUPERSEDES, "false");

        List<NetworkEvent> limitChange = RecordedChangeScenarios.record(sender,
                n -> n.getLine(AC_LINE_SEGMENT).getCurrentLimits1().orElseThrow().setPermanentLimit(850.0));
        var steadyState = DifferenceRoundTrip.exportAndParse(sender, limitChange,
                CgmesDiffExport.DiffGranularity.FULL_OBJECT, SCENARIO_TIME, CREATED);
        assertEquals(CgmesDiffImport.Route.FAST,
                CgmesDiffImport.apply(receiver, steadyState, parameters, ReportNode.NO_OP).route());

        List<NetworkEvent> impedanceChange = RecordedChangeScenarios.record(sender,
                n -> n.getLine(AC_LINE_SEGMENT).setR(1.6));
        var equipment = DifferenceRoundTrip.exportAndParse(sender, impedanceChange,
                CgmesDiffExport.DiffGranularity.FULL_OBJECT, SCENARIO_TIME, CREATED);
        assertEquals(CgmesDiffImport.Route.FAST,
                CgmesDiffImport.apply(receiver, equipment, parameters, ReportNode.NO_OP).route());

        assertEquals(Set.of(), SteadyStateFingerprint
                .diff(SteadyStateFingerprint.of(sender), SteadyStateFingerprint.of(receiver)).keySet());
        assertNotEquals(Set.of(), SteadyStateFingerprint
                .diff(original, SteadyStateFingerprint.of(receiver)).keySet());

        CgmesDiffImport.revert(receiver, equipment, parameters, ReportNode.NO_OP);
        CgmesDiffImport.revert(receiver, steadyState, parameters, ReportNode.NO_OP);
        Map<String, String[]> restored =
                SteadyStateFingerprint.diff(original, SteadyStateFingerprint.of(receiver));
        assertEquals(Set.of(), restored.keySet(), () -> "the chain was not undone: " + describe(restored));
    }

    // The slow route: the same difference applied to the RDF data the network was read from

    /**
     * The store level route, which a database layer and the re-import of a drifted equipment model use: the very same
     * difference is applied to the triple store of the base files with SPARQL UPDATE and the model is converted
     * again. Both routes have to end on the same values, and the re-import must still hold every object &mdash; an
     * equipment value a conversion refuses makes the whole object disappear, which the fast route (a setter) could
     * never show.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("equipmentScenarios")
    void applyEqualsSlowRouteReimport(Scenario scenario) {
        Network sender = scenario.load();
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender, scenario.forwardChange());
        DifferenceModelSet parsed = DifferenceRoundTrip.exportAndParse(sender, events,
                CgmesDiffExport.DiffGranularity.FULL_OBJECT, SCENARIO_TIME, CREATED);

        Network base = scenario.load();
        Network fast = scenario.load();
        Properties parameters = applyParameters(scenario);
        parameters.put(CgmesImport.DIFF_CHECK_SUPERSEDES, "false");
        assertEquals(CgmesDiffImport.Route.FAST,
                CgmesDiffImport.apply(fast, parsed, parameters, ReportNode.NO_OP).route());
        Map<String, String[]> changed =
                SteadyStateFingerprint.diff(SteadyStateFingerprint.of(base), SteadyStateFingerprint.of(fast));
        assertFalse(changed.isEmpty(), () -> scenario.name() + " changed nothing at all");

        Network reimported = reimportThroughTheStore(scenario, parsed);
        SortedMap<String, String> slow = SteadyStateFingerprint.of(reimported);
        SortedMap<String, String> fastValues = SteadyStateFingerprint.of(fast);

        // Nothing may disappear: a value a conversion refuses makes the importer drop the whole equipment
        base.getIdentifiables().forEach(identifiable -> assertNotNull(reimported.getIdentifiable(identifiable.getId()),
                () -> scenario.name() + ": the re-import lost " + identifiable.getId()));
        // And every value the difference moved has to have moved there too
        changed.keySet().forEach(key -> assertEquals(fastValues.get(key), slow.get(key),
                () -> scenario.name() + ": the store level route disagrees on " + key));
    }

    /** A difference of an {@code EquivalentBranch} whose base file states the impedance of both directions. */
    @Test
    void anEquivalentBranchDifferenceKeepsTheBranchOnAStoreLevelApplication() {
        String[] files = {"lineWithImpedance21_EQ.xml", "line_SSH.xml"};
        Network sender = readCgmesResources(LINE_DIR, files);
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender,
                n -> n.getLine("EquivalentBranch").setR(14.0).setX(32.0));
        DifferenceModelSet parsed = DifferenceRoundTrip.exportAndParse(sender, events,
                CgmesDiffExport.DiffGranularity.FULL_OBJECT, SCENARIO_TIME, CREATED);
        // Both directions of the impedance travel, otherwise the conversion refuses the branch
        assertTrue(parsed.get(CgmesSubset.EQUIPMENT).orElseThrow().forward().stream()
                .anyMatch(statement -> "EquivalentBranch.r21".equals(statement.property())));

        Network reimported = reimportThroughTheStore(LINE_DIR, files, parsed);
        assertNotNull(reimported.getLine("EquivalentBranch"),
                "the re-imported model lost the EquivalentBranch: its r21/x21 no longer match its r/x");
        assertEquals(14.0, reimported.getLine("EquivalentBranch").getR(), 1e-9);
        assertEquals(32.0, reimported.getLine("EquivalentBranch").getX(), 1e-9);
    }

    private static Network reimportThroughTheStore(Scenario scenario, DifferenceModelSet diffs) {
        return reimportThroughTheStore(scenario.dir(), scenario.files(), diffs);
    }

    /** Apply the difference to the triple store of the fixture with SPARQL UPDATE and convert the result. */
    private static Network reimportThroughTheStore(String dir, String[] files, DifferenceModelSet diffs) {
        ReadOnlyDataSource dataSource = new ResourceDataSource("fixture", new ResourceSet(dir, files));
        // The default query catalog, because a conversion of the patched store is a full import, not an update
        CgmesModel cgmes = CgmesModelFactory.create(dataSource, null, TripleStoreFactory.DEFAULT_IMPLEMENTATION,
                ReportNode.NO_OP, new TripleStoreOptions());
        try {
            for (DifferenceModel model : diffs.models().values()) {
                CgmesDiffImport.applyToTripleStore(cgmes.tripleStore(), model,
                        contextOf(cgmes, model.header().subset()), cgmes.getBasename());
            }
            return new Conversion(cgmes, new Conversion.Config()).convert();
        } finally {
            cgmes.close();
        }
    }

    /**
     * The graph of one profile. A fixture file may carry no profile fragment in its name, and then the single graph
     * of the store is the one to change.
     */
    private static String contextOf(CgmesModel cgmes, CgmesSubset subset) {
        List<String> named = cgmes.tripleStore().contextNames().stream().filter(subset::isValidName).toList();
        if (named.size() == 1) {
            return named.get(0);
        }
        List<String> all = List.copyOf(cgmes.tripleStore().contextNames());
        assertEquals(1, all.size(), () -> "cannot tell which of " + all + " is the " + subset.getIdentifier()
                + " graph");
        return all.get(0);
    }

    private static Optional<String> modelId(Network network, CgmesSubset subset) {
        CgmesMetadataModels models = network.getExtension(CgmesMetadataModels.class);
        return models == null ? Optional.empty()
                : models.getModelForSubset(subset).map(CgmesMetadataModel::getId);
    }

    private static Properties applyParameters(Scenario scenario) {
        Properties parameters = new Properties();
        parameters.putAll(scenario.importParams());
        parameters.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");
        return parameters;
    }

    private static String describe(Map<String, String[]> differences) {
        Map<String, String> readable = new LinkedHashMap<>();
        differences.forEach((key, values) -> readable.put(key, values[0] + " -> " + values[1]));
        return readable.toString();
    }

    /** The export/write/parse leg of a round trip, shared with the steady state round trip. */
    static final class DifferenceRoundTrip {

        private DifferenceRoundTrip() {
        }

        static DifferenceModelSet exportAndParse(
                Network sender, List<NetworkEvent> events, CgmesDiffExport.DiffGranularity granularity,
                ZonedDateTime scenarioTime, ZonedDateTime created) {
            CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(sender, events,
                    new CgmesDiffExport.ExportOptions()
                            .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL)
                            .setGranularity(granularity)
                            .setScenarioTime(scenarioTime)
                            .setCreated(created));
            List<DifferenceModel> models = new ArrayList<>();
            for (DifferenceModel model : result.differences().models().values()) {
                String xml = DifferenceModelWriter.toString(model);
                DifferenceModelXmlAssert.assertIsADifferenceModel(xml, model.header().cimNamespace());
                models.add(DifferenceModelParser.parse(xml));
            }
            return new DifferenceModelSet(models);
        }
    }
}
