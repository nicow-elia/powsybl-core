/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

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
 * of the day is a snapshot pinned to a version of the <em>base</em> chain by a {@code pdb:TimestampEdge}, with a
 * version chain of its own below it. That shape is what makes every timestamp "the base plus a handful of
 * differences" however many study versions the other timestamps accumulate, and what makes a walk from one
 * timestamp to another a walk through the base.</p>
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
            assertThat(timestamps.get(1).pinnedBase()).isEqualTo(base.iri());
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
    void aTimestampRootMustDeriveFromTheBaseChain(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, 1, null);
            Changes.export(sender, db, ref(S, 1, T1), n -> Changes.moveLoad(n, 12.0));
            // The sender now sits at 11:00; writing 11:15 from there would pin one timestamp to another
            assertThatThrownBy(() -> Changes.export(sender, db, ref(S, 1, T2),
                    n -> Changes.moveLoad(n, 16.0)))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("timestamp roots derive from the base timestamp");
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
    void updateFromOneTimestampToAnotherGoesThroughTheBase(String backend) {
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
            // One step up through the base, one step down into the other timestamp
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
     * A version written on the base chain between two timestamps is the second one's parent, cache or no cache.
     *
     * <p>The entry the first timestamp left behind is keyed by the identifier of the state it compared against, and
     * a new version of the base is a new steady state with a new identifier. So the second timestamp cannot hit it,
     * and the difference it writes supersedes the new state &mdash; which {@code putDiff} would refuse outright if
     * it did not, and which the network loaded afterwards proves it did.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aNewBaseVersionBetweenTwoTimestampsIsTheSecondOnesParent(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            catalog.putAsDiff(TimestampFixtures.ssh(3, T1, "before"), null, ref(S, 1, T1), null,
                    params(), ReportNode.NO_OP);
            long hits = catalog.parentIndexCacheHits();

            Network sender = load(db, S, 1, null);
            SnapshotInfo baseVersion = Changes.export(sender, db, ref(S, 2),
                    n -> Changes.moveLoad(n, 12.0)).snapshot();

            SnapshotInfo written = catalog.putAsDiff(TimestampFixtures.ssh(3, T2, "after"), null,
                    ref(S, 1, T2), null, params(), ReportNode.NO_OP);

            assertThat(catalog.parentIndexCacheHits()).isEqualTo(hits);
            assertThat(written.parent()).isEqualTo(baseVersion.iri());
            Networks.assertSameNetwork(Network.read(TimestampFixtures.ssh(3, T2, "after"), params()),
                    load(db, S, 1, T2), IDENTITY);
            catalog.verify();
        }
    }
}
