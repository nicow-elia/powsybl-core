/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.diff.CgmesDiffImport;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.StatementDiff;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.powsybl.cgmes.rdfdb.Backends.BE;
import static com.powsybl.cgmes.rdfdb.Backends.microGridBe;
import static com.powsybl.cgmes.rdfdb.Backends.params;
import static com.powsybl.cgmes.rdfdb.Backends.ref;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The timestamp dimension: timestamps hanging off the base chain of a tree, and versions inside them.
 *
 * <p>A scenario is one day. The root of a modelling authority's tree is its base timestamp; every other timestamp
 * of the day is a snapshot <em>pinned</em> to another snapshot of the tree by a {@code pdb:TimestampEdge}, with a
 * version chain of its own below it. An ingested timestamp is pinned to the latest rollover at or before it &mdash;
 * the root until a later snapshot is flagged &mdash; so every timestamp is "its rollover plus a handful of
 * differences" however many study versions the other timestamps accumulate, and a walk from one timestamp to
 * another is a walk through their common pin.</p>
 *
 * <p>The day under test is the MicroGrid base case, whose scenario time is 10:30Z; the timestamps below it are
 * therefore 11:00Z, 11:15Z and 11:30Z of the same day.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbTimestampFlowTest {

    private static final String S = "2016-01-01";
    private static final String OTHER = "other";
    private static final Instant BASE = Instant.parse("2014-06-01T10:30:00Z");
    private static final Instant T1 = Instant.parse("2014-06-01T11:00:00Z");
    private static final Instant T2 = Instant.parse("2014-06-01T11:15:00Z");
    private static final Instant T3 = Instant.parse("2014-06-01T11:30:00Z");
    private static final Instant T4 = Instant.parse("2014-06-01T11:45:00Z");
    private static final Instant BEFORE_T1 = Instant.parse("2014-06-01T10:45:00Z");
    private static final String SSH = Profiles.SSH;
    private static final String EQ = Profiles.EQ;
    private static final Set<String> IDENTITY = Set.of("cgmesMetadataModels", "rdfDbProvenance");

    private static RdfDbConnection twoDays(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "timestamp-flow"));
        db.clear(S);
        db.clear(OTHER);
        db.snapshots(S).putFull(microGridBe(), null, ref(S, 1), null, params(), ReportNode.NO_OP);
        db.snapshots(OTHER).putFull(microGridBe(), null, ref(OTHER, 1), null, params(), ReportNode.NO_OP);
        return db;
    }

    private static Network load(RdfDbConnection db, String scenario, Integer version, Instant timestamp) {
        return RdfDbNetworkLoader.load(db, SnapshotRef.of(scenario, BE, timestamp, version == null ? null : version.toString()), null, params(),
                ReportNode.NO_OP);
    }

    // ------------------------------------------------------------------ writing timestamps

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aNewTimestampHangsOffTheBaseHead(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, 1, null);
            SnapshotInfo base = db.snapshots(S).find(ref(S, 1)).orElseThrow();

            SnapshotInfo root = Changes.export(sender, db, ref(S, 1, T1),
                    n -> Changes.moveLoad(n, 12.0)).snapshot();

            assertThat(root.timestamp()).isEqualTo(T1);
            assertThat(root.edge()).isEqualTo(SnapshotInfo.EdgeKind.TIMESTAMP);
            assertThat(root.parent()).isEqualTo(base.iri());
            assertThat(root.depth()).isEqualTo(1);
            assertThat(root.timestampRoot()).isEqualTo(root.iri());
            assertThat(root.state().get(SSH)).isIn(root.members());
            db.snapshots(S).verify();

            List<SnapshotCatalog.TimestampInfo> timestamps = db.snapshots(S).timestamps(BE);
            assertThat(timestamps).hasSize(2);
            assertThat(timestamps.get(0).timestamp()).isEqualTo(BASE);
            assertThat(timestamps.get(1).timestamp()).isEqualTo(T1);
            assertThat(timestamps.get(1).modellingAuthority()).isEqualTo(BE);
            assertThat(timestamps.get(1).pin()).isEqualTo(base.iri());
            assertThat(timestamps.get(1).versionCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void versionsChainInsideTheTimestamp(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, 1, null);
            SnapshotInfo root = Changes.export(sender, db, ref(S, 1, T1),
                    n -> Changes.moveLoad(n, 12.0)).snapshot();
            SnapshotInfo second = Changes.export(sender, db, ref(S, 2, T1),
                    n -> Changes.moveLoad(n, 14.0)).snapshot();

            assertThat(second.edge()).isEqualTo(SnapshotInfo.EdgeKind.VERSION);
            assertThat(second.parent()).isEqualTo(root.iri());
            assertThat(second.timestampRoot()).isEqualTo(root.iri());
            assertThat(second.timestamp()).isEqualTo(T1);
            assertThat(db.snapshots(S).versions(BE, T1)).hasSize(2);
            assertThat(db.snapshots(S).versions(BE, null)).hasSize(1);
            assertThat(db.snapshots(S).nextVersionName(SnapshotRef.latestAt(S, BE, T1))).isEqualTo("3");
            db.snapshots(S).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aSecondRootForOneTimestampIsRejected(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network first = load(db, S, 1, null);
            Network second = load(db, S, 1, null);
            Changes.export(first, db, ref(S, 1, T1), n -> Changes.moveLoad(n, 12.0));

            // The second writer still thinks 11:00 does not exist, and its diff supersedes the base state
            assertThatThrownBy(() -> Changes.export(second, db, ref(S, 20, T1),
                    n -> Changes.moveLoad(n, 15.0)))
                    .isInstanceOf(RdfDbConflictException.class);
            db.snapshots(S).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aRecordedTimestampHangsOffTheDeepestSnapshotStatingWhatItSupersedes(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, 1, null);
            SnapshotInfo t1 = Changes.export(sender, db, ref(S, 1, T1), n -> Changes.moveLoad(n, 12.0)).snapshot();

            // The sender now sits at 11:00, and what its difference supersedes is 11:00's steady state
            SnapshotInfo t2 = Changes.export(sender, db, ref(S, 1, T2), n -> Changes.moveLoad(n, 16.0)).snapshot();

            assertThat(t2.edge()).isEqualTo(SnapshotInfo.EdgeKind.TIMESTAMP);
            assertThat(t2.parent()).isEqualTo(t1.iri());
            assertThat(t2.depth()).isEqualTo(2);
            Networks.assertSameNetworkIgnoringStateVariables(sender, load(db, S, 1, T2), IDENTITY,
                    Set.of(Changes.LOAD_ID));
            db.snapshots(S).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aRecordedTimestampHangsOffTheSnapshotItsSenderIsAt(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            Network sender = load(db, S, 1, null);
            SnapshotInfo t1 = Changes.export(sender, db, ref(S, 1, T1), n -> Changes.moveLoad(n, 12.0)).snapshot();
            // 11:15, pinned to 11:00, drifts the equipment only: it states 11:00's steady state one level deeper
            SnapshotInfo deeper = catalog.putAsDiff(TimestampFixtures.eqDrift(2, T2, "eq-only"), null, ref(S, 1, T2),
                    Set.of(EQ), t1.ref(), params(), ReportNode.NO_OP);
            assertThat(deeper.state().get(SSH)).isEqualTo(t1.state().get(SSH));
            assertThat(deeper.depth()).isGreaterThan(t1.depth());
            // An ingested difference names the capability version of its writer, as a recorded one does
            assertThat(db.catalog(S).models(deeper.members()).values()).isNotEmpty()
                    .allMatch(model -> FastRouteCapabilities.version().equals(model.capabilities()));

            // The sender is at 11:00 and never had 11:15's equipment: its change is filed under 11:00
            SnapshotInfo t3 = Changes.export(sender, db, ref(S, 1, T3), n -> Changes.moveLoad(n, 16.0)).snapshot();

            assertThat(t3.parent()).isEqualTo(t1.iri());
            assertThat(t3.state().get(EQ)).isEqualTo(t1.state().get(EQ));
            Networks.assertSameNetworkIgnoringStateVariables(sender, load(db, S, 1, T3), IDENTITY,
                    Set.of(Changes.LOAD_ID));
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aTimestampRootHangsOffThePinItNames(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            Network sender = load(db, S, 1, null);
            Changes.export(sender, db, ref(S, 1, T1), n -> Changes.moveLoad(n, 12.0));
            SnapshotInfo pin = Changes.export(sender, db, ref(S, 2, T1), n -> Changes.moveLoad(n, 14.0))
                    .snapshot();

            SnapshotInfo t2 = RdfDbExport.export(sender, Changes.record(sender, n -> Changes.moveLoad(n, 16.0)),
                    db, ref(S, 1, T2), ref(S, 2, T1), new CgmesDiffExport.ExportOptions(), ReportNode.NO_OP)
                    .snapshot();

            assertThat(t2.parent()).isEqualTo(pin.iri());
            assertThat(t2.edge()).isEqualTo(SnapshotInfo.EdgeKind.TIMESTAMP);
            assertThat(t2.depth()).isEqualTo(pin.depth() + 1);
            assertThat(t2.timestampRoot()).isEqualTo(t2.iri());
            assertThat(catalog.timestamps(BE)).filteredOn(row -> row.timestamp().equals(T2))
                    .singleElement().extracting(SnapshotCatalog.TimestampInfo::pin).isEqualTo(pin.iri());
            catalog.verify();

            // From 11:15 to 11:00 v1 is two steps up, out of 11:15 and out of 11:00 v2, and never through the base
            Network client = load(db, S, 1, T2);
            UpdatePlan plan = db.versionGraph(S).plan(client, ref(S, 1, T1), new RdfDbUpdateOptions());
            assertThat(plan.steps()).hasSize(2).allMatch(UpdatePlan.DiffStep::inverted)
                    .noneMatch(step -> step.snapshot().equals(catalog.root(BE).orElseThrow().iri()));
            UpdateResult result = RdfDbNetworkLoader.update(client, db, ref(S, 1, T1), new RdfDbUpdateOptions(),
                    params(), ReportNode.NO_OP);
            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(result.statistics().diffCount()).isEqualTo(2);
            Networks.assertSameNetworkIgnoringStateVariables(load(db, S, 1, T1), client, IDENTITY,
                    Set.of(Changes.LOAD_ID));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anExplicitPinThatDoesNotStateTheSupersededModelsIsRefused(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            Network sender = load(db, S, 1, null);
            Changes.export(sender, db, ref(S, 1, T1), n -> Changes.moveLoad(n, 12.0));
            SnapshotInfo root = catalog.root(BE).orElseThrow();

            // The sender sits at 11:00, so its difference supersedes 11:00's steady state, not the root's
            List<NetworkEvent> events = Changes.record(sender, n -> Changes.moveLoad(n, 16.0));
            assertThatThrownBy(() -> RdfDbExport.export(sender, events, db, ref(S, 1, T2), root.ref(),
                    new CgmesDiffExport.ExportOptions(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("the pin " + root.ref());
            // A pin of another tree is not a pin
            SnapshotInfo t1 = catalog.find(ref(S, 1, T1)).orElseThrow();
            assertThatThrownBy(() -> catalog.putAsDiff(TimestampFixtures.ssh(3, T2, "nl"), null, ref(S, 1, T2),
                    null, SnapshotRef.of(S, Backends.NL, null, "1"), params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("holds no snapshot");
            // A pin is chosen when a timestamp is created: an existing one grows on its head
            assertThatThrownBy(() -> catalog.putAsDiff(TimestampFixtures.ssh(3, T1, "again"), null,
                    ref(S, 2, T1), null, t1.ref(), params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("already exists");
            assertThat(catalog.timestamps(BE)).hasSize(2);
            catalog.verify();
        }
    }

    // ------------------------------------------------------------------ reading and walking

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void updateFromTheBaseToATimestampIsOneDifference(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, 1, null);
            Changes.export(sender, db, ref(S, 1, T1), n -> Changes.moveLoad(n, 12.0));
            Network client = load(db, S, 1, null);

            UpdateResult result = RdfDbNetworkLoader.update(client, db, ref(S, 1, T1), new RdfDbUpdateOptions(), params(),
                    ReportNode.NO_OP);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(result.statistics().diffCount()).isEqualTo(1);
            Networks.assertSameNetworkIgnoringStateVariables(sender, client, IDENTITY, Set.of(Changes.LOAD_ID));
            assertThat(db.snapshots(S).snapshotOf(client).orElseThrow().timestamp()).isEqualTo(T1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void updateFromOneTimestampToAnotherGoesThroughTheirCommonPin(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, 1, null);
            Changes.export(sender, db, ref(S, 1, T1), n -> Changes.moveLoad(n, 12.0));
            // Back to the base head to write the next timestamp, which is what the scheme asks of a writer
            RdfDbNetworkLoader.update(sender, db, ref(S, 1), new RdfDbUpdateOptions(), params(),
                    ReportNode.NO_OP);
            Changes.export(sender, db, ref(S, 1, T2), n -> Changes.moveLoad(n, 16.0));

            Network client = load(db, S, 1, T1);
            UpdateResult result = RdfDbNetworkLoader.update(client, db, ref(S, 1, T2), new RdfDbUpdateOptions(), params(),
                    ReportNode.NO_OP);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            // One step up to the base, their common pin, one step down into the other timestamp
            assertThat(result.statistics().diffCount()).isEqualTo(2);
            Networks.assertSameNetworkIgnoringStateVariables(load(db, S, 1, T2), client, IDENTITY,
                    Set.of(Changes.LOAD_ID));
            db.snapshots(S).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void loadingATimestampEqualsWalkingToIt(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, 1, null);
            Changes.export(sender, db, ref(S, 1, T1), n -> Changes.moveLoad(n, 12.0));
            Changes.export(sender, db, ref(S, 2, T1), n -> Changes.moveLoad(n, 14.0));

            Network materialised = load(db, S, 2, T1);

            Networks.assertSameNetworkIgnoringStateVariables(sender, materialised, IDENTITY,
                    Set.of(Changes.LOAD_ID));
            assertThat(materialised.getCaseDate().toInstant()).isEqualTo(T1);
            assertThat(db.snapshots(S).snapshotOf(materialised).orElseThrow().version()).isEqualTo("2");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void checkpointAtATimestampRoot(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, 1, null);
            Changes.export(sender, db, ref(S, 1, T1), n -> Changes.moveLoad(n, 12.0));
            Changes.export(sender, db, ref(S, 2, T1), n -> Changes.moveLoad(n, 14.0));
            Network before = load(db, S, 2, T1);

            Checkpoint.create(db, ref(S, 2, T1));

            assertThat(db.versionGraph(S).materialization(ref(S, 2, T1)).steps()).isEmpty();
            Networks.assertSameNetwork(before, load(db, S, 2, T1), IDENTITY);
            db.snapshots(S).verify();
        }
    }

    // ------------------------------------------------------------------ a moment belongs to its scenario

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void everyFormOfTheMomentAddressesTheSameSnapshot(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, 1, null);
            SnapshotInfo written = Changes.export(sender, db, ref(S, 1, T1), n -> Changes.moveLoad(n, 12.0))
                    .snapshot();
            SnapshotCatalog catalog = db.snapshots(S);

            // An offset date-time is the instant it means, and sub-second precision is not part of the key
            assertThat(catalog.find(SnapshotRef.of(S, BE, OffsetDateTime.parse("2014-06-01T12:00:00+01:00").toInstant(), "1")))
                    .contains(written);
            assertThat(catalog.find(ref(S, 1, T1.plusMillis(250)))).contains(written);
            assertThat(catalog.find(SnapshotRef.latestAt(S, BE, T1))).contains(written);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aTimestampIsLookedUpInsideItsOwnScenario(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            assertThatThrownBy(() -> db.snapshots(S).find(SnapshotRef.latestAt(OTHER, BE, T1)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("cannot address scenario");

            Network sender = load(db, S, 1, null);
            Changes.export(sender, db, ref(S, 1, T1), n -> Changes.moveLoad(n, 12.0));
            // 11:00 exists in S and not in the other scenario, which is the whole point of per-scenario days
            assertThat(db.snapshots(S).versions(BE, T1)).hasSize(1);
            assertThat(db.snapshots(OTHER).versions(BE, T1)).isEmpty();
            assertThat(db.snapshots(OTHER).timestamps(BE)).hasSize(1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anUnknownTimestampNamesTheSnapshotsThatExist(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            assertThatThrownBy(() -> load(db, S, 1, Instant.parse("2014-06-01T09:00:00Z")))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("holds no snapshot")
                    .hasMessageContaining(BASE.toString());
        }
    }

    // ------------------------------------------------------------------ the base chain keeps growing

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aBaseVersionAfterATimestampRootIsAccepted(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network morning = load(db, S, 1, null);
            Changes.export(morning, db, ref(S, 1, T1), n -> Changes.moveLoad(n, 12.0));

            // The day goes on: a study run adds a version to the base chain, whose steady state model the 11:00
            // timestamp already supersedes. Versions are the inner dimension; a timestamp must not freeze them
            Network study = load(db, S, 1, null);
            SnapshotInfo baseVersion = Changes.export(study, db, ref(S, 2),
                    n -> Changes.moveLoad(n, 20.0)).snapshot();

            assertThat(baseVersion.timestamp()).isEqualTo(BASE);
            assertThat(baseVersion.edge()).isEqualTo(SnapshotInfo.EdgeKind.VERSION);
            assertThat(baseVersion.depth()).isEqualTo(1);
            db.snapshots(S).verify();

            // ...and a client sitting at the timestamp can still walk to it: up out of 11:00, down the base chain
            Network client = load(db, S, 1, T1);
            UpdateResult result = RdfDbNetworkLoader.update(client, db, ref(S, 2),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(result.statistics().diffCount()).isEqualTo(2);
            Networks.assertSameNetworkIgnoringStateVariables(study, client, IDENTITY, Set.of(Changes.LOAD_ID));
            assertThat(db.snapshots(S).snapshotOf(client).orElseThrow().version()).isEqualTo("2");
        }
    }

    // ------------------------------------------------------------------ ingesting a timestamp from files

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void putAsDiffSshOnly(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);

            SnapshotInfo written = catalog.putAsDiff(TimestampFixtures.ssh(3, T1, "1100"), null,
                    ref(S, 1, T1), null, params(), ReportNode.NO_OP);

            assertThat(written.timestamp()).isEqualTo(T1);
            assertThat(written.edge()).isEqualTo(SnapshotInfo.EdgeKind.TIMESTAMP);
            assertThat(written.members()).containsExactly("urn:uuid:ssh-1100");
            assertThat(written.fast()).isTrue();
            // Only the steady state moved; every other profile is the one the root holds
            assertThat(written.state().get(Profiles.EQ))
                    .isEqualTo(db.snapshots(S).root(BE).orElseThrow().state().get(Profiles.EQ));

            SnapshotCatalog.IngestStatistics statistics = catalog.lastIngestStatistics();
            assertThat(statistics.forwardStatements().get(SSH)).isEqualTo(3);
            assertThat(statistics.reverseStatements().get(SSH)).isEqualTo(3);
            assertThat(statistics.fast().get(SSH)).isTrue();
            assertThat(statistics.ignored()).contains(Profiles.SV, Profiles.TP);
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void loadTimestampEqualsFileImport(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            ReadOnlyDataSource files = TimestampFixtures.ssh(3, T1, "1100");
            db.snapshots(S).putAsDiff(files, null, ref(S, 1, T1), null, params(), ReportNode.NO_OP);

            Network fromDb = load(db, S, 1, T1);
            Network fromFiles = Network.read(TimestampFixtures.ssh(3, T1, "1100"), params());

            Networks.assertSameNetwork(fromFiles, fromDb, IDENTITY);
            assertThat(fromDb.getCaseDate().toInstant()).isEqualTo(T1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void eqDriftFallsBackToFullReload(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotInfo written = db.snapshots(S).putAsDiff(TimestampFixtures.eqDrift(2, T1, "drift"), null,
                    ref(S, 1, T1), null, params(), ReportNode.NO_OP);

            // The equipment changed, and no in-place update reads a name
            assertThat(written.fast()).isFalse();
            assertThat(db.snapshots(S).lastIngestStatistics().fast().get(EQ)).isFalse();

            Network client = load(db, S, 1, null);
            UpdateResult result = RdfDbNetworkLoader.update(client, db, ref(S, 1, T1), new RdfDbUpdateOptions(), params(),
                    ReportNode.NO_OP);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_RELOAD);
            assertThat(result.reasons()).anyMatch(reason -> reason.contains("urn:uuid:eq-drift"));
            Networks.assertSameNetwork(Network.read(TimestampFixtures.eqDrift(2, T1, "drift"), params()),
                    result.network(), IDENTITY);
            db.snapshots(S).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void putAsDiffRejectsChangedBoundary(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            assertThatThrownBy(() -> db.snapshots(S).putAsDiff(TimestampFixtures.changedBoundary(T1, "bd"), null,
                    ref(S, 1, T1), null, params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("carry the boundary {EQ_BD=urn:uuid:")
                    .hasMessageContaining("a new boundary is a new scenario");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void putAsDiffOfAnUnchangedExportIsRefused(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            // Zero loads moved: the files describe the state the database already holds
            assertThatThrownBy(() -> db.snapshots(S).putAsDiff(TimestampFixtures.ssh(0, T1, "same"), null,
                    ref(S, 1, T1), null, params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("no difference to the parent");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aMemberMustDescribeTheMomentItsSnapshotDoes(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, 1, null);
            List<NetworkEvent> events = Changes.record(sender, n -> Changes.moveLoad(n, 12.0));
            CgmesDiffExport.ExportOptions options = new CgmesDiffExport.ExportOptions()
                    .setScenarioTime(java.time.ZonedDateTime.parse("2014-06-01T23:45:00Z"));
            CgmesDiffExport.Result exported = CgmesDiffExport.toDifferences(sender, events, options);

            assertThatThrownBy(() -> db.snapshots(S).putDiff(exported.differences(),
                    ref(S, 1, T1)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("describe the same moment");
        }
    }
    // ------------------------------------------------------------------ rollovers

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aRootIsARollover(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotInfo root = db.snapshots(S).root(BE).orElseThrow();

            // The first rollover of a tree is its root: the snapshot every timestamp is ingested against until
            // a later one is flagged
            assertThat(root.rollover()).isTrue();
            assertThat(root.hasFull()).isTrue();
            assertThat(Backends.count(db, S, db.snapshots(S).metaGraph(), "?s pdb:rollover true")).isEqualTo(1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void rolloverFlagsAndCheckpointsInOneCall(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo written = catalog.putAsDiff(TimestampFixtures.ssh(3, T1, "1100"), null,
                    ref(S, 1, T1), null, params(), ReportNode.NO_OP);
            assertThat(written.rollover()).isFalse();
            assertThat(written.hasFull()).isFalse();
            Network before = load(db, S, 1, T1);

            SnapshotInfo rollover = catalog.rollover(ref(S, 1, T1));

            // Flagged and checkpointed at once: a materialisation of it starts at it and walks no difference
            assertThat(rollover.iri()).isEqualTo(written.iri());
            assertThat(rollover.rollover()).isTrue();
            assertThat(rollover.hasFull()).isTrue();
            assertThat(db.versionGraph(S).materialization(ref(S, 1, T1)).steps()).isEmpty();
            assertThat(catalog.find(ref(S, 1, T1)).orElseThrow().rollover()).isTrue();
            Networks.assertSameNetwork(before, load(db, S, 1, T1), IDENTITY);

            // Idempotent: a second call changes nothing and writes no second flag
            assertThat(catalog.rollover(ref(S, 1, T1))).isEqualTo(rollover);
            assertThat(Backends.count(db, S, catalog.metaGraph(), "?s pdb:rollover true")).isEqualTo(2);
            catalog.verify();
        }
    }

    // ------------------------------------------------------------------ dropping a timestamp

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aTimestampNothingDependsOnIsDropped(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            catalog.putAsDiff(TimestampFixtures.ssh(3, T1, "kept"), null, ref(S, 1, T1), null, params(),
                    ReportNode.NO_OP);
            // 11:15: a difference with a whole custom graph, a second version, and a checkpoint of it
            SnapshotInfo first = catalog.putAsDiff(TimestampFixtures.with(TimestampFixtures.ssh(2, T2, "gone"),
                            TimestampFixtures.CFG, TimestampFixtures.cfg("urn:uuid:cfg-gone", T2, "45")), null,
                    ref(S, 1, T2), Set.of(EQ, SSH, "CFG"), params(), ReportNode.NO_OP);
            Network sender = load(db, S, 1, T2);
            SnapshotInfo second = Changes.export(sender, db, ref(S, 2, T2), n -> Changes.moveLoad(n, 3.0))
                    .snapshot();
            Checkpoint.create(db, second.ref());
            List<String> graphs = new java.util.ArrayList<>();
            db.catalog(S).models(List.of(first.state().get(SSH), second.state().get(SSH))).values()
                    .forEach(model -> graphs.addAll(List.of(model.forwardGraph(), model.reverseGraph())));
            graphs.add(catalog.graphsOf(second.ref()).get("CFG"));
            graphs.add(RdfDbNames.materialized(S, BE, T2, second.version(), SSH) + "/graph");
            assertThat(graphs).hasSizeGreaterThan(5).allMatch(graph -> hasGraph(db, graph));

            List<SnapshotInfo> dropped = catalog.dropTimestamp(BE, T2);

            assertThat(dropped).extracting(SnapshotInfo::iri).containsExactlyInAnyOrder(first.iri(), second.iri());
            assertThat(catalog.timestamps(BE)).extracting(SnapshotCatalog.TimestampInfo::timestamp)
                    .containsExactly(BASE, T1);
            assertThat(catalog.versions(BE, T2)).isEmpty();
            assertThat(graphs).noneMatch(graph -> hasGraph(db, graph));
            // Nothing of it is left in the metadata graph either: no snapshot, model or checkpoint node names it
            for (String node : List.of(first.iri(), second.iri(), "urn:uuid:cfg-gone", first.state().get(SSH))) {
                assertThat(Backends.count(db, S, catalog.metaGraph(), "<" + node + "> ?p ?o")).isZero();
            }
            assertThat(Backends.count(db, S, catalog.metaGraph(), "?x pdb:snapshot <" + second.iri() + ">"))
                    .isZero();
            catalog.verify();
            Networks.assertSameNetwork(Network.read(TimestampFixtures.ssh(3, T1, "kept"), params()),
                    load(db, S, 1, T1), IDENTITY);

            // ...and the moment can be written again
            catalog.putAsDiff(TimestampFixtures.ssh(1, T2, "again"), null, ref(S, 1, T2), null, params(),
                    ReportNode.NO_OP);
            catalog.verify();
        }
    }

    private static boolean hasGraph(RdfDbConnection db, String graph) {
        return db.sparql(S).ask("ASK { GRAPH <" + graph + "> { ?s ?p ?o } }");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aTimestampAnotherOneIsPinnedToIsRefusedNamingIt(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo t1 = catalog.putAsDiff(TimestampFixtures.ssh(1, T1, "a"), null, ref(S, 1, T1), null,
                    params(), ReportNode.NO_OP);
            catalog.rollover(t1.ref());
            SnapshotInfo t2 = catalog.putAsDiff(TimestampFixtures.ssh(2, T2, "b"), null, ref(S, 1, T2), null,
                    params(), ReportNode.NO_OP);
            SnapshotInfo t3 = catalog.putAsDiff(TimestampFixtures.ssh(3, T3, "c"), null, ref(S, 1, T3), null,
                    params(), ReportNode.NO_OP);

            // No cascade: both timestamps pinned to 11:00 are named, and nothing is dropped
            assertThatThrownBy(() -> catalog.dropTimestamp(BE, T1))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining(t2.ref().toString())
                    .hasMessageContaining(t3.ref().toString())
                    .hasMessageContaining("nothing was dropped");
            assertThat(catalog.timestamps(BE)).hasSize(4);
            catalog.verify();

            // Dropped from the leaves up, it goes
            catalog.dropTimestamp(BE, T3);
            catalog.dropTimestamp(BE, T2);
            catalog.dropTimestamp(BE, T1);
            assertThat(catalog.timestamps(BE)).hasSize(1);
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void theBaseTimestampCannotBeDropped(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            assertThatThrownBy(() -> catalog.dropTimestamp(BE, BASE))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("base timestamp");
            assertThatThrownBy(() -> catalog.dropTimestamp(BE, T1))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("holds no timestamp " + T1);
            assertThat(catalog.snapshots()).hasSize(1);
        }
    }

    // ------------------------------------------------------------------ the changes between two snapshots

    /**
     * Three timestamps of a day across a rollover: 11:00 and 11:15 pinned to the root, 11:30 to 11:15. The changes
     * from 11:00 to 11:30 are one difference per profile, composed from the path up out of 11:00 and down through
     * 11:15 into 11:30, and they are what comparing the two states statement by statement gives.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void changesBetweenTwoTimestampsEqualsReverseThenForward(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            ReadOnlyDataSource at1100 = TimestampFixtures.ssh(2, 7.0, T1, "a");
            ReadOnlyDataSource at1130 = TimestampFixtures.ssh(1, 11.0, T3, "c");
            SnapshotInfo t1 = catalog.putAsDiff(at1100, null, ref(S, 1, T1), null, params(), ReportNode.NO_OP);
            SnapshotInfo t2 = catalog.putAsDiff(TimestampFixtures.ssh(3, 9.0, T2, "b"), null, ref(S, 1, T2), null,
                    params(), ReportNode.NO_OP);
            catalog.rollover(t2.ref());
            SnapshotInfo t3 = catalog.putAsDiff(at1130, null, ref(S, 1, T3), null, params(), ReportNode.NO_OP);

            DifferenceModelSet changes = RdfDbNetworkLoader.changesBetween(db, ref(S, 1, T1), ref(S, 1, T3));

            assertThat(changes.models()).containsOnlyKeys(CgmesSubset.STEADY_STATE_HYPOTHESIS);
            DifferenceModel composed = changes.models().get(CgmesSubset.STEADY_STATE_HYPOTHESIS);
            DifferenceModel direct = directDifference(at1100, at1130);
            assertThat(Set.copyOf(composed.forward())).isEqualTo(Set.copyOf(direct.forward()));
            assertThat(Set.copyOf(composed.reverse())).isEqualTo(Set.copyOf(direct.reverse()));
            // It is 11:30's steady state, recorded against 11:00's
            assertThat(composed.header().id()).isEqualTo(t3.state().get(SSH));
            assertThat(composed.header().supersedes()).containsExactly(t1.state().get(SSH));

            // Applied to the network at 11:00 it gives the network at 11:30, and the reverse direction undoes it
            Network network = load(db, S, 1, T1);
            CgmesDiffImport.apply(network, changes, params(), ReportNode.NO_OP);
            Networks.assertSameNetworkIgnoringStateVariables(load(db, S, 1, T3), network, IDENTITY, loads(network));
            CgmesDiffImport.apply(network, RdfDbNetworkLoader.changesBetween(db, ref(S, 1, T3), ref(S, 1, T1)),
                    params(), ReportNode.NO_OP);
            Networks.assertSameNetworkIgnoringStateVariables(load(db, S, 1, T1), network, IDENTITY, loads(network));

            // A path that only walks up: from 11:30 to its rollover
            Network up = load(db, S, 1, T3);
            CgmesDiffImport.apply(up, RdfDbNetworkLoader.changesBetween(db, ref(S, 1, T3), ref(S, 1, T2)), params(),
                    ReportNode.NO_OP);
            Networks.assertSameNetworkIgnoringStateVariables(load(db, S, 1, T2), up, IDENTITY, loads(up));

            assertThat(RdfDbNetworkLoader.changesBetween(db, ref(S, 1, T3), ref(S, 1, T3)).models()).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void changesBetweenIsRefusedAcrossAuthoritiesAndScenarios(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            db.snapshots(S).putFull(Backends.microGridNl(), null, SnapshotRef.of(S, Backends.NL, null, "1"), null,
                    params(), ReportNode.NO_OP);

            assertThatThrownBy(() -> RdfDbNetworkLoader.changesBetween(db, ref(S, 1),
                    SnapshotRef.of(S, Backends.NL, null, "1")))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("diffs never cross modelling authorities");
            assertThatThrownBy(() -> RdfDbNetworkLoader.changesBetween(db, ref(S, 1), ref(OTHER, 1)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("diffs never cross scenarios");
            assertThatThrownBy(() -> RdfDbNetworkLoader.changesBetween(db, ref(S, 1), ref(S, 1, T1)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("holds no snapshot");
        }
    }

    /** The difference of the steady state hypothesis between two sets of files, statement by statement. */
    private static DifferenceModel directDifference(ReadOnlyDataSource from, ReadOnlyDataSource to) {
        IngestParser.Result parsedFrom = IngestParser.read(from, null, ReportNode.NO_OP, Map.of(), Set.of(SSH));
        IngestParser.Result parsedTo = IngestParser.read(to, null, ReportNode.NO_OP, Map.of(), Set.of(SSH));
        return TripleDiffCalculator.diff(sshIndex(parsedFrom), sshIndex(parsedTo),
                DifferenceModelHeader.builder("urn:uuid:direct", CgmesSubset.STEADY_STATE_HYPOTHESIS,
                        parsedTo.cimNamespace()).build());
    }

    private static StatementDiff.Index sshIndex(IngestParser.Result parsed) {
        return parsed.files().stream().filter(file -> SSH.equals(file.profile())).findFirst().orElseThrow()
                .index();
    }

    private static Set<String> loads(Network network) {
        return network.getLoadStream().map(load -> load.getId()).collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------ the parent index cache

    /**
     * Every timestamp of a day hangs off the same base state, so that state is materialised and decoded once.
     *
     * <p>What the cache has to earn is invisible from the outside &mdash; the differences, the snapshots and the
     * networks are what they always were &mdash; so it is asserted on the hit counter and on the fact that the
     * timestamps after the first report no materialisation time worth speaking of.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aDayOfTimestampsDecodesItsParentStateOnce(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            List<Instant> instants = List.of(T1, T2, T3);
            for (int i = 0; i < instants.size(); i++) {
                catalog.putAsDiff(TimestampFixtures.ssh(3, instants.get(i), "cache" + i), null,
                        ref(S, 1, instants.get(i)), null, params(), ReportNode.NO_OP);
                SnapshotCatalog.IngestStatistics statistics = catalog.lastIngestStatistics();
                assertThat(statistics.forwardStatements().get(SSH)).isEqualTo(3);
                assertThat(statistics.reverseStatements().get(SSH)).isEqualTo(3);
            }

            // The first timestamp materialised, the two after it did not
            assertThat(catalog.parentIndexCacheHits()).isEqualTo(2);
            // ...and the last one still produced the state the files describe
            Networks.assertSameNetwork(Network.read(TimestampFixtures.ssh(3, T3, "cache2"), params()),
                    load(db, S, 1, T3), IDENTITY);
            catalog.verify();
        }
    }

    /**
     * A rollover written between two timestamps is the second one's parent, cache or no cache; a version of the
     * base that is not one is not.
     *
     * <p>The entry the first timestamp left behind is keyed by the identifier of the state it compared against, and
     * a new version of the base is a new steady state with a new identifier. So a timestamp pinned to it cannot hit
     * the entry, and the difference it writes supersedes the new state &mdash; which {@code putDiff} would refuse
     * outright if it did not, and which the network loaded afterwards proves it did.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aRolloverBetweenTwoTimestampsIsTheSecondOnesParent(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo root = catalog.root(BE).orElseThrow();
            catalog.putAsDiff(TimestampFixtures.ssh(3, T1, "before"), null, ref(S, 1, T1), null,
                    params(), ReportNode.NO_OP);

            Network sender = load(db, S, 1, null);
            SnapshotInfo baseVersion = Changes.export(sender, db, ref(S, 2),
                    n -> Changes.moveLoad(n, 12.0)).snapshot();
            // Not a rollover: the next timestamp is still ingested against the root, out of the cache
            long hits = catalog.parentIndexCacheHits();
            SnapshotInfo second = catalog.putAsDiff(TimestampFixtures.ssh(3, T2, "between"), null, ref(S, 1, T2),
                    null, params(), ReportNode.NO_OP);
            assertThat(second.parent()).isEqualTo(root.iri());
            assertThat(catalog.parentIndexCacheHits()).isEqualTo(hits + 1);

            catalog.rollover(baseVersion.ref());
            hits = catalog.parentIndexCacheHits();
            SnapshotInfo written = catalog.putAsDiff(TimestampFixtures.ssh(3, T3, "after"), null,
                    ref(S, 1, T3), null, params(), ReportNode.NO_OP);

            assertThat(catalog.parentIndexCacheHits()).isEqualTo(hits);
            assertThat(written.parent()).isEqualTo(baseVersion.iri());
            Networks.assertSameNetwork(Network.read(TimestampFixtures.ssh(3, T3, "after"), params()),
                    load(db, S, 1, T3), IDENTITY);
            catalog.verify();
        }
    }

    // ------------------------------------------------------------------ pins of ingested timestamps

    /**
     * After a rollover, a timestamp is ingested against it: the difference it stores is the change since the
     * rollover, and the decoded state the rest of the day compares against is the rollover's.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anIngestionDiffsAgainstTheLatestRolloverBeforeIt(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            catalog.putAsDiff(TimestampFixtures.ssh(1, T1, "a"), null, ref(S, 1, T1), null, params(),
                    ReportNode.NO_OP);
            SnapshotInfo t2 = catalog.putAsDiff(TimestampFixtures.ssh(2, T2, "b"), null, ref(S, 1, T2), null,
                    params(), ReportNode.NO_OP);
            catalog.rollover(t2.ref());

            // 11:30 moves one consumer more than 11:15: one statement against the rollover, three against the root
            SnapshotInfo t3 = catalog.putAsDiff(TimestampFixtures.ssh(3, T3, "c"), null, ref(S, 1, T3), null,
                    params(), ReportNode.NO_OP);
            assertThat(t3.parent()).isEqualTo(t2.iri());
            assertThat(t3.depth()).isEqualTo(2);
            assertThat(catalog.lastIngestStatistics().forwardStatements().get(SSH)).isEqualTo(1);
            assertThat(catalog.timestamps(BE)).filteredOn(row -> row.timestamp().equals(T3))
                    .singleElement().extracting(SnapshotCatalog.TimestampInfo::pin).isEqualTo(t2.iri());

            // The rollover's decoded state is kept: the next timestamp after it does not materialise again
            long hits = catalog.parentIndexCacheHits();
            SnapshotInfo t4 = catalog.putAsDiff(TimestampFixtures.ssh(3, T4, "d"), null, ref(S, 1, T4), null,
                    params(), ReportNode.NO_OP);
            assertThat(t4.parent()).isEqualTo(t2.iri());
            assertThat(catalog.parentIndexCacheHits()).isEqualTo(hits + 1);
            assertThat(catalog.lastIngestStatistics().forwardStatements().get(SSH)).isEqualTo(1);

            Networks.assertSameNetwork(Network.read(TimestampFixtures.ssh(3, T4, "d"), params()),
                    load(db, S, 1, T4), IDENTITY);
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anIngestionHangsOffThePinItNames(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo t1 = catalog.putAsDiff(TimestampFixtures.ssh(1, T1, "a"), null, ref(S, 1, T1), null,
                    params(), ReportNode.NO_OP);

            // Not a rollover, named explicitly: the difference is the one against 11:00, two consumers, not three
            SnapshotInfo t2 = catalog.putAsDiff(TimestampFixtures.ssh(3, T2, "b"), null, ref(S, 1, T2), null,
                    t1.ref(), params(), ReportNode.NO_OP);

            assertThat(t2.parent()).isEqualTo(t1.iri());
            assertThat(catalog.lastIngestStatistics().forwardStatements().get(SSH)).isEqualTo(2);
            Networks.assertSameNetwork(Network.read(TimestampFixtures.ssh(3, T2, "b"), params()),
                    load(db, S, 1, T2), IDENTITY);
            catalog.verify();
        }
    }

    /**
     * A day whose equipment drifted at 11:00: rolled over there, the timestamps after it are a fast step from it,
     * and the ones before it do not notice.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aSlowRolloverCheckpointedStartsTheTimestampsAfterIt(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo root = catalog.root(BE).orElseThrow();
            SnapshotInfo drift = catalog.putAsDiff(TimestampFixtures.eqDrift(2, T1, "drift"), null, ref(S, 1, T1),
                    null, params(), ReportNode.NO_OP);
            assertThat(drift.fast()).isFalse();
            catalog.rollover(drift.ref());

            // 11:15 carries the drifted equipment too: against the rollover only its steady state moved
            SnapshotInfo next = catalog.putAsDiff(TimestampFixtures.eqDrift(3, T2, "next"), null, ref(S, 1, T2),
                    null, params(), ReportNode.NO_OP);
            assertThat(next.parent()).isEqualTo(drift.iri());
            assertThat(next.fast()).isTrue();
            assertThat(catalog.lastIngestStatistics().forwardStatements()).containsOnlyKeys(SSH);

            // A load of 11:15 starts at the rollover's full graphs and applies one difference, a fast one
            MaterializationPlan materialization = db.versionGraph(S).materialization(next.iri());
            assertThat(materialization.steps()).hasSize(1).allMatch(step -> step.model().fastPredicatesOnly());
            assertThat(materialization.startModel().get(EQ).snapshot()).isEqualTo(drift.iri());
            assertThat(materialization.startModel().get(SSH).snapshot()).isEqualTo(drift.iri());
            Networks.assertSameNetwork(Network.read(TimestampFixtures.eqDrift(3, T2, "next"), params()),
                    load(db, S, 1, T2), IDENTITY);
            // ...and the walk from the rollover to it crosses no slow difference
            Network client = load(db, S, 1, T1);
            UpdateResult result = RdfDbNetworkLoader.update(client, db, ref(S, 1, T2), new RdfDbUpdateOptions(),
                    params(), ReportNode.NO_OP);
            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(result.statistics().diffCount()).isEqualTo(1);

            // A timestamp before the rollover is still pinned to the root
            SnapshotInfo earlier = catalog.putAsDiff(TimestampFixtures.ssh(1, BEFORE_T1, "early"), null,
                    ref(S, 1, BEFORE_T1), null, params(), ReportNode.NO_OP);
            assertThat(earlier.parent()).isEqualTo(root.iri());
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aDayOfVariantsSpanningARolloverLoads(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            List<Instant> day = List.of(BEFORE_T1, T1, T2, T3, T4, Instant.parse("2014-06-01T12:00:00Z"));
            for (int i = 0; i < day.size(); i++) {
                SnapshotInfo written = catalog.putAsDiff(TimestampFixtures.ssh(1 + i % 3, 7.0 + i, day.get(i),
                        "v" + i), null,
                        ref(S, 1, day.get(i)), null, params(), ReportNode.NO_OP);
                if (day.get(i).equals(T2)) {
                    catalog.rollover(written.ref());
                }
            }
            catalog.verify();

            VariantLoadResult result = RdfDbNetworkLoader.loadVariants(db, S,
                    day.stream().map(t -> VariantRequest.of(ref(S, 1, t))).toList(), new RdfDbVariantLoadOptions(),
                    null, params(), ReportNode.NO_OP);

            assertThat(result.refused()).isEmpty();
            Network network = result.network();
            for (int i = 0; i < day.size(); i++) {
                Network separate = load(db, S, 1, day.get(i));
                network.getVariantManager().setWorkingVariant(result.outcomes().get(i).variantId());
                try {
                    Networks.assertSameNetworkIgnoringStateVariables(separate, network, IDENTITY,
                            separate.getLoadStream().map(load -> load.getId()).collect(Collectors.toSet()));
                } finally {
                    network.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
                }
            }
        }
    }
}
