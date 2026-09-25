/**
 * Copyright (c) 2017-2018, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.triplestore.impl.rdf4j;

import com.powsybl.commons.datasource.DataSource;
import com.powsybl.triplestore.api.*;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.util.URIUtil;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.*;
import org.eclipse.rdf4j.query.algebra.evaluation.optimizer.ParentReferenceChecker;
import org.eclipse.rdf4j.query.explanation.Explanation;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;
import org.eclipse.rdf4j.rio.helpers.BasicWriterSettings;
import org.eclipse.rdf4j.rio.helpers.XMLParserSettings;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Luma Zamarreño {@literal <zamarrenolm at aia.es>}
 */
public class TripleStoreRDF4J extends AbstractPowsyblTripleStore {

    static final String NAME = "rdf4j";

    public TripleStoreRDF4J() {
        this(new TripleStoreOptions());
    }

    public TripleStoreRDF4J(TripleStoreOptions options) {
        this(new SailRepository(new MemoryStore()), options);
    }

    /**
     * Create a triple store on top of the given RDF4J repository.
     *
     * <p>This is the seam that lets a triple store live somewhere else than in the in-memory sail this class
     * defaults to: a {@code SPARQLRepository} pointing at a remote endpoint, a native store on disk, or a
     * repository that several triple stores share. The store <em>owns</em> the repository it is given:
     * {@link #close()} shuts it down.</p>
     *
     * @param repository the repository to read from and write to. It is initialised if it is not initialised yet
     * @param options    the triple store configuration options
     */
    public TripleStoreRDF4J(Repository repository, TripleStoreOptions options) {
        super(options);

        // This boolean is used to deactivate the ParentReferenceChecker optimizers added in testing environment.
        // If you see serialization issues during the tests, do not hesitate to comment this line, at a cost of
        // computation performances IN TEST ENVIRONMENT ONLY, it does not affect production environment
        ParentReferenceChecker.skip = true;

        repo = Objects.requireNonNull(repository);
        if (!repo.isInitialized()) {
            repo.init();
        }
    }

    @Override
    public void close() {
        repo.shutDown();
    }

    @Override
    public String getImplementationName() {
        return NAME;
    }

    public Repository getRepository() {
        return repo;
    }

    public void setWriteBySubject(boolean writeBySubject) {
        this.writeBySubject = writeBySubject;
    }

    @Override
    public void read(InputStream is, String baseName, String contextName) {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.setIsolationLevel(IsolationLevels.NONE);

            // Report invalid identifiers but do not fail
            // (sometimes RDF identifiers contain spaces or begin with #)
            // This is the default behavior for other triple store engines (e.g. Jena)
            conn.getParserConfig().addNonFatalError(XMLParserSettings.FAIL_ON_INVALID_NCNAME);
            conn.getParserConfig().addNonFatalError(BasicParserSettings.VERIFY_URI_SYNTAX);
            conn.getParserConfig().addNonFatalError(XMLParserSettings.FAIL_ON_DUPLICATE_RDF_ID);

            Resource context = context(conn, contextName);
            // We add data with a context (graph) to keep the source of information
            // When we write we want to keep data split by graph
            conn.add(is, baseName, guessFormatFromName(contextName), context);
            addNamespaceForBase(conn, baseName);
        } catch (IOException e) {
            throw new TripleStoreException(String.format("Reading %s %s", baseName, contextName), e);
        }
    }

    private static RDFFormat guessFormatFromName(String name) {
        if (name.endsWith(".ttl")) {
            return RDFFormat.TURTLE;
        } else if (name.endsWith(".xml")) {
            return RDFFormat.RDFXML;
        }
        return RDFFormat.RDFXML;
    }

    @Override
    public void write(DataSource ds) {
        try (RepositoryConnection conn = repo.getConnection()) {
            RepositoryResult<Resource> contexts = conn.getContextIDs();
            while (contexts.hasNext()) {
                Resource context = contexts.next();
                write(ds, conn, context);
            }
        }
    }

    @Override
    public void write(DataSource ds, String contextName) {
        try (RepositoryConnection conn = repo.getConnection()) {
            RepositoryResult<Resource> contexts = conn.getContextIDs();
            while (contexts.hasNext()) {
                Resource context = contexts.next();
                if (context.stringValue().equals(contextName)) {
                    write(ds, conn, context);
                }
            }
        }
    }

    private void write(DataSource ds, RepositoryConnection conn, Resource context) {
        LOGGER.info("Writing context {}", context);

        RepositoryResult<Statement> statements;
        statements = conn.getStatements(null, null, null, context);
        Model model = QueryResults.asModel(statements);
        copyNamespacesToModel(conn, model);

        String outname = context.toString();
        write(model, outputStream(ds, outname));
    }

    @Override
    public void print(PrintStream out) {
        out.println("TripleStore based on RDF4J. Graph names and sizes");
        try (RepositoryConnection conn = repo.getConnection()) {
            RepositoryResult<Resource> ctxs = conn.getContextIDs();
            while (ctxs.hasNext()) {
                Resource ctx = ctxs.next();
                int size = statementsCount(conn, ctx);
                out.println("    " + ctx + " : " + size);
            }
        }
    }

    @Override
    public Set<String> contextNames() {
        try (RepositoryConnection conn = repo.getConnection()) {
            return conn.getContextIDs().stream().map(Resource::stringValue).collect(Collectors.toSet());
        }
    }

    @Override
    public void clear(String contextName) {
        try (RepositoryConnection conn = repo.getConnection()) {
            Resource context = context(conn, contextName);
            conn.clear(context);
        }
    }

    @Override
    public PropertyBags query(String query) {
        String query1 = adjustedQuery(query);
        PropertyBags results = new PropertyBags();
        try (RepositoryConnection conn = repo.getConnection()) {
            // Default language is SPARQL
            try {
                TupleQuery q = prepareTupleQuery(conn, query1);

                // Print the optimization plan for the query
                // Explaining queries take some time, so we change the execution timeout
                if (EXPLAIN_QUERIES && LOGGER.isDebugEnabled()) {
                    Explanation explanation = q.explain(Explanation.Level.Timed);
                    LOGGER.debug("Query explanation:\n{}\n{}", query, explanation);
                }

                // Duplicated triplets are returned in queries
                // when an object is defined in a file and referenced in another (rdf:ID and
                // rdf:about)
                // and data has been added to repository with contexts
                // and we query without using explicit GRAPH clauses
                // This means that we have to filter distinct results
                try (TupleQueryResult r = QueryResults.distinctResults(q.evaluate())) {
                    List<String> names = r.getBindingNames();
                    while (r.hasNext()) {
                        BindingSet s = r.next();
                        PropertyBag result = new PropertyBag(names, getOptions().isRemoveInitialUnderscoreForIdentifiers(), getOptions().unescapeIdentifiers());

                        names.forEach(name -> {
                            if (s.hasBinding(name)) {
                                String value = bindingValue(s.getBinding(name).getValue());
                                result.put(name, value);
                            }
                        });
                        if (result.size() > 0) {
                            results.add(result);
                        }
                    }
                }
            } catch (MalformedQueryException e) {
                int line = 1;
                for (String s : query1.split("\n")) {
                    LOGGER.error(String.format("%3d  %s", line, s));
                    line++;
                }
                throw e;
            }
        }
        return results;
    }

    @Override
    public void add(TripleStore source) {
        Objects.requireNonNull(source);
        Repository sourceRepo;
        if (source instanceof TripleStoreRDF4J tripleStoreRDF4J) {
            sourceRepo = tripleStoreRDF4J.repo;
            try (RepositoryConnection sourceConn = sourceRepo.getConnection()) {
                try (RepositoryConnection targetConn = repo.getConnection()) {
                    copyNamespacesToRepository(sourceConn, targetConn);
                    // copy statements
                    RepositoryResult<Resource> contexts = sourceConn.getContextIDs();
                    for (Resource sourceContext : contexts) {
                        Resource targetContext = context(targetConn, sourceContext.stringValue());

                        RepositoryResult<Statement> statements;
                        statements = sourceConn.getStatements(null, null, null, sourceContext);
                        // add statements to the new repository
                        for (Statement statement : statements) {
                            targetConn.add(statement, targetContext);
                        }
                    }
                }
            }
        } else {
            throw new TripleStoreException(String.format("Add to %s from source %s is not supported",
                getImplementationName(), source.getImplementationName()));
        }
    }

    private static void copyNamespacesToRepository(RepositoryConnection sourceConn, RepositoryConnection targetConn) {
        RepositoryResult<Namespace> ns = sourceConn.getNamespaces();
        for (Namespace namespace : ns) {
            targetConn.setNamespace(namespace.getPrefix(), namespace.getName());
        }
    }

    @Override
    public void update(String query) {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.prepareUpdate(QueryLanguage.SPARQL, adjustedQuery(query)).execute();
        } catch (MalformedQueryException | UpdateExecutionException | RepositoryException e) {
            throw new TripleStoreException(String.format("Query [%s]", query), e);
        }
    }

    @Override
    public void add(String contextName, String objNs, String objType, PropertyBags objects) {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.setIsolationLevel(IsolationLevels.NONE);
            addObjects(conn, contextName, objNs, objType, objects);
        }
    }

    @Override
    public String add(String contextName, String objNs, String objType, PropertyBag object) {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.setIsolationLevel(IsolationLevels.NONE);
            return addObject(conn, contextName, objNs, objType, object);
        }
    }

    /**
     * Create the statements of several new resources on an open connection.
     *
     * <p>Separated from {@link #add(String, String, String, PropertyBags)} so that a subclass whose backend
     * charges per request &mdash; a remote store, where autocommit means one {@code INSERT DATA} per statement
     * &mdash; can wrap the whole lot in one transaction.</p>
     *
     * @param conn        an open connection
     * @param contextName the context the statements are added to
     * @param objNs       the namespace of the class of the new resources
     * @param objType     the class of the new resources
     * @param objects     the properties of the resources
     */
    protected void addObjects(RepositoryConnection conn, String contextName, String objNs, String objType, PropertyBags objects) {
        Resource ctx = context(conn, contextName);
        objects.forEach(object -> createStatements(conn, objNs, objType, object, ctx));
    }

    /**
     * Create the statements of one new resource on an open connection.
     *
     * @param conn        an open connection
     * @param contextName the context the statements are added to
     * @param objNs       the namespace of the class of the new resource
     * @param objType     the class of the new resource
     * @param object      the properties of the resource
     * @return the identifier of the new resource
     */
    protected String addObject(RepositoryConnection conn, String contextName, String objNs, String objType, PropertyBag object) {
        return createStatements(conn, objNs, objType, object, context(conn, contextName));
    }

    private String createStatements(RepositoryConnection cnx, String objNs, String objType,
        PropertyBag statement,
        Resource context) {
        IRI resource;
        if (objType.equals(rdfDescriptionClass())) {
            resource = cnx.getValueFactory().createIRI("urn:uuid:" + UUID.randomUUID().toString());
        } else {
            // Identifiers stored in the triplestore are RDF:ids
            resource = cnx.getValueFactory().createIRI(namespace(cnx, "data"),
                AbstractPowsyblTripleStore.createRdfId());
        }
        IRI parentPredicate = RDF.TYPE;
        IRI parentObject = cnx.getValueFactory().createIRI(objNs + objType);
        Statement parentSt = cnx.getValueFactory().createStatement(resource, parentPredicate, parentObject);
        cnx.add(parentSt, context);
        // add rest of statements for subject
        createStatements(cnx, objNs, objType, statement, context, resource);
        return resource.getLocalName();
    }

    private void createStatements(RepositoryConnection cnx, String objNs, String objType,
        PropertyBag statement, Resource context, IRI resource) {
        List<String> names = statement.propertyNames();
        names.forEach(name -> createStatement(cnx, objNs, objType, statement, context, resource, name));
    }

    private void createStatement(RepositoryConnection cnx, String objNs, String objType,
                                        PropertyBag statement, Resource context, IRI resource, String name) {
        String property = statement.isClassProperty(name) ? name : objType + "." + name;
        String value = statement.get(name);
        IRI predicate = cnx.getValueFactory().createIRI(objNs + property);
        if (statement.isResource(name)) {
            IRI object;
            if (statement.isMultivaluedProperty(name)) {
                addMultivaluedProperty(cnx, value, resource, predicate, context);
            } else {
                if (URIUtil.isValidURIReference(value)) { // the value already contains the namespace
                    object = cnx.getValueFactory().createIRI(value);
                } else { // the value is an id, add the base namespace
                    String namespace = namespace(cnx, statement.namespacePrefix(name));
                    object = cnx.getValueFactory().createIRI(namespace, value);
                }
                Statement st = cnx.getValueFactory().createStatement(resource, predicate, object);
                cnx.add(st, context);
            }
        } else {
            Literal object = cnx.getValueFactory().createLiteral(value);
            Statement st = cnx.getValueFactory().createStatement(resource, predicate, object);
            cnx.add(st, context);
        }
    }

    private void addMultivaluedProperty(RepositoryConnection cnx, String value, IRI resource, IRI predicate, Resource context) {
        String[] objs = value.split(",");
        for (String o : objs) {
            if (!o.startsWith("urn:uuid:")) {
                o = namespace(cnx, "data") + o;
            }
            IRI object = cnx.getValueFactory().createIRI(o);
            Statement st = cnx.getValueFactory().createStatement(resource, predicate, object);
            cnx.add(st, context);
        }
    }

    /**
     * Write a materialised model to an output stream with the powsybl RDF/XML writer.
     *
     * @param model the statements to write, namespaces included
     * @param out   the stream to write to. It is closed afterwards
     */
    protected void write(Model model, OutputStream out) {
        try (PrintStream pout = new PrintStream(out)) {
            RDFWriter writer = new PowsyblWriter(pout);
            writer.getWriterConfig().set(BasicWriterSettings.PRETTY_PRINT, true);
            if (writeBySubject) {
                writeBySubject(model, writer);
            } else {
                Rio.write(model, writer);
            }
        }
    }

    private void writeBySubject(Model model, RDFWriter writer) {
        writer.startRDF();
        if (model instanceof NamespaceAware) {
            for (Namespace nextNamespace : model.getNamespaces()) {
                writer.handleNamespace(nextNamespace.getPrefix(), nextNamespace.getName());
            }
        }
        for (final Resource subject : model.subjects()) {
            // First write the statements RDF.TYPE for this subject
            // A resource may be described as an instance of more than one class
            boolean rdfTypeFound = false;
            for (final Statement st0 : model.filter(subject, RDF.TYPE, null)) {
                writer.handleStatement(st0);
                rdfTypeFound = true;
            }
            if (!rdfTypeFound) {
                String message = "subject is missing an rdfType " + subject;
                LOGGER.error(message);
                for (final Statement st : model.filter(subject, null, null)) {
                    LOGGER.error("    {} {} {}", st.getSubject(), st.getPredicate(), st.getObject());
                }
                throw new TripleStoreException(message);
            }
            // Then all the other statements
            writeSubjectStatements(model, writer, subject);
        }
        writer.endRDF();
    }

    private void writeSubjectStatements(Model model, RDFWriter writer, Resource subject) {
        for (final Statement st : model.filter(subject, null, null)) {
            if (st.getPredicate().equals(RDF.TYPE)) {
                continue;
            }
            writer.handleStatement(st);
        }
    }

    private static int statementsCount(RepositoryConnection conn, Resource ctx) {
        RepositoryResult<Statement> statements = conn.getStatements(null, null, null, ctx);
        int counter = 0;
        while (statements.hasNext()) {
            counter++;
            statements.next();
        }
        return counter;
    }

    private static void copyNamespacesToModel(RepositoryConnection conn, Model m) {
        RepositoryResult<Namespace> ns = conn.getNamespaces();
        while (ns.hasNext()) {
            m.setNamespace(ns.next());
        }
    }

    /**
     * Bind the {@code data} prefix to the base of the file that is being read.
     *
     * <p>Kept as a separate method so that a subclass whose namespaces do not live in the repository (a SPARQL
     * endpoint has no namespace store) can redirect it, see {@link #namespace(RepositoryConnection, String)}.</p>
     *
     * @param cnx  the connection the file is read through
     * @param base the base URI the parser resolves relative identifiers against
     */
    protected void addNamespaceForBase(RepositoryConnection cnx, String base) {
        cnx.setNamespace("data", base + "/#");
    }

    /**
     * The RDF4J resource that stands for a powsybl context name (a named graph).
     *
     * <p>The default maps {@code X_EQ.xml} and {@code contexts:X_EQ.xml} alike to {@code <contexts:X_EQ.xml>}.
     * A subclass that stores graphs under a different naming scheme &mdash; a model set prefix, percent-encoded
     * names &mdash; overrides this method and keeps the powsybl-side name unchanged for everybody above it.</p>
     *
     * @param conn        an open connection, used for its value factory
     * @param contextName the powsybl context name, with or without the {@code contexts:} prefix
     * @return the resource identifying the named graph
     */
    protected Resource context(RepositoryConnection conn, String contextName) {
        // Remove the namespaceForContexts from contextName if it already starts with it
        String name1 = contextName.replace(namespaceForContexts(), "");
        return conn.getValueFactory().createIRI(namespaceForContexts(), name1);
    }

    /**
     * The namespace bound to a prefix in this store.
     *
     * <p>The default asks the repository. A subclass whose backend does not keep namespaces &mdash; a SPARQL
     * endpoint &mdash; answers from its own map instead.</p>
     *
     * @param conn   an open connection
     * @param prefix the prefix to resolve, for instance {@code data}
     * @return the namespace bound to the prefix, or {@code null} when nothing is bound
     */
    protected String namespace(RepositoryConnection conn, String prefix) {
        return conn.getNamespace(prefix);
    }

    /**
     * Prepare a tuple query for evaluation.
     *
     * <p>The default just asks the connection. A subclass that has to restrict the dataset a query sees &mdash;
     * a remote store holding several model sets in one repository &mdash; sets that up here.</p>
     *
     * @param conn          an open connection
     * @param adjustedQuery the query text, prefixes already prepended by {@link #adjustedQuery(String)}
     * @return the prepared query
     */
    protected TupleQuery prepareTupleQuery(RepositoryConnection conn, String adjustedQuery) {
        return conn.prepareTupleQuery(adjustedQuery);
    }

    /**
     * The string a query result value is reported as in a {@link PropertyBag}.
     *
     * <p>The default is the plain lexical value. A subclass that renamed graphs on the way into the backend
     * translates them back here, so that graph bindings look to the conversion exactly as they do locally.</p>
     *
     * @param value the value RDF4J bound
     * @return the string to put in the property bag
     */
    protected String bindingValue(Value value) {
        return value.stringValue();
    }

    @Override
    public void addNamespace(String prefix, String namespace) {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.setNamespace(prefix, namespace);
        }
    }

    @Override
    public List<PrefixNamespace> getNamespaces() {
        List<PrefixNamespace> namespaces = new ArrayList<>();
        try (RepositoryConnection conn = repo.getConnection()) {
            RepositoryResult<Namespace> ns = conn.getNamespaces();
            while (ns.hasNext()) {
                Namespace namespace = ns.next();
                namespaces.add(new PrefixNamespace(namespace.getPrefix(), namespace.getName()));
            }
        }
        return namespaces;
    }

    private final Repository repo;
    private boolean writeBySubject = true;

    private static final boolean EXPLAIN_QUERIES = false;

    private static final Logger LOGGER = LoggerFactory.getLogger(TripleStoreRDF4J.class);
}
