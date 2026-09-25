/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.model.triplestore.CgmesTripleStoreLoader;
import com.powsybl.commons.config.PlatformConfig;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.triplestore.api.TripleStore;
import com.powsybl.triplestore.api.TripleStoreOptions;
import com.powsybl.triplestore.impl.rdf4j.sparql.GraphStoreClient;
import com.powsybl.triplestore.impl.rdf4j.sparql.ScenarioGraphNames;
import com.powsybl.triplestore.impl.rdf4j.sparql.SparqlEndpoint;
import com.powsybl.triplestore.impl.rdf4j.sparql.TripleStoreRDF4JSparql;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An open connection to an RDF database, and everything one can ask of it.
 *
 * <p>This is the object the two halves of the split loading meet on. {@link #loadCgmes} parses instance files and
 * writes them into a scenario of the database; {@link RdfDbNetworkLoader} reads a scenario back and converts it to
 * a network. Neither knows whether the database is a server or the in-process backend.</p>
 *
 * <p>Every operation names a <strong>scenario</strong>: the free-form name of the base grid model whose graphs are
 * meant, a day such as {@code 2026-09-18} or a case identifier. One database holds as many of them as the user
 * wants, strictly separated, and {@link #scenarios()} lists them.</p>
 *
 * <p>The connection owns whatever holds resources &mdash; the SPARQL repository, the HTTP client, the in-process
 * memory stores &mdash; and the stores it hands out do not. Closing it releases everything; closing a store it
 * handed out does nothing. Thread-safe.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class RdfDbConnection implements AutoCloseable {

    private final RdfDatabase database;
    private final Repository repository;
    private final GraphStoreClient graphStoreClient;
    private final InMemoryRdfDatabases.Database memory;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, ModelCatalog> catalogs = new ConcurrentHashMap<>();
    private final Map<String, SnapshotCatalog> snapshotCatalogs = new ConcurrentHashMap<>();
    private final Map<String, VersionGraph> versionGraphs = new ConcurrentHashMap<>();

    /** How many decoded parent profiles the ingestion keeps per connection; see {@link #parentIndex}. */
    private static final int PARENT_INDEX_CACHE_SIZE = 4;

    /**
     * The decoded parent states {@link SnapshotCatalog#putAsDiff} compares timesteps against, across all
     * scenarios of this connection, most recently used last.
     *
     * <p>Every timestep of a day is diffed against the same parent state, so its equipment and steady state graphs
     * used to be materialised and decoded once per timestep &mdash; a hundred and seventy thousand statements
     * copied into a fresh store and turned into statements again, ninety-five times, to answer the same question.
     * The decoded index is kept here instead.</p>
     *
     * <p><strong>Key.</strong> {@code scenario | stateModelId | tripleCount | subjectBase | cimNamespace}
     * ({@link #parentIndexKey}): the stored model whose state the index holds, the size the catalogue records for
     * it, and the two things the decoding depends on. A stored model is written once and never changed &mdash; a
     * new state of a profile is a new identifier &mdash; so an entry does not go stale; a version written on the
     * base chain between two timesteps is a new identifier, misses, and is materialised. The triple count is a
     * cheap second guard for a scenario dropped and re-created with re-used identifiers by another client.</p>
     *
     * <p><strong>Bound.</strong> Four entries in access order <em>for the whole connection</em>, not per
     * scenario: a service ingests one day (one scenario) at a time, and a cache per scenario would keep the
     * parents of every day it ever ingested. Four is the equipment and steady state profile of the current parent
     * plus one older pair. Measured on Svedala, the largest model in the repertoire, a parent pair retains
     * 30.5&nbsp;MB (equipment 21.8&nbsp;MB, steady state 8.8&nbsp;MB), so the cache holds at most ~61&nbsp;MB.
     * The day after starts cold, which costs one materialisation.</p>
     *
     * <p><strong>Invalidation.</strong> By scenario prefix, from {@link #clear}, from a plain upload into the
     * scenario, and from {@code SnapshotCatalog}'s root write and {@code dropAll} &mdash; the same places the
     * {@link GraphCache} is invalidated for the graphs.</p>
     *
     * <p><strong>Thread safety.</strong> A {@code LinkedHashMap} in access order mutates on a read, so every access
     * is inside {@code synchronized (parentIndexes)}. The values are immutable ({@link TripleDiffCalculator.Index}
     * is read-only), so they may be handed out of the lock.</p>
     */
    private final Map<String, TripleDiffCalculator.Index> parentIndexes =
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, TripleDiffCalculator.Index> eldest) {
                    return size() > PARENT_INDEX_CACHE_SIZE;
                }
            };

    private RdfDbConnection(RdfDatabase database, Repository repository, GraphStoreClient graphStoreClient,
                            InMemoryRdfDatabases.Database memory) {
        this.database = database;
        this.repository = repository;
        this.graphStoreClient = graphStoreClient;
        this.memory = memory;
    }

    /**
     * Open a connection and check that the database answers.
     *
     * <p>The check is an {@code ASK {}}, which costs nothing and fails immediately when the host is wrong, the
     * server is down or the credentials are refused &mdash; rather than letting the first real query fail in the
     * middle of a load.</p>
     *
     * @param database which database to connect to
     * @return the open connection
     * @throws RdfDbException if the database cannot be reached
     */
    public static RdfDbConnection open(RdfDatabase database) {
        Objects.requireNonNull(database);
        if (database.isInMemory()) {
            return new RdfDbConnection(database, null, null,
                    InMemoryRdfDatabases.acquire(database.memoryName()));
        }
        SparqlEndpoint endpoint = database.endpoint();
        SPARQLRepository repository = TripleStoreRDF4JSparql.newRepository(endpoint);
        try (RepositoryConnection conn = repository.getConnection()) {
            conn.prepareBooleanQuery("ASK {}").evaluate();
        } catch (Exception e) {
            repository.shutDown();
            throw new RdfDbException("Cannot reach " + endpoint.queryUrl() + ": " + rootMessage(e), e);
        }
        return new RdfDbConnection(database, repository,
                new GraphStoreClient(endpoint, database.gzip()), null);
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null ? root.getClass().getSimpleName() : message;
    }

    /**
     * @return the database this connection talks to
     */
    public RdfDatabase database() {
        return database;
    }

    /**
     * The scenarios the database currently holds data for.
     *
     * <p>A scenario exists as soon as it holds <em>something</em>: uploaded instance files, or only a metadata
     * graph carrying difference models. Both are listed, because either makes the scenario a thing a caller can
     * load from.</p>
     *
     * @return the scenario names, sorted
     */
    public List<String> scenarios() {
        checkOpen();
        if (memory != null) {
            // Each scenario of the in-process backend is a store of its own, metadata graph included, so the
            // registry already knows every scenario that holds anything
            return memory.scenarios();
        }
        TreeSet<String> names = new TreeSet<>();
        try (RepositoryConnection conn = repository.getConnection()) {
            // An empty group pattern lists the named graphs without scanning their statements
            TupleQuery query = conn.prepareTupleQuery(
                    "SELECT DISTINCT ?g WHERE { GRAPH ?g { } FILTER(STRSTARTS(STR(?g), \""
                            + ScenarioGraphNames.CONTEXTS + "\") || STRSTARTS(STR(?g), \""
                            + RdfDbNames.BASE + "\")) }");
            try (TupleQueryResult result = query.evaluate()) {
                while (result.hasNext()) {
                    Value g = result.next().getValue("g");
                    String scenario = g == null ? null : scenarioOfGraph(g.stringValue());
                    if (scenario != null) {
                        names.add(scenario);
                    }
                }
            }
        }
        return new ArrayList<>(names);
    }

    private static String scenarioOfGraph(String graphIri) {
        String scenario = ScenarioGraphNames.scenarioOf(graphIri);
        return scenario != null ? scenario : RdfDbNames.scenarioOfMetaGraph(graphIri);
    }

    /**
     * Typed SPARQL access to the graphs of one scenario.
     *
     * @param scenario the scenario
     * @return the access
     * @throws RdfDbException if the scenario name is blank
     */
    public SparqlAccess sparql(String scenario) {
        checkOpen();
        return new SparqlAccess(this, scenario);
    }

    /**
     * The catalogue of stored models of one scenario.
     *
     * <p>One catalogue per scenario, created on first use and kept, because it is stateless apart from the
     * scenario it is bound to. There is deliberately no connection-wide catalogue: a model identifier means
     * nothing without the scenario it was stored in.</p>
     *
     * @param scenario the scenario
     * @return the catalogue
     * @throws RdfDbException if the scenario name is blank
     */
    public ModelCatalog catalog(String scenario) {
        checkOpen();
        return catalogs.computeIfAbsent(RdfDbNames.checkScenario(scenario),
                s -> new ModelCatalog(this, s));
    }

    /**
     * The snapshots of one scenario: what versions and timesteps it holds, and how a new one is written.
     *
     * <p>One catalogue per scenario, like {@link #catalog(String)} and for the same reason: a version label means
     * nothing without the scenario it belongs to, and nothing in this layer resolves one across scenarios.</p>
     *
     * @param scenario the scenario
     * @return the snapshot catalogue
     * @throws RdfDbException if the scenario name is blank
     */
    public SnapshotCatalog snapshots(String scenario) {
        checkOpen();
        return snapshotCatalogs.computeIfAbsent(RdfDbNames.checkScenario(scenario),
                s -> new SnapshotCatalog(this, s));
    }

    /**
     * The planner of one scenario: "can I reach this snapshot by applying differences?".
     *
     * @param scenario the scenario
     * @return the planner
     * @throws RdfDbException if the scenario name is blank
     */
    public VersionGraph versionGraph(String scenario) {
        checkOpen();
        return versionGraphs.computeIfAbsent(RdfDbNames.checkScenario(scenario),
                s -> new VersionGraph(this, s));
    }

    /**
     * A triple store on the graphs of one scenario, straight on the database.
     *
     * <p>Queries through it are SPARQL requests to the server, restricted to the scenario. Its
     * {@link TripleStore#close()} is a no-op: the connection owns the resources.</p>
     *
     * @param scenario the scenario
     * @param options  the triple store options, which must be the ones of
     *                 {@link CgmesImport#tripleStoreOptions(Properties)} for the parameters a later conversion uses
     * @return the store
     */
    public TripleStore scenarioStore(String scenario, TripleStoreOptions options) {
        return scenarioStore(scenario, options, true);
    }

    /**
     * A triple store on the graphs of one scenario.
     *
     * @param scenario the scenario
     * @param options  the triple store options
     * @param create   whether a scenario that does not exist yet may be created. Only a write needs that; asking
     *                 to read a scenario that is not there must not make {@link #scenarios()} list it
     * @return the store
     */
    TripleStore scenarioStore(String scenario, TripleStoreOptions options, boolean create) {
        checkOpen();
        ScenarioGraphNames.requireValidScenario(scenario);
        if (memory != null) {
            Repository scenarioRepository = memory.scenario(scenario, create);
            return new SharedRepositoryTripleStore(
                    scenarioRepository == null ? memory.emptyRepository() : scenarioRepository, options);
        }
        return new TripleStoreRDF4JSparql(repository, database.endpoint(), scenario, options,
                graphStoreClient, false);
    }

    /**
     * The context names (instance file names) of a scenario.
     *
     * @param scenario the scenario
     * @return the context names, sorted, each with the {@code contexts:} prefix
     */
    public List<String> contextNames(String scenario) {
        checkOpen();
        ScenarioGraphNames.requireValidScenario(scenario);
        if (memory != null) {
            Repository repo = memory.scenario(scenario, false);
            if (repo == null) {
                return List.of();
            }
            try (RepositoryConnection conn = repo.getConnection()) {
                TreeSet<String> names = new TreeSet<>();
                // Only the instance file graphs: the metadata graph of the scenario and the data graphs of its
                // difference models live in the same store on this backend, and they are not CGMES contexts
                conn.getContextIDs().forEach(ctx -> {
                    String name = ctx.stringValue();
                    if (name.startsWith(ScenarioGraphNames.CONTEXTS)) {
                        names.add(name);
                    }
                });
                return new ArrayList<>(names);
            }
        }
        TripleStoreRDF4JSparql store =
                (TripleStoreRDF4JSparql) scenarioStore(scenario, new TripleStoreOptions(), false);
        return new ArrayList<>(new TreeSet<>(store.contextNames()));
    }

    /**
     * The catalogue of a scenario: one row per named graph.
     *
     * @param scenario the scenario
     * @return the graphs, sorted by context name
     */
    public List<GraphInfo> graphs(String scenario) {
        return contextNames(scenario).stream()
                .map(name -> new GraphInfo(scenario, name, GraphInfo.subsetOf(name),
                        ScenarioGraphNames.remoteGraph(scenario, name)))
                .toList();
    }

    /**
     * Read CGMES instance files into a scenario of the database.
     *
     * <p>This is the first half of the split loading, and the expensive half: the files are parsed once here and
     * never again. A graph of the same name that is already in the scenario is <em>replaced</em>, so re-running an
     * upload is idempotent rather than cumulative.</p>
     *
     * @param scenario     the scenario to write into
     * @param ds           the data source holding the instance files
     * @param boundary     the data source to take the boundary from when {@code ds} carries none, or {@code null}
     * @param importParams the CGMES import parameters, for the identifier options of the triple store
     * @param reportNode   where the reader reports the files it read
     * @return what was loaded
     */
    public CgmesTripleStoreLoader.Result loadCgmes(String scenario, ReadOnlyDataSource ds,
                                                   ReadOnlyDataSource boundary, Properties importParams,
                                                   ReportNode reportNode) {
        checkOpen();
        ScenarioGraphNames.requireValidScenario(scenario);
        Objects.requireNonNull(ds);
        // A versioned scenario owns its instance files: they are immutable graphs a snapshot refers to, and this
        // upload would put a second, unversioned set next to them that no snapshot can ever reach
        if (snapshots(scenario).isVersioned()) {
            throw new RdfDbException("scenario '" + scenario + "' is versioned: use SnapshotCatalog.putFull to"
                    + " write its root, or SnapshotCatalog.putAsDiff to add a timestep");
        }
        CgmesImport importer = new CgmesImport(PlatformConfig.defaultConfig());
        TripleStore store = scenarioStore(scenario, importer.tripleStoreOptions(importParams));
        int parallelism = memory != null ? 1 : database.uploadParallelism();
        CgmesTripleStoreLoader.Result result = CgmesTripleStoreLoader.load(ds, boundary, store, parallelism,
                reportNode == null ? ReportNode.NO_OP : reportNode);
        invalidateCache(scenario);
        forgetParentIndexes(scenario);
        // The upload is also the moment the versioning layer learns about the models: a graph that nothing in the
        // metadata graph refers to is invisible to a difference, and a difference has to name the model it applies
        // on. Re-uploading the same file refreshes its node, because a full-model graph is replaced, not versioned.
        catalog(scenario).registerFullModels(result);
        LOGGER.info("Loaded {} graphs of {} into scenario '{}' of {}",
                result.contextNames().size(), ds.getBaseName(), scenario, database);
        return result;
    }

    /**
     * Drop every graph of a scenario.
     *
     * @param scenario the scenario to empty
     */
    public void clear(String scenario) {
        checkOpen();
        ScenarioGraphNames.requireValidScenario(scenario);
        if (memory != null) {
            // The whole scenario is one store here, metadata graph and difference graphs included
            memory.drop(scenario);
        } else {
            TripleStoreRDF4JSparql store =
                    (TripleStoreRDF4JSparql) scenarioStore(scenario, new TripleStoreOptions(), false);
            store.clearScenario();
            clearVersioningGraphs(scenario);
        }
        catalogs.remove(scenario);
        snapshotCatalogs.remove(scenario);
        versionGraphs.remove(scenario);
        invalidateCache(scenario);
        forgetParentIndexes(scenario);
    }

    /**
     * Drop the metadata graph and the difference graphs of a scenario on a server backend, where every scenario
     * lives in one dataset and {@code clearScenario} only knows the instance file graphs.
     */
    private void clearVersioningGraphs(String scenario) {
        StringBuilder update = new StringBuilder("DROP SILENT GRAPH <")
                .append(RdfDbNames.metaGraph(scenario)).append('>');
        // Everything this layer minted for the scenario: the difference graphs, the versioned full graphs and the
        // graphs a checkpoint materialised. The prefix ends with a slash, so scenario "a" never matches "ab"
        String prefix = RdfDbNames.scenarioPrefix(scenario);
        try (RepositoryConnection conn = repository.getConnection()) {
            TupleQuery query = conn.prepareTupleQuery(
                    "SELECT DISTINCT ?g WHERE { GRAPH ?g { } FILTER(STRSTARTS(STR(?g), \"" + prefix + "\")) }");
            try (TupleQueryResult result = query.evaluate()) {
                while (result.hasNext()) {
                    Value g = result.next().getValue("g");
                    if (g != null) {
                        update.append(" ; DROP SILENT GRAPH <").append(g.stringValue()).append('>');
                    }
                }
            }
            conn.prepareUpdate(update.toString()).execute();
        }
    }

    /** The key of one decoded parent profile in {@link #parentIndexes}; the scenario comes first, for the prefix. */
    static String parentIndexKey(String scenario, String stateModelId, long tripleCount, String subjectBase,
                                 String cimNamespace) {
        return scenario + "|" + stateModelId + "|" + tripleCount + "|" + subjectBase + "|" + cimNamespace;
    }

    /** @return the decoded parent profile kept under that key, or {@code null} */
    TripleDiffCalculator.Index parentIndex(String key) {
        synchronized (parentIndexes) {
            return parentIndexes.get(key);
        }
    }

    /** Keep a decoded parent profile, evicting the least recently used one beyond the bound. */
    void rememberParentIndex(String key, TripleDiffCalculator.Index index) {
        synchronized (parentIndexes) {
            parentIndexes.put(key, index);
        }
    }

    /** Forget every decoded parent profile of one scenario, because its states are gone or replaced. */
    void forgetParentIndexes(String scenario) {
        String prefix = scenario + "|";
        synchronized (parentIndexes) {
            parentIndexes.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    private void invalidateCache(String scenario) {
        GraphCache cache = database.cache();
        if (cache != null) {
            cache.invalidatePrefix(cacheKeyPrefix(scenario));
        }
    }

    /**
     * The cache key prefix of a scenario of this database.
     *
     * <p>A cache key is {@code <database>|<graph IRI>}: the database is part of it so that one cache can serve
     * several databases without confusing two scenarios that happen to be called the same, and the graph IRI
     * rather than the file name so that a caller addressing graphs under another scheme is keyed correctly too.</p>
     *
     * <p>An upload into a scenario invalidates everything under this prefix. Graphs that a caller addressed by
     * an explicit IRI of its own (see the {@code Map} overloads of {@code GraphFetcher.fetchInto} and
     * {@code TripleStoreRDF4JSparql.restrictTo}) are not under it, and that is deliberate: such a naming scheme
     * is meant to be immutable, and its owner decides when an entry is stale.</p>
     *
     * @param scenario the scenario
     * @return the prefix every cache key of that scenario starts with
     */
    String cacheKeyPrefix(String scenario) {
        return database + "|" + ScenarioGraphNames.prefix(scenario);
    }

    /**
     * The IRI a context of a scenario has in this database.
     *
     * <p>Not the same on both backends, and that is why the metadata graph stores it rather than letting a reader
     * compute it: a server holds every scenario in one dataset and needs the scenario in the graph name, while the
     * in-process backend gives each scenario a store of its own and keeps the plain context name.</p>
     *
     * @param scenario    the scenario
     * @param contextName the context name, with or without the {@code contexts:} prefix
     * @return the graph IRI
     */
    String graphIri(String scenario, String contextName) {
        return memory == null
                ? ScenarioGraphNames.remoteGraph(scenario, contextName)
                : ScenarioGraphNames.CONTEXTS + ScenarioGraphNames.localName(contextName);
    }

    /**
     * The context name a graph IRI of a scenario stands for, the inverse of {@link #graphIri}.
     *
     * @param scenario the scenario
     * @param graphIri the graph IRI as the metadata graph records it
     * @return {@code contexts:<file name>}, or {@code null} when the IRI is not an instance file graph of that
     *         scenario
     */
    String localContextName(String scenario, String graphIri) {
        return memory == null ? ScenarioGraphNames.localContextName(scenario, graphIri)
                : graphIri != null && graphIri.startsWith(ScenarioGraphNames.CONTEXTS) ? graphIri : null;
    }

    /**
     * Replace a named graph of a scenario with the given statements.
     *
     * <p>The bulk path of the versioning layer: through the Graph Store Protocol on a server, which is one request
     * for the whole graph, and by a plain transaction on the in-process backend.</p>
     *
     * @param scenario   the scenario the graph belongs to
     * @param graphIri   the IRI of the graph, used as it is
     * @param statements the statements the graph is to hold
     */
    void writeGraph(String scenario, String graphIri, Collection<Statement> statements) {
        checkOpen();
        RdfDbNames.checkScenario(scenario);
        if (memory != null) {
            Repository repo = memory.scenario(scenario, true);
            try (RepositoryConnection conn = repo.getConnection()) {
                IRI context = conn.getValueFactory().createIRI(graphIri);
                conn.begin();
                conn.clear(context);
                conn.add(statements, context);
                conn.commit();
            }
        } else {
            TripleStoreRDF4JSparql store =
                    (TripleStoreRDF4JSparql) scenarioStore(scenario, new TripleStoreOptions(), false);
            store.writeGraph(graphIri, statements, true);
        }
    }

    /**
     * The Graph Store Protocol client of this connection, or {@code null} for the in-process backend.
     *
     * @return the client
     */
    GraphStoreClient graphStoreClient() {
        return graphStoreClient;
    }

    /**
     * The in-process database behind this connection, or {@code null} for a real one.
     *
     * @return the in-process database
     */
    InMemoryRdfDatabases.Database memory() {
        return memory;
    }

    /**
     * The RDF4J repository of a scenario, without building a triple store around it.
     *
     * @param scenario the scenario
     * @return the shared SPARQL repository, or the memory store of that scenario. {@code null} when the scenario
     *         does not exist in the in-process backend
     */
    Repository repository(String scenario) {
        return repository(scenario, false);
    }

    /**
     * The RDF4J repository of a scenario.
     *
     * @param scenario the scenario
     * @param create   whether a scenario that does not exist yet may be created. Only a write needs that
     * @return the shared SPARQL repository, or the memory store of that scenario. {@code null} when the scenario
     *         does not exist in the in-process backend and {@code create} is false
     */
    Repository repository(String scenario, boolean create) {
        checkOpen();
        RdfDbNames.checkScenario(scenario);
        return memory == null ? repository : memory.scenario(scenario, create);
    }

    private void checkOpen() {
        if (closed.get()) {
            throw new RdfDbException("The connection to " + database + " is closed");
        }
    }

    @Override
    public void close() {
        // Compare-and-set, not a plain flag: two concurrent closes would both pass a plain check and release the
        // in-process database twice, which decrements a shared reference count under somebody else's connection.
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (memory != null) {
            InMemoryRdfDatabases.release(database.memoryName());
        } else {
            graphStoreClient.close();
            repository.shutDown();
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(RdfDbConnection.class);
}
