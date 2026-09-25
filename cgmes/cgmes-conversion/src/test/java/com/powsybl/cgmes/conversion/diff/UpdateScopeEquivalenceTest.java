/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.diff;

import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.conversion.export.CgmesDiffExport;
import com.powsybl.cgmes.conversion.export.PartialSshExport;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios;
import com.powsybl.cgmes.conversion.test.RecordedChangeScenarios.Scenario;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.cgmes.model.diff.DifferenceModelWriter;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.serde.NetworkSerDe;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Restricting a difference model update to the equipment it touches must not change its result.
 *
 * <p>The scope is a pure performance measure: it exists so that a one switch change on a large network does not walk
 * every element of it. This test therefore applies every recorded scenario twice, once scoped and once not, and
 * compares the two networks as XIIDM &mdash; the finest grained comparison available.</p>
 *
 * <p>Both granularities are covered: {@code CHANGED_ONLY} additionally exercises the completion path, which runs
 * the export context and the probes of the receiving network.</p>
 *
 * <p>The fixtures carry no state variables, which matters: with solved values present the two runs differ on purpose,
 * because the unscoped update resets the state variables of every element it visits while the scoped one leaves the
 * elements it does not visit alone. That deliberate deviation is documented in the import documentation.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class UpdateScopeEquivalenceTest {

    static Stream<Arguments> scenarios() {
        List<Arguments> arguments = new ArrayList<>();
        for (Scenario scenario : RecordedChangeScenarios.all()) {
            for (CgmesDiffExport.DiffGranularity granularity : CgmesDiffExport.DiffGranularity.values()) {
                arguments.add(Arguments.of(scenario, granularity));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("scenarios")
    void scopedAndUnscopedUpdatesAgree(Scenario scenario, CgmesDiffExport.DiffGranularity granularity) {
        Network sender = scenario.load();
        List<NetworkEvent> events = RecordedChangeScenarios.record(sender, scenario.forwardChange());
        CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(sender, events,
                new CgmesDiffExport.ExportOptions()
                        .setUnsupportedChangeBehavior(PartialSshExport.UnsupportedChangeBehavior.FAIL)
                        .setGranularity(granularity));
        List<DifferenceModel> models = new ArrayList<>();
        result.differences().models().values()
                .forEach(model -> models.add(DifferenceModelParser.parse(DifferenceModelWriter.toString(model))));
        DifferenceModelSet set = new DifferenceModelSet(models);

        Properties parameters = new Properties();
        parameters.putAll(scenario.importParams());
        parameters.put(CgmesImport.USE_PREVIOUS_VALUES_DURING_UPDATE, "true");

        Network scoped = scenario.load();
        Network unscoped = scenario.load();
        CgmesDiffImport.apply(scoped, set, config(parameters), new CgmesDiffImport.Options().setScopedUpdate(true),
                ReportNode.NO_OP);
        CgmesDiffImport.apply(unscoped, set, config(parameters), new CgmesDiffImport.Options().setScopedUpdate(false),
                ReportNode.NO_OP);

        assertEquals(xiidm(unscoped), xiidm(scoped),
                () -> "the scoped update of " + scenario.name() + " (" + granularity
                        + ") gives another network than the full one");
    }

    private static com.powsybl.cgmes.conversion.Conversion.Config config(Properties parameters) {
        return new CgmesImport().config(parameters);
    }

    private static String xiidm(Network network) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NetworkSerDe.write(network, bytes);
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
