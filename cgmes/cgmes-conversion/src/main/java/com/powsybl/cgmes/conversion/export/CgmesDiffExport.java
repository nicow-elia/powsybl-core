/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.export.PartialSshExport.UnsupportedChangeBehavior;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.DifferenceModelWriter;
import com.powsybl.cgmes.model.diff.DifferenceSink;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.datasource.DataSource;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Exports the changes recorded on a network as IEC 61970-552 difference models.
 *
 * <p>A difference model says what a CGMES model said before a change and what it says after it. Where
 * {@link PartialSshExport} writes only the new state, and therefore has to be applied in the right order on exactly
 * the right base model, a difference can also be undone, replayed, chained and stored as a pair of statement sets,
 * which is what a change history needs.</p>
 *
 * <p>The pipeline is three steps, each of which a caller can take on its own:</p>
 * <ol>
 *     <li>recorded {@code NetworkEvent}s are compacted to one change per attribute, keeping what the first change of
 *     each attribute replaced;</li>
 *     <li>{@link #toDifferences} translates them into
 *     {@link com.powsybl.cgmes.model.diff.CgmesStatement}s, in both directions, without any I/O;</li>
 *     <li>a {@link DifferenceSink} stores the result, as a document per profile through
 *     {@link DifferenceModelWriter} or in a triple store.</li>
 * </ol>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * NetworkEventRecorder recorder = new NetworkEventRecorder();
 * network.addListener(recorder);
 * network.getLoad("L").setP0(12.5);
 * CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, recorder.getEvents(), new ExportOptions());
 * DifferenceModel ssh = result.differences().get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
 * }</pre>
 *
 * <p><b>Granularity.</b> By default every object a change touches is described in full, in both directions, because
 * the CGMES update reads some properties only as a group: the active and the reactive power of an injection, the
 * section count and the control flag of a shunt. A consumer applying a difference replaces the properties it is
 * given, so a full object description is what keeps those groups consistent. {@link DiffGranularity#CHANGED_ONLY}
 * drops the statements that say the same thing in both directions, which gives a true delta at the price of a
 * receiver that has to merge property by property. Whole objects that did not change at all are dropped in both
 * modes, so a change and its undo produce an empty difference.</p>
 *
 * <p><b>Where the reverse values come from.</b> The previous state is an overlay of the values the change log
 * remembers on top of the live network: an attribute the change set touched reads what its first change replaced,
 * everything else reads live. That is correct as long as the recorder was attached for the whole change set and the
 * network was not modified behind its back. The values themselves are IIDM values translated by the same mapping as
 * the forward direction, not the values a CGMES file originally held, so they are as faithful as the import that
 * produced the network. A change whose previous value was never recorded is reported as unsupported rather than
 * guessed.</p>
 *
 * <p><b>One model per profile.</b> A change set that touches several CGMES profiles produces one difference model
 * per profile, bundled as a {@link DifferenceModelSet} and written as one file per profile into a
 * {@link DataSource}. Never several models in one document, and never one model declaring several profiles: the
 * version and the {@code Supersedes} of such a combined model would be undefined against the per-profile full models
 * it applies on, and a database layer needs one named graph per model anyway.</p>
 *
 * <p><b>Chaining.</b> An export never modifies the metadata of the network it reads, so a second export from the
 * same network supersedes the same source model as the first one. A caller building a chain says so explicitly with
 * {@link HeaderOptions#chainAfter(DifferenceModelHeader)}.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class CgmesDiffExport {

    /** What the rejection message of this export calls its target. */
    static final String DIFFERENCE_MODEL_TARGET = "a CGMES difference model";

    private CgmesDiffExport() {
    }

    /** How much of a changed object a difference model describes. */
    public enum DiffGranularity {
        /**
         * Describe every object a change touches in full, in both directions. The default, because it keeps the
         * property groups a CGMES receiver reads together consistent.
         */
        FULL_OBJECT,
        /** Keep only the statements that differ between the two directions, that is the true delta. */
        CHANGED_ONLY
    }

    /**
     * The header values of the difference model of one profile.
     *
     * <p>The defaults are the ones of a partial file: the identifier is generated and reproducible, the version is
     * the version of the source model of that profile incremented by one, and the difference supersedes that source
     * model and inherits its dependencies.</p>
     */
    public static final class HeaderOptions {

        private final ModelHeaderSettings settings = new ModelHeaderSettings();

        ModelHeaderSettings settings() {
            return settings;
        }

        /** Set the identifier of the exported model, instead of generating one. */
        public HeaderOptions setModelId(String modelId) {
            settings.setModelId(modelId);
            return this;
        }

        /** Set the description of the exported model, instead of inheriting the one of the source model. */
        public HeaderOptions setDescription(String description) {
            settings.setDescription(description);
            return this;
        }

        /** Set the version of the exported model, instead of incrementing the version of the source model. */
        public HeaderOptions setVersion(int version) {
            settings.setVersion(version);
            return this;
        }

        /** Set the authority that produced the exported model, instead of the one of the source model. */
        public HeaderOptions setModelingAuthoritySet(String modelingAuthoritySet) {
            settings.setModelingAuthoritySet(modelingAuthoritySet);
            return this;
        }

        /** Drop the dependencies inherited from the source model, keeping only those added explicitly. */
        public HeaderOptions clearDependencies() {
            settings.clearDependencies();
            return this;
        }

        /** Declare one more model this difference depends on, next to the ones inherited from the source model. */
        public HeaderOptions addDependentOn(String modelId) {
            settings.addDependentOn(modelId);
            return this;
        }

        /** Declare more models this difference depends on, next to the ones inherited from the source model. */
        public HeaderOptions addDependentOn(Collection<String> modelIds) {
            settings.addDependentOn(modelIds);
            return this;
        }

        /** Whether the exported model declares that it supersedes the model the network was imported from. */
        public HeaderOptions setSupersedePreviousModel(boolean supersedePreviousModel) {
            settings.setSupersedePreviousModel(supersedePreviousModel);
            return this;
        }

        /** Declare one more model this difference replaces, next to the source model. */
        public HeaderOptions addSupersedes(String modelId) {
            settings.addSupersedes(modelId);
            return this;
        }

        /** Declare more models this difference replaces, next to the source model. */
        public HeaderOptions addSupersedes(Collection<String> modelIds) {
            settings.addSupersedes(modelIds);
            return this;
        }

        /**
         * Declare that this difference applies on top of an earlier one rather than on the model the network was
         * imported from: it supersedes that earlier difference and carries the next version.
         *
         * <p>An export does not update the metadata of the network it reads, so this is how a sender builds a chain
         * of differences from one network: every export after the first one is told which difference it follows.</p>
         */
        public HeaderOptions chainAfter(DifferenceModelHeader previous) {
            Objects.requireNonNull(previous);
            settings.setSupersedePreviousModel(false);
            settings.addSupersedes(previous.id());
            settings.setVersion(previous.version() + 1);
            return this;
        }
    }

    /** Optional settings of a difference model export. */
    public static final class ExportOptions {

        private UnsupportedChangeBehavior unsupportedChangeBehavior = UnsupportedChangeBehavior.FAIL;
        private DiffGranularity granularity = DiffGranularity.FULL_OBJECT;
        private Set<CgmesSubset> subsets = EnumSet.of(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS);
        private final Map<CgmesSubset, HeaderOptions> headers = new EnumMap<>(CgmesSubset.class);
        private ZonedDateTime scenarioTime;
        private ZonedDateTime created;
        private String variant;
        private boolean rejectSharedChanges;

        /**
         * Export the changes of one variant of the network, reading its values.
         *
         * <p>Changes recorded on another variant are dropped &mdash; naming a variant <em>is</em> the selection
         * &mdash; and the export runs with that variant selected, so the values it writes are the values of that
         * variant. The working variant of the calling thread is restored afterwards.</p>
         *
         * @param variant the variant to export, or {@code null} for the working variant, which is the default
         * @return this
         */
        public ExportOptions setVariant(String variant) {
            this.variant = variant;
            return this;
        }

        /**
         * @return the variant this export describes, or {@code null} for the working one
         */
        public String getVariant() {
            return variant;
        }

        /**
         * Whether a change that is not stored per variant in IIDM is an unsupported change.
         *
         * <p>Off by default, which is right for a network with a single state. A caller writing the difference of
         * <em>one variant</em> of a multi-variant network switches it on: a change of an impedance, of an
         * operational limit or of a property belongs to every variant of that network, so writing it into the
         * history of one of them would describe a state the other variants are in as well. Such a change is
         * recorded without a variant identifier, which is exactly how it is recognised.</p>
         *
         * @param rejectSharedChanges whether changes without a variant are refused
         * @return this
         */
        public ExportOptions setRejectSharedChanges(boolean rejectSharedChanges) {
            this.rejectSharedChanges = rejectSharedChanges;
            return this;
        }

        /**
         * @return whether changes that belong to every variant are refused
         */
        public boolean isRejectSharedChanges() {
            return rejectSharedChanges;
        }

        /** What to do with a change that cannot be described as a difference. Defaults to failing. */
        public ExportOptions setUnsupportedChangeBehavior(UnsupportedChangeBehavior unsupportedChangeBehavior) {
            this.unsupportedChangeBehavior = Objects.requireNonNull(unsupportedChangeBehavior);
            return this;
        }

        /**
         * @return what happens to a change that cannot be described as a difference
         */
        public UnsupportedChangeBehavior getUnsupportedChangeBehavior() {
            return unsupportedChangeBehavior;
        }

        /** How much of a changed object is described. Defaults to {@link DiffGranularity#FULL_OBJECT}. */
        public ExportOptions setGranularity(DiffGranularity granularity) {
            this.granularity = Objects.requireNonNull(granularity);
            return this;
        }

        DiffGranularity getGranularity() {
            return granularity;
        }

        /**
         * The profiles this export writes. A change describing another profile is reported as unsupported.
         * Defaults to the equipment model and the steady state hypothesis.
         */
        public ExportOptions setSubsets(Set<CgmesSubset> subsets) {
            Objects.requireNonNull(subsets);
            if (subsets.isEmpty()) {
                throw new IllegalArgumentException("An export writes at least one profile");
            }
            this.subsets = EnumSet.copyOf(subsets);
            return this;
        }

        Set<CgmesSubset> getSubsets() {
            return subsets;
        }

        /** The same options with the profiles restricted to one, leaving the caller's options untouched. */
        private ExportOptions restrictedTo(CgmesSubset subset) {
            ExportOptions restricted = copy();
            restricted.subsets = EnumSet.of(subset);
            return restricted;
        }

        /**
         * An independent copy of these options.
         *
         * <p>What it is for: a caller that hands its options to an export which has to set something of its own
         * &mdash; the scenario time of the snapshot being written, say &mdash; must not find that value still set
         * when it exports again somewhere else.</p>
         *
         * @return the copy
         */
        public ExportOptions copy() {
            ExportOptions copy = new ExportOptions();
            copy.unsupportedChangeBehavior = unsupportedChangeBehavior;
            copy.granularity = granularity;
            copy.subsets = EnumSet.copyOf(subsets);
            copy.headers.putAll(headers);
            copy.scenarioTime = scenarioTime;
            copy.created = created;
            copy.variant = variant;
            copy.rejectSharedChanges = rejectSharedChanges;
            return copy;
        }

        /** The header of the difference model of one profile, created with its defaults on first use. */
        public HeaderOptions header(CgmesSubset subset) {
            return headers.computeIfAbsent(subset, s -> new HeaderOptions());
        }

        /** Set the point in time the described state applies to, instead of the case date of the network. */
        public ExportOptions setScenarioTime(ZonedDateTime scenarioTime) {
            this.scenarioTime = Objects.requireNonNull(scenarioTime);
            return this;
        }

        /**
         * @return the point in time the described state applies to, or {@code null} for the case date
         */
        public ZonedDateTime getScenarioTime() {
            return scenarioTime;
        }

        /** Set the creation time of the exported models, instead of the time at which they are produced. */
        public ExportOptions setCreated(ZonedDateTime created) {
            this.created = Objects.requireNonNull(created);
            return this;
        }
    }

    /**
     * What an export produced.
     *
     * @param differences    the difference models, one per touched profile
     * @param exportedEvents the changes that reached them, in the order in which they were written. With
     *                       {@link UnsupportedChangeBehavior#FAIL} this is every compacted change; with
     *                       {@link UnsupportedChangeBehavior#IGNORE} it is a subset of them
     */
    public record Result(DifferenceModelSet differences, List<NetworkEvent> exportedEvents) {
    }

    /**
     * Translate recorded changes into difference models, without writing anything.
     *
     * @param network       the network the changes were recorded on. It has to be an individual grid model
     * @param events        the recorded changes
     * @param exportOptions the granularity, the header values and the unsupported change behavior
     * @throws PowsyblException if the network is a merged model, or if a change cannot be described and the behavior
     *                          is {@link UnsupportedChangeBehavior#FAIL}
     */
    public static Result toDifferences(Network network, Collection<NetworkEvent> events, ExportOptions exportOptions) {
        Objects.requireNonNull(exportOptions);
        try (ExportVariantScope scope = ExportVariantScope.enter(network, exportOptions.getVariant())) {
            return builder(network, events, exportOptions).build();
        }
    }

    /**
     * Translate recorded changes into difference models and hand them to a sink.
     *
     * <p>The sink is called once, through {@link DifferenceSink#accept(DifferenceModelSet)}, with every profile of
     * the change set at once, so that a sink which can store several models as a unit &mdash; a transactional triple
     * store, for instance &mdash; never sees a change set half way.</p>
     *
     * @param sink where the difference models are handed over to
     * @return the changes that reached the sink
     */
    public static List<NetworkEvent> export(Network network, Collection<NetworkEvent> events, DifferenceSink sink,
                                            ExportOptions exportOptions) {
        Objects.requireNonNull(sink);
        Result result = toDifferences(network, events, exportOptions);
        sink.accept(result.differences());
        return result.exportedEvents();
    }

    /**
     * Export the changes of one profile as a difference model document, returned as an in-memory string.
     *
     * @param subset the profile to write, or {@code null} to let the changes decide, which then have to touch at
     *               most one profile
     */
    public static String toString(Network network, Collection<NetworkEvent> events, CgmesSubset subset,
                                  UnsupportedChangeBehavior unsupportedChangeBehavior) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        write(network, events, outputStream, subset,
                new ExportOptions().setUnsupportedChangeBehavior(unsupportedChangeBehavior));
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    /**
     * Export the changes of one profile as a difference model document.
     *
     * @param subset       the profile to write. When it is given, a change describing another profile is
     *                     unsupported; when it is {@code null}, the changes have to touch at most one profile
     * @param outputStream the stream to write to. It is neither flushed nor closed by this method
     * @return the changes that reached the document
     * @throws PowsyblException            if the changes touch more than one profile, or if a change cannot be
     *                                     described and the behavior is {@link UnsupportedChangeBehavior#FAIL}
     * @throws UncheckedXmlStreamException if the document cannot be written
     */
    public static List<NetworkEvent> write(Network network, Collection<NetworkEvent> events, OutputStream outputStream,
                                           CgmesSubset subset, ExportOptions exportOptions) {
        Objects.requireNonNull(outputStream);
        Objects.requireNonNull(exportOptions);
        ExportOptions options = subset != null ? exportOptions.restrictedTo(subset) : exportOptions;
        try (ExportVariantScope scope = ExportVariantScope.enter(network, options.getVariant())) {
            DifferenceModelBuilder builder = builder(network, events, options);
            Result result = builder.build();
            DifferenceModelWriter.write(singleModel(result.differences(), subset, builder), outputStream);
            return result.exportedEvents();
        }
    }

    /** As {@link #write(Network, Collection, OutputStream, CgmesSubset, ExportOptions)}, into a file. */
    public static List<NetworkEvent> write(Network network, Collection<NetworkEvent> events, Path filePath,
                                           CgmesSubset subset, ExportOptions exportOptions) throws IOException {
        Objects.requireNonNull(filePath);
        try (OutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(filePath))) {
            return write(network, events, outputStream, subset, exportOptions);
        }
    }

    /**
     * Export the changes as one difference model document per touched profile, into a data source.
     *
     * @param dataSource where to write, a directory or an archive
     * @param baseName   the common prefix of the file names, which are {@code <baseName>_<SUBSET>_DIFF.xml}
     * @return the changes that reached the documents
     */
    public static List<NetworkEvent> write(Network network, Collection<NetworkEvent> events, DataSource dataSource,
                                           String baseName, ExportOptions exportOptions) {
        Result result = toDifferences(network, events, exportOptions);
        DifferenceModelWriter.write(result.differences(), dataSource, baseName);
        return result.exportedEvents();
    }

    private static DifferenceModelBuilder builder(Network network, Collection<NetworkEvent> events,
                                                  ExportOptions exportOptions) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(events);
        Objects.requireNonNull(exportOptions);
        checkSingleGridModel(network);

        CgmesExportContext context = new CgmesExportContext(network);
        if (exportOptions.scenarioTime != null) {
            context.setScenarioTime(exportOptions.scenarioTime);
        }
        context.setModelCreated(exportOptions.created);
        return new DifferenceModelBuilder(network, context,
                EventCompactor.compact(ofSelectedVariant(events, exportOptions.getVariant()),
                        network.getVariantManager().getWorkingVariantId()), exportOptions);
    }

    /**
     * The variant a recorded change belongs to, or {@code null} when it belongs to every variant.
     *
     * <p>Exposed because a caller writing one history per variant has to group the changes before it can translate
     * them, and the rule for what counts as "of a variant" is this library's, not the caller's.</p>
     *
     * @param event the recorded change
     * @return the variant identifier, or {@code null}
     */
    public static String variantOf(NetworkEvent event) {
        return CgmesChangeTranslator.variantIdOf(event);
    }

    /**
     * The changes that belong to the selected variant.
     *
     * <p>Naming a variant is a selection, so a change recorded on another one is simply not part of this export;
     * it is dropped here rather than reported as unsupported. A change without a variant belongs to every variant
     * and is kept &mdash; {@link ExportOptions#setRejectSharedChanges} decides what happens to it.</p>
     */
    private static Collection<NetworkEvent> ofSelectedVariant(Collection<NetworkEvent> events, String variant) {
        if (variant == null) {
            return events;
        }
        return events.stream()
                .filter(event -> {
                    String eventVariant = CgmesChangeTranslator.variantIdOf(event);
                    return eventVariant == null || eventVariant.equals(variant);
                })
                .toList();
    }

    /**
     * The single model a document holds. A change set that produced none is written as an empty difference of the
     * selected profile, so that a caller always gets a well formed document describing what it asked for.
     */
    private static DifferenceModel singleModel(DifferenceModelSet differences, CgmesSubset subset,
                                               DifferenceModelBuilder builder) {
        if (differences.models().size() > 1) {
            throw new PowsyblException("The changes touch the profiles "
                    + differences.subsets().stream().map(CgmesSubset::getIdentifier).toList()
                    + "; a difference model document holds one profile, select one or write to a DataSource");
        }
        if (differences.models().size() == 1) {
            return differences.models().values().iterator().next();
        }
        return builder.emptyModel(subset != null ? subset : CgmesSubset.STEADY_STATE_HYPOTHESIS);
    }

    /**
     * A difference model describes a single individual grid model: its header references the model it replaces and
     * the equipment model it applies to, and a merged network has one of each per subnetwork.
     */
    private static void checkSingleGridModel(Network network) {
        if (!network.getSubnetworks().isEmpty()) {
            throw new PowsyblException("Network " + network.getId() + " is a merged model with "
                    + network.getSubnetworks().size() + " subnetworks. A difference model describes a single "
                    + "individual grid model, so it has to be exported from each subnetwork separately.");
        }
    }
}
