/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.Family;
import com.powsybl.cgmes.conversion.export.Families;
import com.powsybl.iidm.network.Identifiable;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Finds out which network object a difference model statement is about, which CIM class it has and which family of
 * the in-place update it belongs to.
 *
 * <p>The subject index of the mapping ({@link Families#resolve}) names the object and its class; the family is the one
 * whose classes contain that class ({@link FastRouteCapabilities#familyOfClass}), and the subject resolves only when
 * that family can carry every property the difference states about it.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class DiffSubjectResolver {

    /**
     * What a difference model subject turned out to be.
     *
     * @param family  the update query family the subject belongs to
     * @param subject what the subject index of the mapping says about it
     */
    record ResolvedSubject(Family family, Families.Subject subject) {

        /** The CIM class to write for the subject. */
        String rdfType() {
            return subject.cimClass();
        }

        /** The subject as an {@code rdf:about} value, in the form the receiving network's identifiers take. */
        String about() {
            return subject.about();
        }

        /** The IIDM object carrying the subject, which is the one a change of it is recorded on. */
        Identifiable<?> owner() {
            return subject.owner();
        }

        /** Every IIDM object the update of this subject touches, which is what a scoped update has to visit. */
        Set<String> iidmIds() {
            return subject.iidmIds();
        }
    }

    private final Families families;

    DiffSubjectResolver(Families families) {
        this.families = Objects.requireNonNull(families);
    }

    /**
     * Resolve one subject.
     *
     * @param subjectId       the identifier as the difference model states it, already normalized
     * @param properties      the properties the difference states about it, which decide whether the family that was
     *                        found can carry them
     * @param classNameHint   the class the producer gave the subject, or {@code null}
     * @return the resolution, or empty when the subject is unknown or the family cannot carry the properties
     */
    Optional<ResolvedSubject> resolve(String subjectId, Set<String> properties, String classNameHint) {
        return resolveSubject(subjectId, classNameHint)
                .filter(resolved -> FastRouteCapabilities.spec(resolved.family()).properties().containsAll(properties));
    }

    /** Why a subject could not be resolved, as a sentence to append to "&lt;id&gt;: ". */
    String reasonFor(String subjectId, Set<String> properties, String classNameHint) {
        Optional<ResolvedSubject> resolved = resolveSubject(subjectId, classNameHint);
        if (resolved.isEmpty()) {
            return families.unresolvedReason(subjectId);
        }
        Family family = resolved.get().family();
        Set<String> familyProperties = FastRouteCapabilities.spec(family).properties();
        String offending = properties.stream().filter(p -> !familyProperties.contains(p)).findFirst().orElse("?");
        return "property " + offending + " is not updatable on a " + family;
    }

    private Optional<ResolvedSubject> resolveSubject(String subjectId, String classNameHint) {
        return families.resolve(subjectId, classNameHint)
                .map(subject -> new ResolvedSubject(FastRouteCapabilities.familyOfClass(subject.cimClass()), subject));
    }
}
