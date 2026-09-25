/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.model.triplestore;

import com.powsybl.cgmes.model.CgmesModelException;
import com.powsybl.cgmes.model.CgmesModelReports;
import com.powsybl.cgmes.model.CgmesOnDataSource;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.triplestore.api.TripleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The first half of a CGMES import: <em>CGMES files &rarr; triple store</em>, with nothing of IIDM in sight.
 *
 * <p>Reading CGMES data and converting it to IIDM used to be one indivisible step, hidden inside
 * {@link com.powsybl.cgmes.model.CgmesModelFactory} and the CGMES importer. Splitting it in two is what makes an
 * RDF database usable as the place CGMES data lives: the files are parsed and uploaded once (this class), and every
 * later network build reads the statements back out of the database instead of the files. The second half &mdash;
 * <em>triple store &rarr; IIDM</em> &mdash; is {@code TripleStoreNetworkLoader} in {@code cgmes-conversion}.</p>
 *
 * <p>The loader is deliberately ignorant of what kind of triple store it writes into. It calls
 * {@link TripleStore#read(InputStream, String, String)} once per instance file, exactly as the file import does, so
 * a local in-memory store and a remote SPARQL endpoint see the same sequence of calls and end up with the same
 * named graphs. The only thing the caller chooses is how many files are read at once: a local store serialises its
 * writers and gains nothing from parallelism, while an upload over HTTP does.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
public final class CgmesTripleStoreLoader {

    /**
     * What a load put into the triple store.
     *
     * @param cimNamespace  the CIM namespace of the data that was read, as declared by the instance files
     * @param baseName      the base URI relative identifiers of the files were resolved against
     * @param contextNames  the {@code contexts:}-prefixed names of the graphs that were written, in the order
     *                      the files were read
     * @param boundaryLoaded whether the boundary data source had to be read because the main one carried no boundary
     */
    public record Result(String cimNamespace, String baseName, List<String> contextNames, boolean boundaryLoaded) {

        /**
         * @param cimNamespace   see {@link #cimNamespace()}
         * @param baseName       see {@link #baseName()}
         * @param contextNames   see {@link #contextNames()}
         * @param boundaryLoaded see {@link #boundaryLoaded()}
         */
        public Result {
            Objects.requireNonNull(cimNamespace);
            Objects.requireNonNull(baseName);
            contextNames = List.copyOf(contextNames);
        }
    }

    private CgmesTripleStoreLoader() {
    }

    /**
     * Read the CGMES files of a data source into a triple store, one file at a time.
     *
     * @param main     the data source holding the instance files of the model
     * @param boundary the data source to take the boundary from when the main one carries none. May be {@code null}
     * @param target   the triple store the statements are written to
     * @param reportNode where the reader reports the files it read
     * @return what was loaded, see {@link Result}
     */
    public static Result load(ReadOnlyDataSource main, ReadOnlyDataSource boundary, TripleStore target, ReportNode reportNode) {
        return load(main, boundary, target, 1, reportNode);
    }

    /**
     * Read the CGMES files of a data source into a triple store.
     *
     * <p>With {@code parallelism > 1} the instance files are parsed and written concurrently on a small pool of
     * daemon threads. That only pays off for a target whose writes leave the process &mdash; a remote store over
     * HTTP, where the EQ upload overlaps the smaller subsets. A local in-memory store has a single writer, so the
     * file import passes 1 and keeps its behaviour byte for byte.</p>
     *
     * @param main        the data source holding the instance files of the model
     * @param boundary    the data source to take the boundary from when the main one carries none. May be {@code null}
     * @param target      the triple store the statements are written to
     * @param parallelism how many files may be read at once, at least 1
     * @param reportNode  where the reader reports the files it read
     * @return what was loaded, see {@link Result}
     * @throws CgmesModelException if a file cannot be read, naming the file
     */
    public static Result load(ReadOnlyDataSource main, ReadOnlyDataSource boundary, TripleStore target, int parallelism, ReportNode reportNode) {
        return load(main, boundary, target, parallelism, reportNode, null);
    }

    /**
     * Read the CGMES files of a data source into a triple store, with the CIM namespace already known.
     *
     * <p>A caller that already holds the CIM namespace &mdash; a {@code CgmesModelTripleStore}, which was built
     * with it, and which may have taken it from the boundary because the main data source declares none &mdash;
     * passes it rather than have it sniffed again from the files.</p>
     *
     * @param main         the data source holding the instance files of the model
     * @param boundary     the data source to take the boundary from when the main one carries none, or {@code null}
     * @param target       the triple store the statements are written to
     * @param parallelism  how many files may be read at once, at least 1
     * @param reportNode   where the reader reports the files it read
     * @param cimNamespace the CIM namespace of the data, or {@code null} to read it off the files
     * @return what was loaded, see {@link Result}
     * @throws CgmesModelException if a file cannot be read, naming the file
     */
    public static Result load(ReadOnlyDataSource main, ReadOnlyDataSource boundary, TripleStore target,
                              int parallelism, ReportNode reportNode, String cimNamespace) {
        Objects.requireNonNull(main);
        Objects.requireNonNull(target);
        Objects.requireNonNull(reportNode);
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be at least 1, got " + parallelism);
        }

        CgmesOnDataSource cds = new CgmesOnDataSource(main);
        String baseName = cds.baseName();
        String namespace = cimNamespace == null ? obtainCimNamespace(main, boundary) : cimNamespace;

        List<String> fileNames = new ArrayList<>(readAll(cds, baseName, target, parallelism, reportNode));

        boolean boundaryLoaded = false;
        if (boundary != null && !hasBoundary(namespace, target)) {
            CgmesOnDataSource boundaryCds = new CgmesOnDataSource(boundary);
            fileNames.addAll(readAll(boundaryCds, baseName, target, parallelism, reportNode));
            boundaryLoaded = true;
        }
        List<String> contextNames = fileNames.stream().map(CgmesTripleStoreLoader::contextName).toList();
        return new Result(namespace, baseName, contextNames, boundaryLoaded);
    }

    /**
     * The context name a file name becomes in the triple store.
     *
     * <p>{@link TripleStore#contextNames()} answers with the {@code contexts:} prefix, so this result does too:
     * one spelling for one thing, whichever side of the split a caller is on.</p>
     *
     * @param fileName the name of an instance file
     * @return {@code contexts:} + the file name
     */
    public static String contextName(String fileName) {
        return fileName.startsWith(CONTEXTS) ? fileName : CONTEXTS + fileName;
    }

    /**
     * The CIM namespace of the data, taken from the boundary when the main data source declares none.
     *
     * <p>The same rule {@code CgmesModelFactory} applies, and it matters: a data source that holds nothing but
     * boundary files still has to be loadable.</p>
     */
    private static String obtainCimNamespace(ReadOnlyDataSource main, ReadOnlyDataSource boundary) {
        try {
            return new CgmesOnDataSource(main).cimNamespace();
        } catch (CgmesModelException e) {
            if (boundary != null) {
                try {
                    return new CgmesOnDataSource(boundary).cimNamespace();
                } catch (CgmesModelException ignored) {
                    throw e;
                }
            }
            throw e;
        }
    }

    /**
     * Whether the data already in the target store carries boundary models.
     *
     * <p>Asked through a throw-away {@link CgmesModelTripleStore} on the target, because the answer is the
     * {@code modelProfiles} query of the CGMES catalogs and nothing simpler. The model is not closed: closing it
     * would shut the store down.</p>
     */
    private static boolean hasBoundary(String cimNamespace, TripleStore target) {
        String queryCatalog = target.getOptions() == null ? "" : target.getOptions().queryCatalog();
        return new CgmesModelTripleStore(cimNamespace, target, queryCatalog).hasBoundary();
    }

    private static List<String> readAll(CgmesOnDataSource cds, String baseName, TripleStore target, int parallelism, ReportNode reportNode) {
        // Deliberately the iteration order of the data source, not a sorted copy: with parallelism 1 this method
        // is the file import, and the order files are read in decides the order statements land in a local store,
        // which decides the order query results come back in, which the CGMES conversion is sensitive to.
        List<String> names = new ArrayList<>(cds.names());
        if (names.isEmpty()) {
            return names;
        }
        // Reported here, on the calling thread: a ReportNode keeps its children in a plain list, so reporting
        // from the upload threads would race. The reader threads only read files.
        names.forEach(name -> CgmesModelReports.readFile(reportNode, name));
        if (parallelism == 1 || names.size() == 1) {
            names.forEach(name -> readOne(cds, baseName, target, name));
            return names;
        }
        readAllInParallel(cds, baseName, target, Math.min(parallelism, names.size()), names);
        return names;
    }

    private static void readAllInParallel(CgmesOnDataSource cds, String baseName, TripleStore target, int threads,
                                          List<String> names) {
        AtomicInteger threadCounter = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "cgmes-upload-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> futures = new ArrayList<>(names.size());
            for (String name : names) {
                Callable<Void> task = () -> {
                    readOne(cds, baseName, target, name);
                    return null;
                };
                futures.add(pool.submit(task));
            }
            for (Future<?> future : futures) {
                waitFor(future);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void waitFor(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CgmesModelException("Interrupted while reading CGMES files", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CgmesModelException cgmesModelException) {
                throw cgmesModelException;
            }
            throw new CgmesModelException("Reading CGMES files failed", cause);
        }
    }

    private static void readOne(CgmesOnDataSource cds, String baseName, TripleStore target, String name) {
        LOG.info("Reading [{}]", name);
        try (InputStream is = cds.dataSource().newInputStream(name)) {
            target.read(is, baseName, name);
        } catch (Exception e) {
            throw new CgmesModelException(String.format("Reading [%s]", name), e);
        }
    }

    private static final String CONTEXTS = "contexts:";

    private static final Logger LOG = LoggerFactory.getLogger(CgmesTripleStoreLoader.class);
}
