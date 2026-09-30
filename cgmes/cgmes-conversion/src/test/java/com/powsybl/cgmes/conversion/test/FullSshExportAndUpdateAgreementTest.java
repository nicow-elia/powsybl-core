/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.regulation.RegulationMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two behaviours in which the full steady state hypothesis export and the change export used to disagree and that
 * vanished with powsybl-core #3699 and #4106, pinned so that they stay vanished.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class FullSshExportAndUpdateAgreementTest {

    private static Network reactivePowerGenerators() {
        Network network = readCgmesResources("/issues/voltageRegulation/", "generator_EQ.xml", "generator_SSH.xml");
        network.getGenerator("SM_3").getVoltageRegulation().setRegulating(false);
        return network;
    }

    /**
     * B2: {@code RegulatingCondEq.controlEnabled} of a generator is the regulating flag of its regulation, whatever
     * the mode: a generator whose CGMES control regulates reactive power, switched on, is switched on by the update of
     * the full SSH export.
     */
    @Test
    void theUpdateSwitchesTheRegulationOfAGeneratorRegulatingReactivePowerOn() {
        Network sender = reactivePowerGenerators();
        Generator generator = sender.getGenerator("SM_3");
        assertEquals(RegulationMode.REACTIVE_POWER, generator.getVoltageRegulation().getMode(),
                "the fixture is expected to give SM_3 a control regulating reactive power");
        generator.getVoltageRegulation().setRegulating(true);

        Network receiver = FullSshExportRoundTripTest.roundTrip(sender, FullSshExportAndUpdateAgreementTest::reactivePowerGenerators);

        Generator received = receiver.getGenerator("SM_3");
        assertEquals(RegulationMode.REACTIVE_POWER, received.getVoltageRegulation().getMode());
        assertTrue(received.getVoltageRegulation().isRegulating());
    }

    /**
     * B8: a switch imported from a CGMES branch class has no {@code Switch.open} in the SSH; its state is the
     * connection status of its two terminals, each written once, connected when the switch is closed.
     */
    @Test
    void everyTerminalOfASwitchImportedFromABranchClassIsWrittenOnce() {
        Network network = readCgmesResources("/update/switch/", "switch_EQ.xml", "switch_SSH.xml");
        List<Switch> branchSwitches = network.getSwitchStream()
                .filter(sw -> Set.of(CgmesNames.AC_LINE_SEGMENT, CgmesNames.EQUIVALENT_BRANCH, CgmesNames.SERIES_COMPENSATOR)
                        .contains(sw.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS, "")))
                .toList();
        assertFalse(branchSwitches.isEmpty(), "the fixture is expected to hold switches imported from branch classes");
        branchSwitches.get(0).setOpen(true);
        String ssh = FullSshExportRoundTripTest.fullSsh(network);
        for (Switch sw : branchSwitches) {
            assertFalse(ssh.contains("rdf:about=\"#_" + sw.getId() + "\""), sw.getId() + " is written as a switch");
            for (String alias : List.of(Conversion.ALIAS_TERMINAL1, Conversion.ALIAS_TERMINAL2)) {
                String terminal = sw.getAliasFromType(alias).orElseThrow();
                Matcher matcher = Pattern.compile("<cim:Terminal rdf:about=\"#_?" + Pattern.quote(terminal.replaceFirst("^_", ""))
                        + "\">\\s*<cim:ACDCTerminal.connected>(\\w+)</cim:ACDCTerminal.connected>").matcher(ssh);
                assertTrue(matcher.find(), terminal + " is written");
                assertEquals(String.valueOf(!sw.isOpen()), matcher.group(1), terminal);
                assertFalse(matcher.find(), terminal + " is written twice");
            }
        }
    }
}
