/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities;
import com.powsybl.cgmes.conversion.diff.FastRouteCapabilities.FamilySpec;
import com.powsybl.cgmes.conversion.mapping.Block;
import com.powsybl.cgmes.conversion.mapping.LoadRows;
import com.powsybl.cgmes.conversion.mapping.PlainFamily;
import com.powsybl.cgmes.conversion.mapping.PlainRow;
import com.powsybl.cgmes.conversion.mapping.Quantity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The body of the documentation page of the mapping ({@code docs/grid_exchange_formats/cgmes/mapping.md}) is generated
 * from the mapping itself, and this test fails when the page is stale.
 *
 * <p>What is generated, between the two markers of the page: the rows of the plain families with their quantities; per
 * hand-written family the keys it declares for the dispatch of the change export (its non-private {@code *KEYS} fields,
 * the suffixes of a tap changer's keys) and its blocks (its public {@link Block} constants); the families of the in-place
 * import of a difference ({@link FastRouteCapabilities#table()}); and the refusals with rule, scope and remedy
 * ({@link Refusal}). The families are enumerated here, their fields by reflection, so that a new key set or block appears
 * on the page without touching this class. Sets are written sorted, lists in their order.</p>
 *
 * <p>Regenerate the page with
 * {@code mvn -pl cgmes/cgmes-conversion test -Dtest=MappingPageTest -Dpowsybl.docs.regenerate=true}.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class MappingPageTest {

    static final Path PAGE = Path.of("../../docs/grid_exchange_formats/cgmes/mapping.md");
    static final String BEGIN = "<!-- BEGIN GENERATED MAPPING: regenerate with MappingPageTest, do not edit by hand -->";
    static final String END = "<!-- END GENERATED MAPPING -->";

    /** The hand-written families, in the order of the full SSH export, with the class declaring their keys and blocks. */
    private static final List<Class<?>> FAMILIES = List.of(SwitchAndTerminalFamily.class, LoadFamily.class,
            MachineFamily.class, TapChangerAndShuntFamily.class, RegulatingControlFamily.class, HvdcFamily.class,
            ControlAreaFamily.class, LimitFamily.class);

    @Test
    void thePageIsTheMapping() throws IOException {
        String page = Files.readString(PAGE, StandardCharsets.UTF_8);
        int begin = page.indexOf(BEGIN);
        int end = page.indexOf(END);
        assertTrue(begin >= 0 && end > begin, "the page has no generated section: " + PAGE.toAbsolutePath());
        String expected = page.substring(0, begin + BEGIN.length()) + "\n" + body() + page.substring(end);
        if (Boolean.getBoolean("powsybl.docs.regenerate")) {
            Files.writeString(PAGE, expected, StandardCharsets.UTF_8);
        }
        assertEquals(expected, Files.readString(PAGE, StandardCharsets.UTF_8), "the mapping page is stale: regenerate it with -Dpowsybl.docs.regenerate=true");
    }

    static String body() {
        StringBuilder page = new StringBuilder();
        page.append("\n## Plain families\n\nA plain family is data: each row is one CGMES property and the IIDM attribute it is."
                + " The export writes the rows, the importer's update sets the rows it reads, and the in-place import describes"
                + " a load with the same rows.\n");
        staticValues(LoadRows.class, PlainFamily.class).forEach(field -> plainFamily(page, field.getKey(), (PlainFamily<?>) field.getValue()));
        page.append("\n### Quantities\n\n| quantity | unit multiplier | sign | sign of the regulating terminal | enumeration |\n"
                + "| --- | --- | --- | --- | --- |\n");
        for (Quantity quantity : Quantity.values()) {
            page.append(row(code(quantity.name()), orDash(quantity.multiplier()), sign(quantity.encode(1, 1)),
                    quantity.encode(1, -1) == quantity.encode(1, 1) ? "no" : "yes", orDash(quantity.enumeration())));
        }
        page.append("\n## Hand-written families\n\nA hand-written family states the rules of its equipment in code. Its keys are"
                + " the IIDM attributes a recorded change is dispatched to it by; its blocks are what the CGMES update reads"
                + " together: every required property has to be stated for any of them to be read.\n");
        FAMILIES.forEach(family -> handWrittenFamily(page, family));
        page.append("\n## In-place import of a difference\n\nThe families of `FastRouteCapabilities`, one per block above."
                + " A family without update query is applied with IIDM setters; the variant safety says whether applying it"
                + " stays inside one network variant.\n\n| family | CIM classes | update query | profiles | variant safety |\n"
                + "| --- | --- | --- | --- | --- |\n");
        for (FamilySpec spec : FastRouteCapabilities.table()) {
            page.append(row(code(spec.family().name()), codes(spec.rdfTypes()), spec.updateQuery() == null ? "–" : code(spec.updateQuery()),
                    spec.subsets().stream().map(Enum::name).sorted().collect(Collectors.joining(", ")),
                    spec.variantSafety().name()));
        }
        page.append("\n(cgmes-mapping-refusals)=\n## Refusals\n\nA change the mapping cannot state is refused with its cause and a remedy. A refusal that"
                + " protects a receiver of changes (partial SSH, difference model, database) is not honoured by a full steady"
                + " state hypothesis, which states the whole state.\n\n| rule | refusal | honoured by | remedy |\n"
                + "| --- | --- | --- | --- |\n");
        for (Refusal refusal : Refusal.values()) {
            page.append(row(code(refusal.getRule()), code(refusal.name()),
                    refusal.isChangesOnly() ? "receivers of changes" : "every export", refusal.getRemedy()));
        }
        page.append("\nAttributes refused by name, because they are equipment data or have no CGMES property: ")
                .append(codes(RegulationKeyRefusals.EQUIPMENT_KEYS)).append(".\n\n");
        return page.toString();
    }

    private static void plainFamily(StringBuilder page, String name, PlainFamily<?> family) {
        page.append("\n### ").append(code(name)).append("\n\nUpdate query ").append(code(family.updateQuery()))
                .append(", CIM classes ").append(codes(family.cimClasses())).append(".\n\n")
                .append("| CGMES property | query variable | IIDM attribute | quantity | read by the import |\n")
                .append("| --- | --- | --- | --- | --- |\n");
        for (PlainRow<?> row : family.rows()) {
            page.append(row(code(row.property()), code(row.variable()), row.key() == null ? "– (a constant)" : code(row.key()),
                    code(row.quantity().name()), row.setter() == null ? "no" : "yes"));
        }
    }

    private static void handWrittenFamily(StringBuilder page, Class<?> family) {
        page.append("\n### ").append(code(family.getSimpleName())).append("\n\n");
        // the dispatch matches the key of a switch itself, and a load's keys are the keys of its rows
        List<String> keys = family == SwitchAndTerminalFamily.class ? List.of("switch, DC switch: " + code(CgmesChangeTranslator.OPEN))
                : family == LoadFamily.class ? List.of("load: " + codes(LoadRows.keys()) + ", the keys of the plain rows")
                : staticFields(family, Collection.class).stream().filter(MappingPageTest::isKeys)
                        .map(field -> words(family, field.getName()) + ": " + codes((Collection<?>) value(field))).toList();
        page.append("Keys:\n\n");
        keys.forEach(line -> page.append("* ").append(line).append("\n"));
        var blocks = staticValues(family, Block.class);
        if (blocks.isEmpty()) {
            return;
        }
        page.append("\n| block | update query | CIM classes | required | optional |\n| --- | --- | --- | --- | --- |\n");
        for (var block : blocks) {
            Block value = (Block) block.getValue();
            page.append(row(code(block.getKey()), value.updateQuery() == null ? "– (IIDM setters)" : code(value.updateQuery()),
                    codes(value.cimClasses()), codes(value.required()), orDash(value.optional().isEmpty() ? null : codes(value.optional()))));
        }
        if (family == HvdcFamily.class) {
            page.append("\nThe query ").append(code(HvdcFamily.CS_CONVERTER.updateQuery()))
                    .append(" reads the setpoints of a converter with its block, and of both converters of a line: ")
                    .append(codes(HvdcFamily.SETPOINTS)).append(".\n");
        }
    }

    /** A declared key set: a non-private {@code *KEYS} field, or the suffixes of the keys of a tap changer. */
    private static boolean isKeys(Field field) {
        return field.getName().endsWith("_SUFFIXES") || field.getName().endsWith("KEYS") && !Modifier.isPrivate(field.getModifiers());
    }

    private static List<Field> staticFields(Class<?> type, Class<?> valueType) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()) && valueType.isAssignableFrom(field.getType()))
                .toList();
    }

    /** The static fields of the given type, in their declaration order, as name and value. */
    private static List<Map.Entry<String, Object>> staticValues(Class<?> type, Class<?> valueType) {
        return staticFields(type, valueType).stream().map(field -> Map.entry(field.getName(), value(field))).toList();
    }

    private static Object value(Field field) {
        try {
            field.setAccessible(true);
            return field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code GENERATOR_KEYS} is "generator", {@code KEYS} of {@code ControlAreaFamily} "control area". */
    private static String words(Class<?> family, String fieldName) {
        String suffixes = "_SUFFIXES";
        String words = fieldName.endsWith(suffixes) ? fieldName.substring(0, fieldName.length() - suffixes.length())
                : fieldName.replaceFirst("_?KEYS$", "");
        String text = words.isEmpty() ? family.getSimpleName().replace("Family", "").replaceAll("(?<=[a-z])(?=[A-Z])", " ").toLowerCase(Locale.ROOT)
                : words.toLowerCase(Locale.ROOT).replace('_', ' ');
        return fieldName.endsWith(suffixes) ? text + ", after the name a change gives it" : text;
    }

    /** The values as code, a set sorted; a key of a regulation names the deprecated setters that echo it. */
    private static String codes(Collection<?> values) {
        Stream<String> texts = values.stream().map(value -> value instanceof RegulatingControlFamily.Key key
                ? code(key.canonical()) + (key.echoes().isEmpty() ? "" : " (echoed by " + codes(key.echoes()) + ")")
                : code(value.toString()));
        return (values instanceof Set<?> ? texts.sorted() : texts).collect(Collectors.joining(", "));
    }

    private static String code(String text) {
        return "`" + text + "`";
    }

    private static String orDash(String text) {
        return text == null ? "–" : text;
    }

    private static String sign(double value) {
        return value > 0 ? "+" : "−";
    }

    private static String row(String... cells) {
        return Arrays.stream(cells).collect(Collectors.joining(" | ", "| ", " |\n"));
    }
}
