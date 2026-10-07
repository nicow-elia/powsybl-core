/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.powsybl.cgmes.rdfdb.Backends.microGridBe;
import static com.powsybl.cgmes.rdfdb.Backends.params;

/**
 * The network comparison of the tests does not depend on an order the data does not fix.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class NetworksTest {

    /** The same limit groups of a branch, created in two orders: a store hands a conversion either one. */
    @Test
    void theOrderOfTheLimitGroupsOfABranchDoesNotMatter() {
        Network first = withGroups(List.of("g-a", "g-b"));
        Network second = withGroups(List.of("g-b", "g-a"));

        Networks.assertSameNetwork(first, second, Set.of());
    }

    private static Network withGroups(List<String> ids) {
        Network network = Network.read(microGridBe(), params());
        Line line = network.getLines().iterator().next();
        ids.forEach(id -> {
            line.newOperationalLimitsGroup1(id).newCurrentLimits().setPermanentLimit(100.0).add();
            line.newOperationalLimitsGroup2(id).newCurrentLimits().setPermanentLimit(100.0).add();
        });
        return network;
    }
}
