/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.commons.test.ComparisonUtils;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.serde.ExportOptions;
import com.powsybl.iidm.serde.NetworkSerDe;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Comparing two networks that were built from the same CGMES data in two different ways.
 *
 * <p>The claim of the split loading is that a network built out of a database is the network the files would have
 * produced. Writing both to sorted XIIDM and comparing the documents is the strongest cheap way to check it: it
 * covers every identifiable, every attribute and every extension that has a serialiser. {@link RdfDbProvenance}
 * deliberately has no serialiser, so it appears in neither document and does not disturb the comparison.</p>
 *
 * <h2>What a CGMES conversion does not promise, and why it has to be canonicalised away</h2>
 * <p>A CGMES conversion walks over the results of SPARQL queries, and the <em>order</em> of those results is not a
 * property of the data. An RDF4J memory store filled by the RDF/XML parser returns them in one order; the same
 * store filled from a database returns them in another, because the database handed the statements over in the
 * order of its own indexes. Neither order is more correct than the other, and a file import is not reproducible
 * across orders either. Four things in the output follow that order rather than the data, and they are
 * canonicalised here before the documents are compared:</p>
 * <ol>
 *   <li><strong>The terminal a regulating control points at.</strong> A CGMES control names a CGMES terminal, and
 *       the conversion picks one IIDM terminal of the topological node that terminal belongs to. Since
 *       {@code RegulatingTerminalMapper} makes that choice deterministically (a connected terminal first, then
 *       the lowest identifier) the two runs agree on the equipment as well, but the assertion is kept at the
 *       level the regulation actually means: the <em>bus</em> the terminal sits on, named after the equipment
 *       connected to it. The same applies to the {@code terminalRef} of a tap changer.</li>
 *   <li><strong>Node numbers of a node-breaker voltage level.</strong> Numbers are handed out as connectivity
 *       nodes are met, so the same topology comes out renumbered. Each node is relabelled here by <em>what is
 *       attached to it</em> &mdash; the sorted set of {@code <equipment id>#<terminal>} tokens &mdash; which is
 *       invariant under renumbering and still tells two different topologies apart.</li>
 *   <li><strong>The identifier of the network.</strong> Taken from one of the model headers, and a CGM has
 *       several. Blanked on the network element, and the network-level extensions &mdash; which are named after
 *       the network and therefore sorted by a value that is gone &mdash; are moved to the front. The model
 *       headers themselves keep their real identifiers and are compared.</li>
 *   <li><strong>{@code caseDate} and {@code forecastDistance}.</strong> Derived from one row of the
 *       {@code modelDates} query, and a model whose instance files carry different creation times has several
 *       rows. Blanked &mdash; the headers themselves live in the CGMES metadata-models extension and
 *       <em>are</em> compared, element by element.</li>
 *   <li><strong>Sets written as sequences.</strong> Comma-separated identifier lists in properties such as
 *       {@code CGMES.busbarSectionTerminals} and in the {@code nodes} of a calculated bus; repeated text-only
 *       elements such as {@code dependentOnModel}; the {@code internalConnection} elements of a node-breaker
 *       topology, which have no identifier at all; the {@code areaBoundary} elements of a control area. All
 *       sorted by content.</li>
 * </ol>
 * <p>On top of that, floating point values are rounded to twelve significant digits, because a sum over a set
 * &mdash; the susceptance of a shunt compensator over its sections, for instance &mdash; depends on the order the
 * terms are added in at the sixteenth digit.</p>
 *
 * <p>Everything else is compared strictly, attribute by attribute.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class Networks {

    private static final Pattern DECIMAL = Pattern.compile("-?\\d+\\.\\d+([eE][-+]?\\d+)?");
    private static final String NETWORK_ID_TOKEN = "@network@";
    private static final List<String> NODE_ATTRIBUTES = List.of("node", "node1", "node2", "node3");

    private Networks() {
    }

    static byte[] xiidm(Network network) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NetworkSerDe.write(network, new ExportOptions().setSorted(true), bytes);
        return bytes.toByteArray();
    }

    static String xiidmString(Network network) {
        return new String(xiidm(network), StandardCharsets.UTF_8);
    }

    static void assertSameNetwork(Network expected, Network actual) {
        assertSameNetwork(expected, actual, Set.of());
    }

    /**
     * Compare two networks, leaving out the named extensions.
     *
     * <p>Needed by the versioning tests: a network brought to a stored state by applying differences and the same
     * state materialised from the database hold the same grid, but they say different things about <em>which
     * CGMES models they are</em> &mdash; one has advanced its {@code cgmesMetadataModels} step by step, the other
     * was built at the target in one go. That difference is asserted on its own, by identifier, rather than being
     * allowed to make every comparison fail.</p>
     *
     * @param expected           the network the comparison is against
     * @param actual             the network to check
     * @param ignoredExtensions  the names of the extensions to leave out, as XIIDM writes them
     */
    static void assertSameNetwork(Network expected, Network actual, Set<String> ignoredExtensions) {
        ComparisonUtils.assertXmlEquals(
                new ByteArrayInputStream(canonicalXiidm(expected, ignoredExtensions, Set.of())),
                new ByteArrayInputStream(canonicalXiidm(actual, ignoredExtensions, Set.of())));
    }

    /**
     * Compare two networks that were brought to the same state by different routes, leaving out the values that
     * come from the state variables.
     *
     * <p>A network that is <em>updated in place</em> by a difference and the same state <em>converted from the
     * data</em> hold the same grid and the same steady state hypothesis, and they legitimately disagree about the
     * results that belonged to the <em>previous</em> hypothesis: the terminal flows and the solved tap position of
     * the equipment the difference touched. The CGMES update workflow clears them, because the hypothesis they
     * were computed for is gone; a conversion keeps whatever the state variables file says, which no difference
     * ever touched. Neither is wrong, both values are stale, and no difference model can carry the disagreement
     * away: the state variables profile is never diffed.</p>
     *
     * <p>The exclusion is therefore limited to the equipment the change actually touched. Everything else &mdash;
     * every other flow, every bus voltage, every attribute of every identifiable &mdash; is compared exactly, which
     * is what makes this an assertion that the two routes reach the same state rather than a licence to differ.</p>
     *
     * @param expected          the network the comparison is against
     * @param actual            the network to check
     * @param ignoredExtensions the names of the extensions to leave out
     * @param touchedIds        the identifiers of the equipment the differences under test changed; only their
     *                          state variable results are left out of the comparison
     */
    static void assertSameNetworkIgnoringStateVariables(Network expected, Network actual,
                                                        Set<String> ignoredExtensions, Set<String> touchedIds) {
        ComparisonUtils.assertXmlEquals(
                new ByteArrayInputStream(canonicalXiidm(expected, ignoredExtensions, touchedIds)),
                new ByteArrayInputStream(canonicalXiidm(actual, ignoredExtensions, touchedIds)));
    }

    static byte[] canonicalXiidm(Network network) {
        return canonicalXiidm(network, Set.of());
    }

    static byte[] canonicalXiidm(Network network, Set<String> ignoredExtensions) {
        return canonicalXiidm(network, ignoredExtensions, Set.of());
    }

    static byte[] canonicalXiidm(Network network, Set<String> ignoredExtensions, Set<String> touchedIds) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xiidm(network)));
            Element root = document.getDocumentElement();

            removeExtensions(root, ignoredExtensions);
            relabelNodes(root);
            relabelRegulatingTerminals(root, regulatingTerminalBuses(network));
            neutraliseNetworkIdentifier(root, network.getId());
            blank(root, "caseDate");
            blank(root, "forecastDistance");
            if (!touchedIds.isEmpty()) {
                blankStateVariables(root, touchedIds, false);
            }
            canonicalise(root);

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot canonicalise the XIIDM of " + network.getId(), e);
        }
    }

    /**
     * Drop the named extensions, and the {@code extension} wrappers they leave empty.
     *
     * <p>An extension of an identifiable is written as {@code <extension id="…"><name>…</name></extension>}, so
     * removing an extension means removing the element named after it and then the wrapper when nothing else is
     * in it.</p>
     */
    private static void removeExtensions(Element root, Set<String> names) {
        if (names.isEmpty()) {
            return;
        }
        List<Element> wrappers = new ArrayList<>();
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element element && "extension".equals(element.getLocalName())) {
                wrappers.add(element);
            }
        }
        for (Element wrapper : wrappers) {
            List<Element> toRemove = new ArrayList<>();
            NodeList children = wrapper.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element child && names.contains(child.getLocalName())) {
                    toRemove.add(child);
                }
            }
            toRemove.forEach(wrapper::removeChild);
            if (!hasElementChild(wrapper) && wrapper.getParentNode() != null) {
                wrapper.getParentNode().removeChild(wrapper);
            }
        }
    }

    private static boolean hasElementChild(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ node relabelling

    /**
     * Replace the node number of every node-breaker terminal by a label derived from what is attached to it.
     *
     * <p>Two passes: the first collects, per voltage level and node number, the equipment that references it; the
     * second writes the label back. An element that lives in a voltage level references nodes of that voltage
     * level; a branch names its voltage levels in {@code voltageLevelId1} and {@code voltageLevelId2}.</p>
     */
    private static void relabelNodes(Element root) {
        Map<String, Map<String, List<String>>> tokens = new HashMap<>();
        forEachNodeReference(root, (voltageLevelId, nodeNumber, token, attribute) ->
                tokens.computeIfAbsent(voltageLevelId, k -> new TreeMap<>())
                        .computeIfAbsent(nodeNumber, k -> new ArrayList<>())
                        .add(token));

        Map<String, Map<String, String>> labels = new HashMap<>();
        tokens.forEach((voltageLevelId, byNode) -> {
            Map<String, String> byNumber = new HashMap<>();
            // Sort the labels so that nodes whose attachments are indistinguishable (a chain of internal
            // connections) still get stable, comparable names
            List<Map.Entry<String, List<String>>> entries = new ArrayList<>(byNode.entrySet());
            entries.forEach(e -> e.getValue().sort(Comparator.naturalOrder()));
            entries.sort(Comparator.comparing(e -> String.join(";", e.getValue())));
            int index = 0;
            for (Map.Entry<String, List<String>> e : entries) {
                byNumber.put(e.getKey(), "n[" + String.join(";", e.getValue()) + "]#" + index++);
            }
            labels.put(voltageLevelId, byNumber);
        });

        forEachNodeReference(root, (voltageLevelId, nodeNumber, token, attribute) -> {
            String label = labels.getOrDefault(voltageLevelId, Map.of()).get(nodeNumber);
            if (label != null) {
                attribute.getOwnerElement().setAttribute(attribute.getName(), label);
            }
        });
        relabelNodeLists(root, null, labels);
    }

    /**
     * The bus every terminal of the network sits on, keyed by {@code <connectable id>#<side>}.
     *
     * <p>A bus is named by the sorted identifiers of the equipment connected to it, not by its own identifier,
     * because the identifier of a calculated bus is generated and follows the order the bus view built them in.</p>
     */
    private static Map<String, String> regulatingTerminalBuses(Network network) {
        Map<String, String> keys = new HashMap<>();
        for (Connectable<?> connectable : network.getConnectables()) {
            List<? extends Terminal> terminals = connectable.getTerminals();
            for (int i = 0; i < terminals.size(); i++) {
                keys.put(connectable.getId() + "#" + (i + 1), busOf(terminals.get(i)));
            }
        }
        return keys;
    }

    private static String busOf(Terminal terminal) {
        Bus bus = terminal.getBusView().getBus();
        String prefix = "bus";
        if (bus == null) {
            bus = terminal.getBusBreakerView().getBus();
            prefix = "busbreaker";
        }
        if (bus == null) {
            return "vl[" + terminal.getVoltageLevel().getId() + "]/disconnected";
        }
        return prefix + "[" + bus.getConnectedTerminalStream()
                .map(t -> t.getConnectable().getId())
                .sorted()
                .collect(Collectors.joining(";")) + "]";
    }

    /**
     * Replace the equipment a regulating terminal names by the bus that terminal sits on.
     *
     * <p>A CGMES regulating control points at a CGMES terminal, and PowSyBl maps it to one of the IIDM terminals
     * of the topological node that terminal belongs to. Which one it picks comes out of the terminal mapping, and
     * that follows the query results: two runs name two different pieces of equipment, on two different
     * connectivity nodes of the very same bus. The regulation point is identical, and naming the bus says so
     * while still failing if a control ever ends up regulating a different bus. (This was measured on the
     * Svedala fixture: 39 generators, 39 identical regulating buses, 5 different representative terminals.)</p>
     */
    private static void relabelRegulatingTerminals(Element element, Map<String, String> byTerminal) {
        String name = element.getLocalName();
        // regulatingTerminal: generators, shunt and static var compensators. terminalRef: the
        // regulating terminal of a tap changer and the reference terminal of a coordinated control.
        if (("regulatingTerminal".equals(name) || "terminalRef".equals(name)) && element.hasAttribute("id")) {
            String side = switch (element.getAttribute("side")) {
                case "TWO" -> "2";
                case "THREE" -> "3";
                default -> "1";
            };
            String label = byTerminal.get(element.getAttribute("id") + "#" + side);
            if (label != null) {
                element.setAttribute("id", label);
            }
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                relabelRegulatingTerminals(child, byTerminal);
            }
        }
    }

    /**
     * Relabel the {@code nodes} attribute of a calculated bus, which lists the numbers of the nodes it merges.
     *
     * <p>The list is a set, so it is sorted after relabelling.</p>
     */
    private static void relabelNodeLists(Element element, String enclosingVoltageLevel,
                                         Map<String, Map<String, String>> labels) {
        String voltageLevel = "voltageLevel".equals(element.getLocalName())
                ? element.getAttribute("id") : enclosingVoltageLevel;
        Attr nodes = element.getAttributeNode("nodes");
        if (nodes != null && voltageLevel != null) {
            Map<String, String> byNumber = labels.getOrDefault(voltageLevel, Map.of());
            String[] parts = nodes.getValue().split(",");
            for (int i = 0; i < parts.length; i++) {
                parts[i] = byNumber.getOrDefault(parts[i], parts[i]);
            }
            Arrays.sort(parts);
            nodes.setValue(String.join(",", parts));
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                relabelNodeLists(child, voltageLevel, labels);
            }
        }
    }

    private interface NodeReferenceVisitor {
        void visit(String voltageLevelId, String nodeNumber, String token, Attr attribute);
    }

    private static void forEachNodeReference(Element element, NodeReferenceVisitor visitor) {
        forEachNodeReference(element, null, visitor);
    }

    private static void forEachNodeReference(Element element, String enclosingVoltageLevel,
                                             NodeReferenceVisitor visitor) {
        String voltageLevel = enclosingVoltageLevel;
        if ("voltageLevel".equals(element.getLocalName())) {
            voltageLevel = element.getAttribute("id");
        }
        for (String name : NODE_ATTRIBUTES) {
            Attr attribute = element.getAttributeNode(name);
            if (attribute == null) {
                continue;
            }
            String side = name.length() == 4 ? "1" : name.substring(4);
            String owner = element.hasAttribute("id") ? element.getAttribute("id")
                    : element.getLocalName() + "?";
            String vl = element.hasAttribute("voltageLevelId" + side)
                    ? element.getAttribute("voltageLevelId" + side)
                    : voltageLevel;
            if (vl != null && !vl.isEmpty()) {
                visitor.visit(vl, attribute.getValue(), owner + "#" + side, attribute);
            }
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                forEachNodeReference(child, voltageLevel, visitor);
            }
        }
    }

    // ------------------------------------------------------------------ the rest

    /**
     * Blank the identifier of the network itself, and pin the position of the extensions that carry it.
     *
     * <p>Only the network's own identifier is touched: the same UUID also names a model header, and that header
     * is present in both documents and must keep being compared. The network-level extensions are named after
     * the network, so blanking the name would leave them sorted by a value that is gone &mdash; they are moved to
     * the front instead, in the order they were written.</p>
     */
    private static void neutraliseNetworkIdentifier(Element root, String networkId) {
        blank(root, "id");
        List<Element> networkExtensions = new ArrayList<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child
                    && "extension".equals(child.getLocalName())
                    && networkId.equals(child.getAttribute("id"))) {
                networkExtensions.add(child);
            }
        }
        Node first = root.getFirstChild();
        for (Element extension : networkExtensions) {
            extension.setAttribute("id", NETWORK_ID_TOKEN);
            root.removeChild(extension);
            root.insertBefore(extension, first);
        }
    }

    /**
     * The attributes a CGMES state variables file fills in, see
     * {@link #assertSameNetworkIgnoringStateVariables(Network, Network, Set, Set)}: the flows at the terminals,
     * the voltages at the buses, and the tap positions and section counts a solver arrived at.
     */
    private static final List<String> STATE_VARIABLE_ATTRIBUTES = List.of("p", "q", "p1", "q1", "p2", "q2", "p3",
            "q3", "v", "angle", "solvedTapPosition", "solvedSectionCount", "solvedSections");

    /**
     * Drop the state variable results of the touched equipment, recursively.
     *
     * <p>Removed rather than blanked: a value that was cleared is written as no attribute at all, so keeping an
     * empty one would still make the two documents differ in the number of attributes. An element without an
     * identifier of its own &mdash; the tap changer of a transformer, for instance &mdash; belongs to the nearest
     * ancestor that has one.</p>
     */
    private static void blankStateVariables(Element element, Set<String> touchedIds, boolean inheritedTouched) {
        String id = element.getAttribute("id");
        boolean touched = id.isEmpty() ? inheritedTouched : touchedIds.contains(id);
        if (touched) {
            STATE_VARIABLE_ATTRIBUTES.forEach(element::removeAttribute);
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                blankStateVariables(child, touchedIds, touched);
            }
        }
    }

    private static void blank(Element element, String attribute) {
        if (element.hasAttribute(attribute)) {
            element.setAttribute(attribute, "");
        }
    }

    private static void canonicalise(Element element) {
        roundDecimals(element);
        sortIdentifierList(element);
        sortTextOnlySiblings(element);
        sortInternalConnections(element);
        sortSetLikeSiblings(element, "areaBoundary");
        sortSetLikeSiblings(element, "bus");
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                canonicalise(child);
            }
        }
    }

    private static void roundDecimals(Element element) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            String value = attribute.getValue();
            if (DECIMAL.matcher(value).matches()) {
                attribute.setValue(new BigDecimal(value).round(new MathContext(12)).stripTrailingZeros()
                        .toString());
            }
        }
    }

    private static void sortIdentifierList(Element element) {
        if (!"property".equals(element.getLocalName()) || !element.hasAttribute("value")) {
            return;
        }
        String value = element.getAttribute("value");
        if (!value.contains(",")) {
            return;
        }
        String[] parts = value.split(",");
        if (Arrays.stream(parts).anyMatch(part -> !part.matches("[-A-Za-z0-9_.:#@\\[\\]]+"))) {
            return;
        }
        Arrays.sort(parts);
        element.setAttribute("value", String.join(",", parts));
    }

    private static void sortTextOnlySiblings(Element parent) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element child) {
                children.add(child);
            }
        }
        // Only runs of same-named elements that carry nothing but text: those are sets written as a sequence,
        // never an ordered structure (an ordered one would have attributes or children of its own).
        int from = 0;
        while (from < children.size()) {
            int to = from;
            String name = children.get(from).getTagName();
            while (to + 1 < children.size() && children.get(to + 1).getTagName().equals(name)) {
                to++;
            }
            if (to > from && children.subList(from, to + 1).stream().allMatch(Networks::isTextOnly)) {
                List<Element> run = new ArrayList<>(children.subList(from, to + 1));
                run.sort(Comparator.comparing(Node::getTextContent));
                Node anchor = children.get(to).getNextSibling();
                run.forEach(parent::removeChild);
                run.forEach(e -> parent.insertBefore(e, anchor));
            }
            from = to + 1;
        }
    }

    /**
     * Sort the internal connections of a node-breaker topology.
     *
     * <p>They have no identifier of their own, so the order they are written in is the order they were created
     * in, which follows the query results. After the node relabelling above their two ends are canonical names,
     * which makes a deterministic order possible; an internal connection is undirected, so the pair is sorted
     * too.</p>
     */
    private static void sortInternalConnections(Element parent) {
        List<Element> connections = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        Node anchor = null;
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element child && "internalConnection".equals(child.getLocalName())) {
                connections.add(child);
                anchor = child.getNextSibling();
            }
        }
        if (connections.size() < 2) {
            return;
        }
        connections.forEach(c -> {
            String n1 = c.getAttribute("node1");
            String n2 = c.getAttribute("node2");
            c.setAttribute("node1", n1.compareTo(n2) <= 0 ? n1 : n2);
            c.setAttribute("node2", n1.compareTo(n2) <= 0 ? n2 : n1);
        });
        connections.sort(Comparator.comparing((Element c) -> c.getAttribute("node1"))
                .thenComparing(c -> c.getAttribute("node2")));
        List<Element> sorted = new ArrayList<>(connections);
        connections.forEach(parent::removeChild);
        Node before = anchor;
        for (Element c : sorted) {
            parent.insertBefore(c, before);
        }
    }

    /**
     * Sort a group of sibling elements that is a set rather than a sequence.
     *
     * <p>{@code areaBoundary}: the boundaries of a control area, written in the order of the {@code tieFlows}
     * query results. {@code bus}: the calculated buses of a topology, written in the order the bus view built
     * them. Neither carries an order of its own.</p>
     */
    private static void sortSetLikeSiblings(Element parent, String localName) {
        List<Element> group = new ArrayList<>();
        Node anchor = null;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element child && localName.equals(child.getLocalName())) {
                group.add(child);
                anchor = child.getNextSibling();
            }
        }
        if (group.size() < 2) {
            return;
        }
        group.sort(Comparator.comparing(Networks::attributeSignature));
        List<Element> sorted = new ArrayList<>(group);
        group.forEach(parent::removeChild);
        for (Element e : sorted) {
            parent.insertBefore(e, anchor);
        }
    }

    private static String attributeSignature(Element element) {
        NamedNodeMap attributes = element.getAttributes();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            parts.add(attribute.getName() + "=" + attribute.getValue());
        }
        parts.sort(Comparator.naturalOrder());
        return String.join("|", parts);
    }

    private static boolean isTextOnly(Element element) {
        if (element.getAttributes().getLength() > 0) {
            return false;
        }
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element) {
                return false;
            }
        }
        return true;
    }
}
