/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.powsybl.cgmes.model.CgmesNamespace.RDF_NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A snapshot of the full steady state hypothesis export, one row per fixture and subject, so that a change of what the
 * full export writes cannot go unnoticed: a row names the fixture, the class and the identifier of a subject and a
 * digest of its properties in the order they are written (name and lexical value). One {@code ORDER} row per fixture
 * holds a digest of the sequence of the subjects, which pins the order of the sections and of the controls.
 *
 * <p>The fixtures are those of {@link FullExportExpectationTest}. The header is kept without its identifier and
 * without {@code Model.created}, which change on every export. The rows are committed in {@value #EXPECTED} and checked
 * both ways: every subject written has its row, every committed row is written. {@code -Dsnapshot.regenerate=true}
 * rewrites the file from the observed rows, for a review of the diff, as {@code RegulationSetterMatrixTest} does with
 * its expected outcomes.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class FullSshExportSnapshotTest {

    static final String EXPECTED = "/full-ssh-export/expected-subjects.tsv";
    private static final String ORDER = "ORDER";
    private static final String HEADER = "header";
    private static final Set<String> HEADER_PROPERTIES_LEFT_OUT = Set.of("Model.created");
    private static final boolean REGENERATE = Boolean.getBoolean("snapshot.regenerate");

    /** One subject as written: its class, its identifier and the digest of its properties. */
    record Subject(String className, String id, String digest) {
        String row(String fixture) {
            return fixture + "\t" + className + "\t" + id + "\t" + digest;
        }
    }

    private static final Map<String, List<String>> OBSERVED = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> EXPECTED_ROWS = expectedRows();

    static List<FullExportExpectationTest.Fixture> fixtures() {
        return FullExportExpectationTest.fixtures();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void theFullSshExportIsTheCommittedSnapshot(FullExportExpectationTest.Fixture fixture) {
        List<Subject> subjects = subjects(FullExportExpectationTest.fullSsh(fixture.loader().get()));
        List<String> rows = new ArrayList<>();
        subjects.forEach(subject -> rows.add(subject.row(fixture.name())));
        rows.add(fixture.name() + "\t" + ORDER + "\t\t" + digest(subjects.stream()
                .map(subject -> subject.className() + " " + subject.id()).collect(Collectors.joining("\n"))));
        OBSERVED.put(fixture.name(), rows);
        if (!REGENERATE) {
            List<String> expected = EXPECTED_ROWS.getOrDefault(fixture.name(), List.of());
            List<String> missing = expected.stream().filter(row -> !rows.contains(row)).toList();
            List<String> unexpected = rows.stream().filter(row -> !expected.contains(row)).toList();
            assertTrue(missing.isEmpty() && unexpected.isEmpty(), () -> fixture.name()
                    + ": the full SSH export is not the committed snapshot (" + EXPECTED + ", regenerate with"
                    + " -Dsnapshot.regenerate=true and review the diff)\n  committed, not written:\n    "
                    + String.join("\n    ", missing) + "\n  written, not committed:\n    " + String.join("\n    ", unexpected));
        }
    }

    /** Every committed row belongs to a fixture: a removed fixture leaves no row behind. */
    @Test
    void everyCommittedRowIsACase() {
        Set<String> names = new HashSet<>();
        fixtures().forEach(fixture -> names.add(fixture.name()));
        assertEquals(Set.of(), EXPECTED_ROWS.keySet().stream().filter(name -> !names.contains(name))
                .collect(Collectors.toSet()), "rows without a fixture");
    }

    /** With {@code -Dsnapshot.regenerate=true}, write the observed rows as the new committed ones. */
    @AfterAll
    static void regenerate() throws IOException {
        if (!REGENERATE) {
            return;
        }
        StringBuilder subjects = new StringBuilder("# fixture\tclass\tmRID\tdigest (FullSshExportSnapshotTest: 12 hex digits"
                + " of SHA-256 over the properties of the subject in written order)\n");
        StringBuilder order = new StringBuilder("# fixture\tORDER\t\tdigest of the sequence of the subjects\n");
        for (FullExportExpectationTest.Fixture fixture : fixtures()) {
            // A fixture that failed has no rows and is left out: the next run then fails on it
            for (String row : OBSERVED.getOrDefault(fixture.name(), List.of())) {
                (row.contains("\t" + ORDER + "\t") ? order : subjects).append(row).append('\n');
            }
        }
        Path file = Path.of("src/test/resources" + EXPECTED);
        Files.createDirectories(file.getParent());
        Files.writeString(file, subjects.append(order).toString());
    }

    private static Map<String, List<String>> expectedRows() {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        InputStream stream = FullSshExportSnapshotTest.class.getResourceAsStream(EXPECTED);
        if (stream == null) {
            return expected;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .forEach(line -> expected.computeIfAbsent(line.substring(0, line.indexOf('\t')), f -> new ArrayList<>()).add(line));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return expected;
    }

    /**
     * The subjects of a CGMES instance file in the order they are written: every element at the top level of the
     * document, the header included, each with the digest of its properties in written order. A resource is its
     * {@code rdf:resource}, a literal its text. A subject written twice under the same class gets a numbered identifier.
     */
    static List<Subject> subjects(String xml) {
        List<Subject> subjects = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        try {
            XMLStreamReader reader = XMLInputFactory.newInstance().createXMLStreamReader(new StringReader(xml));
            int depth = 0;
            String className = null;
            String id = null;
            boolean header = false;
            String property = null;
            String resource = null;
            StringBuilder text = new StringBuilder();
            StringBuilder properties = new StringBuilder();
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (depth == 2) {
                        className = reader.getLocalName();
                        String about = reader.getAttributeValue(RDF_NAMESPACE, "about");
                        String rdfId = about != null ? about : reader.getAttributeValue(RDF_NAMESPACE, "ID");
                        header = "FullModel".equals(className);
                        id = header ? HEADER : DifferenceModelParser.normalizeId(String.valueOf(rdfId));
                        properties.setLength(0);
                    } else if (depth == 3) {
                        property = reader.getLocalName();
                        resource = reader.getAttributeValue(RDF_NAMESPACE, "resource");
                        text.setLength(0);
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && depth == 3) {
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (depth == 3 && !(header && HEADER_PROPERTIES_LEFT_OUT.contains(property))) {
                        properties.append(property).append('=').append(resource != null ? resource : text.toString().trim())
                                .append('\n');
                    } else if (depth == 2) {
                        int occurrence = occurrences.merge(className + " " + id, 1, Integer::sum);
                        subjects.add(new Subject(className, occurrence == 1 ? id : id + "#" + occurrence,
                                digest(properties.toString())));
                    }
                    depth--;
                }
            }
        } catch (XMLStreamException e) {
            throw new UncheckedXmlStreamException(e);
        }
        return subjects;
    }

    static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
