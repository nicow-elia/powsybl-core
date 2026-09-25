/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The keys of an address: the scenario that may never be guessed, the version label, and the timestep forms.
 *
 * <p>Pure: no database is involved, which is the point &mdash; everything here has to be decidable before a query
 * is sent, so that a wrong address never reaches the database at all.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class SnapshotRefTest {

    private static final String S = "2016-01-01";

    @Test
    void aScenarioIsRequired() {
        assertThatThrownBy(() -> SnapshotRef.of(null, "1.0")).isInstanceOf(RdfDbException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> SnapshotRef.of("", "1.0")).isInstanceOf(RdfDbException.class);
        assertThatThrownBy(() -> SnapshotRef.of("  ", "1.0")).isInstanceOf(RdfDbException.class);
        assertThatThrownBy(() -> SnapshotRef.latest(null)).isInstanceOf(RdfDbException.class);
    }

    @Test
    void theOpenKeysAreTheVersionAndTheTimestep() {
        SnapshotRef latest = SnapshotRef.latest(S);
        assertThat(latest.isLatest()).isTrue();
        assertThat(latest.isBaseTimestep()).isTrue();
        assertThat(latest.scenario()).isEqualTo(S);
        assertThat(latest.toString()).isEqualTo("(2016-01-01, base, latest)");

        SnapshotRef named = SnapshotRef.of(S, "1.1", "2016-01-01T08:30:00Z");
        assertThat(named.isLatest()).isFalse();
        assertThat(named.isBaseTimestep()).isFalse();
        assertThat(named.timestep()).isEqualTo("2016-01-01T08:30:00Z");
    }

    @Test
    void versionLabels() {
        assertThat(SnapshotRef.of(S, "1.1").version()).isEqualTo("1.1");
        assertThat(SnapshotRef.of(S, "study-a_2").version()).isEqualTo("study-a_2");
        assertThatThrownBy(() -> SnapshotRef.of(S, "has space")).isInstanceOf(RdfDbException.class)
                .hasMessageContaining("not a version label");
        assertThatThrownBy(() -> SnapshotRef.of(S, "a/b")).isInstanceOf(RdfDbException.class);
        assertThatThrownBy(() -> SnapshotRef.of(S, "")).isInstanceOf(RdfDbException.class);
    }

    @Test
    void everyTimestepFormCanonicalisesToTheSameInstant() {
        String expected = "2016-01-01T08:30:00Z";
        assertThat(SnapshotRef.of(S, "1.0", "2016-01-01T08:30:00Z").timestep()).isEqualTo(expected);
        assertThat(SnapshotRef.of(S, "1.0", "2016-01-01T09:30:00+01:00").timestep()).isEqualTo(expected);
        // A CGMES header often writes a local date-time, which is read as UTC
        assertThat(SnapshotRef.of(S, "1.0", "2016-01-01T08:30:00").timestep()).isEqualTo(expected);
        assertThat(SnapshotRef.of(S, "1.0",
                ZonedDateTime.of(2016, 1, 1, 8, 30, 0, 123_000_000, ZoneOffset.UTC)).timestep())
                .isEqualTo(expected);
        assertThat(SnapshotRef.of(S, "1.0", (String) null).timestep()).isNull();
        assertThat(SnapshotRef.of(S, "1.0", "").timestep()).isNull();
    }

    @Test
    void anImpossibleTimestepIsRefusedWithTheFormsThatWork() {
        assertThatThrownBy(() -> SnapshotRef.of(S, "1.0", "not a time"))
                .isInstanceOf(RdfDbException.class)
                .hasMessageContaining("is not a timestep")
                .hasMessageContaining("8:30");
    }

    @Test
    void labelsAreDetectedButNotResolvedWithoutAScenario() {
        assertThat(Timesteps.isLabel("8:30")).isTrue();
        assertThat(Timesteps.isLabel("08:30")).isTrue();
        assertThat(Timesteps.isLabel("08:30:15")).isTrue();
        assertThat(Timesteps.isLabel("2016-01-01T08:30:00Z")).isFalse();
        assertThat(Timesteps.isLabel(null)).isFalse();
        // A label is meaningless without the scenario that says which day it is on
        assertThatThrownBy(() -> SnapshotRef.of(S, "1.0", "8:30")).isInstanceOf(RdfDbException.class);
    }

    @Test
    void aLabelIsResolvedAgainstTheBaseDayAndOffsetOfItsScenario() {
        assertThat(Timesteps.resolveLabel("8:30", "2016-01-01T00:00:00Z", "Z"))
                .isEqualTo("2016-01-01T08:30:00Z");
        // The same label, one day whose base is written in +01:00: eight thirty local is seven thirty UTC
        assertThat(Timesteps.resolveLabel("8:30", "2015-12-31T23:00:00Z", "+01:00"))
                .isEqualTo("2016-01-01T07:30:00Z");
        // At -05:00 the base instant 2016-01-01T00:00Z is still the 31st locally, so that is the base day
        assertThat(Timesteps.resolveLabel("08:30:15", "2016-01-01T00:00:00Z", "-05:00"))
                .isEqualTo("2015-12-31T13:30:15Z");
        assertThat(Timesteps.label("2016-01-01T08:30:00Z", "Z")).isEqualTo("08:30");
        assertThat(Timesteps.label("2016-01-01T07:30:00Z", "+01:00")).isEqualTo("08:30");
        assertThatThrownBy(() -> Timesteps.resolveLabel("25:00", "2016-01-01T00:00:00Z", "Z"))
                .isInstanceOf(RdfDbException.class);
        assertThatThrownBy(() -> Timesteps.resolveLabel("8:30", "2016-01-01T00:00:00Z", "nonsense"))
                .isInstanceOf(RdfDbException.class).hasMessageContaining("not a zone offset");
    }

    @Test
    void theIrisCarryTheScenario() {
        String iri = RdfDbNames.snapshot(S, "2016-01-01T08:30:00Z", "1.1");
        assertThat(iri).startsWith(RdfDbNames.scenarioPrefix(S));
        assertThat(RdfDbNames.scenarioOf(iri)).isEqualTo(S);
        assertThat(RdfDbNames.scenarioOf(RdfDbNames.fullGraph("a.b", "urn:uuid:x"))).isEqualTo("a.b");
        assertThat(RdfDbNames.scenarioOf("http://example.org/other")).isNull();
        // The prefix ends with a slash, so one scenario never matches another whose name it starts with
        assertThat(RdfDbNames.snapshot("ab", "2016-01-01T00:00:00Z", "1.0"))
                .doesNotStartWith(RdfDbNames.scenarioPrefix("a"));
    }
}
