/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.test;

import com.powsybl.cgmes.model.CgmesNamespace;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks that a document really is an IEC 61970-552 difference model, structurally.
 *
 * <p>It is the counterpart of the golden files: those pin what the exporter writes today, this one pins what the
 * format allows, so that a deliberate change of the output still has to stay a difference model.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class DifferenceModelXmlAssert {

    private static final String DM = CgmesNamespace.DM_NAMESPACE;
    private static final String MD = CgmesNamespace.MD_NAMESPACE;
    private static final String RDF = CgmesNamespace.RDF_NAMESPACE;

    private DifferenceModelXmlAssert() {
    }

    /** Parse the document, which also checks that it is well formed, and assert its structure. */
    public static void assertIsADifferenceModel(String xml, String cimNamespace) {
        Document document = parse(xml);
        Element root = document.getDocumentElement();
        assertEquals("RDF", root.getLocalName());
        assertEquals(RDF, root.getNamespaceURI());

        List<Element> differenceModels = childElements(root, DM, "DifferenceModel");
        assertEquals(1, differenceModels.size(), "expected exactly one dm:DifferenceModel");
        Element differenceModel = differenceModels.get(0);
        assertTrue(differenceModel.getAttributeNS(RDF, "about").matches("urn:uuid:.+"),
                () -> "expected a urn:uuid identifier, was " + differenceModel.getAttributeNS(RDF, "about"));

        for (String mandatory : List.of("Model.scenarioTime", "Model.created", "Model.version",
                "Model.modelingAuthoritySet")) {
            assertEquals(1, childElements(differenceModel, MD, mandatory).size(),
                    () -> "expected exactly one md:" + mandatory);
        }
        // A model declares at least one profile, and an equipment model legitimately declares several of them
        // (EquipmentCore, EquipmentOperation, ...), which the difference inherits from the model it supersedes
        assertTrue(!childElements(differenceModel, MD, "Model.profile").isEmpty(),
                "expected at least one md:Model.profile");

        assertContainer(differenceModel, "reverseDifferences", cimNamespace);
        assertContainer(differenceModel, "forwardDifferences", cimNamespace);
    }

    private static void assertContainer(Element differenceModel, String name, String cimNamespace) {
        List<Element> containers = childElements(differenceModel, DM, name);
        assertEquals(1, containers.size(), () -> "expected exactly one dm:" + name);
        Element container = containers.get(0);
        assertEquals("Statements", container.getAttributeNS(RDF, "parseType"),
                () -> "dm:" + name + " has to carry rdf:parseType=\"Statements\"");

        Set<String> subjects = new HashSet<>();
        for (Element description : childElements(container, null, null)) {
            if (!RDF.equals(description.getNamespaceURI()) || !"Description".equals(description.getLocalName())) {
                fail("dm:" + name + " may only hold rdf:Description, found "
                        + description.getNamespaceURI() + ":" + description.getLocalName());
            }
            assertTrue(description.getAttributeNS(RDF, "ID").isEmpty(), "a difference states no rdf:ID");
            String about = description.getAttributeNS(RDF, "about");
            assertTrue(about.startsWith("#_"), () -> "expected a local rdf:about, was " + about);
            assertTrue(subjects.add(about), () -> "subject " + about + " is described twice in dm:" + name);

            for (Element property : childElements(description, null, null)) {
                assertEquals(cimNamespace, property.getNamespaceURI(),
                        () -> "expected a CIM property, was " + property.getNodeName());
                assertTrue(!"type".equals(property.getLocalName()), "a difference states no rdf:type");
                boolean hasResource = !property.getAttributeNS(RDF, "resource").isEmpty();
                boolean hasText = !property.getTextContent().isEmpty();
                assertTrue(hasResource ^ hasText,
                        () -> "property " + property.getNodeName() + " needs either text or rdf:resource, not both");
            }
        }
    }

    private static List<Element> childElements(Element parent, String namespace, String localName) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element
                    && (namespace == null || namespace.equals(element.getNamespaceURI()))
                    && (localName == null || localName.equals(element.getLocalName()))) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("The exported document is not well formed XML: " + e.getMessage(), e);
        }
    }
}
