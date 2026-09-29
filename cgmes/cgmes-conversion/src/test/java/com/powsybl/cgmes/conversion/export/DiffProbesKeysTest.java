/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code DiffProbes} (package {@code diff}) re-declares six attribute keys of {@link CgmesChangeTranslator}, which is
 * package private in {@code export}; this pins that both spell them the same (review 21 finding n3).
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class DiffProbesKeysTest {

    @Test
    void diffProbesUseTheKeysOfTheTranslator() throws ReflectiveOperationException {
        Class<?> probes = Class.forName("com.powsybl.cgmes.conversion.diff.DiffProbes");
        Map<String, String> expected = Map.of(
                "LOCAL_TARGET_Q", CgmesChangeTranslator.LOCAL_TARGET_Q,
                "LOCAL_TARGET_V", CgmesChangeTranslator.LOCAL_TARGET_V,
                "VR_TARGET_VALUE", CgmesChangeTranslator.VR_TARGET_VALUE,
                "VR_REGULATING", CgmesChangeTranslator.VR_REGULATING,
                "VR_TARGET_DEADBAND", CgmesChangeTranslator.VR_TARGET_DEADBAND,
                "VR_MODE", CgmesChangeTranslator.VR_MODE);
        for (Map.Entry<String, String> key : expected.entrySet()) {
            Field field = probes.getDeclaredField(key.getKey());
            field.setAccessible(true);
            assertEquals(key.getValue(), field.get(null), key.getKey());
        }
    }
}
