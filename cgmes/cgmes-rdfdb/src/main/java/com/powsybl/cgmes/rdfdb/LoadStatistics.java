/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import java.time.Duration;
import java.util.Map;

/**
 * Where the time of one database load went.
 *
 * <p>Not a nicety: the whole point of loading CGMES from a database is that it should not be slower than reading
 * the files, and "it is slower" is not actionable without knowing whether the time sits in the transfer, in the
 * N-Triples parsing, in filling the local store or in the CGMES conversion, which is the same work a file import
 * does. The benchmark asserts on these numbers and the documentation quotes them.</p>
 *
 * @param listGraphs how long it took to ask the database which graphs the scenario has
 * @param fetch      how long the graph transfers took, wall clock over the parallel fetch
 * @param parse      how long the N-Triples parsing took, summed over the fetch threads
 * @param store      how long filling the local in-memory store took
 * @param describe   how long working out the CIM namespace and base URI took
 * @param convert    how long the CGMES conversion to IIDM took
 * @param statements how many statements were loaded
 * @param graphs     how many graphs were loaded
 * @param cacheHits  how many graphs came from the cache instead of the database
 * @param perGraph   how long each graph took, fetch and parse together
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public record LoadStatistics(Duration listGraphs, Duration fetch, Duration parse, Duration store,
                             Duration describe, Duration convert, long statements, int graphs,
                             int cacheHits, Map<String, Duration> perGraph) {

    /**
     * @param listGraphs see {@link #listGraphs()}
     * @param fetch      see {@link #fetch()}
     * @param parse      see {@link #parse()}
     * @param store      see {@link #store()}
     * @param describe   see {@link #describe()}
     * @param convert    see {@link #convert()}
     * @param statements see {@link #statements()}
     * @param graphs     see {@link #graphs()}
     * @param cacheHits  see {@link #cacheHits()}
     * @param perGraph   see {@link #perGraph()}
     */
    public LoadStatistics {
        perGraph = Map.copyOf(perGraph);
    }

    /**
     * @return the total time of the load
     */
    public Duration total() {
        return listGraphs.plus(fetch).plus(store).plus(describe).plus(convert);
    }

    /**
     * @return a one-line summary in milliseconds, for a log or a benchmark table
     */
    public String summary() {
        return String.format("total=%d listGraphs=%d fetch=%d parse=%d store=%d describe=%d convert=%d"
                        + " statements=%d graphs=%d cacheHits=%d",
                total().toMillis(), listGraphs.toMillis(), fetch.toMillis(), parse.toMillis(), store.toMillis(),
                describe.toMillis(), convert.toMillis(), statements, graphs, cacheHits);
    }
}
