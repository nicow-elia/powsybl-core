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
import com.powsybl.cgmes.conformity.CgmesConformity1ModifiedCatalog;
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
import com.powsybl.cgmes.model.diff.StatementDiff;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import com.powsybl.commons.util.Result;
import com.powsybl.commons.xml.XmlUtil;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.events.ExtensionUpdateNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ReferencePriorities;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulation;
import com.powsybl.iidm.network.regulation.VoltageRegulationHolder;
import com.powsybl.iidm.network.test.BatteryNetworkFactory;
import com.powsybl.iidm.network.test.FourSubstationsNodeBreakerFactory;
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
 * state of the whole network, {@link CgmesChangeTranslator} (with {@link RegulatingControlFamily}) describes
 * the objects a change touches. They share their helpers but write the property by property mapping twice. This test
 * asserts that the two copies agree, fixture by fixture:</p>
 * <ol>
 *     <li>the full SSH export is written into memory and read back into {@code (subject, property) -> value};</li>
 *     <li>the shared mapping is asked, in the live state of the same network, about every attribute of every object
 *     it can describe, which covers every consistency group it writes. This is the synthetic full-object request
 *     {@link CgmesObjectDump} makes, without its cache;</li>
 *     <li>every {@code (subject, property)} of either side is compared, numeric literals by value as
 *     {@link StatementDiff#comparable} does, and ends up in exactly one of: equal,
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
 * <p>The fixtures are every base model of {@link RecordedChangeScenarios}, the state after each of its changes, a set
 * of conformity models (CGMES 2.4.15 bus-branch and node-breaker, HVDC, CGMES 3), and the states in which the two
 * exports may disagree: a battery, fictitious injections, hidden tap changers, holders without a VoltageRegulation or
 * not regulating, a mode that disagrees with the one the import recorded, and a terminal sign of -1 for every family
 * whose regulation target carries it. A rule of the two tables that no fixture exercises fails the test as well, so
 * that the tables cannot rot. A rule that explains a refusal of the shared mapping names the refusal it accepts.</p>
 *
 * <p>The helpers both sides call ({@code regulatingControlView}, {@code computeConverterState}, {@code vscTargetQpcc},
 * ...) change both sides alike: every value that goes through one of them ({@link #SEAM_PROPERTIES}) is therefore also
 * checked against a value this test derives from the getters of the IIDM holder, never through those helpers. The probes
 * are the attribute keys the translator matches on, taken from its constants. Two slots of the same loading limits
 * holding the same value cannot be told apart.</p>
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
    private static final String CONTROL_ENABLED = "RegulatingCondEq.controlEnabled";
    private static final String SSH = "SSH";
    private static final String EQ = "EQ";
    private static final String EQUAL = "EQUAL";
    private static final String ONLY_IN_FULL_EXPORT_TABLE = "ONLY_IN_FULL_EXPORT";
    private static final String DELIBERATE_DIFFERENCES_TABLE = "DELIBERATE_DIFFERENCES";
    private static final String DOCUMENTED = "DOCUMENTED";
    private static final String FULL_EXPORT_DEFECT = "FULL_EXPORT_DEFECT";
    private static final String CODE_REFERENCE = "CODE_REFERENCE";
    private static final String TARGET_VALUE = "RegulatingControl.targetValue";
    private static final String MULTIPLIER = "RegulatingControl.targetValueUnitMultiplier";
    private static final String VOLTAGE_PCC = "VsQpccControlKind.voltagePcc";
    private static final String REACTIVE_PCC = "VsQpccControlKind.reactivePcc";

    /**
     * The properties whose value goes through a helper both exports call (a shared seam): a wrong value there is the
     * same on both sides, so each is checked against a value this test derives from the IIDM object itself, from the
     * holder's own getters, its mode, its flag, the recorded {@code CGMES.terminalSign} and its type.
     */
    private static final Set<String> SEAM_PROPERTIES = Set.of(TARGET_VALUE, "RegulatingControl.enabled",
            "RegulatingControl.targetDeadband", "RegulatingControl.discrete", MULTIPLIER, "VsConverter.targetQpcc",
            "VsConverter.targetUpcc", "VsConverter.qPccControl", "ACDCConverter.q", "StaticVarCompensator.q");

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

    /** The refusal reasons that explain a control only the full export writes, as the shared mapping words them. */
    private static final List<String> NO_RECORDED_CONTROL_REFUSALS = List.of(
            "has no CGMES tap changer control to carry this change",
            "regulates no terminal, so its regulation has no target the receiving side could read",
            "has no CGMES regulating control the import could use");

    /** The classes a generator refused for its CGMES mode is written with: control, machine, GeneratingUnit. */
    private static final Set<String> CGMES_MODE_CLASSES = Set.of("RegulatingControl", CgmesNames.SYNCHRONOUS_MACHINE,
            CgmesNames.EXTERNAL_NETWORK_INJECTION, "GeneratingUnit", "ThermalGeneratingUnit", "HydroGeneratingUnit",
            "WindGeneratingUnit", "SolarGeneratingUnit", "NuclearGeneratingUnit");

    /** The classes of the block of a voltage regulation holder in the steady state hypothesis. */
    private static final Set<String> HOLDER_CLASSES = Set.of(CgmesNames.SYNCHRONOUS_MACHINE,
            CgmesNames.EXTERNAL_NETWORK_INJECTION, "LinearShuntCompensator", "NonlinearShuntCompensator",
            CgmesNames.STATIC_VAR_COMPENSATOR, CgmesNames.VS_CONVERTER, "GeneratingUnit", "ThermalGeneratingUnit",
            "HydroGeneratingUnit", "WindGeneratingUnit", "SolarGeneratingUnit", "NuclearGeneratingUnit");

    private static final Set<String> TAP_CHANGER_CLASSES = Set.of(CgmesNames.RATIO_TAP_CHANGER,
            CgmesNames.PHASE_TAP_CHANGER_TABULAR, "PhaseTapChangerSymmetrical", "PhaseTapChangerAsymmetrical",
            "PhaseTapChangerLinear");

    /**
     * Whether the subject of the row is one the facts name, and the shared mapping refused a probe of the IIDM object
     * behind it for one of the given reasons: any other reason leaves the row unexplained.
     */
    private static boolean refusedAs(Row row, Map<String, String> subjects, List<String> reasons) {
        String refusedObject = subjects.get(row.key().subject());
        return refusedObject != null && reasons.stream().anyMatch(reason -> row.facts().refusedFor(refusedObject, reason));
    }

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
        new OnlyInFull("CGMES_MODE_MISMATCH", CGMES_MODE_CLASSES, Set.of(),
            row -> row.facts().cgmesModeMismatchControls().containsKey(row.key().subject())
                && row.facts().refusedFor(row.facts().cgmesModeMismatchControls().get(row.key().subject()),
                    "recorded at import"),
            "the regulation of the generator is in another mode than the CGMES mode its import recorded, by which the"
                + " CGMES update reads the RegulatingControl on every update of the machine: the shared mapping refuses"
                + " the control, the machine and its GeneratingUnit (rule cgmes-mode, a changesOnly refusal: the"
                + " receiver would read the target as the other quantity), the full export writes them from the IIDM"
                + " mode (docs: Partial SSH export / Limitations)"),
        new OnlyInFull("BRANCH_CLASS_SWITCH_IMPEDANCE", BRANCH_CLASSES,
            Set.of("ACLineSegment.r", "ACLineSegment.x", "ACLineSegment.gch", "ACLineSegment.bch"),
            row -> row.facts().branchSwitches().contains(row.key().subject()) && row.fullNumber() == 0,
            "the full equipment export writes a switch the import created from a CGMES branch class as that class, with"
                + " a zero impedance; IIDM holds no impedance for a switch, so the change mapping has none to write"),
        new OnlyInFull("NO_RECORDED_CONTROL", Set.of("TapChangerControl", "RegulatingControl", CgmesNames.STATIC_VAR_COMPENSATOR,
                "LinearShuntCompensator", "NonlinearShuntCompensator"), Set.of(),
            row -> refusedAs(row, row.facts().unrecordedControls(), NO_RECORDED_CONTROL_REFUSALS),
            "the import recorded no control of the tap changer or the holder (or the network was not imported from"
                + " CGMES), so the full export writes one under a generated identifier; the shared mapping only"
                + " describes objects the receiver already holds and refuses the regulation, and with it the block of"
                + " a compensator it is written with (rules no-control, ptc-no-terminal; a changesOnly refusal)"),
        new OnlyInFull("HOLDER_WITHOUT_REGULATION", HOLDER_CLASSES, Set.of(),
            row -> refusedAs(row, row.facts().holdersWithoutRegulation(),
                List.of("has no VoltageRegulation, but the CGMES update gives it one")),
            "the holder (or a converter of its HVDC line) has no VoltageRegulation, but the CGMES update gives it one"
                + " from its RegulatingControl or its qPccControl: the shared mapping refuses every block of it (rule"
                + " import-gives-regulation, a changesOnly refusal), the full export writes its block and no control"
                + " (upstream: a holder without regulation gets no RegulatingControl)"),
        new OnlyInFull("CONVERTER_NOT_REGULATING", Set.of(CgmesNames.VS_CONVERTER), Set.of(),
            row -> refusedAs(row, row.facts().unregulatedConverters(),
                List.of("does not regulate, and a VsConverter has no control flag")),
            "a converter of the HVDC line does not regulate, and a VsConverter has no control flag: the shared mapping"
                + " refuses the blocks of both converters (rule vsc-no-control-flag, a changesOnly refusal), the full"
                + " export writes them"),
        new OnlyInFull("RTC_REACTIVE_POWER_CONTROL", Set.of("TapChangerControl"), Set.of(),
            row -> refusedAs(row, row.facts().reactivePowerTapChangerControls(),
                List.of("only writes the voltage regulation of ratio tap changers")),
            "the control of a ratio tap changer regulating reactive power: the shared mapping refuses it (rule"
                + " rtc-reactive-power, a changesOnly refusal), the full export writes it"),
        new OnlyInFull("BATTERY", Set.of(CgmesNames.SYNCHRONOUS_MACHINE), Set.of(),
            row -> row.facts().batteries().contains(row.key().subject()),
            "the full export writes a battery as a SynchronousMachine (describeBattery, the machine block of a"
                + " generator); no change names a battery, which the CGMES import never creates"),
        new OnlyInFull("FICTITIOUS_INJECTION", Set.of(CgmesNames.ENERGY_SOURCE, CgmesNames.NONCONFORM_LOAD), Set.of(),
            row -> row.facts().fictitiousInjections().contains(row.key().subject()),
            "the fictitious injection of a node or a bus, written by the full export as a NonConformLoad or an"
                + " EnergySource under a generated identifier (describeFictitiousInjection, the injection block of a"
                + " load); no change names it"),
        new OnlyInFull("HIDDEN_TAP_CHANGER", TAP_CHANGER_CLASSES, Set.of(),
            row -> row.facts().hiddenTapChangers().contains(row.key().subject()),
            "the tap changer the import combined into another one and kept hidden, written by the full export of an"
                + " SSH alone with the step it recorded (describeHiddenTapChanger, the block of a tap changer); IIDM has"
                + " no such tap changer, no change names it"),
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
        add(fixtures, "generator regulating voltage through a reactive power control", () -> reactivePowerGenerator(true));
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
        addObjectsTheMappingMayNotDescribe(fixtures);
        addTerminalSigns(fixtures);
        return List.copyOf(fixtures.values());
    }

    /**
     * Objects the full export writes and the change mapping describes differently or not at all: a battery, fictitious
     * injections, hidden tap changers, an equivalent shunt, holders without a VoltageRegulation or not regulating.
     */
    private static void addObjectsTheMappingMayNotDescribe(Map<String, Fixture> fixtures) {
        add(fixtures, "battery", BatteryNetworkFactory::create);
        add(fixtures, "four substations, a generator without VoltageRegulation", FourSubstationsNodeBreakerFactory::create);
        add(fixtures, "fictitious injections (node/breaker)", () -> {
            Network network = Network.read(CgmesConformity1Catalog.miniNodeBreaker().dataSource());
            VoltageLevel.NodeBreakerView view = network.getVoltageLevelStream()
                    .filter(vl -> vl.getTopologyKind() == TopologyKind.NODE_BREAKER).findFirst().orElseThrow()
                    .getNodeBreakerView();
            int[] nodes = view.getNodes();
            // A consumption is written as a NonConformLoad, a production as an EnergySource
            view.setFictitiousP0(nodes[0], 10.0).setFictitiousQ0(nodes[0], 5.0);
            view.setFictitiousP0(nodes[1], -3.0).setFictitiousQ0(nodes[1], 1.0);
            return network;
        });
        add(fixtures, "fictitious injections (bus/breaker)", () -> {
            Network network = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
            List<Bus> buses = network.getBusBreakerView().getBusStream().limit(2).toList();
            buses.get(0).setFictitiousP0(10.0).setFictitiousQ0(5.0);
            buses.get(1).setFictitiousP0(-3.0).setFictitiousQ0(1.0);
            return network;
        });
        add(fixtures, "MicroGrid BE with hidden tap changers (CGMES 2.4.15)",
            () -> Network.read(CgmesConformity1ModifiedCatalog.microGridBaseCaseBEHiddenTapChangers().dataSource()));
        add(fixtures, "MicroGrid BE with a shared regulating control (CGMES 2.4.15)",
            () -> Network.read(CgmesConformity1ModifiedCatalog.microGridBaseCaseBESharedRegulatingControl().dataSource()));
        add(fixtures, "equivalent shunt without sections", () -> {
            Network network = ConversionUtil.readCgmesResources(SHUNT_DIR, SHUNT_FILES);
            network.getShuntCompensator("EquivalentShunt").setSectionCount(0);
            return network;
        });
        add(fixtures, "generator without VoltageRegulation", () -> {
            Network network = ConversionUtil.readCgmesResources(GENERATOR_DIR, GENERATOR_FILES);
            network.getGenerator(SYNCHRONOUS_MACHINE).removeVoltageRegulation();
            return network;
        });
        add(fixtures, "static var compensator without VoltageRegulation", () -> {
            Network network = ConversionUtil.readCgmesResources(SVC_DIR, SVC_FILES);
            network.getStaticVarCompensator("StaticVarCompensator-V").removeVoltageRegulation();
            return network;
        });
        add(fixtures, "converter station in voltage mode, not regulating", () -> {
            Network network = ConversionUtil.readCgmesResources(HVDC_DIR, HVDC_FILES);
            station(network, 1).getVoltageRegulation().setRegulating(false);
            return network;
        });
    }

    /**
     * One holder per family whose exported regulation target carries the sign of the regulating terminal the import
     * recorded, with that sign -1, so that a target written without it differs from the value derived from IIDM. The
     * generator of this family is the fixture "generator regulating reactive power".
     */
    private static void addTerminalSigns(Map<String, Fixture> fixtures) {
        String sign = CgmesExportUtil.getTerminalSignPropertyName("");
        add(fixtures, "static var compensator regulating reactive power, terminal sign -1", () -> {
            Network network = ConversionUtil.readCgmesResources(SVC_DIR, SVC_FILES);
            network.getStaticVarCompensator("StaticVarCompensator-Q").setProperty(sign, "-1");
            return network;
        });
        add(fixtures, "ratio tap changer regulating reactive power, terminal sign -1", () -> {
            Network network = ConversionUtil.readCgmesResources("/issues/voltageRegulation/", TRANSFORMER_FILES);
            network.getTwoWindingsTransformer("PT2_2").setProperty(sign, "-1");
            return network;
        });
        add(fixtures, "converter station regulating reactive power, terminal sign -1", () -> {
            Network network = ConversionUtil.readCgmesResources(HVDC_DIR, HVDC_FILES);
            VscConverterStation station = station(network, 2);
            VoltageRegulation regulation = station.getVoltageRegulation();
            regulation.setTerminal(station.getTerminal(), station.getRegulatingTargetV());
            regulation.setMode(RegulationMode.REACTIVE_POWER);
            regulation.setTargetValue(30.0);
            station.setProperty(sign, "-1");
            return network;
        });
        add(fixtures, "phase tap changer controlling active power, terminal sign -1", () -> {
            Network network = ConversionUtil.readCgmesResources("/update/transformer/", TRANSFORMER_FILES);
            TwoWindingsTransformer transformer = network.getTwoWindingsTransformer("T2W");
            transformer.getPhaseTapChanger().setRegulating(false)
                    .setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                    .setRegulationValue(75.0);
            transformer.setProperty(sign, "-1");
            return network;
        });
        // The update takes the previous local reactive target of a machine from its regulating target: a generator
        // regulating the reactive power of another terminal, with a local target of its own
        add(fixtures, "generator regulating the reactive power of another terminal", () -> {
            Network network = ConversionUtil.readCgmesResources(GENERATOR_DIR, GENERATOR_FILES);
            Generator generator = network.getGenerator(SYNCHRONOUS_MACHINE);
            generator.setProperty(Conversion.PROPERTY_MODE, "RegulatingControlModeKind.reactivePower");
            VoltageRegulation regulation = generator.getVoltageRegulation();
            regulation.setTerminal(network.getGenerator("ExternalNetworkInjection").getTerminal(), generator.getRegulatingTargetV());
            regulation.setMode(RegulationMode.REACTIVE_POWER);
            regulation.setTargetValue(25.0);
            // A deadband of its own, which the RegulatingControl of a generator does not carry (it is written 0)
            regulation.setTargetDeadband(0.5);
            generator.setLocalTargetQ(7.0);
            return network;
        });
    }

    private static VscConverterStation station(Network network, int side) {
        HvdcLine line = network.getHvdcLine("DCLineSegment-Vsc");
        return (VscConverterStation) (side == 1 ? line.getConverterStation1() : line.getConverterStation2());
    }

    private static final String GENERATOR_DIR = "/update/generator/";
    private static final String[] GENERATOR_FILES = {"generator_EQ.xml", "generator_SSH.xml"};
    private static final String SYNCHRONOUS_MACHINE = "SynchronousMachine";
    private static final String SHUNT_DIR = "/update/shunt-compensator/";
    private static final String[] SHUNT_FILES = {"shuntCompensator_EQ.xml", "shuntCompensator_SSH.xml"};
    private static final String SVC_DIR = "/update/static-var-compensator/";
    private static final String[] SVC_FILES = {"staticVarCompensator_EQ.xml", "staticVarCompensator_SSH.xml"};
    private static final String HVDC_DIR = "/update/hvdc/";
    private static final String[] HVDC_FILES = {"hvdc_EQ.xml", "hvdc_SSH.xml"};
    private static final String[] TRANSFORMER_FILES = {"transformer_EQ.xml", "transformer_SSH.xml"};

    /**
     * The generator of the generator fixture, given a CGMES control regulating reactive power as its import would
     * record it (mode and terminal sign). Its regulation regulates reactive power at its own terminal, or stays the
     * voltage regulation of the fixture, which then disagrees with the recorded CGMES mode.
     */
    private static Network reactivePowerGenerator(boolean regulatesVoltage) {
        Network network = ConversionUtil.readCgmesResources("/update/generator/", "generator_EQ.xml", "generator_SSH.xml");
        Generator generator = network.getGenerator("SynchronousMachine");
        generator.setProperty(Conversion.PROPERTY_MODE, "RegulatingControlModeKind.reactivePower");
        generator.setProperty(CgmesExportUtil.getTerminalSignPropertyName(""), "-1");
        if (!regulatesVoltage) {
            VoltageRegulation regulation = generator.getVoltageRegulation();
            regulation.setTerminal(generator.getTerminal(), generator.getRegulatingTargetV());
            regulation.setMode(RegulationMode.REACTIVE_POWER);
            regulation.setTargetValue(25.0);
            regulation.setRegulating(true);
        }
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
    private static final Map<String, Integer> SEAM_HITS = Collections.synchronizedMap(new TreeMap<>());

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
            checkSeam(row, line, unexplained);
            if (outcome == null) {
                List<String> refusals = facts.refusals().getOrDefault(key.subject(), List.of());
                unexplained.add(refusals.isEmpty() ? line : line + " refusals=" + refusals);
                outcome = "UNEXPLAINED";
            } else if (!EQUAL.equals(outcome) && !outcome.contains("TERMINAL") && !RDF_TYPE.equals(key.property())) {
                EXAMPLES.add(outcome + " | " + line);
            }
            counts.merge(part + " " + outcome, 1, Integer::sum);
        }
        // A value of a shared seam the full export leaves out of an object it writes is as wrong as a wrong value
        facts.seam().keySet().stream()
                .filter(key -> !full.containsKey(key) && full.containsKey(new Key(key.subject(), RDF_TYPE)))
                .forEach(key -> unexplained.add(fixture.name() + ": " + part + " " + key + " SEAM: the full export does not"
                        + " write it, the value derived from IIDM is " + facts.seam().get(key)));
    }

    /**
     * A value that goes through a shared seam has to be the one derived from IIDM, on either side: agreeing with each
     * other is not enough there.
     */
    private static void checkSeam(Row row, String line, List<String> unexplained) {
        String expected = row.facts().seam().get(row.key());
        if (expected == null) {
            return;
        }
        for (Triple side : Arrays.asList(row.full(), row.shared())) {
            if (side != null) {
                SEAM_HITS.merge(row.key().property(), 1, Integer::sum);
                if (!seamMatches(side, expected)) {
                    unexplained.add(line + " SEAM: " + (side == row.full() ? "full" : "shared") + " is not the value"
                            + " derived from IIDM, " + expected);
                }
            }
        }
    }

    static boolean seamMatches(Triple triple, String expected) {
        if (triple.value().equals(expected)) {
            return true;
        }
        try {
            double actual = Double.parseDouble(triple.value()) + 0.0;
            double wanted = Double.parseDouble(expected);
            return Double.compare(actual, wanted + 0.0) == 0
                    || Double.compare(actual, Double.parseDouble(CgmesExportUtil.format(wanted)) + 0.0) == 0;
        } catch (NumberFormatException notANumber) {
            return false;
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
            report.append("== values checked against IIDM\n");
            SEAM_HITS.forEach((property, count) -> report.append("   ").append(count).append(' ').append(property).append('\n'));
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
            Set<String> seams = new TreeSet<>(SEAM_PROPERTIES);
            seams.removeAll(SEAM_HITS.keySet());
            assertEquals(Set.of(), seams, "shared seams that no fixture checks against IIDM");
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

    /** As every statement diff compares: the kind, and a numeric literal by value ({@code -0 == 0}). */
    static String comparable(Triple triple) {
        return StatementDiff.comparable(new CgmesStatement(triple.subject(), triple.className(), triple.property(),
                triple.value(), triple.kind()));
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
            case Switch ignored -> List.of(CgmesChangeTranslator.OPEN);
            case DcSwitch ignored -> List.of(CgmesChangeTranslator.OPEN);
            case Load ignored -> List.of(CgmesChangeTranslator.P0, CgmesChangeTranslator.Q0);
            case Generator ignored -> List.of(CgmesChangeTranslator.TARGET_P, CgmesChangeTranslator.LOCAL_TARGET_Q,
                    CgmesChangeTranslator.LOCAL_TARGET_V, CgmesChangeTranslator.VR_TARGET_VALUE,
                    CgmesChangeTranslator.VR_REGULATING,
                    ActivePowerControl.NAME + "#" + CgmesChangeTranslator.PARTICIPATION_FACTOR,
                    ReferencePriorities.NAME + "#" + CgmesChangeTranslator.REFERENCE_PRIORITY);
            case BoundaryLine ignored -> List.of(CgmesChangeTranslator.P0, CgmesChangeTranslator.Q0,
                    CgmesChangeTranslator.TARGET_P, CgmesChangeTranslator.TARGET_Q, CgmesChangeTranslator.TARGET_V,
                    CgmesChangeTranslator.VOLTAGE_REGULATION_ON, CgmesChangeTranslator.R, CgmesChangeTranslator.X,
                    CgmesChangeTranslator.G, CgmesChangeTranslator.B);
            case Line ignored -> List.of(CgmesChangeTranslator.R, CgmesChangeTranslator.X, CgmesChangeTranslator.G1,
                    CgmesChangeTranslator.B1);
            case VoltageLevel ignored -> List.of(CgmesChangeTranslator.HIGH_VOLTAGE_LIMIT,
                    CgmesChangeTranslator.LOW_VOLTAGE_LIMIT);
            case ShuntCompensator ignored -> List.of(CgmesChangeTranslator.SECTION_COUNT,
                    CgmesChangeTranslator.LOCAL_TARGET_V, CgmesChangeTranslator.VR_TARGET_VALUE,
                    CgmesChangeTranslator.VR_REGULATING, CgmesChangeTranslator.VR_TARGET_DEADBAND);
            case StaticVarCompensator ignored -> List.of(CgmesChangeTranslator.LOCAL_TARGET_Q,
                    CgmesChangeTranslator.LOCAL_TARGET_V, CgmesChangeTranslator.VR_TARGET_VALUE,
                    CgmesChangeTranslator.VR_REGULATING);
            case HvdcLine ignored -> List.of(CgmesChangeTranslator.ACTIVE_POWER_SETPOINT,
                    CgmesChangeTranslator.CONVERTERS_MODE);
            case VscConverterStation ignored -> List.of(CgmesChangeTranslator.LOCAL_TARGET_Q,
                    CgmesChangeTranslator.LOCAL_TARGET_V, CgmesChangeTranslator.VR_TARGET_VALUE,
                    CgmesChangeTranslator.VR_REGULATING, CgmesChangeTranslator.VR_MODE);
            case LccConverterStation ignored -> List.of(CgmesChangeTranslator.POWER_FACTOR);
            case AcDcConverter<?> ignored -> List.of(CgmesChangeTranslator.TARGET_P, CgmesChangeTranslator.TARGET_VDC,
                    CgmesChangeTranslator.CONTROL_MODE, CgmesChangeTranslator.POWER_FACTOR,
                    CgmesChangeTranslator.LOCAL_TARGET_Q, CgmesChangeTranslator.LOCAL_TARGET_V,
                    CgmesChangeTranslator.VR_TARGET_VALUE, CgmesChangeTranslator.VR_REGULATING,
                    CgmesChangeTranslator.VR_MODE);
            case TwoWindingsTransformer ignored -> tapChangerProbes("");
            case ThreeWindingsTransformer ignored -> tapChangerProbes("1", "2", "3");
            default -> List.of();
        };
    }

    /** A phase tap changer regulates through its own attributes, a ratio tap changer through its VoltageRegulation. */
    private static List<String> tapChangerProbes(String... ends) {
        List<String> probes = new ArrayList<>();
        for (String end : ends) {
            String phase = CgmesChangeTranslator.PHASE_TAP_CHANGER_PREFIX + end;
            for (String suffix : List.of(CgmesChangeTranslator.TAP_POSITION_SUFFIX, CgmesChangeTranslator.REGULATING_SUFFIX,
                    CgmesChangeTranslator.REGULATION_VALUE_SUFFIX, CgmesChangeTranslator.TARGET_DEADBAND_SUFFIX)) {
                probes.add(phase + suffix);
            }
            String ratio = CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX + end;
            for (String suffix : List.of(CgmesChangeTranslator.TAP_POSITION_SUFFIX, "." + CgmesChangeTranslator.VR_REGULATING,
                    "." + CgmesChangeTranslator.VR_TARGET_VALUE, "." + CgmesChangeTranslator.VR_TARGET_DEADBAND)) {
                probes.add(ratio + suffix);
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
     * @param detailedLccs                  the line commutated converters of the detailed DC model
     * @param cgmesModeMismatchControls     the regulating controls, machines and GeneratingUnits of generators whose
     *                                      regulation is in another mode than the CGMES mode the import recorded, with
     *                                      the generator
     * @param unrecordedControls            the controls the full export writes under a generated identifier because the
     *                                      import recorded none (and the compensator blocks written with them), with the
     *                                      CGMES subject of the object whose refusal explains them
     * @param holdersWithoutRegulation      the blocks of holders without VoltageRegulation (and of the converters of
     *                                      their HVDC line), with the holder whose refusal explains them
     * @param unregulatedConverters         the converters of an HVDC line one of whose converters does not regulate
     * @param reactivePowerTapChangerControls the controls of ratio tap changers regulating reactive power, with the
     *                                      transformer
     * @param batteries                     the batteries
     * @param fictitiousInjections          the fictitious injections the full export writes
     * @param hiddenTapChangers             the hidden tap changers the import recorded
     * @param currentLimiterLimits          the CurrentLimit the full equipment export writes for a current limiter
     * @param voltageLimits                 the voltage level and the side of every stored VoltageLimit identifier
     * @param refusals                      the reasons of the refused probes, by CGMES subject
     * @param refusedSlots                  loading limit slots without a stored CGMES identifier, which the full export
     *                                      writes and the shared mapping cannot
     * @param expectedShared                the value the shared mapping has to write where it deliberately differs from
     *                                      the full export, derived here from the IIDM objects as the import reads the
     *                                      property back
     * @param seam                          the value of every property of {@link #SEAM_PROPERTIES} both sides have to
     *                                      write, derived from the IIDM objects without the seams
     */
    private record Facts(Set<String> branchSwitches, Set<String> branchSwitchTerminals, Set<String> dcSwitchTerminals,
                         Set<String> generatedEquivalentInjections,
                         Set<String> detailedLccs,
                         Map<String, String> cgmesModeMismatchControls, Map<String, String> unrecordedControls,
                         Map<String, String> holdersWithoutRegulation, Map<String, String> unregulatedConverters,
                         Map<String, String> reactivePowerTapChangerControls, Set<String> batteries,
                         Set<String> fictitiousInjections, Set<String> hiddenTapChangers,
                         Set<String> currentLimiterLimits,
                         Map<String, VoltageLimitRef> voltageLimits, Map<String, List<String>> refusals,
                         Set<String> refusedSlots, Map<Key, String> expectedShared,
                         Map<Key, String> seam) {

        record VoltageLimitRef(String voltageLevelId, boolean high) {
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

        /** Whether a probe of the object behind this CGMES subject was refused with a reason containing the phrase. */
        boolean refusedFor(String subject, String phrase) {
            return refusals.getOrDefault(subject, List.of()).stream().anyMatch(refusal -> refusal.contains(phrase));
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
         * The values the shared mapping has to write where it deliberately differs from the full export, derived from
         * the IIDM objects independently of the mapping.
         */
        private static void expectations(Network network, CgmesExportContext context, Map<Key, String> expected) {
            NamingStrategy naming = context.getNamingStrategy();
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
            // An EquivalentBranch states the impedance of both directions, IIDM holds one
            for (Line line : network.getLines()) {
                equivalentBranchValues(line, line.getR(), line.getX(), context, expected);
            }
            for (BoundaryLine boundaryLine : network.getBoundaryLines(BoundaryLineFilter.ALL)) {
                equivalentBranchValues(boundaryLine, boundaryLine.getR(), boundaryLine.getX(), context, expected);
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
            for (BoundaryLine boundaryLine : network.getBoundaryLines(BoundaryLineFilter.ALL)) {
                String injection = id(naming.getCgmesIdFromProperty(boundaryLine, Conversion.PROPERTY_EQUIVALENT_INJECTION), context);
                if (!boundaryLine.hasProperty(Conversion.PROPERTY_EQUIVALENT_INJECTION)) {
                    generatedEquivalentInjections.add(injection);
                }
            }
            Set<String> detailedLccs = new HashSet<>();
            network.getLineCommutatedConverters().forEach(lcc -> detailedLccs.add(id(naming.getCgmesId(lcc), context)));
            Map<String, String> cgmesModeMismatchControls = new HashMap<>();
            for (Generator generator : network.getGenerators()) {
                String cgmesMode = generator.getProperty(Conversion.PROPERTY_MODE);
                VoltageRegulation regulation = generator.getVoltageRegulation();
                if (cgmesMode != null && regulation != null && regulation.getMode() != null
                        && generator.hasProperty(Conversion.PROPERTY_REGULATING_CONTROL)
                        && (regulation.getMode() == RegulationMode.REACTIVE_POWER
                            ? !RegulatingControlMapping.isControlModeReactivePower(cgmesMode)
                            : !RegulatingControlMapping.isControlModeVoltage(cgmesMode))) {
                    String subject = id(naming.getCgmesId(generator), context);
                    cgmesModeMismatchControls.put(
                            id(naming.getCgmesIdFromProperty(generator, Conversion.PROPERTY_REGULATING_CONTROL), context),
                            subject);
                    cgmesModeMismatchControls.put(subject, subject);
                    if (generator.hasProperty(Conversion.PROPERTY_GENERATING_UNIT)) {
                        cgmesModeMismatchControls.put(id(naming.getCgmesIdFromProperty(generator,
                                Conversion.PROPERTY_GENERATING_UNIT), context), subject);
                    }
                }
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
            expectations(network, context, expectedShared);
            Map<String, List<String>> refusals = new HashMap<>();
            shared.refusals().forEach((iidmId, reasons) ->
                    refusals.put(id(naming.getCgmesId(network.getIdentifiable(iidmId)), context), reasons));
            return new Facts(branchSwitches, branchSwitchTerminals, dcSwitchTerminals, generatedEquivalentInjections,
                    detailedLccs,
                    cgmesModeMismatchControls, unrecordedControls(network, context), holdersWithoutRegulation(network, context),
                    unregulatedConverters(network, context), reactivePowerTapChangerControls(network, context),
                    batteries(network, context), fictitiousInjections(network, context), hiddenTapChangers(network, context),
                    currentLimiterLimits, voltageLimits, refusals, new HashSet<>(),
                    expectedShared, seamExpectations(network, context));
        }

        /**
         * What the shared seams have to give, computed from the getters of the IIDM holders, never through
         * {@code RegulationRef} or the full export: the regulating controls that one equipment uses alone (a shared
         * control combines its users), the converter controls and the reactive power of converters and static var
         * compensators.
         */
        private static Map<Key, String> seamExpectations(Network network, CgmesExportContext context) {
            NamingStrategy naming = context.getNamingStrategy();
            Map<String, List<Map<String, String>>> controls = new HashMap<>();
            for (Generator generator : network.getGenerators()) {
                if (!CgmesNames.EQUIVALENT_INJECTION.equals(generator.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS))) {
                    // Load sign convention in CGMES, and the sign of the regulating terminal the import recorded
                    holderControl(generator, holderControlId(generator, context), false, -terminalSign(generator, ""),
                            context.isExportGeneratorsInLocalRegulationMode(), controls);
                }
            }
            for (ShuntCompensator shunt : network.getShuntCompensators()) {
                if (!"true".equals(shunt.getProperty(Conversion.PROPERTY_IS_EQUIVALENT_SHUNT))) {
                    holderControl(shunt, holderControlId(shunt, context), true, 1, false, controls);
                }
            }
            for (StaticVarCompensator svc : network.getStaticVarCompensators()) {
                holderControl(svc, holderControlId(svc, context), false, terminalSign(svc, ""), false, controls);
            }
            for (TwoWindingsTransformer transformer : network.getTwoWindingsTransformers()) {
                transformer.getOptionalRatioTapChanger().ifPresent(rtc -> holderControl(rtc,
                        tapChangerControlId(transformer, CgmesExportUtil.tapChangerAliasType(transformer,
                                Conversion.ALIAS_RATIO_TAP_CHANGER1, Conversion.ALIAS_RATIO_TAP_CHANGER2),
                                CgmesObjectReference.Part.RATIO_TAP_CHANGER, 1, context),
                        true, terminalSign(transformer, ""), false, controls));
                String phaseAlias = CgmesExportUtil.tapChangerAliasType(transformer,
                        Conversion.ALIAS_PHASE_TAP_CHANGER1, Conversion.ALIAS_PHASE_TAP_CHANGER2);
                transformer.getOptionalPhaseTapChanger().ifPresent(ptc -> phaseTapChangerControl(ptc,
                        tapChangerControlId(transformer, phaseAlias, CgmesObjectReference.Part.PHASE_TAP_CHANGER, 1, context),
                        terminalSign(transformer, ""),
                        controlIdOf(transformer, phaseAlias, context) != null && !context.isExportEquipment(), controls));
            }
            for (ThreeWindingsTransformer transformer : network.getThreeWindingsTransformers()) {
                for (ThreeWindingsTransformer.Leg leg : transformer.getLegs()) {
                    String end = Integer.toString(leg.getSide().getNum());
                    leg.getOptionalRatioTapChanger().ifPresent(rtc -> holderControl(rtc,
                            tapChangerControlId(transformer, CgmesExportUtil.getRatioTapChangerAliasType(end),
                                    CgmesObjectReference.Part.RATIO_TAP_CHANGER, leg.getSide().getNum(), context),
                            true, terminalSign(transformer, end), false, controls));
                    String phaseAlias = CgmesExportUtil.getPhaseTapChangerAliasType(end);
                    leg.getOptionalPhaseTapChanger().ifPresent(ptc -> phaseTapChangerControl(ptc,
                            tapChangerControlId(transformer, phaseAlias, CgmesObjectReference.Part.PHASE_TAP_CHANGER,
                                    leg.getSide().getNum(), context),
                            terminalSign(transformer, end),
                            controlIdOf(transformer, phaseAlias, context) != null && !context.isExportEquipment(), controls));
                }
            }
            Map<Key, String> seam = new HashMap<>();
            controls.forEach((controlId, users) -> {
                if (users.size() == 1 && users.get(0) != null) {
                    users.get(0).forEach((property, value) -> seam.put(new Key(id(controlId, context), property), value));
                }
            });
            // The simplified DC model: the import reads the reactive power target as -terminalSign * targetQpcc
            for (VscConverterStation station : network.getVscConverterStations()) {
                String subject = id(naming.getCgmesId(station), context);
                boolean regulatesVoltage = station.isRegulatingWithMode(RegulationMode.VOLTAGE);
                seam.put(new Key(subject, "VsConverter.qPccControl"), regulatesVoltage ? VOLTAGE_PCC : REACTIVE_PCC);
                seam.put(new Key(subject, "VsConverter.targetQpcc"), String.valueOf(regulatesVoltage ? 0.0
                        : -terminalSign(station, "") * station.getRegulatingTargetQ()));
                seam.put(new Key(subject, "VsConverter.targetUpcc"), String.valueOf(
                        station.isWithMode(RegulationMode.VOLTAGE) ? station.getRegulatingTargetV() : 0.0));
                seam.put(new Key(subject, "ACDCConverter.q"), String.valueOf(-station.getLocalTargetQ()));
            }
            // The detailed DC model states the targets of the mode it is in, and its reactive power as it is
            for (VoltageSourceConverter converter : network.getVoltageSourceConverters()) {
                String subject = id(naming.getCgmesId(converter), context);
                boolean voltageMode = converter.isWithMode(RegulationMode.VOLTAGE);
                seam.put(new Key(subject, "VsConverter.qPccControl"), voltageMode ? VOLTAGE_PCC : REACTIVE_PCC);
                seam.put(new Key(subject, "VsConverter.targetQpcc"), String.valueOf(
                        converter.isWithMode(RegulationMode.REACTIVE_POWER) ? converter.getRegulatingTargetQ() : 0.0));
                seam.put(new Key(subject, "VsConverter.targetUpcc"), String.valueOf(
                        voltageMode ? converter.getRegulatingTargetV() : 0.0));
                seam.put(new Key(subject, "ACDCConverter.q"), String.valueOf(converter.getLocalTargetQ()));
            }
            for (StaticVarCompensator svc : network.getStaticVarCompensators()) {
                seam.put(new Key(id(naming.getCgmesId(svc), context), "StaticVarCompensator.q"),
                        String.valueOf(svc.getLocalTargetQ()));
            }
            return seam;
        }

        /**
         * The RegulatingControl of one voltage regulation holder: its flag, a deadband only for a discrete holder, the
         * regulating target of the mode, a reactive power one multiplied by the given factor (sign convention and
         * terminal sign), and the multiplier of the quantity.
         */
        private static void holderControl(VoltageRegulationHolder<?> holder, String controlId, boolean discrete,
                                          double reactiveFactor, boolean localVoltage,
                                          Map<String, List<Map<String, String>>> controls) {
            VoltageRegulation regulation = holder.getVoltageRegulation();
            if (controlId == null || regulation == null || regulation.getMode() == null) {
                return;
            }
            Map<String, String> values = new HashMap<>();
            values.put("RegulatingControl.enabled", String.valueOf(regulation.isRegulating()));
            values.put("RegulatingControl.discrete", String.valueOf(discrete));
            putDeadband(values, discrete ? regulation.getTargetDeadband() : 0.0);
            if (regulation.getMode() == RegulationMode.REACTIVE_POWER) {
                values.put(TARGET_VALUE, String.valueOf(reactiveFactor * holder.getRegulatingTargetQ()));
                values.put(MULTIPLIER, "UnitMultiplier.M");
            } else {
                values.put(TARGET_VALUE, String.valueOf(localVoltage ? holder.getLocalTargetV() : holder.getRegulatingTargetV()));
                values.put(MULTIPLIER, "UnitMultiplier.k");
            }
            controls.computeIfAbsent(controlId, id -> new ArrayList<>()).add(values);
        }

        /**
         * The TapChangerControl of a phase tap changer: controlling active power, its regulation value with the
         * terminal sign of its end; limiting current, the current it has (no sign, multiplier none) when the import
         * recorded the control and the SSH is read against that equipment model, else zeros.
         */
        private static void phaseTapChangerControl(PhaseTapChanger ptc, String controlId, int terminalSign,
                                                   boolean realCurrentLimit, Map<String, List<Map<String, String>>> controls) {
            if (!ptc.hasLoadTapChangingCapabilities() || ptc.getRegulationMode() == null) {
                return;
            }
            Map<String, String> values = new HashMap<>();
            values.put("RegulatingControl.discrete", "true");
            if (ptc.getRegulationMode() == PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL) {
                values.put("RegulatingControl.enabled", String.valueOf(ptc.isRegulating()));
                putDeadband(values, ptc.getTargetDeadband());
                values.put(TARGET_VALUE, String.valueOf(terminalSign * ptc.getRegulationValue()));
                values.put(MULTIPLIER, "UnitMultiplier.M");
            } else if (realCurrentLimit) {
                values.put("RegulatingControl.enabled", String.valueOf(ptc.isRegulating()));
                putDeadband(values, ptc.getTargetDeadband());
                values.put(TARGET_VALUE, String.valueOf(ptc.getRegulationValue()));
                values.put(MULTIPLIER, "UnitMultiplier.none");
            } else {
                values.put("RegulatingControl.enabled", "false");
                values.put("RegulatingControl.targetDeadband", "0.0");
                values.put(TARGET_VALUE, "0.0");
                values.put(MULTIPLIER, "UnitMultiplier.M");
            }
            controls.computeIfAbsent(controlId, id -> new ArrayList<>()).add(values);
        }

        private static void putDeadband(Map<String, String> values, double deadband) {
            // A deadband that is not a number or negative is not written at all
            if (!Double.isNaN(deadband) && deadband >= 0) {
                values.put("RegulatingControl.targetDeadband", String.valueOf(deadband));
            }
        }

        private static String holderControlId(Identifiable<?> holder, CgmesExportContext context) {
            return context.getNamingStrategy().getCgmesIdFromProperty(holder, Conversion.PROPERTY_REGULATING_CONTROL);
        }

        private static <C extends Connectable<C>> String tapChangerControlId(C transformer, String aliasType,
                                                                             CgmesObjectReference.Part part, int end,
                                                                             CgmesExportContext context) {
            return CgmesExportUtil.getTapChangerControlId(transformer, part, end,
                    transformer.getAliasFromType(aliasType).orElse(null), context);
        }

        /** The sign of the regulating terminal the import recorded; 1 when none was recorded. */
        private static int terminalSign(Identifiable<?> identifiable, String end) {
            String sign = identifiable.getProperty(CgmesExportUtil.getTerminalSignPropertyName(end));
            return sign == null ? 1 : Integer.parseInt(sign);
        }

        /**
         * The controls the full export generates an identifier for: of a tap changer, as {@code writeTapChanger} names
         * them, and of a holder without the property of a recorded control; the block of a compensator is written with
         * its control, so it is refused with it.
         */
        private static Map<String, String> unrecordedControls(Network network, CgmesExportContext context) {
            Map<String, String> generated = new HashMap<>();
            List<Identifiable<?>> holders = new ArrayList<>(network.getGeneratorStream()
                    .filter(g -> !CgmesNames.EQUIVALENT_INJECTION.equals(g.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS)))
                    .toList());
            holders.addAll(network.getShuntCompensatorStream()
                    .filter(s -> !"true".equals(s.getProperty(Conversion.PROPERTY_IS_EQUIVALENT_SHUNT))).toList());
            holders.addAll(network.getStaticVarCompensatorStream().toList());
            for (Identifiable<?> holder : holders) {
                if (!holder.hasProperty(Conversion.PROPERTY_REGULATING_CONTROL)
                        && ((VoltageRegulationHolder<?>) holder).getVoltageRegulation() != null) {
                    String subject = id(context.getNamingStrategy().getCgmesId(holder), context);
                    generated.put(id(holderControlId(holder, context), context), subject);
                    if (!(holder instanceof Generator)) {
                        generated.put(subject, subject);
                    }
                }
            }
            for (TwoWindingsTransformer transformer : network.getTwoWindingsTransformers()) {
                if (transformer.hasPhaseTapChanger()) {
                    addGenerated(transformer, CgmesExportUtil.tapChangerAliasType(transformer, Conversion.ALIAS_PHASE_TAP_CHANGER1,
                            Conversion.ALIAS_PHASE_TAP_CHANGER2), CgmesObjectReference.Part.PHASE_TAP_CHANGER, 1, context, generated);
                }
                if (transformer.hasRatioTapChanger()) {
                    addGenerated(transformer, CgmesExportUtil.tapChangerAliasType(transformer, Conversion.ALIAS_RATIO_TAP_CHANGER1,
                            Conversion.ALIAS_RATIO_TAP_CHANGER2), CgmesObjectReference.Part.RATIO_TAP_CHANGER, 1, context, generated);
                }
            }
            for (ThreeWindingsTransformer transformer : network.getThreeWindingsTransformers()) {
                for (ThreeWindingsTransformer.Leg leg : transformer.getLegs()) {
                    int end = leg.getSide().getNum();
                    if (leg.hasPhaseTapChanger()) {
                        addGenerated(transformer, CgmesExportUtil.getPhaseTapChangerAliasType(Integer.toString(end)),
                                CgmesObjectReference.Part.PHASE_TAP_CHANGER, end, context, generated);
                    }
                    if (leg.hasRatioTapChanger()) {
                        addGenerated(transformer, CgmesExportUtil.getRatioTapChangerAliasType(Integer.toString(end)),
                                CgmesObjectReference.Part.RATIO_TAP_CHANGER, end, context, generated);
                    }
                }
            }
            return generated;
        }

        private static <C extends Connectable<C>> void addGenerated(C transformer, String aliasType, CgmesObjectReference.Part part,
                                                                    int end, CgmesExportContext context, Map<String, String> generated) {
            if (controlIdOf(transformer, aliasType, context) == null) {
                String tapChangerId = transformer.getAliasFromType(aliasType).orElse(null);
                generated.put(id(CgmesExportUtil.getTapChangerControlId(transformer, part, end, tapChangerId, context), context),
                        id(context.getNamingStrategy().getCgmesId(transformer), context));
            }
        }

        private static Map<String, String> holdersWithoutRegulation(Network network, CgmesExportContext context) {
            NamingStrategy naming = context.getNamingStrategy();
            Map<String, String> holders = new HashMap<>();
            for (Generator generator : network.getGenerators()) {
                if (generator.getVoltageRegulation() == null) {
                    String subject = id(naming.getCgmesId(generator), context);
                    holders.put(subject, subject);
                    // The GeneratingUnit of the machine is refused with it
                    if (generator.hasProperty(Conversion.PROPERTY_GENERATING_UNIT)) {
                        holders.put(id(naming.getCgmesIdFromProperty(generator, Conversion.PROPERTY_GENERATING_UNIT),
                                context), subject);
                    }
                }
            }
            for (ShuntCompensator shunt : network.getShuntCompensators()) {
                if (shunt.getVoltageRegulation() == null) {
                    holders.put(id(naming.getCgmesId(shunt), context), id(naming.getCgmesId(shunt), context));
                }
            }
            for (StaticVarCompensator svc : network.getStaticVarCompensators()) {
                if (svc.getVoltageRegulation() == null) {
                    holders.put(id(naming.getCgmesId(svc), context), id(naming.getCgmesId(svc), context));
                }
            }
            convertersOfLines(network, context, station -> station.getVoltageRegulation() == null, holders);
            for (VoltageSourceConverter converter : network.getVoltageSourceConverters()) {
                if (converter.getVoltageRegulation() == null) {
                    holders.put(id(naming.getCgmesId(converter), context), id(naming.getCgmesId(converter), context));
                }
            }
            return holders;
        }

        private static Map<String, String> unregulatedConverters(Network network, CgmesExportContext context) {
            Map<String, String> converters = new HashMap<>();
            convertersOfLines(network, context, station -> station.getVoltageRegulation() != null
                    && !station.getVoltageRegulation().isRegulating(), converters);
            for (VoltageSourceConverter converter : network.getVoltageSourceConverters()) {
                if (converter.getVoltageRegulation() != null && !converter.getVoltageRegulation().isRegulating()) {
                    String subject = id(context.getNamingStrategy().getCgmesId(converter), context);
                    converters.put(subject, subject);
                }
            }
            return converters;
        }

        /** Both converter stations of every HVDC line one of whose VSC stations meets the condition. */
        private static void convertersOfLines(Network network, CgmesExportContext context,
                                              java.util.function.Predicate<VscConverterStation> condition,
                                              Map<String, String> converters) {
            for (HvdcLine line : network.getHvdcLines()) {
                List<HvdcConverterStation<?>> stations = List.of(line.getConverterStation1(), line.getConverterStation2());
                if (stations.stream().anyMatch(s -> s instanceof VscConverterStation vsc && condition.test(vsc))) {
                    stations.forEach(station -> {
                        String subject = id(context.getNamingStrategy().getCgmesId(station), context);
                        converters.put(subject, subject);
                    });
                }
            }
        }

        private static Map<String, String> reactivePowerTapChangerControls(Network network, CgmesExportContext context) {
            Map<String, String> controls = new HashMap<>();
            for (TwoWindingsTransformer transformer : network.getTwoWindingsTransformers()) {
                transformer.getOptionalRatioTapChanger().filter(Facts::regulatesReactivePower).ifPresent(rtc -> controls.put(
                        id(tapChangerControlId(transformer, CgmesExportUtil.tapChangerAliasType(transformer,
                                Conversion.ALIAS_RATIO_TAP_CHANGER1, Conversion.ALIAS_RATIO_TAP_CHANGER2),
                                CgmesObjectReference.Part.RATIO_TAP_CHANGER, 1, context), context),
                        id(context.getNamingStrategy().getCgmesId(transformer), context)));
            }
            for (ThreeWindingsTransformer transformer : network.getThreeWindingsTransformers()) {
                for (ThreeWindingsTransformer.Leg leg : transformer.getLegs()) {
                    int end = leg.getSide().getNum();
                    leg.getOptionalRatioTapChanger().filter(Facts::regulatesReactivePower).ifPresent(rtc -> controls.put(
                            id(tapChangerControlId(transformer, CgmesExportUtil.getRatioTapChangerAliasType(Integer.toString(end)),
                                    CgmesObjectReference.Part.RATIO_TAP_CHANGER, end, context), context),
                            id(context.getNamingStrategy().getCgmesId(transformer), context)));
                }
            }
            return controls;
        }

        private static boolean regulatesReactivePower(RatioTapChanger rtc) {
            return rtc.getVoltageRegulation() != null && rtc.getVoltageRegulation().getMode() == RegulationMode.REACTIVE_POWER;
        }

        private static Set<String> batteries(Network network, CgmesExportContext context) {
            Set<String> batteries = new HashSet<>();
            network.getBatteries().forEach(battery -> batteries.add(id(context.getNamingStrategy().getCgmesId(battery), context)));
            return batteries;
        }

        /** Named as {@code SteadyStateHypothesisExport} names them: per node of a node/breaker level, else per bus. */
        private static Set<String> fictitiousInjections(Network network, CgmesExportContext context) {
            NamingStrategy naming = context.getNamingStrategy();
            Set<String> injections = new HashSet<>();
            for (VoltageLevel voltageLevel : network.getVoltageLevels()) {
                if (voltageLevel.getTopologyKind() == TopologyKind.NODE_BREAKER && !context.isBusBranchExport()) {
                    VoltageLevel.NodeBreakerView view = voltageLevel.getNodeBreakerView();
                    for (int node : view.getNodes()) {
                        injections.add(id(naming.getCgmesId(CgmesObjectReference.refTyped(voltageLevel),
                                CgmesObjectReference.Part.FICTITIOUS, ref("NCL"), ref(node)), context));
                    }
                } else {
                    voltageLevel.getBusBreakerView().getBuses().forEach(bus -> injections.add(id(naming.getCgmesId(
                            CgmesObjectReference.refTyped(bus), CgmesObjectReference.Part.FICTITIOUS, ref("NCL")), context)));
                }
            }
            return injections;
        }

        private static Set<String> hiddenTapChangers(Network network, CgmesExportContext context) {
            Set<String> hidden = new HashSet<>();
            network.getConnectableStream().forEach(connectable -> {
                CgmesTapChangers<?> tapChangers = (CgmesTapChangers<?>) connectable.getExtension(CgmesTapChangers.class);
                if (tapChangers != null) {
                    tapChangers.getTapChangers().stream().filter(CgmesTapChanger::isHidden).forEach(tapChanger ->
                            hidden.add(id(context.getNamingStrategy().getCgmesId(tapChanger.getId()), context)));
                }
            });
            return hidden;
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
