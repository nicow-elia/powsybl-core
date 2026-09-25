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
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A warm cache turns a database load into a conversion and nothing else.
 *
 * <p>Fetching and parsing the graphs is the bulk of what a database load costs, so a second load of the same
 * scenario can be markedly cheaper than a file import ever can. The price is that a cached graph can be stale,
 * which is why the cache is opt-in and checks the statement count of a graph before it trusts an entry.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class GraphCacheTest {

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
    void aWarmLoadSkipsTheFetchAndGivesTheSameNetwork() {
        Properties p = params();
        ReadOnlyDataSource ds = CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
        Network fromFiles = Network.read(ds, p);

        GraphCache cache = new GraphCache();
        RdfDatabase database = fuseki.database().withCache(cache);
        try (RdfDbConnection db = RdfDbConnection.open(database)) {
            db.loadCgmes("cached", ds, null, p, ReportNode.NO_OP);

            RdfDbNetworkLoader.LoadResult cold = RdfDbNetworkLoader.loadWithStatistics(db, "cached",
                    NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            assertEquals(0, cold.statistics().cacheHits());
            assertThat(cache.size()).isEqualTo(cold.statistics().graphs());
            assertThat(cache.statements()).isEqualTo(cold.statistics().statements());

            RdfDbNetworkLoader.LoadResult warm = RdfDbNetworkLoader.loadWithStatistics(db, "cached",
                    NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            assertEquals(warm.statistics().graphs(), warm.statistics().cacheHits());
            assertEquals(0L, warm.statistics().parse().toNanos());
            Networks.assertSameNetwork(fromFiles, warm.network());
            assertThat(cache.hits()).isGreaterThan(0L);
        }
    }

    @Test
    void anUploadIntoTheScenarioInvalidatesItsCachedGraphs() {
        Properties p = params();
        ReadOnlyDataSource ds = CgmesConformity1Catalog.miniBusBranch().dataSource();
        GraphCache cache = new GraphCache();
        try (RdfDbConnection db = RdfDbConnection.open(fuseki.database().withCache(cache))) {
            db.loadCgmes("invalidated", ds, null, p, ReportNode.NO_OP);
            RdfDbNetworkLoader.load(db, "invalidated", NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            assertThat(cache.size()).isGreaterThan(0);

            db.loadCgmes("invalidated", ds, null, p, ReportNode.NO_OP);
            assertEquals(0, cache.size());
        }
    }

    @Test
    void theBudgetIsCountedInStatementsAndEvictsTheLeastRecentlyUsed() {
        GraphCache cache = new GraphCache(10);
        cache.put("g1", statements(4), "ns");
        cache.put("g2", statements(4), "ns");
        assertEquals(8, cache.statements());
        assertEquals(List.of("g1", "g2"), cache.keys());

        // Touch g1 so that g2 becomes the least recently used one
        assertNotNull(cache.get("g1", 4));
        cache.put("g3", statements(4), "ns");
        assertEquals(List.of("g1", "g3"), cache.keys());
        assertEquals(8, cache.statements());

        // A graph that does not fit the whole budget is not cached at all
        cache.put("huge", statements(20), "ns");
        assertThat(cache.keys()).doesNotContain("huge");
    }

    @Test
    void anEntryIsOnlyUsedWhenTheStatementCountStillMatches() {
        GraphCache cache = new GraphCache();
        cache.put("g", statements(3), "ns");
        assertNotNull(cache.get("g", 3));
        assertNull(cache.get("g", 4));
        assertNull(cache.get("g", -1));
        assertNull(cache.get("other", 3));

        cache.trustImmutableGraphs(true);
        assertNotNull(cache.get("g", -1));
        assertNotNull(cache.get("g", 99));

        cache.invalidate("g");
        assertNull(cache.get("g", 3));
        assertEquals(0, cache.statements());
    }

    @Test
    void aBudgetMustBePositive() {
        assertThrows(RdfDbException.class, () -> new GraphCache(0));
    }

    private static List<Statement> statements(int n) {
        SimpleValueFactory f = SimpleValueFactory.getInstance();
        List<Statement> statements = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            statements.add(f.createStatement(f.createIRI("http://example.com/s" + i),
                    f.createIRI("http://example.com/p"), f.createLiteral(i)));
        }
        return statements;
    }
}
