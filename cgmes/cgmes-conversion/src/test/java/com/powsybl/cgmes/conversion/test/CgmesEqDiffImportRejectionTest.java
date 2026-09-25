/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conversion.diff.CgmesDiffImport;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The equipment statements a difference model importer refuses against a real network.
 *
 * <p>These are the network aware half of the decision function: the document alone may look applicable, but the
 * subject has to exist, be of a kind whose impedance CGMES holds as one value, and carry a value IIDM accepts. Every
 * one of them is answered before anything is modified.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesEqDiffImportRejectionTest {

    private static final String LINE_DIR = "/update/line/";
    private static final String[] LINE_FILES = {"line_EQ.xml", "line_SSH.xml"};
    private static final String TRANSFORMER_DIR = "/update/transformer/";
    private static final String[] TRANSFORMER_FILES = {"transformer_EQ.xml", "transformer_SSH.xml"};
    private static final String VOLTAGE_LEVEL_DIR = "/update/voltage-level/";
    private static final String[] VOLTAGE_LEVEL_FILES = {"voltageLevel_EQ.xml", "voltageLevel_SSH.xml"};
    private static final String AC_LINE_SEGMENT_R = "ACLineSegment.r";

    private static Network lineNetwork() {
        return readCgmesResources(LINE_DIR, LINE_FILES);
    }

    private static DifferenceModelSet equipment(CgmesStatement... forward) {
        return new DifferenceModelSet(List.of(new DifferenceModel(
                DifferenceModelHeader.builder("urn:uuid:hand-made", CgmesSubset.EQUIPMENT,
                        CgmesNamespace.CIM_100_NAMESPACE).build(),
                List.of(forward), List.of(), List.of())));
    }

    private static List<String> reasons(Network network, DifferenceModelSet set) {
        CgmesDiffImport.Decision decision = CgmesDiffImport.canApplyInPlace(network, set);
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route(), decision.reasons().toString());
        return decision.reasons();
    }

    @Test
    void anImpedanceThatIsNotANumberIsRejected() {
        assertTrue(reasons(lineNetwork(),
                equipment(CgmesStatement.literal("ACLineSegment", null, AC_LINE_SEGMENT_R, "abc")))
                .get(0).contains("is not a number"));
    }

    @Test
    void anInfiniteImpedanceIsRejected() {
        assertTrue(reasons(lineNetwork(),
                equipment(CgmesStatement.literal("ACLineSegment", null, AC_LINE_SEGMENT_R, "NaN")))
                .get(0).contains("must be finite"));
    }

    @Test
    void aNegativeResistanceIsRejected() {
        assertTrue(reasons(lineNetwork(),
                equipment(CgmesStatement.literal("ACLineSegment", null, AC_LINE_SEGMENT_R, "-1")))
                .get(0).contains("r, x >= 0"));
    }

    @Test
    void anImpedanceOfATransformerIsRejected() {
        Network network = readCgmesResources(TRANSFORMER_DIR, TRANSFORMER_FILES);
        assertTrue(reasons(network, equipment(CgmesStatement.literal("T2W", null, AC_LINE_SEGMENT_R, "2")))
                .get(0).contains("carries no steady state hypothesis properties of its own"));
    }

    @Test
    void aPowerTransformerEndPropertyIsRejected() {
        Network network = readCgmesResources(TRANSFORMER_DIR, TRANSFORMER_FILES);
        assertTrue(reasons(network, equipment(CgmesStatement.literal("T2W", null, "PowerTransformerEnd.r", "2")))
                .get(0).contains("not part of the in-place update"));
    }

    @Test
    void theNormalValueOfALimitIsRejected() {
        assertTrue(reasons(lineNetwork(), equipment(CgmesStatement.literal(
                "ACLineSegment-T1-OperationalLimitSet-CurrentLimit1", null, "CurrentLimit.normalValue", "800")))
                .get(0).contains("not part of the in-place update"));
    }

    @Test
    void anUnknownOperationalLimitIsRejected() {
        assertTrue(reasons(lineNetwork(),
                equipment(CgmesStatement.literal("NoSuchLimit", null, "ACLineSegment.r", "2")))
                .get(0).contains("no object of this network has this identifier"));
    }

    @Test
    void aVoltageLevelWhoseLimitsWouldCrossIsRejected() {
        Network network = readCgmesResources(VOLTAGE_LEVEL_DIR, VOLTAGE_LEVEL_FILES);
        assertTrue(reasons(network, equipment(
                CgmesStatement.literal("VL_1", null, "VoltageLevel.highVoltageLimit", "300"),
                CgmesStatement.literal("VL_1", null, "VoltageLevel.lowVoltageLimit", "400")))
                .get(0).contains("would be low"));
    }

    /**
     * IIDM refuses a negative voltage limit in its setter, which would throw after the update workflow and the other
     * setters had already run, so the plan has to catch it while nothing is modified yet.
     */
    @Test
    void aNegativeVoltageLimitIsRejected() {
        Network network = readCgmesResources(VOLTAGE_LEVEL_DIR, VOLTAGE_LEVEL_FILES);
        String high = String.valueOf(network.getVoltageLevel("VL_1").getHighVoltageLimit());
        assertTrue(reasons(network, equipment(
                CgmesStatement.literal("VL_1", null, "VoltageLevel.lowVoltageLimit", "-10")))
                .get(0).contains("voltage limits must be positive"));
        assertEquals(high, String.valueOf(network.getVoltageLevel("VL_1").getHighVoltageLimit()),
                "nothing may be modified by a refused plan");
    }

    /**
     * A line the CGMES import turned into a switch has no impedance of its own any more, so a difference cannot put
     * one back on it.
     */
    @Test
    void aBranchModelledAsASwitchIsRejected() {
        Network network = readCgmesResources("/update/switch/", "switch_EQ.xml", "switch_SSH.xml");
        assertTrue(reasons(network,
                equipment(CgmesStatement.literal("SeriesCompensator", null, "SeriesCompensator.r", "2")))
                .get(0).contains("modelled as a switch"));
    }
}
