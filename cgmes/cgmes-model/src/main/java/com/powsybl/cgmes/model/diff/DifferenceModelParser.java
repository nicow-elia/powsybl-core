/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.model.diff;

import com.powsybl.cgmes.model.CgmesModelException;
import com.powsybl.cgmes.model.CgmesNames;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.CgmesOnDataSource;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.exceptions.UncheckedXmlStreamException;
import com.powsybl.commons.xml.XmlUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import static com.powsybl.cgmes.model.CgmesNamespace.DM_NAMESPACE;
import static com.powsybl.cgmes.model.CgmesNamespace.MD_NAMESPACE;
import static com.powsybl.cgmes.model.CgmesNamespace.RDF_NAMESPACE;

/**
 * Reads an IEC 61970-552 {@code dm:DifferenceModel} RDF/XML document into the value objects of the difference layer.
 *
 * <p>This is a streaming StAX cursor reader, not an RDF engine: it never builds a graph and never touches a triple
 * store, because a difference model is a flat list of statements about objects that already exist elsewhere. That
 * keeps reading a difference model roughly as cheap as reading the same number of bytes, which is what makes the
 * in-place update route worth having.</p>
 *
 * <p>The reader is deliberately tolerant about everything the standard leaves free and strict about everything that
 * would silently change the meaning of a document:</p>
 * <ul>
 *   <li>prefixes are never looked at, only namespace URIs and local names, so a document using {@code d:} for the
 *       difference namespace and {@code c:} for CIM reads exactly like one using {@code dm:} and {@code cim:};</li>
 *   <li>the three statement containers may appear in any order, several times, or not at all;</li>
 *   <li>the model description may be missing entirely, in which case the profile is taken from the file name;</li>
 *   <li>a subject may be written as a typed node element or as an {@code rdf:Description} with an explicit
 *       {@code rdf:type}; both produce a {@link CgmesStatement#RDF_TYPE} statement;</li>
 *   <li>identifiers are normalized, so {@code #_abc}, {@code urn:uuid:abc} and {@code abc} are the same subject;</li>
 *   <li>blank nodes, nested descriptions, a {@code md:FullModel} document and a document with two difference models
 *       are rejected rather than half understood.</li>
 * </ul>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class DifferenceModelParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(DifferenceModelParser.class);

    /**
     * Property name of a statement that carries the {@code rdf:type} of its subject.
     *
     * @see CgmesStatement#RDF_TYPE
     */
    public static final String RDF_TYPE = CgmesStatement.RDF_TYPE;

    private static final String DIFFERENCE_MODEL = "DifferenceModel";
    private static final String FORWARD_DIFFERENCES = "forwardDifferences";
    private static final String REVERSE_DIFFERENCES = "reverseDifferences";
    private static final String PRECONDITIONS = "preconditions";
    private static final String NODE_ID = "nodeID";
    private static final String TYPE = "type";
    private static final String URN_UUID = "urn:uuid:";

    /** The date format of a CGMES model description, with UTC assumed when the document gives no zone. */
    private static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .appendPattern("[VV][x][xx][xxx]")
            .toFormatter();

    private DifferenceModelParser() {
    }

    /**
     * Read a difference model document.
     *
     * @param inputStream the document. It is read to its end but neither flushed nor closed by this method
     * @param fileName    the name the document was read from, used to guess the profile when the model description
     *                    declares none and to make error messages traceable. May be {@code null}
     * @throws CgmesModelException        if the document is not a difference model or uses a construct this reader
     *                                    refuses to guess about
     * @throws UncheckedXmlStreamException if the document is not well formed XML
     */
    public static DifferenceModel parse(InputStream inputStream, String fileName) {
        Objects.requireNonNull(inputStream);
        return new Reader(fileName).read(inputStream);
    }

    /** Read a difference model from an in-memory document. */
    public static DifferenceModel parse(String xml) {
        Objects.requireNonNull(xml);
        return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), null);
    }

    /** Read a difference model from a file, whose name is used for the profile fallback. */
    public static DifferenceModel parse(Path file) {
        Objects.requireNonNull(file);
        try (InputStream inputStream = Files.newInputStream(file)) {
            return parse(inputStream, file.getFileName().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Read one named file of a data source, transparently entering a single-entry zip. */
    public static DifferenceModel parse(ReadOnlyDataSource dataSource, String fileName) {
        Objects.requireNonNull(dataSource);
        Objects.requireNonNull(fileName);
        return readFile(dataSource, fileName, is -> parse(is, fileName));
    }

    /**
     * Read every difference model file of a data source, in the order of their names.
     *
     * <p>Files that are not difference models are left out, so that a data source holding the difference models of
     * several profiles next to unrelated content still reads.</p>
     *
     * @throws IllegalArgumentException if two files of the data source describe the same profile
     */
    public static DifferenceModelSet parseAll(ReadOnlyDataSource dataSource) {
        Objects.requireNonNull(dataSource);
        List<DifferenceModel> models = new ArrayList<>();
        for (String name : sortedNames(dataSource)) {
            if (isDifferenceModel(dataSource, name)) {
                models.add(parse(dataSource, name));
            }
        }
        return new DifferenceModelSet(models);
    }

    /**
     * Whether a document is a difference model, decided by looking at its first two elements only.
     *
     * <p>This is the sniff a data source detection does on every file, so it must stay cheap and must never throw:
     * anything that is not a well formed XML document whose root has a {@code dm:DifferenceModel} child simply is
     * not a difference model.</p>
     *
     * @param inputStream the document. Read only as far as needed
     */
    public static boolean isDifferenceModel(InputStream inputStream) {
        Objects.requireNonNull(inputStream);
        XMLStreamReader reader = null;
        try {
            reader = XmlUtil.getXMLInputFactory().createXMLStreamReader(inputStream);
            int depth = 0;
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (depth == 2) {
                        return DM_NAMESPACE.equals(reader.getNamespaceURI())
                                && DIFFERENCE_MODEL.equals(reader.getLocalName());
                    }
                }
            }
            return false;
        } catch (XMLStreamException e) {
            // Anything that is not a well formed XML document simply is not a difference model
            LOGGER.trace("Not a difference model document", e);
            return false;
        } finally {
            closeQuietly(reader);
        }
    }

    /** Whether one named file of a data source is a difference model. Never throws for unreadable content. */
    public static boolean isDifferenceModel(ReadOnlyDataSource dataSource, String fileName) {
        Objects.requireNonNull(dataSource);
        Objects.requireNonNull(fileName);
        try {
            return Boolean.TRUE.equals(readFile(dataSource, fileName, DifferenceModelParser::isDifferenceModel));
        } catch (CgmesModelException | UncheckedIOException e) {
            LOGGER.trace("Could not read {} of {}", fileName, dataSource, e);
            return false;
        }
    }

    private static Set<String> sortedNames(ReadOnlyDataSource dataSource) {
        try {
            return new TreeSet<>(dataSource.listNames(".*"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <T> T readFile(ReadOnlyDataSource dataSource, String fileName, Function<InputStream, T> reader) {
        return new CgmesOnDataSource(dataSource).readFile(fileName, reader);
    }

    private static void closeQuietly(XMLStreamReader reader) {
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (XMLStreamException e) {
            LOGGER.trace("Could not close the XML reader", e);
        } finally {
            XmlUtil.gcXmlInputFactory(XmlUtil.getXMLInputFactory());
        }
    }

    /**
     * Normalize an identifier the way the rest of the difference layer holds it: without its document, without the
     * {@code urn:uuid:} scheme and without the leading underscore CGMES puts in front of a mRID.
     *
     * <p>{@code http://x#_abc}, {@code #_abc}, {@code _abc}, {@code urn:uuid:abc} and {@code abc} all become
     * {@code abc}, which is exactly the identifier a CGMES importer gives the object.</p>
     */
    public static String normalizeId(String id) {
        Objects.requireNonNull(id);
        String value = id;
        int hash = value.lastIndexOf('#');
        if (hash >= 0) {
            value = value.substring(hash + 1);
        }
        if (value.startsWith(URN_UUID)) {
            value = value.substring(URN_UUID.length());
        }
        if (value.startsWith("_")) {
            value = value.substring(1);
        }
        return value;
    }

    /** One pass over one document. Not thread safe, one instance per parse. */
    private static final class Reader {

        private final String fileName;

        private XMLStreamReader reader;
        private final Set<String> declaredNamespaces = new LinkedHashSet<>();
        private String cimNamespace;
        private String modelId;
        private boolean differenceModelSeen;

        private ZonedDateTime scenarioTime;
        private ZonedDateTime created;
        private String description;
        private int version;
        private String modelingAuthoritySet;
        private final List<String> profiles = new ArrayList<>();
        private final List<String> dependentOn = new ArrayList<>();
        private final List<String> supersedes = new ArrayList<>();

        private final List<CgmesStatement> forward = new ArrayList<>();
        private final List<CgmesStatement> reverse = new ArrayList<>();
        private final List<CgmesStatement> preconditions = new ArrayList<>();

        /** The container the statements currently read belong to, {@code null} outside any container. */
        private List<CgmesStatement> target;
        /** The subject currently described, {@code null} outside a node element. */
        private String subject;
        /** The class of the subject currently described, {@code null} for an {@code rdf:Description}. */
        private String subjectClass;

        private Reader(String fileName) {
            this.fileName = fileName;
        }

        private DifferenceModel read(InputStream inputStream) {
            try {
                reader = XmlUtil.getXMLInputFactory().createXMLStreamReader(inputStream);
                int depth = 0;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        if (startElement(depth)) {
                            // getElementText() left the cursor on the end element of this one, which the loop will
                            // therefore never see: close the level here
                            endElement(depth);
                            depth--;
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        endElement(depth);
                        depth--;
                    }
                }
            } catch (XMLStreamException e) {
                throw new UncheckedXmlStreamException(e);
            } finally {
                closeQuietly(reader);
            }
            return build();
        }

        /**
         * Handle one start element.
         *
         * @return whether the element was consumed up to and including its end element, which happens for every
         *         element whose text content was read
         */
        private boolean startElement(int depth) throws XMLStreamException {
            return switch (depth) {
                case 1 -> {
                    readRoot();
                    yield false;
                }
                case 2 -> {
                    readDifferenceModel();
                    yield false;
                }
                case 3 -> readHeaderOrContainer();
                case 4 -> {
                    readNode();
                    yield false;
                }
                case 5 -> readProperty();
                default -> throw error("nested descriptions are not supported, found <"
                        + reader.getLocalName() + "> inside a property");
            };
        }

        private void endElement(int depth) {
            if (depth == 3) {
                target = null;
            } else if (depth == 4) {
                subject = null;
                subjectClass = null;
            }
        }

        private void readRoot() {
            for (int k = 0; k < reader.getNamespaceCount(); k++) {
                String namespace = reader.getNamespaceURI(k);
                if (namespace != null) {
                    declaredNamespaces.add(namespace);
                }
            }
            cimNamespace = declaredNamespaces.stream().filter(CgmesNamespace::isValid).findFirst().orElse(null);
        }

        private void readDifferenceModel() {
            if (!DM_NAMESPACE.equals(reader.getNamespaceURI()) || !DIFFERENCE_MODEL.equals(reader.getLocalName())) {
                LOGGER.debug("Skipping <{}> next to the difference model of {}", reader.getLocalName(), where());
                return;
            }
            if (differenceModelSeen) {
                throw error("several difference models in one document");
            }
            differenceModelSeen = true;
            String about = attribute(RDF_NAMESPACE, CgmesNames.ABOUT);
            String id = about != null ? about : attribute(RDF_NAMESPACE, CgmesNames.ID);
            if (id == null) {
                throw error("the difference model has neither rdf:about nor rdf:ID");
            }
            // A local identifier becomes the urn:uuid: form the rest of the CGMES metadata uses; anything that
            // already is an absolute URI is kept exactly as the producer wrote it.
            modelId = isLocal(id) ? URN_UUID + normalizeId(id) : id;
        }

        private static boolean isLocal(String id) {
            return id.startsWith("#") || id.startsWith("_");
        }

        private boolean readHeaderOrContainer() throws XMLStreamException {
            String namespace = reader.getNamespaceURI();
            String localName = reader.getLocalName();
            if (DM_NAMESPACE.equals(namespace)) {
                target = switch (localName) {
                    case FORWARD_DIFFERENCES -> forward;
                    case REVERSE_DIFFERENCES -> reverse;
                    case PRECONDITIONS -> preconditions;
                    default -> null;
                };
                if (target == null) {
                    LOGGER.debug("Skipping unknown difference model element <{}> of {}", localName, where());
                }
                return false;
            }
            if (MD_NAMESPACE.equals(namespace)) {
                return readHeaderProperty(localName);
            }
            LOGGER.debug("Skipping unknown model description element <{}> of {}", localName, where());
            return false;
        }

        /** @return whether the text content of the element was read, which consumes its end element */
        private boolean readHeaderProperty(String localName) throws XMLStreamException {
            switch (localName) {
                case CgmesNames.SCENARIO_TIME -> scenarioTime = parseDate(reader.getElementText(), localName);
                case CgmesNames.CREATED -> created = parseDate(reader.getElementText(), localName);
                case CgmesNames.DESCRIPTION -> description = reader.getElementText();
                case CgmesNames.VERSION -> version = parseVersion(reader.getElementText());
                case CgmesNames.PROFILE -> profiles.add(reader.getElementText());
                case CgmesNames.MODELING_AUTHORITY_SET -> modelingAuthoritySet = reader.getElementText();
                case CgmesNames.DEPENDENT_ON -> {
                    addResource(dependentOn);
                    return false;
                }
                case CgmesNames.SUPERSEDES -> {
                    addResource(supersedes);
                    return false;
                }
                default -> {
                    LOGGER.debug("Skipping unknown model description property <{}> of {}", localName, where());
                    return false;
                }
            }
            return true;
        }

        private void addResource(List<String> into) {
            String resource = attribute(RDF_NAMESPACE, CgmesNames.RESOURCE);
            if (resource != null) {
                // Model references are kept verbatim: they name other models, not objects of this one
                into.add(resource);
            }
        }

        private void readNode() {
            if (target == null) {
                LOGGER.debug("Skipping <{}> outside a statement container of {}", reader.getLocalName(), where());
                return;
            }
            String about = attribute(RDF_NAMESPACE, CgmesNames.ABOUT);
            String id = about != null ? about : attribute(RDF_NAMESPACE, CgmesNames.ID);
            if (id == null) {
                if (attribute(RDF_NAMESPACE, NODE_ID) != null) {
                    throw error("blank nodes are not supported");
                }
                throw error("blank nodes are not supported: <" + reader.getLocalName()
                        + "> has neither rdf:about nor rdf:ID");
            }
            subject = normalizeId(id);
            boolean plainDescription = RDF_NAMESPACE.equals(reader.getNamespaceURI())
                    && "Description".equals(reader.getLocalName());
            subjectClass = plainDescription ? null : reader.getLocalName();
            if (subjectClass != null) {
                // A typed node element says what the subject is; make that explicit so that a consumer does not have
                // to know which of the two RDF/XML shapes the producer chose
                target.add(CgmesStatement.reference(subject, subjectClass, RDF_TYPE, subjectClass));
            }
        }

        /** @return whether the text content of the element was read, which consumes its end element */
        private boolean readProperty() throws XMLStreamException {
            if (subject == null) {
                LOGGER.debug("Skipping <{}> outside a described subject of {}", reader.getLocalName(), where());
                return false;
            }
            String namespace = reader.getNamespaceURI();
            String localName = reader.getLocalName();
            if (RDF_NAMESPACE.equals(namespace) && TYPE.equals(localName)) {
                String resource = attribute(RDF_NAMESPACE, CgmesNames.RESOURCE);
                if (resource == null) {
                    throw error("rdf:type without rdf:resource");
                }
                target.add(CgmesStatement.reference(subject, subjectClass, RDF_TYPE, localPart(resource)));
                return false;
            }
            // A property of the CIM namespace is held by its local name, anything else by its absolute IRI: the
            // update workflow only knows CIM properties, and an absolute IRI can never be mistaken for one
            String property = namespace == null || namespace.equals(cimNamespace) ? localName : namespace + localName;
            if (cimNamespace == null && namespace != null && CgmesNamespace.isValid(namespace)) {
                // The root element declared no CIM namespace; the first valid one seen on a property defines it
                cimNamespace = namespace;
                property = localName;
            }
            String resource = attribute(RDF_NAMESPACE, CgmesNames.RESOURCE);
            if (resource != null) {
                if (cimNamespace != null && resource.startsWith(cimNamespace)) {
                    target.add(CgmesStatement.enumeration(subject, subjectClass, property,
                            resource.substring(cimNamespace.length())));
                } else {
                    target.add(CgmesStatement.reference(subject, subjectClass, property, normalizeId(resource)));
                }
                return false;
            }
            String text;
            try {
                text = reader.getElementText();
            } catch (XMLStreamException e) {
                throw error("nested descriptions are not supported, <" + localName + "> has element content");
            }
            target.add(CgmesStatement.literal(subject, subjectClass, property, text));
            return true;
        }

        /** The part of an IRI a CIM enumeration or class is named by, that is what follows its last {@code #}. */
        private static String localPart(String iri) {
            int hash = iri.lastIndexOf('#');
            return hash < 0 ? iri : iri.substring(hash + 1);
        }

        private String attribute(String namespace, String name) {
            return reader.getAttributeValue(namespace, name);
        }

        private ZonedDateTime parseDate(String text, String property) {
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                TemporalAccessor parsed = DATE_TIME_FORMATTER.parseBest(text.trim(), ZonedDateTime::from,
                        LocalDateTime::from);
                return parsed instanceof ZonedDateTime zoned ? zoned
                        : ZonedDateTime.of((LocalDateTime) parsed, ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                LOGGER.warn("Ignoring unparsable md:{} \"{}\" of {}", property, text, where());
                return null;
            }
        }

        private int parseVersion(String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("Ignoring unparsable md:Model.version \"{}\" of {}", text, where());
                return 0;
            }
        }

        private DifferenceModel build() {
            if (!differenceModelSeen) {
                throw new CgmesModelException(where() + " is not a difference model: it holds no"
                        + " dm:DifferenceModel element");
            }
            if (cimNamespace == null) {
                throw new CgmesModelException(where() + " declares no CIM namespace");
            }
            DifferenceModelHeader header = DifferenceModelHeader.builder(modelId, subset(), cimNamespace)
                    .scenarioTime(scenarioTime)
                    .created(created)
                    .description(description)
                    .version(version)
                    .modelingAuthoritySet(modelingAuthoritySet)
                    .profiles(profiles)
                    .dependentOn(dependentOn)
                    .supersedes(supersedes)
                    .build();
            return new DifferenceModel(header, forward, reverse, preconditions);
        }

        /**
         * The profile of this model: the first profile URI that names one, else the profile the file name says, else
         * unknown.
         *
         * <p>A header declaring several profiles keeps them all, and the model stays one model of the profile with
         * the highest priority. Splitting it would need the CIM schema to tell which statement belongs to which
         * profile; instead the capability check reports the statements of the other profile as not updatable.</p>
         */
        private CgmesSubset subset() {
            for (CgmesSubset candidate : List.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, CgmesSubset.EQUIPMENT,
                    CgmesSubset.TOPOLOGY, CgmesSubset.STATE_VARIABLES)) {
                if (profiles.stream().anyMatch(uri -> isProfileOf(uri, candidate))) {
                    return candidate;
                }
            }
            if (profiles.isEmpty() && fileName != null) {
                for (CgmesSubset candidate : CgmesSubset.values()) {
                    if (candidate.isValidName(fileName)) {
                        return candidate;
                    }
                }
            }
            return CgmesSubset.UNKNOWN;
        }

        private static boolean isProfileOf(String uri, CgmesSubset subset) {
            return switch (subset) {
                case STEADY_STATE_HYPOTHESIS -> uri.contains("SteadyStateHypothesis");
                case EQUIPMENT -> uri.contains("Equipment") || uri.contains("/CIM/CoreEquipment")
                        || uri.contains("/CIM/Operation") || uri.contains("ShortCircuit");
                case TOPOLOGY -> uri.contains("Topology") && !uri.contains("Boundary");
                case STATE_VARIABLES -> uri.contains("StateVariables");
                default -> false;
            };
        }

        private CgmesModelException error(String message) {
            int line = reader != null && reader.getLocation() != null ? reader.getLocation().getLineNumber() : -1;
            return new CgmesModelException(where() + (line > 0 ? " line " + line : "") + ": " + message);
        }

        private String where() {
            return fileName != null ? fileName : "the difference model document";
        }
    }
}
