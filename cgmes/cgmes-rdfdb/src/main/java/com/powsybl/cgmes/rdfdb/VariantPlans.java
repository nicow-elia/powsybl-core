/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import java.util.ArrayList;
import java.util.List;

/**
 * The two decisions a variant operation makes about a path, shared by the single update and the bulk load.
 *
 * <p>They were written twice and drifted apart once already (the bulk copy of the options lost the network
 * factory), which is reason enough for them to live in one place.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class VariantPlans {

    private VariantPlans() {
    }

    /**
     * Whether one plan is a better way to reach a target than another.
     *
     * <p>Fewer differences is better; a path that cannot be walked at all is worse than one that can, whatever
     * their lengths.</p>
     *
     * @param candidate the plan being considered
     * @param current   the best plan so far
     * @return whether the candidate wins
     */
    static boolean isBetter(UpdatePlan candidate, UpdatePlan current) {
        boolean candidateWalkable = candidate.kind() != UpdatePlan.Kind.FULL;
        boolean currentWalkable = current.kind() != UpdatePlan.Kind.FULL;
        if (candidateWalkable != currentWalkable) {
            return candidateWalkable;
        }
        return candidate.chainLength() < current.chainLength();
    }

    /**
     * Why a path cannot be walked inside one variant, decided before anything is cloned or fetched.
     *
     * <p>Two sources: the plan itself refused the difference route, or the store says that one of the differences
     * writes values IIDM does not keep per variant. A difference whose node does not carry the flag at all
     * &mdash; a store written before it existed &mdash; is not refused here; the network aware check runs on the
     * fetched statements and decides it there.</p>
     *
     * @param plan the path, or {@code null} when no source could reach the target at all
     * @return the blocking reasons, empty when the path may be walked
     */
    static List<String> blockingReasons(UpdatePlan plan) {
        List<String> reasons = new ArrayList<>();
        if (plan == null) {
            reasons.add("no loaded variant can reach this snapshot by applying differences");
            return reasons;
        }
        if (plan.kind() == UpdatePlan.Kind.FULL) {
            reasons.addAll(plan.reasons());
            if (reasons.isEmpty()) {
                reasons.add("the target cannot be reached by applying differences");
            }
        }
        plan.variantUnsafeSteps().forEach(step -> reasons.add("the difference " + step.model().id()
                + " of snapshot " + step.snapshot() + " writes values that are not stored per variant in IIDM"));
        return reasons;
    }

    /**
     * The options a variant operation runs with: only per-variant writes, and never a rebuild of the network it
     * was given, whatever the caller allowed.
     *
     * @param options the caller's options
     * @return an independent copy
     */
    static RdfDbUpdateOptions variantSafe(RdfDbUpdateOptions options) {
        RdfDbUpdateOptions copy = options.copy();
        copy.setDiffOptions(options.getDiffOptions().copy().setVariantSafeOnly(true));
        copy.setAllowFullReload(false);
        copy.setTargetVariant(null);
        return copy;
    }
}
