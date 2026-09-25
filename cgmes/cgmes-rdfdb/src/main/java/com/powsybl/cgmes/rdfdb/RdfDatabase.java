/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.triplestore.impl.rdf4j.sparql.SparqlEndpoint;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Which RDF database to talk to, and how to move data in and out of it.
 *
 * <p>An immutable description, not a connection: it can be built, passed around and stored, and
 * {@link RdfDbConnection#open(RdfDatabase)} turns it into something that holds sockets. It says nothing about
 * <em>which</em> data to read &mdash; that is the scenario, and it is a parameter of every call, because one
 * database holds many days and many base grid models at once.</p>
 *
 * <p>Two backends exist. A SPARQL database (Fuseki, RDF4J server, GraphDB, anything speaking SPARQL 1.1 and
 * preferably the Graph Store Protocol) is the real one. {@link #inMemory(String)}, addressed as
 * {@code memory:<name>}, is the second: a database that lives in this JVM, for tests, for demonstrations and for
 * users who want the split loading without running a server. Everything above this class is written once and
 * works on both.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class RdfDatabase {

    /** Where the CGMES query catalogs are evaluated. */
    public enum QueryMode {
        /**
         * Fetch the graphs of the scenario into a local in-memory store and run the catalogs there. The default:
         * one bulk transfer per graph, the queries then cost nothing, and the semantics are byte for byte the ones
         * of a file import.
         */
        LOCAL,
        /**
         * Run the catalogs on the server. Eighty-odd round trips and server CPU instead of a bulk transfer, which
         * is slower for a whole network but does not need the model to fit in this process.
         */
        REMOTE
    }

    /** The URL scheme of the in-process backend. */
    public static final String MEMORY_SCHEME = "memory:";

    /** How many graphs are fetched at once by default. */
    public static final int DEFAULT_FETCH_PARALLELISM = 4;

    /** How many instance files are uploaded at once by default. */
    public static final int DEFAULT_UPLOAD_PARALLELISM = 4;

    private final SparqlEndpoint endpoint;
    private final String memoryName;
    private final QueryMode queryMode;
    private final int fetchParallelism;
    private final int uploadParallelism;
    private final boolean gzip;
    private final GraphCache cache;

    private RdfDatabase(SparqlEndpoint endpoint, String memoryName, QueryMode queryMode, int fetchParallelism,
                        int uploadParallelism, boolean gzip, GraphCache cache) {
        this.endpoint = endpoint;
        this.memoryName = memoryName;
        this.queryMode = queryMode;
        this.fetchParallelism = fetchParallelism;
        this.uploadParallelism = uploadParallelism;
        this.gzip = gzip;
        this.cache = cache;
    }

    /**
     * A database at the given SPARQL endpoint.
     *
     * @param endpoint the endpoint coordinates
     * @return the database description
     */
    public static RdfDatabase of(SparqlEndpoint endpoint) {
        Objects.requireNonNull(endpoint);
        return new RdfDatabase(endpoint, null, QueryMode.LOCAL, DEFAULT_FETCH_PARALLELISM,
                DEFAULT_UPLOAD_PARALLELISM, gzipDefault(endpoint), null);
    }

    /**
     * A database described by a URL.
     *
     * <p>{@code memory:<name>} is the in-process backend; anything else goes through
     * {@link SparqlEndpoint#parse(String)}.</p>
     *
     * @param url the URL
     * @return the database description
     */
    public static RdfDatabase parse(String url) {
        Objects.requireNonNull(url, "an RDF database needs a URL");
        String trimmed = url.trim();
        if (trimmed.startsWith(MEMORY_SCHEME)) {
            return inMemory(trimmed.substring(MEMORY_SCHEME.length()));
        }
        return of(SparqlEndpoint.parse(trimmed));
    }

    /**
     * A Fuseki dataset.
     *
     * @param datasetUrl the dataset URL, for instance {@code http://localhost:3030/ds}
     * @return the database description
     */
    public static RdfDatabase fuseki(String datasetUrl) {
        return of(SparqlEndpoint.fuseki(datasetUrl));
    }

    /**
     * A repository of an RDF4J server.
     *
     * @param serverUrl    the server URL
     * @param repositoryId the repository identifier
     * @return the database description
     */
    public static RdfDatabase rdf4jServer(String serverUrl, String repositoryId) {
        return of(SparqlEndpoint.rdf4jServer(serverUrl, repositoryId));
    }

    /**
     * A GraphDB repository.
     *
     * @param serverUrl    the server URL
     * @param repositoryId the repository identifier
     * @return the database description
     */
    public static RdfDatabase graphDb(String serverUrl, String repositoryId) {
        return of(SparqlEndpoint.graphDb(serverUrl, repositoryId));
    }

    /**
     * A database in this JVM, shared by everybody who names it the same.
     *
     * <p>Each scenario of an in-memory database gets its own RDF4J memory store, which is what makes the
     * isolation between scenarios exactly as strict as it is on a server. The database lives as long as at least
     * one connection to it is open.</p>
     *
     * @param name the name of the database
     * @return the database description
     */
    public static RdfDatabase inMemory(String name) {
        Objects.requireNonNull(name, "an in-memory RDF database needs a name");
        if (name.isBlank()) {
            throw new RdfDbException("An in-memory RDF database needs a name");
        }
        return new RdfDatabase(null, name, QueryMode.LOCAL, DEFAULT_FETCH_PARALLELISM,
                DEFAULT_UPLOAD_PARALLELISM, false, null);
    }

    private static boolean gzipDefault(SparqlEndpoint endpoint) {
        // Compressing a bulk transfer over loopback costs more CPU than it saves time
        String host = endpoint.queryUrl().getHost();
        boolean loopback = host == null || "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        return !loopback;
    }

    private RdfDatabase with(QueryMode newQueryMode, int newFetchParallelism, int newUploadParallelism,
                             boolean newGzip, GraphCache newCache) {
        return new RdfDatabase(endpoint, memoryName, newQueryMode, newFetchParallelism, newUploadParallelism,
                newGzip, newCache);
    }

    /**
     * The same database with other credentials.
     *
     * @param user     the user name
     * @param password the password
     * @return a new description
     */
    public RdfDatabase withCredentials(String user, String password) {
        return new RdfDatabase(requireRemote().withCredentials(user, password), memoryName, queryMode,
                fetchParallelism, uploadParallelism, gzip, cache);
    }

    /**
     * The same database with one more HTTP header on every request.
     *
     * @param name  the header name
     * @param value the header value
     * @return a new description
     */
    public RdfDatabase withHeader(String name, String value) {
        return new RdfDatabase(requireRemote().withHeader(name, value), memoryName, queryMode,
                fetchParallelism, uploadParallelism, gzip, cache);
    }

    /**
     * The same database with other timeouts.
     *
     * @param connect how long to wait for a connection
     * @param read    how long to wait for a response
     * @return a new description
     */
    public RdfDatabase withTimeouts(Duration connect, Duration read) {
        return new RdfDatabase(requireRemote().withTimeouts(connect, read), memoryName, queryMode,
                fetchParallelism, uploadParallelism, gzip, cache);
    }

    /**
     * The same database, queried in the given mode.
     *
     * @param mode where the CGMES query catalogs are evaluated
     * @return a new description
     */
    public RdfDatabase withQueryMode(QueryMode mode) {
        return with(Objects.requireNonNull(mode), fetchParallelism, uploadParallelism, gzip, cache);
    }

    /**
     * The same database, fetching that many graphs at once.
     *
     * @param n the number of concurrent fetches, at least 1
     * @return a new description
     */
    public RdfDatabase withFetchParallelism(int n) {
        return with(queryMode, requirePositive(n, "fetchParallelism"), uploadParallelism, gzip, cache);
    }

    /**
     * The same database, uploading that many instance files at once.
     *
     * @param n the number of concurrent uploads, at least 1
     * @return a new description
     */
    public RdfDatabase withUploadParallelism(int n) {
        return with(queryMode, fetchParallelism, requirePositive(n, "uploadParallelism"), gzip, cache);
    }

    /**
     * The same database, with or without compressed transfers.
     *
     * @param enabled whether to gzip transfers. The default is off for a loopback host and on otherwise
     * @return a new description
     */
    public RdfDatabase withGzip(boolean enabled) {
        return with(queryMode, fetchParallelism, uploadParallelism, enabled, cache);
    }

    /**
     * The same database, reusing parsed graphs from a cache.
     *
     * <p>Off by default, and that is a correctness decision, not a performance one: as long as a graph can be
     * overwritten in place, a cached copy may be stale. The cache checks the statement count of a graph before it
     * trusts an entry, which catches a rewrite but not an edit of the same size.</p>
     *
     * @param newCache the cache to use, or {@code null} for none
     * @return a new description
     */
    public RdfDatabase withCache(GraphCache newCache) {
        return with(queryMode, fetchParallelism, uploadParallelism, gzip, newCache);
    }

    private static int requirePositive(int n, String what) {
        if (n < 1) {
            throw new RdfDbException(what + " must be at least 1, got " + n);
        }
        return n;
    }

    private SparqlEndpoint requireRemote() {
        if (endpoint == null) {
            throw new RdfDbException("The in-memory RDF database '" + memoryName + "' has no endpoint settings");
        }
        return endpoint;
    }

    /**
     * @return the SPARQL endpoint, or {@code null} for the in-process backend
     */
    public SparqlEndpoint endpoint() {
        return endpoint;
    }

    /**
     * @return {@code true} when this is the in-process backend
     */
    public boolean isInMemory() {
        return memoryName != null;
    }

    /**
     * @return the name of the in-process database, or {@code null} for a real one
     */
    public String memoryName() {
        return memoryName;
    }

    /**
     * @return where the CGMES query catalogs are evaluated
     */
    public QueryMode queryMode() {
        return queryMode;
    }

    /**
     * @return how many graphs are fetched at once
     */
    public int fetchParallelism() {
        return fetchParallelism;
    }

    /**
     * @return how many instance files are uploaded at once
     */
    public int uploadParallelism() {
        return uploadParallelism;
    }

    /**
     * @return whether transfers are compressed
     */
    public boolean gzip() {
        return gzip;
    }

    /**
     * @return the graph cache, or {@code null} when caching is off
     */
    public GraphCache cache() {
        return cache;
    }

    /**
     * Build a database description from the string options the language bindings carry.
     *
     * <p>Recognised keys: {@code update_url}, {@code graph_store_url}, {@code user}, {@code password},
     * {@code header.<name>}, {@code query_mode} ({@code local} or {@code remote}), {@code fetch_parallelism},
     * {@code upload_parallelism}, {@code gzip}, {@code connect_timeout_ms}, {@code read_timeout_ms},
     * {@code cache}. The scenario is <em>not</em> among them: it is an argument of every operation.</p>
     *
     * @param url     the database URL, {@code memory:<name>} or the URL of a SPARQL database
     * @param options the options, may be empty
     * @return the database description
     */
    public static RdfDatabase fromParameters(String url, Map<String, String> options) {
        Map<String, String> p = new LinkedHashMap<>(Objects.requireNonNullElseGet(options, Map::of));
        RdfDatabase db = parse(url);
        if (!db.isInMemory()) {
            SparqlEndpoint endpoint = db.endpoint();
            String updateUrl = p.remove("update_url");
            String graphStoreUrl = p.remove("graph_store_url");
            if (updateUrl != null || graphStoreUrl != null) {
                endpoint = new SparqlEndpoint(endpoint.queryUrl(),
                        updateUrl == null ? endpoint.updateUrl() : java.net.URI.create(updateUrl),
                        graphStoreUrl == null ? endpoint.graphStoreUrl() : java.net.URI.create(graphStoreUrl),
                        endpoint.user(), endpoint.password(), endpoint.headers(),
                        endpoint.connectTimeout(), endpoint.readTimeout());
            }
            String user = p.remove("user");
            if (user != null) {
                endpoint = endpoint.withCredentials(user, p.remove("password"));
            }
            for (Map.Entry<String, String> e : Map.copyOf(p).entrySet()) {
                if (e.getKey().startsWith("header.")) {
                    endpoint = endpoint.withHeader(e.getKey().substring("header.".length()), e.getValue());
                    p.remove(e.getKey());
                }
            }
            String connectMs = p.remove("connect_timeout_ms");
            String readMs = p.remove("read_timeout_ms");
            if (connectMs != null || readMs != null) {
                endpoint = endpoint.withTimeouts(
                        connectMs == null ? endpoint.connectTimeout() : Duration.ofMillis(Long.parseLong(connectMs)),
                        readMs == null ? endpoint.readTimeout() : Duration.ofMillis(Long.parseLong(readMs)));
            }
            db = of(endpoint);
        } else {
            p.remove("update_url");
            p.remove("graph_store_url");
            p.remove("user");
            p.remove("password");
            p.remove("connect_timeout_ms");
            p.remove("read_timeout_ms");
            p.keySet().removeIf(k -> k.startsWith("header."));
        }
        String queryMode = p.remove("query_mode");
        if (queryMode != null) {
            db = db.withQueryMode(QueryMode.valueOf(queryMode.trim().toUpperCase(java.util.Locale.ROOT)));
        }
        String fetch = p.remove("fetch_parallelism");
        if (fetch != null) {
            db = db.withFetchParallelism(Integer.parseInt(fetch));
        }
        String upload = p.remove("upload_parallelism");
        if (upload != null) {
            db = db.withUploadParallelism(Integer.parseInt(upload));
        }
        String gzip = p.remove("gzip");
        if (gzip != null) {
            db = db.withGzip(Boolean.parseBoolean(gzip));
        }
        String cache = p.remove("cache");
        if (cache != null && Boolean.parseBoolean(cache)) {
            db = db.withCache(new GraphCache());
        }
        if (!p.isEmpty()) {
            throw new RdfDbException("Unknown RDF database options: " + p.keySet());
        }
        return db;
    }

    @Override
    public String toString() {
        return isInMemory() ? MEMORY_SCHEME + memoryName : endpoint.queryUrl().toString();
    }
}
