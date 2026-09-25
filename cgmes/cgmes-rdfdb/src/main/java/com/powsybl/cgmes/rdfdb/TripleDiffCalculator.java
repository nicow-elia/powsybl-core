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
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
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
 * <h2>What is compared, and what is not</h2>
 * <ul>
 *   <li>The {@code md:FullModel} header is excluded on both sides. It always differs &mdash; new identifier, new
 *       creation time &mdash; and it is not part of the model: the header of the difference says what the new
 *       state is called.</li>
 *   <li>A statement is keyed by {@code (local subject, property)}, with the subject base of its own side stripped,
 *       so the two sides compare even when their documents resolved {@code rdf:ID} against different bases.</li>
 *   <li>Objects are normalised <em>for the comparison only</em>: a reference becomes the local identifier it
 *       points at, an enumeration its local name, and two literals that both parse as {@code double} compare by
 *       value. The last one is what keeps a re-export from producing a difference where nothing changed:
 *       {@code 10}, {@code 10.0} and {@code 1e1} are the same power. What the forward statement <em>carries</em> is
 *       always the new side's lexical form.</li>
 *   <li>A property may be multi-valued, so each key holds a set: what is added is the values only the new side
 *       has, what is removed the values only the parent has.</li>
 *   <li>A type is a statement like any other ({@code rdf:type} becomes {@link CgmesStatement#RDF_TYPE}), which is
 *       what makes an added object a forward type plus its properties and a removed object a reverse type plus all
 *       of them &mdash; exactly the shape {@code applyToGraph} and the fast-route check expect.</li>
 * </ul>
 *
 * <p>The order of the result is deterministic: subjects in the order the new graph first mentions them, then the
 * subjects only the parent has, and within a subject the properties in the order they first appear. Two runs over
 * the same input produce byte-identical graphs, which is what makes a stored difference comparable at all.</p>
 *
 * <p>Cost is {@code O(|parent| + |new|)} with hash maps, and the memory is the two statement lists, which are
 * already in the local stores the caller read them from.</p>
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
     * {@link Index} is what an ingestion does instead of materialising and decoding it per timestep. The result is
     * byte-identical either way &mdash; an index is a pure function of the statements, their order and the two
     * namespaces it was built with.</p>
     *
     * @param parent the index of the state the difference applies on
     * @param next   the index of the state the difference produces
     * @param header the header the difference model gets
     * @return the difference; {@link DifferenceModel#isEmpty()} when the two graphs say the same thing
     */
    static DifferenceModel diff(Index parent, Index next, DifferenceModelHeader header) {
        List<CgmesStatement> forward = new ArrayList<>();
        List<CgmesStatement> reverse = new ArrayList<>();
        // The new graph first, in its own order, then whatever only the parent had: deterministic and stable
        next.bySubject().forEach((subject, properties) ->
                properties.forEach((property, statements) -> compare(
                        parent.bySubject().getOrDefault(subject, Map.of()).getOrDefault(property, List.of()),
                        statements, forward, reverse)));
        parent.bySubject().forEach((subject, properties) -> {
            Map<String, List<CgmesStatement>> counterpart = next.bySubject().getOrDefault(subject, Map.of());
            properties.forEach((property, statements) -> {
                if (!counterpart.containsKey(property)) {
                    compare(statements, List.of(), forward, reverse);
                }
            });
        });
        return new DifferenceModel(header, forward, reverse, List.of());
    }

    /**
     * One graph of one profile, decoded and grouped by subject and property, with the model header left out.
     *
     * <p>Read-only once built, and deliberately so: an ingestion keeps the index of a parent state and compares
     * several timesteps against it, so nothing downstream may write into it. The maps and lists are the ones
     * {@link #index(Collection, String, String)} filled, wrapped unmodifiable at every level; the statements
     * themselves are records and immutable anyway.</p>
     *
     * <p>Size: one entry per subject, one list per property of that subject. Svedala's equipment profile is
     * ~8 400 subjects and ~56 000 statements (15&ndash;20 MB), its steady state hypothesis ~6 000 subjects and
     * ~22 000 statements (~6 MB).</p>
     *
     * @param bySubject the statements of the graph, by local subject identifier and then by property, in the
     *                  order the graph first mentions each
     */
    record Index(Map<String, Map<String, List<CgmesStatement>>> bySubject) {

        /** @return how many statements the index holds, the model header excluded */
        int size() {
            int n = 0;
            for (Map<String, List<CgmesStatement>> properties : bySubject.values()) {
                for (List<CgmesStatement> statements : properties.values()) {
                    n += statements.size();
                }
            }
            return n;
        }
    }

    /**
     * The values of one key on both sides: what only the new side has is added, what only the parent has is gone.
     *
     * <p>The overwhelmingly common case &mdash; one value on each side, unchanged &mdash; is decided on the
     * lexical forms alone. {@link #comparable(CgmesStatement)} is a pure function of {@code (kind, value)}, so two
     * statements of the same kind carrying the same characters have the same comparable, both filters below find
     * their counterpart and nothing is written on either side. Skipping the normalisation there is what keeps a
     * day of schedules from normalising a hundred and seventy thousand values that did not move.</p>
     *
     * <p>Everything else falls through to the general logic, which normalises every statement <em>once</em> and
     * then works on those forms: a key may be multi-valued, and an added or removed subject arrives here as a
     * comparison against the empty list.</p>
     */
    private static void compare(List<CgmesStatement> before, List<CgmesStatement> after,
                                List<CgmesStatement> forward, List<CgmesStatement> reverse) {
        if (before.size() == 1 && after.size() == 1) {
            CgmesStatement b = before.get(0);
            CgmesStatement a = after.get(0);
            if (a.kind() == b.kind() && a.value().equals(b.value())) {
                return;
            }
        }
        List<String> beforeComparables = comparables(before);
        List<String> afterComparables = comparables(after);
        Set<String> beforeValues = new HashSet<>(beforeComparables);
        Set<String> afterValues = new HashSet<>(afterComparables);
        for (int i = 0; i < after.size(); i++) {
            if (!beforeValues.contains(afterComparables.get(i))) {
                forward.add(after.get(i));
            }
        }
        for (int i = 0; i < before.size(); i++) {
            if (!afterValues.contains(beforeComparables.get(i))) {
                reverse.add(before.get(i));
            }
        }
    }

    /** The comparable form of every statement of one side, in order, computed once per statement. */
    private static List<String> comparables(List<CgmesStatement> statements) {
        List<String> values = new ArrayList<>(statements.size());
        for (CgmesStatement statement : statements) {
            values.add(comparable(statement));
        }
        return values;
    }

    /**
     * What decides whether two statements say the same thing.
     *
     * <p>The kind is part of it &mdash; a reference to {@code X} and a literal {@code "X"} are different statements
     * &mdash; and a numeric literal compares by value, so that a re-export which writes {@code 10.0} where the
     * base wrote {@code 10} yields no difference at all.</p>
     *
     * <p>A literal is only handed to {@link Double#parseDouble(String)} when it could possibly be a number
     * ({@link #looksNumeric(String)}). Most CGMES literals are not &mdash; names, descriptions, booleans, dates
     * &mdash; and the exception {@code parseDouble} throws for each of them costs more than everything else this
     * class does put together. The pre-check accepts strictly more strings than {@code parseDouble} does, so the
     * answer never changes: what it rejects, {@code parseDouble} would have rejected too.</p>
     */
    static String comparable(CgmesStatement statement) {
        String value = statement.value();
        if (statement.kind() == CgmesStatement.Kind.LITERAL) {
            if (looksNumeric(value)) {
                try {
                    return "L" + Double.parseDouble(value);
                } catch (NumberFormatException notANumber) {
                    return "L" + value;
                }
            }
            return "L" + value;
        }
        return statement.kind().name().charAt(0) + value;
    }

    /**
     * Whether a literal could be a {@code double}, cheaply and conservatively.
     *
     * <p>{@code parseDouble} ignores leading and trailing characters {@code <= ' '} and then wants a sign, a digit,
     * a decimal point, {@code NaN} or {@code Infinity}. Anything else it rejects, so anything else may skip it.</p>
     */
    private static boolean looksNumeric(String value) {
        int i = 0;
        int n = value.length();
        while (i < n && value.charAt(i) <= ' ') {
            i++;
        }
        if (i == n) {
            return false;
        }
        char c = value.charAt(i);
        return c >= '0' && c <= '9' || c == '+' || c == '-' || c == '.' || c == 'N' || c == 'I';
    }

    /**
     * The statements of one graph, by subject and property, with the model header left out.
     *
     * @param graph        the statements of one profile, in document order
     * @param subjectBase  the IRI prefix the subjects of that graph carry before {@code _<mRID>}
     * @param cimNamespace the CIM namespace the properties are written in
     * @return the index; read-only
     */
    static Index index(Collection<Statement> graph, String subjectBase, String cimNamespace) {
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
        return readOnly(bySubject);
    }

    /**
     * The same nesting, every level unmodifiable, so that a kept index cannot be written into by a later diff.
     *
     * <p>Package-private because {@link IngestParser} fills the same structure straight from the RDF/XML parser
     * and hands it over here rather than build a store for {@link #index(Collection, String, String)} to read.</p>
     */
    static Index readOnly(Map<String, Map<String, List<CgmesStatement>>> bySubject) {
        bySubject.replaceAll((subject, properties) -> {
            properties.replaceAll((property, statements) -> Collections.unmodifiableList(statements));
            return Collections.unmodifiableMap(properties);
        });
        return new Index(Collections.unmodifiableMap(bySubject));
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
