/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.commons.datasource.MemDataSource;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Properties;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The regulation target of an EquivalentInjection that is not a usable voltage (not a number, or not above zero) is
 * written as {@code 0} by every SSH export, as the full export always wrote it: the receiver of a change then reads
 * {@code 0} where it used to keep the target it had before, which was a state the sender no longer has.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class EquivalentInjectionTargetExportTest {

    private static final String DIR = "/update/boundary-line/";

    private static Network boundaryLines() {
        return readCgmesResources(DIR, "boundaryLine_EQ.xml", "boundaryLine_EQ_BD.xml", "boundaryLine_SSH.xml");
    }

    @Test
    void anUnusableTargetOfAnEquivalentInjectionReachesTheReceiverAsZero() {
        Network sender = boundaryLines();
        BoundaryLine.Generation generation = sender.getBoundaryLine("EquivalentBranch").getGeneration();
        assertEquals(405.0, generation.getTargetV(), "the fixture is expected to regulate to 405 kV");
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender,
                n -> n.getBoundaryLine("EquivalentBranch").getGeneration().setVoltageRegulationOn(false).setTargetV(Double.NaN));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PartialSshExport.write(sender, events, bytes, new PartialSshExport.ExportOptions()
                .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL));
        Network receiver = boundaryLines();
        MemDataSource dataSource = new MemDataSource();
        dataSource.putData("partial_SSH.xml", bytes.toByteArray());
        Properties parameters = new Properties();
        parameters.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");
        receiver.update(dataSource, parameters);

        BoundaryLine.Generation received = receiver.getBoundaryLine("EquivalentBranch").getGeneration();
        assertFalse(received.isVoltageRegulationOn());
        // Before, the target was left out and the receiver kept 405 kV
        assertEquals(0.0, received.getTargetV(), 0.0);
    }
}
