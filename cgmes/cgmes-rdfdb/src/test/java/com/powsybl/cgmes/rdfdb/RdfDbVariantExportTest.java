/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport.UnsupportedChangeBehavior;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkEventRecorder;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.events.NetworkEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.powsybl.cgmes.rdfdb.Backends.BE;
import static com.powsybl.cgmes.rdfdb.Backends.microGridBe;
import static com.powsybl.cgmes.rdfdb.Backends.params;
import static com.powsybl.cgmes.rdfdb.Backends.ref;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Writing the changes of one variant, and of every variant, back into the database.
 *
 * <p>A network whose variants are the timestamps of a day holds parallel histories, and an export has to keep them
 * apart: a change recorded on {@code 08:30} becomes the successor of the {@code 08:30} snapshot and of nothing
 * else. The round trip is the assertion &mdash; a second connection loads the successors and they hold what the
 * sender's variants hold.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbVariantExportTest {

    private static final String S = "2016-01-01";
    private static final Instant T0 = Instant.parse("2014-06-01T10:30:00Z");
    private static final Instant T1 = Instant.parse("2014-06-01T11:00:00Z");

    /** A scenario with the base timestamp and one more, and a network whose variants are both. */
    private static RdfDbConnection twoTimestamps(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "variant-export"));
        db.clear(S);
        db.snapshots(S).putFull(microGridBe(), null, ref(S, 1), null, params(), ReportNode.NO_OP);
        db.snapshots(S).putAsDiff(TimestampFixtures.ssh(2, T1, "t1"), null, ref(S, 1, T1), null,
                params(), ReportNode.NO_OP);
        return db;
    }

    private static VariantLoadResult day(RdfDbConnection db) {
        // Named after the wall time, which is what a user calls the two moments of the day
        return RdfDbNetworkLoader.loadVariants(db, S, List.of(new VariantRequest("10:30", ref(S, 1, T0)),
                new VariantRequest("11:00", ref(S, 1, T1))), new RdfDbVariantLoadOptions(), null, params(),
                ReportNode.NO_OP);
    }

    private static List<NetworkEvent> recordOn(Network network, String variant, Consumer<Network> change) {
        String working = network.getVariantManager().getWorkingVariantId();
        network.getVariantManager().setWorkingVariant(variant);
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        network.addListener(recorder);
        try {
            change.accept(network);
        } finally {
            network.removeListener(recorder);
            network.getVariantManager().setWorkingVariant(working);
        }
        return List.copyOf(recorder.getEvents());
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void recordOnTwoVariantsGivesTwoDifferences(String backend) {
        try (RdfDbConnection db = twoTimestamps(backend)) {
            Network sender = day(db).network();
            List<NetworkEvent> events = new ArrayList<>();
            events.addAll(recordOn(sender, "10:30", n -> n.getLoad(Changes.LOAD_ID).setP0(101.0)));
            events.addAll(recordOn(sender, "11:00", n -> n.getLoad(Changes.LOAD_ID).setP0(202.0)));

            Map<String, RdfDbExport.VariantExport> written =
                    RdfDbExport.exportPerVariant(sender, events, db, null, new CgmesDiffExport.ExportOptions(),
                            ReportNode.NO_OP);

            assertThat(written.keySet()).containsExactly("10:30", "11:00");
            assertThat(written.get("10:30").result().snapshot().timestamp()).isEqualTo(T0);
            assertThat(written.get("11:00").result().snapshot().timestamp()).isEqualTo(T1);
            assertThat(written.get("10:30").result().snapshot().version()).isEqualTo("2");
            assertThat(written.get("11:00").result().snapshot().version()).isEqualTo("2");

            // A second process loads the successors and finds what the sender's variants hold
            Network at0 = RdfDbNetworkLoader.load(db, ref(S, 2, T0), null, params(), ReportNode.NO_OP);
            Network at1 = RdfDbNetworkLoader.load(db, ref(S, 2, T1), null, params(), ReportNode.NO_OP);
            assertThat(at0.getLoad(Changes.LOAD_ID).getP0()).isEqualTo(101.0);
            assertThat(at1.getLoad(Changes.LOAD_ID).getP0()).isEqualTo(202.0);

            // Each variant advanced its own identity; the primary did not move
            RdfDbProvenance provenance = sender.getExtension(RdfDbProvenance.class);
            assertThat(provenance.variantBinding("10:30").orElseThrow().version()).isEqualTo("2");
            assertThat(provenance.variantBinding("11:00").orElseThrow().version()).isEqualTo("2");
            assertThat(provenance.variantBinding(VariantManagerConstants.INITIAL_VARIANT_ID).orElseThrow()
                    .version()).isEqualTo("1");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void exportVariantWritesTheSuccessorOfThatVariantsSnapshot(String backend) {
        try (RdfDbConnection db = twoTimestamps(backend)) {
            Network sender = day(db).network();
            double before = loadP0(sender, "11:00");
            List<NetworkEvent> events = recordOn(sender, "11:00",
                n -> n.getLoad(Changes.LOAD_ID).setP0(before + 5.0));

            RdfDbExport.SnapshotResult result = RdfDbExport.exportVariant(sender, events, db, "11:00", null,
                    new CgmesDiffExport.ExportOptions(), ReportNode.NO_OP);

            assertThat(result.snapshot().timestamp()).isEqualTo(T1);
            Network reloaded = RdfDbNetworkLoader.load(db, SnapshotRef.of(S, BE, T1, result.snapshot().version()), null, params(),
                    ReportNode.NO_OP);
            assertThat(reloaded.getLoad(Changes.LOAD_ID).getP0()).isEqualTo(before + 5.0);
            // The base timestamp is untouched
            assertThat(db.snapshots(S).snapshots().stream()
                    .filter(info -> info.timestamp().equals(T0)).count()).isEqualTo(1);
        }
    }

    /** A change IIDM does not store per variant belongs to every variant, so it cannot go into one history. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aSharedChangeIsUnsupportedUnderFail(String backend) {
        try (RdfDbConnection db = twoTimestamps(backend)) {
            Network sender = day(db).network();
            String line = sender.getLineStream().map(Line::getId).sorted().findFirst().orElseThrow();
            List<NetworkEvent> events = recordOn(sender, "11:00", n -> {
                n.getLoad(Changes.LOAD_ID).setP0(77.0);
                n.getLine(line).setR(n.getLine(line).getR() + 1.0);
            });

            assertThatThrownBy(() -> RdfDbExport.exportVariant(sender, events, db, "11:00", null,
                    new CgmesDiffExport.ExportOptions(), ReportNode.NO_OP))
                    .isInstanceOf(PowsyblException.class)
                    .hasMessageContaining("not stored per variant in IIDM");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aSharedChangeIsReportedUnderIgnore(String backend) {
        try (RdfDbConnection db = twoTimestamps(backend)) {
            Network sender = day(db).network();
            String line = sender.getLineStream().map(Line::getId).sorted().findFirst().orElseThrow();
            List<NetworkEvent> events = recordOn(sender, "11:00", n -> {
                n.getLoad(Changes.LOAD_ID).setP0(77.0);
                n.getLine(line).setR(n.getLine(line).getR() + 1.0);
            });

            Map<String, RdfDbExport.VariantExport> written = RdfDbExport.exportPerVariant(sender, events, db, null,
                    new CgmesDiffExport.ExportOptions()
                            .setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE),
                    ReportNode.NO_OP);

            assertThat(written.keySet()).containsExactly("11:00");
            assertThat(written.get("11:00").rejected()).isNotEmpty();
            assertThat(written.get("11:00").result()).isNotNull();
            Network reloaded = RdfDbNetworkLoader.load(db,
                    SnapshotRef.of(S, BE, T1, written.get("11:00").result().snapshot().version()), null, params(), ReportNode.NO_OP);
            assertThat(reloaded.getLoad(Changes.LOAD_ID).getP0()).isEqualTo(77.0);
        }
    }

    /** Under FAIL an unbound variant stops the whole export, with nothing written. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void eventsOnAnUnboundVariantFailBeforeAnythingIsWritten(String backend) {
        try (RdfDbConnection db = twoTimestamps(backend)) {
            Network sender = day(db).network();
            sender.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "stray");
            // The clone inherits the primary's binding, so unbind it the way a user-made variant would be
            ((RdfDbProvenanceImpl) sender.getExtension(RdfDbProvenance.class)).unbind("stray");

            List<NetworkEvent> events = new ArrayList<>();
            events.addAll(recordOn(sender, "11:00", n -> n.getLoad(Changes.LOAD_ID).setP0(55.0)));
            events.addAll(recordOn(sender, "stray", n -> n.getLoad(Changes.LOAD_ID).setP0(66.0)));

            int before = db.snapshots(S).snapshots().size();
            assertThatThrownBy(() -> RdfDbExport.exportPerVariant(sender, events, db, null,
                    new CgmesDiffExport.ExportOptions(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("not bound to a snapshot")
                    .hasMessageContaining("nothing was written");
            assertThat(db.snapshots(S).snapshots()).hasSize(before);

            // Under IGNORE the bound group is written and the stray one is reported
            Map<String, RdfDbExport.VariantExport> written = RdfDbExport.exportPerVariant(sender, events, db, null,
                    new CgmesDiffExport.ExportOptions()
                            .setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE),
                    ReportNode.NO_OP);
            assertThat(written.get("stray").result()).isNull();
            assertThat(written.get("stray").rejected()).isNotEmpty();
            assertThat(written.get("11:00").result()).isNotNull();
        }
    }

    /** A variant's changes go into its own timestamp; asking for another one is an error, not a silent move. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aForeignTimestampIsRefused(String backend) {
        try (RdfDbConnection db = twoTimestamps(backend)) {
            Network sender = day(db).network();
            List<NetworkEvent> events = recordOn(sender, "11:00", n -> n.getLoad(Changes.LOAD_ID).setP0(33.0));

            assertThatThrownBy(() -> RdfDbExport.exportVariant(sender, events, db, "11:00", null,
                    new CgmesDiffExport.ExportOptions()
                            .setScenarioTime(T0.atZone(java.time.ZoneOffset.UTC)),
                    ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("written into its own timestamp");
        }
    }

    /** An explicit version label is used for every group. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anExplicitVersionLabelIsUsed(String backend) {
        try (RdfDbConnection db = twoTimestamps(backend)) {
            Network sender = day(db).network();
            List<NetworkEvent> events = new ArrayList<>();
            events.addAll(recordOn(sender, "10:30", n -> n.getLoad(Changes.LOAD_ID).setP0(11.0)));
            events.addAll(recordOn(sender, "11:00", n -> n.getLoad(Changes.LOAD_ID).setP0(22.0)));

            Map<String, RdfDbExport.VariantExport> written = RdfDbExport.exportPerVariant(sender, events, db,
                    "50", new CgmesDiffExport.ExportOptions(), ReportNode.NO_OP);

            assertThat(written.get("10:30").result().snapshot().version()).isEqualTo("50");
            assertThat(written.get("11:00").result().snapshot().version()).isEqualTo("50");
        }
    }

    /**
     * F2: the model-level export is a variant operation too. It must advance the identity of the <em>working
     * variant</em>, not the primary's, or the next update of either plans from a state that never existed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void theModelLevelExportAdvancesTheWorkingVariant(String backend) {
        try (RdfDbConnection db = twoTimestamps(backend)) {
            Network sender = day(db).network();
            RdfDbProvenance provenance = sender.getExtension(RdfDbProvenance.class);
            String primaryBefore = provenance.variantBinding(VariantManagerConstants.INITIAL_VARIANT_ID)
                    .orElseThrow().modelIds().get(com.powsybl.cgmes.model.CgmesSubset.STEADY_STATE_HYPOTHESIS);
            String variantBefore = provenance.variantBinding("11:00").orElseThrow().modelIds()
                    .get(com.powsybl.cgmes.model.CgmesSubset.STEADY_STATE_HYPOTHESIS);

            List<NetworkEvent> events = recordOn(sender, "11:00", n -> n.getLoad(Changes.LOAD_ID).setP0(88.0));
            sender.getVariantManager().setWorkingVariant("11:00");
            try {
                RdfDbExport.export(sender, events, db, S, new CgmesDiffExport.ExportOptions());
            } finally {
                sender.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
            }

            assertThat(provenance.variantBinding("11:00").orElseThrow().modelIds()
                    .get(com.powsybl.cgmes.model.CgmesSubset.STEADY_STATE_HYPOTHESIS))
                    .as("the variant that holds the new state has to advance").isNotEqualTo(variantBefore);
            assertThat(provenance.variantBinding(VariantManagerConstants.INITIAL_VARIANT_ID).orElseThrow()
                    .modelIds().get(com.powsybl.cgmes.model.CgmesSubset.STEADY_STATE_HYPOTHESIS))
                    .as("the primary still holds its own state, so it must not advance")
                    .isEqualTo(primaryBefore);
        }
    }

    /**
     * R1: naming a variant in an export is an opt-in, exactly as it is in an update.
     *
     * <p>Without it the caller has used the variant API, got a variant standing for a new snapshot, and the very
     * next classic update would still write across every variant of the network.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void exportVariantOptsIn(String backend) {
        try (RdfDbConnection db = twoTimestamps(backend)) {
            // A network that never opted in: loaded at one snapshot, with a clone the user made
            Network network = RdfDbNetworkLoader.load(db, ref(S, 1, T0), null, params(), ReportNode.NO_OP);
            network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "c");
            List<NetworkEvent> events = recordOn(network, "c", n -> n.getLoad(Changes.LOAD_ID).setP0(99.0));

            RdfDbExport.SnapshotResult written = RdfDbExport.exportVariant(network, events, db, "c", null,
                    new CgmesDiffExport.ExportOptions(), ReportNode.NO_OP);
            assertThat(written.snapshot().timestamp()).isEqualTo(T0);

            // From now on the network is in variant mode, so a classic update of an equipment difference is
            // refused instead of writing the impedance into every variant at once
            String line = network.getLineStream().map(Line::getId).sorted().findFirst().orElseThrow();
            Network sender = RdfDbNetworkLoader.load(db, SnapshotRef.latestAt(S, BE, T0), null, params(), ReportNode.NO_OP);
            List<NetworkEvent> drift = Changes.record(sender,
                n -> n.getLine(line).setR(n.getLine(line).getR() + 1.0));
            RdfDbExport.export(sender, drift, db, SnapshotRef.latestAt(S, BE, T0),
                    new CgmesDiffExport.ExportOptions());

            UpdateResult classic = RdfDbNetworkLoader.update(network, db,
                    SnapshotRef.latestAt(S, BE, T0), new RdfDbUpdateOptions(), params(), ReportNode.NO_OP);
            assertThat(classic.route()).isEqualTo(UpdateResult.Route.VARIANT_REFUSED);
            assertThat(network.getExtension(RdfDbProvenance.class).variantBinding("c").orElseThrow().version())
                    .isEqualTo(written.snapshot().version());
        }
    }

    /**
     * R3: in variant mode the classic exports refuse a shared change exactly as {@code exportVariant} does.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void theClassicExportRefusesASharedChangeInVariantMode(String backend) {
        try (RdfDbConnection db = twoTimestamps(backend)) {
            Network sender = day(db).network();
            String line = sender.getLineStream().map(Line::getId).sorted().findFirst().orElseThrow();
            List<NetworkEvent> events = recordOn(sender, "11:00", n -> {
                n.getLoad(Changes.LOAD_ID).setP0(77.0);
                n.getLine(line).setR(n.getLine(line).getR() + 1.0);
            });
            sender.getVariantManager().setWorkingVariant("11:00");
            try {
                assertThatThrownBy(() -> RdfDbExport.export(sender, events, db,
                        SnapshotRef.latestAt(S, BE, T1), new CgmesDiffExport.ExportOptions()))
                        .isInstanceOf(PowsyblException.class)
                        .hasMessageContaining("not stored per variant in IIDM");

                // Under IGNORE the shared change is dropped and the rest is written, as in exportVariant
                RdfDbExport.SnapshotResult written = RdfDbExport.export(sender, events, db,
                        SnapshotRef.latestAt(S, BE, T1), new CgmesDiffExport.ExportOptions()
                                .setUnsupportedChangeBehavior(UnsupportedChangeBehavior.IGNORE),
                        ReportNode.NO_OP);
                assertThat(written.stored()).hasSize(1);
                assertThat(written.stored().get(0).subset())
                        .isEqualTo(com.powsybl.cgmes.model.CgmesSubset.STEADY_STATE_HYPOTHESIS);
            } finally {
                sender.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
            }
        }
    }

    /** F11: two variants standing for the same snapshot are refused before anything is written. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void twoVariantsOnOneSnapshotAreRefusedBeforeAnythingIsWritten(String backend) {
        try (RdfDbConnection db = twoTimestamps(backend)) {
            Network sender = day(db).network();
            sender.getVariantManager().cloneVariant("11:00", "11:00-copy");

            List<NetworkEvent> events = new ArrayList<>();
            events.addAll(recordOn(sender, "11:00", n -> n.getLoad(Changes.LOAD_ID).setP0(11.0)));
            events.addAll(recordOn(sender, "11:00-copy", n -> n.getLoad(Changes.LOAD_ID).setP0(22.0)));

            int before = db.snapshots(S).snapshots().size();
            assertThatThrownBy(() -> RdfDbExport.exportPerVariant(sender, events, db, null,
                    new CgmesDiffExport.ExportOptions(), ReportNode.NO_OP))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("both stand for the snapshot")
                    .hasMessageContaining("nothing was written");
            assertThat(db.snapshots(S).snapshots()).hasSize(before);
        }
    }

    /** A file export of one variant carries that variant's Supersedes and that variant's values. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aFileExportWithAVariantCarriesThatVariantsIdentity(String backend) {
        try (RdfDbConnection db = twoTimestamps(backend)) {
            Network sender = day(db).network();
            RdfDbProvenance provenance = sender.getExtension(RdfDbProvenance.class);
            String sshOfT1 = provenance.variantBinding("11:00").orElseThrow()
                    .modelIds().get(com.powsybl.cgmes.model.CgmesSubset.STEADY_STATE_HYPOTHESIS);
            List<NetworkEvent> events = recordOn(sender, "11:00", n -> n.getLoad(Changes.LOAD_ID).setP0(44.0));

            // Only the public API: inVariant swaps the identity in, setVariant selects the values
            String document = RdfDbProvenance.inVariant(sender, "11:00",
                () -> CgmesDiffExport.toString(sender, events,
                        com.powsybl.cgmes.model.CgmesSubset.STEADY_STATE_HYPOTHESIS,
                        UnsupportedChangeBehavior.FAIL));

            assertThat(document).as("the difference has to supersede the model of the variant, not the primary's")
                    .contains(sshOfT1);
            assertThat(document).contains(">44<");

            // The same for the partial steady state hypothesis export
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            RdfDbProvenance.inVariant(sender, "11:00",
                () -> PartialSshExport.write(sender, events, out,
                        new PartialSshExport.ExportOptions().setVariant("11:00")));
            String ssh = out.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(ssh).contains(sshOfT1);
            assertThat(ssh).contains(">44<");

            assertThat(sender.getVariantManager().getWorkingVariantId())
                    .isEqualTo(VariantManagerConstants.INITIAL_VARIANT_ID);
            // And the network says it is the primary again afterwards
            assertThat(provenance.modelIds().get(com.powsybl.cgmes.model.CgmesSubset.STEADY_STATE_HYPOTHESIS))
                    .isEqualTo(provenance.variantBinding(VariantManagerConstants.INITIAL_VARIANT_ID)
                            .orElseThrow().modelIds()
                            .get(com.powsybl.cgmes.model.CgmesSubset.STEADY_STATE_HYPOTHESIS));
        }
    }
}
