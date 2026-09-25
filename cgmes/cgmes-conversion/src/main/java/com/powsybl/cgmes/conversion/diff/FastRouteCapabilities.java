/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the in-place difference model update can do, as one declarative table.
 *
 * <p>Applying a difference model in place means feeding its forward statements into the ordinary CGMES update
 * workflow, which is driven by the SPARQL queries of {@code CIM16-update.sparql}. A statement can therefore only be
 * applied when some update query reads the property it states, on an object of a class that query accepts. This class
 * is the machine readable form of that catalogue: one {@link FamilySpec} per update query family, naming the query,
 * the CIM classes it accepts and the <em>property groups</em> it reads.</p>
 *
 * <p>A property group matters because SPARQL basic graph patterns are conjunctive: a query block that reads
 * {@code EnergyConsumer.p} and {@code EnergyConsumer.q} together returns <em>nothing at all</em> when only one of
 * them is present. A difference that states {@code p} alone would therefore silently do nothing. The groups make
 * that visible, so the importer can complete the missing properties from the receiving network (see
 * {@code CgmesObjectDump}) instead of applying half a change.</p>
 *
 * <p>{@link #check(DifferenceModelSet)} is deliberately network free: it answers "could this difference model ever be
 * applied in place" from the document alone. A database that stores differences uses exactly this to flag a
 * difference as fast applicable without having a network at hand; the network aware part &mdash; do the subjects
 * exist, are they of the right kind, are the groups complete &mdash; is {@code FastRoutePlan}.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class FastRouteCapabilities {

    /** One update query family, that is one group of CIM classes updated by one query. */
    public enum Family {
        SWITCH, TERMINAL, DC_TERMINAL, ENERGY_CONSUMER, ENERGY_SOURCE, ASYNCHRONOUS_MACHINE, SYNCHRONOUS_MACHINE,
        EXTERNAL_NETWORK_INJECTION, EQUIVALENT_INJECTION, GENERATING_UNIT, STATIC_VAR_COMPENSATOR, SHUNT_COMPENSATOR,
        RATIO_TAP_CHANGER, PHASE_TAP_CHANGER, REGULATING_CONTROL, CS_CONVERTER, VS_CONVERTER, CONTROL_AREA,
        CURRENT_LIMIT, ACTIVE_POWER_LIMIT, APPARENT_POWER_LIMIT, VOLTAGE_LIMIT,
        AC_LINE_SEGMENT, SERIES_COMPENSATOR, EQUIVALENT_BRANCH, VOLTAGE_LEVEL
    }

    /**
     * How the values of a family reach the network.
     *
     * <p>Most of them go through the ordinary CGMES update workflow: the statements are written into a synthetic
     * document and the SPARQL query of the family reads them back, which is what makes a difference model behave
     * exactly like a partial steady state hypothesis file. A few equipment values have no update query at all
     * &mdash; nothing in the update path reads {@code ACLineSegment.r} or {@code VoltageLevel.highVoltageLimit}
     * &mdash; and are applied with plain IIDM setters instead, by {@code DirectEqApplier}.</p>
     */
    public enum Handler {
        /** The statement is written into the synthetic update document and read back by {@link FamilySpec#updateQuery()}. */
        UPDATE_QUERY,
        /** The statement is applied with an IIDM setter after the update workflow has run. */
        DIRECT_SETTER
    }

    /**
     * Whether applying a value of a family touches state IIDM stores <em>per variant</em>.
     *
     * <p>A network variant holds its own copy of the operating values &mdash; setpoints, switch states, tap
     * positions, terminal connections &mdash; but not of the equipment description: impedances, operational limit
     * values, voltage limits, the rating of an HVDC line and every IIDM <em>property</em> are single fields shared
     * by all variants. A difference applied while one variant is bound to a snapshot would therefore leak such a
     * write into every other variant of the same network, silently changing states a caller believes are fixed.
     * This enumeration is the machine readable form of that distinction, and
     * {@link #checkVariantSafe(DifferenceModelSet)} plus {@code FastRoutePlan} enforce it.</p>
     */
    public enum VariantSafety {
        /** Every IIDM target the update writes for this family is stored per variant. */
        SAFE,
        /**
         * Whether the write stays inside the variant depends on the receiving network: the value itself is per
         * variant, but a side effect of the update &mdash; creating an extension, raising a capability flag,
         * falling back to the simplified HVDC model &mdash; is not. {@code FastRoutePlan} decides it against the
         * network before anything is modified.
         */
        NETWORK_DEPENDENT,
        /** The update writes state shared by every variant, whatever the network looks like. */
        UNSAFE
    }

    /**
     * Properties an update query reads together.
     *
     * @param required properties that all have to be present for any property of this group to be read at all
     * @param optional properties the query reads in a nested optional block, so their absence hurts nothing but
     *                 their presence without the required ones is still useless
     */
    public record PropertyGroup(Set<String> required, Set<String> optional) {
        public PropertyGroup {
            required = Set.copyOf(required);
            optional = Set.copyOf(optional);
        }

        static PropertyGroup of(String... required) {
            return new PropertyGroup(Set.of(required), Set.of());
        }

        static PropertyGroup of(Set<String> required, Set<String> optional) {
            return new PropertyGroup(required, optional);
        }

        /** Every property this group mentions. */
        public Set<String> properties() {
            Set<String> all = new LinkedHashSet<>(required);
            all.addAll(optional);
            return all;
        }
    }

    /**
     * One family of the table.
     *
     * @param family        the family this specification describes
     * @param subsets       the CGMES profiles its properties may belong to. Operational limit values are equipment
     *                      data in CIM 2.4.15 and steady state data in CIM 3, so a limit family carries both
     * @param handler       how the values reach the network
     * @param updateQuery   the name of the query in the update catalogue that reads them, {@code null} for a
     *                      {@link Handler#DIRECT_SETTER} family
     * @param canonicalType the CIM class used when a difference does not say which one the subject has
     * @param rdfTypes      every CIM class the query accepts, the canonical one first
     * @param groups        the property groups the query reads
     * @param variantSafety whether applying a value of this family stays inside one network variant
     */
    public record FamilySpec(Family family, Set<CgmesSubset> subsets, Handler handler, String updateQuery,
                             String canonicalType, Set<String> rdfTypes, List<PropertyGroup> groups,
                             VariantSafety variantSafety) {

        public FamilySpec {
            subsets = Set.copyOf(subsets);
            rdfTypes = Set.copyOf(rdfTypes);
            groups = List.copyOf(groups);
            Objects.requireNonNull(variantSafety);
        }

        /**
         * A specification of a family whose values are all stored per variant.
         *
         * <p>Kept so that a caller written against the table before network variants existed still compiles; it is
         * the same as naming {@link VariantSafety#SAFE}.</p>
         */
        public FamilySpec(Family family, Set<CgmesSubset> subsets, Handler handler, String updateQuery,
                          String canonicalType, Set<String> rdfTypes, List<PropertyGroup> groups) {
            this(family, subsets, handler, updateQuery, canonicalType, rdfTypes, groups, VariantSafety.SAFE);
        }

        /** Whether the properties of this family may appear in a difference model of the given profile. */
        public boolean acceptsSubset(CgmesSubset subset) {
            return subsets.contains(subset);
        }

        /** Every property of every group of this family. */
        public Set<String> properties() {
            Set<String> all = new LinkedHashSet<>();
            groups.forEach(group -> all.addAll(group.properties()));
            return all;
        }

        /** The groups that mention the given property. */
        public List<PropertyGroup> groupsOf(String property) {
            return groups.stream().filter(group -> group.properties().contains(property)).toList();
        }
    }

    private static final String ACDC_TERMINAL_CONNECTED = "ACDCTerminal.connected";
    private static final String REGULATING_COND_EQ_CONTROL_ENABLED = "RegulatingCondEq.controlEnabled";
    private static final String ROTATING_MACHINE_P = "RotatingMachine.p";
    private static final String ROTATING_MACHINE_Q = "RotatingMachine.q";
    private static final String ACDC_CONVERTER_P = "ACDCConverter.p";
    private static final String ACDC_CONVERTER_Q = "ACDCConverter.q";
    private static final String ACDC_CONVERTER_TARGET_PPCC = "ACDCConverter.targetPpcc";
    private static final String ACDC_CONVERTER_TARGET_UDC = "ACDCConverter.targetUdc";
    private static final String AC_DC_CONVERTERS_QUERY = "acDcConverters";

    /** The families whose value lives in the equipment profile in CGMES 2.4.15 and in the steady state in CGMES 3. */
    private static final Set<Family> LIMIT_FAMILIES = Set.of(Family.CURRENT_LIMIT, Family.ACTIVE_POWER_LIMIT,
            Family.APPARENT_POWER_LIMIT, Family.VOLTAGE_LIMIT);

    /** The group every AC/DC converter query reads, whatever kind of converter it is. */
    private static final PropertyGroup AC_DC_CONVERTER_SETPOINTS = PropertyGroup.of(
            ACDC_CONVERTER_TARGET_PPCC, ACDC_CONVERTER_TARGET_UDC, ACDC_CONVERTER_P, ACDC_CONVERTER_Q);

    /**
     * Properties the update catalogue reads but a difference model can never apply in place, with the reason.
     *
     * <p>State variables are the result of a computation, not a hypothesis: a difference model of the steady state
     * hypothesis never carries them, and a difference model of the state variables profile describes a solved state
     * that the update workflow reaches through a full SV file, not statement by statement. Operational limits are
     * equipment values and are added by the limit families of a later work package.</p>
     */
    private static final Map<String, String> NOT_DIFFERENCE_UPDATABLE = notDifferenceUpdatable();

    private static Map<String, String> notDifferenceUpdatable() {
        Map<String, String> excluded = new HashMap<>();
        for (String property : List.of("SvPowerFlow.p", "SvPowerFlow.q", "SvPowerFlow.Terminal",
                "SvVoltage.v", "SvVoltage.angle", "SvVoltage.TopologicalNode",
                "SvInjection.pInjection", "SvInjection.qInjection", "SvInjection.TopologicalNode",
                "SvTapStep.position", "SvTapStep.TapChanger",
                "SvShuntCompensatorSections.sections", "SvShuntCompensatorSections.ShuntCompensator",
                "Terminal.TopologicalNode", "ACDCConverter.poleLossP")) {
            excluded.put(property, "state variable");
        }
        return Map.copyOf(excluded);
    }

    /**
     * Properties whose IIDM target is shared by every variant although the rest of their family is not.
     *
     * <p>One entry today: {@code ControlArea.pTolerance} is written as an IIDM <em>property</em> of the area, and
     * properties are a single map per identifiable, not one per variant.</p>
     */
    private static final Map<String, String> VARIANT_UNSAFE_PROPERTIES = Map.of(
            "ControlArea.pTolerance",
            "the control area tolerance is written as the IIDM property \"pTolerance\", and properties are not"
                    + " stored per variant");

    /** Why a family writes state shared by every variant, by family. */
    private static final Map<Family, String> VARIANT_UNSAFE_REASONS = variantUnsafeReasons();

    private static Map<Family, String> variantUnsafeReasons() {
        Map<Family, String> reasons = new java.util.EnumMap<>(Family.class);
        String limits = "operational limit values are not stored per variant in IIDM"
                + " (LoadingLimits.setPermanentLimit / setTemporaryLimitValue write the shared limits group)";
        reasons.put(Family.CURRENT_LIMIT, limits);
        reasons.put(Family.ACTIVE_POWER_LIMIT, limits);
        reasons.put(Family.APPARENT_POWER_LIMIT, limits);
        String voltageLimits = "voltage limits are not stored per variant in IIDM"
                + " (VoltageLevel.highVoltageLimit / lowVoltageLimit are plain fields)";
        reasons.put(Family.VOLTAGE_LIMIT, voltageLimits);
        reasons.put(Family.VOLTAGE_LEVEL, voltageLimits);
        String impedances = "branch impedances are not stored per variant in IIDM"
                + " (Line / BoundaryLine r, x, g, b are plain fields)";
        reasons.put(Family.AC_LINE_SEGMENT, impedances);
        reasons.put(Family.SERIES_COMPENSATOR, impedances);
        reasons.put(Family.EQUIVALENT_BRANCH, impedances);
        reasons.put(Family.CS_CONVERTER, "a line commutated converter update writes"
                + " LccConverterStation.powerFactor and lossFactor and HvdcLine.maxP, none of which is stored per"
                + " variant in IIDM");
        return Map.copyOf(reasons);
    }

    /** Why a family may write state shared by every variant, decided against the receiving network. */
    private static final Map<Family, String> VARIANT_NETWORK_DEPENDENT_REASONS = networkDependentReasons();

    private static Map<Family, String> networkDependentReasons() {
        Map<Family, String> reasons = new java.util.EnumMap<>(Family.class);
        String referencePriority = "setting a reference priority above zero creates the ReferencePriorities"
                + " extension when the generator has none, and creating an extension is not per variant";
        reasons.put(Family.SYNCHRONOUS_MACHINE, referencePriority);
        reasons.put(Family.EXTERNAL_NETWORK_INJECTION, referencePriority);
        reasons.put(Family.GENERATING_UNIT, "GeneratingUnit.normalPF creates the ActivePowerControl extension, or"
                + " writes the property CGMES.normalPF, when a generator of the unit has no such extension, and"
                + " neither is stored per variant");
        String tapChanger = "switching the regulation of a tap changer on raises its"
                + " loadTapChangingCapabilities flag, which is not stored per variant in IIDM";
        reasons.put(Family.RATIO_TAP_CHANGER, tapChanger);
        reasons.put(Family.PHASE_TAP_CHANGER, tapChanger);
        reasons.put(Family.REGULATING_CONTROL, tapChanger);
        reasons.put(Family.VS_CONVERTER, "in the simplified DC model - the default - a voltage source converter"
                + " update writes HvdcLine.maxP and VscConverterStation.lossFactor, which are not stored per"
                + " variant in IIDM");
        return Map.copyOf(reasons);
    }

    private static final List<FamilySpec> TABLE = table0();
    private static final Map<Family, FamilySpec> BY_FAMILY = byFamily();
    private static final Map<String, Set<Family>> BY_PROPERTY = byProperty();

    private FastRouteCapabilities() {
    }

    private static List<FamilySpec> table0() {
        List<FamilySpec> table = new ArrayList<>();
        table.add(ssh(Family.SWITCH, "switches", "Switch",
                Set.of("Switch", "Breaker", "Disconnector", "LoadBreakSwitch", "ProtectedSwitch",
                        "GroundDisconnector", "Jumper"),
                List.of(PropertyGroup.of("Switch.open"))));
        table.add(ssh(Family.TERMINAL, "terminals", "Terminal", Set.of("Terminal"),
                List.of(PropertyGroup.of(ACDC_TERMINAL_CONNECTED))));
        table.add(ssh(Family.DC_TERMINAL, "dcTerminals", "DCTerminal",
                Set.of("DCTerminal", "ACDCConverterDCTerminal"),
                List.of(PropertyGroup.of(ACDC_TERMINAL_CONNECTED))));
        table.add(ssh(Family.ENERGY_CONSUMER, "energyConsumers", "EnergyConsumer",
                Set.of("EnergyConsumer", "ConformLoad", "NonConformLoad", "StationSupply"),
                List.of(PropertyGroup.of("EnergyConsumer.p", "EnergyConsumer.q"))));
        table.add(ssh(Family.ENERGY_SOURCE, "energySources", "EnergySource", Set.of("EnergySource"),
                List.of(PropertyGroup.of("EnergySource.activePower", "EnergySource.reactivePower"))));
        table.add(ssh(Family.ASYNCHRONOUS_MACHINE, "asynchronousMachines", "AsynchronousMachine",
                Set.of("AsynchronousMachine"),
                List.of(PropertyGroup.of(Set.of(ROTATING_MACHINE_P, ROTATING_MACHINE_Q),
                        Set.of("AsynchronousMachine.asynchronousMachineType", REGULATING_COND_EQ_CONTROL_ENABLED)))));
        // The query reads p and q in two optional blocks, but the conversion only takes either of them when BOTH
        // are bound (SynchronousMachineConversion: the updated power flow has to be "defined"). A difference that
        // states the active power alone would therefore be read, accepted and silently not applied, so the two
        // travel together here exactly as they do for an asynchronous machine; the rest of the group is optional
        table.add(ssh(Family.SYNCHRONOUS_MACHINE, "synchronousMachinesForUpdate", "SynchronousMachine",
                Set.of("SynchronousMachine"),
                List.of(PropertyGroup.of(Set.of(ROTATING_MACHINE_P, ROTATING_MACHINE_Q),
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
                List.of(PropertyGroup.of(Set.of("EquivalentInjection.p", "EquivalentInjection.q"),
                        Set.of("EquivalentInjection.regulationStatus", "EquivalentInjection.regulationTarget")))));
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
                List.of(PropertyGroup.of(Set.of("RegulatingControl.enabled", "RegulatingControl.targetValue",
                                "RegulatingControl.targetValueUnitMultiplier", "RegulatingControl.discrete"),
                        Set.of("RegulatingControl.targetDeadband"))),
                VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.CS_CONVERTER, AC_DC_CONVERTERS_QUERY, "CsConverter", Set.of("CsConverter"),
                List.of(AC_DC_CONVERTER_SETPOINTS,
                        PropertyGroup.of("CsConverter.operatingMode", "CsConverter.pPccControl")),
                VariantSafety.UNSAFE));
        table.add(ssh(Family.VS_CONVERTER, AC_DC_CONVERTERS_QUERY, "VsConverter", Set.of("VsConverter"),
                List.of(AC_DC_CONVERTER_SETPOINTS,
                        PropertyGroup.of(Set.of("VsConverter.pPccControl", "VsConverter.qPccControl"),
                                Set.of("VsConverter.targetQpcc", "VsConverter.targetUpcc"))),
                VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.CONTROL_AREA, "controlAreas", "ControlArea", Set.of("ControlArea"),
                List.of(PropertyGroup.of(Set.of("ControlArea.netInterchange"), Set.of("ControlArea.pTolerance")))));
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
     * {@link #checkLimitProfile}.
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

    private static Map<Family, FamilySpec> byFamily() {
        Map<Family, FamilySpec> map = new java.util.EnumMap<>(Family.class);
        TABLE.forEach(spec -> map.put(spec.family(), spec));
        return Map.copyOf(map);
    }

    private static Map<String, Set<Family>> byProperty() {
        Map<String, Set<Family>> map = new HashMap<>();
        for (FamilySpec spec : TABLE) {
            for (String property : spec.properties()) {
                map.computeIfAbsent(property, p -> new LinkedHashSet<>()).add(spec.family());
            }
        }
        map.replaceAll((property, families) -> Set.copyOf(families));
        return Map.copyOf(map);
    }

    /** The whole table, in the order the families are declared. */
    public static List<FamilySpec> table() {
        return TABLE;
    }

    /** The specification of one family. */
    public static FamilySpec spec(Family family) {
        return BY_FAMILY.get(Objects.requireNonNull(family));
    }

    /** The families that read a property. Several, because CGMES shares properties between classes. */
    public static Set<Family> familiesOf(String property) {
        return BY_PROPERTY.getOrDefault(property, Set.of());
    }

    /** Whether any update query reads this property. */
    public static boolean isUpdatableProperty(String property) {
        return BY_PROPERTY.containsKey(property);
    }

    /** Why a property of the update catalogue can never be applied in place, or {@code null} when it can. */
    public static String excludedReason(String property) {
        return NOT_DIFFERENCE_UPDATABLE.get(property);
    }

    /** Every property of the update catalogue that is deliberately outside the in-place route. */
    public static Set<String> notDifferenceUpdatableProperties() {
        return NOT_DIFFERENCE_UPDATABLE.keySet();
    }

    /**
     * Whether writing a property of a family stays inside one network variant.
     *
     * <p>A property the table lists as shared &mdash; see {@link #VARIANT_UNSAFE_PROPERTIES} &mdash; is
     * {@link VariantSafety#UNSAFE} however safe the rest of its family is; otherwise the family decides.</p>
     *
     * @param family   the family the value would be applied through
     * @param property the CGMES property, or {@code null} to ask about the family as a whole
     * @return the verdict
     */
    public static VariantSafety variantSafety(Family family, String property) {
        Objects.requireNonNull(family);
        if (property != null && VARIANT_UNSAFE_PROPERTIES.containsKey(property)) {
            return VariantSafety.UNSAFE;
        }
        return spec(family).variantSafety();
    }

    /**
     * Why writing a property of a family may reach beyond one network variant.
     *
     * <p>The text names the IIDM attribute that is not stored per variant, because that is what a caller has to
     * act on: there is no option that makes such a write variant local, only a different way of getting the state
     * into the network.</p>
     *
     * @param family   the family
     * @param property the CGMES property, or {@code null} to ask about the family as a whole
     * @return the reason, or {@code null} when the value is stored per variant
     */
    public static String variantUnsafeReason(Family family, String property) {
        Objects.requireNonNull(family);
        if (property != null) {
            String shared = VARIANT_UNSAFE_PROPERTIES.get(property);
            if (shared != null) {
                return shared;
            }
        }
        return switch (spec(family).variantSafety()) {
            case UNSAFE -> VARIANT_UNSAFE_REASONS.get(family);
            case NETWORK_DEPENDENT -> VARIANT_NETWORK_DEPENDENT_REASONS.get(family);
            case SAFE -> null;
        };
    }

    /**
     * Decide from the documents alone whether a difference model set could be applied to a single variant of a
     * network without touching the others.
     *
     * <p>Network free, exactly like {@link #check(DifferenceModelSet)}, and for the same reason: a database that
     * stores differences flags each of them once, when it is written, so that a planner can refuse a path without
     * a network at hand. The verdict is deliberately the weak one &mdash; a statement blocks only when
     * <em>every</em> family that could carry it is {@link VariantSafety#UNSAFE} for it &mdash; because the
     * network aware half in {@code FastRoutePlan} runs on every variant update anyway and decides the
     * {@link VariantSafety#NETWORK_DEPENDENT} cases against the receiving network.</p>
     *
     * @param diffs the difference models
     * @return {@link CgmesDiffImport.Route#FAST} when nothing in the documents forbids a variant local update,
     *         the answer of {@link #check(DifferenceModelSet)} when that already refuses the in-place route, and
     *         {@link CgmesDiffImport.Route#SLOW_REQUIRED} with one reason per shared write otherwise
     */
    public static CgmesDiffImport.Decision checkVariantSafe(DifferenceModelSet diffs) {
        CgmesDiffImport.Decision structural = check(diffs);
        if (structural.route() != CgmesDiffImport.Route.FAST) {
            return structural;
        }
        List<CgmesDiffImport.BlockingStatement> blocking = new ArrayList<>();
        for (DifferenceModel model : diffs.models().values()) {
            CgmesSubset subset = model.header().subset();
            List<CgmesStatement> statements = new ArrayList<>(model.forward());
            statements.addAll(model.reverse());
            for (CgmesStatement statement : statements) {
                if (statement.isType()) {
                    continue;
                }
                sharedWriteReason(subset, statement.property())
                        .ifPresent(reason -> blocking.add(
                                new CgmesDiffImport.BlockingStatement(subset, statement, reason)));
            }
        }
        return blocking.isEmpty() ? structural
                : new CgmesDiffImport.Decision(CgmesDiffImport.Route.SLOW_REQUIRED, blocking);
    }

    /**
     * The reason a property can only be written for every variant at once, or empty when some family that accepts
     * it in this profile writes it per variant.
     */
    private static java.util.Optional<String> sharedWriteReason(CgmesSubset subset, String property) {
        List<Family> candidates = familiesOf(property).stream()
                .filter(family -> spec(family).acceptsSubset(subset))
                .toList();
        if (candidates.isEmpty()) {
            // check() already answered FAST, so this property is carried by some family; nothing to add
            return java.util.Optional.empty();
        }
        if (candidates.stream().anyMatch(family -> variantSafety(family, property) != VariantSafety.UNSAFE)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(variantUnsafeReason(candidates.get(0), property));
    }

    /**
     * Decide from the documents alone whether a difference model set could be applied in place.
     *
     * <p>Every violation is collected rather than the first one thrown, because the point of the decision function
     * is to tell a caller <em>everything</em> that stands in the way. A {@link CgmesDiffImport.Route#FAST} answer
     * here means "nothing in the document forbids it"; whether the subjects exist and the groups are complete is
     * decided against a network afterwards.</p>
     */
    public static CgmesDiffImport.Decision check(DifferenceModelSet diffs) {
        Objects.requireNonNull(diffs);
        List<CgmesDiffImport.BlockingStatement> blocking = new ArrayList<>();
        boolean anyStatement = false;
        for (DifferenceModel model : diffs.models().values()) {
            if (model.isEmpty()) {
                continue;
            }
            anyStatement = true;
            checkModel(model, blocking);
        }
        if (!anyStatement) {
            return new CgmesDiffImport.Decision(CgmesDiffImport.Route.NOOP, List.of());
        }
        return new CgmesDiffImport.Decision(
                blocking.isEmpty() ? CgmesDiffImport.Route.FAST : CgmesDiffImport.Route.SLOW_REQUIRED, blocking);
    }

    private static void checkModel(DifferenceModel model, List<CgmesDiffImport.BlockingStatement> blocking) {
        CgmesSubset subset = model.header().subset();
        if (subset != CgmesSubset.STEADY_STATE_HYPOTHESIS && subset != CgmesSubset.EQUIPMENT) {
            blocking.add(new CgmesDiffImport.BlockingStatement(subset, null,
                    "the " + subset.getIdentifier() + " profile cannot be updated in place"));
            return;
        }
        Map<CgmesStatement.Key, CgmesStatement> forwardByKey = new HashMap<>();
        Set<CgmesStatement.Key> duplicated = new LinkedHashSet<>();
        for (CgmesStatement statement : model.forward()) {
            CgmesStatement previous = forwardByKey.put(statement.key(), statement);
            if (previous != null && !previous.equals(statement)) {
                duplicated.add(statement.key());
            }
        }
        Set<String> forwardTypes = typeValues(model.forward());

        for (CgmesStatement statement : model.forward()) {
            if (statement.isType()) {
                // A forward only type statement on an object the network already holds is a no-op; whether it does
                // exist is decided by the network aware plan
                continue;
            }
            checkProperty(subset, statement, blocking);
            checkLimitProfile(model, statement, blocking);
        }
        for (CgmesStatement statement : model.reverse()) {
            if (statement.isType()) {
                if (!forwardTypes.contains(typeKey(statement))) {
                    blocking.add(new CgmesDiffImport.BlockingStatement(subset, statement,
                            "object removal cannot be applied in place"));
                }
                continue;
            }
            if (!forwardByKey.containsKey(statement.key()) && !isOptionalEverywhere(statement.property())) {
                blocking.add(new CgmesDiffImport.BlockingStatement(subset, statement,
                        "removing a property value cannot be applied in place"));
            }
        }
        for (CgmesStatement.Key key : duplicated) {
            blocking.add(new CgmesDiffImport.BlockingStatement(subset, forwardByKey.get(key),
                    "several values for one property"));
        }
    }

    private static void checkProperty(CgmesSubset subset, CgmesStatement statement,
                                      List<CgmesDiffImport.BlockingStatement> blocking) {
        String excluded = NOT_DIFFERENCE_UPDATABLE.get(statement.property());
        if (excluded != null) {
            blocking.add(new CgmesDiffImport.BlockingStatement(subset, statement,
                    "property is not part of the in-place update (" + excluded + ")"));
            return;
        }
        if (!isUpdatableProperty(statement.property())) {
            blocking.add(new CgmesDiffImport.BlockingStatement(subset, statement,
                    "property is not part of the in-place update"));
            return;
        }
        if (familiesOf(statement.property()).stream().noneMatch(family -> spec(family).acceptsSubset(subset))) {
            blocking.add(new CgmesDiffImport.BlockingStatement(subset, statement,
                    statement.property() + " is not a " + subset.getIdentifier() + " property"));
        }
    }

    /**
     * Where a limit value lives depends on the CIM version, and the difference model says which one it uses.
     *
     * <p>CGMES 2.4.15 has {@code <Class>.value} in the equipment profile; CGMES 3 moved it to the steady state
     * hypothesis and left {@code normalValue} behind in the equipment model. A document that puts a value in the
     * other profile describes something the receiver would never read, so it is refused rather than applied to
     * nothing.</p>
     */
    private static void checkLimitProfile(DifferenceModel model, CgmesStatement statement,
                                          List<CgmesDiffImport.BlockingStatement> blocking) {
        if (!isLimitValue(statement.property())) {
            return;
        }
        CgmesSubset subset = model.header().subset();
        boolean cim16 = CgmesNamespace.CIM_16_NAMESPACE.equals(model.header().cimNamespace());
        if (cim16 && subset == CgmesSubset.STEADY_STATE_HYPOTHESIS) {
            blocking.add(new CgmesDiffImport.BlockingStatement(subset, statement,
                    "in CGMES 2.4.15 limit values are equipment data (EQ profile)"));
        } else if (!cim16 && subset == CgmesSubset.EQUIPMENT) {
            blocking.add(new CgmesDiffImport.BlockingStatement(subset, statement,
                    "in CGMES 3 limit values are steady state data (SSH profile)"));
        }
    }

    private static boolean isLimitValue(String property) {
        return familiesOf(property).stream().anyMatch(LIMIT_FAMILIES::contains);
    }

    /**
     * Whether a property is optional in every group that reads it.
     *
     * <p>A direction that states such a property while the other does not is not a removal: the update query reads
     * it in a nested optional block, so a CGMES file describing that state simply leaves the property out, which is
     * exactly what a partial steady state hypothesis file does. A required property is a different matter, because
     * its absence makes the whole group unreadable.</p>
     */
    private static boolean isOptionalEverywhere(String property) {
        Set<Family> families = familiesOf(property);
        if (families.isEmpty()) {
            return false;
        }
        return families.stream()
                .flatMap(family -> spec(family).groupsOf(property).stream())
                .noneMatch(group -> group.required().contains(property));
    }

    private static Set<String> typeValues(List<CgmesStatement> statements) {
        Set<String> types = new HashSet<>();
        statements.stream().filter(CgmesStatement::isType).forEach(s -> types.add(typeKey(s)));
        return types;
    }

    private static String typeKey(CgmesStatement statement) {
        return statement.subjectId() + " " + statement.value();
    }
}
