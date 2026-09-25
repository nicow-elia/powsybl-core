/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.triplestore.impl.rdf4j.sparql;

import com.google.auto.service.AutoService;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.config.ModuleConfig;
import com.powsybl.commons.config.PlatformConfig;
import com.powsybl.triplestore.api.TripleStore;
import com.powsybl.triplestore.api.TripleStoreFactoryService;
import com.powsybl.triplestore.api.TripleStoreOptions;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Makes {@code rdf4j-sparql} a triple store implementation the CGMES importer can be pointed at by name.
 *
 * <p>With {@code iidm.import.cgmes.powsybl-triplestore=rdf4j-sparql}, a plain {@code Network.read} of CGMES files
 * writes the statements into a configured database instead of into memory, and converts them from there. Where the
 * database is and which scenario to use cannot travel in the implementation name, so they come from the platform
 * configuration module {@code rdf4j-sparql}:</p>
 *
 * <pre>
 * rdf4j-sparql:
 *     url: http://localhost:3030/ds      # or query-url / update-url / graph-store-url separately
 *     scenario: 2026-09-18
 *     user: admin
 *     password: secret
 *     connect-timeout: PT10S
 *     read-timeout: PT5M
 * </pre>
 *
 * <p>Without that module the factory fails with a message saying so, rather than silently producing a store that
 * points nowhere. Programmatic callers &mdash; {@code cgmes-rdfdb} &mdash; construct
 * {@link TripleStoreRDF4JSparql} directly and never come through here.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
@AutoService(TripleStoreFactoryService.class)
public class TripleStoreFactoryServiceRDF4JSparql implements TripleStoreFactoryService {

    /** The name of the platform configuration module the endpoint is read from. */
    public static final String MODULE_NAME = "rdf4j-sparql";

    private static final String MISSING_CONFIG = "Triple store '" + TripleStoreRDF4JSparql.NAME
            + "' needs a configured endpoint (platform configuration module '" + MODULE_NAME
            + "', keys url or query-url, and scenario). Use com.powsybl.cgmes.rdfdb to address a database"
            + " programmatically.";

    @Override
    public TripleStore create() {
        return create(new TripleStoreOptions());
    }

    @Override
    public TripleStore create(TripleStoreOptions options) {
        return create(PlatformConfig.defaultConfig(), options);
    }

    /**
     * Create a store from an explicit platform configuration.
     *
     * @param platformConfig the configuration to read the {@code rdf4j-sparql} module from
     * @param options        the triple store configuration options
     * @return the store
     * @throws PowsyblException if the module or one of its required keys is missing
     */
    public TripleStore create(PlatformConfig platformConfig, TripleStoreOptions options) {
        ModuleConfig config = platformConfig.getOptionalModuleConfig(MODULE_NAME)
                .orElseThrow(() -> new PowsyblException(MISSING_CONFIG));
        SparqlEndpoint endpoint = endpoint(config);
        String scenario = config.getOptionalStringProperty("scenario")
                .orElseThrow(() -> new PowsyblException(MISSING_CONFIG));
        return new TripleStoreRDF4JSparql(endpoint, scenario, options);
    }

    private static SparqlEndpoint endpoint(ModuleConfig config) {
        Optional<String> url = config.getOptionalStringProperty("url");
        Optional<String> queryUrl = config.getOptionalStringProperty("query-url");
        if (url.isEmpty() && queryUrl.isEmpty()) {
            throw new PowsyblException(MISSING_CONFIG);
        }
        SparqlEndpoint guessed = SparqlEndpoint.parse(url.orElseGet(queryUrl::orElseThrow));
        Optional<String> updateUrl = config.getOptionalStringProperty("update-url");
        Optional<String> graphStoreUrl = config.getOptionalStringProperty("graph-store-url");
        SparqlEndpoint endpoint = new SparqlEndpoint(
                URI.create(queryUrl.orElseGet(() -> guessed.queryUrl().toString())),
                updateUrl.map(URI::create).orElse(guessed.updateUrl()),
                graphStoreUrl.map(URI::create).orElse(guessed.graphStoreUrl()),
                null, null, Map.of(), guessed.connectTimeout(), guessed.readTimeout());
        Optional<String> user = config.getOptionalStringProperty("user");
        if (user.isPresent()) {
            endpoint = endpoint.withCredentials(user.get(),
                    config.getOptionalStringProperty("password").orElse(null));
        }
        Duration connectTimeout = config.getOptionalStringProperty("connect-timeout")
                .map(Duration::parse).orElse(null);
        Duration readTimeout = config.getOptionalStringProperty("read-timeout")
                .map(Duration::parse).orElse(null);
        if (connectTimeout != null || readTimeout != null) {
            endpoint = endpoint.withTimeouts(
                    connectTimeout == null ? endpoint.connectTimeout() : connectTimeout,
                    readTimeout == null ? endpoint.readTimeout() : readTimeout);
        }
        return endpoint;
    }

    @Override
    public TripleStore copy(TripleStore source) {
        throw new PowsyblException("A remote triple store cannot be copied;"
                + " fetch its graphs with com.powsybl.cgmes.rdfdb.GraphFetcher");
    }

    @Override
    public String getImplementationName() {
        return TripleStoreRDF4JSparql.NAME;
    }

    @Override
    public boolean isWorkingWithNestedGraphClauses() {
        return true;
    }
}
