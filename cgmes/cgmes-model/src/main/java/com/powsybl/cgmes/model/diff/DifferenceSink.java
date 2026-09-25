/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.model.diff;

/**
 * Where the difference models an export produced are handed over to.
 *
 * <p>This is the seam between generating differences and storing them: {@link DifferenceModelWriter} provides a sink
 * writing one document per profile, and a database layer provides one writing a named graph per model. An exporter
 * only ever calls a sink, so it needs to know nothing about the destination.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
@FunctionalInterface
public interface DifferenceSink {

    /**
     * Take one difference model, that is the changes of one profile.
     *
     * @param model the model to store
     */
    void accept(DifferenceModel model);

    /**
     * Take every model of one change set.
     *
     * <p>The default implementation passes them on one by one, in profile order. A sink that can store several
     * models as a unit, such as one writing to a transactional store, overrides this so that a change touching two
     * profiles never lands half way.</p>
     *
     * @param set the models to store
     */
    default void accept(DifferenceModelSet set) {
        set.models().values().forEach(this::accept);
    }
}
