/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.computation.ComputationManager;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * What to read out of a scenario, and what to do with the network afterwards.
 *
 * <p>Mutable and chainable, in the style of the other powsybl option objects.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class RdfDbLoadOptions {

    /** The subsets an update reads when nothing else is said: the two that carry a steady state. */
    public static final EnumSet<CgmesSubset> DEFAULT_UPDATE_SUBSETS =
            EnumSet.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, CgmesSubset.STATE_VARIABLES);

    private EnumSet<CgmesSubset> subsets;
    private boolean applyImportPostProcessors = true;
    private List<String> postProcessors = new ArrayList<>();
    private ComputationManager computationManager;

    /**
     * Options that read everything a scenario holds.
     */
    public RdfDbLoadOptions() {
    }

    /**
     * Options for an update, reading the steady-state subsets.
     *
     * @return the options
     */
    public static RdfDbLoadOptions forUpdate() {
        return new RdfDbLoadOptions().setSubsets(EnumSet.copyOf(DEFAULT_UPDATE_SUBSETS));
    }

    /**
     * Restrict the load to the graphs of the given subsets.
     *
     * @param newSubsets the subsets to read, or {@code null} for every graph of the scenario
     * @return these options
     */
    public RdfDbLoadOptions setSubsets(EnumSet<CgmesSubset> newSubsets) {
        this.subsets = newSubsets == null ? null : EnumSet.copyOf(newSubsets);
        return this;
    }

    /**
     * @return the subsets to read, or {@code null} for every graph of the scenario
     */
    public EnumSet<CgmesSubset> getSubsets() {
        return subsets == null ? null : EnumSet.copyOf(subsets);
    }

    /**
     * Whether the generic IIDM import post-processors are applied after a load.
     *
     * <p>A file import applies them in {@code Importer.find}, above the CGMES importer, so a database load has to
     * do it itself or the two paths would differ.</p>
     *
     * @param enabled whether to apply them
     * @return these options
     */
    public RdfDbLoadOptions setApplyImportPostProcessors(boolean enabled) {
        this.applyImportPostProcessors = enabled;
        return this;
    }

    /**
     * @return whether the generic IIDM import post-processors are applied
     */
    public boolean isApplyImportPostProcessors() {
        return applyImportPostProcessors;
    }

    /**
     * The generic IIDM import post-processors to apply, by name.
     *
     * @param names the names. Empty (the default) means the ones the platform configuration activates, which is
     *              what a file import applies
     * @return these options
     */
    public RdfDbLoadOptions setPostProcessors(List<String> names) {
        this.postProcessors = new ArrayList<>(Objects.requireNonNullElseGet(names, List::of));
        return this;
    }

    /**
     * @return the generic IIDM import post-processors to apply, by name. Empty means the configured ones
     */
    public List<String> getPostProcessors() {
        return List.copyOf(postProcessors);
    }

    /**
     * The computation manager the import post-processors are given.
     *
     * <p>A post-processor may run a load flow, so it needs a real one; a file import hands over the one
     * {@code Importer.find} was given. Left unset, a database load uses
     * {@code LocalComputationManager.getDefault()}, and only when there is a post-processor to run.</p>
     *
     * @param manager the computation manager, or {@code null} for the default
     * @return these options
     */
    public RdfDbLoadOptions setComputationManager(ComputationManager manager) {
        this.computationManager = manager;
        return this;
    }

    /**
     * @return the computation manager the import post-processors are given, or {@code null} for the default
     */
    public ComputationManager getComputationManager() {
        return computationManager;
    }
}
