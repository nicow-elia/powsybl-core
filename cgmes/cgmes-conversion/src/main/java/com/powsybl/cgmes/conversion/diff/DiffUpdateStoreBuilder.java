/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.diff.FastRoutePlan.PlannedModel;
import com.powsybl.cgmes.conversion.diff.FastRoutePlan.RegisteredIdentity;
import com.powsybl.cgmes.conversion.diff.FastRoutePlan.TypedObject;
import com.powsybl.cgmes.model.CgmesModel;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.triplestore.CgmesModelTripleStore;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.commons.xml.XmlUtil;
import com.powsybl.triplestore.api.TripleStoreFactory;
import com.powsybl.triplestore.api.TripleStoreOptions;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import static com.powsybl.cgmes.model.CgmesNamespace.MD_NAMESPACE;
import static com.powsybl.cgmes.model.CgmesNamespace.RDF_NAMESPACE;

/**
 * Turns a planned difference model update into the triple store the ordinary CGMES update workflow reads.
 *
 * <p>Instead of reaching into the triple store with RDF statements, this writes a small RDF/XML document that is
 * <em>exactly</em> the shape of a partial steady state hypothesis instance file: a {@code md:FullModel} header
 * followed by one typed element per changed object. That document goes through the same reader, the same SPARQL
 * queries and the same conversion code a partial file goes through, which is what makes a difference model update
 * and a partial file update the same thing by construction rather than by agreement.</p>
 *
 * <p>It also keeps this module free of a triple store implementation: {@code cgmes-conversion} has the rdf4j
 * implementation in test scope only, so production code has to stay on the {@code CgmesModel} API.</p>
 *
 * <p>The context name is fixed rather than derived from the model identifier: a CGMES profile is recognized in a
 * context name by a {@code _SSH_} like fragment, and a model identifier may well contain one by accident.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class DiffUpdateStoreBuilder {

    /** The base IRI of the synthetic documents. Subjects are written relative to it, as in any CGMES file. */
    static final String BASE_NAME = "http://powsybl.org/cgmes/difference-model";

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(ZoneOffset.UTC);

    private static final String FULL_MODEL = "FullModel";

    private DiffUpdateStoreBuilder() {
    }

    /** The name of the context the difference of one profile is loaded into. */
    static String contextName(CgmesSubset subset) {
        return "difference-model_" + subset.getIdentifier() + ".xml";
    }

    /**
     * A CGMES model holding one synthetic partial instance file per planned difference model.
     *
     * <p>The caller owns the result and has to close it.</p>
     */
    static CgmesModel build(FastRoutePlan plan, ReportNode reportNode) {
        Objects.requireNonNull(plan);
        String cimNamespace = plan.models().get(0).header().cimNamespace();
        CgmesModelTripleStore cgmes = new CgmesModelTripleStore(cimNamespace,
                TripleStoreFactory.create(TripleStoreFactory.DEFAULT_IMPLEMENTATION, new TripleStoreOptions()),
                Conversion.QUERY_CATALOG_NAME_UPDATE);
        for (PlannedModel model : plan.models()) {
            byte[] document = updateDocument(cimNamespace, model);
            cgmes.read(new ByteArrayInputStream(document), BASE_NAME,
                    contextName(model.header().subset()), reportNode);
        }
        return cgmes;
    }

    /**
     * The synthetic instance file of one planned difference model.
     *
     * <p>Package private so that a test can compare it with what the partial steady state hypothesis export writes
     * for the same change.</p>
     */
    static byte[] updateDocument(String cimNamespace, PlannedModel model) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            // Buffered UTF-8 under the StAX writer: over a bare OutputStream the JDK writer emits one byte per call.
            // The same bytes; the flush at the end pushes them through
            XMLStreamWriter writer = XmlUtil.initializeWriter(true, "    ",
                    new BufferedWriter(new OutputStreamWriter(bytes, StandardCharsets.UTF_8)));
            // The same root element a partial steady state hypothesis export writes, European extension prefix
            // included: the document is meant to be indistinguishable from one, and a property of that namespace
            // would otherwise have nowhere to be declared
            CgmesNamespace.Cim cim = cimOf(cimNamespace);
            if (cim != null) {
                writer.setPrefix(cim.getEuPrefix(), cim.getEuNamespace());
            }
            writer.setPrefix("rdf", RDF_NAMESPACE);
            writer.setPrefix("cim", cimNamespace);
            writer.setPrefix("md", MD_NAMESPACE);
            writer.writeStartElement(RDF_NAMESPACE, "RDF");
            if (cim != null) {
                writer.writeNamespace(cim.getEuPrefix(), cim.getEuNamespace());
            }
            writer.writeNamespace("rdf", RDF_NAMESPACE);
            writer.writeNamespace("cim", cimNamespace);
            writer.writeNamespace("md", MD_NAMESPACE);
            writeFullModel(model.identity(), writer);
            for (TypedObject object : model.objects()) {
                writeObject(object, cimNamespace, writer);
            }
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
        } catch (XMLStreamException e) {
            throw new UncheckedXmlStreamException(e);
        }
        return bytes.toByteArray();
    }

    /** The CIM version of a namespace, or {@code null} for a namespace this library does not know. */
    private static CgmesNamespace.Cim cimOf(String cimNamespace) {
        return CgmesNamespace.CIM_LIST.stream()
                .filter(cim -> cim.getNamespace().equals(cimNamespace))
                .findFirst().orElse(null);
    }

    /**
     * The model description the update reads back.
     *
     * <p>The {@code fullModels} query needs a modeling authority set and at least one profile, and
     * {@code modelDates} additionally needs both times, which is why the planned identity never leaves them out even
     * when the difference model header did.</p>
     */
    private static void writeFullModel(RegisteredIdentity identity, XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement(MD_NAMESPACE, FULL_MODEL);
        writer.writeAttribute(RDF_NAMESPACE, CgmesNames.ABOUT, identity.id());
        writeText(CgmesNames.SCENARIO_TIME, format(identity.scenarioTime()), writer);
        writeText(CgmesNames.CREATED, format(identity.created()), writer);
        if (identity.description() != null) {
            writeText(CgmesNames.DESCRIPTION, identity.description(), writer);
        }
        writeText(CgmesNames.VERSION, Integer.toString(identity.version()), writer);
        for (String dependentOn : identity.dependentOn()) {
            writeResource(CgmesNames.DEPENDENT_ON, dependentOn, writer);
        }
        for (String supersedes : identity.supersedes()) {
            writeResource(CgmesNames.SUPERSEDES, supersedes, writer);
        }
        for (String profile : identity.profiles()) {
            writeText(CgmesNames.PROFILE, profile, writer);
        }
        writeText(CgmesNames.MODELING_AUTHORITY_SET, identity.modelingAuthoritySet(), writer);
        writer.writeEndElement();
    }

    private static void writeObject(TypedObject object, String cimNamespace, XMLStreamWriter writer)
            throws XMLStreamException {
        writer.writeStartElement(cimNamespace, object.rdfType());
        writer.writeAttribute(RDF_NAMESPACE, CgmesNames.ABOUT, object.about());
        for (CgmesStatement statement : object.statements()) {
            switch (statement.kind()) {
                case ENUM -> {
                    writer.writeEmptyElement(cimNamespace, statement.property());
                    writer.writeAttribute(RDF_NAMESPACE, CgmesNames.RESOURCE, cimNamespace + statement.value());
                }
                case REFERENCE -> {
                    writer.writeEmptyElement(cimNamespace, statement.property());
                    writer.writeAttribute(RDF_NAMESPACE, CgmesNames.RESOURCE, "#_" + statement.value());
                }
                case LITERAL -> {
                    writer.writeStartElement(cimNamespace, statement.property());
                    writer.writeCharacters(statement.value());
                    writer.writeEndElement();
                }
            }
        }
        writer.writeEndElement();
    }

    private static String format(ZonedDateTime time) {
        return DATE_TIME_FORMATTER.format(time != null ? time : ZonedDateTime.now());
    }

    private static void writeText(String property, String value, XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement(MD_NAMESPACE, property);
        writer.writeCharacters(value);
        writer.writeEndElement();
    }

    private static void writeResource(String property, String value, XMLStreamWriter writer) throws XMLStreamException {
        writer.writeEmptyElement(MD_NAMESPACE, property);
        writer.writeAttribute(RDF_NAMESPACE, CgmesNames.RESOURCE, value);
    }

    /** The document of a planned model as text, for tests and debugging. */
    static String updateDocumentAsString(String cimNamespace, PlannedModel model) {
        return new String(updateDocument(cimNamespace, model), StandardCharsets.UTF_8);
    }
}
