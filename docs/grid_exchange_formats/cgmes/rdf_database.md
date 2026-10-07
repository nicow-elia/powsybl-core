# RDF database

A CGMES import does two things at once: it parses instance files into an RDF triple store, and it converts the
statements in that store to an IIDM network. PowSyBl can also do them separately, with a **SPARQL graph database**
in between:

```
CGMES files  ──►  RDF database  ──►  IIDM network
             (1)                (2)
```

Step (1) happens once per model. Step (2) happens whenever a network is needed, and it reads the database instead
of the files — on this machine, from another process, from another host. That is what makes a grid model a piece
of shared infrastructure rather than a directory of files, and it is the foundation the difference-based update
flows of the follow-up work packages are built on.

The second step is not a compromise for the sake of sharing: on the reference fixtures it is **faster** than
reading the files, see [Performance](#performance).

Where this module meets the CGMES conversion &mdash; which types cross, and who decides what on each flow &mdash; is
drawn on the [integration page](rdf_database_integration.md).

## Scenarios

One database holds the data of many days and many base grid models at the same time. What keeps them apart is the
**scenario**: a free-form name that says which base grid model a set of graphs belongs to — a date such as
`2026-09-18`, a business process name, a case identifier. Every operation names one:

* a scenario is **required**; there is no default and no empty scenario,
* a scenario name must not be blank, must not contain a slash and must not contain whitespace or control
  characters (it becomes one segment of a graph IRI), and is at most 128 characters long,
* anything else is allowed and is percent-encoded on the way into the database,
* `RdfDbConnection.scenarios()` lists the scenarios a database currently holds — the ones holding instance files
  and the ones holding only difference models.

A scenario is the first key of the `(scenario, modelling authority, timestamp, version)` address this family of
work packages addresses data by, and it is the one that names the **base grid model**: one scenario describes one grid, and the
difference models of [Difference models in the database](#difference-models-in-the-database) never cross from one
scenario into another. Several days of data are several scenarios in one database.

Graphs of a scenario live under `contexts:<scenario>/`, so listing, clearing and querying one scenario never
touches another. Above the triple store nothing sees this: the CGMES conversion is handed the plain
`contexts:<file name>` it expects, because the subset of a model and the boundary flag are read off the instance
file name.

## Java API

```java
// Where the database is. Nothing is opened yet.
RdfDatabase database = RdfDatabase.fuseki("http://localhost:3030/ds");

try (RdfDbConnection db = RdfDbConnection.open(database)) {

    // (1) files -> database, once per model
    ReadOnlyDataSource files = new GenericReadOnlyDataSource(Path.of("/data/igm"));
    db.loadCgmes("2026-09-18", files, null, importParameters, ReportNode.NO_OP);

    // what the scenario holds now
    db.scenarios();                 // [2026-09-18]
    db.graphs("2026-09-18");        // one GraphInfo per instance file, with its CGMES subset

    // (2) database -> network, as often as needed
    Network network = RdfDbNetworkLoader.load(db, "2026-09-18",
            NetworkFactory.findDefault(), importParameters, ReportNode.NO_OP);

    // a steady state that arrived later, applied to a network that is already in memory
    db.loadCgmes("2026-09-18-ssh-2", sshFiles, null, importParameters, ReportNode.NO_OP);
    RdfDbNetworkLoader.update(network, db, "2026-09-18-ssh-2", RdfDbLoadOptions.forUpdate(),
            importParameters, ReportNode.NO_OP);
}
```

`RdfDbNetworkLoader.loadWithStatistics` returns the same network together with a `LoadStatistics` that says where
the time went — listing the graphs, fetching them, parsing them, filling the local store, describing it and
converting. A loaded network also carries an `RdfDbProvenance` extension naming the database, the scenario and the
graphs it was built from. That extension is deliberately **not serialised**: it describes a connection to a live
system, not a property of the grid.

### The two halves on their own

The seam is not specific to databases. `CgmesTripleStoreLoader` (in `powsybl-cgmes-model`) reads CGMES files into
*any* triple store, and `TripleStoreNetworkLoader` (in `powsybl-cgmes-conversion`) converts *any* triple store
holding CGMES data to a network. The ordinary file import is literally the two of them with a local in-memory
store in between: `CgmesModelTripleStore.read` delegates to `CgmesTripleStoreLoader` with parallelism 1, so there
is one implementation of "files → store" and it cannot drift from the one the database path uses.

```java
TripleStore store = TripleStoreFactory.create(importer.tripleStoreOptions(params));
CgmesTripleStoreLoader.load(dataSource, boundary, store, ReportNode.NO_OP);
Network network = TripleStoreNetworkLoader.load(store, NetworkFactory.findDefault(), params, ReportNode.NO_OP);
```

`TripleStoreNetworkLoader.describe(store)` works out the CIM namespace, the base URI and the contexts of a store
that somebody else filled, which is what a database load needs and a data source would otherwise have provided.

## Which databases, and how they are addressed

Anything that speaks SPARQL 1.1 Query, SPARQL 1.1 Update and — for the fast path — the SPARQL 1.1 Graph Store
Protocol. Three layouts have factory methods because guessing them from one URL is not reliable:

| database | factory | query | update | graph store |
| --- | --- | --- | --- | --- |
| Apache Jena Fuseki | `RdfDatabase.fuseki("http://host:3030/ds")` | `/ds/query` | `/ds/update` | `/ds/data` |
| RDF4J server | `RdfDatabase.rdf4jServer(server, "cgmes")` | `/repositories/cgmes` | `/repositories/cgmes/statements` | `/repositories/cgmes/rdf-graphs/service` |
| GraphDB | `RdfDatabase.graphDb(server, "cgmes")` | same as RDF4J server | | |
| in this JVM | `RdfDatabase.inMemory("demo")`, or the URL `memory:demo` | — | — | — |

`RdfDatabase.parse(url)` guesses: a URL containing `/repositories/<id>` is taken for an RDF4J server or GraphDB, a
URL ending in `/query` or `/sparql` for a query endpoint whose siblings are `/update` and `/data`, anything else
for a Fuseki dataset. `memory:<name>` is the in-process backend — a real second implementation of the same
abstraction, useful for tests, for demonstrations and for users who want the split loading without running a
server. Each of its scenarios gets its own store, so the isolation is as strict as on a server.

Authentication is HTTP basic (`withCredentials`) or a custom header (`withHeader`). Nothing else is supported in
this work package.

### Docker quick start

```bash
docker run --rm -p 3030:3030 -e ADMIN_PASSWORD=admin \
    -e ENABLE_DATA_WRITE=true -e ENABLE_UPDATE=true -e ENABLE_UPLOAD=true secoresearch/fuseki
# or
docker run --rm -p 3030:3030 stain/jena-fuseki                               # create a dataset "ds" in the UI
```

The `secoresearch/fuseki` image starts read-only unless the three `ENABLE_*` variables are set, and it publishes
its dataset under `/ds/sparql` rather than `/ds/query`, hence `parse` with the query endpoint below instead of
`fuseki("http://localhost:3030/ds")`, which assumes the standard `/query`, `/update` and `/data` layout.

```java
RdfDatabase database = RdfDatabase.parse("http://localhost:3030/ds/sparql").withCredentials("admin", "admin");
```

The automated tests never use Docker: they start an embedded Fuseki on a free port.

## Query modes

| mode | what happens | when |
| --- | --- | --- |
| `LOCAL` (default) | the graphs of the scenario are fetched in bulk into a local in-memory store, and the CGMES query catalogs run there | almost always |
| `REMOTE` | the CGMES query catalogs run on the server, some eighty round trips | a thin client, or a model that should not be held in this process |

Both produce the same network. The tests of `powsybl-cgmes-rdfdb` assert that for six fixtures in `LOCAL` mode on
both backends, and for two of them (`microGridBaseCaseBE`, `miniBusBranch`) in `REMOTE` mode as well; the
demonstration adds Svedala in both modes.

`REMOTE` mode depends on one property of the server. PowSyBl's local in-memory store has a default graph that is
the *union* of all its contexts, and a large part of the CGMES query catalogs — nearly all of the `-update`
catalog — is written without any `GRAPH` clause and relies on that. A database has an empty default graph instead.
Every query is therefore sent with the SPARQL 1.1 Protocol dataset parameters `default-graph-uri` and
`named-graph-uri` listing the scenario's graphs, which both restores the union semantics and keeps one scenario's
queries out of another's data. Both parameter lists are needed: as soon as one of them is present the dataset is
exactly the one described, so without `named-graph-uri` every `GRAPH ?g` pattern would match nothing.

Fuseki honours them, which `RemoteQueryModeTest` verifies. A server that ignores them would need either query-text
rewriting with `FROM` / `FROM NAMED` clauses, or a union default graph configured server-side
(`tdb2:unionDefaultGraph true`) together with `setRemoteQueryDataset(false)` and one scenario per database.

## Performance

### Uploading (files → database)

Per instance file: the RDF/XML is parsed **on the client**, with the same RDF4J parser and the same non-fatal
settings as a local import — which is what makes the statements in the database identical to the ones a file
import produces, `rdf:ID` resolution against the base included. The statements are then serialised as N-Triples
and sent as the body of one `PUT` to the Graph Store Protocol endpoint. Files are uploaded in parallel
(`withUploadParallelism`, default 4). `PUT` replaces the graph, so re-uploading a model is idempotent rather than
cumulative.

A server without a Graph Store Protocol endpoint is served through `INSERT DATA` in transactions of 10 000
statements. That path is markedly slower and exists so that the split loading still works, not as an option to
choose.

### Loading (database → network)

1. One small query lists the graphs of the scenario.
2. `fetchParallelism` (default 4) daemon threads each fetch one graph with a single `GET`, `Accept:
   application/n-triples`, and parse the response **while it is still arriving**. N-Triples is the cheapest parser
   RDF4J has, its IRIs are absolute so nothing is resolved against a base, and the CIM namespace of the data is
   picked up on the way past, which saves a query later.
3. One writer thread — the caller — takes finished graphs in completion order and adds each one to the local store
   in a single call, inside one transaction. An RDF4J memory store has a single writer anyway; what the
   parallelism buys is the overlap between the network, the parsers and that writer.
4. The unchanged CGMES conversion runs on the local store.

Gzip applies to the **download** only: `withGzip` adds `Accept-Encoding: gzip` to the graph fetches and decodes a
compressed response. Uploads are never compressed, because a `PUT` is sent with a fixed `Content-Length` from a
buffer that is already in memory and compressing it would cost more than the transfer saves on a loopback
connection. The default is off for a loopback host and on otherwise.

### Measured

8 cores, 62 GB RAM, Java 21, loopback Fuseki, medians of ten runs after three warm-ups, milliseconds.
`a` = `Network.read` of the files; `u` = the one-off upload; `b` = `RdfDbNetworkLoader.load` with a cold cache;
`c` = the same with a warm `GraphCache`; `r` = `REMOTE` mode.

| fixture | backend | a (file) | u (upload) | b (db) | c (warm) | b/a |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Svedala, CGMES 3, 14 MB | Fuseki TxnMem | 1126 | 2533 | 430 | 307 | **0.38** |
| Svedala | memory backend | 1126 | 950 | 272 | 236 | 0.24 |
| Svedala | Fuseki TDB2 | 1061 | 5191 | 370 | 235 | 0.35 |
| Svedala | Fuseki, `REMOTE` | 1126 | — | 1675 | — | 1.49 |
| SmallGrid node-breaker, CIM16, 11 MB | Fuseki TxnMem | 462 | 1588 | 310 | 220 | **0.67** |
| SmallGrid node-breaker | memory backend | 462 | 372 | 186 | 174 | 0.40 |
| SmallGrid node-breaker | Fuseki TDB2 | 442 | 4548 | 320 | 189 | 0.72 |
| SmallGrid node-breaker | Fuseki, `REMOTE` | 462 | — | 1343 | — | 2.91 |
| MicroGrid BE, CIM16, 1.9 MB | Fuseki TxnMem | 47 | 75 | 40 | 31 | 0.85 |
| MicroGrid BE | memory backend | 47 | 20 | 26 | 26 | 0.55 |
| MicroGrid BE | Fuseki TDB2 | 44 | 1628 | 38 | 29 | 0.86 |
| MicroGrid BE | Fuseki, `REMOTE` | 47 | — | 266 | — | 5.66 |

The phase split of the Svedala load on Fuseki TxnMem: listing the graphs 6 ms, fetching and parsing the five
graphs 413 ms (of which 360 ms of N-Triples parsing, summed over the four fetch threads), filling the local store
169 ms, describing it 15 ms, converting 170 ms. The file import spends 898 ms of its 1126 ms filling a local store
from RDF/XML, which is exactly the part the database path replaces &mdash; and replaces with something cheaper,
because N-Triples needs no XML parser, resolves no relative identifiers, and is parsed four graphs at a time.

The upload is the price of all this, and it is paid once per model: 2533 ms for Svedala against a Fuseki TxnMem
dataset, 5191 ms against TDB2, which has to index what it is given. The client-side half of that is the same
RDF/XML parse a file import does.

`RdfDbLoadBenchmarkTest` produces this table and fails the build if a database load ever costs more than 1.5 times
a file import on the primary backend.

### Caching

`withCache(new GraphCache())` keeps parsed graphs for the next load, which removes the fetch and the parse
entirely. It is **off by default**, and that is a correctness decision: as long as a scenario's graphs can be
replaced in place, a cached copy may be stale. The cache therefore checks the statement count of a graph before it
trusts an entry, which catches a graph that was reloaded with different content but not an edit that keeps the
count. `trustImmutableGraphs(true)` drops even that check, for a database whose graph IRIs identify a version of
the data.

## Difference models in the database

The database does not only hold the instance files of a scenario. A change recorded on a network — see
[difference model export](export.md#difference-model-export-from-recorded-changes) — can be **stored** in the scenario as a difference, and a network
can be **brought to** any stored state of it. The same `(scenario, version)` addressing works for a producer
publishing a stream of changes and for a consumer following it.

A scenario holds one full model per CGMES profile, the instance file that was uploaded, and a **linear chain of
differences** on top of it, one per profile. `md:Model.Supersedes` is that chain: a difference supersedes the model
it applies on. The newest model of a profile is its **head**.

Differences never cross scenarios. A difference applies on a model of its own scenario, an export from a network
that was loaded from another scenario is refused, and an update towards another scenario is a full reload of that
scenario. The reason is that a model identifier means nothing without a scenario: the same instance file uploaded
into two scenarios gives two independent models with two independent chains.

### Storing difference models

```java
Network network = RdfDbNetworkLoader.load(db, "2026-09-18", null, params, reportNode);
NetworkEventRecorder recorder = new NetworkEventRecorder();
network.addListener(recorder);
network.getLoad("load-1").setP0(12.5);
network.removeListener(recorder);

RdfDbExport.Result result = RdfDbExport.export(network, recorder.getEvents(), db, "2026-09-18",
        new CgmesDiffExport.ExportOptions());
StoredModel stored = result.get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
```

`RdfDbExport.export` translates the recorded changes exactly as a file export does and hands the result to
`RdfDbDifferenceSink`, the `DifferenceSink` of the database. Each difference becomes two immutable named graphs —
the state after the change and the state before it — and one node in the metadata graph of the scenario.

Three rules are enforced, and they are enforced **by the write itself** rather than by a check before it, because
two clients recording changes on the same base model is normal and a check-then-write would let both of them
through:

1. a difference supersedes **exactly one** model, which is stored in this scenario and describes the same profile;
2. that model has **no successor** yet, so the chain of a profile stays linear;
3. the identifier of the difference is **new**.

All three are `FILTER` conditions of a single guarded `INSERT … WHERE` request that also carries the data and the
metadata, so a reader sees either nothing or a complete, referenced difference, and the second writer on the same
base gets an `RdfDbConflictException` naming the rule it broke. There is no locking and no retry: the loser loads
the head and records its change again.

A difference larger than `RdfDbDifferenceSink.SINGLE_REQUEST_MAX_STATEMENTS` (20 000 statements) uploads its data
graphs first and then sends the guarded request with the metadata alone. That is safe for the same reason the
design is: a data graph no node refers to is invisible to every reader, and it is dropped again when the guard
refuses. `ModelCatalog.orphanGraphs()` lists the ones a crash left behind.

**The sender is advanced.** After a successful export the network's `CgmesMetadataModels` — and its
`RdfDbProvenance` — say it is at the difference it just wrote. A file export leaves them alone, which is right
there: a document is handed to somebody else and the sender has not changed. A database export is different,
because the difference *is* the newest version of that profile now; without advancing the sender, a second export
from the same network would supersede the same base again, fork the chain and be refused.

### Updating a network from the database

```java
UpdateResult result = RdfDbNetworkLoader.update(network, db, "2026-09-18", DiffTarget.head(),
        new RdfDbUpdateOptions(), params, reportNode);
if (result.isReplacement()) {
    network = result.network();   // a full reload answers with a NEW network instance
}
```

The decision is made before anything is read:

1. **Scenario.** A network that belongs to another scenario is a reload, and no query is sent to decide that.
2. **Identity.** Which stored model the network is at, per profile: its `RdfDbProvenance` if it has one, otherwise
   its `CgmesMetadataModels`. A network with neither is an error.
3. **Chain.** One query reads the chain of the target and the chain of the network's own model, for every profile
   at once. If the network sits on the chain of the target, the differences between the two are the path — walked
   *forwards* when the target is newer, and *backwards*, undoing them, when the target is older.
4. **Applicability.** A path longer than `maxDiffChain` (200 by default), or holding a difference that states a
   property no update query reads (`pdb:fastPredicatesOnly false`), cannot be applied in place. So does a
   difference the importer itself refuses, which is asked before anything is changed.
5. **Fetch, compose, apply.** One query fetches every graph of the path; the differences of a profile are folded
   into one and applied to the network *in place* by the difference importer.

`UpdateResult.route()` says what happened, and `UpdateResult.reasons()` says why the difference route was not
taken. The four routes are:

| Route | What it means |
|---|---|
| `NOOP` | the network is already at the target; nothing was read and nothing was changed |
| `DIFF_APPLIED` | the differences were composed and applied in place; `network()` is the instance handed in |
| `FULL_RELOAD` | the target was materialised from the database; **`network()` is a new instance** |
| `FULL_REQUIRED` | a reload would have been needed and `allowFullReload` is off; the network is untouched |

A full reload never touches the network that was handed in, which is why it answers with a replacement:
`isReplacement()` says so, and the caller has to swap its references. `RdfDbUpdateOptions.setAllowFullReload(false)`
turns it into `FULL_REQUIRED` for a caller that holds references into the network and would rather decide itself.

`RdfDbNetworkLoader.load(db, scenario, target, …)` builds a network at a stored state directly. It **materialises**
it: the instance file graphs of the scenario are transferred into a local in-memory store — from the
[cache](#caching) when they are already there — the differences on the way to the target are applied to that store
as plain RDF, and the result is handed to the unchanged CGMES conversion. The network that comes out is the network
the instance files of that version would have produced. `load(db, scenario, …)` without a target means the head of
the scenario as soon as it holds a difference.

### Metadata graph (schema v1)

Every scenario owns one mutable graph, its **metadata graph**, and that graph is the index of everything else: a
client never computes the IRI of a data graph, it reads it from a model node.

| Thing | IRI |
|---|---|
| metadata graph of a scenario | `http://powsybl.org/rdfdb/<scenario>/meta` |
| full model graph, unversioned upload (`loadCgmes`) | `contexts:<scenario>/<file name>` (the upload names it) |
| full model graph, versioned upload (`putFull`) | `http://powsybl.org/rdfdb/<scenario>/graph/<model id>` (`RdfDbNames.fullGraph`) |
| forward graph of a difference | `http://powsybl.org/rdfdb/<scenario>/graph/<model id>/forward` |
| reverse graph of a difference | `http://powsybl.org/rdfdb/<scenario>/graph/<model id>/reverse` |

The two full-model forms are not interchangeable. Only graphs under `http://powsybl.org/rdfdb/…/graph/…` and
`…/materialized/…` are **immutable** and therefore cache-trusted (`RdfDbNames.isImmutableGraph`): a versioned
scenario writes each instance file once, under its model identifier, and never again. A `contexts:` graph belongs
to a scenario filled by `RdfDbConnection.loadCgmes` without a version (Python `load_cgmes` without one), and a
re-upload of that scenario replaces it, so the cache re-validates it rather than
trusting the name.

Scenario names and model identifiers are percent-encoded in an IRI; the raw scenario name is also stored as a
literal (`pdb:scenario`), so a listing never has to decode anything.

The vocabulary is `pdb: <http://powsybl.org/ns/rdfdb#>` for everything that is about the *storage*, and the model
description terms of IEC 61970-552 (`md:`, `dm:`) **verbatim** for everything that is about the model, so that a
reader who knows CGMES can read the metadata graph without knowing PowSyBl.

| Term | Meaning |
|---|---|
| `pdb:kind` | `pdb:Full` (an uploaded instance file) or `pdb:Diff` (a recorded difference) |
| `pdb:subset` | the profile: one of the nine CGMES subsets `EQ`, `SSH`, `TP`, `SV`, `EQ_BD`, …, or a custom name (see [Profiles are names](#profiles-are-names-custom-ones-are-stored-whole)) |
| `pdb:scenario` | the raw scenario name |
| `pdb:graph` | the named graph of a full model |
| `pdb:forwardGraph`, `pdb:reverseGraph` | the named graphs of a difference |
| `pdb:fastPredicatesOnly` | whether every property the difference states is one an update query reads |
| `pdb:variantSafe` | whether every statement of the difference writes per-variant IIDM state (see "`pdb:variantSafe`, and stores written before it existed" under variant mode) |
| `pdb:capabilities` | the capability version of the writer of a difference, `<12 hex>/<core version>` (`FastRouteCapabilities.version()`, e.g. `8638764b83d6/7.5.0-SNAPSHOT`): the first twelve hex digits of the SHA-256 of the canonical text of the capability table, and the powsybl-core version. A reader trusts the two verdicts above when it equals its own or names an older core version, and re-checks the statements otherwise ([integration page](rdf_database_integration.md#import-the-diff-route-the-full-route-and-how-a-refusal-travels-back)) |
| `pdb:tripleCount` | how many statements the model holds, forward plus reverse for a difference |
| `pdb:subjectBase` | the IRI prefix the subjects carry before `_<mRID>`, e.g. `http://microgrid/#` |
| `pdb:cimNamespace` | the CIM namespace the properties live in |
| `pdb:chainDepth` | how many differences lie between this model and the full model it descends from |
| `pdb:created` | when the node was written |
| `md:Model.*` | the header of the model, as its instance file or its difference document carries it |

```turtle
@prefix pdb: <http://powsybl.org/ns/rdfdb#> .
@prefix md: <http://iec.ch/TC57/61970-552/ModelDescription/1#> .
@prefix dm: <http://iec.ch/TC57/61970-552/DifferenceModel/1#> .

# graph <http://powsybl.org/rdfdb/2016-01-01/meta>
<urn:uuid:ssh-1> a md:FullModel ; pdb:kind pdb:Full ; pdb:subset "SSH" ; pdb:scenario "2016-01-01" ;
    pdb:graph "contexts:2016-01-01/MicroGridTestConfiguration_BC_BE_SSH_V2.xml" ; pdb:chainDepth 0 ;
    pdb:subjectBase "http://microgridtestconfiguration_bc_be_v2/#" ; md:Model.version "2" .
<urn:uuid:ssh-d2> a dm:DifferenceModel ; pdb:kind pdb:Diff ; pdb:subset "SSH" ; pdb:scenario "2016-01-01" ;
    pdb:forwardGraph <http://powsybl.org/rdfdb/2016-01-01/graph/urn%3Auuid%3Assh-d2/forward> ;
    pdb:reverseGraph <http://powsybl.org/rdfdb/2016-01-01/graph/urn%3Auuid%3Assh-d2/reverse> ;
    md:Model.Supersedes <urn:uuid:ssh-1> ; md:Model.version "3" ; pdb:fastPredicatesOnly true ;
    pdb:variantSafe true ; pdb:capabilities "8638764b83d6/7.5.0-SNAPSHOT" ; pdb:tripleCount 4 ; pdb:chainDepth 1 .

# graph <http://powsybl.org/rdfdb/2016-01-01/graph/urn%3Auuid%3Assh-d2/forward>
<http://microgridtestconfiguration_bc_be_v2/#_load-1> cim:EnergyConsumer.p "12.5" ; cim:EnergyConsumer.q "5" .
```

The statements of a difference are written as ordinary RDF, in the namespace and under the subject prefix of the
model they apply on, so a query over the base graph and a forward graph together sees **one** subject rather than
two. An identifier that is already absolute (`urn:`, `http:`) is used as it is; everything else is a CGMES master
resource identifier and becomes `subjectBase + "_" + id`, which is exactly the IRI the RDF/XML reader produced for
`rdf:ID="_<id>"` in the instance file. `pdb:graph` is a **string**, not an IRI, because a CGMES file name is not
always writable as one — the CGMES 3 Svedala fixture has a space in every file name.

Consistency rules, each of which has a test:

* stored data graphs are **immutable**: no API rewrites a forward or reverse graph, and the cache is allowed to
  trust them without checking (a `dropTimestamp` followed by a re-ingestion of the same files is the one way an IRI
  comes back; see the limitations);
* a difference supersedes exactly one stored model of the same profile, and its CIM namespace is the one of that
  model; a `md:Model.DependentOn` the scenario does not hold is reported as a **warning**, not refused — a steady
  state difference may legitimately depend on an equipment model that was never uploaded;
* the chain of a profile is **linear**: a stored model has at most one successor;
* duplicate model identifiers are rejected **within a scenario**; the same identifier in two scenarios is two
  independent nodes;
* `pdb:chainDepth` is the base depth plus one, and it is what orders a chain — never the model version string.

### Performance of the difference flows

Medians of ten runs after three warm-ups, 8 cores, Java 21, loopback Fuseki (TxnMem) and the in-process backend,
milliseconds. `e1`/`e500` are the export of one and of up to five hundred changed objects, split into translating
the events and writing them; `u1`/`u10` bring a network over a chain of one and of ten differences, split into
planning, fetching and composing (this layer) and applying (the difference importer); `f` materialises the head of
a ten-difference chain; `a` is `Network.read` of the same files.

```
fixture                            backend         e1:tr  e1:wr  e500:tr  e500:wr     u1    u10 u10:pl u10:fe u10:co u10:ap  f(cold)  f(warm)  a(file)
svedala(CGMES3,14MB)               fuseki-txnmem       0     22        3       56     16     21      9      5      0      4      394      335     1147
svedala(CGMES3,14MB)               memory              0      1        2       10      5      5      0      0      0      3      232      217     1147
microGridBaseCaseBE(CIM16,1.9MB)   fuseki-txnmem       0     11        0       13     12     18      8      4      0      3       49       44       53
microGridBaseCaseBE(CIM16,1.9MB)   memory              0      0        0        1      4      5      0      0      0      3       26       25       53
```

How many requests each flow sends is counted, not assumed: `RdfDbRequestCountTest` reads them off the request log
of the embedded server and asserts the bounds below.

* **A write is three requests**, whatever the size of the difference set: one `SELECT` resolving every model the
  write touches together with whatever supersedes it, the guarded `INSERT … WHERE` that carries the data, the
  metadata and the rules, and one `SELECT` reading the nodes back. 112 changed objects of Svedala (about 1 100
  statements in both directions) cost 56 ms against 22 ms for a single change, so the payload is the larger half.
  The 50 ms target is on the edge and depends on how warm the JVM is: 56 ms in a run of this benchmark alone,
  34 ms in a run of the whole module, where the target is met. The 500 ms bound is a factor of eight away.
* **An update is two requests.** The first reads the metadata graph of the scenario, and everything the planner
  wants to know — the head of every profile, the chain of the target, the chain of the model the network holds —
  is arithmetic on that list; the second fetches the statements of every difference on the path at once. A chain
  of ten costs 21 ms against 16 ms for a single difference: what an update pays for is not the length of the chain.
* **Applying is the importer's cost** (4 ms here) and is reported separately rather than gated.
* **A materialisation is two requests plus one transfer per instance file**, and it is a full CGMES load. On
  Svedala the plain database load of the same fixture is 430 ms cold and 307 ms warm, so the ten differences add
  about ten milliseconds and everything else is the fetch, the local store and the conversion. The differences of a
  profile are folded into one before they are applied, so a chain costs one pair of SPARQL requests on the local
  store rather than one pair per difference. A warm materialisation beats a file import on both fixtures: 335 ms
  against 1147 ms on Svedala, 44 ms against 53 ms on MicroGrid BE (40 against 42 ms in a whole-module run).

The numbers above are a run of this benchmark on its own, which is the pessimistic one: in a run of the whole
module, with the JVM warm, every one of them is roughly half.

## Versioning: snapshots, modelling authorities, timestamps and versions

Everything above versions *one profile at a time*: a difference supersedes a model and the chain of that profile
grows. That is enough to move a network forward, and not enough to say "load version 2", because a version of a
grid model is a state of *every* profile at once. The versioning layer adds the node that says so.

### A version is a resource, not a tag

A version is a `pdb:Snapshot` node plus immutable named graphs. The objects of the model carry no version property
at all.

The alternative — tagging every object — would need reification or RDF-star, a version filter in all eighty-one
catalog queries of the CGMES conversion (a forked catalog to maintain, and a slower one), and it would make the
data mutable. Named graphs leave the queries untouched, make each graph cacheable by its IRI and free of read
concurrency, and map one to one onto CGMES itself: `md:Model.Supersedes` is the chain of a profile,
`md:Model.DependentOn` the dependency between profiles, `md:Model.modelingAuthoritySet` the tree a model belongs
to, and `md:Model.scenarioTime` the timestamp. What the snapshot adds is the cross-profile consistency unit and
its address.

### The four keys and the profile projection

A snapshot is addressed by **`(scenario, modellingAuthority, timestamp, version)`**, unique in a database, and
`SnapshotRef` is that address.

| Key | Java type | What it is | `null` on a read | `null` on a write |
|---|---|---|---|---|
| `scenario` | `String`, non-blank | the base grid model — in practice **one day** | **refused**: there is no default scenario and no "latest scenario" anywhere in this API | refused |
| `modellingAuthority` | `String`, non-blank | the tree the snapshot is stored under, normally the `md:Model.modelingAuthoritySet` of its equipment and steady state hypothesis files, verbatim, for instance `http://elia.be/CGMES/2.4.15` | **refused**, naming the authorities the scenario holds: guessing would load another TSO's grid | taken from the EQ and SSH headers of what is written (`putFull`, `putAsDiff`, `putDiff`); refused when neither states one (a difference of the state variables alone must name its authority), and naming the authorities found per profile, when they disagree. An explicit authority is taken whatever the files state |
| `timestamp` | `java.time.Instant`, second precision | the moment, equal to `md:Model.scenarioTime` of the snapshot's members | the base timestamp of that authority's tree, which is its root's | the same; `putFull` takes the steady state file's scenario time |
| `version` | `String`, a name registered in the scenario's version registry (`"DA"`, `"ID"`, `"RT"`, or `"1"`, `"2"`, …) | the position in the chain of one timestamp, ordered by the name's **rank** | the head of that chain; a named version means the snapshot of that moment ranking **highest at or below** it (`exact` for that name only) | the lowest registered name ranking above the head's (the lowest registered one for a root or a new timestamp); a permissive registry appends a generated name when there is none |

```java
SnapshotRef.of("2016-01-01", "http://elia.be/CGMES/2.4.15", Instant.parse("2016-01-01T08:30:00Z"), "ID");
SnapshotRef.of("2016-01-01", mas, OffsetDateTime.parse("2016-01-01T09:30:00+01:00").toInstant(), "ID"); // the same
SnapshotRef.of("2016-01-01", mas, instant, "RT").exactly(); // RT and nothing below it
SnapshotRef.latest("2016-01-01", mas);            // base timestamp, head
SnapshotRef.latestAt("2016-01-01", mas, instant); // that timestamp, head
```

The timestamp is an `Instant`, so a naive or unknown time zone cannot occur in the Java API at all: an instant is
only built by saying where it is. The one place a zone-less text is still read is the `md:Model.scenarioTime` of a
CGMES header, which the MicroGrid conformity files write without a zone; it is read as UTC. There are no labels and
no per-scenario offset: rendering a moment in a local zone is the caller's.

The version is a **name**, and names are ordered by the scenario's version registry (next section): inside the
chain of one timestamp a new version **ranks above** the head it is written on, and names may be sparse — a chain
`DA → RT` that never had an `ID` is fine. A read at a named version means the snapshot of that moment whose version
ranks **highest at or below** it: asking for `RT` at a timestamp that only reached `ID` reads `ID`, and asking for
`DA` there reads `DA`. `SnapshotRef.exactly()` asks for that name and nothing else, and is absent (refused by
`require` and the loads with the usual *"holds no snapshot (…, =RT)"*) when the moment does not carry it; a name
the registry does not hold names nothing. `exact` is part of the address (its fifth component, `exact()`), writes
ignore it. The CGMES header's own `md:Model.version` stays what it is, a per-document counter, and is not tied to
the snapshot version.

**Profiles are not a key.** What a snapshot covers is a property of the stored state —
`SnapshotInfo.profiles()`, the keys of its `pdb:state` — and, on every operation, the caller's **projection**:
which profiles to load (`RdfDbNetworkLoader.load(db, ref, profiles, …)`), which to compare when a day is ingested
(`putAsDiff(…, profiles, …)`), which to store at the root (`putFull(…, profiles, …)`), which an update matches the
network by (`RdfDbUpdateOptions.setProfiles`; the FULL route of an update still loads every profile of the target).
Making them a key would give one state two addresses. The defaults of the three writes differ on purpose: `putFull`
stores every profile the files carry, `putDiff` writes the profiles of its difference set, and `putAsDiff` compares
the equipment model and the steady state hypothesis (the other profiles are inherited from the parent).

**One snapshot is stored under one modelling authority; the files it carries may come from several.** A realistic
IGM is such a set: its equipment and topology come from the TSO's modelling tool, its state variables from the
merging agent that ran the power flow (`CGMES_Full.zip` of pypowsybl states `powsybl.org`, `http://elia.be/CGMES`
and `http://tennet.nl/CGMES`). Name the authority on the write and the files are stored under it whatever they
state; leave it open and the equipment and steady state hypothesis headers decide, refused with *"… state the
modelling authorities {EQ=…, SSH=…, SV=…}, and the equipment and steady state hypothesis members do not agree on
one: pass the modelling authority in the address"* when they disagree. Into a scenario of **one tree** an open
authority is that tree, and files whose equipment and steady state hypothesis agree on another authority are
refused with *"… state modelling authority X but the scenario's only tree is Y: pass Y in the address to store them
under it, or X to open a second tree"*.

Several days in one database are **several scenarios**. Inside a scenario every modelling authority owns **one
tree** with exactly one root; a second root of the same authority is refused. All trees of a scenario live in its
one metadata graph and **share its boundary**.

### Profiles are names; custom ones are stored whole

A profile is a **name**, `[A-Z][A-Z0-9_]*`. The nine CGMES subsets are constants of `Profiles` (`Profiles.EQ`,
`SSH`, `TP`, `SV`, `DY`, `DL`, `GL`, `EQ_BD`, `TP_BD`; `Profiles.STANDARD` in the order of `CgmesSubset`,
`Profiles.BOUNDARY` the two boundary ones), and every map and projection of the API is keyed by the name
(`Set<String>`, `Map<String, …>`, listed in `Profiles.ORDER`: the nine first, then the custom ones by name). A
projection with a malformed name is refused (`Profiles.check`).

Any other name is a **custom profile**: an operational configuration, a market overlay, a TSO's own CIM
extension — a file shipped beside the instance files that the CGMES conversion does not read. The profile of a
file is read off its name: the standard subset the conversion recognises (`…_EQ_…`, `…_SSH.`, `_BD` for the
boundary) first, otherwise the **last `_TOKEN` before the extension**, when it is `[A-Z][A-Z0-9]*` and not
version-like: `Grid_OP.xml` holds `OP`, `MicroGrid_BC_BE_CFG.xml` holds `CFG`. A file that names no profile —
`Grid.xml`, `Grid_SC_V2.xml` — is refused by `putFull` and `putAsDiff` with *"cannot tell the profile of 'X': name
the file <base>_<PROFILE>.xml, …"*; an unversioned upload (`loadCgmes`) uploads it and leaves it unregistered,
with a warning. Like every CGMES file, a custom one must declare the RDF and a CIM namespace, or the data source
does not list it, and it must carry an `md:FullModel` header to be a member of a snapshot.

A custom profile follows four rules:

1. **it is always stored whole**: a `md:FullModel` node with `pdb:kind pdb:Full` and `pdb:subset "CFG"`, its
   statements one immutable graph — never a difference, so never `pdb:fastPredicatesOnly` or `pdb:variantSafe`;
2. `putFull` stores it with the other files of the root; `putAsDiff` stores it only when the projection **lists**
   it, as a new member of the difference snapshot with a `pdb:full` link (not a checkpoint: `hasFull()` counts the
   standard profiles only), and only when the file is not the model the parent already states. An unlisted custom
   profile is inherited from the parent, like `TP` and `SV`, and reported as ignored;
3. **it is never part of the network**: a load fetches only the standard profiles into the store the conversion
   reads (whose queries read every graph of the store, so a foreign graph there would be read too), and the
   provenance names only the graphs it transferred;
4. **it is handed to the caller as a graph**: `RdfDbNetworkLoader.loadWithStatistics(…).extraProfiles()` names
   the graph of every custom profile of the snapshot (profile → graph IRI, as the metadata graph records it),
   `SnapshotCatalog.graphsOf(ref)` names it for any snapshot without a load (two requests: the address, the state
   models; a standard profile whose state is still its instance file is listed too, one whose state is a
   difference is not), and `RdfDbConnection.fetchGraph(scenario, graphIri)` reads the statements (one Graph Store
   request, or none when the graph cache holds it).

```java
SnapshotCatalog catalog = db.snapshots("2016-01-01");
catalog.putAsDiff(filesOf1100, null, SnapshotRef.latestAt("2016-01-01", mas, at1100),
        Set.of(Profiles.EQ, Profiles.SSH, "CFG"), importParams, reportNode);   // CFG stored whole
LoadResult loaded = RdfDbNetworkLoader.loadWithStatistics(db, SnapshotRef.latestAt("2016-01-01", mas, at1100),
        null, null, importParams, reportNode);
List<Statement> cfg = db.fetchGraph("2016-01-01", loaded.extraProfiles().get("CFG"));
```

A projection on a load may name a custom profile (`{EQ, SSH, CFG}` returns `CFG` in `extraProfiles()`); one that
does not leaves it out. An update between snapshots walks past a whole member: it is the custom profile's new
state, not a step to apply.

### The version registry

Every scenario holds a **version registry**: one `pdb:Version` node per name, with its `pdb:rank` (an integer,
sparse: an appended name gets the highest rank plus 10) and optionally `pdb:transient true`. A snapshot stores the
*name* of its version, never a rank: every comparison joins the name to its registry node, so a rerank rewrites a
few nodes and not the history. `SnapshotCatalog.registry()` returns it as a `VersionRegistry`:

```java
VersionRegistry registry = db.snapshots("2016-01-01").registry();
registry.create(List.of("DA", "ID", "RT"), false);   // ranks 10, 20, 30; strict
registry.add("RT2");                                 // 40, above every other
registry.insert("ID2", "ID");                        // 25, the midpoint after ID
registry.rerank(Map.of("RT2", 35));                  // refused if a version chain would lose its order
registry.rename("RT2", "late");                      // only a name no snapshot carries
registry.markTransient("ID2", true);
registry.delete("ID2");                              // a transient name goes with its (leaf) snapshots
registry.names(); registry.rank("ID"); registry.isPermissive(); registry.rev();
```

**Strict or permissive.** `create(names, permissive)` makes a **strict** registry unless asked otherwise: a write
under a name it does not hold is refused with *"version 'X' is not registered in scenario 'S' (registry: [DA 10,
ID 20, RT 30]); register it or write into a permissive scenario"*. A **permissive** one appends the unknown name
above every other on its first write. A scenario whose first root is written without a registry **bootstraps a
permissive one** with the root's name at rank 10 — which is what keeps a caller who never heard of the registry
working with `"1"`, `"2"`, … in the order it writes them.

**What a write takes.** A named version that is registered must rank above the head's version of the same
timestamp, else *"version '20' (rank 20) is not above the parent '30' (rank 30) of (S, A, T): a new version ranks
above the head it is written on"*. No name means the lowest registered name ranking above the head's — for a root
or a new timestamp, the lowest registered name; when there is none, a strict registry refuses (*"no version of the
registry ranks above 'RT' (rank 30) in scenario 'S' …"*) and a permissive one appends a generated name, the
smallest number above the registry's size that is not a name yet (`"2"` after `"1"`). A timestamp root's version is
not compared with the snapshot it hangs off: that one belongs to another timestamp.

**Edits.** `add`, `insert` (refused *"no rank between 'A' (20) and 'B' (21) …: rerank first"* when no integer is
left), `rerank` (accepted when, for every snapshot written as the next version of another, the new rank of its
version stays above its parent's — the refusal names the first such pair; `pdb:TimestampEdge`s are not ordered by
rank), `rename` and `delete` of a name no snapshot carries (refused naming the snapshot that carries it: the
snapshot IRI carries the name), `delete` of a **transient** name together with the snapshots that carry it, which
must all be leaves (refused naming the child otherwise), and `markTransient`.

**The revision.** The schema node carries `pdb:rev`, 1 when the registry is created and one more on every edit.
Every edit is one request guarded on it plus one read-back; an edit that lost a race is a `RdfDbConflictException`
that changed nothing. The registry is read **in the same request as the schema check** and cached per catalogue,
so no listing or read costs more than before; every snapshot write is guarded on the name at the rank it was
checked against and on the revision, so a registry edited through another connection makes the write refuse, and
the writer re-reads the registry and retries (at most three times; *"the version registry of scenario 'S' changed
(rev 3 → 4) …"*). An appended name costs one guarded request and no read-back: the snapshot write that follows is
guarded on it. The append drops the cached registry instead of assuming it won, so the next question to the
registry reads it again (one request) and never answers from a registry the store did not have. **A read uses the cache only at the store's revision**: a read at a named version takes the names
ranking at or below it, with their ranks, from the cache and hands them to its query as `VALUES`, together with
the revision they were read at (`FILTER EXISTS { <schema> pdb:rev 3 }`). A stale cache therefore binds nothing;
the reader then re-reads the registry (one request, on a miss only) and, if the revision moved, asks again. A
stale cache costs a request, never a wrong snapshot. The listings join the rank of each snapshot in SPARQL.

### Writing snapshots

```java
try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.sparql("http://localhost:3030/ds"))) {
    SnapshotCatalog catalog = db.snapshots("2016-01-01");

    // The root of one TSO's tree: authority and timestamp from the files, version "1" of a permissive registry
    SnapshotInfo be = catalog.putFull(belgium, boundary, SnapshotRef.latest("2016-01-01", null), null,
            new Properties(), ReportNode.NO_OP);
    // Another authority of the same day: same boundary, its own tree
    SnapshotInfo nl = catalog.putFull(netherlands, boundary, SnapshotRef.latest("2016-01-01", null), null,
            new Properties(), ReportNode.NO_OP);

    // A study run on the Belgian grid, recorded on a network and stored as its next version
    RdfDbExport.export(network, events, db, SnapshotRef.latest("2016-01-01", be.modellingAuthority()),
            new CgmesDiffExport.ExportOptions(), ReportNode.NO_OP);

    catalog.modellingAuthorities();      // [http://elia.be/CGMES/2.4.15, http://tennet.nl/CGMES/2.4.15]
    catalog.snapshots();                 // the history, by authority, oldest first
    catalog.verify();                    // the invariants below, re-checked
}
```

The rules. Every one a concurrent writer could break is enforced by the guard of the write itself rather than by a
check before it; rules 2 and 7 are properties of the files and are checked on them before anything is written (rule 2
with the first-root guard behind it):

1. one root per `(scenario, modellingAuthority)` (`putFull` twice for one authority is a conflict — another day is
   another scenario);
2. **one boundary per scenario**: the first root uploads the boundary models (its full models of `EQ_BD` and `TP_BD`); every
   later root must carry the very same boundary model identifiers, links the stored graphs into its own state and
   uploads nothing of them; a root with another boundary is refused with *"a new boundary is a new scenario"*.
   The stored boundary keeps the subject base of the first root's files; a load, an update or an ingestion of a
   later authority moves its IRIs to that authority's base while fetching it, and the stored graphs are never
   rewritten. A direct SPARQL query on the server (or the `REMOTE` query mode) that joins a later authority's graphs
   with the boundary therefore has to apply the same rewrite: the two do not meet by IRI;
3. a graph of `…/<scenario>/graph/` is written once and never overwritten;
4. a difference must supersede exactly what the parent snapshot states for its profile — the head of its
   timestamp, or the pin of a new one — otherwise the writer is told where that is: *"update the network to the
   head and re-record"*;
5. the chain is linear — a second child along a `pdb:VersionEdge` is refused with *"the linear scheme allows no
   forks"*;
6. versions only grow, by rank: *"version '20' (rank 20) is not above the parent '30' (rank 30) of (…): a new
   version ranks above the head it is written on"*; the rank is checked against the cached registry, and the
   guard of the write requires the name registered at the rank that was checked and the registry at its revision
   (with the parent still the head, an edited registry is all that could make the check stale);
7. a snapshot describes one moment: a member stating another `md:Model.scenarioTime` is refused (its members may
   state other modelling authorities: the snapshot is stored under the one its address names);
8. nothing crosses a scenario, and nothing but the shared boundary crosses a modelling authority: every
   `pdb:parent`, `pdb:member`, `pdb:state` and `pdb:full` of a snapshot points inside its scenario, and a parent is
   always of the same authority;
9. a new timestamp hangs off its **pin**, any snapshot of another timestamp of its tree: by default the latest
   rollover at or before it for an ingestion and, for a recorded change, the snapshot the recording network is at
   (else the one snapshot stating what the differences supersede; several with different states are refused) ([Timestamps](#timestamps-and-one-tree-per-modelling-authority)); a pin is refused for a
   timestamp that exists;
10. nothing is written into a timestamp before the scenario's **archive cutoff**
    ([Archiving](#archiving-the-states-before-a-cutoff)): the head lookup of the write is a read, and is refused like
    one.

Concurrent writers: the guard decides, the loser gets a `RdfDbConflictException` naming the rule, and there is no
retry. Writers of two authorities never conflict — every guard is scoped by the authority — with one exception:
two *first* roots of an empty scenario. Each checked its boundary against no root at all, so the guard of a first
root also requires that the scenario still has no root; the loser gets the conflict, and retried it is a second root
whose boundary is compared with the winner's. Writers of two scenarios never touch the same graph.

### A CGM is a query, and a load

"Every modelling authority at this moment" is one query over the scenario's metadata graph, not a stored node:

```java
Map<String, SnapshotInfo> cgm = db.snapshots("2016-01-01").assembly(instant, null);  // the head of each authority
```

A named version is taken per authority at or below it: `assembly(t, "ID")` is `{BE: ID, NL: DA}` when NL never
reached `ID` at that moment. An authority with no snapshot at that moment (or none at or below that version) is
absent from the map, not refused: compare its
keys with `modellingAuthorities()` to see which (pypowsybl's `assembly()` shows such an authority as a row with no
snapshot). The shared boundary is in the `pdb:state`
of every entry. A stored assembly is deliberately not written — nothing would read it, and a wrong one stored is
worse than none.

Loading the CGM as **one network** is a load-time argument, not a stored thing either:

```java
LoadResult cgm = RdfDbNetworkLoader.loadComposed(db,
        SnapshotRef.of("2016-01-01", null, instant, "ID"),      // the moment: no authority
        List.of("http://elia.be/CGMES/2.4.15", "http://tennet.nl/CGMES/2.4.15"),   // precedence order
        null,           // owned: the trees a write-back goes to; null = the first authority
        null,           // profiles: null = every standard profile
        null, importParameters, ReportNode.NO_OP);
```

* **Resolution.** Every authority is resolved as an ordinary load resolves it — its own tree, the timestamp (the
  base timestamp of its tree for `null`), the latest version at or below the named one — and all of them in **one**
  chain query. An authority without a snapshot at the moment is **refused, naming it** (*"modelling authority
  'http://tennet.nl/CGMES/2.4.15' holds no snapshot at (…), and a common grid model of […] needs every one of them;
  nothing was loaded"*): a CGM with a missing IGM is not a CGM. `assembly` stays the query that reports absence.
* **One store.** Every authority's graphs go into one local store under context names of their own
  (`a<i>_model<j>_<profile>.xml`), the **boundary once** (the first authority's; every authority must name the same
  boundary models, otherwise refused), and **one subject base**: the first authority's. Relative CGMES
  identifiers resolve against the base of the files they were parsed from and the conversion joins by IRI, so every
  other authority's graphs are rebased on the way in, by the same mapping that rebases a shared boundary for a single
  load. Each authority's differences are applied to its own graphs.
* **First wins.** Then one SPARQL update per later authority on the local store removes every statement of its
  graphs whose subject and property an earlier authority's graphs state
  (`DELETE { GRAPH ?gi { ?s ?p ?o } } WHERE { VALUES ?ge { … } VALUES ?gi { … } GRAPH ?ge { ?s ?p ?x }
  GRAPH ?gi { ?s ?p ?o } }`). The order of `authorities` is the only composition rule; there is no other.
* **The flat conversion.** The store is handed to the unchanged conversion, which pairs the tie lines on the boundary
  nodes exactly as for the assembled files: MicroGrid BE + NL compose into the network of the assembled CGM files,
  XIIDM-identical but for the network identifier (one of the EQ model identifiers, as for the files). Every
  authority's state variables are read.
* **Provenance.** `RdfDbProvenance.composition()` lists the composed snapshots in precedence order, `owned()` the
  trees a write-back goes to, and `ownerOf(mRID)` the authority of each object: the first one whose graphs type it
  (the boundary's objects belong to the first). The map has one entry per typed object — 1 212 for MicroGrid BE+NL,
  linear in the size of the grid. `snapshot()` is empty (no single snapshot) and `modelIds()` is the first
  authority's. Custom profiles are not composed.
* **Write-back.** `RdfDbExport.export(network, events, db, SnapshotRef.of(scenario, null, timestamp, version), …)`
  translates the recorded changes once, routes every statement to the tree that owns its subject, and writes one
  snapshot into each **owned** tree it touches (one `putDiff` per tree), superseding that tree's composed state and
  hanging off its composed snapshot; the composition entries are advanced, so the next export grows the same
  chains. The result is the first written tree's; `composition()` names every written snapshot. A change on an
  object of a tree the network does not own refuses the whole export before anything is written: *"the change on
  <mRID> belongs to modelling authority 'NL…', which this composed network does not own (owned: [BE…]); nothing was
  written"*. The two writes are two requests, not one transaction: every address is resolved before the first one,
  so only a write conflict on the second tree can leave the first written.
* **Read-only for the in-place routes.** `update` (every form, with or without a target variant), `exportVariant`,
  `exportPerVariant` and the appending `export(…, scenario, …)` refuse a composed network: *"network … is a
  composition of […]: composed networks are read-only for the diff and variant routes; reload it with
  RdfDbNetworkLoader.loadComposed"*. Another moment is another composition.
* **Requests.** One schema check, one chain query, one model read, the graphs with the boundary once and one
  statement fetch for all differences: **19** requests for MicroGrid BE + NL at their roots against **26** for the
  two separate loads (`RdfDbRequestCountTest`; bound `2 + graphs(n) − 2(n − 1) + 2n`).

### Reading a version, and the one query that decides how

```java
Network n = RdfDbNetworkLoader.load(db, SnapshotRef.of("2016-01-01", mas, null, "ID"), null, params, reportNode);
Network ssh = RdfDbNetworkLoader.load(db, ref, Set.of(CgmesSubset.EQUIPMENT, CgmesSubset.STEADY_STATE_HYPOTHESIS),
        null, params, reportNode);                                       // a profile projection
UpdateResult r = RdfDbNetworkLoader.update(n, db, SnapshotRef.of("2016-01-01", mas, null, "RT"), options, params, rn);
```

`update` sends **one** query. A `UNION` binds the two ends — the snapshot the network is at (from its provenance,
or matched by the model identifiers it carries) and the snapshot the caller asked for, resolved by its address in
the same query (an open timestamp joins the root of the authority's tree, an open version excludes every snapshot
with a version successor, a named version is a sub-select of the snapshots of that moment ranking at or below it,
ordered by the joined rank and limited to one) — and a `pdb:parent*` property path walks each of them up to the
root. The client then
takes the deepest snapshot both sides reached as the lowest common ancestor and reads the path off the two chains:
up from A, each difference *inverted*, then down to B, each one forward.

| Answer | When | What the caller does |
|---|---|---|
| `NOOP` | the network is already at the target | nothing |
| `DIFF` | every difference on the path is fast-route capable and the path is no longer than `maxDiffChain` (200) | they are fetched in one request, folded per profile and applied in place |
| `FULL` | a difference states a property no in-place update reads, the path is too long, the two have no common ancestor, or **the network belongs to another scenario or another modelling authority** | the network is rebuilt at the target, and the result carries a new instance |

The cross-scenario and the cross-authority cases cost **no query at all**: a snapshot IRI carries its scenario and
its authority, so `"network is at scenario 'A', target is scenario 'B': diffs never cross scenarios"` and
`"network is at modelling authority 'A', target is 'B': diffs never cross modelling authorities"` are decided by
string arithmetic. Walking from the last timestamp of one day to the first of the next is therefore a full reload,
by design — it keeps every chain bounded and lets a database hold as many days as it likes.

A worked example on the chain A("1") → B("2") → C("3") → D("4"): `plan(A, B)` is one forward step; `plan(D, C)` is
one inverted step; `plan(A, D)` is `FULL` when C states something the fast route cannot apply, and the reason names
C's difference; `plan(D, "2")` is two inverted steps.

### Materialisation and checkpoints

A load materialises: the full graphs go into a local in-memory store (through the cache — a versioned graph is
immutable, so it is trusted by default), the differences on the way are applied to it as plain RDF, and the
unchanged CGMES conversion runs on the result. Which graph a profile starts from is decided **per profile**: each
one walks up the chain until it finds an ancestor whose `pdb:full` lists a model of that profile. A load with a
profile projection walks only the profiles it names.

That is what makes a checkpoint useful without breaking anything:

```java
Checkpoint.create(db, SnapshotRef.of("2016-01-01", mas, null, "50"));
```

It copies one graph per profile the chain touched, applies the differences to the copies **on the database** with
the same three replace operations the client uses, rewrites the header of each copy to the state model it now
holds, and adds `pdb:full` to the *existing* snapshot &mdash; one `INSERT`, because those links **are** the
statement "a materialisation may start here" and there is no boolean beside them to keep in step. No new root: the
chain stays connected, every earlier version stays reachable, and depth keeps counting from the root. A
materialisation of that snapshot afterwards has zero differences to apply, and one deeper down starts at the
checkpoint.

When to run it: `UpdatePlan.checkpointRecommended()` says so once the distance to the nearest snapshot with full
graphs passes `RdfDbUpdateOptions.setCheckpointAfter` (100 by default). As a rule of thumb, once every hundred
versions, or once per timestamp. It is idempotent and is not on any hot path.

### Metadata graph (schema v4)

Schema v1 above, plus the snapshot nodes, the version registry and the schema marker. Every v1 model node stays
valid and is read unchanged. Schema 4 differs from schema 3 in the version: a snapshot's `pdb:version` is a
registered name (a plain string) where it was an `xsd:integer`, the `pdb:Version` nodes and `pdb:rev` /
`pdb:permissive` (and, when set, `pdb:archiveCutoff` / `pdb:archiveLocation`) on the schema node are new, a snapshot
may carry `pdb:rollover true`, a difference node carries `pdb:capabilities`, and a `pdb:TimestampEdge` may point at
any snapshot of the tree instead of one of the base chain.

```turtle
@prefix pdb: <http://powsybl.org/ns/rdfdb#> .
@prefix md:  <http://iec.ch/TC57/61970-552/ModelDescription/1#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
@prefix be:  <http://powsybl.org/rdfdb/2016-01-01/http%3A%2F%2Felia.be%2FCGMES%2F2.4.15/snapshot/2016-01-01T00%3A00%3A00Z/> .
# graph <http://powsybl.org/rdfdb/2016-01-01/meta>

<http://powsybl.org/rdfdb/2016-01-01/schema> pdb:schema 4 ; pdb:scenario "2016-01-01" ;
    pdb:rev 2 ; pdb:permissive true .   # the registry's revision: 1 at the bootstrap, +1 per edit
# setArchiveCutoff(…) adds, until cleared:  pdb:archiveCutoff "2016-01-01T12:00:00Z"^^xsd:dateTime ;
#                                           pdb:archiveLocation "s3://grid-archive/2016-01-01"

# the version registry: one node per name, its rank the only order versions have
<http://powsybl.org/rdfdb/2016-01-01/version/1> a pdb:Version ; pdb:name "1" ; pdb:rank 10 ;
    pdb:scenario "2016-01-01" ; pdb:created "2026-10-07T12:00:00Z"^^xsd:dateTime .
<http://powsybl.org/rdfdb/2016-01-01/version/2> a pdb:Version ; pdb:name "2" ; pdb:rank 20 ; … .

be:1 a pdb:Snapshot ; pdb:scenario "2016-01-01" ; pdb:modellingAuthority "http://elia.be/CGMES/2.4.15" ;
    pdb:timestamp "2016-01-01T00:00:00Z"^^xsd:dateTime ; pdb:version "1" ; pdb:kind pdb:Full ; pdb:depth 0 ;
    pdb:timestampRoot be:1 ; pdb:rollover true ;   # every root is a rollover
    pdb:member <urn:uuid:eq-1>, <urn:uuid:ssh-1>, <urn:uuid:eqbd> ;   # what this snapshot adds
    pdb:state  <urn:uuid:eq-1>, <urn:uuid:ssh-1>, <urn:uuid:eqbd> ;   # what a reader is at once it reaches it
    pdb:full   <urn:uuid:eq-1>, <urn:uuid:ssh-1>, <urn:uuid:eqbd> .   # where a materialisation may start

<urn:uuid:eqbd> a md:FullModel ; pdb:subset "EQ_BD" ; pdb:snapshot be:1 .   # + the v1 terms; the profile makes it the boundary

be:2 a pdb:Snapshot ; pdb:modellingAuthority "http://elia.be/CGMES/2.4.15" ;
    pdb:timestamp "2016-01-01T00:00:00Z"^^xsd:dateTime ; pdb:version "2" ; pdb:kind pdb:Diff ;
    pdb:parent be:1 ; pdb:edge pdb:VersionEdge ; pdb:depth 1 ;
    pdb:timestampRoot be:1 ; pdb:member <urn:uuid:ssh-d2> ;
    pdb:state <urn:uuid:eq-1>, <urn:uuid:ssh-d2>, <urn:uuid:eqbd> .

<urn:uuid:ssh-d2> a dm:DifferenceModel ; pdb:subset "SSH" ; pdb:snapshot be:2 ;
    md:Model.Supersedes <urn:uuid:ssh-1> ; md:Model.modelingAuthoritySet "http://elia.be/CGMES/2.4.15" ;
    md:Model.scenarioTime "2016-01-01T00:00:00Z" .   # + the v1 terms

# putAsDiff(…, {EQ, SSH, CFG}, …) of a version that also ships a new custom profile file: stored whole
be:3 a pdb:Snapshot ; pdb:version "3" ; pdb:kind pdb:Diff ; pdb:parent be:2 ; … ;
    pdb:member <urn:uuid:ssh-d3>, <urn:uuid:cfg-3> ;
    pdb:state <urn:uuid:eq-1>, <urn:uuid:ssh-d3>, <urn:uuid:eqbd>, <urn:uuid:cfg-3> ;
    pdb:full <urn:uuid:cfg-3> .                     # the custom profile's state, no checkpoint
<urn:uuid:cfg-3> a md:FullModel ; pdb:kind pdb:Full ; pdb:subset "CFG" ; pdb:snapshot be:3 ;
    pdb:graph "http://powsybl.org/rdfdb/2016-01-01/graph/urn%3Auuid%3Acfg-3" .   # + the v1 terms

# a timestamp root: pdb:TimestampEdge to its pin, here a version of the base flagged by rollover(…)
<http://powsybl.org/rdfdb/2016-01-01/http%3A%2F%2Felia.be%2FCGMES%2F2.4.15/snapshot/2016-01-01T12%3A15%3A00Z/1>
    a pdb:Snapshot ; pdb:timestamp "2016-01-01T12:15:00Z"^^xsd:dateTime ; pdb:version "1" ; pdb:kind pdb:Diff ;
    pdb:parent be:2 ; pdb:edge pdb:TimestampEdge ; pdb:depth 2 ; … .
be:2 pdb:rollover true .

# the root of a second authority links the same boundary model and uploads nothing of it
<http://powsybl.org/rdfdb/2016-01-01/http%3A%2F%2Ftennet.nl%2FCGMES%2F2.4.15/snapshot/2016-01-01T00%3A00%3A00Z/1>
    a pdb:Snapshot ; pdb:modellingAuthority "http://tennet.nl/CGMES/2.4.15" ; … ; pdb:state <urn:uuid:eqbd>, … .

# after Checkpoint.create(db, SnapshotRef.of("2016-01-01", "http://elia.be/CGMES/2.4.15", null, "2")):
be:2 pdb:full <http://powsybl.org/rdfdb/2016-01-01/materialized/http%3A%2F%2Felia.be%2FCGMES%2F2.4.15/…/2/SSH>,
    <urn:uuid:eq-1>, <urn:uuid:eqbd> .
```

The snapshot IRI is `http://powsybl.org/rdfdb/<scenario>/<authority>/snapshot/<ISO instant>/<version name>`, every
segment percent-encoded (a version name may hold `/` and `:`), and `RdfDbNames.refOf` reads all four keys back off
it without a request. The IRI is minted once, which is why a name a snapshot carries cannot be renamed. A registry
node is `http://powsybl.org/rdfdb/<scenario>/version/<name>` (`RdfDbNames.versionNode`). A second
scenario repeats the whole structure under `http://powsybl.org/rdfdb/<other scenario>/`, with no edge of any kind
between the two.

**No migration.** A scenario whose metadata graph has snapshots but no `pdb:schema 4` — in particular one written
by the earlier `(scenario, timestep, version)` schema, recognisable by its `pdb:Catalog` node or its `pdb:timestep`
keys — is refused at the first read or write that touches it:

> scenario 'S' was written by the (scenario, timestep, version) schema of an earlier release (a pdb:Catalog node);
> this release reads only stores of schema 4, addressed by (scenario, modelling authority, timestamp, version).
> There is no migration: clear the scenario (RdfDbConnection.clear) and re-ingest it

A graph carrying another schema number — a store of schema 3, whose versions are integers, in particular — is
refused the same way (*"carries pdb:schema 3, and this release reads only stores of schema 4: read it with the
release that wrote it, or clear the scenario and re-ingest it"*). `RdfDbConnection.clear(scenario)` still works on such a scenario: it is the way out.
The earlier schema lived inside one unreleased change, and re-ingesting a day costs minutes.

A snapshot node carries no boolean that repeats what its links already say:

* the fast-route capability is stored **once**, on the difference model (`pdb:fastPredicatesOnly`). The `fast`
  column of a snapshot listing — `SnapshotInfo.fast()` in Java, the `fast` column of `db.snapshots(...)` in
  Python — is *derived*: the conjunction over the difference members of the snapshot, read in the same request
  that returns the snapshot, and a snapshot with no difference member is `true`;
* whether a materialisation may start at a snapshot is *having* a `pdb:full` link of a standard profile.
  `SnapshotInfo.hasFull()` and the `has_full` column are computed from the rows the listing already returns; the
  whole graph of a custom profile does not count.

The typed literals (`xsd:dateTime` of the key, `xsd:integer` of the depth, the rank and the revision) are written in
one lexical form and matched as constants and compared numerically on both backends, which
`RdfDbSparqlSemanticsTest` asserts — including the rank joined by name under a `FILTER`, the sub-select ordered by
it, and an edit guarded on the revision.

`pdb:graph` is a **string literal**, not an IRI: the in-process backend keeps the graphs of an unversioned
scenario under the plain instance file name, and a CGMES file name is not always writable as an IRI — the CGMES 3
Svedala fixture has a space in every one of them.

### Performance of the versioning flows

Medians of ten runs after three warm-ups, eight cores, Java 21, loopback Fuseki and the in-process backend,
milliseconds, on MicroGrid BE with a chain of fifty differences
(`RdfDbVersioningBenchmarkTest`; `p10` is the same plan query with ten scenarios in the database):

```
backend    p1  p10  p1'   u1   u10   u50  u50:plan u50:fetch u50:compose u50:apply  l(cold) l(warm) a(file)  ck  l(after ck)
fuseki     40   36   34   20    26    57      31        6          3         5        160     107      73   120     97
memory      7    5    5    6     8    10       4        0          0         4         60      54      71    62     49
```

`p1'` is the plan query measured a second time with the ten scenarios present, so that the comparison with `p10`
is between two medians of the same warm state.

* **A plan is one request.** The rows that say which differences lie on the path also say what those differences
  are — their graphs, their subject base, their CIM namespace, whether an in-place update can read them — so
  nothing has to be looked up afterwards. Asserted by `RdfDbRequestCountTest`.
* The plan query **does not grow with the database**: ten scenarios of a hundred snapshots each change nothing,
  because the path walk starts at a bound node inside one scenario's metadata graph. The build asserts it at the
  20 % the measurement spreads by.
* An update over fifty differences costs about as much as one: the differences travel in a single request and are
  folded into one per profile before they are applied.
* **The version registry costs no request on a read.** Its rows come with the schema check, once per catalogue;
  a read joins the rank in the query it already sends. A write under a registered name stays at seven requests; the
  first write under a name a permissive registry does not hold yet is one more (the guarded append), and the next
read of the registry one more (the append dropped the cache); an edit of
  the registry is two (the guarded edit, the read-back). Asserted by `RdfDbRequestCountTest`.
* **A named version is resolved from the cached registry.** "The highest rank at or below `v`" hands the query
  the names ranking at or below `v` with their ranks as `VALUES` (and the revision they are valid at), so the
  query looks up those names at the moment and keeps the highest — one request, like an exact address or the
  head. At the extreme of `ScaleVersioningBenchmarkTest` — two hundred versions of one timestamp — planning to a
  named version takes 0.8 ms at depth 1 and 16 ms at depth 200 in process, and 4.2 ms and 127 ms on loopback
  Fuseki, measured in the module build — the level of the exact addresses before the registry (0.8 / 9.3 ms and
  3.9 / 100 ms). An earlier form that joined the registry node of every snapshot of the moment took 8.6 / 81 ms
  and 10 / 136 ms.
* **Timestamps.** Ingesting a new timestamp from files is at most nine requests (the default pin is one query and
  replaced the second head lookup); a rollover of a one-profile timestamp eight (its checkpoint, the flag and the
  read-back); `changesBetween` three; `dropTimestamp` five (the listing, the members, the guarded drop of the nodes
  and its read-back, the graphs). Asserted by `RdfDbRequestCountTest`.
* **An archive cutoff costs a read nothing**: it rides in the schema check and is a filter of the query a read
  sends anyway; setting it is two requests (asserted), a refusal one more (the re-read that names the location).
* Planning, fetching and composing a fifty-difference chain stays well under 100 ms on both backends (40 ms on
  Fuseki, 4 ms in process), which is the bound the build asserts.
* **Target missed: the plan query at chain depth fifty.** It was budgeted at 10 ms and takes 31 ms on loopback
  Fuseki. What is left after the second request was removed is the path query itself: about twenty-five
  predicate-object rows for each of the fifty snapshots on the chain. It is 2 ms at depth 1 and 10 ms at depth 10,
  so the chains a scenario actually accumulates are inside the target and only the fifty-difference worst case is
  not — and that is exactly what a checkpoint shortens, because the plan then reads one snapshot instead of fifty.
  The 50 ms hard bound holds; the in-process backend does the same work in 7 ms.
* **Target missed: a warm versioned load against a file import.** A load at depth fifty takes **107 ms against
  73 ms** for reading the same model from files on loopback Fuseki, and **54 ms against 71 ms** in process. The
  difference is what a file import does not have to do: nine graph transfers over HTTP and fifty local difference
  applications. A checkpoint takes it to 97 ms. The build keeps a loose bound (three times a file import) as a
  guard against an order-of-magnitude regression and logs `TARGET MISSED` with these numbers rather than quietly
  rewriting the target.
* A checkpoint of a fifty-difference chain costs 120 ms on Fuseki, about what one materialisation costs, and takes
  the load from 107 ms to 97 ms.

## Timestamps, and one tree per modelling authority

A day is not one grid state but ninety-six of them, and a study run is another dimension on top of each. Both live
inside the tree of one modelling authority, and they are not the same kind of key:

```
scenario "2016-01-01"   (one metadata graph, one boundary)               scenario "2016-01-02"
  authority BE                                     authority NL             ...no edge of any kind...
  00:00 *1 ──── 2 ──── 3                           00:00 *1 ──── 2           the same structure again
         │      │                                         │
         │      └── 08:30  1 ── 2                         └── 08:30  1
         ├── 08:15  1
         └── 12:00 *1 ── 2         (a rollover: the equipment of the day moved at noon)
                │
                ├── 12:15  1
                └── 12:30  1

  *  a rollover (pdb:rollover true), checkpointed: every root, and what a writer flags
  │  pdb:TimestampEdge from a timestamp root down to its pin; ── pdb:VersionEdge across

  assembly(08:30, null) = { BE: (BE, 08:30, 2), NL: (NL, 08:30, 1) }   — the CGM of that moment, one query
```

**Versions are the inner dimension** because they change more often — every study run adds one — while the
timestamps of a day are fixed by its schedule. So a timestamp is a *root* snapshot linked by a `pdb:TimestampEdge`
to its **pin**, with a version chain of its own below it. A pin is any snapshot of another timestamp of the **same
tree** — the authority's base, a version of the base, or another timestamp — and it is chosen once, when the
timestamp is created:

* an **ingested** timestamp (`putAsDiff`) is compared against and hangs off the **latest rollover at or before
  it** — the root until a later snapshot is flagged — or the pin the caller names;
* a **recorded** change (`RdfDbExport.export`) hangs off the **snapshot the recording network is at** (its
  `RdfDbProvenance`), when that one is of the same tree and states what the differences supersede; otherwise —
  `putDiff` of a bare difference set, a network loaded from files — off **the snapshot of the tree that states what
  its differences supersede**. When several do and their states differ (a later timestamp that drifted the
  equipment states the steady state of the snapshot it hangs off), the change could have been made against any of
  them, and the write is refused rather than guessed: *"several snapshots of modelling authority '…' of scenario '…'
  state what the difference models of … supersede […], and they differ in [EQ], which the differences do not touch:
  (…), (…); without its sender it is not known which one the change was made against, so name the pin"*.
  Snapshots that agree on their whole state are one pin, and the deepest is taken. The caller may name the pin,
  which must state them (*"supersedes … but the pin (…) is at …"*). That is why the network's own snapshot comes
  first;
* a pin is refused for a timestamp that exists: its new versions grow on its head (*"… already exists, and a pin is
  chosen when a timestamp is created"*).

Three things follow:

* every timestamp is "its pin plus a handful of differences", however many study versions the other timestamps
  accumulate, which is what keeps a fetch of any timestamp cheap;
* a walk from one timestamp to another of the same authority is the ordinary lowest-common-ancestor plan: up the
  first timestamp's versions to their common pin, then down into the second. Two fast differences, one composed
  update — between two timestamps of one rollover's afternoon the path never touches the morning;
* `verify()` checks the shape: a timestamp root hangs off a snapshot of its own tree at another timestamp, and a
  version off one of its own timestamp.

### Rollovers: roll over when the equipment drifts, not on churn

A day drifts. In the morning every timestamp is "the base plus a handful of differences"; by the evening the
equipment of the day may have moved so far from the base that every ingestion stores the same drift again, as a
slow equipment difference no in-place update reads. A **rollover** moves the default pin forward:

```java
SnapshotCatalog catalog = db.snapshots("2016-01-01");
catalog.putAsDiff(filesOf1200, null, SnapshotRef.latestAt("2016-01-01", mas, at1200), null, params, rn);
catalog.lastIngestStatistics().forwardStatements().get(Profiles.EQ);   // the equipment delta against its pin
catalog.rollover(SnapshotRef.latestAt("2016-01-01", mas, at1200));     // flag it, and checkpoint it at once
catalog.putAsDiff(filesOf1215, null, SnapshotRef.latestAt("2016-01-01", mas, at1215), null, params, rn);
// 12:15 is compared against 12:00: its difference is the change since noon, a fast one when only the schedule moved
catalog.putAsDiff(filesOf1230, null, SnapshotRef.latestAt("2016-01-01", mas, at1230), null,
        SnapshotRef.latestAt("2016-01-01", mas, at0830), params, rn);   // or name the pin
```

`rollover(ref)` writes `pdb:rollover true` on the snapshot and runs `Checkpoint.create` on it in the same call:
every timestamp pinned to it starts its materialisation there, so the chain above it — the slow drift included —
is folded once, on the server. A load of 12:15 then fetches 12:00's full graphs and applies one difference; an
update from 12:00 to 12:15 applies one. The call is idempotent, and it changes nothing that was written before: a
pin is chosen when a timestamp is written, so 08:15, ingested before the rollover, stays pinned to the root, and so
does a timestamp before noon ingested afterwards.

**The drift rule: roll over when the equipment delta against the pin grows, not on churn.** A schedule — the
steady state hypothesis — changes every timestamp, and its differences are small and fast whatever the pin; rolling
over for it only lengthens the tree. The equipment is what drifts: watch
`lastIngestStatistics().forwardStatements().get(EQ)` (or `fast().get(EQ)`) of the ingestions, and roll over at the
first timestamp of a run whose equipment delta against the pin keeps growing — typically a few times a day, at a
topology change. The layer does not roll over by itself; the rule is the caller's loop over those statistics.

### Archiving the states before a cutoff

```java
catalog.setArchiveCutoff(Instant.parse("2016-01-01T12:00:00Z"), "s3://grid-archive/2016-01-01");
catalog.archiveCutoff();     // Optional[2016-01-01T12:00:00Z]
catalog.archiveLocation();   // Optional[s3://grid-archive/2016-01-01]
catalog.setArchiveCutoff(null, null);   // served again
```

An owner who moved the graphs of a scenario's early hours elsewhere tells the store with an **archive cutoff**: two
triples on the schema node, `pdb:archiveCutoff` (`xsd:dateTime`) and `pdb:archiveLocation` (a string; a URL, a path,
a name — the layer never reads it), set together and cleared together. From then on every read that resolves an
address refuses a snapshot whose own timestamp is before the cutoff — `find`/`require`, a load, an update (the
network is untouched), `assembly`, a bulk load of variants (before anything is loaded), both ends of
`changesBetween` — with one text:

*"snapshot (2016-01-01, http://elia.be/CGMES/2.4.15, 2016-01-01T08:30:00Z, 1) is in the archive at
s3://grid-archive/2016-01-01: states before 2016-01-01T12:00:00Z are not served by this store"*

* **Roots are not exempt.** The comparison is on the target's own timestamp, so the base is refused too. Set the
  cutoff at a [rollover](#rollovers-roll-over-when-the-equipment-drifts-not-on-churn): its checkpoint is where the
  materialisation of every later timestamp starts, so nothing after the cutoff needs an archived graph.
* **The listings still show archived snapshots** (`snapshots()`, `timestamps`, `versions`, `verify()`), and the walk
  of a plan still passes through them: the ancestry is metadata, only the graphs are gone.
* **Nothing starts at an archived snapshot.** A network loaded before the cutoff was set and still at an archived
  snapshot is not walked from: its plan is `FULL` with the archive text as the reason, and an update reloads the
  target. A recorded change of such a network is not filed under it by default: when the archived snapshot is the
  only one stating what the change supersedes, the export is refused with the same text (name a served `pin`).
  Likewise an ingestion whose default pin — the latest rollover at or before it — is archived is refused with the
  text, rather than failing on a missing graph; roll over after the cutoff, or name a served `pin`.
* **It holds at once, everywhere.** The cutoff is read with the schema check (the same request, no cost), and it is
  also a filter inside the query that resolves an address (`FILTER NOT EXISTS { <schema> pdb:archiveCutoff ?c
  FILTER(?ts < ?c) }`), so a cutoff another connection set is honoured before this one has read it; the refusal
  then re-reads the schema node to name the location.
* **One edit, two requests.** `setArchiveCutoff` is a `DELETE/INSERT` guarded by the registry revision `pdb:rev`
  (which it bumps, like a registry edit) and the read-back; a concurrent edit is a `RdfDbConflictException`.

### Dropping a timestamp, and the changes between two

```java
catalog.dropTimestamp(mas, at1230);   // the root and every version of 12:30, with their graphs
DifferenceModelSet changes = RdfDbNetworkLoader.changesBetween(db,
        SnapshotRef.latestAt("2016-01-01", mas, at1215), SnapshotRef.latestAt("2016-01-01", mas, at0830));
```

`dropTimestamp` deletes a timestamp's root and its versions — their nodes, their differences, their whole custom
graphs and their checkpoint copies — when nothing depends on them. A timestamp another one is pinned to is refused,
naming every dependant, and nothing is dropped: there is no cascade, because dropping a pin would take every
timestamp ingested against it along. The base timestamp is the tree itself and is never dropped; another day is
another scenario, cleared as a whole. The nodes go first, in one request guarded on the same check, so a snapshot
written on the timestamp after the check refuses the drop (`RdfDbConflictException`, nothing dropped); the graphs
follow, and a failure in between leaves graphs nothing names, never a snapshot without its graphs.

`changesBetween(db, from, to)` answers what a network at `from` has to apply to be at `to`, as one
`DifferenceModelSet`: the path between the two, its differences fetched, the ones up out of `from` turned round
and the ones down into `to` as they are, composed exactly as an update composes them. Each composed difference
carries the identity of `to`'s model and supersedes `from`'s, so `CgmesDiffImport.apply` takes the network from
one to the other. It is a statement set, not an in-place update: it is made whether or not the differences are
fast-route capable and however long the path. Three requests (the plan, the differences, the end models); two
snapshots of different authorities or scenarios are refused with the planner's reason.

Writing a timestamp from a network is the ordinary export, with the timestamp in the address:

```java
String mas = "http://elia.be/CGMES/2.4.15";
Instant at0830 = Instant.parse("2016-01-01T08:30:00Z");
// a recorder at the base head writes the 08:30 timestamp of that day: version 1 of it, pinned to the base head
RdfDbExport.export(network, events, db, SnapshotRef.latestAt("2016-01-01", mas, at0830), options, reportNode);
// a study on top of it: version 2
RdfDbExport.export(network, events, db, SnapshotRef.latestAt("2016-01-01", mas, at0830), options, reportNode);

db.snapshots("2016-01-01").timestamps(mas);           // one row per timestamp: root, head, versions, pin
db.snapshots("2016-01-01").versions(mas, at0830);     // the chain inside one timestamp
Network n = RdfDbNetworkLoader.load(db, SnapshotRef.of("2016-01-01", mas, at0830, "2"), null, params, rn);
```

### Ingesting a day from its files

A TSO does not record its schedule on a network: it exports ninety-six sets of instance files. `putAsDiff` is what
turns one of them into a version of its tree:

```java
db.snapshots("2016-01-01").putAsDiff(filesOf0830, null, SnapshotRef.latestAt("2016-01-01", null, at0830),
        null, importParams, reportNode);   // authority from the files, EQ and SSH compared
```

It materialises the state of its pin as triples — the latest rollover at or before the timestamp, or the pin the
caller names (above); the first half of an ordinary materialisation, from the cache after the first timestamp of a
rollover — parses the new files, and compares the two graphs profile by profile. What comes
out is an ordinary difference model, so every rule, guard and message of a recorded difference applies to an
ingested one.

The profiles compared are the caller's projection, `EQ` and `SSH` when it names none. A listed profile the files do
not carry is refused; a file of a profile that is not compared is read for its header alone, reported, and the
snapshot inherits the parent's state of it. The boundary is never compared. A listed **custom** profile is not
compared either: its file is stored whole as a new member of the snapshot, unless it is the model the parent
already states (see [Profiles are names](#profiles-are-names-custom-ones-are-stored-whole)); a timestamp whose only
change is such a file is still a new snapshot.

What the comparison does and does not call a change:

* the `md:FullModel` header is excluded — it always differs, and it is not part of the model;
* two literals that both parse as `double` compare **by value**, so a re-export writing `10.0` where the base
  wrote `10` is not a difference;
* a property may be multi-valued, and each key compares as a set;
* an added object arrives as a forward `rdf:type` plus its properties, a removed one as a reverse type plus all of
  them — which is exactly what the store-level applier and the fast-route check expect.

**EQ drift is allowed.** When the equipment of a timestamp differs from the base — a renamed line, an added base
voltage — the ingested EQ difference states properties no in-place update reads, the planner answers `FULL`, and
the client materialises. Nothing fails; the fast route is simply not taken, and the reason names the difference.

**The boundary never changes.** A boundary gives the objects of a grid model their identity, so a set of files
whose boundary model differs from the scenario's is refused: a new boundary is a new base grid model, and a new
base grid model is a new scenario.

Two rules of the timestamp dimension, both enforced by the guard of the write:

1. **one root per (scenario, modellingAuthority, timestamp)** — a second writer of the same new timestamp loses and
   is told so;
2. **a snapshot and its members describe the same moment**: a difference whose `md:Model.scenarioTime` disagrees
   with the timestamp it is written at is refused. A difference that states no scenario time at all belongs to the
   timestamp of its snapshot, which is the ordinary case for a change recorded on a network.

Inside one timestamp the version chain stays linear, exactly as on the base chain. What is *not* linear any more is
`md:Model.Supersedes` of a pinned model: every timestamp pinned to one rollover supersedes the same steady-state
model, which is the fan in the picture above. That is deliberate — it is what "base plus differences per timestamp" means — and
the linearity that matters is guarded on the snapshot (one root per timestamp, one version successor per snapshot)
rather than on the model.

## Timestamps and versions as network variants

Everything above moves *one* network from one stored state to another. This section is the other way of using the
same store: a network that holds **several** stored states at once, one per IIDM variant, so that a whole day is one
object in memory and a study can compare two versions of one moment without loading either of them twice.

It is **opt-in**, and the opt-in is explicit: `loadVariants`, an update that names a `targetVariant`, or
`RdfDbExport.exportVariant` / `exportPerVariant`. From then on the network is in variant mode and *every* in-place
operation of this module is a variant operation. Nothing else switches it on &mdash; in particular **cloning a
variant does not**. Cloning is the ordinary IIDM idiom for a security analysis, and a caller that has never heard
of this feature keeps getting exactly the routes it always got, a `FULL_RELOAD` included.

The bindings of such clones are tracked all the same, so that a later opt-in knows what is there. They are
**dropped as soon as a classic in-place operation runs** on a network that is not in variant mode &mdash; an
update, a profile replacement, an export. Such an operation writes the working variant and is free to write values
IIDM shares between variants, so nothing keeps the other bindings true; forgetting them means a later opt-in says
`variant 'x' is not bound to a snapshot` instead of planning from a state the variant no longer holds.

Variant mode is **sticky**, deliberately, in two situations worth naming:

* a first opt-in that is *refused* still switches it on &mdash; **every** refusal, the cross-scenario one
  included, which is the only one answered without sending a query. The caller asked for variant semantics, and
  giving it classic ones back would let the next operation write across the variants it is about to create, or
  hand it a `FULL_RELOAD` &mdash; a *new network object* &mdash; on a network it is holding variants of;
* removing every variant again does not switch it off. The network then has one variant, and the equipment
  difference that would be refused cannot leak anywhere &mdash; but the answer stays `VARIANT_REFUSED` rather
  than changing under the caller's feet. Load the snapshot again if a classic network is wanted.

`RdfDbProvenance.isVariantMode()` answers the question directly. A caller that has to know which routes are
possible &mdash; whether a `FULL_RELOAD` with a new network object can still happen, say &mdash; asks that, not
`variantBindings()`: a tracked clone produces a binding without opting in.

```java
// A day of 96 timestamps in one network, version 1 of each
List<VariantRequest> requests = day.stream()
        .map(instant -> VariantRequest.of(SnapshotRef.of("2016-01-01", mas, instant, 1))).toList();
VariantLoadResult loaded = RdfDbNetworkLoader.loadVariants(db, "2016-01-01", requests,
        new RdfDbVariantLoadOptions(), null, params, reportNode);
Network network = loaded.network();
network.getVariantManager().setWorkingVariant("2016-01-01T08:30:00Z");
LoadFlow.run(network);                       // the state of 08:30, the other variants untouched

// One more variant, created on demand
RdfDbNetworkLoader.update(network, db, SnapshotRef.of("2016-01-01", mas, at0830, 2),
        new RdfDbUpdateOptions().setTargetVariant("study@08:30"), params, reportNode);
```

### What a variant stands for

A bound variant is a `VariantBinding`: the snapshot address `(scenario, modellingAuthority, timestamp, version)`
(`modellingAuthority()`, `timestamp()`, `version()`), the snapshot IRI, the
stored model per CGMES profile, the case date of that moment, and which variant it was cloned from. The bindings
live on the `RdfDbProvenance` extension of the network and are read with `variantBindings()` and
`variantBinding(id)`.

The **primary** variant — IIDM's `InitialState` — is special in one way only: the network-level identity
(`CgmesMetadataModels`, `RdfDbProvenance.modelIds()/snapshot()`, `caseDate`) always describes *it*. When an
operation acts on another variant, that variant's identity is swapped in for the duration of the operation and
swapped out again (`VariantScope`). That is why the update planner, the difference exporter, the `Supersedes` of a
written difference and the snapshot catalogue are all correct for a variant although none of them knows that
variants exist. `RdfDbProvenance.inVariant(network, id, body)` is the public form of that swap: for a variant bound to a snapshot
it installs the whole identity, so a file export written inside it carries that variant's `md:Model.Supersedes`;
for anything else it only selects the working variant, which is all there is to select. Combine it with
`CgmesDiffExport.ExportOptions.setVariant` / `PartialSshExport.ExportOptions.setVariant`:

```java
RdfDbProvenance.inVariant(network, "08:30",
        () -> CgmesDiffExport.toString(network, events, CgmesSubset.STEADY_STATE_HYPOTHESIS, FAIL));
```

`loadVariants` gives the first requested snapshot to the network itself **and** a named variant of its own, so
every requested snapshot is addressed the same way; the primary stays a pristine clone source.

### Variants a user creates, clones and removes

IIDM lets anyone clone, overwrite and remove a variant at any time, and a binding that outlived its variant would be
worse than no binding at all. The provenance therefore registers a `NetworkListener` and keeps the bindings in step
as it happens:

| what the user does | what happens to the binding |
|---|---|
| `cloneVariant(src, t)` | `t` inherits the binding of `src` exactly — its state *is* that snapshot |
| `cloneVariant(src, t, true)` (overwrite) | the binding of `t` is replaced by the one of `src` |
| `cloneVariant(src, "InitialState", true)` (committing a study variant) | the *network-level* identity becomes the source's: its models, its case date, its snapshot. An unbound source leaves the network at no snapshot at all, so that the next update rebuilds or refuses rather than planning from a stale identity |
| `removeVariant(v)` | the binding of `v` is dropped |
| clone of an **unbound** variant | the target stays unbound |

A variant that existed before the network was given a provenance — a variant of a file-loaded network — is unbound,
and asking this module to move it says so:
`RdfDbException: variant 'x' of network N is not bound to a snapshot: …`.

### Routes, and what a refusal means

`UpdateResult.Route` gains `VARIANT_REFUSED`. An ordinary update owns the whole network and may rebuild it; a
variant update owns one slot of every per-variant array and nothing else, because the other variants are states the
caller is holding on to. So when the target cannot be reached **inside** the variant it is refused:

* `route() == VARIANT_REFUSED`, `network()` is the network that was handed in, unchanged;
* `reasons()` says what stood in the way, and `RdfDbProvenance.lastRefused()` remembers it;
* a variant the call created is removed again, so every variant of the network is byte-identical to what it was.

`RdfDbUpdateOptions.setVariantFallback(SEPARATE_NETWORK)` instead materialises the snapshot into a **separate**
single-variant network and answers `FULL_RELOAD` with it; the multi-variant network is untouched either way.
`setAllowFullReload` is ignored in variant mode. A target in another scenario is refused without sending a query.

As soon as a network holds one bound variant, **every** in-place operation of this module is a variant operation on
`options.getTargetVariant()` or, when that is null, on the working variant — an unsafe write on the primary would
leak into the bound variants. The pre-snapshot entry points (`DiffTarget` with model identifiers, the whole-profile
replacement `update(network, db, scenario, RdfDbLoadOptions, …)`) address models rather than snapshots and are
refused with `variant mode addresses snapshots`.

### Which changes stay inside a variant

IIDM stores the *operating* values per variant and the *equipment description* once per network. That is the whole
of the rule, and the table below explains its machine-readable form (`FastRouteCapabilities.VariantSafety`, the
verdict of each family is on the generated {ref}`mapping page <cgmes-mapping>`). Every row is
asserted against `iidm-impl` by `VariantSafetyProbeTest`: the 61 characterized changes of
`RecordedChangeScenarios` are swept in both directions, and the four original network-dependent rules and the control-area
row &mdash; for which no characterized change exists &mdash; have receivers built for them, with and without the
extension or the capability flag that makes the rule fire.

| family / property | IIDM target written by the update | per variant? | verdict |
|---|---|---|---|
| `Switch.open` | `Switch.open` | yes | SAFE |
| `ACDCTerminal.connected` (DC terminal) | terminal connection | yes | SAFE |
| `ACDCTerminal.connected` (terminal) | terminal connection, node/breaker fictitious switch — `connected = false` in a node/breaker voltage level **creates** `<terminal>_SW_fict` when absent (powsybl-core #4085) | state yes, **creation no** | NETWORK_DEPENDENT: unsafe iff `connected = false` is stated for node/breaker equipment without that switch |
| `EnergyConsumer`, `EnergySource`, `AsynchronousMachine` | `Load.p0/q0`, `LoadDetail` | yes | SAFE |
| `SynchronousMachine`, `ExternalNetworkInjection` p/q, `controlEnabled` | `Generator.targetP`, `localTargetQ`, `localTargetV`, its `VoltageRegulation` (target, flag) | values yes, **creation of the `VoltageRegulation` no** | NETWORK_DEPENDENT: unsafe iff a generator whose CGMES control regulates voltage has no `VoltageRegulation` |
| … `*.referencePriority` | `ReferencePriorities` | value yes, **creation no** | NETWORK_DEPENDENT: unsafe iff the value is above zero and the generator has no `ReferencePriorities` extension |
| `EquivalentInjection` | generator targets and `VoltageRegulation`, or `BoundaryLine.p0/q0` + `Generation` | values yes, **creation of the `VoltageRegulation` no** | NETWORK_DEPENDENT: unsafe iff `regulationStatus = true` is stated for a generator without `VoltageRegulation` |
| `GeneratingUnit.normalPF` | `ActivePowerControl.participationFactor`, or a new extension, or the property `CGMES.normalPF` | only the first | NETWORK_DEPENDENT: safe iff every generator of the unit already has `ActivePowerControl` |
| `StaticVarCompensator` | `localTargetQ`, `localTargetV`, its `VoltageRegulation` (target, flag) | yes | SAFE |
| `ShuntCompensator` | `sectionCount`, `localTargetV`, its `VoltageRegulation` (target, deadband, flag) | yes | SAFE |
| ratio / phase tap changer, tap changer control | `tapPosition`, the `VoltageRegulation` of a ratio tap changer, `regulationValue`, `targetDeadband`, `regulating` of a phase tap changer — but switching regulation on raises `loadTapChangingCapabilities` | all yes except that flag | NETWORK_DEPENDENT: unsafe iff regulation is switched on for a tap changer without the flag |
| `RegulatingControl` of a generator, shunt or SVC | the targets above | yes, **creation of a generator's `VoltageRegulation` no** | NETWORK_DEPENDENT: the tap changer rule, and unsafe iff a generator whose CGMES control regulates voltage has no `VoltageRegulation` |
| `VsConverter` | detailed DC model: control mode, local targets, the `VoltageRegulation` rebuilt from `qPccControl`. **Simplified model (the default)**: also `HvdcLine.maxP` and `VscConverterStation.lossFactor` | detailed: values yes, the `VoltageRegulation` and its terminal no; `maxP`, `lossFactor` no | NETWORK_DEPENDENT: safe iff the subject resolves to a `VoltageSourceConverter` that has a `VoltageRegulation` and whose regulating terminal the stated `qPccControl` does not change |
| `CsConverter` | `LccConverterStation.powerFactor/lossFactor`, `HvdcLine.maxP`; detailed: `LineCommutatedConverter.powerFactor` | no | UNSAFE |
| `ControlArea.netInterchange` | `Area.interchangeTarget` | yes | SAFE |
| `ControlArea.pTolerance` | the IIDM property `pTolerance` | no | UNSAFE |
| current / active power / apparent power limit `.value` (CIM 2.4.15 EQ **and** CIM 3 SSH) | `LoadingLimits.setPermanentLimit` / `setTemporaryLimitValue` | no | UNSAFE |
| `VoltageLimit.value`, `VoltageLevel.high/lowVoltageLimit` | `VoltageLevel.high/lowVoltageLimit` | no | UNSAFE |
| `ACLineSegment`, `SeriesCompensator`, `EquivalentBranch` impedances | `Line` / `BoundaryLine` r, x, g, b | no | UNSAFE |

Side effects that stay and why they are harmless: `OperationalLimitConversion.update` runs for every touched branch
and rewrites the limit values — with previous values in use it writes the value the (shared) limit already has; the
boundary-line fixes touch only per-variant state; `Terminal.setP/setQ` is per variant. Not written by the update at
all, and listed because the question comes up: `PhaseTapChanger.regulationMode` (a plain field), tap changer steps,
`CgmesTapChangers`, `SlackTerminal` and `ReferenceTerminals`.

Anything that is not a fast-route family at all — creating or removing an object, a renamed line, topology, state
variables — was already outside the in-place route and is now refused in variant mode instead of triggering a
reload.

A variant-bound update also **requires** the scoped update: the full update writes the properties `v` and `angle` on
every boundary line and three-windings transformer and lowers the validation level, and neither is per variant.

### `pdb:variantSafe`, and stores written before it existed

When a difference is written, `FastRouteCapabilities.checkVariantSafe` decides from the document alone whether every
statement writes per-variant state, and the answer is stored next to `pdb:fastPredicatesOnly`:

```turtle
<urn:uuid:ssh-d2> pdb:fastPredicatesOnly true ; pdb:variantSafe true .
```

A node without it (a store written before it existed) means *unknown*. How the stored half and the apply-time half
of the verdict divide the work is on the [integration page](rdf_database_integration.md#variant-mode).

### Loading a day, and what it costs in requests

`loadVariants` does in a constant number of requests what a loop would do per timestamp:

1. one `chains` query binds every requested address and walks all of their chains at once, returning each reached
   snapshot's detail rows exactly once;
2. the first request is materialised as the network — the plan for it is read off those same rows, so no second
   plan query is sent;
3. each further target is matched to the nearest variant already loaded (the primary and the last few), the paths
   are read off the chains client-side;
4. one request fetches every difference of every accepted path, one more reads the models those paths end at;
5. the variants sourced from the primary are created in a single `cloneVariant(primary, list)`, and each target is
   then brought to its snapshot inside its own scope.

Measured on the embedded server (`RdfDbRequestCountTest`): **13 requests for 2 timestamps and 13 for 8** — the cost
is the fixture's instance files plus a constant, not a function of the number of timestamps. Creating or moving a
single variant is 3 requests, exactly like a snapshot update.

Naming: an explicit identifier wins (`RdfDbVariantLoadOptions.setNaming` for a rule); otherwise the ISO instant of
the timestamp when the timestamps of all requests are distinct (a day reads as `2016-01-01T08:30:00Z`), and
`version@instant` when they are not (a study reads as `2@2016-01-01T08:30:00Z`); requests of several modelling
authorities are named `authority/version@instant`, so two trees at one moment never collide. A variant never crosses a
modelling authority: a variant of a BE network asked to stand for an NL snapshot is `VARIANT_REFUSED`, decided off
the IRIs without a request. Duplicate identifiers, `InitialState` and an empty request list
are `IllegalArgumentException`; an address the scenario does not hold is an `RdfDbException` naming every missing
one, raised before anything is loaded.

### Writing one history per variant

A network whose variants are the timestamps of a day holds parallel histories, and an export keeps them apart:

```java
Map<String, RdfDbExport.VariantExport> written =
        RdfDbExport.exportPerVariant(network, recorder.getEvents(), db, null, options, reportNode);
```

* the target of a variant is the successor of **that variant's** snapshot: same scenario, same modelling
  authority, same timestamp, next version of that timestamp's chain (or the `String newVersion` given). A
  caller-given scenario time that is not the variant's timestamp is an error, not a silent move;
* the export runs inside the variant's scope, so the values written are that variant's and the `Supersedes` names
  that variant's model;
* changes recorded on another variant are **dropped** — naming a variant is a selection;
* a change IIDM does not store per variant is recorded *without* a variant identifier, so it belongs to every
  variant of the network. With more than one variant such a change is an unsupported change
  (`CgmesDiffExport.ExportOptions.setRejectSharedChanges`), which under `FAIL` is an exception and under `IGNORE`
  appears in `VariantExport.rejected()`;
* two phases: every group is translated first, so a `FAIL` fails with nothing written; only then is group after
  group stored, and a conflict in the second phase names the variants that were already written.

`RdfDbExport.exportVariant(network, events, db, variantId, newVersion, options, reportNode)` does the same for one
variant. The **classic** exports &mdash; `export(…, scenario, …)` and `export(…, SnapshotRef, …)` &mdash; behave
identically on a network in variant mode: they describe the working variant, supersede that variant's model,
advance that variant's binding, and refuse a shared change under the same FAIL/IGNORE rule. The file exports take the variant through `CgmesDiffExport.ExportOptions.setVariant` and
`PartialSshExport.ExportOptions.setVariant`; both restore the working variant of the calling thread afterwards.

### Threading

Every variant operation of one network is serialised by a lock on its provenance, because each of them swaps
network-level state in and out. Readers pinned to *other* variants are unaffected **in the thread-local variant
context** (`VariantManager.allowVariantMultiThreadAccess(true)`, which
`RdfDbVariantLoadOptions.setAllowVariantMultiThreadAccess` switches on): each thread then has a working variant of
its own and an operation on one variant moves nobody else's. In IIDM's default single-context mode there is one
global working variant, and an operation of this module moves it under every other thread &mdash; which is only
acceptable because concurrent readers are not supported in that mode anyway. Note also that the lock covers the
identity swap and the apply; the planning and the cloning around it are not serialised, so two threads must not
create variants of the same network at the same time. What is **not** safe at all is creating a variant while
other threads read the network — IIDM grows its
per-variant arrays then — so create the variants first and start the readers afterwards;
`RdfDbVariantLoadOptions.setAllowVariantMultiThreadAccess(true)` sets IIDM's flag at the one moment it can be set.
`RdfDbVariantThreadingTest` is the smoke test: four readers on four variants keep summing while a writer moves two
others back and forth twenty times.

### Memory

A variant costs one slot in every per-variant array — setpoints, switch states, tap positions, terminal and bus
state variables — not a copy of the topology, the identifiers or the extensions. Measured on the MicroGrid base
case, the 96 variants come to about 1 MB; see the caveat under the table below.

### Performance of the variant flows

MicroGrid BE, 96 steady-state timestamps of one version, medians of five runs after two warm-ups, eight-core
machine, loopback Fuseki and the in-process backend, milliseconds. `lv` is `loadVariants` of the whole day, `sep`
is ninety-six separate loads of the same timestamps, `walk` is one network updated ninety-six times (which keeps no
history at all).

```text
backend  shape  lv(first)  lv(warm)   plan  fetch  clone  apply   apply/variant    sep    walk
fuseki   thin      1081        672      77     52      1    383   med 3 / max 7    6397    1610
fuseki   rich       752        667      70    117      1    412   med 4 / max 10   5230    1842
memory   thin       366        319      21      3      0    262   med 2 / max 6    2734     497
memory   rich       481        407      16      5      0    357   med 3 / max 6    2625     517

TARGET MET on both shapes: a warm load of the 96 timestamps on the in-process backend takes 319 ms (thin) and
407 ms (rich), against a target of 1 000 ms. Every per-variant apply is far inside the 100 ms budget, so no
profiling run was needed. The 96 variants cost about 1 MB of heap on this fixture; the measurement - the same
network read after a collection with and without them - resolves no better than that.
```

Target: a warm `lv` on the in-process backend under 1 000 ms for both shapes. That figure is **reported**
(`TARGET MET` / `TARGET MISSED`) and never asserted, because it is a statement about an *idle* machine: the same
run on a machine with a load average of ten measured 763 ms and 968 ms. What
`-Dpowsybl.rdfdb.benchmark.strict=true` asserts is the ratio the design is about &mdash; a day as variants is at
least three times cheaper than loading every timestamp on its own &mdash; with both figures taken from the same
run, so that a busy machine slows them together. The numbers in the table above therefore need an idle machine to
be reproduced: the same build measured 319, 354 and 763 ms for the warm thin day at load averages of roughly 1, 5
and 10, while `lv : sep` stayed between 9 and 11 throughout.

## Limitations of this work package

* **Rollovers are explicit.** The layer never flags a rollover by itself: the drift rule
  ([Rollovers](#rollovers-roll-over-when-the-equipment-drifts-not-on-churn)) is the caller's loop over
  `lastIngestStatistics()`. A rollover changes the default pin of timestamps written afterwards only; nothing is
  re-pinned, and `dropTimestamp` never cascades.

* **A dropped timestamp re-opens its graph IRIs.** A difference graph is named after the model identifier of the
  file it was ingested from, so ingesting the same files again after `dropTimestamp` writes the same IRIs. The
  promise "a graph IRI never changes content" holds per process: another process's `GraphCache` may still hold the
  dropped graph and serve it for the re-ingested one until it is evicted or the process restarts. Drop and
  re-ingest with the readers stopped, or under new file identifiers.

* **A CGM is a query, and a flat load.** The IGMs of one day are the trees of the modelling authorities of one
  scenario, and `SnapshotCatalog.assembly(timestamp, version)` names the snapshot of each at one moment;
  `RdfDbNetworkLoader.loadComposed` loads them as one **flat** network (first authority wins). Subnetworks of a
  file-loaded CGM are separated at file level, by the importer, before any triple store exists; the tests compare
  CGMs with `iidm.import.cgmes.cgm-with-subnetworks=false` on both sides. The network identifier of a composition
  is one of the EQ model identifiers, which one is not defined (as for the files).
* **Graphs are mutable** in the unversioned flow, so caching is opt-in there (above). A graph a snapshot refers
  to is written once and never rewritten, and is trusted by the cache by default.
* **One base day per scenario, and no link between two scenarios or two modelling authorities.** Walking from the
  last timestamp of one day to the first of the next, or from one authority's tree to another's, is a full reload. That is what keeps every chain bounded and the
  plan query independent of how much the database holds; the base graphs of the other day are cached, so the
  reload is a materialisation and not an upload.
* **File-based timestamp ingestion compares the equipment model and the steady state hypothesis by default.**
  `SnapshotCatalog.putAsDiff` materialises the parent state, compares it with the files triple by triple
  (`TripleDiffCalculator`) and writes the result as an ordinary version. State variables and topology change
  wholesale between timestamps, so a difference of them would be as large as the data: unless the caller lists
  them in the profile projection, their files are read for their headers, reported and left alone, and the snapshot
  inherits the parent's. A network loaded at such a timestamp therefore carries the **base's** state variables; a
  caller wanting consistent flows runs a load flow. Storing them whole per timestamp is the next step and the
  schema already allows it (`pdb:full` on a diff snapshot).
* **Network variants do not make the equipment description per variant.** Operational limit values, voltage
  limits, branch impedances, the rating and loss factor of an HVDC line in the default simplified DC model, the
  power factor of a line commutated converter and every IIDM property are single fields of the network. A
  difference that writes one of them is refused in variant mode (`VARIANT_REFUSED`) rather than applied to every
  variant at once; making them per variant is upstream work in `iidm-impl`.
* **`caseDate` and `forecastDistance` are not per variant either.** The binding of a variant carries its case
  date and the scope swaps it in for the duration of an operation, so an export dates its difference correctly;
  a caller reading `network.getCaseDate()` outside such an operation always reads the primary's.
* **A bound variant never crosses scenarios**, and there is no snapshot-level aggregate of `pdb:variantSafe` and
  no back-fill tool for stores written before it existed (unknown is decided at apply time, above). The
  variant-bound update is not reachable through `Network.update(dataSource)`: there is no import parameter for
  `variantSafeOnly`, because that entry point has no variant to name.
* **Variant bindings are not serialised, and neither are the variants.** The bindings live on
  `RdfDbProvenance`, which has no XIIDM serialiser by design; XIIDM itself writes the *working variant only*, so
  a network written to XIIDM and read back holds neither the other variants nor any binding. The day has to be
  loaded again from the database, which is one `loadVariants` call.
* **Creating a variant while other threads read the network is unsupported** &mdash; that is an IIDM property,
  not one of this layer. Create the variants first (above).
* **The 96-timestamp ingestion benchmark and its store-size claim are not measured.** What is measured is a
  fifty-difference chain (above); what is not is a whole day of ingestion and the claim that the difference store
  stays under a quarter of ninety-six full steady-state models.
* **There are no wall-time labels.** The key is an instant; rendering it in a local zone, daylight saving
  included, is the caller's.
* **Only a transient version's leaf snapshots can be deleted** (`VersionRegistry.delete` of a transient name);
  any other snapshot stays, and a scenario can be dropped whole (`RdfDbConnection.clear`,
  `SnapshotCatalog.dropAll`).
* **A version name a snapshot carries cannot be renamed.** The snapshot IRI carries the name and addresses are read
  back off IRIs (`RdfDbNames.refOf`); `rename` is for names no snapshot carries yet, `rerank` changes the order.
* **The pre-versioning entry points mean "the newest snapshot" on a versioned scenario.**
  `RdfDbNetworkLoader.load(db, scenario, …)` and `update(…, DiffTarget.head(), …)` delegate to the snapshot path
  when the scenario holds snapshots of exactly one modelling authority (a scenario of several is refused with the
  list of them: address it with a `SnapshotRef`); the catalogue read they make anyway is what tells them. A *named* `DiffTarget` keeps the model-level path, which is what a caller addressing
  individual stored models asked for.
* **`CatalogSnapshot.head(subset)` is ambiguous on a scenario with several timestamps**, and says so rather than
  picking one: every timestamp of a day supersedes the same base steady-state model, so that profile has one
  successor per timestamp. Such a scenario is addressed by `SnapshotRef`, which says *which* newest state is meant.
  The copies a `Checkpoint` folds are not affected: they are `pdb:Materialized` nodes and the catalogue of stored
  models ignores them.
* **`loadCgmes` is refused on a versioned scenario**: its instance files are immutable graphs a snapshot refers
  to, and a second, unversioned set next to them would be unreachable. Use `SnapshotCatalog.putFull` for the root
  and `putAsDiff` for a timestamp.
* **No migration of an earlier store.** A scenario of schema 3 (integer versions) or of the earlier
  `(scenario, timestep, version)` schema is refused with a message; clear it and ingest it again.
* **`REMOTE` query mode cannot read a versioned scenario**: the differences would have to be applied on the
  server. `Checkpoint` is what applies them there, and it produces graphs a plain load can read.
* **Authentication** is HTTP basic or a fixed header.
* **Restricting a scenario-addressed load to some CGMES profiles** (`RdfDbLoadOptions.setProfiles`, and the
  default of an update) is
  honoured in `LOCAL` mode on both backends and in `REMOTE` mode on a SPARQL database, where it becomes the
  dataset of every query. It is **not** honoured by `REMOTE` mode on the in-process `memory:` backend, which has
  one store per scenario and no dataset parameters: a remote-mode subset load there sees the whole scenario.
  Nothing needs that combination; `LOCAL` mode, the default, is unaffected.
* **One grid per scenario, one linear chain per profile.** A scenario holds one full model per CGMES profile and
  at most one successor per stored model. Two clients recording on the same base is fine — the second one is told
  to load the head and record again — but a branching version tree is not this release.
* **`REMOTE` query mode cannot see differences.** A scenario holding differences has to be read in `LOCAL` mode,
  where the graphs are transferred and the differences applied on the client. Asking for it in `REMOTE` mode fails
  with a message saying so.
* **Topology and state variables are never diffed.** A difference describes the equipment model or the steady
  state hypothesis. Two consequences worth knowing. A network built at any stored state carries the **state
  variables of the base**, the ones the uploaded SV file holds: they belong to the steady state hypothesis the
  scenario started from, not to the version that was materialised, so a caller who needs flows that match the state
  runs a load flow. And a network updated *in place* and the same state *materialised* from the database agree on
  the grid and on the hypothesis but disagree about the results that belonged to the previous hypothesis — the
  terminal flows and the solved tap position of the equipment the difference touched. The update workflow clears
  them because the hypothesis they were computed for is gone; a conversion keeps what the state variables file
  says. Both values are stale and no difference model can carry the disagreement away.
* **A partial load is addressed, not scenario-wide.** `RdfDbLoadOptions.setProfiles` restricts a plain load; on a
  scenario that holds differences it would build a network from a state that never existed, and that load fails
  with a message saying so. A snapshot is loaded with a profile projection through
  `RdfDbNetworkLoader.load(db, ref, profiles, …)`, which takes each named profile at the state the snapshot has
  for it, and always the boundary.
* **One model per profile.** A network carrying two CGMES models of one profile — a merged model with two modelling
  authorities — cannot be the sender or the receiver of a difference: a stored chain versions one model. Such a
  network is refused rather than silently halved: write each authority's changes from a network of that
  authority (or from its variant), into its own tree. A network of `loadComposed` is the exception for the
  versioned export: it knows the tree of every object and routes each change there.
* **A crash between the two phases of a large write leaves orphan graphs.** They are invisible to every reader;
  `ModelCatalog.orphanGraphs()` lists them.
* **No pre-parsed on-disk cache.** Serialising the parsed graphs as RDF4J binary RDF under a cache directory would
  make a cold start as cheap as a warm one; it is designed for, not implemented.
* **Apache Jena is never on a production classpath.** The client is RDF4J and the JDK HTTP client throughout;
  Jena appears only as a test-scope dependency, as the embedded Fuseki the tests and benchmarks run against.

## One thing that had to be fixed along the way

A CGMES conversion walks over SPARQL query results, and their order is not a property of the data: a triple store
filled by the RDF/XML parser and the same store filled from a database hand the statements over in different
orders, and neither is more correct. Almost everything that follows is cosmetic — node numbers of a node-breaker
voltage level are handed out as connectivity nodes are met, sets are written as sequences — but one thing was not.

A CGMES regulating control names a CGMES terminal, and the conversion maps it to *one* IIDM terminal of the
topological node that terminal belongs to. That node can carry a dozen pieces of equipment, several of them behind
open switches, and the pick used to be whichever the equipment creation order offered first. On the CGMES 3
Svedala model, two equally valid readings of the same files disagreed about seven shunt compensators and three tap
changers, and for four of the shunts one reading picked a **connected** terminal and the other a **disconnected**
one — which decides whether the regulation has a bus at all, and therefore whether a load flow sees it.

`RegulatingTerminalMapper` now chooses deterministically inside each of its preference groups: a connected
terminal first, and among equals the lowest identifier. `RegulatingTerminalDeterminismTest` builds two stores
holding the same statements in opposite order and requires the same answer.

## Using a database as the triple store of a plain import

The `rdf4j-sparql` triple store implementation can also be selected by name, so that an ordinary
`Network.read` of CGMES files writes its statements into a configured database instead of into memory:

```yaml
rdf4j-sparql:
    url: http://localhost:3030/ds
    scenario: 2026-09-18
    user: admin
    password: admin
```

```java
properties.put("iidm.import.cgmes.powsybl-triplestore", "rdf4j-sparql");
```

Without that configuration module the implementation fails with a `PowsyblException` saying what it needs.
Programmatic callers should use `powsybl-cgmes-rdfdb` instead, which addresses the database and the scenario
explicitly.

Note for downstream projects: `powsybl-triple-store-impl-rdf4j-sparql` registers itself as a
`TripleStoreFactoryService`, so it appears in `TripleStoreFactory.allImplementations()` wherever it is on the
classpath — which, through `powsybl-cgmes-rdfdb`, is the whole powsybl distribution. A test that iterates that
list and calls `create()` on every entry will get that exception for `rdf4j-sparql` unless the module is
configured.

## What the module depends on, and what depends on it

`powsybl-cgmes-rdfdb` lives in powsybl-core for now and is built to move out of it later. It is its own package
(`com.powsybl.cgmes.rdfdb`), so it can only reach the **public** API of other modules, and it uses no reflection
to get past that. Its messages come from its own report bundle (`com/powsybl/cgmes/rdfdb/reports*.properties`,
registered as a `ReportResourceBundle` service), so `powsybl-commons` carries none of its texts.

**What it needs from core.** A small set of public types exists because a client outside their module builds on
them; each says so in its javadoc ("Public API: a client outside this module builds on this signature", on the class,
or on the two members of `CgmesImport`), so that changing one is visibly a change of public API. The table of those
types, grouped by purpose with what the database reads from each and what it never assumes, and the flows they
serve, are on the [integration page](rdf_database_integration.md#what-the-database-knows-about-powsybl-internals).

Everything else it uses is ordinary public API, used as any other client uses it: `ReportNode`, `PowsyblException`,
`PlatformConfig`, data sources and extensions from `powsybl-commons`; `Network`, `NetworkFactory`, the variant
manager, network events and the import post-processors from `powsybl-iidm-api`; `CgmesSubset`, `CgmesNamespace`,
the difference model types and metadata models from `powsybl-cgmes-model` and `powsybl-cgmes-extensions`; the
computation manager; `TripleStore` and `TripleStoreRDF4J`. The module's `pom.xml` declares every artefact its
classes reference (`mvn dependency:analyze` names no powsybl artefact as used but undeclared).

The list is pinned by a test: `MoveOutReadinessTest` reads the module's main sources and requires every
`com.powsybl` type they import or name to be on `src/test/resources/com/powsybl/cgmes/rdfdb/allowed-core-imports.txt`
(one type per line, the marked surface first), and every entry of that list to be used. A new dependency on core
is therefore a one-line addition a reviewer sees.

**What depends on it.** Nothing in core: no other module imports `com.powsybl.cgmes.rdfdb`. The module appears only
in the reactor (`cgmes/pom.xml`), in the root `dependencyManagement` and in `distribution-core`. To check:

```bash
grep -rl 'com.powsybl.cgmes.rdfdb' --include=*.java . | grep -v cgmes-rdfdb
```

prints nothing, and removing the module from the reactor and from `distribution-core` leaves a reactor that builds.
Moving it out also takes `powsybl-triple-store-impl-rdf4j-sparql` along: its only consumer is this module, and
`distribution-core` lists it too.
Outside core, pypowsybl uses its public API.

## See also

* [RDF database integration](rdf_database_integration.md) — every flow between the database and the CGMES
  conversion, with the types that cross and who decides what.
* [Triple store](triple_store.md) — the RDF4J store the file import uses, and the `rdf4j-sparql` implementation.
* [Import](import.md) — the CGMES import parameters, which a database load honours unchanged.
