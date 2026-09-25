/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.Family;
import com.powsybl.cgmes.conversion.export.CgmesExportUtil;
import com.powsybl.cgmes.conversion.export.CgmesLimitIndex;
import com.powsybl.cgmes.extensions.CgmesTapChanger;
import com.powsybl.cgmes.extensions.CgmesTapChangers;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.iidm.network.Area;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.HvdcConverterStation;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.LccConverterStation;
import com.powsybl.iidm.network.LimitType;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.LineCommutatedConverter;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.ShuntCompensatorModelType;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.iidm.network.VscConverterStation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Finds out which network object a difference model statement is about, and which CIM class it has.
 *
 * <p>A difference model names its subjects by CGMES master resource identifier. Most of them are equipment and have
 * that identifier as their IIDM identifier, but the interesting ones are not: a terminal, a tap changer, a
 * regulating control, a generating unit and an equivalent injection are CGMES objects that IIDM does not model as
 * objects of their own. The CGMES importer leaves each of them behind as an alias or a property of the equipment
 * that carries them, and this class walks those back.</p>
 *
 * <p>The class of a subject matters as much as the object, because the update queries select on {@code rdf:type} and
 * a synthetic update document has to state one. The network decides it, not the class hint a document carries: a
 * producer may call a load {@code ConformLoad} while the receiving network read it as a {@code NonConformLoad}, and
 * writing the producer's word would make the update read nothing at all. The hint is only used where the network
 * genuinely cannot tell, namely for the five flavours of phase tap changer.</p>
 *
 * <p>The secondary index over properties and extensions is built lazily and exactly once, with a single pass over
 * the generators, shunts, static var compensators, transformers and boundary lines, so a difference that only names
 * equipment never pays for it.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class DiffSubjectResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiffSubjectResolver.class);

    private static final String URN_UUID = "urn:uuid:";
    private static final String TERMINAL_ALIAS_PREFIX = "CGMES.Terminal";
    private static final String DC_TERMINAL_ALIAS_PREFIX = "CGMES.DCTerminal";
    private static final String RATIO_TAP_CHANGER_ALIAS_PREFIX = "CGMES." + CgmesNames.RATIO_TAP_CHANGER;
    private static final String PHASE_TAP_CHANGER_ALIAS_PREFIX = "CGMES." + CgmesNames.PHASE_TAP_CHANGER;
    /** How the importer names the switch it creates for a disconnected terminal, see {@code TerminalConversion}. */
    private static final String FICTITIOUS_SWITCH_SUFFIX = "_SW_fict";
    private static final String RATIO_TAP_CHANGER_ATTRIBUTE_PREFIX = "ratioTapChanger";
    private static final String PHASE_TAP_CHANGER_ATTRIBUTE_PREFIX = "phaseTapChanger";
    private static final String HIGH_VOLTAGE_LIMIT_ATTRIBUTE = "highVoltageLimit";
    private static final String LOW_VOLTAGE_LIMIT_ATTRIBUTE = "lowVoltageLimit";
    private static final String MERGED_VOLTAGE_LEVEL_ALIAS_PREFIX =
            Conversion.CGMES_PREFIX_ALIAS_PROPERTIES + "MergedVoltageLevel";

    /**
     * How {@code DiffProbes} has to read {@link ResolvedSubject#ownerAttributePrefix()}.
     */
    enum ProbeKind {
        /** There is none: the probes of the owner describe the subject. */
        NONE,
        /** A prefix such as {@code phaseTapChanger} or {@code ratioTapChanger2}, to which suffixes are appended. */
        TAP_CHANGER_PREFIX,
        /** A complete attribute key, used as it stands: one operational limit, or a voltage level limit. */
        ATTRIBUTE_KEY
    }

    /**
     * What a difference model subject turned out to be.
     *
     * @param family              the update query family the subject belongs to
     * @param rdfType             the CIM class to write for it
     * @param about               the subject as an {@code rdf:about} value, in the form the receiving network's
     *                            identifiers take, so that the synthetic update document resolves to the same object
     * @param owner               the IIDM object carrying the subject, which is the one a change of it is recorded on
     * @param ownerAttributePrefix what a change of this subject is recorded under on its owner, read according to
     *                            {@code probeKind}; empty when there is none
     * @param probeKind           how to read {@code ownerAttributePrefix}
     * @param iidmIds             every IIDM object the update of this subject touches, which is what a scoped update
     *                            has to visit
     */
    record ResolvedSubject(Family family, String rdfType, String about, Identifiable<?> owner,
                           String ownerAttributePrefix, ProbeKind probeKind, Set<String> iidmIds) {
    }

    private final Network network;

    /** Subjects that are not equipment: regulating controls, generating units, equivalent injections. */
    private Map<String, ResolvedSubject> secondaryIndex;
    /** The index of the CGMES limit identifiers, built with the secondary index and shared with the applier. */
    private CgmesLimitIndex limitIndex;

    DiffSubjectResolver(Network network) {
        this.network = Objects.requireNonNull(network);
    }

    /**
     * Resolve one subject.
     *
     * @param subjectId       the identifier as the difference model states it, already normalized
     * @param properties      the properties the difference states about it, which decide whether the family that was
     *                        found can carry them
     * @param classNameHint   the class the producer gave the subject, or {@code null}
     * @return the resolution, or empty when the subject is unknown or the family cannot carry the properties
     */
    Optional<ResolvedSubject> resolve(String subjectId, Set<String> properties, String classNameHint) {
        Optional<ResolvedSubject> resolved = resolveSubject(subjectId, classNameHint);
        if (resolved.isEmpty()) {
            return resolved;
        }
        Set<String> familyProperties = FastRouteCapabilities.spec(resolved.get().family()).properties();
        for (String property : properties) {
            if (!familyProperties.contains(property)) {
                return Optional.empty();
            }
        }
        return resolved;
    }

    /** Why a subject could not be resolved, as a sentence to append to "&lt;id&gt;: ". */
    String reasonFor(String subjectId, Set<String> properties, String classNameHint) {
        Optional<ResolvedSubject> resolved = resolveSubject(subjectId, classNameHint);
        if (resolved.isEmpty()) {
            return unresolvedReason(subjectId);
        }
        Family family = resolved.get().family();
        Set<String> familyProperties = FastRouteCapabilities.spec(family).properties();
        String offending = properties.stream().filter(p -> !familyProperties.contains(p)).findFirst().orElse("?");
        return "property " + offending + " is not updatable on a " + family;
    }

    private String unresolvedReason(String subjectId) {
        Identifiable<?> identifiable = find(subjectId);
        if (identifiable instanceof Switch sw && isBranchModelledAsSwitch(sw)) {
            return "it is modelled as a switch of a branch class, whose state is carried by its terminals";
        }
        return identifiable == null
                ? "no object of this network has this identifier"
                : "a " + identifiable.getType() + " carries no steady state hypothesis properties of its own";
    }

    private Optional<ResolvedSubject> resolveSubject(String subjectId, String classNameHint) {
        for (String candidate : List.of(subjectId, URN_UUID + subjectId)) {
            Identifiable<?> identifiable = network.getIdentifiable(candidate);
            if (identifiable == null) {
                continue;
            }
            String about = candidate.equals(subjectId) ? "#_" + subjectId : candidate;
            Optional<ResolvedSubject> resolved = identifiable.getId().equals(candidate)
                    ? equipment(identifiable, about, classNameHint)
                    : byAlias(identifiable, candidate, about, classNameHint);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.ofNullable(secondaryIndex().get(subjectId));
    }

    private Identifiable<?> find(String subjectId) {
        Identifiable<?> identifiable = network.getIdentifiable(subjectId);
        return identifiable != null ? identifiable : network.getIdentifiable(URN_UUID + subjectId);
    }

    private Optional<ResolvedSubject> equipment(Identifiable<?> identifiable, String about, String classNameHint) {
        String originalClass = identifiable.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS);
        Set<String> ids = Set.of(identifiable.getId());
        return switch (identifiable) {
            case Switch sw -> isBranchModelledAsSwitch(sw) ? Optional.empty()
                    : Optional.of(of(Family.SWITCH, typeOf(Family.SWITCH, originalClass, null), about, identifiable, ids));
            case Load load -> loadFamily(load, originalClass)
                    .map(family -> of(family, typeOf(family, originalClass, null), about, identifiable, ids));
            case Generator generator -> generatorFamily(generator, originalClass)
                    .map(family -> of(family, typeOf(family, originalClass, null), about, identifiable, ids));
            case ShuntCompensator shunt -> Optional.of(of(Family.SHUNT_COMPENSATOR,
                    shunt.getModelType() == ShuntCompensatorModelType.LINEAR
                            ? "LinearShuntCompensator" : "NonlinearShuntCompensator",
                    about, identifiable, ids));
            case StaticVarCompensator svc -> Optional.of(of(Family.STATIC_VAR_COMPENSATOR, "StaticVarCompensator",
                    about, svc, ids));
            case LccConverterStation station -> Optional.of(converter(Family.CS_CONVERTER, about, station));
            case LineCommutatedConverter converter -> Optional.of(of(Family.CS_CONVERTER, "CsConverter", about,
                    converter, Set.of(converter.getId())));
            case VscConverterStation station -> Optional.of(converter(Family.VS_CONVERTER, about, station));
            case VoltageSourceConverter converter -> Optional.of(of(Family.VS_CONVERTER, "VsConverter", about,
                    converter, Set.of(converter.getId())));
            case Area area -> Optional.of(of(Family.CONTROL_AREA, "ControlArea", about, area, ids));
            case Line line -> branchFamily(originalClass)
                    .map(family -> of(family, FastRouteCapabilities.spec(family).canonicalType(), about, line, ids));
            case BoundaryLine boundaryLine -> branchFamily(originalClass)
                    .map(family -> of(family, FastRouteCapabilities.spec(family).canonicalType(), about,
                            boundaryLine, ids));
            case VoltageLevel voltageLevel -> isMergedVoltageLevel(voltageLevel) ? Optional.empty()
                    : Optional.of(of(Family.VOLTAGE_LEVEL, "VoltageLevel", about, voltageLevel, ids));
            default -> Optional.empty();
        };
    }

    /**
     * The family of a branch subject, by the CIM class the import recorded. A transformer, or a class this library
     * does not know, has no in-place impedance update: CGMES holds the impedance of a transformer per end and the
     * import folds both ends into one IIDM value.
     */
    private static Optional<Family> branchFamily(String originalClass) {
        return Optional.ofNullable(switch (originalClass == null ? CgmesNames.AC_LINE_SEGMENT : originalClass) {
            case CgmesNames.AC_LINE_SEGMENT -> Family.AC_LINE_SEGMENT;
            case CgmesNames.SERIES_COMPENSATOR -> Family.SERIES_COMPENSATOR;
            case CgmesNames.EQUIVALENT_BRANCH -> Family.EQUIVALENT_BRANCH;
            default -> null;
        });
    }

    /** A voltage level that several CGMES VoltageLevel objects were merged into has no single subject. */
    private static boolean isMergedVoltageLevel(VoltageLevel voltageLevel) {
        return voltageLevel.getAliasFromType(MERGED_VOLTAGE_LEVEL_ALIAS_PREFIX + "1").isPresent()
                || voltageLevel.getAliasFromType(MERGED_VOLTAGE_LEVEL_ALIAS_PREFIX + "2").isPresent();
    }

    /**
     * A converter station of the simple HVDC model: its steady state hypothesis values are those of the HVDC line,
     * which is therefore the object a change of them is recorded on and the one a scoped update has to visit.
     */
    private static ResolvedSubject converter(Family family, String about, Identifiable<?> station) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(station.getId());
        Identifiable<?> owner = station;
        if (station instanceof HvdcConverterStation<?> converterStation
                && converterStation.getHvdcLine() != null) {
            ids.add(converterStation.getHvdcLine().getId());
            owner = converterStation.getHvdcLine();
        }
        return new ResolvedSubject(family, FastRouteCapabilities.spec(family).canonicalType(), about, owner, "",
                ProbeKind.NONE, Set.copyOf(ids));
    }

    /**
     * Whether this switch is not a CGMES switch at all: the importer turns a zero impedance line, series compensator
     * or equivalent branch into a switch, and such an object has no {@code Switch.open}. Its connection state is
     * carried by the {@code ACDCTerminal.connected} of its terminals.
     */
    private static boolean isBranchModelledAsSwitch(Switch sw) {
        String originalClass = sw.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS);
        return originalClass != null
                && !FastRouteCapabilities.spec(Family.SWITCH).rdfTypes().contains(originalClass);
    }

    private static Optional<Family> loadFamily(Load load, String originalClass) {
        if (load.isFictitious()) {
            // Loads created for SvInjections are not CGMES objects
            return Optional.empty();
        }
        return Optional.ofNullable(switch (originalClass == null ? "" : originalClass) {
            case CgmesNames.ENERGY_SOURCE -> Family.ENERGY_SOURCE;
            case CgmesNames.ASYNCHRONOUS_MACHINE -> Family.ASYNCHRONOUS_MACHINE;
            case CgmesNames.CONFORM_LOAD, CgmesNames.NONCONFORM_LOAD, CgmesNames.STATION_SUPPLY,
                 CgmesNames.ENERGY_CONSUMER -> Family.ENERGY_CONSUMER;
            default -> null;
        });
    }

    private static Optional<Family> generatorFamily(Generator generator, String originalClass) {
        Objects.requireNonNull(generator);
        return Optional.ofNullable(switch (originalClass == null ? "" : originalClass) {
            case CgmesNames.SYNCHRONOUS_MACHINE -> Family.SYNCHRONOUS_MACHINE;
            case CgmesNames.EXTERNAL_NETWORK_INJECTION -> Family.EXTERNAL_NETWORK_INJECTION;
            case CgmesNames.EQUIVALENT_INJECTION -> Family.EQUIVALENT_INJECTION;
            default -> null;
        });
    }

    private Optional<ResolvedSubject> byAlias(Identifiable<?> owner, String alias, String about, String classNameHint) {
        String aliasType = owner.getAliasType(alias).orElse(null);
        if (aliasType == null) {
            return Optional.empty();
        }
        if (aliasType.startsWith(TERMINAL_ALIAS_PREFIX)) {
            return Optional.of(of(Family.TERMINAL, "Terminal", about, owner, terminalUsers(owner, alias)));
        }
        if (aliasType.startsWith(DC_TERMINAL_ALIAS_PREFIX)) {
            return Optional.of(of(Family.DC_TERMINAL, "DCTerminal", about, owner, Set.of(owner.getId())));
        }
        if (aliasType.startsWith(RATIO_TAP_CHANGER_ALIAS_PREFIX)) {
            return Optional.of(tapChanger(Family.RATIO_TAP_CHANGER, RATIO_TAP_CHANGER_ATTRIBUTE_PREFIX, owner,
                    aliasType, about, classNameHint, alias));
        }
        if (aliasType.startsWith(PHASE_TAP_CHANGER_ALIAS_PREFIX)) {
            return Optional.of(tapChanger(Family.PHASE_TAP_CHANGER, PHASE_TAP_CHANGER_ATTRIBUTE_PREFIX, owner,
                    aliasType, about, classNameHint, alias));
        }
        return Optional.empty();
    }

    /**
     * A tap changer, named by the transformer that owns it and by the position a recorded change gives it.
     *
     * <p>A two windings transformer has a single tap changer of each kind, which IIDM reports without an end number;
     * a three windings transformer reports the number of the leg. The alias type carries the CGMES end, which is what
     * tells the two apart.</p>
     */
    private static ResolvedSubject tapChanger(Family family, String attributePrefix, Identifiable<?> owner,
                                              String aliasType, String about, String classNameHint, String alias) {
        String end = aliasType.substring(aliasType.length() - 1);
        String prefix = owner instanceof ThreeWindingsTransformer ? attributePrefix + end : attributePrefix;
        String rdfType = tapChangerType(family, owner, classNameHint, alias);
        return new ResolvedSubject(family, rdfType, about, owner, prefix, ProbeKind.TAP_CHANGER_PREFIX,
                Set.of(owner.getId()));
    }

    private static String tapChangerType(Family family, Identifiable<?> owner, String classNameHint, String alias) {
        FastRouteCapabilities.FamilySpec spec = FastRouteCapabilities.spec(family);
        if (classNameHint != null && spec.rdfTypes().contains(classNameHint)) {
            // The five flavours of phase tap changer are an equipment property the receiving network does not keep,
            // so here, and only here, the producer knows better than the network
            return classNameHint;
        }
        if (family == Family.PHASE_TAP_CHANGER && owner instanceof Connectable<?> connectable) {
            return phaseTapChangerType(connectable, alias);
        }
        return spec.canonicalType();
    }

    /**
     * Which of the five phase tap changer classes to write, decided exactly as the steady state hypothesis export
     * decides it, from the tap changer table the equipment model left behind.
     */
    @SuppressWarnings("unchecked")
    private static <C extends Connectable<C>> String phaseTapChangerType(Connectable<?> transformer, String alias) {
        return CgmesExportUtil.getPhaseTapChangerType((C) transformer, alias);
    }

    /**
     * The IIDM objects the update of one terminal touches: the equipment itself, and the fictitious switch the
     * importer created for it when the terminal was disconnected in a node/breaker voltage level. That switch holds
     * the connection state of the terminal, so a scoped update that left it out would apply half the change.
     */
    private Set<String> terminalUsers(Identifiable<?> owner, String cgmesTerminalId) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(owner.getId());
        String fictitiousSwitchId = cgmesTerminalId + FICTITIOUS_SWITCH_SUFFIX;
        if (network.getIdentifiable(fictitiousSwitchId) != null) {
            ids.add(fictitiousSwitchId);
        }
        return Set.copyOf(ids);
    }

    private static ResolvedSubject of(Family family, String rdfType, String about, Identifiable<?> owner,
                                      Set<String> iidmIds) {
        return new ResolvedSubject(family, rdfType, about, owner, "", ProbeKind.NONE, iidmIds);
    }

    /** The CIM class of an equipment subject: what the importer recorded, when the family accepts it. */
    private static String typeOf(Family family, String originalClass, String fallback) {
        FastRouteCapabilities.FamilySpec spec = FastRouteCapabilities.spec(family);
        if (originalClass != null && spec.rdfTypes().contains(originalClass)) {
            return originalClass;
        }
        return fallback != null ? fallback : spec.canonicalType();
    }

    /**
     * The index of the CGMES objects IIDM does not model: regulating controls, tap changer controls, generating
     * units and equivalent injections. Built once, by one pass over the equipment that may carry them.
     */
    private Map<String, ResolvedSubject> secondaryIndex() {
        if (secondaryIndex != null) {
            return secondaryIndex;
        }
        Map<String, ResolvedSubject> index = new HashMap<>();
        network.getGenerators().forEach(generator -> {
            addProperty(index, generator, Conversion.PROPERTY_REGULATING_CONTROL, Family.REGULATING_CONTROL,
                    "RegulatingControl");
            addProperty(index, generator, Conversion.PROPERTY_GENERATING_UNIT, Family.GENERATING_UNIT,
                    "GeneratingUnit");
        });
        network.getShuntCompensators().forEach(shunt ->
                addProperty(index, shunt, Conversion.PROPERTY_REGULATING_CONTROL, Family.REGULATING_CONTROL,
                        "RegulatingControl"));
        network.getStaticVarCompensators().forEach(svc ->
                addProperty(index, svc, Conversion.PROPERTY_REGULATING_CONTROL, Family.REGULATING_CONTROL,
                        "RegulatingControl"));
        network.getBoundaryLines().forEach(boundaryLine ->
                addProperty(index, boundaryLine, Conversion.PROPERTY_EQUIVALENT_INJECTION,
                        Family.EQUIVALENT_INJECTION, "EquivalentInjection"));
        network.getTwoWindingsTransformers().forEach(transformer -> addTapChangerControls(index, transformer));
        network.getThreeWindingsTransformers().forEach(transformer -> addTapChangerControls(index, transformer));
        addOperationalLimits(index);
        network.getVoltageLevels().forEach(voltageLevel -> addVoltageLimits(index, voltageLevel));
        secondaryIndex = index;
        return index;
    }

    /**
     * The CGMES OperationalLimit objects, which IIDM keeps only as identifiers on the operational limits groups its
     * import filled. One identifier may stand for more than one IIDM limit, when the CGMES set was attached to the
     * equipment of a line rather than to one of its terminals.
     */
    private void addOperationalLimits(Map<String, ResolvedSubject> index) {
        CgmesLimitIndex limits = CgmesLimitIndex.of(network);
        limitIndex = limits;
        for (String limitId : limits.limitIds()) {
            List<CgmesLimitIndex.LimitSlot> slots = limits.slots(limitId);
            CgmesLimitIndex.LimitSlot first = slots.get(0);
            Family family = limitFamily(first.type());
            Set<String> iidmIds = new LinkedHashSet<>();
            slots.forEach(slot -> iidmIds.add(slot.owner().getId()));
            // The probe key of the first slot describes the whole set of loading limits it belongs to, and the
            // result is filtered back to this subject afterwards
            index.putIfAbsent(DifferenceModelParser.normalizeId(limitId),
                    new ResolvedSubject(family, first.className(), about(limitId), first.owner(),
                            first.memberKey(), ProbeKind.ATTRIBUTE_KEY, Set.copyOf(iidmIds)));
        }
    }

    private static Family limitFamily(LimitType type) {
        return switch (type) {
            case CURRENT -> Family.CURRENT_LIMIT;
            case ACTIVE_POWER -> Family.ACTIVE_POWER_LIMIT;
            case APPARENT_POWER -> Family.APPARENT_POWER_LIMIT;
            default -> throw new IllegalStateException("Not a loading limit type: " + type);
        };
    }

    /**
     * The CGMES VoltageLimit objects a voltage level was built from, which the import remembers as a
     * {@code ";"}-joined property per direction.
     */
    private static void addVoltageLimits(Map<String, ResolvedSubject> index, VoltageLevel voltageLevel) {
        addVoltageLimits(index, voltageLevel, Conversion.PROPERTY_OPERATIONAL_LIMIT_HIGH_VOLTAGE_LIMIT,
                HIGH_VOLTAGE_LIMIT_ATTRIBUTE);
        addVoltageLimits(index, voltageLevel, Conversion.PROPERTY_OPERATIONAL_LIMIT_LOW_VOLTAGE_LIMIT,
                LOW_VOLTAGE_LIMIT_ATTRIBUTE);
    }

    private static void addVoltageLimits(Map<String, ResolvedSubject> index, VoltageLevel voltageLevel,
                                         String property, String attribute) {
        String ids = voltageLevel.getProperty(property);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (String id : ids.split(";")) {
            if (!id.isEmpty()) {
                index.putIfAbsent(DifferenceModelParser.normalizeId(id),
                        new ResolvedSubject(Family.VOLTAGE_LIMIT, "VoltageLimit", about(id), voltageLevel,
                                attribute, ProbeKind.ATTRIBUTE_KEY, Set.of(voltageLevel.getId())));
            }
        }
    }

    private static void addProperty(Map<String, ResolvedSubject> index, Identifiable<?> owner, String property,
                                    Family family, String rdfType) {
        addIndexed(index, owner.getProperty(property), family, rdfType, owner, "");
    }

    /**
     * Index one CGMES object that IIDM does not model, under the identifier a difference model states it by.
     *
     * <p>The property or extension holds the identifier the way the importer read it, which for a model whose
     * identifiers are {@code urn:uuid:} URIs is the full URI, while a difference model states the bare identifier.
     * The key is therefore normalized the same way the parser normalizes a subject, and the {@code rdf:about} is
     * written back in the shape the network uses, so that the synthetic update document resolves to the same
     * object.</p>
     */
    private static void addIndexed(Map<String, ResolvedSubject> index, String id, Family family, String rdfType,
                                   Identifiable<?> owner, String attributePrefix) {
        if (id == null) {
            return;
        }
        ResolvedSubject subject = new ResolvedSubject(family, rdfType, about(id), owner, attributePrefix,
                attributePrefix.isEmpty() ? ProbeKind.NONE : ProbeKind.TAP_CHANGER_PREFIX, Set.of(owner.getId()));
        index.merge(DifferenceModelParser.normalizeId(id), subject, (existing, added) -> withUser(existing, owner));
    }

    /** The {@code rdf:about} of an identifier, keeping an absolute URI and making a bare identifier a local one. */
    private static String about(String id) {
        return id.startsWith(URN_UUID) ? id : "#_" + DifferenceModelParser.normalizeId(id);
    }

    private static <C extends Connectable<C>> void addTapChangerControls(Map<String, ResolvedSubject> index,
                                                                        Connectable<C> transformer) {
        CgmesTapChangers<C> tapChangers = transformer.getExtension(CgmesTapChangers.class);
        if (tapChangers == null) {
            return;
        }
        for (CgmesTapChanger tapChanger : tapChangers.getTapChangers()) {
            String controlId = tapChanger.getControlId();
            if (controlId == null) {
                continue;
            }
            String attributePrefix = attributePrefixOf(transformer, tapChanger);
            index.computeIfAbsent(DifferenceModelParser.normalizeId(controlId),
                key -> new ResolvedSubject(Family.REGULATING_CONTROL, "TapChangerControl", about(controlId),
                        transformer, attributePrefix, ProbeKind.TAP_CHANGER_PREFIX,
                        Set.of(transformer.getId())));
        }
    }

    /** The name a recorded change gives the tap changer a control belongs to, which is how its probes are named. */
    private static String attributePrefixOf(Identifiable<?> transformer, CgmesTapChanger tapChanger) {
        String type = tapChanger.getType();
        String prefix = CgmesNames.PHASE_TAP_CHANGER.equals(type)
                ? PHASE_TAP_CHANGER_ATTRIBUTE_PREFIX : RATIO_TAP_CHANGER_ATTRIBUTE_PREFIX;
        if (!(transformer instanceof ThreeWindingsTransformer)) {
            return prefix;
        }
        // The last character of the CGMES tap changer alias type is the end it belongs to
        String combined = tapChanger.getCombinedTapChangerId();
        String end = endOf(transformer, combined != null ? combined : tapChanger.getId());
        return prefix + end;
    }

    private static String endOf(Identifiable<?> transformer, String tapChangerId) {
        return transformer.getAliasType(tapChangerId)
                .map(aliasType -> aliasType.substring(aliasType.length() - 1))
                .orElse("1");
    }

    private static ResolvedSubject withUser(ResolvedSubject existing, Identifiable<?> owner) {
        Set<String> ids = new LinkedHashSet<>(existing.iidmIds());
        ids.add(owner.getId());
        return new ResolvedSubject(existing.family(), existing.rdfType(), existing.about(), existing.owner(),
                existing.ownerAttributePrefix(), existing.probeKind(), Set.copyOf(ids));
    }

    /** The index of the CGMES limit identifiers this resolver built, or {@code null} when it never needed one. */
    CgmesLimitIndex limitIndex() {
        return limitIndex;
    }

    /** Only for the logging of a class hint the network overruled. */
    static void logIgnoredHint(String subjectId, String hint, String used) {
        LOGGER.debug("Ignoring the class {} the difference model gives {}: the network says it is a {}",
                hint, subjectId, used);
    }
}
