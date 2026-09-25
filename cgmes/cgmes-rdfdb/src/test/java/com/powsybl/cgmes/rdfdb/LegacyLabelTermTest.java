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

/**
 * A snapshot node that names its display label {@code pdb:label} is still read.
 *
 * <p>The term was renamed to {@code pdb:timestepLabel} because the bare name said neither what it labels nor that
 * it is display-only: {@code pdb:timestep} is the key a lookup matches on, the label is its local-time rendering,
 * and a snapshot's <em>version</em> is a free-form label too. Only the new term is written; the old one is still
 * accepted, because a store filled by an earlier release must keep showing {@code "10:30"} rather than nothing.</p>
 *
 * <p>The rewrite below is a real one &mdash; the new triples are deleted and the old ones inserted in their
 * place, with both counts asserted before and after &mdash; so the case cannot pass by not happening.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class LegacyLabelTermTest {

    /** What {@code pdb:timestepLabel} was called before this release, spelled out rather than referenced. */
    private static final String LEGACY_LABEL = "http://powsybl.org/ns/rdfdb#label";

    private static final String S = "2016-01-01";
    private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
    private static final CgmesSubset SSH = CgmesSubset.STEADY_STATE_HYPOTHESIS;
    private static final String BASE_LABEL = "10:30";

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
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "legacy-label"));
        db.clear(S);
        return db;
    }

    private static DifferenceModelSet change(SnapshotInfo parent, String id) {
        DifferenceModelHeader header = DifferenceModelHeader.builder(id, SSH, CIM16)
                .supersedes(List.of(parent.state().get(SSH)))
                .profiles(List.of("http://entsoe.eu/CIM/SteadyStateHypothesis/1/1"))
                .build();
        return new DifferenceModelSet(List.of(new DifferenceModel(header,
                List.of(CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer", "EnergyConsumer.p", "12.0")),
                List.of(CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer", "EnergyConsumer.p", "0.0")),
                List.of())));
    }

    private static long count(RdfDbConnection db, String predicate) {
        List<Map<String, Value>> rows = db.sparql(S).select(RdfDbVocabulary.PREFIXES
                + "SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + RdfDbNames.metaGraph(S) + "> { ?s a pdb:Snapshot ; <"
                + predicate + "> ?o } }");
        return Long.parseLong(rows.get(0).get("n").stringValue());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aStoreWithTheRetiredLabelTermIsStillRead(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo root = catalog.putFull(be(), null, SnapshotRef.of(S, "1.0"), params(), ReportNode.NO_OP);
            SnapshotInfo v11 = catalog.putDiff(change(root, "urn:uuid:ssh-d2"), SnapshotRef.of(S, "1.1"));

            assertThat(root.timestepLabel()).isEqualTo(BASE_LABEL);
            assertThat(v11.timestepLabel()).isEqualTo(BASE_LABEL);
            // After a versioned export the retired term appears nowhere, and the new one on every snapshot
            assertThat(db.sparql(S).ask("ASK { GRAPH <" + RdfDbNames.metaGraph(S) + "> { ?s <" + LEGACY_LABEL
                    + "> ?o } }")).isFalse();
            assertThat(LEGACY_LABEL).isEqualTo(RdfDbVocabulary.NS + "label");
            assertThat(RdfDbVocabulary.TIMESTEP_LABEL).isEqualTo(RdfDbVocabulary.NS + "timestepLabel");
            assertThat(RdfDbVocabulary.PREFIXES).contains("PREFIX pdb: <" + RdfDbVocabulary.NS + ">");
            assertThat(count(db, RdfDbVocabulary.TIMESTEP_LABEL)).isEqualTo(2);
            assertThat(count(db, LEGACY_LABEL)).isZero();

            // Turn the metadata graph into one an earlier release would have written
            db.sparql(S).update(RdfDbVocabulary.PREFIXES
                    + "DELETE { GRAPH <" + RdfDbNames.metaGraph(S) + "> { ?s pdb:timestepLabel ?o } }"
                    + " INSERT { GRAPH <" + RdfDbNames.metaGraph(S) + "> { ?s <" + LEGACY_LABEL + "> ?o } }"
                    + " WHERE { GRAPH <" + RdfDbNames.metaGraph(S)
                    + "> { ?s a pdb:Snapshot ; pdb:timestepLabel ?o } }");
            assertThat(count(db, RdfDbVocabulary.TIMESTEP_LABEL)).isZero();
            assertThat(count(db, LEGACY_LABEL)).isEqualTo(2);

            // Every listing still shows the same rendering
            assertThat(catalog.root().orElseThrow().timestepLabel()).isEqualTo(BASE_LABEL);
            assertThat(catalog.info(v11.iri()).orElseThrow().timestepLabel()).isEqualTo(BASE_LABEL);
            assertThat(catalog.find(SnapshotRef.of(S, "1.1")).orElseThrow().timestepLabel()).isEqualTo(BASE_LABEL);
            assertThat(catalog.snapshots()).allSatisfy(info ->
                    assertThat(info.timestepLabel()).isEqualTo(BASE_LABEL));
            assertThat(catalog.timesteps()).singleElement()
                    .satisfies(row -> assertThat(row.label()).isEqualTo(BASE_LABEL));
            catalog.verify();

            // A node carrying both shows the new term: a store half-migrated by someone else must not flip
            // between the two depending on the order the rows come back in
            db.sparql(S).update(RdfDbVocabulary.PREFIXES + "INSERT DATA { GRAPH <" + RdfDbNames.metaGraph(S)
                    + "> { <" + v11.iri() + "> pdb:timestepLabel \"99:99\" } }");
            assertThat(count(db, RdfDbVocabulary.TIMESTEP_LABEL)).isEqualTo(1);
            assertThat(count(db, LEGACY_LABEL)).isEqualTo(2);
            assertThat(catalog.info(v11.iri()).orElseThrow().timestepLabel()).isEqualTo("99:99");
            assertThat(catalog.root().orElseThrow().timestepLabel()).isEqualTo(BASE_LABEL);
            db.sparql(S).update(RdfDbVocabulary.PREFIXES + "DELETE WHERE { GRAPH <" + RdfDbNames.metaGraph(S)
                    + "> { ?s pdb:timestepLabel ?o } }");
            assertThat(count(db, RdfDbVocabulary.TIMESTEP_LABEL)).isZero();

            // A node carrying neither term shows nothing rather than guessing: the timestep is the key, and a
            // caller with nothing to show falls back to it
            db.sparql(S).update(RdfDbVocabulary.PREFIXES + "DELETE WHERE { GRAPH <" + RdfDbNames.metaGraph(S)
                    + "> { ?s <" + LEGACY_LABEL + "> ?o } }");
            assertThat(count(db, LEGACY_LABEL)).isZero();
            assertThat(catalog.info(v11.iri()).orElseThrow().timestepLabel()).isEmpty();
        }
    }
}
