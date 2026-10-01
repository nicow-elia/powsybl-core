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
import com.powsybl.cgmes.conversion.mapping.LoadRows;
import com.powsybl.cgmes.conversion.mapping.PlainFamily;
import com.powsybl.cgmes.conversion.mapping.PlainRow;
import com.powsybl.cgmes.model.CgmesSubset;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The capability table against the two things it is derived from and describes: the blocks the families of the
 * mapping declare, and the SPARQL update catalogue that reads them.
 *
 * <ul>
 *     <li>The derived table equals the table as it was written by hand, kept here once as a fixture (the table of
 *     {@code FastRouteCapabilities} at slice-b-merged, 278f6ebe07, verbatim; the load entries as they were before
 *     their rows replaced them).</li>
 *     <li>Every property of an update family is read by the query of its family into a variable &mdash; the variable
 *     a data row names, where the row names one &mdash; at the nesting depth its group says: the required properties
 *     of a group in one block, its optional properties in an {@code OPTIONAL} nested in that block. A group whose
 *     required properties the query reads in separate optional blocks is listed with the reason in
 *     {@link #NOT_ONE_BLOCK}. Every class a family accepts is named by the query (D11: {@code Jumper} only by the CIM
 *     100 catalogue, which overrides the CIM 16 query).</li>
 *     <li>Every property of the catalogue is in the table or explicitly excluded, and every exclusion is read.</li>
 * </ul>
 *
 * <p>Both directions matter. A property added to an update query without a table entry would make a difference model
 * carrying it go the slow route for no reason; a table entry whose property no query reads would make a difference
 * apply and change nothing; a required property read in an optional block would let a difference apply half a
 * group.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class UpdateCatalogTest {

    private static final Pattern CIM_PROPERTY = Pattern.compile("cim:([A-Za-z]+\\.[A-Za-z]+)");
    private static final String CIM16 = "/CIM16-update.sparql";
    private static final String CIM100 = "/CIM100-update.sparql";

    /**
     * Groups whose required properties the query does not read in one block, with the reason the group is required
     * all the same.
     */
    private static final Map<Family, String> NOT_ONE_BLOCK = Map.of(Family.SYNCHRONOUS_MACHINE,
            "the query reads p and q in two optional blocks (a condenser may not define p), but the conversion only takes"
                    + " either when both are bound (SynchronousMachineConversion: the updated power flow has to be"
                    + " defined), so the two travel together; the rest of the block is optional");

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

    /**
     * Every property of every update family is read into a variable by the query of its family, at the depth of its
     * group: required properties together in one block, optional ones in an {@code OPTIONAL} nested in it; a row reads
     * into the variable it names.
     */
    @Test
    void everyPropertyIsReadIntoAVariableAtTheDepthOfItsGroup() {
        List<String> problems = new ArrayList<>();
        for (FamilySpec spec : FastRouteCapabilities.table()) {
            if (spec.handler() != Handler.UPDATE_QUERY) {
                continue;
            }
            String query = queryOf(spec.updateQuery(), spec.properties());
            for (PropertyGroup group : spec.groups()) {
                Set<Read> required = new LinkedHashSet<>();
                for (String property : group.required()) {
                    Read read = read(query, property);
                    if (read == null) {
                        problems.add(spec.family() + ": " + spec.updateQuery() + " does not read " + property + " into a variable");
                    } else {
                        required.add(read.withoutVariable());
                    }
                }
                if (required.size() > 1 && !NOT_ONE_BLOCK.containsKey(spec.family())) {
                    problems.add(spec.family() + ": the required properties " + group.required() + " are read in "
                            + required.size() + " blocks of " + spec.updateQuery());
                }
                Read block = required.isEmpty() ? null : required.iterator().next();
                for (String property : group.optional()) {
                    Read read = read(query, property);
                    if (read == null) {
                        problems.add(spec.family() + ": " + spec.updateQuery() + " does not read " + property + " into a variable");
                    } else if (block != null && !NOT_ONE_BLOCK.containsKey(spec.family())
                            && !(read.depth() > block.depth() && read.blocks().startsWith(block.blocks()))) {
                        problems.add(spec.family() + ": the optional " + property + " is not read in an OPTIONAL nested in"
                                + " the block of " + group.required());
                    }
                }
            }
        }
        // A row names the variable the importer's update reads its value from
        for (PlainFamily<?> rows : List.of(LoadRows.ENERGY_CONSUMER, LoadRows.ENERGY_SOURCE, LoadRows.ASYNCHRONOUS_MACHINE)) {
            String query = queryOf(rows.updateQuery(), Set.of());
            for (PlainRow<?> row : rows.rows()) {
                Read read = read(query, row.property());
                if (read == null || !read.variable().equals(row.variable())) {
                    problems.add(rows.updateQuery() + " reads " + row.property() + " into " + (read == null ? null : read.variable())
                            + ", the row names " + row.variable());
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void everyTablePropertyOccursInTheUpdateCatalog() {
        String cim16 = catalog(CIM16);
        String cim100 = catalog(CIM100);
        List<String> missing = new ArrayList<>();
        for (FamilySpec spec : FastRouteCapabilities.table()) {
            if (spec.handler() != Handler.UPDATE_QUERY) {
                // A direct setter family has no query at all: nothing in the update catalogue reads an impedance or
                // a voltage level limit, which is exactly why those values are applied with IIDM setters
                continue;
            }
            String text = query(cim16, spec.updateQuery());
            for (String property : spec.properties()) {
                if (!text.contains("cim:" + property)) {
                    missing.add(spec.family() + " reads " + property + ", " + spec.updateQuery() + " does not");
                }
            }
            if (spec.family() == Family.GENERATING_UNIT) {
                // The generating unit query binds the type freely (?GeneratingUnit a ?generatingUnitType), so it
                // names none of the concrete classes
                continue;
            }
            for (String rdfType : spec.rdfTypes()) {
                // A whole word: "cim:Switch" must not be satisfied by "cim:Switch.open", otherwise a class dropped
                // from a VALUES block would go unnoticed for every family whose properties start with its name
                Pattern wholeClass = Pattern.compile("cim:" + Pattern.quote(rdfType) + "(?![A-Za-z.])");
                boolean known = wholeClass.matcher(text).find()
                        || wholeClass.matcher(query(cim100, spec.updateQuery())).find();
                if (!known) {
                    missing.add(spec.family() + " accepts " + rdfType + ", " + spec.updateQuery() + " does not");
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void everySshPropertyOfTheCatalogIsInTheTableOrExcluded() {
        Set<String> inCatalog = new LinkedHashSet<>();
        for (String catalog : List.of(catalog(CIM16), catalog(CIM100))) {
            Matcher matcher = CIM_PROPERTY.matcher(catalog);
            while (matcher.find()) {
                inCatalog.add(matcher.group(1));
            }
        }
        List<String> unknown = inCatalog.stream()
                .filter(property -> !FastRouteCapabilities.isUpdatableProperty(property))
                .filter(property -> FastRouteCapabilities.excludedReason(property) == null)
                .toList();
        assertEquals(List.of(), unknown,
                "every property an update query reads is either in the table or explicitly excluded");
    }

    @Test
    void everyExcludedPropertyIsReallyReadByTheCatalog() {
        String both = catalog(CIM16) + catalog(CIM100);
        List<String> dead = FastRouteCapabilities.notDifferenceUpdatableProperties().stream()
                .filter(property -> !both.contains("cim:" + property))
                .toList();
        assertEquals(List.of(), dead, "an exclusion of a property no query reads is dead weight");
    }

    /**
     * A direct setter property must not also be read by an update query: it would then be applied twice, once
     * through the synthetic document and once through the setter.
     */
    @Test
    void directSetterPropertiesAreNotInTheUpdateCatalog() {
        String both = catalog(CIM16) + catalog(CIM100);
        List<String> duplicated = FastRouteCapabilities.table().stream()
                .filter(spec -> spec.handler() == Handler.DIRECT_SETTER)
                .flatMap(spec -> spec.properties().stream())
                .filter(property -> both.contains("cim:" + property + " "))
                .toList();
        assertEquals(List.of(), duplicated);
    }

    /**
     * Where a query reads a property: the variable, the number of {@code OPTIONAL} blocks around it and the path of the
     * blocks it is in (the offset of each opening brace, outermost first).
     */
    private record Read(String variable, int depth, String blocks) {
        Read withoutVariable() {
            return new Read("", depth, blocks);
        }
    }

    /** The first place the query reads the property into a variable, {@code null} when it does not. */
    private static Read read(String query, String property) {
        Matcher matcher = Pattern.compile("cim:" + Pattern.quote(property) + "\\s+\\?(\\w+)").matcher(query);
        if (!matcher.find()) {
            return null;
        }
        Deque<int[]> open = new ArrayDeque<>();
        for (int i = 0; i < matcher.start(); i++) {
            char c = query.charAt(i);
            if (c == '{') {
                boolean optional = query.substring(0, i).stripTrailing().endsWith("OPTIONAL");
                open.push(new int[] {i, optional ? 1 : 0});
            } else if (c == '}') {
                open.pop();
            }
        }
        StringBuilder blocks = new StringBuilder();
        int depth = 0;
        List<int[]> outermostFirst = new ArrayList<>(open);
        Collections.reverse(outermostFirst);
        for (int[] brace : outermostFirst) {
            blocks.append(brace[0]).append('/');
            depth += brace[1];
        }
        return new Read(matcher.group(1), depth, blocks.toString());
    }

    /**
     * The text of a named query, comments removed: from the CIM 16 catalogue, or from the CIM 100 one when it overrides
     * it and the CIM 16 query lacks one of the properties.
     */
    private static String queryOf(String name, Set<String> properties) {
        String cim16 = query(catalog(CIM16), name);
        String cim100 = query(catalog(CIM100), name);
        boolean override = !cim100.isEmpty() && properties.stream().anyMatch(property -> !cim16.contains("cim:" + property));
        return (override ? cim100 : cim16).replaceAll("(?m)#.*$", "");
    }

    private static String catalog(String resource) {
        try (InputStream is = UpdateCatalogTest.class.getResourceAsStream(resource)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The text of one named query of a catalogue, up to the next one, empty when the catalogue has none. */
    private static String query(String catalog, String name) {
        int from = catalog.indexOf("# query: " + name);
        if (from < 0) {
            // CIM100 only overrides the queries it changes; everything else is included from CIM16
            return "";
        }
        int to = catalog.indexOf("# query: ", from + 1);
        return to < 0 ? catalog.substring(from) : catalog.substring(from, to);
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
