/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.Family;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.FamilySpec;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.VariantSafety;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The variant-safety half of the capability table, asserted family by family.
 *
 * <p>The table claims which IIDM targets of an update are stored per network variant, and that claim is what the
 * whole variant feature rests on: a wrong {@code SAFE} silently corrupts every other variant of a network. The
 * verdicts themselves are proved against {@code iidm-impl} by {@link VariantSafetyProbeTest}; what is asserted
 * here is that the table is complete, that no family was left unclassified by accident, and that the network free
 * decision function {@code checkVariantSafe} says what the table says.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class FastRouteCapabilitiesVariantSafetyTest {

    /** The verdict the authoritative table of the documentation gives each family. */
    private static final Map<Family, VariantSafety> EXPECTED = Map.ofEntries(
            Map.entry(Family.SWITCH, VariantSafety.SAFE),
            Map.entry(Family.TERMINAL, VariantSafety.SAFE),
            Map.entry(Family.DC_TERMINAL, VariantSafety.SAFE),
            Map.entry(Family.ENERGY_CONSUMER, VariantSafety.SAFE),
            Map.entry(Family.ENERGY_SOURCE, VariantSafety.SAFE),
            Map.entry(Family.ASYNCHRONOUS_MACHINE, VariantSafety.SAFE),
            Map.entry(Family.SYNCHRONOUS_MACHINE, VariantSafety.NETWORK_DEPENDENT),
            Map.entry(Family.EXTERNAL_NETWORK_INJECTION, VariantSafety.NETWORK_DEPENDENT),
            Map.entry(Family.EQUIVALENT_INJECTION, VariantSafety.SAFE),
            Map.entry(Family.GENERATING_UNIT, VariantSafety.NETWORK_DEPENDENT),
            Map.entry(Family.STATIC_VAR_COMPENSATOR, VariantSafety.SAFE),
            Map.entry(Family.SHUNT_COMPENSATOR, VariantSafety.SAFE),
            Map.entry(Family.RATIO_TAP_CHANGER, VariantSafety.NETWORK_DEPENDENT),
            Map.entry(Family.PHASE_TAP_CHANGER, VariantSafety.NETWORK_DEPENDENT),
            Map.entry(Family.REGULATING_CONTROL, VariantSafety.NETWORK_DEPENDENT),
            Map.entry(Family.CS_CONVERTER, VariantSafety.UNSAFE),
            Map.entry(Family.VS_CONVERTER, VariantSafety.NETWORK_DEPENDENT),
            Map.entry(Family.CONTROL_AREA, VariantSafety.SAFE),
            Map.entry(Family.CURRENT_LIMIT, VariantSafety.UNSAFE),
            Map.entry(Family.ACTIVE_POWER_LIMIT, VariantSafety.UNSAFE),
            Map.entry(Family.APPARENT_POWER_LIMIT, VariantSafety.UNSAFE),
            Map.entry(Family.VOLTAGE_LIMIT, VariantSafety.UNSAFE),
            Map.entry(Family.AC_LINE_SEGMENT, VariantSafety.UNSAFE),
            Map.entry(Family.SERIES_COMPENSATOR, VariantSafety.UNSAFE),
            Map.entry(Family.EQUIVALENT_BRANCH, VariantSafety.UNSAFE),
            Map.entry(Family.VOLTAGE_LEVEL, VariantSafety.UNSAFE));

    @Test
    void everyFamilyIsClassified() {
        for (Family family : Family.values()) {
            assertNotNull(EXPECTED.get(family), "the test table does not classify " + family
                    + "; a new family needs a verdict in the documentation table too");
            assertEquals(EXPECTED.get(family), FastRouteCapabilities.spec(family).variantSafety(),
                    "wrong variant safety for " + family);
        }
        assertEquals(Family.values().length, FastRouteCapabilities.table().size());
    }

    @Test
    void everyPropertyOfTheTableHasAVerdict() {
        for (FamilySpec spec : FastRouteCapabilities.table()) {
            for (String property : spec.properties()) {
                VariantSafety safety = FastRouteCapabilities.variantSafety(spec.family(), property);
                assertNotNull(safety, property);
                if (safety == VariantSafety.SAFE) {
                    assertNull(FastRouteCapabilities.variantUnsafeReason(spec.family(), property),
                            "a safe property needs no reason: " + property);
                } else {
                    assertNotNull(FastRouteCapabilities.variantUnsafeReason(spec.family(), property),
                            "no reason given for " + property);
                }
            }
        }
    }

    /** The one property whose family is safe but whose IIDM target is a shared property. */
    @Test
    void theControlAreaToleranceIsUnsafeAlthoughItsFamilyIsNot() {
        assertEquals(VariantSafety.SAFE,
                FastRouteCapabilities.variantSafety(Family.CONTROL_AREA, "ControlArea.netInterchange"));
        assertEquals(VariantSafety.UNSAFE,
                FastRouteCapabilities.variantSafety(Family.CONTROL_AREA, "ControlArea.pTolerance"));
        assertTrue(FastRouteCapabilities.variantUnsafeReason(Family.CONTROL_AREA, "ControlArea.pTolerance")
                .contains("properties are not stored per variant"));
    }

    @Test
    void anOrdinarySetpointDifferenceIsVariantSafe() {
        assertEquals(CgmesDiffImport.Route.FAST, FastRouteCapabilities.checkVariantSafe(ssh(
                List.of(CgmesStatement.literal("L1", null, "EnergyConsumer.p", "1"),
                        CgmesStatement.literal("L1", null, "EnergyConsumer.q", "2")),
                List.of(CgmesStatement.literal("L1", null, "EnergyConsumer.p", "3"),
                        CgmesStatement.literal("L1", null, "EnergyConsumer.q", "4")))).route());
    }

    @Test
    void anEmptySetIsANoop() {
        assertEquals(CgmesDiffImport.Route.NOOP,
                FastRouteCapabilities.checkVariantSafe(new DifferenceModelSet(List.of())).route());
    }

    /** What is already impossible in place stays impossible, with the reason of the structural check. */
    @Test
    void aDifferenceThatIsNotFastAtAllKeepsItsOwnReason() {
        CgmesDiffImport.Decision decision = FastRouteCapabilities.checkVariantSafe(ssh(
                List.of(CgmesStatement.literal("L1", null, "EnergyConsumer.pfixed", "1")), List.of()));
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route());
        assertTrue(decision.reasons().get(0).contains("not part of the in-place update"),
                decision.reasons().toString());
    }

    @Test
    void aLimitValueIsNotVariantSafe() {
        CgmesDiffImport.Decision decision = FastRouteCapabilities.checkVariantSafe(eq(
                List.of(CgmesStatement.literal("CL", null, "CurrentLimit.value", "800")), List.of()));
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route());
        assertTrue(decision.reasons().get(0).contains("operational limit values are not stored per variant"),
                decision.reasons().toString());
    }

    @Test
    void aLineImpedanceIsNotVariantSafe() {
        CgmesDiffImport.Decision decision = FastRouteCapabilities.checkVariantSafe(eq(
                List.of(CgmesStatement.literal("L1", null, "ACLineSegment.r", "2")),
                List.of(CgmesStatement.literal("L1", null, "ACLineSegment.r", "1"))));
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route());
        assertTrue(decision.reasons().get(0).contains("branch impedances are not stored per variant"),
                decision.reasons().toString());
        // Both directions are judged, so a statement stated only in reverse is found too
        assertEquals(2, decision.blocking().size());
    }

    @Test
    void aLineCommutatedConverterIsNotVariantSafe() {
        CgmesDiffImport.Decision decision = FastRouteCapabilities.checkVariantSafe(ssh(
                List.of(CgmesStatement.literal("C1", "CsConverter", "CsConverter.operatingMode", "inverter"),
                        CgmesStatement.literal("C1", "CsConverter", "CsConverter.pPccControl", "activePower")),
                List.of()));
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route());
        assertTrue(decision.reasons().get(0).contains("line commutated converter"), decision.reasons().toString());
    }

    @Test
    void theControlAreaToleranceIsRefusedButItsInterchangeIsNot() {
        CgmesDiffImport.Decision decision = FastRouteCapabilities.checkVariantSafe(ssh(
                List.of(CgmesStatement.literal("A1", null, "ControlArea.netInterchange", "100"),
                        CgmesStatement.literal("A1", null, "ControlArea.pTolerance", "5")),
                List.of()));
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route());
        assertEquals(1, decision.blocking().size());
        assertEquals("ControlArea.pTolerance", decision.blocking().get(0).statement().property());
    }

    /** A network dependent family is not refused here: the network aware half decides it. */
    @Test
    void aTapChangerDifferenceIsLeftToTheNetworkAwareCheck() {
        assertEquals(CgmesDiffImport.Route.FAST, FastRouteCapabilities.checkVariantSafe(ssh(
                List.of(CgmesStatement.literal("TC", "RatioTapChanger", "TapChanger.step", "3"),
                        CgmesStatement.literal("TC", "RatioTapChanger", "TapChanger.controlEnabled", "true")),
                List.of())).route());
    }

    private static DifferenceModelSet ssh(List<CgmesStatement> forward, List<CgmesStatement> reverse) {
        return set(CgmesSubset.STEADY_STATE_HYPOTHESIS, forward, reverse);
    }

    private static DifferenceModelSet eq(List<CgmesStatement> forward, List<CgmesStatement> reverse) {
        return set(CgmesSubset.EQUIPMENT, forward, reverse);
    }

    private static DifferenceModelSet set(CgmesSubset subset, List<CgmesStatement> forward,
                                          List<CgmesStatement> reverse) {
        return new DifferenceModelSet(List.of(new DifferenceModel(
                DifferenceModelHeader.builder("urn:uuid:test", subset, CgmesNamespace.CIM_16_NAMESPACE).build(),
                forward, reverse, List.of())));
    }
}
