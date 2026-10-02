/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.mapping.Block;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.DcSwitch;
import com.powsybl.iidm.network.Switch;
import com.powsybl.triplestore.api.PropertyBag;

import java.util.List;

import static com.powsybl.cgmes.conversion.Conversion.ALIAS_DC_TERMINAL1;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_DC_TERMINAL2;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_TERMINAL1;
import static com.powsybl.cgmes.conversion.Conversion.ALIAS_TERMINAL2;
import static com.powsybl.cgmes.conversion.Conversion.PROPERTY_CGMES_ORIGINAL_CLASS;
import static com.powsybl.cgmes.conversion.export.CgmesChangeTranslator.OPEN;
import static com.powsybl.commons.util.Result.failure;
import static com.powsybl.commons.util.Result.success;

/**
 * The switches and the terminals that carry the state of a switch in every export of the steady state hypothesis: the
 * open state of a CGMES switch, and the connection status of both terminals of an IIDM switch the import made from a
 * CGMES branch (ACLineSegment, EquivalentBranch, SeriesCompensator) and of a DC switch, which have no open state of
 * their own in CGMES. A change of such a switch describes every subject of its owner, both terminals.
 *
 * <p>The keys a change is reported under and the blocks the CGMES update reads are declared here; the dispatch of the
 * change export, the description and the capabilities of the in-place import are derived from them, and the
 * importer's update reads the open state of a switch through {@link #open}.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class SwitchAndTerminalFamily extends AbstractFamily {

    private static final String ACDC_TERMINAL_CONNECTED = "ACDCTerminal.connected";
    private static final String SWITCH_OPEN = "Switch.open";

    public static final Block SWITCH = new Block("switches", List.of("Switch", "Breaker", "Disconnector", "LoadBreakSwitch",
            "ProtectedSwitch", "GroundDisconnector", "Jumper"), SWITCH_OPEN);
    public static final Block TERMINAL = new Block("terminals", List.of(CgmesNames.TERMINAL), ACDC_TERMINAL_CONNECTED);
    public static final Block DC_TERMINAL = new Block("dcTerminals", List.of(CgmesNames.DC_TERMINAL, "ACDCConverterDCTerminal"),
            ACDC_TERMINAL_CONNECTED);

    SwitchAndTerminalFamily(CgmesExportContext context, IidmStateView state, Scope scope) {
        super(context, state, scope);
    }

    Result<CgmesPropertyBuffer, String> switchUpdates(Switch sw) {
        if (!context.isExportedEquipment(sw)) {
            return failure("switch " + sw.getId() + " has no counterpart in the CGMES equipment model"
                    + " (it was created by the import, for instance to represent a disconnected terminal),"
                    + " so its state cannot be referenced from a steady state hypothesis file");
        }
        String originalClass = sw.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS);
        if (isCgmesBranchClass(originalClass)) {
            // In CGMES this equipment is a branch and has no open state of its own:
            // the CGMES import derives the state of the IIDM switch from the connection status of its terminals.
            return success(switchTerminalUpdates(sw));
        }
        return success(collect(out -> describeSwitch(sw, out)));
    }

    /** Describe the open state of a switch that is not a CGMES branch, see {@link #switchUpdates}. */
    void describeSwitch(Switch sw, CgmesPropertySink out) {
        String originalClass = sw.getProperty(PROPERTY_CGMES_ORIGINAL_CLASS);
        out.startObject(originalClass != null ? originalClass : CgmesExportUtil.switchClassname(sw.getKind()), cgmesId(sw))
                .value(SWITCH_OPEN, state.getBoolean(sw, OPEN, sw::isOpen))
                .endObject();
    }

    /**
     * The open state the update query of a switch bound, the given one when it bound none. Whether a disconnected
     * terminal opens the switch as well is the importer's own rule.
     */
    public static boolean open(PropertyBag values, boolean otherwise) {
        return values.asBoolean(variable(SWITCH_OPEN)).orElse(otherwise);
    }

    /** The three CGMES classes an IIDM line or boundary line can have been imported from. */
    static boolean isCgmesBranchClass(String originalClass) {
        return CgmesNames.AC_LINE_SEGMENT.equals(originalClass)
                || CgmesNames.EQUIVALENT_BRANCH.equals(originalClass)
                || CgmesNames.SERIES_COMPENSATOR.equals(originalClass);
    }

    private CgmesPropertyBuffer switchTerminalUpdates(Switch sw) {
        boolean connected = !state.getBoolean(sw, OPEN, sw::isOpen);
        return collect(out -> {
            describeTerminal(cgmesIdFromAlias(sw, ALIAS_TERMINAL1), connected, out);
            describeTerminal(cgmesIdFromAlias(sw, ALIAS_TERMINAL2), connected, out);
        });
    }

    Result<CgmesPropertyBuffer, String> dcSwitchUpdates(DcSwitch dcSwitch) {
        // A DCSwitch has no open state in the SSH profile either, it is carried by its two DC terminals.
        boolean connected = !state.getBoolean(dcSwitch, OPEN, dcSwitch::isOpen);
        return success(collect(out -> {
            describeTerminal(CgmesNames.DC_TERMINAL, cgmesIdFromAlias(dcSwitch, ALIAS_DC_TERMINAL1), connected, out);
            describeTerminal(CgmesNames.DC_TERMINAL, cgmesIdFromAlias(dcSwitch, ALIAS_DC_TERMINAL2), connected, out);
        }));
    }

    /** Describe the connection status of a Terminal. */
    static void describeTerminal(String terminalId, boolean connected, CgmesPropertySink out) {
        describeTerminal(CgmesNames.TERMINAL, terminalId, connected, out);
    }

    /**
     * Describe the connection status of a terminal of the given class: a Terminal, a DCTerminal or the
     * ACDCConverterDCTerminal of a converter.
     */
    static void describeTerminal(String className, String terminalId, boolean connected, CgmesPropertySink out) {
        out.startObject(className, terminalId).literal(ACDC_TERMINAL_CONNECTED, Boolean.toString(connected)).endObject();
    }
}
