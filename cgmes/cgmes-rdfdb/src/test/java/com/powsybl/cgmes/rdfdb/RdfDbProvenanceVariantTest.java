/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.rdfdb;

import com.powsybl.cgmes.extensions.CgmesMetadataModels;
import com.powsybl.cgmes.model.CgmesSubset;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManagerConstants;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bookkeeping of the variants bound to snapshots, without a database.
 *
 * <p>Two mechanisms are asserted here, and both of them are about what happens when nobody calls this package. The
 * <strong>listener</strong> keeps the bindings in step with what a user does to the variants directly &mdash; a
 * clone inherits, an overwrite replaces, a removal drops &mdash; because a binding that outlived its variant would
 * make a later update take the snapshot of a variant that no longer exists as the state of whatever is there now.
 * The <strong>scope</strong> makes the network-level identity describe the bound variant for the duration of an
 * operation and puts the primary's back afterwards, including when the operation throws.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
class RdfDbProvenanceVariantTest {

    private static final String SCENARIO = "provenance-variants";
    private static final String TIMESTEP = "2016-01-01T08:00:00Z";
    private static final ZonedDateTime PRIMARY_CASE_DATE = ZonedDateTime.parse("2016-01-01T08:00:00Z");
    private static final ZonedDateTime BOUND_CASE_DATE = ZonedDateTime.parse("2016-01-01T09:15:00Z");

    private record Fixture(Network network, RdfDbProvenanceImpl provenance) {
    }

    /** A network that is at version {@code 1.0} of the base timestep, with nothing bound yet. */
    private static Fixture primaryAt(String version) {
        Network network = Network.create("variant-fixture", "manual");
        network.setCaseDate(PRIMARY_CASE_DATE);
        RdfDbProvenanceImpl provenance = new RdfDbProvenanceImpl(RdfDatabase.inMemory("prov-variants"), SCENARIO,
                List.of(), Instant.now(), Map.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh-" + version));
        network.addExtension(RdfDbProvenance.class, provenance);
        provenance.setSnapshot(RdfDbNames.snapshot(SCENARIO, TIMESTEP, version));
        return new Fixture(network, provenance);
    }

    private static RdfDbProvenanceImpl.BoundState state(String version, String modelId) {
        RdfDbProvenanceImpl.BoundState bound = new RdfDbProvenanceImpl.BoundState();
        bound.ref = new SnapshotRef(SCENARIO, version, TIMESTEP);
        bound.snapshotIri = RdfDbNames.snapshot(SCENARIO, TIMESTEP, version);
        bound.modelIds.put(CgmesSubset.STEADY_STATE_HYPOTHESIS, modelId);
        bound.caseDate = BOUND_CASE_DATE;
        return bound;
    }

    @Test
    void aCloneOfTheBoundPrimaryInheritsItsBinding() {
        Fixture fixture = primaryAt("1.0");
        fixture.network().getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v1");

        VariantBinding binding = fixture.provenance().variantBinding("v1").orElseThrow();
        assertEquals(RdfDbNames.snapshot(SCENARIO, TIMESTEP, "1.0"), binding.snapshotIri());
        assertEquals("1.0", binding.version());
        assertEquals(TIMESTEP, binding.timestep());
        assertEquals(VariantManagerConstants.INITIAL_VARIANT_ID, binding.clonedFrom());
        assertEquals(List.of("v1"), List.copyOf(fixture.provenance().variantBindings().keySet()));
    }

    @Test
    void aCloneOfABoundVariantInheritsThatVariantsBinding() {
        Fixture fixture = primaryAt("1.0");
        fixture.network().getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v1");
        fixture.provenance().bind("v1", state("1.1", "urn:uuid:ssh-1.1"));

        fixture.network().getVariantManager().cloneVariant("v1", "v2");
        VariantBinding binding = fixture.provenance().variantBinding("v2").orElseThrow();
        assertEquals(RdfDbNames.snapshot(SCENARIO, TIMESTEP, "1.1"), binding.snapshotIri());
        assertEquals("v1", binding.clonedFrom());
        assertEquals(Map.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh-1.1"), binding.modelIds());
    }

    @Test
    void anOverwriteReplacesTheBindingOfTheTarget() {
        Fixture fixture = primaryAt("1.0");
        fixture.network().getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v1");
        fixture.network().getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v2");
        fixture.provenance().bind("v1", state("1.1", "urn:uuid:ssh-1.1"));

        fixture.network().getVariantManager().cloneVariant("v1", "v2", true);
        assertEquals(RdfDbNames.snapshot(SCENARIO, TIMESTEP, "1.1"),
                fixture.provenance().variantBinding("v2").orElseThrow().snapshotIri());
    }

    @Test
    void removingAVariantDropsItsBinding() {
        Fixture fixture = primaryAt("1.0");
        fixture.network().getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v1");
        assertTrue(fixture.provenance().variantBinding("v1").isPresent());

        fixture.network().getVariantManager().removeVariant("v1");
        assertTrue(fixture.provenance().variantBinding("v1").isEmpty());
        assertEquals(Map.of(), fixture.provenance().variantBindings());
    }

    /** A variant that existed before the provenance was attached was never bound, and says so clearly. */
    @Test
    void aVariantOlderThanTheProvenanceIsUnboundAndRefusedWithTheReason() {
        Network network = Network.create("variant-fixture", "manual");
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "older");
        RdfDbProvenanceImpl provenance = new RdfDbProvenanceImpl(RdfDatabase.inMemory("prov-older"), SCENARIO,
                List.of(), Instant.now(), Map.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh"));
        network.addExtension(RdfDbProvenance.class, provenance);

        assertTrue(provenance.variantBinding("older").isEmpty());
        RdfDbException failure = assertThrows(RdfDbException.class,
            () -> VariantScope.enter(network, provenance, "older"));
        assertTrue(failure.getMessage().contains("is not bound to a snapshot"), failure.getMessage());
        assertFalse(provenance.lock().isLocked(), "a refused scope must not leave the lock held");
    }

    /** Removing a variant and creating it again from an unbound source leaves it unbound. */
    @Test
    void removeAndRecreateUnderTheSameIdIsUnbound() {
        Network network = Network.create("variant-fixture", "manual");
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "unbound");
        RdfDbProvenanceImpl provenance = new RdfDbProvenanceImpl(RdfDatabase.inMemory("prov-recreate"), SCENARIO,
                List.of(), Instant.now(), Map.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh"));
        network.addExtension(RdfDbProvenance.class, provenance);
        provenance.setSnapshot(RdfDbNames.snapshot(SCENARIO, TIMESTEP, "1.0"));
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v1");
        assertTrue(provenance.variantBinding("v1").isPresent());

        network.getVariantManager().removeVariant("v1");
        network.getVariantManager().cloneVariant("unbound", "v1");
        assertTrue(provenance.variantBinding("v1").isEmpty(),
                "a variant re-created from an unbound source must not keep the binding of the one it replaced");
    }

    /** A clone of a primary that is at nothing at all is unbound, not bound to nothing. */
    @Test
    void aCloneOfAnUnboundPrimaryIsUnbound() {
        Network network = Network.create("variant-fixture", "manual");
        RdfDbProvenanceImpl provenance = new RdfDbProvenanceImpl(RdfDatabase.inMemory("prov-nothing"), SCENARIO,
                List.of(), Instant.now());
        network.addExtension(RdfDbProvenance.class, provenance);

        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v1");
        assertTrue(provenance.variantBinding("v1").isEmpty());
    }

    @Test
    void theScopeSwapsTheIdentityInAndOutAgain() {
        Fixture fixture = primaryAt("1.0");
        Network network = fixture.network();
        RdfDbProvenanceImpl provenance = fixture.provenance();
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v1");
        provenance.bind("v1", state("1.1", "urn:uuid:ssh-1.1"));
        installModel(network, "urn:uuid:ssh-1.0");
        provenance.boundStates().get("v1").models =
                List.of(new NetworkIdentity.Entry(CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh-1.1", "", 2,
                        "http://elia.be/OperationalPlanning", List.of("http://profile/ssh"), List.of(), List.of()));

        try (VariantScope scope = VariantScope.enter(network, provenance, "v1")) {
            assertEquals("v1", scope.variantId());
            assertEquals("v1", network.getVariantManager().getWorkingVariantId());
            assertEquals(Map.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh-1.1"), provenance.modelIds());
            assertEquals(RdfDbNames.snapshot(SCENARIO, TIMESTEP, "1.1"), provenance.snapshot().orElseThrow());
            assertEquals("urn:uuid:ssh-1.1", modelIdOf(network));
            assertEquals(BOUND_CASE_DATE, network.getCaseDate());
        }

        assertEquals(VariantManagerConstants.INITIAL_VARIANT_ID,
                network.getVariantManager().getWorkingVariantId());
        assertEquals(Map.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh-1.0"), provenance.modelIds());
        assertEquals(RdfDbNames.snapshot(SCENARIO, TIMESTEP, "1.0"), provenance.snapshot().orElseThrow());
        assertEquals("urn:uuid:ssh-1.0", modelIdOf(network));
        assertEquals(PRIMARY_CASE_DATE, network.getCaseDate());
        assertFalse(provenance.lock().isLocked());
    }

    /** What a scope wrote while it was open belongs to its variant afterwards, not to the primary. */
    @Test
    void whatIsRecordedInsideAScopeBelongsToItsVariant() {
        Fixture fixture = primaryAt("1.0");
        Network network = fixture.network();
        RdfDbProvenanceImpl provenance = fixture.provenance();
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v1");
        installModel(network, "urn:uuid:ssh-1.0");

        try (VariantScope scope = VariantScope.enter(network, provenance, "v1")) {
            provenance.setModelIds(Map.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh-1.2"));
            provenance.setSnapshot(RdfDbNames.snapshot(SCENARIO, TIMESTEP, "1.2"));
            installModel(network, "urn:uuid:ssh-1.2");
        }

        assertEquals(Map.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh-1.0"), provenance.modelIds());
        assertEquals("urn:uuid:ssh-1.0", modelIdOf(network));
        VariantBinding binding = provenance.variantBinding("v1").orElseThrow();
        assertEquals(RdfDbNames.snapshot(SCENARIO, TIMESTEP, "1.2"), binding.snapshotIri());
        assertEquals(Map.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh-1.2"), binding.modelIds());
    }

    @Test
    void theScopeRestoresEverythingWhenTheBodyThrows() {
        Fixture fixture = primaryAt("1.0");
        Network network = fixture.network();
        RdfDbProvenanceImpl provenance = fixture.provenance();
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v1");
        installModel(network, "urn:uuid:ssh-1.0");

        assertThrows(IllegalStateException.class, () -> {
            try (VariantScope scope = VariantScope.enter(network, provenance, "v1")) {
                installModel(network, "urn:uuid:broken");
                network.setCaseDate(ZonedDateTime.parse("2000-01-01T00:00:00Z"));
                throw new IllegalStateException("boom");
            }
        });

        assertEquals(VariantManagerConstants.INITIAL_VARIANT_ID,
                network.getVariantManager().getWorkingVariantId());
        assertEquals("urn:uuid:ssh-1.0", modelIdOf(network));
        assertEquals(PRIMARY_CASE_DATE, network.getCaseDate());
        assertFalse(provenance.lock().isLocked());
    }

    /**
     * F9: a scope on the variant that is already swapped in is a no-op; a scope on a different one is refused.
     *
     * <p>Nesting happens as soon as a caller wraps {@code exportVariant} in {@code inVariant}. Without this the
     * inner scope would park the <em>outer variant's</em> identity as "the primary", and the outer close would
     * find nothing to put back.</p>
     */
    @Test
    void aNestedScopeOnTheSameVariantIsANoopAndOnAnotherIsRefused() {
        Fixture fixture = primaryAt("1.0");
        Network network = fixture.network();
        RdfDbProvenanceImpl provenance = fixture.provenance();
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v1");
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v2");
        provenance.bind("v1", state("1.1", "urn:uuid:ssh-1.1"));
        provenance.bind("v2", state("1.2", "urn:uuid:ssh-1.2"));
        installModel(network, "urn:uuid:ssh-1.0");
        provenance.boundStates().get("v1").models = List.of(new NetworkIdentity.Entry(
                CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh-1.1", "", 2,
                "http://elia.be/OperationalPlanning", List.of("http://profile/ssh"), List.of(), List.of()));

        try (VariantScope outer = VariantScope.enter(network, provenance, "v1")) {
            assertEquals("urn:uuid:ssh-1.1", modelIdOf(network));
            try (VariantScope inner = VariantScope.enter(network, provenance, "v1")) {
                assertEquals("urn:uuid:ssh-1.1", modelIdOf(network));
            }
            // The inner scope restored nothing, because it swapped nothing
            assertEquals("urn:uuid:ssh-1.1", modelIdOf(network));
            assertThrows(RdfDbException.class, () -> VariantScope.enter(network, provenance, "v2"));
        }

        assertEquals("urn:uuid:ssh-1.0", modelIdOf(network));
        assertEquals(VariantManagerConstants.INITIAL_VARIANT_ID,
                network.getVariantManager().getWorkingVariantId());
        assertFalse(provenance.lock().isLocked());
    }

    /**
     * R2: overwriting the primary is how a study variant is committed, and the network-level identity has to
     * follow the state the network now holds.
     */
    @Test
    void overwritingThePrimaryInstallsTheSourceIdentity() {
        Fixture fixture = primaryAt("1.0");
        Network network = fixture.network();
        RdfDbProvenanceImpl provenance = fixture.provenance();
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v1");
        provenance.bind("v1", state("1.1", "urn:uuid:ssh-1.1"));
        provenance.boundStates().get("v1").models = List.of(new NetworkIdentity.Entry(
                CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh-1.1", "", 2,
                "http://elia.be/OperationalPlanning", List.of("http://profile/ssh"), List.of(), List.of()));
        installModel(network, "urn:uuid:ssh-1.0");

        network.getVariantManager().cloneVariant("v1", VariantManagerConstants.INITIAL_VARIANT_ID, true);

        assertEquals("urn:uuid:ssh-1.1", modelIdOf(network));
        assertEquals(Map.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh-1.1"), provenance.modelIds());
        assertEquals(RdfDbNames.snapshot(SCENARIO, TIMESTEP, "1.1"), provenance.snapshot().orElseThrow());
        assertEquals(BOUND_CASE_DATE, network.getCaseDate());
        assertEquals("1.1", provenance.variantBinding(VariantManagerConstants.INITIAL_VARIANT_ID)
                .orElseThrow().version());
        assertFalse(provenance.variantBindings().containsKey(VariantManagerConstants.INITIAL_VARIANT_ID),
                "the primary is the network, never a key of the binding map");
    }

    /**
     * R9: the primary overwritten while a scope is open has to be restored <em>whole</em>.
     *
     * <p>Cloning inside a scope is forbidden by the class documentation, but user code running inside
     * {@code inVariant} or a user {@code NetworkListener} can reach it on the same thread. Both halves of the
     * identity &mdash; the {@code CgmesMetadataModels} extension and the provenance's own snapshot and model
     * identifiers &mdash; have to agree afterwards, or the next update plans from a state the network does not
     * hold.</p>
     */
    @Test
    void overwritingThePrimaryInsideAnOpenScopeRestoresTheWholeIdentity() {
        Fixture fixture = primaryAt("1.0");
        Network network = fixture.network();
        RdfDbProvenanceImpl provenance = fixture.provenance();
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "a");
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "b");
        provenance.bind("a", state("1.2", "urn:uuid:ssh-1.2"));
        provenance.bind("b", state("1.1", "urn:uuid:ssh-1.1"));
        provenance.boundStates().get("b").models = List.of(new NetworkIdentity.Entry(
                CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh-1.1", "", 2,
                "http://elia.be/OperationalPlanning", List.of("http://profile/ssh"), List.of(), List.of()));
        installModel(network, "urn:uuid:ssh-1.0");

        try (VariantScope scope = VariantScope.enter(network, provenance, "a")) {
            network.getVariantManager().cloneVariant("b", VariantManagerConstants.INITIAL_VARIANT_ID, true);
        }

        assertEquals("urn:uuid:ssh-1.1", modelIdOf(network));
        assertEquals(Map.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh-1.1"), provenance.modelIds());
        assertEquals(RdfDbNames.snapshot(SCENARIO, TIMESTEP, "1.1"), provenance.snapshot().orElseThrow());
        assertEquals("1.1", provenance.variantBinding(VariantManagerConstants.INITIAL_VARIANT_ID)
                .orElseThrow().version());
        assertFalse(provenance.variantBindings().containsKey(VariantManagerConstants.INITIAL_VARIANT_ID));
        // The variant the scope was on is untouched by the overwrite of the primary
        assertEquals("1.2", provenance.variantBinding("a").orElseThrow().version());
    }

    /** An unbound source leaves the primary at no snapshot at all, rather than at a stale one. */
    @Test
    void overwritingThePrimaryFromAnUnboundSourceClearsTheIdentity() {
        Network network = Network.create("variant-fixture", "manual");
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "unbound");
        RdfDbProvenanceImpl provenance = new RdfDbProvenanceImpl(RdfDatabase.inMemory("prov-overwrite"), SCENARIO,
                List.of(), Instant.now(), Map.of(CgmesSubset.STEADY_STATE_HYPOTHESIS, "urn:uuid:ssh-1.0"));
        network.addExtension(RdfDbProvenance.class, provenance);
        provenance.setSnapshot(RdfDbNames.snapshot(SCENARIO, TIMESTEP, "1.0"));
        installModel(network, "urn:uuid:ssh-1.0");

        network.getVariantManager().cloneVariant("unbound", VariantManagerConstants.INITIAL_VARIANT_ID, true);

        assertTrue(provenance.snapshot().isEmpty());
        assertEquals(Map.of(), provenance.modelIds());
        assertNull(modelIdOf(network));
    }

    /** R1: a classic in-place operation outside variant mode drops the bindings it no longer keeps true. */
    @Test
    void aClassicOperationDropsTheTrackedBindings() {
        Fixture fixture = primaryAt("1.0");
        Network network = fixture.network();
        RdfDbProvenanceImpl provenance = fixture.provenance();
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "c");
        assertTrue(provenance.variantBinding("c").isPresent());

        RdfDbNetworkLoader.classicOperationDone(network);
        assertTrue(provenance.variantBinding("c").isEmpty());

        // In variant mode the bindings are exactly what is being maintained, so nothing is dropped
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "d");
        provenance.enableVariantMode();
        RdfDbNetworkLoader.classicOperationDone(network);
        assertTrue(provenance.variantBinding("d").isPresent());
    }

    /** The primary is always bound, and its binding is the network-level identity. */
    @Test
    void thePrimaryVariantIsTheNetworkLevelIdentity() {
        Fixture fixture = primaryAt("1.0");
        VariantBinding primary = fixture.provenance()
                .variantBinding(VariantManagerConstants.INITIAL_VARIANT_ID).orElseThrow();
        assertEquals(RdfDbNames.snapshot(SCENARIO, TIMESTEP, "1.0"), primary.snapshotIri());
        assertEquals("1.0", primary.version());
        assertEquals(PRIMARY_CASE_DATE, primary.caseDate());
        assertFalse(fixture.provenance().variantBindings().containsKey(
                VariantManagerConstants.INITIAL_VARIANT_ID));
    }

    /** {@code inVariant} switches the working variant and puts the previous one back. */
    @Test
    void inVariantSwitchesAndRestores() {
        Fixture fixture = primaryAt("1.0");
        Network network = fixture.network();
        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v1");

        String seen = RdfDbProvenance.inVariant(network, "v1",
            () -> network.getVariantManager().getWorkingVariantId());
        assertEquals("v1", seen);
        assertEquals(VariantManagerConstants.INITIAL_VARIANT_ID,
                network.getVariantManager().getWorkingVariantId());
    }

    private static void installModel(Network network, String modelId) {
        NetworkIdentity.install(network, List.of(new NetworkIdentity.Entry(
                CgmesSubset.STEADY_STATE_HYPOTHESIS, modelId, "", 1, "http://elia.be/OperationalPlanning",
                List.of("http://profile/ssh"), List.of(), List.of())));
    }

    private static String modelIdOf(Network network) {
        CgmesMetadataModels models = network.getExtension(CgmesMetadataModels.class);
        return models == null ? null
                : models.getModelForSubset(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow().getId();
    }
}
