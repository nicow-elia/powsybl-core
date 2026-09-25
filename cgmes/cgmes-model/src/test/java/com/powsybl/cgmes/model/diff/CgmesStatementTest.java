/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.model.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesStatementTest {

    @Test
    void factoriesSetTheKind() {
        assertEquals(CgmesStatement.Kind.LITERAL,
                CgmesStatement.literal("L", "ConformLoad", "EnergyConsumer.p", "12.5").kind());
        assertEquals(CgmesStatement.Kind.ENUM,
                CgmesStatement.enumeration("RC", "RegulatingControl", "RegulatingControl.targetValueUnitMultiplier", "UnitMultiplier.k").kind());
        assertEquals(CgmesStatement.Kind.REFERENCE,
                CgmesStatement.reference("RC", "RegulatingControl", "RegulatingControl.Terminal", "T1").kind());
    }

    @Test
    void keyIsSubjectAndProperty() {
        CgmesStatement statement = CgmesStatement.literal("L", "ConformLoad", "EnergyConsumer.p", "12.5");
        assertEquals(new CgmesStatement.Key("L", "EnergyConsumer.p"), statement.key());
        assertEquals(statement.key(), CgmesStatement.literal("L", null, "EnergyConsumer.p", "99").key());
    }

    @Test
    void equalityIgnoresClassNameHint() {
        CgmesStatement generated = CgmesStatement.literal("L", "ConformLoad", "EnergyConsumer.p", "12.5");
        CgmesStatement parsed = CgmesStatement.literal("L", null, "EnergyConsumer.p", "12.5");
        assertEquals(generated, parsed);
        assertEquals(generated.hashCode(), parsed.hashCode());
        assertNull(parsed.className());
    }

    @Test
    void equalityComparesSubjectPropertyValueAndKind() {
        CgmesStatement base = CgmesStatement.literal("L", "ConformLoad", "EnergyConsumer.p", "12.5");
        assertNotEquals(base, CgmesStatement.literal("M", "ConformLoad", "EnergyConsumer.p", "12.5"));
        assertNotEquals(base, CgmesStatement.literal("L", "ConformLoad", "EnergyConsumer.q", "12.5"));
        assertNotEquals(base, CgmesStatement.literal("L", "ConformLoad", "EnergyConsumer.p", "10"));
        assertNotEquals(base, CgmesStatement.reference("L", "ConformLoad", "EnergyConsumer.p", "12.5"));
    }

    @Test
    void nullsAreRejected() {
        assertThrows(NullPointerException.class, () -> CgmesStatement.literal(null, "C", "p", "1"));
        assertThrows(NullPointerException.class, () -> CgmesStatement.literal("L", "C", null, "1"));
        assertThrows(NullPointerException.class, () -> CgmesStatement.literal("L", "C", "p", null));
    }
}
