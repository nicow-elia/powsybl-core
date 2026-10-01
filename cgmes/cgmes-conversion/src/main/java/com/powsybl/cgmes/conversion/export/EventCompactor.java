/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
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
 * <h2>Echoes of the deprecated voltage regulation setters</h2>
 *
 * <p>An <em>echo</em> is the event a deprecated voltage regulation setter reports under the historical attribute
 * name after writing through the {@code VoltageRegulation} API, which reports the same change under its own name
 * first (the keys of {@link RegulatingControlFamily} say which events are echoes and which values they repeat). The old
 * value of an echo is not reliable: {@code ShuntCompensator.setTargetDeadband} reports {@code NaN} whatever the
 * deadband was, {@code StaticVarCompensator.setReactivePowerSetpoint} the voltage target, and
 * {@code Generator.setTargetV(v, local)} the local target as the old value of the remote one. One rule decides what
 * becomes of every echo:</p>
 * <ol>
 *     <li>An echo <b>repeats</b> a change when an earlier event of the same equipment in the log reported one of the
 *     canonical values it stands for with the same new value. It is dropped: neither exported nor remembered.</li>
 *     <li>An echo that sets the value it had (old value equal to the new one) is a <b>no-op</b> and is dropped.</li>
 *     <li>Any other echo is the <b>sole carrier</b> of a change the new model did not report. That happens when the
 *     deprecated setter created the {@code VoltageRegulation}, which IIDM reports nowhere (gap G1), and when a
 *     bridge reports a wrong old value for a value it did not change; the two cannot be told apart from the log.
 *     The echo is kept under its own name, it never feeds the state before the change set, and both change exports
 *     refuse it with the remedy (use the {@code VoltageRegulation} setters).</li>
 * </ol>
 * <p>No echo ever feeds the state before the change set: a repeated echo adds nothing to its canonical event, and
 * the old value of a sole carrier is exactly what cannot be trusted.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class EventCompactor {

    /** The key of an echo that is dropped: compared by identity only, never looked up in a map. */
    private static final UpdateKey DROPPED = new UpdateKey(null, null);

    private EventCompactor() {
    }

    /**
     * Compact a recorded change log.
     *
     * @param events           the changes in the order in which they were recorded
     * @param workingVariantId the variant the export reads its values from, or {@code null} when the caller only
     *                         needs the compacted list. Changes recorded on another variant never feed the previous
     *                         values, because the state they describe is not the state of this variant
     * @param network          the network the changes were recorded on, which tells what kind of equipment a change
     *                         belongs to: an echo of a target is recognised by it. {@code null} keeps the echoes of
     *                         targets as changes of their own
     */
    static CompactedChanges compact(Collection<NetworkEvent> events, String workingVariantId, Network network) {
        Objects.requireNonNull(events);
        List<NetworkEvent> eventList = new ArrayList<>(events);
        UpdateKey[] keys = new UpdateKey[eventList.size()];
        Map<UpdateKey, FirstChange> firstChanges = new HashMap<>();
        Set<String> createdExtensions = new HashSet<>();
        // The new values every canonical key was reported with so far, for rule 1
        Map<UpdateKey, Set<Object>> reported = new HashMap<>();
        for (int index = 0; index < keys.length; index++) {
            NetworkEvent event = Objects.requireNonNull(eventList.get(index));
            if (event instanceof ExtensionCreationNetworkEvent creation) {
                createdExtensions.add(extensionKey(creation.id(), creation.extensionName()));
            }
            Identifiable<?> identifiable = event instanceof UpdateNetworkEvent update ? identifiableFor(update, network) : null;
            Set<String> repeated = event instanceof UpdateNetworkEvent update
                    ? RegulatingControlFamily.repeatedKeys(identifiable, update.attribute()) : Set.of();
            if (!repeated.isEmpty()) {
                keys[index] = echoKey((UpdateNetworkEvent) event, repeated, reported);
            } else {
                keys[index] = keyOf(event);
                if (keys[index] != null) {
                    if (event instanceof UpdateNetworkEvent update
                            && RegulatingControlFamily.isRepeatable(keys[index].attributeKey())) {
                        reported.computeIfAbsent(keys[index], key -> new HashSet<>()).add(update.newValue());
                    }
                    // Every recorded change of this variant feeds the previous values, including the ones a mapping
                    // later rejects: whether a change can be exported is decided after the previous state is known.
                    // The position is kept with the previous value, so that a caller comparing two positions can
                    // always read the previous value of the earlier one
                    if (appliesTo(event, workingVariantId)) {
                        firstChanges.putIfAbsent(keys[index], new FirstChange(oldValue(event), index));
                    }
                }
            }
        }

        // The last change of every key, in the order of those last changes
        List<NetworkEvent> compactedEvents = new ArrayList<>(eventList.size());
        Set<UpdateKey> retainedUpdates = new HashSet<>();
        for (int index = keys.length - 1; index >= 0; index--) {
            if (keys[index] != DROPPED && (keys[index] == null || retainedUpdates.add(keys[index]))) {
                compactedEvents.add(eventList.get(index));
            }
        }
        Collections.reverse(compactedEvents);

        return new CompactedChanges(List.copyOf(compactedEvents), Map.copyOf(firstChanges),
                Set.copyOf(createdExtensions));
    }

    /**
     * The key of an echo: {@link #DROPPED} when it repeats a canonical value already reported with the same new value
     * (rule 1) or changes nothing (rule 2), its own attribute name when it is the sole carrier of a change (rule 3).
     */
    private static UpdateKey echoKey(UpdateNetworkEvent echo, Set<String> repeated, Map<UpdateKey, Set<Object>> reported) {
        // The reported values may hold null (a regulating terminal removed): an immutable empty set would throw on it
        boolean repeats = repeated.stream().anyMatch(key -> reported
                .getOrDefault(new UpdateKey(echo.id(), key), Collections.emptySet()).contains(echo.newValue()));
        boolean noOp = Objects.equals(echo.oldValue(), echo.newValue());
        return repeats || noOp ? DROPPED : new UpdateKey(echo.id(), echo.attribute());
    }

    /** The identifiable a change was reported on, looked up only when it decides whether the change is an echo. */
    private static Identifiable<?> identifiableFor(UpdateNetworkEvent update, Network network) {
        return network != null && RegulatingControlFamily.needsIdentifiable(update.attribute())
                ? network.getIdentifiable(update.id()) : null;
    }

    /** Whether a change describes the given variant, which a change belonging to every variant always does. */
    private static boolean appliesTo(NetworkEvent event, String variantId) {
        String eventVariantId = variantIdOf(event);
        return eventVariantId == null || eventVariantId.equals(variantId);
    }

    /** The variant a recorded change belongs to, or {@code null} when it belongs to every variant. */
    static String variantIdOf(NetworkEvent event) {
        return switch (event) {
            case UpdateNetworkEvent update -> update.variantId();
            case ExtensionUpdateNetworkEvent update -> update.variantId();
            default -> null;
        };
    }

    /**
     * The changes that belong to the selected variant, all of them when none is selected. Naming a variant is a
     * selection: a change recorded on another one is simply not part of the export. A change without a variant
     * belongs to every variant and is kept.
     */
    static Collection<NetworkEvent> ofVariant(Collection<NetworkEvent> events, String variantId) {
        return variantId == null ? events : events.stream().filter(event -> appliesTo(event, variantId)).toList();
    }

    /**
     * Refuse a merged network: an exported document describes a single individual grid model, whose header
     * references the model it replaces and the equipment model it applies to, and a merged network has one of each
     * per subnetwork.
     *
     * @param documentName what the message calls the exported document, for instance "A partial SSH file"
     */
    static void checkSingleGridModel(Network network, String documentName) {
        if (!network.getSubnetworks().isEmpty()) {
            throw new PowsyblException("Network " + network.getId() + " is a merged model with "
                    + network.getSubnetworks().size() + " subnetworks. " + documentName + " describes a single "
                    + "individual grid model, so it has to be exported from each subnetwork separately.");
        }
    }

    private static Object oldValue(NetworkEvent event) {
        return switch (event) {
            case UpdateNetworkEvent update -> update.oldValue();
            case ExtensionUpdateNetworkEvent update -> update.oldValue();
            default -> null;
        };
    }

    /** The attribute a change that is not an echo describes, or {@code null} for a change no attribute identifies. */
    private static UpdateKey keyOf(NetworkEvent event) {
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
     * <p>An echo of a deprecated voltage regulation setter never gets here: the compaction and the translator
     * recognise it first ({@link RegulatingControlFamily#HOLDER_KEYS}).</p>
     *
     * @param event a change of an attribute, that is an {@link UpdateNetworkEvent}
     * @return the key
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
     * The first recorded change of an attribute.
     *
     * @param oldValue the value it replaced, which may legitimately be {@code null}: an unset section count or tap
     *                 position, a reference priority that did not exist
     * @param index    where in the recorded log it sits
     */
    record FirstChange(Object oldValue, int index) {
    }

    /**
     * The result of compacting a change log: the changes to export and the state the change set started from.
     *
     * @param events            one change per updated attribute, in the order of its last occurrence in the log
     * @param firstChanges      the first change of every attribute the change set touched on the selected variant
     * @param createdExtensions the extensions the change set created, as {@code id#extensionName}
     */
    record CompactedChanges(List<NetworkEvent> events, Map<UpdateKey, FirstChange> firstChanges,
                            Set<String> createdExtensions) {

        /** Nothing changed: every read of a view over it is a live read. */
        static final CompactedChanges NONE = new CompactedChanges(List.of(), Map.of(), Set.of());

        /** Whether the change set touched nothing on the selected variant and created no extension. */
        boolean isEmpty() {
            return firstChanges.isEmpty() && createdExtensions.isEmpty();
        }

        /**
         * Whether the change set touched the given attribute at all. An attribute it did not touch holds the same
         * value before and after it.
         *
         * @param attributeKey the attribute, or {@code extensionName + "#" + attribute} for an extension attribute
         */
        boolean hasChange(String id, String attributeKey) {
            // The empty check keeps a read of the live state (NONE) free of any allocation
            return !firstChanges.isEmpty() && firstChanges.containsKey(new UpdateKey(id, attributeKey));
        }

        /**
         * The value the given attribute held before the change set, that is the old value carried by the first
         * change of it. {@code null} both for an attribute that was not changed and for one whose previous value was
         * not recorded, which {@link #hasChange} tells apart.
         */
        Object firstOldValue(String id, String attributeKey) {
            FirstChange first = firstChanges.get(new UpdateKey(id, attributeKey));
            return first == null ? null : first.oldValue();
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
            if (firstChanges.isEmpty()) {
                return -1;
            }
            FirstChange first = firstChanges.get(new UpdateKey(id, attributeKey));
            return first == null ? -1 : first.index();
        }

        /**
         * Whether the change set created the given extension. The values such an extension carried before are not
         * recorded anywhere, because it did not exist.
         */
        boolean extensionCreated(String id, String extensionName) {
            return !createdExtensions.isEmpty() && createdExtensions.contains(extensionKey(id, extensionName));
        }

        /**
         * Every attribute the change set touched, as {@code id.attributeKey}. Used to check that a difference export
         * really read the previous value of everything it exported.
         */
        Set<String> changedKeys() {
            Set<String> keys = new HashSet<>();
            firstChanges.keySet().forEach(key -> keys.add(key.identifiableId() + "." + key.attributeKey()));
            return keys;
        }
    }
}
