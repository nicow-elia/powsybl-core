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
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One {@code pdb:Snapshot} node: a consistent grid state of one scenario, and everything a reader decides on.
 *
 * <p>A snapshot is the unit of consistency across profiles. The chain of a single profile lives in
 * {@code md:Model.Supersedes} and is what a difference applies along; a snapshot says which model of
 * <em>every</em> profile belongs together, which is what makes "load version 2" a well-defined request even when
 * the last three changes only touched the steady state.</p>
 *
 * <p>Three model lists, and they answer three different questions:</p>
 * <ul>
 *   <li>{@link #members()} &mdash; what this snapshot <em>adds</em>: the instance files of a root, the differences
 *       of a diff snapshot. This is its own content, and nothing else.</li>
 *   <li>{@link #state()} &mdash; what a reader <em>is at</em> once it reaches this snapshot, per profile. The
 *       parent's state with the touched profiles replaced.</li>
 *   <li>{@link #fullModels()} &mdash; what a materialisation can <em>start</em> from, per profile: a graph that
 *       holds the complete model rather than a delta. A root has one per profile; a checkpointed snapshot has the
 *       copies the checkpoint folded, plus the untouched profiles it inherited.</li>
 * </ul>
 *
 * @param scenario           the scenario the snapshot belongs to
 * @param iri                the IRI of the snapshot node
 * @param modellingAuthority the modelling authority set whose tree the snapshot is in
 * @param timestamp          the moment the snapshot describes
 * @param version            the version, at least 1
 * @param kind               whether the snapshot is a root of full models or a difference on its parent
 * @param parent             the IRI of the snapshot this one derives from, {@code null} for a root
 * @param edge               which kind of link {@link #parent()} is
 * @param depth              how many snapshots lie between this one and the root of its tree
 * @param fast               whether every difference member is fast-route capable; see {@link #fast()}
 * @param state              the effective model identifier per profile
 * @param members            the models this snapshot adds
 * @param fullModels         the model identifier with a full graph per profile
 * @param timestampRoot      the IRI of the root snapshot of this snapshot's timestamp
 * @param created            when the node was written
 * @param description        the free text the writer attached, or {@code null}
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record SnapshotInfo(String scenario, String iri, String modellingAuthority, Instant timestamp, int version,
                           Kind kind, String parent, EdgeKind edge, int depth, boolean fast,
                           Map<CgmesSubset, String> state, List<String> members,
                           Map<CgmesSubset, String> fullModels, String timestampRoot, ZonedDateTime created,
                           String description) {

    /** What a snapshot holds. */
    public enum Kind {
        /** Uploaded instance files: the root of a scenario. */
        FULL,
        /** Difference models on its parent. */
        DIFF
    }

    /** What the link to the parent means. */
    public enum EdgeKind {
        /** No parent: the root of a modelling authority's tree. */
        NONE,
        /** The previous version of the same timestamp. */
        VERSION,
        /** The base-chain snapshot a timestamp root was derived from. */
        TIMESTAMP
    }

    public SnapshotInfo {
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(iri);
        Objects.requireNonNull(modellingAuthority);
        Objects.requireNonNull(timestamp);
        state = Map.copyOf(state);
        members = List.copyOf(members);
        fullModels = Map.copyOf(fullModels);
    }

    /**
     * The CGMES profiles the state of this snapshot covers.
     *
     * <p>Not part of the address: it is what the snapshot <em>holds</em>, derived from its {@code pdb:state}. A
     * caller that wants fewer passes a projection to the operation instead.</p>
     *
     * @return the profiles, in the order of {@link CgmesSubset}
     */
    public Set<CgmesSubset> profiles() {
        return state.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(state.keySet()));
    }

    /**
     * Whether a materialisation can start at this snapshot instead of walking further up the chain.
     *
     * <p><strong>Derived, never stored.</strong> It is exactly {@code !fullModels().isEmpty()}: a snapshot can
     * start a materialisation when it names a full model, and naming one is what {@code pdb:full} does. A root
     * names its instance files, a difference snapshot names nothing until a {@link Checkpoint} folds its chain
     * into copies and adds the links.</p>
     *
     * @return whether this snapshot names at least one full model
     */
    public boolean hasFull() {
        return !fullModels.isEmpty();
    }

    /**
     * Whether a client can walk onto this snapshot without rebuilding its network.
     *
     * <p><strong>Derived, never stored.</strong> The fast-route capability is a property of a difference model
     * ({@code pdb:fastPredicatesOnly}, {@link StoredModel#fastPredicatesOnly()}) and is written once, there. This
     * value is the conjunction over the difference members of the snapshot, read in the same request that returned
     * the snapshot; a snapshot with no difference member &mdash; a root, or a snapshot of full models &mdash; is
     * {@code true}.</p>
     *
     * <p>It answers for the <em>one</em> step this snapshot adds, not for a path: whether an update from A to B can
     * be applied in place is {@code UpdatePlan.kind()}, which weighs every difference between the two.</p>
     *
     * @return whether every difference this snapshot adds is fast-route capable
     */
    @Override
    public boolean fast() {
        return fast;
    }

    /**
     * @return the address this snapshot answers to
     */
    public SnapshotRef ref() {
        return new SnapshotRef(scenario, modellingAuthority, timestamp, version);
    }

    /**
     * @return whether this snapshot is the root of its modelling authority's tree
     */
    public boolean isRoot() {
        return parent == null;
    }

    @Override
    public String toString() {
        return "(" + scenario + ", " + modellingAuthority + ", " + timestamp + ", " + version + ")";
    }
}
