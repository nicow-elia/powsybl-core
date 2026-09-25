/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.triplestore.impl.rdf4j.TripleStoreRDF4J;
import com.powsybl.triplestore.impl.rdf4j.sparql.GraphStoreClient;
import com.powsybl.triplestore.impl.rdf4j.sparql.ScenarioGraphNames;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bulk transfer of the named graphs of a scenario into a local in-memory triple store.
 *
 * <p>This is the machine that has to beat reading the files, and the reason it can is that a file import has no
 * choice but to be sequential and to parse RDF/XML, while this one is neither:</p>
 * <ul>
 *   <li>Each graph is one HTTP request, answered as N-Triples &mdash; line-based, absolute IRIs, the cheapest
 *       parser RDF4J has &mdash; and the body is parsed while it is still arriving.</li>
 *   <li>Several graphs are fetched and parsed at once. A CGMES model is four to six files of very uneven size;
 *       the EQ transfer overlaps the parsing of SSH, TP and SV.</li>
 *   <li>Exactly one thread writes into the local store, because an RDF4J memory store has one writer anyway.
 *       What the parallelism buys is overlap between the network, the parsers and that writer, and the writer
 *       gets whole graphs in one {@code add} call inside a single transaction.</li>
 *   <li>The CIM namespace is picked up while parsing, so the load does not have to ask the database for it
 *       afterwards.</li>
 * </ul>
 *
 * <p>For the in-process backend there is no HTTP: statements are copied from the scenario's memory store, which
 * is the same pipeline with the transfer cost removed.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class GraphFetcher {

    /**
     * What one transfer cost.
     *
     * @param fetch      wall-clock time of the parallel fetch and parse
     * @param parse      parsing time summed over the fetch threads
     * @param store      time spent filling the local store
     * @param statements how many statements were transferred
     * @param graphs     how many graphs were transferred
     * @param cacheHits  how many graphs came from the cache
     * @param cimNamespace the CIM namespace seen in the data, or {@code null} if none was
     * @param perGraph   fetch-and-parse time per context name
     */
    public record FetchStatistics(Duration fetch, Duration parse, Duration store, long statements, int graphs,
                                  int cacheHits, String cimNamespace, Map<String, Duration> perGraph) {

        /**
         * @param fetch        see {@link #fetch()}
         * @param parse        see {@link #parse()}
         * @param store        see {@link #store()}
         * @param statements   see {@link #statements()}
         * @param graphs       see {@link #graphs()}
         * @param cacheHits    see {@link #cacheHits()}
         * @param cimNamespace see {@link #cimNamespace()}
         * @param perGraph     see {@link #perGraph()}
         */
        public FetchStatistics {
            perGraph = Map.copyOf(perGraph);
        }
    }

    private record FetchedGraph(String contextName, String remoteGraph, List<Statement> statements,
                                String cimNamespace, long parseNanos, long elapsedNanos, boolean fromCache) {
    }

    private final RdfDbConnection connection;
    private final String scenario;

    /**
     * A fetcher for one scenario of one database.
     *
     * @param connection the open connection
     * @param scenario   the scenario whose graphs are to be fetched
     */
    public GraphFetcher(RdfDbConnection connection, String scenario) {
        this.connection = Objects.requireNonNull(connection);
        this.scenario = ScenarioGraphNames.requireValidScenario(scenario);
    }

    /**
     * Transfer the given graphs of the scenario into a local store.
     *
     * @param local        the local store to fill. Its contexts get the plain context names, without the scenario
     * @param contextNames the context names to transfer
     * @return what the transfer cost
     */
    public FetchStatistics fetchInto(TripleStoreRDF4J local, Collection<String> contextNames) {
        Objects.requireNonNull(contextNames);
        Map<String, String> byName = new LinkedHashMap<>();
        // The graph IRI as the database addresses it, which is not the same on both backends: a server holds
        // every scenario in one dataset and puts the scenario in the graph name, the in-process backend gives
        // each scenario a store of its own and keeps the plain context name
        contextNames.forEach(name -> byName.put(name, connection.graphIri(scenario, name)));
        return fetchInto(local, byName);
    }

    /**
     * Transfer named graphs into a local store, naming each one explicitly.
     *
     * <p>The overload above derives the database graph IRI of a context from the scenario prefix, which is the
     * only naming this work package has. A caller that keeps graphs under a different scheme &mdash; a version
     * of a model under an immutable IRI, a difference graph &mdash; passes the mapping instead, and the local
     * store still ends up with the plain {@code contexts:<file name>} the CGMES conversion expects.</p>
     *
     * @param local            the local store to fill. Its contexts get the local names, without any prefix
     * @param localToRemote    local context name to the IRI of the graph in the database, in fetch order
     * @return what the transfer cost
     */
    public FetchStatistics fetchInto(TripleStoreRDF4J local, Map<String, String> localToRemote) {
        Objects.requireNonNull(local);
        Map<String, String> graphs = new LinkedHashMap<>(Objects.requireNonNull(localToRemote));
        List<String> names = new ArrayList<>(graphs.keySet());
        names.sort(Comparator.naturalOrder());
        if (names.isEmpty()) {
            return new FetchStatistics(Duration.ZERO, Duration.ZERO, Duration.ZERO, 0, 0, 0, null, Map.of());
        }
        RdfDatabase database = connection.database();
        int threads = Math.min(Math.max(1, database.fetchParallelism()), names.size());
        AtomicInteger counter = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "rdfdb-fetch-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        long fetchStart = System.nanoTime();
        long parseNanos = 0;
        long storeNanos = 0;
        long statements = 0;
        int cacheHits = 0;
        String cimNamespace = null;
        Map<String, Duration> perGraph = new LinkedHashMap<>();
        try (RepositoryConnection writer = local.getRepository().getConnection()) {
            writer.setIsolationLevel(IsolationLevels.NONE);
            writer.begin();
            ExecutorCompletionService<FetchedGraph> completion = new ExecutorCompletionService<>(pool);
            for (String name : names) {
                Callable<FetchedGraph> task = () -> fetchOne(name, graphs.get(name));
                completion.submit(task);
            }
            for (int i = 0; i < names.size(); i++) {
                FetchedGraph graph = take(completion);
                parseNanos += graph.parseNanos();
                perGraph.put(graph.contextName(), Duration.ofNanos(graph.elapsedNanos()));
                statements += graph.statements().size();
                if (graph.fromCache()) {
                    cacheHits++;
                }
                if (cimNamespace == null) {
                    cimNamespace = graph.cimNamespace();
                }
                long storeStart = System.nanoTime();
                IRI context = writer.getValueFactory().createIRI(ScenarioGraphNames.CONTEXTS,
                        ScenarioGraphNames.localName(graph.contextName()));
                writer.add(graph.statements(), context);
                storeNanos += System.nanoTime() - storeStart;
            }
            long commitStart = System.nanoTime();
            writer.commit();
            storeNanos += System.nanoTime() - commitStart;
        } finally {
            pool.shutdownNow();
        }
        long elapsed = System.nanoTime() - fetchStart;
        Duration store = Duration.ofNanos(storeNanos);
        Duration fetch = Duration.ofNanos(elapsed).minus(store);
        if (fetch.isNegative()) {
            fetch = Duration.ZERO;
        }
        LOGGER.debug("Fetched {} graphs ({} statements) of scenario '{}': fetch {} ms, parse {} ms, store {} ms",
                names.size(), statements, scenario, fetch.toMillis(), parseNanos / 1_000_000, store.toMillis());
        return new FetchStatistics(fetch, Duration.ofNanos(parseNanos), store, statements, names.size(),
                cacheHits, cimNamespace, perGraph);
    }

    private static FetchedGraph take(ExecutorCompletionService<FetchedGraph> completion) {
        try {
            Future<FetchedGraph> future = completion.take();
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RdfDbException("Interrupted while fetching graphs", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RdfDbException rdfDbException) {
                throw rdfDbException;
            }
            throw new RdfDbException("Fetching a graph failed: " + cause.getMessage(), cause);
        }
    }

    private FetchedGraph fetchOne(String contextName, String remoteGraph) {
        long start = System.nanoTime();
        GraphCache cache = connection.database().cache();
        String cacheKey = connection.database() + "|" + remoteGraph;
        if (cache != null) {
            // A graph of the versioning layer is written once and never rewritten - a new state is a new
            // difference under a new IRI - so a cached copy of one cannot be stale and the count check, which is
            // a scan of that graph on most servers, is skipped whatever the cache is configured to do.
            boolean trusted = cache.isTrustImmutableGraphs() || RdfDbNames.isImmutableGraph(remoteGraph);
            long currentSize = trusted ? -1L : statementCount(remoteGraph);
            GraphCache.Entry entry = cache.get(cacheKey, currentSize, trusted);
            if (entry != null) {
                return new FetchedGraph(contextName, remoteGraph, entry.statements(), entry.cimNamespace(),
                        0L, System.nanoTime() - start, true);
            }
        }
        CollectingHandler handler = new CollectingHandler();
        long parseStart = System.nanoTime();
        if (connection.memory() != null) {
            // By the database-side name, never by the local one: a caller that keeps graphs under a naming of its
            // own passes the mapping, and the two names are then different things
            copyFromMemory(remoteGraph, handler);
        } else {
            GraphStoreClient client = connection.graphStoreClient();
            if (client.supported()) {
                if (!client.get(remoteGraph, handler)) {
                    throw new RdfDbException("Graph " + contextName + " of scenario '" + scenario
                            + "' is not in " + connection.database());
                }
            } else {
                constructFromSparql(remoteGraph, handler);
            }
        }
        long parseNanos = System.nanoTime() - parseStart;
        if (cache != null) {
            cache.put(cacheKey, handler.statements, handler.cimNamespace);
        }
        return new FetchedGraph(contextName, remoteGraph, handler.statements, handler.cimNamespace,
                parseNanos, System.nanoTime() - start, false);
    }

    private long statementCount(String remoteGraph) {
        if (connection.memory() != null) {
            Repository repository = connection.repository(scenario);
            if (repository == null) {
                return -1;
            }
            try (RepositoryConnection conn = repository.getConnection()) {
                return conn.size(memoryContext(conn, remoteGraph));
            }
        }
        // One cheap aggregate per graph. Still a scan of that graph on most servers, which is why the count
        // check can be switched off for a database whose graphs never change under the same IRI.
        try (RepositoryConnection conn = repositoryOfScenario().getConnection()) {
            var query = conn.prepareTupleQuery("SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + remoteGraph
                    + "> { ?s ?p ?o } }");
            try (var result = query.evaluate()) {
                if (result.hasNext()) {
                    Value n = result.next().getValue("n");
                    return n == null ? -1 : Long.parseLong(n.stringValue());
                }
            }
        }
        return -1;
    }

    private Repository repositoryOfScenario() {
        return connection.repository(scenario);
    }

    private void constructFromSparql(String remoteGraph, CollectingHandler handler) {
        try (RepositoryConnection conn = repositoryOfScenario().getConnection()) {
            conn.prepareGraphQuery("CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <" + remoteGraph + "> { ?s ?p ?o } }")
                    .evaluate(handler);
        }
    }

    private void copyFromMemory(String remoteGraph, CollectingHandler handler) {
        Repository repository = connection.repository(scenario);
        if (repository == null) {
            throw new RdfDbException("Scenario '" + scenario + "' is not in " + connection.database());
        }
        try (RepositoryConnection conn = repository.getConnection()) {
            conn.getStatements(null, null, null, memoryContext(conn, remoteGraph))
                    .forEach(handler::handleStatement);
        }
    }

    /**
     * The context a graph has in the in-process backend.
     *
     * <p>An instance file uploaded by the loading layer is kept under {@code contexts:<file name>} there, while a
     * graph this layer minted &mdash; a version of a model, a difference, a materialised copy &mdash; carries the
     * http IRI it was written under, on both backends. Rewriting the second kind into a {@code contexts:} name
     * would look for a graph that was never written.</p>
     */
    private static IRI memoryContext(RepositoryConnection conn, String remoteGraph) {
        if (remoteGraph.startsWith(RdfDbNames.BASE)) {
            return conn.getValueFactory().createIRI(remoteGraph);
        }
        return conn.getValueFactory().createIRI(ScenarioGraphNames.CONTEXTS,
                ScenarioGraphNames.localName(remoteGraph));
    }

    /**
     * Collects statements and notices the CIM namespace on the way past.
     *
     * <p>Finding out which CIM version a store holds is otherwise a query of its own, and on a database that
     * query is a scan. Every {@code rdf:type} statement goes through here anyway.</p>
     */
    private static final class CollectingHandler extends AbstractRDFHandler {

        private final List<Statement> statements = new ArrayList<>();
        private String cimNamespace;

        @Override
        public void handleStatement(Statement st) throws RDFHandlerException {
            statements.add(st);
            if (cimNamespace == null && RDF.TYPE.equals(st.getPredicate()) && st.getObject() instanceof IRI type) {
                String namespace = type.getNamespace();
                if (CgmesNamespace.isValid(namespace)) {
                    cimNamespace = namespace;
                }
            }
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphFetcher.class);
}
