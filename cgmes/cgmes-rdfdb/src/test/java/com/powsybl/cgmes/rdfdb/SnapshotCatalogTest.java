/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.commons.report.ReportNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.powsybl.cgmes.rdfdb.Backends.BASE;
import static com.powsybl.cgmes.rdfdb.Backends.BE;
import static com.powsybl.cgmes.rdfdb.Backends.CGMES_FULL_SSH;
import static com.powsybl.cgmes.rdfdb.Backends.NL;
import static com.powsybl.cgmes.rdfdb.Backends.cgmesFull;
import static com.powsybl.cgmes.rdfdb.Backends.microGridBe;
import static com.powsybl.cgmes.rdfdb.Backends.microGridNl;
import static com.powsybl.cgmes.rdfdb.Backends.params;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Writing and reading snapshots: the rules of the version chain, the trees of the modelling authorities of a
 * scenario and their shared boundary, and the isolation between scenarios.
 *
 * <p>Every case runs on both backends, and every one of them keeps a second scenario {@code "other"} in the same
 * database holding <em>the same files</em>. That is the hard case for scenario isolation: the model identifiers
 * are identical on both sides, so nothing but the graph scoping keeps the two apart.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class SnapshotCatalogTest {

    private static final String S = "2016-01-01";
    private static final String OTHER = "other";
    private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
    private static final CgmesSubset SSH = CgmesSubset.STEADY_STATE_HYPOTHESIS;
    private static final CgmesSubset EQ = CgmesSubset.EQUIPMENT;
    private static final Instant NOON = Instant.parse("2014-06-01T12:30:00Z");

    private static RdfDbConnection open(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "snapshots"));
        db.clear(S);
        db.clear(OTHER);
        return db;
    }

    private static SnapshotInfo root(RdfDbConnection db, String scenario, Integer version) {
        return db.snapshots(scenario).putFull(microGridBe(), null, SnapshotRef.of(scenario, BE, null,
                version == null ? null : version.toString()), Set.of(), params(), ReportNode.NO_OP);
    }

    private static SnapshotRef at(String scenario, Integer version) {
        return SnapshotRef.of(scenario, BE, null, version == null ? null : version.toString());
    }

    private static SnapshotRef at(String scenario, String version) {
        return SnapshotRef.of(scenario, BE, null, version);
    }

    /** A difference of one profile superseding what the given snapshot states for it. */
    static DifferenceModelSet change(SnapshotInfo parent, CgmesSubset subset, String id, String value) {
        DifferenceModelHeader header = DifferenceModelHeader.builder(id, subset, CIM16)
                .supersedes(List.of(parent.state().get(subset)))
                .profiles(List.of("http://entsoe.eu/CIM/SteadyStateHypothesis/1/1"))
                .build();
        CgmesStatement forward = CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer", "EnergyConsumer.p", value);
        CgmesStatement reverse = CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer", "EnergyConsumer.p", "0.0");
        return new DifferenceModelSet(List.of(new DifferenceModel(header, List.of(forward), List.of(reverse),
                List.of())));
    }

    // ------------------------------------------------------------------ the root

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void putFullCreatesRoot(String backend) {
        try (RdfDbConnection db = open(backend)) {
            // No version given: a root is version 1; no authority given: the one the files state
            SnapshotInfo root = db.snapshots(S).putFull(microGridBe(), null, SnapshotRef.latest(S, null), null,
                    params(), ReportNode.NO_OP);

            assertThat(root.version()).isEqualTo("1");
            assertThat(root.modellingAuthority()).isEqualTo(BE);
            assertThat(root.timestamp()).isEqualTo(BASE);
            assertThat(root.kind()).isEqualTo(SnapshotInfo.Kind.FULL);
            assertThat(root.depth()).isZero();
            assertThat(root.hasFull()).isTrue();
            assertThat(root.isRoot()).isTrue();
            assertThat(root.edge()).isEqualTo(SnapshotInfo.EdgeKind.NONE);
            assertThat(root.timestampRoot()).isEqualTo(root.iri());
            assertThat(root.state()).containsKeys(EQ, SSH, CgmesSubset.TOPOLOGY, CgmesSubset.STATE_VARIABLES);
            assertThat(root.profiles()).isEqualTo(root.state().keySet());
            assertThat(root.state()).isEqualTo(root.fullModels());
            assertThat(root.members()).hasSameSizeAs(root.state().values());
            assertThat(root.iri()).isEqualTo(RdfDbNames.snapshot(S, BE, BASE, "1"));
            assertThat(root.ref()).isEqualTo(SnapshotRef.of(S, BE, BASE, "1"));

            SnapshotCatalog catalog = db.snapshots(S);
            assertThat(catalog.isVersioned()).isTrue();
            assertThat(catalog.modellingAuthorities()).containsExactly(BE);
            assertThat(catalog.baseTimestamp(BE)).isEqualTo(BASE);
            assertThat(catalog.find(at(S, 1))).contains(root);
            assertThat(catalog.find(SnapshotRef.latest(S, BE))).contains(root);
            assertThat(catalog.find(SnapshotRef.latest(S, NL))).isEmpty();
            assertThat(catalog.snapshots()).containsExactly(root);
            assertThat(catalog.nextVersionName(SnapshotRef.latest(S, BE))).isEqualTo("2");
            // The versioned graphs are not instance file contexts of the scenario
            assertThat(db.contextNames(S)).isEmpty();
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void putFullTwiceRejected(String backend) {
        try (RdfDbConnection db = open(backend)) {
            root(db, S, 1);
            assertThatThrownBy(() -> root(db, S, 2))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("modelling authority '" + BE + "'")
                    .hasMessageContaining("already has a root snapshot");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anExplicitAuthorityStoresFilesOfAnyAuthorityUnderIt(String backend) {
        try (RdfDbConnection db = open(backend)) {
            // The MicroGrid BE files stored as NL's tree: the address decides, not the files
            SnapshotInfo nl = db.snapshots(S).putFull(microGridBe(), null, SnapshotRef.latest(S, NL), null,
                    params(), ReportNode.NO_OP);
            assertThat(nl.modellingAuthority()).isEqualTo(NL);
            // A realistic IGM: EQ/TP of one party, SSH of the TSO, SV of the merging agent
            SnapshotInfo full = db.snapshots(OTHER).putFull(cgmesFull(), null,
                    SnapshotRef.latest(OTHER, CGMES_FULL_SSH), null, params(), ReportNode.NO_OP);
            assertThat(full.modellingAuthority()).isEqualTo(CGMES_FULL_SSH);
            assertThat(full.profiles()).contains(EQ, SSH, CgmesSubset.TOPOLOGY, CgmesSubset.STATE_VARIABLES);
            db.snapshots(OTHER).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void withoutAnAuthorityTheEquipmentAndSteadyStateFilesMustAgree(String backend) {
        try (RdfDbConnection db = open(backend)) {
            assertThatThrownBy(() -> db.snapshots(S).putFull(cgmesFull(), null, SnapshotRef.latest(S, null), null,
                    params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("EQ=powsybl.org")
                    .hasMessageContaining("SSH=" + CGMES_FULL_SSH)
                    .hasMessageContaining("SV=http://tennet.nl/CGMES")
                    .hasMessageContaining("pass the modelling authority in the address");
            assertThat(db.snapshots(S).snapshots()).isEmpty();
            // The state variables of another party never decide: without SSH in the projection, EQ does
            SnapshotInfo eqTp = db.snapshots(S).putFull(cgmesFull(), null, SnapshotRef.latest(S, null),
                    Set.of(EQ, CgmesSubset.TOPOLOGY, CgmesSubset.STATE_VARIABLES), params(), ReportNode.NO_OP);
            assertThat(eqTp.modellingAuthority()).isEqualTo("powsybl.org");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anOpenAuthorityIntoAScenarioOfOneTreeRefusesFilesThatAgreeOnAnother(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo be = root(db, S, null);
            SnapshotCatalog catalog = db.snapshots(S);
            String refusal = "modelling authority " + NL + " but the scenario's only tree is " + BE + ": pass " + BE
                    + " in the address to store them under it, or " + NL + " to open a second tree";
            // NL's files, authority left open: neither silently a second tree nor silently diffed into BE's
            assertThatThrownBy(() -> catalog.putFull(microGridNl(), null, SnapshotRef.latest(S, null), null,
                    params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("the instance files of scenario '" + S + "' state " + refusal);
            assertThatThrownBy(() -> catalog.putAsDiff(microGridNl(), null, SnapshotRef.latest(S, null), null,
                    params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("the instance files of scenario '" + S + "' state " + refusal);
            assertThatThrownBy(() -> catalog.putDiff(authored(be, NL, NL), SnapshotRef.latest(S, null)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("the difference models of scenario '" + S + "' state " + refusal);
            assertThat(catalog.snapshots()).containsExactly(be);
            // Members that do not agree decide nothing, and the open authority is the only tree
            assertThat(catalog.putDiff(authored(be, NL, BE), SnapshotRef.latest(S, null)).modellingAuthority())
                    .isEqualTo(BE);
            // Named, either is taken: the address decides
            assertThat(catalog.putFull(microGridNl(), null, SnapshotRef.latest(S, NL), null, params(),
                    ReportNode.NO_OP).modellingAuthority()).isEqualTo(NL);
            catalog.verify();
        }
    }

    /** An equipment and a steady state hypothesis difference on top of a snapshot, stating the given authorities. */
    private static DifferenceModelSet authored(SnapshotInfo parent, String eqAuthority, String sshAuthority) {
        return new DifferenceModelSet(List.of(authored(parent, EQ, "urn:uuid:eq-authored", eqAuthority,
                        CgmesStatement.literal("_line", "IdentifiedObject", "IdentifiedObject.description", "renamed")),
                authored(parent, SSH, "urn:uuid:ssh-authored", sshAuthority,
                        CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer", "EnergyConsumer.p", "13.0"))));
    }

    private static DifferenceModel authored(SnapshotInfo parent, CgmesSubset subset, String id, String authority,
                                            CgmesStatement forward) {
        DifferenceModelHeader header = DifferenceModelHeader.builder(id, subset, CIM16)
                .supersedes(List.of(parent.state().get(subset)))
                .modelingAuthoritySet(authority)
                .build();
        return new DifferenceModel(header, List.of(forward), List.of(), List.of());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aDifferenceWithoutEquipmentOrSteadyStateMustNameItsAuthority(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo be = root(db, S, null);
            // State variables recorded from a merged model: the merging agent's header must not pick the tree
            CgmesSubset sv = CgmesSubset.STATE_VARIABLES;
            DifferenceModelHeader header = DifferenceModelHeader.builder("urn:uuid:sv-merged", sv, CIM16)
                    .supersedes(List.of(be.state().get(sv)))
                    .modelingAuthoritySet("http://merging.agent/CGMES")
                    .build();
            CgmesStatement forward = CgmesStatement.literal("_bus", "SvVoltage", "SvVoltage.v", "231.0");
            CgmesStatement reverse = CgmesStatement.literal("_bus", "SvVoltage", "SvVoltage.v", "230.0");
            DifferenceModelSet svOnly = new DifferenceModelSet(List.of(new DifferenceModel(header,
                    List.of(forward), List.of(reverse), List.of())));
            SnapshotCatalog catalog = db.snapshots(S);

            assertThatThrownBy(() -> catalog.putDiff(svOnly, SnapshotRef.latest(S, null)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("SV=http://merging.agent/CGMES")
                    .hasMessageContaining("only those two decide the modelling authority")
                    .hasMessageContaining("pass the modelling authority in the address");
            assertThat(catalog.snapshots()).containsExactly(be);
            // Named, it is the address's tree, whatever the header states
            assertThat(catalog.putDiff(svOnly, SnapshotRef.latest(S, BE)).modellingAuthority()).isEqualTo(BE);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void rootPerScenario(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo here = root(db, S, 1);
            SnapshotInfo there = root(db, OTHER, 1);

            assertThat(here.iri()).isNotEqualTo(there.iri());
            assertThat(db.snapshots(S).snapshots()).containsExactly(here);
            assertThat(db.snapshots(OTHER).snapshots()).containsExactly(there);
            assertThat(db.scenarios()).contains(S, OTHER);
            // The same files, hence the same model identifiers, in two independent sets of graphs
            assertThat(here.state()).isEqualTo(there.state());
            assertThat(db.versionGraph(S).materialization(here.iri()).startModel()
                    .get(SSH).graph()).startsWith(RdfDbNames.scenarioPrefix(S));
            assertThat(db.versionGraph(OTHER).materialization(there.iri()).startModel()
                    .get(SSH).graph()).startsWith(RdfDbNames.scenarioPrefix(OTHER));
            db.snapshots(S).verify();
            db.snapshots(OTHER).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void refOfOtherScenarioRejected(String backend) {
        try (RdfDbConnection db = open(backend)) {
            root(db, S, 1);
            assertThatThrownBy(() -> db.snapshots(S).find(at(OTHER, 1)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("cannot address scenario 'other'");
            assertThatThrownBy(() -> db.snapshots(S).find(SnapshotRef.latest(S, null)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("names no modelling authority")
                    .hasMessageContaining(BE);
        }
    }

    // ------------------------------------------------------------------ two modelling authorities, one boundary

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void twoModellingAuthoritiesShareTheBoundaryOfTheirScenario(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo be = root(db, S, null);
            // A second tree is opened by naming it (left open, NL's files are refused: the scenario's only tree is BE)
            SnapshotInfo nl = catalog.putFull(microGridNl(), null, SnapshotRef.latest(S, NL), null, params(),
                    ReportNode.NO_OP);

            assertThat(nl.modellingAuthority()).isEqualTo(NL);
            assertThat(nl.version()).isEqualTo("1");
            assertThat(catalog.modellingAuthorities()).containsExactly(BE, NL);
            // The second root links the stored boundary instead of uploading it again
            assertThat(nl.state().get(CgmesSubset.EQUIPMENT_BOUNDARY))
                    .isEqualTo(be.state().get(CgmesSubset.EQUIPMENT_BOUNDARY)).isNotNull();
            assertThat(nl.state().get(CgmesSubset.TOPOLOGY_BOUNDARY))
                    .isEqualTo(be.state().get(CgmesSubset.TOPOLOGY_BOUNDARY)).isNotNull();
            assertThat(nl.state().get(EQ)).isNotEqualTo(be.state().get(EQ));
            assertThat(db.catalog(S).model(be.state().get(CgmesSubset.EQUIPMENT_BOUNDARY)).orElseThrow()
                    .isBoundary()).isTrue();
            // Each authority is its own tree: versions and heads never mix
            SnapshotInfo be2 = catalog.putDiff(change(be, SSH, "urn:uuid:ssh-be", "12.0"), SnapshotRef.latest(S, BE));
            assertThat(be2.version()).isEqualTo("2");
            assertThat(catalog.find(SnapshotRef.latest(S, NL))).contains(nl);
            assertThat(catalog.nextVersionName(SnapshotRef.latest(S, NL))).isEqualTo("2");
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aSecondRootWithAnotherBoundaryIsRefused(String backend) {
        try (RdfDbConnection db = open(backend)) {
            root(db, S, 1);
            assertThatThrownBy(() -> db.snapshots(S).putFull(TimestampFixtures.changedBoundaryNl("nl"), null,
                    SnapshotRef.latest(S, NL), null, params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("a new boundary is a new scenario");
            assertThat(db.snapshots(S).modellingAuthorities()).containsExactly(BE);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void twoFirstRootsWithDifferentBoundariesCannotBothBeWritten(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotCatalog nlWriter = new SnapshotCatalog(db, S);
            // The NL writer has found the scenario empty and parsed its files; BE becomes the first root meanwhile
            nlWriter.beforeRootWrite(() -> root(db, S, 1));
            assertThatThrownBy(() -> nlWriter.putFull(TimestampFixtures.changedBoundaryNl("race"), null,
                    SnapshotRef.latest(S, NL), null, params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("another writer created the root");
            assertThat(db.snapshots(S).modellingAuthorities()).containsExactly(BE);
            // The loser's uploaded graphs are dropped again
            assertThat(Backends.count(db, S, RdfDbNames.fullGraph(S, "urn:uuid:eqbd-race"), "?s ?p ?o")).isZero();
            db.snapshots(S).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anAssemblyIsEveryModellingAuthorityAtOneMoment(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo be = root(db, S, null);
            SnapshotInfo nl = catalog.putFull(microGridNl(), null, SnapshotRef.latest(S, NL), null, params(),
                    ReportNode.NO_OP);
            SnapshotInfo be2 = catalog.putDiff(change(be, SSH, "urn:uuid:ssh-be", "12.0"), SnapshotRef.latest(S, BE));

            Map<String, SnapshotInfo> heads = catalog.assembly(BASE, null);
            assertThat(heads).containsOnlyKeys(BE, NL);
            assertThat(heads.get(BE)).isEqualTo(be2);
            assertThat(heads.get(NL)).isEqualTo(nl);
            assertThat(catalog.assembly(BASE, "1")).containsExactlyInAnyOrderEntriesOf(Map.of(BE, be, NL, nl));
            assertThat(catalog.assembly(BASE, "2")).containsOnlyKeys(BE);
            assertThat(catalog.assembly(NOON, null)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ the version chain

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void putDiffCreatesChild(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo base = root(db, S, 1);
            SnapshotCatalog catalog = db.snapshots(S);

            SnapshotInfo v2 = catalog.putDiff(change(base, SSH, "urn:uuid:ssh-d2", "12.0"), at(S, 2));

            assertThat(v2.version()).isEqualTo("2");
            assertThat(v2.timestamp()).isEqualTo(BASE);
            assertThat(v2.modellingAuthority()).isEqualTo(BE);
            assertThat(v2.kind()).isEqualTo(SnapshotInfo.Kind.DIFF);
            assertThat(v2.parent()).isEqualTo(base.iri());
            assertThat(v2.edge()).isEqualTo(SnapshotInfo.EdgeKind.VERSION);
            assertThat(v2.depth()).isEqualTo(1);
            assertThat(v2.hasFull()).isFalse();
            assertThat(v2.fast()).isTrue();
            assertThat(v2.members()).containsExactly("urn:uuid:ssh-d2");
            assertThat(v2.state().get(SSH)).isEqualTo("urn:uuid:ssh-d2");
            assertThat(v2.state().get(EQ)).isEqualTo(base.state().get(EQ));
            assertThat(v2.timestampRoot()).isEqualTo(base.iri());
            assertThat(catalog.find(SnapshotRef.latest(S, BE))).contains(v2);
            assertThat(catalog.nextVersionName(SnapshotRef.latest(S, BE))).isEqualTo("3");
            assertThat(db.catalog(S).model("urn:uuid:ssh-d2")).isPresent();
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void versionsRankAboveTheirParentAndNamesMayBeSparse(String backend) {
        try (RdfDbConnection db = open(backend)) {
            db.snapshots(S).registry().create(List.of("10", "20", "30"), false);
            SnapshotInfo base = root(db, S, 10);
            SnapshotCatalog catalog = db.snapshots(S);
            // A name may skip registered ones
            SnapshotInfo v30 = catalog.putDiff(change(base, SSH, "urn:uuid:ssh-a", "12.0"), at(S, 30));
            assertThat(v30.version()).isEqualTo("30");
            assertThat(v30.rank()).isEqualTo(30);
            // Not above the head: refused, naming both ranks
            assertThatThrownBy(() -> catalog.putDiff(change(v30, SSH, "urn:uuid:ssh-c", "14.0"), at(S, 20)))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("version '20' (rank 20) is not above the parent '30' (rank 30) of (" + S
                            + ", " + BE + ", " + BASE + "): a new version ranks above the head it is written on");
            // Strict: a name that is not registered is refused, naming the registry
            assertThatThrownBy(() -> catalog.putDiff(change(v30, SSH, "urn:uuid:ssh-c", "14.0"), at(S, 40)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("version '40' is not registered in scenario '" + S + "' (registry: [10 10,"
                            + " 20 20, 30 30]); register it or write into a permissive scenario");
            catalog.registry().add("40");
            SnapshotInfo v40 = catalog.putDiff(change(v30, SSH, "urn:uuid:ssh-c", "14.0"), at(S, 40));
            assertThat(catalog.versions(BE, null)).extracting(SnapshotInfo::version).containsExactly("10", "30", "40");
            assertThat(catalog.versions(BE, null)).extracting(SnapshotInfo::rank).containsExactly(10, 30, 40);
            assertThat(catalog.find(SnapshotRef.latest(S, BE))).contains(v40);
            catalog.verify();

            // Permissive, as a scenario whose root was written without a registry: unknown names are appended
            SnapshotInfo other = root(db, OTHER, 1);
            SnapshotCatalog permissive = db.snapshots(OTHER);
            SnapshotInfo seven = permissive.putDiff(change(other, SSH, "urn:uuid:ssh-o7", "12.0"), at(OTHER, 7));
            assertThat(seven.rank()).isEqualTo(20);
            // No name and nothing registered above: the generated name is the next number the registry lacks
            SnapshotInfo next = permissive.putDiff(change(seven, SSH, "urn:uuid:ssh-o3", "13.0"), SnapshotRef.latest(OTHER, BE));
            assertThat(next.version()).isEqualTo("3");
            assertThat(next.rank()).isEqualTo(30);
            assertThat(permissive.registry().names()).containsExactly("1", "7", "3");
            permissive.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aNullVersionTakesTheNextRegisteredName(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            catalog.registry().create(List.of("DA", "ID", "RT"), false);
            SnapshotInfo da = catalog.putFull(microGridBe(), null, SnapshotRef.latest(S, BE), null, params(),
                    ReportNode.NO_OP);
            assertThat(da.version()).isEqualTo("DA");
            assertThat(catalog.nextVersionName(SnapshotRef.latest(S, BE))).isEqualTo("ID");
            SnapshotInfo id = catalog.putDiff(change(da, SSH, "urn:uuid:ssh-id", "12.0"), SnapshotRef.latest(S, BE));
            assertThat(id.version()).isEqualTo("ID");
            SnapshotInfo rt = catalog.putDiff(change(id, SSH, "urn:uuid:ssh-rt", "13.0"), SnapshotRef.latest(S, BE));
            assertThat(rt.version()).isEqualTo("RT");
            // Strict, and nothing ranks above RT
            assertThatThrownBy(() -> catalog.nextVersionName(SnapshotRef.latest(S, BE)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("no version of the registry ranks above 'RT' (rank 30) in scenario '" + S
                            + "'");
            assertThatThrownBy(() -> catalog.putDiff(change(rt, SSH, "urn:uuid:ssh-x", "14.0"),
                    SnapshotRef.latest(S, BE))).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("no version of the registry ranks above 'RT'");
            // A new timestamp starts at the lowest registered name, whatever the snapshot it hangs off carries
            SnapshotInfo noon = catalog.putDiff(change(rt, SSH, "urn:uuid:ssh-noon", "15.0"),
                    SnapshotRef.latestAt(S, BE, NOON));
            assertThat(noon.version()).isEqualTo("DA");
            assertThat(noon.edge()).isEqualTo(SnapshotInfo.EdgeKind.TIMESTAMP);
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anAutoAppendedNameWinsOrRetries(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo base = root(db, S, 1);
            // Two writers with registries of their own, both at revision 1
            SnapshotCatalog mine = new SnapshotCatalog(db, S);
            SnapshotCatalog theirs = new SnapshotCatalog(db, S);
            assertThat(mine.registry().rev()).isEqualTo(1);
            assertThat(theirs.registry().rev()).isEqualTo(1);
            AtomicBoolean once = new AtomicBoolean();
            // Between my check and my append, they append a name of their own at the rank I am about to take
            mine.registry().beforeRegistryWrite(() -> {
                if (once.compareAndSet(false, true)) {
                    theirs.putDiff(change(base, SSH, "urn:uuid:ssh-y", "11.0"), SnapshotRef.of(S, BE, NOON, "Y"));
                }
            });
            SnapshotInfo x = mine.putDiff(change(base, SSH, "urn:uuid:ssh-x", "12.0"), at(S, "X"));

            VersionRegistry registry = db.snapshots(S).registry();
            registry.refresh();
            assertThat(registry.ranks()).containsExactly(Map.entry("1", 10), Map.entry("Y", 20),
                    Map.entry("X", 30));
            assertThat(x.rank()).isEqualTo(30);
            assertThat(db.snapshots(S).require(SnapshotRef.of(S, BE, NOON, "Y")).rank()).isEqualTo(20);
            db.snapshots(S).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aRegistryEditedElsewhereRefusesTheWriteOnceAndRetries(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo base = root(db, S, 1);
            db.snapshots(S).registry().add("2");
            SnapshotCatalog stale = new SnapshotCatalog(db, S);
            assertThat(stale.registry().rev()).isEqualTo(2);
            // Edited through another catalogue: the stale one does not know
            db.snapshots(S).registry().add("3");
            assertThat(stale.registry().rev()).isEqualTo(2);

            SnapshotInfo two = stale.putDiff(change(base, SSH, "urn:uuid:ssh-2", "12.0"), at(S, 2));
            // The write was refused on the revision, the registry re-read and the write retried
            assertThat(stale.registry().rev()).isEqualTo(3);
            assertThat(two.version()).isEqualTo("2");
            assertThat(two.rank()).isEqualTo(20);
            db.snapshots(S).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void putDiffRejectsFork(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo base = root(db, S, 1);
            SnapshotCatalog catalog = db.snapshots(S);
            catalog.putDiff(change(base, SSH, "urn:uuid:ssh-a", "12.0"), at(S, 2));

            // A second writer that still thinks the head is 1
            assertThatThrownBy(() -> catalog.putDiff(change(base, SSH, "urn:uuid:ssh-b", "13.0"), at(S, 3)))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("update the network to the head");
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void putDiffRejectsWrongSupersedes(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo base = root(db, S, 1);
            SnapshotCatalog catalog = db.snapshots(S);
            catalog.putDiff(change(base, SSH, "urn:uuid:ssh-a", "12.0"), at(S, 2));

            assertThatThrownBy(() -> catalog.putDiff(change(base, SSH, "urn:uuid:ssh-c", "14.0"), at(S, 3)))
                    .isInstanceOf(RdfDbConflictException.class)
                    .hasMessageContaining("supersedes");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void putDiffOfOtherScenarioRejected(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo base = root(db, S, 1);
            root(db, OTHER, 1);
            assertThatThrownBy(() -> db.snapshots(S).putDiff(change(base, SSH, "urn:uuid:x", "1.0"), at(OTHER, 2)))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("cannot address scenario 'other'");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void putDiffAtAnUnknownTimestampCreatesItsRoot(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo base = root(db, S, 1);
            SnapshotCatalog catalog = db.snapshots(S);

            SnapshotInfo root = catalog.putDiff(change(base, SSH, "urn:uuid:ssh-a", "12.0"),
                    SnapshotRef.latestAt(S, BE, NOON));

            assertThat(root.timestamp()).isEqualTo(NOON);
            assertThat(root.version()).isEqualTo("1");
            assertThat(root.edge()).isEqualTo(SnapshotInfo.EdgeKind.TIMESTAMP);
            assertThat(root.parent()).isEqualTo(base.iri());
            assertThat(root.timestampRoot()).isEqualTo(root.iri());
            assertThat(catalog.timestamps(BE)).extracting(SnapshotCatalog.TimestampInfo::timestamp)
                    .containsExactly(BASE, NOON);
            catalog.verify();
        }
    }

    // ------------------------------------------------------------------ isolation and housekeeping

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void identicalChainsInTwoScenariosDoNotInterfere(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo here = root(db, S, 1);
            SnapshotInfo there = root(db, OTHER, 1);
            db.snapshots(S).putDiff(change(here, SSH, "urn:uuid:ssh-s", "12.0"), at(S, 2));
            db.snapshots(OTHER).putDiff(change(there, SSH, "urn:uuid:ssh-o", "13.0"), at(OTHER, 2));

            assertThat(db.snapshots(S).snapshots()).hasSize(2);
            assertThat(db.snapshots(OTHER).snapshots()).hasSize(2);
            assertThat(db.snapshots(S).find(SnapshotRef.latest(S, BE)).orElseThrow().state().get(SSH))
                    .isEqualTo("urn:uuid:ssh-s");
            assertThat(db.snapshots(OTHER).find(SnapshotRef.latest(OTHER, BE)).orElseThrow().state().get(SSH))
                    .isEqualTo("urn:uuid:ssh-o");
            db.snapshots(S).verify();
            db.snapshots(OTHER).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void dropAllRemovesOnlyThisScenario(String backend) {
        try (RdfDbConnection db = open(backend)) {
            SnapshotInfo here = root(db, S, 1);
            root(db, OTHER, 1);
            db.snapshots(S).putDiff(change(here, SSH, "urn:uuid:ssh-s", "12.0"), at(S, 2));

            db.snapshots(S).dropAll();

            assertThat(db.snapshots(S).isVersioned()).isFalse();
            assertThat(db.snapshots(S).snapshots()).isEmpty();
            assertThat(db.snapshots(OTHER).snapshots()).hasSize(1);
            db.snapshots(OTHER).verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void blankScenarioRejected(String backend) {
        try (RdfDbConnection db = open(backend)) {
            assertThatThrownBy(() -> db.snapshots("")).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("must not be blank");
            assertThatThrownBy(() -> db.versionGraph(" ")).isInstanceOf(RdfDbException.class);
            assertThatThrownBy(() -> SnapshotRef.latest(null, BE)).isInstanceOf(RdfDbException.class);
        }
    }
}
