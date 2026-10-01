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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The statement diff on statements alone, without any RDF parsing: what is a change and what is not.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class StatementDiffTest {

    private static StatementDiff.Index index(CgmesStatement... statements) {
        Map<String, Map<String, List<CgmesStatement>>> bySubject = new LinkedHashMap<>();
        for (CgmesStatement statement : statements) {
            bySubject.computeIfAbsent(statement.subjectId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(statement.property(), k -> new ArrayList<>()).add(statement);
        }
        return StatementDiff.readOnly(bySubject);
    }

    private static DifferenceModel diff(StatementDiff.Index before, StatementDiff.Index after) {
        return StatementDiff.diff(before, after, DifferenceModelTest.header("d", CgmesSubset.STEADY_STATE_HYPOTHESIS));
    }

    private static CgmesStatement p(String subject, String value) {
        return CgmesStatement.literal(subject, null, "EnergyConsumer.p", value);
    }

    @Test
    void numbersCompareByValueAndMinusZeroIsZero() {
        assertTrue(diff(index(p("L1", "10")), index(p("L1", "1e1"))).isEmpty());
        assertTrue(diff(index(p("L1", "0")), index(p("L1", "-0.0"))).isEmpty());
        assertEquals(StatementDiff.comparable(p("L1", "-0")), StatementDiff.comparable(p("L1", "0.0")));
    }

    @Test
    void aChangedValueIsForwardAndReverse() {
        DifferenceModel d = diff(index(p("L1", "1"), p("L2", "5")), index(p("L1", "2"), p("L2", "5.0")));
        assertEquals(List.of(p("L1", "2")), d.forward());
        assertEquals(List.of(p("L1", "1")), d.reverse());
    }

    @Test
    void anAddedAndARemovedSubjectCarryTheirType() {
        CgmesStatement typeOfNew = CgmesStatement.reference("N", null, CgmesStatement.RDF_TYPE, "ConformLoad");
        CgmesStatement typeOfOld = CgmesStatement.reference("O", null, CgmesStatement.RDF_TYPE, "ConformLoad");
        DifferenceModel d = diff(index(typeOfOld, p("O", "1")), index(typeOfNew, p("N", "2")));
        // The new side first, in its order, then what only the parent has
        assertEquals(List.of(typeOfNew, p("N", "2")), d.forward());
        assertEquals(List.of(typeOfOld, p("O", "1")), d.reverse());
    }

    @Test
    void aKindIsPartOfTheValue() {
        DifferenceModel d = diff(index(CgmesStatement.literal("T", null, "Terminal", "X")),
                index(CgmesStatement.reference("T", null, "Terminal", "X")));
        assertEquals(1, d.forward().size());
        assertEquals(1, d.reverse().size());
    }

    @Test
    void aKeptIndexIsReadOnly() {
        StatementDiff.Index index = index(p("L1", "1"));
        assertEquals(1, index.size());
        assertThrows(UnsupportedOperationException.class, () -> index.bySubject().get("L1").get("EnergyConsumer.p").add(p("L1", "2")));
    }
}
