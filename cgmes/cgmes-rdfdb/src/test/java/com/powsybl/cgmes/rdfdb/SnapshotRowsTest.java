/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The decoding of a snapshot node: typed timestamp and version, the modelling authority, and the refusal of a node
 * of the earlier addressing schema.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class SnapshotRowsTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String S = "rows";
    private static final String MAS = "http://elia.be/CGMES/2.4.15";
    private static final Instant T = Instant.parse("2014-06-01T10:30:00Z");
    private static final String IRI = RdfDbNames.snapshot(S, MAS, T, "2");

    private static Map<String, Value> row(String predicate, Value object) {
        return Map.of("s", VF.createIRI(IRI), "p", VF.createIRI(predicate), "o", object);
    }

    private static List<Map<String, Value>> node() {
        List<Map<String, Value>> rows = new ArrayList<>();
        rows.add(row(RdfDbVocabulary.RDF_TYPE, VF.createIRI(RdfDbVocabulary.SNAPSHOT_CLASS)));
        rows.add(row(RdfDbVocabulary.MODELLING_AUTHORITY, VF.createLiteral(MAS)));
        rows.add(row(RdfDbVocabulary.TIMESTAMP, VF.createLiteral(T.toString(), XSD.DATETIME)));
        rows.add(row(RdfDbVocabulary.VERSION, VF.createLiteral("2", XSD.INTEGER)));
        rows.add(row(RdfDbVocabulary.KIND, VF.createIRI(RdfDbVocabulary.DIFF)));
        rows.add(row(RdfDbVocabulary.DEPTH, VF.createLiteral("1", XSD.INTEGER)));
        rows.add(Map.of("s", VF.createIRI(IRI), "p", VF.createIRI(RdfDbVocabulary.STATE),
                "o", VF.createIRI("urn:uuid:ssh-2"), "sub", VF.createLiteral("SSH")));
        rows.add(Map.of("s", VF.createIRI(IRI), "p", VF.createIRI(RdfDbVocabulary.STATE),
                "o", VF.createIRI("urn:uuid:eq-1"), "sub", VF.createLiteral("EQ")));
        return rows;
    }

    @Test
    void aSnapshotNodeDecodesIntoTheFourKeysAndItsProfiles() {
        SnapshotInfo info = SnapshotRows.group(S, node(), "s").get(IRI);
        assertThat(info).isNotNull();
        assertThat(info.modellingAuthority()).isEqualTo(MAS);
        assertThat(info.timestamp()).isEqualTo(T);
        assertThat(info.version()).isEqualTo("2");
        assertThat(info.ref()).isEqualTo(SnapshotRef.of(S, MAS, T, "2"));
        assertThat(info.profiles()).isEqualTo(Set.of(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS));
        assertThat(info.edge()).isEqualTo(SnapshotInfo.EdgeKind.NONE);
    }

    @Test
    void aNodeWithoutTheKeysIsNotASnapshot() {
        List<Map<String, Value>> rows = node();
        rows.removeIf(row -> row.get("p").stringValue().equals(RdfDbVocabulary.MODELLING_AUTHORITY));
        assertThat(SnapshotRows.group(S, rows, "s")).isEmpty();
    }

    @Test
    void aNodeOfTheEarlierAddressingSchemaIsRefusedNotMisread() {
        List<Map<String, Value>> rows = node();
        rows.add(row(RdfDbVocabulary.NS + "timestep", VF.createLiteral("2014-06-01T10:30:00Z")));
        assertThatThrownBy(() -> SnapshotRows.group(S, rows, "s"))
                .isInstanceOf(RdfDbException.class)
                .hasMessageContaining("scenario 'rows' was written by the (scenario, timestep, version) schema")
                .hasMessageContaining("re-ingest");
    }
}
