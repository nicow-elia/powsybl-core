/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.TripleStoreNetworkLoader;
import com.powsybl.cgmes.conversion.diff.CgmesDiffImport;
import com.powsybl.cgmes.conversion.diff.CgmesDiffNotApplicableException;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.computation.ComputationManager;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.ImportConfig;
import com.powsybl.iidm.network.ImportPostProcessor;
import com.powsybl.iidm.network.ImportersServiceLoader;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import com.powsybl.triplestore.api.TripleStore;
import com.powsybl.triplestore.api.TripleStoreOptions;
import com.powsybl.triplestore.impl.rdf4j.TripleStoreRDF4J;
import com.powsybl.triplestore.impl.rdf4j.sparql.TripleStoreRDF4JSparql;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds an IIDM network out of a scenario of an RDF database.
 *
 * <p>The second half of the split loading. It reads the graphs of one scenario and hands them to the very same
 * CGMES conversion a file import uses, so the network it produces is the one the files would have produced.</p>
 *
 * <p>Two ways of getting there, chosen by {@link RdfDatabase#withQueryMode}:</p>
 * <ul>
 *   <li>{@link RdfDatabase.QueryMode#LOCAL}, the default: the graphs are bulk-transferred into a local in-memory
 *       store ({@link GraphFetcher}) and the CGMES query catalogs run there. A handful of large requests, then no
 *       network traffic at all, and the query semantics are exactly the local ones.</li>
 *   <li>{@link RdfDatabase.QueryMode#REMOTE}: the catalogs run on the server, some eighty round trips. Slower for
 *       a whole network, but the statements never have to fit in this process.</li>
 * </ul>
 *
 * <p>Both produce the same network, which is asserted by the tests of this module.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class RdfDbNetworkLoader {

    /**
     * A loaded network and where the time went.
     *
     * @param network    the network
     * @param statistics the timings of the load
     */
    public record LoadResult(Network network, LoadStatistics statistics) {
    }

    private RdfDbNetworkLoader() {
    }

    /**
     * Load the whole scenario as a network.
     *
     * @param db             the open connection
     * @param scenario       the scenario to read
     * @param networkFactory the factory the network is created with
     * @param params         the CGMES import parameters
     * @param reportNode     where the load reports
     * @return the network
     */
    public static Network load(RdfDbConnection db, String scenario, NetworkFactory networkFactory,
                               Properties params, ReportNode reportNode) {
        return load(db, scenario, new RdfDbLoadOptions(), networkFactory, params, reportNode);
    }

    /**
     * Load part or all of a scenario as a network.
     *
     * @param db             the open connection
     * @param scenario       the scenario to read
     * @param options        what to read and what to do afterwards
     * @param networkFactory the factory the network is created with
     * @param params         the CGMES import parameters
     * @param reportNode     where the load reports
     * @return the network
     */
    public static Network load(RdfDbConnection db, String scenario, RdfDbLoadOptions options,
                               NetworkFactory networkFactory, Properties params, ReportNode reportNode) {
        return loadWithStatistics(db, scenario, options, networkFactory, params, reportNode).network();
    }

    /**
     * Load the whole scenario as a network, and say where the time went.
     *
     * @param db             the open connection
     * @param scenario       the scenario to read
     * @param networkFactory the factory the network is created with
     * @param params         the CGMES import parameters
     * @param reportNode     where the load reports
     * @return the network and the timings
     */
    public static LoadResult loadWithStatistics(RdfDbConnection db, String scenario, NetworkFactory networkFactory,
                                                Properties params, ReportNode reportNode) {
        return loadWithStatistics(db, scenario, new RdfDbLoadOptions(), networkFactory, params, reportNode);
    }

    /**
     * Load part or all of a scenario as a network, and say where the time went.
     *
     * @param db             the open connection
     * @param scenario       the scenario to read
     * @param options        what to read and what to do afterwards
     * @param networkFactory the factory the network is created with
     * @param params         the CGMES import parameters
     * @param reportNode     where the load reports
     * @return the network and the timings
     */
    public static LoadResult loadWithStatistics(RdfDbConnection db, String scenario, RdfDbLoadOptions options,
                                                NetworkFactory networkFactory, Properties params,
                                                ReportNode reportNode) {
        Objects.requireNonNull(db);
        Objects.requireNonNull(options);
        NetworkFactory factory = networkFactory == null ? NetworkFactory.findDefault() : networkFactory;
        ReportNode rn = reportNode == null ? ReportNode.NO_OP : reportNode;

        long t0 = System.nanoTime();
        CatalogSnapshot snapshot = db.catalog(scenario).snapshot();
        Duration readCatalog = Duration.ofNanos(System.nanoTime() - t0);
        // "The scenario" of a versioned scenario is its newest snapshot, and reaching it is the snapshot path: the
        // graphs of a version live under IRIs of this layer, not among the instance file contexts the unversioned
        // path lists. The catalogue read above already knows, so this costs no request
        if (snapshot.isVersioned()) {
            if (options.getSubsets() != null) {
                throw new RdfDbException("Scenario '" + scenario + "' is versioned, and a partial load of a"
                        + " versioned scenario is not supported: a difference states properties of the profile it"
                        + " belongs to, and leaving a profile out would build a network from a state that never"
                        + " existed. Load the whole scenario, or a snapshot of it");
            }
            LoadResult versioned = loadWithStatistics(db, SnapshotRef.latest(scenario), factory, params, rn);
            applyPostProcessors(versioned.network(), options, rn);
            return versioned;
        }
        if (snapshot.hasDifferences()) {
            // The scenario is versioned: its instance file graphs are the oldest state it holds, and "the
            // scenario" means its newest one. Materialising it is the only way to see the differences.
            if (options.getSubsets() != null) {
                throw new RdfDbException("Scenario '" + scenario + "' holds difference models, and a partial load"
                        + " of a versioned scenario is not supported: a difference states properties of the profile"
                        + " it belongs to, and leaving a profile out would build a network from a state that never"
                        + " existed. Load the whole scenario, or a target of it");
            }
            RdfDbMaterializer.Materialised materialised = RdfDbMaterializer.materialize(db, scenario, snapshot,
                    targetsOf(snapshot, DiffTarget.head()), factory, params, rn);
            applyPostProcessors(materialised.network(), options, rn);
            LoadStatistics statistics = withCatalogTime(materialised.statistics(), readCatalog);
            LOGGER.info("Loaded network {} from scenario '{}' of {} at its newest stored state: {}",
                    materialised.network().getId(), scenario, db.database(), statistics.summary());
            return new LoadResult(materialised.network(), statistics);
        }
        List<GraphInfo> graphs = graphsToRead(db, scenario, options);
        Duration listGraphs = Duration.ofNanos(System.nanoTime() - t0);

        LoadResult result = db.database().queryMode() == RdfDatabase.QueryMode.REMOTE
                ? loadRemote(db, scenario, graphs, factory, params, rn, listGraphs)
                : loadLocal(db, scenario, graphs, factory, params, rn, listGraphs);

        applyPostProcessors(result.network(), options, rn);
        result.network().addExtension(RdfDbProvenance.class,
                new RdfDbProvenanceImpl(db.database(), scenario, graphs, Instant.now(),
                        NetworkIdentity.modelIds(result.network())));
        LOGGER.info("Loaded network {} from scenario '{}' of {}: {}",
                result.network().getId(), scenario, db.database(), result.statistics().summary());
        return result;
    }

    private static List<GraphInfo> graphsToRead(RdfDbConnection db, String scenario, RdfDbLoadOptions options) {
        List<GraphInfo> all = db.graphs(scenario);
        EnumSet<CgmesSubset> subsets = options.getSubsets();
        List<GraphInfo> graphs = subsets == null ? all
                : all.stream().filter(g -> subsets.contains(g.subset())).toList();
        if (graphs.isEmpty()) {
            throw new RdfDbException("No CGMES graphs in " + db.database() + " for scenario '" + scenario + "'"
                    + (subsets == null ? "" : " and subsets " + subsets)
                    + (all.isEmpty() ? " (the scenario is empty; known scenarios: " + db.scenarios() + ")" : ""));
        }
        return graphs;
    }

    private static LoadResult loadLocal(RdfDbConnection db, String scenario, List<GraphInfo> graphs,
                                        NetworkFactory factory, Properties params, ReportNode rn,
                                        Duration listGraphs) {
        CgmesImport importer = TripleStoreNetworkLoader.importer();
        TripleStoreOptions options = importer.tripleStoreOptions(params);
        TripleStoreRDF4J local = new TripleStoreRDF4J(new SailRepository(new MemoryStore()), options);
        // The conversion closes the store itself unless the parameters ask for it to be kept as a network
        // extension, so it is closed here only when the load did not get that far.
        boolean handedOver = false;
        try {
            GraphFetcher.FetchStatistics fetched = new GraphFetcher(db, scenario)
                    .fetchInto(local, graphs.stream().map(GraphInfo::contextName).toList());

            long describeStart = System.nanoTime();
            TripleStoreNetworkLoader.StoreContent content = describe(local, fetched.cimNamespace());
            Duration describe = Duration.ofNanos(System.nanoTime() - describeStart);

            long convertStart = System.nanoTime();
            Network network = TripleStoreNetworkLoader.load(local, content, factory, params, rn);
            handedOver = true;
            Duration convert = Duration.ofNanos(System.nanoTime() - convertStart);

            LoadStatistics statistics = new LoadStatistics(listGraphs, fetched.fetch(), fetched.parse(),
                    fetched.store(), describe, convert, fetched.statements(), fetched.graphs(),
                    fetched.cacheHits(), fetched.perGraph());
            return new LoadResult(network, statistics);
        } finally {
            if (!handedOver) {
                local.close();
            }
        }
    }

    private static TripleStoreNetworkLoader.StoreContent describe(TripleStoreRDF4J local, String cimNamespace) {
        TripleStoreNetworkLoader.StoreContent described = TripleStoreNetworkLoader.describe(local);
        if (cimNamespace == null || cimNamespace.equals(described.cimNamespace())) {
            return described;
        }
        // The namespace the fetch saw wins: it was read off the statements themselves rather than guessed
        return new TripleStoreNetworkLoader.StoreContent(cimNamespace, described.baseName(),
                described.contextNames());
    }

    private static LoadResult loadRemote(RdfDbConnection db, String scenario, List<GraphInfo> graphs,
                                         NetworkFactory factory, Properties params, ReportNode rn,
                                         Duration listGraphs) {
        CgmesImport importer = TripleStoreNetworkLoader.importer();
        TripleStore store = db.scenarioStore(scenario, importer.tripleStoreOptions(params));
        restrict(store, graphs);
        long describeStart = System.nanoTime();
        TripleStoreNetworkLoader.StoreContent content = TripleStoreNetworkLoader.describe(store);
        Duration describe = Duration.ofNanos(System.nanoTime() - describeStart);

        long convertStart = System.nanoTime();
        Network network = TripleStoreNetworkLoader.load(store, content, factory, params, rn);
        Duration convert = Duration.ofNanos(System.nanoTime() - convertStart);

        LoadStatistics statistics = new LoadStatistics(listGraphs, Duration.ZERO, Duration.ZERO, Duration.ZERO,
                describe, convert, -1L, graphs.size(), 0, Map.of());
        return new LoadResult(network, statistics);
    }

    private static void restrict(TripleStore store, List<GraphInfo> graphs) {
        if (store instanceof TripleStoreRDF4JSparql sparql) {
            sparql.restrictTo(graphs.stream().map(GraphInfo::contextName).toList());
        }
        // The in-process backend keeps one store per scenario and has no dataset parameters, so a subset
        // restriction there is only honoured through the graph list the fetcher is given: a remote-mode subset
        // load of the memory backend sees the whole scenario. Listed under "Limitations" in rdf_database.md.
    }

    /**
     * Update a network from the graphs of a scenario.
     *
     * <p>A file update reads a data source of SSH and SV files and applies them through the {@code -update} query
     * catalog. This does the same with the graphs of a scenario, which is how a stack of steady-state changes
     * kept in a database reaches a network that is already in memory.</p>
     *
     * @param network    the network to update in place
     * @param db         the open connection
     * @param scenario   the scenario holding the update data
     * @param options    which subsets to read. The default is the steady-state pair, see
     *                   {@link RdfDbLoadOptions#forUpdate()}
     * @param params     the CGMES import parameters
     * @param reportNode where the update reports
     */
    public static void update(Network network, RdfDbConnection db, String scenario, RdfDbLoadOptions options,
                              Properties params, ReportNode reportNode) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(db);
        RdfDbLoadOptions effective = options == null ? RdfDbLoadOptions.forUpdate() : options;
        ReportNode rn = reportNode == null ? ReportNode.NO_OP : reportNode;
        checkNotVariantMode(network, new RdfDbUpdateOptions(), "a whole-profile replacement");
        List<GraphInfo> graphs = graphsToRead(db, scenario, effective);

        CgmesImport importer = TripleStoreNetworkLoader.importer();
        if (db.database().queryMode() == RdfDatabase.QueryMode.REMOTE) {
            TripleStore store = db.scenarioStore(scenario, importer.tripleStoreOptions(params));
            restrict(store, graphs);
            TripleStoreNetworkLoader.update(network, store, params, rn);
            classicOperationDone(network);
            return;
        }
        TripleStoreRDF4J local = new TripleStoreRDF4J(new SailRepository(new MemoryStore()),
                importer.tripleStoreOptions(params));
        try {
            new GraphFetcher(db, scenario).fetchInto(local, graphs.stream().map(GraphInfo::contextName).toList());
            TripleStoreNetworkLoader.update(network, local, params, rn);
            classicOperationDone(network);
        } finally {
            local.close();
        }
    }

    // ------------------------------------------------------------------ versioned load and update

    /**
     * Build the network of a stored state of a scenario.
     *
     * <p>{@link DiffTarget#head()} is the newest state the scenario holds; a named target is any earlier one. The
     * network is materialised &mdash; the instance file graphs plus the differences on the way, applied as RDF
     * &mdash; and then converted by the very same CGMES conversion a file import uses, so it is the network those
     * files of that version would have produced.</p>
     *
     * @param db             the open connection
     * @param scenario       the scenario to read
     * @param target         which stored state to build
     * @param networkFactory the factory the network is created with
     * @param params         the CGMES import parameters
     * @param reportNode     where the load reports
     * @return the network, at the target
     */
    public static Network load(RdfDbConnection db, String scenario, DiffTarget target, NetworkFactory networkFactory,
                               Properties params, ReportNode reportNode) {
        Objects.requireNonNull(db);
        Objects.requireNonNull(target);
        RdfDbNames.checkScenario(scenario);
        NetworkFactory factory = networkFactory == null ? NetworkFactory.findDefault() : networkFactory;
        ReportNode rn = reportNode == null ? ReportNode.NO_OP : reportNode;
        CatalogSnapshot snapshot = db.catalog(scenario).snapshot();
        return RdfDbMaterializer.materialize(db, scenario, snapshot, targetsOf(snapshot, target), factory, params,
                rn).network();
    }

    /** The time spent reading the metadata graph is the listing a versioned load does instead of listing graphs. */
    private static LoadStatistics withCatalogTime(LoadStatistics statistics, Duration readCatalog) {
        return new LoadStatistics(readCatalog, statistics.fetch(), statistics.parse(), statistics.store(),
                statistics.describe(), statistics.convert(), statistics.statements(), statistics.graphs(),
                statistics.cacheHits(), statistics.perGraph());
    }

    /**
     * Bring a network to a stored state of a scenario.
     *
     * <p>The decision is made before anything is read: if the network is already there, nothing happens; if the
     * stored state is reachable by applying differences the network has not seen, they are fetched in one request,
     * folded into one difference per profile and applied <em>in place</em>; and if neither holds, the network is
     * rebuilt from the database and the result carries a <strong>new instance</strong> the caller has to swap its
     * references to.</p>
     *
     * <p>A network of another scenario is always a rebuild, and no query is sent to decide that: its model
     * identifiers name models of the scenario it came from, and a difference of this scenario does not apply to
     * them.</p>
     *
     * <p>The network holds <strong>one model per profile</strong>: a stored chain versions one model of one
     * profile, so a network carrying two models of a profile this update looks at is refused rather than silently
     * brought to one of them.</p>
     *
     * @param network    the network to bring up to date
     * @param db         the open connection
     * @param scenario   the scenario holding the target state
     * @param target     which stored state to reach
     * @param options    how far the update may go
     * @param params     the CGMES import parameters
     * @param reportNode where the update reports
     * @return what was done, and the up-to-date network
     */
    public static UpdateResult update(Network network, RdfDbConnection db, String scenario, DiffTarget target,
                                      RdfDbUpdateOptions options, Properties params, ReportNode reportNode) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(db);
        Objects.requireNonNull(target);
        RdfDbNames.checkScenario(scenario);
        RdfDbUpdateOptions effective = options == null ? new RdfDbUpdateOptions() : options;
        ReportNode rn = reportNode == null ? ReportNode.NO_OP : reportNode;

        long planStart = System.nanoTime();
        // The metadata graph is read at most once per update, and not at all when the network belongs to another
        // scenario: its model identifiers mean nothing here, so there is nothing to look up
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        boolean otherScenario = provenance != null && !provenance.scenario().equals(scenario);
        CatalogSnapshot snapshot = otherScenario ? null : db.catalog(scenario).snapshot();
        // "The head" of a versioned scenario is its newest snapshot; a named target keeps the model-level path,
        // which is what a caller addressing individual stored models asked for. The catalogue read above knows
        // whether the scenario is versioned, so the decision costs no request
        if (target.isHead() && snapshot != null && snapshot.isVersioned()) {
            return update(network, db, SnapshotRef.latest(scenario), effective, params, rn);
        }
        // Everything below addresses individual stored models rather than snapshots, and a variant stands for a
        // snapshot. Silently doing it on the whole network would write into every bound variant at once
        checkNotVariantMode(network, effective, "a model-level target");
        DiffUpdatePlanner.Plan plan = otherScenario
                ? DiffUpdatePlanner.otherScenario(provenance.scenario(), scenario)
                : planUpdate(network, provenance, snapshot, scenario, target, effective);
        Duration planning = Duration.ofNanos(System.nanoTime() - planStart);

        return switch (plan.route()) {
            case NOOP -> {
                RdfDbReports.updateRouteReport(rn, scenario, UpdateResult.Route.NOOP, 0, List.of());
                yield new UpdateResult(UpdateResult.Route.NOOP, network, Map.of(), List.of(),
                        new UpdateStatistics(planning, Duration.ZERO, Duration.ZERO, Duration.ZERO, 0, 0));
            }
            case DIFF -> applyDifferences(network, db, scenario, snapshot, target, plan, effective, params, rn,
                    planning);
            case FULL -> fullRoute(network, db, scenario, snapshot, target, plan, effective, params, rn, planning);
        };
    }

    private static DiffUpdatePlanner.Plan planUpdate(Network network, RdfDbProvenance provenance,
                                                     CatalogSnapshot snapshot, String scenario, DiffTarget target,
                                                     RdfDbUpdateOptions options) {
        Map<CgmesSubset, String> identity = provenance != null && !provenance.modelIds().isEmpty()
                ? provenance.modelIds() : NetworkIdentity.modelIds(network, options.getSubsets());
        Map<CgmesSubset, String> currentIds = new EnumMap<>(CgmesSubset.class);
        identity.forEach((subset, id) -> {
            if (options.getSubsets().contains(subset)) {
                currentIds.put(subset, id);
            }
        });
        if (currentIds.isEmpty()) {
            throw new RdfDbException("The network carries no CGMES model identity for the profiles "
                    + options.getSubsets() + ", so there is nothing to bring forward from");
        }
        Map<CgmesSubset, String> targetIds = targetIds(snapshot, target, currentIds.keySet());
        if (targetIds.isEmpty()) {
            return new DiffUpdatePlanner.Plan(DiffUpdatePlanner.Route.FULL, Map.of(), Map.of(), Map.of(),
                    List.of("scenario '" + scenario + "' holds no model of the profiles " + currentIds.keySet()));
        }
        Map<CgmesSubset, List<StoredModel>> targetChains = snapshot.chainsDown(targetIds);
        Map<CgmesSubset, List<StoredModel>> currentChains = snapshot.chainsDown(currentIds);
        return DiffUpdatePlanner.plan(scenario, currentIds, targetChains, currentChains,
                options.getMaxDiffChain());
    }

    private static Map<CgmesSubset, String> targetIds(CatalogSnapshot snapshot, DiffTarget target,
                                                      Set<CgmesSubset> subsets) {
        if (!target.isHead()) {
            return new EnumMap<>(target.modelIds());
        }
        Map<CgmesSubset, String> heads = new EnumMap<>(CgmesSubset.class);
        subsets.forEach(subset -> snapshot.head(subset).ifPresent(model -> heads.put(subset, model.id())));
        return heads;
    }

    /** Every profile the scenario holds, at the state the target names. */
    private static Map<CgmesSubset, StoredModel> targetsOf(CatalogSnapshot snapshot, DiffTarget target) {
        Map<CgmesSubset, StoredModel> targets = new EnumMap<>(CgmesSubset.class);
        if (target.isHead()) {
            return snapshot.heads();
        }
        target.modelIds().forEach((subset, id) -> {
            StoredModel model = snapshot.model(id).orElseThrow(() -> new RdfDbException("Scenario '"
                    + snapshot.scenario() + "' holds no model " + id));
            if (model.subset() != subset) {
                throw new RdfDbException("Model " + id + " of scenario '" + snapshot.scenario() + "' describes the "
                        + model.subset().getIdentifier() + " profile, not " + subset.getIdentifier());
            }
            targets.put(subset, model);
        });
        return targets;
    }

    private static UpdateResult applyDifferences(Network network, RdfDbConnection db, String scenario,
                                                 CatalogSnapshot snapshot, DiffTarget target,
                                                 DiffUpdatePlanner.Plan plan, RdfDbUpdateOptions options,
                                                 Properties params, ReportNode rn, Duration planning) {
        boolean anyInverted = plan.inverted().values().stream().anyMatch(Boolean::booleanValue);
        boolean anyForward = plan.paths().entrySet().stream()
                .anyMatch(e -> !e.getValue().isEmpty() && !Boolean.TRUE.equals(plan.inverted().get(e.getKey())));
        if (anyInverted && anyForward) {
            return fallback(network, db, scenario, snapshot, target, plan, options, params, rn, planning,
                    List.of("one profile has to move forward and another backwards, which this release does not"
                            + " combine in one update"));
        }
        List<StoredModel> all = new ArrayList<>();
        plan.paths().values().forEach(all::addAll);

        long fetchStart = System.nanoTime();
        List<DifferenceModel> fetched = RdfDbDiffSource.fetchAll(db, all);
        Duration fetch = Duration.ofNanos(System.nanoTime() - fetchStart);
        Map<String, DifferenceModel> byId = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            byId.put(all.get(i).id(), fetched.get(i));
        }

        long composeStart = System.nanoTime();
        Map<CgmesSubset, List<DifferenceModel>> chains = new EnumMap<>(CgmesSubset.class);
        Map<CgmesSubset, DifferenceModelHeader> headers = new EnumMap<>(CgmesSubset.class);
        Map<CgmesSubset, List<String>> appliedIds = new EnumMap<>(CgmesSubset.class);
        plan.paths().forEach((subset, path) -> {
            if (path.isEmpty()) {
                return;
            }
            chains.put(subset, path.stream().map(model -> byId.get(model.id())).toList());
            headers.put(subset, composedHeader(plan, subset, path));
            appliedIds.put(subset, path.stream().map(StoredModel::id).toList());
        });
        DifferenceModelSet composed = RdfDbDiffSource.compose(chains, headers);
        Duration compose = Duration.ofNanos(System.nanoTime() - composeStart);

        Conversion.Config config = TripleStoreNetworkLoader.importer().config(params);
        long applyStart = System.nanoTime();
        try {
            if (anyInverted) {
                CgmesDiffImport.revert(network, composed, config, options.getDiffOptions(), rn);
            } else {
                CgmesDiffImport.apply(network, composed, config, options.getDiffOptions(), rn);
            }
        } catch (CgmesDiffNotApplicableException e) {
            return fallback(network, db, scenario, snapshot, target, plan, options, params, rn, planning,
                    e.getDecision().reasons());
        }
        Duration apply = Duration.ofNanos(System.nanoTime() - applyStart);

        recordIdentity(network, db, scenario, plan.targets());
        UpdateStatistics statistics = new UpdateStatistics(planning, fetch, compose, apply, plan.diffCount(),
                plan.statementCount());
        RdfDbReports.updateRouteReport(rn, scenario, UpdateResult.Route.DIFF_APPLIED, plan.diffCount(), List.of());
        LOGGER.info("Updated network {} to scenario '{}' by applying {} difference(s): {}", network.getId(),
                scenario, plan.diffCount(), statistics.summary());
        return new UpdateResult(UpdateResult.Route.DIFF_APPLIED, network, appliedIds, List.of(), statistics);
    }

    /**
     * The header the composed difference of a profile carries.
     *
     * <p>Forward, the composed difference <em>is</em> the target model as far as the receiving network is
     * concerned, so it carries the target's identity and supersedes the model the network holds. Backwards, the
     * composed difference is the one being undone, so it carries the identity of the model the network holds and
     * supersedes the target, which is where the network ends up.</p>
     */
    private static DifferenceModelHeader composedHeader(DiffUpdatePlanner.Plan plan, CgmesSubset subset,
                                                        List<StoredModel> path) {
        boolean inverted = Boolean.TRUE.equals(plan.inverted().get(subset));
        StoredModel target = plan.targets().get(subset);
        if (!inverted) {
            return target.toHeader().toBuilder()
                    .supersedes(List.of(path.get(0).supersedes().isEmpty() ? target.id()
                            : path.get(0).supersedes().get(0)))
                    .build();
        }
        // path is oldest first, so its last element is the difference the network currently sits on
        StoredModel current = path.get(path.size() - 1);
        return current.toHeader().toBuilder().supersedes(List.of(target.id())).build();
    }

    /**
     * Give up on the difference route after the plan was made, and rebuild instead.
     *
     * <p>The target the caller asked for travels with it. A fallback is not a change of destination: a caller that
     * asked for a named version and gets a rebuild has to get <em>that</em> version, and the result says nothing
     * about which one it is, so getting it wrong would be invisible.</p>
     */
    private static UpdateResult fallback(Network network, RdfDbConnection db, String scenario,
                                         CatalogSnapshot snapshot, DiffTarget target,
                                         DiffUpdatePlanner.Plan plan, RdfDbUpdateOptions options,
                                         Properties params, ReportNode rn, Duration planning,
                                         List<String> reasons) {
        DiffUpdatePlanner.Plan full = new DiffUpdatePlanner.Plan(DiffUpdatePlanner.Route.FULL, Map.of(),
                plan.targets(), Map.of(), reasons);
        return fullRoute(network, db, scenario, snapshot, target, full, options, params, rn, planning);
    }

    private static UpdateResult fullRoute(Network network, RdfDbConnection db, String scenario,
                                          CatalogSnapshot snapshot, DiffTarget target,
                                          DiffUpdatePlanner.Plan plan, RdfDbUpdateOptions options,
                                          Properties params, ReportNode rn, Duration planning) {
        if (!options.isAllowFullReload()) {
            RdfDbReports.updateRouteReport(rn, scenario, UpdateResult.Route.FULL_REQUIRED, 0, plan.reasons());
            return new UpdateResult(UpdateResult.Route.FULL_REQUIRED, network, Map.of(), plan.reasons(),
                    new UpdateStatistics(planning, Duration.ZERO, Duration.ZERO, Duration.ZERO, 0, 0));
        }
        long applyStart = System.nanoTime();
        // Null only when the network belongs to another scenario: nothing has been read of the target scenario yet
        CatalogSnapshot of = snapshot != null ? snapshot : db.catalog(scenario).snapshot();
        RdfDbMaterializer.Materialised replacement = RdfDbMaterializer.materialize(db, scenario, of,
                targetsOf(of, target), options.getNetworkFactory(), params, rn);
        Duration apply = Duration.ofNanos(System.nanoTime() - applyStart);
        RdfDbReports.updateRouteReport(rn, scenario, UpdateResult.Route.FULL_RELOAD, 0, plan.reasons());
        return new UpdateResult(UpdateResult.Route.FULL_RELOAD, replacement.network(), Map.of(), plan.reasons(),
                new UpdateStatistics(planning, Duration.ZERO, Duration.ZERO, apply, 0, 0));
    }

    /**
     * A classic in-place operation has just written the working variant and the network-level identity.
     *
     * <p>Outside variant mode nothing keeps the bindings of tracked clones true &mdash; the operation was free
     * to write values IIDM shares between variants &mdash; so they are dropped. A later opt-in then gets the
     * clear "variant 'x' is not bound to a snapshot" error instead of a wrong state. In variant mode and inside
     * a scope this does nothing: there the bindings are exactly what is being maintained.</p>
     *
     * @param network the network that was changed
     */
    static void classicOperationDone(Network network) {
        if (network.getExtension(RdfDbProvenance.class) instanceof RdfDbProvenanceImpl impl) {
            impl.dropTrackedBindings();
        }
    }

    private static void recordIdentity(Network network, RdfDbConnection db, String scenario,
                                       Map<CgmesSubset, StoredModel> targets) {
        NetworkIdentity.advance(network, targets);
        Map<CgmesSubset, String> ids = NetworkIdentity.modelIds(network);
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        if (provenance instanceof RdfDbProvenanceImpl impl && provenance.scenario().equals(scenario)) {
            impl.setModelIds(ids);
        } else {
            network.addExtension(RdfDbProvenance.class,
                    new RdfDbProvenanceImpl(db.database(), scenario, List.of(), Instant.now(), ids));
        }
        classicOperationDone(network);
    }

    // ------------------------------------------------------------------ snapshots

    /**
     * Build the network of one snapshot.
     *
     * <p>The mandated entry point of the versioning layer. The address is always complete: the scenario says which
     * base grid model, the timestep which moment of it, the version which study state. A {@code null} version
     * means the newest one of that timestep, a {@code null} timestep the base timestep of the scenario.</p>
     *
     * @param db             the open connection
     * @param ref            the address of the snapshot
     * @param networkFactory the factory the network is created with
     * @param params         the CGMES import parameters
     * @param reportNode     where the load reports
     * @return the network, at that snapshot
     */
    public static Network load(RdfDbConnection db, SnapshotRef ref, NetworkFactory networkFactory,
                               Properties params, ReportNode reportNode) {
        return loadWithStatistics(db, ref, networkFactory, params, reportNode).network();
    }

    /**
     * Build the network of one snapshot, addressed by plain strings.
     *
     * @param db             the open connection
     * @param scenario       the scenario, required
     * @param version        the version label, or {@code null} for the newest one
     * @param timestep       the timestep as an ISO instant, an offset date-time or a {@code "8:30"} label of the
     *                       scenario's base day; {@code null} for the base timestep
     * @param networkFactory the factory the network is created with
     * @param params         the CGMES import parameters
     * @param reportNode     where the load reports
     * @return the network, at that snapshot
     */
    public static Network load(RdfDbConnection db, String scenario, String version, String timestep,
                               NetworkFactory networkFactory, Properties params, ReportNode reportNode) {
        Objects.requireNonNull(db);
        return load(db, db.snapshots(scenario).resolve(version, timestep), networkFactory, params, reportNode);
    }

    /**
     * Build the network of one snapshot, and say where the time went.
     *
     * @param db             the open connection
     * @param ref            the address of the snapshot
     * @param networkFactory the factory the network is created with
     * @param params         the CGMES import parameters
     * @param reportNode     where the load reports
     * @return the network and the timings
     */
    public static LoadResult loadWithStatistics(RdfDbConnection db, SnapshotRef ref, NetworkFactory networkFactory,
                                                Properties params, ReportNode reportNode) {
        Objects.requireNonNull(db);
        Objects.requireNonNull(ref);
        NetworkFactory factory = networkFactory == null ? NetworkFactory.findDefault() : networkFactory;
        ReportNode rn = reportNode == null ? ReportNode.NO_OP : reportNode;
        long t0 = System.nanoTime();
        RdfDbMaterializer.Materialised materialised = materialize(db, ref, factory, params, rn);
        Duration readCatalog = Duration.ofNanos(System.nanoTime() - t0)
                .minus(materialised.statistics().total());
        LoadStatistics statistics = withCatalogTime(materialised.statistics(),
                readCatalog.isNegative() ? Duration.ZERO : readCatalog);
        LOGGER.info("Loaded network {} from snapshot {} of {}: {}", materialised.network().getId(), ref,
                db.database(), statistics.summary());
        return new LoadResult(materialised.network(), statistics);
    }

    private static RdfDbMaterializer.Materialised materialize(RdfDbConnection db, SnapshotRef ref,
                                                              NetworkFactory factory, Properties params,
                                                              ReportNode rn) {
        SnapshotCatalog catalog = db.snapshots(ref.scenario());
        SnapshotInfo info = catalog.find(ref).orElseThrow(() -> new RdfDbException("scenario '" + ref.scenario()
                + "' of " + db.database() + " holds no snapshot " + ref + "; it holds "
                + catalog.snapshots().stream().map(SnapshotInfo::toString).toList()));
        return materialize(db, info, factory, params, rn);
    }

    private static RdfDbMaterializer.Materialised materialize(RdfDbConnection db, SnapshotInfo info,
                                                              NetworkFactory factory, Properties params,
                                                              ReportNode rn) {
        MaterializationPlan plan = db.versionGraph(info.scenario()).materialization(info.iri());
        Map<String, StoredModel> stateModels =
                db.catalog(info.scenario()).models(plan.targetState().values());
        return RdfDbMaterializer.materialize(db, info.scenario(), info, plan, stateModels, factory, params, rn);
    }

    /**
     * Bring a network to a snapshot.
     *
     * <p>One query decides how. The network is already there and nothing happens; or the snapshot is reachable by
     * applying differences the network has not seen &mdash; forwards, backwards, or up one branch of the version
     * chain and down another &mdash; and they are fetched in one request, folded per profile and applied in place;
     * or it is not, and the network is rebuilt at the snapshot, in which case the result carries a <strong>new
     * instance</strong>.</p>
     *
     * <p>A network of another scenario is always a rebuild, and no query is sent to decide it.</p>
     *
     * @param network    the network to bring up to date
     * @param db         the open connection
     * @param target     the address of the snapshot to reach
     * @param options    how far the update may go
     * @param params     the CGMES import parameters
     * @param reportNode where the update reports
     * @return what was done, and the up-to-date network
     */
    public static UpdateResult update(Network network, RdfDbConnection db, SnapshotRef target,
                                      RdfDbUpdateOptions options, Properties params, ReportNode reportNode) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(db);
        Objects.requireNonNull(target);
        RdfDbUpdateOptions effective = options == null ? new RdfDbUpdateOptions() : options;
        ReportNode rn = reportNode == null ? ReportNode.NO_OP : reportNode;
        String scenario = target.scenario();

        // The opt-in, and the only place it is decided. A network that has no bound variant and whose caller named
        // none goes through the code below exactly as it did before variants existed
        String variant = variantModeOf(network, effective);
        if (variant != null) {
            return VariantUpdater.update(network, db, target, variant, effective, params, rn);
        }

        long planStart = System.nanoTime();
        UpdatePlan plan = db.versionGraph(scenario).plan(network, target, effective);
        Duration planning = Duration.ofNanos(System.nanoTime() - planStart);

        return switch (plan.kind()) {
            case NOOP -> {
                RdfDbReports.updateRouteReport(rn, scenario, UpdateResult.Route.NOOP, 0, List.of());
                yield new UpdateResult(UpdateResult.Route.NOOP, network, Map.of(), List.of(),
                        new UpdateStatistics(planning, Duration.ZERO, Duration.ZERO, Duration.ZERO, 0, 0));
            }
            case DIFF -> applySnapshotDifferences(network, db, target, plan, effective, params, rn, planning);
            case FULL -> snapshotFullRoute(network, db, target, plan, effective, params, rn, planning);
        };
    }

    /**
     * Bring a network to a snapshot, addressed by plain strings.
     *
     * @param network    the network to bring up to date
     * @param db         the open connection
     * @param scenario   the scenario, required
     * @param version    the version label, or {@code null} for the newest one
     * @param timestep   the timestep text, or {@code null} for the base timestep
     * @param params     the CGMES import parameters
     * @param reportNode where the update reports
     * @return what was done
     */
    public static UpdateResult update(Network network, RdfDbConnection db, String scenario, String version,
                                      String timestep, Properties params, ReportNode reportNode) {
        Objects.requireNonNull(db);
        return update(network, db, db.snapshots(scenario).resolve(version, timestep), new RdfDbUpdateOptions(),
                params, reportNode);
    }

    /**
     * Load many snapshots of one scenario as the variants of a single network.
     *
     * <p>A whole day in one network: the first requested snapshot is converted from the data and becomes the
     * network, every requested snapshot &mdash; including the first &mdash; gets a named variant, and the rest of
     * them are clones plus the differences between them. One chain query, one statement fetch and one conversion,
     * whatever the number of timesteps.</p>
     *
     * <p>A snapshot that cannot be reached inside a variant does not fail the load: its variant is not created and
     * its {@link VariantOutcome} says why, so a day with one drifted timestep still gives the other ninety-five.
     * A snapshot the scenario does not hold at all is an error, raised before anything is loaded.</p>
     *
     * <p>The working variant of the calling thread is the primary one when the call returns.</p>
     *
     * @param db             the open connection
     * @param scenario       the scenario, required
     * @param requests       the snapshots to load, at least one
     * @param options        the naming rule, how each variant is brought to its snapshot, and whether the network
     *                       may be read from several threads afterwards
     * @param networkFactory the factory the network is created with
     * @param params         the CGMES import parameters
     * @param reportNode     where the load reports
     * @return the network, one outcome per request, and where the time went
     * @throws IllegalArgumentException if the request list is empty, names a variant twice or names the primary
     * @throws RdfDbException           if the scenario holds no snapshot at one of the requested addresses
     */
    public static VariantLoadResult loadVariants(RdfDbConnection db, String scenario,
                                                 List<VariantRequest> requests,
                                                 RdfDbVariantLoadOptions options, NetworkFactory networkFactory,
                                                 Properties params, ReportNode reportNode) {
        NetworkFactory factory = networkFactory == null ? NetworkFactory.findDefault() : networkFactory;
        ReportNode rn = reportNode == null ? ReportNode.NO_OP : reportNode;
        return VariantBulkLoader.load(db, scenario, requests, options, factory, params, rn);
    }

    /**
     * Load many timesteps of one version as the variants of a single network, addressed by text.
     *
     * <p>The form a user interface calls: the timesteps may be {@code "8:30"} labels, which are resolved against
     * the base day of this scenario, and the variants are named after them.</p>
     *
     * @param db             the open connection
     * @param scenario       the scenario, required
     * @param version        the version label every timestep is taken at, or {@code null} for the newest one of
     *                       each
     * @param timestepTexts  the timesteps, as instants, offset date-times or {@code "8:30"} labels
     * @param options        the naming rule and how each variant is brought to its snapshot
     * @param networkFactory the factory the network is created with
     * @param params         the CGMES import parameters
     * @param reportNode     where the load reports
     * @return the network, one outcome per timestep, and where the time went
     */
    public static VariantLoadResult loadVariants(RdfDbConnection db, String scenario, String version,
                                                 List<String> timestepTexts, RdfDbVariantLoadOptions options,
                                                 NetworkFactory networkFactory, Properties params,
                                                 ReportNode reportNode) {
        Objects.requireNonNull(db);
        Objects.requireNonNull(timestepTexts);
        SnapshotCatalog catalog = db.snapshots(scenario);
        List<VariantRequest> requests = timestepTexts.stream()
                .map(text -> VariantRequest.of(catalog.resolve(version, text)))
                .toList();
        return loadVariants(db, scenario, requests, options, networkFactory, params, reportNode);
    }

    /**
     * Create or update one variant of a network so that it stands for a snapshot, addressed by plain strings.
     *
     * <p>The opt-in form of {@link #update(Network, RdfDbConnection, SnapshotRef, RdfDbUpdateOptions, Properties,
     * ReportNode)}: the state of the named variant is brought to the snapshot, every other variant of the network
     * is left exactly as it is, and anything that could not be done without writing state shared by all variants
     * is refused with {@link UpdateResult.Route#VARIANT_REFUSED} rather than applied.</p>
     *
     * <p>A variant that does not exist is created, by cloning the variant nearest to the target in difference
     * terms; a variant that exists is moved from wherever it stands. The working variant of the calling thread is
     * unchanged when the call returns.</p>
     *
     * @param network       the network holding the variant
     * @param db            the open connection
     * @param scenario      the scenario, required
     * @param version       the version label, or {@code null} for the newest one of that timestep
     * @param timestep      the timestep text, or {@code null} for the base timestep
     * @param targetVariant the variant to create or update, {@code null} for the working one
     * @param params        the CGMES import parameters
     * @param reportNode    where the update reports
     * @return what was done, with {@link UpdateResult#variantId()} naming the variant
     */
    public static UpdateResult update(Network network, RdfDbConnection db, String scenario, String version,
                                      String timestep, String targetVariant, Properties params,
                                      ReportNode reportNode) {
        Objects.requireNonNull(db);
        return update(network, db, db.snapshots(scenario).resolve(version, timestep),
                new RdfDbUpdateOptions().setTargetVariant(targetVariant), params, reportNode);
    }

    /**
     * Refuse an entry point that cannot be expressed as a variant operation.
     *
     * <p>A variant of a network stands for a <em>snapshot</em>. The pre-snapshot entry points address individual
     * stored models or replace a whole profile from the instance file graphs, and neither is a snapshot; running
     * them on a network in variant mode would write into every bound variant at once, which is exactly the thing
     * this feature exists to prevent.</p>
     */
    private static void checkNotVariantMode(Network network, RdfDbUpdateOptions options, String what) {
        String variant = variantModeOf(network, options);
        if (variant != null) {
            throw new RdfDbException("network " + network.getId() + " is in variant mode, so " + what
                    + " cannot be applied to it: variant mode addresses snapshots. Use"
                    + " RdfDbNetworkLoader.update(network, db, scenario, version, timestep, variant, ...)");
        }
    }

    /**
     * Whether this update is a variant operation, and on which variant.
     *
     * <p>Two ways in, and both of them are an <strong>explicit opt-in</strong>: the caller names a target
     * variant, or the network was put into variant mode earlier by such a call or by {@code loadVariants}. From
     * then on every in-place operation of this package is a variant operation, because an unsafe write on the
     * working variant would leak into the bound ones.</p>
     *
     * <p>Merely <em>cloning</em> a variant is not an opt-in. Cloning is the ordinary IIDM idiom for a security
     * analysis, and a caller that has never heard of this feature must keep getting the routes it always got
     * &mdash; including a full reload, which a variant operation may not do. The bindings of such clones are
     * tracked all the same, so that a later opt-in knows what is already there.</p>
     */
    private static String variantModeOf(Network network, RdfDbUpdateOptions options) {
        if (options.getTargetVariant() != null) {
            return options.getTargetVariant();
        }
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        if (!(provenance instanceof RdfDbProvenanceImpl impl) || !impl.isVariantMode()) {
            return null;
        }
        return workingVariantOf(network);
    }

    /**
     * The working variant of the calling thread, or the primary when this thread never selected one.
     *
     * <p>{@code getWorkingVariantId} throws in the thread-local variant context rather than answering the initial
     * variant, and a caller that never selected a variant means the primary one.</p>
     *
     * @param network the network
     * @return the variant identifier, never {@code null}
     */
    static String workingVariantOf(Network network) {
        try {
            return network.getVariantManager().getWorkingVariantId();
        } catch (PowsyblException e) {
            return RdfDbProvenance.PRIMARY_VARIANT;
        }
    }

    /**
     * The differences of a path, fetched in one request, plus the models its ends are.
     *
     * @param byId       every difference of the path, by model identifier
     * @param stateModels the {@code md:Model.*} headers of the models a profile's path starts and ends at
     * @param fetch      how long the request took
     * @param statements how many statements were transferred
     */
    record FetchedDiffs(Map<String, DifferenceModel> byId, Map<String, StoredModel> stateModels, Duration fetch,
                        int statements) {
    }

    /**
     * Fetch every difference of a plan, in one request.
     *
     * <p>Shared by the classic update and by the variant one, which is the point of having it: both walk the same
     * path with the same statements, and only what they do with the result differs.</p>
     *
     * @param db       the open connection
     * @param scenario the scenario
     * @param plan     the plan whose steps to fetch
     * @return what came back
     */
    static FetchedDiffs fetchSteps(RdfDbConnection db, String scenario, UpdatePlan plan) {
        List<StoredModel> all = plan.steps().stream().map(UpdatePlan.DiffStep::model).toList();
        long fetchStart = System.nanoTime();
        List<DifferenceModel> fetched = RdfDbDiffSource.fetchAll(db, all);
        Map<String, StoredModel> stateModels = endModels(db, scenario, plan);
        Duration fetch = Duration.ofNanos(System.nanoTime() - fetchStart);
        Map<String, DifferenceModel> byId = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            byId.put(all.get(i).id(), fetched.get(i));
        }
        return new FetchedDiffs(byId, stateModels, fetch,
                all.stream().mapToInt(model -> (int) Math.max(0, model.tripleCount())).sum());
    }

    /**
     * Two fetches as one.
     *
     * <p>A bulk load fetches every difference of every accepted path in one request. When a target has to be
     * re-planned &mdash; its clone source turned out to be refused at apply time &mdash; the steps of the new
     * path may not be among them, and the extra fetch is folded in here rather than replacing the first one.</p>
     *
     * @param first  what the bulk fetch returned
     * @param second what a re-plan had to fetch on top of it
     * @return the union, timings added up
     */
    static FetchedDiffs merge(FetchedDiffs first, FetchedDiffs second) {
        Map<String, DifferenceModel> byId = new LinkedHashMap<>(first.byId());
        byId.putAll(second.byId());
        Map<String, StoredModel> stateModels = new LinkedHashMap<>(first.stateModels());
        stateModels.putAll(second.stateModels());
        return new FetchedDiffs(byId, stateModels, first.fetch().plus(second.fetch()),
                first.statements() + second.statements());
    }

    /**
     * What composing and applying a path did.
     *
     * @param appliedModelIds the models that were applied, per profile
     * @param compose         how long composing them took
     * @param apply           how long applying them took
     */
    record AppliedDiffs(Map<CgmesSubset, List<String>> appliedModelIds, Duration compose, Duration apply) {
    }

    /**
     * Compose the differences of a path into one model per profile and apply them to a network.
     *
     * <p>The network is left <strong>untouched</strong> when the composed difference cannot be applied in place:
     * that is decided before anything is written, and the {@link CgmesDiffNotApplicableException} it throws is
     * what tells the caller to fall back, to refuse, or to give up on a variant.</p>
     *
     * @param network the network to change
     * @param db      the open connection, for the identity record
     * @param scenario the scenario
     * @param plan    the path
     * @param fetched what {@link #fetchSteps} returned for that path
     * @param options how the difference is applied
     * @param config  the conversion configuration
     * @param rn      where the update reports
     * @return what was applied
     * @throws CgmesDiffNotApplicableException if the composed difference cannot be applied in place
     */
    static AppliedDiffs composeAndApply(Network network, RdfDbConnection db, String scenario, UpdatePlan plan,
                                        FetchedDiffs fetched, RdfDbUpdateOptions options, Conversion.Config config,
                                        ReportNode rn) {
        // A path that walks up one branch and down another - which is what a step from one timestep to the next
        // is - is composed with the upward differences already turned round, and then applied forwards like any
        // other. Only a path that is entirely upward is reverted as a whole
        boolean mixed = plan.isMixedDirection();
        boolean inverted = plan.isAllInverted();
        Map<String, DifferenceModel> byId = fetched.byId();
        Map<String, StoredModel> stateModels = fetched.stateModels();

        long composeStart = System.nanoTime();
        Map<CgmesSubset, List<DifferenceModel>> chains = new EnumMap<>(CgmesSubset.class);
        Map<CgmesSubset, DifferenceModelHeader> headers = new EnumMap<>(CgmesSubset.class);
        Map<CgmesSubset, List<String>> appliedIds = new EnumMap<>(CgmesSubset.class);
        plan.stepsBySubset().forEach((subset, steps) -> {
            // The path is in application order; composing wants it oldest first, which for an undo is the
            // reverse of the order the steps are undone in
            List<UpdatePlan.DiffStep> path = new ArrayList<>(steps);
            if (inverted) {
                Collections.reverse(path);
            }
            chains.put(subset, composable(path, byId, mixed));
            headers.put(subset, snapshotHeader(path.stream().map(UpdatePlan.DiffStep::model).toList(), inverted,
                    steps, plan.targetState().get(subset), stateModels));
            appliedIds.put(subset, steps.stream().map(step -> step.model().id()).toList());
        });
        DifferenceModelSet composed = RdfDbDiffSource.compose(chains, headers);
        Duration compose = Duration.ofNanos(System.nanoTime() - composeStart);

        long applyStart = System.nanoTime();
        if (inverted) {
            CgmesDiffImport.revert(network, composed, config, options.getDiffOptions(), rn);
        } else {
            CgmesDiffImport.apply(network, composed, config, options.getDiffOptions(), rn);
        }
        Duration apply = Duration.ofNanos(System.nanoTime() - applyStart);

        recordSnapshotIdentity(network, db, scenario, plan, stateModels);
        return new AppliedDiffs(appliedIds, compose, apply);
    }

    private static UpdateResult applySnapshotDifferences(Network network, RdfDbConnection db, SnapshotRef target,
                                                         UpdatePlan plan, RdfDbUpdateOptions options,
                                                         Properties params, ReportNode rn, Duration planning) {
        String scenario = target.scenario();
        FetchedDiffs fetched = fetchSteps(db, scenario, plan);
        Conversion.Config config = TripleStoreNetworkLoader.importer().config(params);
        AppliedDiffs applied;
        try {
            applied = composeAndApply(network, db, scenario, plan, fetched, options, config, rn);
        } catch (CgmesDiffNotApplicableException e) {
            return snapshotFallback(network, db, target, plan, options, params, rn, planning,
                    e.getDecision().reasons());
        }

        UpdateStatistics statistics = new UpdateStatistics(planning, fetched.fetch(), applied.compose(),
                applied.apply(), plan.chainLength(), fetched.statements());
        List<String> reasons = plan.checkpointRecommended()
                ? List.of("the chain since the last full snapshot is longer than " + options.getCheckpointAfter()
                        + " differences; consider Checkpoint.create")
                : List.of();
        RdfDbReports.updateRouteReport(rn, scenario, UpdateResult.Route.DIFF_APPLIED, plan.chainLength(), reasons);
        LOGGER.info("Updated network {} to snapshot {} by applying {} difference(s): {}", network.getId(),
                plan.to(), plan.chainLength(), statistics.summary());
        return new UpdateResult(UpdateResult.Route.DIFF_APPLIED, network, applied.appliedModelIds(), reasons,
                statistics);
    }

    /**
     * The differences of one profile in the order they compose, with the upward ones already turned round.
     *
     * <p>A path that walks up one branch and down another cannot be reverted as a whole, so the steps that have to
     * be undone are inverted here and the composed result is applied forwards like any other difference.</p>
     */
    private static List<DifferenceModel> composable(List<UpdatePlan.DiffStep> path,
                                                    Map<String, DifferenceModel> byId, boolean mixed) {
        return path.stream()
                .map(step -> {
                    DifferenceModel model = byId.get(step.model().id());
                    return mixed && step.inverted() ? model.inverted(step.model().toHeader()) : model;
                })
                .toList();
    }

    /**
     * The header the composed difference of a profile carries when the target is a snapshot.
     *
     * <p>Forward it is the target's model, superseding what the network holds; backwards it is the model the
     * network holds, superseding the target's. Either way the receiving network ends up saying it is exactly where
     * the plan took it.</p>
     */
    private static DifferenceModelHeader snapshotHeader(List<StoredModel> ordered, boolean inverted,
                                                        List<UpdatePlan.DiffStep> steps, String targetStateId,
                                                        Map<String, StoredModel> stateModels) {
        StoredModel newest = ordered.get(ordered.size() - 1);
        if (inverted) {
            // The header of the difference being undone, with its md:Model.* terms: the plan query leaves those
            // out and endModels has already read them, so there is nothing to gain from the header-less copy
            return withHeader(newest, stateModels).toHeader().toBuilder()
                    .supersedes(List.of(targetStateId)).build();
        }
        UpdatePlan.DiffStep first = steps.get(0);
        // A profile of a mixed path that only walks *up* ends at an ancestor, not at one of its own steps: the
        // composed difference has to say it is that ancestor, or the network would carry the identity of the
        // difference it has just undone
        if (steps.stream().allMatch(UpdatePlan.DiffStep::inverted)) {
            StoredModel end = stateModels.get(targetStateId);
            DifferenceModelHeader base = end != null ? end.toHeader() : withHeader(newest, stateModels).toHeader();
            return base.toBuilder().supersedes(List.of(first.model().id())).build();
        }
        // The last step of a forward path - and of a mixed one that ends downward - is the target's own model for
        // this profile. What it supersedes is what the network holds now: the model of the first step when that
        // step is one to undo, and otherwise the model the first forward step was recorded against
        StoredModel oldestModel = withHeader(ordered.get(0), stateModels);
        StoredModel newestModel = withHeader(newest, stateModels);
        String current = first.inverted() ? first.model().id()
                : oldestModel.supersedes().isEmpty() ? newestModel.id() : oldestModel.supersedes().get(0);
        return newestModel.toHeader().toBuilder().supersedes(List.of(current)).build();
    }

    /**
     * The model with its {@code md:Model.*} header, when the plan query left it out.
     *
     * <p>The plan returns what a step needs to be applied, not what it needs to be described: the header of the
     * one or two models a composed difference takes its identity from is read separately, and this is where the
     * two halves meet.</p>
     */
    private static StoredModel withHeader(StoredModel model, Map<String, StoredModel> withHeaders) {
        StoredModel full = withHeaders.get(model.id());
        return full != null ? full : model;
    }

    /**
     * The stored model of every target state a path ends at but does not carry, in one request.
     *
     * <p>Walking forward, the model a profile ends at is the last step of that profile; walking backwards it is an
     * ancestor. Both the composed header and the identity record need it, so it is read once and shared.</p>
     */
    private static Map<String, StoredModel> endModels(RdfDbConnection db, String scenario, UpdatePlan plan) {
        Set<String> needed = endModelIds(plan);
        return needed.isEmpty() ? Map.of() : db.catalog(scenario).models(needed);
    }

    /**
     * The models a path needs the {@code md:Model.*} header of, which the plan query deliberately leaves out.
     *
     * <p>The two ends of each profile's path: the composed difference carries the identity of one and supersedes
     * what the other was recorded against. A bulk load unions these over every path it accepted, so that one
     * request serves a whole day.</p>
     *
     * @param plan the path
     * @return the model identifiers
     */
    static Set<String> endModelIds(UpdatePlan plan) {
        Set<CgmesSubset> touched = plan.stepsBySubset().keySet();
        Set<String> needed = new java.util.LinkedHashSet<>();
        plan.stepsBySubset().forEach((subset, steps) -> {
            needed.add(steps.get(0).model().id());
            needed.add(steps.get(steps.size() - 1).model().id());
        });
        plan.targetState().forEach((subset, id) -> {
            if (touched.contains(subset)) {
                needed.add(id);
            }
        });
        return needed;
    }

    /**
     * Fetch the differences and the end models of many paths at once.
     *
     * <p>Two requests for a whole day: one for every distinct difference of every accepted path, one for every
     * model those paths start and end at.</p>
     *
     * @param db       the open connection
     * @param scenario the scenario
     * @param plans    the paths
     * @return what came back, shared by every path
     */
    static FetchedDiffs fetchSteps(RdfDbConnection db, String scenario, List<UpdatePlan> plans) {
        List<StoredModel> all = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        Set<String> needed = new java.util.LinkedHashSet<>();
        for (UpdatePlan plan : plans) {
            plan.steps().forEach(step -> {
                if (seen.add(step.model().id())) {
                    all.add(step.model());
                }
            });
            needed.addAll(endModelIds(plan));
        }
        long fetchStart = System.nanoTime();
        List<DifferenceModel> fetched = RdfDbDiffSource.fetchAll(db, all);
        Map<String, StoredModel> stateModels = needed.isEmpty() ? Map.of()
                : db.catalog(scenario).models(needed);
        Duration fetch = Duration.ofNanos(System.nanoTime() - fetchStart);
        Map<String, DifferenceModel> byId = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            byId.put(all.get(i).id(), fetched.get(i));
        }
        return new FetchedDiffs(byId, stateModels, fetch,
                all.stream().mapToInt(model -> (int) Math.max(0, model.tripleCount())).sum());
    }

    private static UpdateResult snapshotFallback(Network network, RdfDbConnection db, SnapshotRef target,
                                                 UpdatePlan plan, RdfDbUpdateOptions options, Properties params,
                                                 ReportNode rn, Duration planning, List<String> reasons) {
        UpdatePlan full = new UpdatePlan(UpdatePlan.Kind.FULL, plan.from(), plan.to(), List.of(), reasons, 0,
                plan.checkpointRecommended(), plan.distanceToFullSnapshot(), plan.targetState());
        return snapshotFullRoute(network, db, target, full, options, params, rn, planning);
    }

    private static UpdateResult snapshotFullRoute(Network network, RdfDbConnection db, SnapshotRef target,
                                                  UpdatePlan plan, RdfDbUpdateOptions options, Properties params,
                                                  ReportNode rn, Duration planning) {
        String scenario = target.scenario();
        if (!options.isAllowFullReload()) {
            RdfDbReports.updateRouteReport(rn, scenario, UpdateResult.Route.FULL_REQUIRED, 0, plan.reasons());
            return new UpdateResult(UpdateResult.Route.FULL_REQUIRED, network, Map.of(), plan.reasons(),
                    new UpdateStatistics(planning, Duration.ZERO, Duration.ZERO, Duration.ZERO, 0, 0));
        }
        long applyStart = System.nanoTime();
        RdfDbMaterializer.Materialised replacement =
                materialize(db, target, options.getNetworkFactory(), params, rn);
        Duration apply = Duration.ofNanos(System.nanoTime() - applyStart);
        RdfDbReports.updateRouteReport(rn, scenario, UpdateResult.Route.FULL_RELOAD, 0, plan.reasons());
        return new UpdateResult(UpdateResult.Route.FULL_RELOAD, replacement.network(), Map.of(), plan.reasons(),
                new UpdateStatistics(planning, Duration.ZERO, Duration.ZERO, apply, 0, 0));
    }

    private static void recordSnapshotIdentity(Network network, RdfDbConnection db, String scenario,
                                               UpdatePlan plan, Map<String, StoredModel> endModels) {
        // The new identity is the target's state for every profile the path touched, and nothing else. Walking
        // forward that model is the last step of that profile; walking backwards it is an ancestor the path
        // undid its way to, which is not among the steps and is then read in one request
        Map<String, StoredModel> stepModels = new LinkedHashMap<>();
        Set<CgmesSubset> touched = plan.stepsBySubset().keySet();
        plan.steps().forEach(step -> stepModels.put(step.model().id(), step.model()));
        Map<String, StoredModel> resolved = endModels;
        Map<CgmesSubset, StoredModel> ends = new EnumMap<>(CgmesSubset.class);
        plan.targetState().forEach((subset, id) -> {
            if (!touched.contains(subset)) {
                return;
            }
            StoredModel model = stepModels.containsKey(id) ? stepModels.get(id) : resolved.get(id);
            if (model != null) {
                ends.put(subset, model);
            }
        });
        NetworkIdentity.advance(network, ends);
        Map<CgmesSubset, String> ids = NetworkIdentity.modelIds(network);
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        RdfDbProvenanceImpl impl;
        if (provenance instanceof RdfDbProvenanceImpl existing && provenance.scenario().equals(scenario)) {
            impl = existing;
            impl.setModelIds(ids);
        } else {
            impl = new RdfDbProvenanceImpl(db.database(), scenario, List.of(), Instant.now(), ids);
            network.addExtension(RdfDbProvenance.class, impl);
        }
        impl.setSnapshot(plan.to());
        classicOperationDone(network);
    }

    private static void applyPostProcessors(Network network, RdfDbLoadOptions options, ReportNode rn) {
        if (!options.isApplyImportPostProcessors()) {
            return;
        }
        // A file import runs these above the CGMES importer, in Importer.find, and takes the ones the platform
        // configuration activates when the caller names none. A database load has to do both itself, or the two
        // paths differ as soon as a user has configured a post processor.
        List<String> names = options.getPostProcessors();
        if (names.isEmpty()) {
            names = ImportConfig.load().getPostProcessors();
        }
        if (names.isEmpty()) {
            return;
        }
        Map<String, ImportPostProcessor> available = new ImportersServiceLoader().loadPostProcessors().stream()
                .collect(Collectors.toMap(ImportPostProcessor::getName, p -> p, (a, b) -> a));
        ComputationManager computationManager = options.getComputationManager() == null
                ? LocalComputationManager.getDefault()
                : options.getComputationManager();
        for (String name : names) {
            ImportPostProcessor postProcessor = available.get(name);
            if (postProcessor == null) {
                throw new RdfDbException("Import post processor '" + name + "' not found, available: "
                        + available.keySet());
            }
            try {
                postProcessor.process(network, computationManager, rn);
            } catch (Exception e) {
                throw new RdfDbException("Import post processor '" + name + "' failed", e);
            }
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(RdfDbNetworkLoader.class);
}
