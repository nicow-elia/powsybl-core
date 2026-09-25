/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fold the differences of a snapshot into full graphs, on the database, once.
 *
 * <h2>Onto the snapshot, not into a new root</h2>
 * <p>A checkpoint adds full graphs to a snapshot that already exists; it does not create a new root. The chain
 * therefore stays connected &mdash; every earlier version is still reachable, the lowest-common-ancestor logic is
 * unchanged &mdash; and what a materialisation gains is that it now starts at the <em>nearest</em> ancestor
 * holding a full graph of each profile instead of walking back to the instance files. Depth counts from the root
 * and never resets.</p>
 *
 * <h2>What it costs, and when to run it</h2>
 * <p>It copies one graph per profile the chain touched and applies the differences to the copies with the same
 * three replace operations the client-side application uses, so it costs about what one materialisation costs
 * &mdash; paid once, on the server, instead of on every client. {@link UpdatePlan#checkpointRecommended()} says
 * when that has become worthwhile; the rule of thumb is once per hundred versions, or once per timestep root.</p>
 *
 * <p>It is idempotent: a snapshot that already has full graphs is returned unchanged. It is also not on any hot
 * path, which is why it is a utility rather than something a load does by itself.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class Checkpoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(Checkpoint.class);

    private Checkpoint() {
    }

    /**
     * Materialise the profiles a snapshot reaches by differences into full graphs of its own.
     *
     * @param db  the open connection
     * @param ref the address of the snapshot
     * @return the snapshot, now with full graphs
     * @throws RdfDbException if the scenario holds no such snapshot
     */
    public static SnapshotInfo create(RdfDbConnection db, SnapshotRef ref) {
        Objects.requireNonNull(db);
        Objects.requireNonNull(ref);
        SnapshotCatalog catalog = db.snapshots(ref.scenario());
        SnapshotInfo info = catalog.find(catalog.check(ref)).orElseThrow(() -> new RdfDbException("scenario '"
                + ref.scenario() + "' holds no snapshot " + ref));
        if (info.hasFull()) {
            return info;
        }
        MaterializationPlan plan = db.versionGraph(ref.scenario()).materialization(info.iri());
        Map<CgmesSubset, List<UpdatePlan.DiffStep>> steps = new EnumMap<>(CgmesSubset.class);
        plan.steps().forEach(step -> steps.computeIfAbsent(step.model().subset(), k -> new ArrayList<>())
                .add(step));
        if (steps.isEmpty()) {
            return info;
        }
        Map<CgmesSubset, String> nodes = new LinkedHashMap<>();
        steps.forEach((subset, path) -> nodes.put(subset,
                RdfDbNames.materialized(ref.scenario(), info.timestep(), info.version(), subset.getIdentifier())));

        // The data first, the metadata last: a graph no node refers to is invisible, so a failure half way leaves
        // the snapshot exactly as it was
        steps.forEach((subset, path) -> materialize(db, ref.scenario(), plan.startModel().get(subset).graph(),
                nodes.get(subset) + "/graph", path, plan.targetState().get(subset)));
        db.sparql(ref.scenario()).update(metadata(ref.scenario(), info, plan, nodes));

        SnapshotInfo updated = catalog.info(info.iri()).orElseThrow(() -> new RdfDbException(
                "the checkpoint of " + info + " was not written"));
        if (!updated.hasFull()) {
            throw new RdfDbException("the checkpoint of " + info + " was not written");
        }
        LOGGER.info("Checkpointed snapshot {} of scenario '{}': {} profile(s) materialised", info,
                ref.scenario(), nodes.size());
        return updated;
    }

    /**
     * Whether a snapshot is far enough from the nearest full one to be worth checkpointing.
     *
     * @param plan    a plan that reached the snapshot
     * @param options the thresholds
     * @return whether a checkpoint is recommended
     */
    public static boolean recommended(UpdatePlan plan, RdfDbUpdateOptions options) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(options);
        // Recomputed rather than read off the plan: the plan was made with the options of whoever made it, and a
        // caller asking this question is asking it with its own threshold
        return plan.distanceToFullSnapshot() > options.getCheckpointAfter();
    }

    // ------------------------------------------------------------------ the data operations

    private static void materialize(RdfDbConnection db, String scenario, String source, String target,
                                    List<UpdatePlan.DiffStep> steps, String stateId) {
        copy(db, scenario, source, target);
        StringBuilder update = new StringBuilder(RdfDbVocabulary.PREFIXES);
        for (UpdatePlan.DiffStep step : steps) {
            appendApply(update, target, step.model());
        }
        appendHeaderRewrite(update, scenario, target, stateId);
        db.sparql(scenario).update(update.toString());
    }

    /**
     * Copy a full graph so the differences can be folded into the copy.
     *
     * <p>{@code COPY} where the source can be named in SPARQL, and a statement copy through the RDF4J API where it
     * cannot &mdash; the in-process backend keeps the graphs of an unversioned scenario under the plain instance
     * file name, and a CGMES file name may hold a space, which no SPARQL IRI reference can.</p>
     */
    private static void copy(RdfDbConnection db, String scenario, String source, String target) {
        if (SparqlText.isWritableIri(source)) {
            db.sparql(scenario).update("DROP SILENT GRAPH " + SparqlText.iri(target) + " ; COPY "
                    + SparqlText.iri(source) + " TO " + SparqlText.iri(target));
            return;
        }
        Repository repository = db.repository(scenario, true);
        List<Statement> statements;
        try (RepositoryConnection conn = repository.getConnection()) {
            statements = new ArrayList<>(conn.getStatements(null, null, null,
                    conn.getValueFactory().createIRI(source)).stream().toList());
        }
        db.writeGraph(scenario, target, statements);
    }

    /**
     * The three operations that apply one difference to a graph, with the replace semantics of this project: set
     * every forward key, remove the keys only the reverse side states, and remove whole objects whose type is only
     * on the reverse side.
     */
    private static void appendApply(StringBuilder update, String graph, StoredModel diff) {
        String m = SparqlText.iri(graph);
        String fwd = SparqlText.iri(diff.forwardGraph());
        String rev = SparqlText.iri(diff.reverseGraph());
        if (update.length() > RdfDbVocabulary.PREFIXES.length()) {
            update.append(" ; ");
        }
        update.append("DELETE { GRAPH ").append(m).append(" { ?s ?p ?old } } INSERT { GRAPH ").append(m)
                .append(" { ?s ?p ?o } } WHERE { GRAPH ").append(fwd)
                .append(" { ?s ?p ?o } OPTIONAL { GRAPH ").append(m).append(" { ?s ?p ?old } } } ; ")
                .append("DELETE { GRAPH ").append(m).append(" { ?s ?p ?o } } WHERE { GRAPH ").append(rev)
                .append(" { ?s ?p ?x } FILTER NOT EXISTS { GRAPH ").append(fwd)
                .append(" { ?s ?p ?y } } GRAPH ").append(m).append(" { ?s ?p ?o } } ; ")
                .append("DELETE { GRAPH ").append(m).append(" { ?s ?p2 ?o2 } } WHERE { GRAPH ").append(rev)
                .append(" { ?s rdf:type ?t } FILTER NOT EXISTS { GRAPH ").append(fwd)
                .append(" { ?s rdf:type ?t2 } } GRAPH ").append(m).append(" { ?s ?p2 ?o2 } }");
    }

    /**
     * Rewrite the {@code md:FullModel} header of the copy to the state model it now holds, so that a client that
     * never reads the metadata graph &mdash; a non-powsybl one, or this library's own plain loader &mdash; still
     * sees the right identity.
     */
    private static void appendHeaderRewrite(StringBuilder update, String scenario, String graph, String stateId) {
        String m = SparqlText.iri(graph);
        String meta = SparqlText.iri(RdfDbNames.metaGraph(scenario));
        update.append(" ; DELETE { GRAPH ").append(m).append(" { ?h ?p ?o } } WHERE { GRAPH ").append(m)
                .append(" { ?h a md:FullModel ; ?p ?o } } ; ")
                .append("INSERT { GRAPH ").append(m).append(" { ").append(SparqlText.iri(stateId))
                .append(" a md:FullModel ; ?p ?o } } WHERE { GRAPH ").append(meta).append(" { ")
                .append(SparqlText.iri(stateId)).append(" ?p ?o . FILTER(STRSTARTS(STR(?p), ")
                .append(SparqlText.str(RdfDbVocabulary.MD_NS)).append(")) } }");
    }

    // ------------------------------------------------------------------ the metadata operation

    private static String metadata(String scenario, SnapshotInfo info, MaterializationPlan plan,
                                   Map<CgmesSubset, String> nodes) {
        String meta = SparqlText.iri(RdfDbNames.metaGraph(scenario));
        StringBuilder update = new StringBuilder(RdfDbVocabulary.PREFIXES)
                .append("INSERT DATA { GRAPH ").append(meta).append(" { ");
        nodes.forEach((subset, node) -> update.append(SparqlText.iri(node)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.RDF_TYPE)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.MATERIALIZED)).append(" ; ")
                // Its own kind, not pdb:Full: a materialised copy is a snapshot's private start graph, and the
                // catalogue of stored models must not count it as a second full model of that profile
                .append(SparqlText.iri(RdfDbVocabulary.KIND)).append(' ')
                .append(SparqlText.iri(RdfDbVocabulary.MATERIALIZED)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SCENARIO)).append(' ')
                .append(SparqlText.str(scenario)).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SUBSET)).append(' ')
                .append(SparqlText.str(subset.getIdentifier())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.OF_MODEL)).append(' ')
                .append(SparqlText.iri(plan.targetState().get(subset))).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.SNAPSHOT)).append(' ')
                .append(SparqlText.iri(info.iri())).append(" ; ")
                .append(SparqlText.iri(RdfDbVocabulary.GRAPH)).append(' ')
                .append(SparqlText.str(node + "/graph")).append(" . "));
        // The profiles the chain did not touch keep the full model they inherited, so that a materialisation that
        // starts here finds one graph per profile and never has to walk further up. These links are the whole
        // statement "this snapshot can start a materialisation": there is no boolean beside them to keep in step,
        // which is why this update is a single INSERT and not an insert-true/delete-false pair
        StringBuilder links = new StringBuilder();
        plan.startModel().forEach((subset, source) -> {
            String id = nodes.containsKey(subset) ? nodes.get(subset) : source.modelId();
            links.append(links.isEmpty() ? "" : " ; ").append(SparqlText.iri(RdfDbVocabulary.FULL_MODELS))
                    .append(' ').append(SparqlText.iri(id));
        });
        if (!links.isEmpty()) {
            update.append(SparqlText.iri(info.iri())).append(' ').append(links).append(" . ");
        }
        return update.append("} }").toString();
    }
}
