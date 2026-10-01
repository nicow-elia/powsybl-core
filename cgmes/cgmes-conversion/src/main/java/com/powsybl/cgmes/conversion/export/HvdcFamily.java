/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.HvdcConverterStation;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.LccConverterStation;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.iidm.network.VscConverterStation;

/**
 * The converters of both DC models in the steady state hypothesis: the setpoints of a converter station of the
 * simplified model and the state of a converter of the detailed one, read from a state of the network, for every
 * export of the steady state hypothesis.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class HvdcFamily {

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
                    activePowerControl ? "pPcc" : "udc", VsConverterControlFamily.qPccControl(regulation, true, state),
                    VsConverterControlFamily.converterTargetQpcc(regulation, state),
                    VsConverterControlFamily.targetUpcc(regulation, state));
        }
        double q = converter.getPccTerminal().getQ();
        return new AcDcConverterState(targetPpcc, targetUdc, p, q, activePowerControl ? "activePower" : "dcVoltage",
                targetPpcc > 0.0 ? "rectifier" : "inverter", Double.NaN, Double.NaN);
    }

    private HvdcFamily() {
    }
}
