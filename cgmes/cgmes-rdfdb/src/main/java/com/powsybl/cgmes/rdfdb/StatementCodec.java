/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.diff.CgmesStatement;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;

/**
 * How a {@link CgmesStatement} becomes an RDF triple, and back.
 *
 * <p>A statement of the difference pipeline is deliberately not RDF: it names a subject by its master resource
 * identifier, a property by its local name, and says how the value is to be written rather than what it is. That
 * is the right currency for a CGMES document, and the wrong one for a triple store, which wants three IRIs or two
 * IRIs and a literal. This class is the translation, and it is written as one class on purpose: the sink and the
 * source have to be exact inverses, and the only way to be sure of that is to have one place where both
 * directions are decided next to each other.</p>
 *
 * <p>The two pieces of context a translation needs come from the model the difference applies on, and are stored
 * with it: the <strong>subject base</strong>, the IRI prefix an identifier is appended to, and the <strong>CIM
 * namespace</strong> of the properties. Both are properties of the full model at the bottom of the chain, so
 * every difference of a chain speaks the same RDF as the model it changes &mdash; which is what makes a query
 * over the base graph and a difference graph together see one subject rather than two.</p>
 *
 * <p>The round trip is exact in the sense the difference pipeline cares about: a decoded statement carries no CIM
 * class name, and {@link CgmesStatement#equals(Object)} ignores that by design, because a parsed statement never
 * has one either.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class StatementCodec {

    private StatementCodec() {
    }

    // ------------------------------------------------------------------ encoding

    /**
     * The IRI of a subject or of a referred object.
     *
     * <p>An identifier that is already absolute is used as it is. Everything else is a CGMES master resource
     * identifier and becomes {@code subjectBase + "_" + id}, which is exactly the IRI the RDF/XML reader produced
     * for {@code rdf:ID="_<id>"} in the instance file.</p>
     */
    static String subjectIri(String id, String subjectBase) {
        if (isAbsolute(id)) {
            return id;
        }
        if (subjectBase == null || subjectBase.isEmpty()) {
            throw new RdfDbException("The identifier \"" + id + "\" is not absolute and the model it belongs to"
                    + " carries no subject base, so it cannot be written as an RDF resource");
        }
        return subjectBase + "_" + id;
    }

    /** The IRI of a property. */
    static String predicateIri(String property, String cimNamespace) {
        if (CgmesStatement.RDF_TYPE.equals(property)) {
            return RdfDbVocabulary.RDF_TYPE;
        }
        // A property of a foreign namespace is carried as an absolute IRI; a CIM property name is a local name
        // such as "EnergyConsumer.p" and holds no colon
        return property.indexOf(':') >= 0 ? property : cimNamespace + property;
    }

    /** The object of a statement, as a SPARQL term. */
    static String objectTerm(CgmesStatement statement, String subjectBase, String cimNamespace) {
        if (statement.isType()) {
            return SparqlText.iri(typeIri(statement.value(), cimNamespace));
        }
        return switch (statement.kind()) {
            case ENUM -> SparqlText.iri(cimNamespace + statement.value());
            case REFERENCE -> SparqlText.iri(subjectIri(statement.value(), subjectBase));
            case LITERAL -> SparqlText.str(statement.value());
        };
    }

    private static String typeIri(String value, String cimNamespace) {
        return isAbsolute(value) ? value : cimNamespace + value;
    }

    /** One statement as the three SPARQL terms of a triple pattern, subject predicate object. */
    static String triple(CgmesStatement statement, String subjectBase, String cimNamespace) {
        return SparqlText.iri(subjectIri(statement.subjectId(), subjectBase)) + ' '
                + SparqlText.iri(predicateIri(statement.property(), cimNamespace)) + ' '
                + objectTerm(statement, subjectBase, cimNamespace) + " .";
    }

    /** One statement as an RDF4J statement, for the paths that write statements rather than query text. */
    static Statement toStatement(ValueFactory factory, CgmesStatement statement, String subjectBase,
                                 String cimNamespace) {
        IRI subject = factory.createIRI(SparqlText.checkIri(subjectIri(statement.subjectId(), subjectBase)));
        IRI predicate = factory.createIRI(SparqlText.checkIri(predicateIri(statement.property(), cimNamespace)));
        Value object;
        if (statement.isType()) {
            object = factory.createIRI(SparqlText.checkIri(typeIri(statement.value(), cimNamespace)));
        } else {
            object = switch (statement.kind()) {
                case ENUM -> factory.createIRI(SparqlText.checkIri(cimNamespace + statement.value()));
                case REFERENCE -> factory.createIRI(
                        SparqlText.checkIri(subjectIri(statement.value(), subjectBase)));
                case LITERAL -> factory.createLiteral(statement.value());
            };
        }
        return factory.createStatement(subject, predicate, object);
    }

    // ------------------------------------------------------------------ decoding

    /**
     * The statement an RDF triple of a difference graph stands for.
     *
     * @param subject      the subject of the triple
     * @param predicate    the predicate of the triple
     * @param object       the object of the triple
     * @param subjectBase  the subject base of the model the difference applies on
     * @param cimNamespace the CIM namespace of that model
     * @return the statement, with no CIM class name, which is what a parsed statement carries too
     */
    static CgmesStatement decode(Value subject, Value predicate, Value object, String subjectBase,
                                 String cimNamespace) {
        String subjectId = localId(subject.stringValue(), subjectBase);
        String predicateIri = predicate.stringValue();
        if (RdfDbVocabulary.RDF_TYPE.equals(predicateIri)) {
            String type = object.stringValue();
            String value = type.startsWith(cimNamespace) ? type.substring(cimNamespace.length()) : type;
            return CgmesStatement.reference(subjectId, null, CgmesStatement.RDF_TYPE, value);
        }
        String property = predicateIri.startsWith(cimNamespace)
                ? predicateIri.substring(cimNamespace.length()) : predicateIri;
        if (object instanceof IRI iri) {
            String value = iri.stringValue();
            String prefix = subjectBase == null || subjectBase.isEmpty() ? null : subjectBase + "_";
            if (prefix != null && value.startsWith(prefix)) {
                return CgmesStatement.reference(subjectId, null, property, value.substring(prefix.length()));
            }
            if (value.startsWith(cimNamespace)) {
                return CgmesStatement.enumeration(subjectId, null, property, value.substring(cimNamespace.length()));
            }
            return CgmesStatement.reference(subjectId, null, property, value);
        }
        // The datatype is dropped on purpose: a CGMES literal is written without one, and the lexical form is
        // what the difference pipeline compares
        return CgmesStatement.literal(subjectId, null, property, object.stringValue());
    }

    private static String localId(String iri, String subjectBase) {
        String prefix = subjectBase == null || subjectBase.isEmpty() ? null : subjectBase + "_";
        return prefix != null && iri.startsWith(prefix) ? iri.substring(prefix.length()) : iri;
    }

    /** Whether an identifier is already an absolute IRI rather than a master resource identifier. */
    static boolean isAbsolute(String id) {
        return id.startsWith("urn:") || id.startsWith("http://") || id.startsWith("https://");
    }
}
