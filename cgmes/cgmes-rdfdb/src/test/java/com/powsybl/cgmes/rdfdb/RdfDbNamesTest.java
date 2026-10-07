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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The snapshot IRI carries the whole address, and reading it back costs no request.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbNamesTest {

    private static final String S = "2016-01-01";
    private static final Instant T = Instant.parse("2016-01-01T08:30:00Z");

    @Test
    void aSnapshotIriRoundTripsTheFourKeys() {
        // A modelling authority set is a URI: it holds ':' and '/', which the IRI segment percent-encodes
        String mas = "http://elia.be/CGMES/2.4.15";
        String iri = RdfDbNames.snapshot(S, mas, T, "3");
        assertThat(iri).isEqualTo("http://powsybl.org/rdfdb/2016-01-01/http%3A%2F%2Felia.be%2FCGMES%2F2.4.15"
                + "/snapshot/2016-01-01T08%3A30%3A00Z/3");
        assertThat(RdfDbNames.refOf(iri)).isEqualTo(SnapshotRef.of(S, mas, T, "3"));
        assertThat(RdfDbNames.scenarioOf(iri)).isEqualTo(S);
        assertThat(iri).startsWith(RdfDbNames.scenarioPrefix(S));
    }

    @Test
    void aVersionNameWithSlashesAndColonsRoundTripsThroughTheIri() {
        String mas = "http://elia.be/CGMES/2.4.15";
        String name = "ID/2016-01-01T09:00:00Z";
        String iri = RdfDbNames.snapshot(S, mas, T, name);
        assertThat(iri).endsWith("/snapshot/2016-01-01T08%3A30%3A00Z/ID%2F2016-01-01T09%3A00%3A00Z");
        assertThat(RdfDbNames.refOf(iri)).isEqualTo(SnapshotRef.of(S, mas, T, name));
        assertThat(RdfDbNames.materialized(S, mas, T, name, "SSH")).contains("/ID%2F2016-01-01T09%3A00%3A00Z/SSH");
        assertThat(RdfDbNames.versionNode(S, name))
                .isEqualTo("http://powsybl.org/rdfdb/2016-01-01/version/ID%2F2016-01-01T09%3A00%3A00Z");
    }

    @Test
    void whatIsNotASnapshotIriHasNoAddress() {
        assertThat(RdfDbNames.refOf("http://example.org/other")).isNull();
        assertThat(RdfDbNames.refOf(RdfDbNames.metaGraph(S))).isNull();
        assertThat(RdfDbNames.refOf(RdfDbNames.fullGraph(S, "urn:uuid:x"))).isNull();
        assertThat(RdfDbNames.scenarioOf(RdfDbNames.fullGraph("a.b", "urn:uuid:x"))).isEqualTo("a.b");
    }

    @Test
    void theIrisOfTwoScenariosOrTwoAuthoritiesNeverShareAPrefix() {
        // The prefix ends with a slash, so one scenario never matches another whose name it starts with
        assertThat(RdfDbNames.snapshot("ab", "A", T, "1")).doesNotStartWith(RdfDbNames.scenarioPrefix("a"));
        assertThat(RdfDbNames.snapshot(S, "A", T, "1")).isNotEqualTo(RdfDbNames.snapshot(S, "B", T, "1"));
        assertThat(RdfDbNames.materialized(S, "A", T, "1", "SSH"))
                .isNotEqualTo(RdfDbNames.materialized(S, "B", T, "1", "SSH"));
        assertThat(RdfDbNames.isImmutableGraph(RdfDbNames.materialized(S, "A", T, "1", "SSH") + "/graph")).isTrue();
    }
}
