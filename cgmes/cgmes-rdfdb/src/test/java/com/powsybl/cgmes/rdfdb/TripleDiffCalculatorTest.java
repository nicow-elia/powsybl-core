/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comparing two graphs of one profile: what counts as a change, and what does not.
 *
 * <p>Pure. What is being pinned down is the semantics a file ingestion depends on &mdash; that a re-export with
 * different number formatting is not a change, that an added object arrives as a type plus its properties, that a
 * removed one arrives as a reverse type plus all of them, and that the result is deterministic.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class TripleDiffCalculatorTest {

    private static final String CIM = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
    private static final String BASE = "http://micro/#";
    private static final ValueFactory FACTORY = SimpleValueFactory.getInstance();

    private static Statement literal(String subject, String property, String value) {
        return FACTORY.createStatement(FACTORY.createIRI(BASE + "_" + subject),
                FACTORY.createIRI(CIM + property), FACTORY.createLiteral(value));
    }

    private static Statement type(String subject, String className) {
        return FACTORY.createStatement(FACTORY.createIRI(BASE + "_" + subject),
                FACTORY.createIRI(RdfDbVocabulary.RDF_TYPE), FACTORY.createIRI(CIM + className));
    }

    private static Statement reference(String subject, String property, String target) {
        return FACTORY.createStatement(FACTORY.createIRI(BASE + "_" + subject),
                FACTORY.createIRI(CIM + property), FACTORY.createIRI(BASE + "_" + target));
    }

    private static Statement header(String id, String property, String value) {
        return FACTORY.createStatement(FACTORY.createIRI(id), FACTORY.createIRI(property),
                FACTORY.createLiteral(value));
    }

    private static Statement headerType(String id) {
        return FACTORY.createStatement(FACTORY.createIRI(id), FACTORY.createIRI(RdfDbVocabulary.RDF_TYPE),
                FACTORY.createIRI(RdfDbVocabulary.FULL_MODEL));
    }

    private static DifferenceModel diff(List<Statement> before, List<Statement> after) {
        DifferenceModelHeader h = DifferenceModelHeader
                .builder("urn:uuid:d", CgmesSubset.STEADY_STATE_HYPOTHESIS, CIM)
                .supersedes(List.of("urn:uuid:base")).build();
        return TripleDiffCalculator.diff(before, BASE, after, BASE, CIM, h);
    }

    private static List<String> values(List<CgmesStatement> statements) {
        List<String> out = new ArrayList<>();
        statements.forEach(s -> out.add(s.subjectId() + " " + s.property() + "=" + s.value()));
        return out;
    }

    @Test
    void anAttributeChangeIsOneForwardAndOneReverseStatement() {
        DifferenceModel d = diff(List.of(literal("L1", "EnergyConsumer.p", "10")),
                List.of(literal("L1", "EnergyConsumer.p", "12.5")));

        assertThat(values(d.forward())).containsExactly("L1 EnergyConsumer.p=12.5");
        assertThat(values(d.reverse())).containsExactly("L1 EnergyConsumer.p=10");
    }

    @Test
    void numberFormattingIsNotAChange() {
        assertThat(diff(List.of(literal("L1", "EnergyConsumer.p", "10")),
                List.of(literal("L1", "EnergyConsumer.p", "10.0"))).isEmpty()).isTrue();
        assertThat(diff(List.of(literal("L1", "EnergyConsumer.p", "10")),
                List.of(literal("L1", "EnergyConsumer.p", "1e1"))).isEmpty()).isTrue();
        // ...but a text that is not a number compares as text
        assertThat(diff(List.of(literal("L1", "IdentifiedObject.name", "A")),
                List.of(literal("L1", "IdentifiedObject.name", "a"))).isEmpty()).isFalse();
    }

    @Test
    void anAddedObjectIsATypeAndItsProperties() {
        DifferenceModel d = diff(List.of(),
                List.of(type("L9", "ConformLoad"), literal("L9", "EnergyConsumer.p", "3.0")));

        assertThat(d.reverse()).isEmpty();
        assertThat(d.forward()).hasSize(2);
        assertThat(d.forward().stream().filter(CgmesStatement::isType).findFirst().orElseThrow().value())
                .isEqualTo("ConformLoad");
        assertThat(values(d.forward())).contains("L9 EnergyConsumer.p=3.0");
    }

    @Test
    void aRemovedObjectIsAReverseTypeAndAllOfItsProperties() {
        DifferenceModel d = diff(List.of(type("L9", "ConformLoad"), literal("L9", "EnergyConsumer.p", "3.0")),
                List.of());

        assertThat(d.forward()).isEmpty();
        assertThat(d.reverse()).hasSize(2);
        assertThat(d.reverse().stream().filter(CgmesStatement::isType)).hasSize(1);
    }

    @Test
    void aMultiValuedPropertyComparesAsASet() {
        DifferenceModel d = diff(List.of(reference("M1", "Model.DependentOn", "A"),
                        reference("M1", "Model.DependentOn", "B")),
                List.of(reference("M1", "Model.DependentOn", "B"),
                        reference("M1", "Model.DependentOn", "C")));

        assertThat(values(d.forward())).containsExactly("M1 Model.DependentOn=C");
        assertThat(values(d.reverse())).containsExactly("M1 Model.DependentOn=A");
    }

    @Test
    void theModelHeaderIsExcludedAndTheOrderIsDeterministic() {
        List<Statement> before = List.of(headerType("urn:uuid:old"),
                header("urn:uuid:old", RdfDbVocabulary.MODEL_SCENARIO_TIME, "2014-06-01T10:30:00Z"),
                literal("L2", "EnergyConsumer.p", "1"), literal("L1", "EnergyConsumer.p", "1"));
        List<Statement> after = List.of(headerType("urn:uuid:new"),
                header("urn:uuid:new", RdfDbVocabulary.MODEL_SCENARIO_TIME, "2014-06-01T11:00:00Z"),
                literal("L2", "EnergyConsumer.p", "2"), literal("L1", "EnergyConsumer.p", "2"));

        DifferenceModel d = diff(before, after);

        // The header differs on both sides and is not part of the model
        assertThat(values(d.forward())).containsExactly("L2 EnergyConsumer.p=2", "L1 EnergyConsumer.p=2");
        assertThat(values(d.reverse())).containsExactly("L2 EnergyConsumer.p=1", "L1 EnergyConsumer.p=1");
        // The new graph's order is the order of the result, whatever the parent's is
        assertThat(values(diff(before, after).forward())).isEqualTo(values(d.forward()));
    }

    /**
     * The fast path of {@code comparable} must not change a single answer.
     *
     * <p>{@code comparable} skips {@code Double.parseDouble} for literals that cannot be numbers. The pre-check is
     * only sound if it accepts everything {@code parseDouble} accepts, so the cheap form is asserted against the
     * expensive one on the literals that sit on the boundary: leading and trailing blanks (which
     * {@code parseDouble} ignores), exponents, the named values, the type suffixes it allows, and the strings that
     * start like a number and are not one.</p>
     */
    @Test
    void theNumericPreCheckAgreesWithParseDouble() {
        for (String value : List.of(" 12", "12 ", "\t12\n", "1e1", "1E1", "NaN", "Infinity", "-Infinity",
                "+Infinity", "0x1p3", "1.5f", "1.5d", "1.5D", ".5", "-.5", "N/A", "-", "+", "", " ", "true",
                "false", "Infinity2", "NaNa", "Svedala Area", "2020-12-02T00:00:00Z", "1,5", "e1")) {
            CgmesStatement statement = CgmesStatement.literal("S", null, "EnergyConsumer.p", value);
            assertThat(TripleDiffCalculator.comparable(statement))
                    .as("comparable of the literal \"" + value + "\"")
                    .isEqualTo(reference(statement));
        }
        // And a non-literal never goes near parseDouble
        assertThat(TripleDiffCalculator.comparable(CgmesStatement.reference("S", null, "Terminal", "12")))
                .isEqualTo("R12");
        assertThat(TripleDiffCalculator.comparable(CgmesStatement.enumeration("S", null, "k", "12")))
                .isEqualTo("E12");
    }

    /** {@code comparable} as it was written before the pre-check: {@code parseDouble} for every literal. */
    private static String reference(CgmesStatement statement) {
        String value = statement.value();
        if (statement.kind() == CgmesStatement.Kind.LITERAL) {
            try {
                return "L" + Double.parseDouble(value);
            } catch (NumberFormatException notANumber) {
                return "L" + value;
            }
        }
        return statement.kind().name().charAt(0) + value;
    }

    /**
     * The one-value-per-side fast path must behave exactly like the set logic, including on the pairs where the
     * lexical forms differ but the normalised values do not.
     */
    @Test
    void oneValuePerSideIsDecidedLikeTheSetLogic() {
        // Equal lexical forms: nothing, whatever the characters are
        assertThat(diff(List.of(literal("L1", "EnergyConsumer.p", "N/A")),
                List.of(literal("L1", "EnergyConsumer.p", "N/A"))).isEmpty()).isTrue();
        // Different lexical forms, same number: still nothing
        assertThat(diff(List.of(literal("L1", "EnergyConsumer.p", "10")),
                List.of(literal("L1", "EnergyConsumer.p", "1e1"))).isEmpty()).isTrue();
        // Different text that is not a number: a change
        DifferenceModel d = diff(List.of(literal("L1", "IdentifiedObject.name", "A")),
                List.of(literal("L1", "IdentifiedObject.name", "B")));
        assertThat(values(d.forward())).containsExactly("L1 IdentifiedObject.name=B");
        assertThat(values(d.reverse())).containsExactly("L1 IdentifiedObject.name=A");
        // Same characters, different kind: a change on both sides
        DifferenceModel k = diff(List.of(literal("L1", "Terminal", "X")),
                List.of(reference("L1", "Terminal", "X")));
        assertThat(k.forward()).hasSize(1);
        assertThat(k.reverse()).hasSize(1);
    }
}
