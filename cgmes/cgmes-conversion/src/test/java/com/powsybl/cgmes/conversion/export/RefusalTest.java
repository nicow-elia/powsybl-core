/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refusals of the change export: every one has its own rule and its own remedy, and the refusal can be told back
 * from its message, alone or inside the message of a change export.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RefusalTest {

    /** The refusals that had no remedy before they became constants: their remedy is an addition at the end. */
    private static final Set<Refusal> REMEDY_ADDED = EnumSet.of(Refusal.TAP_CHANGERS_DISAGREE,
            Refusal.NO_REGULATION_CAPABILITY, Refusal.PTC_NO_TERMINAL, Refusal.UNDESCRIBED_USER);

    @Test
    void rulesAndRemediesAreUnique() {
        assertEquals(Refusal.values().length, Arrays.stream(Refusal.values()).map(r -> r.rule).distinct().count());
        assertEquals(Refusal.values().length, Arrays.stream(Refusal.values()).map(r -> r.remedy).distinct().count());
    }

    @ParameterizedTest
    @EnumSource(Refusal.class)
    void theRefusalIsToldFromItsMessage(Refusal refusal) {
        String message = refusal.message("a cause.");
        assertEquals("a cause. Remedy: " + refusal.remedy, message);
        assertEquals(Optional.of(refusal), Refusal.of(message));
        // As a change export words an unsupported change
        assertEquals(Optional.of(refusal), Refusal.of("Change cannot be exported to a partial SSH file: " + message
                + ". Change: UpdateNetworkEvent[id=G, attribute=targetP]"));
    }

    @Test
    void aMessageWithoutAKnownRemedyIsNoRefusal() {
        assertEquals(Optional.empty(), Refusal.of("the network has no identifiable with id G"));
        assertEquals(Optional.empty(), Refusal.of("a cause. Remedy: something else"));
        assertEquals(Optional.empty(), Refusal.of(null));
    }

    /**
     * The remedies are the sentences the change export wrote before they became constants, byte for byte: a remedy
     * added where there was none, and the point of common coupling of a converter, whose remedy now names the
     * regulating terminal as every other terminal refusal does, are the only changes.
     */
    @Test
    void theRemediesAreTheSentencesOfBefore() throws IOException {
        List<String> before;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/refusals/remedies-before.txt")), StandardCharsets.UTF_8))) {
            before = reader.lines().filter(line -> !line.startsWith("#")).toList();
        }
        Set<Refusal> kept = before.stream().flatMap(remedy -> Arrays.stream(Refusal.values())
                .filter(refusal -> refusal.remedy.equals(remedy))).collect(Collectors.toSet());
        assertEquals(EnumSet.complementOf(EnumSet.copyOf(REMEDY_ADDED)), kept);
        List<String> changed = before.stream()
                .filter(remedy -> Arrays.stream(Refusal.values()).noneMatch(refusal -> refusal.remedy.equals(remedy)))
                .toList();
        assertEquals(List.of("export the equipment model with the change (a full CGMES export)"), changed);
        assertTrue(Refusal.TERMINAL_EQ.remedy.startsWith(changed.get(0) + ", "), Refusal.TERMINAL_EQ.remedy);
    }
}
