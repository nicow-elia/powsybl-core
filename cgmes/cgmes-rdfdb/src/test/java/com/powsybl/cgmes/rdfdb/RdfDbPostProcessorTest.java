/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.google.auto.service.AutoService;
import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.computation.ComputationManager;
import com.powsybl.iidm.network.ImportPostProcessor;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The generic IIDM import post-processors run on a database load exactly as they do on a file import.
 *
 * <p>A file import applies them above the CGMES importer, in {@code Importer.find}, with the computation manager
 * it was given and with the names the platform configuration activates when the caller names none. A database
 * load has neither of those for free, so it has to do both itself &mdash; or the two paths silently differ for
 * every user who has a post-processor configured.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbPostProcessorTest {

    /** Records that it ran, and with what. */
    @AutoService(ImportPostProcessor.class)
    public static class Recorder implements ImportPostProcessor {

        static int calls;
        static ComputationManager lastComputationManager;
        static String lastNetworkId;

        @Override
        public String getName() {
            return "rdfDbTestRecorder";
        }

        @Override
        public void process(Network network, ComputationManager computationManager, ReportNode reportNode) {
            calls++;
            lastComputationManager = computationManager;
            lastNetworkId = network.getId();
        }
    }

    @BeforeEach
    void reset() {
        Recorder.calls = 0;
        Recorder.lastComputationManager = null;
        Recorder.lastNetworkId = null;
    }

    private static Properties params() {
        Properties p = new Properties();
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        return p;
    }

    @Test
    void aNamedPostProcessorRunsWithARealComputationManager() {
        Properties p = params();
        try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.inMemory("post-processors"))) {
            db.loadCgmes("s", CgmesConformity1Catalog.miniBusBranch().dataSource(), null, p, ReportNode.NO_OP);
            RdfDbLoadOptions options = new RdfDbLoadOptions().setPostProcessors(List.of("rdfDbTestRecorder"));
            Network network = RdfDbNetworkLoader.load(db, "s", options, NetworkFactory.findDefault(),
                    p, ReportNode.NO_OP);

            assertEquals(1, Recorder.calls);
            assertEquals(network.getId(), Recorder.lastNetworkId);
            assertThat(Recorder.lastComputationManager)
                    .as("a post processor that runs a load flow needs a real computation manager")
                    .isNotNull();
        }
    }

    @Test
    void anExplicitComputationManagerIsHandedOver() throws Exception {
        Properties p = params();
        ComputationManager mine = new com.powsybl.computation.local.LocalComputationManager();
        try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.inMemory("post-processors-cm"))) {
            db.loadCgmes("s", CgmesConformity1Catalog.miniBusBranch().dataSource(), null, p, ReportNode.NO_OP);
            RdfDbLoadOptions options = new RdfDbLoadOptions()
                    .setPostProcessors(List.of("rdfDbTestRecorder"))
                    .setComputationManager(mine);
            RdfDbNetworkLoader.load(db, "s", options, NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            assertEquals(mine, Recorder.lastComputationManager);
        }
    }

    @Test
    void anUnknownPostProcessorNamesWhatIsAvailable() {
        Properties p = params();
        try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.inMemory("post-processors-unknown"))) {
            db.loadCgmes("s", CgmesConformity1Catalog.miniBusBranch().dataSource(), null, p, ReportNode.NO_OP);
            RdfDbLoadOptions options = new RdfDbLoadOptions().setPostProcessors(List.of("nope"));
            RdfDbException e = assertThrows(RdfDbException.class, () -> RdfDbNetworkLoader.load(db, "s", options,
                    NetworkFactory.findDefault(), p, ReportNode.NO_OP));
            assertThat(e.getMessage()).contains("nope").contains("rdfDbTestRecorder");
        }
    }

    @Test
    void postProcessorsCanBeSwitchedOff() {
        Properties p = params();
        try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.inMemory("post-processors-off"))) {
            db.loadCgmes("s", CgmesConformity1Catalog.miniBusBranch().dataSource(), null, p, ReportNode.NO_OP);
            RdfDbLoadOptions options = new RdfDbLoadOptions()
                    .setPostProcessors(List.of("rdfDbTestRecorder"))
                    .setApplyImportPostProcessors(false);
            RdfDbNetworkLoader.load(db, "s", options, NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            assertEquals(0, Recorder.calls);
        }
    }
}
