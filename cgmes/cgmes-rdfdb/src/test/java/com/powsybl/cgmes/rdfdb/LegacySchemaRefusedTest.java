/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static com.powsybl.cgmes.rdfdb.Backends.BE;
import static com.powsybl.cgmes.rdfdb.Backends.microGridBe;
import static com.powsybl.cgmes.rdfdb.Backends.params;
import static com.powsybl.cgmes.rdfdb.Backends.ref;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A store of the earlier {@code (scenario, timestep, version)} addressing is refused with a message, never read and
 * never migrated, and clearing the scenario is the way out.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class LegacySchemaRefusedTest {

    private static final String S = "legacy";
    private static final String NS = RdfDbVocabulary.NS;

    /** What the earlier schema wrote first: the per-scenario catalogue node with its base timestep and offset. */
    private static final String CATALOG_NODE = "<" + RdfDbNames.BASE + S + "/catalog> a <" + NS + "Catalog> ; <"
            + NS + "baseTimestep> \"2014-06-01T10:30:00Z\" ; <" + NS + "baseOffset> \"Z\" . ";

    private static RdfDbConnection legacyStore(String backend, String triples) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "legacy-schema"));
        db.clear(S);
        db.sparql(S).update("INSERT DATA { GRAPH <" + RdfDbNames.metaGraph(S) + "> { " + triples + " } }");
        return db;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void everyEntryPointRefusesAnEarlierStoreAndClearIsTheWayOut(String backend) {
        try (RdfDbConnection db = legacyStore(backend, CATALOG_NODE)) {
            String expected = "scenario 'legacy' was written by the (scenario, timestep, version) schema of an"
                    + " earlier release (a pdb:Catalog node); this release reads only stores of schema 3";
            assertThatThrownBy(() -> db.snapshots(S).snapshots())
                    .isInstanceOf(RdfDbException.class).hasMessageContaining(expected).hasMessageContaining("re-ingest");
            assertThatThrownBy(() -> RdfDbNetworkLoader.load(db, ref(S, 1), null, params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class).hasMessageContaining(expected);
            assertThatThrownBy(() -> RdfDbNetworkLoader.update(Network.read(microGridBe(), params()), db, ref(S, 1),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class).hasMessageContaining(expected);
            assertThatThrownBy(() -> db.snapshots(S).putDiff(new DifferenceModelSet(List.of()), ref(S, 2)))
                    .isInstanceOf(RdfDbException.class).hasMessageContaining(expected);
            assertThatThrownBy(() -> db.snapshots(S).putFull(microGridBe(), null, ref(S, 1), null, params(),
                    ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class).hasMessageContaining(expected);

            db.clear(S);

            SnapshotInfo root = db.snapshots(S).putFull(microGridBe(), null, ref(S, 1), null, params(),
                    ReportNode.NO_OP);
            assertThat(db.snapshots(S).snapshots()).containsExactly(root);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aSnapshotWithoutTheSchemaMarkerIsRefused(String backend) {
        String snapshot = "<" + RdfDbNames.snapshot(S, BE, Backends.BASE, 1) + "> a <" + NS + "Snapshot> ; <" + NS
                + "modellingAuthority> \"" + BE + "\" . ";
        try (RdfDbConnection db = legacyStore(backend, snapshot)) {
            assertThatThrownBy(() -> db.snapshots(S).find(SnapshotRef.latest(S, BE)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("(snapshots without a pdb:schema marker)")
                    .hasMessageContaining("re-ingest");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aSnapshotKeyedByTimestepIsRefused(String backend) {
        String snapshot = "<" + RdfDbNames.BASE + S + "/snapshot/x/1.0> a <" + NS + "Snapshot> ; <" + NS
                + "timestep> \"2014-06-01T10:30:00Z\" ; <" + NS + "version> \"1.0\" . ";
        try (RdfDbConnection db = legacyStore(backend, snapshot)) {
            assertThatThrownBy(() -> db.snapshots(S).snapshots())
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("(snapshot nodes keyed by pdb:timestep)");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aStoreOfAnotherSchemaNumberIsRefused(String backend) {
        String marker = "<" + RdfDbNames.schemaNode(S) + "> <" + NS + "schema> 4 . ";
        try (RdfDbConnection db = legacyStore(backend, marker)) {
            assertThatThrownBy(() -> db.snapshots(S).snapshots())
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("carries pdb:schema 4")
                    .hasMessageContaining("reads only stores of schema 3");
        }
    }
}
