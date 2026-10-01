/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.StatementDiff;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The difference between two graphs of the same CGMES profile, computed from the triples alone.
 *
 * <p>This is what turns a day's CGMES export into a version of a scenario: nobody recorded those changes on a
 * network, so the only way to say what moved between the parent state and the new file is to compare the two
 * graphs. The result is an ordinary {@code DifferenceModel} &mdash; forward statements are the state after, reverse
 * statements the state before &mdash; so everything downstream (the sink, the fast-route check, the importer, the
 * store-level applier) treats it exactly like a recorded one.</p>
 *
 * <p>This class reads the triples: the {@code md:FullModel} header is excluded on both sides (it always differs and
 * is not part of the model), a statement is keyed by {@code (local subject, property)} with the subject base of its
 * own side stripped, so the two sides compare even when their documents resolved {@code rdf:ID} against different
 * bases, a reference becomes the local identifier it points at and an enumeration its local name. The comparison
 * itself &mdash; numeric literals by value, multi-valued keys as sets, types as statements, deterministic order
 * &mdash; is {@link StatementDiff}, shared with every other producer of a difference without recorded changes.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class TripleDiffCalculator {

    private TripleDiffCalculator() {
    }

    /**
     * The difference from one graph to another.
     *
     * @param parentGraph       the statements of the state the difference applies on
     * @param parentSubjectBase the IRI prefix the parent's subjects carry before {@code _<mRID>}
     * @param nextGraph         the statements of the state the difference produces
     * @param nextSubjectBase   the IRI prefix the new side's subjects carry
     * @param cimNamespace      the CIM namespace both sides are written in
     * @param header            the header the difference model gets
     * @return the difference; {@link DifferenceModel#isEmpty()} when the two graphs say the same thing
     */
    static DifferenceModel diff(Collection<Statement> parentGraph, String parentSubjectBase,
                                Collection<Statement> nextGraph, String nextSubjectBase, String cimNamespace,
                                DifferenceModelHeader header) {
        return diff(index(parentGraph, parentSubjectBase, cimNamespace),
                index(nextGraph, nextSubjectBase, cimNamespace), header);
    }

    /**
     * The difference between two graphs that are already indexed.
     *
     * <p>The same comparison as above, entered one step later. It exists because the parent side of a day of
     * timesteps is the <em>same state</em> ninety-five times over: indexing it once and keeping the
     * {@link StatementDiff.Index} is what an ingestion does instead of materialising and decoding it per timestep.
     * The comparison itself is {@link StatementDiff#diff}, shared with every other producer of a difference that has
     * no recorded changes.</p>
     *
     * @param parent the index of the state the difference applies on
     * @param next   the index of the state the difference produces
     * @param header the header the difference model gets
     * @return the difference; {@link DifferenceModel#isEmpty()} when the two graphs say the same thing
     */
    static DifferenceModel diff(StatementDiff.Index parent, StatementDiff.Index next, DifferenceModelHeader header) {
        return StatementDiff.diff(parent, next, header);
    }

    /**
     * The statements of one graph, by subject and property, with the model header left out.
     *
     * @param graph        the statements of one profile, in document order
     * @param subjectBase  the IRI prefix the subjects of that graph carry before {@code _<mRID>}
     * @param cimNamespace the CIM namespace the properties are written in
     * @return the index; read-only
     */
    static StatementDiff.Index index(Collection<Statement> graph, String subjectBase, String cimNamespace) {
        Set<String> headers = headerSubjects(graph);
        Map<String, Map<String, List<CgmesStatement>>> bySubject = new LinkedHashMap<>();
        for (Statement statement : graph) {
            if (headers.contains(statement.getSubject().stringValue())) {
                continue;
            }
            CgmesStatement decoded = StatementCodec.decode(statement.getSubject(), statement.getPredicate(),
                    statement.getObject(), subjectBase, cimNamespace);
            bySubject.computeIfAbsent(decoded.subjectId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(decoded.property(), k -> new ArrayList<>())
                    .add(decoded);
        }
        return StatementDiff.readOnly(bySubject);
    }

    /** The subjects of the {@code md:FullModel} header, which is about the document rather than about the grid. */
    private static Set<String> headerSubjects(Collection<Statement> graph) {
        Set<String> headers = new LinkedHashSet<>();
        for (Statement statement : graph) {
            Value object = statement.getObject();
            if (RdfDbVocabulary.RDF_TYPE.equals(statement.getPredicate().stringValue())
                    && RdfDbVocabulary.FULL_MODEL.equals(object.stringValue())) {
                headers.add(statement.getSubject().stringValue());
            }
        }
        return headers;
    }
}
