/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.diff.FastRoutePlan.PlannedModel;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios;
import com.powsybl.cgmes.model.CgmesModel;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.DifferenceModelWriter;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.triplestore.api.PropertyBag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The synthetic instance file the fast route builds, which is what makes a difference model update literally a
 * partial steady state hypothesis update.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class DiffUpdateStoreBuilderTest {

    private static final String LOAD_DIR = "/update/load/";
    private static final String[] LOAD_FILES = {"load_EQ.xml", "load_SSH.xml"};

    private static FastRoutePlan planOf(Network receiver, Network sender, Consumer<Network> change) {
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender, change);
        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(sender, events,
                new CgmesDiffExport.ExportOptions()
                        .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL));
        List<DifferenceModel> models = new java.util.ArrayList<>();
        result.differences().models().values()
                .forEach(model -> models.add(DifferenceModelParser.parse(DifferenceModelWriter.toString(model))));
        return FastRoutePlan.of(receiver, new DifferenceModelSet(models), new CgmesDiffImport.Options(), false);
    }

    @Test
    void theDocumentLooksLikeAPartialSteadyStateHypothesisFile() {
        Network sender = readCgmesResources(LOAD_DIR, LOAD_FILES);
        Network receiver = readCgmesResources(LOAD_DIR, LOAD_FILES);
        FastRoutePlan plan = planOf(receiver, sender, n -> n.getLoad("EnergyConsumer").setP0(12.5));
        PlannedModel model = plan.models().get(0);
        String document = DiffUpdateStoreBuilder.updateDocumentAsString(model.header().cimNamespace(), model);

        assertTrue(document.contains("<md:FullModel"), document);
        assertTrue(document.contains("<cim:EnergyConsumer rdf:about=\"#_EnergyConsumer\">"), document);
        assertTrue(document.contains("<cim:EnergyConsumer.p>12.5</cim:EnergyConsumer.p>"), document);
        // The whole consistency group is written, not only the changed property
        assertTrue(document.contains("<cim:EnergyConsumer.q>"), document);
        assertTrue(!document.contains("dm:DifferenceModel"), document);
    }

    /**
     * The central design claim of the fast route, asserted rather than described: the document the importer builds
     * is the partial steady state hypothesis file of the same change.
     *
     * <p>Only the model description differs, because the two name different models, so both {@code md:FullModel}
     * blocks are stripped before the comparison; whitespace is normalised because the two writers indent
     * independently.</p>
     */
    @Test
    void theDocumentIsThePartialSteadyStateHypothesisOfTheSameChange() {
        Network sender = readCgmesResources(LOAD_DIR, LOAD_FILES);
        Network receiver = readCgmesResources(LOAD_DIR, LOAD_FILES);
        Consumer<Network> change = n -> n.getLoad("EnergyConsumer").setP0(12.5);

        FastRoutePlan plan = planOf(receiver, sender, change);
        PlannedModel model = plan.models().get(0);
        String synthetic = DiffUpdateStoreBuilder.updateDocumentAsString(model.header().cimNamespace(), model);

        Network partialSender = readCgmesResources(LOAD_DIR, LOAD_FILES);
        List<NetworkEvent> events = RecordedChangeScenarios.record(partialSender, change);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PartialSshExport.write(partialSender, events, bytes, new PartialSshExport.ExportOptions()
                .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL));
        String partial = bytes.toString(StandardCharsets.UTF_8);

        assertEquals(withoutModelDescription(partial), withoutModelDescription(synthetic));
    }

    /** A document without its {@code md:FullModel} block and without insignificant whitespace. */
    private static String withoutModelDescription(String document) {
        return document.replaceAll("(?s)<md:FullModel.*?</md:FullModel>", "")
                .replaceAll(">\\s+<", "><")
                .replaceAll("\\s+", " ")
                .trim();
    }

    @Test
    void theContextNameIsRecognizedAsTheProfileOfTheDifference() {
        assertTrue(CgmesSubset.STEADY_STATE_HYPOTHESIS
                .isValidName(DiffUpdateStoreBuilder.contextName(CgmesSubset.STEADY_STATE_HYPOTHESIS)));
        assertTrue(CgmesSubset.EQUIPMENT.isValidName(DiffUpdateStoreBuilder.contextName(CgmesSubset.EQUIPMENT)));
    }

    /**
     * The configuration a caller hands over must come back unchanged, because a difference model update needs the
     * previous-value flag on and a caller may be using the same object elsewhere at the same time.
     */
    @Test
    void theCallersConfigurationIsNeitherChangedNorShared() {
        Conversion.Config config = new Conversion.Config()
                .setUsePreviousValuesDuringUpdate(false)
                .setUseDetailedDcModel(true)
                .setCreateFictitiousVoltageLevelsForEveryNode(false);
        config.setImportControlAreas(false);

        Conversion.Config copy = config.copy();
        assertEquals(config.usePreviousValuesDuringUpdate(), copy.usePreviousValuesDuringUpdate());
        assertEquals(config.getUseDetailedDcModel(), copy.getUseDetailedDcModel());
        assertEquals(config.getCreateFictitiousVoltageLevelsForEveryNode(),
                copy.getCreateFictitiousVoltageLevelsForEveryNode());
        assertEquals(config.importControlAreas(), copy.importControlAreas());
        assertEquals(config.getRemovePropertiesAndAliasesAfterImport(), copy.getRemovePropertiesAndAliasesAfterImport());
        copy.setUsePreviousValuesDuringUpdate(true);
        assertTrue(!config.usePreviousValuesDuringUpdate(), "the copy has to be independent");

        Network sender = readCgmesResources(LOAD_DIR, LOAD_FILES);
        Network receiver = readCgmesResources(LOAD_DIR, LOAD_FILES);
        List<DifferenceModel> models = new java.util.ArrayList<>();
        CgmesDiffExport.toDifferences(sender,
                        RecordedChangeScenarios.record(sender, n -> n.getLoad("EnergyConsumer").setP0(12.5)),
                        new CgmesDiffExport.ExportOptions()
                                .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL))
                .differences().models().values()
                .forEach(model -> models.add(DifferenceModelParser.parse(DifferenceModelWriter.toString(model))));

        CgmesDiffImport.apply(receiver, new DifferenceModelSet(models), config,
                new CgmesDiffImport.Options(), ReportNode.NO_OP);
        assertEquals(12.5, receiver.getLoad("EnergyConsumer").getP0(), 1e-9);
        assertTrue(!config.usePreviousValuesDuringUpdate(),
                "applying a difference must not flip a flag on the caller's configuration");
    }

    @Test
    void theStoreAnswersTheUpdateQueries() {
        Network sender = readCgmesResources(LOAD_DIR, LOAD_FILES);
        Network receiver = readCgmesResources(LOAD_DIR, LOAD_FILES);
        FastRoutePlan plan = planOf(receiver, sender, n -> n.getLoad("EnergyConsumer").setP0(12.5));
        CgmesModel cgmes = DiffUpdateStoreBuilder.build(plan, ReportNode.NO_OP);
        try {
            assertEquals(1, cgmes.fullModels().size());
            assertEquals(plan.models().get(0).identity().id(), cgmes.fullModels().get(0).getId("FullModel"));

            List<PropertyBag> consumers = cgmes.energyConsumers();
            assertEquals(1, consumers.size());
            assertEquals("EnergyConsumer", consumers.get(0).getId("EnergyConsumer"));
            assertEquals(12.5, consumers.get(0).asDouble("p"), 1e-9);
            // The receiver's own reactive power was completed into the document, so the query matches at all
            assertTrue(!Double.isNaN(consumers.get(0).asDouble("q")));
        } finally {
            cgmes.close();
        }
    }
}
