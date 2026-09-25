/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.extensions.CgmesMetadataModels;
import com.powsybl.cgmes.extensions.CgmesMetadataModelsAdder;
import com.powsybl.cgmes.model.CgmesMetadataModel;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.iidm.network.Network;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which stored model a network is "at", per CGMES profile.
 *
 * <p>A difference applies on a named model, so both ends of the exchange have to agree on which model that is. For
 * a network the answer lives in the {@code CgmesMetadataModels} extension, which the CGMES import fills from the
 * model headers of the instance files and which the difference import advances when it applies a difference. This
 * class reads it and writes it, and it is the only place in this package that does.</p>
 *
 * <p>Writing means <em>rebuilding</em> the extension, because {@code CgmesMetadataModels} has no setter: the
 * models of the profiles that do not change are copied over, the changed ones are replaced, and the extension is
 * added again. That is exactly what the conversion itself does when it reads a new set of headers.</p>
 *
 * <p><strong>One model per profile.</strong> A stored chain is a chain of one profile, so everything this package
 * does keys a model by its profile. A network that holds two models of one profile &mdash; a merged model with two
 * modelling authorities, or one imported with {@code iidm.import.cgmes.cgm-with-subnetworks} &mdash; cannot be
 * addressed that way: rebuilding the extension would silently drop one of them. Such a network is refused by
 * {@link #modelIds(Network, java.util.Set)} rather than quietly halved.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
final class NetworkIdentity {

    private NetworkIdentity() {
    }

    /**
     * The model identifier the network holds per profile.
     *
     * <p>A network holding two models of one profile keeps the last one here; the caller that has to act on the
     * identity uses {@link #modelIds(Network, Set)}, which refuses that case instead.</p>
     *
     * @param network the network
     * @return the identifiers, profiles without a model absent
     */
    static Map<CgmesSubset, String> modelIds(Network network) {
        Map<CgmesSubset, String> ids = new EnumMap<>(CgmesSubset.class);
        CgmesMetadataModels models = network.getExtension(CgmesMetadataModels.class);
        if (models != null) {
            models.getModels().forEach(model -> ids.put(model.getSubset(), model.getId()));
        }
        return ids;
    }

    /**
     * The model identifier the network holds per profile, refusing a profile it holds two models of.
     *
     * @param network the network
     * @param subsets the profiles the caller is going to act on
     * @return the identifiers, profiles without a model absent
     * @throws RdfDbException if the network holds several models of one of those profiles
     */
    static Map<CgmesSubset, String> modelIds(Network network, Set<CgmesSubset> subsets) {
        Map<CgmesSubset, List<String>> all = new EnumMap<>(CgmesSubset.class);
        CgmesMetadataModels models = network.getExtension(CgmesMetadataModels.class);
        if (models != null) {
            models.getModels().forEach(model -> all.computeIfAbsent(model.getSubset(), k -> new ArrayList<>())
                    .add(model.getId()));
        }
        Map<CgmesSubset, String> ids = new EnumMap<>(CgmesSubset.class);
        all.forEach((subset, found) -> {
            if (found.size() > 1 && subsets.contains(subset)) {
                throw new RdfDbException("The network holds " + found.size() + " " + subset.getIdentifier()
                        + " models " + found.stream().sorted().toList() + ": a stored chain versions one model per"
                        + " profile, so a network describing several of them cannot be addressed by a difference."
                        + " Load the individual grid models into scenarios of their own");
            }
            ids.put(subset, found.get(found.size() - 1));
        });
        return ids;
    }

    /**
     * Make the network say it is at the given models.
     *
     * @param network the network to change
     * @param targets the stored model the network is at, per profile. Profiles that are not named keep the model
     *                the network already holds
     */
    static void advance(Network network, Map<CgmesSubset, StoredModel> targets) {
        if (targets.isEmpty()) {
            return;
        }
        Map<CgmesSubset, Entry> entries = new LinkedHashMap<>();
        CgmesMetadataModels previous = network.getExtension(CgmesMetadataModels.class);
        if (previous != null) {
            previous.getModels().forEach(model -> entries.put(model.getSubset(), Entry.of(model)));
        }
        // A difference header need not repeat what does not change. The profile list in particular is a property of
        // the model the difference applies on, and an extension model without one cannot even be built, so what the
        // network already said about that profile is kept where the stored node says nothing
        targets.forEach((subset, model) -> entries.put(subset, Entry.of(model, entries.get(subset))));
        install(network, List.copyOf(entries.values()));
    }

    /**
     * The models the network currently says it is at, as plain values.
     *
     * <p>What it is for: a variant of a network is bound to its own stored state, and the extension that carries
     * that state is a property of the <em>network</em>. Capturing it, installing another one for the duration of an
     * operation and capturing it again afterwards is how a variant keeps an identity of its own without every
     * reader of the identity having to learn about variants (see {@code VariantScope}).</p>
     *
     * @param network the network
     * @return the entries, in the order the extension holds them
     */
    static List<Entry> capture(Network network) {
        CgmesMetadataModels models = network.getExtension(CgmesMetadataModels.class);
        if (models == null) {
            return List.of();
        }
        return models.getModels().stream().map(Entry::of).toList();
    }

    /**
     * Make the network say it is at exactly these models, replacing whatever it said before.
     *
     * @param network the network to change
     * @param entries the models, or empty to remove the extension altogether
     */
    static void install(Network network, List<Entry> entries) {
        if (network.getExtension(CgmesMetadataModels.class) != null) {
            network.removeExtension(CgmesMetadataModels.class);
        }
        if (entries.isEmpty()) {
            return;
        }
        CgmesMetadataModelsAdder adder = network.newExtension(CgmesMetadataModelsAdder.class);
        entries.forEach(entry -> {
            CgmesMetadataModelsAdder.ModelAdder modelAdder = adder.newModel()
                    .setSubset(entry.subset)
                    .setId(entry.id)
                    .setDescription(entry.description)
                    .setVersion(entry.version)
                    .setModelingAuthoritySet(entry.modelingAuthoritySet);
            entry.profiles.forEach(modelAdder::addProfile);
            entry.dependentOn.forEach(modelAdder::addDependentOn);
            entry.supersedes.forEach(modelAdder::addSupersedes);
            modelAdder.add();
        });
        adder.add();
    }

    /** One model of the extension, as plain values, so that the old and the new one are built the same way. */
    record Entry(CgmesSubset subset, String id, String description, int version, String modelingAuthoritySet,
                 Iterable<String> profiles, Iterable<String> dependentOn, Iterable<String> supersedes) {

        static Entry of(CgmesMetadataModel model) {
            return new Entry(model.getSubset(), model.getId(), model.getDescription(), model.getVersion(),
                    model.getModelingAuthoritySet(), List.copyOf(model.getProfiles()),
                    List.copyOf(model.getDependentOn()), List.copyOf(model.getSupersedes()));
        }

        /**
         * The entry a stored model becomes, falling back to what the network said about the same profile for the
         * values the stored node does not carry.
         *
         * @param previous the entry the network already held for that profile, or {@code null}
         */
        static Entry of(StoredModel model, Entry previous) {
            return new Entry(model.subset(), model.id(),
                    fallback(model.description(), previous == null ? null : previous.description),
                    model.version(),
                    fallback(model.modelingAuthoritySet(),
                            previous == null ? null : previous.modelingAuthoritySet),
                    model.profiles().isEmpty() && previous != null ? previous.profiles : model.profiles(),
                    model.dependentOn().isEmpty() && previous != null ? previous.dependentOn : model.dependentOn(),
                    model.supersedes());
        }

        private static String fallback(String value, String previous) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
            return previous == null ? "" : previous;
        }
    }
}
