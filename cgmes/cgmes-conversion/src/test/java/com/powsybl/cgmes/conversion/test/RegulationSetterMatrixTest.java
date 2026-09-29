/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.diff.CgmesDiffImport;
import com.powsybl.cgmes.conversion.diff.CgmesDiffNotApplicableException;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.DifferenceModelWriter;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.datasource.MemDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.BusbarSection;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulation;
import com.powsybl.iidm.network.regulation.VoltageRegulationHolder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.AssertionFailedError;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The contract of both change exports for every way a voltage regulation can change, checked systematically.
 *
 * <p>The matrix is: every regulation holder the CGMES import creates &times; every setter that can change its
 * regulation state, the setters of the {@link VoltageRegulation} API as well as the deprecated bridges upstream
 * still has &times; the holder has a {@code VoltageRegulation} or has none &times; the setter really changes the
 * value or sets the value it already has. A {@code Battery} is not part of it: the CGMES import creates none.</p>
 *
 * <p>For every case, the change is recorded on a sender and exported through the partial steady state hypothesis and
 * through the difference model, in both granularities. Each export either</p>
 * <ul>
 *     <li>takes a receiver to the state of the sender (the difference model also takes it back to the original state
 *     when it is reverted), or</li>
 *     <li>refuses the change with a message that names the cause and a remedy ({@value #REMEDY}).</li>
 * </ul>
 * <p>A silently empty export and a receiver that ends in another state than the sender are both failures. The only
 * values a receiver may legitimately differ on are the local targets its regulation does not use, see
 * {@link #inactiveLocalTargets}. The only change that may go unexported is one IIDM reports nowhere: a deprecated
 * setter that created the {@code VoltageRegulation} and changed nothing else (gap G1, see {@link #reportsNothing}).
 * A partial file that describes nothing is not applied, since a CGMES update visits the whole receiver whatever the
 * file holds.</p>
 *
 * <p>{@code -Dmatrix.trace=true} prints the recorded events and the outcome of every route of every case.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RegulationSetterMatrixTest {

    /** What a refusal of a regulation change says before its remedy. */
    static final String REMEDY = "Remedy: ";

    /**
     * Cases that fail at the commit that introduces this matrix, each after the finding of review 21 (round 2) that
     * fixes it: R2-B1 (the difference route completes the setpoint block of one converter of a link only),
     * R2-M1/R2-M2 (the normalisation of the echoes of the deprecated setters), r2-m3/r2-m4 (a refusal that names no
     * remedy, or a holder without VoltageRegulation exported although the import gives it one). The test of such a
     * case passes only while the case fails, so a fix that lands makes it fail until its entry is removed.
     */
    private static final Map<String, String> EXPECTED_TO_FAIL = expectedToFail("""
            r2-m3/r2-m4 | generator regulating locally | VoltageRegulation.setTargetDeadband | with regulation | change
            r2-m3/r2-m4 | generator regulating locally | setLocalTargetV | without regulation | change
            r2-m3/r2-m4 | generator regulating locally | setLocalTargetQ | without regulation | change
            r2-m3/r2-m4 | generator regulating locally | Generator.setTargetV | without regulation | change
            r2-m3/r2-m4 | generator regulating locally | Generator.setTargetQ | without regulation | change
            r2-m3/r2-m4 | generator regulating locally | Generator.setRegulatingTerminal | with regulation | change
            r2-m3/r2-m4 | generator regulating locally | Generator.setRegulatingTerminal | with regulation | no-op
            r2-m3/r2-m4 | generator regulating locally | Generator.setRegulatingTerminal | without regulation | change
            r2-m3/r2-m4 | generator regulating locally | Generator.setRegulatingTerminal | without regulation | no-op
            r2-m3/r2-m4 | generator regulating remotely | VoltageRegulation.setTargetDeadband | with regulation | change
            r2-m3/r2-m4 | generator regulating remotely | VoltageRegulation.setMode | with regulation | change
            r2-m3/r2-m4 | generator regulating remotely | VoltageRegulation.setTerminal | with regulation | change
            r2-m3/r2-m4 | generator regulating remotely | Generator.setRegulatingTerminal | with regulation | change
            r2-m3/r2-m4 | generator without a CGMES regulating control | setLocalTargetV | without regulation | change
            r2-m3/r2-m4 | generator without a CGMES regulating control | Generator.setTargetV | without regulation | change
            r2-m3/r2-m4 | generator without a CGMES regulating control | Generator.setTargetV(v, local) | without regulation | change
            r2-m3/r2-m4 | generator without a CGMES regulating control | Generator.setRegulatingTerminal | without regulation | change
            r2-m3/r2-m4 | generator without a CGMES regulating control | Generator.setRegulatingTerminal | without regulation | no-op
            r2-m3/r2-m4 | shunt compensator | VoltageRegulation.setTerminal | with regulation | change
            r2-m3/r2-m4 | shunt compensator | setLocalTargetV | without regulation | change
            r2-m3/r2-m4 | shunt compensator | ShuntCompensator.setTargetV | without regulation | change
            r2-m3/r2-m4 | shunt compensator | ShuntCompensator.setRegulatingTerminal | with regulation | change
            r2-m3/r2-m4 | shunt compensator | ShuntCompensator.setRegulatingTerminal | with regulation | no-op
            r2-m3/r2-m4 | shunt compensator | ShuntCompensator.setRegulatingTerminal | without regulation | change
            r2-m3/r2-m4 | shunt compensator | ShuntCompensator.setRegulatingTerminal | without regulation | no-op
            r2-m3/r2-m4 | equivalent shunt | setLocalTargetV | without regulation | change
            r2-m3/r2-m4 | equivalent shunt | ShuntCompensator.setTargetV | without regulation | change
            r2-m3/r2-m4 | equivalent shunt | ShuntCompensator.setRegulatingTerminal | without regulation | change
            r2-m3/r2-m4 | equivalent shunt | ShuntCompensator.setRegulatingTerminal | without regulation | no-op
            r2-m3/r2-m4 | static var compensator | VoltageRegulation.setTargetDeadband | with regulation | change
            r2-m3/r2-m4 | static var compensator | VoltageRegulation.setMode | with regulation | change
            r2-m3/r2-m4 | static var compensator | setLocalTargetV | without regulation | change
            r2-m3/r2-m4 | static var compensator | setLocalTargetQ | without regulation | change
            r2-m3/r2-m4 | static var compensator | StaticVarCompensator.setVoltageSetpoint | without regulation | change
            r2-m3/r2-m4 | static var compensator | StaticVarCompensator.setReactivePowerSetpoint | without regulation | change
            r2-m3/r2-m4 | static var compensator | StaticVarCompensator.setRegulationMode | with regulation | change
            r2-m3/r2-m4 | static var compensator | StaticVarCompensator.setRegulatingTerminal | with regulation | change
            r2-m3/r2-m4 | static var compensator | StaticVarCompensator.setRegulatingTerminal | with regulation | no-op
            r2-m3/r2-m4 | static var compensator | StaticVarCompensator.setRegulatingTerminal | without regulation | change
            r2-m3/r2-m4 | static var compensator | StaticVarCompensator.setRegulatingTerminal | without regulation | no-op
            r2-m3/r2-m4 | static var compensator regulating reactive power | VoltageRegulation.setTargetDeadband | with regulation | change
            r2-m3/r2-m4 | static var compensator regulating reactive power | VoltageRegulation.setMode | with regulation | change
            r2-m3/r2-m4 | static var compensator regulating reactive power | VoltageRegulation.setTerminal | with regulation | change
            r2-m3/r2-m4 | static var compensator regulating reactive power | setLocalTargetV | without regulation | change
            r2-m3/r2-m4 | static var compensator regulating reactive power | setLocalTargetQ | without regulation | change
            r2-m3/r2-m4 | static var compensator regulating reactive power | StaticVarCompensator.setVoltageSetpoint | without regulation | change
            r2-m3/r2-m4 | static var compensator regulating reactive power | StaticVarCompensator.setReactivePowerSetpoint | without regulation | change
            r2-m3/r2-m4 | static var compensator regulating reactive power | StaticVarCompensator.setRegulationMode | with regulation | change
            r2-m3/r2-m4 | static var compensator regulating reactive power | StaticVarCompensator.setRegulatingTerminal | with regulation | change
            r2-m3/r2-m4 | static var compensator regulating reactive power | StaticVarCompensator.setRegulatingTerminal | without regulation | change
            r2-m3/r2-m4 | static var compensator regulating reactive power | StaticVarCompensator.setRegulatingTerminal | without regulation | no-op
            r2-m3/r2-m4 | ratio tap changer | VoltageRegulation.setMode | with regulation | change
            r2-m3/r2-m4 | ratio tap changer | VoltageRegulation.setTerminal | with regulation | change
            r2-m3/r2-m4 | ratio tap changer | RatioTapChanger.setRegulationMode | with regulation | change
            r2-m3/r2-m4 | ratio tap changer | RatioTapChanger.setRegulationTerminal | with regulation | change
            r2-m3/r2-m4 | ratio tap changer | RatioTapChanger.setRegulationTerminal | without regulation | change
            r2-m3/r2-m4 | ratio tap changer without a CGMES control | RatioTapChanger.setRegulationTerminal | without regulation | change
            r2-m3/r2-m4 | VSC converter station 1 | VoltageRegulation.setTargetDeadband | with regulation | change
            r2-m3/r2-m4 | VSC converter station 1 | VoltageRegulation.setRegulating | with regulation | change
            r2-m3/r2-m4 | VSC converter station 1 | setLocalTargetV | without regulation | change
            r2-m3/r2-m4 | VSC converter station 1 | setLocalTargetQ | without regulation | change
            r2-m3/r2-m4 | VSC converter station 1 | VscConverterStation.setVoltageRegulatorOn | with regulation | change
            r2-m3/r2-m4 | VSC converter station 1 | VscConverterStation.setVoltageSetpoint | without regulation | change
            r2-m3/r2-m4 | VSC converter station 1 | VscConverterStation.setReactivePowerSetpoint | without regulation | change
            r2-m3/r2-m4 | VSC converter station 1 | VscConverterStation.setVoltageRegulatorOn(false) + setReactivePowerSetpoint | with regulation | change
            r2-m3/r2-m4 | VSC converter station 1 | VscConverterStation.setVoltageRegulatorOn(false) + setReactivePowerSetpoint | without regulation | change
            r2-m3/r2-m4 | VSC converter station 1 | VoltageRegulation.setRegulating(false) + setLocalTargetQ | with regulation | change
            r2-m3/r2-m4 | VSC converter station 1 | VscConverterStation.setRegulatingTerminal | with regulation | change
            r2-m3/r2-m4 | VSC converter station 1 | VscConverterStation.setRegulatingTerminal | with regulation | no-op
            r2-m3/r2-m4 | VSC converter station 1 | VscConverterStation.setRegulatingTerminal | without regulation | change
            r2-m3/r2-m4 | VSC converter station 1 | VscConverterStation.setRegulatingTerminal | without regulation | no-op
            r2-m3/r2-m4 | VSC converter station 2 | VoltageRegulation.setTargetDeadband | with regulation | change
            r2-m3/r2-m4 | VSC converter station 2 | VoltageRegulation.setRegulating | with regulation | change
            r2-m3/r2-m4 | VSC converter station 2 | setLocalTargetV | without regulation | change
            r2-m3/r2-m4 | VSC converter station 2 | setLocalTargetQ | without regulation | change
            r2-m3/r2-m4 | VSC converter station 2 | VscConverterStation.setVoltageRegulatorOn | with regulation | change
            r2-m3/r2-m4 | VSC converter station 2 | VscConverterStation.setVoltageSetpoint | without regulation | change
            r2-m3/r2-m4 | VSC converter station 2 | VscConverterStation.setReactivePowerSetpoint | without regulation | change
            r2-m3/r2-m4 | VSC converter station 2 | VscConverterStation.setVoltageRegulatorOn(false) + setReactivePowerSetpoint | with regulation | change
            r2-m3/r2-m4 | VSC converter station 2 | VscConverterStation.setVoltageRegulatorOn(false) + setReactivePowerSetpoint | without regulation | change
            r2-m3/r2-m4 | VSC converter station 2 | VoltageRegulation.setRegulating(false) + setLocalTargetQ | with regulation | change
            r2-m3/r2-m4 | VSC converter station 2 | VscConverterStation.setRegulatingTerminal | with regulation | change
            r2-m3/r2-m4 | VSC converter station 2 | VscConverterStation.setRegulatingTerminal | with regulation | no-op
            r2-m3/r2-m4 | VSC converter station 2 | VscConverterStation.setRegulatingTerminal | without regulation | change
            r2-m3/r2-m4 | VSC converter station 2 | VscConverterStation.setRegulatingTerminal | without regulation | no-op
            r2-m3/r2-m4 | VSC converter station 1 with its terminal set | VoltageRegulation.setTargetDeadband | with regulation | change
            r2-m3/r2-m4 | VSC converter station 1 with its terminal set | VoltageRegulation.setRegulating | with regulation | change
            r2-m3/r2-m4 | VSC converter station 1 with its terminal set | VoltageRegulation.setTerminal | with regulation | change
            r2-m3/r2-m4 | VSC converter station 1 with its terminal set | setLocalTargetV | without regulation | change
            r2-m3/r2-m4 | VSC converter station 1 with its terminal set | setLocalTargetQ | without regulation | change
            r2-m3/r2-m4 | VSC converter station 1 with its terminal set | VscConverterStation.setVoltageRegulatorOn | with regulation | change
            r2-m3/r2-m4 | VSC converter station 1 with its terminal set | VscConverterStation.setVoltageSetpoint | without regulation | change
            r2-m3/r2-m4 | VSC converter station 1 with its terminal set | VscConverterStation.setReactivePowerSetpoint | without regulation | change
            r2-m3/r2-m4 | VSC converter station 1 with its terminal set | VscConverterStation.setVoltageRegulatorOn(false) + setReactivePowerSetpoint | with regulation | change
            r2-m3/r2-m4 | VSC converter station 1 with its terminal set | VscConverterStation.setVoltageRegulatorOn(false) + setReactivePowerSetpoint | without regulation | change
            r2-m3/r2-m4 | VSC converter station 1 with its terminal set | VoltageRegulation.setRegulating(false) + setLocalTargetQ | with regulation | change
            r2-m3/r2-m4 | VSC converter station 1 with its terminal set | VscConverterStation.setRegulatingTerminal | with regulation | change
            r2-m3/r2-m4 | VSC converter station 1 with its terminal set | VscConverterStation.setRegulatingTerminal | without regulation | change
            r2-m3/r2-m4 | VSC converter station 1 with its terminal set | VscConverterStation.setRegulatingTerminal | without regulation | no-op
            r2-m3/r2-m4 | VSC converter station 2 with its terminal set | VoltageRegulation.setTargetDeadband | with regulation | change
            r2-m3/r2-m4 | VSC converter station 2 with its terminal set | VoltageRegulation.setRegulating | with regulation | change
            r2-m3/r2-m4 | VSC converter station 2 with its terminal set | VoltageRegulation.setTerminal | with regulation | change
            r2-m3/r2-m4 | VSC converter station 2 with its terminal set | setLocalTargetV | without regulation | change
            r2-m3/r2-m4 | VSC converter station 2 with its terminal set | setLocalTargetQ | without regulation | change
            r2-m3/r2-m4 | VSC converter station 2 with its terminal set | VscConverterStation.setVoltageRegulatorOn | with regulation | change
            r2-m3/r2-m4 | VSC converter station 2 with its terminal set | VscConverterStation.setVoltageSetpoint | without regulation | change
            r2-m3/r2-m4 | VSC converter station 2 with its terminal set | VscConverterStation.setReactivePowerSetpoint | without regulation | change
            r2-m3/r2-m4 | VSC converter station 2 with its terminal set | VscConverterStation.setVoltageRegulatorOn(false) + setReactivePowerSetpoint | with regulation | change
            r2-m3/r2-m4 | VSC converter station 2 with its terminal set | VscConverterStation.setVoltageRegulatorOn(false) + setReactivePowerSetpoint | without regulation | change
            r2-m3/r2-m4 | VSC converter station 2 with its terminal set | VoltageRegulation.setRegulating(false) + setLocalTargetQ | with regulation | change
            r2-m3/r2-m4 | VSC converter station 2 with its terminal set | VscConverterStation.setRegulatingTerminal | with regulation | change
            r2-m3/r2-m4 | VSC converter station 2 with its terminal set | VscConverterStation.setRegulatingTerminal | without regulation | change
            r2-m3/r2-m4 | VSC converter station 2 with its terminal set | VscConverterStation.setRegulatingTerminal | without regulation | no-op
            r2-m3/r2-m4 | detailed voltage source converter | VoltageRegulation.setTargetDeadband | with regulation | change
            r2-m3/r2-m4 | detailed voltage source converter | VoltageRegulation.setRegulating | with regulation | change
            r2-m3/r2-m4 | detailed voltage source converter | setLocalTargetV | without regulation | change
            r2-m3/r2-m4 | detailed voltage source converter | setLocalTargetQ | without regulation | change
            r2-m3/r2-m4 | detailed voltage source converter | VoltageSourceConverter.setVoltageRegulatorOn | with regulation | no-op
            r2-m3/r2-m4 | detailed voltage source converter | VoltageSourceConverter.setVoltageSetpoint | without regulation | change
            r2-m3/r2-m4 | detailed voltage source converter | VoltageSourceConverter.setReactivePowerSetpoint | without regulation | change
            """);

    private static Map<String, String> expectedToFail(String table) {
        Map<String, String> expected = new java.util.HashMap<>();
        table.lines().filter(line -> !line.isBlank()).forEach(line -> {
            int separator = line.indexOf(" | ");
            expected.put(line.substring(separator + 3).strip(), line.substring(0, separator).strip());
        });
        return expected;
    }

    private static final String GENERATOR_DIR = "/update/generator/";
    private static final String[] GENERATOR_FILES = {"generator_EQ.xml", "generator_SSH.xml"};
    private static final String SYNCHRONOUS_MACHINE = "SynchronousMachine";
    private static final String SHUNT_DIR = "/update/shunt-compensator/";
    private static final String HVDC_DIR = "/update/hvdc/";

    /**
     * A kind of regulation holder in a fixture.
     *
     * @param importedWithRegulation whether the CGMES import gives the holder a {@code VoltageRegulation}. When it
     *                               does, the matrix also runs without one, removed in memory on every copy
     */
    record Holder(String name, Properties importParams, String dir, String[] files, Consumer<Network> prepare,
                  Function<Network, VoltageRegulationHolder<?>> holder, Function<Network, Identifiable<?>> owner,
                  boolean importedWithRegulation) {

        Network load(boolean withRegulation) {
            Network network = readCgmesResources(importParams, dir, files);
            prepare.accept(network);
            VoltageRegulationHolder<?> regulationHolder = holder.apply(network);
            if (!withRegulation && regulationHolder.getVoltageRegulation() != null) {
                regulationHolder.removeVoltageRegulation();
            }
            return network;
        }
    }

    /** A setter, applied with a new value or with the value the holder already has. */
    @FunctionalInterface
    interface Call {
        void apply(Network network, VoltageRegulationHolder<?> holder, boolean change);
    }

    record Setter(String name, boolean needsRegulation, Call call) {
    }

    record Case(Holder holder, Setter setter, boolean withRegulation, boolean change) {
        String name() {
            return holder.name() + " | " + setter.name() + " | " + (withRegulation ? "with" : "without")
                    + " regulation | " + (change ? "change" : "no-op");
        }

        @Override
        public String toString() {
            return name();
        }
    }

    private static Properties noParameters() {
        return new Properties();
    }

    private static Properties detailedDcModel() {
        Properties parameters = new Properties();
        parameters.put(CgmesImport.USE_DETAILED_DC_MODEL, "true");
        return parameters;
    }

    private static VscConverterStation vsc(Network network, int side) {
        HvdcLine line = network.getHvdcLine("DCLineSegment-Vsc");
        return (VscConverterStation) (side == 1 ? line.getConverterStation1() : line.getConverterStation2());
    }

    private static RatioTapChanger ratioTapChanger(Network network) {
        return network.getThreeWindingsTransformer("T3W").getLeg2().getRatioTapChanger();
    }

    static List<Holder> holders() {
        Consumer<Network> nothing = network -> { };
        String[] shuntFiles = {"shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml"};
        String[] svcFiles = {"staticVarCompensator_EQ.xml", "staticVarCompensator_SSH.xml"};
        String[] transformerFiles = {"transformer_EQ.xml", "transformer_SSH.xml"};
        String[] hvdcFiles = {"hvdc_EQ.xml", "hvdc_SSH.xml"};
        return List.of(
                new Holder("generator regulating locally", noParameters(), GENERATOR_DIR, GENERATOR_FILES, nothing,
                        n -> n.getGenerator(SYNCHRONOUS_MACHINE), n -> n.getGenerator(SYNCHRONOUS_MACHINE), true),
                new Holder("generator regulating remotely", noParameters(), GENERATOR_DIR, GENERATOR_FILES,
                        // A remote target other than the local one (405), so that the two cannot be mistaken
                        n -> n.getGenerator(SYNCHRONOUS_MACHINE).getVoltageRegulation()
                                .setTerminal(n.getGenerator("ExternalNetworkInjection").getTerminal(), 410.0),
                        n -> n.getGenerator(SYNCHRONOUS_MACHINE), n -> n.getGenerator(SYNCHRONOUS_MACHINE), true),
                new Holder("generator without a CGMES regulating control", noParameters(), GENERATOR_DIR,
                        GENERATOR_FILES, nothing, n -> n.getGenerator("ExternalNetworkInjection"),
                        n -> n.getGenerator("ExternalNetworkInjection"), false),
                new Holder("shunt compensator", noParameters(), SHUNT_DIR, shuntFiles, nothing,
                        n -> n.getShuntCompensator("LinearShuntCompensator"),
                        n -> n.getShuntCompensator("LinearShuntCompensator"), true),
                new Holder("equivalent shunt", noParameters(), SHUNT_DIR, shuntFiles, nothing,
                        n -> n.getShuntCompensator("EquivalentShunt"), n -> n.getShuntCompensator("EquivalentShunt"),
                        false),
                new Holder("static var compensator", noParameters(), "/update/static-var-compensator/", svcFiles,
                        nothing, n -> n.getStaticVarCompensator("StaticVarCompensator-V"),
                        n -> n.getStaticVarCompensator("StaticVarCompensator-V"), true),
                new Holder("static var compensator regulating reactive power", noParameters(),
                        "/update/static-var-compensator/", svcFiles, nothing,
                        n -> n.getStaticVarCompensator("StaticVarCompensator-Q"),
                        n -> n.getStaticVarCompensator("StaticVarCompensator-Q"), true),
                new Holder("ratio tap changer", noParameters(), "/update/transformer/", transformerFiles, nothing,
                        RegulationSetterMatrixTest::ratioTapChanger, n -> n.getThreeWindingsTransformer("T3W"), true),
                new Holder("ratio tap changer without a CGMES control", noParameters(), "/issues/voltageRegulation/",
                        transformerFiles, nothing, n -> n.getTwoWindingsTransformer("PT2_0").getRatioTapChanger(),
                        n -> n.getTwoWindingsTransformer("PT2_0"), false),
                new Holder("VSC converter station 1", noParameters(), HVDC_DIR, hvdcFiles, nothing, n -> vsc(n, 1),
                        n -> vsc(n, 1), true),
                new Holder("VSC converter station 2", noParameters(), HVDC_DIR, hvdcFiles, nothing, n -> vsc(n, 2),
                        n -> vsc(n, 2), true),
                // Reactive power regulation needs a regulating terminal in IIDM: these stations regulate their own
                // terminal explicitly, which is what the CGMES import sets for reactive power regulation
                new Holder("VSC converter station 1 with its terminal set", noParameters(), HVDC_DIR, hvdcFiles,
                        n -> RecordedChangeScenarios.regulateOwnTerminal(vsc(n, 1)), n -> vsc(n, 1), n -> vsc(n, 1),
                        true),
                new Holder("VSC converter station 2 with its terminal set", noParameters(), HVDC_DIR, hvdcFiles,
                        n -> RecordedChangeScenarios.regulateOwnTerminal(vsc(n, 2)), n -> vsc(n, 2), n -> vsc(n, 2),
                        true),
                new Holder("detailed voltage source converter", detailedDcModel(), "/issues/hvdc/",
                        new String[] {"mixed_bipole_EQ.xml", "mixed_bipole_SSH.xml"}, nothing,
                        n -> n.getVoltageSourceConverter("VSC_1_2"), n -> n.getVoltageSourceConverter("VSC_1_2"),
                        true));
    }

    private static double changed(double value, boolean change, double whenUndefined) {
        if (!change) {
            return value;
        }
        return Double.isNaN(value) ? whenUndefined : value + 1.0;
    }

    private static RegulationMode otherMode(RegulationMode mode) {
        return mode == RegulationMode.VOLTAGE ? RegulationMode.REACTIVE_POWER : RegulationMode.VOLTAGE;
    }

    /** A terminal of another equipment than the holder, to regulate. */
    private static Terminal anotherTerminal(Network network, Identifiable<?> owner) {
        return network.getConnectableStream()
                .filter(c -> !c.getId().equals(owner.getId()) && !(c instanceof BusbarSection)
                        && !(c instanceof AcDcConverter<?>))
                .map(c -> (Terminal) ((Connectable<?>) c).getTerminals().get(0))
                .findFirst().orElseThrow();
    }

    /** The setters of the VoltageRegulation API and of the local targets, which every holder has. */
    private static List<Setter> newApi(Holder holder) {
        List<Setter> setters = new ArrayList<>();
        setters.add(new Setter("VoltageRegulation.setTargetValue", true, (n, h, change) -> {
            VoltageRegulation regulation = h.getVoltageRegulation();
            regulation.setTargetValue(changed(regulation.getTargetValue(), change, 400.0));
        }));
        setters.add(new Setter("VoltageRegulation.setTargetDeadband", true, (n, h, change) -> {
            VoltageRegulation regulation = h.getVoltageRegulation();
            regulation.setTargetDeadband(changed(regulation.getTargetDeadband(), change, 1.0));
        }));
        setters.add(new Setter("VoltageRegulation.setRegulating", true, (n, h, change) -> {
            VoltageRegulation regulation = h.getVoltageRegulation();
            regulation.setRegulating(change != regulation.isRegulating());
        }));
        setters.add(new Setter("VoltageRegulation.setMode", true, (n, h, change) -> {
            VoltageRegulation regulation = h.getVoltageRegulation();
            regulation.setMode(change ? otherMode(regulation.getMode()) : regulation.getMode());
        }));
        setters.add(new Setter("VoltageRegulation.setTerminal", true, (n, h, change) -> {
            VoltageRegulation regulation = h.getVoltageRegulation();
            Terminal terminal = change ? anotherTerminal(n, holder.owner().apply(n)) : regulation.getTerminal();
            regulation.setTerminal(terminal, regulation.getTargetValue());
        }));
        setters.add(new Setter("setLocalTargetV", false,
            (n, h, change) -> h.setLocalTargetV(changed(h.getLocalTargetV(), change, 400.0))));
        setters.add(new Setter("setLocalTargetQ", false,
            (n, h, change) -> h.setLocalTargetQ(changed(h.getLocalTargetQ(), change, 10.0))));
        return setters;
    }

    /** The deprecated bridges of each kind of holder, as upstream still has them. */
    @SuppressWarnings("removal")
    private static List<Setter> bridges(Holder holder) {
        List<Setter> setters = new ArrayList<>();
        switch (holder.holder().apply(holder.load(true))) {
            case Generator ignored -> {
                setters.add(new Setter("Generator.setVoltageRegulatorOn", false, (n, h, change) -> {
                    Generator g = (Generator) h;
                    g.setVoltageRegulatorOn(change != g.isVoltageRegulatorOn());
                }));
                setters.add(new Setter("Generator.setTargetV", false, (n, h, change) -> {
                    Generator g = (Generator) h;
                    g.setTargetV(changed(g.getTargetV(), change, 400.0));
                }));
                setters.add(new Setter("Generator.setTargetV(v, local)", false, (n, h, change) -> {
                    Generator g = (Generator) h;
                    // Two different new values, so that the remote and the local target cannot be mistaken
                    g.setTargetV(changed(g.getTargetV(), change, 400.0), changed(g.getLocalTargetV() + 1, change, 401.0)
                            - (change ? 0 : 1));
                }));
                // The remote target changes, the local one is set to what it already is (review 21 round 2, R2-M1)
                setters.add(new Setter("Generator.setTargetV(v, same local)", false, (n, h, change) -> {
                    Generator g = (Generator) h;
                    g.setTargetV(changed(g.getTargetV(), change, 400.0), g.getLocalTargetV());
                }));
                setters.add(new Setter("Generator.setTargetQ", false, (n, h, change) -> {
                    Generator g = (Generator) h;
                    g.setTargetQ(changed(g.getTargetQ(), change, 10.0));
                }));
                setters.add(new Setter("Generator.setRegulatingTerminal", false, (n, h, change) -> {
                    Generator g = (Generator) h;
                    g.setRegulatingTerminal(change ? anotherTerminal(n, g) : g.getRegulatingTerminal());
                }));
            }
            case ShuntCompensator ignored -> {
                setters.add(new Setter("ShuntCompensator.setVoltageRegulatorOn", false, (n, h, change) -> {
                    ShuntCompensator s = (ShuntCompensator) h;
                    s.setVoltageRegulatorOn(change != s.isVoltageRegulatorOn());
                }));
                setters.add(new Setter("ShuntCompensator.setTargetV", false, (n, h, change) -> {
                    ShuntCompensator s = (ShuntCompensator) h;
                    s.setTargetV(changed(s.getTargetV(), change, 400.0));
                }));
                setters.add(new Setter("ShuntCompensator.setTargetDeadband", false, (n, h, change) -> {
                    ShuntCompensator s = (ShuntCompensator) h;
                    s.setTargetDeadband(changed(s.getTargetDeadband(), change, 1.0));
                }));
                setters.add(new Setter("ShuntCompensator.setRegulatingTerminal", false, (n, h, change) -> {
                    ShuntCompensator s = (ShuntCompensator) h;
                    s.setRegulatingTerminal(change ? anotherTerminal(n, s) : s.getRegulatingTerminal());
                }));
            }
            case StaticVarCompensator ignored -> {
                setters.add(new Setter("StaticVarCompensator.setVoltageSetpoint", false, (n, h, change) -> {
                    StaticVarCompensator s = (StaticVarCompensator) h;
                    s.setVoltageSetpoint(changed(s.getVoltageSetpoint(), change, 400.0));
                }));
                setters.add(new Setter("StaticVarCompensator.setReactivePowerSetpoint", false, (n, h, change) -> {
                    StaticVarCompensator s = (StaticVarCompensator) h;
                    s.setReactivePowerSetpoint(changed(s.getReactivePowerSetpoint(), change, 10.0));
                }));
                setters.add(new Setter("StaticVarCompensator.setRegulationMode", false, (n, h, change) -> {
                    StaticVarCompensator s = (StaticVarCompensator) h;
                    s.setRegulationMode(change ? otherMode(s.getRegulationMode()) : s.getRegulationMode());
                }));
                setters.add(new Setter("StaticVarCompensator.setRegulating", false, (n, h, change) -> {
                    StaticVarCompensator s = (StaticVarCompensator) h;
                    s.setRegulating(change != s.isRegulating());
                }));
                setters.add(new Setter("StaticVarCompensator.setRegulatingTerminal", false, (n, h, change) -> {
                    StaticVarCompensator s = (StaticVarCompensator) h;
                    s.setRegulatingTerminal(change ? anotherTerminal(n, s) : s.getRegulatingTerminal());
                }));
            }
            case RatioTapChanger ignored -> {
                setters.add(new Setter("RatioTapChanger.setRegulationMode", false, (n, h, change) -> {
                    RatioTapChanger r = (RatioTapChanger) h;
                    r.setRegulationMode(change ? otherMode(r.getRegulationMode()) : r.getRegulationMode());
                }));
                setters.add(new Setter("RatioTapChanger.setRegulationValue", false, (n, h, change) -> {
                    RatioTapChanger r = (RatioTapChanger) h;
                    r.setRegulationValue(changed(r.getRegulationValue(), change, 400.0));
                }));
                setters.add(new Setter("RatioTapChanger.setTargetV", false, (n, h, change) -> {
                    RatioTapChanger r = (RatioTapChanger) h;
                    r.setTargetV(changed(r.getTargetV(), change, 400.0));
                }));
                setters.add(new Setter("RatioTapChanger.setTargetDeadband", false, (n, h, change) -> {
                    RatioTapChanger r = (RatioTapChanger) h;
                    r.setTargetDeadband(changed(r.getTargetDeadband(), change, 1.0));
                }));
                setters.add(new Setter("RatioTapChanger.setRegulating", false, (n, h, change) -> {
                    RatioTapChanger r = (RatioTapChanger) h;
                    r.setRegulating(change != r.isRegulating());
                }));
                setters.add(new Setter("RatioTapChanger.setRegulationTerminal", false, (n, h, change) -> {
                    RatioTapChanger r = (RatioTapChanger) h;
                    Identifiable<?> owner = holder.owner().apply(n);
                    r.setRegulationTerminal(change ? anotherTerminal(n, owner) : r.getRegulationTerminal());
                }));
            }
            case VscConverterStation ignored -> {
                setters.add(new Setter("VscConverterStation.setVoltageRegulatorOn", false, (n, h, change) -> {
                    VscConverterStation s = (VscConverterStation) h;
                    s.setVoltageRegulatorOn(change != s.isVoltageRegulatorOn());
                }));
                setters.add(new Setter("VscConverterStation.setVoltageSetpoint", false, (n, h, change) -> {
                    VscConverterStation s = (VscConverterStation) h;
                    s.setVoltageSetpoint(changed(s.getVoltageSetpoint(), change, 400.0));
                }));
                setters.add(new Setter("VscConverterStation.setReactivePowerSetpoint", false, (n, h, change) -> {
                    VscConverterStation s = (VscConverterStation) h;
                    s.setReactivePowerSetpoint(changed(s.getReactivePowerSetpoint(), change, 10.0));
                }));
                // What pypowsybl calls to switch a station to reactive power (review 21 round 2, R2-M4)
                setters.add(new Setter("VscConverterStation.setVoltageRegulatorOn(false) + setReactivePowerSetpoint",
                        false, (n, h, change) -> {
                            VscConverterStation s = (VscConverterStation) h;
                            s.setVoltageRegulatorOn(!change && s.isVoltageRegulatorOn());
                            s.setReactivePowerSetpoint(changed(s.getReactivePowerSetpoint(), change, 30.0));
                        }));
                setters.add(new Setter("VoltageRegulation.setRegulating(false) + setLocalTargetQ", true,
                        (n, h, change) -> {
                            VscConverterStation s = (VscConverterStation) h;
                            s.getVoltageRegulation().setRegulating(!change && s.getVoltageRegulation().isRegulating());
                            s.setLocalTargetQ(changed(s.getLocalTargetQ(), change, 30.0));
                        }));
                setters.add(new Setter("VscConverterStation.setRegulatingTerminal", false, (n, h, change) -> {
                    VscConverterStation s = (VscConverterStation) h;
                    s.setRegulatingTerminal(change ? anotherTerminal(n, s) : s.getRegulatingTerminal());
                }));
            }
            case VoltageSourceConverter ignored -> {
                setters.add(new Setter("VoltageSourceConverter.setVoltageRegulatorOn", false, (n, h, change) -> {
                    VoltageSourceConverter c = (VoltageSourceConverter) h;
                    c.setVoltageRegulatorOn(change != c.isVoltageRegulatorOn());
                }));
                setters.add(new Setter("VoltageSourceConverter.setVoltageSetpoint", false, (n, h, change) -> {
                    VoltageSourceConverter c = (VoltageSourceConverter) h;
                    c.setVoltageSetpoint(changed(c.getVoltageSetpoint(), change, 400.0));
                }));
                setters.add(new Setter("VoltageSourceConverter.setReactivePowerSetpoint", false, (n, h, change) -> {
                    VoltageSourceConverter c = (VoltageSourceConverter) h;
                    c.setReactivePowerSetpoint(changed(c.getReactivePowerSetpoint(), change, 10.0));
                }));
            }
            default -> throw new IllegalStateException("no bridges listed for " + holder.name());
        }
        return setters;
    }

    static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        for (Holder holder : holders()) {
            List<Setter> setters = new ArrayList<>(newApi(holder));
            setters.addAll(bridges(holder));
            for (Setter setter : setters) {
                for (boolean withRegulation : new boolean[] {true, false}) {
                    // Without a VoltageRegulation there is nothing to call its setters on; and a remote regulation
                    // without a VoltageRegulation is the local case
                    boolean skip = withRegulation
                            ? !holder.importedWithRegulation()
                            : setter.needsRegulation() || holder.name().endsWith("remotely");
                    if (skip) {
                        continue;
                    }
                    for (boolean change : new boolean[] {true, false}) {
                        cases.add(new Case(holder, setter, withRegulation, change));
                    }
                }
            }
        }
        return cases;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void everyRegulationSetterIsExportedOrRefused(Case c) {
        String knownFailure = EXPECTED_TO_FAIL.get(c.name());
        if (knownFailure != null) {
            assertThrows(AssertionFailedError.class, () -> check(c),
                    () -> c.name() + " passes now: remove it from EXPECTED_TO_FAIL (" + knownFailure + ")");
        } else {
            check(c);
        }
    }

    private static void check(Case c) {
        Network sender = c.holder().load(c.withRegulation());
        SortedMap<String, String> original = SteadyStateFingerprint.of(sender);
        List<NetworkEvent> events;
        try {
            events = RecordedChangeScenarios.record(sender,
                n -> c.setter().call().apply(n, c.holder().holder().apply(n), c.change()));
        } catch (UnsupportedOperationException | PowsyblException e) {
            // IIDM itself refuses the setter on this holder: there is no change to export
            trace(c, "setter", "IIDM REFUSES " + e.getMessage());
            Assumptions.abort("IIDM refuses " + c.setter().name() + " here: " + e.getMessage());
            return;
        }
        SortedMap<String, String> changed = SteadyStateFingerprint.of(sender);
        trace(c, "events", events.toString());
        trace(c, "sender", SteadyStateFingerprint.diff(original, changed).entrySet().stream()
                .map(en -> en.getKey() + "=" + en.getValue()[0] + "->" + en.getValue()[1]).toList().toString());

        Properties parameters = new Properties();
        parameters.putAll(c.holder().importParams());
        parameters.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");
        Set<String> inactive = inactiveLocalTargets(c, sender);
        boolean nothingExported = false;

        // The partial steady state hypothesis. A file that describes nothing is not applied: an update runs the whole
        // CGMES update over the receiver whatever the file holds, which is not what an empty change asks for
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            List<NetworkEvent> exported = PartialSshExport.write(sender, events, bytes,
                    new PartialSshExport.ExportOptions()
                            .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL));
            Network receiver = c.holder().load(c.withRegulation());
            if (exported.isEmpty() && reportsNothing(c, original, changed)) {
                nothingExported = true;
            } else {
                if (!exported.isEmpty()) {
                    MemDataSource dataSource = new MemDataSource();
                    dataSource.putData("partial_SSH.xml", bytes.toByteArray());
                    receiver.update(dataSource, parameters);
                }
                assertSameState(c, "partial SSH", changed, SteadyStateFingerprint.of(receiver), inactive, events);
            }
        } catch (PowsyblException e) {
            assertRefusal(c, "partial SSH", e);
        }

        // The difference model, both granularities, applied and reverted
        for (CgmesDiffExport.DiffGranularity granularity : CgmesDiffExport.DiffGranularity.values()) {
            String route = "difference " + granularity;
            DifferenceModelSet parsed;
            try {
                parsed = exportAndParse(sender, events, granularity);
            } catch (PowsyblException e) {
                assertRefusal(c, route, e);
                continue;
            }
            if (parsed.models().isEmpty() && nothingExported) {
                continue;
            }
            Network receiver = c.holder().load(c.withRegulation());
            try {
                CgmesDiffImport.apply(receiver, parsed, parameters, ReportNode.NO_OP);
            } catch (CgmesDiffNotApplicableException e) {
                // An explicit refusal of the in-place route, with its reasons: not silent
                trace(c, route, "SLOW " + e.getMessage());
                assertTrue(!e.getMessage().isEmpty(), c.name());
                continue;
            }
            assertSameState(c, route, changed, SteadyStateFingerprint.of(receiver), inactive, events);
            CgmesDiffImport.revert(receiver, parsed, parameters, ReportNode.NO_OP);
            assertSameState(c, route + " reverted", original, SteadyStateFingerprint.of(receiver), inactive, events);
        }
    }

    /**
     * Whether the sender changed although the change set reports nothing an export can see: the deprecated setter
     * created the {@code VoltageRegulation}, which IIDM reports nowhere (gap G1, issue draft
     * {@code voltage-regulation-creation-fires-no-event.md}), and every event it did report is dropped by the
     * compaction as a no-op. It is recognised narrowly: the sender differs from the original by a
     * {@code VoltageRegulation} it did not have, and the export wrote nothing.
     */
    private static boolean reportsNothing(Case c, SortedMap<String, String> original,
                                          SortedMap<String, String> changed) {
        boolean g1 = SteadyStateFingerprint.diff(original, changed).entrySet().stream()
                .anyMatch(entry -> entry.getKey().endsWith("voltageRegulation") && "none".equals(entry.getValue()[0]));
        trace(c, "G1", String.valueOf(g1));
        return g1;
    }

    /**
     * The local voltage target of the holder when its regulation does not use it at the end of the change set, with
     * the regulating voltage target derived from it: the regulation has a regulating terminal (it then regulates to the
     * target of the regulation) or another mode than voltage. The steady state hypothesis has no property for such a
     * value (the full export writes none either), so a receiver keeps what its equipment model gave it, exactly as for
     * {@code CgmesDiffRoundTripTest#KNOWN_IMPORT_NORMALISATIONS}.
     */
    private static Set<String> inactiveLocalTargets(Case c, Network sender) {
        VoltageRegulationHolder<?> holder = c.holder().holder().apply(sender);
        VoltageRegulation regulation = holder.getVoltageRegulation();
        if (regulation == null || !(holder instanceof Identifiable<?> owner)) {
            return Set.of();
        }
        if (regulation.getMode() != RegulationMode.VOLTAGE) {
            return Set.of(owner.getId() + ".localTargetV", owner.getId() + ".regulatingTargetV");
        }
        return regulation.isWithTerminal() ? Set.of(owner.getId() + ".localTargetV") : Set.of();
    }

    private static DifferenceModelSet exportAndParse(Network sender, List<NetworkEvent> events,
                                                     CgmesDiffExport.DiffGranularity granularity) {
        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(sender, events,
                new CgmesDiffExport.ExportOptions()
                        .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL)
                        .setGranularity(granularity));
        List<DifferenceModel> models = new ArrayList<>();
        for (DifferenceModel model : result.differences().models().values()) {
            models.add(DifferenceModelParser.parse(DifferenceModelWriter.toString(model)));
        }
        return new DifferenceModelSet(models);
    }

    private static void assertSameState(Case c, String route, SortedMap<String, String> expected,
                                        SortedMap<String, String> actual, Set<String> inactive,
                                        List<NetworkEvent> events) {
        Map<String, String[]> differences = SteadyStateFingerprint.diff(expected, actual);
        differences.keySet().removeIf(inactive::contains);
        trace(c, route, differences.isEmpty() ? "OK" : "DIVERGES " + differences.entrySet().stream()
                .map(en -> en.getKey() + "=" + en.getValue()[0] + "->" + en.getValue()[1]).toList());
        if (!differences.isEmpty()) {
            Map<String, String> readable = new LinkedHashMap<>();
            differences.forEach((key, values) -> readable.put(key, values[0] + " -> " + values[1]));
            fail(c.name() + ": the " + route + " receiver is not where it should be: " + readable
                    + "; recorded " + events);
        }
    }

    private static void assertRefusal(Case c, String route, PowsyblException e) {
        String message = String.valueOf(e.getMessage());
        trace(c, route, "REFUSED " + message.replaceAll("Change: .*", ""));
        assertTrue(message.contains(REMEDY), () -> c.name() + ": the " + route
                + " refusal names no remedy: " + message);
        assertEquals(-1, message.indexOf(REMEDY + "."), () -> c.name() + ": empty remedy: " + message);
    }

    private static void trace(Case c, String what, String text) {
        if (Boolean.getBoolean("matrix.trace")) {
            System.out.println("MATRIX " + c.name() + " || " + what + " || " + text);
        }
    }
}
