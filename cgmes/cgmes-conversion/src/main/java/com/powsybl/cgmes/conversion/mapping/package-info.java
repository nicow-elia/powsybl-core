/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

/**
 * What both steady state hypothesis exports, the in-place import of a difference and the importer's update share about
 * a CGMES value: its {@link com.powsybl.cgmes.conversion.mapping.Quantity} (multiplier, sign, spelling), and the
 * families that are nothing but plain values as data rows ({@link com.powsybl.cgmes.conversion.mapping.PlainFamily}).
 *
 * <p>The hand-written families &mdash; regulating controls, machines, tap changers, HVDC, limits &mdash; live in
 * {@code com.powsybl.cgmes.conversion.export}, next to the state view and the property sink they read and write; this
 * package holds only what the export and the import read alike. The in-place import reads of the families only the
 * {@link com.powsybl.cgmes.conversion.mapping.Block}s they declare (and the setpoints of a converter and the slot of a
 * loading limit), from which it derives its capabilities, and {@code export.Families}: the subject index and the
 * description of a subject.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
package com.powsybl.cgmes.conversion.mapping;
