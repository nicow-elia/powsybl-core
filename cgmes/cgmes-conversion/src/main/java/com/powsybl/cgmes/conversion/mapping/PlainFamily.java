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
import java.util.function.ToDoubleFunction;

/**
 * A CGMES family that is nothing but plain values: the update query that reads it, the CIM classes it accepts (the
 * canonical one first), its rows, in the order they are written, and of those the rows the import reads (a row with a
 * setter). The update query reads every row as one group, so a family is described and applied as a whole.
 *
 * <p>The export writes the rows ({@code CgmesChangeTranslator}), the in-place import derives the capability of the
 * family from them ({@code FastRouteCapabilities}) and the importer's update sets the IIDM values through
 * {@link #apply}.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record PlainFamily<O>(String updateQuery, List<String> cimClasses, List<PlainRow<O>> rows, List<PlainRow<O>> read) {

    public PlainFamily {
        Objects.requireNonNull(updateQuery);
        cimClasses = List.copyOf(cimClasses);
        rows = List.copyOf(rows);
        read = List.copyOf(read);
    }

    /** A family whose rows the import reads are the rows with a setter. */
    public PlainFamily(String updateQuery, List<String> cimClasses, List<PlainRow<O>> rows) {
        this(updateQuery, cimClasses, rows, rows.stream().filter(row -> row.setter() != null).toList());
    }

    /** The CGMES properties of the family, in the order they are written. */
    public List<String> properties() {
        return rows.stream().map(PlainRow::property).toList();
    }

    /** The block of the family: every row is required, the update query reads them as one group. */
    public Block block() {
        return new Block(updateQuery, cimClasses, properties(), List.of());
    }

    /**
     * Set the IIDM values the import reads: from the values the update query bound when it bound all of them, from
     * the given fallback otherwise (the query binds a group as a whole or not at all).
     *
     * @param values    the lexical value of each variable of the query, {@code null} when it is not bound
     * @param otherwise the value of a row the query did not bind, read when the row is set
     */
    public void apply(O owner, Function<String, String> values, ToDoubleFunction<PlainRow<O>> otherwise) {
        boolean bound = read.stream().allMatch(row -> values.apply(row.variable()) != null);
        read.forEach(row -> row.setter().accept(owner, bound
                ? row.quantity().decode(row.quantity().parse(values.apply(row.variable())), 1)
                : otherwise.applyAsDouble(row)));
    }
}
