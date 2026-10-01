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
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.Handler;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.PropertyGroup;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.VariantSafety;
import com.powsybl.cgmes.model.CgmesSubset;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The capability entries of the families are derived from the blocks and rows the families declare; they equal the
 * entries the table held, written here once as the fixture they were before the families replaced them (the hand
 * written table of {@code FastRouteCapabilities} at slice-b-merged, 278f6ebe07, verbatim).
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class DerivedFamilySpecTest {

    private static final String ACDC_TERMINAL_CONNECTED = "ACDCTerminal.connected";
    private static final String REGULATING_COND_EQ_CONTROL_ENABLED = "RegulatingCondEq.controlEnabled";
    private static final String ROTATING_MACHINE_P = "RotatingMachine.p";
    private static final String ROTATING_MACHINE_Q = "RotatingMachine.q";
    private static final String ACDC_CONVERTER_P = "ACDCConverter.p";
    private static final String ACDC_CONVERTER_Q = "ACDCConverter.q";
    private static final String ACDC_CONVERTER_TARGET_PPCC = "ACDCConverter.targetPpcc";
    private static final String ACDC_CONVERTER_TARGET_UDC = "ACDCConverter.targetUdc";
    private static final String AC_DC_CONVERTERS_QUERY = "acDcConverters";

    private static final PropertyGroup AC_DC_CONVERTER_SETPOINTS = PropertyGroup.of(
            ACDC_CONVERTER_TARGET_PPCC, ACDC_CONVERTER_TARGET_UDC, ACDC_CONVERTER_P, ACDC_CONVERTER_Q);

    @Test
    void everyDerivedEntryIsTheOneTheTableHeld() {
        List<FamilySpec> legacy = legacyTable();
        assertEquals(legacy.size(), FastRouteCapabilities.table().size());
        for (int i = 0; i < legacy.size(); i++) {
            assertEquals(legacy.get(i), FastRouteCapabilities.table().get(i), legacy.get(i).family().name());
        }
    }

    @Test
    void theTableKeepsItsOrder() {
        assertEquals(List.of(Family.values()), FastRouteCapabilities.table().stream().map(FamilySpec::family).toList());
    }

    private static List<FamilySpec> legacyTable() {
        List<FamilySpec> table = new ArrayList<>();
        table.add(ssh(Family.SWITCH, "switches", "Switch",
                Set.of("Switch", "Breaker", "Disconnector", "LoadBreakSwitch", "ProtectedSwitch",
                        "GroundDisconnector", "Jumper"),
                List.of(PropertyGroup.of("Switch.open"))));
        table.add(ssh(Family.TERMINAL, "terminals", "Terminal", Set.of("Terminal"),
                List.of(PropertyGroup.of(ACDC_TERMINAL_CONNECTED)), VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.DC_TERMINAL, "dcTerminals", "DCTerminal",
                Set.of("DCTerminal", "ACDCConverterDCTerminal"),
                List.of(PropertyGroup.of(ACDC_TERMINAL_CONNECTED))));
        // The three load families as the table held them before their rows replaced them
        table.add(ssh(Family.ENERGY_CONSUMER, "energyConsumers", "EnergyConsumer",
                Set.of("EnergyConsumer", "ConformLoad", "NonConformLoad", "StationSupply"),
                List.of(PropertyGroup.of("EnergyConsumer.p", "EnergyConsumer.q"))));
        table.add(ssh(Family.ENERGY_SOURCE, "energySources", "EnergySource", Set.of("EnergySource"),
                List.of(PropertyGroup.of("EnergySource.activePower", "EnergySource.reactivePower"))));
        table.add(ssh(Family.ASYNCHRONOUS_MACHINE, "asynchronousMachines", "AsynchronousMachine",
                Set.of("AsynchronousMachine"),
                List.of(PropertyGroup.of("RotatingMachine.p", "RotatingMachine.q",
                        "AsynchronousMachine.asynchronousMachineType", "RegulatingCondEq.controlEnabled"))));
        // The query reads p and q in two optional blocks, but the conversion only takes either of them when BOTH
        // are bound (SynchronousMachineConversion: the updated power flow has to be "defined"). A difference that
        // states the active power alone would therefore be read, accepted and silently not applied, so the two
        // travel together here exactly as they do for an asynchronous machine; the rest of the group is optional
        table.add(ssh(Family.SYNCHRONOUS_MACHINE, "synchronousMachinesForUpdate", "SynchronousMachine",
                Set.of("SynchronousMachine"),
                List.of(new PropertyGroup(Set.of(ROTATING_MACHINE_P, ROTATING_MACHINE_Q),
                        Set.of("SynchronousMachine.referencePriority", "SynchronousMachine.operatingMode",
                                REGULATING_COND_EQ_CONTROL_ENABLED))),
                VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.EXTERNAL_NETWORK_INJECTION, "externalNetworkInjections", "ExternalNetworkInjection",
                Set.of("ExternalNetworkInjection"),
                List.of(PropertyGroup.of("ExternalNetworkInjection.p", "ExternalNetworkInjection.q",
                        "ExternalNetworkInjection.referencePriority", REGULATING_COND_EQ_CONTROL_ENABLED)),
                VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.EQUIVALENT_INJECTION, "equivalentInjections", "EquivalentInjection",
                Set.of("EquivalentInjection"),
                List.of(new PropertyGroup(Set.of("EquivalentInjection.p", "EquivalentInjection.q"),
                        Set.of("EquivalentInjection.regulationStatus", "EquivalentInjection.regulationTarget"))),
                VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.GENERATING_UNIT, "generatingUnits", "GeneratingUnit",
                Set.of("GeneratingUnit", "ThermalGeneratingUnit", "HydroGeneratingUnit", "NuclearGeneratingUnit",
                        "SolarGeneratingUnit", "WindGeneratingUnit"),
                List.of(PropertyGroup.of("GeneratingUnit.normalPF")), VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.STATIC_VAR_COMPENSATOR, "staticVarCompensators", "StaticVarCompensator",
                Set.of("StaticVarCompensator"),
                List.of(PropertyGroup.of("StaticVarCompensator.q", REGULATING_COND_EQ_CONTROL_ENABLED))));
        table.add(ssh(Family.SHUNT_COMPENSATOR, "shuntCompensators", "LinearShuntCompensator",
                Set.of("LinearShuntCompensator", "NonlinearShuntCompensator"),
                List.of(PropertyGroup.of("ShuntCompensator.sections", REGULATING_COND_EQ_CONTROL_ENABLED))));
        table.add(ssh(Family.RATIO_TAP_CHANGER, "ratioTapChangers", "RatioTapChanger", Set.of("RatioTapChanger"),
                List.of(PropertyGroup.of("TapChanger.step", "TapChanger.controlEnabled")),
                VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.PHASE_TAP_CHANGER, "phaseTapChangers", "PhaseTapChangerLinear",
                Set.of("PhaseTapChangerLinear", "PhaseTapChangerAsymmetrical", "PhaseTapChangerSymmetrical",
                        "PhaseTapChangerNonLinear", "PhaseTapChangerTabular"),
                List.of(PropertyGroup.of("TapChanger.step", "TapChanger.controlEnabled")),
                VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.REGULATING_CONTROL, "regulatingControls", "RegulatingControl",
                Set.of("RegulatingControl", "TapChangerControl"),
                List.of(new PropertyGroup(Set.of("RegulatingControl.enabled", "RegulatingControl.targetValue",
                                "RegulatingControl.targetValueUnitMultiplier", "RegulatingControl.discrete"),
                        Set.of("RegulatingControl.targetDeadband"))),
                VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.CS_CONVERTER, AC_DC_CONVERTERS_QUERY, "CsConverter", Set.of("CsConverter"),
                List.of(AC_DC_CONVERTER_SETPOINTS,
                        PropertyGroup.of("CsConverter.operatingMode", "CsConverter.pPccControl")),
                VariantSafety.UNSAFE));
        table.add(ssh(Family.VS_CONVERTER, AC_DC_CONVERTERS_QUERY, "VsConverter", Set.of("VsConverter"),
                List.of(AC_DC_CONVERTER_SETPOINTS,
                        new PropertyGroup(Set.of("VsConverter.pPccControl", "VsConverter.qPccControl"),
                                Set.of("VsConverter.targetQpcc", "VsConverter.targetUpcc"))),
                VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.CONTROL_AREA, "controlAreas", "ControlArea", Set.of("ControlArea"),
                List.of(new PropertyGroup(Set.of("ControlArea.netInterchange"), Set.of("ControlArea.pTolerance")))));
        // Operational limit values: equipment data in CIM 2.4.15, steady state data in CIM 3. One OperationalLimit
        // is one CGMES object with one value, so every group holds a single property.
        table.add(limit(Family.CURRENT_LIMIT, "CurrentLimit"));
        table.add(limit(Family.ACTIVE_POWER_LIMIT, "ActivePowerLimit"));
        table.add(limit(Family.APPARENT_POWER_LIMIT, "ApparentPowerLimit"));
        table.add(limit(Family.VOLTAGE_LIMIT, "VoltageLimit"));
        // Equipment values nothing in the update path reads, applied with IIDM setters
        table.add(directSetter(Family.AC_LINE_SEGMENT, "ACLineSegment", Set.of("ACLineSegment"),
                List.of("ACLineSegment.r", "ACLineSegment.x", "ACLineSegment.gch", "ACLineSegment.bch")));
        table.add(directSetter(Family.SERIES_COMPENSATOR, "SeriesCompensator", Set.of("SeriesCompensator"),
                List.of("SeriesCompensator.r", "SeriesCompensator.x")));
        // An EquivalentBranch states the impedance of both directions, and its import refuses a branch whose r21/x21
        // differ from r/x, so a difference of one has to move both
        table.add(directSetter(Family.EQUIVALENT_BRANCH, "EquivalentBranch", Set.of("EquivalentBranch"),
                List.of("EquivalentBranch.r", "EquivalentBranch.x",
                        "EquivalentBranch.r21", "EquivalentBranch.x21")));
        table.add(directSetter(Family.VOLTAGE_LEVEL, "VoltageLevel", Set.of("VoltageLevel"),
                List.of("VoltageLevel.highVoltageLimit", "VoltageLevel.lowVoltageLimit")));
        return List.copyOf(table);
    }

    private static FamilySpec ssh(Family family, String query, String canonicalType, Set<String> rdfTypes,
                                  List<PropertyGroup> groups) {
        return ssh(family, query, canonicalType, rdfTypes, groups, VariantSafety.SAFE);
    }

    private static FamilySpec ssh(Family family, String query, String canonicalType, Set<String> rdfTypes,
                                  List<PropertyGroup> groups, VariantSafety variantSafety) {
        return new FamilySpec(family, Set.of(CgmesSubset.STEADY_STATE_HYPOTHESIS), Handler.UPDATE_QUERY, query,
                canonicalType, rdfTypes, groups, variantSafety);
    }

    /**
     * An operational limit family: one class, one value, read by the {@code operationalLimits} query in both CIM
     * versions. Which profile carries the value is a CIM version rule rather than a family rule, see
     * {@code FastRouteCapabilities.checkLimitProfile}.
     */
    private static FamilySpec limit(Family family, String className) {
        return new FamilySpec(family, Set.of(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS),
                Handler.UPDATE_QUERY, "operationalLimits", className, Set.of(className),
                List.of(PropertyGroup.of(className + ".value")), VariantSafety.UNSAFE);
    }

    /**
     * A family whose values are applied with IIDM setters. Every property is a group of its own: there is no query
     * that would read them together, so none of them can make another one unreadable.
     */
    private static FamilySpec directSetter(Family family, String canonicalType, Set<String> rdfTypes,
                                           List<String> properties) {
        return new FamilySpec(family, Set.of(CgmesSubset.EQUIPMENT), Handler.DIRECT_SETTER, null, canonicalType,
                rdfTypes, properties.stream().map(PropertyGroup::of).toList(), VariantSafety.UNSAFE);
    }

}
