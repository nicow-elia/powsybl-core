/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The replication of {@link ReplicatedSvedala} is what every scale benchmark stands on, so it is checked on a
 * two-fold replica: it converts, it holds exactly twice the plain model, it contains the plain model (copy one is
 * the identity), its identifiers are unique, and a replicated timestep is a replicated difference.
 *
 * <p>With {@code -Dpowsybl.bench.generate=6,20} the test also generates and caches the named replicas, which is
 * how the campaign fills {@code scratchpad/fixtures} once, and times {@code Network.read} of each.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class ReplicatedSvedalaTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReplicatedSvedalaTest.class);
    private static final Pattern RDF_ID = Pattern.compile("rdf:ID=\"([^\"]*)\"");
    private static final String ANCHOR = "2020-12-02T00:00:00Z";

    @Test
    void aTwoFoldReplicaIsTwiceTheModel() {
        Network plain = Network.read(ReplicatedSvedala.cached(1), BenchMeters.params());
        ReadOnlyDataSource replica = ReplicatedSvedala.replicate(ReplicatedSvedala.cached(1), 2);
        Network twice = Network.read(replica, BenchMeters.params());

        assertTwice(plain, twice, "substations", Network::getSubstationCount);
        assertTwice(plain, twice, "voltage levels", Network::getVoltageLevelCount);
        assertTwice(plain, twice, "lines", Network::getLineCount);
        assertTwice(plain, twice, "two-winding transformers", Network::getTwoWindingsTransformerCount);
        assertTwice(plain, twice, "generators", Network::getGeneratorCount);
        assertTwice(plain, twice, "loads", Network::getLoadCount);
        assertTwice(plain, twice, "shunts", Network::getShuntCompensatorCount);
        assertTwice(plain, twice, "switches", Network::getSwitchCount);
        assertTwice(plain, twice, "terminals", ReplicatedSvedalaTest::terminals);

        // Copy one is the identity: every identifier of the plain model is in the replica
        for (Identifiable<?> identifiable : plain.getIdentifiables()) {
            if (identifiable == plain) {
                continue;
            }
            assertThat(twice.getIdentifiable(identifiable.getId())).as(identifiable.getId()).isNotNull();
        }

        // Every object of the replicated equipment model is declared once
        int plainCount = count(ReplicatedSvedala.read(ReplicatedSvedala.cached(1), SvedalaTimestepFixtures.EQ));
        String eq = ReplicatedSvedala.read(replica, SvedalaTimestepFixtures.EQ);
        Matcher ids = RDF_ID.matcher(eq);
        Set<String> seen = new HashSet<>();
        int count = 0;
        while (ids.find()) {
            seen.add(ids.group(1));
            count++;
        }
        assertThat(seen).hasSize(count);
        assertThat(count).isEqualTo(2 * plainCount);
    }

    @Test
    void aReplicatedTimestepIsAReplicatedDifference() {
        try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.inMemory("replicated-svedala"))) {
            String scenario = "replicated";
            SnapshotCatalog catalog = db.snapshots(scenario);
            catalog.putFull(ReplicatedSvedala.replicate(SvedalaTimestepFixtures.anchor(ANCHOR), 2), null,
                    SnapshotRef.of(scenario, "1.0"), BenchMeters.params(), ReportNode.NO_OP);
            String instant = "2020-12-02T00:15:00Z";
            ReadOnlyDataSource timestep = ReplicatedSvedala.replicate(SvedalaTimestepFixtures.timestep(
                    SvedalaTimestepFixtures.Shape.RICH, 1, instant).dataSource(), 2);
            catalog.putAsDiff(timestep, null, SnapshotRef.of("1.0", "00:15", catalog), BenchMeters.params(),
                    ReportNode.NO_OP);
            SnapshotCatalog.IngestStatistics statistics = catalog.lastIngestStatistics();
            int forward = statistics.forwardStatements().getOrDefault(CgmesSubset.STEADY_STATE_HYPOTHESIS, 0);
            int reverse = statistics.reverseStatements().getOrDefault(CgmesSubset.STEADY_STATE_HYPOTHESIS, 0);
            LOGGER.info("replicated x2 rich timestep: forward {} reverse {} statement(s)", forward, reverse);
            assertThat(forward).isEqualTo(2 * 311);
            assertThat(reverse).isEqualTo(2 * 311);

            Network loaded = RdfDbNetworkLoader.load(db, new SnapshotRef(scenario, "1.0", instant), null,
                    BenchMeters.params(), ReportNode.NO_OP);
            assertThat(loaded.getLoadCount()).isEqualTo(2 * Network.read(ReplicatedSvedala.cached(1),
                    BenchMeters.params()).getLoadCount());
        }
    }

    /** Opt-in: generate and cache the replicas named by {@code powsybl.bench.generate}, and time a read of each. */
    @Test
    void generateOnRequest() {
        String generate = System.getProperty("powsybl.bench.generate");
        if (generate == null) {
            return;
        }
        for (String n : generate.split(",")) {
            int replicas = Integer.parseInt(n.trim());
            long start = System.nanoTime();
            ReadOnlyDataSource source = ReplicatedSvedala.cached(replicas);
            long cached = (System.nanoTime() - start) / 1_000_000;
            BenchMeters.resetPeak();
            long readStart = System.nanoTime();
            Network network = Network.read(source, BenchMeters.params());
            long read = (System.nanoTime() - readStart) / 1_000_000;
            long peak = BenchMeters.peakHeap();
            long withNetwork = BenchMeters.heapAfterGc();
            String counts = network.getSubstationCount() + " substations " + network.getVoltageLevelCount()
                    + " voltage levels " + network.getLineCount() + " lines " + network.getGeneratorCount()
                    + " generators " + network.getLoadCount() + " loads " + network.getSwitchCount() + " switches "
                    + terminals(network) + " connectable terminals";
            network = null;
            long held = Math.max(0, withNetwork - BenchMeters.heapAfterGc());
            LOGGER.info("svedala x{}: generation {} ms (this call {} ms), Network.read {} ms, network heap after GC"
                            + " {} MB, peak {} MB; {}", replicas, ReplicatedSvedala.generationMillis(replicas),
                    cached, read, BenchMeters.mb(held), BenchMeters.mb(peak), counts);
        }
    }

    private static int count(String xml) {
        Matcher ids = RDF_ID.matcher(xml);
        int count = 0;
        while (ids.find()) {
            count++;
        }
        return count;
    }

    static int terminals(Network network) {
        return network.getConnectableStream().mapToInt(c -> c.getTerminals().size()).sum();
    }

    private static void assertTwice(Network plain, Network twice, String what, ToIntFunction<Network> count) {
        assertThat(count.applyAsInt(twice)).as(what).isEqualTo(2 * count.applyAsInt(plain));
    }
}
