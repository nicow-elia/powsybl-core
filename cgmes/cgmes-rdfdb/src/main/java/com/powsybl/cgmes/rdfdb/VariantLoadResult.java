/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.iidm.network.Network;

import java.time.Duration;
import java.util.List;

/**
 * A whole day in one network, and where the time went.
 *
 * <p>The outcomes are the interesting half. A bulk load does not fail because one timestep of a day cannot be
 * reached inside a variant &mdash; the other ninety-five are perfectly usable &mdash; so each request answers for
 * itself and the caller decides what to do with the refusals.</p>
 *
 * @param network     the network, whose primary variant is the first requested snapshot
 * @param outcomes    what became of each request, in the order they were made
 * @param primaryLoad where the time of the initial load went
 * @param plan        how long the single chain query took
 * @param fetch       how long fetching every difference of every accepted path took
 * @param cloning     how long creating the variants took
 * @param applyTotal  how long applying the differences took, over all variants
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record VariantLoadResult(Network network, List<VariantOutcome> outcomes, LoadStatistics primaryLoad,
                                Duration plan, Duration fetch, Duration cloning, Duration applyTotal) {

    /**
     * @param network     see {@link #network()}
     * @param outcomes    see {@link #outcomes()}
     * @param primaryLoad see {@link #primaryLoad()}
     * @param plan        see {@link #plan()}
     * @param fetch       see {@link #fetch()}
     * @param cloning     see {@link #cloning()}
     * @param applyTotal  see {@link #applyTotal()}
     */
    public VariantLoadResult {
        outcomes = List.copyOf(outcomes);
    }

    /**
     * @return the variants that stand for the snapshot they were asked for
     */
    public List<VariantOutcome> bound() {
        return outcomes.stream().filter(VariantOutcome::isBound).toList();
    }

    /**
     * @return the requests that could not be reached inside a variant, with their reasons
     */
    public List<VariantOutcome> refused() {
        return outcomes.stream().filter(outcome -> !outcome.isBound()).toList();
    }

    /**
     * @return a one-line summary for a log
     */
    public String summary() {
        return bound().size() + " variant(s) bound, " + refused().size() + " refused; plan " + millis(plan)
                + " fetch " + millis(fetch) + " clone " + millis(cloning) + " apply " + millis(applyTotal);
    }

    private static String millis(Duration duration) {
        return duration.toNanos() / 1_000_000 + " ms";
    }
}
