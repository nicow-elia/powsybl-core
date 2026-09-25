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
 * {@code limits<side>_<LimitType>.temporaryLimit.value} of a branch, a three windings transformer leg or a boundary
 * line, see {@code OperationalLimitsGroup}.
 *
 * <p>The attribute name says neither which operational limits group nor which temporary limit changed;
 * {@link #groupId()} and {@link #acceptableDuration()} do. There is no setter for the duration or the name of a
 * temporary limit, so no event ever reports a change of either.</p>
 *
 * @param value              the temporary limit value, in A, MW or MVA depending on the limit type
 * @param groupId            the identifier of the operational limits group the limit belongs to
 * @param inSelectedGroup    whether that group is one of the selected groups of its holder
 * @param acceptableDuration the acceptable duration of the temporary limit, in seconds, which identifies it inside
 *                           its {@code LoadingLimits}
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record TemporaryLimitInfo(double value, String groupId, boolean inSelectedGroup, int acceptableDuration) {
}
