/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.triplestore.api.TripleStoreOptions;
import com.powsybl.triplestore.impl.rdf4j.TripleStoreRDF4J;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.io.InputStream;

/**
 * A triple store on a repository that outlives it.
 *
 * <p>Every other triple store owns its backend and shuts it down when it is closed, and the CGMES conversion
 * relies on that: it closes the model, and with it the store, as soon as it is done. A store on a database must
 * not behave that way &mdash; the repository belongs to the {@link RdfDbConnection}, which may hand out several
 * stores and be asked for more later.</p>
 *
 * <p>It also has to <em>replace</em> a graph that is read into it a second time, which is what the Graph Store
 * Protocol {@code PUT} of the remote backend does and what {@link RdfDbConnection#loadCgmes} promises. The
 * inherited behaviour &mdash; appending to whatever the context already holds &mdash; would make a re-uploaded
 * instance file accumulate two versions of every changed value, and a memory store only hides that while the two
 * versions happen to be identical.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class SharedRepositoryTripleStore extends TripleStoreRDF4J {

    SharedRepositoryTripleStore(Repository repository, TripleStoreOptions options) {
        super(repository, options);
    }

    @Override
    public void read(InputStream is, String baseName, String contextName) {
        try (RepositoryConnection conn = getRepository().getConnection()) {
            conn.clear(context(conn, contextName));
        }
        super.read(is, baseName, contextName);
    }

    @Override
    public void close() {
        // The connection owns the repository
    }
}
