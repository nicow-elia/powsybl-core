/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.CgmesExport;
import com.powsybl.cgmes.extensions.CgmesTapChanger;
import com.powsybl.cgmes.model.CgmesMetadataModel;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.iidm.network.regulation.VoltageRegulationHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.util.*;

import static com.powsybl.cgmes.conversion.Conversion.*;
import static com.powsybl.cgmes.conversion.elements.transformers.AbstractTransformerConversion.getCgmesTapChanger;
import static com.powsybl.cgmes.conversion.export.CgmesExportUtil.*;
import static com.powsybl.cgmes.conversion.export.elements.RegulatingControlEq.*;
import static com.powsybl.cgmes.conversion.naming.CgmesObjectReference.Part.*;
import static com.powsybl.cgmes.conversion.naming.CgmesObjectReference.ref;
import static com.powsybl.cgmes.conversion.naming.CgmesObjectReference.refTyped;
import static com.powsybl.cgmes.model.CgmesNamespace.RDF_NAMESPACE;

/**
 * @author Miora Ralambotiana {@literal <miora.ralambotiana at rte-france.com>}
 * @author Luma Zamarreño {@literal <zamarrenolm at aia.es>}
 */
public final class SteadyStateHypothesisExport {

    private static final Logger LOG = LoggerFactory.getLogger(SteadyStateHypothesisExport.class);
    private static final String ROTATING_MACHINE_P = "RotatingMachine.p";
    private static final String ROTATING_MACHINE_Q = "RotatingMachine.q";
    private static final String REGULATING_COND_EQ_CONTROL_ENABLED = "RegulatingCondEq.controlEnabled";
    private static final String ACDC_CONVERTER_DC_TERMINAL = "ACDCConverterDCTerminal";
    private static final String OPERATING_MODE_GENERATOR = "generator";
    private static final String OPERATING_MODE_MOTOR = "motor";
    private static final String OPERATING_MODE_CONDENSER = "condenser";

    private SteadyStateHypothesisExport() {
    }

    public static void write(Network network, XMLStreamWriter writer, CgmesExportContext context) {
        CgmesMetadataModel model = CgmesExport.initializeModelForExport(
                network, CgmesSubset.STEADY_STATE_HYPOTHESIS, context, true, false);
        write(network, writer, context, model);
    }

    public static void write(Network network, XMLStreamWriter writer, CgmesExportContext context, CgmesMetadataModel model) {
        // One mapping per export: the full model of the change mapping, which describes every object the sections
        // below write (except terminals, DC terminals, control areas and the detailed line commutated converters)
        CgmesChangeTranslator mapping = CgmesChangeTranslator.forFullModel(network, context);
        // The RegulatingControls to write, in the key set and insertion order upstream's HashMap of views had
        // (tap changers, generators, shunt compensators, static var compensators; only controls with a view), so that
        // they are iterated, and written, in the same order
        final Map<String, Boolean> regulatingControlIds = new HashMap<>();
        String cimNamespace = context.getCim().getNamespace();

        try {
            CgmesExportUtil.writeRdfRoot(cimNamespace, context.getCim().getEuPrefix(), context.getCim().getEuNamespace(), writer);

            if (context.getCimVersion() >= 16) {
                CgmesExportUtil.writeModelDescription(network, CgmesSubset.STEADY_STATE_HYPOTHESIS, writer, model, context);
            }

            writeLoads(network, mapping, cimNamespace, writer, context);
            for (VoltageLevel vl : network.getVoltageLevels()) {
                mapping.describeFictitiousInjections(vl).write(cimNamespace, writer, context);
            }
            writeEquivalentInjections(network, mapping, cimNamespace, writer, context);
            writeTapChangers(network, mapping, cimNamespace, regulatingControlIds, writer, context);
            writeGenerators(network, mapping, cimNamespace, regulatingControlIds, writer, context);
            for (Battery battery : network.getBatteries()) {
                write(mapping.describeBattery(battery), cimNamespace, writer, context);
            }
            writeShuntCompensators(network, mapping, cimNamespace, regulatingControlIds, writer, context);
            writeStaticVarCompensators(network, mapping, cimNamespace, regulatingControlIds, writer, context);
            writeRegulatingControls(mapping, regulatingControlIds, cimNamespace, writer, context);
            writeGeneratingUnits(network, mapping, cimNamespace, writer, context);
            writeConverters(network, mapping, cimNamespace, writer, context);
            writeDCTerminals(network, cimNamespace, writer, context);
            // FIXME open status of retained switches in bus-branch models
            writeSwitches(network, mapping, cimNamespace, writer, context);
            writeTerminals(network, cimNamespace, writer, context);
            writeControlAreas(network, cimNamespace, writer, context);

            writer.writeEndDocument();
        } catch (XMLStreamException e) {
            throw new UncheckedXmlStreamException(e);
        }
    }

    private static void writeSwitches(Network network, CgmesChangeTranslator mapping, String cimNamespace,
                                      XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        for (Switch sw : network.getSwitches()) {
            if (context.isExportedEquipment(sw)) {
                String switchType = sw.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS); // may be null
                // A switch imported from a branch class is the connection status of its terminals, written below
                if (!isSwitchImportedFromAcLineSegmentEquivalentBranchOrSeriesCompensator(switchType)) {
                    write(mapping.describeSwitch(sw), cimNamespace, writer, context);
                }
            }
        }
    }

    /**
     * Write what the mapping says about one object; a full model refuses nothing a full export could write, so a
     * refusal here is a network the export cannot describe at all.
     */
    private static void write(Result<CgmesPropertyBuffer, String> description, String cimNamespace, XMLStreamWriter writer,
                              CgmesExportContext context) throws XMLStreamException {
        // instanceof rather than a pattern switch: this runs once per object, and a pattern switch is linked through
        // an invokedynamic bootstrap that stays slow until the JIT compiles it
        if (description instanceof Result.Success<CgmesPropertyBuffer, String> success) {
            success.value().write(cimNamespace, writer, context);
        } else {
            throw new PowsyblException(((Result.Failure<CgmesPropertyBuffer, String>) description).reason());
        }
    }

    private static void writeTerminalForSwitches(Network network, String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) {
        for (Switch sw : network.getSwitches()) {
            if (context.isExportedEquipment(sw)) {
                String switchType = sw.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS); // may be null
                boolean connected = isConnected(sw, switchType);

                writeTerminal(context.getNamingStrategy().getCgmesIdFromAlias(sw, ALIAS_TERMINAL1), connected, cimNamespace, writer, context);
                writeTerminal(context.getNamingStrategy().getCgmesIdFromAlias(sw, ALIAS_TERMINAL2), connected, cimNamespace, writer, context);
            }
        }
    }

    private static boolean isSwitchImportedFromAcLineSegmentEquivalentBranchOrSeriesCompensator(String switchType) {
        return "ACLineSegment".equals(switchType) || "EquivalentBranch".equals(switchType) || "SeriesCompensator".equals(switchType);
    }

    private static boolean isConnected(Switch sw, String switchType) {
        if (isSwitchImportedFromAcLineSegmentEquivalentBranchOrSeriesCompensator(switchType)) {
            return !sw.isOpen();
        } else {
            // Terminals for switches are exported as always connected
            // The status of the switch is "open" if any of the original terminals were not connected
            // An original "closed" switch with any terminal disconnected
            // will be exported as "open" with terminals connected
            return true;
        }
    }

    private static void writeTerminalForBoundaryLines(Network network, String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) {
        for (BoundaryLine bl : network.getBoundaryLines(BoundaryLineFilter.ALL)) {
            // Terminal for equivalent injection at boundary is always connected
            writeTerminal(context.getNamingStrategy().getCgmesIdFromProperty(bl, PROPERTY_EQUIVALENT_INJECTION_TERMINAL), true, cimNamespace, writer, context);
            // Terminal for boundary side of original line/switch is always connected
            writeTerminal(CgmesExportUtil.getBoundaryLineBoundaryTerminalId(bl, context), true, cimNamespace, writer, context);
        }
    }

    private static void writeTerminalForBuses(Network network, String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) {
        for (Bus b : network.getBusBreakerView().getBuses()) {
            String bbsTerminals = b.getProperty(PROPERTY_BUSBAR_SECTION_TERMINALS, "");
            if (!bbsTerminals.isEmpty()) {
                for (String bbsTerminal : bbsTerminals.split(",")) {
                    writeTerminal(bbsTerminal, true, cimNamespace, writer, context);
                }
            }
        }
    }

    private static void writeTerminals(Network network, String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) {
        for (Connectable<?> c : network.getConnectables()) { // TODO write boundary terminals for tie lines from CGMES
            if (context.isExportedEquipment(c)) {
                if (CgmesExportUtil.isEquivalentShuntWithZeroSectionCount(c)) {
                    // Equivalent shunts do not have a section count in SSH, SV profiles,
                    // the only way to make output consistent with IIDM section count == 0 is to disconnect its terminal
                    writeTerminal(CgmesExportUtil.getTerminalId(c.getTerminals().get(0), context), false, cimNamespace, writer, context);
                } else {
                    for (Terminal t : c.getTerminals()) {
                        writeTerminal(t, cimNamespace, writer, context);
                    }
                }
            }
        }
        writeTerminalForSwitches(network, cimNamespace, writer, context);
        writeTerminalForBoundaryLines(network, cimNamespace, writer, context);
        // If we are performing an updated export, write recorded busbar section terminals as connected
        if (!context.isExportEquipment()) {
            writeTerminalForBuses(network, cimNamespace, writer, context);
        }
    }

    private static void writeEquivalentInjections(Network network, CgmesChangeTranslator mapping, String cimNamespace,
                                                  XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        // One equivalent injection for every boundary line
        Set<String> exported = new HashSet<>();
        for (BoundaryLine bl : network.getBoundaryLines(BoundaryLineFilter.ALL)) {
            if (exported.add(context.getNamingStrategy().getCgmesIdFromProperty(bl, PROPERTY_EQUIVALENT_INJECTION))) {
                write(mapping.describeBoundaryInjection(bl), cimNamespace, writer, context);
            }
        }
    }

    private static void writeTapChangers(Network network, CgmesChangeTranslator mapping, String cimNamespace,
                                         Map<String, Boolean> regulatingControlIds,
                                         XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        for (TwoWindingsTransformer twt : network.getTwoWindingsTransformers()) {
            if (twt.hasPhaseTapChanger()) {
                String aliasType = twt.getAliasFromType(ALIAS_PHASE_TAP_CHANGER2).isPresent() && twt.getAliasFromType(ALIAS_PHASE_TAP_CHANGER1).isEmpty() ?
                    ALIAS_PHASE_TAP_CHANGER2 : ALIAS_PHASE_TAP_CHANGER1;
                writeTapChanger(twt, aliasType, PHASE_TAP_CHANGER, 1, CgmesNames.PHASE_TAP_CHANGER_TABULAR, twt.getPhaseTapChanger(), mapping, regulatingControlIds, cimNamespace, writer, context);
            }
            if (twt.hasRatioTapChanger()) {
                String aliasType = twt.getAliasFromType(ALIAS_RATIO_TAP_CHANGER2).isPresent() && twt.getAliasFromType(ALIAS_RATIO_TAP_CHANGER1).isEmpty() ?
                    ALIAS_RATIO_TAP_CHANGER2 : ALIAS_RATIO_TAP_CHANGER1;
                writeTapChanger(twt, aliasType, RATIO_TAP_CHANGER, 1, CgmesNames.RATIO_TAP_CHANGER, twt.getRatioTapChanger(), mapping, regulatingControlIds, cimNamespace, writer, context);
            }
        }

        for (ThreeWindingsTransformer twt : network.getThreeWindingsTransformers()) {
            for (ThreeWindingsTransformer.Leg leg : Arrays.asList(twt.getLeg1(), twt.getLeg2(), twt.getLeg3())) {
                int endNumber = leg.getSide().getNum();
                if (leg.hasPhaseTapChanger()) {
                    String aliasType = getPhaseTapChangerAliasType(Integer.toString(endNumber));
                    writeTapChanger(twt, aliasType, PHASE_TAP_CHANGER, endNumber, CgmesNames.PHASE_TAP_CHANGER_TABULAR,
                        leg.getPhaseTapChanger(), mapping, regulatingControlIds, cimNamespace, writer, context);
                }
                if (leg.hasRatioTapChanger()) {
                    String aliasType = getRatioTapChangerAliasType(Integer.toString(endNumber));
                    writeTapChanger(twt, aliasType, RATIO_TAP_CHANGER, endNumber, CgmesNames.RATIO_TAP_CHANGER, leg.getRatioTapChanger(), mapping, regulatingControlIds, cimNamespace, writer, context);
                }
            }
        }
    }

    private static <C extends Connectable<C>> void writeTapChanger(C twt, String aliasType, Part part, int endNumber,
                                                                   String defaultType, TapChanger<?, ?, ?, ?> tc,
                                                                   CgmesChangeTranslator mapping,
                                                                   Map<String, Boolean> regulatingControlIds,
                                                                   String cimNamespace, XMLStreamWriter writer,
                                                                   CgmesExportContext context) throws XMLStreamException {
        String cgmesTapChangerId = twt.getAliasFromType(aliasType).orElse(null);
        String tapChangerControlId = getTapChangerControlId(twt, part, endNumber, cgmesTapChangerId, context);
        String end = twt instanceof ThreeWindingsTransformer ? Integer.toString(endNumber) : "";
        String prefix = tc instanceof RatioTapChanger ? CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX
                : CgmesChangeTranslator.PHASE_TAP_CHANGER_PREFIX;
        TapChangerRef ref = new TapChangerRef(twt, prefix + end, tc);
        mapping.describeTapChanger(twt, aliasType, defaultType, ref).write(cimNamespace, writer, context);
        boolean hasView;
        if (tc instanceof RatioTapChanger rtc) {
            hasView = rtc.getVoltageRegulation() != null && rtc.getVoltageRegulation().getMode() != null;
        } else {
            hasView = phaseTapChangerView((PhaseTapChanger) tc, tapChangerControlId,
                    getCgmesTapChanger(twt, cgmesTapChangerId).map(CgmesTapChanger::getControlId).isPresent(), ref, context,
                    IidmStateView.LIVE) != null;
        }
        if (hasView) {
            addRegulatingControlId(tapChangerControlId, regulatingControlIds);
        }

        // If we are exporting equipment definitions the hidden tap changer will not be exported
        // because it has been included in the model for the only tap changer left in IIDM
        // If we are exporting only SSH, SV, ... we have to write the step we have saved for it
        if (!context.isExportEquipment()) {
            mapping.describeHiddenTapChanger(twt, cgmesTapChangerId, defaultType).write(cimNamespace, writer, context);
        }
    }

    private static void writeShuntCompensators(Network network, CgmesChangeTranslator mapping, String cimNamespace,
                                               Map<String, Boolean> regulatingControlIds,
                                               XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        for (ShuntCompensator s : network.getShuntCompensators()) {
            if ("true".equals(s.getProperty(PROPERTY_IS_EQUIVALENT_SHUNT))) {
                continue;
            }
            write(mapping.describeShunt(s), cimNamespace, writer, context);
            addRegulatingControlId(s, regulatingControlIds, context);
        }
    }

    /** The control of a holder is written when its regulation has a mode in this variant (B5). */
    private static void addRegulatingControlId(VoltageRegulationHolder<?> holder, Map<String, Boolean> regulatingControlIds,
                                               CgmesExportContext context) {
        if (holder.getVoltageRegulation() != null && holder.getVoltageRegulation().getMode() != null) {
            addRegulatingControlId(getRegulatingControlId((Identifiable<?>) holder, context), regulatingControlIds);
        }
    }

    /**
     * Add a control to write. computeIfAbsent, as upstream filled its map of views: it puts a new key at the head of
     * its hash bucket (put appends it), and the iteration order, hence the order of the controls in the file, depends
     * on that.
     */
    private static void addRegulatingControlId(String regulatingControlId, Map<String, Boolean> regulatingControlIds) {
        regulatingControlIds.computeIfAbsent(regulatingControlId, id -> Boolean.TRUE);
    }

    private static void writeGenerators(Network network, CgmesChangeTranslator mapping, String cimNamespace,
                                        Map<String, Boolean> regulatingControlIds,
                                        XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        for (Generator g : network.getGenerators()) {
            write(mapping.describeGenerator(g), cimNamespace, writer, context);
            // An EquivalentInjection carries its regulation itself, it has no RegulatingControl
            if (!CgmesNames.EQUIVALENT_INJECTION.equals(g.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS))) {
                addRegulatingControlId(g, regulatingControlIds, context);
            }
        }
    }

    /**
     * The operating mode of a machine, with the regulation read from the given state of the network.
     *
     * <p>Package private so that the change export writes the same operating mode as the full export.</p>
     */
    static <I extends ReactiveLimitsHolder & Injection<I>> String obtainOperatingMode(I i, double minP, double maxP,
                                                                                      double targetP, IidmStateView state) {
        String calculatedKind = obtainCalculatedSynchronousMachineKind(minP, maxP, obtainCurve(i), i instanceof Battery || i instanceof Generator gen && gen.isCondenser());
        return obtainOperatingMode(targetP, i, calculatedKind, state);
    }

    private static String obtainOperatingMode(double targetP, Injection<?> injection, String calculatedKind, IidmStateView state) {
        if (targetP < 0) {
            return OPERATING_MODE_MOTOR;
        } else if (targetP > 0) {
            return OPERATING_MODE_GENERATOR;
        } else {
            if (isOperatingAsACondenser(injection, state) && calculatedKind.toLowerCase().contains(OPERATING_MODE_CONDENSER)) {
                return OPERATING_MODE_CONDENSER;
            } else {
                if (calculatedKind.toLowerCase().contains(OPERATING_MODE_GENERATOR)) {
                    return OPERATING_MODE_GENERATOR;
                } else if (calculatedKind.toLowerCase().contains(OPERATING_MODE_MOTOR)) {
                    return OPERATING_MODE_MOTOR;
                } else {
                    return OPERATING_MODE_CONDENSER;
                }
            }
        }
    }

    private static boolean isOperatingAsACondenser(Injection<?> injection, IidmStateView state) {
        switch (injection) {
            case Generator generator -> {
                RegulationRef regulation = RegulationRef.of(generator);
                double regulatingTargetQ = regulation.regulatingTargetQ(state);
                return regulation.isRegulatingWithMode(RegulationMode.VOLTAGE, state) && !Double.isNaN(regulation.localTargetV(state))
                    || !Double.isNaN(regulatingTargetQ) && regulatingTargetQ != 0;
            }
            case Battery battery -> {
                return battery.isRegulatingWithMode(RegulationMode.VOLTAGE) && !Double.isNaN(battery.getLocalTargetV())
                    || !Double.isNaN(battery.getRegulatingTargetQ()) && battery.getRegulatingTargetQ() != 0;
            }
            default -> throw new IllegalStateException("Unexpected value: " + injection);
        }
    }

    private static void writeStaticVarCompensators(Network network, CgmesChangeTranslator mapping, String cimNamespace,
                                                   Map<String, Boolean> regulatingControlIds,
                                                   XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        for (StaticVarCompensator svc : network.getStaticVarCompensators()) {
            write(mapping.describeStaticVarCompensator(svc), cimNamespace, writer, context);
            addRegulatingControlId(svc, regulatingControlIds, context);
        }
    }

    /**
     * The TapChangerControl description of a phase tap changer, read from the given state of the network, or
     * {@code null} when it has no control.
     *
     * <p>Package private so that the change export describes a TapChangerControl exactly as the full export does.</p>
     *
     * @param ref the tap changer and the name a recorded change of it carries
     */
    static RegulatingControlView regulatingControlView(PhaseTapChanger ptc, String controlId, TapChangerRef ref,
                                                       CgmesExportContext context, IidmStateView state) {
        PhaseTapChanger.RegulationMode mode = ref.getEnum(state, CgmesChangeTranslator.REGULATION_MODE_SUFFIX,
                PhaseTapChanger.RegulationMode.class, ptc::getRegulationMode);
        if (!ptc.hasLoadTapChangingCapabilities() || mode == null) {
            return null;
        }
        return switch (mode) {
            // The import multiplies the target by the sign of the regulating terminal it recorded
            // (AbstractTransformerConversion#updatePhaseTapChanger), so the export applies it as well, unless the
            // equipment model is exported too
            case PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL ->
                new RegulatingControlView(controlId, RegulatingControlType.TAP_CHANGER_CONTROL, true,
                    ref.getBoolean(state, CgmesChangeTranslator.REGULATING_SUFFIX, ptc::isRegulating),
                    ref.getDouble(state, CgmesChangeTranslator.TARGET_DEADBAND_SUFFIX, ptc::getTargetDeadband),
                    CgmesExportUtil.exportedTerminalSign(ref.transformer(), ref.end(), context)
                            * ref.getDouble(state, CgmesChangeTranslator.REGULATION_VALUE_SUFFIX, ptc::getRegulationValue),
                    "M");
            case PhaseTapChanger.RegulationMode.CURRENT_LIMITER ->
                new RegulatingControlView(controlId, RegulatingControlType.TAP_CHANGER_CONTROL,
                    true, false, 0.0, 0.0, "M");
        };
    }

    /**
     * The TapChangerControl description of a phase tap changer, read from the given state, or {@code null} when it has
     * none. A phase tap changer limiting current is described with the values it has (a current in Amperes, which
     * carries no sign, multiplier none) when the steady state hypothesis is read against the equipment model its
     * import recorded the control from; with an equipment model of its own the export writes the limit as a
     * CurrentLimit of the regulated terminal and the control keeps upstream's zeros.
     *
     * <p>Package private so that the change export describes a TapChangerControl exactly as the full export does.</p>
     *
     * @param recordedControl whether the import recorded the TapChangerControl of this tap changer
     */
    static RegulatingControlView phaseTapChangerView(PhaseTapChanger ptc, String controlId, boolean recordedControl,
                                                     TapChangerRef ref, CgmesExportContext context, IidmStateView state) {
        PhaseTapChanger.RegulationMode mode = ref.getEnum(state, CgmesChangeTranslator.REGULATION_MODE_SUFFIX,
                PhaseTapChanger.RegulationMode.class, ptc::getRegulationMode);
        if (mode == PhaseTapChanger.RegulationMode.CURRENT_LIMITER && recordedControl && !context.isExportEquipment()) {
            return new RegulatingControlView(controlId, RegulatingControlType.TAP_CHANGER_CONTROL, true,
                    ref.getBoolean(state, CgmesChangeTranslator.REGULATING_SUFFIX, ptc::isRegulating),
                    ref.getDouble(state, CgmesChangeTranslator.TARGET_DEADBAND_SUFFIX, ptc::getTargetDeadband),
                    ref.getDouble(state, CgmesChangeTranslator.REGULATION_VALUE_SUFFIX, ptc::getRegulationValue),
                    "none");
        }
        return regulatingControlView(ptc, controlId, ref, context, state);
    }

    private static String getRegulatingControlId(Identifiable<?> identifiable, CgmesExportContext context) {
        return context.getNamingStrategy().getCgmesIdFromProperty(identifiable, PROPERTY_REGULATING_CONTROL);
    }

    /**
     * The RegulatingControl description of a voltage regulation holder, read from the given state of the network, or
     * {@code null} when the holder has no voltage regulation, or one without a mode in this variant.
     *
     * <p>Package private so that the change export describes a RegulatingControl exactly as the full export does,
     * which is what the receiving side of a partial or difference file expects to read.</p>
     *
     * @param regulation the holder and the name a recorded change of its regulation carries
     */
    static RegulatingControlView regulatingControlView(RegulationRef regulation, String regulatingControlId,
                                                       CgmesExportContext context, IidmStateView state) {
        VoltageRegulationHolder<?> regulationHolder = regulation.holder();
        // A regulation without a mode in this variant (created while another variant was the working one) cannot say
        // what its control regulates: it describes no view, and the control is written from its other users, if any
        if (regulation.regulation() != null && regulation.mode(state) != null) {
            boolean enabled = regulation.isRegulating(state);

            // Only discrete regulation holders can have a non-zero deadband
            boolean discrete = false;
            double targetDeadband = 0.0;
            if (regulationHolder instanceof ShuntCompensator || regulationHolder instanceof RatioTapChanger) {
                discrete = true;
                targetDeadband = regulation.targetDeadband(state);
            }

            // VoltageRegulation Terminal can be left null to force the use of local target instead of the remote one,
            // thus targets should be determined with VoltageRegulationHolder.getRegulatingTargetQ/V
            double targetValue;
            String targetValueUnitMultiplier;
            String mode = getRegulatingControlMode(regulation.mode(state));
            if (REGULATING_CONTROL_REACTIVE_POWER.equals(mode)) {
                // Generator are in generator sign convention in IIDM and load sign convention in CGMES
                targetValue = regulation.regulatingTargetQ(state);
                if (regulationHolder instanceof Generator) {
                    targetValue = -targetValue;
                }
                // The import multiplies the target by the sign of the regulating terminal it recorded
                // (AbstractReactiveLimitsOwnerConversion#updateRegulatingControlReactivePower,
                // StaticVarCompensatorConversion#updateRegulatingControl), so the export applies it as well, unless
                // the equipment model is exported too
                if (regulationHolder instanceof Generator || regulationHolder instanceof StaticVarCompensator
                        || regulationHolder instanceof RatioTapChanger) {
                    // AbstractTransformerConversion#updateRatioTapChanger reads the target of a ratio tap changer
                    // with the sign of the end the tap changer sits on
                    targetValue *= CgmesExportUtil.exportedTerminalSign(regulation.owner(), regulation.end(), context);
                }
                targetValueUnitMultiplier = "M";
            } else if (REGULATING_CONTROL_VOLTAGE.equals(mode)) {
                targetValue = regulation.regulatingTargetV(state);
                if (regulationHolder instanceof Generator && context.isExportGeneratorsInLocalRegulationMode()) {
                    targetValue = regulation.localTargetV(state);
                }
                targetValueUnitMultiplier = "k";
            } else {
                throw new IllegalStateException("Unexpected regulation mode: " + mode);
            }

            // RatioTapChanger VoltageRegulation is exported to a specialized class
            RegulatingControlType regulatingControlType = RegulatingControlType.REGULATING_CONTROL;
            if (regulationHolder instanceof RatioTapChanger) {
                regulatingControlType = RegulatingControlType.TAP_CHANGER_CONTROL;
            }

            return new RegulatingControlView(regulatingControlId, regulatingControlType,
                discrete, enabled, targetDeadband, targetValue, targetValueUnitMultiplier);
        }
        return null;
    }

    private static void writeRegulatingControls(CgmesChangeTranslator mapping, Map<String, Boolean> regulatingControlIds,
                                                String cimNamespace, XMLStreamWriter writer, CgmesExportContext context)
            throws XMLStreamException {
        for (String regulatingControlId : regulatingControlIds.keySet()) {
            write(mapping.describeRegulatingControl(regulatingControlId), cimNamespace, writer, context);
        }
    }

    /**
     * Combine the descriptions that every user of the same RegulatingControl produces into the single description
     * the object gets.
     *
     * <p>Package private so that the change export combines shared controls exactly as the full export does.</p>
     */
    static RegulatingControlView combineRegulatingControlViews(List<RegulatingControlView> rcs) {
        RegulatingControlView combined = rcs.get(0);
        if (rcs.size() > 1 && LOG.isWarnEnabled()) {
            LOG.warn("Multiple views ({}) for regulating control {} are combined", rcs.size(), rcs.get(0).id);
        }
        for (int k = 1; k < rcs.size(); k++) {
            RegulatingControlView current = rcs.get(k);
            if (combinedTargetDeadbandMustBeUpdated(current.targetDeadband, combined.targetDeadband)) {
                combined.targetDeadband = current.targetDeadband;
            }
            if (!combined.discrete && current.discrete) {
                combined.discrete = true;
            }
            if (!combined.controlEnabled && current.controlEnabled) {
                combined.controlEnabled = true;
            }
        }
        return combined;
    }

    private static boolean combinedTargetDeadbandMustBeUpdated(double currentTargetDeadband, double combinedTargetDeadband) {
        return currentTargetDeadband == 0 && (Double.isNaN(combinedTargetDeadband) || combinedTargetDeadband < 0)
                || currentTargetDeadband > 0 && (combinedTargetDeadband == 0 || currentTargetDeadband < combinedTargetDeadband);
    }

    /** Package private so that the change export names a RegulatingControl as the full export does. */
    static String regulatingControlClassname(RegulatingControlType type) {
        if (type == RegulatingControlType.TAP_CHANGER_CONTROL) {
            return "TapChangerControl";
        } else {
            return "RegulatingControl";
        }
    }

    private static void writeTerminal(Terminal t, String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) {
        writeTerminal(CgmesExportUtil.getTerminalId(t, context), t.isConnected(), cimNamespace, writer, context);
    }

    private static void writeTerminal(String terminalId, boolean connected, String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) {
        try {
            CgmesExportUtil.writeStartAbout(CgmesNames.TERMINAL, terminalId, cimNamespace, writer, context);
            writer.writeStartElement(cimNamespace, "ACDCTerminal.connected");
            writer.writeCharacters(Boolean.toString(connected));
            writer.writeEndElement();
            writer.writeEndElement();
        } catch (XMLStreamException e) {
            throw new UncheckedXmlStreamException(e);
        }
    }

    private static void writeLoads(Network network, CgmesChangeTranslator mapping, String cimNamespace, XMLStreamWriter writer,
                                   CgmesExportContext context) throws XMLStreamException {
        for (Load load : network.getLoads()) {
            if (context.isExportedEquipment(load)) {
                write(mapping.describeLoad(load), cimNamespace, writer, context);
            }
        }
    }

    /**
     * The AsynchronousMachineKind of an IIDM load with the given active power (load sign convention).
     *
     * <p>Package private so that the change export writes the same kind as the full export.</p>
     */
    static String obtainAsynchronousMachineKind(double p) {
        if (p < 0) {
            return OPERATING_MODE_GENERATOR;
        } else {
            return OPERATING_MODE_MOTOR;
        }
    }

    private static void writeConverters(Network network, CgmesChangeTranslator mapping, String cimNamespace,
                                        XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        for (HvdcConverterStation<?> converterStation : network.getHvdcConverterStations()) {
            write(mapping.describeConverterStation(converterStation), cimNamespace, writer, context);
        }
        // The active and reactive power of a detailed line commutated converter are the flows of its point of common
        // coupling here (documented behaviour), where the change mapping writes its setpoint and power factor (B4)
        for (LineCommutatedConverter lccConverter : network.getLineCommutatedConverters()) {
            writeAcDcConverter(lccConverter, cimNamespace, writer, context);
        }
        for (VoltageSourceConverter vscConverter : network.getVoltageSourceConverters()) {
            write(mapping.describeAcDcConverter(vscConverter), cimNamespace, writer, context);
        }
    }

    /**
     * The VsConverter.targetQpcc of a converter station of the simplified DC model: the reactive power target whenever
     * {@link #vscQpccControl} writes {@code reactivePcc}, that is whenever the station does not regulate voltage, and
     * zero otherwise.
     *
     * <p>A station in voltage mode that does not regulate (the deprecated {@code setVoltageRegulatorOn(false)} since
     * powsybl-core #3699) is written {@code reactivePcc}, and the import then reads this value as its reactive power
     * target: it is the local reactive power target the station holds, not zero (review 21 round 2, R2-M4).</p>
     *
     * <p>Package private so that the change export writes the same value as the full export.</p>
     */
    static double vscTargetQpcc(RegulationRef regulation, CgmesExportContext context, IidmStateView state) {
        // To be consistent with the import, which reads the target as -terminalSign * targetQpcc
        // (HvdcConverterConversion#getValidTargetQ)
        return !regulation.isRegulatingWithMode(RegulationMode.VOLTAGE, state)
                ? -CgmesExportUtil.exportedTerminalSign(regulation.owner(), "", context) * regulation.regulatingTargetQ(state) : 0;
    }

    /** The VsConverter.targetUpcc of a converter station: the voltage target when the station regulates voltage, zero otherwise. */
    static double vscTargetUpcc(RegulationRef regulation, IidmStateView state) {
        return regulation.isWithMode(RegulationMode.VOLTAGE, state) ? regulation.regulatingTargetV(state) : 0;
    }

    /** The VsConverter.qPccControl of a converter station of the simplified DC model. */
    static String vscQpccControl(RegulationRef regulation, IidmStateView state) {
        return regulation.isRegulatingWithMode(RegulationMode.VOLTAGE, state) ? "voltagePcc" : "reactivePcc";
    }

    /** The four quantities the CGMES import reads as a single block for any converter. */
    interface ConverterSetpoints {
        double targetPpcc();

        double targetUdc();

        double p();

        double q();
    }

    record ConverterState(double targetPpcc, double targetUdc, double p, double q) implements ConverterSetpoints {
    }

    /**
     * The quantities describing a converter station of the simplified DC model, read from the given state of the
     * network.
     *
     * <p>Package private so that the change export writes the same values as the full export.</p>
     */
    static ConverterState computeConverterState(HvdcConverterStation<?> converterStation, IidmStateView state) {
        HvdcLine hvdcLine = converterStation.getHvdcLine();
        double activePowerSetpoint = state.getDouble(hvdcLine, CgmesChangeTranslator.ACTIVE_POWER_SETPOINT,
                hvdcLine::getActivePowerSetpoint);
        double targetPpcc;
        double targetUdc;
        double p;
        if (CgmesExportUtil.isConverterStationRectifier(converterStation, state)) {
            targetPpcc = activePowerSetpoint;
            targetUdc = 0.0;
            p = targetPpcc;
        } else {
            double otherConverterStationLossFactor = converterStation.getOtherConverterStation().map(HvdcConverterStation::getLossFactor).orElse(0.0f);
            double pDCRectifier = activePowerSetpoint * (1 - otherConverterStationLossFactor / 100);
            double idc = pDCRectifier / hvdcLine.getNominalV();
            double pDCInverter = -1 * (pDCRectifier - hvdcLine.getR() * idc * idc);
            double poleLoss = converterStation.getLossFactor() / 100 * Math.abs(pDCInverter);
            targetPpcc = 0.0;
            targetUdc = hvdcLine.getNominalV() - hvdcLine.getR() * idc;
            p = pDCInverter + poleLoss;
        }

        if (converterStation instanceof LccConverterStation lccConverterStation) {
            double powerFactor = state.getDouble(lccConverterStation, CgmesChangeTranslator.POWER_FACTOR,
                    lccConverterStation::getPowerFactor);
            return new ConverterState(targetPpcc, targetUdc, p, Math.abs(getQfromPowerFactor(p, powerFactor)));
        } else if (converterStation instanceof VscConverterStation vscConverterStation) {
            return new ConverterState(targetPpcc, targetUdc, vscConverterStation.getRegulatingTerminal().getP(),
                    -RegulationRef.of(vscConverterStation).localTargetQ(state));
        }
        return new ConverterState(targetPpcc, targetUdc, p, Double.NaN);
    }

    private static void writeDCTerminals(Network network, String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        for (HvdcLine line : network.getHvdcLines()) {
            writeHvdcLineDCTerminals(line, cimNamespace, writer, context);
        }
        for (DcConnectable<?> dcConnectable : network.getDcConnectables()) {
            for (DcTerminal dcTerminal : dcConnectable.getDcTerminals()) {
                String dcTerminalId = CgmesExportUtil.getDcTerminalId(dcTerminal, context);
                String className = dcConnectable instanceof AcDcConverter<?> ? ACDC_CONVERTER_DC_TERMINAL : CgmesNames.DC_TERMINAL;
                boolean connected = dcTerminal.isConnected();
                writeDCTerminal(dcTerminalId, className, connected, cimNamespace, writer, context);
            }
        }
        for (DcSwitch dcSwitch : network.getDcSwitches()) {
            boolean connected = !dcSwitch.isOpen();
            String dcTerminal1Id = context.getNamingStrategy().getCgmesIdFromAlias(dcSwitch, ALIAS_DC_TERMINAL1);
            writeDCTerminal(dcTerminal1Id, CgmesNames.DC_TERMINAL, connected, cimNamespace, writer, context);
            String dcTerminal2Id = context.getNamingStrategy().getCgmesIdFromAlias(dcSwitch, ALIAS_DC_TERMINAL2);
            writeDCTerminal(dcTerminal2Id, CgmesNames.DC_TERMINAL, connected, cimNamespace, writer, context);
        }
    }

    private static void writeHvdcLineDCTerminals(HvdcLine line, String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        String acdcConverterDcTerminal1 = context.getNamingStrategy().getCgmesIdFromAlias(line.getConverterStation1(), ALIAS_DC_TERMINAL1);
        writeDCTerminal(acdcConverterDcTerminal1, ACDC_CONVERTER_DC_TERMINAL, true, cimNamespace, writer, context);
        String acdcConverterDcTerminal1G = context.getNamingStrategy().getCgmesIdFromAlias(line.getConverterStation1(), ALIAS_DC_TERMINAL2);
        writeDCTerminal(acdcConverterDcTerminal1G, ACDC_CONVERTER_DC_TERMINAL, true, cimNamespace, writer, context);

        String acdcConverterDcTerminal2 = context.getNamingStrategy().getCgmesIdFromAlias(line.getConverterStation2(), ALIAS_DC_TERMINAL1);
        writeDCTerminal(acdcConverterDcTerminal2, ACDC_CONVERTER_DC_TERMINAL, true, cimNamespace, writer, context);
        String acdcConverterDcTerminal2G = context.getNamingStrategy().getCgmesIdFromAlias(line.getConverterStation1(), ALIAS_DC_TERMINAL2);
        writeDCTerminal(acdcConverterDcTerminal2G, ACDC_CONVERTER_DC_TERMINAL, true, cimNamespace, writer, context);

        String dcTerminal1 = context.getNamingStrategy().getCgmesIdFromAlias(line, ALIAS_DC_TERMINAL1);
        writeDCTerminal(dcTerminal1, CgmesNames.DC_TERMINAL, true, cimNamespace, writer, context);
        String dcTerminal1G = context.getNamingStrategy().getCgmesId(refTyped(line), DC_TERMINAL, ref("1G"));
        writeDCTerminal(dcTerminal1G, CgmesNames.DC_TERMINAL, true, cimNamespace, writer, context);

        String dcTerminal2 = context.getNamingStrategy().getCgmesIdFromAlias(line, ALIAS_DC_TERMINAL2);
        writeDCTerminal(dcTerminal2, CgmesNames.DC_TERMINAL, true, cimNamespace, writer, context);
        String dcTerminal2G = context.getNamingStrategy().getCgmesId(refTyped(line), DC_TERMINAL, ref("2G"));
        writeDCTerminal(dcTerminal2G, CgmesNames.DC_TERMINAL, true, cimNamespace, writer, context);
    }

    private static void writeDCTerminal(String terminalId, String className, boolean connected, String cimNamespace,
                                        XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        CgmesExportUtil.writeStartAbout(className, terminalId, cimNamespace, writer, context);
        writer.writeStartElement(cimNamespace, "ACDCTerminal.connected");
        writer.writeCharacters(Boolean.toString(connected));
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private static double getQfromPowerFactor(double p, double powerFactor) {
        if (powerFactor == 0.0) {
            return 0.0;
        }
        return p * Math.sqrt((1 - powerFactor * powerFactor) / (powerFactor * powerFactor));
    }

    private static void writeGeneratingUnits(Network network, CgmesChangeTranslator mapping, String cimNamespace,
                                             XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        // Multiple generators may share the same generation unit,
        // we will choose the participation factor from the last generator that references the generating unit
        // We only consider generators and batteries that have participation factors
        Map<String, CgmesPropertyBuffer> generatingUnits = new HashMap<>();
        for (Generator g : network.getGenerators()) {
            addGeneratingUnit(g, mapping.describeGeneratingUnit(g), generatingUnits, context);
        }
        for (Battery b : network.getBatteries()) {
            addGeneratingUnit(b, mapping.describeGeneratingUnit(b), generatingUnits, context);
        }
        for (CgmesPropertyBuffer generatingUnit : generatingUnits.values()) {
            generatingUnit.write(cimNamespace, writer, context);
        }
    }

    private static void addGeneratingUnit(Injection<?> injection, Result<CgmesPropertyBuffer, String> description,
                                          Map<String, CgmesPropertyBuffer> generatingUnits, CgmesExportContext context) {
        if (description instanceof Result.Success<CgmesPropertyBuffer, String> success) {
            generatingUnits.put(context.getNamingStrategy().getCgmesIdFromProperty(injection, PROPERTY_GENERATING_UNIT), success.value());
        }
    }

    /**
     * The GeneratingUnit whose participation factor describes the given injection, with the participation factor
     * read from the given state of the network, or {@code null} when it has none.
     *
     * <p>Package private so that the change export writes the same participation factor as the full export.</p>
     */
    static <I extends ReactiveLimitsHolder & Injection<I>> GeneratingUnit generatingUnitForGeneratorAndBatteries(
            I i, CgmesExportContext context, IidmStateView state) {
        String kind = obtainSynchronousMachineKind(i);
        if (!OPERATING_MODE_CONDENSER.equals(kind) && (i.getExtension(ActivePowerControl.class) != null || i.hasProperty(PROPERTY_NORMAL_PF))) {
            GeneratingUnit gu = new GeneratingUnit();
            gu.id = context.getNamingStrategy().getCgmesIdFromProperty(i, PROPERTY_GENERATING_UNIT);
            if (i.getExtension(ActivePowerControl.class) != null) {
                ActivePowerControl<I> activePowerControl = i.getExtension(ActivePowerControl.class);
                gu.participationFactor = state.getExtensionDouble(i, ActivePowerControl.NAME,
                        CgmesChangeTranslator.PARTICIPATION_FACTOR, activePowerControl::getParticipationFactor);
            } else {
                gu.participationFactor = Double.parseDouble(i.getProperty(PROPERTY_NORMAL_PF));
            }
            gu.className = generatingUnitClassname(i);
            return gu;
        }
        return null;
    }

    private static String generatingUnitClassname(Injection<?> i) {
        if (i instanceof Generator generator) {
            EnergySource energySource = generator.getEnergySource();
            if (energySource == EnergySource.HYDRO) {
                return "HydroGeneratingUnit";
            } else if (energySource == EnergySource.NUCLEAR) {
                return "NuclearGeneratingUnit";
            } else if (energySource == EnergySource.SOLAR) {
                return "SolarGeneratingUnit";
            } else if (energySource == EnergySource.THERMAL) {
                return "ThermalGeneratingUnit";
            } else if (energySource == EnergySource.WIND) {
                return "WindGeneratingUnit";
            } else {
                return "GeneratingUnit";
            }
        }
        if (i instanceof Battery) {
            return "HydroGeneratingUnit"; // TODO export battery differently in CGMES 3.0
        }
        throw new PowsyblException("Unexpected class for " + i.getId() + " using generating units: " + i.getClass());
    }

    private static void writeControlAreas(Network network, String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        for (Area area : network.getAreas()) {
            if (CgmesNames.CONTROL_AREA_TYPE_KIND_INTERCHANGE.equals(area.getAreaType())) {
                writeControlArea(area, cimNamespace, writer, context);
            }
        }
    }

    private static void writeControlArea(Area controlArea, String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        String areaId = context.getNamingStrategy().getCgmesId(controlArea.getId());
        CgmesExportUtil.writeStartAbout("ControlArea", areaId, cimNamespace, writer, context);
        writer.writeStartElement(cimNamespace, "ControlArea.netInterchange");
        double netInterchange = controlArea.getInterchangeTarget().orElse(Double.NaN);
        writer.writeCharacters(CgmesExportUtil.format(netInterchange));
        writer.writeEndElement();
        if (controlArea.hasProperty("pTolerance")) {
            double pTolerance = Double.parseDouble(controlArea.getProperty("pTolerance"));
            writer.writeStartElement(cimNamespace, "ControlArea.pTolerance");
            writer.writeCharacters(CgmesExportUtil.format(pTolerance));
            writer.writeEndElement();
        }
        writer.writeEndElement();
    }

    private static void writeAcDcConverter(LineCommutatedConverter converter, String cimNamespace, XMLStreamWriter writer,
                                           CgmesExportContext context) throws XMLStreamException {
        AcDcConverterState state = computeAcDcConverterState(converter, IidmStateView.LIVE);
        writeCsConverter(context.getNamingStrategy().getCgmesId(converter), state.targetPpcc(), state.targetUdc(),
                state.p(), state.q(), state.operatingModeOrQpccControl(), state.pPccControl(), cimNamespace, writer, context);
    }

    /**
     * The steady state of a converter of the detailed DC model.
     *
     * @param pPccControl                the control mode of the active power, a CsPpccControlKind for a line
     *                                   commutated converter and a VsPpccControlKind for a voltage source one
     * @param operatingModeOrQpccControl the CsOperatingModeKind of a line commutated converter, the VsQpccControlKind
     *                                   of a voltage source one: the second enumeration each class carries
     * @param targetQpcc                 the reactive power target of a voltage source converter, NaN for a line
     *                                   commutated one
     * @param targetUpcc                 the voltage target of a voltage source converter, NaN for a line commutated one
     */
    record AcDcConverterState(double targetPpcc, double targetUdc, double p, double q,
                              String pPccControl, String operatingModeOrQpccControl,
                              double targetQpcc, double targetUpcc) implements ConverterSetpoints {
    }

    /**
     * Compute the quantities describing a converter of the detailed DC model, read from the given state of the
     * network.
     *
     * <p>Package private so that the change export writes the same values as the full export.</p>
     */
    static AcDcConverterState computeAcDcConverterState(AcDcConverter<?> converter, IidmStateView state) {
        AcDcConverter.ControlMode controlMode = state.getEnum(converter, CgmesChangeTranslator.CONTROL_MODE,
                AcDcConverter.ControlMode.class, converter::getControlMode);
        boolean activePowerControl = controlMode == AcDcConverter.ControlMode.P_PCC;
        boolean dcVoltageControl = controlMode == AcDcConverter.ControlMode.V_DC;
        double targetPpcc = activePowerControl
                ? state.getDouble(converter, CgmesChangeTranslator.TARGET_P, converter::getTargetP) : 0.0;
        double targetUdc = dcVoltageControl
                ? state.getDouble(converter, CgmesChangeTranslator.TARGET_VDC, converter::getTargetVdc) : 0.0;
        double p = converter.getPccTerminal().getP();
        if (converter instanceof VoltageSourceConverter vsc) {
            RegulationRef regulation = RegulationRef.of(vsc);
            double targetQpcc = regulation.isWithMode(RegulationMode.REACTIVE_POWER, state) ? regulation.regulatingTargetQ(state) : 0;
            double targetUpcc = regulation.isWithMode(RegulationMode.VOLTAGE, state) ? regulation.regulatingTargetV(state) : 0;
            String qPccControl = regulation.isWithMode(RegulationMode.VOLTAGE, state) ? "voltagePcc" : "reactivePcc";
            return new AcDcConverterState(targetPpcc, targetUdc, p, regulation.localTargetQ(state),
                    activePowerControl ? "pPcc" : "udc", qPccControl, targetQpcc, targetUpcc);
        }
        double q = converter.getPccTerminal().getQ();
        return new AcDcConverterState(targetPpcc, targetUdc, p, q, activePowerControl ? "activePower" : "dcVoltage",
                targetPpcc > 0.0 ? "rectifier" : "inverter", Double.NaN, Double.NaN);
    }

    private static void writeCsConverter(String converterId, double targetPpcc, double targetUdc,
                                         double p, double q, String operatingMode, String pPccControl,
                                         String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        CgmesExportUtil.writeStartAbout(CgmesNames.CS_CONVERTER, converterId, cimNamespace, writer, context);
        writeCommonAcDcConverter(targetPpcc, targetUdc, p, q, cimNamespace, writer);
        writer.writeStartElement(cimNamespace, "CsConverter.targetAlpha");
        writer.writeCharacters(CgmesExportUtil.format(0.0));
        writer.writeEndElement();
        writer.writeStartElement(cimNamespace, "CsConverter.targetGamma");
        writer.writeCharacters(CgmesExportUtil.format(0.0));
        writer.writeEndElement();
        writer.writeStartElement(cimNamespace, "CsConverter.targetIdc");
        writer.writeCharacters(CgmesExportUtil.format(0.0));
        writer.writeEndElement();
        writer.writeEmptyElement(cimNamespace, "CsConverter.operatingMode");
        writer.writeAttribute(RDF_NAMESPACE, CgmesNames.RESOURCE, cimNamespace + "CsOperatingModeKind." + operatingMode);
        writer.writeEmptyElement(cimNamespace, "CsConverter.pPccControl");
        writer.writeAttribute(RDF_NAMESPACE, CgmesNames.RESOURCE, cimNamespace + "CsPpccControlKind." + pPccControl);
        writer.writeEndElement();
    }

    private static void writeCommonAcDcConverter(double targetPpcc, double targetUdc, double p, double q,
                                                 String cimNamespace, XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement(cimNamespace, "ACDCConverter.targetPpcc");
        writer.writeCharacters(CgmesExportUtil.format(targetPpcc));
        writer.writeEndElement();
        writer.writeStartElement(cimNamespace, "ACDCConverter.targetUdc");
        writer.writeCharacters(CgmesExportUtil.format(targetUdc));
        writer.writeEndElement();
        writer.writeStartElement(cimNamespace, "ACDCConverter.p");
        writer.writeCharacters(CgmesExportUtil.format(p));
        writer.writeEndElement();
        writer.writeStartElement(cimNamespace, "ACDCConverter.q");
        writer.writeCharacters(CgmesExportUtil.format(q));
        writer.writeEndElement();
    }

    enum RegulatingControlType {
        REGULATING_CONTROL, TAP_CHANGER_CONTROL
    }

    static final class GeneratingUnit {
        String id;
        String className;
        double participationFactor;
    }

    static class RegulatingControlView {
        String id;
        RegulatingControlType type;
        boolean discrete;
        boolean controlEnabled;
        double targetDeadband;
        double targetValue;
        String targetValueUnitMultiplier;

        RegulatingControlView(String id, RegulatingControlType type, boolean discrete, boolean controlEnabled,
                              double targetDeadband, double targetValue, String targetValueUnitMultiplier) {
            this.id = id;
            this.type = type;
            this.discrete = discrete;
            this.controlEnabled = controlEnabled;
            this.targetDeadband = targetDeadband;
            this.targetValue = targetValue;
            this.targetValueUnitMultiplier = targetValueUnitMultiplier;
        }
    }
}
