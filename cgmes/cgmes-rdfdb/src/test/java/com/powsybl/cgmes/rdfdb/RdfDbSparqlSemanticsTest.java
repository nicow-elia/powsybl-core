/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import org.eclipse.rdf4j.model.Value;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The four SPARQL behaviours the versioning layer is built on, asserted on both backends.
 *
 * <p>Everything this work package does rests on promises the SPARQL specification makes but implementations are
 * free to make in different ways: whether a guarded insert really inserts once and only once, whether a failed
 * operation of a multi-operation request leaves the earlier ones behind, whether {@code VALUES ?g} plus
 * {@code GRAPH ?g} reads several graphs in one query, and whether a zero-or-more property path includes its own
 * starting point. If one of them were false on a backend, the design of the write path would be wrong rather than
 * merely slow &mdash; so it is asserted here rather than assumed, on the embedded server and on the in-process
 * store alike.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbSparqlSemanticsTest {

    private static final String SCENARIO = "semantics";
    private static final String G = RdfDbNames.diffGraphPrefix(SCENARIO) + "data";
    private static final String M = RdfDbNames.metaGraph(SCENARIO);
    private static final String EX = "http://example.org/";

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> backends() {
        return Backends.backends();
    }

    private static RdfDbConnection open(String backend) {
        return RdfDbConnection.open(Backends.database(backend, "semantics"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void guardedInsertRunsExactlyOnceAndNotAtAllWhenTheGuardFails(String backend) {
        try (RdfDbConnection db = open(backend)) {
            db.clear(SCENARIO);
            SparqlAccess sparql = db.sparql(SCENARIO);

            String insert = "INSERT { GRAPH <" + G + "> { <" + EX + "x> <" + EX + "p> \"1\" } }"
                    + " WHERE { FILTER NOT EXISTS { GRAPH <" + M + "> { <" + EX + "x> ?p ?o } } }";
            sparql.update(insert);
            assertThat(countOf(sparql, G)).as("a guard that holds inserts once").isEqualTo(1);

            // Running it again must not duplicate: the same triple is already there, and RDF is a set
            sparql.update(insert);
            assertThat(countOf(sparql, G)).isEqualTo(1);

            sparql.update("INSERT DATA { GRAPH <" + M + "> { <" + EX + "x> <" + EX + "kind> \"diff\" } }");
            sparql.update("INSERT { GRAPH <" + G + "> { <" + EX + "y> <" + EX + "p> \"2\" } }"
                    + " WHERE { FILTER NOT EXISTS { GRAPH <" + M + "> { <" + EX + "x> ?p ?o } } }");
            assertThat(countOf(sparql, G)).as("a guard that fails inserts nothing").isEqualTo(1);

            db.clear(SCENARIO);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aFailingOperationRollsBackTheEarlierOnesOfTheSameRequest(String backend) {
        try (RdfDbConnection db = open(backend)) {
            db.clear(SCENARIO);
            SparqlAccess sparql = db.sparql(SCENARIO);

            // Two operations, the second of which cannot work: nothing listens on that port
            assertThatThrownBy(() -> sparql.update(
                    "INSERT DATA { GRAPH <" + G + "> { <" + EX + "a> <" + EX + "p> \"1\" } } ; "
                            + "LOAD <http://127.0.0.1:1/nothing>"))
                    .isInstanceOf(RuntimeException.class);

            assertThat(countOf(sparql, G))
                    .as("one SPARQL UPDATE request is one transaction on this backend")
                    .isZero();

            db.clear(SCENARIO);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void valuesOverGraphsReadsSeveralGraphsInOneQuery(String backend) {
        try (RdfDbConnection db = open(backend)) {
            db.clear(SCENARIO);
            SparqlAccess sparql = db.sparql(SCENARIO);
            sparql.update("INSERT DATA { GRAPH <" + G + "/1> { <" + EX + "a> <" + EX + "p> \"1\" } "
                    + " GRAPH <" + G + "/2> { <" + EX + "b> <" + EX + "p> \"2\" } }");

            List<Map<String, Value>> rows = sparql.select("SELECT ?g ?s ?p ?o WHERE { VALUES ?g { <" + G + "/1> <"
                    + G + "/2> } GRAPH ?g { ?s ?p ?o } }");
            assertThat(rows).hasSize(2);
            assertThat(rows.stream().map(row -> row.get("g").stringValue()).sorted().toList())
                    .containsExactly(G + "/1", G + "/2");

            db.clear(SCENARIO);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aZeroOrMorePathIncludesItsStartingPoint(String backend) {
        try (RdfDbConnection db = open(backend)) {
            db.clear(SCENARIO);
            SparqlAccess sparql = db.sparql(SCENARIO);
            sparql.update("INSERT DATA { GRAPH <" + M + "> { <" + EX + "c> <" + EX + "supersedes> <" + EX + "b> . "
                    + "<" + EX + "b> <" + EX + "supersedes> <" + EX + "a> } }");

            List<Map<String, Value>> rows = sparql.select("SELECT ?m WHERE { GRAPH <" + M + "> { <" + EX
                    + "c> <" + EX + "supersedes>* ?m } }");
            assertThat(rows.stream().map(row -> row.get("m").stringValue()).sorted().toList())
                    .containsExactly(EX + "a", EX + "b", EX + "c");

            db.clear(SCENARIO);
        }
    }

    /**
     * Probe (a) of the variant work package: a sub-select inside one branch of a {@code UNION}, combined with a
     * {@code pdb:parent*} walk from starts that several other branches bind.
     *
     * <p>That is the shape of the multi-side chain query {@code VersionGraph.chains} sends: one branch per side
     * binds a start, one branch returns the membership rows of every side, and a second branch returns the detail
     * rows of each reached snapshot exactly once through a {@code SELECT DISTINCT} sub-select. If a backend
     * evaluated the sub-select before the {@code BIND}s of the sibling branches &mdash; the specification says it
     * must not, the bindings of a sub-select are not visible outside it and the starts are bound inside the same
     * group &mdash; the detail rows would be missing and the query would have to fall back to one part per side.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aSubSelectInAUnionBranchSeesThePathWalkOfItsOwnBranch(String backend) {
        try (RdfDbConnection db = open(backend)) {
            db.clear(SCENARIO);
            SparqlAccess sparql = db.sparql(SCENARIO);
            // c -> b -> a, and a second chain d -> b
            sparql.update("INSERT DATA { GRAPH <" + M + "> {"
                    + " <" + EX + "c> <" + EX + "parent> <" + EX + "b> ; <" + EX + "n> \"c\" ."
                    + " <" + EX + "d> <" + EX + "parent> <" + EX + "b> ; <" + EX + "n> \"d\" ."
                    + " <" + EX + "b> <" + EX + "parent> <" + EX + "a> ; <" + EX + "n> \"b\" ."
                    + " <" + EX + "a> <" + EX + "n> \"a\" } }");

            String starts = " { BIND(\"A\" AS ?side) BIND(<" + EX + "c> AS ?start) }"
                    + " UNION { BIND(\"B\" AS ?side) BIND(<" + EX + "d> AS ?start) } ";
            String query = "SELECT ?side ?snap ?n WHERE { GRAPH <" + M + "> { "
                    + " {" + starts + " ?start <" + EX + "parent>* ?snap }"
                    + " UNION"
                    + " { { SELECT DISTINCT ?snap WHERE { " + starts
                    + " ?start <" + EX + "parent>* ?snap } } ?snap <" + EX + "n> ?n }"
                    + " } }";
            List<Map<String, Value>> rows = sparql.select(query);

            // Membership rows: c,b,a for side A and d,b,a for side B
            List<String> membership = rows.stream().filter(row -> row.get("side") != null)
                    .map(row -> row.get("side").stringValue() + ":" + row.get("snap").stringValue())
                    .sorted().distinct().toList();
            assertThat(membership).as("both sides walk their own chain")
                    .containsExactly("A:" + EX + "a", "A:" + EX + "b", "A:" + EX + "c",
                            "B:" + EX + "a", "B:" + EX + "b", "B:" + EX + "d");
            // Detail rows: every reached node once, with no side bound
            List<String> details = rows.stream().filter(row -> row.get("side") == null && row.get("n") != null)
                    .map(row -> row.get("n").stringValue()).sorted().toList();
            assertThat(details).as("the sub-select branch returns each reached snapshot exactly once")
                    .containsExactly("a", "b", "c", "d");

            db.clear(SCENARIO);
        }
    }

    /**
     * Probe (b) of the variant work package: a hundred {@code UNION} branches in one query.
     *
     * <p>A day of 96 timesteps loaded in one request binds one start per timestep, and some engines have a limit
     * on the size of a {@code UNION} tree or turn one into a quadratic plan. This asserts that the request is
     * answered at all, and that every branch contributes its rows.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aHundredUnionBranchesAreOneRequest(String backend) {
        try (RdfDbConnection db = open(backend)) {
            db.clear(SCENARIO);
            SparqlAccess sparql = db.sparql(SCENARIO);
            StringBuilder data = new StringBuilder("INSERT DATA { GRAPH <" + M + "> {");
            for (int i = 0; i < 100; i++) {
                data.append(" <").append(EX).append("s").append(i).append("> <").append(EX).append("p> \"")
                        .append(i).append("\" .");
            }
            sparql.update(data.append(" } }").toString());

            StringBuilder query = new StringBuilder("SELECT ?side ?s ?o WHERE { GRAPH <" + M + "> { ");
            for (int i = 0; i < 100; i++) {
                query.append(i == 0 ? " { " : " UNION { ")
                        .append("BIND(\"B").append(i).append("\" AS ?side) BIND(<").append(EX).append("s")
                        .append(i).append("> AS ?s) } ");
            }
            query.append(" ?s <").append(EX).append("p> ?o } }");
            List<Map<String, Value>> rows = sparql.select(query.toString());

            assertThat(rows).as("one row per branch").hasSize(100);
            assertThat(rows.stream().map(row -> row.get("side").stringValue()).distinct().count()).isEqualTo(100L);

            db.clear(SCENARIO);
        }
    }

    private static long countOf(SparqlAccess sparql, String graph) {
        List<Map<String, Value>> rows =
                sparql.select("SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + graph + "> { ?s ?p ?o } }");
        return rows.isEmpty() ? 0 : Long.parseLong(rows.get(0).get("n").stringValue());
    }
}
