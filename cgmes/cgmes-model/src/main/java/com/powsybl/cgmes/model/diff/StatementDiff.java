/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.model.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The difference between two states of one CGMES profile, computed from their statements alone.
 *
 * <p>This is the comparison every producer of a difference without recorded changes needs: the ingestion of a file
 * into the database compares the parent state with the new file, an exporter can compare two of its own exports.
 * The result is an ordinary {@link DifferenceModel} &mdash; forward statements are the state after, reverse
 * statements the state before &mdash; so everything downstream treats it like a recorded one.</p>
 *
 * <h2>What is compared, and what is not</h2>
 * <ul>
 *   <li>A statement is keyed by {@code (subject, property)}. The model header is not part of either side: the
 *       caller leaves it out of the {@link Index}, the header of the difference says what the new state is
 *       called.</li>
 *   <li>Two literals that both parse as {@code double} compare by value ({@link #comparable}): a re-export which
 *       writes {@code 10.0} where the parent wrote {@code 10}, or {@code -0} where it wrote {@code 0}, yields no
 *       difference. What the forward statement <em>carries</em> is always the new side's lexical form.</li>
 *   <li>A property may be multi-valued, so each key holds a list: what is added is the values only the new side
 *       has, what is removed the values only the parent has.</li>
 *   <li>A type is a statement like any other ({@link CgmesStatement#RDF_TYPE}), which is what makes an added
 *       object a forward type plus its properties and a removed object a reverse type plus all of them.</li>
 * </ul>
 *
 * <p>The order of the result is deterministic: subjects in the order the new side first mentions them, then the
 * subjects only the parent has, and within a subject the properties in the order they first appear. Cost is
 * {@code O(|parent| + |new|)} with hash maps.</p>
 *
 * <p>Public API: a client outside this module builds on this signature.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class StatementDiff {

    private StatementDiff() {
    }

    /**
     * The statements of one state of one profile, grouped by subject and then by property, the model header left
     * out.
     *
     * <p>Read-only once built ({@link #readOnly(Map)}): a caller may keep the index of a parent state and compare
     * several new states against it, so nothing downstream may write into it.</p>
     *
     * @param bySubject the statements, by local subject identifier and then by property, in the order the state
     *                  first mentions each
     */
    public record Index(Map<String, Map<String, List<CgmesStatement>>> bySubject) {

        /** @return how many statements the index holds */
        public int size() {
            return bySubject.values().stream().flatMap(properties -> properties.values().stream())
                    .mapToInt(List::size).sum();
        }
    }

    /**
     * The same nesting, every level unmodifiable, so that a kept index cannot be written into by a later diff.
     *
     * @param bySubject the statements by subject and property; its nested maps are wrapped in place
     * @return the read-only index
     */
    public static Index readOnly(Map<String, Map<String, List<CgmesStatement>>> bySubject) {
        bySubject.replaceAll((subject, properties) -> {
            properties.replaceAll((property, statements) -> Collections.unmodifiableList(statements));
            return Collections.unmodifiableMap(properties);
        });
        return new Index(Collections.unmodifiableMap(bySubject));
    }

    /**
     * The difference from one state to another.
     *
     * @param parent the index of the state the difference applies on
     * @param next   the index of the state the difference produces
     * @param header the header the difference model gets
     * @return the difference; {@link DifferenceModel#isEmpty()} when the two states say the same thing
     */
    public static DifferenceModel diff(Index parent, Index next, DifferenceModelHeader header) {
        List<CgmesStatement> forward = new ArrayList<>();
        List<CgmesStatement> reverse = new ArrayList<>();
        // The new state first, in its own order, then whatever only the parent had: deterministic and stable
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
     * The values of one key on both sides: what only the new side has is added, what only the parent has is gone.
     *
     * <p>The overwhelmingly common case &mdash; one value on each side, unchanged &mdash; is decided on the
     * lexical forms alone: {@link #comparable(CgmesStatement)} is a pure function of {@code (kind, value)}, so two
     * statements of the same kind carrying the same characters say the same thing. Everything else normalises
     * every statement <em>once</em> and works on those forms.</p>
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
     * &mdash; and a numeric literal compares by value, {@code -0} and {@code 0} included.</p>
     *
     * <p>A literal is only handed to {@link Double#parseDouble(String)} when it could possibly be a number
     * ({@link #looksNumeric(String)}). Most CGMES literals are not &mdash; names, booleans, dates &mdash; and the
     * exception {@code parseDouble} throws for each of them costs more than the whole comparison. The pre-check
     * accepts strictly more strings than {@code parseDouble} does, so the answer never changes.</p>
     *
     * @param statement a statement
     * @return its comparable form: equal for two statements that say the same thing
     */
    public static String comparable(CgmesStatement statement) {
        String value = statement.value();
        if (statement.kind() == CgmesStatement.Kind.LITERAL) {
            if (looksNumeric(value)) {
                try {
                    // + 0.0 so that -0 and 0 compare equal: numeric equality, not the text of a double
                    return "L" + (Double.parseDouble(value) + 0.0);
                } catch (NumberFormatException notANumber) {
                    return "L" + value;
                }
            }
            return "L" + value;
        }
        return statement.kind().name().charAt(0) + value;
    }

    /**
     * Whether a literal could be a {@code double}, cheaply and conservatively: {@code parseDouble} ignores leading
     * characters {@code <= ' '} and then wants a sign, a digit, a decimal point, {@code NaN} or {@code Infinity}.
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
}
