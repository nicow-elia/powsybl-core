/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

/**
 * A triple store on a remote SPARQL 1.1 endpoint: the store itself, its endpoint, the graph store client that moves
 * whole graphs, and the scenario graph naming.
 *
 * <p>Public API: clients outside this module build on the public signatures of this package, which has no consumer
 * inside powsybl-core.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
package com.powsybl.triplestore.impl.rdf4j.sparql;
