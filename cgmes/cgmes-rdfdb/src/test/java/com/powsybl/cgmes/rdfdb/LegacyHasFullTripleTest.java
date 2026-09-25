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
import org.eclipse.rdf4j.model.Value;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A stored {@code pdb:hasFull} triple on a snapshot node is ignored, whatever it says.
 *
 * <p>"Can a materialisation start here" was written twice by earlier releases: as the {@code pdb:full} links
 * naming the full model of each profile, and as a boolean beside them. The links are the statement &mdash; a
 * snapshot can start one exactly when it names a full model &mdash; so the boolean is no longer written and
 * {@link SnapshotInfo#hasFull()} is derived. A database filled before the change still carries it, and this test
 * is the promise that it does not matter: the flags are injected <strong>the wrong way round</strong> on all
 * three shapes a snapshot comes in, and every reader that used to consult them is re-asked.</p>
 *
 * <p>The three shapes: a root (links, so {@code true}), a plain difference snapshot (no links, {@code false}) and
 * a checkpointed difference snapshot (links the checkpoint added, {@code true}). The interesting readers are the
 * two that <em>act</em> on the answer: {@link Checkpoint#create} skips a snapshot that already has full graphs, and
 * the planner measures the distance to the nearest one. A release that read the stale boolean would refuse to
 * checkpoint the plain snapshot and would fold the checkpointed one a second time.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class LegacyHasFullTripleTest {

    /** The term of schema v1/v2 that is no longer written, spelled out rather than referenced. */
    private static final String LEGACY_HAS_FULL = "http://powsybl.org/ns/rdfdb#hasFull";

    private static final String S = "2016-01-01";
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

    private static RdfDbConnection open(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "legacy-hasfull"));
        db.clear(S);
        return db;
    }

    private static DifferenceModelSet change(SnapshotInfo parent, String id, String value) {
        DifferenceModelHeader header = DifferenceModelHeader.builder(id, SSH, CIM16)
                .supersedes(List.of(parent.state().get(SSH)))
                .profiles(List.of("http://entsoe.eu/CIM/SteadyStateHypothesis/1/1"))
                .build();
        return new DifferenceModelSet(List.of(new DifferenceModel(header,
                List.of(CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer", "EnergyConsumer.p", value)),
                List.of(CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer", "EnergyConsumer.p", "0.0")),
                List.of())));
    }

    /** Write {@code pdb:hasFull} onto a snapshot node, the way a release before this one did. */
    private static void inject(RdfDbConnection db, SnapshotInfo snapshot, boolean value) {
        db.sparql(S).update("INSERT DATA { GRAPH <" + RdfDbNames.metaGraph(S) + "> { <" + snapshot.iri() + "> <"
                + LEGACY_HAS_FULL + "> " + SparqlText.bool(value) + " } }");
    }

    /** How many {@code pdb:hasFull} triples sit on a node this scenario really holds as a snapshot. */
    private static long storedHasFullTriples(RdfDbConnection db) {
        return count(db, "?s a pdb:Snapshot ; <" + LEGACY_HAS_FULL + "> ?o");
    }

    /** How many materialised checkpoint copies the scenario holds. */
    private static long materializedNodes(RdfDbConnection db) {
        return count(db, "?m a pdb:Materialized");
    }

    private static long count(RdfDbConnection db, String pattern) {
        List<Map<String, Value>> rows = db.sparql(S).select(RdfDbVocabulary.PREFIXES
                + "SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + RdfDbNames.metaGraph(S) + "> { " + pattern + " } }");
        return Long.parseLong(rows.get(0).get("n").stringValue());
    }

    /**
     * Every triple of every graph of the whole database.
     *
     * <p>Counting {@code pdb:Materialized} nodes would not discriminate: a second checkpoint of the same snapshot
     * mints the same IRIs, and RDF is a set, so re-writing them would leave that number alone. The total is what
     * a re-run would move &mdash; a re-materialised graph, a re-inserted header, an extra link.</p>
     */
    private static long totalTriples(RdfDbConnection db) {
        List<Map<String, Value>> rows = db.sparql(S).select(
                "SELECT (COUNT(*) AS ?n) WHERE { GRAPH ?g { ?s ?p ?o } }");
        return Long.parseLong(rows.get(0).get("n").stringValue());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aStoredHasFullFlagIsIgnoredAndTheLinksDecide(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo root = catalog.putFull(be(), null, SnapshotRef.of(S, "1.0"), params(), ReportNode.NO_OP);
            SnapshotInfo plain = catalog.putDiff(change(root, "urn:uuid:ssh-d2", "12.0"), SnapshotRef.of(S, "1.1"));
            SnapshotInfo folded = catalog.putDiff(change(plain, "urn:uuid:ssh-d3", "13.0"),
                    SnapshotRef.of(S, "1.2"));
            // After a versioned export: the retired term appears nowhere
            assertThat(db.sparql(S).ask("ASK { GRAPH <" + RdfDbNames.metaGraph(S) + "> { ?s <"
                    + LEGACY_HAS_FULL + "> ?o } }")).isFalse();

            // Before the checkpoint the nearest snapshot with full graphs is the root, two steps up
            RdfDbUpdateOptions after1 = new RdfDbUpdateOptions().setCheckpointAfter(1);
            assertThat(db.versionGraph(S).plan(root.iri(), SnapshotRef.of(S, "1.2"), after1)
                    .distanceToFullSnapshot()).isEqualTo(2);
            assertThat(db.versionGraph(S).plan(root.iri(), SnapshotRef.of(S, "1.2"), after1)
                    .checkpointRecommended()).isTrue();

            folded = Checkpoint.create(db, SnapshotRef.of(S, "1.2"));
            long copies = materializedNodes(db);
            assertThat(copies).isPositive();
            // ...and after a checkpoint either: the pdb:full links are the whole statement
            assertThat(db.sparql(S).ask("ASK { GRAPH <" + RdfDbNames.metaGraph(S) + "> { ?s <"
                    + LEGACY_HAS_FULL + "> ?o } }")).isFalse();

            // The three shapes, as the links say
            assertThat(root.fullModels()).isNotEmpty();
            assertThat(root.hasFull()).isTrue();
            assertThat(plain.fullModels()).isEmpty();
            assertThat(plain.hasFull()).isFalse();
            assertThat(folded.fullModels()).isNotEmpty();
            assertThat(folded.hasFull()).isTrue();

            // The namespace of the injection is the one the schema uses, or the whole test would be vacuous
            assertThat(LEGACY_HAS_FULL).isEqualTo(RdfDbVocabulary.NS + "hasFull");
            assertThat(RdfDbVocabulary.PREFIXES).contains("PREFIX pdb: <" + RdfDbVocabulary.NS + ">");

            // A store of the older shape, every one of the three flags the wrong way round
            inject(db, root, false);
            inject(db, plain, true);
            inject(db, folded, false);
            assertThat(storedHasFullTriples(db)).isEqualTo(3);

            // The listings follow the links
            Map<String, SnapshotInfo> listed = catalog.snapshots().stream()
                    .collect(java.util.stream.Collectors.toMap(SnapshotInfo::iri, info -> info));
            assertThat(listed.get(root.iri()).hasFull()).isTrue();
            assertThat(listed.get(plain.iri()).hasFull()).isFalse();
            assertThat(listed.get(folded.iri()).hasFull()).isTrue();
            assertThat(catalog.find(SnapshotRef.of(S, "1.1")).orElseThrow().hasFull()).isFalse();
            assertThat(catalog.find(SnapshotRef.of(S, "1.2")).orElseThrow().hasFull()).isTrue();
            assertThat(catalog.info(root.iri()).orElseThrow().hasFull()).isTrue();
            assertThat(catalog.root().orElseThrow().hasFull()).isTrue();

            // The planner measures the distance on the links: the checkpointed snapshot is its own start, the
            // plain one is one step away from the root
            assertThat(db.versionGraph(S).plan(root.iri(), SnapshotRef.of(S, "1.2"), after1)
                    .distanceToFullSnapshot()).isZero();
            // ...and the same plan no longer recommends a checkpoint, although the stale flag says it has none
            assertThat(db.versionGraph(S).plan(root.iri(), SnapshotRef.of(S, "1.2"), after1)
                    .checkpointRecommended()).isFalse();
            assertThat(db.versionGraph(S).plan(root.iri(), SnapshotRef.of(S, "1.1"), after1)
                    .distanceToFullSnapshot()).isEqualTo(1);
            assertThat(Checkpoint.recommended(db.versionGraph(S).plan(root.iri(), SnapshotRef.of(S, "1.2"), after1),
                    after1)).isFalse();

            // A materialisation of the checkpointed snapshot starts at its own copies and applies nothing
            MaterializationPlan plan = db.versionGraph(S).materialization(folded.iri());
            assertThat(plan.steps()).isEmpty();
            assertThat(plan.startModel().get(SSH).snapshot()).isEqualTo(folded.iri());
            assertThat(plan.startModel().get(SSH).graph()).contains("/materialized/");

            // Idempotent although the stale flag says the checkpoint is not there: the guard short-circuits and
            // the store does not move by a single triple
            long triples = totalTriples(db);
            assertThat(Checkpoint.create(db, SnapshotRef.of(S, "1.2")).iri()).isEqualTo(folded.iri());
            assertThat(totalTriples(db)).isEqualTo(triples);
            assertThat(materializedNodes(db)).isEqualTo(copies);

            // And the plain snapshot really is checkpointed although the stale flag claims it already was
            SnapshotInfo checkpointed = Checkpoint.create(db, SnapshotRef.of(S, "1.1"));
            assertThat(checkpointed.hasFull()).isTrue();
            assertThat(materializedNodes(db)).isGreaterThan(copies);
            assertThat(totalTriples(db)).isGreaterThan(triples);
            assertThat(db.versionGraph(S).materialization(plain.iri()).steps()).isEmpty();

            // The invariants have no opinion on a triple nothing reads, and the stale triples stay untouched
            assertThatCode(catalog::verify).doesNotThrowAnyException();
            assertThat(storedHasFullTriples(db)).isEqualTo(3);
        }
    }
}
