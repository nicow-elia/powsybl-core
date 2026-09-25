/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conformity.Cgmes3Catalog;
import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.TripleStoreNetworkLoader;
import com.powsybl.cgmes.model.CgmesModel;
import com.powsybl.cgmes.model.CgmesModelException;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.triplestore.CgmesTripleStoreLoader;
import com.powsybl.commons.config.PlatformConfig;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.datasource.ResourceDataSource;
import com.powsybl.commons.datasource.ResourceSet;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.commons.test.ComparisonUtils;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import com.powsybl.iidm.serde.ExportOptions;
import com.powsybl.iidm.serde.NetworkSerDe;
import com.powsybl.triplestore.api.TripleStore;
import com.powsybl.triplestore.api.TripleStoreFactory;
import com.powsybl.triplestore.api.TripleStoreOptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Splitting a CGMES import in two halves must not change the network that comes out of it.
 *
 * <p>The native importer parses files and converts the statements in one go. {@link CgmesTripleStoreLoader} plus
 * {@link TripleStoreNetworkLoader} do the same two things with a triple store in between, and that seam is what
 * makes an RDF database a possible home for CGMES data. This test loads the same fixtures both ways and compares
 * the serialised networks, and it pins what {@link TripleStoreNetworkLoader#describe} reads off a store.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class TripleStoreNetworkLoaderTest {

    private static Properties params() {
        Properties p = new Properties();
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        return p;
    }

    private static TripleStore load(ReadOnlyDataSource main, ReadOnlyDataSource boundary, Properties p) {
        CgmesImport importer = new CgmesImport(PlatformConfig.defaultConfig());
        TripleStoreOptions options = importer.tripleStoreOptions(p);
        TripleStore store = TripleStoreFactory.create(options);
        CgmesTripleStoreLoader.load(main, boundary, store, ReportNode.NO_OP);
        return store;
    }

    private static String xiidm(Network network) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NetworkSerDe.write(network, new ExportOptions().setSorted(true), bytes);
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void assertSameNetwork(Network expected, Network actual) {
        ComparisonUtils.assertXmlEquals(
                new ByteArrayInputStream(xiidm(expected).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new ByteArrayInputStream(xiidm(actual).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private void assertSplitLoadingEqualsFileImport(ReadOnlyDataSource main, ReadOnlyDataSource boundary) {
        Properties p = params();
        Network fromFiles = Network.read(main, p);

        TripleStore store = load(main, boundary, p);
        try {
            Network fromStore = TripleStoreNetworkLoader.load(store, NetworkFactory.findDefault(), p, ReportNode.NO_OP);
            assertSameNetwork(fromFiles, fromStore);
        } finally {
            store.close();
        }
    }

    @Test
    void microGridBaseCaseBE() {
        assertSplitLoadingEqualsFileImport(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource(), null);
    }

    @Test
    void miniBusBranch() {
        assertSplitLoadingEqualsFileImport(CgmesConformity1Catalog.miniBusBranch().dataSource(), null);
    }

    @Test
    void miniNodeBreaker() {
        assertSplitLoadingEqualsFileImport(CgmesConformity1Catalog.miniNodeBreaker().dataSource(), null);
    }

    @Test
    void cgmes3MicroGrid() {
        assertSplitLoadingEqualsFileImport(Cgmes3Catalog.microGrid().dataSource(), null);
    }

    @Test
    void describeReadsTheCimNamespaceTheBaseNameAndTheContexts() {
        Properties p = params();
        ReadOnlyDataSource ds = CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
        TripleStore store = load(ds, null, p);
        try {
            TripleStoreNetworkLoader.StoreContent content = TripleStoreNetworkLoader.describe(store);
            assertEquals(CgmesNamespace.CIM_16_NAMESPACE, content.cimNamespace());
            assertEquals(CgmesModel.baseName(ds), content.baseName());
            assertTrue(content.contextNames().stream().anyMatch(n -> n.contains("_EQ")));
            assertTrue(content.contextNames().stream().anyMatch(n -> n.contains("_EQ_BD")));
            assertEquals(store.contextNames().size(), content.contextNames().size());
        } finally {
            store.close();
        }
    }

    @Test
    void describeOfCgmes3ReadsTheCim100Namespace() {
        Properties p = params();
        TripleStore store = load(Cgmes3Catalog.microGrid().dataSource(), null, p);
        try {
            assertEquals(CgmesNamespace.CIM_100_NAMESPACE, TripleStoreNetworkLoader.describe(store).cimNamespace());
        } finally {
            store.close();
        }
    }

    @Test
    void describeOfAnEmptyStoreIsAnError() {
        TripleStore store = TripleStoreFactory.create(new TripleStoreOptions());
        try {
            assertThrows(CgmesModelException.class, () -> TripleStoreNetworkLoader.describe(store));
        } finally {
            store.close();
        }
    }

    @Test
    void updateThroughTheStoreEqualsUpdateThroughTheDataSource() {
        Properties p = params();
        ReadOnlyDataSource main = CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();

        Network throughDataSource = Network.read(main, p);
        Network throughStore = Network.read(main, p);

        // The update data source is the SSH file of the fixture on its own. Handing the whole model to an update
        // would let the update queries see the EQ graphs too, which the update catalog is not written for.
        ReadOnlyDataSource updateDs = new ResourceDataSource("MicroGridTestConfiguration_BC_BE_SSH_V2",
                new ResourceSet("/conformity/cas-1.1.3-data-4.0.3"
                        + "/MicroGrid/BaseCase/CGMES_v2.4.15_MicroGridTestConfiguration_BC_BE_v2/",
                        "MicroGridTestConfiguration_BC_BE_SSH_V2.xml"));
        throughDataSource.update(updateDs, p);

        CgmesImport importer = new CgmesImport(PlatformConfig.defaultConfig());
        TripleStore updateStore = TripleStoreFactory.create(importer.tripleStoreOptions(p));
        try {
            CgmesTripleStoreLoader.load(updateDs, null, updateStore, ReportNode.NO_OP);
            TripleStoreNetworkLoader.update(throughStore, updateStore, p, ReportNode.NO_OP);
        } finally {
            updateStore.close();
        }
        assertSameNetwork(throughDataSource, throughStore);
    }
}
