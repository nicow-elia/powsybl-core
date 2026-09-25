/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.triplestore.impl.rdf4j.sparql;

import com.powsybl.triplestore.api.TripleStoreException;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandler;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;
import org.eclipse.rdf4j.rio.ntriples.NTriplesParserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * A SPARQL 1.1 Graph Store Protocol client: one named graph per request, streamed as N-Triples.
 *
 * <p>This is the fast lane between CGMES files and a graph database. The alternative &mdash; adding statements
 * through a SPARQL connection &mdash; turns each statement into a piece of an {@code INSERT DATA} string, which
 * for a CGMES EQ file means a multi-megabyte query. The Graph Store Protocol instead takes the graph as a request
 * <em>body</em> in an RDF serialisation, so the client writes N-Triples straight into the socket and the server
 * parses them with its own bulk loader.</p>
 *
 * <p>N-Triples is the serialisation of choice in both directions: it is line-based, so it streams without buffering
 * a document; its IRIs are absolute, so nothing has to be resolved against a base; and it is the cheapest parser
 * RDF4J has. RDF/XML, the format CGMES files come in, is none of those things.</p>
 *
 * <p>Built on the JDK HTTP client, not on Apache HttpClient: one dependency less on the path that matters, and a
 * request body can be a plain {@link InputStream}. Thread-safe; one client is shared by all the threads of a
 * parallel upload or fetch.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class GraphStoreClient implements AutoCloseable {

    private static final String N_TRIPLES = "application/n-triples";
    private static final int FETCH_BUFFER = 64 * 1024;

    private final SparqlEndpoint endpoint;
    private final HttpClient httpClient;
    private final boolean gzip;

    /**
     * A client for the Graph Store Protocol endpoint of the given database.
     *
     * @param endpoint the database coordinates. A {@code null} {@link SparqlEndpoint#graphStoreUrl()} makes
     *                 {@link #supported()} return {@code false} and every transfer method fail
     */
    public GraphStoreClient(SparqlEndpoint endpoint) {
        this(endpoint, false);
    }

    /**
     * A client for the Graph Store Protocol endpoint of the given database, optionally compressing transfers.
     *
     * <p>Compression costs CPU on both ends and buys bandwidth. On a loopback connection that trade is a loss,
     * which is why it is off by default; over a real network it is usually a win.</p>
     *
     * @param endpoint the database coordinates
     * @param gzip     whether to ask for gzip-encoded responses
     */
    public GraphStoreClient(SparqlEndpoint endpoint, boolean gzip) {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.gzip = gzip;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(endpoint.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Whether the database has a Graph Store Protocol endpoint at all.
     *
     * @return {@code true} when graphs can be transferred with this client
     */
    public boolean supported() {
        return endpoint.graphStoreUrl() != null;
    }

    /**
     * Replace a named graph with the given statements.
     *
     * @param graphIri  the IRI of the graph to write
     * @param nTriples  the statements, as an N-Triples stream. It is consumed and closed
     * @param byteCount the exact length of the stream in bytes, so that the request can be sent with a
     *                  {@code Content-Length} rather than chunked
     */
    public void put(String graphIri, InputStream nTriples, long byteCount) {
        send("PUT", graphIri, nTriples, byteCount);
    }

    /**
     * Add the given statements to a named graph, keeping what is already there.
     *
     * @param graphIri  the IRI of the graph to write
     * @param nTriples  the statements, as an N-Triples stream. It is consumed and closed
     * @param byteCount the exact length of the stream in bytes
     */
    public void post(String graphIri, InputStream nTriples, long byteCount) {
        send("POST", graphIri, nTriples, byteCount);
    }

    /**
     * Drop a named graph.
     *
     * <p>A graph that is not there is not an error: the server answers 404 and this method returns quietly, as
     * {@code CLEAR SILENT GRAPH} would.</p>
     *
     * @param graphIri the IRI of the graph to drop
     */
    public void delete(String graphIri) {
        HttpRequest request = request(graphIri).DELETE().build();
        HttpResponse<String> response = execute(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 404) {
            LOGGER.debug("Graph {} was already absent", graphIri);
            return;
        }
        checkStatus("DELETE", graphIri, status, response.body());
    }

    /**
     * Read a named graph and hand its statements to a handler as they arrive.
     *
     * <p>The response body is parsed while it is still being received, so a graph of a million statements never
     * exists as bytes in memory.</p>
     *
     * @param graphIri the IRI of the graph to read
     * @param handler  what to do with the statements
     * @return {@code true} if the graph existed, {@code false} if the server answered 404
     */
    public boolean get(String graphIri, RDFHandler handler) {
        Objects.requireNonNull(handler);
        HttpRequest.Builder builder = request(graphIri).GET().header("Accept", N_TRIPLES);
        if (gzip) {
            builder.header("Accept-Encoding", "gzip");
        }
        HttpResponse<InputStream> response = execute(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() == 404) {
                return false;
            }
            checkStatus("GET", graphIri, response.statusCode(), "");
            InputStream in = response.headers().firstValue("Content-Encoding")
                    .filter("gzip"::equalsIgnoreCase).isPresent()
                    ? new GZIPInputStream(body, FETCH_BUFFER)
                    : body;
            parser(handler).parse(new BufferedInputStream(in, FETCH_BUFFER), graphIri);
            return true;
        } catch (IOException e) {
            throw new TripleStoreException("Reading graph " + graphIri + " from " + endpoint.graphStoreUrl(), e);
        }
    }

    /**
     * An N-Triples parser configured the way the RDF database path needs it.
     *
     * <p>IRI syntax verification is off: the statements come out of a store that was filled by the RDF4J RDF/XML
     * parser with the CGMES non-fatal settings, so identifiers that a strict parser would reject are expected and
     * were accepted on the way in. Invalid <em>lines</em>, on the other hand, are fatal: that would be a broken
     * transfer, not odd data.</p>
     *
     * @param handler what to do with the statements
     * @return the parser
     */
    public static RDFParser parser(RDFHandler handler) {
        RDFParser parser = Rio.createParser(RDFFormat.NTRIPLES);
        parser.getParserConfig().set(BasicParserSettings.VERIFY_URI_SYNTAX, false);
        parser.getParserConfig().set(NTriplesParserSettings.FAIL_ON_INVALID_LINES, true);
        parser.setRDFHandler(handler);
        return parser;
    }

    private void send(String method, String graphIri, InputStream nTriples, long byteCount) {
        if (!supported()) {
            throw new TripleStoreException("The endpoint " + endpoint.queryUrl()
                    + " has no Graph Store Protocol URL, graphs cannot be transferred");
        }
        HttpRequest request = request(graphIri)
                .method(method, HttpRequest.BodyPublishers.fromPublisher(
                        HttpRequest.BodyPublishers.ofInputStream(() -> nTriples), byteCount))
                .header("Content-Type", N_TRIPLES)
                .build();
        HttpResponse<String> response = execute(request, HttpResponse.BodyHandlers.ofString());
        checkStatus(method, graphIri, response.statusCode(), response.body());
    }

    private HttpRequest.Builder request(String graphIri) {
        if (!supported()) {
            throw new TripleStoreException("The endpoint " + endpoint.queryUrl()
                    + " has no Graph Store Protocol URL, graphs cannot be transferred");
        }
        URI uri = URI.create(endpoint.graphStoreUrl().toString() + "?graph="
                + ScenarioGraphNames.encode(graphIri));
        // Note: HttpRequest.timeout bounds the wait for the response headers, not the streaming of the body.
        // A server that answers and then stalls mid-graph is caught by the socket timeout of the JVM's HTTP
        // client, not here; the read timeout is what a caller can influence.
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(endpoint.readTimeout());
        String authorization = endpoint.basicAuthorizationHeader();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        endpoint.headers().forEach(builder::header);
        return builder;
    }

    private <T> HttpResponse<T> execute(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return httpClient.send(request, handler);
        } catch (IOException e) {
            throw new TripleStoreException("Graph Store Protocol request to " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TripleStoreException("Interrupted during a Graph Store Protocol request to " + request.uri(), e);
        }
    }

    private static void checkStatus(String method, String graphIri, int status, String body) {
        if (status < 200 || status >= 300) {
            String detail = body == null || body.isBlank() ? "" : ": " + shorten(body);
            throw new TripleStoreException(method + " of graph " + graphIri + " answered HTTP " + status + detail);
        }
    }

    private static String shorten(String body) {
        String oneLine = body.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 500 ? oneLine : oneLine.substring(0, 500) + "...";
    }

    @Override
    public void close() {
        httpClient.close();
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphStoreClient.class);
}
