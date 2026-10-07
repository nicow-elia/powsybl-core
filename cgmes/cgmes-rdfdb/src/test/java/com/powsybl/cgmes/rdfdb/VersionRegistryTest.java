/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.commons.report.ReportNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static com.powsybl.cgmes.rdfdb.Backends.BE;
import static com.powsybl.cgmes.rdfdb.Backends.microGridBe;
import static com.powsybl.cgmes.rdfdb.Backends.params;
import static com.powsybl.cgmes.rdfdb.SnapshotCatalogTest.change;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The version registry of a scenario: its names, their ranks, the two modes and the revision every edit is guarded
 * on.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class VersionRegistryTest {

    private static final String S = "registry";
    private static final String OTHER = "registry-other";
    private static final String SSH = Profiles.SSH;
    private static final Instant NOON = Instant.parse("2014-06-01T12:30:00Z");

    private static RdfDbConnection open(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "registry"));
        db.clear(S);
        db.clear(OTHER);
        return db;
    }

    private static SnapshotRef at(String version) {
        return SnapshotRef.of(S, BE, null, version);
    }

    private static SnapshotInfo root(RdfDbConnection db, String version) {
        return db.snapshots(S).putFull(microGridBe(), null, at(version), null, params(), ReportNode.NO_OP);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aCreatedRegistryIsStrictUnlessAskedAndRanksInStepsOfTen(String backend) {
        try (RdfDbConnection db = open(backend)) {
            VersionRegistry registry = db.snapshots(S).registry();
            assertThat(registry.rev()).isZero();
            assertThat(registry.names()).isEmpty();
            registry.create(List.of("DA", "ID", "RT"), false);
            assertThat(registry.names()).containsExactly("DA", "ID", "RT");
            assertThat(registry.ranks()).containsExactly(Map.entry("DA", 10), Map.entry("ID", 20),
                    Map.entry("RT", 30));
            assertThat(registry.isPermissive()).isFalse();
            assertThat(registry.rev()).isEqualTo(1);
            assertThat(registry.rank("XX")).isEqualTo(OptionalInt.empty());
            // Another catalogue, with a cache of its own, reads the same registry
            assertThat(new SnapshotCatalog(db, S).registry().ranks()).isEqualTo(registry.ranks());
            assertThatThrownBy(() -> registry.create(List.of("X"), true)).isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("already has a version registry");

            VersionRegistry permissive = db.snapshots(OTHER).registry();
            permissive.create(List.of(), true);
            assertThat(permissive.isPermissive()).isTrue();
            assertThat(permissive.names()).isEmpty();
            // Two scenarios never see each other's names
            assertThat(registry.names()).containsExactly("DA", "ID", "RT");
            assertThatThrownBy(() -> db.snapshots(S).registry().create(List.of("A", "A"), false))
                    .isInstanceOf(RdfDbException.class).hasMessageContaining("listed twice");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void namesAreAppendedAndInsertedAtTheMidpoint(String backend) {
        try (RdfDbConnection db = open(backend)) {
            VersionRegistry registry = db.snapshots(S).registry();
            assertThatThrownBy(() -> registry.add("DA")).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("has no version registry");
            registry.create(List.of("DA", "RT"), false);
            assertThat(registry.add("X")).isEqualTo(30);
            assertThat(registry.insert("ID", "DA")).isEqualTo(15);
            assertThat(registry.insert("ID2", "ID")).isEqualTo(17);
            assertThat(registry.insert("ID3", "ID2")).isEqualTo(18);
            assertThat(registry.insert("first", null)).isEqualTo(5);
            assertThat(registry.insert("last", "X")).isEqualTo(40);
            assertThat(registry.names()).containsExactly("first", "DA", "ID", "ID2", "ID3", "RT", "X", "last");
            assertThatThrownBy(() -> registry.insert("ID4", "ID2")).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("no rank between 'ID2' (17) and 'ID3' (18)")
                    .hasMessageContaining("rerank first");
            assertThatThrownBy(() -> registry.add("RT")).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("already registered");
            assertThatThrownBy(() -> registry.insert("Y", "nope")).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("version 'nope' is not registered");
            assertThat(registry.rev()).isEqualTo(7);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void unusedNamesAreRerankedRenamedMarkedAndDeleted(String backend) {
        try (RdfDbConnection db = open(backend)) {
            VersionRegistry registry = db.snapshots(S).registry();
            registry.create(List.of("DA", "ID", "RT"), false);
            registry.rerank(Map.of("RT", 5));
            assertThat(registry.names()).containsExactly("RT", "DA", "ID");
            assertThatThrownBy(() -> registry.rerank(Map.of("ID", 10))).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("'DA' and 'ID' would share the rank 10");
            assertThatThrownBy(() -> registry.rerank(Map.of("ID", 0))).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("at least 1");
            registry.rename("RT", "realtime");
            assertThat(registry.names()).containsExactly("realtime", "DA", "ID");
            assertThat(registry.rank("realtime")).hasValue(5);
            registry.markTransient("ID", true);
            assertThat(registry.isTransient("ID")).isTrue();
            registry.markTransient("ID", false);
            assertThat(registry.isTransient("ID")).isFalse();
            registry.delete("DA");
            assertThat(registry.names()).containsExactly("realtime", "ID");
            assertThat(registry.rev()).isEqualTo(6);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anEditThatLostARaceIsAConflictAndChangesNothing(String backend) {
        try (RdfDbConnection db = open(backend)) {
            db.snapshots(S).registry().create(List.of("DA"), true);
            // A second catalogue of the same scenario is what another process holds: its own cached registry
            VersionRegistry mine = new SnapshotCatalog(db, S).registry();
            VersionRegistry theirs = new SnapshotCatalog(db, S).registry();
            assertThat(mine.names()).containsExactly("DA");
            assertThat(theirs.names()).containsExactly("DA");
            mine.beforeRegistryWrite(() -> theirs.add("ID"));
            assertThatThrownBy(() -> mine.add("RT")).isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("changed (rev 1 → 2)");
            // Theirs won at rank 20; mine wrote nothing and now knows the registry as it is
            assertThat(mine.ranks()).containsExactly(Map.entry("DA", 10), Map.entry("ID", 20));
            mine.beforeRegistryWrite(null);
            assertThat(mine.add("RT")).isEqualTo(30);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void namesSnapshotsCarryAreNeitherRenamedNorDeletedAndRerankKeepsEveryChainOrdered(String backend) {
        try (RdfDbConnection db = open(backend)) {
            db.snapshots(S).registry().create(List.of("DA", "ID", "RT"), false);
            SnapshotInfo da = root(db, "DA");
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo id = catalog.putDiff(change(da, SSH, "urn:uuid:reg-id", "12.0"), at("ID"));
            VersionRegistry registry = catalog.registry();

            assertThatThrownBy(() -> registry.rerank(Map.of("DA", 25))).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("would put version 'ID' (20) of snapshot " + id.iri())
                    .hasMessageContaining("at or below the version 'DA' (25) of its parent");
            // A rerank that keeps the chain is fine, and RT, which no snapshot carries, may go anywhere
            registry.rerank(Map.of("RT", 1, "ID", 100));
            assertThat(registry.names()).containsExactly("RT", "DA", "ID");
            assertThat(catalog.require(id.ref()).rank()).isEqualTo(100);

            assertThatThrownBy(() -> registry.rename("ID", "intraday")).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("cannot be renamed: the snapshot " + id.iri() + " carries it");
            assertThatThrownBy(() -> registry.delete("DA")).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("cannot be deleted: the snapshot " + da.iri() + " carries it");
            assertThat(registry.names()).containsExactly("RT", "DA", "ID");
            registry.rename("RT", "realtime");
            registry.delete("realtime");
            assertThat(registry.names()).containsExactly("DA", "ID");
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aTransientVersionIsDeletedWithItsLeafSnapshots(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo base = root(db, "1");
            SnapshotCatalog catalog = db.snapshots(S);
            VersionRegistry registry = catalog.registry();
            registry.add("scratch");
            registry.markTransient("scratch", true);
            SnapshotInfo noon = catalog.putDiff(change(base, SSH, "urn:uuid:reg-noon", "11.0"),
                    SnapshotRef.of(S, BE, NOON, "1"));
            SnapshotInfo baseScratch = catalog.putDiff(change(base, SSH, "urn:uuid:reg-s1", "12.0"), at("scratch"));
            SnapshotInfo noonScratch = catalog.putDiff(change(noon, SSH, "urn:uuid:reg-s2", "13.0"),
                    SnapshotRef.of(S, BE, NOON, "scratch"));
            assertThat(catalog.snapshots()).contains(baseScratch, noonScratch);

            registry.delete("scratch");

            assertThat(registry.names()).containsExactly("1");
            assertThat(catalog.snapshots()).containsExactlyInAnyOrder(base, noon);
            assertThat(db.catalog(S).model("urn:uuid:reg-s1")).isEmpty();
            assertThat(db.catalog(S).model("urn:uuid:reg-s2")).isEmpty();
            assertThat(Backends.count(db, S, RdfDbNames.forwardGraph(S, "urn:uuid:reg-s1"), "?s ?p ?o")).isZero();
            assertThat(Backends.count(db, S, RdfDbNames.reverseGraph(S, "urn:uuid:reg-s2"), "?s ?p ?o")).isZero();
            // The parents are intact, and the heads moved back to them
            assertThat(catalog.head(BE, null)).contains(base);
            assertThat(catalog.head(BE, NOON)).contains(noon);
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aTransientVersionWithAChildIsRefusedNamingIt(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo base = root(db, "1");
            SnapshotCatalog catalog = db.snapshots(S);
            VersionRegistry registry = catalog.registry();
            registry.add("scratch");
            registry.markTransient("scratch", true);
            SnapshotInfo scratch = catalog.putDiff(change(base, SSH, "urn:uuid:reg-s1", "12.0"), at("scratch"));
            SnapshotInfo child = catalog.putDiff(change(scratch, SSH, "urn:uuid:reg-c", "13.0"), at("final"));

            assertThatThrownBy(() -> registry.delete("scratch")).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining(scratch.iri())
                    .hasMessageContaining("has the child " + child.iri());
            assertThat(registry.names()).containsExactly("1", "scratch", "final");
            assertThat(catalog.snapshots()).containsExactly(base, scratch, child);
            catalog.verify();
        }
    }
}
