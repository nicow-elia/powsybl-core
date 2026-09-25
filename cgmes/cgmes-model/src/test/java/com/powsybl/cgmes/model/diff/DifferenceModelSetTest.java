/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.model.diff;

import com.powsybl.cgmes.model.CgmesSubset;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.powsybl.cgmes.model.diff.DifferenceModelTest.header;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class DifferenceModelSetTest {

    private static DifferenceModel model(String id, CgmesSubset subset) {
        return new DifferenceModel(header(id, subset), List.of(), List.of(), List.of());
    }

    @Test
    void rejectsTwoModelsOfOneSubset() {
        List<DifferenceModel> models = List.of(model("a", CgmesSubset.STEADY_STATE_HYPOTHESIS),
                model("b", CgmesSubset.STEADY_STATE_HYPOTHESIS));
        assertThrows(IllegalArgumentException.class, () -> new DifferenceModelSet(models));
    }

    @Test
    void iterationFollowsSubsetDeclarationOrder() {
        DifferenceModelSet set = new DifferenceModelSet(List.of(
                model("ssh", CgmesSubset.STEADY_STATE_HYPOTHESIS),
                model("eq", CgmesSubset.EQUIPMENT)));
        List<String> ids = new ArrayList<>();
        set.models().values().forEach(m -> ids.add(m.header().id()));
        assertEquals(List.of("eq", "ssh"), ids);
        assertEquals(List.of(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS), List.copyOf(set.subsets()));
    }

    @Test
    void getAndIsEmpty() {
        DifferenceModelSet empty = new DifferenceModelSet(List.of());
        assertTrue(empty.isEmpty());
        assertEquals(Optional.empty(), empty.get(CgmesSubset.STEADY_STATE_HYPOTHESIS));

        DifferenceModelSet set = new DifferenceModelSet(List.of(model("ssh", CgmesSubset.STEADY_STATE_HYPOTHESIS)));
        assertFalse(set.isEmpty());
        assertEquals("ssh", set.get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow().header().id());
        assertEquals(Optional.empty(), set.get(CgmesSubset.EQUIPMENT));
    }

    @Test
    void modelsAreNotModifiable() {
        DifferenceModelSet set = new DifferenceModelSet(List.of(model("ssh", CgmesSubset.STEADY_STATE_HYPOTHESIS)));
        assertThrows(UnsupportedOperationException.class, () -> set.models().clear());
    }
}
