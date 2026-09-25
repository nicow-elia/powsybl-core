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
import com.powsybl.commons.datasource.MemDataSource;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class DifferenceModelWriterTest {

    /** Rewrites the reference documents instead of comparing against them. */
    private static final String REGENERATE = "diffstacking.regenerate";
    private static final String REFERENCE = "/diff/writer-reference_SSH_DIFF.xml";

    private static DifferenceModelHeader.Builder header(String cimNamespace) {
        return DifferenceModelHeader.builder("urn:uuid:5b6f0c1e-3c1a-4f0e-9d53-0e2d6c3b7a11",
                        CgmesSubset.STEADY_STATE_HYPOTHESIS, cimNamespace)
                .scenarioTime(ZonedDateTime.parse("2024-02-21T11:00:00Z"))
                .created(ZonedDateTime.parse("2026-09-17T08:00:00Z"))
                .description("Load redispatch")
                .version(2)
                .modelingAuthoritySet("https://www.powsybl.org/")
                .profiles(List.of(CgmesNamespace.CIM_100_SSH_PROFILE))
                .dependentOn(List.of("urn:uuid:EQ-model-id-inherited-from-the-source-SSH"))
                .supersedes(List.of("urn:uuid:d400c631-75a0-4c30-8aed-832b0d282e74"));
    }

    private static CgmesStatement p(String value) {
        return CgmesStatement.literal("EnergyConsumer", "ConformLoad", "EnergyConsumer.p", value);
    }

    private static CgmesStatement q(String value) {
        return CgmesStatement.literal("EnergyConsumer", "ConformLoad", "EnergyConsumer.q", value);
    }

    private static DifferenceModel referenceModel() {
        return new DifferenceModel(header(CgmesNamespace.CIM_100_NAMESPACE).build(),
                List.of(p("12.5"), q("5")), List.of(p("10"), q("5")), List.of());
    }

    @Test
    void writesHeaderAndBothStatementContainers() throws IOException {
        String written = DifferenceModelWriter.toString(referenceModel());
        assertEquals(reference(REFERENCE, written), written.replace("\r\n", "\n"));
    }

    @Test
    void enumAndReferenceAreWrittenAsResources() {
        DifferenceModel model = new DifferenceModel(header(CgmesNamespace.CIM_100_NAMESPACE).build(),
                List.of(CgmesStatement.enumeration("RC", "RegulatingControl",
                                "RegulatingControl.targetValueUnitMultiplier", "UnitMultiplier.k"),
                        CgmesStatement.reference("RC", "RegulatingControl", "RegulatingControl.Terminal", "T1")),
                List.of(), List.of());
        String written = DifferenceModelWriter.toString(model);
        assertTrue(written.contains("<cim:RegulatingControl.targetValueUnitMultiplier rdf:resource=\""
                + CgmesNamespace.CIM_100_NAMESPACE + "UnitMultiplier.k\"></cim:RegulatingControl.targetValueUnitMultiplier>")
                || written.contains("<cim:RegulatingControl.targetValueUnitMultiplier rdf:resource=\""
                + CgmesNamespace.CIM_100_NAMESPACE + "UnitMultiplier.k\"/>"), written);
        assertTrue(written.contains("rdf:resource=\"#_T1\""), written);
    }

    @Test
    void emptyModelWritesEmptyContainers() {
        DifferenceModel model = new DifferenceModel(header(CgmesNamespace.CIM_100_NAMESPACE).build(),
                List.of(), List.of(), List.of());
        String written = DifferenceModelWriter.toString(model);
        assertTrue(written.contains("dm:reverseDifferences"), written);
        assertTrue(written.contains("dm:forwardDifferences"), written);
        assertTrue(!written.contains("dm:preconditions"), written);
        assertTrue(!written.contains("rdf:Description"), written);
    }

    @Test
    void preconditionsAreWrittenWhenPresent() {
        DifferenceModel model = new DifferenceModel(header(CgmesNamespace.CIM_100_NAMESPACE).build(),
                List.of(), List.of(), List.of(p("10")));
        assertTrue(DifferenceModelWriter.toString(model).contains("dm:preconditions"));
    }

    @Test
    void statementsOfOneSubjectShareOneDescription() {
        DifferenceModel model = new DifferenceModel(header(CgmesNamespace.CIM_100_NAMESPACE).build(),
                // The two statements of the load are separated by one of another subject
                List.of(p("12.5"), CgmesStatement.literal("Switch", "Breaker", "Switch.open", "true"), q("5")),
                List.of(), List.of());
        String written = DifferenceModelWriter.toString(model);
        assertEquals(2, countOccurrences(written, "<rdf:Description"));
        // The load keeps the position of its first statement
        assertTrue(written.indexOf("#_EnergyConsumer") < written.indexOf("#_Switch"), written);
        assertTrue(written.indexOf("EnergyConsumer.q") < written.indexOf("Switch.open"), written);
    }

    @Test
    void noEuNamespaceForAnUnknownCimNamespace() {
        String written = DifferenceModelWriter.toString(new DifferenceModel(
                header("http://example.com/CIM42#").build(), List.of(), List.of(), List.of()));
        assertTrue(!written.contains("xmlns:eu="), written);
        assertTrue(!written.contains("xmlns:entsoe="), written);
        assertTrue(written.contains("xmlns:cim=\"http://example.com/CIM42#\""), written);
    }

    @Test
    void cim16UsesTheEntsoeExtensionPrefix() {
        String written = DifferenceModelWriter.toString(new DifferenceModel(
                header(CgmesNamespace.CIM_16_NAMESPACE).build(), List.of(), List.of(), List.of()));
        assertTrue(written.contains("xmlns:entsoe=\"" + CgmesNamespace.ENTSOE_NAMESPACE + "\""), written);
    }

    @Test
    void dataSourceGetsOneFilePerProfile() throws IOException {
        DifferenceModelSet set = new DifferenceModelSet(List.of(
                referenceModel(),
                new DifferenceModel(DifferenceModelHeader.builder("urn:uuid:eq", CgmesSubset.EQUIPMENT,
                                CgmesNamespace.CIM_100_NAMESPACE)
                        .scenarioTime(ZonedDateTime.parse("2024-02-21T11:00:00Z"))
                        .created(ZonedDateTime.parse("2026-09-17T08:00:00Z"))
                        .modelingAuthoritySet("https://www.powsybl.org/")
                        .build(), List.of(), List.of(), List.of())));
        MemDataSource dataSource = new MemDataSource();
        List<String> fileNames = DifferenceModelWriter.write(set, dataSource, "case");
        assertEquals(List.of("case_EQ_DIFF.xml", "case_SSH_DIFF.xml"), fileNames);
        assertTrue(CgmesSubset.EQUIPMENT.isValidName(fileNames.get(0)));
        assertTrue(CgmesSubset.STEADY_STATE_HYPOTHESIS.isValidName(fileNames.get(1)));
        for (String fileName : fileNames) {
            assertTrue(new String(dataSource.getData(fileName), StandardCharsets.UTF_8).contains("dm:DifferenceModel"));
        }
    }

    @Test
    void sinkWritesOneFilePerModel() {
        MemDataSource dataSource = new MemDataSource();
        DifferenceSink sink = DifferenceModelWriter.sink(dataSource, "case");
        sink.accept(new DifferenceModelSet(List.of(referenceModel())));
        assertNotNull(dataSource.getData("case_SSH_DIFF.xml"));
    }

    @Test
    void outputIsWellFormedXml() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder()
                .parse(new InputSource(new StringReader(DifferenceModelWriter.toString(referenceModel()))));
        assertEquals("RDF", document.getDocumentElement().getLocalName());
        assertEquals(CgmesNamespace.RDF_NAMESPACE, document.getDocumentElement().getNamespaceURI());
        assertEquals(1, document.getElementsByTagNameNS(CgmesNamespace.DM_NAMESPACE, "DifferenceModel").getLength());
        assertEquals(1, document.getElementsByTagNameNS(CgmesNamespace.DM_NAMESPACE, "forwardDifferences").getLength());
        assertEquals(1, document.getElementsByTagNameNS(CgmesNamespace.DM_NAMESPACE, "reverseDifferences").getLength());
    }

    /**
     * A header that carries nothing but its identifier &mdash; what a difference model of a foreign producer may
     * look like &mdash; writes no empty model description elements, so that writing it back and reading it again
     * gives the same header.
     */
    @Test
    void headerElementsThatAreNullAreLeftOut() {
        DifferenceModelHeader header = DifferenceModelHeader
                .builder("urn:uuid:no-description", CgmesSubset.STEADY_STATE_HYPOTHESIS, CgmesNamespace.CIM_16_NAMESPACE)
                .version(0)
                .build();
        DifferenceModel model = new DifferenceModel(header,
                List.of(CgmesStatement.literal("L1", null, "EnergyConsumer.p", "12.5")), List.of(), List.of());

        String xml = DifferenceModelWriter.toString(model);
        assertFalse(xml.contains("Model.scenarioTime"), xml);
        assertFalse(xml.contains("Model.created"), xml);
        assertFalse(xml.contains("Model.description"), xml);
        assertFalse(xml.contains("Model.modelingAuthoritySet"), xml);
        assertTrue(xml.contains("<md:Model.version>0</md:Model.version>"), xml);
        // The profile is only known from the file name here, because the header declares none
        assertEquals(model, DifferenceModelParser.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "x_SSH_DIFF.xml"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    /**
     * The reference document, regenerated from the given content when {@code -Ddiffstacking.regenerate=true} is set,
     * so that a deliberate layout change is a one-command update followed by a review of the diff.
     */
    private static String reference(String resource, String actual) throws IOException {
        if (Boolean.getBoolean(REGENERATE)) {
            Path path = Path.of("src", "test", "resources", resource.substring(1));
            Files.createDirectories(path.getParent());
            Files.writeString(path, actual, StandardCharsets.UTF_8);
        }
        try (InputStream inputStream = DifferenceModelWriterTest.class.getResourceAsStream(resource)) {
            assertNotNull(inputStream, "Missing reference " + resource
                    + ", regenerate it with -D" + REGENERATE + "=true");
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
