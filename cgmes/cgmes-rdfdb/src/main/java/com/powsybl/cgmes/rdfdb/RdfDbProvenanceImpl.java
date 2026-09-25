/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.extensions.AbstractExtension;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkListener;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The plain value implementation of {@link RdfDbProvenance}, and the bookkeeping of the variants bound to
 * snapshots.
 *
 * <h2>Why the identity readers never learn about variants</h2>
 * <p>Everything that asks where a network stands &mdash; the update planner, the difference exporter, the snapshot
 * catalogue &mdash; reads the network-level identity: the {@code CgmesMetadataModels} extension, {@link #modelIds()}
 * and {@link #snapshot()}. Teaching every one of them about variants would mean two code paths for the same
 * question. Instead the identity of the variant being operated on is <em>swapped in</em> for the duration of the
 * operation by {@link VariantScope}, and the setters below delegate to the binding of that variant while a scope is
 * open. A network with no bound variant never opens one, so nothing changes for it at all.</p>
 *
 * <h2>Why a listener</h2>
 * <p>A user may clone, overwrite or remove a variant at any time, with no call into this package. A binding that
 * outlived its variant would be worse than none: a later update would take the snapshot of a variant that was
 * removed and re-created in the meantime as the state of whatever is there now. IIDM fires a variant event for all
 * three, so the bindings are kept in step as they happen rather than reconciled afterwards.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbProvenanceImpl extends AbstractExtension<Network> implements RdfDbProvenance {

    private final RdfDatabase database;
    private final String scenario;
    private final List<GraphInfo> graphs;
    private final Instant loadedAt;
    private final Map<CgmesSubset, String> modelIds = new EnumMap<>(CgmesSubset.class);
    private String snapshot;

    /**
     * Serialises every variant operation of this network, because each of them swaps network-level state in and
     * out. Readers on other variants are unaffected: they never go through this package.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /** The state of one bound variant. Mutable, and only ever touched under {@link #lock}. */
    static final class BoundState {
        SnapshotRef ref;
        String snapshotIri;
        final Map<CgmesSubset, String> modelIds = new EnumMap<>(CgmesSubset.class);
        List<NetworkIdentity.Entry> models = List.of();
        ZonedDateTime caseDate;
        int forecastDistance;
        String clonedFrom;
        Instant boundAt = Instant.now();

        BoundState copy() {
            BoundState copy = new BoundState();
            copy.ref = ref;
            copy.snapshotIri = snapshotIri;
            copy.modelIds.putAll(modelIds);
            copy.models = models;
            copy.caseDate = caseDate;
            copy.forecastDistance = forecastDistance;
            copy.clonedFrom = clonedFrom;
            copy.boundAt = Instant.now();
            return copy;
        }

        VariantBinding toBinding(String variantId, String scenario) {
            return new VariantBinding(variantId, scenario, ref, snapshotIri, modelIds, caseDate, clonedFrom,
                    boundAt);
        }
    }

    /** The bound variants, in the order they were bound. Never holds the primary variant. */
    private final Map<String, BoundState> bound = new LinkedHashMap<>();
    /**
     * Whether this network is in variant mode, that is whether a caller has <em>opted in</em>.
     *
     * <p>Sticky, and set by exactly two things: {@code loadVariants}, and an update that names a target variant.
     * Cloning a variant is the ordinary IIDM idiom for a security analysis and must not change what any classic
     * entry point of this package does, so the mere presence of a binding is deliberately <strong>not</strong>
     * the opt-in. The listener below keeps tracking clones anyway, so that a later opt-in knows what is there.</p>
     */
    private boolean variantMode;

    /** The variant a scope has swapped in, or {@code null} when the network-level identity is the primary's. */
    private String active;
    /** The primary identity, parked while a scope is open. */
    private BoundState savedPrimary;
    /** What the last operation that could refuse did refuse. */
    private List<VariantOutcome> lastRefused = List.of();
    /** The listener that keeps the bindings in step with what the user does to the variants. */
    private NetworkListener variantListener;

    RdfDbProvenanceImpl(RdfDatabase database, String scenario, List<GraphInfo> graphs, Instant loadedAt) {
        this(database, scenario, graphs, loadedAt, Map.of());
    }

    RdfDbProvenanceImpl(RdfDatabase database, String scenario, List<GraphInfo> graphs, Instant loadedAt,
                        Map<CgmesSubset, String> modelIds) {
        this.database = Objects.requireNonNull(database);
        this.scenario = Objects.requireNonNull(scenario);
        this.graphs = List.copyOf(graphs);
        this.loadedAt = Objects.requireNonNull(loadedAt);
        this.modelIds.putAll(modelIds);
    }

    /** Record which stored model the network is at, after a difference was applied or written. */
    void setModelIds(Map<CgmesSubset, String> newModelIds) {
        lock.lock();
        try {
            Map<CgmesSubset, String> target = activeState() == null ? modelIds : activeState().modelIds;
            target.clear();
            target.putAll(newModelIds);
        } finally {
            lock.unlock();
        }
    }

    /** Record which snapshot the network is at. */
    void setSnapshot(String snapshotIri) {
        lock.lock();
        try {
            if (activeState() == null) {
                this.snapshot = snapshotIri;
            } else {
                // The address travels with the IRI: a caller reading the binding wants the version and the
                // timestep, not a string it would have to parse itself
                activeState().snapshotIri = snapshotIri;
                activeState().ref = snapshotIri == null ? null : RdfDbNames.refOf(snapshotIri);
                activeState().boundAt = Instant.now();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> snapshot() {
        lock.lock();
        try {
            return Optional.ofNullable(activeState() == null ? snapshot : activeState().snapshotIri);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public RdfDatabase database() {
        return database;
    }

    @Override
    public String scenario() {
        return scenario;
    }

    @Override
    public List<GraphInfo> graphs() {
        return graphs;
    }

    @Override
    public Map<CgmesSubset, String> modelIds() {
        lock.lock();
        try {
            return Map.copyOf(activeState() == null ? modelIds : activeState().modelIds);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Instant loadedAt() {
        return loadedAt;
    }

    // ------------------------------------------------------------------ variants

    @Override
    public Map<String, VariantBinding> variantBindings() {
        lock.lock();
        try {
            Map<String, VariantBinding> bindings = new LinkedHashMap<>();
            bound.forEach((variantId, state) -> bindings.put(variantId, state.toBinding(variantId, scenario)));
            return java.util.Collections.unmodifiableMap(bindings);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<VariantBinding> variantBinding(String variantId) {
        Objects.requireNonNull(variantId);
        lock.lock();
        try {
            if (PRIMARY_VARIANT.equals(variantId)) {
                BoundState primary = savedPrimary != null ? savedPrimary : primaryState();
                return Optional.of(primary.toBinding(PRIMARY_VARIANT, scenario));
            }
            BoundState state = bound.get(variantId);
            return Optional.ofNullable(state == null ? null : state.toBinding(variantId, scenario));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<VariantOutcome> lastRefused() {
        lock.lock();
        try {
            return lastRefused;
        } finally {
            lock.unlock();
        }
    }

    void setLastRefused(List<VariantOutcome> refused) {
        lock.lock();
        try {
            this.lastRefused = List.copyOf(refused);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isVariantMode() {
        lock.lock();
        try {
            return variantMode;
        } finally {
            lock.unlock();
        }
    }

    /** Opt this network into variant mode; there is no way back, and none is wanted. */
    void enableVariantMode() {
        lock.lock();
        try {
            variantMode = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Record what a variant stands for now, under the lock.
     *
     * @param variantId the variant
     * @param iri       the IRI of the snapshot it is at; its address is derived from it
     */
    void rebind(String variantId, String iri) {
        lock.lock();
        try {
            BoundState state = bound.get(variantId);
            if (state != null) {
                state.snapshotIri = iri;
                state.ref = iri == null ? null : RdfDbNames.refOf(iri);
                state.boundAt = Instant.now();
            }
        } finally {
            lock.unlock();
        }
    }

    /** The lock every variant operation of this network runs under. */
    ReentrantLock lock() {
        return lock;
    }

    Map<String, BoundState> boundStates() {
        return bound;
    }

    String activeVariant() {
        return active;
    }

    void setActiveVariant(String variantId) {
        this.active = variantId;
    }

    BoundState savedPrimary() {
        return savedPrimary;
    }

    void setSavedPrimary(BoundState state) {
        this.savedPrimary = state;
    }

    /** The state of the variant a scope has swapped in, or {@code null} when none is. */
    private BoundState activeState() {
        return active == null ? null : bound.get(active);
    }

    /** The network-level identity as a state value, which is what the primary variant is. */
    private BoundState primaryState() {
        BoundState state = new BoundState();
        state.snapshotIri = snapshot;
        state.ref = snapshot == null ? null : RdfDbNames.refOf(snapshot);
        state.modelIds.putAll(modelIds);
        if (getExtendable() != null) {
            // The whole identity, not only the identifiers: a clone of the primary has to be able to say what it
            // supersedes, and that lives in the CgmesMetadataModels entries
            state.models = NetworkIdentity.capture(getExtendable());
            state.caseDate = getExtendable().getCaseDate();
            state.forecastDistance = getExtendable().getForecastDistance();
        }
        state.boundAt = loadedAt;
        return state;
    }

    /**
     * Bind a variant to a stored state.
     *
     * @param variantId  the variant
     * @param state      what it stands for
     */
    void bind(String variantId, BoundState state) {
        lock.lock();
        try {
            bound.put(variantId, state);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Forget every non-primary binding.
     *
     * <p>Called at the end of a <em>classic</em> in-place operation on a network that is not in variant mode.
     * Such an operation writes the working variant's state and records it as the network-level identity, and it
     * is free to write values IIDM shares between variants; whatever the tracked clones held before, nothing
     * kept their bindings true. Dropping them means a later opt-in says "variant 'x' is not bound to a
     * snapshot" instead of planning from a state the variant no longer holds.</p>
     *
     * <p>A no-op in variant mode and while a scope is open: there the bindings <em>are</em> what is being
     * maintained.</p>
     */
    void dropTrackedBindings() {
        lock.lock();
        try {
            if (!variantMode && active == null) {
                bound.clear();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Forget the binding of a variant, for instance because its state is no longer defined. */
    void unbind(String variantId) {
        lock.lock();
        try {
            bound.remove(variantId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Follow the variants of the network this extension is put on.
     *
     * <p>Registering the listener here and nowhere else is what makes the bookkeeping complete: the extension is
     * attached exactly once per network, and it is detached &mdash; {@code setExtendable(null)} &mdash; when the
     * extension is removed, which is where the listener has to go as well.</p>
     */
    @Override
    public void setExtendable(Network extendable) {
        Network previous = getExtendable();
        if (previous != null && variantListener != null) {
            previous.removeListener(variantListener);
            variantListener = null;
        }
        super.setExtendable(extendable);
        if (extendable != null) {
            variantListener = new BindingListener();
            extendable.addListener(variantListener);
        }
    }

    /**
     * Keeps {@link #bound} in step with what happens to the variants of the network.
     *
     * <p>A clone <em>is</em> the state it was cloned from, so the new variant inherits the binding of its source
     * exactly. A source that is not bound leaves the target unbound, which is also what an overwrite from an
     * unbound source has to do: the binding that was there describes a state the variant no longer holds.</p>
     */
    private final class BindingListener implements NetworkListener {

        @Override
        public void onVariantCreated(String sourceVariantId, String targetVariantId) {
            inherit(sourceVariantId, targetVariantId);
        }

        @Override
        public void onVariantOverwritten(String sourceVariantId, String targetVariantId) {
            inherit(sourceVariantId, targetVariantId);
        }

        @Override
        public void onVariantRemoved(String variantId) {
            lock.lock();
            try {
                bound.remove(variantId);
            } finally {
                lock.unlock();
            }
        }

        private void inherit(String sourceVariantId, String targetVariantId) {
            lock.lock();
            try {
                BoundState source = PRIMARY_VARIANT.equals(sourceVariantId)
                        ? primaryOrSaved() : bound.get(sourceVariantId);
                if (PRIMARY_VARIANT.equals(targetVariantId)) {
                    // Overwriting the primary is how a study variant is committed. The primary's identity is the
                    // network's, not an entry of the map, so it is installed rather than copied into bound
                    becomeThePrimary(source);
                    return;
                }
                if (source == null) {
                    bound.remove(targetVariantId);
                    return;
                }
                BoundState copy = source.copy();
                copy.clonedFrom = sourceVariantId;
                bound.put(targetVariantId, copy);
            } finally {
                lock.unlock();
            }
        }

        /**
         * The network now holds the state of {@code source}, so that is what it has to say it is.
         *
         * <p>An unbound source leaves the network at <em>no</em> snapshot at all: it now holds a state this layer
         * knows nothing about, and claiming the models it held before would make the next update plan from a
         * lie. The next operation then rebuilds, or refuses and says why.</p>
         */
        private void becomeThePrimary(BoundState source) {
            // Never a key of the map: variantBindings() lists the named variants, the primary is the network
            bound.remove(PRIMARY_VARIANT);
            Network network = getExtendable();
            // The models of the variant a scope is on are only written back to its BoundState at close, so the
            // live extension is the truthful copy while the scope is open
            List<NetworkIdentity.Entry> models = source == null ? List.of()
                    : source == activeState() && network != null ? NetworkIdentity.capture(network)
                    : source.models;
            // These two are the primary's own fields. Writing them while a scope is open is safe, because every
            // reader goes through activeState() first and only sees them once the scope has closed
            snapshot = source == null ? null : source.snapshotIri;
            modelIds.clear();
            if (source != null) {
                modelIds.putAll(source.modelIds);
            }
            if (savedPrimary != null) {
                // A scope is open, so the network-level identity belongs to the variant being operated on; what
                // the primary is has to go into the parked state, which close() installs
                copyInto(source, savedPrimary);
                savedPrimary.models = models;
                return;
            }
            if (network != null) {
                NetworkIdentity.install(network, models);
                if (source != null && source.caseDate != null) {
                    network.setCaseDate(source.caseDate);
                    network.setForecastDistance(source.forecastDistance);
                }
            }
        }

        private void copyInto(BoundState source, BoundState target) {
            target.ref = source == null ? null : source.ref;
            target.snapshotIri = source == null ? null : source.snapshotIri;
            target.modelIds.clear();
            target.models = List.of();
            if (source != null) {
                target.modelIds.putAll(source.modelIds);
                target.models = source.models;
                target.caseDate = source.caseDate;
                target.forecastDistance = source.forecastDistance;
            }
        }

        /**
         * The primary's state: the one parked by an open scope when there is one, because the network-level
         * identity then describes the variant being operated on rather than the primary.
         */
        private BoundState primaryOrSaved() {
            if (savedPrimary != null) {
                return savedPrimary;
            }
            BoundState state = primaryState();
            // A primary that is at no snapshot at all binds nothing: a clone of it is unbound
            return state.snapshotIri == null && state.modelIds.isEmpty() ? null : state;
        }
    }
}
