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
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import com.powsybl.triplestore.api.TripleStoreOptions;
import com.powsybl.triplestore.impl.rdf4j.TripleStoreRDF4J;
import com.powsybl.triplestore.impl.rdf4j.sparql.ScenarioGraphNames;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

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
     * What a materialisation produced.
     *
     * @param network    the network, with its identity and provenance registered
     * @param statistics where the time went, in the shape a plain database load reports it
     */
    record Materialised(Network network, LoadStatistics statistics) {
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
    static Materialised materialize(RdfDbConnection db, String scenario, CatalogSnapshot snapshot,
                                    Map<CgmesSubset, StoredModel> targets, NetworkFactory factory,
                                    Properties params, ReportNode rn) {
        Objects.requireNonNull(db);
        Objects.requireNonNull(snapshot);
        RdfDbNames.checkScenario(scenario);
        if (db.database().queryMode() == RdfDatabase.QueryMode.REMOTE) {
            throw new RdfDbException("Scenario '" + scenario + "' holds difference models, which the REMOTE query"
                    + " mode cannot read: the differences would have to be applied on the server. Load this"
                    + " scenario in LOCAL query mode (RdfDatabase.withQueryMode)");
        }
        List<StoredModel> fullModels = snapshot.models().stream()
                .filter(model -> model.kind() == StoredModel.Kind.FULL)
                .toList();
        if (fullModels.isEmpty()) {
            throw new RdfDbException("Scenario '" + scenario + "' of " + db.database() + " holds no full model to"
                    + " build a network from");
        }
        Map<CgmesSubset, StoredModel> chainTargets = new EnumMap<>(targets);
        Map<CgmesSubset, List<StoredModel>> paths = pathsTo(snapshot, chainTargets);

        CgmesImport importer = TripleStoreNetworkLoader.importer();
        TripleStoreOptions options = importer.tripleStoreOptions(params);
        TripleStoreRDF4J local = new TripleStoreRDF4J(new SailRepository(new MemoryStore()), options);
        boolean handedOver = false;
        try {
            Map<String, String> localToRemote = new LinkedHashMap<>();
            Map<CgmesSubset, String> contextOfSubset = new EnumMap<>(CgmesSubset.class);
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
            List<DifferenceModel> fetched = RdfDbDiffSource.fetchAll(db, allDiffs);
            Map<String, DifferenceModel> byId = new LinkedHashMap<>();
            for (int i = 0; i < allDiffs.size(); i++) {
                byId.put(allDiffs.get(i).id(), fetched.get(i));
            }
            paths.forEach((subset, path) -> {
                if (path.isEmpty()) {
                    return;
                }
                String contextName = contextOfSubset.get(subset);
                if (contextName == null) {
                    throw new RdfDbException("Scenario '" + scenario + "' holds " + path.size() + " difference(s)"
                            + " of the " + subset.getIdentifier() + " profile but no full model of it");
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
            return new Materialised(network, new LoadStatistics(Duration.ZERO, fetchWallClock,
                    fetchStatistics.parse(), fetchStatistics.store().plus(applyDiffs), Duration.ZERO, convert,
                    fetchStatistics.statements(), fetchStatistics.graphs(), fetchStatistics.cacheHits(),
                    fetchStatistics.perGraph()));
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
     * being a new root, and what makes a timestep able to store its state variables whole while its steady state
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
     * @return the network and the timings
     */
    static Materialised materialize(RdfDbConnection db, String scenario, SnapshotInfo snapshot,
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
            Map<CgmesSubset, String> contextOfSubset = new EnumMap<>(CgmesSubset.class);
            List<GraphInfo> graphs = new ArrayList<>();
            int index = 0;
            for (Map.Entry<CgmesSubset, MaterializationPlan.FullSource> entry : plan.startModel().entrySet()) {
                CgmesSubset subset = entry.getKey();
                String graph = entry.getValue().graph();
                if (graph == null) {
                    throw new RdfDbException("the full " + subset.getIdentifier() + " model "
                            + entry.getValue().modelId() + " of scenario '" + scenario + "' names no graph");
                }
                // A name the CGMES conversion reads the profile off, and one SPARQL can write as an IRI
                String localName = ScenarioGraphNames.CONTEXTS + "model" + index++ + "_"
                        + subset.getIdentifier() + ".xml";
                localToRemote.put(localName, graph);
                contextOfSubset.put(subset, localName);
                graphs.add(new GraphInfo(scenario, localName, subset, graph));
            }
            long fetchStart = System.nanoTime();
            GraphFetcher.FetchStatistics fetchStatistics = new GraphFetcher(db, scenario)
                    .fetchInto(local, localToRemote);
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
            return new Materialised(network, new LoadStatistics(Duration.ZERO, fetchWallClock,
                    fetchStatistics.parse(), fetchStatistics.store().plus(applyDiffs), Duration.ZERO, convert,
                    fetchStatistics.statements(), fetchStatistics.graphs(), fetchStatistics.cacheHits(),
                    fetchStatistics.perGraph()));
        } finally {
            if (!handedOver) {
                local.close();
            }
        }
    }

    /**
     * The data of a snapshot on a local store, without converting it.
     *
     * <p>What a file ingestion needs: to say what changed between the parent state and a new instance file, the
     * parent state has to exist as triples somewhere, and that is exactly the first half of a materialisation.
     * Building the network would be wasted work &mdash; and would lose the triples, which are the thing being
     * compared.</p>
     *
     * @param store      the local store, holding one graph per profile of the snapshot. The caller closes it
     * @param contexts   the local context name per profile
     * @param subjectBase the IRI prefix the subjects in that store carry
     */
    record MaterialisedStore(TripleStoreRDF4J store, Map<CgmesSubset, String> contexts, String subjectBase)
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
            Map<CgmesSubset, String> contexts = new EnumMap<>(CgmesSubset.class);
            int index = 0;
            for (Map.Entry<CgmesSubset, MaterializationPlan.FullSource> entry : plan.startModel().entrySet()) {
                String localName = ScenarioGraphNames.CONTEXTS + "model" + index++ + "_"
                        + entry.getKey().getIdentifier() + ".xml";
                localToRemote.put(localName, entry.getValue().graph());
                contexts.put(entry.getKey(), localName);
            }
            new GraphFetcher(db, scenario).fetchInto(local, localToRemote);
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
        return plan.targetState().values().stream()
                .map(models::get).filter(Objects::nonNull)
                .map(StoredModel::subjectBase).filter(base -> !base.isEmpty())
                .findFirst().orElse("");
    }

    /** Fetch every difference of the plan in one request and apply them, folded, one profile at a time. */
    private static void applySteps(RdfDbConnection db, TripleStoreRDF4J local, MaterializationPlan plan,
                                   Map<CgmesSubset, String> contextOfSubset) {
        if (plan.steps().isEmpty()) {
            return;
        }
        List<StoredModel> all = plan.steps().stream().map(UpdatePlan.DiffStep::model).toList();
        List<DifferenceModel> fetched = RdfDbDiffSource.fetchAll(db, all);
        Map<String, DifferenceModel> byId = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            byId.put(all.get(i).id(), fetched.get(i));
        }
        stepsBySubset(plan).forEach((subset, steps) -> {
            String contextName = contextOfSubset.get(subset);
            if (contextName == null) {
                throw new RdfDbException("the plan applies " + steps.size() + " difference(s) of the "
                        + subset.getIdentifier() + " profile but names no graph to start from");
            }
            StoredModel target = steps.get(steps.size() - 1).model();
            List<DifferenceModel> models = steps.stream().map(step -> byId.get(step.model().id())).toList();
            DifferenceModel folded = models.size() == 1 ? models.get(0)
                    : DifferenceModel.compose(models, target.toHeader());
            CgmesDiffImport.applyToGraph(local, folded, contextName, target.subjectBase());
        });
    }

    /** The steps of a materialisation grouped by profile, keeping their order. */
    private static Map<CgmesSubset, List<UpdatePlan.DiffStep>> stepsBySubset(MaterializationPlan plan) {
        Map<CgmesSubset, List<UpdatePlan.DiffStep>> bySubset = new EnumMap<>(CgmesSubset.class);
        plan.steps().forEach(step -> bySubset
                .computeIfAbsent(step.model().subset(), k -> new ArrayList<>()).add(step));
        return bySubset;
    }

    private static void registerSnapshot(Network network, RdfDbConnection db, String scenario,
                                         SnapshotInfo snapshot, MaterializationPlan plan,
                                         Map<String, StoredModel> stateModels, List<GraphInfo> graphs) {
        Map<CgmesSubset, StoredModel> targets = new EnumMap<>(CgmesSubset.class);
        plan.targetState().forEach((subset, id) -> {
            StoredModel model = stateModels.get(id);
            if (model != null) {
                targets.put(subset, model);
            }
        });
        NetworkIdentity.advance(network, targets);
        network.setCaseDate(java.time.ZonedDateTime.parse(snapshot.timestep()));
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
    private static Map<CgmesSubset, List<StoredModel>> pathsTo(CatalogSnapshot snapshot,
                                                               Map<CgmesSubset, StoredModel> targets) {
        Map<CgmesSubset, String> targetIds = new EnumMap<>(CgmesSubset.class);
        targets.forEach((subset, model) -> targetIds.put(subset, model.id()));
        Map<CgmesSubset, List<StoredModel>> chains = snapshot.chainsDown(targetIds);
        Map<CgmesSubset, List<StoredModel>> paths = new EnumMap<>(CgmesSubset.class);
        chains.forEach((subset, chain) -> {
            List<StoredModel> path = new ArrayList<>(chain.stream()
                    .filter(StoredModel::isDiff).toList());
            Collections.reverse(path);
            paths.put(subset, path);
        });
        return paths;
    }

    private static void register(Network network, RdfDbConnection db, String scenario,
                                 Map<CgmesSubset, StoredModel> targets, List<GraphInfo> graphs) {
        NetworkIdentity.advance(network, targets);
        StoredModel ssh = targets.get(CgmesSubset.STEADY_STATE_HYPOTHESIS);
        if (ssh != null && ssh.scenarioTime() != null) {
            network.setCaseDate(ssh.scenarioTime());
        }
        network.addExtension(RdfDbProvenance.class, new RdfDbProvenanceImpl(db.database(), scenario,
                graphs, Instant.now(), NetworkIdentity.modelIds(network)));
    }
}
