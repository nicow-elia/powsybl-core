/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesModelException;
import com.powsybl.cgmes.model.CgmesModelReports;
import com.powsybl.cgmes.model.CgmesOnDataSource;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.triplestore.CgmesTripleStoreLoader;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;
import org.eclipse.rdf4j.rio.helpers.XMLParserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads the instance files of one timestep for the sole purpose of comparing them with a stored state.
 *
 * <p>An ingestion does not want a triple store. It wants, per profile, the model header and the statements grouped
 * by subject &mdash; which is what {@link TripleDiffCalculator.Index} is. Building a scratch
 * {@code MemoryStore} first means every statement is written into an indexed sail, queried back out and only then
 * decoded, and every profile the ingestion does not compare (topology, state variables, diagram layout, the
 * boundary) is parsed in full although nothing but its {@code md:FullModel} is ever read. On the CGMES 3 Svedala
 * model that is roughly a third of the steady state cost and, when a TSO ships its whole export per timestep,
 * about four hundred milliseconds of parsing thrown away per timestep.</p>
 *
 * <p>So the statements go straight from the RDF/XML parser into the index, and a profile that is not compared is
 * parsed only as far as its header. What comes out has to be <em>exactly</em> what the store path produced, and
 * three things are what make it so:</p>
 * <ul>
 *   <li><strong>The same parser.</strong> The three non-fatal settings {@code TripleStoreRDF4J.read} applies are
 *       applied here too, and the same base URI is passed, so identifiers resolve identically.</li>
 *   <li><strong>The same order.</strong> Files are read in the data source's iteration order, statements in
 *       document order &mdash; which is the order a freshly filled {@code MemoryStore} hands them back, and the
 *       order the difference is written in.</li>
 *   <li><strong>The same set semantics.</strong> A store holds a triple once however often a file states it, so a
 *       statement that is already under its key is dropped rather than indexed twice.</li>
 * </ul>
 *
 * <p>Nothing here writes; the result is read-only and the caller owns it.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class IngestParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestParser.class);

    /** The profiles whose statements an ingestion compares; everything else is read for its header alone. */
    private static final Set<CgmesSubset> COMPARED =
            Set.of(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS);

    private IngestParser() {
    }

    /**
     * One instance file, as far as it was read.
     *
     * @param name    the file name inside the data source
     * @param context the {@code contexts:}-prefixed graph name it would have had in a triple store
     * @param subset  the CGMES profile the file carries
     * @param headerId the identifier of its {@code md:FullModel}
     * @param terms   the {@code md:} terms of that header, in document order, {@code rdf:type} excluded
     * @param index   the statements of the file, header excluded, or {@code null} for a header-only read
     */
    record ParsedFile(String name, String context, CgmesSubset subset, String headerId,
                      Map<String, List<Value>> terms, TripleDiffCalculator.Index index) {
    }

    /**
     * What one timestep's files say.
     *
     * @param cimNamespace   the CIM namespace the files declare
     * @param baseName       the base URI relative identifiers were resolved against
     * @param files          the files, in the data source's iteration order
     */
    record Result(String cimNamespace, String baseName, List<ParsedFile> files) {

        /** @return the file of one profile, or {@code null} when the timestep does not ship it */
        ParsedFile of(CgmesSubset subset) {
            return files.stream().filter(file -> file.subset() == subset).findFirst().orElse(null);
        }
    }

    /**
     * Read the files of one timestep.
     *
     * <p>The discovery is the one {@code CgmesTripleStoreLoader} does &mdash; the data source's base name, its CIM
     * namespace, its file names in its own iteration order &mdash; and every file is reported before it is read,
     * so the report of an ingestion is the report of a load.</p>
     *
     * @param main       the data source holding the instance files of the timestep
     * @param boundary   the data source to take the boundary from when the main one carries none, or {@code null}
     * @param reportNode where the reader reports the files it read
     * @param unchanged  per profile, the identifier of the model the database holds as that profile's state; a
     *                   compared file whose header names <em>its own profile's</em> identifier is read for its
     *                   header alone, because it is the state it would be compared against. The gate is the
     *                   identifier only: a re-used identifier with changed content is not detected
     * @return the headers of every file and the index of every compared profile
     * @throws CgmesModelException if a file cannot be read, naming the file
     */
    static Result read(ReadOnlyDataSource main, ReadOnlyDataSource boundary, ReportNode reportNode,
                       Map<CgmesSubset, String> unchanged) {
        Objects.requireNonNull(main);
        Objects.requireNonNull(reportNode);
        CgmesOnDataSource cds = new CgmesOnDataSource(main);
        String baseName = cds.baseName();
        String cimNamespace = cds.cimNamespace();
        String subjectBase = ModelCatalog.subjectBaseOf(baseName);

        List<ParsedFile> files =
                new ArrayList<>(readAll(cds, baseName, subjectBase, cimNamespace, reportNode, unchanged));
        if (boundary != null && !hasBoundary(files)) {
            LOGGER.debug("The files carry no boundary: reading the headers of the boundary data source");
            files.addAll(readAll(new CgmesOnDataSource(boundary), baseName, subjectBase, cimNamespace, reportNode,
                    unchanged));
        }
        return new Result(cimNamespace, baseName, List.copyOf(files));
    }

    /**
     * Whether the files already carry the boundary, by the rule {@code CgmesModelTripleStore.hasBoundary} uses:
     * one model declaring an equipment boundary profile and one declaring a topology boundary profile.
     */
    private static boolean hasBoundary(List<ParsedFile> files) {
        boolean equipment = false;
        boolean topology = false;
        for (ParsedFile file : files) {
            for (Value profile : file.terms().getOrDefault(RdfDbVocabulary.MODEL_PROFILE, List.of())) {
                String text = profile.stringValue();
                equipment |= text.contains("/EquipmentBoundary/");
                topology |= text.contains("/TopologyBoundary/");
            }
        }
        return equipment && topology;
    }

    private static List<ParsedFile> readAll(CgmesOnDataSource cds, String baseName, String subjectBase,
                                            String cimNamespace, ReportNode reportNode,
                                            Map<CgmesSubset, String> unchanged) {
        // Deliberately the data source's iteration order: it decides the order the profiles are compared in and
        // therefore the order of the snapshot's members, exactly as it does for a load
        List<String> names = new ArrayList<>(cds.names());
        names.forEach(name -> CgmesModelReports.readFile(reportNode, name));
        List<ParsedFile> files = new ArrayList<>(names.size());
        for (String name : names) {
            files.add(readOne(cds.dataSource(), baseName, subjectBase, cimNamespace, name, unchanged));
        }
        return files;
    }

    private static ParsedFile readOne(ReadOnlyDataSource ds, String baseName, String subjectBase,
                                      String cimNamespace, String name, Map<CgmesSubset, String> unchanged) {
        String context = CgmesTripleStoreLoader.contextName(name);
        CgmesSubset subset = GraphInfo.subsetOf(context);
        boolean compared = COMPARED.contains(subset);
        Handler handler = new Handler(subjectBase, cimNamespace, compared, compared ? unchanged.get(subset) : null);
        RDFParser parser = parser();
        parser.setRDFHandler(handler);
        try (InputStream is = ds.newInputStream(name)) {
            parser.parse(is, baseName);
        } catch (StopAfterHeader stop) {
            // The header is all this file is read for
        } catch (Exception e) {
            throw new CgmesModelException("Reading [" + name + "]", e);
        }
        LOGGER.debug("Read [{}]{}", name, handler.indexed() ? "" : " (header only)");
        return new ParsedFile(name, context, subset, handler.headerId(), handler.headerTerms(),
                handler.indexed() ? handler.index() : null);
    }

    /**
     * A parser that accepts what {@code TripleStoreRDF4J.read} accepts and produces the same statements; it only
     * skips the IRI syntax check that the triple store runs for a diagnostic nobody listens to.
     *
     * <p>The three tolerated errors are what a CGMES export in the field actually contains, and a parse that is
     * stricter here than the one behind the triple store would refuse files the database already holds.</p>
     */
    private static RDFParser parser() {
        RDFParser parser = Rio.createParser(RDFFormat.RDFXML);
        parser.getParserConfig().addNonFatalError(XMLParserSettings.FAIL_ON_INVALID_NCNAME);
        // Off rather than non-fatal: as a non-fatal error the IRI was verified (a ParsedIRI per IRI, ~22 % of the
        // parse) and then created unchanged anyway, so the only difference is that a malformed IRI is no longer
        // reported to a ParseErrorListener, of which there is none (IngestParserTest.aMalformedIriIsIndexedAsWritten)
        parser.getParserConfig().set(BasicParserSettings.VERIFY_URI_SYNTAX, false);
        parser.getParserConfig().addNonFatalError(XMLParserSettings.FAIL_ON_DUPLICATE_RDF_ID);
        return parser;
    }

    /** How a header-only read stops the parser; stackless, because it is control flow and not a failure. */
    private static final class StopAfterHeader extends RuntimeException {
        StopAfterHeader() {
            super(null, null, false, false);
        }
    }

    /**
     * Turns the statements of one file into a header and an index as they arrive.
     *
     * <p>The {@code md:FullModel} is recognised by its type statement, which RDF/XML emits first for a subject
     * because the type is the element name. Its statements go to the header and not into the index &mdash; the
     * header always differs between two exports and is not part of the grid model. A file may declare more than
     * one; the smallest identifier wins, which is the rule the query this replaces applied through its
     * {@code TreeSet}.</p>
     *
     * <p>A header-only read stops at the first statement of the grid model, which assumes what every CGMES
     * export does and the profile specifications require: the {@code md:FullModel} element comes before the
     * objects it describes. A file that buried its header in the middle would be read as far as its first
     * object and its header taken from what precedes that &mdash; which is why only the profiles the ingestion
     * <em>inherits</em> are read that way, never one whose statements are compared.</p>
     */
    private static final class Handler extends AbstractRDFHandler {

        private final String subjectBase;
        private final String cimNamespace;
        private final boolean compared;
        private final String unchangedId;
        private Boolean indexing;
        private final Set<String> headerSubjects = new TreeSet<>();
        private final Map<String, Map<String, List<Value>>> headerNodes = new LinkedHashMap<>();
        private final Map<String, Map<String, List<CgmesStatement>>> bySubject = new LinkedHashMap<>();

        Handler(String subjectBase, String cimNamespace, boolean compared, String unchangedId) {
            this.subjectBase = subjectBase;
            this.cimNamespace = cimNamespace;
            this.compared = compared;
            this.unchangedId = unchangedId;
        }

        @Override
        public void handleStatement(Statement statement) {
            String subject = statement.getSubject().stringValue();
            String predicate = statement.getPredicate().stringValue();
            if (RdfDbVocabulary.RDF_TYPE.equals(predicate)
                    && RdfDbVocabulary.FULL_MODEL.equals(statement.getObject().stringValue())) {
                headerSubjects.add(subject);
                headerNodes.computeIfAbsent(subject, k -> new LinkedHashMap<>());
                return;
            }
            if (predicate.startsWith(RdfDbVocabulary.MD_NS)) {
                // Kept whatever the subject turns out to be, so that a header property written before its
                // rdf:type still counts; distinct, as the query over a store this replaces saw them
                List<Value> values = headerNodes.computeIfAbsent(subject, k -> new LinkedHashMap<>())
                        .computeIfAbsent(predicate, k -> new ArrayList<>());
                if (!values.contains(statement.getObject())) {
                    values.add(statement.getObject());
                }
            }
            if (headerSubjects.contains(subject)) {
                return;
            }
            if (indexing == null) {
                // Decided once, at the first statement of the grid model. Normally the header is complete by
                // then; a file whose header comes later cannot be known to be unchanged, so it is read in full
                String id = headerId();
                indexing = compared && (id == null || !id.equals(unchangedId));
            }
            if (!indexing) {
                // Everything this file is read for has been read: the first statement of the grid model ends it
                if (headerSubjects.isEmpty()) {
                    return;
                }
                throw new StopAfterHeader();
            }
            CgmesStatement decoded = StatementCodec.decode(statement.getSubject(), statement.getPredicate(),
                    statement.getObject(), subjectBase, cimNamespace);
            List<CgmesStatement> values = bySubject.computeIfAbsent(decoded.subjectId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(decoded.property(), k -> new ArrayList<>());
            // A triple store holds a triple once however often the file states it, and the comparison has always
            // seen a store; a file that repeats a statement must not produce it twice here either
            if (!values.contains(decoded)) {
                values.add(decoded);
            }
        }

        /** Whether this file was read in full; a file with a header and nothing else counts as read in full. */
        boolean indexed() {
            return indexing == null ? compared : indexing;
        }

        String headerId() {
            return headerSubjects.isEmpty() ? null : headerSubjects.iterator().next();
        }

        /** The {@code md:} terms of the winning header, in document order. */
        Map<String, List<Value>> headerTerms() {
            String id = headerId();
            if (id == null) {
                return Map.of();
            }
            Map<String, List<Value>> terms = new LinkedHashMap<>();
            headerNodes.getOrDefault(id, Map.of())
                    .forEach((predicate, values) -> terms.put(predicate, List.copyOf(values)));
            return Collections.unmodifiableMap(terms);
        }

        /**
         * The index, with every header subject taken back out.
         *
         * <p>A header property that a file writes before the {@code rdf:type} it belongs to was indexed as an
         * ordinary statement; removing the header subjects at the end excludes it, which is what the two-pass
         * index over a store did as well.</p>
         */
        TripleDiffCalculator.Index index() {
            Set<String> localHeaders = new LinkedHashSet<>();
            String prefix = subjectBase == null || subjectBase.isEmpty() ? null : subjectBase + "_";
            for (String header : headerSubjects) {
                localHeaders.add(prefix != null && header.startsWith(prefix)
                        ? header.substring(prefix.length()) : header);
            }
            localHeaders.forEach(bySubject::remove);
            return TripleDiffCalculator.readOnly(bySubject);
        }
    }
}
