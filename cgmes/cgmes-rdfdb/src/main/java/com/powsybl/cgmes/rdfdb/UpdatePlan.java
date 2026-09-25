/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The answer to "can I get from snapshot A to snapshot B by applying differences?".
 *
 * <p>One query decides it, and the answer is one of three. {@link Kind#NOOP} when the two are the same snapshot;
 * {@link Kind#DIFF} with the differences to apply, in order, when every one of them is fast-route capable and the
 * path is short enough; and {@link Kind#FULL} with the reasons when it is not, in which case the caller rebuilds
 * the network at B instead.</p>
 *
 * <p>The path is a lowest-common-ancestor walk on a chain that never branches: up from A to the ancestor the two
 * share, applying each difference <em>inverted</em>, then down to B applying each one forward. Both halves are in
 * {@link #steps()} in the order they have to be applied.</p>
 *
 * @param steps                 the differences to apply, in application order
 * @param kind                  what the caller has to do
 * @param from                  the IRI of the snapshot the network is at, {@code null} when it could not be
 *                              identified
 * @param to                    the IRI of the target snapshot, {@code null} when the target does not exist
 * @param reasons               why the answer is {@link Kind#FULL}, empty otherwise
 * @param chainLength           how many differences the path holds
 * @param checkpointRecommended whether the target is far enough from the nearest full snapshot to be worth a
 *                              {@link Checkpoint}, under the options this plan was made with
 * @param distanceToFullSnapshot how many snapshots lie between the target and the nearest ancestor that holds full
 *                              graphs, so that a caller with another threshold can decide for itself
 * @param targetState           the model identifier per profile the network ends up at
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record UpdatePlan(Kind kind, String from, String to, List<DiffStep> steps, List<String> reasons,
                         int chainLength, boolean checkpointRecommended, int distanceToFullSnapshot,
                         Map<CgmesSubset, String> targetState) {

    /** What the caller has to do to reach the target. */
    public enum Kind {
        /** The network is already at the target. */
        NOOP,
        /** The target is reachable by applying the differences of {@link UpdatePlan#steps()}. */
        DIFF,
        /** The network has to be rebuilt at the target. */
        FULL
    }

    /**
     * One difference on the way, and which way round it has to be applied.
     *
     * @param snapshot the IRI of the snapshot the difference belongs to
     * @param model    the stored difference
     * @param inverted whether it has to be undone rather than applied, which is the case for the part of the path
     *                 that walks <em>up</em> from A to the common ancestor
     */
    public record DiffStep(String snapshot, StoredModel model, boolean inverted) {
    }

    /**
     * @param kind                  see {@link #kind()}
     * @param from                  see {@link #from()}
     * @param to                    see {@link #to()}
     * @param steps                 see {@link #steps()}
     * @param reasons               see {@link #reasons()}
     * @param chainLength           see {@link #chainLength()}
     * @param checkpointRecommended see {@link #checkpointRecommended()}
     * @param distanceToFullSnapshot see {@link #distanceToFullSnapshot()}
     * @param targetState           see {@link #targetState()}
     */
    public UpdatePlan {
        steps = List.copyOf(steps);
        reasons = List.copyOf(reasons);
        targetState = Map.copyOf(targetState);
    }

    /**
     * The steps grouped by profile, keeping their order.
     *
     * @return the steps per profile
     */
    public Map<CgmesSubset, List<DiffStep>> stepsBySubset() {
        Map<CgmesSubset, List<DiffStep>> bySubset = new EnumMap<>(CgmesSubset.class);
        steps.forEach(step -> bySubset.computeIfAbsent(step.model().subset(), k -> new ArrayList<>()).add(step));
        return bySubset;
    }

    /**
     * @return whether every step of the path has to be undone rather than applied
     */
    public boolean isAllInverted() {
        return !steps.isEmpty() && steps.stream().allMatch(DiffStep::inverted);
    }

    /**
     * The steps the store says write values IIDM does not keep per network variant.
     *
     * <p>Only a step whose model says {@code false} is listed. A model that says nothing &mdash; a store written
     * before the flag existed &mdash; is left out on purpose: the network aware check runs on every variant update
     * anyway and refuses it there, so being optimistic here costs one fetch and never a wrong result.</p>
     *
     * @return the steps that cannot be applied to a single variant
     */
    public List<DiffStep> variantUnsafeSteps() {
        return steps.stream().filter(step -> step.model().isVariantUnsafe()).toList();
    }

    /**
     * @return whether the path mixes steps to undo and steps to apply
     */
    public boolean isMixedDirection() {
        return steps.stream().anyMatch(DiffStep::inverted) && steps.stream().anyMatch(s -> !s.inverted());
    }
}
