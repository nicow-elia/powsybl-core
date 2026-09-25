/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.export.PartialSshExport.UnsupportedChangeBehavior;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkEventRecorder;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exporting the changes of <em>one variant</em> of a network.
 *
 * <p>Two things are being asserted. That naming a variant really selects it &mdash; the values written are that
 * variant's values, the changes of another variant are dropped rather than refused, and the working variant of the
 * calling thread comes back afterwards &mdash; and that a change IIDM does not store per variant can be turned into
 * an unsupported change, which is what an export writing the history of a single snapshot needs.</p>
 *
 * <p>Both are off by default, and the 39 partial steady state hypothesis golden files and the difference model
 * golden files of the other tests are what pins that: they are produced by the same code with no variant named.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesDiffExportVariantTest {

    private static final String LOAD = "1c6beed6-1acf-42e7-ba55-0cc9f04bddd8";
    private static final String OTHER = "other";

    private static Network twoVariants() {
        Network network = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, OTHER);
        return network;
    }

    private static List<NetworkEvent> record(Network network, Consumer<Network> change) {
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        network.addListener(recorder);
        try {
            change.accept(network);
        } finally {
            network.removeListener(recorder);
        }
        return List.copyOf(recorder.getEvents());
    }

    /**
     * A change of the other variant is not part of this export, and the working variant is restored.
     */
    @Test
    void namingAVariantSelectsItsChangesAndItsValues() {
        Network network = twoVariants();
        List<NetworkEvent> events = new java.util.ArrayList<>();

        network.getVariantManager().setWorkingVariant(OTHER);
        events.addAll(record(network, n -> n.getLoad(LOAD).setP0(111.0)));
        network.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        events.addAll(record(network, n -> n.getLoad(LOAD).setP0(222.0)));

        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, events,
                new CgmesDiffExport.ExportOptions().setVariant(OTHER));

        assertEquals(VariantManagerConstants.INITIAL_VARIANT_ID,
                network.getVariantManager().getWorkingVariantId(),
                "the export has to put the working variant back");
        assertEquals(1, result.exportedEvents().size(), "only the change of the selected variant is exported");
        DifferenceModel ssh = result.differences().get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
        assertEquals("111", valueOf(ssh, "EnergyConsumer.p"),
                "the value written is the value of the selected variant");
    }

    /** Without a variant the export describes the working one, exactly as it always did. */
    @Test
    void withoutAVariantTheWorkingOneIsExported() {
        Network network = twoVariants();
        network.getVariantManager().setWorkingVariant(OTHER);
        List<NetworkEvent> events = record(network, n -> n.getLoad(LOAD).setP0(111.0));

        DifferenceModel ssh = CgmesDiffExport
                .toDifferences(network, events, new CgmesDiffExport.ExportOptions())
                .differences().get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
        assertEquals("111", valueOf(ssh, "EnergyConsumer.p"));
        assertEquals(OTHER, network.getVariantManager().getWorkingVariantId());
    }

    /**
     * A change of a value IIDM keeps once per network cannot be written into the history of one variant.
     */
    @Test
    void aSharedChangeIsUnsupportedUnderFail() {
        Network network = twoVariants();
        network.getVariantManager().setWorkingVariant(OTHER);
        String line = network.getLineStream().map(Line::getId).sorted().findFirst().orElseThrow();
        List<NetworkEvent> events = record(network, n -> {
            n.getLoad(LOAD).setP0(111.0);
            n.getLine(line).setR(n.getLine(line).getR() + 1.0);
        });

        PowsyblException failure = assertThrows(PowsyblException.class, () -> CgmesDiffExport.toDifferences(
                network, events, new CgmesDiffExport.ExportOptions()
                        .setVariant(OTHER).setRejectSharedChanges(true)));
        assertTrue(failure.getMessage().contains("not stored per variant in IIDM"), failure.getMessage());
    }

    @Test
    void aSharedChangeIsDroppedUnderIgnore() {
        Network network = twoVariants();
        network.getVariantManager().setWorkingVariant(OTHER);
        String lineId = network.getLineStream().map(Line::getId).sorted().findFirst().orElseThrow();
        List<NetworkEvent> events = record(network, n -> {
            n.getLoad(LOAD).setP0(111.0);
            n.getLine(lineId).setR(n.getLine(lineId).getR() + 1.0);
        });

        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, events,
                new CgmesDiffExport.ExportOptions()
                        .setVariant(OTHER)
                        .setRejectSharedChanges(true)
                        .setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE));

        assertEquals(1, result.exportedEvents().size());
        assertEquals(List.of(CgmesSubset.STEADY_STATE_HYPOTHESIS), List.copyOf(result.differences().subsets()));
    }

    /** The default is unchanged: a shared change is an ordinary equipment difference. */
    @Test
    void aSharedChangeIsExportedByDefault() {
        Network network = twoVariants();
        String lineId = network.getLineStream().map(Line::getId).sorted().findFirst().orElseThrow();
        List<NetworkEvent> events = record(network, n -> n.getLine(lineId).setR(n.getLine(lineId).getR() + 1.0));

        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, events,
                new CgmesDiffExport.ExportOptions());
        assertEquals(1, result.exportedEvents().size());
        assertTrue(result.differences().get(CgmesSubset.EQUIPMENT).isPresent());
    }

    /** The options a database export copies carry the variant and the rejection with them. */
    @Test
    void theOptionsCopyCarriesBoth() {
        CgmesDiffExport.ExportOptions copy = new CgmesDiffExport.ExportOptions()
                .setVariant(OTHER).setRejectSharedChanges(true)
                .setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE)
                .copy();
        assertEquals(OTHER, copy.getVariant());
        assertTrue(copy.isRejectSharedChanges());
        assertEquals(UnsupportedChangeBehavior.IGNORE, copy.getUnsupportedChangeBehavior());
    }

    /** The partial steady state hypothesis export selects a variant the same way. */
    @Test
    void thePartialSteadyStateExportSelectsAVariantToo() {
        Network network = twoVariants();
        List<NetworkEvent> events = new java.util.ArrayList<>();
        network.getVariantManager().setWorkingVariant(OTHER);
        events.addAll(record(network, n -> n.getLoad(LOAD).setP0(111.0)));
        network.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        events.addAll(record(network, n -> n.getLoad(LOAD).setP0(222.0)));

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        List<NetworkEvent> exported = PartialSshExport.write(network, events, out,
                new PartialSshExport.ExportOptions().setVariant(OTHER));

        assertEquals(1, exported.size());
        assertTrue(out.toString(java.nio.charset.StandardCharsets.UTF_8).contains(">111<"),
                "the partial SSH has to carry the value of the selected variant");
        assertEquals(VariantManagerConstants.INITIAL_VARIANT_ID,
                network.getVariantManager().getWorkingVariantId());
    }

    private static String valueOf(DifferenceModel model, String property) {
        return model.forward().stream()
                .filter(statement -> property.equals(statement.property()))
                .map(CgmesStatement::value)
                .findFirst().orElseThrow(() -> new AssertionError("no " + property + " in " + model.forward()));
    }

    /** The changes of a variant that no longer exists are simply not part of an export of another variant. */
    @Test
    void aLoadOfTheOtherVariantIsDroppedNotRefused() {
        Network network = twoVariants();
        network.getVariantManager().setWorkingVariant(OTHER);
        List<NetworkEvent> events = record(network, n -> n.getLoad(LOAD).setP0(111.0));
        network.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);

        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, events,
                new CgmesDiffExport.ExportOptions()
                        .setVariant(VariantManagerConstants.INITIAL_VARIANT_ID));
        assertEquals(List.of(), result.exportedEvents());
        assertEquals(List.of(), List.copyOf(result.differences().subsets()));

        Load load = network.getLoad(LOAD);
        assertEquals(load.getP0(), load.getP0());
    }
}
