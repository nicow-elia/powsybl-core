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
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.cgmes.model.diff.StatementDiff;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import com.powsybl.commons.util.Result;
import com.powsybl.commons.xml.XmlUtil;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ReferencePriority;
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
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;
import java.util.function.Supplier;

import static com.powsybl.cgmes.conversion.naming.CgmesObjectReference.ref;
import static com.powsybl.cgmes.conversion.naming.CgmesObjectReference.refTyped;
import static com.powsybl.cgmes.model.CgmesNamespace.RDF_NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the full exports write is what the IIDM network says, value by value, and what only they write is known.
 *
 * <p>Since the full steady state hypothesis export describes every object through the families of the mapping, and
 * the full equipment export reads its limit values and impedances through them, comparing the full export with the
 * mapping would compare the mapping with itself. Each value is therefore compared with a value this test derives from
 * the IIDM object itself, from its own getters, never through the mapping or the export: the setpoints and flags of
 * every injection, the blocks of tap changers, compensators and converters, the regulating controls (target, mode,
 * multiplier, deadband, the recorded {@code CGMES.terminalSign}), switches, terminals, control areas, and of the
 * equipment profile the loading limit values, the voltage limits and the impedances. A value without such an
 * expectation fails, unless {@link #NOT_DERIVED} lists why it has none.</p>
 *
 * <p>The second test is the inventory of what only a full export writes: every object and property of the full
 * steady state hypothesis that the description of the network by the mapping ({@code CgmesChangeTranslator#describe},
 * which the change exports and the in-place import read) does not produce is explained by a rule of
 * {@link #ONLY_IN_FULL_EXPORT}, mostly by the refusal the mapping gives the object. Every rule of both tables has to be
 * exercised by a fixture.</p>
 *
 * <p>This test replaces the equivalence guard of the full export and the change mapping ({@code
 * ExportMappingEquivalenceTest}, P0 T3), whose IIDM-derived expectations of the shared seams it extends to every
 * value.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class FullExportExpectationTest {

    private static final String RDF_TYPE = CgmesStatement.RDF_TYPE;
    private static final Set<String> LIMIT_CLASSES = Set.of("CurrentLimit", "ActivePowerLimit", "ApparentPowerLimit");
    private static final String CONTROL_ENABLED = "RegulatingCondEq.controlEnabled";
    private static final String CONNECTED = "ACDCTerminal.connected";
    private static final String TARGET_VALUE = "RegulatingControl.targetValue";
    private static final String MULTIPLIER = "RegulatingControl.targetValueUnitMultiplier";
    private static final String VOLTAGE_PCC = "VsQpccControlKind.voltagePcc";
    private static final String REACTIVE_PCC = "VsQpccControlKind.reactivePcc";

    /** What a rule sees of one row of the full export, and what the fixture knows. */
    private record Row(Key key, Triple full, Facts facts) {
        String className() {
            return full.className();
        }
    }

    @FunctionalInterface
    private interface RowPredicate {
        boolean test(Row row);
    }

    /**
     * A property the full export writes and the mapping does not describe.
     *
     * @param classNames the classes of the subject in the full export
     * @param properties the properties concerned, empty for every property of such a subject (its rdf:type included)
     * @param when       the narrower condition
     */
    private record OnlyInFull(String id, Set<String> classNames, Set<String> properties, RowPredicate when, String reason) {
        boolean matches(Row row) {
            return classNames.contains(row.className())
                    && (properties.isEmpty() || properties.contains(row.key().property())) && when.test(row);
        }
    }

    /** A value of the full export this test derives no expectation for, with the reason. */
    private record NotDerived(String id, Set<String> properties, RowPredicate when, String reason) {
    }

    private static final List<NotDerived> NOT_DERIVED = List.of(
        new NotDerived("SHARED_CONTROL", Set.of(TARGET_VALUE, "RegulatingControl.enabled", "RegulatingControl.discrete",
                "RegulatingControl.targetDeadband", MULTIPLIER),
            row -> row.facts().sharedControls().contains(row.key().subject()),
            "a RegulatingControl several holders regulate through combines their regulations (RegulatingControlView,"
                + " the order of the users decides); the expectation is derived for a control of one user"),
        new NotDerived("CONDENSER_OPERATING_MODE", Set.of("SynchronousMachine.operatingMode"),
            row -> row.facts().zeroActivePowerMachines().contains(row.key().subject()),
            "a machine without active power is a generator, a motor or a condenser according to its reactive limits,"
                + " its regulation and the kind its import recorded; derived for a machine producing or consuming"));

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
     * Whether the subject of the row is one the facts name, and the mapping refused a probe of the IIDM object
     * behind it for one of the given reasons: any other reason leaves the row unexplained.
     */
    private static boolean refusedAs(Row row, Map<String, String> subjects, List<String> reasons) {
        String refusedObject = subjects.get(row.key().subject());
        return refusedObject != null && reasons.stream().anyMatch(reason -> row.facts().refusedFor(refusedObject, reason));
    }

    private static final List<OnlyInFull> ONLY_IN_FULL_EXPORT = List.of(
        new OnlyInFull("TERMINAL_CONNECTED", Set.of(CgmesNames.TERMINAL), Set.of(),
            row -> !row.facts().branchSwitchTerminals().contains(row.key().subject()),
            "COVERAGE GAP, not an impossibility: IIDM holds Terminal.isConnected() and the full export maps it one to"
                + " one, but the connection status of a terminal is not a supported change (docs: partial SSH export,"
                + " supported changes); the mapping writes ACDCTerminal.connected only for the two terminals of a"
                + " switch imported from a CGMES branch class"),
        new OnlyInFull("DC_TERMINAL_CONNECTED", Set.of(CgmesNames.DC_TERMINAL), Set.of(),
            row -> !row.facts().dcSwitchTerminals().contains(row.key().subject()),
            "COVERAGE GAP: the connection status of a DC terminal (DcTerminal.isConnected()) is only mapped for the two"
                + " terminals of a DcSwitch"),
        new OnlyInFull("ACDC_CONVERTER_DC_TERMINAL_CONNECTED", Set.of("ACDCConverterDCTerminal"), Set.of(), row -> true,
            "COVERAGE GAP: a converter DC terminal has no mapped IIDM change; the full export writes it always"
                + " connected (simplified model), or from DcTerminal.isConnected() (detailed model)"),
        new OnlyInFull("CONTROL_AREA", Set.of("ControlArea"), Set.of(), row -> true,
            "a change of the interchange target describes the ControlArea, but the description of the network the"
                + " in-place import completes a group from does not name areas (open point of P2)"),
        new OnlyInFull("CS_CONVERTER_CONSTANTS", Set.of(CgmesNames.CS_CONVERTER),
            Set.of("CsConverter.targetAlpha", "CsConverter.targetGamma", "CsConverter.targetIdc"), row -> true,
            "constant 0 written by the full export; IIDM holds no such attribute"),
        new OnlyInFull("VS_CONVERTER_CONSTANTS", Set.of(CgmesNames.VS_CONVERTER),
            Set.of("VsConverter.droop", "VsConverter.droopCompensation", "VsConverter.qShare"), row -> true,
            "constant 0 written by the full export; IIDM holds no such attribute"),
        new OnlyInFull("GENERATED_EQUIVALENT_INJECTION", Set.of(CgmesNames.EQUIVALENT_INJECTION), Set.of(),
            row -> row.facts().generatedEquivalentInjections().contains(row.key().subject()),
            "the boundary line carries no CGMES.EquivalentInjection, so the full export writes one under a generated"
                + " identifier; the mapping only describes objects the receiver already holds"),
        new OnlyInFull("CGMES_MODE_MISMATCH", CGMES_MODE_CLASSES, Set.of(),
            row -> row.facts().cgmesModeMismatchControls().containsKey(row.key().subject())
                && row.facts().refusedFor(row.facts().cgmesModeMismatchControls().get(row.key().subject()),
                    "recorded at import"),
            "the regulation of the generator is in another mode than the CGMES mode its import recorded, by which the"
                + " CGMES update reads the RegulatingControl on every update of the machine: the mapping refuses"
                + " the control, the machine and its GeneratingUnit (rule cgmes-mode, a changesOnly refusal: the"
                + " receiver would read the target as the other quantity), the full export writes them from the IIDM"
                + " mode (docs: Partial SSH export / Limitations)"),
        new OnlyInFull("NO_RECORDED_CONTROL", Set.of("TapChangerControl", "RegulatingControl", CgmesNames.STATIC_VAR_COMPENSATOR,
                "LinearShuntCompensator", "NonlinearShuntCompensator"), Set.of(),
            row -> refusedAs(row, row.facts().unrecordedControls(), NO_RECORDED_CONTROL_REFUSALS),
            "the import recorded no control of the tap changer or the holder (or the network was not imported from"
                + " CGMES), so the full export writes one under a generated identifier; the mapping only"
                + " describes objects the receiver already holds and refuses the regulation, and with it the block of"
                + " a compensator it is written with (rules no-control, ptc-no-terminal; a changesOnly refusal)"),
        new OnlyInFull("HOLDER_WITHOUT_REGULATION", HOLDER_CLASSES, Set.of(),
            row -> refusedAs(row, row.facts().holdersWithoutRegulation(),
                List.of("has no VoltageRegulation, but the CGMES update gives it one")),
            "the holder (or a converter of its HVDC line) has no VoltageRegulation, but the CGMES update gives it one"
                + " from its RegulatingControl or its qPccControl: the mapping refuses every block of it (rule"
                + " import-gives-regulation, a changesOnly refusal), the full export writes its block and no control"
                + " (upstream: a holder without regulation gets no RegulatingControl)"),
        new OnlyInFull("CONVERTER_NOT_REGULATING", Set.of(CgmesNames.VS_CONVERTER), Set.of(),
            row -> refusedAs(row, row.facts().unregulatedConverters(),
                List.of("does not regulate, and a VsConverter has no control flag")),
            "a converter of the HVDC line does not regulate, and a VsConverter has no control flag: the mapping"
                + " refuses the blocks of both converters (rule vsc-no-control-flag, a changesOnly refusal), the full"
                + " export writes them"),
        new OnlyInFull("RTC_REACTIVE_POWER_CONTROL", Set.of("TapChangerControl"), Set.of(),
            row -> refusedAs(row, row.facts().reactivePowerTapChangerControls(),
                List.of("only writes the voltage regulation of ratio tap changers")),
            "the control of a ratio tap changer regulating reactive power: the mapping refuses it (rule"
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
                + " no such tap changer, no change names it")
    );

    // ---------------------------------------------------------------------------------------------------------------
    // The tests
    // ---------------------------------------------------------------------------------------------------------------

    private static final Map<String, Integer> RULE_HITS = Collections.synchronizedMap(new TreeMap<>());
    private static final Set<String> VALUES_RUN = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> INVENTORY_RUN = Collections.synchronizedSet(new HashSet<>());

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void theFullExportWritesWhatIidmSays(Fixture fixture) {
        Network network = fixture.loader().get();
        CgmesExportContext context = new CgmesExportContext(network);
        String cimNamespace = context.getCim().getNamespace();
        Facts facts = Facts.of(network, context, Map.of());
        Map<Key, String> expected = Facts.expectations(network, context);
        List<String> problems = new ArrayList<>();
        Map<Key, Triple> ssh = parse(fullSsh(network), cimNamespace);
        for (Triple triple : ssh.values()) {
            if (RDF_TYPE.equals(triple.property())) {
                continue;
            }
            Key key = new Key(triple.subject(), triple.property());
            String value = expected.get(key);
            if (value == null) {
                Row row = new Row(key, triple, facts);
                Optional<NotDerived> rule = NOT_DERIVED.stream()
                        .filter(notDerived -> notDerived.properties().contains(key.property()) && notDerived.when().test(row))
                        .findFirst();
                rule.ifPresentOrElse(notDerived -> RULE_HITS.merge(notDerived.id(), 1, Integer::sum),
                    () -> problems.add("SSH " + describe(triple) + ": no value derived from IIDM"));
            } else if (!matches(triple, value)) {
                problems.add("SSH " + describe(triple) + ": IIDM says " + value);
            }
        }
        // A value a shared helper writes that the full export leaves out of an object it writes is as wrong as a wrong value
        expected.keySet().stream()
                .filter(key -> CONTROLS_AND_CONVERTERS.contains(key.property()) && !ssh.containsKey(key)
                        && ssh.containsKey(new Key(key.subject(), RDF_TYPE)))
                .forEach(key -> problems.add("SSH " + key + ": not written, IIDM says " + expected.get(key)));

        // The equipment values the full equipment export reads through the mapping
        CgmesExportContext eqContext = new CgmesExportContext(network).setExportEquipment(true);
        Map<Key, String> expectedEq = Facts.equipmentExpectations(network, eqContext);
        int eqValues = 0;
        for (Triple triple : parse(fullEq(network, eqContext), cimNamespace).values()) {
            if (isEquipmentValue(triple)) {
                eqValues++;
                String value = expectedEq.get(new Key(triple.subject(), triple.property()));
                if (value == null || !matches(triple, value)) {
                    problems.add("EQ " + describe(triple) + ": IIDM says " + value);
                }
            }
        }
        VALUES_RUN.add(fixture.name());
        int checked = eqValues;
        assertTrue(problems.isEmpty(), () -> fixture.name() + " (" + checked + " equipment values):\n" + String.join("\n", problems));
    }

    /** The properties a helper shared by the full export and the change mapping writes, which have to be written. */
    private static final Set<String> CONTROLS_AND_CONVERTERS = Set.of(TARGET_VALUE, "RegulatingControl.enabled",
            "RegulatingControl.discrete", MULTIPLIER, "VsConverter.targetQpcc", "VsConverter.targetUpcc",
            "VsConverter.qPccControl", "ACDCConverter.q", "StaticVarCompensator.q");

    /** A value of the equipment profile the full equipment export reads through the mapping. */
    private static boolean isEquipmentValue(Triple triple) {
        return LIMIT_CLASSES.contains(triple.className()) && triple.property().matches("[A-Za-z]+Limit\\.(value|normalValue)")
                || CgmesNames.AC_LINE_SEGMENT.equals(triple.className()) && triple.property().matches("ACLineSegment\\.(r|x|gch|bch)")
                || CgmesNames.VOLTAGE_LEVEL.equals(triple.className()) && triple.property().matches("VoltageLevel\\.(high|low)VoltageLimit");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void whatOnlyTheFullExportWritesIsKnown(Fixture fixture) {
        Network network = fixture.loader().get();
        CgmesExportContext context = new CgmesExportContext(network);
        Set<Key> described = new HashSet<>();
        Map<String, List<String>> refusals = new HashMap<>();
        describe(network, context, described, refusals);
        Facts facts = Facts.of(network, context, refusals);
        List<String> unexplained = new ArrayList<>();
        for (Triple triple : parse(fullSsh(network), context.getCim().getNamespace()).values()) {
            Key key = new Key(triple.subject(), triple.property());
            if (described.contains(key)) {
                continue;
            }
            Row row = new Row(key, triple, facts);
            ONLY_IN_FULL_EXPORT.stream().filter(rule -> rule.matches(row)).findFirst().ifPresentOrElse(
                rule -> RULE_HITS.merge(rule.id(), 1, Integer::sum),
                () -> unexplained.add(describe(triple) + " refusals=" + refusals.getOrDefault(key.subject(), List.of())));
        }
        INVENTORY_RUN.add(fixture.name());
        assertTrue(unexplained.isEmpty(), () -> fixture.name() + ": the full export writes what the mapping does not"
                + " describe, and no rule explains it:\n" + String.join("\n", unexplained));
    }

    /**
     * What the mapping describes about every object of the network as it stands, in the scope of a change: the
     * steady state statements (an {@code rdf:type} row for each subject), and the refusals by CGMES subject.
     */
    private static void describe(Network network, CgmesExportContext context, Set<Key> described,
                                 Map<String, List<String>> refusals) {
        CgmesChangeTranslator translator = new CgmesChangeTranslator(network, context,
                PartialSshExport.UnsupportedChangeBehavior.IGNORE, "an expectation test",
                EnumSet.of(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS), IidmStateView.LIVE, null);
        for (Identifiable<?> identifiable : network.getIdentifiables()) {
            for (Result<CgmesPropertyBuffer, String> block : translator.describe(identifiable, "")) {
                switch (block) {
                    case Result.Success(CgmesPropertyBuffer buffer) ->
                        buffer.statements(CgmesSubset.STEADY_STATE_HYPOTHESIS, context).forEach(statement -> {
                            described.add(new Key(statement.subjectId(), statement.property()));
                            described.add(new Key(statement.subjectId(), RDF_TYPE));
                        });
                    case Result.Failure(String reason) -> refusals.computeIfAbsent(
                            Facts.id(context.getNamingStrategy().getCgmesId(identifiable), context), id -> new ArrayList<>()).add(reason);
                }
            }
        }
    }

    /**
     * A value equals the expected one literally, or as numbers: as written, as the export formats it, or to a relative
     * 1e-6, the precision of the loss factors IIDM holds as floats, from which the powers of an inverter are derived.
     */
    static boolean matches(Triple triple, String expected) {
        if (triple.value().equals(expected)) {
            return true;
        }
        try {
            double actual = Double.parseDouble(triple.value()) + 0.0;
            double wanted = Double.parseDouble(CgmesExportUtil.format(Double.parseDouble(expected))) + 0.0;
            return Double.compare(actual, Double.parseDouble(expected) + 0.0) == 0 || Double.compare(actual, wanted) == 0
                    || Math.abs(actual - wanted) <= 1e-6 * Math.abs(wanted);
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    private static String describe(Triple triple) {
        return triple.subject() + " " + triple.className() + "." + triple.property() + "=" + triple.value();
    }

    @AfterAll
    static void everyRuleIsExercised() {
        if (VALUES_RUN.size() == fixtures().size() && INVENTORY_RUN.size() == fixtures().size()) {
            // Only when the whole class ran: a rule no fixture exercises is a rule nobody tests
            Set<String> rules = new TreeSet<>();
            ONLY_IN_FULL_EXPORT.forEach(rule -> rules.add(rule.id()));
            NOT_DERIVED.forEach(rule -> rules.add(rule.id()));
            rules.removeAll(RULE_HITS.keySet());
            assertEquals(Set.of(), rules, "table entries that no fixture exercises");
        }
    }

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
    // What IIDM says, and what the rules need to know about a fixture
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The CGMES identifiers the rules condition on, computed with the naming strategy of the export.
     *
     * @param branchSwitchTerminals         the terminals of the IIDM switches the import created from a CGMES branch class
     * @param dcSwitchTerminals             the two terminals of every DcSwitch
     * @param generatedEquivalentInjections the EquivalentInjection identifiers the full export generates for boundary
     *                                      lines that carry none
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
     * @param refusals                      the reasons the mapping refused a block of an object for, by CGMES subject
     * @param sharedControls                the regulating controls several holders regulate through
     * @param zeroActivePowerMachines       the machines (generators and batteries) without active power target
     */
    private record Facts(Set<String> branchSwitchTerminals, Set<String> dcSwitchTerminals,
                         Set<String> generatedEquivalentInjections, Map<String, String> cgmesModeMismatchControls,
                         Map<String, String> unrecordedControls, Map<String, String> holdersWithoutRegulation,
                         Map<String, String> unregulatedConverters, Map<String, String> reactivePowerTapChangerControls,
                         Set<String> batteries, Set<String> fictitiousInjections, Set<String> hiddenTapChangers,
                         Map<String, List<String>> refusals, Set<String> sharedControls, Set<String> zeroActivePowerMachines) {

        /** Whether the mapping refused a block of the object behind this CGMES subject with a reason containing the phrase. */
        boolean refusedFor(String subject, String phrase) {
            return refusals.getOrDefault(subject, List.of()).stream().anyMatch(refusal -> refusal.contains(phrase));
        }

        static Facts of(Network network, CgmesExportContext context, Map<String, List<String>> refusals) {
            NamingStrategy naming = context.getNamingStrategy();
            Set<String> branchSwitchTerminals = new HashSet<>();
            for (Switch sw : network.getSwitches()) {
                if (SwitchAndTerminalFamily.isCgmesBranchClass(sw.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS))) {
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
                if (!boundaryLine.hasProperty(Conversion.PROPERTY_EQUIVALENT_INJECTION)) {
                    generatedEquivalentInjections.add(id(naming.getCgmesIdFromProperty(boundaryLine,
                            Conversion.PROPERTY_EQUIVALENT_INJECTION), context));
                }
            }
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
            Map<String, Integer> controlUsers = new HashMap<>();
            List<Identifiable<?>> holders = new ArrayList<>(network.getGeneratorStream().toList());
            holders.addAll(network.getShuntCompensatorStream().toList());
            holders.addAll(network.getStaticVarCompensatorStream().toList());
            holders.stream().filter(holder -> holder.hasProperty(Conversion.PROPERTY_REGULATING_CONTROL))
                    .forEach(holder -> controlUsers.merge(id(holderControlId(holder, context), context), 1, Integer::sum));
            network.getConnectableStream().forEach(connectable -> {
                CgmesTapChangers<?> tapChangers = (CgmesTapChangers<?>) connectable.getExtension(CgmesTapChangers.class);
                if (tapChangers != null) {
                    tapChangers.getTapChangers().stream().filter(tapChanger -> tapChanger.getControlId() != null && !tapChanger.isHidden())
                            .forEach(tapChanger -> controlUsers.merge(id(naming.getCgmesId(tapChanger.getControlId()), context), 1, Integer::sum));
                }
            });
            Set<String> sharedControls = new HashSet<>();
            controlUsers.forEach((control, users) -> {
                if (users > 1) {
                    sharedControls.add(control);
                }
            });
            Set<String> zeroActivePowerMachines = new HashSet<>();
            network.getGenerators().forEach(generator -> {
                if (generator.getTargetP() == 0) {
                    zeroActivePowerMachines.add(id(naming.getCgmesId(generator), context));
                }
            });
            network.getBatteries().forEach(battery -> {
                if (battery.getTargetP() == 0) {
                    zeroActivePowerMachines.add(id(naming.getCgmesId(battery), context));
                }
            });
            return new Facts(branchSwitchTerminals, dcSwitchTerminals, generatedEquivalentInjections,
                    cgmesModeMismatchControls, unrecordedControls(network, context), holdersWithoutRegulation(network, context),
                    unregulatedConverters(network, context), reactivePowerTapChangerControls(network, context),
                    batteries(network, context), fictitiousInjections(network, context), hiddenTapChangers(network, context),
                    refusals, sharedControls, zeroActivePowerMachines);
        }

        /**
         * Every value of the full steady state hypothesis export, derived from the getters of the IIDM objects: the
         * shared seams (regulating controls, converter controls, the reactive power of converters and compensators)
         * and the rest of every block. A key may be derived for a class the export does not write the object with
         * (the three load families): only the written properties are compared.
         */
        static Map<Key, String> expectations(Network network, CgmesExportContext context) {
            NamingStrategy naming = context.getNamingStrategy();
            Map<Key, String> expected = new HashMap<>(seamExpectations(network, context));
            for (Load load : network.getLoads()) {
                String subject = id(naming.getCgmesId(load), context);
                // The load convention of CGMES is the one of IIDM
                for (String p : List.of("EnergyConsumer.p", "EnergySource.activePower", "RotatingMachine.p")) {
                    expected.put(new Key(subject, p), String.valueOf(load.getP0()));
                }
                for (String q : List.of("EnergyConsumer.q", "EnergySource.reactivePower", "RotatingMachine.q")) {
                    expected.put(new Key(subject, q), String.valueOf(load.getQ0()));
                }
                expected.put(new Key(subject, CONTROL_ENABLED), "false");
                expected.put(new Key(subject, "AsynchronousMachine.asynchronousMachineType"),
                        "AsynchronousMachineKind." + (load.getP0() < 0 ? "generator" : "motor"));
            }
            fictitiousInjectionValues(network, context, expected);
            for (Generator generator : network.getGenerators()) {
                String subject = id(naming.getCgmesId(generator), context);
                VoltageRegulation regulation = generator.getVoltageRegulation();
                String regulating = String.valueOf(regulation != null && regulation.isRegulating());
                // The generator convention of IIDM, the load convention of CGMES
                machine(expected, subject, regulating, -generator.getTargetP(), -generator.getLocalTargetQ(),
                        ReferencePriority.get(generator), generator.getTargetP());
                expected.put(new Key(subject, "EquivalentInjection.p"), String.valueOf(-generator.getTargetP()));
                expected.put(new Key(subject, "EquivalentInjection.q"), String.valueOf(-generator.getLocalTargetQ()));
                expected.put(new Key(subject, "EquivalentInjection.regulationStatus"), regulating);
                // A target that is not a number is written as 0 (owner decision O4)
                expected.put(new Key(subject, "EquivalentInjection.regulationTarget"), String.valueOf(generator.getLocalTargetV()));
                participationFactor(generator, context, expected);
            }
            for (Battery battery : network.getBatteries()) {
                String subject = id(naming.getCgmesId(battery), context);
                VoltageRegulation regulation = battery.getVoltageRegulation();
                machine(expected, subject, String.valueOf(regulation != null && regulation.isRegulating()),
                        -battery.getTargetP(), -battery.getRegulatingTargetQ(), ReferencePriority.get(battery), battery.getTargetP());
                participationFactor(battery, context, expected);
            }
            for (BoundaryLine boundaryLine : network.getBoundaryLines(BoundaryLineFilter.ALL)) {
                // The fixed injection and the generation of the boundary in one injection of the load convention; the
                // first boundary line naming an EquivalentInjection writes it
                String subject = id(naming.getCgmesIdFromProperty(boundaryLine, Conversion.PROPERTY_EQUIVALENT_INJECTION), context);
                BoundaryLine.Generation generation = boundaryLine.getGeneration();
                double targetP = generation == null ? 0 : zeroIfNaN(generation.getTargetP());
                double targetQ = generation == null ? 0 : zeroIfNaN(generation.getTargetQ());
                expected.putIfAbsent(new Key(subject, "EquivalentInjection.p"), String.valueOf(zeroIfNaN(boundaryLine.getP0()) - targetP));
                expected.putIfAbsent(new Key(subject, "EquivalentInjection.q"), String.valueOf(zeroIfNaN(boundaryLine.getQ0()) - targetQ));
                expected.putIfAbsent(new Key(subject, "EquivalentInjection.regulationStatus"),
                        String.valueOf(generation != null && generation.isVoltageRegulationOn()));
                expected.putIfAbsent(new Key(subject, "EquivalentInjection.regulationTarget"),
                        String.valueOf(generation == null ? Double.NaN : generation.getTargetV()));
            }
            tapChangerValues(network, context, expected);
            for (ShuntCompensator shunt : network.getShuntCompensators()) {
                String subject = id(naming.getCgmesId(shunt), context);
                expected.put(new Key(subject, "ShuntCompensator.sections"), String.valueOf(shunt.getSectionCount()));
                expected.put(new Key(subject, CONTROL_ENABLED),
                        String.valueOf(shunt.getVoltageRegulation() != null && shunt.getVoltageRegulation().isRegulating()));
            }
            for (StaticVarCompensator svc : network.getStaticVarCompensators()) {
                expected.put(new Key(id(naming.getCgmesId(svc), context), CONTROL_ENABLED),
                        String.valueOf(svc.getVoltageRegulation() != null && svc.getVoltageRegulation().isRegulating()));
            }
            converterValues(network, context, expected);
            switchAndTerminalValues(network, context, expected);
            for (Area area : network.getAreas()) {
                String subject = id(naming.getCgmesId(area.getId()), context);
                expected.put(new Key(subject, "ControlArea.netInterchange"),
                        String.valueOf(area.getInterchangeTarget().orElse(Double.NaN)));
                if (area.hasProperty("pTolerance")) {
                    expected.put(new Key(subject, "ControlArea.pTolerance"), area.getProperty("pTolerance"));
                }
            }
            return expected;
        }

        private static double zeroIfNaN(double value) {
            return Double.isNaN(value) ? 0 : value;
        }

        /** The block of a SynchronousMachine, or of an ExternalNetworkInjection, in the load convention. */
        private static void machine(Map<Key, String> expected, String subject, String regulating, double p, double q,
                                    int referencePriority, double targetP) {
            expected.put(new Key(subject, CONTROL_ENABLED), regulating);
            expected.put(new Key(subject, "RotatingMachine.p"), String.valueOf(p));
            expected.put(new Key(subject, "RotatingMachine.q"), String.valueOf(q));
            expected.put(new Key(subject, "SynchronousMachine.referencePriority"), String.valueOf(referencePriority));
            expected.put(new Key(subject, "ExternalNetworkInjection.p"), String.valueOf(p));
            expected.put(new Key(subject, "ExternalNetworkInjection.q"), String.valueOf(q));
            expected.put(new Key(subject, "ExternalNetworkInjection.referencePriority"), String.valueOf(referencePriority));
            if (targetP != 0) {
                expected.put(new Key(subject, "SynchronousMachine.operatingMode"),
                        "SynchronousMachineOperatingMode." + (targetP > 0 ? "generator" : "motor"));
            }
        }

        /** The participation factor of the GeneratingUnit of an injection: the last one naming the unit writes it. */
        @SuppressWarnings("unchecked")
        private static <I extends Injection<I>> void participationFactor(I injection, CgmesExportContext context, Map<Key, String> expected) {
            ActivePowerControl<I> activePowerControl = injection.getExtension(ActivePowerControl.class);
            if (activePowerControl != null || injection.hasProperty(Conversion.PROPERTY_NORMAL_PF)) {
                expected.put(new Key(id(context.getNamingStrategy().getCgmesIdFromProperty(injection, Conversion.PROPERTY_GENERATING_UNIT),
                        context), "GeneratingUnit.normalPF"), activePowerControl != null
                        ? String.valueOf(activePowerControl.getParticipationFactor())
                        : injection.getProperty(Conversion.PROPERTY_NORMAL_PF));
            }
        }

        /** The fixed injections of nodes and buses, written as an EnergySource when they produce, else a NonConformLoad. */
        private static void fictitiousInjectionValues(Network network, CgmesExportContext context, Map<Key, String> expected) {
            NamingStrategy naming = context.getNamingStrategy();
            for (VoltageLevel voltageLevel : network.getVoltageLevels()) {
                if (voltageLevel.getTopologyKind() == TopologyKind.NODE_BREAKER && !context.isBusBranchExport()) {
                    VoltageLevel.NodeBreakerView view = voltageLevel.getNodeBreakerView();
                    for (int node : view.getNodes()) {
                        fictitiousInjection(expected, id(naming.getCgmesId(refTyped(voltageLevel), CgmesObjectReference.Part.FICTITIOUS,
                                        ref("NCL"), ref(node)), context),
                                id(naming.getCgmesId(refTyped(voltageLevel), CgmesObjectReference.Part.FICTITIOUS,
                                        CgmesObjectReference.Part.TERMINAL, ref(node)), context),
                                view.getFictitiousP0(node), view.getFictitiousQ0(node));
                    }
                } else {
                    for (Bus bus : voltageLevel.getBusBreakerView().getBuses()) {
                        fictitiousInjection(expected, id(naming.getCgmesId(refTyped(bus), CgmesObjectReference.Part.FICTITIOUS,
                                        ref("NCL")), context),
                                id(naming.getCgmesId(refTyped(bus), CgmesObjectReference.Part.FICTITIOUS,
                                        CgmesObjectReference.Part.TERMINAL), context),
                                bus.getFictitiousP0(), bus.getFictitiousQ0());
                    }
                }
            }
        }

        private static void fictitiousInjection(Map<Key, String> expected, String subject, String terminal, double p, double q) {
            expected.put(new Key(subject, "EnergyConsumer.p"), String.valueOf(p));
            expected.put(new Key(subject, "EnergyConsumer.q"), String.valueOf(q));
            expected.put(new Key(subject, "EnergySource.activePower"), String.valueOf(p));
            expected.put(new Key(subject, "EnergySource.reactivePower"), String.valueOf(q));
            expected.put(new Key(terminal, CONNECTED), "true");
        }

        /** The step and the control flag of every tap changer, and the recorded step of a hidden one. */
        private static void tapChangerValues(Network network, CgmesExportContext context, Map<Key, String> expected) {
            NamingStrategy naming = context.getNamingStrategy();
            for (TwoWindingsTransformer transformer : network.getTwoWindingsTransformers()) {
                transformer.getOptionalRatioTapChanger().ifPresent(rtc -> tapChanger(expected, id(naming.getCgmesIdFromAlias(transformer,
                        CgmesExportUtil.tapChangerAliasType(transformer, Conversion.ALIAS_RATIO_TAP_CHANGER1,
                                Conversion.ALIAS_RATIO_TAP_CHANGER2)), context), rtc.getTapPosition(), regulates(rtc)));
                transformer.getOptionalPhaseTapChanger().ifPresent(ptc -> tapChanger(expected, id(naming.getCgmesIdFromAlias(transformer,
                        CgmesExportUtil.tapChangerAliasType(transformer, Conversion.ALIAS_PHASE_TAP_CHANGER1,
                                Conversion.ALIAS_PHASE_TAP_CHANGER2)), context), ptc.getTapPosition(), ptc.isRegulating()));
            }
            for (ThreeWindingsTransformer transformer : network.getThreeWindingsTransformers()) {
                for (ThreeWindingsTransformer.Leg leg : transformer.getLegs()) {
                    String end = Integer.toString(leg.getSide().getNum());
                    leg.getOptionalRatioTapChanger().ifPresent(rtc -> tapChanger(expected, id(naming.getCgmesIdFromAlias(transformer,
                            CgmesExportUtil.getRatioTapChangerAliasType(end)), context), rtc.getTapPosition(), regulates(rtc)));
                    leg.getOptionalPhaseTapChanger().ifPresent(ptc -> tapChanger(expected, id(naming.getCgmesIdFromAlias(transformer,
                            CgmesExportUtil.getPhaseTapChangerAliasType(end)), context), ptc.getTapPosition(), ptc.isRegulating()));
                }
            }
            network.getConnectableStream().forEach(connectable -> {
                CgmesTapChangers<?> tapChangers = (CgmesTapChangers<?>) connectable.getExtension(CgmesTapChangers.class);
                if (tapChangers != null) {
                    tapChangers.getTapChangers().stream().filter(CgmesTapChanger::isHidden).forEach(tapChanger -> tapChanger(expected,
                            id(naming.getCgmesId(tapChanger.getId()), context), tapChanger.getStep().orElse(0), false));
                }
            });
        }

        /** A ratio tap changer regulates through its VoltageRegulation. */
        private static boolean regulates(RatioTapChanger rtc) {
            return rtc.getVoltageRegulation() != null && rtc.getVoltageRegulation().isRegulating();
        }

        private static void tapChanger(Map<Key, String> expected, String subject, int step, boolean regulating) {
            expected.put(new Key(subject, "TapChanger.step"), String.valueOf(step));
            expected.put(new Key(subject, "TapChanger.controlEnabled"), String.valueOf(regulating));
        }

        /**
         * The setpoints and the control kinds of the converters of both DC models, and the constants of their classes.
         * In the simplified model the HVDC line holds the power of the link: the rectifier states it, the inverter the
         * DC voltage at its end and the power it delivers, after the losses of the line and of both stations.
         */
        private static void converterValues(Network network, CgmesExportContext context, Map<Key, String> expected) {
            NamingStrategy naming = context.getNamingStrategy();
            for (HvdcConverterStation<?> station : network.getHvdcConverterStations()) {
                HvdcLine line = station.getHvdcLine();
                if (line == null) {
                    continue;
                }
                String subject = id(naming.getCgmesId(station), context);
                boolean rectifier = line.getConvertersMode() == HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER
                        ? station == line.getConverterStation1() : station == line.getConverterStation2();
                double setpoint = line.getActivePowerSetpoint();
                double p;
                if (rectifier) {
                    p = setpoint;
                    expected.put(new Key(subject, "ACDCConverter.targetPpcc"), String.valueOf(setpoint));
                    expected.put(new Key(subject, "ACDCConverter.targetUdc"), "0.0");
                } else {
                    HvdcConverterStation<?> other = station == line.getConverterStation1() ? line.getConverterStation2() : line.getConverterStation1();
                    double dcRectifier = setpoint * (1 - other.getLossFactor() / 100);
                    double current = dcRectifier / line.getNominalV();
                    double dcInverter = -(dcRectifier - line.getR() * current * current);
                    p = dcInverter + station.getLossFactor() / 100 * Math.abs(dcInverter);
                    expected.put(new Key(subject, "ACDCConverter.targetPpcc"), "0.0");
                    expected.put(new Key(subject, "ACDCConverter.targetUdc"), String.valueOf(line.getNominalV() - line.getR() * current));
                }
                if (station instanceof LccConverterStation lcc) {
                    double powerFactor = lcc.getPowerFactor();
                    expected.put(new Key(subject, "ACDCConverter.p"), String.valueOf(p));
                    expected.put(new Key(subject, "ACDCConverter.q"), String.valueOf(powerFactor == 0 ? 0
                            : Math.abs(p * Math.sqrt((1 - powerFactor * powerFactor) / (powerFactor * powerFactor)))));
                    csConverter(expected, subject, rectifier ? "rectifier" : "inverter", rectifier ? "activePower" : "dcVoltage");
                } else {
                    // A VsConverter states the flow of its regulating terminal as its active power
                    expected.put(new Key(subject, "ACDCConverter.p"),
                            String.valueOf(((VscConverterStation) station).getRegulatingTerminal().getP()));
                    vsConverter(expected, subject, rectifier ? "pPcc" : "udc");
                }
            }
            for (VoltageSourceConverter converter : network.getVoltageSourceConverters()) {
                String subject = detailedConverter(converter, context, expected);
                vsConverter(expected, subject, converter.getControlMode() == AcDcConverter.ControlMode.P_PCC ? "pPcc" : "udc");
            }
            for (LineCommutatedConverter converter : network.getLineCommutatedConverters()) {
                // The full export writes the flow of the PCC terminal of a detailed line commutated converter as its
                // powers, which the import reads back as the power factor (B4, owner decision O2b, open)
                String subject = detailedConverter(converter, context, expected);
                expected.put(new Key(subject, "ACDCConverter.q"), String.valueOf(converter.getPccTerminal().getQ()));
                boolean activePower = converter.getControlMode() == AcDcConverter.ControlMode.P_PCC;
                csConverter(expected, subject, activePower && converter.getTargetP() > 0 ? "rectifier" : "inverter",
                        activePower ? "activePower" : "dcVoltage");
            }
        }

        /** The setpoints of a converter of the detailed model: the target of the quantity it controls, zero for the other. */
        private static String detailedConverter(AcDcConverter<?> converter, CgmesExportContext context, Map<Key, String> expected) {
            String subject = id(context.getNamingStrategy().getCgmesId(converter), context);
            expected.put(new Key(subject, "ACDCConverter.targetPpcc"), String.valueOf(
                    converter.getControlMode() == AcDcConverter.ControlMode.P_PCC ? converter.getTargetP() : 0.0));
            expected.put(new Key(subject, "ACDCConverter.targetUdc"), String.valueOf(
                    converter.getControlMode() == AcDcConverter.ControlMode.V_DC ? converter.getTargetVdc() : 0.0));
            expected.put(new Key(subject, "ACDCConverter.p"), String.valueOf(converter.getPccTerminal().getP()));
            return subject;
        }

        private static void csConverter(Map<Key, String> expected, String subject, String operatingMode, String pPccControl) {
            expected.put(new Key(subject, "CsConverter.operatingMode"), "CsOperatingModeKind." + operatingMode);
            expected.put(new Key(subject, "CsConverter.pPccControl"), "CsPpccControlKind." + pPccControl);
            // Constants of the class IIDM has no attribute for
            for (String constant : List.of("CsConverter.targetAlpha", "CsConverter.targetGamma", "CsConverter.targetIdc")) {
                expected.put(new Key(subject, constant), "0.0");
            }
        }

        private static void vsConverter(Map<Key, String> expected, String subject, String pPccControl) {
            expected.put(new Key(subject, "VsConverter.pPccControl"), "VsPpccControlKind." + pPccControl);
            // Constants of the class IIDM has no attribute for
            for (String constant : List.of("VsConverter.droop", "VsConverter.droopCompensation", "VsConverter.qShare")) {
                expected.put(new Key(subject, constant), "0.0");
            }
        }

        /**
         * The open state of the switches and the connection status of every terminal the full export writes: of the
         * equipment as IIDM holds it, always connected for the terminals of a switch (whose open state carries the
         * status) and of the boundary, of a fictitious injection, of a recorded busbar section and of the DC side of the
         * simplified model; the terminals of a branch the import made a switch, and of a DC switch, carry its state.
         */
        private static void switchAndTerminalValues(Network network, CgmesExportContext context, Map<Key, String> expected) {
            NamingStrategy naming = context.getNamingStrategy();
            for (Connectable<?> connectable : network.getConnectables()) {
                for (Terminal terminal : connectable.getTerminals()) {
                    expected.put(new Key(id(CgmesExportUtil.getTerminalId(terminal, context), context), CONNECTED),
                            String.valueOf(terminal.isConnected()));
                }
                // An equivalent shunt has no sections in the steady state: none is a disconnected terminal
                if (connectable instanceof ShuntCompensator shunt && "true".equals(shunt.getProperty(Conversion.PROPERTY_IS_EQUIVALENT_SHUNT))
                        && shunt.getSectionCount() == 0) {
                    expected.put(new Key(id(CgmesExportUtil.getTerminalId(shunt.getTerminal(), context), context), CONNECTED), "false");
                }
            }
            for (Switch sw : network.getSwitches()) {
                boolean branch = SwitchAndTerminalFamily.isCgmesBranchClass(sw.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS));
                expected.put(new Key(id(naming.getCgmesId(sw), context), "Switch.open"), String.valueOf(sw.isOpen()));
                for (String alias : List.of(Conversion.ALIAS_TERMINAL1, Conversion.ALIAS_TERMINAL2)) {
                    expected.put(new Key(id(naming.getCgmesIdFromAlias(sw, alias), context), CONNECTED),
                            String.valueOf(!branch || !sw.isOpen()));
                }
            }
            for (BoundaryLine boundaryLine : network.getBoundaryLines(BoundaryLineFilter.ALL)) {
                expected.put(new Key(id(naming.getCgmesIdFromProperty(boundaryLine, Conversion.PROPERTY_EQUIVALENT_INJECTION_TERMINAL),
                        context), CONNECTED), "true");
                expected.put(new Key(id(CgmesExportUtil.getBoundaryLineBoundaryTerminalId(boundaryLine, context), context), CONNECTED), "true");
            }
            for (Bus bus : network.getBusBreakerView().getBuses()) {
                String terminals = bus.getProperty(Conversion.PROPERTY_BUSBAR_SECTION_TERMINALS, "");
                for (String terminal : terminals.isEmpty() ? new String[0] : terminals.split(",")) {
                    expected.put(new Key(id(terminal, context), CONNECTED), "true");
                }
            }
            for (HvdcLine line : network.getHvdcLines()) {
                for (HvdcConverterStation<?> station : List.of(line.getConverterStation1(), line.getConverterStation2())) {
                    for (String alias : List.of(Conversion.ALIAS_DC_TERMINAL1, Conversion.ALIAS_DC_TERMINAL2)) {
                        expected.put(new Key(id(naming.getCgmesIdFromAlias(station, alias), context), CONNECTED), "true");
                    }
                }
                for (String alias : List.of(Conversion.ALIAS_DC_TERMINAL1, Conversion.ALIAS_DC_TERMINAL2)) {
                    expected.put(new Key(id(naming.getCgmesIdFromAlias(line, alias), context), CONNECTED), "true");
                }
                for (String ground : List.of("1G", "2G")) {
                    expected.put(new Key(id(naming.getCgmesId(refTyped(line), CgmesObjectReference.Part.DC_TERMINAL, ref(ground)),
                            context), CONNECTED), "true");
                }
            }
            for (DcConnectable<?> dcConnectable : network.getDcConnectables()) {
                for (DcTerminal dcTerminal : dcConnectable.getDcTerminals()) {
                    expected.put(new Key(id(CgmesExportUtil.getDcTerminalId(dcTerminal, context), context), CONNECTED),
                            String.valueOf(dcTerminal.isConnected()));
                }
            }
            for (DcSwitch dcSwitch : network.getDcSwitches()) {
                for (String alias : List.of(Conversion.ALIAS_DC_TERMINAL1, Conversion.ALIAS_DC_TERMINAL2)) {
                    expected.put(new Key(id(naming.getCgmesIdFromAlias(dcSwitch, alias), context), CONNECTED),
                            String.valueOf(!dcSwitch.isOpen()));
                }
            }
        }

        /**
         * The equipment values the full equipment export reads through the mapping, derived from the IIDM objects: every
         * line and boundary line as an ACLineSegment (r, x and the total of both halves of the shunt admittance), the
         * voltage limits of a voltage level, and every value of every set of loading limits under the identifier the
         * export gives it (CGMES 2.4.15 {@code .value}, CGMES 3 {@code .normalValue}); the current a phase tap changer
         * limits is a CurrentLimit of the terminal it regulates.
         */
        static Map<Key, String> equipmentExpectations(Network network, CgmesExportContext context) {
            NamingStrategy naming = context.getNamingStrategy();
            Map<Key, String> expected = new HashMap<>();
            for (Line line : network.getLines()) {
                impedance(expected, id(naming.getCgmesId(line), context), line.getR(), line.getX(),
                        line.getG1() + line.getG2(), line.getB1() + line.getB2());
            }
            for (BoundaryLine boundaryLine : network.getBoundaryLines(BoundaryLineFilter.ALL)) {
                impedance(expected, id(naming.getCgmesId(boundaryLine), context), boundaryLine.getR(), boundaryLine.getX(),
                        boundaryLine.getG(), boundaryLine.getB());
            }
            // A switch the import made of a CGMES branch is written as that branch, without impedance
            for (Switch sw : network.getSwitches()) {
                if (SwitchAndTerminalFamily.isCgmesBranchClass(sw.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS))) {
                    impedance(expected, id(naming.getCgmesId(sw), context), 0, 0, 0, 0);
                }
            }
            for (VoltageLevel voltageLevel : network.getVoltageLevels()) {
                String subject = id(naming.getCgmesId(voltageLevel), context);
                expected.put(new Key(subject, "VoltageLevel.highVoltageLimit"), String.valueOf(voltageLevel.getHighVoltageLimit()));
                expected.put(new Key(subject, "VoltageLevel.lowVoltageLimit"), String.valueOf(voltageLevel.getLowVoltageLimit()));
            }
            String property = context.getCimVersion() == 16 ? ".value" : ".normalValue";
            for (Identifiable<?> identifiable : network.getIdentifiables()) {
                Map<Terminal, Collection<OperationalLimitsGroup>> sides = new LinkedHashMap<>();
                switch (identifiable) {
                    case TieLine ignored -> { /* its halves are boundary lines, which carry the limits */ }
                    case ThreeWindingsTransformer transformer ->
                        transformer.getLegs().forEach(leg -> sides.put(leg.getTerminal(), leg.getOperationalLimitsGroups()));
                    case Branch<?> branch -> {
                        sides.put(branch.getTerminal1(), branch.getOperationalLimitsGroups1());
                        sides.put(branch.getTerminal2(), branch.getOperationalLimitsGroups2());
                    }
                    case BoundaryLine boundaryLine -> sides.put(boundaryLine.getTerminal(), boundaryLine.getOperationalLimitsGroups());
                    default -> { }
                }
                sides.forEach((terminal, groups) -> groups.forEach(group -> {
                    String setId = EquipmentExport.operationalLimitSetId(group, CgmesExportUtil.getTerminalId(terminal, context), context);
                    List<LoadingLimits> all = new ArrayList<>();
                    group.getActivePowerLimits().ifPresent(all::add);
                    group.getApparentPowerLimits().ifPresent(all::add);
                    group.getCurrentLimits().ifPresent(all::add);
                    for (LoadingLimits limits : all) {
                        String className = LoadingLimitEq.loadingLimitClassName(limits);
                        expected.put(new Key(id(EquipmentExport.operationalLimitId(setId, className, -1, context), context),
                                className + property), String.valueOf(limits.getPermanentLimit()));
                        limits.getTemporaryLimits().forEach(temporaryLimit -> expected.put(new Key(id(EquipmentExport.operationalLimitId(
                                setId, className, temporaryLimit.getAcceptableDuration(), context), context), className + property),
                                String.valueOf(temporaryLimit.getValue())));
                    }
                }));
            }
            network.getTwoWindingsTransformers().forEach(transformer -> transformer.getOptionalPhaseTapChanger()
                    .ifPresent(ptc -> currentLimiter(ptc, property, context, expected)));
            network.getThreeWindingsTransformers().forEach(transformer -> transformer.getLegs().forEach(leg ->
                    leg.getOptionalPhaseTapChanger().ifPresent(ptc -> currentLimiter(ptc, property, context, expected))));
            return expected;
        }

        private static void impedance(Map<Key, String> expected, String subject, double r, double x, double gch, double bch) {
            expected.put(new Key(subject, "ACLineSegment.r"), String.valueOf(r));
            expected.put(new Key(subject, "ACLineSegment.x"), String.valueOf(x));
            expected.put(new Key(subject, "ACLineSegment.gch"), String.valueOf(gch));
            expected.put(new Key(subject, "ACLineSegment.bch"), String.valueOf(bch));
        }

        private static void currentLimiter(PhaseTapChanger ptc, String property, CgmesExportContext context, Map<Key, String> expected) {
            if (isCurrentLimiter(ptc) && ptc.getRegulationTerminal() != null) {
                expected.put(new Key(currentLimiterLimitId(ptc, context), "CurrentLimit" + property),
                        String.valueOf(ptc.getRegulationValue()));
            }
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
