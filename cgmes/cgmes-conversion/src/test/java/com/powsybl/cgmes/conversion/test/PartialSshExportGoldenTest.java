/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios.Scenario;
import com.powsybl.iidm.network.LoadingLimits;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.ExtensionUpdateNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.OperationalLimitsInfo;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the exact partial SSH document every {@link RecordedChangeScenarios} scenario produces.
 *
 * <p>This is a characterization net, not a specification: it exists so that the refactorings that make the mapping
 * usable by the difference model export cannot change what the partial SSH export writes, down to the byte. The
 * reference files were generated from the exporter as it stood before those refactorings and are never regenerated
 * while refactoring; a deliberate change of the output is a regeneration with {@code -Ddiffstacking.regenerate=true}
 * followed by a review of the resulting diff.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class PartialSshExportGoldenTest {

    private static final String REGENERATE = "diffstacking.regenerate";
    private static final String GOLDEN_DIR = "partial-ssh-golden";

    private static final String MODEL_ID = "urn:uuid:00000000-0000-0000-0000-000000000001";
    private static final ZonedDateTime SCENARIO_TIME = ZonedDateTime.parse("2024-02-21T11:00:00Z");
    private static final ZonedDateTime CREATED = ZonedDateTime.parse("2026-09-17T08:00:00Z");

    static List<Scenario> scenarios() {
        return RecordedChangeScenarios.all();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void partialSshOfEveryScenarioIsUnchanged(Scenario scenario) throws IOException {
        Network network = scenario.load();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, scenario.forwardChange());
        assertTrue(!events.isEmpty(), () -> "scenario " + scenario.name() + " recorded no change");

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PartialSshExport.write(network, events, outputStream, new PartialSshExport.ExportOptions()
                .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL)
                .setModelId(MODEL_ID)
                .setScenarioTime(SCENARIO_TIME)
                .setCreated(CREATED));
        String actual = outputStream.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertEquals(golden(scenario.name(), actual), actual);
    }

    /**
     * The backward change of a scenario has to take the network exactly back to where the forward change started,
     * otherwise the symmetry the difference model tests assert would be comparing two different states.
     *
     * <p>It is checked on the change log itself rather than on the network, so that it holds for every attribute the
     * two changes touch, including the ones a scenario does not name explicitly. Only the attributes the forward
     * change reports are compared: an attribute that only the undo reports was not part of the change, and an
     * extension the forward change created reports no attribute at all.</p>
     */
    @Test
    void everyBackwardChangeRestoresTheStateItsForwardChangeStartedFrom() {
        List<String> mismatches = new ArrayList<>();
        for (Scenario scenario : RecordedChangeScenarios.all()) {
            Network network = scenario.load();
            List<NetworkEvent> forward = RecordedChangeScenarios.record(network, scenario.forwardChange());
            List<NetworkEvent> backward = RecordedChangeScenarios.record(network, scenario.backwardChange());

            Map<String, Object> firstOld = new LinkedHashMap<>();
            for (NetworkEvent event : forward) {
                String key = key(event);
                // containsKey, not putIfAbsent: a recorded old value may legitimately be null, which putIfAbsent
                // would treat as no value at all
                if (key != null && !firstOld.containsKey(key)) {
                    firstOld.put(key, oldValue(event));
                }
            }
            Map<String, Object> lastNew = new LinkedHashMap<>();
            for (NetworkEvent event : forward) {
                putNewValue(lastNew, event);
            }
            for (NetworkEvent event : backward) {
                putNewValue(lastNew, event);
            }
            firstOld.forEach((key, before) -> {
                Object after = lastNew.get(key);
                if (!Objects.equals(normalize(key, before), normalize(key, after))) {
                    mismatches.add(scenario.name() + ": " + key + " started at " + before + " but ended at " + after);
                }
            });
        }
        assertEquals(List.of(), mismatches);
    }

    /**
     * A reference priority that is not recorded is a priority of zero, which is what {@code ReferencePriority.get}
     * returns for it, so withdrawing one lands on the same state the generator started from.
     *
     * <p>A whole set of loading limits is reported as the object itself, and replacing it builds a new instance, so
     * two equal sets are two different objects. What a scenario has to restore is the content, which is what this
     * compares.</p>
     */
    private static Object normalize(String key, Object value) {
        if (value == null && key.endsWith("#referencePriority")) {
            return 0;
        }
        if (value instanceof OperationalLimitsInfo info) {
            return describe(info);
        }
        return value;
    }

    /** The content of a set of loading limits, as a value two instances can be compared by. */
    private static String describe(OperationalLimitsInfo info) {
        if (!(info.value() instanceof LoadingLimits limits)) {
            return info.groupId() + " " + info.inSelectedGroup() + " " + info.value();
        }
        StringBuilder description = new StringBuilder(info.groupId()).append(' ').append(info.inSelectedGroup())
                .append(" patl=").append(limits.getPermanentLimit());
        limits.getTemporaryLimits().forEach(temporaryLimit -> description.append(" tatl ")
                .append(temporaryLimit.getAcceptableDuration()).append('=').append(temporaryLimit.getValue()));
        return description.toString();
    }

    private static void putNewValue(Map<String, Object> lastNew, NetworkEvent event) {
        String key = key(event);
        if (key != null) {
            lastNew.put(key, newValue(event));
        }
    }

    private static String key(NetworkEvent event) {
        return switch (event) {
            case UpdateNetworkEvent update -> update.id() + "." + update.attribute();
            case ExtensionUpdateNetworkEvent update -> update.id() + "." + update.extensionName() + "#" + update.attribute();
            default -> null;
        };
    }

    private static Object oldValue(NetworkEvent event) {
        return switch (event) {
            case UpdateNetworkEvent update -> update.oldValue();
            case ExtensionUpdateNetworkEvent update -> update.oldValue();
            default -> null;
        };
    }

    private static Object newValue(NetworkEvent event) {
        return switch (event) {
            case UpdateNetworkEvent update -> update.newValue();
            case ExtensionUpdateNetworkEvent update -> update.newValue();
            default -> null;
        };
    }

    private static String golden(String name, String actual) throws IOException {
        String resource = "/" + GOLDEN_DIR + "/" + name + "_SSH.xml";
        if (Boolean.getBoolean(REGENERATE)) {
            Path path = Path.of("src", "test", "resources", GOLDEN_DIR, name + "_SSH.xml");
            Files.createDirectories(path.getParent());
            Files.writeString(path, actual, StandardCharsets.UTF_8);
        }
        try (InputStream inputStream = PartialSshExportGoldenTest.class.getResourceAsStream(resource)) {
            assertNotNull(inputStream, "Missing golden file " + resource
                    + ", regenerate it with -D" + REGENERATE + "=true");
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
