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
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Decides how a network gets from where it is to a stored state: not at all, by applying differences, or by being
 * rebuilt.
 *
 * <p>A pure function over catalogue data. It is written that way on purpose: the decision is the part of the
 * update that has real logic in it &mdash; is the current model an ancestor of the target, how far back, is every
 * difference on the way applicable in place &mdash; and a pure function can be tested with hand-built chains
 * instead of a database, which is what {@code DiffUpdatePlannerTest} does.</p>
 *
 * <p>Everything it decides against the fast route produces a <em>reason</em>, and the reasons travel all the way
 * to the caller's report. "It reloaded" is not an answer anybody can act on; "the model the network holds is not
 * an ancestor of the target" is.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class DiffUpdatePlanner {

    /** Which way the update goes, before the network has been looked at. */
    enum Route {
        /** The network is already at the target. */
        NOOP,
        /** There is a path of differences from where the network is to the target. */
        DIFF,
        /** There is no usable path: the network has to be rebuilt from the database. */
        FULL
    }

    /**
     * The plan.
     *
     * @param route    what to do
     * @param paths    the differences to apply per profile, in the order they are applied
     * @param targets  the stored model each profile is to end up at
     * @param inverted the profiles whose path is walked backwards, that is towards an older model
     * @param reasons  why the fast route is impossible, empty when it is not
     */
    record Plan(Route route, Map<CgmesSubset, List<StoredModel>> paths, Map<CgmesSubset, StoredModel> targets,
                Map<CgmesSubset, Boolean> inverted, List<String> reasons) {

        Plan {
            paths = Map.copyOf(paths);
            targets = Map.copyOf(targets);
            inverted = Map.copyOf(inverted);
            reasons = List.copyOf(reasons);
        }

        int diffCount() {
            return paths.values().stream().mapToInt(List::size).sum();
        }

        long statementCount() {
            return paths.values().stream().flatMap(List::stream).mapToLong(StoredModel::tripleCount)
                    .filter(n -> n > 0).sum();
        }
    }

    private DiffUpdatePlanner() {
    }

    /**
     * A plan that goes nowhere, for a network that belongs to another scenario.
     *
     * <p>Not a query result but a decision taken before any query is sent: the identifiers a network of another
     * scenario holds mean nothing in this one, and looking them up here could even find a model of the same name
     * that is a different thing. The reason says so.</p>
     *
     * @param networkScenario the scenario the network belongs to
     * @param targetScenario  the scenario it is to be brought to
     * @return the plan
     */
    static Plan otherScenario(String networkScenario, String targetScenario) {
        return new Plan(Route.FULL, Map.of(), Map.of(), Map.of(),
                List.of("network is at scenario '" + networkScenario + "', target is scenario '" + targetScenario
                        + "': diffs never cross scenarios"));
    }

    /**
     * Plan an update.
     *
     * @param scenario     the scenario, for the messages
     * @param currentIds   the stored model the network holds per profile
     * @param chains       the chain from the target of each profile down to its full model, head first, as
     *                     {@link ModelCatalog#chainsDown} answers
     * @param maxDiffChain how many differences may be composed
     * @return the plan
     */
    static Plan plan(String scenario, Map<CgmesSubset, String> currentIds,
                     Map<CgmesSubset, List<StoredModel>> chains,
                     Map<CgmesSubset, List<StoredModel>> currentChains, int maxDiffChain) {
        Map<CgmesSubset, List<StoredModel>> paths = new EnumMap<>(CgmesSubset.class);
        Map<CgmesSubset, StoredModel> targets = new EnumMap<>(CgmesSubset.class);
        Map<CgmesSubset, Boolean> inverted = new EnumMap<>(CgmesSubset.class);
        List<String> reasons = new ArrayList<>();

        for (Map.Entry<CgmesSubset, List<StoredModel>> entry : chains.entrySet()) {
            CgmesSubset subset = entry.getKey();
            List<StoredModel> chain = entry.getValue();
            if (chain.isEmpty()) {
                reasons.add("scenario '" + scenario + "' holds no " + subset.getIdentifier() + " model of the"
                        + " target");
                continue;
            }
            StoredModel target = chain.get(0);
            targets.put(subset, target);
            String currentId = currentIds.get(subset);
            if (currentId == null) {
                reasons.add("the network holds no " + subset.getIdentifier() + " model identity");
                continue;
            }
            if (currentId.equals(target.id())) {
                paths.put(subset, List.of());
                inverted.put(subset, Boolean.FALSE);
                continue;
            }
            int index = indexOf(chain, currentId);
            if (index >= 0) {
                // The network is behind the target: apply chain[0..index) oldest first
                List<StoredModel> forward = new ArrayList<>(chain.subList(0, index));
                Collections.reverse(forward);
                paths.put(subset, forward);
                inverted.put(subset, Boolean.FALSE);
                continue;
            }
            // The other direction: the target may be an ancestor of where the network is, and a difference
            // carries both directions, so going back is applying the same differences the other way round
            List<StoredModel> currentChain = currentChains.getOrDefault(subset, List.of());
            int back = indexOf(currentChain, target.id());
            if (back > 0) {
                List<StoredModel> backwards = new ArrayList<>(currentChain.subList(0, back));
                Collections.reverse(backwards);
                paths.put(subset, backwards);
                inverted.put(subset, Boolean.TRUE);
                continue;
            }
            reasons.add("model " + currentId + " of subset " + subset.getIdentifier() + " is neither an ancestor"
                    + " nor a descendant of " + target.id() + " in scenario '" + scenario + "'");
        }

        if (!reasons.isEmpty()) {
            return new Plan(Route.FULL, Map.of(), targets, Map.of(), reasons);
        }
        int count = paths.values().stream().mapToInt(List::size).sum();
        if (count == 0) {
            return new Plan(Route.NOOP, paths, targets, inverted, List.of());
        }
        if (count > maxDiffChain) {
            return new Plan(Route.FULL, Map.of(), targets, Map.of(),
                    List.of("a chain of " + count + " differences exceeds maxDiffChain " + maxDiffChain
                            + "; consider a checkpoint (Checkpoint.create)"));
        }
        List<String> slow = new ArrayList<>();
        paths.forEach((subset, path) -> path.stream()
                .filter(model -> !model.fastPredicatesOnly())
                .forEach(model -> slow.add("difference model " + model.id() + " of subset "
                        + subset.getIdentifier() + " states a property no update query reads, so it cannot be"
                        + " applied to a live network")));
        if (!slow.isEmpty()) {
            return new Plan(Route.FULL, Map.of(), targets, Map.of(), slow);
        }
        return new Plan(Route.DIFF, paths, targets, inverted, List.of());
    }

    /**
     * Where a model sits in a chain, or {@code -1}.
     *
     * <p>Position matters rather than mere membership: it is how many differences lie between the network and the
     * target, which is the path to apply.</p>
     */
    private static int indexOf(List<StoredModel> chain, String id) {
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
