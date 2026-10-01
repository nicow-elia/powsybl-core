/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.mapping.Block;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.Battery;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.EnergySource;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Injection;
import com.powsybl.iidm.network.ReactiveLimitsHolder;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ReferencePriorities;
import com.powsybl.iidm.network.extensions.ReferencePriority;
import com.powsybl.iidm.network.regulation.RegulationMode;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_CGMES_ORIGINAL_CLASS;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_EQUIVALENT_INJECTION;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_GENERATING_UNIT;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_NORMAL_PF;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_REGULATION_CAPABILITY;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOCAL_TARGET_Q;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.LOCAL_TARGET_V;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.P0;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.PARTICIPATION_FACTOR;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.Q0;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.REFERENCE_PRIORITY;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_P;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_Q;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.TARGET_V;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VOLTAGE_REGULATION_ON;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_REGULATING;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.VR_TARGET_VALUE;
import static com.powsybl.cgmes.conversion.export.CgmesExportUtil.obtainCalculatedSynchronousMachineKind;
import static com.powsybl.cgmes.conversion.export.CgmesExportUtil.obtainCurve;
import static com.powsybl.cgmes.conversion.export.CgmesExportUtil.obtainSynchronousMachineKind;
import static com.powsybl.cgmes.conversion.export.CgmesPropertyBuffer.merge;
import static com.powsybl.commons.util.Result.failure;
import static com.powsybl.commons.util.Result.success;

/**
 * The CGMES machines of an IIDM generator, battery or boundary line in every export of the steady state hypothesis: a
 * SynchronousMachine, an ExternalNetworkInjection or an EquivalentInjection (powers in the load convention, the
 * control flag, the reference priority and the operating mode), the EquivalentInjection at the boundary of a boundary
 * line, and the GeneratingUnit carrying the participation factor. The control of a machine is
 * {@link RegulatingControlFamily}.
 *
 * <p>The keys a change of a machine is reported under and the blocks the CGMES update reads are declared here; the
 * dispatch of the change export, the probes and the capabilities of the in-place import are derived from them.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class MachineFamily extends AbstractFamily {

    private static final String REGULATING_COND_EQ_CONTROL_ENABLED = "RegulatingCondEq.controlEnabled";
    private static final String ROTATING_MACHINE_P = "RotatingMachine.p";
    private static final String ROTATING_MACHINE_Q = "RotatingMachine.q";
    private static final String SYNCHRONOUS_MACHINE_REFERENCE_PRIORITY = "SynchronousMachine.referencePriority";
    private static final String SYNCHRONOUS_MACHINE_OPERATING_MODE = "SynchronousMachine.operatingMode";

    /** The machine block of a generator: its powers, and the control flag it asks its control family for. */
    private static final Set<String> MACHINE_KEYS = Set.of(TARGET_P, LOCAL_TARGET_Q, VR_REGULATING);
    /** The keys of a generator a change of which describes its machine or its control. */
    static final Set<String> GENERATOR_KEYS = Stream.concat(MACHINE_KEYS.stream(), RegulatingControlFamily.GENERATOR_KEYS.stream())
            .collect(Collectors.toUnmodifiableSet());
    /** The keys of a boundary line a change of which describes the EquivalentInjection at its boundary. */
    static final Set<String> BOUNDARY_LINE_KEYS = Set.of(P0, Q0, TARGET_P, TARGET_Q, TARGET_V, VOLTAGE_REGULATION_ON);

    /** What the in-place import asks about a generator: every key, and the extension attributes of its blocks. */
    public static final List<String> GENERATOR_PROBES = List.of(TARGET_P, LOCAL_TARGET_Q, LOCAL_TARGET_V, VR_TARGET_VALUE,
            VR_REGULATING, ActivePowerControl.NAME + CgmesObjectDump.EXTENSION_SEPARATOR + PARTICIPATION_FACTOR,
            ReferencePriorities.NAME + CgmesObjectDump.EXTENSION_SEPARATOR + REFERENCE_PRIORITY);
    /** What the in-place import asks about the EquivalentInjection of a boundary line. */
    public static final List<String> BOUNDARY_LINE_PROBES = List.of(P0, Q0, TARGET_P, TARGET_Q, TARGET_V, VOLTAGE_REGULATION_ON);

    // The blocks the CGMES update reads. The p and q of a SynchronousMachine are two optional blocks of its query, but
    // the conversion only takes either when BOTH are bound (SynchronousMachineConversion: the updated power flow has to
    // be "defined"), so the two travel together here exactly as they do for an asynchronous machine
    public static final Block SYNCHRONOUS_MACHINE = new Block("synchronousMachinesForUpdate", List.of(CgmesNames.SYNCHRONOUS_MACHINE),
            List.of(ROTATING_MACHINE_P, ROTATING_MACHINE_Q),
            List.of(SYNCHRONOUS_MACHINE_REFERENCE_PRIORITY, SYNCHRONOUS_MACHINE_OPERATING_MODE, REGULATING_COND_EQ_CONTROL_ENABLED));
    public static final Block EXTERNAL_NETWORK_INJECTION = new Block("externalNetworkInjections",
            List.of(CgmesNames.EXTERNAL_NETWORK_INJECTION), "ExternalNetworkInjection.p", "ExternalNetworkInjection.q",
            "ExternalNetworkInjection.referencePriority", REGULATING_COND_EQ_CONTROL_ENABLED);
    public static final Block EQUIVALENT_INJECTION = new Block("equivalentInjections", List.of(CgmesNames.EQUIVALENT_INJECTION),
            List.of("EquivalentInjection.p", "EquivalentInjection.q"),
            List.of("EquivalentInjection.regulationStatus", "EquivalentInjection.regulationTarget"));
    public static final Block GENERATING_UNIT = new Block("generatingUnits", List.of("GeneratingUnit", "ThermalGeneratingUnit",
            "HydroGeneratingUnit", "NuclearGeneratingUnit", "SolarGeneratingUnit", "WindGeneratingUnit"), "GeneratingUnit.normalPF");

    private final RegulatingControlFamily controls;

    MachineFamily(CgmesExportContext context, IidmStateView state, Scope scope, RegulatingControlFamily controls) {
        super(context, state, scope);
        this.controls = controls;
    }

    /**
     * The block describing the EquivalentInjection that carries the injection at the boundary of a boundary line.
     *
     * <p>IIDM splits that injection in two: the fixed part on the boundary line itself and, when the model gives
     * the boundary a generation, the targets of that generation, whose sign is the generator convention. CGMES
     * holds a single injection in the load convention, so the two are combined back into one here.</p>
     *
     * <p>A paired boundary line, one half of a tie line, is described the same way: each half has an
     * EquivalentInjection of its own at its own boundary.</p>
     */
    Result<CgmesPropertyBuffer, String> boundaryLineUpdates(BoundaryLine boundaryLine) {
        // A full model writes the EquivalentInjection under a generated identifier, as the equipment export names it
        if (scope == Scope.CHANGES && !boundaryLine.hasProperty(PROPERTY_EQUIVALENT_INJECTION)) {
            return failure("boundary line " + boundaryLine.getId() + " has no CGMES EquivalentInjection");
        }
        return success(collect(out -> describeBoundaryInjection(boundaryLine, out)));
    }

    /** Describe the EquivalentInjection at the boundary of a boundary line, see {@link #boundaryLineUpdates}. */
    void describeBoundaryInjection(BoundaryLine boundaryLine, CgmesPropertySink out) {
        // Unlike a generator imported from an EquivalentInjection, a boundary line needs no regulation capability:
        // the CGMES update reads the regulation of a boundary EquivalentInjection from the file alone.
        BoundaryLine.Generation generation = boundaryLine.getGeneration();
        double targetP = generation != null ? state.getDouble(boundaryLine, TARGET_P, generation::getTargetP) : 0.0;
        double targetQ = generation != null ? state.getDouble(boundaryLine, TARGET_Q, generation::getTargetQ) : 0.0;
        double targetV = generation != null ? state.getDouble(boundaryLine, TARGET_V, generation::getTargetV) : Double.NaN;
        boolean regulationOn = generation != null
                && state.getBoolean(boundaryLine, VOLTAGE_REGULATION_ON, generation::isVoltageRegulationOn);
        double p = nonNaN(state.getDouble(boundaryLine, P0, boundaryLine::getP0)) - nonNaN(targetP);
        double q = nonNaN(state.getDouble(boundaryLine, Q0, boundaryLine::getQ0)) - nonNaN(targetQ);
        equivalentInjectionBlock(out, context.getNamingStrategy().getCgmesIdFromProperty(boundaryLine, PROPERTY_EQUIVALENT_INJECTION),
                p, q, regulationOn, targetV);
    }

    /**
     * The EquivalentInjection block: powers in the load convention, the regulation status and the regulation target.
     */
    private static void equivalentInjectionBlock(CgmesPropertySink out, String id, double p, double q, boolean regulationOn,
                                                 double targetV) {
        out.startObject(CgmesNames.EQUIVALENT_INJECTION, id)
                .value("EquivalentInjection.p", p)
                .value("EquivalentInjection.q", q)
                .value("EquivalentInjection.regulationStatus", regulationOn)
                // Always written, a target that is not a number as 0, as the full export writes it: left out, the
                // receiver would keep a target the sender no longer has
                .value("EquivalentInjection.regulationTarget", targetV)
                .endObject();
    }

    /** Zero for an undefined value, which is what the CGMES import writes back for one. */
    static double nonNaN(double value) {
        return Double.isNaN(value) ? 0.0 : value;
    }

    Result<CgmesPropertyBuffer, String> generatorUpdates(Generator generator, String attribute) {
        // An EquivalentInjection carries its regulation itself, it has no RegulatingControl of its own
        if (CgmesNames.EQUIVALENT_INJECTION.equals(originalClass(generator))) {
            return equivalentInjectionUpdates(generator);
        }
        // The regulation target lives entirely on the RegulatingControl, it must not restate the machine powers. The
        // CGMES update reads the control flag of a machine only together with its powers, its reference priority and
        // its operating mode, so switching the regulation writes the whole machine block and the control
        if (!RegulatingControlFamily.GENERATOR_KEYS.contains(attribute)) {
            return generatorMachineUpdates(generator);
        }
        if (!MACHINE_KEYS.contains(attribute)) {
            return controls.updatesOf(generator, state);
        }
        return generatorMachineUpdates(generator).flatMap(machine ->
                controls.updatesOf(generator, state).map(regulatingControl -> merge(machine, regulatingControl)));
    }

    private static String originalClass(Generator generator) {
        return generator.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS, CgmesNames.SYNCHRONOUS_MACHINE);
    }

    /**
     * The block describing the steady state of the CGMES machine an IIDM generator was imported from.
     *
     * <p>The CGMES update reads the properties of a machine as a single group, so the whole group is written
     * whatever the change was.</p>
     */
    Result<CgmesPropertyBuffer, String> generatorMachineUpdates(Generator generator) {
        Optional<String> refusal = generatorRefusal(generator);
        if (refusal.isPresent()) {
            return failure(refusal.get());
        }
        String originalClass = originalClass(generator);
        return switch (originalClass) {
            case CgmesNames.SYNCHRONOUS_MACHINE -> success(collect(out -> describeSynchronousMachine(generator, out)));
            case CgmesNames.EXTERNAL_NETWORK_INJECTION -> success(collect(out -> describeExternalNetworkInjection(generator, out)));
            case CgmesNames.EQUIVALENT_INJECTION -> equivalentInjectionUpdates(generator);
            default -> failure("generator " + generator.getId() + " is exported as a " + originalClass
                    + ", which has no steady state setpoints");
        };
    }

    /** Describe the SynchronousMachine of a generator. */
    void describeSynchronousMachine(Generator generator, CgmesPropertySink out) {
        double targetP = state.getDouble(generator, TARGET_P, generator::getTargetP);
        synchronousMachineBlock(out, cgmesId(generator), generatorControlEnabled(generator), loadConventionP(generator),
                loadConventionQ(generator), referencePriority(generator),
                obtainOperatingMode(generator, generator.getMinP(), generator.getMaxP(), targetP, state));
    }

    /** Describe the SynchronousMachine of a battery, which no change describes: read as the network stands. */
    void describeBattery(Battery battery, CgmesPropertySink out) {
        synchronousMachineBlock(out, cgmesId(battery), RegulatingControlFamily.flag(RegulationRef.of(battery), IidmStateView.LIVE),
                -battery.getTargetP(), -battery.getRegulatingTargetQ(),
                ReferencePriority.get(battery), obtainOperatingMode(battery, battery.getMinP(),
                        battery.getMaxP(), battery.getTargetP(), state));
    }

    /** The block of a SynchronousMachine, which the CGMES update reads as a whole; powers in the load convention. */
    private static void synchronousMachineBlock(CgmesPropertySink out, String id, boolean controlEnabled, double p,
                                                double q, int referencePriority, String operatingMode) {
        out.startObject(CgmesNames.SYNCHRONOUS_MACHINE, id)
                .value(REGULATING_COND_EQ_CONTROL_ENABLED, controlEnabled)
                .value(ROTATING_MACHINE_P, p)
                .value(ROTATING_MACHINE_Q, q)
                .value(SYNCHRONOUS_MACHINE_REFERENCE_PRIORITY, referencePriority)
                .enumValue(SYNCHRONOUS_MACHINE_OPERATING_MODE, "SynchronousMachineOperatingMode", operatingMode)
                .endObject();
    }

    /** The active power of a generator in the load convention CGMES uses for injections; IIDM uses the generator one. */
    private double loadConventionP(Generator generator) {
        return -state.getDouble(generator, TARGET_P, generator::getTargetP);
    }

    /** The reactive power of a generator in the load convention CGMES uses for injections. */
    private double loadConventionQ(Generator generator) {
        return -RegulationRef.of(generator).localTargetQ(state);
    }

    /**
     * Whether the generator takes part in its CGMES regulating control: the regulating flag of its voltage
     * regulation, whatever the mode, as the full export writes it since powsybl-core #3699. The receiving side
     * combines this flag with {@code RegulatingControl.enabled}.
     */
    private boolean generatorControlEnabled(Generator generator) {
        return RegulatingControlFamily.flag(RegulationRef.of(generator), state);
    }

    /**
     * The reference priority of a generator. An extension that was not there before the change set means a priority
     * of zero, which is what {@link ReferencePriority#get} returns when there is none.
     */
    private int referencePriority(Generator generator) {
        return state.getExtensionInt(generator, ReferencePriorities.NAME, REFERENCE_PRIORITY, 0,
                () -> ReferencePriority.get(generator));
    }

    /** Describe the ExternalNetworkInjection of a generator. */
    void describeExternalNetworkInjection(Generator generator, CgmesPropertySink out) {
        out.startObject(CgmesNames.EXTERNAL_NETWORK_INJECTION, cgmesId(generator))
                .value(REGULATING_COND_EQ_CONTROL_ENABLED, generatorControlEnabled(generator))
                .value("ExternalNetworkInjection.p", loadConventionP(generator))
                .value("ExternalNetworkInjection.q", loadConventionQ(generator))
                .value("ExternalNetworkInjection.referencePriority", referencePriority(generator))
                .endObject();
    }

    /**
     * The block describing an EquivalentInjection, which carries its own regulation instead of pointing at a
     * RegulatingControl.
     *
     * <p>The regulation target is always written, one that is not a number as {@code 0}; the CGMES update turns the
     * regulation off when the target is not a usable voltage. An EquivalentInjection that the equipment model gives
     * no regulation capability can never regulate on the receiving side whatever the file says.</p>
     */
    private Result<CgmesPropertyBuffer, String> equivalentInjectionUpdates(Generator generator) {
        if (RegulationRef.of(generator).isRegulating(state) && scope.honours(Refusal.NO_REGULATION_CAPABILITY)
                && !hasRegulationCapability(generator)) {
            return failure(Refusal.NO_REGULATION_CAPABILITY.message("the EquivalentInjection has no regulation"
                    + " capability, the CGMES update keeps its regulation off."));
        }
        return success(collect(out -> describeEquivalentInjection(generator, out)));
    }

    /** Describe the EquivalentInjection of a generator, see {@link #equivalentInjectionUpdates}. */
    void describeEquivalentInjection(Generator generator, CgmesPropertySink out) {
        RegulationRef regulation = RegulationRef.of(generator);
        // The regulation target of an EquivalentInjection is the local voltage target, as the full export writes it
        equivalentInjectionBlock(out, cgmesId(generator), loadConventionP(generator), loadConventionQ(generator),
                regulation.isRegulating(state), regulation.localTargetV(state));
    }

    private static boolean hasRegulationCapability(Identifiable<?> identifiable) {
        return Boolean.parseBoolean(identifiable.getProperty(PROPERTY_REGULATION_CAPABILITY));
    }

    /**
     * The reference priority selects the angle reference of the network, which CGMES carries on the machine itself,
     * so a change of it writes the machine block of the generator.
     */
    private Result<CgmesPropertyBuffer, String> referencePriorityUpdates(Identifiable<?> identifiable) {
        if (!(identifiable instanceof Generator generator)) {
            return failure(identifiable.getType() + " " + identifiable.getId()
                    + " has no CGMES machine to carry a reference priority");
        }
        if (CgmesNames.EQUIVALENT_INJECTION.equals(originalClass(generator))) {
            return failure("generator " + generator.getId()
                    + " is exported as an EquivalentInjection, which has no reference priority");
        }
        return generatorMachineUpdates(generator);
    }

    /**
     * The participation factor of a generator is a property of the CGMES GeneratingUnit it belongs to, not of the
     * machine, so a change of it describes that unit.
     */
    Result<CgmesPropertyBuffer, String> participationFactorUpdates(Identifiable<?> identifiable, String attribute) {
        if (attribute != null && !PARTICIPATION_FACTOR.equals(attribute)) {
            return failure("no CGMES steady state property corresponds to the " + attribute
                    + " of an active power control");
        }
        if (!(identifiable instanceof Generator generator)) {
            return failure(identifiable.getType() + " " + identifiable.getId() + " has no CGMES GeneratingUnit");
        }
        if (!CgmesNames.SYNCHRONOUS_MACHINE.equals(originalClass(generator))
                || !generator.hasProperty(PROPERTY_GENERATING_UNIT)) {
            return failure("generator " + generator.getId() + " has no CGMES GeneratingUnit");
        }
        Optional<String> refusal = generatorRefusal(generator);
        if (refusal.isPresent()) {
            return failure(refusal.get());
        }
        state.requireExtensionNotCreated(generator, ActivePowerControl.NAME);
        GeneratingUnit generatingUnit =
                generatingUnitForGeneratorAndBatteries(generator, context, state);
        if (generatingUnit == null) {
            return failure("generator " + generator.getId() + " is a condenser or has no participation factor");
        }
        return success(collect(out -> describeGeneratingUnit(generatingUnit, out)));
    }

    /** Describe the participation factor of a GeneratingUnit. */
    static void describeGeneratingUnit(GeneratingUnit generatingUnit, CgmesPropertySink out) {
        out.startObject(generatingUnit.className, generatingUnit.id)
                .value("GeneratingUnit.normalPF", generatingUnit.participationFactor)
                .endObject();
    }

    /**
     * The object refusals of a generator, which every block of it honours (machine, GeneratingUnit; its control
     * honours the same in {@link RegulatingControlFamily}): the import would give it a regulation, or it is in
     * another mode than the CGMES mode its import recorded, by which every update of the machine re-reads its
     * regulation.
     */
    private Optional<String> generatorRefusal(Generator generator) {
        return RegulationKeyRefusals.importGivesRegulation(generator, scope).or(() -> scope.honours(Refusal.CGMES_MODE)
                ? RegulatingControlFamily.cgmesModeRefusal(generator, RegulationRef.of(generator).mode(state))
                : Optional.empty());
    }

    /** The change of an extension of a generator: its reference priority or its participation factor. */
    Result<CgmesPropertyBuffer, String> extensionUpdates(Identifiable<?> identifiable, String extensionName, String attribute) {
        return switch (extensionName) {
            case ReferencePriorities.NAME -> referencePriorityUpdates(identifiable);
            case ActivePowerControl.NAME -> participationFactorUpdates(identifiable, attribute);
            default -> failure("extension " + extensionName + " has no CGMES steady state property");
        };
    }

    // The operating mode and the GeneratingUnit, shared with the full export

    private static final String OPERATING_MODE_GENERATOR = "generator";
    private static final String OPERATING_MODE_MOTOR = "motor";
    private static final String OPERATING_MODE_CONDENSER = "condenser";

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

    static final class GeneratingUnit {
        String id;
        String className;
        double participationFactor;
    }

}
