/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesPropertyBufferTest {

    private static final CgmesSubset SSH = CgmesSubset.STEADY_STATE_HYPOTHESIS;
    private static final CgmesSubset EQ = CgmesSubset.EQUIPMENT;

    private static CgmesExportContext context() {
        return new CgmesExportContext();
    }

    @Test
    void statementsKeepTheBufferedOrderAndCarryTheKind() {
        CgmesPropertyBuffer updates = CgmesPropertyBuffer.newUpdates("ConformLoad", "_EnergyConsumer")
                .value("EnergyConsumer.p", 12.5)
                .value("EnergyConsumer.q", 5.0)
                .object("RegulatingControl", "RC")
                .enumValue("RegulatingControl.targetValueUnitMultiplier", "UnitMultiplier", "k")
                .updates();

        List<CgmesStatement> statements = updates.statements(SSH, context());
        assertEquals(List.of(
                CgmesStatement.literal("EnergyConsumer", "ConformLoad", "EnergyConsumer.p", "12.5"),
                CgmesStatement.literal("EnergyConsumer", "ConformLoad", "EnergyConsumer.q", "5"),
                CgmesStatement.enumeration("RC", "RegulatingControl",
                        "RegulatingControl.targetValueUnitMultiplier", "UnitMultiplier.k")), statements);
        // The leading underscore of an mRID is not part of the identifier of a statement
        assertEquals("EnergyConsumer", statements.get(0).subjectId());
        assertEquals("ConformLoad", statements.get(0).className());
    }

    @Test
    void profilesAreKeptApart() {
        CgmesPropertyBuffer updates = CgmesPropertyBuffer.newUpdates("ConformLoad", "EnergyConsumer")
                .value("EnergyConsumer.p", 12.5)
                .object(EQ, "ConformLoad", "EnergyConsumer")
                .value("EnergyConsumer.pfixed", 1.0)
                .updates();

        assertEquals(List.of(SSH, EQ), List.copyOf(updates.subsets()));
        assertEquals(1, updates.statements(SSH, context()).size());
        assertEquals("EnergyConsumer.p", updates.statements(SSH, context()).get(0).property());
        assertEquals("EnergyConsumer.pfixed", updates.statements(EQ, context()).get(0).property());
        assertTrue(updates.statements(CgmesSubset.TOPOLOGY, context()).isEmpty());
    }

    @Test
    void mergeFromReplacesValuesInPlace() {
        CgmesPropertyBuffer target = CgmesPropertyBuffer.newUpdates("ConformLoad", "EnergyConsumer")
                .value("EnergyConsumer.p", 10.0)
                .value("EnergyConsumer.q", 5.0)
                .updates();
        CgmesPropertyBuffer source = CgmesPropertyBuffer.newUpdates("ConformLoad", "EnergyConsumer")
                .value("EnergyConsumer.p", 12.5)
                .object("Breaker", "Switch")
                .value("Switch.open", true)
                .updates();
        target.mergeFrom(source);

        assertEquals(List.of(
                CgmesStatement.literal("EnergyConsumer", "ConformLoad", "EnergyConsumer.p", "12.5"),
                CgmesStatement.literal("EnergyConsumer", "ConformLoad", "EnergyConsumer.q", "5"),
                CgmesStatement.literal("Switch", "Breaker", "Switch.open", "true")),
                target.statements(SSH, context()));
    }

    @Test
    void anObjectOfAnotherProfileIsADistinctSubject() {
        CgmesPropertyBuffer updates = CgmesPropertyBuffer.newUpdates(EQ, "ConformLoad", "EnergyConsumer")
                .value("EnergyConsumer.pfixed", 1.0)
                .updates();
        assertEquals(List.of(EQ), List.copyOf(updates.subsets()));
        assertTrue(updates.statements(SSH, context()).isEmpty());
    }
}
