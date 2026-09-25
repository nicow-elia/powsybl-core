/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.model.CgmesModel;
import com.powsybl.cgmes.model.CgmesModelException;
import com.powsybl.cgmes.model.CgmesModelFactory;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.datasource.ResourceDataSource;
import com.powsybl.commons.datasource.ResourceSet;
import com.powsybl.triplestore.api.PropertyBags;
import com.powsybl.triplestore.api.TripleStoreFactory;
import com.powsybl.triplestore.api.TripleStoreOptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slow route building block: replacing property values inside a named graph of a triple store through SPARQL
 * UPDATE, which is what a difference applied to RDF data rather than to a network means.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class TripleStoreDiffApplierTest {

    private static CgmesModel load() {
        ReadOnlyDataSource dataSource = new ResourceDataSource("load",
                new ResourceSet("/update/load/", "load_EQ.xml", "load_SSH.xml"));
        TripleStoreOptions options = new TripleStoreOptions();
        options.setQueryCatalog(Conversion.QUERY_CATALOG_NAME_UPDATE);
        return CgmesModelFactory.create(dataSource, null, TripleStoreFactory.DEFAULT_IMPLEMENTATION,
                com.powsybl.commons.report.ReportNode.NO_OP, options);
    }

    private static DifferenceModel diff(List<CgmesStatement> forward, List<CgmesStatement> reverse) {
        DifferenceModelHeader header = DifferenceModelHeader.builder("urn:uuid:applier",
                CgmesSubset.STEADY_STATE_HYPOTHESIS, CgmesNamespace.CIM_100_NAMESPACE).build();
        return new DifferenceModel(header, forward, reverse, List.of());
    }

    private static double p(CgmesModel cgmes) {
        PropertyBags consumers = cgmes.energyConsumers();
        assertEquals(1, consumers.size(), "the graph holds more than one value for the load");
        return consumers.get(0).asDouble("p");
    }

    @Test
    void aValueIsReplacedWhateverItsLexicalForm() {
        CgmesModel cgmes = load();
        try {
            double before = p(cgmes);
            // The reverse value is spelled differently from the file on purpose: a replacement must not depend on
            // the exact text of the value it replaces
            DifferenceModel model = diff(
                    List.of(CgmesStatement.literal("EnergyConsumer", null, "EnergyConsumer.p", "12.5")),
                    List.of(CgmesStatement.literal("EnergyConsumer", null, "EnergyConsumer.p",
                            Double.toString(before) + "0")));
            CgmesDiffImport.applyToTripleStore(cgmes.tripleStore(), model, cgmes.getBasename());
            assertEquals(12.5, p(cgmes), 1e-9);
        } finally {
            cgmes.close();
        }
    }

    @Test
    void aWholeObjectCanBeRemovedAndAdded() {
        CgmesModel cgmes = load();
        try {
            String context = TripleStoreDiffApplier.contextOf(cgmes.tripleStore(),
                    diff(List.of(), List.of()));
            // The class of the object is the one the equipment file gives it: a type statement names a CIM class
            // and is written in the CIM namespace, so adding a second class here would make the object two things
            DifferenceModel removal = diff(List.of(),
                    List.of(CgmesStatement.reference("EnergyConsumer", null, CgmesStatement.RDF_TYPE,
                                    "EnergyConsumer"),
                            CgmesStatement.literal("EnergyConsumer", null, "EnergyConsumer.p", "0"),
                            CgmesStatement.literal("EnergyConsumer", null, "EnergyConsumer.q", "0")));
            CgmesDiffImport.applyToTripleStore(cgmes.tripleStore(), removal, context, cgmes.getBasename());
            assertEquals(0, cgmes.energyConsumers().size());

            DifferenceModel addition = diff(
                    List.of(CgmesStatement.reference("EnergyConsumer", null, CgmesStatement.RDF_TYPE,
                                    "EnergyConsumer"),
                            CgmesStatement.literal("EnergyConsumer", null, "EnergyConsumer.p", "7"),
                            CgmesStatement.literal("EnergyConsumer", null, "EnergyConsumer.q", "3")),
                    List.of());
            CgmesDiffImport.applyToTripleStore(cgmes.tripleStore(), addition, context, cgmes.getBasename());
            assertEquals(7.0, p(cgmes), 1e-9);
            // The type was written as a CIM class, not as an object of the model under the document base
            PropertyBags types = cgmes.tripleStore().query("SELECT ?type WHERE { GRAPH ?g { ?s a ?type } "
                    + "FILTER(STRENDS(STR(?s), \"#_EnergyConsumer\")) }");
            assertTrue(types.stream().allMatch(bag -> bag.get("type").startsWith(CgmesNamespace.CIM_100_NAMESPACE)
                    || bag.get("type").startsWith(CgmesNamespace.CIM_16_NAMESPACE)), types.toString());
        } finally {
            cgmes.close();
        }
    }

    @Test
    void anIdentifierThatCannotBeAnIriIsRejected() {
        CgmesModel cgmes = load();
        try {
            DifferenceModel model = diff(
                    List.of(CgmesStatement.literal("Energy Consumer", null, "EnergyConsumer.p", "1")), List.of());
            String basename = cgmes.getBasename();
            CgmesModelException e = assertThrows(CgmesModelException.class,
                () -> CgmesDiffImport.applyToTripleStore(cgmes.tripleStore(), model, basename));
            assertTrue(e.getMessage().contains("illegal in an IRI"), e.getMessage());
        } finally {
            cgmes.close();
        }
    }

    @Test
    void anEmptyDifferenceChangesNothing() {
        CgmesModel cgmes = load();
        try {
            double before = p(cgmes);
            CgmesDiffImport.applyToTripleStore(cgmes.tripleStore(), diff(List.of(), List.of()), cgmes.getBasename());
            assertEquals(before, p(cgmes), 1e-9);
        } finally {
            cgmes.close();
        }
    }
}
