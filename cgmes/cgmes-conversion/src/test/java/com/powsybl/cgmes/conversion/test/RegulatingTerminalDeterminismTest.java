/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conformity.Cgmes3Catalog;
import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.TripleStoreNetworkLoader;
import com.powsybl.cgmes.model.triplestore.CgmesTripleStoreLoader;
import com.powsybl.commons.config.PlatformConfig;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.triplestore.impl.rdf4j.TripleStoreRDF4J;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Which terminal a regulating control ends up pointing at must not depend on the order the statements arrive in.
 *
 * <p>A CGMES regulating control names a CGMES terminal, and the conversion maps it to <em>one</em> IIDM terminal
 * of the topological node that terminal belongs to. That node can carry a dozen pieces of equipment, several of
 * them behind open switches, and the pick used to be the first one the equipment creation order happened to
 * offer &mdash; which follows the order the SPARQL queries return their results, which follows the order the
 * statements went into the triple store. Nothing in the data says what that order is: a store filled by the
 * RDF/XML parser and the same store filled from a graph database disagree about it, and the two networks then
 * disagree about whether a shunt regulation has a bus at all &mdash; which decides whether a load flow sees the
 * regulation.</p>
 *
 * <p>The choice is now deterministic: inside each preference group of
 * {@code RegulatingTerminalMapper} a connected terminal comes first, and among equals the lowest identifier.
 * This test builds two stores holding exactly the same statements in opposite order and requires the two
 * networks to name the same regulating terminals.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RegulatingTerminalDeterminismTest {

    @Test
    void svedalaIsIndifferentToTheOrderOfItsStatements() {
        assertSameRegulatingTerminals(Cgmes3Catalog.svedala().dataSource());
    }

    @Test
    void smallNodeBreakerIsIndifferentToTheOrderOfItsStatements() {
        assertSameRegulatingTerminals(CgmesConformity1Catalog.smallNodeBreaker().dataSource());
    }

    @Test
    void microGridIsIndifferentToTheOrderOfItsStatements() {
        assertSameRegulatingTerminals(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource());
    }

    private static void assertSameRegulatingTerminals(ReadOnlyDataSource ds) {
        Properties p = new Properties();
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        CgmesImport importer = new CgmesImport(PlatformConfig.defaultConfig());

        TripleStoreRDF4J asRead = store(importer, p);
        TripleStoreRDF4J reversed = store(importer, p);
        try {
            CgmesTripleStoreLoader.load(ds, null, asRead, ReportNode.NO_OP);
            copyReversed(asRead, reversed);

            List<String> straight = regulatingTerminals(
                    TripleStoreNetworkLoader.load(asRead, NetworkFactory.findDefault(), p, ReportNode.NO_OP));
            List<String> backwards = regulatingTerminals(
                    TripleStoreNetworkLoader.load(reversed, NetworkFactory.findDefault(), p, ReportNode.NO_OP));

            assertFalse(straight.isEmpty(), "the fixture must have regulating controls at all");
            assertEquals(straight, backwards);
        } finally {
            asRead.close();
            reversed.close();
        }
    }

    private static TripleStoreRDF4J store(CgmesImport importer, Properties p) {
        return new TripleStoreRDF4J(new SailRepository(new MemoryStore()), importer.tripleStoreOptions(p));
    }

    /**
     * Copy every statement of every context, each context written back to front.
     *
     * <p>An RDF4J memory store returns the statements of a context in the order they were added, so this is the
     * cheapest way to get a store that holds exactly the same data and answers every query in another order.</p>
     */
    private static void copyReversed(TripleStoreRDF4J from, TripleStoreRDF4J to) {
        try (RepositoryConnection source = from.getRepository().getConnection();
             RepositoryConnection target = to.getRepository().getConnection()) {
            target.setIsolationLevel(IsolationLevels.NONE);
            target.begin();
            for (Resource context : source.getContextIDs().stream().toList()) {
                List<Statement> statements = new ArrayList<>();
                source.getStatements(null, null, null, context).forEach(statements::add);
                Collections.reverse(statements);
                target.add(statements, context);
            }
            target.commit();
        }
        from.getNamespaces().forEach(ns -> to.addNamespace(ns.getPrefix(), ns.getNamespace()));
    }

    private static List<String> regulatingTerminals(Network network) {
        List<String> terminals = new ArrayList<>();
        network.getShuntCompensatorStream().forEach(sc -> add(terminals, sc.getId(), sc.getRegulatingTerminal()));
        network.getGeneratorStream().forEach(g -> add(terminals, g.getId(), g.getRegulatingTerminal()));
        network.getStaticVarCompensatorStream().forEach(svc ->
                add(terminals, svc.getId(), svc.getRegulatingTerminal()));
        network.getTwoWindingsTransformerStream().filter(t -> t.hasRatioTapChanger()).forEach(t ->
                add(terminals, t.getId() + "/rtc", t.getRatioTapChanger().getRegulationTerminal()));
        terminals.sort(Comparator.naturalOrder());
        return terminals;
    }

    private static void add(List<String> terminals, String id, Terminal regulating) {
        terminals.add(id + " -> " + (regulating == null ? "none"
                : regulating.getConnectable().getId() + "/connected=" + regulating.isConnected()));
    }
}
