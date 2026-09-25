/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import java.time.ZonedDateTime;
import java.util.regex.Pattern;

/**
 * The address of a stored grid state: {@code (scenario, timestep, version)}.
 *
 * <p>All three keys are explicit, and the scenario is <strong>required</strong>. A scenario is one base grid model
 * &mdash; one day &mdash; and a database is expected to hold several of them; guessing which one a caller meant
 * would silently read or write the wrong day, so there is no default scenario and no "latest scenario" anywhere in
 * this package.</p>
 *
 * <p>The other two keys may be left open:</p>
 * <ul>
 *   <li>{@code timestep == null} means the <em>base timestep</em> of that scenario, which is the timestep of its
 *       root snapshot. It is resolved against the database rather than guessed here, because only the scenario
 *       knows which day it describes.</li>
 *   <li>{@code version == null} means the head of that timestep's version chain, see {@link #latest(String)}.</li>
 * </ul>
 *
 * <p>A version is a free label such as {@code "1.1"} or {@code "study-a"}; its order is defined by the chain the
 * database holds, never by comparing the strings. A timestep is stored as a canonical ISO instant in UTC, and any
 * text {@link Timesteps} accepts &mdash; an instant, an offset date-time, a {@code "8:30"} label of the scenario's
 * base day &mdash; may be passed where a timestep is expected.</p>
 *
 * @param scenario the scenario, required and never blank
 * @param version  the version label, or {@code null} for the head of the chain
 * @param timestep the canonical timestep, or {@code null} for the base timestep of the scenario
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record SnapshotRef(String scenario, String version, String timestep) {

    /** What a version label may look like. */
    static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /**
     * @param scenario see {@link #scenario()}
     * @param version  see {@link #version()}
     * @param timestep see {@link #timestep()}
     */
    public SnapshotRef {
        RdfDbNames.checkScenario(scenario);
        if (version != null && !VERSION.matcher(version).matches()) {
            throw new RdfDbException("\"" + version + "\" is not a version label: expected 1 to 64 characters of"
                    + " A-Z, a-z, 0-9, '.', '_' or '-'");
        }
    }

    /**
     * A version at the base timestep of a scenario.
     *
     * @param scenario the scenario
     * @param version  the version label
     * @return the reference
     */
    public static SnapshotRef of(String scenario, String version) {
        return new SnapshotRef(scenario, version, null);
    }

    /**
     * A version at a named timestep of a scenario.
     *
     * @param scenario the scenario
     * @param version  the version label, or {@code null} for the head of that timestep
     * @param timestep the timestep as an ISO instant, an offset date-time or a local date-time (which is read as
     *                 UTC, because that is the form a CGMES header usually carries), or {@code null} for the base
     *                 timestep. A {@code "8:30"} label means nothing without a scenario and is rejected here; use
     *                 {@link #of(String, String, SnapshotCatalog)} or {@code SnapshotCatalog.resolve} for it
     * @return the reference
     */
    public static SnapshotRef of(String scenario, String version, String timestep) {
        return new SnapshotRef(scenario, version,
                timestep == null || timestep.isBlank() ? null : Timesteps.canonical(timestep));
    }

    /**
     * A version at a named timestep of a scenario.
     *
     * @param scenario the scenario
     * @param version  the version label, or {@code null}
     * @param timestep the timestep
     * @return the reference
     */
    public static SnapshotRef of(String scenario, String version, ZonedDateTime timestep) {
        return new SnapshotRef(scenario, version, timestep == null ? null : Timesteps.canonical(timestep));
    }

    /**
     * An address whose timestep may be a label, resolved against the scenario the catalogue is bound to.
     *
     * <p>A label such as {@code "8:30"} means that wall time <em>on the base day of that scenario</em>, so it
     * cannot be turned into an instant without asking the scenario which day it describes. This is the form to use
     * wherever a user types a timestep: the catalogue reads its own {@code pdb:Catalog} node and never another
     * scenario's.</p>
     *
     * @param version      the version label, or {@code null} for the newest one
     * @param timestepText the timestep as an ISO instant, an offset date-time, a local date-time read as UTC, a
     *                     {@code "8:30"} label of that scenario's base day, or {@code null} for the base timestep
     * @param catalog      the snapshot catalogue of the scenario this address belongs to
     * @return the reference, carrying {@code catalog.scenario()}
     */
    public static SnapshotRef of(String version, String timestepText, SnapshotCatalog catalog) {
        return catalog.resolve(version, timestepText);
    }

    /**
     * The newest version of the base timestep of a scenario.
     *
     * @param scenario the scenario
     * @return the reference
     */
    public static SnapshotRef latest(String scenario) {
        return new SnapshotRef(scenario, null, null);
    }

    /**
     * The newest version of a timestep of a scenario.
     *
     * @param scenario the scenario
     * @param timestep the timestep, or {@code null} for the base timestep
     * @return the reference
     */
    public static SnapshotRef latestAt(String scenario, String timestep) {
        return of(scenario, null, timestep);
    }

    /**
     * @return whether this reference means "the head of the chain" rather than a named version
     */
    public boolean isLatest() {
        return version == null;
    }

    /**
     * @return whether this reference means the base timestep of its scenario
     */
    public boolean isBaseTimestep() {
        return timestep == null;
    }

    /**
     * The canonical form of a moment in time, as the database stores it.
     *
     * @param time the moment
     * @return the ISO instant in UTC, second precision
     */
    public static String canonicalTimestep(ZonedDateTime time) {
        return Timesteps.canonical(time);
    }

    /**
     * The same address at another timestep.
     *
     * @param canonicalTimestep the canonical timestep
     * @return the reference
     */
    public SnapshotRef at(String canonicalTimestep) {
        return new SnapshotRef(scenario, version, canonicalTimestep);
    }

    /**
     * The same address at another version.
     *
     * @param newVersion the version label
     * @return the reference
     */
    public SnapshotRef withVersion(String newVersion) {
        return new SnapshotRef(scenario, newVersion, timestep);
    }

    @Override
    public String toString() {
        return "(" + scenario + ", " + (timestep == null ? "base" : timestep) + ", "
                + (version == null ? "latest" : version) + ")";
    }
}
