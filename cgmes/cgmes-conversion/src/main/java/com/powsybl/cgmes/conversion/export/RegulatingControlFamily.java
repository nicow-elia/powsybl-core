/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.RegulatingControlMapping;
import com.powsybl.cgmes.conversion.mapping.Quantity;
import com.powsybl.cgmes.conversion.naming.CgmesObjectReference.Part;
import com.powsybl.cgmes.extensions.CgmesTapChanger;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.TapChanger;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulationHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.powsybl.cgmes.conversion.Conversion.ALIAS_PHASE_TAP_CHANGER1;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_PHASE_TAP_CHANGER2;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_RATIO_TAP_CHANGER1;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_RATIO_TAP_CHANGER2;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_CGMES_ORIGINAL_CLASS;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_IS_EQUIVALENT_SHUNT;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_MODE;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_REGULATING_CONTROL;
import static com.powsybl.cgmes.conversion.elements.transformers.AbstractTransformerConversion.getCgmesTapChanger;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOCAL_TARGET_Q;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOCAL_TARGET_V;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REGULATING_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REGULATION_MODE_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REGULATION_VALUE_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_DEADBAND_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_MODE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_PREFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_REGULATING;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_SLOPE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TARGET_DEADBAND;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TARGET_VALUE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TERMINAL;
import static com.powsybl.cgmes.conversion.export.CgmesExportUtil.getRegulatingControlMode;
import static com.powsybl.cgmes.conversion.export.elements.RegulatingControlEq.REGULATING_CONTROL_REACTIVE_POWER;
import static com.powsybl.cgmes.conversion.export.elements.RegulatingControlEq.REGULATING_CONTROL_VOLTAGE;
import static com.powsybl.commons.util.Result.failure;
import static com.powsybl.commons.util.Result.success;

/**
 * The CGMES RegulatingControl and TapChangerControl objects, in every export of the steady state hypothesis: what a
 * holder or a tap changer contributes to its control ({@link #regulatingControlView}, {@link #phaseTapChangerView}),
 * how the contributions of the users of one control combine ({@link #combine}), how the control is written
 * ({@link #describeRegulatingControl}), which keys of a holder describe it and the echoes the deprecated setters
 * report them under ({@link #HOLDER_KEYS}), and the refusals of a control.
 *
 * <p>A RegulatingControl is shared: a generator, a shunt compensator and a tap changer can all point at the same
 * one, and CGMES then carries a single enabled flag, a single target and a single deadband for all of them. Writing
 * the view of the one piece of equipment that happened to change would therefore switch the regulation of its
 * neighbours off. Every description a change export asks for is the combination of the views of <em>all</em> the users
 * of the control, exactly as the full export combines them, so that a receiver reading a partial file and a receiver
 * reading a full one end up in the same state.</p>
 *
 * <p>Finding the users means walking the regulating equipment of the network, which is why the index is built
 * lazily, on the first control that is actually needed, and then kept: an export that changes no regulation never
 * pays for it, and one that changes a hundred of them pays for it once. The index holds structure only &mdash; which
 * equipment regulates through which control &mdash; and every value is read when a description is asked for, from
 * the {@link IidmStateView} of that pass, so a difference model export shares one index between both directions. The
 * full export fills upstream's own map of views in its section pass instead (its control order is upstream's).</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RegulatingControlFamily {

    private static final Logger LOG = LoggerFactory.getLogger(RegulatingControlFamily.class);

    /**
     * A key of a regulation and the names the deprecated setters of IIDM report a change of it under after the
     * VoltageRegulation API reported it (powsybl-core #3699): the <em>echoes</em>, whose fate {@link EventCompactor}
     * decides.
     */
    record Key(String canonical, List<String> echoes) {
        Key(String canonical, String... echoes) {
            this(canonical, List.of(echoes));
        }
    }

    /** What a deprecated setter reports a target under: the local or the remote target, by the regulating terminal. */
    private static final String[] TARGET_ECHOES = {"targetV", "voltageSetpoint", "reactivePowerSetpoint"};

    /**
     * The keys of the regulation of a holder: the local targets on the holder, everything else on its VoltageRegulation.
     * No other equipment than a holder reports these echo names at powsybl-core 7.5 (a boundary line spells its flag
     * voltageRegulationOn).
     */
    static final List<Key> HOLDER_KEYS = List.of(
            // generator, shunt compensator, VSC converter station, voltage source converter; static var compensator
            new Key(VR_REGULATING, "voltageRegulatorOn", "regulating"),
            new Key(VR_MODE, "regulationMode"),
            new Key(VR_TARGET_DEADBAND, "targetDeadband"),
            new Key(VR_TERMINAL, "regulatingTerminal"),
            new Key(VR_SLOPE),
            new Key(LOCAL_TARGET_V, TARGET_ECHOES),
            new Key(LOCAL_TARGET_Q, TARGET_ECHOES),
            new Key(VR_TARGET_VALUE, TARGET_ECHOES));

    /** The keys of the regulation of a ratio tap changer, below the name a change gives the tap changer. */
    static final List<Key> RATIO_TAP_CHANGER_KEYS = List.of(
            new Key(VR_REGULATING, "regulating"),
            new Key(VR_MODE, "regulationMode"),
            new Key(VR_TARGET_DEADBAND, "targetDeadband"),
            new Key(VR_TERMINAL, "regulationTerminal"),
            new Key(VR_TARGET_VALUE, "regulationValue"));

    private static final Pattern RATIO_TAP_CHANGER_ATTRIBUTE = Pattern.compile("^(ratioTapChanger[123]?)\\.(\\w+)$");

    /** The canonical keys each echo name of a holder repeats, indexed once (asked for every compacted event). */
    private static final Map<String, Set<String>> HOLDER_ECHOES = HOLDER_KEYS.stream()
            .flatMap(key -> key.echoes().stream().map(echo -> Map.entry(echo, key.canonical())))
            .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toUnmodifiableSet())));

    /** The keys of a holder whose change describes its control: the targets and the flag. */
    private static final Set<String> CONTROL_KEYS = Set.of(LOCAL_TARGET_V, VR_TARGET_VALUE, VR_REGULATING);
    static final Set<String> GENERATOR_KEYS = CONTROL_KEYS;
    /** The CGMES update reads the deadband of a RegulatingControl for shunt compensators (and tap changers) only. */
    static final Set<String> SHUNT_KEYS = union(CONTROL_KEYS, Set.of(VR_TARGET_DEADBAND));
    /** The single target of a compensator is the one of its mode: the local reactive power target too. */
    static final Set<String> STATIC_VAR_COMPENSATOR_KEYS = union(CONTROL_KEYS, Set.of(LOCAL_TARGET_Q));

    private final Network network;
    private final CgmesExportContext context;
    /** Which controls are named (a full model names the ones the import did not record) and which users refused. */
    private final Scope scope;

    /** The users of every RegulatingControl of the network, by control identifier. Built on first use. */
    private Map<String, List<User>> usersByControlId;

    RegulatingControlFamily(Network network, CgmesExportContext context) {
        this(network, context, Scope.CHANGES);
    }

    RegulatingControlFamily(Network network, CgmesExportContext context, Scope scope) {
        this.network = network;
        this.context = context;
        this.scope = scope;
    }

    // The keys

    static Set<String> union(Set<String> first, Set<String> second) {
        return Stream.concat(first.stream(), second.stream()).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The canonical keys the given change repeats when it is an echo, an empty set when it is not one.
     *
     * @param identifiable the identifiable the change was reported on, {@code null} when it is not known: an echo of a
     *                     target is then not recognised
     */
    static Set<String> repeatedKeys(Identifiable<?> identifiable, String attribute) {
        Matcher matcher = attribute.startsWith("ratioTapChanger") ? RATIO_TAP_CHANGER_ATTRIBUTE.matcher(attribute) : null;
        if (matcher != null && matcher.matches()) {
            return repeated(RATIO_TAP_CHANGER_KEYS, matcher.group(2), matcher.group(1) + ".");
        }
        Set<String> keys = HOLDER_ECHOES.getOrDefault(attribute, Set.of());
        // A target echo repeats the local or the remote target; a boundary line reports a targetV of its own
        return keys.size() > 1 && (identifiable == null || identifiable instanceof BoundaryLine) ? Set.of() : keys;
    }

    private static Set<String> repeated(List<Key> keys, String echo, String prefix) {
        return keys.stream().filter(key -> key.echoes().contains(echo)).map(key -> prefix + key.canonical())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Whether the key of the given attribute depends on the kind of equipment it was reported on: a target echo. */
    static boolean needsIdentifiable(String attribute) {
        return HOLDER_ECHOES.getOrDefault(attribute, Set.of()).size() > 1;
    }

    /** Whether an echo may repeat a value reported under the given key: a key of a regulation. */
    static boolean isRepeatable(String attributeKey) {
        return attributeKey.equals(LOCAL_TARGET_V) || attributeKey.equals(LOCAL_TARGET_Q)
                || attributeKey.startsWith(VR_PREFIX) || attributeKey.contains("." + VR_PREFIX);
    }

    /** Whether the given change is an echo. */
    static boolean isEcho(Identifiable<?> identifiable, String attribute) {
        return !repeatedKeys(identifiable, attribute).isEmpty();
    }

    // The flag a block asks for

    /**
     * Whether a holder takes part in its control: the regulating flag of its voltage regulation, whatever the mode, as
     * the full export writes it since powsybl-core #3699 ({@code RegulatingCondEq.controlEnabled}). The receiving side
     * combines this flag with {@code RegulatingControl.enabled}.
     */
    static boolean flag(RegulationRef regulation, IidmStateView state) {
        return regulation.isRegulating(state);
    }

    /** Whether a tap changer regulates ({@code TapChanger.controlEnabled}): a ratio one through its VoltageRegulation. */
    static boolean tapChangerFlag(TapChangerRef ref, IidmStateView state) {
        return ref.tapChanger() instanceof RatioTapChanger
                ? flag(ref.regulation(), state)
                : ref.getBoolean(state, REGULATING_SUFFIX, ref.tapChanger()::isRegulating);
    }

    // The control of a change

    /**
     * The description of the RegulatingControl carrying the regulation of the given holder, holding the combined
     * state of every equipment regulating through it, or a failure if it has none the receiver knows.
     */
    Result<CgmesPropertyBuffer, String> updatesOf(Identifiable<?> holder, IidmStateView state) {
        // A full model names a control the import did not record under a generated identifier
        if (!holder.hasProperty(PROPERTY_REGULATING_CONTROL) && scope.honours(Refusal.NO_CONTROL)) {
            return failure(Refusal.NO_CONTROL.message(holder.getType() + " " + holder.getId()
                    + " has no CGMES regulating control the import could use: none in the equipment model, or one the"
                    + " import ignored (a mode other than voltage or reactive power, a regulating terminal it could"
                    + " not map, a control the model does not contain)."));
        }
        return updatesFor(context.getNamingStrategy().getCgmesIdFromProperty(holder, PROPERTY_REGULATING_CONTROL), state);
    }

    /**
     * The description of the TapChangerControl of a tap changer whose regulation changed (the suffix of the key below
     * the name of the tap changer), or why it cannot be described.
     */
    <C extends Connectable<C>> Result<CgmesPropertyBuffer, String> tapChangerUpdates(C transformer, String aliasType,
                                                                                     TapChangerRef ref, String suffix,
                                                                                     IidmStateView state) {
        if ("regulationMode".equals(suffix) || RegulationKeyRefusals.EQUIPMENT_KEYS.contains(suffix)) {
            return failure(RegulationKeyRefusals.equipmentOnly(suffix));
        }
        TapChanger<?, ?, ?, ?> tapChanger = ref.tapChanger();
        if (tapChanger instanceof RatioTapChanger) {
            RegulationRef regulation = ref.regulation();
            if (regulation.regulation() == null) {
                return failure(Refusal.IMPORT_GIVES_REGULATION.message("tap changer " + aliasType + " of "
                        + transformer.getId() + " has no voltage regulation the receiving side could read."));
            }
            if (regulation.mode(state) != RegulationMode.VOLTAGE) {
                return failure(Refusal.RTC_REACTIVE_POWER.message(
                        "the change export only writes the voltage regulation of ratio tap changers."));
            }
        } else if (tapChanger instanceof PhaseTapChanger phaseTapChanger && phaseTapChanger.getRegulationTerminal() == null) {
            return failure(Refusal.PTC_NO_TERMINAL.message("tap changer " + aliasType + " of " + transformer.getId()
                    + " regulates no terminal, so its regulation has no target the receiving side could read."));
        }
        return controlId(transformer, aliasType)
                .map(controlId -> updatesFor(controlId, state))
                .orElseGet(() -> failure(Refusal.NO_TAP_CHANGER_CONTROL.message("tap changer " + aliasType + " of "
                        + transformer.getId() + " has no CGMES tap changer control to carry this change.")));
    }

    /**
     * The properties describing the RegulatingControl with the given identifier, holding the combined state of every
     * equipment that regulates through it.
     *
     * @param regulatingControlId the CGMES master resource identifier of the control
     * @return the properties to write, or a failure describing why the control cannot be expressed
     */
    Result<CgmesPropertyBuffer, String> updatesFor(String regulatingControlId, IidmStateView state) {
        List<User> users = users().getOrDefault(regulatingControlId, List.of());
        if (users.isEmpty()) {
            return failure("no equipment of the network regulates through CGMES regulating control " + regulatingControlId);
        }
        if (scope.honours(Refusal.TAP_CHANGERS_DISAGREE) && tapChangersDisagree(users, state)) {
            return failure(Refusal.TAP_CHANGERS_DISAGREE.message("tap changers sharing CGMES tap changer control "
                    + regulatingControlId + " do not agree on whether they regulate, and the CGMES update gives them"
                    + " all the state of the shared control."));
        }

        List<RegulatingControlView> views = new ArrayList<>(users.size());
        for (User user : users) {
            switch (user.view().apply(state)) {
                case Result.Success(RegulatingControlView view) -> views.add(view);
                // One user the control cannot describe makes the whole description of a change wrong, not just its
                // own part; a full model writes the control from the users it can describe
                case Result.Failure(String reason) -> {
                    if (scope.honours(Refusal.UNDESCRIBED_USER)) {
                        return failure(reason);
                    }
                }
            }
        }
        // A control none of whose users can be described is left out of a full model
        CgmesPropertyBuffer buffer = new CgmesPropertyBuffer();
        if (!views.isEmpty()) {
            describe(views, buffer);
        }
        return success(buffer);
    }

    /**
     * Two tap changers sharing a TapChangerControl but disagreeing on whether they regulate cannot be described: the
     * CGMES update derives the state of a tap changer from {@code RegulatingControl.enabled} alone and ignores
     * {@code TapChanger.controlEnabled}, so both would come back with the same state on the receiving side.
     */
    private static boolean tapChangersDisagree(List<User> users, IidmStateView state) {
        return users.stream()
                .filter(User::isTapChanger)
                .map(user -> user.regulatesIn().test(state))
                .distinct()
                .count() > 1;
    }

    // Writing a control

    /** Describe a RegulatingControl or TapChangerControl from the views of all its users. */
    static void describe(List<RegulatingControlView> views, CgmesPropertySink out) {
        describeRegulatingControl(combine(views), out);
    }

    private static void describeRegulatingControl(RegulatingControlView view, CgmesPropertySink out) {
        out.startObject(view.type == RegulatingControlType.TAP_CHANGER_CONTROL ? "TapChangerControl" : "RegulatingControl", view.id)
                .value("RegulatingControl.discrete", view.discrete)
                .value("RegulatingControl.enabled", view.controlEnabled);
        if (CgmesExportUtil.targetDeadbandIsDefined(view.targetDeadband)) {
            out.value("RegulatingControl.targetDeadband", view.targetDeadband);
        }
        out.value("RegulatingControl.targetValue", view.targetValue)
                .enumValue("RegulatingControl.targetValueUnitMultiplier", "UnitMultiplier", view.targetValueUnitMultiplier)
                .endObject();
    }

    /**
     * Combine the descriptions that every user of the same RegulatingControl produces into the single description
     * the object gets: the target, the multiplier and the class of the first view.
     */
    static RegulatingControlView combine(List<RegulatingControlView> rcs) {
        RegulatingControlView combined = rcs.get(0);
        if (rcs.size() > 1 && LOG.isWarnEnabled()) {
            LOG.warn("Multiple views ({}) for regulating control {} are combined", rcs.size(), rcs.get(0).id);
        }
        for (int k = 1; k < rcs.size(); k++) {
            RegulatingControlView current = rcs.get(k);
            if (combinedTargetDeadbandMustBeUpdated(current.targetDeadband, combined.targetDeadband)) {
                combined.targetDeadband = current.targetDeadband;
            }
            if (!combined.discrete && current.discrete) {
                combined.discrete = true;
            }
            if (!combined.controlEnabled && current.controlEnabled) {
                combined.controlEnabled = true;
            }
        }
        return combined;
    }

    private static boolean combinedTargetDeadbandMustBeUpdated(double currentTargetDeadband, double combinedTargetDeadband) {
        return currentTargetDeadband == 0 && (Double.isNaN(combinedTargetDeadband) || combinedTargetDeadband < 0)
                || currentTargetDeadband > 0 && (combinedTargetDeadband == 0 || currentTargetDeadband < combinedTargetDeadband);
    }

    // The views

    /**
     * The RegulatingControl description of a voltage regulation holder, read from the given state of the network, or
     * {@code null} when the holder has no voltage regulation, or one without a mode in this variant.
     *
     * @param regulation the holder and the name a recorded change of its regulation carries
     */
    static RegulatingControlView regulatingControlView(RegulationRef regulation, String regulatingControlId,
                                                       CgmesExportContext context, IidmStateView state) {
        VoltageRegulationHolder<?> regulationHolder = regulation.holder();
        // A regulation without a mode in this variant (created while another variant was the working one) cannot say
        // what its control regulates: it describes no view, and the control is written from its other users, if any
        if (regulation.regulation() == null || regulation.mode(state) == null) {
            return null;
        }
        // Only discrete regulation holders can have a non-zero deadband
        boolean discrete = regulationHolder instanceof ShuntCompensator || regulationHolder instanceof RatioTapChanger;
        double targetDeadband = discrete ? regulation.targetDeadband(state) : 0.0;

        // VoltageRegulation Terminal can be left null to force the use of local target instead of the remote one,
        // thus targets are determined with VoltageRegulationHolder.getRegulatingTargetQ/V
        Quantity quantity;
        double targetValue;
        String mode = getRegulatingControlMode(regulation.mode(state));
        if (REGULATING_CONTROL_REACTIVE_POWER.equals(mode)) {
            // A machine states its target in the generator convention. The import multiplies the target by the sign of
            // the regulating terminal it recorded (AbstractReactiveLimitsOwnerConversion#updateRegulatingControlReactivePower,
            // StaticVarCompensatorConversion#updateRegulatingControl, and for a ratio tap changer the end it sits on,
            // AbstractTransformerConversion#updateRatioTapChanger), so the export applies it as well, unless the
            // equipment model is exported too
            quantity = regulationHolder instanceof Generator ? Quantity.MVAR_MACHINE_TARGET : Quantity.MVAR_TARGET;
            boolean signed = regulationHolder instanceof Generator || regulationHolder instanceof StaticVarCompensator
                    || regulationHolder instanceof RatioTapChanger;
            targetValue = quantity.encode(regulation.regulatingTargetQ(state),
                    signed ? CgmesExportUtil.exportedTerminalSign(regulation.owner(), regulation.end(), context) : 1);
        } else if (REGULATING_CONTROL_VOLTAGE.equals(mode)) {
            quantity = Quantity.KV_TARGET;
            targetValue = regulationHolder instanceof Generator && context.isExportGeneratorsInLocalRegulationMode()
                    ? regulation.localTargetV(state) : regulation.regulatingTargetV(state);
        } else {
            throw new IllegalStateException("Unexpected regulation mode: " + mode);
        }
        // RatioTapChanger VoltageRegulation is exported to a specialized class
        RegulatingControlType type = regulationHolder instanceof RatioTapChanger
                ? RegulatingControlType.TAP_CHANGER_CONTROL : RegulatingControlType.REGULATING_CONTROL;
        return new RegulatingControlView(regulatingControlId, type, discrete, flag(regulation, state), targetDeadband,
                targetValue, quantity.multiplier());
    }

    /**
     * The TapChangerControl description of a phase tap changer, read from the given state, or {@code null} when it has
     * none. A phase tap changer limiting current is described with the values it has (a current in Amperes, which
     * carries no sign, multiplier none) when the steady state hypothesis is read against the equipment model its
     * import recorded the control from; with an equipment model of its own the export writes the limit as a
     * CurrentLimit of the regulated terminal and the control keeps upstream's zeros.
     *
     * @param recordedControl whether the import recorded the TapChangerControl of this tap changer
     * @param ref             the tap changer and the name a recorded change of it carries
     */
    static RegulatingControlView phaseTapChangerView(PhaseTapChanger ptc, String controlId, boolean recordedControl,
                                                     TapChangerRef ref, CgmesExportContext context, IidmStateView state) {
        PhaseTapChanger.RegulationMode mode = ref.getEnum(state, REGULATION_MODE_SUFFIX,
                PhaseTapChanger.RegulationMode.class, ptc::getRegulationMode);
        boolean realLimiter = mode == PhaseTapChanger.RegulationMode.CURRENT_LIMITER && recordedControl && !context.isExportEquipment();
        if (!realLimiter && (!ptc.hasLoadTapChangingCapabilities() || mode == null)) {
            return null;
        }
        if (!realLimiter && mode == PhaseTapChanger.RegulationMode.CURRENT_LIMITER) {
            // Upstream's zeros, with the multiplier of a power target
            return new RegulatingControlView(controlId, RegulatingControlType.TAP_CHANGER_CONTROL, true, false, 0.0, 0.0,
                    Quantity.MW_TARGET.multiplier());
        }
        // The import multiplies an active power target by the sign of the regulating terminal it recorded
        // (AbstractTransformerConversion#updatePhaseTapChanger), so the export applies it as well, unless the
        // equipment model is exported too
        Quantity quantity = realLimiter ? Quantity.AMPERE_LIMITER : Quantity.MW_TARGET;
        return new RegulatingControlView(controlId, RegulatingControlType.TAP_CHANGER_CONTROL, true,
                tapChangerFlag(ref, state),
                ref.getDouble(state, TARGET_DEADBAND_SUFFIX, ptc::getTargetDeadband),
                quantity.encode(ref.getDouble(state, REGULATION_VALUE_SUFFIX, ptc::getRegulationValue),
                        CgmesExportUtil.exportedTerminalSign(ref.transformer(), ref.end(), context)),
                quantity.multiplier());
    }

    // The index

    private Map<String, List<User>> users() {
        if (usersByControlId == null) {
            usersByControlId = buildIndex();
        }
        return usersByControlId;
    }

    /**
     * Walk the regulating equipment of the network in the order the full export collects it: tap changers, then
     * generators, then shunt compensators, then static var compensators.
     *
     * <p>The order matters because {@link #combine} keeps the target, the multiplier and the class of the <em>first</em>
     * view. A control shared between a tap changer and another piece of equipment would otherwise be described
     * differently here than in a full export.</p>
     */
    private Map<String, List<User>> buildIndex() {
        Map<String, List<User>> index = new HashMap<>();
        for (TwoWindingsTransformer transformer : network.getTwoWindingsTransformers()) {
            indexTapChanger(index, transformer, "", transformer.getOptionalPhaseTapChanger().orElse(null),
                    CgmesExportUtil.tapChangerAliasType(transformer, ALIAS_PHASE_TAP_CHANGER1, ALIAS_PHASE_TAP_CHANGER2),
                    CgmesChangeTranslator.PHASE_TAP_CHANGER_PREFIX);
            indexTapChanger(index, transformer, "", transformer.getOptionalRatioTapChanger().orElse(null),
                    CgmesExportUtil.tapChangerAliasType(transformer, ALIAS_RATIO_TAP_CHANGER1, ALIAS_RATIO_TAP_CHANGER2),
                    CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX);
        }
        for (ThreeWindingsTransformer transformer : network.getThreeWindingsTransformers()) {
            for (ThreeWindingsTransformer.Leg leg : transformer.getLegs()) {
                String end = Integer.toString(leg.getSide().getNum());
                indexTapChanger(index, transformer, end, leg.getOptionalPhaseTapChanger().orElse(null),
                        CgmesExportUtil.getPhaseTapChangerAliasType(end),
                        CgmesChangeTranslator.PHASE_TAP_CHANGER_PREFIX + end);
                indexTapChanger(index, transformer, end, leg.getOptionalRatioTapChanger().orElse(null),
                        CgmesExportUtil.getRatioTapChangerAliasType(end),
                        CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX + end);
            }
        }
        for (Generator generator : network.getGenerators()) {
            indexHolder(index, generator, RegulationRef.of(generator));
        }
        for (ShuntCompensator shunt : network.getShuntCompensators()) {
            indexHolder(index, shunt, RegulationRef.of(shunt));
        }
        for (StaticVarCompensator svc : network.getStaticVarCompensators()) {
            indexHolder(index, svc, RegulationRef.of(svc));
        }
        return index;
    }

    private void indexHolder(Map<String, List<User>> index, Identifiable<?> holder, RegulationRef regulation) {
        regulatingControlId(holder).ifPresent(id -> add(index, id, new User(regulation::isRegulating, false,
                state -> holderView(regulation, id, state))));
    }

    private <C extends Connectable<C>> void indexTapChanger(Map<String, List<User>> index, C transformer, String end,
                                                            TapChanger<?, ?, ?, ?> tapChanger, String aliasType,
                                                            String attributePrefix) {
        if (tapChanger == null) {
            return;
        }
        TapChangerRef ref = new TapChangerRef(transformer, attributePrefix, tapChanger);
        boolean recorded = controlId(transformer, aliasType).isPresent();
        indexedControlId(transformer, aliasType, tapChanger, end).ifPresent(id -> add(index, id,
                new User(state -> tapChangerFlag(ref, state), true, state -> tapChangerView(transformer, ref, id, recorded, state))));
    }

    private static void add(Map<String, List<User>> index, String controlId, User user) {
        index.computeIfAbsent(controlId, id -> new ArrayList<>()).add(user);
    }

    /**
     * The control of a holder: the one the import recorded, or in a full model the one the full export names for a
     * holder that has a VoltageRegulation (an EquivalentInjection and an EquivalentShunt have none).
     */
    private Optional<String> regulatingControlId(Identifiable<?> identifiable) {
        boolean named = identifiable.hasProperty(PROPERTY_REGULATING_CONTROL)
                || scope == Scope.FULL_MODEL && identifiable instanceof VoltageRegulationHolder<?> holder
                    && holder.getVoltageRegulation() != null
                    && !CgmesNames.EQUIVALENT_INJECTION.equals(identifiable.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS))
                    && !Boolean.parseBoolean(identifiable.getProperty(PROPERTY_IS_EQUIVALENT_SHUNT));
        return named
                ? Optional.of(context.getNamingStrategy().getCgmesIdFromProperty(identifiable, PROPERTY_REGULATING_CONTROL))
                : Optional.empty();
    }

    /**
     * The TapChangerControl of a tap changer in the index: the one the import recorded, or in a full model the one the
     * full export names, under a generated identifier when the import recorded none.
     */
    private <C extends Connectable<C>> Optional<String> indexedControlId(C transformer, String aliasType,
                                                                          TapChanger<?, ?, ?, ?> tapChanger, String end) {
        if (scope == Scope.CHANGES) {
            return controlId(transformer, aliasType);
        }
        Part part = tapChanger instanceof PhaseTapChanger ? Part.PHASE_TAP_CHANGER : Part.RATIO_TAP_CHANGER;
        return Optional.of(CgmesExportUtil.getTapChangerControlId(transformer, part, end.isEmpty() ? 1 : Integer.parseInt(end),
                transformer.getAliasFromType(aliasType).orElse(null), context));
    }

    /**
     * The identifier of the TapChangerControl of the tap changer the given alias points at, taken from the CGMES tap
     * changer the import recorded. A control the export would have to generate an identifier for is left out: the
     * receiver of a partial file resolves identifiers against the model it already holds, and a generated one
     * resolves to nothing there.
     */
    <C extends Connectable<C>> Optional<String> controlId(C transformer, String aliasType) {
        return getCgmesTapChanger(transformer, transformer.getAliasFromType(aliasType).orElse(null))
                .map(CgmesTapChanger::getControlId)
                .map(controlId -> context.getNamingStrategy().getCgmesId(controlId));
    }

    /**
     * The view of a voltage regulation holder in a change: described exactly as the full export describes it but read
     * from the given state.
     *
     * <p>A regulation whose mode is undefined in this variant (it was created from another variant) has no CGMES
     * mode and is refused. For a generator the receiving side does not dispatch on the mode of the regulation but on
     * the CGMES mode its import recorded ({@code AbstractReactiveLimitsOwnerConversion#updateRegulatingControl}), so a
     * regulation whose mode was changed after the import is refused as well: its target would be read as the other
     * quantity.</p>
     */
    private Result<RegulatingControlView, String> holderView(RegulationRef regulation, String controlId, IidmStateView state) {
        Identifiable<?> owner = regulation.owner();
        if (regulation.regulation() == null) {
            return failure(RegulationKeyRefusals.noVoltageRegulation(owner, "RegulatingControl"));
        }
        RegulationMode mode = regulation.mode(state);
        if (mode == null) {
            return failure(Refusal.NO_MODE.message("the voltage regulation of " + owner.getType() + " "
                    + owner.getId() + " has no mode in this variant."));
        }
        if (regulation.holder() instanceof Generator generator && scope.honours(Refusal.CGMES_MODE)) {
            Optional<String> refusal = cgmesModeRefusal(generator, mode);
            if (refusal.isPresent()) {
                return failure(refusal.get());
            }
        }
        return success(regulatingControlView(regulation, controlId, context, state));
    }

    /**
     * The refusal of a generator whose regulation is in another mode than the CGMES mode its import recorded, empty
     * when they agree (or the mode is undefined): the CGMES update dispatches the regulation on the recorded mode on
     * every update of the machine, so a receiver would read the target as the other quantity (D14).
     */
    static Optional<String> cgmesModeRefusal(Generator generator, RegulationMode mode) {
        if (mode == null || agreesWithCgmesMode(generator, mode)) {
            return Optional.empty();
        }
        return Optional.of(Refusal.CGMES_MODE.message("the voltage regulation of generator " + generator.getId()
                + " is in mode " + mode + ", but the CGMES update reads its RegulatingControl in the mode "
                + generator.getProperty(PROPERTY_MODE) + " recorded at import."));
    }

    private static boolean agreesWithCgmesMode(Generator generator, RegulationMode mode) {
        String cgmesMode = generator.getProperty(PROPERTY_MODE);
        if (cgmesMode == null) {
            // Not imported from CGMES: nothing on the receiving side to disagree with
            return true;
        }
        return mode == RegulationMode.REACTIVE_POWER
                ? RegulatingControlMapping.isControlModeReactivePower(cgmesMode)
                : RegulatingControlMapping.isControlModeVoltage(cgmesMode);
    }

    /**
     * The view of a tap changer. A phase tap changer limiting a current is described with the values it really has,
     * where the full export writes zeros: a partial file is applied on top of a state the receiver already holds, so
     * writing zeros would reset a regulation that did not change.
     */
    private Result<RegulatingControlView, String> tapChangerView(Connectable<?> transformer, TapChangerRef ref,
                                                                 String controlId, boolean recordedControl,
                                                                 IidmStateView state) {
        TapChanger<?, ?, ?, ?> tapChanger = ref.tapChanger();
        if (tapChanger instanceof RatioTapChanger) {
            // The same guards as any other holder: no regulation, or a regulation without a mode in this variant
            return holderView(ref.regulation(), controlId, state);
        }
        RegulatingControlView view = phaseTapChangerView((PhaseTapChanger) tapChanger, controlId, recordedControl, ref,
                context, state);
        if (view == null) {
            return failure("tap changer " + controlId + " of " + transformer.getId()
                    + " has no regulation the steady state hypothesis profile can express");
        }
        return success(view);
    }

    enum RegulatingControlType {
        REGULATING_CONTROL, TAP_CHANGER_CONTROL
    }

    /** What one user says about its control, before the views of all users are combined. */
    static class RegulatingControlView {
        String id;
        RegulatingControlType type;
        boolean discrete;
        boolean controlEnabled;
        double targetDeadband;
        double targetValue;
        String targetValueUnitMultiplier;

        RegulatingControlView(String id, RegulatingControlType type, boolean discrete, boolean controlEnabled,
                              double targetDeadband, double targetValue, String targetValueUnitMultiplier) {
            this.id = id;
            this.type = type;
            this.discrete = discrete;
            this.controlEnabled = controlEnabled;
            this.targetDeadband = targetDeadband;
            this.targetValue = targetValue;
            this.targetValueUnitMultiplier = targetValueUnitMultiplier;
        }
    }

    /**
     * One equipment regulating through a control, kept as a supplier so that the index costs one pass over the
     * network and nothing more: the view itself is only built for the controls an export actually writes.
     *
     * @param regulatesIn  whether this equipment regulates in a given state of the network. Only read for tap changers,
     *                     whose state the CGMES update cannot hold separately
     * @param isTapChanger whether this user is a tap changer, whose state the CGMES update cannot hold separately
     * @param view         the view of the control this user describes, in a given state of the network
     */
    private record User(Predicate<IidmStateView> regulatesIn, boolean isTapChanger,
                        Function<IidmStateView, Result<RegulatingControlView, String>> view) {
    }
}
