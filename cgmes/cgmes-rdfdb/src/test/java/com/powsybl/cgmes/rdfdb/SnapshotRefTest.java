/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The keys of an address: the scenario that may never be guessed, the modelling authority, the timestamp and the
 * integer version.
 *
 * <p>Pure: no database is involved, which is the point &mdash; everything here has to be decidable before a query
 * is sent, so that a wrong address never reaches the database at all.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class SnapshotRefTest {

    private static final String S = "2016-01-01";
    private static final String MAS = "http://elia.be/CGMES/2.4.15";
    private static final Instant T = Instant.parse("2016-01-01T08:30:00Z");

    @Test
    void aScenarioIsRequired() {
        assertThatThrownBy(() -> SnapshotRef.of(null, MAS, T, 1)).isInstanceOf(RdfDbException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> SnapshotRef.of("", MAS, T, 1)).isInstanceOf(RdfDbException.class);
        assertThatThrownBy(() -> SnapshotRef.of("  ", MAS, T, 1)).isInstanceOf(RdfDbException.class);
        assertThatThrownBy(() -> SnapshotRef.latest(null, MAS)).isInstanceOf(RdfDbException.class);
    }

    @Test
    void aBlankModellingAuthorityIsRefusedAnAbsentOneMeansTakeItFromTheFiles() {
        assertThatThrownBy(() -> SnapshotRef.of(S, "", T, 1)).isInstanceOf(RdfDbException.class)
                .hasMessageContaining("modelling authority must not be blank");
        assertThatThrownBy(() -> SnapshotRef.of(S, "  ", T, 1)).isInstanceOf(RdfDbException.class);
        // null is what a write passes to take the authority from the header of the files; a read refuses it
        assertThat(SnapshotRef.of(S, null, T, 1).modellingAuthority()).isNull();
    }

    @Test
    void aVersionIsAPositiveInteger() {
        assertThat(SnapshotRef.of(S, MAS, T, 1).version()).isEqualTo(1);
        assertThat(SnapshotRef.of(S, MAS, T, 30).version()).isEqualTo(30);
        assertThatThrownBy(() -> SnapshotRef.of(S, MAS, T, 0)).isInstanceOf(RdfDbException.class)
                .hasMessageContaining("version must be at least 1, got 0");
        assertThatThrownBy(() -> SnapshotRef.of(S, MAS, T, -3)).isInstanceOf(RdfDbException.class);
        assertThat(SnapshotRef.latest(S, MAS).withVersion(4).version()).isEqualTo(4);
        assertThatThrownBy(() -> SnapshotRef.latest(S, MAS).withVersion(0)).isInstanceOf(RdfDbException.class);
    }

    @Test
    void theOpenKeysAreTheVersionAndTheTimestamp() {
        SnapshotRef latest = SnapshotRef.latest(S, MAS);
        assertThat(latest.isLatest()).isTrue();
        assertThat(latest.isBaseTimestamp()).isTrue();
        assertThat(latest.scenario()).isEqualTo(S);
        assertThat(latest.modellingAuthority()).isEqualTo(MAS);
        assertThat(latest.toString()).isEqualTo("(2016-01-01, " + MAS + ", base, latest)");

        SnapshotRef named = SnapshotRef.of(S, MAS, T, 2);
        assertThat(named.isLatest()).isFalse();
        assertThat(named.isBaseTimestamp()).isFalse();
        assertThat(named.timestamp()).isEqualTo(T);
        assertThat(named.toString()).isEqualTo("(2016-01-01, " + MAS + ", 2016-01-01T08:30:00Z, 2)");

        SnapshotRef there = SnapshotRef.latestAt(S, MAS, T);
        assertThat(there.isLatest()).isTrue();
        assertThat(there.timestamp()).isEqualTo(T);
        assertThat(latest.at(T)).isEqualTo(there);
    }

    @Test
    void theTimestampIsAnInstantOfSecondPrecision() {
        // A naive date-time cannot even be passed: the key is an Instant, so there is no zone to guess
        assertThat(SnapshotRef.of(S, MAS, Instant.parse("2016-01-01T08:30:00.123Z"), 1).timestamp()).isEqualTo(T);
        assertThat(SnapshotRef.of(S, MAS, OffsetDateTime.of(2016, 1, 1, 9, 30, 0, 0, ZoneOffset.ofHours(1)), 1)
                .timestamp()).isEqualTo(T);
        assertThat(SnapshotRef.of(S, MAS, (Instant) null, 1).timestamp()).isNull();
        assertThat(SnapshotRef.of(S, MAS, (OffsetDateTime) null, 1).timestamp()).isNull();
    }

    @Test
    void aCgmesScenarioTimeIsReadInEveryFormAHeaderCarries() {
        assertThat(SnapshotRef.scenarioTime("2016-01-01T08:30:00Z")).isEqualTo(T);
        assertThat(SnapshotRef.scenarioTime("2016-01-01T09:30:00+01:00")).isEqualTo(T);
        // A CGMES header often writes a local date-time, which is read as UTC
        assertThat(SnapshotRef.scenarioTime("2016-01-01T08:30:00")).isEqualTo(T);
        assertThatThrownBy(() -> SnapshotRef.scenarioTime("not a time")).isInstanceOf(RdfDbException.class)
                .hasMessageContaining("is not a scenario time");
    }
}
