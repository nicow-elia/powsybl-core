/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.model.diff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An IEC 61970-552 difference model: what a CGMES model said before a change and what it says after it.
 *
 * <p>The forward statements are the new state, the reverse statements the previous one. A receiver that holds the
 * model this difference applies on moves forward by replacing every property named in {@link #forward()} and moves
 * back by replacing every property named in {@link #reverse()}. Both directions are given as complete statements
 * rather than as a delta, so a consumer never has to look the previous value up to undo a change.</p>
 *
 * <p>One difference model describes exactly one profile, which its {@link #header()} names; a change touching
 * several profiles is a {@link DifferenceModelSet} of one model per profile.</p>
 *
 * <p>Lists keep the order their producer chose &mdash; subjects in the order in which they were first changed, the
 * properties of a subject in the order in which they were first set &mdash; and {@link #minimized()},
 * {@link #inverted(DifferenceModelHeader)} and {@link #compose(List, DifferenceModelHeader)} all preserve relative
 * order, so that exporting the same change twice writes the same document twice.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class DifferenceModel {

    private final DifferenceModelHeader header;
    private final List<CgmesStatement> forward;
    private final List<CgmesStatement> reverse;
    private final List<CgmesStatement> preconditions;

    /**
     * @param header        the model description of this difference
     * @param forward       the statements describing the state after the change
     * @param reverse       the statements describing the state before the change
     * @param preconditions the statements a receiver has to find in the model before applying the difference. Not
     *                      produced by the exporters of this release, but part of the difference model and therefore
     *                      carried through
     */
    public DifferenceModel(DifferenceModelHeader header, List<CgmesStatement> forward, List<CgmesStatement> reverse,
                           List<CgmesStatement> preconditions) {
        this.header = Objects.requireNonNull(header);
        this.forward = List.copyOf(forward);
        this.reverse = List.copyOf(reverse);
        this.preconditions = List.copyOf(preconditions);
    }

    /** The model description of this difference. */
    public DifferenceModelHeader header() {
        return header;
    }

    /** The statements describing the state after the change. */
    public List<CgmesStatement> forward() {
        return forward;
    }

    /** The statements describing the state before the change. */
    public List<CgmesStatement> reverse() {
        return reverse;
    }

    /** The statements a receiver has to find in the model before applying this difference. */
    public List<CgmesStatement> preconditions() {
        return preconditions;
    }

    /** Whether this difference says nothing at all, that is whether both directions are empty. */
    public boolean isEmpty() {
        return forward.isEmpty() && reverse.isEmpty();
    }

    /**
     * The difference that undoes this one: its forward statements are this one's reverse statements and the other way
     * round.
     *
     * <p>Preconditions are dropped, because they describe the state this difference expects to be applied on, which
     * is not the state its inverse is applied on.</p>
     *
     * @param newHeader the header of the inverted model. A difference and its inverse are two different models, so
     *                  the caller has to say what identifies the new one
     */
    public DifferenceModel inverted(DifferenceModelHeader newHeader) {
        return new DifferenceModel(newHeader, reverse, forward, List.of());
    }

    /**
     * The same difference with every statement that says the same thing in both directions dropped, leaving only what
     * actually changed.
     *
     * <p>Exporters describe whole consistency groups, because a receiver reads some CGMES properties only together,
     * which means both directions repeat the properties of the group that did not change. Minimizing turns such a
     * full object description into a true delta, at the price of a document a receiver has to merge property by
     * property rather than object by object.</p>
     */
    public DifferenceModel minimized() {
        Set<CgmesStatement> inBoth = new HashSet<>(forward);
        inBoth.retainAll(new HashSet<>(reverse));
        if (inBoth.isEmpty()) {
            return this;
        }
        return new DifferenceModel(header,
                forward.stream().filter(s -> !inBoth.contains(s)).toList(),
                reverse.stream().filter(s -> !inBoth.contains(s)).toList(),
                preconditions);
    }

    /**
     * Fold a chain of differences applied one after the other into the single difference with the same net effect.
     *
     * <p>Per {@link CgmesStatement.Key}, the reverse statement is the one of the <em>first</em> model of the chain
     * that mentions the key, because that is the state the chain started from, and the forward statement is the one
     * of the <em>last</em> model that mentions it, because that is the state the chain ends in. A last model that
     * only reverses a key leaves it without a forward statement, which is how a property that ends up unset is
     * expressed. Keys whose forward and reverse ends up saying the same thing are dropped from both directions.
     * Keys appear in the order in which the chain first mentions them.</p>
     *
     * <p>Preconditions are dropped: they belong to the state the first model of the chain was applied on, which a
     * composed difference no longer describes.</p>
     *
     * @param chain  the differences in the order they are applied. All of them have to describe the same profile in
     *               the same CIM namespace
     * @param header the header of the composed model
     * @throws IllegalArgumentException if the models of the chain do not share their profile and CIM namespace
     */
    public static DifferenceModel compose(List<DifferenceModel> chain, DifferenceModelHeader header) {
        Objects.requireNonNull(chain);
        Objects.requireNonNull(header);
        checkOneProfile(chain);

        Map<CgmesStatement.Key, CgmesStatement> reverseByKey = new LinkedHashMap<>();
        Map<CgmesStatement.Key, CgmesStatement> forwardByKey = new LinkedHashMap<>();
        Set<CgmesStatement.Key> mentioned = new HashSet<>();
        for (DifferenceModel model : chain) {
            Set<CgmesStatement.Key> mentionedHere = new HashSet<>();
            model.reverse().forEach(s -> mentionedHere.add(s.key()));
            model.forward().forEach(s -> mentionedHere.add(s.key()));
            // The state the chain started from is what the first model mentioning a key says it reversed
            for (CgmesStatement statement : model.reverse()) {
                if (!mentioned.contains(statement.key())) {
                    reverseByKey.put(statement.key(), statement);
                }
            }
            // A model that mentions a key replaces whatever an earlier one made of it, and a model that only
            // reverses the key leaves it without a forward value
            for (CgmesStatement.Key key : mentionedHere) {
                forwardByKey.remove(key);
            }
            model.forward().forEach(s -> forwardByKey.put(s.key(), s));
            mentioned.addAll(mentionedHere);
        }

        List<CgmesStatement.Key> order = keyOrder(chain);
        List<CgmesStatement> forward = new ArrayList<>();
        List<CgmesStatement> reverse = new ArrayList<>();
        for (CgmesStatement.Key key : order) {
            CgmesStatement f = forwardByKey.get(key);
            CgmesStatement r = reverseByKey.get(key);
            if (Objects.equals(f, r)) {
                // The chain put the property back where it found it
                continue;
            }
            if (f != null) {
                forward.add(f);
            }
            if (r != null) {
                reverse.add(r);
            }
        }
        return new DifferenceModel(header, forward, reverse, List.of());
    }

    /** The keys of a chain in the order in which the chain first mentions them, reverse before forward. */
    private static List<CgmesStatement.Key> keyOrder(List<DifferenceModel> chain) {
        Set<CgmesStatement.Key> seen = new LinkedHashSet<>();
        for (DifferenceModel model : chain) {
            model.reverse().forEach(s -> seen.add(s.key()));
            model.forward().forEach(s -> seen.add(s.key()));
        }
        return List.copyOf(seen);
    }

    private static void checkOneProfile(Collection<DifferenceModel> chain) {
        DifferenceModel first = null;
        for (DifferenceModel model : chain) {
            Objects.requireNonNull(model);
            if (first == null) {
                first = model;
            } else if (first.header().subset() != model.header().subset()
                    || !first.header().cimNamespace().equals(model.header().cimNamespace())) {
                throw new IllegalArgumentException("A chain of difference models describes a single profile, but"
                        + " it holds a " + first.header().subset().getIdentifier() + " model in "
                        + first.header().cimNamespace() + " and a " + model.header().subset().getIdentifier()
                        + " model in " + model.header().cimNamespace());
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DifferenceModel other
                && header.equals(other.header)
                && forward.equals(other.forward)
                && reverse.equals(other.reverse)
                && preconditions.equals(other.preconditions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(header, forward, reverse, preconditions);
    }

    @Override
    public String toString() {
        return "DifferenceModel(" + header.id() + ", " + header.subset().getIdentifier() + ", forward="
                + forward.size() + " statements, reverse=" + reverse.size() + " statements)";
    }
}
