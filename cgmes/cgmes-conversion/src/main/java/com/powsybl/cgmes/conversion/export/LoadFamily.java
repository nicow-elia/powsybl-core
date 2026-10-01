/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.iidm.network.Load;

import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_CGMES_ORIGINAL_CLASS;

/**
 * The CGMES class of a load in every export of the steady state hypothesis.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class LoadFamily {

    // if EQ is not exported, the original class name is preserved
    // Package private so that the change export names a load as the full export does
    static String obtainLoadClassName(Load load, CgmesExportContext context) {
        String originalClassName = load.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS);
        return (originalClassName != null && !context.isExportEquipment()) ? originalClassName : CgmesExportUtil.loadClassName(load);
    }

    private LoadFamily() {
    }
}
