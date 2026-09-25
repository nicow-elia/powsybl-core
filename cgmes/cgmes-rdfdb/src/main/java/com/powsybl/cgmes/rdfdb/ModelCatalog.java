/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.triplestore.CgmesTripleStoreLoader;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The stored models of one scenario: reading the metadata graph, and writing full model nodes into it.
 *
 * <p>The metadata graph is the index of a scenario. Uploading an instance file writes a node here, recording a
 * difference writes a node here, and every reader &mdash; the update planner, the materializer, a user asking what
 * the database holds &mdash; navigates through these nodes and never guesses the name of a data graph. That is
 * what makes the graph naming of {@link RdfDbNames} an implementation detail, and it is what makes a half-written
 * difference invisible: a data graph nothing refers to is a graph nobody looks at.</p>
 *
 * <p>One catalogue is bound to one scenario and every query it sends names that scenario's metadata graph
 * explicitly. Isolation between scenarios is therefore by construction rather than by a filter somebody could
 * forget: there is no query in this class that could return a model of another scenario, except the deliberate
 * diagnostic {@link #scenarioOf(String)}.</p>
 *
 * <p><strong>Chains.</strong> Inside a scenario, {@code md:Model.Supersedes} is the version chain of a profile: a
 * difference supersedes the model it applies on, and a model has at most one successor per profile (the rule the
 * sink enforces on write). {@link #chainDown(String)} walks that chain in one request with a property path.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class ModelCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelCatalog.class);

    /**
     * What keeps the copies a checkpoint folded out of the catalogue of stored models.
     *
     * <p>A materialised graph is a snapshot's private start graph: it carries the statements of a model the
     * database already holds as a chain of differences, under a node of its own. It is not a stored model, and
     * counting it as one would give a profile two full models and, with them, two heads.</p>
     */
    private static final String NOT_MATERIALIZED = "FILTER(?k != pdb:Materialized) ";

    private final RdfDbConnection connection;
    private final String scenario;
    private final String metaGraph;

    ModelCatalog(RdfDbConnection connection, String scenario) {
        this.connection = Objects.requireNonNull(connection);
        this.scenario = RdfDbNames.checkScenario(scenario);
        this.metaGraph = RdfDbNames.metaGraph(scenario);
    }

    /**
     * @return the scenario this catalogue describes
     */
    public String scenario() {
        return scenario;
    }

    /**
     * @return the IRI of the metadata graph of the scenario
     */
    public String metaGraph() {
        return metaGraph;
    }

    private SparqlAccess sparql() {
        return connection.sparql(scenario);
    }

    private String graphClause() {
        return " GRAPH " + SparqlText.iri(metaGraph) + " ";
    }

    // ------------------------------------------------------------------ reads

    /**
     * Whether the scenario holds no stored model at all.
     *
     * @return whether the metadata graph is empty
     */
    public boolean isEmpty() {
        return !sparql().ask("ASK {" + graphClause() + "{ ?s ?p ?o } }");
    }

    /**
     * Every stored model of the scenario.
     *
     * <p>One request: the whole metadata graph comes back as subject-predicate-object rows and the nodes are built
     * here. Sorted by profile and then by depth in the chain, so that a listing reads as a history.</p>
     *
     * @return the stored models
     */
    public List<StoredModel> models() {
        List<Map<String, Value>> rows = sparql().select(RdfDbVocabulary.PREFIXES
                + "SELECT ?m ?p ?o WHERE {" + graphClause() + "{ ?m pdb:kind ?k ; ?p ?o " + NOT_MATERIALIZED + "} }");
        return sorted(build(rows, "m", "p", "o").values());
    }

    /**
     * The whole metadata graph of the scenario, in one request, ready to be asked questions.
     *
     * <p>This is what a load, an update and a materialisation use. Every question they have &mdash; heads, chains,
     * full models, a model by identifier &mdash; is a question about the same nodes, and a metadata graph is small
     * by construction, so it is cheaper by an order of magnitude to fetch it once than to send a query per
     * question. {@link CatalogSnapshot} answers them without touching the database again.</p>
     *
     * @return the snapshot
     */
    public CatalogSnapshot snapshot() {
        List<Map<String, Value>> rows = sparql().select(RdfDbVocabulary.PREFIXES
                + "SELECT ?m ?p ?o WHERE {" + graphClause() + "{ ?m pdb:kind ?k ; ?p ?o " + NOT_MATERIALIZED
                + "} }");
        // The snapshot nodes carry pdb:kind too, so they are in these rows already: whether the scenario is
        // versioned is read off them rather than asked for with a request of its own
        boolean versioned = rows.stream().anyMatch(row -> {
            Value p = row.get("p");
            Value o = row.get("o");
            return p != null && o != null && RdfDbVocabulary.RDF_TYPE.equals(p.stringValue())
                    && RdfDbVocabulary.SNAPSHOT_CLASS.equals(o.stringValue());
        });
        return new CatalogSnapshot(scenario, sorted(build(rows, "m", "p", "o").values()), versioned);
    }

    /**
     * One stored model by its identifier, inside this scenario.
     *
     * @param id the CGMES model identifier
     * @return the model, or empty when this scenario holds none of that identifier
     */
    public Optional<StoredModel> model(String id) {
        Objects.requireNonNull(id);
        List<Map<String, Value>> rows = sparql().select(RdfDbVocabulary.PREFIXES
                + "SELECT ?p ?o WHERE {" + graphClause() + "{ " + SparqlText.iri(id) + " ?p ?o } }");
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Node node = new Node(id);
        rows.forEach(row -> node.add(row.get("p"), row.get("o")));
        return node.build(scenario);
    }

    /**
     * The model of a profile that nothing supersedes, that is the current state of that profile.
     *
     * <p>Reads the metadata graph once and answers from it. A caller with more than one question to ask takes a
     * {@link #snapshot()} instead and asks it.</p>
     *
     * @param subset the CGMES profile
     * @return the head model, or empty when the scenario holds no model of that profile
     * @throws RdfDbException if the profile has several heads, which means the chain forked
     */
    public Optional<StoredModel> head(CgmesSubset subset) {
        return snapshot().head(subset);
    }

    /**
     * The full model of a profile, that is the uploaded instance file every difference of that profile descends
     * from.
     *
     * @param subset the CGMES profile
     * @return the full model, or empty
     * @throws RdfDbException if the scenario holds several full models of that profile
     */
    public Optional<StoredModel> full(CgmesSubset subset) {
        return snapshot().full(subset);
    }

    /**
     * The chain from a model down to the full model it descends from.
     *
     * @param headId the identifier of the model to start from
     * @return the model itself first, then its predecessor, down to the full model. Empty when the identifier is
     *         not stored in this scenario
     */
    public List<StoredModel> chainDown(String headId) {
        return snapshot().chainDown(headId);
    }

    /**
     * Several stored models by identifier, in one request.
     *
     * @param ids the identifiers to read
     * @return the models the scenario holds, keyed by identifier; identifiers it does not hold are absent
     */
    Map<String, StoredModel> models(Collection<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Value>> rows = sparql().select(RdfDbVocabulary.PREFIXES
                + "SELECT ?m ?p ?o WHERE {" + graphClause() + "{ VALUES ?m {" + values(ids)
                + " } ?m pdb:kind ?k ; ?p ?o " + NOT_MATERIALIZED + "} }");
        Map<String, StoredModel> models = new LinkedHashMap<>();
        build(rows, "m", "p", "o").forEach((id, node) -> node.build(scenario)
                .ifPresent(model -> models.put(id, model)));
        return models;
    }

    /**
     * Everything a write has to know about the models it touches, in <strong>one</strong> request.
     *
     * <p>A difference write checks four things &mdash; the model it supersedes exists and describes the same
     * profile, that model has no successor yet, the identifier of the difference is new, and the dependencies it
     * declares are stored &mdash; and each of them used to be a query of its own, which made a four-statement write
     * seven round trips. All four are questions about the same handful of nodes, so they are asked together: the
     * union gives the properties of every named node and, separately, whatever supersedes one of them.</p>
     *
     * <p>None of this decides whether the write may happen; the guarded update does. What it buys is a failure
     * message naming the rule that was broken instead of "the guard did not hold".</p>
     *
     * @param ids the identifiers to ask about: the bases, the differences themselves and their declared
     *            dependencies
     * @return the models and the successors
     */
    WriteCheck writeCheck(Collection<String> ids) {
        if (ids.isEmpty()) {
            return new WriteCheck(Map.of(), Map.of());
        }
        List<Map<String, Value>> rows = sparql().select(RdfDbVocabulary.PREFIXES
                + "SELECT ?m ?p ?o ?succ ?succSubset WHERE {" + graphClause() + "{ VALUES ?m {" + values(ids) + " } "
                + "{ ?m pdb:kind ?k ; ?p ?o " + NOT_MATERIALIZED + "} UNION { ?succ md:Model.Supersedes ?m ;"
                + " pdb:subset ?succSubset } } }");

        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, List<Successor>> successors = new LinkedHashMap<>();
        for (Map<String, Value> row : rows) {
            Value m = row.get("m");
            if (m == null) {
                continue;
            }
            Value succ = row.get("succ");
            if (succ != null) {
                successors.computeIfAbsent(m.stringValue(), k -> new ArrayList<>())
                        .add(new Successor(succ.stringValue(), subsetOf(row.get("succSubset"))));
            } else {
                nodes.computeIfAbsent(m.stringValue(), Node::new).add(row.get("p"), row.get("o"));
            }
        }
        Map<String, StoredModel> models = new LinkedHashMap<>();
        nodes.forEach((id, node) -> node.build(scenario).ifPresent(model -> models.put(id, model)));
        return new WriteCheck(models, successors);
    }

    /**
     * What one request told a write about the models it touches.
     *
     * @param models     the stored models, by identifier; an identifier the scenario does not hold is absent
     * @param successors the models that supersede one of the given identifiers, by that identifier
     */
    record WriteCheck(Map<String, StoredModel> models, Map<String, List<Successor>> successors) {
    }

    /**
     * A model that supersedes another one.
     *
     * @param id     the identifier of the successor
     * @param subset the profile it describes, {@code null} when it names one this release does not know
     */
    record Successor(String id, CgmesSubset subset) {
    }

    private static String values(Collection<String> ids) {
        StringBuilder values = new StringBuilder();
        new TreeSet<>(ids).forEach(id -> values.append(' ').append(SparqlText.iri(id)));
        return values.toString();
    }

    /**
     * Whether the scenario holds any difference model at all.
     *
     * <p>One cheap question, asked before a load decides whether it can hand the instance file graphs straight to
     * the conversion or has to materialise a version first.</p>
     *
     * @return whether the scenario holds a difference
     */
    public boolean hasDifferences() {
        return sparql().ask(RdfDbVocabulary.PREFIXES + "ASK {" + graphClause() + "{ ?m pdb:kind pdb:Diff } }");
    }

    /**
     * The scenarios that hold a model of this identifier, for error messages.
     *
     * <p>Diagnostics only, and deliberately the one query of this class that leaves the scenario: when a caller
     * tries to chain a difference onto a model that is not here, the useful part of the message is <em>where it
     * is</em>.</p>
     *
     * @param modelId the model identifier
     * @return the scenarios holding it, sorted
     */
    public List<String> scenariosOf(String modelId) {
        Objects.requireNonNull(modelId);
        List<String> found = new ArrayList<>();
        for (String other : connection.scenarios()) {
            if (other.equals(scenario)) {
                continue;
            }
            boolean holds = connection.sparql(other).ask(RdfDbVocabulary.PREFIXES + "ASK { GRAPH "
                    + SparqlText.iri(RdfDbNames.metaGraph(other)) + " { " + SparqlText.iri(modelId)
                    + " pdb:kind ?k } }");
            if (holds) {
                found.add(other);
            }
        }
        found.sort(Comparator.naturalOrder());
        return found;
    }

    /**
     * The first other scenario holding a model of this identifier.
     *
     * @param modelId the model identifier
     * @return the scenario, or empty
     */
    public Optional<String> scenarioOf(String modelId) {
        return scenariosOf(modelId).stream().findFirst();
    }

    /**
     * Data graphs of this scenario that no model node refers to.
     *
     * <p>A crash between the two phases of a large difference write leaves such a graph behind. It is harmless
     * &mdash; a reader only ever reaches a graph through a model node &mdash; but it costs space, so it can be
     * listed and dropped.</p>
     *
     * @return the orphaned graph IRIs, sorted
     */
    public List<String> orphanGraphs() {
        List<Map<String, Value>> rows = sparql().select(RdfDbVocabulary.PREFIXES
                + "SELECT DISTINCT ?g WHERE { GRAPH ?g { } FILTER(STRSTARTS(STR(?g), "
                + SparqlText.str(RdfDbNames.diffGraphPrefix(scenario)) + ")) "
                + "FILTER NOT EXISTS {" + graphClause() + "{ ?m pdb:forwardGraph ?g } } "
                + "FILTER NOT EXISTS {" + graphClause() + "{ ?m pdb:reverseGraph ?g } } }");
        return rows.stream().map(row -> row.get("g")).filter(Objects::nonNull).map(Value::stringValue)
                .sorted().toList();
    }

    /**
     * Drop named graphs of this scenario.
     *
     * @param graphIris the graphs to drop; graphs that do not exist are ignored
     */
    void dropGraphs(List<String> graphIris) {
        if (graphIris.isEmpty()) {
            return;
        }
        StringBuilder update = new StringBuilder();
        for (String iri : graphIris) {
            if (update.length() > 0) {
                update.append(" ; ");
            }
            update.append("DROP SILENT GRAPH ").append(SparqlText.iri(iri));
        }
        sparql().update(update.toString());
    }

    // ------------------------------------------------------------------ writes

    /**
     * Register the instance files an upload wrote as full model nodes.
     *
     * <p>Called by {@link RdfDbConnection#loadCgmes}. The CIM namespace and the base IRI come from the upload
     * itself &mdash; they are what the parser used &mdash; so no query has to sniff them back out of the data.</p>
     *
     * @param result what the upload loaded
     */
    public void registerFullModels(CgmesTripleStoreLoader.Result result) {
        Objects.requireNonNull(result);
        register(result.contextNames(), result.cimNamespace(), subjectBaseOf(result.baseName()));
    }

    /**
     * Register named graphs of this scenario as full model nodes.
     *
     * <p>The CIM namespace and the subject base are read off the data, which costs two aggregate queries. The
     * overload taking an upload result knows both already and is the one {@link RdfDbConnection#loadCgmes}
     * uses.</p>
     *
     * @param contextNames the context names of the graphs to register
     */
    public void registerFullModels(List<String> contextNames) {
        Objects.requireNonNull(contextNames);
        if (contextNames.isEmpty()) {
            return;
        }
        register(contextNames, probeCimNamespace(contextNames), probeSubjectBase(contextNames));
    }

    private void register(List<String> contextNames, String cimNamespace, String subjectBase) {
        if (contextNames.isEmpty()) {
            return;
        }
        Map<String, String> graphs = new LinkedHashMap<>();
        contextNames.forEach(name -> graphs.put(name, connection.graphIri(scenario, name)));
        Map<String, Node> headers = readFullModelHeaders(graphs.values());
        Map<String, Long> counts = countStatements(graphs.values());
        ZonedDateTime now = ZonedDateTime.now();

        StringBuilder update = new StringBuilder();
        int registered = 0;
        for (Map.Entry<String, String> entry : graphs.entrySet()) {
            String contextName = entry.getKey();
            String graphIri = entry.getValue();
            Node header = headers.get(graphIri);
            if (header == null) {
                // A graph without a model header cannot be versioned: a difference has to name the model it
                // applies on, and there is none. The upload itself stays valid - the loading layer never needed a
                // header - so this is a warning, and a difference against that graph is refused by the sink.
                LOGGER.warn("Graph {} of scenario '{}' carries no md:FullModel header and is not registered as a"
                        + " stored model; only CGMES instance files with a model header can be versioned",
                        contextName, scenario);
                continue;
            }
            CgmesSubset subset = GraphInfo.subsetOf(contextName);
            long count = counts.getOrDefault(graphIri, -1L);
            appendFullModelNode(update, header, subset, graphIri, count, subjectBase, cimNamespace, now);
            registered++;
        }
        if (registered > 0) {
            sparql().update(update.toString());
        }
    }

    private void appendFullModelNode(StringBuilder update, Node header, CgmesSubset subset, String graphIri,
                                     long tripleCount, String subjectBase, String cimNamespace, ZonedDateTime now) {
        String id = SparqlText.iri(header.id);
        String meta = SparqlText.iri(metaGraph);
        if (update.length() > 0) {
            update.append(" ; ");
        }
        // Idempotent: a re-upload of the same file replaces its node rather than merging into it. Full model
        // graphs of the unversioned flow are mutable, so the node has to follow; a graph a snapshot refers
        // to is written once and is never reached through this path.
        update.append("DELETE { GRAPH ").append(meta).append(" { ").append(id).append(" ?p ?o } }")
                .append(" WHERE { GRAPH ").append(meta).append(" { ").append(id).append(" ?p ?o } } ; ");
        update.append("INSERT DATA { GRAPH ").append(meta).append(" { ").append(id).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.RDF_TYPE)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.FULL_MODEL)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.KIND)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.FULL)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SUBSET)).append(' ')
                .append(SparqlText.str(subset.getIdentifier())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SCENARIO)).append(' ')
                .append(SparqlText.str(scenario)).append(" ; ")
                // A string rather than an IRI: the graph name of the in-process backend is the plain instance file
                // name, and a CGMES file name is not always writable as an IRI (see SparqlText.isWritableIri)
                .append(SparqlText.iri(RdfDbVocabulary.GRAPH)).append(' ')
                .append(SparqlText.str(graphIri)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.CHAIN_DEPTH)).append(' ')
                .append(SparqlText.integer(0)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.TRIPLE_COUNT)).append(' ')
                .append(SparqlText.integer(tripleCount)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SUBJECT_BASE)).append(' ')
                .append(SparqlText.str(subjectBase)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.CIM_NAMESPACE)).append(' ')
                .append(SparqlText.str(cimNamespace)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.CREATED)).append(' ')
                .append(SparqlText.dateTime(now));
        header.headerTerms().forEach((predicate, objects) -> objects.forEach(object -> update.append(" ; ")
                .append(SparqlText.iri(predicate)).append(' ').append(term(object))));
        update.append(" . } }");
    }

    private static String term(Value value) {
        if (value instanceof IRI iri) {
            return SparqlText.iri(iri.stringValue());
        }
        return SparqlText.str(value.stringValue());
    }

    private Map<String, Node> readFullModelHeaders(Collection<String> graphIris) {
        List<Map<String, Value>> rows = sparql().select(RdfDbVocabulary.PREFIXES
                + "SELECT ?g ?m ?p ?o WHERE { " + SparqlText.graphSelector("g", graphIris)
                + "GRAPH ?g { ?m a md:FullModel ; ?p ?o } }");
        Map<String, Map<String, Node>> byGraph = new LinkedHashMap<>();
        for (Map<String, Value> row : rows) {
            Value g = row.get("g");
            Value m = row.get("m");
            if (g == null || m == null) {
                continue;
            }
            byGraph.computeIfAbsent(g.stringValue(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(m.stringValue(), Node::new)
                    .add(row.get("p"), row.get("o"));
        }
        Map<String, Node> headers = new LinkedHashMap<>();
        byGraph.forEach((graph, nodes) -> {
            if (nodes.size() > 1) {
                LOGGER.warn("Graph {} of scenario '{}' carries {} md:FullModel headers; the first by identifier is"
                        + " registered", graph, scenario, nodes.size());
            }
            nodes.values().stream().min(Comparator.comparing(node -> node.id))
                    .ifPresent(node -> headers.put(graph, node));
        });
        return headers;
    }

    private Map<String, Long> countStatements(Collection<String> graphIris) {
        if (connection.memory() != null) {
            // The in-process backend knows the size of a context without counting it, and a SPARQL aggregate over
            // it would be a full scan of every graph of the scenario - the upload has just written them, so this
            // is the one place where asking the store directly is both cheaper and exact
            Repository repository = connection.repository(scenario);
            Map<String, Long> sizes = new LinkedHashMap<>();
            if (repository == null) {
                return sizes;
            }
            try (RepositoryConnection conn = repository.getConnection()) {
                graphIris.forEach(iri -> sizes.put(iri, conn.size(conn.getValueFactory().createIRI(iri))));
            }
            return sizes;
        }
        List<Map<String, Value>> rows = sparql().select(
                "SELECT ?g (COUNT(*) AS ?n) WHERE { " + SparqlText.graphSelector("g", graphIris)
                        + "GRAPH ?g { ?s ?p ?o } } GROUP BY ?g");
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Value> row : rows) {
            Value g = row.get("g");
            Value n = row.get("n");
            if (g != null && n != null) {
                counts.put(g.stringValue(), longOf(n, -1L));
            }
        }
        return counts;
    }

    private String probeCimNamespace(List<String> contextNames) {
        String selector = SparqlText.graphSelector("g", graphIris(contextNames));
        for (String candidate : List.of(CgmesNamespace.CIM_16_NAMESPACE, CgmesNamespace.CIM_100_NAMESPACE)) {
            boolean found = sparql().ask("ASK { " + selector + "GRAPH ?g { ?s a ?t } "
                    + "FILTER(STRSTARTS(STR(?t), " + SparqlText.str(candidate) + ")) }");
            if (found) {
                return candidate;
            }
        }
        throw new RdfDbException("No CGMES data in the graphs " + contextNames + " of scenario '" + scenario + "'");
    }

    private List<String> graphIris(List<String> contextNames) {
        return contextNames.stream().map(name -> connection.graphIri(scenario, name)).toList();
    }

    private String probeSubjectBase(List<String> contextNames) {
        List<Map<String, Value>> rows = sparql().select("SELECT ?s WHERE { "
                + SparqlText.graphSelector("g", graphIris(contextNames))
                + "GRAPH ?g { ?s a ?t } FILTER(CONTAINS(STR(?s), \"#_\")) } LIMIT 1");
        if (rows.isEmpty()) {
            // Nothing but absolute identifiers: statements of a difference on this model are then written with
            // their identifiers verbatim, see StatementCodec
            return "";
        }
        String subject = rows.get(0).get("s").stringValue();
        return subject.substring(0, subject.indexOf("#_") + 1);
    }

    /**
     * The IRI prefix the subjects of a model carry, derived from the base the parser resolved against.
     *
     * <p>A CGMES instance file writes its objects as {@code rdf:ID="_<mRID>"}, which an RDF reader resolves
     * against the base IRI of the document. Resolving a fragment against a base whose path is empty adds the root
     * path first (RFC 3986 section 6.2.3), so {@code http://example} becomes {@code http://example/#_<mRID>}.</p>
     *
     * @param baseName the base the files were read with
     * @return the prefix before {@code _<mRID>}
     */
    static String subjectBaseOf(String baseName) {
        if (baseName.endsWith("#")) {
            return baseName;
        }
        if (baseName.endsWith("/")) {
            return baseName + "#";
        }
        try {
            URI uri = new URI(baseName);
            boolean rootless = uri.getAuthority() != null && (uri.getPath() == null || uri.getPath().isEmpty());
            return baseName + (rootless ? "/#" : "#");
        } catch (URISyntaxException e) {
            throw new RdfDbException("The base \"" + baseName + "\" is not a valid IRI", e);
        }
    }

    // ------------------------------------------------------------------ node building

    private static Map<String, Node> build(List<Map<String, Value>> rows, String subject, String predicate,
                                           String object) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (Map<String, Value> row : rows) {
            Value s = row.get(subject);
            if (s == null) {
                continue;
            }
            nodes.computeIfAbsent(s.stringValue(), Node::new).add(row.get(predicate), row.get(object));
        }
        return nodes;
    }

    private List<StoredModel> sorted(Collection<Node> nodes) {
        List<StoredModel> models = new ArrayList<>();
        nodes.forEach(node -> node.build(scenario).ifPresent(models::add));
        models.sort(Comparator.comparing((StoredModel m) -> m.subset().getIdentifier())
                .thenComparingInt(StoredModel::chainDepth)
                .thenComparing(StoredModel::id));
        return List.copyOf(models);
    }

    private static CgmesSubset subsetOf(Value value) {
        if (value == null) {
            return null;
        }
        String identifier = value.stringValue();
        for (CgmesSubset subset : CgmesSubset.values()) {
            if (subset.getIdentifier().equals(identifier)) {
                return subset;
            }
        }
        return null;
    }

    private static long longOf(Value value, long fallback) {
        try {
            return value instanceof Literal literal ? literal.longValue() : Long.parseLong(value.stringValue());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static ZonedDateTime dateOf(Value value) {
        if (value == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value.stringValue());
        } catch (DateTimeParseException e) {
            LOGGER.debug("Not a date: {}", value.stringValue());
            return null;
        }
    }

    /** The predicate-object pairs of one node of the metadata graph, before it becomes a {@link StoredModel}. */
    private static final class Node {

        private final String id;
        private final Map<String, List<Value>> byPredicate = new LinkedHashMap<>();

        Node(String id) {
            this.id = id;
        }

        void add(Value predicate, Value object) {
            if (predicate == null || object == null) {
                return;
            }
            byPredicate.computeIfAbsent(predicate.stringValue(), k -> new ArrayList<>()).add(object);
        }

        private Value one(String predicate) {
            List<Value> values = byPredicate.get(predicate);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        private String string(String predicate) {
            Value value = one(predicate);
            return value == null ? null : value.stringValue();
        }

        private List<String> strings(String predicate) {
            return byPredicate.getOrDefault(predicate, List.of()).stream().map(Value::stringValue).sorted().toList();
        }

        /** The CGMES header terms of a full model, to be copied verbatim onto its node. */
        Map<String, List<Value>> headerTerms() {
            Map<String, List<Value>> terms = new LinkedHashMap<>();
            byPredicate.forEach((predicate, values) -> {
                if (predicate.startsWith(RdfDbVocabulary.MD_NS) && !RdfDbVocabulary.RDF_TYPE.equals(predicate)) {
                    terms.put(predicate, values);
                }
            });
            return terms;
        }

        /** {@code null} when the node does not carry the flag at all, which an older store never does. */
        private static Boolean variantSafeOf(Value value) {
            return value instanceof Literal literal ? literal.booleanValue() : null;
        }

        Optional<StoredModel> build(String scenario) {
            String kindIri = string(RdfDbVocabulary.KIND);
            if (kindIri == null) {
                // Not a model node: the property path of a chain query reaches whatever Supersedes names, and a
                // model header may name a model this database never saw
                return Optional.empty();
            }
            StoredModel.Kind kind = RdfDbVocabulary.DIFF.equals(kindIri)
                    ? StoredModel.Kind.DIFF : StoredModel.Kind.FULL;
            CgmesSubset subset = subsetOf(one(RdfDbVocabulary.SUBSET));
            if (subset == null) {
                return Optional.empty();
            }
            Value version = one(RdfDbVocabulary.MODEL_VERSION);
            Value fast = one(RdfDbVocabulary.FAST_PREDICATES_ONLY);
            Value count = one(RdfDbVocabulary.TRIPLE_COUNT);
            Value depth = one(RdfDbVocabulary.CHAIN_DEPTH);
            return Optional.of(new StoredModel(scenario, id, subset, kind,
                    string(RdfDbVocabulary.GRAPH),
                    string(RdfDbVocabulary.FORWARD_GRAPH),
                    string(RdfDbVocabulary.REVERSE_GRAPH),
                    version == null ? 1 : (int) longOf(version, 1),
                    string(RdfDbVocabulary.MODEL_DESCRIPTION),
                    dateOf(one(RdfDbVocabulary.MODEL_SCENARIO_TIME)),
                    dateOf(one(RdfDbVocabulary.MODEL_CREATED)),
                    string(RdfDbVocabulary.MODEL_MODELING_AUTHORITY_SET),
                    strings(RdfDbVocabulary.MODEL_PROFILE),
                    strings(RdfDbVocabulary.MODEL_DEPENDENT_ON),
                    strings(RdfDbVocabulary.MODEL_SUPERSEDES),
                    fast instanceof Literal literal && literal.booleanValue(),
                    count == null ? -1L : longOf(count, -1L),
                    string(RdfDbVocabulary.SUBJECT_BASE),
                    string(RdfDbVocabulary.CIM_NAMESPACE),
                    depth == null ? 0 : (int) longOf(depth, 0),
                    variantSafeOf(one(RdfDbVocabulary.VARIANT_SAFE))));
        }
    }
}
