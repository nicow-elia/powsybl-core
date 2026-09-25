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

A scenario is the first key of the `(scenario, timestep, version)` triple this family of work packages
addresses data by, and it is the one that names the **base grid model**: one scenario describes one grid, and the
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
to a scenario filled by `RdfDbConnection.loadCgmes` without a version (Python `load_cgmes` without one) — or to
one that was later migrated by `migrateImplicitRoot()`, which declares the files it already holds to be the root
without moving them — and a re-upload of that scenario replaces it, so the cache re-validates it rather than
trusting the name.

Scenario names and model identifiers are percent-encoded in an IRI; the raw scenario name is also stored as a
literal (`pdb:scenario`), so a listing never has to decode anything.

The vocabulary is `pdb: <http://powsybl.org/ns/rdfdb#>` for everything that is about the *storage*, and the model
description terms of IEC 61970-552 (`md:`, `dm:`) **verbatim** for everything that is about the model, so that a
reader who knows CGMES can read the metadata graph without knowing PowSyBl.

| Term | Meaning |
|---|---|
| `pdb:kind` | `pdb:Full` (an uploaded instance file) or `pdb:Diff` (a recorded difference) |
| `pdb:subset` | the CGMES profile: `EQ`, `SSH`, `TP`, `SV`, `EQ_BD`, … |
| `pdb:scenario` | the raw scenario name |
| `pdb:graph` | the named graph of a full model |
| `pdb:forwardGraph`, `pdb:reverseGraph` | the named graphs of a difference |
| `pdb:fastPredicatesOnly` | whether every property the difference states is one an update query reads |
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
    pdb:tripleCount 4 ; pdb:chainDepth 1 .

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
  trust them without checking;
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

## Versioning: snapshots, versions and timesteps

Everything above versions *one profile at a time*: a difference supersedes a model and the chain of that profile
grows. That is enough to move a network forward, and not enough to say "load version 1.1", because a version of a
grid model is a state of *every* profile at once. The versioning layer adds the node that says so.

### A version is a resource, not a tag

A version is a `pdb:Snapshot` node plus immutable named graphs. The objects of the model carry no version property
at all.

The alternative — tagging every object — would need reification or RDF-star, a version filter in all eighty-one
catalog queries of the CGMES conversion (a forked catalog to maintain, and a slower one), and it would make the
data mutable. Named graphs leave the queries untouched, make each graph cacheable by its IRI and free of read
concurrency, and map one to one onto CGMES itself: `md:Model.Supersedes` is the chain of a profile,
`md:Model.DependentOn` the dependency between profiles, `md:Model.version` the per-profile counter, and
`md:Model.scenarioTime` the timestep. What the snapshot adds is the cross-profile consistency unit and the
user-facing label.

### The three keys

A snapshot is addressed by **`(scenario, timestep, version)`**, and `SnapshotRef` is that address.

| Key | What it is | May it be left open? |
|---|---|---|
| `scenario` | the base grid model — in practice **one day**. Free-form, non-blank | **never**: there is no default scenario and no "latest scenario" anywhere in this API |
| `timestep` | a canonical ISO instant in UTC, equal to `md:Model.scenarioTime` of the snapshot's members | yes: `null` means the base timestep of that scenario |
| `version` | a free label, `[A-Za-z0-9._-]{1,64}`, for instance `"1.1"` | yes: `null` means the head of that timestep's chain |

Several days in one database are **several scenarios**. A scenario has exactly one root snapshot; a second one is
refused. Version order is what the chain says, never what comparing two labels says, so `"study-a"` is as good a
version as `"1.1"`.

A timestep may be written as an ISO instant (`2016-01-01T08:30:00Z`), an offset date-time
(`2016-01-01T09:30:00+01:00`), a local date-time read as UTC (which is what a CGMES header usually carries), or as
a `"8:30"` label. A label is resolved **against the base day and the zone offset of the scenario it is addressed
in**, which the per-scenario `pdb:Catalog` node records, so the same label means two different instants in two
scenarios that describe two days. Daylight saving is not handled: the offset is fixed at the root.

The instant is the key; its `HH:MM` rendering is not. The snapshot node stores that rendering separately as
`pdb:timestepLabel`, computed once at write time from `pdb:timestep` and the scenario's base offset, and **no
query ever matches on it** — a caller's `"8:30"` has become an instant before the first request goes out. The term
was called `pdb:label` before this release, which said neither what it labels nor that it is for display; that
name is still read, so a store written earlier keeps showing its labels, but it is no longer written. In Java the
accessor is `SnapshotInfo.timestepLabel()`; in Python the `snapshots()` column is `timestep_label`. The row of
`timesteps()` keeps the plain name `label`, because a row about one timestep can mean nothing else.

### Writing snapshots

```java
try (RdfDbConnection db = RdfDbConnection.open(RdfDatabase.sparql("http://localhost:3030/ds"))) {
    SnapshotCatalog catalog = db.snapshots("2016-01-01");

    // The root: the instance files become immutable graphs of this scenario
    SnapshotInfo root = catalog.putFull(dataSource, boundary, SnapshotRef.of("2016-01-01", "1.0"),
            new Properties(), ReportNode.NO_OP);

    // A study run on top of it, recorded on a network and stored as version 1.1
    RdfDbExport.export(network, events, db, "2016-01-01", "1.1", null,
            new CgmesDiffExport.ExportOptions(), ReportNode.NO_OP);

    catalog.snapshots();            // the history, oldest first
    catalog.nextVersionLabel(null); // "1.2"
    catalog.verify();               // the invariants below, re-checked
}
```

The rules, all of them enforced by the guard of the write itself rather than by a check before it:

1. one root per scenario (`putFull` twice is a conflict — another day is another scenario);
2. a graph of `…/<scenario>/graph/` is written once and never overwritten;
3. a difference must supersede exactly what the parent snapshot states for its profile, otherwise the writer is
   told where the head is: *"update the network to the head and re-record"*;
4. the chain is linear — a second child along a `pdb:VersionEdge` is refused with *"the linear scheme allows no
   forks"*;
5. `(scenario, timestep, version)` is unique;
6. nothing crosses a scenario: every `pdb:parent`, `pdb:member`, `pdb:state` and `pdb:full` of a snapshot points
   inside the scenario it was written in.

Concurrent writers: the guard decides, the loser gets a `RdfDbConflictException` naming the rule, and there is no
retry. Two writers of two different scenarios never conflict at all — their metadata graphs are disjoint.

A scenario written before this release (instance files, possibly with a difference chain on them) becomes a
versioned one through `SnapshotCatalog.migrateImplicitRoot()`: its instance files are declared to be version
`"0"` at the scenario time of its steady state file, and the differences it already held stay where they are.

### Reading a version, and the one query that decides how

```java
Network n = RdfDbNetworkLoader.load(db, "2016-01-01", "1.1", null, null, params, reportNode);
UpdateResult r = RdfDbNetworkLoader.update(n, db, SnapshotRef.of("2016-01-01", "1.3"), options, params, rn);
```

`update` sends **one** query. A `UNION` binds the two ends — the snapshot the network is at (from its provenance,
or matched by the model identifiers it carries) and the snapshot the caller asked for, resolved by
`(timestep, version)` in the same query — and a `pdb:parent*` property path walks each of them up to the root. The
client then takes the deepest snapshot both sides reached as the lowest common ancestor and reads the path off the
two chains: up from A, each difference *inverted*, then down to B, each one forward.

| Answer | When | What the caller does |
|---|---|---|
| `NOOP` | the network is already at the target | nothing |
| `DIFF` | every difference on the path is fast-route capable and the path is no longer than `maxDiffChain` (200) | they are fetched in one request, folded per profile and applied in place |
| `FULL` | a difference states a property no in-place update reads, the path is too long, the two have no common ancestor, or **the network belongs to another scenario** | the network is rebuilt at the target, and the result carries a new instance |

The cross-scenario case costs **no query at all**: a snapshot IRI carries its scenario, so
`"network is at scenario 'A', target is scenario 'B': diffs never cross scenarios"` is decided by string
arithmetic. Walking from the last timestep of one day to the first of the next is therefore a full reload, by
design — it keeps every chain bounded and lets a database hold as many days as it likes.

A worked example on the chain A(1.0) → B(1.1) → C(1.2) → D(1.3): `plan(A, B)` is one forward step; `plan(D, C)` is
one inverted step; `plan(A, D)` is `FULL` when C states something the fast route cannot apply, and the reason names
C's difference; `plan(D, 1.1)` is two inverted steps.

### Materialisation and checkpoints

A load materialises: the full graphs go into a local in-memory store (through the cache — a versioned graph is
immutable, so it is trusted by default), the differences on the way are applied to it as plain RDF, and the
unchanged CGMES conversion runs on the result. Which graph a profile starts from is decided **per profile**: each
one walks up the chain until it finds an ancestor whose `pdb:full` lists a model of that profile.

That is what makes a checkpoint useful without breaking anything:

```java
Checkpoint.create(db, SnapshotRef.of("2016-01-01", "1.50"));
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
versions, or once per timestep. It is idempotent and is not on any hot path.

### Metadata graph (schema v2)

Schema v1 above, plus the snapshot nodes. Every v1 node stays valid and is read unchanged.

```turtle
@prefix pdb: <http://powsybl.org/ns/rdfdb#> .
@prefix md:  <http://iec.ch/TC57/61970-552/ModelDescription/1#> .
@prefix s:   <http://powsybl.org/rdfdb/2016-01-01/snapshot/2016-01-01T00%3A00%3A00Z/> .
# graph <http://powsybl.org/rdfdb/2016-01-01/meta>

<http://powsybl.org/rdfdb/2016-01-01/catalog> a pdb:Catalog ; pdb:scenario "2016-01-01" ;
    pdb:baseTimestep "2016-01-01T00:00:00Z" ; pdb:baseOffset "Z" .

s:1.0 a pdb:Snapshot ; pdb:scenario "2016-01-01" ; pdb:version "1.0" ;
    pdb:timestep "2016-01-01T00:00:00Z" ; pdb:timestepLabel "00:00" ; pdb:kind pdb:Full ; pdb:depth 0 ;
    pdb:timestepRoot s:1.0 ;
    pdb:member <urn:uuid:eq-1>, <urn:uuid:ssh-1> ;   # what this snapshot adds
    pdb:state  <urn:uuid:eq-1>, <urn:uuid:ssh-1> ;   # what a reader is at once it reaches it
    pdb:full   <urn:uuid:eq-1>, <urn:uuid:ssh-1> .   # where a materialisation may start, per profile

s:1.1 a pdb:Snapshot ; pdb:version "1.1" ; pdb:timestep "2016-01-01T00:00:00Z" ; pdb:kind pdb:Diff ;
    pdb:parent s:1.0 ; pdb:edge pdb:VersionEdge ; pdb:depth 1 ;
    pdb:timestepRoot s:1.0 ; pdb:member <urn:uuid:ssh-d2> ;
    pdb:state <urn:uuid:eq-1>, <urn:uuid:ssh-d2> .

<urn:uuid:ssh-d2> a dm:DifferenceModel ; pdb:subset "SSH" ; pdb:snapshot s:1.1 ;
    md:Model.Supersedes <urn:uuid:ssh-1> ; md:Model.scenarioTime "2016-01-01T00:00:00Z" .   # + the v1 terms

# after Checkpoint.create(db, SnapshotRef.of("2016-01-01", "1.1")):
s:1.1 pdb:full <http://powsybl.org/rdfdb/2016-01-01/materialized/…/1.1/SSH>, <urn:uuid:eq-1> .
```

A second scenario repeats the whole structure under `http://powsybl.org/rdfdb/<other scenario>/`, with no edge of
any kind between the two.

A snapshot node carries no boolean that repeats what its links already say. Two used to:

* the fast-route capability is stored **once**, on the difference model (`pdb:fastPredicatesOnly`). The `fast`
  column of a snapshot listing — `SnapshotInfo.fast()` in Java, the `fast` column of `db.snapshots(...)` in
  Python — is *derived*: the conjunction over the difference members of the snapshot, read in the same request
  that returns the snapshot, and a snapshot with no difference member is `true`;
* whether a materialisation may start at a snapshot is *having* a `pdb:full` link. `SnapshotInfo.hasFull()` and
  the `has_full` column are `!fullModels().isEmpty()`, computed from the rows the listing already returns.

Databases written by an earlier release carry `pdb:fast` and `pdb:hasFull` triples on their snapshot nodes; this
release ignores both — never read, never rewritten, never deleted — so a store filled before the change is read
correctly without a migration. One consequence is worth stating for a mixed-release deployment: a checkpoint
written here adds the `pdb:full` links and leaves a legacy `pdb:hasFull false` beside them, so a client of the
*previous* shape reading that store would still believe the snapshot has no full graphs. Such a client would
reload rather than start at the checkpoint — slower, never wrong — and the situation ends as soon as every client
reads the links.

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

## Timesteps: the outer dimension

A day is not one grid state but ninety-six of them, and a study run is another dimension on top of each. Both live
inside the same scenario, and they are not the same kind of key:

```
scenario "2016-01-01"                     scenario "2016-01-02"   (a different day = a different scenario)
  catalog: base 00:00, offset Z             ...no edge of any kind between the two...
  00:00  1.0 ──── 1.1 ──── 1.2             the same structure again
          │        │
          │        └── 08:30  1.0 ── 1.1        pdb:TimestepEdge down, pdb:VersionEdge across
          └── 08:15  1.0
```

**Versions are the inner dimension** because they change more often — every study run adds one — while the
timesteps of a day are fixed by its schedule. So a timestep is a *root* snapshot pinned to a version of the **base
chain** by a `pdb:TimestepEdge`, with a version chain of its own below it. Three things follow:

* every timestep is "the base plus a handful of differences", however many study versions the other timesteps
  accumulate, which is what keeps a fetch of any timestep cheap;
* a walk from one timestep to another is the ordinary lowest-common-ancestor plan: up the first timestep's
  versions, up to the pin, then down into the second. Two fast differences, one composed update;
* a timestep root may only derive from the **base** chain. A client sitting at 08:30 that wants to write 08:45
  first brings itself back to the base head — which is fast when its own differences are fast — and is told so:
  *"timestep roots derive from the base timestep of scenario 'S'; update the network to the base head first"*.

Writing one is the ordinary export, with the timestep in the address:

```java
// a recorder at the base head writes the 08:30 timestep of that day. A label belongs to a scenario - it means
// that wall time on *its* base day - so the form taking one takes the scenario with it
RdfDbExport.export(network, events, db, "2016-01-01", "1.0", "8:30", options, reportNode);
// a study on top of it
RdfDbExport.export(network, events, db, "2016-01-01", "1.1", "8:30", options, reportNode);
// the same address as a value: SnapshotRef.of(version, text, catalog) resolves the label in that scenario
SnapshotRef at0830 = SnapshotRef.of("1.1", "8:30", db.snapshots("2016-01-01"));

db.snapshots("2016-01-01").timesteps();        // one row per timestep: label, root, head, how many versions
db.snapshots("2016-01-01").versions("8:30");   // the chain inside one timestep
Network n = RdfDbNetworkLoader.load(db, "2016-01-01", "1.1", "8:30", null, params, rn);
```

### Ingesting a day from its files

A TSO does not record its schedule on a network: it exports ninety-six sets of instance files. `putAsDiff` is what
turns one of them into a version of the scenario:

```java
db.snapshots("2016-01-01").putAsDiff(filesOf0830, null,
        SnapshotRef.of("1.0", "8:30", db.snapshots("2016-01-01")), importParams, reportNode);
```

It materialises the parent state as triples (the first half of an ordinary materialisation, from the cache after
the first timestep of the day), parses the new files, and compares the two graphs profile by profile. What comes
out is an ordinary difference model, so every rule, guard and message of a recorded difference applies to an
ingested one.

What the comparison does and does not call a change:

* the `md:FullModel` header is excluded — it always differs, and it is not part of the model;
* two literals that both parse as `double` compare **by value**, so a re-export writing `10.0` where the base
  wrote `10` is not a difference;
* a property may be multi-valued, and each key compares as a set;
* an added object arrives as a forward `rdf:type` plus its properties, a removed one as a reverse type plus all of
  them — which is exactly what the store-level applier and the fast-route check expect.

**EQ drift is allowed.** When the equipment of a timestep differs from the base — a renamed line, an added base
voltage — the ingested EQ difference states properties no in-place update reads, the planner answers `FULL`, and
the client materialises. Nothing fails; the fast route is simply not taken, and the reason names the difference.

**The boundary never changes.** A boundary gives the objects of a grid model their identity, so a set of files
whose boundary model differs from the scenario's root is refused: a new boundary is a new base grid model, and a
new base grid model is a new scenario.

Two rules of the timestep dimension, both enforced by the guard of the write:

1. **one root per (scenario, timestep)** — a second writer of the same new timestep loses and is told so;
2. **a snapshot and its members describe the same moment**: a difference whose `md:Model.scenarioTime` disagrees
   with the timestep it is written at is refused. A difference that states no scenario time at all belongs to the
   timestep of its snapshot, which is the ordinary case for a change recorded on a network.

Inside one timestep the version chain stays linear, exactly as on the base chain. What is *not* linear any more is
`md:Model.Supersedes` of a base model: every timestep of a day supersedes the same base steady-state model, which
is the fan in the picture above. That is deliberate — it is what "base plus differences per timestep" means — and
the linearity that matters is guarded on the snapshot (one root per timestep, one version successor per snapshot)
rather than on the model.

## Timesteps and versions as network variants

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
// A day of 96 timesteps in one network
VariantLoadResult day = RdfDbNetworkLoader.loadVariants(db, "2016-01-01", "1.0",
        List.of("08:00", "08:15", "08:30", /* … */), new RdfDbVariantLoadOptions(), null, params, reportNode);
Network network = day.network();
network.getVariantManager().setWorkingVariant("08:30");
LoadFlow.run(network);                       // the state of 08:30, the other variants untouched

// One more variant, created on demand
RdfDbNetworkLoader.update(network, db, "2016-01-01", "1.1", "08:30", "study@08:30", params, reportNode);
```

### What a variant stands for

A bound variant is a `VariantBinding`: the snapshot address `(scenario, version, timestep)`, the snapshot IRI, the
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
of the rule, and the table below is its machine-readable form (`FastRouteCapabilities.VariantSafety`). Every row is
asserted against `iidm-impl` by `VariantSafetyProbeTest`: the 61 characterized changes of
`RecordedChangeScenarios` are swept in both directions, and the four network-dependent rules and the control-area
row &mdash; for which no characterized change exists &mdash; have receivers built for them, with and without the
extension or the capability flag that makes the rule fire.

| family / property | IIDM target written by the update | per variant? | verdict |
|---|---|---|---|
| `Switch.open` | `Switch.open` | yes | SAFE |
| `ACDCTerminal.connected` (terminal, DC terminal) | terminal connection, node/breaker switch | yes | SAFE |
| `EnergyConsumer`, `EnergySource`, `AsynchronousMachine` | `Load.p0/q0`, `LoadDetail` | yes | SAFE |
| `SynchronousMachine`, `ExternalNetworkInjection` p/q, `controlEnabled` | `Generator.targetP/Q/V`, `voltageRegulatorOn`, `RemoteReactivePowerControl` | yes | SAFE |
| … `*.referencePriority` | `ReferencePriorities` | value yes, **creation no** | NETWORK_DEPENDENT: unsafe iff the value is above zero and the generator has no `ReferencePriorities` extension |
| `EquivalentInjection` | generator targets or `BoundaryLine.p0/q0` + `Generation` | yes | SAFE |
| `GeneratingUnit.normalPF` | `ActivePowerControl.participationFactor`, or a new extension, or the property `CGMES.normalPF` | only the first | NETWORK_DEPENDENT: safe iff every generator of the unit already has `ActivePowerControl` |
| `StaticVarCompensator` | setpoints, `regulating` | yes | SAFE |
| `ShuntCompensator` | `sectionCount`, `targetV`, `targetDeadband`, `voltageRegulatorOn` | yes | SAFE |
| ratio / phase tap changer, tap changer control | `tapPosition`, `regulationValue`, `targetDeadband`, `regulating` — but switching regulation on raises `loadTapChangingCapabilities` | all yes except that flag | NETWORK_DEPENDENT: unsafe iff regulation is switched on for a tap changer without the flag |
| `RegulatingControl` of a generator, shunt or SVC | the targets above | yes | SAFE |
| `VsConverter` | detailed DC model: setpoints, control mode. **Simplified model (the default)**: also `HvdcLine.maxP` and `VscConverterStation.lossFactor` | detailed yes; `maxP`, `lossFactor` no | NETWORK_DEPENDENT: safe iff the subject resolves to a `VoltageSourceConverter` |
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

A node that does not carry it at all — every store written before this release — means **unknown**, and the planner
is optimistic: the path proceeds, and the network-aware check that runs on every variant update anyway refuses it at
apply time. Being wrong costs one fetch and never a wrong result, which is why there is neither a snapshot-level
aggregate nor a back-fill tool.

### Loading a day, and what it costs in requests

`loadVariants` does in a constant number of requests what a loop would do per timestep:

1. one `chains` query binds every requested address and walks all of their chains at once, returning each reached
   snapshot's detail rows exactly once;
2. the first request is materialised as the network — the plan for it is read off those same rows, so no second
   plan query is sent;
3. each further target is matched to the nearest variant already loaded (the primary and the last few), the paths
   are read off the chains client-side;
4. one request fetches every difference of every accepted path, one more reads the models those paths end at;
5. the variants sourced from the primary are created in a single `cloneVariant(primary, list)`, and each target is
   then brought to its snapshot inside its own scope.

Measured on the embedded server (`RdfDbRequestCountTest`): **13 requests for 2 timesteps and 13 for 8** — the cost
is the fixture's instance files plus a constant, not a function of the number of timesteps. Creating or moving a
single variant is 3 requests, exactly like a snapshot update.

Naming: an explicit identifier wins; otherwise the `HH:MM` label of the timestep when the labels of all requests are
distinct (a day reads as `08:30`), and `version@label` when they are not (a study reads as `1.1@08:30`). A timestep
without a label falls back to its canonical instant. Duplicate identifiers, `InitialState` and an empty request list
are `IllegalArgumentException`; an address the scenario does not hold is an `RdfDbException` naming every missing
one, raised before anything is loaded.

### Writing one history per variant

A network whose variants are the timesteps of a day holds parallel histories, and an export keeps them apart:

```java
Map<String, RdfDbExport.VariantExport> written =
        RdfDbExport.exportPerVariant(network, recorder.getEvents(), db, null, options, reportNode);
```

* the target of a variant is the successor of **that variant's** snapshot: same scenario, same timestep, next
  version of that timestep's chain. A caller-given scenario time that is not the variant's timestep is an error,
  not a silent move;
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

MicroGrid BE, 96 steady-state timesteps of one version, medians of five runs after two warm-ups, eight-core
machine, loopback Fuseki and the in-process backend, milliseconds. `lv` is `loadVariants` of the whole day, `sep`
is ninety-six separate loads of the same timesteps, `walk` is one network updated ninety-six times (which keeps no
history at all).

```text
backend  shape  lv(first)  lv(warm)   plan  fetch  clone  apply   apply/variant    sep    walk
fuseki   thin      1081        672      77     52      1    383   med 3 / max 7    6397    1610
fuseki   rich       752        667      70    117      1    412   med 4 / max 10   5230    1842
memory   thin       366        319      21      3      0    262   med 2 / max 6    2734     497
memory   rich       481        407      16      5      0    357   med 3 / max 6    2625     517

TARGET MET on both shapes: a warm load of the 96 timesteps on the in-process backend takes 319 ms (thin) and
407 ms (rich), against a target of 1 000 ms. Every per-variant apply is far inside the 100 ms budget, so no
profiling run was needed. The 96 variants cost about 1 MB of heap on this fixture; the measurement - the same
network read after a collection with and without them - resolves no better than that.
```

Target: a warm `lv` on the in-process backend under 1 000 ms for both shapes. That figure is **reported**
(`TARGET MET` / `TARGET MISSED`) and never asserted, because it is a statement about an *idle* machine: the same
run on a machine with a load average of ten measured 763 ms and 968 ms. What
`-Dpowsybl.rdfdb.benchmark.strict=true` asserts is the ratio the design is about &mdash; a day as variants is at
least three times cheaper than loading every timestep on its own &mdash; with both figures taken from the same
run, so that a busy machine slows them together. The numbers in the table above therefore need an idle machine to
be reproduced: the same build measured 319, 354 and 763 ms for the warm thin day at load averages of roughly 1, 5
and 10, while `lv : sep` stayed between 9 and 11 throughout.

## Limitations of this work package

* **A CGM produces one network.** Subnetworks are separated at file level, by the importer, before any triple
  store exists. To get subnetworks out of a database, load each IGM into its own scenario and merge the networks.
  The tests compare CGMs with `iidm.import.cgmes.cgm-with-subnetworks=false` on both sides.
* **Graphs are mutable** in the unversioned flow, so caching is opt-in there (above). A graph a snapshot refers
  to is written once and never rewritten, and is trusted by the cache by default.
* **One base day per scenario, and no link between two scenarios.** Walking from the last timestep of one day to
  the first of the next is a full reload of the other scenario. That is what keeps every chain bounded and the
  plan query independent of how much the database holds; the base graphs of the other day are cached, so the
  reload is a materialisation and not an upload.
* **File-based timestep ingestion compares the equipment model and the steady state hypothesis only.**
  `SnapshotCatalog.putAsDiff` materialises the parent state, compares it with the files triple by triple
  (`TripleDiffCalculator`) and writes the result as an ordinary version. State variables and topology change
  wholesale between timesteps, so a difference of them would be as large as the data: their files are read,
  reported and left alone, and the snapshot inherits the parent's. A network loaded at such a timestep therefore
  carries the **base's** state variables; a caller wanting consistent flows runs a load flow. Storing them whole
  per timestep is the next step and the schema already allows it (`pdb:full` on a diff snapshot).
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
* **The 96-timestep ingestion benchmark and its store-size claim are not measured.** What is measured is a
  fifty-difference chain (above); what is not is a whole day of ingestion and the claim that the difference store
  stays under a quarter of ninety-six full steady-state models.
* **Daylight saving is not handled** by the `HH:MM` labels: a scenario has one fixed zone offset, recorded on its
  catalogue node at the root. Callers working across a transition pass instants.
* **A snapshot cannot be deleted**; a scenario can be dropped whole (`RdfDbConnection.clear`,
  `SnapshotCatalog.dropAll`).
* **The pre-versioning entry points mean "the newest snapshot" on a versioned scenario.**
  `RdfDbNetworkLoader.load(db, scenario, …)` and `update(…, DiffTarget.head(), …)` delegate to the snapshot path
  when the scenario holds snapshots; the catalogue read they make anyway is what tells them, so the delegation
  costs no request. A *named* `DiffTarget` keeps the model-level path, which is what a caller addressing
  individual stored models asked for.
* **`CatalogSnapshot.head(subset)` is ambiguous on a scenario with several timesteps**, and says so rather than
  picking one: every timestep of a day supersedes the same base steady-state model, so that profile has one
  successor per timestep. Such a scenario is addressed by `SnapshotRef`, which says *which* newest state is meant.
  The copies a `Checkpoint` folds are not affected: they are `pdb:Materialized` nodes and the catalogue of stored
  models ignores them.
* **`loadCgmes` is refused on a versioned scenario**: its instance files are immutable graphs a snapshot refers
  to, and a second, unversioned set next to them would be unreachable. Use `SnapshotCatalog.putFull` for the root
  and `putAsDiff` for a timestep.
* **A timestep label is `HH:MM`, and `HH:MM:SS` when the timestep is not on the minute**, so that two timesteps
  thirty seconds apart never show the same label.
* **`REMOTE` query mode cannot read a versioned scenario**: the differences would have to be applied on the
  server. `Checkpoint` is what applies them there, and it produces graphs a plain load can read.
* **Authentication** is HTTP basic or a fixed header.
* **Restricting a load to some CGMES subsets** (`RdfDbLoadOptions.setSubsets`, and the default of an update) is
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
* **A partial load of a versioned scenario is refused.** `RdfDbLoadOptions.setSubsets` restricts a plain load; on a
  scenario that holds differences it would build a network from a state that never existed, and the load fails with
  a message saying so.
* **One model per profile.** A network carrying two CGMES models of one profile — a merged model with two modelling
  authorities — cannot be the sender or the receiver of a difference: a stored chain versions one model. Such a
  network is refused rather than silently halved. Load the individual grid models into scenarios of their own.
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

## See also

* [Triple store](triple_store.md) — the RDF4J store the file import uses, and the `rdf4j-sparql` implementation.
* [Import](import.md) — the CGMES import parameters, which a database load honours unchanged.
