/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.export.EventCompactor.CompactedChanges;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios.Scenario;
import com.powsybl.cgmes.extensions.CgmesMetadataModels;
import com.powsybl.cgmes.model.CgmesMetadataModel;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the difference model export that its public API does not reach: the safety net on the previous
 * values, and the profile wiring of a change set that touches the equipment model, for which no mapping exists yet.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesDiffExportInternalsTest {

    private static final CgmesSubset SSH = CgmesSubset.STEADY_STATE_HYPOTHESIS;
    private static final CgmesSubset EQ = CgmesSubset.EQUIPMENT;

    /**
     * Previous values that are legitimately never read, because the profile cannot express the attribute they belong
     * to in the state it is in.
     *
     * <p>The setpoint of the mode a regulating equipment is not in has no property in the steady state hypothesis:
     * the single target of a RegulatingControl means what the CGMES mode says, and the CGMES import resets the
     * inactive setpoint of a converter to zero. A change of it is therefore exported as the object description that
     * does change, without a statement of its own. The DC voltage target of a converter controlling its active
     * power is the same case: {@code ACDCConverter.targetUdc} is zero while the converter is in that mode.</p>
     */
    private static final Set<String> INACTIVE_SETPOINT_KEYS = Set.of(
            "StaticVarCompensator-V.reactivePowerSetpoint",
            "StaticVarCompensator-Q.voltageSetpoint",
            "CSC_1_1.targetVdc");

    static List<Scenario> scenarios() {
        return RecordedChangeScenarios.all();
    }

    /**
     * Every recorded change whose forward description reached the difference has to have had its previous value
     * read, otherwise the reverse statements silently repeat the new state.
     */
    /**
     * Every entry of {@link #INACTIVE_SETPOINT_KEYS} has to be produced by a scenario, otherwise the exception list
     * silently grants an exemption nothing checks any more.
     */
    @Test
    void everyInactiveSetpointExceptionIsExercised() {
        Set<String> unconsumed = new HashSet<>();
        for (Scenario scenario : RecordedChangeScenarios.all()) {
            unconsumed.addAll(unconsumedKeysOf(scenario));
        }
        assertEquals(INACTIVE_SETPOINT_KEYS, unconsumed);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void everyExportedChangeHadItsPreviousValueRead(Scenario scenario) {
        Set<String> unconsumed = new HashSet<>(unconsumedKeysOf(scenario));
        unconsumed.removeAll(INACTIVE_SETPOINT_KEYS);
        assertEquals(Set.of(), unconsumed, scenario.name());
    }

    private static Set<String> unconsumedKeysOf(Scenario scenario) {
        Network network = scenario.load();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, scenario.forwardChange());
        CompactedChanges changes = EventCompactor.compact(events, network.getVariantManager().getWorkingVariantId());
        DifferenceModelBuilder builder = new DifferenceModelBuilder(network, new CgmesExportContext(network), changes,
                new CgmesDiffExport.ExportOptions());
        builder.build();
        return builder.previousState().unconsumedKeys();
    }

    /**
     * A change set that touches the equipment model as well as the steady state hypothesis produces two differences,
     * and the steady state one applies on top of the equipment one rather than on the equipment model it came from.
     */
    @Test
    void sshDependsOnEqDifferenceWhenBothAreTouched() {
        Network network = com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources(
                "/update/load/", "load_EQ.xml", "load_SSH.xml");
        String sourceEquipmentId = sourceModelId(network, EQ);
        assertTrue(sourceEquipmentId != null, "the fixture is expected to carry an equipment model identifier");

        // The fixtures declare no dependency on their equipment model, which a real CGMES SSH does, so the
        // dependency this test is about is stated explicitly
        CgmesDiffExport.ExportOptions options = new CgmesDiffExport.ExportOptions();
        options.header(SSH).addDependentOn(sourceEquipmentId);
        CgmesExportContext context = new CgmesExportContext(network);
        DifferenceModelBuilder builder = new DifferenceModelBuilder(network, context,
                EventCompactor.compact(List.of(), null), options);

        // No mapping produces equipment statements yet, so the two buffers are built by hand
        CgmesPropertyBuffer after = new CgmesPropertyBuffer();
        after.mergeFrom(CgmesPropertyBuffer.newUpdates("ConformLoad", "EnergyConsumer")
                .value("EnergyConsumer.p", 12.5)
                .object(EQ, "ConformLoad", "EnergyConsumer")
                .value("EnergyConsumer.pfixed", 2.0)
                .updates());
        CgmesPropertyBuffer before = new CgmesPropertyBuffer();
        before.mergeFrom(CgmesPropertyBuffer.newUpdates("ConformLoad", "EnergyConsumer")
                .value("EnergyConsumer.p", 10.0)
                .object(EQ, "ConformLoad", "EnergyConsumer")
                .value("EnergyConsumer.pfixed", 1.0)
                .updates());

        CgmesDiffExport.Result result = builder.buildFrom(after, before, List.of());
        assertEquals(List.of(EQ, SSH), List.copyOf(result.differences().subsets()));
        DifferenceModel equipment = result.differences().get(EQ).orElseThrow();
        DifferenceModel steadyState = result.differences().get(SSH).orElseThrow();
        assertTrue(steadyState.header().dependentOn().contains(equipment.header().id()),
                () -> "expected the steady state difference to depend on " + equipment.header().id()
                        + ", was " + steadyState.header().dependentOn());
        assertTrue(!steadyState.header().dependentOn().contains(sourceEquipmentId));
    }

    /** With the dependencies cleared, the steady state difference keeps whatever the caller gave it. */
    @Test
    void clearedDependenciesAreNotRedirected() {
        Network network = com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources(
                "/update/load/", "load_EQ.xml", "load_SSH.xml");
        CgmesDiffExport.ExportOptions options = new CgmesDiffExport.ExportOptions();
        options.header(SSH).clearDependencies().addDependentOn("urn:uuid:explicit");

        DifferenceModelBuilder builder = new DifferenceModelBuilder(network, new CgmesExportContext(network),
                EventCompactor.compact(List.of(), null), options);
        CgmesPropertyBuffer after = new CgmesPropertyBuffer();
        after.mergeFrom(CgmesPropertyBuffer.newUpdates("ConformLoad", "EnergyConsumer").value("EnergyConsumer.p", 12.5)
                .object(EQ, "ConformLoad", "EnergyConsumer").value("EnergyConsumer.pfixed", 2.0).updates());
        CgmesPropertyBuffer before = new CgmesPropertyBuffer();
        before.mergeFrom(CgmesPropertyBuffer.newUpdates("ConformLoad", "EnergyConsumer").value("EnergyConsumer.p", 10.0)
                .object(EQ, "ConformLoad", "EnergyConsumer").value("EnergyConsumer.pfixed", 1.0).updates());

        CgmesDiffExport.Result result = builder.buildFrom(after, before, List.of());
        assertEquals(List.of("urn:uuid:explicit"), result.differences().get(SSH).orElseThrow().header().dependentOn());
    }

    private static String sourceModelId(Network network, CgmesSubset subset) {
        CgmesMetadataModels models = network.getExtension(CgmesMetadataModels.class);
        return Optional.ofNullable(models)
                .flatMap(m -> m.getModelForSubset(subset))
                .map(CgmesMetadataModel::getId)
                .orElse(null);
    }
}
