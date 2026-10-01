/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.export.ControlAreaFamily;
import com.powsybl.cgmes.conversion.export.HvdcFamily;
import com.powsybl.cgmes.conversion.export.LimitFamily;
import com.powsybl.cgmes.conversion.export.MachineFamily;
import com.powsybl.cgmes.conversion.export.RegulatingControlFamily;
import com.powsybl.cgmes.conversion.export.SwitchAndTerminalFamily;
import com.powsybl.cgmes.conversion.export.TapChangerAndShuntFamily;
import com.powsybl.cgmes.conversion.mapping.Block;
import com.powsybl.cgmes.conversion.mapping.LoadRows;
import com.powsybl.cgmes.conversion.mapping.PlainFamily;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.iidm.network.Load;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the in-place difference model update can do, as one declarative table.
 *
 * <p>Applying a difference model in place means feeding its forward statements into the ordinary CGMES update
 * workflow, which is driven by the SPARQL queries of {@code CIM16-update.sparql}. A statement can therefore only be
 * applied when some update query reads the property it states, on an object of a class that query accepts &mdash; or,
 * for the few equipment values no query reads, when a family names them as {@link Handler#DIRECT_SETTER}. This class
 * is the machine readable form of that catalogue: one {@link FamilySpec} per family, naming the query (or the direct
 * setter), the CIM classes it accepts and the <em>property groups</em> it reads.</p>
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

    /**
     * One family, that is one group of CIM classes updated together. Each constant is named after the CIM class (or
     * class group) it stands for; the operational limit families ({@code CURRENT_LIMIT} &hellip; {@code VOLTAGE_LIMIT})
     * accept the equipment profile as well, and the last four ({@code AC_LINE_SEGMENT} &hellip;
     * {@code VOLTAGE_LEVEL}) are {@link Handler#DIRECT_SETTER} families of the equipment profile.
     */
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

    /** The families whose value lives in the equipment profile in CGMES 2.4.15 and in the steady state in CGMES 3. */
    private static final Set<Family> LIMIT_FAMILIES = Set.of(Family.CURRENT_LIMIT, Family.ACTIVE_POWER_LIMIT,
            Family.APPARENT_POWER_LIMIT, Family.VOLTAGE_LIMIT);

    /** The setpoint block of a converter: the CGMES update reads these four together (and of both converters of a line). */
    static final PropertyGroup AC_DC_CONVERTER_SETPOINTS = PropertyGroup.of(HvdcFamily.SETPOINTS.toArray(String[]::new));

    /**
     * Properties the update catalogue reads but a difference model can never apply in place, all for the reason
     * {@link #STATE_VARIABLE}.
     *
     * <p>State variables are the result of a computation, not a hypothesis: a difference model of the steady state
     * hypothesis never carries them, and a difference model of the state variables profile describes a solved state
     * that the update workflow reaches through a full SV file, not statement by statement.</p>
     */
    private static final Set<String> NOT_DIFFERENCE_UPDATABLE = Set.of("SvPowerFlow.p", "SvPowerFlow.q",
            "SvPowerFlow.Terminal", "SvVoltage.v", "SvVoltage.angle", "SvVoltage.TopologicalNode",
            "SvInjection.pInjection", "SvInjection.qInjection", "SvInjection.TopologicalNode",
            "SvTapStep.position", "SvTapStep.TapChanger",
            "SvShuntCompensatorSections.sections", "SvShuntCompensatorSections.ShuntCompensator",
            "Terminal.TopologicalNode", "ACDCConverter.poleLossP");

    private static final String STATE_VARIABLE = "state variable";

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
        Map<Family, String> reasons = new EnumMap<>(Family.class);
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
        Map<Family, String> reasons = new EnumMap<>(Family.class);
        String referencePriority = "setting a reference priority above zero creates the ReferencePriorities"
                + " extension when the generator has none, and creating an extension is not per variant";
        // powsybl-core #3699: the VoltageRegulation of a holder exists in every variant or in none
        String generatorRegulation = "updating the voltage regulation of a generator creates its VoltageRegulation"
                + " when it has none, and the object exists in every variant";
        reasons.put(Family.SYNCHRONOUS_MACHINE, referencePriority + "; " + generatorRegulation);
        reasons.put(Family.EXTERNAL_NETWORK_INJECTION, referencePriority + "; " + generatorRegulation);
        reasons.put(Family.EQUIVALENT_INJECTION, "switching the regulation of an EquivalentInjection on creates the"
                + " VoltageRegulation of its generator when it has none, and the object exists in every variant");
        reasons.put(Family.GENERATING_UNIT, "GeneratingUnit.normalPF creates the ActivePowerControl extension, or"
                + " writes the property CGMES.normalPF, when a generator of the unit has no such extension, and"
                + " neither is stored per variant");
        String tapChanger = "switching the regulation of a tap changer on raises its"
                + " loadTapChangingCapabilities flag, which is not stored per variant in IIDM";
        reasons.put(Family.RATIO_TAP_CHANGER, tapChanger);
        reasons.put(Family.PHASE_TAP_CHANGER, tapChanger);
        reasons.put(Family.REGULATING_CONTROL, tapChanger + "; " + generatorRegulation);
        reasons.put(Family.VS_CONVERTER, "in the simplified DC model - the default - a voltage source converter"
                + " update writes HvdcLine.maxP and VscConverterStation.lossFactor, which are not stored per"
                + " variant in IIDM; in the detailed DC model the update rebuilds the VoltageRegulation of the"
                + " converter, which creates it when absent and replaces its regulating terminal when the control"
                + " kind changes, neither of which is per variant (IIDM refuses a terminal change with several"
                + " variants)");
        // powsybl-core #4085
        reasons.put(Family.TERMINAL, "disconnecting a terminal of a node/breaker voltage level creates the"
                + " fictitious switch of that terminal when it does not exist yet; the switch is created in every"
                + " variant (for a terminal of a switch, the switch itself is re-created with the open state of the"
                + " working variant in all variants)");
        return Map.copyOf(reasons);
    }

    /** The families of an IIDM load, in the order of the table. */
    private static final Map<Family, PlainFamily<Load>> LOAD_FAMILIES = loadFamilies();

    private static final List<FamilySpec> TABLE = table0();
    private static final Map<Family, FamilySpec> BY_FAMILY = byFamily();
    private static final Map<String, Set<Family>> BY_PROPERTY = byProperty();
    private static final Map<String, Family> BY_CLASS = byClass();

    private FastRouteCapabilities() {
    }

    private static Map<Family, PlainFamily<Load>> loadFamilies() {
        Map<Family, PlainFamily<Load>> families = new EnumMap<>(Family.class);
        families.put(Family.ENERGY_CONSUMER, LoadRows.ENERGY_CONSUMER);
        families.put(Family.ENERGY_SOURCE, LoadRows.ENERGY_SOURCE);
        families.put(Family.ASYNCHRONOUS_MACHINE, LoadRows.ASYNCHRONOUS_MACHINE);
        return Collections.unmodifiableMap(families);
    }

    private static List<FamilySpec> table0() {
        List<FamilySpec> table = new ArrayList<>();
        table.add(ssh(Family.SWITCH, SwitchAndTerminalFamily.SWITCH, VariantSafety.SAFE));
        table.add(ssh(Family.TERMINAL, SwitchAndTerminalFamily.TERMINAL, VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.DC_TERMINAL, SwitchAndTerminalFamily.DC_TERMINAL, VariantSafety.SAFE));
        // The families of a load are data rows: query, classes and the one group they are read in come from the rows
        // (the asynchronous machine's four properties are one required block, powsybl-core #4103)
        LOAD_FAMILIES.forEach((family, rows) -> table.add(ssh(family, rows.block(), VariantSafety.SAFE)));
        table.add(ssh(Family.SYNCHRONOUS_MACHINE, MachineFamily.SYNCHRONOUS_MACHINE, VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.EXTERNAL_NETWORK_INJECTION, MachineFamily.EXTERNAL_NETWORK_INJECTION, VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.EQUIVALENT_INJECTION, MachineFamily.EQUIVALENT_INJECTION, VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.GENERATING_UNIT, MachineFamily.GENERATING_UNIT, VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.STATIC_VAR_COMPENSATOR, TapChangerAndShuntFamily.STATIC_VAR_COMPENSATOR, VariantSafety.SAFE));
        table.add(ssh(Family.SHUNT_COMPENSATOR, TapChangerAndShuntFamily.SHUNT_COMPENSATOR, VariantSafety.SAFE));
        table.add(ssh(Family.RATIO_TAP_CHANGER, TapChangerAndShuntFamily.RATIO_TAP_CHANGER, VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.PHASE_TAP_CHANGER, TapChangerAndShuntFamily.PHASE_TAP_CHANGER, VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.REGULATING_CONTROL, RegulatingControlFamily.REGULATING_CONTROL, VariantSafety.NETWORK_DEPENDENT));
        table.add(converter(Family.CS_CONVERTER, HvdcFamily.CS_CONVERTER, VariantSafety.UNSAFE));
        table.add(converter(Family.VS_CONVERTER, HvdcFamily.VS_CONVERTER, VariantSafety.NETWORK_DEPENDENT));
        table.add(ssh(Family.CONTROL_AREA, ControlAreaFamily.CONTROL_AREA, VariantSafety.SAFE));
        // Operational limit values: equipment data in CIM 2.4.15, steady state data in CIM 3. One OperationalLimit
        // is one CGMES object with one value, so every group holds a single property.
        table.add(limit(Family.CURRENT_LIMIT, LimitFamily.CURRENT_LIMIT));
        table.add(limit(Family.ACTIVE_POWER_LIMIT, LimitFamily.ACTIVE_POWER_LIMIT));
        table.add(limit(Family.APPARENT_POWER_LIMIT, LimitFamily.APPARENT_POWER_LIMIT));
        table.add(limit(Family.VOLTAGE_LIMIT, LimitFamily.VOLTAGE_LIMIT_VALUE));
        // Equipment values nothing in the update path reads, applied with IIDM setters
        table.add(directSetter(Family.AC_LINE_SEGMENT, LimitFamily.AC_LINE_SEGMENT));
        table.add(directSetter(Family.SERIES_COMPENSATOR, LimitFamily.SERIES_COMPENSATOR));
        table.add(directSetter(Family.EQUIVALENT_BRANCH, LimitFamily.EQUIVALENT_BRANCH));
        table.add(directSetter(Family.VOLTAGE_LEVEL, LimitFamily.VOLTAGE_LEVEL));
        return List.copyOf(table);
    }

    /** A family of the steady state hypothesis whose one group is the block a family of the mapping declares. */
    private static FamilySpec ssh(Family family, Block block, VariantSafety variantSafety) {
        return ssh(family, block.updateQuery(), block.cimClasses().get(0), Set.copyOf(block.cimClasses()),
                List.of(new PropertyGroup(Set.copyOf(block.required()), Set.copyOf(block.optional()))), variantSafety);
    }

    /** A converter family: the setpoint block every converter has, then the control block of its class. */
    private static FamilySpec converter(Family family, Block block, VariantSafety variantSafety) {
        FamilySpec spec = ssh(family, block, variantSafety);
        return new FamilySpec(family, spec.subsets(), spec.handler(), spec.updateQuery(), spec.canonicalType(), spec.rdfTypes(),
                List.of(AC_DC_CONVERTER_SETPOINTS, spec.groups().get(0)), variantSafety);
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
    private static FamilySpec limit(Family family, Block block) {
        return new FamilySpec(family, Set.of(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS),
                Handler.UPDATE_QUERY, block.updateQuery(), block.cimClasses().get(0), Set.copyOf(block.cimClasses()),
                List.of(PropertyGroup.of(block.required().toArray(String[]::new))), VariantSafety.UNSAFE);
    }

    /**
     * A family whose values are applied with IIDM setters. Every property is a group of its own: there is no query
     * that would read them together, so none of them can make another one unreadable.
     */
    private static FamilySpec directSetter(Family family, Block block) {
        return new FamilySpec(family, Set.of(CgmesSubset.EQUIPMENT), Handler.DIRECT_SETTER, null, block.cimClasses().get(0),
                Set.copyOf(block.cimClasses()), block.required().stream().map(PropertyGroup::of).toList(), VariantSafety.UNSAFE);
    }

    private static Map<Family, FamilySpec> byFamily() {
        Map<Family, FamilySpec> map = new EnumMap<>(Family.class);
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

    /** Every CIM class of the table and the one family that accepts it. */
    private static Map<String, Family> byClass() {
        Map<String, Family> map = new HashMap<>();
        for (FamilySpec spec : TABLE) {
            spec.rdfTypes().forEach(rdfType -> {
                if (map.put(rdfType, spec.family()) != null) {
                    throw new IllegalStateException("two families accept the class " + rdfType);
                }
            });
        }
        return Map.copyOf(map);
    }

    /** The family whose CIM classes contain the given one; every class of the table belongs to exactly one family. */
    static Family familyOfClass(String cimClass) {
        return Objects.requireNonNull(BY_CLASS.get(cimClass), cimClass);
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
        return NOT_DIFFERENCE_UPDATABLE.contains(property) ? STATE_VARIABLE : null;
    }

    /** Every property of the update catalogue that is deliberately outside the in-place route. */
    public static Set<String> notDifferenceUpdatableProperties() {
        return NOT_DIFFERENCE_UPDATABLE;
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
    private static Optional<String> sharedWriteReason(CgmesSubset subset, String property) {
        List<Family> candidates = familiesOf(property).stream()
                .filter(family -> spec(family).acceptsSubset(subset))
                .toList();
        if (candidates.isEmpty()) {
            // No family accepts the property in this profile, so there is no shared write to report; the branch is
            // reachable even after check() answered FAST, and it keeps candidates.get(0) below safe
            return Optional.empty();
        }
        if (candidates.stream().anyMatch(family -> variantSafety(family, property) != VariantSafety.UNSAFE)) {
            return Optional.empty();
        }
        return Optional.of(variantUnsafeReason(candidates.get(0), property));
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
        String excluded = excludedReason(statement.property());
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
