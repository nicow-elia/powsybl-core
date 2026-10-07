/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.diff.CgmesDiffImport;
import com.powsybl.cgmes.conversion.elements.TerminalConversion;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios;
import com.powsybl.cgmes.extensions.CgmesMetadataModels;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.InMemoryCgmesModel;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.commons.report.PowsyblCoreReportResourceBundle;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.commons.test.PowsyblTestReportResourceBundle;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.triplestore.api.PropertyBag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The pass that creates the fictitious switch of every disconnected terminal (powsybl-core #4085) scans the switches of
 * the network once, not once per disconnected terminal: on a large model the scan per terminal made the import 1.6 times
 * slower. A pass without a disconnected terminal (an update of setpoints) does not scan them at all. And the scoped
 * update of a difference model runs the pass, and so its {@code terminals} query, only when the difference disconnects
 * a terminal.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class FictitiousSwitchPassTest {

    private static final int TERMINALS = 50;

    /** A node/breaker voltage level with a busbar and {@code TERMINALS} loads, each with its CGMES terminal alias. */
    private static Network network() {
        Network network = Network.create("fictitious-switch-pass", "test");
        VoltageLevel voltageLevel = network.newSubstation().setId("S").add().newVoltageLevel().setId("VL")
                .setNominalV(400.0).setTopologyKind(TopologyKind.NODE_BREAKER).add();
        voltageLevel.getNodeBreakerView().newBusbarSection().setId("BBS").setNode(0).add();
        for (int i = 1; i <= TERMINALS; i++) {
            voltageLevel.newLoad().setId("L" + i).setNode(i).setP0(1.0).setQ0(0.0).add();
            voltageLevel.getNodeBreakerView().newInternalConnection().setNode1(0).setNode2(i).add();
            network.getLoad("L" + i).addAlias("T" + i, Conversion.ALIAS_TERMINAL1);
        }
        return network;
    }

    /** The CGMES model of the update: every terminal is disconnected. */
    private static InMemoryCgmesModel disconnectedTerminals() {
        String[] ids = Stream.iterate(1, i -> i + 1).limit(TERMINALS).map(i -> "T" + i).toArray(String[]::new);
        InMemoryCgmesModel cgmes = new InMemoryCgmesModel().terminals(ids);
        cgmes.terminals().forEach(terminal -> terminal.put(CgmesNames.CONNECTED, "false"));
        return cgmes;
    }

    /** The network, counting how often its switches are scanned. */
    private static Network counting(Network network, int[] scans) {
        return (Network) Proxy.newProxyInstance(Network.class.getClassLoader(), new Class<?>[] {Network.class},
                (proxy, method, args) -> {
                    if ("getSwitchStream".equals(method.getName()) || "getSwitches".equals(method.getName())) {
                        scans[0]++;
                    }
                    try {
                        return method.invoke(network, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    void theSwitchesAreScannedOncePerPass() {
        Network network = network();
        InMemoryCgmesModel cgmes = disconnectedTerminals();
        int[] scans = {0};
        Network counted = counting(network, scans);
        Context context = new Context(cgmes, new Conversion.Config()
                .createFictitiousSwitchesForDisconnectedTerminalsMode(CgmesImport.FictitiousSwitchesCreationMode.ALWAYS),
                counted);

        Update.createFictitiousSwitchesForDisconnectedTerminalsDuringUpdate(counted, cgmes, context);
        assertEquals(1, scans[0]);
        assertEquals(TERMINALS, network.getSwitchStream().filter(TerminalConversion::isFictitiousSwitchOfATerminal).count());

        // A second update finds every switch through the same single scan and creates none
        scans[0] = 0;
        Update.createFictitiousSwitchesForDisconnectedTerminalsDuringUpdate(counted, cgmes, context);
        assertEquals(1, scans[0]);
        assertEquals(TERMINALS, network.getSwitchStream().filter(Switch::isFictitious).count());
    }

    @Test
    void aPassWithoutDisconnectedTerminalsDoesNotScanTheSwitches() {
        Network network = network();
        InMemoryCgmesModel cgmes = disconnectedTerminals();
        cgmes.terminals().forEach(terminal -> terminal.put(CgmesNames.CONNECTED, "true"));
        int[] scans = {0};
        Network counted = counting(network, scans);
        Context context = new Context(cgmes, new Conversion.Config()
                .createFictitiousSwitchesForDisconnectedTerminalsMode(CgmesImport.FictitiousSwitchesCreationMode.ALWAYS),
                counted);

        Update.createFictitiousSwitchesForDisconnectedTerminalsDuringUpdate(counted, cgmes, context);
        assertEquals(0, scans[0]);
        assertEquals(0, network.getSwitchCount());
    }

    /**
     * The single-terminal overload keeps its former cost: the switches are scanned only for a disconnected terminal,
     * after the early returns.
     */
    @Test
    void theSingleTerminalOverloadScansOnlyForADisconnectedTerminal() {
        Network network = network();
        InMemoryCgmesModel cgmes = disconnectedTerminals();
        int[] scans = {0};
        Network counted = counting(network, scans);
        Context context = new Context(cgmes, new Conversion.Config()
                .createFictitiousSwitchesForDisconnectedTerminalsMode(CgmesImport.FictitiousSwitchesCreationMode.ALWAYS),
                counted);
        PropertyBag disconnected = cgmes.terminals().get(0);
        PropertyBag connected = cgmes.terminals().get(1);
        connected.put(CgmesNames.CONNECTED, "true");

        TerminalConversion.create(counted, connected, context);
        assertEquals(0, scans[0]);
        TerminalConversion.create(counted, disconnected, context);
        assertEquals(1, scans[0]);
        assertEquals(1, network.getSwitchStream().filter(TerminalConversion::isFictitiousSwitchOfATerminal).count());

        // A second call finds the switch it created and creates none
        TerminalConversion.create(counted, disconnected, context);
        assertEquals(2, scans[0]);
        assertEquals(1, network.getSwitchStream().filter(TerminalConversion::isFictitiousSwitchOfATerminal).count());
    }

    private static final String CONVERTING_DURING_UPDATE = "core.cgmes.conversion.convertingDuringUpdateElementType";

    /** How often an update entered the fictitious switch pass, that is issued its {@code terminals} query. */
    private static long passes(ReportNode reportNode) {
        long own = CONVERTING_DURING_UPDATE.equals(reportNode.getMessageKey())
                && reportNode.getValue("elementType").map(value -> CgmesNames.TERMINAL.equals(value.getValue())).orElse(false)
                ? 1 : 0;
        return own + reportNode.getChildren().stream().mapToLong(FictitiousSwitchPassTest::passes).sum();
    }

    private static ReportNode reportNode() {
        return ReportNode.newRootReportNode()
                .withResourceBundles(PowsyblTestReportResourceBundle.TEST_BASE_NAME, PowsyblCoreReportResourceBundle.BASE_NAME)
                .withMessageTemplate("test")
                .build();
    }

    private static Network miniNodeBreaker() {
        Properties parameters = new Properties();
        parameters.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");
        return Network.read(CgmesConformity1Catalog.miniNodeBreaker().dataSource(), parameters);
    }

    private static long applied(DifferenceModelSet set, boolean scoped) {
        ReportNode reportNode = reportNode();
        CgmesDiffImport.apply(miniNodeBreaker(), set, new Conversion.Config(),
                new CgmesDiffImport.Options().setScopedUpdate(scoped), reportNode);
        return passes(reportNode);
    }

    /**
     * A rich steady state hypothesis difference (setpoints of a load and a generator, a switch opened) connects or
     * disconnects no terminal, so its scoped update issues no {@code terminals} query; the full update of the same
     * difference keeps the pass, as the update of a file does (powsybl-core #4085).
     */
    @Test
    void aScopedUpdateWithoutADisconnectionIssuesNoTerminalsQuery() {
        Network sender = miniNodeBreaker();
        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(sender, RecordedChangeScenarios.record(sender, network -> {
            Load load = network.getLoads().iterator().next();
            load.setP0(load.getP0() + 1.0).setQ0(load.getQ0() + 1.0);
            network.getGenerators().iterator().next().setTargetP(network.getGenerators().iterator().next().getTargetP() + 1.0);
            network.getSwitchStream().filter(sw -> !sw.isOpen()).findFirst().orElseThrow().setOpen(true);
        }), new CgmesDiffExport.ExportOptions());
        DifferenceModelSet set = result.differences();

        assertEquals(0, applied(set, true));
        assertEquals(1, applied(set, false));
    }

    /** A scoped difference that disconnects a node/breaker terminal still runs the pass and creates the switch. */
    @Test
    void aScopedUpdateThatDisconnectsATerminalRunsThePass() {
        Network network = miniNodeBreaker();
        String terminalId = network.getLoadStream()
                .filter(load -> load.getTerminal().getVoltageLevel().getTopologyKind() == TopologyKind.NODE_BREAKER)
                .filter(load -> load.getTerminal().isConnected())
                .findFirst().orElseThrow()
                .getAliasFromType(Conversion.ALIAS_TERMINAL1).orElseThrow();
        assertNull(network.getSwitch(terminalId + "_SW_fict"));
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
        DifferenceModelSet set = new DifferenceModelSet(List.of(DifferenceModelParser.parse(
                new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)), "x_SSH_DIFF.xml")));
        ReportNode reportNode = reportNode();

        CgmesDiffImport.apply(network, set, new Conversion.Config(), new CgmesDiffImport.Options(), reportNode);

        assertEquals(1, passes(reportNode));
        assertNotNull(network.getSwitch(terminalId + "_SW_fict"));
    }
}
