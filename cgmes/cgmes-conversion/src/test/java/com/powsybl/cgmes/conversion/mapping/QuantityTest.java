/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every quantity reads back what it writes, with either sign of the regulating terminal.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class QuantityTest {

    @Test
    void decodeIsTheInverseOfEncode() {
        for (Quantity quantity : Quantity.values()) {
            for (int terminalSign : new int[] {1, -1}) {
                for (double value : new double[] {12.5, -3.25, 0.0}) {
                    assertEquals(value, quantity.decode(quantity.encode(value, terminalSign), terminalSign), 0.0,
                            quantity + " " + terminalSign);
                }
            }
        }
    }

    @Test
    void aNumberIsReadBackAsWritten() {
        for (Quantity quantity : Quantity.values()) {
            if (quantity != Quantity.FLAG && quantity != Quantity.ASYNCHRONOUS_MACHINE_KIND) {
                assertEquals(-3.25, quantity.parse(quantity.lexical(-3.25)), 0.0, quantity.name());
            }
        }
        assertEquals(1, Quantity.FLAG.parse(Quantity.FLAG.lexical(1)));
        assertEquals(0, Quantity.FLAG.parse(Quantity.FLAG.lexical(0)));
        assertEquals(Double.NaN, Quantity.MW_LOAD.parse("not a number"));
    }

    @Test
    void signsAndSpellingsAreStatedOnce() {
        assertEquals(-5.0, Quantity.MVAR_MACHINE_TARGET.encode(5.0, 1));
        assertEquals(5.0, Quantity.MVAR_MACHINE_TARGET.encode(5.0, -1));
        assertEquals(-5.0, Quantity.MVAR_TARGET.encode(5.0, -1));
        assertEquals(5.0, Quantity.KV_TARGET.encode(5.0, -1));
        assertEquals(5.0, Quantity.MW_LOAD.encode(5.0, -1));
        assertEquals("k", Quantity.KV_TARGET.multiplier());
        assertEquals("M", Quantity.MVAR_TARGET.multiplier());
        assertEquals("none", Quantity.AMPERE_LIMITER.multiplier());
        assertEquals("generator", Quantity.ASYNCHRONOUS_MACHINE_KIND.lexical(-1));
        assertEquals("motor", Quantity.ASYNCHRONOUS_MACHINE_KIND.lexical(0));
        assertEquals("AsynchronousMachineKind", Quantity.ASYNCHRONOUS_MACHINE_KIND.enumeration());
        assertEquals("false", Quantity.FLAG.lexical(0));
    }
}
