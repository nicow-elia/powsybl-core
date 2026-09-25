/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.triplestore.impl.rdf4j.sparql;

import com.powsybl.cgmes.conformity.Cgmes3Catalog;
import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.triplestore.CgmesTripleStoreLoader;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.triplestore.api.PropertyBag;
import com.powsybl.triplestore.api.PropertyBags;
import com.powsybl.triplestore.api.TripleStore;
import com.powsybl.triplestore.api.TripleStoreFactory;
import com.powsybl.triplestore.api.TripleStoreOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A triple store on a SPARQL endpoint must answer exactly what the local in-memory store answers.
 *
 * <p>Every test here loads the same CGMES files into an embedded Fuseki and into a local store and compares. That
 * is the only assertion that matters: the CGMES conversion above the store is a large body of queries written
 * against the local semantics, and it is reused unchanged on the database path.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class TripleStoreRDF4JSparqlTest {

    private static final String SCENARIO = "2026-09-18";

    private static EmbeddedFuseki fuseki;

    @BeforeAll
    static void startServer() {
        fuseki = EmbeddedFuseki.inMemory();
    }

    @AfterAll
    static void stopServer() {
        fuseki.close();
    }

    private static TripleStoreRDF4JSparql remote(String scenario) {
        return new TripleStoreRDF4JSparql(fuseki.endpoint(), scenario, new TripleStoreOptions());
    }

    private static TripleStore local() {
        return TripleStoreFactory.create(new TripleStoreOptions());
    }

    private static void loadInto(TripleStore store, ReadOnlyDataSource ds, int parallelism) {
        CgmesTripleStoreLoader.load(ds, null, store, parallelism, ReportNode.NO_OP);
    }

    private static Set<String> asRows(PropertyBags bags) {
        return bags.stream()
                .map(bag -> bag.entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey())
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining("|")))
                .collect(Collectors.toSet());
    }

    @Test
    void contextsQueriesAndCountsMatchTheLocalStore() {
        ReadOnlyDataSource ds = CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
        TripleStoreRDF4JSparql remote = remote("micro-be");
        TripleStore localStore = local();
        try {
            loadInto(remote, ds, 4);
            loadInto(localStore, ds, 1);

            assertEquals(localStore.contextNames(), remote.contextNames());

            for (String prefix : List.of("cim")) {
                remote.defineQueryPrefix(prefix, CgmesNamespace.CIM_16_NAMESPACE);
                localStore.defineQueryPrefix(prefix, CgmesNamespace.CIM_16_NAMESPACE);
            }
            String energyConsumers = """
                    SELECT ?consumer ?name ?graph WHERE { GRAPH ?graph {
                        ?consumer a cim:EnergyConsumer ; cim:IdentifiedObject.name ?name .
                    }}
                    """;
            PropertyBags localBags = localStore.query(energyConsumers);
            PropertyBags remoteBags = remote.query(energyConsumers);
            assertFalse(localBags.isEmpty());
            assertEquals(asRows(localBags), asRows(remoteBags));

            // The graph binding a query reports must be the plain file name, whatever the scenario is called
            assertThat(remoteBags.stream().map(b -> b.get("graph")).collect(Collectors.toSet()))
                    .allSatisfy(g -> assertThat(g).startsWith("contexts:").doesNotContain("micro-be/"));

            // print() reports the same graph sizes as the local store
            assertEquals(countsOf(localStore), countsOf(remote));
        } finally {
            remote.close();
            localStore.close();
        }
    }

    private static java.util.Map<String, String> countsOf(TripleStore store) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        store.print(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8).lines()
                .filter(l -> l.contains(" : "))
                .map(l -> l.trim().split(" : "))
                .collect(Collectors.toMap(a -> a[0], a -> a[1]));
    }

    @Test
    void modelHeaderQueriesMatchTheLocalStore() {
        ReadOnlyDataSource ds = CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
        TripleStoreRDF4JSparql remote = remote("micro-be-headers");
        TripleStore localStore = local();
        try {
            loadInto(remote, ds, 4);
            loadInto(localStore, ds, 1);
            String md = "http://iec.ch/TC57/61970-552/ModelDescription/1#";
            remote.defineQueryPrefix("md", md);
            localStore.defineQueryPrefix("md", md);
            String modelProfiles = """
                    SELECT ?model ?profile WHERE { GRAPH ?graph {
                        ?model a md:FullModel ; md:Model.profile ?profile .
                    }}
                    """;
            assertEquals(asRows(localStore.query(modelProfiles)), asRows(remote.query(modelProfiles)));
        } finally {
            remote.close();
            localStore.close();
        }
    }

    @Test
    void clearRemovesOneGraphOnly() {
        ReadOnlyDataSource ds = CgmesConformity1Catalog.miniBusBranch().dataSource();
        TripleStoreRDF4JSparql remote = remote("mini-clear");
        try {
            loadInto(remote, ds, 2);
            Set<String> before = remote.contextNames();
            assertThat(before).hasSizeGreaterThan(1);
            String victim = before.iterator().next();
            remote.clear(victim);
            assertThat(remote.contextNames()).hasSize(before.size() - 1).doesNotContain(victim);
        } finally {
            remote.close();
        }
    }

    @Test
    void addWritesPropertyBagsInOneRequest() {
        TripleStoreRDF4JSparql remote = remote("add-bags");
        try {
            remote.addNamespace("data", "http://example.com/model/#");
            remote.defineQueryPrefix("cim", CgmesNamespace.CIM_16_NAMESPACE);
            PropertyBags objects = new PropertyBags();
            for (int i = 0; i < 1000; i++) {
                PropertyBag bag = new PropertyBag(List.of("name"), false, false);
                bag.put("name", "object-" + i);
                objects.add(bag);
            }
            long t0 = System.nanoTime();
            remote.add("added_EQ.xml", CgmesNamespace.CIM_16_NAMESPACE, "Substation", objects);
            long millis = (System.nanoTime() - t0) / 1_000_000;

            assertThat(remote.contextNames()).contains("contexts:added_EQ.xml");
            PropertyBags found = remote.query(
                    "SELECT ?s ?name WHERE { GRAPH ?g { ?s a cim:Substation ; cim:Substation.name ?name }}");
            assertEquals(1000, found.size());
            assertThat(millis).as("1000 objects must be written in a single request, not one per statement")
                    .isLessThan(20_000L);
        } finally {
            remote.close();
        }
    }

    @Test
    void writeRoundTripsAGraphToRdfXml() {
        ReadOnlyDataSource ds = CgmesConformity1Catalog.miniBusBranch().dataSource();
        TripleStoreRDF4JSparql remote = remote("mini-write");
        TripleStore reread = local();
        try {
            loadInto(remote, ds, 1);
            String context = remote.contextNames().stream().filter(n -> n.contains("_SSH")).findFirst().orElseThrow();
            com.powsybl.commons.datasource.MemDataSource out = new com.powsybl.commons.datasource.MemDataSource();
            remote.write(out, context);
            String fileName = context.substring("contexts:".length());
            byte[] written = out.getData(fileName);
            assertNotNull(written);
            reread.read(new java.io.ByteArrayInputStream(written), "http://minigrid", fileName);
            assertEquals(Set.of("contexts:" + fileName), reread.contextNames());
            long remoteCount = Long.parseLong(countsOf(remote).get(context));
            long rereadCount = Long.parseLong(countsOf(reread).get(context));
            assertEquals(remoteCount, rereadCount);
        } finally {
            remote.close();
            reread.close();
        }
    }

    @Test
    void fileNamesWithSpacesSurviveTheGraphNaming() {
        // The CGMES 3 Svedala fixture has a space in every file name; a graph IRI cannot.
        String contextName = "contexts:20201202T1843Z_1D_Svedala Area_EQ_001.xml";
        String graph = ScenarioGraphNames.remoteGraph("2026-09-18", contextName);
        assertEquals("contexts:2026-09-18/20201202T1843Z_1D_Svedala%20Area_EQ_001.xml", graph);
        assertEquals(contextName, ScenarioGraphNames.localContextName("2026-09-18", graph));
        assertEquals("2026-09-18", ScenarioGraphNames.scenarioOf(graph));
        assertNull(ScenarioGraphNames.localContextName("other", graph));
    }

    @Test
    void aScenarioWithAnAwkwardNameStillWorks() {
        String scenario = "Base case / winter"; // slash and spaces: must be rejected
        assertThrows(PowsyblException.class, () -> ScenarioGraphNames.requireValidScenario(scenario));
        assertThrows(PowsyblException.class, () -> ScenarioGraphNames.requireValidScenario(""));
        assertThrows(PowsyblException.class, () -> ScenarioGraphNames.requireValidScenario("  "));
        assertThrows(NullPointerException.class, () -> ScenarioGraphNames.requireValidScenario(null));
        assertThrows(PowsyblException.class, () -> ScenarioGraphNames.requireValidScenario("x".repeat(129)));
        // Non-ASCII is fine, it is percent-encoded
        assertEquals("2026-09-18", ScenarioGraphNames.requireValidScenario("2026-09-18"));
        assertEquals("contexts:sc%C3%A9nario/", ScenarioGraphNames.prefix("scénario"));
    }

    @Test
    void aStoreCannotBeOpenedWithoutAScenario() {
        assertThrows(NullPointerException.class,
                () -> new TripleStoreRDF4JSparql(fuseki.endpoint(), null, new TripleStoreOptions()));
        assertThrows(PowsyblException.class,
                () -> new TripleStoreRDF4JSparql(fuseki.endpoint(), " ", new TripleStoreOptions()));
    }

    @Test
    void cgmes3FixtureWithSpacesInFileNamesLoadsAndQueries() {
        ReadOnlyDataSource ds = Cgmes3Catalog.microGrid().dataSource();
        TripleStoreRDF4JSparql remote = remote("cgmes3-micro");
        TripleStore localStore = local();
        try {
            loadInto(remote, ds, 4);
            loadInto(localStore, ds, 1);
            assertEquals(localStore.contextNames(), remote.contextNames());
            assertEquals(countsOf(localStore), countsOf(remote));
        } finally {
            remote.close();
            localStore.close();
        }
    }

    @Test
    void theImplementationNameStartsWithRdf4j() {
        // CgmesModelTripleStore.getBaseUri depends on this: the base of an rdf4j-parsed model is <base>/#
        TripleStoreRDF4JSparql remote = remote("name-check");
        try {
            assertTrue(remote.getImplementationName().startsWith("rdf4j"));
            assertEquals("rdf4j-sparql", remote.getImplementationName());
            assertEquals(SCENARIO, remote("2026-09-18").getScenario());
        } finally {
            remote.close();
        }
    }
}
