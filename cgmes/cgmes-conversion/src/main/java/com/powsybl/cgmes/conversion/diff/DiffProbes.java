/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.diff.DiffSubjectResolver.ResolvedSubject;
import com.powsybl.cgmes.conversion.export.HvdcFamily;
import com.powsybl.cgmes.conversion.export.MachineFamily;
import com.powsybl.cgmes.conversion.export.SwitchAndTerminalFamily;
import com.powsybl.cgmes.conversion.export.TapChangerAndShuntFamily;
import com.powsybl.cgmes.conversion.mapping.LoadRows;
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

    private static final List<String> LINE = List.of("r", "x", "g1", "b1");
    private static final List<String> VOLTAGE_LEVEL = List.of("highVoltageLimit", "lowVoltageLimit");
    private static final List<String> BOUNDARY_LINE_IMPEDANCE = List.of("r", "x", "g", "b");

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
                case TAP_CHANGER_PREFIX -> probes.addAll(TapChangerAndShuntFamily.tapChangerProbes(prefix));
                case ATTRIBUTE_KEY -> probes.add(prefix);
                case NONE -> { /* nothing beyond the probes of the owner */ }
            }
        }
        return probes;
    }

    private static List<String> ownerProbes(Identifiable<?> owner) {
        return switch (owner) {
            case Switch ignored -> SwitchAndTerminalFamily.PROBES;
            case Load ignored -> LoadRows.keys();
            case Generator ignored -> MachineFamily.GENERATOR_PROBES;
            case BoundaryLine ignored -> concat(MachineFamily.BOUNDARY_LINE_PROBES, BOUNDARY_LINE_IMPEDANCE);
            case Line ignored -> LINE;
            case VoltageLevel ignored -> VOLTAGE_LEVEL;
            case ShuntCompensator ignored -> TapChangerAndShuntFamily.SHUNT_PROBES;
            case StaticVarCompensator ignored -> TapChangerAndShuntFamily.STATIC_VAR_COMPENSATOR_PROBES;
            case HvdcLine ignored -> HvdcFamily.LINE_PROBES;
            case VscConverterStation ignored -> HvdcFamily.VSC_STATION_PROBES;
            case LccConverterStation ignored -> HvdcFamily.LCC_STATION_PROBES;
            case VoltageSourceConverter ignored -> HvdcFamily.CONVERTER_PROBES;
            case LineCommutatedConverter ignored -> HvdcFamily.CONVERTER_PROBES;
            case TwoWindingsTransformer ignored -> TapChangerAndShuntFamily.transformerProbes("");
            case ThreeWindingsTransformer ignored -> TapChangerAndShuntFamily.transformerProbes("1", "2", "3");
            case DcSwitch ignored -> SwitchAndTerminalFamily.PROBES;
            default -> List.of();
        };
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
