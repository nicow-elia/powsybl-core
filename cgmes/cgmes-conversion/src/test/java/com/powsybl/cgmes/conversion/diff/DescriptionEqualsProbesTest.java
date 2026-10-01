/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.diff.DiffSubjectResolver.ResolvedSubject;
import com.powsybl.cgmes.conversion.export.CgmesObjectDump;
import com.powsybl.cgmes.conversion.export.Families;
import com.powsybl.cgmes.extensions.CgmesTapChanger;
import com.powsybl.cgmes.extensions.CgmesTapChangers;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration oracle of the commit that replaces the probes of the in-place import by the description of a subject:
 * for every subject of every fixture of the equivalence guard, the description ({@link Families#describe}) states
 * the same properties with the same values as the probes did ({@code DiffProbes} through {@link CgmesObjectDump}),
 * and refuses only where a probe was refused. Deleted with the probes in the next commit.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class DescriptionEqualsProbesTest {

    static List<Object[]> fixtures() throws ReflectiveOperationException {
        Class<?> guard = Class.forName("com.powsybl.cgmes.conversion.export.ExportMappingEquivalenceTest");
        Method fixtures = guard.getDeclaredMethod("fixtures");
        fixtures.setAccessible(true);
        List<Object[]> arguments = new ArrayList<>();
        for (Object fixture : (List<?>) fixtures.invoke(null)) {
            Method name = fixture.getClass().getDeclaredMethod("name");
            Method loader = fixture.getClass().getDeclaredMethod("loader");
            name.setAccessible(true);
            loader.setAccessible(true);
            arguments.add(new Object[] {name.invoke(fixture), loader.invoke(fixture)});
        }
        return arguments;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @SuppressWarnings("unchecked")
    void theDescriptionIsWhatTheProbesSaid(String name, Supplier<Network> loader) {
        Network network = loader.get();
        Families families = new Families(network);
        DiffSubjectResolver resolver = new DiffSubjectResolver(families);
        CgmesObjectDump dump = new CgmesObjectDump(network);
        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (String id : candidates(network, families)) {
            Optional<Families.Subject> found = families.resolve(id, null);
            if (found.isEmpty()) {
                continue;
            }
            ResolvedSubject subject = new ResolvedSubject(FastRouteCapabilities.familyOfClass(found.get().cimClass()), found.get());
            Map<String, String> probed = new TreeMap<>();
            boolean probeRefused = false;
            for (Identifiable<?> object : families.objectsOf(found.get())) {
                for (String probe : DiffProbes.probesFor(subject, object)) {
                    var result = dump.dump(object.getId(), probe);
                    probeRefused |= result instanceof com.powsybl.commons.util.Result.Failure;
                    keep(dump.statementsFor(object.getId(), probe), id, probed);
                }
            }
            Families.Description description = families.describe(found.get());
            Map<String, String> described = new TreeMap<>();
            keep(description.statements(), id, described);
            compared++;
            if (!probed.equals(described)) {
                problems.add(id + ": probes " + probed + " description " + described);
            }
            // A probe of a key may be refused for the key alone (an echo, a local target, a mode without operating
            // mode) while another key describes the block; the description never refuses what every probe described
            if (description.refusal().isPresent() && !probeRefused) {
                problems.add(id + ": probes refused " + probeRefused + ", description refusal " + description.refusal());
            }
        }
        int count = compared;
        assertTrue(problems.isEmpty(), () -> name + " (" + count + " subjects):\n" + String.join("\n", problems));
        assertEquals(true, compared > 0 || network.getIdentifiables().isEmpty(), name);
    }

    private static void keep(List<CgmesStatement> statements, String subjectId, Map<String, String> byProperty) {
        for (CgmesStatement statement : statements) {
            if (statement.subjectId().equals(subjectId) && !statement.isType()) {
                byProperty.putIfAbsent(statement.property(), statement.className() + " " + statement.value());
            }
        }
    }

    /** Every identifier a difference may name a subject of this network by. */
    private static Set<String> candidates(Network network, Families families) {
        Set<String> ids = new LinkedHashSet<>();
        for (Identifiable<?> identifiable : network.getIdentifiables()) {
            ids.add(identifiable.getId());
            identifiable.getAliases().forEach(ids::add);
            for (String property : List.of(Conversion.PROPERTY_REGULATING_CONTROL, Conversion.PROPERTY_GENERATING_UNIT,
                    Conversion.PROPERTY_EQUIVALENT_INJECTION)) {
                Optional.ofNullable(identifiable.getProperty(property)).ifPresent(ids::add);
            }
            for (String property : List.of(Conversion.PROPERTY_OPERATIONAL_LIMIT_HIGH_VOLTAGE_LIMIT,
                    Conversion.PROPERTY_OPERATIONAL_LIMIT_LOW_VOLTAGE_LIMIT)) {
                Optional.ofNullable(identifiable.getProperty(property)).ifPresent(value -> ids.addAll(Arrays.asList(value.split(";"))));
            }
            if (identifiable instanceof Connectable<?> connectable && connectable.getExtension(CgmesTapChangers.class) != null) {
                for (Object tapChanger : ((CgmesTapChangers<?>) connectable.getExtension(CgmesTapChangers.class)).getTapChangers()) {
                    Optional.ofNullable(((CgmesTapChanger) tapChanger).getControlId()).ifPresent(ids::add);
                }
            }
        }
        ids.addAll(families.limitSlots().keySet());
        Set<String> normalized = new LinkedHashSet<>();
        ids.stream().filter(id -> !id.isEmpty()).forEach(id -> normalized.add(DifferenceModelParser.normalizeId(id)));
        return normalized;
    }
}
