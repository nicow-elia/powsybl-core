/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.model.diff;

import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class DifferenceModelTest {

    private static final ZonedDateTime TIME = ZonedDateTime.parse("2024-02-21T11:00:00Z");

    static DifferenceModelHeader header(String id, CgmesSubset subset) {
        return DifferenceModelHeader.builder(id, subset, CgmesNamespace.CIM_100_NAMESPACE)
                .scenarioTime(TIME)
                .created(TIME)
                .version(1)
                .modelingAuthoritySet("https://www.powsybl.org/")
                .build();
    }

    private static CgmesStatement p(String value) {
        return CgmesStatement.literal("L", "ConformLoad", "EnergyConsumer.p", value);
    }

    private static CgmesStatement q(String value) {
        return CgmesStatement.literal("L", "ConformLoad", "EnergyConsumer.q", value);
    }

    private static DifferenceModel model(String id, List<CgmesStatement> forward, List<CgmesStatement> reverse) {
        return new DifferenceModel(header(id, CgmesSubset.STEADY_STATE_HYPOTHESIS), forward, reverse, List.of());
    }

    @Test
    void isEmptyOnlyWhenBothDirectionsAreEmpty() {
        assertTrue(model("m", List.of(), List.of()).isEmpty());
        assertFalse(model("m", List.of(p("1")), List.of()).isEmpty());
        assertFalse(model("m", List.of(), List.of(p("1"))).isEmpty());
    }

    @Test
    void minimizedDropsStatementsPresentInBothDirections() {
        DifferenceModel full = model("m", List.of(p("12.5"), q("5")), List.of(p("10"), q("5")));
        DifferenceModel minimized = full.minimized();
        assertEquals(List.of(p("12.5")), minimized.forward());
        assertEquals(List.of(p("10")), minimized.reverse());
        assertEquals(full.header(), minimized.header());
        // Nothing to drop: the very same instance comes back
        assertSame(minimized, minimized.minimized());
    }

    @Test
    void invertedSwapsDirections() {
        DifferenceModel forward = new DifferenceModel(header("m1", CgmesSubset.STEADY_STATE_HYPOTHESIS),
                List.of(p("12.5")), List.of(p("10")), List.of(q("5")));
        DifferenceModel inverted = forward.inverted(header("m2", CgmesSubset.STEADY_STATE_HYPOTHESIS));
        assertEquals(List.of(p("10")), inverted.forward());
        assertEquals(List.of(p("12.5")), inverted.reverse());
        assertEquals("m2", inverted.header().id());
        assertTrue(inverted.preconditions().isEmpty());
    }

    @Test
    void invertedTwiceIsIdentity() {
        DifferenceModel forward = model("m1", List.of(p("12.5"), q("5")), List.of(p("10"), q("5")));
        DifferenceModel back = forward.inverted(header("m2", CgmesSubset.STEADY_STATE_HYPOTHESIS))
                .inverted(forward.header());
        assertEquals(forward, back);
    }

    @Test
    void composeKeepsFirstReverseAndLastForward() {
        DifferenceModel first = model("m1", List.of(p("11")), List.of(p("10")));
        DifferenceModel second = model("m2", List.of(p("12"), q("6")), List.of(p("11"), q("5")));
        DifferenceModel composed = DifferenceModel.compose(List.of(first, second),
                header("composed", CgmesSubset.STEADY_STATE_HYPOTHESIS));
        assertEquals(List.of(p("12"), q("6")), composed.forward());
        assertEquals(List.of(p("10"), q("5")), composed.reverse());
        assertEquals("composed", composed.header().id());
    }

    @Test
    void composeDropsNetNoOps() {
        DifferenceModel first = model("m1", List.of(p("12")), List.of(p("10")));
        DifferenceModel second = model("m2", List.of(p("10"), q("6")), List.of(p("12"), q("5")));
        DifferenceModel composed = DifferenceModel.compose(List.of(first, second),
                header("composed", CgmesSubset.STEADY_STATE_HYPOTHESIS));
        assertEquals(List.of(q("6")), composed.forward());
        assertEquals(List.of(q("5")), composed.reverse());
    }

    @Test
    void composeOfAnEmptyChainIsEmpty() {
        DifferenceModel composed = DifferenceModel.compose(List.of(), header("composed", CgmesSubset.STEADY_STATE_HYPOTHESIS));
        assertTrue(composed.isEmpty());
    }

    @Test
    void composeDropsAForwardTheLastModelOnlyReverses() {
        DifferenceModel first = model("m1", List.of(p("12")), List.of(p("10")));
        // A model that only reverses the key, for instance because the property ends up unset
        DifferenceModel second = model("m2", List.of(), List.of(p("12")));
        DifferenceModel composed = DifferenceModel.compose(List.of(first, second),
                header("composed", CgmesSubset.STEADY_STATE_HYPOTHESIS));
        assertEquals(List.of(), composed.forward());
        assertEquals(List.of(p("10")), composed.reverse());
    }

    @Test
    void composeRejectsMixedSubsets() {
        DifferenceModel ssh = model("m1", List.of(p("11")), List.of(p("10")));
        DifferenceModel eq = new DifferenceModel(header("m2", CgmesSubset.EQUIPMENT), List.of(p("12")), List.of(p("11")), List.of());
        DifferenceModelHeader composedHeader = header("composed", CgmesSubset.STEADY_STATE_HYPOTHESIS);
        List<DifferenceModel> chain = List.of(ssh, eq);
        assertThrows(IllegalArgumentException.class, () -> DifferenceModel.compose(chain, composedHeader));
    }
}
