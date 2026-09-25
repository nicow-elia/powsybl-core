/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which stored state of a scenario a network is to be brought to.
 *
 * <p>Two ways of saying it, and they are the two a user actually has. {@link #head()} means "the newest version of
 * every profile", which is what a client following a stream of changes wants. {@link #models(Map)} names a model
 * per profile, which is what a study reproducing an earlier state wants &mdash; and because a difference carries
 * both directions, naming an <em>older</em> model is a perfectly ordinary target: the chain between the two is
 * walked backwards.</p>
 *
 * <p>The scenario is never part of a target. It is a separate, required argument of every call, because a model
 * identifier means nothing without it.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class DiffTarget {

    private final Map<CgmesSubset, String> modelIds;

    private DiffTarget(Map<CgmesSubset, String> modelIds) {
        this.modelIds = modelIds;
    }

    /**
     * The newest version of every profile the scenario holds.
     *
     * @return the target
     */
    public static DiffTarget head() {
        return new DiffTarget(null);
    }

    /**
     * A named stored model per profile.
     *
     * @param modelIds the model identifier per CGMES profile; profiles that are not named are left alone
     * @return the target
     */
    public static DiffTarget models(Map<CgmesSubset, String> modelIds) {
        Objects.requireNonNull(modelIds);
        if (modelIds.isEmpty()) {
            throw new RdfDbException("A difference target names at least one model, or is DiffTarget.head()");
        }
        return new DiffTarget(new EnumMap<>(modelIds));
    }

    /**
     * @return whether this target is the newest version of every profile
     */
    public boolean isHead() {
        return modelIds == null;
    }

    /**
     * @return the named models, empty for {@link #head()}
     */
    public Map<CgmesSubset, String> modelIds() {
        return modelIds == null ? Map.of() : Map.copyOf(modelIds);
    }

    /**
     * @return the profiles this target names, empty for {@link #head()}, which names whatever the scenario holds
     */
    public Set<CgmesSubset> subsets() {
        return modelIds == null ? Set.of() : Set.copyOf(modelIds.keySet());
    }

    @Override
    public String toString() {
        return isHead() ? "DiffTarget.head()" : "DiffTarget" + modelIds;
    }
}
