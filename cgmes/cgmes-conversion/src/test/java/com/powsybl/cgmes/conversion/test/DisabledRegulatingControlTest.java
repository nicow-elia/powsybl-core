/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.conformity.CgmesConformity1ModifiedCatalog;
import com.powsybl.cgmes.conformity.ReliCapGridCatalog;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.conversion.diff.CgmesDiffImport;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.DifferenceModelWriter;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.datasource.MemDataSource;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.regulation.VoltageRegulationHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real CGMES data: a RegulatingControl that is disabled in the steady state hypothesis gives its equipment a
 * VoltageRegulation that does not regulate, so switching it on is an ordinary, exportable change on all three routes
 * (coordinator question, round 3: the refusal "has no CGMES regulating control the import could use" is reachable only
 * for equipment without a RegulatingControl in its equipment model, e.g. an IIDM network exported by the full export,
 * issue R2-6 a).
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class DisabledRegulatingControlTest {

    static Stream<Arguments> cases() {
        Supplier<ReadOnlyDataSource> offSvc = () -> CgmesConformity1ModifiedCatalog.microT4BeBbOffSvc().dataSource();
        Supplier<ReadOnlyDataSource> nl = () -> CgmesConformity1ModifiedCatalog.microGridBaseCaseNLMultipleReferencePriorities().dataSource();
        return Stream.of(
                Arguments.of("static var compensator, MicroGrid T4 BE off SVC", offSvc, "STATIC_VAR_COMPENSATOR"),
                Arguments.of("shunt compensator, MicroGrid NL multiple generators", nl, "SHUNT_COMPENSATOR"));
    }

    private static Network read(Supplier<ReadOnlyDataSource> source) {
        return Network.read(source.get(), new Properties());
    }

    /** The equipment of the given type whose RegulatingControl the import turned into a regulation that does not regulate. */
    private static List<Identifiable<?>> disabled(Network network, String type) {
        List<Identifiable<?>> found = new ArrayList<>();
        network.getIdentifiables().forEach(identifiable -> {
            if (identifiable.getType().name().equals(type) && identifiable.hasProperty(Conversion.PROPERTY_REGULATING_CONTROL)
                    && identifiable instanceof VoltageRegulationHolder<?> holder && holder.getVoltageRegulation() != null
                    && !holder.getVoltageRegulation().isRegulating()) {
                found.add(identifiable);
            }
        });
        return found;
    }

    private static VoltageRegulationHolder<?> holder(Network network, String id) {
        return (VoltageRegulationHolder<?>) network.getIdentifiable(id);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void aDisabledControlGivesARegulationThatCanBeSwitchedOnThroughEveryRoute(String name,
                                                                            Supplier<ReadOnlyDataSource> source,
                                                                            String type) {
        Network sender = read(source);
        List<Identifiable<?>> candidates = disabled(sender, type);
        assertFalse(candidates.isEmpty(), () -> name + ": no " + type + " with a disabled RegulatingControl");
        String id = candidates.get(0).getId();
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender,
                n -> holder(n, id).getVoltageRegulation().setRegulating(true));
        assertEquals(1, events.size());

        Properties parameters = new Properties();
        parameters.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");

        // Partial SSH
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PartialSshExport.write(sender, events, bytes, new PartialSshExport.ExportOptions()
                .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL));
        Network sshReceiver = read(source);
        MemDataSource dataSource = new MemDataSource();
        dataSource.putData("partial_SSH.xml", bytes.toByteArray());
        sshReceiver.update(dataSource, parameters);
        assertTrue(holder(sshReceiver, id).isRegulating(), name + ": partial SSH");

        // Difference, both granularities, applied and reverted
        for (CgmesDiffExport.DiffGranularity granularity : CgmesDiffExport.DiffGranularity.values()) {
            CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(sender, events, new CgmesDiffExport.ExportOptions()
                    .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL).setGranularity(granularity));
            List<DifferenceModel> models = new ArrayList<>();
            result.differences().models().values()
                    .forEach(model -> models.add(DifferenceModelParser.parse(DifferenceModelWriter.toString(model))));
            DifferenceModelSet parsed = new DifferenceModelSet(models);
            Network receiver = read(source);
            assertNotNull(holder(receiver, id).getVoltageRegulation());
            CgmesDiffImport.apply(receiver, parsed, parameters, ReportNode.NO_OP);
            assertTrue(holder(receiver, id).isRegulating(), name + ": difference " + granularity);
            CgmesDiffImport.revert(receiver, parsed, parameters, ReportNode.NO_OP);
            assertFalse(holder(receiver, id).isRegulating(), name + ": difference " + granularity + " reverted");
        }
        System.out.println("DISABLED-RC " + name + ": " + candidates.size() + " with a disabled control, switched on "
                + id + " on all three routes");
    }

    /**
     * The generators of real data whose machine control is disabled have no RegulatingControl in their equipment model
     * (ReliCap Espheim: six machines with {@code controlEnabled=false}, none with a control). The import gives them no
     * VoltageRegulation; a regulation created on them is not reported (G1), and a change of their local voltage target
     * is refused because there is no control to carry it. That refusal is the one reachable with real data, besides
     * the IIDM networks exported by the full export (issue R2-6 a). No conformity fixture holds a generator with a
     * disabled RegulatingControl.
     */
    @Test
    void aMachineWithoutRegulatingControlHasNoRegulationAndItsVoltageTargetIsRefused() {
        Network sender = Network.read(ReliCapGridCatalog.espheim().dataSource(), new Properties());
        List<Generator> withoutControl = sender.getGeneratorStream()
                .filter(g -> !g.hasProperty(Conversion.PROPERTY_REGULATING_CONTROL)
                        && "SynchronousMachine".equals(g.getProperty(Conversion.PROPERTY_CGMES_ORIGINAL_CLASS, "SynchronousMachine")))
                .toList();
        assertFalse(withoutControl.isEmpty());
        assertTrue(withoutControl.stream().allMatch(g -> g.getVoltageRegulation() == null));
        String id = withoutControl.get(0).getId();
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender,
                n -> n.getGenerator(id).setLocalTargetV(Double.isNaN(n.getGenerator(id).getLocalTargetV())
                        ? 400.0 : n.getGenerator(id).getLocalTargetV() + 1.0));
        assertEquals(1, events.size());
        PowsyblException refusal = assertThrows(PowsyblException.class,
                () -> PartialSshExport.toString(sender, events, PartialSshExport.UnsupportedChangeBehavior.FAIL));
        assertTrue(refusal.getMessage().contains("has no CGMES regulating control the import could use")
                        && refusal.getMessage().contains("give it a VoltageRegulation (not regulating) first"),
                refusal.getMessage());
        System.out.println("DISABLED-RC generators of Espheim without RegulatingControl: " + withoutControl.size());
    }
}
