/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.model.diff;

import com.powsybl.cgmes.model.CgmesSubset;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The model description of a {@link DifferenceModel}, that is what a {@code dm:DifferenceModel} element carries
 * besides its statements.
 *
 * <p>It is the same information a CGMES {@code md:FullModel} holds, because a difference model is a model in its own
 * right: it has an identifier, a version and a modeling authority, it depends on the models whose objects it talks
 * about, and it supersedes the model it applies on &mdash; which may itself be a difference model, so that a chain of
 * differences can be replayed in order.</p>
 *
 * <p>Instances are immutable; use {@link #builder(String, CgmesSubset, String)} to create one and
 * {@link #toBuilder()} to derive a variant of one.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class DifferenceModelHeader {

    private final String id;
    private final CgmesSubset subset;
    private final String cimNamespace;
    private final ZonedDateTime scenarioTime;
    private final ZonedDateTime created;
    private final String description;
    private final int version;
    private final String modelingAuthoritySet;
    private final List<String> profiles;
    private final List<String> dependentOn;
    private final List<String> supersedes;

    private DifferenceModelHeader(Builder builder) {
        this.id = Objects.requireNonNull(builder.id);
        this.subset = Objects.requireNonNull(builder.subset);
        this.cimNamespace = Objects.requireNonNull(builder.cimNamespace);
        this.scenarioTime = builder.scenarioTime;
        this.created = builder.created;
        this.description = builder.description;
        this.version = builder.version;
        this.modelingAuthoritySet = builder.modelingAuthoritySet;
        this.profiles = List.copyOf(builder.profiles);
        this.dependentOn = List.copyOf(builder.dependentOn);
        this.supersedes = List.copyOf(builder.supersedes);
    }

    /** The identifier of this difference model, written as {@code rdf:about}, typically a {@code urn:uuid:} URI. */
    public String id() {
        return id;
    }

    /** The CGMES profile the statements of this model belong to. One difference model describes exactly one. */
    public CgmesSubset subset() {
        return subset;
    }

    /** The CIM namespace the properties of the statements live in, which also selects the European extension prefix. */
    public String cimNamespace() {
        return cimNamespace;
    }

    /**
     * The point in time the described state applies to, or {@code null} when a parsed document does not carry it.
     *
     * <p>A document produced by this library always carries one; a foreign difference model may leave the whole
     * model description out, which IEC 61970-552 allows.</p>
     */
    public ZonedDateTime scenarioTime() {
        return scenarioTime;
    }

    /** The point in time this model was produced, or {@code null} when a parsed document does not carry it. */
    public ZonedDateTime created() {
        return created;
    }

    /** A human readable description, or {@code null} when there is none. */
    public String description() {
        return description;
    }

    /** The version of this model, which a receiver uses to order the differences it applies. */
    public int version() {
        return version;
    }

    /** The authority that produced this model, or {@code null} when a parsed document does not carry it. */
    public String modelingAuthoritySet() {
        return modelingAuthoritySet;
    }

    /** The profile URIs declared by this model. */
    public List<String> profiles() {
        return profiles;
    }

    /** The identifiers of the models this one depends on, typically the equipment model of the described objects. */
    public List<String> dependentOn() {
        return dependentOn;
    }

    /** The identifiers of the models this one replaces, that is the model the difference applies on. */
    public List<String> supersedes() {
        return supersedes;
    }

    /**
     * Start building a header.
     *
     * @param id           the identifier of the model
     * @param subset       the profile its statements belong to
     * @param cimNamespace the CIM namespace of its properties
     */
    public static Builder builder(String id, CgmesSubset subset, String cimNamespace) {
        return new Builder(id, subset, cimNamespace);
    }

    /** A builder holding every value of this header, to derive a variant of it. */
    public Builder toBuilder() {
        return new Builder(id, subset, cimNamespace)
                .scenarioTime(scenarioTime)
                .created(created)
                .description(description)
                .version(version)
                .modelingAuthoritySet(modelingAuthoritySet)
                .profiles(profiles)
                .dependentOn(dependentOn)
                .supersedes(supersedes);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DifferenceModelHeader other
                && id.equals(other.id)
                && subset == other.subset
                && cimNamespace.equals(other.cimNamespace)
                && Objects.equals(scenarioTime, other.scenarioTime)
                && Objects.equals(created, other.created)
                && Objects.equals(description, other.description)
                && version == other.version
                && Objects.equals(modelingAuthoritySet, other.modelingAuthoritySet)
                && profiles.equals(other.profiles)
                && dependentOn.equals(other.dependentOn)
                && supersedes.equals(other.supersedes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, subset, cimNamespace, scenarioTime, created, description, version,
                modelingAuthoritySet, profiles, dependentOn, supersedes);
    }

    @Override
    public String toString() {
        return "DifferenceModelHeader(id=" + id + ", subset=" + subset + ", version=" + version
                + ", scenarioTime=" + scenarioTime + ", created=" + created
                + ", description=" + description + ", modelingAuthoritySet=" + modelingAuthoritySet
                + ", profiles=" + profiles + ", dependentOn=" + dependentOn + ", supersedes=" + supersedes + ")";
    }

    /** Fluent builder of a {@link DifferenceModelHeader}. */
    public static final class Builder {

        private final String id;
        private final CgmesSubset subset;
        private final String cimNamespace;
        private ZonedDateTime scenarioTime;
        private ZonedDateTime created;
        private String description;
        private int version = 1;
        private String modelingAuthoritySet;
        private List<String> profiles = new ArrayList<>();
        private List<String> dependentOn = new ArrayList<>();
        private List<String> supersedes = new ArrayList<>();

        private Builder(String id, CgmesSubset subset, String cimNamespace) {
            this.id = Objects.requireNonNull(id);
            this.subset = Objects.requireNonNull(subset);
            this.cimNamespace = Objects.requireNonNull(cimNamespace);
        }

        /** Set the point in time the described state applies to, or {@code null} when there is none. */
        public Builder scenarioTime(ZonedDateTime scenarioTime) {
            this.scenarioTime = scenarioTime;
            return this;
        }

        /** Set the point in time this model was produced, or {@code null} when there is none. */
        public Builder created(ZonedDateTime created) {
            this.created = created;
            return this;
        }

        /** Set a human readable description, or {@code null} for none. */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** Set the version of this model. Defaults to 1. */
        public Builder version(int version) {
            this.version = version;
            return this;
        }

        /** Set the authority that produced this model, or {@code null} when there is none. */
        public Builder modelingAuthoritySet(String modelingAuthoritySet) {
            this.modelingAuthoritySet = modelingAuthoritySet;
            return this;
        }

        /** Replace the declared profile URIs. */
        public Builder profiles(List<String> profiles) {
            this.profiles = new ArrayList<>(Objects.requireNonNull(profiles));
            return this;
        }

        /** Replace the identifiers of the models this one depends on. */
        public Builder dependentOn(List<String> dependentOn) {
            this.dependentOn = new ArrayList<>(Objects.requireNonNull(dependentOn));
            return this;
        }

        /** Replace the identifiers of the models this one replaces. */
        public Builder supersedes(List<String> supersedes) {
            this.supersedes = new ArrayList<>(Objects.requireNonNull(supersedes));
            return this;
        }

        /**
         * The header described by this builder.
         *
         * <p>Only the identifier, the profile and the CIM namespace are mandatory: everything a
         * {@code md:FullModel} may leave out &mdash; the scenario time, the creation time, the description and the
         * modeling authority set &mdash; stays {@code null} here, because a parsed foreign difference model may
         * carry no model description at all. The exporters of this library always fill them in.</p>
         */
        public DifferenceModelHeader build() {
            return new DifferenceModelHeader(this);
        }
    }
}
