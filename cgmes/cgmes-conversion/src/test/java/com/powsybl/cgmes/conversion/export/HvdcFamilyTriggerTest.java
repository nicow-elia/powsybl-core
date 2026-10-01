/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.HvdcConverterStation;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.LccConverterStation;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import com.powsybl.iidm.network.test.HvdcTestNetwork;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The setpoint blocks of both converters of an HVDC line of the simplified model are one group for the CGMES update
 * (powsybl-core #4057): every change of the HVDC family that writes the setpoint block of a converter (a key of the
 * line, of a voltage source converter station, the power factor of a line commutated one) writes the whole block of
 * both converters, and the description of the line the in-place import completes a partner from is both blocks too.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class HvdcFamilyTriggerTest {

    private static Network cgmes() {
        return readCgmesResources("/update/hvdc/", "hvdc_EQ.xml", "hvdc_SSH.xml");
    }

    private static Set<String> keysOf(Identifiable<?> object) {
        return switch (object) {
            case HvdcLine ignored -> HvdcFamily.LINE_KEYS;
            case VscConverterStation ignored -> HvdcFamily.CONTROL_KEYS;
            case LccConverterStation ignored -> Set.of(CgmesChangeTranslator.POWER_FACTOR);
            default -> Set.of();
        };
    }

    @Test
    void everyTriggerWritesTheSetpointsOfBothConverters() {
        int withSetpoints = 0;
        for (Supplier<Network> loader : List.<Supplier<Network>>of(HvdcFamilyTriggerTest::cgmes, HvdcTestNetwork::createVsc,
                HvdcTestNetwork::createLcc)) {
            Network network = loader.get();
            CgmesExportContext context = new CgmesExportContext(network);
            CgmesChangeTranslator translator = new CgmesChangeTranslator(network, context,
                    PartialSshExport.UnsupportedChangeBehavior.IGNORE);
            String variantId = network.getVariantManager().getWorkingVariantId();
            for (HvdcLine line : network.getHvdcLines()) {
                Set<String> bothBlocks = new HashSet<>();
                for (HvdcConverterStation<?> station : List.of(line.getConverterStation1(), line.getConverterStation2())) {
                    HvdcFamily.SETPOINTS.forEach(property -> bothBlocks.add(station.getId() + " " + property));
                }
                for (Identifiable<?> object : List.of(line, line.getConverterStation1(), line.getConverterStation2())) {
                    for (String key : keysOf(object)) {
                        Set<String> setpoints = new HashSet<>();
                        if (translator.translate(new UpdateNetworkEvent(object.getId(), key, variantId, null, null))
                                instanceof Result.Success(CgmesPropertyBuffer buffer)) {
                            buffer.statements(CgmesSubset.STEADY_STATE_HYPOTHESIS, context).stream()
                                    .filter(statement -> HvdcFamily.SETPOINTS.contains(statement.property()))
                                    .forEach(statement -> setpoints.add(statement.subjectId() + " " + statement.property()));
                        }
                        if (!setpoints.isEmpty()) {
                            withSetpoints++;
                            assertEquals(bothBlocks, setpoints, () -> object.getId() + " " + key);
                        }
                    }
                }
                // The description of the line is both blocks too
                Set<String> link = new HashSet<>();
                new Families(network).linkStatements(line).stream()
                        .filter(statement -> HvdcFamily.SETPOINTS.contains(statement.property()))
                        .forEach(statement -> link.add(statement.subjectId() + " " + statement.property()));
                assertEquals(bothBlocks, link, line.getId());
            }
        }
        // the power of each line, its power factor or reactive target where it has one: the test is not vacuous
        int triggers = withSetpoints;
        assertTrue(triggers >= 8, () -> "only " + triggers + " triggers wrote setpoints");
    }
}
