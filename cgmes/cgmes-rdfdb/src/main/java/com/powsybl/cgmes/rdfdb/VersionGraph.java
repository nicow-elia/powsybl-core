/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.iidm.network.Network;
import org.eclipse.rdf4j.model.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The one query that answers "can I get from here to there by applying differences?", and the arithmetic on it.
 *
 * <h2>One round trip</h2>
 * <p>Both chains come back in a single request. A {@code UNION} binds the start of each side &mdash; the snapshot
 * the network is at, and the snapshot the caller asked for, resolved by {@code (timestep, version)} in the same
 * query &mdash; and a {@code pdb:parent*} property path walks each of them up to the root. The client then finds
 * the lowest common ancestor, which on a chain that never branches is simply the deepest snapshot both sides
 * reached, and reads the path off the two chains.</p>
 *
 * <p>The cost is bounded by the <em>depth</em> of the two snapshots, not by how much the scenario holds: a
 * database with a thousand snapshots, or with ten other scenarios next to this one, answers the same query in the
 * same time, because the path walk starts at a bound node and every other scenario lives in a metadata graph this
 * query does not name.</p>
 *
 * <h2>Across scenarios there is no query</h2>
 * <p>A snapshot IRI carries its scenario, so a network at another scenario is answered with {@code FULL} and a
 * reason, and nothing is sent. That is not an optimisation but the design: two scenarios are two base grid models
 * and no difference relates them.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class VersionGraph {

    private static final String SIDE_A = "A";
    private static final String SIDE_B = "B";

    private final RdfDbConnection connection;
    private final String scenario;
    private final String metaGraph;
    private final SnapshotCatalog catalog;

    VersionGraph(RdfDbConnection connection, String scenario) {
        this.connection = Objects.requireNonNull(connection);
        this.scenario = RdfDbNames.checkScenario(scenario);
        this.metaGraph = RdfDbNames.metaGraph(scenario);
        this.catalog = connection.snapshots(scenario);
    }

    /**
     * @return the scenario this planner is bound to
     */
    public String scenario() {
        return scenario;
    }

    /**
     * Plan the way from one snapshot to another.
     *
     * @param fromSnapshotIri the IRI of the snapshot the network is at, or {@code null} when it is unknown
     * @param target          the address of the target
     * @param options         how long a chain the caller allows, and when a checkpoint is worth recommending
     * @return the plan
     */
    public UpdatePlan plan(String fromSnapshotIri, SnapshotRef target, RdfDbUpdateOptions options) {
        return plan(fromSnapshotIri, Map.of(), target, options);
    }

    /**
     * Plan the way from the state a network is at to a target.
     *
     * @param network the network
     * @param target  the address of the target
     * @param options how long a chain the caller allows
     * @return the plan
     */
    public UpdatePlan plan(Network network, SnapshotRef target, RdfDbUpdateOptions options) {
        catalog.check(target);
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        if (provenance != null && !scenario.equals(provenance.scenario())) {
            return crossScenario(provenance.scenario());
        }
        String from = provenance == null ? null : provenance.snapshot().orElse(null);
        Map<CgmesSubset, String> identity = from == null
                ? NetworkIdentity.modelIds(network) : Map.of();
        return plan(from, identity, target, options);
    }

    private UpdatePlan crossScenario(String networkScenario) {
        return new UpdatePlan(UpdatePlan.Kind.FULL, null, null, List.of(),
                List.of("network is at scenario '" + networkScenario + "', target is scenario '" + scenario
                        + "': diffs never cross scenarios"), 0, false, 0, Map.of());
    }

    /**
     * Plan the way to a target from a snapshot named by its IRI or by the models a network holds.
     *
     * @param fromSnapshotIri the IRI of the snapshot the network is at, or {@code null}
     * @param identity        the model identifier per profile the network holds, used when the IRI is unknown
     * @param target          the address of the target
     * @param options         how far the caller allows the plan to go
     * @return the plan
     */
    public UpdatePlan plan(String fromSnapshotIri, Map<CgmesSubset, String> identity, SnapshotRef target,
                           RdfDbUpdateOptions options) {
        catalog.check(target);
        RdfDbUpdateOptions effective = options == null ? new RdfDbUpdateOptions() : options;
        if (fromSnapshotIri != null && !scenario.equals(RdfDbNames.scenarioOf(fromSnapshotIri))) {
            return crossScenario(String.valueOf(RdfDbNames.scenarioOf(fromSnapshotIri)));
        }
        String from = fromSnapshotIri;
        if (from == null && !identity.isEmpty()) {
            from = catalog.byState(identity).map(SnapshotInfo::iri).orElse(null);
        }
        Sides sides = query(from, target);
        if (sides.b.isEmpty()) {
            throw new RdfDbException("scenario '" + scenario + "' holds no snapshot " + target);
        }
        SnapshotInfo b = sides.b.get(0);
        Map<CgmesSubset, String> targetState = b.state();
        if (from == null) {
            return new UpdatePlan(UpdatePlan.Kind.FULL, null, b.iri(), List.of(),
                    List.of("the network is at no snapshot of scenario '" + scenario + "': it has to be rebuilt"),
                    0, checkpointRecommended(sides.b, effective), distanceToFull(sides.b), targetState);
        }
        if (from.equals(b.iri())) {
            return new UpdatePlan(UpdatePlan.Kind.NOOP, from, b.iri(), List.of(), List.of(), 0,
                    checkpointRecommended(sides.b, effective), distanceToFull(sides.b), targetState);
        }
        if (sides.a.isEmpty()) {
            return new UpdatePlan(UpdatePlan.Kind.FULL, from, b.iri(), List.of(),
                    List.of("scenario '" + scenario + "' no longer holds the snapshot " + from), 0,
                    checkpointRecommended(sides.b, effective), distanceToFull(sides.b), targetState);
        }
        return path(sides.a, sides.b, sides.diffs, effective);
    }

    /**
     * How to build the data of a snapshot: per profile, the full graph to start from and the differences below it.
     *
     * @param snapshotIri the IRI of the snapshot to materialise
     * @return the plan
     */
    public MaterializationPlan materialization(String snapshotIri) {
        Objects.requireNonNull(snapshotIri);
        if (!scenario.equals(RdfDbNames.scenarioOf(snapshotIri))) {
            throw new RdfDbException("snapshot " + snapshotIri + " does not belong to scenario '" + scenario + "'");
        }
        Sides sides = query(null, snapshotIri);
        if (sides.b.isEmpty()) {
            throw new RdfDbException("scenario '" + scenario + "' holds no snapshot " + snapshotIri);
        }
        return materialization(sides.b, sides.fullGraphs, sides.diffs);
    }

    /**
     * How to build the data of a snapshot named by its address.
     *
     * @param ref the address
     * @return the plan
     */
    public MaterializationPlan materialization(SnapshotRef ref) {
        SnapshotInfo info = catalog.find(catalog.check(ref))
                .orElseThrow(() -> new RdfDbException("scenario '" + scenario + "' holds no snapshot " + ref));
        return materialization(info.iri());
    }

    /**
     * Build a materialisation plan from a chain that was already queried.
     *
     * @param chain      the chain of the target, deepest first
     * @param fullGraphs the graph of every full model named by the chain
     * @return the plan
     */
    MaterializationPlan materialization(List<SnapshotInfo> chain, Map<String, String> fullGraphs,
                                        Map<String, StoredModel> models) {
        SnapshotInfo target = chain.get(0);
        Map<CgmesSubset, MaterializationPlan.FullSource> start = new EnumMap<>(CgmesSubset.class);
        Map<CgmesSubset, Integer> startIndex = new EnumMap<>(CgmesSubset.class);
        for (CgmesSubset subset : target.state().keySet()) {
            for (int i = 0; i < chain.size(); i++) {
                String modelId = chain.get(i).fullModels().get(subset);
                if (modelId != null) {
                    start.put(subset, new MaterializationPlan.FullSource(chain.get(i).iri(), modelId,
                            fullGraphs.get(modelId)));
                    startIndex.put(subset, i);
                    break;
                }
            }
            if (!start.containsKey(subset)) {
                throw new RdfDbException("no snapshot on the chain of " + target + " of scenario '" + scenario
                        + "' holds a full " + subset.getIdentifier() + " model to start a materialisation from");
            }
        }
        // Down the chain, oldest first, taking the difference members of each profile below its start snapshot
        List<UpdatePlan.DiffStep> steps = new ArrayList<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            SnapshotInfo snapshot = chain.get(i);
            for (Map.Entry<CgmesSubset, Integer> entry : startIndex.entrySet()) {
                if (i >= entry.getValue()) {
                    continue;
                }
                memberOf(snapshot, entry.getKey())
                        .ifPresent(id -> steps.add(new UpdatePlan.DiffStep(snapshot.iri(), named(id), false)));
            }
        }
        return new MaterializationPlan(target.iri(), start, resolve(steps, models), target.state());
    }

    // ------------------------------------------------------------------ the lowest common ancestor

    /**
     * The path from one chain to another, read off two chains that were already queried.
     *
     * <p>A lowest-common-ancestor walk on a chain that never branches: up from the first chain to the snapshot the
     * two share, undoing each difference, then down to the second one applying each one forward. It sends nothing:
     * both chains and every model they name came out of one request, which is what lets a bulk load plan a whole
     * day from a single query.</p>
     *
     * @param from    the chain of the snapshot to start at, deepest first
     * @param to      the chain of the snapshot to reach, deepest first
     * @param models  the difference models the chains name
     * @param options how long a chain the caller allows
     * @return the plan
     */
    UpdatePlan path(List<SnapshotInfo> from, List<SnapshotInfo> to, Map<String, StoredModel> models,
                    RdfDbUpdateOptions options) {
        SnapshotInfo b = to.get(0);
        Map<CgmesSubset, String> targetState = b.state();
        if (from.isEmpty()) {
            return new UpdatePlan(UpdatePlan.Kind.FULL, null, b.iri(), List.of(),
                    List.of("the network is at no snapshot of scenario '" + scenario + "': it has to be rebuilt"),
                    0, checkpointRecommended(to, options), distanceToFull(to), targetState);
        }
        if (from.get(0).iri().equals(b.iri())) {
            return new UpdatePlan(UpdatePlan.Kind.NOOP, b.iri(), b.iri(), List.of(), List.of(), 0,
                    checkpointRecommended(to, options), distanceToFull(to), targetState);
        }
        Set<String> bChain = new LinkedHashSet<>(to.stream().map(SnapshotInfo::iri).toList());
        SnapshotInfo lca = from.stream().filter(info -> bChain.contains(info.iri())).findFirst().orElse(null);
        if (lca == null) {
            return new UpdatePlan(UpdatePlan.Kind.FULL, from.get(0).iri(), b.iri(), List.of(),
                    List.of("no common ancestor of " + from.get(0) + " and " + b + " in scenario '" + scenario
                            + "'"), 0, checkpointRecommended(to, options), distanceToFull(to),
                    targetState);
        }
        List<UpdatePlan.DiffStep> steps = new ArrayList<>();
        // Up from A, exclusive of the common ancestor: every difference of those snapshots has to be undone
        for (SnapshotInfo info : from) {
            if (info.iri().equals(lca.iri())) {
                break;
            }
            info.members().forEach(id -> steps.add(new UpdatePlan.DiffStep(info.iri(), named(id), true)));
        }
        // Down to B, exclusive of the common ancestor, oldest first
        List<SnapshotInfo> down = new ArrayList<>();
        for (SnapshotInfo info : to) {
            if (info.iri().equals(lca.iri())) {
                break;
            }
            down.add(info);
        }
        Collections.reverse(down);
        down.forEach(info -> info.members()
                .forEach(id -> steps.add(new UpdatePlan.DiffStep(info.iri(), named(id), false))));

        List<UpdatePlan.DiffStep> resolved = resolve(steps, models);
        List<String> reasons = new ArrayList<>();
        for (UpdatePlan.DiffStep step : resolved) {
            if (!step.model().fastPredicatesOnly()) {
                reasons.add("the difference " + step.model().id() + " of snapshot " + step.snapshot()
                        + " states properties an in-place update does not read");
                break;
            }
        }
        if (resolved.size() > options.getMaxDiffChain()) {
            reasons.add("the path holds " + resolved.size() + " differences, more than the allowed "
                    + options.getMaxDiffChain());
        }
        UpdatePlan.Kind kind = reasons.isEmpty() ? UpdatePlan.Kind.DIFF : UpdatePlan.Kind.FULL;
        return new UpdatePlan(kind, from.get(0).iri(), b.iri(), resolved, reasons, resolved.size(),
                checkpointRecommended(to, options), distanceToFull(to), targetState);
    }

    private static Optional<String> memberOf(SnapshotInfo snapshot, CgmesSubset subset) {
        String state = snapshot.state().get(subset);
        return state != null && snapshot.members().contains(state) ? Optional.of(state) : Optional.empty();
    }

    /** A step before its model is put on it: the identifier is what the chain rows name. */
    private static StoredModel named(String id) {
        return new StoredModel("", id, CgmesSubset.UNKNOWN, StoredModel.Kind.DIFF, null, null, null, 1, null,
                null, null, null, List.of(), List.of(), List.of(), false, -1L, "", null, 0);
    }

    /**
     * The difference model one row of the plan query describes.
     *
     * <p>Everything a step needs to be fetched and applied &mdash; its graphs, its subject base, its CIM namespace,
     * whether an in-place update can read it &mdash; is a property of the model node, and the plan query asks for
     * all of it. What is deliberately <em>not</em> here is the {@code md:Model.*} header: only the first and the
     * last model of a profile need one, and returning fourteen more terms for every step to serve two of them is
     * what made the plan query expensive.</p>
     */
    private StoredModel storedModel(String id, Map<String, Value> row) {
        return new StoredModel(scenario, id, SnapshotRows.subsetOf(row.get("sub")), StoredModel.Kind.DIFF, null,
                text(row, "fwd"), text(row, "rev"), 1, null, null, null, null, List.of(), List.of(), List.of(),
                row.get("mfast") != null && SnapshotRows.booleanOf(row.get("mfast")),
                longOf(row.get("n")), text(row, "sbase"), text(row, "cim"), (int) longOf(row.get("cdepth")),
                row.get("vsafe") == null ? null : SnapshotRows.booleanOf(row.get("vsafe")));
    }

    private static String text(Map<String, Value> row, String binding) {
        Value value = row.get(binding);
        return value == null ? null : value.stringValue();
    }

    private static long longOf(Value value) {
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value.stringValue());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * Put the models the plan query described onto the steps that name them.
     *
     * <p>No request: the rows that said which differences lie on the path also said what they are.</p>
     */
    private List<UpdatePlan.DiffStep> resolve(List<UpdatePlan.DiffStep> steps, Map<String, StoredModel> models) {
        if (steps.isEmpty()) {
            return List.of();
        }
        List<UpdatePlan.DiffStep> resolved = new ArrayList<>();
        for (UpdatePlan.DiffStep step : steps) {
            StoredModel model = models.get(step.model().id());
            if (model == null) {
                throw new RdfDbException("scenario '" + scenario + "' names the model " + step.model().id()
                        + " in snapshot " + step.snapshot() + " but does not hold it as a difference");
            }
            resolved.add(new UpdatePlan.DiffStep(step.snapshot(), model, step.inverted()));
        }
        return resolved;
    }

    private static boolean checkpointRecommended(List<SnapshotInfo> chain, RdfDbUpdateOptions options) {
        return distanceToFull(chain) > options.getCheckpointAfter();
    }

    /** How many snapshots lie between the target and the nearest ancestor that can start a materialisation. */
    private static int distanceToFull(List<SnapshotInfo> chain) {
        int distance = 0;
        for (SnapshotInfo info : chain) {
            if (info.hasFull()) {
                return distance;
            }
            distance++;
        }
        return distance;
    }

    // ------------------------------------------------------------------ many sides in one request

    /**
     * Where one side of a multi-side chain query starts.
     *
     * @param snapshotIri the IRI of the snapshot, when it is already known
     * @param ref         the address to resolve in the query itself, when it is not. Ignored when the IRI is given
     */
    record Start(String snapshotIri, SnapshotRef ref) {
    }

    /**
     * The chains of many snapshots, and everything their models are, out of one request.
     *
     * @param bySide     the chain of each side, deepest first, empty when that side resolved to nothing
     * @param fullGraphs the named graph of every full model the chains name
     * @param diffs      every difference model the chains name
     */
    record Chains(Map<String, List<SnapshotInfo>> bySide, Map<String, String> fullGraphs,
                  Map<String, StoredModel> diffs) {
    }

    /**
     * Walk the version graph up from many snapshots at once.
     *
     * <p>A day of ninety-six timesteps is ninety-six chains, and asking for them one by one would be ninety-six
     * round trips over a path walk that mostly reads the <em>same</em> ancestors. One request instead: a
     * {@code UNION} binds the start of every side, one branch returns which snapshots each side reached, and a
     * second branch returns the detail rows of each reached snapshot <em>once</em>, through a
     * {@code SELECT DISTINCT} sub-select. The cost is therefore the union of the chains, roughly
     * {@code (sides + base depth)} snapshots, not {@code sides × depth}.</p>
     *
     * <p>Both SPARQL behaviours this rests on &mdash; a sub-select inside a {@code UNION} branch seeing the path
     * walk of its own branch, and a hundred {@code UNION} branches in one request &mdash; are asserted on both
     * backends by {@code RdfDbSparqlSemanticsTest}.</p>
     *
     * @param starts where each side starts, by side name
     * @return the chains
     */
    Chains chains(Map<String, Start> starts) {
        Objects.requireNonNull(starts);
        if (starts.isEmpty()) {
            return new Chains(Map.of(), Map.of(), Map.of());
        }
        StringBuilder startPattern = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Start> entry : starts.entrySet()) {
            startPattern.append(first ? " { " : " UNION { ");
            first = false;
            startPattern.append("BIND(").append(SparqlText.str(entry.getKey())).append(" AS ?side) ")
                    .append(startOf(entry.getValue())).append(" } ");
        }
        String starting = startPattern.toString();

        String query = RdfDbVocabulary.PREFIXES
                + "SELECT ?side ?snap ?p ?o ?sub ?graph ?fwd ?rev ?mfast ?vsafe ?n ?sbase ?cim ?cdepth"
                + " WHERE { GRAPH " + SparqlText.iri(metaGraph) + " {"
                + " {" + starting + " ?start pdb:parent* ?snap . ?snap a pdb:Snapshot }"
                + " UNION"
                + " { { SELECT DISTINCT ?snap WHERE {" + starting + " ?start pdb:parent* ?snap } }"
                + "   ?snap a pdb:Snapshot ; ?p ?o "
                + "   OPTIONAL { ?o pdb:subset ?sub "
                + "     OPTIONAL { ?o pdb:graph ?graph } "
                + "     OPTIONAL { ?o pdb:forwardGraph ?fwd ; pdb:reverseGraph ?rev ;"
                + "       pdb:fastPredicatesOnly ?mfast ; pdb:subjectBase ?sbase ; pdb:cimNamespace ?cim ;"
                + "       pdb:chainDepth ?cdepth . OPTIONAL { ?o pdb:tripleCount ?n }"
                + "       OPTIONAL { ?o pdb:variantSafe ?vsafe } } } } } }";

        List<Map<String, Value>> rows = connection.sparql(scenario).select(query);
        List<Map<String, Value>> detailRows = new ArrayList<>();
        Map<String, List<String>> membership = new LinkedHashMap<>();
        Map<String, String> graphs = new LinkedHashMap<>();
        Map<String, StoredModel> diffs = new LinkedHashMap<>();
        for (Map<String, Value> row : rows) {
            Value side = row.get("side");
            if (side != null) {
                Value snap = row.get("snap");
                if (snap != null) {
                    membership.computeIfAbsent(side.stringValue(), k -> new ArrayList<>())
                            .add(snap.stringValue());
                }
                continue;
            }
            detailRows.add(row);
            Value graph = row.get("graph");
            Value object = row.get("o");
            if (graph != null && object != null) {
                graphs.put(object.stringValue(), graph.stringValue());
            }
            if (object != null && row.get("fwd") != null) {
                diffs.putIfAbsent(object.stringValue(), storedModel(object.stringValue(), row));
            }
        }
        Map<String, SnapshotInfo> byIri = SnapshotRows.group(scenario, detailRows, "snap");
        Map<String, List<SnapshotInfo>> bySide = new LinkedHashMap<>();
        starts.keySet().forEach(side -> {
            List<SnapshotInfo> chain = new ArrayList<>();
            java.util.Set<String> seen = new LinkedHashSet<>();
            for (String iri : membership.getOrDefault(side, List.of())) {
                SnapshotInfo info = byIri.get(iri);
                if (info != null && seen.add(iri)) {
                    chain.add(info);
                }
            }
            chain.sort(java.util.Comparator.comparingInt(SnapshotInfo::depth).reversed());
            bySide.put(side, List.copyOf(chain));
        });
        // The side order is the caller's, so a bulk load can read its requests back off the result in order
        return new Chains(Collections.unmodifiableMap(bySide), Map.copyOf(graphs), Map.copyOf(diffs));
    }

    /** The graph pattern binding {@code ?start} for one side. */
    private String startOf(Start start) {
        if (start.snapshotIri() != null) {
            return "BIND(" + SparqlText.iri(start.snapshotIri()) + " AS ?start)";
        }
        SnapshotRef ref = start.ref();
        String timestep = ref.timestep() == null ? catalog.baseTimestep() : ref.timestep();
        return "?start a pdb:Snapshot ; pdb:timestep " + SparqlText.str(timestep)
                + (ref.version() == null
                        ? " . FILTER NOT EXISTS { ?c pdb:parent ?start ; pdb:edge pdb:VersionEdge }"
                        : " ; pdb:version " + SparqlText.str(ref.version()));
    }

    // ------------------------------------------------------------------ the query

    /**
     * The two chains a plan query returns, deepest first, plus what the rows said about the models they name.
     *
     * @param a          the chain of the snapshot the network is at, deepest first
     * @param b          the chain of the target, deepest first
     * @param fullGraphs the named graph of every full model the chains refer to
     * @param diffs      every difference model the chains refer to, built from the rows of the same request
     */
    private record Sides(List<SnapshotInfo> a, List<SnapshotInfo> b, Map<String, String> fullGraphs,
                         Map<String, StoredModel> diffs) {
    }

    private Sides query(String fromIri, SnapshotRef target) {
        String timestep = target.timestep() == null ? catalog.baseTimestep() : target.timestep();
        String bStart = " { BIND(" + SparqlText.str(SIDE_B) + " AS ?side) ?start a pdb:Snapshot ; pdb:timestep "
                + SparqlText.str(timestep)
                + (target.isLatest() ? " . FILTER NOT EXISTS { ?c pdb:parent ?start ; pdb:edge pdb:VersionEdge }"
                        : " ; pdb:version " + SparqlText.str(target.version()))
                + " } ";
        return runQuery(fromIri, bStart);
    }

    private Sides query(String fromIri, String bSnapshotIri) {
        return runQuery(fromIri, " { BIND(" + SparqlText.str(SIDE_B) + " AS ?side) BIND("
                + SparqlText.iri(bSnapshotIri) + " AS ?start) } ");
    }

    private Sides runQuery(String fromIri, String bStart) {
        StringBuilder query = new StringBuilder(RdfDbVocabulary.PREFIXES)
                .append("SELECT ?side ?snap ?p ?o ?sub ?graph ?fwd ?rev ?mfast ?vsafe ?n ?sbase ?cim ?cdepth")
                .append(" WHERE { GRAPH ").append(SparqlText.iri(metaGraph))
                .append(" { ");
        if (fromIri != null) {
            query.append(" { BIND(").append(SparqlText.str(SIDE_A)).append(" AS ?side) BIND(")
                    .append(SparqlText.iri(fromIri)).append(" AS ?start) } UNION ");
        }
        // One request. The path walk is bounded by the depth of the two ends, and every row an object needs to
        // become a stored model travels with it, so the plan needs no second query to resolve what it found
        query.append(bStart)
                .append(" ?start pdb:parent* ?snap . ?snap a pdb:Snapshot ; ?p ?o ")
                .append(" OPTIONAL { ?o pdb:subset ?sub ")
                .append("   OPTIONAL { ?o pdb:graph ?graph } ")
                .append("   OPTIONAL { ?o pdb:forwardGraph ?fwd ; pdb:reverseGraph ?rev ;")
                .append("     pdb:fastPredicatesOnly ?mfast ; pdb:subjectBase ?sbase ; pdb:cimNamespace ?cim ;")
                .append("     pdb:chainDepth ?cdepth . OPTIONAL { ?o pdb:tripleCount ?n }")
                .append("     OPTIONAL { ?o pdb:variantSafe ?vsafe } } } } }");

        List<Map<String, Value>> rows = connection.sparql(scenario).select(query.toString());
        Map<String, List<Map<String, Value>>> bySide = new LinkedHashMap<>();
        Map<String, String> graphs = new LinkedHashMap<>();
        Map<String, StoredModel> diffs = new LinkedHashMap<>();
        for (Map<String, Value> row : rows) {
            Value side = row.get("side");
            if (side == null) {
                continue;
            }
            bySide.computeIfAbsent(side.stringValue(), k -> new ArrayList<>()).add(row);
            Value graph = row.get("graph");
            Value object = row.get("o");
            if (graph != null && object != null) {
                graphs.put(object.stringValue(), graph.stringValue());
            }
            if (object != null && row.get("fwd") != null) {
                diffs.putIfAbsent(object.stringValue(), storedModel(object.stringValue(), row));
            }
        }
        return new Sides(chain(bySide.get(SIDE_A)), chain(bySide.get(SIDE_B)), graphs, diffs);
    }

    /** The snapshots of one side, deepest first: the chain a path walk reads off. */
    private List<SnapshotInfo> chain(List<Map<String, Value>> rows) {
        if (rows == null) {
            return List.of();
        }
        List<SnapshotInfo> chain = new ArrayList<>(SnapshotRows.group(scenario, rows, "snap").values());
        chain.sort(java.util.Comparator.comparingInt(SnapshotInfo::depth).reversed());
        return chain;
    }
}
