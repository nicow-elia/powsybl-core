/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.diff.FastRoutePlan.TypedObject;
import com.powsybl.cgmes.conversion.export.Families;
import com.powsybl.cgmes.conversion.export.HvdcFamily;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VscConverterStation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The setpoint blocks of both converters of an HVDC line of the simplified model are one group for the CGMES update
 * (powsybl-core #4057): the in-place import completes the other converter of a difference that states one of them from
 * the family's description of the line, or refuses the fast route when the family cannot describe the line. That every
 * trigger of the family writes both blocks is {@code HvdcFamilyTriggerTest}.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class HvdcFamilyTest {

    private static final String HVDC_DIR = "/update/hvdc/";
    private static final String VSC_LINE = "DCLineSegment-Vsc";
    private static final String VSC_1 = "DCLineSegment-Vsc-VscConverter-1";
    private static final String VSC_2 = "DCLineSegment-Vsc-VscConverter-2";

    private static Network cgmes() {
        return readCgmesResources(HVDC_DIR, "hvdc_EQ.xml", "hvdc_SSH.xml");
    }

    @Test
    void aDifferenceStatingOneConverterIsCompletedWithTheOther() {
        FastRoutePlan plan = planOneConverter(cgmes());
        assertEquals(CgmesDiffImport.Route.FAST, plan.decision().route(), () -> plan.decision().toString());
        Set<String> subjects = new HashSet<>();
        for (TypedObject object : plan.models().get(0).objects()) {
            object.statements().stream().filter(statement -> HvdcFamily.SETPOINTS.contains(statement.property()))
                    .forEach(statement -> subjects.add(statement.subjectId()));
        }
        assertEquals(Set.of(VSC_1, VSC_2), subjects);
    }

    /**
     * A line the family cannot describe (a converter that does not regulate: a VsConverter has no control flag) gives
     * no partner block, and the converter the difference states cannot travel alone: the fast route is refused.
     */
    @Test
    void aConverterWhosePartnerCannotBeDescribedIsRefused() {
        Network network = cgmes();
        ((VscConverterStation) network.getHvdcLine(VSC_LINE).getConverterStation1()).getVoltageRegulation().setRegulating(false);
        assertTrue(new Families(network).linkStatements(network.getHvdcLine(VSC_LINE)).isEmpty());
        FastRoutePlan plan = planOneConverter(network);
        assertNotEquals(CgmesDiffImport.Route.FAST, plan.decision().route());
    }

    /** A difference that states the power of the rectifier of the VSC line only. */
    private static FastRoutePlan planOneConverter(Network receiver) {
        List<CgmesStatement> forward = new ArrayList<>(List.of(
                CgmesStatement.literal(VSC_2, "VsConverter", "ACDCConverter.targetPpcc", "400")));
        List<CgmesStatement> reverse = List.of(
                CgmesStatement.literal(VSC_2, "VsConverter", "ACDCConverter.targetPpcc", "497.7"));
        DifferenceModelSet diffs = new DifferenceModelSet(List.of(new DifferenceModel(
                DifferenceModelHeader.builder("urn:uuid:hvdc-test", CgmesSubset.STEADY_STATE_HYPOTHESIS,
                        CgmesNamespace.CIM_16_NAMESPACE).build(), forward, reverse, List.of())));
        return FastRoutePlan.of(receiver, diffs, new CgmesDiffImport.Options(), false);
    }
}
