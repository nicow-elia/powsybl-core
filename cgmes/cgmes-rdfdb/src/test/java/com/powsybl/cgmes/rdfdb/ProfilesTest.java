/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Profile names: the nine standard ones, the file rule for a custom one, and the refusals.
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class ProfilesTest {

    @Test
    void theStandardNineAreTheSubsetsInTheirOrder() {
        assertThat(Profiles.STANDARD).containsExactly(Profiles.EQ, Profiles.TP, Profiles.SV, Profiles.SSH,
                Profiles.DY, Profiles.DL, Profiles.GL, Profiles.EQ_BD, Profiles.TP_BD);
        Arrays.stream(CgmesSubset.values()).filter(s -> s != CgmesSubset.UNKNOWN).forEach(subset -> {
            assertThat(Profiles.of(subset)).isEqualTo(subset.getIdentifier());
            assertThat(Profiles.subset(Profiles.of(subset))).contains(subset);
        });
        assertThat(Profiles.subset("OP")).isEmpty();
        assertThatThrownBy(() -> Profiles.of(CgmesSubset.UNKNOWN)).isInstanceOf(RdfDbException.class);
    }

    @Test
    void boundaryAndStandardPredicates() {
        assertThat(Profiles.isBoundary(Profiles.EQ_BD)).isTrue();
        assertThat(Profiles.isBoundary(Profiles.TP_BD)).isTrue();
        assertThat(Profiles.isBoundary(Profiles.EQ)).isFalse();
        assertThat(Profiles.isBoundary("OP")).isFalse();
        assertThat(Profiles.isStandard(Profiles.GL)).isTrue();
        assertThat(Profiles.isStandard("OP")).isFalse();
        assertThat(Profiles.isStandard("unknown")).isFalse();
    }

    @Test
    void theOrderListsTheStandardOnesFirst() {
        TreeSet<String> sorted = new TreeSet<>(Profiles.ORDER);
        sorted.addAll(List.of("OP", "TP_BD", "SSH", "CFG", "EQ"));
        assertThat(sorted).containsExactly("EQ", "SSH", "TP_BD", "CFG", "OP");
    }

    @Test
    void aStandardFileIsReadAsTheConversionReadsIt() {
        assertThat(Profiles.ofContextName("contexts:MicroGridTestConfiguration_BC_BE_EQ_V2.xml")).isEqualTo("EQ");
        assertThat(Profiles.ofContextName("contexts:MicroGridTestConfiguration_BC_BE_SSH_V2.xml"))
                .isEqualTo("SSH");
        assertThat(Profiles.ofContextName("MicroGridTestConfiguration_EQ_BD.xml")).isEqualTo("EQ_BD");
        assertThat(Profiles.ofContextName("contexts:20171002T0930Z_ENTSO-E_EQ_BD_1130.xml")).isEqualTo("EQ_BD");
        assertThat(Profiles.ofContextName("Grid_TP.xml")).isEqualTo("TP");
    }

    @Test
    void aCustomFileIsNamedByItsLastToken() {
        assertThat(Profiles.ofContextName("contexts:MicroGrid_OP.xml")).isEqualTo("OP");
        assertThat(Profiles.ofContextName("MicroGrid_BE_CFG.xml")).isEqualTo("CFG");
        assertThat(Profiles.ofContextName("dir/Grid_OPS2.zip")).isEqualTo("OPS2");
    }

    @Test
    void aFileThatNamesNoProfileIsRefused() {
        for (String name : List.of("Grid_SC_V2.xml", "Grid.xml", "Grid_op.xml", "Grid_001.xml", "Grid_.xml")) {
            assertThatThrownBy(() -> Profiles.ofContextName(name))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("cannot tell the profile of '" + name + "'")
                    .hasMessageContaining("<base>_<PROFILE>.xml");
            assertThat(Profiles.find(name)).isEmpty();
        }
    }

    @Test
    void aNameIsChecked() {
        assertThat(Profiles.check("OP")).isEqualTo("OP");
        assertThat(Profiles.check("EQ_BD")).isEqualTo("EQ_BD");
        assertThat(Profiles.check("X1")).isEqualTo("X1");
        for (String name : Arrays.asList(null, "", " ", "op", "1X", "_X", "E Q", "EQ-BD")) {
            assertThatThrownBy(() -> Profiles.check(name))
                    .isInstanceOf(RdfDbException.class)
                    .hasMessageContaining("is not a profile name");
        }
    }
}
