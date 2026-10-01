/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.test.ConversionUtil;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.events.ExtensionUpdateNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.test.BatteryNetworkFactory;
import com.powsybl.iidm.network.test.FourSubstationsNodeBreakerFactory;
import com.powsybl.iidm.network.test.HvdcTestNetwork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The change mapping describes an object without an event (the change of each object family,
 * {@code CgmesChangeTranslator.*Updates}, which asks the object refusals and then the one describe function of the
 * family), and that description is the one the event path writes.
 *
 * <p>In {@link Scope#CHANGES}, for every object of every fixture of {@link ExportMappingEquivalenceTest}: every
 * statement the event path writes for a probe of the object (the probes of the guard, one per attribute the translator
 * matches on, extension probes included) is described with the same value, and a value both write is the same. So an
 * object the description refuses (a holder the import would give a regulation to, a generator in another mode than
 * the CGMES mode its import recorded) is refused by every path of the event mapping too, the extension probes
 * included. The description may say more than the event path where the event path writes a block only together with a
 * control it refuses.</p>
 *
 * <p>In {@link Scope#FULL_MODEL}, objects a change export refuses but a full export writes are described, and their
 * description equals what the full export writes: a generator without a recorded control (the battery network), a
 * generator without VoltageRegulation (four substations, {@code GTH1}), a converter station that does not regulate, an
 * EquivalentInjection regulating without regulation capability.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class DescribeObjectTest {

    private record Value(String className, String value) {
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.conversion.export.ExportMappingEquivalenceTest#fixtures")
    void theDescriptionIsWhatTheEventPathWrites(ExportMappingEquivalenceTest.Fixture fixture) {
        Network network = fixture.loader().get();
        CgmesExportContext context = new CgmesExportContext(network);
        // The controls the translator reads too, so that a TapChangerControl is described from the same index
        RegulatingControlFamily controls = new RegulatingControlFamily(network, context, Scope.CHANGES);
        CgmesChangeTranslator translator = new CgmesChangeTranslator(network, context,
                PartialSshExport.UnsupportedChangeBehavior.IGNORE, "a description test",
                EnumSet.of(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS), IidmStateView.LIVE, controls);
        String variantId = network.getVariantManager().getWorkingVariantId();
        List<String> problems = new ArrayList<>();
        for (Identifiable<?> identifiable : network.getIdentifiables()) {
            List<Result<CgmesPropertyBuffer, String>> descriptions = describe(translator, controls, identifiable);
            if (descriptions.isEmpty()) {
                continue;
            }
            Map<CgmesStatement.Key, Value> byDescription = statements(descriptions, context);
            for (String probe : ExportMappingEquivalenceTest.probes(identifiable)) {
                if (probe.contains("@")) {
                    continue; // a loading limit, equipment data the descriptions do not cover
                }
                if (translator.translate(event(identifiable, probe, variantId)) instanceof Result.Success(CgmesPropertyBuffer buffer)) {
                    for (CgmesStatement statement : buffer.statements(CgmesSubset.STEADY_STATE_HYPOTHESIS, context)) {
                        Value value = byDescription.get(statement.key());
                        if (!new Value(statement.className(), statement.value()).equals(value)) {
                            problems.add(identifiable.getId() + " " + probe + ": " + statement + " is described as " + value);
                        }
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> fixture.name() + ":\n" + String.join("\n", problems));
    }

    /** What a network written by a full export states about the named objects, and what the full model describes. */
    static List<Object[]> fullModels() {
        return List.of(
            new Object[] {"battery network, a generator without a recorded control",
                (Supplier<Network>) BatteryNetworkFactory::create, List.of("GEN")},
            new Object[] {"four substations, a generator without VoltageRegulation",
                (Supplier<Network>) FourSubstationsNodeBreakerFactory::create, List.of("GTH1", "GH1", "VSC1", "VSC2")},
            new Object[] {"converter station in voltage mode, not regulating", (Supplier<Network>) () -> {
                Network network = ConversionUtil.readCgmesResources("/update/hvdc/", "hvdc_EQ.xml", "hvdc_SSH.xml");
                HvdcLine line = network.getHvdcLine("DCLineSegment-Vsc");
                ((VscConverterStation) line.getConverterStation1()).getVoltageRegulation().setRegulating(false);
                return network;
            }, List.of("DCLineSegment-Vsc-VscConverter-1", "DCLineSegment-Vsc-VscConverter-2")},
            new Object[] {"EquivalentInjection regulating without regulation capability", (Supplier<Network>) () -> {
                Network network = ConversionUtil.readCgmesResources("/update/generator/", "generator_EQ.xml", "generator_SSH.xml");
                Generator generator = network.getGenerator("EquivalentInjection");
                generator.setLocalTargetV(400.0);
                generator.newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(true).build();
                return network;
            }, List.of("EquivalentInjection")});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fullModels")
    @SuppressWarnings("unchecked")
    void aFullModelDescribesWhatAChangeRefusesAsTheFullExportWritesIt(String name, Supplier<Network> loader, List<String> ids) {
        Network network = loader.get();
        CgmesExportContext context = new CgmesExportContext(network);
        CgmesChangeTranslator changes = new CgmesChangeTranslator(network, context,
                PartialSshExport.UnsupportedChangeBehavior.IGNORE);
        CgmesChangeTranslator fullModel = CgmesChangeTranslator.forFullModel(network, context);
        RegulatingControlFamily changeControls = new RegulatingControlFamily(network, context, Scope.CHANGES);
        RegulatingControlFamily fullModelControls = new RegulatingControlFamily(network, context, Scope.FULL_MODEL);
        Map<ExportMappingEquivalenceTest.Key, ExportMappingEquivalenceTest.Triple> fullExport =
                ExportMappingEquivalenceTest.parse(ExportMappingEquivalenceTest.fullSsh(network), context.getCim().getNamespace());
        boolean refusedAsChange = false;
        for (String id : ids) {
            Identifiable<?> identifiable = network.getIdentifiable(id);
            List<Result<CgmesPropertyBuffer, String>> descriptions = describe(fullModel, fullModelControls, identifiable);
            assertTrue(!descriptions.isEmpty() && descriptions.stream().allMatch(Result.Success.class::isInstance),
                    () -> id + " is not described by a full model: " + descriptions);
            refusedAsChange |= describe(changes, changeControls, identifiable).stream().anyMatch(Result.Failure.class::isInstance);
            Map<CgmesStatement.Key, Value> described = statements(descriptions, context);
            assertTrue(!described.isEmpty(), id);
            described.forEach((key, value) -> {
                ExportMappingEquivalenceTest.Triple written = fullExport.get(
                        new ExportMappingEquivalenceTest.Key(key.subjectId(), key.property()));
                assertTrue(written != null, () -> id + ": the full export does not write " + key + " = " + value);
                assertEquals(ExportMappingEquivalenceTest.comparable(written),
                        ExportMappingEquivalenceTest.comparable(new ExportMappingEquivalenceTest.Triple(key.subjectId(),
                                value.className(), key.property(), value.value(), written.kind())),
                        () -> id + " " + key);
            });
        }
        assertTrue(refusedAsChange, name + ": no object of the fixture is refused as a change, the fixture tests nothing");
    }

    /**
     * The generator of the matrix fixture without VoltageRegulation: its import gives it one on every update, so every
     * path refuses it, the reference priority of the extension included.
     */
    @Test
    void theExtensionPathOfARefusedHolderIsRefused() {
        Network network = ConversionUtil.readCgmesResources("/update/generator/", "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = network.getGenerator("SynchronousMachine");
        generator.removeVoltageRegulation();
        CgmesChangeTranslator translator = new CgmesChangeTranslator(network, new CgmesExportContext(network),
                PartialSshExport.UnsupportedChangeBehavior.IGNORE);
        String variantId = network.getVariantManager().getWorkingVariantId();
        for (String probe : ExportMappingEquivalenceTest.probes(generator)) {
            Result<CgmesPropertyBuffer, String> result = translator.translate(event(generator, probe, variantId));
            assertTrue(result instanceof Result.Failure(String reason)
                    && Refusal.of(reason).orElse(null) == Refusal.IMPORT_GIVES_REGULATION, () -> probe + ": " + result);
        }
    }

    /**
     * A converter station that belongs to no HVDC line has no setpoints to write and no end that rectifies: every
     * change of its control, and the station as a whole, is refused (no NullPointerException).
     */
    @Test
    void aConverterStationWithoutHvdcLineIsRefused() {
        Network network = HvdcTestNetwork.createVsc();
        network.getHvdcLine("L").remove();
        VscConverterStation station = network.getVscConverterStation("C1");
        CgmesChangeTranslator translator = new CgmesChangeTranslator(network, new CgmesExportContext(network),
                PartialSshExport.UnsupportedChangeBehavior.IGNORE);
        String variantId = network.getVariantManager().getWorkingVariantId();
        List<Result<CgmesPropertyBuffer, String>> results = new ArrayList<>();
        for (String attribute : HvdcFamily.CONTROL_KEYS) {
            results.add(translator.translate(event(station, attribute, variantId)));
        }
        results.add(translator.hvdc.converterStationUpdates(station));
        for (Result<CgmesPropertyBuffer, String> result : results) {
            assertTrue(result instanceof Result.Failure(String reason) && reason.contains("belongs to no HVDC line"),
                    result::toString);
        }
    }

    private static NetworkEvent event(Identifiable<?> identifiable, String probe, String variantId) {
        int separator = probe.indexOf('#');
        return separator < 0
                ? new UpdateNetworkEvent(identifiable.getId(), probe, variantId, null, null)
                : new ExtensionUpdateNetworkEvent(identifiable.getId(), probe.substring(0, separator),
                        probe.substring(separator + 1), variantId, null, null);
    }

    /**
     * The descriptions of one object, as the change of each family gives them; none for an object they do not cover.
     *
     * @param controls the regulating controls of the scope of the translator, which describe a TapChangerControl
     */
    static List<Result<CgmesPropertyBuffer, String>> describe(CgmesChangeTranslator translator,
                                                              RegulatingControlFamily controls, Identifiable<?> identifiable) {
        List<Result<CgmesPropertyBuffer, String>> descriptions = new ArrayList<>();
        switch (identifiable) {
            case Load load -> descriptions.add(translator.loads.loadUpdates(load));
            case Generator generator -> {
                descriptions.add(translator.machines.generatorMachineUpdates(generator));
                if (generator.hasProperty(Conversion.PROPERTY_GENERATING_UNIT)) {
                    descriptions.add(translator.machines.participationFactorUpdates(generator, null));
                }
                if (!CgmesNames.EQUIVALENT_INJECTION.equals(generator.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS))
                        && generator.getVoltageRegulation() != null) {
                    descriptions.add(controls.updatesOf(generator, IidmStateView.LIVE));
                }
            }
            case ShuntCompensator shunt -> {
                descriptions.add(translator.tapChangers.shuntCompensatorUpdates(shunt, CgmesChangeTranslator.SECTION_COUNT));
                if (shunt.getVoltageRegulation() != null) {
                    descriptions.add(controls.updatesOf(shunt, IidmStateView.LIVE));
                }
            }
            // The block and the control of a static var compensator, which the update reads as one group
            case StaticVarCompensator svc -> descriptions.add(translator.tapChangers.staticVarCompensatorUpdates(svc));
            case BoundaryLine boundaryLine -> descriptions.add(translator.machines.boundaryLineUpdates(boundaryLine));
            case Switch sw -> descriptions.add(translator.switches.switchUpdates(sw));
            case HvdcLine line -> {
                descriptions.add(translator.hvdc.converterStationUpdates(line.getConverterStation1()));
                descriptions.add(translator.hvdc.converterStationUpdates(line.getConverterStation2()));
            }
            case HvdcConverterStation<?> station -> {
                descriptions.add(translator.hvdc.converterStationUpdates(station));
                station.getOtherConverterStation().ifPresent(other -> descriptions.add(translator.hvdc.converterStationUpdates(other)));
            }
            case AcDcConverter<?> converter -> descriptions.add(translator.hvdc.acDcConverterUpdates(converter, null));
            case TwoWindingsTransformer transformer -> {
                transformer.getOptionalPhaseTapChanger().ifPresent(ptc -> describeTapChanger(translator, controls, transformer,
                        CgmesExportUtil.tapChangerAliasType(transformer, Conversion.ALIAS_PHASE_TAP_CHANGER1,
                                Conversion.ALIAS_PHASE_TAP_CHANGER2),
                        CgmesNames.PHASE_TAP_CHANGER_TABULAR, CgmesChangeTranslator.PHASE_TAP_CHANGER_PREFIX, ptc, descriptions));
                transformer.getOptionalRatioTapChanger().ifPresent(rtc -> describeTapChanger(translator, controls, transformer,
                        CgmesExportUtil.tapChangerAliasType(transformer, Conversion.ALIAS_RATIO_TAP_CHANGER1,
                                Conversion.ALIAS_RATIO_TAP_CHANGER2),
                        CgmesNames.RATIO_TAP_CHANGER, CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX, rtc, descriptions));
            }
            case ThreeWindingsTransformer transformer -> transformer.getLegs().forEach(leg -> {
                String end = Integer.toString(leg.getSide().getNum());
                leg.getOptionalPhaseTapChanger().ifPresent(ptc -> describeTapChanger(translator, controls, transformer,
                        CgmesExportUtil.getPhaseTapChangerAliasType(end), CgmesNames.PHASE_TAP_CHANGER_TABULAR,
                        CgmesChangeTranslator.PHASE_TAP_CHANGER_PREFIX + end, ptc, descriptions));
                leg.getOptionalRatioTapChanger().ifPresent(rtc -> describeTapChanger(translator, controls, transformer,
                        CgmesExportUtil.getRatioTapChangerAliasType(end), CgmesNames.RATIO_TAP_CHANGER,
                        CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX + end, rtc, descriptions));
            });
            default -> { }
        }
        return descriptions;
    }

    /** A tap changer: its block and, when the import recorded one, its TapChangerControl. */
    private static <C extends Connectable<C>> void describeTapChanger(CgmesChangeTranslator translator,
                                                                      RegulatingControlFamily controls, C transformer,
                                                                      String aliasType, String defaultClassName,
                                                                      String prefix, TapChanger<?, ?, ?, ?> tapChanger,
                                                                      List<Result<CgmesPropertyBuffer, String>> descriptions) {
        CgmesPropertyBuffer block = new CgmesPropertyBuffer();
        translator.tapChangers.describeTapChanger(transformer, aliasType, defaultClassName, new TapChangerRef(transformer, prefix, tapChanger), block);
        descriptions.add(Result.success(block));
        controls.controlId(transformer, aliasType)
                .ifPresent(controlId -> descriptions.add(controls.updatesFor(controlId, IidmStateView.LIVE)));
    }

    /** The steady state statements of the successful descriptions, by subject and property. */
    private static Map<CgmesStatement.Key, Value> statements(List<Result<CgmesPropertyBuffer, String>> descriptions,
                                                             CgmesExportContext context) {
        Map<CgmesStatement.Key, Value> statements = new HashMap<>();
        for (Result<CgmesPropertyBuffer, String> description : descriptions) {
            if (description instanceof Result.Success(CgmesPropertyBuffer buffer)) {
                buffer.statements(CgmesSubset.STEADY_STATE_HYPOTHESIS, context)
                        .forEach(statement -> statements.put(statement.key(), new Value(statement.className(), statement.value())));
            }
        }
        return statements;
    }
}
