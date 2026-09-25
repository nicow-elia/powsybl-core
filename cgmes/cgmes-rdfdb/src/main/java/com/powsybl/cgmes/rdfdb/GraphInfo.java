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
import java.util.Objects;

/**
 * One named graph of one scenario, as the catalogue of a database describes it.
 *
 * @param scenario    the scenario the graph belongs to
 * @param contextName the powsybl context name, which is the name of the instance file the graph was loaded from
 * @param subset      the CGMES subset the file name says it holds, {@link CgmesSubset#UNKNOWN} when it says nothing
 * @param remoteGraph the graph IRI in the database
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record GraphInfo(String scenario, String contextName, CgmesSubset subset, String remoteGraph) {

    /**
     * @param scenario    see {@link #scenario()}
     * @param contextName see {@link #contextName()}
     * @param subset      see {@link #subset()}
     * @param remoteGraph see {@link #remoteGraph()}
     */
    public GraphInfo {
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(contextName);
        Objects.requireNonNull(subset);
    }

    /**
     * The CGMES subset a context name belongs to, read off the file name the way the conversion reads it.
     *
     * @param contextName the context name
     * @return the subset, or {@link CgmesSubset#UNKNOWN}
     */
    public static CgmesSubset subsetOf(String contextName) {
        // The boundary subsets first: an EQ_BD file name also satisfies the plain EQ base name test
        return Arrays.stream(CgmesSubset.values())
                .filter(s -> s == CgmesSubset.EQUIPMENT_BOUNDARY || s == CgmesSubset.TOPOLOGY_BOUNDARY)
                .filter(s -> s.isValidName(contextName))
                .findFirst()
                .orElseGet(() -> Arrays.stream(CgmesSubset.values())
                        .filter(s -> s.isValidName(contextName))
                        .findFirst()
                        .orElse(CgmesSubset.UNKNOWN));
    }
}
