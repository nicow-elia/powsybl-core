/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.iidm.network.events.ExtensionCreationNetworkEvent;
import com.powsybl.iidm.network.events.ExtensionUpdateNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.OperationalLimitsInfo;
import com.powsybl.iidm.network.events.PermanentLimitInfo;
import com.powsybl.iidm.network.events.TemporaryLimitInfo;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reduces a recorded change log to one change per updated attribute, keeping what the first of them replaced.
 *
 * <p>An exporter describes the state the network is in now, so only the last change of an attribute matters for what
 * it writes. A difference model also has to describe the state the network was in before the whole change set, and
 * that is what the <em>first</em> change of an attribute remembers: its old value. Compaction is therefore the one
 * place where both ends of a change log are collected.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class EventCompactor {

    private EventCompactor() {
    }

    /**
     * Compact a recorded change log.
     *
     * @param events           the changes in the order in which they were recorded
     * @param workingVariantId the variant the export reads its values from, or {@code null} when the caller only
     *                         needs the compacted list. Changes recorded on another variant never feed the previous
     *                         values, because the state they describe is not the state of this variant
     */
    static CompactedChanges compact(Collection<NetworkEvent> events, String workingVariantId) {
        Objects.requireNonNull(events);

        Map<UpdateKey, Object> firstOldValues = new HashMap<>();
        Map<UpdateKey, Integer> firstEventIndexes = new HashMap<>();
        Set<String> createdExtensions = new HashSet<>();
        int index = 0;
        for (NetworkEvent event : events) {
            Objects.requireNonNull(event);
            if (event instanceof ExtensionCreationNetworkEvent creation) {
                createdExtensions.add(extensionKey(creation.id(), creation.extensionName()));
            }
            UpdateKey key = updateKey(event);
            // Every recorded change of this variant feeds the previous values, including the ones a mapping later
            // rejects: whether a change can be exported is decided after the previous state is known.
            // containsKey, not putIfAbsent: a recorded previous value may legitimately be null (an unset section
            // count or tap position, a reference priority that did not exist), and putIfAbsent would treat that as
            // no value at all and let the next change of the same attribute overwrite it with an intermediate one.
            if (key != null && appliesTo(event, workingVariantId) && !firstOldValues.containsKey(key)) {
                firstOldValues.put(key, oldValue(event));
                // The position is recorded exactly where the previous value is, so that a caller comparing two
                // positions can always read the previous value of the earlier one
                firstEventIndexes.put(key, index);
            }
            index++;
        }

        List<NetworkEvent> reversedEvents = new ArrayList<>(events);
        Collections.reverse(reversedEvents);
        List<NetworkEvent> compactedEvents = new ArrayList<>(reversedEvents.size());
        Set<UpdateKey> retainedUpdates = new HashSet<>();
        for (NetworkEvent event : reversedEvents) {
            UpdateKey key = updateKey(event);
            if (key == null || retainedUpdates.add(key)) {
                compactedEvents.add(event);
            }
        }
        Collections.reverse(compactedEvents);

        // A previous value may legitimately be null (an unset section count, a withdrawn reference priority), so
        // the map cannot be a Map.copyOf, which rejects null values
        return new CompactedChanges(List.copyOf(compactedEvents), Collections.unmodifiableMap(firstOldValues),
                Map.copyOf(firstEventIndexes), Set.copyOf(createdExtensions));
    }

    /** Whether a change describes the variant the export reads its values from. */
    private static boolean appliesTo(NetworkEvent event, String workingVariantId) {
        String variantId = switch (event) {
            case UpdateNetworkEvent update -> update.variantId();
            case ExtensionUpdateNetworkEvent update -> update.variantId();
            default -> null;
        };
        return variantId == null || variantId.equals(workingVariantId);
    }

    private static Object oldValue(NetworkEvent event) {
        return switch (event) {
            case UpdateNetworkEvent update -> update.oldValue();
            case ExtensionUpdateNetworkEvent update -> update.oldValue();
            default -> null;
        };
    }

    /** The attribute a change describes, or {@code null} for a change that no attribute identifies. */
    static UpdateKey updateKey(NetworkEvent event) {
        return switch (event) {
            case UpdateNetworkEvent update -> new UpdateKey(update.id(), attributeKey(update));
            // An extension attribute is namespaced by its extension: two extensions of the same object may well
            // both call an attribute "enabled" without describing the same value.
            case ExtensionUpdateNetworkEvent update ->
                new UpdateKey(update.id(), extensionAttributeKey(update.extensionName(), update.attribute()));
            default -> null;
        };
    }

    /** Separates the refinement of a limit attribute key from the attribute name IIDM reports. */
    static final String KEY_SEPARATOR = "@";

    /**
     * The key identifying the value a change describes, which is the attribute name for everything but limits.
     *
     * <p>IIDM reports every permanent limit of a branch side under one attribute name, whichever operational limits
     * group it belongs to, and every temporary limit of a side under a second one, whichever acceptable duration it
     * has. Two changes of two groups, or of two durations, therefore share an attribute name while describing
     * different values, and compacting them into one would silently lose one of the two. The group and the duration
     * are carried by the payload of the event, so the key is refined with them.</p>
     *
     * <p>A selection change ({@code setSelectedOperationalLimitsGroup} and its relatives) uses the same attribute
     * name as a whole replacement but carries the raw limits object as payload; it keeps the plain attribute name,
     * which is also what makes the mapping able to tell the two apart and refuse the selection change.</p>
     *
     * @param event a change of an attribute, that is an {@link UpdateNetworkEvent}
     */
    static String attributeKey(UpdateNetworkEvent event) {
        String attribute = event.attribute();
        if (attribute.indexOf(KEY_SEPARATOR.charAt(0)) >= 0) {
            // Already a refined key: a synthetic probe event built by the difference model importer
            return attribute;
        }
        Object payload = event.newValue() != null ? event.newValue() : event.oldValue();
        return switch (payload) {
            case PermanentLimitInfo info -> attribute + KEY_SEPARATOR + info.groupId();
            case TemporaryLimitInfo info ->
                attribute + KEY_SEPARATOR + info.groupId() + KEY_SEPARATOR + info.acceptableDuration();
            case OperationalLimitsInfo info -> attribute + KEY_SEPARATOR + info.groupId();
            case null, default -> attribute;
        };
    }

    /** The key identifying the value a change describes, for any kind of event. */
    static String attributeKey(NetworkEvent event) {
        return switch (event) {
            case UpdateNetworkEvent update -> attributeKey(update);
            case ExtensionUpdateNetworkEvent update ->
                extensionAttributeKey(update.extensionName(), update.attribute());
            default -> null;
        };
    }

    /** The attribute key of an extension attribute, which is namespaced by the name of its extension. */
    static String extensionAttributeKey(String extensionName, String attribute) {
        return extensionName + "#" + attribute;
    }

    private static String extensionKey(String id, String extensionName) {
        return id + "#" + extensionName;
    }

    /** What identifies an updated attribute of an identifiable. */
    record UpdateKey(String identifiableId, String attributeKey) {
    }

    /**
     * The result of compacting a change log: the changes to export and the state the change set started from.
     */
    static final class CompactedChanges {

        private final List<NetworkEvent> events;
        private final Map<UpdateKey, Object> firstOldValues;
        private final Map<UpdateKey, Integer> firstEventIndexes;
        private final Set<String> createdExtensions;

        private CompactedChanges(List<NetworkEvent> events, Map<UpdateKey, Object> firstOldValues,
                                 Map<UpdateKey, Integer> firstEventIndexes, Set<String> createdExtensions) {
            this.events = events;
            this.firstOldValues = firstOldValues;
            this.firstEventIndexes = firstEventIndexes;
            this.createdExtensions = createdExtensions;
        }

        /** One change per updated attribute, in the order of its last occurrence in the log. */
        List<NetworkEvent> events() {
            return events;
        }

        /**
         * Whether the change set touched the given attribute at all. An attribute it did not touch holds the same
         * value before and after it.
         *
         * @param attributeKey the attribute, or {@code extensionName + "#" + attribute} for an extension attribute
         */
        boolean hasChange(String id, String attributeKey) {
            return firstOldValues.containsKey(new UpdateKey(id, attributeKey));
        }

        /**
         * The value the given attribute held before the change set, that is the old value carried by the first
         * change of it. {@code null} both for an attribute that was not changed and for one whose previous value was
         * not recorded, which {@link #hasChange} tells apart.
         */
        Object firstOldValue(String id, String attributeKey) {
            return firstOldValues.get(new UpdateKey(id, attributeKey));
        }

        /**
         * Where in the recorded log the first change of the given attribute sits, or {@code -1} when the change set
         * did not touch it.
         *
         * <p>Order matters for the limits of one {@code LoadingLimits} object, which a change log describes either
         * member by member (a permanent limit value, a temporary limit value) or as a whole (the object was
         * replaced). Both kinds of change speak about the same value, so the state before the change set is the one
         * remembered by whichever of them came <em>first</em>.</p>
         */
        int firstEventIndex(String id, String attributeKey) {
            return firstEventIndexes.getOrDefault(new UpdateKey(id, attributeKey), -1);
        }

        /** The key under which a change of this event is remembered here, see {@link EventCompactor#attributeKey}. */
        String attributeKey(NetworkEvent event) {
            return EventCompactor.attributeKey(event);
        }

        /**
         * Whether the change set created the given extension. The values such an extension carried before are not
         * recorded anywhere, because it did not exist.
         */
        boolean extensionCreated(String id, String extensionName) {
            return createdExtensions.contains(extensionKey(id, extensionName));
        }

        /**
         * Every attribute the change set touched, as {@code id.attributeKey}. Used to check that a difference export
         * really read the previous value of everything it exported.
         */
        Set<String> changedKeys() {
            Set<String> keys = new HashSet<>();
            firstOldValues.keySet().forEach(key -> keys.add(key.identifiableId() + "." + key.attributeKey()));
            return keys;
        }
    }
}
