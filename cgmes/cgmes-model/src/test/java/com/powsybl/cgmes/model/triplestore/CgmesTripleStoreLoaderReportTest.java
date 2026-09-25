/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.model.triplestore;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reading files concurrently must not report concurrently.
 *
 * <p>A {@link ReportNode} keeps its children in a plain list, so a reader thread adding a "file read" entry would
 * race with the other three of the default upload pool: entries silently lost, or an index out of bounds. Every
 * file is therefore reported on the calling thread, before any task is submitted. The tests of this project pass
 * {@link ReportNode#NO_OP}, which is exactly why this needs a test of its own.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesTripleStoreLoaderReportTest {

    private static ReadOnlyDataSource main() {
        return new ResourceDataSource("tiny", new ResourceSet("/tsloader/", "tiny_EQ.xml", "tiny_SSH.xml"));
    }

    private static ReadOnlyDataSource boundary() {
        return new ResourceDataSource("tinybd",
                new ResourceSet("/tsloaderbd/", "tinybd_EQ_BD.xml", "tinybd_TP_BD.xml"));
    }

    private static ReportNode root() {
        return ReportNode.newRootReportNode()
                .withResourceBundles("com.powsybl.commons.reports")
                .withMessageTemplate("core.cgmes.model.CGMESFileRead")
                .build();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void everyFileIsReportedExactlyOnce(int parallelism) {
        TripleStore store = TripleStoreFactory.create(new TripleStoreOptions());
        ReportNode reportNode = root();
        try {
            CgmesTripleStoreLoader.load(main(), boundary(), store, parallelism, reportNode);
            assertEquals(4, reportNode.getChildren().size());
            assertThat(reportNode.getChildren().stream()
                    .map(child -> child.getValue("instanceFile").orElseThrow().toString()).toList())
                    .containsExactlyInAnyOrder("tiny_EQ.xml", "tiny_SSH.xml",
                            "tinybd_EQ_BD.xml", "tinybd_TP_BD.xml");
        } finally {
            store.close();
        }
    }

    @Test
    void manyConcurrentReadsStillReportEveryFile() {
        // Four threads on four files is the shape the default upload parallelism produces
        TripleStore store = TripleStoreFactory.create(new TripleStoreOptions());
        ReportNode reportNode = root();
        try {
            for (int i = 0; i < 20; i++) {
                CgmesTripleStoreLoader.load(main(), null, store, 4, reportNode);
            }
            assertEquals(40, reportNode.getChildren().size());
        } finally {
            store.close();
        }
    }
}
