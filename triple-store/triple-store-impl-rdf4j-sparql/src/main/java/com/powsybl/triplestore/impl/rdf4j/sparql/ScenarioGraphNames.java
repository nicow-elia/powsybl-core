/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.triplestore.impl.rdf4j.sparql;

import com.powsybl.commons.PowsyblException;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * How a powsybl context name and a scenario become one graph IRI, and back.
 *
 * <p>One database holds the CGMES data of many days and many base grid models at once. What keeps them apart is
 * the <em>scenario</em>: a free-form name such as {@code 2026-09-18} that says which base grid model the graphs
 * belong to. Every graph of a scenario lives under {@code contexts:<scenario>/}, so listing, clearing and querying
 * a scenario is a prefix operation and never touches another one.</p>
 *
 * <p>Above the triple store nothing knows about this. The CGMES conversion depends on graph names being instance
 * <em>file</em> names &mdash; it reads the subset of a model out of them, and tells boundary base voltages from
 * ordinary ones the same way &mdash; so the store translates in both directions:
 * {@code contexts:X_EQ.xml} &harr; {@code contexts:2026-09-18/X_EQ.xml}.</p>
 *
 * <p>Both halves are percent-encoded, because CGMES file names are not IRI-safe: the CGMES 3 Svedala fixture has a
 * space in every file name, and a scenario is whatever a user typed.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class ScenarioGraphNames {

    /** The prefix every powsybl context name carries, with or without a scenario. */
    public static final String CONTEXTS = "contexts:";

    /** The longest a scenario name may be, in characters. */
    public static final int MAX_SCENARIO_LENGTH = 128;

    private ScenarioGraphNames() {
    }

    /**
     * Check that a string can be used as a scenario name, and return it unchanged.
     *
     * <p>A scenario is free-form on purpose &mdash; a date, a business process name, a case identifier &mdash; but
     * it becomes one segment of a graph IRI, so it must not be blank, must not contain a slash (that would forge a
     * second segment and let one scenario address another's graphs) and must not contain control characters.</p>
     *
     * @param scenario the scenario name
     * @return the scenario name
     * @throws PowsyblException      if the name cannot be used
     * @throws NullPointerException  if the name is {@code null}
     */
    public static String requireValidScenario(String scenario) {
        Objects.requireNonNull(scenario, "a scenario name is required");
        if (scenario.isBlank()) {
            throw new PowsyblException("A scenario name must not be blank");
        }
        if (scenario.length() > MAX_SCENARIO_LENGTH) {
            throw new PowsyblException("A scenario name must not be longer than " + MAX_SCENARIO_LENGTH
                    + " characters, got " + scenario.length());
        }
        if (scenario.indexOf('/') >= 0) {
            throw new PowsyblException("A scenario name must not contain '/': " + scenario);
        }
        for (int i = 0; i < scenario.length(); i++) {
            char c = scenario.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                throw new PowsyblException("A scenario name must not contain whitespace or control characters: '"
                        + scenario + "'");
            }
        }
        return scenario;
    }

    /**
     * The IRI prefix shared by every graph of a scenario.
     *
     * @param scenario the scenario name
     * @return {@code contexts:<encoded scenario>/}
     */
    public static String prefix(String scenario) {
        return CONTEXTS + encode(requireValidScenario(scenario)) + "/";
    }

    /**
     * The graph IRI of a context of a scenario.
     *
     * @param scenario    the scenario name
     * @param contextName the powsybl context name, with or without the {@code contexts:} prefix
     * @return the graph IRI
     */
    public static String remoteGraph(String scenario, String contextName) {
        return prefix(scenario) + encode(localName(contextName));
    }

    /**
     * The context name a graph IRI of a scenario stands for.
     *
     * @param scenario    the scenario name
     * @param remoteGraph the graph IRI
     * @return {@code contexts:<file name>}, or {@code null} when the IRI does not belong to the scenario
     */
    public static String localContextName(String scenario, String remoteGraph) {
        String p = prefix(scenario);
        if (remoteGraph == null || !remoteGraph.startsWith(p)) {
            return null;
        }
        return CONTEXTS + decode(remoteGraph.substring(p.length()));
    }

    /**
     * The scenario a graph IRI belongs to.
     *
     * @param remoteGraph the graph IRI
     * @return the decoded scenario name, or {@code null} when the IRI is not a scenario graph
     */
    public static String scenarioOf(String remoteGraph) {
        if (remoteGraph == null || !remoteGraph.startsWith(CONTEXTS)) {
            return null;
        }
        String rest = remoteGraph.substring(CONTEXTS.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return null;
        }
        return decode(rest.substring(0, slash));
    }

    /**
     * The context name without the {@code contexts:} prefix.
     *
     * @param contextName the powsybl context name, with or without the prefix
     * @return the bare name
     */
    public static String localName(String contextName) {
        Objects.requireNonNull(contextName);
        return contextName.startsWith(CONTEXTS) ? contextName.substring(CONTEXTS.length()) : contextName;
    }

    /**
     * Percent-encode everything that is not unreserved in an IRI path segment.
     *
     * @param s the string to encode
     * @return the encoded string
     */
    public static String encode(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (isUnreserved(c)) {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return sb.toString();
    }

    /**
     * The inverse of {@link #encode(String)}.
     *
     * @param s the encoded string
     * @return the decoded string
     */
    public static String decode(String s) {
        byte[] out = new byte[s.length()];
        int n = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                out[n++] = (byte) Integer.parseInt(s.substring(i + 1, i + 3), 16);
                i += 3;
            } else {
                out[n++] = (byte) c;
                i++;
            }
        }
        return new String(out, 0, n, StandardCharsets.UTF_8);
    }

    private static boolean isUnreserved(int c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                || c == '-' || c == '.' || c == '_' || c == '~';
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
}
