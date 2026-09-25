/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.cgmes.model.diff.CgmesStatement;
import com.powsybl.commons.datasource.MemDataSource;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the ingestion reads of a timestep's files, and what it deliberately does not read.
 *
 * <p>The three properties this locks are the three the ingestion's correctness rests on: a profile that is
 * compared comes out exactly as a triple store would have handed it over, a profile that is inherited is read
 * for its header alone, and a profile the database already holds under the same model identifier is not read at
 * all beyond its header.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class IngestParserTest {

    private static final String SSH = "MicroGridTestConfiguration_BC_BE_SSH_V2.xml";

    private static ReadOnlyDataSource base() {
        return CgmesConformity1Catalog.microGridBaseCaseBE().dataSource();
    }

    /**
     * Only the equipment model and the steady state hypothesis are read in full; every other file of the export
     * contributes its {@code md:FullModel} and nothing else.
     */
    @Test
    void onlyTheComparedProfilesAreReadInFull() {
        IngestParser.Result result = IngestParser.read(base(), null, ReportNode.NO_OP, Map.of());

        assertThat(result.files()).hasSizeGreaterThan(5);
        for (IngestParser.ParsedFile file : result.files()) {
            assertThat(file.headerId()).as("header of " + file.name()).isNotNull().startsWith("urn:uuid:");
            boolean compared = file.subset() == CgmesSubset.EQUIPMENT
                    || file.subset() == CgmesSubset.STEADY_STATE_HYPOTHESIS;
            assertThat(file.index() != null).as("read in full: " + file.name()).isEqualTo(compared);
        }
        // And what was read in full is what a scratch triple store would have produced, statement for statement
        IngestParser.ParsedFile ssh = result.of(CgmesSubset.STEADY_STATE_HYPOTHESIS);
        assertThat(ssh.index().bySubject()).isEqualTo(viaStore(read(SSH), result).bySubject());
    }

    /**
     * A file whose model identifier is one the database already stores is the state the database holds, so it is
     * not indexed &mdash; which is what makes a timestep that re-ships its whole export cheap.
     */
    @Test
    void aProfileTheDatabaseAlreadyHoldsIsNotIndexed() {
        IngestParser.Result all = IngestParser.read(base(), null, ReportNode.NO_OP, Map.of());
        String equipmentId = all.of(CgmesSubset.EQUIPMENT).headerId();

        IngestParser.Result skipped = IngestParser.read(base(), null, ReportNode.NO_OP,
                Map.of(CgmesSubset.EQUIPMENT, equipmentId));

        assertThat(skipped.of(CgmesSubset.EQUIPMENT).index()).isNull();
        assertThat(skipped.of(CgmesSubset.EQUIPMENT).headerId()).isEqualTo(equipmentId);
        // The steady state hypothesis is not on the list, so it is still read in full
        assertThat(skipped.of(CgmesSubset.STEADY_STATE_HYPOTHESIS).index()).isNotNull();
    }

    /**
     * A triple a file states twice is one triple.
     *
     * <p>A triple store has set semantics and the comparison has always seen one, so a file that repeats a
     * statement &mdash; which is legal RDF/XML and the reason the duplicate-id error is tolerated &mdash; must
     * not produce a difference the plain file does not.</p>
     */
    @Test
    void aRepeatedTripleIsIndexedOnce() {
        IngestParser.Result plain = IngestParser.read(base(), null, ReportNode.NO_OP, Map.of());
        IngestParser.Result repeated = IngestParser.read(withDuplicatedConsumer(), null, ReportNode.NO_OP, Map.of());

        assertThat(repeated.of(CgmesSubset.STEADY_STATE_HYPOTHESIS).index().bySubject())
                .isEqualTo(plain.of(CgmesSubset.STEADY_STATE_HYPOTHESIS).index().bySubject());
    }

    /**
     * The unchanged gate is per profile: an identifier that is the database's state of <em>another</em> profile
     * does not make a file unchanged.
     */
    @Test
    void anIdentifierIsOnlyUnchangedForItsOwnProfile() {
        IngestParser.Result all = IngestParser.read(base(), null, ReportNode.NO_OP, Map.of());
        String equipmentId = all.of(CgmesSubset.EQUIPMENT).headerId();
        String sshId = all.of(CgmesSubset.STEADY_STATE_HYPOTHESIS).headerId();

        IngestParser.Result crossed = IngestParser.read(base(), null, ReportNode.NO_OP,
                Map.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, equipmentId, CgmesSubset.EQUIPMENT, sshId));

        assertThat(crossed.of(CgmesSubset.EQUIPMENT).index()).isNotNull();
        assertThat(crossed.of(CgmesSubset.STEADY_STATE_HYPOTHESIS).index()).isNotNull();
    }

    /**
     * RDF/XML does not require the {@code md:FullModel} to come first. A compared file whose header follows its
     * first object is read in full, indexed exactly as a store would have it, and keeps its whole header &mdash;
     * even when its identifier is the unchanged one, since that cannot be known at the first object.
     */
    @Test
    void aComparedFileWhoseHeaderIsNotFirstIsReadInFull() {
        IngestParser.Result plain = IngestParser.read(base(), null, ReportNode.NO_OP, Map.of());
        IngestParser.ParsedFile plainSsh = plain.of(CgmesSubset.STEADY_STATE_HYPOTHESIS);
        String moved = headerAfterFirstObject(read(SSH));

        IngestParser.Result result = IngestParser.read(withSsh(moved), null, ReportNode.NO_OP,
                Map.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, plainSsh.headerId()));

        IngestParser.ParsedFile ssh = result.of(CgmesSubset.STEADY_STATE_HYPOTHESIS);
        assertThat(ssh.headerId()).isEqualTo(plainSsh.headerId());
        assertThat(ssh.terms()).isEqualTo(plainSsh.terms());
        assertThat(ssh.index()).isNotNull();
        assertThat(ssh.index().bySubject()).isEqualTo(viaStore(moved, result).bySubject());
    }

    /** A header property written twice is one value, as it was when the header came out of a store. */
    @Test
    void aRepeatedHeaderPropertyIsCollectedOnce() {
        String ssh = read(SSH);
        Matcher profile = Pattern.compile("\\s*<md:Model.profile>[^<]*</md:Model.profile>").matcher(ssh);
        assertThat(profile.find()).isTrue();
        String doubled = ssh.substring(0, profile.end()) + profile.group() + ssh.substring(profile.end());

        IngestParser.Result result = IngestParser.read(withSsh(doubled), null, ReportNode.NO_OP, Map.of());

        assertThat(result.of(CgmesSubset.STEADY_STATE_HYPOTHESIS).terms().get(RdfDbVocabulary.MODEL_PROFILE))
                .hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    /** The same file with its {@code md:FullModel} block moved behind the first grid object. */
    private static String headerAfterFirstObject(String file) {
        int headerStart = file.indexOf("<md:FullModel");
        int headerEnd = file.indexOf("</md:FullModel>") + "</md:FullModel>".length();
        String header = file.substring(headerStart, headerEnd);
        String rest = file.substring(0, headerStart) + file.substring(headerEnd);
        int firstObject = rest.indexOf("<cim:", rest.indexOf("<rdf:RDF"));
        int firstObjectEnd = rest.indexOf("</cim:Terminal>", firstObject) + "</cim:Terminal>".length();
        assertThat(rest.substring(firstObject)).startsWith("<cim:Terminal ");
        return rest.substring(0, firstObjectEnd) + "\n  " + header + rest.substring(firstObjectEnd);
    }

    /** The base case with its steady state hypothesis replaced. */
    private static ReadOnlyDataSource withSsh(String content) {
        MemDataSource source = new MemDataSource();
        try {
            for (String name : base().listNames(".*")) {
                write(source, name, SSH.equals(name) ? content : read(name));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return source;
    }

    /**
     * An IRI that is not valid syntax (a space, a broken percent escape) is indexed like any other: the parser
     * does not verify IRI syntax, and verifying it as a non-fatal error, as before, only reported the IRI and
     * created the same one. The statements of the file are otherwise those of the unmodified file.
     */
    @Test
    void aMalformedIriIsIndexedAsWritten() {
        String ssh = read(SSH);
        int end = ssh.lastIndexOf("</rdf:RDF>");
        String malformed = ssh.substring(0, end)
                + "<cim:EnergyConsumer rdf:about=\"#_bad%zz\">"
                + "<cim:EnergyConsumer.p>1.5</cim:EnergyConsumer.p>"
                + "<cim:EnergyConsumer.LoadResponse rdf:resource=\"#_a b\"/>"
                + "</cim:EnergyConsumer>\n" + ssh.substring(end);
        IngestParser.Result plain = IngestParser.read(base(), null, ReportNode.NO_OP, Map.of());
        IngestParser.Result result = IngestParser.read(withSsh(malformed), null, ReportNode.NO_OP, Map.of());

        Map<String, Map<String, List<CgmesStatement>>> bySubject =
                result.of(CgmesSubset.STEADY_STATE_HYPOTHESIS).index().bySubject();
        Map<String, Map<String, List<CgmesStatement>>> expected =
                new HashMap<>(plain.of(CgmesSubset.STEADY_STATE_HYPOTHESIS).index().bySubject());
        assertThat(bySubject).containsKey("bad%25zz");
        expected.put("bad%25zz", bySubject.get("bad%25zz"));
        assertThat(bySubject).isEqualTo(expected);
        assertThat(bySubject.get("bad%25zz").get("EnergyConsumer.p"))
                .extracting(CgmesStatement::value).containsExactly("1.5");
        assertThat(bySubject.get("bad%25zz").get("EnergyConsumer.LoadResponse"))
                .extracting(CgmesStatement::value).containsExactly("a%20b");
    }

    /** The base case with the first energy consumer's whole block written a second time, verbatim. */
    private static ReadOnlyDataSource withDuplicatedConsumer() {
        String ssh = read(SSH);
        int start = ssh.indexOf("<cim:EnergyConsumer rdf:about=");
        int end = ssh.indexOf("</cim:EnergyConsumer>", start) + "</cim:EnergyConsumer>".length();
        assertThat(start).isPositive();
        String block = ssh.substring(start, end);
        String duplicated = ssh.substring(0, end) + "\n" + block + ssh.substring(end);

        return withSsh(duplicated);
    }

    /** The same file through a scratch {@code MemoryStore} and {@code TripleDiffCalculator.index}, as before. */
    private static TripleDiffCalculator.Index viaStore(String content, IngestParser.Result result) {
        SailRepository repository = new SailRepository(new MemoryStore());
        repository.init();
        List<Statement> statements = new ArrayList<>();
        try (var connection = repository.getConnection()) {
            try (InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
                connection.add(in, result.baseName(), RDFFormat.RDFXML);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            connection.getStatements(null, null, null, false).forEach(statements::add);
        }
        repository.shutDown();
        return TripleDiffCalculator.index(statements, ModelCatalog.subjectBaseOf(result.baseName()),
                result.cimNamespace());
    }

    private static String read(String name) {
        try (InputStream in = base().newInputStream(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(MemDataSource source, String name, String content) {
        try (var out = source.newOutputStream(name, false)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
