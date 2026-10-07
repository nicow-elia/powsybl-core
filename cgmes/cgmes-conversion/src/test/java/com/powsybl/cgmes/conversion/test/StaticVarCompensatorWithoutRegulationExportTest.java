/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.commons.datasource.DirectoryDataSource;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.test.SvcTestCaseFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Since powsybl-core #3699 a static var compensator may have no {@code VoltageRegulation}; the full CGMES export
 * refused it with "Invalid regulation mode for Static Var Compensator null". Without a regulation the compensator holds
 * its local reactive power target, which is how IIDM itself answers ({@code isWithMode(REACTIVE_POWER)}), so its
 * control mode is exported as reactive power (pypowsybl review round 2).
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class StaticVarCompensatorWithoutRegulationExportTest {

    @TempDir
    Path tmpDir;

    @Test
    void aCompensatorWithoutRegulationIsExported() {
        Network network = SvcTestCaseFactory.create();
        StaticVarCompensator svc = network.getStaticVarCompensator("SVC2");
        svc.removeVoltageRegulation();
        double localTargetQ = 5.0;
        svc.setLocalTargetQ(localTargetQ);

        network.write("CGMES", new Properties(), tmpDir.resolve("svc"));

        StaticVarCompensator back = Network.read(new DirectoryDataSource(tmpDir, "svc")).getStaticVarCompensator("SVC2");
        assertNotNull(back);
        assertFalse(back.isRegulating());
        assertEquals(localTargetQ, back.getLocalTargetQ(), 1e-6);
    }
}
