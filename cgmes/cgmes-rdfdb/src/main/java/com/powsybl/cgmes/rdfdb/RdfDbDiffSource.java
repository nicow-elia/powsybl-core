/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.triplestore.impl.rdf4j.sparql.GraphStoreClient;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Reads stored difference models back out of a scenario.
 *
 * <p>The inverse of {@link RdfDbDifferenceSink}, and its counterpart in one more way: it fetches <em>every</em>
 * graph a plan needs in one request. An update that walks ten differences of two profiles is twenty named graphs,
 * and twenty requests would cost more than the statements they carry; one {@code VALUES ?g … GRAPH ?g} query
 * returns all of them with the graph they came from bound, and the grouping happens here. That holds while the
 * differences are small. When they carry more than {@link #GSP_MIN_STATEMENTS_PER_GRAPH} statements per graph on
 * average and the server speaks the Graph Store Protocol, each graph is read by its own N-Triples GET instead, in
 * parallel: two requests per difference, but the statements are then the cost, not the requests.</p>
 *
 * <p>The order statements come back in is not defined by SPARQL, so the statements of a fetched model are sorted
 * by subject and property. Nothing downstream depends on the order &mdash; composing a chain is order-insensitive
 * per key and the round-trip assertions compare sets &mdash; but a stable order makes two fetches of the same
 * model produce the same object, which is what a test and a cache both want.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class RdfDbDiffSource {

    /**
     * The mean number of statements per graph from which the graphs are fetched one by one through the Graph Store
     * Protocol rather than by the one {@code SELECT}.
     *
     * <p>A row of the {@code SELECT} costs ~12 &micro;s on a SPARQL/XML result (server serialisation plus client
     * parse), a statement of an N-Triples GET ~1-2 &micro;s, and a GET ~5 ms of round trip, spread over the fetch
     * pool. Above a few hundred statements per graph the GETs win (a day of 95 differences of 1 866 statements on
     * Fuseki: 4.3 s by the {@code SELECT}); below it the one request does, and the number of requests of an
     * update of small differences stays independent of the number of differences.</p>
     */
    static final int GSP_MIN_STATEMENTS_PER_GRAPH = 500;

    private RdfDbDiffSource() {
    }

    /**
     * Fetch one stored difference.
     *
     * @param connection the open connection
     * @param diff       the node of the difference, from a {@link ModelCatalog}
     * @return the difference model, with the header the node carries
     */
    public static DifferenceModel fetch(RdfDbConnection connection, StoredModel diff) {
        return fetchAll(connection, List.of(diff)).get(0);
    }

    /**
     * Fetch several stored differences: in one request, or one per graph when they are large (see the class).
     *
     * @param connection the open connection
     * @param diffs      the nodes of the differences, all of the same scenario
     * @return the difference models, in the order of the given nodes
     */
    public static List<DifferenceModel> fetchAll(RdfDbConnection connection, List<StoredModel> diffs) {
        return fetchAll(connection, diffs, GSP_MIN_STATEMENTS_PER_GRAPH);
    }

    static List<DifferenceModel> fetchAll(RdfDbConnection connection, List<StoredModel> diffs,
                                          int minStatementsPerGraph) {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(diffs);
        if (diffs.isEmpty()) {
            return List.of();
        }
        String scenario = diffs.get(0).scenario();
        StringBuilder values = new StringBuilder();
        for (StoredModel diff : diffs) {
            if (diff.kind() != StoredModel.Kind.DIFF) {
                throw new RdfDbException("Model " + diff.id() + " of scenario '" + diff.scenario()
                        + "' is a full model, not a difference: it has no forward and reverse graphs to fetch");
            }
            values.append(' ').append(SparqlText.iri(diff.forwardGraph()))
                    .append(' ').append(SparqlText.iri(diff.reverseGraph()));
        }
        GraphStoreClient client = connection.graphStoreClient();
        long statements = diffs.stream().mapToLong(diff -> Math.max(0, diff.tripleCount())).sum();
        if (client != null && client.supported() && statements >= (long) minStatementsPerGraph * 2 * diffs.size()) {
            return toModels(diffs, fetchByGraph(connection, client, diffs));
        }
        Map<String, List<Row>> byGraph = new LinkedHashMap<>();
        List<Map<String, Value>> rows = connection.sparql(scenario)
                .select("SELECT ?g ?s ?p ?o WHERE { VALUES ?g {" + values + " } GRAPH ?g { ?s ?p ?o } }");
        for (Map<String, Value> row : rows) {
            Value g = row.get("g");
            Value s = row.get("s");
            Value p = row.get("p");
            Value o = row.get("o");
            if (g == null || s == null || p == null || o == null) {
                continue;
            }
            byGraph.computeIfAbsent(g.stringValue(), k -> new ArrayList<>()).add(new Row(s, p, o));
        }
        return toModels(diffs, byGraph);
    }

    private static List<DifferenceModel> toModels(List<StoredModel> diffs, Map<String, List<Row>> byGraph) {
        List<DifferenceModel> models = new ArrayList<>();
        for (StoredModel diff : diffs) {
            models.add(new DifferenceModel(diff.toHeader(),
                    decode(byGraph.get(diff.forwardGraph()), diff),
                    decode(byGraph.get(diff.reverseGraph()), diff),
                    List.of()));
        }
        return models;
    }

    /**
     * Fold the chains of several profiles into one difference model set.
     *
     * @param chains  the differences to apply per profile, in the order they are applied
     * @param headers the header the composed model of each profile is to carry
     * @return the composed set, profiles whose chain is empty left out
     */
    public static DifferenceModelSet compose(Map<CgmesSubset, List<DifferenceModel>> chains,
                                             Map<CgmesSubset, DifferenceModelHeader> headers) {
        Objects.requireNonNull(chains);
        Objects.requireNonNull(headers);
        List<DifferenceModel> composed = new ArrayList<>();
        chains.forEach((subset, chain) -> {
            if (chain.isEmpty()) {
                return;
            }
            DifferenceModelHeader header = headers.get(subset);
            if (header == null) {
                throw new RdfDbException("No header for the composed " + subset.getIdentifier() + " difference");
            }
            composed.add(chain.size() == 1 && chain.get(0).header().equals(header)
                    ? chain.get(0) : DifferenceModel.compose(chain, header));
        });
        return new DifferenceModelSet(composed);
    }

    private record Row(Value subject, Value predicate, Value object) {
    }

    /** Every graph by its own Graph Store Protocol GET, on a pool as wide as the database's fetch parallelism. */
    private static Map<String, List<Row>> fetchByGraph(RdfDbConnection connection, GraphStoreClient client,
                                                       List<StoredModel> diffs) {
        List<String> graphs = diffs.stream()
                .flatMap(diff -> Stream.of(diff.forwardGraph(), diff.reverseGraph())).distinct().toList();
        int threads = Math.min(Math.max(1, connection.database().fetchParallelism()), graphs.size());
        AtomicInteger counter = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "rdfdb-diff-fetch-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<List<Row>>> futures = new ArrayList<>();
            graphs.forEach(graph -> futures.add(pool.submit(() -> getGraph(client, graph))));
            Map<String, List<Row>> byGraph = new LinkedHashMap<>();
            for (int i = 0; i < graphs.size(); i++) {
                byGraph.put(graphs.get(i), futures.get(i).get());
            }
            return byGraph;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RdfDbException("Interrupted while fetching difference graphs", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RdfDbException("Fetching a difference graph failed: " + e.getCause().getMessage(), e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Row> getGraph(GraphStoreClient client, String graph) {
        List<Row> rows = new ArrayList<>();
        // A graph the server does not know (404) is an empty one, as on the SELECT route: a quad store keeps no
        // empty named graph, and a difference with nothing to undo has an empty reverse graph
        client.get(graph, new AbstractRDFHandler() {
            @Override
            public void handleStatement(Statement st) {
                rows.add(new Row(st.getSubject(), st.getPredicate(), st.getObject()));
            }
        });
        return rows;
    }

    private static List<CgmesStatement> decode(List<Row> rows, StoredModel diff) {
        if (rows == null) {
            return List.of();
        }
        List<CgmesStatement> statements = new ArrayList<>(rows.size());
        rows.forEach(row -> statements.add(StatementCodec.decode(row.subject(), row.predicate(), row.object(),
                diff.subjectBase(), diff.cimNamespace())));
        statements.sort(Comparator.comparing(CgmesStatement::subjectId).thenComparing(CgmesStatement::property));
        return statements;
    }
}
