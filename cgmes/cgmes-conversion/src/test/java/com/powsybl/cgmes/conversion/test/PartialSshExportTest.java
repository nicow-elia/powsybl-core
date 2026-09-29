/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conformity.CgmesConformity3Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.CgmesExportUtil;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport.UnsupportedChangeBehavior;
import com.powsybl.cgmes.extensions.CgmesMetadataModels;
import com.powsybl.cgmes.model.CgmesMetadataModel;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.datasource.GenericReadOnlyDataSource;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.test.AbstractSerDeTest;
import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.LccConverterStation;
import com.powsybl.iidm.network.LineCommutatedConverter;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkEventRecorder;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.TapChanger;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.events.ExtensionUpdateNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ActivePowerControlAdder;
import com.powsybl.iidm.network.extensions.ReferencePriority;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class PartialSshExportTest extends AbstractSerDeTest {

    /** What the refusal of a change reported by a deprecated setter only says, see EventCompactor (rule 3). */
    private static final String SOLE_ECHO = "reported under the name of a deprecated voltage regulation setter only";

    private static final double TOLERANCE = 1e-7;

    private static final String SWITCH_DIR = "/update/switch/";
    private static final String LOAD_DIR = "/update/load/";
    private static final String GENERATOR_DIR = "/update/generator/";
    private static final String TRANSFORMER_DIR = "/update/transformer/";
    private static final String SHUNT_DIR = "/update/shunt-compensator/";
    private static final String STATIC_VAR_COMPENSATOR_DIR = "/update/static-var-compensator/";
    private static final String HVDC_DIR = "/update/hvdc/";
    private static final String BOUNDARY_LINE_DIR = "/update/boundary-line/";
    private static final String DC_DIR = "/issues/hvdc/";

    /**
     * The transformer of the BE micro grid whose phase tap changer is a PhaseTapChangerAsymmetrical. Its
     * counterpart in the same model is a PhaseTapChangerSymmetrical, which
     * {@link #symmetricalPhaseTapChangerPositionRoundTrip} picks by exclusion.
     */
    private static final String ASYMMETRICAL_PHASE_TAP_CHANGER = "b94318f6-6d24-4f56-96b9-df2531ad6543";

    private static final Pattern FULL_MODEL_ID_PATTERN = Pattern.compile("FullModel rdf:about=\"(.*?)\"");
    private static final Pattern MODEL_VERSION_PATTERN = Pattern.compile("Model.version>(.*?)<");

    private static final String USE_PREVIOUS_VALUES = "iidm.import.cgmes.use-previous-values-during-update";

    // Helpers. A round trip loads the same base model twice, applies the changes to one of the two copies,
    // exports them as a partial SSH file and applies that file to the other copy. This is the exchange the
    // partial SSH export is meant to support, so every supported change is verified this way.

    record RoundTripResult(Network sender, Network receiver, List<NetworkEvent> events, String sshXml) {
    }

    private RoundTripResult roundTrip(String dir, Consumer<Network> senderChanges, String... baselineFiles) throws IOException {
        return roundTrip(new Properties(), dir, senderChanges, baselineFiles);
    }

    /**
     * Round trip that starts from a state the fixture does not have, for instance a regulation that is switched off
     * so that switching it on is the change under test. The preparation is applied to the sender and to the receiver
     * alike, before the recorder is attached, so it is part of the common base model and not part of the change.
     */
    private RoundTripResult roundTrip(String dir, Consumer<Network> prepareBoth, Consumer<Network> senderChanges,
                                      String... baselineFiles) throws IOException {
        Properties importParameters = new Properties();
        return roundTrip(importParameters, () -> readCgmesResources(importParameters, dir, baselineFiles),
                prepareBoth, senderChanges);
    }

    private RoundTripResult roundTrip(Properties importParameters, String dir, Consumer<Network> senderChanges,
                                      String... baselineFiles) throws IOException {
        return roundTrip(importParameters, () -> readCgmesResources(importParameters, dir, baselineFiles), senderChanges);
    }

    /** Round trip on a whole grid model, for instance one of the CGMES conformity models. */
    private RoundTripResult roundTrip(ReadOnlyDataSource dataSource, Consumer<Network> senderChanges) throws IOException {
        return roundTrip(new Properties(), () -> Network.read(dataSource, new Properties()), senderChanges);
    }

    private RoundTripResult roundTrip(Properties importParameters, Supplier<Network> load,
                                      Consumer<Network> senderChanges) throws IOException {
        return roundTrip(importParameters, load, network -> { }, senderChanges);
    }

    private RoundTripResult roundTrip(Properties importParameters, Supplier<Network> load,
                                      Consumer<Network> prepareBoth, Consumer<Network> senderChanges) throws IOException {
        Network sender = load.get();
        Network receiver = load.get();
        prepareBoth.accept(sender);
        prepareBoth.accept(receiver);

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        senderChanges.accept(sender);

        String baseName = "partial-ssh-roundtrip";
        Path exportDir = tmpDir.toAbsolutePath();
        Path sshFile = exportDir.resolve(baseName + "_SSH.xml");
        List<NetworkEvent> exportedEvents =
                PartialSshExport.write(sender, recorder.getEvents(), sshFile, UnsupportedChangeBehavior.FAIL);

        // Nothing can be dropped when unsupported changes are rejected, so every compacted change has to be
        // reported as exported. This catches a mapping that silently writes nothing without rejecting.
        assertEquals(PartialSshExport.compactEvents(recorder.getEvents(), sender), exportedEvents);

        Properties updateParameters = new Properties();
        updateParameters.putAll(importParameters);
        updateParameters.put(USE_PREVIOUS_VALUES, "true");
        receiver.update(new GenericReadOnlyDataSource(exportDir, baseName), updateParameters);

        return new RoundTripResult(sender, receiver, List.copyOf(recorder.getEvents()), Files.readString(sshFile));
    }

    // Round trips, one per supported change

    @Test
    void switchStateRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(SWITCH_DIR, sender -> {
            sender.getSwitch("SeriesCompensator").setOpen(false);
            sender.getSwitch("Breaker").setOpen(true);
        }, "switch_EQ.xml", "switch_SSH.xml");

        assertTrue(result.sshXml().contains("<cim:Breaker rdf:about=\"#_Breaker\">"));
        assertSwitchState(result, "SeriesCompensator");
        assertSwitchState(result, "Breaker");
    }

    /** Closing again is the other half of the switch state: the baseline of this variant has the breaker open. */
    @Test
    void switchClosingRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(SWITCH_DIR,
                sender -> sender.getSwitch("Breaker").setOpen(false), "switch_EQ.xml", "switch_SSH_1.xml");

        assertTrue(result.sshXml().contains("<cim:Switch.open>false</cim:Switch.open>"));
        assertFalse(result.sender().getSwitch("Breaker").isOpen());
        assertSwitchState(result, "Breaker");
    }

    /**
     * An ACLineSegment, EquivalentBranch or SeriesCompensator that the import turned into an IIDM switch is not a
     * Switch in CGMES: it has no open state of its own, and the import derives the state of the IIDM switch from
     * the connection status of its terminals. Opening one is the case that a Switch.open encoding would lose.
     */
    @Test
    void branchModelledAsSwitchOpeningRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(SWITCH_DIR,
                sender -> sender.getSwitch("SeriesCompensator").setOpen(true), "switch_EQ.xml", "switch_SSH_1.xml");

        assertFalse(result.sshXml().contains("Switch.open"));
        assertTrue(result.sshXml().contains("<cim:Terminal rdf:about=\"#_SeriesCompensator-T1\">"));
        assertTrue(result.sender().getSwitch("SeriesCompensator").isOpen());
        assertSwitchState(result, "SeriesCompensator");
    }

    @Test
    void dcSwitchStateRoundTrip() throws IOException {
        Properties importParameters = new Properties();
        importParameters.put(CgmesImport.USE_DETAILED_DC_MODEL, "true");

        RoundTripResult result = roundTrip(importParameters, DC_DIR,
                sender -> sender.getDcSwitch("DCSW_1_1").setOpen(true), "mixed_bipole_EQ.xml");

        assertTrue(result.sshXml().contains("<cim:DCTerminal rdf:about=\"#_T_DCSW_1_1_1\">"));
        assertTrue(result.sender().getDcSwitch("DCSW_1_1").isOpen());
        assertEquals(result.sender().getDcSwitch("DCSW_1_1").isOpen(), result.receiver().getDcSwitch("DCSW_1_1").isOpen());
    }

    @Test
    void loadSetpointsRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(LOAD_DIR, sender -> {
            sender.getLoad("EnergyConsumer").setP0(10.5).setQ0(5.5);
            sender.getLoad("EnergySource").setP0(-200.5).setQ0(-90.5);
        }, "load_EQ.xml", "load_SSH.xml");

        assertTrue(result.sshXml().contains("<cim:EnergyConsumer rdf:about=\"#_EnergyConsumer\">"));
        assertTrue(result.sshXml().contains("<cim:EnergySource rdf:about=\"#_EnergySource\">"));
        assertLoadSetpoints(result, "EnergyConsumer");
        assertLoadSetpoints(result, "EnergySource");
    }

    /**
     * A load exported as an AsynchronousMachine carries its setpoints like any other load. The machine kind and the
     * control flag travel with them, because the CGMES update reads the whole steady state hypothesis of the machine
     * as one block. (https://github.com/powsybl/powsybl-core/issues/4029)
     */
    @Test
    void asynchronousMachineSetpointsRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(LOAD_DIR,
                sender -> sender.getLoad("AsynchronousMachine").setP0(200.5).setQ0(50.5),
                "load_EQ.xml", "load_SSH.xml");

        assertLoadSetpoints(result, "AsynchronousMachine");
    }

    /**
     * The CGMES import only accepts an injection power when both components are present, so a change of a single
     * setpoint has to export both. Exporting only the changed one silently loses the change.
     */
    @Test
    void activePowerOnlyLoadChangeRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(LOAD_DIR,
                sender -> sender.getLoad("EnergyConsumer").setP0(77.5), "load_EQ.xml", "load_SSH.xml");

        assertEquals(1, result.events().size());
        assertTrue(result.sshXml().contains("<cim:EnergyConsumer.p>77.5</cim:EnergyConsumer.p>"));
        assertTrue(result.sshXml().contains("<cim:EnergyConsumer.q>"));
        assertLoadSetpoints(result, "EnergyConsumer");
    }

    @Test
    void generatorSetpointsRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(GENERATOR_DIR,
                sender -> sender.getGenerator("SynchronousMachine").setTargetP(165.0).setTargetQ(-5.0).setTargetV(410.0),
                "generator_EQ.xml", "generator_SSH.xml");

        assertGeneratorTargets(result, "SynchronousMachine");
    }

    @Test
    void generatorVoltageRegulationRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(GENERATOR_DIR,
                sender -> sender.getGenerator("SynchronousMachine").setVoltageRegulatorOn(false),
                "generator_EQ.xml", "generator_SSH.xml");

        assertFalse(result.sender().getGenerator("SynchronousMachine").isVoltageRegulatorOn());
        assertEquals(result.sender().getGenerator("SynchronousMachine").isVoltageRegulatorOn(),
                result.receiver().getGenerator("SynchronousMachine").isVoltageRegulatorOn());
    }

    /**
     * Switching regulation on is the half of the change that the CGMES update reads differently from switching it
     * off: the receiving side derives the state of a machine from the whole steady state hypothesis block, so
     * writing the control flag alone leaves it at the value it had.
     */
    @Test
    void generatorVoltageRegulationSwitchOnRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(GENERATOR_DIR,
                network -> network.getGenerator("SynchronousMachine").setVoltageRegulatorOn(false),
                sender -> sender.getGenerator("SynchronousMachine").setVoltageRegulatorOn(true),
                "generator_EQ.xml", "generator_SSH.xml");

        assertTrue(result.sender().getGenerator("SynchronousMachine").isVoltageRegulatorOn());
        assertEquals(result.sender().getGenerator("SynchronousMachine").isVoltageRegulatorOn(),
                result.receiver().getGenerator("SynchronousMachine").isVoltageRegulatorOn());
    }

    /** A voltage setpoint lives on the RegulatingControl, it must not restate the machine active power. */
    @Test
    void voltageSetpointOnlyDoesNotExportActivePower() throws IOException {
        RoundTripResult result = roundTrip(GENERATOR_DIR,
                sender -> sender.getGenerator("SynchronousMachine").setTargetV(410.0),
                "generator_EQ.xml", "generator_SSH.xml");

        assertFalse(result.sshXml().contains("<cim:RotatingMachine.p>"));
        assertEquals(result.sender().getGenerator("SynchronousMachine").getTargetV(),
                result.receiver().getGenerator("SynchronousMachine").getTargetV(), TOLERANCE);
    }

    /**
     * A generator imported from an ExternalNetworkInjection carries its setpoints on that injection, which the
     * CGMES update reads together with its reference priority and its control flag.
     *
     * <p>The regulation itself is not exercised here: the ExternalNetworkInjection of this fixture has no
     * RegulatingControl, and a change of its voltage regulation is rejected for that reason, like any other
     * equipment without one.</p>
     */
    @Test
    void externalNetworkInjectionSetpointsRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(GENERATOR_DIR,
                sender -> sender.getGenerator("ExternalNetworkInjection").setTargetP(45.0).setTargetQ(-12.0),
                "generator_EQ.xml", "generator_SSH.xml");

        assertTrue(result.sshXml().contains("<cim:ExternalNetworkInjection rdf:about=\"#_ExternalNetworkInjection\">"));
        Generator expected = result.sender().getGenerator("ExternalNetworkInjection");
        Generator actual = result.receiver().getGenerator("ExternalNetworkInjection");
        assertEquals(45.0, expected.getTargetP(), TOLERANCE);
        assertEquals(expected.getTargetP(), actual.getTargetP(), TOLERANCE);
        assertEquals(expected.getTargetQ(), actual.getTargetQ(), TOLERANCE);
    }

    /** An EquivalentInjection carries its setpoints and its regulation itself, it has no RegulatingControl. */
    @Test
    void equivalentInjectionSetpointsRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(GENERATOR_DIR,
                sender -> sender.getGenerator("EquivalentInjection").setTargetP(-70.0).setTargetQ(15.0),
                "generator_EQ.xml", "generator_SSH.xml");

        assertTrue(result.sshXml().contains("<cim:EquivalentInjection rdf:about=\"#_EquivalentInjection\">"));
        assertFalse(result.sshXml().contains("<cim:RegulatingControl rdf:about="));
        Generator expected = result.sender().getGenerator("EquivalentInjection");
        Generator actual = result.receiver().getGenerator("EquivalentInjection");
        assertEquals(expected.getTargetP(), actual.getTargetP(), TOLERANCE);
        assertEquals(expected.getTargetQ(), actual.getTargetQ(), TOLERANCE);
    }

    /**
     * An EquivalentInjection that the equipment model gives the capability to regulate carries its regulation
     * itself, on {@code regulationStatus} and {@code regulationTarget}. The fixture grants no capability, so it is
     * granted here on both sides, as an equipment model that did would. The fixture also gives the injection no
     * VoltageRegulation: it is created before the change set, switched off, since IIDM reports no creation (gap G1).
     */
    @Test
    void equivalentInjectionRegulationRoundTrip() throws IOException {
        Consumer<Network> grantCapability = network -> {
            Generator injection = network.getGenerator("EquivalentInjection");
            injection.setProperty(Conversion.PROPERTY_REGULATION_CAPABILITY, "true");
            injection.newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(false).build();
        };

        RoundTripResult switchedOn = roundTrip(GENERATOR_DIR, grantCapability,
                sender -> {
                    Generator injection = sender.getGenerator("EquivalentInjection");
                    injection.setLocalTargetV(401.0);
                    injection.getVoltageRegulation().setRegulating(true);
                },
                "generator_EQ.xml", "generator_SSH.xml");

        assertTrue(switchedOn.sshXml().contains("<cim:EquivalentInjection.regulationStatus>true</cim:EquivalentInjection.regulationStatus>"));
        assertTrue(switchedOn.sshXml().contains("<cim:EquivalentInjection.regulationTarget>401</cim:EquivalentInjection.regulationTarget>"));
        assertTrue(switchedOn.sender().getGenerator("EquivalentInjection").isVoltageRegulatorOn());
        assertEquivalentInjectionRegulation(switchedOn);

        RoundTripResult switchedOff = roundTrip(GENERATOR_DIR,
                grantCapability.andThen(network -> network.getGenerator("EquivalentInjection")
                        .setTargetV(401.0).setVoltageRegulatorOn(true)),
                sender -> sender.getGenerator("EquivalentInjection").setVoltageRegulatorOn(false),
                "generator_EQ.xml", "generator_SSH.xml");

        assertFalse(switchedOff.sender().getGenerator("EquivalentInjection").isVoltageRegulatorOn());
        assertEquivalentInjectionRegulation(switchedOff);
    }

    /**
     * The deprecated setter that switches the regulation of an EquivalentInjection on creates its VoltageRegulation
     * and reports the change under its deprecated name only (gap G1): the state before cannot be told, and a
     * difference could not undo the creation, so both change exports refuse it with the remedy (review 21 round 2,
     * R2-M2).
     */
    @Test
    @SuppressWarnings("removal")
    void equivalentInjectionRegulationCreatedByADeprecatedSetterIsRejected() {
        Network sender = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        Generator injection = sender.getGenerator("EquivalentInjection");
        injection.setProperty(Conversion.PROPERTY_REGULATION_CAPABILITY, "true");
        injection.setLocalTargetV(401.0);
        assertNull(injection.getVoltageRegulation(), "the fixture is expected to give the injection no regulation");

        List<NetworkEvent> events = RecordedChangeScenarios.record(sender,
                n -> n.getGenerator("EquivalentInjection").setVoltageRegulatorOn(true));

        PowsyblException partial = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, events, UnsupportedChangeBehavior.FAIL));
        assertTrue(partial.getMessage().contains(SOLE_ECHO) && partial.getMessage().contains("Remedy: "),
                partial.getMessage());
        PowsyblException difference = assertThrows(PowsyblException.class,
                () -> CgmesDiffExport.toDifferences(sender, events, new CgmesDiffExport.ExportOptions()));
        assertTrue(difference.getMessage().contains(SOLE_ECHO) && difference.getMessage().contains("Remedy: "),
                difference.getMessage());
    }

    private static void assertEquivalentInjectionRegulation(RoundTripResult result) {
        Generator expected = result.sender().getGenerator("EquivalentInjection");
        Generator actual = result.receiver().getGenerator("EquivalentInjection");
        assertEquals(expected.isVoltageRegulatorOn(), actual.isVoltageRegulatorOn());
        assertEquals(expected.getTargetV(), actual.getTargetV(), TOLERANCE);
    }

    /**
     * The equipment model says whether an EquivalentInjection may regulate at all. Without that capability the
     * CGMES update keeps its regulation off whatever the steady state hypothesis says, so switching it on is a
     * change that cannot be exported.
     */
    @Test
    void equivalentInjectionRegulationWithoutCapabilityIsRejected() {
        Network sender = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = sender.getGenerator("EquivalentInjection");
        assertEquals("false", generator.getProperty(Conversion.PROPERTY_REGULATION_CAPABILITY),
                "the test model is expected to give the equivalent injection no regulation capability");
        generator.setLocalTargetV(400.0);
        generator.newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(false).build();

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        generator.getVoltageRegulation().setRegulating(true);

        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL));
        assertTrue(exception.getMessage().contains("has no regulation capability"));
    }

    /**
     * The reference priority selects the angle reference of the network. It is carried by the machine, so a change
     * of it writes the machine block, and withdrawing one has to reach the receiving side as well as setting one.
     */
    @Test
    void generatorReferencePriorityRoundTrip() throws IOException {
        RoundTripResult set = roundTrip(GENERATOR_DIR,
                sender -> ReferencePriority.set(sender.getGenerator("SynchronousMachine"), 3),
                "generator_EQ.xml", "generator_SSH.xml");

        assertEquals(3, ReferencePriority.get(set.sender().getGenerator("SynchronousMachine")));
        assertEquals(ReferencePriority.get(set.sender().getGenerator("SynchronousMachine")),
                ReferencePriority.get(set.receiver().getGenerator("SynchronousMachine")));

        RoundTripResult withdraw = roundTrip(GENERATOR_DIR,
                network -> ReferencePriority.set(network.getGenerator("SynchronousMachine"), 3),
                sender -> ReferencePriority.set(sender.getGenerator("SynchronousMachine"), 0),
                "generator_EQ.xml", "generator_SSH.xml");

        assertEquals(0, ReferencePriority.get(withdraw.sender().getGenerator("SynchronousMachine")));
        assertEquals(ReferencePriority.get(withdraw.sender().getGenerator("SynchronousMachine")),
                ReferencePriority.get(withdraw.receiver().getGenerator("SynchronousMachine")));
    }

    /**
     * The participation factor of a generator belongs to the CGMES GeneratingUnit it is part of, not to the
     * machine, so it is described there.
     */
    @Test
    void generatorParticipationFactorRoundTrip() throws IOException {
        Properties importParameters = new Properties();
        importParameters.put(CgmesImport.CREATE_ACTIVE_POWER_CONTROL_EXTENSION, "true");

        RoundTripResult result = roundTrip(importParameters, GENERATOR_DIR,
                sender -> sender.getGenerator("SynchronousMachine").getExtension(ActivePowerControl.class)
                        .setParticipationFactor(0.75),
                "generator_EQ.xml", "generator_SSH.xml");

        assertTrue(result.sshXml().contains("<cim:GeneratingUnit.normalPF>0.75</cim:GeneratingUnit.normalPF>"));
        ActivePowerControl<Generator> expected = result.sender().getGenerator("SynchronousMachine").getExtension(ActivePowerControl.class);
        ActivePowerControl<Generator> actual = result.receiver().getGenerator("SynchronousMachine").getExtension(ActivePowerControl.class);
        assertEquals(0.75, expected.getParticipationFactor(), TOLERANCE);
        assertEquals(expected.getParticipationFactor(), actual.getParticipationFactor(), TOLERANCE);
    }

    /** A generator that belongs to no CGMES GeneratingUnit has nowhere to carry a participation factor. */
    @Test
    void participationFactorWithoutGeneratingUnitIsRejected() {
        Network sender = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = sender.getGenerator("ExternalNetworkInjection");
        assertFalse(generator.hasProperty(Conversion.PROPERTY_GENERATING_UNIT),
                "the test model is expected to give the external network injection no generating unit");
        generator.newExtension(ActivePowerControlAdder.class)
                .withParticipate(true)
                .withDroop(4.0)
                .withParticipationFactor(1.0)
                .add();

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        generator.getExtension(ActivePowerControl.class).setParticipationFactor(2.0);

        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL));
        assertTrue(exception.getMessage().contains("has no CGMES GeneratingUnit"));
    }

    /**
     * A boundary line without generation carries the whole injection at its boundary as a fixed load, which CGMES
     * holds on the EquivalentInjection of that boundary.
     */
    @Test
    void boundaryLineLoadRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(BOUNDARY_LINE_DIR,
                sender -> sender.getBoundaryLine("ACLineSegment").setP0(310.5).setQ0(-40.5),
                "boundaryLine_EQ.xml", "boundaryLine_EQ_BD.xml", "boundaryLine_SSH.xml");

        assertTrue(result.sshXml().contains("<cim:EquivalentInjection rdf:about=\"#_ACLineSegment-EquivalentInjection\">"));
        assertBoundaryLineOperatingValues(result, "ACLineSegment");
    }

    /**
     * A boundary line with a generation splits the injection of the boundary between the line and the generation,
     * where CGMES holds a single one, so both halves travel together.
     */
    @Test
    void boundaryLineGenerationRoundTrip() throws IOException {
        RoundTripResult switchedOff = roundTrip(BOUNDARY_LINE_DIR, sender -> {
            BoundaryLine.Generation generation = sender.getBoundaryLine("EquivalentBranch").getGeneration();
            generation.setVoltageRegulationOn(false);
            generation.setTargetP(300.0).setTargetQ(-20.0);
        }, "boundaryLine_EQ.xml", "boundaryLine_EQ_BD.xml", "boundaryLine_SSH.xml");

        assertFalse(switchedOff.sender().getBoundaryLine("EquivalentBranch").getGeneration().isVoltageRegulationOn());
        assertBoundaryLineOperatingValues(switchedOff, "EquivalentBranch");

        RoundTripResult switchedOn = roundTrip(BOUNDARY_LINE_DIR,
                network -> network.getBoundaryLine("EquivalentBranch").getGeneration().setVoltageRegulationOn(false),
                sender -> sender.getBoundaryLine("EquivalentBranch").getGeneration()
                        .setTargetV(402.0).setVoltageRegulationOn(true),
                "boundaryLine_EQ.xml", "boundaryLine_EQ_BD.xml", "boundaryLine_SSH.xml");

        assertTrue(switchedOn.sender().getBoundaryLine("EquivalentBranch").getGeneration().isVoltageRegulationOn());
        assertBoundaryLineOperatingValues(switchedOn, "EquivalentBranch");
    }

    @Test
    void tapPositionsRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(TRANSFORMER_DIR, sender -> {
            sender.getTwoWindingsTransformer("T2W").getPhaseTapChanger().setTapPosition(-1);
            sender.getThreeWindingsTransformer("T3W").getLeg2().getRatioTapChanger().setTapPosition(7);
        }, "transformer_EQ.xml", "transformer_SSH.xml");

        TwoWindingsTransformer expected2w = result.sender().getTwoWindingsTransformer("T2W");
        TwoWindingsTransformer actual2w = result.receiver().getTwoWindingsTransformer("T2W");
        assertEquals(expected2w.getPhaseTapChanger().getTapPosition(), actual2w.getPhaseTapChanger().getTapPosition());

        ThreeWindingsTransformer expected3w = result.sender().getThreeWindingsTransformer("T3W");
        ThreeWindingsTransformer actual3w = result.receiver().getThreeWindingsTransformer("T3W");
        assertEquals(expected3w.getLeg2().getRatioTapChanger().getTapPosition(),
                actual3w.getLeg2().getRatioTapChanger().getTapPosition());
    }

    /**
     * The regulation of a tap changer lives on its TapChangerControl, which carries the target and the deadband
     * both a phase and a ratio tap changer regulate with.
     */
    @Test
    void tapChangerRegulationValueAndDeadbandRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(TRANSFORMER_DIR, sender -> {
            PhaseTapChanger phaseTapChanger = sender.getTwoWindingsTransformer("T2W").getPhaseTapChanger();
            phaseTapChanger.setRegulationValue(phaseTapChanger.getRegulationValue() + 5.0).setTargetDeadband(1.5);
            RatioTapChanger ratioTapChanger = sender.getThreeWindingsTransformer("T3W").getLeg2().getRatioTapChanger();
            ratioTapChanger.setRegulationValue(ratioTapChanger.getRegulationValue() + 1.0).setTargetDeadband(3.0);
        }, "transformer_EQ.xml", "transformer_SSH.xml");

        assertTrue(result.sshXml().contains("<cim:TapChangerControl rdf:about=\"#_T2W-PhaseTapChanger-Control\">"));
        assertTrue(result.sshXml().contains("<cim:TapChangerControl rdf:about=\"#_T3W-Winding2-RatioTapChanger-Control\">"));
        assertTapChangerRegulation(result.sender().getTwoWindingsTransformer("T2W").getPhaseTapChanger(),
                result.receiver().getTwoWindingsTransformer("T2W").getPhaseTapChanger(), "T2W phase tap changer");
        assertTapChangerRegulation(result.sender().getThreeWindingsTransformer("T3W").getLeg2().getRatioTapChanger(),
                result.receiver().getThreeWindingsTransformer("T3W").getLeg2().getRatioTapChanger(), "T3W leg 2 ratio tap changer");
    }

    /**
     * The deprecated voltage regulation setters of IIDM report every change twice, under the name of the
     * VoltageRegulation and under their historical one (powsybl-core #3699). The export has to write the same file
     * whichever of the two APIs the change was made with.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("deprecatedSetterTwins")
    @SuppressWarnings("removal")
    void deprecatedSettersExportLikeTheVoltageRegulation(String name, String dir, String[] files,
                                                         Consumer<Network> throughDeprecatedSetter,
                                                         Consumer<Network> throughVoltageRegulation) {
        assertEquals(partialSshOf(dir, files, throughVoltageRegulation), partialSshOf(dir, files, throughDeprecatedSetter), name);
    }

    @SuppressWarnings("removal")
    static Stream<Arguments> deprecatedSetterTwins() {
        String[] generator = {"generator_EQ.xml", "generator_SSH.xml"};
        String[] shunt = {"shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml"};
        String[] svc = {"staticVarCompensator_EQ.xml", "staticVarCompensator_SSH.xml"};
        String[] transformer = {"transformer_EQ.xml", "transformer_SSH.xml"};
        return Stream.of(
                Arguments.of("generatorTargetVThroughDeprecatedSetter", GENERATOR_DIR, generator,
                        (Consumer<Network>) n -> n.getGenerator("SynchronousMachine").setTargetV(410.0),
                        (Consumer<Network>) n -> {
                            Generator g = n.getGenerator("SynchronousMachine");
                            if (g.hasRegulatingTerminal()) {
                                g.getVoltageRegulation().setTargetValue(410.0);
                            } else {
                                g.setLocalTargetV(410.0);
                            }
                        }),
                Arguments.of("generatorRegulationOffThroughDeprecatedSetter", GENERATOR_DIR, generator,
                        (Consumer<Network>) n -> n.getGenerator("SynchronousMachine").setVoltageRegulatorOn(false),
                        (Consumer<Network>) n -> n.getGenerator("SynchronousMachine").getVoltageRegulation().setRegulating(false)),
                Arguments.of("generatorTargetQThroughDeprecatedSetter", GENERATOR_DIR, generator,
                        (Consumer<Network>) n -> n.getGenerator("SynchronousMachine").setTargetQ(-5.0),
                        (Consumer<Network>) n -> n.getGenerator("SynchronousMachine").setLocalTargetQ(-5.0)),
                Arguments.of("shuntTargetVThroughDeprecatedSetter", SHUNT_DIR, shunt,
                        (Consumer<Network>) n -> n.getShuntCompensator("LinearShuntCompensator").setTargetV(407.0),
                        (Consumer<Network>) n -> {
                            ShuntCompensator s = n.getShuntCompensator("LinearShuntCompensator");
                            if (s.hasRegulatingTerminal()) {
                                s.getVoltageRegulation().setTargetValue(407.0);
                            } else {
                                s.setLocalTargetV(407.0);
                            }
                        }),
                Arguments.of("shuntRegulationThroughDeprecatedSetter", SHUNT_DIR, shunt,
                        (Consumer<Network>) n -> n.getShuntCompensator("LinearShuntCompensator").setVoltageRegulatorOn(true).setTargetDeadband(1.0),
                        (Consumer<Network>) n -> n.getShuntCompensator("LinearShuntCompensator").getVoltageRegulation()
                                .setRegulating(true).setTargetDeadband(1.0)),
                Arguments.of("staticVarCompensatorVoltageSetpointThroughDeprecatedSetter", STATIC_VAR_COMPENSATOR_DIR, svc,
                        (Consumer<Network>) n -> n.getStaticVarCompensator("StaticVarCompensator-V").setVoltageSetpoint(400.0),
                        (Consumer<Network>) n -> {
                            StaticVarCompensator c = n.getStaticVarCompensator("StaticVarCompensator-V");
                            if (c.hasRegulatingTerminal()) {
                                c.getVoltageRegulation().setTargetValue(400.0);
                            } else {
                                c.setLocalTargetV(400.0);
                            }
                        }),
                Arguments.of("staticVarCompensatorRegulationOffThroughDeprecatedSetter", STATIC_VAR_COMPENSATOR_DIR, svc,
                        (Consumer<Network>) n -> n.getStaticVarCompensator("StaticVarCompensator-V").setRegulating(false),
                        (Consumer<Network>) n -> n.getStaticVarCompensator("StaticVarCompensator-V").getVoltageRegulation().setRegulating(false)),
                Arguments.of("ratioTapChangerRegulationThroughDeprecatedSetter", TRANSFORMER_DIR, transformer,
                        (Consumer<Network>) n -> n.getThreeWindingsTransformer("T3W").getLeg2().getRatioTapChanger()
                                .setRegulationValue(226.0).setTargetDeadband(3.0),
                        (Consumer<Network>) n -> n.getThreeWindingsTransformer("T3W").getLeg2().getRatioTapChanger()
                                .getVoltageRegulation().setTargetValue(226.0).setTargetDeadband(3.0)));
    }

    private static String partialSshOf(String dir, String[] files, Consumer<Network> change) {
        Network network = readCgmesResources(dir, files);
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        network.addListener(recorder);
        change.accept(network);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PartialSshExport.write(network, recorder.getEvents(), out, new PartialSshExport.ExportOptions()
                .setUnsupportedChangeBehavior(UnsupportedChangeBehavior.FAIL)
                .setCreated(ZonedDateTime.parse("2026-09-28T12:00:00Z")));
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * A regulation target measured at a regulating terminal that CGMES orients the other way than IIDM: the import
     * multiplies what it reads by the sign it recorded as {@code CGMES.terminalSign} (generators, static var
     * compensators, VSC converter stations and phase tap changers alike), so the export has to apply the same sign
     * for the value to survive a round trip (rule R-SIGN of plan 21; the fixtures of this repository all have a
     * sign of +1, hence the property set by hand on both sides).
     */
    @Test
    void generatorReactivePowerTargetSurvivesAReversedRegulatingTerminal() throws IOException {
        RoundTripResult result = roundTrip(GENERATOR_DIR,
                network -> {
                    Generator generator = network.getGenerator("SynchronousMachine");
                    generator.setProperty(Conversion.PROPERTY_MODE, "RegulatingControlModeKind.reactivePower");
                    generator.setProperty(CgmesExportUtil.getTerminalSignPropertyName(""), "-1");
                    generator.newVoltageRegulation()
                            .withMode(RegulationMode.REACTIVE_POWER)
                            .withTargetValue(25.0)
                            .withTerminal(generator.getTerminal())
                            .withRegulating(true)
                            .build();
                },
                sender -> sender.getGenerator("SynchronousMachine").getVoltageRegulation().setTargetValue(30.0),
                "generator_EQ.xml", "generator_SSH.xml");

        assertEquals(30.0, result.receiver().getGenerator("SynchronousMachine").getRegulatingTargetQ(), TOLERANCE);
    }

    /** As {@link #generatorReactivePowerTargetSurvivesAReversedRegulatingTerminal}, for a static var compensator. */
    @Test
    void staticVarCompensatorReactivePowerTargetSurvivesAReversedRegulatingTerminal() throws IOException {
        RoundTripResult result = roundTrip(STATIC_VAR_COMPENSATOR_DIR,
                network -> network.getStaticVarCompensator("StaticVarCompensator-Q")
                        .setProperty(CgmesExportUtil.getTerminalSignPropertyName(""), "-1"),
                sender -> {
                    StaticVarCompensator svc = sender.getStaticVarCompensator("StaticVarCompensator-Q");
                    assertTrue(svc.hasRegulatingTerminal(), "the compensator is expected to regulate a terminal");
                    svc.getVoltageRegulation().setTargetValue(220.0);
                },
                "staticVarCompensator_EQ.xml", "staticVarCompensator_SSH.xml");

        assertEquals(220.0, result.receiver().getStaticVarCompensator("StaticVarCompensator-Q").getRegulatingTargetQ(), TOLERANCE);
    }

    /** As {@link #generatorReactivePowerTargetSurvivesAReversedRegulatingTerminal}, for a VSC converter station. */
    @Test
    void vscReactivePowerTargetSurvivesAReversedRegulatingTerminal() throws IOException {
        RoundTripResult result = roundTrip(HVDC_DIR,
                network -> {
                    converter(network, 2).setProperty(CgmesExportUtil.getTerminalSignPropertyName(""), "-1");
                    RecordedChangeScenarios.regulateOwnTerminal(converter(network, 2));
                },
                sender -> {
                    converter(sender, 2).getVoltageRegulation().setMode(RegulationMode.REACTIVE_POWER);
                    converter(sender, 2).getVoltageRegulation().setTargetValue(30.0);
                }, "hvdc_EQ.xml", "hvdc_SSH.xml");

        assertEquals(30.0, converter(result.receiver(), 2).getRegulatingTargetQ(), TOLERANCE);
    }

    /** As {@link #generatorReactivePowerTargetSurvivesAReversedRegulatingTerminal}, for a phase tap changer. */
    @Test
    void phaseTapChangerActivePowerTargetSurvivesAReversedRegulatingTerminal() throws IOException {
        RoundTripResult result = roundTrip(TRANSFORMER_DIR,
                network -> network.getTwoWindingsTransformer("T2W")
                        .setProperty(CgmesExportUtil.getTerminalSignPropertyName(""), "-1"),
                sender -> {
                    PhaseTapChanger phaseTapChanger = sender.getTwoWindingsTransformer("T2W").getPhaseTapChanger();
                    assertEquals(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL, phaseTapChanger.getRegulationMode());
                    phaseTapChanger.setRegulationValue(phaseTapChanger.getRegulationValue() + 5.0);
                }, "transformer_EQ.xml", "transformer_SSH.xml");

        assertEquals(result.sender().getTwoWindingsTransformer("T2W").getPhaseTapChanger().getRegulationValue(),
                result.receiver().getTwoWindingsTransformer("T2W").getPhaseTapChanger().getRegulationValue(), TOLERANCE);
    }

    /**
     * Whether a tap changer regulates is only carried by its TapChangerControl: the CGMES update ignores
     * {@code TapChanger.controlEnabled}, so both directions of the switch have to travel on the control.
     */
    @Test
    void tapChangerRegulationStateRoundTrip() throws IOException {
        RoundTripResult switchedOff = roundTrip(TRANSFORMER_DIR,
                sender -> sender.getTwoWindingsTransformer("T2W").getPhaseTapChanger().setRegulating(false),
                "transformer_EQ.xml", "transformer_SSH.xml");

        assertFalse(switchedOff.sender().getTwoWindingsTransformer("T2W").getPhaseTapChanger().isRegulating());
        assertTapChangerRegulation(switchedOff.sender().getTwoWindingsTransformer("T2W").getPhaseTapChanger(),
                switchedOff.receiver().getTwoWindingsTransformer("T2W").getPhaseTapChanger(), "T2W phase tap changer");

        RoundTripResult switchedOn = roundTrip(TRANSFORMER_DIR,
                network -> network.getTwoWindingsTransformer("T2W").getPhaseTapChanger().setRegulating(false),
                sender -> sender.getTwoWindingsTransformer("T2W").getPhaseTapChanger().setRegulating(true),
                "transformer_EQ.xml", "transformer_SSH.xml");

        assertTrue(switchedOn.sender().getTwoWindingsTransformer("T2W").getPhaseTapChanger().isRegulating());
        assertTapChangerRegulation(switchedOn.sender().getTwoWindingsTransformer("T2W").getPhaseTapChanger(),
                switchedOn.receiver().getTwoWindingsTransformer("T2W").getPhaseTapChanger(), "T2W phase tap changer");
    }

    /**
     * A phase tap changer limiting a current is described with the values it really has, where the full export
     * writes zeros. A partial file is applied on top of a state the receiver already holds, so writing zeros would
     * reset a regulation that never changed.
     *
     * <p>The phase tap changer of the fixture controls active power, so the current limiter mode is set here and
     * only the exported file is checked: the CGMES regulating control of the receiver would still say active
     * power.</p>
     */
    @Test
    void phaseTapChangerCurrentLimiterRegulationIsExportedWithItsRealValues() {
        Network sender = readCgmesResources(TRANSFORMER_DIR, "transformer_EQ.xml", "transformer_SSH.xml");
        PhaseTapChanger phaseTapChanger = sender.getTwoWindingsTransformer("T2W").getPhaseTapChanger();
        phaseTapChanger.setRegulating(false)
                .setRegulationMode(PhaseTapChanger.RegulationMode.CURRENT_LIMITER)
                .setRegulationValue(800.0)
                .setTargetDeadband(10.0);

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        phaseTapChanger.setRegulating(true);

        String sshXml = PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL);
        assertTrue(sshXml.contains("<cim:RegulatingControl.targetValue>800</cim:RegulatingControl.targetValue>"),
                () -> "expected the real regulation value, was " + sshXml);
        assertTrue(sshXml.contains("<cim:RegulatingControl.targetDeadband>10</cim:RegulatingControl.targetDeadband>"));
        assertTrue(sshXml.contains("<cim:RegulatingControl.enabled>true</cim:RegulatingControl.enabled>"));
        assertTrue(sshXml.contains("UnitMultiplier.none\"/>"));
    }

    /** The regulation mode of a tap changer is RegulatingControl.mode, which belongs to the equipment model. */
    @Test
    void tapChangerRegulationModeChangeIsRejected() {
        Network sender = readCgmesResources(TRANSFORMER_DIR, "transformer_EQ.xml", "transformer_SSH.xml");
        PhaseTapChanger phaseTapChanger = sender.getTwoWindingsTransformer("T2W").getPhaseTapChanger();
        phaseTapChanger.setRegulating(false);

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        phaseTapChanger.setRegulationMode(PhaseTapChanger.RegulationMode.CURRENT_LIMITER);

        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL));
        assertTrue(exception.getMessage().contains("belongs to the EQ profile"));
    }

    /**
     * A PhaseTapChangerSymmetrical is a phase tap changer like any other: its position has to reach the receiving
     * side. The CGMES update query used to leave the class out of the tap changers it selects, so the position was
     * silently dropped on import. (https://github.com/powsybl/powsybl-core/issues/4034)
     */
    @Test
    void symmetricalPhaseTapChangerPositionRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(CgmesConformity3Catalog.microGridBaseCaseBE().dataSource(),
                sender -> moveUp(symmetricalPhaseTapChanger(sender).getPhaseTapChanger()));

        assertEquals(symmetricalPhaseTapChanger(result.sender()).getPhaseTapChanger().getTapPosition(),
                symmetricalPhaseTapChanger(result.receiver()).getPhaseTapChanger().getTapPosition());
    }

    /** The one phase tap changer of the BE micro grid that is not the asymmetrical one. */
    private static TwoWindingsTransformer symmetricalPhaseTapChanger(Network network) {
        return network.getTwoWindingsTransformerStream()
                .filter(t -> t.hasPhaseTapChanger() && !t.getId().equals(ASYMMETRICAL_PHASE_TAP_CHANGER))
                .findFirst().orElseThrow();
    }

    @Test
    void shuntOperatingValuesRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(SHUNT_DIR, sender -> {
            sender.getShuntCompensator("LinearShuntCompensator").setTargetV(407.0);
            sender.getShuntCompensator("NonLinearShuntCompensator").setSectionCount(2).setTargetV(406.0);
        }, "shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml");

        assertShuntOperatingValues(result, "LinearShuntCompensator");
        assertShuntOperatingValues(result, "NonLinearShuntCompensator");
    }

    /**
     * The regulation state of a shunt compensator travels on its RegulatingControl, but the CGMES update only reads
     * the section count and the control flag of the shunt as one block, so both directions of the switch need the
     * whole block next to the control.
     */
    @Test
    void shuntVoltageRegulationSwitchOnRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(SHUNT_DIR,
                sender -> sender.getShuntCompensator("LinearShuntCompensator").setVoltageRegulatorOn(true),
                "shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml");

        assertTrue(result.sender().getShuntCompensator("LinearShuntCompensator").isVoltageRegulatorOn());
        assertShuntRegulationState(result, "LinearShuntCompensator");
    }

    @Test
    void shuntVoltageRegulationSwitchOffRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(SHUNT_DIR,
                network -> network.getShuntCompensator("LinearShuntCompensator").setVoltageRegulatorOn(true),
                sender -> sender.getShuntCompensator("LinearShuntCompensator").setVoltageRegulatorOn(false),
                "shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml");

        assertFalse(result.sender().getShuntCompensator("LinearShuntCompensator").isVoltageRegulatorOn());
        assertShuntRegulationState(result, "LinearShuntCompensator");
    }

    @Test
    void shuntTargetDeadbandRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(SHUNT_DIR,
                network -> network.getShuntCompensator("LinearShuntCompensator").setVoltageRegulatorOn(true),
                sender -> sender.getShuntCompensator("LinearShuntCompensator").setTargetDeadband(2.5),
                "shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml");

        assertTrue(result.sshXml().contains("<cim:RegulatingControl.targetDeadband>2.5</cim:RegulatingControl.targetDeadband>"));
        assertEquals(2.5, result.sender().getShuntCompensator("LinearShuntCompensator").getTargetDeadband(), TOLERANCE);
        assertEquals(result.sender().getShuntCompensator("LinearShuntCompensator").getTargetDeadband(),
                result.receiver().getShuntCompensator("LinearShuntCompensator").getTargetDeadband(), TOLERANCE);
    }

    /**
     * A deadband that is not defined is left out of the file rather than written as a non-value, so the receiving
     * side keeps the one it has. An undefined deadband is only reachable while the regulation is off.
     */
    @Test
    void undefinedShuntTargetDeadbandIsNotWritten() throws IOException {
        RoundTripResult result = roundTrip(SHUNT_DIR,
                network -> network.getShuntCompensator("LinearShuntCompensator").setVoltageRegulatorOn(true).setTargetDeadband(2.5),
                sender -> sender.getShuntCompensator("LinearShuntCompensator")
                        .setVoltageRegulatorOn(false).setTargetDeadband(Double.NaN),
                "shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml");

        assertTrue(Double.isNaN(result.sender().getShuntCompensator("LinearShuntCompensator").getTargetDeadband()));
        assertFalse(result.sshXml().contains("RegulatingControl.targetDeadband"));
        assertEquals(2.5, result.receiver().getShuntCompensator("LinearShuntCompensator").getTargetDeadband(), TOLERANCE);
        assertShuntRegulationState(result, "LinearShuntCompensator");
    }

    /**
     * A RegulatingControl is shared: several pieces of equipment can regulate through the same one, and CGMES then
     * carries a single enabled flag for all of them. Switching one of them off must not switch off the control the
     * others still use, so the exported description holds the combined state of every user.
     */
    @Test
    void sharedRegulatingControlStaysEnabledWhileAnotherUserRegulates() throws IOException {
        RoundTripResult result = roundTrip(SHUNT_DIR,
                network -> {
                    ShuntCompensator linear = network.getShuntCompensator("LinearShuntCompensator");
                    ShuntCompensator nonLinear = network.getShuntCompensator("NonLinearShuntCompensator");
                    nonLinear.setProperty(Conversion.PROPERTY_REGULATING_CONTROL,
                            linear.getProperty(Conversion.PROPERTY_REGULATING_CONTROL));
                    nonLinear.setTargetV(linear.getTargetV());
                    linear.setVoltageRegulatorOn(true);
                    nonLinear.setVoltageRegulatorOn(true);
                },
                sender -> sender.getShuntCompensator("LinearShuntCompensator").setVoltageRegulatorOn(false),
                "shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml");

        // One description of the control, still enabled because the other shunt regulates through it
        assertEquals(1, countOccurrences(result.sshXml(), "<cim:RegulatingControl rdf:about="));
        assertTrue(result.sshXml().contains("<cim:RegulatingControl.enabled>true</cim:RegulatingControl.enabled>"));
        // The shunt that stopped regulating says so through its own control flag, the other one is untouched
        assertShuntRegulationState(result, "LinearShuntCompensator");
        assertFalse(result.receiver().getShuntCompensator("LinearShuntCompensator").isVoltageRegulatorOn());
        assertTrue(result.receiver().getShuntCompensator("NonLinearShuntCompensator").isVoltageRegulatorOn());
    }

    /**
     * A RegulatingControl carries a single target, whose meaning is the CGMES mode, which belongs to the equipment
     * model. A generator whose control regulates voltage has nowhere to put a reactive power target, so a change of
     * its reactive power regulation cannot be exported: writing the block anyway would send a target the receiver
     * reads as a voltage and lose the change silently. Before powsybl-core #3699 this regulation was the
     * RemoteReactivePowerControl extension; it is now the VoltageRegulation of the generator, in reactive power mode.
     */
    @Test
    void remoteReactivePowerControlOnVoltageRegulatingGeneratorIsRejected() {
        Network sender = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = sender.getGenerator("SynchronousMachine");
        assertTrue(generator.getProperty(Conversion.PROPERTY_MODE).endsWith("voltage"),
                "the test model is expected to give the generator a voltage regulating control");
        VoltageRegulation regulation = generator.newVoltageRegulation()
                .withMode(RegulationMode.REACTIVE_POWER)
                .withTargetValue(25.0)
                .withTerminal(generator.getTerminal())
                .withRegulating(false)
                .build();

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        regulation.setTargetValue(55.0);

        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL));
        assertTrue(exception.getMessage().contains("reads its RegulatingControl in the mode"), exception.getMessage());

        String sshXml = PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.IGNORE);
        assertFalse(sshXml.contains("<cim:SynchronousMachine rdf:about="));
        assertFalse(sshXml.contains("<cim:RegulatingControl rdf:about="));
    }

    /**
     * A generator whose CGMES regulating control regulates reactive power carries the target of its voltage
     * regulation in reactive power mode, in MVAr. No fixture of this repository holds such a generator, so the
     * regulation is built here and only the exported file is checked.
     */
    @Test
    void generatorReactivePowerRegulationIsExportedInMegavar() {
        Network sender = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = sender.getGenerator("SynchronousMachine");
        generator.setProperty(Conversion.PROPERTY_MODE, "RegulatingControlModeKind.reactivePower");
        generator.setProperty(CgmesExportUtil.getTerminalSignPropertyName(""), "-1");
        VoltageRegulation regulation = generator.newVoltageRegulation()
                .withMode(RegulationMode.REACTIVE_POWER)
                .withTargetValue(25.0)
                .withTerminal(generator.getTerminal())
                .withRegulating(true)
                .build();

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        regulation.setTargetValue(30.0);

        String sshXml = PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL);
        assertEquals(1, countOccurrences(sshXml, "<cim:RegulatingControl rdf:about="));
        // Negated twice: CGMES uses the load sign convention for the target (powsybl-core #4115), and the regulating
        // terminal is oriented the other way (rule R-SIGN of plan 21)
        assertTrue(sshXml.contains("<cim:RegulatingControl.targetValue>30</cim:RegulatingControl.targetValue>"),
                () -> "expected the target negated by the sign convention and by the terminal sign, was " + sshXml);
        assertTrue(sshXml.contains("UnitMultiplier.M\"/>"));
        assertTrue(sshXml.contains("<cim:RegulatingControl.enabled>true</cim:RegulatingControl.enabled>"));
    }

    @Test
    void staticVarCompensatorSetpointsRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(STATIC_VAR_COMPENSATOR_DIR, sender -> {
            sender.getStaticVarCompensator("StaticVarCompensator-V").setVoltageSetpoint(400.0);
            sender.getStaticVarCompensator("StaticVarCompensator-Q").setReactivePowerSetpoint(215.0);
        }, "staticVarCompensator_EQ.xml", "staticVarCompensator_SSH.xml");

        assertStaticVarCompensatorSetpoints(result, "StaticVarCompensator-V");
        assertStaticVarCompensatorSetpoints(result, "StaticVarCompensator-Q");
    }

    /**
     * Whether a static var compensator regulates travels on its RegulatingControl, next to the block the CGMES
     * update reads as a whole.
     */
    @Test
    void staticVarCompensatorRegulationStateRoundTrip() throws IOException {
        RoundTripResult switchedOff = roundTrip(STATIC_VAR_COMPENSATOR_DIR, sender -> {
            sender.getStaticVarCompensator("StaticVarCompensator-V").setRegulating(false);
            sender.getStaticVarCompensator("StaticVarCompensator-Q").setRegulating(false);
        }, "staticVarCompensator_EQ.xml", "staticVarCompensator_SSH.xml");

        assertFalse(switchedOff.sender().getStaticVarCompensator("StaticVarCompensator-V").isRegulating());
        assertStaticVarCompensatorRegulationState(switchedOff, "StaticVarCompensator-V");
        assertStaticVarCompensatorRegulationState(switchedOff, "StaticVarCompensator-Q");

        RoundTripResult switchedOn = roundTrip(STATIC_VAR_COMPENSATOR_DIR,
                network -> {
                    network.getStaticVarCompensator("StaticVarCompensator-V").setRegulating(false);
                    network.getStaticVarCompensator("StaticVarCompensator-Q").setRegulating(false);
                },
                sender -> {
                    sender.getStaticVarCompensator("StaticVarCompensator-V").setRegulating(true);
                    sender.getStaticVarCompensator("StaticVarCompensator-Q").setRegulating(true);
                },
                "staticVarCompensator_EQ.xml", "staticVarCompensator_SSH.xml");

        assertTrue(switchedOn.sender().getStaticVarCompensator("StaticVarCompensator-V").isRegulating());
        assertStaticVarCompensatorRegulationState(switchedOn, "StaticVarCompensator-V");
        assertStaticVarCompensatorRegulationState(switchedOn, "StaticVarCompensator-Q");
    }

    /**
     * What a static var compensator regulates is RegulatingControl.mode, which belongs to the equipment model, so a
     * change of it cannot be written to a steady state hypothesis file.
     */
    @Test
    void staticVarCompensatorModeChangeIsRejected() {
        Network sender = readCgmesResources(STATIC_VAR_COMPENSATOR_DIR,
                "staticVarCompensator_EQ.xml", "staticVarCompensator_SSH.xml");
        StaticVarCompensator svc = sender.getStaticVarCompensator("StaticVarCompensator-V");
        svc.setLocalTargetQ(10.0);

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        svc.getVoltageRegulation().setMode(RegulationMode.REACTIVE_POWER);

        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL));
        assertTrue(exception.getMessage().contains("belongs to the EQ profile"));

        String sshXml = PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.IGNORE);
        assertFalse(sshXml.contains("<cim:StaticVarCompensator rdf:about="));
    }

    @Test
    void hvdcAndVscVoltageSetpointsRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(HVDC_DIR, sender -> {
            sender.getHvdcLine("DCLineSegment-Lcc").setActivePowerSetpoint(350.0);
            HvdcLine vscLine = sender.getHvdcLine("DCLineSegment-Vsc");
            vscLine.setActivePowerSetpoint(497.0);
            ((VscConverterStation) vscLine.getConverterStation1()).setVoltageSetpoint(396.54);
            ((VscConverterStation) vscLine.getConverterStation2()).setVoltageSetpoint(391.0);
        }, "hvdc_EQ.xml", "hvdc_SSH.xml");

        assertHvdcSetpoint(result, "DCLineSegment-Lcc");
        assertHvdcSetpoint(result, "DCLineSegment-Vsc");
        assertVscVoltageSetpoint(result, 1);
        assertVscVoltageSetpoint(result, 2);
    }

    /**
     * The local reactive power target of a VSC station is its {@code ACDCConverter.q}, which the import reads in one
     * block with {@code targetPpcc}. That block of the inverter carries a {@code targetPpcc} of zero, and since
     * powsybl-core #4057 the import takes a zero stated on either side as the power of the link: writing the block of
     * the changed station alone would bring the link down. Both converters are therefore written, as for any change of
     * the power of the link.
     */
    @ParameterizedTest(name = "converter {0}")
    @ValueSource(ints = {1, 2})
    void vscLocalReactiveTargetKeepsTheActivePowerOfTheLink(int side) throws IOException {
        RoundTripResult result = roundTrip(HVDC_DIR, sender -> converter(sender, side).setLocalTargetQ(12.5),
                "hvdc_EQ.xml", "hvdc_SSH.xml");

        HvdcLine expected = result.sender().getHvdcLine("DCLineSegment-Vsc");
        HvdcLine actual = result.receiver().getHvdcLine("DCLineSegment-Vsc");
        assertEquals(12.5, converter(result.receiver(), side).getLocalTargetQ(), TOLERANCE);
        assertEquals(expected.getActivePowerSetpoint(), actual.getActivePowerSetpoint(), TOLERANCE);
        assertEquals(expected.getMaxP(), actual.getMaxP(), TOLERANCE);
        assertEquals(expected.getConvertersMode(), actual.getConvertersMode());
    }

    /**
     * Zero is a setpoint like any other, and a line brought down to no power is exactly the change an operator
     * expects to survive. A targetPpcc of zero is a setpoint, not a missing value: since powsybl-core #4057 (which
     * fixed https://github.com/powsybl/powsybl-core/issues/4028) the import takes a finite targetPpcc stated on either
     * side as the power of the link.
     */
    @Test
    void hvdcActivePowerSetpointOfZeroRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(HVDC_DIR, sender -> {
            sender.getHvdcLine("DCLineSegment-Lcc").setActivePowerSetpoint(0.0);
            sender.getHvdcLine("DCLineSegment-Vsc").setActivePowerSetpoint(0.0);
        }, "hvdc_EQ.xml", "hvdc_SSH.xml");

        // The two lines take different paths through the mode computation of the import: a CsConverter carries an
        // operating mode, a VsConverter does not and falls back to the mode it had.
        for (String id : List.of("DCLineSegment-Lcc", "DCLineSegment-Vsc")) {
            assertEquals(0.0, result.receiver().getHvdcLine(id).getActivePowerSetpoint(), TOLERANCE, id);
            assertHvdcSetpoint(result, id);
            assertEquals(result.sender().getHvdcLine(id).getConvertersMode(),
                    result.receiver().getHvdcLine(id).getConvertersMode(), id);
        }
    }

    /**
     * The reactive power setpoint of a voltage source converter. The import reads it as
     * {@code -terminalSign * targetQpcc}, so the export has to apply the same sign for the value to survive.
     * (https://github.com/powsybl/powsybl-core/issues/4027, fixed upstream by #4054)
     *
     * <p>The setpoint is only the active one while the converter regulates reactive power, so the sender switches
     * the mode of its VoltageRegulation first (powsybl-core #3699: {@code setVoltageRegulatorOn(false)} keeps the
     * voltage mode and the exported targetQpcc is then zero).</p>
     */
    @Test
    void vscReactivePowerSetpointRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(HVDC_DIR,
                network -> RecordedChangeScenarios.regulateOwnTerminal(converter(network, 2)),
                sender -> {
                    converter(sender, 2).getVoltageRegulation().setMode(RegulationMode.REACTIVE_POWER);
                    converter(sender, 2).getVoltageRegulation().setTargetValue(30.0);
                }, "hvdc_EQ.xml", "hvdc_SSH.xml");

        assertEquals(30.0, converter(result.sender(), 2).getRegulatingTargetQ(), TOLERANCE);
        assertEquals(converter(result.sender(), 2).getRegulatingTargetQ(),
                converter(result.receiver(), 2).getRegulatingTargetQ(), TOLERANCE);
        assertEquals(converter(result.sender(), 2).isRegulatingWithMode(RegulationMode.VOLTAGE),
                converter(result.receiver(), 2).isRegulatingWithMode(RegulationMode.VOLTAGE));
    }

    /**
     * Which end of an HVDC line rectifies is carried by the two converters, next to the power of the link, so a
     * change of the direction travels with the setpoint.
     */
    @Test
    void hvdcConvertersModeRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(HVDC_DIR, sender -> {
            sender.getHvdcLine("DCLineSegment-Lcc").setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER);
            sender.getHvdcLine("DCLineSegment-Vsc").setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER);
        }, "hvdc_EQ.xml", "hvdc_SSH.xml");

        for (String id : List.of("DCLineSegment-Lcc", "DCLineSegment-Vsc")) {
            assertEquals(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER,
                    result.sender().getHvdcLine(id).getConvertersMode(), id);
            assertEquals(result.sender().getHvdcLine(id).getConvertersMode(),
                    result.receiver().getHvdcLine(id).getConvertersMode(), id);
            assertHvdcSetpoint(result, id);
        }
    }

    /**
     * The power factor of a line commutated converter is not a property of its own in CGMES: the profile carries
     * the active and the reactive power of the converter, and the import derives the factor back from them, with
     * the sign of the active power.
     */
    @Test
    void lccPowerFactorRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(HVDC_DIR, sender -> {
            HvdcLine line = sender.getHvdcLine("DCLineSegment-Lcc");
            ((LccConverterStation) line.getConverterStation2()).setPowerFactor(0.95f);
            ((LccConverterStation) line.getConverterStation1()).setPowerFactor(-0.9f);
        }, "hvdc_EQ.xml", "hvdc_SSH.xml");

        HvdcLine expected = result.sender().getHvdcLine("DCLineSegment-Lcc");
        HvdcLine actual = result.receiver().getHvdcLine("DCLineSegment-Lcc");
        assertEquals(0.95f, ((LccConverterStation) expected.getConverterStation2()).getPowerFactor(), 1e-6);
        assertEquals(((LccConverterStation) expected.getConverterStation1()).getPowerFactor(),
                ((LccConverterStation) actual.getConverterStation1()).getPowerFactor(), 1e-6);
        assertEquals(((LccConverterStation) expected.getConverterStation2()).getPowerFactor(),
                ((LccConverterStation) actual.getConverterStation2()).getPowerFactor(), 1e-6);
    }

    /** A line carrying no power carries no power factor either: its active and reactive power are both zero. */
    @Test
    void lccPowerFactorWithoutPowerIsRejected() {
        Network sender = readCgmesResources(HVDC_DIR, "hvdc_EQ.xml", "hvdc_SSH.xml");
        HvdcLine line = sender.getHvdcLine("DCLineSegment-Lcc");
        line.setActivePowerSetpoint(0.0);

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        ((LccConverterStation) line.getConverterStation2()).setPowerFactor(0.95f);

        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL));
        assertTrue(exception.getMessage().contains("which are zero"));
    }

    /**
     * Whether a voltage source converter regulates voltage is the control mode of its reactive power, which the
     * CGMES import reads together with the setpoint of the mode it turns on.
     */
    @Test
    void vscVoltageRegulationStateRoundTrip() throws IOException {
        // Switching from voltage to reactive power regulation is a change of the mode since powsybl-core #3699
        RoundTripResult switchedOff = roundTrip(HVDC_DIR,
                network -> RecordedChangeScenarios.regulateOwnTerminal(converter(network, 2)),
                sender -> {
                    converter(sender, 2).getVoltageRegulation().setMode(RegulationMode.REACTIVE_POWER);
                    converter(sender, 2).getVoltageRegulation().setTargetValue(25.0);
                }, "hvdc_EQ.xml", "hvdc_SSH.xml");

        assertFalse(converter(switchedOff.sender(), 2).isRegulatingWithMode(RegulationMode.VOLTAGE));
        assertEquals(converter(switchedOff.sender(), 2).isRegulatingWithMode(RegulationMode.VOLTAGE),
                converter(switchedOff.receiver(), 2).isRegulatingWithMode(RegulationMode.VOLTAGE));
        assertEquals(25.0, converter(switchedOff.receiver(), 2).getRegulatingTargetQ(), TOLERANCE);

        RoundTripResult switchedOn = roundTrip(HVDC_DIR,
                network -> {
                    RecordedChangeScenarios.regulateOwnTerminal(converter(network, 2));
                    converter(network, 2).getVoltageRegulation().setMode(RegulationMode.REACTIVE_POWER);
                    converter(network, 2).getVoltageRegulation().setTargetValue(25.0);
                },
                sender -> {
                    converter(sender, 2).getVoltageRegulation().setMode(RegulationMode.VOLTAGE);
                    converter(sender, 2).getVoltageRegulation().setTargetValue(394.0);
                },
                "hvdc_EQ.xml", "hvdc_SSH.xml");

        assertTrue(converter(switchedOn.sender(), 2).isRegulatingWithMode(RegulationMode.VOLTAGE));
        assertEquals(converter(switchedOn.sender(), 2).isRegulatingWithMode(RegulationMode.VOLTAGE),
                converter(switchedOn.receiver(), 2).isRegulatingWithMode(RegulationMode.VOLTAGE));
        assertEquals(394.0, converter(switchedOn.receiver(), 2).getRegulatingTargetV(), TOLERANCE);
    }

    /**
     * A converter of the detailed DC model carries its own control modes and setpoints, which the CGMES update
     * reads as one group.
     */
    @Test
    void detailedVscSetpointsRoundTrip() throws IOException {
        RoundTripResult reactivePower = roundTrip(detailedDcModel(), DC_DIR,
                sender -> sender.getVoltageSourceConverter("VSC_1_2").setReactivePowerSetpoint(40.0),
                "mixed_bipole_EQ.xml", "mixed_bipole_SSH.xml");

        assertFalse(reactivePower.sender().getVoltageSourceConverter("VSC_1_2").isVoltageRegulatorOn());
        assertEquals(40.0, reactivePower.receiver().getVoltageSourceConverter("VSC_1_2").getReactivePowerSetpoint(), TOLERANCE);
        assertEquals(reactivePower.sender().getVoltageSourceConverter("VSC_1_2").isVoltageRegulatorOn(),
                reactivePower.receiver().getVoltageSourceConverter("VSC_1_2").isVoltageRegulatorOn());

        // Switching to voltage regulation is a change of the mode and of the one target since powsybl-core #3699:
        // the deprecated setVoltageSetpoint(400) of a converter regulating reactive power only sets its local target
        RoundTripResult voltage = roundTrip(detailedDcModel(), DC_DIR, sender -> {
            VoltageRegulation regulation = sender.getVoltageSourceConverter("VSC_1_2").getVoltageRegulation();
            regulation.setMode(RegulationMode.VOLTAGE);
            regulation.setTargetValue(400.0);
            regulation.setRegulating(true);
        }, "mixed_bipole_EQ.xml", "mixed_bipole_SSH.xml");

        assertTrue(voltage.sender().getVoltageSourceConverter("VSC_1_2").isRegulatingWithMode(RegulationMode.VOLTAGE));
        assertEquals(voltage.sender().getVoltageSourceConverter("VSC_1_2").isRegulatingWithMode(RegulationMode.VOLTAGE),
                voltage.receiver().getVoltageSourceConverter("VSC_1_2").isRegulatingWithMode(RegulationMode.VOLTAGE));
        assertEquals(400.0, voltage.receiver().getVoltageSourceConverter("VSC_1_2").getRegulatingTargetV(), TOLERANCE);
    }

    /**
     * A VsConverter has no control flag: the CGMES import rebuilds the VoltageRegulation of a converter from
     * {@code qPccControl} and always makes it regulate. A converter whose regulation is switched off (in particular a
     * station after the legacy {@code setVoltageRegulatorOn(false)}, which since powsybl-core #3699 keeps the voltage
     * mode and only stops regulating) would come back regulating in another mode, so the change is refused rather than
     * exported as a different state (review 21 finding M4).
     */
    @Test
    @SuppressWarnings("removal")
    void vscWhoseRegulationIsSwitchedOffIsRejected() {
        Network station = readCgmesResources(HVDC_DIR, "hvdc_EQ.xml", "hvdc_SSH.xml");
        NetworkEventRecorder stationRecorder = new NetworkEventRecorder();
        station.addListener(stationRecorder);
        converter(station, 2).setVoltageRegulatorOn(false).setReactivePowerSetpoint(30.0);
        PowsyblException stationRefusal = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(station, stationRecorder.getEvents(), UnsupportedChangeBehavior.FAIL));
        assertTrue(stationRefusal.getMessage().contains("has no control flag"), stationRefusal.getMessage());

        Network detailed = readCgmesResources(detailedDcModel(), DC_DIR, "mixed_bipole_EQ.xml", "mixed_bipole_SSH.xml");
        NetworkEventRecorder detailedRecorder = new NetworkEventRecorder();
        detailed.addListener(detailedRecorder);
        detailed.getVoltageSourceConverter("VSC_1_2").getVoltageRegulation().setRegulating(false);
        PowsyblException detailedRefusal = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(detailed, detailedRecorder.getEvents(), UnsupportedChangeBehavior.FAIL));
        assertTrue(detailedRefusal.getMessage().contains("has no control flag"), detailedRefusal.getMessage());
        // The remedy (review 21 round 2, r2-m4)
        assertTrue(stationRefusal.getMessage().contains("Remedy: let it regulate"), stationRefusal.getMessage());

        // A power change of the line writes the blocks of both stations, so it is refused as well (r2-m2)
        Network line = readCgmesResources(HVDC_DIR, "hvdc_EQ.xml", "hvdc_SSH.xml");
        converter(line, 2).getVoltageRegulation().setRegulating(false);
        List<NetworkEvent> lineChange = RecordedChangeScenarios.record(line,
                n -> n.getHvdcLine("DCLineSegment-Vsc").setActivePowerSetpoint(300.0));
        PowsyblException lineRefusal = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(line, lineChange, UnsupportedChangeBehavior.FAIL));
        assertTrue(lineRefusal.getMessage().contains("has no control flag"), lineRefusal.getMessage());
    }

    /**
     * The local voltage target of a generator regulating voltage at a remote terminal is the target a load flow falls
     * back to when it switches to local control, and the SSH has no property for it: a change of it is refused on both
     * exports, not dropped (review 21 round 3, R3-M3).
     */
    @Test
    void theLocalVoltageTargetOfARemoteVoltageRegulationIsRejected() {
        Network sender = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = sender.getGenerator("SynchronousMachine");
        generator.getVoltageRegulation().setTerminal(sender.getGenerator("ExternalNetworkInjection").getTerminal(), 410.0);
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender,
                n -> n.getGenerator("SynchronousMachine").setLocalTargetV(399.0));

        PowsyblException partial = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, events, UnsupportedChangeBehavior.FAIL));
        assertTrue(partial.getMessage().contains("falls back to") && partial.getMessage().contains("Remedy: "),
                partial.getMessage());
        assertThrows(PowsyblException.class,
                () -> CgmesDiffExport.toDifferences(sender, events, new CgmesDiffExport.ExportOptions()));
    }

    /**
     * A change the steady state hypothesis does not represent writes nothing and is not listed among the exported
     * changes, as the difference is empty (review 21 round 3, R3-M3): the voltage target of a compensator regulating
     * reactive power.
     */
    @Test
    void aChangeTheSshDoesNotRepresentIsNotListedAsExported() {
        Network sender = readCgmesResources("/update/static-var-compensator/", "staticVarCompensator_EQ.xml",
                "staticVarCompensator_SSH.xml");
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender,
                n -> n.getStaticVarCompensator("StaticVarCompensator-Q").setLocalTargetV(401.0));
        assertEquals(1, events.size());

        List<NetworkEvent> exported = PartialSshExport.write(sender, events, new java.io.ByteArrayOutputStream(),
                new PartialSshExport.ExportOptions().setUnsupportedChangeBehavior(UnsupportedChangeBehavior.FAIL));
        assertEquals(List.of(), exported);
        CgmesDiffExport.Result difference = CgmesDiffExport.toDifferences(sender, events,
                new CgmesDiffExport.ExportOptions());
        assertTrue(difference.differences().models().isEmpty());
        assertEquals(List.of(), difference.exportedEvents());
    }

    /**
     * The voltage target of a station regulating reactive power is not represented in the steady state hypothesis:
     * the import rebuilds a reactive power regulation from {@code qPccControl} and its target, and keeps the local
     * voltage target it had, whatever {@code targetUpcc} says. A change of it alone is therefore not a change of the
     * SSH: nothing to refuse and nothing to carry, the difference is empty and a receiver keeps its own value, as for
     * the voltage target of a compensator regulating reactive power (pypowsybl review round 2, R2-6 b).
     */
    @Test
    void theVoltageTargetOfAStationRegulatingReactivePowerIsNotAChangeOfTheSsh() throws IOException {
        Consumer<Network> reactiveMode = network -> {
            VscConverterStation station = converter(network, 2);
            RecordedChangeScenarios.regulateOwnTerminal(station);
            station.getVoltageRegulation().setMode(RegulationMode.REACTIVE_POWER);
            station.getVoltageRegulation().setTargetValue(30.0);
        };
        Network sender = readCgmesResources(HVDC_DIR, "hvdc_EQ.xml", "hvdc_SSH.xml");
        reactiveMode.accept(sender);
        double before = converter(sender, 2).getLocalTargetV();
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender,
                n -> converter(n, 2).setLocalTargetV(before + 5.0));
        assertEquals(1, events.size());

        assertTrue(CgmesDiffExport.toDifferences(sender, events, new CgmesDiffExport.ExportOptions())
                .differences().models().isEmpty());
        // Nothing is written and nothing is listed as exported (review 21 round 3, R3-M3)
        assertEquals(List.of(), PartialSshExport.write(sender, events, new java.io.ByteArrayOutputStream(),
                new PartialSshExport.ExportOptions().setUnsupportedChangeBehavior(UnsupportedChangeBehavior.FAIL)));
    }

    /**
     * A station without VoltageRegulation cannot be described: the CGMES import gives every VsConverter one, from
     * {@code qPccControl}, so the receiver would gain a regulation the sender does not have. A power change of its line
     * is refused with the remedy (review 21 round 2, r2-m3).
     */
    @Test
    void vscStationWithoutVoltageRegulationIsRejected() {
        Network sender = readCgmesResources(HVDC_DIR, "hvdc_EQ.xml", "hvdc_SSH.xml");
        converter(sender, 2).removeVoltageRegulation();
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender,
                n -> n.getHvdcLine("DCLineSegment-Vsc").setActivePowerSetpoint(300.0));

        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, events, UnsupportedChangeBehavior.FAIL));
        assertTrue(exception.getMessage().contains("has no VoltageRegulation, but the CGMES update gives it one from its"
                + " VsConverter.qPccControl"), exception.getMessage());
        assertTrue(exception.getMessage().contains("Remedy: "), exception.getMessage());
    }

    /**
     * Open problem O1 of report 21, closed in round 3 (R3-M4): IIDM accepts reactive power regulation of a station only
     * with a regulating terminal. Regulating the station's own terminal (or none) is part of {@code qPccControl}, which
     * the CGMES import sets itself, so switching an imported station from voltage to reactive power regulation at its
     * own terminal is an exportable change; any other regulating terminal is refused with a remedy.
     */
    @Test
    void aVscStationSwitchesToReactivePowerAtItsOwnTerminal() throws IOException {
        RoundTripResult result = roundTrip(HVDC_DIR, sender -> {
            VscConverterStation station = converter(sender, 2);
            station.getVoltageRegulation().setTerminal(station.getTerminal(), station.getRegulatingTargetV());
            station.getVoltageRegulation().setMode(RegulationMode.REACTIVE_POWER);
            station.getVoltageRegulation().setTargetValue(30.0);
        }, "hvdc_EQ.xml", "hvdc_SSH.xml");
        assertTrue(result.sshXml().contains("VsQpccControlKind.reactivePcc"));
        VscConverterStation received = converter(result.receiver(), 2);
        assertEquals(RegulationMode.REACTIVE_POWER, received.getVoltageRegulation().getMode());
        assertEquals(30.0, received.getRegulatingTargetQ(), TOLERANCE);

        Network sender = readCgmesResources(HVDC_DIR, "hvdc_EQ.xml", "hvdc_SSH.xml");
        List<NetworkEvent> other = RecordedChangeScenarios.record(sender, n -> converter(n, 2).getVoltageRegulation()
                .setTerminal(converter(n, 1).getTerminal(), converter(n, 2).getRegulatingTargetV()));
        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, other, UnsupportedChangeBehavior.FAIL));
        assertTrue(exception.getMessage().contains("is not its own terminal") && exception.getMessage().contains("Remedy: "),
                exception.getMessage());
    }

    /**
     * A deprecated setter that has to create the VoltageRegulation reports the target under its deprecated name only
     * ({@code RatioTapChanger.setRegulationValue} on a tap changer without regulation). The echo repeats no canonical
     * event, so it is the sole carrier of the change: it is refused, with the remedy, instead of silently lost (review
     * 21 m4, rule 3 of EventCompactor).
     */
    @Test
    @SuppressWarnings("removal")
    void aTargetReportedOnlyUnderItsDeprecatedNameIsRejected() {
        Network sender = readCgmesResources(TRANSFORMER_DIR, "transformer_EQ.xml", "transformer_SSH.xml");
        RatioTapChanger ratioTapChanger = sender.getThreeWindingsTransformer("T3W").getLeg2().getRatioTapChanger();
        ratioTapChanger.removeVoltageRegulation();

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        ratioTapChanger.setRegulationValue(226.0);

        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL));
        assertTrue(exception.getMessage().contains(SOLE_ECHO), exception.getMessage());
    }

    /**
     * Open problem O2 of report 21: the change export writes the voltage regulation of ratio tap changers only; a
     * target of a ratio tap changer regulating reactive power is refused.
     */
    @Test
    void ratioTapChangerReactivePowerRegulationIsRejected() {
        Network sender = readCgmesResources(TRANSFORMER_DIR, "transformer_EQ.xml", "transformer_SSH.xml");
        VoltageRegulation regulation = sender.getThreeWindingsTransformer("T3W").getLeg2().getRatioTapChanger()
                .getVoltageRegulation();
        assertNotNull(regulation.getTerminal(), "the fixture is expected to regulate a remote terminal");
        regulation.setRegulating(false);
        regulation.setTargetValue(10.0);
        regulation.setMode(RegulationMode.REACTIVE_POWER);

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        regulation.setTargetValue(12.0);

        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL));
        assertTrue(exception.getMessage().contains("only writes the voltage regulation of ratio tap changers"),
                exception.getMessage());
    }

    /** Whether a converter controls the power at its connection point or the DC voltage is a steady state choice. */
    @Test
    void detailedConverterControlModeRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(detailedDcModel(), DC_DIR, sender -> {
            LineCommutatedConverter converter = sender.getLineCommutatedConverter("CSC_1_1");
            converter.setTargetVdc(500.0);
            converter.setControlMode(AcDcConverter.ControlMode.V_DC);
        }, "mixed_bipole_EQ.xml", "mixed_bipole_SSH.xml");

        LineCommutatedConverter expected = result.sender().getLineCommutatedConverter("CSC_1_1");
        LineCommutatedConverter actual = result.receiver().getLineCommutatedConverter("CSC_1_1");
        assertEquals(AcDcConverter.ControlMode.V_DC, expected.getControlMode());
        assertEquals(expected.getControlMode(), actual.getControlMode());
        assertEquals(expected.getTargetVdc(), actual.getTargetVdc(), TOLERANCE);
    }

    /**
     * The power factor of a line commutated converter of the detailed model is carried by its active and reactive
     * power, from which the import derives the factor back.
     */
    @Test
    void detailedLccPowerFactorRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(detailedDcModel(), DC_DIR,
                sender -> sender.getLineCommutatedConverter("CSC_1_1").setPowerFactor(0.92),
                "mixed_bipole_EQ.xml", "mixed_bipole_SSH.xml");

        assertEquals(0.92, result.sender().getLineCommutatedConverter("CSC_1_1").getPowerFactor(), 1e-6);
        assertEquals(result.sender().getLineCommutatedConverter("CSC_1_1").getPowerFactor(),
                result.receiver().getLineCommutatedConverter("CSC_1_1").getPowerFactor(), 1e-6);
    }

    private static Properties detailedDcModel() {
        Properties importParameters = new Properties();
        importParameters.put(CgmesImport.USE_DETAILED_DC_MODEL, "true");
        return importParameters;
    }

    /**
     * The tests above use small purpose-built models, one per equipment type. This one runs a single export over a
     * real CGMES conformity model, changing every element of every supported type at once, so that the exporter is
     * also exercised against the naming, the class variety and the regulating control layout of an actual grid
     * model rather than of a fixture.
     */
    @Test
    void conformityModelMultiEquipmentRoundTrip() throws IOException {
        RoundTripResult result = roundTrip(CgmesConformity3Catalog.microGridBaseCaseBE().dataSource(), sender -> {
            sender.getLoadStream().forEach(l -> l.setP0(l.getP0() + 1.0).setQ0(l.getQ0() + 1.0));
            sender.getGeneratorStream().forEach(g -> g.setTargetP(g.getTargetP() + 1.0).setTargetQ(g.getTargetQ() + 1.0));
            sender.getShuntCompensatorStream()
                    .filter(s -> s.getSectionCount() < s.getMaximumSectionCount())
                    .forEach(s -> s.setSectionCount(s.getSectionCount() + 1));
            sender.getStaticVarCompensatorStream()
                    .filter(s -> s.getVoltageRegulation().getMode() == RegulationMode.VOLTAGE)
                    .forEach(s -> s.setVoltageSetpoint(s.getVoltageSetpoint() + 1.0));
            sender.getTwoWindingsTransformerStream()
                    .filter(t -> t.hasRatioTapChanger() && canMoveUp(t.getRatioTapChanger()))
                    .forEach(t -> moveUp(t.getRatioTapChanger()));
            sender.getThreeWindingsTransformerStream()
                    .filter(t -> t.getLeg2().hasRatioTapChanger() && canMoveUp(t.getLeg2().getRatioTapChanger()))
                    .forEach(t -> moveUp(t.getLeg2().getRatioTapChanger()));
            sender.getTwoWindingsTransformerStream()
                    .filter(t -> t.hasPhaseTapChanger() && canMoveUp(t.getPhaseTapChanger()))
                    .forEach(t -> moveUp(t.getPhaseTapChanger()));
        });

        // Guard the coverage of this test: if the model or a filter above changes so that a category stops being
        // exercised, the loops below would silently assert nothing.
        Set<String> changedAttributes = result.events().stream()
                .filter(UpdateNetworkEvent.class::isInstance)
                .map(UpdateNetworkEvent.class::cast)
                .map(UpdateNetworkEvent::attribute)
                .collect(Collectors.toSet());
        // localTargetQ: the name the reactive target of a generator is reported under since powsybl-core #3699
        assertTrue(changedAttributes.containsAll(Set.of("p0", "q0", "targetP", "localTargetQ", "sectionCount",
                        "voltageSetpoint", "ratioTapChanger.tapPosition", "ratioTapChanger2.tapPosition",
                        "phaseTapChanger.tapPosition")),
                () -> "some equipment type was not exercised, changed attributes were " + changedAttributes);

        for (Load expected : result.sender().getLoads()) {
            assertLoadSetpoints(result, expected.getId());
        }
        for (Generator expected : result.sender().getGenerators()) {
            Generator actual = result.receiver().getGenerator(expected.getId());
            assertEquals(expected.getTargetP(), actual.getTargetP(), TOLERANCE, expected.getId());
            assertEquals(expected.getTargetQ(), actual.getTargetQ(), TOLERANCE, expected.getId());
        }
        for (ShuntCompensator expected : result.sender().getShuntCompensators()) {
            assertEquals(expected.getSectionCount(), result.receiver().getShuntCompensator(expected.getId()).getSectionCount(),
                    expected.getId());
        }
        for (StaticVarCompensator expected : result.sender().getStaticVarCompensators()) {
            assertStaticVarCompensatorSetpoints(result, expected.getId());
        }
        for (TwoWindingsTransformer expected : result.sender().getTwoWindingsTransformers()) {
            TwoWindingsTransformer actual = result.receiver().getTwoWindingsTransformer(expected.getId());
            if (expected.hasRatioTapChanger()) {
                assertEquals(expected.getRatioTapChanger().getTapPosition(), actual.getRatioTapChanger().getTapPosition(),
                        expected.getId());
            }
        }
        for (ThreeWindingsTransformer expected : result.sender().getThreeWindingsTransformers()) {
            ThreeWindingsTransformer actual = result.receiver().getThreeWindingsTransformer(expected.getId());
            if (expected.getLeg2().hasRatioTapChanger()) {
                assertEquals(expected.getLeg2().getRatioTapChanger().getTapPosition(),
                        actual.getLeg2().getRatioTapChanger().getTapPosition(), expected.getId());
            }
        }
        for (TwoWindingsTransformer expected : result.sender().getTwoWindingsTransformers()) {
            if (expected.hasPhaseTapChanger()) {
                assertEquals(expected.getPhaseTapChanger().getTapPosition(),
                        result.receiver().getTwoWindingsTransformer(expected.getId()).getPhaseTapChanger().getTapPosition(),
                        expected.getId());
            }
        }
    }

    private static boolean canMoveUp(TapChanger<?, ?, ?, ?> tapChanger) {
        return tapChanger.getTapPosition() < tapChanger.getHighTapPosition();
    }

    private static void moveUp(TapChanger<?, ?, ?, ?> tapChanger) {
        tapChanger.setTapPosition(tapChanger.getTapPosition() + 1);
    }

    // Every object is described exactly once, whatever the number of changes affecting it

    @Test
    void changesOnTheSameObjectAreMergedIntoOneDescription() {
        Network sender = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);

        sender.getGenerator("SynchronousMachine").setTargetP(165.0).setTargetQ(-5.0).setTargetV(410.0);

        String sshXml = PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL);
        assertEquals(1, countOccurrences(sshXml, "<cim:SynchronousMachine rdf:about="));
        assertEquals(1, countOccurrences(sshXml, "<cim:RegulatingControl rdf:about="));
    }

    /**
     * A RegulatingControl carries a single target whose meaning depends on the regulation mode. Writing one
     * description per changed setpoint would produce two contradictory targets for the same object.
     */
    @Test
    void bothStaticVarCompensatorSetpointsProduceOneConsistentRegulatingControl() {
        Network sender = readCgmesResources(STATIC_VAR_COMPENSATOR_DIR, "staticVarCompensator_EQ.xml", "staticVarCompensator_SSH.xml");
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);

        StaticVarCompensator svc = sender.getStaticVarCompensator("StaticVarCompensator-V");
        svc.setVoltageSetpoint(400.0);
        svc.setReactivePowerSetpoint(50.0);

        String sshXml = PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL);
        assertEquals(1, countOccurrences(sshXml, "<cim:RegulatingControl rdf:about="));
        // The compensator regulates voltage, so the exported target is the voltage setpoint, in kV
        assertTrue(sshXml.contains("<cim:RegulatingControl.targetValue>400</cim:RegulatingControl.targetValue>"));
        assertTrue(sshXml.contains("UnitMultiplier.k\"/>"));
        assertFalse(sshXml.contains("UnitMultiplier.M\"/>"));
    }

    // Changes that cannot be expressed in a Steady State Hypothesis file

    /**
     * The CGMES import creates fictitious switches to represent disconnected terminals. They have no master
     * resource identifier in the equipment model, so a receiver could not resolve a description of them.
     */
    @Test
    void changeOnAFictitiousSwitchIsRejected() {
        Network sender = readCgmesResources(SWITCH_DIR, "switch_EQ.xml", "switch_SSH.xml");
        Switch fictitiousSwitch = sender.getSwitchStream()
                .filter(sw -> sw.getProperty(Conversion.PROPERTY_IS_CREATED_FOR_DISCONNECTED_TERMINAL) != null)
                .findFirst().orElse(null);
        assertNotNull(fictitiousSwitch, "the test model is expected to contain a fictitious switch");

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        fictitiousSwitch.setOpen(!fictitiousSwitch.isOpen());

        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL));
        assertTrue(exception.getMessage().contains("no counterpart in the CGMES equipment model"));
    }

    /**
     * A partial file references a RegulatingControl the receiver already has, so equipment that has none in the
     * source model cannot carry a regulation change: a generated identifier would resolve to nothing there.
     */
    @Test
    void changeOnEquipmentWithoutARegulatingControlIsRejected() {
        Network sender = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = sender.getGenerator("SynchronousMachine");
        assertTrue(generator.removeProperty(Conversion.PROPERTY_REGULATING_CONTROL),
                "the test model is expected to give the generator a regulating control");

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        generator.setTargetV(410.0);

        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL));
        assertTrue(exception.getMessage().contains("has no CGMES regulating control"));
    }

    /**
     * Whether a change can be expressed at all is sometimes only known once part of it has been translated:
     * switching voltage regulation off writes the control enabled flag of the machine before it discovers that the
     * machine has no RegulatingControl to carry the target. A receiver must never see the one without the other.
     */
    @Test
    void anUnexportableChangeLeavesNothingBehind() {
        Network sender = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = sender.getGenerator("SynchronousMachine");
        generator.removeProperty(Conversion.PROPERTY_REGULATING_CONTROL);

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        generator.setVoltageRegulatorOn(false);

        String sshXml = PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.IGNORE);
        assertFalse(sshXml.contains("RegulatingCondEq.controlEnabled"));
        assertFalse(sshXml.contains("<cim:SynchronousMachine rdf:about="));
        assertFalse(sshXml.contains("<cim:RegulatingControl rdf:about="));
    }

    /**
     * Only the change that cannot be exported is dropped. A property it shares with a change that was exported
     * stays in the file, carrying the value the network currently has, as it would without the dropped change.
     */
    @Test
    void anUnexportableChangeDoesNotDropTheChangesAroundIt() {
        Network sender = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = sender.getGenerator("SynchronousMachine");
        generator.removeProperty(Conversion.PROPERTY_REGULATING_CONTROL);

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        generator.setTargetP(120.0);
        generator.setVoltageRegulatorOn(false);

        String sshXml = PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.IGNORE);
        assertTrue(sshXml.contains("<cim:RotatingMachine.p>-120</cim:RotatingMachine.p>"));
        assertTrue(sshXml.contains("RegulatingCondEq.controlEnabled"));
        assertFalse(sshXml.contains("<cim:RegulatingControl rdf:about="));
    }

    /**
     * The values written are read from the network as it currently stands, so a change recorded on another variant
     * would be exported with the values of the working one, describing a state that never existed.
     */
    @Test
    void variantMismatchIsRejected() {
        Network sender = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        sender.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "other");
        sender.getVariantManager().setWorkingVariant("other");

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        sender.getGenerator("SynchronousMachine").setTargetP(165.0);
        sender.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);

        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL));
        assertTrue(exception.getMessage().contains("was recorded on variant other"), exception.getMessage());
    }

    @Test
    void structuralChangeIsRejected() {
        Network sender = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);

        sender.getLoad("EnergyConsumer").remove();

        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL));
        assertFalse(exception.getMessage().isEmpty());
    }

    @Test
    void unsupportedChangesAreSkippedWhenIgnoring() {
        Network sender = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);

        sender.getLoad("EnergyConsumer").setP0(10.5).setQ0(5.5);
        sender.getLoad("EnergySource").remove();

        String sshXml = PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.IGNORE);
        assertTrue(sshXml.contains("<cim:EnergyConsumer rdf:about=\"#_EnergyConsumer\">"));
        assertFalse(sshXml.contains("EnergySource"));
    }

    /**
     * The export reports which changes reached the file. Under {@link UnsupportedChangeBehavior#IGNORE} that is a
     * strict subset of the recorded changes, holding the very objects that were passed in.
     */
    @Test
    void ignoredChangesAreLeftOutOfTheExportedChanges() {
        Network sender = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);

        sender.getLoad("EnergyConsumer").setP0(10.5).setQ0(5.5);
        sender.getLoad("EnergySource").remove();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        List<NetworkEvent> exportedEvents = PartialSshExport.write(sender, recorder.getEvents(), outputStream,
                new PartialSshExport.ExportOptions()
                        .setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE));

        List<NetworkEvent> compactedEvents = PartialSshExport.compactEvents(recorder.getEvents());
        assertTrue(compactedEvents.containsAll(exportedEvents));
        assertNotEquals(compactedEvents.size(), exportedEvents.size());
        assertTrue(exportedEvents.stream()
                .allMatch(event -> event instanceof UpdateNetworkEvent update && "EnergyConsumer".equals(update.id())));
    }

    /** Under {@link UnsupportedChangeBehavior#FAIL} nothing can be dropped, so the report is the compacted log. */
    @Test
    void everyCompactedChangeIsReportedAsExportedWhenFailing() throws IOException {
        Network sender = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);

        Generator generator = sender.getGenerator("SynchronousMachine");
        generator.setTargetP(100.0);
        generator.setTargetP(120.0);
        generator.setTargetV(410.0);

        List<NetworkEvent> exportedEvents = PartialSshExport.write(sender, recorder.getEvents(),
                tmpDir.resolve("manifest_SSH.xml"), UnsupportedChangeBehavior.FAIL);

        assertEquals(PartialSshExport.compactEvents(recorder.getEvents(), sender), exportedEvents);
        assertNotEquals(recorder.getEvents().size(), exportedEvents.size());
    }

    @Test
    void emptyChangeListProducesAHeaderOnly() {
        Network sender = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");

        String sshXml = PartialSshExport.toString(sender, List.of(), UnsupportedChangeBehavior.FAIL);
        assertTrue(sshXml.contains("</md:FullModel>"));
        assertFalse(sshXml.contains("rdf:about=\"#_"));
    }

    /**
     * A partial SSH references the SSH it replaces and the equipment model it applies to, and a merged network has
     * one of each per subnetwork. Exporting from the merged network would silently produce a header without them.
     */
    @Test
    void mergedNetworkIsRejected() {
        Network merged = Network.merge(Network.create("igm1", "test"), Network.create("igm2", "test"));

        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(merged, List.of(), UnsupportedChangeBehavior.FAIL));
        assertTrue(exception.getMessage().contains("subnetworks"));
    }

    // Compaction

    @Test
    void repeatedUpdatesAreCompactedWithoutChangingExportedXml() {
        Network sender = readCgmesResources(LOAD_DIR, "load_EQ.xml", "load_SSH.xml");
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);

        sender.getLoad("EnergyConsumer").setP0(10.5).setQ0(5.5).setP0(12.5).setQ0(6.5);

        List<NetworkEvent> compactedEvents = PartialSshExport.compactEvents(recorder.getEvents());

        assertEquals(2, compactedEvents.size());
        assertEquals(Set.of("p0", "q0"), compactedEvents.stream()
                .map(UpdateNetworkEvent.class::cast)
                .map(UpdateNetworkEvent::attribute)
                .collect(Collectors.toSet()));
        assertEquals(PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL),
                PartialSshExport.toString(sender, compactedEvents, UnsupportedChangeBehavior.FAIL));
    }

    /**
     * An attribute of an extension is compacted like any other attribute, under the name of its extension: two
     * extensions of the same object may well both call an attribute "enabled" without describing the same value.
     */
    @Test
    void repeatedExtensionUpdatesAreCompactedPerExtensionAndAttribute() {
        Network sender = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = sender.getGenerator("SynchronousMachine");
        generator.newExtension(ActivePowerControlAdder.class)
                .withParticipate(true).withDroop(4.0).withParticipationFactor(1.0).add();
        ReferencePriority.set(generator, 1);

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        ActivePowerControl<Generator> activePowerControl = generator.getExtension(ActivePowerControl.class);
        activePowerControl.setParticipationFactor(2.0);
        activePowerControl.setParticipationFactor(3.0);
        ReferencePriority.set(generator, 2);

        List<NetworkEvent> compactedEvents = PartialSshExport.compactEvents(recorder.getEvents());

        assertEquals(3, recorder.getEvents().size());
        // The two participation factors collapse, the priority of the other extension does not merge with them
        // (the second extension was the RemoteReactivePowerControl before powsybl-core #3699 removed it)
        assertEquals(2, compactedEvents.size());
        assertEquals(new ExtensionUpdateNetworkEvent("SynchronousMachine", ActivePowerControl.NAME,
                        "participationFactor", VariantManagerConstants.INITIAL_VARIANT_ID, 2.0, 3.0), compactedEvents.get(0));
        ExtensionUpdateNetworkEvent priority = (ExtensionUpdateNetworkEvent) compactedEvents.get(1);
        assertEquals("SynchronousMachine", priority.id());
        assertEquals("referencePriorities", priority.extensionName());
        assertEquals("referencePriority", priority.attribute());
    }

    /** The variant check applies to a change of an extension exactly as it applies to a change of the equipment. */
    @Test
    void extensionVariantMismatchIsRejected() {
        Network sender = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = sender.getGenerator("SynchronousMachine");
        generator.newExtension(ActivePowerControlAdder.class)
                .withParticipate(true).withDroop(4.0).withParticipationFactor(1.0).add();
        sender.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "other");
        sender.getVariantManager().setWorkingVariant("other");

        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);
        generator.getExtension(ActivePowerControl.class).setParticipationFactor(2.0);
        sender.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);

        PowsyblException exception = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL));
        assertTrue(exception.getMessage().contains("was recorded on variant other"), exception.getMessage());
    }

    // Header

    @Test
    void metadataDefaultsToNewModelVersionAndSupersedesSourceSsh() {
        Network sender = readCgmesResources(SWITCH_DIR, "switch_EQ.xml", "switch_SSH.xml");
        CgmesMetadataModel sourceSshModel = sourceSshModel(sender);
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);

        sender.getSwitch("Breaker").setOpen(true);

        String sshXml = PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL);

        assertNotEquals(sourceSshModel.getId(), extractFirstMatch(sshXml, FULL_MODEL_ID_PATTERN));
        assertEquals(Integer.toString(sourceSshModel.getVersion() + 1), extractFirstMatch(sshXml, MODEL_VERSION_PATTERN));
        assertTrue(sshXml.contains("Model.Supersedes rdf:resource=\"" + sourceSshModel.getId() + "\""));
        assertTrue(sshXml.contains("<md:Model.scenarioTime>"));
        assertTrue(sshXml.contains("<md:Model.created>"));
    }

    /**
     * A partial SSH is only applicable next to the equipment model it was derived from, so the dependencies of the
     * source SSH have to be carried over.
     */
    @Test
    void metadataKeepsDependenciesOfSourceSsh() {
        Network sender = readCgmesResources(GENERATOR_DIR, "generator_EQ.xml", "generator_SSH.xml");
        CgmesMetadataModel sourceSshModel = sourceSshModel(sender);
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);

        sender.getGenerator("SynchronousMachine").setTargetP(165.0);

        String sshXml = PartialSshExport.toString(sender, recorder.getEvents(), UnsupportedChangeBehavior.FAIL);
        for (String dependentOn : sourceSshModel.getDependentOn()) {
            assertTrue(sshXml.contains("Model.DependentOn rdf:resource=\"" + dependentOn + "\""),
                    "expected a dependency on " + dependentOn);
        }
    }

    @Test
    void metadataOptionsOverrideDefaultHeaderValues() {
        Network sender = readCgmesResources(SWITCH_DIR, "switch_EQ.xml", "switch_SSH.xml");
        CgmesMetadataModel sourceSshModel = sourceSshModel(sender);
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        sender.addListener(recorder);

        sender.getSwitch("Breaker").setOpen(true);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PartialSshExport.write(sender, recorder.getEvents(), outputStream,
                new PartialSshExport.ExportOptions()
                        .setModelId("urn:uuid:partial-ssh-test")
                        .setDescription("partial ssh update")
                        .setVersion(99)
                        .setModelingAuthoritySet("urn:mas:test")
                        .setScenarioTime(ZonedDateTime.parse("2026-03-01T10:15:00Z"))
                        .setCreated(ZonedDateTime.parse("2026-03-02T08:00:00Z"))
                        .clearDependencies()
                        .addDependentOn("urn:dependency:eq")
                        .setSupersedePreviousSshModel(false)
                        .addSupersedes("urn:supersedes:ssh"));
        String sshXml = outputStream.toString(StandardCharsets.UTF_8);

        assertEquals("urn:uuid:partial-ssh-test", extractFirstMatch(sshXml, FULL_MODEL_ID_PATTERN));
        assertEquals("99", extractFirstMatch(sshXml, MODEL_VERSION_PATTERN));
        assertTrue(sshXml.contains("<md:Model.description>partial ssh update</md:Model.description>"));
        assertTrue(sshXml.contains("<md:Model.modelingAuthoritySet>urn:mas:test</md:Model.modelingAuthoritySet>"));
        assertTrue(sshXml.contains("<md:Model.scenarioTime>2026-03-01T10:15:00Z</md:Model.scenarioTime>"), sshXml);
        assertTrue(sshXml.contains("<md:Model.created>2026-03-02T08:00:00Z</md:Model.created>"), sshXml);
        assertTrue(sshXml.contains("Model.DependentOn rdf:resource=\"urn:dependency:eq\""));
        assertTrue(sshXml.contains("Model.Supersedes rdf:resource=\"urn:supersedes:ssh\""));
        assertFalse(sshXml.contains("Model.Supersedes rdf:resource=\"" + sourceSshModel.getId() + "\""));
        for (String dependentOn : sourceSshModel.getDependentOn()) {
            assertFalse(sshXml.contains("Model.DependentOn rdf:resource=\"" + dependentOn + "\""));
        }
    }

    // Helpers

    private static CgmesMetadataModel sourceSshModel(Network network) {
        return network.getExtension(CgmesMetadataModels.class)
                .getModelForSubset(CgmesSubset.STEADY_STATE_HYPOTHESIS)
                .orElseThrow();
    }

    private static int countOccurrences(String text, String searched) {
        int count = 0;
        for (int index = text.indexOf(searched); index >= 0; index = text.indexOf(searched, index + 1)) {
            count++;
        }
        return count;
    }

    private static String extractFirstMatch(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private static void assertSwitchState(RoundTripResult result, String id) {
        assertEquals(result.sender().getSwitch(id).isOpen(), result.receiver().getSwitch(id).isOpen(), id);
    }

    private static void assertLoadSetpoints(RoundTripResult result, String id) {
        Load expected = result.sender().getLoad(id);
        Load actual = result.receiver().getLoad(id);
        assertEquals(expected.getP0(), actual.getP0(), TOLERANCE, id);
        assertEquals(expected.getQ0(), actual.getQ0(), TOLERANCE, id);
    }

    private static void assertGeneratorTargets(RoundTripResult result, String id) {
        Generator expected = result.sender().getGenerator(id);
        Generator actual = result.receiver().getGenerator(id);
        assertEquals(expected.getTargetP(), actual.getTargetP(), TOLERANCE, id);
        assertEquals(expected.getTargetQ(), actual.getTargetQ(), TOLERANCE, id);
        assertEquals(expected.getTargetV(), actual.getTargetV(), TOLERANCE, id);
    }

    private static void assertTapChangerRegulation(PhaseTapChanger expected, PhaseTapChanger actual, String message) {
        assertEquals(expected.isRegulating(), actual.isRegulating(), message);
        assertEquals(expected.getRegulationValue(), actual.getRegulationValue(), TOLERANCE, message);
        assertEquals(expected.getTargetDeadband(), actual.getTargetDeadband(), TOLERANCE, message);
    }

    private static void assertTapChangerRegulation(RatioTapChanger expected, RatioTapChanger actual, String message) {
        assertEquals(expected.isRegulating(), actual.isRegulating(), message);
        assertEquals(expected.getRegulationValue(), actual.getRegulationValue(), TOLERANCE, message);
        assertEquals(expected.getTargetDeadband(), actual.getTargetDeadband(), TOLERANCE, message);
    }

    private static void assertBoundaryLineOperatingValues(RoundTripResult result, String id) {
        BoundaryLine expected = result.sender().getBoundaryLine(id);
        BoundaryLine actual = result.receiver().getBoundaryLine(id);
        assertEquals(expected.getP0(), actual.getP0(), TOLERANCE, id);
        assertEquals(expected.getQ0(), actual.getQ0(), TOLERANCE, id);
        BoundaryLine.Generation expectedGeneration = expected.getGeneration();
        BoundaryLine.Generation actualGeneration = actual.getGeneration();
        assertEquals(expectedGeneration == null, actualGeneration == null, id);
        if (expectedGeneration != null) {
            assertEquals(expectedGeneration.getTargetP(), actualGeneration.getTargetP(), TOLERANCE, id);
            assertEquals(expectedGeneration.getTargetQ(), actualGeneration.getTargetQ(), TOLERANCE, id);
            assertEquals(expectedGeneration.getTargetV(), actualGeneration.getTargetV(), TOLERANCE, id);
            assertEquals(expectedGeneration.isVoltageRegulationOn(), actualGeneration.isVoltageRegulationOn(), id);
        }
    }

    private static void assertShuntRegulationState(RoundTripResult result, String id) {
        assertEquals(result.sender().getShuntCompensator(id).isVoltageRegulatorOn(),
                result.receiver().getShuntCompensator(id).isVoltageRegulatorOn(), id);
    }

    private static void assertShuntOperatingValues(RoundTripResult result, String id) {
        ShuntCompensator expected = result.sender().getShuntCompensator(id);
        ShuntCompensator actual = result.receiver().getShuntCompensator(id);
        assertEquals(expected.getSectionCount(), actual.getSectionCount(), id);
        assertEquals(expected.getTargetV(), actual.getTargetV(), TOLERANCE, id);
    }

    private static void assertStaticVarCompensatorRegulationState(RoundTripResult result, String id) {
        assertEquals(result.sender().getStaticVarCompensator(id).isRegulating(),
                result.receiver().getStaticVarCompensator(id).isRegulating(), id);
    }

    private static void assertStaticVarCompensatorSetpoints(RoundTripResult result, String id) {
        StaticVarCompensator expected = result.sender().getStaticVarCompensator(id);
        StaticVarCompensator actual = result.receiver().getStaticVarCompensator(id);
        assertEquals(expected.getVoltageSetpoint(), actual.getVoltageSetpoint(), TOLERANCE, id);
        assertEquals(expected.getReactivePowerSetpoint(), actual.getReactivePowerSetpoint(), TOLERANCE, id);
    }

    private static void assertHvdcSetpoint(RoundTripResult result, String id) {
        assertEquals(result.sender().getHvdcLine(id).getActivePowerSetpoint(),
                result.receiver().getHvdcLine(id).getActivePowerSetpoint(), TOLERANCE, id);
    }

    private static void assertVscVoltageSetpoint(RoundTripResult result, int side) {
        VscConverterStation expected = converter(result.sender(), side);
        VscConverterStation actual = converter(result.receiver(), side);
        assertEquals(expected.getVoltageSetpoint(), actual.getVoltageSetpoint(), TOLERANCE, "converter " + side);
    }

    private static VscConverterStation converter(Network network, int side) {
        HvdcLine line = network.getHvdcLine("DCLineSegment-Vsc");
        return (VscConverterStation) (side == 1 ? line.getConverterStation1() : line.getConverterStation2());
    }
}
