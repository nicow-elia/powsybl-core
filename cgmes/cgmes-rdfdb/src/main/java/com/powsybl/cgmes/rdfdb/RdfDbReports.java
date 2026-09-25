/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.commons.report.TypedValue;

import java.util.List;

/**
 * What the versioning layer tells a caller's report.
 *
 * <p>The one thing a caller has to be able to see afterwards is <em>which route an update took and why</em>: a
 * network that was rebuilt instead of updated is a different object, and a difference that could not be applied
 * has a reason a user can act on. Both go into the report next to everything else the conversion said, rather than
 * only into a log.</p>
 *
 * <p>The message templates live in the shared bundle of {@code powsybl-commons}, as every other powsybl report
 * does.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class RdfDbReports {

    private static final String SCENARIO = "scenario";
    private static final String MODEL_ID = "modelId";

    private RdfDbReports() {
    }

    /**
     * Report the route an update took, with the reasons it did not take a shorter one.
     *
     * @param reportNode where to report
     * @param scenario   the scenario the network was brought to
     * @param route      the route taken
     * @param diffCount  how many differences were applied
     * @param reasons    why the difference route was impossible, empty when it was taken
     */
    public static void updateRouteReport(ReportNode reportNode, String scenario, UpdateResult.Route route,
                                         int diffCount, List<String> reasons) {
        if (reportNode == null) {
            return;
        }
        ReportNode node = reportNode.newReportNode()
                .withMessageTemplate("core.cgmes.rdfdb.updateRoute")
                .withUntypedValue(SCENARIO, scenario)
                .withUntypedValue("route", route.name())
                .withUntypedValue("diffCount", diffCount)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
        reasons.forEach(reason -> node.newReportNode()
                .withMessageTemplate("core.cgmes.rdfdb.updateReason")
                .withUntypedValue("reason", reason)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add());
    }

    /**
     * Report a difference that was written into a scenario.
     *
     * @param reportNode where to report
     * @param modelId    the identifier of the stored difference
     * @param subset     the profile it describes
     * @param scenario   the scenario it was written into
     */
    public static void storedDifferenceReport(ReportNode reportNode, String modelId, CgmesSubset subset,
                                              String scenario) {
        if (reportNode == null) {
            return;
        }
        reportNode.newReportNode()
                .withMessageTemplate("core.cgmes.rdfdb.storedDifference")
                .withUntypedValue(MODEL_ID, modelId)
                .withUntypedValue("cgmesSubset", subset.getIdentifier())
                .withUntypedValue(SCENARIO, scenario)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    /**
     * Report a declared dependency the scenario does not hold.
     *
     * <p>A warning rather than a failure: a steady state difference legitimately depends on an equipment model
     * that was never uploaded, and refusing it would make the database unusable for exactly the case it is for.</p>
     *
     * @param reportNode  where to report
     * @param modelId     the identifier of the difference
     * @param dependentOn the identifier it declares a dependency on
     * @param scenario    the scenario
     */
    public static void dependencyNotStoredReport(ReportNode reportNode, String modelId, String dependentOn,
                                                 String scenario) {
        if (reportNode == null) {
            return;
        }
        reportNode.newReportNode()
                .withMessageTemplate("core.cgmes.rdfdb.dependencyNotStored")
                .withUntypedValue(MODEL_ID, modelId)
                .withUntypedValue("dependentOn", dependentOn)
                .withUntypedValue(SCENARIO, scenario)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    /**
     * Say that an instance file of a timestep was read but left alone.
     *
     * <p>State variables and topology change wholesale from one timestep to the next, so a difference of them
     * would be as large as the data itself; this release inherits the parent's. The caller is told, because the
     * network it loads at that timestep will carry the base's state variables, not the file's.</p>
     *
     * @param reportNode the node to report to
     * @param subset     the profile that was ignored
     * @param scenario   the scenario
     */
    public static void ingestedProfileIgnoredReport(ReportNode reportNode, String subset, String scenario) {
        if (reportNode == null) {
            return;
        }
        reportNode.newReportNode()
                .withMessageTemplate("core.cgmes.rdfdb.ingestedProfileIgnored")
                .withUntypedValue(CgmesSubsetKeys.SUBSET, subset)
                .withUntypedValue(SCENARIO, scenario)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    /** The binding name the reports bundle uses for a CGMES profile. */
    private static final class CgmesSubsetKeys {
        private static final String SUBSET = "cgmesSubset";

        private CgmesSubsetKeys() {
        }
    }
}
