/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * The address of a stored grid state: {@code (scenario, modellingAuthority, timestamp, version)}, and whether the
 * version is meant exactly.
 *
 * <p>The four keys together are unique in a database. The <strong>scenario</strong> is required everywhere: it is
 * one base grid model &mdash; one day &mdash; and a database is expected to hold several of them; guessing which
 * one a caller meant would silently read or write the wrong day, so there is no default scenario and no "latest
 * scenario" anywhere in this package. Inside a scenario every <strong>modelling authority</strong> (the
 * {@code md:Model.modelingAuthoritySet} of the instance files, for instance {@code http://elia.be/CGMES/2.4.15})
 * owns one snapshot tree; the trees of one scenario share its boundary and are what a CGM is assembled from
 * ({@link SnapshotCatalog#assembly}).</p>
 *
 * <p>What a snapshot <em>covers</em> &mdash; which CGMES profiles &mdash; is not a key: it is a property of the
 * stored state ({@link SnapshotInfo#profiles()}) and, on every operation, the caller's projection. Making it a key
 * would give one state two addresses.</p>
 *
 * <p>The keys that may be left open:</p>
 * <ul>
 *   <li>{@code timestamp == null} means the <em>base timestamp</em> of the modelling authority's tree, which is the
 *       timestamp of its root snapshot. It is resolved against the database rather than guessed here.</li>
 *   <li>{@code version == null} means the head of that timestamp's version chain on a read, and on a write the
 *       lowest registered version ranking above the head's (the lowest registered one for a new timestamp).</li>
 *   <li>{@code modellingAuthority == null} is accepted by the writes that read instance files
 *       ({@code SnapshotCatalog.putFull}, {@code putAsDiff}) and by {@code putDiff}, which take it from the
 *       equipment and steady state hypothesis headers of what they write. Every read refuses it: guessing the
 *       authority would load another TSO's grid.</li>
 * </ul>
 *
 * <p>"Modelling authority" is spelt the British way on purpose, as the owner of this API writes it; the CGMES term
 * {@code md:Model.modelingAuthoritySet} is quoted verbatim wherever the CGMES term is meant.</p>
 *
 * <p>The timestamp is a {@link Instant}, truncated to the second: one canonical form, so that two writers in two
 * zones cannot disagree about it. A naive or unknown time zone cannot occur in this API at all &mdash; there is no
 * way to build an {@code Instant} without saying where it is &mdash; and the only place a zone-less text is still
 * read is the {@code md:Model.scenarioTime} of a CGMES header, which {@link #scenarioTime(String)} reads as UTC.
 * The version is a <strong>name</strong> registered in the scenario's {@link VersionRegistry} (for instance
 * {@code "DA"}, {@code "ID"}, {@code "RT"}, or {@code "1"}, {@code "2"} in a scenario that registers names as they
 * come); the registry ranks the names, and a new version always ranks above the head it is written on.</p>
 *
 * <p>A read at a named version means the snapshot of the timestamp whose version ranks <em>highest at or below</em>
 * it: asking for {@code "RT"} where the timestamp only reached {@code "ID"} reads {@code "ID"}. An
 * {@linkplain #exactly() exact} address means that version and nothing else, and is absent when the timestamp does
 * not carry it. Writes ignore {@link #exact()}: a write names the version it creates.</p>
 *
 * @param scenario           the scenario, required and never blank
 * @param modellingAuthority the modelling authority set, never blank; {@code null} only on the writes named above
 * @param timestamp          the moment, or {@code null} for the base timestamp of the authority's tree
 * @param version            the version name, never blank, or {@code null} for the head (read) or the next one
 *                           (write)
 * @param exact              whether a read means exactly this version rather than the highest ranking one at or
 *                           below it; ignored by writes, and only allowed with a version
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record SnapshotRef(String scenario, String modellingAuthority, Instant timestamp, String version,
                          boolean exact) {

    public SnapshotRef {
        RdfDbNames.checkScenario(scenario);
        if (modellingAuthority != null && modellingAuthority.isBlank()) {
            throw new RdfDbException("a modelling authority must not be blank: pass the md:Model.modelingAuthoritySet"
                    + " of the instance files, or null on a write to take it from them");
        }
        if (version != null && version.isBlank()) {
            throw new RdfDbException("a version name must not be blank: pass a registered name, or null for the head"
                    + " on a read and the next version on a write");
        }
        if (exact && version == null) {
            throw new RdfDbException("an exact address needs a version name: the head is never exact");
        }
        timestamp = timestamp == null ? null : timestamp.truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * An address whose read means the highest ranking version at or below {@code version}.
     *
     * @param scenario           the scenario
     * @param modellingAuthority the modelling authority set
     * @param timestamp          the moment, or {@code null} for the base timestamp
     * @param version            the version name, or {@code null}
     */
    public SnapshotRef(String scenario, String modellingAuthority, Instant timestamp, String version) {
        this(scenario, modellingAuthority, timestamp, version, false);
    }

    /**
     * An address.
     *
     * @param scenario           the scenario
     * @param modellingAuthority the modelling authority set
     * @param timestamp          the moment, or {@code null} for the base timestamp
     * @param version            the version name, or {@code null}
     * @return the reference
     */
    public static SnapshotRef of(String scenario, String modellingAuthority, Instant timestamp, String version) {
        return new SnapshotRef(scenario, modellingAuthority, timestamp, version);
    }

    /**
     * The newest version of the base timestamp of a modelling authority.
     *
     * @param scenario           the scenario
     * @param modellingAuthority the modelling authority set
     * @return the reference
     */
    public static SnapshotRef latest(String scenario, String modellingAuthority) {
        return new SnapshotRef(scenario, modellingAuthority, null, null);
    }

    /**
     * The newest version of one timestamp of a modelling authority.
     *
     * @param scenario           the scenario
     * @param modellingAuthority the modelling authority set
     * @param timestamp          the moment, or {@code null} for the base timestamp
     * @return the reference
     */
    public static SnapshotRef latestAt(String scenario, String modellingAuthority, Instant timestamp) {
        return new SnapshotRef(scenario, modellingAuthority, timestamp, null);
    }

    /**
     * @return whether this reference means "the head of the chain" rather than a named version
     */
    public boolean isLatest() {
        return version == null;
    }

    /**
     * @return whether this reference means the base timestamp of its modelling authority's tree
     */
    public boolean isBaseTimestamp() {
        return timestamp == null;
    }

    /**
     * The same address at another timestamp.
     *
     * @param newTimestamp the moment
     * @return the reference
     */
    public SnapshotRef at(Instant newTimestamp) {
        return new SnapshotRef(scenario, modellingAuthority, newTimestamp, version, exact);
    }

    /**
     * The same address at another version, not exact.
     *
     * @param newVersion the version name
     * @return the reference
     */
    public SnapshotRef withVersion(String newVersion) {
        return new SnapshotRef(scenario, modellingAuthority, timestamp, newVersion);
    }

    /**
     * The same address meaning exactly its version: a read is absent when the timestamp does not carry that
     * version, instead of falling back to the highest ranking one below it.
     *
     * @return the reference
     * @throws RdfDbException if the address names no version
     */
    public SnapshotRef exactly() {
        return new SnapshotRef(scenario, modellingAuthority, timestamp, version, true);
    }

    /**
     * The same address with the modelling authority filled in.
     *
     * @param authority the modelling authority set
     * @return the reference
     */
    SnapshotRef withAuthority(String authority) {
        return new SnapshotRef(scenario, authority, timestamp, version, exact);
    }

    /**
     * The instant a {@code md:Model.scenarioTime} of a CGMES header means.
     *
     * <p>A header may carry an ISO instant, an offset date-time or &mdash; the MicroGrid conformity files do
     * &mdash; a local date-time without any zone. The last is read as UTC, which is what every reader of a CGMES
     * file that does not state a zone has to assume. This is the only text this package turns into a timestamp.</p>
     *
     * @param text the header literal
     * @return the instant, second precision
     * @throws RdfDbException if the text is none of the three forms
     */
    static Instant scenarioTime(String text) {
        String trimmed = text == null ? "" : text.trim();
        try {
            return OffsetDateTime.parse(trimmed).toInstant().truncatedTo(ChronoUnit.SECONDS);
        } catch (DateTimeParseException notOffset) {
            try {
                return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
            } catch (DateTimeParseException notLocal) {
                RdfDbException failure = new RdfDbException("\"" + text + "\" is not a scenario time: expected an"
                        + " ISO instant, an offset date-time or a local date-time (read as UTC)", notLocal);
                failure.addSuppressed(notOffset);
                throw failure;
            }
        }
    }

    @Override
    public String toString() {
        return "(" + scenario + ", " + (modellingAuthority == null ? "?" : modellingAuthority) + ", "
                + (timestamp == null ? "base" : timestamp.toString()) + ", "
                + (version == null ? "latest" : (exact ? "=" : "") + version) + ")";
    }
}
