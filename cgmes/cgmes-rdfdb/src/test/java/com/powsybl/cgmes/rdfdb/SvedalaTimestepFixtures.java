/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.conformity.Cgmes3Catalog;
import com.powsybl.commons.datasource.MemDataSource;
import com.powsybl.commons.datasource.ReadOnlyDataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A day of quarter-hourly CGMES exports of the <em>largest</em> conformity model, made by editing Svedala.
 *
 * <p>{@link TimestepFixtures} does the same for the MicroGrid base case, which is 1.9 MB and holds a handful of
 * objects. The question this fixture exists for &mdash; what it costs to diff ninety-five timesteps of a real
 * model against an anchor and write them &mdash; cannot be answered on a model that small, so the same idea is
 * applied to the biggest CGMES set the repertoire carries: Svedala (CGMES 3, 14 MB over five instance files,
 * 8 397 equipment objects, file names with spaces).</p>
 *
 * <p>The edits are textual, for the same reason {@link TimestepFixtures} gives: re-exporting the model through the
 * CGMES exporter would differ from the base in a hundred incidental ways, and a benchmark of the difference
 * calculator would become a benchmark of the exporter. Here the text editing is done on <em>parsed blocks</em>
 * rather than with a plain regular expression, because a structural timestep has to drop an object together with
 * everything that points at it, and that is a graph walk.</p>
 *
 * <h2>The three shapes of a timestep</h2>
 * <ul>
 *   <li>{@link Shape#THIN} &mdash; five {@code EnergyConsumer.p} values move. The fixed cost of an ingestion.</li>
 *   <li>{@link Shape#RICH} &mdash; every continuous set point of the steady state hypothesis is scaled by the
 *       timestep's factor: all {@code EnergyConsumer.p}/{@code .q}, all {@code RotatingMachine.p}/{@code .q} and
 *       all {@code RegulatingControl.targetValue}. That is 322 values, which is <em>everything Svedala's SSH has
 *       to offer</em> short of flipping switches; see the report for why that is not "thousands".</li>
 *   <li>{@link Shape#STRUCTURAL} &mdash; the European exchange case: the equipment model of the timestep
 *       <em>omits</em> a share of the objects the anchor holds (de-energised or in maintenance) and sometimes
 *       carries one the anchor does not. Set points move as in {@code THIN} on top.</li>
 * </ul>
 *
 * <p>A timestep of the first two shapes ships the steady state file alone, which is what a quarter-hourly
 * schedule is; a structural timestep ships equipment and steady state, because that is what it changes. The other
 * profiles are inherited from the anchor either way &mdash; {@code putAsDiff} compares EQ and SSH and nothing
 * else &mdash; and {@link #fullFileSet} exists to measure what shipping them anyway would cost.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class SvedalaTimestepFixtures {

    /** The steady state hypothesis of the fixture. */
    static final String SSH = "20201202T1843Z_1D_Svedala Area_SSH_001.xml";

    /** The equipment model of the fixture. */
    static final String EQ = "20201202T1843Z_1D_Svedala Area_EQ_001.xml";

    /** The profiles a timestep never touches and always inherits. */
    static final List<String> INHERITED = List.of(
            "20201202T1843Z_1D_Svedala Area_DL_001.xml",
            "20201202T1843Z_1D_Svedala Area_SV_001.xml",
            "20201202T1843Z_1D_Svedala Area_TP_001.xml");

    /** How much of the model one timestep moves. */
    enum Shape {
        /** Five load set points: the fixed cost of an ingestion. */
        THIN,
        /** Every continuous set point of the steady state hypothesis, scaled by the timestep's factor. */
        RICH,
        /** Objects omitted from the equipment model, objects added to it, and five load set points. */
        STRUCTURAL
    }

    /** The properties a {@link Shape#RICH} timestep scales, in the order they are rewritten. */
    private static final List<String> SCALED = List.of(
            "EnergyConsumer.p", "EnergyConsumer.q",
            "RotatingMachine.p", "RotatingMachine.q",
            "RegulatingControl.targetValue");

    /** How many load set points the thin part of every timestep moves. */
    private static final int THIN_LOADS = 5;

    /** How many equipment objects a structural timestep omits, as a share of the 90 {@code ACLineSegment}s. */
    private static final int OMITTED_LINES = 2;

    /** One timestep in every so many also carries a line the anchor does not have. */
    private static final int ADD_EVERY = 8;

    private static final Pattern ID = Pattern.compile("rdf:(?:ID|about)=\"#?_?([^\"]*)\"");
    private static final Pattern REF = Pattern.compile("rdf:resource=\"#_([^\"]*)\"");

    private static Map<String, String> files;
    private static Document eqDocument;
    private static Document sshDocument;
    private static List<String> lineIds;

    private SvedalaTimestepFixtures() {
    }

    // ------------------------------------------------------------------ what a timestep is

    /**
     * The files of one timestep, and what makes them structurally different from the anchor.
     *
     * @param dataSource      the files
     * @param omittedObjects  how many equipment objects of the anchor the timestep does not carry
     * @param addedObjects    how many objects it carries that the anchor does not
     * @param omittedLines    the {@code ACLineSegment} identifiers that were dropped, with their dependants
     */
    record TimestepFiles(ReadOnlyDataSource dataSource, int omittedObjects, int addedObjects,
                         List<String> omittedLines) {
    }

    /**
     * The anchor of the day: the unmodified model, claiming the given moment.
     *
     * <p>Only the scenario time of the steady state file is rewritten, because that is what the catalogue reads
     * the base timestep and the label offset off. Writing it as midnight UTC is what makes the timesteps of the
     * day the labels {@code "00:15"} … {@code "23:45"}.</p>
     *
     * @param instant the canonical scenario time, for instance {@code 2020-12-02T00:00:00Z}
     * @return a data source holding all five instance files
     */
    static ReadOnlyDataSource anchor(String instant) {
        MemDataSource source = new MemDataSource();
        put(source, SSH, rewriteScenarioTime(file(SSH), instant));
        put(source, EQ, file(EQ));
        INHERITED.forEach(name -> put(source, name, file(name)));
        return source;
    }

    /**
     * One timestep of the day.
     *
     * @param shape   what the timestep moves
     * @param index   the quarter hour, {@code 1} for 00:15 and {@code 95} for 23:45
     * @param instant the canonical scenario time the files claim
     * @return the files and what is structurally different about them
     */
    static TimestepFiles timestep(Shape shape, int index, String instant) {
        String suffix = shape.name().toLowerCase(java.util.Locale.ROOT) + "-" + index;
        if (shape != Shape.STRUCTURAL) {
            MemDataSource source = new MemDataSource();
            put(source, SSH, steadyState(shape, index, instant, suffix, Set.of(), List.of()));
            return new TimestepFiles(source, 0, 0, List.of());
        }
        List<String> dropped = omittedLines(index);
        Set<String> removed = removalClosure(dropped);
        List<Copy> added = additions(index, removed);
        MemDataSource source = new MemDataSource();
        put(source, EQ, equipment(instant, suffix, removed, added));
        put(source, SSH, steadyState(shape, index, instant, suffix, removed, added));
        return new TimestepFiles(source, removed.size(), added.size(), dropped);
    }

    /**
     * The same timestep, with all five profiles shipped rather than the ones that change.
     *
     * <p>What it is for: {@code putAsDiff} compares the equipment model and the steady state hypothesis and
     * inherits the rest, but it still has to <em>parse</em> whatever the data source carries. This is how much
     * shipping the whole export costs over shipping what changed.</p>
     *
     * @param shape   what the timestep moves
     * @param index   the quarter hour
     * @param instant the canonical scenario time
     * @return the files
     */
    static ReadOnlyDataSource fullFileSet(Shape shape, int index, String instant) {
        TimestepFiles files1 = timestep(shape, index, instant);
        MemDataSource source = new MemDataSource();
        put(source, SSH, read(files1.dataSource(), SSH));
        put(source, EQ, contains(files1.dataSource(), EQ) ? read(files1.dataSource(), EQ) : file(EQ));
        INHERITED.forEach(name -> put(source, name, file(name)));
        return source;
    }

    /**
     * A timestep whose equipment model drifted: one line renamed, nothing else.
     *
     * <p>"EQ drift" in the sense of {@link TimestepFixtures#eqDrift}: a name is the cheapest change that no
     * in-place update can apply, so it is what makes the ingested difference take the slow route without changing
     * the shape of the model at all.</p>
     *
     * @param index   the quarter hour
     * @param instant the canonical scenario time
     * @return the files
     */
    static ReadOnlyDataSource eqDrift(int index, String instant) {
        String suffix = "drift-" + index;
        MemDataSource source = new MemDataSource();
        put(source, EQ, renameFirstLine(rewriteHeader(file(EQ), "urn:uuid:svedala-eq-" + suffix, instant)));
        put(source, SSH, steadyState(Shape.THIN, index, instant, suffix, Set.of(), List.of()));
        return source;
    }

    /**
     * @return how many {@code ACLineSegment}s the equipment model of the anchor holds
     */
    static int lineCount() {
        return lines().size();
    }

    /**
     * @return how many top-level objects the equipment model of the anchor holds
     */
    static int equipmentObjectCount() {
        return eq().blocks.size();
    }

    /**
     * @return how many top-level objects the steady state hypothesis of the anchor holds
     */
    static int steadyStateObjectCount() {
        return ssh().blocks.size();
    }

    /**
     * @return how many set points a {@link Shape#RICH} timestep rewrites
     */
    static int richSetPointCount() {
        String text = file(SSH);
        int total = 0;
        for (String property : SCALED) {
            total += count(text, property);
        }
        return total;
    }

    /**
     * The load factor of a quarter hour: a daily curve between 0.75 and 1.20 that never passes through one.
     *
     * @param index the quarter hour
     * @return the factor
     */
    static double factor(int index) {
        return 0.75 + 0.45 * (0.5 + 0.5 * Math.sin(2 * Math.PI * (index - 24) / 96.0));
    }

    // ------------------------------------------------------------------ the steady state file

    private static String steadyState(Shape shape, int index, String instant, String suffix,
                                      Set<String> removed, List<Copy> added) {
        Document document = ssh();
        StringBuilder out = new StringBuilder(2 << 20);
        document.head.forEach(line -> out.append(line).append('\n'));
        for (int i = 0; i < document.blocks.size(); i++) {
            Block block = document.blocks.get(i);
            // An object the equipment model no longer has, and anything in this file pointing at one
            if (removed.contains(block.id) || document.references.get(i).stream().anyMatch(removed::contains)) {
                continue;
            }
            out.append(block.text).append('\n');
        }
        for (Copy copy : added) {
            String text = sshOf(copy.originalId);
            if (text != null) {
                out.append(substitute(text, copy.mapping)).append('\n');
            }
        }
        document.tail.forEach(line -> out.append(line).append('\n'));
        String text = rewriteHeader(out.toString(), "urn:uuid:svedala-ssh-" + suffix, instant);
        if (shape == Shape.RICH) {
            double factor = factor(index);
            for (String property : SCALED) {
                text = scaleAll(text, property, factor);
            }
        }
        // Whatever the shape, a handful of load set points move by an offset that no other timestep uses: it is
        // what guarantees that every timestep really differs from the anchor, rather than relying on a factor
        return offsetFirst(text, "EnergyConsumer.p", THIN_LOADS, 7.0 + index);
    }

    private static String scaleAll(String xml, String property, double factor) {
        return rewriteValues(xml, property, Integer.MAX_VALUE, value -> value * factor);
    }

    private static String offsetFirst(String xml, String property, int count, double offset) {
        return rewriteValues(xml, property, count, value -> value + offset);
    }

    private static String rewriteValues(String xml, String property, int limit, DoubleEdit edit) {
        Pattern pattern = Pattern.compile("(<cim:" + Pattern.quote(property) + ">)(-?[0-9.eE+]+)(</cim:"
                + Pattern.quote(property) + ">)");
        Matcher matcher = pattern.matcher(xml);
        StringBuilder out = new StringBuilder(xml.length() + 4096);
        int done = 0;
        while (matcher.find()) {
            String replacement;
            if (done < limit) {
                replacement = matcher.group(1) + format(edit.apply(Double.parseDouble(matcher.group(2))))
                        + matcher.group(3);
                done++;
            } else {
                replacement = matcher.group();
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        if (limit != Integer.MAX_VALUE && done < limit) {
            throw new IllegalStateException("the fixture holds " + done + " " + property + " values, not " + limit);
        }
        return out.toString();
    }

    private interface DoubleEdit {
        double apply(double value);
    }

    /** A number a CGMES file can carry: plain notation, four decimals, no trailing zeroes. */
    private static String format(double value) {
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.scale() < 0 ? rounded.setScale(0, RoundingMode.UNNECESSARY).toPlainString()
                : rounded.toPlainString();
    }

    // ------------------------------------------------------------------ the equipment file

    private static String equipment(String instant, String suffix, Set<String> removed, List<Copy> added) {
        Document document = eq();
        StringBuilder out = new StringBuilder(6 << 20);
        document.head.forEach(line -> out.append(line).append('\n'));
        for (Block block : document.blocks) {
            if (removed.contains(block.id)) {
                continue;
            }
            out.append(block.text).append('\n');
        }
        for (Copy copy : added) {
            out.append(substitute(copy.text, copy.mapping)).append('\n');
        }
        document.tail.forEach(line -> out.append(line).append('\n'));
        return rewriteHeader(out.toString(), "urn:uuid:svedala-eq-" + suffix, instant);
    }

    /**
     * The {@code ACLineSegment}s a timestep omits: a rolling window over the ninety the model holds.
     *
     * <p>A window rather than a random draw, so that every line is out for a few quarter hours and back
     * afterwards &mdash; which is what a maintenance schedule looks like from the outside, and which means the
     * day as a whole both removes and re-adds objects. Every single timestep is nonetheless a difference against
     * the <em>anchor</em>, because a timestep root pins to the base chain, so a removal is a removal in every one
     * of them.</p>
     */
    private static List<String> omittedLines(int index) {
        List<String> all = lines();
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < OMITTED_LINES; i++) {
            chosen.add(all.get((OMITTED_LINES * index + i) % all.size()));
        }
        return chosen;
    }

    /**
     * Everything that has to go when those lines go: the lines, their terminals, the limit sets on those
     * terminals and the limits in those sets, to a fixed point.
     */
    private static Set<String> removalClosure(Collection<String> seeds) {
        Document document = eq();
        Set<String> removed = new LinkedHashSet<>(seeds);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < document.blocks.size(); i++) {
                Block block = document.blocks.get(i);
                if (removed.contains(block.id)) {
                    continue;
                }
                for (String reference : document.references.get(i)) {
                    if (removed.contains(reference)) {
                        removed.add(block.id);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return removed;
    }

    /** One object the timestep carries and the anchor does not: a copy under fresh identifiers. */
    private record Copy(String originalId, String text, Map<String, String> mapping) {
    }

    /**
     * A line the anchor does not have, in one timestep out of {@link #ADD_EVERY}.
     *
     * <p>A copy of an existing line and of everything that hangs off it, under identifiers derived from the
     * timestep, so the result is a valid model rather than a dangling reference: the copy keeps the container,
     * the base voltage and the connectivity nodes of its original, which all still exist.</p>
     */
    private static List<Copy> additions(int index, Set<String> removed) {
        if (index % ADD_EVERY != 0) {
            return List.of();
        }
        List<String> all = lines();
        String source = all.get((7 * index + 3) % all.size());
        if (removed.contains(source)) {
            source = all.get((7 * index + 4) % all.size());
        }
        Set<String> group = removalClosure(List.of(source));
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String id : group) {
            mapping.put(id, UUID.nameUUIDFromBytes(("svedala-added-" + index + "-" + id)
                    .getBytes(StandardCharsets.UTF_8)).toString());
        }
        List<Copy> copies = new ArrayList<>();
        for (Block block : eq().blocks) {
            if (group.contains(block.id)) {
                copies.add(new Copy(block.id, block.text, mapping));
            }
        }
        return copies;
    }

    private static String substitute(String text, Map<String, String> mapping) {
        String result = text;
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static String renameFirstLine(String eq) {
        int line = eq.indexOf("<cim:ACLineSegment rdf:ID=");
        int name = eq.indexOf("<cim:IdentifiedObject.name>", line);
        int end = eq.indexOf("</cim:IdentifiedObject.name>", name);
        if (line < 0 || name < 0 || end < 0) {
            throw new IllegalStateException("the equipment fixture holds no named ACLineSegment");
        }
        return eq.substring(0, name) + "<cim:IdentifiedObject.name>renamed-by-the-day" + eq.substring(end);
    }

    // ------------------------------------------------------------------ headers

    private static String rewriteHeader(String text, String modelId, String instant) {
        return rewriteScenarioTime(text.replaceFirst("rdf:about=\"urn:uuid:[^\"]*\"",
                Matcher.quoteReplacement("rdf:about=\"" + modelId + "\"")), instant);
    }

    private static String rewriteScenarioTime(String text, String instant) {
        return text.replaceAll("(<md:Model.scenarioTime>)[^<]*(</md:Model.scenarioTime>)",
                "$1" + Matcher.quoteReplacement(instant) + "$2");
    }

    // ------------------------------------------------------------------ the block model

    /** One top-level element of an instance file, with its identifier and its whole text. */
    private record Block(String id, String text) {
    }

    /** An instance file as its header, its top-level elements and its closing tag. */
    private record Document(List<String> head, List<Block> blocks, List<List<String>> references,
                            List<String> tail, Map<String, String> byId) {
    }

    private static Document parse(String xml) {
        String[] lines = xml.split("\n", -1);
        List<String> head = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        List<List<String>> references = new ArrayList<>();
        List<String> tail = new ArrayList<>();
        Map<String, String> byId = new LinkedHashMap<>();
        int i = 0;
        while (i < lines.length && !isBlockStart(lines[i])) {
            head.add(lines[i]);
            i++;
        }
        while (i < lines.length && isBlockStart(lines[i])) {
            int start = i;
            i++;
            while (i < lines.length && !isBlockEnd(lines[i])) {
                i++;
            }
            StringBuilder text = new StringBuilder();
            for (int j = start; j <= Math.min(i, lines.length - 1); j++) {
                text.append(lines[j]);
                if (j < i) {
                    text.append('\n');
                }
            }
            String block = text.toString();
            String id = idOf(lines[start]);
            blocks.add(new Block(id, block));
            references.add(referencesOf(block));
            byId.put(id, block);
            i++;
        }
        while (i < lines.length) {
            tail.add(lines[i]);
            i++;
        }
        return new Document(head, blocks, references, tail, byId);
    }

    private static boolean isBlockStart(String line) {
        return line.startsWith("  <") && !line.startsWith("  </");
    }

    private static boolean isBlockEnd(String line) {
        return line.startsWith("  </");
    }

    private static String idOf(String line) {
        Matcher matcher = ID.matcher(line);
        return matcher.find() ? matcher.group(1) : line.trim();
    }

    private static List<String> referencesOf(String text) {
        List<String> references = new ArrayList<>();
        Matcher matcher = REF.matcher(text);
        while (matcher.find()) {
            references.add(matcher.group(1));
        }
        return references;
    }

    // ------------------------------------------------------------------ reading the fixture, once

    private static synchronized Map<String, String> files() {
        if (files == null) {
            Map<String, String> read = new LinkedHashMap<>();
            ReadOnlyDataSource source = Cgmes3Catalog.svedala().dataSource();
            read.put(SSH, read(source, SSH));
            read.put(EQ, read(source, EQ));
            INHERITED.forEach(name -> read.put(name, read(source, name)));
            files = read;
        }
        return files;
    }

    private static String file(String name) {
        return files().get(name);
    }

    private static synchronized Document eq() {
        if (eqDocument == null) {
            eqDocument = parse(file(EQ));
        }
        return eqDocument;
    }

    private static synchronized Document ssh() {
        if (sshDocument == null) {
            sshDocument = parse(file(SSH));
        }
        return sshDocument;
    }

    private static String sshOf(String id) {
        return ssh().byId.get(id);
    }

    private static synchronized List<String> lines() {
        if (lineIds == null) {
            List<String> found = new ArrayList<>();
            for (Block block : eq().blocks) {
                if (block.text.startsWith("  <cim:ACLineSegment ")) {
                    found.add(block.id);
                }
            }
            lineIds = List.copyOf(found);
        }
        return lineIds;
    }

    private static int count(String text, String property) {
        int total = 0;
        int from = 0;
        String open = "<cim:" + property + ">";
        while (true) {
            int at = text.indexOf(open, from);
            if (at < 0) {
                return total;
            }
            total++;
            from = at + open.length();
        }
    }

    private static boolean contains(ReadOnlyDataSource source, String name) {
        try {
            return source.exists(name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(ReadOnlyDataSource source, String name) {
        try (InputStream in = source.newInputStream(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void put(MemDataSource source, String name, String content) {
        try (var out = source.newOutputStream(name, false)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
