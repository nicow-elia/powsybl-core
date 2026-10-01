/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Why the change mapping does not write a change of a voltage regulation, or of an object that carries one.
 *
 * <p>Every refusal names its cause and a remedy: its message is the cause, followed by {@value #REMEDY} and the remedy
 * sentence of its constant, which is unique, so that the constant can be told back from the message ({@link #of}).
 * The detection stays where the condition is known, in {@link CgmesChangeTranslator} and
 * {@link CgmesChangeRegulatingControls}; this type only names the refusal and says which receivers honour it.</p>
 *
 * <p>A refusal is {@link #changesOnly} when it protects a receiver that already holds a state and applies a change
 * on top of it (a partial SSH, a difference model). A full steady state hypothesis states the whole state and does
 * not honour it, see {@link Scope#honours}.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public enum Refusal {

    /** An echo of a deprecated voltage regulation setter that is the sole carrier of a change. */
    ECHO_3("echo-3", "give the equipment its VoltageRegulation before recording the change set, and change it through"
            + " the VoltageRegulation and the local target setters", true),
    /** The local voltage target of a holder regulating voltage at a terminal elsewhere. */
    LOCAL_TARGET("local-target", "export the equipment model with the change, or change the target of the regulation"
            + " only (VoltageRegulation.setTargetValue; Generator.setTargetV(v, local) reports a local target in any"
            + " case)", true),
    /** A converter station regulating a terminal other than its own. */
    OWN_TERMINAL("own-terminal", "regulate the converter's own terminal (VoltageRegulation.setTerminal(converter"
            + " terminal, target)), or none", true),
    /** A regulating terminal, which is equipment data. */
    TERMINAL_EQ("terminal-eq", "export the equipment model with the change (a full CGMES export), or keep the"
            + " regulating terminal the import set", true),
    /** A regulation mode, which is equipment data. */
    MODE_EQ("mode-eq", "export the equipment model with the change (a full CGMES export), or keep the mode the import"
            + " set", true),
    /** A slope, which a CGMES RegulatingControl does not have. */
    SLOPE_NO_PROPERTY("slope-no-property", "leave the slope as the import set it", true),
    /** A deadband the CGMES update does not read for this kind of holder. */
    DEADBAND_NOT_READ("deadband-not-read", "leave the deadband of this regulation as the import set it", true),
    /** A holder without VoltageRegulation to which the CGMES update gives one. */
    IMPORT_GIVES_REGULATION("import-gives-regulation", "give it a VoltageRegulation (not regulating, if it must not"
            + " regulate) before recording the change set", true),
    /** A converter of either DC model that does not regulate: a VsConverter has no control flag. */
    VSC_NO_CONTROL_FLAG("vsc-no-control-flag", "let it regulate, in REACTIVE_POWER mode with its reactive power target"
            + " for a converter that must not regulate voltage", true),
    /** A holder without a RegulatingControl the import recorded. */
    NO_CONTROL("no-control", "keep its regulation as the import left it; to change it, give it a VoltageRegulation (not"
            + " regulating) first and export the full model (EQ and SSH): a full export writes a RegulatingControl only"
            + " for equipment that has a VoltageRegulation", true),
    /** A tap changer without a TapChangerControl the import recorded. */
    NO_TAP_CHANGER_CONTROL("no-tap-changer-control", "keep its regulation as the equipment model defines it, or export"
            + " the equipment model with the change", true),
    /** A shunt compensator the import made an EquivalentShunt, which has no steady state properties. */
    EQUIVALENT_SHUNT("equivalent-shunt", "keep it as the equipment model defines it, or export the equipment model"
            + " with the change", false),
    /** A regulation without a mode in this variant: it was created while another variant was the working one. */
    NO_MODE("no-mode", "set the mode of its VoltageRegulation in this variant", false),
    /** A generator whose regulation mode disagrees with the CGMES mode its import recorded. */
    CGMES_MODE("cgmes-mode", "keep the mode the import set, or export the equipment model with the change", true),
    /** The regulation of a ratio tap changer that does not regulate voltage. */
    RTC_REACTIVE_POWER("rtc-reactive-power", "export the full steady state hypothesis instead", true),
    /** Tap changers sharing a TapChangerControl that disagree on whether they regulate. */
    TAP_CHANGERS_DISAGREE("tap-changers-disagree", "switch the regulation of every tap changer of the control"
            + " together", true),
    /** An EquivalentInjection switched to regulate although the equipment model gives it no regulation capability. */
    NO_REGULATION_CAPABILITY("no-regulation-capability", "keep its regulation off, or export the equipment model with"
            + " a regulation capability", true),
    /** A phase tap changer that regulates no terminal. */
    PTC_NO_TERMINAL("ptc-no-terminal", "give it a regulation terminal before recording the change set, or keep its"
            + " regulation as the import left it", true),
    /**
     * A shared control one of whose users cannot be described. Its message is the refusal of that user; this
     * constant only says that a full export writes the control from the users it can describe.
     */
    UNDESCRIBED_USER("undescribed-user", "make every user of the shared control describable", true);

    /** What a refusal says before its remedy. */
    public static final String REMEDY = "Remedy: ";

    /** What a change export appends to a refusal, after a full stop. */
    private static final String CHANGE = ". Change: ";

    private final String rule;
    private final String remedy;
    private final boolean changesOnly;

    Refusal(String rule, String remedy, boolean changesOnly) {
        this.rule = rule;
        this.remedy = remedy;
        this.changesOnly = changesOnly;
    }

    /** The rule this refusal follows, as the column RULE of the setter matrix names it. */
    public String getRule() {
        return rule;
    }

    /** The remedy sentence, unique to this refusal. */
    public String getRemedy() {
        return remedy;
    }

    /** Whether only a receiver of changes honours it, see {@link Scope#honours}. */
    public boolean isChangesOnly() {
        return changesOnly;
    }

    /** The message of this refusal: the cause, then {@value #REMEDY} and the remedy. */
    String message(String cause) {
        return Objects.requireNonNull(cause) + " " + REMEDY + remedy;
    }

    /**
     * The refusal a message was built from: the one whose remedy sentence it ends with, the message of a change export
     * ({@code ". Change: …"}) included.
     */
    public static Optional<Refusal> of(String message) {
        if (message == null) {
            return Optional.empty();
        }
        int start = message.lastIndexOf(REMEDY);
        if (start < 0) {
            return Optional.empty();
        }
        String remedy = message.substring(start + REMEDY.length());
        int change = remedy.indexOf(CHANGE);
        String sentence = change < 0 ? remedy : remedy.substring(0, change);
        return Arrays.stream(values()).filter(refusal -> refusal.remedy.equals(sentence)).findFirst();
    }
}
