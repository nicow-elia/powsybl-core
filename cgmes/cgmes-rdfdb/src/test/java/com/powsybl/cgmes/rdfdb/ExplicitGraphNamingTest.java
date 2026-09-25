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
import com.powsybl.cgmes.conversion.TripleStoreNetworkLoader;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import com.powsybl.triplestore.api.PropertyBags;
import com.powsybl.triplestore.api.TripleStoreOptions;
import com.powsybl.triplestore.impl.rdf4j.TripleStoreRDF4J;
import com.powsybl.triplestore.impl.rdf4j.sparql.TripleStoreRDF4JSparql;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Graphs can be addressed by an explicit name, not only through the scenario prefix.
 *
 * <p>This work package keeps every graph of a scenario at {@code contexts:<scenario>/<file name>}, and that is all
 * the naming it needs. The follow-up work packages do not: a version of a model gets an immutable IRI of its own,
 * and a difference gets a graph that is not an instance file at all. Both need to fetch and to query graphs whose
 * IRI the scenario prefix cannot produce, while everything above the triple store keeps seeing the plain
 * {@code contexts:<file name>} the CGMES conversion is written against.</p>
 *
 * <p>The test therefore stores the graphs of a model under IRIs of a completely different shape and shows that a
 * network built from them is still the network the files produce.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class ExplicitGraphNamingTest {

    private static final String OTHER_SCHEME = "http://powsybl.org/rdfdb/v1/";

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

    /**
     * Copy the graphs of a scenario to IRIs of another shape, and return the mapping.
     */
    private static Map<String, String> republishUnderAnotherScheme(RdfDbConnection db, String scenario) {
        TripleStoreRDF4JSparql store =
                (TripleStoreRDF4JSparql) db.scenarioStore(scenario, new TripleStoreOptions());
        Map<String, String> localToRemote = new LinkedHashMap<>();
        for (String contextName : db.contextNames(scenario)) {
            String target = OTHER_SCHEME + Integer.toHexString(contextName.hashCode());
            List<Statement> statements = new ArrayList<>();
            store.graphStoreClient().get(store.remoteGraph(contextName), new StatementCollector(statements));
            store.writeGraph(target, statements, true);
            localToRemote.put(contextName, target);
        }
        return localToRemote;
    }

    @Test
    void graphsAddressedByAnExplicitMapProduceTheSameNetwork() {
        Properties p = params();
        ReadOnlyDataSource ds = CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
        Network fromFiles = Network.read(ds, p);

        try (RdfDbConnection db = RdfDbConnection.open(fuseki.database())) {
            db.loadCgmes("v1", ds, null, p, ReportNode.NO_OP);
            Map<String, String> localToRemote = republishUnderAnotherScheme(db, "v1");
            assertThat(localToRemote.values()).allSatisfy(iri -> assertThat(iri).startsWith(OTHER_SCHEME));

            // (1) fetch them into a local store by the explicit map, and convert
            CgmesImport importer = TripleStoreNetworkLoader.importer();
            TripleStoreRDF4J local = new TripleStoreRDF4J(new SailRepository(new MemoryStore()),
                    importer.tripleStoreOptions(p));
            new GraphFetcher(db, "v1").fetchInto(local, localToRemote);
            assertEquals(localToRemote.keySet(), local.contextNames());
            Network fetched = TripleStoreNetworkLoader.load(local, NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            Networks.assertSameNetwork(fromFiles, fetched);

            // (2) query them on the server by the explicit map, in remote mode
            TripleStoreRDF4JSparql remote =
                    (TripleStoreRDF4JSparql) db.scenarioStore("v1", importer.tripleStoreOptions(p));
            remote.restrictTo(localToRemote);
            assertEquals(localToRemote.keySet(), remote.contextNames());
            remote.defineQueryPrefix("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
            PropertyBags models = remote.query(
                    "SELECT ?model ?graph WHERE { GRAPH ?graph { ?model a md:FullModel }}");
            assertThat(models).isNotEmpty();
            // Even though the graphs are named something else entirely, the conversion sees file names
            assertThat(models.stream().map(bag -> bag.get("graph")))
                    .allSatisfy(graph -> assertThat(graph).startsWith("contexts:MicroGridTestConfiguration"));
            Network queried = TripleStoreNetworkLoader.load(remote, NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            Networks.assertSameNetwork(fromFiles, queried);
        }
    }
}
