# RDF database integration

This page is for a reader who knows the CGMES conversion and not the [RDF database](rdf_database.md): it shows
every place where the two meet, which type crosses the boundary, and who decides what. The conversion pages
([import](import.md), [export](export.md)) describe their own side only and link here for the database's.

Two modules are involved, and the dependency goes one way:

```{mermaid}
flowchart LR
    subgraph core["powsybl-cgmes-conversion / powsybl-cgmes-model"]
        EXP["CgmesDiffExport<br/>recorded changes to statements"]
        CAP["FastRouteCapabilities<br/>check / checkVariantSafe"]
        IMP["CgmesDiffImport<br/>apply / revert / applyToGraph"]
        CONV["TripleStoreNetworkLoader<br/>CgmesImport.convert"]
        SD["StatementDiff<br/>CgmesTripleStoreLoader"]
    end
    subgraph rdfdb["powsybl-cgmes-rdfdb"]
        SINK["RdfDbDifferenceSink"]
        CAT["SnapshotCatalog<br/>putFull / putDiff / putAsDiff"]
        VG["VersionGraph<br/>the plan query"]
        LOAD["RdfDbNetworkLoader<br/>RdfDbMaterializer"]
    end
    DB[("SPARQL database")]
    EXP --> SINK
    CAP --> SINK
    IMP --> LOAD
    CONV --> LOAD
    SD --> CAT
    SINK --> DB
    CAT --> DB
    VG --> DB
    LOAD --> DB
```

`powsybl-cgmes-rdfdb` calls a small, marked set of public types of the conversion (the table at the
[end of this page](#what-the-database-knows-about-powsybl-internals)); nothing in the conversion knows the database
exists. The two verdicts the database stores about a difference &mdash; `pdb:fastPredicatesOnly` and
`pdb:variantSafe` &mdash; are computed by the conversion from the document alone, written once, and read back by the
planner without a network.

## Export: from a recorded change to a stored snapshot

```{mermaid}
sequenceDiagram
    participant R as NetworkEventRecorder
    participant E as CgmesDiffExport<br/>(cgmes-conversion)
    participant X as RdfDbExport
    participant C as SnapshotCatalog
    participant S as RdfDbDifferenceSink
    participant F as FastRouteCapabilities<br/>(cgmes-conversion)
    participant D as Database
    R->>X: recorded NetworkEvents
    X->>E: toDifferences(network, events, ExportOptions)
    Note over E: families and blocks of the mapping,<br/>Refusal for what a receiver of changes cannot take
    E-->>X: Result (DifferenceModelSet, exported events)
    X->>C: putDiff(set, SnapshotRef)
    C->>S: accept(set) with the snapshot node
    S->>F: per difference model, in a one-member set:<br/>check(set).route() == FAST
    S->>F: checkVariantSafe(model).route() == FAST
    S->>D: one guarded INSERT ... WHERE<br/>(model nodes, forward/reverse graphs, snapshot node)
    D-->>C: written, or nothing (guard failed)
    C-->>X: SnapshotInfo
    Note over X: NetworkIdentity.advance rebuilds CgmesMetadataModels,<br/>RdfDbProvenance points at the new snapshot
```

| What crosses | Type | Decided by |
|---|---|---|
| which changes become statements, and which are refused (with a remedy) | `CgmesDiffExport.Result`, `Refusal` | cgmes-conversion (the mapping) |
| the statements of one profile, forward and reverse | `DifferenceModel` in a `DifferenceModelSet` | cgmes-conversion |
| "every statement is in a block an in-place update reads" | `FastRouteCapabilities.check(set).route() == FAST`, per difference model wrapped in a one-member set → `pdb:fastPredicatesOnly` on the difference model node | cgmes-conversion decides, rdfdb stores |
| "every property is per-variant state" (the network-dependent cases are left to apply time) | `FastRouteCapabilities.checkVariantSafe(set).route() == FAST` → `pdb:variantSafe` on the difference model node | cgmes-conversion decides, rdfdb stores |
| the address of the new snapshot, the version rule, the chain guards | `SnapshotRef`, the guarded `INSERT` | rdfdb |
| where the sending network now stands | `CgmesMetadataModels`, `RdfDbProvenance` | rdfdb writes the extension the conversion defines |

rdfdb never inspects a family, a block or an update query: the verdict crosses as a `Decision`, of which it keeps
one boolean per difference.

## Import: the diff route, the full route, and how a refusal travels back

```{mermaid}
sequenceDiagram
    participant P as caller / pypowsybl
    participant L as RdfDbNetworkLoader
    participant V as VersionGraph
    participant G as RdfDbDiffSource
    participant I as CgmesDiffImport<br/>(cgmes-conversion)
    participant M as RdfDbMaterializer
    participant T as TripleStoreNetworkLoader<br/>(cgmes-conversion)
    P->>L: update(network, db, SnapshotRef, RdfDbUpdateOptions)
    L->>V: plan: one query, both ends, every pdb:fastPredicatesOnly on the path, maxDiffChain
    alt DIFF
        L->>G: fetchById: forward / reverse graphs of the path<br/>(Graph Store GET per graph, or one SELECT ... VALUES ?g)
        Note over L: DifferenceModel.compose per profile
        L->>I: apply(network, composed, Conversion.Config, Options, reportNode)
        alt applied in place
            I-->>L: done
            L-->>P: DIFF_APPLIED
        else CgmesDiffNotApplicableException
            I-->>L: Decision (route SLOW_REQUIRED, blocking statements)
            L->>M: materialise the target
            M->>T: load(local store)
            L-->>P: FULL_RELOAD (new network) or FULL_REQUIRED, reasons = Decision.reasons()
        end
    else FULL (another scenario or authority, a slow difference, a chain too long)
        L->>M: fetch the pdb:full graphs (GraphFetcher, the shared boundary moved to the snapshot's subject base),<br/>apply the chain on a local store (CgmesDiffImport.applyToGraph)
        M->>T: load(local store): the ordinary conversion, post-processors from ImportConfig
        L-->>P: FULL_RELOAD with a new network, or FULL_REQUIRED when reloads are not allowed
    end
```

The planner decides from what was stored: a path all of whose differences carry `pdb:fastPredicatesOnly true` and
that is not longer than `maxDiffChain` is `DIFF`; a target in another scenario or of another modelling authority is
`FULL` without sending a query. The importer then has the last word on the diff route, because only it sees the
network: the subjects must exist and the groups of a block must be completable from the network (`FastRoutePlan`).
When they are not, it throws `CgmesDiffNotApplicableException` **before anything is written**, and the loader falls
back to the full route with the importer's reasons.

| What crosses | Type | Decided by |
|---|---|---|
| the composed difference per profile, and how to apply it | `DifferenceModelSet`, `Conversion.Config`, `CgmesDiffImport.Options` | rdfdb composes, the conversion applies |
| "this cannot be applied in place" | `CgmesDiffNotApplicableException.getDecision()` → `Decision.reasons()` | cgmes-conversion |
| the route and the reasons the caller sees | `UpdateResult.Route` (`NOOP`, `DIFF_APPLIED`, `FULL_RELOAD`, `FULL_REQUIRED`, `VARIANT_REFUSED`), `UpdateResult.reasons()` | rdfdb |
| a materialised state as a network | a local `TripleStoreRDF4J`, `TripleStoreNetworkLoader.load` | rdfdb fills the store, the conversion converts it unchanged |

What a caller sees. Java: the `UpdateResult` (route, reasons, statistics, the network — a new instance on
`FULL_RELOAD`), an `RdfDbException` for a request that cannot be served at all (an address the scenario does not
hold, a store of an earlier schema), an `RdfDbConflictException` for a write a guard refused. pypowsybl:
`Network.update_from_rdf_db` returns `'noop'`, `'diff'` or `'full'` (and swaps the Java network behind the Python
object on `'full'`), or `'update'` for an un-versioned scenario named without an address, where the profiles are
replaced from the stored graphs; `VARIANT_REFUSED` becomes `RdfDbVariantRefusedError` with the reasons; the reasons of a full
reload are in the report node. The `Refusal` texts of the export never reach an importer: they are the reasons a
change could not be *written*, not a difference could not be *applied*.

## Ingestion: files of one timestamp become a difference

```{mermaid}
flowchart TD
    A["instance files of one timestamp"] --> H["header-only pass: the modelling authority<br/>(address, or EQ and SSH headers)"]
    H --> PL["MaterializationPlan of the parent<br/>(VersionGraph.materialization)"]
    PL --> P["IngestParser: compared profiles in full,<br/>the others and unchanged ones header only"]
    PL --> PS["parent state as StatementDiff.Index<br/>(RdfDbMaterializer.materializeStore, cached per day)"]
    P --> B{"boundary is the scenario's?"}
    B -- no --> X["RdfDbConflictException<br/>a new boundary is a new scenario"]
    B -- yes --> TD["TripleDiffCalculator per compared profile"]
    PS --> TD
    TD --> DM["DifferenceModel (forward = files, reverse = parent)"]
    DM --> PD["putDiff: the export path from RdfDbDifferenceSink on,<br/>FastRouteCapabilities.check / checkVariantSafe on the statements"]
```

`SnapshotCatalog.putAsDiff` is the only writer that reads instance files and compares them. The comparison is
rdfdb's (`TripleDiffCalculator`, on `StatementDiff.Index`es of the conversion's statement model); the fast-route
verdict of an ingested timestamp is then computed on the *statements* exactly as for a recorded change, never on
the files.

| What crosses | Type | Decided by |
|---|---|---|
| the statements of a file, keyed by subject and property | `StatementDiff.Index` (cgmes-model) | rdfdb builds and compares them |
| the parent state as triples | a local store, `CgmesDiffImport.applyToGraph` for the differences on the way | rdfdb plans, the conversion applies to the graph |
| the verdicts of the result | as on the export side | cgmes-conversion |

## Checkpoints

```{mermaid}
flowchart LR
    C["Checkpoint.create(db, ref)"] --> M["VersionGraph.materialization:<br/>start graph and differences per profile"]
    M --> CP["COPY the start graph on the database"]
    CP --> AP["three replace operations per difference,<br/>SPARQL UPDATE on the database"]
    AP --> HD["rewrite the md:FullModel header of the copy"]
    HD --> L["pdb:full links on the existing snapshot (one INSERT)"]
```

A checkpoint uses **no** conversion code and builds no network: the three replace operations are rdfdb's own SPARQL
(the same semantics `CgmesDiffImport.applyToGraph` applies to a local store), run on the server, so a later
materialisation starts at the checkpoint. What the importer contributes to a checkpoint is the definition of what
"apply a difference to a graph" means, not the code that does it.

## Variant mode

```{mermaid}
sequenceDiagram
    participant P as caller
    participant U as VariantUpdater
    participant S as VariantScope
    participant V as VersionGraph
    participant I as CgmesDiffImport<br/>(cgmes-conversion)
    P->>U: update(..., setTargetVariant(id))
    U->>V: plan, refusing a path with pdb:variantSafe false (StoredModel.isVariantUnsafe), no network needed
    alt refused by the planner
        U-->>P: VARIANT_REFUSED, reasons, nothing written
    else
        U->>S: bind the variant: CgmesMetadataModels, case date and snapshot of that variant
        U->>I: apply with Options.setVariantSafeOnly(true) (VariantPlans.variantSafe), scoped update required
        alt NETWORK_DEPENDENT case unsafe on this network (FastRoutePlan.checkVariantSafe)
            I-->>U: CgmesDiffNotApplicableException
            U-->>P: VARIANT_REFUSED, nothing written, a created variant removed again
        else
            I-->>U: applied to the working variant only
            U-->>P: DIFF_APPLIED, binding updated
        end
    end
```

The verdict is split in two halves on purpose. The network-free half (`FastRouteCapabilities.checkVariantSafe`) is
stored as `pdb:variantSafe` when a difference is written, so that the planner can refuse a path without a network;
it blocks a statement only when **every** family that could carry it is unsafe for it. The network-aware half
(`FastRoutePlan.checkVariantSafe`, run by the importer on every variant update) decides the `NETWORK_DEPENDENT`
cases against the receiving network. A difference node written before `pdb:variantSafe` existed carries no value,
which means *unknown*: the planner proceeds optimistically and the apply-time check refuses, at the cost of one fetch
and never of a wrong result. The table of which changes stay inside a variant is in the
[RDF database page](rdf_database.md#which-changes-stay-inside-a-variant).

| What crosses | Type | Decided by |
|---|---|---|
| "stays inside one variant", without a network | `pdb:variantSafe` (from `checkVariantSafe`) | cgmes-conversion decides once, rdfdb's planner reads |
| "stays inside one variant", on this network | `CgmesDiffImport.Options.setVariantSafeOnly(true)` → `CgmesDiffNotApplicableException` | cgmes-conversion |
| where a variant stands | `VariantBinding`, swapped in and out by `VariantScope` | rdfdb |

## What the database knows about powsybl internals

The complete list is the allow-list of `MoveOutReadinessTest`
(`cgmes/cgmes-rdfdb/src/test/resources/com/powsybl/cgmes/rdfdb/allowed-core-imports.txt`): the test fails when the
module's main code names a `com.powsybl` type that is not on it, or when an entry is no longer used. The marked
surface (block 1 of the list), grouped by purpose:

| Purpose | Types | What rdfdb reads from them | What it never assumes |
|---|---|---|---|
| conversion entry | `CgmesImport` (`tripleStoreOptions`, `config`), `TripleStoreNetworkLoader` (`importer`, `describe`, `load`, `update`), `CgmesTripleStoreLoader` (`load`, `contextName`) | parse files into a store, convert a store to a network with the parameters of a file import | how the conversion queries the store |
| difference import | `CgmesDiffImport` (`apply`, `revert`, `applyToGraph`, `Options`, `Decision`, `Route`), `CgmesDiffNotApplicableException` | apply a composed difference to a network or to a graph; the reasons of a refusal | which block or group made it refuse |
| capability verdicts | `FastRouteCapabilities` (`check`, `checkVariantSafe`) | one `Route` per difference, stored as a boolean | `Family`, `FamilySpec`, blocks, update queries |
| export translation | `CgmesDiffExport` (`toDifferences`, `export`, `toString`, `variantOf`, `ExportOptions`, `Result`) | the statements of recorded changes | the mapping that produced them |
| statement model | `StatementDiff` (`Index`, `diff`, `readOnly`), `DifferenceSink` | keyed statements to compare, a sink to receive an export | — |
| triple-store transport | `TripleStoreRDF4JSparql`, `SparqlEndpoint`, `GraphStoreClient`, `ScenarioGraphNames` | talk to the endpoint, name scenario graphs | — (moves out together with rdfdb) |

Block 2 of the list is ordinary public API used as any client uses it (`Network`, the variant manager, network
events, `CgmesSubset`, the difference model types, `CgmesMetadataModels`, `ReportNode`, data sources, the import
post-processors). See [what the module depends on](rdf_database.md#what-the-module-depends-on-and-what-depends-on-it)
for the dependency rules.

## What powsybl internals know about the database

Nothing. No module of powsybl-core imports `com.powsybl.cgmes.rdfdb`, and the conversion has no type, parameter or
branch for it: the stored verdicts are produced by functions that take a `DifferenceModelSet` and nothing else, the
importer is called with the same `Conversion.Config` a file import uses, and the identity extension
(`CgmesMetadataModels`) is the one any CGMES import writes. Removing the module from the reactor leaves a reactor
that builds.
