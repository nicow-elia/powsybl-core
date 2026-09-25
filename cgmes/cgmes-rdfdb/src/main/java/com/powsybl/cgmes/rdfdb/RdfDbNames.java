/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.commons.PowsyblException;
import com.powsybl.triplestore.impl.rdf4j.sparql.ScenarioGraphNames;

/**
 * The IRIs of the versioning layer: one metadata graph and two data graphs per difference, all per scenario.
 *
 * <p>A scenario owns exactly one mutable graph, its <em>metadata graph</em>, and that graph is the index of
 * everything else: a client never computes the IRI of a data graph, it reads it from a model node. That is what
 * makes the naming here an implementation detail the versioning layer could change without touching a
 * caller.</p>
 *
 * <p>The scheme is {@code http://powsybl.org/rdfdb/<scenario>/…} rather than the {@code urn:powsybl:rdfdb:…} of
 * the original design. Two reasons. Apache Jena validates URNs against RFC 8141 and {@code urn:uuid:} against RFC
 * 4122, and a CGMES model identifier is not always a UUID, so {@code urn:uuid:<not a uuid>/forward} is rejected by
 * the server rather than by us. And an http hierarchy gives each scenario one namespace, which makes "everything
 * of this scenario" a prefix question on every backend.</p>
 *
 * <p>Full-model graphs keep the naming of the loading layer ({@code contexts:<scenario>/<file name>}): they are
 * written by the upload, which knows nothing about versions, and they are referenced from the metadata graph like
 * every other graph.</p>
 *
 * <p>Scenario names are validated and encoded by {@link ScenarioGraphNames}, the single place in this code base
 * that decides what a scenario name may look like and how it becomes an IRI segment.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class RdfDbNames {

    /** The root every IRI this layer mints starts with. */
    public static final String BASE = "http://powsybl.org/rdfdb/";

    /** The last segment of a metadata graph IRI. */
    private static final String META = "/meta";

    private static final String GRAPH_SEGMENT = "/graph/";

    private static final String SNAPSHOT_SEGMENT = "/snapshot/";

    private RdfDbNames() {
    }

    /**
     * Check that a scenario name can be used, and return it unchanged.
     *
     * <p>Every public entry point of the versioning layer calls this before anything else, because a scenario is
     * not a defaultable argument: it says which base grid model the caller means, and guessing it would silently
     * write a difference into the wrong day.</p>
     *
     * @param scenario the scenario name
     * @return the scenario name
     * @throws RdfDbException if the name is {@code null}, blank or otherwise unusable
     */
    public static String checkScenario(String scenario) {
        if (scenario == null || scenario.isBlank()) {
            throw new RdfDbException("scenario must not be blank");
        }
        try {
            return ScenarioGraphNames.requireValidScenario(scenario);
        } catch (PowsyblException e) {
            throw new RdfDbException(e.getMessage(), e);
        }
    }

    /**
     * The IRI segment a scenario name becomes.
     *
     * @param scenario the raw scenario name
     * @return the percent-encoded name
     */
    public static String safe(String scenario) {
        return ScenarioGraphNames.encode(checkScenario(scenario));
    }

    /**
     * The raw name behind an IRI segment.
     *
     * @param segment the percent-encoded segment
     * @return the raw scenario name
     */
    public static String unsafe(String segment) {
        return ScenarioGraphNames.decode(segment);
    }

    /**
     * The metadata graph of a scenario, the only graph of this layer that is ever rewritten.
     *
     * @param scenario the scenario
     * @return the graph IRI
     */
    public static String metaGraph(String scenario) {
        return BASE + safe(scenario) + META;
    }

    /**
     * The prefix every data graph of a scenario shares.
     *
     * @param scenario the scenario
     * @return the IRI prefix
     */
    public static String diffGraphPrefix(String scenario) {
        return BASE + safe(scenario) + GRAPH_SEGMENT;
    }

    /**
     * The graph holding the forward statements of a difference, that is the state after the change.
     *
     * @param scenario the scenario
     * @param modelId  the identifier of the difference model
     * @return the graph IRI
     */
    public static String forwardGraph(String scenario, String modelId) {
        return diffGraphPrefix(scenario) + ScenarioGraphNames.encode(modelId) + "/forward";
    }

    /**
     * The graph holding the reverse statements of a difference, that is the state before the change.
     *
     * @param scenario the scenario
     * @param modelId  the identifier of the difference model
     * @return the graph IRI
     */
    public static String reverseGraph(String scenario, String modelId) {
        return diffGraphPrefix(scenario) + ScenarioGraphNames.encode(modelId) + "/reverse";
    }

    /**
     * The node holding the base timestep and the base offset of a scenario, one per scenario.
     *
     * @param scenario the scenario
     * @return the node IRI
     */
    public static String catalogNode(String scenario) {
        return BASE + safe(scenario) + "/catalog";
    }

    /**
     * The IRI of a snapshot, which is what {@code (scenario, timestep, version)} addresses.
     *
     * @param scenario the scenario
     * @param timestep the canonical ISO instant of the timestep
     * @param version  the version label
     * @return the snapshot IRI
     */
    public static String snapshot(String scenario, String timestep, String version) {
        return BASE + safe(scenario) + SNAPSHOT_SEGMENT + ScenarioGraphNames.encode(timestep) + "/"
                + ScenarioGraphNames.encode(version);
    }

    /**
     * The immutable graph a versioned full-model upload writes.
     *
     * <p>Scenario-scoped, so the same instance file may be the root of several scenarios without the two uploads
     * sharing a graph: a graph of this layer is written once and never again, and two scenarios are independent.</p>
     *
     * @param scenario the scenario
     * @param modelId  the identifier of the model the file carries
     * @return the graph IRI
     */
    public static String fullGraph(String scenario, String modelId) {
        return diffGraphPrefix(scenario) + ScenarioGraphNames.encode(modelId);
    }

    /**
     * The node a checkpoint writes for the materialised state of one profile at one snapshot.
     *
     * @param scenario the scenario
     * @param timestep the canonical timestep of the snapshot
     * @param version  the version of the snapshot
     * @param subset   the CGMES profile identifier, for instance {@code SSH}
     * @return the node IRI; its graph is this IRI plus {@code /graph}
     */
    public static String materialized(String scenario, String timestep, String version, String subset) {
        return BASE + safe(scenario) + "/materialized/" + ScenarioGraphNames.encode(timestep) + "/"
                + ScenarioGraphNames.encode(version) + "/" + subset;
    }

    /**
     * The prefix every IRI this layer mints for a scenario starts with.
     *
     * <p>It ends with a slash, so that scenario {@code "a"} never matches {@code "ab"}.</p>
     *
     * @param scenario the scenario
     * @return the prefix
     */
    public static String scenarioPrefix(String scenario) {
        return BASE + safe(scenario) + "/";
    }

    /**
     * The scenario an IRI of this layer belongs to.
     *
     * <p>This is what lets the planner refuse a snapshot of another scenario <em>without asking the database</em>:
     * a snapshot IRI carries its scenario, so "can I reach it by differences?" is answered by string arithmetic
     * when the two sides do not even belong together.</p>
     *
     * @param iri any IRI under {@value #BASE}
     * @return the raw scenario name, or {@code null} when the IRI does not belong to this layer
     */
    public static String scenarioOf(String iri) {
        if (iri == null || !iri.startsWith(BASE)) {
            return null;
        }
        int end = iri.indexOf('/', BASE.length());
        if (end < 0) {
            return null;
        }
        return unsafe(iri.substring(BASE.length(), end));
    }

    /**
     * The address a snapshot IRI encodes.
     *
     * <p>The inverse of {@link #snapshot(String, String, String)}. It exists because the identity a network carries
     * is the snapshot <em>IRI</em>, while what a user wants to see &mdash; and what a variant binding shows &mdash;
     * is the address: which version of which timestep. Reading it off the IRI costs nothing and asks no database.</p>
     *
     * @param snapshotIri the IRI of a snapshot
     * @return the address, or {@code null} when the IRI is not a snapshot of this layer
     */
    public static SnapshotRef refOf(String snapshotIri) {
        String scenario = scenarioOf(snapshotIri);
        if (scenario == null) {
            return null;
        }
        int segment = snapshotIri.indexOf(SNAPSHOT_SEGMENT);
        if (segment < 0) {
            return null;
        }
        String rest = snapshotIri.substring(segment + SNAPSHOT_SEGMENT.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return null;
        }
        return new SnapshotRef(scenario, ScenarioGraphNames.decode(rest.substring(slash + 1)),
                ScenarioGraphNames.decode(rest.substring(0, slash)));
    }

    /**
     * Whether an IRI is the metadata graph of some scenario.
     *
     * @param graphIri the graph IRI
     * @return whether it is a metadata graph
     */
    public static boolean isMetaGraph(String graphIri) {
        return graphIri != null && graphIri.startsWith(BASE) && graphIri.endsWith(META)
                && graphIri.length() > BASE.length() + META.length();
    }

    /**
     * The scenario a metadata graph belongs to.
     *
     * @param graphIri the graph IRI
     * @return the raw scenario name, or {@code null} when the IRI is not a metadata graph
     */
    public static String scenarioOfMetaGraph(String graphIri) {
        if (!isMetaGraph(graphIri)) {
            return null;
        }
        return unsafe(graphIri.substring(BASE.length(), graphIri.length() - META.length()));
    }

    /**
     * Whether a graph IRI belongs to this layer, and can therefore be trusted never to change under its name.
     *
     * <p>Data graphs of a difference are written once and never rewritten (a new state is a new difference with a
     * new identifier), which is exactly the promise {@code GraphCache.trustImmutableGraphs} needs.</p>
     *
     * @param graphIri the graph IRI
     * @return whether the graph is immutable
     */
    public static boolean isImmutableGraph(String graphIri) {
        return graphIri != null && graphIri.startsWith(BASE)
                && (graphIri.contains(GRAPH_SEGMENT) || graphIri.contains("/materialized/"));
    }
}
