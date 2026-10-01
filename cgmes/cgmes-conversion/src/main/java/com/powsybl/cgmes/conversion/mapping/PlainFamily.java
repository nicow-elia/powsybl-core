/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.mapping;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A CGMES family that is nothing but plain values: the update query that reads it, the CIM classes it accepts (the
 * canonical one first) and its rows, in the order they are written. The update query reads every row as one group,
 * so a family is described and applied as a whole.
 *
 * <p>The export writes the rows ({@code CgmesChangeTranslator}), the in-place import derives the capability of the
 * family from them ({@code FastRouteCapabilities}) and the importer's update sets the IIDM values through
 * {@link #apply}.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record PlainFamily<O>(String updateQuery, List<String> cimClasses, List<PlainRow<O>> rows) {

    public PlainFamily {
        Objects.requireNonNull(updateQuery);
        cimClasses = List.copyOf(cimClasses);
        rows = List.copyOf(rows);
    }

    /** The CGMES properties of the family, in the order they are written. */
    public List<String> properties() {
        return rows.stream().map(PlainRow::property).toList();
    }

    /**
     * Set the IIDM values the import reads from the values the update query bound, when it bound all of them.
     *
     * @param values the lexical value of each variable of the query, {@code null} when it is not bound
     * @return whether the values were set; when one is missing nothing is set and the caller falls back on its defaults
     */
    public boolean apply(O owner, Function<String, String> values) {
        List<PlainRow<O>> read = rows.stream().filter(row -> row.setter() != null).toList();
        if (read.stream().anyMatch(row -> values.apply(row.variable()) == null)) {
            return false;
        }
        read.forEach(row -> row.setter().accept(owner,
                row.quantity().decode(row.quantity().parse(values.apply(row.variable())), 1)));
        return true;
    }
}
