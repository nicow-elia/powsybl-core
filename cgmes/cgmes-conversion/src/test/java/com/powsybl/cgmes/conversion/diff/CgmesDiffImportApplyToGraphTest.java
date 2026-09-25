/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.model.CgmesModelException;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.triplestore.api.PropertyBags;
import com.powsybl.triplestore.api.TripleStore;
import com.powsybl.triplestore.api.TripleStoreFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applying a difference to a named graph that is addressed by its IRI rather than by a powsybl context name.
 *
 * <p>This is the overload a database needs. The graph names of a versioned store belong to the store &mdash; a
 * model version, a difference, a scenario prefix &mdash; and are nothing a powsybl context name can express; and
 * the subjects of a stored model carry a prefix the store recorded when the model was uploaded, instead of being
 * resolved against the base IRI of a document that no longer exists. Both are given here, and the replace
 * semantics are the ones of the context-name overload: a property named by the difference is removed whatever its
 * current value is, and the forward value is written in its place.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class CgmesDiffImportApplyToGraphTest {

    private static final String GRAPH = "http://powsybl.org/rdfdb/2016-01-01/graph/urn:uuid:d1/forward";
    private static final String OTHER_GRAPH = "http://powsybl.org/rdfdb/2016-01-01/graph/urn:uuid:d2/forward";
    private static final String BASE = "http://microgrid/#";
    private static final String CIM = CgmesNamespace.CIM_16_NAMESPACE;
    private static final String LOAD = "1c6beed6";

    /** A store holding one load with an active and a reactive power, in two named graphs of its own naming. */
    private static TripleStore store() {
        TripleStore store = TripleStoreFactory.create();
        store.update("INSERT DATA { GRAPH <" + GRAPH + "> {"
                + " <" + BASE + "_" + LOAD + "> <" + CIM + "EnergyConsumer.p> \"10\" ;"
                + " <" + CIM + "EnergyConsumer.q> \"5\" ;"
                + " <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <" + CIM + "ConformLoad> . } }");
        store.update("INSERT DATA { GRAPH <" + OTHER_GRAPH + "> {"
                + " <" + BASE + "_" + LOAD + "> <" + CIM + "EnergyConsumer.p> \"99\" . } }");
        return store;
    }

    private static DifferenceModel diff(List<CgmesStatement> forward, List<CgmesStatement> reverse) {
        DifferenceModelHeader header = DifferenceModelHeader
                .builder("urn:uuid:d1", CgmesSubset.STEADY_STATE_HYPOTHESIS, CIM).build();
        return new DifferenceModel(header, forward, reverse, List.of());
    }

    private static List<String> values(TripleStore store, String graph, String property) {
        PropertyBags bags = store.query("SELECT ?v WHERE { GRAPH <" + graph + "> { <" + BASE + "_" + LOAD + "> <"
                + CIM + property + "> ?v } }");
        return bags.stream().map(bag -> bag.get("v")).sorted().toList();
    }

    @Test
    void aValueIsReplacedWhateverItsCurrentValueIs() {
        TripleStore store = store();
        try {
            // The reverse value deliberately does not match what the graph holds: the replacement is by key
            DifferenceModel model = diff(
                    List.of(CgmesStatement.literal(LOAD, null, "EnergyConsumer.p", "12.5")),
                    List.of(CgmesStatement.literal(LOAD, null, "EnergyConsumer.p", "somethingElse")));
            CgmesDiffImport.applyToGraph(store, model, GRAPH, BASE);
            assertEquals(List.of("12.5"), values(store, GRAPH, "EnergyConsumer.p"));
            // Only the named property, and only in the named graph
            assertEquals(List.of("5"), values(store, GRAPH, "EnergyConsumer.q"));
            assertEquals(List.of("99"), values(store, OTHER_GRAPH, "EnergyConsumer.p"));
        } finally {
            store.close();
        }
    }

    @Test
    void aPropertyStatedOnlyInTheReverseIsRemoved() {
        TripleStore store = store();
        try {
            DifferenceModel model = diff(List.of(),
                    List.of(CgmesStatement.literal(LOAD, null, "EnergyConsumer.q", "5")));
            CgmesDiffImport.applyToGraph(store, model, GRAPH, BASE);
            assertEquals(List.of(), values(store, GRAPH, "EnergyConsumer.q"));
            assertEquals(List.of("10"), values(store, GRAPH, "EnergyConsumer.p"));
        } finally {
            store.close();
        }
    }

    @Test
    void aPropertyStatedOnlyInTheForwardIsAdded() {
        TripleStore store = store();
        try {
            DifferenceModel model = diff(
                    List.of(CgmesStatement.literal(LOAD, null, "EnergyConsumer.pfixed", "3")), List.of());
            CgmesDiffImport.applyToGraph(store, model, GRAPH, BASE);
            assertEquals(List.of("3"), values(store, GRAPH, "EnergyConsumer.pfixed"));
        } finally {
            store.close();
        }
    }

    @Test
    void anEnumAndAReferenceAndATypeAreWrittenAsIris() {
        TripleStore store = store();
        try {
            DifferenceModel model = diff(List.of(
                    CgmesStatement.enumeration(LOAD, null, "RegulatingControl.mode", "RegulatingControlModeKind.voltage"),
                    CgmesStatement.reference(LOAD, null, "Equipment.EquipmentContainer", "some-container"),
                    CgmesStatement.reference(LOAD, null, CgmesStatement.RDF_TYPE, "NonConformLoad")), List.of());
            CgmesDiffImport.applyToGraph(store, model, GRAPH, BASE);

            PropertyBags bags = store.query("SELECT ?mode ?container ?type WHERE { GRAPH <" + GRAPH + "> { <"
                    + BASE + "_" + LOAD + "> <" + CIM + "RegulatingControl.mode> ?mode ;"
                    + " <" + CIM + "Equipment.EquipmentContainer> ?container ;"
                    + " <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?type } }");
            assertEquals(1, bags.size());
            assertEquals(CIM + "RegulatingControlModeKind.voltage", bags.get(0).get("mode"));
            // A reference is a subject identifier of the same model, so it carries the same prefix
            assertEquals(BASE + "_some-container", bags.get(0).get("container"));
            assertEquals(CIM + "NonConformLoad", bags.get(0).get("type"));
        } finally {
            store.close();
        }
    }

    @Test
    void anAbsoluteIdentifierIsUsedAsItIs() {
        TripleStore store = store();
        try {
            String header = "urn:uuid:d1";
            DifferenceModel model = diff(List.of(
                    CgmesStatement.literal(header, null, "Model.description", "a versioned header")), List.of());
            CgmesDiffImport.applyToGraph(store, model, GRAPH, BASE);
            PropertyBags bags = store.query("SELECT ?v WHERE { GRAPH <" + GRAPH + "> { <" + header + "> <"
                    + CIM + "Model.description> ?v } }");
            assertEquals(1, bags.size());
            assertEquals("a versioned header", bags.get(0).get("v"));
        } finally {
            store.close();
        }
    }

    @Test
    void anEmptySubjectBaseMeansEveryIdentifierIsAbsolute() {
        TripleStore store = store();
        try {
            DifferenceModel model = diff(List.of(
                    CgmesStatement.literal("urn:uuid:object-1", null, "IdentifiedObject.name", "n")), List.of());
            CgmesDiffImport.applyToGraph(store, model, GRAPH, "");
            PropertyBags bags = store.query("SELECT ?v WHERE { GRAPH <" + GRAPH + "> { <urn:uuid:object-1> <"
                    + CIM + "IdentifiedObject.name> ?v } }");
            assertEquals(1, bags.size());
        } finally {
            store.close();
        }
    }

    @Test
    void aRelativeIdentifierWithAnEmptySubjectBaseIsRejected() {
        TripleStore store = store();
        try {
            // Appending a master resource identifier to nothing would produce a relative reference, which a store
            // resolves against a base this code does not know. The encoder of the database layer refuses the same
            // thing, and the two have to agree
            DifferenceModel model = diff(
                    List.of(CgmesStatement.literal(LOAD, null, "EnergyConsumer.p", "1")), List.of());
            CgmesModelException e = assertThrows(CgmesModelException.class,
                () -> CgmesDiffImport.applyToGraph(store, model, GRAPH, ""));
            assertTrue(e.getMessage().contains("carries no subject base"), e.getMessage());
        } finally {
            store.close();
        }
    }

    @Test
    void anIdentifierThatCannotBeAnIriIsRejectedNamingItself() {
        TripleStore store = store();
        try {
            DifferenceModel model = diff(
                    List.of(CgmesStatement.literal("Energy Consumer", null, "EnergyConsumer.p", "1")), List.of());
            CgmesModelException e = assertThrows(CgmesModelException.class,
                () -> CgmesDiffImport.applyToGraph(store, model, GRAPH, BASE));
            assertTrue(e.getMessage().contains("illegal in an IRI"), e.getMessage());
        } finally {
            store.close();
        }
    }

    @Test
    void aGraphIriThatCannotBeWrittenIsRejected() {
        TripleStore store = store();
        try {
            DifferenceModel model = diff(
                    List.of(CgmesStatement.literal(LOAD, null, "EnergyConsumer.p", "1")), List.of());
            CgmesModelException e = assertThrows(CgmesModelException.class,
                () -> CgmesDiffImport.applyToGraph(store, model, "contexts:a graph with a space", BASE));
            assertTrue(e.getMessage().contains("illegal in an IRI"), e.getMessage());
        } finally {
            store.close();
        }
    }

    @Test
    void anEmptyDifferenceChangesNothing() {
        TripleStore store = store();
        try {
            CgmesDiffImport.applyToGraph(store, diff(List.of(), List.of()), GRAPH, BASE);
            assertEquals(List.of("10"), values(store, GRAPH, "EnergyConsumer.p"));
            assertEquals(List.of("5"), values(store, GRAPH, "EnergyConsumer.q"));
        } finally {
            store.close();
        }
    }
}
