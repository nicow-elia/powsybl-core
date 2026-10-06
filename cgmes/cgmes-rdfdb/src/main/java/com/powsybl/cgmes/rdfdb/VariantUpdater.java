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
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Bringing one <em>variant</em> of a network to a stored snapshot.
 *
 * <h2>What makes it different from an ordinary update</h2>
 * <p>An ordinary update owns the whole network: when the difference route fails it may rebuild the network and
 * hand the caller a new instance. A variant update owns one slot of every per-variant array and nothing else, so
 * it may not rebuild anything &mdash; the other variants of that network are states a caller is holding on to. It
 * therefore <strong>refuses</strong>, with reasons, and leaves every variant exactly as it was. That is the whole
 * design: {@link UpdateResult.Route#VARIANT_REFUSED} is a normal answer, not an error.</p>
 *
 * <h2>Where a new variant comes from</h2>
 * <p>Creating a variant means cloning one, and which one decides how much work is left: cloning the variant that
 * already holds {@code 1.0@08:30} and applying one difference is cheaper than cloning the primary and walking a
 * day. The candidates are the primary and the variants bound to the <em>same timestamp</em> as the target, and all
 * of their chains come out of one request together with the target's, so choosing the nearest one costs nothing
 * beyond the query the update needs anyway.</p>
 *
 * <h2>Order of operations</h2>
 * <p>Everything that can refuse runs before anything is created: the scenario check without a query, the plan, the
 * stored variant-safety flags. Only then is a variant cloned, and only then are statements fetched. A refusal
 * after that point &mdash; the network aware safety check, which needs the statements &mdash; removes the variant
 * this call created again.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class VariantUpdater {

    private static final Logger LOGGER = LoggerFactory.getLogger(VariantUpdater.class);

    /** How many chains one update asks for: the target, plus the primary and three bound variants. */
    private static final int MAX_CANDIDATES = 4;

    private static final String TARGET_SIDE = "B0";

    private VariantUpdater() {
    }

    /**
     * Bring one variant of a network to a snapshot, creating the variant when it does not exist yet.
     *
     * @param network   the network holding the variant
     * @param db        the open connection
     * @param target    the snapshot the variant has to stand for
     * @param variantId the variant to create or update
     * @param options   how far the update may go and what happens when it cannot stay inside the variant
     * @param params    the CGMES import parameters
     * @param rn        where the update reports
     * @return what was done
     */
    static UpdateResult update(Network network, RdfDbConnection db, SnapshotRef target, String variantId,
                               RdfDbUpdateOptions options, Properties params, ReportNode rn) {
        Objects.requireNonNull(variantId);
        String scenario = target.scenario();
        RdfDbProvenance existing = network.getExtension(RdfDbProvenance.class);
        // The opt-in happens before anything can refuse, and on the provenance the network already has: naming a
        // variant is asking for variant mode even when this very call turns out to be impossible. A refusal that
        // left the network in classic mode would hand the next update a full reload - a new network object - on
        // a network the caller is holding variants of
        if (options.getTargetVariant() != null && existing instanceof RdfDbProvenanceImpl already) {
            already.enableVariantMode();
        }
        Call call = new Call(network, db, options, params, rn, existing == null);
        if (existing != null && !existing.scenario().equals(scenario)) {
            return refused(call, variantId, target, null,
                    List.of("the network was loaded from scenario '" + existing.scenario() + "' and the target"
                            + " is in scenario '" + scenario + "': differences never cross scenarios"));
        }
        RdfDbProvenanceImpl provenance = provenanceOf(network, db, scenario, options);
        if (options.getTargetVariant() != null) {
            provenance.enableVariantMode();
        }

        boolean exists = network.getVariantManager().getVariantIds().contains(variantId);
        boolean primary = RdfDbProvenance.PRIMARY_VARIANT.equals(variantId);
        if (exists && !primary && provenance.variantBinding(variantId).isEmpty()) {
            throw new RdfDbException("variant '" + variantId + "' of network " + network.getId()
                    + " is not bound to a snapshot: it was created outside this package, or the variant it was"
                    + " cloned from was not bound either. Remove it and let the update create it, or load the"
                    + " whole day with RdfDbNetworkLoader.loadVariants");
        }

        long planStart = System.nanoTime();
        SnapshotCatalog catalog = db.snapshots(scenario);
        SnapshotRef resolved = catalog.readable(target);
        VersionGraph graph = db.versionGraph(scenario);

        List<Candidate> all = candidatesFor(network, provenance, variantId, exists, resolved);
        List<Candidate> candidates = all.stream()
                .filter(candidate -> sameAuthority(candidate.snapshotIri(), resolved)).toList();
        if (candidates.isEmpty() && !all.isEmpty()) {
            // The same string arithmetic as across scenarios: the snapshot IRI carries its modelling authority
            return refused(call, variantId, target, null, List.of("the network's variants stand for modelling"
                    + " authority '" + RdfDbNames.refOf(all.get(0).snapshotIri()).modellingAuthority() + "' and"
                    + " the target is '" + resolved.modellingAuthority() + "': diffs never cross modelling"
                    + " authorities"));
        }
        Map<String, VersionGraph.Start> starts = new LinkedHashMap<>();
        starts.put(TARGET_SIDE, new VersionGraph.Start(null, resolved));
        for (int i = 0; i < candidates.size(); i++) {
            starts.put(side(i), new VersionGraph.Start(candidates.get(i).snapshotIri(), null));
        }
        VersionGraph.Chains chains = graph.chains(starts);
        List<SnapshotInfo> targetChain = chains.bySide().get(TARGET_SIDE);
        if (targetChain == null || targetChain.isEmpty()) {
            throw new RdfDbException("scenario '" + scenario + "' holds no snapshot " + resolved);
        }
        String targetIri = targetChain.get(0).iri();

        List<VariantPlans.Source> sources = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            sources.add(new VariantPlans.Source(candidates.get(i).variantId(), chains.bySide().get(side(i))));
        }
        VariantPlans.Chosen chosen = VariantPlans.nearest(sources,
                from -> graph.path(from, targetChain, chains.diffs(), options)).orElse(null);
        Duration planning = Duration.ofNanos(System.nanoTime() - planStart);
        if (chosen == null) {
            return refused(call, variantId, resolved, targetIri,
                    List.of("no variant of the network is at a snapshot of scenario '" + scenario
                            + "' that the target can be reached from"));
        }

        UpdatePlan plan = chosen.plan();
        List<String> blocking = VariantPlans.blockingReasons(plan);
        if (!blocking.isEmpty()) {
            return refused(call, variantId, resolved, targetIri, blocking);
        }
        if (plan.kind() == UpdatePlan.Kind.NOOP) {
            return noop(call, provenance, variantId, exists, chosen, targetIri, planning, scenario);
        }
        return applyOnVariant(call, provenance, variantId, exists, chosen, resolved, targetIri, planning);
    }

    private static String side(int candidate) {
        return "A" + candidate;
    }

    /**
     * What every step of one update is given.
     *
     * @param network           the network holding the variant
     * @param db                the open connection
     * @param options           the caller's options
     * @param params            the CGMES import parameters
     * @param rn                where the update reports
     * @param createdProvenance whether this update gave the network its provenance, which a refusal takes back
     */
    private record Call(Network network, RdfDbConnection db, RdfDbUpdateOptions options, Properties params,
                        ReportNode rn, boolean createdProvenance) {
    }

    /** One variant whose state a new variant could be cloned from. */
    private record Candidate(String variantId, String snapshotIri) {
    }

    /**
     * Where the state of the variant may come from.
     *
     * <p>An existing variant is its own and only candidate: its state <em>is</em> the snapshot it is bound to, and
     * taking it anywhere else would throw that state away. A new one is cloned from the nearest of the primary and
     * the variants at the same timestamp, which are the ones a difference of one step away from the target is
     * likely to sit on. The primary comes first, so that a tie is decided in favour of the pristine clone
     * source.</p>
     */
    private static List<Candidate> candidatesFor(Network network, RdfDbProvenanceImpl provenance, String variantId,
                                                 boolean exists, SnapshotRef target) {
        if (exists) {
            VariantBinding binding = provenance.variantBinding(variantId).orElseThrow();
            return binding.snapshotIri() == null ? List.of()
                    : List.of(new Candidate(variantId, binding.snapshotIri()));
        }
        List<Candidate> candidates = new ArrayList<>();
        provenance.variantBinding(RdfDbProvenance.PRIMARY_VARIANT)
                .filter(binding -> binding.snapshotIri() != null)
                .ifPresent(binding -> candidates.add(
                        new Candidate(RdfDbProvenance.PRIMARY_VARIANT, binding.snapshotIri())));
        provenance.variantBindings().values().stream()
                .filter(binding -> binding.snapshotIri() != null)
                // A target whose timestamp the caller left open (the base timestamp of the tree) is not
                // resolved yet at this point, and resolving it would cost a request; every bound variant is then
                // a candidate, which is what the cap below keeps bounded anyway
                .filter(binding -> target.timestamp() == null
                        || Objects.equals(binding.timestamp(), target.timestamp()))
                .filter(binding -> network.getVariantManager().getVariantIds().contains(binding.variantId()))
                .sorted(Comparator.comparing(VariantBinding::boundAt).reversed())
                .limit(MAX_CANDIDATES - 1L)
                .forEach(binding -> candidates.add(new Candidate(binding.variantId(), binding.snapshotIri())));
        return candidates;
    }

    private static boolean sameAuthority(String snapshotIri, SnapshotRef target) {
        SnapshotRef ref = RdfDbNames.refOf(snapshotIri);
        return ref != null && ref.modellingAuthority().equals(target.modellingAuthority());
    }

    /** The variant is already at the target, or a clone of a variant that is. */
    private static UpdateResult noop(Call call, RdfDbProvenanceImpl provenance, String variantId, boolean exists,
                                     VariantPlans.Chosen chosen, String targetIri, Duration planning,
                                     String scenario) {
        if (!exists) {
            call.network().getVariantManager().cloneVariant(chosen.sourceVariant(), variantId);
        }
        rebind(provenance, variantId, targetIri);
        provenance.setLastRefused(List.of());
        RdfDbReports.updateRouteReport(call.rn(), scenario, UpdateResult.Route.NOOP, 0, List.of());
        return new UpdateResult(UpdateResult.Route.NOOP, call.network(), Map.of(), List.of(),
                new UpdateStatistics(planning, Duration.ZERO, Duration.ZERO, Duration.ZERO, 0, 0), variantId);
    }

    /**
     * Clone the nearest variant when needed and apply the path on it, inside its own scope.
     */
    private static UpdateResult applyOnVariant(Call call, RdfDbProvenanceImpl provenance, String variantId,
                                               boolean exists, VariantPlans.Chosen chosen, SnapshotRef resolved,
                                               String targetIri, Duration planning) {
        Network network = call.network();
        RdfDbConnection db = call.db();
        ReportNode rn = call.rn();
        String scenario = resolved.scenario();
        UpdatePlan plan = chosen.plan();
        // Cloning happens outside the scope on purpose: the binding listener reads the network-level identity for
        // a clone of the primary, and inside a scope that identity describes the variant being operated on
        boolean created = !exists;
        if (created) {
            network.getVariantManager().cloneVariant(chosen.sourceVariant(), variantId);
        }
        RdfDbUpdateOptions variantOptions = VariantPlans.variantSafe(call.options());
        Conversion.Config config = TripleStoreNetworkLoader.importer().config(call.params());

        // Fetching needs neither the network nor the identity, so it stays outside the scope: a transient
        // endpoint error then costs nothing at all rather than the binding of an intact variant
        RdfDbNetworkLoader.FetchedDiffs fetched = RdfDbNetworkLoader.fetchSteps(db, scenario, plan);
        RdfDbNetworkLoader.AppliedDiffs applied;
        boolean settled = false;
        try {
            applied = VariantScope.call(network, provenance, variantId, () -> RdfDbNetworkLoader.composeAndApply(
                    network, db, scenario, plan, fetched, variantOptions, config, rn));
            settled = true;
        } catch (CgmesDiffNotApplicableException e) {
            settled = true;
            undo(network, provenance, variantId, created);
            return refused(call, variantId, resolved, targetIri, e.getDecision().reasons());
        } catch (PowsyblException e) {
            // The apply got far enough to change something: the variant no longer stands for any stored state
            settled = true;
            if (created) {
                undo(network, provenance, variantId, true);
                throw e;
            }
            provenance.unbind(variantId);
            throw new RdfDbException("variant '" + variantId + "' of network " + network.getId() + " is in an"
                    + " undefined state and was unbound: applying the differences to " + resolved + " failed"
                    + " half way through", e);
        } finally {
            if (!settled) {
                // A failure no layer of this stack declares; the variant is in an undefined state either way
                undo(network, provenance, variantId, created);
                provenance.unbind(variantId);
            }
        }
        rebind(provenance, variantId, targetIri);

        UpdateStatistics statistics = new UpdateStatistics(planning, fetched.fetch(), applied.compose(),
                applied.apply(), plan.chainLength(), fetched.statements());
        RdfDbReports.updateRouteReport(rn, resolved.toString(), UpdateResult.Route.DIFF_APPLIED,
                plan.chainLength(), List.of());
        LOGGER.info("Brought variant '{}' of network {} to snapshot {} by applying {} difference(s) from '{}': {}",
                variantId, network.getId(), resolved, plan.chainLength(), chosen.sourceVariant(),
                statistics.summary());
        provenance.setLastRefused(List.of());
        return new UpdateResult(UpdateResult.Route.DIFF_APPLIED, network, applied.appliedModelIds(), List.of(),
                statistics, variantId);
    }

    /** Take a variant this call created back out, so that a refusal really leaves the network untouched. */
    private static void undo(Network network, RdfDbProvenanceImpl provenance, String variantId, boolean created) {
        if (created && network.getVariantManager().getVariantIds().contains(variantId)) {
            network.getVariantManager().removeVariant(variantId);
        }
        provenance.unbind(variantId);
    }

    /**
     * Record what the variant stands for now.
     *
     * <p>The address is derived from the IRI of the snapshot that was really reached, never from the request: a
     * caller may ask for "the newest version of the base timestamp" and the binding has to say which one that
     * turned out to be.</p>
     */
    private static void rebind(RdfDbProvenanceImpl provenance, String variantId, String targetIri) {
        if (RdfDbProvenance.PRIMARY_VARIANT.equals(variantId)) {
            provenance.setSnapshot(targetIri);
            return;
        }
        provenance.rebind(variantId, targetIri);
    }

    /**
     * Refuse, and remember why.
     *
     * <p>Under {@link RdfDbUpdateOptions.VariantFallback#SEPARATE_NETWORK} the caller still gets the state it
     * asked for, but in a network of its own; the multi-variant network is untouched either way.</p>
     */
    private static UpdateResult refused(Call call, String variantId, SnapshotRef target, String targetIri,
                                        List<String> reasons) {
        Network network = call.network();
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        if (call.createdProvenance()) {
            // "The network handed in, unchanged" has to be literally true, extensions included
            network.removeExtension(RdfDbProvenance.class);
        } else if (provenance instanceof RdfDbProvenanceImpl impl) {
            impl.setLastRefused(List.of(VariantOutcome.refused(variantId, target, targetIri, reasons)));
        }
        RdfDbReports.updateRouteReport(call.rn(), target.toString(), UpdateResult.Route.VARIANT_REFUSED, 0,
                reasons);
        LOGGER.info("Refused to bring variant '{}' of network {} to snapshot {}: {}", variantId, network.getId(),
                target, reasons);
        if (call.options().getVariantFallback() == RdfDbUpdateOptions.VariantFallback.SEPARATE_NETWORK) {
            long start = System.nanoTime();
            Network separate = RdfDbNetworkLoader.load(call.db(), target, call.options().getNetworkFactory(),
                    call.params(), call.rn());
            Duration apply = Duration.ofNanos(System.nanoTime() - start);
            return new UpdateResult(UpdateResult.Route.FULL_RELOAD, separate, Map.of(), reasons,
                    new UpdateStatistics(Duration.ZERO, Duration.ZERO, Duration.ZERO, apply, 0, 0), variantId);
        }
        return new UpdateResult(UpdateResult.Route.VARIANT_REFUSED, network, Map.of(), reasons,
                UpdateStatistics.none(), variantId);
    }

    /** The provenance of the network, created for a file-loaded network. The scenario is checked by the caller. */
    private static RdfDbProvenanceImpl provenanceOf(Network network, RdfDbConnection db, String scenario,
                                                    RdfDbUpdateOptions options) {
        RdfDbProvenance existing = network.getExtension(RdfDbProvenance.class);
        if (existing != null) {
            return (RdfDbProvenanceImpl) existing;
        }
        Map<CgmesSubset, String> identity = NetworkIdentity.modelIds(network, options.getProfiles());
        RdfDbProvenanceImpl created = new RdfDbProvenanceImpl(db.database(), scenario, List.of(), Instant.now(),
                identity);
        network.addExtension(RdfDbProvenance.class, created);
        // Which snapshot the network already is decides where a new variant is cloned from; a network read from
        // files says so only through its model identifiers
        if (!identity.isEmpty()) {
            db.snapshots(scenario).byState(identity)
                    .ifPresent(info -> created.setSnapshot(info.iri()));
        }
        return created;
    }
}
