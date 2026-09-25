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
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The second addressing dimension: timesteps hanging off the base chain, and versions inside them.
 *
 * <p>A scenario is one day. Its root is the base timestep; every other timestep of the day is a snapshot pinned to
 * a version of the <em>base</em> chain by a {@code pdb:TimestepEdge}, with a version chain of its own below it.
 * That shape is what makes every timestep "the base plus a handful of differences" however many study versions the
 * other timesteps accumulate, and what makes a walk from one timestep to another a walk through the base.</p>
 *
 * <p>The day under test is the MicroGrid base case, whose scenario time is 10:30; the timesteps below it are
 * therefore 11:00 and 11:15 of the same day, addressed both as instants and as {@code "11:00"} labels.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbTimestepFlowTest {

    private static final String S = "2016-01-01";
    private static final String OTHER = "other";
    private static final String BASE = "2014-06-01T10:30:00Z";
    private static final String T1 = "2014-06-01T11:00:00Z";
    private static final String T2 = "2014-06-01T11:15:00Z";
    private static final String T3 = "2014-06-01T11:30:00Z";
    private static final CgmesSubset SSH = CgmesSubset.STEADY_STATE_HYPOTHESIS;
    private static final CgmesSubset EQ = CgmesSubset.EQUIPMENT;
    private static final Set<String> IDENTITY = Set.of("cgmesMetadataModels", "rdfDbProvenance");

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

    private static RdfDbConnection twoDays(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "timestep-flow"));
        db.clear(S);
        db.clear(OTHER);
        db.snapshots(S).putFull(be(), null, SnapshotRef.of(S, "1.0"), params(), ReportNode.NO_OP);
        db.snapshots(OTHER).putFull(be(), null, SnapshotRef.of(OTHER, "1.0"), params(), ReportNode.NO_OP);
        return db;
    }

    private static Network load(RdfDbConnection db, String scenario, String version, String timestep) {
        return RdfDbNetworkLoader.load(db, scenario, version, timestep, null, params(), ReportNode.NO_OP);
    }

    private static RdfDbExport.SnapshotResult record(Network network, RdfDbConnection db, SnapshotRef target,
                                                     Consumer<Network> change) {
        List<NetworkEvent> events = Changes.record(network, change);
        return RdfDbExport.export(network, events, db, target, new CgmesDiffExport.ExportOptions());
    }

    // ------------------------------------------------------------------ writing timesteps

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aNewTimestepHangsOffTheBaseHead(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, "1.0", null);
            SnapshotInfo base = db.snapshots(S).find(SnapshotRef.of(S, "1.0")).orElseThrow();

            SnapshotInfo root = record(sender, db, SnapshotRef.of(S, "1.0", T1),
                    n -> Changes.moveLoad(n, 12.0)).snapshot();

            assertThat(root.timestep()).isEqualTo(T1);
            assertThat(root.timestepLabel()).isEqualTo("11:00");
            assertThat(root.edge()).isEqualTo(SnapshotInfo.EdgeKind.TIMESTEP);
            assertThat(root.parent()).isEqualTo(base.iri());
            assertThat(root.depth()).isEqualTo(1);
            assertThat(root.timestepRoot()).isEqualTo(root.iri());
            assertThat(root.state().get(SSH)).isIn(root.members());
            db.snapshots(S).verify();

            List<SnapshotCatalog.TimestepInfo> timesteps = db.snapshots(S).timesteps();
            assertThat(timesteps).hasSize(2);
            assertThat(timesteps.get(0).timestep()).isEqualTo(BASE);
            assertThat(timesteps.get(1).timestep()).isEqualTo(T1);
            assertThat(timesteps.get(1).label()).isEqualTo("11:00");
            assertThat(timesteps.get(1).pinnedBase()).isEqualTo(base.iri());
            assertThat(timesteps.get(1).versionCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void versionsChainInsideTheTimestep(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, "1.0", null);
            SnapshotInfo root = record(sender, db, SnapshotRef.of(S, "1.0", T1),
                    n -> Changes.moveLoad(n, 12.0)).snapshot();
            SnapshotInfo second = record(sender, db, SnapshotRef.of(S, "1.1", T1),
                    n -> Changes.moveLoad(n, 14.0)).snapshot();

            assertThat(second.edge()).isEqualTo(SnapshotInfo.EdgeKind.VERSION);
            assertThat(second.parent()).isEqualTo(root.iri());
            assertThat(second.timestepRoot()).isEqualTo(root.iri());
            assertThat(second.timestep()).isEqualTo(T1);
            assertThat(db.snapshots(S).versions(T1)).hasSize(2);
            assertThat(db.snapshots(S).versions(null)).hasSize(1);
            assertThat(db.snapshots(S).nextVersionLabel(T1)).isEqualTo("1.2");
            db.snapshots(S).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aSecondRootForOneTimestepIsRejected(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network first = load(db, S, "1.0", null);
            Network second = load(db, S, "1.0", null);
            record(first, db, SnapshotRef.of(S, "1.0", T1), n -> Changes.moveLoad(n, 12.0));

            // The second writer still thinks 11:00 does not exist, and its diff supersedes the base state
            assertThatThrownBy(() -> record(second, db, SnapshotRef.of(S, "2.0", T1),
                    n -> Changes.moveLoad(n, 15.0)))
                    .isInstanceOf(RdfDbConflictException.class);
            db.snapshots(S).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aTimestepRootMustDeriveFromTheBaseChain(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, "1.0", null);
            record(sender, db, SnapshotRef.of(S, "1.0", T1), n -> Changes.moveLoad(n, 12.0));
            // The sender now sits at 11:00; writing 11:15 from there would pin one timestep to another
            assertThatThrownBy(() -> record(sender, db, SnapshotRef.of(S, "1.0", T2),
                    n -> Changes.moveLoad(n, 16.0)))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("timestep roots derive from the base timestep");
        }
    }

    // ------------------------------------------------------------------ reading and walking

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void updateFromTheBaseToATimestepIsOneDifference(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, "1.0", null);
            record(sender, db, SnapshotRef.of(S, "1.0", T1), n -> Changes.moveLoad(n, 12.0));
            Network client = load(db, S, "1.0", null);

            UpdateResult result = RdfDbNetworkLoader.update(client, db, S, "1.0", "11:00", params(),
                    ReportNode.NO_OP);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(result.statistics().diffCount()).isEqualTo(1);
            Networks.assertSameNetworkIgnoringStateVariables(sender, client, IDENTITY, Set.of(Changes.LOAD_ID));
            assertThat(db.snapshots(S).snapshotOf(client).orElseThrow().timestep()).isEqualTo(T1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void updateFromOneTimestepToAnotherGoesThroughTheBase(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, "1.0", null);
            record(sender, db, SnapshotRef.of(S, "1.0", T1), n -> Changes.moveLoad(n, 12.0));
            // Back to the base head to write the next timestep, which is what the scheme asks of a writer
            RdfDbNetworkLoader.update(sender, db, SnapshotRef.of(S, "1.0"), new RdfDbUpdateOptions(), params(),
                    ReportNode.NO_OP);
            record(sender, db, SnapshotRef.of(S, "1.0", T2), n -> Changes.moveLoad(n, 16.0));

            Network client = load(db, S, "1.0", "11:00");
            UpdateResult result = RdfDbNetworkLoader.update(client, db, S, "1.0", "11:15", params(),
                    ReportNode.NO_OP);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            // One step up through the base, one step down into the other timestep
            assertThat(result.statistics().diffCount()).isEqualTo(2);
            Networks.assertSameNetworkIgnoringStateVariables(load(db, S, "1.0", "11:15"), client, IDENTITY,
                    Set.of(Changes.LOAD_ID));
            db.snapshots(S).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void loadingATimestepEqualsWalkingToIt(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, "1.0", null);
            record(sender, db, SnapshotRef.of(S, "1.0", T1), n -> Changes.moveLoad(n, 12.0));
            record(sender, db, SnapshotRef.of(S, "1.1", T1), n -> Changes.moveLoad(n, 14.0));

            Network materialised = load(db, S, "1.1", "11:00");

            Networks.assertSameNetworkIgnoringStateVariables(sender, materialised, IDENTITY,
                    Set.of(Changes.LOAD_ID));
            assertThat(materialised.getCaseDate().toInstant().toString()).isEqualTo(T1);
            assertThat(db.snapshots(S).snapshotOf(materialised).orElseThrow().version()).isEqualTo("1.1");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void checkpointAtATimestepRoot(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, "1.0", null);
            record(sender, db, SnapshotRef.of(S, "1.0", T1), n -> Changes.moveLoad(n, 12.0));
            record(sender, db, SnapshotRef.of(S, "1.1", T1), n -> Changes.moveLoad(n, 14.0));
            Network before = load(db, S, "1.1", "11:00");

            Checkpoint.create(db, SnapshotRef.of(S, "1.1", T1));

            assertThat(db.versionGraph(S).materialization(SnapshotRef.of(S, "1.1", T1)).steps()).isEmpty();
            Networks.assertSameNetwork(before, load(db, S, "1.1", "11:00"), IDENTITY);
            db.snapshots(S).verify();
        }
    }

    // ------------------------------------------------------------------ labels belong to their scenario

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void everyTimestepFormAddressesTheSameSnapshot(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, "1.0", null);
            record(sender, db, SnapshotRef.of(S, "1.0", T1), n -> Changes.moveLoad(n, 12.0));
            SnapshotCatalog catalog = db.snapshots(S);

            assertThat(catalog.resolve("1.0", "11:00").timestep()).isEqualTo(T1);
            assertThat(catalog.resolve("1.0", "11:00:00").timestep()).isEqualTo(T1);
            assertThat(catalog.resolve("1.0", T1).timestep()).isEqualTo(T1);
            assertThat(catalog.resolve("1.0", "2014-06-01T12:00:00+01:00").timestep()).isEqualTo(T1);
            assertThat(catalog.resolve("1.0", null).timestep()).isNull();
            assertThat(catalog.baseOffset()).isEqualTo("Z");
            assertThat(catalog.find(catalog.resolve("1.0", "11:00"))).isPresent();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aLabelIsResolvedInsideItsOwnScenario(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            // Both scenarios are the same day here, so the same label must still be resolved independently and
            // must never reach across: the two catalogues answer from their own catalogue node
            assertThat(db.snapshots(S).resolve("1.0", "11:00").scenario()).isEqualTo(S);
            assertThat(db.snapshots(OTHER).resolve("1.0", "11:00").scenario()).isEqualTo(OTHER);
            assertThatThrownBy(() -> db.snapshots(S).find(db.snapshots(OTHER).resolve("1.0", "11:00")))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("cannot address scenario");

            Network sender = load(db, S, "1.0", null);
            record(sender, db, SnapshotRef.of(S, "1.0", T1), n -> Changes.moveLoad(n, 12.0));
            // "11:00" exists in S and not in the other scenario, which is the whole point of per-scenario days
            assertThat(db.snapshots(S).versions("11:00")).hasSize(1);
            assertThat(db.snapshots(OTHER).versions("11:00")).isEmpty();
            assertThat(db.snapshots(OTHER).timesteps()).hasSize(1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void anUnknownTimestepNamesTheOnesThatExist(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            assertThatThrownBy(() -> load(db, S, "1.0", "9:00"))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("holds no snapshot");
            assertThatThrownBy(() -> load(db, S, "1.0", "25:00"))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("not a wall time");
        }
    }

    // ------------------------------------------------------------------ the base chain keeps growing

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aBaseVersionAfterATimestepRootIsAccepted(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network morning = load(db, S, "1.0", null);
            record(morning, db, SnapshotRef.of(S, "1.0", T1), n -> Changes.moveLoad(n, 12.0));

            // The day goes on: a study run adds a version to the base chain, whose steady state model the 11:00
            // timestep already supersedes. Versions are the inner dimension; a timestep must not freeze them
            Network study = load(db, S, "1.0", null);
            SnapshotInfo baseVersion = record(study, db, SnapshotRef.of(S, "1.1"),
                    n -> Changes.moveLoad(n, 20.0)).snapshot();

            assertThat(baseVersion.timestep()).isEqualTo(BASE);
            assertThat(baseVersion.edge()).isEqualTo(SnapshotInfo.EdgeKind.VERSION);
            assertThat(baseVersion.depth()).isEqualTo(1);
            db.snapshots(S).verify();

            // ...and a client sitting at the timestep can still walk to it: up out of 11:00, down the base chain
            Network client = load(db, S, "1.0", "11:00");
            UpdateResult result = RdfDbNetworkLoader.update(client, db, SnapshotRef.of(S, "1.1"),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(result.statistics().diffCount()).isEqualTo(2);
            Networks.assertSameNetworkIgnoringStateVariables(study, client, IDENTITY, Set.of(Changes.LOAD_ID));
            assertThat(db.snapshots(S).snapshotOf(client).orElseThrow().version()).isEqualTo("1.1");
        }
    }

    // ------------------------------------------------------------------ ingesting a timestep from files

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void putAsDiffSshOnly(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);

            SnapshotInfo written = catalog.putAsDiff(TimestepFixtures.ssh(3, T1, "1100"), null,
                    SnapshotRef.of(S, "1.0", T1), params(), ReportNode.NO_OP);

            assertThat(written.timestep()).isEqualTo(T1);
            assertThat(written.edge()).isEqualTo(SnapshotInfo.EdgeKind.TIMESTEP);
            assertThat(written.members()).containsExactly("urn:uuid:ssh-1100");
            assertThat(written.fast()).isTrue();
            // Only the steady state moved; every other profile is the one the root holds
            assertThat(written.state().get(CgmesSubset.EQUIPMENT))
                    .isEqualTo(db.snapshots(S).root().orElseThrow().state().get(CgmesSubset.EQUIPMENT));

            SnapshotCatalog.IngestStatistics statistics = catalog.lastIngestStatistics();
            assertThat(statistics.forwardStatements().get(SSH)).isEqualTo(3);
            assertThat(statistics.reverseStatements().get(SSH)).isEqualTo(3);
            assertThat(statistics.fast().get(SSH)).isTrue();
            assertThat(statistics.ignored()).contains(CgmesSubset.STATE_VARIABLES, CgmesSubset.TOPOLOGY);
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void loadTimestepEqualsFileImport(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            ReadOnlyDataSource files = TimestepFixtures.ssh(3, T1, "1100");
            db.snapshots(S).putAsDiff(files, null, SnapshotRef.of(S, "1.0", T1), params(), ReportNode.NO_OP);

            Network fromDb = load(db, S, "1.0", "11:00");
            Network fromFiles = Network.read(TimestepFixtures.ssh(3, T1, "1100"), params());

            Networks.assertSameNetwork(fromFiles, fromDb, IDENTITY);
            assertThat(fromDb.getCaseDate().toInstant().toString()).isEqualTo(T1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void eqDriftFallsBackToFullReload(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotInfo written = db.snapshots(S).putAsDiff(TimestepFixtures.eqDrift(2, T1, "drift"), null,
                    SnapshotRef.of(S, "1.0", T1), params(), ReportNode.NO_OP);

            // The equipment changed, and no in-place update reads a name
            assertThat(written.fast()).isFalse();
            assertThat(db.snapshots(S).lastIngestStatistics().fast().get(EQ)).isFalse();

            Network client = load(db, S, "1.0", null);
            UpdateResult result = RdfDbNetworkLoader.update(client, db, S, "1.0", "11:00", params(),
                    ReportNode.NO_OP);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_RELOAD);
            assertThat(result.reasons()).anyMatch(reason -> reason.contains("urn:uuid:eq-drift"));
            Networks.assertSameNetwork(Network.read(TimestepFixtures.eqDrift(2, T1, "drift"), params()),
                    result.network(), IDENTITY);
            db.snapshots(S).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void putAsDiffRejectsChangedBoundary(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            assertThatThrownBy(() -> db.snapshots(S).putAsDiff(TimestepFixtures.changedBoundary(T1, "bd"), null,
                    SnapshotRef.of(S, "1.0", T1), params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("boundary model changed");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void putAsDiffOfAnUnchangedExportIsRefused(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            // Zero loads moved: the files describe the state the database already holds
            assertThatThrownBy(() -> db.snapshots(S).putAsDiff(TimestepFixtures.ssh(0, T1, "same"), null,
                    SnapshotRef.of(S, "1.0", T1), params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("no difference to the parent");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aMemberMustDescribeTheMomentItsSnapshotDoes(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            Network sender = load(db, S, "1.0", null);
            List<NetworkEvent> events = Changes.record(sender, n -> Changes.moveLoad(n, 12.0));
            CgmesDiffExport.ExportOptions options = new CgmesDiffExport.ExportOptions()
                    .setScenarioTime(java.time.ZonedDateTime.parse("2014-06-01T23:45:00Z"));
            CgmesDiffExport.Result exported = CgmesDiffExport.toDifferences(sender, events, options);

            assertThatThrownBy(() -> db.snapshots(S).putDiff(exported.differences(),
                    SnapshotRef.of(S, "1.0", T1)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("describe the same moment");
        }
    }
    // ------------------------------------------------------------------ the parent index cache

    /**
     * Every timestep of a day hangs off the same base state, so that state is materialised and decoded once.
     *
     * <p>What the cache has to earn is invisible from the outside &mdash; the differences, the snapshots and the
     * networks are what they always were &mdash; so it is asserted on the hit counter and on the fact that the
     * timesteps after the first report no materialisation time worth speaking of.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aDayOfTimestepsDecodesItsParentStateOnce(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            List<String> instants = List.of(T1, T2, T3);
            for (int i = 0; i < instants.size(); i++) {
                catalog.putAsDiff(TimestepFixtures.ssh(3, instants.get(i), "cache" + i), null,
                        SnapshotRef.of(S, "1.0", instants.get(i)), params(), ReportNode.NO_OP);
                SnapshotCatalog.IngestStatistics statistics = catalog.lastIngestStatistics();
                assertThat(statistics.forwardStatements().get(SSH)).isEqualTo(3);
                assertThat(statistics.reverseStatements().get(SSH)).isEqualTo(3);
            }

            // The first timestep materialised, the two after it did not
            assertThat(catalog.parentIndexCacheHits()).isEqualTo(2);
            // ...and the last one still produced the state the files describe
            Networks.assertSameNetwork(Network.read(TimestepFixtures.ssh(3, T3, "cache2"), params()),
                    load(db, S, "1.0", T3), IDENTITY);
            catalog.verify();
        }
    }

    /**
     * A version written on the base chain between two timesteps is the second one's parent, cache or no cache.
     *
     * <p>The entry the first timestep left behind is keyed by the identifier of the state it compared against, and
     * a new version of the base is a new steady state with a new identifier. So the second timestep cannot hit it,
     * and the difference it writes supersedes the new state &mdash; which {@code putDiff} would refuse outright if
     * it did not, and which the network loaded afterwards proves it did.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aNewBaseVersionBetweenTwoTimestepsIsTheSecondOnesParent(String backend) {
        try (RdfDbConnection db = twoDays(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            catalog.putAsDiff(TimestepFixtures.ssh(3, T1, "before"), null, SnapshotRef.of(S, "1.0", T1),
                    params(), ReportNode.NO_OP);
            long hits = catalog.parentIndexCacheHits();

            Network sender = load(db, S, "1.0", null);
            SnapshotInfo baseVersion = record(sender, db, SnapshotRef.of(S, "1.1"),
                    n -> Changes.moveLoad(n, 12.0)).snapshot();

            SnapshotInfo written = catalog.putAsDiff(TimestepFixtures.ssh(3, T2, "after"), null,
                    SnapshotRef.of(S, "1.0", T2), params(), ReportNode.NO_OP);

            assertThat(catalog.parentIndexCacheHits()).isEqualTo(hits);
            assertThat(written.parent()).isEqualTo(baseVersion.iri());
            Networks.assertSameNetwork(Network.read(TimestepFixtures.ssh(3, T2, "after"), params()),
                    load(db, S, "1.0", T2), IDENTITY);
            catalog.verify();
        }
    }
}
