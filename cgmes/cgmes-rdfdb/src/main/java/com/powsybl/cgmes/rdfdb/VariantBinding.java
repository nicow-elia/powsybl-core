/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * What a variant of a network stands for: the stored snapshot its state is.
 *
 * <p>A network loaded from a database is "at" one stored state, and the versioning layer keeps that identity on the
 * network itself so that the next operation knows where it stands. A network with bound <em>variants</em> is at
 * several stored states at once, one per variant, and this record is one of them. It is a value, taken under the
 * lock of the provenance, and it never changes behind the caller's back; the binding it was read from does.</p>
 *
 * <p>The identity is complete on purpose. The snapshot IRI is what the planner walks the version graph from, the
 * model identifiers per profile are what a difference has to supersede, and the case date is the scenario time of
 * that snapshot &mdash; a value IIDM keeps once per network, so a variant can only carry it here.</p>
 *
 * @param variantId   the identifier of the IIDM variant
 * @param scenario    the scenario the snapshot belongs to
 * @param ref         the address of the snapshot, or {@code null} when the variant is at a state of an unversioned
 *                    scenario or of a file-loaded network
 * @param snapshotIri the IRI of the snapshot, or {@code null} in the same cases
 * @param modelIds    the stored model the variant is at, per CGMES profile
 * @param caseDate    the case date of the network while this variant is the one being operated on
 * @param clonedFrom  the variant this one was cloned from, or {@code null} when it was not
 * @param boundAt     when the binding was recorded
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record VariantBinding(String variantId, String scenario, SnapshotRef ref, String snapshotIri,
                             Map<CgmesSubset, String> modelIds, ZonedDateTime caseDate, String clonedFrom,
                             Instant boundAt) {

    /**
     * @param variantId   see {@link #variantId()}
     * @param scenario    see {@link #scenario()}
     * @param ref         see {@link #ref()}
     * @param snapshotIri see {@link #snapshotIri()}
     * @param modelIds    see {@link #modelIds()}
     * @param caseDate    see {@link #caseDate()}
     * @param clonedFrom  see {@link #clonedFrom()}
     * @param boundAt     see {@link #boundAt()}
     */
    public VariantBinding {
        modelIds = Map.copyOf(modelIds);
    }

    /**
     * @return the timestep of the snapshot, or {@code null} when the variant is not at a snapshot
     */
    public String timestep() {
        return ref == null ? null : ref.timestep();
    }

    /**
     * @return the version label of the snapshot, or {@code null} when the variant is not at a snapshot
     */
    public String version() {
        return ref == null ? null : ref.version();
    }

    @Override
    public String toString() {
        return variantId + "@" + (ref == null ? "(no snapshot)" : ref.toString());
    }
}
