/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;

/**
 * Turning Java values into SPARQL terms, in one place.
 *
 * <p>Every query of this package is built as text rather than through a query object model, because the text is
 * what a server logs and what a developer has to be able to paste into a SPARQL console when something goes wrong.
 * The price is that escaping has to be right, and this class is where that is decided.</p>
 *
 * <p>An identifier that cannot be written as an IRI is rejected here, naming itself, rather than producing a query
 * that a server refuses with a parse error twenty kilobytes further down.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class SparqlText {

    /** Characters that cannot appear inside a SPARQL IRIREF. */
    private static final String ILLEGAL_IRI_CHARACTERS = "<>\"{}|^`\\ ";

    private SparqlText() {
    }

    /** An IRI reference, checked. */
    static String iri(String value) {
        return "<" + checkIri(value) + ">";
    }

    /** The IRI unchanged, after checking that it can be written. */
    static String checkIri(String iri) {
        for (int i = 0; i < iri.length(); i++) {
            char c = iri.charAt(i);
            if (ILLEGAL_IRI_CHARACTERS.indexOf(c) >= 0 || c < 0x21) {
                throw new RdfDbException("The identifier \"" + iri + "\" cannot be written as an RDF resource: it"
                        + " holds the character '" + c + "', which is illegal in an IRI");
            }
        }
        return iri;
    }

    /**
     * Whether an IRI can appear as an {@code <…>} term in SPARQL text.
     *
     * <p>Not every named graph of a powsybl triple store can. The in-process backend keeps the graphs of a scenario
     * under the plain instance file name, and a CGMES file name is not IRI-safe &mdash; the CGMES 3 Svedala fixture
     * has a space in every one of them. A server backend never has the problem, because the graph IRIs it uses are
     * percent-encoded by {@code ScenarioGraphNames}. Where such a graph has to be named in a query, the name is
     * compared as a string instead, see {@link #graphSelector}.</p>
     *
     * @param iri the IRI to check
     * @return whether it can be written as an IRI reference
     */
    static boolean isWritableIri(String iri) {
        if (iri == null) {
            return false;
        }
        for (int i = 0; i < iri.length(); i++) {
            char c = iri.charAt(i);
            if (ILLEGAL_IRI_CHARACTERS.indexOf(c) >= 0 || c < 0x21) {
                return false;
            }
        }
        return true;
    }

    /**
     * A group pattern restricting a graph variable to the given named graphs.
     *
     * <p>{@code VALUES} where it can be used, which is an index lookup on every backend, and a string comparison of
     * the graph name where one of the graphs cannot be written as an IRI (see {@link #isWritableIri}). The second
     * form makes the engine walk the named graphs of the dataset, so it is deliberately the exception rather than
     * the rule; it is only ever reached on the in-process backend, whose datasets hold one scenario each.</p>
     *
     * @param variable  the name of the graph variable, without the {@code ?}
     * @param graphIris the graphs to restrict it to
     * @return the pattern, ending in a space
     */
    static String graphSelector(String variable, Collection<String> graphIris) {
        StringBuilder pattern = new StringBuilder();
        if (graphIris.stream().allMatch(SparqlText::isWritableIri)) {
            pattern.append("VALUES ?").append(variable).append(" {");
            graphIris.forEach(iri -> pattern.append(' ').append(iri(iri)));
            return pattern.append(" } ").toString();
        }
        pattern.append("FILTER(STR(?").append(variable).append(") IN (");
        boolean first = true;
        for (String iri : graphIris) {
            if (!first) {
                pattern.append(", ");
            }
            first = false;
            pattern.append(str(iri));
        }
        return pattern.append(")) ").toString();
    }

    /** A plain literal, escaped. */
    static String str(String value) {
        return "\"" + escape(value) + "\"";
    }

    /** An {@code xsd:dateTime} literal. */
    static String dateTime(ZonedDateTime value) {
        return "\"" + value.format(DateTimeFormatter.ISO_INSTANT) + "\"^^<" + RdfDbVocabulary.XSD_NS + "dateTime>";
    }

    /** An {@code xsd:integer} literal. */
    static String integer(long value) {
        return "\"" + value + "\"^^<" + RdfDbVocabulary.XSD_NS + "integer>";
    }

    /** An {@code xsd:boolean} literal. */
    static String bool(boolean value) {
        return "\"" + value + "\"^^<" + RdfDbVocabulary.XSD_NS + "boolean>";
    }

    /** The escaping an N-Triples style literal needs. */
    static String escape(String literal) {
        return literal.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
