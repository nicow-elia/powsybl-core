/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.diff.DiffSubjectResolver.ResolvedSubject;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.DcSwitch;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.LccConverterStation;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.LineCommutatedConverter;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.iidm.network.VscConverterStation;

import java.util.ArrayList;
import java.util.List;

/**
 * Which attribute changes to ask the export mapping about in order to learn what a CGMES object currently says.
 *
 * <p>{@code CgmesObjectDump} answers "what would the change export write if this attribute had changed"; to describe
 * a whole CGMES object one has to ask about every attribute that maps onto it. This class holds that list, per kind
 * of IIDM object, using the very attribute names the change translator matches on.</p>
 *
 * <p>The list is deliberately generous: asking about an attribute the mapping refuses costs one rejected translation
 * and nothing else, while forgetting one would make a consistency group uncompletable. The result is filtered by
 * subject afterwards, so probing the transformer for all of its tap changers is harmless.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class DiffProbes {

    private static final List<String> LOAD = List.of("p0", "q0");
    // The voltage regulation of IIDM (powsybl-core #3699): local targets on the holder, the rest on its VoltageRegulation
    private static final String LOCAL_TARGET_Q = "localTargetQ";
    private static final String LOCAL_TARGET_V = "localTargetV";
    private static final String VR_TARGET_VALUE = "VoltageRegulation.TargetValue";
    private static final String VR_REGULATING = "VoltageRegulation.isRegulating";
    private static final String VR_TARGET_DEADBAND = "VoltageRegulation.TargetDeadband";
    private static final String VR_MODE = "VoltageRegulation.RegulationMode";

    private static final List<String> GENERATOR = List.of("targetP", LOCAL_TARGET_Q, LOCAL_TARGET_V, VR_TARGET_VALUE,
            VR_REGULATING, "activePowerControl#participationFactor", "referencePriority#referencePriority");
    private static final List<String> BOUNDARY_LINE = List.of("p0", "q0", "targetP", "targetQ", "targetV",
            "voltageRegulationOn");
    private static final List<String> SHUNT = List.of("sectionCount", LOCAL_TARGET_V, VR_TARGET_VALUE, VR_REGULATING,
            VR_TARGET_DEADBAND);
    private static final List<String> SVC = List.of(LOCAL_TARGET_Q, LOCAL_TARGET_V, VR_TARGET_VALUE, VR_REGULATING);
    private static final List<String> HVDC_LINE = List.of("activePowerSetpoint", "convertersMode");
    private static final List<String> VSC = List.of(LOCAL_TARGET_Q, LOCAL_TARGET_V, VR_TARGET_VALUE, VR_REGULATING, VR_MODE);
    private static final List<String> LCC = List.of("powerFactor");
    private static final List<String> DETAILED_CONVERTER = List.of("targetP", "targetVdc", "controlMode",
            LOCAL_TARGET_Q, LOCAL_TARGET_V, VR_TARGET_VALUE, VR_REGULATING, VR_MODE, "powerFactor");
    private static final List<String> SWITCH = List.of("open");
    private static final List<String> LINE = List.of("r", "x", "g1", "b1");
    private static final List<String> VOLTAGE_LEVEL = List.of("highVoltageLimit", "lowVoltageLimit");
    private static final List<String> BOUNDARY_LINE_IMPEDANCE = List.of("r", "x", "g", "b");

    /** The suffixes of a phase tap changer, which are prefixed by the name a recorded change gives it. */
    private static final List<String> PHASE_TAP_CHANGER_SUFFIXES = List.of(".tapPosition", ".regulating",
            ".regulationValue", ".targetDeadband");
    /** The suffixes of a ratio tap changer, which regulates through its VoltageRegulation. */
    private static final List<String> RATIO_TAP_CHANGER_SUFFIXES = List.of(".tapPosition", "." + VR_REGULATING,
            "." + VR_TARGET_VALUE, "." + VR_TARGET_DEADBAND);
    private static final String RATIO_TAP_CHANGER = "ratioTapChanger";
    private static final String PHASE_TAP_CHANGER = "phaseTapChanger";

    private DiffProbes() {
    }

    /**
     * The attribute keys to probe on one IIDM object in order to describe the given subject.
     *
     * <p>A subject may be described by more than one object: the steady state hypothesis of a converter station of
     * the simplified HVDC model lives partly on the station and partly on the HVDC line, and a regulating control is
     * described by every equipment regulating through it. The caller therefore asks for the probes of each object
     * the subject resolved to.</p>
     *
     * <p>A tap changer control belongs to one tap changer of a transformer, which is why the resolved subject
     * carries the name that tap changer has in a change log: only its attributes are probed, not those of the other
     * tap changers of the same transformer.</p>
     */
    static List<String> probesFor(ResolvedSubject subject, Identifiable<?> object) {
        List<String> probes = new ArrayList<>(ownerProbes(object));
        String prefix = subject.ownerAttributePrefix();
        if (!prefix.isEmpty() && object.equals(subject.owner())) {
            switch (subject.probeKind()) {
                case TAP_CHANGER_PREFIX -> tapChangerSuffixes(prefix).forEach(suffix -> probes.add(prefix + suffix));
                case ATTRIBUTE_KEY -> probes.add(prefix);
                case NONE -> { /* nothing beyond the probes of the owner */ }
            }
        }
        return probes;
    }

    private static List<String> ownerProbes(Identifiable<?> owner) {
        return switch (owner) {
            case Switch ignored -> SWITCH;
            case Load ignored -> LOAD;
            case Generator ignored -> GENERATOR;
            case BoundaryLine ignored -> concat(BOUNDARY_LINE, BOUNDARY_LINE_IMPEDANCE);
            case Line ignored -> LINE;
            case VoltageLevel ignored -> VOLTAGE_LEVEL;
            case ShuntCompensator ignored -> SHUNT;
            case StaticVarCompensator ignored -> SVC;
            case HvdcLine ignored -> HVDC_LINE;
            case VscConverterStation ignored -> VSC;
            case LccConverterStation ignored -> LCC;
            case VoltageSourceConverter ignored -> DETAILED_CONVERTER;
            case LineCommutatedConverter ignored -> DETAILED_CONVERTER;
            case TwoWindingsTransformer ignored -> allTapChangerProbes("");
            case ThreeWindingsTransformer ignored -> allTapChangerProbes("1", "2", "3");
            case DcSwitch ignored -> SWITCH;
            default -> List.of();
        };
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static List<String> tapChangerSuffixes(String prefix) {
        return prefix.startsWith(RATIO_TAP_CHANGER) ? RATIO_TAP_CHANGER_SUFFIXES : PHASE_TAP_CHANGER_SUFFIXES;
    }

    /** Every tap changer attribute of a transformer, for the case where the subject is the transformer itself. */
    private static List<String> allTapChangerProbes(String... ends) {
        List<String> probes = new ArrayList<>();
        for (String end : ends) {
            RATIO_TAP_CHANGER_SUFFIXES.forEach(suffix -> probes.add(RATIO_TAP_CHANGER + end + suffix));
            PHASE_TAP_CHANGER_SUFFIXES.forEach(suffix -> probes.add(PHASE_TAP_CHANGER + end + suffix));
        }
        return probes;
    }
}
