/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.model.diff;

import java.util.Objects;

/**
 * One RDF statement (triple) about an existing CGMES object.
 *
 * <p>Statements are the currency of the difference pipeline: a recorded network change is translated into the
 * properties of the CGMES objects it affects, those properties are turned into statements, and a
 * {@link DifferenceModel} holds the statements of the state before and of the state after the change. A sink then
 * writes them, as an IEC 61970-552 difference model document or into a triple store.</p>
 *
 * <p>The subject of a statement is always an object that already exists in the model the difference applies to: this
 * record cannot express the creation or the removal of an object, and it cannot express a multi-valued property.
 * Every property is assumed to be <em>functional</em>, that is to hold exactly one value per subject, which is what
 * makes {@link Key} identify a statement inside one direction of a difference model.</p>
 *
 * @param subjectId the master resource identifier of the subject, without the leading {@code _} or {@code #} and
 *                  already URL encoded by the producer when identifiers are encoded. A writer emits it as
 *                  {@code rdf:about="#_" + subjectId}
 * @param className the CIM class owning the property, for instance {@code ConformLoad}. A hint for readers and
 *                  debugging only: it is never serialized and is {@code null} on a parsed statement, which is why
 *                  {@link #equals(Object)} ignores it
 * @param property  the local name of the property in the CIM namespace, for instance {@code EnergyConsumer.p}
 * @param value     the value in its lexical form, exactly as it is serialized. For {@link Kind#ENUM} it is
 *                  {@code <Enumeration>.<literal>} such as {@code UnitMultiplier.k} and a writer prepends the CIM
 *                  namespace; for {@link Kind#REFERENCE} it is the referred identifier without its leading
 *                  {@code _} and a writer emits {@code #_<value>}
 * @param kind      how the value is to be serialized
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record CgmesStatement(String subjectId, String className, String property, String value, Kind kind) {

    /**
     * The {@link #property()} of a statement that says what class its subject is an instance of.
     *
     * <p>RDF/XML expresses the type of a resource either as a typed node element ({@code <cim:ConformLoad
     * rdf:about="#_L1">}) or as an explicit {@code rdf:type} property; a difference model may use both. Both shapes
     * become a statement with this property, {@link Kind#REFERENCE} and the local name of the class as its value, so
     * that a consumer sees one representation of the type whatever the producer wrote.</p>
     *
     * <p>It is not a CIM property, which is why it is spelled with its prefix: no CIM property name can collide
     * with it.</p>
     */
    public static final String RDF_TYPE = "rdf:type";

    /** How the value of a statement is written. */
    public enum Kind {
        /** A plain literal, written as the text of the property element, without an RDF datatype. */
        LITERAL,
        /** A CIM enumeration literal, written as {@code rdf:resource} in the CIM namespace. */
        ENUM,
        /** A reference to another object of the model, written as a local {@code rdf:resource}. */
        REFERENCE
    }

    /**
     * What identifies a statement inside one direction of a difference model: its subject and its property.
     *
     * <p>Properties are assumed to be functional, so a direction holds at most one statement per key. This is what
     * lets {@link DifferenceModel#compose} fold a chain of differences and {@link DifferenceModel#minimized} drop
     * the statements that do not change anything.</p>
     *
     * @param subjectId the subject of the statement
     * @param property  the property of the statement
     */
    public record Key(String subjectId, String property) {
    }

    public CgmesStatement {
        Objects.requireNonNull(subjectId);
        Objects.requireNonNull(property);
        Objects.requireNonNull(value);
        Objects.requireNonNull(kind);
    }

    /** The subject and property of this statement. */
    public Key key() {
        return new Key(subjectId, property);
    }

    /** Whether this statement says what class its subject is an instance of, see {@link #RDF_TYPE}. */
    public boolean isType() {
        return RDF_TYPE.equals(property);
    }

    /** A statement whose value is a plain literal, such as {@code EnergyConsumer.p} = {@code 12.5}. */
    public static CgmesStatement literal(String subjectId, String className, String property, String value) {
        return new CgmesStatement(subjectId, className, property, value, Kind.LITERAL);
    }

    /**
     * A statement whose value is a CIM enumeration literal.
     *
     * @param enumLiteral the literal qualified by its enumeration, such as {@code UnitMultiplier.k}
     */
    public static CgmesStatement enumeration(String subjectId, String className, String property, String enumLiteral) {
        return new CgmesStatement(subjectId, className, property, enumLiteral, Kind.ENUM);
    }

    /**
     * A statement whose value points at another object of the model.
     *
     * @param referredId the master resource identifier of the referred object, without a leading {@code _}
     */
    public static CgmesStatement reference(String subjectId, String className, String property, String referredId) {
        return new CgmesStatement(subjectId, className, property, referredId, Kind.REFERENCE);
    }

    /**
     * Two statements are equal when they say the same thing about the same object, whatever class the producer
     * believed the subject to have.
     *
     * <p>{@link #className()} is a hint that is not serialized, so a statement read back from a document carries
     * {@code null} there while the statement that generated it carries the CIM class. Comparing it would make a
     * generated and a parsed statement differ although they describe the very same triple, and every use of
     * statements in this package &mdash; minimizing a model, composing a chain, asserting a round trip &mdash;
     * compares triples, not producers.</p>
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof CgmesStatement other
                && subjectId.equals(other.subjectId)
                && property.equals(other.property)
                && value.equals(other.value)
                && kind == other.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(subjectId, property, value, kind);
    }
}
