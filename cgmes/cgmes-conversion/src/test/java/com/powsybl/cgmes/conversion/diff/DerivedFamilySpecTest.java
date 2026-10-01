/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.Family;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.FamilySpec;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.Handler;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.PropertyGroup;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.VariantSafety;
import com.powsybl.cgmes.model.CgmesSubset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The capability entries of the families whose rows are data are derived from those rows; they equal the entries the
 * table held, written here once as the fixture they were before the rows replaced them.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class FamiliesTest {

    @Test
    void theCapabilityOfALoadFamilyIsTheOneTheTableHeld() {
        assertEquals(ssh(Family.ENERGY_CONSUMER, "energyConsumers", "EnergyConsumer",
                        Set.of("EnergyConsumer", "ConformLoad", "NonConformLoad", "StationSupply"),
                        PropertyGroup.of("EnergyConsumer.p", "EnergyConsumer.q")),
                FastRouteCapabilities.spec(Family.ENERGY_CONSUMER));
        assertEquals(ssh(Family.ENERGY_SOURCE, "energySources", "EnergySource", Set.of("EnergySource"),
                        PropertyGroup.of("EnergySource.activePower", "EnergySource.reactivePower")),
                FastRouteCapabilities.spec(Family.ENERGY_SOURCE));
        assertEquals(ssh(Family.ASYNCHRONOUS_MACHINE, "asynchronousMachines", "AsynchronousMachine",
                        Set.of("AsynchronousMachine"),
                        PropertyGroup.of("RotatingMachine.p", "RotatingMachine.q",
                                "AsynchronousMachine.asynchronousMachineType", "RegulatingCondEq.controlEnabled")),
                FastRouteCapabilities.spec(Family.ASYNCHRONOUS_MACHINE));
    }

    @Test
    void theTableKeepsItsOrder() {
        assertEquals(List.of(Family.values()), FastRouteCapabilities.table().stream().map(FamilySpec::family).toList());
    }

    private static FamilySpec ssh(Family family, String query, String canonicalType, Set<String> rdfTypes,
                                  PropertyGroup group) {
        return new FamilySpec(family, Set.of(CgmesSubset.STEADY_STATE_HYPOTHESIS), Handler.UPDATE_QUERY, query,
                canonicalType, rdfTypes, List.of(group), VariantSafety.SAFE);
    }
}
