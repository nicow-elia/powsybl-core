/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.export.EventCompactor.CompactedChanges;
import com.powsybl.cgmes.model.CgmesMetadataModel;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a recorded change log into the difference models describing it, one per CGMES profile it touches.
 *
 * <p>Every change is translated twice by the very same mapping: once against the live network, which yields the
 * forward statements, and once against the state the change log says the network was in before, which yields the
 * reverse ones. A change is exported only when both directions succeed, so a difference model never holds a forward
 * statement whose reverse is missing or guessed.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class DifferenceModelBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(DifferenceModelBuilder.class);

    private final Network network;
    private final CgmesExportContext context;
    private final CompactedChanges changes;
    private final CgmesDiffExport.ExportOptions options;
    private final IidmStateView previousState;
    /**
     * Resolved once, so that every profile of one change set carries the same creation time: the models of a set
     * describe one change, and a receiver orders them by it.
     */
    private final ZonedDateTime created;

    DifferenceModelBuilder(Network network, CgmesExportContext context, CompactedChanges changes,
                           CgmesDiffExport.ExportOptions options) {
        this.network = network;
        this.context = context;
        this.changes = changes;
        this.options = options;
        this.previousState = IidmStateView.before(changes);
        this.created = Optional.ofNullable(context.getModelCreated()).orElseGet(ZonedDateTime::now);
    }

    /** The difference models of the change log, and the changes that reached them. */
    CgmesDiffExport.Result build() {
        CgmesPropertyBuffer after = new CgmesPropertyBuffer();
        CgmesPropertyBuffer before = new CgmesPropertyBuffer();
        List<NetworkEvent> exportedEvents = translate(after, before);
        return buildFrom(after, before, exportedEvents);
    }

    /**
     * The difference models of two already translated buffers.
     *
     * <p>Separate from {@link #build()} so that the assembly of the models &mdash; the no-op compaction, the
     * granularity and the headers &mdash; can be exercised on buffers that no mapping produces yet.</p>
     */
    CgmesDiffExport.Result buildFrom(CgmesPropertyBuffer after, CgmesPropertyBuffer before,
                                     List<NetworkEvent> exportedEvents) {
        Set<CgmesSubset> subsets = new LinkedHashSet<>(after.subsets());
        subsets.addAll(before.subsets());

        List<DifferenceModel> models = new ArrayList<>(subsets.size());
        Map<CgmesSubset, CgmesMetadataModel> metadata = new EnumMap<>(CgmesSubset.class);
        for (CgmesSubset subset : subsets) {
            List<CgmesStatement> forward = after.statements(subset, context);
            List<CgmesStatement> reverse = before.statements(subset, context);
            Set<String> unchangedSubjects = unchangedSubjects(forward, reverse);
            forward = withoutSubjects(forward, unchangedSubjects);
            reverse = withoutSubjects(reverse, unchangedSubjects);
            if (forward.isEmpty() && reverse.isEmpty()) {
                continue;
            }
            CgmesMetadataModel model = options.header(subset).settings()
                    .initialize(network, subset, context, true);
            metadata.put(subset, model);
            DifferenceModel differenceModel = new DifferenceModel(header(model, subset), forward, reverse, List.of());
            models.add(options.getGranularity() == CgmesDiffExport.DiffGranularity.CHANGED_ONLY
                    ? differenceModel.minimized() : differenceModel);
        }
        models = dependOnTheEquipmentDifference(models, metadata);
        logUnconsumedKeys();
        return new CgmesDiffExport.Result(new DifferenceModelSet(models), List.copyOf(exportedEvents));
    }

    /** The state before the change set, exposed so that tests can check that every change was really read. */
    IidmStateView previousState() {
        return previousState;
    }

    /**
     * A difference model of the given profile that says nothing, which is what a single document export writes when
     * the change set produced no difference at all.
     */
    DifferenceModel emptyModel(CgmesSubset subset) {
        CgmesMetadataModel model = options.header(subset).settings().initialize(network, subset, context, true);
        return new DifferenceModel(header(model, subset), List.of(), List.of(), List.of());
    }

    private List<NetworkEvent> translate(CgmesPropertyBuffer after, CgmesPropertyBuffer before) {
        CgmesChangeRegulatingControls regulatingControls = new CgmesChangeRegulatingControls(network, context);
        CgmesChangeTranslator afterTranslator = new CgmesChangeTranslator(network, context,
                options.getUnsupportedChangeBehavior(), CgmesDiffExport.DIFFERENCE_MODEL_TARGET,
                options.getSubsets(), IidmStateView.LIVE, regulatingControls)
                .setRejectSharedChanges(options.isRejectSharedChanges());
        // The index is structure only, so both directions share the single walk over the network it costs
        CgmesChangeTranslator beforeTranslator = new CgmesChangeTranslator(network, context,
                options.getUnsupportedChangeBehavior(), CgmesDiffExport.DIFFERENCE_MODEL_TARGET,
                options.getSubsets(), previousState, regulatingControls)
                .setRejectSharedChanges(options.isRejectSharedChanges());

        List<NetworkEvent> exportedEvents = new ArrayList<>();
        for (NetworkEvent event : changes.events()) {
            String reason = null;
            CgmesPropertyBuffer stagedAfter = null;
            CgmesPropertyBuffer stagedBefore = null;
            switch (afterTranslator.translate(event)) {
                case Result.Success(CgmesPropertyBuffer staged) -> stagedAfter = staged;
                case Result.Failure(String failure) -> reason = failure;
            }
            if (reason == null) {
                switch (beforeTranslator.translate(event)) {
                    case Result.Success(CgmesPropertyBuffer staged) -> stagedBefore = staged;
                    case Result.Failure(String failure) -> reason = failure;
                }
            }
            if (reason != null) {
                // The reject behaviour of the export is the same whichever direction refused the change
                afterTranslator.reject(event, reason);
            } else {
                after.mergeFrom(stagedAfter);
                before.mergeFrom(stagedBefore);
                exportedEvents.add(event);
            }
        }
        return exportedEvents;
    }

    /**
     * The subjects whose forward description says exactly what its reverse description says.
     *
     * <p>Whole objects are described, because a receiver reads some CGMES properties only together, so a change that
     * cancels out on one object still produces a full description of it in both directions. Dropping those subjects
     * is what makes a change and its undo come out as an empty difference.</p>
     */
    private static Set<String> unchangedSubjects(List<CgmesStatement> forward, List<CgmesStatement> reverse) {
        Map<String, Set<CgmesStatement>> forwardBySubject = bySubject(forward);
        Map<String, Set<CgmesStatement>> reverseBySubject = bySubject(reverse);
        Set<String> unchanged = new LinkedHashSet<>();
        forwardBySubject.forEach((subject, statements) -> {
            if (statements.equals(reverseBySubject.get(subject))) {
                unchanged.add(subject);
            }
        });
        return unchanged;
    }

    private static Map<String, Set<CgmesStatement>> bySubject(List<CgmesStatement> statements) {
        return statements.stream().collect(Collectors.groupingBy(CgmesStatement::subjectId, Collectors.toSet()));
    }

    private static List<CgmesStatement> withoutSubjects(List<CgmesStatement> statements, Set<String> subjects) {
        return subjects.isEmpty() ? statements
                : statements.stream().filter(s -> !subjects.contains(s.subjectId())).toList();
    }

    private DifferenceModelHeader header(CgmesMetadataModel model, CgmesSubset subset) {
        return DifferenceModelHeader.builder(model.getId(), subset, context.getCim().getNamespace())
                .scenarioTime(context.getScenarioTime())
                .created(created)
                .description(model.getDescription())
                .version(model.getVersion())
                .modelingAuthoritySet(model.getModelingAuthoritySet())
                .profiles(List.copyOf(model.getProfiles()))
                .dependentOn(List.copyOf(model.getDependentOn()))
                .supersedes(List.copyOf(model.getSupersedes()))
                .build();
    }

    /**
     * When a change set touches the equipment model as well as the steady state hypothesis, the steady state
     * difference applies on top of the equipment difference, not on the equipment model it was derived from, so the
     * dependency is redirected to the equipment difference.
     */
    private List<DifferenceModel> dependOnTheEquipmentDifference(List<DifferenceModel> models,
                                                                 Map<CgmesSubset, CgmesMetadataModel> metadata) {
        CgmesMetadataModel equipmentModel = metadata.get(CgmesSubset.EQUIPMENT);
        if (equipmentModel == null || options.header(CgmesSubset.STEADY_STATE_HYPOTHESIS).settings().isClearDependencies()) {
            return models;
        }
        String sourceEquipmentId = ModelHeaderSettings.sourceModel(network, CgmesSubset.EQUIPMENT)
                .map(CgmesMetadataModel::getId).orElse(null);
        if (sourceEquipmentId == null) {
            return models;
        }
        return models.stream().map(model -> {
            if (model.header().subset() != CgmesSubset.STEADY_STATE_HYPOTHESIS
                    || !model.header().dependentOn().contains(sourceEquipmentId)) {
                return model;
            }
            List<String> dependentOn = model.header().dependentOn().stream()
                    .map(id -> id.equals(sourceEquipmentId) ? equipmentModel.getId() : id)
                    .toList();
            return new DifferenceModel(model.header().toBuilder().dependentOn(dependentOn).build(),
                    model.forward(), model.reverse(), model.preconditions());
        }).toList();
    }

    /**
     * A recorded change whose previous value no mapping ever read is a change whose reverse statements do not
     * describe it. That is legitimate for a value the exported profile cannot express, and a bug otherwise, so it is
     * logged rather than silently dropped.
     */
    private void logUnconsumedKeys() {
        // unconsumedKeys() copies every changed key, so it is only computed when the log will actually take it
        if (LOGGER.isDebugEnabled()) {
            Set<String> unconsumed = previousState.unconsumedKeys();
            if (!unconsumed.isEmpty()) {
                LOGGER.debug("The previous value of {} was never read while building the difference models", unconsumed);
            }
        }
    }
}
