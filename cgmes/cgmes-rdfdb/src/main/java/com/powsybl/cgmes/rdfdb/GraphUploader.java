/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Copies the named graphs of a locally parsed model into immutable graphs of the database.
 *
 * <p>The write half of a versioned upload. A snapshot needs its instance files under IRIs it owns &mdash;
 * {@code …/<scenario>/graph/<model id>} &mdash; rather than under the file name the loading layer uses, because a
 * versioned graph is written once and is then referenced by a snapshot forever. Immutability is what makes the
 * graph cacheable by name and a read concurrency-free.</p>
 *
 * <p>One graph is one request on a server, through the Graph Store Protocol, and the graphs go in parallel up to
 * {@code RdfDatabase.uploadParallelism()}. Nothing here is transactional and nothing has to be: a graph that no
 * snapshot refers to yet is invisible to every reader, and the guarded metadata write that comes afterwards is what
 * makes them visible &mdash; or leaves them to be dropped again.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class GraphUploader {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphUploader.class);

    private final RdfDbConnection connection;
    private final String scenario;

    GraphUploader(RdfDbConnection connection, String scenario) {
        this.connection = connection;
        this.scenario = RdfDbNames.checkScenario(scenario);
    }

    /**
     * Copy graphs of a local repository into the database.
     *
     * @param source        the repository the files were parsed into
     * @param localToRemote the target graph IRI per local context name
     * @return the graph IRIs that were written, so that a failed metadata write can drop them again
     */
    List<String> upload(Repository source, Map<String, String> localToRemote) {
        List<String> written = new ArrayList<>();
        int parallelism = Math.min(Math.max(1, connection.database().uploadParallelism()), localToRemote.size());
        if (parallelism <= 1 || connection.memory() != null) {
            localToRemote.forEach((context, graphIri) -> {
                writeOne(source, context, graphIri);
                written.add(graphIri);
            });
            return written;
        }
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<String>> futures = new ArrayList<>();
            localToRemote.forEach((context, graphIri) -> futures.add(pool.submit(() -> {
                writeOne(source, context, graphIri);
                return graphIri;
            })));
            for (Future<String> future : futures) {
                written.add(get(future));
            }
        } finally {
            pool.shutdown();
        }
        return written;
    }

    private static String get(Future<String> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RdfDbException("Interrupted while uploading a graph", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime
                    : new RdfDbException("Uploading a graph failed", cause);
        }
    }

    private void writeOne(Repository source, String contextName, String graphIri) {
        List<Statement> statements;
        try (RepositoryConnection conn = source.getConnection()) {
            statements = new ArrayList<>(conn.getStatements(null, null, null,
                    conn.getValueFactory().createIRI(contextName)).stream().toList());
        }
        connection.writeGraph(scenario, graphIri, statements);
        LOGGER.debug("Uploaded {} statements of {} into {} of scenario '{}'", statements.size(), contextName,
                graphIri, scenario);
    }
}
