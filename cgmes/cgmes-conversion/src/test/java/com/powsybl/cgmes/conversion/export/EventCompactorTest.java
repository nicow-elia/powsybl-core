/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.export.EventCompactor.CompactedChanges;
import com.powsybl.iidm.network.events.CreationNetworkEvent;
import com.powsybl.iidm.network.events.ExtensionCreationNetworkEvent;
import com.powsybl.iidm.network.events.ExtensionUpdateNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.OperationalLimitsInfo;
import com.powsybl.iidm.network.events.PermanentLimitInfo;
import com.powsybl.iidm.network.events.RemovalNetworkEvent;
import com.powsybl.iidm.network.events.TemporaryLimitInfo;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class EventCompactorTest {

    private static final String VARIANT = "InitialState";

    private static UpdateNetworkEvent update(String id, String attribute, Object oldValue, Object newValue) {
        return new UpdateNetworkEvent(id, attribute, VARIANT, oldValue, newValue);
    }

    @Test
    void keepsLastEventAndFirstOldValue() {
        UpdateNetworkEvent first = update("L", "p0", 10.0, 11.0);
        UpdateNetworkEvent second = update("L", "p0", 11.0, 12.5);
        CompactedChanges changes = EventCompactor.compact(List.of(first, second), VARIANT);

        assertEquals(List.of(second), changes.events());
        assertTrue(changes.hasChange("L", "p0"));
        assertEquals(10.0, changes.firstOldValue("L", "p0"));
        assertFalse(changes.hasChange("L", "q0"));
        assertNull(changes.firstOldValue("L", "q0"));
    }

    /**
     * IIDM records {@code null} as the old value of an attribute that had none: an unset section count or tap
     * position, a reference priority that did not exist. That {@code null} is the state the change set started from
     * and a later change of the same attribute must not replace it with an intermediate value.
     */
    @Test
    void firstOldValueMayBeNullAndIsKeptWhenTheAttributeChangesAgain() {
        CompactedChanges changes = EventCompactor.compact(List.of(
                update("S", "sectionCount", null, 2),
                update("S", "sectionCount", 2, 1)), VARIANT);

        assertTrue(changes.hasChange("S", "sectionCount"));
        assertNull(changes.firstOldValue("S", "sectionCount"));
        assertEquals(1, changes.events().size());
    }

    @Test
    void extensionEventsAreKeyedByExtensionAndAttribute() {
        ExtensionUpdateNetworkEvent enabledOfOne =
                new ExtensionUpdateNetworkEvent("G", "activePowerControl", "enabled", VARIANT, true, false);
        ExtensionUpdateNetworkEvent enabledOfAnother =
                new ExtensionUpdateNetworkEvent("G", "generatorRemoteReactivePowerControl", "enabled", VARIANT, false, true);
        CompactedChanges changes = EventCompactor.compact(List.of(enabledOfOne, enabledOfAnother), VARIANT);

        assertEquals(List.of(enabledOfOne, enabledOfAnother), changes.events());
        assertEquals(true, changes.firstOldValue("G", "activePowerControl#enabled"));
        assertEquals(false, changes.firstOldValue("G", "generatorRemoteReactivePowerControl#enabled"));
        assertFalse(changes.hasChange("G", "enabled"));
    }

    @Test
    void eventsOfOtherVariantsDoNotFeedOldValues() {
        NetworkEvent otherVariant = new UpdateNetworkEvent("L", "p0", "OtherVariant", 1.0, 2.0);
        NetworkEvent thisVariant = update("L", "p0", 10.0, 12.5);
        CompactedChanges changes = EventCompactor.compact(List.of(otherVariant, thisVariant), VARIANT);

        // Both are still compacted, only the previous value comes from the change of this variant
        assertEquals(List.of(thisVariant), changes.events());
        assertEquals(10.0, changes.firstOldValue("L", "p0"));
    }

    @Test
    void eventsWithoutAVariantAlwaysFeedOldValues() {
        NetworkEvent noVariant = new UpdateNetworkEvent("L", "p0", null, 10.0, 12.5);
        CompactedChanges changes = EventCompactor.compact(List.of(noVariant), VARIANT);
        assertEquals(10.0, changes.firstOldValue("L", "p0"));
    }

    @Test
    void nonUpdateEventsAreRetained() {
        NetworkEvent creation = new CreationNetworkEvent("L");
        NetworkEvent removal = new RemovalNetworkEvent("M", true);
        NetworkEvent extensionCreation = new ExtensionCreationNetworkEvent("G", "referencePriorities");
        NetworkEvent first = update("L", "p0", 10.0, 11.0);
        NetworkEvent second = update("L", "p0", 11.0, 12.5);
        CompactedChanges changes =
                EventCompactor.compact(List.of(creation, first, removal, second, extensionCreation), VARIANT);

        assertEquals(List.of(creation, removal, second, extensionCreation), changes.events());
        assertTrue(changes.extensionCreated("G", "referencePriorities"));
        assertFalse(changes.extensionCreated("G", "activePowerControl"));
        assertFalse(changes.extensionCreated("H", "referencePriorities"));
    }

    // Operational limits: one attribute name, several values

    private static UpdateNetworkEvent permanentLimit(String group, double oldValue, double newValue) {
        return update("L", "limits1_CURRENT.permanentLimit",
                new PermanentLimitInfo("PATL", oldValue, group, true),
                new PermanentLimitInfo("PATL", newValue, group, true));
    }

    private static UpdateNetworkEvent temporaryLimit(String group, int duration, double oldValue, double newValue) {
        return update("L", "limits1_CURRENT.temporaryLimit.value",
                new TemporaryLimitInfo(oldValue, group, true, duration),
                new TemporaryLimitInfo(newValue, group, true, duration));
    }

    /**
     * IIDM reports every permanent limit of a side under one attribute name and every temporary limit under a
     * second one, so the group and the acceptable duration have to become part of the key. Compacting them together
     * would silently lose one of the two changes.
     */
    @Test
    void limitEventsAreKeyedPerGroupAndDuration() {
        UpdateNetworkEvent groupA = permanentLimit("A", 100.0, 110.0);
        UpdateNetworkEvent groupB = permanentLimit("B", 200.0, 210.0);
        UpdateNetworkEvent tatl600 = temporaryLimit("A", 600, 300.0, 310.0);
        UpdateNetworkEvent tatl900 = temporaryLimit("A", 900, 400.0, 410.0);
        CompactedChanges changes =
                EventCompactor.compact(List.of(groupA, groupB, tatl600, tatl900), VARIANT);

        assertEquals(List.of(groupA, groupB, tatl600, tatl900), changes.events());
        assertEquals(100.0, ((PermanentLimitInfo) changes.firstOldValue("L", "limits1_CURRENT.permanentLimit@A")).value());
        assertEquals(200.0, ((PermanentLimitInfo) changes.firstOldValue("L", "limits1_CURRENT.permanentLimit@B")).value());
        assertEquals(300.0, ((TemporaryLimitInfo) changes
                .firstOldValue("L", "limits1_CURRENT.temporaryLimit.value@A@600")).value());
        assertEquals(410.0, ((TemporaryLimitInfo) tatl900.newValue()).value());
    }

    /**
     * A change of which groups are selected carries the raw limits object rather than one of the payload records, so
     * its key stays the plain attribute name and the mapping can tell it apart from a whole replacement.
     */
    @Test
    void selectionEventsKeepThePlainAttribute() {
        UpdateNetworkEvent selection = update("L", "limits1_CURRENT", "someLimitsObject", null);
        CompactedChanges changes = EventCompactor.compact(List.of(selection), VARIANT);
        assertEquals("limits1_CURRENT", changes.attributeKey(selection));
        assertTrue(changes.hasChange("L", "limits1_CURRENT"));
    }

    @Test
    void wholeReplacementsAreKeyedPerGroup() {
        UpdateNetworkEvent replacement = update("L", "limits1_CURRENT",
                new OperationalLimitsInfo(null, "A", true), new OperationalLimitsInfo(null, "A", true));
        CompactedChanges changes = EventCompactor.compact(List.of(replacement), VARIANT);
        assertEquals("limits1_CURRENT@A", changes.attributeKey(replacement));
        assertTrue(changes.hasChange("L", "limits1_CURRENT@A"));
    }

    /** A key a difference model importer builds itself is already refined and must not be refined twice. */
    @Test
    void anAlreadyRefinedKeyIsKept() {
        UpdateNetworkEvent probe = update("L", "limits1_CURRENT.permanentLimit@A", null, null);
        assertEquals("limits1_CURRENT.permanentLimit@A", EventCompactor.attributeKey(probe));
    }

    @Test
    void firstEventIndexFollowsLogOrder() {
        UpdateNetworkEvent member = permanentLimit("A", 100.0, 110.0);
        UpdateNetworkEvent whole = update("L", "limits1_CURRENT",
                new OperationalLimitsInfo(null, "A", true), new OperationalLimitsInfo(null, "A", true));
        CompactedChanges changes = EventCompactor.compact(List.of(member, whole), VARIANT);

        assertEquals(0, changes.firstEventIndex("L", "limits1_CURRENT.permanentLimit@A"));
        assertEquals(1, changes.firstEventIndex("L", "limits1_CURRENT@A"));
        assertEquals(-1, changes.firstEventIndex("L", "limits2_CURRENT@A"));

        CompactedChanges reversed = EventCompactor.compact(List.of(whole, member), VARIANT);
        assertEquals(0, reversed.firstEventIndex("L", "limits1_CURRENT@A"));
        assertEquals(1, reversed.firstEventIndex("L", "limits1_CURRENT.permanentLimit@A"));
    }

    @Test
    void compactEventsIsUnchanged() {
        NetworkEvent creation = new CreationNetworkEvent("L");
        NetworkEvent first = update("L", "p0", 10.0, 11.0);
        NetworkEvent other = update("M", "q0", 1.0, 2.0);
        NetworkEvent second = update("L", "p0", 11.0, 12.5);
        NetworkEvent extension =
                new ExtensionUpdateNetworkEvent("G", "activePowerControl", "participationFactor", VARIANT, 0.0, 0.5);
        NetworkEvent extensionAgain =
                new ExtensionUpdateNetworkEvent("G", "activePowerControl", "participationFactor", VARIANT, 0.5, 0.75);
        List<NetworkEvent> events = List.of(creation, first, other, second, extension, extensionAgain);

        // The public contract: one change per attribute, in the order of its last occurrence
        assertEquals(List.of(creation, other, second, extensionAgain), PartialSshExport.compactEvents(events));
        assertEquals(PartialSshExport.compactEvents(events), EventCompactor.compact(events, null).events());
    }
}
