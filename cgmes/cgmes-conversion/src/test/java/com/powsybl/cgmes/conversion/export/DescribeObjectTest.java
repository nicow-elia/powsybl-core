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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The change mapping describes an object without an event ({@code CgmesChangeTranslator.describe*}), and that
 * description is the one the event path writes.
 *
 * <p>In {@link Scope#CHANGES}, for every object of every fixture of {@link ExportMappingEquivalenceTest}: every
 * statement the event path writes for a probe of the object (the probes of the guard, one per attribute the translator
 * matches on, extension probes included) is described with the same value, and a value both write is the same. So an
 * object the description refuses (a holder the import would give a regulation to, a generator in another mode than
 * the CGMES mode its import recorded) is refused by every path of the event mapping too, the extension probes
 * included. The description may say more than the event path where the event path writes a block only together with a
 * control it refuses (a static var compensator, whose block and control the update reads as one group).</p>
 *
 * <p>In {@link Scope#FULL_MODEL}, objects a change export refuses but a full export writes are described, and their
 * description equals what the full export writes: batteries and a generator without a recorded control, a
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
        CgmesChangeTranslator translator = new CgmesChangeTranslator(network, context,
                PartialSshExport.UnsupportedChangeBehavior.IGNORE, "a description test",
                EnumSet.of(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS), IidmStateView.LIVE, null);
        String variantId = network.getVariantManager().getWorkingVariantId();
        List<String> problems = new ArrayList<>();
        for (Identifiable<?> identifiable : network.getIdentifiables()) {
            List<Result<CgmesPropertyBuffer, String>> descriptions = describe(translator, identifiable);
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
            new Object[] {"battery network: batteries, a generator without a recorded control",
                (Supplier<Network>) BatteryNetworkFactory::create, List.of("GEN", "BAT", "BAT2")},
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
        Map<ExportMappingEquivalenceTest.Key, ExportMappingEquivalenceTest.Triple> fullExport =
                ExportMappingEquivalenceTest.parse(ExportMappingEquivalenceTest.fullSsh(network), context.getCim().getNamespace());
        boolean refusedAsChange = false;
        for (String id : ids) {
            Identifiable<?> identifiable = network.getIdentifiable(id);
            List<Result<CgmesPropertyBuffer, String>> descriptions = describe(fullModel, identifiable);
            assertTrue(!descriptions.isEmpty() && descriptions.stream().allMatch(Result.Success.class::isInstance),
                    () -> id + " is not described by a full model: " + descriptions);
            refusedAsChange |= describe(changes, identifiable).stream().anyMatch(Result.Failure.class::isInstance);
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

    private static NetworkEvent event(Identifiable<?> identifiable, String probe, String variantId) {
        int separator = probe.indexOf('#');
        return separator < 0
                ? new UpdateNetworkEvent(identifiable.getId(), probe, variantId, null, null)
                : new ExtensionUpdateNetworkEvent(identifiable.getId(), probe.substring(0, separator),
                        probe.substring(separator + 1), variantId, null, null);
    }

    /** The descriptions of one object, as the describe entries give them; none for an object they do not cover. */
    static List<Result<CgmesPropertyBuffer, String>> describe(CgmesChangeTranslator translator, Identifiable<?> identifiable) {
        List<Result<CgmesPropertyBuffer, String>> descriptions = new ArrayList<>();
        switch (identifiable) {
            case Load load -> descriptions.add(translator.describeLoad(load));
            case Battery battery -> descriptions.add(translator.describeBattery(battery));
            case Generator generator -> {
                descriptions.add(translator.describeGenerator(generator));
                if (generator.hasProperty(Conversion.PROPERTY_GENERATING_UNIT)) {
                    descriptions.add(translator.describeGeneratingUnit(generator));
                }
                if (!CgmesNames.EQUIVALENT_INJECTION.equals(generator.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS))
                        && generator.getVoltageRegulation() != null) {
                    descriptions.add(translator.describedControlId(generator).flatMap(translator::describeRegulatingControl));
                }
            }
            case ShuntCompensator shunt -> {
                descriptions.add(translator.describeShunt(shunt));
                if (shunt.getVoltageRegulation() != null) {
                    descriptions.add(translator.describedControlId(shunt).flatMap(translator::describeRegulatingControl));
                }
            }
            case StaticVarCompensator svc -> {
                descriptions.add(translator.describeStaticVarCompensator(svc));
                if (svc.getVoltageRegulation() != null) {
                    descriptions.add(translator.describedControlId(svc).flatMap(translator::describeRegulatingControl));
                }
            }
            case BoundaryLine boundaryLine -> descriptions.add(translator.describeBoundaryInjection(boundaryLine));
            case Switch sw -> descriptions.add(translator.describeSwitch(sw));
            case HvdcLine line -> {
                descriptions.add(translator.describeConverterStation(line.getConverterStation1()));
                descriptions.add(translator.describeConverterStation(line.getConverterStation2()));
            }
            case HvdcConverterStation<?> station -> {
                descriptions.add(translator.describeConverterStation(station));
                station.getOtherConverterStation().ifPresent(other -> descriptions.add(translator.describeConverterStation(other)));
            }
            case AcDcConverter<?> converter -> descriptions.add(translator.describeAcDcConverter(converter));
            case TwoWindingsTransformer transformer -> {
                transformer.getOptionalPhaseTapChanger().ifPresent(ptc -> describeTapChanger(translator, transformer,
                        CgmesExportUtil.tapChangerAliasType(transformer, Conversion.ALIAS_PHASE_TAP_CHANGER1,
                                Conversion.ALIAS_PHASE_TAP_CHANGER2),
                        CgmesNames.PHASE_TAP_CHANGER_TABULAR, CgmesChangeTranslator.PHASE_TAP_CHANGER_PREFIX, ptc, descriptions));
                transformer.getOptionalRatioTapChanger().ifPresent(rtc -> describeTapChanger(translator, transformer,
                        CgmesExportUtil.tapChangerAliasType(transformer, Conversion.ALIAS_RATIO_TAP_CHANGER1,
                                Conversion.ALIAS_RATIO_TAP_CHANGER2),
                        CgmesNames.RATIO_TAP_CHANGER, CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX, rtc, descriptions));
            }
            case ThreeWindingsTransformer transformer -> transformer.getLegs().forEach(leg -> {
                String end = Integer.toString(leg.getSide().getNum());
                leg.getOptionalPhaseTapChanger().ifPresent(ptc -> describeTapChanger(translator, transformer,
                        CgmesExportUtil.getPhaseTapChangerAliasType(end), CgmesNames.PHASE_TAP_CHANGER_TABULAR,
                        CgmesChangeTranslator.PHASE_TAP_CHANGER_PREFIX + end, ptc, descriptions));
                leg.getOptionalRatioTapChanger().ifPresent(rtc -> describeTapChanger(translator, transformer,
                        CgmesExportUtil.getRatioTapChangerAliasType(end), CgmesNames.RATIO_TAP_CHANGER,
                        CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX + end, rtc, descriptions));
            });
            default -> { }
        }
        return descriptions;
    }

    /** A tap changer: its block and, when the import recorded one, its TapChangerControl. */
    private static <C extends Connectable<C>> void describeTapChanger(CgmesChangeTranslator translator, C transformer,
                                                                      String aliasType, String defaultClassName,
                                                                      String prefix, TapChanger<?, ?, ?, ?> tapChanger,
                                                                      List<Result<CgmesPropertyBuffer, String>> descriptions) {
        descriptions.add(Result.success(translator.describeTapChanger(transformer, aliasType, defaultClassName,
                new TapChangerRef(transformer, prefix, tapChanger))));
        translator.describedTapChangerControlId(transformer, aliasType)
                .ifPresent(controlId -> descriptions.add(translator.describeRegulatingControl(controlId)));
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
