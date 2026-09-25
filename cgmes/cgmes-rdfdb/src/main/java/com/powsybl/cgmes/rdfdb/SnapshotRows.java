/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;

import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turning the rows of a snapshot query into {@link SnapshotInfo} objects.
 *
 * <p>Every query of the versioning layer that returns snapshots returns the same shape &mdash; one row per
 * predicate-object pair of the snapshot node, plus the profile of the object when that object is a model node
 * &mdash; so grouping them is one piece of code rather than one per query. A profile is needed because
 * {@code pdb:state} and {@code pdb:full} are keyed by profile and RDF has no way of saying so in the object
 * itself.</p>
 *
 * <p>A row may carry two further optional bindings describing the object rather than the snapshot: {@code mkind},
 * its {@code pdb:kind}, and {@code mfast}, its {@code pdb:fastPredicatesOnly}. Together they are what
 * {@link SnapshotInfo#fast()} is derived from, and every query that returns snapshots asks for them in the same
 * request that returns the rows &mdash; the flag lives on the member model and is read from there, never from the
 * snapshot node.</p>
 *
 * <p>Two components of {@link SnapshotInfo} are computed here rather than read: {@code fast} from the bindings
 * above, and {@code hasFull} from the {@code pdb:full} links the rows already carry &mdash; a snapshot can start a
 * materialisation exactly when it names a full model, so the boolean earlier releases stored beside the links is
 * not read.</p>
 *
 * <p>Unknown predicates are ignored on purpose: a metadata graph written by a later release has to be readable by
 * this one, and the schema only ever grows. That is also what makes the two retired booleans of the snapshot node,
 * {@code pdb:fast} and {@code pdb:hasFull}, harmless in a store written before this release.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class SnapshotRows {

    /**
     * The binding every snapshot query carries the {@code pdb:fastPredicatesOnly} of the row's object in.
     *
     * <p>Named the same in all of them on purpose: the plan query of {@link VersionGraph} already selected it for
     * the difference models it resolves, so the listing queries of {@link SnapshotCatalog} ask for it under the
     * same name and one grouping step serves both. The two are not bound under exactly the same condition &mdash;
     * the plan query binds it only together with the forward graph, the reverse graph, the subject base, the CIM
     * namespace and the chain depth of the model, the listing queries bind it whenever the flag exists &mdash; but
     * a node this layer writes carries either all of them or none, so on a store written here the two agree. A
     * difference node that carried none of them would not become a plan step at all, it would be refused by
     * {@code VersionGraph.resolve}; in a listing it is caught by {@link #MEMBER_KIND}.</p>
     */
    static final String MEMBER_FAST = "mfast";

    /**
     * The binding a snapshot listing carries the {@code pdb:kind} of the row's object in.
     *
     * <p>It exists so that a missing {@link #MEMBER_FAST} on a <em>difference</em> member is read as "not fast",
     * which is how {@code ModelCatalog} and {@code VersionGraph} read an absent flag. Without it a hand-written or
     * foreign node typed {@code pdb:Diff} but lacking the flag would be taken for a full member and silently make
     * its snapshot look fast-route capable while the planner routes it FULL.</p>
     */
    static final String MEMBER_KIND = "mkind";

    /**
     * The {@code OPTIONAL} block a snapshot listing adds to bind {@link #MEMBER_KIND} and {@link #MEMBER_FAST}.
     *
     * <p>Both are functional properties of a model node, so this multiplies no row: the listing returns exactly
     * the rows it would return without it, with at most two more bindings on each of them.</p>
     */
    static final String MEMBER_FAST_CLAUSE = " OPTIONAL { ?o pdb:kind ?" + MEMBER_KIND
            + " OPTIONAL { ?o pdb:fastPredicatesOnly ?" + MEMBER_FAST + " } } ";

    /**
     * What {@link RdfDbVocabulary#TIMESTEP_LABEL} was called before this release.
     *
     * <p>Read, never written. A metadata graph filled by an earlier release names the display rendering of a
     * timestep {@code pdb:label}, and a listing of it has to keep showing {@code "08:30"} rather than nothing.</p>
     */
    private static final String LEGACY_LABEL = RdfDbVocabulary.NS + "label";

    private SnapshotRows() {
    }

    /**
     * Group query rows into snapshots.
     *
     * @param scenario the scenario the rows belong to
     * @param rows     the rows, with the bindings {@code s}, {@code p}, {@code o} and optionally {@code sub},
     *                 {@code mkind} and {@code mfast}
     * @param subject  the name of the binding carrying the snapshot node
     * @return the snapshots, keyed by IRI, in the order the rows first named them
     */
    static Map<String, SnapshotInfo> group(String scenario, List<Map<String, Value>> rows, String subject) {
        Map<String, Builder> builders = new LinkedHashMap<>();
        for (Map<String, Value> row : rows) {
            Value s = row.get(subject);
            Value p = row.get("p");
            Value o = row.get("o");
            if (s == null || p == null || o == null) {
                continue;
            }
            builders.computeIfAbsent(s.stringValue(), Builder::new)
                    .add(p.stringValue(), o, row.get("sub"), row.get(MEMBER_KIND), row.get(MEMBER_FAST));
        }
        Map<String, SnapshotInfo> snapshots = new LinkedHashMap<>();
        builders.forEach((iri, builder) -> builder.build(scenario).ifPresent(info -> snapshots.put(iri, info)));
        return snapshots;
    }

    /** The natural order of a listing: by timestep, then by depth in the chain. */
    static Comparator<SnapshotInfo> byTimestepAndDepth() {
        return Comparator.comparing(SnapshotInfo::timestep)
                .thenComparingInt(SnapshotInfo::depth)
                .thenComparing(SnapshotInfo::version);
    }

    static CgmesSubset subsetOf(Value value) {
        if (value == null) {
            return null;
        }
        String identifier = value.stringValue();
        for (CgmesSubset subset : CgmesSubset.values()) {
            if (subset.getIdentifier().equals(identifier)) {
                return subset;
            }
        }
        return null;
    }

    static boolean booleanOf(Value value) {
        return value instanceof Literal literal ? literal.booleanValue() : Boolean.parseBoolean(value.stringValue());
    }

    /** The predicate-object pairs of one snapshot node, before it becomes a {@link SnapshotInfo}. */
    private static final class Builder {

        private final String iri;
        private String version;
        private String timestep;
        private String timestepLabel;
        /** What a store written before the term was renamed carries, used only when the new one is absent. */
        private String legacyLabel;
        private String kind;
        private String parent;
        private String edge;
        private String timestepRoot;
        private String description;
        private int depth;
        /** The conjunction over the difference members seen so far; a snapshot without any is fast. */
        private boolean fast = true;
        private ZonedDateTime created;
        private final List<String> members = new ArrayList<>();
        private final Map<CgmesSubset, String> state = new EnumMap<>(CgmesSubset.class);
        private final Map<CgmesSubset, String> full = new EnumMap<>(CgmesSubset.class);
        private boolean isSnapshot;

        Builder(String iri) {
            this.iri = iri;
        }

        void add(String predicate, Value object, Value subsetValue, Value memberKind, Value memberFast) {
            switch (predicate) {
                case RdfDbVocabulary.RDF_TYPE -> isSnapshot |= RdfDbVocabulary.SNAPSHOT_CLASS.equals(
                        object.stringValue());
                case RdfDbVocabulary.VERSION -> version = object.stringValue();
                case RdfDbVocabulary.TIMESTEP -> timestep = object.stringValue();
                case RdfDbVocabulary.TIMESTEP_LABEL -> timestepLabel = object.stringValue();
                case LEGACY_LABEL -> legacyLabel = object.stringValue();
                case RdfDbVocabulary.KIND -> kind = object.stringValue();
                case RdfDbVocabulary.PARENT -> parent = object.stringValue();
                case RdfDbVocabulary.EDGE -> edge = object.stringValue();
                case RdfDbVocabulary.TIMESTEP_ROOT -> timestepRoot = object.stringValue();
                case RdfDbVocabulary.DESCRIPTION -> description = object.stringValue();
                case RdfDbVocabulary.DEPTH -> depth = intOf(object);
                case RdfDbVocabulary.CREATED -> created = dateOf(object);
                case RdfDbVocabulary.MEMBER -> {
                    members.add(object.stringValue());
                    fast &= isFastMember(memberKind, memberFast);
                }
                case RdfDbVocabulary.STATE -> put(state, object, subsetValue);
                case RdfDbVocabulary.FULL_MODELS -> put(full, object, subsetValue);
                default -> {
                    // A term of a later schema version, or a term of the model header this view does not read
                }
            }
        }

        /**
         * The display rendering of the timestep, preferring the current term over the retired one.
         *
         * <p>Empty when the node carries neither. It is not computed from the timestep here: that needs the base
         * offset of the scenario, which this class does not hold and cannot fetch without a request &mdash; and a
         * listing must not grow one. Callers that need something to show fall back to the timestep itself, which
         * is what {@code VariantBulkLoader} has always done.</p>
         */
        private String labelOrEmpty() {
            if (timestepLabel != null) {
                return timestepLabel;
            }
            return legacyLabel == null ? "" : legacyLabel;
        }

        /**
         * What one member of a snapshot contributes to {@link SnapshotInfo#fast()}.
         *
         * <p>Only a difference has an opinion. A full model of a root, or a materialised checkpoint copy, is not
         * something an in-place update applies, so it neither allows nor forbids the fast route and answers
         * {@code true} &mdash; the neutral element of the conjunction. A difference answers what
         * {@code pdb:fastPredicatesOnly} says, and a difference <em>without</em> the flag answers {@code false},
         * which is the reading of {@code ModelCatalog} and {@code VersionGraph.storedModel}: an unknown
         * capability is not a capability, and a listing must not promise a route the planner refuses.</p>
         *
         * @param kind the {@code pdb:kind} of the member, or {@code null} when the query did not ask for it
         * @param flag the {@code pdb:fastPredicatesOnly} of the member, or {@code null} when it has none
         */
        private static boolean isFastMember(Value kind, Value flag) {
            if (kind != null) {
                return !RdfDbVocabulary.DIFF.equals(kind.stringValue()) || flag != null && booleanOf(flag);
            }
            // The query did not ask for the kind (the plan query of VersionGraph): there only a difference node
            // binds the flag at all, and a difference that bound none of its storage terms never becomes a step
            return flag == null || booleanOf(flag);
        }

        private static void put(Map<CgmesSubset, String> map, Value object, Value subsetValue) {
            CgmesSubset subset = subsetOf(subsetValue);
            if (subset != null) {
                map.put(subset, object.stringValue());
            }
        }

        java.util.Optional<SnapshotInfo> build(String scenario) {
            if (!isSnapshot || version == null || timestep == null) {
                return java.util.Optional.empty();
            }
            SnapshotInfo.Kind snapshotKind = RdfDbVocabulary.FULL.equals(kind)
                    ? SnapshotInfo.Kind.FULL : SnapshotInfo.Kind.DIFF;
            SnapshotInfo.EdgeKind edgeKind;
            if (parent == null) {
                edgeKind = SnapshotInfo.EdgeKind.NONE;
            } else if (RdfDbVocabulary.TIMESTEP_EDGE.equals(edge)) {
                edgeKind = SnapshotInfo.EdgeKind.TIMESTEP;
            } else {
                edgeKind = SnapshotInfo.EdgeKind.VERSION;
            }
            return java.util.Optional.of(new SnapshotInfo(scenario, iri, version, timestep,
                    labelOrEmpty(), snapshotKind, parent, edgeKind, depth, !full.isEmpty(), fast,
                    state, members, full, timestepRoot == null ? iri : timestepRoot, created, description));
        }

        private static int intOf(Value value) {
            try {
                return value instanceof Literal literal ? literal.intValue()
                        : Integer.parseInt(value.stringValue());
            } catch (IllegalArgumentException e) {
                return 0;
            }
        }

        private static ZonedDateTime dateOf(Value value) {
            try {
                return ZonedDateTime.parse(value.stringValue());
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }
}
