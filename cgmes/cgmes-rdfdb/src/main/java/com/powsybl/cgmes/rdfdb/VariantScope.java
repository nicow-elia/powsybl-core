/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManager;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Makes a network be one of its variants for the duration of an operation, and puts it back.
 *
 * <h2>What it swaps</h2>
 * <p>Three things describe "where the network stands", and IIDM stores none of them per variant: the
 * {@code CgmesMetadataModels} extension, the case date with the forecast distance, and the snapshot identity of the
 * {@link RdfDbProvenance}. While a scope is open they describe the <em>bound variant</em> instead of the primary
 * one, and the working variant of the calling thread is that variant too. Every reader of the identity is therefore
 * correct for the variant without knowing that variants exist &mdash; the update planner, the difference exporter,
 * the {@code Supersedes} of a written difference, the snapshot catalogue &mdash; and not one of them was rewritten
 * for this feature.</p>
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li>Used in a try-with-resources and nowhere else: a scope that stayed open would leave the network claiming to
 *       be a variant it is not.</li>
 *   <li>Never clone or remove a variant inside a scope. The binding listener copies the network-level identity for
 *       a clone of the primary, and inside a scope that identity is the bound variant's.</li>
 *   <li>Entering takes the provenance lock, so the variant operations of one network are serialised.</li>
 *   <li>A thread that never selected a working variant keeps the one this scope selected: there is nothing to put
 *       back, and the alternative would be to leave the thread without a variant at all.</li>
 * </ul>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class VariantScope implements AutoCloseable {

    private final Network network;
    private final RdfDbProvenanceImpl provenance;
    private final String variantId;
    private final boolean swapped;
    private final String previousWorkingVariant;

    private VariantScope(Network network, RdfDbProvenanceImpl provenance, String variantId, boolean swapped,
                         String previousWorkingVariant) {
        this.network = network;
        this.provenance = provenance;
        this.variantId = variantId;
        this.swapped = swapped;
        this.previousWorkingVariant = previousWorkingVariant;
    }

    /**
     * Open a scope on one variant of a network.
     *
     * @param network    the network
     * @param provenance the provenance holding the bindings
     * @param variantId  the variant to become
     * @return the scope
     * @throws RdfDbException       if the variant is not the primary one and is not bound to a stored snapshot
     * @throws RdfDbException       if another variant of the same network is already swapped in
     */
    static VariantScope enter(Network network, RdfDbProvenanceImpl provenance, String variantId) {
        provenance.lock().lock();
        boolean entered = false;
        RdfDbProvenanceImpl.BoundState saved = null;
        try {
            String active = provenance.activeVariant();
            if (active != null && !active.equals(variantId)) {
                throw new RdfDbException("network " + network.getId() + " is already acting as variant '"
                        + active + "', so it cannot also act as '" + variantId + "': the identity of a network"
                        + " describes one variant at a time. Close the outer scope first");
            }
            boolean primary = RdfDbProvenance.PRIMARY_VARIANT.equals(variantId);
            RdfDbProvenanceImpl.BoundState state = provenance.boundStates().get(variantId);
            if (!primary && state == null) {
                throw new RdfDbException("variant '" + variantId + "' of network " + network.getId()
                        + " is not bound to a snapshot: it was created outside this package, or the variant it was"
                        + " cloned from was not bound either. Bind it with RdfDbNetworkLoader.update(network, db,"
                        + " scenario, version, timestep, variant, ...) or RdfDbNetworkLoader.loadVariants");
            }
            VariantManager variantManager = network.getVariantManager();
            String previous = currentVariant(variantManager);
            // A scope on the variant that is already swapped in changes nothing and restores nothing: the outer
            // one owns the identity. It still holds the lock, which is what makes the nesting safe
            boolean swap = !primary && active == null;
            if (swap) {
                saved = capturePrimary(network, provenance);
                provenance.setSavedPrimary(saved);
                NetworkIdentity.install(network, state.models);
                applyCaseDate(network, state.caseDate, state.forecastDistance);
                provenance.setActiveVariant(variantId);
            }
            variantManager.setWorkingVariant(variantId);
            entered = true;
            return new VariantScope(network, provenance, variantId, swap, previous);
        } finally {
            if (!entered) {
                // Nothing may be left half swapped: the network has to keep saying what it really is
                if (saved != null) {
                    rollback(network, provenance, saved);
                }
                provenance.lock().unlock();
            }
        }
    }

    /** Undo a half-finished {@link #enter}. */
    private static void rollback(Network network, RdfDbProvenanceImpl provenance,
                                 RdfDbProvenanceImpl.BoundState saved) {
        provenance.setActiveVariant(null);
        provenance.setSavedPrimary(null);
        NetworkIdentity.install(network, saved.models);
        applyCaseDate(network, saved.caseDate, saved.forecastDistance);
    }

    /** What the network says about itself right now, which is what the primary variant is. */
    private static RdfDbProvenanceImpl.BoundState capturePrimary(Network network,
                                                                 RdfDbProvenanceImpl provenance) {
        RdfDbProvenanceImpl.BoundState saved = new RdfDbProvenanceImpl.BoundState();
        saved.models = NetworkIdentity.capture(network);
        saved.caseDate = network.getCaseDate();
        saved.forecastDistance = network.getForecastDistance();
        saved.modelIds.putAll(provenance.modelIds());
        saved.snapshotIri = provenance.snapshot().orElse(null);
        saved.ref = saved.snapshotIri == null ? null : RdfDbNames.refOf(saved.snapshotIri);
        return saved;
    }

    private static void applyCaseDate(Network network, ZonedDateTime caseDate, int forecastDistance) {
        if (caseDate != null) {
            network.setCaseDate(caseDate);
            network.setForecastDistance(forecastDistance);
        }
    }

    /** Put this variant's identity away and the primary's back, whatever happens in between. */
    private void swapBack() {
        RdfDbProvenanceImpl.BoundState saved = provenance.savedPrimary();
        try {
            RdfDbProvenanceImpl.BoundState state = provenance.boundStates().get(variantId);
            if (state != null) {
                // Whatever the operation made the network say about itself is now this variant's identity
                state.models = NetworkIdentity.capture(network);
                state.caseDate = network.getCaseDate();
                state.forecastDistance = network.getForecastDistance();
            }
        } finally {
            provenance.setActiveVariant(null);
            provenance.setSavedPrimary(null);
            if (saved != null) {
                List<NetworkIdentity.Entry> models = saved.models;
                NetworkIdentity.install(network, models);
                applyCaseDate(network, saved.caseDate, saved.forecastDistance);
            }
        }
    }

    /** The working variant of this thread, or {@code null} when it never selected one. */
    private static String currentVariant(VariantManager variantManager) {
        try {
            return variantManager.getWorkingVariantId();
        } catch (PowsyblException e) {
            return null;
        }
    }

    /**
     * @return the variant this scope is on
     */
    String variantId() {
        return variantId;
    }

    @Override
    public void close() {
        try {
            try {
                if (swapped) {
                    swapBack();
                }
            } finally {
                // Whatever went wrong above, the caller's thread gets its variant back and the lock is released
                if (previousWorkingVariant != null) {
                    network.getVariantManager().setWorkingVariant(previousWorkingVariant);
                }
            }
        } finally {
            provenance.lock().unlock();
        }
    }
}
