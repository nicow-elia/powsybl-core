/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.regulation.RegulationMode;
import org.junit.jupiter.api.Test;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RegulatingControlFamilyTest {

    /**
     * A VoltageRegulation created while another variant is the working one has no mode in the working variant. A
     * ratio tap changer in that state, reached through its TapChangerControl, is refused like any other holder, and
     * never makes the export throw (review 21 finding m6).
     */
    @Test
    void aRatioTapChangerRegulationWithoutModeIsRefused() {
        Network network = readCgmesResources("/update/transformer/", "transformer_EQ.xml", "transformer_SSH.xml");
        ThreeWindingsTransformer transformer = network.getThreeWindingsTransformer("T3W");
        RatioTapChanger ratioTapChanger = transformer.getLeg2().getRatioTapChanger();
        ratioTapChanger.removeVoltageRegulation();
        VariantManager variants = network.getVariantManager();
        variants.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "other");
        variants.setWorkingVariant("other");
        ratioTapChanger.newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(false).build();
        variants.setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertNull(ratioTapChanger.getVoltageRegulation().getMode());

        RegulatingControlFamily controls = new RegulatingControlFamily(network, new CgmesExportContext(network));
        String controlId = controls.controlId(transformer, CgmesExportUtil.getRatioTapChangerAliasType("2")).orElseThrow();
        Result<CgmesPropertyBuffer, String> result = controls.updatesFor(controlId, IidmStateView.LIVE);

        Result.Failure<CgmesPropertyBuffer, String> failure = assertInstanceOf(Result.Failure.class, result);
        assertTrue(failure.reason().contains("has no mode in this variant"), failure.reason());
    }
}
