/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.diff.CgmesDiffNotApplicableException;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.model.CgmesModelException;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.datasource.DirectoryDataSource;
import com.powsybl.commons.datasource.GenericReadOnlyDataSource;
import com.powsybl.commons.datasource.ZipArchiveDataSource;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code network.update(dataSource)} has to notice a difference model on its own.
 *
 * <p>A caller that receives CGMES files does not want to inspect them before deciding which API to call, and
 * pypowsybl's {@code update_from_file} has no place to say it either. The importer therefore sniffs the files and
 * routes them, which is what this class pins &mdash; including that a data source mixing the two kinds is refused
 * rather than applied in an order nothing defines.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesDiffUpdateDetectionTest {

    private static final String LOAD_DIR = "/update/load/";
    private static final String[] LOAD_FILES = {"load_EQ.xml", "load_SSH.xml"};
    private static final String LOAD = "EnergyConsumer";

    private static Network load() {
        return readCgmesResources(LOAD_DIR, LOAD_FILES);
    }

    private static List<NetworkEvent> change(Network sender, Consumer<Network> change) {
        return RecordedChangeScenarios.record(sender, change);
    }

    private static Properties previousValues() {
        Properties parameters = new Properties();
        parameters.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");
        return parameters;
    }

    /** Write the difference model of a load change into a directory, as a producer would hand it over. */
    private static void writeDifference(Path directory, String baseName, Network sender) throws IOException {
        List<NetworkEvent> events = change(sender, n -> n.getLoad(LOAD).setP0(12.5));
        String xml = CgmesDiffExport.toString(sender, events, null,
                PartialSshExport.UnsupportedChangeBehavior.FAIL);
        Files.writeString(directory.resolve(baseName + "_SSH_DIFF.xml"), xml, StandardCharsets.UTF_8);
    }

    @Test
    void updateAcceptsADifferenceModelDataSource(@TempDir Path tempDir) throws IOException {
        writeDifference(tempDir, "x", load());
        Network receiver = load();

        // No parameter at all: the data source alone says what it holds
        receiver.update(new GenericReadOnlyDataSource(tempDir, "x"));

        assertEquals(12.5, receiver.getLoad(LOAD).getP0(), 1e-9);
    }

    @Test
    void updateAcceptsAZipOfDifferenceModels(@TempDir Path tempDir) throws IOException {
        Network sender = load();
        List<NetworkEvent> events = change(sender, n -> n.getLoad(LOAD).setP0(12.5));
        Path archive = tempDir.resolve("diff.zip");
        CgmesDiffExport.write(sender, events, new ZipArchiveDataSource(archive), "case",
                new CgmesDiffExport.ExportOptions()
                        .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL));
        Network receiver = load();
        receiver.update(new ZipArchiveDataSource(archive));
        assertEquals(12.5, receiver.getLoad(LOAD).getP0(), 1e-9);
    }

    @Test
    void mixingFullAndDifferenceFilesIsRejected(@TempDir Path tempDir) throws IOException {
        writeDifference(tempDir, "x", load());
        try (OutputStream out = Files.newOutputStream(tempDir.resolve("x_EQ.xml"));
             var in = CgmesDiffUpdateDetectionTest.class.getResourceAsStream(LOAD_DIR + "load_EQ.xml")) {
            in.transferTo(out);
        }
        Network receiver = load();
        DirectoryDataSource dataSource = new DirectoryDataSource(tempDir, "x");

        CgmesModelException e = assertThrows(CgmesModelException.class, () -> receiver.update(dataSource));
        assertTrue(e.getMessage().contains("mixes difference models"), e.getMessage());
        assertEquals(10.0, receiver.getLoad(LOAD).getP0(), 1e-9);
    }

    @Test
    void partialSshUpdateIsUnaffected(@TempDir Path tempDir) throws IOException {
        Network sender = load();
        List<NetworkEvent> events = change(sender, n -> n.getLoad(LOAD).setP0(12.5));
        try (OutputStream out = Files.newOutputStream(tempDir.resolve("x_SSH.xml"))) {
            PartialSshExport.write(sender, events, out, new PartialSshExport.ExportOptions()
                    .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL));
        }
        Network receiver = load();
        receiver.update(new DirectoryDataSource(tempDir, "x"), previousValues());
        assertEquals(12.5, receiver.getLoad(LOAD).getP0(), 1e-9);
    }

    @Test
    void parametersReachTheDiffImport(@TempDir Path tempDir) throws IOException {
        Network sender = load();
        List<NetworkEvent> events = change(sender, n -> n.getLoad(LOAD).setP0(12.5));
        CgmesDiffExport.ExportOptions options = new CgmesDiffExport.ExportOptions()
                .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL);
        options.header(CgmesSubset.STEADY_STATE_HYPOTHESIS)
                .setSupersedePreviousModel(false)
                .addSupersedes("urn:uuid:some-other-model");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        CgmesDiffExport.write(sender, events, bytes, CgmesSubset.STEADY_STATE_HYPOTHESIS, options);
        String xml = bytes.toString(StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("x_SSH_DIFF.xml"), xml, StandardCharsets.UTF_8);

        Network strict = load();
        DirectoryDataSource dataSource = new DirectoryDataSource(tempDir, "x");
        assertThrows(CgmesDiffNotApplicableException.class,
            () -> strict.update(dataSource));

        Network lenient = load();
        Properties parameters = new Properties();
        parameters.put(CgmesImport.DIFF_CHECK_SUPERSEDES, "false");
        lenient.update(dataSource, parameters);
        assertEquals(12.5, lenient.getLoad(LOAD).getP0(), 1e-9);
    }

    @Test
    void twoDifferenceModelsOfOneProfileAreRejected(@TempDir Path tempDir) throws IOException {
        writeDifference(tempDir, "x", load());
        Files.copy(tempDir.resolve("x_SSH_DIFF.xml"), tempDir.resolve("x_SSH_DIFF_2.xml"));
        Network receiver = load();
        DirectoryDataSource dataSource = new DirectoryDataSource(tempDir, "x");

        CgmesModelException e = assertThrows(CgmesModelException.class, () -> receiver.update(dataSource));
        assertTrue(e.getMessage().contains("Supersedes order"), e.getMessage());
    }
}
