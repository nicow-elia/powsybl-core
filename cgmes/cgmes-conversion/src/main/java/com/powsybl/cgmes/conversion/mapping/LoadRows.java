/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.mapping;

import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.iidm.network.Load;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The three CGMES families an IIDM load is imported from, as data rows: {@code p0} and {@code q0} in the load
 * convention, plus the two values of an AsynchronousMachine the update query reads with them (the control flag, which
 * an IIDM load never has, and the machine kind, derived from the active power).
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class LoadRows {

    /** The attributes a change of a load is reported under. */
    private static final String P0 = "p0";
    private static final String Q0 = "q0";

    public static final PlainFamily<Load> ENERGY_CONSUMER = new PlainFamily<>("energyConsumers",
            List.of(CgmesNames.ENERGY_CONSUMER, CgmesNames.CONFORM_LOAD, CgmesNames.NONCONFORM_LOAD, CgmesNames.STATION_SUPPLY),
            List.of(p("EnergyConsumer.p"), q("EnergyConsumer.q")));

    public static final PlainFamily<Load> ENERGY_SOURCE = new PlainFamily<>("energySources",
            List.of(CgmesNames.ENERGY_SOURCE),
            List.of(p("EnergySource.activePower"), q("EnergySource.reactivePower")));

    public static final PlainFamily<Load> ASYNCHRONOUS_MACHINE = new PlainFamily<>("asynchronousMachines",
            List.of(CgmesNames.ASYNCHRONOUS_MACHINE),
            List.of(p("RotatingMachine.p"), q("RotatingMachine.q"),
                    constant("RegulatingCondEq.controlEnabled", "controlEnabled", Quantity.FLAG, load -> 0),
                    new PlainRow<>("AsynchronousMachine.asynchronousMachineType", "type", P0,
                            Quantity.ASYNCHRONOUS_MACHINE_KIND, Load::getP0, null)));

    private static final List<PlainFamily<Load>> ALL = List.of(ENERGY_CONSUMER, ENERGY_SOURCE, ASYNCHRONOUS_MACHINE);
    private static final Map<String, PlainFamily<Load>> BY_CLASS = ALL.stream()
            .flatMap(family -> family.cimClasses().stream().map(cimClass -> Map.entry(cimClass, family)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    private static final List<String> KEYS = ALL.stream().flatMap(family -> family.rows().stream())
            .map(PlainRow::key).flatMap(Stream::ofNullable).distinct().toList();

    private LoadRows() {
    }

    /** The family of the given CIM class, empty when no load family accepts it. */
    public static Optional<PlainFamily<Load>> ofClass(String cimClass) {
        return cimClass == null ? Optional.empty() : Optional.ofNullable(BY_CLASS.get(cimClass));
    }

    /** The attributes of a load a change of which one of its families describes. */
    public static List<String> keys() {
        return KEYS;
    }

    /** A row the import does not read and no change is reported under: written as the getter says. */
    private static PlainRow<Load> constant(String property, String variable, Quantity quantity, ToDoubleFunction<Load> getter) {
        return new PlainRow<>(property, variable, null, quantity, getter, null);
    }

    private static PlainRow<Load> p(String property) {
        return new PlainRow<>(property, "p", P0, Quantity.MW_LOAD, Load::getP0, Load::setP0);
    }

    private static PlainRow<Load> q(String property) {
        return new PlainRow<>(property, "q", Q0, Quantity.MVAR_LOAD, Load::getQ0, Load::setQ0);
    }
}
