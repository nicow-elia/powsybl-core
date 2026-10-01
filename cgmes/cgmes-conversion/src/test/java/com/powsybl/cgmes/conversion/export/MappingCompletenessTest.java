/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.mapping.LoadRows;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.ThreeWindingsTransformerNetworkFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every attribute a setter of {@code iidm-impl} notifies is either read by a family of the mapping (a row, a key or a
 * probe it declares), refused by name with a reason, an echo of a deprecated setter, or in {@link #NOT_CGMES} with the
 * reason it has no CGMES property a change export writes. Silence fails: a new notified attribute, or a call whose
 * attribute name is built in a way this test does not know ({@link #BUILT_AT_RUN_TIME}), makes the test fail until it
 * is mapped or listed.
 *
 * <p>The attributes are read from the sources of {@code iidm-impl} (the string literals and string constants a
 * {@code notifyUpdate} call names), so the check runs in the source tree of powsybl-core; a layout in which they are
 * not where the build says fails with the path it looked at. The lasting answer would be a list of the notified
 * attributes published by IIDM itself, an API change outside this module.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class MappingCompletenessTest {

    /**
     * The sources of {@code iidm-impl}: the path the build of this module gives (system property
     * {@code iidm.impl.sources}, set in its pom), else the place they have in the source tree of powsybl-core.
     */
    private static final Path IIDM_IMPL = Path.of(System.getProperty("iidm.impl.sources",
            Path.of("..", "..", "iidm", "iidm-impl", "src", "main", "java").toString()));

    private static final String STATE_VARIABLE = "a state variable (SV profile) or a solved value, not a hypothesis";
    private static final String TOPOLOGY = "topology computed by IIDM or the connection of a terminal; terminal"
            + " connection changes are not a supported change (plan 20c, what the rework does not solve 8)";
    private static final String EQUIPMENT = "equipment data the change export does not write (no SSH property, and not"
            + " one of the equipment values a change carries: limits, voltage limits, impedances)";
    private static final String STRUCTURE = "structure: creates, removes or replaces an object, which a change export"
            + " cannot describe (it names existing CGMES objects only)";
    private static final String IIDM_ONLY = "IIDM bookkeeping without a CGMES counterpart";

    /** Notified attributes without a CGMES property a change export writes, and why. */
    static final Map<String, String> NOT_CGMES = new TreeMap<>(Map.ofEntries(
            entry("v", STATE_VARIABLE), entry("angle", STATE_VARIABLE), entry("p", STATE_VARIABLE),
            entry("q", STATE_VARIABLE), entry("p_dc", STATE_VARIABLE), entry("i_dc", STATE_VARIABLE),
            entry("solvedSectionCount", STATE_VARIABLE), entry(".solvedTapPosition", STATE_VARIABLE),
            entry("connected", TOPOLOGY), entry("connected_dc", TOPOLOGY), entry("connectableBusId", TOPOLOGY),
            entry("connectedComponentNumber", TOPOLOGY), entry("synchronousComponentNumber", TOPOLOGY),
            entry("dcComponentNumber", TOPOLOGY), entry("beginConnect", TOPOLOGY), entry("endConnect", TOPOLOGY),
            entry("beginDisconnect", TOPOLOGY), entry("endDisconnect", TOPOLOGY), entry("terminal", TOPOLOGY),
            entry("topologyKind", TOPOLOGY), entry("retained", TOPOLOGY),
            entry("minP", EQUIPMENT), entry("maxP", EQUIPMENT), entry("ratedU0", EQUIPMENT), entry("nominalV", EQUIPMENT),
            entry("energySource", EQUIPMENT), entry("loadType", EQUIPMENT), entry("lossFactor", EQUIPMENT),
            entry("idleLoss", EQUIPMENT), entry("switchingLoss", EQUIPMENT), entry("resistiveLoss", EQUIPMENT),
            entry("reactiveModel", EQUIPMENT), entry("reactiveLimits", EQUIPMENT), entry("bMin", EQUIPMENT),
            entry("bMax", EQUIPMENT), entry("bPerSection", EQUIPMENT), entry("gPerSection", EQUIPMENT),
            entry("maximumSectionCount", EQUIPMENT), entry(".loadTapChangingCapabilities", EQUIPMENT),
            entry(".lowTapPosition", EQUIPMENT), entry(".step[", EQUIPMENT), entry("rho", EQUIPMENT),
            entry("alpha", EQUIPMENT), entry("country", EQUIPMENT), entry("tso", EQUIPMENT),
            entry("geographicalTags", EQUIPMENT), entry("pairing_key", EQUIPMENT),
            entry("ratioTapChanger", STRUCTURE), entry("phaseTapChanger", STRUCTURE),
            entry("id", IIDM_ONLY), entry("name", IIDM_ONLY), entry("fictitious", IIDM_ONLY), entry("equivalent", IIDM_ONLY),
            entry("participate", "an ActivePowerControl attribute of IIDM; CGMES carries the participation factor only"),
            entry("droop", "an ActivePowerControl attribute of IIDM; CGMES carries the participation factor only"),
            entry("fictitiousP0", "written by the full export as a fictitious NonConformLoad or EnergySource under a"
                    + " generated identifier, which a change cannot name"),
            entry("fictitiousQ0", "written by the full export as a fictitious NonConformLoad or EnergySource under a"
                    + " generated identifier, which a change cannot name"),
            entry("interchangeTarget", "ControlArea.netInterchange: not part of the change export yet (P1 lists it as"
                    + " a behaviour change of its own)")));

    /**
     * Calls whose attribute name is built at run time, by the code they appear in, and which mapping covers them.
     * The key is a fragment of the call.
     */
    static final Map<String, String> BUILT_AT_RUN_TIME = Map.ofEntries(
            entry("getLegAttribute() + \".\" + getTapChangerAttribute()", "creation of a tap changer of a leg: "
                    + "structure (NOT_CGMES)"),
            entry("parent.getTransformer(), attribute,", "the tap changer attributes, named by a prefix and a suffix:"
                    + " TapChangerAndShuntFamily"),
            entry("voltageLevel, modifiedVariable,", "fictitious injections of a node: NOT_CGMES fictitiousP0/Q0"),
            entry("LimitType.", "operational limits: LimitFamily (limits prefix)"),
            entry("attributeName + \"_\" + LimitType.", "operational limits: LimitFamily (limits prefix)"),
            entry("oldLimits, newLimits", "operational limits: LimitFamily (limits prefix)"),
            entry("null, newSelected", "selection of a limits group: refused by LimitFamily"),
            entry("operationalLimitsGroupById.get(id), null", "removal of a limits group: refused by LimitFamily"),
            entry("getNotification(attribute)", "the VoltageRegulation keys (NotifyUpdateKey): RegulatingControlFamily"),
            entry("Priority", "the reference priority extension: MachineFamily"),
            entry("attribute, variantId, oldValue, newValue", "a forwarding helper of an attribute named by its caller"),
            entry("attribute, oldValue, newValue", "a forwarding helper of an attribute named by its caller"));

    private static final Network NETWORK = EurostagTutorialExample1Factory.create();
    private static final Identifiable<?> GENERATOR = NETWORK.getGenerator("GEN");
    private static final Identifiable<?> TRANSFORMER = NETWORK.getTwoWindingsTransformer("NGEN_NHV1");
    private static final Identifiable<?> THREE_WINDINGS = ThreeWindingsTransformerNetworkFactory.create().getThreeWindingsTransformer("3WT");

    private static final Pattern CONSTANT = Pattern.compile("\\bString\\s+([A-Z_][A-Z0-9_]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern KEY = Pattern.compile("\\b([A-Z_][A-Z0-9_]*)\\(VOLTAGE_REGULATION_PREFIX \\+ \"([^\"]*)\"\\)");
    private static final Pattern TOKEN = Pattern.compile("\"([^\"]*)\"|\\b(NotifyUpdateKey\\.[A-Z_]+|[A-Z][A-Z0-9_]{2,})\\b");

    @Test
    void everyNotifiedAttributeIsMappedRefusedAnEchoOrNotCgmes() throws IOException {
        assertTrue(Files.isDirectory(IIDM_IMPL), () -> "the sources of iidm-impl are not at " + IIDM_IMPL.toAbsolutePath());
        List<String> sources = sources();
        Map<String, String> constants = new HashMap<>();
        for (String source : sources) {
            collect(CONSTANT, source, constants, "");
            collect(KEY, source, constants, "NotifyUpdateKey.");
        }
        Set<String> notified = new TreeSet<>();
        Set<String> unknownCalls = new TreeSet<>();
        for (String source : sources) {
            int from = 0;
            for (int call = source.indexOf("notifyUpdate(", from); call >= 0; call = source.indexOf("notifyUpdate(", from)) {
                from = call + "notifyUpdate(".length();
                String text = arguments(source, from);
                if (text == null) {
                    continue; // a declaration
                }
                String attribute = attribute(text, constants);
                if (attribute != null) {
                    notified.add(attribute);
                } else if (BUILT_AT_RUN_TIME.keySet().stream().noneMatch(text::contains)) {
                    unknownCalls.add(text.replaceAll("\\s+", " "));
                }
            }
        }
        Map<String, String> silent = new TreeMap<>();
        notified.stream().filter(attribute -> !NOT_CGMES.containsKey(attribute) && category(attribute) == null)
                .forEach(attribute -> silent.put(attribute, "neither read, refused, an echo nor NOT_CGMES"));
        NOT_CGMES.keySet().stream().filter(attribute -> category(attribute) != null)
                .forEach(attribute -> silent.put(attribute, "in NOT_CGMES but " + category(attribute)));
        NOT_CGMES.keySet().stream().filter(attribute -> !notified.contains(attribute))
                .forEach(attribute -> silent.put(attribute, "in NOT_CGMES but no longer notified"));
        unknownCalls.forEach(call -> silent.put(call, "a call whose attribute this test cannot read"));
        assertTrue(silent.isEmpty(), silent::toString);
        assertTrue(notified.size() > 100, () -> "only " + notified.size() + " notified attributes found");
    }

    /** How the mapping treats a notified attribute, {@code null} when it does not know it. */
    private static String category(String attribute) {
        if (read().contains(attribute)) {
            return "read by a family";
        }
        if (attribute.startsWith(".")) {
            // A suffix below the name of a tap changer, or an attribute of a leg of a three windings transformer
            if (TapChangerAndShuntFamily.tapChangerAttribute(CgmesChangeTranslator.PHASE_TAP_CHANGER_PREFIX + attribute) != null
                    || TapChangerAndShuntFamily.tapChangerAttribute(CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX + attribute) != null) {
                return "claimed by the tap changer family";
            }
            if (RegulatingControlFamily.isEcho(TRANSFORMER, CgmesChangeTranslator.RATIO_TAP_CHANGER_PREFIX + attribute)) {
                return "an echo of a ratio tap changer";
            }
            return LimitFamily.transformerImpedanceRefusal(THREE_WINDINGS, "leg1" + attribute).isPresent()
                    ? "refused by name (transformer impedance)" : null;
        }
        if (RegulatingControlFamily.isEcho(GENERATOR, attribute)) {
            return "an echo of a deprecated setter";
        }
        if (RegulationKeyRefusals.EQUIPMENT_KEYS.contains(attribute) || RegulationKeyRefusals.unread(GENERATOR, attribute).isPresent()
                || LimitFamily.transformerImpedanceRefusal(TRANSFORMER, attribute).isPresent()) {
            return "refused by name";
        }
        return null;
    }

    /** The keys the families declare: the dispatch of the change export and the probes of the in-place import. */
    private static Set<String> read() {
        Set<String> read = new TreeSet<>(LoadRows.keys());
        Stream.of(MachineFamily.GENERATOR_PROBES, MachineFamily.BOUNDARY_LINE_PROBES, TapChangerAndShuntFamily.SHUNT_PROBES,
                        TapChangerAndShuntFamily.STATIC_VAR_COMPENSATOR_PROBES, SwitchAndTerminalFamily.PROBES,
                        HvdcFamily.LINE_PROBES, HvdcFamily.VSC_STATION_PROBES, HvdcFamily.LCC_STATION_PROBES,
                        HvdcFamily.CONVERTER_PROBES, List.copyOf(LimitFamily.LINE_KEYS), List.copyOf(LimitFamily.BOUNDARY_LINE_KEYS),
                        LimitFamily.VOLTAGE_LEVEL_PROBES)
                .flatMap(List::stream)
                .map(key -> key.substring(key.indexOf(CgmesObjectDump.EXTENSION_SEPARATOR) + 1))
                .forEach(read::add);
        RegulatingControlFamily.HOLDER_KEYS.forEach(key -> read.add(key.canonical()));
        return read;
    }

    private static List<String> sources() throws IOException {
        try (Stream<Path> files = Files.walk(IIDM_IMPL)) {
            return files.filter(file -> file.toString().endsWith(".java")).map(file -> {
                try {
                    return Files.readString(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).toList();
        }
    }

    private static void collect(Pattern pattern, String source, Map<String, String> constants, String prefix) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            constants.put(prefix + matcher.group(1),
                    (prefix.isEmpty() ? "" : CgmesChangeTranslator.VR_PREFIX) + matcher.group(2));
        }
    }

    /** The arguments of a call up to its end, {@code null} for the declaration of a method of that name. */
    private static String arguments(String source, int from) {
        int semicolon = source.indexOf(';', from);
        int brace = source.indexOf('{', from);
        return brace >= 0 && brace < semicolon ? null : source.substring(from, semicolon);
    }

    /** The first string literal or string constant of a call, the attribute it notifies; {@code null} when none. */
    private static String attribute(String arguments, Map<String, String> constants) {
        Matcher matcher = TOKEN.matcher(arguments);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                if (!Set.of(".", "_", "").contains(matcher.group(1))) {
                    return matcher.group(1);
                }
            } else if (constants.containsKey(matcher.group(2))) {
                return constants.get(matcher.group(2));
            }
        }
        return null;
    }
}
