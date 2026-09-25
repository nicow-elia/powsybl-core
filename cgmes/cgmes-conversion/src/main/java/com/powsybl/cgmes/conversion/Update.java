/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion;

import com.powsybl.cgmes.conversion.elements.*;
import com.powsybl.cgmes.conversion.elements.dc.*;
import com.powsybl.cgmes.conversion.elements.transformers.ThreeWindingsTransformerConversion;
import com.powsybl.cgmes.conversion.elements.transformers.TwoWindingsTransformerConversion;
import com.powsybl.cgmes.model.CgmesModel;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.iidm.network.*;
import com.powsybl.triplestore.api.PropertyBag;
import com.powsybl.triplestore.api.PropertyBags;

import java.util.*;

/**
 * @author Luma Zamarreño {@literal <zamarrenolm at aia.es>}
 * @author José Antonio Marqués {@literal <marquesja at aia.es>}
 */

public final class Update {

    private static final String UNEXPECTED_ORIGINAL_CLASS = "Unexpected originalClass ";
    private static final PropertyBag EMPTY_PROPERTY_BAG = new PropertyBag(Collections.emptyList(), false);

    private Update() {
    }

    /**
     * Whether a scoped pass has nothing to visit and may therefore be skipped entirely.
     *
     * <p>Skipping matters because the first thing a pass does is ask the triple store for its property bags, and on
     * the tiny synthetic store of a difference model update that SPARQL call is the dominant cost: preparing and
     * evaluating a query that returns nothing is not free. A full update is never skipped, so that its report tree
     * and its behaviour stay exactly as they were.</p>
     */
    private static boolean nothingToDo(UpdateScope scope, Iterable<?> selection) {
        return !scope.isAll() && !selection.iterator().hasNext();
    }

    static void updateLoads(Network network, CgmesModel cgmes, Context context, UpdateScope scope) {
        Iterable<Load> loads = scope.select(network.getLoads(), network::getLoad);
        if (nothingToDo(scope, loads)) {
            return;
        }
        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.LOAD.name()));

        Map<String, PropertyBag> equipmentIdPropertyBag = new HashMap<>();
        addPropertyBags(cgmes.energyConsumers(), CgmesNames.ENERGY_CONSUMER, equipmentIdPropertyBag);
        addPropertyBags(cgmes.energySources(), CgmesNames.ENERGY_SOURCE, equipmentIdPropertyBag);
        addPropertyBags(cgmes.asynchronousMachines(), CgmesNames.ASYNCHRONOUS_MACHINE, equipmentIdPropertyBag);

        loads.forEach(load -> updateLoad(load, getPropertyBag(load.getId(), equipmentIdPropertyBag), context));
        context.popReportNode();
    }

    private static void updateLoad(Load load, PropertyBag cgmesData, Context context) {
        if (!load.isFictitious()) { // Loads from SvInjections are fictitious
            String originalClass = load.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS);

            switch (originalClass) {
                case CgmesNames.ENERGY_SOURCE -> EnergySourceConversion.update(load, cgmesData, context);
                case CgmesNames.ASYNCHRONOUS_MACHINE -> AsynchronousMachineConversion.update(load, cgmesData, context);
                case CgmesNames.CONFORM_LOAD, CgmesNames.NONCONFORM_LOAD, CgmesNames.STATION_SUPPLY, CgmesNames.ENERGY_CONSUMER ->
                    EnergyConsumerConversion.update(load, cgmesData, context);
                default -> throw new ConversionException(UNEXPECTED_ORIGINAL_CLASS + originalClass + " for Load: " + load.getId());
            }
        }
    }

    static void updateGenerators(Network network, CgmesModel cgmes, Context context, UpdateScope scope) {
        Iterable<Generator> generators = scope.select(network.getGenerators(), network::getGenerator);
        if (nothingToDo(scope, generators)) {
            return;
        }
        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.GENERATOR.name()));

        Map<String, PropertyBag> equipmentIdPropertyBag = new HashMap<>();
        addPropertyBags(cgmes.synchronousMachinesForUpdate(), CgmesNames.SYNCHRONOUS_MACHINE, equipmentIdPropertyBag);
        addPropertyBags(cgmes.equivalentInjections(), CgmesNames.EQUIVALENT_INJECTION, equipmentIdPropertyBag);
        addPropertyBags(cgmes.externalNetworkInjections(), CgmesNames.EXTERNAL_NETWORK_INJECTION, equipmentIdPropertyBag);

        generators.forEach(generator -> updateGenerator(generator, equipmentIdPropertyBag, context));
        context.popReportNode();
    }

    private static void updateGenerator(Generator generator, Map<String, PropertyBag> equipmentIdPropertyBag, Context context) {
        String originalClass = generator.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS);

        switch (originalClass) {
            case CgmesNames.SYNCHRONOUS_MACHINE -> SynchronousMachineConversion.update(generator, getPropertyBag(generator.getId(), equipmentIdPropertyBag), context);
            case CgmesNames.EQUIVALENT_INJECTION -> EquivalentInjectionConversion.update(generator, getEquivalentInjectionPropertyBag(generator.getId(), context), context);
            case CgmesNames.EXTERNAL_NETWORK_INJECTION -> ExternalNetworkInjectionConversion.update(generator, getPropertyBag(generator.getId(), equipmentIdPropertyBag), context);
            default -> throw new ConversionException(UNEXPECTED_ORIGINAL_CLASS + originalClass + " for Generator: " + generator.getId());
        }
    }

    static void updateTransformers(Network network, Context context, UpdateScope scope) {
        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.TWO_WINDINGS_TRANSFORMER.name()));
        scope.select(network.getTwoWindingsTransformers(), network::getTwoWindingsTransformer)
            .forEach(t2w -> TwoWindingsTransformerConversion.update(t2w, context));
        context.popReportNode();

        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.THREE_WINDINGS_TRANSFORMER.name()));
        scope.select(network.getThreeWindingsTransformers(), network::getThreeWindingsTransformer)
            .forEach(t3w -> ThreeWindingsTransformerConversion.update(t3w, context));
        context.popReportNode();
    }

    static void updateStaticVarCompensators(Network network, CgmesModel cgmes, Context context, UpdateScope scope) {
        Iterable<StaticVarCompensator> svcs =
                scope.select(network.getStaticVarCompensators(), network::getStaticVarCompensator);
        if (nothingToDo(scope, svcs)) {
            return;
        }
        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.STATIC_VAR_COMPENSATOR.name()));

        Map<String, PropertyBag> equipmentIdPropertyBag = new HashMap<>();
        addPropertyBags(cgmes.staticVarCompensators(), CgmesNames.STATIC_VAR_COMPENSATOR, equipmentIdPropertyBag);

        svcs.forEach(staticVarCompensator -> StaticVarCompensatorConversion.update(staticVarCompensator, getPropertyBag(staticVarCompensator.getId(), equipmentIdPropertyBag), context));
        context.popReportNode();
    }

    static void updateShuntCompensators(Network network, CgmesModel cgmes, Context context, UpdateScope scope) {
        Iterable<ShuntCompensator> shunts = scope.select(network.getShuntCompensators(), network::getShuntCompensator);
        if (nothingToDo(scope, shunts)) {
            return;
        }
        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.SHUNT_COMPENSATOR.name()));

        Map<String, PropertyBag> equipmentIdPropertyBag = new HashMap<>();
        addPropertyBags(cgmes.shuntCompensators(), CgmesNames.SHUNT_COMPENSATOR, equipmentIdPropertyBag);
        addPropertyBags(cgmes.equivalentShunts(), CgmesNames.EQUIVALENT_SHUNT, equipmentIdPropertyBag);

        shunts.forEach(shuntCompensator -> updateShuntCompensator(shuntCompensator, getPropertyBag(shuntCompensator.getId(), equipmentIdPropertyBag), context));
        context.popReportNode();
    }

    private static void updateShuntCompensator(ShuntCompensator shuntCompensator, PropertyBag cgmesData, Context context) {
        String isEquivalentShunt = shuntCompensator.getProperty(Conversion.PROPERTY_IS_EQUIVALENT_SHUNT);
        if (Boolean.parseBoolean(isEquivalentShunt)) {
            EquivalentShuntConversion.update(shuntCompensator, context);
        } else {
            ShuntConversion.update(shuntCompensator, cgmesData, context);
        }
    }

    static void updateHvdcLines(Network network, CgmesModel cgmes, Context context, UpdateScope scope) {
        Iterable<HvdcLine> hvdcLines = scope.select(network.getHvdcLines(), network::getHvdcLine);
        if (nothingToDo(scope, hvdcLines)) {
            return;
        }
        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.HVDC_LINE.name()));

        Map<String, PropertyBag> equipmentIdPropertyBag = new HashMap<>();
        addPropertyBags(cgmes.acDcConverters(), CgmesNames.ACDC_CONVERTER, equipmentIdPropertyBag);
        hvdcLines.forEach(hvdcLine -> HvdcLineConversion.update(hvdcLine,
                getPropertyBag(hvdcLine.getConverterStation1().getId(), equipmentIdPropertyBag),
                getPropertyBag(hvdcLine.getConverterStation2().getId(), equipmentIdPropertyBag),
                context));

        context.popReportNode();
    }

    static void updateBoundaryLines(Network network, Context context, UpdateScope scope) {
        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.BOUNDARY_LINE.name()));
        scope.select(network.getBoundaryLines(), network::getBoundaryLine)
            .forEach(boundaryLine -> updateBoundaryLine(boundaryLine, context));
        context.popReportNode();
    }

    private static void updateBoundaryLine(BoundaryLine boundaryLine, Context context) {
        String originalClass = boundaryLine.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS);
        switch (originalClass) {
            case CgmesNames.AC_LINE_SEGMENT -> ACLineSegmentConversion.update(boundaryLine, context);
            case CgmesNames.POWER_TRANSFORMER -> TwoWindingsTransformerConversion.update(boundaryLine, context);
            case CgmesNames.EQUIVALENT_BRANCH -> EquivalentBranchConversion.update(boundaryLine, context);
            case CgmesNames.SWITCH -> SwitchConversion.update(boundaryLine, getSwitchPropertyBag(boundaryLine.getId(), context), context);
            default -> throw new ConversionException(UNEXPECTED_ORIGINAL_CLASS + originalClass + " for BoundaryLine: " + boundaryLine.getId());
        }
    }

    static void updateLines(Network network, Context context, UpdateScope scope) {
        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.LINE.name()));
        scope.select(network.getLines(), network::getLine).forEach(line -> updateLine(line, context));
        context.popReportNode();
    }

    private static void updateLine(Line line, Context context) {
        String originalClass = line.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS);
        switch (originalClass) {
            case CgmesNames.AC_LINE_SEGMENT -> ACLineSegmentConversion.update(line, context);
            case CgmesNames.EQUIVALENT_BRANCH -> EquivalentBranchConversion.update(line, context);
            case CgmesNames.SERIES_COMPENSATOR -> SeriesCompensatorConversion.update(line, context);
            default -> throw new ConversionException(UNEXPECTED_ORIGINAL_CLASS + originalClass + " for Line: " + line.getId());
        }
    }

    static void updateSwitches(Network network, Context context, UpdateScope scope) {
        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.SWITCH.name()));
        scope.select(network.getSwitches(), network::getSwitch).forEach(sw -> updateSwitch(sw, context));
        context.popReportNode();
    }

    private static void updateSwitch(Switch sw, Context context) {
        if (sw.getProperty(Conversion.PROPERTY_IS_CREATED_FOR_DISCONNECTED_TERMINAL) != null) {
            TerminalConversion.update(sw, context);
            return;
        }
        String originalClass = sw.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS);
        switch (originalClass) {
            case CgmesNames.AC_LINE_SEGMENT -> ACLineSegmentConversion.update(sw, context);
            case CgmesNames.EQUIVALENT_BRANCH -> EquivalentBranchConversion.update(sw, context);
            case CgmesNames.SERIES_COMPENSATOR -> SeriesCompensatorConversion.update(sw, context);
            case CgmesNames.SWITCH, "Breaker", "Disconnector", "LoadBreakSwitch", "ProtectedSwitch", "GroundDisconnector", "Jumper" ->
                SwitchConversion.update(sw, getSwitchPropertyBag(sw.getId(), context), context);
            default -> throw new ConversionException(UNEXPECTED_ORIGINAL_CLASS + originalClass + " for Switch: " + sw.getId());
        }
    }

    // There are some node-breaker models that,
    // in addition to the information of opened switches also set
    // the terminal.connected property to false,
    // we have decided to create fictitious switches to precisely
    // map this situation to IIDM.
    // This behavior can be disabled through configuration.
    static void createFictitiousSwitchesForDisconnectedTerminalsDuringUpdate(Network network, CgmesModel cgmes, Context context) {
        if (createFictitiousSwitches(context)) {
            context.pushReportNode(CgmesReports.convertingDuringUpdateElementTypeReport(context.getReportNode(), CgmesNames.TERMINAL));
            cgmes.terminals().forEach(cgmesTerminal -> TerminalConversion.create(network, cgmesTerminal, context));
            context.popReportNode();
        }
    }

    private static boolean createFictitiousSwitches(Context context) {
        return context.config().getCreateFictitiousSwitchesForDisconnectedTerminalsMode() != CgmesImport.FictitiousSwitchesCreationMode.NEVER;
    }

    // In some TYNDP there are three or more acLineSegments at the boundary node, only two connected.
    static void createTieLinesWhenThereAreMoreThanTwoBoundaryLinesAtBoundaryNodeDuringUpdate(Network network, Context context) {
        context.pushReportNode(CgmesReports.convertingDuringUpdateElementTypeReport(context.getReportNode(), IdentifiableType.TIE_LINE.name()));
        TieLineConversion.createDuringUpdate(network, context);
        context.popReportNode();
    }

    static void updateVoltageLevels(Network network, Context context, UpdateScope scope) {
        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.VOLTAGE_LEVEL.name()));
        scope.select(network.getVoltageLevels(), network::getVoltageLevel)
            .forEach(voltageLevel -> VoltageLevelConversion.update(voltageLevel, context));
        context.popReportNode();
    }

    static void updateGrounds(Network network, Context context, UpdateScope scope) {
        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.GROUND.name()));
        scope.select(network.getGrounds(), network::getGround).forEach(ground -> GroundConversion.update(ground, context));
        context.popReportNode();
    }

    static void createFictitiousLoadsForSvInjectionsDuringUpdate(Network network, CgmesModel cgmes, Context context) {
        if (context.config().convertSvInjections()) {
            context.pushReportNode(CgmesReports.convertingDuringUpdateElementTypeReport(context.getReportNode(), CgmesNames.SV_INJECTION));
            cgmes.svInjections().forEach(svInjection -> SvInjectionConversion.create(network, svInjection));
            context.popReportNode();
        }
    }

    static void updateAreas(Network network, CgmesModel cgmes, Context context, UpdateScope scope) {
        Iterable<Area> areas = scope.select(network.getAreas(), network::getArea);
        if (nothingToDo(scope, areas)) {
            return;
        }
        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.AREA.name()));
        Map<String, PropertyBag> equipmentIdPropertyBag = new HashMap<>();
        addPropertyBags(cgmes.controlAreas(), CgmesNames.CONTROL_AREA, equipmentIdPropertyBag);
        areas.forEach(area -> ControlAreaConversion.update(area, getPropertyBag(area.getId(), equipmentIdPropertyBag), context));
        context.popReportNode();
    }

    static void updateDcSwitches(Network network, Context context, UpdateScope scope) {
        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.DC_SWITCH.name()));
        scope.select(network.getDcSwitches(), network::getDcSwitch)
            .forEach(dcSwitch -> DCSwitchConversion.update(dcSwitch, context));
        context.popReportNode();
    }

    static void updateDcGrounds(Network network, Context context, UpdateScope scope) {
        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.DC_GROUND.name()));
        scope.select(network.getDcGrounds(), network::getDcGround)
            .forEach(dcGround -> DCGroundConversion.update(dcGround, context));
        context.popReportNode();
    }

    static void updateDcLines(Network network, Context context, UpdateScope scope) {
        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.DC_LINE.name()));
        scope.select(network.getDcLines(), network::getDcLine)
            .forEach(dcLine -> DCLineSegmentConversion.update(dcLine, context));
        context.popReportNode();
    }

    static void updateAcDcConverters(Network network, CgmesModel cgmes, Context context, UpdateScope scope) {
        Iterable<LineCommutatedConverter> lccs =
                scope.select(network.getLineCommutatedConverters(), network::getLineCommutatedConverter);
        Iterable<VoltageSourceConverter> vscs =
                scope.select(network.getVoltageSourceConverters(), network::getVoltageSourceConverter);
        if (nothingToDo(scope, lccs) && nothingToDo(scope, vscs)) {
            return;
        }
        Map<String, PropertyBag> equipmentIdPropertyBag = new HashMap<>();
        addPropertyBags(cgmes.acDcConverters(), CgmesNames.ACDC_CONVERTER, equipmentIdPropertyBag);

        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.LINE_COMMUTATED_CONVERTER.name()));
        lccs.forEach(lcc -> AcDcConverterConversion.update(lcc, getPropertyBag(lcc.getId(), equipmentIdPropertyBag), context));
        context.popReportNode();

        context.pushReportNode(CgmesReports.updatingElementTypeReport(context.getReportNode(), IdentifiableType.VOLTAGE_SOURCE_CONVERTER.name()));
        vscs.forEach(vsc -> AcDcConverterConversion.update(vsc, getPropertyBag(vsc.getId(), equipmentIdPropertyBag), context));
        context.popReportNode();
    }

    static void updateAndCompleteVoltageAndAngles(Network network, Context context) {
        context.pushReportNode(CgmesReports.settingVoltagesAndAnglesReport(context.getReportNode()));
        // update voltage and angles
        network.getBusView().getBuses().forEach(bus -> NodeConversion.update(bus, context));

        // Voltage and angle in boundary buses
        network.getBoundaryLineStream(BoundaryLineFilter.UNPAIRED)
                .forEach(AbstractConductingEquipmentConversion::calculateVoltageAndAngleInBoundaryBus);

        // Now in tieLines
        network.getTieLines().forEach(tieLine -> AbstractConductingEquipmentConversion.calculateVoltageAndAngleInBoundaryBus(tieLine.getBoundaryLine1(), tieLine.getBoundaryLine2()));

        // Voltage and angle in starBus as properties
        network.getThreeWindingsTransformers().forEach(ThreeWindingsTransformerConversion::calculateVoltageAndAngleInStarBus);
        context.popReportNode();
    }

    private static void addPropertyBags(PropertyBags propertyBags, String idTag, Map<String, PropertyBag> equipmentIdPropertyBag) {
        propertyBags.forEach(propertyBag -> equipmentIdPropertyBag.put(propertyBag.getId(idTag), propertyBag));
    }

    private static PropertyBag getPropertyBag(String identifiableId, Map<String, PropertyBag> equipmentIdPropertyBag) {
        return equipmentIdPropertyBag.getOrDefault(identifiableId, EMPTY_PROPERTY_BAG);
    }

    private static PropertyBag getEquivalentInjectionPropertyBag(String equivalentInjectionId, Context context) {
        PropertyBag cgmesData = context.equivalentInjection(equivalentInjectionId);
        return cgmesData != null ? cgmesData : EMPTY_PROPERTY_BAG;
    }

    private static PropertyBag getSwitchPropertyBag(String switchId, Context context) {
        PropertyBag cgmesData = context.cgmesSwitch(switchId);
        return cgmesData != null ? cgmesData : EMPTY_PROPERTY_BAG;
    }
}
