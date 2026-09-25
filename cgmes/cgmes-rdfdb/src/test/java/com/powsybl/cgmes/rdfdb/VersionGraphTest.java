/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The planner: the lowest-common-ancestor walk, the reasons it refuses, and the scenario short-circuit.
 *
 * <p>The chain under test is A(1.0, root) &rarr; B(1.1, fast steady state) &rarr; C(1.2, a property no in-place
 * update reads) &rarr; D(1.3, fast steady state again). Everything the planner has to decide is a question about
 * where two snapshots sit on that chain, so it is built once and asked many times.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class VersionGraphTest {

    private static final String S = "2016-01-01";
    private static final String OTHER = "other";
    private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
    private static final CgmesSubset SSH = CgmesSubset.STEADY_STATE_HYPOTHESIS;

    static Stream<Arguments> backends() {
        return Backends.backends();
    }

    private static Properties params() {
        Properties p = new Properties();
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        return p;
    }

    private static ReadOnlyDataSource be() {
        return CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
    }

    /** A fast difference: a property the steady-state update queries read. */
    private static DifferenceModelSet fast(SnapshotInfo parent, String id, String value) {
        return one(parent, id, CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer", "EnergyConsumer.p",
                value), CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer", "EnergyConsumer.p", "0.0"));
    }

    /** A difference no in-place update can apply: a name change is not on the fast route. */
    private static DifferenceModelSet slow(SnapshotInfo parent, String id) {
        return one(parent, id, CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer",
                        "IdentifiedObject.name", "renamed"),
                CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer", "IdentifiedObject.name", "before"));
    }

    private static DifferenceModelSet one(SnapshotInfo parent, String id, CgmesStatement forward,
                                          CgmesStatement reverse) {
        DifferenceModelHeader header = DifferenceModelHeader.builder(id, SSH, CIM16)
                .supersedes(List.of(parent.state().get(SSH)))
                .profiles(List.of("http://entsoe.eu/CIM/SteadyStateHypothesis/1/1"))
                .build();
        return new DifferenceModelSet(List.of(new DifferenceModel(header, List.of(forward), List.of(reverse),
                List.of())));
    }

    /** A(1.0) -> B(1.1 fast) -> C(1.2 not fast) -> D(1.3 fast), in scenario S, with a second scenario present. */
    private record Chain(RdfDbConnection db, SnapshotInfo a, SnapshotInfo b, SnapshotInfo c, SnapshotInfo d) {
    }

    private static Chain chain(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "version-graph"));
        db.clear(S);
        db.clear(OTHER);
        SnapshotCatalog catalog = db.snapshots(S);
        SnapshotInfo a = catalog.putFull(be(), null, SnapshotRef.of(S, "1.0"), params(), ReportNode.NO_OP);
        SnapshotInfo b = catalog.putDiff(fast(a, "urn:uuid:ssh-b", "11.0"), SnapshotRef.of(S, "1.1"));
        SnapshotInfo c = catalog.putDiff(slow(b, "urn:uuid:ssh-c"), SnapshotRef.of(S, "1.2"));
        SnapshotInfo d = catalog.putDiff(fast(c, "urn:uuid:ssh-d", "13.0"), SnapshotRef.of(S, "1.3"));
        catalog.verify();
        return new Chain(db, a, b, c, d);
    }

    private static UpdatePlan plan(Chain chain, SnapshotInfo from, SnapshotInfo to) {
        return plan(chain, from, to, new RdfDbUpdateOptions());
    }

    private static UpdatePlan plan(Chain chain, SnapshotInfo from, SnapshotInfo to, RdfDbUpdateOptions options) {
        return chain.db.versionGraph(S).plan(from.iri(), to.ref(), options);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void oneStepForwardIsADiff(String backend) {
        try (RdfDbConnection db = chain(backend).db) {
            Chain chain = new Chain(db, db.snapshots(S).find(SnapshotRef.of(S, "1.0")).orElseThrow(),
                    db.snapshots(S).find(SnapshotRef.of(S, "1.1")).orElseThrow(),
                    db.snapshots(S).find(SnapshotRef.of(S, "1.2")).orElseThrow(),
                    db.snapshots(S).find(SnapshotRef.of(S, "1.3")).orElseThrow());

            UpdatePlan ab = plan(chain, chain.a, chain.b);
            assertThat(ab.kind()).isEqualTo(UpdatePlan.Kind.DIFF);
            assertThat(ab.steps()).hasSize(1);
            assertThat(ab.steps().get(0).inverted()).isFalse();
            assertThat(ab.steps().get(0).model().id()).isEqualTo("urn:uuid:ssh-b");
            assertThat(ab.chainLength()).isEqualTo(1);
            assertThat(ab.targetState().get(SSH)).isEqualTo("urn:uuid:ssh-b");
            assertThat(ab.reasons()).isEmpty();

            UpdatePlan same = plan(chain, chain.d, chain.d);
            assertThat(same.kind()).isEqualTo(UpdatePlan.Kind.NOOP);
            assertThat(same.steps()).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void backwardsIsADiffOfInvertedSteps(String backend) {
        Chain chain = chain(backend);
        try (RdfDbConnection db = chain.db) {
            UpdatePlan ba = plan(chain, chain.b, chain.a);
            assertThat(ba.kind()).isEqualTo(UpdatePlan.Kind.DIFF);
            assertThat(ba.steps()).hasSize(1);
            assertThat(ba.steps().get(0).inverted()).isTrue();
            assertThat(ba.isAllInverted()).isTrue();
            assertThat(ba.targetState().get(SSH)).isEqualTo(chain.a.state().get(SSH));

            UpdatePlan dc = plan(chain, chain.d, chain.c);
            assertThat(dc.kind()).isEqualTo(UpdatePlan.Kind.DIFF);
            assertThat(dc.steps()).hasSize(1);
            assertThat(dc.steps().get(0).inverted()).isTrue();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aStepThatIsNotFastMakesTheWholePathFull(String backend) {
        Chain chain = chain(backend);
        try (RdfDbConnection db = chain.db) {
            UpdatePlan ad = plan(chain, chain.a, chain.d);
            assertThat(ad.kind()).isEqualTo(UpdatePlan.Kind.FULL);
            assertThat(ad.reasons()).anyMatch(reason -> reason.contains("urn:uuid:ssh-c"));

            UpdatePlan bd = plan(chain, chain.b, chain.d);
            assertThat(bd.kind()).isEqualTo(UpdatePlan.Kind.FULL);

            // ...while the pieces that avoid it stay fast
            assertThat(plan(chain, chain.c, chain.d).kind()).isEqualTo(UpdatePlan.Kind.DIFF);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aPathLongerThanAllowedIsFull(String backend) {
        Chain chain = chain(backend);
        try (RdfDbConnection db = chain.db) {
            SnapshotInfo e = db.snapshots(S).putDiff(fast(chain.d, "urn:uuid:ssh-e", "15.0"),
                    SnapshotRef.of(S, "1.4"));
            assertThat(plan(chain, chain.c, e).kind()).isEqualTo(UpdatePlan.Kind.DIFF);

            UpdatePlan limited = db.versionGraph(S).plan(chain.c.iri(), e.ref(),
                    new RdfDbUpdateOptions().setMaxDiffChain(1));
            assertThat(limited.kind()).isEqualTo(UpdatePlan.Kind.FULL);
            assertThat(limited.reasons()).anyMatch(reason -> reason.contains("more than the allowed 1"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aCheckpointIsRecommendedWhenTheChainGetsLong(String backend) {
        Chain chain = chain(backend);
        try (RdfDbConnection db = chain.db) {
            assertThat(plan(chain, chain.a, chain.d).checkpointRecommended()).isFalse();
            UpdatePlan eager = plan(chain, chain.a, chain.d, new RdfDbUpdateOptions().setCheckpointAfter(1));
            assertThat(eager.checkpointRecommended()).isTrue();
            assertThat(eager.distanceToFullSnapshot()).isEqualTo(3);
            // recommended() answers with the options it is given, not with the ones the plan was made with
            assertThat(Checkpoint.recommended(eager, new RdfDbUpdateOptions().setCheckpointAfter(1))).isTrue();
            assertThat(Checkpoint.recommended(eager, new RdfDbUpdateOptions())).isFalse();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void materialisationStartsAtTheNearestFullAncestor(String backend) {
        Chain chain = chain(backend);
        try (RdfDbConnection db = chain.db) {
            MaterializationPlan before = db.versionGraph(S).materialization(chain.d.iri());
            assertThat(before.steps()).hasSize(3);
            assertThat(before.startModel().get(SSH).snapshot()).isEqualTo(chain.a.iri());
            assertThat(before.targetState().get(SSH)).isEqualTo("urn:uuid:ssh-d");

            Checkpoint.create(db, chain.c.ref());

            MaterializationPlan after = db.versionGraph(S).materialization(chain.d.iri());
            assertThat(after.steps()).hasSize(1);
            assertThat(after.startModel().get(SSH).snapshot()).isEqualTo(chain.c.iri());
            // The profiles the chain never touched are inherited by the checkpoint, so the walk stops there too
            // but the graph it names is still the root's instance file
            assertThat(after.startModel().get(CgmesSubset.TOPOLOGY).modelId())
                    .isEqualTo(chain.a.fullModels().get(CgmesSubset.TOPOLOGY));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void anotherScenarioIsFullWithoutAQuery(String backend) {
        Chain chain = chain(backend);
        try (RdfDbConnection db = chain.db) {
            SnapshotInfo there = db.snapshots(OTHER).putFull(be(), null, SnapshotRef.of(OTHER, "1.0"), params(),
                    ReportNode.NO_OP);

            UpdatePlan across = db.versionGraph(S).plan(there.iri(), chain.b.ref(), new RdfDbUpdateOptions());

            assertThat(across.kind()).isEqualTo(UpdatePlan.Kind.FULL);
            assertThat(across.reasons()).anyMatch(reason -> reason.contains("network is at scenario 'other'")
                    && reason.contains("target is scenario '" + S + "'"));
            assertThat(across.steps()).isEmpty();
            assertThat(across.from()).isNull();

            assertThatThrownBy(() -> db.versionGraph(S).plan(chain.a.iri(), SnapshotRef.of(OTHER, "1.0"),
                    new RdfDbUpdateOptions())).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("cannot address scenario 'other'");
            assertThatThrownBy(() -> db.versionGraph(S).materialization(there.iri()))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("does not belong to scenario");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void anUnknownTargetIsRefused(String backend) {
        Chain chain = chain(backend);
        try (RdfDbConnection db = chain.db) {
            assertThatThrownBy(() -> db.versionGraph(S).plan(chain.a.iri(), SnapshotRef.of(S, "9.9"),
                    new RdfDbUpdateOptions())).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("holds no snapshot");
        }
    }

    /**
     * Many sides in one request answer exactly what one request per side would have answered.
     *
     * <p>This is the query a bulk load of a whole day sends. It is compared against the single-side planner it
     * replaces, side by side, so that "one request instead of ninety-six" is a statement about round trips and not
     * about results.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void chainsOfManySidesInOneRequest(String backend) {
        Chain chain = chain(backend);
        try (RdfDbConnection db = chain.db) {
            VersionGraph graph = db.versionGraph(S);
            Map<String, VersionGraph.Start> starts = new java.util.LinkedHashMap<>();
            starts.put("A0", new VersionGraph.Start(chain.a.iri(), null));
            starts.put("B0", new VersionGraph.Start(null, chain.b.ref()));
            starts.put("B1", new VersionGraph.Start(null, chain.c.ref()));
            starts.put("B2", new VersionGraph.Start(null, chain.d.ref()));

            VersionGraph.Chains chains = graph.chains(starts);

            assertThat(chains.bySide().keySet()).containsExactly("A0", "B0", "B1", "B2");
            assertThat(chains.bySide().get("A0")).extracting(SnapshotInfo::iri).containsExactly(chain.a.iri());
            assertThat(chains.bySide().get("B0")).extracting(SnapshotInfo::iri)
                    .containsExactly(chain.b.iri(), chain.a.iri());
            assertThat(chains.bySide().get("B2")).extracting(SnapshotInfo::iri)
                    .containsExactly(chain.d.iri(), chain.c.iri(), chain.b.iri(), chain.a.iri());
            // Every difference the chains name came back with them, so nothing has to be looked up afterwards
            assertThat(chains.diffs().keySet()).contains("urn:uuid:ssh-b", "urn:uuid:ssh-c", "urn:uuid:ssh-d");
            assertThat(chains.fullGraphs()).isNotEmpty();
        }
    }

    /** A side whose address no snapshot has comes back empty rather than throwing. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aSideThatResolvesToNothingIsEmpty(String backend) {
        Chain chain = chain(backend);
        try (RdfDbConnection db = chain.db) {
            VersionGraph.Chains chains = db.versionGraph(S).chains(Map.of(
                    "B0", new VersionGraph.Start(null, SnapshotRef.of(S, "9.9"))));
            assertThat(chains.bySide().get("B0")).isEmpty();
        }
    }

    /** The path read off two chains of the multi-side query is the plan the single-side query answers. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void pathEqualsPlan(String backend) {
        Chain chain = chain(backend);
        try (RdfDbConnection db = chain.db) {
            VersionGraph graph = db.versionGraph(S);
            VersionGraph.Chains chains = graph.chains(Map.of(
                    "A0", new VersionGraph.Start(chain.b.iri(), null),
                    "B0", new VersionGraph.Start(chain.d.iri(), null),
                    "B1", new VersionGraph.Start(chain.a.iri(), null)));

            UpdatePlan forward = graph.path(chains.bySide().get("A0"), chains.bySide().get("B0"),
                    chains.diffs(), new RdfDbUpdateOptions());
            UpdatePlan expectedForward = plan(chain, chain.b, chain.d);
            assertThat(forward.kind()).isEqualTo(expectedForward.kind());
            assertThat(forward.reasons()).isEqualTo(expectedForward.reasons());
            assertThat(forward.steps().stream().map(step -> step.model().id()).toList())
                    .isEqualTo(expectedForward.steps().stream().map(step -> step.model().id()).toList());

            UpdatePlan backward = graph.path(chains.bySide().get("A0"), chains.bySide().get("B1"),
                    chains.diffs(), new RdfDbUpdateOptions());
            UpdatePlan expectedBackward = plan(chain, chain.b, chain.a);
            assertThat(backward.kind()).isEqualTo(expectedBackward.kind());
            assertThat(backward.steps().stream().map(step -> step.model().id()).toList())
                    .isEqualTo(expectedBackward.steps().stream().map(step -> step.model().id()).toList());
            assertThat(backward.steps()).allMatch(UpdatePlan.DiffStep::inverted);

            UpdatePlan nothing = graph.path(chains.bySide().get("A0"), chains.bySide().get("A0"),
                    chains.diffs(), new RdfDbUpdateOptions());
            assertThat(nothing.kind()).isEqualTo(UpdatePlan.Kind.NOOP);
        }
    }

    /** The variant-safety flag travels with the plan rows, so a path can be refused without a network. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void thePlanRowsCarryTheVariantSafetyFlag(String backend) {
        Chain chain = chain(backend);
        try (RdfDbConnection db = chain.db) {
            UpdatePlan safe = plan(chain, chain.a, chain.b);
            assertThat(safe.steps()).isNotEmpty();
            assertThat(safe.steps()).allMatch(step -> Boolean.TRUE.equals(step.model().variantSafe()));
            assertThat(safe.variantUnsafeSteps()).isEmpty();
        }
    }
}
