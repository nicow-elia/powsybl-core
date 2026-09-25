/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.diff.CgmesDiffImport;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.DifferenceSink;
import com.powsybl.commons.report.ReportNode;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Writes difference models into a scenario of an RDF database, as immutable graphs plus one metadata node each.
 *
 * <p>This is the {@code DifferenceSink} of the export pipeline for a database. A change recorded on a network
 * becomes one difference model per profile; each of them is stored as two named graphs &mdash; the state after the
 * change and the state before it &mdash; and a node in the metadata graph that says what they are and which model
 * they apply on.</p>
 *
 * <h2>What makes the write safe</h2>
 * <p>Three rules are enforced, and they are enforced <em>by the write itself</em> rather than by a check before
 * it, because two clients recording changes on the same base model is normal and a check-then-write would let both
 * of them through:</p>
 * <ol>
 *   <li>a difference supersedes exactly one model, which is stored in this scenario and describes the same
 *       profile;</li>
 *   <li>that model has no successor yet, so the chain of a profile stays linear;</li>
 *   <li>the identifier of the difference is new.</li>
 * </ol>
 * <p>All three are {@code FILTER} conditions of a single {@code INSERT … WHERE} request that also carries the data
 * and the metadata. One SPARQL UPDATE request is one transaction on both backends, so a reader sees either nothing
 * or a complete, referenced difference, and a second writer on the same base gets nothing written and is told so.
 * The checks are also run <em>before</em> the write, so that the normal failure has a message naming the rule
 * rather than "the guard did not hold".</p>
 *
 * <p>A difference larger than {@value #SINGLE_REQUEST_MAX_STATEMENTS} statements would make that one request very
 * large, so its data graphs are uploaded first and the guarded request then carries the metadata alone. That is
 * safe for the same reason the design is: a data graph no node refers to is invisible to every reader, and it is
 * dropped again when the guard refuses.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class RdfDbDifferenceSink implements DifferenceSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(RdfDbDifferenceSink.class);

    /**
     * Above this many statements the data graphs are uploaded before the guarded metadata write.
     *
     * <p>Low on purpose: rdf4j's SPARQL update parser is quadratic in the number of template triples (probe
     * {@code scratchpad/probes/perf16/UpdateParseProbe.java}: 3 732 patterns 95 ms, 12 440 &rarr; 1 000 ms,
     * 24 880 &rarr; 3 860 ms; {@code INSERT DATA} and the Graph Store Protocol are linear at ~5 &micro;s per
     * triple), so the data goes through the Graph Store Protocol above 1 000 statements and the guarded request
     * stays small.</p>
     */
    public static final int SINGLE_REQUEST_MAX_STATEMENTS = 1_000;

    private final RdfDbConnection connection;
    private final String scenario;
    private final ModelCatalog catalog;
    private final ReportNode reportNode;
    private final List<StoredModel> stored = new ArrayList<>();
    private Runnable beforeMetadataWrite;
    private SnapshotWrite snapshotWrite;

    /**
     * The snapshot node a versioned write carries alongside its difference nodes.
     *
     * <p>{@code SnapshotCatalog.putDiff} fills this in, and the whole point of it living here is that the snapshot
     * node, its difference members and their data graphs then go into the <em>same</em> guarded request: a reader
     * never sees a snapshot whose members are missing, and a losing writer leaves nothing behind.</p>
     *
     * @param iri          the IRI of the snapshot node
     * @param version      the version label
     * @param timestep     the canonical timestep
     * @param timestepLabel the {@code HH:MM} rendering of the timestep, for display only
     * @param parent       the IRI of the parent snapshot
     * @param edge         {@code pdb:VersionEdge} or {@code pdb:TimestepEdge}
     * @param depth        the depth of the new snapshot
     * @param state        the effective model per profile at the new snapshot
     * @param fullModels   the models with a full graph the new snapshot owns, per profile
     * @param timestepRoot the root snapshot of the new snapshot's timestep
     * @param parentStates the parent states the write is guarded against, per profile
     * @param uniqueTimestep whether the write must fail if the timestep already exists (a new timestep root)
     * @param description  the free text of the writer, or {@code null}
     */
    record SnapshotWrite(String iri, String version, String timestep, String timestepLabel, String parent,
                         String edge,
                         int depth, Map<CgmesSubset, String> state,
                         Map<CgmesSubset, String> fullModels, String timestepRoot,
                         Map<CgmesSubset, String> parentStates, boolean uniqueTimestep, String description) {
    }

    /**
     * Whether the model-level "one successor per stored model" rule applies to this write.
     *
     * <p>It applies to an <em>unversioned</em> write and to nothing else. A scenario without snapshots has no other
     * way of keeping the chain of a profile linear, so the rule is the chain. A versioned write is guarded on the
     * snapshot instead &mdash; one {@code pdb:VersionEdge} child per snapshot, a unique {@code (timestep, version)},
     * and the parent's {@code pdb:state} unchanged &mdash; and those guards subsume it.</p>
     *
     * <p>Keeping it for versioned writes would be worse than redundant: it would be wrong. Every timestep of a day
     * is "the base plus these differences", so the base steady-state model legitimately has one successor per
     * timestep; and once one of them exists, the model-level rule would refuse the <em>next base version</em> of
     * that profile and freeze the base chain for the rest of the day. Versions are the inner dimension precisely
     * so that they keep growing while timesteps fan out.</p>
     */
    private boolean modelChainMustStayLinear() {
        return snapshotWrite == null;
    }

    /**
     * Carry a snapshot node in the next write.
     *
     * @param snapshot what to write, or {@code null} for the unversioned behaviour
     */
    void writeInto(SnapshotWrite snapshot) {
        this.snapshotWrite = snapshot;
    }

    /**
     * A sink writing into one scenario, reporting nowhere.
     *
     * @param connection the open connection
     * @param scenario   the scenario the differences belong to. A difference never crosses scenarios: the model it
     *                   supersedes has to be stored in this one
     * @throws RdfDbException if the scenario name is blank
     */
    public RdfDbDifferenceSink(RdfDbConnection connection, String scenario) {
        this(connection, scenario, ReportNode.NO_OP);
    }

    /**
     * A sink writing into one scenario.
     *
     * @param connection the open connection
     * @param scenario   the scenario the differences belong to
     * @param reportNode where what was written, and which declared dependency the scenario does not hold, is
     *                   reported. {@code null} is the same as {@link ReportNode#NO_OP}
     * @throws RdfDbException if the scenario name is blank
     */
    public RdfDbDifferenceSink(RdfDbConnection connection, String scenario, ReportNode reportNode) {
        this.connection = Objects.requireNonNull(connection);
        this.scenario = RdfDbNames.checkScenario(scenario);
        this.catalog = connection.catalog(scenario);
        this.reportNode = reportNode == null ? ReportNode.NO_OP : reportNode;
    }

    /**
     * @return the scenario this sink writes into
     */
    public String scenario() {
        return scenario;
    }

    /**
     * @return the nodes this sink instance has written, in the order it wrote them
     */
    public List<StoredModel> stored() {
        return List.copyOf(stored);
    }

    /**
     * A hook that runs between the two phases of a large write, so that a test can make a concurrent writer win.
     *
     * @param hook what to run, or {@code null} for nothing
     */
    void beforeMetadataWrite(Runnable hook) {
        this.beforeMetadataWrite = hook;
    }

    @Override
    public void accept(DifferenceModel model) {
        accept(new DifferenceModelSet(List.of(model)));
    }

    @Override
    public void accept(DifferenceModelSet set) {
        Objects.requireNonNull(set);
        List<DifferenceModel> models = set.models().values().stream().filter(m -> !m.isEmpty()).toList();
        if (models.isEmpty()) {
            return;
        }
        Set<String> knownIds = new LinkedHashSet<>();
        List<Planned> planned = plan(models, knownIds);
        long statements = planned.stream().mapToLong(p -> p.forward.size() + p.reverse.size()).sum();
        ZonedDateTime now = ZonedDateTime.now();

        List<String> uploaded = List.of();
        if (statements > SINGLE_REQUEST_MAX_STATEMENTS) {
            uploaded = uploadDataGraphs(planned);
        }
        if (beforeMetadataWrite != null) {
            beforeMetadataWrite.run();
        }
        boolean written = false;
        try {
            connection.sparql(scenario).update(guardedWrite(planned, now, uploaded.isEmpty()));
            written = true;
        } finally {
            if (!written) {
                // The data graphs of the first phase are unreferenced, and a graph nothing refers to is invisible
                // to every reader; dropping them anyway keeps the database from growing on repeated failures
                catalog.dropGraphs(uploaded);
            }
        }
        // Read back: the guard is silent when it refuses, which is the price of doing the checks inside the write.
        // One request for every model of the set, and the same rows answer both "was it written" and "what does
        // the node say now"
        Map<String, StoredModel> nodes =
                catalog.models(planned.stream().map(p -> p.header.id()).toList());
        List<String> missing = planned.stream()
                .map(p -> p.header.id())
                .filter(id -> !nodes.containsKey(id))
                .toList();
        if (!missing.isEmpty()) {
            catalog.dropGraphs(uploaded);
            throw new RdfDbConflictException(diagnose(planned, missing));
        }
        planned.forEach(p -> stored.add(nodes.get(p.header.id())));
        report(planned, knownIds);
        LOGGER.info("Stored {} difference model(s) in scenario '{}' of {}: {}",
                planned.size(), scenario, connection.database(),
                planned.stream().map(p -> p.header.id()).toList());
    }

    /**
     * Say what was written, and warn about every declared dependency the scenario does not hold.
     *
     * <p>A missing {@code md:Model.DependentOn} is deliberately not an error: a steady state difference depends on
     * the equipment model it was recorded against, and a database that only ever saw steady state files is a
     * perfectly ordinary way to use this. The caller is told, and that is all.</p>
     *
     * <p>No request of its own: the declared dependencies were part of the one pre-check query, so what is known
     * about them is already here.</p>
     */
    private void report(List<Planned> planned, Set<String> known) {
        planned.forEach(p -> RdfDbReports.storedDifferenceReport(reportNode, p.header.id(), p.header.subset(),
                scenario));
        for (Planned p : planned) {
            p.header.dependentOn().stream().filter(id -> !known.contains(id))
                    .forEach(id -> RdfDbReports.dependencyNotStoredReport(reportNode, p.header.id(), id, scenario));
        }
    }

    // ------------------------------------------------------------------ checking

    /** One difference model with everything the write needs, resolved against the catalogue. */
    private static final class Planned {
        private final DifferenceModelHeader header;
        private final StoredModel base;
        private final List<CgmesStatement> forward;
        private final List<CgmesStatement> reverse;
        private final boolean fast;
        private final boolean variantSafe;
        private final String forwardGraph;
        private final String reverseGraph;

        private Planned(DifferenceModelHeader header, StoredModel base, DifferenceModel model, boolean fast,
                        boolean variantSafe, String forwardGraph, String reverseGraph) {
            this.header = header;
            this.base = base;
            this.forward = model.forward();
            this.reverse = model.reverse();
            this.fast = fast;
            this.variantSafe = variantSafe;
            this.forwardGraph = forwardGraph;
            this.reverseGraph = reverseGraph;
        }
    }

    /**
     * Resolve every model of the set against the catalogue and check the rules, in one request.
     *
     * @param models the difference models to write
     * @param known  filled with the identifiers the scenario holds, so that the report afterwards needs no query
     */
    private List<Planned> plan(List<DifferenceModel> models, Set<String> known) {
        Set<String> ids = new LinkedHashSet<>();
        for (DifferenceModel model : models) {
            DifferenceModelHeader header = model.header();
            if (header.supersedes().size() != 1) {
                throw new RdfDbConflictException("difference model " + header.id() + " must supersede exactly one"
                        + " stored model, got " + header.supersedes() + "; a stored difference is a version of one"
                        + " model of one profile");
            }
            ids.add(header.supersedes().get(0));
            ids.add(header.id());
            ids.addAll(header.dependentOn());
        }
        ModelCatalog.WriteCheck check = catalog.writeCheck(ids);
        known.clear();
        known.addAll(check.models().keySet());

        List<Planned> planned = new ArrayList<>();
        for (DifferenceModel model : models) {
            DifferenceModelHeader header = model.header();
            String baseId = header.supersedes().get(0);
            StoredModel base = check.models().get(baseId);
            checkBase(header, baseId, base);
            if (modelChainMustStayLinear()) {
                checkNoSuccessor(header, baseId, check.successors().getOrDefault(baseId, List.of()).stream()
                        .filter(successor -> successor.subset() == header.subset())
                        .map(ModelCatalog.Successor::id).toList());
            }
            if (check.models().containsKey(header.id())) {
                throw new RdfDbConflictException("difference model " + header.id() + " is already stored in scenario"
                        + " '" + scenario + "'");
            }
            if (!base.cimNamespace().equals(header.cimNamespace())) {
                throw new RdfDbException("difference model " + header.id() + " is written in the CIM namespace "
                        + header.cimNamespace() + " but the model it applies on, " + baseId + ", is written in "
                        + base.cimNamespace());
            }
            boolean fast = FastRouteCapabilities.check(new DifferenceModelSet(List.of(model)))
                    .route() == CgmesDiffImport.Route.FAST;
            // The network free half of the variant verdict, recorded once here so that a planner can refuse a
            // path without a network at hand. The network aware half runs at apply time either way
            boolean variantSafe = fast && FastRouteCapabilities.checkVariantSafe(
                    new DifferenceModelSet(List.of(model))).route() == CgmesDiffImport.Route.FAST;
            planned.add(new Planned(header, base, model, fast, variantSafe,
                    RdfDbNames.forwardGraph(scenario, header.id()),
                    RdfDbNames.reverseGraph(scenario, header.id())));
        }
        return planned;
    }

    private void checkBase(DifferenceModelHeader header, String baseId, StoredModel base) {
        if (base == null) {
            String where = catalog.scenariosOf(baseId).isEmpty() ? ""
                    : " (it is stored in scenario " + catalog.scenariosOf(baseId) + "); diffs never cross scenarios";
            throw new RdfDbConflictException("difference model " + header.id() + " supersedes " + baseId
                    + ", which is not stored in scenario '" + scenario + "'" + where);
        }
        if (base.subset() != header.subset()) {
            throw new RdfDbConflictException("difference model " + header.id() + " describes the "
                    + header.subset().getIdentifier() + " profile but supersedes " + baseId + ", which describes the "
                    + base.subset().getIdentifier() + " profile");
        }
    }

    private void checkNoSuccessor(DifferenceModelHeader header, String baseId, List<String> successors) {
        if (!successors.isEmpty()) {
            throw new RdfDbConflictException("difference model " + header.id() + " supersedes " + baseId
                    + ", which is already superseded by " + successors + " in scenario '" + scenario + "': the "
                    + header.subset().getIdentifier() + " chain of a scenario is linear, so load the head and"
                    + " re-record, or address the state you want with a SnapshotRef");
        }
    }

    /** Re-run the checks against the current catalogue, to say which rule a silent guard refused on. */
    private String diagnose(List<Planned> planned, List<String> missing) {
        try {
            plan(planned.stream().map(p -> new DifferenceModel(p.header, p.forward, p.reverse, List.of())).toList(),
                    new LinkedHashSet<>());
        } catch (RdfDbException e) {
            return "the difference model(s) " + missing + " were not stored: " + e.getMessage();
        }
        return "the difference model(s) " + missing + " were not stored in scenario '" + scenario + "' although"
                + " every rule holds now: another writer stored a difference on the same model between the check"
                + " and the write. Load the head and record the change again";
    }

    // ------------------------------------------------------------------ writing

    private List<String> uploadDataGraphs(List<Planned> planned) {
        ValueFactory factory = connection.sparql(scenario).valueFactory();
        List<String> written = new ArrayList<>();
        for (Planned p : planned) {
            // An empty side is not uploaded (a Graph Store PUT of nothing is refused): a quad store keeps no empty
            // named graph, so an absent graph is what the single-request route leaves behind for it as well
            if (!p.forward.isEmpty()) {
                connection.writeGraph(scenario, p.forwardGraph, statements(factory, p, p.forward));
                written.add(p.forwardGraph);
            }
            if (!p.reverse.isEmpty()) {
                connection.writeGraph(scenario, p.reverseGraph, statements(factory, p, p.reverse));
                written.add(p.reverseGraph);
            }
        }
        return written;
    }

    private static List<Statement> statements(ValueFactory factory, Planned p, List<CgmesStatement> statements) {
        return statements.stream()
                .map(s -> StatementCodec.toStatement(factory, s, p.base.subjectBase(), p.base.cimNamespace()))
                .toList();
    }

    /**
     * The one request that carries the data, the metadata and the rules.
     *
     * @param withData whether the data graphs are part of this request, or were uploaded before it
     */
    private String guardedWrite(List<Planned> planned, ZonedDateTime now, boolean withData) {
        String meta = SparqlText.iri(catalog.metaGraph());
        StringBuilder insert = new StringBuilder(RdfDbVocabulary.PREFIXES).append("INSERT {");
        for (Planned p : planned) {
            if (withData) {
                appendGraph(insert, p.forwardGraph, p, p.forward);
                appendGraph(insert, p.reverseGraph, p, p.reverse);
            }
            insert.append(" GRAPH ").append(meta).append(" { ");
            appendNode(insert, p, now);
            insert.append(" } ");
        }
        if (snapshotWrite != null) {
            insert.append(" GRAPH ").append(meta).append(" { ");
            appendSnapshotNode(insert, planned, now);
            insert.append(" } ");
        }
        insert.append("} WHERE {");
        appendSnapshotGuards(insert, meta);
        int i = 0;
        for (Planned p : planned) {
            i++;
            String id = SparqlText.iri(p.header.id());
            String base = SparqlText.iri(p.base.id());
            String subset = SparqlText.str(p.header.subset().getIdentifier());
            insert.append(" FILTER NOT EXISTS { GRAPH ").append(meta).append(" { ").append(id)
                    .append(" ?p").append(i).append(" ?o").append(i).append(" } }")
                    .append(" FILTER EXISTS { GRAPH ").append(meta).append(" { ").append(base)
                    .append(" pdb:subset ").append(subset).append(" } }");
            if (modelChainMustStayLinear()) {
                // Only an unversioned scenario needs this: see modelChainMustStayLinear()
                insert.append(" FILTER NOT EXISTS { GRAPH ").append(meta).append(" { ?x").append(i)
                        .append(" md:Model.Supersedes ").append(base).append(" ; pdb:subset ").append(subset)
                        .append(" } }");
            }
        }
        insert.append(" }");
        return insert.toString();
    }

    private static void appendGraph(StringBuilder query, String graphIri, Planned p,
                                    List<CgmesStatement> statements) {
        if (statements.isEmpty()) {
            return;
        }
        query.append(" GRAPH ").append(SparqlText.iri(graphIri)).append(" {");
        for (CgmesStatement statement : statements) {
            query.append(' ').append(StatementCodec.triple(statement, p.base.subjectBase(), p.base.cimNamespace()));
        }
        query.append(" }");
    }

    private void appendNode(StringBuilder query, Planned p, ZonedDateTime now) {
        DifferenceModelHeader header = p.header;
        query.append(SparqlText.iri(header.id())).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.RDF_TYPE)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.DIFFERENCE_MODEL)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.KIND)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.DIFF)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SUBSET)).append(' ')
                .append(SparqlText.str(header.subset().getIdentifier())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SCENARIO)).append(' ')
                .append(SparqlText.str(scenario)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.FORWARD_GRAPH)).append(' ')
                .append(SparqlText.iri(p.forwardGraph)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.REVERSE_GRAPH)).append(' ')
                .append(SparqlText.iri(p.reverseGraph)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.FAST_PREDICATES_ONLY)).append(' ')
                .append(SparqlText.bool(p.fast)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.VARIANT_SAFE)).append(' ')
                .append(SparqlText.bool(p.variantSafe)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.TRIPLE_COUNT)).append(' ')
                .append(SparqlText.integer((long) p.forward.size() + p.reverse.size())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.CHAIN_DEPTH)).append(' ')
                .append(SparqlText.integer(p.base.chainDepth() + 1L)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SUBJECT_BASE)).append(' ')
                .append(SparqlText.str(p.base.subjectBase())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.CIM_NAMESPACE)).append(' ')
                .append(SparqlText.str(p.base.cimNamespace())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.CREATED)).append(' ')
                .append(SparqlText.dateTime(now)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.MODEL_VERSION)).append(' ')
                .append(SparqlText.str(String.valueOf(header.version())));
        if (snapshotWrite != null) {
            query.append(" ; ").append(SparqlText.iri(RdfDbVocabulary.SNAPSHOT)).append(' ')
                    .append(SparqlText.iri(snapshotWrite.iri()));
        }
        appendOptional(query, RdfDbVocabulary.MODEL_SCENARIO_TIME, header.scenarioTime());
        appendOptional(query, RdfDbVocabulary.MODEL_CREATED, header.created());
        appendOptionalString(query, RdfDbVocabulary.MODEL_DESCRIPTION, header.description());
        appendOptionalString(query, RdfDbVocabulary.MODEL_MODELING_AUTHORITY_SET, header.modelingAuthoritySet());
        // A literal, not an IRI: a CGMES model header carries md:Model.profile as text, ModelCatalog copies it
        // as text off the instance file, and a node of either kind has to answer the same SPARQL filter
        header.profiles().forEach(profile -> appendOptionalString(query, RdfDbVocabulary.MODEL_PROFILE, profile));
        header.dependentOn().forEach(id -> appendIri(query, RdfDbVocabulary.MODEL_DEPENDENT_ON, id));
        header.supersedes().forEach(id -> appendIri(query, RdfDbVocabulary.MODEL_SUPERSEDES, id));
        query.append(" .");
    }

    /**
     * The snapshot node of a versioned write, in the same {@code INSERT} block as its members.
     *
     * <p>It carries neither of the two booleans earlier releases wrote beside its links:
     * {@link RdfDbVocabulary#FAST_PREDICATES_ONLY} is written on each member a few lines above and a reader takes
     * the conjunction ({@link SnapshotInfo#fast()}), and a difference snapshot has no {@link
     * RdfDbVocabulary#FULL_MODELS} link at all until a {@link Checkpoint} gives it one, which is what
     * {@link SnapshotInfo#hasFull()} reads.</p>
     */
    private void appendSnapshotNode(StringBuilder query, List<Planned> planned, ZonedDateTime now) {
        SnapshotWrite s = snapshotWrite;
        query.append(SparqlText.iri(s.iri())).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.RDF_TYPE)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.SNAPSHOT_CLASS)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SCENARIO)).append(' ')
                .append(SparqlText.str(scenario)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.VERSION)).append(' ')
                .append(SparqlText.str(s.version())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.TIMESTEP)).append(' ')
                .append(SparqlText.str(s.timestep())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.TIMESTEP_LABEL)).append(' ')
                .append(SparqlText.str(s.timestepLabel())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.KIND)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.DIFF)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.PARENT)).append(' ')
                .append(SparqlText.iri(s.parent())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.EDGE)).append(' ')
                .append(SparqlText.iri(s.edge())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.DEPTH)).append(' ')
                .append(SparqlText.integer(s.depth())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.TIMESTEP_ROOT)).append(' ')
                .append(SparqlText.iri(s.timestepRoot())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.CREATED)).append(' ')
                .append(SparqlText.dateTime(now));
        planned.forEach(p -> query.append(" ; ").append(SparqlText.iri(RdfDbVocabulary.MEMBER)).append(' ')
                .append(SparqlText.iri(p.header.id())));
        s.state().values().forEach(id -> query.append(" ; ").append(SparqlText.iri(RdfDbVocabulary.STATE))
                .append(' ').append(SparqlText.iri(id)));
        s.fullModels().values().forEach(id -> query.append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.FULL_MODELS)).append(' ').append(SparqlText.iri(id)));
        appendOptionalString(query, RdfDbVocabulary.DESCRIPTION, s.description());
        query.append(" .");
    }

    /**
     * The guards a versioned write adds: no fork, no duplicate address, and a parent that is still where the
     * writer thought it was.
     */
    private void appendSnapshotGuards(StringBuilder query, String meta) {
        if (snapshotWrite == null) {
            return;
        }
        SnapshotWrite s = snapshotWrite;
        String parent = SparqlText.iri(s.parent());
        query.append(" FILTER NOT EXISTS { GRAPH ").append(meta).append(" { ").append(SparqlText.iri(s.iri()))
                .append(" ?ps ?os } }")
                .append(" FILTER NOT EXISTS { GRAPH ").append(meta)
                .append(" { ?ys a pdb:Snapshot ; pdb:timestep ").append(SparqlText.str(s.timestep()))
                .append(" ; pdb:version ").append(SparqlText.str(s.version())).append(" } }")
                .append(" FILTER EXISTS { GRAPH ").append(meta).append(" { ").append(parent)
                .append(" a pdb:Snapshot } }");
        if (RdfDbVocabulary.VERSION_EDGE.equals(s.edge())) {
            query.append(" FILTER NOT EXISTS { GRAPH ").append(meta).append(" { ?cs pdb:parent ").append(parent)
                    .append(" ; pdb:edge pdb:VersionEdge } }");
        }
        if (s.uniqueTimestep()) {
            query.append(" FILTER NOT EXISTS { GRAPH ").append(meta)
                    .append(" { ?ts a pdb:Snapshot ; pdb:timestep ").append(SparqlText.str(s.timestep()))
                    .append(" } }");
        }
        s.parentStates().values().forEach(id -> query.append(" FILTER EXISTS { GRAPH ").append(meta).append(" { ")
                .append(parent).append(" pdb:state ").append(SparqlText.iri(id)).append(" } }"));
    }

    private static void appendOptional(StringBuilder query, String predicate, ZonedDateTime value) {
        if (value != null) {
            query.append(" ; ").append(SparqlText.iri(predicate)).append(' ')
                    .append(SparqlText.str(value.toInstant().toString()));
        }
    }

    private static void appendOptionalString(StringBuilder query, String predicate, String value) {
        if (value != null) {
            query.append(" ; ").append(SparqlText.iri(predicate)).append(' ').append(SparqlText.str(value));
        }
    }

    private static void appendIri(StringBuilder query, String predicate, String value) {
        query.append(" ; ").append(SparqlText.iri(predicate)).append(' ').append(SparqlText.iri(value));
    }
}
