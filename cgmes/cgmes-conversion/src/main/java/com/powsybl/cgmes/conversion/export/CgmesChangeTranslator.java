/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.export.PartialSshExport.UnsupportedChangeBehavior;
import com.powsybl.cgmes.conversion.mapping.LoadRows;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.DcSwitch;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.LccConverterStation;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.events.ExtensionCreationNetworkEvent;
import com.powsybl.iidm.network.events.ExtensionUpdateNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 * <p>This class is the event loop of the mapping: the variant and subset checks, the refusals that depend on the key
 * of a change ({@link RegulationKeyRefusals}) and the dispatch of a change to the family of its equipment. The families
 * describe the CGMES objects ({@link LoadFamily}, {@link MachineFamily}, {@link TapChangerAndShuntFamily},
 * {@link SwitchAndTerminalFamily}, {@link HvdcFamily}, {@link LimitFamily}, {@link ControlAreaFamily}; the shared regulating controls
 * {@link RegulatingControlFamily}); they declare the keys this class dispatches on, and the full steady state hypothesis
 * export describes through the same families ({@link #forFullModel}).</p>
 *
 * <p>Every value a change log can speak about is read through an {@link IidmStateView}; structure is read live. A
 * value the previous state needs but the change log never recorded makes the change unsupported rather than wrong,
 * through {@link UnreconstructibleStateException}.</p>
 *
 * <p><b>The local voltage target of a regulation holder</b> (review 21 round 3, R3-M3): in a voltage mode without a
 * regulating terminal it is the regulating target and is exported. In another mode, or when the regulation names the
 * holder's own terminal (it then regulates to its target value), it is not represented in the steady state hypothesis
 * and nothing reads it: a change of it alone is not a change of the SSH, translated into nothing and not listed as
 * exported. In a voltage mode with a regulating terminal elsewhere it is the target a load flow falls back to when it
 * switches to local control, and the SSH has no property for it: refused. The same rule is in
 * {@code docs/grid_exchange_formats/cgmes/export.md}.</p>
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
    /** Boundary line generation only: every voltage regulation holder reports {@link #LOCAL_TARGET_Q} instead. */
    static final String TARGET_Q = "targetQ";
    /** Boundary line generation only: every voltage regulation holder reports {@link #LOCAL_TARGET_V} instead. */
    static final String TARGET_V = "targetV";
    /** Boundary line generation spells the same idea differently from every other regulating equipment. */
    static final String VOLTAGE_REGULATION_ON = "voltageRegulationOn";
    static final String SECTION_COUNT = "sectionCount";
    static final String ACTIVE_POWER_SETPOINT = "activePowerSetpoint";
    static final String CONVERTERS_MODE = "convertersMode";
    static final String POWER_FACTOR = "powerFactor";
    // The voltage regulation of IIDM (powsybl-core #3699): the local targets live on the holder, everything else on
    // its VoltageRegulation, whose changes are reported with the prefix "VoltageRegulation."
    static final String LOCAL_TARGET_Q = "localTargetQ";
    static final String LOCAL_TARGET_V = "localTargetV";
    static final String VR_PREFIX = "VoltageRegulation.";
    static final String VR_TARGET_VALUE = VR_PREFIX + "TargetValue";
    static final String VR_TARGET_DEADBAND = VR_PREFIX + "TargetDeadband";
    static final String VR_REGULATING = VR_PREFIX + "isRegulating";
    static final String VR_MODE = VR_PREFIX + "RegulationMode";
    static final String VR_SLOPE = VR_PREFIX + "Slope";
    static final String VR_TERMINAL = VR_PREFIX + "Terminal";
    // Tap changers: a change is reported on the transformer, as prefix + end + suffix. A ratio tap changer regulates
    // through its VoltageRegulation, whose suffixes are the VR_ names above: "ratioTapChanger2.VoltageRegulation.TargetValue"
    static final String TAP_POSITION_SUFFIX = ".tapPosition";
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
    // Operational limits, voltage limits and branch impedances (equipment profile)
    static final String LIMITS_PREFIX = CgmesLimitIndex.LIMITS_PREFIX;
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

    /** The default of {@link #targetDescription}, used by the partial SSH export. */
    static final String PARTIAL_SSH_TARGET = "a partial SSH file";

    private final Network network;
    private final UnsupportedChangeBehavior unsupportedChangeBehavior;
    /** What the rejection message calls the document a change cannot be written to. */
    private final String targetDescription;
    /** The profiles this export writes. A change describing any other profile is unsupported here. */
    private final Set<CgmesSubset> allowedSubsets;

    private final RegulatingControlFamily regulatingControls;
    // The families a change is dispatched to, which the full export describes through as well
    final LoadFamily loads;
    final MachineFamily machines;
    final TapChangerAndShuntFamily tapChangers;
    final SwitchAndTerminalFamily switches;
    final HvdcFamily hvdc;
    final LimitFamily limits;
    final ControlAreaFamily controlAreas;
    /** Who reads the description: which objects it may name and which refusals it honours. */
    private final Scope scope;
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
                              RegulatingControlFamily regulatingControls) {
        this(network, context, unsupportedChangeBehavior, targetDescription, allowedSubsets, state, regulatingControls,
                Scope.CHANGES);
    }

    private CgmesChangeTranslator(Network network, CgmesExportContext context,
                                  UnsupportedChangeBehavior unsupportedChangeBehavior, String targetDescription,
                                  Set<CgmesSubset> allowedSubsets, IidmStateView state,
                                  RegulatingControlFamily regulatingControls, Scope scope) {
        this.network = network;
        this.unsupportedChangeBehavior = unsupportedChangeBehavior;
        this.targetDescription = Objects.requireNonNull(targetDescription);
        this.allowedSubsets = Set.copyOf(allowedSubsets);
        Objects.requireNonNull(state);
        this.scope = Objects.requireNonNull(scope);
        this.regulatingControls = regulatingControls != null
                ? regulatingControls : new RegulatingControlFamily(network, context, scope);
        this.loads = new LoadFamily(context, state, scope);
        this.machines = new MachineFamily(context, state, scope, this.regulatingControls);
        this.tapChangers = new TapChangerAndShuntFamily(context, state, scope, this.regulatingControls);
        this.switches = new SwitchAndTerminalFamily(context, state, scope);
        this.hvdc = new HvdcFamily(context, state, scope);
        this.limits = new LimitFamily(network, context, state, scope);
        this.controlAreas = new ControlAreaFamily(context, state, scope);
    }

    /**
     * The mapping as a full steady state hypothesis export reads it: the network as it stands, every object named
     * (under a generated identifier where the import recorded none), and only the refusals a full model honours
     * ({@link Scope#FULL_MODEL}).
     */
    static CgmesChangeTranslator forFullModel(Network network, CgmesExportContext context) {
        return new CgmesChangeTranslator(network, context, UnsupportedChangeBehavior.FAIL, "a full SSH export",
                EnumSet.of(CgmesSubset.STEADY_STATE_HYPOTHESIS), IidmStateView.LIVE, null, Scope.FULL_MODEL);
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

    /**
     * What the translation of a change log produced.
     *
     * @param after          the properties describing the network as it stands, buffered per CGMES object
     * @param before         the properties describing the state before the change set, or {@code null} when no
     *                       translator of that state was given
     * @param exportedEvents the changes that were translated, in order; the others were rejected
     */
    record Translation(CgmesPropertyBuffer after, CgmesPropertyBuffer before, List<NetworkEvent> exportedEvents) {
    }

    /**
     * Translate every recorded change into the CGMES properties that describe it, buffered per object.
     *
     * <p>The changes are expected to have been compacted already ({@link EventCompactor#compact}) to avoid writing
     * the same attribute twice.</p>
     *
     * <p>A change is exported only when every given translator translates it; otherwise it is rejected through
     * {@code after}, whatever direction refused it, so the reject behaviour is the export's. The values are read
     * through the state view of each translator, not taken from the changes themselves, so the result describes a
     * state the network really was in.</p>
     *
     * @param events the compacted changes to translate, in the order in which they are to be written
     * @param after  the translator reading the network as it stands
     * @param before the translator reading the state before the change set, or {@code null} for a forward-only
     *               export such as the partial SSH one
     * @throws PowsyblException under {@link UnsupportedChangeBehavior#FAIL}, on the first change that cannot be
     *                          exported
     */
    static Translation translateAll(Collection<NetworkEvent> events, CgmesChangeTranslator after,
                                    CgmesChangeTranslator before) {
        CgmesPropertyBuffer afterUpdates = new CgmesPropertyBuffer();
        CgmesPropertyBuffer beforeUpdates = before == null ? null : new CgmesPropertyBuffer();
        List<NetworkEvent> exportedEvents = new ArrayList<>();
        for (NetworkEvent event : events) {
            // The before direction is only translated when the after direction succeeded
            Result<CgmesPropertyBuffer, String> afterResult = after.translate(event);
            Result<CgmesPropertyBuffer, String> beforeResult = before == null || afterResult instanceof Result.Failure
                    ? afterResult : before.translate(event);
            if (afterResult instanceof Result.Success(CgmesPropertyBuffer a)
                    && beforeResult instanceof Result.Success(CgmesPropertyBuffer b)) {
                afterUpdates.mergeFrom(a);
                if (beforeUpdates != null) {
                    beforeUpdates.mergeFrom(b);
                }
                // A change the steady state hypothesis does not represent is translated into nothing: it neither
                // reaches the file nor is refused, and is not listed
                if (!a.isEmpty()) {
                    exportedEvents.add(event);
                }
            } else if (beforeResult instanceof Result.Failure(String reason)) {
                after.reject(event, reason);
            }
        }
        return new Translation(afterUpdates, beforeUpdates, exportedEvents);
    }

    /**
     * Translate a single change into the CGMES properties describing it.
     *
     * <p>One change may map to several properties; {@link #translateAll} merges them per object.</p>
     */
    Result<CgmesPropertyBuffer, String> translate(NetworkEvent event) {
        String workingVariantId = network.getVariantManager().getWorkingVariantId();
        String eventVariantId = EventCompactor.variantIdOf(event);
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
        return machines.extensionUpdates(identifiable, extensionName, attribute);
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
        // The refusals of a regulation that depend on the change: an echo, a regulation the import would give, a
        // local target without property; for every key of a holder, before any family is asked
        Optional<String> refusal = RegulationKeyRefusals.refuse(identifiable, event.attribute(), attribute, scope);
        if (refusal.isPresent()) {
            return failure(refusal.get());
        }
        if (RegulationKeyRefusals.notRepresented(identifiable, attribute)) {
            // Not represented in the steady state hypothesis and read by nothing: not a change of the SSH
            return success(new CgmesPropertyBuffer());
        }
        // A tap changer change is reported on its transformer: the key is parsed for a transformer only
        TapChangerAndShuntFamily.TapChangerAttribute tapChangerAttribute =
                identifiable instanceof TwoWindingsTransformer || identifiable instanceof ThreeWindingsTransformer
                        ? TapChangerAndShuntFamily.tapChangerAttribute(attribute) : null;
        return switch (identifiable) {
            case Switch sw when OPEN.equals(attribute) -> switches.switchUpdates(sw);
            case DcSwitch dcSwitch when OPEN.equals(attribute) -> switches.dcSwitchUpdates(dcSwitch);
            case Load load when LoadRows.keys().contains(attribute) -> loads.loadUpdates(load);
            case BoundaryLine boundaryLine when MachineFamily.BOUNDARY_LINE_KEYS.contains(attribute) -> machines.boundaryLineUpdates(boundaryLine);
            case Generator generator when MachineFamily.GENERATOR_KEYS.contains(attribute) -> machines.generatorUpdates(generator, attribute);
            case TwoWindingsTransformer transformer when tapChangerAttribute != null -> tapChangers.twoWindingsTapChangerUpdates(transformer, tapChangerAttribute);
            case ThreeWindingsTransformer transformer when tapChangerAttribute != null -> tapChangers.threeWindingsTapChangerUpdates(transformer, tapChangerAttribute);
            case ShuntCompensator shunt when TapChangerAndShuntFamily.SHUNT_KEYS.contains(attribute) -> tapChangers.shuntCompensatorUpdates(shunt, attribute);
            case StaticVarCompensator svc when TapChangerAndShuntFamily.STATIC_VAR_COMPENSATOR_KEYS.contains(attribute) -> tapChangers.staticVarCompensatorUpdates(svc);
            case HvdcLine hvdcLine when HvdcFamily.LINE_KEYS.contains(attribute) -> hvdc.hvdcLineUpdates(hvdcLine, attribute);
            case LccConverterStation converter when POWER_FACTOR.equals(attribute) -> hvdc.lccPowerFactorUpdates(converter);
            case AcDcConverter<?> converter when HvdcFamily.CONVERTER_KEYS.contains(attribute) -> hvdc.acDcConverterUpdates(converter, attribute);
            case VscConverterStation converter when HvdcFamily.CONTROL_KEYS.contains(attribute) || VR_TERMINAL.equals(attribute) ->
                hvdc.vscStationUpdates(converter, attribute, event);
            case VoltageLevel voltageLevel when LimitFamily.VOLTAGE_LEVEL_KEYS.contains(attribute) ->
                limits.voltageLimitUpdates(voltageLevel, attribute);
            case Line line when LimitFamily.LINE_KEYS.contains(attribute) -> limits.lineImpedanceUpdates(line, attribute);
            case BoundaryLine boundaryLine when LimitFamily.BOUNDARY_LINE_KEYS.contains(attribute) ->
                limits.boundaryLineImpedanceUpdates(boundaryLine, attribute);
            case Identifiable<?> owner when attribute.startsWith(LIMITS_PREFIX)
                    && CgmesLimitIndex.holdsLoadingLimits(owner) ->
                limits.loadingLimitsUpdates(owner, attribute, event.oldValue());
            // The control mode of a converter is SSH data (qPccControl) and is handled above; for every other holder
            // the mode and the regulating terminal are equipment data, and CGMES has no slope on a RegulatingControl
            case Identifiable<?> holder when RegulationKeyRefusals.EQUIPMENT_KEYS.contains(attribute) ->
                failure(RegulationKeyRefusals.equipmentOnly(attribute));
            default -> unmappedAttributeUpdates(identifiable, attribute);
        };
    }

    /** No mapping claimed the change: no CGMES profile this export writes has a property for it. */
    private static Result<CgmesPropertyBuffer, String> unmappedAttributeUpdates(Identifiable<?> identifiable, String attribute) {
        Optional<String> unread = LimitFamily.transformerImpedanceRefusal(identifiable, attribute)
                .or(() -> RegulationKeyRefusals.unread(identifiable, attribute));
        if (unread.isPresent()) {
            return failure(unread.get());
        }
        return failure("no CGMES steady state property corresponds to " + identifiable.getType() + "." + attribute);
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
