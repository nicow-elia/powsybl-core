/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.commons.PowsyblException;

import java.util.List;

/**
 * A difference model cannot be applied to a network in place.
 *
 * <p>The network is untouched when this is thrown: every check runs before the first modification. The decision it
 * carries lists what stands in the way, statement by statement, so that a caller can either fix its producer or go
 * the slow route (re-import with the difference applied to the source data, or apply the difference in an RDF
 * database and re-import from there).</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public class CgmesDiffNotApplicableException extends PowsyblException {

    private static final long serialVersionUID = 1L;

    /** How many reasons the message of this exception spells out before it only counts the rest. */
    private static final int REASONS_IN_MESSAGE = 10;

    private final transient CgmesDiffImport.Decision decision;

    public CgmesDiffNotApplicableException(CgmesDiffImport.Decision decision) {
        super(message(decision));
        this.decision = decision;
    }

    /** What stands in the way of applying the difference model in place. */
    public CgmesDiffImport.Decision getDecision() {
        return decision;
    }

    private static String message(CgmesDiffImport.Decision decision) {
        List<String> reasons = decision.reasons();
        StringBuilder message = new StringBuilder("The difference model cannot be applied to this network in place:");
        reasons.stream().limit(REASONS_IN_MESSAGE).forEach(reason -> message.append(System.lineSeparator())
                .append("  ").append(reason));
        if (reasons.size() > REASONS_IN_MESSAGE) {
            message.append(System.lineSeparator())
                    .append("  ... and ").append(reasons.size() - REASONS_IN_MESSAGE).append(" more");
        }
        return message.toString();
    }
}
