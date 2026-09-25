/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion;

import com.powsybl.iidm.network.Identifiable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Which equipment a CGMES update walks over.
 *
 * <p>An update driven by a file has to visit every element of the network, because any of them may appear in the
 * file. An update driven by a <em>difference model</em> knows the objects it touches before it starts, and on a
 * large network the walk over everything else is the dominant cost: for each element the update looks its CGMES
 * identifier up, asks the triple store cache for a property bag and, finding none, still runs the "no data" branch
 * that re-applies the previous values.</p>
 *
 * <p>{@link #ALL} is the ordinary update and behaves exactly as before this class existed. A restricted scope visits
 * only the named identifiables, in a deterministic order, and additionally lets the caller skip the two passes that
 * are meaningless for a partial change: the voltage and angle completion (a difference model carries no state
 * variables, so untouched buses keep the values they have) and the final validation check (legal only when every
 * setter already validated at steady state hypothesis level).</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class UpdateScope {

    /** Every element of the network, which is what a file driven update needs. */
    public static final UpdateScope ALL = new UpdateScope(null);

    private final Set<String> ids;

    private UpdateScope(Set<String> ids) {
        this.ids = ids;
    }

    /**
     * The scope holding exactly the given identifiables.
     *
     * @param identifiableIds the IIDM identifiers to visit. Copied into a sorted set, so that the order of an update
     *                        does not depend on the iteration order of the caller's collection
     */
    public static UpdateScope of(Collection<String> identifiableIds) {
        Objects.requireNonNull(identifiableIds);
        return new UpdateScope(new TreeSet<>(identifiableIds));
    }

    /** Whether this scope is the whole network. */
    public boolean isAll() {
        return ids == null;
    }

    /** Whether this scope holds the given identifier. */
    public boolean contains(String id) {
        return ids == null || ids.contains(id);
    }

    /** The identifiers of a restricted scope, empty for {@link #ALL}. */
    public Set<String> ids() {
        return ids == null ? Set.of() : ids;
    }

    /**
     * The elements of {@code all} this scope selects.
     *
     * <p>For {@link #ALL} this is {@code all} itself, untouched, so that a full update pays nothing for the
     * existence of this class. For a restricted scope it is the identifiers of the scope looked up one by one,
     * leaving out those that name something else than a {@code T}.</p>
     *
     * @param all  every element of the kind being updated
     * @param byId how to look one up by identifier, typically a method reference such as {@code network::getLoad}
     */
    public <T extends Identifiable<?>> Iterable<T> select(Iterable<T> all, Function<String, T> byId) {
        if (ids == null) {
            return all;
        }
        List<T> selected = new java.util.ArrayList<>();
        for (String id : ids) {
            T element = byId.apply(id);
            if (element != null) {
                selected.add(element);
            }
        }
        return selected;
    }

    /** Whether any element of the stream is in this scope. */
    public boolean containsAny(Stream<? extends Identifiable<?>> stream) {
        return ids == null || stream.anyMatch(identifiable -> ids.contains(identifiable.getId()));
    }

    @Override
    public String toString() {
        return ids == null ? "UpdateScope(all)" : "UpdateScope" + ids;
    }
}
