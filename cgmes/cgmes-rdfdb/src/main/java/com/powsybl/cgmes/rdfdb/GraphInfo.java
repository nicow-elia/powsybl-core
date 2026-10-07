/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import java.util.Objects;

/**
 * One named graph of one scenario, as the catalogue of a database describes it.
 *
 * @param scenario    the scenario the graph belongs to
 * @param contextName the powsybl context name, which is the name of the instance file the graph was loaded from
 * @param profile     the profile the file name says it holds ({@link Profiles#ofContextName}), {@code null} when it
 *                    says none
 * @param remoteGraph the graph IRI in the database
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record GraphInfo(String scenario, String contextName, String profile, String remoteGraph) {

    /**
     * @param scenario    see {@link #scenario()}
     * @param contextName see {@link #contextName()}
     * @param profile     see {@link #profile()}
     * @param remoteGraph see {@link #remoteGraph()}
     */
    public GraphInfo {
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(contextName);
    }
}
