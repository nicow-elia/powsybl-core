/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.commons.report.ReportNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The report messages of the versioning layer render from the module's own bundle.
 *
 * <p>The templates live in {@code com/powsybl/cgmes/rdfdb/reports*.properties} and reach a report tree through the
 * {@code ReportResourceBundle} service the module registers, the same way a module outside powsybl-core adds its
 * messages. A root built from every bundle on the classpath must therefore render them, in both languages.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbReportsTest {

    private static ReportNode root(Locale locale) {
        return ReportNode.newRootReportNode()
                .withAllResourceBundlesFromClasspath()
                .withLocale(locale)
                .withMessageTemplate("test")
                .build();
    }

    @Test
    void theMessagesRenderInEnglish() {
        ReportNode root = root(Locale.ENGLISH);
        RdfDbReports.updateRouteReport(root, "s1", UpdateResult.Route.FULL_RELOAD, 0, List.of("no common ancestor"));
        RdfDbReports.storedDifferenceReport(root, "urn:uuid:d1", Profiles.SSH, "s1");
        RdfDbReports.dependencyNotStoredReport(root, "urn:uuid:d1", "urn:uuid:eq", "s1");
        RdfDbReports.ingestedProfileIgnoredReport(root, "SV", "s1");

        List<ReportNode> children = root.getChildren();
        assertThat(children).extracting(ReportNode::getMessage).containsExactly(
                "Update of the network towards scenario s1: FULL_RELOAD, 0 difference(s)",
                "Stored difference model urn:uuid:d1 of profile SSH in scenario s1",
                "Difference model urn:uuid:d1 depends on urn:uuid:eq, which scenario s1 does not hold",
                "The SV file of the ingested timestamp was not compared; scenario s1 inherits the state of the parent"
                        + " snapshot");
        assertThat(children.get(0).getChildren()).extracting(ReportNode::getMessage)
                .containsExactly("The difference route was not taken: no common ancestor");
    }

    @Test
    void theMessagesRenderInFrench() {
        ReportNode root = root(Locale.FRENCH);
        RdfDbReports.updateRouteReport(root, "s1", UpdateResult.Route.FULL_RELOAD, 0, List.of("raison"));

        ReportNode update = root.getChildren().get(0);
        assertThat(update.getMessage())
                .isEqualTo("Mise à jour du réseau vers le scénario s1 : FULL_RELOAD, 0 différence(s)");
        assertThat(update.getChildren().get(0).getMessage())
                .isEqualTo("La route par différences n'a pas été prise : raison");
    }
}
