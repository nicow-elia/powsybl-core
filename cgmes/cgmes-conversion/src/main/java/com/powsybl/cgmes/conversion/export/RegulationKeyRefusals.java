/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.export.AbstractFamily.Scope;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulation;
import com.powsybl.iidm.network.regulation.VoltageRegulationHolder;

import java.util.Optional;
import java.util.Set;

import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_REGULATING_CONTROL;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOCAL_TARGET_V;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_MODE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_SLOPE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TARGET_DEADBAND;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TERMINAL;

/**
 * The refusals of a change of a voltage regulation that depend on the change itself, not only on the state it leaves:
 * which key changed, or what it was before. Asked by the change export before it describes anything; a full model has
 * no change and asks none of them, except {@link #importGivesRegulation}, which it does not honour.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class RegulationKeyRefusals {

    /**
     * What of a voltage regulation no steady state hypothesis file can change: the mode and the regulating terminal
     * of a RegulatingControl are equipment data, and CGMES has no slope on a RegulatingControl at all.
     */
    static final Set<String> EQUIPMENT_KEYS = Set.of(VR_MODE, VR_TERMINAL, VR_SLOPE);

    /** The refusal of an echo that is the sole carrier of a change, see {@link EventCompactor}. */
    static final String SOLE_ECHO = Refusal.ECHO_3.message("the change is reported under the name of a deprecated"
            + " voltage regulation setter only, which happens when that setter created the VoltageRegulation (IIDM"
            + " reports no creation) or reported a value it did not change, so the state before the change set cannot"
            + " be told.");

    private RegulationKeyRefusals() {
    }

    /**
     * The refusal every change of a regulation holder asks first, in this order: an echo that survived the compaction
     * is the sole carrier of a change (rule 3); the import would give the holder a regulation it does not have; a local
     * voltage target the steady state hypothesis has no property for.
     *
     * @param attribute the attribute the change was reported under
     * @param key       its key, which for a limit carries the group and the duration as well
     */
    static Optional<String> refuse(Identifiable<?> identifiable, String attribute, String key, Scope scope) {
        if (RegulatingControlFamily.isEcho(identifiable, attribute)) {
            return Optional.of(SOLE_ECHO);
        }
        return importGivesRegulation(identifiable, scope).or(() -> localTarget(identifiable, key));
    }

    /**
     * Whether a change of the given key is no change of the steady state hypothesis at all: the local voltage target of a
     * holder in another mode, or regulating its own terminal, which regulates to its target value. Read by nothing.
     */
    static boolean notRepresented(Identifiable<?> identifiable, String key) {
        VoltageRegulation regulation = regulationOf(identifiable, key);
        return regulation != null && (!isVoltageMode(regulation.getMode())
                || regulation.isWithTerminal() && regulation.getTerminal() == ((VoltageRegulationHolder<?>) identifiable).getTerminal());
    }

    /**
     * A local voltage target in a voltage mode with a regulating terminal elsewhere is the target a load flow falls back
     * to when it switches to local control, and the SSH has no property for it.
     */
    private static Optional<String> localTarget(Identifiable<?> identifiable, String key) {
        VoltageRegulation regulation = regulationOf(identifiable, key);
        if (regulation == null || notRepresented(identifiable, key) || !regulation.isWithTerminal()) {
            return Optional.empty();
        }
        return Optional.of(Refusal.LOCAL_TARGET.message(identifiable.getType() + " " + identifiable.getId()
                + " regulates voltage at a regulating terminal, so its local voltage target is the target a"
                + " load flow falls back to when it switches to local control, and the steady state hypothesis"
                + " has no property for it."));
    }

    /** The regulation of a holder whose local voltage target the key names, {@code null} otherwise. */
    private static VoltageRegulation regulationOf(Identifiable<?> identifiable, String key) {
        return LOCAL_TARGET_V.equals(key) && identifiable instanceof VoltageRegulationHolder<?> holder
                ? holder.getVoltageRegulation() : null;
    }

    private static boolean isVoltageMode(RegulationMode mode) {
        return mode == RegulationMode.VOLTAGE || mode == RegulationMode.VOLTAGE_PER_REACTIVE_POWER;
    }

    /** The refusal of a key no steady state hypothesis has a property for: the mode, the terminal or the slope. */
    static String equipmentOnly(String key) {
        return switch (key.substring(key.lastIndexOf('.') + 1)) {
            case "RegulationMode", "regulationMode" -> Refusal.MODE_EQ.message(
                    "the regulation mode is RegulatingControl.mode, which belongs to the EQ profile.");
            case "Terminal", "regulationTerminal" -> Refusal.TERMINAL_EQ.message(
                    "the regulating terminal is RegulatingControl.Terminal, which belongs to the EQ profile.");
            default -> Refusal.SLOPE_NO_PROPERTY.message(
                    "a CGMES RegulatingControl has no slope, the " + key + " has no CGMES property.");
        };
    }

    /** The refusal of a key that is not the key of a family of the holder; empty for a key no regulation has. */
    static Optional<String> unread(Identifiable<?> identifiable, String key) {
        if ("pccTerminal".equals(key)) {
            // The regulating terminal of a voltage source converter of the detailed model is its point of common coupling
            return Optional.of(Refusal.TERMINAL_EQ.message("the point of common coupling of a converter is"
                    + " ACDCConverter.PccTerminal, which belongs to the EQ profile."));
        }
        if (key.endsWith(VR_TARGET_DEADBAND)) {
            return Optional.of(Refusal.DEADBAND_NOT_READ.message("the CGMES update reads the deadband of a"
                    + " RegulatingControl for shunt compensators and tap changers only, not for a " + identifiable.getType() + "."));
        }
        return Optional.empty();
    }

    /**
     * Why the given holder cannot be described although it changed, empty when it can: it has no VoltageRegulation,
     * but the CGMES update gives it one on every update of its equipment, from the RegulatingControl the equipment
     * model assigns it (a voltage source converter always has one, from {@code qPccControl}). A receiver would
     * therefore not end in the state of the sender (review 21 round 2, r2-m3). A full model does not honour it.
     */
    static Optional<String> importGivesRegulation(Identifiable<?> identifiable, Scope scope) {
        if (!scope.honours(Refusal.IMPORT_GIVES_REGULATION)) {
            return Optional.empty();
        }
        String source = switch (identifiable) {
            case VscConverterStation station when station.getVoltageRegulation() == null -> HvdcFamily.Q_PCC_CONTROL;
            case VoltageSourceConverter converter when converter.getVoltageRegulation() == null -> HvdcFamily.Q_PCC_CONTROL;
            case Generator generator when generator.getVoltageRegulation() == null
                    && generator.hasProperty(PROPERTY_REGULATING_CONTROL) -> "RegulatingControl";
            case ShuntCompensator shunt when shunt.getVoltageRegulation() == null
                    && shunt.hasProperty(PROPERTY_REGULATING_CONTROL) -> "RegulatingControl";
            case StaticVarCompensator svc when svc.getVoltageRegulation() == null
                    && svc.hasProperty(PROPERTY_REGULATING_CONTROL) -> "RegulatingControl";
            default -> null;
        };
        return Optional.ofNullable(source).map(from -> noVoltageRegulation(identifiable, from));
    }

    /** The refusal of a holder without VoltageRegulation whose CGMES equipment makes the import give it one. */
    static String noVoltageRegulation(Identifiable<?> holder, String source) {
        return Refusal.IMPORT_GIVES_REGULATION.message(holder.getType() + " " + holder.getId() + " has no"
                + " VoltageRegulation, but the CGMES update gives it one from its " + source + ", so the receiver would"
                + " not end in this state.");
    }
}
