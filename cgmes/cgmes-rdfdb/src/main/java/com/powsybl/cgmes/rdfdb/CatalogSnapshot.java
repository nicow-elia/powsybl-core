/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The metadata graph of one scenario, read once and then asked as often as needed.
 *
 * <p>Every question this layer puts to the catalogue &mdash; which model is the head of a profile, which instance
 * file did it descend from, what lies between two models, which model is this identifier &mdash; is a question
 * about the <em>same</em> handful of nodes. Sending one query per question is what an update and a materialisation
 * used to do, and on a loopback server that was fifteen round trips before a single statement was fetched. The
 * metadata graph of a scenario is small by construction (one node per instance file plus one per recorded change),
 * so it is read in one query and the chain arithmetic happens here.</p>
 *
 * <p>A snapshot is exactly that: what the database said at one moment. Everything that has to be decided by the
 * database rather than by a reader &mdash; whether a difference may be written, whether it was written &mdash; is
 * decided by the guarded write of {@link RdfDbDifferenceSink}, never by a snapshot.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class CatalogSnapshot {

    private final String scenario;
    private final List<StoredModel> models;
    private final Map<String, StoredModel> byId = new LinkedHashMap<>();
    private final Map<String, List<StoredModel>> successors = new LinkedHashMap<>();
    private final boolean versioned;

    CatalogSnapshot(String scenario, List<StoredModel> models) {
        this(scenario, models, false);
    }

    CatalogSnapshot(String scenario, List<StoredModel> models, boolean versioned) {
        this.versioned = versioned;
        this.scenario = Objects.requireNonNull(scenario);
        this.models = List.copyOf(models);
        this.models.forEach(model -> byId.put(model.id(), model));
        // A successor is a model that supersedes another one; inside a scenario that is the version chain, and
        // the head of a profile is the model no successor of that profile names
        for (StoredModel model : this.models) {
            model.supersedes().forEach(superseded ->
                    successors.computeIfAbsent(superseded, k -> new ArrayList<>()).add(model));
        }
    }

    /**
     * @return the scenario this snapshot describes
     */
    public String scenario() {
        return scenario;
    }

    /**
     * @return every stored model of the scenario, sorted by profile and then by depth in the chain
     */
    public List<StoredModel> models() {
        return models;
    }

    /**
     * @return whether the scenario holds no stored model at all
     */
    public boolean isEmpty() {
        return models.isEmpty();
    }

    /**
     * Whether the scenario holds snapshots, read off the same rows as everything else.
     *
     * <p>What it decides is which entry point answers "the scenario": a versioned scenario means its newest
     * snapshot, and its graphs are not among the instance file contexts this catalogue lists.</p>
     *
     * @return whether the scenario is versioned
     */
    public boolean isVersioned() {
        return versioned;
    }

    /**
     * @return whether the scenario holds any recorded difference
     */
    public boolean hasDifferences() {
        return models.stream().anyMatch(StoredModel::isDiff);
    }

    /**
     * @return the profiles the scenario holds a model of, in the order the models are sorted in
     */
    public Set<CgmesSubset> subsets() {
        Set<CgmesSubset> subsets = new LinkedHashSet<>();
        models.forEach(model -> subsets.add(model.subset()));
        return subsets;
    }

    /**
     * One stored model by its identifier.
     *
     * @param id the CGMES model identifier
     * @return the model, or empty
     */
    public Optional<StoredModel> model(String id) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(id)));
    }

    /**
     * The model of a profile that nothing supersedes, that is the current state of that profile.
     *
     * @param subset the CGMES profile
     * @return the head model, or empty when the scenario holds no model of that profile
     * @throws RdfDbException if the profile has several heads, which means the chain forked
     */
    public Optional<StoredModel> head(CgmesSubset subset) {
        Objects.requireNonNull(subset);
        List<String> heads = new ArrayList<>(new TreeSet<>(models.stream()
                .filter(model -> model.subset() == subset)
                .filter(model -> !isSuperseded(model))
                .map(StoredModel::id)
                .toList()));
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        if (heads.size() > 1) {
            throw new RdfDbException("The " + subset.getIdentifier() + " chain of scenario '" + scenario
                    + "' has " + heads.size() + " heads " + heads + ": it forked, and this release stores one"
                    + " linear chain per profile");
        }
        return model(heads.get(0));
    }

    /**
     * The head of every profile the scenario holds.
     *
     * @return the head per profile
     * @throws RdfDbException if a profile has several heads
     */
    public Map<CgmesSubset, StoredModel> heads() {
        Map<CgmesSubset, StoredModel> heads = new EnumMap<>(CgmesSubset.class);
        subsets().forEach(subset -> head(subset).ifPresent(model -> heads.put(subset, model)));
        return heads;
    }

    private boolean isSuperseded(StoredModel model) {
        return successors.getOrDefault(model.id(), List.of()).stream()
                .anyMatch(successor -> successor.subset() == model.subset());
    }

    /**
     * The full model of a profile, that is the uploaded instance file every difference of that profile descends
     * from.
     *
     * @param subset the CGMES profile
     * @return the full model, or empty
     * @throws RdfDbException if the scenario holds several full models of that profile
     */
    public Optional<StoredModel> full(CgmesSubset subset) {
        Objects.requireNonNull(subset);
        List<StoredModel> full = models.stream()
                .filter(model -> model.subset() == subset && model.kind() == StoredModel.Kind.FULL)
                .toList();
        if (full.isEmpty()) {
            return Optional.empty();
        }
        if (full.size() > 1) {
            throw new RdfDbException("Scenario '" + scenario + "' holds " + full.size() + " full "
                    + subset.getIdentifier() + " models "
                    + full.stream().map(StoredModel::id).sorted().toList()
                    + ": one scenario describes one base grid model");
        }
        return Optional.of(full.get(0));
    }

    /**
     * The chain from a model down to the full model it descends from.
     *
     * <p>Walking {@code md:Model.Supersedes} in this list is what the property path {@code Supersedes*} used to do
     * on the server. It stops where the chain leaves the scenario: the header of an instance file may name a model
     * this database never saw, and that is not part of any chain here.</p>
     *
     * @param headId the identifier of the model to start from
     * @return the model itself first, then its predecessor, down to the full model. Empty when the identifier is
     *         not stored in this scenario
     */
    public List<StoredModel> chainDown(String headId) {
        Objects.requireNonNull(headId);
        List<StoredModel> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String id = headId;
        while (id != null && seen.add(id)) {
            StoredModel model = byId.get(id);
            if (model == null) {
                break;
            }
            chain.add(model);
            id = model.supersedes().isEmpty() ? null : model.supersedes().get(0);
        }
        // Deepest first, as the server ordered it: the target is the newest model of the chain, the full model is
        // at depth zero
        chain.sort(Comparator.comparingInt(StoredModel::chainDepth).reversed());
        return List.copyOf(chain);
    }

    /**
     * The chains of several profiles.
     *
     * @param targets the model to start from, per profile
     * @return the chain per profile, head first
     */
    public Map<CgmesSubset, List<StoredModel>> chainsDown(Map<CgmesSubset, String> targets) {
        Map<CgmesSubset, List<StoredModel>> chains = new EnumMap<>(CgmesSubset.class);
        targets.forEach((subset, id) -> chains.put(subset, chainDown(id)));
        return chains;
    }
}
