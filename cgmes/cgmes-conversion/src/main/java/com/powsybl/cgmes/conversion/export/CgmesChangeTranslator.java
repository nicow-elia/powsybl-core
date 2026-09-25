/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.RegulatingControlMapping;
import com.powsybl.cgmes.conversion.elements.OperationalLimitConversion;
import com.powsybl.cgmes.conversion.export.PartialSshExport.UnsupportedChangeBehavior;
import com.powsybl.cgmes.extensions.CimCharacteristics;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.DcSwitch;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.HvdcConverterStation;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.LccConverterStation;
import com.powsybl.iidm.network.LimitType;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.LineCommutatedConverter;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.LoadingLimits;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.OperationalLimitsGroup;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.TapChanger;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.events.ExtensionCreationNetworkEvent;
import com.powsybl.iidm.network.events.ExtensionUpdateNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.OperationalLimitsInfo;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ReferencePriorities;
import com.powsybl.iidm.network.extensions.ReferencePriority;
import com.powsybl.iidm.network.extensions.RemoteReactivePowerControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.powsybl.cgmes.conversion.Conversion.ALIAS_DC_TERMINAL1;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_DC_TERMINAL2;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_PHASE_TAP_CHANGER1;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_PHASE_TAP_CHANGER2;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_RATIO_TAP_CHANGER1;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_RATIO_TAP_CHANGER2;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_TERMINAL1;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_TERMINAL2;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_CGMES_ORIGINAL_CLASS;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_EQUIVALENT_INJECTION;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_GENERATING_UNIT;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_IS_EQUIVALENT_SHUNT;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_MODE;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_REGULATING_CONTROL;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_REGULATION_CAPABILITY;
import static com.powsybl.cgmes.conversion.export.CgmesPropertyBuffer.merge;
import static com.powsybl.cgmes.conversion.export.CgmesPropertyBuffer.newUpdates;
import static com.powsybl.commons.util.Result.failure;
import static com.powsybl.commons.util.Result.success;

/**
 * Translates the changes recorded on an IIDM network into the CGMES properties describing them.
 *
 * <p>Shared by the partial SSH export and the difference model export, so that both describe a change in exactly the
 * same way: the difference export runs this translation twice, once against the live network and once against the
 * state the change log says the network was in before, and a partial SSH export is the forward half of that.</p>
 *
 * <p>Only the steady state changes listed in the CGMES SSH profile can be translated. Anything else is reported
 * through {@link UnsupportedChangeBehavior}, either by failing or by logging a warning and skipping the change.
 * The exporter never emits a description of an object that the receiving side would not be able to resolve.</p>
 *
 * <p>Three rules drive most of the mapping decisions:</p>
 * <ul>
 *     <li>A property is written only when the change actually affects it, so that the receiving side keeps its
 *     previous value for everything else. The one exception is a group of properties that the CGMES importer only
 *     accepts as a whole, such as the active and reactive power of an injection, or the section count and the
 *     control flag of a shunt compensator: the whole group is written whatever of it changed.</li>
 *     <li>The exported value is always the one that is consistent with the current state of the network, not the
 *     one carried by the event. Compaction and the buffering done by {@link CgmesPropertyBuffer} therefore cannot
 *     produce a file that contradicts the network it was exported from. A change recorded on another variant is
 *     rejected for that reason: its values are not the ones the network currently holds.</li>
 *     <li>What is written is what the receiving side can read back. A value the CGMES update would ignore, or
 *     would read with the other sign, is either corrected here or reported as unsupported, never written as if it
 *     were going to arrive.</li>
 * </ul>
 *
 * <p>Regulating controls are shared between pieces of equipment, so they are described by
 * {@link CgmesChangeRegulatingControls} rather than here.</p>
 *
 * <p>Every value a change log can speak about is read through an {@link IidmStateView}; structure is read live. A
 * value the previous state needs but the change log never recorded makes the change unsupported rather than wrong,
 * through {@link UnreconstructibleStateException}.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesChangeTranslator {

    private static final Logger LOGGER = LoggerFactory.getLogger(CgmesChangeTranslator.class);

    // Internal names that live somewhere in the iidm module, pinned by PartialSshAttributeNameTest
    static final String OPEN = "open";
    static final String P0 = "p0";
    static final String Q0 = "q0";
    static final String TARGET_P = "targetP";
    static final String TARGET_Q = "targetQ";
    static final String TARGET_V = "targetV";
    static final String VOLTAGE_REGULATOR_ON = "voltageRegulatorOn";
    /** Boundary line generation spells the same idea differently from every other regulating equipment. */
    static final String VOLTAGE_REGULATION_ON = "voltageRegulationOn";
    static final String SECTION_COUNT = "sectionCount";
    static final String VOLTAGE_SETPOINT = "voltageSetpoint";
    static final String REACTIVE_POWER_SETPOINT = "reactivePowerSetpoint";
    static final String ACTIVE_POWER_SETPOINT = "activePowerSetpoint";
    static final String CONVERTERS_MODE = "convertersMode";
    static final String POWER_FACTOR = "powerFactor";
    static final String REGULATING = "regulating";
    static final String TARGET_DEADBAND = "targetDeadband";
    static final String REGULATION_MODE = "regulationMode";
    static final String TAP_POSITION_SUFFIX = ".tapPosition";
    private static final String TAP_POSITION = "tapPosition";
    static final String REGULATING_SUFFIX = ".regulating";
    static final String REGULATION_VALUE_SUFFIX = ".regulationValue";
    static final String TARGET_DEADBAND_SUFFIX = ".targetDeadband";
    static final String REGULATION_MODE_SUFFIX = ".regulationMode";
    static final String PHASE_TAP_CHANGER_PREFIX = "phaseTapChanger";
    static final String RATIO_TAP_CHANGER_PREFIX = "ratioTapChanger";
    // Detailed DC model converters
    static final String CONTROL_MODE = "controlMode";
    static final String TARGET_VDC = "targetVdc";
    // Extension attributes
    static final String PARTICIPATION_FACTOR = "participationFactor";
    static final String REFERENCE_PRIORITY = "referencePriority";
    static final String RRPC_TARGET_Q = "targetQ";
    static final String RRPC_ENABLED = "enabled";
    // Operational limits, voltage limits and branch impedances (equipment profile)
    static final String LIMITS_PREFIX = CgmesLimitIndex.LIMITS_PREFIX;
    static final String PERMANENT_LIMIT_SUFFIX = ".permanentLimit";
    static final String TEMPORARY_LIMIT_VALUE_SUFFIX = ".temporaryLimit.value";
    static final String HIGH_VOLTAGE_LIMIT = "highVoltageLimit";
    static final String LOW_VOLTAGE_LIMIT = "lowVoltageLimit";
    static final String R = "r";
    static final String X = "x";
    static final String G1 = "g1";
    static final String G2 = "g2";
    static final String B1 = "b1";
    static final String B2 = "b2";
    static final String G = "g";
    static final String B = "b";

    /** The CIM class of a voltage limit object, which CGMES has but IIDM does not model. */
    private static final String VOLTAGE_LIMIT = "VoltageLimit";

    /** What {@code TieLineUtil.buildMergedId} joins two identifiers with. */
    private static final String MERGED_ID_SEPARATOR = " + ";
    private static final String MERGED_VOLTAGE_LEVEL_ALIAS_PREFIX =
            Conversion.CGMES_PREFIX_ALIAS_PROPERTIES + "MergedVoltageLevel";

    private static final String REGULATING_COND_EQ_CONTROL_ENABLED = "RegulatingCondEq.controlEnabled";
    private static final String ACDC_TERMINAL_CONNECTED = "ACDCTerminal.connected";
    private static final String ROTATING_MACHINE_P = "RotatingMachine.p";
    private static final String ROTATING_MACHINE_Q = "RotatingMachine.q";
    private static final String UNIT_MULTIPLIER = "UnitMultiplier";
    private static final String KILO = "k";
    private static final String MEGA = "M";

    private static final Set<String> LOAD_ATTRIBUTES = Set.of(P0, Q0);
    private static final Set<String> GENERATOR_ATTRIBUTES = Set.of(TARGET_P, TARGET_Q, TARGET_V, VOLTAGE_REGULATOR_ON);
    private static final Set<String> SHUNT_ATTRIBUTES = Set.of(SECTION_COUNT, TARGET_V, VOLTAGE_REGULATOR_ON, TARGET_DEADBAND);
    private static final Set<String> STATIC_VAR_COMPENSATOR_ATTRIBUTES =
            Set.of(VOLTAGE_SETPOINT, REACTIVE_POWER_SETPOINT, REGULATING, REGULATION_MODE);
    private static final Set<String> VSC_CONVERTER_ATTRIBUTES = Set.of(VOLTAGE_SETPOINT, REACTIVE_POWER_SETPOINT, VOLTAGE_REGULATOR_ON);
    private static final Set<String> RRPC_ATTRIBUTES = Set.of(RRPC_TARGET_Q, RRPC_ENABLED);
    private static final Set<String> HVDC_LINE_ATTRIBUTES = Set.of(ACTIVE_POWER_SETPOINT, CONVERTERS_MODE);
    private static final Set<String> AC_DC_CONVERTER_ATTRIBUTES = Set.of(TARGET_P, TARGET_VDC, CONTROL_MODE,
            VOLTAGE_REGULATOR_ON, VOLTAGE_SETPOINT, REACTIVE_POWER_SETPOINT, POWER_FACTOR);
    private static final Set<String> BOUNDARY_LINE_ATTRIBUTES =
            Set.of(P0, Q0, TARGET_P, TARGET_Q, TARGET_V, VOLTAGE_REGULATION_ON);
    private static final Set<String> VOLTAGE_LIMIT_ATTRIBUTES = Set.of(HIGH_VOLTAGE_LIMIT, LOW_VOLTAGE_LIMIT);
    private static final Set<String> LINE_IMPEDANCE_ATTRIBUTES = Set.of(R, X, G1, G2, B1, B2);
    private static final Set<String> BOUNDARY_LINE_IMPEDANCE_ATTRIBUTES = Set.of(R, X, G, B);
    /** Attributes an impedance change of a transformer is reported under, which this exporter cuts. */
    private static final Set<String> TRANSFORMER_IMPEDANCE_ATTRIBUTES =
            Set.of(R, X, G, B, "ratedU1", "ratedU2", "ratedU", "ratedS");

    /** The default of {@link #targetDescription}, used by the partial SSH export. */
    static final String PARTIAL_SSH_TARGET = "a partial SSH file";

    private final Network network;
    private final CgmesExportContext context;
    private final UnsupportedChangeBehavior unsupportedChangeBehavior;
    /** What the rejection message calls the document a change cannot be written to. */
    private final String targetDescription;
    /** The profiles this export writes. A change describing any other profile is unsupported here. */
    private final Set<CgmesSubset> allowedSubsets;
    /** Which state of the network the values are read from: the current one, or the one before the change set. */
    private final IidmStateView state;

    private final CgmesPropertyBuffer updates = new CgmesPropertyBuffer();
    private final List<NetworkEvent> exportedEvents = new ArrayList<>();
    private final CgmesChangeRegulatingControls regulatingControls;
    /** Built on first use, so that a change set without limits never pays for the walk it costs. */
    private CgmesLimitIndex limitIndex;
    /** Whether a change that belongs to every variant of the network is refused, see {@link #setRejectSharedChanges}. */
    private boolean rejectSharedChanges;

    CgmesChangeTranslator(Network network, CgmesExportContext context, UnsupportedChangeBehavior unsupportedChangeBehavior) {
        this(network, context, unsupportedChangeBehavior, PARTIAL_SSH_TARGET,
                EnumSet.of(CgmesSubset.STEADY_STATE_HYPOTHESIS), IidmStateView.LIVE, null);
    }

    /**
     * @param targetDescription what the rejection message calls the document being written, so that a reader of a
     *                          warning knows which export refused the change
     * @param allowedSubsets    the profiles this export writes; a change that describes another one is reported as
     *                          unsupported rather than silently left out
     * @param state             which state of the network the values are read from
     * @param regulatingControls an index to share with another translator of the same export, or {@code null} to
     *                          build one. Sharing it means the network is walked once for both directions of a
     *                          difference model
     */
    CgmesChangeTranslator(Network network, CgmesExportContext context,
                              UnsupportedChangeBehavior unsupportedChangeBehavior, String targetDescription,
                              Set<CgmesSubset> allowedSubsets, IidmStateView state,
                              CgmesChangeRegulatingControls regulatingControls) {
        this.network = network;
        this.context = context;
        this.unsupportedChangeBehavior = unsupportedChangeBehavior;
        this.targetDescription = Objects.requireNonNull(targetDescription);
        this.allowedSubsets = Set.copyOf(allowedSubsets);
        this.state = Objects.requireNonNull(state);
        this.regulatingControls = regulatingControls != null
                ? regulatingControls : new CgmesChangeRegulatingControls(network, context);
    }

    /**
     * Refuse changes that are not stored per variant in IIDM.
     *
     * <p>A change of an impedance, of an operational limit value or of a property is recorded <em>without</em> a
     * variant identifier, because IIDM keeps one such value for the whole network. An export that writes the
     * history of one variant of a multi-variant network cannot carry it: the state it describes is the state of
     * every variant, so putting it in the successor of one snapshot would be a lie about the others.</p>
     *
     * @param rejectSharedChanges whether such a change is reported as unsupported
     * @return this
     */
    CgmesChangeTranslator setRejectSharedChanges(boolean rejectSharedChanges) {
        this.rejectSharedChanges = rejectSharedChanges;
        return this;
    }

    /** The regulating control index of this translator, so that a second one can share it. */
    CgmesChangeRegulatingControls regulatingControls() {
        return regulatingControls;
    }

    /**
     * Translate every recorded change into the CGMES properties that describe it, buffered per object.
     *
     * <p>The changes are expected to have been compacted by {@link PartialSshExport#compactEvents}
     * already to avoid writing the same attribute twice.</p>
     *
     * <p>The values buffered are read from the network as it currently stands, not taken from the changes
     * themselves, so the result describes a state the network really was in.</p>
     *
     * @param events the compacted changes to translate, in the order in which they are to be written
     * @return the properties to write, buffered per CGMES object
     * @throws PowsyblException under {@link UnsupportedChangeBehavior#FAIL}, on the first change that cannot be
     *                          exported
     */
    CgmesPropertyBuffer translateAll(Collection<NetworkEvent> events) {
        for (NetworkEvent event : events) {
            switch (translate(event)) {
                case Result.Success(CgmesPropertyBuffer staged) -> {
                    updates.mergeFrom(staged);
                    exportedEvents.add(event);
                }
                case Result.Failure(String reason) -> reject(event, reason);
            }
        }
        return updates;
    }

    /**
     * The changes that reached the file so IGNORE users can track what they exported.
     */
    List<NetworkEvent> exportedEvents() {
        return exportedEvents;
    }

    /**
     * Translate a single change into the CGMES properties describing it
     *
     * Note that one change may map to multiple properties, they will be merged upstream in `translateAll`.
     */
    Result<CgmesPropertyBuffer, String> translate(NetworkEvent event) {
        String workingVariantId = network.getVariantManager().getWorkingVariantId();
        String eventVariantId = variantIdOf(event);
        if (eventVariantId != null && !eventVariantId.equals(workingVariantId)) {
            // Values are read from the network as it currently stands, so a change recorded on another variant
            // would be written with the values of the working one, describing a state that never existed.
            return failure("the change was recorded on variant " + eventVariantId
                    + " but the network is on variant " + workingVariantId);
        }
        if (rejectSharedChanges && eventVariantId == null
                && (event instanceof UpdateNetworkEvent || event instanceof ExtensionUpdateNetworkEvent)) {
            return failure("the change is not stored per variant in IIDM, so it belongs to every variant and"
                    + " cannot be written into the snapshot of one");
        }
        Result<CgmesPropertyBuffer, String> result;
        try {
            result = switch (event) {
                case UpdateNetworkEvent updateEvent -> translateAttributeChange(updateEvent);
                case ExtensionUpdateNetworkEvent extensionEvent ->
                    translateExtensionChange(extensionEvent.id(), extensionEvent.extensionName(), extensionEvent.attribute());
                // An extension is created empty and filled by its adder afterwards, so the creation event carries no
                // value of its own. Reading the values from the network makes exporting it at that point safe anyway.
                case ExtensionCreationNetworkEvent creationEvent ->
                    translateExtensionChange(creationEvent.id(), creationEvent.extensionName(), null);
                default -> failure("only attribute updates can be exported, but this is a " + event.getClass().getSimpleName());
            };
        } catch (UnreconstructibleStateException e) {
            // A value the described state needs was never recorded: the change is unsupported here, not an error
            return failure(e.getMessage());
        }
        return result.flatMap(this::checkAllowedSubsets);
    }

    /**
     * A change whose description reaches a profile this export does not write cannot be written at all: leaving the
     * part that does not fit out would send a description the receiving side cannot act on.
     */
    private Result<CgmesPropertyBuffer, String> checkAllowedSubsets(CgmesPropertyBuffer staged) {
        for (CgmesSubset subset : staged.subsets()) {
            if (!allowedSubsets.contains(subset)) {
                return failure("the change belongs to the " + subset.getIdentifier()
                        + " profile, which is not part of this export");
            }
        }
        return success(staged);
    }

    static String variantIdOf(NetworkEvent event) {
        return switch (event) {
            case UpdateNetworkEvent updateEvent -> updateEvent.variantId();
            case ExtensionUpdateNetworkEvent extensionEvent -> extensionEvent.variantId();
            default -> null;
        };
    }

    /**
     * Translate a change of an extension of the given identifiable.
     *
     * @param attribute the changed attribute, or {@code null} when the whole extension was created and every value
     *                  it carries has to be exported
     */
    private Result<CgmesPropertyBuffer, String> translateExtensionChange(String id, String extensionName, String attribute) {
        Identifiable<?> identifiable = network.getIdentifiable(id);
        if (identifiable == null) {
            return failure("the network has no identifiable with id " + id);
        }
        return switch (extensionName) {
            case RemoteReactivePowerControl.NAME -> remoteReactivePowerControlUpdates(identifiable, attribute);
            case ReferencePriorities.NAME -> referencePriorityUpdates(identifiable);
            case ActivePowerControl.NAME -> participationFactorUpdates(identifiable, attribute);
            default -> failure("extension " + extensionName + " has no CGMES steady state property");
        };
    }

    /**
     * A remote reactive power control describes the target of the RegulatingControl of its generator, whenever the
     * CGMES regulating control of that generator regulates reactive power.
     *
     * <p>A RegulatingControl carries a single target, whose meaning is the CGMES mode, which belongs to the
     * equipment model and cannot be changed from a steady state hypothesis file. A generator whose control
     * regulates voltage therefore has nowhere to put a reactive power target: writing the block anyway would send
     * the voltage target and silently lose the change.</p>
     */
    private Result<CgmesPropertyBuffer, String> remoteReactivePowerControlUpdates(Identifiable<?> identifiable, String attribute) {
        if (attribute != null && !RRPC_ATTRIBUTES.contains(attribute)) {
            return failure("no CGMES steady state property corresponds to the " + attribute
                    + " of a remote reactive power control");
        }
        if (!(identifiable instanceof Generator generator)) {
            return failure(identifiable.getType() + " " + identifiable.getId()
                    + " carries a remote reactive power control the SSH profile cannot describe");
        }
        if (!RegulatingControlMapping.isControlModeReactivePower(generator.getProperty(PROPERTY_MODE))) {
            return failure("generator " + generator.getId() + " does not regulate reactive power in CGMES,"
                    + " its remote reactive power control has no steady state property");
        }
        state.requireExtensionNotCreated(generator, RemoteReactivePowerControl.NAME);
        return generatorMachineUpdates(generator).flatMap(machine ->
                regulatingControlUpdates(generator).map(regulatingControl -> merge(machine, regulatingControl)));
    }

    private Result<CgmesPropertyBuffer, String> translateAttributeChange(UpdateNetworkEvent event) {
        Identifiable<?> identifiable = network.getIdentifiable(event.id());
        if (identifiable == null) {
            return failure("the network has no identifiable with id " + event.id());
        }
        // The key rather than the plain attribute name, so that the operational limits group and the acceptable
        // duration a limit change carries in its payload select the right limit. For every other attribute the two
        // are the same string.
        String attribute = EventCompactor.attributeKey(event);
        TapChangerAttribute tapChangerAttribute = tapChangerAttribute(attribute);
        return switch (identifiable) {
            case Switch sw when OPEN.equals(attribute) -> switchUpdates(sw);
            case DcSwitch dcSwitch when OPEN.equals(attribute) -> dcSwitchUpdates(dcSwitch);
            case Load load when LOAD_ATTRIBUTES.contains(attribute) -> loadUpdates(load);
            case BoundaryLine boundaryLine when BOUNDARY_LINE_ATTRIBUTES.contains(attribute) -> boundaryLineUpdates(boundaryLine);
            case Generator generator when GENERATOR_ATTRIBUTES.contains(attribute) -> generatorUpdates(generator, attribute);
            case TwoWindingsTransformer transformer when tapChangerAttribute != null -> twoWindingsTapChangerUpdates(transformer, tapChangerAttribute);
            case ThreeWindingsTransformer transformer when tapChangerAttribute != null -> threeWindingsTapChangerUpdates(transformer, tapChangerAttribute);
            case ShuntCompensator shunt when SHUNT_ATTRIBUTES.contains(attribute) -> shuntCompensatorUpdates(shunt, attribute);
            case StaticVarCompensator svc when STATIC_VAR_COMPENSATOR_ATTRIBUTES.contains(attribute) -> staticVarCompensatorUpdates(svc, attribute);
            case HvdcLine hvdcLine when HVDC_LINE_ATTRIBUTES.contains(attribute) -> hvdcLineUpdates(hvdcLine, attribute);
            case LccConverterStation converter when POWER_FACTOR.equals(attribute) -> lccPowerFactorUpdates(converter);
            case AcDcConverter<?> converter when AC_DC_CONVERTER_ATTRIBUTES.contains(attribute) -> acDcConverterUpdates(converter, attribute);
            case VscConverterStation converter when VSC_CONVERTER_ATTRIBUTES.contains(attribute) -> success(vscStationUpdates(converter));
            case VoltageLevel voltageLevel when VOLTAGE_LIMIT_ATTRIBUTES.contains(attribute) ->
                voltageLimitUpdates(voltageLevel, attribute);
            case Line line when LINE_IMPEDANCE_ATTRIBUTES.contains(attribute) -> lineImpedanceUpdates(line, attribute);
            case BoundaryLine boundaryLine when BOUNDARY_LINE_IMPEDANCE_ATTRIBUTES.contains(attribute) ->
                boundaryLineImpedanceUpdates(boundaryLine, attribute);
            case Identifiable<?> owner when attribute.startsWith(LIMITS_PREFIX)
                    && CgmesLimitIndex.holdsLoadingLimits(owner) ->
                loadingLimitsUpdates(owner, attribute, event.oldValue());
            default -> unmappedAttributeUpdates(identifiable, attribute);
        };
    }

    /** No mapping claimed the change: no CGMES profile this export writes has a property for it. */
    private static Result<CgmesPropertyBuffer, String> unmappedAttributeUpdates(Identifiable<?> identifiable, String attribute) {
        // A three windings transformer reports the attributes of a leg as "leg1.r", "leg2.x" and so on
        String impedanceAttribute = attribute.matches("^leg[123]\\..+")
                ? attribute.substring(attribute.indexOf('.') + 1) : attribute;
        if (isTransformer(identifiable) && TRANSFORMER_IMPEDANCE_ATTRIBUTES.contains(impedanceAttribute)) {
            return failure("no CGMES property corresponds to " + identifiable.getType() + "." + attribute
                    + " (transformer impedances cannot be mapped to CGMES ends, see docs)");
        }
        return failure("no CGMES steady state property corresponds to " + identifiable.getType() + "." + attribute);
    }

    private static boolean isTransformer(Identifiable<?> identifiable) {
        return identifiable instanceof TwoWindingsTransformer || identifiable instanceof ThreeWindingsTransformer;
    }

    // AC switches
    private Result<CgmesPropertyBuffer, String> switchUpdates(Switch sw) {
        if (!context.isExportedEquipment(sw)) {
            return failure("switch " + sw.getId() + " has no counterpart in the CGMES equipment model"
                    + " (it was created by the import, for instance to represent a disconnected terminal),"
                    + " so its state cannot be referenced from a steady state hypothesis file");
        }
        String originalClass = sw.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS);
        if (isBranchModelledAsSwitch(originalClass)) {
            // In CGMES this equipment is a branch and has no open state of its own:
            // the CGMES import derives the state of the IIDM switch from the connection status of its terminals.
            return success(switchTerminalUpdates(sw));
        }
        String className = originalClass != null ? originalClass : CgmesExportUtil.switchClassname(sw.getKind());
        return success(newUpdates(className, cgmesId(sw))
                .value("Switch.open", state.getBoolean(sw, OPEN, sw::isOpen))
                .updates());
    }

    private static boolean isBranchModelledAsSwitch(String originalClass) {
        return isCgmesBranchClass(originalClass);
    }

    /**
     * Whether an identifier is one powsybl built out of two, and therefore names no single CGMES object.
     *
     * <p>{@code TieLineUtil.buildMergedId} joins two identifiers with {@value #MERGED_ID_SEPARATOR} whenever one
     * IIDM object stands for two &mdash; the classic case being two halves of a line that meet at a boundary
     * point. Such an identifier is not a CGMES master resource identifier: the CGMES model that produced it writes
     * it URL-encoded, and a difference model receiver resolves the identifier a statement names exactly as it is
     * written. Exporting a change of such an object would hand over a document the receiver has to refuse, which
     * is a rejection that belongs here, before anything is written.</p>
     */
    private static boolean isMergedIdentifier(String id) {
        return id.contains(MERGED_ID_SEPARATOR);
    }

    /** The three CGMES classes an IIDM line or boundary line can have been imported from. */
    private static boolean isCgmesBranchClass(String originalClass) {
        return CgmesNames.AC_LINE_SEGMENT.equals(originalClass)
                || CgmesNames.EQUIVALENT_BRANCH.equals(originalClass)
                || CgmesNames.SERIES_COMPENSATOR.equals(originalClass);
    }

    private CgmesPropertyBuffer switchTerminalUpdates(Switch sw) {
        boolean connected = !state.getBoolean(sw, OPEN, sw::isOpen);
        return newUpdates(CgmesNames.TERMINAL, cgmesIdFromAlias(sw, ALIAS_TERMINAL1)).value(ACDC_TERMINAL_CONNECTED, connected)
                .object(CgmesNames.TERMINAL, cgmesIdFromAlias(sw, ALIAS_TERMINAL2)).value(ACDC_TERMINAL_CONNECTED, connected)
                .updates();
    }

    // DC switches

    private Result<CgmesPropertyBuffer, String> dcSwitchUpdates(DcSwitch dcSwitch) {
        // A DCSwitch has no open state in the SSH profile either, it is carried by its two DC terminals.
        boolean connected = !state.getBoolean(dcSwitch, OPEN, dcSwitch::isOpen);
        return success(newUpdates(CgmesNames.DC_TERMINAL, cgmesIdFromAlias(dcSwitch, ALIAS_DC_TERMINAL1)).value(ACDC_TERMINAL_CONNECTED, connected)
                .object(CgmesNames.DC_TERMINAL, cgmesIdFromAlias(dcSwitch, ALIAS_DC_TERMINAL2)).value(ACDC_TERMINAL_CONNECTED, connected)
                .updates());
    }

    // Loads

    private Result<CgmesPropertyBuffer, String> loadUpdates(Load load) {
        if (!context.isExportedEquipment(load)) {
            return failure("load " + load.getId() + " has no counterpart in the CGMES equipment model");
        }
        // The CGMES import only accepts an injection power when both components are present,
        // so a change of either setpoint exports both.
        String className = loadClassName(load);
        double p0 = state.getDouble(load, P0, load::getP0);
        double q0 = state.getDouble(load, Q0, load::getQ0);
        return switch (className) {
            case CgmesNames.ENERGY_SOURCE -> success(newUpdates(className, cgmesId(load))
                    .value("EnergySource.activePower", p0)
                    .value("EnergySource.reactivePower", q0)
                    .updates());
            case CgmesNames.ENERGY_CONSUMER, CgmesNames.CONFORM_LOAD, CgmesNames.NONCONFORM_LOAD, CgmesNames.STATION_SUPPLY ->
                success(newUpdates(className, cgmesId(load))
                        .value("EnergyConsumer.p", p0)
                        .value("EnergyConsumer.q", q0)
                        .updates());
            // An AsynchronousMachine is both a RotatingMachine and a RegulatingCondEq, and the CGMES update reads
            // its powers only together with the machine kind and the control flag, so the four are exported as one
            // block. IIDM has no regulation on a load, hence the fixed false.
            case CgmesNames.ASYNCHRONOUS_MACHINE -> success(newUpdates(className, cgmesId(load))
                    .value(ROTATING_MACHINE_P, p0)
                    .value(ROTATING_MACHINE_Q, q0)
                    .value(REGULATING_COND_EQ_CONTROL_ENABLED, false)
                    .enumValue("AsynchronousMachine.asynchronousMachineType", "AsynchronousMachineKind",
                            SteadyStateHypothesisExport.asynchronousMachineKind(p0))
                    .updates());
            default -> failure("load " + load.getId() + " is exported as a " + className
                    + ", which has no steady state setpoints");
        };
    }

    private String loadClassName(Load load) {
        String originalClass = load.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS);
        return originalClass != null && !context.isExportEquipment() ? originalClass : CgmesExportUtil.loadClassName(load);
    }

    // Boundary lines

    /**
     * The block describing the EquivalentInjection that carries the injection at the boundary of a boundary line.
     *
     * <p>IIDM splits that injection in two: the fixed part on the boundary line itself and, when the model gives
     * the boundary a generation, the targets of that generation, whose sign is the generator convention. CGMES
     * holds a single injection in the load convention, so the two are combined back into one here.</p>
     *
     * <p>A paired boundary line, one half of a tie line, is described the same way: each half has an
     * EquivalentInjection of its own at its own boundary.</p>
     */
    private Result<CgmesPropertyBuffer, String> boundaryLineUpdates(BoundaryLine boundaryLine) {
        if (!boundaryLine.hasProperty(PROPERTY_EQUIVALENT_INJECTION)) {
            return failure("boundary line " + boundaryLine.getId() + " has no CGMES EquivalentInjection");
        }
        // Unlike a generator imported from an EquivalentInjection, a boundary line needs no regulation capability:
        // the CGMES update reads the regulation of a boundary EquivalentInjection from the file alone.
        BoundaryLine.Generation generation = boundaryLine.getGeneration();
        double targetP = generation != null ? state.getDouble(boundaryLine, TARGET_P, generation::getTargetP) : 0.0;
        double targetQ = generation != null ? state.getDouble(boundaryLine, TARGET_Q, generation::getTargetQ) : 0.0;
        double targetV = generation != null ? state.getDouble(boundaryLine, TARGET_V, generation::getTargetV) : Double.NaN;
        boolean regulationOn = generation != null
                && state.getBoolean(boundaryLine, VOLTAGE_REGULATION_ON, generation::isVoltageRegulationOn);
        double p = nonNaN(state.getDouble(boundaryLine, P0, boundaryLine::getP0)) - nonNaN(targetP);
        double q = nonNaN(state.getDouble(boundaryLine, Q0, boundaryLine::getQ0)) - nonNaN(targetQ);
        CgmesPropertyBuffer.ObjectUpdate update = newUpdates(CgmesNames.EQUIVALENT_INJECTION,
                context.getNamingStrategy().getCgmesIdFromProperty(boundaryLine, PROPERTY_EQUIVALENT_INJECTION))
                .value("EquivalentInjection.p", p)
                .value("EquivalentInjection.q", q)
                .value("EquivalentInjection.regulationStatus", regulationOn);
        if (targetV > 0) {
            update.value("EquivalentInjection.regulationTarget", targetV);
        }
        return success(update.updates());
    }

    /** Zero for an undefined value, which is what the CGMES import writes back for one. */
    private static double nonNaN(double value) {
        return Double.isNaN(value) ? 0.0 : value;
    }

    // Generators

    private Result<CgmesPropertyBuffer, String> generatorUpdates(Generator generator, String attribute) {
        // An EquivalentInjection carries its regulation itself, it has no RegulatingControl of its own
        if (CgmesNames.EQUIVALENT_INJECTION.equals(originalClass(generator))) {
            return equivalentInjectionUpdates(generator);
        }
        return switch (attribute) {
            case TARGET_P, TARGET_Q -> generatorMachineUpdates(generator);
            // The voltage target lives entirely on the RegulatingControl, it must not restate the machine powers
            case TARGET_V -> regulatingControlUpdates(generator);
            // The CGMES update reads the control flag of a machine only together with its powers, its reference
            // priority and its operating mode, so switching the regulation writes the whole machine block
            case VOLTAGE_REGULATOR_ON -> generatorMachineUpdates(generator).flatMap(machine ->
                    regulatingControlUpdates(generator).map(regulatingControl -> merge(machine, regulatingControl)));
            default -> throw new IllegalStateException("Unhandled generator attribute " + attribute);
        };
    }

    private static String originalClass(Generator generator) {
        return generator.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS, CgmesNames.SYNCHRONOUS_MACHINE);
    }

    /**
     * The block describing the steady state of the CGMES machine an IIDM generator was imported from.
     *
     * <p>The CGMES update reads the properties of a machine as a single group, so the whole group is written
     * whatever the change was.</p>
     */
    private Result<CgmesPropertyBuffer, String> generatorMachineUpdates(Generator generator) {
        String originalClass = originalClass(generator);
        return switch (originalClass) {
            case CgmesNames.SYNCHRONOUS_MACHINE -> success(synchronousMachineUpdates(generator));
            case CgmesNames.EXTERNAL_NETWORK_INJECTION -> success(externalNetworkInjectionUpdates(generator));
            case CgmesNames.EQUIVALENT_INJECTION -> equivalentInjectionUpdates(generator);
            default -> failure("generator " + generator.getId() + " is exported as a " + originalClass
                    + ", which has no steady state setpoints");
        };
    }

    private CgmesPropertyBuffer synchronousMachineUpdates(Generator generator) {
        // Sign convention: CGMES uses the load convention for machines, IIDM the generator convention.
        double targetP = state.getDouble(generator, TARGET_P, generator::getTargetP);
        return newUpdates(CgmesNames.SYNCHRONOUS_MACHINE, cgmesId(generator))
                .value(REGULATING_COND_EQ_CONTROL_ENABLED, generatorControlEnabled(generator))
                .value(ROTATING_MACHINE_P, -targetP)
                .value(ROTATING_MACHINE_Q, -state.getDouble(generator, TARGET_Q, generator::getTargetQ))
                .value("SynchronousMachine.referencePriority", referencePriority(generator))
                .enumValue("SynchronousMachine.operatingMode", "SynchronousMachineOperatingMode",
                        SteadyStateHypothesisExport.obtainOperatingMode(generator, generator.getMinP(), generator.getMaxP(), targetP, state))
                .updates();
    }

    /**
     * Whether the generator takes part in its CGMES regulating control.
     *
     * <p>Which IIDM flag says so depends on what that control regulates: the voltage regulation flag of the
     * generator for a voltage control, the state of its remote reactive power control for a reactive power one. The
     * receiving side combines this flag with {@code RegulatingControl.enabled}, so a control regulating reactive
     * power that reported the voltage flag would never be switched on again.</p>
     */
    private boolean generatorControlEnabled(Generator generator) {
        RemoteReactivePowerControl reactivePowerControl = generator.getExtension(RemoteReactivePowerControl.class);
        if (reactivePowerControl != null
                && RegulatingControlMapping.isControlModeReactivePower(generator.getProperty(PROPERTY_MODE))) {
            state.requireExtensionNotCreated(generator, RemoteReactivePowerControl.NAME);
            return state.getExtensionBoolean(generator, RemoteReactivePowerControl.NAME, RRPC_ENABLED,
                    reactivePowerControl::isEnabled);
        }
        return state.getBoolean(generator, VOLTAGE_REGULATOR_ON, generator::isVoltageRegulatorOn);
    }

    /**
     * The reference priority of a generator. An extension that was not there before the change set means a priority
     * of zero, which is what {@link ReferencePriority#get} returns when there is none.
     */
    private int referencePriority(Generator generator) {
        return state.getExtensionInt(generator, ReferencePriorities.NAME, REFERENCE_PRIORITY, 0,
                () -> ReferencePriority.get(generator));
    }

    private CgmesPropertyBuffer externalNetworkInjectionUpdates(Generator generator) {
        // Sign convention: CGMES uses the load convention for injections, IIDM the generator convention.
        return newUpdates(CgmesNames.EXTERNAL_NETWORK_INJECTION, cgmesId(generator))
                .value(REGULATING_COND_EQ_CONTROL_ENABLED, generatorControlEnabled(generator))
                .value("ExternalNetworkInjection.p", -state.getDouble(generator, TARGET_P, generator::getTargetP))
                .value("ExternalNetworkInjection.q", -state.getDouble(generator, TARGET_Q, generator::getTargetQ))
                .value("ExternalNetworkInjection.referencePriority", referencePriority(generator))
                .updates();
    }

    /**
     * The block describing an EquivalentInjection, which carries its own regulation instead of pointing at a
     * RegulatingControl.
     *
     * <p>The regulation target is only written when it is a usable voltage: the CGMES update turns the regulation
     * off when the target is not, and an EquivalentInjection that the equipment model gives no regulation
     * capability can never regulate on the receiving side whatever the file says.</p>
     */
    private Result<CgmesPropertyBuffer, String> equivalentInjectionUpdates(Generator generator) {
        boolean voltageRegulatorOn = state.getBoolean(generator, VOLTAGE_REGULATOR_ON, generator::isVoltageRegulatorOn);
        if (voltageRegulatorOn && !hasRegulationCapability(generator)) {
            return failure("the EquivalentInjection has no regulation capability, the CGMES update keeps its"
                    + " regulation off");
        }
        double targetV = state.getDouble(generator, TARGET_V, generator::getTargetV);
        CgmesPropertyBuffer.ObjectUpdate update = newUpdates(CgmesNames.EQUIVALENT_INJECTION, cgmesId(generator))
                .value("EquivalentInjection.p", -state.getDouble(generator, TARGET_P, generator::getTargetP))
                .value("EquivalentInjection.q", -state.getDouble(generator, TARGET_Q, generator::getTargetQ))
                .value("EquivalentInjection.regulationStatus", voltageRegulatorOn);
        if (targetV > 0) {
            update.value("EquivalentInjection.regulationTarget", targetV);
        }
        return success(update.updates());
    }

    private static boolean hasRegulationCapability(Identifiable<?> identifiable) {
        return Boolean.parseBoolean(identifiable.getProperty(PROPERTY_REGULATION_CAPABILITY));
    }

    /**
     * The reference priority selects the angle reference of the network, which CGMES carries on the machine itself,
     * so a change of it writes the machine block of the generator.
     */
    private Result<CgmesPropertyBuffer, String> referencePriorityUpdates(Identifiable<?> identifiable) {
        if (!(identifiable instanceof Generator generator)) {
            return failure(identifiable.getType() + " " + identifiable.getId()
                    + " has no CGMES machine to carry a reference priority");
        }
        if (CgmesNames.EQUIVALENT_INJECTION.equals(originalClass(generator))) {
            return failure("generator " + generator.getId()
                    + " is exported as an EquivalentInjection, which has no reference priority");
        }
        return generatorMachineUpdates(generator);
    }

    /**
     * The participation factor of a generator is a property of the CGMES GeneratingUnit it belongs to, not of the
     * machine, so a change of it describes that unit.
     */
    private Result<CgmesPropertyBuffer, String> participationFactorUpdates(Identifiable<?> identifiable, String attribute) {
        if (attribute != null && !PARTICIPATION_FACTOR.equals(attribute)) {
            return failure("no CGMES steady state property corresponds to the " + attribute
                    + " of an active power control");
        }
        if (!(identifiable instanceof Generator generator)) {
            return failure(identifiable.getType() + " " + identifiable.getId() + " has no CGMES GeneratingUnit");
        }
        if (!CgmesNames.SYNCHRONOUS_MACHINE.equals(originalClass(generator))
                || !generator.hasProperty(PROPERTY_GENERATING_UNIT)) {
            return failure("generator " + generator.getId() + " has no CGMES GeneratingUnit");
        }
        state.requireExtensionNotCreated(generator, ActivePowerControl.NAME);
        SteadyStateHypothesisExport.GeneratingUnit generatingUnit =
                SteadyStateHypothesisExport.generatingUnitForGeneratorAndBatteries(generator, context, state);
        if (generatingUnit == null) {
            return failure("generator " + generator.getId() + " is a condenser or has no participation factor");
        }
        return success(newUpdates(generatingUnit.className, generatingUnit.id)
                .value("GeneratingUnit.normalPF", generatingUnit.participationFactor)
                .updates());
    }

    // Tap changers

    /**
     * The tap changer an attribute name points at, and what of it changed.
     *
     * @param phase  whether the attribute names a phase tap changer rather than a ratio one
     * @param end    the end the tap changer sits on, {@code ""} for a two windings transformer
     * @param suffix the changed property, without its leading dot
     */
    private record TapChangerAttribute(boolean phase, String end, String suffix) {
    }

    /**
     * The attributes of a tap changer this exporter maps. Everything else it may report, such as a solved position
     * or a regulation terminal, has no counterpart in the steady state hypothesis profile.
     */
    private static final Pattern TAP_CHANGER_ATTRIBUTE = Pattern.compile(
            "^(ratio|phase)TapChanger([123]?)\\.(tapPosition|regulating|regulationValue|targetDeadband|regulationMode)$");

    private static TapChangerAttribute tapChangerAttribute(String attribute) {
        Matcher matcher = TAP_CHANGER_ATTRIBUTE.matcher(attribute);
        return matcher.matches()
                ? new TapChangerAttribute("phase".equals(matcher.group(1)), matcher.group(2), matcher.group(3))
                : null;
    }

    private Result<CgmesPropertyBuffer, String> twoWindingsTapChangerUpdates(TwoWindingsTransformer transformer,
                                                                           TapChangerAttribute attribute) {
        if (!attribute.end().isEmpty()) {
            return noTapChangerMatching(transformer, attribute);
        }
        if (attribute.phase() && transformer.hasPhaseTapChanger()) {
            return tapChangerUpdates(transformer, "", CgmesExportUtil.tapChangerAliasType(transformer, ALIAS_PHASE_TAP_CHANGER1, ALIAS_PHASE_TAP_CHANGER2),
                    CgmesNames.PHASE_TAP_CHANGER_TABULAR, tapChangerRef(transformer, attribute, transformer.getPhaseTapChanger()), attribute);
        }
        if (!attribute.phase() && transformer.hasRatioTapChanger()) {
            return tapChangerUpdates(transformer, "", CgmesExportUtil.tapChangerAliasType(transformer, ALIAS_RATIO_TAP_CHANGER1, ALIAS_RATIO_TAP_CHANGER2),
                    CgmesNames.RATIO_TAP_CHANGER, tapChangerRef(transformer, attribute, transformer.getRatioTapChanger()), attribute);
        }
        return noTapChangerMatching(transformer, attribute);
    }

    private Result<CgmesPropertyBuffer, String> threeWindingsTapChangerUpdates(ThreeWindingsTransformer transformer,
                                                                             TapChangerAttribute attribute) {
        ThreeWindingsTransformer.Leg leg = leg(transformer, attribute.end());
        if (leg == null || (attribute.phase() ? !leg.hasPhaseTapChanger() : !leg.hasRatioTapChanger())) {
            return noTapChangerMatching(transformer, attribute);
        }
        return attribute.phase()
                ? tapChangerUpdates(transformer, attribute.end(), CgmesExportUtil.getPhaseTapChangerAliasType(attribute.end()),
                        CgmesNames.PHASE_TAP_CHANGER_TABULAR, tapChangerRef(transformer, attribute, leg.getPhaseTapChanger()), attribute)
                : tapChangerUpdates(transformer, attribute.end(), CgmesExportUtil.getRatioTapChangerAliasType(attribute.end()),
                        CgmesNames.RATIO_TAP_CHANGER, tapChangerRef(transformer, attribute, leg.getRatioTapChanger()), attribute);
    }

    private static Result<CgmesPropertyBuffer, String> noTapChangerMatching(Identifiable<?> transformer, TapChangerAttribute attribute) {
        return failure(transformer.getType() + " " + transformer.getId() + " has no "
                + (attribute.phase() ? "phase" : "ratio") + " tap changer on end '" + attribute.end() + "'");
    }

    private static ThreeWindingsTransformer.Leg leg(ThreeWindingsTransformer transformer, String end) {
        return switch (end) {
            case "1" -> transformer.getLeg1();
            case "2" -> transformer.getLeg2();
            case "3" -> transformer.getLeg3();
            default -> null;
        };
    }

    /**
     * The properties describing a change of the given tap changer: its own block, which the CGMES update reads as a
     * whole, and the TapChangerControl carrying its regulation when the regulation is what changed.
     */
    /** The name a recorded change gives the given tap changer, which is how its previous values are looked up. */
    private static TapChangerRef tapChangerRef(Identifiable<?> transformer, TapChangerAttribute attribute,
                                               TapChanger<?, ?, ?, ?> tapChanger) {
        return new TapChangerRef(transformer,
                (attribute.phase() ? PHASE_TAP_CHANGER_PREFIX : RATIO_TAP_CHANGER_PREFIX) + attribute.end(), tapChanger);
    }

    private <C extends Connectable<C>> Result<CgmesPropertyBuffer, String> tapChangerUpdates(
            C transformer, String end, String aliasType, String defaultClassName,
            TapChangerRef ref, TapChangerAttribute attribute) {
        TapChanger<?, ?, ?, ?> tapChanger = ref.tapChanger();
        CgmesPropertyBuffer tapChangerBlock = tapChangerBlock(transformer, aliasType, defaultClassName, ref);
        if (TAP_POSITION.equals(attribute.suffix())) {
            return success(tapChangerBlock);
        }
        if (REGULATION_MODE.equals(attribute.suffix())) {
            return failure("the regulation mode is RegulatingControl.mode, which belongs to the EQ profile");
        }
        if (tapChanger instanceof RatioTapChanger ratioTapChanger
                && ref.getEnum(state, REGULATION_MODE_SUFFIX, RatioTapChanger.RegulationMode.class,
                        ratioTapChanger::getRegulationMode) != RatioTapChanger.RegulationMode.VOLTAGE) {
            return failure("the CGMES update only reads voltage regulation of ratio tap changers");
        }
        if (tapChanger.getRegulationTerminal() == null) {
            return failure("tap changer " + aliasType + " of " + transformer.getId()
                    + " regulates no terminal, so its regulation has no target the receiving side could read");
        }
        return regulatingControls.controlId(transformer, aliasType)
                .map(controlId -> regulatingControls.updatesFor(controlId, state)
                        .map(regulatingControl -> merge(tapChangerBlock, regulatingControl)))
                .orElseGet(() -> failure("tap changer " + aliasType + " of " + transformer.getId()
                        + " has no CGMES tap changer control to carry this change"));
    }

    private <C extends Connectable<C>> CgmesPropertyBuffer tapChangerBlock(C transformer, String aliasType, String defaultClassName,
                                                                         TapChangerRef ref) {
        TapChanger<?, ?, ?, ?> tapChanger = ref.tapChanger();
        String className = defaultClassName;
        if (tapChanger instanceof PhaseTapChanger && !context.isExportEquipment()) {
            className = CgmesExportUtil.getPhaseTapChangerType(transformer, transformer.getAliasFromType(aliasType).orElse(null));
        }
        return newUpdates(className, cgmesIdFromAlias(transformer, aliasType))
                .value("TapChanger.controlEnabled", ref.getBoolean(state, REGULATING_SUFFIX, tapChanger::isRegulating))
                .value("TapChanger.step", ref.getInt(state, TAP_POSITION_SUFFIX, tapChanger::getTapPosition))
                .updates();
    }

    // Shunt compensators

    private Result<CgmesPropertyBuffer, String> shuntCompensatorUpdates(ShuntCompensator shunt, String attribute) {
        if (Boolean.parseBoolean(shunt.getProperty(PROPERTY_IS_EQUIVALENT_SHUNT))) {
            return failure("shunt compensator " + shunt.getId()
                    + " is exported as an EquivalentShunt, which has no steady state properties");
        }
        // The CGMES update reads the section count and the control flag of a shunt as one block, so every change
        // of either writes both. Only a change of the regulation itself also describes the RegulatingControl.
        CgmesPropertyBuffer shuntBlock = newUpdates(shuntClassName(shunt), cgmesId(shunt))
                .value("ShuntCompensator.sections", state.getInt(shunt, SECTION_COUNT, shunt::getSectionCount))
                .value(REGULATING_COND_EQ_CONTROL_ENABLED,
                        state.getBoolean(shunt, VOLTAGE_REGULATOR_ON, shunt::isVoltageRegulatorOn))
                .updates();
        return switch (attribute) {
            case SECTION_COUNT -> success(shuntBlock);
            case TARGET_V, VOLTAGE_REGULATOR_ON, TARGET_DEADBAND -> regulatingControlUpdates(shunt)
                    .map(regulatingControl -> merge(shuntBlock, regulatingControl));
            default -> throw new IllegalStateException("Unhandled shunt compensator attribute " + attribute);
        };
    }

    private static String shuntClassName(ShuntCompensator shunt) {
        return switch (shunt.getModelType()) {
            case LINEAR -> "LinearShuntCompensator";
            case NON_LINEAR -> "NonlinearShuntCompensator";
        };
    }

    // Static var compensators

    private Result<CgmesPropertyBuffer, String> staticVarCompensatorUpdates(StaticVarCompensator svc, String attribute) {
        if (REGULATION_MODE.equals(attribute)) {
            return failure("the regulation mode is RegulatingControl.mode, which belongs to the EQ profile");
        }
        // The CGMES update reads the reactive power and the control flag of a compensator as one block, and the
        // single target of its RegulatingControl is the one matching the current mode, so a change of the setpoint
        // of the other mode is not observable in the SSH profile.
        CgmesPropertyBuffer svcBlock = newUpdates("StaticVarCompensator", cgmesId(svc))
                .value(REGULATING_COND_EQ_CONTROL_ENABLED, state.getBoolean(svc, REGULATING, svc::isRegulating))
                // The reactive power of the terminal is a state variable, not part of a steady state change set
                .value("StaticVarCompensator.q", svc.getTerminal().getQ())
                .updates();
        return regulatingControlUpdates(svc).map(regulatingControl -> merge(svcBlock, regulatingControl));
    }

    // HVDC

    /**
     * The blocks of both converters of an HVDC line, which is where CGMES holds the power of the link and which of
     * its two ends rectifies.
     */
    private Result<CgmesPropertyBuffer, String> hvdcLineUpdates(HvdcLine hvdcLine, String attribute) {
        if (CONVERTERS_MODE.equals(attribute)
                && hvdcLine.getConverterStation1() instanceof VscConverterStation
                && state.getDouble(hvdcLine, ACTIVE_POWER_SETPOINT, hvdcLine::getActivePowerSetpoint) == 0) {
            return failure("a VsConverter has no operating mode, the mode is only derived from a non zero targetPpcc");
        }
        return success(bothConverterUpdates(hvdcLine));
    }

    /**
     * The power factor of a line commutated converter is not a CGMES property of its own: the profile carries the
     * active and the reactive power of the converter, from which the CGMES import derives the factor back.
     */
    private Result<CgmesPropertyBuffer, String> lccPowerFactorUpdates(LccConverterStation converter) {
        HvdcLine hvdcLine = converter.getHvdcLine();
        if (hvdcLine == null) {
            return failure("converter " + converter.getId() + " belongs to no HVDC line, so it has no power to"
                    + " carry its power factor");
        }
        if (state.getDouble(hvdcLine, ACTIVE_POWER_SETPOINT, hvdcLine::getActivePowerSetpoint) == 0) {
            return failure("the power factor is carried by ACDCConverter.p and q, which are zero");
        }
        return success(bothConverterUpdates(hvdcLine));
    }

    private CgmesPropertyBuffer bothConverterUpdates(HvdcLine hvdcLine) {
        return merge(converterActivePowerUpdates(hvdcLine.getConverterStation1()),
                converterActivePowerUpdates(hvdcLine.getConverterStation2()));
    }

    private CgmesPropertyBuffer converterActivePowerUpdates(HvdcConverterStation<?> converter) {
        // The CGMES import reads targetPpcc, targetUdc, p and q as a single block, and derives the power factor of
        // a line commutated converter from p and q, so the four quantities are always exported together. They are
        // computed exactly as the full SSH export computes them.
        SteadyStateHypothesisExport.ConverterState converterState =
                SteadyStateHypothesisExport.computeConverterState(converter, state);
        boolean rectifier = CgmesExportUtil.isConverterStationRectifier(converter, state);
        return switch (converter) {
            case LccConverterStation lcc -> converterStateUpdate(CgmesNames.CS_CONVERTER, cgmesId(lcc),
                    converterState.targetPpcc(), converterState.targetUdc(), converterState.p(), converterState.q())
                    .enumValue("CsConverter.operatingMode", "CsOperatingModeKind", rectifier ? "rectifier" : "inverter")
                    .enumValue("CsConverter.pPccControl", "CsPpccControlKind", rectifier ? "activePower" : "dcVoltage")
                    .updates();
            case VscConverterStation vsc -> merge(converterStateUpdate(CgmesNames.VS_CONVERTER, cgmesId(vsc),
                    converterState.targetPpcc(), converterState.targetUdc(), converterState.p(), converterState.q()).updates(),
                    vscControlModeUpdates(vsc));
            default -> throw new IllegalStateException("Unhandled converter station " + converter.getClass().getSimpleName());
        };
    }

    /**
     * The four quantities the CGMES import reads as a single block for any converter, of the simplified model as
     * well as of the detailed one.
     */
    private static CgmesPropertyBuffer.ObjectUpdate converterStateUpdate(String className, String masterResourceId,
                                                                       double targetPpcc, double targetUdc, double p, double q) {
        return newUpdates(className, masterResourceId)
                .value("ACDCConverter.targetPpcc", targetPpcc)
                .value("ACDCConverter.targetUdc", targetUdc)
                .value("ACDCConverter.p", p)
                .value("ACDCConverter.q", q);
    }

    /**
     * The block describing the control of a voltage source converter station: both control modes, which the CGMES
     * import only reads together, and the setpoints of both modes when they carry a usable value.
     *
     * <p>The import derives which of the two setpoints it applies from {@code qPccControl} and resets the other one
     * to zero, so the inactive setpoint of the sender is not transportable. It does not need to be: a later change
     * of the regulation mode re-exports the then active value.</p>
     */
    private CgmesPropertyBuffer vscStationUpdates(VscConverterStation converter) {
        CgmesPropertyBuffer.ObjectUpdate update = vscControlModeUpdates(converter).object(CgmesNames.VS_CONVERTER, cgmesId(converter));
        double voltageSetpoint = state.getDouble(converter, VOLTAGE_SETPOINT, converter::getVoltageSetpoint);
        if (voltageSetpoint > 0) {
            update.value("VsConverter.targetUpcc", voltageSetpoint);
        }
        double targetQpcc = SteadyStateHypothesisExport.vscTargetQpcc(converter, state);
        if (Double.isFinite(targetQpcc)) {
            update.value("VsConverter.targetQpcc", targetQpcc);
        }
        return update.updates();
    }

    // Detailed DC model converters

    /**
     * The block describing a converter of the detailed DC model, which carries its own control modes and setpoints
     * rather than deriving them from an HVDC line.
     *
     * <p>The CGMES update reads the setpoints and the control modes of a converter as one group, so all of them are
     * written whatever the change was. A line commutated converter has no power factor of its own in CGMES: the
     * profile carries its active and reactive power, from which the import derives the factor back, so a power
     * factor is only transportable next to a power that is not zero.</p>
     */
    private Result<CgmesPropertyBuffer, String> acDcConverterUpdates(AcDcConverter<?> converter, String attribute) {
        SteadyStateHypothesisExport.AcDcConverterState converterState =
                SteadyStateHypothesisExport.computeAcDcConverterState(converter, state);
        return switch (converter) {
            case LineCommutatedConverter lcc -> lineCommutatedConverterUpdates(lcc, converterState, attribute);
            case VoltageSourceConverter vsc -> success(voltageSourceConverterUpdates(vsc, converterState));
            default -> failure("converter " + converter.getId() + " is a "
                    + converter.getClass().getSimpleName() + ", which has no steady state setpoints");
        };
    }

    private Result<CgmesPropertyBuffer, String> lineCommutatedConverterUpdates(
            LineCommutatedConverter converter, SteadyStateHypothesisExport.AcDcConverterState converterState, String attribute) {
        CgmesPropertyBuffer.ObjectUpdate update = converterStateUpdate(CgmesNames.CS_CONVERTER, cgmesId(converter),
                converterState.targetPpcc(), converterState.targetUdc(), converterState.p(), converterState.q())
                .enumValue("CsConverter.operatingMode", "CsOperatingModeKind", converterState.operatingModeOrQpccControl())
                .enumValue("CsConverter.pPccControl", "CsPpccControlKind", converterState.pPccControl());
        double referenceP = lineCommutatedConverterReferenceP(converter, converterState);
        double powerFactor = state.getDouble(converter, POWER_FACTOR, converter::getPowerFactor);
        if (referenceP != 0 && powerFactor > 0) {
            update.value("ACDCConverter.p", referenceP)
                    .value("ACDCConverter.q", Math.abs(referenceP) * Math.sqrt(1 - powerFactor * powerFactor) / powerFactor);
        } else if (POWER_FACTOR.equals(attribute)) {
            // A power factor of zero would make the reactive power infinite, and there is no power to express it
            // against anyway
            return failure("the power factor is carried by ACDCConverter.p and q, which are zero");
        }
        return success(update.updates());
    }

    /** The active power the power factor of a line commutated converter is expressed against, or zero if it has none. */
    private static double lineCommutatedConverterReferenceP(LineCommutatedConverter converter,
                                                            SteadyStateHypothesisExport.AcDcConverterState converterState) {
        if (converterState.targetPpcc() != 0 && Double.isFinite(converterState.targetPpcc())) {
            return converterState.targetPpcc();
        }
        return Double.isFinite(converterState.p()) ? converterState.p() : 0.0;
    }

    private CgmesPropertyBuffer voltageSourceConverterUpdates(VoltageSourceConverter converter,
                                                            SteadyStateHypothesisExport.AcDcConverterState converterState) {
        CgmesPropertyBuffer.ObjectUpdate update = converterStateUpdate(CgmesNames.VS_CONVERTER, cgmesId(converter),
                converterState.targetPpcc(), converterState.targetUdc(), converterState.p(), converterState.q())
                .enumValue("VsConverter.pPccControl", "VsPpccControlKind", converterState.pPccControl())
                .enumValue("VsConverter.qPccControl", "VsQpccControlKind", converterState.operatingModeOrQpccControl());
        double reactivePowerSetpoint = state.getDouble(converter, REACTIVE_POWER_SETPOINT, converter::getReactivePowerSetpoint);
        if (Double.isFinite(reactivePowerSetpoint)) {
            // The detailed model importer reads this target without applying the sign of the regulating terminal
            update.value("VsConverter.targetQpcc", reactivePowerSetpoint);
        }
        double voltageSetpoint = state.getDouble(converter, VOLTAGE_SETPOINT, converter::getVoltageSetpoint);
        if (Double.isFinite(voltageSetpoint)) {
            update.value("VsConverter.targetUpcc", voltageSetpoint);
        }
        return update.updates();
    }

    /** The CGMES import only reads the targets of a voltage source converter when both control modes are present. */
    private CgmesPropertyBuffer vscControlModeUpdates(VscConverterStation converter) {
        return newUpdates(CgmesNames.VS_CONVERTER, cgmesId(converter))
                .enumValue("VsConverter.pPccControl", "VsPpccControlKind",
                        CgmesExportUtil.isConverterStationRectifier(converter, state) ? "pPcc" : "udc")
                .enumValue("VsConverter.qPccControl", "VsQpccControlKind",
                        state.getBoolean(converter, VOLTAGE_REGULATOR_ON, converter::isVoltageRegulatorOn)
                                ? "voltagePcc" : "reactivePcc")
                .updates();
    }

    // Operational limits (equipment values, CIM16 EQ / CIM100 SSH)

    /**
     * Which loading limit an attribute key of a change log names.
     *
     * @param owner    the branch, three windings transformer or boundary line the change was recorded on
     * @param prefix   the attribute name prefix of the side, {@code limits1}, {@code limits2}, {@code limits3} or
     *                 {@code limits} for a boundary line
     * @param type     which of the three kinds of loading limits changed
     * @param groupId  the operational limits group, taken from the payload of the event by the compactor
     * @param group    that group, read live
     * @param limits   the loading limits of that group and type, read live, {@code null} when there are none
     * @param duration the acceptable duration of the temporary limit named by the key, {@code -1} for the permanent
     *                 limit and for a key naming the whole object
     * @param member   whether the key names a single limit rather than the whole {@code LoadingLimits} object
     */
    private record LimitRef(Identifiable<?> owner, String prefix, LimitType type, String groupId,
                            OperationalLimitsGroup group, LoadingLimits limits, int duration, boolean member) {

        /** The attribute key of a replacement of the whole {@code LoadingLimits} object. */
        String wholeKey() {
            return prefix + "_" + type + EventCompactor.KEY_SEPARATOR + groupId;
        }

        /** The attribute key of the change of one limit value of that object. */
        String memberKey(int acceptableDuration) {
            return acceptableDuration < 0
                    ? prefix + "_" + type + PERMANENT_LIMIT_SUFFIX + EventCompactor.KEY_SEPARATOR + groupId
                    : prefix + "_" + type + TEMPORARY_LIMIT_VALUE_SUFFIX + EventCompactor.KEY_SEPARATOR + groupId
                            + EventCompactor.KEY_SEPARATOR + acceptableDuration;
        }

        String className() {
            return switch (type) {
                case CURRENT -> CgmesNames.CURRENT_LIMIT;
                case ACTIVE_POWER -> CgmesNames.ACTIVE_POWER_LIMIT;
                case APPARENT_POWER -> CgmesNames.APPARENT_POWER_LIMIT;
                default -> throw new IllegalStateException("Not a loading limit type: " + type);
            };
        }
    }

    /**
     * The attribute keys a limit change is reported under, once the compactor has added the group and the duration:
     * {@code limits1_CURRENT@<group>}, {@code limits2_ACTIVE_POWER.permanentLimit@<group>},
     * {@code limits_CURRENT.temporaryLimit.value@<group>@<duration>}.
     */
    private static final Pattern LIMIT_ATTRIBUTE = Pattern.compile(
            "^(limits[123]?)_([A-Z_]+?)(\\.permanentLimit|\\.temporaryLimit\\.value)?@(.*)$");

    private static final String STRUCTURAL_LIMIT_CHANGE = "adding or removing operational limits is a structural"
            + " change (new OperationalLimit/OperationalLimitType objects); apply it with the regular import";
    private static final String LIMIT_SELECTION_CHANGE = "selecting, creating or removing an operational limits"
            + " group has no counterpart in CGMES (all limit sets are exchanged)";

    private static Optional<LimitRef> parseLimitRef(Identifiable<?> owner, String attributeKey) {
        Matcher matcher = LIMIT_ATTRIBUTE.matcher(attributeKey);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String prefix = matcher.group(1);
        LimitType type;
        try {
            type = LimitType.valueOf(matcher.group(2));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String suffix = matcher.group(3);
        String tail = matcher.group(4);
        String groupId = tail;
        int duration = -1;
        if (TEMPORARY_LIMIT_VALUE_SUFFIX.equals(suffix)) {
            int separator = tail.lastIndexOf(EventCompactor.KEY_SEPARATOR.charAt(0));
            if (separator < 0) {
                return Optional.empty();
            }
            groupId = tail.substring(0, separator);
            try {
                duration = Integer.parseInt(tail.substring(separator + 1));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        OperationalLimitsGroup group = groupOf(owner, prefix, groupId);
        if (group == null) {
            return Optional.empty();
        }
        return Optional.of(new LimitRef(owner, prefix, type, groupId, group, loadingLimits(group, type), duration,
                suffix != null));
    }

    private static OperationalLimitsGroup groupOf(Identifiable<?> owner, String prefix, String groupId) {
        return switch (owner) {
            case ThreeWindingsTransformer transformer -> {
                ThreeWindingsTransformer.Leg leg = leg(transformer, prefix.substring(LIMITS_PREFIX.length()));
                yield leg == null ? null : leg.getOperationalLimitsGroup(groupId).orElse(null);
            }
            case Branch<?> branch -> switch (prefix) {
                case LIMITS_PREFIX + "1" -> branch.getOperationalLimitsGroup1(groupId).orElse(null);
                case LIMITS_PREFIX + "2" -> branch.getOperationalLimitsGroup2(groupId).orElse(null);
                default -> null;
            };
            case BoundaryLine boundaryLine -> LIMITS_PREFIX.equals(prefix)
                    ? boundaryLine.getOperationalLimitsGroup(groupId).orElse(null) : null;
            default -> null;
        };
    }

    private static LoadingLimits loadingLimits(OperationalLimitsGroup group, LimitType type) {
        return switch (type) {
            case CURRENT -> group.getCurrentLimits().orElse(null);
            case ACTIVE_POWER -> group.getActivePowerLimits().orElse(null);
            case APPARENT_POWER -> group.getApparentPowerLimits().orElse(null);
            default -> null;
        };
    }

    /** The terminal of the side a limits group belongs to, which is what the CGMES limit set is attached to. */
    private static Terminal terminalOf(LimitRef ref) {
        return switch (ref.owner()) {
            case ThreeWindingsTransformer transformer -> {
                ThreeWindingsTransformer.Leg transformerLeg =
                        leg(transformer, ref.prefix().substring(LIMITS_PREFIX.length()));
                yield transformerLeg == null ? null : transformerLeg.getTerminal();
            }
            case Branch<?> branch -> (LIMITS_PREFIX + "1").equals(ref.prefix())
                    ? branch.getTerminal1() : branch.getTerminal2();
            case BoundaryLine boundaryLine -> boundaryLine.getTerminal();
            default -> null;
        };
    }

    /**
     * The block describing a change of one loading limit.
     *
     * <p>CGMES has one object per limit value, and IIDM one {@code LoadingLimits} object holding the permanent limit
     * and every temporary limit of a side. The whole {@code LoadingLimits} is the consistency group: an event names
     * either one member of it or the object as a whole, and in both cases every value of the object is described, so
     * that the two spellings of the same change produce the same statements and a receiver never sees half a set.</p>
     *
     * <p>CIM 2.4.15 holds limit values in the equipment profile and CIM 3 in the steady state hypothesis, which is
     * the only thing the version decides here.</p>
     */
    private Result<CgmesPropertyBuffer, String> loadingLimitsUpdates(Identifiable<?> owner, String attributeKey,
                                                                     Object replacedLimits) {
        Optional<LimitRef> parsed = parseLimitRef(owner, attributeKey);
        if (parsed.isEmpty()) {
            // Either a selection change, whose payload carries no group and therefore leaves the key unrefined, or a
            // group that no longer exists. Both are structural.
            return failure(attributeKey.contains(EventCompactor.KEY_SEPARATOR)
                    ? STRUCTURAL_LIMIT_CHANGE : LIMIT_SELECTION_CHANGE);
        }
        LimitRef ref = parsed.get();
        if (ref.limits() == null) {
            return failure(STRUCTURAL_LIMIT_CHANGE);
        }
        String wholeKey = ref.wholeKey();
        LoadingLimits live = ref.limits();
        LoadingLimits replaced = replacedLoadingLimits(ref, replacedLimits);
        if (isStructuralChange(ref, replacedLimits, replaced, live)) {
            return failure(STRUCTURAL_LIMIT_CHANGE);
        }
        SortedSet<Integer> durations = state.getLimitDurations(owner, wholeKey, () -> durationsOf(live));
        boolean hasPermanentLimit = state.hasPermanentLimit(owner, wholeKey,
                () -> !Double.isNaN(live.getPermanentLimit()));
        if (!durations.equals(durationsOf(live)) || hasPermanentLimit != !Double.isNaN(live.getPermanentLimit())) {
            return failure(STRUCTURAL_LIMIT_CHANGE);
        }
        CgmesSubset subset = context.getCimVersion() == 16
                ? CgmesSubset.EQUIPMENT : CgmesSubset.STEADY_STATE_HYPOTHESIS;
        String className = ref.className();
        String property = className + ".value";
        CgmesPropertyBuffer buffer = new CgmesPropertyBuffer();
        List<Integer> members = new ArrayList<>();
        if (hasPermanentLimit) {
            members.add(-1);
        }
        members.addAll(durations);
        for (int duration : members) {
            Result<String, String> idResult = limitId(ref, className, duration);
            String limitId = idResult.fold(id -> id, reason -> null);
            if (limitId == null) {
                if (mustTravel(ref, replaced, live, duration)) {
                    return failure(idResult.fold(id -> "", reason -> reason));
                }
                // A limit the CGMES model does not hold, and that this change does not touch: the receiver keeps the
                // value its own import gave it, see mustTravel
                continue;
            }
            double value = state.getLimitValue(owner, wholeKey, ref.memberKey(duration), duration,
                    () -> liveValue(live, duration));
            if (!Double.isFinite(value) || value < 0) {
                return failure("limit values must be finite and >= 0, but " + limitId + " would be " + value);
            }
            String shared = sharedLimitIdFailure(limitId, value);
            if (shared != null) {
                return failure(shared);
            }
            buffer.object(subset, className, cgmesId(limitId)).value(property, value);
        }
        return success(buffer);
    }

    /**
     * The set of loading limits a whole-object replacement replaced, taken from the payload of the event itself.
     *
     * <p>Read from the event rather than from the state view, because the structural check below has to hold for
     * <em>every</em> export, including the partial steady state hypothesis one, which runs against the live network
     * only and would otherwise compare the live object with itself.</p>
     *
     * @return the replaced object, or {@code null} when the event is not a whole-object replacement
     */
    private static LoadingLimits replacedLoadingLimits(LimitRef ref, Object replacedLimits) {
        if (ref.member() || !(replacedLimits instanceof OperationalLimitsInfo info)) {
            return null;
        }
        return info.value() instanceof LoadingLimits limits ? limits : null;
    }

    /**
     * Whether a whole-object replacement changed the <em>structure</em> of the set of loading limits, that is the
     * presence of the permanent limit or the set of acceptable durations. CGMES models each of them as an object, so
     * such a change cannot travel as a value and must not be written as if only the remaining limits had moved.
     */
    private static boolean isStructuralChange(LimitRef ref, Object replacedLimits, LoadingLimits replaced,
                                              LoadingLimits live) {
        if (ref.member() || !(replacedLimits instanceof OperationalLimitsInfo info)) {
            return false;
        }
        if (info.value() == null || replaced == null) {
            // The limits were created by this change, or replaced by something that is not a set of loading limits
            return true;
        }
        return !durationsOf(replaced).equals(durationsOf(live))
                || Double.isNaN(replaced.getPermanentLimit()) != Double.isNaN(live.getPermanentLimit());
    }

    /**
     * Whether a limit that has no CGMES object has to travel anyway, in which case the change is refused.
     *
     * <p>The CGMES import synthesizes a permanent limit for a set that has none (see
     * {@code missing-permanent-limit-percentage}), and such a limit is not in the CGMES model at all: nothing can be
     * written about it, and the receiving side derives its own from the same option. Leaving it out is therefore the
     * right answer for every limit this change does not touch. It is <em>not</em> the right answer for the limit the
     * change actually names, nor for one a whole-object replacement moved, because that value would be silently
     * lost.</p>
     */
    private static boolean mustTravel(LimitRef ref, LoadingLimits replaced, LoadingLimits live, int duration) {
        if (ref.member()) {
            return ref.duration() == duration;
        }
        if (replaced == null) {
            // Not a whole-object replacement this translator can compare: keep the refusal
            return true;
        }
        return Double.compare(liveValue(replaced, duration), liveValue(live, duration)) != 0;
    }

    private static SortedSet<Integer> durationsOf(LoadingLimits limits) {
        SortedSet<Integer> durations = new TreeSet<>();
        limits.getTemporaryLimits().forEach(temporaryLimit -> durations.add(temporaryLimit.getAcceptableDuration()));
        return durations;
    }

    private static double liveValue(LoadingLimits limits, int acceptableDuration) {
        if (acceptableDuration < 0) {
            return limits.getPermanentLimit();
        }
        LoadingLimits.TemporaryLimit temporaryLimit = limits.getTemporaryLimit(acceptableDuration);
        return temporaryLimit == null ? Double.NaN : temporaryLimit.getValue();
    }

    /**
     * The identifier of the CGMES OperationalLimit holding one limit value.
     *
     * <p>It is the master resource identifier the import stored on the operational limits group. A network that was
     * not imported from CGMES has none, and then the identifier a full equipment export would write is used instead:
     * a receiver that read such an export stores exactly those identifiers, so the two sides agree. A network that
     * <em>was</em> imported from CGMES but whose limit carries no identifier is a limit that was created afterwards,
     * for which no CGMES object exists at all.</p>
     */
    private Result<String, String> limitId(LimitRef ref, String className, int duration) {
        String propertyName = Conversion.getOperationalLimitPropertyName(className, duration < 0,
                Math.max(duration, 0), CgmesNames.OPERATIONAL_LIMIT);
        String stored = ref.group().getProperty(propertyName);
        if (stored != null && !stored.isEmpty()) {
            return success(stored);
        }
        if (network.getExtension(CimCharacteristics.class) != null) {
            return failure("limit " + ref.groupId() + "/" + className + "/"
                    + (duration < 0 ? "patl" : "tatl " + duration) + " of " + ref.owner().getId()
                    + " has no CGMES OperationalLimit id");
        }
        Terminal terminal = terminalOf(ref);
        if (terminal == null) {
            return failure("limit " + ref.groupId() + "/" + className + " of " + ref.owner().getId()
                    + " belongs to no terminal, so no CGMES OperationalLimit id can be derived");
        }
        String setId = EquipmentExport.operationalLimitSetId(ref.group(),
                CgmesExportUtil.getTerminalId(terminal, context), context);
        return success(EquipmentExport.operationalLimitId(setId, className, duration, context));
    }

    /**
     * Refuse a change of a limit whose CGMES object describes more than one IIDM limit unless all of them take the
     * same value.
     *
     * <p>A CGMES OperationalLimitSet attached to the equipment of a line applies to both of its ends, and the import
     * creates a group on each side storing the same OperationalLimit identifiers. One CGMES value cannot say two
     * things, so changing one side alone is not exportable.</p>
     */
    private String sharedLimitIdFailure(String limitId, double value) {
        List<CgmesLimitIndex.LimitSlot> slots = limitIndex().slots(limitId);
        if (slots.size() <= 1) {
            return null;
        }
        for (CgmesLimitIndex.LimitSlot slot : slots) {
            OperationalLimitsGroup group = groupOf(slot.owner(), slot.prefix(), slot.groupId());
            LoadingLimits limits = group == null ? null : loadingLimits(group, slot.type());
            if (limits == null) {
                return sharedLimitFailure(limitId);
            }
            double slotValue = state.getLimitValue(slot.owner(), slot.wholeKey(), slot.memberKey(), slot.duration(),
                    () -> liveValue(limits, slot.duration()));
            if (Double.compare(slotValue, value) != 0) {
                return sharedLimitFailure(limitId);
            }
        }
        return null;
    }

    private static String sharedLimitFailure(String limitId) {
        return "OperationalLimit " + limitId + " applies to the whole equipment in CGMES; change both sides to the"
                + " same value";
    }

    /** The index of the CGMES limit identifiers of this network, built on first use and shared by both directions. */
    private CgmesLimitIndex limitIndex() {
        if (limitIndex == null) {
            limitIndex = CgmesLimitIndex.of(network);
        }
        return limitIndex;
    }

    // Voltage level limits

    /**
     * The block describing a change of the high or the low voltage limit of a voltage level.
     *
     * <p>CGMES models these in two ways, and which one a network uses is decided by what its import found. When the
     * equipment model carried {@code VoltageLimit} objects, the import stored their identifiers on the voltage level
     * and the update reads their values back, so the change is written on every one of them. When it did not, the
     * limits live on the {@code VoltageLevel} itself, which is also where the full equipment export writes them.</p>
     *
     * <p>A receiver only accepts a {@code VoltageLimit} value strictly inside the range the
     * {@code VoltageLevel.highVoltageLimit} and {@code .lowVoltageLimit} attributes declare, and silently keeps its
     * previous value otherwise, so a value outside that range is refused here rather than written and lost.</p>
     */
    private Result<CgmesPropertyBuffer, String> voltageLimitUpdates(VoltageLevel voltageLevel, String attribute) {
        boolean high = HIGH_VOLTAGE_LIMIT.equals(attribute);
        double value = state.getDouble(voltageLevel, attribute,
                high ? voltageLevel::getHighVoltageLimit : voltageLevel::getLowVoltageLimit);
        if (!Double.isFinite(value)) {
            return failure("voltage limits must be finite, but " + voltageLevel.getId() + "." + attribute
                    + " would be " + value);
        }
        double other = state.getDouble(voltageLevel, high ? LOW_VOLTAGE_LIMIT : HIGH_VOLTAGE_LIMIT,
                high ? voltageLevel::getLowVoltageLimit : voltageLevel::getHighVoltageLimit);
        double newHigh = high ? value : other;
        double newLow = high ? other : value;
        if (Double.isFinite(newHigh) && Double.isFinite(newLow) && newHigh <= newLow) {
            return failure("the receiver only accepts voltage limits strictly inside the VoltageLevel range ("
                    + newLow + ", " + newHigh + ")");
        }
        String property = high ? Conversion.PROPERTY_OPERATIONAL_LIMIT_HIGH_VOLTAGE_LIMIT
                : Conversion.PROPERTY_OPERATIONAL_LIMIT_LOW_VOLTAGE_LIMIT;
        String ids = voltageLevel.getProperty(property);
        if (ids == null || ids.isEmpty()) {
            return voltageLevelAttributeUpdates(voltageLevel, high, value);
        }
        Double rangeHigh = OperationalLimitConversion.getNormalVoltageLimitValue(voltageLevel,
                Conversion.PROPERTY_HIGH_VOLTAGE_LIMIT);
        Double rangeLow = OperationalLimitConversion.getNormalVoltageLimitValue(voltageLevel,
                Conversion.PROPERTY_LOW_VOLTAGE_LIMIT);
        if (rangeHigh != null && value >= rangeHigh || rangeLow != null && value <= rangeLow) {
            double current = high ? voltageLevel.getHighVoltageLimit() : voltageLevel.getLowVoltageLimit();
            if (Double.compare(value, current) != 0) {
                // The value being described is not the one the network holds, so this is the state before the change:
                // the limit the change started from is not one a VoltageLimit object can express
                return failure("the current " + attribute + " of " + voltageLevel.getId() + " is the VoltageLevel"
                        + " attribute itself, no VoltageLimit object binds it, so the change cannot be undone through"
                        + " VoltageLimit values");
            }
            return failure("the receiver only accepts voltage limits strictly inside the VoltageLevel range ("
                    + rangeLow + ", " + rangeHigh + ")");
        }
        CgmesSubset subset = context.getCimVersion() == 16
                ? CgmesSubset.EQUIPMENT : CgmesSubset.STEADY_STATE_HYPOTHESIS;
        CgmesPropertyBuffer buffer = new CgmesPropertyBuffer();
        for (String id : ids.split(";")) {
            if (!id.isEmpty()) {
                buffer.object(subset, VOLTAGE_LIMIT, cgmesId(id)).value(VOLTAGE_LIMIT + ".value", value);
            }
        }
        return success(buffer);
    }

    /**
     * A voltage level with no {@code VoltageLimit} objects carries its limits as attributes of the
     * {@code VoltageLevel}, which belong to the equipment profile in every CIM version.
     */
    private Result<CgmesPropertyBuffer, String> voltageLevelAttributeUpdates(VoltageLevel voltageLevel, boolean high,
                                                                            double value) {
        if (voltageLevel.getAliasFromType(MERGED_VOLTAGE_LEVEL_ALIAS_PREFIX + "1").isPresent()
                || voltageLevel.getAliasFromType(MERGED_VOLTAGE_LEVEL_ALIAS_PREFIX + "2").isPresent()) {
            return failure("merged voltage levels have several CGMES VoltageLevel objects");
        }
        return success(newUpdates(CgmesSubset.EQUIPMENT, CgmesNames.VOLTAGE_LEVEL, cgmesId(voltageLevel))
                .value(CgmesNames.VOLTAGE_LEVEL + "." + (high ? HIGH_VOLTAGE_LIMIT : LOW_VOLTAGE_LIMIT), value)
                .updates());
    }

    // Branch impedances (equipment profile)

    /**
     * The block describing a change of an impedance of a line.
     *
     * <p>IIDM splits the shunt admittance of a line in two halves, one per end, while CGMES holds a single total that
     * the import splits equally. A line whose two halves differ therefore has no CGMES spelling, and neither has a
     * zero impedance line inside one voltage level, which the import turns into a switch.</p>
     */
    private Result<CgmesPropertyBuffer, String> lineImpedanceUpdates(Line line, String attribute) {
        String originalClass = line.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS, CgmesNames.AC_LINE_SEGMENT);
        if (!isCgmesBranchClass(originalClass)) {
            return failure(originalClass + " " + line.getId()
                    + " is represented as a switch in CGMES or is not a CGMES branch, so it carries no impedance");
        }
        if (isMergedIdentifier(line.getId())) {
            return failure("the identifier of line " + line.getId() + " is not a single CGMES master resource"
                    + " identifier: it is a pair of identifiers joined by \"" + MERGED_ID_SEPARATOR + "\", which"
                    + " is how powsybl names an object that stands for two, and a difference model names one"
                    + " existing CGMES object per statement");
        }
        if (CgmesNames.EQUIVALENT_BRANCH.equals(originalClass)
                && line.getTerminal1().getVoltageLevel().getNominalV()
                    != line.getTerminal2().getVoltageLevel().getNominalV()) {
            // EquivalentBranchConversion folds the ideal ratio between the two nominal voltages into the IIDM
            // parameters, so the IIDM value is not the value the CGMES file holds
            return failure("the import transforms EquivalentBranch parameters between nominal voltages");
        }
        double r = state.getDouble(line, R, line::getR);
        double x = state.getDouble(line, X, line::getX);
        Result<CgmesPropertyBuffer, String> impedance = checkImpedance(line, r, x);
        if (impedance != null) {
            return impedance;
        }
        if (R.equals(attribute) || X.equals(attribute)) {
            return success(seriesImpedanceUpdates(originalClass, cgmesId(line), attribute,
                    R.equals(attribute) ? r : x));
        }
        if (!CgmesNames.AC_LINE_SEGMENT.equals(originalClass)) {
            return failure("a " + originalClass + " has no shunt admittance in CGMES");
        }
        boolean conductance = G1.equals(attribute) || G2.equals(attribute);
        double side1 = state.getDouble(line, conductance ? G1 : B1, conductance ? line::getG1 : line::getB1);
        double side2 = state.getDouble(line, conductance ? G2 : B2, conductance ? line::getG2 : line::getB2);
        if (!Double.isFinite(side1) || !Double.isFinite(side2)) {
            return failure("impedance values must be finite (r, x >= 0)");
        }
        if (!symmetric(side1, side2)) {
            return failure("CGMES ACLineSegment holds one total gch/bch split equally on import; g1 == g2 and"
                    + " b1 == b2 are required");
        }
        return success(newUpdates(CgmesSubset.EQUIPMENT, originalClass, cgmesId(line))
                .rawLiteral(CgmesNames.AC_LINE_SEGMENT + "." + (conductance ? "gch" : "bch"),
                        CgmesExportUtil.formatExact(side1 + side2))
                .updates());
    }

    /**
     * The block describing a change of an impedance of a boundary line, whose CGMES branch carries the shunt
     * admittance undivided: the import sets {@code g = gch} and {@code b = bch} one to one.
     */
    private Result<CgmesPropertyBuffer, String> boundaryLineImpedanceUpdates(BoundaryLine boundaryLine, String attribute) {
        String originalClass = boundaryLine.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS, CgmesNames.AC_LINE_SEGMENT);
        if (!isCgmesBranchClass(originalClass)) {
            return failure(originalClass + " " + boundaryLine.getId()
                    + " is represented as a switch in CGMES or is not a CGMES branch, so it carries no impedance");
        }
        double r = state.getDouble(boundaryLine, R, boundaryLine::getR);
        double x = state.getDouble(boundaryLine, X, boundaryLine::getX);
        if (!Double.isFinite(r) || !Double.isFinite(x) || r < 0 || x < 0) {
            return failure("impedance values must be finite (r, x >= 0)");
        }
        if (R.equals(attribute) || X.equals(attribute)) {
            return success(seriesImpedanceUpdates(originalClass, cgmesId(boundaryLine), attribute,
                    R.equals(attribute) ? r : x));
        }
        if (!CgmesNames.AC_LINE_SEGMENT.equals(originalClass)) {
            return failure("a " + originalClass + " has no shunt admittance in CGMES");
        }
        boolean conductance = G.equals(attribute);
        double value = state.getDouble(boundaryLine, attribute,
                conductance ? boundaryLine::getG : boundaryLine::getB);
        if (!Double.isFinite(value)) {
            return failure("impedance values must be finite (r, x >= 0)");
        }
        return success(newUpdates(CgmesSubset.EQUIPMENT, CgmesNames.AC_LINE_SEGMENT, cgmesId(boundaryLine))
                .rawLiteral(CgmesNames.AC_LINE_SEGMENT + "." + (conductance ? "gch" : "bch"),
                        CgmesExportUtil.formatExact(value))
                .updates());
    }

    /**
     * The statement describing one series impedance value of a CGMES branch.
     *
     * <p>An {@code EquivalentBranch} states the impedance of both directions, and its import refuses a branch whose
     * {@code r21}/{@code x21} differ from {@code r}/{@code x} &mdash; such a branch is not converted at all. IIDM
     * holds a single value, so both directions are written; a base file that has no {@code r21} is unaffected,
     * because the import reads an absent one as {@code r}.</p>
     */
    private static CgmesPropertyBuffer seriesImpedanceUpdates(String originalClass, String subjectId,
                                                              String attribute, double value) {
        CgmesPropertyBuffer.ObjectUpdate update = newUpdates(CgmesSubset.EQUIPMENT, originalClass, subjectId)
                .rawLiteral(originalClass + "." + attribute, CgmesExportUtil.formatExact(value));
        if (CgmesNames.EQUIVALENT_BRANCH.equals(originalClass)) {
            update.rawLiteral(originalClass + "." + attribute + "21", CgmesExportUtil.formatExact(value));
        }
        return update.updates();
    }

    /** A failure when the resistance and the reactance cannot be written as they stand, {@code null} otherwise. */
    private static Result<CgmesPropertyBuffer, String> checkImpedance(Line line, double r, double x) {
        if (!Double.isFinite(r) || !Double.isFinite(x) || r < 0 || x < 0) {
            return failure("impedance values must be finite (r, x >= 0)");
        }
        if (r == 0 && x == 0 && line.getTerminal1().getVoltageLevel() == line.getTerminal2().getVoltageLevel()) {
            return failure("a zero-impedance branch inside one voltage level becomes a switch on import");
        }
        return null;
    }

    /**
     * Whether the two halves of a shunt admittance are the same value, to a relative tolerance of 1e-9.
     *
     * <p>Relative rather than absolute, because a susceptance is routinely of the order of 1e-4 S and an absolute
     * tolerance of 1e-9 S would accept a relative difference of 1e-5 there: the receiver splits the total equally, so
     * it would hold a value that differs from the sender's in its fifth significant digit while the mapping promises
     * an exact round trip. Two halves that came from one {@code gch/2} are bit-identical, so nothing legitimate is
     * lost by being strict.</p>
     */
    private static boolean symmetric(double side1, double side2) {
        double difference = Math.abs(side1 - side2);
        return difference <= 1e-9 * Math.max(Math.abs(side1), Math.abs(side2));
    }

    // Regulating controls

    /**
     * The description of the RegulatingControl carrying the regulation of the given equipment.
     *
     * <p>A RegulatingControl is shared, so the description holds the combined state of every equipment regulating
     * through it, not only of the one that changed: see {@link CgmesChangeRegulatingControls}.</p>
     */
    private Result<CgmesPropertyBuffer, String> regulatingControlUpdates(Identifiable<?> identifiable) {
        return regulatingControlId(identifiable).flatMap(id -> regulatingControls.updatesFor(id, state));
    }

    /**
     * The identifier of the RegulatingControl carrying the regulation of the given equipment, or a failure if it
     * has none.
     */
    private Result<String, String> regulatingControlId(Identifiable<?> identifiable) {
        if (!identifiable.hasProperty(PROPERTY_REGULATING_CONTROL)) {
            return failure(identifiable.getType() + " " + identifiable.getId()
                    + " has no CGMES regulating control to carry this change");
        }
        return success(context.getNamingStrategy().getCgmesIdFromProperty(identifiable, PROPERTY_REGULATING_CONTROL));
    }

    // Helpers

    private String cgmesId(Identifiable<?> identifiable) {
        return context.getNamingStrategy().getCgmesId(identifiable);
    }

    /**
     * The identifier a CGMES object the import stored as a property has in this export.
     *
     * <p>The identity naming strategy returns it unchanged; a strategy that rewrites identifiers keeps a UUID and
     * hashes anything else, exactly as it does for the identifiers of equipment.</p>
     */
    private String cgmesId(String identifier) {
        return context.getNamingStrategy().getCgmesId(identifier);
    }

    private String cgmesIdFromAlias(Identifiable<?> identifiable, String aliasType) {
        return context.getNamingStrategy().getCgmesIdFromAlias(identifiable, aliasType);
    }

    /**
     * Report a change the SSH profile cannot express, as {@link UnsupportedChangeBehavior} asks: by failing when
     * changes must not be lost, and by logging and moving on to the next change otherwise.
     */
    void reject(NetworkEvent event, String reason) {
        String change = switch (event) {
            case UpdateNetworkEvent updateEvent -> updateEvent.id() + "." + updateEvent.attribute();
            case ExtensionUpdateNetworkEvent extensionEvent ->
                extensionEvent.id() + "." + extensionEvent.extensionName() + "." + extensionEvent.attribute();
            case ExtensionCreationNetworkEvent creationEvent -> creationEvent.id() + "." + creationEvent.extensionName();
            default -> String.valueOf(event);
        };
        String message = "Change cannot be exported to " + targetDescription + ": " + reason + ". Change: " + change;
        if (unsupportedChangeBehavior == UnsupportedChangeBehavior.FAIL) {
            throw new PowsyblException(message);
        }
        LOGGER.warn("{}", message);
    }

}
