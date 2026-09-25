/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.iidm.network.Network;

import java.util.List;
import java.util.Map;

/**
 * What an update did, and to which network.
 *
 * <p>The route matters to a caller because one of them &mdash; {@link Route#FULL_RELOAD} &mdash; answers with a
 * <em>different network instance</em>. The network that was handed in is then untouched and the one in this result
 * is the one to keep, which is why {@link #isReplacement()} exists rather than being something a caller has to
 * infer. Everything else updates in place and {@link #network()} is the instance that was handed in.</p>
 *
 * @param route            which way the update went
 * @param network          the up-to-date network: the one handed in, or the replacement for a full reload
 * @param appliedModelIds  the stored models that were applied, per profile, oldest first. Empty for a reload
 * @param reasons          why the fast route was not taken, empty when it was
 * @param statistics       where the time went
 * @param variantId        the variant the update acted on, or {@code null} for a network without bound variants
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record UpdateResult(UpdateResult.Route route, Network network,
                           Map<CgmesSubset, List<String>> appliedModelIds, List<String> reasons,
                           UpdateStatistics statistics, String variantId) {

    /** Which way an update went. */
    public enum Route {
        /** The network was already at the target: nothing was read and nothing was changed. */
        NOOP,
        /** The differences between the current state and the target were composed and applied in place. */
        DIFF_APPLIED,
        /** The target was materialised from the database; {@link UpdateResult#network()} is a new instance. */
        FULL_RELOAD,
        /** A reload would have been needed and was not allowed; the network is untouched. */
        FULL_REQUIRED,
        /**
         * The target could not be reached inside one variant, so nothing was done at all: every variant of the
         * network still holds exactly what it held. {@link UpdateResult#reasons()} says what stood in the way.
         */
        VARIANT_REFUSED
    }

    /**
     * @param route           see {@link #route()}
     * @param network         see {@link #network()}
     * @param appliedModelIds see {@link #appliedModelIds()}
     * @param reasons         see {@link #reasons()}
     * @param statistics      see {@link #statistics()}
     * @param variantId       see {@link #variantId()}
     */
    public UpdateResult {
        appliedModelIds = Map.copyOf(appliedModelIds);
        reasons = List.copyOf(reasons);
    }

    /**
     * The result of an update that did not act on a named variant.
     *
     * @param route           see {@link #route()}
     * @param network         see {@link #network()}
     * @param appliedModelIds see {@link #appliedModelIds()}
     * @param reasons         see {@link #reasons()}
     * @param statistics      see {@link #statistics()}
     */
    public UpdateResult(UpdateResult.Route route, Network network,
                        Map<CgmesSubset, List<String>> appliedModelIds, List<String> reasons,
                        UpdateStatistics statistics) {
        this(route, network, appliedModelIds, reasons, statistics, null);
    }

    /**
     * @return whether the update was refused because it could not stay inside one variant
     */
    public boolean isVariantRefused() {
        return route == Route.VARIANT_REFUSED;
    }

    /**
     * @return whether {@link #network()} is a new instance the caller has to swap its references to
     */
    public boolean isReplacement() {
        return route == Route.FULL_RELOAD;
    }

    /**
     * @return how many differences were applied
     */
    public int diffCount() {
        return appliedModelIds.values().stream().mapToInt(List::size).sum();
    }
}
