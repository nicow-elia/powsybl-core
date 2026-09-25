/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision an update makes, taken apart from the database that feeds it.
 *
 * <p>{@link DiffUpdatePlanner} is a pure function over catalogue rows, and that is the point of it: the part of an
 * update with real logic in it &mdash; is the model the network holds an ancestor of the target, how far back, does
 * every difference on the way state properties the in-place workflow can apply &mdash; is decided here, and a chain
 * built by hand says far more clearly what is being asserted than one assembled by writing differences into a
 * store. The flow test does the latter, on both backends; this one covers the corners.</p>
 *
 * <p>Every decision against the fast route carries a reason, and the reasons are asserted: they are what a user
 * sees when a network was rebuilt instead of updated, and a wrong one is as bad as a wrong route.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class DiffUpdatePlannerTest {

    private static final String S = "2016-01-01";
    private static final CgmesSubset SSH = CgmesSubset.STEADY_STATE_HYPOTHESIS;
    private static final CgmesSubset EQ = CgmesSubset.EQUIPMENT;

    // ------------------------------------------------------------------ nothing to do

    @Test
    void aNetworkAtTheTargetIsANoop() {
        List<StoredModel> chain = chain(SSH, 2, true);
        DiffUpdatePlanner.Plan plan = DiffUpdatePlanner.plan(S, Map.of(SSH, chain.get(0).id()),
                Map.of(SSH, chain), Map.of(SSH, chain), 200);
        assertThat(plan.route()).isEqualTo(DiffUpdatePlanner.Route.NOOP);
        assertThat(plan.diffCount()).isZero();
        assertThat(plan.reasons()).isEmpty();
        assertThat(plan.targets().get(SSH)).isEqualTo(chain.get(0));
    }

    // ------------------------------------------------------------------ forwards

    @Test
    void aNetworkBehindTheTargetAppliesTheDifferencesOldestFirst() {
        // full <- d1 <- d2 <- d3, and the network sits on the full model
        List<StoredModel> chain = chain(SSH, 3, true);
        String full = chain.get(chain.size() - 1).id();
        DiffUpdatePlanner.Plan plan = DiffUpdatePlanner.plan(S, Map.of(SSH, full),
                Map.of(SSH, chain), Map.of(SSH, List.of()), 200);
        assertThat(plan.route()).isEqualTo(DiffUpdatePlanner.Route.DIFF);
        assertThat(plan.paths().get(SSH).stream().map(StoredModel::id).toList())
                .containsExactly("SSH-1", "SSH-2", "SSH-3");
        assertThat(plan.inverted().get(SSH)).isFalse();
        assertThat(plan.diffCount()).isEqualTo(3);
        assertThat(plan.statementCount()).isEqualTo(30);
        assertThat(plan.reasons()).isEmpty();
    }

    @Test
    void aNetworkOneVersionBehindAppliesOneDifference() {
        List<StoredModel> chain = chain(SSH, 3, true);
        DiffUpdatePlanner.Plan plan = DiffUpdatePlanner.plan(S, Map.of(SSH, "SSH-2"),
                Map.of(SSH, chain), Map.of(SSH, chain.subList(1, chain.size())), 200);
        assertThat(plan.route()).isEqualTo(DiffUpdatePlanner.Route.DIFF);
        assertThat(plan.paths().get(SSH).stream().map(StoredModel::id).toList()).containsExactly("SSH-3");
    }

    @Test
    void twoProfilesArePlannedTogetherAndOneOfThemMayBeAtTheTarget() {
        List<StoredModel> ssh = chain(SSH, 2, true);
        List<StoredModel> eq = chain(EQ, 0, true);
        DiffUpdatePlanner.Plan plan = DiffUpdatePlanner.plan(S,
                Map.of(SSH, "SSH-full", EQ, "EQ-full"),
                Map.of(SSH, ssh, EQ, eq), Map.of(SSH, List.of(), EQ, eq), 200);
        assertThat(plan.route()).isEqualTo(DiffUpdatePlanner.Route.DIFF);
        assertThat(plan.paths().get(SSH)).hasSize(2);
        assertThat(plan.paths().get(EQ)).isEmpty();
        assertThat(plan.diffCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ backwards

    @Test
    void aTargetBehindTheNetworkWalksTheChainTheOtherWay() {
        // The network is at the head, the target is the first difference: undo what lies between
        List<StoredModel> currentChain = chain(SSH, 3, true);
        List<StoredModel> targetChain = currentChain.subList(2, currentChain.size());
        DiffUpdatePlanner.Plan plan = DiffUpdatePlanner.plan(S, Map.of(SSH, "SSH-3"),
                Map.of(SSH, targetChain), Map.of(SSH, currentChain), 200);
        assertThat(plan.route()).isEqualTo(DiffUpdatePlanner.Route.DIFF);
        assertThat(plan.inverted().get(SSH)).isTrue();
        // Oldest first, so that reverting them in order ends at the target
        assertThat(plan.paths().get(SSH).stream().map(StoredModel::id).toList())
                .containsExactly("SSH-2", "SSH-3");
        assertThat(plan.targets().get(SSH).id()).isEqualTo("SSH-1");
    }

    @Test
    void aTargetThatIsTheFullModelUndoesEveryDifference() {
        List<StoredModel> currentChain = chain(SSH, 2, true);
        List<StoredModel> targetChain = currentChain.subList(2, currentChain.size());
        DiffUpdatePlanner.Plan plan = DiffUpdatePlanner.plan(S, Map.of(SSH, "SSH-2"),
                Map.of(SSH, targetChain), Map.of(SSH, currentChain), 200);
        assertThat(plan.route()).isEqualTo(DiffUpdatePlanner.Route.DIFF);
        assertThat(plan.inverted().get(SSH)).isTrue();
        assertThat(plan.paths().get(SSH).stream().map(StoredModel::id).toList())
                .containsExactly("SSH-1", "SSH-2");
    }

    // ------------------------------------------------------------------ the full route, with its reason

    @Test
    void aScenarioMismatchIsDecidedWithoutLookingAtAnyChain() {
        DiffUpdatePlanner.Plan plan = DiffUpdatePlanner.otherScenario("2016-01-02", S);
        assertThat(plan.route()).isEqualTo(DiffUpdatePlanner.Route.FULL);
        assertThat(plan.paths()).isEmpty();
        assertThat(plan.targets()).isEmpty();
        assertThat(plan.reasons()).singleElement().asString()
                .contains("2016-01-02").contains(S).contains("never cross scenarios");
    }

    @Test
    void anIdentityThatIsNotOnTheChainFallsBack() {
        List<StoredModel> chain = chain(SSH, 2, true);
        DiffUpdatePlanner.Plan plan = DiffUpdatePlanner.plan(S, Map.of(SSH, "urn:uuid:somewhere-else"),
                Map.of(SSH, chain), Map.of(SSH, List.of()), 200);
        assertThat(plan.route()).isEqualTo(DiffUpdatePlanner.Route.FULL);
        assertThat(plan.reasons()).singleElement().asString()
                .contains("urn:uuid:somewhere-else").contains("neither an ancestor nor a descendant");
        // The target is still known, so the caller can materialise it
        assertThat(plan.targets().get(SSH).id()).isEqualTo("SSH-2");
    }

    @Test
    void aScenarioWithoutTheTargetProfileFallsBack() {
        DiffUpdatePlanner.Plan plan = DiffUpdatePlanner.plan(S, Map.of(SSH, "SSH-full"),
                Map.of(SSH, List.of()), Map.of(SSH, List.of()), 200);
        assertThat(plan.route()).isEqualTo(DiffUpdatePlanner.Route.FULL);
        assertThat(plan.reasons()).singleElement().asString().contains("holds no SSH model of the target");
    }

    @Test
    void aNetworkWithoutAnIdentityForAProfileFallsBack() {
        List<StoredModel> chain = chain(SSH, 1, true);
        DiffUpdatePlanner.Plan plan = DiffUpdatePlanner.plan(S, Map.of(),
                Map.of(SSH, chain), Map.of(), 200);
        assertThat(plan.route()).isEqualTo(DiffUpdatePlanner.Route.FULL);
        assertThat(plan.reasons()).singleElement().asString().contains("no SSH model identity");
    }

    @Test
    void aChainLongerThanAllowedFallsBackAndSaysSo() {
        List<StoredModel> chain = chain(SSH, 3, true);
        DiffUpdatePlanner.Plan plan = DiffUpdatePlanner.plan(S, Map.of(SSH, "SSH-full"),
                Map.of(SSH, chain), Map.of(SSH, List.of()), 2);
        assertThat(plan.route()).isEqualTo(DiffUpdatePlanner.Route.FULL);
        assertThat(plan.reasons()).singleElement().asString()
                .contains("chain of 3 differences exceeds maxDiffChain 2").contains("checkpoint");
    }

    @Test
    void aDifferenceStatingAPropertyNoUpdateQueryReadsFallsBackAndNamesIt() {
        List<StoredModel> chain = new ArrayList<>(chain(SSH, 2, true));
        // The newest difference states something the in-place workflow cannot apply
        chain.set(0, slow(chain.get(0)));
        DiffUpdatePlanner.Plan plan = DiffUpdatePlanner.plan(S, Map.of(SSH, "SSH-full"),
                Map.of(SSH, chain), Map.of(SSH, List.of()), 200);
        assertThat(plan.route()).isEqualTo(DiffUpdatePlanner.Route.FULL);
        assertThat(plan.reasons()).singleElement().asString()
                .contains("SSH-2").contains("no update query reads");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A chain of {@code diffs} differences on top of a full model, head first, exactly as
     * {@link ModelCatalog#chainsDown} answers it.
     */
    private static List<StoredModel> chain(CgmesSubset subset, int diffs, boolean fast) {
        String prefix = subset.getIdentifier() + "-";
        List<StoredModel> chain = new ArrayList<>();
        for (int depth = diffs; depth >= 1; depth--) {
            chain.add(new StoredModel(S, prefix + depth, subset, StoredModel.Kind.DIFF, null,
                    "http://powsybl.org/rdfdb/s/graph/" + prefix + depth + "/forward",
                    "http://powsybl.org/rdfdb/s/graph/" + prefix + depth + "/reverse",
                    depth + 1, null, null, null, null, List.of(), List.of(),
                    List.of(depth == 1 ? prefix + "full" : prefix + (depth - 1)), fast, 10L,
                    "http://x/#", "http://iec.ch/TC57/2013/CIM-schema-cim16#", depth));
        }
        chain.add(new StoredModel(S, prefix + "full", subset, StoredModel.Kind.FULL, "contexts:s/" + prefix + ".xml",
                null, null, 1, null, null, null, null, List.of(), List.of(), List.of(), false, 1000L,
                "http://x/#", "http://iec.ch/TC57/2013/CIM-schema-cim16#", 0));
        return List.copyOf(chain);
    }

    /** The same node, but stating a property the in-place update workflow does not read. */
    private static StoredModel slow(StoredModel model) {
        return new StoredModel(model.scenario(), model.id(), model.subset(), model.kind(), model.graph(),
                model.forwardGraph(), model.reverseGraph(), model.version(), model.description(),
                model.scenarioTime(), model.created(), model.modelingAuthoritySet(), model.profiles(),
                model.dependentOn(), model.supersedes(), false, model.tripleCount(), model.subjectBase(),
                model.cimNamespace(), model.chainDepth());
    }
}
