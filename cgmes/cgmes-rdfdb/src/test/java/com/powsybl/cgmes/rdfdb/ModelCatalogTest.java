/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.cgmes.model.diff.DifferenceModel;
import com.powsybl.cgmes.model.diff.DifferenceModelHeader;
import com.powsybl.cgmes.model.diff.DifferenceModelSet;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static com.powsybl.cgmes.rdfdb.Backends.microGridBe;
import static com.powsybl.cgmes.rdfdb.Backends.params;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The metadata graph seen through {@link ModelCatalog}: what an upload registers, what a chain looks like, and what
 * one scenario can and cannot see of another.
 *
 * <p>The catalogue is the index of a scenario, so the questions asked here are the ones every other part of the
 * versioning layer asks it: which model is the head of a profile, which instance file did a chain start from, what
 * lies between two models. They are answered by SPARQL against a real store on both backends, because that is where
 * a property path or an aggregate is either supported or silently different.</p>
 *
 * <p>Isolation is asserted throughout rather than in one case: every fixture holds two scenarios, and every answer
 * about one of them is also an answer about what the other did not contribute.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class ModelCatalogTest {

    private static final String S = "2016-01-01";
    private static final String OTHER = "2016-01-02";
    private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";

    /** A connection whose two scenarios both hold the MicroGrid BE fixture. */
    private static RdfDbConnection twoScenarios(String backend) {
        RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "catalog"));
        db.clear(S);
        db.clear(OTHER);
        db.loadCgmes(S, microGridBe(), null, params(), ReportNode.NO_OP);
        db.loadCgmes(OTHER, microGridBe(), null, params(), ReportNode.NO_OP);
        return db;
    }

    // ------------------------------------------------------------------ registration

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anEmptyScenarioHasAnEmptyCatalogue(String backend) {
        try (RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "catalog-empty"))) {
            String scenario = "nothing-here";
            db.clear(scenario);
            ModelCatalog catalog = db.catalog(scenario);
            assertThat(catalog.isEmpty()).isTrue();
            assertThat(catalog.models()).isEmpty();
            assertThat(catalog.hasDifferences()).isFalse();
            assertThat(catalog.head(Profiles.SSH)).isEmpty();
            assertThat(catalog.full(Profiles.SSH)).isEmpty();
            assertThat(catalog.chainDown("urn:uuid:nothing")).isEmpty();
            assertThat(catalog.model("urn:uuid:nothing")).isEmpty();
            assertThat(catalog.orphanGraphs()).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anUploadRegistersEveryInstanceFileWithItsHeader(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            ModelCatalog catalog = db.catalog(S);
            assertThat(catalog.isEmpty()).isFalse();
            List<StoredModel> models = catalog.models();
            assertThat(models).hasSameSizeAs(db.contextNames(S));
            assertThat(models).allSatisfy(model -> {
                assertThat(model.kind()).isEqualTo(StoredModel.Kind.FULL);
                assertThat(model.scenario()).isEqualTo(S);
                assertThat(model.chainDepth()).isZero();
                assertThat(model.graph()).isNotBlank();
                assertThat(model.cimNamespace()).isEqualTo(CIM16);
                assertThat(model.tripleCount()).isPositive();
                assertThat(model.supersedes()).isEmpty();
                assertThat(model.forwardGraph()).isNull();
                assertThat(model.reverseGraph()).isNull();
            });
            // The header of the steady state file, as the file itself carries it
            StoredModel ssh = catalog.full(Profiles.SSH).orElseThrow();
            assertThat(ssh.profiles()).isNotEmpty();
            assertThat(ssh.modelingAuthoritySet()).isNotBlank();
            assertThat(ssh.dependentOn()).isNotEmpty();
            assertThat(ssh.version()).isPositive();
            assertThat(catalog.model(ssh.id())).contains(ssh);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anUploadRegistersACustomProfileFileUnderTheNameItsFileGives(String backend) {
        try (RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "catalog-custom"))) {
            String scenario = "custom";
            db.clear(scenario);
            ReadOnlyDataSource files = TimestampFixtures.with(TimestampFixtures.with(microGridBe(),
                    TimestampFixtures.CFG, TimestampFixtures.cfg("urn:uuid:cfg-1", Backends.BASE, "40")),
                    "Notes.xml", TimestampFixtures.cfg("urn:uuid:notes", Backends.BASE, "1"));
            db.loadCgmes(scenario, files, null, params(), ReportNode.NO_OP);
            ModelCatalog catalog = db.catalog(scenario);
            // A file whose name says no profile is uploaded, and not registered
            assertThat(catalog.models()).hasSize(db.contextNames(scenario).size() - 1);
            assertThat(catalog.model("urn:uuid:notes")).isEmpty();
            StoredModel cfg = catalog.full("CFG").orElseThrow();
            assertThat(cfg.id()).isEqualTo("urn:uuid:cfg-1");
            assertThat(cfg.kind()).isEqualTo(StoredModel.Kind.FULL);
            assertThat(cfg.profiles()).containsExactly("http://example.org/Configuration/1");
            assertThat(cfg.tripleCount()).isPositive();
            assertThat(db.graphs(scenario)).extracting(GraphInfo::profile).contains("CFG", Profiles.EQ);
            db.clear(scenario);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void modelsAreSortedByProfileThenByDepth(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            ModelCatalog catalog = db.catalog(S);
            StoredModel base = catalog.full(Profiles.SSH).orElseThrow();
            new RdfDbDifferenceSink(db, S).accept(diff("urn:uuid:c-1", base.id()));
            new RdfDbDifferenceSink(db, S).accept(diff("urn:uuid:c-2", "urn:uuid:c-1"));

            List<StoredModel> ssh = catalog.models().stream()
                    .filter(model -> Profiles.SSH.equals(model.subset())).toList();
            assertThat(ssh.stream().map(StoredModel::chainDepth).toList()).isSorted();
            assertThat(ssh.stream().map(StoredModel::id).toList())
                    .containsExactly(base.id(), "urn:uuid:c-1", "urn:uuid:c-2");
            assertThat(catalog.models().stream().map(StoredModel::subset).toList()).isSorted();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void reRegisteringAGraphReplacesItsNodeRatherThanMergingIntoIt(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            ModelCatalog catalog = db.catalog(S);
            StoredModel before = catalog.full(Profiles.SSH).orElseThrow();
            // The overload that probes the data rather than being told what the upload used
            catalog.registerFullModels(db.contextNames(S));
            StoredModel after = catalog.full(Profiles.SSH).orElseThrow();
            assertThat(after.id()).isEqualTo(before.id());
            assertThat(after.subset()).isEqualTo(before.subset());
            assertThat(after.graph()).isEqualTo(before.graph());
            assertThat(after.cimNamespace()).isEqualTo(before.cimNamespace());
            assertThat(after.subjectBase()).isEqualTo(before.subjectBase());
            // A merge would have produced two versions, two depths, two graphs on one node
            assertThat(catalog.models()).hasSameSizeAs(db.contextNames(S));
        }
    }

    // ------------------------------------------------------------------ chains

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void headAndChainDownFollowTheSupersedesChain(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            ModelCatalog catalog = db.catalog(S);
            StoredModel base = catalog.full(Profiles.SSH).orElseThrow();
            assertThat(catalog.head(Profiles.SSH).orElseThrow().id()).isEqualTo(base.id());

            new RdfDbDifferenceSink(db, S).accept(diff("urn:uuid:h-1", base.id()));
            new RdfDbDifferenceSink(db, S).accept(diff("urn:uuid:h-2", "urn:uuid:h-1"));
            new RdfDbDifferenceSink(db, S).accept(diff("urn:uuid:h-3", "urn:uuid:h-2"));

            assertThat(catalog.head(Profiles.SSH).orElseThrow().id())
                    .isEqualTo("urn:uuid:h-3");
            assertThat(catalog.hasDifferences()).isTrue();
            // Head first, down to the instance file
            assertThat(catalog.chainDown("urn:uuid:h-3").stream().map(StoredModel::id).toList())
                    .containsExactly("urn:uuid:h-3", "urn:uuid:h-2", "urn:uuid:h-1", base.id());
            assertThat(catalog.chainDown("urn:uuid:h-3").stream().map(StoredModel::chainDepth).toList())
                    .containsExactly(3, 2, 1, 0);
            // From the middle of the chain, only what lies below
            assertThat(catalog.chainDown("urn:uuid:h-2").stream().map(StoredModel::id).toList())
                    .containsExactly("urn:uuid:h-2", "urn:uuid:h-1", base.id());
            // The full model of the profile is still the instance file, however long the chain gets
            assertThat(catalog.full(Profiles.SSH).orElseThrow().id()).isEqualTo(base.id());
            // Another profile is untouched by all of this
            assertThat(catalog.head(Profiles.EQ).orElseThrow().id())
                    .isEqualTo(catalog.full(Profiles.EQ).orElseThrow().id());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void anUnknownIdentifierHasNoChain(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            assertThat(db.catalog(S).chainDown("urn:uuid:never-stored")).isEmpty();
        }
    }

    // ------------------------------------------------------------------ one catalogue per scenario

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void cataloguesArePerScenario(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            ModelCatalog here = db.catalog(S);
            ModelCatalog there = db.catalog(OTHER);
            assertThat(here.metaGraph()).isNotEqualTo(there.metaGraph());
            assertThat(here.metaGraph()).isEqualTo(RdfDbNames.metaGraph(S));
            assertThat(db.catalog(S)).isSameAs(here);

            StoredModel base = here.full(Profiles.SSH).orElseThrow();
            // The same files in both scenarios, so the same identifiers: two independent nodes
            assertThat(there.model(base.id())).isPresent();
            assertThat(there.model(base.id()).orElseThrow().scenario()).isEqualTo(OTHER);
            assertThat(here.scenariosOf(base.id())).containsExactly(OTHER);
            assertThat(there.scenarioOf(base.id())).contains(S);

            new RdfDbDifferenceSink(db, S).accept(diff("urn:uuid:only-here", base.id()));
            assertThat(here.head(Profiles.SSH).orElseThrow().id())
                    .isEqualTo("urn:uuid:only-here");
            assertThat(there.head(Profiles.SSH).orElseThrow().id()).isEqualTo(base.id());
            assertThat(there.hasDifferences()).isFalse();
            assertThat(there.model("urn:uuid:only-here")).isEmpty();
            assertThat(there.chainDown(base.id()).stream().map(StoredModel::id).toList())
                    .containsExactly(base.id());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aScenarioNameSurvivesTheIriRoundTrip(String backend) {
        try (RdfDbConnection db = RdfDbConnection.open(Backends.database(backend, "catalog-names"))) {
            // Whitespace and '/' are refused by the scenario name rules of the loading layer: one would make the
            // name unusable in a report, the other would forge a second IRI segment and let one scenario address
            // another's graphs. Everything else is free-form and has to survive the round trip through the IRI.
            String scenario = "DACF#2016?01:01+%";
            db.clear(scenario);
            db.loadCgmes(scenario, microGridBe(), null, params(), ReportNode.NO_OP);
            String meta = db.catalog(scenario).metaGraph();
            assertThat(meta).startsWith(RdfDbNames.BASE).endsWith("/meta")
                    .doesNotContain("#").doesNotContain("?");
            assertThat(RdfDbNames.scenarioOfMetaGraph(meta)).isEqualTo(scenario);
            assertThat(RdfDbNames.unsafe(RdfDbNames.safe(scenario))).isEqualTo(scenario);
            assertThat(db.scenarios()).contains(scenario);
            assertThat(db.catalog(scenario).models()).isNotEmpty()
                    .allMatch(model -> scenario.equals(model.scenario()));
            db.clear(scenario);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.powsybl.cgmes.rdfdb.Backends#backends")
    void aScenarioThatOnlyOwnsAMetadataGraphIsListed(String backend) {
        try (RdfDbConnection db = twoScenarios(backend)) {
            assertThat(db.scenarios()).contains(S, OTHER);
        }
    }

    // ------------------------------------------------------------------ what the catalogue refuses

    @Test
    void aBlankScenarioHasNoCatalogue() {
        try (RdfDbConnection db = RdfDbConnection.open(Backends.database(Backends.MEMORY, "catalog-blank"))) {
            for (String blank : new String[] {"", " ", null}) {
                assertThatThrownBy(() -> db.catalog(blank))
                        .isInstanceOf(RdfDbException.class)
                        .hasMessageContaining("must not be blank");
            }
        }
    }

    @Test
    void theSubjectBaseOfAnUploadFollowsTheResolutionRulesOfRelativeReferences() {
        // A base with an authority and no path gets the root path added before the fragment (RFC 3986 6.2.3)
        assertThat(ModelCatalog.subjectBaseOf("http://microgrid")).isEqualTo("http://microgrid/#");
        assertThat(ModelCatalog.subjectBaseOf("http://microgrid/")).isEqualTo("http://microgrid/#");
        assertThat(ModelCatalog.subjectBaseOf("http://microgrid/base")).isEqualTo("http://microgrid/base#");
        assertThat(ModelCatalog.subjectBaseOf("http://microgrid/#")).isEqualTo("http://microgrid/#");
    }

    // ------------------------------------------------------------------ helpers

    /** A one-statement steady state difference of the MicroGrid load, superseding the given model. */
    private static DifferenceModelSet diff(String id, String supersedes) {
        DifferenceModelHeader header = DifferenceModelHeader
                .builder(id, CgmesSubset.STEADY_STATE_HYPOTHESIS, CIM16)
                .version(2).supersedes(List.of(supersedes)).build();
        return new DifferenceModelSet(List.of(new DifferenceModel(header,
                List.of(CgmesStatement.literal(Changes.LOAD_ID, null, "EnergyConsumer.p", "1")),
                List.of(CgmesStatement.literal(Changes.LOAD_ID, null, "EnergyConsumer.p", "0")), List.of())));
    }
}
