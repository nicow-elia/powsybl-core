/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.mapping.Block;
import com.powsybl.cgmes.extensions.CgmesTapChanger;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.TapChanger;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.powsybl.cgmes.conversion.Conversion.ALIAS_PHASE_TAP_CHANGER1;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_PHASE_TAP_CHANGER2;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_RATIO_TAP_CHANGER1;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_RATIO_TAP_CHANGER2;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_IS_EQUIVALENT_SHUNT;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOCAL_TARGET_Q;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOCAL_TARGET_V;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.PHASE_TAP_CHANGER_PREFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REGULATING_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REGULATION_VALUE_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.SECTION_COUNT;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TAP_POSITION_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_DEADBAND_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_REGULATING;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TARGET_DEADBAND;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TARGET_VALUE;
import static com.powsybl.cgmes.conversion.export.CgmesPropertyBuffer.merge;
import static com.powsybl.commons.util.Result.failure;
import static com.powsybl.commons.util.Result.success;

/**
 * The tap changers, shunt compensators and static var compensators in every export of the steady state hypothesis:
 * the block of a tap changer (its step and control flag, a hidden tap changer of the import included), of a shunt
 * compensator (its sections and control flag) and of a static var compensator (its reactive power and control flag),
 * each of which the CGMES update reads as a whole, and the control of a change of their regulation, which is
 * {@link RegulatingControlFamily}.
 *
 * <p>The keys a change is reported under and the blocks the CGMES update reads are declared here; the dispatch of the
 * change export, the probes and the capabilities of the in-place import are derived from them.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class TapChangerAndShuntFamily extends AbstractFamily {

    private static final String REGULATING_COND_EQ_CONTROL_ENABLED = "RegulatingCondEq.controlEnabled";
    private static final String TAP_POSITION = "tapPosition";
    private static final String TAP_CHANGER_STEP = "TapChanger.step";
    private static final String TAP_CHANGER_CONTROL_ENABLED = "TapChanger.controlEnabled";

    /**
     * The keys of a shunt compensator (its section count, the targets and the flag of its control, and the deadband, which
     * the CGMES update reads for shunt compensators and tap changers only) and of a static var compensator (the local
     * reactive power target too: the single target of its control is the one of its mode), in the order the in-place
     * import probes them. The dispatch of the change export reads the same keys.
     */
    public static final List<String> SHUNT_PROBES = List.of(SECTION_COUNT, LOCAL_TARGET_V, VR_TARGET_VALUE, VR_REGULATING,
            VR_TARGET_DEADBAND);
    public static final List<String> STATIC_VAR_COMPENSATOR_PROBES = List.of(LOCAL_TARGET_Q, LOCAL_TARGET_V, VR_TARGET_VALUE,
            VR_REGULATING);
    static final Set<String> SHUNT_KEYS = Set.copyOf(SHUNT_PROBES);
    static final Set<String> STATIC_VAR_COMPENSATOR_KEYS = Set.copyOf(STATIC_VAR_COMPENSATOR_PROBES);
    /** The suffixes of a phase tap changer, which are prefixed by the name a recorded change gives it. */
    private static final List<String> PHASE_TAP_CHANGER_SUFFIXES = List.of(TAP_POSITION_SUFFIX, REGULATING_SUFFIX,
            REGULATION_VALUE_SUFFIX, TARGET_DEADBAND_SUFFIX);
    /** The suffixes of a ratio tap changer, which regulates through its VoltageRegulation. */
    private static final List<String> RATIO_TAP_CHANGER_SUFFIXES = List.of(TAP_POSITION_SUFFIX, "." + VR_REGULATING,
            "." + VR_TARGET_VALUE, "." + VR_TARGET_DEADBAND);

    // The blocks the CGMES update reads as a whole: the control flag with the reactive power, the sections or the step
    public static final Block STATIC_VAR_COMPENSATOR = new Block("staticVarCompensators",
            List.of("StaticVarCompensator"), "StaticVarCompensator.q", REGULATING_COND_EQ_CONTROL_ENABLED);
    public static final Block SHUNT_COMPENSATOR = new Block("shuntCompensators",
            List.of("LinearShuntCompensator", "NonlinearShuntCompensator"), "ShuntCompensator.sections",
            REGULATING_COND_EQ_CONTROL_ENABLED);
    public static final Block RATIO_TAP_CHANGER = new Block("ratioTapChangers", List.of(CgmesNames.RATIO_TAP_CHANGER),
            TAP_CHANGER_STEP, TAP_CHANGER_CONTROL_ENABLED);
    public static final Block PHASE_TAP_CHANGER = new Block("phaseTapChangers", List.of("PhaseTapChangerLinear",
            "PhaseTapChangerAsymmetrical", "PhaseTapChangerSymmetrical", "PhaseTapChangerNonLinear",
            CgmesNames.PHASE_TAP_CHANGER_TABULAR), TAP_CHANGER_STEP, TAP_CHANGER_CONTROL_ENABLED);

    private final RegulatingControlFamily controls;

    TapChangerAndShuntFamily(CgmesExportContext context, IidmStateView state, Scope scope, RegulatingControlFamily controls) {
        super(context, state, scope);
        this.controls = controls;
    }

    /** The probes of the tap changer the given prefix names ({@code ratioTapChanger2}, {@code phaseTapChanger}). */
    public static List<String> tapChangerProbes(String prefix) {
        return (prefix.startsWith(RATIO_TAP_CHANGER_PREFIX) ? RATIO_TAP_CHANGER_SUFFIXES : PHASE_TAP_CHANGER_SUFFIXES)
                .stream().map(suffix -> prefix + suffix).toList();
    }

    /** Every tap changer probe of a transformer with the given ends ({@code ""} for two windings), ratio first. */
    public static List<String> transformerProbes(String... ends) {
        List<String> probes = new ArrayList<>();
        for (String end : ends) {
            probes.addAll(tapChangerProbes(RATIO_TAP_CHANGER_PREFIX + end));
            probes.addAll(tapChangerProbes(PHASE_TAP_CHANGER_PREFIX + end));
        }
        return probes;
    }

    /**
     * The tap changer an attribute name points at, and what of it changed.
     *
     * @param phase  whether the attribute names a phase tap changer rather than a ratio one
     * @param end    the end the tap changer sits on, {@code ""} for a two windings transformer
     * @param suffix the changed property, without its leading dot
     */
    record TapChangerAttribute(boolean phase, String end, String suffix) {
    }

    /**
     * The attributes of a tap changer this exporter maps. Everything else it may report, such as a solved position
     * or the regulation terminal of a phase tap changer, has no counterpart in the steady state hypothesis profile.
     * A ratio tap changer regulates through its VoltageRegulation, whose attributes carry a dotted suffix of their
     * own; the mode, the terminal and the slope are matched so that they can be refused with a reason.
     */
    private static final Pattern TAP_CHANGER_ATTRIBUTE = Pattern.compile(
            "^(?:(ratio)TapChanger([123]?)\\.(tapPosition|VoltageRegulation\\.(?:TargetValue|TargetDeadband|isRegulating|RegulationMode|Terminal|Slope))"
                    + "|(phase)TapChanger([123]?)\\.(tapPosition|regulating|regulationValue|targetDeadband|regulationMode))$");

    static TapChangerAttribute tapChangerAttribute(String attribute) {
        Matcher matcher = TAP_CHANGER_ATTRIBUTE.matcher(attribute);
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1) != null
                ? new TapChangerAttribute(false, matcher.group(2), matcher.group(3))
                : new TapChangerAttribute(true, matcher.group(5), matcher.group(6));
    }

    Result<CgmesPropertyBuffer, String> twoWindingsTapChangerUpdates(TwoWindingsTransformer transformer,
                                                                           TapChangerAttribute attribute) {
        if (!attribute.end().isEmpty()) {
            return noTapChangerMatching(transformer, attribute);
        }
        if (attribute.phase() && transformer.hasPhaseTapChanger()) {
            return tapChangerUpdates(transformer, CgmesExportUtil.tapChangerAliasType(transformer, ALIAS_PHASE_TAP_CHANGER1, ALIAS_PHASE_TAP_CHANGER2),
                    CgmesNames.PHASE_TAP_CHANGER_TABULAR, tapChangerRef(transformer, attribute, transformer.getPhaseTapChanger()), attribute);
        }
        if (!attribute.phase() && transformer.hasRatioTapChanger()) {
            return tapChangerUpdates(transformer, CgmesExportUtil.tapChangerAliasType(transformer, ALIAS_RATIO_TAP_CHANGER1, ALIAS_RATIO_TAP_CHANGER2),
                    CgmesNames.RATIO_TAP_CHANGER, tapChangerRef(transformer, attribute, transformer.getRatioTapChanger()), attribute);
        }
        return noTapChangerMatching(transformer, attribute);
    }

    Result<CgmesPropertyBuffer, String> threeWindingsTapChangerUpdates(ThreeWindingsTransformer transformer,
                                                                             TapChangerAttribute attribute) {
        ThreeWindingsTransformer.Leg leg = leg(transformer, attribute.end());
        if (leg == null || (attribute.phase() ? !leg.hasPhaseTapChanger() : !leg.hasRatioTapChanger())) {
            return noTapChangerMatching(transformer, attribute);
        }
        return attribute.phase()
                ? tapChangerUpdates(transformer, CgmesExportUtil.getPhaseTapChangerAliasType(attribute.end()),
                        CgmesNames.PHASE_TAP_CHANGER_TABULAR, tapChangerRef(transformer, attribute, leg.getPhaseTapChanger()), attribute)
                : tapChangerUpdates(transformer, CgmesExportUtil.getRatioTapChangerAliasType(attribute.end()),
                        CgmesNames.RATIO_TAP_CHANGER, tapChangerRef(transformer, attribute, leg.getRatioTapChanger()), attribute);
    }

    private static Result<CgmesPropertyBuffer, String> noTapChangerMatching(Identifiable<?> transformer, TapChangerAttribute attribute) {
        return failure(transformer.getType() + " " + transformer.getId() + " has no "
                + (attribute.phase() ? "phase" : "ratio") + " tap changer on end '" + attribute.end() + "'");
    }

    static ThreeWindingsTransformer.Leg leg(ThreeWindingsTransformer transformer, String end) {
        return switch (end) {
            case "1" -> transformer.getLeg1();
            case "2" -> transformer.getLeg2();
            case "3" -> transformer.getLeg3();
            default -> null;
        };
    }

    /** The name a recorded change gives the given tap changer, which is how its previous values are looked up. */
    private static TapChangerRef tapChangerRef(Identifiable<?> transformer, TapChangerAttribute attribute,
                                               TapChanger<?, ?, ?, ?> tapChanger) {
        return new TapChangerRef(transformer,
                (attribute.phase() ? PHASE_TAP_CHANGER_PREFIX : RATIO_TAP_CHANGER_PREFIX) + attribute.end(), tapChanger);
    }

    /**
     * The properties describing a change of the given tap changer: its own block, which the CGMES update reads as a
     * whole, and the TapChangerControl carrying its regulation when the regulation is what changed.
     */
    private <C extends Connectable<C>> Result<CgmesPropertyBuffer, String> tapChangerUpdates(
            C transformer, String aliasType, String defaultClassName,
            TapChangerRef ref, TapChangerAttribute attribute) {
        CgmesPropertyBuffer tapChangerBlock = collect(out -> describeTapChanger(transformer, aliasType, defaultClassName, ref, out));
        if (TAP_POSITION.equals(attribute.suffix())) {
            return success(tapChangerBlock);
        }
        return controls.tapChangerUpdates(transformer, aliasType, ref, attribute.suffix(), state)
                .map(regulatingControl -> merge(tapChangerBlock, regulatingControl));
    }

    /** Describe the block of one tap changer: its step and its control flag. */
    <C extends Connectable<C>> void describeTapChanger(C transformer, String aliasType, String defaultClassName,
                                                       TapChangerRef ref, CgmesPropertySink out) {
        TapChanger<?, ?, ?, ?> tapChanger = ref.tapChanger();
        String className = defaultClassName;
        if (tapChanger instanceof PhaseTapChanger && !context.isExportEquipment()) {
            className = CgmesExportUtil.getPhaseTapChangerType(transformer, transformer.getAliasFromType(aliasType).orElse(null));
        }
        tapChangerBlock(out, className, cgmesIdFromAlias(transformer, aliasType), RegulatingControlFamily.tapChangerFlag(ref, state),
                ref.getInt(state, TAP_POSITION_SUFFIX, tapChanger::getTapPosition));
    }

    /**
     * Describe the tap changer the import combined into the only one IIDM kept, with the step it recorded: an export
     * of the steady state hypothesis without the equipment model still has to write it.
     */
    static void describeHiddenTapChanger(CgmesTapChanger hiddenTapChanger, String defaultClassName, CgmesPropertySink out) {
        tapChangerBlock(out, Optional.ofNullable(hiddenTapChanger.getType()).orElse(defaultClassName), hiddenTapChanger.getId(),
                false, hiddenTapChanger.getStep().orElseThrow(
                        () -> new PowsyblException("Non null step expected for tap changer " + hiddenTapChanger.getId())));
    }

    private static void tapChangerBlock(CgmesPropertySink out, String className, String id, boolean controlEnabled, int step) {
        out.startObject(className, id)
                .value(TAP_CHANGER_CONTROL_ENABLED, controlEnabled)
                .value(TAP_CHANGER_STEP, step)
                .endObject();
    }

    Result<CgmesPropertyBuffer, String> shuntCompensatorUpdates(ShuntCompensator shunt, String attribute) {
        // The CGMES update reads the section count and the control flag of a shunt as one block, so every change
        // of either writes both. Only a change of the regulation itself also describes the RegulatingControl.
        Optional<String> refusal = Boolean.parseBoolean(shunt.getProperty(PROPERTY_IS_EQUIVALENT_SHUNT))
                ? Optional.of(Refusal.EQUIVALENT_SHUNT.message("shunt compensator " + shunt.getId()
                    + " is exported as an EquivalentShunt, which has no steady state properties."))
                : RegulationKeyRefusals.importGivesRegulation(shunt, scope);
        return unlessRefused(refusal, out -> describeShunt(shunt, out)).flatMap(shuntBlock -> switch (attribute) {
            case SECTION_COUNT -> success(shuntBlock);
            default -> controls.updatesOf(shunt, state).map(regulatingControl -> merge(shuntBlock, regulatingControl));
        });
    }

    private static String shuntClassName(ShuntCompensator shunt) {
        return switch (shunt.getModelType()) {
            case LINEAR -> "LinearShuntCompensator";
            case NON_LINEAR -> "NonlinearShuntCompensator";
        };
    }

    Result<CgmesPropertyBuffer, String> staticVarCompensatorUpdates(StaticVarCompensator svc) {
        // The CGMES update reads the reactive power and the control flag of a compensator as one block, and the
        // single target of its RegulatingControl is the one matching the current mode, so a change of the target
        // of the other mode is not observable in the SSH profile. StaticVarCompensator.q is the local reactive power
        // target in both directions since powsybl-core #3699.
        return unlessRefused(RegulationKeyRefusals.importGivesRegulation(svc, scope), out -> describeStaticVarCompensator(svc, out))
                .flatMap(svcBlock -> controls.updatesOf(svc, state)
                .map(regulatingControl -> merge(svcBlock, regulatingControl)));
    }

    /**
     * Describe the section count and the control flag of a shunt compensator that is not an EquivalentShunt, the block
     * the CGMES update reads as a whole.
     */
    void describeShunt(ShuntCompensator shunt, CgmesPropertySink out) {
        out.startObject(shuntClassName(shunt), cgmesId(shunt))
                .value("ShuntCompensator.sections", state.getInt(shunt, SECTION_COUNT, shunt::getSectionCount))
                .value(REGULATING_COND_EQ_CONTROL_ENABLED, RegulatingControlFamily.flag(RegulationRef.of(shunt), state))
                .endObject();
    }

    /**
     * Describe the control flag and the reactive power of a static var compensator, the block the CGMES update reads
     * as a whole. StaticVarCompensator.q is the local reactive power target in both directions since powsybl-core #3699.
     */
    void describeStaticVarCompensator(StaticVarCompensator svc, CgmesPropertySink out) {
        RegulationRef regulation = RegulationRef.of(svc);
        out.startObject("StaticVarCompensator", cgmesId(svc))
                .value(REGULATING_COND_EQ_CONTROL_ENABLED, RegulatingControlFamily.flag(regulation, state))
                .value("StaticVarCompensator.q", regulation.localTargetQ(state))
                .endObject();
    }
}
