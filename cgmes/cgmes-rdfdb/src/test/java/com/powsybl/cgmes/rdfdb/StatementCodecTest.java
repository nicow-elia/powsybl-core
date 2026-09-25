/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The statement wire format, asserted as a round trip.
 *
 * <p>The sink and the source have to be exact inverses, and "exact" here means what the difference pipeline means
 * by it: a decoded statement carries no CIM class name, because a parsed one never does either, and
 * {@code CgmesStatement.equals} ignores it. Everything else &mdash; the subject, the property, the kind of value
 * and its lexical form &mdash; has to come back unchanged, or a difference read out of the database is not the
 * difference that was written into it.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class StatementCodecTest {

    private static final String BASE = "http://microgrid/#";
    private static final String CIM = CgmesNamespace.CIM_16_NAMESPACE;
    private static final ValueFactory FACTORY = SimpleValueFactory.getInstance();

    private static CgmesStatement roundTrip(CgmesStatement statement) {
        Statement triple = StatementCodec.toStatement(FACTORY, statement, BASE, CIM);
        return StatementCodec.decode(triple.getSubject(), triple.getPredicate(), triple.getObject(), BASE, CIM);
    }

    @Test
    void aLiteralRoundTrips() {
        CgmesStatement statement = CgmesStatement.literal("load1", "ConformLoad", "EnergyConsumer.p", "12.5");
        Statement triple = StatementCodec.toStatement(FACTORY, statement, BASE, CIM);
        assertThat(triple.getSubject().stringValue()).isEqualTo(BASE + "_load1");
        assertThat(triple.getPredicate().stringValue()).isEqualTo(CIM + "EnergyConsumer.p");
        assertThat(triple.getObject().stringValue()).isEqualTo("12.5");
        assertThat(roundTrip(statement)).isEqualTo(statement);
    }

    @Test
    void anEnumerationRoundTrips() {
        CgmesStatement statement =
                CgmesStatement.enumeration("svc1", "StaticVarCompensator", "StaticVarCompensator.sVCControlMode",
                        "SVCControlMode.voltage");
        Statement triple = StatementCodec.toStatement(FACTORY, statement, BASE, CIM);
        assertThat(triple.getObject().stringValue()).isEqualTo(CIM + "SVCControlMode.voltage");
        assertThat(roundTrip(statement)).isEqualTo(statement);
    }

    @Test
    void aReferenceRoundTrips() {
        CgmesStatement statement = CgmesStatement.reference("t1", "Terminal", "Terminal.ConductingEquipment", "eq1");
        Statement triple = StatementCodec.toStatement(FACTORY, statement, BASE, CIM);
        assertThat(triple.getObject().stringValue()).isEqualTo(BASE + "_eq1");
        assertThat(roundTrip(statement)).isEqualTo(statement);
    }

    @Test
    void aTypeStatementRoundTrips() {
        CgmesStatement statement = CgmesStatement.reference("load1", null, CgmesStatement.RDF_TYPE, "ConformLoad");
        Statement triple = StatementCodec.toStatement(FACTORY, statement, BASE, CIM);
        assertThat(triple.getPredicate().stringValue()).isEqualTo(RdfDbVocabulary.RDF_TYPE);
        assertThat(triple.getObject().stringValue()).isEqualTo(CIM + "ConformLoad");
        assertThat(roundTrip(statement)).isEqualTo(statement);
    }

    @Test
    void anAbsoluteSubjectIsWrittenAsItIs() {
        CgmesStatement statement = CgmesStatement.literal("urn:uuid:abc", null, "Model.version", "3");
        Statement triple = StatementCodec.toStatement(FACTORY, statement, BASE, CIM);
        assertThat(triple.getSubject().stringValue()).isEqualTo("urn:uuid:abc");
        assertThat(roundTrip(statement)).isEqualTo(statement);
    }

    @Test
    void anAbsolutePropertyIsWrittenAsItIs() {
        String property = "http://entsoe.eu/CIM/SchemaExtension/3/1#Something.value";
        CgmesStatement statement = CgmesStatement.literal("load1", null, property, "7");
        Statement triple = StatementCodec.toStatement(FACTORY, statement, BASE, CIM);
        assertThat(triple.getPredicate().stringValue()).isEqualTo(property);
        assertThat(roundTrip(statement)).isEqualTo(statement);
    }

    @Test
    void anObjectOutsideTheModelStaysAnAbsoluteReference() {
        CgmesStatement statement = CgmesStatement.reference("load1", null, "Model.DependentOn", "urn:uuid:other");
        Statement triple = StatementCodec.toStatement(FACTORY, statement, BASE, CIM);
        assertThat(triple.getObject().stringValue()).isEqualTo("urn:uuid:other");
        assertThat(roundTrip(statement)).isEqualTo(statement);
    }

    @Test
    void aLiteralWithQuotesAndNewlinesRoundTripsThroughQueryText() {
        CgmesStatement statement =
                CgmesStatement.literal("load1", null, "IdentifiedObject.name", "a \"quoted\"\nname\twith\\tabs");
        assertThat(roundTrip(statement)).isEqualTo(statement);
        // And the SPARQL term is escaped rather than broken
        String term = StatementCodec.objectTerm(statement, BASE, CIM);
        assertThat(term).startsWith("\"").endsWith("\"")
                .contains("\\\"quoted\\\"").contains("\\n").contains("\\t").doesNotContain("\n");
    }

    @Test
    void anIdentifierWithASpaceIsRefusedNamingItself() {
        CgmesStatement statement = CgmesStatement.literal("a + b", null, "ACLineSegment.r", "1.0");
        assertThatThrownBy(() -> StatementCodec.toStatement(FACTORY, statement, BASE, CIM))
                .isInstanceOf(RdfDbException.class)
                .hasMessageContaining("a + b")
                .hasMessageContaining("illegal in an IRI");
    }

    @Test
    void aModelWithoutASubjectBaseNeedsAbsoluteIdentifiers() {
        CgmesStatement absolute = CgmesStatement.literal("urn:uuid:abc", null, "Model.version", "3");
        assertThat(StatementCodec.toStatement(FACTORY, absolute, "", CIM).getSubject().stringValue())
                .isEqualTo("urn:uuid:abc");
        CgmesStatement relative = CgmesStatement.literal("load1", null, "EnergyConsumer.p", "1.0");
        assertThatThrownBy(() -> StatementCodec.toStatement(FACTORY, relative, "", CIM))
                .isInstanceOf(RdfDbException.class)
                .hasMessageContaining("carries no subject base");
    }

    @Test
    void theTripleTextIsTheSameAsTheStatement() {
        CgmesStatement statement = CgmesStatement.literal("load1", "ConformLoad", "EnergyConsumer.p", "12.5");
        assertThat(StatementCodec.triple(statement, BASE, CIM))
                .isEqualTo("<" + BASE + "_load1> <" + CIM + "EnergyConsumer.p> \"12.5\" .");
    }
}
