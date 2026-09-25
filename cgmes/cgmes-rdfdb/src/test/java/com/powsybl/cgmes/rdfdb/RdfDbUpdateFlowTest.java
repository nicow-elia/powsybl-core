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
import com.powsybl.commons.datasource.DirectoryDataSource;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A steady-state update that travelled through an RDF database must land the same way a file update does.
 *
 * <p>This is the flow the whole diff-stacking design is built on: a base grid model sits in a scenario of the
 * database, a changed steady state arrives as an SSH document, and a network that is already in memory is brought
 * up to date from it. The test makes a real change to a network, exports the SSH the CGMES exporter writes for it,
 * and then applies that SSH twice &mdash; once straight from the file, once after a round trip through a scenario
 * of the database &mdash; and requires the two networks to be identical.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbUpdateFlowTest {

    private static final String LOAD_ID = "1c6beed6-1acf-42e7-ba55-0cc9f04bddd8";
    private static final java.util.concurrent.atomic.AtomicReference<String> CHANGED_TRANSFORMER =
            new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicInteger CHANGED_TAP =
            new java.util.concurrent.atomic.AtomicInteger();

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
     * Change one load, and write the SSH document that describes the new steady state.
     *
     * @param network the network to change and export
     * @param dir     where to write
     * @return the data source holding the exported SSH
     */
    private static ReadOnlyDataSource exportChangedSsh(Network network, Path dir) {
        Load load = network.getLoad(LOAD_ID);
        assertThat(load).isNotNull();
        load.setP0(load.getP0() + 42.0);
        load.setQ0(load.getQ0() + 7.0);

        // A tap as well as a load: the two shapes a steady state change takes, and the tap goes through a
        // different update query than the injection does
        TwoWindingsTransformer transformer = network.getTwoWindingsTransformerStream()
                .filter(t -> t.hasRatioTapChanger() && t.getRatioTapChanger().getStepCount() > 1)
                .findFirst()
                .orElseThrow();
        RatioTapChanger tapChanger = transformer.getRatioTapChanger();
        int newTap = tapChanger.getTapPosition() == tapChanger.getHighTapPosition()
                ? tapChanger.getLowTapPosition()
                : tapChanger.getTapPosition() + 1;
        tapChanger.setTapPosition(newTap);
        CHANGED_TRANSFORMER.set(transformer.getId());
        CHANGED_TAP.set(newTap);

        Properties exportParams = new Properties();
        exportParams.put("iidm.export.cgmes.profiles", java.util.List.of("SSH"));
        network.write("CGMES", exportParams, dir.resolve("changed"));
        return new DirectoryDataSource(dir, "changed");
    }

    @Test
    void updateThroughAScenarioEqualsUpdateFromTheFile(@TempDir Path dir) {
        Properties p = params();
        ReadOnlyDataSource ds = CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();

        Network changed = Network.read(ds, p);
        ReadOnlyDataSource sshDs = exportChangedSsh(changed, dir);
        double expectedP0 = changed.getLoad(LOAD_ID).getP0();

        Network throughFile = Network.read(ds, p);
        assertNotEquals(expectedP0, throughFile.getLoad(LOAD_ID).getP0());
        throughFile.update(sshDs, p);
        assertEquals(expectedP0, throughFile.getLoad(LOAD_ID).getP0(), 1e-6);

        try (RdfDbConnection db = RdfDbConnection.open(fuseki.database())) {
            db.loadCgmes("base-2026-09-18", ds, null, p, ReportNode.NO_OP);
            db.loadCgmes("ssh-2026-09-18", sshDs, null, p, ReportNode.NO_OP);
            assertThat(db.scenarios()).contains("base-2026-09-18", "ssh-2026-09-18");

            Network throughDb = RdfDbNetworkLoader.load(db, "base-2026-09-18",
                    NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            assertNotEquals(expectedP0, throughDb.getLoad(LOAD_ID).getP0());

            RdfDbNetworkLoader.update(throughDb, db, "ssh-2026-09-18", RdfDbLoadOptions.forUpdate(),
                    p, ReportNode.NO_OP);
            assertEquals(expectedP0, throughDb.getLoad(LOAD_ID).getP0(), 1e-6);
            assertEquals(CHANGED_TAP.get(), throughDb.getTwoWindingsTransformer(CHANGED_TRANSFORMER.get())
                    .getRatioTapChanger().getTapPosition());
            Networks.assertSameNetwork(throughFile, throughDb);
        }
    }

    @Test
    void updateInRemoteQueryModeEqualsUpdateFromTheFile(@TempDir Path dir) {
        Properties p = params();
        ReadOnlyDataSource ds = CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();

        Network changed = Network.read(ds, p);
        ReadOnlyDataSource sshDs = exportChangedSsh(changed, dir);
        double expectedP0 = changed.getLoad(LOAD_ID).getP0();

        Network throughFile = Network.read(ds, p);
        throughFile.update(sshDs, p);

        RdfDatabase database = fuseki.database().withQueryMode(RdfDatabase.QueryMode.REMOTE);
        try (RdfDbConnection db = RdfDbConnection.open(database)) {
            db.loadCgmes("remote-base", ds, null, p, ReportNode.NO_OP);
            db.loadCgmes("remote-ssh", sshDs, null, p, ReportNode.NO_OP);
            Network throughDb = RdfDbNetworkLoader.load(db, "remote-base",
                    NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            RdfDbNetworkLoader.update(throughDb, db, "remote-ssh", RdfDbLoadOptions.forUpdate(),
                    p, ReportNode.NO_OP);
            assertEquals(expectedP0, throughDb.getLoad(LOAD_ID).getP0(), 1e-6);
            Networks.assertSameNetwork(throughFile, throughDb);
        }
    }

    @Test
    void updateThroughTheMemoryBackendEqualsUpdateFromTheFile(@TempDir Path dir) {
        Properties p = params();
        ReadOnlyDataSource ds = CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();

        Network changed = Network.read(ds, p);
        ReadOnlyDataSource sshDs = exportChangedSsh(changed, dir);
        double expectedP0 = changed.getLoad(LOAD_ID).getP0();

        Network throughFile = Network.read(ds, p);
        throughFile.update(sshDs, p);

        try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.inMemory("update-flow"))) {
            db.loadCgmes("base", ds, null, p, ReportNode.NO_OP);
            db.loadCgmes("ssh", sshDs, null, p, ReportNode.NO_OP);
            Network throughDb = RdfDbNetworkLoader.load(db, "base", NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            RdfDbNetworkLoader.update(throughDb, db, "ssh", RdfDbLoadOptions.forUpdate(), p, ReportNode.NO_OP);
            assertEquals(expectedP0, throughDb.getLoad(LOAD_ID).getP0(), 1e-6);
            Networks.assertSameNetwork(throughFile, throughDb);
        }
    }
}
