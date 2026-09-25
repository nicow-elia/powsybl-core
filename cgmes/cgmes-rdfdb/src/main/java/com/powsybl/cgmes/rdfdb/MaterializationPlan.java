/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;

import java.util.List;
import java.util.Map;

/**
 * How to build the data of one snapshot from what the database holds: per profile, where to start and what to apply.
 *
 * <p>Per profile rather than per snapshot, because a snapshot need not have a full graph of everything. A timestep
 * root stores the state variables of its timestep as a full graph while its steady state is a difference; a
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
}
