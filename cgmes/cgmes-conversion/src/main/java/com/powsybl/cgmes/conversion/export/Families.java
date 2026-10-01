/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.elements.TerminalConversion;
import com.powsybl.cgmes.conversion.export.LimitFamily.LimitSlot;
import com.powsybl.cgmes.conversion.mapping.Block;
import com.powsybl.cgmes.conversion.mapping.LoadRows;
import com.powsybl.cgmes.conversion.mapping.PlainFamily;
import com.powsybl.cgmes.extensions.CgmesTapChanger;
import com.powsybl.cgmes.extensions.CgmesTapChangers;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.iidm.network.*;

import java.util.*;
import java.util.stream.Stream;

/**
 * The mapping as the in-place import of a difference model reads it: which network object a CGMES master resource
 * identifier names, and with which CIM class (the subject index).
 *
 * <p>A difference model names its subjects by CGMES master resource identifier. Most of them are equipment and have
 * that identifier as their IIDM identifier, but the interesting ones are not: a terminal, a tap changer, a regulating
 * control, a generating unit, an equivalent injection and an operational limit are CGMES objects that IIDM does not
 * model as objects of their own. The CGMES import leaves each of them behind as an alias or a property of the equipment
 * that carries them, and the families of the mapping read the same aliases and properties to name them; this class
 * walks them back.</p>
 *
 * <p>The class of a subject matters as much as the object, because the update queries select on {@code rdf:type} and
 * a synthetic update document has to state one. The network decides it, among the classes of the block that describes
 * the subject, not the class hint a document carries: a producer may call a load {@code ConformLoad} while the
 * receiving network read it as a {@code NonConformLoad}, and writing the producer's word would make the update read
 * nothing at all. The hint is only used where the network genuinely cannot tell, namely for the five flavours of phase
 * tap changer.</p>
 *
 * <p>The index of the subjects that are not equipment is built lazily and exactly once, with a single pass over the
 * equipment that may carry them, so a difference that only names equipment never pays for it.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class Families {

    private static final String URN_UUID = "urn:uuid:";
    private static final String TERMINAL_ALIAS_PREFIX = "CGMES.Terminal";
    private static final String DC_TERMINAL_ALIAS_PREFIX = "CGMES.DCTerminal";
    private static final String RATIO_TAP_CHANGER_ALIAS_PREFIX = "CGMES." + CgmesNames.RATIO_TAP_CHANGER;
    private static final String PHASE_TAP_CHANGER_ALIAS_PREFIX = "CGMES." + CgmesNames.PHASE_TAP_CHANGER;
    /** How the importer names the switch it creates for a disconnected terminal, see {@code TerminalConversion}. */
    private static final String FICTITIOUS_SWITCH_SUFFIX = "_SW_fict";
    private static final String MERGED_VOLTAGE_LEVEL_ALIAS_PREFIX =
            Conversion.CGMES_PREFIX_ALIAS_PROPERTIES + "MergedVoltageLevel";

    /**
     * What a CGMES identifier names in a network.
     *
     * @param cimClass the CIM class to write for it, one of the classes of the block that describes it
     * @param about    the subject as an {@code rdf:about} value, in the form the receiving network's identifiers take,
     *                 so that a synthetic update document resolves to the same object
     * @param owner    the IIDM object carrying the subject, which is the one a change of it is recorded on
     * @param key      which part of the owner the subject is, as a change log names it: a tap changer
     *                 ({@code phaseTapChanger}, {@code ratioTapChanger2}), one loading limit (its member key) or one
     *                 voltage limit ({@code highVoltageLimit}); empty when the subject is the owner, or is described by
     *                 its owner as a whole
     * @param iidmIds  every IIDM object the update of this subject touches, which is what a scoped update has to visit
     */
    public record Subject(String cimClass, String about, Identifiable<?> owner, String key, Set<String> iidmIds) {
    }

    private final Network network;
    /** Subjects that are not equipment: regulating controls, generating units, equivalent injections, limits. */
    private Map<String, Subject> secondaryIndex;
    /** The loading limits of every CGMES limit identifier, built with the secondary index. */
    private Map<String, List<LimitSlot>> limitSlots;
    /**
     * The fictitious switch of every CGMES terminal that has one, by terminal, built on the first terminal whose switch
     * does not carry the usual identifier: the importer identifies these switches by their properties, and the
     * identifier may differ when identifier unicity is ensured.
     */
    private Map<String, String> fictitiousSwitchByTerminal;

    public Families(Network network) {
        this.network = Objects.requireNonNull(network);
    }

    /**
     * What a CGMES identifier names in this network.
     *
     * @param subjectId     the identifier as a difference model states it, already normalized
     * @param classNameHint the class the producer gave the subject, or {@code null}
     * @return the subject, or empty when the identifier names nothing the mapping describes
     */
    public Optional<Subject> resolve(String subjectId, String classNameHint) {
        for (String candidate : List.of(subjectId, URN_UUID + subjectId)) {
            Identifiable<?> identifiable = network.getIdentifiable(candidate);
            if (identifiable == null) {
                continue;
            }
            String about = candidate.equals(subjectId) ? "#_" + subjectId : candidate;
            Optional<Subject> resolved = identifiable.getId().equals(candidate)
                    ? equipment(identifiable, about)
                    : byAlias(identifiable, candidate, about, classNameHint);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.ofNullable(secondaryIndex().get(subjectId));
    }

    /** Why an identifier names nothing the mapping describes, as a sentence to append to "&lt;id&gt;: ". */
    public String unresolvedReason(String subjectId) {
        Identifiable<?> identifiable = network.getIdentifiable(subjectId);
        if (identifiable == null) {
            identifiable = network.getIdentifiable(URN_UUID + subjectId);
        }
        if (identifiable instanceof Switch sw && isBranchModelledAsSwitch(sw)) {
            return "it is modelled as a switch of a branch class, whose state is carried by its terminals";
        }
        return identifiable == null
                ? "no object of this network has this identifier"
                : "a " + identifiable.getType() + " carries no steady state hypothesis properties of its own";
    }

    /** The loading limits of every CGMES limit identifier of this network, built once. */
    public Map<String, List<LimitSlot>> limitSlots() {
        if (limitSlots == null) {
            limitSlots = LimitFamily.limitSlots(network);
        }
        return limitSlots;
    }

    private Optional<Subject> equipment(Identifiable<?> identifiable, String about) {
        String originalClass = identifiable.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS);
        Set<String> ids = Set.of(identifiable.getId());
        String cimClass = switch (identifiable) {
            case Switch sw -> isBranchModelledAsSwitch(sw) ? null : classOf(SwitchAndTerminalFamily.SWITCH, originalClass);
            case Load load -> loadClass(load, originalClass);
            case Generator ignored -> generatorClass(originalClass);
            case ShuntCompensator shunt -> shunt.getModelType() == ShuntCompensatorModelType.LINEAR
                    ? "LinearShuntCompensator" : "NonlinearShuntCompensator";
            case StaticVarCompensator ignored -> "StaticVarCompensator";
            case LccConverterStation station -> {
                ids = converterStationUsers(station);
                yield CgmesNames.CS_CONVERTER;
            }
            case LineCommutatedConverter ignored -> CgmesNames.CS_CONVERTER;
            case VscConverterStation station -> {
                ids = converterStationUsers(station);
                yield CgmesNames.VS_CONVERTER;
            }
            case VoltageSourceConverter ignored -> CgmesNames.VS_CONVERTER;
            case Area ignored -> ControlAreaFamily.CONTROL_AREA.cimClasses().get(0);
            case Line ignored -> branchClass(originalClass);
            case BoundaryLine ignored -> branchClass(originalClass);
            case VoltageLevel voltageLevel -> isMergedVoltageLevel(voltageLevel) ? null : CgmesNames.VOLTAGE_LEVEL;
            default -> null;
        };
        if (cimClass == null) {
            return Optional.empty();
        }
        // A converter station of the simple HVDC model: its steady state hypothesis values are those of the HVDC line,
        // which is therefore the object a change of them is recorded on
        Identifiable<?> owner = identifiable instanceof HvdcConverterStation<?> station && station.getHvdcLine() != null
                ? station.getHvdcLine() : identifiable;
        return Optional.of(new Subject(cimClass, about, owner, "", ids));
    }

    /** The IIDM objects the update of a converter station of the simple HVDC model touches: it and its HVDC line. */
    private static Set<String> converterStationUsers(HvdcConverterStation<?> station) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(station.getId());
        if (station.getHvdcLine() != null) {
            ids.add(station.getHvdcLine().getId());
        }
        return Set.copyOf(ids);
    }

    /**
     * The class of a branch subject, as the import recorded it. A transformer, or a class this library does not know,
     * has no in-place impedance update: CGMES holds the impedance of a transformer per end and the import folds both
     * ends into one IIDM value.
     */
    private static String branchClass(String originalClass) {
        String cimClass = originalClass == null ? CgmesNames.AC_LINE_SEGMENT : originalClass;
        return SwitchAndTerminalFamily.isCgmesBranchClass(cimClass) ? cimClass : null;
    }

    /** A voltage level that several CGMES VoltageLevel objects were merged into has no single subject. */
    private static boolean isMergedVoltageLevel(VoltageLevel voltageLevel) {
        return voltageLevel.getAliasFromType(MERGED_VOLTAGE_LEVEL_ALIAS_PREFIX + "1").isPresent()
                || voltageLevel.getAliasFromType(MERGED_VOLTAGE_LEVEL_ALIAS_PREFIX + "2").isPresent();
    }

    /**
     * Whether this switch is not a CGMES switch at all: the importer turns a zero impedance line, series compensator
     * or equivalent branch into a switch, and such an object has no {@code Switch.open}. Its connection state is
     * carried by the {@code ACDCTerminal.connected} of its terminals.
     */
    private static boolean isBranchModelledAsSwitch(Switch sw) {
        String originalClass = sw.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS);
        return originalClass != null && !SwitchAndTerminalFamily.SWITCH.cimClasses().contains(originalClass);
    }

    private static String loadClass(Load load, String originalClass) {
        // Loads created for SvInjections are not CGMES objects
        if (load.isFictitious() || originalClass == null) {
            return null;
        }
        return Stream.of(LoadRows.ENERGY_CONSUMER, LoadRows.ENERGY_SOURCE, LoadRows.ASYNCHRONOUS_MACHINE)
                .map(PlainFamily::cimClasses)
                .anyMatch(classes -> classes.contains(originalClass)) ? originalClass : null;
    }

    private static String generatorClass(String originalClass) {
        return switch (originalClass == null ? "" : originalClass) {
            case CgmesNames.SYNCHRONOUS_MACHINE, CgmesNames.EXTERNAL_NETWORK_INJECTION, CgmesNames.EQUIVALENT_INJECTION -> originalClass;
            default -> null;
        };
    }

    /** The class of an equipment subject: what the importer recorded, when the block accepts it, its canonical one otherwise. */
    private static String classOf(Block block, String originalClass) {
        return originalClass != null && block.cimClasses().contains(originalClass) ? originalClass : block.cimClasses().get(0);
    }

    private Optional<Subject> byAlias(Identifiable<?> owner, String alias, String about, String classNameHint) {
        String aliasType = owner.getAliasType(alias).orElse(null);
        if (aliasType == null) {
            return Optional.empty();
        }
        if (aliasType.startsWith(TERMINAL_ALIAS_PREFIX)) {
            return Optional.of(new Subject(CgmesNames.TERMINAL, about, owner, "", terminalUsers(owner, alias)));
        }
        if (aliasType.startsWith(DC_TERMINAL_ALIAS_PREFIX)) {
            return Optional.of(new Subject(CgmesNames.DC_TERMINAL, about, owner, "", Set.of(owner.getId())));
        }
        if (aliasType.startsWith(RATIO_TAP_CHANGER_ALIAS_PREFIX)) {
            return Optional.of(tapChanger(TapChangerAndShuntFamily.RATIO_TAP_CHANGER, CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX,
                    owner, aliasType, about, classNameHint, alias));
        }
        if (aliasType.startsWith(PHASE_TAP_CHANGER_ALIAS_PREFIX)) {
            return Optional.of(tapChanger(TapChangerAndShuntFamily.PHASE_TAP_CHANGER, CgmesChangeTranslator.PHASE_TAP_CHANGER_PREFIX,
                    owner, aliasType, about, classNameHint, alias));
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
    private static Subject tapChanger(Block block, String attributePrefix, Identifiable<?> owner, String aliasType,
                                      String about, String classNameHint, String alias) {
        String end = aliasType.substring(aliasType.length() - 1);
        String key = owner instanceof ThreeWindingsTransformer ? attributePrefix + end : attributePrefix;
        return new Subject(tapChangerClass(block, owner, classNameHint, alias), about, owner, key, Set.of(owner.getId()));
    }

    private static String tapChangerClass(Block block, Identifiable<?> owner, String classNameHint, String alias) {
        if (classNameHint != null && block.cimClasses().contains(classNameHint)) {
            // The five flavours of phase tap changer are an equipment property the receiving network does not keep,
            // so here, and only here, the producer knows better than the network
            return classNameHint;
        }
        if (block == TapChangerAndShuntFamily.PHASE_TAP_CHANGER && owner instanceof Connectable<?> connectable) {
            return phaseTapChangerClass(connectable, alias);
        }
        return block.cimClasses().get(0);
    }

    /**
     * Which of the five phase tap changer classes to write, decided exactly as the steady state hypothesis export
     * decides it, from the tap changer table the equipment model left behind.
     */
    @SuppressWarnings("unchecked")
    private static <C extends Connectable<C>> String phaseTapChangerClass(Connectable<?> transformer, String alias) {
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
        Switch usual = network.getSwitch(cgmesTerminalId + FICTITIOUS_SWITCH_SUFFIX);
        if (TerminalConversion.isFictitiousSwitchOfATerminal(usual) && cgmesTerminalId.equals(usual.getProperty(Conversion.PROPERTY_TERMINAL))) {
            ids.add(usual.getId());
        } else {
            Optional.ofNullable(fictitiousSwitchByTerminal().get(cgmesTerminalId)).ifPresent(ids::add);
        }
        return Set.copyOf(ids);
    }

    private Map<String, String> fictitiousSwitchByTerminal() {
        if (fictitiousSwitchByTerminal == null) {
            fictitiousSwitchByTerminal = new HashMap<>();
            network.getSwitchStream()
                    .filter(TerminalConversion::isFictitiousSwitchOfATerminal)
                    .forEach(sw -> fictitiousSwitchByTerminal.put(sw.getProperty(Conversion.PROPERTY_TERMINAL), sw.getId()));
        }
        return fictitiousSwitchByTerminal;
    }

    /**
     * The index of the CGMES objects IIDM does not model: regulating controls, tap changer controls, generating
     * units, equivalent injections, operational limits and voltage limits. Built once, by one pass over the equipment
     * that may carry them.
     */
    private Map<String, Subject> secondaryIndex() {
        if (secondaryIndex != null) {
            return secondaryIndex;
        }
        Map<String, Subject> index = new HashMap<>();
        network.getGenerators().forEach(generator -> {
            addProperty(index, generator, Conversion.PROPERTY_REGULATING_CONTROL, "RegulatingControl");
            addProperty(index, generator, Conversion.PROPERTY_GENERATING_UNIT, "GeneratingUnit");
        });
        network.getShuntCompensators().forEach(shunt ->
                addProperty(index, shunt, Conversion.PROPERTY_REGULATING_CONTROL, "RegulatingControl"));
        network.getStaticVarCompensators().forEach(svc ->
                addProperty(index, svc, Conversion.PROPERTY_REGULATING_CONTROL, "RegulatingControl"));
        network.getBoundaryLines().forEach(boundaryLine ->
                addProperty(index, boundaryLine, Conversion.PROPERTY_EQUIVALENT_INJECTION, CgmesNames.EQUIVALENT_INJECTION));
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
    private void addOperationalLimits(Map<String, Subject> index) {
        limitSlots().forEach((limitId, slots) -> {
            LimitSlot first = slots.get(0);
            Set<String> iidmIds = new LinkedHashSet<>();
            slots.forEach(slot -> iidmIds.add(slot.owner().getId()));
            // The member key of the first slot names the whole set of loading limits it belongs to, and its description
            // is filtered back to this subject afterwards
            index.putIfAbsent(DifferenceModelParser.normalizeId(limitId),
                    new Subject(first.className(), about(limitId), first.owner(), first.memberKey(), Set.copyOf(iidmIds)));
        });
    }

    /**
     * The CGMES VoltageLimit objects a voltage level was built from, which the import remembers as a
     * {@code ";"}-joined property per direction.
     */
    private static void addVoltageLimits(Map<String, Subject> index, VoltageLevel voltageLevel) {
        addVoltageLimits(index, voltageLevel, Conversion.PROPERTY_OPERATIONAL_LIMIT_HIGH_VOLTAGE_LIMIT,
                CgmesChangeTranslator.HIGH_VOLTAGE_LIMIT);
        addVoltageLimits(index, voltageLevel, Conversion.PROPERTY_OPERATIONAL_LIMIT_LOW_VOLTAGE_LIMIT,
                CgmesChangeTranslator.LOW_VOLTAGE_LIMIT);
    }

    private static void addVoltageLimits(Map<String, Subject> index, VoltageLevel voltageLevel, String property,
                                         String attribute) {
        String ids = voltageLevel.getProperty(property);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (String id : ids.split(";")) {
            if (!id.isEmpty()) {
                index.putIfAbsent(DifferenceModelParser.normalizeId(id), new Subject(LimitFamily.VOLTAGE_LIMIT_VALUE.cimClasses().get(0),
                        about(id), voltageLevel, attribute, Set.of(voltageLevel.getId())));
            }
        }
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
    private static void addProperty(Map<String, Subject> index, Identifiable<?> owner, String property, String cimClass) {
        String id = owner.getProperty(property);
        if (id == null) {
            return;
        }
        Subject subject = new Subject(cimClass, about(id), owner, "", Set.of(owner.getId()));
        index.merge(DifferenceModelParser.normalizeId(id), subject, (existing, added) -> withUser(existing, owner));
    }

    /** The {@code rdf:about} of an identifier, keeping an absolute URI and making a bare identifier a local one. */
    private static String about(String id) {
        return id.startsWith(URN_UUID) ? id : "#_" + DifferenceModelParser.normalizeId(id);
    }

    private static <C extends Connectable<C>> void addTapChangerControls(Map<String, Subject> index,
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
            String key = keyOf(transformer, tapChanger);
            index.computeIfAbsent(DifferenceModelParser.normalizeId(controlId),
                id -> new Subject("TapChangerControl", about(controlId), transformer, key, Set.of(transformer.getId())));
        }
    }

    /** The name a recorded change gives the tap changer a control belongs to. */
    private static String keyOf(Identifiable<?> transformer, CgmesTapChanger tapChanger) {
        String prefix = CgmesNames.PHASE_TAP_CHANGER.equals(tapChanger.getType())
                ? CgmesChangeTranslator.PHASE_TAP_CHANGER_PREFIX : CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX;
        if (!(transformer instanceof ThreeWindingsTransformer)) {
            return prefix;
        }
        // The last character of the CGMES tap changer alias type is the end it belongs to
        String combined = tapChanger.getCombinedTapChangerId();
        return prefix + transformer.getAliasType(combined != null ? combined : tapChanger.getId())
                .map(aliasType -> aliasType.substring(aliasType.length() - 1))
                .orElse("1");
    }

    private static Subject withUser(Subject existing, Identifiable<?> owner) {
        Set<String> ids = new LinkedHashSet<>(existing.iidmIds());
        ids.add(owner.getId());
        return new Subject(existing.cimClass(), existing.about(), existing.owner(), existing.key(), Set.copyOf(ids));
    }
}
