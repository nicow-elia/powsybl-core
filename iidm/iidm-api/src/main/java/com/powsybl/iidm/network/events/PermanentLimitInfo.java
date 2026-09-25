/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.events;

/**
 * Payload of the {@link UpdateNetworkEvent} fired for the attribute
 * {@code limits<side>_<LimitType>.permanentLimit} of a branch, a three windings transformer leg or a boundary line,
 * see {@code OperationalLimitsGroup}.
 *
 * <p>The attribute name alone does not say which operational limits group the permanent limit belongs to, because a
 * side may carry several groups and only one of them is selected; {@link #groupId()} does. A change of the name of a
 * permanent limit fires the same event, with the same value and a different name.</p>
 *
 * @param name           the name of the permanent limit, which CGMES does not carry for a PATL
 * @param value          the permanent limit value, in A, MW or MVA depending on the limit type
 * @param groupId        the identifier of the operational limits group the limit belongs to
 * @param inSelectedGroup whether that group is one of the selected groups of its holder
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record PermanentLimitInfo(String name, double value, String groupId, boolean inSelectedGroup) {
}
