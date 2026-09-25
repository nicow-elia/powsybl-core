/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.triplestore.impl.rdf4j.sparql;

import com.powsybl.commons.PowsyblException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three endpoint layouts that matter, and the guesswork for a URL a user typed.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class SparqlEndpointTest {

    static Stream<Arguments> layouts() {
        return Stream.of(
                Arguments.of("fuseki",
                        SparqlEndpoint.fuseki("http://localhost:3030/ds"),
                        "http://localhost:3030/ds/query",
                        "http://localhost:3030/ds/update",
                        "http://localhost:3030/ds/data"),
                Arguments.of("fuseki with trailing slash",
                        SparqlEndpoint.fuseki("http://localhost:3030/ds/"),
                        "http://localhost:3030/ds/query",
                        "http://localhost:3030/ds/update",
                        "http://localhost:3030/ds/data"),
                Arguments.of("rdf4j server",
                        SparqlEndpoint.rdf4jServer("http://localhost:8080/rdf4j-server", "cgmes"),
                        "http://localhost:8080/rdf4j-server/repositories/cgmes",
                        "http://localhost:8080/rdf4j-server/repositories/cgmes/statements",
                        "http://localhost:8080/rdf4j-server/repositories/cgmes/rdf-graphs/service"),
                Arguments.of("graphdb",
                        SparqlEndpoint.graphDb("http://localhost:7200", "cgmes"),
                        "http://localhost:7200/repositories/cgmes",
                        "http://localhost:7200/repositories/cgmes/statements",
                        "http://localhost:7200/repositories/cgmes/rdf-graphs/service"),
                Arguments.of("parsed fuseki dataset",
                        SparqlEndpoint.parse("http://localhost:3030/ds"),
                        "http://localhost:3030/ds/query",
                        "http://localhost:3030/ds/update",
                        "http://localhost:3030/ds/data"),
                Arguments.of("parsed rdf4j repository",
                        SparqlEndpoint.parse("http://localhost:8080/rdf4j-server/repositories/cgmes"),
                        "http://localhost:8080/rdf4j-server/repositories/cgmes",
                        "http://localhost:8080/rdf4j-server/repositories/cgmes/statements",
                        "http://localhost:8080/rdf4j-server/repositories/cgmes/rdf-graphs/service"),
                Arguments.of("parsed rdf4j repository with a tail",
                        SparqlEndpoint.parse("http://localhost:8080/rdf4j-server/repositories/cgmes/statements"),
                        "http://localhost:8080/rdf4j-server/repositories/cgmes",
                        "http://localhost:8080/rdf4j-server/repositories/cgmes/statements",
                        "http://localhost:8080/rdf4j-server/repositories/cgmes/rdf-graphs/service"),
                Arguments.of("parsed query url",
                        SparqlEndpoint.parse("http://example.com/store/query"),
                        "http://example.com/store/query",
                        "http://example.com/store/update",
                        "http://example.com/store/data"),
                Arguments.of("parsed sparql url",
                        SparqlEndpoint.parse("http://example.com/store/sparql"),
                        "http://example.com/store/sparql",
                        "http://example.com/store/update",
                        "http://example.com/store/data"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    void layoutsAreResolvedAsDocumented(String name, SparqlEndpoint endpoint, String query, String update, String graphStore) {
        assertEquals(query, endpoint.queryUrl().toString());
        assertEquals(update, endpoint.updateUrl().toString());
        assertEquals(graphStore, endpoint.graphStoreUrl().toString());
        assertTrue(endpoint.writable());
        assertEquals(SparqlEndpoint.DEFAULT_CONNECT_TIMEOUT, endpoint.connectTimeout());
        assertEquals(SparqlEndpoint.DEFAULT_READ_TIMEOUT, endpoint.readTimeout());
    }

    @Test
    void credentialsBecomeABasicAuthorizationHeader() {
        SparqlEndpoint plain = SparqlEndpoint.fuseki("http://localhost:3030/ds");
        assertNull(plain.basicAuthorizationHeader());

        SparqlEndpoint withUser = plain.withCredentials("admin", "admin");
        // "admin:admin" in base64
        assertEquals("Basic YWRtaW46YWRtaW4=", withUser.basicAuthorizationHeader());
        assertEquals("Basic YWRtaW46", plain.withCredentials("admin", null).basicAuthorizationHeader());
    }

    @Test
    void headersAndTimeoutsAreCarriedAlong() {
        SparqlEndpoint endpoint = SparqlEndpoint.fuseki("http://localhost:3030/ds")
                .withHeader("X-Api-Key", "secret")
                .withTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertEquals("secret", endpoint.headers().get("X-Api-Key"));
        assertEquals(Duration.ofSeconds(1), endpoint.connectTimeout());
        assertEquals(Duration.ofSeconds(2), endpoint.readTimeout());
        // Immutable
        assertThrows(UnsupportedOperationException.class, () -> endpoint.headers().put("a", "b"));
    }

    @Test
    void anEmptyUrlIsRejected() {
        assertThrows(PowsyblException.class, () -> SparqlEndpoint.fuseki(""));
        assertThrows(PowsyblException.class, () -> SparqlEndpoint.parse("  "));
        assertThrows(NullPointerException.class, () -> SparqlEndpoint.parse(null));
    }

    @Test
    void aReadOnlyEndpointIsNotWritable() {
        SparqlEndpoint readOnly = new SparqlEndpoint(java.net.URI.create("http://example.com/query"),
                null, null, null, null, null, null, null);
        assertEquals(SparqlEndpoint.DEFAULT_CONNECT_TIMEOUT, readOnly.connectTimeout());
        assertTrue(readOnly.headers().isEmpty());
        assertEquals(false, readOnly.writable());
    }
}
