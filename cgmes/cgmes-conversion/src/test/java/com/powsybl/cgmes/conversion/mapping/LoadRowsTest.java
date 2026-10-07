/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.mapping;

import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The rows of a load family read back what they write: the import of the values the export writes gives the load it
 * was written from, in both directions of the power.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class LoadRowsTest {

    private static final List<PlainFamily<Load>> FAMILIES =
            List.of(LoadRows.ENERGY_CONSUMER, LoadRows.ENERGY_SOURCE, LoadRows.ASYNCHRONOUS_MACHINE);

    @Test
    void applyOfTheWrittenValuesGivesTheLoadBack() {
        for (PlainFamily<Load> family : FAMILIES) {
            for (double[] pq : new double[][] {{12.5, -3.25}, {-7.0, 4.5}}) {
                Network sender = EurostagTutorialExample1Factory.create();
                Load load = sender.getLoad("LOAD").setP0(pq[0]).setQ0(pq[1]);
                Map<String, String> written = written(family, load);

                Network receiver = EurostagTutorialExample1Factory.create();
                Load received = receiver.getLoad("LOAD");
                family.apply(received, written::get, row -> Double.NaN);
                assertEquals(pq[0], received.getP0(), 0.0, family.updateQuery());
                assertEquals(pq[1], received.getQ0(), 0.0, family.updateQuery());
            }
        }
    }

    @Test
    void anIncompleteGroupIsNotRead() {
        Load load = EurostagTutorialExample1Factory.create().getLoad("LOAD");
        LoadRows.ENERGY_CONSUMER.apply(load, Map.of("p", "1.0")::get, row -> "p".equals(row.variable()) ? 42.0 : 43.0);
        assertEquals(42.0, load.getP0(), 0.0);
        assertEquals(43.0, load.getQ0(), 0.0);
    }

    @Test
    void theMachineKindAndFlagAreWrittenFromTheRows() {
        Load load = EurostagTutorialExample1Factory.create().getLoad("LOAD").setP0(-2.0);
        Map<String, String> written = written(LoadRows.ASYNCHRONOUS_MACHINE, load);
        assertEquals("generator", written.get("type"));
        assertEquals("false", written.get("controlEnabled"));
        assertEquals(List.of("RotatingMachine.p", "RotatingMachine.q", "RegulatingCondEq.controlEnabled",
                "AsynchronousMachine.asynchronousMachineType"), LoadRows.ASYNCHRONOUS_MACHINE.properties());
        assertEquals(List.of("p0", "q0"), LoadRows.keys());
    }

    /** What the export writes for a load, by query variable: every row, encoded and spelled by its quantity. */
    private static Map<String, String> written(PlainFamily<Load> family, Load load) {
        Map<String, String> written = new HashMap<>();
        family.rows().forEach(row -> written.put(row.variable(),
                row.quantity().lexical(row.quantity().encode(row.getter().applyAsDouble(load), 1))));
        return written;
    }
}
