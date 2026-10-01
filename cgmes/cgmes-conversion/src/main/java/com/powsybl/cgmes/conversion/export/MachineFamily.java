/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Battery;
import com.powsybl.iidm.network.EnergySource;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Injection;
import com.powsybl.iidm.network.ReactiveLimitsHolder;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.regulation.RegulationMode;

import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_GENERATING_UNIT;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_NORMAL_PF;
import static com.powsybl.cgmes.conversion.export.CgmesExportUtil.obtainCalculatedSynchronousMachineKind;
import static com.powsybl.cgmes.conversion.export.CgmesExportUtil.obtainCurve;
import static com.powsybl.cgmes.conversion.export.CgmesExportUtil.obtainSynchronousMachineKind;

/**
 * The CGMES machines of the steady state hypothesis: the operating mode of a machine and the GeneratingUnit of a
 * generator or a battery, read from a state of the network, for every export of the steady state hypothesis.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class MachineFamily {

    private static final String OPERATING_MODE_GENERATOR = "generator";
    private static final String OPERATING_MODE_MOTOR = "motor";
    private static final String OPERATING_MODE_CONDENSER = "condenser";

    /**
     * The operating mode of a machine, with the regulation read from the given state of the network.
     *
     * <p>Package private so that the change export writes the same operating mode as the full export.</p>
     */
    static <I extends ReactiveLimitsHolder & Injection<I>> String obtainOperatingMode(I i, double minP, double maxP,
                                                                                      double targetP, IidmStateView state) {
        String calculatedKind = obtainCalculatedSynchronousMachineKind(minP, maxP, obtainCurve(i), i instanceof Battery || i instanceof Generator gen && gen.isCondenser());
        return obtainOperatingMode(targetP, i, calculatedKind, state);
    }

    private static String obtainOperatingMode(double targetP, Injection<?> injection, String calculatedKind, IidmStateView state) {
        if (targetP < 0) {
            return OPERATING_MODE_MOTOR;
        } else if (targetP > 0) {
            return OPERATING_MODE_GENERATOR;
        } else {
            if (isOperatingAsACondenser(injection, state) && calculatedKind.toLowerCase().contains(OPERATING_MODE_CONDENSER)) {
                return OPERATING_MODE_CONDENSER;
            } else {
                if (calculatedKind.toLowerCase().contains(OPERATING_MODE_GENERATOR)) {
                    return OPERATING_MODE_GENERATOR;
                } else if (calculatedKind.toLowerCase().contains(OPERATING_MODE_MOTOR)) {
                    return OPERATING_MODE_MOTOR;
                } else {
                    return OPERATING_MODE_CONDENSER;
                }
            }
        }
    }

    private static boolean isOperatingAsACondenser(Injection<?> injection, IidmStateView state) {
        switch (injection) {
            case Generator generator -> {
                RegulationRef regulation = RegulationRef.of(generator);
                double regulatingTargetQ = regulation.regulatingTargetQ(state);
                return regulation.isRegulatingWithMode(RegulationMode.VOLTAGE, state) && !Double.isNaN(regulation.localTargetV(state))
                    || !Double.isNaN(regulatingTargetQ) && regulatingTargetQ != 0;
            }
            case Battery battery -> {
                return battery.isRegulatingWithMode(RegulationMode.VOLTAGE) && !Double.isNaN(battery.getLocalTargetV())
                    || !Double.isNaN(battery.getRegulatingTargetQ()) && battery.getRegulatingTargetQ() != 0;
            }
            default -> throw new IllegalStateException("Unexpected value: " + injection);
        }
    }

    /**
     * The GeneratingUnit whose participation factor describes the given injection, with the participation factor
     * read from the given state of the network, or {@code null} when it has none.
     *
     * <p>Package private so that the change export writes the same participation factor as the full export.</p>
     */
    static <I extends ReactiveLimitsHolder & Injection<I>> GeneratingUnit generatingUnitForGeneratorAndBatteries(
            I i, CgmesExportContext context, IidmStateView state) {
        String kind = obtainSynchronousMachineKind(i);
        if (!OPERATING_MODE_CONDENSER.equals(kind) && (i.getExtension(ActivePowerControl.class) != null || i.hasProperty(PROPERTY_NORMAL_PF))) {
            GeneratingUnit gu = new GeneratingUnit();
            gu.id = context.getNamingStrategy().getCgmesIdFromProperty(i, PROPERTY_GENERATING_UNIT);
            if (i.getExtension(ActivePowerControl.class) != null) {
                ActivePowerControl<I> activePowerControl = i.getExtension(ActivePowerControl.class);
                gu.participationFactor = state.getExtensionDouble(i, ActivePowerControl.NAME,
                        CgmesChangeTranslator.PARTICIPATION_FACTOR, activePowerControl::getParticipationFactor);
            } else {
                gu.participationFactor = Double.parseDouble(i.getProperty(PROPERTY_NORMAL_PF));
            }
            gu.className = generatingUnitClassname(i);
            return gu;
        }
        return null;
    }

    private static String generatingUnitClassname(Injection<?> i) {
        if (i instanceof Generator generator) {
            EnergySource energySource = generator.getEnergySource();
            if (energySource == EnergySource.HYDRO) {
                return "HydroGeneratingUnit";
            } else if (energySource == EnergySource.NUCLEAR) {
                return "NuclearGeneratingUnit";
            } else if (energySource == EnergySource.SOLAR) {
                return "SolarGeneratingUnit";
            } else if (energySource == EnergySource.THERMAL) {
                return "ThermalGeneratingUnit";
            } else if (energySource == EnergySource.WIND) {
                return "WindGeneratingUnit";
            } else {
                return "GeneratingUnit";
            }
        }
        if (i instanceof Battery) {
            return "HydroGeneratingUnit"; // TODO export battery differently in CGMES 3.0
        }
        throw new PowsyblException("Unexpected class for " + i.getId() + " using generating units: " + i.getClass());
    }

    static final class GeneratingUnit {
        String id;
        String className;
        double participationFactor;
    }

    private MachineFamily() {
    }
}
