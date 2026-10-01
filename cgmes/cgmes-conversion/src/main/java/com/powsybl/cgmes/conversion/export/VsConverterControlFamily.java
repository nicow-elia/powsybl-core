/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.mapping.Quantity;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulationHolder;

import java.util.Optional;
import java.util.Set;

import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOCAL_TARGET_Q;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOCAL_TARGET_V;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_MODE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_REGULATING;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TARGET_VALUE;

/**
 * The control of a voltage source converter, of the simplified DC model (a converter station) and of the detailed one:
 * {@code VsConverter.qPccControl}, {@code targetUpcc}, {@code targetQpcc}, and the refusals of a converter whose
 * regulation the import would rebuild differently.
 *
 * <p>A VsConverter has no RegulatingControl and no control flag: the CGMES import rebuilds the whole VoltageRegulation
 * of a converter from {@code qPccControl} and the target of that mode, and always makes it regulate. IIDM holds one
 * regulation target and a mode since powsybl-core #3699: the target of the mode the converter is not in is written as
 * zero.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class VsConverterControlFamily {

    static final String Q_PCC_CONTROL = "VsConverter.qPccControl";

    /** The keys of a converter whose change describes its control. */
    static final Set<String> KEYS = Set.of(LOCAL_TARGET_Q, LOCAL_TARGET_V, VR_TARGET_VALUE, VR_REGULATING, VR_MODE);

    private VsConverterControlFamily() {
    }

    /**
     * The VsConverter.targetQpcc of a converter. Of a station of the simplified model: the reactive power target
     * whenever {@link #qPccControl} writes {@code reactivePcc}, that is whenever the station does not regulate voltage,
     * and zero otherwise; the import reads it as {@code -terminalSign * targetQpcc}
     * (HvdcConverterConversion#getValidTargetQ). A station in voltage mode that does not regulate (the deprecated
     * {@code setVoltageRegulatorOn(false)}) is written {@code reactivePcc}, and its target is the local reactive power
     * target it holds, not zero (review 21 round 2, R2-M4). Of the detailed model: the target of the reactive power
     * mode, as it stands.
     */
    static double targetQpcc(RegulationRef regulation, boolean detailed, CgmesExportContext context, IidmStateView state) {
        if (detailed) {
            return regulation.isWithMode(RegulationMode.REACTIVE_POWER, state) ? regulation.regulatingTargetQ(state) : 0;
        }
        return !regulation.isRegulatingWithMode(RegulationMode.VOLTAGE, state)
                ? Quantity.MVAR_MACHINE_TARGET.encode(regulation.regulatingTargetQ(state),
                        CgmesExportUtil.exportedTerminalSign(regulation.owner(), "", context))
                : 0;
    }

    /** The VsConverter.targetUpcc of a converter: the voltage target in voltage mode, zero otherwise. */
    static double targetUpcc(RegulationRef regulation, IidmStateView state) {
        return regulation.isWithMode(RegulationMode.VOLTAGE, state) ? regulation.regulatingTargetV(state) : 0;
    }

    /**
     * The VsConverter.qPccControl of a converter: {@code voltagePcc} for a station regulating voltage, and for a
     * converter of the detailed model in voltage mode; {@code reactivePcc} otherwise.
     */
    static String qPccControl(RegulationRef regulation, boolean detailed, IidmStateView state) {
        boolean voltage = detailed ? regulation.isWithMode(RegulationMode.VOLTAGE, state)
                : regulation.isRegulatingWithMode(RegulationMode.VOLTAGE, state);
        return voltage ? "voltagePcc" : "reactivePcc";
    }

    /** Both targets of a VsConverter, the one of the mode it is not in zero. */
    static void describeTargets(CgmesPropertySink out, double targetQpcc, double targetUpcc) {
        out.value("VsConverter.targetQpcc", targetQpcc).value("VsConverter.targetUpcc", targetUpcc);
    }

    /** Both control modes of a VsConverter, with which the CGMES import reads its targets; the end of the object. */
    static void describeControlModes(CgmesPropertySink out, String pPccControl, String qPccControl) {
        out.enumValue("VsConverter.pPccControl", "VsPpccControlKind", pPccControl)
                .enumValue(Q_PCC_CONTROL, "VsQpccControlKind", qPccControl)
                .endObject();
    }

    /**
     * Why a converter cannot be described, empty when it can: a VsConverter has no control flag, so a converter whose
     * regulation is switched off would come back regulating, in another mode for a station in voltage mode
     * (powsybl-core #3699: {@code qPccControl} follows {@code isRegulatingWithMode(VOLTAGE)}); a converter without
     * VoltageRegulation would be given one.
     */
    static Optional<String> refusal(Identifiable<?> converter, IidmStateView state, Scope scope) {
        if (!scope.honours(Refusal.VSC_NO_CONTROL_FLAG)) {
            return Optional.empty();
        }
        if (converter instanceof VoltageRegulationHolder<?> holder && holder.getVoltageRegulation() != null
                && !new RegulationRef(converter, "", holder).isRegulating(state)) {
            return Optional.of(Refusal.VSC_NO_CONTROL_FLAG.message("converter " + converter.getId() + " does not"
                    + " regulate, and a VsConverter has no control flag: the CGMES import always makes it regulate in"
                    + " the mode qPccControl names."));
        }
        if (converter instanceof VoltageRegulationHolder<?> holder && holder.getVoltageRegulation() == null) {
            return Optional.of(RegulationKeyRefusals.noVoltageRegulation(converter, Q_PCC_CONTROL));
        }
        return Optional.empty();
    }

    /** Why a converter of the given line cannot be described, empty when both can. */
    static Optional<String> refusalOfLine(HvdcLine hvdcLine, IidmStateView state, Scope scope) {
        return refusal(hvdcLine.getConverterStation1(), state, scope)
                .or(() -> refusal(hvdcLine.getConverterStation2(), state, scope));
    }

    /**
     * Why the control of a converter station cannot be described, empty when it can: a converter of its line refuses,
     * or its regulation has no mode in this variant. The station must belong to an HVDC line.
     */
    static Optional<String> stationRefusal(VscConverterStation converter, IidmStateView state, Scope scope) {
        RegulationRef regulation = RegulationRef.of(converter);
        return refusalOfLine(converter.getHvdcLine(), state, scope)
                .or(() -> regulation.regulation() != null && regulation.mode(state) == null
                        ? Optional.of(Refusal.NO_MODE.message("the voltage regulation of converter " + converter.getId()
                                + " has no mode in this variant, so qPccControl cannot be written."))
                        : Optional.empty());
    }

    /**
     * Why a change of the regulating terminal of a station cannot be described: regulating its own terminal or none is
     * the qPccControl of the station; any other terminal has no CGMES property (review 21 round 3, R3-M4).
     */
    static Optional<String> terminalRefusal(VscConverterStation converter, UpdateNetworkEvent event) {
        return RegulationRef.isOwnTerminalSwitch(converter.getTerminal(), event.oldValue(), event.newValue())
                ? Optional.empty()
                : Optional.of(Refusal.OWN_TERMINAL.message("the regulating terminal of converter "
                        + converter.getId() + " is not its own terminal, and a VsConverter has no property for"
                        + " another one: the import makes it regulate its own terminal."));
    }
}
