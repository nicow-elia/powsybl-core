/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.TripleStoreNetworkLoader;
import com.powsybl.cgmes.conversion.diff.CgmesDiffImport;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import com.powsybl.triplestore.api.TripleStoreOptions;
import com.powsybl.triplestore.impl.rdf4j.TripleStoreRDF4J;
import com.powsybl.triplestore.impl.rdf4j.sparql.ScenarioGraphNames;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Builds the network of a stored state that no difference can reach, by materialising the data first.
 *
 * <p>The slow route of the database integration, and the one that always works. The base graphs of the scenario
 * are transferred into a local in-memory store &mdash; from the cache when they are already there, and they are
 * the same graphs for every version, which is what makes the cache worth having &mdash; the differences on the way
 * to the target are applied to that store as plain RDF, and the result is handed to the unchanged CGMES
 * conversion. The network that comes out is the network the instance files of that version would have produced,
 * because by the time the conversion runs the store <em>is</em> those files.</p>
 *
 * <p>It is used for a target no in-place update can reach: a difference stating a property no update query reads,
 * a model that is not on the network's chain, a chain longer than the caller allows &mdash; and for a plain load
 * of a scenario that holds differences.</p>
 *
 * <p>The identity of the produced network is registered afterwards, so it says it is at the target models rather
 * than at the instance files it was materialised from.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class RdfDbMaterializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RdfDbMaterializer.class);

    private RdfDbMaterializer() {
    }

    /**
     * Materialise a scenario at a target and convert it.
     *
     * <p>Two requests before the graph transfer, whatever the scenario holds: the caller has already read the
     * metadata graph once and hands the {@link CatalogSnapshot} over, and everything else &mdash; which instance
     * file each profile descends from, which differences lie on the way to the target &mdash; is arithmetic on
     * that list. The second request fetches the statements of every difference of every profile at once.</p>
     *
     * @param db       the open connection
     * @param scenario the scenario
     * @param snapshot the metadata graph of that scenario, read once by the caller
     * @param targets  the stored model to reach per profile; profiles that are not named stay at their full model
     * @param factory  the factory the network is created with
     * @param params   the CGMES import parameters
     * @param rn       where the load reports
     * @return the network and the timings
     */
    static RdfDbNetworkLoader.LoadResult materialize(RdfDbConnection db, String scenario, CatalogSnapshot snapshot,
                                    Map<String, StoredModel> targets, NetworkFactory factory,
                                    Properties params, ReportNode rn) {
        Objects.requireNonNull(db);
        Objects.requireNonNull(snapshot);
        RdfDbNames.checkScenario(scenario);
        if (db.database().queryMode() == RdfDatabase.QueryMode.REMOTE) {
            throw new RdfDbException("Scenario '" + scenario + "' holds difference models, which the REMOTE query"
                    + " mode cannot read: the differences would have to be applied on the server. Load this"
                    + " scenario in LOCAL query mode (RdfDatabase.withQueryMode)");
        }
        // Only what the conversion reads: a custom profile is never part of a network
        List<StoredModel> fullModels = snapshot.models().stream()
                .filter(model -> model.kind() == StoredModel.Kind.FULL && Profiles.isStandard(model.subset()))
                .toList();
        if (fullModels.isEmpty()) {
            throw new RdfDbException("Scenario '" + scenario + "' of " + db.database() + " holds no full model to"
                    + " build a network from");
        }
        Map<String, StoredModel> chainTargets = Profiles.map(targets);
        Map<String, List<StoredModel>> paths = pathsTo(snapshot, chainTargets);

        CgmesImport importer = TripleStoreNetworkLoader.importer();
        TripleStoreOptions options = importer.tripleStoreOptions(params);
        TripleStoreRDF4J local = new TripleStoreRDF4J(new SailRepository(new MemoryStore()), options);
        boolean handedOver = false;
        try {
            Map<String, String> localToRemote = new LinkedHashMap<>();
            Map<String, String> contextOfSubset = Profiles.map();
            List<GraphInfo> graphs = new ArrayList<>();
            for (StoredModel model : fullModels) {
                String contextName = db.localContextName(scenario, model.graph());
                if (contextName == null) {
                    LOGGER.warn("Full model {} of scenario '{}' names the graph {}, which is not an instance file"
                            + " graph of that scenario and is skipped", model.id(), scenario, model.graph());
                    continue;
                }
                String localName = localContext(contextName);
                localToRemote.put(localName, model.graph());
                contextOfSubset.put(model.subset(), localName);
                // The provenance of the result is what was actually transferred, which is known here; asking the
                // database for the graphs of the scenario again would be one more round trip for the same answer
                graphs.add(new GraphInfo(scenario, contextName, model.subset(), model.graph()));
            }
            long fetchStart = System.nanoTime();
            GraphFetcher.FetchStatistics fetchStatistics = new GraphFetcher(db, scenario)
                    .fetchInto(local, localToRemote);
            Duration fetchWallClock = Duration.ofNanos(System.nanoTime() - fetchStart);

            long applyStart = System.nanoTime();
            List<StoredModel> allDiffs = new ArrayList<>();
            paths.values().forEach(allDiffs::addAll);
            Map<String, DifferenceModel> byId = RdfDbDiffSource.fetchById(db, allDiffs);
            paths.forEach((subset, path) -> {
                if (path.isEmpty()) {
                    return;
                }
                String contextName = contextOfSubset.get(subset);
                if (contextName == null) {
                    throw new RdfDbException("Scenario '" + scenario + "' holds " + path.size() + " difference(s)"
                            + " of the " + subset + " profile but no full model of it");
                }
                // Folded into one difference first, and applied once. Replacing a property by its final value is
                // the same thing as replacing it once per step of the chain, and it turns a chain of ten into two
                // SPARQL requests instead of two per difference
                StoredModel target = path.get(path.size() - 1);
                List<DifferenceModel> models = path.stream().map(diff -> byId.get(diff.id())).toList();
                DifferenceModel folded = models.size() == 1 ? models.get(0)
                        : DifferenceModel.compose(models, target.toHeader());
                CgmesDiffImport.applyToGraph(local, folded, contextName, target.subjectBase());
            });
            Duration applyDiffs = Duration.ofNanos(System.nanoTime() - applyStart);

            long convertStart = System.nanoTime();
            Network network = TripleStoreNetworkLoader.load(local, factory, params, rn);
            Duration convert = Duration.ofNanos(System.nanoTime() - convertStart);
            handedOver = true;
            register(network, db, scenario, chainTargets, graphs);
            LOGGER.info("Materialised scenario '{}' of {} at {} difference(s)", scenario, db.database(),
                    allDiffs.size());
            // The differences are counted as part of the store phase: they are statements written into the local
            // store, which is what that phase means for a plain load too
            return new RdfDbNetworkLoader.LoadResult(network, LoadStatistics.of(Duration.ZERO, fetchWallClock,
                    fetchStatistics, applyDiffs, Duration.ZERO, convert));
        } finally {
            if (!handedOver) {
                local.close();
            }
        }
    }

    /**
     * Materialise one snapshot and convert it.
     *
     * <p>Per profile rather than per snapshot: each one starts at the nearest ancestor that holds a full graph of
     * <em>that</em> profile and applies the differences below it. That is what makes a checkpoint useful without
     * being a new root, and what makes a timestamp able to store its state variables whole while its steady state
     * is a difference.</p>
     *
     * @param db         the open connection
     * @param scenario   the scenario
     * @param snapshot   the snapshot being built, for the identity and the case date of the result
     * @param plan       where to start per profile and what to apply
     * @param stateModels the stored model of every identifier in {@code plan.targetState()}
     * @param factory    the factory the network is created with
     * @param params     the CGMES import parameters
     * @param rn         where the load reports
     * @return the network, the timings, and the graph of every custom profile of the plan, which the network does
     *         not hold
     */
    static RdfDbNetworkLoader.LoadResult materialize(RdfDbConnection db, String scenario, SnapshotInfo snapshot,
                                    MaterializationPlan plan, Map<String, StoredModel> stateModels,
                                    NetworkFactory factory, Properties params, ReportNode rn) {
        Objects.requireNonNull(db);
        Objects.requireNonNull(plan);
        RdfDbNames.checkScenario(scenario);
        if (db.database().queryMode() == RdfDatabase.QueryMode.REMOTE) {
            throw new RdfDbException("Scenario '" + scenario + "' is versioned, which the REMOTE query mode cannot"
                    + " read: the differences would have to be applied on the server. Load this scenario in LOCAL"
                    + " query mode (RdfDatabase.withQueryMode)");
        }
        CgmesImport importer = TripleStoreNetworkLoader.importer();
        TripleStoreOptions options = importer.tripleStoreOptions(params);
        TripleStoreRDF4J local = new TripleStoreRDF4J(new SailRepository(new MemoryStore()), options);
        boolean handedOver = false;
        try {
            Map<String, String> localToRemote = new LinkedHashMap<>();
            Map<String, String> contextOfSubset = Profiles.map();
            List<GraphInfo> graphs = new ArrayList<>();
            Map<String, String> extraProfiles = Profiles.map();
            int index = 0;
            for (Map.Entry<String, MaterializationPlan.FullSource> entry : plan.startModel().entrySet()) {
                String subset = entry.getKey();
                String graph = entry.getValue().graph();
                if (graph == null) {
                    throw new RdfDbException("the full " + subset + " model "
                            + entry.getValue().modelId() + " of scenario '" + scenario + "' names no graph");
                }
                if (!Profiles.isStandard(subset)) {
                    // A custom profile is always stored whole, so its start model is its state: handed to the
                    // caller as that graph, never fetched into the store the conversion reads every graph of
                    extraProfiles.put(subset, graph);
                    continue;
                }
                // A name the CGMES conversion reads the profile off, and one SPARQL can write as an IRI
                String localName = ScenarioGraphNames.CONTEXTS + "model" + index++ + "_"
                        + subset + ".xml";
                localToRemote.put(localName, graph);
                contextOfSubset.put(subset, localName);
                graphs.add(new GraphInfo(scenario, localName, subset, graph));
            }
            long fetchStart = System.nanoTime();
            GraphFetcher.FetchStatistics fetchStatistics = new GraphFetcher(db, scenario)
                    .fetchInto(local, localToRemote, rebasedBoundary(plan, stateModels, contextOfSubset));
            Duration fetchWallClock = Duration.ofNanos(System.nanoTime() - fetchStart);

            long applyStart = System.nanoTime();
            applySteps(db, local, plan, contextOfSubset);
            Duration applyDiffs = Duration.ofNanos(System.nanoTime() - applyStart);

            long convertStart = System.nanoTime();
            Network network = TripleStoreNetworkLoader.load(local, factory, params, rn);
            Duration convert = Duration.ofNanos(System.nanoTime() - convertStart);
            handedOver = true;
            registerSnapshot(network, db, scenario, snapshot, plan, stateModels, graphs);
            LOGGER.info("Materialised snapshot {} of scenario '{}' from {} difference(s)", snapshot, scenario,
                    plan.steps().size());
            return new RdfDbNetworkLoader.LoadResult(network, LoadStatistics.of(Duration.ZERO, fetchWallClock,
                    fetchStatistics, applyDiffs, Duration.ZERO, convert), extraProfiles);
        } finally {
            if (!handedOver) {
                local.close();
            }
        }
    }

    /**
     * Materialise the snapshots of several modelling authorities into one store and convert them as one network.
     *
     * <p>What a CGM is, built from its IGMs as they are stored: every authority's graphs in one store under
     * context names of their own, the boundary once (the first authority's, which every authority has to name),
     * every graph speaking the first authority's subject base (relative identifiers resolve against the base of
     * the files they were parsed from, and the conversion joins by IRI), each authority's differences applied to
     * its own graphs, and then the precedence: one {@code DELETE} per later authority removes every statement of
     * its graphs whose subject and property an earlier authority's graphs state, so the first one in the list
     * wins. The flat conversion of that store pairs the tie lines on the boundary nodes, exactly as it does for
     * the assembled files.</p>
     *
     * @param db          the open connection
     * @param scenario    the scenario
     * @param snapshots   the snapshot of each authority, in precedence order
     * @param plans       how to build each of them, in the same order
     * @param stateModels the stored model of every identifier in the plans' target states
     * @param owned       the authorities a write-back of the network goes to
     * @param factory     the factory the network is created with
     * @param params      the CGMES import parameters
     * @param rn          where the load reports
     * @return the network and the timings
     */
    static RdfDbNetworkLoader.LoadResult materializeComposed(RdfDbConnection db, String scenario,
                                                             List<SnapshotInfo> snapshots,
                                                             List<MaterializationPlan> plans,
                                                             Map<String, StoredModel> stateModels,
                                                             List<String> owned, NetworkFactory factory,
                                                             Properties params, ReportNode rn) {
        RdfDbNames.checkScenario(scenario);
        if (db.database().queryMode() == RdfDatabase.QueryMode.REMOTE) {
            throw new RdfDbException("Scenario '" + scenario + "' is versioned, which the REMOTE query mode cannot"
                    + " read: a composition is built in a local store. Load it in LOCAL query mode"
                    + " (RdfDatabase.withQueryMode)");
        }
        // The subject base each authority's graphs were parsed with, and the first one's, which the store speaks
        List<String> bases = plans.stream().map(plan -> subjectBase(plan, stateModels)).toList();
        String base = bases.get(0);
        TripleStoreRDF4J local = new TripleStoreRDF4J(new SailRepository(new MemoryStore()),
                TripleStoreNetworkLoader.importer().tripleStoreOptions(params));
        boolean handedOver = false;
        try {
            Map<String, String> localToRemote = new LinkedHashMap<>();
            Map<String, UnaryOperator<Statement>> mappings = new LinkedHashMap<>();
            List<Map<String, String>> contexts = new ArrayList<>();
            List<GraphInfo> graphs = new ArrayList<>();
            for (int i = 0; i < plans.size(); i++) {
                String own = bases.get(i);
                Map<String, String> contextOfSubset = Profiles.map();
                int index = 0;
                for (Map.Entry<String, MaterializationPlan.FullSource> entry : plans.get(i).startModel().entrySet()) {
                    String subset = entry.getKey();
                    if (!Profiles.isStandard(subset)) {
                        continue;
                    }
                    if (Profiles.isBoundary(subset) && i > 0) {
                        checkSameBoundary(plans, i, subset, snapshots);
                        continue;
                    }
                    String localName = ScenarioGraphNames.CONTEXTS + "a" + i + "_model" + index++ + "_" + subset
                            + ".xml";
                    localToRemote.put(localName, entry.getValue().graph());
                    contextOfSubset.put(subset, localName);
                    graphs.add(new GraphInfo(scenario, localName, subset, entry.getValue().graph()));
                    StoredModel model = stateModels.get(entry.getValue().modelId());
                    String parsedWith = Profiles.isBoundary(subset) && model != null ? model.subjectBase() : own;
                    if (!base.isEmpty() && !parsedWith.isEmpty() && !parsedWith.equals(base)) {
                        mappings.put(localName, GraphFetcher.rebase(parsedWith, base));
                    }
                }
                contexts.add(contextOfSubset);
            }
            long fetchStart = System.nanoTime();
            GraphFetcher.FetchStatistics fetchStatistics = new GraphFetcher(db, scenario)
                    .fetchInto(local, localToRemote, mappings);
            Duration fetchWallClock = Duration.ofNanos(System.nanoTime() - fetchStart);

            long applyStart = System.nanoTime();
            Map<String, DifferenceModel> byId = fetchSteps(db, plans);
            for (int i = 0; i < plans.size(); i++) {
                String own = bases.get(i);
                applySteps(local, plans.get(i), contexts.get(i), byId,
                        stored -> stored.equals(own) && !base.isEmpty() ? base : stored);
            }
            for (int i = 1; i < plans.size(); i++) {
                local.update(precedence(contexts.subList(0, i), contexts.get(i)));
            }
            Map<String, String> owners = owners(local, snapshots, contexts, base);
            Duration applyDiffs = Duration.ofNanos(System.nanoTime() - applyStart);

            long convertStart = System.nanoTime();
            Network network = TripleStoreNetworkLoader.load(local, factory, params, rn);
            Duration convert = Duration.ofNanos(System.nanoTime() - convertStart);
            handedOver = true;
            network.setCaseDate(snapshots.get(0).timestamp().atZone(ZoneOffset.UTC));
            Map<String, String> modelIds = Profiles.map();
            plans.get(0).targetState().forEach((subset, id) -> {
                if (Profiles.isStandard(subset)) {
                    modelIds.put(subset, id);
                }
            });
            RdfDbProvenanceImpl provenance = new RdfDbProvenanceImpl(db.database(), scenario, graphs,
                    Instant.now(), modelIds);
            provenance.compose(snapshots, owned, owners);
            network.addExtension(RdfDbProvenance.class, provenance);
            LOGGER.info("Composed {} of scenario '{}': {} difference(s), {} objects owned", snapshots, scenario,
                    byId.size(), owners.size());
            return new RdfDbNetworkLoader.LoadResult(network, LoadStatistics.of(Duration.ZERO, fetchWallClock,
                    fetchStatistics, applyDiffs, Duration.ZERO, convert));
        } finally {
            if (!handedOver) {
                local.close();
            }
        }
    }

    /**
     * Refuse a later authority that names another boundary model than the first one: a CGM has one boundary, and
     * the first authority's is the one fetched.
     */
    private static void checkSameBoundary(List<MaterializationPlan> plans, int i, String subset,
                                          List<SnapshotInfo> snapshots) {
        MaterializationPlan.FullSource first = plans.get(0).startModel().get(subset);
        String mine = plans.get(i).startModel().get(subset).modelId();
        if (first == null || !first.modelId().equals(mine)) {
            throw new RdfDbException("the composition of " + snapshots.get(0) + " and " + snapshots.get(i)
                    + " names two " + subset + " boundaries (" + (first == null ? "none" : first.modelId()) + ", "
                    + mine + "), and a common grid model has one; nothing was loaded");
        }
    }

    /**
     * The precedence of the earlier authorities over one later one: every statement of the later one's graphs
     * whose subject and property a graph of an earlier one states.
     */
    private static String precedence(List<Map<String, String>> earlier, Map<String, String> later) {
        return "DELETE { GRAPH ?gi { ?s ?p ?o } } WHERE { VALUES ?ge {"
                + earlier.stream().flatMap(contexts -> contexts.values().stream())
                        .map(SparqlText::iri).map(iri -> " " + iri).collect(Collectors.joining())
                + " } VALUES ?gi {" + later.values().stream().map(SparqlText::iri).map(iri -> " " + iri)
                        .collect(Collectors.joining())
                + " } GRAPH ?ge { ?s ?p ?x } GRAPH ?gi { ?s ?p ?o } }";
    }

    /**
     * The modelling authority of every object of a composition: the first one whose graphs type it, read off the
     * local store before the conversion, keyed by master resource identifier.
     */
    private static Map<String, String> owners(TripleStoreRDF4J local, List<SnapshotInfo> snapshots,
                                              List<Map<String, String>> contexts, String base) {
        String prefix = base + "_";
        Map<String, String> owners = new HashMap<>();
        try (RepositoryConnection connection = local.getRepository().getConnection()) {
            for (int i = 0; i < contexts.size(); i++) {
                String authority = snapshots.get(i).modellingAuthority();
                Resource[] graphs = contexts.get(i).values().stream().map(Values::iri).toArray(Resource[]::new);
                try (RepositoryResult<Statement> typed = connection.getStatements(null, RDF.TYPE, null, graphs)) {
                    typed.forEach(statement -> {
                        String subject = statement.getSubject().stringValue();
                        if (subject.startsWith(prefix)) {
                            owners.putIfAbsent(subject.substring(prefix.length()), authority);
                        }
                    });
                }
            }
        }
        return owners;
    }

    /**
     * The data of a snapshot on a local store, without converting it.
     *
     * <p>What a file ingestion needs: to say what changed between the parent state and a new instance file, the
     * parent state has to exist as triples somewhere, and that is exactly the first half of a materialisation.
     * Building the network would be wasted work &mdash; and would lose the triples, which are the thing being
     * compared.</p>
     *
     * @param store      the local store, holding one graph per standard profile of the snapshot. The caller
     *                   closes it
     * @param contexts   the local context name per profile
     * @param subjectBase the IRI prefix the subjects in that store carry
     */
    record MaterialisedStore(TripleStoreRDF4J store, Map<String, String> contexts, String subjectBase)
            implements AutoCloseable {

        @Override
        public void close() {
            store.close();
        }
    }

    /**
     * Materialise the data of a snapshot onto a local store and stop there.
     *
     * @param db       the open connection
     * @param scenario the scenario
     * @param plan     where to start per profile and what to apply
     * @param models   the stored model of every identifier in {@code plan.targetState()}, for the subject base
     * @param params   the CGMES import parameters, for the triple store options
     * @return the store and what is in it
     */
    static MaterialisedStore materializeStore(RdfDbConnection db, String scenario, MaterializationPlan plan,
                                              Map<String, StoredModel> models, Properties params) {
        RdfDbNames.checkScenario(scenario);
        CgmesImport importer = TripleStoreNetworkLoader.importer();
        TripleStoreRDF4J local = new TripleStoreRDF4J(new SailRepository(new MemoryStore()),
                importer.tripleStoreOptions(params));
        boolean handedOver = false;
        try {
            Map<String, String> localToRemote = new LinkedHashMap<>();
            Map<String, String> contexts = Profiles.map();
            int index = 0;
            for (Map.Entry<String, MaterializationPlan.FullSource> entry : plan.startModel().entrySet()) {
                if (!Profiles.isStandard(entry.getKey())) {
                    continue;
                }
                String localName = ScenarioGraphNames.CONTEXTS + "model" + index++ + "_"
                        + entry.getKey() + ".xml";
                localToRemote.put(localName, entry.getValue().graph());
                contexts.put(entry.getKey(), localName);
            }
            new GraphFetcher(db, scenario).fetchInto(local, localToRemote, rebasedBoundary(plan, models, contexts));
            applySteps(db, local, plan, contexts);
            handedOver = true;
            return new MaterialisedStore(local, contexts, subjectBase(plan, models));
        } finally {
            if (!handedOver) {
                local.close();
            }
        }
    }

    /**
     * The IRI prefix the subjects of a materialised state carry, without materialising anything.
     *
     * <p>The fallback for a profile whose own state model is not in the catalogue: every profile of one scenario
     * was written against the same base grid model, so the first subject base any of the target states names is
     * the one the store speaks. Split out of {@link #materializeStore} because an ingestion that hits its parent
     * index cache needs the answer <em>without</em> building the store it used to read it from.</p>
     *
     * @param plan   the materialisation plan, for the state of each profile
     * @param models the stored model of every identifier in {@code plan.targetState()}
     * @return the subject base, or the empty string when no target state names one
     */
    static String subjectBase(MaterializationPlan plan, Map<String, StoredModel> models) {
        // The boundary last: a scenario shares it among its modelling authorities, and it speaks the subject
        // base of the first root, which is not the one of the others. A custom profile never: it is not in the
        // store, and one a timestamp shipped first was parsed with the base of that timestamp's files
        return plan.targetState().entrySet().stream()
                .filter(entry -> Profiles.isStandard(entry.getKey()))
                .map(entry -> models.get(entry.getValue())).filter(Objects::nonNull)
                .sorted(Comparator.comparing(StoredModel::isBoundary))
                .map(StoredModel::subjectBase).filter(base -> !base.isEmpty())
                .findFirst().orElse("");
    }

    /**
     * Make the shared boundary speak the subject base of the snapshot it is materialised for.
     *
     * <p>Relative identifiers of CGMES files ({@code rdf:about="#_…"}) are resolved against a base that the parse
     * takes from the data source, so the files of two modelling authorities carry two subject bases. The boundary
     * of a scenario is stored once, by the first root, and every later root links it: in the store of the second
     * authority its equipment then points at {@code <base of NL>#_bv} while the base voltage stored with the
     * boundary is {@code <base of BE>#_bv}, and the conversion finds no nominal voltage. Moving the boundary
     * graphs' subjects and IRI objects to the snapshot's base while they are fetched is what the files themselves,
     * read together, would have given; the stored graphs stay as they were written.</p>
     *
     * <p>Only the boundary start models are looked at: they are the only graphs a scenario shares between modelling
     * authorities, and a boundary's start model is always its target state, so its stored model is in
     * {@code models}.</p>
     *
     * @return per local context name, the mapping of its statements; empty when no boundary needs one
     */
    private static Map<String, UnaryOperator<Statement>> rebasedBoundary(MaterializationPlan plan,
                                                                         Map<String, StoredModel> models,
                                                                         Map<String, String> contexts) {
        String base = subjectBase(plan, models);
        Map<String, UnaryOperator<Statement>> mappings = new LinkedHashMap<>();
        plan.startModel().forEach((subset, source) -> {
            StoredModel model = models.get(source.modelId());
            if (!base.isEmpty() && model != null && model.isBoundary() && !model.subjectBase().isEmpty()
                    && !model.subjectBase().equals(base)) {
                mappings.put(contexts.get(subset), GraphFetcher.rebase(model.subjectBase(), base));
            }
        });
        return mappings;
    }

    /** Fetch every difference of the plan in one request and apply them, folded, one profile at a time. */
    private static void applySteps(RdfDbConnection db, TripleStoreRDF4J local, MaterializationPlan plan,
                                   Map<String, String> contextOfSubset) {
        applySteps(local, plan, contextOfSubset, fetchSteps(db, List.of(plan)), UnaryOperator.identity());
    }

    /** Every difference the plans apply, in one request; none for plans without a step. */
    private static Map<String, DifferenceModel> fetchSteps(RdfDbConnection db, List<MaterializationPlan> plans) {
        List<StoredModel> all = plans.stream().flatMap(plan -> plan.steps().stream())
                .map(UpdatePlan.DiffStep::model).toList();
        return all.isEmpty() ? Map.of() : RdfDbDiffSource.fetchById(db, all);
    }

    /**
     * Apply the differences of a plan, folded, one profile at a time.
     *
     * @param subjectBase the subject base a difference is applied with, given the one it was stored with: the
     *                    identity, unless the store speaks another base than the plan's graphs were parsed with
     */
    private static void applySteps(TripleStoreRDF4J local, MaterializationPlan plan,
                                   Map<String, String> contextOfSubset, Map<String, DifferenceModel> byId,
                                   UnaryOperator<String> subjectBase) {
        stepsBySubset(plan).forEach((subset, steps) -> {
            String contextName = contextOfSubset.get(subset);
            if (contextName == null) {
                throw new RdfDbException("the plan applies " + steps.size() + " difference(s) of the "
                        + subset + " profile but names no graph to start from");
            }
            StoredModel target = steps.get(steps.size() - 1).model();
            List<DifferenceModel> models = steps.stream().map(step -> byId.get(step.model().id())).toList();
            DifferenceModel folded = models.size() == 1 ? models.get(0)
                    : DifferenceModel.compose(models, target.toHeader());
            CgmesDiffImport.applyToGraph(local, folded, contextName, subjectBase.apply(target.subjectBase()));
        });
    }

    /** The steps of a materialisation grouped by profile, keeping their order. */
    private static Map<String, List<UpdatePlan.DiffStep>> stepsBySubset(MaterializationPlan plan) {
        Map<String, List<UpdatePlan.DiffStep>> bySubset = Profiles.map();
        plan.steps().forEach(step -> bySubset
                .computeIfAbsent(step.model().subset(), k -> new ArrayList<>()).add(step));
        return bySubset;
    }

    private static void registerSnapshot(Network network, RdfDbConnection db, String scenario,
                                         SnapshotInfo snapshot, MaterializationPlan plan,
                                         Map<String, StoredModel> stateModels, List<GraphInfo> graphs) {
        Map<String, StoredModel> targets = Profiles.map();
        plan.targetState().forEach((subset, id) -> {
            StoredModel model = stateModels.get(id);
            if (model != null) {
                targets.put(subset, model);
            }
        });
        NetworkIdentity.advance(network, targets);
        network.setCaseDate(snapshot.timestamp().atZone(ZoneOffset.UTC));
        RdfDbProvenanceImpl provenance = new RdfDbProvenanceImpl(db.database(), scenario, graphs, Instant.now(),
                NetworkIdentity.modelIds(network));
        provenance.setSnapshot(snapshot.iri());
        network.addExtension(RdfDbProvenance.class, provenance);
    }

    /**
     * The name the local store gives a graph, which has to be writable as an IRI.
     *
     * <p>A difference is applied to the local store by SPARQL, and SPARQL has no way to name a graph whose IRI
     * holds a space &mdash; which a CGMES instance file name may well do, the CGMES 3 Svedala fixture being the
     * example. The local name is then percent-encoded. Nothing above the triple store minds: what the conversion
     * reads out of a context name is the profile and whether it is a boundary file, and both are suffixes that
     * encoding leaves alone.</p>
     */
    private static String localContext(String contextName) {
        if (SparqlText.isWritableIri(contextName)) {
            return contextName;
        }
        return ScenarioGraphNames.CONTEXTS + ScenarioGraphNames.encode(ScenarioGraphNames.localName(contextName));
    }

    /**
     * The differences to apply per profile, oldest first.
     *
     * <p>A profile whose target is its full model has an empty path, and so does a profile the caller did not name
     * at all: naming a target is how a caller says which profile moves, and the instance file is where everything
     * else stays. The caller that means "the newest state of everything" resolves the heads first and names them,
     * which is what {@code DiffTarget.head()} does.</p>
     */
    private static Map<String, List<StoredModel>> pathsTo(CatalogSnapshot snapshot,
                                                               Map<String, StoredModel> targets) {
        Map<String, String> targetIds = Profiles.map();
        targets.forEach((subset, model) -> targetIds.put(subset, model.id()));
        Map<String, List<StoredModel>> chains = snapshot.chainsDown(targetIds);
        Map<String, List<StoredModel>> paths = Profiles.map();
        chains.forEach((subset, chain) -> {
            List<StoredModel> path = new ArrayList<>(chain.stream()
                    .filter(StoredModel::isDiff).toList());
            Collections.reverse(path);
            paths.put(subset, path);
        });
        return paths;
    }

    private static void register(Network network, RdfDbConnection db, String scenario,
                                 Map<String, StoredModel> targets, List<GraphInfo> graphs) {
        NetworkIdentity.advance(network, targets);
        StoredModel ssh = targets.get(Profiles.SSH);
        if (ssh != null && ssh.scenarioTime() != null) {
            network.setCaseDate(ssh.scenarioTime());
        }
        network.addExtension(RdfDbProvenance.class, new RdfDbProvenanceImpl(db.database(), scenario,
                graphs, Instant.now(), NetworkIdentity.modelIds(network)));
    }
}
