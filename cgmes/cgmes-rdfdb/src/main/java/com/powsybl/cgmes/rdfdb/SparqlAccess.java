/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Typed SPARQL on the graphs of one scenario, without handing an RDF4J repository to a caller.
 *
 * <p>The versioning layer needs SPARQL that the CGMES triple store abstraction does not offer: aggregates, property
 * paths, guarded updates, values that are booleans and integers rather than strings. This is the whole of that
 * seam &mdash; three methods and a value factory &mdash; so that the repository, its lifecycle and the difference
 * between a server and the in-process backend stay inside {@link RdfDbConnection}.</p>
 *
 * <p>Query text is sent <strong>verbatim</strong>: no prefix is injected, no graph name is rewritten. Every query
 * of this package therefore carries its own {@code PREFIX} block ({@link RdfDbVocabulary#PREFIXES}) and names its
 * graphs by full IRI, which is also what makes them readable in a server log.</p>
 *
 * <p>Reads never create anything: asking a scenario that holds nothing answers empty rather than bringing that
 * scenario into existence.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class SparqlAccess {

    private final RdfDbConnection connection;
    private final String scenario;

    SparqlAccess(RdfDbConnection connection, String scenario) {
        this.connection = Objects.requireNonNull(connection);
        this.scenario = RdfDbNames.checkScenario(scenario);
    }

    /**
     * @return the scenario these queries run on
     */
    public String scenario() {
        return scenario;
    }

    /**
     * Evaluate a SELECT query.
     *
     * @param sparql the query text, sent as it is
     * @return one map per solution, keyed by binding name. An unbound variable is absent from its map rather than
     *         mapped to {@code null}, so {@code containsKey} and {@code get} agree
     */
    public List<Map<String, Value>> select(String sparql) {
        Objects.requireNonNull(sparql);
        Repository repository = connection.repository(scenario, false);
        if (repository == null) {
            return List.of();
        }
        List<Map<String, Value>> rows = new ArrayList<>();
        try (RepositoryConnection conn = repository.getConnection();
             TupleQueryResult result = conn.prepareTupleQuery(sparql).evaluate()) {
            while (result.hasNext()) {
                BindingSet bindings = result.next();
                Map<String, Value> row = new LinkedHashMap<>();
                bindings.forEach(binding -> row.put(binding.getName(), binding.getValue()));
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Evaluate an ASK query.
     *
     * @param sparql the query text, sent as it is
     * @return the answer, and {@code false} when the scenario does not exist at all
     */
    public boolean ask(String sparql) {
        Objects.requireNonNull(sparql);
        Repository repository = connection.repository(scenario, false);
        if (repository == null) {
            return false;
        }
        try (RepositoryConnection conn = repository.getConnection()) {
            return conn.prepareBooleanQuery(sparql).evaluate();
        }
    }

    /**
     * Execute a SPARQL UPDATE request.
     *
     * <p>One request is one transaction on both backends &mdash; Fuseki opens one per request, RDF4J one per
     * {@code Update.execute} &mdash; which is what lets a guarded write of this package be atomic without a
     * protocol for transactions across requests.</p>
     *
     * @param sparql the update text, sent as it is
     */
    public void update(String sparql) {
        Objects.requireNonNull(sparql);
        try (RepositoryConnection conn = connection.repository(scenario, true).getConnection()) {
            conn.prepareUpdate(sparql).execute();
        }
    }

    /**
     * @return a value factory of the underlying repository, for building the {@code IRI} and literal objects a
     *         caller wants to compare query results with
     */
    public ValueFactory valueFactory() {
        return connection.repository(scenario, true).getValueFactory();
    }
}
