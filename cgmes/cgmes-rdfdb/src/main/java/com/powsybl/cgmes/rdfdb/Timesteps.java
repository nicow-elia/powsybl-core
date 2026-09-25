/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * The timestep key: one canonical form in the database, several convenient forms in the API.
 *
 * <p>A stored timestep is an ISO-8601 <em>instant</em> in UTC with second precision, because that is the only form
 * two writers in two zones cannot disagree about, and because it is exactly what {@code md:Model.scenarioTime} of
 * the members of the snapshot says once it is normalised. Everything else &mdash; an offset date-time, the
 * {@code "8:30"} a scheduler thinks in &mdash; is text a caller may pass and that is resolved here.</p>
 *
 * <p>A label such as {@code "8:30"} is <strong>relative to the scenario it is addressed in</strong>: it means that
 * wall time on the base day of that scenario, in the zone offset that scenario's root was written in. The same
 * label therefore resolves to two different instants in two scenarios whose base days or offsets differ, which is
 * the point &mdash; a day is a scenario, and {@code "8:30"} means eight thirty <em>of that day</em>.</p>
 *
 * <p>The offset of a scenario is fixed at its root and is not a time zone: daylight saving is deliberately not
 * handled, because a base day that crosses a transition would make one wall time ambiguous and the other
 * impossible. Callers working across a transition pass instants.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class Timesteps {

    private static final Pattern LABEL = Pattern.compile("\\d{1,2}:\\d{2}(:\\d{2})?");

    private Timesteps() {
    }

    /**
     * The canonical form of a moment in time.
     *
     * @param time the moment
     * @return the ISO instant in UTC, second precision
     */
    public static String canonical(ZonedDateTime time) {
        return DateTimeFormatter.ISO_INSTANT.format(time.toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    /**
     * The canonical form of an ISO instant or offset date-time written as text.
     *
     * @param isoText the text, for instance {@code 2016-01-01T09:30:00+01:00}
     * @return the ISO instant in UTC, second precision
     * @throws RdfDbException if the text is neither an instant nor an offset date-time
     */
    public static String canonical(String isoText) {
        if (isoText == null || isoText.isBlank()) {
            throw new RdfDbException("a timestep must not be blank");
        }
        String text = isoText.trim();
        try {
            return canonical(Instant.parse(text).atZone(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            // Not an instant; an offset date-time is the other form a CGMES header may carry
            try {
                return canonical(OffsetDateTime.parse(text).toZonedDateTime());
            } catch (DateTimeParseException notOffset) {
                return localDateTime(isoText, text, notOffset);
            }
        }
    }

    /**
     * A local date-time, which is what a CGMES header often carries.
     *
     * <p>{@code md:Model.scenarioTime} is written without a zone by more than one exporter &mdash; the MicroGrid
     * conformity files say {@code 2014-06-01T10:30:00} &mdash; and refusing that would make the ordinary case of
     * "take the timestep from the files" fail. Such a text is read as UTC, which is what every reader of a CGMES
     * file that does not state a zone has to assume, and the scenario's offset is then {@code Z}.</p>
     */
    private static String localDateTime(String original, String text, DateTimeParseException notOffset) {
        try {
            return canonical(java.time.LocalDateTime.parse(text).atOffset(ZoneOffset.UTC).toZonedDateTime());
        } catch (DateTimeParseException notLocal) {
            RdfDbException failure = new RdfDbException("\"" + original + "\" is not a timestep: pass an ISO"
                    + " instant (2016-01-01T08:30:00Z), an offset date-time (2016-01-01T09:30:00+01:00), a local"
                    + " date-time read as UTC (2016-01-01T08:30:00) or a label of the scenario's base day"
                    + " (\"8:30\")", notLocal);
            failure.addSuppressed(notOffset);
            throw failure;
        }
    }

    /**
     * Whether a text is a wall-clock label rather than an ISO date-time.
     *
     * @param text the text
     * @return whether it matches {@code H:MM} or {@code HH:MM(:SS)}
     */
    public static boolean isLabel(String text) {
        return text != null && LABEL.matcher(text.trim()).matches();
    }

    /**
     * The instant a label means on the base day of a scenario.
     *
     * @param label        the label, {@code H:MM} or {@code HH:MM:SS}
     * @param baseTimestep the canonical timestep of the scenario's root
     * @param baseOffset   the zone offset the scenario writes its labels in, for instance {@code Z} or
     *                     {@code +01:00}
     * @return the canonical ISO instant
     * @throws RdfDbException if the label or the offset cannot be read
     */
    public static String resolveLabel(String label, String baseTimestep, String baseOffset) {
        if (!isLabel(label)) {
            throw new RdfDbException("\"" + label + "\" is not a timestep label (expected H:MM or HH:MM:SS)");
        }
        ZoneOffset offset = offset(baseOffset);
        LocalDate day = Instant.parse(baseTimestep).atOffset(offset).toLocalDate();
        String[] parts = label.trim().split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        int second = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        if (hour > 23 || minute > 59 || second > 59) {
            throw new RdfDbException("\"" + label + "\" is not a wall time of a day");
        }
        return canonical(OffsetDateTime.of(day, LocalTime.of(hour, minute, second), offset).toZonedDateTime());
    }

    /**
     * The label a canonical timestep shows as.
     *
     * @param canonicalTimestep the canonical ISO instant
     * @param baseOffset        the zone offset of the scenario
     * @return {@code HH:MM}
     */
    public static String label(String canonicalTimestep, String baseOffset) {
        OffsetDateTime time = Instant.parse(canonicalTimestep).atOffset(offset(baseOffset));
        // Seconds only when there are any: a schedule is on the minute and reads better without them, but two
        // timesteps thirty seconds apart must not end up with the same label
        if (time.getSecond() == 0) {
            return String.format("%02d:%02d", time.getHour(), time.getMinute());
        }
        return String.format("%02d:%02d:%02d", time.getHour(), time.getMinute(), time.getSecond());
    }

    /**
     * The zone offset a scenario writes its labels in, as text.
     *
     * @param time the moment the scenario's root was written for
     * @return {@code Z} or {@code +01:00}
     */
    public static String offsetOf(ZonedDateTime time) {
        return time.getOffset().getId();
    }

    private static ZoneOffset offset(String text) {
        if (text == null || text.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneOffset.of(text.trim());
        } catch (java.time.DateTimeException e) {
            throw new RdfDbException("\"" + text + "\" is not a zone offset", e);
        }
    }
}
