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
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios.Scenario;
import com.powsybl.cgmes.conversion.test.SteadyStateFingerprint;
import com.powsybl.cgmes.extensions.CgmesMetadataModels;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.DifferenceModelWriter;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.SwitchKind;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.serde.NetworkSerDe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restricting a difference model update to the equipment it touches must not change its result.
 *
 * <p>The scope is a pure performance measure: it exists so that a one switch change on a large network does not walk
 * every element of it. This test therefore applies every recorded scenario twice, once scoped and once not, and
 * compares the two networks as XIIDM &mdash; the finest grained comparison available.</p>
 *
 * <p>Both granularities are covered: {@code CHANGED_ONLY} additionally exercises the completion path, which runs
 * the export context and the probes of the receiving network.</p>
 *
 * <p>The fixtures carry no state variables, which matters: with solved values present the two runs differ on purpose,
 * because the unscoped update resets the state variables of every element it visits while the scoped one leaves the
 * elements it does not visit alone. That deliberate deviation is documented in the import documentation.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class UpdateScopeEquivalenceTest {

    static Stream<Arguments> scenarios() {
        List<Arguments> arguments = new ArrayList<>();
        for (Scenario scenario : RecordedChangeScenarios.all()) {
            for (CgmesDiffExport.DiffGranularity granularity : CgmesDiffExport.DiffGranularity.values()) {
                arguments.add(Arguments.of(scenario, granularity));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("scenarios")
    void scopedAndUnscopedUpdatesAgree(Scenario scenario, CgmesDiffExport.DiffGranularity granularity) {
        Network sender = scenario.load();
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender, scenario.forwardChange());
        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(sender, events,
                new CgmesDiffExport.ExportOptions()
                        .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL)
                        .setGranularity(granularity));
        List<DifferenceModel> models = new ArrayList<>();
        result.differences().models().values()
                .forEach(model -> models.add(DifferenceModelParser.parse(DifferenceModelWriter.toString(model))));
        DifferenceModelSet set = new DifferenceModelSet(models);

        Properties parameters = new Properties();
        parameters.putAll(scenario.importParams());
        parameters.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");

        Network scoped = scenario.load();
        Network unscoped = scenario.load();
        CgmesDiffImport.apply(scoped, set, config(parameters), new CgmesDiffImport.Options().setScopedUpdate(true),
                ReportNode.NO_OP);
        CgmesDiffImport.apply(unscoped, set, config(parameters), new CgmesDiffImport.Options().setScopedUpdate(false),
                ReportNode.NO_OP);

        assertEquals(xiidm(unscoped), xiidm(scoped),
                () -> "the scoped update of " + scenario.name() + " (" + granularity
                        + ") gives another network than the full one");
    }

    /**
     * Since powsybl-core #4085 an update that disconnects a terminal of a node/breaker voltage level creates the
     * fictitious switch {@code <terminal>_SW_fict} of that terminal, closed in every variant and open in the working
     * one. The scoped update does not list the switch in its scope (it did not exist when the scope was computed), so
     * {@code updateSwitches} leaves it as the creation made it, which is the state the full update ends with too.
     */
    /** A difference that disconnects the given terminal, applied through the update workflow. */
    private static DifferenceModelSet disconnecting(Network network, String terminalId) {
        String supersedes = network.getExtension(CgmesMetadataModels.class)
                .getModelForSubset(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow().getId();
        String document = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xmlns:dm="http://iec.ch/TC57/61970-552/DifferenceModel/1#">
                  <dm:DifferenceModel rdf:about="urn:uuid:disconnect-a-node-breaker-terminal">
                    <md:Model.Supersedes rdf:resource="%s"/>
                    <dm:forwardDifferences rdf:parseType="Statements">
                      <rdf:Description rdf:about="#_%s">
                        <cim:ACDCTerminal.connected>false</cim:ACDCTerminal.connected>
                      </rdf:Description>
                    </dm:forwardDifferences>
                    <dm:reverseDifferences rdf:parseType="Statements">
                      <rdf:Description rdf:about="#_%s">
                        <cim:ACDCTerminal.connected>true</cim:ACDCTerminal.connected>
                      </rdf:Description>
                    </dm:reverseDifferences>
                  </dm:DifferenceModel>
                </rdf:RDF>
                """.formatted(supersedes, terminalId, terminalId);
        return new DifferenceModelSet(List.of(DifferenceModelParser.parse(
                new java.io.ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)), "x_SSH_DIFF.xml")));
    }

    private static Network miniNodeBreaker(Properties parameters) {
        return Network.read(CgmesConformity1Catalog.miniNodeBreaker().dataSource(), parameters);
    }

    private static String connectedNodeBreakerLoadTerminal(Network network) {
        return network.getLoadStream()
                .filter(l -> l.getTerminal().getVoltageLevel().getTopologyKind() == TopologyKind.NODE_BREAKER)
                .filter(l -> l.getTerminal().isConnected())
                .findFirst().orElseThrow()
                .getAliasFromType(Conversion.ALIAS_TERMINAL1).orElseThrow();
    }

    /** Re-create the fictitious switch of the terminal under another identifier, with the properties the import sets. */
    private static void renameTheFictitiousSwitch(Network network, String terminalId) {
        Switch created = network.getSwitch(terminalId + "_SW_fict");
        VoltageLevel.NodeBreakerView view = created.getVoltageLevel().getNodeBreakerView();
        int node1 = view.getNode1(created.getId());
        int node2 = view.getNode2(created.getId());
        view.removeSwitch(created.getId());
        Switch renamed = view.newSwitch().setId("renamed-fictitious-switch").setNode1(node1).setNode2(node2)
                .setKind(SwitchKind.BREAKER).setOpen(true).setFictitious(true).add();
        renamed.setProperty(Conversion.PROPERTY_IS_CREATED_FOR_DISCONNECTED_TERMINAL, "true");
        renamed.setProperty(Conversion.PROPERTY_TERMINAL, terminalId);
    }

    private static long fictitiousSwitchesOf(Network network, String terminalId) {
        return network.getSwitchStream()
                .filter(s -> "true".equals(s.getProperty(Conversion.PROPERTY_IS_CREATED_FOR_DISCONNECTED_TERMINAL)))
                .filter(s -> terminalId.equals(s.getProperty(Conversion.PROPERTY_TERMINAL)))
                .count();
    }

    /**
     * Since powsybl-core #4085 an update that disconnects a terminal of a node/breaker voltage level creates the
     * fictitious switch {@code <terminal>_SW_fict} of that terminal, closed in every variant and open in the working
     * one. The scoped update does not list the switch in its scope (it did not exist when the scope was computed), so
     * {@code updateSwitches} leaves it as the creation made it, which is the state the full update ends with too.
     */
    @Test
    void disconnectingANodeBreakerTerminalCreatesTheFictitiousSwitch() {
        Properties parameters = new Properties();
        parameters.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");
        Network scoped = miniNodeBreaker(parameters);
        Network unscoped = miniNodeBreaker(parameters);
        String terminalId = connectedNodeBreakerLoadTerminal(scoped);
        assertNull(scoped.getSwitch(terminalId + "_SW_fict"), "the fixture is expected to have no such switch yet");
        DifferenceModelSet set = disconnecting(scoped, terminalId);

        CgmesDiffImport.apply(scoped, set, config(parameters), new CgmesDiffImport.Options().setScopedUpdate(true),
                ReportNode.NO_OP);
        CgmesDiffImport.apply(unscoped, set, config(parameters), new CgmesDiffImport.Options().setScopedUpdate(false),
                ReportNode.NO_OP);

        for (Network network : List.of(scoped, unscoped)) {
            Switch created = network.getSwitch(terminalId + "_SW_fict");
            assertNotNull(created, "the update is expected to create the fictitious switch of the terminal");
            assertTrue(created.isOpen());
        }
        // The steady state hypothesis, including the terminal connection of every connectable; the solved state is
        // not compared: the full update clears it for the whole network, the scoped one only for what it touched
        assertEquals(SteadyStateFingerprint.of(unscoped), SteadyStateFingerprint.of(scoped));
    }

    /**
     * The terminals that already have a fictitious switch are indexed once per update (the index replaces a scan of
     * every switch per disconnected terminal). The index recognises a switch by the properties the creation sets, like
     * the scan did, not by its identifier, which may differ when identifier unicity is ensured: a switch created by an
     * earlier update under another identifier is found, and a second update creates none.
     */
    @Test
    void anExistingFictitiousSwitchIsFoundByItsPropertiesWhateverItsIdentifier() {
        Properties parameters = new Properties();
        parameters.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");
        Network network = miniNodeBreaker(parameters);
        String terminalId = connectedNodeBreakerLoadTerminal(network);
        DifferenceModelSet set = disconnecting(network, terminalId);
        CgmesDiffImport.apply(network, set, config(parameters), new CgmesDiffImport.Options().setScopedUpdate(false),
                ReportNode.NO_OP);
        assertEquals(1, fictitiousSwitchesOf(network, terminalId));
        renameTheFictitiousSwitch(network, terminalId);

        CgmesDiffImport.apply(network, disconnecting(network, terminalId), config(parameters),
                new CgmesDiffImport.Options().setScopedUpdate(false).setCheckSupersedes(false), ReportNode.NO_OP);

        assertEquals(1, fictitiousSwitchesOf(network, terminalId));
        assertNull(network.getSwitch(terminalId + "_SW_fict"));
    }

    /**
     * The variant-safe gate of a terminal (a disconnection creates the fictitious switch, which exists in every
     * variant) looks for an existing switch by its properties, as the import does, not by its usual identifier
     * (review 21 finding m10).
     */
    @Test
    void aRenamedFictitiousSwitchIsFoundByTheVariantSafeGate() {
        Properties parameters = new Properties();
        parameters.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");
        Network network = miniNodeBreaker(parameters);
        String terminalId = connectedNodeBreakerLoadTerminal(network);
        CgmesDiffImport.apply(network, disconnecting(network, terminalId), config(parameters),
                new CgmesDiffImport.Options().setScopedUpdate(false), ReportNode.NO_OP);
        renameTheFictitiousSwitch(network, terminalId);

        CgmesDiffImport.apply(network, disconnecting(network, terminalId), config(parameters),
                new CgmesDiffImport.Options().setVariantSafeOnly(true).setCheckSupersedes(false), ReportNode.NO_OP);
        assertEquals(1, fictitiousSwitchesOf(network, terminalId));
    }

    private static com.powsybl.cgmes.conversion.Conversion.Config config(Properties parameters) {
        return new CgmesImport().config(parameters);
    }

    private static String xiidm(Network network) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NetworkSerDe.write(network, bytes);
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
