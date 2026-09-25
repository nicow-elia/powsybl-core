/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.conversion;

import com.powsybl.cgmes.model.CgmesModel;
import com.powsybl.cgmes.model.CgmesModelException;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.triplestore.CgmesModelTripleStore;
import com.powsybl.commons.config.PlatformConfig;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import com.powsybl.triplestore.api.PropertyBags;
import com.powsybl.triplestore.api.TripleStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * The second half of a CGMES import: <em>triple store &rarr; IIDM</em>, with nothing of files in sight.
 *
 * <p>A CGMES import is a parse of instance files followed by a conversion of the statements they hold. The parse is
 * {@link com.powsybl.cgmes.model.triplestore.CgmesTripleStoreLoader}; this class is the conversion, cut loose from
 * where the statements came from. Given any triple store that already holds CGMES named graphs &mdash; a local
 * in-memory store filled from an RDF database, or the database itself queried remotely &mdash; it produces the
 * network a file import of the same data would have produced.</p>
 *
 * <p>It is deliberately thin: everything it does goes through {@link CgmesImport}, so the two paths are one code
 * path and cannot drift apart. What it has to work out for itself is the little that a data source would otherwise
 * have told it: the CIM namespace and the base URI of the data, see {@link #describe(TripleStore)}.</p>
 *
 * <p><strong>Cut.</strong> A CGM is split into subnetworks at file level, by the importer, before any triple store
 * exists. This loader therefore always produces <em>one</em> network. To get subnetworks out of a database, load
 * each IGM into its own model set and merge the networks.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class TripleStoreNetworkLoader {

    /**
     * The little a triple store has to be asked before its statements can be converted.
     *
     * @param cimNamespace the CIM namespace of the data, as a data source would have declared it
     * @param baseName     the base URI relative identifiers were resolved against when the data was parsed
     * @param contextNames the names of the contexts (named graphs) the store holds, sorted
     */
    public record StoreContent(String cimNamespace, String baseName, List<String> contextNames) {

        /**
         * @param cimNamespace see {@link #cimNamespace()}
         * @param baseName     see {@link #baseName()}
         * @param contextNames see {@link #contextNames()}
         */
        public StoreContent {
            Objects.requireNonNull(cimNamespace);
            Objects.requireNonNull(baseName);
            contextNames = List.copyOf(contextNames);
        }
    }

    private TripleStoreNetworkLoader() {
    }

    /**
     * Work out the CIM namespace, the base URI and the contexts of a triple store holding CGMES data.
     *
     * <p>Both facts are read off the data with cheap queries rather than guessed: the CIM namespace from the first
     * {@code rdf:type} object in a known CIM namespace, the base URI from the first subject that carries the
     * {@code #_} of a resolved {@code rdf:ID}. Model headers are absolute {@code urn:uuid:} IRIs and are skipped by
     * that filter, which is why it finds the base of the data and not of a header.</p>
     *
     * @param store the store to describe
     * @return what the store holds
     * @throws CgmesModelException if the store holds no CGMES data at all
     */
    public static StoreContent describe(TripleStore store) {
        Objects.requireNonNull(store);
        List<String> contextNames = new ArrayList<>(store.contextNames());
        Collections.sort(contextNames);
        String cimNamespace = findCimNamespace(store, contextNames);
        String baseName = findBaseName(store, contextNames);
        return new StoreContent(cimNamespace, baseName, contextNames);
    }

    /**
     * Convert the CGMES data a triple store holds to a network.
     *
     * @param store          the store holding the CGMES named graphs. It must have been created with
     *                       {@link CgmesImport#tripleStoreOptions(Properties)} of the same parameters
     * @param networkFactory the factory the network is created with
     * @param params         the CGMES import parameters
     * @param reportNode     where the conversion reports
     * @return the network
     */
    public static Network load(TripleStore store, NetworkFactory networkFactory, Properties params, ReportNode reportNode) {
        return load(store, describe(store), networkFactory, params, reportNode);
    }

    /**
     * Convert the CGMES data a triple store holds to a network, with its description already at hand.
     *
     * <p>The overload without a {@link StoreContent} calls {@link #describe(TripleStore)} itself. A caller that
     * filled the store and already knows its CIM namespace and base URI &mdash; a database fetch captures both
     * while parsing &mdash; passes them in and saves the two queries.</p>
     *
     * @param store          the store holding the CGMES named graphs. It must have been created with
     *                       {@link CgmesImport#tripleStoreOptions(Properties)} of the same parameters
     * @param content        what the store holds
     * @param networkFactory the factory the network is created with
     * @param params         the CGMES import parameters
     * @param reportNode     where the conversion reports
     * @return the network
     */
    public static Network load(TripleStore store, StoreContent content, NetworkFactory networkFactory, Properties params, ReportNode reportNode) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(content);
        CgmesImport cgmesImport = importer();
        CgmesModel cgmes = model(store, content, params);
        return cgmesImport.convert(cgmes, content.baseName(), networkFactory, params, reportNode);
    }

    /**
     * Apply the CGMES data a triple store holds as an update of an existing network.
     *
     * <p>The store must hold the update subsets only (typically SSH and SV) &mdash; the update queries of the
     * CGMES catalogs run over the union of all contexts, so an EQ graph left in the same store would be queried
     * too. The model is built with the {@code -update} query catalog, exactly as {@code network.update(dataSource)}
     * does, and the store is <em>not</em> closed: the caller owns it.</p>
     *
     * @param network    the network to update in place
     * @param store      the store holding the update named graphs
     * @param params     the CGMES import parameters
     * @param reportNode where the update reports
     */
    public static void update(Network network, TripleStore store, Properties params, ReportNode reportNode) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(store);
        StoreContent content = describe(store);
        CgmesModel cgmes = new CgmesModelTripleStore(content.cimNamespace(), store, Conversion.QUERY_CATALOG_NAME_UPDATE);
        cgmes.setBasename(content.baseName());
        importer().update(network, cgmes, params, reportNode);
    }

    /**
     * A CGMES model view of a triple store, ready to be converted.
     *
     * <p>Handed out so that a caller can run something else than a plain conversion on the same seam &mdash; a
     * difference model applier, a metadata inspection &mdash; without rebuilding the query catalog wiring.</p>
     *
     * @param store   the store holding the CGMES named graphs
     * @param content what the store holds
     * @param params  the CGMES import parameters, for the query catalog carried by the store options
     * @return the CGMES model. Closing it closes the store
     */
    public static CgmesModel model(TripleStore store, StoreContent content, Properties params) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(content);
        String queryCatalog = store.getOptions() == null ? "" : store.getOptions().queryCatalog();
        CgmesModel cgmes = new CgmesModelTripleStore(content.cimNamespace(), store, queryCatalog);
        cgmes.setBasename(content.baseName());
        return cgmes;
    }

    /**
     * The CGMES importer the loader converts through.
     *
     * <p>Built on the default platform configuration, so that platform defaults of the CGMES import parameters
     * reach a database load exactly as they reach a file import.</p>
     *
     * @return the importer
     */
    public static CgmesImport importer() {
        return new CgmesImport(PlatformConfig.defaultConfig());
    }

    private static String findCimNamespace(TripleStore store, List<String> contextNames) {
        // The two namespaces powsybl supports, asked for one by one: a single LIMIT 1 query over every
        // "http://iec.ch/TC57/" type would be cheaper still, but it can answer with the CIM100-European
        // namespace, which is not a CIM namespace, and nothing says which type a store returns first.
        for (String candidate : List.of(CgmesNamespace.CIM_16_NAMESPACE, CgmesNamespace.CIM_100_NAMESPACE)) {
            if (!store.query(typeInNamespaceQuery(candidate)).isEmpty()) {
                return candidate;
            }
        }
        // A CIM version beyond the two known ones: now it is worth listing the types that are there.
        PropertyBags types = store.query(CIM_TYPES_QUERY);
        return types.stream()
                .map(bag -> bag.get("type"))
                .filter(Objects::nonNull)
                .map(TripleStoreNetworkLoader::namespaceOf)
                .filter(CgmesNamespace::isValid)
                .sorted()
                .findFirst()
                .orElseThrow(() -> new CgmesModelException("No CGMES data in triple store (contexts: " + contextNames + ")"));
    }

    private static String namespaceOf(String type) {
        int hash = type.indexOf('#');
        return hash < 0 ? type : type.substring(0, hash + 1);
    }

    private static String typeInNamespaceQuery(String namespace) {
        return "SELECT ?subject WHERE { GRAPH ?graph { ?subject a ?type } FILTER(STRSTARTS(STR(?type), \""
                + namespace + "\")) } LIMIT 1";
    }

    private static String findBaseName(TripleStore store, List<String> contextNames) {
        PropertyBags subjects = store.query(BASE_NAME_QUERY);
        String subject = subjects.stream()
                .map(bag -> bag.get("subject"))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new CgmesModelException(
                        "No CGMES identifiers in triple store, cannot determine the base URI (contexts: " + contextNames + ")"));
        // rdf4j resolves rdf:ID="_x" against <base> to <base>/#_x; other parsers yield <base>#_x
        int hash = subject.indexOf("#_");
        String base = subject.substring(0, hash);
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static final String CIM_TYPES_QUERY = """
            SELECT DISTINCT ?type WHERE { GRAPH ?graph { ?subject a ?type }
            FILTER(STRSTARTS(STR(?type), "http://iec.ch/TC57/")
                && !STRSTARTS(STR(?type), "http://iec.ch/TC57/61970-552/")) }
            """;

    private static final String BASE_NAME_QUERY = """
            SELECT ?subject WHERE { GRAPH ?graph { ?subject a ?type }
            FILTER(CONTAINS(STR(?subject), "#_")) } LIMIT 1
            """;
}
