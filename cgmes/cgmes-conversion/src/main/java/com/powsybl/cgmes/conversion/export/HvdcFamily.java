/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.mapping.Block;
import com.powsybl.cgmes.conversion.mapping.Quantity;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.HvdcConverterStation;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.LccConverterStation;
import com.powsybl.iidm.network.LineCommutatedConverter;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulationHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.ACTIVE_POWER_SETPOINT;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.CONTROL_MODE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.CONVERTERS_MODE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOCAL_TARGET_Q;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOCAL_TARGET_V;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.POWER_FACTOR;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_P;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_VDC;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_MODE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_REGULATING;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TARGET_VALUE;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TERMINAL;
import static com.powsybl.cgmes.conversion.export.CgmesPropertyBuffer.merge;
import static com.powsybl.commons.util.Result.failure;
import static com.powsybl.commons.util.Result.success;

/**
 * The converters of both DC models in every export of the steady state hypothesis: the setpoints a converter station of
 * the simplified model takes from its HVDC line, the VsConverter and CsConverter of a station and of a converter of
 * the detailed model, and the control of a voltage source converter ({@code VsConverter.qPccControl},
 * {@code targetUpcc}, {@code targetQpcc}) with the refusals of a converter whose regulation the import would rebuild
 * differently.
 *
 * <p>The CGMES update of the simplified model takes the power of a link from the {@code targetPpcc} of whichever
 * converter states one (powsybl-core #4057), and the inverter states zero: the setpoint blocks of both converters of a
 * line are one group, so a change of the line, of a power factor or of the reactive power of a station describes both
 * converters ({@link #linkUpdates}), and the in-place import completes the partner from the same description.</p>
 *
 * <p>A VsConverter has no RegulatingControl and no control flag: the CGMES import rebuilds the whole VoltageRegulation
 * of a converter from {@code qPccControl} and the target of that mode, and always makes it regulate. IIDM holds one
 * regulation target and a mode since powsybl-core #3699: the target of the mode the converter is not in is written as
 * zero.</p>
 *
 * <p>The keys a change is reported under and the blocks the CGMES update reads are declared here; the dispatch of the
 * change export, the description and the capabilities of the in-place import are derived from them.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class HvdcFamily extends AbstractFamily {

    private static final String ACDC_CONVERTER_TARGET_PPCC = "ACDCConverter.targetPpcc";
    private static final String ACDC_CONVERTER_TARGET_UDC = "ACDCConverter.targetUdc";
    private static final String ACDC_CONVERTER_P = "ACDCConverter.p";
    private static final String ACDC_CONVERTER_Q = "ACDCConverter.q";
    private static final String CS_CONVERTER_OPERATING_MODE = "CsConverter.operatingMode";
    private static final String CS_CONVERTER_P_PCC_CONTROL = "CsConverter.pPccControl";
    private static final String VS_CONVERTER_P_PCC_CONTROL = "VsConverter.pPccControl";
    static final String Q_PCC_CONTROL = "VsConverter.qPccControl";
    private static final String VS_CONVERTER_TARGET_QPCC = "VsConverter.targetQpcc";
    private static final String VS_CONVERTER_TARGET_UPCC = "VsConverter.targetUpcc";

    /**
     * The keys of an HVDC line (the power of the link and which of its ends rectifies), of a converter station and of a
     * converter of the detailed model whose change this family describes; the power factor of a line commutated converter
     * station is the other key it describes.
     */
    static final Set<String> LINE_KEYS = Set.of(ACTIVE_POWER_SETPOINT, CONVERTERS_MODE);
    /** The keys of a voltage source converter whose change describes its control. */
    static final Set<String> CONTROL_KEYS = Set.of(LOCAL_TARGET_Q, LOCAL_TARGET_V, VR_TARGET_VALUE, VR_REGULATING, VR_MODE);
    static final Set<String> CONVERTER_KEYS = Set.of(TARGET_P, TARGET_VDC, CONTROL_MODE, LOCAL_TARGET_Q,
            LOCAL_TARGET_V, VR_TARGET_VALUE, VR_REGULATING, VR_MODE, POWER_FACTOR);

    /** The setpoint block of any converter: the CGMES update reads these four together (and of both converters of a line). */
    public static final List<String> SETPOINTS = List.of(ACDC_CONVERTER_TARGET_PPCC, ACDC_CONVERTER_TARGET_UDC,
            ACDC_CONVERTER_P, ACDC_CONVERTER_Q);
    /** The control block of a converter, read with its setpoints by the same query. */
    public static final Block CS_CONVERTER = new Block("acDcConverters", List.of(CgmesNames.CS_CONVERTER),
            CS_CONVERTER_OPERATING_MODE, CS_CONVERTER_P_PCC_CONTROL);
    public static final Block VS_CONVERTER = new Block("acDcConverters", List.of(CgmesNames.VS_CONVERTER),
            List.of(VS_CONVERTER_P_PCC_CONTROL, Q_PCC_CONTROL), List.of(VS_CONVERTER_TARGET_QPCC, VS_CONVERTER_TARGET_UPCC));

    HvdcFamily(CgmesExportContext context, IidmStateView state, Scope scope) {
        super(context, state, scope);
    }

    /**
     * The blocks of both converters of an HVDC line, or why a converter of the line cannot be described: what a change
     * of the line describes, and what the in-place import completes the partner of a converter from.
     */
    Result<CgmesPropertyBuffer, String> linkUpdates(HvdcLine hvdcLine) {
        return refusalOfLine(hvdcLine, state, scope).<Result<CgmesPropertyBuffer, String>>map(Result::failure)
                .orElseGet(() -> success(bothConverterUpdates(hvdcLine)));
    }

    /**
     * The blocks of both converters of an HVDC line, which is where CGMES holds the power of the link and which of
     * its two ends rectifies.
     */
    Result<CgmesPropertyBuffer, String> hvdcLineUpdates(HvdcLine hvdcLine, String attribute) {
        if (CONVERTERS_MODE.equals(attribute)
                && hvdcLine.getConverterStation1() instanceof VscConverterStation
                && state.getDouble(hvdcLine, ACTIVE_POWER_SETPOINT, hvdcLine::getActivePowerSetpoint) == 0) {
            return failure("a VsConverter has no operating mode, the mode is only derived from a non zero targetPpcc");
        }
        return linkUpdates(hvdcLine);
    }

    /**
     * The power factor of a line commutated converter is not a CGMES property of its own: the profile carries the
     * active and the reactive power of the converter, from which the CGMES import derives the factor back.
     */
    Result<CgmesPropertyBuffer, String> lccPowerFactorUpdates(LccConverterStation converter) {
        HvdcLine hvdcLine = converter.getHvdcLine();
        if (hvdcLine == null) {
            return failure("converter " + converter.getId() + " belongs to no HVDC line, so it has no power to"
                    + " carry its power factor");
        }
        if (state.getDouble(hvdcLine, ACTIVE_POWER_SETPOINT, hvdcLine::getActivePowerSetpoint) == 0) {
            return failure("the power factor is carried by ACDCConverter.p and q, which are zero");
        }
        return success(bothConverterUpdates(hvdcLine));
    }

    private CgmesPropertyBuffer bothConverterUpdates(HvdcLine hvdcLine) {
        return merge(converterActivePowerUpdates(hvdcLine.getConverterStation1()),
                converterActivePowerUpdates(hvdcLine.getConverterStation2()));
    }

    private CgmesPropertyBuffer converterActivePowerUpdates(HvdcConverterStation<?> converter) {
        // The CGMES import reads targetPpcc, targetUdc, p and q as a single block, and derives the power factor of
        // a line commutated converter from p and q, so the four quantities are always exported together. They are
        // computed exactly as the full SSH export computes them.
        return collect(out -> {
            switch (converter) {
                case LccConverterStation lcc -> describeLccConverterStation(lcc, out);
                case VscConverterStation vsc -> vsConverterStationBlock(vsc, true, false, out);
                default -> throw new IllegalStateException("Unhandled converter station " + converter.getClass().getSimpleName());
            }
        });
    }

    /**
     * The four quantities the CGMES import reads as a single block for any converter, of the simplified model as
     * well as of the detailed one.
     */
    private static CgmesPropertySink converterSetpoints(CgmesPropertySink out, ConverterSetpoints setpoints) {
        return out.value(ACDC_CONVERTER_TARGET_PPCC, setpoints.targetPpcc())
                .value(ACDC_CONVERTER_TARGET_UDC, setpoints.targetUdc())
                .value(ACDC_CONVERTER_P, setpoints.p())
                .value(ACDC_CONVERTER_Q, setpoints.q());
    }

    /**
     * Describe the CsConverter of a converter station of the simplified DC model: the setpoints of its line and the
     * control modes of the end it is. The station must belong to an HVDC line.
     */
    void describeLccConverterStation(LccConverterStation converter, CgmesPropertySink out) {
        boolean rectifier = CgmesExportUtil.isConverterStationRectifier(converter, state);
        csConverterBlock(out, cgmesId(converter), computeConverterState(converter, state),
                rectifier ? "rectifier" : "inverter", rectifier ? "activePower" : "dcVoltage");
    }

    /**
     * A CsConverter: its setpoints, in a full model the constants of the class, which no change touches, and its
     * control modes. The full export keeps its own writer for a converter of the detailed model, which writes the
     * powers of a line commutated converter differently (B4, owner decision O2b), until that is decided.
     */
    private void csConverterBlock(CgmesPropertySink out, String id, ConverterSetpoints setpoints,
                                  String operatingMode, String pPccControl) {
        converterSetpoints(out.startObject(CgmesNames.CS_CONVERTER, id), setpoints);
        if (scope == Scope.FULL_MODEL) {
            out.value("CsConverter.targetAlpha", 0.0).value("CsConverter.targetGamma", 0.0).value("CsConverter.targetIdc", 0.0);
        }
        out.enumValue(CS_CONVERTER_OPERATING_MODE, "CsOperatingModeKind", operatingMode)
                .enumValue(CS_CONVERTER_P_PCC_CONTROL, "CsPpccControlKind", pPccControl)
                .endObject();
    }

    /**
     * The control of a voltage source converter station: both control modes, which the CGMES import only reads
     * together, and both targets as the full export writes them.
     *
     * <p>IIDM holds one regulation target and a mode since powsybl-core #3699: the target of the mode the station is
     * not in is written as zero, and the import rebuilds the whole VoltageRegulation from {@code qPccControl} and the
     * target of that mode. The reactive power of the station, {@code ACDCConverter.q}, is its local reactive power
     * target, so a change of it writes the converter blocks of both stations of the line. A station that belongs to
     * no line is refused: the line holds the setpoints of the station and says which of its ends rectifies.</p>
     *
     * <p>Regulating its own terminal or none is the qPccControl of the station, exported with it; a change to any other
     * terminal is refused ({@link #terminalRefusal}).</p>
     *
     * @param attribute the changed attribute, {@code null} for the whole station (its setpoints, targets and modes)
     * @param event     the change, read for the old and the new regulating terminal; {@code null} for the whole station
     */
    Result<CgmesPropertyBuffer, String> vscStationUpdates(VscConverterStation converter, String attribute,
                                                          UpdateNetworkEvent event) {
        if (converter.getHvdcLine() == null) {
            return failure(noHvdcLine(converter));
        }
        if (VR_TERMINAL.equals(attribute)) {
            Optional<String> terminal = terminalRefusal(converter, event);
            if (terminal.isPresent()) {
                return failure(terminal.get());
            }
        }
        Optional<String> refusal = stationRefusal(converter, state, scope);
        if (refusal.isPresent()) {
            return failure(refusal.get());
        }
        CgmesPropertyBuffer control = collect(out -> vsConverterStationBlock(converter, attribute == null, true, out));
        if (!LOCAL_TARGET_Q.equals(attribute)) {
            return success(control);
        }
        // ACDCConverter.q travels in one block with targetPpcc, and the import takes a targetPpcc stated on either side
        // as the power of the link (powsybl-core #4057): the zero of the inverter alone would bring the link down
        return success(merge(control, bothConverterUpdates(converter.getHvdcLine())));
    }

    private static String noHvdcLine(HvdcConverterStation<?> converter) {
        return "converter " + converter.getId() + " belongs to no HVDC line, which holds its power";
    }

    /**
     * Describe the VsConverter of a converter station of the simplified DC model: its setpoints, its targets and its
     * control modes, in the order of its CIM class. The station must belong to an HVDC line.
     */
    void describeVscConverterStation(VscConverterStation converter, CgmesPropertySink out) {
        vsConverterStationBlock(converter, true, true, out);
    }

    /**
     * The VsConverter of a converter station, or the part of it a change touches: the setpoints of the line, or the
     * targets; the control modes always, which the CGMES import reads the targets with.
     */
    private void vsConverterStationBlock(VscConverterStation converter, boolean withSetpoints, boolean withTargets,
                                         CgmesPropertySink out) {
        RegulationRef regulation = RegulationRef.of(converter);
        out.startObject(CgmesNames.VS_CONVERTER, cgmesId(converter));
        if (withSetpoints) {
            vsConverterSetpoints(out, computeConverterState(converter, state));
        }
        if (withTargets) {
            describeTargets(out, stationTargetQpcc(regulation, context, state),
                    targetUpcc(regulation, state));
        }
        describeControlModes(out, CgmesExportUtil.isConverterStationRectifier(converter, state) ? "pPcc" : "udc",
                qPccControl(regulation, false, state));
    }

    /** Describe a voltage source converter of the detailed DC model, in the order of its CIM class. */
    void describeVoltageSourceConverter(VoltageSourceConverter converter, CgmesPropertySink out) {
        AcDcConverterState converterState =
                computeAcDcConverterState(converter, state);
        vsConverterSetpoints(out.startObject(CgmesNames.VS_CONVERTER, cgmesId(converter)), converterState);
        describeTargets(out, converterState.targetQpcc(), converterState.targetUpcc());
        describeControlModes(out, converterState.pPccControl(), converterState.operatingModeOrQpccControl());
    }

    /** The setpoints of a VsConverter, and in a full model the constants of the class, which no change touches. */
    private void vsConverterSetpoints(CgmesPropertySink out, ConverterSetpoints setpoints) {
        converterSetpoints(out, setpoints);
        if (scope == Scope.FULL_MODEL) {
            out.value("VsConverter.droop", 0.0).value("VsConverter.droopCompensation", 0.0).value("VsConverter.qShare", 0.0);
        }
    }

    /**
     * The block describing a converter of the detailed DC model, which carries its own control modes and setpoints
     * rather than deriving them from an HVDC line.
     *
     * <p>The CGMES update reads the setpoints and the control modes of a converter as one group, so all of them are
     * written whatever the change was. A line commutated converter has no power factor of its own in CGMES: the
     * profile carries its active and reactive power, from which the import derives the factor back, so a power
     * factor is only transportable next to a power that is not zero.</p>
     */
    Result<CgmesPropertyBuffer, String> acDcConverterUpdates(AcDcConverter<?> converter, String attribute) {
        AcDcConverterState converterState =
                computeAcDcConverterState(converter, state);
        return switch (converter) {
            case LineCommutatedConverter lcc -> lineCommutatedConverterUpdates(lcc, converterState, attribute);
            case VoltageSourceConverter vsc -> unlessRefused(refusal(vsc, state, scope),
                    out -> describeVoltageSourceConverter(vsc, out));
            default -> failure("converter " + converter.getId() + " is a "
                    + converter.getClass().getSimpleName() + ", which has no steady state setpoints");
        };
    }

    private Result<CgmesPropertyBuffer, String> lineCommutatedConverterUpdates(
            LineCommutatedConverter converter, AcDcConverterState converterState, String attribute) {
        double referenceP = lineCommutatedConverterReferenceP(converterState);
        double powerFactor = state.getDouble(converter, POWER_FACTOR, converter::getPowerFactor);
        ConverterSetpoints setpoints = converterState;
        if (referenceP != 0 && powerFactor > 0) {
            setpoints = new ConverterState(converterState.targetPpcc(), converterState.targetUdc(),
                    referenceP, Math.abs(referenceP) * Math.sqrt(1 - powerFactor * powerFactor) / powerFactor);
        } else if (POWER_FACTOR.equals(attribute)) {
            // A power factor of zero would make the reactive power infinite, and there is no power to express it
            // against anyway
            return failure("the power factor is carried by ACDCConverter.p and q, which are zero");
        }
        ConverterSetpoints described = setpoints;
        return success(collect(out -> csConverterBlock(out, cgmesId(converter), described,
                converterState.operatingModeOrQpccControl(), converterState.pPccControl())));
    }

    /** The active power the power factor of a line commutated converter is expressed against, or zero if it has none. */
    private static double lineCommutatedConverterReferenceP(AcDcConverterState converterState) {
        if (converterState.targetPpcc() != 0 && Double.isFinite(converterState.targetPpcc())) {
            return converterState.targetPpcc();
        }
        return Double.isFinite(converterState.p()) ? converterState.p() : 0.0;
    }

    // The control of a voltage source converter

    /**
     * The VsConverter.targetQpcc of a converter station of the simplified model: the reactive power target whenever
     * {@link #qPccControl} writes {@code reactivePcc}, that is whenever the station does not regulate voltage, and zero
     * otherwise; the import reads it as {@code -terminalSign * targetQpcc} (HvdcConverterConversion#getValidTargetQ). A
     * station in voltage mode that does not regulate (the deprecated {@code setVoltageRegulatorOn(false)}) is written
     * {@code reactivePcc}, and its target is the local reactive power target it holds, not zero (review 21 round 2,
     * R2-M4).
     */
    static double stationTargetQpcc(RegulationRef regulation, CgmesExportContext context, IidmStateView state) {
        return !regulation.isRegulatingWithMode(RegulationMode.VOLTAGE, state)
                ? Quantity.MVAR_MACHINE_TARGET.encode(regulation.regulatingTargetQ(state),
                        CgmesExportUtil.exportedTerminalSign(regulation.owner(), "", context))
                : 0;
    }

    /** The VsConverter.targetQpcc of a converter of the detailed model: the target of the reactive power mode, as it stands. */
    static double converterTargetQpcc(RegulationRef regulation, IidmStateView state) {
        return regulation.isWithMode(RegulationMode.REACTIVE_POWER, state) ? regulation.regulatingTargetQ(state) : 0;
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
        out.value(VS_CONVERTER_TARGET_QPCC, targetQpcc).value(VS_CONVERTER_TARGET_UPCC, targetUpcc);
    }

    /** Both control modes of a VsConverter, with which the CGMES import reads its targets; the end of the object. */
    static void describeControlModes(CgmesPropertySink out, String pPccControl, String qPccControl) {
        out.enumValue(VS_CONVERTER_P_PCC_CONTROL, "VsPpccControlKind", pPccControl)
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
    private static Optional<String> terminalRefusal(VscConverterStation converter, UpdateNetworkEvent event) {
        return RegulationRef.isOwnTerminalSwitch(converter.getTerminal(), event.oldValue(), event.newValue())
                ? Optional.empty()
                : Optional.of(Refusal.OWN_TERMINAL.message("the regulating terminal of converter "
                        + converter.getId() + " is not its own terminal, and a VsConverter has no property for"
                        + " another one: the import makes it regulate its own terminal."));
    }

    // The setpoints and the state of a converter, shared with the full export

    /** The four quantities the CGMES import reads as a single block for any converter. */
    interface ConverterSetpoints {
        double targetPpcc();

        double targetUdc();

        double p();

        double q();
    }

    record ConverterState(double targetPpcc, double targetUdc, double p, double q) implements ConverterSetpoints {
    }

    /**
     * The quantities describing a converter station of the simplified DC model, read from the given state of the
     * network.
     *
     * <p>Package private so that the change export writes the same values as the full export.</p>
     */
    static ConverterState computeConverterState(HvdcConverterStation<?> converterStation, IidmStateView state) {
        HvdcLine hvdcLine = converterStation.getHvdcLine();
        double activePowerSetpoint = state.getDouble(hvdcLine, CgmesChangeTranslator.ACTIVE_POWER_SETPOINT,
                hvdcLine::getActivePowerSetpoint);
        double targetPpcc;
        double targetUdc;
        double p;
        if (CgmesExportUtil.isConverterStationRectifier(converterStation, state)) {
            targetPpcc = activePowerSetpoint;
            targetUdc = 0.0;
            p = targetPpcc;
        } else {
            double otherConverterStationLossFactor = converterStation.getOtherConverterStation().map(HvdcConverterStation::getLossFactor).orElse(0.0f);
            double pDCRectifier = activePowerSetpoint * (1 - otherConverterStationLossFactor / 100);
            double idc = pDCRectifier / hvdcLine.getNominalV();
            double pDCInverter = -1 * (pDCRectifier - hvdcLine.getR() * idc * idc);
            double poleLoss = converterStation.getLossFactor() / 100 * Math.abs(pDCInverter);
            targetPpcc = 0.0;
            targetUdc = hvdcLine.getNominalV() - hvdcLine.getR() * idc;
            p = pDCInverter + poleLoss;
        }

        if (converterStation instanceof LccConverterStation lccConverterStation) {
            double powerFactor = state.getDouble(lccConverterStation, CgmesChangeTranslator.POWER_FACTOR,
                    lccConverterStation::getPowerFactor);
            return new ConverterState(targetPpcc, targetUdc, p, Math.abs(getQfromPowerFactor(p, powerFactor)));
        } else if (converterStation instanceof VscConverterStation vscConverterStation) {
            return new ConverterState(targetPpcc, targetUdc, vscConverterStation.getRegulatingTerminal().getP(),
                    -RegulationRef.of(vscConverterStation).localTargetQ(state));
        }
        return new ConverterState(targetPpcc, targetUdc, p, Double.NaN);
    }

    private static double getQfromPowerFactor(double p, double powerFactor) {
        if (powerFactor == 0.0) {
            return 0.0;
        }
        return p * Math.sqrt((1 - powerFactor * powerFactor) / (powerFactor * powerFactor));
    }

    /**
     * The steady state of a converter of the detailed DC model.
     *
     * @param pPccControl                the control mode of the active power, a CsPpccControlKind for a line
     *                                   commutated converter and a VsPpccControlKind for a voltage source one
     * @param operatingModeOrQpccControl the CsOperatingModeKind of a line commutated converter, the VsQpccControlKind
     *                                   of a voltage source one: the second enumeration each class carries
     * @param targetQpcc                 the reactive power target of a voltage source converter, NaN for a line
     *                                   commutated one
     * @param targetUpcc                 the voltage target of a voltage source converter, NaN for a line commutated one
     */
    record AcDcConverterState(double targetPpcc, double targetUdc, double p, double q,
                              String pPccControl, String operatingModeOrQpccControl,
                              double targetQpcc, double targetUpcc) implements ConverterSetpoints {
    }

    /**
     * Compute the quantities describing a converter of the detailed DC model, read from the given state of the
     * network.
     *
     * <p>Package private so that the change export writes the same values as the full export.</p>
     */
    static AcDcConverterState computeAcDcConverterState(AcDcConverter<?> converter, IidmStateView state) {
        AcDcConverter.ControlMode controlMode = state.getEnum(converter, CgmesChangeTranslator.CONTROL_MODE,
                AcDcConverter.ControlMode.class, converter::getControlMode);
        boolean activePowerControl = controlMode == AcDcConverter.ControlMode.P_PCC;
        boolean dcVoltageControl = controlMode == AcDcConverter.ControlMode.V_DC;
        double targetPpcc = activePowerControl
                ? state.getDouble(converter, CgmesChangeTranslator.TARGET_P, converter::getTargetP) : 0.0;
        double targetUdc = dcVoltageControl
                ? state.getDouble(converter, CgmesChangeTranslator.TARGET_VDC, converter::getTargetVdc) : 0.0;
        double p = converter.getPccTerminal().getP();
        if (converter instanceof VoltageSourceConverter vsc) {
            RegulationRef regulation = RegulationRef.of(vsc);
            return new AcDcConverterState(targetPpcc, targetUdc, p, regulation.localTargetQ(state),
                    activePowerControl ? "pPcc" : "udc", qPccControl(regulation, true, state),
                    converterTargetQpcc(regulation, state),
                    targetUpcc(regulation, state));
        }
        double q = converter.getPccTerminal().getQ();
        return new AcDcConverterState(targetPpcc, targetUdc, p, q, activePowerControl ? "activePower" : "dcVoltage",
                targetPpcc > 0.0 ? "rectifier" : "inverter", Double.NaN, Double.NaN);
    }

}
