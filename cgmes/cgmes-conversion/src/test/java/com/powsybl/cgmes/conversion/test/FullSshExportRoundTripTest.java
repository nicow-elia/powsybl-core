/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conversion.export.CgmesExportContext;
import com.powsybl.cgmes.conversion.export.SteadyStateHypothesisExport;
import com.powsybl.commons.datasource.MemDataSource;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import com.powsybl.commons.xml.XmlUtil;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.PhaseTapChanger;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.function.Supplier;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The full steady state hypothesis export, read back through the CGMES update of a receiver that holds the same
 * equipment model: the receiver has to end in the state of the sender, for what the change mapping and the import
 * agree on. Each test is a behaviour of the full export that changed to write what the import reads.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class FullSshExportRoundTripTest {

    private static final double TOLERANCE = 1e-9;
    private static final String BOUNDARY_LINE_DIR = "/update/boundary-line/";

    /** The full SSH export of the sender, written alone (the equipment model is the one both sides hold). */
    static String fullSsh(Network sender) {
        return fullSsh(sender, false);
    }

    /** The full SSH export of the sender, as written alone or together with its equipment model. */
    static String fullSsh(Network sender, boolean withEquipment) {
        StringWriter out = new StringWriter();
        try {
            SteadyStateHypothesisExport.write(sender, XmlUtil.initializeWriter(true, "    ", out),
                    new CgmesExportContext(sender).setExportEquipment(withEquipment));
        } catch (XMLStreamException e) {
            throw new UncheckedXmlStreamException(e);
        }
        return out.toString();
    }

    /** A receiver loaded as the sender was, updated with the full SSH export of the sender. */
    static Network roundTrip(Network sender, Supplier<Network> receiverLoader) {
        Network receiver = receiverLoader.get();
        MemDataSource dataSource = new MemDataSource();
        dataSource.putData("full_SSH.xml", fullSsh(sender).getBytes(StandardCharsets.UTF_8));
        receiver.update(dataSource, new Properties());
        return receiver;
    }

    private static Network boundaryLines() {
        return readCgmesResources(BOUNDARY_LINE_DIR, "boundaryLine_EQ.xml", "boundaryLine_EQ_BD.xml", "boundaryLine_SSH.xml");
    }

    /**
     * B1: the import puts the whole injection of a boundary EquivalentInjection into the generation of the boundary
     * line ({@code EquivalentInjectionConversion.update}), so the full export writes {@code p0 - targetP} and
     * {@code q0 - targetQ}, as the change mapping does, and the generation targets survive.
     */
    @Test
    void theGenerationOfABoundaryLineSurvivesTheFullExport() {
        Network sender = boundaryLines();
        BoundaryLine.Generation generation = sender.getBoundaryLine("EquivalentBranch").getGeneration();
        generation.setTargetP(-123.0).setTargetQ(-45.0);

        Network receiver = roundTrip(sender, FullSshExportRoundTripTest::boundaryLines);

        BoundaryLine received = receiver.getBoundaryLine("EquivalentBranch");
        assertEquals(-123.0, received.getGeneration().getTargetP(), TOLERANCE);
        assertEquals(-45.0, received.getGeneration().getTargetQ(), TOLERANCE);
        assertEquals(0.0, received.getP0(), TOLERANCE);
        assertEquals(0.0, received.getQ0(), TOLERANCE);
    }

    private static Network transformers() {
        return readCgmesResources("/update/transformer/", "transformer_EQ.xml", "transformer_SSH.xml");
    }

    private static Network currentLimiter() {
        Network network = transformers();
        network.getTwoWindingsTransformer("T2W").getPhaseTapChanger().setRegulating(false)
                .setRegulationMode(PhaseTapChanger.RegulationMode.CURRENT_LIMITER)
                .setRegulationValue(800.0)
                .setTargetDeadband(10.0);
        return network;
    }

    /**
     * B3: the TapChangerControl the import recorded for a phase tap changer limiting current is written with the
     * values the tap changer has (a current in Amperes, multiplier none) in an SSH read against that equipment model,
     * as the change export writes it; the update reads them back.
     */
    @Test
    void aCurrentLimiterKeepsItsValuesThroughAnSshAlone() {
        Network sender = currentLimiter();
        String ssh = fullSsh(sender);
        String control = ssh.substring(ssh.indexOf("\"#_T2W-PhaseTapChanger-Control\""));
        control = control.substring(0, control.indexOf("</cim:TapChangerControl>"));
        assertTrue(control.contains("<cim:RegulatingControl.targetValue>800</cim:RegulatingControl.targetValue>"), control);
        assertTrue(control.contains("UnitMultiplier.none"), control);

        Network receiver = roundTrip(sender, () -> {
            Network network = transformers();
            network.getTwoWindingsTransformer("T2W").getPhaseTapChanger()
                    .setRegulationMode(PhaseTapChanger.RegulationMode.CURRENT_LIMITER);
            return network;
        });
        PhaseTapChanger received = receiver.getTwoWindingsTransformer("T2W").getPhaseTapChanger();
        assertEquals(800.0, received.getRegulationValue(), TOLERANCE);
        assertEquals(10.0, received.getTargetDeadband(), TOLERANCE);
        assertFalse(received.isRegulating());
    }

    /**
     * B3, the other case: with the equipment model the export writes the current limit as a CurrentLimit of the
     * regulated terminal, and the TapChangerControl keeps upstream's zeros with multiplier M.
     */
    @Test
    void aCurrentLimiterWrittenWithItsEquipmentModelKeepsTheZerosOfTheControl() {
        String ssh = fullSsh(currentLimiter(), true);
        String control = ssh.substring(ssh.indexOf("TapChangerControl rdf:about=\"#_T2W-PhaseTapChanger-Control\""));
        control = control.substring(0, control.indexOf("</cim:TapChangerControl>"));
        assertTrue(control.contains("<cim:RegulatingControl.targetValue>0</cim:RegulatingControl.targetValue>"), control);
        assertTrue(control.contains("UnitMultiplier.M"), control);
    }
}
