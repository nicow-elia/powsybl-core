/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesExportUtilTest {

    /**
     * Wherever the fourteen decimals of the shared formatter are lossless, an exact literal is the very same string,
     * so a difference model and a full export spell a value identically.
     */
    @Test
    void formatExactAgreesWithFormatWhereverFormatIsLossless() {
        assertEquals("1.5", CgmesExportUtil.formatExact(1.5));
        assertEquals("0.00091", CgmesExportUtil.formatExact(9.1E-4));
        assertEquals("0", CgmesExportUtil.formatExact(0.0));
        assertEquals("18.35", CgmesExportUtil.formatExact(18.35));
        assertEquals(CgmesExportUtil.format(1.5), CgmesExportUtil.formatExact(1.5));
    }

    /**
     * The shared formatter keeps fourteen decimals, so it is still lossless at 1e-9 &mdash; the magnitude of a
     * susceptance in siemens &mdash; and only rounds to zero below that. A difference model cannot afford the
     * rounding, because its reverse statements have to restore the value the sender held exactly.
     */
    @Test
    void formatExactKeepsValuesBelowTheFourteenthDecimal() {
        assertEquals("0.000000001", CgmesExportUtil.format(1E-9));
        assertEquals("0.000000001", CgmesExportUtil.formatExact(1E-9));

        assertEquals("0", CgmesExportUtil.format(3.3E-15));
        assertEquals("3.3E-15", CgmesExportUtil.formatExact(3.3E-15));
        assertEquals(3.3E-15, Double.parseDouble(CgmesExportUtil.formatExact(3.3E-15)), 0.0);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.0, 1.0, -1.0, 1.5, 9.1E-4, 1E-9, 1.23456789012345E-7, 3.3E-15, 1234567.891,
        Double.MIN_NORMAL, 1e18})
    void formatExactRoundTripsEveryDouble(double value) {
        assertEquals(value, Double.parseDouble(CgmesExportUtil.formatExact(value)), 0.0);
    }
}
