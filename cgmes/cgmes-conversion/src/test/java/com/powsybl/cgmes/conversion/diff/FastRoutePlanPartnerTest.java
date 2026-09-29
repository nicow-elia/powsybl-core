/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.diff.DiffSubjectResolver.ResolvedSubject;
import com.powsybl.cgmes.conversion.diff.FastRoutePlan.TypedObject;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The completion of the other converter of an HVDC line (review 21 round 2 R2-B1, round 3 r3-m4).
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class FastRoutePlanPartnerTest {

    private static Map<String, CgmesStatement> partnerBlock() {
        Map<String, CgmesStatement> block = new LinkedHashMap<>();
        block.put("ACDCConverter.targetPpcc", CgmesStatement.literal("C2", "VsConverter", "ACDCConverter.targetPpcc", "497.7"));
        block.put("ACDCConverter.q", CgmesStatement.literal("C2", "VsConverter", "ACDCConverter.q", "-10"));
        return block;
    }

    /** A partner the receiver cannot resolve is not completed: the caller blocks, so that the slow route takes it. */
    @Test
    void anUnresolvedPartnerIsNotCompleted() {
        Map<String, TypedObject> objects = new LinkedHashMap<>();
        Set<String> touched = new HashSet<>();
        assertFalse(FastRoutePlan.completePartner(objects, "C2", partnerBlock(), Optional.empty(), touched));
        assertTrue(objects.isEmpty());
        assertTrue(touched.isEmpty());
    }

    /** A resolved partner is added with its block; one the difference states keeps its own statements. */
    @Test
    void aResolvedOrStatedPartnerIsCompleted() {
        Map<String, TypedObject> objects = new LinkedHashMap<>();
        Set<String> touched = new HashSet<>();
        ResolvedSubject partner = new ResolvedSubject(FastRouteCapabilities.Family.VS_CONVERTER, "VsConverter", "#_C2",
                null, "", DiffSubjectResolver.ProbeKind.NONE, Set.of("C2", "LINE"));
        assertTrue(FastRoutePlan.completePartner(objects, "C2", partnerBlock(), Optional.of(partner), touched));
        assertEquals(2, objects.get("C2").statements().size());
        assertEquals(Set.of("C2", "LINE"), touched);

        Map<String, TypedObject> stated = new LinkedHashMap<>();
        stated.put("C2", new TypedObject("#_C2", "VsConverter",
                List.of(CgmesStatement.literal("C2", "VsConverter", "ACDCConverter.q", "-12.5"))));
        assertTrue(FastRoutePlan.completePartner(stated, "C2", partnerBlock(), Optional.empty(), new HashSet<>()));
        assertEquals(List.of("-12.5", "497.7"), stated.get("C2").statements().stream().map(CgmesStatement::value).toList());
    }
}
