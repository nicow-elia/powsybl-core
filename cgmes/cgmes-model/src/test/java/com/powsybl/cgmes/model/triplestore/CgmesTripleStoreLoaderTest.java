/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.model.triplestore;

import com.powsybl.cgmes.model.CgmesModelException;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.datasource.ResourceDataSource;
import com.powsybl.commons.datasource.ResourceSet;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.triplestore.api.TripleStore;
import com.powsybl.triplestore.api.TripleStoreFactory;
import com.powsybl.triplestore.api.TripleStoreOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CgmesTripleStoreLoader} reads CGMES files into a triple store and nothing more.
 *
 * <p>The properties pinned here are the ones every consumer of the loader relies on: the named graphs are the file
 * names, the base URI and the CIM namespace are the ones the files declare, the boundary is read only when the main
 * data source does not carry one, and reading the files concurrently produces exactly the same store as reading
 * them one by one.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesTripleStoreLoaderTest {

    private static ReadOnlyDataSource main() {
        return new ResourceDataSource("tiny", new ResourceSet("/tsloader/", "tiny_EQ.xml", "tiny_SSH.xml"));
    }

    private static ReadOnlyDataSource boundary() {
        return new ResourceDataSource("tinybd", new ResourceSet("/tsloaderbd/", "tinybd_EQ_BD.xml", "tinybd_TP_BD.xml"));
    }

    private static ReadOnlyDataSource mainWithBoundary() {
        return new ResourceDataSource("tiny",
                new ResourceSet("/tsloader/", "tiny_EQ.xml", "tiny_SSH.xml"),
                new ResourceSet("/tsloaderbd/", "tinybd_EQ_BD.xml", "tinybd_TP_BD.xml"));
    }

    private static TripleStore store() {
        return TripleStoreFactory.create(new TripleStoreOptions());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void loadsEveryFileAsItsOwnContext(int parallelism) {
        TripleStore store = store();
        try {
            CgmesTripleStoreLoader.Result result = CgmesTripleStoreLoader.load(main(), null, store, parallelism, ReportNode.NO_OP);

            assertEquals(CgmesNamespace.CIM_16_NAMESPACE, result.cimNamespace());
            assertEquals("http://tsloader", result.baseName());
            assertThat(result.contextNames())
                    .containsExactlyInAnyOrder("contexts:tiny_EQ.xml", "contexts:tiny_SSH.xml");
            assertFalse(result.boundaryLoaded());
            assertEquals(Set.of("contexts:tiny_EQ.xml", "contexts:tiny_SSH.xml"), store.contextNames());
        } finally {
            store.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void readsTheBoundaryWhenTheMainDataSourceHasNone(int parallelism) {
        TripleStore store = store();
        try {
            CgmesTripleStoreLoader.Result result = CgmesTripleStoreLoader.load(main(), boundary(), store, parallelism, ReportNode.NO_OP);

            assertTrue(result.boundaryLoaded());
            assertThat(result.contextNames()).containsExactlyInAnyOrder("contexts:tiny_EQ.xml",
                    "contexts:tiny_SSH.xml", "contexts:tinybd_EQ_BD.xml", "contexts:tinybd_TP_BD.xml");
            // The base name stays the one of the main data source, as in a file import
            assertEquals("http://tsloader", result.baseName());
        } finally {
            store.close();
        }
    }

    @Test
    void doesNotReadTheBoundaryWhenTheMainDataSourceCarriesOne() {
        TripleStore store = store();
        try {
            CgmesTripleStoreLoader.Result result = CgmesTripleStoreLoader.load(mainWithBoundary(), boundary(), store, ReportNode.NO_OP);

            assertFalse(result.boundaryLoaded());
            assertEquals(4, result.contextNames().size());
            assertEquals(4, store.contextNames().size());
        } finally {
            store.close();
        }
    }

    @Test
    void parallelAndSequentialLoadsAgree() {
        TripleStore sequential = store();
        TripleStore parallel = store();
        try {
            CgmesTripleStoreLoader.Result r1 = CgmesTripleStoreLoader.load(mainWithBoundary(), null, sequential, 1, ReportNode.NO_OP);
            CgmesTripleStoreLoader.Result r2 = CgmesTripleStoreLoader.load(mainWithBoundary(), null, parallel, 4, ReportNode.NO_OP);

            assertEquals(r1.cimNamespace(), r2.cimNamespace());
            assertEquals(r1.baseName(), r2.baseName());
            assertEquals(r1.boundaryLoaded(), r2.boundaryLoaded());
            assertThat(r1.contextNames()).containsExactlyInAnyOrderElementsOf(r2.contextNames());
            assertEquals(sequential.contextNames(), parallel.contextNames());
            String allStatements = "SELECT ?s ?p ?o WHERE { GRAPH ?graph { ?s ?p ?o }}";
            assertEquals(sequential.query(allStatements).size(), parallel.query(allStatements).size());
        } finally {
            sequential.close();
            parallel.close();
        }
    }

    @Test
    void rejectsAnImpossibleParallelism() {
        TripleStore store = store();
        try {
            ReadOnlyDataSource ds = main();
            assertThrows(IllegalArgumentException.class,
                    () -> CgmesTripleStoreLoader.load(ds, null, store, 0, ReportNode.NO_OP));
        } finally {
            store.close();
        }
    }

    @Test
    void reportsADataSourceWithoutCgmesData() {
        TripleStore store = store();
        try {
            ReadOnlyDataSource ds = new ResourceDataSource("incomplete", new ResourceSet("/", "validRdfInvalidContent_EQ.xml"));
            assertThrows(CgmesModelException.class, () -> CgmesTripleStoreLoader.load(ds, null, store, ReportNode.NO_OP));
        } finally {
            store.close();
        }
    }
}
