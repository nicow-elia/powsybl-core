/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManager;

/**
 * Runs an export with one variant of a network selected, and puts the previous selection back.
 *
 * <p>The whole export pipeline reads the network as it currently stands: the translator compares the variant a
 * change was recorded on with the working one, the compaction feeds its previous values only from changes of the
 * working variant, and the state view reads live values through the working variant. Selecting the variant for the
 * duration of the export therefore makes every one of those correct at once, and no part of the pipeline needs a
 * variant parameter of its own.</p>
 *
 * <p>The working variant is a thread-local of the variant manager, and a thread that never selected one has none
 * at all &mdash; {@code getWorkingVariantId} throws rather than answering the initial variant. Such a thread keeps
 * the variant this scope selected, which is the only thing it can do: there is nothing to restore.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class ExportVariantScope implements AutoCloseable {

    private final VariantManager variantManager;
    private final String previous;

    private ExportVariantScope(VariantManager variantManager, String previous) {
        this.variantManager = variantManager;
        this.previous = previous;
    }

    /**
     * Select a variant for the duration of the scope.
     *
     * @param network   the network
     * @param variantId the variant to select, or {@code null} to change nothing at all
     * @return the scope, to be closed in a try-with-resources
     */
    static ExportVariantScope enter(Network network, String variantId) {
        if (variantId == null) {
            return new ExportVariantScope(null, null);
        }
        VariantManager variantManager = network.getVariantManager();
        String previous = currentVariant(variantManager);
        if (variantId.equals(previous)) {
            return new ExportVariantScope(null, null);
        }
        variantManager.setWorkingVariant(variantId);
        return new ExportVariantScope(variantManager, previous);
    }

    /** The working variant of this thread, or {@code null} when it never selected one. */
    private static String currentVariant(VariantManager variantManager) {
        try {
            return variantManager.getWorkingVariantId();
        } catch (PowsyblException e) {
            return null;
        }
    }

    @Override
    public void close() {
        if (variantManager != null && previous != null) {
            variantManager.setWorkingVariant(previous);
        }
    }
}
