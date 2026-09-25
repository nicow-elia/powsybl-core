/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.triplestore.impl.rdf4j.sparql;

import com.powsybl.commons.PowsyblException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Where a SPARQL 1.1 graph database can be reached, and how.
 *
 * <p>A SPARQL database offers up to three HTTP services, and the three of them live at URLs the server chooses:
 * a <em>query</em> endpoint (SPARQL 1.1 Query Protocol), an <em>update</em> endpoint (SPARQL 1.1 Update) and a
 * <em>Graph Store Protocol</em> endpoint, over which a whole named graph is written or read in one request. The
 * last one is the reason CGMES data can be moved in and out of a database at a useful speed: it takes an
 * N-Triples stream instead of a SPARQL string of every statement.</p>
 *
 * <p>The three layouts that matter in practice have their own factory methods, because guessing them from a single
 * URL is not reliable: {@link #fuseki(String)}, {@link #rdf4jServer(String, String)} and
 * {@link #graphDb(String, String)}. {@link #parse(String)} is the best-effort version for a URL a user typed.</p>
 *
 * <p>Immutable; the {@code with*} methods return a new endpoint.</p>
 *
 * @param queryUrl       the SPARQL query endpoint. Required
 * @param updateUrl      the SPARQL update endpoint, or {@code null} for a read-only database
 * @param graphStoreUrl  the Graph Store Protocol endpoint, or {@code null} when the server has none. Without it
 *                       graphs are written with batched {@code INSERT DATA} requests, which is markedly slower
 * @param user           the user name for HTTP basic authentication, or {@code null} for no authentication
 * @param password       the password for HTTP basic authentication, or {@code null}
 * @param headers        additional HTTP headers sent with every request, for instance an API token
 * @param connectTimeout how long to wait for a connection to be established
 * @param readTimeout    how long to wait for a response
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record SparqlEndpoint(URI queryUrl, URI updateUrl, URI graphStoreUrl, String user, String password,
                             Map<String, String> headers, Duration connectTimeout, Duration readTimeout) {

    /** The default time to wait for a connection to be established: 10 seconds. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** The default time to wait for a response: 5 minutes, because a bulk graph transfer can be large. */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMinutes(5);

    /**
     * @param queryUrl       see {@link #queryUrl()}
     * @param updateUrl      see {@link #updateUrl()}
     * @param graphStoreUrl  see {@link #graphStoreUrl()}
     * @param user           see {@link #user()}
     * @param password       see {@link #password()}
     * @param headers        see {@link #headers()}
     * @param connectTimeout see {@link #connectTimeout()}
     * @param readTimeout    see {@link #readTimeout()}
     */
    public SparqlEndpoint {
        Objects.requireNonNull(queryUrl, "a SPARQL endpoint needs a query URL");
        headers = Map.copyOf(Objects.requireNonNullElseGet(headers, Map::of));
        connectTimeout = Objects.requireNonNullElse(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
        readTimeout = Objects.requireNonNullElse(readTimeout, DEFAULT_READ_TIMEOUT);
    }

    /**
     * The endpoint of an Apache Jena Fuseki dataset.
     *
     * <p>Fuseki exposes {@code <dataset>/query}, {@code <dataset>/update} and {@code <dataset>/data}. The last one
     * is the read-write Graph Store Protocol endpoint ({@code <dataset>/get} is its read-only twin).</p>
     *
     * @param datasetUrl the dataset URL, for instance {@code http://localhost:3030/ds}
     * @return the endpoint
     */
    public static SparqlEndpoint fuseki(String datasetUrl) {
        String base = trimTrailingSlash(datasetUrl);
        return of(base + "/query", base + "/update", base + "/data");
    }

    /**
     * The endpoint of a repository of an RDF4J server.
     *
     * <p>RDF4J server exposes {@code /repositories/<id>} for queries, {@code /repositories/<id>/statements} for
     * updates and {@code /repositories/<id>/rdf-graphs/service} for the Graph Store Protocol.</p>
     *
     * @param serverUrl    the server URL, for instance {@code http://localhost:8080/rdf4j-server}
     * @param repositoryId the repository identifier
     * @return the endpoint
     */
    public static SparqlEndpoint rdf4jServer(String serverUrl, String repositoryId) {
        String repo = trimTrailingSlash(serverUrl) + "/repositories/" + Objects.requireNonNull(repositoryId);
        return of(repo, repo + "/statements", repo + "/rdf-graphs/service");
    }

    /**
     * The endpoint of a GraphDB repository, which follows the RDF4J server layout.
     *
     * @param serverUrl    the server URL, for instance {@code http://localhost:7200}
     * @param repositoryId the repository identifier
     * @return the endpoint
     */
    public static SparqlEndpoint graphDb(String serverUrl, String repositoryId) {
        return rdf4jServer(serverUrl, repositoryId);
    }

    /**
     * Guess the layout of a database from a single URL.
     *
     * <p>A URL that contains {@code /repositories/<id>} is taken for an RDF4J server or GraphDB repository; a URL
     * ending in {@code /query} or {@code /sparql} is taken for a query endpoint whose siblings are {@code /update}
     * and {@code /data}; anything else is taken for a Fuseki dataset. When the guess is wrong, name the parts
     * explicitly with {@link #rdf4jServer(String, String)} or the record constructor.</p>
     *
     * @param url the URL a user gave
     * @return the endpoint
     */
    public static SparqlEndpoint parse(String url) {
        String trimmed = trimTrailingSlash(url);
        int repositories = trimmed.indexOf("/repositories/");
        if (repositories >= 0) {
            String tail = trimmed.substring(repositories + "/repositories/".length());
            String repositoryId = tail.contains("/") ? tail.substring(0, tail.indexOf('/')) : tail;
            return rdf4jServer(trimmed.substring(0, repositories), repositoryId);
        }
        if (trimmed.endsWith("/query") || trimmed.endsWith("/sparql")) {
            String base = trimmed.substring(0, trimmed.lastIndexOf('/'));
            return of(trimmed, base + "/update", base + "/data");
        }
        return fuseki(trimmed);
    }

    private static SparqlEndpoint of(String queryUrl, String updateUrl, String graphStoreUrl) {
        return new SparqlEndpoint(uri(queryUrl), uri(updateUrl), uri(graphStoreUrl), null, null, Map.of(),
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    private static URI uri(String s) {
        try {
            return new URI(s);
        } catch (URISyntaxException e) {
            throw new PowsyblException("Not a valid SPARQL endpoint URL: " + s, e);
        }
    }

    private static String trimTrailingSlash(String url) {
        String s = Objects.requireNonNull(url, "a SPARQL endpoint needs a URL").trim();
        if (s.isEmpty()) {
            throw new PowsyblException("A SPARQL endpoint URL must not be empty");
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * The same endpoint with HTTP basic authentication credentials.
     *
     * @param newUser     the user name, or {@code null} to drop the credentials
     * @param newPassword the password
     * @return a new endpoint
     */
    public SparqlEndpoint withCredentials(String newUser, String newPassword) {
        return new SparqlEndpoint(queryUrl, updateUrl, graphStoreUrl, newUser, newPassword, headers,
                connectTimeout, readTimeout);
    }

    /**
     * The same endpoint with one more HTTP header.
     *
     * @param name  the header name
     * @param value the header value
     * @return a new endpoint
     */
    public SparqlEndpoint withHeader(String name, String value) {
        Map<String, String> newHeaders = new LinkedHashMap<>(headers);
        newHeaders.put(Objects.requireNonNull(name), Objects.requireNonNull(value));
        return new SparqlEndpoint(queryUrl, updateUrl, graphStoreUrl, user, password, newHeaders,
                connectTimeout, readTimeout);
    }

    /**
     * The same endpoint with other timeouts.
     *
     * @param newConnectTimeout how long to wait for a connection, or {@code null} for the default
     * @param newReadTimeout    how long to wait for a response, or {@code null} for the default
     * @return a new endpoint
     */
    public SparqlEndpoint withTimeouts(Duration newConnectTimeout, Duration newReadTimeout) {
        return new SparqlEndpoint(queryUrl, updateUrl, graphStoreUrl, user, password, headers,
                newConnectTimeout, newReadTimeout);
    }

    /**
     * The value of the {@code Authorization} header for the configured credentials.
     *
     * @return the {@code Basic ...} header value, or {@code null} when no user is configured
     */
    public String basicAuthorizationHeader() {
        if (user == null) {
            return null;
        }
        String raw = user + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whether this endpoint can be written to at all.
     *
     * @return {@code true} when either an update URL or a Graph Store Protocol URL is configured
     */
    public boolean writable() {
        return updateUrl != null || graphStoreUrl != null;
    }

    /**
     * An HTTP client configured with the timeouts of this endpoint.
     *
     * <p>RDF4J's {@code SPARQLRepository} otherwise uses a default Apache HttpClient with no connect and no
     * socket timeout, so a query against a host that silently drops packets would hang until the operating
     * system gives up &mdash; and the configured timeouts would cover only the Graph Store Protocol transfers.
     * Hand the result to {@code SPARQLRepository.setHttpClient}.</p>
     *
     * @return a new Apache HttpClient honouring {@link #connectTimeout()} and {@link #readTimeout()}
     */
    public HttpClient newHttpClient() {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout((int) connectTimeout.toMillis())
                .setConnectionRequestTimeout((int) connectTimeout.toMillis())
                .setSocketTimeout((int) readTimeout.toMillis())
                .build();
        return HttpClientBuilder.create().setDefaultRequestConfig(config).useSystemProperties().build();
    }

    /**
     * A description that never prints a secret.
     *
     * <p>The generated {@code toString} of a record prints every component, and two of them &mdash; the password
     * and the header values, which is where an API token lives &mdash; must not reach a log line or an assertion
     * failure message.</p>
     *
     * @return the URLs, the user name, and the header names with their values masked
     */
    @Override
    public String toString() {
        return "SparqlEndpoint[queryUrl=" + queryUrl
                + ", updateUrl=" + updateUrl
                + ", graphStoreUrl=" + graphStoreUrl
                + ", user=" + user
                + ", password=" + (password == null ? "null" : "***")
                + ", headers=" + headers.keySet().stream().collect(
                        java.util.stream.Collectors.joining(", ", "{", headers.isEmpty() ? "}" : "=***}"))
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
