/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.triplestore.impl.rdf4j.sparql;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.triplestore.CgmesTripleStoreLoader;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.triplestore.api.PropertyBags;
import com.powsybl.triplestore.api.TripleStoreOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two scenarios in one database must not see each other, not even through a graph-less query.
 *
 * <p>This is the property the whole multi-day design rests on, and it is not free: a local in-memory store has a
 * default graph that is the union of all its contexts, and a third of the CGMES catalog queries &mdash; nearly all
 * of the {@code -update} catalog &mdash; rely on that. A database has an empty default graph instead, so every
 * query is sent with the SPARQL protocol dataset parameters of the scenario's graphs. This test proves that the
 * server honours them, in both shapes of query.</p>
 *
 * <p>If it ever fails against a server, the fallback is to rewrite the query text with {@code FROM} /
 * {@code FROM NAMED} clauses, or to configure a union default graph and switch the dataset off per scenario
 * database.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RemoteQueryModeTest {

    private static EmbeddedFuseki fuseki;

    @BeforeAll
    static void startServer() {
        fuseki = EmbeddedFuseki.inMemory();
        // Both micro grids, in the same Fuseki dataset, under two scenarios
        load("be-2026-09-18", CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        load("nl-2026-09-19", CgmesConformity1Catalog.microGridBaseCaseNL().dataSource());
    }

    private static void load(String scenario, com.powsybl.commons.datasource.ReadOnlyDataSource ds) {
        TripleStoreRDF4JSparql store = new TripleStoreRDF4JSparql(fuseki.endpoint(), scenario, new TripleStoreOptions());
        try {
            CgmesTripleStoreLoader.load(ds, null, store, 4, ReportNode.NO_OP);
        } finally {
            store.close();
        }
    }

    @AfterAll
    static void stopServer() {
        fuseki.close();
    }

    private static TripleStoreRDF4JSparql store(String scenario) {
        TripleStoreRDF4JSparql store = new TripleStoreRDF4JSparql(fuseki.endpoint(), scenario, new TripleStoreOptions());
        store.defineQueryPrefix("cim", CgmesNamespace.CIM_16_NAMESPACE);
        store.defineQueryPrefix("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
        return store;
    }

    @Test
    void aGraphQueryOfOneScenarioSeesOnlyThatScenario() {
        TripleStoreRDF4JSparql be = store("be-2026-09-18");
        TripleStoreRDF4JSparql nl = store("nl-2026-09-19");
        try {
            String fullModels = "SELECT ?model ?graph WHERE { GRAPH ?graph { ?model a md:FullModel }}";
            Set<String> beGraphs = graphs(be.query(fullModels));
            Set<String> nlGraphs = graphs(nl.query(fullModels));

            assertFalse(beGraphs.isEmpty());
            assertFalse(nlGraphs.isEmpty());
            // Every graph of the BE scenario is either a BE instance file or a shared boundary file, and none of
            // them is an NL instance file - and the other way round.
            assertThat(beGraphs).noneSatisfy(g -> assertThat(g).contains("_NL_"));
            assertThat(nlGraphs).noneSatisfy(g -> assertThat(g).contains("_BE_"));
            assertThat(beGraphs).anySatisfy(g -> assertThat(g).contains("_BE_"));
            assertThat(nlGraphs).anySatisfy(g -> assertThat(g).contains("_NL_"));
        } finally {
            be.close();
            nl.close();
        }
    }

    @Test
    void aGraphlessQueryOfOneScenarioSeesOnlyThatScenario() {
        // This is the shape almost every query of the CGMES "-update" catalog has: no GRAPH clause at all, so it
        // runs against the default graph, which for a scenario must be the union of exactly its own graphs.
        TripleStoreRDF4JSparql be = store("be-2026-09-18");
        TripleStoreRDF4JSparql nl = store("nl-2026-09-19");
        try {
            String models = """
                    SELECT ?model ?mas WHERE {
                        ?model a md:FullModel ; md:Model.modelingAuthoritySet ?mas .
                    }
                    """;
            PropertyBags beModels = be.query(models);
            PropertyBags nlModels = nl.query(models);
            assertFalse(beModels.isEmpty(),
                    "a graph-less query must see the scenario's graphs through the default graph");
            assertFalse(nlModels.isEmpty());
            Set<String> beIds = beModels.stream().map(b -> b.get("model")).collect(Collectors.toSet());
            Set<String> nlIds = nlModels.stream().map(b -> b.get("model")).collect(Collectors.toSet());
            // The boundary models are shared by both scenarios; the instance models are not
            assertThat(beIds).isNotEqualTo(nlIds);
            assertThat(beModels.stream().map(b -> b.get("mas")).collect(Collectors.toSet()))
                    .anySatisfy(mas -> assertThat(mas).contains("elia"));
            assertThat(nlModels.stream().map(b -> b.get("mas")).collect(Collectors.toSet()))
                    .anySatisfy(mas -> assertThat(mas).contains("tennet"));
            assertThat(nlModels.stream().map(b -> b.get("mas")).collect(Collectors.toSet()))
                    .noneSatisfy(mas -> assertThat(mas).contains("elia"));
        } finally {
            be.close();
            nl.close();
        }
    }

    @Test
    void anUnknownScenarioSeesNothingRatherThanEverything() {
        TripleStoreRDF4JSparql empty = store("no-such-scenario");
        try {
            assertTrue(empty.contextNames().isEmpty());
            assertTrue(empty.query("SELECT ?s WHERE { GRAPH ?g { ?s a md:FullModel }}").isEmpty());
            assertTrue(empty.query("SELECT ?s WHERE { ?s a md:FullModel }").isEmpty());
        } finally {
            empty.close();
        }
    }

    @Test
    void switchingTheDatasetOffLetsAQuerySeeTheWholeDatabase() {
        TripleStoreRDF4JSparql be = store("be-2026-09-18");
        try {
            String fullModels = "SELECT ?model ?graph WHERE { GRAPH ?graph { ?model a md:FullModel }}";
            assertTrue(be.isRemoteQueryDataset(), "the dataset of a scenario is sent by default");
            int scoped = be.query(fullModels).size();
            be.setRemoteQueryDataset(false);
            assertFalse(be.isRemoteQueryDataset());
            int unscoped = be.query(fullModels).size();
            assertThat(unscoped).isGreaterThan(scoped);
            // The graph names of the other scenario are reported raw, since they are not under this prefix
            assertEquals(scoped, be.query(fullModels).stream()
                    .filter(b -> b.get("graph").startsWith("contexts:MicroGridTestConfiguration"))
                    .count());
        } finally {
            be.close();
        }
    }

    private static Set<String> graphs(PropertyBags bags) {
        return bags.stream().map(b -> b.get("graph")).collect(Collectors.toSet());
    }
}
