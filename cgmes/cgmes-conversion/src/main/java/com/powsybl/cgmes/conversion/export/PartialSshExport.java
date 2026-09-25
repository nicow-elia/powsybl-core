/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.model.CgmesMetadataModel;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import com.powsybl.commons.xml.XmlUtil;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Exports the changes recorded on a network as a partial CGMES .ssh file.
 *
 * <p>Where the regular {@link SteadyStateHypothesisExport} writes the complete steady state of a network, this
 * exporter writes only the objects affected by a list of {@link NetworkEvent}s, typically collected with a
 * {@code NetworkEventRecorder}. The result is a valid SSH instance file that a receiver holding the same base
 * model can apply through the network update workflow.</p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * NetworkEventRecorder recorder = new NetworkEventRecorder();
 * network.addListener(recorder);
 * network.getGenerator("G").setTargetP(120.0);
 * String ssh = PartialSshExport.toString(network, recorder.getEvents(), UnsupportedChangeBehavior.FAIL);
 * }</pre>
 *
 * <p>Changes that have no representation in the SSH profile or whose import/export is not implemented, such as the
 * creation or removal of equipment, are either reported as an error or skipped with a warning:
 * {@link UnsupportedChangeBehavior#FAIL} makes sure a change is never silently lost, while
 * {@link UnsupportedChangeBehavior#IGNORE} means only the supported changes are written to file. Skipped elements are
 * logged and the write method returns a list of written events for cross-reference.</p>
 *
 * <p>A partial SSH describes a single individual grid model, because its header has to reference the SSH it
 * replaces and the equipment model it applies to. A merged network has one of each per subnetwork, so it cannot be
 * exported as a whole, subnetworks have to be passed in one at a time.</p>
 *
 * <p>The receiving side applies the file through the network update workflow, which needs
 * {@code iidm.import.cgmes.use-previous-values-during-update} so that it keeps its current value for everything the
 * partial file does not mention. Two further import parameters have to match on both sides for the changes that
 * depend on them: {@code iidm.import.cgmes.create-active-power-control-extension} for participation factors and
 * {@code iidm.import.cgmes.use-detailed-dc-model} for the converters of the detailed DC model. What is supported,
 * what is only accepted, and what is reported as unsupported is listed in the CGMES export documentation.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class PartialSshExport {

    /**
     * What to do with a recorded change that cannot be written to a Steady State Hypothesis file.
     */
    public enum UnsupportedChangeBehavior {
        /** Throw a {@link PowsyblException} describing the change. This is what {@link ExportOptions} defaults to. */
        FAIL,
        /** Log a warning and continue, leaving the change out of the exported file. */
        IGNORE
    }

    /**
     * Optional settings of a partial Steady State Hypothesis export.
     *
     * <p>Every header value has a default derived from the model the network was imported from: the identifier is
     * generated, the version is the one of the source SSH incremented by one, and the new model supersedes the
     * source SSH and depends on whatever the source SSH depended on, that is on the equipment model that both the
     * sender and the receiver share.</p>
     */
    public static final class ExportOptions {

        private UnsupportedChangeBehavior unsupportedChangeBehavior = UnsupportedChangeBehavior.FAIL;
        private final ModelHeaderSettings header = new ModelHeaderSettings();
        private String variant;
        private boolean rejectSharedChanges;

        public ExportOptions setUnsupportedChangeBehavior(UnsupportedChangeBehavior unsupportedChangeBehavior) {
            this.unsupportedChangeBehavior = Objects.requireNonNull(unsupportedChangeBehavior);
            return this;
        }

        /**
         * @return what happens to a change that cannot be written into a partial steady state hypothesis
         */
        public UnsupportedChangeBehavior getUnsupportedChangeBehavior() {
            return unsupportedChangeBehavior;
        }

        /**
         * Export the state of one variant of the network.
         *
         * <p>Changes recorded on another variant are dropped, and the export runs with this variant selected so
         * that the values it writes are the values of that variant. The working variant of the calling thread is
         * restored afterwards.</p>
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
         * <p>Off by default. See {@code CgmesDiffExport.ExportOptions.setRejectSharedChanges}: a change of an
         * impedance, of an operational limit or of a property belongs to every variant of the network.</p>
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

        /**
         * An independent copy of these options.
         *
         * @return the copy
         */
        public ExportOptions copy() {
            ExportOptions copy = new ExportOptions();
            copy.unsupportedChangeBehavior = unsupportedChangeBehavior;
            copy.variant = variant;
            copy.rejectSharedChanges = rejectSharedChanges;
            header.copyInto(copy.header);
            return copy;
        }

        /** Set the identifier of the exported model, instead of generating one. */
        public ExportOptions setModelId(String modelId) {
            header.setModelId(modelId);
            return this;
        }

        public ExportOptions setDescription(String description) {
            header.setDescription(description);
            return this;
        }

        /** Set the version of the exported model, instead of incrementing the version of the source SSH. */
        public ExportOptions setVersion(int version) {
            header.setVersion(version);
            return this;
        }

        public ExportOptions setModelingAuthoritySet(String modelingAuthoritySet) {
            header.setModelingAuthoritySet(modelingAuthoritySet);
            return this;
        }

        /**
         * Set the point in time the exported state describes, instead of the case date of the network.
         *
         * <p>The scenario time is part of the identity of a model: two files describing the same grid at two
         * different moments are two models, and a receiver uses it to order what it applies.</p>
         */
        public ExportOptions setScenarioTime(ZonedDateTime scenarioTime) {
            header.setScenarioTime(scenarioTime);
            return this;
        }

        /**
         * Set the creation time of the exported model, instead of the time at which the file is written.
         *
         * <p>Setting it makes an export reproducible, and lets a caller writing a file on behalf of an earlier
         * event date it with that event rather than with now.</p>
         */
        public ExportOptions setCreated(ZonedDateTime created) {
            header.setCreated(created);
            return this;
        }

        /** Drop the dependencies inherited from the source SSH, keeping only those added explicitly. */
        public ExportOptions clearDependencies() {
            header.clearDependencies();
            return this;
        }

        public ExportOptions addDependentOn(String modelId) {
            header.addDependentOn(modelId);
            return this;
        }

        public ExportOptions addDependentOn(Collection<String> modelIds) {
            header.addDependentOn(modelIds);
            return this;
        }

        /** Whether the exported model declares that it supersedes the SSH the network was imported from. */
        public ExportOptions setSupersedePreviousSshModel(boolean supersedePreviousSshModel) {
            header.setSupersedePreviousModel(supersedePreviousSshModel);
            return this;
        }

        public ExportOptions addSupersedes(String modelId) {
            header.addSupersedes(modelId);
            return this;
        }

        public ExportOptions addSupersedes(Collection<String> modelIds) {
            header.addSupersedes(modelIds);
            return this;
        }
    }

    private PartialSshExport() {
    }

    /**
     * Export the given changes as a partial .ssh file and return it as an in-memory string.
     *
     * <p>Shorthand for {@link #write(Network, Collection, OutputStream, ExportOptions)} that collects the file in
     * memory.</p>
     *
     * @param network                   the network the changes were recorded on. It has to be an individual grid
     *                                  model, a merged network has to be exported one subnetwork at a time
     * @param events                    the recorded changes to write
     * @param unsupportedChangeBehavior what to do with a change that can not be written
     * @return the partial SSH instance file as an XML document
     * @throws PowsyblException            if the network is a merged model, or if one of the changes has no
     *                                     representation in the SSH profile and the behavior is
     *                                     {@link UnsupportedChangeBehavior#FAIL}
     * @throws UncheckedXmlStreamException if the XML document cannot be written
     */
    public static String toString(Network network, Collection<NetworkEvent> events, UnsupportedChangeBehavior unsupportedChangeBehavior) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        write(network, events, outputStream, new ExportOptions().setUnsupportedChangeBehavior(unsupportedChangeBehavior));
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    /**
     * Export the given changes as a partial .ssh file on disk
     *
     * <p>Shorthand for {@link #write(Network, Collection, OutputStream, ExportOptions)} that opens a file and writes
     * it
     * </p>
     *
     * @param network                   the network the changes were recorded on. It has to be an individual grid
     *                                  model, a merged network has to be exported one subnetwork at a time
     * @param events                    the recorded changes to write
     * @param filePath                  the file to write the instance file to
     * @param unsupportedChangeBehavior what to do with a change that has no representation in the SSH profile
     * @return the changes that reached the file, as described by
     *         {@link #write(Network, Collection, OutputStream, ExportOptions)}
     * @throws IOException                 if the file cannot be opened or written
     * @throws PowsyblException            if the network is a merged model, or if one of the changes has no
     *                                     representation in the SSH profile and the behavior is
     *                                     {@link UnsupportedChangeBehavior#FAIL}
     * @throws UncheckedXmlStreamException if the XML document cannot be written
     */
    public static List<NetworkEvent> write(Network network, Collection<NetworkEvent> events, Path filePath, UnsupportedChangeBehavior unsupportedChangeBehavior) throws IOException {
        Objects.requireNonNull(filePath);
        try (OutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(filePath))) {
            return write(network, events, outputStream, new ExportOptions().setUnsupportedChangeBehavior(unsupportedChangeBehavior));
        }
    }

    /**
     * Export the given changes as a partial .ssh file and write it to an output stream.
     *
     * <p>The events are first compacted as described in {@link #compactEvents(Collection)} before they are
     * written.</p>
     *
     * @param network       the network the changes were recorded on. It has to be an individual grid model, a
     *                      merged network has to be exported one subnetwork at a time. It also provides the
     *                      metadata of the source SSH, from which the header of the exported model is derived
     * @param events        the recorded changes to write, typically those collected by a {@code NetworkEventRecorder}
     * @param outputStream  the stream to write the instance file to. It is neither flushed nor closed by this
     *                      method
     * @param exportOptions the header values and the unsupported change behavior of the export
     * @return the changes that reached the file, in the order in which they were written. With
     *         {@link UnsupportedChangeBehavior#FAIL} the result is exactly {@link #compactEvents(Collection)}.
     *         With {@link UnsupportedChangeBehavior#IGNORE} it is a subset of that.
     * @throws PowsyblException            if the network is a merged model, or something could not be exported.
     * @throws UncheckedXmlStreamException if the XML document cannot be written
     */
    public static List<NetworkEvent> write(Network network, Collection<NetworkEvent> events, OutputStream outputStream, ExportOptions exportOptions) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(events);
        Objects.requireNonNull(outputStream);
        Objects.requireNonNull(exportOptions);
        checkSingleGridModel(network);

        try (ExportVariantScope scope = ExportVariantScope.enter(network, exportOptions.getVariant())) {
            CgmesExportContext context = new CgmesExportContext(network);
            if (exportOptions.header.getScenarioTime() != null) {
                context.setScenarioTime(exportOptions.header.getScenarioTime());
            }
            context.setModelCreated(exportOptions.header.getCreated());
            CgmesMetadataModel model =
                    exportOptions.header.initialize(network, CgmesSubset.STEADY_STATE_HYPOTHESIS, context);
            CgmesChangeTranslator translator =
                    new CgmesChangeTranslator(network, context, exportOptions.unsupportedChangeBehavior)
                            .setRejectSharedChanges(exportOptions.isRejectSharedChanges());
            CgmesPropertyBuffer updates =
                    translator.translateAll(compactEvents(ofSelectedVariant(events, exportOptions.getVariant())));

            try {
                // Buffered UTF-8 under the StAX writer: over a bare OutputStream the JDK writer emits one byte per
                // call. The same bytes; the flush pushes them through to the stream, which is not closed
                Writer out = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
                XMLStreamWriter writer = XmlUtil.initializeWriter(true, "    ", out);
                write(updates, writer, context, model, network);
                writer.flush();
            } catch (XMLStreamException e) {
                throw new UncheckedXmlStreamException(e);
            }
            return List.copyOf(translator.exportedEvents());
        }
    }

    /**
     * Keep, for every updated attribute, only the last recorded change.
     *
     * <p>Attributes of an extension are compacted the same way, under the name of their extension, so that a
     * repeated change of a participation factor collapses like a repeated change of a setpoint. The relative order
     * of the retained changes is the order of their last occurrence. Events that are neither an attribute update nor
     * an extension attribute update are all retained, since they cannot be identified by an attribute.</p>
     *
     * <p>Compaction is per (identifiable, attribute key); limit events are keyed per operational limits group and,
     * for a temporary limit, per acceptable duration, because IIDM reports all of them under a single attribute name
     * and two changes of two groups do not describe the same value.</p>
     */
    public static List<NetworkEvent> compactEvents(Collection<NetworkEvent> events) {
        return EventCompactor.compact(events, null).events();
    }

    /**
     * The changes that belong to the selected variant; naming a variant is a selection, not a rejection.
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
     * A partial SSH describes a single individual grid model: its header has to reference the SSH it replaces and
     * the equipment model it applies to, and a merged network has one of each per subnetwork.
     */
    private static void checkSingleGridModel(Network network) {
        if (!network.getSubnetworks().isEmpty()) {
            throw new PowsyblException("Network " + network.getId() + " is a merged model with "
                    + network.getSubnetworks().size() + " subnetworks. A partial SSH file describes a single "
                    + "individual grid model, so it has to be exported from each subnetwork separately.");
        }
    }

    private static void write(CgmesPropertyBuffer updates, XMLStreamWriter writer, CgmesExportContext context,
                              CgmesMetadataModel model, Network network) throws XMLStreamException {
        String cimNamespace = context.getCim().getNamespace();
        CgmesExportUtil.writeRdfRoot(cimNamespace, context.getCim().getEuPrefix(), context.getCim().getEuNamespace(), writer);
        if (context.getCimVersion() >= 16) {
            CgmesExportUtil.writeModelDescription(network, CgmesSubset.STEADY_STATE_HYPOTHESIS, writer, model, context);
        }
        updates.write(cimNamespace, writer, context);
        writer.writeEndDocument();
    }
}
