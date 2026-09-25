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
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A network that came out of a database remembers where from, and does not carry that into a file.
 *
 * <p>The later work packages &mdash; writing differences back, following a chain of timesteps &mdash; need the
 * database and the scenario a network was built from, so it is recorded on the network. It must not be
 * serialised: a XIIDM file that claimed a network still belongs to some server would be wrong as soon as the file
 * is moved, which is why the extension has no serialiser and is silently skipped by the exporter.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbProvenanceTest {

    @Test
    void theExtensionRecordsTheDatabaseTheScenarioAndTheGraphs() {
        Properties p = new Properties();
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        Instant before = Instant.now();
        try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.inMemory("provenance"))) {
            db.loadCgmes("2026-09-18", CgmesConformity1Catalog.microGridBaseCaseBE().dataSource(),
                    null, p, ReportNode.NO_OP);
            Network network = RdfDbNetworkLoader.load(db, "2026-09-18", NetworkFactory.findDefault(),
                    p, ReportNode.NO_OP);

            RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
            assertNotNull(provenance);
            assertEquals(RdfDbProvenance.NAME, provenance.getName());
            assertEquals("memory:provenance", provenance.database().toString());
            assertEquals("2026-09-18", provenance.scenario());
            assertThat(provenance.loadedAt()).isAfterOrEqualTo(before);
            assertThat(provenance.graphs()).isNotEmpty();
            assertThat(provenance.graphs()).allSatisfy(g -> assertEquals("2026-09-18", g.scenario()));
            assertThat(provenance.graphs().stream().map(GraphInfo::subset)).contains(CgmesSubset.EQUIPMENT);

            // Not serialised: the XIIDM of this network must not mention it
            assertThat(Networks.xiidmString(network)).doesNotContain(RdfDbProvenance.NAME);
        }
    }

    @Test
    void theSubsetOfAContextNameIsReadTheWayTheConversionReadsIt() {
        assertEquals(CgmesSubset.EQUIPMENT, GraphInfo.subsetOf("contexts:X_EQ_001.xml"));
        assertEquals(CgmesSubset.EQUIPMENT_BOUNDARY, GraphInfo.subsetOf("contexts:X_EQ_BD_001.xml"));
        assertEquals(CgmesSubset.TOPOLOGY, GraphInfo.subsetOf("contexts:X_TP_001.xml"));
        assertEquals(CgmesSubset.TOPOLOGY_BOUNDARY, GraphInfo.subsetOf("contexts:X_TP_BD_001.xml"));
        assertEquals(CgmesSubset.STEADY_STATE_HYPOTHESIS, GraphInfo.subsetOf("contexts:X_SSH_001.xml"));
        assertEquals(CgmesSubset.STATE_VARIABLES, GraphInfo.subsetOf("contexts:X_SV_001.xml"));
        assertEquals(CgmesSubset.DIAGRAM_LAYOUT, GraphInfo.subsetOf("contexts:X_DL_001.xml"));
        assertEquals(CgmesSubset.UNKNOWN, GraphInfo.subsetOf("contexts:something-else.xml"));
    }
}
