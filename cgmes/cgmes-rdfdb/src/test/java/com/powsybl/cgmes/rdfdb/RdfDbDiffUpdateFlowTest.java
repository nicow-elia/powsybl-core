/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bringing a network to a stored state: the whole round trip, on a real store, on both backends.
 *
 * <p>What is being asserted is the promise the work package makes, and it has two halves that have to agree. A
 * network updated <em>in place</em> by applying the differences it has not seen, and the same state
 * <em>materialised</em> from the database and converted from scratch, must be the same network &mdash; otherwise
 * the fast route is a shortcut that changes the answer. So the tests below run a change through the exporter into
 * the database, bring a second network to it, and compare against the network the change produced in the first
 * place.</p>
 *
 * <p>The {@code cgmesMetadataModels} extension is left out of those comparisons and asserted on its own: a network
 * that walked the chain step by step and one that was built at the target say different things about the versions
 * they passed, which is correct in both cases and is exactly what the identity assertions check.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbDiffUpdateFlowTest {

    private static final String S = "2016-01-01";
    private static final String OTHER = "2016-01-02";
    private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
    private static final CgmesSubset SSH = CgmesSubset.STEADY_STATE_HYPOTHESIS;
    private static final CgmesSubset EQ = CgmesSubset.EQUIPMENT;

    /** The identity of a network is its own assertion, so it is kept out of the network comparison. */
    private static final Set<String> IDENTITY = Set.of("cgmesMetadataModels");

    static Stream<Arguments> backends() {
        return Backends.backends();
    }

    private static Properties params() {
        Properties p = new Properties();
        p.put(CgmesImport.IMPORT_CGM_WITH_SUBNETWORKS, "false");
        return p;
    }

    private static ReadOnlyDataSource be() {
        return CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
    }

    private static RdfDbConnection twoScenarios(String backend) {
        return twoScenarios(Backends.database(backend, "update-flow"));
    }

    private static RdfDbConnection twoScenarios(RdfDatabase database) {
        RdfDbConnection db = RdfDbConnection.open(database);
        db.clear(S);
        db.clear(OTHER);
        db.loadCgmes(S, be(), null, params(), ReportNode.NO_OP);
        db.loadCgmes(OTHER, be(), null, params(), ReportNode.NO_OP);
        return db;
    }

    private static Network load(RdfDbConnection db, String scenario) {
        return RdfDbNetworkLoader.load(db, scenario, null, params(), ReportNode.NO_OP);
    }

    private static UpdateResult update(Network network, RdfDbConnection db, String scenario) {
        return update(network, db, scenario, DiffTarget.head(), new RdfDbUpdateOptions());
    }

    private static UpdateResult update(Network network, RdfDbConnection db, String scenario, DiffTarget target,
                                       RdfDbUpdateOptions options) {
        return RdfDbNetworkLoader.update(network, db, scenario, target, options, params(), ReportNode.NO_OP);
    }

    /** Record a change on a network and store it as a difference. */
    private static RdfDbExport.Result record(Network network, RdfDbConnection db, String scenario,
                                             java.util.function.Consumer<Network> change) {
        List<NetworkEvent> events = Changes.record(network, change);
        return RdfDbExport.export(network, events, db, scenario, new CgmesDiffExport.ExportOptions());
    }

    // ------------------------------------------------------------------ the fast route

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aSingleDifferenceIsAppliedInPlace(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network sender = load(db, S);
            Network receiver = load(db, S);
            StoredModel diff = record(sender, db, S, n -> Changes.moveLoad(n, 12.0)).get(SSH).orElseThrow();

            UpdateResult result = update(receiver, db, S);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(result.isReplacement()).isFalse();
            assertThat(result.network()).isSameAs(receiver);
            assertThat(result.appliedModelIds().get(SSH)).containsExactly(diff.id());
            assertThat(result.reasons()).isEmpty();
            assertThat(result.statistics().diffCount()).isEqualTo(1);
            Networks.assertSameNetworkIgnoringStateVariables(sender, receiver, IDENTITY, Set.of(Changes.LOAD_ID));
            // The receiver now says it is at the difference, both in the CGMES identity and in the provenance
            assertThat(NetworkIdentity.modelIds(receiver).get(SSH)).isEqualTo(diff.id());
            assertThat(receiver.getExtension(RdfDbProvenance.class).modelIds().get(SSH)).isEqualTo(diff.id());
            assertThat(receiver.getExtension(RdfDbProvenance.class).scenario()).isEqualTo(S);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aNetworkAtTheHeadIsNotTouched(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network network = load(db, S);
            UpdateResult first = update(network, db, S);
            assertThat(first.route()).isEqualTo(UpdateResult.Route.NOOP);

            record(network, db, S, n -> Changes.moveLoad(n, 4.0));
            UpdateResult second = update(network, db, S);
            // The sender wrote the difference and was advanced by the export, so it is at the head already
            assertThat(second.route()).isEqualTo(UpdateResult.Route.NOOP);
            assertThat(second.statistics().diffCount()).isZero();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aChainOfThreeDifferencesIsComposedAndAppliedOnce(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network sender = load(db, S);
            Network receiver = load(db, S);
            record(sender, db, S, n -> Changes.moveLoad(n, 3.0));
            record(sender, db, S, n -> Changes.moveTap(n));
            record(sender, db, S, n -> Changes.moveGenerator(n));

            UpdateResult result = update(receiver, db, S);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(result.statistics().diffCount()).isEqualTo(3);
            assertThat(result.appliedModelIds().get(SSH)).hasSize(3);
            // The three quantities the chain changed, each from a different difference of it
            assertThat(receiver.getLoad(Changes.LOAD_ID).getP0())
                    .isEqualTo(sender.getLoad(Changes.LOAD_ID).getP0());
            String generator = Changes.firstGenerator(sender);
            assertThat(receiver.getGenerator(generator).getTargetP())
                    .isEqualTo(sender.getGenerator(generator).getTargetP());
            String transformer = Changes.tapChangerOwner(sender);
            assertThat(receiver.getTwoWindingsTransformer(transformer).getRatioTapChanger().getTapPosition())
                    .isEqualTo(sender.getTwoWindingsTransformer(transformer).getRatioTapChanger().getTapPosition());
            Networks.assertSameNetworkIgnoringStateVariables(sender, receiver, IDENTITY,
                    Set.of(Changes.LOAD_ID, generator, transformer));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void anOlderModelCanBeTargetedAndTheDifferencesAreUndone(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network sender = load(db, S);
            Network afterFirst = load(db, S);
            StoredModel first = record(sender, db, S, n -> Changes.moveLoad(n, 3.0)).get(SSH).orElseThrow();
            // A second network brought to the first difference is the state to compare against
            update(afterFirst, db, S);
            record(sender, db, S, n -> Changes.moveGenerator(n));

            Network receiver = load(db, S, DiffTarget.head());
            UpdateResult back = update(receiver, db, S, DiffTarget.models(Map.of(SSH, first.id())),
                    new RdfDbUpdateOptions());

            assertThat(back.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(back.isReplacement()).isFalse();
            Networks.assertSameNetworkIgnoringStateVariables(afterFirst, receiver, IDENTITY,
                    Set.of(Changes.LOAD_ID, Changes.firstGenerator(sender)));
            assertThat(NetworkIdentity.modelIds(receiver).get(SSH)).isEqualTo(first.id());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void whatTheDifferenceRouteReachesIsWhatTheDatabaseHolds(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network sender = load(db, S);
            Network receiver = load(db, S);
            record(sender, db, S, n -> Changes.moveLoad(n, 6.0));
            record(sender, db, S, n -> Changes.moveTap(n));
            update(receiver, db, S);

            Network materialised = load(db, S, DiffTarget.head());
            Networks.assertSameNetworkIgnoringStateVariables(receiver, materialised, IDENTITY,
                    Set.of(Changes.LOAD_ID, Changes.tapChangerOwner(sender)));
            // Both say they are at the same stored models, this time including the identity
            assertThat(NetworkIdentity.modelIds(materialised).get(SSH))
                    .isEqualTo(NetworkIdentity.modelIds(receiver).get(SSH));
        }
    }

    private static Network load(RdfDbConnection db, String scenario, DiffTarget target) {
        return RdfDbNetworkLoader.load(db, scenario, target, null, params(), ReportNode.NO_OP);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aPlainLoadOfAVersionedScenarioIsItsNewestState(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network sender = load(db, S);
            record(sender, db, S, n -> Changes.moveLoad(n, 8.0));
            // load(db, scenario) without a target means the head once the scenario holds differences
            Network fresh = load(db, S);
            Networks.assertSameNetwork(sender, fresh, IDENTITY);
            assertThat(NetworkIdentity.modelIds(fresh).get(SSH)).isEqualTo(NetworkIdentity.modelIds(sender).get(SSH));
        }
    }

    // ------------------------------------------------------------------ the full route

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aDifferenceNoUpdateQueryCanApplyIsMaterialised(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network receiver = load(db, S);
            String lineId = receiver.getLineStream().map(Line::getId).sorted().findFirst().orElseThrow();
            StoredModel eqBase = db.catalog(S).full(EQ).orElseThrow();
            String oldName = receiver.getLine(lineId).getNameOrId();
            new RdfDbDifferenceSink(db, S).accept(renaming("urn:uuid:eq-rename", eqBase, lineId, oldName));

            StoredModel diff = db.catalog(S).model("urn:uuid:eq-rename").orElseThrow();
            assertThat(diff.fastPredicatesOnly()).isFalse();

            UpdateResult result = update(receiver, db, S);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_RELOAD);
            assertThat(result.isReplacement()).isTrue();
            assertThat(result.network()).isNotSameAs(receiver);
            assertThat(result.reasons()).isNotEmpty();
            assertThat(result.reasons().toString()).contains("urn:uuid:eq-rename");
            // The replacement carries the change, the network that was handed in does not
            assertThat(result.network().getLine(lineId).getNameOrId()).isEqualTo("a new name");
            assertThat(receiver.getLine(lineId).getNameOrId()).isEqualTo(oldName);
            // And it is the same network a plain load of the head produces
            Networks.assertSameNetwork(load(db, S, DiffTarget.head()), result.network(), IDENTITY);
        }
    }

    /**
     * A fallback is not a change of destination.
     *
     * <p>The equipment model has to move forward and the steady state hypothesis backward, which this release does
     * not combine in one update, so the difference route is given up <em>after</em> the plan was made. What comes
     * back has to be the version the caller asked for: the result says which route was taken but not which state it
     * reached, so a rebuild that quietly materialises the head instead would be invisible.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aFallbackKeepsTheRequestedTarget(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network sender = load(db, S);
            String line = sender.getLineStream().map(Line::getId).sorted().findFirst().orElseThrow();
            double baseR = sender.getLine(line).getR();
            double baseP0 = sender.getLoad(Changes.LOAD_ID).getP0();
            StoredModel sshFull = db.catalog(S).full(SSH).orElseThrow();
            StoredModel eqFull = db.catalog(S).full(EQ).orElseThrow();

            StoredModel sshDiff = record(sender, db, S, n -> Changes.moveLoad(n, 9.0)).get(SSH).orElseThrow();
            StoredModel eqDiff = record(sender, db, S, n -> n.getLine(line).setR(baseR + 0.25))
                    .get(EQ).orElseThrow();

            // The receiver holds the newest steady state hypothesis and the original equipment model, so reaching
            // (EQ at its difference, SSH at its instance file) means one profile forward and one backward
            Network receiver = load(db, S, DiffTarget.models(Map.of(EQ, eqFull.id(), SSH, sshDiff.id())));
            UpdateResult result = update(receiver, db, S,
                    DiffTarget.models(Map.of(EQ, eqDiff.id(), SSH, sshFull.id())), new RdfDbUpdateOptions());

            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_RELOAD);
            assertThat(result.reasons().toString()).contains("forward and another backwards");
            // The replacement is at the models that were asked for, not at the head of the scenario
            assertThat(NetworkIdentity.modelIds(result.network()).get(SSH)).isEqualTo(sshFull.id());
            assertThat(NetworkIdentity.modelIds(result.network()).get(EQ)).isEqualTo(eqDiff.id());
            assertThat(result.network().getLine(line).getR()).isEqualTo(baseR + 0.25);
            assertThat(result.network().getLoad(Changes.LOAD_ID).getP0()).isEqualTo(baseP0);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aReloadThatIsNotAllowedIsReportedInstead(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network receiver = load(db, S);
            String lineId = receiver.getLineStream().map(Line::getId).sorted().findFirst().orElseThrow();
            StoredModel eqBase = db.catalog(S).full(EQ).orElseThrow();
            String oldName = receiver.getLine(lineId).getNameOrId();
            new RdfDbDifferenceSink(db, S).accept(renaming("urn:uuid:eq-rename-2", eqBase, lineId, oldName));

            UpdateResult result = update(receiver, db, S, DiffTarget.head(),
                    new RdfDbUpdateOptions().setAllowFullReload(false));

            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_REQUIRED);
            assertThat(result.isReplacement()).isFalse();
            assertThat(result.network()).isSameAs(receiver);
            assertThat(result.reasons()).isNotEmpty();
            assertThat(receiver.getLine(lineId).getNameOrId()).isEqualTo(oldName);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aNetworkOfAnotherGridFallsBackAndSaysWhy(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network stranger = Network.read(CgmesConformity1Catalog.miniBusBranch().dataSource(), params());
            UpdateResult result = update(stranger, db, S, DiffTarget.head(),
                    new RdfDbUpdateOptions().setAllowFullReload(false));
            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_REQUIRED);
            assertThat(result.reasons().toString()).contains("neither an ancestor nor a descendant");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aChainLongerThanAllowedIsMaterialisedInstead(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network sender = load(db, S);
            Network receiver = load(db, S);
            record(sender, db, S, n -> Changes.moveLoad(n, 1.0));
            record(sender, db, S, n -> Changes.moveLoad(n, 1.0));
            record(sender, db, S, n -> Changes.moveLoad(n, 1.0));

            UpdateResult result = update(receiver, db, S, DiffTarget.head(),
                    new RdfDbUpdateOptions().setMaxDiffChain(2));
            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_RELOAD);
            assertThat(result.reasons().toString()).contains("exceeds maxDiffChain 2");
            Networks.assertSameNetwork(sender, result.network(), IDENTITY);
        }
    }

    // ------------------------------------------------------------------ scenarios

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void anUpdateTowardsAnotherScenarioIsAReload(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network here = load(db, S);
            Network there = load(db, OTHER);
            record(there, db, OTHER, n -> Changes.moveLoad(n, 15.0));

            UpdateResult result = update(here, db, OTHER);
            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_RELOAD);
            assertThat(result.reasons().toString()).contains("never cross scenarios").contains(S).contains(OTHER);
            assertThat(result.isReplacement()).isTrue();
            assertThat(result.network().getExtension(RdfDbProvenance.class).scenario()).isEqualTo(OTHER);
            Networks.assertSameNetwork(there, result.network(), IDENTITY);
            // The network that was handed in is untouched, and still belongs to its own scenario
            assertThat(here.getExtension(RdfDbProvenance.class).scenario()).isEqualTo(S);

            UpdateResult refused = update(here, db, OTHER, DiffTarget.head(),
                    new RdfDbUpdateOptions().setAllowFullReload(false));
            assertThat(refused.route()).isEqualTo(UpdateResult.Route.FULL_REQUIRED);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void differencesOfAnotherScenarioAreInvisible(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network there = load(db, OTHER);
            Network here = load(db, S);
            record(there, db, OTHER, n -> Changes.moveLoad(n, 2.0));
            record(there, db, OTHER, n -> Changes.moveTap(n));

            UpdateResult result = update(here, db, S);
            assertThat(result.route()).isEqualTo(UpdateResult.Route.NOOP);
            assertThat(result.statistics().diffCount()).isZero();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void blankScenarioRejected(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network network = load(db, S);
            for (String blank : new String[] {"", " ", null}) {
                assertThatThrownBy(() -> update(network, db, blank))
                        .isInstanceOf(RdfDbException.class).hasMessageContaining("must not be blank");
                assertThatThrownBy(() -> load(db, blank, DiffTarget.head()))
                        .isInstanceOf(RdfDbException.class).hasMessageContaining("must not be blank");
            }
        }
    }

    // ------------------------------------------------------------------ what is not supported

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void theRemoteQueryModeCannotSeeDifferences(String backend) {
        RdfDatabase database = Backends.database(backend, "update-remote");
        try (RdfDbConnection db = twoScenarios(database)) {
            Network sender = load(db, S);
            record(sender, db, S, n -> Changes.moveLoad(n, 5.0));
            // The very same data, addressed by a connection that wants to query the store rather than fetch it.
            // The first connection stays open on purpose: an in-process database lives as long as somebody holds it
            try (RdfDbConnection remote =
                         RdfDbConnection.open(database.withQueryMode(RdfDatabase.QueryMode.REMOTE))) {
                assertThatThrownBy(() -> load(remote, S))
                        .isInstanceOf(RdfDbException.class)
                        .hasMessageContaining("REMOTE query mode")
                        .hasMessageContaining("difference models");
            }
        }
    }

    /**
     * The two ways {@link RdfDbDiffSource#fetchAll} reads the step graphs, one {@code SELECT} over all of them or
     * one Graph Store Protocol GET per graph, return the same statements in both directions. On the in-process
     * backend both calls take the only route there is, which is the reference; a difference with an empty reverse
     * graph (a graph the server does not know) is part of the set, since the GET answers it with a 404.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void bothFetchRoutesReadTheSameStatements(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network sender = load(db, S);
            StoredModel first = record(sender, db, S, n -> Changes.moveLoad(n, 3.0)).get(SSH).orElseThrow();
            StoredModel second = record(sender, db, S, Changes::moveTap).get(SSH).orElseThrow();
            DifferenceModelHeader header = DifferenceModelHeader.builder("urn:uuid:diff-no-reverse", SSH, CIM16)
                    .version(second.version() + 1).supersedes(List.of(second.id())).build();
            RdfDbDifferenceSink sink = new RdfDbDifferenceSink(db, S);
            sink.accept(new DifferenceModelSet(List.of(new DifferenceModel(header,
                    List.of(CgmesStatement.literal(Changes.LOAD_ID, null, "EnergyConsumer.q", "1.5")), List.of(),
                    List.of()))));
            List<StoredModel> nodes = List.of(first, second, sink.stored().get(0));

            List<DifferenceModel> bySelect = RdfDbDiffSource.fetchAll(db, nodes, Integer.MAX_VALUE);
            List<DifferenceModel> byGraph = RdfDbDiffSource.fetchAll(db, nodes, 0);

            assertThat(byGraph).hasSize(3);
            for (int i = 0; i < nodes.size(); i++) {
                assertThat(bySelect.get(i).forward()).isNotEmpty();
                assertThat(byGraph.get(i).forward()).containsExactlyInAnyOrderElementsOf(bySelect.get(i).forward());
                assertThat(byGraph.get(i).reverse()).containsExactlyInAnyOrderElementsOf(bySelect.get(i).reverse());
                assertThat(byGraph.get(i).header()).isEqualTo(bySelect.get(i).header());
            }
            assertThat(byGraph.get(2).reverse()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * An equipment difference renaming a line, which states a property no update query reads and is therefore not
     * applicable to a live network.
     */
    private static DifferenceModelSet renaming(String id, StoredModel base, String lineId, String oldName) {
        DifferenceModelHeader header = DifferenceModelHeader.builder(id, EQ, CIM16)
                .version(base.version() + 1).supersedes(List.of(base.id())).build();
        return new DifferenceModelSet(List.of(new DifferenceModel(header,
                List.of(CgmesStatement.literal(lineId, null, "IdentifiedObject.name", "a new name")),
                List.of(CgmesStatement.literal(lineId, null, "IdentifiedObject.name", oldName)), List.of())));
    }
}
