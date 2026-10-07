/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.powsybl.cgmes.rdfdb.Backends.BASE;
import static com.powsybl.cgmes.rdfdb.Backends.microGridBe;
import static com.powsybl.cgmes.rdfdb.Backends.params;
import static com.powsybl.cgmes.rdfdb.Backends.ref;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A custom profile: stored whole, never compared, left out of the network, and handed back to the caller as a graph.
 *
 * <p>The profile is {@code CFG}, a synthetic file of three settings in an invented namespace
 * ({@link TimestampFixtures#cfg}). It is part of the root of the MicroGrid BE tree, and a later timestamp ships a
 * changed one. What has to hold: the network is the one the standard files give, as if the custom file did not
 * exist; the custom graph is listed with the snapshot, comes back statement for statement, and travels with the
 * snapshots exactly as a whole graph &mdash; never as a difference.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbCustomProfileTest {

    private static final String S = "custom-profile";
    private static final String CFG = "CFG";
    private static final Instant T1 = Instant.parse("2014-06-01T11:00:00Z");
    private static final Set<String> IDENTITY = Set.of("cgmesMetadataModels", "rdfDbProvenance");

    private static final String CFG_1 = TimestampFixtures.cfg("urn:uuid:cfg-1", BASE, "40");
    private static final String CFG_2 = TimestampFixtures.cfg("urn:uuid:cfg-2", T1, "45");

    /** A scenario whose root carries the BE files and the custom profile. */
    private static RdfDbConnection withRoot(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "custom-profile"));
        db.clear(S);
        db.snapshots(S).putFull(TimestampFixtures.with(microGridBe(), TimestampFixtures.CFG, CFG_1), null,
                ref(S, 1), null, params(), ReportNode.NO_OP);
        return db;
    }

    /** The files of 11:00: three loads moved, and the custom profile given. */
    private static ReadOnlyDataSource t1(String cfg) {
        return TimestampFixtures.with(TimestampFixtures.ssh(3, T1, "1100"), TimestampFixtures.CFG, cfg);
    }

    private static Set<List<String>> triples(Collection<Statement> statements) {
        return statements.stream()
                .map(s -> List.of(s.getSubject().stringValue(), s.getPredicate().stringValue(),
                        s.getObject().toString()))
                .collect(Collectors.toSet());
    }

    private static Set<List<String>> parsed(String file) {
        try {
            Model model = Rio.parse(new StringReader(file), "http://example.org/base", RDFFormat.RDFXML);
            return triples(model);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aRootStoresTheCustomProfileAsAFullModel(String backend) {
        try (RdfDbConnection db = withRoot(backend)) {
            SnapshotInfo root = db.snapshots(S).require(ref(S, 1));
            assertThat(root.profiles()).contains(Profiles.EQ, Profiles.SSH, CFG);
            assertThat(root.state()).containsEntry(CFG, "urn:uuid:cfg-1");
            assertThat(root.fullModels()).containsEntry(CFG, "urn:uuid:cfg-1");
            StoredModel cfg = db.catalog(S).model("urn:uuid:cfg-1").orElseThrow();
            assertThat(cfg.subset()).isEqualTo(CFG);
            assertThat(cfg.kind()).isEqualTo(StoredModel.Kind.FULL);
            assertThat(cfg.fastPredicatesOnly()).isFalse();
            assertThat(cfg.variantSafe()).isNull();
            db.snapshots(S).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aLoadLeavesTheCustomProfileOutOfTheNetworkAndHandsItBack(String backend) {
        try (RdfDbConnection db = withRoot(backend)) {
            RdfDbNetworkLoader.LoadResult result = RdfDbNetworkLoader.loadWithStatistics(db, ref(S, 1), null,
                    params(), ReportNode.NO_OP);

            Networks.assertSameNetwork(Network.read(microGridBe(), params()), result.network(), IDENTITY);
            String graph = result.extraProfiles().get(CFG);
            assertThat(result.extraProfiles()).containsOnlyKeys(CFG);
            // Never in the conversion's store: the nine standard graphs are what the load transferred
            assertThat(result.statistics().perGraph()).hasSize(Profiles.STANDARD.size());
            assertThat(result.statistics().perGraph().keySet()).noneMatch(context -> context.contains(CFG));
            assertThat(result.network().getExtension(RdfDbProvenance.class).graphs())
                    .extracting(GraphInfo::remoteGraph).doesNotContain(graph);

            assertThat(db.snapshots(S).graphsOf(ref(S, 1))).containsEntry(CFG, graph);
            assertThat(triples(db.fetchGraph(S, graph))).isEqualTo(parsed(CFG_1));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aProjectionKeepsOrDropsTheCustomProfileAndRefusesAMalformedName(String backend) {
        try (RdfDbConnection db = withRoot(backend)) {
            Set<String> standard = Set.of(Profiles.EQ, Profiles.SSH, Profiles.TP, Profiles.SV);
            Set<String> withCfg = Set.of(Profiles.EQ, Profiles.SSH, Profiles.TP, Profiles.SV, CFG);
            assertThat(RdfDbNetworkLoader.loadWithStatistics(db, ref(S, 1), withCfg, null, params(),
                    ReportNode.NO_OP).extraProfiles()).containsOnlyKeys(CFG);
            assertThat(RdfDbNetworkLoader.loadWithStatistics(db, ref(S, 1), standard, null, params(),
                    ReportNode.NO_OP).extraProfiles()).isEmpty();
            assertThatThrownBy(() -> RdfDbNetworkLoader.load(db, ref(S, 1), Set.of(Profiles.EQ, "cfg"), null,
                    params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class).hasMessageContaining("'cfg' is not a profile name");
            assertThatThrownBy(() -> db.snapshots(S).putAsDiff(t1(CFG_2), null, ref(S, 1, T1),
                    Set.of(Profiles.SSH, "Cfg"), params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class).hasMessageContaining("'Cfg' is not a profile name");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anIngestionStoresAListedCustomProfileWholeAndComparesTheRest(String backend) {
        try (RdfDbConnection db = withRoot(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo written = catalog.putAsDiff(t1(CFG_2), null, ref(S, 1, T1),
                    Set.of(Profiles.EQ, Profiles.SSH, CFG), params(), ReportNode.NO_OP);

            assertThat(written.edge()).isEqualTo(SnapshotInfo.EdgeKind.TIMESTAMP);
            assertThat(written.members()).containsExactlyInAnyOrder("urn:uuid:ssh-1100", "urn:uuid:cfg-2");
            assertThat(written.state()).containsEntry(CFG, "urn:uuid:cfg-2")
                    .containsEntry(Profiles.SSH, "urn:uuid:ssh-1100");
            // The whole custom graph is the only full model of the difference snapshot
            assertThat(written.fullModels()).containsOnlyKeys(CFG);
            assertThat(written.fast()).isTrue();
            StoredModel cfg = db.catalog(S).model("urn:uuid:cfg-2").orElseThrow();
            assertThat(cfg.kind()).isEqualTo(StoredModel.Kind.FULL);
            assertThat(cfg.isDiff()).isFalse();
            assertThat(catalog.lastIngestStatistics().forwardStatements()).containsOnlyKeys(Profiles.SSH);
            catalog.verify();

            RdfDbNetworkLoader.LoadResult result = RdfDbNetworkLoader.loadWithStatistics(db, ref(S, 1, T1), null,
                    params(), ReportNode.NO_OP);
            Networks.assertSameNetwork(Network.read(TimestampFixtures.ssh(3, T1, "1100"), params()),
                    result.network(), IDENTITY);
            String graph = result.extraProfiles().get(CFG);
            assertThat(catalog.graphsOf(ref(S, 1, T1))).containsEntry(CFG, graph);
            assertThat(triples(db.fetchGraph(S, graph))).isEqualTo(parsed(CFG_2));
            // The root still answers with its own
            assertThat(triples(db.fetchGraph(S, catalog.graphsOf(ref(S, 1)).get(CFG)))).isEqualTo(parsed(CFG_1));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anUnlistedOrUnchangedCustomProfileIsInherited(String backend) {
        try (RdfDbConnection db = withRoot(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo unlisted = catalog.putAsDiff(t1(CFG_2), null, ref(S, 1, T1), null, params(),
                    ReportNode.NO_OP);
            assertThat(unlisted.state()).containsEntry(CFG, "urn:uuid:cfg-1");
            assertThat(unlisted.members()).containsExactly("urn:uuid:ssh-1100");
            assertThat(unlisted.fullModels()).isEmpty();
            assertThat(catalog.lastIngestStatistics().ignored()).contains(CFG);

            // Listed, but the file is the one the parent states: nothing is stored for it
            ReadOnlyDataSource again = TimestampFixtures.with(TimestampFixtures.ssh(2, T1, "1100b"),
                    TimestampFixtures.CFG, CFG_1);
            SnapshotInfo unchanged = catalog.putAsDiff(again, null, ref(S, 2, T1),
                    Set.of(Profiles.SSH, CFG), params(), ReportNode.NO_OP);
            assertThat(unchanged.members()).containsExactly("urn:uuid:ssh-1100b");
            assertThat(unchanged.state()).containsEntry(CFG, "urn:uuid:cfg-1");
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aTimestampWhoseOnlyChangeIsACustomProfileIsStillASnapshot(String backend) {
        try (RdfDbConnection db = withRoot(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo root = catalog.require(ref(S, 1));
            // Nothing standard is compared, so the custom file is the only change, and no difference names the pin
            SnapshotInfo written = catalog.putAsDiff(t1(CFG_2), null, ref(S, 1, T1), Set.of(CFG), params(),
                    ReportNode.NO_OP);
            assertThat(written.edge()).isEqualTo(SnapshotInfo.EdgeKind.TIMESTAMP);
            assertThat(written.parent()).isEqualTo(root.iri());
            assertThat(written.members()).containsExactly("urn:uuid:cfg-2");
            assertThat(written.state()).containsEntry(Profiles.SSH, root.state().get(Profiles.SSH))
                    .containsEntry(CFG, "urn:uuid:cfg-2");
            catalog.verify();
            // The grid of the base, at the moment of the new timestamp
            Network expected = Network.read(microGridBe(), params());
            expected.setCaseDate(T1.atZone(ZoneOffset.UTC));
            Networks.assertSameNetwork(expected,
                    RdfDbNetworkLoader.load(db, ref(S, 1, T1), null, params(), ReportNode.NO_OP), IDENTITY);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anUpdateWalksPastAWholeMemberAndACheckpointStillFoldsTheChain(String backend) {
        try (RdfDbConnection db = withRoot(backend)) {
            db.snapshots(S).putAsDiff(t1(CFG_2), null, ref(S, 1, T1), Set.of(Profiles.EQ, Profiles.SSH, CFG),
                    params(), ReportNode.NO_OP);
            Network network = RdfDbNetworkLoader.load(db, ref(S, 1), null, params(), ReportNode.NO_OP);

            UpdateResult result = RdfDbNetworkLoader.update(network, db, ref(S, 1, T1), new RdfDbUpdateOptions(),
                    params(), ReportNode.NO_OP);
            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(result.appliedModelIds()).containsOnlyKeys(Profiles.SSH);
            // The same grid as the materialised target; the moved loads keep their stale flows in place
            Network target = RdfDbNetworkLoader.load(db, ref(S, 1, T1), null, params(), ReportNode.NO_OP);
            Network base = RdfDbNetworkLoader.load(db, ref(S, 1), null, params(), ReportNode.NO_OP);
            Set<String> moved = target.getLoadStream()
                    .filter(load -> load.getP0() != base.getLoad(load.getId()).getP0())
                    .map(Identifiable::getId).collect(Collectors.toSet());
            assertThat(moved).hasSize(3);
            Networks.assertSameNetworkIgnoringStateVariables(target, result.network(), IDENTITY, moved);

            // A whole custom graph is no checkpoint of the standard profiles
            SnapshotInfo checkpoint = Checkpoint.create(db, ref(S, 1, T1));
            assertThat(checkpoint.fullModels()).containsKeys(Profiles.SSH, CFG);
            // The steady state is a difference there, the equipment the instance file of the root
            Map<String, String> graphs = db.snapshots(S).graphsOf(ref(S, 1, T1));
            assertThat(graphs).containsKeys(Profiles.EQ, CFG).doesNotContainKey(Profiles.SSH);
            db.snapshots(S).verify();
        }
    }
}
