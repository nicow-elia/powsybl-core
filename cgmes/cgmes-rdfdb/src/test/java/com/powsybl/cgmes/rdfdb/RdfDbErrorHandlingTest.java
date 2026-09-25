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
import com.powsybl.cgmes.model.CgmesModelException;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import com.powsybl.triplestore.api.TripleStoreException;
import com.powsybl.triplestore.api.TripleStoreOptions;
import com.powsybl.triplestore.impl.rdf4j.TripleStoreRDF4J;
import com.powsybl.triplestore.impl.rdf4j.sparql.SparqlEndpoint;
import com.powsybl.triplestore.impl.rdf4j.sparql.TripleStoreRDF4JSparql;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What happens when the database is not there, is empty, or cannot do what the fast path needs.
 *
 * <p>Failures of a database are ordinary operating conditions, so the messages have to name the thing that is
 * wrong &mdash; the URL, the scenario, the subsets &mdash; and a missing capability has to degrade rather than
 * break. The last test here is the important one: a server without a Graph Store Protocol endpoint is served
 * through plain SPARQL, slower but with the same result.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbErrorHandlingTest {

    private static EmbeddedFuseki fuseki;

    @BeforeAll
    static void startServer() {
        fuseki = EmbeddedFuseki.inMemory();
    }

    @AfterAll
    static void stopServer() {
        fuseki.close();
    }

    private static Properties params() {
        Properties p = new Properties();
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        return p;
    }

    @Test
    void anUnreachableEndpointIsReportedWithItsUrl() {
        RdfDatabase database = RdfDatabase.fuseki("http://127.0.0.1:1/ds")
                .withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2));
        long start = System.nanoTime();
        RdfDbException e = assertThrows(RdfDbException.class, () -> RdfDbConnection.open(database));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(e.getMessage()).contains("http://127.0.0.1:1/ds/query");
        assertThat(millis).as("the connection attempt must give up quickly").isLessThan(30_000L);
    }

    @Test
    void anEmptyScenarioIsReportedWithTheScenariosThatDoExist() {
        Properties p = params();
        try (RdfDbConnection db = RdfDbConnection.open(fuseki.database())) {
            db.loadCgmes("exists", CgmesConformity1Catalog.miniBusBranch().dataSource(), null, p, ReportNode.NO_OP);
            RdfDbException e = assertThrows(RdfDbException.class,
                    () -> RdfDbNetworkLoader.load(db, "not-there", NetworkFactory.findDefault(), p, ReportNode.NO_OP));
            assertThat(e.getMessage()).contains("not-there").contains("exists");
        }
    }

    @Test
    void anEmptySubsetSelectionIsReportedWithTheSubsets() {
        Properties p = params();
        try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.inMemory("empty-subsets"))) {
            db.loadCgmes("s", CgmesConformity1Catalog.miniBusBranch().dataSource(), null, p, ReportNode.NO_OP);
            RdfDbLoadOptions options = new RdfDbLoadOptions()
                    .setSubsets(EnumSet.of(com.powsybl.cgmes.model.CgmesSubset.DYNAMIC));
            RdfDbException e = assertThrows(RdfDbException.class,
                    () -> RdfDbNetworkLoader.load(db, "s", options, NetworkFactory.findDefault(), p, ReportNode.NO_OP));
            assertThat(e.getMessage()).contains("DYNAMIC");
        }
    }

    @Test
    void aBlankScenarioIsRefused() {
        Properties p = params();
        try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.inMemory("blank-scenario"))) {
            ReadOnlyDataSource ds = CgmesConformity1Catalog.miniBusBranch().dataSource();
            assertThrows(PowsyblException.class, () -> db.loadCgmes("  ", ds, null, p, ReportNode.NO_OP));
            assertThrows(NullPointerException.class, () -> db.loadCgmes(null, ds, null, p, ReportNode.NO_OP));
            assertThrows(PowsyblException.class, () -> db.contextNames("a/b"));
        }
    }

    @Test
    void usingAClosedConnectionIsReported() {
        RdfDbConnection db = RdfDbConnection.open(RdfDatabase.inMemory("closed"));
        db.close();
        db.close(); // idempotent
        RdfDbException e = assertThrows(RdfDbException.class, db::scenarios);
        assertThat(e.getMessage()).contains("closed");
    }

    @Test
    void withoutAGraphStoreEndpointTheSparqlFallbackGivesTheSameNetwork() {
        Properties p = params();
        ReadOnlyDataSource ds = CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
        Network fromFiles = Network.read(ds, p);

        SparqlEndpoint withoutGsp = new SparqlEndpoint(
                java.net.URI.create(fuseki.datasetUrl() + "/query"),
                java.net.URI.create(fuseki.datasetUrl() + "/update"),
                null, null, null, null, null, null);
        try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.of(withoutGsp))) {
            db.loadCgmes("no-gsp", ds, null, p, ReportNode.NO_OP);
            Network fromDb = RdfDbNetworkLoader.load(db, "no-gsp", NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            Networks.assertSameNetwork(fromFiles, fromDb);
        }
    }

    @Test
    void aRefusedGraphStoreWriteNamesTheMethodTheGraphAndTheStatus() {
        Properties p = params();
        try (EmbeddedFuseki readOnly = EmbeddedFuseki.readOnly();
             RdfDbConnection db = RdfDbConnection.open(readOnly.database())) {
            ReadOnlyDataSource ds = CgmesConformity1Catalog.miniBusBranch().dataSource();
            // The loader names the file it could not read; the client's message is the cause
            CgmesModelException e = assertThrows(CgmesModelException.class,
                    () -> db.loadCgmes("read-only", ds, null, p, ReportNode.NO_OP));
            Throwable cause = e.getCause();
            while (cause != null && !(cause instanceof TripleStoreException)) {
                cause = cause.getCause();
            }
            assertThat(cause).isInstanceOf(TripleStoreException.class);
            assertThat(cause.getMessage()).contains("PUT").contains("contexts:read-only/")
                    .contains("answered HTTP");
        }
    }

    @Test
    void fetchingAGraphThatIsNotThereNamesTheGraphAndTheScenario() {
        Properties p = params();
        try (RdfDbConnection db = RdfDbConnection.open(fuseki.database())) {
            db.loadCgmes("vanishing", CgmesConformity1Catalog.miniBusBranch().dataSource(), null, p, ReportNode.NO_OP);
            List<String> names = db.contextNames("vanishing");
            TripleStoreRDF4JSparql store =
                    (TripleStoreRDF4JSparql) db.scenarioStore("vanishing", new TripleStoreOptions());
            // Ask the fetcher for a graph the database does not have
            List<String> asked = new ArrayList<>(names);
            asked.add("contexts:not-there_EQ.xml");
            TripleStoreRDF4J local = new TripleStoreRDF4J(
                    new org.eclipse.rdf4j.repository.sail.SailRepository(
                            new org.eclipse.rdf4j.sail.memory.MemoryStore()), new TripleStoreOptions());
            try {
                RdfDbException e = assertThrows(RdfDbException.class,
                        () -> new GraphFetcher(db, "vanishing").fetchInto(local, asked));
                assertThat(e.getMessage()).contains("not-there_EQ.xml").contains("vanishing");
            } finally {
                local.close();
            }
            assertThat(store.contextNames()).isNotEmpty();
        }
    }

    @Test
    void credentialsBecomeABasicAuthorizationHeader() {
        // Fuseki without a security configuration cannot answer 401, so what is checked here is that the
        // credentials reach the wire in the form a server expects.
        RdfDatabase database = fuseki.database().withCredentials("admin", "admin");
        assertEquals("Basic YWRtaW46YWRtaW4=", database.endpoint().basicAuthorizationHeader());
        try (RdfDbConnection db = RdfDbConnection.open(database)) {
            assertThat(db.scenarios()).isNotNull();
        }
    }
}
