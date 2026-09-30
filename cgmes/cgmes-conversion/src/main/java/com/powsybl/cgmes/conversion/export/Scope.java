/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

/**
 * Who reads what the change mapping describes: a receiver of changes, or the reader of a full steady state
 * hypothesis. It decides which objects an export may NAME (a full export writes objects the receiver does not hold yet,
 * under a generated identifier) and which refusals it honours; no value depends on it.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
enum Scope {

    /** A partial SSH, a difference model, a database write: applied on top of a state the receiver holds. */
    CHANGES,
    /** A full steady state hypothesis: the whole state, read against the equipment model. */
    FULL_MODEL;

    /** Whether this scope refuses what the refusal describes: a full model does not honour a {@code changesOnly} one. */
    boolean honours(Refusal refusal) {
        return this == CHANGES || !refusal.changesOnly;
    }
}
