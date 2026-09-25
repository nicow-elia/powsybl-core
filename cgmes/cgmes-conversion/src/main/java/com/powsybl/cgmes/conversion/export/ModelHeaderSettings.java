/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.CgmesExport;
import com.powsybl.cgmes.extensions.CgmesMetadataModels;
import com.powsybl.cgmes.model.CgmesMetadataModel;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.iidm.network.Network;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The header values of a change export, and how they are turned into the model description of one profile.
 *
 * <p>Both change exports write a model that is derived from the model the network was imported from: it carries the
 * next version, it supersedes the model it replaces, and it depends on whatever that model depended on, that is on
 * the equipment model the sender and the receiver share. Every one of those defaults can be overridden, and this
 * class is where that derivation lives, so that the partial SSH export and the difference model export produce
 * headers that only differ where they are meant to.</p>
 *
 * <p>Instances are mutable value holders filled by the export options of the caller; {@link #initialize} does not
 * change them, and it never modifies the metadata of the network either, so exporting twice from the same network
 * produces two models superseding the same source.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class ModelHeaderSettings {

    private String modelId;
    private String description;
    private Integer version;
    private String modelingAuthoritySet;
    private ZonedDateTime scenarioTime;
    private ZonedDateTime created;
    private boolean clearDependencies;
    private boolean supersedePreviousModel = true;
    private final Set<String> dependentOn = new LinkedHashSet<>();
    private final Set<String> supersedes = new LinkedHashSet<>();

    /**
     * Copy every value of these settings into another instance.
     *
     * <p>What it is for: an export options object that a caller hands over and an export has to change something
     * of its own on &mdash; the scenario time of the snapshot being written, say &mdash; is copied first, and the
     * header values have to travel with the copy.</p>
     *
     * @param target the settings to overwrite
     */
    void copyInto(ModelHeaderSettings target) {
        target.modelId = modelId;
        target.description = description;
        target.version = version;
        target.modelingAuthoritySet = modelingAuthoritySet;
        target.scenarioTime = scenarioTime;
        target.created = created;
        target.clearDependencies = clearDependencies;
        target.supersedePreviousModel = supersedePreviousModel;
        target.dependentOn.clear();
        target.dependentOn.addAll(dependentOn);
        target.supersedes.clear();
        target.supersedes.addAll(supersedes);
    }

    /** Set the identifier of the exported model, instead of generating one. */
    void setModelId(String modelId) {
        this.modelId = modelId;
    }

    String getModelId() {
        return modelId;
    }

    void setDescription(String description) {
        this.description = description;
    }

    /** Set the version of the exported model, instead of incrementing the version of the source model. */
    void setVersion(int version) {
        this.version = version;
    }

    void setModelingAuthoritySet(String modelingAuthoritySet) {
        this.modelingAuthoritySet = modelingAuthoritySet;
    }

    /** The point in time the exported state describes, or {@code null} for the case date of the network. */
    void setScenarioTime(ZonedDateTime scenarioTime) {
        this.scenarioTime = Objects.requireNonNull(scenarioTime);
    }

    ZonedDateTime getScenarioTime() {
        return scenarioTime;
    }

    /** The creation time of the exported model, or {@code null} for the time at which the file is written. */
    void setCreated(ZonedDateTime created) {
        this.created = Objects.requireNonNull(created);
    }

    ZonedDateTime getCreated() {
        return created;
    }

    /** Drop the dependencies inherited from the source model, keeping only those added explicitly. */
    void clearDependencies() {
        clearDependencies = true;
        dependentOn.clear();
    }

    boolean isClearDependencies() {
        return clearDependencies;
    }

    void addDependentOn(String modelId) {
        dependentOn.add(modelId);
    }

    void addDependentOn(Collection<String> modelIds) {
        dependentOn.addAll(modelIds);
    }

    /** Whether the exported model declares that it supersedes the model the network was imported from. */
    void setSupersedePreviousModel(boolean supersedePreviousModel) {
        this.supersedePreviousModel = supersedePreviousModel;
    }

    void addSupersedes(String modelId) {
        supersedes.add(modelId);
    }

    void addSupersedes(Collection<String> modelIds) {
        supersedes.addAll(modelIds);
    }

    /**
     * The model description of the exported model of one profile, derived from the model of that profile the network
     * was imported from and overridden with whatever this settings object holds.
     *
     * @param network the network the changes were recorded on, which carries the source models
     * @param subset  the profile the exported model describes
     * @param context the export context, which provides the naming strategy and the scenario time the identifier is
     *                derived from
     */
    CgmesMetadataModel initialize(Network network, CgmesSubset subset, CgmesExportContext context) {
        return initialize(network, subset, context, false);
    }

    /**
     * As {@link #initialize(Network, CgmesSubset, CgmesExportContext)}.
     *
     * @param differenceModel whether the generated identifier is the one of a difference model, which never collides
     *                        with the identifier of a partial file of the same base version
     */
    CgmesMetadataModel initialize(Network network, CgmesSubset subset, CgmesExportContext context, boolean differenceModel) {
        CgmesMetadataModel model = CgmesExport.initializeModelForExport(network, subset, context, true, false);
        Optional<CgmesMetadataModel> sourceModel = sourceModel(network, subset);

        if (description != null) {
            model.setDescription(description);
        }
        if (version != null) {
            model.setVersion(version);
        } else {
            sourceModel.ifPresent(source -> model.setVersion(source.getVersion() + 1));
        }
        if (modelingAuthoritySet != null) {
            model.setModelingAuthoritySet(modelingAuthoritySet);
        }

        if (modelId != null) {
            model.setId(modelId);
        } else if (differenceModel) {
            CgmesExportUtil.initializeDifferenceModelId(network, model, context);
        } else {
            CgmesExportUtil.initializeModelId(network, model, context);
        }

        if (clearDependencies) {
            model.clearDependencies();
        }
        model.addDependentOn(dependentOn);

        model.clearSupersedes();
        if (supersedePreviousModel) {
            sourceModel.map(CgmesMetadataModel::getId)
                    .filter(id -> id != null && !id.isEmpty())
                    .filter(id -> !id.equals(model.getId()))
                    .ifPresent(model::addSupersedes);
        }
        supersedes.stream()
                .filter(id -> !id.equals(model.getId()))
                .forEach(model::addSupersedes);

        return model;
    }

    /** The model of the given profile the network was imported from, if it has one. */
    static Optional<CgmesMetadataModel> sourceModel(Network network, CgmesSubset subset) {
        CgmesMetadataModels networkModels = network.getExtension(CgmesMetadataModels.class);
        return networkModels != null ? networkModels.getModelForSubset(subset) : Optional.empty();
    }
}
