/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;

import static com.powsybl.cgmes.rdfdb.Backends.microGridBe;
import static com.powsybl.cgmes.rdfdb.Backends.params;
import static com.powsybl.cgmes.rdfdb.Backends.ref;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A reader trusts the stored verdicts of a difference only when they were reached by a capability table it agrees
 * with: its own, or an older one. A difference stamped by a newer writer is checked against the reader's table on
 * its fetched statements before anything is applied.
 *
 * <p>The stores here are written by this build, so a "newer writer" is made by rewriting {@code pdb:capabilities}
 * on the difference node, and a statement the reader cannot apply is injected into its forward graph while
 * {@code pdb:fastPredicatesOnly} stays {@code true} &mdash; what a newer table that learned a property would have
 * written.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CapabilityVersionTest {

    private static final String S = "2016-01-01";
    private static final String NEWER = "ffffffffffff/99.0.0";
    private static final String OLDER = "000000000000/0.1.0";
    private static final String OUTSIDE_THE_TABLE = "IdentifiedObject.description";
    private static final Set<String> IDENTITY = Set.of("cgmesMetadataModels", "rdfDbProvenance");

    /** A root and a second version one load step away. */
    private static RdfDbConnection twoVersions(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "capability-version"));
        db.clear(S);
        db.snapshots(S).putFull(microGridBe(), null, ref(S, 1), null, params(), ReportNode.NO_OP);
        Network sender = load(db, 1);
        Changes.export(sender, db, ref(S, 2), n -> Changes.moveLoad(n, 11.0));
        return db;
    }

    private static Network load(RdfDbConnection db, int version) {
        return RdfDbNetworkLoader.load(db, ref(S, version), null, params(), ReportNode.NO_OP);
    }

    private static UpdateResult update(Network network, RdfDbConnection db) {
        return RdfDbNetworkLoader.update(network, db, ref(S, 2), new RdfDbUpdateOptions(), params(),
                ReportNode.NO_OP);
    }

    /** Rewrite the capability version of every difference of the scenario. */
    private static void stamp(RdfDbConnection db, String capabilities) {
        String meta = "<" + RdfDbNames.metaGraph(S) + ">";
        db.sparql(S).update(RdfDbVocabulary.PREFIXES + "DELETE { GRAPH " + meta + " { ?m pdb:capabilities ?c } }"
                + " INSERT { GRAPH " + meta + " { ?m pdb:capabilities \"" + capabilities + "\" } }"
                + " WHERE { GRAPH " + meta + " { ?m pdb:capabilities ?c } }");
        assertThat(Backends.count(db, S, RdfDbNames.metaGraph(S), "?m pdb:capabilities \"" + capabilities + "\""))
                .as("the rewrite has to reach the nodes").isPositive();
    }

    /** State a property no update query reads on every subject of every forward graph; the flags stay as they are. */
    private static void injectAStatementOutsideTheTable(RdfDbConnection db) {
        String meta = "<" + RdfDbNames.metaGraph(S) + ">";
        db.sparql(S).update(RdfDbVocabulary.PREFIXES + "INSERT { GRAPH ?fwd { ?s ?property \"injected\" } }"
                + " WHERE { GRAPH " + meta + " { ?m pdb:forwardGraph ?fwd ; pdb:cimNamespace ?cim ;"
                + " pdb:fastPredicatesOnly true }"
                + " BIND(IRI(CONCAT(?cim, \"" + OUTSIDE_THE_TABLE + "\")) AS ?property)"
                + " GRAPH ?fwd { ?s ?p ?o } }");
        assertThat(Backends.count(db, S, RdfDbNames.metaGraph(S), "?m pdb:fastPredicatesOnly true")).isPositive();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aDifferenceOfTheReadersOwnVersionIsTrusted(String backend) {
        try (RdfDbConnection db = twoVersions(backend)) {
            Network network = load(db, 1);
            UpdatePlan plan = db.versionGraph(S).plan(network, ref(S, 2), new RdfDbUpdateOptions());
            assertThat(plan.steps()).isNotEmpty().noneMatch(UpdatePlan.DiffStep::recheck);

            int before = RdfDbNetworkLoader.recheckedDifferences();
            assertThat(update(network, db).route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(RdfDbNetworkLoader.recheckedDifferences()).isEqualTo(before);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aNewerWritersDifferenceIsRecheckedAndAppliedWhenTheReaderCanApplyIt(String backend) {
        try (RdfDbConnection db = twoVersions(backend)) {
            stamp(db, NEWER);
            Network network = load(db, 1);
            UpdatePlan plan = db.versionGraph(S).plan(network, ref(S, 2), new RdfDbUpdateOptions());
            assertThat(plan.steps()).isNotEmpty().allMatch(UpdatePlan.DiffStep::recheck);

            int before = RdfDbNetworkLoader.recheckedDifferences();
            UpdateResult result = update(network, db);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(RdfDbNetworkLoader.recheckedDifferences() - before).isEqualTo(plan.steps().size());
            Networks.assertSameNetworkIgnoringStateVariables(load(db, 2), network, IDENTITY, Set.of(Changes.LOAD_ID));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aNewerWritersDifferenceTheReaderCannotApplyIsReloadedNamingBothVersions(String backend) {
        try (RdfDbConnection db = twoVersions(backend)) {
            stamp(db, NEWER);
            injectAStatementOutsideTheTable(db);
            Network network = load(db, 1);
            double p0 = network.getLoad(Changes.LOAD_ID).getP0();

            int before = RdfDbNetworkLoader.recheckedDifferences();
            UpdateResult result = update(network, db);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_RELOAD);
            assertThat(RdfDbNetworkLoader.recheckedDifferences() - before).isEqualTo(1);
            assertThat(String.join("\n", result.reasons()))
                    .contains("was written by capability version " + NEWER)
                    .contains("this reader (" + FastRouteCapabilities.version() + ")")
                    .contains(OUTSIDE_THE_TABLE);
            // Refused before anything was composed: the network handed in is untouched
            assertThat(network.getLoad(Changes.LOAD_ID).getP0()).isEqualTo(p0);
            assertThat(result.network()).isNotSameAs(network);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anOlderWritersDifferenceIsTrustedWithoutARecheck(String backend) {
        try (RdfDbConnection db = twoVersions(backend)) {
            stamp(db, OLDER);
            injectAStatementOutsideTheTable(db);
            Network network = load(db, 1);

            int before = RdfDbNetworkLoader.recheckedDifferences();
            UpdateResult result = update(network, db);

            // The planner took the flag as stored; the apply-time check of the composed difference answers instead
            assertThat(RdfDbNetworkLoader.recheckedDifferences()).isEqualTo(before);
            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_RELOAD);
            assertThat(String.join("\n", result.reasons()))
                    .contains(OUTSIDE_THE_TABLE)
                    .doesNotContain("capability version");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anotherTableOfTheSameCoreVersionIsRechecked(String backend) {
        try (RdfDbConnection db = twoVersions(backend)) {
            String reader = FastRouteCapabilities.version();
            String sibling = "000000000000" + reader.substring(reader.indexOf('/'));
            stamp(db, sibling);
            injectAStatementOutsideTheTable(db);
            Network network = load(db, 1);

            int before = RdfDbNetworkLoader.recheckedDifferences();
            UpdateResult result = update(network, db);

            assertThat(RdfDbNetworkLoader.recheckedDifferences() - before).isEqualTo(1);
            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_RELOAD);
            assertThat(String.join("\n", result.reasons())).contains("capability version " + sibling);
        }
    }

    @Test
    void coreVersionsAreOrderedNumericallyAndADevelopmentBuildBeforeItsRelease() {
        assertThat(StoredModel.isOlder("7.4.0", "7.5.0-SNAPSHOT")).isTrue();
        assertThat(StoredModel.isOlder("7.5.0-SNAPSHOT", "7.5.0")).isTrue();
        assertThat(StoredModel.isOlder("7.9.3", "7.10.0")).isTrue();
        assertThat(StoredModel.isOlder("6.99.99", "7.0.0")).isTrue();
        assertThat(StoredModel.isOlder("7.5.0", "7.5.0-SNAPSHOT")).isFalse();
        assertThat(StoredModel.isOlder("7.5.0-SNAPSHOT", "7.5.0-SNAPSHOT")).isFalse();
        assertThat(StoredModel.isOlder("7.5.0", "7.5.0")).isFalse();
        assertThat(StoredModel.isOlder("7.10.0", "7.9.0")).isFalse();
        assertThat(StoredModel.isOlder("unknown", "7.5.0")).isFalse();
        assertThat(StoredModel.isOlder("7.5.0", "unknown")).isFalse();
    }
}
