/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Record a change on a network and store it as a difference in a scenario of an RDF database, in one call.
 *
 * <p>The database counterpart of {@code CgmesDiffExport.export}: the recorded changes are translated into one
 * difference model per profile and handed to {@link RdfDbDifferenceSink}, which writes them under the rules of the
 * scenario. What this class adds is the two pieces of bookkeeping that only make sense when the destination is a
 * database rather than a file.</p>
 *
 * <h2>The sender is advanced</h2>
 * <p>A file export leaves the metadata of the network it read untouched, which is right: a document is handed to
 * somebody else and the sender has not changed. A database export is different &mdash; the difference is now
 * <em>the</em> newest version of that profile in that scenario &mdash; so the sender is advanced to it. Without
 * that, a second export from the same network would supersede the same base model again, fork the chain, and be
 * refused; with it, recording and exporting repeatedly just works.</p>
 *
 * <h2>One model per profile</h2>
 * <p>A stored chain versions one model of one profile, so a network that carries two models of the same profile
 * &mdash; a merged model with two modelling authorities, or one imported with
 * {@code iidm.import.cgmes.cgm-with-subnetworks} &mdash; cannot be the sender of a difference: there is no single
 * model for it to supersede. Such a network is refused before anything is translated.</p>
 *
 * <h2>Differences never cross scenarios</h2>
 * <p>A network that was loaded from one scenario cannot be exported into another: its model identifiers name
 * models of the scenario it came from, and a difference on them belongs there. A network that was read from files
 * has no scenario and can be exported into any scenario that holds the models it names &mdash; the same files
 * uploaded twice give the same identifiers &mdash; and it is marked as belonging to that scenario afterwards.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class RdfDbExport {

    /**
     * What an export produced.
     *
     * @param exportedEvents the changes that reached the database, in the order they were written
     * @param stored         the nodes that were written, one per profile the changes touched
     */
    public record Result(List<NetworkEvent> exportedEvents, List<StoredModel> stored) {

        /**
         * @param exportedEvents see {@link #exportedEvents()}
         * @param stored         see {@link #stored()}
         */
        public Result {
            exportedEvents = List.copyOf(exportedEvents);
            stored = List.copyOf(stored);
        }

        /**
         * @param subset the CGMES profile
         * @return the stored difference of a profile, or empty
         */
        public Optional<StoredModel> get(CgmesSubset subset) {
            return stored.stream().filter(model -> model.subset() == subset).findFirst();
        }
    }

    private RdfDbExport() {
    }

    /**
     * Translate recorded changes and store them in a scenario.
     *
     * @param network  the network the changes were recorded on
     * @param events   the recorded changes
     * @param db       the open connection
     * @param scenario the scenario to write into. Required and never guessed: it says which base grid model the
     *                 difference applies on
     * @param options  the granularity, the header values and the unsupported change behaviour
     * @return what was exported and what was stored
     * @throws RdfDbException         if the scenario is blank, or if the network belongs to another scenario
     * @throws RdfDbConflictException if the difference does not apply where it says it does
     */
    public static Result export(Network network, Collection<NetworkEvent> events, RdfDbConnection db,
                                String scenario, CgmesDiffExport.ExportOptions options) {
        return export(network, events, db, scenario, options, ReportNode.NO_OP);
    }

    /**
     * Translate recorded changes and store them in a scenario, reporting what was written.
     *
     * @param network    the network the changes were recorded on
     * @param events     the recorded changes
     * @param db         the open connection
     * @param scenario   the scenario to write into
     * @param options    the granularity, the header values and the unsupported change behaviour
     * @param reportNode where the stored differences and their unstored dependencies are reported
     * @return what was exported and what was stored
     * @throws RdfDbException         if the scenario is blank, or if the network belongs to another scenario
     * @throws RdfDbConflictException if the difference does not apply where it says it does
     */
    public static Result export(Network network, Collection<NetworkEvent> events, RdfDbConnection db,
                                String scenario, CgmesDiffExport.ExportOptions options, ReportNode reportNode) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(db);
        RdfDbNames.checkScenario(scenario);
        checkSameScenario(network, scenario);
        // In variant mode the whole export - the sender check included - describes the working variant and
        // supersedes the model that variant is at. Outside it, nothing changes
        RdfDbProvenanceImpl impl = variantModeProvenance(network);
        if (impl != null) {
            String working = RdfDbNetworkLoader.workingVariantOf(network);
            try (VariantScope scope = VariantScope.enter(network, impl, working)) {
                return writeModels(network, events, db, scenario,
                        variantOptions(network, options, working), reportNode);
            }
        }
        Result result = writeModels(network, events, db, scenario, options, reportNode);
        RdfDbNetworkLoader.classicOperationDone(network);
        return result;
    }

    /** Translate the changes and append them to the model chain of the scenario. */
    private static Result writeModels(Network network, Collection<NetworkEvent> events, RdfDbConnection db,
                                      String scenario, CgmesDiffExport.ExportOptions options,
                                      ReportNode reportNode) {
        // A difference can only describe these two, and each of them has to be one model to be superseded
        NetworkIdentity.modelIds(network,
                EnumSet.of(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS));

        CgmesDiffExport.Result exported = CgmesDiffExport.toDifferences(network, events,
                options == null ? new CgmesDiffExport.ExportOptions() : options);
        RdfDbDifferenceSink sink = new RdfDbDifferenceSink(db, scenario, reportNode);
        sink.accept(exported.differences());
        List<StoredModel> stored = sink.stored();
        advanceSender(network, db, scenario, stored);
        return new Result(exported.exportedEvents(), stored);
    }

    /** The provenance of a network that a caller has opted into variant mode, or {@code null}. */
    private static RdfDbProvenanceImpl variantModeProvenance(Network network) {
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        return provenance instanceof RdfDbProvenanceImpl impl && impl.isVariantMode() ? impl : null;
    }

    /**
     * Translate recorded changes and store them as a new snapshot.
     *
     * <p>The versioned form of {@link #export(Network, Collection, RdfDbConnection, String,
     * CgmesDiffExport.ExportOptions)}: instead of appending to the chain of each profile, the difference becomes
     * one addressable version of the scenario. The scenario time of every header is set to the target's timestep,
     * so that the rule "a snapshot's timestep is the scenario time of its members" holds by construction.</p>
     *
     * @param network    the network the changes were recorded on
     * @param events     the recorded changes
     * @param db         the open connection
     * @param target     the address the new snapshot gets; a {@code null} version takes the next label of that
     *                   timestep's chain
     * @param options    the granularity, the header values and the unsupported change behaviour
     * @param reportNode where the stored differences are reported
     * @return what was exported, what was stored and the snapshot it became
     * @throws RdfDbException         if the network belongs to another scenario
     * @throws RdfDbConflictException if the address is taken or the chain would fork
     */
    public static SnapshotResult export(Network network, Collection<NetworkEvent> events, RdfDbConnection db,
                                        SnapshotRef target, CgmesDiffExport.ExportOptions options,
                                        ReportNode reportNode) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(db);
        Objects.requireNonNull(target);
        SnapshotCatalog catalog = db.snapshots(target.scenario());
        catalog.check(target);
        checkSameScenario(network, target.scenario());

        String timestep = target.timestep() == null ? catalog.baseTimestep() : target.timestep();
        SnapshotRef effective = target.isLatest()
                ? new SnapshotRef(target.scenario(), catalog.nextVersionLabel(timestep), timestep)
                : target.at(timestep);

        // In variant mode every operation of this package is a variant operation: the difference describes the
        // working variant and supersedes the model that variant is at, not the primary's
        RdfDbProvenanceImpl impl = variantModeProvenance(network);
        if (impl != null) {
            String working = RdfDbNetworkLoader.workingVariantOf(network);
            // The same rules as exportVariant: the values of that variant, and a change IIDM does not store per
            // variant is an unsupported change rather than something written into one variant's history
            try (VariantScope scope = VariantScope.enter(network, impl, working)) {
                return writeSnapshot(network, events, db, catalog, effective, timestep,
                        variantOptions(network, options, working), reportNode);
            }
        }
        SnapshotResult result =
                writeSnapshot(network, events, db, catalog, effective, timestep, options, reportNode);
        RdfDbNetworkLoader.classicOperationDone(network);
        return result;
    }

    /** Translate the changes and store them as the given snapshot; the caller decides the variant context. */
    private static SnapshotResult writeSnapshot(Network network, Collection<NetworkEvent> events,
                                                RdfDbConnection db, SnapshotCatalog catalog, SnapshotRef effective,
                                                String timestep, CgmesDiffExport.ExportOptions options,
                                                ReportNode reportNode) {
        // The sender check reads the identity, so it belongs inside whatever variant context the caller set up
        NetworkIdentity.modelIds(network,
                EnumSet.of(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS));
        CgmesDiffExport.Result exported = translate(network, events, timestep, options);
        return store(network, db, catalog, exported, effective, reportNode);
    }

    /**
     * Turn the recorded changes into one difference per profile, dated with the timestep being written.
     *
     * <p>The scenario time belongs to the snapshot, not to the caller's options, so the options are copied: a
     * caller reusing its object for a second export into another timestep must not inherit it.</p>
     */
    private static CgmesDiffExport.Result translate(Network network, Collection<NetworkEvent> events,
                                                    String timestep, CgmesDiffExport.ExportOptions options) {
        CgmesDiffExport.ExportOptions effectiveOptions = copyOf(options)
                .setScenarioTime(ZonedDateTime.parse(timestep));
        return CgmesDiffExport.toDifferences(network, events, effectiveOptions);
    }

    /** Store an already translated difference set as a snapshot, and advance the sender to it. */
    private static SnapshotResult store(Network network, RdfDbConnection db, SnapshotCatalog catalog,
                                        CgmesDiffExport.Result exported, SnapshotRef effective,
                                        ReportNode reportNode) {
        SnapshotInfo snapshot = catalog.putDiff(exported.differences(), effective,
                reportNode == null ? ReportNode.NO_OP : reportNode);

        List<StoredModel> stored = db.catalog(effective.scenario())
                .models(snapshot.state().values()).values().stream()
                .filter(model -> snapshot.members().contains(model.id()))
                .toList();
        advanceSender(network, db, effective.scenario(), stored);
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        if (provenance instanceof RdfDbProvenanceImpl impl) {
            impl.setSnapshot(snapshot.iri());
        }
        return new SnapshotResult(exported.exportedEvents(), stored, snapshot);
    }

    /**
     * Translate recorded changes and store them as a new snapshot.
     *
     * @param network the network the changes were recorded on
     * @param events  the recorded changes
     * @param db      the open connection
     * @param target  the address the new snapshot gets
     * @param options the granularity, the header values and the unsupported change behaviour
     * @return what was exported, what was stored and the snapshot it became
     */
    public static SnapshotResult export(Network network, Collection<NetworkEvent> events, RdfDbConnection db,
                                        SnapshotRef target, CgmesDiffExport.ExportOptions options) {
        return export(network, events, db, target, options, ReportNode.NO_OP);
    }

    /**
     * What a versioned export produced.
     *
     * @param exportedEvents the changes that reached the database
     * @param stored         the difference nodes that were written
     * @param snapshot       the snapshot they became
     */
    public record SnapshotResult(List<NetworkEvent> exportedEvents, List<StoredModel> stored,
                                 SnapshotInfo snapshot) {

        /**
         * @param exportedEvents see {@link #exportedEvents()}
         * @param stored         see {@link #stored()}
         * @param snapshot       see {@link #snapshot()}
         */
        public SnapshotResult {
            exportedEvents = List.copyOf(exportedEvents);
            stored = List.copyOf(stored);
        }

        /**
         * @param subset the CGMES profile
         * @return the stored difference of a profile, or empty
         */
        public Optional<StoredModel> get(CgmesSubset subset) {
            return stored.stream().filter(model -> model.subset() == subset).findFirst();
        }
    }

    /**
     * Translate recorded changes and store them as a new snapshot, addressing the timestep by text.
     *
     * <p>The form a user interface calls: the timestep may be a {@code "8:30"} label, which is resolved against the
     * base day of <em>this</em> scenario.</p>
     *
     * @param network      the network the changes were recorded on
     * @param events       the recorded changes
     * @param db           the open connection
     * @param scenario     the scenario to write into, required
     * @param version      the version label the new snapshot gets, or {@code null} for the next label of that
     *                     timestep's chain
     * @param timestepText the timestep as an instant, an offset date-time or an {@code "8:30"} label, or
     *                     {@code null} for the base timestep
     * @param options      the granularity, the header values and the unsupported change behaviour
     * @param reportNode   where the stored differences are reported
     * @return what was exported, what was stored and the snapshot it became
     */
    public static SnapshotResult export(Network network, Collection<NetworkEvent> events, RdfDbConnection db,
                                        String scenario, String version, String timestepText,
                                        CgmesDiffExport.ExportOptions options, ReportNode reportNode) {
        Objects.requireNonNull(db);
        return export(network, events, db, db.snapshots(scenario).resolve(version, timestepText), options,
                reportNode);
    }

    // ------------------------------------------------------------------ one variant at a time

    /**
     * Write the changes recorded on one variant as the successor of <em>that variant's</em> snapshot.
     *
     * <p>A network whose variants stand for the timesteps of a day is a day of parallel histories, and a change
     * recorded on {@code 08:30} belongs after {@code 08:30}, not after whatever the primary variant happens to be
     * at. The target is therefore derived from the binding of the variant: same scenario, same timestep, next
     * version of that timestep's chain.</p>
     *
     * <p>The whole export runs inside the variant's scope, so the values written are that variant's values and
     * the {@code md:Model.Supersedes} of the difference names that variant's model. Changes recorded on another
     * variant are dropped; changes IIDM does not store per variant are refused when the network holds more than
     * one variant, because such a change belongs to all of them.</p>
     *
     * @param network    the network the changes were recorded on
     * @param events     the recorded changes
     * @param db         the open connection
     * @param variantId  the variant whose history is being written
     * @param newVersion the version label the new snapshot gets, or {@code null} for the next one of that
     *                   timestep's chain
     * @param options    the granularity, the header values and the unsupported change behaviour
     * @param reportNode where the stored differences are reported
     * @return what was exported, what was stored and the snapshot it became
     * @throws RdfDbException if the variant is not bound to a snapshot, or if the options name a scenario time
     *                        that is not the variant's timestep
     */
    public static SnapshotResult exportVariant(Network network, Collection<NetworkEvent> events,
                                               RdfDbConnection db, String variantId, String newVersion,
                                               CgmesDiffExport.ExportOptions options, ReportNode reportNode) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(db);
        Objects.requireNonNull(variantId);
        RdfDbProvenanceImpl provenance = boundProvenance(network, variantId);
        // Naming a variant is the opt-in here exactly as it is for an update: from now on every in-place
        // operation of this module is a variant operation, so nothing can quietly write across the variants
        provenance.enableVariantMode();
        VariantBinding binding = binding(provenance, network, variantId);
        SnapshotRef target = targetOf(db, binding, newVersion, options, variantId, new LinkedHashMap<>());
        SnapshotCatalog catalog = db.snapshots(target.scenario());
        catalog.check(target);

        CgmesDiffExport.ExportOptions variantOptions = variantOptions(network, options, variantId);
        try (VariantScope scope = VariantScope.enter(network, provenance, variantId)) {
            CgmesDiffExport.Result exported = translate(network, events, target.timestep(), variantOptions);
            return store(network, db, catalog, exported, target, reportNode);
        }
    }

    /**
     * What one variant's export produced.
     *
     * @param variantId the variant
     * @param result    what was written, or {@code null} when the variant's changes produced no difference
     * @param rejected  the changes of that group that did not reach the database, as text
     */
    public record VariantExport(String variantId, SnapshotResult result, List<String> rejected) {

        /**
         * @param variantId see {@link #variantId()}
         * @param result    see {@link #result()}
         * @param rejected  see {@link #rejected()}
         */
        public VariantExport {
            rejected = List.copyOf(rejected);
        }
    }

    /**
     * Write the changes of every variant they were recorded on, each as the successor of its own snapshot.
     *
     * <p>Two phases, and that is the point of it. Every group is <em>translated</em> first, inside its own scope,
     * so that an unsupported change under {@link PartialSshExport.UnsupportedChangeBehavior#FAIL} fails with nothing written at all; only then is group after group stored. A
     * database conflict in the second phase names the variants that were already written, because at that point
     * some of them are.</p>
     *
     * <p>A change recorded without a variant belongs to every variant of the network. It is handed to every group,
     * where the translator refuses it as an unsupported change &mdash; under {@code FAIL} that is an exception,
     * under {@code IGNORE} it appears once per group in {@link VariantExport#rejected()}.</p>
     *
     * @param network    the network the changes were recorded on
     * @param events     the recorded changes
     * @param db         the open connection
     * @param newVersion the version label every new snapshot gets, or {@code null} for the next one of each
     *                   timestep's chain
     * @param options    the granularity, the header values and the unsupported change behaviour
     * @param reportNode where the stored differences are reported
     * @return one entry per variant the changes were recorded on, in first-occurrence order
     * @throws RdfDbException if a change was recorded on a variant that is not bound to a snapshot and the
     *                        behaviour is to fail
     */
    public static Map<String, VariantExport> exportPerVariant(Network network, Collection<NetworkEvent> events,
                                                              RdfDbConnection db, String newVersion,
                                                              CgmesDiffExport.ExportOptions options,
                                                              ReportNode reportNode) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(db);
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        if (!(provenance instanceof RdfDbProvenanceImpl impl)) {
            throw new RdfDbException("network " + network.getId() + " was not loaded from an RDF database, so"
                    + " there is no scenario to write the changes of its variants into");
        }
        // Writing one history per variant is an opt-in too
        impl.enableVariantMode();
        boolean fail = (options == null ? new CgmesDiffExport.ExportOptions() : options)
                .getUnsupportedChangeBehavior() == PartialSshExport.UnsupportedChangeBehavior.FAIL;

        Map<String, List<NetworkEvent>> byVariant = groupByVariant(network, events);
        // One label lookup per distinct timestep, and none at all when the caller named the version
        Map<String, String> versionByTimestep = new LinkedHashMap<>();
        // Phase one: everything that can refuse, with nothing written
        Map<String, Translated> translated = new LinkedHashMap<>();
        for (Map.Entry<String, List<NetworkEvent>> group : byVariant.entrySet()) {
            String variantId = group.getKey();
            if (impl.variantBinding(variantId).isEmpty()) {
                if (fail) {
                    throw new RdfDbException("changes were recorded on variant '" + variantId + "' of network "
                            + network.getId() + ", which is not bound to a snapshot: nothing was written");
                }
                translated.put(variantId, new Translated(null, null,
                        List.of("variant '" + variantId + "' is not bound to a snapshot")));
                continue;
            }
            VariantBinding binding = impl.variantBinding(variantId).orElseThrow();
            SnapshotRef target = targetOf(db, binding, newVersion, options, variantId, versionByTimestep);
            CgmesDiffExport.ExportOptions variantOptions = variantOptions(network, options, variantId);
            try (VariantScope scope = VariantScope.enter(network, impl, variantId)) {
                CgmesDiffExport.Result exported = translate(network, events, target.timestep(), variantOptions);
                translated.put(variantId, new Translated(target, exported,
                        rejectedOf(group.getValue(), exported.exportedEvents())));
            }
        }

        checkDistinctTargets(translated);

        // Phase two: write group by group
        Map<String, VariantExport> results = new LinkedHashMap<>();
        for (Map.Entry<String, Translated> entry : translated.entrySet()) {
            String variantId = entry.getKey();
            Translated one = entry.getValue();
            if (one.exported() == null || one.exported().differences().models().values().stream()
                    .allMatch(model -> model.isEmpty())) {
                results.put(variantId, new VariantExport(variantId, null, one.rejected()));
                continue;
            }
            SnapshotCatalog catalog = db.snapshots(one.target().scenario());
            try (VariantScope scope = VariantScope.enter(network, impl, variantId)) {
                SnapshotResult stored = store(network, db, catalog, one.exported(), one.target(), reportNode);
                results.put(variantId, new VariantExport(variantId, stored, one.rejected()));
            } catch (RdfDbConflictException e) {
                throw new RdfDbConflictException("writing the changes of variant '" + variantId + "' failed after"
                        + " the variants " + results.keySet() + " had already been written: " + e.getMessage(), e);
            }
        }
        return results;
    }

    /** One group after phase one: where it goes, what it says, and what did not make it. */
    private record Translated(SnapshotRef target, CgmesDiffExport.Result exported, List<String> rejected) {
    }

    /**
     * Two variants that stand for the <em>same</em> snapshot would be written as the same new version.
     *
     * <p>Which is the normal result of a user clone: a clone inherits its source's binding exactly. The second
     * write would be refused by the linear-chain guard of the sink, after the first one had already been
     * written, so it is refused here instead, while nothing has been written at all.</p>
     */
    private static void checkDistinctTargets(Map<String, Translated> translated) {
        Map<SnapshotRef, String> byTarget = new LinkedHashMap<>();
        for (Map.Entry<String, Translated> entry : translated.entrySet()) {
            Translated one = entry.getValue();
            if (one.target() == null || one.exported() == null) {
                continue;
            }
            String first = byTarget.putIfAbsent(one.target(), entry.getKey());
            if (first != null) {
                throw new RdfDbException("the variants '" + first + "' and '" + entry.getKey() + "' both stand"
                        + " for the snapshot " + one.target() + ", so their changes would be written as the same"
                        + " new version; nothing was written. Move one of them to a snapshot of its own, or"
                        + " export them one at a time with exportVariant");
            }
        }
    }

    /**
     * The changes of each variant, in the order the variants were first touched.
     *
     * <p>A change without a variant identifier belongs to every variant, so it is handed to every group; a network
     * that has no recorded change of any variant at all still exports the working variant's group, which is what
     * makes a single-variant network behave as it always did.</p>
     */
    private static Map<String, List<NetworkEvent>> groupByVariant(Network network,
                                                                  Collection<NetworkEvent> events) {
        Map<String, List<NetworkEvent>> byVariant = new LinkedHashMap<>();
        List<NetworkEvent> shared = new java.util.ArrayList<>();
        for (NetworkEvent event : events) {
            String variantId = CgmesDiffExport.variantOf(event);
            if (variantId == null) {
                shared.add(event);
            } else {
                byVariant.computeIfAbsent(variantId, id -> new java.util.ArrayList<>()).add(event);
            }
        }
        if (byVariant.isEmpty() && !shared.isEmpty()) {
            byVariant.put(RdfDbNetworkLoader.workingVariantOf(network), new java.util.ArrayList<>());
        }
        byVariant.values().forEach(group -> group.addAll(shared));
        return byVariant;
    }

    private static List<String> rejectedOf(List<NetworkEvent> group, List<NetworkEvent> exported) {
        java.util.Set<NetworkEvent> written = new java.util.LinkedHashSet<>(exported);
        return group.stream().filter(event -> !written.contains(event)).map(Object::toString).toList();
    }

    /** The snapshot a variant's changes become: the next version of that variant's own timestep. */
    private static SnapshotRef targetOf(RdfDbConnection db, VariantBinding binding, String newVersion,
                                        CgmesDiffExport.ExportOptions options, String variantId,
                                        Map<String, String> versionByTimestep) {
        String timestep = binding.timestep();
        if (timestep == null) {
            throw new RdfDbException("variant '" + variantId + "' is not at a snapshot of a versioned scenario,"
                    + " so there is no timestep to write its changes into");
        }
        if (options != null && options.getScenarioTime() != null
                && !options.getScenarioTime().toInstant().equals(ZonedDateTime.parse(timestep).toInstant())) {
            throw new RdfDbException("variant '" + variantId + "' stands for the timestep " + timestep
                    + ", but the export was given the scenario time " + options.getScenarioTime()
                    + ": a variant's changes are written into its own timestep");
        }
        String version = newVersion != null ? newVersion
                : versionByTimestep.computeIfAbsent(timestep,
                    ts -> db.snapshots(binding.scenario()).nextVersionLabel(ts));
        return new SnapshotRef(binding.scenario(), version, timestep);
    }

    /**
     * The options one variant's export runs with: its own variant selected, and shared changes refused when the
     * network holds more than one variant.
     */
    private static CgmesDiffExport.ExportOptions variantOptions(Network network,
                                                                CgmesDiffExport.ExportOptions options,
                                                                String variantId) {
        return copyOf(options)
                .setVariant(variantId)
                .setRejectSharedChanges(network.getVariantManager().getVariantIds().size() > 1);
    }

    private static RdfDbProvenanceImpl boundProvenance(Network network, String variantId) {
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        if (!(provenance instanceof RdfDbProvenanceImpl impl)) {
            throw new RdfDbException("network " + network.getId() + " was not loaded from an RDF database, so the"
                    + " variant '" + variantId + "' stands for no stored snapshot");
        }
        return impl;
    }

    private static VariantBinding binding(RdfDbProvenanceImpl provenance, Network network, String variantId) {
        return provenance.variantBinding(variantId).orElseThrow(() -> new RdfDbException("variant '" + variantId
                + "' of network " + network.getId() + " is not bound to a snapshot, so there is no history to"
                + " write its changes into"));
    }

    /** A copy of the caller's options, so that what this export needs to set does not leak back out. */
    private static CgmesDiffExport.ExportOptions copyOf(CgmesDiffExport.ExportOptions options) {
        if (options == null) {
            return new CgmesDiffExport.ExportOptions();
        }
        return options.copy();
    }

    private static void checkSameScenario(Network network, String scenario) {
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        if (provenance != null && !provenance.scenario().equals(scenario)) {
            throw new RdfDbException("network was loaded from scenario '" + provenance.scenario() + "' but the"
                    + " export targets scenario '" + scenario + "': diffs never cross scenarios - load or update"
                    + " the network from '" + scenario + "' first");
        }
    }

    /**
     * Make the network say it is at the differences that were just stored.
     *
     * <p>Both halves of the identity are moved: the {@code CgmesMetadataModels} extension, which is what a
     * difference export reads to decide what it supersedes, and the provenance, which is what the update planner
     * reads. They are kept in step deliberately &mdash; one of them alone would make the next operation disagree
     * with the other.</p>
     */
    private static void advanceSender(Network network, RdfDbConnection db, String scenario,
                                      List<StoredModel> stored) {
        if (stored.isEmpty()) {
            return;
        }
        Map<CgmesSubset, StoredModel> bySubset = new EnumMap<>(CgmesSubset.class);
        stored.forEach(model -> bySubset.put(model.subset(), model));
        NetworkIdentity.advance(network, bySubset);

        Map<CgmesSubset, String> ids = NetworkIdentity.modelIds(network);
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        if (provenance instanceof RdfDbProvenanceImpl impl && provenance.scenario().equals(scenario)) {
            impl.setModelIds(ids);
        } else {
            // A network read from files now belongs to the scenario it wrote its first difference into
            network.addExtension(RdfDbProvenance.class,
                    new RdfDbProvenanceImpl(db.database(), scenario, List.of(), Instant.now(), ids));
        }
    }
}
