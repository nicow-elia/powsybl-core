/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One {@code pdb:Snapshot} node: a consistent grid state of one scenario, and everything a reader decides on.
 *
 * <p>A snapshot is the unit of consistency across profiles. The chain of a single profile lives in
 * {@code md:Model.Supersedes} and is what a difference applies along; a snapshot says which model of
 * <em>every</em> profile belongs together, which is what makes "load version 1.1" a well-defined request even when
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
 * @param scenario    the scenario the snapshot belongs to
 * @param iri         the IRI of the snapshot node
 * @param version     the version label, for instance {@code "1.1"}
 * @param timestep    the canonical ISO instant the snapshot describes
 * @param timestepLabel the {@code HH:MM} rendering of {@link #timestep()} in the scenario's base offset, for
 *                     display only; see {@link #timestepLabel()}
 * @param kind        whether the snapshot is a root of full models or a difference on its parent
 * @param parent      the IRI of the snapshot this one derives from, {@code null} for the root
 * @param edge        which kind of link {@link #parent()} is
 * @param depth       how many snapshots lie between this one and the root of the scenario
 * @param hasFull     whether a materialisation can start here without walking further up; see {@link #hasFull()}
 * @param fast        whether every difference member is fast-route capable; see {@link #fast()}
 * @param state       the effective model identifier per profile
 * @param members     the models this snapshot adds
 * @param fullModels  the model identifier with a full graph per profile
 * @param timestepRoot the IRI of the root snapshot of this snapshot's timestep
 * @param created     when the node was written
 * @param description the free text the writer attached, or {@code null}
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record SnapshotInfo(String scenario, String iri, String version, String timestep, String timestepLabel,
                           Kind kind,
                           String parent, EdgeKind edge, int depth, boolean hasFull, boolean fast,
                           Map<CgmesSubset, String> state, List<String> members,
                           Map<CgmesSubset, String> fullModels, String timestepRoot, ZonedDateTime created,
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
        /** No parent: the root of the scenario. */
        NONE,
        /** The previous version of the same timestep. */
        VERSION,
        /** The base-chain snapshot a timestep root was derived from. */
        TIMESTEP
    }

    /**
     * @param scenario     see {@link #scenario()}
     * @param iri          see {@link #iri()}
     * @param version      see {@link #version()}
     * @param timestep     see {@link #timestep()}
     * @param timestepLabel see {@link #timestepLabel()}
     * @param kind         see {@link #kind()}
     * @param parent       see {@link #parent()}
     * @param edge         see {@link #edge()}
     * @param depth        see {@link #depth()}
     * @param hasFull      see {@link #hasFull()}
     * @param fast         see {@link #fast()}
     * @param state        see {@link #state()}
     * @param members      see {@link #members()}
     * @param fullModels   see {@link #fullModels()}
     * @param timestepRoot see {@link #timestepRoot()}
     * @param created      see {@link #created()}
     * @param description  see {@link #description()}
     */
    public SnapshotInfo {
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(iri);
        Objects.requireNonNull(version);
        Objects.requireNonNull(timestep);
        state = Map.copyOf(state);
        members = List.copyOf(members);
        fullModels = Map.copyOf(fullModels);
    }

    /**
     * The {@code HH:MM} rendering of {@link #timestep()} in the scenario's base offset, for display only.
     *
     * <p>The address of a snapshot is {@code (scenario, timestep, version)} and {@link #timestep()} is the key: a
     * canonical ISO instant in UTC, which is what every lookup matches on. This is its local-time rendering,
     * computed once when the snapshot is written and never compared against anything &mdash; a label a caller
     * passes is resolved into an instant before a query is sent. Two scenarios describing two days can therefore
     * show the same rendering for two different moments.</p>
     *
     * <p>It is empty when the stored node carries no rendering at all, which nothing this release writes
     * produces; a caller with nothing to show falls back to the timestep. A node written before the term was
     * renamed from {@code pdb:label} is read unchanged.</p>
     *
     * @return the rendering, or an empty string
     */
    @Override
    public String timestepLabel() {
        return timestepLabel;
    }

    /**
     * Whether a materialisation can start at this snapshot instead of walking further up the chain.
     *
     * <p><strong>Derived, never stored.</strong> It is exactly {@code !fullModels().isEmpty()}: a snapshot can
     * start a materialisation when it names a full model, and naming one is what {@code pdb:full} does. A root
     * names its instance files, a difference snapshot names nothing until a {@link Checkpoint} folds its chain
     * into copies and adds the links. Databases written by an earlier release carry a {@code pdb:hasFull} boolean
     * beside the links: it is ignored, and a stale one cannot make this answer wrong.</p>
     *
     * @return whether this snapshot names at least one full model
     */
    @Override
    public boolean hasFull() {
        return hasFull;
    }

    /**
     * Whether a client can walk onto this snapshot without rebuilding its network.
     *
     * <p><strong>Derived, never stored.</strong> The fast-route capability is a property of a difference model
     * ({@code pdb:fastPredicatesOnly}, {@link StoredModel#fastPredicatesOnly()}) and is written once, there. This
     * value is the conjunction over the difference members of the snapshot, read in the same request that returned
     * the snapshot; a snapshot with no difference member &mdash; a root, or a snapshot of full models &mdash; is
     * {@code true}. Databases written by an earlier release carry a {@code pdb:fast} triple on the snapshot node:
     * it is ignored, and a stale one cannot make this answer wrong.</p>
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
        return new SnapshotRef(scenario, version, timestep);
    }

    /**
     * @return whether this snapshot is the root of its scenario
     */
    public boolean isRoot() {
        return parent == null;
    }

    @Override
    public String toString() {
        return "(" + scenario + ", " + timestep + ", " + version + ")";
    }
}
