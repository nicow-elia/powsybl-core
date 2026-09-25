/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import org.junit.jupiter.params.provider.Arguments;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The two backends every test of the versioning layer runs on.
 *
 * <p>Everything this work package writes is SPARQL, and SPARQL is where two implementations diverge quietly: an
 * aggregate, a property path, a guarded {@code INSERT … WHERE}, the transaction boundary of a multi-operation
 * update. A test that only ran against the in-process RDF4J store would say nothing about a real server, and one
 * that only ran against the server would let the in-process backend rot. So every test class of this package is
 * parameterised over both.</p>
 *
 * <p>One embedded Fuseki serves the whole test run: starting a server costs about a second, and the tests keep
 * themselves apart by scenario, which is exactly what the scenario key is for.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class Backends {

    /** The name of the server backend. */
    static final String FUSEKI = "fuseki";

    /** The name of the in-process backend. */
    static final String MEMORY = "memory";

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static EmbeddedFuseki fuseki;

    private Backends() {
    }

    /** The two backend names, as a {@code @MethodSource}. */
    static Stream<Arguments> backends() {
        return Stream.of(Arguments.of(FUSEKI), Arguments.of(MEMORY));
    }

    /** The embedded server, started on first use and stopped when the JVM ends. */
    static synchronized EmbeddedFuseki fuseki() {
        if (fuseki == null) {
            fuseki = EmbeddedFuseki.inMemory();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> fuseki.close(), "fuseki-stop"));
        }
        return fuseki;
    }

    /**
     * A database of the given backend.
     *
     * <p>The in-process backend is given a name of its own per call, because two tests sharing one in-process
     * database would share its scenarios; the server is shared and the tests keep apart by scenario.</p>
     *
     * @param backend {@link #FUSEKI} or {@link #MEMORY}
     * @param name    what to call the in-process database
     * @return the database
     */
    static RdfDatabase database(String backend, String name) {
        return MEMORY.equals(backend)
                ? RdfDatabase.inMemory(name + "-" + COUNTER.incrementAndGet())
                : fuseki().database();
    }
}
