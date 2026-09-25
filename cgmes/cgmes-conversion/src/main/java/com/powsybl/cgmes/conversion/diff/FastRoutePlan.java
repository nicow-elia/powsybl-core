/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.UpdateScope;
import com.powsybl.cgmes.conversion.diff.DiffSubjectResolver.ResolvedSubject;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.PropertyGroup;
import com.powsybl.cgmes.conversion.export.CgmesLimitIndex;
import com.powsybl.cgmes.conversion.export.CgmesObjectDump;
import com.powsybl.cgmes.extensions.CgmesMetadataModels;
import com.powsybl.cgmes.model.CgmesMetadataModel;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TapChanger;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.ValidationLevel;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ReferencePriorities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Everything the in-place route decides before it modifies anything.
 *
 * <p>An update that fails half way through leaves a network in a state no file describes, so a difference model
 * update resolves every subject, completes every consistency group, checks the metadata and evaluates the optional
 * reverse check <em>first</em>, and only then writes. What comes out of the planning is a list of typed objects with
 * complete statements, which is exactly the content of a partial steady state hypothesis document.</p>
 *
 * <p>The plan is also what {@link CgmesDiffImport#canApplyInPlace} answers with: building it modifies nothing, so
 * asking whether a difference applies and applying it are the same code.</p>
 *
 * <p>Cost: one pass over the statements, plus at most one pass over the equipment carrying CGMES objects that IIDM
 * does not model (built lazily by {@link DiffSubjectResolver}), plus one export context when a group has to be
 * completed.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class FastRoutePlan {

    private static final Logger LOGGER = LoggerFactory.getLogger(FastRoutePlan.class);

    /** How close two numbers have to be for the reverse check to call them equal. */
    private static final double RELATIVE_TOLERANCE = 1e-6;

    /** The modeling authority written when neither the difference nor the network names one. */
    private static final String UNKNOWN_AUTHORITY = "unknown";

    static final String VOLTAGE_LEVEL_HIGH_LIMIT = "VoltageLevel.highVoltageLimit";
    static final String VOLTAGE_LEVEL_LOW_LIMIT = "VoltageLevel.lowVoltageLimit";

    /** The suffix of {@code SynchronousMachine.referencePriority} and its external network injection twin. */
    private static final String REFERENCE_PRIORITY_SUFFIX = ".referencePriority";
    private static final String NORMAL_PF = "GeneratingUnit.normalPF";
    private static final String TAP_CHANGER_CONTROL_ENABLED = "TapChanger.controlEnabled";
    private static final String REGULATING_CONTROL_ENABLED = "RegulatingControl.enabled";

    /**
     * One object of the synthetic update document.
     *
     * @param about      the subject as {@code rdf:about}, in the form the receiving network uses
     * @param rdfType    the CIM class to write, which is what the update queries select on
     * @param statements the complete forward statements of that object, type statements left out
     */
    record TypedObject(String about, String rdfType, List<CgmesStatement> statements) {
    }

    /**
     * What is registered as the model of the profile after the difference was applied.
     *
     * <p>It is explicit rather than simply the header of the difference, because a revert has to register the
     * <em>predecessor</em> of the reverted model, not the inverted model itself, and because a foreign header may
     * leave out values the {@code fullModels} and {@code modelDates} queries require.</p>
     */
    record RegisteredIdentity(String id, int version, String description, String modelingAuthoritySet,
                              List<String> profiles, List<String> dependentOn, List<String> supersedes,
                              ZonedDateTime scenarioTime, ZonedDateTime created) {
    }

    /** One difference model, ready to be written. */
    record PlannedModel(DifferenceModelHeader header, RegisteredIdentity identity, List<TypedObject> objects) {

        /** How many statements this model writes, for the report. */
        int statementCount() {
            return objects.stream().mapToInt(object -> object.statements().size()).sum();
        }
    }

    /**
     * One statement that is applied with an IIDM setter rather than through the update workflow.
     *
     * <p>Nothing in the CGMES update path reads {@code ACLineSegment.r} or {@code VoltageLevel.highVoltageLimit}, so
     * writing them into the synthetic document would change nothing at all. They are kept apart, validated while
     * nothing is modified yet, and applied by {@code DirectEqApplier} after the update workflow has run.</p>
     *
     * @param subset    the profile of the difference model the statement came from
     * @param subject   what the statement is about
     * @param statement the statement itself
     */
    record DirectStatement(CgmesSubset subset, ResolvedSubject subject, CgmesStatement statement, double value) {
    }

    private final CgmesDiffImport.Decision decision;
    private final List<PlannedModel> models;
    private final List<DirectStatement> directStatements;
    private final Set<String> touchedIidmIds;
    private final CgmesLimitIndex limitIndex;

    private FastRoutePlan(CgmesDiffImport.Decision decision, List<PlannedModel> models,
                          List<DirectStatement> directStatements, Set<String> touchedIidmIds) {
        this(decision, models, directStatements, touchedIidmIds, null);
    }

    private FastRoutePlan(CgmesDiffImport.Decision decision, List<PlannedModel> models,
                          List<DirectStatement> directStatements, Set<String> touchedIidmIds,
                          CgmesLimitIndex limitIndex) {
        this.decision = decision;
        this.models = models;
        this.directStatements = directStatements;
        this.touchedIidmIds = touchedIidmIds;
        this.limitIndex = limitIndex;
    }

    /**
     * The index of the CGMES limit identifiers the subject resolution built, or {@code null} when it never needed
     * one. Handing it over saves the applier a second walk over the operational limits groups.
     */
    CgmesLimitIndex limitIndex() {
        return limitIndex;
    }

    /** The statements applied with IIDM setters, after the update workflow. */
    List<DirectStatement> directStatements() {
        return directStatements;
    }

    /** Whether any difference model of the set describes the steady state hypothesis profile. */
    boolean touchesSteadyStateHypothesis() {
        return models.stream()
                .anyMatch(model -> model.header().subset() == CgmesSubset.STEADY_STATE_HYPOTHESIS);
    }

    CgmesDiffImport.Decision decision() {
        return decision;
    }

    /** The models to write, only meaningful when the decision is {@link CgmesDiffImport.Route#FAST}. */
    List<PlannedModel> models() {
        return models;
    }

    /** Every IIDM object the update touches. */
    Set<String> touchedIidmIds() {
        return touchedIidmIds;
    }

    /**
     * The scope the update runs with.
     *
     * <p>Restricting it is only safe when the network is already at steady state hypothesis validation level, so
     * that the setters of the update validate every value themselves, and when the import does not remove the
     * properties and aliases the resolution relies on.</p>
     */
    UpdateScope updateScope(Network network, Conversion.Config config, CgmesDiffImport.Options options) {
        if (!options.isScopedUpdate()
                || network.getValidationLevel() != ValidationLevel.STEADY_STATE_HYPOTHESIS
                || config.getRemovePropertiesAndAliasesAfterImport()) {
            return UpdateScope.ALL;
        }
        return UpdateScope.of(touchedIidmIds);
    }

    /**
     * Plan the application of a difference model set.
     *
     * @param inverted whether the reverse statements are applied instead of the forward ones, that is whether this
     *                 is a revert
     */
    static FastRoutePlan of(Network network, DifferenceModelSet diffs, CgmesDiffImport.Options options,
                            boolean inverted) {
        CgmesDiffImport.Decision structural = FastRouteCapabilities.check(inverted ? invert(diffs) : diffs);
        if (structural.route() != CgmesDiffImport.Route.FAST) {
            return new FastRoutePlan(structural, List.of(), List.of(), Set.of());
        }
        return new Planner(network, diffs, options, inverted).plan();
    }

    /** The same set with every model inverted, which is what a revert applies. */
    private static DifferenceModelSet invert(DifferenceModelSet diffs) {
        List<DifferenceModel> inverted = new ArrayList<>();
        diffs.models().values().forEach(model -> inverted.add(model.inverted(model.header())));
        return new DifferenceModelSet(inverted);
    }

    /** One planning run. */
    private static final class Planner {

        private final Network network;
        private final DifferenceModelSet diffs;
        private final CgmesDiffImport.Options options;
        private final boolean inverted;
        private final DiffSubjectResolver resolver;
        private final CgmesObjectDump dump;
        private final List<CgmesDiffImport.BlockingStatement> blocking = new ArrayList<>();
        private final List<DirectStatement> directs = new ArrayList<>();
        private final Set<String> touched = new LinkedHashSet<>();

        private Planner(Network network, DifferenceModelSet diffs, CgmesDiffImport.Options options, boolean inverted) {
            this.network = network;
            this.diffs = diffs;
            this.options = options;
            this.inverted = inverted;
            this.resolver = new DiffSubjectResolver(network);
            this.dump = new CgmesObjectDump(network);
        }

        private FastRoutePlan plan() {
            checkOneCimNamespace();
            List<PlannedModel> models = new ArrayList<>();
            for (DifferenceModel model : diffs.models().values()) {
                if (model.isEmpty()) {
                    continue;
                }
                PlannedModel planned = planModel(model);
                if (planned != null) {
                    models.add(planned);
                }
            }
            checkVoltageLevelLimits();
            if (!blocking.isEmpty()) {
                return new FastRoutePlan(
                        new CgmesDiffImport.Decision(CgmesDiffImport.Route.SLOW_REQUIRED, blocking), List.of(),
                        List.of(), Set.of());
            }
            if (models.isEmpty() && directs.isEmpty()) {
                return new FastRoutePlan(new CgmesDiffImport.Decision(CgmesDiffImport.Route.NOOP, List.of()),
                        List.of(), List.of(), Set.of());
            }
            return new FastRoutePlan(new CgmesDiffImport.Decision(CgmesDiffImport.Route.FAST, List.of()),
                    List.copyOf(models), List.copyOf(directs), Set.copyOf(touched), resolver.limitIndex());
        }

        private void checkOneCimNamespace() {
            String first = null;
            for (DifferenceModel model : diffs.models().values()) {
                String namespace = model.header().cimNamespace();
                if (first == null) {
                    first = namespace;
                } else if (!first.equals(namespace)) {
                    blocking.add(new CgmesDiffImport.BlockingStatement(model.header().subset(), null,
                            "the difference models of one set mix the CIM namespaces " + first + " and " + namespace));
                    return;
                }
            }
        }

        private PlannedModel planModel(DifferenceModel model) {
            CgmesSubset subset = model.header().subset();
            List<CgmesStatement> forward = inverted ? model.reverse() : model.forward();
            List<CgmesStatement> reverse = inverted ? model.forward() : model.reverse();
            Optional<CgmesMetadataModel> current = currentModel(subset);
            checkSupersedes(model, current);

            Map<String, List<CgmesStatement>> bySubject = new LinkedHashMap<>();
            Map<String, String> hints = new LinkedHashMap<>();
            for (CgmesStatement statement : forward) {
                if (statement.isType()) {
                    hints.putIfAbsent(statement.subjectId(), statement.value());
                    bySubject.computeIfAbsent(statement.subjectId(), id -> new ArrayList<>());
                } else {
                    bySubject.computeIfAbsent(statement.subjectId(), id -> new ArrayList<>()).add(statement);
                    if (statement.className() != null) {
                        hints.putIfAbsent(statement.subjectId(), statement.className());
                    }
                }
            }

            int directsBefore = directs.size();
            List<TypedObject> objects = new ArrayList<>(bySubject.size());
            for (Map.Entry<String, List<CgmesStatement>> entry : bySubject.entrySet()) {
                TypedObject object = planSubject(subset, entry.getKey(), entry.getValue(), hints.get(entry.getKey()));
                if (object != null && !object.statements().isEmpty()) {
                    objects.add(object);
                }
            }
            checkReverse(subset, reverse, model.preconditions());
            if (objects.isEmpty() && directs.size() == directsBefore) {
                return null;
            }
            // A model whose statements are all applied with setters still carries a header, so that the metadata of
            // the profile is registered exactly as it is for a model the update workflow reads
            return new PlannedModel(model.header(), identity(model, subset, current), List.copyOf(objects));
        }

        private TypedObject planSubject(CgmesSubset subset, String subjectId, List<CgmesStatement> statements,
                                        String classNameHint) {
            Set<String> properties = new LinkedHashSet<>();
            statements.forEach(statement -> properties.add(statement.property()));
            Optional<ResolvedSubject> resolved = resolver.resolve(subjectId, properties, classNameHint);
            if (resolved.isEmpty()) {
                CgmesStatement first = statements.isEmpty() ? null : statements.get(0);
                blocking.add(new CgmesDiffImport.BlockingStatement(subset, first,
                        statements.isEmpty()
                                ? "object creation cannot be applied in place: " + subjectId + " is unknown"
                                : subjectId + ": " + resolver.reasonFor(subjectId, properties, classNameHint)));
                return null;
            }
            ResolvedSubject subject = resolved.get();
            if (classNameHint != null && !classNameHint.equals(subject.rdfType())) {
                DiffSubjectResolver.logIgnoredHint(subjectId, classNameHint, subject.rdfType());
            }
            if (statements.isEmpty()) {
                // A forward only type statement on an object the network holds is a no-op
                return null;
            }
            touched.addAll(subject.iidmIds());
            if (options.isVariantSafeOnly()) {
                checkVariantSafe(subset, subject, statements);
            }
            if (FastRouteCapabilities.spec(subject.family()).handler() == FastRouteCapabilities.Handler.DIRECT_SETTER) {
                statements.forEach(statement -> planDirectStatement(subset, subject, statement));
                return null;
            }
            List<CgmesStatement> complete = complete(subset, subject, subjectId, statements);
            return complete == null ? null : new TypedObject(subject.about(), subject.rdfType(), complete);
        }

        /**
         * Whether every statement of one subject writes state the receiving network stores per variant.
         *
         * <p>Only run when the caller asked for it, that is when a <em>variant</em> of the network is bound to a
         * stored state. The family table answers most of it from the document alone; the rest &mdash; the four
         * {@link FastRouteCapabilities.VariantSafety#NETWORK_DEPENDENT} cases &mdash; depends on what the receiving
         * network looks like and is decided here, against the resolved subject, while nothing is modified yet.</p>
         *
         * <p>Only the statements the difference carries are judged. A property the completion adds from the
         * network afterwards writes the value the network already holds, so it changes nothing in any variant.</p>
         */
        private void checkVariantSafe(CgmesSubset subset, ResolvedSubject subject,
                                      List<CgmesStatement> statements) {
            for (CgmesStatement statement : statements) {
                String property = statement.property();
                FastRouteCapabilities.VariantSafety safety =
                        FastRouteCapabilities.variantSafety(subject.family(), property);
                if (safety == FastRouteCapabilities.VariantSafety.UNSAFE) {
                    blocking.add(new CgmesDiffImport.BlockingStatement(subset, statement,
                            FastRouteCapabilities.variantUnsafeReason(subject.family(), property)));
                } else if (safety == FastRouteCapabilities.VariantSafety.NETWORK_DEPENDENT) {
                    checkNetworkDependent(subset, subject, statement);
                }
            }
        }

        /** The four rules a {@link FastRouteCapabilities.VariantSafety#NETWORK_DEPENDENT} family is judged by. */
        private void checkNetworkDependent(CgmesSubset subset, ResolvedSubject subject, CgmesStatement statement) {
            String property = statement.property();
            boolean unsafe = switch (subject.family()) {
                case SYNCHRONOUS_MACHINE, EXTERNAL_NETWORK_INJECTION -> property.endsWith(REFERENCE_PRIORITY_SUFFIX)
                        && referencePriorityWouldCreateTheExtension(subject, statement.value());
                case GENERATING_UNIT -> NORMAL_PF.equals(property) && aGeneratorHasNoActivePowerControl(subject);
                case RATIO_TAP_CHANGER, PHASE_TAP_CHANGER, REGULATING_CONTROL ->
                    isRegulationSwitchedOn(property, statement.value())
                            && aTapChangerHasNoLoadTapChangingCapabilities(subject);
                case VS_CONVERTER -> !isDetailedConverter(subject);
                default -> false;
            };
            if (unsafe) {
                blocking.add(new CgmesDiffImport.BlockingStatement(subset, statement,
                        FastRouteCapabilities.variantUnsafeReason(subject.family(), property)));
            }
        }

        /**
         * A reference priority above zero on a generator that has no {@code ReferencePriorities} extension yet
         * creates one, and creating an extension is a change of the network, not of a variant.
         */
        private boolean referencePriorityWouldCreateTheExtension(ResolvedSubject subject, String value) {
            int priority;
            try {
                priority = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                // Not a number: the update would ignore it, so nothing is written
                return false;
            }
            if (priority <= 0) {
                return false;
            }
            return objectsOf(subject).stream()
                    .filter(Generator.class::isInstance)
                    .anyMatch(object -> object.getExtension(ReferencePriorities.class) == null);
        }

        /**
         * {@code GeneratingUnit.normalPF} reaches the per-variant participation factor only when the generator
         * already carries an {@code ActivePowerControl}; otherwise the update creates that extension or writes an
         * IIDM property, and neither is per variant.
         */
        private boolean aGeneratorHasNoActivePowerControl(ResolvedSubject subject) {
            return objectsOf(subject).stream()
                    .filter(Generator.class::isInstance)
                    .anyMatch(object -> object.getExtension(ActivePowerControl.class) == null);
        }

        private static boolean isRegulationSwitchedOn(String property, String value) {
            return (TAP_CHANGER_CONTROL_ENABLED.equals(property) || REGULATING_CONTROL_ENABLED.equals(property))
                    && Boolean.parseBoolean(value.trim());
        }

        /**
         * Switching the regulation of a tap changer on raises {@code loadTapChangingCapabilities} when the tap
         * changer does not have it, and that flag is a plain field of the tap changer, shared by all variants.
         *
         * <p>Every tap changer of the owning transformer is looked at, not only the one the subject names: a
         * regulating control reaches the tap changer through the CGMES tap changer index, and a three windings
         * transformer carries one per leg.</p>
         */
        private boolean aTapChangerHasNoLoadTapChangingCapabilities(ResolvedSubject subject) {
            for (Identifiable<?> object : objectsOf(subject)) {
                for (TapChanger<?, ?, ?, ?> tapChanger : tapChangersOf(object)) {
                    if (!tapChanger.hasLoadTapChangingCapabilities()) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static List<TapChanger<?, ?, ?, ?>> tapChangersOf(Identifiable<?> object) {
            List<TapChanger<?, ?, ?, ?>> tapChangers = new ArrayList<>();
            if (object instanceof TwoWindingsTransformer transformer) {
                transformer.getOptionalRatioTapChanger().ifPresent(tapChangers::add);
                transformer.getOptionalPhaseTapChanger().ifPresent(tapChangers::add);
            } else if (object instanceof ThreeWindingsTransformer transformer) {
                transformer.getLegs().forEach(leg -> {
                    leg.getOptionalRatioTapChanger().ifPresent(tapChangers::add);
                    leg.getOptionalPhaseTapChanger().ifPresent(tapChangers::add);
                });
            }
            return tapChangers;
        }

        /**
         * Whether the converter subject resolved to the detailed DC model.
         *
         * <p>In the simplified model &mdash; the default &mdash; a voltage source converter update also writes
         * {@code HvdcLine.maxP} and {@code VscConverterStation.lossFactor}, neither of which is per variant. The
         * detailed model writes only per-variant setpoints.</p>
         */
        private boolean isDetailedConverter(ResolvedSubject subject) {
            return objectsOf(subject).stream().anyMatch(VoltageSourceConverter.class::isInstance);
        }

        /**
         * Validate a statement that will be applied with an IIDM setter, while nothing is modified yet.
         *
         * <p>The setters of IIDM validate their arguments themselves and throw, which after a partly applied update
         * would leave the network in a state no document describes. Checking here means the whole plan is refused
         * instead.</p>
         */
        private void planDirectStatement(CgmesSubset subset, ResolvedSubject subject, CgmesStatement statement) {
            double value;
            try {
                value = Double.parseDouble(statement.value().trim());
            } catch (NumberFormatException e) {
                blocking.add(new CgmesDiffImport.BlockingStatement(subset, statement,
                        "the value " + statement.value() + " is not a number"));
                return;
            }
            if (!Double.isFinite(value)) {
                blocking.add(new CgmesDiffImport.BlockingStatement(subset, statement,
                        "impedance and limit values must be finite"));
                return;
            }
            if (isSeriesImpedance(statement.property()) && value < 0) {
                blocking.add(new CgmesDiffImport.BlockingStatement(subset, statement,
                        "impedance values must be finite (r, x >= 0)"));
                return;
            }
            if (isVoltageLevelLimit(statement.property()) && value < 0) {
                // IIDM refuses a negative voltage limit in its setter, which would throw after the update workflow
                // and the other setters had already run
                blocking.add(new CgmesDiffImport.BlockingStatement(subset, statement,
                        "voltage limits must be positive"));
                return;
            }
            directs.add(new DirectStatement(subset, subject, statement, value));
        }

        private static boolean isSeriesImpedance(String property) {
            return property.endsWith(".r") || property.endsWith(".x")
                    || property.endsWith(".r21") || property.endsWith(".x21");
        }

        private static boolean isVoltageLevelLimit(String property) {
            return VOLTAGE_LEVEL_HIGH_LIMIT.equals(property) || VOLTAGE_LEVEL_LOW_LIMIT.equals(property);
        }

        /**
         * A voltage level only accepts a low limit below its high limit, whichever of the two the difference states.
         *
         * <p>Both may be stated, and then they are checked against each other; a single one is checked against the
         * value the network holds.</p>
         */
        private void checkVoltageLevelLimits() {
            Map<String, Double> newHigh = new LinkedHashMap<>();
            Map<String, Double> newLow = new LinkedHashMap<>();
            Map<String, DirectStatement> byVoltageLevel = new LinkedHashMap<>();
            for (DirectStatement direct : directs) {
                String property = direct.statement().property();
                if (!(direct.subject().owner() instanceof VoltageLevel voltageLevel)) {
                    continue;
                }
                if (VOLTAGE_LEVEL_HIGH_LIMIT.equals(property)) {
                    newHigh.put(voltageLevel.getId(), direct.value());
                    byVoltageLevel.put(voltageLevel.getId(), direct);
                } else if (VOLTAGE_LEVEL_LOW_LIMIT.equals(property)) {
                    newLow.put(voltageLevel.getId(), direct.value());
                    byVoltageLevel.put(voltageLevel.getId(), direct);
                }
            }
            byVoltageLevel.forEach((id, direct) -> {
                VoltageLevel voltageLevel = network.getVoltageLevel(id);
                double high = newHigh.getOrDefault(id, voltageLevel.getHighVoltageLimit());
                double low = newLow.getOrDefault(id, voltageLevel.getLowVoltageLimit());
                if (Double.isFinite(high) && Double.isFinite(low) && high < low) {
                    blocking.add(new CgmesDiffImport.BlockingStatement(direct.subset(), direct.statement(),
                            "the resulting voltage limits of " + id + " would be low " + low + " above high " + high));
                }
            });
        }

        /**
         * The statements of one subject, with every property of a touched consistency group added from the
         * receiving network.
         *
         * <p>The statements of the difference always win: what is added is what the difference does not mention and
         * therefore, by definition, did not change. Optional properties are completed too, not only the required
         * ones: a SPARQL optional block says the query still matches without them, but a conversion may still need
         * one &mdash; a voltage source converter that regulates its voltage has no target without
         * {@code VsConverter.targetUpcc}, and the importer then switches the regulation off. Completing them costs
         * one probe of the export mapping and removes a whole class of silent half-applications.</p>
         *
         * <p>A required property the receiver cannot produce blocks the in-place route; an optional one it cannot
         * produce is simply left out, which is what a CGMES file describing that state does.</p>
         */
        private List<CgmesStatement> complete(CgmesSubset subset, ResolvedSubject subject, String subjectId,
                                              List<CgmesStatement> statements) {
            Map<String, CgmesStatement> byProperty = new LinkedHashMap<>();
            statements.forEach(statement -> byProperty.putIfAbsent(statement.property(), statement));

            Set<String> missingRequired = new LinkedHashSet<>();
            Set<String> missingOptional = new LinkedHashSet<>();
            for (PropertyGroup group : FastRouteCapabilities.spec(subject.family()).groups()) {
                if (group.properties().stream().noneMatch(byProperty::containsKey)) {
                    continue;
                }
                group.required().stream().filter(property -> !byProperty.containsKey(property))
                        .forEach(missingRequired::add);
                group.optional().stream().filter(property -> !byProperty.containsKey(property))
                        .forEach(missingOptional::add);
            }
            if (missingRequired.isEmpty() && missingOptional.isEmpty()) {
                return List.copyOf(byProperty.values());
            }
            Map<String, CgmesStatement> fromNetwork = dumpOf(subject, subjectId);
            List<String> stillMissing = new ArrayList<>();
            for (String property : missingRequired) {
                CgmesStatement statement = fromNetwork.get(property);
                if (statement == null) {
                    stillMissing.add(property);
                } else {
                    byProperty.put(property, statement);
                }
            }
            missingOptional.stream().map(fromNetwork::get).filter(Objects::nonNull)
                    .forEach(statement -> byProperty.put(statement.property(), statement));
            if (!stillMissing.isEmpty()) {
                blocking.add(new CgmesDiffImport.BlockingStatement(subset, statements.get(0),
                        "the properties " + stillMissing + " are read together with the ones stated here and the"
                                + " receiver cannot derive them: " + dumpFailureReason(subject, subjectId)));
                return null;
            }
            return List.copyOf(byProperty.values());
        }

        /**
         * What the network currently says about the subject, by property.
         *
         * <p>Every IIDM object the subject resolved to is probed, because a CGMES object may be described by more
         * than one of them, and the result is filtered back to this subject: probing a transformer answers for all
         * of its tap changers, probing an HVDC line for both of its converters.</p>
         */
        private Map<String, CgmesStatement> dumpOf(ResolvedSubject subject, String subjectId) {
            Map<String, CgmesStatement> byProperty = new LinkedHashMap<>();
            for (Identifiable<?> object : objectsOf(subject)) {
                for (String attributeKey : DiffProbes.probesFor(subject, object)) {
                    for (CgmesStatement statement : dump.statementsFor(object.getId(), attributeKey)) {
                        if (statement.subjectId().equals(subjectId) && !statement.isType()) {
                            byProperty.putIfAbsent(statement.property(), statement);
                        }
                    }
                }
            }
            return byProperty;
        }

        private String dumpFailureReason(ResolvedSubject subject, String subjectId) {
            for (Identifiable<?> object : objectsOf(subject)) {
                for (String attributeKey : DiffProbes.probesFor(subject, object)) {
                    String reason = dump.dump(object.getId(), attributeKey)
                            .fold(statements -> null, failure -> failure);
                    if (reason != null) {
                        return reason;
                    }
                }
            }
            return "no CGMES mapping produces them for " + subjectId;
        }

        /** The objects describing the subject, its owner first so that its tap changer probes come first. */
        private List<Identifiable<?>> objectsOf(ResolvedSubject subject) {
            List<Identifiable<?>> objects = new ArrayList<>();
            objects.add(subject.owner());
            for (String id : subject.iidmIds()) {
                Identifiable<?> object = network.getIdentifiable(id);
                if (object != null && !object.equals(subject.owner())) {
                    objects.add(object);
                }
            }
            return objects;
        }

        private Optional<CgmesMetadataModel> currentModel(CgmesSubset subset) {
            CgmesMetadataModels models = network.getExtension(CgmesMetadataModels.class);
            return models == null ? Optional.empty() : models.getModelForSubset(subset);
        }

        /**
         * A difference is a delta on a named base, and applying or undoing it on another base silently corrupts the
         * network.
         *
         * <p>Forward, the base is what the {@code md:Model.Supersedes} of the difference names: the network has to be
         * at one of those models. Backward, the base is the difference <em>itself</em>: only a network that is at
         * {@code m} can undo {@code m}, which is what makes a chain of differences walk back one step at a time
         * instead of leaving a network that claims to be at a predecessor while holding the content of a later
         * model.</p>
         *
         * <p>Both checks are skipped when the network holds no model of the profile, and the forward one also when
         * the difference declares no {@code Supersedes} &mdash; a foreign producer may leave it out, and refusing
         * every such difference would make the parameter useless.</p>
         */
        private void checkSupersedes(DifferenceModel model, Optional<CgmesMetadataModel> current) {
            if (!options.isCheckSupersedes() || current.isEmpty()) {
                LOGGER.debug("No base check for difference model {}: check {}, network model {}",
                        model.header().id(), options.isCheckSupersedes(), current.map(CgmesMetadataModel::getId));
                return;
            }
            String currentId = current.get().getId();
            if (inverted) {
                if (!sameModel(model.header().id(), currentId)) {
                    blocking.add(new CgmesDiffImport.BlockingStatement(model.header().subset(), null,
                            "difference model " + model.header().id() + " cannot be reverted: the network is at "
                                    + currentId));
                }
                return;
            }
            List<String> supersedes = model.header().supersedes();
            if (supersedes.isEmpty()) {
                LOGGER.debug("Difference model {} declares no Supersedes, so the base it applies on is not checked",
                        model.header().id());
                return;
            }
            if (supersedes.stream().noneMatch(id -> sameModel(id, currentId))) {
                blocking.add(new CgmesDiffImport.BlockingStatement(model.header().subset(), null,
                        "difference model " + model.header().id() + " supersedes " + supersedes
                                + " but the network is at " + currentId));
            }
        }

        /** Model identifiers are compared without their {@code urn:uuid:} scheme, which producers spell freely. */
        private static boolean sameModel(String a, String b) {
            return DifferenceModelParser.normalizeId(a).equals(DifferenceModelParser.normalizeId(b));
        }

        private void checkReverse(CgmesSubset subset, List<CgmesStatement> reverse,
                                  List<CgmesStatement> preconditions) {
            if (options.getReverseCheck() == CgmesDiffImport.ReverseCheck.OFF) {
                return;
            }
            List<CgmesStatement> toCheck = new ArrayList<>(reverse);
            toCheck.addAll(preconditions);
            for (CgmesStatement statement : toCheck) {
                if (statement.isType()) {
                    continue;
                }
                Optional<ResolvedSubject> resolved =
                        resolver.resolve(statement.subjectId(), Set.of(statement.property()), statement.className());
                if (resolved.isEmpty()) {
                    continue;
                }
                CgmesStatement actual = dumpOf(resolved.get(), statement.subjectId()).get(statement.property());
                if (actual == null) {
                    LOGGER.info("The value of {} of {} is not verifiable: the receiver has no mapping producing it",
                            statement.property(), statement.subjectId());
                    continue;
                }
                if (valuesMatch(statement.value(), actual.value())) {
                    continue;
                }
                String message = "network value " + actual.value() + " of " + statement.property() + " of "
                        + statement.subjectId() + " differs from the expected value " + statement.value();
                if (options.getReverseCheck() == CgmesDiffImport.ReverseCheck.FAIL) {
                    blocking.add(new CgmesDiffImport.BlockingStatement(subset, statement, message));
                } else {
                    LOGGER.warn("{}", message);
                }
            }
        }

        /** Two lexical values say the same thing: as numbers within a relative tolerance, else literally. */
        private static boolean valuesMatch(String expected, String actual) {
            if (expected.equals(actual)) {
                return true;
            }
            try {
                double a = Double.parseDouble(expected);
                double b = Double.parseDouble(actual);
                return Math.abs(a - b) <= RELATIVE_TOLERANCE * Math.max(1, Math.max(Math.abs(a), Math.abs(b)));
            } catch (NumberFormatException e) {
                return expected.equalsIgnoreCase(actual)
                        && ("true".equalsIgnoreCase(expected) || "false".equalsIgnoreCase(expected));
            }
        }

        /**
         * What is registered as the model of the profile afterwards.
         *
         * <p>Forward, that is the header of the difference, with the values a foreign header may lack filled in from
         * the model the network holds. A revert registers the predecessor the difference superseded, because that is
         * the model the network is at again; its description and version are not carried by the difference, so the
         * version is decremented and the other values of the header are kept &mdash; an approximation a database
         * layer holding the real metadata may overwrite.</p>
         */
        private RegisteredIdentity identity(DifferenceModel model, CgmesSubset subset,
                                            Optional<CgmesMetadataModel> current) {
            DifferenceModelHeader header = model.header();
            String authority = header.modelingAuthoritySet() != null ? header.modelingAuthoritySet()
                    : current.map(CgmesMetadataModel::getModelingAuthoritySet).orElse(UNKNOWN_AUTHORITY);
            List<String> profiles = !header.profiles().isEmpty() ? header.profiles()
                    : current.map(m -> List.copyOf(m.getProfiles())).orElseGet(() -> defaultProfiles(header, subset));
            ZonedDateTime scenarioTime = header.scenarioTime() != null ? header.scenarioTime() : network.getCaseDate();
            ZonedDateTime created = header.created() != null ? header.created() : ZonedDateTime.now();
            int version = header.version() > 0 ? header.version()
                    : current.map(CgmesMetadataModel::getVersion).orElse(0) + 1;
            if (!inverted) {
                return new RegisteredIdentity(header.id(), version, header.description(), authority, profiles,
                        header.dependentOn(), header.supersedes(), scenarioTime, created);
            }
            return new RegisteredIdentity(predecessorId(header), Math.max(1, version - 1), header.description(),
                    authority, profiles, header.dependentOn(), List.of(), scenarioTime, created);
        }

        /** The model a difference was applied on, that is what its {@code Supersedes} names. */
        private String predecessorId(DifferenceModelHeader header) {
            if (header.supersedes().size() == 1) {
                return header.supersedes().get(0);
            }
            LOGGER.warn("Difference model {} supersedes {} models, so the model the network is back at after the"
                    + " revert cannot be named; a derived identifier is registered instead",
                    header.id(), header.supersedes().size());
            return CgmesDiffImport.derivedId("reverted:" + header.id());
        }

        private static List<String> defaultProfiles(DifferenceModelHeader header, CgmesSubset subset) {
            for (CgmesNamespace.Cim cim : CgmesNamespace.CIM_LIST) {
                if (cim.getNamespace().equals(header.cimNamespace())) {
                    String uri = cim.getProfileUri(subset.getIdentifier());
                    if (uri != null) {
                        return List.of(uri);
                    }
                }
            }
            return List.of();
        }
    }
}
