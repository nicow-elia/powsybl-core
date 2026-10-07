/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import org.eclipse.rdf4j.model.Value;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The version names of one scenario, and their order.
 *
 * <h2>Versions are names</h2>
 * <p>A snapshot stores the <em>name</em> of its version ({@code "DA"}, {@code "ID"}, {@code "RT"}, or {@code "1"},
 * {@code "2"}, …); the registry gives every name a <strong>rank</strong>, and the ranks are the only order versions
 * have. A snapshot never stores a rank: every comparison joins the name to its {@code pdb:Version} node, so a
 * {@link #rerank} rewrites a few nodes and not the history. Ranks are sparse &mdash; an appended name gets the
 * highest rank plus 10 &mdash; so that a name can be {@linkplain #insert inserted} between two others later.</p>
 *
 * <h2>Strict and permissive</h2>
 * <p>A <strong>strict</strong> scenario writes only registered names: the registry is the contract of a process
 * ({@code DA → ID → RT}) and a typo is refused rather than appended. A <strong>permissive</strong> one registers an
 * unknown name on its first write, above every other. {@link #create} makes a strict registry unless asked
 * otherwise; a scenario whose first root is written without one bootstraps a permissive registry holding the root's
 * name at rank 10, which is what keeps a caller that never heard of the registry working with {@code "1"},
 * {@code "2"}, … in the order it writes them.</p>
 *
 * <h2>The rules a write follows</h2>
 * <ol>
 *   <li>A named version that is registered must rank above the version of the head it is written on.</li>
 *   <li>A named version that is not registered is appended in a permissive scenario and refused in a strict
 *       one.</li>
 *   <li>No name means the lowest registered name ranking above the head's &mdash; for a new timestamp, the lowest
 *       registered name. When there is none, a strict scenario refuses and a permissive one appends a generated
 *       name: the smallest number above the registry's size that is not yet a name ({@code "2"} after
 *       {@code "1"}).</li>
 * </ol>
 * <p>The ranks of two versions of one timestamp are compared; a timestamp root's version is not compared with the
 * snapshot it hangs off, which belongs to another timestamp.</p>
 *
 * <h2>The revision</h2>
 * <p>The schema node of the scenario carries {@code pdb:rev}, 1 when the registry is created and one more on every
 * edit. It is the cache token. This object holds the registry as last read &mdash; read in the same request as the
 * schema check, so no listing costs more than before &mdash; and every edit and every snapshot write is guarded on
 * the revision it was checked against: an edit through another connection makes the next write through this one
 * refuse, and the write re-reads the registry and retries. Reads never use the cache: they join the rank in SPARQL,
 * so a stale cache can only make a write lose a race, never a read return a wrong snapshot.</p>
 *
 * <p>Every edit is one guarded request and one read-back. An edit that lost a race is refused with an
 * {@link RdfDbConflictException} and changes nothing.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class VersionRegistry {

    /** The rank step between two appended names, which leaves room for nine names inserted in between. */
    static final int STEP = 10;

    /** What a binding of the schema read names a registry row. */
    static final String ROW_REV = "rev";
    static final String ROW_PERMISSIVE = "permissive";
    static final String ROW_VERSION = "version";

    private final RdfDbConnection connection;
    private final SnapshotCatalog catalog;
    private final String scenario;
    private final String meta;
    private final String schemaNode;
    /** The registry as last read, or {@code null} when it has to be read again. */
    private volatile State state;
    /** Runs between the check of an edit and its guarded write, so that a test can make a concurrent edit win. */
    private Runnable beforeRegistryWrite;

    /** One registered name. */
    private record Entry(String name, int rank, boolean isTransient) {
    }

    /**
     * The registry at one revision.
     *
     * @param rev        the revision, 0 when the scenario has no registry yet
     * @param permissive whether unknown names are appended
     * @param entries    the names in rank order
     */
    private record State(long rev, boolean permissive, List<Entry> entries) {

        Optional<Entry> entry(String name) {
            return entries.stream().filter(e -> e.name().equals(name)).findFirst();
        }

        int maxRank() {
            return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).rank();
        }

        boolean exists() {
            return rev > 0;
        }
    }

    /**
     * What a snapshot write takes from the registry: the name, its rank and the revision both were checked against.
     *
     * @param name      the version name the snapshot gets
     * @param rank      its rank
     * @param rev       the revision the write is guarded on; 0 when the write bootstraps the registry
     */
    record Resolved(String name, int rank, long rev) {

        /** Whether the write creates the registry: the first root of a scenario that has none. */
        boolean bootstraps() {
            return rev == 0;
        }
    }

    VersionRegistry(RdfDbConnection connection, SnapshotCatalog catalog) {
        this.connection = connection;
        this.catalog = catalog;
        this.scenario = catalog.scenario();
        this.meta = SparqlText.iri(catalog.metaGraph());
        this.schemaNode = SparqlText.iri(RdfDbNames.schemaNode(scenario));
    }

    // ------------------------------------------------------------------ reading

    /**
     * @return the scenario this registry belongs to
     */
    public String scenario() {
        return scenario;
    }

    /**
     * @return the registered names, lowest rank first
     */
    public List<String> names() {
        return state().entries().stream().map(Entry::name).toList();
    }

    /**
     * @return the registered names with their ranks, lowest rank first
     */
    public Map<String, Integer> ranks() {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        state().entries().forEach(e -> ranks.put(e.name(), e.rank()));
        return ranks;
    }

    /**
     * @param name a version name
     * @return its rank, or empty when it is not registered
     */
    public OptionalInt rank(String name) {
        return state().entry(name).map(e -> OptionalInt.of(e.rank())).orElse(OptionalInt.empty());
    }

    /**
     * @param name a version name
     * @return whether it is registered as transient
     */
    public boolean isTransient(String name) {
        return state().entry(name).map(Entry::isTransient).orElse(false);
    }

    /**
     * @return whether the scenario appends an unknown name on its first write; a scenario without a registry is
     *         permissive, because its first root bootstraps one that is
     */
    public boolean isPermissive() {
        State s = state();
        return !s.exists() || s.permissive();
    }

    /**
     * @return the revision of the registry as last read, 0 when the scenario has none yet
     */
    public long rev() {
        return state().rev();
    }

    /**
     * Read the registry again, in one request: after an edit through another connection, for instance.
     */
    public void refresh() {
        catalog.readSchema();
    }

    /** The registry as last read, reading it when needed (the one request that also checks the schema). */
    private State state() {
        State s = state;
        if (s == null) {
            catalog.readSchema();
            s = state;
        }
        return s;
    }

    /** Forget what was read; the next question reads again. */
    void invalidate() {
        state = null;
    }

    /**
     * The rows of the schema read that describe the registry, as {@code SnapshotCatalog.readSchema} binds them.
     *
     * @param rows the rows; {@code k} names the row kind, {@code v} the value, {@code n}, {@code r}, {@code t} the
     *             name, rank and transient flag of a version node
     */
    void load(List<Map<String, Value>> rows) {
        long rev = 0;
        boolean permissive = false;
        List<Entry> entries = new ArrayList<>();
        for (Map<String, Value> row : rows) {
            String kind = SnapshotRows.text(row, "k");
            if (ROW_REV.equals(kind)) {
                rev = SnapshotRows.longOf(row.get("v"), 0);
            } else if (ROW_PERMISSIVE.equals(kind)) {
                permissive = SnapshotRows.booleanOf(row.get("v"));
            } else if (ROW_VERSION.equals(kind)) {
                Value t = row.get("t");
                entries.add(new Entry(SnapshotRows.text(row, "n"), SnapshotRows.intOf(row.get("r")),
                        t != null && SnapshotRows.booleanOf(t)));
            }
        }
        entries.sort(Comparator.comparingInt(Entry::rank).thenComparing(Entry::name));
        state = new State(rev, permissive, List.copyOf(entries));
    }

    /** The UNION branches the schema read adds to read the registry in the same request. */
    String readBranches() {
        return " UNION { " + schemaNode + " pdb:rev ?v BIND(\"" + ROW_REV + "\" AS ?k) }"
                + " UNION { " + schemaNode + " pdb:permissive ?v BIND(\"" + ROW_PERMISSIVE + "\" AS ?k) }"
                + " UNION { ?vn a pdb:Version ; pdb:name ?n ; pdb:rank ?r OPTIONAL { ?vn pdb:transient ?t }"
                + " BIND(\"" + ROW_VERSION + "\" AS ?k) }";
    }

    @Override
    public String toString() {
        State s = state();
        return s.entries().stream().map(e -> e.name() + " " + e.rank() + (e.isTransient() ? " (transient)" : ""))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    // ------------------------------------------------------------------ editing

    /**
     * Create the registry of a scenario that has none.
     *
     * @param names      the names, lowest rank first; they get the ranks 10, 20, …
     * @param permissive whether a write may append a name that is not registered
     * @throws RdfDbConflictException if the scenario already has a registry, which every scenario that holds a
     *                                snapshot has
     */
    public void create(List<String> names, boolean permissive) {
        Objects.requireNonNull(names);
        Set<String> distinct = new HashSet<>();
        names.forEach(name -> {
            checkName(name);
            if (!distinct.add(name)) {
                throw new RdfDbException("the version name '" + name + "' is listed twice for the registry of"
                        + " scenario '" + scenario + "'");
            }
        });
        State before = state();
        if (before.exists()) {
            throw new RdfDbConflictException("scenario '" + scenario + "' already has a version registry " + this
                    + " (rev " + before.rev() + "): edit it with add, insert, rerank, rename or delete");
        }
        StringBuilder insert = new StringBuilder(RdfDbVocabulary.PREFIXES).append("INSERT { GRAPH ").append(meta)
                .append(" { ");
        appendBootstrap(insert, permissive);
        ZonedDateTime now = ZonedDateTime.now();
        for (int i = 0; i < names.size(); i++) {
            appendNode(insert, names.get(i), STEP * (i + 1), false, now);
        }
        insert.append(" } } WHERE { FILTER NOT EXISTS { GRAPH ").append(meta).append(" { ").append(schemaNode)
                .append(" pdb:rev ?anyRev } } }");
        write(insert.toString(), before, "create", after -> after.rev() == 1 && after.entries().size() == names.size()
                && after.permissive() == permissive);
    }

    /**
     * Register a name above every other.
     *
     * @param name the version name
     * @return its rank: the highest rank plus 10
     * @throws RdfDbException if the name is registered already
     */
    public int add(String name) {
        State before = existing();
        refuseRegistered(before, name);
        int rank = before.maxRank() + STEP;
        write(append(before, name, rank, ZonedDateTime.now()), before, "add '" + name + "'",
                after -> after.entry(name).map(e -> e.rank() == rank).orElse(false));
        return rank;
    }

    /**
     * Register a name right after another one, at the midpoint between that one's rank and its successor's.
     *
     * @param name  the version name
     * @param after the registered name it follows, or {@code null} to insert it before the first one
     * @return its rank
     * @throws RdfDbException if the name is registered already, if {@code after} is not, or if no integer lies
     *                        between the two ranks (rerank first)
     */
    public int insert(String name, String after) {
        State before = existing();
        refuseRegistered(before, name);
        int low = after == null ? 0 : registered(before, after).rank();
        Optional<Entry> next = before.entries().stream().filter(e -> e.rank() > low).findFirst();
        int rank;
        if (next.isEmpty()) {
            rank = low + STEP;
        } else if (next.get().rank() - low < 2) {
            throw new RdfDbException("no rank between " + (after == null ? "the start" : "'" + after + "' (" + low
                    + ")") + " and '" + next.get().name() + "' (" + next.get().rank() + ") of the version registry"
                    + " of scenario '" + scenario + "': rerank first");
        } else {
            rank = low + (next.get().rank() - low) / 2;
        }
        write(append(before, name, rank, ZonedDateTime.now()), before, "insert '" + name + "'",
                state -> state.entry(name).map(e -> e.rank() == rank).orElse(false));
        return rank;
    }

    /**
     * Give registered names new ranks.
     *
     * <p>Accepted only when it keeps the order of every version chain: for every snapshot written as the next
     * version of another, the new rank of its version stays above the new rank of its parent's. The snapshots a
     * timestamp root hangs off belong to another timestamp and are not compared.</p>
     *
     * @param newRanks the new rank per name; names not listed keep theirs
     * @throws RdfDbException if a name is not registered, a rank is below 1, two names would share a rank, or the
     *                        new ranks would put a version below its parent, naming the first such pair
     */
    public void rerank(Map<String, Integer> newRanks) {
        Objects.requireNonNull(newRanks);
        State before = existing();
        Map<String, Integer> ranks = new LinkedHashMap<>();
        before.entries().forEach(e -> ranks.put(e.name(), e.rank()));
        newRanks.forEach((name, rank) -> {
            registered(before, name);
            if (rank == null || rank < 1) {
                throw new RdfDbException("the rank of '" + name + "' must be at least 1, got " + rank);
            }
            ranks.put(name, rank);
        });
        Map<Integer, String> byRank = new LinkedHashMap<>();
        ranks.forEach((name, rank) -> {
            String other = byRank.putIfAbsent(rank, name);
            if (other != null) {
                throw new RdfDbException("'" + other + "' and '" + name + "' would share the rank " + rank
                        + " in the version registry of scenario '" + scenario + "'");
            }
        });
        // One listing of the version edges, the check in Java so that the refusal can name the pair; the same check
        // is in the guarded write, against a snapshot written in between
        for (Map<String, Value> row : catalog.select("SELECT DISTINCT ?c ?cn ?pn WHERE { GRAPH " + meta + " { ?c"
                + " pdb:parent ?p ; pdb:edge pdb:VersionEdge ; pdb:version ?cn . ?p pdb:version ?pn } }"
                + " ORDER BY ?c")) {
            String child = SnapshotRows.text(row, "cn");
            String parent = SnapshotRows.text(row, "pn");
            if (ranks.getOrDefault(child, 0) <= ranks.getOrDefault(parent, 0)) {
                throw new RdfDbException("the ranks " + newRanks + " would put version '" + child + "' ("
                        + ranks.get(child) + ") of snapshot " + SnapshotRows.text(row, "c") + " at or below the"
                        + " version '" + parent + "' (" + ranks.get(parent) + ") of its parent, and a version ranks"
                        + " above the version it was written on; nothing was changed");
            }
        }
        String values = ranks.entrySet().stream().map(e -> "(" + SparqlText.str(e.getKey()) + " "
                + SparqlText.integer(e.getValue()) + ")").collect(Collectors.joining(" "));
        StringBuilder update = new StringBuilder(RdfDbVocabulary.PREFIXES).append("DELETE { GRAPH ").append(meta)
                .append(" { ").append(schemaNode).append(" pdb:rev ").append(SparqlText.integer(before.rev()));
        newRanks.keySet().forEach(name -> update.append(" . ").append(nodeOf(name)).append(" pdb:rank ")
                .append(SparqlText.integer(before.entry(name).orElseThrow().rank())));
        update.append(" } } INSERT { GRAPH ").append(meta).append(" { ").append(schemaNode).append(" pdb:rev ")
                .append(SparqlText.integer(before.rev() + 1));
        newRanks.forEach((name, rank) -> update.append(" . ").append(nodeOf(name)).append(" pdb:rank ")
                .append(SparqlText.integer(rank)));
        update.append(" } } WHERE { GRAPH ").append(meta).append(" { ").append(revGuard(before))
                .append(" FILTER NOT EXISTS { ?c pdb:parent ?p ; pdb:edge pdb:VersionEdge ; pdb:version ?cn ."
                        + " ?p pdb:version ?pn . VALUES (?cn ?cr) { ").append(values).append(" } VALUES (?pn ?pr) { ")
                .append(values).append(" } FILTER(?cr <= ?pr) } } }");
        write(update.toString(), before, "rerank", after -> newRanks.entrySet().stream()
                .allMatch(e -> after.entry(e.getKey()).map(x -> x.rank() == e.getValue()).orElse(false)));
    }

    /**
     * Give a registered name that no snapshot carries another name.
     *
     * <p>A name a snapshot carries cannot be renamed: the snapshot's IRI carries it, and so does every address read
     * back off such an IRI. Use {@link #rerank} to change the order instead.</p>
     *
     * @param oldName the registered name
     * @param newName the new name, not registered
     * @throws RdfDbException if the old name is not registered, the new one is, or a snapshot carries the old one
     */
    public void rename(String oldName, String newName) {
        State before = existing();
        Entry entry = registered(before, oldName);
        refuseRegistered(before, newName);
        StringBuilder update = new StringBuilder(RdfDbVocabulary.PREFIXES).append("DELETE { GRAPH ").append(meta)
                .append(" { ").append(schemaNode).append(" pdb:rev ").append(SparqlText.integer(before.rev()))
                .append(" . ").append(nodeOf(oldName)).append(" ?vp ?vo } } INSERT { GRAPH ").append(meta)
                .append(" { ").append(schemaNode).append(" pdb:rev ").append(SparqlText.integer(before.rev() + 1))
                .append(" . ");
        appendNode(update, newName, entry.rank(), entry.isTransient(), ZonedDateTime.now());
        update.append(" } } WHERE { GRAPH ").append(meta).append(" { ").append(revGuard(before)).append(' ')
                .append(nodeOf(oldName)).append(" ?vp ?vo ").append(unused(oldName)).append(" } }");
        writeUnlessCarried(update.toString(), before, oldName, "renamed",
                after -> after.entry(newName).isPresent() && after.entry(oldName).isEmpty());
    }

    /**
     * Mark a registered name transient, or not.
     *
     * @param name        the registered name
     * @param isTransient whether deleting it drops the snapshots that carry it
     */
    public void markTransient(String name, boolean isTransient) {
        State before = existing();
        registered(before, name);
        String update = RdfDbVocabulary.PREFIXES + "DELETE { GRAPH " + meta + " { " + schemaNode + " pdb:rev "
                + SparqlText.integer(before.rev()) + " . " + nodeOf(name) + " pdb:transient ?t } } INSERT { GRAPH "
                + meta + " { " + schemaNode + " pdb:rev " + SparqlText.integer(before.rev() + 1)
                + (isTransient ? " . " + nodeOf(name) + " pdb:transient " + SparqlText.bool(true) : "")
                + " } } WHERE { GRAPH " + meta + " { " + revGuard(before) + " OPTIONAL { " + nodeOf(name)
                + " pdb:transient ?t } } }";
        write(update, before, "mark '" + name + "' transient", after -> after.entry(name)
                .map(e -> e.isTransient() == isTransient).orElse(false));
    }

    /**
     * Delete a registered name.
     *
     * <p>A name no snapshot carries is simply dropped. A <em>transient</em> name is dropped together with the
     * snapshots that carry it, which must all be leaves: a transient version is scratch work nobody built on. Any
     * other name a snapshot carries is refused, naming the snapshot.</p>
     *
     * <p>A transient delete is two requests in this order: the snapshots, then the name. A failure in between
     * leaves a registered name no snapshot carries, never a snapshot carrying an unregistered name.</p>
     *
     * @param name the registered name
     * @throws RdfDbException if a snapshot that is not a transient leaf carries the name
     */
    public void delete(String name) {
        State before = existing();
        if (registered(before, name).isTransient()) {
            List<SnapshotInfo> carriers = catalog.snapshots().stream().filter(s -> s.version().equals(name))
                    .toList();
            catalog.dropSnapshots(carriers);
        }
        String update = RdfDbVocabulary.PREFIXES + "DELETE { GRAPH " + meta + " { " + schemaNode + " pdb:rev "
                + SparqlText.integer(before.rev()) + " . " + nodeOf(name) + " ?vp ?vo } } INSERT { GRAPH " + meta
                + " { " + schemaNode + " pdb:rev " + SparqlText.integer(before.rev() + 1) + " } } WHERE { GRAPH "
                + meta + " { " + revGuard(before) + " " + nodeOf(name) + " ?vp ?vo " + unused(name) + " } }";
        writeUnlessCarried(update, before, name, "deleted", after -> after.entry(name).isEmpty());
    }

    /**
     * A hook that runs between the check of an edit and its guarded write, so that a test can make a concurrent
     * edit win. A test seam only: production code never sets it.
     *
     * @param hook what to run, or {@code null} for nothing
     */
    void beforeRegistryWrite(Runnable hook) {
        this.beforeRegistryWrite = hook;
    }

    // ------------------------------------------------------------------ what a snapshot write takes

    /**
     * The version name a snapshot write takes, appending it to a permissive registry when needed.
     *
     * <p>An append is one guarded request with no read-back: the snapshot write that follows is guarded on the name
     * at that rank and on the revision the append produced, so a lost append makes it refuse, and the writer
     * re-reads the registry and retries ({@link #changedSince}).</p>
     *
     * @param requested     the name the caller asked for, or {@code null}
     * @param parentVersion the version of the head the snapshot is written on, or {@code null} for a new timestamp
     * @param address       the address being written, for the messages
     * @return the name, its rank and the revision to guard on
     * @throws RdfDbConflictException if a registered name does not rank above the parent's
     * @throws RdfDbException         if the name is not registered (or none ranks above the parent's) in a strict
     *                                scenario
     */
    Resolved resolve(String requested, String parentVersion, Object address) {
        State s = state();
        if (!s.exists()) {
            // The first root of a scenario: the write creates the registry with its own name at the first rank
            return new Resolved(requested != null ? requested : "1", STEP, 0);
        }
        String name = nameFor(s, requested, parentVersion, address);
        Optional<Entry> entry = s.entry(name);
        if (entry.isPresent()) {
            return new Resolved(name, entry.get().rank(), s.rev());
        }
        int rank = s.maxRank() + STEP;
        if (beforeRegistryWrite != null) {
            beforeRegistryWrite.run();
        }
        connection.sparql(scenario).update(append(s, name, rank, ZonedDateTime.now()));
        List<Entry> entries = new ArrayList<>(s.entries());
        entries.add(new Entry(name, rank, false));
        state = new State(s.rev() + 1, s.permissive(), List.copyOf(entries));
        return new Resolved(name, rank, s.rev() + 1);
    }

    /**
     * The name a write takes when the caller names none, without registering it: the lowest registered name ranking
     * above the parent's, or the generated name a permissive registry would append.
     *
     * @param parentVersion the version of the head, or {@code null} for a new timestamp
     * @param address       the address, for the messages
     * @return the name
     */
    String nextName(String parentVersion, Object address) {
        State s = state();
        return s.exists() ? nameFor(s, null, parentVersion, address) : "1";
    }

    private String nameFor(State s, String requested, String parentVersion, Object address) {
        int parentRank = parentVersion == null ? 0 : s.entry(parentVersion).map(Entry::rank).orElseThrow(() ->
                new RdfDbException("the head of " + address + " carries the version '" + parentVersion + "', which"
                        + " the version registry of scenario '" + scenario + "' does not hold " + this));
        if (requested != null) {
            Optional<Entry> entry = s.entry(requested);
            if (entry.isPresent()) {
                if (entry.get().rank() <= parentRank) {
                    throw new RdfDbConflictException("version '" + requested + "' (rank " + entry.get().rank()
                            + ") is not above the parent '" + parentVersion + "' (rank " + parentRank + ") of "
                            + address + ": a new version ranks above the head it is written on");
                }
                return requested;
            }
            refuseStrict(s, "version '" + requested + "' is not registered");
            return requested;
        }
        Optional<Entry> above = s.entries().stream().filter(e -> e.rank() > parentRank).findFirst();
        if (above.isPresent()) {
            return above.get().name();
        }
        refuseStrict(s, "no version of the registry ranks above '" + parentVersion + "' (rank " + parentRank + ")");
        int n = s.entries().size() + 1;
        while (s.entry(String.valueOf(n)).isPresent()) {
            n++;
        }
        return String.valueOf(n);
    }

    private void refuseStrict(State s, String what) {
        if (!s.permissive()) {
            throw new RdfDbException(what + " in scenario '" + scenario + "' (registry: " + this + "); register it"
                    + " or write into a permissive scenario");
        }
    }

    /**
     * Whether the registry changed since a write took a name from it: re-read, and the revision or the name's rank
     * differ. The question a writer asks after its guarded write was refused.
     *
     * @param taken what the write took
     * @return whether to retry with a fresh resolution
     */
    boolean changedSince(Resolved taken) {
        refresh();
        State s = state();
        return s.rev() != taken.rev() || s.entry(taken.name()).map(e -> e.rank() != taken.rank()).orElse(true);
    }

    /**
     * The guards a snapshot write adds, inside the metadata graph: the name is registered at the rank that was
     * checked, and the registry is at the revision it was checked at. A bootstrapping write instead requires that
     * the scenario has no registry.
     *
     * @param where   the {@code WHERE} clause being built, outside any {@code GRAPH}
     * @param taken   what the write took
     */
    void appendWriteGuards(StringBuilder where, Resolved taken) {
        if (taken.bootstraps()) {
            where.append(" FILTER NOT EXISTS { GRAPH ").append(meta).append(" { ").append(schemaNode)
                    .append(" pdb:rev ?anyRev } }");
            return;
        }
        where.append(" FILTER EXISTS { GRAPH ").append(meta).append(" { ").append(schemaNode).append(" pdb:rev ")
                .append(SparqlText.integer(taken.rev())).append(" . ?takenVersion a pdb:Version ; pdb:name ")
                .append(SparqlText.str(taken.name())).append(" ; pdb:rank ").append(SparqlText.integer(taken.rank()))
                .append(" } }");
    }

    /**
     * The triples a bootstrapping root write inserts: the registry with the root's name at the first rank,
     * permissive.
     *
     * @param insert the {@code INSERT} template being built, inside the metadata graph
     * @param taken  what the root took
     * @param now    the creation time
     */
    void appendBootstrap(StringBuilder insert, Resolved taken, ZonedDateTime now) {
        appendBootstrap(insert, true);
        appendNode(insert, taken.name(), taken.rank(), false, now);
    }

    /** After a bootstrapping root write: the registry it created, without reading it back. */
    void bootstrapped(Resolved taken) {
        state = new State(1, true, List.of(new Entry(taken.name(), taken.rank(), false)));
    }

    // ------------------------------------------------------------------ SPARQL

    private void appendBootstrap(StringBuilder insert, boolean permissive) {
        insert.append(schemaNode).append(" pdb:schema ").append(SparqlText.integer(RdfDbVocabulary.SCHEMA_VERSION))
                .append(" ; pdb:scenario ").append(SparqlText.str(scenario))
                .append(" ; pdb:rev ").append(SparqlText.integer(1))
                .append(" ; pdb:permissive ").append(SparqlText.bool(permissive)).append(" . ");
    }

    private void appendNode(StringBuilder insert, String name, int rank, boolean isTransient, ZonedDateTime now) {
        insert.append(nodeOf(name)).append(" a pdb:Version ; pdb:name ").append(SparqlText.str(name))
                .append(" ; pdb:rank ").append(SparqlText.integer(rank))
                .append(" ; pdb:scenario ").append(SparqlText.str(scenario))
                .append(" ; pdb:created ").append(SparqlText.dateTime(now));
        if (isTransient) {
            insert.append(" ; pdb:transient ").append(SparqlText.bool(true));
        }
        insert.append(" . ");
    }

    /** One name above or between the others, guarded on the revision alone: equal revisions are equal registries. */
    private String append(State before, String name, int rank, ZonedDateTime now) {
        StringBuilder update = new StringBuilder(RdfDbVocabulary.PREFIXES).append("DELETE { GRAPH ").append(meta)
                .append(" { ").append(schemaNode).append(" pdb:rev ").append(SparqlText.integer(before.rev()))
                .append(" } } INSERT { GRAPH ").append(meta).append(" { ").append(schemaNode).append(" pdb:rev ")
                .append(SparqlText.integer(before.rev() + 1)).append(" . ");
        appendNode(update, name, rank, false, now);
        return update.append(" } } WHERE { GRAPH ").append(meta).append(" { ").append(revGuard(before))
                .append(" } }").toString();
    }

    private String revGuard(State before) {
        return schemaNode + " pdb:rev " + SparqlText.integer(before.rev()) + " .";
    }

    private static String unused(String name) {
        return "FILTER NOT EXISTS { ?carrier a pdb:Snapshot ; pdb:version " + SparqlText.str(name) + " }";
    }

    private String nodeOf(String name) {
        return SparqlText.iri(RdfDbNames.versionNode(scenario, name));
    }

    /** Send a guarded edit and read the registry back; a refused edit is a conflict that changed nothing. */
    private void write(String update, State before, String what, Predicate<State> applied) {
        if (beforeRegistryWrite != null) {
            beforeRegistryWrite.run();
        }
        connection.sparql(scenario).update(update);
        refresh();
        State after = state();
        if (after.rev() != before.rev() + 1 || !applied.test(after)) {
            throw new RdfDbConflictException("the version registry of scenario '" + scenario + "' changed (rev "
                    + before.rev() + " → " + after.rev() + ") while this connection tried to " + what + ": nothing"
                    + " was changed by it; the registry is now " + this + ", retry");
        }
    }

    /** {@link #write} for an edit that a snapshot carrying the name refuses, naming the snapshot. */
    private void writeUnlessCarried(String update, State before, String name, String done,
                                    Predicate<State> applied) {
        try {
            write(update, before, "edit '" + name + "'", applied);
        } catch (RdfDbConflictException e) {
            if (rev() == before.rev()) {
                List<Map<String, Value>> carrier = catalog.select("SELECT ?s WHERE { GRAPH " + meta + " { ?s a"
                        + " pdb:Snapshot ; pdb:version " + SparqlText.str(name) + " } } ORDER BY ?s LIMIT 1");
                if (!carrier.isEmpty()) {
                    throw new RdfDbException("version '" + name + "' of scenario '" + scenario + "' cannot be " + done
                            + ": the snapshot " + SnapshotRows.text(carrier.get(0), "s") + " carries it"
                            + ("deleted".equals(done) ? " (only a transient version is deleted with its snapshots)"
                            : " (a snapshot IRI carries its version name; rerank to change the order)"), e);
                }
            }
            throw e;
        }
    }

    private State existing() {
        State s = state();
        if (!s.exists()) {
            throw new RdfDbException("scenario '" + scenario + "' has no version registry: create one, or write a"
                    + " root snapshot, which creates a permissive one");
        }
        return s;
    }

    private Entry registered(State s, String name) {
        return s.entry(name).orElseThrow(() -> new RdfDbException("version '" + name + "' is not registered in"
                + " scenario '" + scenario + "' (registry: " + this + ")"));
    }

    private void refuseRegistered(State s, String name) {
        checkName(name);
        s.entry(name).ifPresent(e -> {
            throw new RdfDbException("version '" + name + "' is already registered in scenario '" + scenario
                    + "' at rank " + e.rank());
        });
    }

    private static void checkName(String name) {
        if (name == null || name.isBlank()) {
            throw new RdfDbException("a version name must not be blank");
        }
    }
}
