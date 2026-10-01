/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.cgmes.conversion.export;

import com.powsybl.commons.util.Result;
import com.powsybl.iidm.network.Identifiable;

import java.util.Optional;
import java.util.function.Consumer;

import static com.powsybl.commons.util.Result.success;

/**
 * What every family of the steady state hypothesis mapping reads: the export context (naming, CIM version, whether the
 * equipment model is exported), the state of the network the values are read from and who reads the description.
 *
 * <p>A family describes the CGMES objects of one kind of IIDM equipment for every export of the steady state
 * hypothesis: {@code describe*} writes an object to a sink (the full export writes straight to its document), and the
 * {@code *Updates} of a change asks the refusals of the object first and collects its description in a buffer of its
 * own. {@link CgmesChangeTranslator} dispatches a change to the family of its equipment.</p>
 *
 * @author Nico Westerbeck {@literal <nico.westerbeck at 50hertz.com>}
 */
abstract class AbstractFamily {

    final CgmesExportContext context;
    /** Which state of the network the values are read from: the current one, or the one before the change set. */
    final IidmStateView state;
    /** Who reads the description: which objects it may name and which refusals it honours. */
    final Scope scope;

    AbstractFamily(CgmesExportContext context, IidmStateView state, Scope scope) {
        this.context = context;
        this.state = state;
        this.scope = scope;
    }

    String cgmesId(Identifiable<?> identifiable) {
        return context.getNamingStrategy().getCgmesId(identifiable);
    }

    /**
     * The identifier a CGMES object the import stored as a property has in this export.
     *
     * <p>The identity naming strategy returns it unchanged; a strategy that rewrites identifiers keeps a UUID and
     * hashes anything else, exactly as it does for the identifiers of equipment.</p>
     */
    String cgmesId(String identifier) {
        return context.getNamingStrategy().getCgmesId(identifier);
    }

    String cgmesIdFromAlias(Identifiable<?> identifiable, String aliasType) {
        return context.getNamingStrategy().getCgmesIdFromAlias(identifiable, aliasType);
    }

    /** What a describe function writes, collected in a buffer of its own: the description of a change. */
    static CgmesPropertyBuffer collect(Consumer<CgmesPropertySink> description) {
        CgmesPropertyBuffer buffer = new CgmesPropertyBuffer();
        description.accept(buffer);
        return buffer;
    }

    /**
     * The description of an object for the changes scope: the refusal of the object when there is one, else what its
     * describe function writes, collected in a buffer of its own.
     */
    static Result<CgmesPropertyBuffer, String> unlessRefused(Optional<String> refusal,
                                                            Consumer<CgmesPropertySink> description) {
        return refusal.<Result<CgmesPropertyBuffer, String>>map(Result::failure).orElseGet(() -> success(collect(description)));
    }

    /**
     * Who reads what the change mapping describes: a receiver of changes, or the reader of a full steady state
     * hypothesis. It decides which objects an export may NAME (a full export writes objects the receiver does not hold yet,
     * under a generated identifier) and which refusals it honours; no value depends on it.
     */
    enum Scope {

        /** A partial SSH, a difference model, a database write: applied on top of a state the receiver holds. */
        CHANGES,
        /** A full steady state hypothesis: the whole state, read against the equipment model. */
        FULL_MODEL;

        /** Whether this scope refuses what the refusal describes: a full model does not honour a {@code changesOnly} one. */
        boolean honours(Refusal refusal) {
            return this == CHANGES || !refusal.isChangesOnly();
        }
    }
}
