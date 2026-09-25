/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The RDF databases that live in this JVM, addressed as {@code memory:<name>}.
 *
 * <p>The second backend of the abstraction, and the reason the split loading can be shown, tested and used
 * without a server: {@code memory:demo} behaves like a database &mdash; it holds several scenarios, it is shared
 * by everybody who names it, and it is emptied when the last connection to it closes.</p>
 *
 * <p>Each scenario gets its own RDF4J memory store. That is not an implementation detail but the isolation
 * guarantee: a graph-less query over one scenario cannot reach another's statements, exactly as on a server,
 * where the dataset parameters of a request do the same job.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class InMemoryRdfDatabases {

    private static final Map<String, Database> DATABASES = new HashMap<>();

    private InMemoryRdfDatabases() {
    }

    /**
     * One in-process database: its scenarios, and how many connections are open to it.
     */
    static final class Database {
        private final Map<String, Repository> scenarios = new LinkedHashMap<>();
        private Repository empty;
        private int connections;

        synchronized Repository scenario(String scenario, boolean create) {
            Repository repository = scenarios.get(scenario);
            if (repository == null && create) {
                repository = new SailRepository(new MemoryStore());
                repository.init();
                scenarios.put(scenario, repository);
            }
            return repository;
        }

        synchronized List<String> scenarios() {
            // Only scenarios that actually hold something: a caller that asked to read a scenario that is not
            // there must not make it appear in the catalogue.
            List<String> names = new ArrayList<>();
            scenarios.forEach((name, repository) -> {
                try (RepositoryConnection conn = repository.getConnection()) {
                    if (!conn.isEmpty()) {
                        names.add(name);
                    }
                }
            });
            names.sort(String::compareTo);
            return names;
        }

        /**
         * An empty, shared repository standing in for a scenario that does not exist.
         *
         * <p>Reading a scenario that was never written is not an error &mdash; it is empty &mdash; and answering
         * with a store on this repository keeps that true without creating the scenario.</p>
         */
        synchronized Repository emptyRepository() {
            if (empty == null) {
                empty = new SailRepository(new MemoryStore());
                empty.init();
            }
            return empty;
        }

        synchronized void drop(String scenario) {
            Repository repository = scenarios.remove(scenario);
            if (repository != null) {
                repository.shutDown();
            }
        }

        private synchronized void shutDown() {
            scenarios.values().forEach(Repository::shutDown);
            scenarios.clear();
            if (empty != null) {
                empty.shutDown();
                empty = null;
            }
        }
    }

    /**
     * Open (and if needed create) the database of that name, counting one more connection.
     *
     * @param name the database name
     * @return the database
     */
    static synchronized Database acquire(String name) {
        Database database = DATABASES.computeIfAbsent(Objects.requireNonNull(name), n -> new Database());
        database.connections++;
        return database;
    }

    /**
     * Give a connection back. The database is dropped when it was the last one.
     *
     * @param name the database name
     */
    static synchronized void release(String name) {
        Database database = DATABASES.get(name);
        if (database == null) {
            return;
        }
        database.connections--;
        if (database.connections <= 0) {
            DATABASES.remove(name);
            database.shutDown();
        }
    }

}
