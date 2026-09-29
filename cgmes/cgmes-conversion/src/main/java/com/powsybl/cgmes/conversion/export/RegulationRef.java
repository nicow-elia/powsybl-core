/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulation;
import com.powsybl.iidm.network.regulation.VoltageRegulationHolder;

import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOCAL_TARGET_Q;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOCAL_TARGET_V;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_MODE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_REGULATING;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TARGET_DEADBAND;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TARGET_VALUE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TERMINAL;

/**
 * A voltage regulation holder together with what a recorded change calls its values.
 *
 * <p>Since the voltage regulation refactoring of IIDM (powsybl-core #3699) the regulation of a generator, a shunt
 * compensator, a static var compensator, a voltage source converter or a ratio tap changer is one
 * {@link VoltageRegulation} object, plus the local targets the holder keeps itself. IIDM reports a change of any of
 * them on the identifiable owning the holder: the holder itself, or the transformer for a ratio tap changer, whose
 * attribute names are then prefixed with {@code ratioTapChanger}, {@code ratioTapChanger1}, ... This reference is what
 * lets a single piece of code read these values from any {@link IidmStateView}.</p>
 *
 * <p>The readers below mirror the defaults of {@link VoltageRegulationHolder} ({@code isWithMode},
 * {@code isRegulatingWithMode}, {@code getRegulatingTargetV}, {@code getRegulatingTargetQ}) exactly, so that a read
 * through {@link IidmStateView#LIVE} gives the value the holder itself gives. The regulating terminal and the
 * existence of the regulation are structure and read live: neither is stored per variant in IIDM.</p>
 *
 * @param owner           the identifiable a change of the regulation is recorded on
 * @param attributePrefix the prefix of the attribute names, {@code ""} when the owner is the holder itself
 * @param holder          the holder of the regulation
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
record RegulationRef(Identifiable<?> owner, String attributePrefix, VoltageRegulationHolder<?> holder) {

    /** The reference of a holder that is an identifiable itself, which is every holder but a ratio tap changer. */
    static <H extends Identifiable<?> & VoltageRegulationHolder<?>> RegulationRef of(H holder) {
        return new RegulationRef(holder, "", holder);
    }

    /** The name a change of the given value of this regulation is recorded under. */
    String attribute(String key) {
        return attributePrefix.isEmpty() ? key : attributePrefix + "." + key;
    }

    VoltageRegulation regulation() {
        return holder.getVoltageRegulation();
    }

    /** The mode of the regulation, {@code null} when there is no regulation or its mode is undefined in this variant. */
    RegulationMode mode(IidmStateView state) {
        VoltageRegulation regulation = regulation();
        return regulation == null ? null
                : state.getEnum(owner, attribute(VR_MODE), RegulationMode.class, regulation::getMode);
    }

    boolean isRegulating(IidmStateView state) {
        VoltageRegulation regulation = regulation();
        return regulation != null && state.getBoolean(owner, attribute(VR_REGULATING), regulation::isRegulating);
    }

    double targetValue(IidmStateView state) {
        VoltageRegulation regulation = regulation();
        return regulation == null ? Double.NaN
                : state.getDouble(owner, attribute(VR_TARGET_VALUE), regulation::getTargetValue);
    }

    double targetDeadband(IidmStateView state) {
        VoltageRegulation regulation = regulation();
        return regulation == null ? Double.NaN
                : state.getDouble(owner, attribute(VR_TARGET_DEADBAND), regulation::getTargetDeadband);
    }

    double localTargetV(IidmStateView state) {
        return state.getDouble(owner, attribute(LOCAL_TARGET_V), holder::getLocalTargetV);
    }

    double localTargetQ(IidmStateView state) {
        return state.getDouble(owner, attribute(LOCAL_TARGET_Q), holder::getLocalTargetQ);
    }

    /** As {@link VoltageRegulationHolder#isWithMode}: {@code true} for reactive power when there is no regulation. */
    boolean isWithMode(RegulationMode mode, IidmStateView state) {
        if (regulation() == null) {
            return mode == RegulationMode.REACTIVE_POWER;
        }
        return mode != null && mode == mode(state);
    }

    /** As {@link VoltageRegulationHolder#isRegulatingWithMode}: {@code true} for reactive power when there is no regulation. */
    boolean isRegulatingWithMode(RegulationMode mode, IidmStateView state) {
        if (regulation() == null) {
            return mode == RegulationMode.REACTIVE_POWER;
        }
        return isRegulating(state) && mode(state) == mode;
    }

    /**
     * Whether the regulating terminal is structure the state can be read against: the terminal is not per variant and
     * is read live, so a change set that changed it describes its state before against the wrong terminal.
     */
    private void requireTerminalUnchanged(IidmStateView state) {
        if (state.hasChange(owner, attribute(VR_TERMINAL))) {
            throw new UnreconstructibleStateException("the regulating terminal of " + owner.getId()
                    + " changed in the change set, so its targets before the change cannot be told apart");
        }
    }

    /** As {@link VoltageRegulationHolder#getRegulatingTargetV}: the remote target when a terminal is set, else the local one. */
    double regulatingTargetV(IidmStateView state) {
        requireTerminalUnchanged(state);
        if ((isWithMode(RegulationMode.VOLTAGE, state) || isWithMode(RegulationMode.VOLTAGE_PER_REACTIVE_POWER, state))
                && holder.hasRegulatingTerminal()) {
            return targetValue(state);
        }
        return localTargetV(state);
    }

    /** As {@link VoltageRegulationHolder#getRegulatingTargetQ}: the remote target when a terminal is set, else the local one. */
    double regulatingTargetQ(IidmStateView state) {
        requireTerminalUnchanged(state);
        if (isWithMode(RegulationMode.REACTIVE_POWER, state) && holder.hasRegulatingTerminal()) {
            return targetValue(state);
        }
        return localTargetQ(state);
    }
}
