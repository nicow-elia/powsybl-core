/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.CgmesReports;
import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.diff.FastRoutePlan.DirectStatement;
import com.powsybl.cgmes.conversion.diff.FastRoutePlan.PlannedModel;
import com.powsybl.cgmes.conversion.diff.FastRoutePlan.TypedObject;
import com.powsybl.cgmes.conversion.export.CgmesLimitIndex;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.OperationalLimitsGroup;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.VoltageLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies the equipment statements of a difference model that the CGMES update workflow cannot express.
 *
 * <p>Almost everything a difference model carries reaches the network through the ordinary update workflow: the
 * statements are written into a synthetic instance file and the SPARQL queries of the update catalogue read them
 * back. A handful of equipment values have no such query &mdash; nothing in the update path reads
 * {@code ACLineSegment.r}, {@code ACLineSegment.bch} or {@code VoltageLevel.highVoltageLimit} &mdash; and writing
 * them into the document would silently change nothing. They are applied here instead, with the plain IIDM setters,
 * mirroring exactly what {@code AbstractBranchConversion.convertBranch} and {@code VoltageLevelConversion} do when
 * they read the same values out of a full equipment model.</p>
 *
 * <p>Every value was validated while the plan was built, so a setter throwing here would be a defect rather than a
 * refusal; a network that a partly applied update left in an unexpected state is the same caveat any steady state
 * hypothesis update carries.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class DirectEqApplier {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirectEqApplier.class);

    private static final String GCH = "gch";
    private static final String BCH = "bch";

    private DirectEqApplier() {
    }

    /**
     * Apply the statements the update workflow could not, and synchronize the equipment values the update workflow
     * keeps as a fallback.
     *
     * @param plan the plan that was just applied, which knows both the direct statements and the limit statements
     *             the update workflow carried
     */
    static void apply(Network network, FastRoutePlan plan, ReportNode reportNode) {
        List<DirectStatement> statements = plan.directStatements();
        if (!statements.isEmpty()) {
            applyStatements(network, statements);
            CgmesReports.appliedDirectEqStatementsReport(reportNode, statements.size());
        }
        EqNormalValueSync.sync(network, plan);
    }

    private static void applyStatements(Network network, List<DirectStatement> statements) {
        // The voltage limits of one voltage level constrain each other, so they are applied together and in the
        // order that never makes IIDM see a low limit above a high one
        List<DirectStatement> voltageLimits = new ArrayList<>();
        for (DirectStatement direct : statements) {
            if (direct.statement().property().equals(FastRoutePlan.VOLTAGE_LEVEL_HIGH_LIMIT)
                    || direct.statement().property().equals(FastRoutePlan.VOLTAGE_LEVEL_LOW_LIMIT)) {
                voltageLimits.add(direct);
            } else {
                applyImpedance(direct);
            }
        }
        applyVoltageLimits(network, voltageLimits);
    }

    /**
     * One impedance value of a line or of a boundary line.
     *
     * <p>CGMES holds a single total shunt admittance, which the import of a line splits equally over its two ends
     * and the import of a boundary line takes as it stands, so the two are written back the same way here.</p>
     */
    private static void applyImpedance(DirectStatement direct) {
        String property = direct.statement().property();
        String attribute = property.substring(property.indexOf('.') + 1);
        double value = direct.value();
        switch (direct.subject().owner()) {
            case Line line -> applyLineImpedance(line, attribute, value, property);
            case BoundaryLine boundaryLine -> applyBoundaryLineImpedance(boundaryLine, attribute, value, property);
            default -> throw new PowsyblException("A " + direct.subject().owner().getType()
                    + " carries no CGMES branch impedance: " + property + " of "
                    + direct.statement().subjectId());
        }
    }

    private static void applyLineImpedance(Line line, String attribute, double value, String property) {
        switch (attribute) {
            // r21 and x21 are the other direction of an EquivalentBranch, which IIDM holds as a single value
            case "r", "r21" -> line.setR(value);
            case "x", "x21" -> line.setX(value);
            // The import splits the total equally over the two ends, see AbstractBranchConversion.convertBranch
            case GCH -> line.setG1(value / 2).setG2(value / 2);
            case BCH -> line.setB1(value / 2).setB2(value / 2);
            default -> throw new PowsyblException("Unhandled branch property " + property);
        }
    }

    private static void applyBoundaryLineImpedance(BoundaryLine boundaryLine, String attribute, double value,
                                                   String property) {
        switch (attribute) {
            case "r", "r21" -> boundaryLine.setR(value);
            case "x", "x21" -> boundaryLine.setX(value);
            // At a boundary the import takes the total as it stands, see convertToBoundaryLine
            case GCH -> boundaryLine.setG(value);
            case BCH -> boundaryLine.setB(value);
            default -> throw new PowsyblException("Unhandled branch property " + property);
        }
    }

    /**
     * The high and low limits of the voltage levels a difference touches.
     *
     * <p>{@code VoltageLevel.setHighVoltageLimit} refuses a value below the current low limit, so when a difference
     * moves the whole range downwards the low limit has to be set first. The CGMES range properties are updated
     * alongside, because a later steady state hypothesis update filters its voltage limit candidates against
     * them.</p>
     */
    private static void applyVoltageLimits(Network network, List<DirectStatement> statements) {
        Map<String, Double> high = new LinkedHashMap<>();
        Map<String, Double> low = new LinkedHashMap<>();
        for (DirectStatement direct : statements) {
            String id = direct.subject().owner().getId();
            if (FastRoutePlan.VOLTAGE_LEVEL_HIGH_LIMIT.equals(direct.statement().property())) {
                high.put(id, direct.value());
            } else {
                low.put(id, direct.value());
            }
        }
        for (String id : orderedIds(high, low)) {
            VoltageLevel voltageLevel = network.getVoltageLevel(id);
            Double newHigh = high.get(id);
            Double newLow = low.get(id);
            if (newHigh != null && (newLow == null || newHigh >= voltageLevel.getLowVoltageLimit()
                    || Double.isNaN(voltageLevel.getLowVoltageLimit()))) {
                setHigh(voltageLevel, newHigh);
                if (newLow != null) {
                    setLow(voltageLevel, newLow);
                }
            } else {
                if (newLow != null) {
                    setLow(voltageLevel, newLow);
                }
                if (newHigh != null) {
                    setHigh(voltageLevel, newHigh);
                }
            }
        }
    }

    private static List<String> orderedIds(Map<String, Double> high, Map<String, Double> low) {
        List<String> ids = new ArrayList<>(high.keySet());
        low.keySet().stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        return ids;
    }

    private static void setHigh(VoltageLevel voltageLevel, double value) {
        voltageLevel.setHighVoltageLimit(value);
        voltageLevel.setProperty(Conversion.PROPERTY_HIGH_VOLTAGE_LIMIT, String.valueOf(value));
    }

    private static void setLow(VoltageLevel voltageLevel, double value) {
        voltageLevel.setLowVoltageLimit(value);
        voltageLevel.setProperty(Conversion.PROPERTY_LOW_VOLTAGE_LIMIT, String.valueOf(value));
    }

    /**
     * Keeps the equipment values a later update falls back on in step with the values a CGMES 2.4.15 difference just
     * wrote.
     *
     * <p>In CGMES 2.4.15 a limit value <em>is</em> an equipment value: the difference changed the equipment model,
     * not a steady state hypothesis. The importer remembers the equipment value of every limit as the group property
     * {@code CGMES.normalValue_<Class>_patl|_tatl_<d>} and falls back on it whenever a later update carries no value
     * for that limit. Without this synchronization such an update would quietly restore the value the difference
     * replaced. In CGMES 3 the normal value is a separate equipment attribute that a steady state difference does not
     * touch, so nothing is synchronized there.</p>
     */
    private static final class EqNormalValueSync {

        private EqNormalValueSync() {
        }

        static void sync(Network network, FastRoutePlan plan) {
            List<CgmesStatement> limitStatements = cim16EquipmentLimitStatements(plan);
            if (limitStatements.isEmpty()) {
                return;
            }
            // The subject resolution has already built one whenever a limit was resolved through it
            CgmesLimitIndex index = plan.limitIndex() != null ? plan.limitIndex() : CgmesLimitIndex.of(network);
            for (CgmesStatement statement : limitStatements) {
                syncOne(network, index, statement);
            }
        }

        private static List<CgmesStatement> cim16EquipmentLimitStatements(FastRoutePlan plan) {
            List<CgmesStatement> statements = new ArrayList<>();
            for (PlannedModel model : plan.models()) {
                if (model.header().subset() != CgmesSubset.EQUIPMENT
                        || !CgmesNamespace.CIM_16_NAMESPACE.equals(model.header().cimNamespace())) {
                    continue;
                }
                for (TypedObject object : model.objects()) {
                    object.statements().stream()
                            .filter(statement -> statement.property().endsWith(".value"))
                            .forEach(statements::add);
                }
            }
            return statements;
        }

        private static void syncOne(Network network, CgmesLimitIndex index, CgmesStatement statement) {
            String id = DifferenceModelParser.normalizeId(statement.subjectId());
            List<CgmesLimitIndex.LimitSlot> slots = index.slots(id);
            if (!slots.isEmpty()) {
                slots.forEach(slot -> syncLoadingLimit(slot, statement.value()));
                return;
            }
            syncVoltageLimit(network, id, statement.value());
        }

        private static void syncLoadingLimit(CgmesLimitIndex.LimitSlot slot, String value) {
            OperationalLimitsGroup group = groupOf(slot);
            if (group == null) {
                LOGGER.debug("The operational limits group {} of {} is gone, its normal value is not synchronized",
                        slot.groupId(), slot.owner().getId());
                return;
            }
            group.setProperty(Conversion.getOperationalLimitPropertyName(slot.className(), slot.duration() < 0,
                    Math.max(slot.duration(), 0), CgmesNames.NORMAL_VALUE), value);
        }

        private static OperationalLimitsGroup groupOf(CgmesLimitIndex.LimitSlot slot) {
            return switch (slot.owner()) {
                case ThreeWindingsTransformer transformer -> {
                    ThreeWindingsTransformer.Leg leg = leg(transformer, slot);
                    yield leg == null ? null : leg.getOperationalLimitsGroup(slot.groupId()).orElse(null);
                }
                case Branch<?> branch -> (CgmesLimitIndex.LIMITS_PREFIX + "1").equals(slot.prefix())
                        ? branch.getOperationalLimitsGroup1(slot.groupId()).orElse(null)
                        : branch.getOperationalLimitsGroup2(slot.groupId()).orElse(null);
                case BoundaryLine boundaryLine -> boundaryLine.getOperationalLimitsGroup(slot.groupId()).orElse(null);
                default -> null;
            };
        }

        private static ThreeWindingsTransformer.Leg leg(ThreeWindingsTransformer transformer,
                                                        CgmesLimitIndex.LimitSlot slot) {
            return switch (slot.prefix().substring(CgmesLimitIndex.LIMITS_PREFIX.length())) {
                case "1" -> transformer.getLeg1();
                case "2" -> transformer.getLeg2();
                case "3" -> transformer.getLeg3();
                default -> null;
            };
        }

        /** A voltage limit: the equipment fallback is the aggregate the voltage level now holds. */
        private static void syncVoltageLimit(Network network, String id, String value) {
            for (VoltageLevel voltageLevel : network.getVoltageLevels()) {
                if (contains(voltageLevel, Conversion.PROPERTY_OPERATIONAL_LIMIT_HIGH_VOLTAGE_LIMIT, id)) {
                    voltageLevel.setProperty(Conversion.PROPERTY_NORMAL_VALUE_HIGH_VOLTAGE_LIMIT,
                            String.valueOf(voltageLevel.getHighVoltageLimit()));
                } else if (contains(voltageLevel, Conversion.PROPERTY_OPERATIONAL_LIMIT_LOW_VOLTAGE_LIMIT, id)) {
                    voltageLevel.setProperty(Conversion.PROPERTY_NORMAL_VALUE_LOW_VOLTAGE_LIMIT,
                            String.valueOf(voltageLevel.getLowVoltageLimit()));
                } else {
                    continue;
                }
                LOGGER.debug("Synchronized the equipment voltage limit of {} after applying {} = {}",
                        voltageLevel.getId(), id, value);
            }
        }

        private static boolean contains(VoltageLevel voltageLevel, String property, String id) {
            String ids = voltageLevel.getProperty(property);
            if (ids == null) {
                return false;
            }
            for (String candidate : ids.split(";")) {
                if (DifferenceModelParser.normalizeId(candidate).equals(id)) {
                    return true;
                }
            }
            return false;
        }
    }
}
