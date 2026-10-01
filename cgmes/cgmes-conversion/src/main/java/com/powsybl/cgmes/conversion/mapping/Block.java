/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.mapping;

import java.util.List;

/**
 * The properties of a CGMES object a family writes and the update reads together: the update query that reads them
 * ({@code null} for values the update applies with IIDM setters), the CIM classes it accepts (the canonical one first),
 * the properties that all have to be present for any of them to be read, and the ones the query reads in a nested
 * optional block. A family declares its blocks; the capabilities of the in-place import are derived from them
 * ({@code FastRouteCapabilities}).
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record Block(String updateQuery, List<String> cimClasses, List<String> required, List<String> optional) {

    public Block {
        cimClasses = List.copyOf(cimClasses);
        required = List.copyOf(required);
        optional = List.copyOf(optional);
    }

    /** A block whose properties are all required. */
    public Block(String updateQuery, List<String> cimClasses, String... required) {
        this(updateQuery, cimClasses, List.of(required), List.of());
    }
}
