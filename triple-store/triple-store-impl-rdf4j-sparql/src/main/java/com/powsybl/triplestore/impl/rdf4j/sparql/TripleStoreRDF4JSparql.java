/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.triplestore.impl.rdf4j.sparql;

import com.powsybl.commons.datasource.DataSource;
import com.powsybl.triplestore.api.PrefixNamespace;
import com.powsybl.triplestore.api.PropertyBag;
import com.powsybl.triplestore.api.PropertyBags;
import com.powsybl.triplestore.api.TripleStore;
import com.powsybl.triplestore.api.TripleStoreException;
import com.powsybl.triplestore.api.TripleStoreOptions;
import com.powsybl.triplestore.impl.rdf4j.TripleStoreRDF4J;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.eclipse.rdf4j.rio.helpers.XMLParserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A powsybl triple store whose statements live in a remote SPARQL 1.1 graph database.
 *
 * <p>Everything above a triple store &mdash; {@code CgmesModelTripleStore}, the query catalogs, the CGMES
 * conversion &mdash; is written against {@link TripleStore} and does not care where the statements are. This
 * implementation puts them in a database: queries are SPARQL requests over HTTP, and a whole named graph is
 * written or read in one Graph Store Protocol request.</p>
 *
 * <h2>Scenarios</h2>
 * <p>One database holds many days and many base grid models at the same time. Every store is therefore opened for
 * exactly one <em>scenario</em>, and its graphs live under {@code contexts:<scenario>/}. The conversion above
 * never sees that: it is handed {@code contexts:<file name>} on the way out and understands
 * {@code contexts:<file name>} on the way in, so reading the subset of a model off a graph name keeps working
 * whatever the scenario is called. See {@link ScenarioGraphNames}.</p>
 *
 * <h2>What a SPARQL endpoint cannot do, and what is done instead</h2>
 * <ul>
 *   <li><strong>Namespaces.</strong> A SPARQL endpoint has no namespace store: RDF4J's
 *       {@code SPARQLConnection.setNamespace} throws and {@code getNamespaces()} is empty. They are kept in a
 *       local map here, which is all they are used for (query prefixes, and the {@code data} prefix new
 *       statements are created under).</li>
 *   <li><strong>Listing graphs.</strong> RDF4J would run a full quad scan. This class asks
 *       {@code SELECT DISTINCT ?g} and filters by the scenario prefix.</li>
 *   <li><strong>Adding statements.</strong> Autocommit sends one {@code INSERT DATA} request per statement. Every
 *       write here is either a Graph Store Protocol request or a single explicit transaction.</li>
 *   <li><strong>Which graphs a query sees.</strong> The local in-memory store powsybl uses by default has a
 *       default graph that is the union of all contexts, so the graph-less parts of the CGMES catalogs match
 *       everything. A database does not, so every query is sent with the SPARQL protocol dataset parameters
 *       {@code default-graph-uri} and {@code named-graph-uri} of the scenario's graphs, which both restores the
 *       union semantics and keeps one scenario's queries out of another's data.</li>
 * </ul>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public class TripleStoreRDF4JSparql extends TripleStoreRDF4J {

    /** The name this implementation is registered under, see {@code TripleStoreFactory}. */
    public static final String NAME = "rdf4j-sparql";

    /** How many statements go into one {@code INSERT DATA} request when there is no Graph Store Protocol URL. */
    private static final int INSERT_BATCH = 10_000;

    private final SparqlEndpoint endpoint;
    private final String scenario;
    private final String graphPrefix;
    private final GraphStoreClient graphStoreClient;
    private Map<String, String> namespaces;
    private final boolean ownsClient;

    private volatile boolean remoteQueryDataset = true;
    private volatile List<IRI> cachedGraphs;
    private volatile Set<String> restriction;
    private volatile Map<String, String> explicitGraphs;

    /**
     * Open a store on a SPARQL endpoint for one scenario.
     *
     * @param endpoint the database coordinates
     * @param scenario the scenario the graphs of this store belong to. Required and not blank
     * @param options  the triple store configuration options
     */
    public TripleStoreRDF4JSparql(SparqlEndpoint endpoint, String scenario, TripleStoreOptions options) {
        this(repositoryFor(endpoint, scenario), endpoint, scenario, options, new GraphStoreClient(endpoint), true);
    }

    /**
     * Open a store on a repository and a Graph Store Protocol client somebody else owns.
     *
     * <p>Used by a connection object that keeps one repository and one HTTP client for several stores and
     * several scenarios. {@link #close()} then leaves both alone.</p>
     *
     * @param repository       the repository to query through, normally a {@code SPARQLRepository}
     * @param endpoint         the database coordinates
     * @param scenario         the scenario the graphs of this store belong to. Required and not blank
     * @param options          the triple store configuration options
     * @param graphStoreClient the client to transfer graphs with
     * @param ownsResources    whether {@link #close()} shuts the repository and the client down
     */
    public TripleStoreRDF4JSparql(Repository repository, SparqlEndpoint endpoint, String scenario,
                                  TripleStoreOptions options, GraphStoreClient graphStoreClient,
                                  boolean ownsResources) {
        super(repository, options);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.scenario = ScenarioGraphNames.requireValidScenario(scenario);
        this.graphPrefix = ScenarioGraphNames.prefix(this.scenario);
        this.graphStoreClient = Objects.requireNonNull(graphStoreClient);
        this.ownsClient = ownsResources;
    }

    /**
     * The namespace map of this store.
     *
     * <p>Lazily created because the constructor of {@code AbstractPowsyblTripleStore} already defines the
     * {@code rdf} query prefix, which reaches {@link #defineQueryPrefix(String, String)} here before any field
     * initialiser of this class has run.</p>
     *
     * <p>The field itself needs no {@code volatile}: this synchronized accessor is the only reader and the only
     * writer of it, so the map is safely published to every thread that goes through it.</p>
     *
     * @return the map, synchronised because a parallel upload writes the {@code data} prefix from several threads
     */
    private synchronized Map<String, String> namespaces() {
        if (namespaces == null) {
            namespaces = Collections.synchronizedMap(new LinkedHashMap<>());
        }
        return namespaces;
    }

    private static Repository repositoryFor(SparqlEndpoint endpoint, String scenario) {
        ScenarioGraphNames.requireValidScenario(scenario);
        return newRepository(endpoint);
    }

    /**
     * An initialised RDF4J repository on a SPARQL endpoint, with its credentials, headers and timeouts.
     *
     * <p>The one place a {@code SPARQLRepository} is built, so that the timeouts of the endpoint cover the
     * queries and the updates and not only the Graph Store Protocol transfers.</p>
     *
     * @param endpoint the database coordinates
     * @return the repository. The caller owns it and has to shut it down
     */
    public static SPARQLRepository newRepository(SparqlEndpoint endpoint) {
        Objects.requireNonNull(endpoint);
        SPARQLRepository repository = endpoint.updateUrl() == null
                ? new SPARQLRepository(endpoint.queryUrl().toString())
                : new SPARQLRepository(endpoint.queryUrl().toString(), endpoint.updateUrl().toString());
        repository.setHttpClient(endpoint.newHttpClient());
        if (endpoint.user() != null) {
            repository.setUsernameAndPassword(endpoint.user(), endpoint.password() == null ? "" : endpoint.password());
        }
        if (!endpoint.headers().isEmpty()) {
            repository.setAdditionalHttpHeaders(endpoint.headers());
        }
        repository.init();
        return repository;
    }

    @Override
    public String getImplementationName() {
        return NAME;
    }

    /**
     * @return the coordinates of the database this store talks to
     */
    public SparqlEndpoint getEndpoint() {
        return endpoint;
    }

    /**
     * @return the scenario whose graphs this store holds
     */
    public String getScenario() {
        return scenario;
    }

    /**
     * @return the client this store transfers whole graphs with
     */
    public GraphStoreClient graphStoreClient() {
        return graphStoreClient;
    }

    /**
     * The database graph IRI a powsybl context name stands for.
     *
     * @param contextName the context name, with or without the {@code contexts:} prefix
     * @return the graph IRI in the database
     */
    public String remoteGraph(String contextName) {
        Map<String, String> explicit = explicitGraphs;
        if (explicit != null) {
            String iri = explicit.get(ScenarioGraphNames.localName(contextName));
            if (iri != null) {
                return iri;
            }
        }
        return ScenarioGraphNames.remoteGraph(scenario, contextName);
    }

    /**
     * The powsybl context name a database graph IRI stands for.
     *
     * @param remoteGraphIri the graph IRI in the database
     * @return the context name, or {@code null} when the graph does not belong to this store's scenario
     */
    public String localContextName(String remoteGraphIri) {
        Map<String, String> explicit = explicitGraphs;
        if (explicit != null) {
            for (Map.Entry<String, String> e : explicit.entrySet()) {
                if (e.getValue().equals(remoteGraphIri)) {
                    return ScenarioGraphNames.CONTEXTS + e.getKey();
                }
            }
        }
        return ScenarioGraphNames.localContextName(scenario, remoteGraphIri);
    }

    /**
     * Whether queries are sent with the dataset of this scenario's graphs.
     *
     * <p>On by default, and the only reason to switch it off is a server configured with a union default graph
     * that holds a single scenario. With it off, a query sees whatever the server's default dataset is, which for
     * a database holding several scenarios means all of them.</p>
     *
     * @param enabled whether to send {@code default-graph-uri} and {@code named-graph-uri}
     */
    public void setRemoteQueryDataset(boolean enabled) {
        this.remoteQueryDataset = enabled;
    }

    /**
     * @return whether queries are sent with the dataset of this scenario's graphs
     */
    public boolean isRemoteQueryDataset() {
        return remoteQueryDataset;
    }

    // ---------------------------------------------------------------- naming hooks

    @Override
    protected Resource context(RepositoryConnection conn, String contextName) {
        return conn.getValueFactory().createIRI(remoteGraph(contextName));
    }

    @Override
    protected String bindingValue(Value value) {
        String s = value.stringValue();
        if (s.startsWith(graphPrefix)) {
            return ScenarioGraphNames.CONTEXTS + ScenarioGraphNames.decode(s.substring(graphPrefix.length()));
        }
        String local = localContextName(s);
        return local == null ? s : local;
    }

    @Override
    protected String namespace(RepositoryConnection conn, String prefix) {
        return namespaces().get(prefix);
    }

    @Override
    protected void addNamespaceForBase(RepositoryConnection cnx, String base) {
        // A SPARQL endpoint has no namespace store, so the connection is not used and may be null
        namespaces().put("data", base + "/#");
    }

    @Override
    public void addNamespace(String prefix, String namespace) {
        namespaces().put(prefix, namespace);
    }

    @Override
    public List<PrefixNamespace> getNamespaces() {
        return namespaces().entrySet().stream()
                .map(e -> new PrefixNamespace(e.getKey(), e.getValue()))
                .toList();
    }

    @Override
    public void defineQueryPrefix(String prefix, String namespace) {
        super.defineQueryPrefix(prefix, namespace);
        namespaces().put(prefix, namespace);
    }

    // ---------------------------------------------------------------- queries

    @Override
    protected TupleQuery prepareTupleQuery(RepositoryConnection conn, String adjustedQuery) {
        TupleQuery query = conn.prepareTupleQuery(adjustedQuery);
        if (remoteQueryDataset) {
            List<IRI> graphs = graphs(conn);
            SimpleDataset dataset = new SimpleDataset();
            if (graphs.isEmpty()) {
                // An empty dataset would send no protocol parameters at all, and the query would run against
                // whatever the server's default dataset is - every other scenario included. A graph that cannot
                // exist keeps the answer empty, which is the truth for a scenario without graphs.
                IRI nothing = conn.getValueFactory().createIRI(graphPrefix + "%00no-such-graph");
                dataset.addDefaultGraph(nothing);
                dataset.addNamedGraph(nothing);
            }
            // Both lists are needed. SPARQL 1.1 Protocol: as soon as one of the two parameters is present the
            // dataset is exactly the one described, so without named-graph-uri every GRAPH ?g pattern - two
            // thirds of the CGMES catalogs - would match nothing at all.
            graphs.forEach(g -> {
                dataset.addDefaultGraph(g);
                dataset.addNamedGraph(g);
            });
            query.setDataset(dataset);
        }
        return query;
    }

    @Override
    public Set<String> contextNames() {
        try (RepositoryConnection conn = getRepository().getConnection()) {
            return graphs(conn).stream()
                    .map(iri -> localContextName(iri.stringValue()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private List<IRI> graphs(RepositoryConnection conn) {
        List<IRI> graphs = cachedGraphs;
        if (graphs == null) {
            graphs = listGraphs(conn);
            cachedGraphs = graphs;
        }
        return graphs;
    }

    private List<IRI> listGraphs(RepositoryConnection conn) {
        Set<String> only = restriction;
        if (only != null) {
            return only.stream()
                    .map(name -> conn.getValueFactory().createIRI(remoteGraph(name)))
                    .sorted(Comparator.comparing(IRI::stringValue))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        // Deliberately not conn.getContextIDs(): RDF4J turns that into "GRAPH ?g { ?s ?p ?o }", a full quad
        // scan, and on a 14 MB model that alone costs as much as parsing an instance file. An empty group
        // pattern binds the graph variable once per named graph without looking at a single statement.
        String query = "SELECT DISTINCT ?g WHERE { GRAPH ?g { } FILTER(STRSTARTS(STR(?g), \""
                + graphPrefix + "\")) }";
        List<IRI> graphs = new ArrayList<>();
        TupleQuery q = conn.prepareTupleQuery(query);
        try (var result = q.evaluate()) {
            while (result.hasNext()) {
                Value g = result.next().getValue("g");
                if (g instanceof IRI iri) {
                    graphs.add(iri);
                }
            }
        }
        graphs.sort(Comparator.comparing(IRI::stringValue));
        return graphs;
    }

    /**
     * Narrow this store to some of the scenario's graphs.
     *
     * <p>A CGMES update reads the profiles that changed, typically SSH and SV, and the update query catalog is
     * mostly graph-less: it runs over the union of everything the store holds. Handing it a store that also holds
     * the EQ graphs would have it read the initial state back. A restriction makes the store behave as if only
     * the named graphs existed &mdash; {@link #contextNames()} lists them, and queries are sent with exactly them
     * as their dataset.</p>
     *
     * @param contextNames the context names to restrict to, or {@code null} to see the whole scenario again
     */
    public void restrictTo(Collection<String> contextNames) {
        this.explicitGraphs = null;
        this.restriction = contextNames == null ? null
                : contextNames.stream().map(ScenarioGraphNames::localName)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        invalidateGraphCache();
    }

    /**
     * Narrow this store to graphs named one by one.
     *
     * <p>{@link #restrictTo(Collection)} derives the database IRI of a context from the scenario prefix, which is
     * the only naming this work package has. A caller that keeps graphs under another scheme &mdash; an immutable
     * IRI per version of a model, a difference graph &mdash; gives the mapping instead. Above the store nothing
     * changes: {@link #contextNames()} still answers {@code contexts:<file name>} and so does every {@code graph}
     * binding of a query, whatever the graphs are called in the database.</p>
     *
     * @param localToRemote local context name to the IRI of the graph in the database, or {@code null} to see the
     *                      whole scenario again
     */
    public void restrictTo(Map<String, String> localToRemote) {
        if (localToRemote == null) {
            this.explicitGraphs = null;
            this.restriction = null;
        } else {
            Map<String, String> graphs = new LinkedHashMap<>();
            localToRemote.forEach((name, iri) -> graphs.put(ScenarioGraphNames.localName(name), iri));
            this.explicitGraphs = graphs;
            this.restriction = new LinkedHashSet<>(graphs.keySet());
        }
        invalidateGraphCache();
    }

    /**
     * Forget the cached list of this scenario's graphs.
     *
     * <p>The list is needed by every query (it is the dataset that is sent along) and is therefore cached. Any
     * write through this store invalidates it by itself; a write that went to the database some other way &mdash;
     * another process, another store on the same connection &mdash; has to say so here.</p>
     */
    public void invalidateGraphCache() {
        cachedGraphs = null;
    }

    // ---------------------------------------------------------------- writes

    @Override
    public void read(InputStream is, String baseName, String contextName) {
        // The file is parsed locally, with the very same RDF4J parser and the very same non-fatal settings as a
        // local import, and re-serialised as N-Triples. That is what makes the statements in the database
        // identical to the ones a file import would have produced: the rdf:ID resolution against the base
        // happens here, once, and every IRI that leaves this method is absolute.
        StatementCollector collector = new StatementCollector();
        RDFParser parser = Rio.createParser(formatOf(contextName));
        parser.getParserConfig().addNonFatalError(XMLParserSettings.FAIL_ON_INVALID_NCNAME);
        parser.getParserConfig().addNonFatalError(BasicParserSettings.VERIFY_URI_SYNTAX);
        parser.getParserConfig().addNonFatalError(XMLParserSettings.FAIL_ON_DUPLICATE_RDF_ID);
        parser.setRDFHandler(collector);
        try {
            parser.parse(is, baseName);
        } catch (Exception e) {
            throw new TripleStoreException(String.format("Reading %s %s", baseName, contextName), e);
        }
        addNamespaceForBase(null, baseName);
        writeGraph(remoteGraph(contextName), collector.getStatements(), true);
        invalidateGraphCache();
    }

    private static RDFFormat formatOf(String name) {
        return name.endsWith(".ttl") ? RDFFormat.TURTLE : RDFFormat.RDFXML;
    }

    /**
     * Replace or extend one graph of this scenario with the given statements.
     *
     * <p>Through the Graph Store Protocol when the database has one, which is one request per graph; otherwise
     * through {@code INSERT DATA} in transactions of {@value #INSERT_BATCH} statements, which is what a server
     * without that protocol leaves as the only option.</p>
     *
     * @param graphIri   the graph IRI in the database
     * @param statements the statements to write
     * @param replace    whether to drop whatever the graph held before
     */
    public void writeGraph(String graphIri, Collection<Statement> statements, boolean replace) {
        if (graphStoreClient.supported()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(1024, statements.size() * 120));
            RDFWriter writer = Rio.createWriter(RDFFormat.NTRIPLES, bytes);
            writer.startRDF();
            statements.forEach(writer::handleStatement);
            writer.endRDF();
            byte[] body = bytes.toByteArray();
            if (replace) {
                graphStoreClient.put(graphIri, new ByteArrayInputStream(body), body.length);
            } else {
                graphStoreClient.post(graphIri, new ByteArrayInputStream(body), body.length);
            }
        } else {
            writeGraphWithSparqlUpdate(graphIri, statements, replace);
        }
        invalidateGraphCache();
    }

    private void writeGraphWithSparqlUpdate(String graphIri, Collection<Statement> statements, boolean replace) {
        try (RepositoryConnection conn = getRepository().getConnection()) {
            if (replace) {
                conn.prepareUpdate("CLEAR SILENT GRAPH <" + graphIri + ">").execute();
            }
            IRI context = conn.getValueFactory().createIRI(graphIri);
            List<Statement> batch = new ArrayList<>(INSERT_BATCH);
            for (Statement statement : statements) {
                batch.add(statement);
                if (batch.size() == INSERT_BATCH) {
                    commitBatch(conn, batch, context);
                }
            }
            if (!batch.isEmpty()) {
                commitBatch(conn, batch, context);
            }
        }
    }

    private static void commitBatch(RepositoryConnection conn, List<Statement> batch, IRI context) {
        conn.begin();
        conn.add(batch, context);
        conn.commit();
        batch.clear();
    }

    @Override
    public void clear(String contextName) {
        String graphIri = remoteGraph(contextName);
        if (graphStoreClient.supported()) {
            graphStoreClient.delete(graphIri);
        } else {
            try (RepositoryConnection conn = getRepository().getConnection()) {
                conn.prepareUpdate("CLEAR SILENT GRAPH <" + graphIri + ">").execute();
            }
        }
        invalidateGraphCache();
    }

    /**
     * Drop every graph of this scenario.
     */
    public void clearScenario() {
        contextNames().forEach(this::clear);
        invalidateGraphCache();
    }

    @Override
    public void add(String contextName, String objNs, String objType, PropertyBags objects) {
        // One transaction, therefore one request: in autocommit an rdf4j SPARQL connection sends an INSERT DATA
        // for every single statement.
        try (RepositoryConnection conn = getRepository().getConnection()) {
            conn.begin(IsolationLevels.NONE);
            addObjects(conn, contextName, objNs, objType, objects);
            conn.commit();
        }
        invalidateGraphCache();
    }

    @Override
    public String add(String contextName, String objNs, String objType, PropertyBag object) {
        try (RepositoryConnection conn = getRepository().getConnection()) {
            conn.begin(IsolationLevels.NONE);
            String id = addObject(conn, contextName, objNs, objType, object);
            conn.commit();
            invalidateGraphCache();
            return id;
        }
    }

    @Override
    public void add(TripleStore source) {
        Objects.requireNonNull(source);
        if (source instanceof TripleStoreRDF4J rdf4j) {
            Repository sourceRepo = rdf4j.getRepository();
            try (RepositoryConnection sourceConn = sourceRepo.getConnection()) {
                for (Resource sourceContext : sourceConn.getContextIDs()) {
                    List<Statement> statements = new ArrayList<>();
                    sourceConn.getStatements(null, null, null, sourceContext).forEach(statements::add);
                    writeGraph(remoteGraph(sourceContext.stringValue()), statements, true);
                }
            }
            rdf4j.getNamespaces().forEach(ns -> namespaces().put(ns.getPrefix(), ns.getNamespace()));
        } else {
            throw new TripleStoreException(String.format("Add to %s from source %s is not supported",
                    getImplementationName(), source.getImplementationName()));
        }
        invalidateGraphCache();
    }

    @Override
    public void update(String queryText) {
        // No USING / USING NAMED is added: an update of this store targets the explicit graphs its text names,
        // which is what the CGMES "-update" catalog does.
        try (RepositoryConnection conn = getRepository().getConnection()) {
            conn.prepareUpdate(adjustedQuery(queryText)).execute();
        }
        invalidateGraphCache();
    }

    // ---------------------------------------------------------------- reads

    @Override
    public void write(DataSource ds) {
        contextNames().forEach(name -> write(ds, name));
    }

    @Override
    public void write(DataSource ds, String contextName) {
        List<Statement> statements = new ArrayList<>();
        StatementCollector collector = new StatementCollector(statements);
        if (!graphStoreClient.supported()) {
            fetchGraphWithConstruct(contextName, collector);
        } else if (!graphStoreClient.get(remoteGraph(contextName), collector)) {
            LOGGER.warn("Graph {} of scenario {} is not in the database", contextName, scenario);
            return;
        }
        // Statements that arrive over the Graph Store Protocol carry no context - the graph they belong to is the
        // request, not part of the body - but the powsybl RDF/XML writer reports the context of every statement.
        Model model = new LinkedHashModel();
        IRI context = getRepository().getValueFactory()
                .createIRI(ScenarioGraphNames.CONTEXTS, ScenarioGraphNames.localName(contextName));
        statements.forEach(st -> model.add(st.getSubject(), st.getPredicate(), st.getObject(), context));
        namespaces().forEach(model::setNamespace);
        write(model, outputStream(ds, contextName));
    }

    private void fetchGraphWithConstruct(String contextName, StatementCollector collector) {
        try (RepositoryConnection conn = getRepository().getConnection()) {
            conn.prepareGraphQuery("CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <"
                    + remoteGraph(contextName) + "> { ?s ?p ?o } }").evaluate(collector);
        }
    }

    @Override
    public void print(PrintStream out) {
        out.println("TripleStore on the SPARQL endpoint " + endpoint.queryUrl() + ", scenario " + scenario
                + ". Graph names and sizes");
        try (RepositoryConnection conn = getRepository().getConnection()) {
            for (IRI graph : graphs(conn)) {
                out.println("    " + localContextName(graph.stringValue()) + " : " + statementCount(conn, graph));
            }
        }
    }

    private static long statementCount(RepositoryConnection conn, IRI graph) {
        TupleQuery q = conn.prepareTupleQuery("SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + graph.stringValue()
                + "> { ?s ?p ?o } }");
        try (var result = q.evaluate()) {
            if (result.hasNext()) {
                Value n = result.next().getValue("n");
                return n == null ? 0L : Long.parseLong(n.stringValue());
            }
        }
        return 0L;
    }

    @Override
    public void close() {
        if (ownsClient) {
            graphStoreClient.close();
            super.close();
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(TripleStoreRDF4JSparql.class);
}
