/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.mapping;

import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;

/**
 * One plain value of a family, as data: a CGMES property and the IIDM attribute it is, nothing else. A family made
 * only of such rows is described, captured and updated by the rows alone ({@link PlainFamily}).
 *
 * @param property the CGMES property, {@code Class.attribute}
 * @param variable the name the update query binds the property to
 * @param key      the attribute a recorded change of the value is reported under, {@code null} for a value the IIDM
 *                 object does not hold (a constant)
 * @param quantity unit, sign and spelling of the value
 * @param getter   the IIDM value
 * @param setter   how the import sets the IIDM value, {@code null} when it does not read the property
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record PlainRow<O>(String property, String variable, String key, Quantity quantity,
                          ToDoubleFunction<O> getter, ObjDoubleConsumer<O> setter) {
}
