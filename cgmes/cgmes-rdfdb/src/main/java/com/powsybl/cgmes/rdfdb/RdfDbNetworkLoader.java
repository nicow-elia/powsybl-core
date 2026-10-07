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
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
     * A loaded network, where the time went, and what the network does not hold.
     *
     * @param network       the network
     * @param statistics    the timings of the load
     * @param extraProfiles the custom profiles of the snapshot ({@link Profiles}) and the graph holding each of
     *                      them, as the metadata graph records it: the conversion reads only the nine standard
     *                      profiles, so these are handed to the caller instead, to be read with
     *                      {@link RdfDbConnection#fetchGraph}. Empty for a scenario-addressed load and for a
     *                      projection that names none
     */
    public record LoadResult(Network network, LoadStatistics statistics, Map<String, String> extraProfiles) {

        /**
         * @param network       see {@link #network()}
         * @param statistics    see {@link #statistics()}
         * @param extraProfiles see {@link #extraProfiles()}
         */
        public LoadResult {
            extraProfiles = Collections.unmodifiableSortedMap(Profiles.map(extraProfiles));
        }

        /**
         * A load without custom profiles.
         *
         * @param network    the network
         * @param statistics the timings of the load
         */
        public LoadResult(Network network, LoadStatistics statistics) {
            this(network, statistics, Map.of());
        }
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
            refusePartialLoad(scenario, options, "is versioned", "a snapshot of it");
            LoadResult versioned = loadWithStatistics(db,
                    SnapshotRef.latest(scenario, db.snapshots(scenario).onlyAuthority()), factory, params, rn);
            applyPostProcessors(versioned.network(), options, rn);
            return versioned;
        }
        if (snapshot.hasDifferences()) {
            // The scenario holds a model-level difference chain (no snapshots): its instance file graphs are the
            // oldest state it holds, and "the scenario" means its newest one. Materialising it is the only way to
            // see the differences.
            refusePartialLoad(scenario, options, "holds difference models", "a target of it");
            LoadResult materialised = RdfDbMaterializer.materialize(db, scenario, snapshot,
                    targetsOf(snapshot, DiffTarget.head()), factory, params, rn);
            applyPostProcessors(materialised.network(), options, rn);
            // The time spent reading the metadata graph is the listing a versioned load does instead of listing graphs
            LoadStatistics statistics = materialised.statistics().withListGraphs(readCatalog);
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
        Set<String> subsets = options.getProfiles();
        List<GraphInfo> graphs = subsets == null ? all
                : all.stream().filter(g -> g.profile() != null && subsets.contains(g.profile())).toList();
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

            return new LoadResult(network,
                    LoadStatistics.of(listGraphs, fetched.fetch(), fetched, Duration.ZERO, describe, convert));
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

    /** A difference states properties of its profile: a partial load would build a state that never existed. */
    private static void refusePartialLoad(String scenario, RdfDbLoadOptions options, String what, String instead) {
        if (options.getProfiles() != null) {
            throw new RdfDbException("Scenario '" + scenario + "' " + what + ", and a partial load of a versioned"
                    + " scenario is not supported: a difference states properties of the profile it belongs to,"
                    + " and leaving a profile out would build a network from a state that never existed. Load the"
                    + " whole scenario, or " + instead);
        }
    }

    /**
     * Bring a network to a stored state of a scenario.
     *
     * <p>The decision is made before anything is read: if the network is already there, nothing happens; if the
     * stored state is reachable by applying differences the network has not seen, they are fetched (one SELECT, or
     * one GET per graph on the Graph Store route),
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
            return update(network, db, SnapshotRef.latest(scenario, db.snapshots(scenario).onlyAuthority()), effective,
                    params, rn);
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
                yield terminal(rn, scenario, UpdateResult.Route.NOOP, network, List.of(), planning,
                        Duration.ZERO);
            }
            case DIFF -> applyDifferences(network, db, scenario, snapshot, target, plan, effective, params, rn,
                    planning);
            case FULL -> fullRoute(network, db, scenario, snapshot, target, plan, effective, params, rn, planning);
        };
    }

    private static DiffUpdatePlanner.Plan planUpdate(Network network, RdfDbProvenance provenance,
                                                     CatalogSnapshot snapshot, String scenario, DiffTarget target,
                                                     RdfDbUpdateOptions options) {
        Map<String, String> identity = provenance != null && !provenance.modelIds().isEmpty()
                ? provenance.modelIds() : NetworkIdentity.modelIds(network, options.getProfiles());
        Map<String, String> currentIds = Profiles.map();
        currentIds.putAll(identity);
        currentIds.keySet().retainAll(options.getProfiles());
        if (currentIds.isEmpty()) {
            throw new RdfDbException("The network carries no CGMES model identity for the profiles "
                    + options.getProfiles() + ", so there is nothing to bring forward from");
        }
        Map<String, String> targetIds = targetIds(snapshot, target, currentIds.keySet());
        if (targetIds.isEmpty()) {
            return new DiffUpdatePlanner.Plan(DiffUpdatePlanner.Route.FULL, Map.of(), Map.of(), Map.of(),
                    List.of("scenario '" + scenario + "' holds no model of the profiles " + currentIds.keySet()));
        }
        Map<String, List<StoredModel>> targetChains = snapshot.chainsDown(targetIds);
        Map<String, List<StoredModel>> currentChains = snapshot.chainsDown(currentIds);
        return DiffUpdatePlanner.plan(scenario, currentIds, targetChains, currentChains,
                options.getMaxDiffChain());
    }

    private static Map<String, String> targetIds(CatalogSnapshot snapshot, DiffTarget target,
                                                      Set<String> subsets) {
        if (!target.isHead()) {
            return Profiles.map(target.modelIds());
        }
        Map<String, String> heads = Profiles.map();
        subsets.forEach(subset -> snapshot.head(subset).ifPresent(model -> heads.put(subset, model.id())));
        return heads;
    }

    /** Every profile the scenario holds, at the state the target names. */
    private static Map<String, StoredModel> targetsOf(CatalogSnapshot snapshot, DiffTarget target) {
        if (target.isHead()) {
            return snapshot.heads();
        }
        Map<String, StoredModel> targets = Profiles.map();
        target.modelIds().forEach((subset, id) -> {
            StoredModel model = snapshot.model(id).orElseThrow(() -> new RdfDbException("Scenario '"
                    + snapshot.scenario() + "' holds no model " + id));
            if (!model.subset().equals(subset)) {
                throw new RdfDbException("Model " + id + " of scenario '" + snapshot.scenario() + "' describes the "
                        + model.subset() + " profile, not " + subset);
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
        Map<String, DifferenceModel> byId = RdfDbDiffSource.fetchById(db, all);
        Duration fetch = Duration.ofNanos(System.nanoTime() - fetchStart);

        long composeStart = System.nanoTime();
        Map<String, List<DifferenceModel>> chains = Profiles.map();
        Map<String, DifferenceModelHeader> headers = Profiles.map();
        Map<String, List<String>> appliedIds = Profiles.map();
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
    private static DifferenceModelHeader composedHeader(DiffUpdatePlanner.Plan plan, String subset,
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
            return terminal(rn, scenario, UpdateResult.Route.FULL_REQUIRED, network, plan.reasons(), planning,
                    Duration.ZERO);
        }
        long applyStart = System.nanoTime();
        // Null only when the network belongs to another scenario: nothing has been read of the target scenario yet
        CatalogSnapshot of = snapshot != null ? snapshot : db.catalog(scenario).snapshot();
        LoadResult replacement = RdfDbMaterializer.materialize(db, scenario, of,
                targetsOf(of, target), options.getNetworkFactory(), params, rn);
        Duration apply = Duration.ofNanos(System.nanoTime() - applyStart);
        return terminal(rn, scenario, UpdateResult.Route.FULL_RELOAD, replacement.network(), plan.reasons(), planning,
                apply);
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
                                       Map<String, StoredModel> targets) {
        NetworkIdentity.advance(network, targets);
        provenanceAt(network, db, scenario);
        classicOperationDone(network);
    }

    /** The provenance of a network that was just advanced, now stating the models it holds. */
    private static RdfDbProvenanceImpl provenanceAt(Network network, RdfDbConnection db, String scenario) {
        Map<String, String> ids = NetworkIdentity.modelIds(network);
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        if (provenance instanceof RdfDbProvenanceImpl impl && provenance.scenario().equals(scenario)) {
            impl.setModelIds(ids);
            return impl;
        }
        RdfDbProvenanceImpl created = new RdfDbProvenanceImpl(db.database(), scenario, List.of(), Instant.now(), ids);
        network.addExtension(RdfDbProvenance.class, created);
        return created;
    }

    // ------------------------------------------------------------------ snapshots

    /**
     * Build the network of one snapshot.
     *
     * <p>The mandated entry point of the versioning layer. The address is always complete: the scenario says which
     * base grid model, the modelling authority whose grid, the timestamp which moment of it, the version which study
     * state. A {@code null} version means the newest one of that timestamp, a {@code null} timestamp the base
     * timestamp of the authority's tree.</p>
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
        return loadWithStatistics(db, ref, Set.of(), networkFactory, params, reportNode).network();
    }

    /**
     * Build the network of one snapshot from some of its profiles.
     *
     * <p>The profiles are a projection, not a part of the address: the snapshot is the same, and the network holds
     * the profiles named here, each at the state the snapshot has for it, and always the boundary, which belongs to
     * the scenario rather than to a projection. The equipment profile is what a network
     * is built from, so a projection without it fails in the conversion.</p>
     *
     * @param db             the open connection
     * @param ref            the address of the snapshot
     * @param profiles       the profiles to read, or {@code null} or empty for every profile of the snapshot. A
     *                       custom profile ({@link Profiles}) is never read into the network: a load with statistics
     *                       names its graph in {@link LoadResult#extraProfiles()}
     * @param networkFactory the factory the network is created with
     * @param params         the CGMES import parameters
     * @param reportNode     where the load reports
     * @return the network, at that snapshot
     * @throws RdfDbException if the snapshot does not hold one of the profiles
     */
    public static Network load(RdfDbConnection db, SnapshotRef ref, Set<String> profiles,
                               NetworkFactory networkFactory, Properties params, ReportNode reportNode) {
        return loadWithStatistics(db, ref, profiles, networkFactory, params, reportNode).network();
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
        return loadWithStatistics(db, ref, Set.of(), networkFactory, params, reportNode);
    }

    /**
     * Build the network of some profiles of one snapshot, and say where the time went.
     *
     * @param db             the open connection
     * @param ref            the address of the snapshot
     * @param profiles       the profiles to read, or {@code null} or empty for every profile of the snapshot. A
     *                       custom profile ({@link Profiles}) is never read into the network: a load with statistics
     *                       names its graph in {@link LoadResult#extraProfiles()}
     * @param networkFactory the factory the network is created with
     * @param params         the CGMES import parameters
     * @param reportNode     where the load reports
     * @return the network and the timings
     */
    public static LoadResult loadWithStatistics(RdfDbConnection db, SnapshotRef ref, Set<String> profiles,
                                                NetworkFactory networkFactory, Properties params,
                                                ReportNode reportNode) {
        Objects.requireNonNull(db);
        Objects.requireNonNull(ref);
        NetworkFactory factory = networkFactory == null ? NetworkFactory.findDefault() : networkFactory;
        ReportNode rn = reportNode == null ? ReportNode.NO_OP : reportNode;
        long t0 = System.nanoTime();
        LoadResult materialised = materialize(db, ref, profiles, factory, params, rn);
        Duration readCatalog = Duration.ofNanos(System.nanoTime() - t0)
                .minus(materialised.statistics().total());
        LoadStatistics statistics = materialised.statistics()
                .withListGraphs(readCatalog.isNegative() ? Duration.ZERO : readCatalog);
        LOGGER.info("Loaded network {} from snapshot {} of {}: {}", materialised.network().getId(), ref,
                db.database(), statistics.summary());
        return new LoadResult(materialised.network(), statistics, materialised.extraProfiles());
    }

    private static LoadResult materialize(RdfDbConnection db, SnapshotRef ref, Set<String> profiles,
                                          NetworkFactory factory, Properties params, ReportNode rn) {
        return materialize(db, db.snapshots(ref.scenario()).require(ref), profiles, factory, params, rn);
    }

    private static LoadResult materialize(RdfDbConnection db, SnapshotInfo info, Set<String> profiles,
                                          NetworkFactory factory, Properties params, ReportNode rn) {
        MaterializationPlan plan = db.versionGraph(info.scenario()).materialization(info.iri()).project(profiles);
        Map<String, StoredModel> stateModels =
                db.catalog(info.scenario()).models(plan.targetState().values());
        return RdfDbMaterializer.materialize(db, info.scenario(), info, plan, stateModels, factory, params, rn);
    }

    /** Report the route of an update that applied no difference, and answer it. */
    private static UpdateResult terminal(ReportNode rn, String scenario, UpdateResult.Route route, Network network,
                                         List<String> reasons, Duration planning, Duration apply) {
        RdfDbReports.updateRouteReport(rn, scenario, route, 0, reasons);
        return new UpdateResult(route, network, Map.of(), reasons,
                new UpdateStatistics(planning, Duration.ZERO, Duration.ZERO, apply, 0, 0));
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
                yield terminal(rn, target.toString(), UpdateResult.Route.NOOP, network, List.of(), planning,
                        Duration.ZERO);
            }
            case DIFF -> applySnapshotDifferences(network, db, target, plan, effective, params, rn, planning);
            case FULL -> snapshotFullRoute(network, db, target, plan, effective, params, rn, planning);
        };
    }

    /**
     * Load many snapshots of one scenario as the variants of a single network.
     *
     * <p>A whole day in one network: the first requested snapshot is converted from the data and becomes the
     * network, every requested snapshot &mdash; including the first &mdash; gets a named variant, and the rest of
     * them are clones plus the differences between them. One chain query, one statement fetch and one conversion,
     * whatever the number of timestamps.</p>
     *
     * <p>A snapshot that cannot be reached inside a variant does not fail the load: its variant is not created and
     * its {@link VariantOutcome} says why, so a day with one drifted timestamp still gives the other ninety-five.
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
                    + " RdfDbNetworkLoader.update(network, db, ref, new RdfDbUpdateOptions().setTargetVariant(variant),"
                    + " ...)");
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
        return Objects.requireNonNullElse(VariantScope.workingVariantOrNull(network), RdfDbProvenance.PRIMARY_VARIANT);
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
        return fetchSteps(db, scenario, List.of(plan));
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
    record AppliedDiffs(Map<String, List<String>> appliedModelIds, Duration compose, Duration apply) {
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
     * @throws CgmesDiffNotApplicableException if the composed difference cannot be applied in place, or a
     *                                         difference of a capability version this reader does not trust fails
     *                                         its table
     */
    static AppliedDiffs composeAndApply(Network network, RdfDbConnection db, String scenario, UpdatePlan plan,
                                        FetchedDiffs fetched, RdfDbUpdateOptions options, Conversion.Config config,
                                        ReportNode rn) {
        recheck(plan, fetched, options.getDiffOptions().isVariantSafeOnly());
        // A path that walks up one branch and down another - which is what a step from one timestamp to the next
        // is - is composed with the upward differences already turned round, and then applied forwards like any
        // other. Only a path that is entirely upward is reverted as a whole
        boolean inverted = plan.isAllInverted();
        long composeStart = System.nanoTime();
        DifferenceModelSet composed = compose(plan, fetched, inverted);
        Duration compose = Duration.ofNanos(System.nanoTime() - composeStart);

        long applyStart = System.nanoTime();
        if (inverted) {
            CgmesDiffImport.revert(network, composed, config, options.getDiffOptions(), rn);
        } else {
            CgmesDiffImport.apply(network, composed, config, options.getDiffOptions(), rn);
        }
        Duration apply = Duration.ofNanos(System.nanoTime() - applyStart);

        recordSnapshotIdentity(network, db, scenario, plan, fetched.stateModels());
        Map<String, List<String>> appliedIds = Profiles.map();
        plan.stepsBySubset().forEach((subset, steps) -> appliedIds.put(subset,
                steps.stream().map(step -> step.model().id()).toList()));
        return new AppliedDiffs(appliedIds, compose, apply);
    }

    /**
     * Compose the differences of a path into one difference per profile.
     *
     * @param plan       the path
     * @param fetched    what {@link #fetchSteps} returned for it
     * @param revertable whether the set is to be reverted as a whole: then the steps, all upward, are composed as
     *                   they were written, oldest first, and the set carries the identity of the model being undone.
     *                   Otherwise every upward step is turned round here and the set is applied forwards
     * @return the composed set
     */
    private static DifferenceModelSet compose(UpdatePlan plan, FetchedDiffs fetched, boolean revertable) {
        Map<String, List<DifferenceModel>> chains = Profiles.map();
        Map<String, DifferenceModelHeader> headers = Profiles.map();
        plan.stepsBySubset().forEach((subset, steps) -> {
            // The path is in application order; composing wants it oldest first, which for an undo is the
            // reverse of the order the steps are undone in
            List<UpdatePlan.DiffStep> path = new ArrayList<>(steps);
            if (revertable) {
                Collections.reverse(path);
            }
            chains.put(subset, composable(path, fetched.byId(), !revertable));
            headers.put(subset, snapshotHeader(path.stream().map(UpdatePlan.DiffStep::model).toList(), revertable,
                    steps, plan.targetState().get(subset), fetched.stateModels()));
        });
        return RdfDbDiffSource.compose(chains, headers);
    }

    /**
     * The changes between two snapshots of one tree, as one difference per profile.
     *
     * <p>What a network at {@code from} has to apply to be at {@code to}: the path between the two
     * ({@link VersionGraph}), its differences fetched, and the steps composed as an update composes them &mdash; the
     * ones up out of {@code from} turned round, the ones down into {@code to} as they are &mdash; into a set that is
     * applied forwards. It is a statement set, not an in-place update, so it is made whether or not the differences
     * are fast-route capable and however long the path is. Each composed difference carries the identity of
     * {@code to}'s model of its profile and supersedes {@code from}'s. A custom profile stored whole is not a
     * difference and is not in it ({@link SnapshotCatalog#graphsOf}).</p>
     *
     * <p>Three requests: the plan, the differences, and the models the path starts and ends at.</p>
     *
     * @param db   the open connection
     * @param from the address of the first snapshot
     * @param to   the address of the second snapshot, of the same scenario and modelling authority
     * @return the composed set, empty when the two are the same snapshot
     * @throws RdfDbException if the two are of different scenarios or modelling authorities, or either is not
     *                        held
     */
    public static DifferenceModelSet changesBetween(RdfDbConnection db, SnapshotRef from, SnapshotRef to) {
        Objects.requireNonNull(db);
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (!from.scenario().equals(to.scenario())) {
            throw new RdfDbException("no difference leads from " + from + " to " + to + ": diffs never cross"
                    + " scenarios");
        }
        UpdatePlan plan = db.versionGraph(from.scenario()).plan(from, to,
                new RdfDbUpdateOptions().setMaxDiffChain(Integer.MAX_VALUE));
        if (plan.steps().isEmpty()) {
            if (plan.kind() == UpdatePlan.Kind.FULL) {
                throw new RdfDbException("no difference leads from " + from + " to " + to + ": "
                        + String.join("; ", plan.reasons()));
            }
            return new DifferenceModelSet(List.of());
        }
        return compose(plan, fetchSteps(db, from.scenario(), plan), false);
    }

    /** How many differences {@link #recheck} checked against this reader's table, for the tests. */
    private static final AtomicInteger RECHECKED = new AtomicInteger();

    static int recheckedDifferences() {
        return RECHECKED.get();
    }

    /**
     * Check the differences whose stored verdicts this reader does not trust against its own capability table.
     *
     * <p>The planner took {@code pdb:fastPredicatesOnly} and {@code pdb:variantSafe} as they are stored. For a
     * difference written by a newer capability table ({@link UpdatePlan.DiffStep#recheck()}) they may promise more
     * than this reader can do, so its fetched statements go through {@link FastRouteCapabilities#check} &mdash;
     * and {@link FastRouteCapabilities#checkVariantSafe} on a variant route &mdash; before anything is composed. A
     * refusal is the ordinary not-applicable answer, with reasons naming both versions: the caller then takes its
     * usual fallback (a full reload, or the refusal of the variant). No request: the statements are in hand.</p>
     *
     * @throws CgmesDiffNotApplicableException if a re-checked difference cannot be applied in place by this reader
     */
    private static void recheck(UpdatePlan plan, FetchedDiffs fetched, boolean variantRoute) {
        for (UpdatePlan.DiffStep step : plan.steps()) {
            if (!step.recheck()) {
                continue;
            }
            RECHECKED.incrementAndGet();
            DifferenceModelSet one = new DifferenceModelSet(List.of(fetched.byId().get(step.model().id())));
            CgmesDiffImport.Decision decision = FastRouteCapabilities.check(one);
            if (decision.route() == CgmesDiffImport.Route.FAST && variantRoute) {
                decision = FastRouteCapabilities.checkVariantSafe(one);
            }
            if (decision.route() == CgmesDiffImport.Route.SLOW_REQUIRED) {
                String why = "difference " + step.model().id() + " of snapshot " + step.snapshot()
                        + " was written by capability version " + step.model().capabilities() + " and this reader ("
                        + FastRouteCapabilities.version() + ") cannot apply it in place: ";
                throw new CgmesDiffNotApplicableException(new CgmesDiffImport.Decision(decision.route(),
                        decision.blocking().stream().map(blocking -> new CgmesDiffImport.BlockingStatement(
                                blocking.subset(), blocking.statement(), why + blocking.reason())).toList()));
            }
        }
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
        RdfDbReports.updateRouteReport(rn, target.toString(), UpdateResult.Route.DIFF_APPLIED, plan.chainLength(), reasons);
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
                                                    Map<String, DifferenceModel> byId, boolean turnUpward) {
        return path.stream()
                .map(step -> {
                    DifferenceModel model = byId.get(step.model().id());
                    return turnUpward && step.inverted() ? model.inverted(step.model().toHeader()) : model;
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
        Set<String> touched = plan.stepsBySubset().keySet();
        Set<String> needed = new LinkedHashSet<>();
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
        Set<String> seen = new LinkedHashSet<>();
        Set<String> needed = new LinkedHashSet<>();
        for (UpdatePlan plan : plans) {
            plan.steps().forEach(step -> {
                if (seen.add(step.model().id())) {
                    all.add(step.model());
                }
            });
            needed.addAll(endModelIds(plan));
        }
        long fetchStart = System.nanoTime();
        Map<String, DifferenceModel> byId = RdfDbDiffSource.fetchById(db, all);
        Map<String, StoredModel> stateModels = needed.isEmpty() ? Map.of()
                : db.catalog(scenario).models(needed);
        Duration fetch = Duration.ofNanos(System.nanoTime() - fetchStart);
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
            return terminal(rn, target.toString(), UpdateResult.Route.FULL_REQUIRED, network, plan.reasons(), planning,
                    Duration.ZERO);
        }
        long applyStart = System.nanoTime();
        LoadResult replacement =
                materialize(db, target, Set.of(), options.getNetworkFactory(), params, rn);
        Duration apply = Duration.ofNanos(System.nanoTime() - applyStart);
        return terminal(rn, target.toString(), UpdateResult.Route.FULL_RELOAD, replacement.network(), plan.reasons(),
                planning, apply);
    }

    private static void recordSnapshotIdentity(Network network, RdfDbConnection db, String scenario,
                                               UpdatePlan plan, Map<String, StoredModel> endModels) {
        // The new identity is the target's state for every profile the path touched, and nothing else. Walking
        // forward that model is the last step of that profile; walking backwards it is an ancestor the path
        // undid its way to, which is not among the steps and is then read in one request
        Map<String, StoredModel> stepModels = new LinkedHashMap<>();
        Set<String> touched = plan.stepsBySubset().keySet();
        plan.steps().forEach(step -> stepModels.put(step.model().id(), step.model()));
        Map<String, StoredModel> ends = Profiles.map();
        plan.targetState().forEach((subset, id) -> {
            if (!touched.contains(subset)) {
                return;
            }
            StoredModel model = stepModels.getOrDefault(id, endModels.get(id));
            if (model != null) {
                ends.put(subset, model);
            }
        });
        NetworkIdentity.advance(network, ends);
        provenanceAt(network, db, scenario).setSnapshot(plan.to());
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
