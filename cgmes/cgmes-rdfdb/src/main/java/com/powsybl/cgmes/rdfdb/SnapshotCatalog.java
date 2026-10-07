/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.TripleStoreNetworkLoader;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.StatementDiff;
import com.powsybl.cgmes.model.triplestore.CgmesTripleStoreLoader;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.triplestore.api.TripleStoreOptions;
import com.powsybl.triplestore.impl.rdf4j.TripleStoreRDF4J;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The snapshots of one scenario: what states the database holds, and how a new one is written.
 *
 * <h2>What a snapshot is, and why it is a resource</h2>
 * <p>A version is a {@code pdb:Snapshot} node plus immutable named graphs, not a tag on the objects of the model.
 * Tagging would mean a version property on every object &mdash; or reification &mdash; a version filter in all
 * eighty-one catalog queries of the CGMES conversion, a forked catalog to maintain and mutable data. Named graphs
 * leave the queries untouched, make every graph cacheable by its IRI, and map one to one onto CGMES itself:
 * {@code md:Model.Supersedes} is the chain of one profile, {@code md:Model.DependentOn} the dependency between
 * them, {@code md:Model.scenarioTime} the timestamp, {@code md:Model.modelingAuthoritySet} the tree. What the
 * snapshot node adds on top is the one thing CGMES has no term for: which models of <em>different</em> profiles
 * belong together.</p>
 *
 * <h2>The keys</h2>
 * <p>{@code (scenario, modellingAuthority, timestamp, version)}, unique. The scenario is the outermost and is
 * required everywhere: it is one base grid model, one day, and a database is expected to hold several. Inside a
 * scenario every modelling authority owns <strong>one tree</strong> with exactly one root; another day is another
 * scenario, never a second root of the same authority. All trees of a scenario live in its one metadata graph and
 * share its boundary, so "every authority at this moment" &mdash; a CGM &mdash; is one query ({@link #assembly}).
 * Every other timestamp of a tree has a root of its own, <em>pinned</em> to another snapshot of the same tree
 * &mdash; by default the latest {@linkplain #rollover rollover} at or before it &mdash; and below that root the
 * version chain of a timestamp is linear, its versions names that only grow in the order of the scenario's
 * {@link VersionRegistry}: a new version ranks above the head it is written on, and a read at a version means the
 * highest ranking one at or below it. The profiles a snapshot covers are not a key;
 * they are what it holds ({@link SnapshotInfo#profiles()}) and what a caller projects on.</p>
 *
 * <h2>Nothing crosses a scenario</h2>
 * <p>One catalogue is bound to one scenario and every query it sends names that scenario's metadata graph. A
 * {@link SnapshotRef} naming another scenario is refused before any query is sent, and no {@code pdb:parent},
 * {@code pdb:state}, {@code pdb:member} or {@code pdb:full} link ever points out of the scenario it was written
 * in &mdash; {@link #verify()} checks exactly that, and that no link crosses a modelling authority except the
 * shared boundary.</p>
 *
 * <h2>One schema</h2>
 * <p>The metadata graph carries {@code pdb:schema 4}. A scenario written by an earlier schema &mdash; schema 3,
 * whose versions were integers, or the {@code (scenario, timestep, version)} schema before it &mdash; is refused
 * with a message, not migrated: clear it and ingest it again.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class SnapshotCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotCatalog.class);

    /** How often a write retries after the version registry changed under it, before it gives up. */
    private static final int REGISTRY_RETRIES = 3;

    /** The profiles whose stated modelling authority decides the authority of a snapshot that names none. */
    private static final Set<String> DECIDING_PROFILES =
            Set.of(Profiles.EQ, Profiles.SSH);

    /** What {@link #putAsDiff} compares when the caller names no profile: the two that carry a schedule. */
    private static final Set<String> DEFAULT_COMPARED =
            Set.of(Profiles.EQ, Profiles.SSH);

    private final RdfDbConnection connection;
    private final String scenario;
    private final String metaGraph;
    private final String schemaNode;
    /** Whether the metadata graph was found to be of this release's schema; it is never written by another. */
    private volatile boolean schemaChecked;
    /**
     * The root of each tree, once read: a root is written once and never changed, so the base timestamp an open
     * address means costs no request after the first. Only roots that exist are kept. It assumes the scenario is not
     * cleared through another connection while this catalogue lives.
     */
    private final Map<String, SnapshotInfo> rootByAuthority = new ConcurrentHashMap<>();
    /** The version registry of the scenario, read with the schema check and cached; see {@link VersionRegistry}. */
    private final VersionRegistry registry;
    private volatile IngestStatistics lastIngest;
    /** Runs between the parse of a root and its guarded write, so that a test can make a concurrent writer win. */
    private Runnable beforeRootWrite;

    /** How often a whole timestamp was answered out of the parent index cache: test-only telemetry. */
    private final AtomicLong parentIndexHits = new AtomicLong();

    SnapshotCatalog(RdfDbConnection connection, String scenario) {
        this.connection = Objects.requireNonNull(connection);
        this.scenario = RdfDbNames.checkScenario(scenario);
        this.metaGraph = RdfDbNames.metaGraph(scenario);
        this.schemaNode = RdfDbNames.schemaNode(scenario);
        this.registry = new VersionRegistry(connection, this);
    }

    /**
     * The version registry of the scenario: the registered version names and their ranks.
     *
     * @return the registry, as last read through this connection
     */
    public VersionRegistry registry() {
        return registry;
    }

    /**
     * @return the scenario this catalogue is bound to
     */
    public String scenario() {
        return scenario;
    }

    /**
     * @return the IRI of the metadata graph of the scenario
     */
    public String metaGraph() {
        return metaGraph;
    }

    private SparqlAccess sparql() {
        return connection.sparql(scenario);
    }

    private String graphClause() {
        return " GRAPH " + SparqlText.iri(metaGraph) + " ";
    }

    /**
     * Refuse an address that names another scenario, without asking the database.
     *
     * @param ref the reference to check
     * @return the reference
     */
    SnapshotRef check(SnapshotRef ref) {
        Objects.requireNonNull(ref);
        if (!scenario.equals(ref.scenario())) {
            throw new RdfDbException("the snapshot catalogue of scenario '" + scenario + "' cannot address scenario"
                    + " '" + ref.scenario() + "': a scenario is one base grid model and nothing links two of them."
                    + " Use db.snapshots(\"" + ref.scenario() + "\")");
        }
        return ref;
    }

    /**
     * Refuse an address a read cannot resolve: one of another scenario, or one without a modelling authority.
     *
     * <p>A read never guesses the authority, because the tree it would pick is another TSO's grid. Only the error
     * path asks the database, to name the authorities the caller can choose from.</p>
     *
     * @param ref the reference to check
     * @return the reference
     */
    SnapshotRef readable(SnapshotRef ref) {
        check(ref);
        if (ref.modellingAuthority() == null) {
            throw new RdfDbException("the address " + ref + " names no modelling authority, and a read needs one:"
                    + " scenario '" + scenario + "' holds " + modellingAuthorities());
        }
        return ref;
    }

    /**
     * Refuse a metadata graph of another addressing schema.
     *
     * <p>One request the first time a catalogue reads, none afterwards: a graph this release has accepted is only
     * ever written by this release. Called by every listing, so no read decodes a node of an older schema into a
     * wrong address. The same request reads the version registry ({@link #readSchema}).</p>
     *
     * @throws RdfDbException if the graph holds snapshots but not {@code pdb:schema 4}, or a node of the earlier
     *                        {@code (scenario, timestep, version)} schema
     */
    void checkSchema() {
        if (schemaChecked) {
            return;
        }
        readSchema();
    }

    /**
     * Check the schema and read the version registry, in one request, whether or not this was done before.
     */
    void readSchema() {
        List<Map<String, Value>> rows = select("SELECT DISTINCT ?k ?v ?n ?r ?t WHERE {" + graphClause() + "{"
                + " { " + SparqlText.iri(schemaNode) + " pdb:schema ?v BIND(\"schema\" AS ?k) }"
                + " UNION { ?x a " + SparqlText.iri(SnapshotRows.LEGACY_CATALOG) + " BIND(\"catalog\" AS ?k) }"
                + " UNION { ?x " + SparqlText.iri(SnapshotRows.LEGACY_TIMESTEP) + " ?ts BIND(\"timestep\" AS ?k) }"
                + " UNION { ?x a pdb:Snapshot BIND(\"snapshot\" AS ?k) }" + registry.readBranches() + " } }");
        Map<String, Value> found = new LinkedHashMap<>();
        rows.forEach(row -> found.put(SnapshotRows.text(row, "k"), row.get("v")));
        if (found.containsKey("catalog")) {
            throw SnapshotRows.legacySchema(scenario, "a pdb:Catalog node");
        }
        if (found.containsKey("timestep")) {
            throw SnapshotRows.legacySchema(scenario, "snapshot nodes keyed by pdb:timestep");
        }
        Value schema = found.get("schema");
        if (schema != null && SnapshotRows.intOf(schema) != RdfDbVocabulary.SCHEMA_VERSION) {
            throw new RdfDbException("scenario '" + scenario + "' carries pdb:schema " + schema.stringValue()
                    + ", and this release reads only stores of schema " + RdfDbVocabulary.SCHEMA_VERSION
                    + ": read it with the release that wrote it, or clear the scenario and re-ingest it");
        }
        if (schema == null && found.containsKey("snapshot")) {
            throw SnapshotRows.legacySchema(scenario, "snapshots without a pdb:schema marker");
        }
        registry.load(rows);
        schemaChecked = true;
    }

    // ------------------------------------------------------------------ reads

    /**
     * Whether the scenario holds any snapshot at all.
     *
     * <p>A scenario that does not is a scenario of the unversioned flow: its instance file graphs and its
     * difference chain are read by {@code RdfDbNetworkLoader.load(db, scenario, ...)}.</p>
     *
     * @return whether the scenario is versioned
     */
    public boolean isVersioned() {
        return sparql().ask(RdfDbVocabulary.PREFIXES + "ASK {" + graphClause() + "{ ?s a pdb:Snapshot } }");
    }

    /**
     * Every snapshot of the scenario, by modelling authority, oldest first.
     *
     * <p>One request. The metadata graph of a scenario is small by construction, and grouping the rows here is
     * what {@link SnapshotRows} is for. That single request also carries the {@code pdb:fastPredicatesOnly} of the
     * member models, which is what {@link SnapshotInfo#fast()} is derived from.</p>
     *
     * @return the snapshots, ordered by modelling authority, timestamp and depth
     */
    public List<SnapshotInfo> snapshots() {
        List<SnapshotInfo> all = new ArrayList<>(snapshotsWhere("", "").values());
        all.sort(SnapshotRows.byTimestampAndDepth());
        return List.copyOf(all);
    }

    /**
     * One snapshot by its IRI.
     *
     * @param snapshotIri the IRI
     * @return the snapshot, or empty when this scenario does not hold it &mdash; which is also the answer for an
     *         IRI of another scenario
     */
    public Optional<SnapshotInfo> info(String snapshotIri) {
        Objects.requireNonNull(snapshotIri);
        if (!scenario.equals(RdfDbNames.scenarioOf(snapshotIri))) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshotsWhere("BIND(" + SparqlText.iri(snapshotIri) + " AS ?s) ", "")
                .get(snapshotIri));
    }

    /**
     * Resolve an address to the snapshot it names.
     *
     * <p>One request: an open timestamp is resolved to the root's of that modelling authority inside it, and a named
     * version to the snapshot of that moment ranking highest at or below it &mdash; {@code "RT"} reads
     * {@code "ID"} where the timestamp did not reach {@code "RT"} &mdash; unless the address is
     * {@linkplain SnapshotRef#exactly() exact}.</p>
     *
     * @param ref the address; a {@code null} version means the head of the chain, a {@code null} timestamp the base
     *            timestamp of the modelling authority's tree
     * @return the snapshot, or empty when the scenario holds none at that address
     * @throws RdfDbException if the address names another scenario or no modelling authority
     */
    public Optional<SnapshotInfo> find(SnapshotRef ref) {
        readable(ref);
        Map<String, SnapshotInfo> found = snapshotsWhere(addressPattern("?s", ref), "");
        if (found.isEmpty() && isNamed(ref) && registry.changedAfterMiss()) {
            found = snapshotsWhere(addressPattern("?s", ref), "");
        }
        if (found.size() > 1) {
            throw new RdfDbException("the metadata graph of scenario '" + scenario + "' is inconsistent: " + ref
                    + " names " + found.size() + " snapshots " + found.keySet() + ", and the version chain of a"
                    + " timestamp is linear. No write of this release can produce that state");
        }
        return found.values().stream().findFirst();
    }

    /**
     * The graph of every profile a snapshot holds as a whole graph: what a caller reads a custom profile with.
     *
     * <p>A custom profile ({@link Profiles}) is always stored whole, so every one the snapshot holds is here; so is
     * a standard profile whose state is still the instance file it started from. A profile whose state is a
     * difference has no single graph and is not. Read a graph with {@link RdfDbConnection#fetchGraph}.</p>
     *
     * @param ref the address of the snapshot
     * @return the graph IRI per profile, as the metadata graph records it
     * @throws RdfDbException if the scenario holds no snapshot at that address
     */
    public Map<String, String> graphsOf(SnapshotRef ref) {
        SnapshotInfo info = require(ref);
        Map<String, StoredModel> models = connection.catalog(scenario).models(info.state().values());
        Map<String, String> graphs = Profiles.map();
        info.state().forEach((profile, id) -> {
            StoredModel model = models.get(id);
            if (model != null && model.graph() != null) {
                graphs.put(profile, model.graph());
            }
        });
        return Collections.unmodifiableMap(graphs);
    }

    /**
     * The snapshot an address names, which has to exist.
     *
     * @param ref the address
     * @return the snapshot
     * @throws RdfDbException if the scenario holds no snapshot at that address, with the one text every entry point
     *                        that needs one uses
     */
    public SnapshotInfo require(SnapshotRef ref) {
        return find(ref).orElseThrow(() -> noSuchSnapshot(ref));
    }

    /**
     * The refusal of an address the scenario holds no snapshot at: one text for every entry point, naming what the
     * scenario does hold (one more request, on the failure only).
     *
     * @param what the address, the snapshot IRI or the list of addresses that were not found
     * @return the exception to throw
     */
    RdfDbException noSuchSnapshot(Object what) {
        return new RdfDbException("scenario '" + scenario + "' holds no snapshot " + what + ", and nothing was loaded"
                + " or written; it holds " + snapshots().stream().map(SnapshotInfo::toString).toList());
    }

    /**
     * The graph pattern that binds a variable to the snapshot an address names, inside the metadata graph.
     *
     * <p>Shared by {@link #find} and the plan query of {@link VersionGraph}, so that an address means the same in
     * both. An open timestamp joins the root of the authority's tree, which is what "the base timestamp" is; an
     * open version excludes every snapshot that has a version successor, which on a linear chain is the head; an
     * exact version is the snapshot carrying that name. A named version is the snapshot of the moment whose
     * version ranks highest at or below the name's rank: the {@linkplain VersionRegistry#candidates candidate
     * names} come from the cached registry as {@code VALUES} with their ranks, so the query looks up a handful of
     * names instead of joining a registry node per snapshot, and a sub-select ordered by that rank and limited to
     * one resolves the address before the plan query walks {@code pdb:parent*} from it. The candidates are valid at
     * one revision of the registry, which the pattern requires; a read that found nothing re-reads the registry
     * and asks again when it was stale ({@link #isNamed}).</p>
     *
     * @param var the variable, with its {@code ?}; the pattern also uses {@code var} plus {@code Base},
     *            {@code Root}, {@code Child}, {@code N} and {@code R}
     * @param ref the address, with a modelling authority
     * @return the pattern, ending with a space
     */
    String addressPattern(String var, SnapshotRef ref) {
        String authority = SparqlText.str(ref.modellingAuthority());
        StringBuilder pattern = new StringBuilder(var).append(" a pdb:Snapshot ; pdb:modellingAuthority ")
                .append(authority).append(" ; pdb:timestamp ")
                .append(ref.timestamp() == null ? var + "Base" : SparqlText.dateTime(ref.timestamp()))
                .append(" . ");
        if (ref.timestamp() == null) {
            pattern.append(var).append("Root pdb:depth ").append(SparqlText.integer(0))
                    .append(" ; pdb:modellingAuthority ").append(authority)
                    .append(" ; pdb:timestamp ").append(var).append("Base . ");
        }
        if (ref.version() == null) {
            return pattern.append("FILTER NOT EXISTS { ").append(var).append("Child pdb:parent ").append(var)
                    .append(" ; pdb:edge pdb:VersionEdge } ").toString();
        }
        if (ref.exact()) {
            return pattern.append(var).append(" pdb:version ").append(SparqlText.str(ref.version())).append(" . ")
                    .toString();
        }
        // The candidates first: a backend that joins in text order then looks up a handful of names
        return "{ SELECT " + var + " WHERE { " + registry.candidates(var, ref.version()) + pattern + "} ORDER BY DESC("
                + var + "R) LIMIT 1 } ";
    }

    /**
     * Whether an address is resolved from the cached registry, and a read of it that found nothing has to ask
     * whether the registry changed meanwhile.
     *
     * @param ref the address
     * @return whether it names a version, not exactly
     */
    static boolean isNamed(SnapshotRef ref) {
        return ref.version() != null && !ref.exact();
    }

    /**
     * The newest version of a timestamp of a modelling authority.
     *
     * @param modellingAuthority the modelling authority set
     * @param timestamp          the moment, or {@code null} for the base timestamp of its tree
     * @return the head snapshot, or empty
     */
    public Optional<SnapshotInfo> head(String modellingAuthority, Instant timestamp) {
        return find(SnapshotRef.latestAt(scenario, modellingAuthority, timestamp));
    }

    /**
     * The root snapshot of a modelling authority's tree.
     *
     * @param modellingAuthority the modelling authority set
     * @return the root, or empty when the scenario holds no tree of that authority
     */
    public Optional<SnapshotInfo> root(String modellingAuthority) {
        Objects.requireNonNull(modellingAuthority);
        SnapshotInfo cached = rootByAuthority.get(modellingAuthority);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<SnapshotInfo> root = snapshotsWhere("?s pdb:depth " + SparqlText.integer(0)
                + " ; pdb:modellingAuthority " + SparqlText.str(modellingAuthority) + " . ", "").values().stream()
                .findFirst();
        root.ifPresent(info -> rootByAuthority.put(modellingAuthority, info));
        return root;
    }

    /**
     * The roots of every tree of the scenario, in one request.
     *
     * @return the root per modelling authority, sorted by authority
     */
    private Map<String, SnapshotInfo> roots() {
        Map<String, SnapshotInfo> roots = new TreeMap<>();
        snapshotsWhere("?s pdb:depth " + SparqlText.integer(0) + " . ", "").values()
                .forEach(root -> roots.put(root.modellingAuthority(), root));
        rootByAuthority.putAll(roots);
        return roots;
    }

    /**
     * The modelling authorities the scenario holds a tree of.
     *
     * @return the modelling authority sets, sorted
     */
    public List<String> modellingAuthorities() {
        return List.copyOf(roots().keySet());
    }

    /**
     * The one modelling authority of the scenario, for the entry points that are addressed by scenario alone.
     *
     * @return the authority
     * @throws RdfDbException if the scenario holds none or several
     */
    String onlyAuthority() {
        List<String> all = modellingAuthorities();
        if (all.size() != 1) {
            throw new RdfDbException("scenario '" + scenario + "' holds " + (all.isEmpty() ? "no snapshot tree"
                    : "the trees of the modelling authorities " + all) + ", and an entry point addressed by the"
                    + " scenario alone reads a scenario of exactly one: address a snapshot with a SnapshotRef");
        }
        return all.get(0);
    }

    /**
     * The snapshots matching a pattern, with their members' fast flags, in one request.
     *
     * @param pattern     graph patterns binding or restricting {@code ?s}, each ending with a space, or empty
     * @param outerFilter a filter after the snapshot pattern, or empty
     * @return the snapshots, keyed by IRI
     */
    private Map<String, SnapshotInfo> snapshotsWhere(String pattern, String outerFilter) {
        checkSchema();
        return SnapshotRows.group(scenario, select("SELECT ?s ?p ?o ?sub ?mkind ?mfast ?rank WHERE {"
                + graphClause() + "{ " + pattern + "?s a pdb:Snapshot ; ?p ?o OPTIONAL { ?o pdb:subset ?sub }"
                + SnapshotRows.MEMBER_FAST_CLAUSE + SnapshotRows.RANK_CLAUSE + "}" + outerFilter + " }"), "s");
    }

    /**
     * The timestamp of the root of a modelling authority's tree, which is the moment its base describes.
     *
     * @param modellingAuthority the modelling authority set
     * @return the base timestamp
     * @throws RdfDbException if the scenario holds no tree of that authority
     */
    public Instant baseTimestamp(String modellingAuthority) {
        return root(modellingAuthority).map(SnapshotInfo::timestamp).orElseThrow(() -> noRoot(modellingAuthority));
    }

    private RdfDbException noRoot(String modellingAuthority) {
        return new RdfDbException("scenario '" + scenario + "' has no root snapshot of modelling authority '"
                + modellingAuthority + "': putFull first");
    }

    /**
     * Which snapshot a network is at.
     *
     * <p>The provenance first, which is exact and free; failing that, the model identifiers the network carries
     * are matched against the {@code pdb:state} of the snapshots of <em>this</em> scenario, deepest first. A
     * network loaded from another scenario answers empty, even when the two scenarios were built from the same
     * files: its identifiers are that scenario's.</p>
     *
     * @param network the network
     * @return the snapshot it is at, or empty
     */
    public Optional<SnapshotInfo> snapshotOf(Network network) {
        Objects.requireNonNull(network);
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        if (provenance != null && !scenario.equals(provenance.scenario())) {
            return Optional.empty();
        }
        if (provenance != null && provenance.snapshot().isPresent()) {
            return info(provenance.snapshot().get());
        }
        Map<String, String> ids = NetworkIdentity.modelIds(network);
        return byState(ids);
    }

    /**
     * The deepest snapshot whose state matches the given model identifiers.
     *
     * @param ids the model identifier per profile a network holds
     * @return the snapshot, or empty
     */
    Optional<SnapshotInfo> byState(Map<String, String> ids) {
        List<String> stateIds = Stream.of(Profiles.EQ, Profiles.SSH)
                .map(ids::get).filter(Objects::nonNull).toList();
        if (stateIds.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Value>> rows = deepestByState(stateIds, "", 1);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return info(rows.get(0).get("s").stringValue());
    }

    /** The snapshots stating all the given models, deepest first: rows of {@code ?s} and {@code ?d}. */
    private List<Map<String, Value>> deepestByState(List<String> stateIds, String restriction, int limit) {
        return select("SELECT ?s ?d WHERE {" + graphClause() + "{ ?s a pdb:Snapshot" + restriction
                + " ; pdb:depth ?d" + stateIds.stream().map(id -> " ; pdb:state " + SparqlText.iri(id))
                .collect(Collectors.joining()) + " } } ORDER BY DESC(?d) LIMIT " + limit);
    }

    /**
     * One timestamp of a modelling authority's tree: its root, its head and how many versions it holds.
     *
     * @param scenario           the scenario
     * @param modellingAuthority the modelling authority set
     * @param timestamp          the moment
     * @param root               the IRI of the timestamp's root snapshot
     * @param head               the IRI of the newest version of the timestamp
     * @param versionCount       how many snapshots the timestamp holds
     * @param pin                the IRI of the snapshot the timestamp's root hangs off, any snapshot of another
     *                           timestamp of the tree; {@code null} for the base timestamp
     */
    public record TimestampInfo(String scenario, String modellingAuthority, Instant timestamp, String root,
                                String head, int versionCount, String pin) {
    }

    /**
     * The timestamps of a modelling authority's tree, oldest first.
     *
     * @param modellingAuthority the modelling authority set
     * @return one row per timestamp
     */
    public List<TimestampInfo> timestamps(String modellingAuthority) {
        Objects.requireNonNull(modellingAuthority);
        Map<String, List<SnapshotInfo>> byRoot = new LinkedHashMap<>();
        snapshots().stream().filter(info -> info.modellingAuthority().equals(modellingAuthority))
                .forEach(info -> byRoot.computeIfAbsent(info.timestampRoot(), k -> new ArrayList<>()).add(info));
        List<TimestampInfo> rows = new ArrayList<>();
        byRoot.forEach((rootIri, versions) -> {
            SnapshotInfo rootInfo = versions.stream().filter(info -> info.iri().equals(rootIri)).findFirst()
                    .orElse(versions.get(0));
            SnapshotInfo headInfo = versions.stream().max(Comparator.comparingInt(SnapshotInfo::depth))
                    .orElse(rootInfo);
            rows.add(new TimestampInfo(scenario, modellingAuthority, rootInfo.timestamp(), rootIri, headInfo.iri(),
                    versions.size(), rootInfo.parent()));
        });
        rows.sort(Comparator.comparing(TimestampInfo::timestamp));
        return List.copyOf(rows);
    }

    /**
     * The versions of one timestamp of a modelling authority, oldest first.
     *
     * @param modellingAuthority the modelling authority set
     * @param timestamp          the moment, or {@code null} for the base timestamp of its tree
     * @return the snapshots of that timestamp
     */
    public List<SnapshotInfo> versions(String modellingAuthority, Instant timestamp) {
        Instant moment = timestamp == null ? baseTimestamp(modellingAuthority)
                : timestamp.truncatedTo(ChronoUnit.SECONDS);
        return snapshots().stream().filter(info -> info.modellingAuthority().equals(modellingAuthority)
                && info.timestamp().equals(moment)).toList();
    }

    /**
     * The version name a new snapshot at an address gets when the caller names none, without registering it: what
     * {@link #putDiff} would take. The exports ask it before they write, to refuse two variants that would become
     * the same snapshot while nothing is written.
     *
     * @param ref the address; its version is ignored
     * @return the lowest registered name ranking above the head's, or the name a permissive registry would append
     * @throws RdfDbException if the registry is strict and holds no such name
     */
    String nextVersionName(SnapshotRef ref) {
        readable(ref);
        Optional<SnapshotInfo> head = find(SnapshotRef.latestAt(scenario, ref.modellingAuthority(), ref.timestamp()));
        return registry.nextName(head.map(SnapshotInfo::version).orElse(null),
                head.map(h -> moment(h.modellingAuthority(), h.timestamp())).orElse(ref.toString()));
    }

    /**
     * Every modelling authority of the scenario at one moment: what a CGM is assembled from.
     *
     * <p>A query, not a stored assembly: one request over the scenario's metadata graph, where the trees of all its
     * authorities live. An authority with no snapshot at that moment (or none whose version ranks at or below the
     * one asked) is absent from the answer, never refused: compare the keys with {@link #modellingAuthorities()} to
     * see which. The shared boundary
     * is in the {@link SnapshotInfo#state()} of every entry ({@code EQ_BD}, {@code TP_BD}), the same in all of them.
     * Loading the result as one network stays the caller's: load each entry by its {@link SnapshotInfo#ref()} and
     * merge.</p>
     *
     * @param timestamp the moment
     * @param version   the version name every authority is taken at &mdash; for each, its snapshot of that moment
     *                  ranking highest at or below it &mdash; or {@code null} for the head of each
     * @return the snapshot per modelling authority, sorted by authority
     */
    public Map<String, SnapshotInfo> assembly(Instant timestamp, String version) {
        Objects.requireNonNull(timestamp);
        SnapshotRef moment = SnapshotRef.of(scenario, null, timestamp, version);
        Map<String, SnapshotInfo> byAuthority = assemblyOf(moment);
        if (byAuthority.isEmpty() && version != null && registry.changedAfterMiss()) {
            byAuthority = assemblyOf(moment);
        }
        return byAuthority;
    }

    private Map<String, SnapshotInfo> assemblyOf(SnapshotRef moment) {
        // Every snapshot of the moment, or every one at or below the version; the deepest of each authority wins
        String restriction = "?s pdb:timestamp " + SparqlText.dateTime(moment.timestamp()) + " . "
                + (moment.version() == null ? "" : registry.candidates("?s", moment.version()));
        Map<String, SnapshotInfo> byAuthority = new TreeMap<>();
        snapshotsWhere(restriction, "").values().forEach(info -> byAuthority.merge(info.modellingAuthority(), info,
                (a, b) -> a.depth() >= b.depth() ? a : b));
        return byAuthority;
    }

    List<Map<String, Value>> select(String body) {
        return sparql().select(RdfDbVocabulary.PREFIXES + body);
    }

    // ------------------------------------------------------------------ writes

    /**
     * Upload CGMES instance files as the root snapshot of one modelling authority's tree.
     *
     * <p>The files are parsed once into a scratch store, their graphs are copied into immutable graphs of this
     * scenario, and one guarded request then writes a model node per file plus the snapshot that ties them
     * together. The guard is what makes "one root per modelling authority" a property of the database rather than
     * of the caller: a second root of the same authority, or a second upload of the same model, is refused.</p>
     *
     * <p>The boundary is shared by the scenario. The first root uploads its models (the full models of
     * {@code EQ_BD} and {@code TP_BD}); every later root &mdash; another modelling authority of the same day &mdash; must carry
     * the very same boundary models, whose stored graphs it then links into its own state rather than uploading them
     * again. A root with another boundary is refused: a new boundary is a new scenario.</p>
     *
     * @param ds           the data source holding the instance files
     * @param boundary     the data source holding the boundary files, or {@code null} when {@code ds} carries them
     * @param ref          the address of the root. An explicit modelling authority is taken whatever the
     *                     files state; a {@code null} one is the {@code md:Model.modelingAuthoritySet} the equipment
     *                     and steady state hypothesis files agree on, refused when they do not &mdash; in a scenario of
     *                     one tree, that tree, and files agreeing on another authority are refused (a second tree is
     *                     opened by naming it); its timestamp may be {@code null}
     *                     and is then the {@code md:Model.scenarioTime} of the steady state file; its version may be
     *                     {@code null} and is then the lowest registered name ({@code "1"} for the first root of a
     *                     scenario without a registry, which creates a permissive one)
     * @param profiles     the profiles to store, or {@code null} or empty for every profile the files carry. The
     *                     boundary is always stored: it belongs to the scenario, not to the projection
     * @param importParams the CGMES import parameters, for the identifier options of the parser
     * @param rn           where the parse reports
     * @return the root snapshot
     * @throws RdfDbConflictException if the modelling authority already has a root, if the boundary is not the one
     *                                the scenario shares, or if the scenario already holds one of the models
     */
    public SnapshotInfo putFull(ReadOnlyDataSource ds, ReadOnlyDataSource boundary, SnapshotRef ref,
                                Set<String> profiles, Properties importParams, ReportNode rn) {
        check(ref);
        Objects.requireNonNull(ds);
        // One request, before the parse: an authority that already has a root will refuse this write whatever the
        // files say, and parsing a fourteen-megabyte data source first to find that out is wasted work
        Map<String, SnapshotInfo> roots = roots();
        if (ref.modellingAuthority() != null) {
            refuseSecondRoot(roots, ref.modellingAuthority());
        }
        ReportNode report = rn == null ? ReportNode.NO_OP : rn;
        CgmesImport importer = TripleStoreNetworkLoader.importer();
        TripleStoreOptions options = importer.tripleStoreOptions(importParams);
        SailRepository repository = new SailRepository(new MemoryStore());
        TripleStoreRDF4J scratch = new TripleStoreRDF4J(repository, options);
        try {
            CgmesTripleStoreLoader.Result parsed =
                    CgmesTripleStoreLoader.load(ds, boundary, scratch, 1, report);
            Map<String, Header> headers = project(readHeaders(repository, parsed.contextNames()), profiles);
            String authority = authorityOf(statedAuthorities(headers.values()), ref.modellingAuthority(),
                    roots::keySet, "the instance files");
            refuseSecondRoot(roots, authority);
            Instant timestamp = ref.timestamp() != null ? ref.timestamp() : scenarioTimeOf(headers);
            Set<String> shared = sharedBoundary(headers, roots, authority);
            Map<String, Header> own = new LinkedHashMap<>(headers);
            own.values().removeIf(header -> shared.contains(header.id));
            Map<String, String> localToRemote = new LinkedHashMap<>();
            own.forEach((context, header) -> localToRemote.put(context, RdfDbNames.fullGraph(scenario, header.id)));
            refuseKnownModels(own.values().stream().map(h -> h.id).toList());

            List<String> uploaded = new GraphUploader(connection, scenario).upload(repository, localToRemote);
            Map<String, String> state = Profiles.map();
            headers.values().forEach(header -> state.put(header.subset, header.id));
            Map<String, Long> counts = counts(repository, own.keySet());
            if (beforeRootWrite != null) {
                beforeRootWrite.run();
            }
            SnapshotInfo written = null;
            for (int attempt = 1; written == null; attempt++) {
                // A root has no parent to rank above: its version is any registered name, the lowest when none is
                // asked, and the first root of a scenario without a registry creates one
                VersionRegistry.Resolved version = registry.resolve(ref.version(), null,
                        moment(authority, timestamp));
                String snapshotIri = RdfDbNames.snapshot(scenario, authority, timestamp, version.name());
                sparql().update(rootWrite(own, localToRemote, parsed, state, roots.isEmpty(),
                        RdfDbDifferenceSink.SnapshotWrite.root(snapshotIri, authority, version, timestamp, state),
                        counts));
                // A new root is a new set of states, and the decoded parents of the old ones are of no use to anyone
                connection.forgetParentIndexes(scenario);
                written = info(snapshotIri).orElse(null);
                if (written != null && version.bootstraps()) {
                    registry.bootstrapped(version);
                } else if (written == null && !(attempt <= REGISTRY_RETRIES && registry.changedSince(version))) {
                    connection.catalog(scenario).dropGraphs(uploaded);
                    throw new RdfDbConflictException("the root snapshot " + snapshotIri + " was not written:"
                            + " another writer created the root of modelling authority '" + authority
                            + "' of scenario '" + scenario + "', or its boundary, or changed its version registry,"
                            + " first");
                }
            }
            LOGGER.info("Stored the root snapshot {} of scenario '{}' with {} model(s), {} of them the shared"
                    + " boundary", written, scenario, headers.size(), shared.size());
            return written;
        } finally {
            scratch.close();
        }
    }

    /**
     * A hook that runs between the parse of a root and its guarded write, so that a test can make a concurrent
     * writer win.
     * A test seam only: production code never sets it, so it is {@code null} there and costs one comparison.
     *
     * @param hook what to run, or {@code null} for nothing
     */
    void beforeRootWrite(Runnable hook) {
        this.beforeRootWrite = hook;
    }

    private void refuseSecondRoot(Map<String, SnapshotInfo> roots, String authority) {
        SnapshotInfo existing = roots.get(authority);
        if (existing != null) {
            throw new RdfDbConflictException("modelling authority '" + authority + "' of scenario '" + scenario
                    + "' already has a root snapshot " + existing + "; use putDiff, putAsDiff or Checkpoint. Another"
                    + " day is another scenario");
        }
    }

    /** The headers of the projected profiles, the boundary always included. */
    private static Map<String, Header> project(Map<String, Header> headers, Set<String> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return headers;
        }
        profiles.forEach(Profiles::check);
        Set<String> carried = headers.values().stream().map(Header::subset).collect(Collectors.toSet());
        Set<String> missing = Profiles.set(profiles);
        missing.removeAll(carried);
        if (!missing.isEmpty()) {
            throw new RdfDbException("the profiles " + missing + " are to be stored but the files do not carry them;"
                    + " they carry " + new TreeSet<>(carried));
        }
        Map<String, Header> projected = new LinkedHashMap<>(headers);
        projected.values().removeIf(header -> !profiles.contains(header.subset) && !Profiles.isBoundary(header.subset));
        return projected;
    }

    /**
     * The boundary models a new root links instead of uploading: the ones the scenario's other roots share.
     *
     * @return the identifiers of the shared boundary models, empty for the first root of the scenario
     * @throws RdfDbConflictException if the files carry another boundary than the one the scenario shares
     */
    private Set<String> sharedBoundary(Map<String, Header> headers, Map<String, SnapshotInfo> roots,
                                       String authority) {
        if (roots.isEmpty()) {
            return Set.of();
        }
        Map<String, String> stored = boundaryOf(roots.values().iterator().next().state());
        requireSharedBoundary(boundaryOfHeaders(headers), stored, authority);
        return Set.copyOf(stored.values());
    }

    /**
     * The boundary of a scenario never changes: one rule and one text for a root and for an ingestion.
     *
     * <p>A boundary is what gives the objects of a grid model their identity across files; a new one is a new base
     * grid model, and a new base grid model is a new scenario. A second root with another boundary would be
     * another day, and an ingestion with another boundary would produce a difference against a state that was
     * never the parent.</p>
     *
     * @param files  the boundary models the files carry, by profile
     * @param shared the boundary models the scenario shares, by profile (restricted by the caller to what it asks)
     * @throws RdfDbConflictException if the two differ
     */
    private void requireSharedBoundary(Map<String, String> files, Map<String, String> shared,
                                       String authority) {
        if (!files.equals(shared)) {
            throw new RdfDbConflictException("the files of modelling authority '" + authority + "' carry the boundary "
                    + files + ", but scenario '" + scenario + "' shares the boundary " + shared
                    + ": a new boundary is a new scenario");
        }
    }

    private static Map<String, String> boundaryOfHeaders(Map<String, Header> headers) {
        Map<String, String> files = Profiles.map();
        headers.values().forEach(header -> {
            if (Profiles.isBoundary(header.subset)) {
                files.put(header.subset, header.id);
            }
        });
        return files;
    }

    private static Map<String, String> boundaryOf(Map<String, String> state) {
        Map<String, String> boundary = Profiles.map();
        state.forEach((subset, id) -> {
            if (Profiles.isBoundary(subset)) {
                boundary.put(subset, id);
            }
        });
        return boundary;
    }

    private void refuseKnownModels(List<String> ids) {
        Map<String, StoredModel> known = connection.catalog(scenario).models(ids);
        if (!known.isEmpty()) {
            throw new RdfDbConflictException("model(s) " + new TreeSet<>(known.keySet()) + " are already stored in"
                    + " scenario '" + scenario + "': a versioned graph is written once and never overwritten");
        }
    }

    /**
     * The modelling authority a snapshot is stored under: the one its address names, or the one its equipment and
     * steady state hypothesis members state when the address names none, or the scenario's only tree.
     *
     * <p>One snapshot is stored under one modelling authority; the files it carries may come from several. A
     * realistic IGM is one: its equipment and topology come from the TSO's modelling tool, its state variables from
     * the merging agent that ran the power flow. So an explicit authority is taken as given, whatever the members
     * state. Without one, the equipment and the steady state hypothesis decide &mdash; the profiles a TSO owns
     * &mdash; and only when they agree. A set with neither of the two (a difference of the state variables alone,
     * say, recorded from a merged model) is refused rather than filed under whatever its other members state: that
     * would be the merging agent's tree. The boundary is never asked, it is the scenario's.</p>
     *
     * <p>A scenario of one tree takes the write into that tree when the deciding members do not agree or state
     * none, as a read of it is addressed there. Members that agree on <em>another</em> authority are refused: they
     * are another TSO's files, and neither silently diffing them into the tree nor silently opening a second tree
     * is what a caller who left the authority open can have meant.</p>
     *
     * @param stated what each non-boundary member states, by profile
     * @param given  the authority of the address, or {@code null}
     * @param trees  the modelling authorities the scenario holds a tree of, asked only when {@code given} is
     *               {@code null}
     * @param what   what the members are, for the message
     * @return the authority
     * @throws RdfDbException if the address names none and the deciding members are missing, state none or state
     *                        several where the scenario holds no single tree, or agree on another authority than
     *                        the single tree it holds
     */
    private String authorityOf(Map<String, String> stated, String given,
                               Supplier<? extends Collection<String>> trees, String what) {
        if (given != null) {
            return given;
        }
        Map<String, String> deciding = Profiles.map();
        deciding.putAll(stated);
        deciding.keySet().retainAll(DECIDING_PROFILES);
        if (deciding.isEmpty() && !stated.isEmpty()) {
            throw new RdfDbException(what + " of scenario '" + scenario + "' state the modelling authorities "
                    + byIdentifier(stated) + ", but no equipment or steady state hypothesis member states one, and"
                    + " only those two decide the modelling authority of an address that names none: pass the"
                    + " modelling authority in the address");
        }
        Set<String> authorities = new TreeSet<>(deciding.values());
        Collection<String> all = trees.get();
        if (all.size() == 1) {
            String tree = all.iterator().next();
            if (authorities.size() == 1 && !authorities.contains(tree)) {
                String other = authorities.iterator().next();
                throw new RdfDbException(what + " of scenario '" + scenario + "' state modelling authority " + other
                        + " but the scenario's only tree is " + tree + ": pass " + tree + " in the address to store"
                        + " them under it, or " + other + " to open a second tree");
            }
            return tree;
        }
        if (authorities.size() == 1) {
            return authorities.iterator().next();
        }
        throw new RdfDbException(authorities.isEmpty()
                ? what + " of scenario '" + scenario + "' state no md:Model.modelingAuthoritySet: pass the"
                    + " modelling authority in the address"
                : what + " of scenario '" + scenario + "' state the modelling authorities " + byIdentifier(stated)
                    + ", and the equipment and steady state hypothesis members do not agree on one: pass the"
                    + " modelling authority in the address (one snapshot is stored under one modelling authority;"
                    + " the files it carries may come from several)");
    }

    private static Map<String, String> byIdentifier(Map<String, String> stated) {
        Map<String, String> named = new LinkedHashMap<>();
        stated.forEach((subset, authority) -> named.put(subset, authority));
        return named;
    }

    /** The {@code md:Model.modelingAuthoritySet} each non-boundary file states, by profile. */
    private static Map<String, String> statedAuthorities(Collection<Header> headers) {
        Map<String, String> stated = Profiles.map();
        headers.stream()
                .filter(header -> !Profiles.isBoundary(header.subset))
                .forEach(header -> {
                    String authority = header.term(RdfDbVocabulary.MODEL_MODELING_AUTHORITY_SET);
                    if (authority != null) {
                        stated.put(header.subset, authority);
                    }
                });
        return stated;
    }

    /**
     * Write a difference set as a new version on top of the head of its timestamp.
     *
     * @param set    the difference models, one per profile at most
     * @param target the address the new snapshot gets
     * @return the new snapshot
     * @throws RdfDbConflictException if the version does not rank above the head's, the chain would fork, or a
     *                                difference does not supersede the state of its profile at the parent
     */
    public SnapshotInfo putDiff(DifferenceModelSet set, SnapshotRef target) {
        return putDiff(set, target, ReportNode.NO_OP);
    }

    /**
     * Write a difference set as a new version on top of the head of its timestamp.
     *
     * <p>The profiles the new snapshot touches are the profiles of the set; every other profile is inherited from
     * the parent.</p>
     *
     * @param set        the difference models
     * @param target     the address the new snapshot gets. A {@code null} modelling authority is the one the EQ
     *                   and SSH difference headers agree on, and a set with neither is refused; in a scenario of one
     *                   tree it is that tree, and headers agreeing on another authority are refused; a {@code null}
     *                   timestamp is the base timestamp of that
     *                   authority's tree; a {@code null} version is the lowest registered one ranking above the
     *                   head's (the lowest registered one for a new timestamp). An explicit version must rank above
     *                   the head's; one that is not registered is appended in a permissive scenario and refused in
     *                   a strict one (see {@link VersionRegistry})
     * @param reportNode where the write reports
     * @return the new snapshot
     */
    public SnapshotInfo putDiff(DifferenceModelSet set, SnapshotRef target, ReportNode reportNode) {
        return putDiff(set, target, null, reportNode);
    }

    /**
     * Write a difference set as a new version on top of the head of its timestamp, or as the root of a new
     * timestamp hanging off a pin.
     *
     * <p>A timestamp the tree does not hold yet gets a root of its own, linked by a {@code pdb:TimestampEdge} to
     * its <em>pin</em>: any snapshot of the same tree, at another timestamp. Which one is not a guess: the caller
     * names it, or it is the deepest snapshot of the tree whose state the differences say they supersede. Either
     * way the pin must state what every difference supersedes, because the new timestamp is "the pin plus these
     * differences". A timestamp the tree holds already grows another version on its head, and a pin is refused
     * there: it is chosen once, when the timestamp is created.</p>
     *
     * @param set        the difference models
     * @param target     the address the new snapshot gets, as for {@link #putDiff(DifferenceModelSet, SnapshotRef,
     *                   ReportNode)}
     * @param pin        the snapshot a new timestamp hangs off, of the same tree, or {@code null} for the deepest
     *                   snapshot of the tree stating what the differences supersede
     * @param reportNode where the write reports
     * @return the new snapshot
     * @throws RdfDbConflictException if a difference does not supersede the state of its profile at the pin or the
     *                                head, if a pin is named for a timestamp that exists, or for any reason of the
     *                                three-argument form
     * @throws RdfDbException         if the pin does not exist or belongs to another tree
     */
    public SnapshotInfo putDiff(DifferenceModelSet set, SnapshotRef target, SnapshotRef pin,
                                ReportNode reportNode) {
        Objects.requireNonNull(set);
        check(target);
        checkSchema();
        List<DifferenceModel> models = set.models().values().stream().filter(m -> !m.isEmpty()).toList();
        if (models.isEmpty()) {
            throw new RdfDbException("no difference to store as " + target + " of scenario '" + scenario + "'");
        }
        return putDiff(models, Wholes.NONE, target, pin == null ? null : require(pin), reportNode);
    }

    /**
     * The custom profiles a difference snapshot stores whole ({@link Profiles}).
     *
     * @param ids   the model identifier per custom profile
     * @param nodes the {@code md:FullModel} nodes of those models as {@code INSERT} triples, given the IRI of the
     *              snapshot they belong to
     */
    private record Wholes(Map<String, String> ids, UnaryOperator<String> nodes) {
        static final Wholes NONE = new Wholes(Map.of(), iri -> "");
    }

    /**
     * Write differences, and the whole graphs of custom profiles, as a new snapshot.
     *
     * @param models     the differences, possibly none when {@code wholes} names a profile and a pin is given
     * @param wholes     the custom profiles stored whole, whose graphs were uploaded already
     * @param target     the address
     * @param pin        the snapshot a new timestamp hangs off, or {@code null} for the deepest one stating what the
     *                   differences supersede
     * @param reportNode where the write reports
     */
    private SnapshotInfo putDiff(List<DifferenceModel> models, Wholes wholes, SnapshotRef target,
                                 SnapshotInfo pin, ReportNode reportNode) {
        check(target);
        checkSchema();
        Map<String, String> stated = Profiles.map();
        models.stream().filter(model -> model.header().modelingAuthoritySet() != null)
                .forEach(model -> stated.put(Profiles.of(model.header().subset()),
                        model.header().modelingAuthoritySet()));
        String authority = authorityOf(stated, target.modellingAuthority(), this::modellingAuthorities,
                "the difference models");
        // One request: an open timestamp is the base one, resolved inside the head lookup. A timestamp this tree
        // does not hold yet becomes a new timestamp root hanging off its pin; a timestamp it already holds grows
        // another version inside itself
        Optional<SnapshotInfo> existingHead = head(authority, target.timestamp());
        if (existingHead.isEmpty() && target.timestamp() == null) {
            throw noRoot(authority);
        }
        Instant timestamp = existingHead.map(SnapshotInfo::timestamp).orElse(target.timestamp());
        checkScenarioTimes(models, timestamp);
        boolean newTimestamp = existingHead.isEmpty();
        if (!newTimestamp && pin != null) {
            throw new RdfDbConflictException("timestamp " + timestamp + " of modelling authority '" + authority
                    + "' of scenario '" + scenario + "' already exists, and a pin is chosen when a timestamp is"
                    + " created: a new version of it grows on its head " + existingHead.get().ref()
                    + ", so write it without a pin");
        }
        SnapshotInfo parent;
        if (!newTimestamp) {
            parent = existingHead.get();
            if (target.timestamp() != null) {
                checkNotASecondRoot(models, parent, authority, timestamp);
            }
        } else {
            parent = pin != null ? checkPin(pin, authority, timestamp) : defaultPin(models, authority, timestamp);
        }
        checkSupersedes(models, parent, newTimestamp ? "the pin" : "the head");

        Map<String, String> state = Profiles.map(parent.state());
        Map<String, String> parentStates = Profiles.map();
        for (DifferenceModel model : models) {
            String subset = Profiles.of(model.header().subset());
            parentStates.put(subset, parent.state().get(subset));
            state.put(subset, model.header().id());
        }
        state.putAll(wholes.ids());
        // The fast-route capability of the new snapshot is not computed here and not written: the sink records it
        // per difference model as pdb:fastPredicatesOnly, and SnapshotInfo.fast() is the conjunction of those
        for (int attempt = 1; ; attempt++) {
            // The rank rule: a version of the same timestamp ranks above the head it is written on; a new
            // timestamp's root is not compared with the snapshot it hangs off, which is another timestamp's
            VersionRegistry.Resolved version = registry.resolve(target.version(),
                    newTimestamp ? null : parent.version(), moment(authority, timestamp));
            SnapshotRef address = SnapshotRef.of(scenario, authority, timestamp, version.name());
            String snapshotIri = RdfDbNames.snapshot(scenario, authority, timestamp, version.name());
            RdfDbDifferenceSink.SnapshotWrite write = new RdfDbDifferenceSink.SnapshotWrite(snapshotIri, authority,
                    version, timestamp, parent.iri(),
                    newTimestamp ? RdfDbVocabulary.TIMESTAMP_EDGE : RdfDbVocabulary.VERSION_EDGE,
                    parent.depth() + 1, state, newTimestamp ? snapshotIri : parent.timestampRoot(), parentStates,
                    wholes.ids(), wholes.nodes().apply(snapshotIri));
            RdfDbDifferenceSink sink = new RdfDbDifferenceSink(connection, scenario, reportNode);
            sink.writeInto(write);
            try {
                sink.accept(new DifferenceModelSet(models));
            } catch (RdfDbConflictException e) {
                long seen = version.rev();
                if (registry.changedSince(version)) {
                    String changed = "the version registry of scenario '" + scenario + "' changed (rev " + seen
                            + " → " + registry.rev() + ")";
                    if (attempt <= REGISTRY_RETRIES) {
                        LOGGER.info("{} under the write of {}: retrying", changed, address);
                        continue;
                    }
                    throw new RdfDbConflictException(changed + " under every attempt to write " + address
                            + ": retry", e);
                }
                throw new RdfDbConflictException(diagnose(address, parent, version, e.getMessage()), e);
            }
            SnapshotInfo written = info(snapshotIri).orElseThrow(() -> new RdfDbConflictException(
                    diagnose(address, parent, version, "the snapshot node was not written")));
            LOGGER.info("Stored the snapshot {} of scenario '{}' with {} difference(s) and {} whole graph(s)",
                    written, scenario, models.size(), wholes.ids().size());
            return written;
        }
    }

    /** The moment of a tree a write goes to, as the messages name it. */
    private String moment(String authority, Instant timestamp) {
        return "(" + scenario + ", " + authority + ", " + timestamp + ")";
    }

    /**
     * The snapshot a new timestamp root of a recorded change hangs off when the caller names none.
     *
     * <p>A timestamp is "its pin plus these differences", and which pin is not a guess: it is the snapshot of the
     * same tree whose state the differences say they supersede, the deepest one when several do (a snapshot that
     * changed none of the superseded profiles states them as well as its parent).</p>
     */
    private SnapshotInfo defaultPin(List<DifferenceModel> models, String authority, Instant timestamp) {
        List<String> superseded = models.stream()
                .filter(model -> model.header().supersedes().size() == 1)
                .map(model -> model.header().supersedes().get(0))
                .toList();
        if (superseded.isEmpty()) {
            throw new RdfDbConflictException("the difference models of the new timestamp " + timestamp
                    + " of scenario '" + scenario + "' do not each supersede exactly one stored model, so the"
                    + " snapshot they were made against cannot be identified");
        }
        List<Map<String, Value>> rows = deepestByState(superseded, " ; pdb:modellingAuthority "
                + SparqlText.str(authority), 2);
        if (rows.isEmpty()) {
            throw new RdfDbConflictException("a new timestamp hangs off a snapshot of its own tree, and no snapshot"
                    + " of modelling authority '" + authority + "' of scenario '" + scenario + "' states what the"
                    + " difference models of " + timestamp + " supersede " + superseded + "; update the network to"
                    + " a snapshot of the tree and re-record");
        }
        if (rows.size() > 1 && SnapshotRows.intOf(rows.get(0).get("d")) == SnapshotRows.intOf(rows.get(1).get("d"))) {
            LOGGER.warn("Several snapshots of '{}' in scenario '{}' at depth {} state what the new timestamp {}"
                    + " supersedes; the first is taken", authority, scenario, rows.get(0).get("d").stringValue(),
                    timestamp);
        }
        return info(rows.get(0).get("s").stringValue()).orElseThrow(() -> new RdfDbException(
                "scenario '" + scenario + "' lost the snapshot it was pinned to"));
    }

    /**
     * The snapshot a new timestamp is ingested against when the caller names none: the latest rollover of the tree
     * at or before it, deepest first at one timestamp, in one request.
     *
     * @return the rollover, or the root of the tree when it holds none (a tree whose root carries no flag)
     */
    private SnapshotInfo latestRollover(SnapshotInfo root, Instant timestamp) {
        return snapshotsWhere("{ SELECT ?s WHERE { ?s pdb:rollover true ; pdb:modellingAuthority "
                + SparqlText.str(root.modellingAuthority()) + " ; pdb:timestamp ?t ; pdb:depth ?d FILTER(?t <= "
                + SparqlText.dateTime(timestamp) + ") } ORDER BY DESC(?t) DESC(?d) LIMIT 1 } ", "").values().stream()
                .findFirst().orElse(root);
    }

    /** A pin the caller named belongs to the tree and to another timestamp. */
    private SnapshotInfo checkPin(SnapshotInfo pin, String authority, Instant timestamp) {
        if (!pin.modellingAuthority().equals(authority)) {
            throw new RdfDbException("the pin " + pin.ref() + " is a snapshot of modelling authority '"
                    + pin.modellingAuthority() + "', and a new timestamp of '" + authority + "' hangs off a snapshot"
                    + " of its own tree");
        }
        if (pin.timestamp().equals(timestamp)) {
            throw new RdfDbException("the pin " + pin.ref() + " is at the timestamp " + timestamp + " it would be"
                    + " the pin of; a timestamp hangs off another one");
        }
        return pin;
    }

    /**
     * A member of a snapshot describes the moment the snapshot does.
     *
     * <p>Only checked where the header says so: a difference recorded on a network need not repeat a scenario time
     * that did not change, and the snapshot's timestamp is then what it belongs to.</p>
     */
    private void checkScenarioTimes(List<DifferenceModel> models, Instant timestamp) {
        for (DifferenceModel model : models) {
            ZonedDateTime scenarioTime = model.header().scenarioTime();
            if (scenarioTime != null && !scenarioTime.toInstant().truncatedTo(ChronoUnit.SECONDS).equals(timestamp)) {
                throw new RdfDbException("the difference model " + model.header().id() + " states the scenario"
                        + " time " + scenarioTime.toInstant() + " but is written at timestamp " + timestamp
                        + " of scenario '" + scenario + "': a snapshot and its members describe the same moment");
            }
        }
    }

    // ------------------------------------------------------------------ ingesting a timestamp from files

    /**
     * What ingesting one timestamp from files cost and produced.
     *
     * @param parse             reading the instance files: the compared profiles in full, the rest's headers
     * @param materializeParent building the parent state as triples
     * @param diff              comparing the two graph sets
     * @param write             the guarded write
     * @param forwardStatements how many statements the forward side of each profile holds
     * @param reverseStatements how many statements the reverse side of each profile holds
     * @param fast              whether each profile's difference is fast-route capable
     * @param ignored           the profiles whose files were present and left alone
     */
    public record IngestStatistics(Duration parse, Duration materializeParent, Duration diff, Duration write,
                                   Map<String, Integer> forwardStatements,
                                   Map<String, Integer> reverseStatements, Map<String, Boolean> fast,
                                   Set<String> ignored) {
    }

    /**
     * @return what the last {@link #putAsDiff} of this catalogue cost, or {@code null} when there was none
     */
    public IngestStatistics lastIngestStatistics() {
        return lastIngest;
    }

    /**
     * Write the CGMES export of one timestamp as a difference against the state it derives from.
     *
     * <p>This is how a day reaches the database. A TSO does not record its schedule on a network: it exports
     * ninety-six sets of instance files, and what the database should hold is the base plus what each of them
     * changed. So the parent state is indexed as statements, the new files are parsed into the same shape
     * ({@link IngestParser}), the two are compared profile by profile ({@link TripleDiffCalculator}) and the
     * result is written by the ordinary {@link #putDiff} &mdash; which means every rule, guard and message of a
     * recorded difference applies to an ingested one too.</p>
     *
     * <p>Only what is compared is read in full. The profiles the ingestion inherits are read as far as their
     * {@code md:FullModel} and no further, and so is a compared profile whose model identifier is the one the
     * database already stores &mdash; that file <em>is</em> the state it would be compared against. The parent
     * state of a day is the same state for every timestamp of it, so it is materialised and decoded once and kept
     * (see {@link RdfDbConnection#parentIndex}).</p>
     *
     * <p>Two consequences of reading less, stated so that nobody relies on the opposite. A profile that is
     * inherited, or compared but unchanged, is not read beyond its header, so a defect after the header of such a
     * file &mdash; a truncated topology file, say &mdash; is not detected: the ingestion vouches for what it
     * compares, not for the completeness of the export. And the unchanged check is by identifier: a file that
     * re-uses the identifier of the state the database holds for its profile is taken to <em>be</em> that state,
     * so a re-used identifier with changed content is not detected. CGMES requires a fresh identifier per
     * export.</p>
     *
     * <p>The profiles compared are the caller's projection, the <strong>equipment model and the steady state
     * hypothesis</strong> when it names none. State variables and topology change wholesale between timestamps, so
     * a difference of them would be as large as the data; the files of a profile that is not compared are ignored
     * with a report line and the snapshot inherits the parent's state of it, which is also what makes the result a
     * state that existed. The boundary is never compared: a new boundary is a new scenario.</p>
     *
     * @param ds           the data source holding the instance files of that timestamp
     * @param boundary     the data source holding the boundary files, or {@code null}
     * @param target       the address the new snapshot gets. A {@code null} modelling authority is the one the
     *                     equipment and steady state hypothesis files agree on (in a scenario of one tree: that tree,
     *                     and files agreeing on another authority are refused), a {@code null} timestamp the base
     *                     timestamp of that authority's tree, a
     *                     {@code null} version the lowest registered one ranking above the head's
     * @param profiles     the profiles to compare, or {@code null} or empty for {@code EQ} and {@code SSH}. A
     *                     listed profile the files do not carry is refused. A listed custom profile
     *                     ({@link Profiles}) is not compared: its file is stored whole as a new member of the
     *                     snapshot, with a {@code pdb:full} link, unless it is the model the parent already states;
     *                     an unlisted one is inherited from the parent like every other profile not compared
     * @param importParams the CGMES import parameters
     * @param rn           where the ingestion reports
     * @return the new snapshot
     * @throws RdfDbConflictException if the boundary changed, or if the write is refused
     * @throws RdfDbException         if the scenario has no root, or if nothing changed
     */
    public SnapshotInfo putAsDiff(ReadOnlyDataSource ds, ReadOnlyDataSource boundary, SnapshotRef target,
                                  Set<String> profiles, Properties importParams, ReportNode rn) {
        return putAsDiff(ds, boundary, target, profiles, null, importParams, rn);
    }

    /**
     * Write the CGMES export of one timestamp as a difference against the snapshot it is pinned to.
     *
     * <p>As {@link #putAsDiff(ReadOnlyDataSource, ReadOnlyDataSource, SnapshotRef, Set, Properties, ReportNode)},
     * with the snapshot a <em>new</em> timestamp is compared against and hangs off named: any snapshot of another
     * timestamp of the same tree. Without one it is the latest {@linkplain #rollover rollover} of the tree at or
     * before the timestamp &mdash; the root until a later snapshot is flagged &mdash; so that a day is stored as
     * the change since its last rollover rather than since the morning. A timestamp the tree holds already grows
     * on its head, and a pin is refused there.</p>
     *
     * @param ds           the data source holding the instance files of that timestamp
     * @param boundary     the data source holding the boundary files, or {@code null}
     * @param target       the address the new snapshot gets
     * @param profiles     the profiles to compare, or {@code null} or empty for {@code EQ} and {@code SSH}
     * @param pin          the snapshot a new timestamp is compared against and hangs off, or {@code null} for the
     *                     latest rollover at or before it
     * @param importParams the CGMES import parameters
     * @param rn           where the ingestion reports
     * @return the new snapshot
     * @throws RdfDbConflictException if the boundary changed, if a pin is named for a timestamp that exists, or if
     *                                the write is refused
     * @throws RdfDbException         if the scenario has no root, if the pin does not exist or belongs to another
     *                                tree, or if nothing changed
     */
    public SnapshotInfo putAsDiff(ReadOnlyDataSource ds, ReadOnlyDataSource boundary, SnapshotRef target,
                                  Set<String> profiles, SnapshotRef pin, Properties importParams, ReportNode rn) {
        check(target);
        Objects.requireNonNull(ds);
        ReportNode report = rn == null ? ReportNode.NO_OP : rn;
        Set<String> listed = comparedProfiles(profiles);
        // A custom profile is never compared: a listed one is stored whole, the conversion's nine are compared
        Set<String> compared = listed.stream().filter(Profiles::isStandard).collect(Collectors.toSet());
        Set<String> whole = listed.stream().filter(profile -> !Profiles.isStandard(profile))
                .collect(Collectors.toSet());
        // The authority decides which tree the files are compared against, so an open one is read off the headers
        // first: a header-only pass, which stops at every md:FullModel
        String authority = target.modellingAuthority() != null ? target.modellingAuthority()
                : authorityOf(statedAuthorities(headersOf(IngestParser.read(ds, boundary, ReportNode.NO_OP, Map.of(),
                        Set.of())).values()), null, this::modellingAuthorities, "the instance files");
        SnapshotInfo root = root(authority).orElseThrow(() -> noRoot(authority));
        Instant timestamp = target.timestamp() == null ? root.timestamp() : target.timestamp();
        // An existing timestamp grows on its head; a new one is compared against its pin, which it then hangs off
        Optional<SnapshotInfo> head = head(authority, timestamp);
        if (head.isPresent() && pin != null) {
            throw new RdfDbConflictException("timestamp " + timestamp + " of modelling authority '" + authority
                    + "' of scenario '" + scenario + "' already exists, and a pin is chosen when a timestamp is"
                    + " created: a new version of it grows on its head " + head.get().ref() + ", so ingest it"
                    + " without a pin");
        }
        SnapshotInfo parent = head.orElseGet(() -> pin == null ? latestRollover(root, timestamp)
                : checkPin(require(pin), authority, timestamp));

        // Before the files: which state each profile is compared against decides which of them has to be read in
        // full at all, and asking costs two requests against a parse of a whole export
        long tp = System.nanoTime();
        MaterializationPlan plan = connection.versionGraph(scenario).materialization(parent.iri());
        Map<String, StoredModel> stateModels = connection.catalog(scenario).models(plan.targetState().values());
        Duration planning = Duration.ofNanos(System.nanoTime() - tp);

        long t0 = System.nanoTime();
        IngestParser.Result parsed = IngestParser.read(ds, boundary, report, plan.targetState(), compared);
        Map<String, Header> headers = headersOf(parsed);
        Duration parse = Duration.ofNanos(System.nanoTime() - t0);
        // A timestamp's files need not carry the boundary; the ones they carry must be the scenario's
        Map<String, String> carried = boundaryOfHeaders(headers);
        Map<String, String> shared = boundaryOf(root.state());
        shared.keySet().retainAll(carried.keySet());
        requireSharedBoundary(carried, shared, authority);
        // A listed profile has to be there; the default pair is compared where it is shipped
        Set<String> missing = Profiles.set(profiles == null || profiles.isEmpty() ? Set.of() : listed);
        parsed.files().forEach(file -> missing.remove(file.profile()));
        if (!missing.isEmpty()) {
            throw new RdfDbException("the profiles " + missing + " are to be compared, but the files of "
                    + target + " do not carry them");
        }

        long t1 = System.nanoTime();
        String cimNamespace = parsed.cimNamespace();
        Map<String, String> parentKeys = parentIndexKeys(parsed, plan, stateModels, cimNamespace);
        Map<String, StatementDiff.Index> parentSides =
                parentIndexesOf(parentKeys, plan, stateModels, cimNamespace, importParams);
        Duration materialize = planning.plus(Duration.ofNanos(System.nanoTime() - t1));

        List<DifferenceModel> models = new ArrayList<>();
        Set<String> ignored = new LinkedHashSet<>();
        Map<String, Integer> forward = Profiles.map();
        Map<String, Integer> reverse = Profiles.map();
        long t2 = System.nanoTime();
        for (IngestParser.ParsedFile file : parsed.files()) {
            String subset = file.profile();
            if (!compared.contains(subset)) {
                if (!whole.contains(subset)) {
                    ignored.add(subset);
                }
                continue;
            }
            StatementDiff.Index nextSide = file.index();
            StatementDiff.Index parentSide = parentSides.get(subset);
            if (nextSide == null || parentSide == null) {
                // The file is the state the database already holds, or the parent has no model of that profile
                continue;
            }
            DifferenceModel model = diffOf(parentSide, nextSide, plan.targetState().get(subset), subset,
                    headers.get(file.context()), cimNamespace, timestamp);
            if (model.isEmpty()) {
                continue;
            }
            models.add(model);
            forward.put(subset, model.forward().size());
            reverse.put(subset, model.reverse().size());
        }
        Duration diffTime = Duration.ofNanos(System.nanoTime() - t2);

        ignored.forEach(subset -> RdfDbReports.ingestedProfileIgnoredReport(report,
                subset, scenario));
        // A listed custom profile whose file is not the state the parent holds becomes a new whole graph
        Map<String, IngestParser.ParsedFile> wholeFiles = Profiles.map();
        parsed.files().stream()
                .filter(file -> whole.contains(file.profile())
                        && !file.headerId().equals(plan.targetState().get(file.profile())))
                .forEach(file -> wholeFiles.put(file.profile(), file));
        if (models.isEmpty() && wholeFiles.isEmpty()) {
            throw new RdfDbException("no difference to the parent " + parent + " of scenario '" + scenario
                    + "': the files of " + target + " describe the state the database already holds");
        }
        long t3 = System.nanoTime();
        List<String> uploaded = new ArrayList<>();
        SnapshotInfo written = null;
        try {
            Wholes wholes = wholeFiles.isEmpty() ? Wholes.NONE
                    : uploadWholes(ds, parsed, headers, wholeFiles, uploaded);
            written = putDiff(models, wholes, SnapshotRef.of(scenario, authority, timestamp, target.version()),
                    head.isPresent() ? null : parent, report);
        } finally {
            if (written == null) {
                // Unreferenced graphs are invisible to every reader; dropping them keeps a refusal traceless
                connection.catalog(scenario).dropGraphs(uploaded);
            }
        }
        Map<String, Boolean> fast = Profiles.map();
        models.forEach(model -> fast.put(Profiles.of(model.header().subset()), RdfDbDifferenceSink.isFast(model)));
        lastIngest = new IngestStatistics(parse, materialize, diffTime,
                Duration.ofNanos(System.nanoTime() - t3), forward, reverse, fast, ignored);
        LOGGER.info("Ingested {} of scenario '{}' from files: {} difference(s), {} whole graph(s), {} profile(s)"
                + " inherited", written, scenario, models.size(), wholeFiles.size(), ignored.size());
        return written;
    }

    /**
     * Upload the files of the custom profiles an ingestion stores whole, and say what the snapshot names of them.
     *
     * <p>The graphs go first, through the bulk route a large difference takes, so that the guarded request carries
     * the metadata alone; a model the scenario already holds is refused before anything is uploaded, because its
     * graph would be the one to be overwritten.</p>
     *
     * @param uploaded where the uploaded graph IRIs are collected, for the caller to drop on a refusal
     */
    private Wholes uploadWholes(ReadOnlyDataSource ds, IngestParser.Result parsed, Map<String, Header> headers,
                                Map<String, IngestParser.ParsedFile> files, List<String> uploaded) {
        refuseKnownModels(files.values().stream().map(IngestParser.ParsedFile::headerId).toList());
        String subjectBase = ModelCatalog.subjectBaseOf(parsed.baseName());
        Map<String, String> ids = Profiles.map();
        Map<String, FullGraph> graphs = Profiles.map();
        files.forEach((profile, file) -> {
            List<Statement> statements = IngestParser.statements(ds, file.name(), parsed.baseName());
            String graph = RdfDbNames.fullGraph(scenario, file.headerId());
            connection.writeGraph(scenario, graph, statements);
            uploaded.add(graph);
            ids.put(profile, file.headerId());
            graphs.put(profile, new FullGraph(profile, graph, statements.size(), subjectBase, parsed.cimNamespace()));
        });
        ZonedDateTime now = ZonedDateTime.now();
        return new Wholes(ids, snapshotIri -> {
            StringBuilder nodes = new StringBuilder();
            graphs.forEach((profile, graph) -> appendFullModelNode(nodes, headers.get(files.get(profile).context()),
                    graph, snapshotIri, now));
            return nodes.toString();
        });
    }

    /** The profiles an ingestion compares: the projection, or {@code EQ} and {@code SSH}; never the boundary. */
    private static Set<String> comparedProfiles(Set<String> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return DEFAULT_COMPARED;
        }
        profiles.forEach(Profiles::check);
        profiles.stream().filter(Profiles::isBoundary).findFirst().ifPresent(subset -> {
            throw new RdfDbException("the boundary profile " + subset + " cannot be compared: the"
                    + " boundary of a scenario never changes, a new boundary is a new scenario");
        });
        return Set.copyOf(profiles);
    }

    /** The header of every parsed file, by context. */
    private Map<String, Header> headersOf(IngestParser.Result parsed) {
        Map<String, Header> headers = new LinkedHashMap<>();
        parsed.files().forEach(file -> {
            if (file.headerId() == null) {
                throw new RdfDbException("the instance file " + file.context() + " carries no md:FullModel header,"
                        + " so it cannot be a member of a snapshot of scenario '" + scenario + "'");
            }
            headers.put(file.context(), new Header(file.headerId(), file.profile(), file.terms()));
        });
        return headers;
    }

    /**
     * Which parent profile each comparable profile of the timestamp needs, and under which cache key.
     *
     * <p>A profile is comparable when the timestamp ships it, the parent's materialisation plan starts from a full
     * model of it and the parent names a state of it. A profile that fails any of those is left out here rather
     * than discovered to be uncomparable halfway through the diff, which is what lets the whole materialisation
     * be skipped when every key is already known.</p>
     */
    private Map<String, String> parentIndexKeys(IngestParser.Result parsed, MaterializationPlan plan,
                                                     Map<String, StoredModel> stateModels, String cimNamespace) {
        String fallbackBase = RdfDbMaterializer.subjectBase(plan, stateModels);
        Map<String, String> keys = Profiles.map();
        for (IngestParser.ParsedFile file : parsed.files()) {
            String subset = file.profile();
            if (file.index() == null) {
                // Not compared, or the state the database already holds
                continue;
            }
            String stateId = plan.targetState().get(subset);
            if (stateId == null || !plan.startModel().containsKey(subset)) {
                continue;
            }
            StoredModel stored = stateModels.get(stateId);
            keys.put(subset, RdfDbConnection.parentIndexKey(scenario, stateId,
                    stored == null ? -1 : stored.tripleCount(),
                    parentBaseOf(stateModels, stateId, fallbackBase), cimNamespace));
        }
        return keys;
    }

    /** The subject base the parent's statements of one profile carry. */
    private static String parentBaseOf(Map<String, StoredModel> stateModels, String stateId, String fallbackBase) {
        StoredModel parentModel = stateModels.get(stateId);
        return parentModel == null ? fallbackBase : parentModel.subjectBase();
    }

    /**
     * The decoded parent state of every profile to compare, from the cache where possible.
     *
     * <p>The point of the cache: when every profile is already indexed, the parent is <em>not</em> materialised at
     * all &mdash; no store, no graph transfer, no difference application, no decoding &mdash; which is the whole
     * of {@code materializeParent} for every timestamp of a day after the first. When anything is missing the
     * materialisation happens exactly as it always did and only the missing profiles are read out of it, so the
     * statements and their order are the ones the comparison has always seen.</p>
     */
    private Map<String, StatementDiff.Index> parentIndexesOf(Map<String, String> keys,
                                                                        MaterializationPlan plan,
                                                                        Map<String, StoredModel> stateModels,
                                                                        String cimNamespace,
                                                                        Properties importParams) {
        Map<String, StatementDiff.Index> indexes = Profiles.map();
        keys.forEach((subset, key) -> {
            StatementDiff.Index cached = connection.parentIndex(key);
            if (cached != null) {
                indexes.put(subset, cached);
            }
        });
        if (keys.isEmpty()) {
            // Nothing to compare: nothing to materialise either, and nothing that could count as a hit
            return indexes;
        }
        if (indexes.size() == keys.size()) {
            parentIndexHits.incrementAndGet();
            LOGGER.debug("Parent index cache hit for {} profile(s) of scenario '{}': no materialisation",
                    keys.size(), scenario);
            return indexes;
        }
        String fallbackBase = RdfDbMaterializer.subjectBase(plan, stateModels);
        // Only the profiles still to be indexed; the projection keeps the boundary, which is never compared
        Set<String> toIndex = Profiles.set(keys.keySet());
        toIndex.removeAll(indexes.keySet());
        try (RdfDbMaterializer.MaterialisedStore parentState = RdfDbMaterializer.materializeStore(
                connection, scenario, plan.project(toIndex), stateModels, importParams)) {
            for (Map.Entry<String, String> entry : keys.entrySet()) {
                String subset = entry.getKey();
                if (indexes.containsKey(subset)) {
                    continue;
                }
                String stateId = plan.targetState().get(subset);
                StatementDiff.Index index = TripleDiffCalculator.index(
                        SparqlAccess.statementsOf(parentState.store().getRepository(),
                                parentState.contexts().get(subset)),
                        parentBaseOf(stateModels, stateId, fallbackBase), cimNamespace);
                indexes.put(subset, index);
                connection.rememberParentIndex(entry.getValue(), index);
                LOGGER.debug("Indexed the parent {} state {} of scenario '{}': {} statement(s)",
                        subset, stateId, scenario, index.size());
            }
        }
        return indexes;
    }

    /** @return how often {@link #putAsDiff} answered a whole timestamp out of the parent index cache */
    long parentIndexCacheHits() {
        return parentIndexHits.get();
    }

    /**
     * The difference of one profile between the parent state and the file that was just parsed.
     *
     * @param parentSide    the decoded parent state of that profile
     * @param nextSide      the decoded file of that profile
     * @param parentStateId the stored model the difference supersedes
     * @param subset        the profile
     * @param header        the {@code md:FullModel} of the file, which becomes the header of the difference
     * @param cimNamespace  the CIM namespace both sides are written in
     * @param timestamp     the moment the snapshot describes
     * @return the difference
     */
    private DifferenceModel diffOf(StatementDiff.Index parentSide, StatementDiff.Index nextSide,
                                   String parentStateId, String subset, Header header, String cimNamespace,
                                   Instant timestamp) {
        DifferenceModelHeader diffHeader = DifferenceModelHeader.builder(header.id,
                        Profiles.subset(subset).orElseThrow(), cimNamespace)
                .version(intOf(header.term(RdfDbVocabulary.MODEL_VERSION), 1))
                .description(header.term(RdfDbVocabulary.MODEL_DESCRIPTION))
                .modelingAuthoritySet(header.term(RdfDbVocabulary.MODEL_MODELING_AUTHORITY_SET))
                .profiles(header.texts(RdfDbVocabulary.MODEL_PROFILE))
                .dependentOn(header.texts(RdfDbVocabulary.MODEL_DEPENDENT_ON))
                .supersedes(List.of(parentStateId))
                .scenarioTime(timestamp.atZone(ZoneOffset.UTC))
                .created(ZonedDateTime.now())
                .build();
        return TripleDiffCalculator.diff(parentSide, nextSide, diffHeader);
    }

    private static int intOf(String text, int fallback) {
        try {
            return text == null ? fallback : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * A writer that believes it is creating a timestamp the tree already holds.
     *
     * <p>It is told what actually happened rather than being handed the generic "supersedes the wrong model"
     * message: what its differences supersede is the state of the timestamp's <em>pin</em>, which is what a
     * timestamp root supersedes, so it is not a stale version writer but the loser of a race for the root. Asked
     * only when the differences do not supersede the head, so the ordinary write costs no request here.</p>
     */
    private void checkNotASecondRoot(List<DifferenceModel> models, SnapshotInfo head, String authority,
                                     Instant timestamp) {
        if (models.isEmpty() || supersedes(models, head)) {
            return;
        }
        SnapshotInfo timestampRoot = head.iri().equals(head.timestampRoot()) ? head
                : info(head.timestampRoot()).orElse(null);
        if (timestampRoot == null || timestampRoot.parent() == null) {
            // The base timestamp has no root of its own to race for: its root is the tree's
            return;
        }
        Optional<SnapshotInfo> pin = info(timestampRoot.parent());
        if (pin.isPresent() && supersedes(models, pin.get())) {
            throw new RdfDbConflictException("timestamp " + timestamp + " of modelling authority '" + authority
                    + "' of scenario '" + scenario + "' already has a root (version " + head.version() + "); a new"
                    + " version of it must supersede its head, so update the network to " + head.ref()
                    + " and re-record");
        }
    }

    /** Whether every difference supersedes exactly the state of its profile at a snapshot. */
    private static boolean supersedes(List<DifferenceModel> models, SnapshotInfo snapshot) {
        return models.stream().allMatch(model -> model.header().supersedes()
                .equals(List.of(Objects.requireNonNullElse(
                        snapshot.state().get(Profiles.of(model.header().subset())), ""))));
    }

    private void checkSupersedes(List<DifferenceModel> models, SnapshotInfo parent, String what) {
        for (DifferenceModel model : models) {
            String subset = Profiles.of(model.header().subset());
            List<String> supersedes = model.header().supersedes();
            String expected = parent.state().get(subset);
            if (supersedes.size() != 1 || !supersedes.get(0).equals(expected)) {
                String found = supersedes.isEmpty() ? "nothing" : String.join(", ", supersedes);
                String elsewhere = "";
                if (supersedes.size() == 1) {
                    Optional<String> other = connection.catalog(scenario).scenarioOf(supersedes.get(0));
                    if (other.isPresent()) {
                        elsewhere = " (" + supersedes.get(0) + " is stored in scenario '" + other.get()
                                + "'; diffs never cross scenarios)";
                    }
                }
                throw new RdfDbConflictException("difference model of subset " + subset
                        + " supersedes " + found + " but " + what + " " + parent.ref() + " is at " + expected
                        + ": update the network to " + what + " and re-record" + elsewhere);
            }
        }
    }

    /** Re-read the chain and say which rule the silent guard refused on. */
    private String diagnose(SnapshotRef address, SnapshotInfo parent, VersionRegistry.Resolved version,
                            String detail) {
        Optional<SnapshotInfo> nowHead = head(address.modellingAuthority(), address.timestamp());
        if (nowHead.isPresent() && !nowHead.get().iri().equals(parent.iri())) {
            return "snapshot " + parent.ref() + " already has successor " + nowHead.get().ref()
                    + " - the linear scheme allows no forks; update to the head first";
        }
        if (nowHead.isPresent() && nowHead.get().rank() >= version.rank()) {
            return "version '" + version.name() + "' (rank " + version.rank() + ") is not above the head '"
                    + nowHead.get().version() + "' (rank " + nowHead.get().rank() + ") of " + nowHead.get().ref()
                    + ": a new version ranks above the head it is written on";
        }
        return "the snapshot " + address + " was not written: " + detail;
    }

    // ------------------------------------------------------------------ rollovers

    /**
     * Make a snapshot a rollover: the snapshot later timestamps of its tree are ingested against by default.
     *
     * <p>A day drifts. In the morning every timestamp is "the base plus a handful of differences"; by the evening
     * the equipment of the day has moved far enough from the base that every ingestion compares against a stale
     * state and stores the drift again. A rollover moves the default {@linkplain #putAsDiff pin} forward: a
     * timestamp ingested afterwards, at or after the rollover's own timestamp, hangs off the latest rollover at or
     * before it instead of the root, so its difference is the change since the rollover. Roll over when the
     * equipment difference against the pin grows, not on every change of the schedule.</p>
     *
     * <p>A rollover is checkpointed in the same call ({@link Checkpoint}): every timestamp pinned to it will start
     * its materialisation at it, so the chain above it is folded once, here. Flagging is one {@code INSERT DATA}
     * after the checkpoint, so a failure halfway leaves a checkpointed snapshot that is not yet a rollover &mdash;
     * correct either way. The call is idempotent; the root of every tree is a rollover from the start. Nothing
     * that was written before changes: a pin is chosen when a timestamp is written.</p>
     *
     * @param ref the address of the snapshot
     * @return the snapshot, flagged and with full graphs
     * @throws RdfDbException if the scenario holds no snapshot at that address
     */
    public SnapshotInfo rollover(SnapshotRef ref) {
        SnapshotInfo info = require(ref);
        if (info.rollover() && info.hasFull()) {
            return info;
        }
        SnapshotInfo checkpointed = Checkpoint.create(connection, info);
        if (checkpointed.rollover()) {
            return checkpointed;
        }
        sparql().update(RdfDbVocabulary.PREFIXES + "INSERT DATA {" + graphClause() + "{ " + SparqlText.iri(info.iri())
                + " pdb:rollover " + SparqlText.bool(true) + " } }");
        SnapshotInfo flagged = info(info.iri()).orElseThrow(() -> noSuchSnapshot(info.iri()));
        LOGGER.info("Snapshot {} of scenario '{}' is a rollover", flagged, scenario);
        return flagged;
    }

    // ------------------------------------------------------------------ dropping and checking

    /**
     * Drop every graph and every node this layer wrote for the scenario.
     *
     * <p>Only this scenario: the prefix ends with a slash, so scenario {@code "a"} never matches {@code "ab"}.</p>
     */
    public void dropAll() {
        String prefix = RdfDbNames.scenarioPrefix(scenario);
        List<Map<String, Value>> rows = sparql().select("SELECT DISTINCT ?g WHERE { GRAPH ?g { } "
                + "FILTER(STRSTARTS(STR(?g), " + SparqlText.str(prefix) + ")) }");
        List<String> graphs = new ArrayList<>(rows.stream().map(row -> row.get("g"))
                .filter(Objects::nonNull).map(Value::stringValue).toList());
        if (!graphs.contains(metaGraph)) {
            graphs.add(metaGraph);
        }
        connection.catalog(scenario).dropGraphs(graphs);
        schemaChecked = false;
        registry.invalidate();
        rootByAuthority.clear();
        connection.forgetParentIndexes(scenario);
    }

    /**
     * Drop snapshots nothing was built on: their nodes, their difference members and checkpoint copies, and the
     * graphs those name.
     *
     * <p>The one primitive that removes snapshots, for {@link VersionRegistry#delete} of a transient version. Every
     * snapshot must be a leaf &mdash; no snapshot outside the list names it as its parent, by either edge &mdash;
     * and none may be the root of its tree, whose full graphs are the tree's base. After the check one request
     * drops the graphs and the nodes; the decoded parent states and the cached graphs of the scenario are
     * forgotten.</p>
     *
     * @param leaves the snapshots to drop
     * @throws RdfDbException naming the first snapshot that is a root or has a child outside the list; nothing is
     *                        dropped then
     */
    void dropSnapshots(List<SnapshotInfo> leaves) {
        if (leaves.isEmpty()) {
            return;
        }
        Set<String> dropped = new LinkedHashSet<>();
        leaves.forEach(leaf -> {
            check(leaf.ref());
            dropped.add(leaf.iri());
        });
        for (SnapshotInfo info : snapshots()) {
            if (info.isRoot() && dropped.contains(info.iri())) {
                throw new RdfDbException("snapshot " + info + " of scenario '" + scenario + "' is the root of its"
                        + " tree and is not dropped: nothing was dropped");
            }
            if (info.parent() != null && dropped.contains(info.parent()) && !dropped.contains(info.iri())) {
                throw new RdfDbException("snapshot " + info.parent() + " of scenario '" + scenario + "' has the child "
                        + info.iri() + ", and only a snapshot nothing was built on is dropped: nothing was dropped");
            }
        }
        String values = dropped.stream().map(SparqlText::iri).collect(Collectors.joining(" "));
        // The member differences and the checkpoint copies of the dropped snapshots, and the graphs they name
        List<Map<String, Value>> rows = select("SELECT DISTINCT ?node ?g WHERE {" + graphClause() + "{ VALUES ?s { "
                + values + " } { ?s pdb:member ?node . ?node pdb:kind pdb:Diff } UNION { ?node a pdb:Materialized ;"
                + " pdb:snapshot ?s } OPTIONAL { { ?node pdb:forwardGraph ?g } UNION { ?node pdb:reverseGraph ?g }"
                + " UNION { ?node a pdb:Materialized ; pdb:graph ?g } } } }");
        Set<String> nodes = new LinkedHashSet<>(dropped);
        Set<String> graphs = new LinkedHashSet<>();
        rows.forEach(row -> {
            nodes.add(SnapshotRows.text(row, "node"));
            String graph = SnapshotRows.text(row, "g");
            if (graph != null) {
                graphs.add(graph);
            }
        });
        StringBuilder update = new StringBuilder(RdfDbVocabulary.PREFIXES);
        graphs.forEach(graph -> update.append("DROP SILENT GRAPH ").append(SparqlText.iri(graph)).append(" ; "));
        String meta = SparqlText.iri(metaGraph);
        update.append(nodes.stream().map(node -> "DELETE WHERE { GRAPH " + meta + " { " + SparqlText.iri(node)
                + " ?p ?o } }").collect(Collectors.joining(" ; ")));
        sparql().update(update.toString());
        connection.forgetParentIndexes(scenario);
        connection.invalidateCache(scenario);
        LOGGER.info("Dropped the snapshot(s) {} of scenario '{}' with {} graph(s)", dropped, scenario,
                graphs.size());
    }

    /**
     * Check the invariants of the snapshot trees of this scenario.
     *
     * <p>Depth, state and the edge kinds are derived when a snapshot is written, so this is not how correctness is
     * achieved &mdash; it is how it is asserted. Tests call it after every scenario they build, and a caller that
     * suspects a half-written state can call it too.</p>
     *
     * @throws RdfDbException naming the first invariant that does not hold
     */
    public void verify() {
        List<SnapshotInfo> all = snapshots();
        if (all.isEmpty()) {
            return;
        }
        Map<String, SnapshotInfo> byIri = new LinkedHashMap<>();
        all.forEach(info -> byIri.put(info.iri(), info));
        Map<String, SnapshotInfo> roots = new TreeMap<>();
        all.stream().filter(SnapshotInfo::isRoot).forEach(root -> {
            if (roots.put(root.modellingAuthority(), root) != null) {
                throw new RdfDbException("modelling authority '" + root.modellingAuthority() + "' of scenario '"
                        + scenario + "' has more than one root snapshot: a tree has one base grid model");
            }
        });
        Set<SnapshotRef> timestampRoots = new LinkedHashSet<>();
        all.stream().filter(info -> info.iri().equals(info.timestampRoot()))
                .forEach(info -> {
                    if (!timestampRoots.add(SnapshotRef.latestAt(scenario, info.modellingAuthority(),
                            info.timestamp()))) {
                        throw new RdfDbException("modelling authority '" + info.modellingAuthority() + "' of scenario '"
                                + scenario + "' has more than one root at timestamp " + info.timestamp());
                    }
                });
        verifySharedBoundary(roots);
        String prefix = RdfDbNames.scenarioPrefix(scenario);
        Set<String> versionChildren = new LinkedHashSet<>();
        for (SnapshotInfo info : all) {
            if (!info.iri().equals(RdfDbNames.snapshot(scenario, info.modellingAuthority(), info.timestamp(),
                    info.version())) || !info.iri().startsWith(prefix)) {
                throw new RdfDbException("snapshot " + info.iri() + " is not named by its address " + info.ref());
            }
            if (info.rank() == 0) {
                throw new RdfDbException("snapshot " + info + " of scenario '" + scenario + "' carries the version '"
                        + info.version() + "', which its version registry does not hold " + registry);
            }
            if (info.isRoot()) {
                verifyRoot(info);
                continue;
            }
            SnapshotInfo parent = byIri.get(info.parent());
            if (parent == null) {
                throw new RdfDbException("snapshot " + info + " of scenario '" + scenario + "' names the parent "
                        + info.parent() + ", which this scenario does not hold");
            }
            if (!parent.modellingAuthority().equals(info.modellingAuthority())) {
                throw new RdfDbException("snapshot " + info + " of scenario '" + scenario + "' derives from " + parent
                        + " of another modelling authority");
            }
            if (info.depth() != parent.depth() + 1) {
                throw new RdfDbException("snapshot " + info + " has depth " + info.depth() + " but its parent "
                        + parent + " has depth " + parent.depth());
            }
            if (info.edge() == SnapshotInfo.EdgeKind.VERSION && !versionChildren.add(parent.iri())) {
                throw new RdfDbException("snapshot " + parent + " of scenario '" + scenario + "' has more than one"
                        + " version successor: the chain forked");
            }
            if (info.edge() == SnapshotInfo.EdgeKind.VERSION && info.rank() <= parent.rank()) {
                throw new RdfDbException("snapshot " + info + " of scenario '" + scenario + "' has a version not"
                        + " ranking above its parent " + parent + "'s");
            }
            verifyTimestampRoot(info, parent, byIri);
            verifyState(info, parent);
        }
    }

    /** Every tree of the scenario states the same boundary models. */
    private void verifySharedBoundary(Map<String, SnapshotInfo> roots) {
        Map<String, String> first = null;
        for (SnapshotInfo root : roots.values()) {
            Map<String, String> boundary = boundaryOf(root.state());
            if (first != null && !first.equals(boundary)) {
                throw new RdfDbException("the roots of scenario '" + scenario + "' do not share one boundary: "
                        + root + " states " + boundary + ", another root " + first);
            }
            first = boundary;
        }
    }

    /**
     * A timestamp root hangs off a snapshot of another timestamp of its own tree, its pin; every other snapshot
     * belongs to its parent's timestamp.
     */
    private void verifyTimestampRoot(SnapshotInfo info, SnapshotInfo parent, Map<String, SnapshotInfo> byIri) {
        if (info.edge() == SnapshotInfo.EdgeKind.TIMESTAMP) {
            if (!info.iri().equals(info.timestampRoot())) {
                throw new RdfDbException("the timestamp root " + info + " of scenario '" + scenario + "' names "
                        + info.timestampRoot() + " as its own root");
            }
            if (parent.timestamp().equals(info.timestamp())) {
                throw new RdfDbException("the timestamp root " + info + " of scenario '" + scenario + "' hangs off "
                        + parent + ", which is at its own timestamp: a pin is a snapshot of another timestamp");
            }
            return;
        }
        if (!info.timestamp().equals(parent.timestamp())) {
            throw new RdfDbException("snapshot " + info + " of scenario '" + scenario + "' is a version of "
                    + parent + ", which is at another timestamp");
        }
        if (!info.timestampRoot().equals(parent.timestampRoot())) {
            throw new RdfDbException("snapshot " + info + " of scenario '" + scenario + "' names the timestamp root "
                    + info.timestampRoot() + " but its parent " + parent + " names " + parent.timestampRoot());
        }
        if (byIri.get(info.timestampRoot()) == null) {
            throw new RdfDbException("snapshot " + info + " of scenario '" + scenario + "' names a timestamp root"
                    + " this scenario does not hold");
        }
    }

    private void verifyRoot(SnapshotInfo info) {
        if (info.kind() != SnapshotInfo.Kind.FULL || info.fullModels().isEmpty()) {
            throw new RdfDbException("the root " + info + " of scenario '" + scenario + "' is not a full snapshot");
        }
        if (!info.state().equals(info.fullModels())) {
            throw new RdfDbException("the root " + info + " of scenario '" + scenario + "' has a state "
                    + info.state() + " that differs from its full models " + info.fullModels());
        }
    }

    private void verifyState(SnapshotInfo info, SnapshotInfo parent) {
        Map<String, String> expected = Profiles.map(parent.state());
        Set<String> members = new LinkedHashSet<>(info.members());
        info.state().forEach((subset, id) -> {
            if (members.contains(id)) {
                expected.put(subset, id);
            }
        });
        if (!expected.equals(info.state())) {
            throw new RdfDbException("snapshot " + info + " of scenario '" + scenario + "' states " + info.state()
                    + " but its parent " + parent + " states " + parent.state() + " and its members are "
                    + info.members());
        }
    }

    // ------------------------------------------------------------------ SPARQL fragments

    private String rootWrite(Map<String, Header> own, Map<String, String> graphs,
                             CgmesTripleStoreLoader.Result parsed, Map<String, String> state,
                             boolean first, RdfDbDifferenceSink.SnapshotWrite root, Map<String, Long> counts) {
        String subjectBase = ModelCatalog.subjectBaseOf(parsed.baseName());
        ZonedDateTime now = ZonedDateTime.now();
        String meta = SparqlText.iri(metaGraph);
        StringBuilder update = new StringBuilder(RdfDbVocabulary.PREFIXES).append("INSERT { GRAPH ")
                .append(meta).append(" { ");
        own.forEach((context, header) -> appendFullModelNode(update, header,
                new FullGraph(header.subset, graphs.get(context), counts.getOrDefault(context, -1L),
                        subjectBase, parsed.cimNamespace()), root.iri(), now));
        // The schema marker goes with every root: written twice it is the same triple
        update.append(SparqlText.iri(schemaNode)).append(' ').append(SparqlText.iri(RdfDbVocabulary.SCHEMA))
                .append(' ').append(SparqlText.integer(RdfDbVocabulary.SCHEMA_VERSION)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SCENARIO)).append(' ').append(SparqlText.str(scenario))
                .append(" . ");
        if (root.version().bootstraps()) {
            registry.appendBootstrap(update, root.version(), now);
        }
        root.appendTo(update, scenario, state.values(), now);
        update.append(" } } WHERE { FILTER NOT EXISTS { GRAPH ").append(meta)
                .append(" { ?x a pdb:Snapshot ; pdb:depth ").append(SparqlText.integer(0))
                .append(" ; pdb:modellingAuthority ").append(SparqlText.str(root.modellingAuthority()))
                .append(" } } FILTER NOT EXISTS { GRAPH ").append(meta)
                .append(" { ").append(SparqlText.iri(root.iri())).append(" ?p ?o } }");
        if (first) {
            // The boundary was checked against no root at all: a root written meanwhile has its own boundary
            update.append(" FILTER NOT EXISTS { GRAPH ").append(meta).append(" { ?any a pdb:Snapshot ; pdb:depth ")
                    .append(SparqlText.integer(0)).append(" } }");
        }
        own.values().forEach(header -> update.append(" FILTER NOT EXISTS { GRAPH ")
                .append(meta).append(" { ").append(SparqlText.iri(header.id))
                .append(" ?p1 ?o1 } }"));
        registry.appendWriteGuards(update, root.version());
        return update.append(" }").toString();
    }

    /** Where one parsed instance file of a root went, and what its statements look like. */
    private record FullGraph(String subset, String graphIri, long tripleCount, String subjectBase,
                             String cimNamespace) {
    }

    private void appendFullModelNode(StringBuilder update, Header header, FullGraph graph, String snapshotIri,
                                     ZonedDateTime now) {
        update.append(SparqlText.iri(header.id)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.RDF_TYPE)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.FULL_MODEL)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.KIND)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.FULL)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SUBSET)).append(' ')
                .append(SparqlText.str(graph.subset())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SCENARIO)).append(' ')
                .append(SparqlText.str(scenario)).append(" ; ")
                // A string, like every other pdb:graph of this layer: the in-process backend names graphs by the
                // plain file name, which is not always writable as an IRI
                .append(SparqlText.iri(RdfDbVocabulary.GRAPH)).append(' ')
                .append(SparqlText.str(graph.graphIri())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SNAPSHOT)).append(' ')
                .append(SparqlText.iri(snapshotIri)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.CHAIN_DEPTH)).append(' ')
                .append(SparqlText.integer(0)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.TRIPLE_COUNT)).append(' ')
                .append(SparqlText.integer(graph.tripleCount())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SUBJECT_BASE)).append(' ')
                .append(SparqlText.str(graph.subjectBase())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.CIM_NAMESPACE)).append(' ')
                .append(SparqlText.str(graph.cimNamespace())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.CREATED)).append(' ')
                .append(SparqlText.dateTime(now));
        header.terms.forEach((predicate, values) -> values.forEach(value -> update.append(" ; ")
                .append(SparqlText.iri(predicate)).append(' ')
                .append(value instanceof IRI iri ? SparqlText.iri(iri.stringValue())
                        : SparqlText.str(value.stringValue()))));
        update.append(" . ");
    }

    // ------------------------------------------------------------------ scratch store helpers

    /** The {@code md:FullModel} header of one parsed instance file, and the profile the file carries. */
    private record Header(String id, String subset, Map<String, List<Value>> terms) {

        String term(String predicate) {
            List<Value> values = terms.get(predicate);
            return values == null || values.isEmpty() ? null : values.get(0).stringValue();
        }

        List<String> texts(String predicate) {
            return terms.getOrDefault(predicate, List.of()).stream().map(Value::stringValue).toList();
        }
    }

    private Map<String, Header> readHeaders(SailRepository repository, List<String> contextNames) {
        Map<String, Map<String, Map<String, List<Value>>>> byGraph = new LinkedHashMap<>();
        try (var conn = repository.getConnection()) {
            var result = conn.prepareTupleQuery(RdfDbVocabulary.PREFIXES
                    + "SELECT ?g ?m ?p ?o WHERE { GRAPH ?g { ?m a md:FullModel ; ?p ?o } }").evaluate();
            result.forEach(bindings -> {
                String graph = bindings.getValue("g").stringValue();
                String model = bindings.getValue("m").stringValue();
                byGraph.computeIfAbsent(graph, k -> new LinkedHashMap<>())
                        .computeIfAbsent(model, k -> new LinkedHashMap<>())
                        .computeIfAbsent(bindings.getValue("p").stringValue(), k -> new ArrayList<>())
                        .add(bindings.getValue("o"));
            });
        }
        Map<String, Header> headers = new LinkedHashMap<>();
        for (String context : contextNames) {
            Map<String, Map<String, List<Value>>> nodes = byGraph.get(context);
            if (nodes == null || nodes.isEmpty()) {
                throw new RdfDbException("the instance file " + context + " carries no md:FullModel header, so it"
                        + " cannot be a member of a snapshot of scenario '" + scenario + "'");
            }
            String id = new TreeSet<>(nodes.keySet()).first();
            Map<String, List<Value>> terms = new LinkedHashMap<>();
            nodes.get(id).forEach((predicate, values) -> {
                if (predicate.startsWith(RdfDbVocabulary.MD_NS) && !RdfDbVocabulary.RDF_TYPE.equals(predicate)) {
                    terms.put(predicate, values);
                }
            });
            headers.put(context, new Header(id, Profiles.ofContextName(context), terms));
        }
        return headers;
    }

    private static Map<String, Long> counts(SailRepository repository, Set<String> contextNames) {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (var conn = repository.getConnection()) {
            contextNames.forEach(name -> counts.put(name, conn.size(conn.getValueFactory().createIRI(name))));
        }
        return counts;
    }

    /** The scenario time of the steady state file, or of the first file that states one. */
    private Instant scenarioTimeOf(Map<String, Header> headers) {
        return headers.values().stream()
                .sorted(Comparator.comparingInt(h -> Profiles.SSH.equals(h.subset) ? 0 : 1))
                .map(h -> h.term(RdfDbVocabulary.MODEL_SCENARIO_TIME))
                .filter(Objects::nonNull)
                .findFirst()
                .map(SnapshotRef::scenarioTime)
                .orElseThrow(() -> new RdfDbException("the instance files state no md:Model.scenarioTime: pass a"
                        + " timestamp in the address of the root of scenario '" + scenario + "'"));
    }
}
