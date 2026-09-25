/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.model.diff;

import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.datasource.DataSource;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import com.powsybl.commons.xml.XmlUtil;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.powsybl.cgmes.model.CgmesNamespace.DM_NAMESPACE;
import static com.powsybl.cgmes.model.CgmesNamespace.MD_NAMESPACE;
import static com.powsybl.cgmes.model.CgmesNamespace.RDF_NAMESPACE;

/**
 * Serializes a {@link DifferenceModel} as an IEC 61970-552 difference model document.
 *
 * <p>The document holds a single {@code dm:DifferenceModel} whose model description is written exactly like the
 * {@code md:FullModel} of a complete CGMES instance file, followed by a {@code dm:reverseDifferences} and a
 * {@code dm:forwardDifferences} container. Both containers are always present, empty when the model says nothing in
 * that direction, and both carry {@code rdf:parseType="Statements"}, which is what tells an RDF parser that their
 * content is a list of statements about objects that exist elsewhere rather than a description of new objects.</p>
 *
 * <p>Inside a container every subject is described by exactly one {@code rdf:Description rdf:about="#_<id>"},
 * placed where the subject is first mentioned, and carries no {@code rdf:type}: a difference states properties of
 * objects the receiver already holds, it never introduces one.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class DifferenceModelWriter {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(ZoneOffset.UTC);

    private static final String DIFFERENCE_MODEL = "DifferenceModel";
    private static final String FORWARD_DIFFERENCES = "forwardDifferences";
    private static final String REVERSE_DIFFERENCES = "reverseDifferences";
    private static final String PRECONDITIONS = "preconditions";
    private static final String PARSE_TYPE = "parseType";
    private static final String STATEMENTS = "Statements";
    private static final String DESCRIPTION = "Description";
    private static final String TYPE = "type";
    private static final String RDF_TYPE = CgmesStatement.RDF_TYPE;
    /** The prefix given to a namespace this writer does not know, declared on the property element itself. */
    private static final String FOREIGN_PREFIX = "ns0";

    private DifferenceModelWriter() {
    }

    /**
     * Write a difference model as an XML document.
     *
     * @param model        the model to write
     * @param outputStream the stream to write to. It is neither flushed nor closed by this method, exactly as the
     *                     partial SSH export leaves it
     * @throws UncheckedXmlStreamException if the document cannot be written
     */
    public static void write(DifferenceModel model, OutputStream outputStream) {
        Objects.requireNonNull(model);
        Objects.requireNonNull(outputStream);
        try {
            // Buffered UTF-8 under the StAX writer: over a bare OutputStream the JDK writer emits one byte per call.
            // The same bytes; the StAX flush below reaches the stream through the buffer as it did before
            XMLStreamWriter writer = XmlUtil.initializeWriter(true, "    ",
                    new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)));
            writeDocument(model, writer);
            // Flush the StAX writer, not the stream: the contract of this method is that the stream is neither
            // flushed nor closed, but the document must be complete whatever the StAX implementation buffers
            writer.flush();
        } catch (XMLStreamException e) {
            throw new UncheckedXmlStreamException(e);
        }
    }

    /** The document of {@link #write(DifferenceModel, OutputStream)} as an in-memory string. */
    public static String toString(DifferenceModel model) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        write(model, outputStream);
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    /**
     * Write every model of a set as its own document into a data source, one file per profile.
     *
     * @param set      the models to write
     * @param dataSource where to write them, a directory or an archive
     * @param baseName the common prefix of the file names
     * @return the names of the files written, in profile order
     * @throws UncheckedIOException if a file cannot be opened or written
     */
    public static List<String> write(DifferenceModelSet set, DataSource dataSource, String baseName) {
        Objects.requireNonNull(set);
        Objects.requireNonNull(dataSource);
        Objects.requireNonNull(baseName);
        List<String> fileNames = new ArrayList<>(set.models().size());
        for (DifferenceModel model : set.models().values()) {
            String fileName = fileName(baseName, model.header().subset());
            try (OutputStream outputStream = dataSource.newOutputStream(fileName, false)) {
                write(model, outputStream);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            fileNames.add(fileName);
        }
        return fileNames;
    }

    /**
     * The name of the file holding the difference model of one profile.
     *
     * <p>It follows the CGMES instance file convention {@code <name>_<SUBSET>_...}, which is what
     * {@link CgmesSubset#isValidName(String)} recognizes, so that a reader can tell the profile of a file from its
     * name alone.</p>
     */
    public static String fileName(String baseName, CgmesSubset subset) {
        return baseName + "_" + subset.getIdentifier() + "_DIFF.xml";
    }

    /** A sink writing every model it is given as its own document into the given data source. */
    public static DifferenceSink sink(DataSource dataSource, String baseName) {
        Objects.requireNonNull(dataSource);
        Objects.requireNonNull(baseName);
        return model -> {
            try (OutputStream outputStream = dataSource.newOutputStream(fileName(baseName, model.header().subset()), false)) {
                write(model, outputStream);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static void writeDocument(DifferenceModel model, XMLStreamWriter writer) throws XMLStreamException {
        DifferenceModelHeader header = model.header();
        String cimNamespace = header.cimNamespace();
        writeRdfRoot(cimNamespace, writer);
        writer.writeStartElement(DM_NAMESPACE, DIFFERENCE_MODEL);
        writer.writeAttribute(RDF_NAMESPACE, CgmesNames.ABOUT, header.id());
        writeModelDescription(header, writer);
        if (!model.preconditions().isEmpty()) {
            writeStatements(PRECONDITIONS, model.preconditions(), cimNamespace, writer);
        }
        // Reverse before forward is a readability choice of this writer. IEC 61970-552 leaves the order of the
        // containers free and a reader must not rely on it.
        writeStatements(REVERSE_DIFFERENCES, model.reverse(), cimNamespace, writer);
        writeStatements(FORWARD_DIFFERENCES, model.forward(), cimNamespace, writer);
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndDocument();
    }

    private static void writeRdfRoot(String cimNamespace, XMLStreamWriter writer) throws XMLStreamException {
        String euPrefix = null;
        String euNamespace = null;
        for (CgmesNamespace.Cim cim : CgmesNamespace.CIM_LIST) {
            if (cim.getNamespace().equals(cimNamespace)) {
                euPrefix = cim.getEuPrefix();
                euNamespace = cim.getEuNamespace();
                break;
            }
        }
        if (euPrefix != null) {
            writer.setPrefix(euPrefix, euNamespace);
        }
        writer.setPrefix("rdf", RDF_NAMESPACE);
        writer.setPrefix("cim", cimNamespace);
        writer.setPrefix("md", MD_NAMESPACE);
        writer.setPrefix("dm", DM_NAMESPACE);
        writer.writeStartElement(RDF_NAMESPACE, "RDF");
        if (euPrefix != null) {
            writer.writeNamespace(euPrefix, euNamespace);
        }
        writer.writeNamespace("rdf", RDF_NAMESPACE);
        writer.writeNamespace("cim", cimNamespace);
        writer.writeNamespace("md", MD_NAMESPACE);
        writer.writeNamespace("dm", DM_NAMESPACE);
    }

    /**
     * The model description, in the property order of a CGMES {@code md:FullModel}.
     *
     * <p>Every element a header may leave out is skipped when it is {@code null}, so that a difference model parsed
     * from a document without a model description is written back without one instead of with empty elements.</p>
     */
    private static void writeModelDescription(DifferenceModelHeader header, XMLStreamWriter writer) throws XMLStreamException {
        if (header.scenarioTime() != null) {
            writeMdText(CgmesNames.SCENARIO_TIME, DATE_TIME_FORMATTER.format(header.scenarioTime()), writer);
        }
        if (header.created() != null) {
            writeMdText(CgmesNames.CREATED, DATE_TIME_FORMATTER.format(header.created()), writer);
        }
        if (header.description() != null) {
            writeMdText(CgmesNames.DESCRIPTION, header.description(), writer);
        }
        writeMdText(CgmesNames.VERSION, Integer.toString(header.version()), writer);
        for (String dependentOn : header.dependentOn()) {
            writeMdResource(CgmesNames.DEPENDENT_ON, dependentOn, writer);
        }
        for (String supersedes : header.supersedes()) {
            writeMdResource(CgmesNames.SUPERSEDES, supersedes, writer);
        }
        for (String profile : header.profiles()) {
            writeMdText(CgmesNames.PROFILE, profile, writer);
        }
        if (header.modelingAuthoritySet() != null) {
            writeMdText(CgmesNames.MODELING_AUTHORITY_SET, header.modelingAuthoritySet(), writer);
        }
    }

    private static void writeMdText(String property, String value, XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement(MD_NAMESPACE, property);
        writer.writeCharacters(value);
        writer.writeEndElement();
    }

    private static void writeMdResource(String property, String value, XMLStreamWriter writer) throws XMLStreamException {
        writer.writeEmptyElement(MD_NAMESPACE, property);
        writer.writeAttribute(RDF_NAMESPACE, CgmesNames.RESOURCE, value);
    }

    /**
     * One statement container, grouping the statements of a subject into a single {@code rdf:Description} placed
     * where that subject is first mentioned.
     */
    private static void writeStatements(String containerName, List<CgmesStatement> statements, String cimNamespace,
                                        XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement(DM_NAMESPACE, containerName);
        writer.writeAttribute(RDF_NAMESPACE, PARSE_TYPE, STATEMENTS);
        Map<String, List<CgmesStatement>> bySubject = new LinkedHashMap<>();
        for (CgmesStatement statement : statements) {
            bySubject.computeIfAbsent(statement.subjectId(), id -> new ArrayList<>()).add(statement);
        }
        for (Map.Entry<String, List<CgmesStatement>> entry : bySubject.entrySet()) {
            writer.writeStartElement(RDF_NAMESPACE, DESCRIPTION);
            writer.writeAttribute(RDF_NAMESPACE, CgmesNames.ABOUT, "#_" + entry.getKey());
            for (CgmesStatement statement : entry.getValue()) {
                writeStatement(statement, cimNamespace, writer);
            }
            writer.writeEndElement();
        }
        writer.writeEndElement();
    }

    private static void writeStatement(CgmesStatement statement, String cimNamespace, XMLStreamWriter writer) throws XMLStreamException {
        if (RDF_TYPE.equals(statement.property())) {
            // The type of the subject, which a parser produces for a typed node element and which the fast route
            // needs in the synthetic update document. It is written back as an explicit rdf:type property so that
            // parse -> write -> parse is stable whatever shape the producer chose.
            writer.writeEmptyElement(RDF_NAMESPACE, TYPE);
            writer.writeAttribute(RDF_NAMESPACE, CgmesNames.RESOURCE, cimNamespace + statement.value());
            return;
        }
        int split = namespaceEnd(statement.property());
        String namespace = split < 0 ? cimNamespace : statement.property().substring(0, split + 1);
        String localName = split < 0 ? statement.property() : statement.property().substring(split + 1);
        boolean foreign = split >= 0;
        switch (statement.kind()) {
            case ENUM -> {
                writeStatementStart(true, namespace, localName, foreign, writer);
                writer.writeAttribute(RDF_NAMESPACE, CgmesNames.RESOURCE, cimNamespace + statement.value());
            }
            case REFERENCE -> {
                writeStatementStart(true, namespace, localName, foreign, writer);
                writer.writeAttribute(RDF_NAMESPACE, CgmesNames.RESOURCE, "#_" + statement.value());
            }
            case LITERAL -> {
                writeStatementStart(false, namespace, localName, foreign, writer);
                writer.writeCharacters(statement.value());
                writer.writeEndElement();
            }
        }
    }

    /**
     * Where the namespace of a property IRI ends, or {@code -1} for a plain CIM property name.
     *
     * <p>A namespace ends in {@code #} or in {@code /} &mdash; both are legal &mdash; so the separator is whichever
     * of the two comes last. A CIM property is carried by its local name alone and holds neither.</p>
     */
    private static int namespaceEnd(String property) {
        return Math.max(property.lastIndexOf('#'), property.lastIndexOf('/'));
    }

    /**
     * Start a property element, declaring its namespace inline when the property lives outside the CIM namespace.
     *
     * <p>A property of a foreign namespace &mdash; an ENTSO-E extension, or anything a third party added &mdash; is
     * carried as an absolute IRI by {@link CgmesStatement#property()}. The prefix of such a namespace is not known
     * here, so it is declared on the property element itself, which is valid RDF/XML and keeps the root element of
     * the document independent of its content.</p>
     */
    private static void writeStatementStart(boolean empty, String namespace, String localName, boolean foreign,
                                            XMLStreamWriter writer) throws XMLStreamException {
        if (!foreign) {
            if (empty) {
                writer.writeEmptyElement(namespace, localName);
            } else {
                writer.writeStartElement(namespace, localName);
            }
            return;
        }
        if (empty) {
            writer.writeEmptyElement(FOREIGN_PREFIX, localName, namespace);
        } else {
            writer.writeStartElement(FOREIGN_PREFIX, localName, namespace);
        }
        writer.writeNamespace(FOREIGN_PREFIX, namespace);
    }
}
