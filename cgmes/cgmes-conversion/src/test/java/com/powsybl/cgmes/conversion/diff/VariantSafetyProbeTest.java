/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport.UnsupportedChangeBehavior;
import com.powsybl.cgmes.conversion.test.ConversionUtil;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios.Scenario;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.serde.ExportOptions;
import com.powsybl.iidm.serde.NetworkSerDe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proof that the variant-safety table matches {@code iidm-impl}, change by characterized change.
 *
 * <p>{@code FastRouteCapabilities} <em>claims</em> which IIDM targets of the update workflow are stored per
 * network variant. That claim cannot be checked by reading it: it is a statement about which fields of
 * {@code iidm-impl} are per-variant arrays, and those fields are private. So it is checked by doing it. Every
 * characterized change of {@link RecordedChangeScenarios} is exported as a difference and applied to a
 * <em>second</em> variant of a fresh copy of the same fixture, with {@code variantSafeOnly}; afterwards the
 * canonical XIIDM of the untouched variant has to be byte-identical to what it was before. A family whose update
 * writes a shared field and is nevertheless classified {@code SAFE} fails here, whichever field it is &mdash;
 * nothing has to be enumerated for the check to find it.</p>
 *
 * <p>The refused scenarios are pinned by name, so that a change of the table shows up as a test change rather than
 * as silently weaker enforcement, and each of them is also applied <em>without</em> the flag to prove that the
 * refusal is not pedantry: the same difference really does reach the other variant.</p>
 *
 * <p>Three network-level values are excluded from the comparison and documented as such: the case date, the
 * forecast distance and the {@code cgmesMetadataModels} extension. IIDM stores none of them per variant, and the
 * layer that binds a variant to a stored snapshot swaps them in and out around the update instead
 * ({@code VariantScope} in {@code cgmes-rdfdb}). Everything else &mdash; every setpoint, every switch, every tap
 * position, every limit, every impedance, every property of every identifiable &mdash; is compared exactly.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class VariantSafetyProbeTest {

    private static final String OTHER = "other";

    /**
     * The characterized changes a variant-bound update refuses, by scenario name.
     *
     * <p>Every one of them writes state {@code iidm-impl} keeps once per network: an operational limit value, a
     * branch impedance, a voltage limit, the rating and loss factor of an HVDC line in the simplified DC model, the
     * power factor of a line commutated converter, or an extension that does not exist yet.</p>
     */
    private static final Set<String> REFUSED = Set.of(
            // Equipment values: impedances and the limits of work package 5
            "lineResistance", "lineAllImpedances", "seriesCompensatorReactance", "equivalentBranchImpedance",
            "boundaryLineImpedance", "voltageLevelLimitsWithoutVoltageLimitObjects", "cim16ThreeKindsOfLimits",
            "cim16EquipmentAttachedLimitBothSides", "cim16VoltageLimits", "mixedSshAndEq",
            // CGMES 3 limit values, which travel in the steady state hypothesis but still land on a shared field
            "cim100CurrentPatlSide1", "cim100CurrentTatlSide2", "cim100ApparentPowerLimitTransformer",
            "cim100ApparentPowerTatlThreeWindings", "cim100ActivePowerLimitBoundaryLine", "cim100TieLineHalfLimits",
            "cim100VoltageLimitsMultiId", "cim100VoltageLimitSingleId", "wholeLimitsReplacedSameStructure",
            // The simplified DC model writes HvdcLine.maxP and the converter loss factor
            "hvdcActivePowerSetpoint", "hvdcActivePowerSetpointToZero", "hvdcConvertersMode", "lccPowerFactor",
            "vscVoltageSetpoint", "vscReactivePowerSetpointAndRegulation",
            // A line commutated converter is refused whichever DC model is in use: the detailed one still writes
            // the power factor of the station, which is a plain field
            "detailedLccPowerFactor", "detailedConverterControlMode",
            // An extension the update would have to create
            "generatorReferencePriority");

    /**
     * Refusals the table makes on family grounds although <em>this</em> change happens not to leak.
     *
     * <p>A voltage source converter of the simplified DC model is refused as a whole, because its update
     * recomputes {@code HvdcLine.maxP} and the converter loss factor from the active power setpoint, and both are
     * plain fields. A difference that moves only the voltage or the reactive power setpoint recomputes them to the
     * values they already hold, so nothing changes in the other variants &mdash; but whether that is so depends on
     * the values, not on the document, and the verdict is taken from the document. The conservative answer is the
     * right one; it is listed here so that it stays visible rather than being asserted away.</p>
     */
    private static final Set<String> CONSERVATIVELY_REFUSED = Set.of(
            "vscVoltageSetpoint", "vscReactivePowerSetpointAndRegulation",
            // The same for a line commutated converter of the detailed model: only a difference that states the
            // power factor really writes the shared field, and the family is refused as a whole
            "detailedConverterControlMode");

    static List<Scenario> scenarios() {
        return RecordedChangeScenarios.allChanges();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void aVariantBoundUpdateNeverTouchesAnotherVariant(Scenario scenario) {
        DifferenceModelSet diffs = differenceOf(scenario);

        Network receiver = scenario.load();
        receiver.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, OTHER);
        receiver.getVariantManager().setWorkingVariant(OTHER);

        String primaryBefore = xiidmOf(receiver, VariantManagerConstants.INITIAL_VARIANT_ID);
        String boundBefore = xiidmOf(receiver, OTHER);

        List<String> reasons = new ArrayList<>();
        CgmesDiffImport.Decision decision = null;
        try {
            decision = CgmesDiffImport.apply(receiver, diffs, config(scenario),
                    new CgmesDiffImport.Options().setVariantSafeOnly(true).setCheckSupersedes(false),
                    ReportNode.NO_OP);
        } catch (CgmesDiffNotApplicableException e) {
            reasons.addAll(e.getDecision().reasons());
        }
        boolean refused = decision == null;

        assertEquals(primaryBefore, xiidmOf(receiver, VariantManagerConstants.INITIAL_VARIANT_ID),
                "applying " + scenario + " to the variant '" + OTHER + "' changed the primary variant");
        assertEquals(REFUSED.contains(scenario.name()), refused,
                "unexpected variant-safety verdict for " + scenario + ", reasons " + reasons);

        if (refused) {
            assertEquals(boundBefore, xiidmOf(receiver, OTHER),
                    "a refused update has to leave the bound variant untouched too");
            assertFalse(reasons.isEmpty(), "a refusal has to say why");
            // Without the flag the very same difference is applied, which is what makes the refusal meaningful
            if (!CONSERVATIVELY_REFUSED.contains(scenario.name())) {
                assertUnsafeWithoutTheFlag(scenario, diffs);
            }
        } else {
            assertEquals(CgmesDiffImport.Route.FAST, decision.route());
            assertNotEquals(boundBefore, xiidmOf(receiver, OTHER),
                    "the difference of " + scenario + " changed nothing at all in the bound variant");
        }
    }

    /**
     * The same difference applied without {@code variantSafeOnly} really does reach the other variant.
     *
     * <p>Without this half a refusal would prove nothing: a family could be refused although its write is per
     * variant after all, and the feature would simply be needlessly narrow. The state written by a shared field is
     * the same in every variant, so the untouched variant changes with the bound one.</p>
     */
    private static void assertUnsafeWithoutTheFlag(Scenario scenario, DifferenceModelSet diffs) {
        Network receiver = scenario.load();
        receiver.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, OTHER);
        receiver.getVariantManager().setWorkingVariant(OTHER);
        String primaryBefore = xiidmOf(receiver, VariantManagerConstants.INITIAL_VARIANT_ID);

        CgmesDiffImport.apply(receiver, diffs, config(scenario),
                new CgmesDiffImport.Options().setCheckSupersedes(false), ReportNode.NO_OP);

        assertNotEquals(primaryBefore, xiidmOf(receiver, VariantManagerConstants.INITIAL_VARIANT_ID),
                "the difference of " + scenario + " is refused in variant mode, but applying it without the flag"
                        + " does not change the other variant: the verdict of the table is too strict");
    }

    /** A variant-bound update cannot run at all when the scoped update is switched off. */
    @Test
    void anUnscopedUpdateIsRefused() {
        Scenario scenario = RecordedChangeScenarios.all().stream()
                .filter(s -> "loadActivePower".equals(s.name())).findFirst().orElseThrow();
        DifferenceModelSet diffs = differenceOf(scenario);
        Network receiver = scenario.load();
        receiver.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, OTHER);
        receiver.getVariantManager().setWorkingVariant(OTHER);

        CgmesDiffImport.Options options = new CgmesDiffImport.Options()
                .setVariantSafeOnly(true).setCheckSupersedes(false).setScopedUpdate(false);

        CgmesDiffImport.Decision decision = CgmesDiffImport.canApplyInPlace(receiver, diffs, options);
        assertEquals(CgmesDiffImport.Route.SLOW_REQUIRED, decision.route());
        assertTrue(decision.reasons().get(0).contains("needs the scoped update"), decision.reasons().toString());

        String before = xiidmOf(receiver, OTHER);
        try {
            CgmesDiffImport.apply(receiver, diffs, config(scenario), options, ReportNode.NO_OP);
            throw new AssertionError("an unscoped variant-bound update must be refused");
        } catch (CgmesDiffNotApplicableException e) {
            assertTrue(e.getDecision().reasons().get(0).contains("needs the scoped update"));
        }
        assertEquals(before, xiidmOf(receiver, OTHER));
    }

    private static DifferenceModelSet differenceOf(Scenario scenario) {
        Network sender = scenario.load();
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender, scenario.forwardChange());
        return CgmesDiffExport.toDifferences(sender, events,
                new CgmesDiffExport.ExportOptions()
                        .setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE)).differences();
    }

    private static com.powsybl.cgmes.conversion.Conversion.Config config(Scenario scenario) {
        return new CgmesImport().config(scenario.importParams());
    }

    /**
     * The canonical XIIDM of one variant: everything IIDM stores per variant, and nothing that is network level
     * by design.
     */
    private static String xiidmOf(Network network, String variantId) {
        String working = network.getVariantManager().getWorkingVariantId();
        network.getVariantManager().setWorkingVariant(variantId);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NetworkSerDe.write(network, new ExportOptions().setSorted(true), bytes);
            return canonicalise(bytes.toByteArray());
        } finally {
            network.getVariantManager().setWorkingVariant(working);
        }
    }

    private static String canonicalise(byte[] xiidm) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            org.w3c.dom.Document document =
                    factory.newDocumentBuilder().parse(new ByteArrayInputStream(xiidm));
            Element root = document.getDocumentElement();
            root.setAttribute("caseDate", "");
            root.setAttribute("forecastDistance", "");
            removeAll(root, "cgmesMetadataModels");
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toString(StandardCharsets.UTF_8);
        } catch (java.io.IOException | org.xml.sax.SAXException
                 | javax.xml.parsers.ParserConfigurationException
                 | javax.xml.transform.TransformerException e) {
            throw new IllegalStateException("cannot canonicalise the XIIDM", e);
        }
    }

    /** Remove every element of the given local name, and the {@code extension} wrapper it leaves empty. */
    private static void removeAll(Element root, String localName) {
        List<Element> found = new ArrayList<>();
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element element && localName.equals(element.getLocalName())) {
                found.add(element);
            }
        }
        for (Element element : found) {
            org.w3c.dom.Node parent = element.getParentNode();
            parent.removeChild(element);
            if (parent instanceof Element wrapper && "extension".equals(wrapper.getLocalName())
                    && wrapper.getElementsByTagName("*").getLength() == 0
                    && wrapper.getParentNode() != null) {
                wrapper.getParentNode().removeChild(wrapper);
            }
        }
    }
    // ------------------------------------------------------------------ the network-aware rules, one by one

    /**
     * The four {@link FastRouteCapabilities.VariantSafety#NETWORK_DEPENDENT} rules and the one shared property
     * are what the parameterised sweep above cannot reach: {@code RecordedChangeScenarios} has no fixture whose
     * <em>receiver</em> lacks the extension or the capability flag, and none for a control area at all. Each one
     * is therefore set up here in both directions &mdash; the receiver that makes the rule fire, and the one that
     * makes it not fire &mdash; and asserted the same way as the sweep: the untouched variant byte-identical.
     */
    private record RuleCase(String name, Supplier<Network> receiver, DifferenceModelSet diffs,
                            Properties params, boolean expectRefused, String reasonFragment) {
    }

    private static void assertRule(RuleCase testCase) {
        Network receiver = testCase.receiver().get();
        receiver.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, OTHER);
        receiver.getVariantManager().setWorkingVariant(OTHER);
        String primaryBefore = xiidmOf(receiver, VariantManagerConstants.INITIAL_VARIANT_ID);
        String boundBefore = xiidmOf(receiver, OTHER);

        List<String> reasons = new ArrayList<>();
        boolean refused = false;
        try {
            CgmesDiffImport.apply(receiver, testCase.diffs(), new CgmesImport().config(testCase.params()),
                    new CgmesDiffImport.Options().setVariantSafeOnly(true).setCheckSupersedes(false),
                    ReportNode.NO_OP);
        } catch (CgmesDiffNotApplicableException e) {
            refused = true;
            reasons.addAll(e.getDecision().reasons());
        }

        assertEquals(primaryBefore, xiidmOf(receiver, VariantManagerConstants.INITIAL_VARIANT_ID),
                testCase.name() + ": the primary variant must not move");
        assertEquals(testCase.expectRefused(), refused, testCase.name() + ", reasons " + reasons);
        if (refused) {
            assertTrue(String.join(" ", reasons).contains(testCase.reasonFragment()),
                    testCase.name() + ": expected a reason naming \"" + testCase.reasonFragment() + "\", got "
                            + reasons);
            assertEquals(boundBefore, xiidmOf(receiver, OTHER),
                    testCase.name() + ": a refusal has to leave the bound variant untouched too");
            // And the refusal is not pedantry: without the flag the same difference reaches the other variant
            Network unguarded = testCase.receiver().get();
            unguarded.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, OTHER);
            unguarded.getVariantManager().setWorkingVariant(OTHER);
            String primary = xiidmOf(unguarded, VariantManagerConstants.INITIAL_VARIANT_ID);
            CgmesDiffImport.apply(unguarded, testCase.diffs(), new CgmesImport().config(testCase.params()),
                    new CgmesDiffImport.Options().setCheckSupersedes(false), ReportNode.NO_OP);
            assertNotEquals(primary, xiidmOf(unguarded, VariantManagerConstants.INITIAL_VARIANT_ID),
                    testCase.name() + ": the rule refuses a change that does not leak");
        } else {
            assertNotEquals(boundBefore, xiidmOf(receiver, OTHER),
                    testCase.name() + ": the difference changed nothing at all in the bound variant");
        }
    }

    private static Scenario scenario(String name) {
        return RecordedChangeScenarios.allChanges().stream()
                .filter(s -> name.equals(s.name())).findFirst().orElseThrow();
    }

    /**
     * {@code GeneratingUnit.normalPF} reaches the per-variant participation factor only through an
     * {@code ActivePowerControl} that is already there; otherwise the update creates the extension or writes an
     * IIDM property, and neither belongs to a variant.
     */
    @Test
    void normalPfIsRefusedOnlyWhenTheGeneratorHasNoActivePowerControl() {
        Scenario withApc = scenario("generatorParticipationFactor");
        DifferenceModelSet diffs = differenceOf(withApc);
        assertRule(new RuleCase("normalPF without ActivePowerControl",
                () -> {
                    Network network = ConversionUtil.readCgmesResources(new Properties(), withApc.dir(),
                            withApc.files());
                    network.getGenerator("SynchronousMachine").removeExtension(ActivePowerControl.class);
                    return network;
                },
                diffs, new Properties(), true, "creates the ActivePowerControl extension"));
        assertRule(new RuleCase("normalPF with ActivePowerControl", withApc::load, diffs,
                withApc.importParams(), false, null));
    }

    /**
     * Switching the regulation of a tap changer on raises {@code loadTapChangingCapabilities}, which is a plain
     * field of the tap changer and therefore shared by every variant.
     */
    @Test
    void tapChangerRegulationIsRefusedOnlyWithoutLoadTapChangingCapabilities() {
        Scenario regulation = scenario("phaseTapChangerRegulationState");
        DifferenceModelSet diffs = differenceOf(regulation);
        assertRule(new RuleCase("regulation on a tap changer without LTC",
                () -> {
                    Network network = regulation.load();
                    network.getTwoWindingsTransformer("T2W").getPhaseTapChanger()
                            .setRegulating(false).setLoadTapChangingCapabilities(false);
                    return network;
                },
                diffs, regulation.importParams(), true, "loadTapChangingCapabilities"));
        assertRule(new RuleCase("regulation on a tap changer with LTC", regulation::load, diffs,
                regulation.importParams(), false, null));
    }

    /**
     * A control area: its interchange target is per variant, its tolerance is an IIDM property and is not.
     *
     * <p>The two travel in one property group, so the interesting difference is the one that states both: it is
     * applied as a whole outside variant mode &mdash; and then the tolerance reaches every variant &mdash; and
     * refused inside it.</p>
     */
    @Test
    void theControlAreaToleranceIsRefusedButItsInterchangeIsNot() {
        assertRule(new RuleCase("ControlArea.netInterchange", VariantSafetyProbeTest::controlArea,
                controlAreaDifference(false), new Properties(), false, null));
        assertRule(new RuleCase("ControlArea.netInterchange + pTolerance", VariantSafetyProbeTest::controlArea,
                controlAreaDifference(true), new Properties(), true, "properties are not stored per variant"));
    }

    private static Network controlArea() {
        return ConversionUtil.readCgmesResources(new Properties(), "/update/control-area/",
                new String[] {"controlArea_EQ.xml", "controlArea_EQ_BD.xml", "controlArea_SSH.xml"});
    }

    /** A control-area difference, optionally carrying the tolerance the update writes as an IIDM property. */
    private static DifferenceModelSet controlAreaDifference(boolean withTolerance) {
        List<CgmesStatement> forward = new ArrayList<>();
        List<CgmesStatement> reverse = new ArrayList<>();
        forward.add(CgmesStatement.literal("ControlArea", "ControlArea", "ControlArea.netInterchange", "111.0"));
        reverse.add(CgmesStatement.literal("ControlArea", "ControlArea", "ControlArea.netInterchange", "235.0"));
        if (withTolerance) {
            forward.add(CgmesStatement.literal("ControlArea", "ControlArea", "ControlArea.pTolerance", "7"));
            reverse.add(CgmesStatement.literal("ControlArea", "ControlArea", "ControlArea.pTolerance", "10"));
        }
        return new DifferenceModelSet(List.of(new DifferenceModel(
                DifferenceModelHeader.builder("urn:uuid:control-area", CgmesSubset.STEADY_STATE_HYPOTHESIS,
                        CgmesNamespace.CIM_100_NAMESPACE).build(), forward, reverse, List.of())));
    }
}
