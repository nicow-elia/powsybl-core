/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.diff.FastRoutePlan.TypedObject;
import com.powsybl.cgmes.conversion.export.CgmesObjectDump;
import com.powsybl.cgmes.conversion.export.HvdcFamily;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.iidm.network.HvdcConverterStation;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.LccConverterStation;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.test.HvdcTestNetwork;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The setpoint blocks of both converters of an HVDC line of the simplified model are one group for the CGMES update
 * (powsybl-core #4057): every trigger of the HVDC family that writes the setpoint block of a converter writes the whole
 * block of both converters, and the in-place import completes the other converter of a difference that states one of
 * them from the family's description of the line, or refuses the fast route when the family cannot describe the line.
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

    private static List<String> probesOf(Identifiable<?> object) {
        return switch (object) {
            case HvdcLine ignored -> HvdcFamily.LINE_PROBES;
            case VscConverterStation ignored -> HvdcFamily.VSC_STATION_PROBES;
            case LccConverterStation ignored -> HvdcFamily.LCC_STATION_PROBES;
            default -> List.of();
        };
    }

    @Test
    void everyTriggerWritesTheSetpointsOfBothConverters() {
        int withSetpoints = 0;
        for (Supplier<Network> loader : List.<Supplier<Network>>of(HvdcFamilyTest::cgmes, HvdcTestNetwork::createVsc,
                HvdcTestNetwork::createLcc)) {
            Network network = loader.get();
            CgmesObjectDump dump = new CgmesObjectDump(network);
            for (HvdcLine line : network.getHvdcLines()) {
                Set<String> bothBlocks = new HashSet<>();
                for (HvdcConverterStation<?> station : List.of(line.getConverterStation1(), line.getConverterStation2())) {
                    HvdcFamily.SETPOINTS.forEach(property -> bothBlocks.add(station.getId() + " " + property));
                }
                for (Identifiable<?> object : List.of(line, line.getConverterStation1(), line.getConverterStation2())) {
                    for (String probe : probesOf(object)) {
                        Set<String> setpoints = new HashSet<>();
                        dump.statementsFor(object.getId(), probe).stream()
                                .filter(statement -> HvdcFamily.SETPOINTS.contains(statement.property()))
                                .forEach(statement -> setpoints.add(statement.subjectId() + " " + statement.property()));
                        if (!setpoints.isEmpty()) {
                            withSetpoints++;
                            assertEquals(bothBlocks, setpoints, () -> object.getId() + " " + probe);
                        }
                    }
                }
                // The description of the line is both blocks too
                Set<String> link = new HashSet<>();
                dump.linkStatementsFor(line.getId()).stream()
                        .filter(statement -> HvdcFamily.SETPOINTS.contains(statement.property()))
                        .forEach(statement -> link.add(statement.subjectId() + " " + statement.property()));
                assertEquals(bothBlocks, link, line.getId());
            }
        }
        // the power of each line, its power factor or reactive target where it has one: the test is not vacuous
        int triggers = withSetpoints;
        assertTrue(triggers >= 8, () -> "only " + triggers + " triggers wrote setpoints");
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
        assertTrue(new CgmesObjectDump(network).linkStatementsFor(VSC_LINE).isEmpty());
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
