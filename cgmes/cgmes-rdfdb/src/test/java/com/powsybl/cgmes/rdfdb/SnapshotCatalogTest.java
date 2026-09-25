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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Writing and reading snapshots: the rules of the version chain, and the isolation between scenarios.
 *
 * <p>Every case runs on both backends, and every one of them keeps a second scenario {@code "other"} in the same
 * database holding <em>the same files</em>. That is the hard case for scenario isolation: the model identifiers
 * are identical on both sides, so nothing but the graph scoping keeps the two apart.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class SnapshotCatalogTest {

    private static final String S = "2016-01-01";
    private static final String OTHER = "other";
    private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
    private static final CgmesSubset SSH = CgmesSubset.STEADY_STATE_HYPOTHESIS;
    private static final CgmesSubset EQ = CgmesSubset.EQUIPMENT;
    private static final String BASE_TIMESTEP = "2014-06-01T10:30:00Z";

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

    private static RdfDbConnection open(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "snapshots"));
        db.clear(S);
        db.clear(OTHER);
        return db;
    }

    private static SnapshotInfo root(RdfDbConnection db, String scenario, String version) {
        return db.snapshots(scenario).putFull(be(), null, SnapshotRef.of(scenario, version), params(),
                ReportNode.NO_OP);
    }

    /** A difference of one profile superseding what the given snapshot states for it. */
    private static DifferenceModelSet change(SnapshotInfo parent, CgmesSubset subset, String id, String value) {
        DifferenceModelHeader header = DifferenceModelHeader.builder(id, subset, CIM16)
                .supersedes(List.of(parent.state().get(subset)))
                .profiles(List.of("http://entsoe.eu/CIM/SteadyStateHypothesis/1/1"))
                .build();
        CgmesStatement forward = CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer", "EnergyConsumer.p", value);
        CgmesStatement reverse = CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer", "EnergyConsumer.p", "0.0");
        return new DifferenceModelSet(List.of(new DifferenceModel(header, List.of(forward), List.of(reverse),
                List.of())));
    }

    // ------------------------------------------------------------------ the root

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void putFullCreatesRoot(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo root = root(db, S, "1.0");

            assertThat(root.version()).isEqualTo("1.0");
            assertThat(root.timestep()).isEqualTo(BASE_TIMESTEP);
            assertThat(root.timestepLabel()).isEqualTo("10:30");
            assertThat(root.kind()).isEqualTo(SnapshotInfo.Kind.FULL);
            assertThat(root.depth()).isZero();
            assertThat(root.hasFull()).isTrue();
            assertThat(root.isRoot()).isTrue();
            assertThat(root.edge()).isEqualTo(SnapshotInfo.EdgeKind.NONE);
            assertThat(root.timestepRoot()).isEqualTo(root.iri());
            assertThat(root.state()).containsKeys(EQ, SSH, CgmesSubset.TOPOLOGY, CgmesSubset.STATE_VARIABLES);
            assertThat(root.state()).isEqualTo(root.fullModels());
            assertThat(root.members()).hasSameSizeAs(root.state().values());
            assertThat(root.iri()).startsWith(RdfDbNames.scenarioPrefix(S));

            SnapshotCatalog catalog = db.snapshots(S);
            assertThat(catalog.isVersioned()).isTrue();
            assertThat(catalog.baseTimestep()).isEqualTo(BASE_TIMESTEP);
            assertThat(catalog.baseOffset()).isEqualTo("Z");
            assertThat(catalog.find(SnapshotRef.of(S, "1.0"))).contains(root);
            assertThat(catalog.find(SnapshotRef.latest(S))).contains(root);
            assertThat(catalog.snapshots()).containsExactly(root);
            // The versioned graphs are not instance file contexts of the scenario
            assertThat(db.contextNames(S)).isEmpty();
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void putFullTwiceRejected(String backend) {
        try (RdfDbConnection db = open(backend)) {
            root(db, S, "1.0");
            assertThatThrownBy(() -> root(db, S, "2.0"))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("already has a root snapshot");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void rootPerScenario(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo here = root(db, S, "1.0");
            SnapshotInfo there = root(db, OTHER, "1.0");

            assertThat(here.iri()).isNotEqualTo(there.iri());
            assertThat(db.snapshots(S).snapshots()).containsExactly(here);
            assertThat(db.snapshots(OTHER).snapshots()).containsExactly(there);
            assertThat(db.scenarios()).contains(S, OTHER);
            // The same files, hence the same model identifiers, in two independent sets of graphs
            assertThat(here.state()).isEqualTo(there.state());
            assertThat(db.versionGraph(S).materialization(here.iri()).startModel()
                    .get(SSH).graph()).startsWith(RdfDbNames.scenarioPrefix(S));
            assertThat(db.versionGraph(OTHER).materialization(there.iri()).startModel()
                    .get(SSH).graph()).startsWith(RdfDbNames.scenarioPrefix(OTHER));
            db.snapshots(S).verify();
            db.snapshots(OTHER).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void refOfOtherScenarioRejected(String backend) {
        try (RdfDbConnection db = open(backend)) {
            root(db, S, "1.0");
            assertThatThrownBy(() -> db.snapshots(S).find(SnapshotRef.of(OTHER, "1.0")))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("cannot address scenario 'other'");
        }
    }

    // ------------------------------------------------------------------ the version chain

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void putDiffCreatesChild(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo base = root(db, S, "1.0");
            SnapshotCatalog catalog = db.snapshots(S);

            SnapshotInfo v11 = catalog.putDiff(change(base, SSH, "urn:uuid:ssh-d2", "12.0"),
                    SnapshotRef.of(S, "1.1"));

            assertThat(v11.version()).isEqualTo("1.1");
            assertThat(v11.timestep()).isEqualTo(BASE_TIMESTEP);
            assertThat(v11.kind()).isEqualTo(SnapshotInfo.Kind.DIFF);
            assertThat(v11.parent()).isEqualTo(base.iri());
            assertThat(v11.edge()).isEqualTo(SnapshotInfo.EdgeKind.VERSION);
            assertThat(v11.depth()).isEqualTo(1);
            assertThat(v11.hasFull()).isFalse();
            assertThat(v11.fast()).isTrue();
            assertThat(v11.members()).containsExactly("urn:uuid:ssh-d2");
            assertThat(v11.state().get(SSH)).isEqualTo("urn:uuid:ssh-d2");
            assertThat(v11.state().get(EQ)).isEqualTo(base.state().get(EQ));
            assertThat(v11.timestepRoot()).isEqualTo(base.iri());
            assertThat(catalog.find(SnapshotRef.latest(S))).contains(v11);
            assertThat(catalog.nextVersionLabel(null)).isEqualTo("1.2");
            assertThat(db.catalog(S).model("urn:uuid:ssh-d2")).isPresent();
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void putDiffRejectsFork(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo base = root(db, S, "1.0");
            SnapshotCatalog catalog = db.snapshots(S);
            catalog.putDiff(change(base, SSH, "urn:uuid:ssh-a", "12.0"), SnapshotRef.of(S, "1.1"));

            // A second writer that still thinks the head is 1.0
            assertThatThrownBy(() -> catalog.putDiff(change(base, SSH, "urn:uuid:ssh-b", "13.0"),
                    SnapshotRef.of(S, "1.2")))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("update the network to the head");
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void putDiffRejectsDuplicateVersion(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo base = root(db, S, "1.0");
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo v11 = catalog.putDiff(change(base, SSH, "urn:uuid:ssh-a", "12.0"),
                    SnapshotRef.of(S, "1.1"));

            assertThatThrownBy(() -> catalog.putDiff(change(v11, SSH, "urn:uuid:ssh-b", "13.0"),
                    SnapshotRef.of(S, "1.1")))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("already exists");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void putDiffRejectsWrongSupersedes(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo base = root(db, S, "1.0");
            SnapshotCatalog catalog = db.snapshots(S);
            catalog.putDiff(change(base, SSH, "urn:uuid:ssh-a", "12.0"), SnapshotRef.of(S, "1.1"));

            assertThatThrownBy(() -> catalog.putDiff(change(base, SSH, "urn:uuid:ssh-c", "14.0"),
                    SnapshotRef.of(S, "1.2")))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("supersedes");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void putDiffOfOtherScenarioRejected(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo base = root(db, S, "1.0");
            root(db, OTHER, "1.0");
            assertThatThrownBy(() -> db.snapshots(S).putDiff(change(base, SSH, "urn:uuid:x", "1.0"),
                    SnapshotRef.of(OTHER, "1.1")))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("cannot address scenario 'other'");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void putDiffAtAnUnknownTimestepCreatesItsRoot(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo base = root(db, S, "1.0");
            SnapshotCatalog catalog = db.snapshots(S);

            SnapshotInfo root = catalog.putDiff(change(base, SSH, "urn:uuid:ssh-a", "12.0"),
                    SnapshotRef.of(S, "1.1", "2014-06-01T12:30:00Z"));

            assertThat(root.timestep()).isEqualTo("2014-06-01T12:30:00Z");
            assertThat(root.edge()).isEqualTo(SnapshotInfo.EdgeKind.TIMESTEP);
            assertThat(root.parent()).isEqualTo(base.iri());
            assertThat(root.timestepRoot()).isEqualTo(root.iri());
            catalog.verify();
        }
    }

    // ------------------------------------------------------------------ isolation and housekeeping

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void identicalChainsInTwoScenariosDoNotInterfere(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo here = root(db, S, "1.0");
            SnapshotInfo there = root(db, OTHER, "1.0");
            db.snapshots(S).putDiff(change(here, SSH, "urn:uuid:ssh-s", "12.0"), SnapshotRef.of(S, "1.1"));
            db.snapshots(OTHER).putDiff(change(there, SSH, "urn:uuid:ssh-o", "13.0"),
                    SnapshotRef.of(OTHER, "1.1"));

            assertThat(db.snapshots(S).snapshots()).hasSize(2);
            assertThat(db.snapshots(OTHER).snapshots()).hasSize(2);
            assertThat(db.snapshots(S).find(SnapshotRef.latest(S)).orElseThrow().state().get(SSH))
                    .isEqualTo("urn:uuid:ssh-s");
            assertThat(db.snapshots(OTHER).find(SnapshotRef.latest(OTHER)).orElseThrow().state().get(SSH))
                    .isEqualTo("urn:uuid:ssh-o");
            db.snapshots(S).verify();
            db.snapshots(OTHER).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void dropAllRemovesOnlyThisScenario(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo here = root(db, S, "1.0");
            root(db, OTHER, "1.0");
            db.snapshots(S).putDiff(change(here, SSH, "urn:uuid:ssh-s", "12.0"), SnapshotRef.of(S, "1.1"));

            db.snapshots(S).dropAll();

            assertThat(db.snapshots(S).isVersioned()).isFalse();
            assertThat(db.snapshots(S).snapshots()).isEmpty();
            assertThat(db.snapshots(OTHER).snapshots()).hasSize(1);
            db.snapshots(OTHER).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void migrateImplicitRootMakesAnUnversionedScenarioVersionZero(String backend) {
        try (RdfDbConnection db = open(backend)) {
            db.loadCgmes(S, be(), null, params(), ReportNode.NO_OP);
            assertThat(db.snapshots(S).isVersioned()).isFalse();

            SnapshotInfo migrated = db.snapshots(S).migrateImplicitRoot().orElseThrow();

            assertThat(migrated.version()).isEqualTo("0");
            assertThat(migrated.timestep()).isEqualTo(BASE_TIMESTEP);
            assertThat(migrated.hasFull()).isTrue();
            assertThat(migrated.state()).containsKeys(EQ, SSH);
            assertThat(db.snapshots(S).isVersioned()).isTrue();
            // Idempotent
            assertThat(db.snapshots(S).migrateImplicitRoot().orElseThrow().iri()).isEqualTo(migrated.iri());
            db.snapshots(S).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void blankScenarioRejected(String backend) {
        try (RdfDbConnection db = open(backend)) {
            assertThatThrownBy(() -> db.snapshots("")).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("must not be blank");
            assertThatThrownBy(() -> db.versionGraph(" ")).isInstanceOf(RdfDbException.class);
            assertThatThrownBy(() -> SnapshotRef.of(null, "1.0")).isInstanceOf(RdfDbException.class);
        }
    }

    @Test
    void nextVersionLabelTable() {
        assertThat(SnapshotCatalog.increment("1.1")).isEqualTo("1.2");
        assertThat(SnapshotCatalog.increment("1.9")).isEqualTo("1.10");
        assertThat(SnapshotCatalog.increment("v7")).isEqualTo("v8");
        assertThat(SnapshotCatalog.increment("study")).isEqualTo("study.1");
    }

    @Test
    void anInvalidVersionLabelIsRejected() {
        assertThatThrownBy(() -> SnapshotRef.of(S, "1 1")).isInstanceOf(RdfDbException.class)
                .hasMessageContaining("is not a version label");
    }
}
