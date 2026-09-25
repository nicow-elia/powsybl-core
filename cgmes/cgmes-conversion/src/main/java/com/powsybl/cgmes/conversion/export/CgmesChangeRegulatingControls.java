/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.RegulatingControlMapping;
import com.powsybl.cgmes.conversion.export.SteadyStateHypothesisExport.RegulatingControlType;
import com.powsybl.cgmes.conversion.export.SteadyStateHypothesisExport.RegulatingControlView;
import com.powsybl.cgmes.extensions.CgmesTapChanger;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.TapChanger;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.extensions.RemoteReactivePowerControl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static com.powsybl.cgmes.conversion.Conversion.ALIAS_PHASE_TAP_CHANGER1;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_PHASE_TAP_CHANGER2;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_RATIO_TAP_CHANGER1;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_RATIO_TAP_CHANGER2;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_MODE;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_REGULATING_CONTROL;
import static com.powsybl.cgmes.conversion.elements.transformers.AbstractTransformerConversion.getCgmesTapChanger;
import static com.powsybl.cgmes.conversion.export.CgmesPropertyBuffer.newUpdates;
import static com.powsybl.commons.util.Result.failure;
import static com.powsybl.commons.util.Result.success;

/**
 * Describes the CGMES RegulatingControl and TapChangerControl objects of a change export, shared by the partial SSH
 * and the difference model export.
 *
 * <p>A RegulatingControl is shared: a generator, a shunt compensator and a tap changer can all point at the same
 * one, and CGMES then carries a single enabled flag, a single target and a single deadband for all of them. Writing
 * the view of the one piece of equipment that happened to change would therefore switch the regulation of its
 * neighbours off. Every description this class produces is the combination of the views of <em>all</em> the users of
 * the control, exactly as {@link SteadyStateHypothesisExport} combines them for a full export, so that a receiver
 * reading a partial file and a receiver reading a full one end up in the same state.</p>
 *
 * <p>Finding the users means walking the regulating equipment of the network, which is why the index is built
 * lazily, on the first control that is actually needed, and then kept: an export that changes no regulation never
 * pays for it, and one that changes a hundred of them pays for it once. The index holds structure only &mdash; which
 * equipment regulates through which control &mdash; and every value is read when a description is asked for, from
 * the {@link IidmStateView} of that pass, so a difference model export shares one index between both directions.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesChangeRegulatingControls {

    private final Network network;
    private final CgmesExportContext context;

    /** The users of every RegulatingControl of the network, by control identifier. Built on first use. */
    private Map<String, List<User>> usersByControlId;

    CgmesChangeRegulatingControls(Network network, CgmesExportContext context) {
        this.network = network;
        this.context = context;
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
        Optional<String> conflict = tapChangerDisagreement(regulatingControlId, users, state);
        if (conflict.isPresent()) {
            return failure(conflict.get());
        }

        List<RegulatingControlView> views = new ArrayList<>(users.size());
        for (User user : users) {
            switch (user.view(state)) {
                case Result.Success(RegulatingControlView view) -> views.add(view);
                // One user the control cannot describe makes the whole description wrong, not just its own part
                case Result.Failure(String reason) -> {
                    return failure(reason);
                }
            }
        }
        return success(write(SteadyStateHypothesisExport.combineRegulatingControlViews(views)));
    }

    /**
     * Two tap changers sharing a TapChangerControl but disagreeing on whether they regulate cannot be described: the
     * CGMES update derives the state of a tap changer from {@code RegulatingControl.enabled} alone and ignores
     * {@code TapChanger.controlEnabled}, so both would come back with the same state on the receiving side.
     */
    private static Optional<String> tapChangerDisagreement(String regulatingControlId, List<User> users, IidmStateView state) {
        boolean disagree = users.stream()
                .filter(User::isTapChanger)
                .map(user -> user.regulates(state))
                .distinct()
                .count() > 1;
        return disagree
                ? Optional.of("tap changers sharing CGMES tap changer control " + regulatingControlId
                        + " do not agree on whether they regulate, and the CGMES update gives them all the state of"
                        + " the shared control")
                : Optional.empty();
    }

    private CgmesPropertyBuffer write(RegulatingControlView view) {
        CgmesPropertyBuffer.ObjectUpdate update =
                newUpdates(SteadyStateHypothesisExport.regulatingControlClassname(view.type), view.id)
                        .value("RegulatingControl.discrete", view.discrete)
                        .value("RegulatingControl.enabled", view.controlEnabled);
        if (CgmesExportUtil.targetDeadbandIsDefined(view.targetDeadband)) {
            update.value("RegulatingControl.targetDeadband", view.targetDeadband);
        }
        return update.value("RegulatingControl.targetValue", view.targetValue)
                .enumValue("RegulatingControl.targetValueUnitMultiplier", "UnitMultiplier", view.targetValueUnitMultiplier)
                .updates();
    }

    // The index

    private Map<String, List<User>> users() {
        if (usersByControlId == null) {
            usersByControlId = buildIndex();
        }
        return usersByControlId;
    }

    /**
     * Walk the regulating equipment of the network in the order {@link SteadyStateHypothesisExport#write} collects
     * it: tap changers, then generators, then shunt compensators, then static var compensators.
     *
     * <p>The order matters because {@code combineRegulatingControlViews} keeps the target, the multiplier and the
     * class of the <em>first</em> view. A control shared between a tap changer and another piece of equipment would
     * otherwise be described differently here than in a full export.</p>
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
            regulatingControlId(generator).ifPresent(id -> add(index, id, new User(
                    state -> state.getBoolean(generator, CgmesChangeTranslator.VOLTAGE_REGULATOR_ON,
                            generator::isVoltageRegulatorOn),
                    false, state -> generatorView(generator, id, state))));
        }
        for (ShuntCompensator shunt : network.getShuntCompensators()) {
            regulatingControlId(shunt).ifPresent(id -> add(index, id, new User(
                    state -> state.getBoolean(shunt, CgmesChangeTranslator.VOLTAGE_REGULATOR_ON,
                            shunt::isVoltageRegulatorOn),
                    false,
                    state -> viewOrFailure(SteadyStateHypothesisExport.regulatingControlView(shunt, context, state), shunt))));
        }
        for (StaticVarCompensator svc : network.getStaticVarCompensators()) {
            regulatingControlId(svc).ifPresent(id -> add(index, id, new User(
                    state -> state.getBoolean(svc, CgmesChangeTranslator.REGULATING, svc::isRegulating),
                    false,
                    state -> viewOrFailure(SteadyStateHypothesisExport.regulatingControlView(svc, context, state), svc))));
        }
        return index;
    }

    private <C extends Connectable<C>> void indexTapChanger(Map<String, List<User>> index, C transformer, String end,
                                                            TapChanger<?, ?, ?, ?> tapChanger, String aliasType,
                                                            String attributePrefix) {
        if (tapChanger == null) {
            return;
        }
        TapChangerRef ref = new TapChangerRef(transformer, attributePrefix, tapChanger);
        controlId(transformer, aliasType).ifPresent(id -> add(index, id, new User(
                state -> ref.getBoolean(state, CgmesChangeTranslator.REGULATING_SUFFIX, tapChanger::isRegulating),
                true, state -> tapChangerView(transformer, end, ref, id, state))));
    }

    private static void add(Map<String, List<User>> index, String controlId, User user) {
        index.computeIfAbsent(controlId, id -> new ArrayList<>()).add(user);
    }

    private Optional<String> regulatingControlId(Identifiable<?> identifiable) {
        return identifiable.hasProperty(PROPERTY_REGULATING_CONTROL)
                ? Optional.of(context.getNamingStrategy().getCgmesIdFromProperty(identifiable, PROPERTY_REGULATING_CONTROL))
                : Optional.empty();
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

    // The views

    /**
     * The view of a generator, built here rather than taken from the full export because the receiving side picks
     * the meaning of the target from the CGMES mode the import recorded, not from the state the generator is in.
     */
    private Result<RegulatingControlView, String> generatorView(Generator generator, String controlId, IidmStateView state) {
        String mode = generator.getProperty(PROPERTY_MODE);
        if (RegulatingControlMapping.isControlModeVoltage(mode)) {
            return success(new RegulatingControlView(controlId, RegulatingControlType.REGULATING_CONTROL, false,
                    state.getBoolean(generator, CgmesChangeTranslator.VOLTAGE_REGULATOR_ON, generator::isVoltageRegulatorOn),
                    0.0, SteadyStateHypothesisExport.generatorTargetV(generator, context, state), "k"));
        }
        if (RegulatingControlMapping.isControlModeReactivePower(mode)) {
            RemoteReactivePowerControl reactivePowerControl = generator.getExtension(RemoteReactivePowerControl.class);
            if (reactivePowerControl == null) {
                return failure("generator " + generator.getId() + " regulates reactive power in CGMES but has no"
                        + " remote reactive power control the target could be read from");
            }
            state.requireExtensionNotCreated(generator, RemoteReactivePowerControl.NAME);
            // The import negates the target of a regulating terminal oriented the other way
            double target = CgmesExportUtil.terminalSign(generator, "")
                    * state.getExtensionDouble(generator, RemoteReactivePowerControl.NAME,
                            CgmesChangeTranslator.RRPC_TARGET_Q, reactivePowerControl::getTargetQ);
            return success(new RegulatingControlView(controlId, RegulatingControlType.REGULATING_CONTROL, false,
                    state.getExtensionBoolean(generator, RemoteReactivePowerControl.NAME,
                            CgmesChangeTranslator.RRPC_ENABLED, reactivePowerControl::isEnabled),
                    0.0, target, "M"));
        }
        return failure("generator " + generator.getId() + " has no CGMES regulating control mode the update can read");
    }

    /**
     * The view of a tap changer. A phase tap changer limiting a current is described with the values it really has,
     * where the full export writes zeros: a partial file is applied on top of a state the receiver already holds, so
     * writing zeros would reset a regulation that did not change.
     */
    private Result<RegulatingControlView, String> tapChangerView(Connectable<?> transformer, String end,
                                                                 TapChangerRef ref, String controlId, IidmStateView state) {
        TapChanger<?, ?, ?, ?> tapChanger = ref.tapChanger();
        if (tapChanger instanceof PhaseTapChanger phaseTapChanger
                && ref.getEnum(state, CgmesChangeTranslator.REGULATION_MODE_SUFFIX,
                        PhaseTapChanger.RegulationMode.class, phaseTapChanger::getRegulationMode)
                        == PhaseTapChanger.RegulationMode.CURRENT_LIMITER) {
            return success(currentLimiterView(phaseTapChanger, controlId, ref, state));
        }
        RegulatingControlView view = SteadyStateHypothesisExport.regulatingControlView(transformer, end, controlId, ref, state);
        if (view == null) {
            return failure("tap changer " + controlId + " of " + transformer.getId()
                    + " has no regulation the steady state hypothesis profile can express");
        }
        return success(view);
    }

    private static RegulatingControlView currentLimiterView(PhaseTapChanger phaseTapChanger, String controlId,
                                                           TapChangerRef ref, IidmStateView state) {
        // Unit multiplier is none (multiply by 1), the regulation value is a current in Amperes and carries no sign
        return new RegulatingControlView(controlId, RegulatingControlType.TAP_CHANGER_CONTROL, true,
                ref.getBoolean(state, CgmesChangeTranslator.REGULATING_SUFFIX, phaseTapChanger::isRegulating),
                ref.getDouble(state, CgmesChangeTranslator.TARGET_DEADBAND_SUFFIX, phaseTapChanger::getTargetDeadband),
                ref.getDouble(state, CgmesChangeTranslator.REGULATION_VALUE_SUFFIX, phaseTapChanger::getRegulationValue),
                "none");
    }

    private static Result<RegulatingControlView, String> viewOrFailure(RegulatingControlView view, Identifiable<?> user) {
        return view != null ? success(view)
                : failure(user.getType() + " " + user.getId() + " has no regulation the steady state hypothesis"
                        + " profile can express");
    }

    /**
     * One equipment regulating through a control, kept as a supplier so that the index costs one pass over the
     * network and nothing more: the view itself is only built for the controls an export actually writes.
     *
     * @param regulatesIn  whether this equipment regulates in a given state of the network. Only read for tap changers, whose state the
     *                     CGMES update cannot hold separately, so it is the plain IIDM flag and not the mode aware
     *                     one that {@code CgmesChangeTranslator#generatorControlEnabled} computes for the
     *                     {@code RegulatingCondEq.controlEnabled} of a generator
     * @param isTapChanger whether this user is a tap changer, whose state the CGMES update cannot hold separately
     */
    private record User(Predicate<IidmStateView> regulatesIn, boolean isTapChanger, ViewSupplier viewSupplier) {
        Result<RegulatingControlView, String> view(IidmStateView state) {
            return viewSupplier.get(state);
        }

        boolean regulates(IidmStateView state) {
            return regulatesIn.test(state);
        }
    }

    @FunctionalInterface
    private interface ViewSupplier {
        Result<RegulatingControlView, String> get(IidmStateView state);
    }
}
