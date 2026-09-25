/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.tdb2.DatabaseMgr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * A real SPARQL server, in this JVM, for the length of a test class.
 *
 * <p>Nothing that matters about loading CGMES from a database can be shown against a mock: the question is what a
 * server does with a Graph Store Protocol body and with the dataset parameters of a query. Apache Jena Fuseki is
 * that server here, on a free port, in two flavours &mdash; a transactional in-memory dataset for the functional
 * tests, and a TDB2 dataset on disk for the benchmark, where a real storage engine changes the numbers.</p>
 *
 * <p>Jena is a <strong>test dependency only</strong>. The client side of everything here is RDF4J and the JDK HTTP
 * client, so that production code never sees it.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class EmbeddedFuseki implements AutoCloseable {

    private final FusekiServer server;
    private final Path directory;

    private EmbeddedFuseki(DatasetGraph dataset, Path directory) {
        this(dataset, directory, true);
    }

    private EmbeddedFuseki(DatasetGraph dataset, Path directory, boolean writable) {
        this.directory = directory;
        this.server = FusekiServer.create()
                .port(0)
                .verbose(false)
                .enablePing(true)
                .add("/ds", dataset, writable)
                .build()
                .start();
    }

    /**
     * Start a server whose dataset refuses every write.
     *
     * <p>The only way to get a real 4xx out of a Graph Store Protocol request without a security setup, which is
     * what the error handling of the client has to be tested against.</p>
     *
     * @return the running server
     */
    public static EmbeddedFuseki readOnly() {
        return new EmbeddedFuseki(DatasetGraphFactory.createTxnMem(), null, false);
    }

    /**
     * Start a server on a transactional in-memory dataset.
     *
     * @return the running server
     */
    public static EmbeddedFuseki inMemory() {
        return new EmbeddedFuseki(DatasetGraphFactory.createTxnMem(), null);
    }

    /**
     * Start a server on a TDB2 dataset in the given directory.
     *
     * <p>The directory is emptied first and deleted when the server is closed.</p>
     *
     * @param directory where the database files go, normally somewhere under {@code target/}
     * @return the running server
     */
    public static EmbeddedFuseki tdb2(Path directory) {
        try {
            deleteRecursively(directory);
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare the TDB2 directory " + directory, e);
        }
        return new EmbeddedFuseki(DatabaseMgr.connectDatasetGraph(Location.create(directory.toString())), directory);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
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
     * @return a description of this server as an RDF database
     */
    public RdfDatabase database() {
        return RdfDatabase.fuseki(datasetUrl());
    }

    @Override
    public void close() {
        server.stop();
        if (directory != null) {
            try {
                deleteRecursively(directory);
            } catch (IOException e) {
                // Leftover files under target/ are not worth failing a test for
            }
        }
    }
}
