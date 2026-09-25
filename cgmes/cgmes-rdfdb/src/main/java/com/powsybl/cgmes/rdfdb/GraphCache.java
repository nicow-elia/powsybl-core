/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import org.eclipse.rdf4j.model.Statement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parsed graphs kept for the next load.
 *
 * <p>Fetching and parsing a graph is the bulk of what a database load costs. A network that is loaded again from
 * the same scenario &mdash; a study that reads a base case over and over, a service answering requests &mdash;
 * can skip both, and the load then costs the CGMES conversion and nothing else.</p>
 *
 * <p>The cache is <strong>opt-in</strong>, and that is a correctness decision. As long as a scenario's graphs can
 * be replaced in place, a cached copy may be out of date, and no cheap question to the database can prove it is
 * not. What is checked is the statement count of the graph, which catches a graph that was reloaded with different
 * content; an edit that happens to keep the count the same slips through. {@link #trustImmutableGraphs(boolean)}
 * drops even that check, for a database whose graph IRIs are versioned and therefore never rewritten.</p>
 *
 * <p>The budget is counted in statements rather than bytes, because that is what is actually held: roughly 100
 * bytes per statement, so the two-million default is some 200 MB. Eviction is least-recently-used.</p>
 *
 * <p>Thread-safe.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class GraphCache {

    /** The default budget: two million statements, roughly 200 MB. */
    public static final long DEFAULT_MAX_STATEMENTS = 2_000_000L;

    /**
     * What the cache holds for one graph.
     *
     * @param statements   the parsed statements, unmodifiable
     * @param cimNamespace the CIM namespace seen while parsing the graph, or {@code null} if it holds no CIM type
     */
    public record Entry(List<Statement> statements, String cimNamespace) {

        /**
         * @param statements   see {@link #statements()}
         * @param cimNamespace see {@link #cimNamespace()}
         */
        public Entry {
            statements = List.copyOf(statements);
        }

        /**
         * @return how many statements the graph holds
         */
        public int size() {
            return statements.size();
        }
    }

    private final long maxStatements;
    private final Map<String, Entry> entries;
    private long statements;
    private boolean trustImmutableGraphs;
    private long hits;
    private long misses;

    /**
     * A cache with the default budget.
     */
    public GraphCache() {
        this(DEFAULT_MAX_STATEMENTS);
    }

    /**
     * A cache with the given budget.
     *
     * @param maxStatements how many statements the cache may hold in total, at least 1
     */
    public GraphCache(long maxStatements) {
        if (maxStatements < 1) {
            throw new RdfDbException("A graph cache needs a budget of at least one statement, got " + maxStatements);
        }
        this.maxStatements = maxStatements;
        this.entries = new LinkedHashMap<>(16, 0.75f, true);
    }

    /**
     * Stop checking the statement count of a graph before using its cached copy.
     *
     * <p>Only for a database whose graph IRIs identify a version of the data, so that content never changes under
     * an IRI. With it on, a cache hit costs no request at all.</p>
     *
     * @param trust whether cached graphs may be used without checking
     * @return this cache
     */
    public synchronized GraphCache trustImmutableGraphs(boolean trust) {
        this.trustImmutableGraphs = trust;
        return this;
    }

    /**
     * @return whether cached graphs are used without checking their statement count
     */
    public synchronized boolean isTrustImmutableGraphs() {
        return trustImmutableGraphs;
    }

    /**
     * The cached copy of a graph, if it can be trusted.
     *
     * @param remoteGraph        the graph IRI, which is the cache key
     * @param currentSize        how many statements the database says the graph holds now, or {@code -1} when the
     *                           caller did not ask. Ignored when {@link #trustImmutableGraphs(boolean)} is on
     * @return the entry, or {@code null} for a miss
     */
    public synchronized Entry get(String remoteGraph, long currentSize) {
        return get(remoteGraph, currentSize, trustImmutableGraphs);
    }

    /**
     * The cached copy of a graph, saying for this graph alone whether it can be trusted.
     *
     * <p>{@link #trustImmutableGraphs(boolean)} is a property of the whole database, and there is a case it cannot
     * express: a database whose instance file graphs are replaced in place but whose <em>difference</em> graphs are
     * written once and never again. The fetcher knows which of the two a graph is and says so here.</p>
     *
     * @param remoteGraph the graph IRI, which is the cache key
     * @param currentSize how many statements the database says the graph holds now, or {@code -1} when the caller
     *                    did not ask. Ignored when the graph is trusted
     * @param trusted     whether this graph can never have changed under its IRI
     * @return the entry, or {@code null} for a miss
     */
    public synchronized Entry get(String remoteGraph, long currentSize, boolean trusted) {
        Entry entry = entries.get(Objects.requireNonNull(remoteGraph));
        if (entry == null || !trusted && (currentSize < 0 || currentSize != entry.size())) {
            misses++;
            return null;
        }
        hits++;
        return entry;
    }

    /**
     * Remember a parsed graph.
     *
     * @param remoteGraph  the graph IRI, which is the cache key
     * @param graph        the parsed statements
     * @param cimNamespace the CIM namespace seen while parsing, or {@code null}
     */
    public synchronized void put(String remoteGraph, List<Statement> graph, String cimNamespace) {
        Objects.requireNonNull(remoteGraph);
        Entry previous = entries.remove(remoteGraph);
        if (previous != null) {
            statements -= previous.size();
        }
        Entry entry = new Entry(graph, cimNamespace);
        if (entry.size() > maxStatements) {
            // A single graph larger than the whole budget is not cached, rather than emptying the cache for it
            return;
        }
        entries.put(remoteGraph, entry);
        statements += entry.size();
        evict();
    }

    private void evict() {
        var it = entries.entrySet().iterator();
        while (statements > maxStatements && it.hasNext()) {
            Map.Entry<String, Entry> oldest = it.next();
            statements -= oldest.getValue().size();
            it.remove();
        }
    }

    /**
     * Forget one graph.
     *
     * @param remoteGraph the graph IRI
     */
    public synchronized void invalidate(String remoteGraph) {
        Entry removed = entries.remove(remoteGraph);
        if (removed != null) {
            statements -= removed.size();
        }
    }

    /**
     * Forget every graph of a scenario.
     *
     * @param prefix the graph IRI prefix of the scenario
     */
    public synchronized void invalidatePrefix(String prefix) {
        List<String> keys = new ArrayList<>(entries.keySet());
        keys.stream().filter(k -> k.startsWith(prefix)).forEach(this::invalidate);
    }

    /**
     * Forget everything.
     */
    public synchronized void clear() {
        entries.clear();
        statements = 0;
    }

    /**
     * @return how many statements the cache currently holds
     */
    public synchronized long statements() {
        return statements;
    }

    /**
     * @return how many graphs the cache currently holds
     */
    public synchronized int size() {
        return entries.size();
    }

    /**
     * @return the graph IRIs currently cached, least recently used first
     */
    public synchronized List<String> keys() {
        return Collections.unmodifiableList(new ArrayList<>(entries.keySet()));
    }

    /**
     * @return how many times a cached graph could be used
     */
    public synchronized long hits() {
        return hits;
    }

    /**
     * @return how many times a graph had to be fetched
     */
    public synchronized long misses() {
        return misses;
    }
}
