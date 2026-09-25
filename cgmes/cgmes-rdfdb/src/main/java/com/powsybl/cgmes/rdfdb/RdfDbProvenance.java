/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.extensions.Extension;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManagerConstants;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Where a network came from, when it was loaded from an RDF database.
 *
 * <p>A network that was read from a scenario of a database can be written back to it, updated from it, or compared
 * with it, and all of that needs to know which database and which scenario. Carrying it on the network means a
 * caller does not have to.</p>
 *
 * <p>Deliberately <strong>not serialisable</strong>: it describes a connection to a live system, not a property of
 * the grid, and a XIIDM file that claimed a network still belongs to some database would be misleading. The IIDM
 * exporter writes only extensions that have a serialiser, so this one is silently skipped.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public interface RdfDbProvenance extends Extension<Network> {

    /** The name this extension is registered under. */
    String NAME = "rdfDbProvenance";

    @Override
    default String getName() {
        return NAME;
    }

    /**
     * @return the database the network was loaded from
     */
    RdfDatabase database();

    /**
     * @return the scenario the network was loaded from
     */
    String scenario();

    /**
     * @return the graphs the network was built from
     */
    List<GraphInfo> graphs();

    /**
     * The stored model the network is at, per CGMES profile.
     *
     * <p>Kept in sync by every flow of the versioning layer &mdash; a load records what it materialised, an update
     * records what it applied, an export records the difference it wrote &mdash; so that the next operation knows
     * where in the chain the network stands without asking the database.</p>
     *
     * <p>Empty for a network that was loaded before any difference existed; the {@code CgmesMetadataModels}
     * extension is then the identity, and both say the same thing.</p>
     *
     * @return the stored model identifiers, profiles without one absent
     */
    Map<CgmesSubset, String> modelIds();

    /**
     * The snapshot the network is at.
     *
     * <p>Set by every flow that knows it: a versioned load records the snapshot it materialised, an update the one
     * it reached, an export the one it wrote. It is what lets the planner answer "can I get from here to there by
     * differences?" without matching model identifiers first &mdash; and what lets it answer "no, and without
     * asking the database" when the snapshot belongs to another scenario.</p>
     *
     * @return the IRI of the snapshot, or empty for an unversioned scenario
     */
    default Optional<String> snapshot() {
        return Optional.empty();
    }

    /**
     * @return when the network was loaded
     */
    Instant loadedAt();

    // ------------------------------------------------------------------ variants bound to snapshots

    /**
     * The variant the network-level identity always describes.
     *
     * <p>{@code CgmesMetadataModels}, {@link #modelIds()}, {@link #snapshot()} and the case date are properties of
     * the network, not of a variant, and they describe the <em>primary</em> variant. A bound variant's identity is
     * swapped in for the duration of an operation on it and swapped out again, so that every reader of the identity
     * &mdash; the planner, the exporter, the difference importer &mdash; is correct for that variant without
     * knowing that variants exist.</p>
     */
    String PRIMARY_VARIANT = VariantManagerConstants.INITIAL_VARIANT_ID;

    /**
     * Whether a caller has opted this network into <em>variant mode</em>.
     *
     * <p>Three things switch it on, and nothing else does: {@code RdfDbNetworkLoader.loadVariants}, an update
     * that names a target variant, and {@code RdfDbExport.exportVariant} / {@code exportPerVariant}. From then on
     * every in-place operation of this package is a variant operation: it acts on one variant, it refuses
     * anything that would write state IIDM shares between variants, and it never rebuilds the network it was
     * given. It is <strong>sticky</strong> &mdash; a first opt-in that is refused switches it on all the same,
     * and removing every variant again does not switch it off.</p>
     *
     * <p>Cloning a variant does <em>not</em> switch it on. The bindings of such clones are tracked all the same,
     * so {@link #variantBindings()} being non-empty is not the question a caller wants answered: this is.</p>
     *
     * @return whether every in-place operation of this package is a variant operation
     */
    default boolean isVariantMode() {
        return false;
    }

    /**
     * The named variants of this network that stand for a stored snapshot.
     *
     * <p>Empty for an ordinary network, which is what makes every classic entry point behave exactly as it did:
     * without a bound variant no variant machinery is ever entered. The primary variant is not listed here; ask
     * {@link #variantBinding(String)} for it, which answers from the network-level identity.</p>
     *
     * @return the bindings by variant identifier, in the order the variants were bound
     */
    default Map<String, VariantBinding> variantBindings() {
        return Map.of();
    }

    /**
     * What one variant of the network stands for.
     *
     * @param variantId the identifier of the IIDM variant
     * @return the binding, or empty when that variant is not bound to a stored snapshot
     */
    default Optional<VariantBinding> variantBinding(String variantId) {
        return Optional.ofNullable(variantBindings().get(variantId));
    }

    /**
     * The variants a bulk load or an update refused, with the reasons, since the last operation that could refuse.
     *
     * @return the refusals, empty when the last operation refused nothing
     */
    default List<VariantOutcome> lastRefused() {
        return List.of();
    }

    /**
     * Run something as one variant of a network, and put the network back as it was.
     *
     * <p>The public form of what every variant operation of this package does internally. For a variant that is
     * <em>bound</em> to a stored snapshot it swaps the whole identity in &mdash; the {@code CgmesMetadataModels}
     * extension, the case date and the snapshot &mdash; so that a file export written inside it carries that
     * variant's {@code md:Model.Supersedes} and not the primary's. For anything else it only selects the working
     * variant, which is all there is to select.</p>
     *
     * <p>This is what {@code CgmesDiffExport.ExportOptions.setVariant} and
     * {@code PartialSshExport.ExportOptions.setVariant} are meant to be combined with:</p>
     * <pre>{@code
     * RdfDbProvenance.inVariant(network, "08:30",
     *     () -> CgmesDiffExport.toString(network, events, CgmesSubset.STEADY_STATE_HYPOTHESIS, FAIL));
     * }</pre>
     *
     * @param network   the network
     * @param variantId the variant to act as, or {@code null} to change nothing
     * @param body      what to run
     * @param <T>       what the body answers
     * @return what the body answered
     */
    static <T> T inVariant(Network network, String variantId, Supplier<T> body) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(body);
        if (variantId == null) {
            return body.get();
        }
        if (network.getExtension(RdfDbProvenance.class) instanceof RdfDbProvenanceImpl impl
                && impl.variantBindings().containsKey(variantId)) {
            try (VariantScope scope = VariantScope.enter(network, impl, variantId)) {
                return body.get();
            }
        }
        return inWorkingVariant(network, variantId, body);
    }

    /** The working-variant half on its own, for a variant that stands for no stored snapshot. */
    private static <T> T inWorkingVariant(Network network, String variantId, Supplier<T> body) {
        VariantManager variantManager = network.getVariantManager();
        String previous;
        try {
            previous = variantManager.getWorkingVariantId();
        } catch (PowsyblException e) {
            // A thread that never selected a variant has none at all; there is nothing to restore
            previous = null;
        }
        if (variantId.equals(previous)) {
            return body.get();
        }
        variantManager.setWorkingVariant(variantId);
        try {
            return body.get();
        } finally {
            if (previous != null) {
                variantManager.setWorkingVariant(previous);
            }
        }
    }
}
