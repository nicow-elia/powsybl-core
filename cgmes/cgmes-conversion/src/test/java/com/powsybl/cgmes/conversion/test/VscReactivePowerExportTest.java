/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conversion.CgmesExport;
import com.powsybl.commons.datasource.GenericReadOnlyDataSource;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.regulation.RegulationMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A VSC converter station that stops regulating voltage and holds a reactive power target instead, the way pypowsybl
 * switches a station to reactive power control (review 21 round 2, R2-M4).
 *
 * <p>Since powsybl-core #3699 the deprecated {@code setVoltageRegulatorOn(false)} keeps the voltage mode and only stops
 * regulating, and {@code setReactivePowerSetpoint} then sets the local reactive power target. The full export writes
 * {@code qPccControl = reactivePcc} for such a station, so the reactive power target has to be the one it writes as
 * {@code targetQpcc}: writing zero, as for a station regulating voltage, loses the setpoint silently (powsybl-core 7.4
 * wrote it).</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class VscReactivePowerExportTest {

    private static final double TOLERANCE = 1e-6;

    @TempDir
    Path tmpDir;

    @SuppressWarnings("removal")
    static Stream<Arguments> switches() {
        Consumer<VscConverterStation> deprecated = station ->
                station.setVoltageRegulatorOn(false).setReactivePowerSetpoint(30.0);
        Consumer<VscConverterStation> voltageRegulation = station -> {
            station.getVoltageRegulation().setRegulating(false);
            station.setLocalTargetQ(30.0);
        };
        return Stream.of(Arguments.of("deprecated setters", deprecated),
                Arguments.of("VoltageRegulation API", voltageRegulation));
    }

    private static VscConverterStation station(Network network) {
        HvdcLine line = network.getHvdcLine("DCLineSegment-Vsc");
        return (VscConverterStation) line.getConverterStation2();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("switches")
    void theReactivePowerTargetOfAStationThatStoppedRegulatingVoltageIsExported(String name,
                                                                               Consumer<VscConverterStation> change)
            throws IOException {
        Network sender = readCgmesResources("/update/hvdc/", "hvdc_EQ.xml", "hvdc_SSH.xml");
        VscConverterStation station = station(sender);
        change.accept(station);
        assertEquals(RegulationMode.VOLTAGE, station.getVoltageRegulation().getMode());
        assertEquals(30.0, station.getRegulatingTargetQ(), TOLERANCE);

        Properties export = new Properties();
        export.put(CgmesExport.PROFILES, List.of("EQ", "SSH"));
        sender.write("CGMES", export, tmpDir.resolve("full"));

        String ssh;
        try (Stream<Path> files = Files.list(tmpDir)) {
            Path sshFile = files.filter(file -> file.getFileName().toString().endsWith("_SSH.xml")).findFirst()
                    .orElseThrow();
            ssh = Files.readString(sshFile);
        }
        String block = ssh.substring(ssh.indexOf("#_" + station.getId() + "\""));
        block = block.substring(0, block.indexOf("</cim:VsConverter>"));
        assertTrue(block.contains("VsQpccControlKind.reactivePcc"), block);
        assertTrue(block.contains("<cim:VsConverter.targetQpcc>-30</cim:VsConverter.targetQpcc>"), block);

        Network receiver = Network.read(new GenericReadOnlyDataSource(tmpDir, "full"));
        VscConverterStation received = station(receiver);
        assertEquals(RegulationMode.REACTIVE_POWER, received.getVoltageRegulation().getMode());
        assertEquals(30.0, received.getRegulatingTargetQ(), TOLERANCE);
    }
}
