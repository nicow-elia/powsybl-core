/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.triplestore.impl.rdf4j.sparql;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.config.InMemoryPlatformConfig;
import com.powsybl.commons.config.MapModuleConfig;
import com.powsybl.triplestore.api.TripleStore;
import com.powsybl.triplestore.api.TripleStoreFactory;
import com.powsybl.triplestore.api.TripleStoreOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code rdf4j-sparql} implementation is visible to {@code TripleStoreFactory}, and says so when it is not
 * configured.
 *
 * <p>Registering a triple store implementation that needs configuration to exist is a risk: a test that iterates
 * {@code TripleStoreFactory.allImplementations()} and calls {@code create()} on each would break. That is why the
 * failure is a clear {@link PowsyblException} naming the configuration it wants, and why this module is a
 * dependency of nothing but {@code cgmes-rdfdb}.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class TripleStoreFactoryServiceRDF4JSparqlTest {

    private static EmbeddedFuseki fuseki;
    private static FileSystem fileSystem;

    @BeforeAll
    static void startServer() {
        fuseki = EmbeddedFuseki.inMemory();
        fileSystem = com.google.common.jimfs.Jimfs.newFileSystem(com.google.common.jimfs.Configuration.unix());
    }

    @AfterAll
    static void stopServer() throws Exception {
        fuseki.close();
        fileSystem.close();
    }

    @Test
    void theImplementationIsRegistered() {
        assertThat(TripleStoreFactory.allImplementations()).contains(TripleStoreRDF4JSparql.NAME);
        assertThat(TripleStoreFactory.implementationsWorkingWithNestedGraphClauses())
                .contains(TripleStoreRDF4JSparql.NAME);
    }

    @Test
    void aConfiguredEndpointYieldsAStoreOnTheServer() {
        InMemoryPlatformConfig platformConfig = new InMemoryPlatformConfig(fileSystem);
        MapModuleConfig module = platformConfig.createModuleConfig("rdf4j-sparql");
        module.setStringProperty("url", fuseki.datasetUrl());
        module.setStringProperty("scenario", "2026-09-18");

        TripleStore store = new TripleStoreFactoryServiceRDF4JSparql()
                .create(platformConfig, new TripleStoreOptions());
        try {
            assertEquals(TripleStoreRDF4JSparql.NAME, store.getImplementationName());
            assertEquals("2026-09-18", ((TripleStoreRDF4JSparql) store).getScenario());
            assertEquals(fuseki.datasetUrl() + "/data",
                    ((TripleStoreRDF4JSparql) store).getEndpoint().graphStoreUrl().toString());
            assertTrue(store.contextNames().isEmpty());
        } finally {
            store.close();
        }
    }

    @Test
    void separateUrlsOverrideTheGuessedLayout() {
        InMemoryPlatformConfig platformConfig = new InMemoryPlatformConfig(fileSystem);
        MapModuleConfig module = platformConfig.createModuleConfig("rdf4j-sparql");
        module.setStringProperty("query-url", fuseki.datasetUrl() + "/sparql");
        module.setStringProperty("update-url", fuseki.datasetUrl() + "/update");
        module.setStringProperty("graph-store-url", fuseki.datasetUrl() + "/data");
        module.setStringProperty("scenario", "other");
        module.setStringProperty("user", "admin");
        module.setStringProperty("password", "admin");
        module.setStringProperty("connect-timeout", "PT3S");

        TripleStoreRDF4JSparql store = (TripleStoreRDF4JSparql) new TripleStoreFactoryServiceRDF4JSparql()
                .create(platformConfig, new TripleStoreOptions());
        try {
            assertEquals(fuseki.datasetUrl() + "/sparql", store.getEndpoint().queryUrl().toString());
            assertEquals("Basic YWRtaW46YWRtaW4=", store.getEndpoint().basicAuthorizationHeader());
            assertEquals(java.time.Duration.ofSeconds(3), store.getEndpoint().connectTimeout());
        } finally {
            store.close();
        }
    }

    @Test
    void withoutConfigurationTheFactorySaysSo() {
        InMemoryPlatformConfig empty = new InMemoryPlatformConfig(fileSystem);
        TripleStoreFactoryServiceRDF4JSparql factory = new TripleStoreFactoryServiceRDF4JSparql();
        PowsyblException e = assertThrows(PowsyblException.class,
                () -> factory.create(empty, new TripleStoreOptions()));
        assertThat(e.getMessage()).contains("rdf4j-sparql").contains("scenario");
    }

    @Test
    void withoutAScenarioTheFactorySaysSo() {
        InMemoryPlatformConfig platformConfig = new InMemoryPlatformConfig(fileSystem);
        platformConfig.createModuleConfig("rdf4j-sparql").setStringProperty("url", fuseki.datasetUrl());
        TripleStoreFactoryServiceRDF4JSparql factory = new TripleStoreFactoryServiceRDF4JSparql();
        PowsyblException e = assertThrows(PowsyblException.class,
                () -> factory.create(platformConfig, new TripleStoreOptions()));
        assertThat(e.getMessage()).contains("scenario");
    }

    @Test
    void aRemoteStoreCannotBeCopied() {
        InMemoryPlatformConfig platformConfig = new InMemoryPlatformConfig(fileSystem);
        MapModuleConfig module = platformConfig.createModuleConfig("rdf4j-sparql");
        module.setStringProperty("url", fuseki.datasetUrl());
        module.setStringProperty("scenario", "copy-me");
        TripleStoreFactoryServiceRDF4JSparql factory = new TripleStoreFactoryServiceRDF4JSparql();
        TripleStore store = factory.create(platformConfig, new TripleStoreOptions());
        try {
            PowsyblException e = assertThrows(PowsyblException.class, () -> factory.copy(store));
            assertThat(e.getMessage()).contains("GraphFetcher");
        } finally {
            store.close();
        }
    }
}
