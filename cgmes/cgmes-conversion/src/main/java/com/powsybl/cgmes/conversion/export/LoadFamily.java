/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.mapping.LoadRows;
import com.powsybl.cgmes.conversion.mapping.PlainFamily;
import com.powsybl.cgmes.conversion.mapping.PlainRow;
import com.powsybl.cgmes.conversion.mapping.Quantity;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.Load;

import java.util.function.ToDoubleFunction;

import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_CGMES_ORIGINAL_CLASS;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.P0;
import static com.powsybl.commons.util.Result.failure;
import static com.powsybl.commons.util.Result.success;

/**
 * The loads in every export of the steady state hypothesis: an EnergyConsumer, an EnergySource or an
 * AsynchronousMachine, each a plain family of data rows ({@link LoadRows}), and the fictitious injection of a node or a
 * bus, written with the rows of the class it is exported as.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class LoadFamily extends AbstractFamily {

    LoadFamily(CgmesExportContext context, IidmStateView state, Scope scope) {
        super(context, state, scope);
    }

    // if EQ is not exported, the original class name is preserved
    // Package private so that the change export names a load as the full export does
    static String obtainLoadClassName(Load load, CgmesExportContext context) {
        String originalClassName = load.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS);
        return (originalClassName != null && !context.isExportEquipment()) ? originalClassName : CgmesExportUtil.loadClassName(load);
    }

    Result<CgmesPropertyBuffer, String> loadUpdates(Load load) {
        if (!context.isExportedEquipment(load)) {
            return failure("load " + load.getId() + " has no counterpart in the CGMES equipment model");
        }
        CgmesPropertyBuffer buffer = new CgmesPropertyBuffer();
        return describeLoad(load, buffer) ? success(buffer) : failure("load " + load.getId() + " is exported as a "
                + obtainLoadClassName(load, context) + ", which has no steady state setpoints");
    }

    /**
     * Describe the steady state hypothesis of a load from the rows of its family; false, and nothing described, when its
     * CGMES class has none. The CGMES import reads the rows of a family only together, so a change of either setpoint
     * describes all of them.
     */
    boolean describeLoad(Load load, CgmesPropertySink out) {
        String className = obtainLoadClassName(load, context);
        return LoadRows.ofClass(className).map(family -> {
            plainBlock(out, family, className, cgmesId(load), row -> row.key() == null ? row.getter().applyAsDouble(load)
                    : state.getDouble(load, row.key(), () -> row.getter().applyAsDouble(load)));
            return true;
        }).orElse(false);
    }

    /** Describe a fictitious injection of a node or a bus: an EnergySource when it produces, a NonConformLoad otherwise. */
    static void describeFictitiousInjection(String id, double p, double q, CgmesPropertySink out) {
        String className = p <= 0 ? CgmesNames.ENERGY_SOURCE : CgmesNames.NONCONFORM_LOAD;
        plainBlock(out, LoadRows.ofClass(className).orElseThrow(), className, id, row -> P0.equals(row.key()) ? p : q);
    }

    /** The rows of a plain family, in their order, each spelled as its quantity says. */
    private static <O> void plainBlock(CgmesPropertySink out, PlainFamily<O> family, String className, String id,
                                       ToDoubleFunction<PlainRow<O>> value) {
        out.startObject(className, id);
        for (PlainRow<O> row : family.rows()) {
            Quantity quantity = row.quantity();
            String lexical = quantity.lexical(quantity.encode(value.applyAsDouble(row), 1));
            if (quantity.enumeration() == null) {
                out.literal(row.property(), lexical);
            } else {
                out.enumValue(row.property(), quantity.enumeration(), lexical);
            }
        }
        out.endObject();
    }
}
