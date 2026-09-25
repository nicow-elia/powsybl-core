/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conformity.Cgmes3Catalog;
import com.powsybl.commons.datasource.DataSource;
import com.powsybl.commons.datasource.MemDataSource;
import com.powsybl.commons.datasource.ReadOnlyDataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Svedala, copied {@code n} times into one model set: an IGM-sized grid built from the largest CGMES 3 model the
 * test repertoire carries.
 *
 * <p><b>Why text, not RDF.</b> The Svedala files are regular: every object is {@code rdf:ID="_<uuid>"} or
 * {@code rdf:about="#_<uuid>"}, every local reference {@code rdf:resource="#_<uuid>"}, every
 * {@code IdentifiedObject.mRID} the bare uuid, and every model header {@code urn:uuid:<id>}. A copy is therefore
 * the body of the file with every uuid token renamed &mdash; one precompiled pattern and a per-copy memo &mdash;
 * which takes seconds for 288 MB, where a copy through a parser, a store and a writer would take minutes and would
 * benchmark the writer. {@link SvedalaTimestepFixtures} edits the same files textually for the same reason.</p>
 *
 * <p><b>The rules.</b> Each file keeps <em>one</em> header, whose {@code urn:uuid:} identifiers (its own and its
 * {@code DependentOn}/{@code Supersedes} links) are renamed by {@link #modelId}, the same rule for every file, so
 * a replicated steady state still depends on the replicated equipment model. The body is appended {@code n}
 * times; copy {@code 1} is the identity, so the plain model is a subset of every replica and every identifier the
 * fixtures of this package use stays valid; copy {@code k > 1} renames every uuid token with
 * {@link #objectId}. File names are kept, because profile recognition works on the name.</p>
 *
 * <p><b>Boundary.</b> Svedala ships none and references nothing outside its five files, so the copies are
 * {@code n} electrically separate islands of one model set with nothing to attach to; no boundary is invented.</p>
 *
 * <p><b>Cache.</b> A replicated grid is written once to {@code <fixtures>/svedala-x<n>/} (the directory is the
 * system property {@code powsybl.bench.fixtures}, by default the {@code scratchpad/fixtures} folder of the
 * workspace, never {@code /tmp} and never a repository) and reused while the byte sizes recorded in
 * {@code svedala-x<n>.manifest} next to it still match. Timesteps of a replicated day are replicated in memory
 * per timestep, outside every timer.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class ReplicatedSvedala {

    /** The five instance files of Svedala. */
    static final List<String> FILES = List.of(SvedalaTimestepFixtures.EQ, SvedalaTimestepFixtures.SSH,
            SvedalaTimestepFixtures.INHERITED.get(0), SvedalaTimestepFixtures.INHERITED.get(1),
            SvedalaTimestepFixtures.INHERITED.get(2));

    private static final Pattern UUID_TOKEN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern MODEL_ID = Pattern.compile("urn:uuid:([^\"<\\s]+)");
    private static final String HEADER_END = "</md:FullModel>";
    private static final String TAIL = "</rdf:RDF>";

    private ReplicatedSvedala() {
    }

    /** The renamed identifier of a model header of the {@code n}-fold replica. */
    static String modelId(int n, String id) {
        return "urn:uuid:" + UUID.nameUUIDFromBytes(("svedala-x" + n + ":model:" + id)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** The identifier of object {@code uuid} in copy {@code k}; copy 1 is the identity. */
    static String objectId(int k, String uuid) {
        return k == 1 ? uuid
                : UUID.nameUUIDFromBytes(("svedala-x:" + k + ":" + uuid).getBytes(StandardCharsets.UTF_8)).toString();
    }

    // ------------------------------------------------------------------ replication

    /**
     * Replicate every file of a data source in memory.
     *
     * @param in the files, for instance a timestep of {@link SvedalaTimestepFixtures}
     * @param n  how many copies; {@code 1} returns {@code in} itself
     * @return the replica
     */
    static ReadOnlyDataSource replicate(ReadOnlyDataSource in, int n) {
        if (n == 1) {
            return in;
        }
        MemDataSource out = new MemDataSource();
        for (String name : names(in)) {
            StringBuilder text = new StringBuilder();
            replicate(read(in, name), n, text);
            out.putData(name, text.toString().getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    /**
     * Replicate one file.
     *
     * @param xml the text of an instance file
     * @param n   how many copies
     * @param out where the replica goes
     */
    static void replicate(String xml, int n, Appendable out) {
        int headerEnd = xml.indexOf(HEADER_END);
        int headEnd = headerEnd >= 0 ? headerEnd + HEADER_END.length() : xml.indexOf('>', xml.indexOf("<rdf:RDF")) + 1;
        int tailStart = xml.lastIndexOf(TAIL);
        if (headEnd <= 0 || tailStart < headEnd) {
            throw new IllegalArgumentException("not an RDF/XML instance file");
        }
        try {
            Matcher header = MODEL_ID.matcher(xml.substring(0, headEnd));
            StringBuilder head = new StringBuilder();
            while (header.find()) {
                header.appendReplacement(head, Matcher.quoteReplacement(modelId(n, header.group(1))));
            }
            header.appendTail(head);
            out.append(head);
            String body = xml.substring(headEnd, tailStart);
            for (int k = 1; k <= n; k++) {
                if (k == 1) {
                    out.append(body);
                    continue;
                }
                Map<String, String> memo = new HashMap<>();
                int copy = k;
                Matcher token = UUID_TOKEN.matcher(body);
                StringBuilder replaced = new StringBuilder(body.length() + 64);
                while (token.find()) {
                    token.appendReplacement(replaced, memo.computeIfAbsent(token.group(), t -> objectId(copy, t)));
                }
                token.appendTail(replaced);
                out.append(replaced);
            }
            out.append(xml, tailStart, xml.length());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Replicate every file of a data source into a directory.
     *
     * @param in  the files
     * @param n   how many copies
     * @param dir where to write them (created)
     * @return the byte size of every file written
     */
    static Map<String, Long> replicateToDirectory(ReadOnlyDataSource in, int n, Path dir) {
        try {
            Files.createDirectories(dir);
            Map<String, Long> sizes = new LinkedHashMap<>();
            for (String name : names(in)) {
                Path file = dir.resolve(name);
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    replicate(read(in, name), n, writer);
                }
                sizes.put(name, Files.size(file));
            }
            return sizes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ the cache

    /** Where the replicated grids are kept: {@code powsybl.bench.fixtures}, else the workspace scratchpad. */
    static Path fixturesRoot() {
        String configured = System.getProperty("powsybl.bench.fixtures");
        return configured != null ? Path.of(configured)
                : Path.of("").toAbsolutePath().resolve("../../../scratchpad/fixtures").normalize();
    }

    /**
     * The {@code n}-fold replica of the unmodified Svedala model, generated on first use and cached on disk.
     *
     * @param n how many copies; {@code 1} is the catalogue model itself
     * @return the five files
     */
    static synchronized ReadOnlyDataSource cached(int n) {
        if (n == 1) {
            return Cgmes3Catalog.svedala().dataSource();
        }
        Path dir = fixturesRoot().resolve("svedala-x" + n);
        Path manifest = fixturesRoot().resolve("svedala-x" + n + ".manifest");
        if (!valid(dir, manifest)) {
            long start = System.nanoTime();
            Map<String, Long> sizes = replicateToDirectory(Cgmes3Catalog.svedala().dataSource(), n, dir);
            Properties p = new Properties();
            sizes.forEach((name, size) -> p.setProperty(name, Long.toString(size)));
            p.setProperty("generation.ms", Long.toString((System.nanoTime() - start) / 1_000_000));
            try (OutputStream out = Files.newOutputStream(manifest)) {
                p.store(out, "ReplicatedSvedala x" + n);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return DataSource.fromPath(dir);
    }

    /** How long the cached replica took to generate, from its manifest, or {@code -1}. */
    static long generationMillis(int n) {
        Properties p = manifest(fixturesRoot().resolve("svedala-x" + n + ".manifest"));
        return p == null ? -1 : Long.parseLong(p.getProperty("generation.ms", "-1"));
    }

    private static boolean valid(Path dir, Path manifest) {
        Properties p = manifest(manifest);
        if (p == null) {
            return false;
        }
        try {
            for (String name : FILES) {
                Path file = dir.resolve(name);
                if (!Files.exists(file) || !Long.toString(Files.size(file)).equals(p.getProperty(name))) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Properties manifest(Path manifest) {
        if (!Files.exists(manifest)) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(manifest)) {
            p.load(in);
            return p;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The anchor of a replicated day: the cached replica with the scenario time of its steady state rewritten.
     *
     * @param n       how many copies
     * @param instant the canonical scenario time, for instance {@code 2020-12-02T00:00:00Z}
     * @return the five files, in memory
     */
    static ReadOnlyDataSource anchor(int n, String instant) {
        if (n == 1) {
            return SvedalaTimestepFixtures.anchor(instant);
        }
        ReadOnlyDataSource replica = cached(n);
        MemDataSource out = new MemDataSource();
        for (String name : FILES) {
            String text = read(replica, name);
            if (name.equals(SvedalaTimestepFixtures.SSH)) {
                int headEnd = text.indexOf(HEADER_END);
                text = text.substring(0, headEnd).replaceAll(
                        "(<md:Model.scenarioTime>)[^<]*(</md:Model.scenarioTime>)",
                        "$1" + Matcher.quoteReplacement(instant) + "$2") + text.substring(headEnd);
            }
            out.putData(name, text.getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    // ------------------------------------------------------------------ small helpers

    private static List<String> names(ReadOnlyDataSource in) {
        try {
            List<String> names = new ArrayList<>();
            for (String name : in.listNames(".*")) {
                if (name.endsWith(".xml")) {
                    names.add(name);
                }
            }
            names.sort(String::compareTo);
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
}
