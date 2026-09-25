/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.events;

import com.powsybl.iidm.network.OperationalLimits;

/**
 * Payload of the {@link UpdateNetworkEvent} fired for the attribute {@code limits<side>_<LimitType>} of a branch, a
 * three windings transformer leg or a boundary line, that is when a whole set of loading limits is added, replaced or
 * removed, see {@code OperationalLimitsGroup}.
 *
 * <p>{@link #value()} is {@code null} when the limits were removed or did not exist yet. The old limits object stays
 * readable after the event, so a listener can ask it for the permanent limit and the temporary limits the change
 * replaced.</p>
 *
 * <p>Changing which groups are selected fires an event for the same attribute, but with the raw
 * {@code CurrentLimits} / {@code ActivePowerLimits} / {@code ApparentPowerLimits} as payload rather than this record,
 * which is how the two are told apart.</p>
 *
 * @param value          the loading limits after the change, or {@code null} when they were removed
 * @param groupId        the identifier of the operational limits group the limits belong to
 * @param inSelectedGroup whether that group is one of the selected groups of its holder
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record OperationalLimitsInfo(OperationalLimits value, String groupId, boolean inSelectedGroup) {
}
