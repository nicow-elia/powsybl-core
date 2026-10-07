/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The names of the profiles a database stores: the nine CGMES subsets, and any other name a caller ships a file of.
 *
 * <p>A profile is a name, not an enumeration. The nine standard ones are what the CGMES conversion reads and what a
 * difference can describe; a <em>custom</em> profile &mdash; an operational configuration, a market overlay, a
 * TSO's own extension &mdash; is anything else a caller stores next to them. The database keeps a custom profile as
 * a whole graph, never as a difference, never hands it to the conversion, and gives it back to the caller as a
 * graph (see {@code RdfDbNetworkLoader.LoadResult#extraProfiles()}).</p>
 *
 * <p>A name is {@code [A-Z][A-Z0-9_]*}: the identifiers of the nine ({@code EQ}, {@code EQ_BD}, …) are of that
 * shape, it is what the last token of a CGMES file name looks like, and it can be written in a metadata graph,
 * a log line and a Python keyword list without quoting.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class Profiles {

    /** Equipment. */
    public static final String EQ = "EQ";
    /** Topology. */
    public static final String TP = "TP";
    /** State variables. */
    public static final String SV = "SV";
    /** Steady state hypothesis. */
    public static final String SSH = "SSH";
    /** Dynamics. */
    public static final String DY = "DY";
    /** Diagram layout. */
    public static final String DL = "DL";
    /** Geographical location. */
    public static final String GL = "GL";
    /** Equipment boundary. */
    public static final String EQ_BD = "EQ_BD";
    /** Topology boundary. */
    public static final String TP_BD = "TP_BD";

    /** The nine standard profiles, in the order of {@link CgmesSubset}. */
    public static final Set<String> STANDARD = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.stream(CgmesSubset.values())
                    .filter(subset -> subset != CgmesSubset.UNKNOWN)
                    .map(CgmesSubset::getIdentifier)
                    .toList()));

    /** The two profiles of the boundary a scenario shares. */
    private static final Set<String> BOUNDARY = Set.of(EQ_BD, TP_BD);

    /**
     * The order profiles are listed and written in: the standard ones in the order of {@link CgmesSubset}, then the
     * custom ones by name.
     */
    public static final Comparator<String> ORDER = Comparator
            .comparingInt((String profile) -> isStandard(profile) ? 0 : 1)
            .thenComparingInt(profile -> isStandard(profile) ? subset(profile).orElseThrow().ordinal() : 0)
            .thenComparing(Comparator.naturalOrder());

    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern FILE_TOKEN = Pattern.compile("[A-Z][A-Z0-9]*");
    private static final Pattern VERSION_TOKEN = Pattern.compile("V[0-9]+");

    private Profiles() {
    }

    /** @return an empty map listing its profiles in {@link #ORDER}, which is what an {@code EnumMap} used to do */
    static <V> SortedMap<String, V> map() {
        return new TreeMap<>(ORDER);
    }

    /** @return a copy of a map listing its profiles in {@link #ORDER} */
    static <V> SortedMap<String, V> map(Map<String, ? extends V> source) {
        SortedMap<String, V> copy = map();
        copy.putAll(source);
        return copy;
    }

    /** @return a copy of a set listing its profiles in {@link #ORDER} */
    static SortedSet<String> set(Collection<String> profiles) {
        SortedSet<String> copy = new TreeSet<>(ORDER);
        copy.addAll(profiles);
        return copy;
    }

    /**
     * @param profile a profile name
     * @return whether it is one of the two boundary profiles
     */
    public static boolean isBoundary(String profile) {
        return BOUNDARY.contains(profile);
    }

    /**
     * @param profile a profile name
     * @return whether it is one of the nine CGMES subsets
     */
    public static boolean isStandard(String profile) {
        return STANDARD.contains(profile);
    }

    /**
     * @param profile a profile name
     * @return the CGMES subset of a standard profile, empty for a custom one
     */
    public static Optional<CgmesSubset> subset(String profile) {
        if (!isStandard(profile)) {
            return Optional.empty();
        }
        return Arrays.stream(CgmesSubset.values()).filter(s -> s.getIdentifier().equals(profile)).findFirst();
    }

    /**
     * @param subset a CGMES subset
     * @return its profile name
     * @throws RdfDbException for {@link CgmesSubset#UNKNOWN}, which names no profile
     */
    public static String of(CgmesSubset subset) {
        if (subset == CgmesSubset.UNKNOWN) {
            throw new RdfDbException("the CGMES subset UNKNOWN names no profile");
        }
        return subset.getIdentifier();
    }

    /**
     * Refuse what is not a profile name.
     *
     * @param profile the name
     * @return the name
     * @throws RdfDbException if it is blank or not of the shape {@code [A-Z][A-Z0-9_]*}
     */
    public static String check(String profile) {
        if (!isName(profile)) {
            throw new RdfDbException("'" + profile + "' is not a profile name: a profile is named [A-Z][A-Z0-9_]*,"
                    + " like EQ, SSH or a custom OP");
        }
        return profile;
    }

    /** @return whether a text is of the shape of a profile name, {@code [A-Z][A-Z0-9_]*} */
    static boolean isName(String text) {
        return text != null && NAME.matcher(text).matches();
    }

    /**
     * The profile an instance file holds, read off its name.
     *
     * <p>The standard subset the CGMES conversion recognises in the name comes first ({@code …_EQ_…}, {@code …_SSH.},
     * the boundary by {@code _BD} or {@code BOUNDARY}); otherwise the last {@code _TOKEN} before the extension, when
     * it is {@code [A-Z][A-Z0-9]*} and not version-like ({@code V2}): {@code Grid_OP.xml} holds {@code OP}.</p>
     *
     * @param contextName the context name or the file name
     * @return the profile
     * @throws RdfDbException if the name says no profile
     */
    static String ofContextName(String contextName) {
        return find(contextName).orElseThrow(() -> new RdfDbException("cannot tell the profile of '" + contextName
                + "': name the file <base>_<PROFILE>.xml, the profile being one of " + STANDARD
                + " or a custom name [A-Z][A-Z0-9]* that is not a version (V2)"));
    }

    /**
     * {@link #ofContextName} without the refusal, for a listing that shows what it cannot classify.
     *
     * @param contextName the context name or the file name
     * @return the profile, empty when the name says none
     */
    static Optional<String> find(String contextName) {
        // The boundary subsets first: an EQ_BD file name also satisfies the plain EQ base name test
        Optional<CgmesSubset> standard = Arrays.stream(CgmesSubset.values())
                .filter(s -> s == CgmesSubset.EQUIPMENT_BOUNDARY || s == CgmesSubset.TOPOLOGY_BOUNDARY)
                .filter(s -> s.isValidName(contextName))
                .findFirst()
                .or(() -> Arrays.stream(CgmesSubset.values()).filter(s -> s.isValidName(contextName)).findFirst());
        if (standard.isPresent()) {
            return standard.map(CgmesSubset::getIdentifier);
        }
        String name = contextName.substring(contextName.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        int underscore = stem.lastIndexOf('_');
        if (underscore < 0) {
            return Optional.empty();
        }
        String token = stem.substring(underscore + 1);
        return FILE_TOKEN.matcher(token).matches() && !VERSION_TOKEN.matcher(token).matches()
                ? Optional.of(token) : Optional.empty();
    }
}
