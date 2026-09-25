/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.commons.PowsyblException;

/**
 * Something went wrong between powsybl and an RDF database.
 *
 * <p>Its own exception type, because the failures of a database are a kind a caller may well want to react to:
 * the server is not reachable, the scenario holds nothing, the credentials are wrong. Failures of the CGMES data
 * itself keep raising {@code CgmesModelException}.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public class RdfDbException extends PowsyblException {

    /**
     * @param message what went wrong
     */
    public RdfDbException(String message) {
        super(message);
    }

    /**
     * @param message what went wrong
     * @param cause   the underlying failure
     */
    public RdfDbException(String message, Throwable cause) {
        super(message, cause);
    }
}
