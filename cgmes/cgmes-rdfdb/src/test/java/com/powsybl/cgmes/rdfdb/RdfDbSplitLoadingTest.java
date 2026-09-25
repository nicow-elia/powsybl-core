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
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.triplestore.CgmesTripleStoreLoader;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.datasource.ReadOnlyMemDataSource;
import com.powsybl.commons.datasource.ResourceDataSource;
import com.powsybl.commons.datasource.ResourceSet;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import com.powsybl.triplestore.api.TripleStore;
import com.powsybl.triplestore.api.TripleStoreOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A network loaded out of an RDF database must be the network the files would have produced.
 *
 * <p>This is the acceptance test of the whole follow-up work package. Every fixture is loaded twice &mdash; the
 * native CGMES importer straight off the files, and the two-step path through a database &mdash; and the two
 * networks are compared as sorted XIIDM documents. It runs against both backends, an embedded Fuseki server and
 * the in-process one, and in both query modes.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbSplitLoadingTest {

    private static final String LOAD_ID = "1c6beed6-1acf-42e7-ba55-0cc9f04bddd8";
    private static final String MICRO_GRID_BE_RESOURCES =
            "/conformity/cas-1.1.3-data-4.0.3/MicroGrid/BaseCase/CGMES_v2.4.15_MicroGridTestConfiguration_BC_BE_v2/";
    private static final String SSH_FILE_NAME = "MicroGridTestConfiguration_BC_BE_SSH_V2.xml";
    private static final String SSH_BASE_NAME = "MicroGridTestConfiguration_BC_BE_SSH_V2";

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
        // A CGM is split into subnetworks at file level, above any triple store; the database path always
        // produces one network, so the comparison has to compare like with like.
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        return p;
    }

    static Stream<Arguments> fixtures() {
        return Stream.of(
                Arguments.of("microGridBaseCaseBE",
                        (Supplier<ReadOnlyDataSource>) () -> CgmesConformity1Catalog.microGridBaseCaseBE().dataSource()),
                Arguments.of("miniBusBranch",
                        (Supplier<ReadOnlyDataSource>) () -> CgmesConformity1Catalog.miniBusBranch().dataSource()),
                Arguments.of("miniNodeBreaker",
                        (Supplier<ReadOnlyDataSource>) () -> CgmesConformity1Catalog.miniNodeBreaker().dataSource()),
                Arguments.of("cgmes3MicroGrid",
                        (Supplier<ReadOnlyDataSource>) () -> Cgmes3Catalog.microGrid().dataSource()),
                Arguments.of("cgmes3SmallGrid",
                        (Supplier<ReadOnlyDataSource>) () -> Cgmes3Catalog.smallGrid().dataSource()),
                Arguments.of("microGridBaseCaseAssembled",
                        (Supplier<ReadOnlyDataSource>) () -> CgmesConformity1Catalog.microGridBaseCaseAssembled().dataSource()));
    }

    static Stream<Arguments> fixturesAndBackends() {
        return fixtures().flatMap(fixture -> Stream.of("fuseki", "memory")
                .map(backend -> Arguments.of(fixture.get()[0] + "/" + backend, fixture.get()[1], backend)));
    }

    private RdfDatabase databaseOf(String backend, String scenario) {
        return "memory".equals(backend)
                ? RdfDatabase.inMemory("split-" + scenario)
                : fuseki.database();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixturesAndBackends")
    void splitLoadingEqualsFileImport(String name, Supplier<ReadOnlyDataSource> dataSource, String backend) {
        Properties p = params();
        ReadOnlyDataSource ds = dataSource.get();
        Network fromFiles = Network.read(ds, p);

        String scenario = name.replace('/', '-');
        try (RdfDbConnection db = RdfDbConnection.open(databaseOf(backend, scenario))) {
            db.loadCgmes(scenario, ds, null, p, ReportNode.NO_OP);
            Network fromDb = RdfDbNetworkLoader.load(db, scenario, NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            Networks.assertSameNetwork(fromFiles, fromDb);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("remoteModeFixtures")
    void remoteQueryModeEqualsFileImport(String name, Supplier<ReadOnlyDataSource> dataSource) {
        Properties p = params();
        ReadOnlyDataSource ds = dataSource.get();
        Network fromFiles = Network.read(ds, p);

        String scenario = "remote-" + name;
        RdfDatabase database = fuseki.database().withQueryMode(RdfDatabase.QueryMode.REMOTE);
        try (RdfDbConnection db = RdfDbConnection.open(database)) {
            db.loadCgmes(scenario, ds, null, p, ReportNode.NO_OP);
            Network fromDb = RdfDbNetworkLoader.load(db, scenario, NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            Networks.assertSameNetwork(fromFiles, fromDb);
        }
    }

    static Stream<Arguments> remoteModeFixtures() {
        return Stream.of(
                Arguments.of("microGridBaseCaseBE",
                        (Supplier<ReadOnlyDataSource>) () -> CgmesConformity1Catalog.microGridBaseCaseBE().dataSource()),
                Arguments.of("miniBusBranch",
                        (Supplier<ReadOnlyDataSource>) () -> CgmesConformity1Catalog.miniBusBranch().dataSource()));
    }

    @Test
    void twoScenariosInOneDatabaseDoNotSeeEachOther() {
        Properties p = params();
        ReadOnlyDataSource be = CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
        ReadOnlyDataSource nl = CgmesConformity1Catalog.microGridBaseCaseNL().dataSource();

        try (RdfDbConnection db = RdfDbConnection.open(fuseki.database())) {
            db.loadCgmes("2026-09-18", be, null, p, ReportNode.NO_OP);
            db.loadCgmes("2026-09-19", nl, null, p, ReportNode.NO_OP);

            assertThat(db.scenarios()).contains("2026-09-18", "2026-09-19");

            Network beNetwork = RdfDbNetworkLoader.load(db, "2026-09-18", NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            Network nlNetwork = RdfDbNetworkLoader.load(db, "2026-09-19", NetworkFactory.findDefault(), p, ReportNode.NO_OP);

            Networks.assertSameNetwork(Network.read(be, p), beNetwork);
            Networks.assertSameNetwork(Network.read(nl, p), nlNetwork);
            assertThat(beNetwork.getSubstationCount()).isNotEqualTo(nlNetwork.getSubstationCount());

            // Clearing one scenario leaves the other alone
            db.clear("2026-09-18");
            assertThat(db.contextNames("2026-09-18")).isEmpty();
            assertThat(db.contextNames("2026-09-19")).isNotEmpty();
            Networks.assertSameNetwork(Network.read(nl, p),
                    RdfDbNetworkLoader.load(db, "2026-09-19", NetworkFactory.findDefault(), p, ReportNode.NO_OP));
        }
    }

    @Test
    void twoScenariosOfTheMemoryBackendDoNotSeeEachOther() {
        Properties p = params();
        ReadOnlyDataSource be = CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
        ReadOnlyDataSource nl = CgmesConformity1Catalog.microGridBaseCaseNL().dataSource();

        try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.inMemory("two-scenarios"))) {
            db.loadCgmes("day-1", be, null, p, ReportNode.NO_OP);
            db.loadCgmes("day-2", nl, null, p, ReportNode.NO_OP);
            assertEquals(List.of("day-1", "day-2"), db.scenarios());
            Networks.assertSameNetwork(Network.read(be, p),
                    RdfDbNetworkLoader.load(db, "day-1", NetworkFactory.findDefault(), p, ReportNode.NO_OP));
            Networks.assertSameNetwork(Network.read(nl, p),
                    RdfDbNetworkLoader.load(db, "day-2", NetworkFactory.findDefault(), p, ReportNode.NO_OP));
        }
    }

    @Test
    void theCatalogueOfAScenarioNamesTheSubsets() {
        Properties p = params();
        ReadOnlyDataSource ds = CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
        try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.inMemory("catalogue"))) {
            db.loadCgmes("catalogue-scenario", ds, null, p, ReportNode.NO_OP);
            List<GraphInfo> graphs = db.graphs("catalogue-scenario");
            assertThat(graphs).isNotEmpty();
            assertThat(graphs).allSatisfy(g -> {
                assertEquals("catalogue-scenario", g.scenario());
                assertThat(g.contextName()).startsWith("contexts:");
                assertNotNull(g.remoteGraph());
            });
            assertThat(graphs.stream().map(GraphInfo::subset))
                    .contains(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS,
                            CgmesSubset.TOPOLOGY, CgmesSubset.STATE_VARIABLES,
                            CgmesSubset.EQUIPMENT_BOUNDARY, CgmesSubset.TOPOLOGY_BOUNDARY);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fuseki", "memory"})
    void reUploadingAChangedFileReplacesTheGraphRatherThanAddingToIt(String backend, @TempDir Path dir) {
        // The contract of loadCgmes is replace, not append. On the SPARQL backend that is a Graph Store Protocol
        // PUT; on the in-process one the store has to clear the context first. Re-reading *identical* content
        // would not show the difference, because a triple store de-duplicates statements - so the second upload
        // here carries a changed value, and the scenario must end up holding only the new one.
        Properties p = params();
        ReadOnlyDataSource ds = CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
        Network changed = Network.read(ds, p);
        Load load = changed.getLoad(LOAD_ID);
        load.setP0(load.getP0() + 123.0);
        double expectedP0 = load.getP0();

        Properties exportParams = new Properties();
        exportParams.put("iidm.export.cgmes.profiles", List.of("SSH"));
        changed.write("CGMES", exportParams, dir.resolve("ssh"));
        // Under the original file name, so that the second upload lands in the very same graph
        ReadOnlyMemDataSource changedSsh = new ReadOnlyMemDataSource(SSH_BASE_NAME);
        try {
            changedSsh.putData(SSH_FILE_NAME, Files.readAllBytes(dir.resolve("ssh_SSH.xml")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ReadOnlyDataSource originalSsh = new ResourceDataSource(SSH_BASE_NAME,
                new ResourceSet(MICRO_GRID_BE_RESOURCES, SSH_FILE_NAME));

        String scenario = "idempotent-" + backend;
        try (RdfDbConnection db = RdfDbConnection.open(databaseOf(backend, scenario))) {
            db.loadCgmes(scenario, originalSsh, null, p, ReportNode.NO_OP);
            List<String> first = db.contextNames(scenario);
            long statementsBefore = statements(db, scenario);

            // The exported SSH is written under the same file name, so it lands in the same graph
            db.loadCgmes(scenario, changedSsh, null, p, ReportNode.NO_OP);
            assertEquals(first, db.contextNames(scenario));

            // The decisive check: the graph must hold exactly one value for the property that changed
            assertThat(valuesOf(db, scenario, LOAD_ID, "EnergyConsumer.p"))
                    .as("a replaced graph holds one value per property, not one per upload")
                    .hasSize(1);
            assertThat(statements(db, scenario))
                    .as("a replaced graph must not grow")
                    .isEqualTo(statementsBefore);

            Network base = Network.read(ds, p);
            RdfDbNetworkLoader.update(base, db, scenario, RdfDbLoadOptions.forUpdate(), p, ReportNode.NO_OP);
            assertEquals(expectedP0, base.getLoad(LOAD_ID).getP0(), 1e-6,
                    "the scenario must hold the new value only, not both");
        }
    }

    private static long statements(RdfDbConnection db, String scenario) {
        TripleStore store = db.scenarioStore(scenario, new TripleStoreOptions());
        return store.query("SELECT ?s ?p ?o WHERE { GRAPH ?g { ?s ?p ?o }}").size();
    }

    private static List<String> valuesOf(RdfDbConnection db, String scenario, String subject, String property) {
        TripleStore store = db.scenarioStore(scenario, new TripleStoreOptions());
        store.defineQueryPrefix("cim", CgmesNamespace.CIM_16_NAMESPACE);
        return store.query("SELECT ?value WHERE { GRAPH ?g { ?s cim:" + property + " ?value "
                        + "FILTER(STRENDS(STR(?s), \"#_" + subject + "\")) }}")
                .stream().map(bag -> bag.get("value")).toList();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fuseki", "memory"})
    void aSeparateBoundaryDataSourceIsUploadedWhenTheModelCarriesNone(String backend) {
        // Every other test hands loadCgmes a data source that already contains its boundary. This one splits the
        // fixture in two, which is what a real IGM plus a shared boundary looks like, and exercises the
        // hasBoundary query against the database itself.
        Properties p = params();
        ReadOnlyDataSource whole = CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
        ReadOnlyDataSource withoutBoundary = new FilteredDataSource(whole, name -> !name.contains("_BD"));
        ReadOnlyDataSource boundaryOnly = new FilteredDataSource(whole, name -> name.contains("_BD"));

        String scenario = "boundary-" + backend;
        try (RdfDbConnection db = RdfDbConnection.open(databaseOf(backend, scenario))) {
            CgmesTripleStoreLoader.Result result =
                    db.loadCgmes(scenario, withoutBoundary, boundaryOnly, p, ReportNode.NO_OP);
            assertTrue(result.boundaryLoaded(), "the boundary of a model that has none must be read");
            assertThat(db.graphs(scenario).stream().map(GraphInfo::subset))
                    .contains(CgmesSubset.EQUIPMENT_BOUNDARY, CgmesSubset.TOPOLOGY_BOUNDARY);

            Network fromDb = RdfDbNetworkLoader.load(db, scenario, NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            Networks.assertSameNetwork(Network.read(whole, p), fromDb);

            // Asked a second time, the model now carries its boundary and it is not read again
            CgmesTripleStoreLoader.Result again =
                    db.loadCgmes(scenario, whole, boundaryOnly, p, ReportNode.NO_OP);
            assertFalse(again.boundaryLoaded());
        }
    }

    /** A data source restricted to the files whose name passes a test. */
    private record FilteredDataSource(ReadOnlyDataSource ds, java.util.function.Predicate<String> keep)
            implements ReadOnlyDataSource {

        @Override
        public String getBaseName() {
            return ds.getBaseName();
        }

        @Override
        public boolean exists(String suffix, String ext) throws java.io.IOException {
            return ds.exists(suffix, ext)
                    && keep.test(com.powsybl.commons.datasource.DataSourceUtil.getFileName(getBaseName(), suffix, ext));
        }

        @Override
        public boolean isDataExtension(String ext) {
            return ds.isDataExtension(ext);
        }

        @Override
        public boolean exists(String fileName) throws java.io.IOException {
            return ds.exists(fileName) && keep.test(fileName);
        }

        @Override
        public java.io.InputStream newInputStream(String suffix, String ext) throws java.io.IOException {
            return ds.newInputStream(suffix, ext);
        }

        @Override
        public java.io.InputStream newInputStream(String fileName) throws java.io.IOException {
            return ds.newInputStream(fileName);
        }

        @Override
        public java.util.Set<String> listNames(String regex) throws java.io.IOException {
            return ds.listNames(regex).stream().filter(keep).collect(java.util.stream.Collectors.toSet());
        }
    }
}
