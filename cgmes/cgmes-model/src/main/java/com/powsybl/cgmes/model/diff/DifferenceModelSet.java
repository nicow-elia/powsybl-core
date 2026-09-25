/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.model.diff;

import com.powsybl.cgmes.model.CgmesSubset;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The difference models a single change set produced, one per CGMES profile it touched.
 *
 * <p>A difference model describes exactly one profile, but a change rarely does: moving a tap and changing an
 * operational limit at once touches the steady state hypothesis and the equipment model. This set is what an
 * exporter hands over in that case, and what a {@link DifferenceSink} may take as a unit when it wants to store the
 * profiles of one change atomically.</p>
 *
 * <p>Iteration follows the declaration order of {@link CgmesSubset}, so a consumer sees the equipment model before
 * the steady state hypothesis, which is the order in which they have to be applied.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class DifferenceModelSet {

    private final Map<CgmesSubset, DifferenceModel> models;

    /**
     * @param models the difference models of the set, at most one per profile
     * @throws IllegalArgumentException if two models describe the same profile
     */
    public DifferenceModelSet(Collection<DifferenceModel> models) {
        Objects.requireNonNull(models);
        Map<CgmesSubset, DifferenceModel> bySubset = new EnumMap<>(CgmesSubset.class);
        for (DifferenceModel model : models) {
            Objects.requireNonNull(model);
            CgmesSubset subset = model.header().subset();
            DifferenceModel previous = bySubset.put(subset, model);
            if (previous != null) {
                throw new IllegalArgumentException("A difference model set holds one model per profile, but it was"
                        + " given two models of the " + subset.getIdentifier() + " profile: " + previous.header().id()
                        + " and " + model.header().id());
            }
        }
        this.models = Collections.unmodifiableMap(bySubset);
    }

    /** The models by profile, in {@link CgmesSubset} declaration order. */
    public Map<CgmesSubset, DifferenceModel> models() {
        return models;
    }

    /** The model describing the given profile, if this set holds one. */
    public Optional<DifferenceModel> get(CgmesSubset subset) {
        return Optional.ofNullable(models.get(subset));
    }

    /** The profiles this set describes. */
    public Set<CgmesSubset> subsets() {
        return models.keySet();
    }

    /** Whether this set holds no model at all. */
    public boolean isEmpty() {
        return models.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DifferenceModelSet other && models.equals(other.models);
    }

    @Override
    public int hashCode() {
        return models.hashCode();
    }

    @Override
    public String toString() {
        return "DifferenceModelSet" + models.values();
    }
}
