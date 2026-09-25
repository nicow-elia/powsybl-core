/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.TapChanger;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A tap changer together with what a recorded change calls it.
 *
 * <p>IIDM reports a tap changer change on the transformer that owns it, under an attribute named after the kind and
 * the end of the tap changer, such as {@code phaseTapChanger.tapPosition} or {@code ratioTapChanger2.regulating}. A
 * tap changer alone therefore cannot be looked up in a change log, which is why every read of one goes through this
 * reference.</p>
 *
 * @param transformer     the transformer owning the tap changer, which is the identifiable a change of it is
 *                        recorded on
 * @param attributePrefix the name the change log gives this tap changer, {@code phaseTapChanger} or
 *                        {@code ratioTapChanger} followed by the end number for a three windings transformer
 * @param tapChanger      the tap changer itself
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
record TapChangerRef(Identifiable<?> transformer, String attributePrefix, TapChanger<?, ?, ?, ?> tapChanger) {

    /** The name a change of the given property of this tap changer is recorded under. */
    String attribute(String suffix) {
        return attributePrefix + suffix;
    }

    int getInt(IidmStateView state, String suffix, IntSupplier live) {
        return state.getInt(transformer, attribute(suffix), live);
    }

    double getDouble(IidmStateView state, String suffix, DoubleSupplier live) {
        return state.getDouble(transformer, attribute(suffix), live);
    }

    boolean getBoolean(IidmStateView state, String suffix, BooleanSupplier live) {
        return state.getBoolean(transformer, attribute(suffix), live);
    }

    <E extends Enum<E>> E getEnum(IidmStateView state, String suffix, Class<E> type, Supplier<E> live) {
        return state.getEnum(transformer, attribute(suffix), type, live);
    }
}
