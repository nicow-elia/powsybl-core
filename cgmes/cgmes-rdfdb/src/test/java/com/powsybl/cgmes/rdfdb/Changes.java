/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkEventRecorder;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.events.NetworkEvent;

import java.util.List;
import java.util.function.Consumer;

/**
 * Recording changes on a network, and the handful of changes the tests of this module record.
 *
 * <p>The change <em>actions</em> are copied from the round trip tests of the difference exporter rather than the
 * class that holds them: that class lives in the test sources of {@code cgmes-conversion}, which are not on this
 * module's classpath, and the point here is not to re-characterise the translator but to have a change that
 * produces a difference of a known shape.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class Changes {

    /** A load of the MicroGrid BE fixture. */
    static final String LOAD_ID = "1c6beed6-1acf-42e7-ba55-0cc9f04bddd8";

    private Changes() {
    }

    /** Record what a change does to a network. */
    static List<NetworkEvent> record(Network network, Consumer<Network> change) {
        NetworkEventRecorder recorder = new NetworkEventRecorder();
        network.addListener(recorder);
        try {
            change.accept(network);
        } finally {
            network.removeListener(recorder);
        }
        return List.copyOf(recorder.getEvents());
    }

    /** Move the active power of a load by the given amount and answer the value it ends up at. */
    static double moveLoad(Network network, double delta) {
        Load load = network.getLoad(LOAD_ID);
        double p0 = load.getP0() + delta;
        load.setP0(p0);
        return p0;
    }

    /** Move the tap of the first ratio tap changer that has room, and answer the position it ends up at. */
    static int moveTap(Network network) {
        RatioTapChanger tapChanger = firstRatioTapChanger(network);
        int position = tapChanger.getTapPosition() < tapChanger.getHighTapPosition()
                ? tapChanger.getTapPosition() + 1 : tapChanger.getTapPosition() - 1;
        tapChanger.setTapPosition(position);
        return position;
    }

    /** The transformer whose tap {@link #moveTap} moves. */
    static String tapChangerOwner(Network network) {
        return network.getTwoWindingsTransformerStream()
                .filter(t -> t.getRatioTapChanger() != null)
                .findFirst().orElseThrow().getId();
    }

    private static RatioTapChanger firstRatioTapChanger(Network network) {
        TwoWindingsTransformer transformer = network.getTwoWindingsTransformerStream()
                .filter(t -> t.getRatioTapChanger() != null)
                .findFirst().orElseThrow(() -> new IllegalStateException("no ratio tap changer in the fixture"));
        return transformer.getRatioTapChanger();
    }

    /** Open or close the first retained switch, and answer what it ends up as. */
    static boolean toggleSwitch(Network network) {
        Switch sw = network.getSwitchStream().findFirst()
                .orElseThrow(() -> new IllegalStateException("no switch in the fixture"));
        boolean open = !sw.isOpen();
        sw.setOpen(open);
        return open;
    }

    /** The identifier of the switch {@link #toggleSwitch} toggles. */
    static String firstSwitch(Network network) {
        return network.getSwitchStream().findFirst().orElseThrow().getId();
    }

    /** Move the active power target of the first generator, and answer the value it ends up at. */
    static double moveGenerator(Network network) {
        Generator generator = network.getGeneratorStream().findFirst()
                .orElseThrow(() -> new IllegalStateException("no generator in the fixture"));
        double targetP = generator.getTargetP() + 7.0;
        generator.setTargetP(targetP);
        return targetP;
    }

    /** The identifier of the generator {@link #moveGenerator} moves. */
    static String firstGenerator(Network network) {
        return network.getGeneratorStream().findFirst().orElseThrow().getId();
    }
}
