/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conversion.CgmesExport;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.export.CgmesExportUtil;
import com.powsybl.commons.datasource.GenericReadOnlyDataSource;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.regulation.RegulationMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The full SSH export of a regulation target measured at a regulating terminal that CGMES orients the other way than
 * IIDM ({@code CGMES.terminalSign = -1}).
 *
 * <p>The import multiplies such a target by the recorded sign. An SSH exported alone is read against the ORIGINAL
 * equipment model, whose regulating terminal carries the sign, so the export applies the sign too. An SSH exported
 * together with its equipment model is read against the NEW one, which names the IIDM regulating terminal itself: the
 * sign is then +1 on re-import and the export must not apply it (review 21 finding M1).</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class TerminalSignExportTest {

    private static final double TOLERANCE = 1e-6;
    private static final String SIGN = CgmesExportUtil.getTerminalSignPropertyName("");

    @TempDir
    Path tmpDir;

    record Family(String name, String dir, String[] files, Consumer<Network> reverseAndChange,
                  ToDoubleFunction<Network> target) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> families() {
        Family svc = new Family("static var compensator", "/update/static-var-compensator/",
                new String[] {"staticVarCompensator_EQ.xml", "staticVarCompensator_SSH.xml"},
                n -> {
                    n.getStaticVarCompensator("StaticVarCompensator-Q").setProperty(SIGN, "-1");
                    n.getStaticVarCompensator("StaticVarCompensator-Q").getVoltageRegulation().setTargetValue(220.0);
                },
                n -> n.getStaticVarCompensator("StaticVarCompensator-Q").getRegulatingTargetQ());
        Family phaseTapChanger = new Family("phase tap changer", "/update/transformer/",
                new String[] {"transformer_EQ.xml", "transformer_SSH.xml"},
                n -> {
                    n.getTwoWindingsTransformer("T2W").setProperty(SIGN, "-1");
                    PhaseTapChanger ptc = n.getTwoWindingsTransformer("T2W").getPhaseTapChanger();
                    ptc.setRegulationValue(ptc.getRegulationValue() + 5.0);
                },
                n -> n.getTwoWindingsTransformer("T2W").getPhaseTapChanger().getRegulationValue());
        Family vsc = new Family("VSC converter station", "/update/hvdc/", new String[] {"hvdc_EQ.xml", "hvdc_SSH.xml"},
                n -> {
                    VscConverterStation station = station(n);
                    station.setProperty(SIGN, "-1");
                    RecordedChangeScenarios.regulateOwnTerminal(station);
                    station.getVoltageRegulation().setMode(RegulationMode.REACTIVE_POWER);
                    station.getVoltageRegulation().setTargetValue(30.0);
                },
                n -> station(n).getRegulatingTargetQ());
        Family ratioTapChanger = new Family("ratio tap changer regulating reactive power", "/issues/voltageRegulation/",
                new String[] {"transformer_EQ.xml", "transformer_SSH.xml"},
                n -> {
                    n.getTwoWindingsTransformer("PT2_2").setProperty(SIGN, "-1");
                    n.getTwoWindingsTransformer("PT2_2").getRatioTapChanger().getVoltageRegulation().setTargetValue(12.0);
                },
                n -> n.getTwoWindingsTransformer("PT2_2").getRatioTapChanger().getRegulatingTargetQ());
        return Stream.of(svc, phaseTapChanger, vsc, ratioTapChanger).map(Arguments::of);
    }

    private static VscConverterStation station(Network network) {
        HvdcLine line = network.getHvdcLine("DCLineSegment-Vsc");
        return (VscConverterStation) line.getConverterStation2();
    }

    /** SSH alone, read against the original equipment model: the sign travels. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("families")
    void sshExportedAloneKeepsTheTarget(Family family) {
        Network sender = readCgmesResources(family.dir(), family.files());
        family.reverseAndChange().accept(sender);
        Properties export = new Properties();
        export.put(CgmesExport.PROFILES, List.of("SSH"));
        sender.write("CGMES", export, tmpDir.resolve("ssh"));

        Network receiver = readCgmesResources(family.dir(), family.files());
        family.reverseAndChange().accept(receiver);
        Properties update = new Properties();
        update.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");
        receiverOriginalTarget(receiver, family);
        receiver.update(new GenericReadOnlyDataSource(tmpDir, "ssh"), update);

        assertEquals(family.target().applyAsDouble(sender), family.target().applyAsDouble(receiver), TOLERANCE);
    }

    /** EQ and SSH together, read against the exported equipment model: no sign. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("families")
    void sshExportedWithItsEquipmentModelKeepsTheTarget(Family family) {
        Network sender = readCgmesResources(family.dir(), family.files());
        family.reverseAndChange().accept(sender);
        Properties export = new Properties();
        export.put(CgmesExport.PROFILES, List.of("EQ", "SSH"));
        sender.write("CGMES", export, tmpDir.resolve("full"));

        Network receiver = Network.read(new GenericReadOnlyDataSource(tmpDir, "full"));

        assertEquals(family.target().applyAsDouble(sender), family.target().applyAsDouble(receiver), TOLERANCE);
    }

    /** Put the receiver back to the value of the base files, so that only the file can bring the change. */
    private static void receiverOriginalTarget(Network receiver, Family family) {
        Network original = readCgmesResources(family.dir(), family.files());
        double value = family.target().applyAsDouble(original);
        switch (family.name()) {
            case "static var compensator" ->
                receiver.getStaticVarCompensator("StaticVarCompensator-Q").getVoltageRegulation().setTargetValue(value);
            case "phase tap changer" -> receiver.getTwoWindingsTransformer("T2W").getPhaseTapChanger().setRegulationValue(value);
            case "ratio tap changer regulating reactive power" ->
                receiver.getTwoWindingsTransformer("PT2_2").getRatioTapChanger().getVoltageRegulation().setTargetValue(value);
            default -> station(receiver).getVoltageRegulation().setTargetValue(value);
        }
    }
}
