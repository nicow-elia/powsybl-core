/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.LimitType;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.OperationalLimitsGroup;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which IIDM loading limits a CGMES OperationalLimit identifier stands for, for a whole network.
 *
 * <p>The CGMES import stores the master resource identifier of every converted OperationalLimit as a property of the
 * {@code OperationalLimitsGroup} it landed in, named {@code CGMES.OperationalLimit_<Class>_patl} or
 * {@code CGMES.OperationalLimit_<Class>_tatl_<duration>}. This class turns that scattered bookkeeping into a lookup
 * in both directions:</p>
 * <ul>
 *     <li>the change export needs it to find out whether the identifier it is about to write is shared, which happens
 *     when the CGMES set was attached to the <em>equipment</em> of a line rather than to one of its terminals: the
 *     import then creates a group on each side and stores the same identifiers in both. A single CGMES value cannot
 *     describe two different IIDM values, so a change of one side alone cannot be exported;</li>
 *     <li>the difference model import needs the inverse direction, to find out which IIDM objects a statement about
 *     an OperationalLimit touches.</li>
 * </ul>
 *
 * <p>Built by one pass over the lines, two and three windings transformers and boundary lines of a network. Tie
 * lines are not walked: their limits <em>are</em> the limits of their boundary lines, which is also where a change of
 * them is recorded.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class CgmesLimitIndex {

    /** The attribute name prefix of the limits of side 1 of a branch, as {@code OperationalLimitsGroupsImpl} spells it. */
    public static final String LIMITS_PREFIX = "limits";

    private static final String OPERATIONAL_LIMIT_PROPERTY_PREFIX =
            Conversion.CGMES_PREFIX_ALIAS_PROPERTIES + CgmesNames.OPERATIONAL_LIMIT + "_";

    /** {@code CGMES.OperationalLimit_<Class>_patl} and {@code ..._tatl_<duration>}. */
    private static final Pattern OPERATIONAL_LIMIT_PROPERTY = Pattern.compile(
            Pattern.quote(OPERATIONAL_LIMIT_PROPERTY_PREFIX) + "(\\w+?)_(?:patl|tatl_(\\d+))$");

    /**
     * One place a CGMES OperationalLimit value lands in IIDM.
     *
     * @param owner    the branch, three windings transformer or boundary line a change of the limit is recorded on
     * @param prefix   the attribute name prefix of the side, {@code limits1}, {@code limits2}, {@code limits3} or
     *                 {@code limits} for a boundary line
     * @param type     which of the three kinds of loading limits this is
     * @param groupId  the identifier of the operational limits group holding the limit
     * @param duration the acceptable duration of the temporary limit, or {@code -1} for the permanent one
     */
    public record LimitSlot(Identifiable<?> owner, String prefix, LimitType type, String groupId, int duration) {

        /** The attribute key of a replacement of the whole {@code LoadingLimits} object this limit belongs to. */
        public String wholeKey() {
            return prefix + "_" + type + EventCompactor.KEY_SEPARATOR + groupId;
        }

        /** The attribute key of a change of this very limit value. */
        public String memberKey() {
            return duration < 0
                    ? prefix + "_" + type + ".permanentLimit" + EventCompactor.KEY_SEPARATOR + groupId
                    : prefix + "_" + type + ".temporaryLimit.value" + EventCompactor.KEY_SEPARATOR + groupId
                            + EventCompactor.KEY_SEPARATOR + duration;
        }

        /** The CIM class of the OperationalLimit, which is also the prefix of the property naming its value. */
        public String className() {
            return switch (type) {
                case CURRENT -> CgmesNames.CURRENT_LIMIT;
                case ACTIVE_POWER -> CgmesNames.ACTIVE_POWER_LIMIT;
                case APPARENT_POWER -> CgmesNames.APPARENT_POWER_LIMIT;
                default -> throw new IllegalStateException("Not a loading limit type: " + type);
            };
        }
    }

    private final Map<String, List<LimitSlot>> slotsByLimitId;

    private CgmesLimitIndex(Map<String, List<LimitSlot>> slotsByLimitId) {
        this.slotsByLimitId = slotsByLimitId;
    }

    /** Build the index of a network, by one pass over the equipment that can carry loading limits. */
    public static CgmesLimitIndex of(Network network) {
        Objects.requireNonNull(network);
        Map<String, List<LimitSlot>> index = new LinkedHashMap<>();
        network.getLines().forEach(line -> addBranch(index, line));
        network.getTwoWindingsTransformers().forEach(transformer -> addBranch(index, transformer));
        network.getThreeWindingsTransformers().forEach(transformer -> addThreeWindings(index, transformer));
        network.getBoundaryLines().forEach(boundaryLine -> addBoundaryLine(index, boundaryLine));
        Map<String, List<LimitSlot>> copy = new HashMap<>();
        index.forEach((id, slots) -> copy.put(id, List.copyOf(slots)));
        return new CgmesLimitIndex(Map.copyOf(copy));
    }

    /** Every IIDM loading limit the given CGMES OperationalLimit identifier stands for, empty when it is unknown. */
    public List<LimitSlot> slots(String limitId) {
        return slotsByLimitId.getOrDefault(limitId, List.of());
    }

    /** Every CGMES OperationalLimit identifier the network remembers. */
    public Set<String> limitIds() {
        return slotsByLimitId.keySet();
    }

    private static void addBranch(Map<String, List<LimitSlot>> index, Branch<?> branch) {
        branch.getOperationalLimitsGroups1().forEach(group -> addGroup(index, branch, LIMITS_PREFIX + "1", group));
        branch.getOperationalLimitsGroups2().forEach(group -> addGroup(index, branch, LIMITS_PREFIX + "2", group));
    }

    private static void addThreeWindings(Map<String, List<LimitSlot>> index, ThreeWindingsTransformer transformer) {
        for (ThreeWindingsTransformer.Leg leg : transformer.getLegs()) {
            String prefix = LIMITS_PREFIX + leg.getSide().getNum();
            leg.getOperationalLimitsGroups().forEach(group -> addGroup(index, transformer, prefix, group));
        }
    }

    private static void addBoundaryLine(Map<String, List<LimitSlot>> index, BoundaryLine boundaryLine) {
        boundaryLine.getOperationalLimitsGroups()
                .forEach(group -> addGroup(index, boundaryLine, LIMITS_PREFIX, group));
    }

    private static void addGroup(Map<String, List<LimitSlot>> index, Identifiable<?> owner, String prefix,
                                 OperationalLimitsGroup group) {
        for (String propertyName : group.getPropertyNames()) {
            Matcher matcher = OPERATIONAL_LIMIT_PROPERTY.matcher(propertyName);
            if (!matcher.matches()) {
                continue;
            }
            LimitType type = limitType(matcher.group(1));
            if (type == null) {
                continue;
            }
            int duration = matcher.group(2) == null ? -1 : Integer.parseInt(matcher.group(2));
            String limitId = group.getProperty(propertyName);
            if (limitId != null && !limitId.isEmpty()) {
                index.computeIfAbsent(limitId, id -> new ArrayList<>())
                        .add(new LimitSlot(owner, prefix, type, group.getId(), duration));
            }
        }
    }

    private static LimitType limitType(String className) {
        return switch (className) {
            case CgmesNames.CURRENT_LIMIT -> LimitType.CURRENT;
            case CgmesNames.ACTIVE_POWER_LIMIT -> LimitType.ACTIVE_POWER;
            case CgmesNames.APPARENT_POWER_LIMIT -> LimitType.APPARENT_POWER;
            default -> null;
        };
    }

    /** Whether an IIDM object can carry loading limits at all, which is what the index walked. */
    public static boolean holdsLoadingLimits(Identifiable<?> identifiable) {
        return identifiable instanceof Line || identifiable instanceof TwoWindingsTransformer
                || identifiable instanceof ThreeWindingsTransformer || identifiable instanceof BoundaryLine;
    }
}
