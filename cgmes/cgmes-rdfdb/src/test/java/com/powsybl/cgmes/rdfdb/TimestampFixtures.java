/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conformity.CgmesConformity1Catalog;
import com.powsybl.commons.datasource.MemDataSource;
import com.powsybl.commons.datasource.ReadOnlyDataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The daily CGMES export of a timestamp, made by editing the MicroGrid base case.
 *
 * <p>What the ingestion path has to be tested against is a <em>set of instance files</em> that differs from the
 * base in exactly the way a schedule differs from it: the same equipment, the same identifiers, other setpoints,
 * another scenario time. No conformity fixture offers a day, so one is generated here by rewriting the text of the
 * steady state file &mdash; which is also how a reader can see precisely what the difference under test is.</p>
 *
 * <p>The edits are deliberately textual. Re-exporting the model through the CGMES exporter would produce a file
 * that differs from the base in a hundred incidental ways (element order, formatting, generated identifiers), and
 * a difference calculator tested against that would be tested against the exporter rather than against the
 * change.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class TimestampFixtures {

    private static final String SSH = "MicroGridTestConfiguration_BC_BE_SSH_V2.xml";
    private static final String EQ = "MicroGridTestConfiguration_BC_BE_EQ_V2.xml";

    private static final String EQ_BD = "MicroGridTestConfiguration_EQ_BD.xml";
    private static final String TP_BD = "MicroGridTestConfiguration_TP_BD.xml";

    /**
     * The files of the base case that a timestamp does not touch &mdash; the boundary included.
     *
     * <p>The boundary is what the base voltages live in, so a set without it is not loadable at all; it also has to
     * be byte-identical to the base's, which is what {@code putAsDiff} checks.</p>
     */
    private static final List<String> UNCHANGED = List.of(
            "MicroGridTestConfiguration_BC_BE_DL_V2.xml",
            "MicroGridTestConfiguration_BC_BE_DY_V2.xml",
            "MicroGridTestConfiguration_BC_BE_GL_V2.xml",
            "MicroGridTestConfiguration_BC_BE_SV_V2.xml",
            "MicroGridTestConfiguration_BC_BE_TP_V2.xml",
            EQ_BD,
            "MicroGridTestConfiguration_TP_BD.xml");

    private static final Pattern CONSUMER_P =
            Pattern.compile("(<cim:EnergyConsumer.p>)(-?[0-9.eE+]+)(</cim:EnergyConsumer.p>)");

    private TimestampFixtures() {
    }

    /**
     * The base case with the active power of the first {@code loads} energy consumers scaled.
     *
     * @param loads    how many consumers the timestamp moves
     * @param instant  the scenario time the files claim, for instance {@code 2014-06-01T11:00:00Z}
     * @param suffix   what makes the model identifiers of this timestamp unique
     * @return a data source holding the whole set
     */
    static ReadOnlyDataSource ssh(int loads, Instant instant, String suffix) {
        String ssh = read(SSH);
        ssh = rewriteHeader(ssh, "urn:uuid:ssh-" + suffix, instant);
        ssh = scaleConsumers(ssh, loads);
        MemDataSource source = new MemDataSource();
        put(source, SSH, ssh);
        put(source, EQ, read(EQ));
        UNCHANGED.forEach(name -> put(source, name, read(name)));
        return source;
    }

    /**
     * The same set, with one line renamed in the equipment model.
     *
     * <p>"EQ drift": the equipment of a day is not quite the equipment of the base. A name is the cheapest change
     * that no in-place update can apply, so it is what makes the ingested difference take the slow route.</p>
     *
     * @param loads   how many consumers the timestamp moves
     * @param instant the scenario time
     * @param suffix  what makes the model identifiers unique
     * @return a data source holding the whole set
     */
    static ReadOnlyDataSource eqDrift(int loads, Instant instant, String suffix) {
        String ssh = scaleConsumers(rewriteHeader(read(SSH), "urn:uuid:ssh-" + suffix, instant), loads);
        String eq = rewriteHeader(read(EQ), "urn:uuid:eq-" + suffix, instant);
        eq = renameFirstLine(eq);
        MemDataSource source = new MemDataSource();
        put(source, SSH, ssh);
        put(source, EQ, eq);
        UNCHANGED.forEach(name -> put(source, name, read(name)));
        return source;
    }

    /** The base case with a boundary that claims to be a different model. */
    static ReadOnlyDataSource changedBoundary(Instant instant, String suffix) {
        MemDataSource source = new MemDataSource();
        put(source, SSH, scaleConsumers(rewriteHeader(read(SSH), "urn:uuid:ssh-" + suffix, instant), 1));
        put(source, EQ, read(EQ));
        UNCHANGED.stream().filter(name -> !EQ_BD.equals(name))
                .forEach(name -> put(source, name, read(name)));
        put(source, EQ_BD, rewriteHeader(read(EQ_BD), "urn:uuid:eqbd-" + suffix, instant));
        return source;
    }

    /**
     * The MicroGrid NL base case with a boundary whose two models claim to be different models: another authority
     * of the same day that does not share the boundary of the scenario.
     *
     * @param suffix what makes the boundary identifier unique
     * @return a data source holding the whole set
     */
    static ReadOnlyDataSource changedBoundaryNl(String suffix) {
        ReadOnlyDataSource nl = CgmesConformity1Catalog.microGridBaseCaseNL().dataSource();
        MemDataSource source = new MemDataSource();
        try {
            for (String name : nl.listNames(".*")) {
                String content = read(nl, name);
                String renamed = EQ_BD.equals(name) ? "eqbd-" : TP_BD.equals(name) ? "tpbd-" : null;
                put(source, name, renamed == null ? content
                        : content.replaceFirst("rdf:about=\"urn:uuid:[^\"]*\"",
                                Matcher.quoteReplacement("rdf:about=\"urn:uuid:" + renamed + suffix + "\"")));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return source;
    }

    // ------------------------------------------------------------------ a custom profile

    /** The file name of the synthetic custom profile: its last token, {@code CFG}, is its profile. */
    static final String CFG = "MicroGridTestConfiguration_BC_BE_CFG.xml";

    /** The namespace of the invented vocabulary the custom profile speaks. */
    static final String CFG_NS = "http://example.org/Configuration/1#";

    /**
     * A custom profile file: a {@code md:FullModel} header of the BE authority and three settings in an invented
     * namespace, every subject an absolute IRI so that the statements do not depend on the base the file is parsed
     * against. It declares the CIM namespace without using it, as a CIM extension does, which is what makes a CGMES
     * data source list it.
     *
     * @param modelId the identifier of the model the file carries
     * @param instant the scenario time it claims
     * @param value   the value of the first setting, which is what a later timestamp changes
     * @return the RDF/XML text
     */
    static String cfg(String modelId, Instant instant, String value) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#"
                         xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
                         xmlns:cfg="%s">
                  <md:FullModel rdf:about="%s">
                    <md:Model.scenarioTime>%s</md:Model.scenarioTime>
                    <md:Model.created>2014-06-01T09:00:00Z</md:Model.created>
                    <md:Model.version>1</md:Model.version>
                    <md:Model.profile>http://example.org/Configuration/1</md:Model.profile>
                    <md:Model.modelingAuthoritySet>http://elia.be/CGMES/2.4.15</md:Model.modelingAuthoritySet>
                  </md:FullModel>
                  <cfg:Setting rdf:about="http://example.org/cfg/setting-1">
                    <cfg:Setting.name>ramp limit</cfg:Setting.name>
                    <cfg:Setting.value>%s</cfg:Setting.value>
                  </cfg:Setting>
                  <cfg:Setting rdf:about="http://example.org/cfg/setting-2">
                    <cfg:Setting.name>reserve share</cfg:Setting.name>
                    <cfg:Setting.value>0.25</cfg:Setting.value>
                  </cfg:Setting>
                  <cfg:Setting rdf:about="http://example.org/cfg/setting-3">
                    <cfg:Setting.next rdf:resource="http://example.org/cfg/setting-1"/>
                  </cfg:Setting>
                </rdf:RDF>
                """.formatted(CFG_NS, modelId, instant, value);
    }

    /**
     * A copy of a data source with one more file.
     *
     * @param base    the files to copy
     * @param name    the name of the added file
     * @param content its text
     * @return the copy
     */
    static ReadOnlyDataSource with(ReadOnlyDataSource base, String name, String content) {
        MemDataSource source = new MemDataSource();
        try {
            for (String file : base.listNames(".*")) {
                put(source, file, read(base, file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        put(source, name, content);
        return source;
    }

    // ------------------------------------------------------------------ the edits

    private static String scaleConsumers(String ssh, int loads) {
        Matcher matcher = CONSUMER_P.matcher(ssh);
        StringBuilder out = new StringBuilder();
        int done = 0;
        while (matcher.find()) {
            String replacement = matcher.group(1) + matcher.group(2) + matcher.group(3);
            if (done < loads) {
                double value = Double.parseDouble(matcher.group(2));
                // A value that is never the original one, and never zero: the difference has to be visible
                replacement = matcher.group(1) + (value + 7.0 + done) + matcher.group(3);
                done++;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        if (done < loads) {
            throw new IllegalStateException("the fixture holds " + done + " energy consumers, not " + loads);
        }
        return out.toString();
    }

    static String rewriteHeader(String file, String modelId, Instant instant) {
        String rewritten = file.replaceFirst("rdf:about=\"urn:uuid:[^\"]*\"",
                Matcher.quoteReplacement("rdf:about=\"" + modelId + "\""));
        rewritten = rewritten.replaceAll("(<md:Model.scenarioTime>)[^<]*(</md:Model.scenarioTime>)",
                "$1" + Matcher.quoteReplacement(instant.toString()) + "$2");
        return rewritten;
    }

    static String renameFirstLine(String eq) {
        int line = eq.indexOf("<cim:ACLineSegment rdf:ID=");
        if (line < 0) {
            throw new IllegalStateException("the equipment fixture holds no ACLineSegment");
        }
        int name = eq.indexOf("<cim:IdentifiedObject.name>", line);
        int end = eq.indexOf("</cim:IdentifiedObject.name>", name);
        if (name < 0 || end < 0) {
            throw new IllegalStateException("the first ACLineSegment has no name");
        }
        return eq.substring(0, name) + "<cim:IdentifiedObject.name>renamed-by-the-day"
                + eq.substring(end);
    }

    // ------------------------------------------------------------------ reading and writing

    private static String read(String name) {
        return read(CgmesConformity1Catalog.microGridBaseCaseBE().dataSource(), name);
    }

    static String read(ReadOnlyDataSource source, String name) {
        try (InputStream in = source.newInputStream(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void put(MemDataSource source, String name, String content) {
        try (var out = source.newOutputStream(name, false)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
