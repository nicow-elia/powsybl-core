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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * <p>One component of {@link SnapshotInfo} is computed here rather than read: {@code fast}, from the bindings
 * above.</p>
 *
 * <p>Unknown predicates are ignored on purpose: a metadata graph written by a later release of the same schema has
 * to be readable by this one, and a schema only ever grows. The one predicate that is <em>not</em> ignored is the
 * key of the earlier addressing schema, {@code pdb:timestep}: a node carrying it would be decoded into nothing, or
 * worse into a wrong address, so it is refused ({@link #legacySchema}).</p>
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
     * The key term of the earlier {@code (scenario, timestep, version)} schema.
     *
     * <p>Never written by this release, and never read as data: a snapshot node carrying it belongs to a store this
     * release refuses, see {@link #legacySchema}.</p>
     */
    static final String LEGACY_TIMESTEP = RdfDbVocabulary.NS + "timestep";

    /** The per-scenario node class of the earlier schema, which held its base timestep and offset. */
    static final String LEGACY_CATALOG = RdfDbVocabulary.NS + "Catalog";

    private SnapshotRows() {
    }

    /**
     * The refusal of a scenario written in an addressing schema this release does not read.
     *
     * <p>There is no migration: the earlier schema lived inside one unreleased change, and re-ingesting a day costs
     * minutes. The message says what was found and what to do.</p>
     *
     * @param scenario the scenario
     * @param found    what the metadata graph carries instead of {@code pdb:schema 3}
     * @return the exception to throw
     */
    static RdfDbException legacySchema(String scenario, String found) {
        return new RdfDbException("scenario '" + scenario + "' was written by the (scenario, timestep, version)"
                + " schema of an earlier release (" + found + "); this release reads only stores of schema "
                + RdfDbVocabulary.SCHEMA_VERSION + ", addressed by (scenario, modelling authority, timestamp,"
                + " version). There is no migration: clear the scenario (RdfDbConnection.clear) and re-ingest it");
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
            if (LEGACY_TIMESTEP.equals(p.stringValue())) {
                throw legacySchema(scenario, "a snapshot node keyed by pdb:timestep");
            }
            builders.computeIfAbsent(s.stringValue(), Builder::new)
                    .add(p.stringValue(), o, row.get("sub"), row.get(MEMBER_KIND), row.get(MEMBER_FAST));
        }
        Map<String, SnapshotInfo> snapshots = new LinkedHashMap<>();
        builders.forEach((iri, builder) -> builder.build(scenario).ifPresent(info -> snapshots.put(iri, info)));
        return snapshots;
    }

    /** The natural order of a listing: by modelling authority, by timestamp, then by depth in the chain. */
    static Comparator<SnapshotInfo> byTimestampAndDepth() {
        return Comparator.comparing(SnapshotInfo::modellingAuthority)
                .thenComparing(SnapshotInfo::timestamp)
                .thenComparingInt(SnapshotInfo::depth)
                .thenComparingInt(SnapshotInfo::version);
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

    static long longOf(Value value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return value instanceof Literal literal ? literal.longValue() : Long.parseLong(value.stringValue());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static int intOf(Value value) {
        try {
            return value instanceof Literal literal ? literal.intValue() : Integer.parseInt(value.stringValue());
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /** The instant of an {@code xsd:dateTime} literal, whatever offset the backend writes it back with. */
    static Instant instantOf(Value value) {
        try {
            return OffsetDateTime.parse(value.stringValue()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static ZonedDateTime dateOf(Value value) {
        if (value == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value.stringValue());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The text of one binding of a row, {@code null} when unbound. */
    static String text(Map<String, Value> row, String binding) {
        Value value = row.get(binding);
        return value == null ? null : value.stringValue();
    }

    /** The predicate-object pairs of one snapshot node, before it becomes a {@link SnapshotInfo}. */
    private static final class Builder {

        private final String iri;
        private String modellingAuthority;
        private Instant timestamp;
        private int version;
        private String kind;
        private String parent;
        private String edge;
        private String timestampRoot;
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
                case RdfDbVocabulary.MODELLING_AUTHORITY -> modellingAuthority = object.stringValue();
                case RdfDbVocabulary.TIMESTAMP -> timestamp = instantOf(object);
                case RdfDbVocabulary.VERSION -> version = intOf(object);
                case RdfDbVocabulary.KIND -> kind = object.stringValue();
                case RdfDbVocabulary.PARENT -> parent = object.stringValue();
                case RdfDbVocabulary.EDGE -> edge = object.stringValue();
                case RdfDbVocabulary.TIMESTAMP_ROOT -> timestampRoot = object.stringValue();
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

        Optional<SnapshotInfo> build(String scenario) {
            if (!isSnapshot || modellingAuthority == null || timestamp == null || version < 1) {
                return Optional.empty();
            }
            SnapshotInfo.Kind snapshotKind = RdfDbVocabulary.FULL.equals(kind)
                    ? SnapshotInfo.Kind.FULL : SnapshotInfo.Kind.DIFF;
            SnapshotInfo.EdgeKind edgeKind;
            if (parent == null) {
                edgeKind = SnapshotInfo.EdgeKind.NONE;
            } else if (RdfDbVocabulary.TIMESTAMP_EDGE.equals(edge)) {
                edgeKind = SnapshotInfo.EdgeKind.TIMESTAMP;
            } else {
                edgeKind = SnapshotInfo.EdgeKind.VERSION;
            }
            return Optional.of(new SnapshotInfo(scenario, iri, modellingAuthority, timestamp, version, snapshotKind,
                    parent, edgeKind, depth, fast, state, members, full, timestampRoot == null ? iri : timestampRoot,
                    created, description));
        }
    }
}
