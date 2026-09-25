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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A stored {@code pdb:fast} triple on a snapshot node is ignored, whatever it says.
 *
 * <p>Releases before this one wrote the fast-route capability twice: once on every difference model
 * ({@code pdb:fastPredicatesOnly}) and once more, as the conjunction, on the snapshot node ({@code pdb:fast}).
 * Only the first is written now and only the first is read, so a database filled by an older release carries
 * triples this release never looks at. That is a promise worth a test rather than a sentence in a changelog: it is
 * what lets an existing store be read by a new client without a migration, and it is exactly the kind of property
 * that rots silently.</p>
 *
 * <p>The test therefore writes the <strong>wrong</strong> flag onto both kinds of snapshot &mdash; {@code false}
 * where the members are all fast, {@code true} where one of them is not &mdash; and then asks every reader that
 * used to consult it. Each has to answer what the <em>model</em> nodes say.</p>
 *
 * <p>An injection like this is only a test while it really injects: written into the wrong namespace it produces a
 * triple nothing could ever have read, and the assertions below would pass on their own. So the namespace is taken
 * from {@link RdfDbVocabulary#NS}, checked against the literal IRI of the schema, and the injected triples are
 * counted back through a pattern that joins them onto the snapshot nodes.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class LegacyFastTripleTest {

    /** The term of schema v1/v2 that is no longer written, spelled out rather than referenced. */
    private static final String LEGACY_FAST = "http://powsybl.org/ns/rdfdb#fast";

    private static final String S = "2016-01-01";
    private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
    private static final CgmesSubset SSH = CgmesSubset.STEADY_STATE_HYPOTHESIS;
    private static final CgmesSubset EQ = CgmesSubset.EQUIPMENT;
    private static final String LINE_ID = "b58bf21a-096a-4dae-9a01-3f03b60c24c7";

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
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "legacy-fast"));
        db.clear(S);
        return db;
    }

    /** An SSH change of one load: every property it states is one an update query reads. */
    private static DifferenceModel fast(SnapshotInfo parent, String id, String value) {
        DifferenceModelHeader header = DifferenceModelHeader.builder(id, SSH, CIM16)
                .supersedes(List.of(parent.state().get(SSH)))
                .profiles(List.of("http://entsoe.eu/CIM/SteadyStateHypothesis/1/1"))
                .build();
        return new DifferenceModel(header,
                List.of(CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer", "EnergyConsumer.p", value)),
                List.of(CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer", "EnergyConsumer.p", "0.0")),
                List.of());
    }

    /** Renaming a line: no update query reads a name, so this difference is not fast-route capable. */
    private static DifferenceModel slow(SnapshotInfo parent, String id, String name) {
        DifferenceModelHeader header = DifferenceModelHeader.builder(id, EQ, CIM16)
                .supersedes(List.of(parent.state().get(EQ)))
                .profiles(List.of("http://entsoe.eu/CIM/EquipmentCore/3/1"))
                .build();
        return new DifferenceModel(header,
                List.of(CgmesStatement.literal(LINE_ID, "ACLineSegment", "IdentifiedObject.name", name)),
                List.of(CgmesStatement.literal(LINE_ID, "ACLineSegment", "IdentifiedObject.name", "BE-Line_1")),
                List.of());
    }

    private static DifferenceModelSet fastChange(SnapshotInfo parent, String id) {
        return new DifferenceModelSet(List.of(fast(parent, id, "12.0")));
    }

    private static DifferenceModelSet slowChange(SnapshotInfo parent, String id) {
        return new DifferenceModelSet(List.of(slow(parent, id, "renamed")));
    }

    /** Write {@code pdb:fast} onto a snapshot node, the way a release before this one did. */
    private static void inject(RdfDbConnection db, SnapshotInfo snapshot, boolean value) {
        db.sparql(S).update("INSERT DATA { GRAPH <" + RdfDbNames.metaGraph(S) + "> { <" + snapshot.iri() + "> <"
                + LEGACY_FAST + "> " + SparqlText.bool(value) + " } }");
    }

    /** How many {@code pdb:fast} triples sit on a node this scenario really holds as a snapshot. */
    private static long storedFastTriples(RdfDbConnection db) {
        List<Map<String, Value>> rows = db.sparql(S).select(RdfDbVocabulary.PREFIXES
                + "SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + RdfDbNames.metaGraph(S)
                + "> { ?s a pdb:Snapshot ; <" + LEGACY_FAST + "> ?o } }");
        return Long.parseLong(rows.get(0).get("n").stringValue());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aStoredFastFlagIsIgnoredAndTheModelFlagsDecide(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo root = catalog.putFull(be(), null, SnapshotRef.of(S, "1.0"), params(), ReportNode.NO_OP);
            SnapshotInfo v11 = catalog.putDiff(fastChange(root, "urn:uuid:ssh-d2"), SnapshotRef.of(S, "1.1"));
            SnapshotInfo v12 = catalog.putDiff(slowChange(v11, "urn:uuid:eq-d3"), SnapshotRef.of(S, "1.2"));

            // What the model nodes say, and what the derived value therefore is
            assertThat(db.catalog(S).model("urn:uuid:ssh-d2").orElseThrow().fastPredicatesOnly()).isTrue();
            assertThat(db.catalog(S).model("urn:uuid:eq-d3").orElseThrow().fastPredicatesOnly()).isFalse();
            assertThat(v11.fast()).isTrue();
            assertThat(v12.fast()).isFalse();
            // Nothing wrote the retired term
            assertThat(storedFastTriples(db)).isZero();

            // The namespace of the injection is the one the schema uses, or the whole test would be vacuous
            assertThat(LEGACY_FAST).isEqualTo(RdfDbVocabulary.NS + "fast");
            assertThat(RdfDbVocabulary.PREFIXES).contains("PREFIX pdb: <" + RdfDbVocabulary.NS + ">");

            // A store of the older shape, with both flags the wrong way round
            inject(db, v11, false);
            inject(db, v12, true);
            assertThat(storedFastTriples(db)).isEqualTo(2);

            // Every reader that used to consult the snapshot node now follows the member models
            Map<String, SnapshotInfo> listed = catalog.snapshots().stream()
                    .collect(Collectors.toMap(SnapshotInfo::iri, info -> info));
            assertThat(listed.get(v11.iri()).fast()).isTrue();
            assertThat(listed.get(v12.iri()).fast()).isFalse();
            assertThat(catalog.find(SnapshotRef.of(S, "1.1")).orElseThrow().fast()).isTrue();
            assertThat(catalog.find(SnapshotRef.of(S, "1.2")).orElseThrow().fast()).isFalse();
            assertThat(catalog.root().orElseThrow().fast()).isTrue();
            assertThat(catalog.info(v12.iri()).orElseThrow().fast()).isFalse();

            // The route a client would take: the SSH step is applied in place, the rename forces a rebuild
            RdfDbUpdateOptions options = new RdfDbUpdateOptions();
            assertThat(db.versionGraph(S).plan(root.iri(), SnapshotRef.of(S, "1.1"), options).kind())
                    .isEqualTo(UpdatePlan.Kind.DIFF);
            assertThat(db.versionGraph(S).plan(root.iri(), SnapshotRef.of(S, "1.2"), options).kind())
                    .isEqualTo(UpdatePlan.Kind.FULL);

            // And the invariant check has no opinion on a triple it does not read
            assertThatCode(catalog::verify).doesNotThrowAnyException();

            // The stale triples are left exactly as they were: this release neither rewrites nor deletes them
            assertThat(storedFastTriples(db)).isEqualTo(2);
        }
    }

    /**
     * The conjunction over <em>several</em> members, which is where a fold can go wrong without anyone noticing.
     *
     * <p>One snapshot that adds an equipment rename and a load move at once has two members, one of each kind.
     * Its {@code fast} is {@code false} however the rows happen to arrive &mdash; an assignment instead of an
     * {@code &=} would make the answer depend on the order the store returns the two {@code pdb:member} rows in,
     * which is not something a test of single-member snapshots can catch.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aSnapshotIsOnlyFastWhenEveryOneOfItsMembersIs(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo root = catalog.putFull(be(), null, SnapshotRef.of(S, "1.0"), params(), ReportNode.NO_OP);
            SnapshotInfo mixed = catalog.putDiff(new DifferenceModelSet(List.of(
                    slow(root, "urn:uuid:eq-m1", "renamed"), fast(root, "urn:uuid:ssh-m1", "12.0"))),
                    SnapshotRef.of(S, "1.1"));
            SnapshotInfo sshOnly = catalog.putDiff(fastChange(mixed, "urn:uuid:ssh-m2"), SnapshotRef.of(S, "1.2"));

            assertThat(db.catalog(S).model("urn:uuid:eq-m1").orElseThrow().fastPredicatesOnly()).isFalse();
            assertThat(db.catalog(S).model("urn:uuid:ssh-m1").orElseThrow().fastPredicatesOnly()).isTrue();
            for (SnapshotInfo seen : List.of(mixed,
                    catalog.find(SnapshotRef.of(S, "1.1")).orElseThrow(),
                    catalog.info(mixed.iri()).orElseThrow(),
                    catalog.snapshots().stream().filter(i -> i.iri().equals(mixed.iri())).findFirst()
                            .orElseThrow())) {
                assertThat(seen.members()).containsExactlyInAnyOrder("urn:uuid:eq-m1", "urn:uuid:ssh-m1");
                assertThat(seen.fast()).isFalse();
            }
            // The next snapshot adds only the fast member, so it is fast again: the value is per snapshot, not
            // inherited down the chain
            assertThat(sshOnly.fast()).isTrue();
            assertThat(catalog.find(SnapshotRef.of(S, "1.2")).orElseThrow().fast()).isTrue();
            catalog.verify();
        }
    }

    /**
     * A difference member with no {@code pdb:fastPredicatesOnly} at all is read as "not fast".
     *
     * <p>Nothing this code writes produces such a node &mdash; the sink writes the flag unconditionally &mdash;
     * but a hand-written or foreign metadata graph can, and the listing must then agree with the planner, which
     * reads an absent flag as {@code false} ({@code ModelCatalog}, {@code VersionGraph.storedModel}). The flag is
     * deleted here by SPARQL and the deletion is counted, so the case cannot pass by not happening.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aDifferenceMemberWithoutTheFlagIsNotFast(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo root = catalog.putFull(be(), null, SnapshotRef.of(S, "1.0"), params(), ReportNode.NO_OP);
            SnapshotInfo v11 = catalog.putDiff(fastChange(root, "urn:uuid:ssh-d2"), SnapshotRef.of(S, "1.1"));
            assertThat(v11.fast()).isTrue();
            assertThat(flagsOf(db, "urn:uuid:ssh-d2")).isEqualTo(1);

            db.sparql(S).update(RdfDbVocabulary.PREFIXES + "DELETE WHERE { GRAPH <" + RdfDbNames.metaGraph(S)
                    + "> { <urn:uuid:ssh-d2> pdb:fastPredicatesOnly ?o } }");
            assertThat(flagsOf(db, "urn:uuid:ssh-d2")).isZero();
            // The node is still there and still typed as a difference, it just no longer says anything
            assertThat(db.catalog(S).model("urn:uuid:ssh-d2").orElseThrow().fastPredicatesOnly()).isFalse();

            assertThat(catalog.info(v11.iri()).orElseThrow().fast()).isFalse();
            assertThat(catalog.find(SnapshotRef.of(S, "1.1")).orElseThrow().fast()).isFalse();
            assertThat(catalog.snapshots().stream().filter(i -> i.iri().equals(v11.iri())).findFirst()
                    .orElseThrow().fast()).isFalse();
            // The root has no difference member at all, so it stays fast
            assertThat(catalog.root().orElseThrow().fast()).isTrue();
        }
    }

    /** How many {@code pdb:fastPredicatesOnly} triples the given model node carries. */
    private static long flagsOf(RdfDbConnection db, String modelId) {
        List<Map<String, Value>> rows = db.sparql(S).select(RdfDbVocabulary.PREFIXES
                + "SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + RdfDbNames.metaGraph(S) + "> { <" + modelId
                + "> pdb:fastPredicatesOnly ?o } }");
        return Long.parseLong(rows.get(0).get("n").stringValue());
    }
}
