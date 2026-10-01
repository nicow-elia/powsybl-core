/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import static com.powsybl.cgmes.model.CgmesNamespace.RDF_NAMESPACE;

/**
 * Where the steady state hypothesis of a CGMES object is described to, one object at a time, as an XML stream
 * writer takes elements: {@code startObject}, its properties, {@code endObject}.
 *
 * <p>The change mapping describes every object it knows through one function writing to a sink, and both exports read
 * that function: the full SSH export writes straight to its document ({@link Xml}), a change export collects the
 * properties in a {@link CgmesPropertyBuffer}, which merges the descriptions of a change set object by object. So a
 * property of an object is written by one line of code, whichever export asks for it.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
interface CgmesPropertySink {

    /** Start describing the steady state hypothesis of the CGMES object with the given class and identifier. */
    CgmesPropertySink startObject(String className, String masterResourceId);

    /** Set a property of the object being described to a lexical value the caller has already formatted. */
    CgmesPropertySink literal(String property, String lexicalValue);

    /** Set a property of the object being described to a CIM enumeration literal. */
    CgmesPropertySink enumValue(String property, String enumerationName, String literal);

    /** End the description of the object. */
    void endObject();

    default CgmesPropertySink value(String property, boolean value) {
        return literal(property, CgmesExportUtil.format(value));
    }

    default CgmesPropertySink value(String property, int value) {
        return literal(property, CgmesExportUtil.format(value));
    }

    default CgmesPropertySink value(String property, double value) {
        return literal(property, CgmesExportUtil.format(value));
    }

    /** The sink of a full export: every object is written as one typed element carrying an {@code rdf:about}. */
    record Xml(String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) implements CgmesPropertySink {

        @Override
        public CgmesPropertySink startObject(String className, String masterResourceId) {
            try {
                CgmesExportUtil.writeStartAbout(className, masterResourceId, cimNamespace, writer, context);
            } catch (XMLStreamException e) {
                throw new UncheckedXmlStreamException(e);
            }
            return this;
        }

        @Override
        public CgmesPropertySink literal(String property, String lexicalValue) {
            try {
                writer.writeStartElement(cimNamespace, property);
                writer.writeCharacters(lexicalValue);
                writer.writeEndElement();
            } catch (XMLStreamException e) {
                throw new UncheckedXmlStreamException(e);
            }
            return this;
        }

        @Override
        public CgmesPropertySink enumValue(String property, String enumerationName, String literal) {
            try {
                writer.writeEmptyElement(cimNamespace, property);
                writer.writeAttribute(RDF_NAMESPACE, CgmesNames.RESOURCE, cimNamespace + enumerationName + "." + literal);
            } catch (XMLStreamException e) {
                throw new UncheckedXmlStreamException(e);
            }
            return this;
        }

        @Override
        public void endObject() {
            try {
                writer.writeEndElement();
            } catch (XMLStreamException e) {
                throw new UncheckedXmlStreamException(e);
            }
        }
    }
}
