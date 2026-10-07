/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conformity.CgmesConformity1ModifiedCatalog;
import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.export.CgmesExportContext;
import com.powsybl.cgmes.conversion.export.SteadyStateHypothesisExport;
import com.powsybl.commons.datasource.MemDataSource;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import com.powsybl.commons.xml.XmlUtil;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulation;
import com.powsybl.iidm.network.regulation.VoltageRegulationHolder;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * Replace the regulation of the holder by one created while another variant is the working one: in the working
     * variant it has no mode, and neither export can say what its RegulatingControl regulates.
     */
    private static void regulationWithoutMode(Network network, VoltageRegulationHolder<?> holder) {
        holder.removeVoltageRegulation();
        VariantManager variants = network.getVariantManager();
        String working = variants.getWorkingVariantId();
        variants.cloneVariant(working, "another variant");
        variants.setWorkingVariant("another variant");
        holder.newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(false).build();
        variants.setWorkingVariant(working);
    }

    private static String control(String ssh, String controlId) {
        int start = ssh.indexOf("RegulatingControl rdf:about=\"#_" + controlId + "\"");
        return start < 0 ? null : ssh.substring(start, ssh.indexOf("</cim:RegulatingControl>", start));
    }

    /**
     * B5: a RegulatingControl is written from the users the full export can describe; a user whose regulation has no
     * mode in this variant is left out of it (the export threw before).
     */
    @Test
    void aSharedControlIsWrittenFromTheUsersThatCanBeDescribed() {
        Network network = Network.read(CgmesConformity1ModifiedCatalog.microGridBaseCaseBESharedRegulatingControl().dataSource());
        Map<String, List<VoltageRegulationHolder<?>>> users = new LinkedHashMap<>();
        network.getGeneratorStream().filter(g -> g.getVoltageRegulation() != null && g.hasProperty(Conversion.PROPERTY_REGULATING_CONTROL))
                .forEach(g -> users.computeIfAbsent(g.getProperty(Conversion.PROPERTY_REGULATING_CONTROL), c -> new ArrayList<>()).add(g));
        network.getShuntCompensatorStream().filter(s -> s.getVoltageRegulation() != null && s.hasProperty(Conversion.PROPERTY_REGULATING_CONTROL))
                .forEach(s -> users.computeIfAbsent(s.getProperty(Conversion.PROPERTY_REGULATING_CONTROL), c -> new ArrayList<>()).add(s));
        Map.Entry<String, List<VoltageRegulationHolder<?>>> shared = users.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1).findFirst().orElseThrow();
        String controlId = shared.getKey();
        regulationWithoutMode(network, shared.getValue().get(0));

        String control = control(fullSsh(network), controlId);
        assertNotNull(control, "the control of the other users is written");
        VoltageRegulationHolder<?> other = shared.getValue().get(1);
        assertTrue(control.contains("<cim:RegulatingControl.enabled>" + other.isRegulating() + "</cim:RegulatingControl.enabled>"), control);
    }

    /** B5: a RegulatingControl none of whose users can be described is left out; the equipment is still written. */
    @Test
    void aControlWithoutAnyDescribableUserIsLeftOut() {
        Network network = readCgmesResources("/update/shunt-compensator/", "shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml");
        ShuntCompensator shunt = network.getShuntCompensator("LinearShuntCompensator");
        String controlId = shunt.getProperty(Conversion.PROPERTY_REGULATING_CONTROL);
        regulationWithoutMode(network, shunt);

        String ssh = fullSsh(network);
        assertNull(control(ssh, controlId));
        assertTrue(ssh.contains("#_LinearShuntCompensator\""), "the shunt compensator itself is written");
    }

    /**
     * B5, the change export's refusal the full export does not honour: a generator whose mode was changed after the
     * import is written with the control of its IIDM mode (cgmes-mode is a refusal of changes only).
     */
    @Test
    void aGeneratorInAnotherModeThanItsRecordedOneIsWrittenInItsIidmMode() {
        Network network = readCgmesResources("/update/generator/", "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = network.getGenerator("SynchronousMachine");
        VoltageRegulation regulation = generator.getVoltageRegulation();
        regulation.setTerminal(generator.getTerminal(), generator.getRegulatingTargetV());
        regulation.setMode(RegulationMode.REACTIVE_POWER);
        regulation.setTargetValue(10.0);

        String control = control(fullSsh(network), generator.getProperty(Conversion.PROPERTY_REGULATING_CONTROL));
        assertNotNull(control);
        assertTrue(control.contains("UnitMultiplier.M"), control);
        assertTrue(control.contains("<cim:RegulatingControl.targetValue>-10</cim:RegulatingControl.targetValue>"), control);
    }
}
