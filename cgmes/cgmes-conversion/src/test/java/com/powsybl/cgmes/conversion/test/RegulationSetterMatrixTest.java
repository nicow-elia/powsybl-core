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
import com.powsybl.cgmes.conversion.export.Refusal;
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
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulation;
import com.powsybl.iidm.network.regulation.VoltageRegulationHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@link #inactiveLocalTargets}. The only change that may go unexported is one IIDM reports nowhere (gap G1, see
 * {@link #iidmSilent}). A partial file that describes nothing is not applied, since a CGMES update visits the whole
 * receiver whatever the file holds.</p>
 *
 * <p>The outcome of every case ({@link Outcome}) is committed in {@value #EXPECTED_OUTCOMES} and compared after the
 * run, so that a later change cannot turn an export into a refusal, or a refusal into a silent loss, unnoticed.
 * {@code -Dmatrix.regenerate=true} rewrites it from the observed outcomes, for a review of the diff.</p>
 *
 * <p>{@code -Dmatrix.trace=true} prints the recorded events and the outcome of every route of every case.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RegulationSetterMatrixTest {

    /** What a refusal of a regulation change says before its remedy. */
    static final String REMEDY = Refusal.REMEDY;

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
     * @param regulationOnly         whether the holder differs from another holder of the matrix in its regulation
     *                               only: without a {@code VoltageRegulation} it is that other holder, so the matrix
     *                               runs it with its regulation only
     */
    record Holder(String name, Properties importParams, String dir, String[] files, Consumer<Network> prepare,
                  Function<Network, VoltageRegulationHolder<?>> holder, Function<Network, Identifiable<?>> owner,
                  boolean importedWithRegulation, boolean regulationOnly) {

        Holder(String name, Properties importParams, String dir, String[] files, Consumer<Network> prepare,
               Function<Network, VoltageRegulationHolder<?>> holder, Function<Network, Identifiable<?>> owner,
               boolean importedWithRegulation) {
            this(name, importParams, dir, files, prepare, holder, owner, importedWithRegulation, false);
        }

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

    /**
     * Replace the regulation of the holder by one created while another variant is the working one: in the working
     * variant it then has no mode, is not regulating and has no target (U2). IIDM sets no regulating terminal while
     * there are several variants, so it regulates locally.
     */
    private static void regulationFromAnotherVariant(Network network, VoltageRegulationHolder<?> holder) {
        holder.removeVoltageRegulation();
        VariantManager variants = network.getVariantManager();
        String working = variants.getWorkingVariantId();
        variants.cloneVariant(working, "another variant");
        variants.setWorkingVariant("another variant");
        holder.newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(false).build();
        variants.setWorkingVariant(working);
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
                        n -> n.getGenerator(SYNCHRONOUS_MACHINE), n -> n.getGenerator(SYNCHRONOUS_MACHINE), true, true),
                // The import records the CGMES mode of the machine's control, voltage; the regulation is switched to
                // reactive power at the machine's own terminal, which the update would read as the other quantity
                new Holder("generator whose mode was changed after the import", noParameters(), GENERATOR_DIR,
                        GENERATOR_FILES, n -> {
                            Generator g = n.getGenerator(SYNCHRONOUS_MACHINE);
                            g.getVoltageRegulation().setTerminal(g.getTerminal(), g.getRegulatingTargetV());
                            g.getVoltageRegulation().setMode(RegulationMode.REACTIVE_POWER);
                            g.getVoltageRegulation().setTargetValue(10.0);
                        }, n -> n.getGenerator(SYNCHRONOUS_MACHINE), n -> n.getGenerator(SYNCHRONOUS_MACHINE), true,
                        true),
                new Holder("generator without a CGMES regulating control", noParameters(), GENERATOR_DIR,
                        GENERATOR_FILES, nothing, n -> n.getGenerator("ExternalNetworkInjection"),
                        n -> n.getGenerator("ExternalNetworkInjection"), false),
                new Holder("shunt compensator", noParameters(), SHUNT_DIR, shuntFiles, nothing,
                        n -> n.getShuntCompensator("LinearShuntCompensator"),
                        n -> n.getShuntCompensator("LinearShuntCompensator"), true),
                // The regulation was created while another variant was the working one: it has no mode in this one
                new Holder("shunt compensator whose regulation was created in another variant", noParameters(),
                        SHUNT_DIR, shuntFiles,
                        n -> regulationFromAnotherVariant(n, n.getShuntCompensator("LinearShuntCompensator")),
                        n -> n.getShuntCompensator("LinearShuntCompensator"),
                        n -> n.getShuntCompensator("LinearShuntCompensator"), true, true),
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
                // The CGMES TapChangerControl of this ratio tap changer regulates reactive power
                new Holder("ratio tap changer regulating reactive power", noParameters(), "/issues/voltageRegulation/",
                        transformerFiles, nothing, n -> n.getTwoWindingsTransformer("PT2_2").getRatioTapChanger(),
                        n -> n.getTwoWindingsTransformer("PT2_2"), true, true),
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
                new Holder("VSC converter station 2 regulating reactive power", noParameters(), HVDC_DIR, hvdcFiles,
                        n -> {
                            RecordedChangeScenarios.regulateOwnTerminal(vsc(n, 2));
                            vsc(n, 2).getVoltageRegulation().setMode(RegulationMode.REACTIVE_POWER);
                            vsc(n, 2).getVoltageRegulation().setTargetValue(30.0);
                        }, n -> vsc(n, 2), n -> vsc(n, 2), true),
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

    /**
     * The regulating terminal the regulation has, {@code null} when none: the no-op of a terminal setter (the
     * deprecated getRegulatingTerminal answers the holder's own terminal, and setting that is a change, c-m11).
     */
    private static Terminal currentTerminal(VoltageRegulationHolder<?> holder) {
        return holder.getVoltageRegulation() != null ? holder.getVoltageRegulation().getTerminal() : null;
    }

    /** A terminal of another equipment than the holder, to regulate. */
    private static Terminal anotherTerminal(Network network, Identifiable<?> owner, Terminal current) {
        return network.getConnectableStream()
                .filter(c -> !c.getId().equals(owner.getId()) && !(c instanceof BusbarSection)
                        && !(c instanceof AcDcConverter<?>))
                .map(c -> (Terminal) ((Connectable<?>) c).getTerminals().get(0))
                .filter(t -> t != current)
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
        setters.add(new Setter("VoltageRegulation.setSlope", true, (n, h, change) -> {
            VoltageRegulation regulation = h.getVoltageRegulation();
            regulation.setSlope(changed(regulation.getSlope(), change, 0.01));
        }));
        setters.add(new Setter("VoltageRegulation.setTerminal", true, (n, h, change) -> {
            VoltageRegulation regulation = h.getVoltageRegulation();
            Terminal terminal = change ? anotherTerminal(n, holder.owner().apply(n), regulation.getTerminal()) : regulation.getTerminal();
            // A defined target, so that the row tests the export and not the validation (review 21 closing, c-m10).
            // Without a terminal the target stays NaN, as IIDM requires: the no-op of a regulation without terminal
            double target = terminal == null || regulation.isWithTerminal() ? regulation.getTargetValue()
                    : regulation.getMode() == RegulationMode.REACTIVE_POWER ? h.getRegulatingTargetQ() : h.getRegulatingTargetV();
            regulation.setTerminal(terminal, target);
        }));
        // Creation and removal through the API: IIDM reports neither (gap G1, review 21 round 3, R3-M1). The way to
        // record a creation is to create the regulation not regulating and switch it on then, see export.md
        setters.add(new Setter("newVoltageRegulation (not regulating)", false, (n, h, change) -> {
            if (change) {
                h.newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(false).build();
            }
        }));
        setters.add(new Setter("newVoltageRegulation (regulating)", false, (n, h, change) -> {
            if (change) {
                h.newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(true).build();
            }
        }));
        setters.add(new Setter("removeVoltageRegulation", true, (n, h, change) -> {
            if (change) {
                h.removeVoltageRegulation();
            }
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
                // The no-op of a regulation without terminal is setRegulatingTerminal(null), which IIDM refuses: the bridge
                // passes the regulating target with the null terminal, not NaN (upstream; the same in the other bridges)
                setters.add(new Setter("Generator.setRegulatingTerminal", false, (n, h, change) -> {
                    Generator g = (Generator) h;
                    g.setRegulatingTerminal(change ? anotherTerminal(n, g, g.getRegulatingTerminal()) : currentTerminal(g));
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
                    s.setRegulatingTerminal(change ? anotherTerminal(n, s, s.getRegulatingTerminal()) : currentTerminal(s));
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
                    s.setRegulatingTerminal(change ? anotherTerminal(n, s, s.getRegulatingTerminal()) : currentTerminal(s));
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
                    r.setRegulationTerminal(change ? anotherTerminal(n, owner, r.getRegulationTerminal()) : r.getRegulationTerminal());
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
                // Switching a station between voltage and reactive power regulation the way the CGMES import represents
                // it: reactive power regulates the station's own terminal (review 21 round 3, R3-M4)
                setters.add(new Setter("switch to reactive power at the own terminal", true, (n, h, change) -> {
                    VscConverterStation s = (VscConverterStation) h;
                    VoltageRegulation regulation = s.getVoltageRegulation();
                    if (change) {
                        regulation.setTerminal(s.getTerminal(), s.getRegulatingTargetV());
                        regulation.setMode(RegulationMode.REACTIVE_POWER);
                        regulation.setTargetValue(s.getLocalTargetQ() + 1.0);
                    }
                }));
                setters.add(new Setter("switch to voltage", true, (n, h, change) -> {
                    VscConverterStation s = (VscConverterStation) h;
                    VoltageRegulation regulation = s.getVoltageRegulation();
                    if (change) {
                        regulation.setMode(RegulationMode.VOLTAGE);
                        regulation.setTargetValue(Double.isNaN(s.getLocalTargetV()) ? 400.0 : s.getLocalTargetV() + 1.0);
                    }
                }));
                setters.add(new Setter("VscConverterStation.setRegulatingTerminal", false, (n, h, change) -> {
                    VscConverterStation s = (VscConverterStation) h;
                    s.setRegulatingTerminal(change ? anotherTerminal(n, s, s.getRegulatingTerminal()) : currentTerminal(s));
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
            VoltageRegulationHolder<?> loaded = holder.holder().apply(holder.load(true));
            boolean withTerminal = loaded.getVoltageRegulation() != null && loaded.getVoltageRegulation().isWithTerminal();
            for (Setter setter : setters) {
                // Rows that cannot carry a change (review 21 round 3, r3-m9): a ratio tap changer has no local targets,
                // and the target value of a regulation without terminal is not read (refused by IIDM, or ignored)
                RegulationMode mode = loaded.getVoltageRegulation() != null ? loaded.getVoltageRegulation().getMode() : null;
                if (loaded instanceof RatioTapChanger && setter.name().startsWith("setLocalTarget")
                        || !withTerminal && setter.name().equals("VoltageRegulation.setTargetValue")
                        || setter.name().startsWith("switch to reactive") && mode != RegulationMode.VOLTAGE
                        || setter.name().equals("switch to voltage") && mode != RegulationMode.REACTIVE_POWER) {
                    continue;
                }
                for (boolean withRegulation : new boolean[] {true, false}) {
                    // Without a VoltageRegulation there is nothing to call its setters on; and a holder that differs
                    // from another in its regulation only is that other one without a VoltageRegulation
                    // The builder is a creation only on a holder without regulation
                    boolean skip = withRegulation
                            ? !holder.importedWithRegulation() || setter.name().startsWith("newVoltageRegulation")
                            : setter.needsRegulation() || holder.regulationOnly();
                    if (skip) {
                        continue;
                    }
                    for (boolean change : new boolean[] {true, false}) {
                        // setVoltageRegulatorOn(false) of a converter regulating reactive power switches it to voltage:
                        // the bridge has no value that keeps its state, so it has no no-op (c-m11)
                        if (!change && withRegulation && setter.name().contains("setVoltageRegulatorOn")
                                && mode == RegulationMode.REACTIVE_POWER) {
                            continue;
                        }
                        // ShuntCompensator.setVoltageRegulatorOn sets the mode VOLTAGE: on a regulation without a mode in
                        // this variant it has no value that keeps its state, so it has no no-op either
                        if (!change && withRegulation && setter.name().equals("ShuntCompensator.setVoltageRegulatorOn")
                                && mode == null) {
                            continue;
                        }
                        // Generator.setVoltageRegulatorOn reads the flag in voltage mode only and writes the regulating
                        // flag: on a generator regulating reactive power its change sets the flag it already has
                        if (change && withRegulation && setter.name().equals("Generator.setVoltageRegulatorOn")
                                && mode == RegulationMode.REACTIVE_POWER) {
                            continue;
                        }
                        cases.add(new Case(holder, setter, withRegulation, change));
                    }
                }
            }
        }
        return cases;
    }

    /** The outcome of a case, committed per case in {@value #EXPECTED_OUTCOMES}. */
    enum Outcome {
        /** The sender changed, every route takes a receiver to its state (and back to the original on revert). */
        EXPORTED,
        /** Every route refuses the change, with a cause and a remedy. */
        REFUSED,
        /** The sender changed only in values the SSH does not represent (see {@link #inactiveLocalTargets}). */
        NOT_REPRESENTED,
        /**
         * The sender changed, but IIDM reported no event with a new value (gap G1: a regulation created or removed):
         * no export can see it, and none writes anything.
         */
        IIDM_SILENT,
        /** IIDM refuses the setter itself: there is no change. */
        IIDM_REFUSES,
        /** The setter sets the values the holder has: the sender does not change (only for the no-op cases). */
        UNCHANGED
    }

    /**
     * The committed expected outcome of every case, with the documented rule it follows: a generated resource,
     * reviewed row by row, and checked both ways (every case has a row, every row a case).
     */
    static final String EXPECTED_OUTCOMES = "/regulation-setter-matrix/expected-outcomes.tsv";

    /**
     * The documented rules a row may name. {@code steady-state}: the change is an SSH property and travels;
     * {@code echo-1}, {@code echo-2}, {@code echo-3}: the three rules of {@code EventCompactor} for the echoes of the
     * deprecated setters; {@code local-target}: the local voltage target (R3-M3); {@code own-terminal}: a converter
     * station regulating its own terminal or none (R3-M4); {@code G1}: IIDM reports nothing; {@code iidm-validation}:
     * IIDM refuses the setter; {@code no-change}: nothing changed. The others, {@code echo-3}, {@code local-target} and
     * {@code own-terminal} included, are the rules of the refusals ({@link Refusal#rule}), which a refused row reads
     * from the message ({@link Refusal#of}).
     */
    static final Set<String> RULES = Stream.concat(Stream.of("steady-state", "echo-1", "echo-2", "G1",
            "iidm-validation", "no-change"), Arrays.stream(Refusal.values()).map(r -> r.rule))
            .collect(Collectors.toUnmodifiableSet());

    /** The outcome of a case and the rule it follows. */
    record Observed(Outcome outcome, String rule) {
        @Override
        public String toString() {
            return outcome + "\t" + rule;
        }
    }

    private static final Map<String, Observed> OBSERVED = new ConcurrentHashMap<>();

    private static Map<String, Observed> expectedOutcomes() {
        Map<String, Observed> expected = new LinkedHashMap<>();
        InputStream stream = RegulationSetterMatrixTest.class.getResourceAsStream(EXPECTED_OUTCOMES);
        if (stream == null) {
            return expected;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).forEach(line -> {
                String[] columns = line.split("\t");
                expected.put(columns[0], new Observed(Outcome.valueOf(columns[1]), columns[2]));
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return expected;
    }

    private static final Map<String, Observed> EXPECTED = expectedOutcomes();

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void everyRegulationSetterIsExportedOrRefused(Case c) {
        Observed observed = check(c);
        OBSERVED.put(c.name(), observed);
        trace(c, "OUTCOME", observed.toString());
        assertTrue(RULES.contains(observed.rule()), () -> c.name() + ": no documented rule: " + observed.rule());
        if (!Boolean.getBoolean("matrix.regenerate")) {
            assertEquals(EXPECTED.get(c.name()), observed, () -> c.name() + ": not the committed outcome ("
                    + EXPECTED_OUTCOMES + ", regenerate with -Dmatrix.regenerate=true and review the diff)");
        }
    }

    /** Every row of the committed file names a case of the matrix: a removed setter leaves no row behind. */
    @Test
    void everyCommittedRowIsACase() {
        Set<String> names = new HashSet<>();
        cases().forEach(c -> names.add(c.name()));
        assertEquals(Set.of(), EXPECTED.keySet().stream().filter(name -> !names.contains(name))
                .collect(Collectors.toSet()), "rows without a case");
    }

    /** With {@code -Dmatrix.regenerate=true}, write the observed outcomes as the new expected ones. */
    @AfterAll
    static void regenerate() throws IOException {
        if (!Boolean.getBoolean("matrix.regenerate")) {
            return;
        }
        StringBuilder table = new StringBuilder("# case\texpected outcome (RegulationSetterMatrixTest.Outcome)\trule"
                + " (RegulationSetterMatrixTest.RULES)\n");
        for (Case c : cases()) {
            // A case that failed has no outcome and is left out: the next run then fails on it
            if (OBSERVED.containsKey(c.name())) {
                table.append(c.name()).append('\t').append(OBSERVED.get(c.name())).append('\n');
            }
        }
        Path file = Path.of("src/test/resources" + EXPECTED_OUTCOMES);
        Files.createDirectories(file.getParent());
        Files.writeString(file, table.toString());
    }

    /** The names under which the deprecated setters report their echoes. */
    private static final Pattern ECHO = Pattern.compile(
            "voltageRegulatorOn|regulating|regulationMode|targetDeadband|regulatingTerminal|targetV|voltageSetpoint"
                    + "|reactivePowerSetpoint|ratioTapChanger[123]?\\.(regulating|regulationMode|targetDeadband"
                    + "|regulationTerminal|regulationValue)");

    private static boolean hasEcho(List<NetworkEvent> events) {
        return events.stream().anyMatch(e -> e instanceof UpdateNetworkEvent u && ECHO.matcher(u.attribute()).matches());
    }

    private static String rule(Outcome outcome, String refusal, List<NetworkEvent> events, boolean terminalChanged) {
        return switch (outcome) {
            case REFUSED -> Refusal.of(refusal).map(r -> r.rule).orElse("unknown refusal: " + refusal);
            case EXPORTED -> terminalChanged ? "own-terminal" : hasEcho(events) ? "echo-1" : "steady-state";
            case NOT_REPRESENTED -> "local-target";
            case IIDM_SILENT -> "G1";
            case IIDM_REFUSES -> "iidm-validation";
            case UNCHANGED -> hasEcho(events) ? "echo-2" : "no-change";
        };
    }

    private static Observed check(Case c) {
        Network sender = c.holder().load(c.withRegulation());
        SortedMap<String, String> original = SteadyStateFingerprint.of(sender);
        String terminalBefore = regulatingTerminalOf(c, sender);
        String slopeBefore = slopeOf(c, sender);
        // What the regulation does not use in the original state, for the comparison after a revert
        Set<String> inactiveBefore = inactiveLocalTargets(c, sender);
        List<NetworkEvent> events;
        try {
            events = RecordedChangeScenarios.record(sender,
                n -> c.setter().call().apply(n, c.holder().holder().apply(n), c.change()));
        } catch (UnsupportedOperationException | PowsyblException e) {
            // IIDM itself refuses the setter on this holder: there is no change to export
            trace(c, "setter", "IIDM REFUSES " + e.getMessage());
            return new Observed(Outcome.IIDM_REFUSES, "iidm-validation");
        }
        SortedMap<String, String> changed = SteadyStateFingerprint.of(sender);
        Map<String, String[]> senderChange = SteadyStateFingerprint.diff(original, changed);
        trace(c, "events", events.toString());
        trace(c, "sender", senderChange.entrySet().stream()
                .map(en -> en.getKey() + "=" + en.getValue()[0] + "->" + en.getValue()[1]).toList().toString());
        // A change case that changes nothing tests nothing (review 21 round 3, r3-m9). The regulating terminal is not in
        // the fingerprint (the import normalises it), so it is compared here, on the sender only
        boolean terminalChanged = !Objects.equals(terminalBefore, regulatingTerminalOf(c, sender));
        // Nor is the slope of a regulation, which no steady state hypothesis holds: a change of it alone has to be
        // refused. A regulation created or removed is compared as such, not by its slope
        String slopeAfter = slopeOf(c, sender);
        boolean slopeChanged = slopeBefore != null && slopeAfter != null && !slopeBefore.equals(slopeAfter);
        assertTrue(!c.change() || !senderChange.isEmpty() || terminalChanged || slopeChanged,
                () -> c.name() + ": the change changes nothing");

        Properties parameters = new Properties();
        parameters.putAll(c.holder().importParams());
        parameters.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");
        Set<String> inactive = inactiveLocalTargets(c, sender);
        int refusals = 0;
        String refusal = "";
        boolean written = false;

        // The partial steady state hypothesis. A file that describes nothing is not applied: an update runs the whole
        // CGMES update over the receiver whatever the file holds, which is not what an empty change asks for
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            List<NetworkEvent> exported = PartialSshExport.write(sender, events, bytes,
                    new PartialSshExport.ExportOptions()
                            .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL));
            written = !exported.isEmpty();
            Network receiver = c.holder().load(c.withRegulation());
            if (written) {
                MemDataSource dataSource = new MemDataSource();
                dataSource.putData("partial_SSH.xml", bytes.toByteArray());
                receiver.update(dataSource, parameters);
            }
            if (written || !iidmSilent(c, sender, events, original, changed)) {
                assertSameState(c, "partial SSH", changed, SteadyStateFingerprint.of(receiver), inactive, events);
            }
        } catch (PowsyblException e) {
            assertRefusal(c, "partial SSH", e);
            refusal = String.valueOf(e.getMessage());
            refusals++;
        }

        // The difference model, both granularities, applied and reverted
        for (CgmesDiffExport.DiffGranularity granularity : CgmesDiffExport.DiffGranularity.values()) {
            String route = "difference " + granularity;
            DifferenceModelSet parsed;
            try {
                parsed = exportAndParse(sender, events, granularity);
            } catch (PowsyblException e) {
                assertRefusal(c, route, e);
                refusals++;
                continue;
            }
            written |= !parsed.models().isEmpty();
            if (parsed.models().isEmpty() && iidmSilent(c, sender, events, original, changed)) {
                continue;
            }
            Network receiver = c.holder().load(c.withRegulation());
            try {
                CgmesDiffImport.apply(receiver, parsed, parameters, ReportNode.NO_OP);
            } catch (CgmesDiffNotApplicableException e) {
                fail(c.name() + ": the in-place route refused " + route + ": " + e.getMessage());
            }
            assertSameState(c, route, changed, SteadyStateFingerprint.of(receiver), inactive, events);
            CgmesDiffImport.revert(receiver, parsed, parameters, ReportNode.NO_OP);
            assertSameState(c, route + " reverted", original, SteadyStateFingerprint.of(receiver), inactiveBefore, events);
        }

        Outcome outcome = outcome(c, refusals, senderChange, terminalChanged, written, sender, events, original,
                changed, inactive);
        assertTrue(!slopeChanged || outcome == Outcome.REFUSED, () -> c.name() + ": a slope change that is not refused");
        // A no-op changes nothing, except where a deprecated setter creates the regulation, which IIDM does not report
        // (G1): the case is then silent, or refused when the bridge reports a wrong old value (echo rule 3)
        boolean createdByABridge = senderChange.entrySet().stream()
                .anyMatch(entry -> entry.getKey().endsWith("voltageRegulation") && "none".equals(entry.getValue()[0]));
        assertTrue(c.change() || senderChange.isEmpty() && !terminalChanged && !slopeChanged || createdByABridge,
                () -> c.name() + ": a no-op that changes the sender");
        return new Observed(outcome, rule(outcome, refusal, events, terminalChanged));
    }

    private static Outcome outcome(Case c, int refusals, Map<String, String[]> senderChange, boolean terminalChanged,
                                   boolean written, Network sender, List<NetworkEvent> events,
                                   SortedMap<String, String> original, SortedMap<String, String> changed,
                                   Set<String> inactive) {
        if (refusals > 0) {
            assertEquals(3, refusals, () -> c.name() + ": refused by some routes only");
            return Outcome.REFUSED;
        }
        if (senderChange.isEmpty()) {
            // Only the regulating terminal changed, which the fingerprint does not hold: a station switching between
            // no terminal and its own one travels as qPccControl (R3-M4), with a receiver in an equivalent state
            return !terminalChanged ? Outcome.UNCHANGED : written ? Outcome.EXPORTED : Outcome.NOT_REPRESENTED;
        }
        if (!written && iidmSilent(c, sender, events, original, changed)) {
            return Outcome.IIDM_SILENT;
        }
        if (inactive.containsAll(senderChange.keySet())) {
            // Not represented: neither written nor listed as exported, by any route
            assertTrue(!written, () -> c.name() + ": a change the SSH does not represent was written");
            return Outcome.NOT_REPRESENTED;
        }
        return Outcome.EXPORTED;
    }

    /** The regulating terminal of the regulation of the case, as its connectable and side, or {@code none}. */
    private static String regulatingTerminalOf(Case c, Network network) {
        VoltageRegulation regulation = c.holder().holder().apply(network).getVoltageRegulation();
        Terminal terminal = regulation == null ? null : regulation.getTerminal();
        return terminal == null ? "none" : terminal.getConnectable().getId() + "/"
                + terminal.getConnectable().getTerminals().indexOf(terminal);
    }

    /** The slope of the regulation of the case, {@code null} when it has none. */
    private static String slopeOf(Case c, Network network) {
        VoltageRegulation regulation = c.holder().holder().apply(network).getVoltageRegulation();
        return regulation == null ? null : String.valueOf(regulation.getSlope());
    }

    /**
     * Whether the sender changed although IIDM reported nothing an export can see (gap G1, issue draft
     * {@code voltage-regulation-creation-fires-no-event.md}): a {@code VoltageRegulation} was created or removed, and
     * no recorded event has an old value different from its new one. When a deprecated setter created the regulation
     * and reported a no-op, the regulation it created must not regulate either: a holder that starts regulating has
     * changed in a way the log would have to show (review 21 round 3, R3-M2).
     */
    private static boolean iidmSilent(Case c, Network sender, List<NetworkEvent> events,
                                      SortedMap<String, String> original, SortedMap<String, String> changed) {
        boolean creationOrRemoval = SteadyStateFingerprint.diff(original, changed).entrySet().stream()
                .anyMatch(entry -> entry.getKey().endsWith("voltageRegulation")
                        && ("none".equals(entry.getValue()[0]) || "none".equals(entry.getValue()[1])));
        boolean nothingReported = events.stream().allMatch(event -> event instanceof UpdateNetworkEvent update
                && Objects.equals(update.oldValue(), update.newValue()));
        VoltageRegulation now = c.holder().holder().apply(sender).getVoltageRegulation();
        boolean startsRegulatingUnderANoOp = !events.isEmpty() && now != null && now.isRegulating();
        boolean silent = creationOrRemoval && nothingReported && !startsRegulatingUnderANoOp;
        trace(c, "G1", String.valueOf(silent));
        return silent;
    }

    /**
     * The local voltage target of the holder, with the regulating voltage target derived from it, when its regulation is
     * not in a voltage mode (only the local target when it regulates the holder's own terminal), in the state compared (the end of the change set after an apply, the original state after
     * a revert). The steady state hypothesis does not represent it and nothing reads it, so a change of it alone is not
     * a change of the SSH and is not exported (review 21 round 3, R3-M3; the local target of a voltage regulation at a
     * terminal, which a load flow falls back to, is refused instead).
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
        // A regulation of the holder's own terminal regulates to its target value: the local target is not read
        return regulation.getTerminal() == holder.getTerminal() ? Set.of(owner.getId() + ".localTargetV") : Set.of();
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
