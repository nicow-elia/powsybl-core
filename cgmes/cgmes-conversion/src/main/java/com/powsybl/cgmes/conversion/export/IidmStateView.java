/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.export.EventCompactor.CompactedChanges;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.LoadingLimits;
import com.powsybl.iidm.network.OperationalLimits;
import com.powsybl.iidm.network.events.OperationalLimitsInfo;
import com.powsybl.iidm.network.events.PermanentLimitInfo;
import com.powsybl.iidm.network.events.TemporaryLimitInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Which state of a network a change translation reads its values from.
 *
 * <p>A difference model has to describe the state a network was in before a change set as well as the state it is in
 * now. Running the mapping twice, once against the live network and once against an overlay of the values the change
 * log remembers, is what makes both descriptions come out of the very same code: derived quantities such as the
 * operating mode of a machine, the sign of a setpoint or the combined state of a shared regulating control are then
 * computed consistently in both directions, which they would not be if the reverse statements were patched together
 * from event payloads afterwards.</p>
 *
 * <p>Only the values the change log can speak about go through a view. Structure &mdash; identifiers, aliases, CGMES
 * properties, regulating terminals, nominal voltages, limits &mdash; is read live in both passes, because a change
 * set of steady state hypothesis values does not touch it.</p>
 *
 * <p>Every getter takes the live read as a supplier, so that {@link #LIVE} costs one call and nothing else, and the
 * full steady state hypothesis export, which shares those mappings, is not affected at all.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
interface IidmStateView {

    /** The state the network is in now: every read goes straight to the network. */
    IidmStateView LIVE = new LiveStateView();

    /**
     * The state the network was in before the given change set.
     *
     * <p>An attribute the change set touched reads the value the first change of it replaced; every other attribute
     * reads live, because a value nothing changed is the same before and after. That holds as long as the recorder
     * was attached for the whole change set and the network was not modified behind its back, which is the
     * precondition a difference export documents.</p>
     */
    static IidmStateView before(CompactedChanges changes) {
        return new PreviousStateView(changes);
    }

    double getDouble(Identifiable<?> identifiable, String attribute, DoubleSupplier live);

    int getInt(Identifiable<?> identifiable, String attribute, IntSupplier live);

    boolean getBoolean(Identifiable<?> identifiable, String attribute, BooleanSupplier live);

    <E extends Enum<E>> E getEnum(Identifiable<?> identifiable, String attribute, Class<E> type, Supplier<E> live);

    double getExtensionDouble(Identifiable<?> identifiable, String extensionName, String attribute, DoubleSupplier live);

    boolean getExtensionBoolean(Identifiable<?> identifiable, String extensionName, String attribute, BooleanSupplier live);

    /**
     * @param valueWhenOldIsNull what an unrecorded previous value means for this attribute, for instance zero for a
     *                           reference priority, which is what an absent one is worth
     */
    int getExtensionInt(Identifiable<?> identifiable, String extensionName, String attribute,
                        int valueWhenOldIsNull, IntSupplier live);

    /**
     * Refuse to describe the previous state of an extension the change set created, because there is none: the
     * extension did not exist, and nothing recorded what its values would have been.
     *
     * @throws UnreconstructibleStateException if this view describes a state before the creation
     */
    void requireExtensionNotCreated(Identifiable<?> identifiable, String extensionName);

    /**
     * The value of one loading limit, permanent or temporary, of one {@code LoadingLimits} object.
     *
     * <p>A change log describes such a value in two ways: member by member, through the attribute of the permanent
     * or of the temporary limit, and as a whole, when the {@code LoadingLimits} object itself was replaced. The two
     * are separate attribute keys although they speak about the same value, so the state before the change set is
     * the one remembered by whichever of them the log holds <em>first</em>.</p>
     *
     * @param owner              the branch, three windings transformer or boundary line a change is recorded on
     * @param wholeKey           the key of a replacement of the whole object, {@code "<prefix>_<TYPE>@<group>"}
     * @param memberKey          the key of this member, {@code "<prefix>_<TYPE>.permanentLimit@<group>"} or
     *                           {@code "<prefix>_<TYPE>.temporaryLimit.value@<group>@<duration>"}
     * @param acceptableDuration the acceptable duration of the temporary limit, or {@code -1} for the permanent one
     * @param live               the value as the network currently holds it
     * @throws UnreconstructibleStateException if the limits did not exist before the change set
     */
    double getLimitValue(Identifiable<?> owner, String wholeKey, String memberKey, int acceptableDuration,
                         DoubleSupplier live);

    /**
     * The acceptable durations of the temporary limits of one {@code LoadingLimits} object.
     *
     * <p>Adding or removing a temporary limit is a structural change in CGMES, so a mapping compares the durations
     * before the change set with the ones the network holds now and refuses when they differ.</p>
     */
    SortedSet<Integer> getLimitDurations(Identifiable<?> owner, String wholeKey, Supplier<SortedSet<Integer>> live);

    /** Whether the {@code LoadingLimits} object had a permanent limit, which is a structural property in CGMES too. */
    boolean hasPermanentLimit(Identifiable<?> owner, String wholeKey, BooleanSupplier live);

    /**
     * The attributes of the overlay that no mapping read, as {@code id.attributeKey}. Empty for {@link #LIVE}.
     *
     * <p>A key that stays unread means a change whose previous value never reached the reverse statements, which is
     * either a read that was not routed through this view or an attribute that is genuinely not observable in the
     * exported profile. It is a test hook and a debug log, never a failure.</p>
     */
    Set<String> unconsumedKeys();

    /** Reads everything from the network as it currently stands. */
    final class LiveStateView implements IidmStateView {

        private LiveStateView() {
        }

        @Override
        public double getDouble(Identifiable<?> identifiable, String attribute, DoubleSupplier live) {
            return live.getAsDouble();
        }

        @Override
        public int getInt(Identifiable<?> identifiable, String attribute, IntSupplier live) {
            return live.getAsInt();
        }

        @Override
        public boolean getBoolean(Identifiable<?> identifiable, String attribute, BooleanSupplier live) {
            return live.getAsBoolean();
        }

        @Override
        public <E extends Enum<E>> E getEnum(Identifiable<?> identifiable, String attribute, Class<E> type, Supplier<E> live) {
            return live.get();
        }

        @Override
        public double getExtensionDouble(Identifiable<?> identifiable, String extensionName, String attribute, DoubleSupplier live) {
            return live.getAsDouble();
        }

        @Override
        public boolean getExtensionBoolean(Identifiable<?> identifiable, String extensionName, String attribute, BooleanSupplier live) {
            return live.getAsBoolean();
        }

        @Override
        public int getExtensionInt(Identifiable<?> identifiable, String extensionName, String attribute,
                                   int valueWhenOldIsNull, IntSupplier live) {
            return live.getAsInt();
        }

        @Override
        public void requireExtensionNotCreated(Identifiable<?> identifiable, String extensionName) {
            // The extension exists now, whether or not the change set created it
        }

        @Override
        public double getLimitValue(Identifiable<?> owner, String wholeKey, String memberKey,
                                    int acceptableDuration, DoubleSupplier live) {
            return live.getAsDouble();
        }

        @Override
        public SortedSet<Integer> getLimitDurations(Identifiable<?> owner, String wholeKey,
                                                    Supplier<SortedSet<Integer>> live) {
            return live.get();
        }

        @Override
        public boolean hasPermanentLimit(Identifiable<?> owner, String wholeKey, BooleanSupplier live) {
            return live.getAsBoolean();
        }

        @Override
        public Set<String> unconsumedKeys() {
            return Set.of();
        }
    }

    /** Reads the value the change log remembers, and falls back to the network for everything it says nothing about. */
    final class PreviousStateView implements IidmStateView {

        private final CompactedChanges changes;
        private final Set<String> consumedKeys = new HashSet<>();

        private PreviousStateView(CompactedChanges changes) {
            this.changes = changes;
        }

        @Override
        public double getDouble(Identifiable<?> identifiable, String attribute, DoubleSupplier live) {
            Object old = previous(identifiable, attribute);
            return old == NOT_CHANGED ? live.getAsDouble() : asDouble(identifiable, attribute, old);
        }

        @Override
        public int getInt(Identifiable<?> identifiable, String attribute, IntSupplier live) {
            Object old = previous(identifiable, attribute);
            return old == NOT_CHANGED ? live.getAsInt() : asInt(identifiable, attribute, old);
        }

        @Override
        public boolean getBoolean(Identifiable<?> identifiable, String attribute, BooleanSupplier live) {
            Object old = previous(identifiable, attribute);
            return old == NOT_CHANGED ? live.getAsBoolean() : asBoolean(identifiable, attribute, old);
        }

        @Override
        public <E extends Enum<E>> E getEnum(Identifiable<?> identifiable, String attribute, Class<E> type, Supplier<E> live) {
            Object old = previous(identifiable, attribute);
            if (old == NOT_CHANGED) {
                return live.get();
            }
            if (type.isInstance(old)) {
                return type.cast(old);
            }
            throw notRecordedAs(identifiable, attribute, type.getSimpleName());
        }

        @Override
        public double getExtensionDouble(Identifiable<?> identifiable, String extensionName, String attribute, DoubleSupplier live) {
            String key = EventCompactor.extensionAttributeKey(extensionName, attribute);
            Object old = previous(identifiable, key);
            return old == NOT_CHANGED ? live.getAsDouble() : asDouble(identifiable, key, old);
        }

        @Override
        public boolean getExtensionBoolean(Identifiable<?> identifiable, String extensionName, String attribute, BooleanSupplier live) {
            String key = EventCompactor.extensionAttributeKey(extensionName, attribute);
            Object old = previous(identifiable, key);
            return old == NOT_CHANGED ? live.getAsBoolean() : asBoolean(identifiable, key, old);
        }

        @Override
        public int getExtensionInt(Identifiable<?> identifiable, String extensionName, String attribute,
                                   int valueWhenOldIsNull, IntSupplier live) {
            String key = EventCompactor.extensionAttributeKey(extensionName, attribute);
            Object old = previous(identifiable, key);
            if (changes.extensionCreated(identifiable.getId(), extensionName) || old == null) {
                // The extension did not exist, or the change did not say what it replaced: for this attribute that
                // is a defined value rather than an unknown one
                return valueWhenOldIsNull;
            }
            return old == NOT_CHANGED ? live.getAsInt() : asInt(identifiable, key, old);
        }

        @Override
        public void requireExtensionNotCreated(Identifiable<?> identifiable, String extensionName) {
            if (changes.extensionCreated(identifiable.getId(), extensionName)) {
                throw new UnreconstructibleStateException("extension " + extensionName + " of "
                        + identifiable.getId() + " was created by this change set, so the state before it is unknown");
            }
        }

        @Override
        public double getLimitValue(Identifiable<?> owner, String wholeKey, String memberKey,
                                    int acceptableDuration, DoubleSupplier live) {
            LimitSource source = limitSource(owner, wholeKey, memberKey);
            return switch (source) {
                case LIVE -> live.getAsDouble();
                case MEMBER -> memberValue(owner, memberKey);
                case WHOLE -> wholeValue(owner, wholeKey, acceptableDuration);
            };
        }

        @Override
        public SortedSet<Integer> getLimitDurations(Identifiable<?> owner, String wholeKey,
                                                    Supplier<SortedSet<Integer>> live) {
            if (changes.firstEventIndex(owner.getId(), wholeKey) < 0) {
                // The object itself was not replaced, so its structure is the one the network holds
                return live.get();
            }
            SortedSet<Integer> durations = new TreeSet<>();
            previousLimits(owner, wholeKey).getTemporaryLimits()
                    .forEach(temporaryLimit -> durations.add(temporaryLimit.getAcceptableDuration()));
            return durations;
        }

        @Override
        public boolean hasPermanentLimit(Identifiable<?> owner, String wholeKey, BooleanSupplier live) {
            if (changes.firstEventIndex(owner.getId(), wholeKey) < 0) {
                return live.getAsBoolean();
            }
            return !Double.isNaN(previousLimits(owner, wholeKey).getPermanentLimit());
        }

        /** Which change of a limit value the log remembers the previous state in. */
        private enum LimitSource { LIVE, MEMBER, WHOLE }

        private LimitSource limitSource(Identifiable<?> owner, String wholeKey, String memberKey) {
            int member = changes.firstEventIndex(owner.getId(), memberKey);
            int whole = changes.firstEventIndex(owner.getId(), wholeKey);
            if (member < 0 && whole < 0) {
                return LimitSource.LIVE;
            }
            // The earlier of the two remembers the state the change set started from
            return whole < 0 || member >= 0 && member < whole ? LimitSource.MEMBER : LimitSource.WHOLE;
        }

        private double memberValue(Identifiable<?> owner, String memberKey) {
            Object old = previous(owner, memberKey);
            return switch (old) {
                case PermanentLimitInfo info -> info.value();
                case TemporaryLimitInfo info -> info.value();
                case null, default -> throw notRecordedAs(owner, memberKey, "loading limit value");
            };
        }

        private double wholeValue(Identifiable<?> owner, String wholeKey, int acceptableDuration) {
            LoadingLimits limits = previousLimits(owner, wholeKey);
            if (acceptableDuration < 0) {
                return limits.getPermanentLimit();
            }
            LoadingLimits.TemporaryLimit temporaryLimit = limits.getTemporaryLimit(acceptableDuration);
            if (temporaryLimit == null) {
                throw new UnreconstructibleStateException("temporary limit " + acceptableDuration + " s of "
                        + owner.getId() + "." + wholeKey + " was added by this change set");
            }
            return temporaryLimit.getValue();
        }

        /**
         * The loading limits the change set replaced, read from the old object the event carried.
         *
         * <p>iidm-impl never mutates an existing {@code LoadingLimits} in place &mdash; an adder builds a new
         * instance and the setter stores it &mdash; so the object the event carries still describes the state before
         * the change.</p>
         */
        private LoadingLimits previousLimits(Identifiable<?> owner, String wholeKey) {
            Object old = previous(owner, wholeKey);
            if (!(old instanceof OperationalLimitsInfo info)) {
                throw notRecordedAs(owner, wholeKey, "set of operational limits");
            }
            OperationalLimits value = info.value();
            if (value == null) {
                throw new UnreconstructibleStateException("the limits " + owner.getId() + "." + wholeKey
                        + " were created by this change set, so the state before it is unknown");
            }
            if (!(value instanceof LoadingLimits limits)) {
                throw notRecordedAs(owner, wholeKey, "set of loading limits");
            }
            return limits;
        }

        @Override
        public Set<String> unconsumedKeys() {
            Set<String> keys = new HashSet<>(changes.changedKeys());
            keys.removeAll(consumedKeys);
            return keys;
        }

        /** Marker telling a value the change set did not touch apart from one it recorded as {@code null}. */
        private static final Object NOT_CHANGED = new Object();

        private Object previous(Identifiable<?> identifiable, String attributeKey) {
            String id = identifiable.getId();
            if (!changes.hasChange(id, attributeKey)) {
                return NOT_CHANGED;
            }
            consumedKeys.add(id + "." + attributeKey);
            return changes.firstOldValue(id, attributeKey);
        }

        private static double asDouble(Identifiable<?> identifiable, String attributeKey, Object old) {
            if (old instanceof Number number) {
                return number.doubleValue();
            }
            throw notRecordedAs(identifiable, attributeKey, "number");
        }

        private static int asInt(Identifiable<?> identifiable, String attributeKey, Object old) {
            if (old instanceof Number number) {
                return number.intValue();
            }
            throw notRecordedAs(identifiable, attributeKey, "number");
        }

        private static boolean asBoolean(Identifiable<?> identifiable, String attributeKey, Object old) {
            if (old instanceof Boolean value) {
                return value;
            }
            throw notRecordedAs(identifiable, attributeKey, "boolean");
        }

        private static UnreconstructibleStateException notRecordedAs(Identifiable<?> identifiable, String attributeKey, String type) {
            return new UnreconstructibleStateException("the previous value of " + identifiable.getId() + "."
                    + attributeKey + " was not recorded as a " + type);
        }
    }
}
