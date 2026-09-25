/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.powsybl.cgmes.model.CgmesNamespace.RDF_NAMESPACE;

/**
 * The CIM properties that a change export has to write, buffered per CGMES object and per profile.
 *
 * <p>The mapping between IIDM changes and CGMES properties is not one to one: several IIDM changes can describe
 * the same CGMES object (the active and the reactive setpoint of a load), and several IIDM objects can describe a
 * single CGMES object (the voltage setpoint of a generator and of a shunt compensator both describe a
 * RegulatingControl). Buffering the properties per master resource identifier guarantees that every object is
 * described exactly once and that its description is internally consistent, whatever the order and the number of
 * the recorded changes.</p>
 *
 * <p>Objects are written in the order in which they were first updated, and the properties of an object in the
 * order in which they were first set, so that the export is reproducible.</p>
 *
 * <p>A buffer is keyed by profile as well, because a single change may describe properties of several CGMES
 * profiles and a document holds one profile. Every method that does not name a profile means the steady state
 * hypothesis, which is the only one a partial SSH export writes and the only one the mappings of this release
 * produce.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesPropertyBuffer {

    private final Map<ObjectKey, ObjectUpdate> updatesByObject = new LinkedHashMap<>();

    /** What identifies a described CGMES object: the profile the description belongs to and the object itself. */
    private record ObjectKey(CgmesSubset subset, String masterResourceId) {
    }

    /**
     * Return the steady state hypothesis description of the CGMES object with the given master resource identifier,
     * creating an empty one if this object has not been updated yet.
     *
     * @param className the CIM class of the object, used as the element name
     * @param masterResourceId the CGMES master resource identifier (mRID) of the object
     */
    ObjectUpdate object(String className, String masterResourceId) {
        return object(CgmesSubset.STEADY_STATE_HYPOTHESIS, className, masterResourceId);
    }

    /**
     * Return the description of the CGMES object with the given master resource identifier in the given profile,
     * creating an empty one if this object has not been updated there yet.
     */
    ObjectUpdate object(CgmesSubset subset, String className, String masterResourceId) {
        return updatesByObject.computeIfAbsent(new ObjectKey(subset, masterResourceId),
                key -> new ObjectUpdate(this, subset, className, key.masterResourceId()));
    }

    /**
     * Start describing a single CGMES object in a buffer of its own, for instance
     * {@code newUpdates(CgmesNames.TERMINAL, terminalId).value("ACDCTerminal.connected", true).updates()}.
     *
     * <p>This is the entry point of the buffers built per change: a mapping describes the objects a change
     * affects and returns the buffer, which the caller merges, or drops if the change turns out to be
     * unsupported.</p>
     */
    static ObjectUpdate newUpdates(String className, String masterResourceId) {
        return new CgmesPropertyBuffer().object(className, masterResourceId);
    }

    /** As {@link #newUpdates(String, String)}, for an object described in another profile. */
    static ObjectUpdate newUpdates(CgmesSubset subset, String className, String masterResourceId) {
        return new CgmesPropertyBuffer().object(subset, className, masterResourceId);
    }

    /** Return a new buffer holding every property of the given buffers, merged in the order they are given. */
    static CgmesPropertyBuffer merge(CgmesPropertyBuffer... parts) {
        CgmesPropertyBuffer merged = new CgmesPropertyBuffer();
        for (CgmesPropertyBuffer part : parts) {
            merged.mergeFrom(part);
        }
        return merged;
    }

    boolean isEmpty() {
        return updatesByObject.isEmpty();
    }

    /** The profiles this buffer describes objects of, in the order in which they were first described. */
    Set<CgmesSubset> subsets() {
        Set<CgmesSubset> subsets = new LinkedHashSet<>();
        updatesByObject.keySet().forEach(key -> subsets.add(key.subset()));
        return subsets;
    }

    /**
     * Copy every property of the given buffer into this one, as if its objects had been updated here.
     *
     * <p>This is how a change is committed once it is known to be entirely exportable: it is collected into a
     * buffer of its own and merged here only then, so that a change which turns out to be unsupported halfway
     * through leaves nothing behind. Objects and properties this buffer already holds keep their position, and a
     * property set on both sides takes the merged value, exactly as a second direct update would have done, so
     * merging change by change writes the same file as updating this buffer directly would have.</p>
     */
    void mergeFrom(CgmesPropertyBuffer other) {
        other.updatesByObject.forEach((key, objectUpdate) ->
                object(key.subset(), objectUpdate.className, key.masterResourceId()).properties.putAll(objectUpdate.properties));
    }

    /**
     * The buffered properties of one profile as RDF statements, in the order they were buffered.
     *
     * <p>This is the form the difference model export works with: the identifiers are resolved exactly as the XML
     * writer below resolves them, so a statement and the element a partial SSH file would hold for it describe the
     * same triple.</p>
     */
    List<CgmesStatement> statements(CgmesSubset subset, CgmesExportContext context) {
        List<CgmesStatement> statements = new ArrayList<>();
        updatesByObject.forEach((key, objectUpdate) -> {
            if (key.subset() == subset) {
                objectUpdate.addStatements(statements, context);
            }
        });
        return statements;
    }

    /**
     * Write the steady state hypothesis objects of this buffer, each as one typed element carrying an
     * {@code rdf:about}.
     */
    void write(String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        for (Map.Entry<ObjectKey, ObjectUpdate> entry : updatesByObject.entrySet()) {
            if (entry.getKey().subset() == CgmesSubset.STEADY_STATE_HYPOTHESIS) {
                entry.getValue().write(cimNamespace, writer, context);
            }
        }
    }

    /**
     * The properties to write for a single CGMES object. Setting a property that has already been set replaces its
     * value, keeping its original position.
     */
    static final class ObjectUpdate {

        private final CgmesPropertyBuffer buffer;
        private final CgmesSubset subset;
        private final String className;
        private final String masterResourceId;
        private final Map<String, Property> properties = new LinkedHashMap<>();

        private ObjectUpdate(CgmesPropertyBuffer buffer, CgmesSubset subset, String className, String masterResourceId) {
            this.buffer = buffer;
            this.subset = subset;
            this.className = className;
            this.masterResourceId = masterResourceId;
        }

        /** Move on to another object of the same buffer, so that a change affecting several objects reads as one chain. */
        ObjectUpdate object(String className, String masterResourceId) {
            return buffer.object(subset, className, masterResourceId);
        }

        /** Move on to an object of another profile of the same buffer. */
        ObjectUpdate object(CgmesSubset subset, String className, String masterResourceId) {
            return buffer.object(subset, className, masterResourceId);
        }

        /** The buffer this object belongs to, which is what a mapping returns once it has described everything. */
        CgmesPropertyBuffer updates() {
            return buffer;
        }

        ObjectUpdate value(String property, boolean value) {
            return literal(property, CgmesExportUtil.format(value));
        }

        ObjectUpdate value(String property, int value) {
            return literal(property, CgmesExportUtil.format(value));
        }

        ObjectUpdate value(String property, double value) {
            return literal(property, CgmesExportUtil.format(value));
        }

        /**
         * Set a property pointing to a CIM enumeration literal, for instance
         * {@code enumValue("VsConverter.qPccControl", "VsQpccControlKind", "voltagePcc")}.
         */
        ObjectUpdate enumValue(String property, String enumerationName, String literal) {
            properties.put(property, new Property(enumerationName + "." + literal, true));
            return this;
        }

        /**
         * Set a property to a lexical value the caller has already formatted.
         *
         * <p>Used where the shared {@link CgmesExportUtil#format(double)} is not the right formatter, that is for
         * impedances, whose magnitude may be far below the fourteen decimals it keeps: see
         * {@link CgmesExportUtil#formatExact(double)}.</p>
         */
        ObjectUpdate rawLiteral(String property, String lexicalValue) {
            return literal(property, lexicalValue);
        }

        private ObjectUpdate literal(String property, String value) {
            properties.put(property, new Property(value, false));
            return this;
        }

        private void addStatements(List<CgmesStatement> statements, CgmesExportContext context) {
            String subjectId = context.encode(masterResourceId.startsWith("_")
                    ? masterResourceId.substring(1) : masterResourceId);
            properties.forEach((property, value) -> statements.add(value.enumeration()
                    ? CgmesStatement.enumeration(subjectId, className, property, value.value())
                    : CgmesStatement.literal(subjectId, className, property, value.value())));
        }

        private void write(String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
            CgmesExportUtil.writeStartAbout(className, masterResourceId, cimNamespace, writer, context);
            for (Map.Entry<String, Property> entry : properties.entrySet()) {
                Property property = entry.getValue();
                if (property.enumeration()) {
                    writer.writeEmptyElement(cimNamespace, entry.getKey());
                    writer.writeAttribute(RDF_NAMESPACE, CgmesNames.RESOURCE, cimNamespace + property.value());
                } else {
                    writer.writeStartElement(cimNamespace, entry.getKey());
                    writer.writeCharacters(property.value());
                    writer.writeEndElement();
                }
            }
            writer.writeEndElement();
        }
    }

    private record Property(String value, boolean enumeration) {
    }
}
