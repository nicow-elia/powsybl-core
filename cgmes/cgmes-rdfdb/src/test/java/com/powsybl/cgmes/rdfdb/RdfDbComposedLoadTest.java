/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static com.powsybl.cgmes.rdfdb.Backends.BASE;
import static com.powsybl.cgmes.rdfdb.Backends.BE;
import static com.powsybl.cgmes.rdfdb.Backends.NL;
import static com.powsybl.cgmes.rdfdb.Backends.microGridBe;
import static com.powsybl.cgmes.rdfdb.Backends.microGridNl;
import static com.powsybl.cgmes.rdfdb.Backends.params;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Several modelling authorities of one moment loaded as one network: a CGM is a query, and a load.
 *
 * <p>The fixture is the MicroGrid day of two TSOs: the BE and the NL base case stored as the roots of their trees
 * in one scenario, NL linking the boundary BE stored. Composed, they are the network the assembled CGM files give
 * &mdash; one store, the boundary once, every graph speaking the first authority's subject base, and the flat
 * conversion pairing the tie lines on the boundary nodes.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbComposedLoadTest {

    private static final String S = "2016-01-01";
    private static final Set<String> IDENTITY = Set.of("cgmesMetadataModels", "rdfDbProvenance");
    private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";

    private static RdfDbConnection day(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "composed"));
        db.clear(S);
        db.snapshots(S).putFull(microGridBe(), null, SnapshotRef.of(S, BE, null, "1"), null, params(),
                ReportNode.NO_OP);
        db.snapshots(S).putFull(microGridNl(), null, SnapshotRef.of(S, NL, null, "1"), null, params(),
                ReportNode.NO_OP);
        return db;
    }

    private static RdfDbNetworkLoader.LoadResult composed(RdfDbConnection db, String version,
                                                          List<String> authorities) {
        return RdfDbNetworkLoader.loadComposed(db, SnapshotRef.of(S, null, null, version), authorities, null, null,
                null, params(), ReportNode.NO_OP);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void twoAuthoritiesComposeIntoTheAssembledNetwork(String backend) {
        try (RdfDbConnection db = day(backend)) {
            Network assembled = Network.read(CgmesConformity1Catalog.microGridBaseCaseAssembled().dataSource(),
                    params());

            RdfDbNetworkLoader.LoadResult result = composed(db, null, List.of(BE, NL));
            Network network = result.network();

            assertThat(network.getTieLineCount()).isEqualTo(assembled.getTieLineCount()).isPositive();
            assertThat(network.getSubnetworks()).isEmpty();
            // XIIDM-identical but for the network identifier (one of the EQ model ids) and the identity extensions;
            // the assembled SV file is the two IGMs' state variables, which the composition reads both of
            Networks.assertSameNetwork(assembled, network, IDENTITY);
            // The boundary once: every graph of both trees but the two boundary graphs NL links
            assertThat(result.statistics().graphs()).isEqualTo(9 + 9 - 2);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void theProvenanceNamesTheCompositionAndTheOwnerOfEverySubject(String backend) {
        try (RdfDbConnection db = day(backend)) {
            SnapshotInfo be = db.snapshots(S).require(SnapshotRef.latest(S, BE));
            SnapshotInfo nl = db.snapshots(S).require(SnapshotRef.latest(S, NL));
            Network beAlone = RdfDbNetworkLoader.load(db, be.ref(), null, params(), ReportNode.NO_OP);
            Network nlAlone = RdfDbNetworkLoader.load(db, nl.ref(), null, params(), ReportNode.NO_OP);

            Network network = composed(db, null, List.of(BE, NL)).network();
            RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);

            assertThat(provenance.composition()).extracting(SnapshotInfo::iri).containsExactly(be.iri(), nl.iri());
            assertThat(provenance.owned()).containsExactly(BE);
            assertThat(provenance.snapshot()).isEmpty();
            assertThat(provenance.modelIds()).isEqualTo(be.state());
            assertThat(provenance.ownerOf(Changes.LOAD_ID)).contains(BE);
            assertThat(nlAlone.getLoadStream().map(l -> provenance.ownerOf(l.getId())).toList())
                    .isNotEmpty().allMatch(owner -> owner.equals(Optional.of(NL)));
            assertThat(beAlone.getGeneratorStream().map(g -> provenance.ownerOf(g.getId())).toList())
                    .isNotEmpty().allMatch(owner -> owner.equals(Optional.of(BE)));
            assertThat(provenance.ownerOf("no-such-object")).isEmpty();

            // owned names who may write; it is a subset of the composition
            Network both = RdfDbNetworkLoader.loadComposed(db, SnapshotRef.of(S, null, null, null), List.of(BE, NL),
                    List.of(NL, BE), null, null, params(), ReportNode.NO_OP).network();
            assertThat(both.getExtension(RdfDbProvenance.class).owned()).containsExactly(NL, BE);
            assertThatThrownBy(() -> RdfDbNetworkLoader.loadComposed(db, SnapshotRef.of(S, null, null, null),
                    List.of(BE), List.of(NL), null, null, params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class).hasMessageContaining(NL)
                    .hasMessageContaining("not in the composition");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void theFirstAuthorityWinsAPropertyBothState(String backend) {
        try (RdfDbConnection db = day(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            double original = RdfDbNetworkLoader.load(db, SnapshotRef.latest(S, BE), null, params(),
                    ReportNode.NO_OP).getLoad(Changes.LOAD_ID).getP0();
            // NL's version 2 states BE's load with another value: the same subject and property in two trees
            SnapshotInfo nl = catalog.require(SnapshotRef.latest(S, NL));
            DifferenceModelHeader header = DifferenceModelHeader.builder("urn:uuid:5b1e0c55-0c6e-4c1f-9d77-composed",
                            CgmesSubset.STEADY_STATE_HYPOTHESIS, CIM16)
                    .scenarioTime(BASE.atZone(ZoneOffset.UTC)).modelingAuthoritySet(NL)
                    .supersedes(List.of(nl.state().get(Profiles.SSH))).build();
            catalog.putDiff(new DifferenceModelSet(List.of(new DifferenceModel(header,
                    List.of(CgmesStatement.literal(Changes.LOAD_ID, "EnergyConsumer", "EnergyConsumer.p", "99")),
                    List.of(), List.of()))), SnapshotRef.of(S, NL, null, "2"), ReportNode.NO_OP);

            assertThat(composed(db, null, List.of(BE, NL)).network().getLoad(Changes.LOAD_ID).getP0())
                    .isEqualTo(original);
            assertThat(composed(db, null, List.of(NL, BE)).network().getLoad(Changes.LOAD_ID).getP0())
                    .isEqualTo(99.0);
            // Each authority is resolved by the latest-at-or-below rule: at version 1, NL is its root
            assertThat(composed(db, "1", List.of(NL, BE)).network().getLoad(Changes.LOAD_ID).getP0())
                    .isEqualTo(original);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anAuthorityWithoutASnapshotAtTheMomentIsRefusedByName(String backend) {
        try (RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "composed-missing"))) {
            db.clear(S);
            db.snapshots(S).putFull(microGridBe(), null, SnapshotRef.of(S, BE, null, "1"), null, params(),
                    ReportNode.NO_OP);

            assertThatThrownBy(() -> composed(db, null, List.of(BE, NL))).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("modelling authority '" + NL + "'")
                    .hasMessageContaining("nothing was loaded");
            assertThatThrownBy(() -> composed(db, null, List.of(BE, BE))).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("twice");
            assertThatThrownBy(() -> RdfDbNetworkLoader.loadComposed(db, SnapshotRef.latest(S, BE), List.of(BE),
                    null, null, null, params(), ReportNode.NO_OP)).isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("names no modelling authority");
        }
    }
    // ------------------------------------------------------------------ write-back

    private static final String READ_ONLY = "composed networks are read-only for the diff and variant routes;"
            + " reload";

    /** The first load of the NL tree, as the composed network names it. */
    private static String nlLoad(Network network) {
        RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
        return network.getLoadStream().map(Load::getId)
                .filter(id -> provenance.ownerOf(id).equals(Optional.of(NL))).sorted().findFirst().orElseThrow();
    }

    private static RdfDbExport.SnapshotResult writeBack(Network network, RdfDbConnection db,
                                                        Consumer<Network> change) {
        return RdfDbExport.export(network, Changes.record(network, change), db, SnapshotRef.of(S, null, null, null),
                new CgmesDiffExport.ExportOptions(), ReportNode.NO_OP);
    }

    private static double p0(RdfDbConnection db, String authority, String load) {
        return RdfDbNetworkLoader.load(db, SnapshotRef.latest(S, authority), null, params(), ReportNode.NO_OP)
                .getLoad(load).getP0();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aChangeOnTheOwnedAuthorityIsWrittenIntoItsTree(String backend) {
        try (RdfDbConnection db = day(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo be = catalog.require(SnapshotRef.latest(S, BE));
            SnapshotInfo nl = catalog.require(SnapshotRef.latest(S, NL));
            Network network = composed(db, null, List.of(BE, NL)).network();

            double moved = network.getLoad(Changes.LOAD_ID).getP0();
            SnapshotInfo written = writeBack(network, db, n -> Changes.moveLoad(n, 7.0)).snapshot();

            assertThat(written.modellingAuthority()).isEqualTo(BE);
            assertThat(written.parent()).isEqualTo(be.iri());
            assertThat(catalog.require(SnapshotRef.latest(S, NL)).iri()).isEqualTo(nl.iri());
            assertThat(p0(db, BE, Changes.LOAD_ID)).isEqualTo(moved + 7.0);
            RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
            assertThat(provenance.composition()).extracting(SnapshotInfo::iri).containsExactly(written.iri(), nl.iri());

            // The composition was advanced: a second change grows the same chain
            SnapshotInfo second = writeBack(network, db, n -> Changes.moveLoad(n, 1.0)).snapshot();
            assertThat(second.parent()).isEqualTo(written.iri());
            assertThat(p0(db, BE, Changes.LOAD_ID)).isEqualTo(moved + 8.0);
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aChangeOnAnAuthorityNotOwnedIsRefusedBeforeAnythingIsWritten(String backend) {
        try (RdfDbConnection db = day(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            List<SnapshotInfo> before = catalog.snapshots();
            Network network = composed(db, null, List.of(BE, NL)).network();
            String load = nlLoad(network);

            // One change on each side: the owned one is not written either
            assertThatThrownBy(() -> writeBack(network, db, n -> {
                Changes.moveLoad(n, 3.0);
                n.getLoad(load).setP0(n.getLoad(load).getP0() + 5.0);
            })).isInstanceOf(RdfDbException.class).hasMessage("the change on " + load
                    + " belongs to modelling authority '" + NL + "', which this composed network does not own"
                    + " (owned: [" + BE + "]); nothing was written");
            assertThat(catalog.snapshots()).isEqualTo(before);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void withTwoOwnersTwoSnapshotsAreWritten(String backend) {
        try (RdfDbConnection db = day(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            SnapshotInfo nl = catalog.require(SnapshotRef.latest(S, NL));
            Network network = RdfDbNetworkLoader.loadComposed(db, SnapshotRef.of(S, null, null, null),
                    List.of(BE, NL), List.of(BE, NL), null, null, params(), ReportNode.NO_OP).network();
            String load = nlLoad(network);
            double beP0 = network.getLoad(Changes.LOAD_ID).getP0();
            double nlP0 = network.getLoad(load).getP0();

            RdfDbExport.SnapshotResult result = writeBack(network, db, n -> {
                Changes.moveLoad(n, 3.0);
                n.getLoad(load).setP0(nlP0 + 5.0);
            });

            // The result is the first owner's; the composition names both written snapshots
            assertThat(result.snapshot().modellingAuthority()).isEqualTo(BE);
            SnapshotInfo nlWritten = catalog.require(SnapshotRef.latest(S, NL));
            assertThat(nlWritten.parent()).isEqualTo(nl.iri());
            assertThat(network.getExtension(RdfDbProvenance.class).composition()).extracting(SnapshotInfo::iri)
                    .containsExactly(result.snapshot().iri(), nlWritten.iri());
            assertThat(p0(db, BE, Changes.LOAD_ID)).isEqualTo(beP0 + 3.0);
            assertThat(p0(db, NL, load)).isEqualTo(nlP0 + 5.0);
            // ...and composing again gives the network that wrote them
            Network again = composed(db, null, List.of(BE, NL)).network();
            assertThat(again.getLoad(Changes.LOAD_ID).getP0()).isEqualTo(beP0 + 3.0);
            assertThat(again.getLoad(load).getP0()).isEqualTo(nlP0 + 5.0);
            catalog.verify();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aComposedNetworkIsNotUpdatedInPlace(String backend) {
        try (RdfDbConnection db = day(backend)) {
            Network network = composed(db, null, List.of(BE, NL)).network();

            assertThatThrownBy(() -> RdfDbNetworkLoader.update(network, db, SnapshotRef.latest(S, BE),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class).hasMessageContaining(READ_ONLY);
            assertThatThrownBy(() -> RdfDbNetworkLoader.update(network, db, SnapshotRef.latest(S, BE),
                    new RdfDbUpdateOptions().setTargetVariant("v"), params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class).hasMessageContaining(READ_ONLY);
            assertThatThrownBy(() -> RdfDbNetworkLoader.update(network, db, S, DiffTarget.head(),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class).hasMessageContaining(READ_ONLY);
            assertThatThrownBy(() -> RdfDbNetworkLoader.update(network, db, S, null, params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class).hasMessageContaining(READ_ONLY);
            assertThat(network.getVariantManager().getVariantIds()).containsExactly(RdfDbProvenance.PRIMARY_VARIANT);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aComposedNetworkIsNotAVariantHolder(String backend) {
        try (RdfDbConnection db = day(backend)) {
            SnapshotCatalog catalog = db.snapshots(S);
            List<SnapshotInfo> before = catalog.snapshots();
            Network network = composed(db, null, List.of(BE, NL)).network();
            List<NetworkEvent> events = Changes.record(network, n -> Changes.moveLoad(n, 1.0));

            assertThatThrownBy(() -> RdfDbExport.exportVariant(network, events, db,
                    RdfDbProvenance.PRIMARY_VARIANT, null, new CgmesDiffExport.ExportOptions(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class).hasMessageContaining(READ_ONLY);
            assertThatThrownBy(() -> RdfDbExport.exportPerVariant(network, events, db, null,
                    new CgmesDiffExport.ExportOptions(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class).hasMessageContaining(READ_ONLY);
            assertThatThrownBy(() -> RdfDbExport.export(network, events, db, S, new CgmesDiffExport.ExportOptions()))
                    .isInstanceOf(RdfDbException.class).hasMessageContaining(READ_ONLY);
            assertThat(catalog.snapshots()).isEqualTo(before);
        }
    }
}
