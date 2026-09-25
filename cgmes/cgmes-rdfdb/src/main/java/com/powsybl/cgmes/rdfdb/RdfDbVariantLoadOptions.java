/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import java.util.Objects;
import java.util.function.Function;

/**
 * How a whole day is loaded into the variants of one network.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class RdfDbVariantLoadOptions {

    private Function<SnapshotInfo, String> naming;
    private RdfDbUpdateOptions updateOptions = new RdfDbUpdateOptions();
    private boolean allowVariantMultiThreadAccess;

    /**
     * How a requested snapshot that carries no explicit variant identifier is named.
     *
     * <p>The default names a variant after the {@code HH:MM} label of its timestep when the requested labels are
     * all different &mdash; which is what walking one day looks like &mdash; and after
     * {@code version + "@" + label} when they are not, so that two versions of the same moment never collide. A
     * timestep without a label falls back to its canonical instant.</p>
     *
     * @param naming the rule, or {@code null} for the default
     * @return this
     */
    public RdfDbVariantLoadOptions setNaming(Function<SnapshotInfo, String> naming) {
        this.naming = naming;
        return this;
    }

    /**
     * @return the naming rule, or {@code null} when the default applies
     */
    public Function<SnapshotInfo, String> getNaming() {
        return naming;
    }

    /**
     * How each variant is brought to its snapshot.
     *
     * @param updateOptions the options, never {@code null}
     * @return this
     */
    public RdfDbVariantLoadOptions setUpdateOptions(RdfDbUpdateOptions updateOptions) {
        this.updateOptions = Objects.requireNonNull(updateOptions);
        return this;
    }

    /**
     * @return how each variant is brought to its snapshot
     */
    public RdfDbUpdateOptions getUpdateOptions() {
        return updateOptions;
    }

    /**
     * Whether the network may be read from several threads afterwards.
     *
     * <p>Off by default, exactly like {@code VariantManager.allowVariantMultiThreadAccess}. Switching it on is
     * what a caller running a load flow per variant in parallel needs, and it is set <em>here</em> because the
     * variants have to exist before the readers start: IIDM grows its per-variant arrays when a variant is
     * created, which is not safe while another thread is reading them.</p>
     *
     * @param allowVariantMultiThreadAccess whether each thread keeps its own working variant
     * @return this
     */
    public RdfDbVariantLoadOptions setAllowVariantMultiThreadAccess(boolean allowVariantMultiThreadAccess) {
        this.allowVariantMultiThreadAccess = allowVariantMultiThreadAccess;
        return this;
    }

    /**
     * @return whether each thread keeps its own working variant
     */
    public boolean isAllowVariantMultiThreadAccess() {
        return allowVariantMultiThreadAccess;
    }
}
