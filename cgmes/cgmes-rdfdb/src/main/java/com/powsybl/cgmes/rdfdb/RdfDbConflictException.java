/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

/**
 * A difference could not be stored because the database does not agree that it applies where it says it does.
 *
 * <p>Its own exception type because it is the one failure of a write that is <em>not</em> a defect: two clients
 * recording changes on the same base model is the normal way of working, and exactly one of them wins. The loser
 * gets this, with the rule it broke and what to do about it in the message, and the database is unchanged.</p>
 *
 * <p>The rules that raise it: a difference must supersede exactly one model that is stored in the same scenario
 * and describes the same profile; that model must not have a successor yet; and the identifier of the difference
 * must be new.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public class RdfDbConflictException extends RdfDbException {

    /**
     * @param message the rule that was broken
     */
    public RdfDbConflictException(String message) {
        super(message);
    }

    /**
     * @param message the rule that was broken
     * @param cause   the underlying failure
     */
    public RdfDbConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
