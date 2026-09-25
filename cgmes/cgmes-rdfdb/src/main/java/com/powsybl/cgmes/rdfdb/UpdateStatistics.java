/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import java.time.Duration;

/**
 * Where the time of an update went.
 *
 * <p>Split the way the design is argued about: everything before the apply is what this work package is
 * responsible for and is bounded by two round trips, while the apply itself is the cost of the CGMES update
 * workflow and belongs to the difference importer. Reporting them separately is what makes a benchmark able to
 * gate the one without gating the other.</p>
 *
 * @param plan            deciding the route: reading the chain out of the metadata graph
 * @param fetch           fetching the statements of the differences
 * @param compose         folding the chain into one difference per profile
 * @param apply           applying it to the network, or materialising the network for a full reload
 * @param diffCount       how many stored differences took part
 * @param statementCount  how many statements they carry, forward and reverse
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record UpdateStatistics(Duration plan, Duration fetch, Duration compose, Duration apply, int diffCount,
                               long statementCount) {

    /** An empty measurement, for a route that did nothing. */
    static UpdateStatistics none() {
        return new UpdateStatistics(Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, 0, 0);
    }

    /**
     * @return the sum of every phase
     */
    public Duration total() {
        return plan.plus(fetch).plus(compose).plus(apply);
    }

    /**
     * @return a one-line summary, for a log or a report
     */
    public String summary() {
        return "plan " + plan.toMillis() + " ms, fetch " + fetch.toMillis() + " ms, compose " + compose.toMillis()
                + " ms, apply " + apply.toMillis() + " ms, " + diffCount + " difference(s), " + statementCount
                + " statement(s)";
    }
}
