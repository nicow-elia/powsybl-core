/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.mapping.Quantity;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.regulation.VoltageRegulationHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REGULATION_MODE_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REGULATION_VALUE_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_DEADBAND_SUFFIX;
import static com.powsybl.cgmes.conversion.export.CgmesExportUtil.getRegulatingControlMode;
import static com.powsybl.cgmes.conversion.export.elements.RegulatingControlEq.REGULATING_CONTROL_REACTIVE_POWER;
import static com.powsybl.cgmes.conversion.export.elements.RegulatingControlEq.REGULATING_CONTROL_VOLTAGE;

/**
 * What one user says about its CGMES RegulatingControl or TapChangerControl ({@link #of}, {@link #ofPhaseTapChanger}),
 * how the views of all the users of one control combine ({@link #combine}) and how the control is written
 * ({@link #describe}). Upstream's view of the full steady state hypothesis export, read from a state of the network so
 * that every export of the steady state hypothesis writes a control the same way; which equipment shares a control is
 * {@link RegulatingControlFamily}.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RegulatingControlView {

    private static final Logger LOG = LoggerFactory.getLogger(RegulatingControlView.class);

    enum RegulatingControlType {
        REGULATING_CONTROL, TAP_CHANGER_CONTROL
    }

    String id;
    RegulatingControlType type;
    boolean discrete;
    boolean controlEnabled;
    double targetDeadband;
    double targetValue;
    String targetValueUnitMultiplier;

    RegulatingControlView(String id, RegulatingControlType type, boolean discrete, boolean controlEnabled,
                          double targetDeadband, double targetValue, String targetValueUnitMultiplier) {
        this.id = id;
        this.type = type;
        this.discrete = discrete;
        this.controlEnabled = controlEnabled;
        this.targetDeadband = targetDeadband;
        this.targetValue = targetValue;
        this.targetValueUnitMultiplier = targetValueUnitMultiplier;
    }

    /** Describe a RegulatingControl or TapChangerControl from the views of all its users. */
    static void describe(List<RegulatingControlView> views, CgmesPropertySink out) {
        describeRegulatingControl(combine(views), out);
    }

    private static void describeRegulatingControl(RegulatingControlView view, CgmesPropertySink out) {
        out.startObject(view.type == RegulatingControlType.TAP_CHANGER_CONTROL ? "TapChangerControl" : "RegulatingControl", view.id)
                .value("RegulatingControl.discrete", view.discrete)
                .value("RegulatingControl.enabled", view.controlEnabled);
        if (CgmesExportUtil.targetDeadbandIsDefined(view.targetDeadband)) {
            out.value("RegulatingControl.targetDeadband", view.targetDeadband);
        }
        out.value("RegulatingControl.targetValue", view.targetValue)
                .enumValue("RegulatingControl.targetValueUnitMultiplier", "UnitMultiplier", view.targetValueUnitMultiplier)
                .endObject();
    }

    /**
     * Combine the descriptions that every user of the same RegulatingControl produces into the single description
     * the object gets: the target, the multiplier and the class of the first view.
     */
    static RegulatingControlView combine(List<RegulatingControlView> rcs) {
        RegulatingControlView combined = rcs.get(0);
        if (rcs.size() > 1 && LOG.isWarnEnabled()) {
            LOG.warn("Multiple views ({}) for regulating control {} are combined", rcs.size(), rcs.get(0).id);
        }
        for (int k = 1; k < rcs.size(); k++) {
            RegulatingControlView current = rcs.get(k);
            if (combinedTargetDeadbandMustBeUpdated(current.targetDeadband, combined.targetDeadband)) {
                combined.targetDeadband = current.targetDeadband;
            }
            if (!combined.discrete && current.discrete) {
                combined.discrete = true;
            }
            if (!combined.controlEnabled && current.controlEnabled) {
                combined.controlEnabled = true;
            }
        }
        return combined;
    }

    private static boolean combinedTargetDeadbandMustBeUpdated(double currentTargetDeadband, double combinedTargetDeadband) {
        return currentTargetDeadband == 0 && (Double.isNaN(combinedTargetDeadband) || combinedTargetDeadband < 0)
                || currentTargetDeadband > 0 && (combinedTargetDeadband == 0 || currentTargetDeadband < combinedTargetDeadband);
    }

    /**
     * The RegulatingControl description of a voltage regulation holder, read from the given state of the network, or
     * {@code null} when the holder has no voltage regulation, or one without a mode in this variant.
     *
     * @param regulation the holder and the name a recorded change of its regulation carries
     */
    static RegulatingControlView of(RegulationRef regulation, String regulatingControlId, CgmesExportContext context,
                                    IidmStateView state) {
        VoltageRegulationHolder<?> regulationHolder = regulation.holder();
        // A regulation without a mode in this variant (created while another variant was the working one) cannot say
        // what its control regulates: it describes no view, and the control is written from its other users, if any
        if (regulation.regulation() == null || regulation.mode(state) == null) {
            return null;
        }
        // Only discrete regulation holders can have a non-zero deadband
        boolean discrete = regulationHolder instanceof ShuntCompensator || regulationHolder instanceof RatioTapChanger;
        double targetDeadband = discrete ? regulation.targetDeadband(state) : 0.0;

        // VoltageRegulation Terminal can be left null to force the use of local target instead of the remote one,
        // thus targets are determined with VoltageRegulationHolder.getRegulatingTargetQ/V
        Quantity quantity;
        double targetValue;
        String mode = getRegulatingControlMode(regulation.mode(state));
        if (REGULATING_CONTROL_REACTIVE_POWER.equals(mode)) {
            // A machine states its target in the generator convention. The import multiplies the target by the sign of
            // the regulating terminal it recorded (AbstractReactiveLimitsOwnerConversion#updateRegulatingControlReactivePower,
            // StaticVarCompensatorConversion#updateRegulatingControl, and for a ratio tap changer the end it sits on,
            // AbstractTransformerConversion#updateRatioTapChanger), so the export applies it as well, unless the
            // equipment model is exported too
            quantity = regulationHolder instanceof Generator ? Quantity.MVAR_MACHINE_TARGET : Quantity.MVAR_TARGET;
            boolean signed = regulationHolder instanceof Generator || regulationHolder instanceof StaticVarCompensator
                    || regulationHolder instanceof RatioTapChanger;
            targetValue = quantity.encode(regulation.regulatingTargetQ(state),
                    signed ? CgmesExportUtil.exportedTerminalSign(regulation.owner(), regulation.end(), context) : 1);
        } else if (REGULATING_CONTROL_VOLTAGE.equals(mode)) {
            quantity = Quantity.KV_TARGET;
            targetValue = regulationHolder instanceof Generator && context.isExportGeneratorsInLocalRegulationMode()
                    ? regulation.localTargetV(state) : regulation.regulatingTargetV(state);
        } else {
            throw new IllegalStateException("Unexpected regulation mode: " + mode);
        }
        // RatioTapChanger VoltageRegulation is exported to a specialized class
        RegulatingControlType type = regulationHolder instanceof RatioTapChanger
                ? RegulatingControlType.TAP_CHANGER_CONTROL : RegulatingControlType.REGULATING_CONTROL;
        return new RegulatingControlView(regulatingControlId, type, discrete, RegulatingControlFamily.flag(regulation, state), targetDeadband,
                targetValue, quantity.multiplier());
    }

    /**
     * The TapChangerControl description of a phase tap changer, read from the given state, or {@code null} when it has
     * none. A phase tap changer limiting current is described with the values it has (a current in Amperes, which
     * carries no sign, multiplier none) when the steady state hypothesis is read against the equipment model its
     * import recorded the control from; with an equipment model of its own the export writes the limit as a
     * CurrentLimit of the regulated terminal and the control keeps upstream's zeros.
     *
     * @param recordedControl whether the import recorded the TapChangerControl of this tap changer
     * @param ref             the tap changer and the name a recorded change of it carries
     */
    static RegulatingControlView ofPhaseTapChanger(PhaseTapChanger ptc, String controlId, boolean recordedControl,
                                                   TapChangerRef ref, CgmesExportContext context, IidmStateView state) {
        PhaseTapChanger.RegulationMode mode = ref.getEnum(state, REGULATION_MODE_SUFFIX,
                PhaseTapChanger.RegulationMode.class, ptc::getRegulationMode);
        boolean realLimiter = mode == PhaseTapChanger.RegulationMode.CURRENT_LIMITER && recordedControl && !context.isExportEquipment();
        if (!realLimiter && (!ptc.hasLoadTapChangingCapabilities() || mode == null)) {
            return null;
        }
        if (!realLimiter && mode == PhaseTapChanger.RegulationMode.CURRENT_LIMITER) {
            // Upstream's zeros, with the multiplier of a power target
            return new RegulatingControlView(controlId, RegulatingControlType.TAP_CHANGER_CONTROL, true, false, 0.0, 0.0,
                    Quantity.MW_TARGET.multiplier());
        }
        // The import multiplies an active power target by the sign of the regulating terminal it recorded
        // (AbstractTransformerConversion#updatePhaseTapChanger), so the export applies it as well, unless the
        // equipment model is exported too
        Quantity quantity = realLimiter ? Quantity.AMPERE_LIMITER : Quantity.MW_TARGET;
        return new RegulatingControlView(controlId, RegulatingControlType.TAP_CHANGER_CONTROL, true,
                RegulatingControlFamily.tapChangerFlag(ref, state),
                ref.getDouble(state, TARGET_DEADBAND_SUFFIX, ptc::getTargetDeadband),
                quantity.encode(ref.getDouble(state, REGULATION_VALUE_SUFFIX, ptc::getRegulationValue),
                        CgmesExportUtil.exportedTerminalSign(ref.transformer(), ref.end(), context)),
                quantity.multiplier());
    }
}
