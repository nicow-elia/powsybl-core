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
 * <p>Public API: used by the RDF database layer ({@code powsybl-cgmes-rdfdb}); changing a public signature of this
 * package is a breaking change for it. The module has no other consumer inside powsybl-core and moves out together
 * with that layer.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
package com.powsybl.triplestore.impl.rdf4j.sparql;
