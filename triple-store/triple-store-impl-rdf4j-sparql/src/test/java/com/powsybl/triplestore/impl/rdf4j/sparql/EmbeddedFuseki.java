/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.triplestore.impl.rdf4j.sparql;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;

/**
 * A real SPARQL server, in this JVM, for the length of a test class.
 *
 * <p>Nothing about a remote triple store can be tested against a mock: the whole point of the implementation is
 * what an HTTP server does with a SPARQL request and a Graph Store Protocol body. Apache Jena Fuseki is that
 * server, embedded on a free port. It is a <strong>test dependency only</strong> &mdash; production code must never
 * see Jena, which is why the client side is RDF4J and the JDK HTTP client throughout.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class EmbeddedFuseki implements AutoCloseable {

    private final FusekiServer server;

    private EmbeddedFuseki(DatasetGraph dataset) {
        this.server = FusekiServer.create()
                .port(0)
                .verbose(false)
                .enablePing(true)
                .add("/ds", dataset)
                .build()
                .start();
    }

    /**
     * Start a server on a transactional in-memory dataset.
     *
     * @return the running server
     */
    public static EmbeddedFuseki inMemory() {
        return new EmbeddedFuseki(DatasetGraphFactory.createTxnMem());
    }

    /**
     * @return the port the server listens on
     */
    public int port() {
        return server.getPort();
    }

    /**
     * @return the URL of the dataset, without a trailing slash
     */
    public String datasetUrl() {
        return "http://localhost:" + port() + "/ds";
    }

    /**
     * @return the coordinates of the dataset, Fuseki layout
     */
    public SparqlEndpoint endpoint() {
        return SparqlEndpoint.fuseki(datasetUrl());
    }

    @Override
    public void close() {
        server.stop();
    }
}
