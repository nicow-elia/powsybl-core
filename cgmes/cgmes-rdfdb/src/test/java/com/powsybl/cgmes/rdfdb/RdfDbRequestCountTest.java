/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How many requests each flow of the versioning layer sends, counted rather than asserted in prose.
 *
 * <p>Every design decision of this work package is about round trips: the difference write is one guarded update
 * because the rules have to be atomic with it, the update is a plan and a fetch because a chain is read in one
 * query, the materialisation reads the metadata graph once because every question it has is a question about the
 * same nodes. Those claims are cheap to make and easy to lose &mdash; a convenience call added inside a loop costs
 * nothing on the in-process backend and doubles the latency against a server &mdash; so they are counted here,
 * from the request log of the embedded server, and the bounds are asserted.</p>
 *
 * <p>The bounds are the design plus a little slack, not the measured number exactly: this is a guard against a
 * regression by an order of magnitude, not a golden file.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbRequestCountTest {

    /** What the Fuseki request log writes per HTTP request. */
    private static final Pattern REQUEST = Pattern.compile("^\\[\\d+] (GET|POST|PUT|DELETE|HEAD) .*");

    private static final String S = "2016-01-01";

    private static final AtomicInteger REQUESTS = new AtomicInteger();

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(RdfDbRequestCountTest.class);

    private static Properties params() {
        Properties p = new Properties();
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        return p;
    }

    private static int since(int mark) {
        return REQUESTS.get() - mark;
    }

    @Test
    void theFlowsSendTheRequestsTheDesignSays() {
        Logger fusekiLog = (Logger) LoggerFactory.getLogger("org.apache.jena.fuseki.Fuseki");
        Level previous = fusekiLog.getLevel();
        fusekiLog.setLevel(Level.INFO);
        AppenderBase<ILoggingEvent> counter = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (REQUEST.matcher(event.getFormattedMessage()).matches()) {
                    REQUESTS.incrementAndGet();
                }
            }
        };
        counter.start();
        fusekiLog.addAppender(counter);
        try (EmbeddedFuseki fuseki = EmbeddedFuseki.inMemory();
             RdfDbConnection db = RdfDbConnection.open(fuseki.database())) {
            db.clear(S);
            db.loadCgmes(S, CgmesConformity1Catalog.microGridBaseCaseBE().dataSource(), null, params(),
                    ReportNode.NO_OP);
            Network sender = RdfDbNetworkLoader.load(db, S, null, params(), ReportNode.NO_OP);
            Network receiver = RdfDbNetworkLoader.load(db, S, null, params(), ReportNode.NO_OP);
            String line = sender.getLineStream().map(Line::getId).sorted().findFirst().orElseThrow();

            // One difference of one profile: check, write, read back
            int mark = REQUESTS.get();
            RdfDbExport.export(sender, Changes.record(sender, n -> Changes.moveLoad(n, 3.0)), db, S,
                    new CgmesDiffExport.ExportOptions());
            int oneDiff = since(mark);
            assertAtMost("exporting one difference", oneDiff, 3);

            // A set of two profiles is the same three requests: what grows is the size of each, not their number
            mark = REQUESTS.get();
            RdfDbExport.export(sender, Changes.record(sender, n -> {
                Changes.moveLoad(n, 2.0);
                n.getLine(line).setR(n.getLine(line).getR() + 0.1);
            }), db, S, new CgmesDiffExport.ExportOptions());
            int twoProfiles = since(mark);
            assertAtMost("exporting a set of two profiles", twoProfiles, 3);

            // An update is the catalogue and the statements of the chain
            mark = REQUESTS.get();
            UpdateResult update = RdfDbNetworkLoader.update(receiver, db, S, DiffTarget.head(),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP);
            int updateRequests = since(mark);
            assertTrue(update.route() == UpdateResult.Route.DIFF_APPLIED, update.reasons().toString());
            assertAtMost("an update over a chain of " + update.diffCount(), updateRequests, 2);

            // A materialisation is the catalogue, the statements of the chain and one transfer per instance file
            int graphs = db.contextNames(S).size();
            mark = REQUESTS.get();
            RdfDbNetworkLoader.load(db, S, DiffTarget.head(), null, params(), ReportNode.NO_OP);
            int materialise = since(mark);
            assertAtMost("materialising the head of a chain (" + graphs + " instance files)", materialise,
                    graphs + 2);

            // Reading the catalogue is one request, whatever is asked of it afterwards
            mark = REQUESTS.get();
            CatalogSnapshot snapshot = db.catalog(S).snapshot();
            List<StoredModel> chain = snapshot.chainDown(snapshot.head(CgmesSubset.STEADY_STATE_HYPOTHESIS)
                    .orElseThrow().id());
            snapshot.full(CgmesSubset.EQUIPMENT).orElseThrow();
            snapshot.heads();
            assertAtMost("reading the catalogue and asking it " + chain.size() + " questions", since(mark), 1);

            versionedFlows(db);
        } finally {
            fusekiLog.detachAppender(counter);
            fusekiLog.setLevel(previous);
        }
    }

    /**
     * The same discipline for the snapshot flows.
     *
     * <p>They cost a little more than the unversioned ones, and the extra requests are named rather than left to
     * be discovered: an address has to be resolved before a plan can be sent, and the plan returns the shape of a
     * path whose models are then read in one {@code VALUES} query. What matters is that none of them is
     * <em>per difference</em> or <em>per profile</em>.</p>
     */
    private static void versionedFlows(RdfDbConnection db) {
        String scenario = "versioned";
        db.clear(scenario);
        SnapshotCatalog catalog = db.snapshots(scenario);
        int graphs = 9;

        int mark = REQUESTS.get();
        catalog.putFull(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource(), null,
                SnapshotRef.of(scenario, "1.0"), params(), ReportNode.NO_OP);
        // The uploads, plus the checks before them and the guarded write and read-back after them
        assertAtMost("writing a root snapshot of " + graphs + " instance files", since(mark), graphs + 5);

        int load = REQUESTS.get();
        Network sender = RdfDbNetworkLoader.load(db, SnapshotRef.of(scenario, "1.0"), null, params(),
                ReportNode.NO_OP);
        assertAtMost("loading a root snapshot (" + graphs + " graphs)", since(load), graphs + 5);

        mark = REQUESTS.get();
        RdfDbExport.export(sender, Changes.record(sender, n -> Changes.moveLoad(n, 4.0)), db,
                SnapshotRef.of(scenario, "1.1"), new CgmesDiffExport.ExportOptions());
        // The three of the sink, plus resolving the head, refusing a duplicate version and reading the node back
        assertAtMost("writing a version as a snapshot", since(mark), 7);

        Network receiver = RdfDbNetworkLoader.load(db, SnapshotRef.of(scenario, "1.0"), null, params(),
                ReportNode.NO_OP);
        mark = REQUESTS.get();
        UpdateResult result = RdfDbNetworkLoader.update(receiver, db, SnapshotRef.of(scenario, "1.1"),
                new RdfDbUpdateOptions(), params(), ReportNode.NO_OP);
        assertTrue(result.route() == UpdateResult.Route.DIFF_APPLIED, result.reasons().toString());
        // The plan, the models of the path and the statements of the differences: three, whatever the chain length
        assertAtMost("an update to a snapshot over " + result.diffCount() + " difference(s)", since(mark), 3);

        String from = catalog.find(SnapshotRef.of(scenario, "1.0")).orElseThrow().iri();
        mark = REQUESTS.get();
        db.versionGraph(scenario).plan(from, SnapshotRef.of(scenario, "1.1"), new RdfDbUpdateOptions());
        // One request: the rows that say which differences lie on the path also say what they are
        assertAtMost("planning a path", since(mark), 1);

        mark = REQUESTS.get();
        catalog.snapshots();
        // The listing of a whole scenario, including what its members say about the fast route and where a
        // materialisation may start: still one request, because both are read off the same rows
        assertAtMost("listing every snapshot of a scenario", since(mark), 1);

        mark = REQUESTS.get();
        catalog.verify();
        // And checking the invariants is that listing and nothing else: the tree is derived when it is written,
        // so there is no second source of truth left to compare it against
        assertAtMost("verifying the snapshot tree", since(mark), 1);

        // Backwards: the models the path ends at are ancestors, not steps, so their headers are read as well
        Network backward = RdfDbNetworkLoader.load(db, SnapshotRef.of(scenario, "1.1"), null, params(),
                ReportNode.NO_OP);
        mark = REQUESTS.get();
        UpdateResult undone = RdfDbNetworkLoader.update(backward, db, SnapshotRef.of(scenario, "1.0"),
                new RdfDbUpdateOptions(), params(), ReportNode.NO_OP);
        assertTrue(undone.route() == UpdateResult.Route.DIFF_APPLIED, undone.reasons().toString());
        assertAtMost("an update backwards over " + undone.diffCount() + " difference(s)", since(mark), 3);

        // A network read from files carries no provenance, so the planner has to find it by its model identifiers
        Network fromFiles = Network.read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource(), params());
        mark = REQUESTS.get();
        UpdatePlan identified = db.versionGraph(scenario).plan(fromFiles, SnapshotRef.of(scenario, "1.1"),
                new RdfDbUpdateOptions());
        assertTrue(identified.kind() == UpdatePlan.Kind.DIFF, identified.reasons().toString());
        // The identity match, the snapshot it found, and the plan itself
        assertAtMost("planning for a network read from files", since(mark), 3);

        variantFlows(db);
    }

    /**
     * The same discipline for the variant flows, whose whole point is that a day costs a constant number of
     * requests rather than one set per timestep.
     *
     * <p>The bound that matters is the last one: loading two timesteps and loading eight of them send the
     * <em>same</em> number of requests. Everything else in this method is there so that the day exists.</p>
     */
    private static void variantFlows(RdfDbConnection db) {
        String scenario = "variants";
        db.clear(scenario);
        SnapshotCatalog catalog = db.snapshots(scenario);
        catalog.putFull(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource(), null,
                SnapshotRef.of(scenario, "1.0"), params(), ReportNode.NO_OP);
        // The instance files of the fixture; a versioned scenario keeps them under IRIs of the versioning layer
        // rather than among the contexts, so the number is the fixture's, as in versionedFlows above
        int graphs = 9;

        List<String> timesteps = new java.util.ArrayList<>();
        timesteps.add("2014-06-01T10:30:00Z");
        for (int i = 1; i < 8; i++) {
            String instant = String.format("2014-06-01T%02d:00:00Z", 11 + i);
            catalog.putAsDiff(TimestepFixtures.ssh(1 + i % 3, instant, "rq" + i), null,
                    new SnapshotRef(scenario, "1.0", instant), params(), ReportNode.NO_OP);
            timesteps.add(instant);
        }
        Network sender = RdfDbNetworkLoader.load(db, SnapshotRef.of(scenario, "1.0"), null, params(),
                ReportNode.NO_OP);
        RdfDbExport.export(sender, Changes.record(sender, n -> Changes.moveLoad(n, 4.0)), db,
                SnapshotRef.of(scenario, "1.1"), new CgmesDiffExport.ExportOptions());

        // Creating a variant: one chain query for the target and the candidates, one model fetch, one statement
        // fetch
        Network network = RdfDbNetworkLoader.load(db, SnapshotRef.of(scenario, "1.0"), null, params(),
                ReportNode.NO_OP);
        int mark = REQUESTS.get();
        UpdateResult created = RdfDbNetworkLoader.update(network, db, scenario, "1.1", null, "A", params(),
                ReportNode.NO_OP);
        assertTrue(created.route() == UpdateResult.Route.DIFF_APPLIED, created.reasons().toString());
        assertAtMost("creating a variant at a snapshot", since(mark), 3);

        // Updating an existing bound variant: the same three, with only its own chain as a candidate
        mark = REQUESTS.get();
        UpdateResult moved = RdfDbNetworkLoader.update(network, db, scenario, "1.0", null, "A", params(),
                ReportNode.NO_OP);
        assertTrue(moved.route() == UpdateResult.Route.DIFF_APPLIED, moved.reasons().toString());
        assertAtMost("updating an existing bound variant", since(mark), 3);

        // The bound that carries the design: two timesteps and eight of them cost the same
        mark = REQUESTS.get();
        RdfDbNetworkLoader.loadVariants(db, scenario, "1.0", timesteps.subList(0, 2),
                new RdfDbVariantLoadOptions(), null, params(), ReportNode.NO_OP);
        int twoTimesteps = since(mark);
        mark = REQUESTS.get();
        VariantLoadResult wholeDay = RdfDbNetworkLoader.loadVariants(db, scenario, "1.0", timesteps,
                new RdfDbVariantLoadOptions(), null, params(), ReportNode.NO_OP);
        int eightTimesteps = since(mark);
        // A load that refused six of the eight would send the same number of requests, so the equality below
        // only means something together with this
        assertTrue(wholeDay.refused().isEmpty() && wholeDay.bound().size() == timesteps.size(),
                () -> "the day has to load completely, got " + wholeDay.summary());
        LOGGER.info("loadVariants of 2 timesteps: {} request(s); of {}: {} request(s)", twoTimesteps,
                timesteps.size(), eightTimesteps);
        assertTrue(eightTimesteps == twoTimesteps,
                () -> "REGRESSION: loadVariants sent " + eightTimesteps + " requests for " + timesteps.size()
                        + " timesteps but " + twoTimesteps + " for 2: the cost has to be independent of the"
                        + " number of timesteps");
        assertAtMost("loading a day of " + timesteps.size() + " timesteps as variants", eightTimesteps,
                graphs + 6);

        // Writing one difference per variant: the seven of a versioned write, per group
        VariantLoadResult loaded = RdfDbNetworkLoader.loadVariants(db, scenario, "1.0",
                timesteps.subList(1, 4), new RdfDbVariantLoadOptions(), null, params(), ReportNode.NO_OP);
        Network day = loaded.network();
        List<com.powsybl.iidm.network.events.NetworkEvent> events = new java.util.ArrayList<>();
        for (String variant : loaded.bound().stream().map(VariantOutcome::variantId).toList()) {
            day.getVariantManager().setWorkingVariant(variant);
            events.addAll(Changes.record(day, n -> Changes.moveLoad(n, 2.5)));
        }
        day.getVariantManager().setWorkingVariant(RdfDbProvenance.PRIMARY_VARIANT);
        mark = REQUESTS.get();
        RdfDbExport.exportPerVariant(day, events, db, null, new CgmesDiffExport.ExportOptions(),
                ReportNode.NO_OP);
        // The seven of a versioned write plus the models read back, and one label resolution per group in the
        // first phase: nine per variant, and nothing that grows with the number of differences
        assertAtMost("writing one difference per variant for 3 variants", since(mark), 9 * 3);

        // With an explicit version label no label has to be resolved at all, so one request per group goes away
        List<com.powsybl.iidm.network.events.NetworkEvent> more = new java.util.ArrayList<>();
        for (String variant : loaded.bound().stream().map(VariantOutcome::variantId).toList()) {
            day.getVariantManager().setWorkingVariant(variant);
            more.addAll(Changes.record(day, n -> Changes.moveLoad(n, 1.5)));
        }
        day.getVariantManager().setWorkingVariant(RdfDbProvenance.PRIMARY_VARIANT);
        mark = REQUESTS.get();
        RdfDbExport.exportPerVariant(day, more, db, "study-a", new CgmesDiffExport.ExportOptions(),
                ReportNode.NO_OP);
        assertAtMost("writing one difference per variant for 3 variants, version label given", since(mark),
                8 * 3);
    }

    private static void assertAtMost(String what, int requests, int bound) {
        LOGGER.info("{}: {} request(s), bound {}", what, requests, bound);
        assertTrue(requests <= bound,
                () -> "REGRESSION: " + what + " sent " + requests + " requests, more than the " + bound
                        + " the design allows");
    }
}
