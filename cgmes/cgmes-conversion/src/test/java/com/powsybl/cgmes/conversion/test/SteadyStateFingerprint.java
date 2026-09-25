/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.LineCommutatedConverter;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.LoadingLimits;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.OperationalLimitsGroup;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.TapChanger;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ReferencePriority;
import com.powsybl.iidm.network.extensions.RemoteReactivePowerControl;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Every value a steady state hypothesis can carry, read out of a network as a flat sorted map.
 *
 * <p>Two networks that agree on this fingerprint agree on everything a CGMES steady state hypothesis describes, which
 * is what a difference model round trip has to prove. It is deliberately narrower than a full IIDM comparison: the
 * identifiers of the metadata models, the case date and the state variables differ between a sender and a receiver
 * for reasons that have nothing to do with the difference, and comparing them would drown the assertion.</p>
 *
 * <p>The attributes are exactly the ones the change translator maps, so a value that appears here and not in the
 * mapping would be a gap in the exporter and a value in the mapping that does not appear here would be an untested
 * one.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class SteadyStateFingerprint {

    /** How many significant digits a double keeps, so that a round trip through a decimal text is stable. */
    private static final MathContext PRECISION = new MathContext(9);

    private SteadyStateFingerprint() {
    }

    /** The fingerprint of a network, keyed by {@code <identifier>.<attribute>}. */
    public static SortedMap<String, String> of(Network network) {
        SortedMap<String, String> values = new TreeMap<>();
        network.getSwitches().forEach(sw -> put(values, sw, "open", sw.isOpen()));
        network.getDcSwitches().forEach(sw -> put(values, sw, "open", sw.isOpen()));
        network.getLoads().forEach(load -> load(values, load));
        network.getGenerators().forEach(generator -> generator(values, generator));
        network.getBoundaryLines().forEach(boundaryLine -> boundaryLine(values, boundaryLine));
        network.getShuntCompensators().forEach(shunt -> shunt(values, shunt));
        network.getStaticVarCompensators().forEach(svc -> staticVarCompensator(values, svc));
        network.getTwoWindingsTransformers().forEach(transformer -> twoWindings(values, transformer));
        network.getThreeWindingsTransformers().forEach(transformer -> threeWindings(values, transformer));
        network.getHvdcLines().forEach(line -> hvdc(values, line));
        network.getVscConverterStations().forEach(station -> vsc(values, station));
        network.getLccConverterStations().forEach(station -> put(values, station, "powerFactor", station.getPowerFactor()));
        network.getVoltageSourceConverters().forEach(converter -> detailedVsc(values, converter));
        network.getLineCommutatedConverters().forEach(converter -> detailedLcc(values, converter));
        // The connection state of every terminal: it is what a difference carrying cim:ACDCTerminal.connected
        // changes, and what the fictitious switches of a node/breaker import stand for
        network.getConnectableStream().forEach(connectable -> terminals(values, connectable));
        // Equipment values a difference model can carry since work package 5
        network.getVoltageLevels().forEach(voltageLevel -> voltageLevelLimits(values, voltageLevel));
        network.getLines().forEach(line -> lineImpedance(values, line));
        network.getBoundaryLines().forEach(boundaryLine -> boundaryLineImpedance(values, boundaryLine));
        network.getLines().forEach(line -> branchLimits(values, line));
        network.getTwoWindingsTransformers().forEach(transformer -> branchLimits(values, transformer));
        network.getThreeWindingsTransformers().forEach(transformer -> threeWindingsLimits(values, transformer));
        network.getBoundaryLines().forEach(boundaryLine -> limitsGroups(values, boundaryLine, "limits",
                boundaryLine.getOperationalLimitsGroups(),
                boundaryLine.getSelectedOperationalLimitsGroupId().orElse(null)));
        return values;
    }

    private static void voltageLevelLimits(SortedMap<String, String> values, VoltageLevel voltageLevel) {
        put(values, voltageLevel, "highVoltageLimit", voltageLevel.getHighVoltageLimit());
        put(values, voltageLevel, "lowVoltageLimit", voltageLevel.getLowVoltageLimit());
    }

    private static void lineImpedance(SortedMap<String, String> values, Line line) {
        put(values, line, "r", line.getR());
        put(values, line, "x", line.getX());
        put(values, line, "g1", line.getG1());
        put(values, line, "b1", line.getB1());
        put(values, line, "g2", line.getG2());
        put(values, line, "b2", line.getB2());
    }

    private static void boundaryLineImpedance(SortedMap<String, String> values, BoundaryLine boundaryLine) {
        put(values, boundaryLine, "r", boundaryLine.getR());
        put(values, boundaryLine, "x", boundaryLine.getX());
        put(values, boundaryLine, "g", boundaryLine.getG());
        put(values, boundaryLine, "b", boundaryLine.getB());
    }

    private static void branchLimits(SortedMap<String, String> values, Branch<?> branch) {
        limitsGroups(values, branch, "limits1", branch.getOperationalLimitsGroups1(),
                branch.getSelectedOperationalLimitsGroupId1().orElse(null));
        limitsGroups(values, branch, "limits2", branch.getOperationalLimitsGroups2(),
                branch.getSelectedOperationalLimitsGroupId2().orElse(null));
    }

    private static void threeWindingsLimits(SortedMap<String, String> values, ThreeWindingsTransformer transformer) {
        for (ThreeWindingsTransformer.Leg leg : transformer.getLegs()) {
            limitsGroups(values, transformer, "limits" + leg.getSide().getNum(), leg.getOperationalLimitsGroups(),
                    leg.getSelectedOperationalLimitsGroupId().orElse(null));
        }
    }

    /** Every loading limit of every group of one side, plus which group is selected. */
    private static void limitsGroups(SortedMap<String, String> values, Identifiable<?> owner, String prefix,
                                     Collection<OperationalLimitsGroup> groups, String selectedId) {
        if (groups.isEmpty()) {
            return;
        }
        put(values, owner, prefix + ".selected", String.valueOf(selectedId));
        for (OperationalLimitsGroup group : groups) {
            String groupPrefix = prefix + "." + group.getId() + ".";
            group.getCurrentLimits().ifPresent(limits -> loadingLimits(values, owner, groupPrefix + "CURRENT", limits));
            group.getActivePowerLimits()
                    .ifPresent(limits -> loadingLimits(values, owner, groupPrefix + "ACTIVE_POWER", limits));
            group.getApparentPowerLimits()
                    .ifPresent(limits -> loadingLimits(values, owner, groupPrefix + "APPARENT_POWER", limits));
        }
    }

    private static void loadingLimits(SortedMap<String, String> values, Identifiable<?> owner, String prefix,
                                      LoadingLimits limits) {
        put(values, owner, prefix + ".patl", limits.getPermanentLimit());
        limits.getTemporaryLimits().forEach(temporaryLimit -> {
            String key = prefix + ".tatl." + temporaryLimit.getAcceptableDuration();
            put(values, owner, key, temporaryLimit.getValue());
            put(values, owner, key + ".name", temporaryLimit.getName());
        });
    }

    /** The connection state of every terminal of a connectable, numbered as IIDM orders them. */
    private static void terminals(SortedMap<String, String> values, Connectable<?> connectable) {
        List<? extends Terminal> terminals = connectable.getTerminals();
        for (int side = 0; side < terminals.size(); side++) {
            put(values, connectable, "terminal" + (side + 1) + ".connected", terminals.get(side).isConnected());
        }
    }

    /** The entries in which two fingerprints differ, as {@code key -> [left, right]}. */
    public static Map<String, String[]> diff(SortedMap<String, String> left, SortedMap<String, String> right) {
        Map<String, String[]> differences = new LinkedHashMap<>();
        TreeMap<String, String> keys = new TreeMap<>(left);
        keys.putAll(right);
        keys.keySet().forEach(key -> {
            String a = left.get(key);
            String b = right.get(key);
            if (a == null ? b != null : !a.equals(b)) {
                differences.put(key, new String[] {a, b});
            }
        });
        return differences;
    }

    private static void load(SortedMap<String, String> values, Load load) {
        put(values, load, "p0", load.getP0());
        put(values, load, "q0", load.getQ0());
    }

    private static void generator(SortedMap<String, String> values, Generator generator) {
        put(values, generator, "targetP", generator.getTargetP());
        put(values, generator, "targetQ", generator.getTargetQ());
        put(values, generator, "targetV", generator.getTargetV());
        put(values, generator, "voltageRegulatorOn", generator.isVoltageRegulatorOn());
        ActivePowerControl<Generator> activePowerControl = generator.getExtension(ActivePowerControl.class);
        if (activePowerControl != null) {
            put(values, generator, "participationFactor", activePowerControl.getParticipationFactor());
        }
        RemoteReactivePowerControl control = generator.getExtension(RemoteReactivePowerControl.class);
        if (control != null) {
            put(values, generator, "remoteTargetQ", control.getTargetQ());
            put(values, generator, "remoteEnabled", control.isEnabled());
        }
        put(values, generator, "referencePriority", ReferencePriority.get(generator));
    }

    private static void boundaryLine(SortedMap<String, String> values, BoundaryLine boundaryLine) {
        put(values, boundaryLine, "p0", boundaryLine.getP0());
        put(values, boundaryLine, "q0", boundaryLine.getQ0());
        BoundaryLine.Generation generation = boundaryLine.getGeneration();
        if (generation != null) {
            put(values, boundaryLine, "generationTargetP", generation.getTargetP());
            put(values, boundaryLine, "generationTargetQ", generation.getTargetQ());
            put(values, boundaryLine, "generationTargetV", generation.getTargetV());
            put(values, boundaryLine, "generationVoltageRegulationOn", generation.isVoltageRegulationOn());
        }
    }

    private static void shunt(SortedMap<String, String> values, ShuntCompensator shunt) {
        put(values, shunt, "sectionCount", shunt.getSectionCount());
        put(values, shunt, "targetV", shunt.getTargetV());
        put(values, shunt, "targetDeadband", shunt.getTargetDeadband());
        put(values, shunt, "voltageRegulatorOn", shunt.isVoltageRegulatorOn());
    }

    private static void staticVarCompensator(SortedMap<String, String> values, StaticVarCompensator svc) {
        put(values, svc, "voltageSetpoint", svc.getVoltageSetpoint());
        put(values, svc, "reactivePowerSetpoint", svc.getReactivePowerSetpoint());
        put(values, svc, "regulating", svc.isRegulating());
        put(values, svc, "regulationMode", String.valueOf(svc.getRegulationMode()));
    }

    private static void twoWindings(SortedMap<String, String> values, TwoWindingsTransformer transformer) {
        tapChanger(values, transformer, "ratioTapChanger", transformer.getOptionalRatioTapChanger().orElse(null));
        tapChanger(values, transformer, "phaseTapChanger", transformer.getOptionalPhaseTapChanger().orElse(null));
    }

    private static void threeWindings(SortedMap<String, String> values, ThreeWindingsTransformer transformer) {
        int end = 0;
        for (ThreeWindingsTransformer.Leg leg : transformer.getLegs()) {
            end++;
            tapChanger(values, transformer, "ratioTapChanger" + end, leg.getOptionalRatioTapChanger().orElse(null));
            tapChanger(values, transformer, "phaseTapChanger" + end, leg.getOptionalPhaseTapChanger().orElse(null));
        }
    }

    private static void tapChanger(SortedMap<String, String> values, Identifiable<?> owner, String prefix,
                                   TapChanger<?, ?, ?, ?> tapChanger) {
        if (tapChanger == null) {
            return;
        }
        put(values, owner, prefix + ".tapPosition", tapChanger.getTapPosition());
        put(values, owner, prefix + ".regulating", tapChanger.isRegulating());
        put(values, owner, prefix + ".targetDeadband", tapChanger.getTargetDeadband());
        if (tapChanger instanceof RatioTapChanger ratio) {
            put(values, owner, prefix + ".regulationValue", ratio.getRegulationValue());
            put(values, owner, prefix + ".regulationMode", String.valueOf(ratio.getRegulationMode()));
        } else if (tapChanger instanceof PhaseTapChanger phase) {
            put(values, owner, prefix + ".regulationValue", phase.getRegulationValue());
            put(values, owner, prefix + ".regulationMode", String.valueOf(phase.getRegulationMode()));
        }
    }

    private static void hvdc(SortedMap<String, String> values, HvdcLine line) {
        put(values, line, "activePowerSetpoint", line.getActivePowerSetpoint());
        put(values, line, "convertersMode", String.valueOf(line.getConvertersMode()));
    }

    private static void vsc(SortedMap<String, String> values, VscConverterStation station) {
        put(values, station, "voltageSetpoint", station.getVoltageSetpoint());
        put(values, station, "reactivePowerSetpoint", station.getReactivePowerSetpoint());
        put(values, station, "voltageRegulatorOn", station.isVoltageRegulatorOn());
    }

    private static void detailedVsc(SortedMap<String, String> values, VoltageSourceConverter converter) {
        put(values, converter, "targetP", converter.getTargetP());
        put(values, converter, "targetVdc", converter.getTargetVdc());
        put(values, converter, "controlMode", String.valueOf(converter.getControlMode()));
        put(values, converter, "voltageRegulatorOn", converter.isVoltageRegulatorOn());
        put(values, converter, "voltageSetpoint", converter.getVoltageSetpoint());
        put(values, converter, "reactivePowerSetpoint", converter.getReactivePowerSetpoint());
    }

    private static void detailedLcc(SortedMap<String, String> values, LineCommutatedConverter converter) {
        put(values, converter, "targetP", converter.getTargetP());
        put(values, converter, "targetVdc", converter.getTargetVdc());
        put(values, converter, "controlMode", String.valueOf(converter.getControlMode()));
        put(values, converter, "powerFactor", converter.getPowerFactor());
    }

    private static void put(SortedMap<String, String> values, Identifiable<?> owner, String attribute, double value) {
        values.put(owner.getId() + "." + attribute, format(value));
    }

    private static void put(SortedMap<String, String> values, Identifiable<?> owner, String attribute, int value) {
        values.put(owner.getId() + "." + attribute, Integer.toString(value));
    }

    private static void put(SortedMap<String, String> values, Identifiable<?> owner, String attribute, boolean value) {
        values.put(owner.getId() + "." + attribute, Boolean.toString(value));
    }

    private static void put(SortedMap<String, String> values, Identifiable<?> owner, String attribute, String value) {
        values.put(owner.getId() + "." + attribute, value);
    }

    /** Doubles are rounded, so that a value that went through a decimal text compares equal to the one it came from. */
    private static String format(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return Double.toString(value);
        }
        return new BigDecimal(value).round(PRECISION).stripTrailingZeros().toPlainString();
    }
}
