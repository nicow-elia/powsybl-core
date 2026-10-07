/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.diff.CgmesDiffImport;
import com.powsybl.iidm.network.NetworkFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * How far an update may go to bring a network to a stored state.
 *
 * <p>The defaults are the ones a caller who has not thought about it wants: apply differences when that is
 * possible, reload the whole network when it is not, and do not walk an unreasonably long chain of differences to
 * avoid a reload that would be cheaper anyway.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class RdfDbUpdateOptions {

    /** The default limit on how many differences an update composes before it reloads instead. */
    public static final int DEFAULT_MAX_DIFF_CHAIN = 200;

    /** How far a snapshot may be from the nearest full one before a checkpoint is worth recommending. */
    public static final int DEFAULT_CHECKPOINT_AFTER = 100;

    private int checkpointAfter = DEFAULT_CHECKPOINT_AFTER;

    private boolean allowFullReload = true;
    private int maxDiffChain = DEFAULT_MAX_DIFF_CHAIN;
    private CgmesDiffImport.Options diffOptions = new CgmesDiffImport.Options();
    private Set<String> profiles =
            Profiles.set(List.of(Profiles.EQ, Profiles.SSH));
    private NetworkFactory networkFactory;
    private String targetVariant;
    private VariantFallback variantFallback = VariantFallback.REFUSE;

    /** What an update does when a snapshot cannot be reached inside one variant. */
    public enum VariantFallback {
        /**
         * Answer {@link UpdateResult.Route#VARIANT_REFUSED} with the reasons and leave every variant of the
         * network untouched. The default, because a caller who asked for a variant asked for a network whose
         * other variants keep standing.
         */
        REFUSE,
        /**
         * Materialise the snapshot into a <strong>separate</strong> single-variant network and answer
         * {@link UpdateResult.Route#FULL_RELOAD} with it. The multi-variant network is untouched either way; what
         * changes is only whether the caller is handed the state it wanted somewhere else.
         */
        SEPARATE_NETWORK
    }

    /**
     * The variant to bring to the target, created when it does not exist yet.
     *
     * <p>This is the opt-in. Without it, and without any variant of the network already being bound to a
     * snapshot, every entry point of this package behaves exactly as it did before variants existed. With it the
     * update is a <em>variant</em> operation: the difference is applied on that variant alone, and anything that
     * would write state shared by all variants is refused instead.</p>
     *
     * @param targetVariant the identifier of the IIDM variant, or {@code null} for the working variant
     * @return this
     */
    public RdfDbUpdateOptions setTargetVariant(String targetVariant) {
        this.targetVariant = targetVariant;
        return this;
    }

    /**
     * @return the variant to bring to the target, or {@code null}
     */
    public String getTargetVariant() {
        return targetVariant;
    }

    /**
     * What happens when the target cannot be reached inside one variant.
     *
     * @param variantFallback the behaviour, never {@code null}
     * @return this
     */
    public RdfDbUpdateOptions setVariantFallback(VariantFallback variantFallback) {
        this.variantFallback = Objects.requireNonNull(variantFallback);
        return this;
    }

    /**
     * @return what happens when the target cannot be reached inside one variant
     */
    public VariantFallback getVariantFallback() {
        return variantFallback;
    }

    /**
     * Whether an update that cannot be applied in place may rebuild the network from the database.
     *
     * <p>On by default. A caller that holds references into the network &mdash; and a full reload answers with a
     * <em>new</em> network &mdash; switches it off and gets {@code FULL_REQUIRED} instead, with its own network
     * untouched.</p>
     *
     * <p>Ignored in variant mode: a variant operation never rebuilds the network it was given, because that would
     * throw away every other variant of it. {@link #setVariantFallback} decides what happens there.</p>
     *
     * @param allowFullReload whether a full reload is allowed
     * @return this
     */
    public RdfDbUpdateOptions setAllowFullReload(boolean allowFullReload) {
        this.allowFullReload = allowFullReload;
        return this;
    }

    /**
     * @return whether a full reload is allowed
     */
    public boolean isAllowFullReload() {
        return allowFullReload;
    }

    /**
     * How many differences may be composed into one update.
     *
     * <p>Composing is linear in statements and cheap, but a network that is a thousand versions behind is usually
     * better off being reloaded, and {@link Checkpoint} folds such a chain on the database so that the next
     * reload is cheap.</p>
     *
     * @param maxDiffChain the limit, at least 1
     * @return this
     */
    public RdfDbUpdateOptions setMaxDiffChain(int maxDiffChain) {
        if (maxDiffChain < 1) {
            throw new RdfDbException("maxDiffChain is at least 1, got " + maxDiffChain);
        }
        this.maxDiffChain = maxDiffChain;
        return this;
    }

    /**
     * @return the limit on the number of composed differences
     */
    public int getMaxDiffChain() {
        return maxDiffChain;
    }

    /**
     * @param diffOptions how the composed difference is applied to the network
     * @return this
     */
    public RdfDbUpdateOptions setDiffOptions(CgmesDiffImport.Options diffOptions) {
        this.diffOptions = Objects.requireNonNull(diffOptions);
        return this;
    }

    /**
     * @return how the composed difference is applied
     */
    public CgmesDiffImport.Options getDiffOptions() {
        return diffOptions;
    }

    /**
     * The profile projection of an update: the profiles it looks at.
     *
     * <p>The equipment model and the steady state hypothesis by default. A scenario-addressed update brings exactly
     * these forward; an update to a snapshot identifies where a network without provenance stands by these, and
     * then moves the network along every difference of the path.</p>
     *
     * <p>The projection identifies, it does not restrict what is loaded: when the update takes the FULL route, the
     * network is rebuilt from every profile of the target snapshot.</p>
     *
     * @param profiles the profiles
     * @return this
     * @throws RdfDbException if the set is empty or a name is not a profile name ({@link Profiles#check})
     */
    public RdfDbUpdateOptions setProfiles(Set<String> profiles) {
        Objects.requireNonNull(profiles);
        if (profiles.isEmpty()) {
            throw new RdfDbException("An update looks at at least one profile");
        }
        profiles.forEach(Profiles::check);
        this.profiles = Profiles.set(profiles);
        return this;
    }

    /**
     * @return the profiles an update looks at
     */
    public Set<String> getProfiles() {
        return Profiles.set(profiles);
    }

    /**
     * The factory a full reload builds its replacement network with.
     *
     * <p>Only a reload creates a network at all &mdash; the difference route changes the one it was handed &mdash;
     * but a caller who built the original with a factory of its own wants the replacement from the same one.</p>
     *
     * @param networkFactory the factory, or {@code null} for {@link NetworkFactory#findDefault()}
     * @return this
     */
    public RdfDbUpdateOptions setNetworkFactory(NetworkFactory networkFactory) {
        this.networkFactory = networkFactory;
        return this;
    }

    /**
     * How far a snapshot may be from the nearest snapshot with full graphs before a plan recommends a checkpoint.
     *
     * <p>A recommendation only: nothing fails because of it, and the difference route stays available up to
     * {@link #getMaxDiffChain()}. What it says is that materialising that snapshot has become expensive enough to
     * be worth paying once, server-side, with {@link Checkpoint#create}.</p>
     *
     * @param checkpointAfter the distance, at least 1
     * @return this
     */
    public RdfDbUpdateOptions setCheckpointAfter(int checkpointAfter) {
        if (checkpointAfter < 1) {
            throw new RdfDbException("checkpointAfter must be at least 1, got " + checkpointAfter);
        }
        this.checkpointAfter = checkpointAfter;
        return this;
    }

    /**
     * @return the distance from the nearest full snapshot above which a checkpoint is recommended
     */
    public int getCheckpointAfter() {
        return checkpointAfter;
    }

    /**
     * @return the factory a full reload uses, never {@code null}
     */
    public NetworkFactory getNetworkFactory() {
        return networkFactory == null ? NetworkFactory.findDefault() : networkFactory;
    }

    /**
     * An independent copy of these options.
     *
     * <p>What it is for: a variant operation has to run with {@code variantSafeOnly} and without a full reload
     * whatever the caller allowed, and must not write that back into the caller's object.</p>
     *
     * @return the copy
     */
    public RdfDbUpdateOptions copy() {
        RdfDbUpdateOptions copy = new RdfDbUpdateOptions();
        copy.checkpointAfter = checkpointAfter;
        copy.allowFullReload = allowFullReload;
        copy.maxDiffChain = maxDiffChain;
        copy.diffOptions = diffOptions.copy();
        copy.profiles = Profiles.set(profiles);
        copy.networkFactory = networkFactory;
        copy.targetVariant = targetVariant;
        copy.variantFallback = variantFallback;
        return copy;
    }
}
