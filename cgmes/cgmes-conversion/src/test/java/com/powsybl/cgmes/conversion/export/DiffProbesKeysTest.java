/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.test.ConversionUtil;
import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The probes of the in-place import come from the families of the mapping ({@code DiffProbes} no longer re-declares the
 * keys of {@link CgmesChangeTranslator}, review 21 finding n3, so their spelling cannot drift); the extension probes of a
 * generator are each mapped.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class DiffProbesKeysTest {

    /**
     * Every extension probe of a generator names an extension the translator maps: a probe under another name is
     * refused whatever the state, so the group it should complete would silently lose it.
     */
    @Test
    void everyExtensionProbeOfAGeneratorIsMapped() {
        List<String> extensionProbes = MachineFamily.GENERATOR_PROBES.stream().filter(p -> p.contains("#")).toList();
        assertEquals(2, extensionProbes.size(), extensionProbes::toString);
        Network network = ConversionUtil.readCgmesResources("/update/generator/", "generator_EQ.xml", "generator_SSH.xml");
        CgmesObjectDump dump = new CgmesObjectDump(network);
        for (String probe : extensionProbes) {
            Result<?, String> result = dump.dump("SynchronousMachine", probe);
            assertInstanceOf(Result.Success.class, result, () -> probe + ": " + result);
            assertTrue(!dump.statementsFor("SynchronousMachine", probe).isEmpty(), probe);
        }
    }
}
