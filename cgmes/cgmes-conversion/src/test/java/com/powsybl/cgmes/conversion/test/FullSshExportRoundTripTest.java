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
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.function.Supplier;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        StringWriter out = new StringWriter();
        try {
            SteadyStateHypothesisExport.write(sender, XmlUtil.initializeWriter(true, "    ", out), new CgmesExportContext(sender));
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
}
