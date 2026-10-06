/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.google.auto.service.AutoService;
import com.powsybl.commons.report.ReportResourceBundle;

/**
 * The message templates of the versioning layer ({@link RdfDbReports}), registered for every report tree built
 * from the bundles on the classpath.
 *
 * <p>The module ships its own bundle, as a module outside powsybl-core does, so that nothing in
 * {@code powsybl-commons} knows the module exists.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
@AutoService(ReportResourceBundle.class)
public final class RdfDbReportResourceBundle implements ReportResourceBundle {

    /** The base name of the bundle: {@code com/powsybl/cgmes/rdfdb/reports*.properties}. */
    public static final String BASE_NAME = "com.powsybl.cgmes.rdfdb.reports";

    @Override
    public String getBaseName() {
        return BASE_NAME;
    }
}
