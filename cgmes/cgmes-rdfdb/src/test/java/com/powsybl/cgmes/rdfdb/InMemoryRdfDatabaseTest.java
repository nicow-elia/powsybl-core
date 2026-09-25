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
import com.powsybl.commons.report.ReportNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-process backend, {@code memory:<name>}, behaves like a database.
 *
 * <p>It exists so that the split loading can be used, demonstrated and tested without a server: it is shared by
 * name, it holds several scenarios side by side, and it disappears when nobody is connected to it any more. The
 * last property is what keeps one test from seeing another test's data.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class InMemoryRdfDatabaseTest {

    private static Properties params() {
        Properties p = new Properties();
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        return p;
    }

    @Test
    void twoConnectionsToTheSameNameSeeTheSameData() {
        Properties p = params();
        try (RdfDbConnection first = RdfDbConnection.open(RdfDatabase.inMemory("shared"))) {
            first.loadCgmes("s", CgmesConformity1Catalog.miniBusBranch().dataSource(), null, p, ReportNode.NO_OP);
            try (RdfDbConnection second = RdfDbConnection.open(RdfDatabase.parse("memory:shared"))) {
                assertEquals(first.scenarios(), second.scenarios());
                assertEquals(first.contextNames("s"), second.contextNames("s"));
            }
            // Closing the second connection must not have dropped the database
            assertThat(first.contextNames("s")).isNotEmpty();
        }
    }

    @Test
    void theDatabaseDisappearsWhenTheLastConnectionCloses() {
        Properties p = params();
        try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.inMemory("transient"))) {
            db.loadCgmes("s", CgmesConformity1Catalog.miniBusBranch().dataSource(), null, p, ReportNode.NO_OP);
            assertThat(db.contextNames("s")).isNotEmpty();
        }
        try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.inMemory("transient"))) {
            assertThat(db.scenarios()).isEmpty();
            assertThat(db.contextNames("s")).isEmpty();
        }
    }

    @Test
    void theMemorySchemeIsRecognisedInAUrl() {
        RdfDatabase database = RdfDatabase.parse("memory:demo");
        assertTrue(database.isInMemory());
        assertEquals("demo", database.memoryName());
        assertNull(database.endpoint());
        assertEquals("memory:demo", database.toString());
        assertThrows(RdfDbException.class, () -> RdfDatabase.inMemory(" "));
        assertThrows(RdfDbException.class, () -> database.withCredentials("a", "b"));
    }

    @Test
    void optionsComeFromStringParameters() {
        RdfDatabase memory = RdfDatabase.fromParameters("memory:demo",
                Map.of("query_mode", "remote", "fetch_parallelism", "8", "cache", "true"));
        assertTrue(memory.isInMemory());
        assertEquals(RdfDatabase.QueryMode.REMOTE, memory.queryMode());
        assertEquals(8, memory.fetchParallelism());
        assertThat(memory.cache()).isNotNull();

        RdfDatabase remote = RdfDatabase.fromParameters("http://db.example.com:3030/ds",
                Map.of("user", "u", "password", "p", "header.X-Token", "t",
                        "graph_store_url", "http://db.example.com:3030/ds/data",
                        "upload_parallelism", "2", "gzip", "false",
                        "connect_timeout_ms", "1500", "read_timeout_ms", "60000"));
        assertFalse(remote.isInMemory());
        assertEquals("Basic dTpw", remote.endpoint().basicAuthorizationHeader());
        assertEquals("t", remote.endpoint().headers().get("X-Token"));
        assertEquals(2, remote.uploadParallelism());
        assertFalse(remote.gzip());
        assertEquals(Duration.ofMillis(1500), remote.endpoint().connectTimeout());
        assertEquals(Duration.ofSeconds(60), remote.endpoint().readTimeout());

        assertThrows(RdfDbException.class,
                () -> RdfDatabase.fromParameters("memory:x", Map.of("nonsense", "1")));
    }

    @Test
    void gzipIsOffForALoopbackHostAndOnForARemoteOne() {
        assertFalse(RdfDatabase.fuseki("http://localhost:3030/ds").gzip());
        assertFalse(RdfDatabase.fuseki("http://127.0.0.1:3030/ds").gzip());
        assertTrue(RdfDatabase.fuseki("http://db.example.com:3030/ds").gzip());
        assertTrue(RdfDatabase.fuseki("http://localhost:3030/ds").withGzip(true).gzip());
    }

    @Test
    void parallelismMustBePositive() {
        RdfDatabase database = RdfDatabase.inMemory("x");
        assertThrows(RdfDbException.class, () -> database.withFetchParallelism(0));
        assertThrows(RdfDbException.class, () -> database.withUploadParallelism(-1));
    }
}
