/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import java.time.Duration;
import java.util.List;

/**
 * What became of one requested variant.
 *
 * <p>A bulk load asks for many snapshots at once and some of them may be unreachable in place &mdash; a timestep
 * whose equipment drifted, a difference that writes values IIDM does not store per variant. Such a request is
 * <em>refused</em>, and the network keeps exactly the variants that worked. That is only usable if the caller can
 * tell which ones those are and why the others failed, which is what this record is for; it is also what the
 * refusals of a single update are remembered as, see {@code RdfDbProvenance.lastRefused}.</p>
 *
 * @param variantId   the identifier of the IIDM variant, also when it was not created
 * @param requested   the snapshot that was asked for
 * @param snapshotIri the IRI of that snapshot, or {@code null} when it was never resolved
 * @param status      whether the variant is bound to the snapshot now
 * @param reasons     why it is not, empty when it is
 * @param clonedFrom  the variant the state was cloned from, or {@code null}
 * @param diffCount   how many differences were applied to reach the snapshot
 * @param apply       how long applying them took
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record VariantOutcome(String variantId, SnapshotRef requested, String snapshotIri, Status status,
                             List<String> reasons, String clonedFrom, int diffCount, Duration apply) {

    /** Whether a requested variant is there. */
    public enum Status {
        /** The variant exists and is bound to the snapshot. */
        BOUND,
        /** The snapshot could not be reached without touching state shared by all variants; nothing was changed. */
        REFUSED
    }

    /**
     * @param variantId   see {@link #variantId()}
     * @param requested   see {@link #requested()}
     * @param snapshotIri see {@link #snapshotIri()}
     * @param status      see {@link #status()}
     * @param reasons     see {@link #reasons()}
     * @param clonedFrom  see {@link #clonedFrom()}
     * @param diffCount   see {@link #diffCount()}
     * @param apply       see {@link #apply()}
     */
    public VariantOutcome {
        reasons = List.copyOf(reasons);
    }

    /**
     * @return whether the variant is bound to the snapshot
     */
    public boolean isBound() {
        return status == Status.BOUND;
    }

    /** A refusal with its reasons, for a variant that is not there. */
    static VariantOutcome refused(String variantId, SnapshotRef requested, String snapshotIri,
                                  List<String> reasons) {
        return new VariantOutcome(variantId, requested, snapshotIri, Status.REFUSED, reasons, null, 0,
                Duration.ZERO);
    }

    @Override
    public String toString() {
        return variantId + " " + status + (reasons.isEmpty() ? "" : " " + reasons);
    }
}
