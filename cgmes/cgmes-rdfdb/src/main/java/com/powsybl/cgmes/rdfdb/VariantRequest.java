/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

/**
 * One snapshot a bulk load turns into a variant of the network.
 *
 * @param variantId the identifier the variant gets, or {@code null} to let the naming rule of
 *                  {@link RdfDbVariantLoadOptions#setNaming} decide
 * @param ref       the address of the snapshot
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record VariantRequest(String variantId, SnapshotRef ref) {

    /**
     * @param variantId see {@link #variantId()}
     * @param ref       see {@link #ref()}
     */
    public VariantRequest {
        java.util.Objects.requireNonNull(ref);
    }

    /**
     * A request that lets the naming rule decide.
     *
     * @param ref the address of the snapshot
     * @return the request
     */
    public static VariantRequest of(SnapshotRef ref) {
        return new VariantRequest(null, ref);
    }
}
