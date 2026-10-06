/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.commons.datasource.DataSource;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import org.eclipse.rdf4j.model.Value;
import org.junit.jupiter.params.provider.Arguments;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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

    /** The CGMES import parameters every test loads with: one network per CGM, no subnetworks. */
    static Properties params() {
        Properties p = new Properties();
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        return p;
    }

    /**
     * How many solutions a graph pattern has inside one named graph of a scenario.
     *
     * @param db       the open connection
     * @param scenario the scenario to ask
     * @param graph    the IRI of the named graph
     * @param pattern  the graph pattern, with the {@code pdb:} prefix available
     * @return the count, 0 when the database answers no row
     */
    static long count(RdfDbConnection db, String scenario, String graph, String pattern) {
        List<Map<String, Value>> rows = db.sparql(scenario).select(RdfDbVocabulary.PREFIXES
                + "SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + graph + "> { " + pattern + " } }");
        return rows.isEmpty() ? 0 : Long.parseLong(rows.get(0).get("n").stringValue());
    }

    /** The modelling authority set of the MicroGrid BE files. */
    static final String BE = "http://elia.be/CGMES/2.4.15";

    /** The modelling authority set of the MicroGrid NL files. */
    static final String NL = "http://tennet.nl/CGMES/2.4.15";

    /** The modelling authority set of the CGMES 3 Svedala files, and of every fixture derived from them. */
    static final String SVK = "http://www.svk.se/ARISTO";

    /** The scenario time of the MicroGrid base case, which is the base timestamp of a tree it is the root of. */
    static final Instant BASE = Instant.parse("2014-06-01T10:30:00Z");

    /** The MicroGrid BE base case, the fixture most tests of this package store. */
    static ReadOnlyDataSource microGridBe() {
        return CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
    }

    /** The MicroGrid NL base case: another modelling authority of the same day, with the same boundary. */
    static ReadOnlyDataSource microGridNl() {
        return CgmesConformity1Catalog.microGridBaseCaseNL().dataSource();
    }

    /**
     * {@code CGMES_Full.zip} of pypowsybl: a realistic IGM whose profiles name different modelling authorities
     * (EQ and TP {@code powsybl.org}, SSH {@link #CGMES_FULL_SSH}, SV {@code http://tennet.nl/CGMES}, the merging
     * agent's), with the ENTSO-E EQ boundary inside.
     */
    static ReadOnlyDataSource cgmesFull() {
        try {
            return DataSource.fromPath(Path.of(Backends.class.getResource("CGMES_Full.zip").toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The modelling authority set the SSH file of {@link #cgmesFull()} states. */
    static final String CGMES_FULL_SSH = "http://elia.be/CGMES";

    /** A version of the MicroGrid BE tree of a scenario, at its base timestamp. */
    static SnapshotRef ref(String scenario, int version) {
        return SnapshotRef.of(scenario, BE, (Instant) null, version);
    }

    /** A version of the MicroGrid BE tree of a scenario, at a timestamp. */
    static SnapshotRef ref(String scenario, int version, Instant timestamp) {
        return SnapshotRef.of(scenario, BE, timestamp, version);
    }

    /** A version of the Svedala tree of a scenario, at a timestamp, or at the base timestamp for {@code null}. */
    static SnapshotRef svk(String scenario, int version, Instant timestamp) {
        return SnapshotRef.of(scenario, SVK, timestamp, version);
    }

    /** An instant written as ISO text, for the readability of a test. */
    static Instant at(String iso) {
        return Instant.parse(iso);
    }
}
