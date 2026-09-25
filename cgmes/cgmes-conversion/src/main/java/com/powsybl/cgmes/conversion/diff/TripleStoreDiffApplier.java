/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.model.CgmesModelException;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.triplestore.api.TripleStore;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Applies a difference model to RDF data rather than to a network, through SPARQL UPDATE.
 *
 * <p>This is the slow route building block. It replaces the value of every stated {@code (subject, property)} inside
 * one named graph: the existing values are deleted, the stated ones inserted. Everything goes through
 * {@link TripleStore#update(String)}, so the same code changes the in-memory store of a loaded model and a remote
 * repository reached over SPARQL, which is what the RDF database integration needs.</p>
 *
 * <p>Type statements map onto {@code rdf:type}, so a difference that adds or removes a whole object works as long as
 * its reverse direction lists every triple of the removed object.</p>
 *
 * <p><b>Limitation:</b> properties are treated as single valued, exactly as {@link CgmesStatement} assumes. A
 * property that legitimately holds several values in the target graph loses all but the stated one.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class TripleStoreDiffApplier {

    /** How many keys one DELETE/INSERT request carries, so that a huge difference does not build one huge query. */
    private static final int BATCH_SIZE = 1000;

    private static final String RDF_TYPE_IRI = "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>";

    /** Characters that cannot appear inside a SPARQL IRIREF. */
    private static final String ILLEGAL_IRI_CHARACTERS = "<>\"{}|^`\\ ";

    private TripleStoreDiffApplier() {
    }

    /**
     * The single context of the store that carries the profile of the given difference.
     *
     * @throws CgmesModelException if none or several of them do
     */
    static String contextOf(TripleStore store, DifferenceModel diff) {
        CgmesSubset subset = diff.header().subset();
        List<String> candidates = store.contextNames().stream().filter(subset::isValidName).toList();
        if (candidates.size() != 1) {
            throw new CgmesModelException("The triple store holds " + candidates.size() + " graphs of the "
                    + subset.getIdentifier() + " profile " + candidates + ", name the one to change explicitly");
        }
        return candidates.get(0);
    }

    static void apply(TripleStore store, DifferenceModel diff, String contextName, String baseName) {
        Objects.requireNonNull(contextName);
        Objects.requireNonNull(baseName);
        apply(store, diff, graph(contextName), id -> subjectIri(id, baseName));
    }

    /**
     * Apply a difference inside a named graph whose IRI is given as it is, forming subject IRIs from the prefix
     * their identifiers carry.
     *
     * <p>The difference to {@link #apply(TripleStore, DifferenceModel, String, String)} is what the two string
     * arguments mean. There, they are a powsybl context name and the base IRI a document was parsed against, and
     * this class derives {@code base + "#_" + id} from them. Here the graph IRI is written verbatim &mdash; a
     * database names its graphs, not powsybl &mdash; and {@code subjectBase} is the complete prefix an identifier
     * is appended to, which a store of versioned models keeps alongside the model.</p>
     *
     * <p>An identifier that is already absolute ({@code urn:}, {@code http:}, {@code https:}) is used as it is, so
     * that a difference about a model header or about an object of a foreign namespace can be expressed at all.</p>
     */
    static void applyToGraph(TripleStore store, DifferenceModel diff, String graphIri, String subjectBase) {
        Objects.requireNonNull(graphIri);
        Objects.requireNonNull(subjectBase);
        apply(store, diff, checkIri(graphIri), id -> basedSubjectIri(id, subjectBase));
    }

    private static void apply(TripleStore store, DifferenceModel diff, String graphIri,
                              UnaryOperator<String> subjectIri) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(diff);

        String cimNamespace = diff.header().cimNamespace();
        Set<CgmesStatement.Key> keys = new LinkedHashSet<>();
        diff.reverse().forEach(statement -> keys.add(statement.key()));
        diff.forward().forEach(statement -> keys.add(statement.key()));
        if (keys.isEmpty()) {
            return;
        }
        List<CgmesStatement.Key> keyList = new ArrayList<>(keys);
        for (int from = 0; from < keyList.size(); from += BATCH_SIZE) {
            List<CgmesStatement.Key> batch = keyList.subList(from, Math.min(from + BATCH_SIZE, keyList.size()));
            store.update(deleteQuery(batch, graphIri, subjectIri, cimNamespace));
        }
        List<CgmesStatement> forward = diff.forward();
        for (int from = 0; from < forward.size(); from += BATCH_SIZE) {
            List<CgmesStatement> batch = forward.subList(from, Math.min(from + BATCH_SIZE, forward.size()));
            store.update(insertQuery(batch, graphIri, subjectIri, cimNamespace));
        }
    }

    private static String deleteQuery(List<CgmesStatement.Key> keys, String graphIri,
                                      UnaryOperator<String> subjectIri, String cimNamespace) {
        StringBuilder query = new StringBuilder("DELETE { GRAPH <").append(graphIri)
                .append("> { ?s ?p ?old } } WHERE { VALUES (?s ?p) {");
        for (CgmesStatement.Key key : keys) {
            query.append(" (").append(subjectIri.apply(key.subjectId())).append(' ')
                    .append(propertyIri(key.property(), cimNamespace)).append(')');
        }
        query.append(" } GRAPH <").append(graphIri).append("> { ?s ?p ?old } }");
        return query.toString();
    }

    private static String insertQuery(List<CgmesStatement> statements, String graphIri,
                                      UnaryOperator<String> subjectIri, String cimNamespace) {
        StringBuilder query = new StringBuilder("INSERT DATA { GRAPH <").append(graphIri).append("> {");
        for (CgmesStatement statement : statements) {
            query.append(' ').append(subjectIri.apply(statement.subjectId())).append(' ')
                    .append(propertyIri(statement.property(), cimNamespace)).append(' ')
                    .append(value(statement, subjectIri, cimNamespace)).append(" .");
        }
        query.append(" } }");
        return query.toString();
    }

    private static String value(CgmesStatement statement, UnaryOperator<String> subjectIri, String cimNamespace) {
        if (statement.isType()) {
            // The value of a type statement is a CIM class, not an object of the model: it belongs in the CIM
            // namespace, not under the subject prefix. Writing it as a reference would produce a graph whose
            // objects have no class the conversion recognises
            return "<" + checkIri(isAbsolute(statement.value())
                    ? statement.value() : cimNamespace + statement.value()) + ">";
        }
        return switch (statement.kind()) {
            case ENUM -> "<" + checkIri(cimNamespace + statement.value()) + ">";
            case REFERENCE -> subjectIri.apply(statement.value());
            case LITERAL -> "\"" + escape(statement.value()) + "\"";
        };
    }

    /** The IRI of a subject whose prefix is known, see {@link #applyToGraph}. */
    private static String basedSubjectIri(String id, String subjectBase) {
        if (isAbsolute(id)) {
            return "<" + checkIri(id) + ">";
        }
        if (subjectBase.isEmpty()) {
            // Appending to nothing would produce a relative reference, which a store resolves against a base this
            // code does not know: the update would quietly change something else, or nothing. The models whose
            // subjects are all absolute are recorded with an empty subject base, and a difference on one of them
            // states absolute identifiers only
            throw new CgmesModelException("The identifier \"" + id + "\" is not absolute and the model it belongs"
                    + " to carries no subject base, so it cannot be written as an RDF resource");
        }
        return "<" + checkIri(subjectBase + "_" + id) + ">";
    }

    /** Whether an identifier is already an absolute IRI rather than a master resource identifier. */
    static boolean isAbsolute(String id) {
        return id.startsWith("urn:") || id.startsWith("http://") || id.startsWith("https://");
    }

    private static String graph(String contextName) {
        return checkIri(contextName.startsWith("contexts:") || contextName.contains("://")
                ? contextName : "contexts:" + contextName);
    }

    /**
     * The IRI of a subject inside the documents of the given base.
     *
     * <p>A CGMES instance file writes its objects as {@code rdf:ID="_<mRID>"}, which an RDF reader resolves against
     * the base IRI of the document. Resolving a fragment against a base whose path is empty adds the root path
     * first (RFC 3986 section 6.2.3), so {@code http://example} becomes {@code http://example/#_<mRID>}; getting
     * that wrong means a SPARQL update that quietly matches nothing.</p>
     */
    private static String subjectIri(String id, String baseName) {
        return "<" + checkIri(normalizedBase(baseName) + "#_" + id) + ">";
    }

    private static String normalizedBase(String baseName) {
        if (baseName.endsWith("#") || baseName.endsWith("/")) {
            return baseName.endsWith("#") ? baseName.substring(0, baseName.length() - 1) : baseName;
        }
        try {
            URI uri = new URI(baseName);
            return uri.getAuthority() != null && (uri.getPath() == null || uri.getPath().isEmpty())
                    ? baseName + "/" : baseName;
        } catch (URISyntaxException e) {
            throw new CgmesModelException("The base \"" + baseName + "\" is not a valid IRI", e);
        }
    }

    private static String propertyIri(String property, String cimNamespace) {
        if (CgmesStatement.RDF_TYPE.equals(property)) {
            return RDF_TYPE_IRI;
        }
        // A property of a foreign namespace is carried as an absolute IRI by the parser. Its namespace ends in
        // '#' or in '/', both legal; a CIM property name holds neither
        boolean foreign = property.indexOf('#') >= 0 || property.indexOf('/') >= 0;
        return "<" + checkIri(foreign ? property : cimNamespace + property) + ">";
    }

    private static String checkIri(String iri) {
        for (int i = 0; i < iri.length(); i++) {
            if (ILLEGAL_IRI_CHARACTERS.indexOf(iri.charAt(i)) >= 0 || iri.charAt(i) < 0x21) {
                throw new CgmesModelException("The identifier \"" + iri + "\" cannot be written as an RDF resource:"
                        + " it holds the character '" + iri.charAt(i) + "', which is illegal in an IRI");
            }
        }
        return iri;
    }

    private static String escape(String literal) {
        return literal.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
