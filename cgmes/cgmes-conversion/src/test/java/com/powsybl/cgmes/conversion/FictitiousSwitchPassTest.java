/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion;

import com.powsybl.cgmes.conversion.elements.TerminalConversion;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.InMemoryCgmesModel;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.triplestore.api.PropertyBag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pass that creates the fictitious switch of every disconnected terminal (powsybl-core #4085) scans the switches of
 * the network once, not once per disconnected terminal (review 21 B2 and round 2 r2-m1): on a large model the scan per
 * terminal made the import 1.6 times slower.
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

    /**
     * The single-terminal overload keeps upstream's cost: the switches are scanned only for a disconnected terminal,
     * after the early returns (review 21 closing, c-m7).
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
}
