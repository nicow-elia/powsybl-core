/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.triplestore.impl.rdf4j.test;

import com.powsybl.triplestore.api.PropertyBags;
import com.powsybl.triplestore.api.TripleStoreOptions;
import com.powsybl.triplestore.impl.rdf4j.TripleStoreRDF4J;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repository of a {@link TripleStoreRDF4J} can be handed in from outside.
 *
 * <p>That constructor is the seam the RDF-database loading of CGMES data is built on: the very same triple store
 * code runs on an in-memory sail, on a shared repository, or on a remote SPARQL endpoint. This test pins the two
 * properties the seam must have: an injected repository behaves exactly like the default one, and the store owns
 * it &mdash; closing the store shuts the repository down.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class TripleStoreRDF4JInjectedRepositoryTest {

    private static final String BASE = "http://example.com/model";

    private static final String RDF_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:ex="http://example.com/ns#">
              <ex:Thing rdf:ID="_a">
                <ex:Thing.name>first</ex:Thing.name>
              </ex:Thing>
              <ex:Thing rdf:ID="_b">
                <ex:Thing.name>second</ex:Thing.name>
              </ex:Thing>
            </rdf:RDF>
            """;

    private static InputStream content() {
        return new ByteArrayInputStream(RDF_XML.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void injectedRepositoryReadsQueriesAndCloses() {
        Repository repository = new SailRepository(new MemoryStore());
        TripleStoreRDF4J store = new TripleStoreRDF4J(repository, new TripleStoreOptions());

        assertTrue(repository.isInitialized());
        assertSame(repository, store.getRepository());

        store.read(content(), BASE, "test_EQ.xml");
        assertEquals(Set.of("contexts:test_EQ.xml"), store.contextNames());

        store.defineQueryPrefix("ex", "http://example.com/ns#");
        PropertyBags things = store.query("""
                SELECT ?thing ?name WHERE { GRAPH ?graph {
                    ?thing a ex:Thing ; ex:Thing.name ?name .
                }}
                """);
        assertEquals(2, things.size());
        assertEquals(Set.of("first", "second"),
                things.stream().map(b -> b.get("name")).collect(Collectors.toSet()));

        store.close();
        assertFalse(repository.isInitialized());
    }

    @Test
    void injectedRepositoryIsEquivalentToTheDefaultOne() {
        TripleStoreRDF4J defaultStore = new TripleStoreRDF4J();
        TripleStoreRDF4J injectedStore = new TripleStoreRDF4J(new SailRepository(new MemoryStore()), new TripleStoreOptions());
        try {
            defaultStore.read(content(), BASE, "test_EQ.xml");
            injectedStore.read(content(), BASE, "test_EQ.xml");

            String query = "SELECT ?s ?p ?o WHERE { GRAPH ?graph { ?s ?p ?o }}";
            assertEquals(defaultStore.query(query).size(), injectedStore.query(query).size());
            assertEquals(defaultStore.contextNames(), injectedStore.contextNames());
            assertEquals(defaultStore.getNamespaces().size(), injectedStore.getNamespaces().size());
        } finally {
            defaultStore.close();
            injectedStore.close();
        }
    }

    @Test
    void anAlreadyInitialisedRepositoryIsNotInitialisedTwice() {
        Repository repository = new SailRepository(new MemoryStore());
        repository.init();
        TripleStoreRDF4J store = new TripleStoreRDF4J(repository, new TripleStoreOptions());
        store.read(content(), BASE, "test_EQ.xml");
        assertEquals(1, store.contextNames().size());
        store.close();
        assertFalse(repository.isInitialized());
    }
}
