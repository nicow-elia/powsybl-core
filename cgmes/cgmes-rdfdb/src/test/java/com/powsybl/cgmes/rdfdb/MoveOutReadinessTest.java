/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the powsybl-core types the module's main code uses, so that the module can move out of core later.
 *
 * <p>Every {@code import com.powsybl.…} (and {@code import static com.powsybl.…}) of a main source file, and every
 * fully qualified {@code com.powsybl.…} type named in code, has to be on the allow-list
 * {@code allowed-core-imports.txt} next to this test; every entry of the list has to be used. A new dependency on
 * core therefore shows up in a review as a one-line addition to the list, and a dependency that went away as a
 * removal. The first block of the list is the surface whose members say in their javadoc that they are used by the
 * RDF database layer; the second block is ordinary public API. Wildcard imports of core are refused, since they
 * would hide what is used. The module's own package is not core and is not listed.</p>
 *
 * <p>A source scan rather than a bytecode analysis: it needs no extra dependency and reads the same files a
 * reviewer does.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class MoveOutReadinessTest {

    private static final String OWN_PACKAGE = "com.powsybl.cgmes.rdfdb.";
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(static\\s+)?(com\\.powsybl\\.[\\w.]+?)(\\.\\*)?\\s*;");
    private static final Pattern QUALIFIED = Pattern.compile("\\bcom\\.powsybl\\.(?:[a-z]\\w*\\.)+[A-Z]\\w*");

    /** Every core type the main code uses, with the files that use it. */
    private static Map<String, List<String>> used;
    private static List<String> wildcards;
    private static List<List<String>> allowListBlocks;

    @BeforeAll
    static void scan() throws IOException {
        Path sources = Path.of(System.getProperty("basedir", ""), "src/main/java/com/powsybl/cgmes/rdfdb");
        assertThat(sources).isDirectory();
        used = new TreeMap<>();
        wildcards = new ArrayList<>();
        try (Stream<Path> files = Files.list(sources)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                scan(file);
            }
        }
        allowListBlocks = readAllowList();
    }

    private static void scan(Path file) throws IOException {
        String name = file.getFileName().toString();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            Matcher imported = IMPORT.matcher(trimmed);
            if (imported.find()) {
                if (imported.group(3) != null) {
                    wildcards.add(name + ": " + trimmed);
                    continue;
                }
                String target = imported.group(2);
                // A static import names a member: the type is everything before it.
                use(imported.group(1) != null ? target.substring(0, target.lastIndexOf('.')) : target, name);
            } else if (!trimmed.startsWith("package ") && !trimmed.startsWith("*") && !trimmed.startsWith("/")) {
                Matcher qualified = QUALIFIED.matcher(trimmed);
                while (qualified.find()) {
                    use(qualified.group(), name);
                }
            }
        }
    }

    private static void use(String type, String file) {
        if (!type.startsWith(OWN_PACKAGE)) {
            used.computeIfAbsent(type, t -> new ArrayList<>()).add(file);
        }
    }

    private static List<List<String>> readAllowList() throws IOException {
        List<List<String>> blocks = new ArrayList<>();
        List<String> block = new ArrayList<>();
        try (InputStream in = Objects.requireNonNull(
                MoveOutReadinessTest.class.getResourceAsStream("allowed-core-imports.txt"))) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String entry = line.trim();
                if (entry.startsWith("#")) {
                    if (!block.isEmpty()) {
                        blocks.add(block);
                    }
                    block = new ArrayList<>();
                } else if (!entry.isEmpty()) {
                    block.add(entry);
                }
            }
        }
        if (!block.isEmpty()) {
            blocks.add(block);
        }
        return blocks;
    }

    private static TreeSet<String> allowed() {
        TreeSet<String> allowed = new TreeSet<>();
        allowListBlocks.forEach(allowed::addAll);
        return allowed;
    }

    @Test
    void everyCoreTypeTheModuleUsesIsOnTheAllowList() {
        TreeSet<String> allowed = allowed();
        List<String> unlisted = used.entrySet().stream()
                .filter(e -> !allowed.contains(e.getKey()))
                .map(e -> e.getKey() + " (used by " + String.join(", ", new TreeSet<>(e.getValue())) + ")")
                .toList();
        assertThat(unlisted)
                .as("core types used by the module but missing from allowed-core-imports.txt")
                .isEmpty();
    }

    @Test
    void everyAllowListEntryIsUsed() {
        assertThat(allowed())
                .as("stale entries of allowed-core-imports.txt: no main source file uses them any more")
                .isSubsetOf(used.keySet());
    }

    @Test
    void theAllowListIsSortedWithoutDuplicates() {
        for (List<String> block : allowListBlocks) {
            assertThat(block).as("each block of allowed-core-imports.txt, one entry per line").isSorted()
                    .doesNotHaveDuplicates();
        }
        assertThat(allowListBlocks.stream().mapToInt(List::size).sum()).isEqualTo(allowed().size());
    }

    @Test
    void noWildcardImportOfCore() {
        assertThat(wildcards).as("wildcard imports of com.powsybl hide what the module uses").isEmpty();
    }
}
