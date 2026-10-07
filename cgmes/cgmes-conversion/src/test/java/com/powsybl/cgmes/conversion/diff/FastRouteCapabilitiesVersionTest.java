/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.FamilySpec;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.VariantSafety;
import com.powsybl.cgmes.model.CgmesSubset;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capability version a database stores next to every difference: a hash of what the table declares and the
 * core version that declared it. A reader compares it with its own, so it must not depend on anything but the
 * declarations &mdash; in particular not on the iteration order of the sets of the table, which differs between
 * JVMs.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class FastRouteCapabilitiesVersionTest {

    @Test
    void theHashIsTheSha256OfTheDeclarations() throws NoSuchAlgorithmException {
        String expected = expectedCanonicalText();
        assertEquals(expected, FastRouteCapabilities.canonicalText());
        String hash = FastRouteCapabilities.tableHash();
        assertTrue(hash.matches("[0-9a-f]{12}"), hash);
        assertEquals(sha256(expected).substring(0, 12), hash);
        assertEquals(hash, FastRouteCapabilities.tableHash());
    }

    @Test
    void theCanonicalTextNamesEveryFamilyOnce() {
        List<String> lines = FastRouteCapabilities.canonicalText().lines().toList();
        for (FamilySpec spec : FastRouteCapabilities.table()) {
            assertEquals(1, lines.stream().filter(line -> line.startsWith(spec.family().name() + "|")).count(),
                    spec.family().name());
        }
    }

    @Test
    void theVersionIsTheHashAndTheCoreVersion() {
        String version = FastRouteCapabilities.version();
        assertEquals(version, FastRouteCapabilities.version());
        String[] parts = version.split("/", 2);
        assertEquals(FastRouteCapabilities.tableHash(), parts[0]);
        assertFalse(parts[1].isBlank());
        assertFalse(parts[1].contains("${"), "the resource was not filtered: " + parts[1]);
        assertTrue(parts[1].matches("\\d+\\.\\d+\\.\\d+.*"), parts[1]);
    }

    @Test
    void anotherJvmComputesTheSameVersion() throws IOException, InterruptedException {
        // Set.of iterates in an order salted per JVM: the same declarations must give the same text in another one
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                PrintVersion.class.getName()).redirectErrorStream(false).start();
        String printed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        assertEquals(FastRouteCapabilities.version(), printed);
    }

    @Test
    void aChangedDeclarationChangesTheHash() throws NoSuchAlgorithmException {
        String text = expectedCanonicalText();
        String changed = text.replaceFirst("\\|SAFE\n", "|UNSAFE\n");
        assertNotEquals(text, changed);
        assertNotEquals(sha256(text).substring(0, 12), sha256(changed).substring(0, 12));
    }

    /** Prints the capability version of a fresh JVM. */
    static final class PrintVersion {
        public static void main(String[] args) {
            System.out.println(FastRouteCapabilities.version());
        }
    }

    /**
     * The canonical text built from the public table, independently of the production builder: one line per family
     * in table order, then the properties outside the in-place route, then the properties that are shared by every
     * variant although their family is not.
     */
    private static String expectedCanonicalText() {
        StringBuilder text = new StringBuilder();
        for (FamilySpec spec : FastRouteCapabilities.table()) {
            text.append(spec.family().name()).append('|')
                    .append(spec.handler().name()).append('|')
                    .append(spec.updateQuery()).append('|')
                    .append(sorted(spec.subsets().stream().map(CgmesSubset::getIdentifier))).append('|')
                    .append(spec.canonicalType()).append('|')
                    .append(sorted(spec.rdfTypes().stream())).append('|')
                    .append(spec.groups().stream()
                            .map(group -> sorted(group.required().stream()) + ";" + sorted(group.optional().stream()))
                            .collect(Collectors.joining(","))).append('|')
                    .append(spec.variantSafety().name()).append('\n');
        }
        FastRouteCapabilities.notDifferenceUpdatableProperties().stream().sorted()
                .forEach(property -> text.append("notDifferenceUpdatable|").append(property).append('\n'));
        FastRouteCapabilities.table().stream()
                .filter(spec -> spec.variantSafety() != VariantSafety.UNSAFE)
                .flatMap(spec -> spec.properties().stream()
                        .filter(property -> FastRouteCapabilities.variantSafety(spec.family(), property)
                                == VariantSafety.UNSAFE))
                .distinct().sorted()
                .forEach(property -> text.append("variantUnsafe|").append(property).append('\n'));
        return text.toString();
    }

    private static String sorted(Stream<String> values) {
        return values.sorted().collect(Collectors.joining(" "));
    }

    private static String sha256(String text) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
