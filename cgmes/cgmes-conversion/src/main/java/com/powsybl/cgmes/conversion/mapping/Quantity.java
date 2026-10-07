/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.mapping;

import com.powsybl.cgmes.conversion.export.CgmesExportUtil;

/**
 * What a value means on both sides of the mapping: its unit multiplier in CGMES, its sign, whether the sign of the
 * regulating terminal the import recorded applies to it, and how it is spelled. Stated once here, read by the export
 * ({@link #encode}, {@link #lexical}) and by the import ({@link #parse}, {@link #decode}), so that one constant serves
 * both directions.
 *
 * <p>Every value is carried as a {@code double}: a flag is {@code 0} or {@code 1}, an enumeration is derived from a
 * number by {@link #lexical}.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public enum Quantity {
    /** The active power of an injection in the load convention, which both IIDM loads and CGMES use. */
    MW_LOAD(null, 1, false),
    /** The reactive power of an injection in the load convention. */
    MVAR_LOAD(null, 1, false),
    /** A boolean property: written {@code true} for any value but zero. */
    FLAG(null, 1, false),
    /** The AsynchronousMachineKind of a machine, derived from its active power in the load convention. */
    ASYNCHRONOUS_MACHINE_KIND(null, 1, false),
    /** A voltage target in kV. */
    KV_TARGET("k", 1, false),
    /** A reactive power target in MVar, read by the import with the sign of the regulating terminal it recorded. */
    MVAR_TARGET("M", 1, true),
    /**
     * The reactive power target of a machine: IIDM states it in the generator convention, CGMES in the load one, and
     * the import applies the sign of the regulating terminal too.
     */
    MVAR_MACHINE_TARGET("M", -1, true),
    /** An active power target in MW, with the sign of the regulating terminal (a phase tap changer). */
    MW_TARGET("M", 1, true),
    /** A current limit in A, which carries no sign. */
    AMPERE_LIMITER("none", 1, false);

    private final String multiplier;
    private final int sign;
    private final boolean terminalSigned;

    Quantity(String multiplier, int sign, boolean terminalSigned) {
        this.multiplier = multiplier;
        this.sign = sign;
        this.terminalSigned = terminalSigned;
    }

    /** The value of {@code RegulatingControl.targetValueUnitMultiplier} for a target of this quantity. */
    public String multiplier() {
        return multiplier;
    }

    /**
     * The CGMES value of an IIDM value.
     *
     * @param terminalSign the sign of the regulating terminal the import recorded, {@code 1} when the equipment model
     *                     is exported too; ignored by a quantity the import reads without it
     */
    public double encode(double iidmValue, int terminalSign) {
        return sign * (terminalSigned ? terminalSign : 1) * iidmValue;
    }

    /** The IIDM value of a CGMES value, the inverse of {@link #encode}. */
    public double decode(double cgmesValue, int terminalSign) {
        return encode(cgmesValue, terminalSign);
    }

    /** The CIM enumeration a value of this quantity is a literal of, {@code null} for a plain literal. */
    public String enumeration() {
        return this == ASYNCHRONOUS_MACHINE_KIND ? "AsynchronousMachineKind" : null;
    }

    /** How the CGMES value is written. */
    public String lexical(double cgmesValue) {
        return switch (this) {
            case FLAG -> CgmesExportUtil.format(cgmesValue != 0);
            case ASYNCHRONOUS_MACHINE_KIND -> cgmesValue < 0 ? "generator" : "motor";
            default -> CgmesExportUtil.format(cgmesValue);
        };
    }

    /** How a written value is read back; a value that is not a number reads as {@code NaN}, as the import reads it. */
    public double parse(String lexical) {
        if (this == FLAG) {
            return Boolean.parseBoolean(lexical) ? 1 : 0;
        }
        try {
            return Double.parseDouble(lexical);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
