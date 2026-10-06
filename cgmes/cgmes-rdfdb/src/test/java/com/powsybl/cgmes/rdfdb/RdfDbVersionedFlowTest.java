/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.eclipse.rdf4j.model.Value;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.powsybl.cgmes.rdfdb.Backends.BE;
import static com.powsybl.cgmes.rdfdb.Backends.NL;
import static com.powsybl.cgmes.rdfdb.Backends.microGridBe;
import static com.powsybl.cgmes.rdfdb.Backends.microGridNl;
import static com.powsybl.cgmes.rdfdb.Backends.params;
import static com.powsybl.cgmes.rdfdb.Backends.ref;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loading, updating and checkpointing an addressed version: the whole round trip, on both backends.
 *
 * <p>The promise the versioning layer makes has two halves that have to agree. A network brought to a version
 * <em>in place</em>, by applying the differences it has not seen, and the same version <em>materialised</em> from
 * the database and converted from scratch, must be the same network; and both must be the network the instance
 * files of that version would have produced. Every case below asserts at least two of those three against each
 * other.</p>
 *
 * <p>A second scenario holding the same files is always present, so that nothing passes because the database only
 * ever held one day.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbVersionedFlowTest {

    private static final String S = "2016-01-01";
    private static final String OTHER = "other";
    private static final CgmesSubset SSH = CgmesSubset.STEADY_STATE_HYPOTHESIS;

    /** The identity of a network is its own assertion, so it is kept out of the network comparison. */
    private static final Set<String> IDENTITY = Set.of("cgmesMetadataModels", "rdfDbProvenance");

    /** Two scenarios of the same files, each with a root snapshot at version 1.0. */
    private static RdfDbConnection twoScenarios(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "versioned-flow"));
        db.clear(S);
        db.clear(OTHER);
        db.snapshots(S).putFull(microGridBe(), null, ref(S, 1), null, params(), ReportNode.NO_OP);
        db.snapshots(OTHER).putFull(microGridBe(), null, ref(OTHER, 1), null, params(), ReportNode.NO_OP);
        return db;
    }

    private static Network load(RdfDbConnection db, String scenario, Integer version) {
        return RdfDbNetworkLoader.load(db, SnapshotRef.of(scenario, BE, (Instant) null, version), null, params(),
                ReportNode.NO_OP);
    }

    private static UpdateResult update(Network network, RdfDbConnection db, SnapshotRef target,
                                       RdfDbUpdateOptions options) {
        return RdfDbNetworkLoader.update(network, db, target, options, params(), ReportNode.NO_OP);
    }

    // ------------------------------------------------------------------ loading

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void loadAtVersionEqualsFileImport(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network fromDb = load(db, S, 1);
            Network fromFiles = Network.read(microGridBe(), params());

            Networks.assertSameNetwork(fromFiles, fromDb, IDENTITY);
            RdfDbProvenance provenance = fromDb.getExtension(RdfDbProvenance.class);
            assertThat(provenance.scenario()).isEqualTo(S);
            assertThat(provenance.snapshot()).contains(RdfDbNames.snapshot(S, BE, Instant.parse("2014-06-01T10:30:00Z"), 1));
            assertThat(db.snapshots(S).snapshotOf(fromDb)).isPresent();
            assertThat(db.snapshots(OTHER).snapshotOf(fromDb)).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void theSecondModellingAuthorityLoadsWithTheSharedBoundary(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            // NL is stored after BE: it links BE's boundary graphs instead of uploading them
            db.snapshots(S).putFull(microGridNl(), null, SnapshotRef.latest(S, NL), null, params(),
                    ReportNode.NO_OP);

            Network fromDb = RdfDbNetworkLoader.load(db, SnapshotRef.latest(S, NL), null, params(),
                    ReportNode.NO_OP);
            Networks.assertSameNetwork(Network.read(microGridNl(), params()), fromDb, IDENTITY);
            // And the first authority still loads as before
            Network be = load(db, S, 1);
            Networks.assertSameNetwork(Network.read(microGridBe(), params()), be, IDENTITY);
            // The full route of an update reaches the second authority the same way
            UpdateResult toNl = update(be, db, SnapshotRef.latest(S, NL), new RdfDbUpdateOptions());
            assertThat(toNl.route()).isEqualTo(UpdateResult.Route.FULL_RELOAD);
            Networks.assertSameNetwork(Network.read(microGridNl(), params()), toNl.network(), IDENTITY);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void latestResolvesTheHead(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network sender = load(db, S, 1);
            Changes.export(sender, db, ref(S, 2), n -> Changes.moveLoad(n, 12.0));

            Network latest = load(db, S, null);

            Networks.assertSameNetworkIgnoringStateVariables(sender, latest, IDENTITY, Set.of(Changes.LOAD_ID));
            assertThat(db.snapshots(S).snapshotOf(latest).orElseThrow().version()).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------ updating along the chain

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void updateAlongFastChain(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network sender = load(db, S, 1);
            Network receiver = load(db, S, 1);
            RdfDbExport.SnapshotResult exported =
                    Changes.export(sender, db, ref(S, 2), n -> Changes.moveLoad(n, 12.0));

            assertThat(exported.snapshot().version()).isEqualTo(2);
            // Derived from pdb:fastPredicatesOnly of the member, and never written onto the snapshot node: the
            // retired pdb:fast term appears nowhere in the metadata graph of a scenario this release wrote
            assertThat(exported.snapshot().fast()).isTrue();
            assertThat(db.sparql(S).ask("ASK { GRAPH <" + RdfDbNames.metaGraph(S) + "> { ?s <"
                    + RdfDbVocabulary.NS + "fast> ?o } }")).isFalse();

            UpdateResult result = update(receiver, db, ref(S, 2), new RdfDbUpdateOptions());

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(result.network()).isSameAs(receiver);
            assertThat(result.statistics().diffCount()).isEqualTo(1);
            Networks.assertSameNetworkIgnoringStateVariables(sender, receiver, IDENTITY, Set.of(Changes.LOAD_ID));
            assertThat(db.snapshots(S).snapshotOf(receiver).orElseThrow().version()).isEqualTo(2);
            assertThat(receiver.getExtension(RdfDbProvenance.class).snapshot())
                    .contains(exported.snapshot().iri());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anUpdateToWhereTheNetworkAlreadyIsDoesNothing(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network network = load(db, S, 1);
            UpdateResult result = update(network, db, ref(S, 1), new RdfDbUpdateOptions());
            assertThat(result.route()).isEqualTo(UpdateResult.Route.NOOP);
            assertThat(result.network()).isSameAs(network);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void updateBackwardsUndoesTheDifference(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network base = load(db, S, 1);
            Network sender = load(db, S, 1);
            Changes.export(sender, db, ref(S, 2), n -> Changes.moveLoad(n, 12.0));

            UpdateResult result = update(sender, db, ref(S, 1), new RdfDbUpdateOptions());

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            Networks.assertSameNetworkIgnoringStateVariables(base, sender, IDENTITY, Set.of(Changes.LOAD_ID));
            assertThat(db.snapshots(S).snapshotOf(sender).orElseThrow().version()).isEqualTo(1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anAccumulatedChainIsAppliedInOneUpdate(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network sender = load(db, S, 1);
            Network receiver = load(db, S, 1);
            for (int i = 1; i <= 10; i++) {
                double delta = i % 2 == 0 ? 11.0 : 13.0;
                Changes.export(sender, db, ref(S, i + 1), n -> Changes.moveLoad(n, delta));
            }
            assertThat(db.snapshots(S).snapshots()).hasSize(11);
            db.snapshots(S).verify();

            UpdateResult result = update(receiver, db, SnapshotRef.latest(S, BE), new RdfDbUpdateOptions());

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(result.statistics().diffCount()).isEqualTo(10);
            Networks.assertSameNetworkIgnoringStateVariables(sender, receiver, IDENTITY, Set.of(Changes.LOAD_ID));
            // and the same state materialised from scratch
            Network materialised = load(db, S, 11);
            Networks.assertSameNetworkIgnoringStateVariables(receiver, materialised, IDENTITY,
                    Set.of(Changes.LOAD_ID));
        }
    }

    // ------------------------------------------------------------------ checkpoints

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void checkpointEqualsClientSideMaterialisation(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network sender = load(db, S, 1);
            for (int i = 1; i <= 5; i++) {
                double delta = i % 2 == 0 ? 11.0 : 13.0;
                Changes.export(sender, db, ref(S, i + 1), n -> Changes.moveLoad(n, delta));
            }
            Network before = load(db, S, 6);
            MaterializationPlan plainPlan = db.versionGraph(S)
                    .materialization(ref(S, 6));
            assertThat(plainPlan.steps()).hasSize(5);

            SnapshotInfo checkpointed = Checkpoint.create(db, ref(S, 6));

            assertThat(checkpointed.hasFull()).isTrue();
            assertThat(checkpointed.kind()).isEqualTo(SnapshotInfo.Kind.DIFF);
            MaterializationPlan afterPlan = db.versionGraph(S).materialization(ref(S, 6));
            assertThat(afterPlan.steps()).isEmpty();

            Network after = load(db, S, 6);
            Networks.assertSameNetwork(before, after, IDENTITY);
            assertThat(db.snapshots(S).snapshotOf(after).orElseThrow().version()).isEqualTo(6);
            // The materialised graph carries the identity of the state it holds, so a client that never reads the
            // metadata graph still sees the right model
            String materialisedGraph = afterPlan.startModel().get(SSH).graph();
            List<Map<String, Value>> headers = db.sparql(S).select(RdfDbVocabulary.PREFIXES
                    + "SELECT ?h WHERE { GRAPH <" + materialisedGraph + "> { ?h a md:FullModel } }");
            assertThat(headers).hasSize(1);
            assertThat(headers.get(0).get("h").stringValue())
                    .isEqualTo(checkpointed.state().get(SSH));

            // Idempotent, and the earlier versions are still reachable
            assertThat(Checkpoint.create(db, ref(S, 6)).iri()).isEqualTo(checkpointed.iri());
            Networks.assertSameNetwork(load(db, S, 1), Network.read(microGridBe(), params()), IDENTITY);
            db.snapshots(S).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void theF1AndF2EntryPointsMeanTheNewestSnapshot(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network sender = load(db, S, 1);
            Changes.export(sender, db, ref(S, 2), n -> Changes.moveLoad(n, 12.0));
            Changes.export(sender, db, ref(S, 3), n -> Changes.moveLoad(n, 15.0));

            // "The scenario", asked the pre-versioning way, is its newest snapshot
            Network f1 = RdfDbNetworkLoader.load(db, S, null, params(), ReportNode.NO_OP);
            Networks.assertSameNetworkIgnoringStateVariables(load(db, S, 3), f1, IDENTITY,
                    Set.of(Changes.LOAD_ID));
            assertThat(db.snapshots(S).snapshotOf(f1).orElseThrow().version()).isEqualTo(3);

            // ...and so is "the head" of an update
            Network receiver = load(db, S, 1);
            UpdateResult result = RdfDbNetworkLoader.update(receiver, db, S, DiffTarget.head(),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP);
            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(db.snapshots(S).snapshotOf(receiver).orElseThrow().version()).isEqualTo(3);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void checkpointKeepsTheModelCatalogueUsable(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network sender = load(db, S, 1);
            Changes.export(sender, db, ref(S, 2), n -> Changes.moveLoad(n, 12.0));
            Changes.export(sender, db, ref(S, 3), n -> Changes.moveLoad(n, 15.0));
            String headBefore = db.catalog(S).head(SSH).orElseThrow().id();

            Checkpoint.create(db, ref(S, 3));

            // A materialised copy is not a stored model: the profile still has one head and one full model
            assertThat(db.catalog(S).head(SSH).orElseThrow().id()).isEqualTo(headBefore);
            assertThat(db.catalog(S).full(SSH)).isPresent();
            assertThat(db.catalog(S).models().stream().map(StoredModel::id))
                    .doesNotContain(RdfDbNames.materialized(S, BE, Instant.parse("2014-06-01T10:30:00Z"), 3, "SSH"));
            // ...and the pre-versioning entry points still work
            Network f1 = RdfDbNetworkLoader.load(db, S, null, params(), ReportNode.NO_OP);
            assertThat(db.snapshots(S).snapshotOf(f1).orElseThrow().version()).isEqualTo(3);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void loadCgmesRejectedOnVersionedCatalog(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            assertThatThrownBy(() -> db.loadCgmes(S, microGridBe(), null, params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("is versioned: use SnapshotCatalog.putFull");
        }
    }

    // ------------------------------------------------------------------ scenarios never mix

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void updateAcrossScenariosReloads(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network there = load(db, OTHER, 1);
            Changes.export(there, db, ref(OTHER, 2), n -> Changes.moveLoad(n, 12.0));
            Network here = load(db, S, 1);

            UpdateResult result = update(here, db, ref(OTHER, 2), new RdfDbUpdateOptions());

            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_RELOAD);
            assertThat(result.reasons()).anyMatch(reason -> reason.contains("never cross scenarios"));
            assertThat(result.network()).isNotSameAs(here);
            assertThat(result.network().getExtension(RdfDbProvenance.class).scenario()).isEqualTo(OTHER);
            Networks.assertSameNetworkIgnoringStateVariables(there, result.network(), IDENTITY,
                    Set.of(Changes.LOAD_ID));
            // The original is untouched and still updatable inside its own scenario
            assertThat(db.snapshots(S).snapshotOf(here).orElseThrow().version()).isEqualTo(1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aCrossScenarioUpdateCanBeRefusedInsteadOfReloading(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network here = load(db, S, 1);
            UpdateResult result = update(here, db, ref(OTHER, 1),
                    new RdfDbUpdateOptions().setAllowFullReload(false));
            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_REQUIRED);
            assertThat(result.network()).isSameAs(here);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void exportIntoOtherScenarioRejected(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network here = load(db, S, 1);
            List<NetworkEvent> events = Changes.record(here, n -> Changes.moveLoad(n, 12.0));
            assertThatThrownBy(() -> RdfDbExport.export(here, events, db, ref(OTHER, 2),
                    new CgmesDiffExport.ExportOptions()))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("diffs never cross scenarios");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void twoScenariosKeepIndependentChains(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network senderHere = load(db, S, 1);
            Network senderThere = load(db, OTHER, 1);
            for (int i = 1; i <= 4; i++) {
                double delta = 10.0 + i;
                Changes.export(senderHere, db, ref(S, i + 1), n -> Changes.moveLoad(n, delta));
            }
            for (int i = 1; i <= 2; i++) {
                double delta = 20.0 + i;
                Changes.export(senderThere, db, ref(OTHER, i + 1), n -> Changes.moveLoad(n, delta));
            }

            Network client = load(db, OTHER, 1);
            UpdateResult result = update(client, db, SnapshotRef.latest(OTHER, BE), new RdfDbUpdateOptions());

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(result.statistics().diffCount()).isEqualTo(2);
            assertThat(db.snapshots(S).snapshots()).hasSize(5);
            assertThat(db.snapshots(OTHER).snapshots()).hasSize(3);
            Networks.assertSameNetworkIgnoringStateVariables(senderThere, client, IDENTITY,
                    Set.of(Changes.LOAD_ID));
            db.snapshots(S).verify();
            db.snapshots(OTHER).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aPlanAcrossScenariosSendsNoQuery(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network here = load(db, S, 1);
            UpdatePlan plan = db.versionGraph(OTHER).plan(here, ref(OTHER, 1),
                    new RdfDbUpdateOptions());
            assertThat(plan.kind()).isEqualTo(UpdatePlan.Kind.FULL);
            assertThat(plan.reasons()).anyMatch(reason -> reason.contains("network is at scenario '" + S + "'"));
            assertThat(plan.steps()).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void blankScenarioRejected(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            assertThatThrownBy(() -> RdfDbNetworkLoader.load(db, SnapshotRef.latest("", BE), null, params(),
                    ReportNode.NO_OP)).isInstanceOf(RdfDbException.class);
            assertThatThrownBy(() -> db.snapshots(" ")).isInstanceOf(RdfDbException.class);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anOffsetDateTimeAddressesTheSameSnapshot(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network sender = load(db, S, 1);
            Changes.export(sender, db, ref(S, 2), n -> Changes.moveLoad(n, 12.0));

            Network receiver = load(db, S, 1);
            UpdateResult result = RdfDbNetworkLoader.update(receiver, db,
                    SnapshotRef.of(S, BE, OffsetDateTime.parse("2014-06-01T12:30:00+02:00"), 2),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            Networks.assertSameNetworkIgnoringStateVariables(
                    load(db, S, 2), receiver, IDENTITY, Set.of(Changes.LOAD_ID));
            assertThat(SSH).isEqualTo(CgmesSubset.STEADY_STATE_HYPOTHESIS);
        }
    }
}
