/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.elements.OperationalLimitConversion;
import com.powsybl.cgmes.conversion.mapping.Block;
import com.powsybl.cgmes.extensions.CimCharacteristics;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.LimitType;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.LoadingLimits;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.OperationalLimitsGroup;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.events.OperationalLimitsInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_CGMES_ORIGINAL_CLASS;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.B;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.B1;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.B2;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.G;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.G1;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.G2;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.HIGH_VOLTAGE_LIMIT;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LIMITS_PREFIX;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOW_VOLTAGE_LIMIT;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.R;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.X;
import static com.powsybl.cgmes.conversion.export.CgmesPropertyBuffer.newUpdates;
import static com.powsybl.commons.util.Result.failure;
import static com.powsybl.commons.util.Result.success;

/**
 * The equipment values a change can carry: operational limit values (equipment data in CGMES 2.4.15, steady state data
 * in CGMES 3), the voltage limits of a voltage level and the impedances of a line or boundary line (equipment data).
 * The full export of the steady state hypothesis writes none of them; a change export writes the value, after the
 * checks that it can be written without misleading the receiver (the structure of a set of limits, a limit shared
 * between both sides of a line, a voltage limit outside the range of its voltage level, the two halves of a shunt
 * admittance).
 *
 * <p>The keys a change is reported under and the blocks the in-place import reads or applies are declared here; the
 * dispatch of the change export, the probes and the capabilities of the in-place import are derived from them.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class LimitFamily extends AbstractFamily {

    /** The CIM class of a voltage limit object, which CGMES has but IIDM does not model. */
    private static final String VOLTAGE_LIMIT = "VoltageLimit";

    /** What {@code TieLineUtil.buildMergedId} joins two identifiers with. */
    private static final String MERGED_ID_SEPARATOR = " + ";
    private static final String MERGED_VOLTAGE_LEVEL_ALIAS_PREFIX =
            Conversion.CGMES_PREFIX_ALIAS_PROPERTIES + "MergedVoltageLevel";

    /** What the in-place import asks about a voltage level, a line and a boundary line. */
    public static final List<String> VOLTAGE_LEVEL_PROBES = List.of(HIGH_VOLTAGE_LIMIT, LOW_VOLTAGE_LIMIT);
    public static final List<String> LINE_PROBES = List.of(R, X, G1, B1);
    public static final List<String> BOUNDARY_LINE_PROBES = List.of(R, X, G, B);
    static final Set<String> VOLTAGE_LEVEL_KEYS = Set.copyOf(VOLTAGE_LEVEL_PROBES);
    static final Set<String> LINE_KEYS = Set.of(R, X, G1, G2, B1, B2);
    static final Set<String> BOUNDARY_LINE_KEYS = Set.copyOf(BOUNDARY_LINE_PROBES);
    /** Attributes an impedance change of a transformer is reported under, which this exporter cuts. */
    private static final Set<String> TRANSFORMER_IMPEDANCE_KEYS = Set.of(R, X, G, B, "ratedU1", "ratedU2", "ratedU", "ratedS");

    // The operational limit values, read by the operationalLimits query in both CIM versions, one value per object
    public static final Block CURRENT_LIMIT = limitBlock("CurrentLimit");
    public static final Block ACTIVE_POWER_LIMIT = limitBlock("ActivePowerLimit");
    public static final Block APPARENT_POWER_LIMIT = limitBlock("ApparentPowerLimit");
    public static final Block VOLTAGE_LIMIT_VALUE = limitBlock(VOLTAGE_LIMIT);
    // Equipment values no update query reads, applied with IIDM setters, each property on its own. An EquivalentBranch
    // states the impedance of both directions, and its import refuses a branch whose r21/x21 differ from r/x
    public static final Block AC_LINE_SEGMENT = new Block(null, List.of(CgmesNames.AC_LINE_SEGMENT), "ACLineSegment.r",
            "ACLineSegment.x", "ACLineSegment.gch", "ACLineSegment.bch");
    public static final Block SERIES_COMPENSATOR = new Block(null, List.of(CgmesNames.SERIES_COMPENSATOR),
            "SeriesCompensator.r", "SeriesCompensator.x");
    public static final Block EQUIVALENT_BRANCH = new Block(null, List.of(CgmesNames.EQUIVALENT_BRANCH), "EquivalentBranch.r",
            "EquivalentBranch.x", "EquivalentBranch.r21", "EquivalentBranch.x21");
    public static final Block VOLTAGE_LEVEL = new Block(null, List.of(CgmesNames.VOLTAGE_LEVEL),
            CgmesNames.VOLTAGE_LEVEL + "." + HIGH_VOLTAGE_LIMIT, CgmesNames.VOLTAGE_LEVEL + "." + LOW_VOLTAGE_LIMIT);

    private final Network network;
    /** Built on first use, so that a change set without limits never pays for the walk it costs. */
    private CgmesLimitIndex limitIndex;

    LimitFamily(Network network, CgmesExportContext context, IidmStateView state, Scope scope) {
        super(context, state, scope);
        this.network = network;
    }

    private static Block limitBlock(String className) {
        return new Block("operationalLimits", List.of(className), className + ".value");
    }

    /** The refusal of an impedance change of a transformer, whose impedances cannot be mapped to CGMES ends. */
    static Optional<String> transformerImpedanceRefusal(Identifiable<?> identifiable, String attribute) {
        // A three windings transformer reports the attributes of a leg as "leg1.r", "leg2.x" and so on
        String impedanceAttribute = attribute.matches("^leg[123]\\..+")
                ? attribute.substring(attribute.indexOf('.') + 1) : attribute;
        return (identifiable instanceof TwoWindingsTransformer || identifiable instanceof ThreeWindingsTransformer)
                && TRANSFORMER_IMPEDANCE_KEYS.contains(impedanceAttribute)
                ? Optional.of("no CGMES property corresponds to " + identifiable.getType() + "." + attribute
                        + " (transformer impedances cannot be mapped to CGMES ends, see docs)")
                : Optional.empty();
    }

    /**
     * Whether an identifier is one powsybl built out of two, and therefore names no single CGMES object.
     *
     * <p>{@code TieLineUtil.buildMergedId} joins two identifiers with {@value #MERGED_ID_SEPARATOR} whenever one
     * IIDM object stands for two &mdash; the classic case being two halves of a line that meet at a boundary
     * point. Such an identifier is not a CGMES master resource identifier: the CGMES model that produced it writes
     * it URL-encoded, and a difference model receiver resolves the identifier a statement names exactly as it is
     * written. Exporting a change of such an object would hand over a document the receiver has to refuse, which
     * is a rejection that belongs here, before anything is written.</p>
     */
    private static boolean isMergedIdentifier(String id) {
        return id.contains(MERGED_ID_SEPARATOR);
    }

    /**
     * Which loading limit an attribute key of a change log names.
     *
     * @param slot   where the limit lands: owner, side prefix, type, group identifier (taken from the payload of the
     *               event by the compactor) and the acceptable duration named by the key, {@code -1} for the
     *               permanent limit and for a key naming the whole object
     * @param group  that group, read live
     * @param limits the loading limits of that group and type, read live, {@code null} when there are none
     * @param member whether the key names a single limit rather than the whole {@code LoadingLimits} object
     */
    private record LimitRef(CgmesLimitIndex.LimitSlot slot, OperationalLimitsGroup group, LoadingLimits limits,
                            boolean member) {
    }

    /**
     * The attribute keys a limit change is reported under, once the compactor has added the group and the duration:
     * {@code limits1_CURRENT@<group>}, {@code limits2_ACTIVE_POWER.permanentLimit@<group>},
     * {@code limits_CURRENT.temporaryLimit.value@<group>@<duration>}.
     */
    private static final Pattern LIMIT_ATTRIBUTE = Pattern.compile(
            "^(limits[123]?)_([A-Z_]+?)(\\.permanentLimit|\\.temporaryLimit\\.value)?@(.*)$");

    private static final String STRUCTURAL_LIMIT_CHANGE = "adding or removing operational limits is a structural"
            + " change (new OperationalLimit/OperationalLimitType objects); apply it with the regular import";
    private static final String LIMIT_SELECTION_CHANGE = "selecting, creating or removing an operational limits"
            + " group has no counterpart in CGMES (all limit sets are exchanged)";

    private static Optional<LimitRef> parseLimitRef(Identifiable<?> owner, String attributeKey) {
        Matcher matcher = LIMIT_ATTRIBUTE.matcher(attributeKey);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String prefix = matcher.group(1);
        LimitType type;
        try {
            type = LimitType.valueOf(matcher.group(2));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String suffix = matcher.group(3);
        String tail = matcher.group(4);
        String groupId = tail;
        int duration = -1;
        if (CgmesLimitIndex.TEMPORARY_LIMIT_VALUE_SUFFIX.equals(suffix)) {
            int separator = tail.lastIndexOf(EventCompactor.KEY_SEPARATOR.charAt(0));
            if (separator < 0) {
                return Optional.empty();
            }
            groupId = tail.substring(0, separator);
            try {
                duration = Integer.parseInt(tail.substring(separator + 1));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        OperationalLimitsGroup group = groupOf(owner, prefix, groupId);
        if (group == null) {
            return Optional.empty();
        }
        return Optional.of(new LimitRef(new CgmesLimitIndex.LimitSlot(owner, prefix, type, groupId, duration), group,
                loadingLimits(group, type), suffix != null));
    }

    private static OperationalLimitsGroup groupOf(Identifiable<?> owner, String prefix, String groupId) {
        return switch (owner) {
            case ThreeWindingsTransformer transformer -> {
                ThreeWindingsTransformer.Leg leg = TapChangerAndShuntFamily.leg(transformer, prefix.substring(LIMITS_PREFIX.length()));
                yield leg == null ? null : leg.getOperationalLimitsGroup(groupId).orElse(null);
            }
            case Branch<?> branch -> switch (prefix) {
                case LIMITS_PREFIX + "1" -> branch.getOperationalLimitsGroup1(groupId).orElse(null);
                case LIMITS_PREFIX + "2" -> branch.getOperationalLimitsGroup2(groupId).orElse(null);
                default -> null;
            };
            case BoundaryLine boundaryLine -> LIMITS_PREFIX.equals(prefix)
                    ? boundaryLine.getOperationalLimitsGroup(groupId).orElse(null) : null;
            default -> null;
        };
    }

    private static LoadingLimits loadingLimits(OperationalLimitsGroup group, LimitType type) {
        return switch (type) {
            case CURRENT -> group.getCurrentLimits().orElse(null);
            case ACTIVE_POWER -> group.getActivePowerLimits().orElse(null);
            case APPARENT_POWER -> group.getApparentPowerLimits().orElse(null);
            default -> null;
        };
    }

    /** The terminal of the side a limits group belongs to, which is what the CGMES limit set is attached to. */
    private static Terminal terminalOf(LimitRef ref) {
        return switch (ref.slot().owner()) {
            case ThreeWindingsTransformer transformer -> {
                ThreeWindingsTransformer.Leg transformerLeg =
                        TapChangerAndShuntFamily.leg(transformer, ref.slot().prefix().substring(LIMITS_PREFIX.length()));
                yield transformerLeg == null ? null : transformerLeg.getTerminal();
            }
            case Branch<?> branch -> (LIMITS_PREFIX + "1").equals(ref.slot().prefix())
                    ? branch.getTerminal1() : branch.getTerminal2();
            case BoundaryLine boundaryLine -> boundaryLine.getTerminal();
            default -> null;
        };
    }

    /**
     * The block describing a change of one loading limit.
     *
     * <p>CGMES has one object per limit value, and IIDM one {@code LoadingLimits} object holding the permanent limit
     * and every temporary limit of a side. The whole {@code LoadingLimits} is the consistency group: an event names
     * either one member of it or the object as a whole, and in both cases every value of the object is described, so
     * that the two spellings of the same change produce the same statements and a receiver never sees half a set.</p>
     *
     * <p>CIM 2.4.15 holds limit values in the equipment profile and CIM 3 in the steady state hypothesis, which is
     * the only thing the version decides here.</p>
     */
    Result<CgmesPropertyBuffer, String> loadingLimitsUpdates(Identifiable<?> owner, String attributeKey,
                                                                     Object replacedLimits) {
        Optional<LimitRef> parsed = parseLimitRef(owner, attributeKey);
        if (parsed.isEmpty()) {
            // Either a selection change, whose payload carries no group and therefore leaves the key unrefined, or a
            // group that no longer exists. Both are structural.
            return failure(attributeKey.contains(EventCompactor.KEY_SEPARATOR)
                    ? STRUCTURAL_LIMIT_CHANGE : LIMIT_SELECTION_CHANGE);
        }
        LimitRef ref = parsed.get();
        if (ref.limits() == null) {
            return failure(STRUCTURAL_LIMIT_CHANGE);
        }
        String wholeKey = ref.slot().wholeKey();
        LoadingLimits live = ref.limits();
        LoadingLimits replaced = replacedLoadingLimits(ref, replacedLimits);
        if (isStructuralChange(ref, replacedLimits, replaced, live)) {
            return failure(STRUCTURAL_LIMIT_CHANGE);
        }
        SortedSet<Integer> durations = state.getLimitDurations(owner, wholeKey, () -> durationsOf(live));
        boolean hasPermanentLimit = state.hasPermanentLimit(owner, wholeKey,
                () -> !Double.isNaN(live.getPermanentLimit()));
        if (!durations.equals(durationsOf(live)) || hasPermanentLimit != !Double.isNaN(live.getPermanentLimit())) {
            return failure(STRUCTURAL_LIMIT_CHANGE);
        }
        CgmesSubset subset = equipmentValueSubset();
        String className = ref.slot().className();
        String property = className + ".value";
        CgmesPropertyBuffer buffer = new CgmesPropertyBuffer();
        List<Integer> members = new ArrayList<>();
        if (hasPermanentLimit) {
            members.add(-1);
        }
        members.addAll(durations);
        for (int duration : members) {
            String limitId;
            switch (limitId(ref, className, duration)) {
                case Result.Success(String id) -> limitId = id;
                case Result.Failure(String reason) -> {
                    if (mustTravel(ref, replaced, live, duration)) {
                        return failure(reason);
                    }
                    // A limit the CGMES model does not hold, and that this change does not touch: the receiver keeps
                    // the value its own import gave it, see mustTravel
                    continue;
                }
            }
            double value = state.getLimitValue(owner, wholeKey, ref.slot().memberKey(duration), duration,
                    () -> liveValue(live, duration));
            if (!Double.isFinite(value) || value < 0) {
                return failure("limit values must be finite and >= 0, but " + limitId + " would be " + value);
            }
            Optional<String> shared = sharedLimitIdFailure(limitId, value);
            if (shared.isPresent()) {
                return failure(shared.get());
            }
            buffer.object(subset, className, cgmesId(limitId)).value(property, value);
        }
        return success(buffer);
    }

    /**
     * The set of loading limits a whole-object replacement replaced, taken from the payload of the event itself.
     *
     * <p>Read from the event rather than from the state view, because the structural check below has to hold for
     * <em>every</em> export, including the partial steady state hypothesis one, which runs against the live network
     * only and would otherwise compare the live object with itself.</p>
     *
     * @return the replaced object, or {@code null} when the event is not a whole-object replacement
     */
    private static LoadingLimits replacedLoadingLimits(LimitRef ref, Object replacedLimits) {
        if (ref.member() || !(replacedLimits instanceof OperationalLimitsInfo info)) {
            return null;
        }
        return info.value() instanceof LoadingLimits limits ? limits : null;
    }

    /**
     * Whether a whole-object replacement changed the <em>structure</em> of the set of loading limits, that is the
     * presence of the permanent limit or the set of acceptable durations. CGMES models each of them as an object, so
     * such a change cannot travel as a value and must not be written as if only the remaining limits had moved.
     */
    private static boolean isStructuralChange(LimitRef ref, Object replacedLimits, LoadingLimits replaced,
                                              LoadingLimits live) {
        if (ref.member() || !(replacedLimits instanceof OperationalLimitsInfo info)) {
            return false;
        }
        if (info.value() == null || replaced == null) {
            // The limits were created by this change, or replaced by something that is not a set of loading limits
            return true;
        }
        return !durationsOf(replaced).equals(durationsOf(live))
                || Double.isNaN(replaced.getPermanentLimit()) != Double.isNaN(live.getPermanentLimit());
    }

    /**
     * Whether a limit that has no CGMES object has to travel anyway, in which case the change is refused.
     *
     * <p>The CGMES import synthesizes a permanent limit for a set that has none (see
     * {@code missing-permanent-limit-percentage}), and such a limit is not in the CGMES model at all: nothing can be
     * written about it, and the receiving side derives its own from the same option. Leaving it out is therefore the
     * right answer for every limit this change does not touch. It is <em>not</em> the right answer for the limit the
     * change actually names, nor for one a whole-object replacement moved, because that value would be silently
     * lost.</p>
     */
    private static boolean mustTravel(LimitRef ref, LoadingLimits replaced, LoadingLimits live, int duration) {
        if (ref.member()) {
            return ref.slot().duration() == duration;
        }
        if (replaced == null) {
            // Not a whole-object replacement this translator can compare: keep the refusal
            return true;
        }
        return Double.compare(liveValue(replaced, duration), liveValue(live, duration)) != 0;
    }

    private static SortedSet<Integer> durationsOf(LoadingLimits limits) {
        SortedSet<Integer> durations = new TreeSet<>();
        limits.getTemporaryLimits().forEach(temporaryLimit -> durations.add(temporaryLimit.getAcceptableDuration()));
        return durations;
    }

    private static double liveValue(LoadingLimits limits, int acceptableDuration) {
        if (acceptableDuration < 0) {
            return limits.getPermanentLimit();
        }
        LoadingLimits.TemporaryLimit temporaryLimit = limits.getTemporaryLimit(acceptableDuration);
        return temporaryLimit == null ? Double.NaN : temporaryLimit.getValue();
    }

    /**
     * The identifier of the CGMES OperationalLimit holding one limit value.
     *
     * <p>It is the master resource identifier the import stored on the operational limits group. A network that was
     * not imported from CGMES has none, and then the identifier a full equipment export would write is used instead:
     * a receiver that read such an export stores exactly those identifiers, so the two sides agree. A network that
     * <em>was</em> imported from CGMES but whose limit carries no identifier is a limit that was created afterwards,
     * for which no CGMES object exists at all.</p>
     */
    private Result<String, String> limitId(LimitRef ref, String className, int duration) {
        String propertyName = Conversion.getOperationalLimitPropertyName(className, duration < 0,
                Math.max(duration, 0), CgmesNames.OPERATIONAL_LIMIT);
        String stored = ref.group().getProperty(propertyName);
        if (stored != null && !stored.isEmpty()) {
            return success(stored);
        }
        if (network.getExtension(CimCharacteristics.class) != null) {
            return failure("limit " + ref.slot().groupId() + "/" + className + "/"
                    + (duration < 0 ? "patl" : "tatl " + duration) + " of " + ref.slot().owner().getId()
                    + " has no CGMES OperationalLimit id");
        }
        Terminal terminal = terminalOf(ref);
        if (terminal == null) {
            return failure("limit " + ref.slot().groupId() + "/" + className + " of " + ref.slot().owner().getId()
                    + " belongs to no terminal, so no CGMES OperationalLimit id can be derived");
        }
        String setId = EquipmentExport.operationalLimitSetId(ref.group(),
                CgmesExportUtil.getTerminalId(terminal, context), context);
        return success(EquipmentExport.operationalLimitId(setId, className, duration, context));
    }

    /**
     * Refuse a change of a limit whose CGMES object describes more than one IIDM limit unless all of them take the
     * same value.
     *
     * <p>A CGMES OperationalLimitSet attached to the equipment of a line applies to both of its ends, and the import
     * creates a group on each side storing the same OperationalLimit identifiers. One CGMES value cannot say two
     * things, so changing one side alone is not exportable.</p>
     */
    private Optional<String> sharedLimitIdFailure(String limitId, double value) {
        List<CgmesLimitIndex.LimitSlot> slots = limitIndex().slots(limitId);
        if (slots.size() <= 1) {
            return Optional.empty();
        }
        for (CgmesLimitIndex.LimitSlot slot : slots) {
            OperationalLimitsGroup group = groupOf(slot.owner(), slot.prefix(), slot.groupId());
            LoadingLimits limits = group == null ? null : loadingLimits(group, slot.type());
            if (limits == null) {
                return sharedLimitFailure(limitId);
            }
            double slotValue = state.getLimitValue(slot.owner(), slot.wholeKey(), slot.memberKey(), slot.duration(),
                    () -> liveValue(limits, slot.duration()));
            if (Double.compare(slotValue, value) != 0) {
                return sharedLimitFailure(limitId);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> sharedLimitFailure(String limitId) {
        return Optional.of("OperationalLimit " + limitId + " applies to the whole equipment in CGMES; change both"
                + " sides to the same value");
    }

    /** The profile an equipment value that the steady state carries in CGMES 3 is written to. */
    private CgmesSubset equipmentValueSubset() {
        return context.getCimVersion() == 16 ? CgmesSubset.EQUIPMENT : CgmesSubset.STEADY_STATE_HYPOTHESIS;
    }

    /** The index of the CGMES limit identifiers of this network, built on first use and shared by both directions. */
    private CgmesLimitIndex limitIndex() {
        if (limitIndex == null) {
            limitIndex = CgmesLimitIndex.of(network);
        }
        return limitIndex;
    }

    // Voltage level limits

    /**
     * The block describing a change of the high or the low voltage limit of a voltage level.
     *
     * <p>CGMES models these in two ways, and which one a network uses is decided by what its import found. When the
     * equipment model carried {@code VoltageLimit} objects, the import stored their identifiers on the voltage level
     * and the update reads their values back, so the change is written on every one of them. When it did not, the
     * limits live on the {@code VoltageLevel} itself, which is also where the full equipment export writes them.</p>
     *
     * <p>A receiver only accepts a {@code VoltageLimit} value strictly inside the range the
     * {@code VoltageLevel.highVoltageLimit} and {@code .lowVoltageLimit} attributes declare, and silently keeps its
     * previous value otherwise, so a value outside that range is refused here rather than written and lost.</p>
     */
    Result<CgmesPropertyBuffer, String> voltageLimitUpdates(VoltageLevel voltageLevel, String attribute) {
        boolean high = HIGH_VOLTAGE_LIMIT.equals(attribute);
        double value = state.getDouble(voltageLevel, attribute,
                high ? voltageLevel::getHighVoltageLimit : voltageLevel::getLowVoltageLimit);
        if (!Double.isFinite(value)) {
            return failure("voltage limits must be finite, but " + voltageLevel.getId() + "." + attribute
                    + " would be " + value);
        }
        double other = state.getDouble(voltageLevel, high ? LOW_VOLTAGE_LIMIT : HIGH_VOLTAGE_LIMIT,
                high ? voltageLevel::getLowVoltageLimit : voltageLevel::getHighVoltageLimit);
        double newHigh = high ? value : other;
        double newLow = high ? other : value;
        if (Double.isFinite(newHigh) && Double.isFinite(newLow) && newHigh <= newLow) {
            return failure("the receiver only accepts voltage limits strictly inside the VoltageLevel range ("
                    + newLow + ", " + newHigh + ")");
        }
        String property = high ? Conversion.PROPERTY_OPERATIONAL_LIMIT_HIGH_VOLTAGE_LIMIT
                : Conversion.PROPERTY_OPERATIONAL_LIMIT_LOW_VOLTAGE_LIMIT;
        String ids = voltageLevel.getProperty(property);
        if (ids == null || ids.isEmpty()) {
            return voltageLevelAttributeUpdates(voltageLevel, high, value);
        }
        Double rangeHigh = OperationalLimitConversion.getNormalVoltageLimitValue(voltageLevel,
                Conversion.PROPERTY_HIGH_VOLTAGE_LIMIT);
        Double rangeLow = OperationalLimitConversion.getNormalVoltageLimitValue(voltageLevel,
                Conversion.PROPERTY_LOW_VOLTAGE_LIMIT);
        if (rangeHigh != null && value >= rangeHigh || rangeLow != null && value <= rangeLow) {
            double current = high ? voltageLevel.getHighVoltageLimit() : voltageLevel.getLowVoltageLimit();
            if (Double.compare(value, current) != 0) {
                // The value being described is not the one the network holds, so this is the state before the change:
                // the limit the change started from is not one a VoltageLimit object can express
                return failure("the current " + attribute + " of " + voltageLevel.getId() + " is the VoltageLevel"
                        + " attribute itself, no VoltageLimit object binds it, so the change cannot be undone through"
                        + " VoltageLimit values");
            }
            return failure("the receiver only accepts voltage limits strictly inside the VoltageLevel range ("
                    + rangeLow + ", " + rangeHigh + ")");
        }
        CgmesSubset subset = equipmentValueSubset();
        CgmesPropertyBuffer buffer = new CgmesPropertyBuffer();
        for (String id : ids.split(";")) {
            if (!id.isEmpty()) {
                buffer.object(subset, VOLTAGE_LIMIT, cgmesId(id)).value(VOLTAGE_LIMIT + ".value", value);
            }
        }
        return success(buffer);
    }

    /**
     * A voltage level with no {@code VoltageLimit} objects carries its limits as attributes of the
     * {@code VoltageLevel}, which belong to the equipment profile in every CIM version.
     */
    private Result<CgmesPropertyBuffer, String> voltageLevelAttributeUpdates(VoltageLevel voltageLevel, boolean high,
                                                                            double value) {
        if (voltageLevel.getAliasFromType(MERGED_VOLTAGE_LEVEL_ALIAS_PREFIX + "1").isPresent()
                || voltageLevel.getAliasFromType(MERGED_VOLTAGE_LEVEL_ALIAS_PREFIX + "2").isPresent()) {
            return failure("merged voltage levels have several CGMES VoltageLevel objects");
        }
        return success(newUpdates(CgmesSubset.EQUIPMENT, CgmesNames.VOLTAGE_LEVEL, cgmesId(voltageLevel))
                .value(CgmesNames.VOLTAGE_LEVEL + "." + (high ? HIGH_VOLTAGE_LIMIT : LOW_VOLTAGE_LIMIT), value)
                .updates());
    }

    // Branch impedances (equipment profile)

    /**
     * The block describing a change of an impedance of a line.
     *
     * <p>IIDM splits the shunt admittance of a line in two halves, one per end, while CGMES holds a single total that
     * the import splits equally. A line whose two halves differ therefore has no CGMES spelling, and neither has a
     * zero impedance line inside one voltage level, which the import turns into a switch.</p>
     */
    Result<CgmesPropertyBuffer, String> lineImpedanceUpdates(Line line, String attribute) {
        String originalClass = line.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS, CgmesNames.AC_LINE_SEGMENT);
        if (!SwitchAndTerminalFamily.isCgmesBranchClass(originalClass)) {
            return failure(originalClass + " " + line.getId()
                    + " is represented as a switch in CGMES or is not a CGMES branch, so it carries no impedance");
        }
        if (isMergedIdentifier(line.getId())) {
            return failure("the identifier of line " + line.getId() + " is not a single CGMES master resource"
                    + " identifier: it is a pair of identifiers joined by \"" + MERGED_ID_SEPARATOR + "\", which"
                    + " is how powsybl names an object that stands for two, and a difference model names one"
                    + " existing CGMES object per statement");
        }
        if (CgmesNames.EQUIVALENT_BRANCH.equals(originalClass)
                && line.getTerminal1().getVoltageLevel().getNominalV()
                    != line.getTerminal2().getVoltageLevel().getNominalV()) {
            // EquivalentBranchConversion folds the ideal ratio between the two nominal voltages into the IIDM
            // parameters, so the IIDM value is not the value the CGMES file holds
            return failure("the import transforms EquivalentBranch parameters between nominal voltages");
        }
        double r = state.getDouble(line, R, line::getR);
        double x = state.getDouble(line, X, line::getX);
        Optional<String> problem = seriesImpedanceProblem(originalClass, r, x);
        if (problem.isPresent()) {
            return failure(problem.get());
        }
        if (r == 0 && x == 0 && line.getTerminal1().getVoltageLevel() == line.getTerminal2().getVoltageLevel()) {
            return failure("a zero-impedance branch inside one voltage level becomes a switch on import");
        }
        if (R.equals(attribute) || X.equals(attribute)) {
            return success(seriesImpedanceUpdates(originalClass, cgmesId(line), attribute,
                    R.equals(attribute) ? r : x));
        }
        if (!CgmesNames.AC_LINE_SEGMENT.equals(originalClass)) {
            return failure("a " + originalClass + " has no shunt admittance in CGMES");
        }
        boolean conductance = G1.equals(attribute) || G2.equals(attribute);
        double side1 = state.getDouble(line, conductance ? G1 : B1, conductance ? line::getG1 : line::getB1);
        double side2 = state.getDouble(line, conductance ? G2 : B2, conductance ? line::getG2 : line::getB2);
        if (!Double.isFinite(side1) || !Double.isFinite(side2)) {
            return failure("impedance values must be finite (r, x >= 0)");
        }
        if (!symmetric(side1, side2)) {
            return failure("CGMES ACLineSegment holds one total gch/bch split equally on import; g1 == g2 and"
                    + " b1 == b2 are required");
        }
        return success(newUpdates(CgmesSubset.EQUIPMENT, originalClass, cgmesId(line))
                .rawLiteral(CgmesNames.AC_LINE_SEGMENT + "." + (conductance ? "gch" : "bch"),
                        CgmesExportUtil.formatExact(side1 + side2))
                .updates());
    }

    /**
     * The block describing a change of an impedance of a boundary line, whose CGMES branch carries the shunt
     * admittance undivided: the import sets {@code g = gch} and {@code b = bch} one to one.
     */
    Result<CgmesPropertyBuffer, String> boundaryLineImpedanceUpdates(BoundaryLine boundaryLine, String attribute) {
        String originalClass = boundaryLine.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS, CgmesNames.AC_LINE_SEGMENT);
        if (!SwitchAndTerminalFamily.isCgmesBranchClass(originalClass)) {
            return failure(originalClass + " " + boundaryLine.getId()
                    + " is represented as a switch in CGMES or is not a CGMES branch, so it carries no impedance");
        }
        double r = state.getDouble(boundaryLine, R, boundaryLine::getR);
        double x = state.getDouble(boundaryLine, X, boundaryLine::getX);
        Optional<String> problem = seriesImpedanceProblem(originalClass, r, x);
        if (problem.isPresent()) {
            return failure(problem.get());
        }
        if (R.equals(attribute) || X.equals(attribute)) {
            return success(seriesImpedanceUpdates(originalClass, cgmesId(boundaryLine), attribute,
                    R.equals(attribute) ? r : x));
        }
        if (!CgmesNames.AC_LINE_SEGMENT.equals(originalClass)) {
            return failure("a " + originalClass + " has no shunt admittance in CGMES");
        }
        boolean conductance = G.equals(attribute);
        double value = state.getDouble(boundaryLine, attribute,
                conductance ? boundaryLine::getG : boundaryLine::getB);
        if (!Double.isFinite(value)) {
            return failure("impedance values must be finite (r, x >= 0)");
        }
        return success(newUpdates(CgmesSubset.EQUIPMENT, CgmesNames.AC_LINE_SEGMENT, cgmesId(boundaryLine))
                .rawLiteral(CgmesNames.AC_LINE_SEGMENT + "." + (conductance ? "gch" : "bch"),
                        CgmesExportUtil.formatExact(value))
                .updates());
    }

    /**
     * The statement describing one series impedance value of a CGMES branch.
     *
     * <p>An {@code EquivalentBranch} states the impedance of both directions, and its import refuses a branch whose
     * {@code r21}/{@code x21} differ from {@code r}/{@code x} &mdash; such a branch is not converted at all. IIDM
     * holds a single value, so both directions are written; a base file that has no {@code r21} is unaffected,
     * because the import reads an absent one as {@code r}.</p>
     */
    private static CgmesPropertyBuffer seriesImpedanceUpdates(String originalClass, String subjectId,
                                                              String attribute, double value) {
        CgmesPropertyBuffer.ObjectUpdate update = newUpdates(CgmesSubset.EQUIPMENT, originalClass, subjectId)
                .rawLiteral(originalClass + "." + attribute, CgmesExportUtil.formatExact(value));
        if (CgmesNames.EQUIVALENT_BRANCH.equals(originalClass)) {
            update.rawLiteral(originalClass + "." + attribute + "21", CgmesExportUtil.formatExact(value));
        }
        return update.updates();
    }

    /**
     * Why a resistance and a reactance cannot be written as they stand, empty when they can. A SeriesCompensator may be
     * capacitive: CGMES and its import accept a negative reactance (owner decision O3).
     */
    private static Optional<String> seriesImpedanceProblem(String originalClass, double r, double x) {
        boolean capacitive = CgmesNames.SERIES_COMPENSATOR.equals(originalClass) && x < 0;
        return !Double.isFinite(r) || !Double.isFinite(x) || r < 0 || x < 0 && !capacitive
                ? Optional.of("impedance values must be finite (r, x >= 0; x < 0 for a SeriesCompensator only)")
                : Optional.empty();
    }

    /**
     * Whether the two halves of a shunt admittance are the same value, to a relative tolerance of 1e-9.
     *
     * <p>Relative rather than absolute, because a susceptance is routinely of the order of 1e-4 S and an absolute
     * tolerance of 1e-9 S would accept a relative difference of 1e-5 there: the receiver splits the total equally, so
     * it would hold a value that differs from the sender's in its fifth significant digit while the mapping promises
     * an exact round trip. Two halves that came from one {@code gch/2} are bit-identical, so nothing legitimate is
     * lost by being strict.</p>
     */
    private static boolean symmetric(double side1, double side2) {
        double difference = Math.abs(side1 - side2);
        return difference <= 1e-9 * Math.max(Math.abs(side1), Math.abs(side2));
    }
}
