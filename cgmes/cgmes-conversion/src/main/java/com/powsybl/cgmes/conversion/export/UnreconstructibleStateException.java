/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

/**
 * Thrown when the state a network was in before a change set cannot be derived from the recorded changes.
 *
 * <p>It is a control flow signal inside the change translation, not an error reported to the caller: the translator
 * catches it and turns it into an unsupported change, which the export then fails on or skips as its
 * {@code UnsupportedChangeBehavior} says. A previous value that was not recorded is never guessed, because a
 * difference model whose reverse statements are wrong is worse than one that refuses to describe the change.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class UnreconstructibleStateException extends RuntimeException {

    UnreconstructibleStateException(String reason) {
        super(reason);
    }
}
