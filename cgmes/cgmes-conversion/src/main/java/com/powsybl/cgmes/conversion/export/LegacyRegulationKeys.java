/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.Identifiable;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_MODE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_REGULATING;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TARGET_DEADBAND;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TERMINAL;

/**
 * Maps the attribute names the deprecated voltage regulation setters of IIDM still report onto the names of the
 * voltage regulation itself.
 *
 * <p>Since the voltage regulation refactoring (powsybl-core #3699) a regulation is one {@code VoltageRegulation}
 * object plus the local targets of its holder, and each of them reports its changes under a name of its own:
 * {@code VoltageRegulation.TargetValue}, {@code VoltageRegulation.isRegulating}, {@code localTargetV}, ... The
 * deprecated setters ({@code Generator.setVoltageRegulatorOn}, {@code StaticVarCompensator.setVoltageSetpoint},
 * {@code RatioTapChanger.setRegulationValue}, ...) write through the new model, which reports the change under its
 * name, and then report it once more under the historical name: an <em>echo</em>. A change export that took both
 * events as two changes would compact them apart and could read the state before the change set from the echo, whose
 * old value is not always right.</p>
 *
 * <p>So every echo is either mapped onto the name of the value it repeats, when that is unambiguous (a flag, a mode,
 * a deadband, the regulating terminal), or dropped, when the value it repeats depends on the state (a target that is
 * the local one or the remote one depending on whether a regulating terminal is set): the setter always reported the
 * target under its own name first. The old value of an echo never becomes the state before a change set (it is not
 * reliable, and the canonical event is suppressed when nothing changed); when the deprecated setter had to create the
 * regulation, the echo is the only event, and the mapping keeps the change, with the live value as the state before.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class LegacyRegulationKeys {

    private LegacyRegulationKeys() {
    }

    /** The key under which a change is kept: the attribute itself, a canonical attribute, or {@link #DROPPED}. */
    static final String DROPPED = null;

    // Keyed by attribute name for any kind of identifiable: at powsybl-core 7.5 no other equipment than the voltage
    // regulation holders named in the comments reports these names (a boundary line spells its flag
    // voltageRegulationOn), so no type check is needed; a new user of one of these names would have to be added here.
    private static final Map<String, String> CANONICAL = Map.of(
            // Generator, shunt compensator, VSC converter station, voltage source converter
            "voltageRegulatorOn", VR_REGULATING,
            // Static var compensator
            "regulating", VR_REGULATING,
            "regulationMode", VR_MODE,
            // Shunt compensator
            "targetDeadband", VR_TARGET_DEADBAND,
            // Generator, shunt compensator, static var compensator, VSC converter station
            "regulatingTerminal", VR_TERMINAL);

    /** Targets the new model reports under their own name first, local or remote, see the class comment. */
    private static final Set<String> TARGET_ECHOES = Set.of("targetV", "voltageSetpoint", "reactivePowerSetpoint");

    private static final Pattern RATIO_TAP_CHANGER_ECHO = Pattern.compile(
            "^(ratioTapChanger[123]?)\\.(regulating|regulationMode|targetDeadband|regulationTerminal|regulationValue)$");

    /** Whether the key of the given attribute depends on the kind of equipment it was reported on. */
    static boolean needsIdentifiable(String attribute) {
        return TARGET_ECHOES.contains(attribute);
    }

    /**
     * The attribute a change of the given identifiable is kept under.
     *
     * @param identifiable the identifiable the change was reported on, {@code null} when the network no longer has it
     * @return the attribute unchanged when it is not an echo, the canonical attribute it repeats, or {@link #DROPPED}
     */
    static String canonical(Identifiable<?> identifiable, String attribute) {
        String canonical = CANONICAL.get(attribute);
        if (canonical != null) {
            return canonical;
        }
        // A boundary line generation is not a voltage regulation holder: its targetV is a value of its own
        if (TARGET_ECHOES.contains(attribute) && identifiable != null && !(identifiable instanceof BoundaryLine)) {
            return DROPPED;
        }
        if (attribute.startsWith("ratioTapChanger")) {
            Matcher matcher = RATIO_TAP_CHANGER_ECHO.matcher(attribute);
            if (matcher.matches()) {
                String prefix = matcher.group(1) + ".";
                return switch (matcher.group(2)) {
                    case "regulating" -> prefix + VR_REGULATING;
                    case "regulationMode" -> prefix + VR_MODE;
                    case "targetDeadband" -> prefix + VR_TARGET_DEADBAND;
                    case "regulationTerminal" -> prefix + VR_TERMINAL;
                    default -> DROPPED;
                };
            }
        }
        return attribute;
    }
}
