/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.TripleStoreNetworkLoader;
import com.powsybl.cgmes.conversion.diff.CgmesDiffNotApplicableException;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Loading a whole day into the variants of one network.
 *
 * <h2>Why it is not a loop over the single update</h2>
 * <p>Ninety-six updates are ninety-six plan queries and ninety-six statement fetches over chains that mostly share
 * their ancestors. This does the same work in <strong>one</strong> chain query, one statement fetch and one
 * catalogue read: the chains of all requested snapshots come back together (see {@code VersionGraph.chains}), the
 * paths are read off them client-side, and the statements of every accepted path are fetched in a single request.
 * The conversion runs once, for the first requested snapshot; every other variant is a clone plus a difference.</p>
 *
 * <h2>What a refusal does</h2>
 * <p>Nothing to the rest. A timestamp whose equipment drifted, or whose difference writes an operational limit, is
 * answered with a {@link VariantOutcome.Status#REFUSED} outcome and its variant is not created; the other
 * ninety-five variants are there and usable. A caller that wants all or nothing reads
 * {@link VariantLoadResult#refused()} and throws its own exception.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class VariantBulkLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(VariantBulkLoader.class);

    /** How many already-loaded variants a new one looks at when choosing what to clone from. */
    private static final int MAX_SOURCES = 4;

    private VariantBulkLoader() {
    }

    /**
     * Build a network whose variants are the requested snapshots.
     *
     * @param db       the open connection
     * @param scenario the scenario, required
     * @param requests the snapshots to load, at least one; the first one becomes the network itself
     * @param options  the naming rule and how each variant is brought to its snapshot
     * @param factory  the factory the network is created with
     * @param params   the CGMES import parameters
     * @param rn       where the load reports
     * @return the network and one outcome per request
     */
    static VariantLoadResult load(RdfDbConnection db, String scenario, List<VariantRequest> requests,
                                  RdfDbVariantLoadOptions options, NetworkFactory factory, Properties params,
                                  ReportNode rn) {
        Objects.requireNonNull(db);
        RdfDbNames.checkScenario(scenario);
        RdfDbVariantLoadOptions effective = options == null ? new RdfDbVariantLoadOptions() : options;
        checkRequests(requests);

        SnapshotCatalog catalog = db.snapshots(scenario);
        VersionGraph graph = db.versionGraph(scenario);
        List<SnapshotRef> refs = requests.stream().map(request -> catalog.readable(request.ref())).toList();

        long planStart = System.nanoTime();
        Map<String, VersionGraph.Start> starts = new LinkedHashMap<>();
        for (int i = 0; i < refs.size(); i++) {
            starts.put(side(i), new VersionGraph.Start(null, refs.get(i)));
        }
        VersionGraph.Chains chains = graph.chains(starts);
        List<String> missing = new ArrayList<>();
        List<SnapshotInfo> targets = new ArrayList<>();
        for (int i = 0; i < refs.size(); i++) {
            List<SnapshotInfo> chain = chains.bySide().get(side(i));
            if (chain == null || chain.isEmpty()) {
                missing.add(refs.get(i).toString());
            } else {
                targets.add(chain.get(0));
            }
        }
        if (!missing.isEmpty()) {
            throw new RdfDbException("scenario '" + scenario + "' of " + db.database() + " holds no snapshot "
                    + missing + "; nothing was loaded");
        }
        Day day = new Day(refs, targets, namesOf(requests, targets, effective), chains);
        Duration planning = Duration.ofNanos(System.nanoTime() - planStart);

        // The first request is the network itself, converted from the data; no second plan query is sent for it
        MaterializationPlan materialization = graph.materialization(day.chain(0), chains.fullGraphs(),
                chains.diffs());
        Map<String, StoredModel> stateModels = db.catalog(scenario).models(materialization.targetState().values());
        RdfDbNetworkLoader.LoadResult primary = RdfDbMaterializer.materialize(db, scenario, targets.get(0),
                materialization, stateModels, factory, params, rn);
        Network network = primary.network();
        if (effective.isAllowVariantMultiThreadAccess()) {
            // Before any variant exists: growing the per-variant arrays is not safe while readers are running
            network.getVariantManager().allowVariantMultiThreadAccess(true);
        }
        RdfDbProvenanceImpl provenance = (RdfDbProvenanceImpl) network.getExtension(RdfDbProvenance.class);
        // The opt-in: asking for a day as variants is asking for variant mode, and it stays on
        provenance.enableVariantMode();

        return bind(db, scenario, network, provenance, graph, day, effective, params, rn, primary.statistics(),
                planning);
    }

    private static String side(int index) {
        return "B" + index;
    }

    /**
     * The requested snapshots of a bulk load, resolved: request {@code i} is {@code refs[i]}, reached as
     * {@code targets[i]} and loaded into the variant {@code names[i]}.
     *
     * @param refs    the addresses, checked against the scenario
     * @param targets the snapshots they resolved to
     * @param names   the variant each one becomes
     * @param chains  the chain of every target, and what their models are
     */
    private record Day(List<SnapshotRef> refs, List<SnapshotInfo> targets, List<String> names,
                       VersionGraph.Chains chains) {

        List<SnapshotInfo> chain(int index) {
            return chains.bySide().get(side(index));
        }

        int size() {
            return refs.size();
        }
    }

    /** One accepted request: which variant, from which source, over which path. */
    private record Accepted(int index, String variantId, String sourceVariant, UpdatePlan plan) {
    }

    private static VariantLoadResult bind(RdfDbConnection db, String scenario, Network network,
                                          RdfDbProvenanceImpl provenance, VersionGraph graph, Day day,
                                          RdfDbVariantLoadOptions options, Properties params, ReportNode rn,
                                          LoadStatistics primaryLoad, Duration planning) {
        RdfDbUpdateOptions updateOptions = VariantPlans.variantSafe(options.getUpdateOptions());
        Map<Integer, VariantOutcome> outcomes = new LinkedHashMap<>();
        List<Accepted> accepted = new ArrayList<>();
        // The primary is the first request: it is the network, and it also gets a variant of its own so that
        // every requested snapshot is addressed the same way
        List<VariantPlans.Source> sources = new ArrayList<>();
        sources.add(new VariantPlans.Source(RdfDbProvenance.PRIMARY_VARIANT, day.chain(0)));

        long planStart = System.nanoTime();
        for (int i = 0; i < day.size(); i++) {
            List<SnapshotInfo> chain = day.chain(i);
            Optional<VariantPlans.Chosen> chosen = nearest(graph, sources, chain, day.chains(),
                    options.getUpdateOptions());
            List<String> blocking = VariantPlans.blockingReasons(chosen.map(VariantPlans.Chosen::plan).orElse(null));
            if (!blocking.isEmpty()) {
                outcomes.put(i, refused(day, i, day.names().get(i), blocking));
                continue;
            }
            accepted.add(new Accepted(i, day.names().get(i), chosen.get().sourceVariant(), chosen.get().plan()));
            sources.add(new VariantPlans.Source(day.names().get(i), chain));
        }
        Duration planningTotal = planning.plus(Duration.ofNanos(System.nanoTime() - planStart));

        // One request for every difference of every accepted path, and one for the models their ends are
        long fetchStart = System.nanoTime();
        RdfDbNetworkLoader.FetchedDiffs fetched = RdfDbNetworkLoader.fetchSteps(db, scenario,
                accepted.stream().map(Accepted::plan).toList());
        Duration fetch = Duration.ofNanos(System.nanoTime() - fetchStart);

        long cloneStart = System.nanoTime();
        List<String> fromPrimary = accepted.stream()
                .filter(step -> RdfDbProvenance.PRIMARY_VARIANT.equals(step.sourceVariant()))
                .map(Accepted::variantId).toList();
        if (!fromPrimary.isEmpty()) {
            network.getVariantManager().cloneVariant(RdfDbProvenance.PRIMARY_VARIANT, fromPrimary);
        }
        Duration cloning = Duration.ofNanos(System.nanoTime() - cloneStart);

        Conversion.Config config = TripleStoreNetworkLoader.importer().config(params);
        Duration applyTotal = Duration.ZERO;
        // Plan time cannot know which targets will survive their own apply: the network aware safety check needs
        // the statements, and a store written before pdb:variantSafe existed says nothing at all. A target whose
        // chosen source turned out to be refused is therefore re-planned here against the variants that are
        // really there - which costs no request, every chain is already in hand. Primary first, then in the
        // order they were bound
        Map<String, List<SnapshotInfo>> aliveChains = new LinkedHashMap<>();
        aliveChains.put(RdfDbProvenance.PRIMARY_VARIANT, day.chain(0));
        for (Accepted planned : accepted) {
            Accepted step = planned;
            if (!aliveChains.containsKey(step.sourceVariant())) {
                Optional<VariantPlans.Chosen> again = nearest(graph, aliveChains.entrySet().stream()
                        .map(e -> new VariantPlans.Source(e.getKey(), e.getValue())).toList(),
                        day.chain(step.index()), day.chains(), options.getUpdateOptions());
                List<String> blocking = VariantPlans.blockingReasons(
                        again.map(VariantPlans.Chosen::plan).orElse(null));
                if (!blocking.isEmpty()) {
                    outcomes.put(step.index(), refused(day, step.index(), step.variantId(), blocking));
                    continue;
                }
                step = new Accepted(step.index(), step.variantId(), again.get().sourceVariant(), again.get().plan());
                // The bulk fetch already holds every difference of every plan-time path; a re-planned path
                // usually walks the very same ones, and then nothing has to be sent
                if (!isFetched(fetched, step.plan())) {
                    fetched = RdfDbNetworkLoader.merge(fetched,
                            RdfDbNetworkLoader.fetchSteps(db, scenario, List.of(step.plan())));
                }
            }
            // A variant id occurs once in the accepted list, so what was not cloned from the primary above is
            // cloned here, from its own source
            if (!fromPrimary.contains(step.variantId())) {
                long start = System.nanoTime();
                network.getVariantManager().cloneVariant(step.sourceVariant(), step.variantId());
                cloning = cloning.plus(Duration.ofNanos(System.nanoTime() - start));
            }
            VariantOutcome outcome = apply(network, db, scenario, provenance, step, day.refs().get(step.index()),
                    day.targets().get(step.index()), fetched, updateOptions, config, rn);
            outcomes.put(step.index(), outcome);
            applyTotal = applyTotal.plus(outcome.apply());
            if (outcome.isBound()) {
                aliveChains.put(step.variantId(), day.chain(step.index()));
            }
        }

        List<VariantOutcome> ordered = new ArrayList<>();
        for (int i = 0; i < day.size(); i++) {
            ordered.add(outcomes.get(i));
        }
        provenance.setLastRefused(ordered.stream().filter(outcome -> !outcome.isBound()).toList());
        network.getVariantManager().setWorkingVariant(RdfDbProvenance.PRIMARY_VARIANT);
        VariantLoadResult result = new VariantLoadResult(network, ordered, primaryLoad, planningTotal, fetch,
                cloning, applyTotal);
        LOGGER.info("Loaded {} snapshot(s) of scenario '{}' as variants of network {}: {}", day.size(),
                scenario, network.getId(), result.summary());
        return result;
    }

    private static VariantOutcome refused(Day day, int index, String variantId, List<String> reasons) {
        return VariantOutcome.refused(variantId, day.refs().get(index), day.targets().get(index).iri(), reasons);
    }

    /** Whether every difference and every end model a path needs is already in hand. */
    private static boolean isFetched(RdfDbNetworkLoader.FetchedDiffs fetched, UpdatePlan plan) {
        return plan.steps().stream().allMatch(step -> fetched.byId().containsKey(step.model().id()))
                && fetched.stateModels().keySet().containsAll(RdfDbNetworkLoader.endModelIds(plan));
    }

    /**
     * The variant nearest to a target in difference terms.
     *
     * <p>Only the primary and the last few variants that were bound are looked at. That is what a day looks like
     * &mdash; the neighbour in time is the neighbour in the version graph &mdash; and it keeps the choice linear
     * in the number of requests rather than quadratic.</p>
     */
    private static Optional<VariantPlans.Chosen> nearest(VersionGraph graph, List<VariantPlans.Source> sources,
                                                         List<SnapshotInfo> target, VersionGraph.Chains chains,
                                                         RdfDbUpdateOptions options) {
        int from = Math.max(1, sources.size() - MAX_SOURCES + 1);
        List<VariantPlans.Source> candidates = new ArrayList<>();
        candidates.add(sources.get(0));
        candidates.addAll(sources.subList(from, sources.size()));
        return VariantPlans.nearest(candidates, chain -> graph.path(chain, target, chains.diffs(), options));
    }

    /** Bring one already-cloned variant to its snapshot, inside its own scope. */
    private static VariantOutcome apply(Network network, RdfDbConnection db, String scenario,
                                        RdfDbProvenanceImpl provenance, Accepted step, SnapshotRef ref,
                                        SnapshotInfo target, RdfDbNetworkLoader.FetchedDiffs fetched,
                                        RdfDbUpdateOptions options, Conversion.Config config, ReportNode rn) {
        if (step.plan().kind() == UpdatePlan.Kind.NOOP) {
            provenance.rebind(step.variantId(), target.iri());
            return new VariantOutcome(step.variantId(), ref, target.iri(), VariantOutcome.Status.BOUND,
                    List.of(), step.sourceVariant(), 0, Duration.ZERO);
        }
        long start = System.nanoTime();
        boolean settled = false;
        try {
            VariantScope.call(network, provenance, step.variantId(), () -> RdfDbNetworkLoader.composeAndApply(
                    network, db, scenario, step.plan(), fetched, options, config, rn));
            settled = true;
        } catch (CgmesDiffNotApplicableException e) {
            settled = true;
            removeVariant(network, provenance, step.variantId());
            return VariantOutcome.refused(step.variantId(), ref, target.iri(), e.getDecision().reasons());
        } catch (PowsyblException e) {
            settled = true;
            removeVariant(network, provenance, step.variantId());
            return VariantOutcome.refused(step.variantId(), ref, target.iri(),
                    List.of("applying the differences failed: " + e.getMessage()));
        } finally {
            if (!settled) {
                removeVariant(network, provenance, step.variantId());
            }
        }
        Duration apply = Duration.ofNanos(System.nanoTime() - start);
        provenance.rebind(step.variantId(), target.iri());
        return new VariantOutcome(step.variantId(), ref, target.iri(), VariantOutcome.Status.BOUND, List.of(),
                step.sourceVariant(), step.plan().chainLength(), apply);
    }

    private static void removeVariant(Network network, RdfDbProvenanceImpl provenance, String variantId) {
        if (network.getVariantManager().getVariantIds().contains(variantId)) {
            network.getVariantManager().removeVariant(variantId);
        }
        provenance.unbind(variantId);
    }

    // ------------------------------------------------------------------ naming and validation

    private static void checkRequests(List<VariantRequest> requests) {
        Objects.requireNonNull(requests);
        if (requests.isEmpty()) {
            throw new IllegalArgumentException("loadVariants needs at least one snapshot to load");
        }
        Set<String> explicit = new LinkedHashSet<>();
        for (VariantRequest request : requests) {
            String id = request.variantId();
            if (id == null) {
                continue;
            }
            if (RdfDbProvenance.PRIMARY_VARIANT.equals(id)) {
                throw new IllegalArgumentException("'" + RdfDbProvenance.PRIMARY_VARIANT + "' is the variant the"
                        + " network itself is and cannot be requested; the first snapshot of the list is loaded"
                        + " into it anyway");
            }
            if (!explicit.add(id)) {
                throw new IllegalArgumentException("the variant '" + id + "' is requested twice");
            }
        }
    }

    /**
     * The identifier of each requested variant.
     *
     * <p>An explicit one wins. Otherwise the ISO instant of the timestamp when the timestamps of all requests are
     * different, and {@code version@instant} when they are not &mdash; a day walked timestamp by timestamp reads as
     * {@code 2021-02-09T08:30:00Z}, a study comparing two versions of one moment as
     * {@code 2@2021-02-09T08:30:00Z}.</p>
     */
    private static List<String> namesOf(List<VariantRequest> requests, List<SnapshotInfo> targets,
                                        RdfDbVariantLoadOptions options) {
        List<String> labels = targets.stream().map(info -> info.timestamp().toString()).toList();
        boolean distinct = new LinkedHashSet<>(labels).size() == labels.size();
        List<String> names = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (int i = 0; i < requests.size(); i++) {
            String name = requests.get(i).variantId();
            if (name == null) {
                name = options.getNaming() != null ? options.getNaming().apply(targets.get(i))
                        : distinct ? labels.get(i) : targets.get(i).version() + "@" + labels.get(i);
            }
            if (!used.add(name)) {
                throw new IllegalArgumentException("the naming rule gives the variant name '" + name + "' to more"
                        + " than one of the requested snapshots; name them explicitly");
            }
            names.add(name);
        }
        return names;
    }
}
