/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.export.EventCompactor.CompactedChanges;
import com.powsybl.iidm.network.CurrentLimits;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.ExtensionCreationNetworkEvent;
import com.powsybl.iidm.network.events.ExtensionUpdateNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.OperationalLimitsInfo;
import com.powsybl.iidm.network.events.PermanentLimitInfo;
import com.powsybl.iidm.network.events.TemporaryLimitInfo;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class IidmStateViewTest {

    private static final double TOLERANCE = 1e-9;
    private static final String VARIANT = "InitialState";
    private static final String APC = "activePowerControl";

    private Network network;
    private Load load;

    @BeforeEach
    void setUp() {
        network = EurostagTutorialExample1Factory.create();
        load = network.getLoad("LOAD");
    }

    private static IidmStateView before(NetworkEvent... events) {
        CompactedChanges changes = EventCompactor.compact(List.of(events), VARIANT);
        return IidmStateView.before(changes);
    }

    private static UpdateNetworkEvent update(Identifiable<?> identifiable, String attribute, Object oldValue, Object newValue) {
        return new UpdateNetworkEvent(identifiable.getId(), attribute, VARIANT, oldValue, newValue);
    }

    @Test
    void liveReadsTheNetwork() {
        assertEquals(load.getP0(), IidmStateView.LIVE.getDouble(load, "p0", load::getP0), TOLERANCE);
        assertEquals(3, IidmStateView.LIVE.getInt(load, "sectionCount", () -> 3));
        assertTrue(IidmStateView.LIVE.getBoolean(load, "open", () -> true));
        assertEquals(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER,
                IidmStateView.LIVE.getEnum(load, "convertersMode", HvdcLine.ConvertersMode.class,
                        () -> HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER));
        assertEquals(0.5, IidmStateView.LIVE.getExtensionDouble(load, APC, "participationFactor", () -> 0.5), TOLERANCE);
        assertTrue(IidmStateView.LIVE.getExtensionBoolean(load, APC, "participate", () -> true));
        assertEquals(7, IidmStateView.LIVE.getExtensionInt(load, APC, "referencePriority", 0, () -> 7));
        assertTrue(IidmStateView.LIVE.unconsumedKeys().isEmpty());
    }

    @Test
    void overlayReturnsTheFirstOldValueAndFallsBackToTheNetwork() {
        IidmStateView state = before(
                update(load, "p0", 10.0, 11.0),
                update(load, "p0", 11.0, 12.5));

        assertEquals(10.0, state.getDouble(load, "p0", load::getP0), TOLERANCE);
        // Nothing changed q0, so it is the same before and after
        assertEquals(load.getQ0(), state.getDouble(load, "q0", load::getQ0), TOLERANCE);
    }

    @Test
    void overlayAcceptsEveryNumberType() {
        // The power factor of a line commutated converter is recorded as a Float
        IidmStateView state = before(update(load, "powerFactor", 0.9f, 0.95f),
                update(load, "sectionCount", 3, 4));
        assertEquals(0.9, state.getDouble(load, "powerFactor", () -> 0.0), 1e-6);
        assertEquals(3, state.getInt(load, "sectionCount", () -> 0));
    }

    @Test
    void overlayHandlesBooleansAndEnums() {
        IidmStateView state = before(update(load, "open", true, false),
                update(load, "convertersMode", HvdcLine.ConvertersMode.SIDE_1_INVERTER_SIDE_2_RECTIFIER,
                        HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER));
        assertTrue(state.getBoolean(load, "open", () -> false));
        assertEquals(HvdcLine.ConvertersMode.SIDE_1_INVERTER_SIDE_2_RECTIFIER,
                state.getEnum(load, "convertersMode", HvdcLine.ConvertersMode.class,
                        () -> HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER));
    }

    @Test
    void anUnrecordedPreviousValueIsUnreconstructible() {
        IidmStateView state = before(update(load, "sectionCount", null, 4));
        UnreconstructibleStateException exception = assertThrows(UnreconstructibleStateException.class,
                () -> state.getInt(load, "sectionCount", () -> 0));
        assertTrue(exception.getMessage().contains("LOAD.sectionCount"), exception.getMessage());
        assertTrue(exception.getMessage().contains("number"), exception.getMessage());
    }

    @Test
    void aPreviousValueOfAnUnexpectedTypeIsUnreconstructible() {
        IidmStateView state = before(update(load, "open", "yes", false),
                update(load, "convertersMode", "rectifier", HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER),
                update(load, "p0", "ten", 12.0));
        assertThrows(UnreconstructibleStateException.class, () -> state.getBoolean(load, "open", () -> false));
        assertThrows(UnreconstructibleStateException.class,
                () -> state.getEnum(load, "convertersMode", HvdcLine.ConvertersMode.class, () -> null));
        assertThrows(UnreconstructibleStateException.class, () -> state.getDouble(load, "p0", () -> 0.0));
    }

    @Test
    void aCreatedExtensionHasNoPreviousState() {
        IidmStateView state = before(new ExtensionCreationNetworkEvent(load.getId(), APC));
        UnreconstructibleStateException exception = assertThrows(UnreconstructibleStateException.class,
                () -> state.requireExtensionNotCreated(load, APC));
        assertTrue(exception.getMessage().contains(APC), exception.getMessage());
        // An extension that was already there is fine, and so is every extension under LIVE
        state.requireExtensionNotCreated(load, "referencePriorities");
        IidmStateView.LIVE.requireExtensionNotCreated(load, APC);
    }

    @Test
    void aCreatedExtensionStillHasADefinedIntValue() {
        IidmStateView state = before(new ExtensionCreationNetworkEvent(load.getId(), "referencePriorities"));
        assertEquals(0, state.getExtensionInt(load, "referencePriorities", "referencePriority", 0, () -> 3));
    }

    @Test
    void anExtensionIntWithoutARecordedOldValueFallsBackOnTheGivenDefault() {
        IidmStateView state = before(new ExtensionUpdateNetworkEvent(load.getId(), "referencePriorities",
                "referencePriority", VARIANT, null, 3));
        assertEquals(0, state.getExtensionInt(load, "referencePriorities", "referencePriority", 0, () -> 3));
    }

    @Test
    void extensionAttributesAreNamespacedByTheirExtension() {
        IidmStateView state = before(
                new ExtensionUpdateNetworkEvent(load.getId(), APC, "enabled", VARIANT, true, false),
                new ExtensionUpdateNetworkEvent(load.getId(), "generatorRemoteReactivePowerControl", "enabled", VARIANT, false, true));
        assertTrue(state.getExtensionBoolean(load, APC, "enabled", () -> false));
        assertFalse(state.getExtensionBoolean(load, "generatorRemoteReactivePowerControl", "enabled", () -> true));
    }

    @Test
    void unconsumedKeysAreTheOverlayValuesNoMappingRead() {
        IidmStateView state = before(
                update(load, "p0", 10.0, 12.5),
                update(load, "q0", 5.0, 6.0),
                new ExtensionUpdateNetworkEvent(load.getId(), APC, "participationFactor", VARIANT, 0.0, 0.5));
        assertEquals(Set.of("LOAD.p0", "LOAD.q0", "LOAD." + APC + "#participationFactor"), state.unconsumedKeys());

        state.getDouble(load, "p0", load::getP0);
        state.getExtensionDouble(load, APC, "participationFactor", () -> 0.5);
        assertEquals(Set.of("LOAD.q0"), state.unconsumedKeys());
    }

    // Loading limits

    private static final String WHOLE_KEY = "limits1_CURRENT@G";
    private static final String PATL_KEY = "limits1_CURRENT.permanentLimit@G";
    private static final String TATL_KEY = "limits1_CURRENT.temporaryLimit.value@G@600";

    /** A line with a current limits group holding a permanent limit and one temporary limit of 600 s. */
    private Line limitedLine() {
        Line line = network.getLine("NHV1_NHV2_1");
        line.newOperationalLimitsGroup1("G").newCurrentLimits()
                .setPermanentLimit(1000.0)
                .beginTemporaryLimit().setAcceptableDuration(600).setName("TATL 600").setValue(1200.0)
                .endTemporaryLimit()
                .add();
        return line;
    }

    private static CurrentLimits limitsOf(Line line) {
        return line.getOperationalLimitsGroup1("G").orElseThrow().getCurrentLimits().orElseThrow();
    }

    private static UpdateNetworkEvent permanentLimit(Line line, double oldValue, double newValue) {
        return update(line, "limits1_CURRENT.permanentLimit",
                new PermanentLimitInfo("PATL", oldValue, "G", true),
                new PermanentLimitInfo("PATL", newValue, "G", true));
    }

    private static UpdateNetworkEvent temporaryLimit(Line line, double oldValue, double newValue) {
        return update(line, "limits1_CURRENT.temporaryLimit.value",
                new TemporaryLimitInfo(oldValue, "G", true, 600),
                new TemporaryLimitInfo(newValue, "G", true, 600));
    }

    private static UpdateNetworkEvent wholeReplacement(Line line, CurrentLimits oldLimits, CurrentLimits newLimits) {
        return update(line, "limits1_CURRENT",
                new OperationalLimitsInfo(oldLimits, "G", true),
                new OperationalLimitsInfo(newLimits, "G", true));
    }

    @Test
    void limitValueFromMemberEvent() {
        Line line = limitedLine();
        IidmStateView state = before(permanentLimit(line, 900.0, 1000.0), temporaryLimit(line, 1100.0, 1200.0));
        assertEquals(900.0, state.getLimitValue(line, WHOLE_KEY, PATL_KEY, -1, () -> 1000.0), TOLERANCE);
        assertEquals(1100.0, state.getLimitValue(line, WHOLE_KEY, TATL_KEY, 600, () -> 1200.0), TOLERANCE);
    }

    @Test
    void anAttributeTheChangeSetDidNotTouchReadsLive() {
        Line line = limitedLine();
        IidmStateView state = before(update(load, "p0", 10.0, 12.5));
        assertEquals(1000.0, state.getLimitValue(line, WHOLE_KEY, PATL_KEY, -1, () -> 1000.0), TOLERANCE);
    }

    @Test
    void limitValueFromWholeReplacementUsesOldObject() {
        Line line = limitedLine();
        CurrentLimits old = limitsOf(line);
        IidmStateView state = before(wholeReplacement(line, old, old));
        assertEquals(1000.0, state.getLimitValue(line, WHOLE_KEY, PATL_KEY, -1, () -> 1.0), TOLERANCE);
        assertEquals(1200.0, state.getLimitValue(line, WHOLE_KEY, TATL_KEY, 600, () -> 1.0), TOLERANCE);
    }

    @Test
    void earliestEventWins() {
        Line line = limitedLine();
        CurrentLimits old = limitsOf(line);
        IidmStateView memberFirst = before(permanentLimit(line, 900.0, 950.0), wholeReplacement(line, old, old));
        assertEquals(900.0, memberFirst.getLimitValue(line, WHOLE_KEY, PATL_KEY, -1, () -> 1.0), TOLERANCE);

        IidmStateView wholeFirst = before(wholeReplacement(line, old, old), permanentLimit(line, 900.0, 950.0));
        assertEquals(1000.0, wholeFirst.getLimitValue(line, WHOLE_KEY, PATL_KEY, -1, () -> 1.0), TOLERANCE);
    }

    @Test
    void createdLimitsAreUnreconstructible() {
        Line line = limitedLine();
        IidmStateView state = before(wholeReplacement(line, null, limitsOf(line)));
        assertThrows(UnreconstructibleStateException.class,
            () -> state.getLimitValue(line, WHOLE_KEY, PATL_KEY, -1, () -> 1.0));
    }

    @Test
    void aTemporaryLimitThatDidNotExistBeforeIsUnreconstructible() {
        Line line = limitedLine();
        CurrentLimits old = limitsOf(line);
        IidmStateView state = before(wholeReplacement(line, old, old));
        assertThrows(UnreconstructibleStateException.class,
            () -> state.getLimitValue(line, WHOLE_KEY, "limits1_CURRENT.temporaryLimit.value@G@60", 60, () -> 1.0));
    }

    @Test
    void durationsAndPermanentLimitBeforeComeFromTheOldObject() {
        Line line = limitedLine();
        CurrentLimits old = limitsOf(line);
        SortedSet<Integer> live = new TreeSet<>(Set.of(60, 600));
        IidmStateView state = before(wholeReplacement(line, old, old));
        assertEquals(new TreeSet<>(Set.of(600)), state.getLimitDurations(line, WHOLE_KEY, () -> live));
        assertTrue(state.hasPermanentLimit(line, WHOLE_KEY, () -> false));

        IidmStateView untouched = before(update(load, "p0", 10.0, 12.5));
        assertEquals(live, untouched.getLimitDurations(line, WHOLE_KEY, () -> live));
        assertFalse(untouched.hasPermanentLimit(line, WHOLE_KEY, () -> false));
    }

    @Test
    void liveLimitAccessorsPassThrough() {
        Line line = limitedLine();
        SortedSet<Integer> live = new TreeSet<>(Set.of(600));
        assertEquals(7.0, IidmStateView.LIVE.getLimitValue(line, WHOLE_KEY, PATL_KEY, -1, () -> 7.0), TOLERANCE);
        assertEquals(live, IidmStateView.LIVE.getLimitDurations(line, WHOLE_KEY, () -> live));
        assertTrue(IidmStateView.LIVE.hasPermanentLimit(line, WHOLE_KEY, () -> true));
    }

    @Test
    void changesOfAnotherVariantAreNotInTheOverlay() {
        CompactedChanges changes = EventCompactor.compact(
                List.of(new UpdateNetworkEvent(load.getId(), "p0", "OtherVariant", 1.0, 2.0)), VARIANT);
        IidmStateView state = IidmStateView.before(changes);
        assertEquals(load.getP0(), state.getDouble(load, "p0", load::getP0), TOLERANCE);
        assertTrue(state.unconsumedKeys().isEmpty());
    }
}
