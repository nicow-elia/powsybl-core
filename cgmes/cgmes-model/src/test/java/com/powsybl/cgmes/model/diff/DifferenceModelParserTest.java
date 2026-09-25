/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.model.diff;

import com.powsybl.cgmes.model.CgmesModelException;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.datasource.MemDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.powsybl.cgmes.model.diff.DifferenceModelParser.RDF_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading of {@code dm:DifferenceModel} documents, from the shape this library writes to the shapes other producers
 * write.
 *
 * <p>Every fixture of {@code /diff/variants/} states a single load change unless the test says otherwise, so that the
 * assertions can be read without opening the file.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class DifferenceModelParserTest {

    private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
    private static final String CIM100 = "http://iec.ch/TC57/CIM100#";
    private static final String SSH_PROFILE = "http://entsoe.eu/CIM/SteadyStateHypothesis/1/1";

    private static DifferenceModel parseVariant(String fixture) {
        return parseVariant(fixture, fixture);
    }

    private static DifferenceModel parseVariant(String fixture, String fileName) {
        try (InputStream is = DifferenceModelParserTest.class.getResourceAsStream("/diff/variants/" + fixture)) {
            return DifferenceModelParser.parse(is, fileName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String variant(String fixture) {
        try (InputStream is = DifferenceModelParserTest.class.getResourceAsStream("/diff/variants/" + fixture)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void parsesWriterOutput() {
        DifferenceModelHeader header = DifferenceModelHeader
                .builder("urn:uuid:5b6f0c1e-3c1a-4f0e-9d53-0e2d6c3b7a11", CgmesSubset.STEADY_STATE_HYPOTHESIS, CIM100)
                .scenarioTime(ZonedDateTime.of(2024, 2, 21, 11, 0, 0, 0, ZoneOffset.UTC))
                .created(ZonedDateTime.of(2026, 9, 17, 8, 0, 0, 0, ZoneOffset.UTC))
                .description("Load redispatch")
                .version(2)
                .modelingAuthoritySet("https://www.powsybl.org/")
                .profiles(List.of("http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/3.0"))
                .dependentOn(List.of("urn:uuid:EQ-model-id-inherited-from-the-source-SSH"))
                .supersedes(List.of("urn:uuid:d400c631-75a0-4c30-8aed-832b0d282e74"))
                .build();
        DifferenceModel model = new DifferenceModel(header,
                List.of(CgmesStatement.literal("EnergyConsumer", null, "EnergyConsumer.p", "12.5"),
                        CgmesStatement.enumeration("RC", null, "RegulatingControl.targetValueUnitMultiplier", "UnitMultiplier.k"),
                        CgmesStatement.reference("RC", null, "RegulatingControl.Terminal", "T1")),
                List.of(CgmesStatement.literal("EnergyConsumer", null, "EnergyConsumer.p", "10")),
                List.of());

        assertEquals(model, DifferenceModelParser.parse(DifferenceModelWriter.toString(model)));
    }

    @Test
    void typedNodesBecomeTypeStatementsAndClassHints() {
        DifferenceModel model = parseVariant("typed-nodes.xml");
        assertEquals(List.of(
                CgmesStatement.reference("L1", "ConformLoad", RDF_TYPE, "ConformLoad"),
                CgmesStatement.literal("L1", "ConformLoad", "EnergyConsumer.p", "12.5")),
                model.forward());
        assertEquals(List.of(
                CgmesStatement.reference("L1", "ConformLoad", RDF_TYPE, "ConformLoad"),
                CgmesStatement.literal("L1", "ConformLoad", "EnergyConsumer.p", "10")),
                model.reverse());
        // The class hint is carried although equality ignores it
        assertEquals("ConformLoad", model.forward().get(1).className());
        assertEquals(CgmesSubset.STEADY_STATE_HYPOTHESIS, model.header().subset());
    }

    @Test
    void urnUuidAndHashUnderscoreSubjectsAreTheSameId() {
        DifferenceModel model = parseVariant("urn-uuid-about.xml");
        assertEquals("L1", model.forward().get(0).subjectId());
        assertEquals("L1", model.reverse().get(0).subjectId());
    }

    @Test
    void rdfIdSubject() {
        DifferenceModel model = parseVariant("rdf-id.xml");
        assertEquals("urn:uuid:rdf-id-model", model.header().id());
        assertEquals(List.of(
                CgmesStatement.reference("N1", "ConformLoad", RDF_TYPE, "ConformLoad"),
                CgmesStatement.literal("N1", "ConformLoad", "EnergyConsumer.p", "12.5")),
                model.forward());
    }

    @Test
    void containerOrderAndPreconditionsAreFree() {
        DifferenceModel model = parseVariant("opencgmes-order.xml");
        assertEquals(List.of(CgmesStatement.literal("L1", null, "EnergyConsumer.q", "5")), model.preconditions());
        assertEquals(List.of(
                CgmesStatement.literal("L1", null, "EnergyConsumer.p", "12.5"),
                CgmesStatement.reference("L2", "ConformLoad", RDF_TYPE, "ConformLoad"),
                CgmesStatement.literal("L2", "ConformLoad", "EnergyConsumer.p", "1")),
                model.forward());
        assertEquals(List.of(
                CgmesStatement.literal("L1", null, "EnergyConsumer.p", "10"),
                CgmesStatement.reference("L3", "ConformLoad", RDF_TYPE, "ConformLoad"),
                CgmesStatement.literal("L3", "ConformLoad", "EnergyConsumer.p", "2")),
                model.reverse());
    }

    @Test
    void headerWithoutModelProperties() {
        DifferenceModel model = parseVariant("opencgmes-order.xml", "x_SSH_DIFF.xml");
        assertEquals("urn:uuid:opencgmes-order", model.header().id());
        assertNull(model.header().scenarioTime());
        assertNull(model.header().created());
        assertNull(model.header().description());
        assertNull(model.header().modelingAuthoritySet());
        assertEquals(0, model.header().version());
        assertEquals(List.of(), model.header().profiles());
        assertEquals(List.of("urn:uuid:base-model"), model.header().supersedes());
        // No profile at all: the file name decides
        assertEquals(CgmesSubset.STEADY_STATE_HYPOTHESIS, model.header().subset());
        assertEquals(CgmesSubset.UNKNOWN, parseVariant("opencgmes-order.xml", "nothing.xml").header().subset());
        assertEquals(CgmesSubset.UNKNOWN, parseVariant("opencgmes-order.xml", null).header().subset());
    }

    @Test
    void severalProfilesInOneHeader() {
        DifferenceModel model = parseVariant("multi-profile-header.xml");
        assertEquals(2, model.header().profiles().size());
        // The steady state hypothesis wins, and nothing is split: the capability check reports the statements of the
        // other profile as not updatable
        assertEquals(CgmesSubset.STEADY_STATE_HYPOTHESIS, model.header().subset());
    }

    @Test
    void entsoeHeaderExample() {
        DifferenceModel model = parseVariant("hdr-example.xml");
        DifferenceModelHeader header = model.header();
        assertEquals("urn:uuid:7ca72efa-e952-11e3-89cf-82687f4fc15c", header.id());
        assertEquals(ZonedDateTime.of(2030, 1, 15, 17, 0, 0, 0, ZoneOffset.UTC), header.scenarioTime());
        assertEquals(ZonedDateTime.of(2014, 5, 15, 17, 48, 31, 474_000_000, ZoneOffset.UTC), header.created());
        assertEquals("CGMES Conformity Assessment: This is guidelines on the file header.", header.description());
        assertEquals(2, header.version());
        assertEquals(List.of("http://entsoe.eu/CIM/Topology/4/1"), header.profiles());
        assertEquals(List.of("urn:uuid:bcb6877a-e948-11e3-89cf-82687f4fc15c"), header.dependentOn());
        assertEquals(List.of("urn:uuid:d63e4784-e94b-11e3-89cf-82687f4fc15c"), header.supersedes());
        assertEquals("http://elia.be/Planning/CGMES/2.4.14", header.modelingAuthoritySet());
        assertEquals(CIM16, header.cimNamespace());
        assertEquals(CgmesSubset.TOPOLOGY, header.subset());
    }

    @Test
    void enumReferenceAndForeignNamespaceValues() {
        DifferenceModel model = parseVariant("values.xml");
        assertEquals(List.of(
                CgmesStatement.enumeration("RC1", null, "RegulatingControl.targetValueUnitMultiplier", "UnitMultiplier.k"),
                CgmesStatement.reference("RC1", null, "RegulatingControl.Terminal", "T1"),
                CgmesStatement.reference("RC1", null, "RegulatingControl.RegulatingCondEq", "G1"),
                CgmesStatement.reference("RC1", null, "RegulatingControl.Foreign", "X1"),
                CgmesStatement.literal("RC1", null,
                        "http://entsoe.eu/CIM/SchemaExtension/3/1#EnergyConsumer.hint", "kept"),
                // A namespace ending in '/' is as legal as one ending in '#'
                CgmesStatement.literal("RC1", null,
                        "http://example.org/ext/EnergyConsumer.note", "slash namespace")),
                model.forward());
    }

    @Test
    void foreignNamespacePropertiesSurviveAWriteAndReadBack() {
        DifferenceModel model = parseVariant("values.xml");
        assertEquals(model, DifferenceModelParser.parse(DifferenceModelWriter.toString(model)));
    }

    @Test
    void explicitRdfTypeProperty() {
        DifferenceModel model = parseVariant("explicit-rdf-type.xml");
        assertEquals(List.of(
                CgmesStatement.reference("L1", null, RDF_TYPE, "ConformLoad"),
                CgmesStatement.literal("L1", null, "EnergyConsumer.p", "12.5")),
                model.forward());
        // A type statement is written back as an explicit rdf:type whatever shape it came from
        assertEquals(model, DifferenceModelParser.parse(DifferenceModelWriter.toString(model)));
    }

    @Test
    void emptyAndMissingContainers() {
        DifferenceModel model = parseVariant("empty-containers.xml");
        assertEquals(List.of(), model.reverse());
        assertEquals(List.of(), model.preconditions());
        // A repeated container appends
        assertEquals(List.of(
                CgmesStatement.literal("L1", null, "EnergyConsumer.p", "12.5"),
                CgmesStatement.literal("L2", null, "EnergyConsumer.p", "1")),
                model.forward());
    }

    @Test
    void otherPrefixesThanDmAndCim() {
        DifferenceModel model = parseVariant("other-prefixes.xml");
        assertEquals(List.of(CgmesStatement.literal("L1", null, "EnergyConsumer.p", "12.5")), model.forward());
        assertEquals(CIM16, model.header().cimNamespace());
    }

    @Test
    void unparsableHeaderValuesAreIgnored() {
        DifferenceModel model = parseVariant("unparsable-header.xml");
        assertNull(model.header().scenarioTime());
        assertEquals(0, model.header().version());
    }

    @Test
    void fullModelIsRejected() {
        CgmesModelException e = assertThrows(CgmesModelException.class, () -> parseVariant("full-model.xml"));
        assertTrue(e.getMessage().contains("is not a difference model"), e.getMessage());
    }

    @Test
    void twoDifferenceModelsAreRejected() {
        CgmesModelException e = assertThrows(CgmesModelException.class,
            () -> parseVariant("two-difference-models.xml"));
        assertTrue(e.getMessage().contains("several difference models"), e.getMessage());
    }

    @Test
    void nestedDescriptionIsRejected() {
        CgmesModelException e = assertThrows(CgmesModelException.class,
            () -> parseVariant("nested-description.xml"));
        assertTrue(e.getMessage().contains("nested descriptions are not supported"), e.getMessage());
    }

    @Test
    void blankNodeIsRejected() {
        CgmesModelException e = assertThrows(CgmesModelException.class, () -> parseVariant("blank-node.xml"));
        assertTrue(e.getMessage().contains("blank nodes are not supported"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"typed-nodes.xml", "urn-uuid-about.xml", "rdf-id.xml", "opencgmes-order.xml",
        "multi-profile-header.xml", "hdr-example.xml", "values.xml", "explicit-rdf-type.xml",
        "empty-containers.xml", "other-prefixes.xml", "two-difference-models.xml", "nested-description.xml",
        "blank-node.xml", "unparsable-header.xml"})
    void isDifferenceModelSniffsOnlyTheFirstElements(String fixture) {
        assertTrue(DifferenceModelParser.isDifferenceModel(stream(variant(fixture))));
    }

    @Test
    void isDifferenceModelRejectsFullModelsAndGarbage() {
        assertFalse(DifferenceModelParser.isDifferenceModel(stream(variant("full-model.xml"))));
        assertFalse(DifferenceModelParser.isDifferenceModel(stream("not xml at all")));
        assertFalse(DifferenceModelParser.isDifferenceModel(stream("")));
        assertFalse(DifferenceModelParser.isDifferenceModel(stream("<rdf:RDF xmlns:rdf=\""
                + "http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/>")));
    }

    @Test
    void parseAllReadsEveryDifferenceFileOfADataSource() throws IOException {
        MemDataSource dataSource = new MemDataSource();
        dataSource.putData("a_SSH_DIFF.xml", variant("typed-nodes.xml").getBytes(StandardCharsets.UTF_8));
        dataSource.putData("b_TP_DIFF.xml", variant("hdr-example.xml").getBytes(StandardCharsets.UTF_8));
        dataSource.putData("c_EQ.xml", variant("full-model.xml").getBytes(StandardCharsets.UTF_8));
        dataSource.putData("d_EQ_DIFF.xml.zip", zipped(variant("eq-diff.xml")));

        DifferenceModelSet set = DifferenceModelParser.parseAll(dataSource);
        // The full model file is left out and the zip entry is entered
        assertEquals(3, set.models().size());
        assertEquals("urn:uuid:typed-nodes", set.get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow().header().id());
        assertEquals("urn:uuid:7ca72efa-e952-11e3-89cf-82687f4fc15c",
                set.get(CgmesSubset.TOPOLOGY).orElseThrow().header().id());
        assertEquals("urn:uuid:eq-diff", set.get(CgmesSubset.EQUIPMENT).orElseThrow().header().id());
    }

    private static byte[] zipped(String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("entry.xml"));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void normalizeIdAcceptsEveryShapeOfAnIdentifier() {
        assertEquals("abc", DifferenceModelParser.normalizeId("abc"));
        assertEquals("abc", DifferenceModelParser.normalizeId("_abc"));
        assertEquals("abc", DifferenceModelParser.normalizeId("#_abc"));
        assertEquals("abc", DifferenceModelParser.normalizeId("urn:uuid:abc"));
        assertEquals("abc", DifferenceModelParser.normalizeId("http://example.org/model#_abc"));
        assertEquals("SSH_PROFILE", DifferenceModelParser.normalizeId("SSH_PROFILE"));
        assertEquals("", DifferenceModelParser.normalizeId(""));
    }

    @Test
    void sshProfileConstantIsRecognized() {
        DifferenceModel model = parseVariant("typed-nodes.xml");
        assertEquals(List.of(SSH_PROFILE), model.header().profiles());
    }
}
