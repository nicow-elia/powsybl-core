/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

/**
 * The RDF vocabulary the metadata graph of a scenario is written in.
 *
 * <p>A stored model &mdash; a CGMES instance file that was uploaded, or a difference model that was recorded
 * &mdash; is one node of that graph. Everything a reader has to know about it is a property of that node: which
 * profile it describes, which named graph holds its statements, which model it supersedes, whether it can be
 * applied to a live network without rebuilding it.</p>
 *
 * <p>Two namespaces meet here. The model description terms of IEC 61970-552 ({@code md:}, {@code dm:}) are reused
 * <em>verbatim</em>, because a stored model is a CGMES model and its header is the header the file carried: a
 * reader that knows CGMES can read the metadata graph without knowing powsybl. Everything that is about the
 * <em>storage</em> rather than about the model &mdash; which graph, how many statements, how deep in the chain
 * &mdash; lives in the powsybl namespace {@value #NS}.</p>
 *
 * <p>The terms are {@code String}s rather than RDF4J {@code IRI}s: they are written into SPARQL text far more often
 * than they are compared as values, and an {@code IRI} is one {@code createIRI} call away where it is needed.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class RdfDbVocabulary {

    /** The namespace of the storage terms of this library. */
    public static final String NS = "http://powsybl.org/ns/rdfdb#";

    /** The IEC 61970-552 model description namespace, which carries the header of every CGMES model. */
    public static final String MD_NS = "http://iec.ch/TC57/61970-552/ModelDescription/1#";

    /** The IEC 61970-552 difference model namespace. */
    public static final String DM_NS = "http://iec.ch/TC57/61970-552/DifferenceModel/1#";

    /** The RDF namespace. */
    public static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    /** The XML Schema datatype namespace. */
    public static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";

    /** {@code rdf:type}. */
    public static final String RDF_TYPE = RDF_NS + "type";

    /** {@code md:FullModel}, the class of an uploaded instance file. */
    public static final String FULL_MODEL = MD_NS + "FullModel";

    /** {@code dm:DifferenceModel}, the class of a stored difference. */
    public static final String DIFFERENCE_MODEL = DM_NS + "DifferenceModel";

    /** Value of {@link #KIND} for an uploaded instance file. */
    public static final String FULL = NS + "Full";

    /** Value of {@link #KIND} for a stored difference. */
    public static final String DIFF = NS + "Diff";

    /** Whether the node is a full model or a difference, as {@link #FULL} or {@link #DIFF}. */
    public static final String KIND = NS + "kind";

    /** The CGMES profile of the model, as {@code CgmesSubset.getIdentifier()}: {@code EQ}, {@code SSH}, … */
    public static final String SUBSET = NS + "subset";

    /** The named graph holding the statements of a full model. */
    public static final String GRAPH = NS + "graph";

    /** The named graph holding the forward statements of a difference. */
    public static final String FORWARD_GRAPH = NS + "forwardGraph";

    /** The named graph holding the reverse statements of a difference. */
    public static final String REVERSE_GRAPH = NS + "reverseGraph";

    /**
     * Whether every property of a difference is one the in-place update workflow reads.
     *
     * <p>This is the one place the fast-route capability is stored. A snapshot is fast when every difference it
     * adds says so, and that conjunction is derived where it is needed ({@link SnapshotInfo#fast()}) rather than
     * written a second time onto the snapshot node. Stores written before this release carry a
     * {@code pdb:fast} triple on their snapshot nodes; it is never read, never rewritten and never deleted.</p>
     */
    public static final String FAST_PREDICATES_ONLY = NS + "fastPredicatesOnly";

    /**
     * Whether every statement of a difference writes state IIDM stores per network variant.
     *
     * <p>Written next to {@link #FAST_PREDICATES_ONLY} when the difference is stored. A node that does not carry
     * it was written before the flag existed, and a planner then proceeds and lets the network aware check at
     * apply time decide: being optimistic costs one fetch, and correctness never depends on the stored value.</p>
     */
    public static final String VARIANT_SAFE = NS + "variantSafe";

    /** How many statements the model holds; forward plus reverse for a difference. */
    public static final String TRIPLE_COUNT = NS + "tripleCount";

    /** The IRI prefix the subjects of the model carry before {@code _<mRID>}, for instance {@code http://x/#}. */
    public static final String SUBJECT_BASE = NS + "subjectBase";

    /** The CIM namespace the properties of the model live in. */
    public static final String CIM_NAMESPACE = NS + "cimNamespace";

    /** How many differences lie between this model and the full model it descends from; 0 for a full model. */
    public static final String CHAIN_DEPTH = NS + "chainDepth";

    /** When the node was written into the database. */
    public static final String CREATED = NS + "created";

    /** The raw, undecoded name of the scenario the model belongs to. */
    public static final String SCENARIO = NS + "scenario";

    /** {@code md:Model.version}. */
    public static final String MODEL_VERSION = MD_NS + "Model.version";

    /** {@code md:Model.scenarioTime}. */
    public static final String MODEL_SCENARIO_TIME = MD_NS + "Model.scenarioTime";

    /** {@code md:Model.created}. */
    public static final String MODEL_CREATED = MD_NS + "Model.created";

    /** {@code md:Model.description}. */
    public static final String MODEL_DESCRIPTION = MD_NS + "Model.description";

    /** {@code md:Model.modelingAuthoritySet}. */
    public static final String MODEL_MODELING_AUTHORITY_SET = MD_NS + "Model.modelingAuthoritySet";

    /** {@code md:Model.profile}. */
    public static final String MODEL_PROFILE = MD_NS + "Model.profile";

    /** {@code md:Model.DependentOn}. */
    public static final String MODEL_DEPENDENT_ON = MD_NS + "Model.DependentOn";

    /** {@code md:Model.Supersedes}, which is the version chain of a profile inside a scenario. */
    public static final String MODEL_SUPERSEDES = MD_NS + "Model.Supersedes";

    // ------------------------------------------------------------------ versioning (follow-up WP3, schema v2)

    /** {@code pdb:Snapshot}, the class of a consistent grid state addressed by (scenario, timestep, version). */
    public static final String SNAPSHOT_CLASS = NS + "Snapshot";

    /** {@code pdb:Catalog}, the class of the single per-scenario node holding its base timestep and offset. */
    public static final String CATALOG_CLASS = NS + "Catalog";

    /** {@code pdb:Materialized}, the class of a model node whose graph a checkpoint copied and folded. */
    public static final String MATERIALIZED = NS + "Materialized";

    /** Value of {@link #EDGE} for the link from a snapshot to the previous version of the same timestep. */
    public static final String VERSION_EDGE = NS + "VersionEdge";

    /** Value of {@link #EDGE} for the link from a timestep root to the base-chain snapshot it derives from. */
    public static final String TIMESTEP_EDGE = NS + "TimestepEdge";

    /** The user-facing version label of a snapshot, for instance {@code "1.1"}. */
    public static final String VERSION = NS + "version";

    /**
     * The canonical ISO instant a snapshot describes, equal to {@code md:Model.scenarioTime} of its members.
     *
     * <p>This is the <strong>key</strong>: one third of the unique {@code (scenario, timestep, version)} address,
     * and the only form of the moment a lookup ever matches on.</p>
     */
    public static final String TIMESTEP = NS + "timestep";

    /**
     * The {@code HH:MM} rendering of {@link #TIMESTEP} in the scenario's base offset, for display only.
     *
     * <p>Derived at write time by {@code Timesteps.label(timestep, baseOffset)} and never matched on: a caller
     * that passes {@code "08:30"} has it resolved against the scenario's base day <em>before</em> any query is
     * sent, so two scenarios describing two days may carry the same rendering for two different instants.</p>
     *
     * <p>It was called {@code pdb:label} until this release, which was ambiguous in two directions &mdash;
     * {@code rdfs:label} names a thing, and a snapshot's <em>version</em> is also a free-form label. A store
     * written earlier carries the old term and is still read: the reader prefers this one and falls back to it.</p>
     */
    public static final String TIMESTEP_LABEL = NS + "timestepLabel";

    /** The snapshot this one was derived from, at most one. */
    public static final String PARENT = NS + "parent";

    /** Which kind of link {@link #PARENT} is: {@link #VERSION_EDGE} or {@link #TIMESTEP_EDGE}. */
    public static final String EDGE = NS + "edge";

    /** How many snapshots lie between this one and the root of its scenario; 0 for the root. */
    public static final String DEPTH = NS + "depth";

    /** The models that <em>define</em> the snapshot: the full models of a root, the differences of a diff. */
    public static final String MEMBER = NS + "member";

    /** The effective model per profile at this snapshot, that is what a reader has to reach to be "at" it. */
    public static final String STATE = NS + "state";

    /**
     * The models with a full graph a materialisation of this snapshot can start from, per profile.
     *
     * <p>Whether a snapshot can start one at all is <em>this link existing</em>, nothing else: there is no
     * {@code pdb:hasFull} boolean beside it, and {@link SnapshotInfo#hasFull()} is derived from the links. Stores
     * written before this release carry such a boolean; it is never read, never rewritten and never deleted.</p>
     */
    public static final String FULL_MODELS = NS + "full";

    /** The snapshot a model node belongs to. */
    public static final String SNAPSHOT = NS + "snapshot";

    /** The state model a {@link #MATERIALIZED} node carries the statements of. */
    public static final String OF_MODEL = NS + "ofModel";

    /** The root snapshot of the timestep a snapshot belongs to. */
    public static final String TIMESTEP_ROOT = NS + "timestepRoot";

    /** The base timestep of a scenario, on the per-scenario {@link #CATALOG_CLASS} node. */
    public static final String BASE_TIMESTEP = NS + "baseTimestep";

    /** The zone offset the labels of a scenario are written in, on the {@link #CATALOG_CLASS} node. */
    public static final String BASE_OFFSET = NS + "baseOffset";

    /** Free text a writer attached to a snapshot. */
    public static final String DESCRIPTION = NS + "description";

    /** The prefix block every query of this package starts with, so that no prefix is ever injected implicitly. */
    public static final String PREFIXES = "PREFIX pdb: <" + NS + "> "
            + "PREFIX md: <" + MD_NS + "> "
            + "PREFIX dm: <" + DM_NS + "> "
            + "PREFIX rdf: <" + RDF_NS + "> "
            + "PREFIX xsd: <" + XSD_NS + "> ";

    private RdfDbVocabulary() {
    }
}
