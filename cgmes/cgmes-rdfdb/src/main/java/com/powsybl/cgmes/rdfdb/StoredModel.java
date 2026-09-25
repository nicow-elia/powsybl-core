/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * One node of the metadata graph: a CGMES model the database holds, full or difference.
 *
 * <p>This is the whole of what a client needs to decide what to do with a stored model, in one round trip: which
 * profile it describes, which named graphs carry its statements, which model it supersedes, and &mdash; the field
 * that decides the route of an update &mdash; whether every property it states is one the in-place update
 * workflow can apply.</p>
 *
 * <p>A model is identified by {@code (scenario, id)}, never by {@code id} alone: the same instance file uploaded
 * into two scenarios is two independent models with two chains, and nothing in this package resolves an identifier
 * across scenarios except the diagnostics of {@link ModelCatalog#scenarioOf(String)}.</p>
 *
 * @param scenario             the raw name of the scenario the model belongs to
 * @param id                   the CGMES model identifier, typically a {@code urn:uuid:} URI
 * @param subset               the CGMES profile the model describes
 * @param kind                 whether it is an uploaded instance file or a recorded difference
 * @param graph                the named graph of a {@link Kind#FULL} model, {@code null} for a difference
 * @param forwardGraph         the named graph holding the forward statements of a difference, {@code null} for a
 *                             full model
 * @param reverseGraph         the named graph holding the reverse statements of a difference, {@code null} for a
 *                             full model
 * @param version              {@code md:Model.version}
 * @param description          {@code md:Model.description}, or {@code null}
 * @param scenarioTime         {@code md:Model.scenarioTime}, or {@code null}
 * @param created              {@code md:Model.created}, or {@code null}
 * @param modelingAuthoritySet {@code md:Model.modelingAuthoritySet}, or {@code null}
 * @param profiles             {@code md:Model.profile}
 * @param dependentOn          {@code md:Model.DependentOn}
 * @param supersedes           {@code md:Model.Supersedes}, which inside a scenario is the version chain
 * @param fastPredicatesOnly   whether every property of a difference is one an update query reads, decided by
 *                             {@code FastRouteCapabilities} when the difference was written. Always {@code false}
 *                             for a full model, which is never applied as a delta
 * @param tripleCount          how many statements the model holds, forward plus reverse for a difference
 * @param subjectBase          the IRI prefix the subjects carry before {@code _<mRID>}, or the empty string when
 *                             the model writes its subjects as absolute IRIs
 * @param cimNamespace         the CIM namespace the properties live in
 * @param chainDepth           how many differences lie between this model and the full model it descends from
 * @param variantSafe          whether every statement of a difference writes state IIDM stores per network
 *                             variant, decided by {@code FastRouteCapabilities.checkVariantSafe} when the
 *                             difference was written. {@code null} means <em>unknown</em>: a store written before
 *                             this flag existed says nothing, and a planner then proceeds optimistically and lets
 *                             the network aware check at apply time decide. Correctness never depends on it
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record StoredModel(String scenario, String id, CgmesSubset subset, StoredModel.Kind kind, String graph,
                          String forwardGraph, String reverseGraph, int version, String description,
                          ZonedDateTime scenarioTime, ZonedDateTime created, String modelingAuthoritySet,
                          List<String> profiles, List<String> dependentOn, List<String> supersedes,
                          boolean fastPredicatesOnly, long tripleCount, String subjectBase, String cimNamespace,
                          int chainDepth, Boolean variantSafe) {

    /** What a stored model is. */
    public enum Kind {
        /** An instance file that was uploaded; its statements are a complete model of its profile. */
        FULL,
        /** A recorded change; its statements are the state before and after, and it applies on one other model. */
        DIFF
    }

    /**
     * @param scenario             see {@link #scenario()}
     * @param id                   see {@link #id()}
     * @param subset               see {@link #subset()}
     * @param kind                 see {@link #kind()}
     * @param graph                see {@link #graph()}
     * @param forwardGraph         see {@link #forwardGraph()}
     * @param reverseGraph         see {@link #reverseGraph()}
     * @param version              see {@link #version()}
     * @param description          see {@link #description()}
     * @param scenarioTime         see {@link #scenarioTime()}
     * @param created              see {@link #created()}
     * @param modelingAuthoritySet see {@link #modelingAuthoritySet()}
     * @param profiles             see {@link #profiles()}
     * @param dependentOn          see {@link #dependentOn()}
     * @param supersedes           see {@link #supersedes()}
     * @param fastPredicatesOnly   see {@link #fastPredicatesOnly()}
     * @param tripleCount          see {@link #tripleCount()}
     * @param subjectBase          see {@link #subjectBase()}
     * @param cimNamespace         see {@link #cimNamespace()}
     * @param chainDepth           see {@link #chainDepth()}
     * @param variantSafe          see {@link #variantSafe()}
     */
    public StoredModel {
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(id);
        Objects.requireNonNull(subset);
        Objects.requireNonNull(kind);
        profiles = List.copyOf(profiles);
        dependentOn = List.copyOf(dependentOn);
        supersedes = List.copyOf(supersedes);
        subjectBase = subjectBase == null ? "" : subjectBase;
    }

    /**
     * A model of a store that does not carry the variant-safety flag, which is what every store written before it
     * existed looks like.
     *
     * @param scenario             see {@link #scenario()}
     * @param id                   see {@link #id()}
     * @param subset               see {@link #subset()}
     * @param kind                 see {@link #kind()}
     * @param graph                see {@link #graph()}
     * @param forwardGraph         see {@link #forwardGraph()}
     * @param reverseGraph         see {@link #reverseGraph()}
     * @param version              see {@link #version()}
     * @param description          see {@link #description()}
     * @param scenarioTime         see {@link #scenarioTime()}
     * @param created              see {@link #created()}
     * @param modelingAuthoritySet see {@link #modelingAuthoritySet()}
     * @param profiles             see {@link #profiles()}
     * @param dependentOn          see {@link #dependentOn()}
     * @param supersedes           see {@link #supersedes()}
     * @param fastPredicatesOnly   see {@link #fastPredicatesOnly()}
     * @param tripleCount          see {@link #tripleCount()}
     * @param subjectBase          see {@link #subjectBase()}
     * @param cimNamespace         see {@link #cimNamespace()}
     * @param chainDepth           see {@link #chainDepth()}
     */
    public StoredModel(String scenario, String id, CgmesSubset subset, StoredModel.Kind kind, String graph,
                       String forwardGraph, String reverseGraph, int version, String description,
                       ZonedDateTime scenarioTime, ZonedDateTime created, String modelingAuthoritySet,
                       List<String> profiles, List<String> dependentOn, List<String> supersedes,
                       boolean fastPredicatesOnly, long tripleCount, String subjectBase, String cimNamespace,
                       int chainDepth) {
        this(scenario, id, subset, kind, graph, forwardGraph, reverseGraph, version, description, scenarioTime,
                created, modelingAuthoritySet, profiles, dependentOn, supersedes, fastPredicatesOnly, tripleCount,
                subjectBase, cimNamespace, chainDepth, null);
    }

    /**
     * Whether an in-place update of this difference is known to write only state IIDM stores per network variant.
     *
     * @return {@code false} when it certainly is not, {@code true} when it certainly is, and {@code null} when the
     *         store does not say
     */
    public boolean isVariantUnsafe() {
        return Boolean.FALSE.equals(variantSafe);
    }

    /**
     * The difference model header this node describes.
     *
     * <p>For a difference it is the header the model was written with, minus the creation time of the database
     * node itself. For a full model it is the header of the instance file, which is what a composed difference
     * targeting this model inherits its dependencies and its modeling authority from.</p>
     *
     * @return the header
     */
    public DifferenceModelHeader toHeader() {
        return DifferenceModelHeader.builder(id, subset, cimNamespace == null ? "" : cimNamespace)
                .version(version)
                .description(description)
                .scenarioTime(scenarioTime)
                .created(created)
                .modelingAuthoritySet(modelingAuthoritySet)
                .profiles(profiles)
                .dependentOn(dependentOn)
                .supersedes(supersedes)
                .build();
    }

    /**
     * @return whether this node is a recorded difference
     */
    public boolean isDiff() {
        return kind == Kind.DIFF;
    }
}
