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
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A whole day loaded into the variants of one network.
 *
 * <p>Three claims. Every variant holds what a separate load of that snapshot holds; the naming rule gives a day
 * readable names and a study unambiguous ones; and one timestep that cannot be reached inside a variant costs its
 * own variant and nothing else.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbVariantBulkLoadTest {

    private static final String S = "2016-01-01";
    private static final Set<String> IDENTITY = Set.of("cgmesMetadataModels", "rdfDbProvenance");

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

    private static RdfDbConnection rootOnly(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "variant-bulk"));
        db.clear(S);
        db.snapshots(S).putFull(be(), null, SnapshotRef.of(S, "1.0"), params(), ReportNode.NO_OP);
        return db;
    }

    private static Network load(RdfDbConnection db, String version, String timestep) {
        return RdfDbNetworkLoader.load(db, S, version, timestep, null, params(), ReportNode.NO_OP);
    }

    private static void record(Network network, RdfDbConnection db, SnapshotRef target, Consumer<Network> change) {
        List<NetworkEvent> events = Changes.record(network, change);
        RdfDbExport.export(network, events, db, target, new CgmesDiffExport.ExportOptions());
    }

    /** The base timestep plus three more, each one load step apart, all at version 1.0. */
    private static final List<String> DAY = List.of("2014-06-01T10:30:00Z", "2014-06-01T11:00:00Z",
            "2014-06-01T11:30:00Z", "2014-06-01T12:00:00Z");

    private static RdfDbConnection day(String backend) {
        RdfDbConnection db = rootOnly(backend);
        for (int i = 1; i < DAY.size(); i++) {
            db.snapshots(S).putAsDiff(TimestepFixtures.ssh(i, DAY.get(i), "t" + i), null,
                    new SnapshotRef(S, "1.0", DAY.get(i)), params(), ReportNode.NO_OP);
        }
        return db;
    }

    private static VariantLoadResult loadDay(RdfDbConnection db, List<String> timesteps) {
        return RdfDbNetworkLoader.loadVariants(db, S, "1.0", timesteps, new RdfDbVariantLoadOptions(), null,
                params(), ReportNode.NO_OP);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void everyTimestepVariantEqualsASeparateLoad(String backend) {
        try (RdfDbConnection db = day(backend)) {
            VariantLoadResult result = loadDay(db, DAY);

            assertThat(result.refused()).isEmpty();
            assertThat(result.outcomes()).hasSize(DAY.size());
            Network network = result.network();
            assertThat(network.getVariantManager().getWorkingVariantId())
                    .isEqualTo(VariantManagerConstants.INITIAL_VARIANT_ID);

            for (int i = 0; i < DAY.size(); i++) {
                VariantOutcome outcome = result.outcomes().get(i);
                assertThat(outcome.isBound()).isTrue();
                Network separate = load(db, "1.0", DAY.get(i));
                network.getVariantManager().setWorkingVariant(outcome.variantId());
                try {
                    Networks.assertSameNetworkIgnoringStateVariables(separate, network, IDENTITY,
                            touchedLoads(separate));
                } finally {
                    network.getVariantManager()
                            .setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
                }
            }
        }
    }

    /** The loads the day fixture moves; their state-variable results legitimately differ between the routes. */
    private static Set<String> touchedLoads(Network network) {
        return network.getLoadStream().map(load -> load.getId()).collect(java.util.stream.Collectors.toSet());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void theNamingRuleUsesLabelsWhenTheyAreDistinct(String backend) {
        try (RdfDbConnection db = day(backend)) {
            VariantLoadResult result = loadDay(db, DAY);
            assertThat(result.outcomes().stream().map(VariantOutcome::variantId).toList())
                    .containsExactly("10:30", "11:00", "11:30", "12:00");
            assertThat(result.network().getVariantManager().getVariantIds())
                    .contains("10:30", "11:00", "11:30", "12:00",
                            VariantManagerConstants.INITIAL_VARIANT_ID);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void theNamingRuleQualifiesWithTheVersionWhenLabelsRepeat(String backend) {
        try (RdfDbConnection db = rootOnly(backend)) {
            Network sender = load(db, "1.0", null);
            record(sender, db, SnapshotRef.of(S, "1.1"), n -> Changes.moveLoad(n, 11.0));

            VariantLoadResult result = RdfDbNetworkLoader.loadVariants(db, S, List.of(
                    VariantRequest.of(SnapshotRef.of(S, "1.0")),
                    VariantRequest.of(SnapshotRef.of(S, "1.1"))),
                    new RdfDbVariantLoadOptions(), null, params(), ReportNode.NO_OP);

            assertThat(result.outcomes().stream().map(VariantOutcome::variantId).toList())
                    .containsExactly("1.0@10:30", "1.1@10:30");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void explicitIdentifiersAreUsedAsGiven(String backend) {
        try (RdfDbConnection db = day(backend)) {
            VariantLoadResult result = RdfDbNetworkLoader.loadVariants(db, S, List.of(
                    new VariantRequest("morning", new SnapshotRef(S, "1.0", DAY.get(0))),
                    new VariantRequest("noon", new SnapshotRef(S, "1.0", DAY.get(3)))),
                    new RdfDbVariantLoadOptions(), null, params(), ReportNode.NO_OP);

            assertThat(result.outcomes().stream().map(VariantOutcome::variantId).toList())
                    .containsExactly("morning", "noon");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void duplicateAndReservedIdentifiersAreRefused(String backend) {
        try (RdfDbConnection db = day(backend)) {
            assertThatThrownBy(() -> RdfDbNetworkLoader.loadVariants(db, S, List.of(
                    new VariantRequest("x", new SnapshotRef(S, "1.0", DAY.get(0))),
                    new VariantRequest("x", new SnapshotRef(S, "1.0", DAY.get(1)))),
                    null, null, params(), ReportNode.NO_OP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requested twice");

            assertThatThrownBy(() -> RdfDbNetworkLoader.loadVariants(db, S, List.of(
                    new VariantRequest(VariantManagerConstants.INITIAL_VARIANT_ID,
                            new SnapshotRef(S, "1.0", DAY.get(0)))),
                    null, null, params(), ReportNode.NO_OP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("the variant the network itself is");

            assertThatThrownBy(() -> RdfDbNetworkLoader.loadVariants(db, S, List.of(), null, null, params(),
                    ReportNode.NO_OP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one snapshot");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aMissingSnapshotIsNamedBeforeAnythingIsLoaded(String backend) {
        try (RdfDbConnection db = day(backend)) {
            assertThatThrownBy(() -> RdfDbNetworkLoader.loadVariants(db, S, List.of(
                    VariantRequest.of(new SnapshotRef(S, "1.0", DAY.get(0))),
                    VariantRequest.of(new SnapshotRef(S, "9.9", DAY.get(1)))),
                    null, null, params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("holds no snapshot")
                    .hasMessageContaining("nothing was loaded");
        }
    }

    /** Mixing versions and timesteps in one request list is an ordinary case, not a special one. */
    /** F5: a request with an open version binds to the version it really reached. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aBindingCarriesTheResolvedVersion(String backend) {
        try (RdfDbConnection db = rootOnly(backend)) {
            Network sender = load(db, "1.0", null);
            record(sender, db, SnapshotRef.of(S, "1.1"), n -> Changes.moveLoad(n, 11.0));

            VariantLoadResult result = RdfDbNetworkLoader.loadVariants(db, S, List.of(
                    new VariantRequest("head", SnapshotRef.latest(S))),
                    new RdfDbVariantLoadOptions(), null, params(), ReportNode.NO_OP);

            assertThat(result.refused()).isEmpty();
            VariantBinding binding = result.network().getExtension(RdfDbProvenance.class)
                    .variantBinding("head").orElseThrow();
            assertThat(binding.version()).isEqualTo("1.1");
            assertThat(binding.timestep()).isEqualTo(DAY.get(0));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void mixedVersionsAndTimesteps(String backend) {
        try (RdfDbConnection db = day(backend)) {
            Network sender = load(db, "1.0", DAY.get(1));
            record(sender, db, new SnapshotRef(S, "1.1", DAY.get(1)), n -> Changes.moveLoad(n, 17.0));

            VariantLoadResult result = RdfDbNetworkLoader.loadVariants(db, S, List.of(
                    new VariantRequest("base", new SnapshotRef(S, "1.0", DAY.get(0))),
                    new VariantRequest("t11-v10", new SnapshotRef(S, "1.0", DAY.get(1))),
                    new VariantRequest("t11-v11", new SnapshotRef(S, "1.1", DAY.get(1)))),
                    new RdfDbVariantLoadOptions(), null, params(), ReportNode.NO_OP);

            assertThat(result.refused()).isEmpty();
            RdfDbProvenance provenance = result.network().getExtension(RdfDbProvenance.class);
            assertThat(provenance.variantBinding("t11-v11").orElseThrow().version()).isEqualTo("1.1");
            // The 1.1 variant is one difference away from the 1.0 variant of the same timestep, not from the base
            assertThat(result.outcomes().get(2).diffCount()).isEqualTo(1);
            assertThat(result.outcomes().get(2).clonedFrom()).isEqualTo("t11-v10");
        }
    }

    /** One drifted timestep is refused; the others are loaded and usable. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void oneRefusedTimestepDoesNotCostTheOthers(String backend) {
        try (RdfDbConnection db = rootOnly(backend)) {
            db.snapshots(S).putAsDiff(TimestepFixtures.ssh(1, DAY.get(1), "ok"), null,
                    new SnapshotRef(S, "1.0", DAY.get(1)), params(), ReportNode.NO_OP);
            db.snapshots(S).putAsDiff(TimestepFixtures.eqDrift(2, DAY.get(2), "drift"), null,
                    new SnapshotRef(S, "1.0", DAY.get(2)), params(), ReportNode.NO_OP);

            VariantLoadResult result = loadDay(db, List.of(DAY.get(0), DAY.get(1), DAY.get(2)));

            assertThat(result.bound()).hasSize(2);
            assertThat(result.refused()).hasSize(1);
            VariantOutcome refused = result.refused().get(0);
            assertThat(refused.variantId()).isEqualTo("11:30");
            assertThat(refused.reasons()).isNotEmpty();
            assertThat(result.network().getVariantManager().getVariantIds()).doesNotContain("11:30");
            assertThat(result.network().getExtension(RdfDbProvenance.class).lastRefused()).hasSize(1);
        }
    }

    /** Every requested snapshot gets a variant of its own, including the first one. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void theFirstRequestIsTheNetworkAndAVariantOfItsOwn(String backend) {
        try (RdfDbConnection db = day(backend)) {
            VariantLoadResult result = loadDay(db, List.of(DAY.get(0), DAY.get(1)));
            Network network = result.network();

            RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
            assertThat(provenance.variantBinding(VariantManagerConstants.INITIAL_VARIANT_ID).orElseThrow()
                    .timestep()).isEqualTo(DAY.get(0));
            assertThat(provenance.variantBinding("10:30").orElseThrow().timestep()).isEqualTo(DAY.get(0));
            assertThat(provenance.variantBindings().keySet()).containsExactly("10:30", "11:00");
        }
    }

    /**
     * F4: a clone source that is refused at apply time must not take the whole load down.
     *
     * <p>Apply-time refusals are the normal case: the store says `pdb:variantSafe` for every NETWORK_DEPENDENT
     * family, and a store written before the flag existed says nothing at all. The day here is
     * {@code 1.0 -> 1.1 (a line impedance) -> 1.2 (a load)} of one timestep, with the flags deleted so that the
     * refusal really happens at apply time. 1.1 is refused; 1.2 was planned to be cloned from 1.1 and has to be
     * re-planned against what is really there, which still runs into the same unsafe difference.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aRefusedCloneSourceDoesNotFailTheLoad(String backend) {
        try (RdfDbConnection db = rootOnly(backend)) {
            Network sender = load(db, "1.0", null);
            String line = sender.getLineStream().map(l -> l.getId()).sorted().findFirst().orElseThrow();
            record(sender, db, SnapshotRef.of(S, "1.1"),
                n -> n.getLine(line).setR(n.getLine(line).getR() + 1.0));
            record(sender, db, SnapshotRef.of(S, "1.2"), n -> Changes.moveLoad(n, 7.0));
            db.sparql(S).update(RdfDbVocabulary.PREFIXES + "DELETE { GRAPH <" + RdfDbNames.metaGraph(S)
                    + "> { ?m pdb:variantSafe ?v } } WHERE { GRAPH <" + RdfDbNames.metaGraph(S)
                    + "> { ?m pdb:variantSafe ?v } }");

            VariantLoadResult result = RdfDbNetworkLoader.loadVariants(db, S, List.of(
                    VariantRequest.of(SnapshotRef.of(S, "1.0")),
                    VariantRequest.of(SnapshotRef.of(S, "1.1")),
                    VariantRequest.of(SnapshotRef.of(S, "1.2"))),
                    new RdfDbVariantLoadOptions(), null, params(), ReportNode.NO_OP);

            assertThat(result.bound()).hasSize(1);
            assertThat(result.refused()).hasSize(2);
            assertThat(result.refused()).allSatisfy(outcome ->
                    assertThat(String.join(" ", outcome.reasons())).contains("not stored per variant"));
            assertThat(result.network().getVariantManager().getVariantIds()).hasSize(2);
        }
    }

    /** The multi-thread flag is set before any variant exists, which is the only time it is safe. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void multiThreadAccessCanBeAskedForUpFront(String backend) {
        try (RdfDbConnection db = day(backend)) {
            VariantLoadResult result = RdfDbNetworkLoader.loadVariants(db, S, "1.0", DAY,
                    new RdfDbVariantLoadOptions().setAllowVariantMultiThreadAccess(true), null, params(),
                    ReportNode.NO_OP);
            assertThat(result.refused()).isEmpty();
            assertThat(result.network().getVariantManager().getVariantIds()).hasSize(DAY.size() + 1);
        }
    }
}
