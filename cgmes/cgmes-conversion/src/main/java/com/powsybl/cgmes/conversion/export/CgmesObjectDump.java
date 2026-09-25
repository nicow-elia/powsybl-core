/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.export.PartialSshExport.UnsupportedChangeBehavior;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.events.ExtensionUpdateNetworkEvent;
import com.powsybl.iidm.network.events.NetworkEvent;
import com.powsybl.iidm.network.events.UpdateNetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The CGMES statements the change export would write for one attribute of one network object, read from the network
 * as it currently stands.
 *
 * <p>This exists for the difference model <em>importer</em>, and it is deliberately built out of the exporter. A
 * CGMES update query reads several properties together &mdash; {@code EnergyConsumer.p} and
 * {@code EnergyConsumer.q}, the whole block of a tap changer, the four values of a regulating control &mdash; and a
 * difference model written with a minimal granularity, or by a third party, states only the ones that changed. The
 * others are, by definition, unchanged, so the value the receiving network holds <em>is</em> the intended value; the
 * importer only needs a way to spell it in CGMES.</p>
 *
 * <p>Deriving that spelling from the export mapping rather than writing a second one is the whole point: signs,
 * unit multipliers, operating modes, which class a subject has and which properties belong to one consistency group
 * are knowledge that already lives in {@link CgmesChangeTranslator}, and a second copy of it would drift. What this
 * class does is therefore to ask the translator a hypothetical question &mdash; "what would you write if this
 * attribute had changed?" &mdash; and keep the answer. That is legal because a mapping never reads the values of the
 * change it is given: it reads the network.</p>
 *
 * <p>The same mechanism answers the second question the importer has, namely what the network currently says about a
 * property, which is how the optional check of the reverse statements of a difference is evaluated.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class CgmesObjectDump {

    private static final Logger LOGGER = LoggerFactory.getLogger(CgmesObjectDump.class);

    /** Separates the extension name from the attribute name in the key of an extension attribute. */
    public static final String EXTENSION_SEPARATOR = "#";

    private final Network network;
    private final String variantId;

    private CgmesExportContext context;
    private CgmesChangeTranslator translator;

    /** What identifies one probe: the object and the attribute a change of it would be recorded under. */
    private record Probe(String identifiableId, String attributeKey) {
    }

    private final Map<Probe, Result<List<CgmesStatement>, String>> cache = new HashMap<>();

    /**
     * @param network the network the values are read from, which for an importer is the network being updated
     */
    public CgmesObjectDump(Network network) {
        this.network = Objects.requireNonNull(network);
        this.variantId = network.getVariantManager().getWorkingVariantId();
    }

    /**
     * The steady state hypothesis statements the change export would write if the given attribute of the given
     * object had just changed.
     *
     * @param identifiableId the IIDM identifier of the object, that is the one a change would be recorded on. For a
     *                       tap changer this is the transformer, for a converter station of a simple HVDC model it
     *                       may be the HVDC line
     * @param attributeKey   the attribute, or {@code extensionName + "#" + attribute} for an extension attribute
     * @return the statements, or an empty list when the mapping cannot express this attribute
     */
    public List<CgmesStatement> statementsFor(String identifiableId, String attributeKey) {
        return dump(identifiableId, attributeKey).fold(statements -> statements, reason -> List.of());
    }

    /**
     * The same as {@link #statementsFor}, keeping the reason when the mapping refuses, so that a caller can tell a
     * user <em>why</em> a property could not be completed.
     */
    public Result<List<CgmesStatement>, String> dump(String identifiableId, String attributeKey) {
        Objects.requireNonNull(identifiableId);
        Objects.requireNonNull(attributeKey);
        return cache.computeIfAbsent(new Probe(identifiableId, attributeKey),
            probe -> translate(probe.identifiableId(), probe.attributeKey()));
    }

    private Result<List<CgmesStatement>, String> translate(String identifiableId, String attributeKey) {
        int separator = attributeKey.indexOf(EXTENSION_SEPARATOR);
        // The values of the event are never read by a mapping, which reads the network; only its identifier and the
        // name of the attribute select what is written
        NetworkEvent event = separator < 0
                ? new UpdateNetworkEvent(identifiableId, attributeKey, variantId, null, null)
                : new ExtensionUpdateNetworkEvent(identifiableId, attributeKey.substring(0, separator),
                        attributeKey.substring(separator + 1), variantId, null, null);
        return switch (translator().translate(event)) {
            case Result.Success(CgmesPropertyBuffer buffer) -> {
                // Both profiles the change mappings write: operational limits are steady state data in CGMES 3 and
                // equipment data in CGMES 2.4.15, impedances and voltage level limits are always equipment data
                List<CgmesStatement> statements =
                        new ArrayList<>(buffer.statements(CgmesSubset.EQUIPMENT, context));
                statements.addAll(buffer.statements(CgmesSubset.STEADY_STATE_HYPOTHESIS, context));
                yield Result.success(List.copyOf(statements));
            }
            case Result.Failure(String reason) -> {
                LOGGER.debug("No CGMES mapping for {} of {}: {}", attributeKey, identifiableId, reason);
                yield Result.failure(reason);
            }
        };
    }

    /**
     * The translator, created on first use.
     *
     * <p>Building a {@link CgmesExportContext} walks the network once, so an importer that never needs to complete a
     * group never pays for it. It is built with {@link UnsupportedChangeBehavior#IGNORE}, because a probe that the
     * mapping refuses is an answer, not an error.</p>
     */
    private CgmesChangeTranslator translator() {
        if (translator == null) {
            context = new CgmesExportContext(network);
            translator = new CgmesChangeTranslator(network, context, UnsupportedChangeBehavior.IGNORE,
                    "a difference model applied in place", EnumSet.of(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS),
                    IidmStateView.LIVE, null);
        }
        return translator;
    }
}
