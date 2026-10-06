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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * How to build the data of one snapshot from what the database holds: per profile, where to start and what to apply.
 *
 * <p>Per profile rather than per snapshot, because a snapshot need not have a full graph of everything. A timestamp
 * root stores the state variables of its timestamp as a full graph while its steady state is a difference; a
 * checkpoint materialises the profiles a chain touched and leaves the untouched ones at the instance file they have
 * always been at. So each profile walks up the chain on its own until it finds an ancestor that holds a full graph
 * of <em>that</em> profile, and the differences below that ancestor are what has to be applied.</p>
 *
 * @param target      the IRI of the snapshot being materialised
 * @param startModel  the full model to start from, per profile
 * @param steps       the differences to apply, in application order, never inverted &mdash; a materialisation only
 *                    ever walks down
 * @param targetState the model identifier per profile the result is at
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record MaterializationPlan(String target, Map<CgmesSubset, FullSource> startModel,
                                  List<UpdatePlan.DiffStep> steps, Map<CgmesSubset, String> targetState) {

    /**
     * The full graph one profile starts from.
     *
     * @param snapshot the IRI of the snapshot that owns it
     * @param modelId  the identifier of the full model
     * @param graph    the named graph holding its statements, as the metadata graph records it
     */
    public record FullSource(String snapshot, String modelId, String graph) {
    }

    /**
     * @param target      see {@link #target()}
     * @param startModel  see {@link #startModel()}
     * @param steps       see {@link #steps()}
     * @param targetState see {@link #targetState()}
     */
    public MaterializationPlan {
        startModel = Map.copyOf(startModel);
        steps = List.copyOf(steps);
        targetState = Map.copyOf(targetState);
    }

    /**
     * The same plan for fewer profiles: what a load with a profile projection materialises.
     *
     * @param profiles the profiles to keep, or {@code null} or empty for all of them
     * @return the plan, restricted to those profiles
     * @throws RdfDbException if a profile is not part of the snapshot's state
     */
    public MaterializationPlan project(Set<CgmesSubset> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return this;
        }
        Set<CgmesSubset> missing = EnumSet.copyOf(profiles);
        missing.removeAll(targetState.keySet());
        if (!missing.isEmpty()) {
            throw new RdfDbException("the snapshot " + target + " holds no " + missing + "; it holds "
                    + new TreeSet<>(targetState.keySet()));
        }
        Map<CgmesSubset, FullSource> start = new EnumMap<>(CgmesSubset.class);
        Map<CgmesSubset, String> state = new EnumMap<>(CgmesSubset.class);
        profiles.forEach(subset -> {
            start.put(subset, startModel.get(subset));
            state.put(subset, targetState.get(subset));
        });
        return new MaterializationPlan(target, start,
                steps.stream().filter(step -> profiles.contains(step.model().subset())).toList(), state);
    }
}
