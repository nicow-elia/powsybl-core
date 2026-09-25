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
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Storing difference models: what is written, what comes back, and every rule the write refuses.
 *
 * <p>Two things are being asserted here and they are worth telling apart. One is the <em>content</em>: a
 * difference that goes into the database has to come back as the same statements, because everything downstream
 * &mdash; composing a chain, applying it to a network, materialising a version &mdash; is only as correct as that
 * round trip. The other is the <em>rules</em>: a difference applies on exactly one stored model of its own
 * scenario, a model has one successor, an identifier is used once. Those are what keeps a shared database from
 * quietly growing two versions of the truth.</p>
 *
 * <p>Every case runs on the embedded server and on the in-process backend, and the store always holds a second
 * scenario with the same files, so every assertion is also an assertion that the two do not see each other.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbDifferenceSinkTest {

    private static final String S = "2016-01-01";
    private static final String OTHER = "other";

    static Stream<org.junit.jupiter.params.provider.Arguments> backends() {
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

    /** A connection whose scenarios {@value #S} and {@value #OTHER} both hold the MicroGrid BE fixture. */
    private static RdfDbConnection twoScenarios(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "sink"));
        db.clear(S);
        db.clear(OTHER);
        db.loadCgmes(S, be(), null, params(), ReportNode.NO_OP);
        db.loadCgmes(OTHER, be(), null, params(), ReportNode.NO_OP);
        return db;
    }

    private static Network load(RdfDbConnection db, String scenario) {
        return RdfDbNetworkLoader.load(db, scenario, null, params(), ReportNode.NO_OP);
    }

    private static RdfDbExport.Result exportLoadChange(Network network, RdfDbConnection db, String scenario) {
        List<NetworkEvent> events = Changes.record(network, n -> Changes.moveLoad(n, 12.0));
        return RdfDbExport.export(network, events, db, scenario, new CgmesDiffExport.ExportOptions());
    }

    // ------------------------------------------------------------------ the uploaded models

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void anUploadRegistersOneFullModelPerInstanceFile(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            List<StoredModel> models = db.catalog(S).models();
            assertThat(models).isNotEmpty();
            assertThat(models).allSatisfy(model -> {
                assertThat(model.kind()).isEqualTo(StoredModel.Kind.FULL);
                assertThat(model.chainDepth()).isZero();
                assertThat(model.scenario()).isEqualTo(S);
                assertThat(model.graph()).isNotBlank();
                assertThat(model.tripleCount()).isPositive();
                assertThat(model.cimNamespace()).isEqualTo("http://iec.ch/TC57/2013/CIM-schema-cim16#");
                assertThat(model.subjectBase()).endsWith("#");
            });
            assertThat(models.stream().map(StoredModel::subset).toList())
                    .contains(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS,
                            CgmesSubset.TOPOLOGY, CgmesSubset.STATE_VARIABLES);
            // The subject base is the one the instance files were parsed with, so a statement written with it
            // addresses the very objects the graphs hold
            StoredModel ssh = db.catalog(S).full(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
            assertThat(db.sparql(S).ask("ASK { GRAPH <" + ssh.graph() + "> { <" + ssh.subjectBase() + "_"
                    + Changes.LOAD_ID + "> ?p ?o } }")).isTrue();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void reUploadingTheSameFileReplacesItsNode(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            List<StoredModel> before = db.catalog(S).models();
            db.loadCgmes(S, be(), null, params(), ReportNode.NO_OP);
            List<StoredModel> after = db.catalog(S).models();
            assertThat(after.stream().map(StoredModel::id).toList())
                    .containsExactlyElementsOf(before.stream().map(StoredModel::id).toList());
        }
    }

    // ------------------------------------------------------------------ writing a difference

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void writesForwardReverseAndMetadataInOneRequest(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network network = load(db, S);
            StoredModel sshBefore = db.catalog(S).full(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();

            RdfDbExport.Result result = exportLoadChange(network, db, S);
            StoredModel diff = result.get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();

            assertThat(diff.kind()).isEqualTo(StoredModel.Kind.DIFF);
            assertThat(diff.chainDepth()).isEqualTo(1);
            assertThat(diff.fastPredicatesOnly()).isTrue();
            assertThat(diff.supersedes()).containsExactly(sshBefore.id());
            assertThat(diff.subjectBase()).isEqualTo(sshBefore.subjectBase());
            assertThat(diff.cimNamespace()).isEqualTo(sshBefore.cimNamespace());
            assertThat(diff.tripleCount()).isPositive();

            long forward = count(db, diff.forwardGraph());
            long reverse = count(db, diff.reverseGraph());
            assertThat(forward).isPositive();
            assertThat(reverse).isPositive();
            assertThat(forward + reverse).isEqualTo(diff.tripleCount());
            assertThat(db.catalog(S).head(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow().id())
                    .isEqualTo(diff.id());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void readBackEqualsWritten(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network network = load(db, S);
            List<NetworkEvent> events = Changes.record(network, n -> Changes.moveLoad(n, 12.0));
            CgmesDiffExport.Result exported =
                    CgmesDiffExport.toDifferences(network, events, new CgmesDiffExport.ExportOptions());
            DifferenceModel written = exported.differences().get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();

            new RdfDbDifferenceSink(db, S).accept(exported.differences());
            StoredModel node = db.catalog(S).model(written.header().id()).orElseThrow();
            DifferenceModel read = RdfDbDiffSource.fetch(db, node);

            assertThat(read.forward()).containsExactlyInAnyOrderElementsOf(written.forward());
            assertThat(read.reverse()).containsExactlyInAnyOrderElementsOf(written.reverse());
            assertThat(read.header().id()).isEqualTo(written.header().id());
            assertThat(read.header().subset()).isEqualTo(written.header().subset());
            assertThat(read.header().version()).isEqualTo(written.header().version());
            assertThat(read.header().supersedes()).isEqualTo(written.header().supersedes());
            assertThat(read.header().cimNamespace()).isEqualTo(written.header().cimNamespace());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void senderIdentityAdvancesAfterExportSoASecondExportChains(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network network = load(db, S);
            StoredModel first = exportLoadChange(network, db, S).get(CgmesSubset.STEADY_STATE_HYPOTHESIS)
                    .orElseThrow();
            assertThat(NetworkIdentity.modelIds(network).get(CgmesSubset.STEADY_STATE_HYPOTHESIS))
                    .isEqualTo(first.id());

            StoredModel second = exportLoadChange(network, db, S).get(CgmesSubset.STEADY_STATE_HYPOTHESIS)
                    .orElseThrow();
            assertThat(second.supersedes()).containsExactly(first.id());
            assertThat(second.chainDepth()).isEqualTo(2);
            assertThat(db.catalog(S).chainDown(second.id()).stream().map(StoredModel::id).toList())
                    .startsWith(second.id(), first.id());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void eqAndSshOfOneChangeSetAreStoredTogether(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network network = load(db, S);
            String lineId = network.getLineStream().findFirst().orElseThrow().getId();
            List<NetworkEvent> events = Changes.record(network, n -> {
                Changes.moveLoad(n, 5.0);
                n.getLine(lineId).setR(n.getLine(lineId).getR() + 0.1);
            });
            RdfDbExport.Result result =
                    RdfDbExport.export(network, events, db, S, new CgmesDiffExport.ExportOptions());
            assertThat(result.stored()).hasSize(2);
            assertThat(result.get(CgmesSubset.EQUIPMENT)).isPresent();
            assertThat(result.get(CgmesSubset.STEADY_STATE_HYPOTHESIS)).isPresent();
            assertThat(db.catalog(S).head(CgmesSubset.EQUIPMENT).orElseThrow().id())
                    .isEqualTo(result.get(CgmesSubset.EQUIPMENT).orElseThrow().id());
        }
    }

    // ------------------------------------------------------------------ the rules

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void rejectsUnknownBase(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            DifferenceModelSet set = handMade("urn:uuid:diff-1", List.of("urn:uuid:not-stored"));
            assertThatThrownBy(() -> new RdfDbDifferenceSink(db, S).accept(set))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("urn:uuid:not-stored")
                    .hasMessageContaining("not stored in scenario '" + S + "'");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void rejectsMultipleSupersedes(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            String base = db.catalog(S).full(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow().id();
            DifferenceModelSet set = handMade("urn:uuid:diff-2", List.of(base, "urn:uuid:other"));
            assertThatThrownBy(() -> new RdfDbDifferenceSink(db, S).accept(set))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("must supersede exactly one stored model");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void rejectsDuplicateId(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            StoredModel base = db.catalog(S).full(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
            DifferenceModelSet set = handMade("urn:uuid:diff-3", List.of(base.id()));
            new RdfDbDifferenceSink(db, S).accept(set);
            // The same identifier again, on a base that is free now, so only the duplicate rule can refuse it
            DifferenceModelSet again = handMade("urn:uuid:diff-3", List.of("urn:uuid:diff-3"));
            assertThatThrownBy(() -> new RdfDbDifferenceSink(db, S).accept(again))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("is already stored");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void rejectsSecondSuccessorAndNamesIt(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            StoredModel base = db.catalog(S).full(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
            new RdfDbDifferenceSink(db, S).accept(handMade("urn:uuid:diff-a", List.of(base.id())));
            DifferenceModelSet fork = handMade("urn:uuid:diff-b", List.of(base.id()));
            assertThatThrownBy(() -> new RdfDbDifferenceSink(db, S).accept(fork))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("urn:uuid:diff-a")
                    .hasMessageContaining("linear");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void rejectsCimNamespaceMismatch(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            StoredModel base = db.catalog(S).full(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
            DifferenceModelHeader header = DifferenceModelHeader
                    .builder("urn:uuid:diff-ns", CgmesSubset.STEADY_STATE_HYPOTHESIS, "http://iec.ch/TC57/CIM100#")
                    .version(2).supersedes(List.of(base.id())).build();
            DifferenceModelSet set = new DifferenceModelSet(List.of(new DifferenceModel(header,
                    List.of(CgmesStatement.literal(Changes.LOAD_ID, null, "EnergyConsumer.p", "1")),
                    List.of(CgmesStatement.literal(Changes.LOAD_ID, null, "EnergyConsumer.p", "0")), List.of())));
            assertThatThrownBy(() -> new RdfDbDifferenceSink(db, S).accept(set))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("CIM namespace");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void rejectsABaseStoredInAnotherScenarioAndSaysWhereItIs(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            String third = "third";
            db.clear(third);
            db.loadCgmes(third, CgmesConformity1Catalog.miniBusBranch().dataSource(), null, params(),
                    ReportNode.NO_OP);
            String beBase = db.catalog(S).full(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow().id();
            DifferenceModelSet set = handMade("urn:uuid:diff-cross", List.of(beBase));
            assertThatThrownBy(() -> new RdfDbDifferenceSink(db, third).accept(set))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("never cross scenarios")
                    .hasMessageContaining(S);
            db.clear(third);
        }
    }

    // ------------------------------------------------------------------ scenarios

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void twoScenariosHoldingTheSameFilesAreIsolated(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network network = load(db, S);
            exportLoadChange(network, db, S);

            assertThat(db.catalog(OTHER).models()).allMatch(model -> model.kind() == StoredModel.Kind.FULL);
            assertThat(db.catalog(OTHER).head(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow().id())
                    .isEqualTo(db.catalog(OTHER).full(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow().id());
            assertThat(db.catalog(OTHER).models()).allMatch(model -> OTHER.equals(model.scenario()));
            assertThat(db.catalog(S).models()).allMatch(model -> S.equals(model.scenario()));
            assertThat(db.scenarios()).contains(S, OTHER);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aSenderOfAnotherScenarioIsRefused(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network network = load(db, OTHER);
            List<NetworkEvent> events = Changes.record(network, n -> Changes.moveLoad(n, 3.0));
            assertThatThrownBy(() ->
                    RdfDbExport.export(network, events, db, S, new CgmesDiffExport.ExportOptions()))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("loaded from scenario '" + OTHER + "'");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aFileLoadedSenderExportsIntoEitherScenario(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            Network network = Network.read(be(), params());
            RdfDbProvenance before = network.getExtension(RdfDbProvenance.class);
            assertThat(before).isNull();
            RdfDbExport.Result result = exportLoadChange(network, db, OTHER);
            assertThat(result.stored()).hasSize(1);
            assertThat(db.catalog(S).models()).allMatch(model -> model.kind() == StoredModel.Kind.FULL);
            RdfDbProvenance after = network.getExtension(RdfDbProvenance.class);
            assertThat(after.scenario()).isEqualTo(OTHER);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aScenarioNameWithSpecialCharactersRoundTrips(String backend) {
        try (RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "sink-special"))) {
            String scenario = "DACF#2016?01:01";
            db.clear(scenario);
            db.loadCgmes(scenario, be(), null, params(), ReportNode.NO_OP);
            assertThat(db.catalog(scenario).metaGraph()).startsWith(RdfDbNames.BASE).endsWith("/meta")
                    .doesNotContain("#").doesNotContain("?");
            assertThat(db.catalog(scenario).models()).allMatch(model -> scenario.equals(model.scenario()));
            assertThat(db.scenarios()).contains(scenario);
            db.clear(scenario);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void blankScenarioRejected(String backend) {
        try (RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "sink-blank"))) {
            for (String blank : new String[] {"", " ", null}) {
                assertThat(assertThrows(RdfDbException.class, () -> new RdfDbDifferenceSink(db, blank))
                        .getMessage()).contains("must not be blank");
                assertThat(assertThrows(RdfDbException.class, () -> db.catalog(blank)).getMessage())
                        .contains("must not be blank");
            }
        }
    }

    // ------------------------------------------------------------------ large writes

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aLargeSetUsesTwoPhasesAndReadsBackEqual(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            StoredModel base = db.catalog(S).full(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
            int statements = RdfDbDifferenceSink.SINGLE_REQUEST_MAX_STATEMENTS / 2 + 500;
            List<CgmesStatement> forward = new ArrayList<>();
            List<CgmesStatement> reverse = new ArrayList<>();
            for (int i = 0; i < statements; i++) {
                forward.add(CgmesStatement.literal("synthetic-" + i, null, "EnergyConsumer.p", String.valueOf(i)));
                reverse.add(CgmesStatement.literal("synthetic-" + i, null, "EnergyConsumer.p", "0"));
            }
            DifferenceModelHeader header = DifferenceModelHeader
                    .builder("urn:uuid:diff-large", CgmesSubset.STEADY_STATE_HYPOTHESIS, base.cimNamespace())
                    .version(2).supersedes(List.of(base.id())).build();
            RdfDbDifferenceSink sink = new RdfDbDifferenceSink(db, S);
            sink.accept(new DifferenceModelSet(List.of(new DifferenceModel(header, forward, reverse, List.of()))));

            StoredModel node = sink.stored().get(0);
            assertThat(node.tripleCount()).isEqualTo(2L * statements);
            DifferenceModel read = RdfDbDiffSource.fetch(db, node);
            assertThat(read.forward()).containsExactlyInAnyOrderElementsOf(forward);
            assertThat(read.reverse()).containsExactlyInAnyOrderElementsOf(reverse);
        }
    }

    /** A large difference with nothing to undo takes the two-phase route with one data graph only. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aLargeSetWithAnEmptyReverseUsesTwoPhasesAndReadsBackEqual(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            StoredModel base = db.catalog(S).full(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
            List<CgmesStatement> forward = new ArrayList<>();
            for (int i = 0; i <= RdfDbDifferenceSink.SINGLE_REQUEST_MAX_STATEMENTS; i++) {
                forward.add(CgmesStatement.literal("synthetic-" + i, null, "EnergyConsumer.p", String.valueOf(i)));
            }
            DifferenceModelHeader header = DifferenceModelHeader
                    .builder("urn:uuid:diff-large-forward", CgmesSubset.STEADY_STATE_HYPOTHESIS, base.cimNamespace())
                    .version(2).supersedes(List.of(base.id())).build();
            RdfDbDifferenceSink sink = new RdfDbDifferenceSink(db, S);
            sink.accept(new DifferenceModelSet(List.of(new DifferenceModel(header, forward, List.of(), List.of()))));

            DifferenceModel read = RdfDbDiffSource.fetch(db, sink.stored().get(0));
            assertThat(read.forward()).containsExactlyInAnyOrderElementsOf(forward);
            assertThat(read.reverse()).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aLostRaceInTheTwoPhaseWriteLeavesNoOrphanGraph(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            StoredModel base = db.catalog(S).full(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
            int statements = RdfDbDifferenceSink.SINGLE_REQUEST_MAX_STATEMENTS / 2 + 500;
            List<CgmesStatement> forward = new ArrayList<>();
            List<CgmesStatement> reverse = new ArrayList<>();
            for (int i = 0; i < statements; i++) {
                forward.add(CgmesStatement.literal("synthetic-" + i, null, "EnergyConsumer.p", String.valueOf(i)));
                reverse.add(CgmesStatement.literal("synthetic-" + i, null, "EnergyConsumer.p", "0"));
            }
            DifferenceModelHeader header = DifferenceModelHeader
                    .builder("urn:uuid:diff-race", CgmesSubset.STEADY_STATE_HYPOTHESIS, base.cimNamespace())
                    .version(2).supersedes(List.of(base.id())).build();
            RdfDbDifferenceSink sink = new RdfDbDifferenceSink(db, S);
            // Another writer takes the base between the two phases, exactly as a concurrent recorder would
            sink.beforeMetadataWrite(() ->
                    new RdfDbDifferenceSink(db, S).accept(handMade("urn:uuid:diff-winner", List.of(base.id()))));
            DifferenceModelSet set =
                    new DifferenceModelSet(List.of(new DifferenceModel(header, forward, reverse, List.of())));
            assertThatThrownBy(() -> sink.accept(set))
                    .isInstanceOf(RdfDbConflictException.class);
            assertThat(db.catalog(S).model("urn:uuid:diff-race").isPresent()).isFalse();
            assertThat(db.catalog(S).orphanGraphs()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static DifferenceModelSet handMade(String id, List<String> supersedes) {
        DifferenceModelHeader header = DifferenceModelHeader
                .builder(id, CgmesSubset.STEADY_STATE_HYPOTHESIS, "http://iec.ch/TC57/2013/CIM-schema-cim16#")
                .version(2).supersedes(supersedes).build();
        return new DifferenceModelSet(List.of(new DifferenceModel(header,
                List.of(CgmesStatement.literal(Changes.LOAD_ID, null, "EnergyConsumer.p", "1")),
                List.of(CgmesStatement.literal(Changes.LOAD_ID, null, "EnergyConsumer.p", "0")), List.of())));
    }

    private static long count(RdfDbConnection db, String graph) {
        return Long.parseLong(db.sparql(S)
                .select("SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + graph + "> { ?s ?p ?o } }")
                .get(0).get("n").stringValue());
    }
}
