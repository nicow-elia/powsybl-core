/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.CgmesReports;
import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.model.CgmesModel;
import com.powsybl.cgmes.model.CgmesModelException;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.triplestore.api.TripleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/**
 * Applies an IEC 61970-552 difference model to a network that is already loaded.
 *
 * <p>A difference model says what a CGMES model said before a change and what it says after it. When every statement
 * it carries is one the CGMES update workflow reads &mdash; the operating values a steady state hypothesis carries
 * &mdash; the change can be applied <em>in place</em>: no file is re-read, no network is rebuilt, the receiving
 * network is updated exactly as a partial steady state hypothesis file would update it. That is the fast route, and
 * it is what this class does.</p>
 *
 * <h2>How the fast route works</h2>
 * <ol>
 *   <li>the document is parsed into statements ({@code DifferenceModelParser});</li>
 *   <li>{@link FastRouteCapabilities#check(DifferenceModelSet)} decides from the document alone whether every
 *       property is one an update query reads;</li>
 *   <li>every subject is resolved against the network &mdash; by identifier, by CGMES alias (terminals, tap
 *       changers) or by the properties the importer left on the equipment (regulating controls, generating units,
 *       equivalent injections) &mdash; which also yields the CIM class to write;</li>
 *   <li>properties an update query only reads together with others are completed from the receiving network through
 *       the very mapping the change exporter uses, so that a minimal difference of a third party applies;</li>
 *   <li>the result is written as one synthetic partial steady state hypothesis document per profile into a fresh
 *       in-memory triple store and handed to {@code Conversion.update}.</li>
 * </ol>
 *
 * <p>Step 5 is what makes this robust: from there on the fast route <em>is</em> the partial steady state hypothesis
 * update path, with the same RDF reader, the same SPARQL queries and the same {@code XxxConversion.update} code. A
 * difference model therefore cannot drift away from what a partial file does.</p>
 *
 * <p>Previous values are always used ({@code iidm.import.cgmes.use-previous-values-during-update}): a difference is
 * partial by definition, so an attribute it does not mention has to keep the value the network holds.</p>
 *
 * <h2>What it refuses</h2>
 * <p>Creating or removing objects, changing topology, and any property outside the update catalogue cannot be
 * applied to a live network. {@link #canApplyInPlace(Network, DifferenceModelSet)} answers that <em>before</em>
 * anything is modified and lists every statement in the way, so a caller can route the difference without trying.
 * Applying such a difference is the generic RDF operation "base graph minus reverse plus forward" and belongs to RDF
 * tooling; {@link #applyToTripleStore} is the building block this library offers for it.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class CgmesDiffImport {

    private static final Logger LOGGER = LoggerFactory.getLogger(CgmesDiffImport.class);

    /** Which route a difference model can take. */
    public enum Route {
        /** The difference says nothing; the network and its metadata are left untouched. */
        NOOP,
        /** Every statement maps onto the network update workflow: the difference is applied in place. */
        FAST,
        /** At least one statement cannot be applied to a live network. */
        SLOW_REQUIRED
    }

    /** How strictly the reverse statements and the preconditions of a difference are checked against the network. */
    public enum ReverseCheck {
        /** Do not evaluate them. Applying a difference means replacing the property value. */
        OFF,
        /** Evaluate them and report every mismatch, but apply the difference anyway. */
        WARN,
        /** Evaluate them and refuse to apply the difference on a mismatch. */
        FAIL
    }

    /**
     * One reason why the in-place route is impossible.
     *
     * @param subset    the profile of the difference model the reason belongs to
     * @param statement the statement in the way, or {@code null} for a reason about the model as a whole, such as a
     *                  profile that cannot be updated or a difference applied on the wrong base
     * @param reason    a human readable explanation
     */
    public record BlockingStatement(CgmesSubset subset, CgmesStatement statement, String reason) {

        /** This reason as one line, naming the subject and the property when there is one. */
        public String asText() {
            String where = statement == null ? "" : " " + statement.subjectId() + " " + statement.property();
            return subset.getIdentifier() + where + ": " + reason;
        }
    }

    /**
     * What this importer decided about a difference model set.
     *
     * @param route    the route the difference can take
     * @param blocking everything that stands in the way of the fast route, empty unless the route is
     *                 {@link Route#SLOW_REQUIRED}
     */
    public record Decision(Route route, List<BlockingStatement> blocking) {
        public Decision {
            blocking = List.copyOf(blocking);
        }

        /** The blocking reasons as lines of text, in the order they were found. */
        public List<String> reasons() {
            return blocking.stream().map(BlockingStatement::asText).toList();
        }

        /** Whether the difference can be applied without rebuilding the network. */
        public boolean isFast() {
            return route == Route.FAST || route == Route.NOOP;
        }
    }

    /** How a difference model is applied. */
    public static final class Options {

        private ReverseCheck reverseCheck = ReverseCheck.OFF;
        private boolean checkSupersedes = true;
        private boolean scopedUpdate = true;
        private boolean variantSafeOnly;

        /**
         * Whether the difference may only write state IIDM stores per network variant.
         *
         * <p>Off by default, which is the classic behaviour: a network has one state and the update writes it.
         * A caller that has bound a <em>variant</em> of a network to a stored state switches it on, and then every
         * statement whose IIDM target is shared by all variants &mdash; an impedance, an operational limit value,
         * the rating of an HVDC line in the simplified model, an IIDM property &mdash; blocks the update instead of
         * leaking into the other variants. See {@link FastRouteCapabilities.VariantSafety}.</p>
         *
         * <p>It also requires the scoped update: the full update writes properties and validation levels that
         * belong to the whole network.</p>
         *
         * @param variantSafeOnly whether only variant local writes are allowed
         * @return this
         */
        public Options setVariantSafeOnly(boolean variantSafeOnly) {
            this.variantSafeOnly = variantSafeOnly;
            return this;
        }

        /**
         * @return whether only writes that stay inside one network variant are allowed
         */
        public boolean isVariantSafeOnly() {
            return variantSafeOnly;
        }

        /**
         * Whether the reverse statements and the preconditions are compared with the receiving network before the
         * difference is applied. {@link ReverseCheck#OFF} by default, because applying a difference means replacing
         * a property value, not merging a delta into an unknown state.
         */
        public Options setReverseCheck(ReverseCheck reverseCheck) {
            this.reverseCheck = Objects.requireNonNull(reverseCheck);
            return this;
        }

        /**
         * Whether the {@code md:Model.Supersedes} of the difference has to name the model the network currently
         * holds for that profile. On by default: applying a delta on the wrong base is silent corruption. The check
         * is skipped when the difference declares no {@code Supersedes} or the network holds no model of the profile.
         */
        public Options setCheckSupersedes(boolean checkSupersedes) {
            this.checkSupersedes = checkSupersedes;
            return this;
        }

        /**
         * Whether the update is restricted to the equipment the difference touches. On by default; it falls back to
         * the full update when the network is not yet at steady state hypothesis validation level or when the import
         * removes properties and aliases, because both make the restriction unsafe.
         */
        public Options setScopedUpdate(boolean scopedUpdate) {
            this.scopedUpdate = scopedUpdate;
            return this;
        }

        public ReverseCheck getReverseCheck() {
            return reverseCheck;
        }

        public boolean isCheckSupersedes() {
            return checkSupersedes;
        }

        public boolean isScopedUpdate() {
            return scopedUpdate;
        }

        /**
         * An independent copy of these options.
         *
         * @return the copy
         */
        public Options copy() {
            Options copy = new Options();
            copy.reverseCheck = reverseCheck;
            copy.checkSupersedes = checkSupersedes;
            copy.scopedUpdate = scopedUpdate;
            copy.variantSafeOnly = variantSafeOnly;
            return copy;
        }

        /**
         * Options read from plain properties, without any platform configuration lookup.
         *
         * <p>Keys are {@link CgmesImport#DIFF_CHECK_REVERSE} ({@code off}, {@code warn} or {@code fail}) and
         * {@link CgmesImport#DIFF_CHECK_SUPERSEDES}. The importer itself reads the same two parameters through the
         * parameter machinery, so that a platform configuration default is honoured; this factory is the fallback
         * for the static API of this class.</p>
         */
        public static Options from(Properties parameters) {
            Options options = new Options();
            if (parameters == null) {
                return options;
            }
            String reverse = parameters.getProperty(CgmesImport.DIFF_CHECK_REVERSE);
            if (reverse != null) {
                options.setReverseCheck(reverseCheckOf(reverse));
            }
            String supersedes = parameters.getProperty(CgmesImport.DIFF_CHECK_SUPERSEDES);
            if (supersedes != null) {
                options.setCheckSupersedes(Boolean.parseBoolean(supersedes));
            }
            return options;
        }

        /** The reverse check named by a parameter value, one of {@code off}, {@code warn} and {@code fail}. */
        public static ReverseCheck reverseCheckOf(String value) {
            try {
                return ReverseCheck.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new CgmesModelException("Unknown value \"" + value + "\" for "
                        + CgmesImport.DIFF_CHECK_REVERSE + ", expected one of off, warn, fail", e);
            }
        }
    }

    /** A lazily created importer, only used to turn plain {@link Properties} into a conversion configuration. */
    private static final class Holder {
        static final CgmesImport IMPORTER = new CgmesImport();

        private Holder() {
        }
    }

    private CgmesDiffImport() {
    }

    /**
     * Read every difference model of a data source.
     *
     * @throws CgmesModelException if two files describe the same profile, because a receiver cannot know in which
     *                             order to apply them
     */
    public static DifferenceModelSet read(ReadOnlyDataSource dataSource) {
        Objects.requireNonNull(dataSource);
        try {
            return DifferenceModelParser.parseAll(dataSource);
        } catch (IllegalArgumentException e) {
            throw new CgmesModelException(e.getMessage()
                    + ": apply them one after the other in Supersedes order, or compose them with"
                    + " DifferenceModel.compose", e);
        }
    }

    /** Whether the given difference models can be applied to this network in place, with the default options. */
    public static Decision canApplyInPlace(Network network, DifferenceModelSet diffs) {
        return canApplyInPlace(network, diffs, new Options());
    }

    /**
     * Whether the given difference models can be applied to this network in place.
     *
     * <p>Nothing is modified. Every blocking statement is collected, not only the first one.</p>
     */
    public static Decision canApplyInPlace(Network network, DifferenceModelSet diffs, Options options) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(diffs);
        Objects.requireNonNull(options);
        Decision scoped = checkVariantScope(network, options);
        if (scoped != null) {
            return scoped;
        }
        return FastRoutePlan.of(network, diffs, options, false).decision();
    }

    /**
     * The message a variant bound update is refused with when the full update would have to run.
     *
     * <p>{@code Update.updateAndCompleteVoltageAndAngles} writes the properties {@code v} and {@code angle} on
     * every boundary line and three windings transformer of the network and lowers the validation level, and
     * neither is stored per variant. A variant bound update therefore <em>requires</em> the scoped update rather
     * than falling back to the full one.</p>
     */
    static final String VARIANT_NEEDS_SCOPED_UPDATE =
            "a variant-bound update needs the scoped update: the full update writes properties and validation"
                    + " levels shared by all variants";

    /**
     * Whether a variant bound update can run at all on this network, before a plan is built.
     *
     * @return the refusal, or {@code null} when nothing stands in the way
     */
    private static Decision checkVariantScope(Network network, Options options) {
        if (!options.isVariantSafeOnly()) {
            return null;
        }
        if (!options.isScopedUpdate()
                || network.getValidationLevel() != com.powsybl.iidm.network.ValidationLevel
                        .STEADY_STATE_HYPOTHESIS) {
            return new Decision(Route.SLOW_REQUIRED, List.of(new BlockingStatement(
                    CgmesSubset.STEADY_STATE_HYPOTHESIS, null, VARIANT_NEEDS_SCOPED_UPDATE)));
        }
        return null;
    }

    /** Apply every difference model of a set, reading the parameters from plain properties. */
    public static Decision apply(Network network, DifferenceModelSet diffs, Properties parameters,
                                 ReportNode reportNode) {
        return apply(network, diffs, config(parameters), Options.from(parameters), reportNode);
    }

    /** Apply a single difference model. */
    public static Decision apply(Network network, DifferenceModel diff, Properties parameters, ReportNode reportNode) {
        return apply(network, new DifferenceModelSet(List.of(diff)), parameters, reportNode);
    }

    /** Parse and apply a difference model document. */
    public static Decision apply(Network network, InputStream differenceModelXml, Properties parameters,
                                 ReportNode reportNode) {
        return apply(network, DifferenceModelParser.parse(differenceModelXml, null), parameters, reportNode);
    }

    /** Parse and apply a difference model file, whose name gives the profile when the header declares none. */
    public static Decision apply(Network network, Path differenceModelFile, Properties parameters,
                                 ReportNode reportNode) {
        return apply(network, DifferenceModelParser.parse(differenceModelFile), parameters, reportNode);
    }

    /** Parse and apply an in-memory difference model document. */
    public static Decision apply(Network network, String differenceModelXml, Properties parameters,
                                 ReportNode reportNode) {
        return apply(network, DifferenceModelParser.parse(differenceModelXml), parameters, reportNode);
    }

    /**
     * Apply every difference model of a set with an explicit conversion configuration.
     *
     * <p>This overload does no platform configuration lookup at all, which is what the CGMES importer and a database
     * layer need: they already hold the configuration the network was read with.</p>
     *
     * @return the decision taken, {@link Route#NOOP} when the set says nothing and {@link Route#FAST} after a
     *         successful update
     * @throws CgmesDiffNotApplicableException if a statement cannot be applied in place. The network is untouched
     */
    public static Decision apply(Network network, DifferenceModelSet diffs, Conversion.Config config, Options options,
                                 ReportNode reportNode) {
        return applyInternal(network, diffs, config, options, reportNode, false).decision();
    }

    /**
     * Undo a difference: apply its reverse statements.
     *
     * <p>The network has to be in the state the forward statements produced. The metadata registered afterwards is
     * the predecessor named by {@code md:Model.Supersedes}, so that the network is "at" the model the difference was
     * applied on again and a chain of differences can be walked back one by one.</p>
     */
    public static Decision revert(Network network, DifferenceModelSet diffs, Properties parameters,
                                  ReportNode reportNode) {
        return revert(network, diffs, config(parameters), Options.from(parameters), reportNode);
    }

    /** Undo a difference with an explicit conversion configuration. */
    public static Decision revert(Network network, DifferenceModelSet diffs, Conversion.Config config, Options options,
                                  ReportNode reportNode) {
        return applyInternal(network, diffs, config, options, reportNode, true).decision();
    }

    /**
     * How long each phase of an apply took, for the benchmark.
     *
     * @param parseNs  always zero here: parsing happens before this class is entered
     * @param planNs   resolving subjects, completing groups and checking
     * @param storeNs  writing the synthetic update documents and loading them into a triple store
     * @param updateNs the CGMES update workflow itself
     * @param directNs the equipment statements applied with IIDM setters afterwards
     */
    record PhaseTimes(long parseNs, long planNs, long storeNs, long updateNs, long directNs) {
    }

    /** An apply together with what it cost, so that the benchmark does not need a second code path. */
    record Applied(Decision decision, PhaseTimes times) {
    }

    static Applied applyInternal(Network network, DifferenceModelSet diffs, Conversion.Config config, Options options,
                                 ReportNode reportNode, boolean inverted) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(diffs);
        Objects.requireNonNull(config);
        Objects.requireNonNull(options);
        Objects.requireNonNull(reportNode);

        Decision scoped = checkVariantScope(network, options);
        if (scoped != null) {
            throw new CgmesDiffNotApplicableException(scoped);
        }
        long planStart = System.nanoTime();
        FastRoutePlan plan = FastRoutePlan.of(network, diffs, options, inverted);
        long planNs = System.nanoTime() - planStart;
        Decision decision = plan.decision();
        if (decision.route() == Route.SLOW_REQUIRED) {
            throw new CgmesDiffNotApplicableException(decision);
        }
        if (decision.route() == Route.NOOP) {
            return new Applied(decision, new PhaseTimes(0, planNs, 0, 0, 0));
        }

        if (!config.usePreviousValuesDuringUpdate()) {
            LOGGER.debug("A difference model is partial by definition, so previous values are used during the update"
                    + " although {} is false", CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE);
        }
        // A copy, so that the caller's configuration object is neither changed nor read while another thread uses it
        Conversion.Config updateConfig = config.copy().setUsePreviousValuesDuringUpdate(true);

        // The scope is only known once the plan has resolved its subjects, and the conversion configuration can
        // still force the full update; a variant bound update is refused here, before anything is built
        if (options.isVariantSafeOnly()
                && plan.updateScope(network, updateConfig, options).isAll()) {
            throw new CgmesDiffNotApplicableException(new Decision(Route.SLOW_REQUIRED,
                    List.of(new BlockingStatement(CgmesSubset.STEADY_STATE_HYPOTHESIS, null,
                            VARIANT_NEEDS_SCOPED_UPDATE))));
        }

        for (FastRoutePlan.PlannedModel model : plan.models()) {
            CgmesReports.applyingDifferenceModelReport(reportNode, model.identity().id(),
                    model.header().subset().getIdentifier(), model.statementCount());
        }

        // A difference of the equipment profile alone carries no dated steady state model, and the update workflow
        // then reads the model dates of a store that holds none, which would overwrite them with the current time
        ZonedDateTime caseDate = network.getCaseDate();
        int forecastDistance = network.getForecastDistance();

        long storeStart = System.nanoTime();
        CgmesModel cgmes = plan.models().isEmpty() ? null : DiffUpdateStoreBuilder.build(plan, reportNode);
        long storeNs = System.nanoTime() - storeStart;
        long updateNs = 0;
        if (cgmes != null) {
            try {
                long updateStart = System.nanoTime();
                new Conversion(cgmes, updateConfig)
                        .update(network, plan.updateScope(network, updateConfig, options), reportNode);
                updateNs = System.nanoTime() - updateStart;
            } finally {
                cgmes.close();
            }
        }
        if (!plan.touchesSteadyStateHypothesis()) {
            network.setCaseDate(caseDate);
            network.setForecastDistance(forecastDistance);
        }
        long directStart = System.nanoTime();
        DirectEqApplier.apply(network, plan, reportNode);
        long directNs = System.nanoTime() - directStart;
        return new Applied(decision, new PhaseTimes(0, planNs, storeNs, updateNs, directNs));
    }

    /**
     * Replace the property values a difference states inside one named graph of a triple store.
     *
     * <p>This is the slow route building block: it changes the RDF data a network was or will be read from, not a
     * live network, and it goes through SPARQL UPDATE only, so it works on the in-memory triple store of a loaded
     * model as well as on a remote repository. Properties are treated as single valued: every value of a stated
     * property is deleted before the stated one is inserted.</p>
     *
     * @param store       the triple store to change
     * @param diff        the difference to apply
     * @param contextName the named graph to change, that is one of {@link TripleStore#contextNames()}
     * @param baseName    the base IRI of the documents in that graph, which is what subject identifiers are
     *                    resolved against
     */
    public static void applyToTripleStore(TripleStore store, DifferenceModel diff, String contextName,
                                          String baseName) {
        TripleStoreDiffApplier.apply(store, diff, contextName, baseName);
    }

    /**
     * Replace the property values a difference states in the single graph of the store that matches its profile.
     *
     * @throws CgmesModelException if no context or several contexts of the store carry the profile of the difference
     */
    public static void applyToTripleStore(TripleStore store, DifferenceModel diff, String baseName) {
        TripleStoreDiffApplier.apply(store, diff, TripleStoreDiffApplier.contextOf(store, diff), baseName);
    }

    /**
     * Replace the property values a difference states inside a named graph named by its IRI.
     *
     * <p>Same replace semantics as {@link #applyToTripleStore(TripleStore, DifferenceModel, String, String)} and
     * the same SPARQL, but addressed the way a database addresses things: the graph IRI is written verbatim rather
     * than derived from a powsybl context name, and subject IRIs are formed by appending {@code "_" + id} to the
     * prefix the model carries rather than by resolving a fragment against a document base. That is what a store
     * of versioned models needs, where the graph names belong to the store and the subject prefix is a recorded
     * property of the model a difference applies on.</p>
     *
     * <p>An identifier that is already an absolute IRI ({@code urn:}, {@code http:}, {@code https:}) is used as it
     * is, which is how a statement about a model header or about an object outside the instance file's base can be
     * expressed.</p>
     *
     * @param store       the triple store to change
     * @param diff        the difference to apply
     * @param graphIri    the IRI of the named graph to change, used as it is
     * @param subjectBase the IRI prefix subject identifiers are appended to, for instance {@code http://x/#}. The
     *                    empty string means every identifier of the difference is already absolute; a difference
     *                    that names a master resource identifier anyway is refused, because there would be nothing
     *                    to resolve it against
     */
    public static void applyToGraph(TripleStore store, DifferenceModel diff, String graphIri, String subjectBase) {
        TripleStoreDiffApplier.applyToGraph(store, diff, graphIri, subjectBase);
    }

    /** The conversion configuration described by plain properties, through a shared importer. */
    private static Conversion.Config config(Properties parameters) {
        return Holder.IMPORTER.config(parameters);
    }

    /** A deterministic {@code urn:uuid:} identifier derived from a name. */
    static String derivedId(String name) {
        return "urn:uuid:" + UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
