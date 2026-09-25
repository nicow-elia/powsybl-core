/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.FamilySpec;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.PropertyGroup;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static com.powsybl.cgmes.conversion.test.ConversionUtil.readCgmesResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The active and the reactive power of a synchronous machine have to travel together.
 *
 * <p>The update query reads {@code cim:RotatingMachine.p} and {@code cim:RotatingMachine.q} in two optional blocks,
 * so a document stating only the active power is read without complaint. The conversion then throws it away:
 * {@code SynchronousMachineConversion} takes the updated power flow only when it is <em>defined</em>, which means
 * both values are bound, and otherwise keeps the target the network already has. A difference stating the active
 * power alone was therefore accepted, applied and silently without effect.</p>
 *
 * <p>A difference the change exporter writes always carries the whole group, which is why nothing noticed for a
 * long time. A <em>composed</em> difference does not: folding a chain drops every property that ends where it
 * started, so a chain that moved the active power and put the reactive power back produces exactly the statement
 * that used to be lost. The capability table now requires the pair, and the receiver completes the missing half
 * from its own state.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class SynchronousMachinePowerGroupTest {

    private static final String DIR = "/update/generator/";
    private static final String[] FILES = {"generator_EQ.xml", "generator_SSH.xml"};
    private static final String MACHINE = "SynchronousMachine";

    private static Properties previousValues() {
        Properties parameters = new Properties();
        parameters.put(com.powsybl.cgmes.conversion.CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");
        return parameters;
    }

    private static DifferenceModelSet activePowerOnly(double p) {
        DifferenceModelHeader header = DifferenceModelHeader
                .builder("urn:uuid:p-only", CgmesSubset.STEADY_STATE_HYPOTHESIS, CgmesNamespace.CIM_100_NAMESPACE)
                .build();
        return new DifferenceModelSet(List.of(new DifferenceModel(header,
                List.of(CgmesStatement.literal(MACHINE, null, "RotatingMachine.p", String.valueOf(p))),
                List.of(), List.of())));
    }

    @Test
    void aDifferenceStatingOnlyTheActivePowerIsApplied() {
        Network network = readCgmesResources(DIR, FILES);
        double before = network.getGenerator(MACHINE).getTargetP();
        double q = network.getGenerator(MACHINE).getTargetQ();
        assertEquals(160.0, before, 1e-9, "the fixture is expected to start at the value of its SSH file");

        CgmesDiffImport.apply(network, activePowerOnly(-99.0), previousValues(), ReportNode.NO_OP);

        assertEquals(99.0, network.getGenerator(MACHINE).getTargetP(), 1e-9,
                "a difference stating cim:RotatingMachine.p alone has to reach the generator");
        assertEquals(q, network.getGenerator(MACHINE).getTargetQ(), 1e-9,
                "the reactive power the difference does not state has to survive the update");
    }

    @Test
    void theInPlaceRouteIsStillTakenForSuchADifference() {
        Network network = readCgmesResources(DIR, FILES);
        CgmesDiffImport.Decision decision = CgmesDiffImport.canApplyInPlace(network, activePowerOnly(-99.0),
                new CgmesDiffImport.Options());
        assertEquals(CgmesDiffImport.Route.FAST, decision.route(), decision.reasons().toString());
    }

    @Test
    void theTableRequiresTheTwoPowersTogether() {
        FamilySpec spec = FastRouteCapabilities.spec(FastRouteCapabilities.Family.SYNCHRONOUS_MACHINE);
        List<PropertyGroup> withActivePower = spec.groups().stream()
                .filter(group -> group.properties().contains("RotatingMachine.p"))
                .toList();
        assertEquals(1, withActivePower.size(),
                "the active power belongs to exactly one group of the synchronous machine");
        PropertyGroup group = withActivePower.get(0);
        assertTrue(group.required().contains("RotatingMachine.p"), group.toString());
        assertTrue(group.required().contains("RotatingMachine.q"),
                "the conversion only takes the power flow when both values are bound, so the receiver has to"
                        + " complete the reactive power: " + group);
    }
}
