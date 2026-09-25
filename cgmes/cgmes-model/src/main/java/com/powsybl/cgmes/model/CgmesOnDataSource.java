/**
 * Copyright (c) 2017-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.model;

import com.powsybl.cgmes.model.diff.DifferenceModelParser;
import com.powsybl.commons.compress.SafeZipInputStream;
import com.powsybl.commons.datasource.CompressionFormat;
import com.powsybl.commons.datasource.ReadOnlyDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;

import static com.powsybl.cgmes.model.CgmesNamespace.*;

/**
 * @author Luma Zamarreño {@literal <zamarrenolm at aia.es>}
 */
public class CgmesOnDataSource {
    private static final String LISTING_CGMES_NAMES_IN_DATA_SOURCE = "Listing CGMES names in data source %s";
    private static final String EXTENSION = "xml";

    public CgmesOnDataSource(ReadOnlyDataSource ds) {
        this.dataSource = ds;
    }

    public ReadOnlyDataSource dataSource() {
        return dataSource;
    }

    private boolean checkIfMainFileNotWithCgmesData() throws IOException {
        if (dataSource.getDataExtension() == null || dataSource.getDataExtension().isEmpty() || !dataSource.exists(null, dataSource.getDataExtension())) {
            return false;
        } else if (EXTENSION.equals(dataSource.getDataExtension())) {
            try (InputStream is = dataSource.newInputStream(null, EXTENSION)) {
                return !existsNamespaces(NamespaceReader.namespaces(is));
            }
        }
        return true;
    }

    public boolean exists() throws IOException {
        // Check that the main file is a CGMES file
        if (checkIfMainFileNotWithCgmesData()) {
            return false;
        }
        // check that RDF and CIM16 are defined as namespaces in the data source
        return existsNamespaces(namespaces());
    }

    private boolean existsNamespaces(Set<String> namespaces) {
        if (!namespaces.contains(RDF_NAMESPACE)) {
            return false;
        }
        return namespaces.contains(CIM_16_NAMESPACE) || namespaces.contains(CIM_100_NAMESPACE);
    }

    public String baseName() {
        // Get the base URI if present, else build an absolute URI from the data source base name
        return names().stream()
                .map(n -> loadInputStreamAndGetNamespace(n, NamespaceReader::base))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(() -> {
                    String name = dataSource.getBaseName().toLowerCase();
                    if (name.isEmpty()) {
                        name = "default-cgmes-model";
                    }
                    return "http://" + name;
                });
    }

    public Set<String> names() {
        try {
            // the set of names may be empty if the data source does not contain CGMES data
            Set<String> allNames = dataSource.listNames(REGEX_VALID_NAME);
            allNames.removeIf(n -> !existsInDatasource(n) || !containsValidNamespace(n));
            return allNames;
        } catch (IOException e) {
            throw new CgmesModelException(String.format(LISTING_CGMES_NAMES_IN_DATA_SOURCE, dataSource), e);
        }
    }

    private boolean existsInDatasource(String fileName) {
        try {
            return dataSource.exists(fileName);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Open one file of this data source and hand its content to a reader.
     *
     * <p>A file whose name ends in {@code .zip} is transparently entered: the reader sees the content of its single
     * entry, not the archive. This is the only way content of a CGMES data source is read, so that every reader
     * &mdash; namespace sniffing, difference model detection, difference model parsing &mdash; treats a zipped and
     * an unzipped instance file the same way.</p>
     *
     * @param name   the name of the file inside the data source
     * @param reader what to do with its content. The stream it is given is closed afterwards
     * @return whatever the reader returned
     * @throws CgmesModelException if the file cannot be opened or read
     */
    public <T> T readFile(String name, Function<InputStream, T> reader) {
        try (InputStream in = dataSource.newInputStream(name)) {
            String fileExtension = name.substring(name.lastIndexOf('.') + 1);
            if (fileExtension.equals(CompressionFormat.ZIP.getExtension())) {
                try (SafeZipInputStream zis = new SafeZipInputStream(new ZipInputStream(in), 1, 1024000L)) {
                    zis.getNextEntry();
                    return reader.apply(zis);
                }
            } else {
                return reader.apply(in);
            }
        } catch (IOException e) {
            throw new CgmesModelException(String.format(LISTING_CGMES_NAMES_IN_DATA_SOURCE, dataSource), e);
        }
    }

    private <T> T loadInputStreamAndGetNamespace(String n, Function<InputStream, T> namespaceGetter) {
        return readFile(n, namespaceGetter);
    }

    /**
     * The names of the files of this data source that hold an IEC 61970-552 difference model, sorted.
     *
     * <p>A difference model is a CGMES document like any other &mdash; it declares the RDF and a CIM namespace and
     * therefore appears in {@link #names()} &mdash; so telling the two apart needs a look at the first elements of
     * each file. That is one extra streamed read per file, stopping at the second start element.</p>
     */
    public Set<String> differenceModelNames() {
        Set<String> found = new TreeSet<>();
        for (String name : names()) {
            if (DifferenceModelParser.isDifferenceModel(dataSource, name)) {
                found.add(name);
            }
        }
        return found;
    }

    private boolean containsValidNamespace(String name) {
        Set<String> ns = loadInputStreamAndGetNamespace(name, NamespaceReader::namespacesOrEmpty);
        return ns.contains(RDF_NAMESPACE) && ns.stream().anyMatch(CgmesNamespace::isValid);
    }

    public Set<String> namespaces() {
        return names().stream()
                .map(name -> loadInputStreamAndGetNamespace(name, NamespaceReader::namespaces))
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
    }

    public String cimNamespace() {
        // If no cim namespace is found, return CIM16 namespace
        return namespaces().stream()
            .filter(CgmesNamespace::isValid)
            .findFirst()
            .orElseThrow(() -> new CgmesModelException("CIM Namespace not found"));
    }

    private final ReadOnlyDataSource dataSource;
    private static final String REGEX_VALID_NAME = ""
        // Ignore case
        + "(?i)"
        // Any number of characters from the start
        + "^.*"
        // Ending with extension .xml
        + "\\.(XML|ZIP)$";
}
