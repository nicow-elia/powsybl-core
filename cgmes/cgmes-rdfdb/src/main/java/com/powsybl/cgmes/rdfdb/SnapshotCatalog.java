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
import com.powsybl.cgmes.conversion.diff.CgmesDiffImport;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.triplestore.CgmesTripleStoreLoader;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.triplestore.api.TripleStoreOptions;
import com.powsybl.triplestore.impl.rdf4j.TripleStoreRDF4J;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The snapshots of one scenario: what states the database holds, and how a new one is written.
 *
 * <h2>What a snapshot is, and why it is a resource</h2>
 * <p>A version is a {@code pdb:Snapshot} node plus immutable named graphs, not a tag on the objects of the model.
 * Tagging would mean a version property on every object &mdash; or reification &mdash; a version filter in all
 * eighty-one catalog queries of the CGMES conversion, a forked catalog to maintain and mutable data. Named graphs
 * leave the queries untouched, make every graph cacheable by its IRI, and map one to one onto CGMES itself:
 * {@code md:Model.Supersedes} is the chain of one profile, {@code md:Model.DependentOn} the dependency between
 * them, {@code md:Model.scenarioTime} the timestep. What the snapshot node adds on top is the one thing CGMES has
 * no term for: which models of <em>different</em> profiles belong together.</p>
 *
 * <h2>The keys</h2>
 * <p>{@code (scenario, timestep, version)}. The scenario is the outermost and is required everywhere: it is one
 * base grid model, one day, and a database is expected to hold several. A scenario has exactly <strong>one
 * root</strong> snapshot; another day is another scenario, never a second root. Below it the version chain is
 * linear: a snapshot has at most one child along a {@code pdb:VersionEdge}, and an attempt to add a second is
 * refused rather than forking. Order is what the chain says, never what comparing two version strings says.</p>
 *
 * <h2>Nothing crosses a scenario</h2>
 * <p>One catalogue is bound to one scenario and every query it sends names that scenario's metadata graph. A
 * {@link SnapshotRef} naming another scenario is refused before any query is sent, and no {@code pdb:parent},
 * {@code pdb:state}, {@code pdb:member} or {@code pdb:full} link ever points out of the scenario it was written
 * in &mdash; {@link #verify()} checks exactly that.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class SnapshotCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotCatalog.class);

    private static final CgmesSubset SSH = CgmesSubset.STEADY_STATE_HYPOTHESIS;
    private static final CgmesSubset EQ = CgmesSubset.EQUIPMENT;

    private final RdfDbConnection connection;
    private final String scenario;
    private final String metaGraph;
    private final String catalogNode;
    private volatile CatalogNode cachedCatalogNode;
    private volatile IngestStatistics lastIngest;

    /** How often a whole timestep of this scenario was answered out of the parent index cache; read by tests. */
    private final AtomicLong parentIndexHits = new AtomicLong();

    SnapshotCatalog(RdfDbConnection connection, String scenario) {
        this.connection = Objects.requireNonNull(connection);
        this.scenario = RdfDbNames.checkScenario(scenario);
        this.metaGraph = RdfDbNames.metaGraph(scenario);
        this.catalogNode = RdfDbNames.catalogNode(scenario);
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

    // ------------------------------------------------------------------ reads

    /**
     * Whether the scenario holds any snapshot at all.
     *
     * <p>A scenario that does not is a scenario of the earlier, unversioned shape: its instance file graphs and
     * its difference chain are still readable, and the first versioned write migrates it, see
     * {@link #migrateImplicitRoot()}.</p>
     *
     * @return whether the scenario is versioned
     */
    public boolean isVersioned() {
        return sparql().ask(RdfDbVocabulary.PREFIXES + "ASK {" + graphClause() + "{ ?s a pdb:Snapshot } }");
    }

    /**
     * Every snapshot of the scenario, oldest first.
     *
     * <p>One request. The metadata graph of a scenario is small by construction, and grouping the rows here is
     * what {@link SnapshotRows} is for. That single request also carries the {@code pdb:fastPredicatesOnly} of the
     * member models, which is what {@link SnapshotInfo#fast()} is derived from.</p>
     *
     * @return the snapshots, ordered by timestep and then by depth
     */
    public List<SnapshotInfo> snapshots() {
        List<SnapshotInfo> all = new ArrayList<>(SnapshotRows.group(scenario, select(
                "SELECT ?s ?p ?o ?sub ?mkind ?mfast WHERE {" + graphClause() + "{ ?s a pdb:Snapshot ; ?p ?o "
                        + " OPTIONAL { ?o pdb:subset ?sub }" + SnapshotRows.MEMBER_FAST_CLAUSE + "} }"), "s")
                .values());
        all.sort(SnapshotRows.byTimestepAndDepth());
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
        return Optional.ofNullable(SnapshotRows.group(scenario, select(
                "SELECT ?s ?p ?o ?sub ?mkind ?mfast WHERE {" + graphClause() + "{ BIND(" + SparqlText.iri(snapshotIri)
                        + " AS ?s) ?s a pdb:Snapshot ; ?p ?o OPTIONAL { ?o pdb:subset ?sub }"
                        + SnapshotRows.MEMBER_FAST_CLAUSE + "} }"), "s")
                .get(snapshotIri));
    }

    /**
     * Resolve an address to the snapshot it names.
     *
     * @param ref the address; a {@code null} version means the head of the chain, a {@code null} timestep the base
     *            timestep of this scenario
     * @return the snapshot, or empty when the scenario holds none at that address
     * @throws RdfDbException if the address names another scenario
     */
    public Optional<SnapshotInfo> find(SnapshotRef ref) {
        check(ref);
        String timestep = ref.timestep() == null ? baseTimestepOrNull() : ref.timestep();
        if (timestep == null) {
            return Optional.empty();
        }
        String pattern = ref.isLatest()
                ? " FILTER NOT EXISTS {" + graphClause() + "{ ?c pdb:parent ?s ; pdb:edge pdb:VersionEdge } } "
                : " ";
        String version = ref.isLatest() ? "" : " ; pdb:version " + SparqlText.str(ref.version());
        Map<String, SnapshotInfo> found = SnapshotRows.group(scenario, select(
                "SELECT ?s ?p ?o ?sub ?mkind ?mfast WHERE {" + graphClause() + "{ ?s a pdb:Snapshot ; pdb:timestep "
                        + SparqlText.str(timestep) + version + " ; ?p ?o OPTIONAL { ?o pdb:subset ?sub }"
                        + SnapshotRows.MEMBER_FAST_CLAUSE + "}" + pattern + "}"), "s");
        if (found.size() > 1) {
            throw new RdfDbException("the metadata graph of scenario '" + scenario + "' is inconsistent: timestep "
                    + timestep + " has " + found.size() + " heads " + found.keySet() + ", and the version chain of"
                    + " a timestep is linear. No write of this release can produce that state");
        }
        return found.values().stream().findFirst();
    }

    /**
     * The newest version of a timestep.
     *
     * @param timestep the canonical timestep, or {@code null} for the base timestep of this scenario
     * @return the head snapshot, or empty
     */
    public Optional<SnapshotInfo> head(String timestep) {
        return find(SnapshotRef.latestAt(scenario, timestep));
    }

    /**
     * The root snapshot of the scenario.
     *
     * @return the root, or empty when the scenario is not versioned
     */
    public Optional<SnapshotInfo> root() {
        return SnapshotRows.group(scenario, select(
                "SELECT ?s ?p ?o ?sub ?mkind ?mfast WHERE {" + graphClause() + "{ ?s a pdb:Snapshot ; pdb:depth "
                        + SparqlText.integer(0) + " ; ?p ?o OPTIONAL { ?o pdb:subset ?sub }"
                        + SnapshotRows.MEMBER_FAST_CLAUSE + "} }"), "s")
                .values().stream().findFirst();
    }

    /**
     * The timestep of the root of this scenario, which is the day it describes.
     *
     * @return the canonical timestep
     * @throws RdfDbException if the scenario holds no snapshot
     */
    public String baseTimestep() {
        String base = baseTimestepOrNull();
        if (base == null) {
            throw new RdfDbException("scenario '" + scenario + "' has no root snapshot: putFull first");
        }
        return base;
    }

    /**
     * @return the zone offset the labels of this scenario are written in, {@code Z} when unknown
     */
    public String baseOffset() {
        CatalogNode node = catalogNode();
        return node == null || node.offset == null ? "Z" : node.offset;
    }

    private String baseTimestepOrNull() {
        CatalogNode node = catalogNode();
        return node == null ? null : node.timestep;
    }

    private CatalogNode catalogNode() {
        CatalogNode cached = cachedCatalogNode;
        if (cached != null) {
            return cached;
        }
        List<Map<String, Value>> rows = select("SELECT ?p ?o WHERE {" + graphClause() + "{ "
                + SparqlText.iri(catalogNode) + " ?p ?o } }");
        String timestep = null;
        String offset = null;
        for (Map<String, Value> row : rows) {
            Value p = row.get("p");
            Value o = row.get("o");
            if (p == null || o == null) {
                continue;
            }
            if (RdfDbVocabulary.BASE_TIMESTEP.equals(p.stringValue())) {
                timestep = o.stringValue();
            } else if (RdfDbVocabulary.BASE_OFFSET.equals(p.stringValue())) {
                offset = o.stringValue();
            }
        }
        if (timestep == null) {
            return null;
        }
        CatalogNode node = new CatalogNode(timestep, offset);
        cachedCatalogNode = node;
        return node;
    }

    /** The per-scenario catalogue node, cached: it is written once by {@code putFull} and never changed. */
    private record CatalogNode(String timestep, String offset) {
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
        Map<CgmesSubset, String> ids = NetworkIdentity.modelIds(network);
        return byState(ids);
    }

    /**
     * The deepest snapshot whose state matches the given model identifiers.
     *
     * @param ids the model identifier per profile a network holds
     * @return the snapshot, or empty
     */
    Optional<SnapshotInfo> byState(Map<CgmesSubset, String> ids) {
        List<String> patterns = new ArrayList<>();
        for (CgmesSubset subset : List.of(EQ, SSH)) {
            String id = ids.get(subset);
            if (id != null) {
                patterns.add(" ; pdb:state " + SparqlText.iri(id));
            }
        }
        if (patterns.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Value>> rows = select("SELECT ?s ?d WHERE {" + graphClause()
                + "{ ?s a pdb:Snapshot ; pdb:depth ?d" + String.join("", patterns) + " } } ORDER BY DESC(?d)"
                + " LIMIT 1");
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return info(rows.get(0).get("s").stringValue());
    }

    /**
     * Resolve a version and a timestep text into an address of this scenario.
     *
     * <p>The timestep text may be an ISO instant, an offset date-time, a {@code "8:30"} label of this scenario's
     * base day, or {@code null} for the base timestep. A label is <strong>always</strong> resolved against this
     * scenario's own base day and offset, so the same label means two different moments in two scenarios that
     * describe two days.</p>
     *
     * @param version      the version label, or {@code null} for the newest one
     * @param timestepText the timestep text, or {@code null}
     * @return the address
     */
    public SnapshotRef resolve(String version, String timestepText) {
        if (timestepText == null || timestepText.isBlank()) {
            return SnapshotRef.of(scenario, version);
        }
        if (Timesteps.isLabel(timestepText)) {
            return new SnapshotRef(scenario, version,
                    Timesteps.resolveLabel(timestepText, baseTimestep(), baseOffset()));
        }
        return SnapshotRef.of(scenario, version, timestepText);
    }

    /**
     * One timestep of this scenario: its root, its head and how many versions it holds.
     *
     * @param scenario   the scenario
     * @param timestep   the canonical timestep
     * @param label      the {@code HH:MM} label
     * @param root       the IRI of the timestep's root snapshot
     * @param head       the IRI of the newest version of the timestep
     * @param versionCount how many snapshots the timestep holds
     * @param pinnedBase the IRI of the base-chain snapshot the root hangs off, {@code null} for the base timestep
     */
    public record TimestepInfo(String scenario, String timestep, String label, String root, String head,
                               int versionCount, String pinnedBase) {
    }

    /**
     * The timesteps of this scenario, oldest first.
     *
     * @return one row per timestep
     */
    public List<TimestepInfo> timesteps() {
        Map<String, List<SnapshotInfo>> byRoot = new LinkedHashMap<>();
        snapshots().forEach(info -> byRoot.computeIfAbsent(info.timestepRoot(), k -> new ArrayList<>()).add(info));
        List<TimestepInfo> rows = new ArrayList<>();
        byRoot.forEach((rootIri, versions) -> {
            SnapshotInfo rootInfo = versions.stream().filter(info -> info.iri().equals(rootIri)).findFirst()
                    .orElse(versions.get(0));
            SnapshotInfo headInfo = versions.stream().max(Comparator.comparingInt(SnapshotInfo::depth))
                    .orElse(rootInfo);
            rows.add(new TimestepInfo(scenario, rootInfo.timestep(), rootInfo.timestepLabel(), rootIri, headInfo.iri(),
                    versions.size(), rootInfo.parent()));
        });
        rows.sort(Comparator.comparing(TimestepInfo::timestep));
        return List.copyOf(rows);
    }

    /**
     * The versions of one timestep, oldest first.
     *
     * @param timestepText the timestep text, or {@code null} for the base timestep
     * @return the snapshots of that timestep
     */
    public List<SnapshotInfo> versions(String timestepText) {
        String timestep = resolve(null, timestepText).timestep();
        String canonical = timestep == null ? baseTimestep() : timestep;
        return snapshots().stream().filter(info -> info.timestep().equals(canonical)).toList();
    }

    /**
     * The label a new version on top of a timestep would get.
     *
     * <p>The head's label with its last numeric component incremented: {@code "1.1"} becomes {@code "1.2"},
     * {@code "v7"} becomes {@code "v8"}, and a label with no number at all gets {@code ".1"} appended. It is a
     * convenience, not a rule: any label the pattern of {@link SnapshotRef} accepts is a valid version.</p>
     *
     * @param timestep the canonical timestep, or {@code null} for the base timestep
     * @return the suggested label
     */
    public String nextVersionLabel(String timestep) {
        return head(timestep).map(info -> increment(info.version())).orElse("1.0");
    }

    static String increment(String label) {
        int end = label.length();
        while (end > 0 && Character.isDigit(label.charAt(end - 1))) {
            end--;
        }
        if (end == label.length()) {
            return label + ".1";
        }
        long value = Long.parseLong(label.substring(end));
        return label.substring(0, end) + (value + 1);
    }

    private List<Map<String, Value>> select(String body) {
        return sparql().select(RdfDbVocabulary.PREFIXES + body);
    }

    // ------------------------------------------------------------------ writes

    /**
     * Upload CGMES instance files as the root snapshot of this scenario.
     *
     * <p>The files are parsed once into a scratch store, their graphs are copied into immutable graphs of this
     * scenario, and one guarded request then writes a model node per file plus the snapshot that ties them
     * together. The guard is what makes "one root per scenario" a property of the database rather than of the
     * caller: a second root, or a second upload of the same model, is refused.</p>
     *
     * @param ds           the data source holding the instance files
     * @param boundary     the data source holding the boundary files, or {@code null} when {@code ds} carries them
     * @param ref          the address of the root; its version is required, its timestep may be left open and is
     *                     then taken from {@code md:Model.scenarioTime} of the steady state file
     * @param importParams the CGMES import parameters, for the identifier options of the parser
     * @param rn           where the parse reports
     * @return the root snapshot
     * @throws RdfDbConflictException if the scenario already has a root, or already holds one of the models
     */
    public SnapshotInfo putFull(ReadOnlyDataSource ds, ReadOnlyDataSource boundary, SnapshotRef ref,
                                Properties importParams, ReportNode rn) {
        check(ref);
        Objects.requireNonNull(ds);
        if (ref.version() == null) {
            throw new RdfDbException("putFull needs an explicit version: \"latest\" means nothing before the first"
                    + " snapshot of scenario '" + scenario + "' exists");
        }
        // One request, before the parse: a scenario that already has a root will refuse this write whatever the
        // files say, and parsing a fourteen-megabyte data source first to find that out is wasted work
        existingRoot().ifPresent(existing -> {
            throw new RdfDbConflictException("scenario '" + scenario + "' timestep " + existing.timestep()
                    + " already has a root snapshot (version " + existing.version() + "); use putDiff or"
                    + " Checkpoint. Another day is another scenario");
        });
        ReportNode report = rn == null ? ReportNode.NO_OP : rn;
        CgmesImport importer = TripleStoreNetworkLoader.importer();
        TripleStoreOptions options = importer.tripleStoreOptions(importParams);
        SailRepository repository = new SailRepository(new MemoryStore());
        List<String> uploaded = List.of();
        TripleStoreRDF4J scratch = new TripleStoreRDF4J(repository, options);
        try {
            CgmesTripleStoreLoader.Result parsed =
                    CgmesTripleStoreLoader.load(ds, boundary, scratch, 1, report);
            Map<String, Header> headers = readHeaders(repository, parsed.contextNames());
            String timestep = timestepOf(ref, headers);
            String offset = offsetOf(ref, headers);
            Map<String, String> localToRemote = new LinkedHashMap<>();
            headers.forEach((context, header) ->
                    localToRemote.put(context, RdfDbNames.fullGraph(scenario, header.id)));
            refuseKnownModels(headers.values().stream().map(h -> h.id).toList());

            uploaded = new GraphUploader(connection, scenario).upload(repository, localToRemote);
            String snapshotIri = RdfDbNames.snapshot(scenario, timestep, ref.version());
            String label = Timesteps.label(timestep, offset);
            sparql().update(rootWrite(headers, localToRemote, parsed, snapshotIri, timestep, label, offset,
                    counts(repository, headers.keySet())));
            cachedCatalogNode = null;
            // A new root is a new set of states, and the decoded parents of the old ones are of no use to anyone
            connection.forgetParentIndexes(scenario);
            SnapshotInfo written = info(snapshotIri).orElse(null);
            if (written == null) {
                connection.catalog(scenario).dropGraphs(uploaded);
                throw new RdfDbConflictException("the root snapshot " + ref + " was not written: another writer"
                        + " created the root of scenario '" + scenario + "' first");
            }
            LOGGER.info("Stored the root snapshot {} of scenario '{}' with {} model(s)", written, scenario,
                    headers.size());
            return written;
        } finally {
            scratch.close();
        }
    }

    private Optional<SnapshotInfo> existingRoot() {
        return root();
    }

    private void refuseKnownModels(List<String> ids) {
        Map<String, StoredModel> known = connection.catalog(scenario).models(ids);
        if (!known.isEmpty()) {
            throw new RdfDbConflictException("model(s) " + new TreeSet<>(known.keySet()) + " are already stored in"
                    + " scenario '" + scenario + "': a versioned graph is written once and never overwritten");
        }
    }

    /**
     * Write a difference set as a new version on top of the head of its timestep.
     *
     * @param set    the difference models, one per profile at most
     * @param target the address the new snapshot gets
     * @return the new snapshot
     * @throws RdfDbConflictException if the address is taken, the chain would fork, or a difference does not
     *                                supersede the state of its profile at the parent
     */
    public SnapshotInfo putDiff(DifferenceModelSet set, SnapshotRef target) {
        return putDiff(set, target, ReportNode.NO_OP);
    }

    /**
     * Write a difference set as a new version on top of the head of its timestep.
     *
     * @param set        the difference models
     * @param target     the address the new snapshot gets
     * @param reportNode where the write reports
     * @return the new snapshot
     */
    public SnapshotInfo putDiff(DifferenceModelSet set, SnapshotRef target, ReportNode reportNode) {
        Objects.requireNonNull(set);
        check(target);
        if (target.version() == null) {
            throw new RdfDbException("putDiff needs an explicit version; SnapshotCatalog.nextVersionLabel"
                    + " suggests one");
        }
        List<DifferenceModel> models = set.models().values().stream().filter(m -> !m.isEmpty()).toList();
        if (models.isEmpty()) {
            throw new RdfDbException("no difference to store as " + target + " of scenario '" + scenario + "'");
        }
        String timestep = target.timestep() == null ? baseTimestep() : target.timestep();
        checkScenarioTimes(models, timestep);
        // A timestep this scenario does not hold yet becomes a new timestep root hanging off the base chain; a
        // timestep it already holds grows another version inside itself
        Optional<SnapshotInfo> existingHead = head(timestep);
        boolean newTimestep = existingHead.isEmpty();
        SnapshotInfo parent = newTimestep ? pin(models, timestep) : existingHead.get();
        if (!newTimestep) {
            checkNotASecondRoot(models, parent, timestep);
        }
        checkVersionIsNew(timestep, target.version());
        checkSupersedes(models, parent);

        Map<CgmesSubset, String> state = new EnumMap<>(parent.state());
        Map<CgmesSubset, String> parentStates = new EnumMap<>(CgmesSubset.class);
        for (DifferenceModel model : models) {
            CgmesSubset subset = model.header().subset();
            parentStates.put(subset, parent.state().get(subset));
            state.put(subset, model.header().id());
        }
        // The fast-route capability of the new snapshot is not computed here and not written: the sink records it
        // per difference model as pdb:fastPredicatesOnly, and SnapshotInfo.fast() is the conjunction of those
        String snapshotIri = RdfDbNames.snapshot(scenario, timestep, target.version());
        RdfDbDifferenceSink.SnapshotWrite write = new RdfDbDifferenceSink.SnapshotWrite(snapshotIri,
                target.version(), timestep, Timesteps.label(timestep, baseOffset()), parent.iri(),
                newTimestep ? RdfDbVocabulary.TIMESTEP_EDGE : RdfDbVocabulary.VERSION_EDGE,
                parent.depth() + 1, state, Map.of(),
                newTimestep ? snapshotIri : parent.timestepRoot(), parentStates, newTimestep, null);

        RdfDbDifferenceSink sink = new RdfDbDifferenceSink(connection, scenario, reportNode);
        sink.writeInto(write);
        try {
            sink.accept(new DifferenceModelSet(models));
        } catch (RdfDbConflictException e) {
            throw new RdfDbConflictException(diagnose(timestep, target, parent, e.getMessage()), e);
        }
        SnapshotInfo written = info(snapshotIri).orElseThrow(() -> new RdfDbConflictException(
                diagnose(timestep, target, parent, "the snapshot node was not written")));
        LOGGER.info("Stored the snapshot {} of scenario '{}' with {} difference(s)", written, scenario,
                models.size());
        return written;
    }

    /**
     * The base-chain snapshot a new timestep root hangs off.
     *
     * <p>A timestep is "the base plus these differences", and which base is not a guess: it is the snapshot whose
     * state the differences say they supersede. It has to be on the <em>base</em> chain, so a client sitting at
     * 08:30 cannot write 08:45 as a child of it &mdash; that would make 08:45 reachable only through 08:30 and
     * turn the day into a line rather than a fan.</p>
     */
    private SnapshotInfo pin(List<DifferenceModel> models, String timestep) {
        String base = baseTimestep();
        List<String> patterns = models.stream()
                .filter(model -> model.header().supersedes().size() == 1)
                .map(model -> " ; pdb:state " + SparqlText.iri(model.header().supersedes().get(0)))
                .toList();
        if (patterns.isEmpty()) {
            throw new RdfDbConflictException("the difference models of the new timestep " + timestep
                    + " of scenario '" + scenario + "' do not each supersede exactly one stored model, so the base"
                    + " version they were made against cannot be identified");
        }
        List<Map<String, Value>> rows = select("SELECT ?s ?d WHERE {" + graphClause()
                + "{ ?s a pdb:Snapshot ; pdb:timestep " + SparqlText.str(base) + " ; pdb:depth ?d"
                + String.join("", patterns) + " } } ORDER BY DESC(?d) LIMIT 2");
        if (rows.isEmpty()) {
            throw new RdfDbConflictException("timestep roots derive from the base timestep of scenario '"
                    + scenario + "' (" + base + "), and no snapshot of it states what these difference models"
                    + " supersede; update the network to the base head first");
        }
        if (rows.size() > 1) {
            LOGGER.warn("Several base snapshots of scenario '{}' state what the new timestep {} supersedes;"
                    + " the deepest is taken", scenario, timestep);
        }
        return info(rows.get(0).get("s").stringValue()).orElseThrow(() -> new RdfDbException(
                "scenario '" + scenario + "' lost the snapshot it was pinned to"));
    }

    /**
     * A member of a snapshot describes the moment the snapshot does.
     *
     * <p>Only checked where the header says so: a difference recorded on a network need not repeat a scenario time
     * that did not change, and the snapshot's timestep is then what it belongs to.</p>
     */
    private void checkScenarioTimes(List<DifferenceModel> models, String timestep) {
        for (DifferenceModel model : models) {
            ZonedDateTime scenarioTime = model.header().scenarioTime();
            if (scenarioTime != null && !Timesteps.canonical(scenarioTime).equals(timestep)) {
                throw new RdfDbException("the difference model " + model.header().id() + " states the scenario"
                        + " time " + Timesteps.canonical(scenarioTime) + " but is written at timestep " + timestep
                        + " of scenario '" + scenario + "': a snapshot and its members describe the same moment");
            }
        }
    }

    // ------------------------------------------------------------------ ingesting a timestep from files

    /**
     * What ingesting one timestep from files cost and produced.
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
                                   Map<CgmesSubset, Integer> forwardStatements,
                                   Map<CgmesSubset, Integer> reverseStatements, Map<CgmesSubset, Boolean> fast,
                                   Set<CgmesSubset> ignored) {
    }

    /**
     * @return what the last {@link #putAsDiff} of this catalogue cost, or {@code null} when there was none
     */
    public IngestStatistics lastIngestStatistics() {
        return lastIngest;
    }

    /**
     * Write the CGMES export of one timestep as a difference against the state it derives from.
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
     * state of a day is the same state for every timestep of it, so it is materialised and decoded once and kept
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
     * <p>This release compares the <strong>equipment model and the steady state hypothesis</strong>. State
     * variables and topology change wholesale between timesteps, so a difference of them would be as large as the
     * data; their files are ignored with a report line and the snapshot inherits the parent's, which is also what
     * makes the result a state that existed. Storing them whole per timestep is the next step, and the schema
     * already allows it ({@code pdb:full} on a diff snapshot).</p>
     *
     * @param ds           the data source holding the instance files of that timestep
     * @param boundary     the data source holding the boundary files, or {@code null}
     * @param target       the address the new snapshot gets
     * @param importParams the CGMES import parameters
     * @param rn           where the ingestion reports
     * @return the new snapshot
     * @throws RdfDbConflictException if the boundary changed, or if the write is refused
     * @throws RdfDbException         if the scenario has no root, or if nothing changed
     */
    public SnapshotInfo putAsDiff(ReadOnlyDataSource ds, ReadOnlyDataSource boundary, SnapshotRef target,
                                  Properties importParams, ReportNode rn) {
        check(target);
        Objects.requireNonNull(ds);
        ReportNode report = rn == null ? ReportNode.NO_OP : rn;
        SnapshotInfo root = root().orElseThrow(() -> new RdfDbException("scenario '" + scenario
                + "' has no root snapshot: putFull first"));
        String timestep = target.timestep() == null ? baseTimestep() : target.timestep();
        SnapshotInfo parent = head(timestep).orElseGet(() -> head(baseTimestep()).orElse(root));

        // Before the files: which state each profile is compared against decides which of them has to be read in
        // full at all, and asking costs two requests against a parse of a whole export
        long tp = System.nanoTime();
        MaterializationPlan plan = connection.versionGraph(scenario).materialization(parent.iri());
        Map<String, StoredModel> stateModels = connection.catalog(scenario).models(plan.targetState().values());
        Duration planning = Duration.ofNanos(System.nanoTime() - tp);

        long t0 = System.nanoTime();
        IngestParser.Result parsed = IngestParser.read(ds, boundary, report, plan.targetState());
        Map<String, Header> headers = new LinkedHashMap<>();
        parsed.files().forEach(file -> {
            if (file.headerId() == null) {
                throw new RdfDbException("the instance file " + file.context() + " carries no md:FullModel header,"
                        + " so it cannot be a member of a snapshot of scenario '" + scenario + "'");
            }
            headers.put(file.context(), new Header(file.headerId(), file.terms()));
        });
        Duration parse = Duration.ofNanos(System.nanoTime() - t0);
        checkBoundaryUnchanged(headers, root);

        long t1 = System.nanoTime();
        String cimNamespace = parsed.cimNamespace();
        Map<CgmesSubset, String> parentKeys = parentIndexKeys(parsed, plan, stateModels, cimNamespace);
        Map<CgmesSubset, TripleDiffCalculator.Index> parentSides =
                parentIndexesOf(parentKeys, plan, stateModels, cimNamespace, importParams);
        Duration materialize = planning.plus(Duration.ofNanos(System.nanoTime() - t1));

        List<DifferenceModel> models = new ArrayList<>();
        Set<CgmesSubset> ignored = new LinkedHashSet<>();
        Map<CgmesSubset, Integer> forward = new EnumMap<>(CgmesSubset.class);
        Map<CgmesSubset, Integer> reverse = new EnumMap<>(CgmesSubset.class);
        long t2 = System.nanoTime();
        for (IngestParser.ParsedFile file : parsed.files()) {
            CgmesSubset subset = file.subset();
            if (subset != EQ && subset != SSH) {
                ignored.add(subset);
                continue;
            }
            TripleDiffCalculator.Index nextSide = file.index();
            TripleDiffCalculator.Index parentSide = parentSides.get(subset);
            if (nextSide == null || parentSide == null) {
                // The file is the state the database already holds, or the parent has no model of that profile
                continue;
            }
            DifferenceModel model = diffOf(parentSide, nextSide, plan.targetState().get(subset), subset,
                    headers.get(file.context()), cimNamespace, timestep);
            if (model.isEmpty()) {
                continue;
            }
            models.add(model);
            forward.put(subset, model.forward().size());
            reverse.put(subset, model.reverse().size());
        }
        Duration diffTime = Duration.ofNanos(System.nanoTime() - t2);

        ignored.forEach(subset -> RdfDbReports.ingestedProfileIgnoredReport(report,
                subset.getIdentifier(), scenario));
        if (models.isEmpty()) {
            throw new RdfDbException("no difference to the parent " + parent + " of scenario '" + scenario
                    + "': the files of " + target + " describe the state the database already holds");
        }
        long t3 = System.nanoTime();
        SnapshotInfo written = putDiff(new DifferenceModelSet(models), target, report);
        Map<CgmesSubset, Boolean> fast = new EnumMap<>(CgmesSubset.class);
        models.forEach(model -> fast.put(model.header().subset(),
                FastRouteCapabilities.check(new DifferenceModelSet(List.of(model)))
                        .route() == CgmesDiffImport.Route.FAST));
        lastIngest = new IngestStatistics(parse, materialize, diffTime,
                Duration.ofNanos(System.nanoTime() - t3), forward, reverse, fast, ignored);
        LOGGER.info("Ingested {} of scenario '{}' from files: {} difference(s), {} profile(s) inherited",
                written, scenario, models.size(), ignored.size());
        return written;
    }

    /**
     * Which parent profile each comparable profile of the timestep needs, and under which cache key.
     *
     * <p>A profile is comparable when the timestep ships it, the parent's materialisation plan starts from a full
     * model of it and the parent names a state of it. A profile that fails any of those is left out here rather
     * than discovered to be uncomparable halfway through the diff, which is what lets the whole materialisation
     * be skipped when every key is already known.</p>
     */
    private Map<CgmesSubset, String> parentIndexKeys(IngestParser.Result parsed, MaterializationPlan plan,
                                                     Map<String, StoredModel> stateModels, String cimNamespace) {
        String fallbackBase = RdfDbMaterializer.subjectBase(plan, stateModels);
        Map<CgmesSubset, String> keys = new EnumMap<>(CgmesSubset.class);
        for (IngestParser.ParsedFile file : parsed.files()) {
            CgmesSubset subset = file.subset();
            if (file.index() == null || subset != EQ && subset != SSH) {
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
     * of {@code materializeParent} for every timestep of a day after the first. When anything is missing the
     * materialisation happens exactly as it always did and only the missing profiles are read out of it, so the
     * statements and their order are the ones the comparison has always seen.</p>
     */
    private Map<CgmesSubset, TripleDiffCalculator.Index> parentIndexesOf(Map<CgmesSubset, String> keys,
                                                                        MaterializationPlan plan,
                                                                        Map<String, StoredModel> stateModels,
                                                                        String cimNamespace,
                                                                        Properties importParams) {
        Map<CgmesSubset, TripleDiffCalculator.Index> indexes = new EnumMap<>(CgmesSubset.class);
        keys.forEach((subset, key) -> {
            TripleDiffCalculator.Index cached = connection.parentIndex(key);
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
        try (RdfDbMaterializer.MaterialisedStore parentState = RdfDbMaterializer.materializeStore(
                connection, scenario, plan, stateModels, importParams)) {
            for (Map.Entry<CgmesSubset, String> entry : keys.entrySet()) {
                CgmesSubset subset = entry.getKey();
                if (indexes.containsKey(subset)) {
                    continue;
                }
                String stateId = plan.targetState().get(subset);
                TripleDiffCalculator.Index index = TripleDiffCalculator.index(
                        statementsOf(parentState.store().getRepository(), parentState.contexts().get(subset)),
                        parentBaseOf(stateModels, stateId, fallbackBase), cimNamespace);
                indexes.put(subset, index);
                connection.rememberParentIndex(entry.getValue(), index);
                LOGGER.debug("Indexed the parent {} state {} of scenario '{}': {} statement(s)",
                        subset.getIdentifier(), stateId, scenario, index.size());
            }
        }
        return indexes;
    }

    /** @return how often {@link #putAsDiff} answered a whole timestep out of the parent index cache */
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
     * @param timestep      the moment the snapshot describes
     * @return the difference
     */
    private DifferenceModel diffOf(TripleDiffCalculator.Index parentSide, TripleDiffCalculator.Index nextSide,
                                   String parentStateId, CgmesSubset subset, Header header, String cimNamespace,
                                   String timestep) {
        DifferenceModelHeader diffHeader = DifferenceModelHeader.builder(header.id, subset, cimNamespace)
                .version(intOf(header.term(RdfDbVocabulary.MODEL_VERSION), 1))
                .description(header.text(RdfDbVocabulary.MODEL_DESCRIPTION))
                .modelingAuthoritySet(header.text(RdfDbVocabulary.MODEL_MODELING_AUTHORITY_SET))
                .profiles(header.texts(RdfDbVocabulary.MODEL_PROFILE))
                .dependentOn(header.texts(RdfDbVocabulary.MODEL_DEPENDENT_ON))
                .supersedes(List.of(parentStateId))
                .scenarioTime(ZonedDateTime.parse(timestep))
                .created(ZonedDateTime.now())
                .build();
        return TripleDiffCalculator.diff(parentSide, nextSide, diffHeader);
    }

    private static List<Statement> statementsOf(org.eclipse.rdf4j.repository.Repository repository,
                                                String context) {
        try (var conn = repository.getConnection()) {
            return new ArrayList<>(conn.getStatements(null, null, null,
                    conn.getValueFactory().createIRI(context)).stream().toList());
        }
    }

    private static int intOf(String text, int fallback) {
        try {
            return text == null ? fallback : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * The boundary of a scenario never changes.
     *
     * <p>A boundary is what gives the objects of a grid model their identity across files; a new one is a new base
     * grid model, and a new base grid model is a new scenario. Ingesting a timestep whose boundary differs would
     * produce a difference against a state that was never the parent.</p>
     */
    private void checkBoundaryUnchanged(Map<String, Header> headers, SnapshotInfo root) {
        for (Map.Entry<String, Header> entry : headers.entrySet()) {
            CgmesSubset subset = GraphInfo.subsetOf(entry.getKey());
            if (subset != CgmesSubset.EQUIPMENT_BOUNDARY && subset != CgmesSubset.TOPOLOGY_BOUNDARY) {
                continue;
            }
            String expected = root.state().get(subset);
            if (expected != null && !expected.equals(entry.getValue().id)) {
                throw new RdfDbConflictException("boundary model changed (" + entry.getValue().id + " vs "
                        + expected + "): a new base (putFull into a new scenario) is required");
            }
        }
    }

    /**
     * A writer that believes it is creating a timestep the scenario already holds.
     *
     * <p>It is told what actually happened rather than being handed the generic "supersedes the wrong model"
     * message: what its differences supersede is the state of the <em>base</em> chain, which is what a timestep
     * root supersedes, so it is not a stale version writer but the loser of a race for the root.</p>
     */
    private void checkNotASecondRoot(List<DifferenceModel> models, SnapshotInfo head, String timestep) {
        if (timestep.equals(baseTimestep())) {
            // The base timestep has no root of its own to race for: its root is the scenario's
            return;
        }
        SnapshotInfo root = root().orElse(null);
        if (root == null || root.iri().equals(head.iri())) {
            return;
        }
        boolean againstTheBase = models.stream().allMatch(model -> {
            List<String> supersedes = model.header().supersedes();
            return supersedes.size() == 1
                    && supersedes.get(0).equals(root.state().get(model.header().subset()));
        });
        if (againstTheBase) {
            throw new RdfDbConflictException("scenario '" + scenario + "' timestep " + timestep + " already has a"
                    + " root (version " + head.version() + "); a new version of it must supersede its head, so"
                    + " update the network to (" + scenario + ", " + timestep + ", " + head.version() + ") and"
                    + " re-record");
        }
    }

    private void checkVersionIsNew(String timestep, String version) {
        if (find(SnapshotRef.of(scenario, version, timestep)).isPresent()) {
            throw new RdfDbConflictException("version " + version + " already exists at (" + scenario + ", "
                    + timestep + ")");
        }
    }

    private void checkSupersedes(List<DifferenceModel> models, SnapshotInfo parent) {
        for (DifferenceModel model : models) {
            CgmesSubset subset = model.header().subset();
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
                throw new RdfDbConflictException("difference model of subset " + subset.getIdentifier()
                        + " supersedes " + found + " but the head (" + scenario + ", " + parent.timestep() + ", "
                        + parent.version() + ") is at " + expected + ": update the network to the head and"
                        + " re-record" + elsewhere);
            }
        }
    }

    /** Re-read the chain and say which rule the silent guard refused on. */
    private String diagnose(String timestep, SnapshotRef target, SnapshotInfo parent, String detail) {
        Optional<SnapshotInfo> nowHead = head(timestep);
        if (nowHead.isPresent() && !nowHead.get().iri().equals(parent.iri())) {
            return "snapshot (" + scenario + ", " + timestep + ", " + parent.version() + ") already has successor ("
                    + scenario + ", " + timestep + ", " + nowHead.get().version() + ") - the linear scheme allows"
                    + " no forks; update to the head first";
        }
        if (find(SnapshotRef.of(scenario, target.version(), timestep)).isPresent()) {
            return "version " + target.version() + " already exists at (" + scenario + ", " + timestep + ")";
        }
        return "the snapshot " + target + " was not written: " + detail;
    }

    /**
     * Turn an unversioned scenario into a versioned one by declaring what it already holds to be its root.
     *
     * <p>A scenario written before this release has instance file graphs and, possibly, a difference chain on
     * them. Its instance files <em>are</em> a consistent state, so they become a root snapshot of version
     * {@code "0"} at the steady state file's scenario time, and every difference that was already stored stays
     * exactly where it is &mdash; the chain of a profile is untouched by this. It is called by the first versioned
     * write of a scenario and is idempotent.</p>
     *
     * @return the root snapshot, or empty when the scenario holds no full model at all
     */
    public Optional<SnapshotInfo> migrateImplicitRoot() {
        Optional<SnapshotInfo> existing = root();
        if (existing.isPresent()) {
            return existing;
        }
        CatalogSnapshot models = connection.catalog(scenario).snapshot();
        List<StoredModel> full = models.models().stream()
                .filter(model -> model.kind() == StoredModel.Kind.FULL).toList();
        if (full.isEmpty()) {
            return Optional.empty();
        }
        // The raw literal rather than StoredModel.scenarioTime: a CGMES header may write a local date-time, which
        // is not a ZonedDateTime and which the catalogue therefore reads as absent
        String scenarioTime = rawScenarioTime()
                .orElseThrow(() -> new RdfDbException("scenario '" + scenario + "' holds no"
                        + " md:Model.scenarioTime: it cannot be migrated to a versioned scenario, pass a"
                        + " timestep explicitly"));
        String timestep = Timesteps.canonical(scenarioTime);
        String offset = offsetOfText(scenarioTime);
        String snapshotIri = RdfDbNames.snapshot(scenario, timestep, "0");
        Map<CgmesSubset, String> state = new EnumMap<>(CgmesSubset.class);
        full.forEach(model -> state.put(model.subset(), model.id()));

        StringBuilder update = new StringBuilder(RdfDbVocabulary.PREFIXES).append("INSERT { GRAPH ")
                .append(SparqlText.iri(metaGraph)).append(" { ");
        appendCatalogNode(update, timestep, offset);
        appendSnapshotNode(update, snapshotIri, "0", timestep, Timesteps.label(timestep, offset),
                RdfDbVocabulary.FULL, null, 0, state, state, state, snapshotIri);
        full.forEach(model -> update.append(SparqlText.iri(model.id())).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.SNAPSHOT)).append(' ')
                .append(SparqlText.iri(snapshotIri)).append(" . "));
        update.append("} } WHERE { FILTER NOT EXISTS { GRAPH ").append(SparqlText.iri(metaGraph))
                .append(" { ?x a pdb:Snapshot } } }");
        sparql().update(update.toString());
        cachedCatalogNode = null;
        LOGGER.info("Migrated scenario '{}' to a versioned scenario: its instance files are version \"0\" at {}",
                scenario, timestep);
        return info(snapshotIri);
    }

    /** The {@code md:Model.scenarioTime} of the steady state full model of an unversioned scenario, as written. */
    private Optional<String> rawScenarioTime() {
        List<Map<String, Value>> rows = select("SELECT ?sub ?ts WHERE {" + graphClause()
                + "{ ?m pdb:kind pdb:Full ; pdb:subset ?sub ; md:Model.scenarioTime ?ts } }");
        return rows.stream()
                .sorted(Comparator.comparingInt(row -> SSH.getIdentifier().equals(row.get("sub").stringValue())
                        ? 0 : 1))
                .map(row -> row.get("ts").stringValue())
                .findFirst();
    }

    private static String offsetOfText(String text) {
        try {
            return OffsetDateTime.parse(text.trim()).getOffset().getId();
        } catch (java.time.format.DateTimeParseException e) {
            return ZoneOffset.UTC.getId();
        }
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
        cachedCatalogNode = null;
        connection.forgetParentIndexes(scenario);
    }

    /**
     * Check the invariants of the snapshot tree of this scenario.
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
        List<SnapshotInfo> roots = all.stream().filter(SnapshotInfo::isRoot).toList();
        if (roots.size() != 1) {
            throw new RdfDbException("scenario '" + scenario + "' has " + roots.size() + " root snapshots "
                    + roots.stream().map(SnapshotInfo::iri).toList() + ": a scenario is one base grid model");
        }
        Set<String> versionChildren = new LinkedHashSet<>();
        Set<String> timestepRoots = new LinkedHashSet<>();
        all.stream().filter(info -> info.iri().equals(info.timestepRoot()))
                .forEach(info -> {
                    if (!timestepRoots.add(info.timestep())) {
                        throw new RdfDbException("scenario '" + scenario + "' has more than one root at timestep "
                                + info.timestep());
                    }
                });
        String prefix = RdfDbNames.scenarioPrefix(scenario);
        for (SnapshotInfo info : all) {
            if (!info.iri().startsWith(prefix)) {
                throw new RdfDbException("snapshot " + info.iri() + " is not under " + prefix);
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
            if (info.depth() != parent.depth() + 1) {
                throw new RdfDbException("snapshot " + info + " has depth " + info.depth() + " but its parent "
                        + parent + " has depth " + parent.depth());
            }
            if (info.edge() == SnapshotInfo.EdgeKind.VERSION && !versionChildren.add(parent.iri())) {
                throw new RdfDbException("snapshot " + parent + " of scenario '" + scenario + "' has more than one"
                        + " version successor: the chain forked");
            }
            verifyTimestepRoot(info, parent, byIri, roots.get(0));
            verifyState(info, parent);
        }
    }

    /**
     * A timestep root derives from the base chain; every other snapshot belongs to its parent's timestep.
     */
    private void verifyTimestepRoot(SnapshotInfo info, SnapshotInfo parent, Map<String, SnapshotInfo> byIri,
                                    SnapshotInfo root) {
        if (info.edge() == SnapshotInfo.EdgeKind.TIMESTEP) {
            if (!info.iri().equals(info.timestepRoot())) {
                throw new RdfDbException("the timestep root " + info + " of scenario '" + scenario + "' names "
                        + info.timestepRoot() + " as its own root");
            }
            if (!parent.timestep().equals(root.timestep())) {
                throw new RdfDbException("the timestep root " + info + " of scenario '" + scenario + "' hangs off "
                        + parent + ", which is not on the base chain (" + root.timestep() + ")");
            }
            return;
        }
        if (!info.timestepRoot().equals(parent.timestepRoot())) {
            throw new RdfDbException("snapshot " + info + " of scenario '" + scenario + "' names the timestep root "
                    + info.timestepRoot() + " but its parent " + parent + " names " + parent.timestepRoot());
        }
        if (byIri.get(info.timestepRoot()) == null) {
            throw new RdfDbException("snapshot " + info + " of scenario '" + scenario + "' names a timestep root"
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
        Map<CgmesSubset, String> expected = new EnumMap<>(parent.state());
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

    private String rootWrite(Map<String, Header> headers, Map<String, String> graphs,
                             CgmesTripleStoreLoader.Result parsed, String snapshotIri, String timestep,
                             String label, String offset, Map<String, Long> counts) {
        String subjectBase = ModelCatalog.subjectBaseOf(parsed.baseName());
        ZonedDateTime now = ZonedDateTime.now();
        Map<CgmesSubset, String> state = new EnumMap<>(CgmesSubset.class);
        headers.forEach((context, header) -> state.put(GraphInfo.subsetOf(context), header.id));

        StringBuilder update = new StringBuilder(RdfDbVocabulary.PREFIXES).append("INSERT { GRAPH ")
                .append(SparqlText.iri(metaGraph)).append(" { ");
        headers.forEach((context, header) -> appendFullModelNode(update, header, GraphInfo.subsetOf(context),
                graphs.get(context), counts.getOrDefault(context, -1L), subjectBase, parsed.cimNamespace(),
                snapshotIri, now));
        appendCatalogNode(update, timestep, offset);
        appendSnapshotNode(update, snapshotIri, versionOf(snapshotIri), timestep, label, RdfDbVocabulary.FULL,
                null, 0, state, state, state, snapshotIri);
        update.append("} } WHERE { FILTER NOT EXISTS { GRAPH ").append(SparqlText.iri(metaGraph))
                .append(" { ?x a pdb:Snapshot } } FILTER NOT EXISTS { GRAPH ").append(SparqlText.iri(metaGraph))
                .append(" { ").append(SparqlText.iri(snapshotIri)).append(" ?p ?o } }");
        headers.values().forEach(header -> update.append(" FILTER NOT EXISTS { GRAPH ")
                .append(SparqlText.iri(metaGraph)).append(" { ").append(SparqlText.iri(header.id))
                .append(" ?p1 ?o1 } }"));
        return update.append(" }").toString();
    }

    private static String versionOf(String snapshotIri) {
        String tail = snapshotIri.substring(snapshotIri.lastIndexOf('/') + 1);
        return RdfDbNames.unsafe(tail);
    }

    private void appendCatalogNode(StringBuilder update, String timestep, String offset) {
        update.append(SparqlText.iri(catalogNode)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.RDF_TYPE)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.CATALOG_CLASS)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SCENARIO)).append(' ')
                .append(SparqlText.str(scenario)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.BASE_TIMESTEP)).append(' ')
                .append(SparqlText.str(timestep)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.BASE_OFFSET)).append(' ')
                .append(SparqlText.str(offset)).append(" . ");
    }

    // CHECKSTYLE:OFF ParameterNumber - one snapshot node has that many properties; the alternative is a builder
    // that exists only to be unpacked again two lines later
    private void appendSnapshotNode(StringBuilder update, String iri, String version, String timestep, String label,
                                    String kind, String parent, int depth,
                                    Map<CgmesSubset, String> members, Map<CgmesSubset, String> state,
                                    Map<CgmesSubset, String> full, String timestepRoot) {
        // CHECKSTYLE:ON ParameterNumber
        update.append(SparqlText.iri(iri)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.RDF_TYPE)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.SNAPSHOT_CLASS)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SCENARIO)).append(' ')
                .append(SparqlText.str(scenario)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.VERSION)).append(' ')
                .append(SparqlText.str(version)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.TIMESTEP)).append(' ')
                .append(SparqlText.str(timestep)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.TIMESTEP_LABEL)).append(' ')
                .append(SparqlText.str(label)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.KIND)).append(' ').append(SparqlText.iri(kind)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.DEPTH)).append(' ')
                .append(SparqlText.integer(depth)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.TIMESTEP_ROOT)).append(' ')
                .append(SparqlText.iri(timestepRoot)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.CREATED)).append(' ')
                .append(SparqlText.dateTime(ZonedDateTime.now()));
        if (parent != null) {
            update.append(" ; ").append(SparqlText.iri(RdfDbVocabulary.PARENT)).append(' ')
                    .append(SparqlText.iri(parent));
        }
        members.values().forEach(id -> update.append(" ; ").append(SparqlText.iri(RdfDbVocabulary.MEMBER))
                .append(' ').append(SparqlText.iri(id)));
        state.values().forEach(id -> update.append(" ; ").append(SparqlText.iri(RdfDbVocabulary.STATE))
                .append(' ').append(SparqlText.iri(id)));
        full.values().forEach(id -> update.append(" ; ").append(SparqlText.iri(RdfDbVocabulary.FULL_MODELS))
                .append(' ').append(SparqlText.iri(id)));
        update.append(" . ");
    }

    // CHECKSTYLE:OFF ParameterNumber - see above
    private void appendFullModelNode(StringBuilder update, Header header, CgmesSubset subset, String graphIri,
                                     long tripleCount, String subjectBase, String cimNamespace, String snapshotIri,
                                     ZonedDateTime now) {
        // CHECKSTYLE:ON ParameterNumber
        update.append(SparqlText.iri(header.id)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.RDF_TYPE)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.FULL_MODEL)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.KIND)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.FULL)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SUBSET)).append(' ')
                .append(SparqlText.str(subset.getIdentifier())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SCENARIO)).append(' ')
                .append(SparqlText.str(scenario)).append(" ; ")
                // A string, like every other pdb:graph of this layer: the in-process backend names graphs by the
                // plain file name, which is not always writable as an IRI
                .append(SparqlText.iri(RdfDbVocabulary.GRAPH)).append(' ')
                .append(SparqlText.str(graphIri)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SNAPSHOT)).append(' ')
                .append(SparqlText.iri(snapshotIri)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.CHAIN_DEPTH)).append(' ')
                .append(SparqlText.integer(0)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.TRIPLE_COUNT)).append(' ')
                .append(SparqlText.integer(tripleCount)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SUBJECT_BASE)).append(' ')
                .append(SparqlText.str(subjectBase)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.CIM_NAMESPACE)).append(' ')
                .append(SparqlText.str(cimNamespace)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.CREATED)).append(' ')
                .append(SparqlText.dateTime(now));
        header.terms.forEach((predicate, values) -> values.forEach(value -> update.append(" ; ")
                .append(SparqlText.iri(predicate)).append(' ')
                .append(value instanceof org.eclipse.rdf4j.model.IRI iri ? SparqlText.iri(iri.stringValue())
                        : SparqlText.str(value.stringValue()))));
        update.append(" . ");
    }

    // ------------------------------------------------------------------ scratch store helpers

    /** The {@code md:FullModel} header of one parsed instance file. */
    private record Header(String id, Map<String, List<Value>> terms) {

        String term(String predicate) {
            List<Value> values = terms.get(predicate);
            return values == null || values.isEmpty() ? null : values.get(0).stringValue();
        }

        String text(String predicate) {
            return term(predicate);
        }

        List<String> texts(String predicate) {
            return terms.getOrDefault(predicate, List.of()).stream().map(Value::stringValue).toList();
        }

        String scenarioTimeText() {
            List<Value> values = terms.get(RdfDbVocabulary.MODEL_SCENARIO_TIME);
            return values == null || values.isEmpty() ? null : values.get(0).stringValue();
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
            headers.put(context, new Header(id, terms));
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

    private String timestepOf(SnapshotRef ref, Map<String, Header> headers) {
        if (ref.timestep() != null) {
            return ref.timestep();
        }
        String text = scenarioTimeText(headers);
        if (text == null) {
            throw new RdfDbException("no scenarioTime: pass a timestep");
        }
        return Timesteps.canonical(text);
    }

    private String offsetOf(SnapshotRef ref, Map<String, Header> headers) {
        String text = scenarioTimeText(headers);
        return text == null ? ZoneOffset.UTC.getId() : offsetOfText(text);
    }

    private static String scenarioTimeText(Map<String, Header> headers) {
        return headers.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> GraphInfo.subsetOf(e.getKey()) == SSH ? 0 : 1))
                .map(e -> e.getValue().scenarioTimeText())
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }
}
