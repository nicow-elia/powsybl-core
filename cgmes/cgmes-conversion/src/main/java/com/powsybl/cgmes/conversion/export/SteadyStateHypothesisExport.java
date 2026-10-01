/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.CgmesExport;
import com.powsybl.cgmes.conversion.export.RegulatingControlFamily.RegulatingControlView;
import com.powsybl.cgmes.extensions.CgmesTapChanger;
import com.powsybl.cgmes.model.CgmesMetadataModel;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.regulation.RegulationMode;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.util.*;

import static com.powsybl.cgmes.conversion.Conversion.*;
import static com.powsybl.cgmes.conversion.elements.transformers.AbstractTransformerConversion.getCgmesTapChanger;
import static com.powsybl.cgmes.conversion.export.CgmesExportUtil.*;
import static com.powsybl.cgmes.conversion.naming.CgmesObjectReference.Part.*;
import static com.powsybl.cgmes.conversion.naming.CgmesObjectReference.ref;
import static com.powsybl.cgmes.conversion.naming.CgmesObjectReference.refTyped;
import static com.powsybl.cgmes.model.CgmesNamespace.RDF_NAMESPACE;

/**
 * @author Miora Ralambotiana {@literal <miora.ralambotiana at rte-france.com>}
 * @author Luma Zamarreño {@literal <zamarrenolm at aia.es>}
 */
public final class SteadyStateHypothesisExport {

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
        final Map<String, List<RegulatingControlView>> regulatingControlViews = new HashMap<>();
        String cimNamespace = context.getCim().getNamespace();
        // The objects are described by the mapping a change export reads too, written straight to the document
        CgmesChangeTranslator mapping = CgmesChangeTranslator.forFullModel(network, context);
        CgmesPropertySink out = new CgmesPropertySink.Xml(cimNamespace, writer, context);

        try {
            CgmesExportUtil.writeRdfRoot(cimNamespace, context.getCim().getEuPrefix(), context.getCim().getEuNamespace(), writer);

            if (context.getCimVersion() >= 16) {
                CgmesExportUtil.writeModelDescription(network, CgmesSubset.STEADY_STATE_HYPOTHESIS, writer, model, context);
            }

            writeLoads(network, mapping, out, context);
            writeFictitiousInjections(network, cimNamespace, writer, context);
            writeEquivalentInjections(network, mapping, out, context);
            writeTapChangers(network, mapping, regulatingControlViews, out, context);
            writeGenerators(network, mapping, regulatingControlViews, out, context);
            writeBatteries(network, mapping, out);
            writeShuntCompensators(network, mapping, regulatingControlViews, out, context);
            writeStaticVarCompensators(network, mapping, regulatingControlViews, out, context);
            writeRegulatingControls(regulatingControlViews, out);
            writeGeneratingUnitsParticitationFactors(network, out, context);
            writeConverters(network, mapping, out, cimNamespace, writer, context);
            writeDCTerminals(network, cimNamespace, writer, context);
            // FIXME open status of retained switches in bus-branch models
            writeSwitches(network, mapping, out, context);
            writeTerminals(network, cimNamespace, writer, context);
            writeControlAreas(network, cimNamespace, writer, context);

            writer.writeEndDocument();
        } catch (XMLStreamException e) {
            throw new UncheckedXmlStreamException(e);
        }
    }

    private static void writeSwitches(Network network, CgmesChangeTranslator mapping, CgmesPropertySink out, CgmesExportContext context) {
        for (Switch sw : network.getSwitches()) {
            if (context.isExportedEquipment(sw)) {
                String switchType = sw.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS); // may be null
                if (!isSwitchImportedFromAcLineSegmentEquivalentBranchOrSeriesCompensator(switchType)) {
                    mapping.describeSwitch(sw, out);
                }
            }
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

    private static void writeFictitiousInjections(Network network, String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        for (VoltageLevel vl : network.getVoltageLevels()) {
            if (vl.getTopologyKind() == TopologyKind.NODE_BREAKER && !context.isBusBranchExport()) {
                writeNodeBreakerFictitiousInjections(vl, cimNamespace, writer, context);
            } else {
                writeBusBranchFictitiousInjections(vl, cimNamespace, writer, context);
            }
        }
    }

    private static void writeNodeBreakerFictitiousInjections(VoltageLevel vl, String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        VoltageLevel.NodeBreakerView nb = vl.getNodeBreakerView();
        for (int node : nb.getNodes()) {
            double p = nb.getFictitiousP0(node);
            double q = nb.getFictitiousQ0(node);
            if (p != 0.0 || q != 0.0) {
                String loadId = context.getNamingStrategy().getCgmesId(refTyped(vl), FICTITIOUS, ref("NCL"), ref(node));
                String terminalId = context.getNamingStrategy().getCgmesId(refTyped(vl), FICTITIOUS, TERMINAL, ref(node));
                writeFictitiousInjection(loadId, terminalId, p, q, cimNamespace, writer, context);
            }
        }
    }

    private static void writeBusBranchFictitiousInjections(VoltageLevel vl, String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        for (Bus b : vl.getBusBreakerView().getBuses()) {
            double p = b.getFictitiousP0();
            double q = b.getFictitiousQ0();
            if (p != 0.0 || q != 0.0) {
                String loadId = context.getNamingStrategy().getCgmesId(refTyped(b), FICTITIOUS, ref("NCL"));
                String terminalId = context.getNamingStrategy().getCgmesId(refTyped(b), FICTITIOUS, TERMINAL);
                writeFictitiousInjection(loadId, terminalId, p, q, cimNamespace, writer, context);
            }
        }
    }

    private static void writeFictitiousInjection(String loadId, String terminalId, double p, double q,
                                                 String cimNamespace, XMLStreamWriter writer,
                                                 CgmesExportContext context) throws XMLStreamException {
        CgmesChangeTranslator.describeFictitiousInjection(loadId, p, q, new CgmesPropertySink.Xml(cimNamespace, writer, context));
        // Terminal connected state (always connected in SSH for fictitious terminals)
        writeTerminal(terminalId, true, cimNamespace, writer, context);
    }

    private static void writeEquivalentInjections(Network network, CgmesChangeTranslator mapping, CgmesPropertySink out, CgmesExportContext context) {
        // One equivalent injection for every boundary line
        List<String> exported = new ArrayList<>();

        for (BoundaryLine bl : network.getBoundaryLines(BoundaryLineFilter.ALL)) {
            String equivalentInjectionId = context.getNamingStrategy().getCgmesIdFromProperty(bl, PROPERTY_EQUIVALENT_INJECTION);
            if (!exported.contains(equivalentInjectionId)) {
                mapping.describeBoundaryInjection(bl, out);
                exported.add(equivalentInjectionId);
            }
        }
    }

    private static void writeTapChangers(Network network, CgmesChangeTranslator mapping,
                                         Map<String, List<RegulatingControlView>> regulatingControlViews,
                                         CgmesPropertySink out, CgmesExportContext context) {
        for (TwoWindingsTransformer twt : network.getTwoWindingsTransformers()) {
            if (twt.hasPhaseTapChanger()) {
                String aliasType = twt.getAliasFromType(ALIAS_PHASE_TAP_CHANGER2).isPresent() && twt.getAliasFromType(ALIAS_PHASE_TAP_CHANGER1).isEmpty() ?
                    ALIAS_PHASE_TAP_CHANGER2 : ALIAS_PHASE_TAP_CHANGER1;
                writeTapChanger(twt, aliasType, PHASE_TAP_CHANGER, 1, CgmesNames.PHASE_TAP_CHANGER_TABULAR, twt.getPhaseTapChanger(), mapping, regulatingControlViews, out, context);
            }
            if (twt.hasRatioTapChanger()) {
                String aliasType = twt.getAliasFromType(ALIAS_RATIO_TAP_CHANGER2).isPresent() && twt.getAliasFromType(ALIAS_RATIO_TAP_CHANGER1).isEmpty() ?
                    ALIAS_RATIO_TAP_CHANGER2 : ALIAS_RATIO_TAP_CHANGER1;
                writeTapChanger(twt, aliasType, RATIO_TAP_CHANGER, 1, CgmesNames.RATIO_TAP_CHANGER, twt.getRatioTapChanger(), mapping, regulatingControlViews, out, context);
            }
        }

        for (ThreeWindingsTransformer twt : network.getThreeWindingsTransformers()) {
            for (ThreeWindingsTransformer.Leg leg : Arrays.asList(twt.getLeg1(), twt.getLeg2(), twt.getLeg3())) {
                int endNumber = leg.getSide().getNum();
                if (leg.hasPhaseTapChanger()) {
                    String aliasType = getPhaseTapChangerAliasType(Integer.toString(endNumber));
                    writeTapChanger(twt, aliasType, PHASE_TAP_CHANGER, endNumber, CgmesNames.PHASE_TAP_CHANGER_TABULAR,
                        leg.getPhaseTapChanger(), mapping, regulatingControlViews, out, context);
                }
                if (leg.hasRatioTapChanger()) {
                    String aliasType = getRatioTapChangerAliasType(Integer.toString(endNumber));
                    writeTapChanger(twt, aliasType, RATIO_TAP_CHANGER, endNumber, CgmesNames.RATIO_TAP_CHANGER, leg.getRatioTapChanger(), mapping, regulatingControlViews, out, context);
                }
            }
        }
    }

    private static <C extends Connectable<C>> void writeTapChanger(C twt, String aliasType, Part part, int endNumber,
                                                                   String defaultType, TapChanger<?, ?, ?, ?> tc,
                                                                   CgmesChangeTranslator mapping,
                                                                   Map<String, List<RegulatingControlView>> regulatingControlViews,
                                                                   CgmesPropertySink out, CgmesExportContext context) {
        String cgmesTapChangerId = twt.getAliasFromType(aliasType).orElse(null);
        String tapChangerControlId = getTapChangerControlId(twt, part, endNumber, cgmesTapChangerId, context);
        String end = twt instanceof ThreeWindingsTransformer ? Integer.toString(endNumber) : "";
        TapChangerRef ref = new TapChangerRef(twt, (tc instanceof RatioTapChanger
                ? CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX : CgmesChangeTranslator.PHASE_TAP_CHANGER_PREFIX) + end, tc);

        mapping.describeTapChanger(twt, aliasType, defaultType, ref, out);
        if (tc instanceof RatioTapChanger) {
            addRegulatingControlView(RegulatingControlFamily.regulatingControlView(ref.regulation(), tapChangerControlId, context, IidmStateView.LIVE),
                    regulatingControlViews);
        } else if (tc instanceof PhaseTapChanger ptc) {
            boolean recordedControl = getCgmesTapChanger(twt, cgmesTapChangerId).map(CgmesTapChanger::getControlId).isPresent();
            addRegulatingControlView(RegulatingControlFamily.phaseTapChangerView(ptc, tapChangerControlId, recordedControl, ref, context, IidmStateView.LIVE),
                    regulatingControlViews);
        }

        // If we are exporting equipment definitions the hidden tap changer will not be exported
        // because it has been included in the model for the only tap changer left in IIDM
        // If we are exporting only SSH, SV, ... we have to write the step we have saved for it
        if (!context.isExportEquipment()) {
            Optional<CgmesTapChanger> hiddenCombinedTapChanger = getHiddenCombinedTapChanger(twt, cgmesTapChangerId);
            if (hiddenCombinedTapChanger.isPresent()) {
                CgmesChangeTranslator.describeHiddenTapChanger(hiddenCombinedTapChanger.get(), defaultType, out);
            }
        }
    }

    private static void writeShuntCompensators(Network network, CgmesChangeTranslator mapping, Map<String, List<RegulatingControlView>> regulatingControlViews,
                                               CgmesPropertySink out, CgmesExportContext context) {
        for (ShuntCompensator s : network.getShuntCompensators()) {
            if ("true".equals(s.getProperty(PROPERTY_IS_EQUIVALENT_SHUNT))) {
                continue;
            }
            mapping.describeShunt(s, out);
            addRegulatingControlView(RegulationRef.of(s), getRegulatingControlId(s, context), regulatingControlViews, context);
        }
    }

    private static void writeGenerators(Network network, CgmesChangeTranslator mapping, Map<String, List<RegulatingControlView>> regulatingControlViews,
                                                 CgmesPropertySink out, CgmesExportContext context) {
        for (Generator g : network.getGenerators()) {
            String cgmesOriginalClass = g.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS, CgmesNames.SYNCHRONOUS_MACHINE);

            switch (cgmesOriginalClass) {
                case CgmesNames.EQUIVALENT_INJECTION:
                    mapping.describeEquivalentInjection(g, out);
                    break;
                case CgmesNames.EXTERNAL_NETWORK_INJECTION:
                    mapping.describeExternalNetworkInjection(g, out);
                    addRegulatingControlView(RegulationRef.of(g), getRegulatingControlId(g, context), regulatingControlViews, context);
                    break;
                case CgmesNames.SYNCHRONOUS_MACHINE:
                    mapping.describeSynchronousMachine(g, out);
                    addRegulatingControlView(RegulationRef.of(g), getRegulatingControlId(g, context), regulatingControlViews, context);
                    break;
                default:
                    throw new PowsyblException("Unexpected cgmes equipment " + cgmesOriginalClass);
            }
        }
    }

    private static void writeBatteries(Network network, CgmesChangeTranslator mapping, CgmesPropertySink out) {
        for (Battery b : network.getBatteries()) {
            mapping.describeBattery(b, out);
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

    private static void writeStaticVarCompensators(Network network, CgmesChangeTranslator mapping, Map<String, List<RegulatingControlView>> regulatingControlViews,
                                                   CgmesPropertySink out, CgmesExportContext context) {
        for (StaticVarCompensator svc : network.getStaticVarCompensators()) {
            mapping.describeStaticVarCompensator(svc, out);
            addRegulatingControlView(RegulationRef.of(svc), getRegulatingControlId(svc, context), regulatingControlViews, context);
        }
    }

    private static void addRegulatingControlView(RegulatingControlView rcv, Map<String, List<RegulatingControlView>> regulatingControlViews) {
        // Multiple tap changers can be stored at the same equipment
        // We use the tap changer id as part of the key for storing the tap changer control id
        if (rcv != null) {
            regulatingControlViews.computeIfAbsent(rcv.id, k -> new ArrayList<>()).add(rcv);
        }
    }

    private static String getRegulatingControlId(Identifiable<?> identifiable, CgmesExportContext context) {
        return context.getNamingStrategy().getCgmesIdFromProperty(identifiable, PROPERTY_REGULATING_CONTROL);
    }

    private static void addRegulatingControlView(RegulationRef regulation, String regulatingControlId,
                                                 Map<String, List<RegulatingControlView>> regulatingControlViews, CgmesExportContext context) {
        addRegulatingControlView(RegulatingControlFamily.regulatingControlView(regulation, regulatingControlId, context, IidmStateView.LIVE),
                regulatingControlViews);
    }

    private static void writeRegulatingControls(Map<String, List<RegulatingControlView>> regulatingControlViews, CgmesPropertySink out) {
        for (List<RegulatingControlView> views : regulatingControlViews.values()) {
            RegulatingControlFamily.describe(views, out);
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

    private static void writeLoads(Network network, CgmesChangeTranslator mapping, CgmesPropertySink out, CgmesExportContext context) {
        for (Load load : network.getLoads()) {
            if (context.isExportedEquipment(load) && !mapping.describeLoad(load, out)) {
                throw new PowsyblException("Unexpected class name: " + obtainLoadClassName(load, context));
            }
        }
    }

    // if EQ is not exported, the original class name is preserved
    // Package private so that the change export names a load as the full export does
    static String obtainLoadClassName(Load load, CgmesExportContext context) {
        String originalClassName = load.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS);
        return (originalClassName != null && !context.isExportEquipment()) ? originalClassName : CgmesExportUtil.loadClassName(load);
    }

    private static void writeConverters(Network network, CgmesChangeTranslator mapping, CgmesPropertySink out, String cimNamespace,
                                        XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        for (HvdcConverterStation<?> converterStation : network.getHvdcConverterStations()) {
            writeConverterStation(converterStation, mapping, out, cimNamespace, writer, context);
        }
        for (LineCommutatedConverter lccConverter : network.getLineCommutatedConverters()) {
            writeAcDcConverter(lccConverter, cimNamespace, writer, context);
        }
        for (VoltageSourceConverter vscConverter : network.getVoltageSourceConverters()) {
            mapping.describeVoltageSourceConverter(vscConverter, out);
        }
    }

    private static void writeConverterStation(HvdcConverterStation<?> converterStation, CgmesChangeTranslator mapping,
                                              CgmesPropertySink out, String cimNamespace,
                                              XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        String converterId = context.getNamingStrategy().getCgmesId(converterStation);
        ConverterState state = computeConverterState(converterStation, IidmStateView.LIVE);
        if (converterStation instanceof LccConverterStation) {
            String operatingMode = CgmesExportUtil.isConverterStationRectifier(converterStation) ? "rectifier" : "inverter";
            String pPccControl = CgmesExportUtil.isConverterStationRectifier(converterStation) ? "activePower" : "dcVoltage";
            writeCsConverter(converterId, state.targetPpcc(), state.targetUdc(), state.p(), state.q(), operatingMode, pPccControl, cimNamespace, writer, context);
        } else if (converterStation instanceof VscConverterStation vscConverterStation) {
            mapping.describeVscConverterStation(vscConverterStation, out);
        }
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

    private static void writeGeneratingUnitsParticitationFactors(Network network, CgmesPropertySink out, CgmesExportContext context) {
        // Multiple generators may share the same generation unit,
        // we will choose the participation factor from the last generator that references the generating unit
        // We only consider generators and batteries that have participation factors
        Map<String, GeneratingUnit> generatingUnits = new HashMap<>();
        for (Generator g : network.getGenerators()) {
            GeneratingUnit gu = generatingUnitForGeneratorAndBatteries(g, context);
            if (gu != null) {
                generatingUnits.put(gu.id, gu);
            }
        }
        for (Battery b : network.getBatteries()) {
            GeneratingUnit gu = generatingUnitForGeneratorAndBatteries(b, context);
            if (gu != null) {
                generatingUnits.put(gu.id, gu);
            }
        }
        for (GeneratingUnit gu : generatingUnits.values()) {
            CgmesChangeTranslator.describeGeneratingUnit(gu, out);
        }
    }

    private static <I extends ReactiveLimitsHolder & Injection<I>> GeneratingUnit generatingUnitForGeneratorAndBatteries(I i, CgmesExportContext context) {
        return generatingUnitForGeneratorAndBatteries(i, context, IidmStateView.LIVE);
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

    private static void writeAcDcConverter(AcDcConverter<?> converter, String cimNamespace, XMLStreamWriter writer, CgmesExportContext context) throws XMLStreamException {
        String converterId = context.getNamingStrategy().getCgmesId(converter);
        AcDcConverterState state = computeAcDcConverterState(converter, IidmStateView.LIVE);
        if (converter instanceof LineCommutatedConverter) {
            writeCsConverter(converterId, state.targetPpcc(), state.targetUdc(), state.p(), state.q(),
                    state.operatingModeOrQpccControl(), state.pPccControl(), cimNamespace, writer, context);
        }
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
            return new AcDcConverterState(targetPpcc, targetUdc, p, regulation.localTargetQ(state),
                    activePowerControl ? "pPcc" : "udc", VsConverterControlFamily.qPccControl(regulation, true, state),
                    VsConverterControlFamily.targetQpcc(regulation, true, null, state),
                    VsConverterControlFamily.targetUpcc(regulation, state));
        }
        double q = converter.getPccTerminal().getQ();
        return new AcDcConverterState(targetPpcc, targetUdc, p, q, activePowerControl ? "activePower" : "dcVoltage",
                targetPpcc > 0.0 ? "rectifier" : "inverter", Double.NaN, Double.NaN);
    }

    // A CsConverter keeps this writer until B4 (owner decision O2b) decides how the powers of a detailed line commutated
    // converter are written; every other converter is described by the change mapping
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

    static final class GeneratingUnit {
        String id;
        String className;
        double participationFactor;
    }
}
