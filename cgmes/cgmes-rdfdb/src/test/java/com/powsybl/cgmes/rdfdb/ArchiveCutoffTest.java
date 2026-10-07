/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.powsybl.cgmes.rdfdb.Backends.BE;
import static com.powsybl.cgmes.rdfdb.Backends.microGridBe;
import static com.powsybl.cgmes.rdfdb.Backends.params;
import static com.powsybl.cgmes.rdfdb.Backends.ref;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An archive cutoff: the states of a scenario before a moment were moved elsewhere, and this store refuses to serve
 * them, naming where they went. Every read that resolves an address refuses a target before the cutoff &mdash; with
 * the same text, decided inside the query that resolves it, so that a cutoff set through another connection holds
 * at once &mdash; while the listings still show the archived snapshots.
 *
 * <p>The fixture is what an owner archives: the base at 10:30, 11:00 ingested and rolled over, 11:15 ingested
 * against it; the cutoff is set at the rollover.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class ArchiveCutoffTest {

    private static final String S = "2016-01-01";
    private static final Instant BASE = Instant.parse("2014-06-01T10:30:00Z");
    private static final Instant T1 = Instant.parse("2014-06-01T11:00:00Z");
    private static final Instant T2 = Instant.parse("2014-06-01T11:15:00Z");
    private static final Instant T3 = Instant.parse("2014-06-01T11:30:00Z");
    private static final String LOCATION = "s3://archive/2016-01-01";
    private static final Set<String> IDENTITY = Set.of("cgmesMetadataModels", "rdfDbProvenance");

    private static RdfDbConnection day(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "archive-cutoff"));
        db.clear(S);
        SnapshotCatalog catalog = db.snapshots(S);
        catalog.putFull(microGridBe(), null, ref(S, 1), null, params(), ReportNode.NO_OP);
        SnapshotInfo t1 = catalog.putAsDiff(TimestampFixtures.ssh(2, T1, "t1"), null, ref(S, 1, T1), null, params(),
                ReportNode.NO_OP);
        catalog.rollover(t1.ref());
        catalog.putAsDiff(TimestampFixtures.ssh(3, T2, "t2"), null, ref(S, 1, T2), null, params(), ReportNode.NO_OP);
        return db;
    }

    private static Network load(RdfDbConnection db, SnapshotRef ref) {
        return RdfDbNetworkLoader.load(db, ref, null, params(), ReportNode.NO_OP);
    }

    private static String refusal(Object what) {
        return "snapshot " + what + " is in the archive at " + LOCATION + ": states before " + T1
                + " are not served by this store";
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aReadBeforeTheCutoffIsRefusedNamingTheArchive(String backend) {
        try (RdfDbConnection db = day(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            catalog.setArchiveCutoff(T1, LOCATION);
            assertThat(catalog.archiveCutoff()).contains(new SnapshotCatalog.ArchiveCutoff(T1, LOCATION));

            // The base, by its open address and by its timestamp: roots are not exempt
            assertThatThrownBy(() -> load(db, ref(S, 1))).isInstanceOf(RdfDbException.class)
                    .hasMessage(refusal(ref(S, 1)));
            assertThatThrownBy(() -> load(db, ref(S, 1, BASE))).isInstanceOf(RdfDbException.class)
                    .hasMessage(refusal(ref(S, 1, BASE)));
            assertThatThrownBy(() -> catalog.find(ref(S, 1, BASE))).hasMessage(refusal(ref(S, 1, BASE)));

            // At and after the cutoff everything is served, from the rollover's full graphs
            Networks.assertSameNetwork(Network.read(TimestampFixtures.ssh(2, T1, "t1"), params()),
                    load(db, ref(S, 1, T1)), IDENTITY);
            Networks.assertSameNetwork(Network.read(TimestampFixtures.ssh(3, T2, "t2"), params()),
                    load(db, ref(S, 1, T2)), IDENTITY);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anUpdateIntoTheArchiveIsRefusedAndTheNetworkIsUntouched(String backend) {
        try (RdfDbConnection db = day(backend)) {
            Network network = load(db, ref(S, 1, T2));
            db.snapshots(S).setArchiveCutoff(T1, LOCATION);

            assertThatThrownBy(() -> RdfDbNetworkLoader.update(network, db, ref(S, 1, BASE),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class).hasMessage(refusal(ref(S, 1, BASE)));
            Networks.assertSameNetwork(load(db, ref(S, 1, T2)), network, IDENTITY);
            // ...while an update inside the served part still walks
            assertThat(RdfDbNetworkLoader.update(network, db, ref(S, 1, T1), new RdfDbUpdateOptions(), params(),
                    ReportNode.NO_OP).route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aNetworkAtAnArchivedSnapshotIsReloadedNotWalkedFrom(String backend) {
        try (RdfDbConnection db = day(backend)) {
            // Loaded before the cutoff was set: the network is at the base, whose differences may be gone
            Network network = load(db, ref(S, 1));
            db.snapshots(S).setArchiveCutoff(T1, LOCATION);

            UpdatePlan plan = db.versionGraph(S).plan(network, ref(S, 1, T2), new RdfDbUpdateOptions());
            assertThat(plan.kind()).isEqualTo(UpdatePlan.Kind.FULL);
            assertThat(plan.steps()).isEmpty();
            assertThat(plan.reasons()).singleElement().asString().contains("is in the archive at " + LOCATION);
            UpdateResult result = RdfDbNetworkLoader.update(network, db, ref(S, 1, T2), new RdfDbUpdateOptions(),
                    params(), ReportNode.NO_OP);
            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_RELOAD);
            Networks.assertSameNetwork(load(db, ref(S, 1, T2)), result.network(), IDENTITY);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aRecordedChangeIsNotFiledUnderAnArchivedSnapshot(String backend) {
        try (RdfDbConnection db = day(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            Network sender = load(db, ref(S, 1));
            catalog.setArchiveCutoff(T1, LOCATION);
            List<SnapshotInfo> before = catalog.snapshots();

            // The default pin would be the sender's snapshot, the base: the only one stating its steady state
            assertThatThrownBy(() -> Changes.export(sender, db, ref(S, 1, T3), n -> Changes.moveLoad(n, 12.0)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessage(refusal(catalog.root(BE).orElseThrow().ref()));
            assertThat(catalog.snapshots()).isEqualTo(before);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anIngestionIsNotComparedAgainstAnArchivedRollover(String backend) {
        try (RdfDbConnection db = day(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            // The cutoff after the rollover with no rollover at or after it: the default pin, 11:00, is archived
            catalog.setArchiveCutoff(T2, LOCATION);
            List<SnapshotInfo> before = catalog.snapshots();

            assertThatThrownBy(() -> catalog.putAsDiff(TimestampFixtures.ssh(1, T3, "t3"), null, ref(S, 1, T3), null,
                    params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessage("snapshot " + ref(S, 1, T1) + " is in the archive at " + LOCATION + ": states before " + T2
                            + " are not served by this store");
            assertThat(catalog.snapshots()).isEqualTo(before);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void theAssemblyTheChangesBetweenAndABulkLoadBeforeTheCutoffAreRefused(String backend) {
        try (RdfDbConnection db = day(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            catalog.setArchiveCutoff(T1, LOCATION);

            assertThatThrownBy(() -> catalog.assembly(BASE, null)).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("is in the archive at " + LOCATION);
            assertThat(catalog.assembly(T1, null)).containsOnlyKeys(BE);

            assertThatThrownBy(() -> RdfDbNetworkLoader.changesBetween(db, ref(S, 1, BASE), ref(S, 1, T2)))
                    .isInstanceOf(RdfDbException.class).hasMessage(refusal(ref(S, 1, BASE)));
            assertThat(RdfDbNetworkLoader.changesBetween(db, ref(S, 1, T1), ref(S, 1, T2)).models()).isNotEmpty();

            // One archived request refuses the whole bulk load before anything is loaded
            assertThatThrownBy(() -> RdfDbNetworkLoader.loadVariants(db, S, List.of(
                    VariantRequest.of(ref(S, 1, T1)), VariantRequest.of(ref(S, 1, BASE))),
                    new RdfDbVariantLoadOptions(), null, params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class).hasMessage(refusal(ref(S, 1, BASE)));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void theListingsStillShowTheArchivedSnapshots(String backend) {
        try (RdfDbConnection db = day(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            List<SnapshotInfo> before = catalog.snapshots();
            catalog.setArchiveCutoff(T1, LOCATION);

            assertThat(catalog.snapshots()).isEqualTo(before);
            assertThat(catalog.snapshots()).anyMatch(s -> s.timestamp().equals(BASE));
            assertThat(catalog.timestamps(BE)).anyMatch(t -> t.timestamp().equals(BASE));
            assertThat(catalog.versions(BE, BASE)).isNotEmpty();
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aClearedCutoffServesTheArchiveAgainAndHalfACutoffIsRefused(String backend) {
        try (RdfDbConnection db = day(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            long rev = catalog.registry().rev();
            catalog.setArchiveCutoff(T1, LOCATION);
            assertThat(catalog.registry().rev()).isEqualTo(rev + 1);

            catalog.setArchiveCutoff(null, null);
            assertThat(catalog.archiveCutoff()).isEmpty();
            assertThat(catalog.registry().rev()).isEqualTo(rev + 2);
            Networks.assertSameNetwork(Network.read(microGridBe(), params()), load(db, ref(S, 1)), IDENTITY);

            assertThatThrownBy(() -> catalog.setArchiveCutoff(T1, null)).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("together");
            assertThatThrownBy(() -> catalog.setArchiveCutoff(null, LOCATION)).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("together");
            assertThat(catalog.archiveCutoff()).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aCutoffSetThroughAnotherConnectionHoldsAtOnce(String backend) {
        try (RdfDbConnection db = day(backend)) {
            SnapshotCatalog mine = db.snapshots(S);
            assertThat(mine.archiveCutoff()).isEmpty();
            // Another process's catalogue, with its own cache: this one still believes there is no cutoff
            new SnapshotCatalog(db, S).setArchiveCutoff(T1, LOCATION);

            // The filter is in the query that resolves the address: nothing archived is served, and the refusal
            // re-reads the schema node to name the archive
            assertThatThrownBy(() -> load(db, ref(S, 1, BASE))).isInstanceOf(RdfDbException.class)
                    .hasMessage(refusal(ref(S, 1, BASE)));
            assertThat(mine.archiveCutoff()).contains(new SnapshotCatalog.ArchiveCutoff(T1, LOCATION));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aWriteIntoATimestampArchivedElsewhereNamesTheArchive(String backend) {
        try (RdfDbConnection db = day(backend)) {
            SnapshotCatalog mine = db.snapshots(S);
            SnapshotInfo t1 = mine.require(ref(S, 1, T1));
            new SnapshotCatalog(db, S).setArchiveCutoff(T2, LOCATION);
            List<SnapshotInfo> before = new SnapshotCatalog(db, S).snapshots();

            // This catalogue still believes 11:00 is served: its head lookup finds nothing (the filter is in the
            // query), the write is refused by its guard, and the refusal names the archive, not a lost race
            assertThatThrownBy(() -> mine.putDiff(SnapshotCatalogTest.change(t1, Profiles.SSH, "urn:uuid:late",
                    "12.0"), ref(S, 2, T1)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("is in the archive at " + LOCATION)
                    .hasMessageContaining("states before " + T2);
            assertThat(mine.snapshots()).isEqualTo(before);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aNewTimestampBeforeACutoffSetElsewhereIsRefusedNamingTheArchive(String backend) {
        try (RdfDbConnection db = day(backend)) {
            SnapshotCatalog mine = db.snapshots(S);
            SnapshotInfo root = mine.require(ref(S, 1));
            new SnapshotCatalog(db, S).setArchiveCutoff(T2, LOCATION);
            List<SnapshotInfo> before = new SnapshotCatalog(db, S).snapshots();
            Instant early = Instant.parse("2014-06-01T10:45:00Z");

            assertThatThrownBy(() -> mine.putDiff(SnapshotCatalogTest.change(root, Profiles.SSH, "urn:uuid:early",
                    "12.0"), ref(S, 1, early)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("is in the archive at " + LOCATION)
                    .hasMessageContaining("states before " + T2);
            assertThat(mine.snapshots()).isEqualTo(before);
        }
    }
}
