/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.test.ConversionUtil;
import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.BusbarSection;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulation;
import com.powsybl.iidm.network.regulation.VoltageRegulationBuilder;
import com.powsybl.iidm.network.regulation.VoltageRegulationHolder;
import com.powsybl.iidm.network.test.BatteryNetworkFactory;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.HvdcTestNetwork;
import com.powsybl.iidm.network.test.ShuntTestCaseFactory;
import com.powsybl.iidm.network.test.SvcTestCaseFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state-aware readers of {@link RegulationRef} are the mirror of the defaults of {@link VoltageRegulationHolder}:
 * read through {@link IidmStateView#LIVE}, every one of them gives the value the holder itself gives.
 *
 * <p>Every kind of holder is put into every state IIDM accepts: no regulation, a regulation created in another variant
 * (no mode in the working one), and a regulation in every mode, regulating or not, with no regulating terminal, the
 * holder's own terminal or a terminal of another equipment. The local targets and the target of the regulation are
 * different numbers, so that a reader taking the one for the other fails.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RegulationRefTest {

    private static final double LOCAL_TARGET_V = 401.0;
    private static final double LOCAL_TARGET_Q = 7.0;
    private static final double TARGET_V = 402.0;
    private static final double TARGET_Q = 15.0;
    private static final double DEADBAND = 0.5;
    private static final String NO_REGULATION = "no regulation";
    private static final String NO_MODE = "no mode in this variant";

    enum Kind { GENERATOR, BATTERY, SHUNT_COMPENSATOR, STATIC_VAR_COMPENSATOR, VSC_CONVERTER_STATION,
        VOLTAGE_SOURCE_CONVERTER, RATIO_TAP_CHANGER }

    enum RegulatingTerminal { NONE, OWN, OTHER }

    /**
     * One state of one kind of holder.
     *
     * @param regulation {@value #NO_REGULATION}, {@value #NO_MODE} or the name of a {@link RegulationMode}
     */
    record Case(Kind kind, String regulation, RegulatingTerminal terminal, boolean regulating) {
        @Override
        public String toString() {
            return kind + " | " + regulation + " | terminal " + terminal + " | " + (regulating ? "regulating" : "not regulating");
        }
    }

    private static RegulationRef load(Kind kind) {
        return switch (kind) {
            case GENERATOR -> RegulationRef.of(EurostagTutorialExample1Factory.create().getGenerator("GEN"));
            case BATTERY -> RegulationRef.of(BatteryNetworkFactory.create().getBattery("BAT"));
            case SHUNT_COMPENSATOR -> RegulationRef.of(ShuntTestCaseFactory.create().getShuntCompensator("SHUNT"));
            case STATIC_VAR_COMPENSATOR -> RegulationRef.of(SvcTestCaseFactory.create().getStaticVarCompensator("SVC2"));
            case VSC_CONVERTER_STATION -> RegulationRef.of(HvdcTestNetwork.createVsc().getVscConverterStation("C1"));
            case VOLTAGE_SOURCE_CONVERTER -> {
                Properties parameters = new Properties();
                parameters.put(CgmesImport.USE_DETAILED_DC_MODEL, "true");
                yield RegulationRef.of(ConversionUtil.readCgmesResources(parameters, "/issues/hvdc/",
                        "mixed_bipole_EQ.xml", "mixed_bipole_SSH.xml").getVoltageSourceConverter("VSC_1_2"));
            }
            case RATIO_TAP_CHANGER -> {
                TwoWindingsTransformer transformer = EurostagTutorialExample1Factory.create()
                        .getTwoWindingsTransformer("NHV2_NLOAD");
                yield new RegulationRef(transformer, CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX,
                        transformer.getRatioTapChanger());
            }
        };
    }

    /** The holder of the case in the state of the case, or {@code null} when IIDM refuses that state. */
    private static RegulationRef prepare(Case c) {
        RegulationRef ref = load(c.kind());
        VoltageRegulationHolder<?> holder = ref.holder();
        Network network = ref.owner().getNetwork();
        // A ratio tap changer has no local targets, a shunt compensator no local reactive target. They are set first:
        // a holder without regulation needs its local targets
        ignoreUnsupported(() -> holder.setLocalTargetV(LOCAL_TARGET_V));
        ignoreUnsupported(() -> holder.setLocalTargetQ(LOCAL_TARGET_Q));
        try {
            if (holder.getVoltageRegulation() != null) {
                holder.removeVoltageRegulation();
            }
            switch (c.regulation()) {
                case NO_REGULATION -> { }
                case NO_MODE -> {
                    VariantManager variants = network.getVariantManager();
                    String working = variants.getWorkingVariantId();
                    variants.cloneVariant(working, "another variant");
                    variants.setWorkingVariant("another variant");
                    holder.newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(false)
                            .withTargetValue(TARGET_V).build();
                    variants.setWorkingVariant(working);
                }
                default -> {
                    RegulationMode mode = RegulationMode.valueOf(c.regulation());
                    VoltageRegulationBuilder builder = holder.newVoltageRegulation().withMode(mode)
                            .withRegulating(c.regulating())
                            .withTargetValue(mode == RegulationMode.REACTIVE_POWER ? TARGET_Q : TARGET_V)
                            .withTargetDeadband(DEADBAND);
                    switch (c.terminal()) {
                        case NONE -> { }
                        case OWN -> builder.withTerminal(holder.getTerminal());
                        case OTHER -> builder.withTerminal(anotherTerminal(ref));
                    }
                    builder.build();
                }
            }
        } catch (PowsyblException | UnsupportedOperationException refused) {
            return null;
        }
        return ref;
    }

    private static void ignoreUnsupported(Runnable setter) {
        try {
            setter.run();
        } catch (UnsupportedOperationException | PowsyblException unsupported) {
            // not a value of this kind of holder
        }
    }

    private static Terminal anotherTerminal(RegulationRef ref) {
        String ownerId = ref.owner().getId();
        return ref.owner().getNetwork().getConnectableStream()
                .filter(c -> !c.getId().equals(ownerId) && !(c instanceof BusbarSection) && !(c instanceof AcDcConverter<?>))
                .map(c -> (Terminal) ((Connectable<?>) c).getTerminals().get(0))
                .filter(t -> t != ref.holder().getTerminal())
                .findFirst().orElseThrow();
    }

    /** Every combination IIDM accepts. */
    static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            cases.add(new Case(kind, NO_REGULATION, RegulatingTerminal.NONE, false));
            cases.add(new Case(kind, NO_MODE, RegulatingTerminal.NONE, false));
            for (RegulationMode mode : RegulationMode.values()) {
                for (RegulatingTerminal terminal : RegulatingTerminal.values()) {
                    for (boolean regulating : new boolean[] {false, true}) {
                        cases.add(new Case(kind, mode.name(), terminal, regulating));
                    }
                }
            }
        }
        return cases.stream().filter(c -> prepare(c) != null).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void everyReaderEqualsTheDefaultOfTheHolder(Case c) {
        RegulationRef ref = prepare(c);
        VoltageRegulationHolder<?> holder = ref.holder();
        VoltageRegulation regulation = holder.getVoltageRegulation();
        IidmStateView live = IidmStateView.LIVE;

        assertEquals(regulation == null ? null : regulation.getMode(), ref.mode(live), "mode");
        assertEquals(holder.isRegulating(), ref.isRegulating(live), "isRegulating");
        assertEquals(regulation == null ? Double.NaN : regulation.getTargetValue(), ref.targetValue(live), "targetValue");
        assertEquals(regulation == null ? Double.NaN : regulation.getTargetDeadband(), ref.targetDeadband(live),
                "targetDeadband");
        assertEquals(holder.getLocalTargetV(), ref.localTargetV(live), "localTargetV");
        assertEquals(holder.getLocalTargetQ(), ref.localTargetQ(live), "localTargetQ");
        assertSame(regulation == null ? null : regulation.getTerminal(), ref.terminal(live), "terminal");
        for (RegulationMode mode : modesAndNull()) {
            assertEquals(holder.isWithMode(mode), ref.isWithMode(mode, live), "isWithMode " + mode);
            assertEquals(holder.isRegulatingWithMode(mode), ref.isRegulatingWithMode(mode, live),
                    "isRegulatingWithMode " + mode);
        }
        assertEquals(holder.getRegulatingTargetV(), ref.regulatingTargetV(live), "regulatingTargetV");
        assertEquals(holder.getRegulatingTargetQ(), ref.regulatingTargetQ(live), "regulatingTargetQ");
    }

    private static List<RegulationMode> modesAndNull() {
        List<RegulationMode> modes = new ArrayList<>(Arrays.asList(RegulationMode.values()));
        modes.add(null);
        return modes;
    }

    /**
     * The states that tell the readers apart are reached for every kind of holder: no regulation, no mode, and a
     * regulation in a voltage mode and in reactive power mode, each regulating at a terminal of another equipment.
     */
    @Test
    void everyKindReachesTheStatesThatTellTheReadersApart() {
        List<Case> cases = cases();
        for (Kind kind : Kind.values()) {
            Supplier<Stream<Case>> ofKind = () -> cases.stream().filter(c -> c.kind() == kind);
            assertTrue(ofKind.get().anyMatch(c -> NO_REGULATION.equals(c.regulation())), kind + ": " + NO_REGULATION);
            assertTrue(ofKind.get().anyMatch(c -> NO_MODE.equals(c.regulation())), kind + ": " + NO_MODE);
            assertTrue(ofKind.get().anyMatch(c -> RegulationMode.VOLTAGE.name().equals(c.regulation())
                    && c.terminal() == RegulatingTerminal.OTHER && c.regulating()), kind + ": remote voltage regulation");
        }
    }
}
