/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conformity.Cgmes3Catalog;
import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The demonstration of follow-up work package 1: the same CGMES data, imported from files and through a database.
 *
 * <p>Run it on its own to see the two paths side by side, with the timings and the graph catalogue printed:</p>
 * <pre>
 * mvn -o -pl cgmes/cgmes-rdfdb test -Dtest=RdfDbSplitLoadingDemoTest
 * </pre>
 *
 * <p>What it shows, for a small CIM16 model and for the largest CGMES 3 model in the repository:</p>
 * <ol>
 *   <li>A real SPARQL server (embedded Apache Jena Fuseki, on a free port) holding two <em>scenarios</em> at once,
 *       the way a production database holds many days of base grid models.</li>
 *   <li>The upload half: instance files parsed once and written into a scenario, one Graph Store Protocol request
 *       per file.</li>
 *   <li>The load half: the graphs fetched back in parallel and converted to IIDM, in the default local query mode
 *       and in the remote one, where the CGMES query catalogs run on the server.</li>
 *   <li>That all three networks &mdash; native, local-mode and remote-mode &mdash; are the same network.</li>
 * </ol>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbSplitLoadingDemoTest {

    private static EmbeddedFuseki fuseki;

    @BeforeAll
    static void startServer() {
        fuseki = EmbeddedFuseki.inMemory();
        LOGGER.info("Embedded Fuseki started at {}", fuseki.datasetUrl());
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
    void fileImportVersusSplitImportThroughAnEmbeddedFuseki() {
        List<String> table = new ArrayList<>();
        table.add(String.format("%-28s %-18s %9s %9s %9s %9s %9s",
                "fixture", "scenario", "graphs", "native", "upload", "db-local", "db-remote"));

        demo("microGridBaseCaseBE", "2026-09-18",
                CgmesConformity1Catalog.microGridBaseCaseBE().dataSource(), table);
        demo("svedala", "2026-09-19", Cgmes3Catalog.svedala().dataSource(), table);

        LOGGER.info("");
        LOGGER.info("=== Follow-up WP1: CGMES files -> RDF database -> IIDM (milliseconds) ===");
        table.forEach(LOGGER::info);
        LOGGER.info("");

        try (RdfDbConnection db = RdfDbConnection.open(fuseki.database())) {
            LOGGER.info("Scenarios in {}: {}", fuseki.datasetUrl(), db.scenarios());
            assertThat(db.scenarios()).containsExactly("2026-09-18", "2026-09-19");
        }
    }

    private void demo(String name, String scenario, ReadOnlyDataSource ds, List<String> table) {
        Properties p = params();

        long t0 = System.nanoTime();
        Network nativeNetwork = Network.read(ds, p);
        long nativeMillis = (System.nanoTime() - t0) / 1_000_000;

        long uploadMillis;
        long localMillis;
        long remoteMillis;
        int graphs;

        try (RdfDbConnection db = RdfDbConnection.open(fuseki.database())) {
            long t1 = System.nanoTime();
            db.loadCgmes(scenario, ds, null, p, ReportNode.NO_OP);
            uploadMillis = (System.nanoTime() - t1) / 1_000_000;

            List<GraphInfo> catalogue = db.graphs(scenario);
            graphs = catalogue.size();
            LOGGER.info("");
            LOGGER.info("--- {} uploaded into scenario '{}' ---", name, scenario);
            catalogue.forEach(g -> LOGGER.info("    {}  subset={}  statements={}  graph={}",
                    g.contextName(), g.subset(), statements(db, scenario, g), g.remoteGraph()));

            long t2 = System.nanoTime();
            RdfDbNetworkLoader.LoadResult local = RdfDbNetworkLoader.loadWithStatistics(db, scenario,
                    NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            localMillis = (System.nanoTime() - t2) / 1_000_000;
            LOGGER.info("    local  mode: {}", local.statistics().summary());
            Networks.assertSameNetwork(nativeNetwork, local.network());
        }

        try (RdfDbConnection db = RdfDbConnection.open(
                fuseki.database().withQueryMode(RdfDatabase.QueryMode.REMOTE))) {
            long t3 = System.nanoTime();
            Network remote = RdfDbNetworkLoader.load(db, scenario, NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            remoteMillis = (System.nanoTime() - t3) / 1_000_000;
            LOGGER.info("    remote mode: {} ms, the CGMES query catalogs evaluated on the server", remoteMillis);
            Networks.assertSameNetwork(nativeNetwork, remote);
            assertEquals(nativeNetwork.getSubstationCount(), remote.getSubstationCount());
        }

        table.add(String.format("%-28s %-18s %9d %9d %9d %9d %9d",
                name, scenario, graphs, nativeMillis, uploadMillis, localMillis, remoteMillis));
    }

    /** How many statements one graph of a scenario holds, as the database counts them. */
    private static long statements(RdfDbConnection db, String scenario, GraphInfo graph) {
        return db.scenarioStore(scenario, new com.powsybl.triplestore.api.TripleStoreOptions())
                .query("SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + graph.remoteGraph() + "> { ?s ?p ?o } }")
                .stream().map(bag -> Long.parseLong(bag.get("n"))).findFirst().orElse(0L);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(RdfDbSplitLoadingDemoTest.class);
}
