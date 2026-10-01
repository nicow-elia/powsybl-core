/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.mapping.Block;
import com.powsybl.iidm.network.Area;

import java.util.List;

/**
 * The control areas in every export of the steady state hypothesis: the net interchange of an IIDM area of the
 * interchange type, which is its interchange target, and the tolerance its import kept as a property.
 *
 * <p>The block the CGMES update reads is declared here; the capabilities of the in-place import are derived from
 * it.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class ControlAreaFamily extends AbstractFamily {

    private static final String CONTROL_AREA_CLASS = "ControlArea";
    private static final String NET_INTERCHANGE = "ControlArea.netInterchange";
    private static final String P_TOLERANCE = "ControlArea.pTolerance";
    /** The IIDM property the import keeps the tolerance of a control area in. */
    private static final String P_TOLERANCE_PROPERTY = "pTolerance";

    public static final Block CONTROL_AREA = new Block("controlAreas", List.of(CONTROL_AREA_CLASS),
            List.of(NET_INTERCHANGE), List.of(P_TOLERANCE));

    ControlAreaFamily(CgmesExportContext context, IidmStateView state, Scope scope) {
        super(context, state, scope);
    }

    /** Describe the net interchange of a control area, and its tolerance when the import kept one. */
    void describeControlArea(Area area, CgmesPropertySink out) {
        out.startObject(CONTROL_AREA_CLASS, cgmesId(area.getId()))
                .value(NET_INTERCHANGE, area.getInterchangeTarget().orElse(Double.NaN));
        if (area.hasProperty(P_TOLERANCE_PROPERTY)) {
            out.value(P_TOLERANCE, Double.parseDouble(area.getProperty(P_TOLERANCE_PROPERTY)));
        }
        out.endObject();
    }
}
