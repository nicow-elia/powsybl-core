/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conformity.Cgmes3Catalog;
import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.RegulatingControlMapping;
import com.powsybl.cgmes.conversion.export.elements.LoadingLimitEq;
import com.powsybl.cgmes.conversion.naming.CgmesObjectReference;
import com.powsybl.cgmes.conversion.naming.NamingStrategy;
import com.powsybl.cgmes.conversion.test.ConversionUtil;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios;
import com.powsybl.cgmes.extensions.CgmesTapChanger;
import com.powsybl.cgmes.extensions.CgmesTapChangers;
import com.powsybl.cgmes.extensions.CimCharacteristics;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import com.powsybl.commons.util.Result;
import com.powsybl.commons.xml.XmlUtil;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.events.ExtensionUpdateNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import com.powsybl.iidm.network.extensions.RemoteReactivePowerControl;
import com.powsybl.iidm.network.extensions.RemoteReactivePowerControlAdder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import static com.powsybl.cgmes.conversion.naming.CgmesObjectReference.ref;
import static com.powsybl.cgmes.model.CgmesNamespace.RDF_NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The full steady state hypothesis export and the change mapping shared by the partial SSH, the difference model, the
 * database and the object dump exports have to say the same thing about every object both of them describe.
 *
 * <p>The two are separate implementations of one piece of knowledge: {@link SteadyStateHypothesisExport} writes the
 * state of the whole network, {@link CgmesChangeTranslator} (with {@link CgmesChangeRegulatingControls}) describes
 * the objects a change touches. They share their helpers but write the property by property mapping twice. This test
 * asserts that the two copies agree, fixture by fixture:</p>
 * <ol>
 *     <li>the full SSH export is written into memory and read back into {@code (subject, property) -> value};</li>
 *     <li>the shared mapping is asked, in the live state of the same network, about every attribute of every object
 *     it can describe, which covers every consistency group it writes. This is the synthetic full-object request
 *     {@link CgmesObjectDump} makes, without its cache;</li>
 *     <li>every {@code (subject, property)} of either side is compared, numeric literals by value as
 *     {@code TripleDiffCalculator.comparable} does, and ends up in exactly one of: equal,
 *     {@link #ONLY_IN_FULL_EXPORT}, {@link #DELIBERATE_DIFFERENCES}, or unexplained, which fails the test. The class
 *     of a subject takes part in the comparison as an {@code rdf:type} row.</li>
 * </ol>
 *
 * <p>The same is done for the equipment values the mapping shares with {@link EquipmentExport}: branch impedances,
 * voltage level limits and loading limit values (CGMES 2.4.15 in EQ; CGMES 3 as {@code .value} in SSH against the EQ
 * {@code normalValue}). Only those properties of the full equipment export are compared, because the mapping writes
 * no other equipment data. Loading limits are matched by their IIDM slot (owner, side, group, type, acceptable
 * duration), because the full equipment export re-mints the identifier of every {@code OperationalLimit}.</p>
 *
 * <p>The fixtures are every base model of {@link RecordedChangeScenarios}, the state after each of its changes, and a
 * set of conformity models (CGMES 2.4.15 bus-branch and node-breaker, HVDC, CGMES 3). A rule of the two tables that no
 * fixture exercises fails the test as well, so that the tables cannot rot.</p>
 *
 * <p>Limits of the guard: the helpers both sides call ({@code regulatingControlView}, {@code computeConverterState},
 * {@code vscTargetQpcc}, {@code obtainOperatingMode}, ...) are outside it by construction, a wrong sign there changes
 * both sides alike. The probe list of this test is a copy of the attribute names the translator matches on. Two slots
 * of the same loading limits holding the same value cannot be told apart.</p>
 *
 * <p>Set the system property {@code equivalence.report} to a file path to get the per-fixture counts written
 * there.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class ExportMappingEquivalenceTest {

    private static final String RDF_TYPE = CgmesStatement.RDF_TYPE;
    private static final Set<String> LIMIT_CLASSES = Set.of("CurrentLimit", "ActivePowerLimit", "ApparentPowerLimit");
    private static final String VOLTAGE_LIMIT = "VoltageLimit";
    private static final Set<String> BRANCH_CLASSES = Set.of(CgmesNames.AC_LINE_SEGMENT, CgmesNames.EQUIVALENT_BRANCH,
            CgmesNames.SERIES_COMPENSATOR);
    private static final String ACDC_TERMINAL_CONNECTED = "ACDCTerminal.connected";
    private static final String CONTROL_ENABLED = "RegulatingCondEq.controlEnabled";
    private static final String SSH = "SSH";
    private static final String EQ = "EQ";
    private static final String EQUAL = "EQUAL";
    private static final String ONLY_IN_FULL_EXPORT_TABLE = "ONLY_IN_FULL_EXPORT";
    private static final String DELIBERATE_DIFFERENCES_TABLE = "DELIBERATE_DIFFERENCES";
    private static final String DOCUMENTED = "DOCUMENTED";
    private static final String FULL_EXPORT_DEFECT = "FULL_EXPORT_DEFECT";
    private static final String CODE_REFERENCE = "CODE_REFERENCE";

    // ---------------------------------------------------------------------------------------------------------------
    // The tables
    // ---------------------------------------------------------------------------------------------------------------

    /** What a rule sees of one row: the two statements, either of which may be absent, and what the fixture knows. */
    private record Row(Key key, Triple full, Triple shared, Facts facts) {
        String className() {
            return full != null ? full.className() : shared.className();
        }

        boolean both() {
            return full != null && shared != null;
        }

        double fullNumber() {
            return Double.parseDouble(full.value());
        }

        double sharedNumber() {
            return Double.parseDouble(shared.value());
        }
    }

    @FunctionalInterface
    private interface RowPredicate {
        boolean test(Row row);
    }

    /**
     * A property the full export writes and the shared mapping does not produce.
     *
     * @param classNames the classes of the subject in the full export
     * @param properties the properties concerned, empty for every property of such a subject (its rdf:type included)
     * @param when       the narrower condition
     */
    private record OnlyInFull(String id, Set<String> classNames, Set<String> properties, RowPredicate when, String reason) {
        boolean matches(Row row) {
            return row.full() != null && row.shared() == null && classNames.contains(row.className())
                    && (properties.isEmpty() || properties.contains(row.key().property())) && when.test(row);
        }
    }

    /**
     * A known deviation between the two.
     *
     * @param basis     {@link #DOCUMENTED} for a deviation the documentation states, {@link #CODE_REFERENCE} for one
     *                  that only the code of the full export states, {@link #FULL_EXPORT_DEFECT} for a row where the
     *                  pre-existing full export is wrong, the evidence being how the import reads the property back.
     *                  Such a row is a finding against the full export, which this test does not change. Wherever the
     *                  two sides disagree, the predicate checks the shared value against a value the test derives
     *                  from the IIDM object itself, since this test is the only check of the shared value there
     * @param reference where it is documented, or the evidence
     */
    private record Deliberate(String id, String basis, RowPredicate when, String reference) {
    }

    /** The probes whose refusal explains a missing equipment value, by the property the full equipment export writes. */
    private static final Map<String, Set<String>> EQ_PROBES = Map.of(
            "ACLineSegment.r", Set.of("r"), "ACLineSegment.x", Set.of("x"),
            "ACLineSegment.gch", Set.of("g1", "g"), "ACLineSegment.bch", Set.of("b1", "b"),
            "VoltageLevel.highVoltageLimit", Set.of("highVoltageLimit"),
            "VoltageLevel.lowVoltageLimit", Set.of("lowVoltageLimit"));

    /** The refusal reasons that explain a missing impedance, as the shared mapping words them. */
    private static final List<String> IMPEDANCE_REFUSALS = List.of(
            "is represented as a switch in CGMES or is not a CGMES branch",
            "has no shunt admittance in CGMES",
            "is not a single CGMES master resource identifier",
            "the import transforms EquivalentBranch parameters between nominal voltages",
            "a zero-impedance branch inside one voltage level becomes a switch on import",
            "g1 == g2 and b1 == b2 are required");

    /** The refusal reasons that explain a missing voltage level limit, as the shared mapping words them. */
    private static final List<String> VOLTAGE_LIMIT_REFUSALS = List.of(
            "the receiver only accepts voltage limits strictly inside the VoltageLevel range",
            "no VoltageLimit object binds it",
            "merged voltage levels have several CGMES VoltageLevel objects");

    private static final List<OnlyInFull> ONLY_IN_FULL_EXPORT = List.of(
        new OnlyInFull("TERMINAL_CONNECTED", Set.of(CgmesNames.TERMINAL), Set.of(),
            row -> !row.facts().branchSwitchTerminals().contains(row.key().subject()),
            "COVERAGE GAP, not an impossibility: IIDM holds Terminal.isConnected() and the full export maps it one to"
                + " one, but the connection status of a terminal is not a supported change (docs: partial SSH export,"
                + " supported changes); the shared mapping writes ACDCTerminal.connected only for the two terminals of a"
                + " switch imported from a CGMES branch class"),
        new OnlyInFull("DC_TERMINAL_CONNECTED", Set.of(CgmesNames.DC_TERMINAL), Set.of(),
            row -> !row.facts().dcSwitchTerminals().contains(row.key().subject()),
            "COVERAGE GAP: the connection status of a DC terminal (DcTerminal.isConnected()) is only mapped for the two"
                + " terminals of a DcSwitch"),
        new OnlyInFull("ACDC_CONVERTER_DC_TERMINAL_CONNECTED", Set.of("ACDCConverterDCTerminal"), Set.of(), row -> true,
            "COVERAGE GAP: a converter DC terminal has no mapped IIDM change; the full export writes it always"
                + " connected (simplified model), or from DcTerminal.isConnected() (detailed model)"),
        new OnlyInFull("CONTROL_AREA", Set.of("ControlArea"), Set.of(), row -> true,
            "Area.interchangeTarget and the pTolerance property of an area are not mapped changes"),
        new OnlyInFull("CS_CONVERTER_CONSTANTS", Set.of(CgmesNames.CS_CONVERTER),
            Set.of("CsConverter.targetAlpha", "CsConverter.targetGamma", "CsConverter.targetIdc"), row -> true,
            "constant 0 written by the full export; IIDM holds no such attribute"),
        new OnlyInFull("VS_CONVERTER_CONSTANTS", Set.of(CgmesNames.VS_CONVERTER),
            Set.of("VsConverter.droop", "VsConverter.droopCompensation", "VsConverter.qShare"), row -> true,
            "constant 0 written by the full export; IIDM holds no such attribute"),
        new OnlyInFull("GENERATED_EQUIVALENT_INJECTION", Set.of(CgmesNames.EQUIVALENT_INJECTION), Set.of(),
            row -> row.facts().generatedEquivalentInjections().contains(row.key().subject()),
            "the boundary line carries no CGMES.EquivalentInjection, so the full export writes one under a generated"
                + " identifier; the shared mapping only describes objects the receiver already holds"),
        new OnlyInFull("UNUSABLE_TARGET_OMITTED", Set.of(CgmesNames.EQUIVALENT_INJECTION),
            Set.of("EquivalentInjection.regulationTarget"),
            row -> row.fullNumber() == 0 && row.facts().expectedOmissions().contains(row.key()),
            "the shared mapping writes a regulation target only when it is a usable voltage (> 0,"
                + " CgmesChangeTranslator.equivalentInjectionBlock); the full export writes 0 for none or NaN. Only"
                + " where the IIDM target really is not a usable voltage"),
        new OnlyInFull("VSC_UNUSABLE_SETPOINT_OMITTED", Set.of(CgmesNames.VS_CONVERTER),
            Set.of("VsConverter.targetUpcc", "VsConverter.targetQpcc"),
            row -> row.fullNumber() == 0 && row.facts().expectedOmissions().contains(row.key()),
            "the shared mapping writes targetUpcc only when > 0 (converter station) or finite (detailed model) and"
                + " targetQpcc only when finite; the full export writes a NaN as 0 (CgmesExportUtil.format). Only where"
                + " the IIDM setpoint really is such a value"),
        new OnlyInFull("SWITCH_OPEN_ON_BRANCH_CLASS", BRANCH_CLASSES, Set.of("Switch.open", RDF_TYPE),
            row -> row.facts().branchSwitches().contains(row.key().subject()),
            "the full export writes Switch.open on the element of a switch imported from a CGMES branch class, which"
                + " has no such property; the shared mapping writes the two terminals instead, see"
                + " BRANCH_SWITCH_TERMINALS"),
        new OnlyInFull("REACTIVE_POWER_CONTROL_REGULATING_VOLTAGE", Set.of("RegulatingControl"), Set.of(),
            row -> row.facts().reactivePowerControlsRegulatingVoltage().contains(row.key().subject()),
            "the generator regulates voltage through a control whose CGMES mode is reactive power: the shared mapping"
                + " refuses it (the receiver would read the voltage target as a reactive power), the full export writes"
                + " the voltage target with multiplier k, which an SSH-only exchange delivers wrongly (finding)"),
        new OnlyInFull("CURRENT_LIMITER_OPERATIONAL_LIMIT", LIMIT_CLASSES, Set.of(),
            row -> row.facts().currentLimiterLimits().contains(row.key().subject()),
            "the full equipment export writes the regulation value of a phase tap changer limiting current as a"
                + " CurrentLimit of its regulated terminal (EquipmentExport, current limiter mode); the shared mapping"
                + " carries that value on the TapChangerControl"),
        new OnlyInFull("IMPEDANCE_REFUSED", Set.of(CgmesNames.AC_LINE_SEGMENT), Set.of(),
            row -> row.facts().refused(row.key().subject(), EQ_PROBES.get(row.key().property()), IMPEDANCE_REFUSALS),
            "the shared mapping refuses the probe of exactly this impedance for one of the reasons of"
                + " IMPEDANCE_REFUSALS (branch imported as a switch, no shunt admittance in the class, merged identifier,"
                + " EquivalentBranch between nominal voltages, zero impedance inside a voltage level, asymmetric shunt"
                + " admittance): docs, Difference model export / Not supported"),
        new OnlyInFull("NEGATIVE_IMPEDANCE_REFUSED", Set.of(CgmesNames.AC_LINE_SEGMENT), Set.of(),
            row -> row.facts().refused(row.key().subject(), EQ_PROBES.get(row.key().property()),
                List.of("impedance values must be finite (r, x >= 0)")),
            "the shared mapping refuses a negative r or x (CgmesChangeTranslator.seriesImpedanceProblem), which a"
                + " capacitive SeriesCompensator has; NOT documented in export.md, reported as a finding (the mapping is"
                + " more restrictive than the import)"),
        new OnlyInFull("VOLTAGE_LIMIT_REFUSED", Set.of(CgmesNames.VOLTAGE_LEVEL), Set.of(),
            row -> row.facts().refused(row.key().subject(), EQ_PROBES.get(row.key().property()), VOLTAGE_LIMIT_REFUSALS),
            "the shared mapping refuses the probe of exactly this voltage limit for one of the reasons of"
                + " VOLTAGE_LIMIT_REFUSALS (outside the declared range, not bound by a VoltageLimit object, merged voltage"
                + " levels): docs, Difference model export / Not supported"),
        new OnlyInFull("SYNTHESIZED_LIMIT_REFUSED", LIMIT_CLASSES, Set.of(),
            row -> row.facts().refusedSlots().contains(row.key().subject()),
            "the loading limit of a network imported from CGMES carries no stored CGMES OperationalLimit identifier:"
                + " the import synthesized it, there is no CGMES object to write it on (docs: Difference model export /"
                + " Equipment changes and their limits). A limit the mapping drops although it has an identifier stays"
                + " unexplained")
    );

    private static final List<Deliberate> DELIBERATE_DIFFERENCES = List.of(
        new Deliberate("GENERATOR_CONTROL_ENABLED_REACTIVE_MODE", DOCUMENTED,
            row -> row.both() && CONTROL_ENABLED.equals(row.key().property())
                && row.facts().reactivePowerModeMachines().containsKey(row.key().subject())
                && row.facts().reactivePowerModeMachines().get(row.key().subject())
                    .equals(row.full().value() + "|" + row.shared().value()),
            "docs/grid_exchange_formats/cgmes/export.md, Partial SSH export / Regulating controls, second bullet"),
        new Deliberate("CURRENT_LIMITER_REAL_VALUES", DOCUMENTED,
            row -> row.both() && row.facts().currentLimiterControls().contains(row.key().subject())
                && Set.of("RegulatingControl.enabled", "RegulatingControl.targetDeadband", "RegulatingControl.targetValue")
                    .contains(row.key().property())
                && ("false".equals(row.full().value()) || row.fullNumber() == 0) && row.facts().sharedAsExpected(row),
            "docs/grid_exchange_formats/cgmes/export.md, Partial SSH export / Regulating controls, first bullet"),
        new Deliberate("BRANCH_SWITCH_TERMINALS", FULL_EXPORT_DEFECT,
            row -> row.both() && ACDC_TERMINAL_CONNECTED.equals(row.key().property())
                && row.facts().branchSwitchTerminals().contains(row.key().subject())
                && "true".equals(row.full().value()) && row.facts().sharedAsExpected(row),
            "the full export writes the terminals of a switch imported from a CGMES branch class always connected;"
                + " the CGMES update derives the state of that switch from them (AbstractBranchConversion.update)"),
        new Deliberate("BOUNDARY_GENERATION_OMITTED", FULL_EXPORT_DEFECT,
            row -> row.both() && row.facts().boundaryInjections().containsKey(row.key().subject())
                && Set.of("EquivalentInjection.p", "EquivalentInjection.q").contains(row.key().property())
                && row.fullNumber() == row.facts().boundaryInjections().get(row.key().subject()).p0OrQ0(row.key().property())
                && row.facts().sharedAsExpected(row),
            "the full export writes EquivalentInjection.p/q = p0/q0 of the boundary line and ignores its Generation,"
                + " into which the import puts the whole injection (EquivalentInjectionConversion.update)"),
        new Deliberate("DETAILED_LCC_POWER_FACTOR", FULL_EXPORT_DEFECT,
            row -> row.both() && row.facts().detailedLccs().contains(row.key().subject())
                && Set.of("ACDCConverter.p", "ACDCConverter.q").contains(row.key().property())
                && row.facts().sharedAsExpected(row),
            "the full export writes ACDCConverter.p/q of a detailed LineCommutatedConverter from the flow of its PCC"
                + " terminal, which the import reads back as the power factor (AcDcConverterConversion.updatePowerFactor)"),
        new Deliberate("LIMIT_BY_SLOT", DOCUMENTED,
            row -> row.both() && LIMIT_CLASSES.contains(row.className()) && row.fullNumber() == row.sharedNumber(),
            "equal value of the same IIDM limit under another identifier or property: the full equipment export"
                + " re-mints OperationalLimit identifiers (EquipmentExport.operationalLimitId) and CGMES 3 exchanges the"
                + " value as .value in SSH while EQ holds normalValue (docs: Difference model export / Equipment"
                + " changes and their limits, first and third bullet)"),
        new Deliberate("VOLTAGE_LIMIT_OBJECTS", DOCUMENTED,
            row -> row.both() && VOLTAGE_LIMIT.equals(row.shared().className()) && row.fullNumber() == row.sharedNumber(),
            "voltage level built from VoltageLimit objects: the shared mapping writes VoltageLimit.value of every"
                + " stored identifier, the full equipment export VoltageLevel.high/lowVoltageLimit (docs: Difference"
                + " model export / Supported changes per profile)"),
        new Deliberate("REMODELLED_BRANCH_CLASS", DOCUMENTED,
            row -> row.both() && !row.full().className().equals(row.shared().className())
                && BRANCH_CLASSES.contains(row.shared().className()) && row.fullNumber() == row.sharedNumber(),
            "the full equipment export writes every IIDM line as an ACLineSegment, the shared mapping names the class"
                + " the receiver holds (docs: Difference model export / Supported changes per profile, Line r, x)"),
        new Deliberate("EQUIVALENT_BRANCH_REVERSE_IMPEDANCE", DOCUMENTED,
            row -> row.full() == null && row.shared() != null
                && row.key().property().matches("EquivalentBranch\\.[rx]21") && row.facts().sharedAsExpected(row),
            "docs: Difference model export / Equipment changes and their limits, EquivalentBranch bullet"),
        new Deliberate("ZERO_GCH_NOT_WRITTEN", CODE_REFERENCE,
            row -> row.full() == null && row.shared() != null && "ACLineSegment.gch".equals(row.key().property())
                && row.sharedNumber() == 0,
            "the full equipment export leaves out a gch of zero (AcLineSegmentEq.write), which the import reads as 0"),
        new Deliberate("LOSSLESS_IMPEDANCE_FORMAT", DOCUMENTED,
            row -> row.both() && BRANCH_CLASSES.contains(row.shared().className())
                && row.fullNumber() == Double.parseDouble(CgmesExportUtil.format(row.sharedNumber())),
            "docs: Difference model export / Equipment changes and their limits, lossless formatter bullet")
    );

    // ---------------------------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------------------------

    record Fixture(String name, Supplier<Network> loader) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Fixture> fixtures() {
        Map<String, Fixture> fixtures = new LinkedHashMap<>();
        for (RecordedChangeScenarios.Scenario scenario : RecordedChangeScenarios.allChanges()) {
            String base = scenario.dir() + String.join(",", scenario.files())
                    + (scenario.importParams().isEmpty() ? "" : " " + scenario.importParams());
            fixtures.putIfAbsent(base, new Fixture(base,
                () -> ConversionUtil.readCgmesResources(scenario.importParams(), scenario.dir(), scenario.files())));
            add(fixtures, "after " + scenario.name(), () -> {
                Network network = scenario.load();
                scenario.forwardChange().accept(network);
                return network;
            });
        }
        add(fixtures, "MicroGrid BE (CGMES 2.4.15)",
            () -> Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource()));
        add(fixtures, "MicroGrid BE (CGMES 2.4.15, active power control extension)",
            () -> Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource(),
                importParameters(CgmesImport.CREATE_ACTIVE_POWER_CONTROL_EXTENSION)));
        add(fixtures, "MicroGrid NL (CGMES 2.4.15)",
            () -> Network.read(CgmesConformity1Catalog.microGridBaseCaseNL().dataSource()));
        add(fixtures, "MiniGrid node-breaker (CGMES 2.4.15)",
            () -> Network.read(CgmesConformity1Catalog.miniNodeBreaker().dataSource()));
        add(fixtures, "SmallGrid node-breaker HVDC (CGMES 2.4.15)",
            () -> Network.read(CgmesConformity1Catalog.smallNodeBreakerHvdc().dataSource()));
        // The assembled network carries its CIM characteristics on the subnetworks only, so it is exported as CIM16
        add(fixtures, "MicroGrid assembled (CGMES 3 files)", () -> Network.read(Cgmes3Catalog.microGrid().dataSource()));
        add(fixtures, "SmallGrid (CGMES 3)", () -> Network.read(Cgmes3Catalog.smallGrid().dataSource()));
        add(fixtures, "Svedala (CGMES 3)", () -> Network.read(Cgmes3Catalog.svedala().dataSource()));
        // States that exercise the documented deviations, set up as the partial SSH export tests set them up
        add(fixtures, "generator regulating reactive power", () -> reactivePowerGenerator(false));
        add(fixtures, "generator of a reactive power control, switched to voltage regulation", () -> reactivePowerGenerator(true));
        add(fixtures, "phase tap changer limiting current", () -> {
            Network network = ConversionUtil.readCgmesResources("/update/transformer/", "transformer_EQ.xml", "transformer_SSH.xml");
            network.getTwoWindingsTransformer("T2W").getPhaseTapChanger().setRegulating(false)
                    .setRegulationMode(PhaseTapChanger.RegulationMode.CURRENT_LIMITER)
                    .setRegulationValue(800.0)
                    .setTargetDeadband(10.0);
            return network;
        });
        add(fixtures, "line susceptance below the resolution of the shared formatter", () -> {
            Network network = ConversionUtil.readCgmesResources("/update/line/", "line_EQ.xml", "line_SSH.xml");
            network.getLine("ACLineSegment").setB1(5e-16).setB2(5e-16);
            return network;
        });
        add(fixtures, "operational limit set without a CGMES permanent limit",
            () -> ConversionUtil.readCgmesResources("/issues/operational-limits/", "missing_limits.xml"));
        return List.copyOf(fixtures.values());
    }

    /** The generator of the generator fixture, given a CGMES control regulating reactive power as its import would. */
    private static Network reactivePowerGenerator(boolean voltageRegulatorOn) {
        Network network = ConversionUtil.readCgmesResources("/update/generator/", "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = network.getGenerator("SynchronousMachine");
        generator.setProperty(Conversion.PROPERTY_MODE, "RegulatingControlModeKind.reactivePower");
        generator.setProperty(CgmesExportUtil.getTerminalSignPropertyName(""), "-1");
        generator.newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(25.0)
                .withRegulatingTerminal(generator.getTerminal())
                .withEnabled(!voltageRegulatorOn)
                .add();
        generator.setVoltageRegulatorOn(voltageRegulatorOn);
        return network;
    }

    private static Properties importParameters(String name) {
        Properties parameters = new Properties();
        parameters.put(name, "true");
        return parameters;
    }

    private static void add(Map<String, Fixture> fixtures, String name, Supplier<Network> loader) {
        fixtures.put(name, new Fixture(name, loader));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The test
    // ---------------------------------------------------------------------------------------------------------------

    private static final Map<String, Map<String, Integer>> COUNTS = Collections.synchronizedMap(new LinkedHashMap<>());
    private static final Map<String, Integer> RULE_HITS = Collections.synchronizedMap(new TreeMap<>());
    private static final List<String> EXAMPLES = Collections.synchronizedList(new ArrayList<>());

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void fullExportAndSharedMappingAgree(Fixture fixture) {
        Network network = fixture.loader().get();
        CgmesExportContext context = new CgmesExportContext(network);
        String cimNamespace = context.getCim().getNamespace();
        Shared shared = shared(network, context);
        assertEquals(List.of(), shared.conflicts(), "two probes of the shared mapping disagree about one property");
        Facts facts = Facts.of(network, context, shared);

        Map<String, Integer> counts = new TreeMap<>();
        List<String> unexplained = new ArrayList<>();

        // Steady state hypothesis
        compare(SSH, parse(fullSsh(network), cimNamespace), shared.ssh(), facts, fixture, counts, unexplained);

        // Equipment values
        CgmesExportContext eqContext = new CgmesExportContext(network).setExportEquipment(true);
        EqSides eq = eqSides(network, context, eqContext, parse(fullEq(network, eqContext), cimNamespace), shared, facts);
        compare(EQ, eq.full(), eq.shared(), facts, fixture, counts, unexplained);

        COUNTS.put(fixture.name() + " [CIM" + context.getCimVersion() + "]", counts);
        assertTrue(unexplained.isEmpty(), () -> "Unexplained differences between the full export and the shared"
                + " mapping in " + fixture.name() + ":\n" + String.join("\n", unexplained));
    }

    private static void compare(String part, Map<Key, Triple> full, Map<Key, Triple> shared, Facts facts, Fixture fixture,
                                Map<String, Integer> counts, List<String> unexplained) {
        Set<Key> keys = new LinkedHashSet<>(full.keySet());
        keys.addAll(shared.keySet());
        for (Key key : keys) {
            Row row = new Row(key, full.get(key), shared.get(key), facts);
            String outcome = classify(row);
            String line = fixture.name() + ": " + part + " " + key + " full=" + describe(row.full())
                    + " shared=" + describe(row.shared());
            if (outcome == null) {
                List<String> refusals = facts.refusals().getOrDefault(key.subject(), List.of());
                unexplained.add(refusals.isEmpty() ? line : line + " refusals=" + refusals);
                outcome = "UNEXPLAINED";
            } else if (!EQUAL.equals(outcome) && !outcome.contains("TERMINAL") && !RDF_TYPE.equals(key.property())) {
                EXAMPLES.add(outcome + " | " + line);
            }
            counts.merge(part + " " + outcome, 1, Integer::sum);
        }
    }

    /** The table a row belongs to, or {@code null} when it is an unexplained difference. */
    private static String classify(Row row) {
        if (row.both() && row.full().subject().equals(row.shared().subject())
                && row.full().property().equals(row.shared().property())
                && row.full().className().equals(row.shared().className())
                && comparable(row.full()).equals(comparable(row.shared()))) {
            return EQUAL;
        }
        for (Deliberate deliberate : DELIBERATE_DIFFERENCES) {
            if (deliberate.when().test(row)) {
                RULE_HITS.merge(deliberate.id(), 1, Integer::sum);
                return DELIBERATE_DIFFERENCES_TABLE + " " + deliberate.basis() + " " + deliberate.id();
            }
        }
        for (OnlyInFull onlyInFull : ONLY_IN_FULL_EXPORT) {
            if (onlyInFull.matches(row)) {
                RULE_HITS.merge(onlyInFull.id(), 1, Integer::sum);
                return ONLY_IN_FULL_EXPORT_TABLE + " " + onlyInFull.id();
            }
        }
        return null;
    }

    private static String describe(Triple triple) {
        return triple == null ? "-" : triple.subject() + " " + triple.className() + "." + triple.property() + "=" + triple.value();
    }

    @AfterAll
    static void everyRuleIsExercised() throws IOException {
        String path = System.getProperty("equivalence.report");
        if (path != null) {
            StringBuilder report = new StringBuilder();
            COUNTS.forEach((fixture, counts) -> {
                report.append("== ").append(fixture).append('\n');
                counts.forEach((outcome, count) -> report.append("   ").append(count).append(' ').append(outcome).append('\n'));
            });
            report.append("== rule hits\n");
            RULE_HITS.forEach((rule, count) -> report.append("   ").append(count).append(' ').append(rule).append('\n'));
            report.append("== rows other than equal, terminals and types\n");
            EXAMPLES.forEach(example -> report.append("   ").append(example).append('\n'));
            Files.writeString(Path.of(path), report.toString());
        }
        if (COUNTS.size() == fixtures().size()) {
            // Only when the whole class ran: a rule no fixture exercises is a rule nobody tests
            Set<String> rules = new TreeSet<>();
            ONLY_IN_FULL_EXPORT.forEach(rule -> rules.add(rule.id()));
            DELIBERATE_DIFFERENCES.forEach(rule -> rules.add(rule.id()));
            rules.removeAll(RULE_HITS.keySet());
            assertEquals(Set.of(), rules, "table entries that no fixture exercises");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Reading the full exports
    // ---------------------------------------------------------------------------------------------------------------

    record Triple(String subject, String className, String property, String value, CgmesStatement.Kind kind) {
    }

    record Key(String subject, String property) {
    }

    static String fullSsh(Network network) {
        StringWriter out = new StringWriter();
        try {
            SteadyStateHypothesisExport.write(network, XmlUtil.initializeWriter(true, "    ", out), new CgmesExportContext(network));
        } catch (XMLStreamException e) {
            throw new UncheckedXmlStreamException(e);
        }
        return out.toString();
    }

    private static String fullEq(Network network, CgmesExportContext context) {
        StringWriter out = new StringWriter();
        try {
            EquipmentExport.write(network, XmlUtil.initializeWriter(true, "    ", out), context);
        } catch (XMLStreamException e) {
            throw new UncheckedXmlStreamException(e);
        }
        return out.toString();
    }

    /**
     * The statements of a CGMES instance file: every CIM object at the top level of the document, with an
     * {@code rdf:type} row for its class. The header, which is not in the CIM namespace, is skipped.
     */
    static Map<Key, Triple> parse(String xml, String cimNamespace) {
        Map<Key, Triple> triples = new LinkedHashMap<>();
        try {
            XMLStreamReader reader = XMLInputFactory.newInstance().createXMLStreamReader(new StringReader(xml));
            int depth = 0;
            String subject = null;
            String className = null;
            String property = null;
            String resource = null;
            StringBuilder text = new StringBuilder();
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (depth == 2) {
                        String about = reader.getAttributeValue(RDF_NAMESPACE, "about");
                        String id = about != null ? about : reader.getAttributeValue(RDF_NAMESPACE, "ID");
                        className = reader.getLocalName();
                        subject = id != null && cimNamespace.equals(reader.getNamespaceURI())
                                ? DifferenceModelParser.normalizeId(id) : null;
                        if (subject != null) {
                            put(triples, new Triple(subject, className, RDF_TYPE, className, CgmesStatement.Kind.REFERENCE));
                        }
                    } else if (depth == 3) {
                        property = reader.getLocalName();
                        resource = reader.getAttributeValue(RDF_NAMESPACE, "resource");
                        text.setLength(0);
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && depth == 3) {
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (depth == 3 && subject != null) {
                        put(triples, statement(subject, className, property, resource, text.toString().trim(), cimNamespace));
                    }
                    depth--;
                }
            }
        } catch (XMLStreamException e) {
            throw new UncheckedXmlStreamException(e);
        }
        return triples;
    }

    private static Triple statement(String subject, String className, String property, String resource, String text,
                                    String cimNamespace) {
        if (resource == null) {
            return new Triple(subject, className, property, text, CgmesStatement.Kind.LITERAL);
        }
        return resource.startsWith(cimNamespace)
                ? new Triple(subject, className, property, resource.substring(cimNamespace.length()), CgmesStatement.Kind.ENUM)
                : new Triple(subject, className, property, DifferenceModelParser.normalizeId(resource), CgmesStatement.Kind.REFERENCE);
    }

    /** A full export writing one property of one object twice, with two values, would be a defect of its own. */
    private static void put(Map<Key, Triple> triples, Triple triple) {
        Triple previous = triples.putIfAbsent(new Key(triple.subject(), triple.property()), triple);
        if (previous != null && !comparable(previous).equals(comparable(triple))) {
            throw new AssertionError("the full export writes two values: " + triple + " and " + previous);
        }
    }

    /** As {@code TripleDiffCalculator.comparable}: the kind, and a numeric literal by value ({@code -0 == 0}). */
    static String comparable(Triple triple) {
        if (triple.kind() == CgmesStatement.Kind.LITERAL) {
            try {
                // + 0.0 so that -0 and 0 compare equal: numeric equality, not the text of a double
                return "L" + (Double.parseDouble(triple.value()) + 0.0);
            } catch (NumberFormatException notANumber) {
                return "L" + triple.value();
            }
        }
        return triple.kind().name().charAt(0) + triple.value();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Asking the shared mapping
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * What the shared mapping says about the network.
     *
     * @param ssh       the steady state hypothesis statements, loading and voltage limits excepted
     * @param eq        the equipment statements, loading and voltage limits excepted
     * @param limits    the loading and voltage limit values, whichever profile the CIM version puts them in
     * @param refusals  the reasons of the refused probes, by IIDM identifier
     * @param conflicts two probes stating different values for one property, which must not happen
     */
    record Shared(Map<Key, Triple> ssh, Map<Key, Triple> eq, Map<Key, Triple> limits, Map<String, List<String>> refusals,
                  List<String> conflicts) {
    }

    static Shared shared(Network network, CgmesExportContext context) {
        // Built as CgmesObjectDump builds it, without its cache
        CgmesChangeTranslator translator = new CgmesChangeTranslator(network, context,
                PartialSshExport.UnsupportedChangeBehavior.IGNORE, "an equivalence test",
                EnumSet.of(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS), IidmStateView.LIVE, null);
        String variantId = network.getVariantManager().getWorkingVariantId();
        Shared shared = new Shared(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
                new LinkedHashMap<>(), new ArrayList<>());
        for (Identifiable<?> identifiable : network.getIdentifiables()) {
            for (String probe : probes(identifiable)) {
                int separator = probe.indexOf('#');
                NetworkEvent event = separator < 0
                        ? new UpdateNetworkEvent(identifiable.getId(), probe, variantId, null, null)
                        : new ExtensionUpdateNetworkEvent(identifiable.getId(), probe.substring(0, separator),
                                probe.substring(separator + 1), variantId, null, null);
                switch (translator.translate(event)) {
                    case Result.Success(CgmesPropertyBuffer buffer) -> {
                        collect(shared, buffer.statements(CgmesSubset.EQUIPMENT, context), shared.eq(), identifiable, probe);
                        collect(shared, buffer.statements(CgmesSubset.STEADY_STATE_HYPOTHESIS, context), shared.ssh(),
                                identifiable, probe);
                    }
                    case Result.Failure(String reason) -> shared.refusals()
                            .computeIfAbsent(identifiable.getId(), id -> new ArrayList<>()).add(probe + ": " + reason);
                }
            }
        }
        return shared;
    }

    private static void collect(Shared shared, List<CgmesStatement> statements, Map<Key, Triple> target,
                                Identifiable<?> identifiable, String probe) {
        for (CgmesStatement statement : statements) {
            boolean limit = LIMIT_CLASSES.contains(statement.className()) || VOLTAGE_LIMIT.equals(statement.className());
            Map<Key, Triple> map = limit ? shared.limits() : target;
            for (Triple triple : List.of(
                    new Triple(statement.subjectId(), statement.className(), RDF_TYPE, statement.className(), CgmesStatement.Kind.REFERENCE),
                    new Triple(statement.subjectId(), statement.className(), statement.property(), statement.value(), statement.kind()))) {
                Triple previous = map.putIfAbsent(new Key(triple.subject(), triple.property()), triple);
                if (previous != null && !comparable(previous).equals(comparable(triple))) {
                    shared.conflicts().add(identifiable.getId() + " " + probe + ": " + triple + " vs " + previous);
                }
            }
        }
    }

    /**
     * Every attribute the shared mapping matches on, per kind of IIDM object: the attribute names of
     * {@code DiffProbes}, which is what the difference model importer asks, plus every limit of every group. Asking
     * about an attribute the mapping refuses costs a refusal and nothing else.
     */
    static List<String> probes(Identifiable<?> identifiable) {
        List<String> probes = new ArrayList<>(ownerProbes(identifiable));
        limitSides(identifiable).forEach(side -> side.groups().forEach(group -> {
            group.getCurrentLimits().ifPresent(limits -> limitProbes(probes, side.prefix() + "_CURRENT", group.getId(), limits));
            group.getActivePowerLimits().ifPresent(limits -> limitProbes(probes, side.prefix() + "_ACTIVE_POWER", group.getId(), limits));
            group.getApparentPowerLimits().ifPresent(limits -> limitProbes(probes, side.prefix() + "_APPARENT_POWER", group.getId(), limits));
        }));
        return probes;
    }

    private static List<String> ownerProbes(Identifiable<?> identifiable) {
        return switch (identifiable) {
            case Switch ignored -> List.of("open");
            case DcSwitch ignored -> List.of("open");
            case Load ignored -> List.of("p0", "q0");
            case Generator ignored -> List.of("targetP", "targetQ", "targetV", "voltageRegulatorOn",
                    "activePowerControl#participationFactor", "referencePriorities#referencePriority",
                    "generatorRemoteReactivePowerControl#targetQ", "generatorRemoteReactivePowerControl#enabled");
            case BoundaryLine ignored -> List.of("p0", "q0", "targetP", "targetQ", "targetV", "voltageRegulationOn",
                    "r", "x", "g", "b");
            case Line ignored -> List.of("r", "x", "g1", "b1");
            case VoltageLevel ignored -> List.of("highVoltageLimit", "lowVoltageLimit");
            case ShuntCompensator ignored -> List.of("sectionCount", "voltageRegulatorOn", "targetV", "targetDeadband");
            case StaticVarCompensator ignored -> List.of("regulating", "reactivePowerSetpoint", "voltageSetpoint");
            case HvdcLine ignored -> List.of("activePowerSetpoint", "convertersMode");
            case VscConverterStation ignored -> List.of("voltageRegulatorOn", "voltageSetpoint", "reactivePowerSetpoint");
            case LccConverterStation ignored -> List.of("powerFactor");
            case AcDcConverter<?> ignored -> List.of("targetP", "targetVdc", "controlMode", "voltageRegulatorOn",
                    "voltageSetpoint", "reactivePowerSetpoint", "powerFactor");
            case TwoWindingsTransformer ignored -> tapChangerProbes("");
            case ThreeWindingsTransformer ignored -> tapChangerProbes("1", "2", "3");
            default -> List.of();
        };
    }

    private static List<String> tapChangerProbes(String... ends) {
        List<String> probes = new ArrayList<>();
        for (String end : ends) {
            for (String kind : List.of("ratioTapChanger", "phaseTapChanger")) {
                for (String suffix : List.of(".tapPosition", ".regulating", ".regulationValue", ".targetDeadband")) {
                    probes.add(kind + end + suffix);
                }
            }
        }
        return probes;
    }

    /** The whole object, the permanent limit and every temporary limit, so that each value is asked for. */
    private static void limitProbes(List<String> probes, String name, String groupId, LoadingLimits limits) {
        probes.add(name + "@" + groupId);
        probes.add(name + ".permanentLimit@" + groupId);
        limits.getTemporaryLimits().forEach(temporaryLimit ->
                probes.add(name + ".temporaryLimit.value@" + groupId + "@" + temporaryLimit.getAcceptableDuration()));
    }

    /** One side of an object holding loading limits: the prefix a change log gives it, its terminal and its groups. */
    private record LimitSide(String prefix, Terminal terminal, Collection<OperationalLimitsGroup> groups) {
    }

    private static List<LimitSide> limitSides(Identifiable<?> identifiable) {
        return switch (identifiable) {
            case TieLine ignored -> List.of(); // its halves are boundary lines, which carry the limits
            case ThreeWindingsTransformer transformer -> transformer.getLegs().stream()
                    .map(leg -> new LimitSide("limits" + leg.getSide().getNum(), leg.getTerminal(), leg.getOperationalLimitsGroups()))
                    .toList();
            case Branch<?> branch -> List.of(
                    new LimitSide("limits1", branch.getTerminal1(), branch.getOperationalLimitsGroups1()),
                    new LimitSide("limits2", branch.getTerminal2(), branch.getOperationalLimitsGroups2()));
            case BoundaryLine line -> List.of(new LimitSide("limits", line.getTerminal(), line.getOperationalLimitsGroups()));
            default -> List.of();
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The equipment values, brought onto common keys
    // ---------------------------------------------------------------------------------------------------------------

    private record EqSides(Map<Key, Triple> full, Map<Key, Triple> shared) {
    }

    /**
     * The equipment values of both sides on common keys. The triples keep what each side really wrote, so that a
     * rule sees the class, the identifier and the property of both.
     *
     * <ul>
     *     <li>Impedances: the full equipment export writes every line and boundary line as an {@code ACLineSegment},
     *     so a shared {@code EquivalentBranch.r} is keyed as {@code ACLineSegment.r} of the same subject.</li>
     *     <li>Voltage limits written on {@code VoltageLimit} objects are keyed as the {@code VoltageLevel} attribute
     *     of their voltage level.</li>
     *     <li>Loading limits are keyed by their IIDM slot, {@code OperationalLimit[owner|side|group|class|duration]}.
     *     A limit value either side writes that no slot claims is kept under its own key, so it cannot go
     *     unnoticed.</li>
     * </ul>
     */
    private static EqSides eqSides(Network network, CgmesExportContext context, CgmesExportContext eqContext,
                                   Map<Key, Triple> fullEq, Shared shared, Facts facts) {
        Map<Key, Triple> full = new LinkedHashMap<>();
        Map<Key, Triple> sharedSide = new LinkedHashMap<>();
        fullEq.values().forEach(triple -> {
            boolean impedance = CgmesNames.AC_LINE_SEGMENT.equals(triple.className())
                    && triple.property().matches("ACLineSegment\\.(r|x|gch|bch)");
            boolean voltageLevel = CgmesNames.VOLTAGE_LEVEL.equals(triple.className())
                    && triple.property().matches("VoltageLevel\\.(high|low)VoltageLimit");
            if (impedance || voltageLevel) {
                full.put(new Key(triple.subject(), triple.property()), triple);
            }
        });
        shared.eq().values().stream().filter(triple -> !RDF_TYPE.equals(triple.property())).forEach(triple -> {
            String property = triple.property();
            if (BRANCH_CLASSES.contains(triple.className()) && !property.matches(".*\\.[rx]21")) {
                property = CgmesNames.AC_LINE_SEGMENT + property.substring(property.indexOf('.'));
            }
            sharedSide.put(new Key(triple.subject(), property), triple);
        });
        shared.limits().values().stream()
                .filter(triple -> VOLTAGE_LIMIT.equals(triple.className()) && !RDF_TYPE.equals(triple.property()))
                .forEach(triple -> {
                    Facts.VoltageLimitRef ref = facts.voltageLimits().get(triple.subject());
                    sharedSide.put(ref == null ? new Key(triple.subject(), triple.property())
                            : new Key(ref.voltageLevelId(), "VoltageLevel." + (ref.high() ? "high" : "low") + "VoltageLimit"), triple);
                });

        boolean cim16 = context.getCimVersion() == 16;
        Set<Key> claimedFull = new HashSet<>();
        Set<Key> claimedShared = new HashSet<>();
        for (Identifiable<?> identifiable : network.getIdentifiables()) {
            for (LimitSide side : limitSides(identifiable)) {
                String fullSetTerminal = CgmesExportUtil.getTerminalId(side.terminal(), eqContext);
                for (OperationalLimitsGroup group : side.groups()) {
                    String fullSetId = EquipmentExport.operationalLimitSetId(group, fullSetTerminal, eqContext);
                    List<LoadingLimits> all = new ArrayList<>();
                    group.getActivePowerLimits().ifPresent(all::add);
                    group.getApparentPowerLimits().ifPresent(all::add);
                    group.getCurrentLimits().ifPresent(all::add);
                    for (LoadingLimits limits : all) {
                        String className = LoadingLimitEq.loadingLimitClassName(limits);
                        List<Integer> durations = new ArrayList<>();
                        durations.add(-1);
                        limits.getTemporaryLimits().forEach(temporaryLimit -> durations.add(temporaryLimit.getAcceptableDuration()));
                        for (int duration : durations) {
                            Key slot = new Key("OperationalLimit[" + identifiable.getId() + "|" + side.prefix() + "|"
                                    + group.getId() + "|" + className + "|" + duration + "]", "value");
                            Key fullKey = new Key(CgmesExportUtil.toMasterResourceId(
                                    EquipmentExport.operationalLimitId(fullSetId, className, duration, eqContext), eqContext),
                                    className + (cim16 ? ".value" : ".normalValue"));
                            Triple fullTriple = fullEq.get(fullKey);
                            if (fullTriple != null) {
                                full.put(slot, fullTriple);
                                claimedFull.add(fullKey);
                            }
                            String sharedId = sharedLimitId(network, context, side, group, className, duration);
                            Key sharedKey = sharedId == null ? null : new Key(sharedId, className + ".value");
                            Triple sharedTriple = sharedKey == null ? null : shared.limits().get(sharedKey);
                            if (sharedTriple != null) {
                                sharedSide.put(slot, sharedTriple);
                                claimedShared.add(sharedKey);
                            } else if (fullTriple != null && sharedId == null) {
                                // No stored identifier on a network imported from CGMES: a synthesized limit
                                facts.refusedSlots().add(slot.subject());
                            }
                        }
                    }
                }
            }
        }
        fullEq.forEach((key, triple) -> {
            if (LIMIT_CLASSES.contains(triple.className()) && !RDF_TYPE.equals(key.property()) && !claimedFull.contains(key)
                    && key.property().matches("[A-Za-z]+Limit\\.(value|normalValue)")) {
                full.put(key, triple);
            }
        });
        shared.limits().forEach((key, triple) -> {
            if (LIMIT_CLASSES.contains(triple.className()) && !RDF_TYPE.equals(key.property()) && !claimedShared.contains(key)) {
                sharedSide.put(key, triple);
            }
        });
        return new EqSides(full, sharedSide);
    }

    /** The identifier under which the shared mapping writes one loading limit, as {@code CgmesChangeTranslator#limitId}. */
    private static String sharedLimitId(Network network, CgmesExportContext context, LimitSide side,
                                        OperationalLimitsGroup group, String className, int duration) {
        String stored = group.getProperty(Conversion.getOperationalLimitPropertyName(className, duration < 0,
                Math.max(duration, 0), CgmesNames.OPERATIONAL_LIMIT));
        String id;
        if (stored != null && !stored.isEmpty()) {
            id = context.getNamingStrategy().getCgmesId(stored);
        } else if (network.getExtension(CimCharacteristics.class) != null) {
            return null;
        } else {
            id = EquipmentExport.operationalLimitId(EquipmentExport.operationalLimitSetId(group,
                    CgmesExportUtil.getTerminalId(side.terminal(), context), context), className, duration, context);
        }
        return CgmesExportUtil.toMasterResourceId(id, context);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // What the rules need to know about a fixture
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The CGMES identifiers the rules condition on, computed with the naming strategy of the export.
     *
     * @param branchSwitches                IIDM switches the import created from a CGMES branch class
     * @param branchSwitchTerminals         the terminals of those
     * @param dcSwitchTerminals             the two terminals of every DcSwitch
     * @param generatedEquivalentInjections the EquivalentInjection identifiers the full export generates for boundary
     *                                      lines that carry none
     * @param boundaryInjections            the EquivalentInjection of every boundary line with a Generation
     * @param detailedLccs                  the line commutated converters of the detailed DC model
     * @param reactivePowerModeMachines     machines whose CGMES control regulates reactive power, with the documented
     *                                      pair of control flags {@code "<voltage flag>|<remote reactive power control enabled>"}
     * @param currentLimiterControls        tap changer controls of phase tap changers in current limiter mode
     * @param reactivePowerControlsRegulatingVoltage regulating controls of CGMES mode reactive power whose generator
     *                                      regulates voltage
     * @param currentLimiterLimits          the CurrentLimit the full equipment export writes for a current limiter
     * @param voltageLimits                 the voltage level and the side of every stored VoltageLimit identifier
     * @param refusals                      the reasons of the refused probes, by CGMES subject
     * @param refusedSlots                  loading limit slots without a stored CGMES identifier, which the full export
     *                                      writes and the shared mapping cannot
     * @param expectedShared                the value the shared mapping has to write where it deliberately differs from
     *                                      the full export, derived here from the IIDM objects as the import reads the
     *                                      property back
     * @param expectedOmissions             the properties the shared mapping has to leave out, because the IIDM value is
     *                                      not one it writes (an unusable voltage target, a setpoint that is not finite)
     */
    private record Facts(Set<String> branchSwitches, Set<String> branchSwitchTerminals, Set<String> dcSwitchTerminals,
                         Set<String> generatedEquivalentInjections, Map<String, BoundaryInjection> boundaryInjections,
                         Set<String> detailedLccs, Map<String, String> reactivePowerModeMachines, Set<String> currentLimiterControls,
                         Set<String> reactivePowerControlsRegulatingVoltage, Set<String> currentLimiterLimits,
                         Map<String, VoltageLimitRef> voltageLimits, Map<String, List<String>> refusals,
                         Set<String> refusedSlots, Map<Key, String> expectedShared, Set<Key> expectedOmissions) {

        record VoltageLimitRef(String voltageLevelId, boolean high) {
        }

        record BoundaryInjection(double p0, double q0) {
            double p0OrQ0(String property) {
                return property.endsWith(".p") ? p0 : q0;
            }
        }

        /**
         * Whether the shared mapping refused one of the given probes of the object behind this CGMES subject, for one of
         * the given reasons. Any other refusal leaves the row unexplained.
         */
        boolean refused(String subject, Set<String> probes, List<String> reasons) {
            return probes != null && refusals.getOrDefault(subject, List.of()).stream()
                    .anyMatch(refusal -> probes.contains(refusal.substring(0, refusal.indexOf(": ")))
                            && reasons.stream().anyMatch(refusal::contains));
        }

        /** Whether the shared statement of the row is the value {@link #expectedShared} derives from IIDM. */
        boolean sharedAsExpected(Row row) {
            String expected = expectedShared.get(row.key());
            if (expected == null || row.shared() == null) {
                return false;
            }
            if (expected.equals(row.shared().value())) {
                return true;
            }
            try {
                double value = Double.parseDouble(row.shared().value());
                double wanted = Double.parseDouble(expected);
                return Double.compare(value + 0.0, wanted + 0.0) == 0
                        || Double.compare(value + 0.0, Double.parseDouble(CgmesExportUtil.format(wanted)) + 0.0) == 0;
            } catch (NumberFormatException notANumber) {
                return false;
            }
        }

        /**
         * The values the shared mapping has to write where it deliberately differs from the full export, and the
         * properties it has to leave out, derived from the IIDM objects independently of the mapping.
         */
        private static void expectations(Network network, CgmesExportContext context, Map<Key, String> expected,
                                         Set<Key> omissions) {
            NamingStrategy naming = context.getNamingStrategy();
            // The update derives the state of a switch imported from a branch class from its two terminals
            for (Switch sw : network.getSwitches()) {
                if (BRANCH_CLASSES.contains(sw.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS, ""))) {
                    for (String alias : List.of(Conversion.ALIAS_TERMINAL1, Conversion.ALIAS_TERMINAL2)) {
                        expected.put(new Key(id(naming.getCgmesIdFromAlias(sw, alias), context), ACDC_TERMINAL_CONNECTED),
                                String.valueOf(!sw.isOpen()));
                    }
                }
            }
            // The import puts the whole boundary injection into the generation: targetP = -p, p0 = 0
            for (BoundaryLine boundaryLine : network.getBoundaryLines(BoundaryLineFilter.ALL)) {
                if (!boundaryLine.hasProperty(Conversion.PROPERTY_EQUIVALENT_INJECTION)) {
                    continue;
                }
                String injection = id(naming.getCgmesIdFromProperty(boundaryLine, Conversion.PROPERTY_EQUIVALENT_INJECTION), context);
                BoundaryLine.Generation generation = boundaryLine.getGeneration();
                if (generation != null) {
                    expected.put(new Key(injection, "EquivalentInjection.p"),
                            String.valueOf(zeroIfNaN(boundaryLine.getP0()) - zeroIfNaN(generation.getTargetP())));
                    expected.put(new Key(injection, "EquivalentInjection.q"),
                            String.valueOf(zeroIfNaN(boundaryLine.getQ0()) - zeroIfNaN(generation.getTargetQ())));
                }
                if (generation == null || !(generation.getTargetV() > 0)) {
                    omissions.add(new Key(injection, "EquivalentInjection.regulationTarget"));
                }
            }
            for (Generator generator : network.getGenerators()) {
                if (CgmesNames.EQUIVALENT_INJECTION.equals(generator.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS))
                        && !(generator.getTargetV() > 0)) {
                    omissions.add(new Key(id(naming.getCgmesId(generator), context), "EquivalentInjection.regulationTarget"));
                }
            }
            // The import reads the power factor of a line commutated converter back as |p| / hypot(p, q), and the
            // active power as the setpoint of a converter controlling it
            for (LineCommutatedConverter lcc : network.getLineCommutatedConverters()) {
                double p = lcc.getControlMode() == AcDcConverter.ControlMode.P_PCC ? lcc.getTargetP() : Double.NaN;
                double powerFactor = lcc.getPowerFactor();
                if (Double.isFinite(p) && p != 0 && powerFactor > 0) {
                    String subject = id(naming.getCgmesId(lcc), context);
                    expected.put(new Key(subject, "ACDCConverter.p"), String.valueOf(p));
                    expected.put(new Key(subject, "ACDCConverter.q"),
                            String.valueOf(Math.abs(p) * Math.sqrt(1 - powerFactor * powerFactor) / powerFactor));
                }
            }
            // A phase tap changer limiting current, described with its own values on the control the import recorded
            for (TwoWindingsTransformer transformer : network.getTwoWindingsTransformers()) {
                transformer.getOptionalPhaseTapChanger().filter(Facts::isCurrentLimiter).ifPresent(ptc -> currentLimiterValues(ptc,
                        controlIdOf(transformer, CgmesExportUtil.tapChangerAliasType(transformer,
                                Conversion.ALIAS_PHASE_TAP_CHANGER1, Conversion.ALIAS_PHASE_TAP_CHANGER2), context), expected));
            }
            for (ThreeWindingsTransformer transformer : network.getThreeWindingsTransformers()) {
                transformer.getLegs().forEach(leg -> leg.getOptionalPhaseTapChanger().filter(Facts::isCurrentLimiter)
                        .ifPresent(ptc -> currentLimiterValues(ptc, controlIdOf(transformer,
                                CgmesExportUtil.getPhaseTapChangerAliasType(Integer.toString(leg.getSide().getNum())), context),
                                expected)));
            }
            // An EquivalentBranch states the impedance of both directions, IIDM holds one
            for (Line line : network.getLines()) {
                equivalentBranchValues(line, line.getR(), line.getX(), context, expected);
            }
            for (BoundaryLine boundaryLine : network.getBoundaryLines(BoundaryLineFilter.ALL)) {
                equivalentBranchValues(boundaryLine, boundaryLine.getR(), boundaryLine.getX(), context, expected);
            }
            // Setpoints of a voltage source converter the shared mapping does not write
            for (VscConverterStation station : network.getVscConverterStations()) {
                String subject = id(naming.getCgmesId(station), context);
                if (!(station.getVoltageSetpoint() > 0)) {
                    omissions.add(new Key(subject, "VsConverter.targetUpcc"));
                }
                if (!Double.isFinite(station.getReactivePowerSetpoint())) {
                    omissions.add(new Key(subject, "VsConverter.targetQpcc"));
                }
            }
            for (VoltageSourceConverter converter : network.getVoltageSourceConverters()) {
                String subject = id(naming.getCgmesId(converter), context);
                if (!Double.isFinite(converter.getVoltageSetpoint())) {
                    omissions.add(new Key(subject, "VsConverter.targetUpcc"));
                }
                if (!Double.isFinite(converter.getReactivePowerSetpoint())) {
                    omissions.add(new Key(subject, "VsConverter.targetQpcc"));
                }
            }
        }

        private static double zeroIfNaN(double value) {
            return Double.isNaN(value) ? 0.0 : value;
        }

        private static void currentLimiterValues(PhaseTapChanger ptc, String controlId, Map<Key, String> expected) {
            if (controlId != null) {
                expected.put(new Key(controlId, "RegulatingControl.enabled"), String.valueOf(ptc.isRegulating()));
                expected.put(new Key(controlId, "RegulatingControl.targetDeadband"), String.valueOf(ptc.getTargetDeadband()));
                expected.put(new Key(controlId, "RegulatingControl.targetValue"), String.valueOf(ptc.getRegulationValue()));
            }
        }

        private static void equivalentBranchValues(Identifiable<?> branch, double r, double x, CgmesExportContext context,
                                                   Map<Key, String> expected) {
            if (CgmesNames.EQUIVALENT_BRANCH.equals(branch.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS))) {
                String subject = id(context.getNamingStrategy().getCgmesId(branch), context);
                expected.put(new Key(subject, "EquivalentBranch.r21"), String.valueOf(r));
                expected.put(new Key(subject, "EquivalentBranch.x21"), String.valueOf(x));
            }
        }

        static Facts of(Network network, CgmesExportContext context, Shared shared) {
            NamingStrategy naming = context.getNamingStrategy();
            Set<String> branchSwitches = new HashSet<>();
            Set<String> branchSwitchTerminals = new HashSet<>();
            for (Switch sw : network.getSwitches()) {
                if (BRANCH_CLASSES.contains(sw.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS, ""))) {
                    branchSwitches.add(id(naming.getCgmesId(sw), context));
                    branchSwitchTerminals.add(id(naming.getCgmesIdFromAlias(sw, Conversion.ALIAS_TERMINAL1), context));
                    branchSwitchTerminals.add(id(naming.getCgmesIdFromAlias(sw, Conversion.ALIAS_TERMINAL2), context));
                }
            }
            Set<String> dcSwitchTerminals = new HashSet<>();
            for (DcSwitch dcSwitch : network.getDcSwitches()) {
                dcSwitchTerminals.add(id(naming.getCgmesIdFromAlias(dcSwitch, Conversion.ALIAS_DC_TERMINAL1), context));
                dcSwitchTerminals.add(id(naming.getCgmesIdFromAlias(dcSwitch, Conversion.ALIAS_DC_TERMINAL2), context));
            }
            Set<String> generatedEquivalentInjections = new HashSet<>();
            Map<String, BoundaryInjection> boundaryInjections = new HashMap<>();
            for (BoundaryLine boundaryLine : network.getBoundaryLines(BoundaryLineFilter.ALL)) {
                String injection = id(naming.getCgmesIdFromProperty(boundaryLine, Conversion.PROPERTY_EQUIVALENT_INJECTION), context);
                if (!boundaryLine.hasProperty(Conversion.PROPERTY_EQUIVALENT_INJECTION)) {
                    generatedEquivalentInjections.add(injection);
                } else if (boundaryLine.getGeneration() != null) {
                    boundaryInjections.put(injection, new BoundaryInjection(boundaryLine.getP0(), boundaryLine.getQ0()));
                }
            }
            Set<String> detailedLccs = new HashSet<>();
            network.getLineCommutatedConverters().forEach(lcc -> detailedLccs.add(id(naming.getCgmesId(lcc), context)));
            Map<String, String> reactivePowerModeMachines = new HashMap<>();
            Set<String> reactivePowerControlsRegulatingVoltage = new HashSet<>();
            for (Generator generator : network.getGenerators()) {
                RemoteReactivePowerControl reactivePowerControl = generator.getExtension(RemoteReactivePowerControl.class);
                if (RegulatingControlMapping.isControlModeReactivePower(generator.getProperty(Conversion.PROPERTY_MODE))
                        && reactivePowerControl != null) {
                    reactivePowerModeMachines.put(id(naming.getCgmesId(generator), context),
                            generator.isVoltageRegulatorOn() + "|" + reactivePowerControl.isEnabled());
                    if (generator.isVoltageRegulatorOn() && generator.hasProperty(Conversion.PROPERTY_REGULATING_CONTROL)) {
                        reactivePowerControlsRegulatingVoltage.add(
                                id(naming.getCgmesIdFromProperty(generator, Conversion.PROPERTY_REGULATING_CONTROL), context));
                    }
                }
            }
            Set<String> currentLimiterControls = new HashSet<>();
            for (TwoWindingsTransformer transformer : network.getTwoWindingsTransformers()) {
                transformer.getOptionalPhaseTapChanger().filter(Facts::isCurrentLimiter).ifPresent(ptc -> addControlId(transformer,
                        CgmesExportUtil.tapChangerAliasType(transformer, Conversion.ALIAS_PHASE_TAP_CHANGER1, Conversion.ALIAS_PHASE_TAP_CHANGER2),
                        context, currentLimiterControls));
            }
            for (ThreeWindingsTransformer transformer : network.getThreeWindingsTransformers()) {
                transformer.getLegs().forEach(leg -> leg.getOptionalPhaseTapChanger().filter(Facts::isCurrentLimiter).ifPresent(ptc ->
                        addControlId(transformer, CgmesExportUtil.getPhaseTapChangerAliasType(Integer.toString(leg.getSide().getNum())),
                                context, currentLimiterControls)));
            }
            Set<String> currentLimiterLimits = new HashSet<>();
            network.getTwoWindingsTransformers().forEach(transformer -> transformer.getOptionalPhaseTapChanger()
                    .filter(Facts::isCurrentLimiter).filter(ptc -> ptc.getRegulationTerminal() != null)
                    .ifPresent(ptc -> currentLimiterLimits.add(currentLimiterLimitId(ptc, context))));
            network.getThreeWindingsTransformers().forEach(transformer -> transformer.getLegs().forEach(leg -> leg.getOptionalPhaseTapChanger()
                    .filter(Facts::isCurrentLimiter).filter(ptc -> ptc.getRegulationTerminal() != null)
                    .ifPresent(ptc -> currentLimiterLimits.add(currentLimiterLimitId(ptc, context)))));
            Map<String, VoltageLimitRef> voltageLimits = new HashMap<>();
            for (VoltageLevel voltageLevel : network.getVoltageLevels()) {
                String voltageLevelId = id(naming.getCgmesId(voltageLevel), context);
                storedIds(voltageLevel, Conversion.PROPERTY_OPERATIONAL_LIMIT_HIGH_VOLTAGE_LIMIT).forEach(stored ->
                        voltageLimits.put(id(naming.getCgmesId(stored), context), new VoltageLimitRef(voltageLevelId, true)));
                storedIds(voltageLevel, Conversion.PROPERTY_OPERATIONAL_LIMIT_LOW_VOLTAGE_LIMIT).forEach(stored ->
                        voltageLimits.put(id(naming.getCgmesId(stored), context), new VoltageLimitRef(voltageLevelId, false)));
            }
            Map<Key, String> expectedShared = new HashMap<>();
            Set<Key> expectedOmissions = new HashSet<>();
            expectations(network, context, expectedShared, expectedOmissions);
            Map<String, List<String>> refusals = new HashMap<>();
            shared.refusals().forEach((iidmId, reasons) ->
                    refusals.put(id(naming.getCgmesId(network.getIdentifiable(iidmId)), context), reasons));
            return new Facts(branchSwitches, branchSwitchTerminals, dcSwitchTerminals, generatedEquivalentInjections,
                    boundaryInjections, detailedLccs, reactivePowerModeMachines, currentLimiterControls,
                    reactivePowerControlsRegulatingVoltage, currentLimiterLimits, voltageLimits, refusals, new HashSet<>(),
                    expectedShared, expectedOmissions);
        }

        /** As EquipmentExport names the CurrentLimit it writes for a phase tap changer limiting current. */
        private static String currentLimiterLimitId(PhaseTapChanger phaseTapChanger, CgmesExportContext context) {
            String terminalId = CgmesExportUtil.getTerminalId(phaseTapChanger.getRegulationTerminal(), context);
            String setId = context.getNamingStrategy().getCgmesId(ref(terminalId),
                    ref(PhaseTapChanger.RegulationMode.CURRENT_LIMITER.name()), CgmesObjectReference.Part.OPERATIONAL_LIMIT_SET);
            return id(context.getNamingStrategy().getCgmesId(ref(setId), ref("CurrentLimit"), CgmesObjectReference.Part.PATL,
                    CgmesObjectReference.Part.OPERATIONAL_LIMIT_VALUE), context);
        }

        private static boolean isCurrentLimiter(PhaseTapChanger phaseTapChanger) {
            return phaseTapChanger.getRegulationMode() == PhaseTapChanger.RegulationMode.CURRENT_LIMITER;
        }

        /** The TapChangerControl of the CGMES tap changer the given alias points at, as the shared mapping finds it. */
        /** The TapChangerControl the import recorded for the tap changer the given alias points at, or {@code null}. */
        private static <C extends Connectable<C>> String controlIdOf(C transformer, String aliasType, CgmesExportContext context) {
            Set<String> ids = new HashSet<>();
            addControlId(transformer, aliasType, context, ids);
            return ids.isEmpty() ? null : ids.iterator().next();
        }

        private static <C extends Connectable<C>> void addControlId(C transformer, String aliasType, CgmesExportContext context,
                                                                    Set<String> ids) {
            CgmesTapChangers<C> tapChangers = transformer.getExtension(CgmesTapChangers.class);
            String tapChangerId = transformer.getAliasFromType(aliasType).orElse(null);
            CgmesTapChanger tapChanger = tapChangers == null || tapChangerId == null ? null : tapChangers.getTapChanger(tapChangerId);
            if (tapChanger != null && tapChanger.getControlId() != null) {
                ids.add(id(context.getNamingStrategy().getCgmesId(tapChanger.getControlId()), context));
            }
        }

        private static List<String> storedIds(VoltageLevel voltageLevel, String property) {
            String ids = voltageLevel.getProperty(property);
            return ids == null ? List.of() : Arrays.stream(ids.split(";")).filter(id -> !id.isEmpty()).toList();
        }

        private static String id(String cgmesId, CgmesExportContext context) {
            return CgmesExportUtil.toMasterResourceId(cgmesId, context);
        }
    }
}
