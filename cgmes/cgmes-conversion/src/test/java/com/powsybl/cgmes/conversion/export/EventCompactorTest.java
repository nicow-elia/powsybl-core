/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.export.EventCompactor.CompactedChanges;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.events.CreationNetworkEvent;
import com.powsybl.iidm.network.events.ExtensionCreationNetworkEvent;
import com.powsybl.iidm.network.events.ExtensionUpdateNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.OperationalLimitsInfo;
import com.powsybl.iidm.network.events.PermanentLimitInfo;
import com.powsybl.iidm.network.events.RemovalNetworkEvent;
import com.powsybl.iidm.network.events.TemporaryLimitInfo;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import com.powsybl.iidm.network.test.BoundaryLineNetworkFactory;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.ShuntTestCaseFactory;
import com.powsybl.iidm.network.test.SvcTestCaseFactory;
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
        CompactedChanges changes = EventCompactor.compact(List.of(first, second), VARIANT, null);

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
                update("S", "sectionCount", 2, 1)), VARIANT, null);

        assertTrue(changes.hasChange("S", "sectionCount"));
        assertNull(changes.firstOldValue("S", "sectionCount"));
        assertEquals(1, changes.events().size());
    }

    @Test
    void extensionEventsAreKeyedByExtensionAndAttribute() {
        ExtensionUpdateNetworkEvent enabledOfOne =
                new ExtensionUpdateNetworkEvent("G", "activePowerControl", "enabled", VARIANT, true, false);
        ExtensionUpdateNetworkEvent enabledOfAnother =
                new ExtensionUpdateNetworkEvent("G", "referencePriorities", "enabled", VARIANT, false, true);
        CompactedChanges changes = EventCompactor.compact(List.of(enabledOfOne, enabledOfAnother), VARIANT, null);

        assertEquals(List.of(enabledOfOne, enabledOfAnother), changes.events());
        assertEquals(true, changes.firstOldValue("G", "activePowerControl#enabled"));
        assertEquals(false, changes.firstOldValue("G", "referencePriorities#enabled"));
        assertFalse(changes.hasChange("G", "enabled"));
    }

    @Test
    void eventsOfOtherVariantsDoNotFeedOldValues() {
        NetworkEvent otherVariant = new UpdateNetworkEvent("L", "p0", "OtherVariant", 1.0, 2.0);
        NetworkEvent thisVariant = update("L", "p0", 10.0, 12.5);
        CompactedChanges changes = EventCompactor.compact(List.of(otherVariant, thisVariant), VARIANT, null);

        // Both are still compacted, only the previous value comes from the change of this variant
        assertEquals(List.of(thisVariant), changes.events());
        assertEquals(10.0, changes.firstOldValue("L", "p0"));
    }

    @Test
    void eventsWithoutAVariantAlwaysFeedOldValues() {
        NetworkEvent noVariant = new UpdateNetworkEvent("L", "p0", null, 10.0, 12.5);
        CompactedChanges changes = EventCompactor.compact(List.of(noVariant), VARIANT, null);
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
                EventCompactor.compact(List.of(creation, first, removal, second, extensionCreation), VARIANT, null);

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
                EventCompactor.compact(List.of(groupA, groupB, tatl600, tatl900), VARIANT, null);

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
        CompactedChanges changes = EventCompactor.compact(List.of(selection), VARIANT, null);
        assertEquals("limits1_CURRENT", EventCompactor.attributeKey(selection));
        assertTrue(changes.hasChange("L", "limits1_CURRENT"));
    }

    @Test
    void wholeReplacementsAreKeyedPerGroup() {
        UpdateNetworkEvent replacement = update("L", "limits1_CURRENT",
                new OperationalLimitsInfo(null, "A", true), new OperationalLimitsInfo(null, "A", true));
        CompactedChanges changes = EventCompactor.compact(List.of(replacement), VARIANT, null);
        assertEquals("limits1_CURRENT@A", EventCompactor.attributeKey(replacement));
        assertTrue(changes.hasChange("L", "limits1_CURRENT@A"));
    }

    @Test
    void firstEventIndexFollowsLogOrder() {
        UpdateNetworkEvent member = permanentLimit("A", 100.0, 110.0);
        UpdateNetworkEvent whole = update("L", "limits1_CURRENT",
                new OperationalLimitsInfo(null, "A", true), new OperationalLimitsInfo(null, "A", true));
        CompactedChanges changes = EventCompactor.compact(List.of(member, whole), VARIANT, null);

        assertEquals(0, changes.firstEventIndex("L", "limits1_CURRENT.permanentLimit@A"));
        assertEquals(1, changes.firstEventIndex("L", "limits1_CURRENT@A"));
        assertEquals(-1, changes.firstEventIndex("L", "limits2_CURRENT@A"));

        CompactedChanges reversed = EventCompactor.compact(List.of(whole, member), VARIANT, null);
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
        assertEquals(PartialSshExport.compactEvents(events), EventCompactor.compact(events, null, null).events());
    }

    // The echoes of the deprecated voltage regulation setters (powsybl-core #3699), see the keys of RegulatingControlFamily

    /**
     * Rule 1: an echo repeating its canonical event is dropped, whatever lies between them; the old value kept is the
     * canonical one.
     */
    @Test
    @SuppressWarnings("removal")
    void aFlagEchoCompactsWithItsCanonicalEvent() {
        Network network = EurostagTutorialExample1Factory.create();
        Generator generator = network.getGenerator("GEN");
        boolean regulating = generator.getVoltageRegulation().isRegulating();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> {
            generator.setVoltageRegulatorOn(!regulating);
            generator.getVoltageRegulation().setRegulating(regulating);
            generator.setVoltageRegulatorOn(!regulating);
        });
        // Canonical and echo, canonical alone, canonical and echo
        assertEquals(5, events.size());
        CompactedChanges changes = EventCompactor.compact(events, VARIANT, network);

        assertEquals(List.of(events.get(3)), changes.events());
        assertEquals(regulating, changes.firstOldValue("GEN", CgmesChangeTranslator.VR_REGULATING));
        assertFalse(changes.hasChange("GEN", "voltageRegulatorOn"));
    }

    /**
     * An echo without canonical event that reports no old value ({@code ShuntCompensator.setTargetDeadband} reports NaN
     * whatever the deadband was) is not a no-op: the bridge may have created the regulation with that deadband, so it
     * is the sole carrier of a change (rule 3), kept under its own name and never remembered (review 21 round 3,
     * r3-m7: rule 2 no longer has a NaN clause).
     */
    @Test
    @SuppressWarnings("removal")
    void anEchoWithoutOldValueThatRepeatsNothingIsASoleCarrier() {
        Network network = ShuntTestCaseFactory.create();
        ShuntCompensator shunt = network.getShuntCompensator("SHUNT");
        shunt.getVoltageRegulation().setTargetDeadband(1.0);
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> shunt.setTargetDeadband(1.0));
        assertEquals(1, events.size());
        assertTrue(Double.isNaN((Double) ((UpdateNetworkEvent) events.get(0)).oldValue()));
        CompactedChanges changes = EventCompactor.compact(events, VARIANT, network);

        assertEquals(events, changes.events());
        assertFalse(changes.hasChange("SHUNT", CgmesChangeTranslator.VR_TARGET_DEADBAND));
        assertFalse(changes.hasChange("SHUNT", "targetDeadband"));
    }

    /**
     * Rule 1 looks at every earlier event of the equipment, not only at the one just before the echo:
     * {@code Generator.setTargetV(v, local)} on a generator regulating a remote terminal reports the target of the
     * regulation, then the local target again (with the old remote target as its old value, F3), then the echo
     * (review 21 round 2, R2-M1).
     */
    @Test
    void anEchoRepeatsAnEarlierCanonicalEventWhateverLiesBetween() {
        List<NetworkEvent> events = List.of(
                update("G", CgmesChangeTranslator.VR_TARGET_VALUE, 410.0, 411.0),
                update("G", CgmesChangeTranslator.LOCAL_TARGET_V, 410.0, 405.0),
                update("G", "targetV", 405.0, 411.0));
        Network network = networkWithGenerator("G");
        CompactedChanges changes = EventCompactor.compact(events, VARIANT, network);

        assertEquals(events.subList(0, 2), changes.events());
        assertFalse(changes.hasChange("G", "targetV"));
    }

    /**
     * Rule 3: an echo that repeats nothing and changes something is the sole carrier of a change the new model did not
     * report (the deprecated setter created the VoltageRegulation, gap G1). It is kept under its own name, so that the
     * exports refuse it, and its old value never becomes the previous state (review 21 round 2, R2-M2).
     */
    @Test
    void aSoleCarrierEchoIsKeptUnderItsOwnNameAndNotRemembered() {
        List<NetworkEvent> events = List.of(update("G", "voltageRegulatorOn", false, true));
        CompactedChanges changes = EventCompactor.compact(events, VARIANT, networkWithGenerator("G"));

        assertEquals(events, changes.events());
        assertFalse(changes.hasChange("G", CgmesChangeTranslator.VR_REGULATING));
        assertFalse(changes.hasChange("G", "voltageRegulatorOn"));
    }

    /**
     * Rule 2: an echo that changes nothing is dropped, although it repeats no canonical change. No setter of the
     * setter matrix reports such an echo (its rule {@code echo-2} has no row), so this is the test of the rule.
     */
    @Test
    void anEchoThatChangesNothingIsDropped() {
        List<NetworkEvent> events = List.of(update("G", "voltageRegulatorOn", true, true));
        CompactedChanges changes = EventCompactor.compact(events, VARIANT, networkWithGenerator("G"));

        assertEquals(List.of(), changes.events());
        assertFalse(changes.hasChange("G", "voltageRegulatorOn"));
    }

    /**
     * An echo whose new value is {@code null} (the deprecated setRegulatingTerminal(null) removing a terminal) is
     * compacted like any other: the lookup of the reported values must not throw (review 21 closing, found by the
     * no-op rows of c-m11).
     */
    @Test
    void anEchoWithANullNewValueIsCompacted() {
        List<NetworkEvent> events = List.of(update("G", "regulatingTerminal", "T", null));
        CompactedChanges changes = EventCompactor.compact(events, VARIANT, networkWithGenerator("G"));
        assertEquals(events, changes.events());
    }

    /** A network answering every identifiable lookup with one generator. */
    private static Network networkWithGenerator(String id) {
        Generator generator = (Generator) java.lang.reflect.Proxy.newProxyInstance(Generator.class.getClassLoader(),
                new Class<?>[] {Generator.class}, (proxy, method, args) -> {
                    if ("getId".equals(method.getName())) {
                        return id;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (Network) java.lang.reflect.Proxy.newProxyInstance(Network.class.getClassLoader(),
                new Class<?>[] {Network.class}, (proxy, method, args) -> {
                    if ("getIdentifiable".equals(method.getName())) {
                        return generator;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** The echo of a target is dropped: the target was reported under its own name first. */
    @Test
    @SuppressWarnings("removal")
    void aTargetEchoIsDropped() {
        Network network = SvcTestCaseFactory.create();
        StaticVarCompensator svc = network.getStaticVarCompensator("SVC2");
        double before = svc.getLocalTargetV();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> svc.setVoltageSetpoint(before + 1.0));
        assertEquals(2, events.size());
        CompactedChanges changes = EventCompactor.compact(events, VARIANT, network);

        assertEquals(List.of(events.get(0)), changes.events());
        assertEquals(before, changes.firstOldValue("SVC2", CgmesChangeTranslator.LOCAL_TARGET_V));
        assertFalse(changes.hasChange("SVC2", "voltageSetpoint"));
    }

    /**
     * Only the three target echoes need to know what equipment a change belongs to (a boundary line's {@code targetV} is
     * not an echo), so no other change costs an identifiable lookup during the compaction (review 21 finding F2/m1).
     */
    @Test
    void onlyTargetEchoesLookTheIdentifiableUp() {
        int[] lookups = {0};
        Network counting = (Network) java.lang.reflect.Proxy.newProxyInstance(Network.class.getClassLoader(),
                new Class<?>[] {Network.class}, (proxy, method, args) -> {
                    if ("getIdentifiable".equals(method.getName())) {
                        lookups[0]++;
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        EventCompactor.compact(List.of(update("L", "p0", 1.0, 2.0), update("G", "voltageRegulatorOn", true, false),
                update("S", "sectionCount", 1, 2)), VARIANT, counting);
        assertEquals(0, lookups[0]);

        EventCompactor.compact(List.of(update("G", "targetV", 400.0, 401.0)), VARIANT, counting);
        assertEquals(1, lookups[0]);
    }

    /** A boundary line generation is not a voltage regulation holder: its targetV is a value of its own. */
    @Test
    void aBoundaryLineTargetIsNotAnEcho() {
        Network network = BoundaryLineNetworkFactory.createWithGeneration();
        BoundaryLine.Generation generation = network.getBoundaryLine("BL").getGeneration();
        List<NetworkEvent> events = RecordedChangeScenarios.record(network, n -> generation.setTargetV(generation.getTargetV() + 1.0));
        CompactedChanges changes = EventCompactor.compact(events, VARIANT, network);

        assertEquals(events, changes.events());
        assertTrue(changes.hasChange("BL", CgmesChangeTranslator.TARGET_V));
    }
}
