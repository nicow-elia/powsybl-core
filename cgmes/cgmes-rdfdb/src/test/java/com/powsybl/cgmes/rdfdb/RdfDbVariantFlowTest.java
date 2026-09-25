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
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * One network, several variants, each standing for a stored snapshot.
 *
 * <p>The claim under test is exactly two sentences. A variant brought to a snapshot holds what a network loaded at
 * that snapshot holds; and bringing it there changes <em>nothing</em> in any other variant, not one attribute. The
 * second half is asserted by writing the canonical XIIDM of every variant before and after and comparing the
 * documents, which covers every identifiable and every attribute rather than the handful a test would think to
 * look at.</p>
 *
 * <p>The other half of the feature is the refusal. A difference that cannot be applied without writing state IIDM
 * keeps once per network is answered with {@link UpdateResult.Route#VARIANT_REFUSED} and the reasons, and the
 * network &mdash; every variant of it, including the one the call would have created &mdash; is left exactly as it
 * was. That is asserted the same way.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbVariantFlowTest {

    private static final String S = "2016-01-01";
    private static final String OTHER = "other";
    private static final String BASE_TIMESTEP = "2014-06-01T10:30:00Z";

    /** The identity of a network is its own assertion, so it is kept out of the network comparison. */
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
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "variant-flow"));
        db.clear(S);
        db.clear(OTHER);
        db.snapshots(S).putFull(be(), null, SnapshotRef.of(S, "1.0"), params(), ReportNode.NO_OP);
        return db;
    }

    private static Network load(RdfDbConnection db, String scenario, String version, String timestep) {
        return RdfDbNetworkLoader.load(db, scenario, version, timestep, null, params(), ReportNode.NO_OP);
    }

    /** Record a change on a network and store it as the given snapshot. */
    private static void record(Network network, RdfDbConnection db, SnapshotRef target, Consumer<Network> change) {
        List<NetworkEvent> events = Changes.record(network, change);
        RdfDbExport.export(network, events, db, target, new CgmesDiffExport.ExportOptions());
    }

    private static UpdateResult bring(Network network, RdfDbConnection db, String version, String timestep,
                                      String variant) {
        return RdfDbNetworkLoader.update(network, db, S, version, timestep, variant, params(),
                ReportNode.NO_OP);
    }

    /** How many {@code pdb:variantSafe} flags the metadata graph of the scenario holds. */
    private static long variantSafeFlags(RdfDbConnection db) {
        List<Map<String, org.eclipse.rdf4j.model.Value>> rows = db.sparql(S).select(RdfDbVocabulary.PREFIXES
                + "SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + RdfDbNames.metaGraph(S)
                + "> { ?m pdb:variantSafe ?v } }");
        return rows.isEmpty() ? 0 : Long.parseLong(rows.get(0).get("n").stringValue());
    }

    /** The canonical XIIDM of every variant of a network, keyed by variant. */
    private static Map<String, String> xiidmPerVariant(Network network) {
        String working = network.getVariantManager().getWorkingVariantId();
        Map<String, String> documents = new LinkedHashMap<>();
        try {
            for (String variant : new ArrayList<>(network.getVariantManager().getVariantIds())) {
                network.getVariantManager().setWorkingVariant(variant);
                documents.put(variant, new String(Networks.canonicalXiidm(network, IDENTITY),
                        StandardCharsets.UTF_8));
            }
        } finally {
            network.getVariantManager().setWorkingVariant(working);
        }
        return documents;
    }

    private static double loadP0(Network network, String variant) {
        String working = network.getVariantManager().getWorkingVariantId();
        network.getVariantManager().setWorkingVariant(variant);
        try {
            return network.getLoad(Changes.LOAD_ID).getP0();
        } finally {
            network.getVariantManager().setWorkingVariant(working);
        }
    }

    /** A scenario with versions 1.0, 1.1 and 1.2 of the base timestep, each one load step apart. */
    private static RdfDbConnection threeVersions(String backend) {
        RdfDbConnection db = rootOnly(backend);
        Network sender = load(db, S, "1.0", null);
        record(sender, db, SnapshotRef.of(S, "1.1"), n -> Changes.moveLoad(n, 11.0));
        record(sender, db, SnapshotRef.of(S, "1.2"), n -> Changes.moveLoad(n, 13.0));
        return db;
    }

    // ------------------------------------------------------------------ the two promises

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void applyOnVariantLeavesOthersByteIdentical(String backend) {
        try (RdfDbConnection db = threeVersions(backend)) {
            Network network = load(db, S, "1.0", null);
            UpdateResult a = bring(network, db, "1.1", null, "A");
            assertThat(a.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);

            Map<String, String> before = xiidmPerVariant(network);
            UpdateResult b = bring(network, db, "1.2", null, "B");
            assertThat(b.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(b.variantId()).isEqualTo("B");
            Map<String, String> after = xiidmPerVariant(network);

            assertThat(after.get(VariantManagerConstants.INITIAL_VARIANT_ID))
                    .as("the primary variant must not move")
                    .isEqualTo(before.get(VariantManagerConstants.INITIAL_VARIANT_ID));
            assertThat(after.get("A")).as("variant A must not move").isEqualTo(before.get("A"));
            assertThat(after).containsKey("B");
            // The working variant of the caller is where it was
            assertThat(network.getVariantManager().getWorkingVariantId())
                    .isEqualTo(VariantManagerConstants.INITIAL_VARIANT_ID);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void everyVariantEqualsASeparateLoad(String backend) {
        try (RdfDbConnection db = threeVersions(backend)) {
            Network network = load(db, S, "1.0", null);
            bring(network, db, "1.1", null, "A");
            bring(network, db, "1.2", null, "B");

            for (String version : List.of("1.1", "1.2")) {
                String variant = "1.1".equals(version) ? "A" : "B";
                Network separate = load(db, S, version, null);
                network.getVariantManager().setWorkingVariant(variant);
                try {
                    Networks.assertSameNetworkIgnoringStateVariables(separate, network, IDENTITY,
                            Set.of(Changes.LOAD_ID));
                } finally {
                    network.getVariantManager()
                            .setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
                }
            }
        }
    }

    // ------------------------------------------------------------------ create or update

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void createOrUpdateSemantics(String backend) {
        try (RdfDbConnection db = threeVersions(backend)) {
            Network network = load(db, S, "1.0", null);
            assertThat(bring(network, db, "1.1", null, "A").route())
                    .isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(network.getVariantManager().getVariantIds()).contains("A");
            double atOneOne = loadP0(network, "A");

            // Present: the variant is moved, not created again
            assertThat(bring(network, db, "1.2", null, "A").route())
                    .isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(loadP0(network, "A")).isNotEqualTo(atOneOne);
            assertThat(db.snapshots(S).find(SnapshotRef.of(S, "1.2")).orElseThrow().iri())
                    .isEqualTo(network.getExtension(RdfDbProvenance.class).variantBinding("A")
                            .orElseThrow().snapshotIri());

            // Already there: nothing to do
            UpdateResult again = bring(network, db, "1.2", null, "A");
            assertThat(again.route()).isEqualTo(UpdateResult.Route.NOOP);
            assertThat(again.variantId()).isEqualTo("A");
        }
    }

    /** A new variant is cloned from the variant nearest to the target, not always from the primary. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void newVariantIsClonedFromTheNearestBoundVariant(String backend) {
        try (RdfDbConnection db = threeVersions(backend)) {
            Network network = load(db, S, "1.0", null);
            bring(network, db, "1.1", null, "A");

            // 1.2 is one difference from 1.1 and two from the primary at 1.0
            UpdateResult result = bring(network, db, "1.2", null, "B");
            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(result.diffCount()).isEqualTo(1);
            assertThat(network.getExtension(RdfDbProvenance.class).variantBinding("B").orElseThrow()
                    .clonedFrom()).isEqualTo("A");
        }
    }

    /** A variant can be walked backwards as well as forwards. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void backwardsOnAVariant(String backend) {
        try (RdfDbConnection db = threeVersions(backend)) {
            Network network = load(db, S, "1.0", null);
            bring(network, db, "1.2", null, "A");
            Map<String, String> before = xiidmPerVariant(network);

            UpdateResult back = bring(network, db, "1.1", null, "A");
            assertThat(back.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            Network separate = load(db, S, "1.1", null);
            network.getVariantManager().setWorkingVariant("A");
            try {
                Networks.assertSameNetworkIgnoringStateVariables(separate, network, IDENTITY,
                        Set.of(Changes.LOAD_ID));
            } finally {
                network.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
            }
            assertThat(xiidmPerVariant(network).get(VariantManagerConstants.INITIAL_VARIANT_ID))
                    .isEqualTo(before.get(VariantManagerConstants.INITIAL_VARIANT_ID));
        }
    }

    // ------------------------------------------------------------------ refusals

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void equipmentDifferenceIsRefused(String backend) {
        try (RdfDbConnection db = rootOnly(backend)) {
            Network sender = load(db, S, "1.0", null);
            String line = sender.getLineStream().map(l -> l.getId()).sorted().findFirst().orElseThrow();
            record(sender, db, SnapshotRef.of(S, "1.1"),
                n -> n.getLine(line).setR(n.getLine(line).getR() + 1.0));

            Network network = load(db, S, "1.0", null);
            Map<String, String> before = xiidmPerVariant(network);

            UpdateResult result = bring(network, db, "1.1", null, "A");

            assertThat(result.route()).isEqualTo(UpdateResult.Route.VARIANT_REFUSED);
            assertThat(result.network()).isSameAs(network);
            assertThat(result.reasons()).isNotEmpty();
            assertThat(String.join(" ", result.reasons())).contains("not stored per variant");
            assertThat(network.getVariantManager().getVariantIds()).doesNotContain("A");
            assertThat(xiidmPerVariant(network)).isEqualTo(before);
            assertThat(network.getExtension(RdfDbProvenance.class).lastRefused())
                    .singleElement()
                    .satisfies(outcome -> assertThat(outcome.status())
                            .isEqualTo(VariantOutcome.Status.REFUSED));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void eqDriftIsRefused(String backend) {
        try (RdfDbConnection db = rootOnly(backend)) {
            // A renamed line is an equipment change no in-place update can apply at all
            db.snapshots(S).putAsDiff(TimestepFixtures.eqDrift(1, "2014-06-01T11:00:00Z", "drift"), null,
                    new SnapshotRef(S, "1.0", "2014-06-01T11:00:00Z"), params(), ReportNode.NO_OP);

            Network network = load(db, S, "1.0", null);
            Map<String, String> before = xiidmPerVariant(network);

            UpdateResult result = bring(network, db, "1.0", "2014-06-01T11:00:00Z", "A");

            assertThat(result.route()).isEqualTo(UpdateResult.Route.VARIANT_REFUSED);
            assertThat(result.reasons()).isNotEmpty();
            assertThat(network.getVariantManager().getVariantIds()).doesNotContain("A");
            assertThat(xiidmPerVariant(network)).isEqualTo(before);
        }
    }

    /** The separate-network fallback hands the caller the state, and still does not touch the network. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void separateNetworkFallbackLeavesTheOriginalUntouched(String backend) {
        try (RdfDbConnection db = rootOnly(backend)) {
            Network sender = load(db, S, "1.0", null);
            String line = sender.getLineStream().map(l -> l.getId()).sorted().findFirst().orElseThrow();
            record(sender, db, SnapshotRef.of(S, "1.1"),
                n -> n.getLine(line).setR(n.getLine(line).getR() + 1.0));

            Network network = load(db, S, "1.0", null);
            Map<String, String> before = xiidmPerVariant(network);

            UpdateResult result = RdfDbNetworkLoader.update(network, db, SnapshotRef.of(S, "1.1"),
                    new RdfDbUpdateOptions().setTargetVariant("A")
                            .setVariantFallback(RdfDbUpdateOptions.VariantFallback.SEPARATE_NETWORK),
                    params(), ReportNode.NO_OP);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.FULL_RELOAD);
            assertThat(result.network()).isNotSameAs(network);
            assertThat(result.reasons()).isNotEmpty();
            Networks.assertSameNetwork(load(db, S, "1.1", null), result.network(), IDENTITY);
            assertThat(network.getVariantManager().getVariantIds()).doesNotContain("A");
            assertThat(xiidmPerVariant(network)).isEqualTo(before);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void crossScenarioIsRefusedWithoutAQuery(String backend) {
        try (RdfDbConnection db = threeVersions(backend)) {
            db.snapshots(OTHER).putFull(be(), null, SnapshotRef.of(OTHER, "1.0"), params(), ReportNode.NO_OP);
            Network network = load(db, OTHER, "1.0", null);

            UpdateResult result = RdfDbNetworkLoader.update(network, db, SnapshotRef.of(S, "1.1"),
                    new RdfDbUpdateOptions().setTargetVariant("A"), params(), ReportNode.NO_OP);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.VARIANT_REFUSED);
            assertThat(String.join(" ", result.reasons())).contains("never cross scenarios");
            assertThat(network.getVariantManager().getVariantIds()).doesNotContain("A");
        }
    }

    /**
     * R1: after a classic in-place update, a tracked clone no longer claims a snapshot it does not hold.
     *
     * <p>The classic update is free to write values IIDM shares between all variants, so whatever the clone held
     * is gone. Opting in on it afterwards therefore has to say so, instead of planning from a lie.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aClassicUpdateDropsTheBindingsOfTrackedClones(String backend) {
        try (RdfDbConnection db = threeVersions(backend)) {
            Network network = load(db, S, "1.0", null);
            network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "c");
            assertThat(network.getExtension(RdfDbProvenance.class).variantBindings()).containsKey("c");

            UpdateResult classic = RdfDbNetworkLoader.update(network, db, SnapshotRef.of(S, "1.1"),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP);
            assertThat(classic.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(network.getExtension(RdfDbProvenance.class).variantBindings()).isEmpty();

            assertThatThrownBy(() -> bring(network, db, "1.2", null, "c"))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("is not bound to a snapshot");
        }
    }

    /**
     * R2: committing a study variant onto the primary moves the network-level identity with the state.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void overwritingThePrimaryMovesTheIdentityWithTheState(String backend) {
        try (RdfDbConnection db = threeVersions(backend)) {
            Network network = load(db, S, "1.0", null);
            assertThat(bring(network, db, "1.1", null, "A").route())
                    .isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            double atOneOne = loadP0(network, "A");
            double atOneZero = loadP0(network, VariantManagerConstants.INITIAL_VARIANT_ID);
            assertThat(atOneOne).isNotEqualTo(atOneZero);

            network.getVariantManager().cloneVariant("A", VariantManagerConstants.INITIAL_VARIANT_ID, true);

            RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
            assertThat(provenance.variantBinding(VariantManagerConstants.INITIAL_VARIANT_ID).orElseThrow()
                    .version()).isEqualTo("1.1");
            assertThat(provenance.variantBindings()).doesNotContainKey(
                    VariantManagerConstants.INITIAL_VARIANT_ID);

            // And the primary can be taken back to 1.0, which is a real step and not a NOOP
            UpdateResult back = bring(network, db, "1.0", null,
                    VariantManagerConstants.INITIAL_VARIANT_ID);
            assertThat(back.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(loadP0(network, VariantManagerConstants.INITIAL_VARIANT_ID)).isEqualTo(atOneZero);
        }
    }

    /**
     * R4: the opt-in is sticky, and stays on in two situations worth pinning &mdash; after a <em>refused</em>
     * first opt-in, and after the user removed every variant again. Both are the conservative answer: a caller
     * that asked for variant semantics keeps getting them, so nothing can leak between variants it may create
     * later.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void variantModeStaysOnAfterARefusalAndAfterRemovingEveryVariant(String backend) {
        try (RdfDbConnection db = rootOnly(backend)) {
            Network sender = load(db, S, "1.0", null);
            record(sender, db, SnapshotRef.of(S, "1.1"), n -> Changes.moveLoad(n, 11.0));
            String line = sender.getLineStream().map(l -> l.getId()).sorted().findFirst().orElseThrow();
            record(sender, db, SnapshotRef.of(S, "1.2"),
                n -> n.getLine(line).setR(n.getLine(line).getR() + 1.0));

            // (a) a refused first opt-in still switches the mode on
            Network refusedFirst = load(db, S, "1.0", null);
            assertThat(bring(refusedFirst, db, "1.2", null, "A").route())
                    .isEqualTo(UpdateResult.Route.VARIANT_REFUSED);
            assertThat(refusedFirst.getVariantManager().getVariantIds())
                    .containsExactly(VariantManagerConstants.INITIAL_VARIANT_ID);
            assertThat(RdfDbNetworkLoader.update(refusedFirst, db, SnapshotRef.of(S, "1.2"),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP).route())
                    .isEqualTo(UpdateResult.Route.VARIANT_REFUSED);

            // (b) removing every variant again does not switch it off
            Network removed = load(db, S, "1.0", null);
            assertThat(bring(removed, db, "1.1", null, "A").route())
                    .isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            removed.getVariantManager().removeVariant("A");
            assertThat(removed.getExtension(RdfDbProvenance.class).variantBindings()).isEmpty();
            assertThat(RdfDbNetworkLoader.update(removed, db, SnapshotRef.of(S, "1.2"),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP).route())
                    .isEqualTo(UpdateResult.Route.VARIANT_REFUSED);
        }
    }

    /** F14: a refusal on a network this package never saw leaves nothing behind, extensions included. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aRefusalOnAFileLoadedNetworkLeavesNoProvenance(String backend) {
        try (RdfDbConnection db = rootOnly(backend)) {
            Network sender = load(db, S, "1.0", null);
            String line = sender.getLineStream().map(l -> l.getId()).sorted().findFirst().orElseThrow();
            record(sender, db, SnapshotRef.of(S, "1.1"),
                n -> n.getLine(line).setR(n.getLine(line).getR() + 1.0));

            Network network = Network.read(be(), params());
            RdfDbProvenance before = network.getExtension(RdfDbProvenance.class);
            assertThat(before).isNull();

            UpdateResult result = bring(network, db, "1.1", null, "A");

            assertThat(result.route()).isEqualTo(UpdateResult.Route.VARIANT_REFUSED);
            RdfDbProvenance after = network.getExtension(RdfDbProvenance.class);
            assertThat(after).isNull();
            assertThat(network.getVariantManager().getVariantIds()).doesNotContain("A");
        }
    }

    /** F5: a variant created with an open address reports the version and the timestep it really reached. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aBindingCarriesTheResolvedAddress(String backend) {
        try (RdfDbConnection db = threeVersions(backend)) {
            Network network = load(db, S, "1.0", null);

            // version given, timestep left open (the base timestep of the scenario)
            assertThat(bring(network, db, "1.1", null, "A").route())
                    .isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            VariantBinding a = network.getExtension(RdfDbProvenance.class).variantBinding("A").orElseThrow();
            assertThat(a.version()).isEqualTo("1.1");
            assertThat(a.timestep()).isEqualTo(BASE_TIMESTEP);

            // both left open: the newest version of the base timestep
            assertThat(bring(network, db, null, null, "B").route())
                    .isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            VariantBinding b = network.getExtension(RdfDbProvenance.class).variantBinding("B").orElseThrow();
            assertThat(b.version()).isEqualTo("1.2");
            assertThat(b.timestep()).isEqualTo(BASE_TIMESTEP);

            // ... which is exactly what the export needs to find the timestep to write into. B is at the head
            // of that timestep, so its successor is the next version of it
            network.getVariantManager().setWorkingVariant("B");
            List<NetworkEvent> events = Changes.record(network, n -> Changes.moveLoad(n, 5.0));
            network.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
            RdfDbExport.SnapshotResult written = RdfDbExport.exportVariant(network, events, db, "B", null,
                    new CgmesDiffExport.ExportOptions(), ReportNode.NO_OP);
            assertThat(written.snapshot().timestep()).isEqualTo(BASE_TIMESTEP);
            assertThat(written.snapshot().version()).isEqualTo("1.3");
        }
    }

    /**
     * The binding has to be able to <em>ask</em> whether a network is in variant mode.
     *
     * <p>A tracked binding does not answer it: cloning a variant produces one without opting in. Each of the
     * three opt-in paths is checked here, a refused one included, because that is what a caller has to be able
     * to tell apart before it decides whether a route of {@code 'full'} is possible at all.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void isVariantModeAnswersWhoOptedIn(String backend) {
        try (RdfDbConnection db = threeVersions(backend)) {
            // A plain load, and a clone the user made: bindings tracked, mode off
            Network plain = load(db, S, "1.0", null);
            assertThat(plain.getExtension(RdfDbProvenance.class).isVariantMode()).isFalse();
            plain.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "c");
            assertThat(plain.getExtension(RdfDbProvenance.class).variantBindings()).containsKey("c");
            assertThat(plain.getExtension(RdfDbProvenance.class).isVariantMode()).isFalse();

            // (1) an update that names a target variant
            Network named = load(db, S, "1.0", null);
            assertThat(bring(named, db, "1.1", null, "A").route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(named.getExtension(RdfDbProvenance.class).isVariantMode()).isTrue();

            // (2) loadVariants
            Network day = RdfDbNetworkLoader.loadVariants(db, S, List.of(
                    VariantRequest.of(SnapshotRef.of(S, "1.0")),
                    VariantRequest.of(SnapshotRef.of(S, "1.1"))),
                    new RdfDbVariantLoadOptions(), null, params(), ReportNode.NO_OP).network();
            assertThat(day.getExtension(RdfDbProvenance.class).isVariantMode()).isTrue();

            // (3) a variant export. It writes the successor of the variant's own snapshot, so the network is
            // loaded at the head of the timestep: a chain is linear, and 1.0 is no longer its head here
            Network exporter = load(db, S, null, null);
            exporter.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "e");
            assertThat(exporter.getExtension(RdfDbProvenance.class).isVariantMode()).isFalse();
            exporter.getVariantManager().setWorkingVariant("e");
            List<NetworkEvent> events = Changes.record(exporter, n -> Changes.moveLoad(n, 3.0));
            exporter.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
            RdfDbExport.exportVariant(exporter, events, db, "e", null,
                    new CgmesDiffExport.ExportOptions(), ReportNode.NO_OP);
            assertThat(exporter.getExtension(RdfDbProvenance.class).isVariantMode()).isTrue();

            // ... and a refused first opt-in switches it on too, which is what makes the answer usable
            db.snapshots(OTHER).putFull(be(), null, SnapshotRef.of(OTHER, "1.0"), params(), ReportNode.NO_OP);
            Network refused = load(db, OTHER, "1.0", null);
            assertThat(RdfDbNetworkLoader.update(refused, db, SnapshotRef.of(S, "1.1"),
                    new RdfDbUpdateOptions().setTargetVariant("A"), params(), ReportNode.NO_OP).route())
                    .isEqualTo(UpdateResult.Route.VARIANT_REFUSED);
            assertThat(refused.getExtension(RdfDbProvenance.class).isVariantMode()).isTrue();
        }
    }

    /**
     * A cross-scenario refusal is an opt-in like any other refusal.
     *
     * <p>It is the one refusal that happens before a query is sent, so it used to return before the mode was
     * switched on &mdash; and the next classic update then answered {@code FULL_RELOAD} and handed the caller a
     * <em>new network object</em>, on a network it is holding variants of. R4 says a refused first opt-in leaves
     * the mode on; this pins it for that path too.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aCrossScenarioRefusalIsStillAnOptIn(String backend) {
        try (RdfDbConnection db = threeVersions(backend)) {
            db.snapshots(OTHER).putFull(be(), null, SnapshotRef.of(OTHER, "1.0"), params(), ReportNode.NO_OP);
            Network network = load(db, OTHER, "1.0", null);
            Map<String, String> before = xiidmPerVariant(network);

            // The first opt-in, refused without a query because the scenarios differ
            UpdateResult first = RdfDbNetworkLoader.update(network, db, SnapshotRef.of(S, "1.1"),
                    new RdfDbUpdateOptions().setTargetVariant("A"), params(), ReportNode.NO_OP);
            assertThat(first.route()).isEqualTo(UpdateResult.Route.VARIANT_REFUSED);

            // The network is in variant mode now, so the classic update refuses too instead of rebuilding
            UpdateResult classic = RdfDbNetworkLoader.update(network, db, SnapshotRef.of(S, "1.1"),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP);
            assertThat(classic.route()).isEqualTo(UpdateResult.Route.VARIANT_REFUSED);
            assertThat(classic.isReplacement()).isFalse();
            assertThat(classic.network()).isSameAs(network);
            assertThat(network.getVariantManager().getVariantIds()).doesNotContain("A");
            assertThat(xiidmPerVariant(network)).isEqualTo(before);
        }
    }

    /** An older store carries no variant-safety flag, and the network aware check still refuses. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void anOlderStoreWithoutTheFlagStillRefusesAtApply(String backend) {
        try (RdfDbConnection db = rootOnly(backend)) {
            Network sender = load(db, S, "1.0", null);
            String line = sender.getLineStream().map(l -> l.getId()).sorted().findFirst().orElseThrow();
            record(sender, db, SnapshotRef.of(S, "1.1"),
                n -> n.getLine(line).setR(n.getLine(line).getR() + 1.0));
            // Make the store look like one written before the flag existed. The prefix has to be the real one:
            // a DELETE against a wrong namespace matches nothing and the test would pass on the plan-time path
            assertThat(variantSafeFlags(db) > 0).isTrue();
            db.sparql(S).update(RdfDbVocabulary.PREFIXES + "DELETE { GRAPH <"
                    + RdfDbNames.metaGraph(S) + "> { ?m pdb:variantSafe ?v } } WHERE { GRAPH <"
                    + RdfDbNames.metaGraph(S) + "> { ?m pdb:variantSafe ?v } }");
            assertThat(variantSafeFlags(db)).as("the flags really have to be gone").isEqualTo(0L);

            Network network = load(db, S, "1.0", null);
            Map<String, String> before = xiidmPerVariant(network);

            UpdateResult result = bring(network, db, "1.1", null, "A");

            assertThat(result.route()).isEqualTo(UpdateResult.Route.VARIANT_REFUSED);
            // The network aware reason, which only the apply-time check can produce: the plan-time one names the
            // difference and the snapshot, this one names the CGMES property and the IIDM field
            assertThat(String.join(" ", result.reasons()))
                    .contains("ACLineSegment.r")
                    .contains("branch impedances are not stored per variant");
            assertThat(network.getVariantManager().getVariantIds()).doesNotContain("A");
            assertThat(xiidmPerVariant(network)).isEqualTo(before);
        }
    }

    // ------------------------------------------------------------------ what a user may do to the variants

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void userRemovedVariantAndUserClonedVariant(String backend) {
        try (RdfDbConnection db = threeVersions(backend)) {
            Network network = load(db, S, "1.0", null);
            bring(network, db, "1.1", null, "A");

            // A clone the user made is bound to the same snapshot, and can be updated from there
            network.getVariantManager().cloneVariant("A", "A-copy");
            assertThat(network.getExtension(RdfDbProvenance.class).variantBinding("A-copy").orElseThrow()
                    .version()).isEqualTo("1.1");
            assertThat(bring(network, db, "1.2", null, "A-copy").route())
                    .isEqualTo(UpdateResult.Route.DIFF_APPLIED);

            // A removed variant is forgotten, and asking for it again creates it from scratch
            network.getVariantManager().removeVariant("A");
            assertThat(network.getExtension(RdfDbProvenance.class).variantBinding("A")).isEmpty();
            assertThat(bring(network, db, "1.1", null, "A").route())
                    .isEqualTo(UpdateResult.Route.DIFF_APPLIED);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void unboundVariantIsAClearError(String backend) {
        try (RdfDbConnection db = threeVersions(backend)) {
            Network network = Network.read(be(), params());
            network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "stray");
            // Bind another variant so that the network is in variant mode at all
            RdfDbNetworkLoader.update(network, db, SnapshotRef.of(S, "1.1"),
                    new RdfDbUpdateOptions().setTargetVariant("A"), params(), ReportNode.NO_OP);

            assertThatThrownBy(() -> bring(network, db, "1.2", null, "stray"))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("is not bound to a snapshot");
        }
    }

    /**
     * With bound variants, the classic entry point is a variant operation: an unsafe write on the working variant
     * would leak into the bound ones.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void classicApiWithBoundVariantsIsAVariantOperation(String backend) {
        try (RdfDbConnection db = rootOnly(backend)) {
            Network sender = load(db, S, "1.0", null);
            record(sender, db, SnapshotRef.of(S, "1.1"), n -> Changes.moveLoad(n, 11.0));
            String line = sender.getLineStream().map(l -> l.getId()).sorted().findFirst().orElseThrow();
            record(sender, db, SnapshotRef.of(S, "1.2"),
                n -> n.getLine(line).setR(n.getLine(line).getR() + 1.0));

            Network network = load(db, S, "1.0", null);
            bring(network, db, "1.1", null, "A");
            Map<String, String> before = xiidmPerVariant(network);

            // No variant named: the working variant is the primary, and the equipment difference is refused
            UpdateResult result = RdfDbNetworkLoader.update(network, db, SnapshotRef.of(S, "1.2"),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.VARIANT_REFUSED);
            assertThat(result.variantId()).isEqualTo(VariantManagerConstants.INITIAL_VARIANT_ID);
            assertThat(xiidmPerVariant(network)).isEqualTo(before);
        }
    }

    /** The pre-snapshot entry points say so rather than writing into every variant at once. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void modelLevelEntryPointsAreRefusedInVariantMode(String backend) {
        try (RdfDbConnection db = threeVersions(backend)) {
            Network network = load(db, S, "1.0", null);
            bring(network, db, "1.1", null, "A");

            assertThatThrownBy(() -> RdfDbNetworkLoader.update(network, db, S,
                    DiffTarget.models(Map.of(com.powsybl.cgmes.model.CgmesSubset.STEADY_STATE_HYPOTHESIS,
                            "urn:uuid:whatever")), new RdfDbUpdateOptions(), params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("variant mode addresses snapshots");

            assertThatThrownBy(() -> RdfDbNetworkLoader.update(network, db, S, RdfDbLoadOptions.forUpdate(),
                    params(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("variant mode addresses snapshots");
        }
    }

    /**
     * F1: cloning a variant is the ordinary IIDM idiom for a security analysis and must not change any classic
     * route. Only naming a target variant, or loadVariants, is the opt-in.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aUserCloneAloneDoesNotSwitchToVariantMode(String backend) {
        try (RdfDbConnection db = rootOnly(backend)) {
            Network sender = load(db, S, "1.0", null);
            String line = sender.getLineStream().map(l -> l.getId()).sorted().findFirst().orElseThrow();
            record(sender, db, SnapshotRef.of(S, "1.1"),
                n -> n.getLine(line).setR(n.getLine(line).getR() + 1.0));

            Network network = load(db, S, "1.0", null);
            network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "contingency-1");
            // The clone is tracked, so that a later opt-in knows it is there ...
            assertThat(network.getExtension(RdfDbProvenance.class).variantBindings())
                    .containsKey("contingency-1");

            // ... but the classic update still applies the equipment difference in place, as it always did
            UpdateResult classic = RdfDbNetworkLoader.update(network, db, SnapshotRef.of(S, "1.1"),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP);
            assertThat(classic.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            assertThat(network.getLine(line).getR()).isEqualTo(sender.getLine(line).getR());

            // ... and the pre-snapshot entry points are not refused *because of variant mode*. They may still
            // refuse for their own reasons - a versioned scenario keeps no instance-file contexts - and that is
            // exactly the message a caller got before this feature existed
            Throwable replacement = catchThrowable(() -> RdfDbNetworkLoader.update(network, db, S,
                    RdfDbLoadOptions.forUpdate(), params(), ReportNode.NO_OP));
            assertThat(replacement == null ? "" : String.valueOf(replacement.getMessage()))
                    .doesNotContain("variant mode addresses snapshots");

            // Naming a variant is the opt-in, and it is sticky
            Network second = load(db, S, "1.0", null);
            second.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "contingency-1");
            assertThat(bring(second, db, "1.1", null, "A").route())
                    .isEqualTo(UpdateResult.Route.VARIANT_REFUSED);
            assertThat(RdfDbNetworkLoader.update(second, db, SnapshotRef.of(S, "1.1"),
                    new RdfDbUpdateOptions(), params(), ReportNode.NO_OP).route())
                    .isEqualTo(UpdateResult.Route.VARIANT_REFUSED);
        }
    }

    /** F1, export side: a user clone must not change what the classic snapshot export does either. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aUserCloneDoesNotChangeTheClassicExport(String backend) {
        try (RdfDbConnection db = rootOnly(backend)) {
            Network sender = load(db, S, "1.0", null);
            sender.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "contingency-1");
            String sshBefore = sender.getExtension(RdfDbProvenance.class).modelIds()
                    .get(com.powsybl.cgmes.model.CgmesSubset.STEADY_STATE_HYPOTHESIS);

            record(sender, db, SnapshotRef.of(S, "1.1"), n -> Changes.moveLoad(n, 9.0));

            // The primary advanced, exactly as it always did
            assertThat(sender.getExtension(RdfDbProvenance.class).modelIds()
                    .get(com.powsybl.cgmes.model.CgmesSubset.STEADY_STATE_HYPOTHESIS))
                    .isNotEqualTo(sshBefore);
            assertThat(db.snapshots(S).find(SnapshotRef.of(S, "1.1"))).isPresent();
        }
    }

    /** A file-loaded network is recognised by its model identifiers and gets a provenance of its own. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aFileLoadedNetworkCanBeGivenVariants(String backend) {
        try (RdfDbConnection db = threeVersions(backend)) {
            Network network = Network.read(be(), params());

            UpdateResult result = RdfDbNetworkLoader.update(network, db, SnapshotRef.of(S, "1.1"),
                    new RdfDbUpdateOptions().setTargetVariant("A"), params(), ReportNode.NO_OP);

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            RdfDbProvenance provenance = network.getExtension(RdfDbProvenance.class);
            assertThat(provenance.scenario()).isEqualTo(S);
            assertThat(provenance.variantBinding("A").orElseThrow().version()).isEqualTo("1.1");
            assertThat(provenance.variantBinding(VariantManagerConstants.INITIAL_VARIANT_ID).orElseThrow()
                    .version()).isEqualTo("1.0");
        }
    }

    /** A timestep is an address like any other: a variant may stand for another moment of the day. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    void aVariantMayStandForAnotherTimestep(String backend) {
        try (RdfDbConnection db = rootOnly(backend)) {
            db.snapshots(S).putAsDiff(TimestepFixtures.ssh(2, "2014-06-01T11:00:00Z", "t11"), null,
                    new SnapshotRef(S, "1.0", "2014-06-01T11:00:00Z"), params(), ReportNode.NO_OP);

            Network network = load(db, S, "1.0", null);
            Map<String, String> before = xiidmPerVariant(network);
            UpdateResult result = bring(network, db, "1.0", "2014-06-01T11:00:00Z", "T11");

            assertThat(result.route()).isEqualTo(UpdateResult.Route.DIFF_APPLIED);
            VariantBinding binding = network.getExtension(RdfDbProvenance.class)
                    .variantBinding("T11").orElseThrow();
            assertThat(binding.timestep()).isEqualTo("2014-06-01T11:00:00Z");
            assertThat(xiidmPerVariant(network).get(VariantManagerConstants.INITIAL_VARIANT_ID))
                    .isEqualTo(before.get(VariantManagerConstants.INITIAL_VARIANT_ID));
            Load load = network.getLoad(Changes.LOAD_ID);
            assertThat(load).isNotNull();
            assertThat(BASE_TIMESTEP).isEqualTo(network.getExtension(RdfDbProvenance.class)
                    .variantBinding(VariantManagerConstants.INITIAL_VARIANT_ID).orElseThrow().timestep());
        }
    }
}
