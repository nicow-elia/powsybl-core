# Import

The CIM-CGMES importer reads and converts a CIM-CGMES model to the PowSyBl grid model. The import process is performed in two steps:
- Read input files into a triplestore
- Convert CIM-CGMES data retrieved by SPARQL requests from the created triplestore to PowSyBl grid model

The data in input CIM/XML files uses RDF (Resource Description Framework) syntax. In RDF, data is described making statements about resources using triplet expressions: (subject, predicate, object).

The CIM-CGMES importer supports reading CIM/XML profile files from one of the following data sources:
- a folder containing all uncompressed profile files
- a folder containing the zipped profile files, each one being in a separate zip file
- a zipped file containing all profile files

To describe the conversion from CGMES to PowSyBl, we first introduce several ways of using the importer. We then present some generic considerations about the level of detail of the model (node/breaker or bus/branch), the identity of the equipments and equipment containment in substations and voltage levels. After that, the conversion for every CGMES relevant class is explained. Consistency checks and validations performed during the conversion are mentioned in the corresponding sections.

(cgmes-import-update)=
## Import / Update

In CGMES, the power system model is described using data organized into different profiles. 
This structure allows the equipment in the network and its physical characteristics to be defined in one profile (EQ), while operational data is provided in other profiles (SSH, TP and SV). 
As a result, it is common to have a single EQ file and multiple SSH files, for example, one for each of the 24 hours of the day.

To handle these cases properly, the importer supports subsequent updates to the initially imported model. 
You can use the `Network.read` method to perform the initial import, and then apply further updates with `network.update`, where network refers to the originally imported model.
Next, we present four use cases to illustrate how both methods can be applied:

**Import the entire model in a single step** 
You need to use the `Network.read` method, providing all the CGMES profiles.

**Import the complete model in two steps**
In the first step, only the EQ profile is imported using the `Network.read` method. 
Then, the operational data is imported using the `network.update` method, providing all or part of the following profiles: SSH, TP, and SV. 
The `network.update` method cannot be used with EQ profiles.

**Import one EQ file and 24 partial SSH files**
In this case, the 00 SSH file is the only complete file and contains data for all equipment. 
The remaining SSH files are partial, including only the changes relative to the preceding SSH file.
In the first step, we can import the EQ and 00 SSH files using the `Network.read` method. 
Then, we can perform one update for each of the remaining SSH files using the `network.update` method. 
Before performing the updates, the property `iidm.import.cgmes.use-previous-values-during-update` must be set to `true` to fill in any missing data in the partial SSH files using the values previously recorded in the model.

PowSyBl uses variants to record operational data. In this case, we are performing the update on the same variant, so at the end, PowSyBl will only contain the operational data corresponding to the last hour.

**Import one EQ file and 24 SSH files, using a different variant for each SSH file**
In this case, all the SSH files contain data for all the equipment, but we want to record each SSH file on a different variant.
To do that, we first import the EQ and 00 SSH files using the `Network.read` method, recording the data in the default initial variant. 
Then, we perform an update for each of the remaining SSH files using the `network.update` method. 
However, before each update, we must create a new variant by cloning the initial one and set it as the working variant.
At the end, PowSyBl will contain the operational data for all 24 hours, with each hour recorded in a different variant.


(cgmes-import-difference-model)=
## Difference model update

A difference model is an IEC 61970-552 `dm:DifferenceModel` document: it says what a CGMES model said *before* a
change and what it says *after* it, as two lists of statements. PowSyBl writes such documents from a recorded change
log (see [difference model export](export.md#cgmes-difference-model-export)) and reads them back here. Difference
models can also come from an [RDF database](rdf_database.md#difference-models-in-the-database) &mdash; where a
state is addressed by `(scenario, timestep, version)`, see
[versioning](rdf_database.md#versioning-snapshots-versions-and-timesteps) and
[timesteps](rdf_database.md#timesteps-the-outer-dimension) &mdash; rather than from
documents: the stored differences between the version a network holds and the version it is to reach are fetched and
applied by the very same code.

Applying one *in place* means feeding its forward statements through the ordinary network update workflow: no file is
re-read, no network is rebuilt, and the result is exactly what the partial SSH file of the same change would have
produced. Previous values are always used, because a difference is partial by definition.

### Usage

`network.update` detects a difference model on its own, so nothing has to be said about it:

```java
Network network = Network.read(originalDataSource);
network.update(new GenericReadOnlyDataSource(directory, "case"));   // holds case_SSH_DIFF.xml
```

The file names are free &mdash; the detection reads the first elements of each file rather than its name &mdash; and a
zip of difference models works like any other CGMES data source. A data source that mixes difference models with full
or partial files is refused, because nothing would define the order in which they apply.

The direct API adds the decision function, the undo and the explicit options:

```java
DifferenceModelSet differences = CgmesDiffImport.read(dataSource);

// Nothing is modified: this answers whether the fast route is possible and why not
Decision decision = CgmesDiffImport.canApplyInPlace(network, differences);
if (decision.route() == Route.FAST) {
    CgmesDiffImport.apply(network, differences, parameters, ReportNode.NO_OP);
    // ... and back again
    CgmesDiffImport.revert(network, differences, parameters, ReportNode.NO_OP);
} else {
    LOGGER.warn("Not applicable in place: {}", decision.reasons());
}
```

`apply` also takes a single `DifferenceModel`, an `InputStream`, a `Path` or a `String`, and throws
`CgmesDiffNotApplicableException` &mdash; which carries the decision &mdash; when the fast route is impossible. The
network is never modified in that case: every check runs before the first mutation.

### How the fast route works

1. the document is parsed into statements by `DifferenceModelParser`, a streaming reader that never builds a graph;
2. `FastRouteCapabilities` decides, from the document alone, whether every property is one an update query reads;
3. every subject is resolved against the network &mdash; by identifier, by CGMES alias (terminals, tap changers) or by
   the properties the importer left on the equipment (regulating controls, generating units, equivalent injections)
   &mdash; which also decides the CIM class to write;
4. properties an update query only reads *together* with others are completed from the receiving network, through the
   very mapping the change exporter uses (`CgmesObjectDump`), so that a minimal difference of a third party applies;
5. the result is written as one synthetic partial SSH document per profile into a fresh in-memory triple store and
   handed to the ordinary update workflow;
6. a difference of the equipment profile alone carries no dated steady state model, so `caseDate` and
   `forecastDistance` are restored afterwards instead of being taken from a store that holds no date;
7. the equipment statements no update query reads &mdash; branch impedances and the limits of a voltage level that
   has no `VoltageLimit` objects &mdash; are applied with plain IIDM setters, mirroring exactly what the conversion
   of a full equipment model does with the same values. They are validated while the plan is built, so a refusal
   still costs nothing;
8. after a CGMES 2.4.15 equipment difference, the equipment values the update workflow falls back on
   (`CGMES.normalValue_*`, `CGMES.high|lowVoltageLimit`) are moved with it. In CGMES 2.4.15 a limit value *is* an
   equipment value, and without this a later steady state update carrying no value for that limit would quietly
   restore the value the difference replaced.

Step 5 is what makes this robust: from there on the fast route *is* the partial SSH update path, with the same reader,
the same SPARQL queries and the same conversion code, so the two cannot drift apart.

### Updatable properties

| CIM classes | properties read together |
| --- | --- |
| `Switch`, `Breaker`, `Disconnector`, `LoadBreakSwitch`, `ProtectedSwitch`, `GroundDisconnector`, `Jumper` | `Switch.open` |
| `Terminal` | `ACDCTerminal.connected` |
| `DCTerminal`, `ACDCConverterDCTerminal` | `ACDCTerminal.connected` |
| `EnergyConsumer`, `ConformLoad`, `NonConformLoad`, `StationSupply` | `EnergyConsumer.p`, `EnergyConsumer.q` |
| `EnergySource` | `EnergySource.activePower`, `EnergySource.reactivePower` |
| `AsynchronousMachine` | `RotatingMachine.p`, `RotatingMachine.q` (+ optional `AsynchronousMachine.asynchronousMachineType`, `RegulatingCondEq.controlEnabled`) |
| `SynchronousMachine` | `RotatingMachine.p`; `RotatingMachine.q`, `SynchronousMachine.referencePriority`, `SynchronousMachine.operatingMode`, `RegulatingCondEq.controlEnabled` |
| `ExternalNetworkInjection` | `ExternalNetworkInjection.p`, `.q`, `.referencePriority`, `RegulatingCondEq.controlEnabled` |
| `EquivalentInjection` | `EquivalentInjection.p`, `.q` (+ optional `.regulationStatus`, `.regulationTarget`) |
| `GeneratingUnit` and its subclasses | `GeneratingUnit.normalPF` |
| `StaticVarCompensator` | `StaticVarCompensator.q`, `RegulatingCondEq.controlEnabled` |
| `LinearShuntCompensator`, `NonlinearShuntCompensator` | `ShuntCompensator.sections`, `RegulatingCondEq.controlEnabled` |
| `RatioTapChanger` | `TapChanger.step`, `TapChanger.controlEnabled` |
| `PhaseTapChanger*` (five flavours) | `TapChanger.step`, `TapChanger.controlEnabled` |
| `RegulatingControl`, `TapChangerControl` | `RegulatingControl.enabled`, `.targetValue`, `.targetValueUnitMultiplier`, `.discrete` (+ optional `.targetDeadband`) |
| `CsConverter` | `ACDCConverter.targetPpcc`, `.targetUdc`, `.p`, `.q`; `CsConverter.operatingMode`, `CsConverter.pPccControl` |
| `VsConverter` | `ACDCConverter.*` as above; `VsConverter.pPccControl`, `.qPccControl` (+ optional `.targetQpcc`, `.targetUpcc`) |
| `ControlArea` | `ControlArea.netInterchange` (+ optional `ControlArea.pTolerance`) |
| `CurrentLimit`, `ActivePowerLimit`, `ApparentPowerLimit`, `VoltageLimit` | `<Class>.value` |

Properties inside one cell are read by one SPARQL block, so stating one of them without the others would silently do
nothing. The importer completes the missing ones from the receiving network instead, which is what makes a minimal
difference applicable. The state variable properties of the update catalogue (`SvPowerFlow.*`, `SvVoltage.*`,
`SvInjection.*`, `SvTapStep.*`, `SvShuntCompensatorSections.*`, `Terminal.TopologicalNode`,
`ACDCConverter.poleLossP`) are explicitly outside the in-place route.

The limit values above belong to the equipment profile in CGMES 2.4.15 and to the steady state hypothesis in
CGMES 3; a document that puts them in the other profile of its CIM version is refused, because the receiver would
never read them.

These equipment properties have no update query at all and are applied with IIDM setters after the update workflow,
which is why they are listed separately:

| CIM classes | properties applied with a setter |
| --- | --- |
| `ACLineSegment` | `ACLineSegment.r`, `.x`, `.gch`, `.bch` |
| `SeriesCompensator` | `SeriesCompensator.r`, `.x` |
| `EquivalentBranch` | `EquivalentBranch.r`, `.x`, `.r21`, `.x21` |
| `VoltageLevel` | `VoltageLevel.highVoltageLimit`, `.lowVoltageLimit` |

`gch` and `bch` are split equally over the two ends of a line and taken as they stand on a boundary line, which is
exactly what the conversion of a full equipment model does. An `EquivalentBranch` states the impedance of both
directions and a conversion refuses one whose `r21`/`x21` differ from its `r`/`x`, so a difference of such a branch
carries all four and each of them sets the single IIDM value. A subject whose CGMES class is a transformer, or a line
the import represented as a switch, is refused: CGMES holds a transformer impedance per end, and a switch has none.

### Accepted document variants

The reader compares namespace URIs and local names, never prefixes, and accepts everything IEC 61970-552 leaves free:

- the `preconditions`, `forwardDifferences` and `reverseDifferences` containers in any order, repeated, empty or absent;
- a model description that is missing entirely, in which case the profile is taken from the file name;
- subjects written as a typed node element or as an `rdf:Description` with an explicit `rdf:type`;
- identifiers written as `#_abc`, `urn:uuid:abc` or `abc`, which are all the same subject;
- properties of a foreign namespace, which are carried as absolute IRIs (and reported as not updatable).

Blank nodes, nested descriptions, a `md:FullModel` document and a document holding two difference models are rejected
rather than half understood.

### Supersedes check and model metadata

A difference is a delta on a named base, so applying it to a network holding a different model of that profile would
silently corrupt it. By default the `md:Model.Supersedes` of the difference therefore has to name the model the
network is at; the check is skipped when the difference declares no `Supersedes` or the network holds no model of the
profile, and it can be switched off with `iidm.import.cgmes.diff.check-supersedes`.

After a successful apply the network holds the difference as its model of that profile &mdash; identifier, version,
description, modeling authority, profiles, `DependentOn` and `Supersedes` &mdash; exactly as an SSH file update would
register it, with the models of the other profiles kept and `caseDate`/`forecastDistance` following the header. Values
a foreign header leaves out are filled in from the model the network was at. Chains work: a difference exported with
`chainAfter` applies on top of the previous one, and reverting them in reverse order walks back.

Undoing has the mirror rule: only a network that is **at** a difference may undo it, so `revert` refuses when the
network holds another model of the profile. Without that, undoing the first difference of a chain while the network
is at the second one would leave a network whose metadata names a predecessor and whose content is neither &mdash;
the same silent corruption the forward check prevents. The same parameter switches both checks off.

A revert registers the *predecessor* named by `Supersedes`, because that is the model the network is at again. Its
description and version are not carried by the difference, so the version is decremented and the other header values
are kept; that is an approximation a layer holding the real metadata can overwrite.

### Reverse differences and preconditions

By default they are parsed and never evaluated: applying a difference means *replacing* the value of a property, not
merging a delta into an unknown state. `iidm.import.cgmes.diff.check-reverse` turns them into a check against the
receiving network &mdash; `warn` reports every mismatch and applies anyway, `fail` refuses. Values are compared as
numbers within a relative tolerance where they parse as such; a property the receiver has no mapping for is reported
as not verifiable and never fails the check.

### Limitations

- **Steady state hypothesis and a part of the equipment profile.** The equipment values a difference can carry are
  the operational limit values, the voltage level limits and the impedances of lines, series compensators,
  equivalent branches and boundary lines. Everything else of the equipment profile needs the slow route, and state
  variables are never applied statement by statement.
- **Transformer impedances are cut.** CGMES holds them per `PowerTransformerEnd` plus the tap step corrections, and
  the import folds both ends into one IIDM value depending on import options the network does not remember, so
  neither direction can be produced faithfully. See the
  [export limitations](export.md#cgmes-difference-model-export) for the full reasoning.
- **A `TieLine` has no impedance of its own**; its two `BoundaryLine`s do, and those are supported.
- **Adding, removing or renaming a limit, and selecting another operational limits group**, are structural changes
  CGMES models with objects, so they are refused.
- **A voltage limit outside the range of its voltage level is dropped by the conversion**, exactly as it is when a
  full file carries it. The export refuses to write one for that reason.
- **A permanent limit the import synthesized** for a CGMES set that has none is not a CGMES object, so no difference
  can carry it; the receiving import derives its own from `missing-permanent-limit-percentage`.
- **No creation or removal.** A difference that introduces or removes an object, or rewires topology, is not an update
  of a live network. `canApplyInPlace` says so before anything is touched.
- **Properties are single valued**, which is what `CgmesStatement` assumes throughout.
- **A header declaring several profiles stays one model** of the profile with the highest priority; the statements of
  the other profile are then reported as not updatable.
- **`use-detailed-dc-model` has to be passed** exactly as for any SSH update, because it decides which DC objects the
  network holds.
- **State variables are reset** for the equipment the difference touches, exactly as any SSH update resets them.
- **A terminal of equipment the update workflow has no pass for** &mdash; a battery, a busbar section &mdash; is
  accepted and then silently ignored, exactly as a partial SSH file carrying it would be.
- **A terminal the difference does not mention keeps its connection state** when
  `iidm.import.cgmes.use-previous-values-during-update` is on. Without that flag an update still defaults an unstated
  terminal to connected, which is what a full instance file means when it omits `cim:ACDCTerminal.connected`.
- **A minimal difference (`CHANGED_ONLY`) of a regulation mode flip cannot be undone.** Minimizing drops every
  property that says the same thing in both directions, which assumes that a property the difference does not state
  does not change. That assumption breaks where the importer *clears* a property as a side effect: a voltage source
  converter that switches from voltage to reactive control keeps its `cim:VsConverter.targetUpcc` in both directions
  of the change &mdash; the sender never touched it &mdash; so it is dropped, while the update sets the receiver's
  voltage setpoint to zero because the converter no longer controls voltage. Undoing then finds the target neither
  in the document nor in the network, and the converter **stays in reactive control**: it is the regulation mode
  that is not restored, not merely an inactive setpoint. The same shape applies to the mode of a static var
  compensator and of a generator. The forward direction is correct in every case, and `FULL_OBJECT`, which states
  whole consistency groups, undoes the same change exactly, so use it for changes that flip a regulation mode.

### Applying a difference to one network variant

`CgmesDiffImport.Options.setVariantSafeOnly(true)` restricts an in-place update to the state IIDM stores **per
network variant**. Every statement whose IIDM target is a single field of the network — an operational limit value,
a voltage limit, a branch impedance, the rating of an HVDC line in the default simplified DC model, an IIDM
property — then blocks the update with a reason naming that field, instead of leaking into the other variants of
the same network. Four cases depend on the receiving network and are decided against it before anything is
written: a reference priority that would create the `ReferencePriorities` extension, a participation factor on a
generator without `ActivePowerControl`, switching the regulation of a tap changer that has no
`loadTapChangingCapabilities` on, and a voltage source converter of the simplified DC model. The flag also
requires the scoped update, because the full update writes properties and validation levels that belong to the
whole network.

There is no import parameter for it: it is set by the layer that binds a variant to a stored state, see
[the RDF database](rdf_database.md), and `Network.update(dataSource)` has no variant to name. The authoritative
table of what is and is not per variant is in that document.

### Performance

Measured on the CGMES 3 `svedala` model (2342 switches), best of ten runs after warm up, 8 cores. One run is one
apply, alternating between the difference and its inverse so that it always does real work:

| what | time |
| --- | --- |
| apply 1 switch change | 3.7 ms (parse 0.4, plan 0.1, store 1.4, update 3.3) |
| apply 500 switch changes | 11.6 ms (parse 1.9, plan 2.1, store 4.1, update 5.3) |
| apply 127 mixed equipment changes | 18.4 ms |
| the same 500 changes as a partial SSH file | 15.6 ms |
| apply 18 operational limit changes of the CGMES 2.4.15 micro grid | 4.9 ms |
| apply 90 line impedance changes of svedala | 3.6 ms (plan 0.3, store 0.7, update 2.4, setters 0.07) |
| apply 140 mixed equipment and steady state changes | 9.8 ms |
| `Network.read(svedala)` | 1054 ms |

Applying a difference is roughly three hundred times cheaper than reading the model again for a single change, and
cheaper than the partial SSH file of the same change. The dominant cost is fixed rather than proportional to the
change: it is the **preparation and evaluation of the SPARQL queries** of the update workflow against the synthetic
store, each of which rdf4j parses again, plus the RDF/XML parse of that store. Two things keep it small:

- the update is restricted to the equipment the difference names, so the passes over the kinds of equipment it does
  not touch are skipped before they query anything, and the ten caches of the update workflow are built one by one
  on first use rather than up front. Together these take a single change from 8.6 ms to 3.7 ms; a full file update
  is unaffected, because it reads all of them anyway;
- the equipment statements applied with IIDM setters cost almost nothing (0.07 ms for ninety lines): the fixed cost
  of an apply is the RDF and SPARQL machinery, not the setters. The index of the CGMES limit identifiers is built
  once per apply and only when a limit subject has to be resolved through it;
- walking the network is not part of the cost either. Restricting the walk is worth about 4 ms of the numbers above
  on this model (10.0 ms scoped against 14.1 ms unscoped for one change, measured during review) and grows with the
  size of the model, which is why it is done although the budget would allow the full walk here.

With state variables present the restriction is a deliberate deviation from a file driven update: the state
variables of equipment the difference does not touch are kept instead of being reset.

### Slow route and long-term vision

**Which difference models does PowSyBl apply?** PowSyBl applies a CGMES difference model *in place* when every change
maps onto the network update workflow (the operating values a partial SSH can carry, plus the equipment values added
over time). `CgmesDiffImport.canApplyInPlace` answers this for any difference model before anything is modified, and
lists the blocking statements otherwise.

**What about everything else?** Applying an arbitrary difference model &mdash; adding or removing objects, rewiring
topology, touching attributes the update workflow does not read &mdash; is the generic RDF operation
*base graph &minus; reverse + forward*. It has no power-system semantics and is independent of PowSyBl; it does not
belong in the CGMES importer. General difference-model application is owned by RDF tooling: OpenCGMES
(`CimDatasetGraph.differenceModelToFullModel` / `FastDeltaGraph` + `CimXmlWriter`) for files, or SPARQL UPDATE in a
graph database. The result is a regular CGMES model that the regular importer reads. PowSyBl will not keep original
files around, will not grow a second RDF engine, and will never apply structural differences to a live network.

**What PowSyBl owns:** (1) the in-place fast route and its steadily widened coverage, (2) the decision function, so
that callers and databases can route a difference without trying, (3) a small statement applier for *its own* triple
store, `CgmesDiffImport.applyToTripleStore(store, difference, contextName, baseName)`, which replaces property values
inside one named graph through SPARQL UPDATE and therefore works on an in-memory store as well as on a remote
repository, and (4) with the RDF database integration, a database-side merge fallback: when a path of differences is
not fast, the base graphs are already at hand in the store, the differences are merged there and the conversion is
re-run &mdash; a slow route without files. OpenCGMES is also the reference the difference-model file structure is
validated against.

(cgmes-import-level-of-detail)=
## Levels of detail: node/breaker and bus/branch

CGMES models defined at node/breaker level of detail will be mapped to PowSyBl node/breaker topology level. CGMES models defined at bus/branch level will be mapped to PowSyBl bus/breaker topology level.

For each equipment in the PowSyBl grid model, it is necessary to specify how it should be connected to the network.

If the model is specified at the bus/breaker level, a `Bus` must be specified for the equipment.

If the voltage level is built at node/breaker level, a `Node` must be specified when adding the equipment to PowSyBl. The conversion will create a different `Node` in PowSyBl for each equipment connection.

Using the `Node` or `Bus` information, PowSyBl creates a `Terminal` that will be used to manage the point of connection of the equipment to the network.

Some equipment, like switches, lines or transformers, have more than one point of connection to the Network.

In PowSyBl, a `Node` can have zero or one terminal. In CGMES, the `ConnectivityNode` objects may have more than one associated terminal. To be able to represent this in PowSyBl, the conversion process will automatically create internal connections between the PowSyBl nodes that represent equipment connections and the nodes created to map `ConnectivityNode` objects.

(cgmes-id-import)=
## Identity of model equipments

Almost all the equipments of the PowSyBl grid model require a unique identifier `Id` and may optionally have a human-readable `Name`. Whenever possible, these attributes will be directly copied from original CGMES attributes.

Terminals are used by CGMES and PowSyBl to define the points of connection of the equipment to the network. CGMES terminals have unique identifiers. PowSyBl does not allow terminals to have an associated identifier. Information about original CGMES terminal identifiers is stored in each PowSyBl object using aliases.

(cgmes-substation-voltage-level-import)=
## Equipment containers: substations and voltage levels

The PowSyBl grid model establishes the substation as a required container of voltage levels and transformers (two- and three-winding transformers and phase shifters). Voltage levels are the required container of the rest of the network equipment, except for the AC and DC transmission lines that establish connections between substations and are directly associated with the network model. All buses at the transformer ends should be kept in the same substation.

The CGMES model does not guarantee these hierarchical constraints, so the first step in the conversion process is to identify all the transformers with ends in different substations and all the breakers and switches with ends in different voltage levels. All the voltage levels connected by breakers or switches should be mapped to a single voltage level in the PowSyBl grid model. The first CGMES voltage level, in alphabetical order, will be the representative voltage level associated with the PowSyBl voltage level. The same criterion is used for substations, and the first CGMES substation will be the representative substation associated with the PowSyBl one. The joined voltage level and substation information is used in almost every step of the mapping between CGMES and PowSyBl models, and it is recorded in the `Context` conversion class, which keeps the data throughout the entire conversion process.

## Conversion from CGMES to PowSyBl grid model

The following sections describe in detail how each supported CGMES network object is converted to PowSyBl network model objects.

(cgmes-substation-import)=
### Substation

For each substation (considering only the representative substation if they are connected by transformers) in the CGMES model a new substation is created in the PowSyBl grid model with the following attributes created as such:
- `Country` It is obtained from the `regionName` property as a first option, from `subRegionName` as second option. Otherwise, is assigned to `null`.
- `GeographicalTags` It is obtained from the `SubRegion` property.

(cgmes-voltage-level-import)=
### VoltageLevel

As for substations, for each voltage level (considering only the representative voltage level if they are connected by switches) in the CGMES model, a new voltage level is created in the PowSyBl grid model with the following attributes created as such:
- `NominalV` It is copied from the `nominalVoltage` property of the CGMES voltage level.
- `TopologyKind` It will be `NODE_BREAKER` or `BUS_BREAKER` depending on the level of detail of the CGMES grid model.
- `LowVoltageLimit` It is copied from the `lowVoltageLimit` property.
- `HighVoltageLimit` It is copied from the `highVoltageLimit` property.

(cgmes-connectivity-node-import)=
### ConnectivityNode

If the CGMES model is a node/breaker model then `ConnectivityNode` objects are present in the CGMES input files, and for each of them a new `Node` is created in the corresponding PowSyBl voltage level. A `Node` in the PowSyBl model is an integer identifier that is unique by voltage level.

If the import option `iidm.import.cgmes.create-busbar-section-for-every-connectivity-node` is `true` an additional busbar section is also created in the same voltage level. This option is used to debug the conversion and facilitate the comparison of the topology present in the CGMES input files and the topology computed by PowSyBl. The attributes of the busbar section are created as such:
- Identity attributes `Id` and `Name` are copied from the `ConnectivityNode`.
- `Node` The same `Node` assigned to the mapped `ConnectivityNode`.

(cgmes-topological-node-import)=
### TopologicalNode

If the CGMES model is defined at bus/branch detail, then `TopologicalNode` objects are used in the conversion, and for each of them a `Bus` is created in the PowSyBl grid model inside the corresponding voltage level container, at the PowSyBl bus/breaker topology level. The created `Bus` has the following attributes:
- Identity attributes `Id` and `Name` are copied from the `TopologicalNode`.
- `V` The voltage of the `TopologicalNode` is copied if it is valid (greater than `0`).
- `Angle` The angle the `TopologicalNode` is copied if the previous voltage is valid.

(cgmes-busbar-section-import)=
### BusbarSection

Busbar sections can be created in PowSyBl grid model only at node/breaker level.

CGMES Busbar sections are mapped to PowSyBl busbar sections only if CGMES is node/breaker and the import option `iidm.import.cgmes.create-busbar-section-for-every-connectivity-node` is set to `false`. In this case, a `BusbarSection` is created in the PowSyBl grid model for each `BusbarSection` of the CGMES model, with the attributes created as such:
- Identity attributes `Id` and `Name` are copied from the CGMES `BusbarSection`.
- `Node` A new `Node` in the corresponding voltage level.

(cgmes-energy-consumer-import)=
### EnergyConsumer

Every `EnergyConsumer` in the CGMES model creates a new `Load` in PowSyBl. The attributes are created as such:
- `P0`, `Q0` are set from CGMES values taken from `SSH`, `SV`, or `EQ` data depending on which are defined.
- `LoadType` It will be `FICTITIOUS` if the `Id` of the `energyConsumer` contains the pattern `fict`. Otherwise `UNDEFINED`.
- `LoadDetail` Additional information about conform and non-conform loads is added as an extension of the `Load` object (for more details about the [extension](../../grid_model/extensions.md#load-detail)).

The `LoadDetail` extension attributes depend on the `type` property of the `EnergyConsumer`. For a conform load:
- `withFixedActivePower` is always `0`.
- `withFixedReactivePower` is always `0`.
- `withVariableActivePower` is set to the Load `P0`.
- `withVariableReactivePower` is set to the Load `Q0`.

When the type is a non-conform load:
- `withFixedActivePower` is set to the Load `P0`.
- `withFixedReactivePower` is set to the Load `Q0`.
- `withVariableActivePower` is set to `0`.
- `withVariableReactivePower` is set to `0`.

(cgmes-energy-source-import)=
### EnergySource

An `EnergySource` is a generic equivalent for an energy supplier, with the injection given using load sign convention.

For each `EnergySource` object in the CGMES model a new PowSyBl `Load` is created, with attributes created as such:
- `P0`, `Q0` set from `SSH` or `SV` values depending on which are defined.
- `LoadType` It will be `FICTITIOUS` if the `Id` of the `energySource` contains the pattern `fict`. Otherwise `UNDEFINED`.

(cgmes-sv-injection-import)=
### SvInjection

CGMES uses `SvInjection` objects to report mismatches on calculated buses: they record the calculated bus injection minus the sum of the terminal flows. According to the documentation, the values will thus follow generator sign convention: positive sign means injection into the bus. Note that all the reference cases used for development follow load sign convention to report these mismatches, so we have decided to follow this load sign convention as a first approach.

For each `SvInjection` in the CGMES network model a new PowSyBl `Load` with attributes created as such:
- `P0`, `Q0` are set from `SvInjection.pInjection/qInjection`.
- `LoadType` is always set to `FICTITIOUS`.
- `Fictitious` is set to `true`.

(cgmes-equivalent-injection-import)=
### EquivalentInjection

The mapping of an `EquivalentInjection` depends on its location relative to the boundary area.

If the `EquivalentInjection` is outside the boundary area, it will be mapped to a PowSyBl `Generator`.

If the `EquivalentInjection` is at the boundary area, its regulating voltage data will be mapped to the generation data inside the PowSyBl `BoundaryLine` created at the boundary point and its values for `P`, `Q` will be used to define the BoundaryLine `P0`, `Q0`. Please note that the said `BoundaryLine` can be created from an [`ACLineSegment`](#aclinesegment), a [`Switch`](#switch-switch-breaker-disconnector-loadbreakswitch-protectedswitch-grounddisconnector-jumper),
an [`EquivalentBranch`](#equivalentbranch) or a [`PowerTransformer`](#powertransformer).

Attributes of the PowSyBl generator or of the PowSyBl boundary line generation are created as such:
- `MinP`/`MaxP` are copied from CGMES `minP`/`maxP` if defined, otherwise they are set to `-Double.MAX_VALUE`/`Double.MAX_VALUE`.
- `TargetP`/`TargetQ` are set from `SSH` or `SV` values depending on which are defined. CGMES values for `p`/`q` are given with load sign convention, so a change in sign is applied when copying them to `TargetP`/`TargetQ`.
- `TargetV` The `regulationTarget` property is copied if it is not equal to zero. Otherwise, the nominal voltage associated to the connected terminal of the `equivalentInjection` is assigned. For CGMES Equivalent Injections, the voltage regulation is allowed only at the point of connection.
- `VoltageRegulatorOn` It is assigned to `true` if both properties, `regulationCapability` and `regulationStatus` are `true` and the terminal is connected.
- `EnergySource` is set to `OTHER`.

(cgmes-ac-line-segment-import)=
### ACLineSegment

`ACLineSegments`' mapping depends on its location relative to the boundary area.

If the `ACLineSegment` is outside the boundary area, it will be mapped to a PowSyBl [`Line`](../../grid_model/network_subnetwork.md#line).

If the `ACLineSegment` is completely inside the boundary area, if the boundaries are not imported, it is ignored. Otherwise, it is mapped to a PowSyBl [`Line`](../../grid_model/network_subnetwork.md#line).

If the `ACLineSegment` has one side inside the boundary area and one side outside the boundary area, the importer checks if another branch is connected to the same [`TopologicalNode`](#topologicalnode) in the boundary area.
- If there is no other branch connected to this `TopologicalNode`, it will be mapped to a PowSyBl [`BoundaryLine`](../../grid_model/network_subnetwork.md#boundary-line).
- If there are one or more other branches connected to this `TopologicalNode` and they all are in the same `SubGeographicalRegion`, they will all be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line).
- If there is exactly one other branch connected to this `TopologicalNode` in another `SubGeographicalRegion`, they will both be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line), which are part of the same PowSyBl [`TieLine`](../../grid_model/network_subnetwork.md#tie-line).
- If there are two or more other branches connected to this `TopologicalNode` in different `SubGeographicalRegions`:
  - If there are only two branches with their boundary terminal connected and in different `SubGeographicalRegion`, they will both be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line), which are part of the same PowSyBl [`TieLine`](../../grid_model/network_subnetwork.md#tie-line) and all other `ACLineSegments` will be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line).
  - Otherwise, they will all be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line).

If the `ACLineSegment` is mapped to a PowSyBl [`Line`](../../grid_model/network_subnetwork.md#line):
- `R` is copied from CGMES `r`
- `X` is copied from CGMES `x`
- `G1` is calculated as half of CGMES `gch` if defined, `0.0` otherwise
- `G2` is calculated as half of CGMES `gch` if defined, `0.0` otherwise
- `B1` is calculated as half of CGMES `bch`
- `B2` is calculated as half of CGMES `bch`

If the `ACLineSegment` is mapped to an unpaired PowSyBl [`BoundaryLine`](../../grid_model/network_subnetwork.md#boundary-line):
- `R` is copied from CGMES `r`
- `X` is copied from CGMES `x`
- `G` is copied from CGMES `gch` if defined, `0.0` otherwise
- `B` is copied from CGMES `bch`
- `PairingKey` is copied from the name of the `TopologicalNode` or the `ConnectivityNode` (respectively in `NODE-BREAKER` or `BUS-BRANCH`) inside boundaries
- `P0` is copied from CGMES `P` of the terminal at boundary side
- `Q0` is copied from CGMES `Q` of the terminal at boundary side

If the `ACLineSegment` is mapped to a paired PowSyBl [`BoundaryLine`](../../grid_model/network_subnetwork.md#boundary-line):
- `R` is copied from CGMES `r`
- `X` is copied from CGMES `x`
- `G1` is `0.0` is the boundary line is on side `ONE` of the Tie Line. If the boundary line is on side `TWO` of the Tie Line, it is copied from CGMES `gch` if defined, `0.0` otherwise.
- `G2` is `0.0` is the boundary line is on side `TWO` of the Tie Line. If the boundary line is on side `ONE` of the Tie Line, it is copied from CGMES `gch` if defined, `0.0` otherwise.
- `B1` is `0.0` is the boundary line is on side `ONE` of the Tie Line. If the boundary line is on side `TWO` of the Tie Line, it is copied from CGMES `bch`.
- `B2` is `0.0` is the boundary line is on side `TWO` of the Tie Line. If the boundary line is on side `ONE` of the Tie Line, it is copied from CGMES `bch`.
- `PairingKey` is copied from the name of the `TopologicalNode` or the `ConnectivityNode` (respectively in `NODE-BREAKER` or `BUS-BRANCH`) inside boundaries

(cgmes-equivalent-branch-import)=
### EquivalentBranch

Equivalent branches mapping depends on its location relative to the boundary area.

If the `EquivalentBranch` is outside the boundary area, it will be mapped to a PowSyBl [`Line`](../../grid_model/network_subnetwork.md#line).

If the `EquivalentBranch` is completely inside the boundary area, if the boundaries are not imported, it is ignored. Otherwise, it is mapped to a PowSyBl [`Line`](../../grid_model/network_subnetwork.md#line).

If the `EquivalentBranch` has one side inside the boundary area and one side outside the boundary area, the importer checks if another branch is connected to the same [`TopologicalNode`](#topologicalnode) in the boundary area.
- If there is no other branch connected to this `TopologicalNode`, it will be mapped to a PowSyBl [`BoundaryLine`](../../grid_model/network_subnetwork.md#boundary-line).
- If there are one or more other branches connected to this `TopologicalNode` and they all are in the same `SubGeographicalRegion`, they will all be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line).
- If there is exactly one other branch connected to this `TopologicalNode` in another `SubGeographicalRegion`, they will both be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line), which are part of the same PowSyBl [`TieLine`](../../grid_model/network_subnetwork.md#tie-line).
- If there are two or more other branches connected to this `TopologicalNode` in different `SubGeographicalRegions`:
  - If there are only two branches connected with their boundary terminal connected and in different `SubGeographicalRegion`, they will both be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line), which are part of the same PowSyBl [`TieLine`](../../grid_model/network_subnetwork.md#tie-line) and all other `EquivalentBranches` will be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line).
  - Otherwise, they will all be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line).

If the `EquivalentBranch` is mapped to a PowSyBl [`Line`](../../grid_model/network_subnetwork.md#line):
- `R` is copied from CGMES `r`
- `X` is copied from CGMES `x`
- `G1` is `0.0`
- `G2` is `0.0`
- `B1` is `0.0`
- `B2` is `0.0`

If the `EquivalentBranch` is mapped to a PowSyBl [`BoundaryLine`](../../grid_model/network_subnetwork.md#boundary-line):
- `R` is copied from CGMES `r`
- `X` is copied from CGMES `x`
- `G` is `0.0`
- `B` is `0.0`
- `PairingKey` is copied from the name of the `TopologicalNode` or the `ConnectivityNode` (respectively in `NODE-BREAKER` or `BUS-BRANCH`) inside boundaries
- `P0` is copied from CGMES `P` of the terminal at boundary side
- `Q0` is copied from CGMES `Q` of the terminal at boundary side

(cgmes-asynchronous-machine-import)=
### AsynchronousMachine

Asynchronous machines represent rotating machines whose shaft rotates asynchronously with the electrical field.
It can be motor or generator; no distinction is made for the conversion of these two types.

An `AsynchronousMachine` is mapped to a PowSyBl [`Load`](../../grid_model/network_subnetwork.md#load) with attributes created as described below:
- `P0`, `Q0` are set from CGMES values taken from `SSH` or `SV`data depending on which are defined. If there is no defined data, it is `0.0`.
- `LoadType` is `FICTITIOUS` if the CGMES ID contains "fict". Otherwise, it is `UNDEFINED`.

(cgmes-synchronous-machine-import)=
### SynchronousMachine

Synchronous machines represent rotating machines whose shaft rotates synchronously with the electrical field.
It can be motor or generator; no distinction is made for the conversion of these two types.

A `SynchronousMachine` is mapped to a PowSyBl [`Generator`](../../grid_model/network_subnetwork.md#generator) with attributes created as described below:
- `MinP` is set from `GeneratingUnit.minOperatingP` on the `GeneratingUnit` associated with the `SynchronousMachine`. If invalid, `MinP` is `-Double.MAX_VALUE`.
- `MaxP` is set from `GeneratingUnit.maxOperatingP` on the `GeneratingUnit` associated with the `SynchronousMachine`. If invalid, `MaxP` is `Double.MAX_VALUE`.
- `ratedS` is copied from CGMES `ratedS`. If it is strictly lower than 0, it is considered undefined.
- `EnergySource` is defined from the `GeneratingUnit` class of the `GeneratingUnit` associated with the `SynchronousMachine`
  - If it is a `HydroGeneratingUnit`, `EnergySource` is `HYDRO`
  - If it is a `NuclearGeneratingUnit`, `EnergySource` is `NUCLEAR`
  - If it is a `ThermalGeneratingUnit`, `EnergySource` is `THERMAL`
  - If it is a `WindGeneratingUnit`, `EnergySource` is `WIND`. Additionally, the `WindGeneratingUnit.windGenUnitType` value (`onshore` or `offshore`) is stored as an iIDM property `CGMES.windGenUnitType` of the generator.
  - If it is a `SolarGeneratingUnit`, `EnergySource` is `SOLAR`
  - Else, `EnergySource` is `OTHER`
- `TargetP`/`TargetQ` are set from `SSH` or `SV` values depending on which are defined. CGMES values for `p`/`q` are given with load sign convention, so a change in sign is applied when copying them to `TargetP`/`TargetQ`. If undefined, `TargetP` is set from CGMES `GeneratingUnit.initialP` from the `GeneratingUnit` associated to the `SynchronousMachine` and `TargetQ` is set to `0`.
- `isCondenser` is defined from the `SynchronousMachine.type`. If it contains `condenser` (`condenser`,
`generatorOrCondenser`, `motorOrCondenser`, `generatorOrMotorOrCondenser`), then the flag is set to `true`. Otherwise, it is set to `false`.

<span style="color: red">TODO reactive limits</span>

<span style="color: red">TODO regulation</span>

<span style="color: red">TODO normalPF</span>

(cgmes-equivalent-shunt-import)=
### EquivalentShunt

An `EquivalentShunt` is mapped to a PowSyBl linear [`ShuntCompensator`](../../grid_model/network_subnetwork.md#shunt-compensator). A linear shunt compensator has banks or sections with equal admittance values.
Its attributes are created as described below:
- `SectionCount` is `1` if the `EquivalentShunt` CGMES `Terminal` is connected, else it is `0`.
- `BPerSection` is copied from CGMES `b`
- `MaximumSectionCount` is set to `1`

(cgmes-external-network-injection-import)=
### ExternalNetworkInjection

External network injections are injections representing the flows from an entire external network.

An `ExternalNetworkinjection` is mapped to a PowSyBl [`Generator`](../../grid_model/network_subnetwork.md#generator) with attributes created as described below:
- `MinP` is copied from CGMES `minP`
- `MaxP` is copied from CGMES `maxP`
- `TargetP`/`TargetQ` are set from `SSH` or `SV` values depending on which are defined. CGMES values for `p`/`q` are given with load sign convention, so a change in sign is applied when copying them to `TargetP`/`TargetQ`. If undefined, they are set to `0`.
- `EnergySource` is set as `OTHER`

The [`Reference Priority`](../../grid_model/extensions.md#reference-priorities) extension is created from the `ExternalNetworkInjection.referencePriority` attribute in `SSH`.

<span style="color: red">TODO reactive limits</span>

<span style="color: red">TODO regulation</span>

(cgmes-linear-shunt-compensator-import)=
### LinearShuntCompensator

Linear shunt compensators represent shunt compensators with banks or sections with equal admittance values.

A `LinearShuntCompensator` is mapped to a PowSyBl [`ShuntCompensator`](../../grid_model/network_subnetwork.md#shunt-compensator) with `SectionCount` copied from CGMES SSH `sections` if present. If not, it is copied from CGMES `SvShuntCompensatorSections.sections` or `normalSections`.
The `SolvedSectionCount` is copied from `SvShuntCompensatorSections.sections` if the SV is imported, and left to `null` otherwise.
The created PowSyBl shunt compensator is linear, and its attributes are defined as described below:
- `BPerSection` is copied from CGMES `bPerSection` if defined. Else, it is `Float.MIN_VALUE`.
- `GPerSection` is copied from CGMES `gPerSection` if defined. Else, it is left undefined.
- `MaximumSectionCount` is copied from CGMES `maximumSections`.

<span style="color: red">TODO regulation</span>

(cgmes-nonlinear-shunt-compensator-import)=
### NonlinearShuntCompensator

Non-linear shunt compensators represent shunt compensators with banks or section admittance values that differ.

A `NonlinearShuntCompensator` is mapped to a PowSyBl [`ShuntCompensator`](../../grid_model/network_subnetwork.md#shunt-compensator) with `SectionCount` copied from CGMES SSH `sections` if present. If not, it is copied from CGMES `SvShuntCompensatorSections.sections` or `normalSections`.
The `SolvedSectionCount` is copied from `SvShuntCompensatorSections.sections` if the SV is imported, and left to `null` otherwise.
The created PowSyBl shunt compensator is non-linear and has as many `Sections` as there are `NonlinearShuntCompensatorPoint` associated with the `NonlinearShuntCompensator` it is mapped to.

Sections are created from the lowest CGMES `sectionNumber` to the highest and each section has its attributes created as described below:
- `B` is calculated as the sum of all CGMES `b` of `NonlinearShuntCompensatorPoints` with `sectionNumber` lower or equal to its `sectionNumber`
- `G` is calculated as the sum of all CGMES `g` of `NonlinearShuntCompensatorPoints` with `sectionNumber` lower or equal to its `sectionNumber`

<span style="color: red">TODO regulation</span>

(cgmes-operational_limit-import)=
### OperationalLimits

OperationalLimits model a specification of limits associated with equipments.

#### OperationalLimitSet

A CGMES `OperationalLimitSet` is a set of `OperationalLimit` associated with equipment or terminal. It is mapped to a PowSyBl [`OperationalLimitsGroup`](../../grid_model/additional.md#limit-group-collection).

Just like CGMES allows to attach multiple `OperationalLimitSet` on the same equipment or terminal, PowSyBl stores a collection of `OperationalLimitsGroup` for every 
[`Line`](../../grid_model/network_subnetwork.md#line) side, [`BoundaryLine`](../../grid_model/network_subnetwork.md#boundary-line) and [`ThreeWindingTransformer.Leg`](../../grid_model/network_subnetwork.md#three-winding-transformer-leg).

The same way a CGMES `OperationalLimitSet` may contain `OperationalLimit` of different subclasses, a PowSyBl `OperationalLimitsGroup` may have multipe non-null `LoadingLimits`.

If there is only one `OperationalLimitsGroup` on an end, it automatically gets to be selected (active). However, if there is multiple groups, none is selected: the user has to choose which set is active.

#### OperationalLimit

A CGMES `OperationalLimit` is an abstract class that represent different kinds of limits: current, active power or apparent power.
The collection of the same subclass of CGMES `OperationalLimit` in the set is mapped to a PowSyBl [`LoadingLimits`](../../grid_model/additional.md#loading-limits) as follows:
- The collection of CGMES `CurrentLimit` in the `OperationalLimitSet` is mapped to the `currentLimits` attribute of the PowSyBl `OperationalLimitsGroup` corresponding to the set.
- The collection of CGMES `ActivePowerLimit` in the `OperationalLimitSet` is mapped to the `activePowerLimits` attribute of the PowSyBl `OperationalLimitsGroup` corresponding to the set.
- The collection of CGMES `ApparentPowerLimit` in the `OperationalLimitSet` is mapped to the `apparentPowerLimits` attribute of the PowSyBl `OperationalLimitsGroup` corresponding to the set.

A particular CGMES `OperationalLimit` is mapped differently depending on its associated CGMES `OperationalLimitType`:
- A permanent limit (`OperationalLimitType.limitType` is `LimitTypeKind.patl`) is mapped as follows:
    - PowSyBl `LoadingLimits.permanentLimit` is copied from CGMES `OperationalLimit.value`
- A temporary limit (`OperationalLimitType.limitType` is `LimitTypeKind.tatl`) is mapped as follows:
    - A new entry is created in PowSyBl `LoadingLimits.temporaryLimits`
    - `name` is copied from `OperationalLimit.name`
    - `value` is copied from `OperationalLimit.value`
    - `acceptableDuration` is copied from `OperationalType.acceptableDuration`

(cgmes-power-transformer-import)=
### PowerTransformer

Power transformers represent electrical devices consisting of two or more coupled windings, each represented by a `PowerTransformerEnd`. PowSyBl only supports `PowerTransformers` with two or three windings.

#### PowerTransformer with two PowerTransformerEnds

If a `PowerTransformer` has two `PowerTransformerEnds`, both outside the boundary area, it is mapped to a PowSyBl [`TwoWindingsTransformer`](../../grid_model/network_subnetwork.md#two-winding-transformer).
Please note that in this case, if `PowerTransformerEnds` are in different substations, the substations are merged into one.

If a `PowerTransformer` has two `PowerTransformerEnds`, both completely inside the boundary area, and if the boundary area is not imported, the `PowerTransformer` is ignored. Otherwise, it is mapped to a PowSyBl [`TwoWindingsTransformer`](../../grid_model/network_subnetwork.md#two-winding-transformer).

If the `PowerTransformer` has one `PowerTransformerEnd` inside the boundary area and the other outside the boundary area, the importer checks if another branch is connected to the same [`TopologicalNode`](#topologicalnode) in the boundary area.
- If there is no other connected to this `TopologicalNode`, it is mapped to a PowSyBl [`BoundaryLine`](../../grid_model/network_subnetwork.md#boundary-line).
- If there is one or more other branches connected to this `TopologicalNode` and they are all in the same `SubGeographicalRegion`, they will all be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line).
- If there is exactly one other branch connected to this `TopologicalNode` in another `SubGeographicalRegion`, they will both be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line), which are part of the same PowSyBl [`TieLine`](../../grid_model/network_subnetwork.md#tie-line).
- If there are two or more other branches connected to this `TopologicalNode` in different `SubGeographicalRegions`:
  - If there are only two branches with their boundary terminal connected and in different `SubGeographicalRegion`, they will both be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line), which are part of the same PowSyBl [`TieLine`](../../grid_model/network_subnetwork.md#tie-line) and all other `EquivalentBranches` will be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line).
  - Otherwise, they will all be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line).

In every case, a `PowerTransformer` with two `PowerTransformerEnds` is mapped to an intermediary model that corresponds to a PowSyBl [`TwoWindingsTransformer`](../../grid_model/network_subnetwork.md#two-winding-transformer).
For more information about this conversion, please look at the classes [`InterpretedT2xModel`](https://github.com/powsybl/powsybl-core/blob/main/cgmes/cgmes-conversion/src/main/java/com/powsybl/cgmes/conversion/elements/transformers/InterpretedT2xModel.java)
and [`ConvertedT2xModel`](https://github.com/powsybl/powsybl-core/blob/main/cgmes/cgmes-conversion/src/main/java/com/powsybl/cgmes/conversion/elements/transformers/ConvertedT2xModel.java).

If the `PowerTransformer` is finally mapped to a PowSyBl [`BoundaryLine`](../../grid_model/network_subnetwork.md#boundary-line), its structural attributes (`R`, `X`, `G` and `B`) are calculated from the intermediary model's attributes, and the ratio from its ratio tap changer and/or its phase tap changer.
`P0` and `Q0` are set from CGMES `P` and `Q` values at boundary side; `PairingKey` is copied from the name of the `TopologicalNode` or the `ConnectivityNode` (respectively in `NODE-BREAKER` or `BUS-BRANCH`) inside boundaries.

If the `PowerTransformer` is finally mapped to a PowSyBl [`BoundaryLine`](../../grid_model/network_subnetwork.md#boundary-line), its attributes are calculated using a standard $$\pi$$ model with distributed parameters.

#### PowerTransformer with three PowerTransformerEnds

A `PowerTransformer` with three `PowerTransformerEnds` is mapped to a PowSyBl [`ThreeWindingsTransformer`](../../grid_model/network_subnetwork.md#three-winding-transformer).
Please note that in this case, if `PowerTransformerEnds` are in different substations, the substations are merged into one.

For more information about this conversion, please look at the classes [`InterpretedT3xModel`](https://github.com/powsybl/powsybl-core/blob/main/cgmes/cgmes-conversion/src/main/java/com/powsybl/cgmes/conversion/elements/transformers/InterpretedT3xModel.java)
and [`ConvertedT3xModel`](https://github.com/powsybl/powsybl-core/blob/main/cgmes/cgmes-conversion/src/main/java/com/powsybl/cgmes/conversion/elements/transformers/ConvertedT3xModel.java).

(cgmes-series-compensator-import)=
### SeriesCompensator

Series compensators represent series capacitors or reactors or AC transmission lines without charging susceptance.

If a `SeriesCompensator` has both its ends inside the same voltage level, it is mapped to a PowSyBl [`Switch`](../../grid_model/network_subnetwork.md#breakerswitch). In this case,
all its CGMES electrical attributes are ignored. It is considered as closed, fictitious and, if it is in a node-breaker voltage level, retained. Its `SwitchKind` is `BREAKER`.

If a `SeriesCompensator` has its ends inside different voltage levels, it is mapped to a PowSyBl [`Line`](../../grid_model/network_subnetwork.md#line) with attributes as described below:
- `R` is copied from CGMES `r`
- `X` is copied from CGMES `x`
- `G1`, `G2`, `B1` and `B2` are set to `0`

(cgmes-static-var-compensator-import)=
### StaticVarCompensator

Static VAR compensators represent a facility for providing variable and controllable shunt reactive power.

A `StaticVarCompensator` is mapped to a PowSyBl [`StaticVarCompensator`](../../grid_model/network_subnetwork.md#static-var-compensator) with attributes as described below:
- `Bmin` is calculated from CGMES `inductiveRating`: if it is defined and not equals to `0`, `Bmin` is `1 / inductiveRating`. Else, it is `-Double.MAX_VALUE`.
- `Bmax` is calculated from CGMES `capacitiveRating`: if it defined and not equals to `0`, `Bmax` is `1 / capacitiveRating`. Else, it is `Double.MAX_VALUE`.

A PowSyBl [`VoltagePerReactivePowerControl`](../../grid_model/extensions.md#voltage-per-reactive-power-control) extension is also created from the CGMES `StaticVarCompensator` and linked to the PowSyBl `StaticVarCompensator` with its `slope` attribute copied from CGMES `slope` if the latter is `0` or positive.

<span style="color: red">TODO regulation</span>

(cgmes-switch-import)=
### Switch (Switch, Breaker, Disconnector, LoadBreakSwitch, ProtectedSwitch, GroundDisconnector, Jumper)

Switches, breakers, disconnectors, load break switches, protected switches, jumpers and ground disconnectors are
all imported in the same manner. For convenience purposes, we will now use `Switch` as a say but keep in mind that this section is valid for all these CGMES classes.

If the `Switch` has its ends both inside the same voltage level, it is mapped to a PowSyBl [`Switch`](../../grid_model/network_subnetwork.md#breakerswitch) with attributes as described below:
- `SwitchKind` is defined depending on the CGMES class
  - If it is a CGMES `Breaker`, `Switch` or `ProtectedSwitch`, it is `BREAKER`
  - If it is a CGMES `Disconnector`, `GroundDisconnector` or `Jumper` it is `DISCONNECTOR`
  - If it is a CGMES `LoadBreakSwitch`, it is `LOAD_BREAK_SWITCH`
- `Retained` is copied from CGMES `retained` if defined in node-breaker. Else, it is `false`.
- `Open` is copied from CGMES SSH `open` if defined. Else, it is copied from CGMES `normalOpen`. If neither are defined, it is `false`.

If the CGMES `Switch` has its ends in different voltage levels inside the same IGM, it is mapped to a [`Switch`](../../grid_model/network_subnetwork.md#breakerswitch) but the voltage levels, and potentially the substations, that contain its ends are merged: they are mapped to only one voltage level and/or substation.
The created PowSyBl `Switch` has its attributes defined as described above.

If the `Switch` has one side inside the boundary area and the other side outside the boundary area, the importer checks if another branch is connected to the same CGMES [`TopologicalNode`](#topologicalnode) in the boundary area.
- If there is no other branch connected to this `TopologicalNode`, it will be mapped to a PowSyBl [`BoundaryLine`](../../grid_model/network_subnetwork.md#boundary-line).
- If there are one or more other branches connected to this `TopologicalNode` and they all are in the same `SubGeographicalRegion`, they will all be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line).
- If there is exactly one other branch connected to this `TopologicalNode` in another `SubGeographicalRegion`, they will both be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line), which are part of the same PowSyBl [`TieLine`](../../grid_model/network_subnetwork.md#tie-line).
- If there are two or more other branches connected to this `TopologicalNode` in different `SubGeographicalRegions`:
  - If there are only two branches with their boundary terminal connected and in different `SubGeographicalRegion`, they will both mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line), which are part of the same PowSyBl [`TieLine`](../../grid_model/network_subnetwork.md#tie-line) and all other `EquivalentBranches` will be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line).
  - Otherwise, they will all be mapped to PowSyBl [`BoundaryLines`](../../grid_model/network_subnetwork.md#boundary-line).

If the CGMES `Switch` is mapped to a PowSyBl [`BoundaryLine`](../../grid_model/network_subnetwork.md#boundary-line), its attributes are as described below:
- `R`, `X`, `G`, `B` are `0.0`;
- `PairingKey` is copied from the name of the `TopologicalNode` or the `ConnectivityNode` (respectively in `NODE-BREAKER` or `BUS-BRANCH`) inside boundaries;
- `P0` is copied from CGMES `P` of the terminal at boundary side;
- `Q0` is copied from CGMES `Q` of the terminal at boundary side

(cgmes-control-area-import)=
### Control areas

CGMES control areas (objects of class `ControlArea`) are mapped directly to PowSyBl objects of type `Area`.

The control area CGMES `type` is copied as a string in the `areaType` attribute of the PowSyBl `Area`. The CGMES `netInterchange` is copied to the PowSyBl `interchangeTarget`. If CGMES `pTolerance` is defined, its value is copied to a new property named `pTolerance`. Finally, if an attribute `entsoe:IdentifiedObject.energyIdentCodeEic` is found for the CGMES control area, it is added as an alias with `aliasType == "energyIdentCodeEic"`.

The CGMES control area tie flows (objects of class `TieFlow`) are mapped to PowSyBl `Area` boundary items. 
Boundary items can be terminals (if the corresponding CGMES point can be mapped to a PowSyBl `Terminal`) or boundaries, when the corresponding CGMES point is the boundary side of a boundary line in PowSyBl.

(cgmes-reduced-dc-model-import)=
### Reduced DC model

The reduced DC model allows the support of the following simple DC configurations:
- Monopole with ground return.
- Monopole with metallic return.
- Bipole with dedicated metallic return (DMR).
- Bipole without DMR.

In the above point-to-point configurations, each `DCConverterUnit` can contain
1 CGMES `ACDCConverter` (1* 12-pulse bridge) or 2 CGMES `ACDCConverter` (2* 6-pulses bridges).

Other configurations such as back-to-back, multi-terminal or hybrid aren't supported with the reduced DC model.
If one of these complex DC configurations is to be imported, it is required to set the optional parameter
`iidm.import.cgmes.use-detailed-dc-model` to `true`.

Each valid DC configuration is mapped to PowSyBl as follows:
- 1 CGMES `ACDCConverter` is always mapped to 1 PowSyBl `HvdcConverterStation`:
  - CGMES subclass `CsConverter` is mapped to PowSyBl subclass `LccConverterStation`.
  - CGMES subclass `VsConverter` is mapped to PowSyBl subclass `VscConverterStation`.
- 1 or 2 CGMES `DCLineSegment` are mapped to 1 or 2 `HvdcLine`.
  - See table below that shows when dc lines are merged or split.

| Configuration                                   | Number of converters<br/>(CGMES or PowSyBl) | Number of<br/>CGMES DCLineSegment | Number of<br/>PowSyBl HvdcLine |
|-------------------------------------------------|---------------------------------------------|-----------------------------------|--------------------------------|
| Monopole, metallic return                       | 1                                           | 2                                 | 1                              |
| Monopole, ground return,<br/>1 bridge per unit  | 1                                           | 1                                 | 1                              |
| Monopole, ground return,<br/>2 bridges per unit | 2                                           | 1                                 | 2                              |
| Bipole                                          | 2                                           | 2 (*)                             | 2                              |
| Bipole, 2 bridges per unit                      | 4                                           | 2 (*)                             | 4                              |

- (*) The DMR is never considered for the mapping since no flow runs through it.

The merging or splitting of dc lines is necessary to always end up with triplets:
`HvdcConverterStation` (side 1) + `HvdcLine` + `HvdcConverterStation` (side 2) in PowSyBl.

The detail mapping of the classes is detailed below.

#### DCLineSegment

The mapping of CGMES `DCLineSegment` to PowSyBl `HvdcLine` isn't done in isolation, but always in association with the `ACDCConverter` on each side it is connected to.

The PowSyBl `R` value is mapped as follows:
- If the CGMES to PowSyBl cardinality is 1 to 1, the PowSyBl `R` value is copied from CGMES EQ `r`.
- If the CGMES to PowSyBl cardinality is 2 to 1, the PowSyBl `R` value is the sum of the CGMES EQ `r`: $R = r_{1} + r_{2}$
- If the CGMES to PowSyBl cardinality is 1 to 2, each PowSyBl `R` is equal to half the CGMES EQ `r`: $R_{1} = R_{2} = \frac{r}{2}$

The PowSyBl `NominalV` is copied from side 1 converter CGMES EQ `ACDCConverter.ratedUdc`.

The PowSyBl `ConvertersMode` is determined from the 2 neighbouring `ACDCConverter`:
- If one of the CGMES SSH `ACDCConverter.targetPpcc` is set and has a positive value,
or in the case of LCC lines if one of the CGMES SSH `CsConverter.operatingMode` is set to `CsOperatingModeKind.rectifier`,
then this converter is the rectifier, and the one on the other side is the inverter.
- If one of the CGMES SSH `ACDCConverter.targetPpcc` is set and has a negative value,
  or in the case of LCC lines if one of the CGMES SSH `CsConverter.operatingMode` is set to `CsOperatingModeKind.inverter`,
  then this converter is the inverter, and the one on the other side is the rectifier.
- Based on above results and on which side each converter is located, the PowSyBl `ConvertersMode` is then computed to `SIDE_1_RECTIFIER_SIDE_2_INVERTER` or `SIDE_1_INVERTER_SIDE_2_RECTIFIER`.
In case the information couldn't be retrieved, for example in the case of an EQ only import, the default mode is set to `SIDE_1_RECTIFIER_SIDE_2_INVERTER`.

Similarly, the PowSyBl `ActivePowerSetpoint` is determined from the 2 neighbouring `ACDCConverter`:
- If the CGMES SSH rectifier's `ACDCConverter` defines a `ACDCConverter.targetPpcc`, then the PowSyBl `ActivePowerSetpoint` is copied from it.
- If the CGMES SSH inverter's `ACDCConverter` defines a `ACDCConverter.targetPpcc`, this value is brought back to the rectifier's side, by adding losses all along the line.
See `ACDCConverter` mapping for the calculation detail.
- In case the information couldn't be retrieved, for example in the case of an EQ only import, the default value is `0.0`.

The PowSyBl `MaxP` is set to 120% of `ActivePowerSetpoint`.

#### ACDCConverter

The mapping of CGMES `ACDCConverter` to PowSyBl `HvdcConverterStation` isn't done in isolation, but always in association with the `DCLineSegment`
it is connected to and the `ACDCConverter` on the other side of the line.

The PowSyBl `LossFactor` is computed from CGMES SSH `ACDCConverter.targetPpcc` values and CGMES SV `poleLossP` values.
It is sufficient for one of the converter to define a `targetPpcc` to be able to calculate the AC and DC active powers all along the line:
- If `ACDCConverter.targetPpcc` is defined by CGMES SSH rectifier's `ACDCConverter`, then:
  - $P_{AC, rectifier} = targetPpcc$
  - $P_{DC, rectifier} = P_{AC, rectifier} - poleLossP_{rectifier}$
  - $P_{DC, inverter} = -1 \times (P_{DC, rectifier} - resistiveLosses)$, where $resistiveLosses = R * idc²$ and $idc = \frac{P_{DC, rectifier}}{NominalV}$
  - $P_{AC, inverter} = P_{DC, inverter} + poleLossP_{inverter}$
- If `ACDCConverter.targetPpcc` is defined by CGMES SSH inverter's `ACDCConverter`, then:
  - $P_{AC, inverter} = targetPpcc$
  - $P_{DC, inverter} = P_{AC, inverter} - poleLossP_{inverter}$
  - $P_{DC, rectifier} = abs(P_{DC, inverter}) + resistiveLosses$, where $resistiveLosses = R * idc²$ and $idc = \frac{NominalV - \sqrt{NominalV^2 - 4 \times R \times abs(P_{DC, inverter})}}{2 \times R}$
  - $P_{AC, rectifier} = P_{DC, rectifier} + poleLossP_{rectifier}$
- Once these active power have been calculated, the PowSyBl `LossFactor` is computed as follows:
  - $LossFactor_{rectifier} = \frac{poleLossP_{rectifier}}{P_{AC, rectifier}}$
  - $LossFactor_{inverter} = \frac{poleLossP_{inverter}}{abs(P_{DC, inverter})}$
  - In case the calculations can't be evaluated, for example in the case of an EQ only import, the default value is `0.0`.

The PowSyBl `LccConverterStation` `PowerFactor` is calculated from the CGMES SSH `ACDCConverter.p` and `ACDCConverter.q` values:
  - $PowerFactor = \frac{p}{\sqrt{p² + q²}}$
  - In case the calculations can't be evaluated, for example in the case of an EQ only import, the default value is `0.8`.


(cgmes-detailed-dc-model-import)=
### Detailed DC model

In order to import CGMES DC objects into the IIDM detailed DC model, it is required to set the optional parameter
`iidm.import.cgmes.use-detailed-dc-model` to `true`.

The following CGMES classes are read and imported: `DCNode`, `DCTopologicalNode`, `DCLineSegment`, `DCSwitch`, `DCBreaker`, `DCDisconnector`,
`DCGround`, `CsConverter`, `VsConverter`.

#### DCNode, DCTopologicalNode

If the CGMES model is a node/breaker one, then CGMES `DCNode` are imported into PowSyBl [`DC Node`](../../grid_model/network_subnetwork.md#dc-node),
and CGMES `DCTopologicalNode` are discarded. If the CGMES model is a bus/branch one, then CGMES `DCTopologicalNode` are imported into PowSyBl `DcNode`.
Attribute mapping is:
- `NominalV` is copied from EQ `ratedUdc` of a CGMES `ACDCConverter` which is located in the same `DCConverterUnit` as the `DCNode`.

#### DCLineSegment

CGMES `DCLineSegment` are imported into PowSyBl [`DC Line`](../../grid_model/network_subnetwork.md#dc-line), with attribute:
- `R` is copied from EQ `resistance`.

#### DCSwitch, DCBreaker, DCDisconnector

All CGMES `DCSwitch` are imported into PowSyBl [`DC Switch`](../../grid_model/network_subnetwork.md#dc-switch), with attributes described as follow:
- `Kind` is defined depending on the CGMES class: it is `BREAKER` for CGMES `DCBreaker`, and it is `DISCONNECTOR` for CGMES `DCSwitch` and `DCDisconnector`.
- `Open` is defined depending on the associated CGMES `DCTerminal`: if both have SSH `connected` set to `true`, then it is `false`, otherwise it is `true`.

#### DCGround

CGMES `DCGround` are imported into PowSyBl [`DC Ground`](../../grid_model/network_subnetwork.md#dc-ground), with attribute:
- `R` is copied from EQ `r`.

#### ACDCConverter (CsConverter, VsConverter)

CGMES `CsConverter` are imported into PowSyBl [`Line Commutated Converter`](../../grid_model/network_subnetwork.md#line-commutated-converter),
and `VsConverter` into [`Voltage Source Converter`](../../grid_model/network_subnetwork.md#voltage-source-converter).
Common [`AC/DC Converter`](../../grid_model/network_subnetwork.md#acdc-converter) attributes are mapped as follows:
- `IdleLoss` is copied from EQ `idleLoss`.
- `SwitchingLoss` is copied from EQ `switchingLoss`.
- `ResistiveLoss` is copied from EQ `resistiveLoss`.
- `PccTerminal` is copied from EQ `PccTerminal`.
- `ControlMode` is `P_PCC` if SSH `pPccControl` is `activePower` (CSC) or `pPcc` (VSC), else it is `V_DC` if `pPccControl` is `dcVoltage` (CSC) or `udc` (VSC).
- `TargetP` is copied from SSH `targetPpcc`.
- `TargetVdc` is copied from SSH `targetUdc`.

Specific `CsConverter` attributes are mapped as follows:
- `ReactiveModel` is always set to `FIXED_POWER_FACTOR`.
- `PowerFactor` is calculated from SSH `p` and `q` as $\left|{\frac{p}{\sqrt{p^2 + q^2}}}\right|$.
If p and q are equal to 0, the power factor is calculated with $Q = 0.5P$, which gives the value 0.89443.

Specific `VsConverter` attributes are mapped as follows:
- `VoltageRegulatorOn` is set to `true` if SSH `qPccControl` is set to `voltagePcc`, else it is `false`.
- `VoltageSetpoint` is copied from SSH `targetUpcc`.
- `ReactivePowerSetpoint` is copied from SSH `targetQpcc`.

## Extensions

The CIM-CGMES format contains more information than what the `iidm` grid model needs for calculation. The additional data that are needed to export a network in CIM-CGMES format are stored in several extensions.

(cgmes-boundary-line-boundary-node-import)=
### CGMES boundary line boundary node

This extension is used to add some CIM-CGMES characteristics to boundary lines.


| Attribute                             | Type    | Unit | Required | Default value | Description                                                         |
|---------------------------------------|---------|------|----------|---------------|---------------------------------------------------------------------|
| hvdc status                           | boolean | -    | no       | false         | Indicates if the boundary line is associated with a DC Xnode or not |
| Line Energy Identification Code (EIC) | String  | -    | no       | -             | The EIC of the boundary line if it exists                           |

This extension is provided by the `com.powsybl:powsybl-cgmes-extensions` module.

(cgmes-line-boundary-node-import)=
### CGMES line boundary node

This extension is used to add some CIM-CGMES characteristics to tie lines.

| Attribute                             | Type    | Unit | Required | Default value | Description                                                         |
|---------------------------------------|---------|------|----------|---------------|---------------------------------------------------------------------|
| hvdc status                           | boolean | -    | no       | false         | Indicates if the boundary line is associated with a DC Xnode or not |
| Line Energy Identification Code (EIC) | String  | -    | no       | -             | The EIC of the boundary line EIC if it exists                       |

This extension is provided by the `com.powsybl:powsybl-cgmes-extensions` module.

(cgmes-tap-changers-import)=
### CGMES Tap Changers

<span style="color: red">TODO</span>

The `TapPosition` of the IIDM `TapChanger` is copied from the CGMES SSH `step` if present. If not, it is copied from CGMES `SVtapStep` or `normalStep` from EQ.
The `SolvedTapPosition` is copied from `SVtapStep` if the SV is imported, and left to `null` otherwise.

(cgmes-metadata-models-import)=
### CGMES metadata models

This extension is attached to a Network and is used to store the metadata information about the imported CGMES dataset.
The extension consists of a collection of `CgmesMetadataModel` objects, one per CGMES instance file or _subset_,
that hold the metadata information present in the `FullModel` tag in the header of the CGMES instance file.

| Attribute            | Type          | Unit | Required | Default value      | Description                                                                                    |
|----------------------|---------------|------|----------|--------------------|------------------------------------------------------------------------------------------------|
| id                   | String        | -    | no       | -                  | Unique identifier of the model                                                                 |
| subset               | CgmesSubset   | -    | yes      | -                  | The imported instance file<br/>(EQUIPMENT, TOPOLOGY, STEADY_STATE_HYPOTHESIS, STATE_VARIABLES) |
| description          | String        | -    | no       | \<subset\> + Model | The description of the model and explanation of the purpose                                    |
| version              | int           | -    | no       | 1                  | The version number of the model                                                                |
| modelingAuthoritySet | String        | -    | yes      | -                  | The organisation role which is the source of the model                                         |
| profiles             | Set\<String\> | -    | no       | -                  | The profiles included in this subset                                                           |
| dependentOn          | Set\<String\> | -    | no       | -                  | References to other models this model depends on                                               |
| supersedes           | Set\<String\> | -    | no       | -                  | References to other models this model supsersedes                                              |

Example of code to read the extension and retrieve the modeling authority set assuming the network has been imported from a CGMES datasource:

```java
CgmesMetadataModels models = network.getExtension(CgmesMetadataModels.class);
CgmesMetadataModel eqModel = models.getModelForSubset(CgmesSubset.EQUIPMENT).orElseThrow();
String modelingAuthoritySet = eqModel.getModelingAuthoritySet();
```

This extension is provided by the `com.powsybl:powsybl-cgmes-extensions` module.

(cgmes-cim-characteristics-import)=
### CIM characteristics

This extension is attached to a network and is used to store characteristics about the imported CGMES dataset.

| Attribute    | Type              | Unit | Required | Default value      | Description                                                                             |
|--------------|-------------------|------|----------|--------------------|-----------------------------------------------------------------------------------------|
| cimVersion   | int               | -    | yes      | -                  | Version number of imported dataset: 16 for CIM16/CGMES 2.4.15, 100 for CIM100/CGMES 3.0 |
| topologyKind | CgmesTopologyKind | -    | yes      | -                  | Topology kind: NODE_BREAKER or BUS_BRANCH                                               |

Please note that the topologyKind attribute reflects how the dataset has been considered:
if the `iidm.import.cgmes.import-node-breaker-as-bus-breaker` import parameter has been set to `true`, topologyKind will be `BUS_BRANCH`, even if the network has `NODE_BREAKER` model details.

Example of code to read the extension and retrieve the topology kind assuming the network has been imported from a CGMES datasource:

```java
CimCharacteristics cimCharacteristics = network.getExtension(CimCharacteristics.class);
CgmesTopologyKind topologyKind = cimCharacteristics.getTopologyKind();
```

This extension is provided by the `com.powsybl:powsybl-cgmes-extensions` module.

(cgmes-base-voltage-mapping-import)=
### Base voltage mapping

This extension is attached to a network and is used to store information about BaseVoltage of the imported CGMES dataset.
The extension consists of a collection of `BaseVoltageSource` objects, indexed by nominal voltage, with the following attributes:

| Attribute | Type   | Unit | Required | Default value      | Description                                                                    |
|-----------|--------|------|----------|--------------------|--------------------------------------------------------------------------------|
| id        | String | -    | yes      | -                  | The rdf:ID of the CGMES BaseVoltage                                            |
| nominalV  | double | kV   | yes      | -                  | The CGMES BaseVoltage's nominal voltage                                        |
| source    | Source | -    | yes      | -                  | The kind of grid model containing the BaseVoltage definition (IGM or BOUNDARY) |

Example of code to read the extension and retrieve the source of the 400kV base voltage assuming the network has been imported from a CGMES datasource:

```java
BaseVoltageMapping bvMapping = network.getExtension(BaseVoltageMapping.class);
BaseVoltageMapping.BaseVoltageSource bvSource = bvMapping.getBaseVoltages().get(400.0);
Source source = bvSource.getSource();
```

This extension is provided by the `com.powsybl:powsybl-cgmes-extensions` module.

(cgmes-model-import)=
### CGMES model

[![Javadoc](https://img.shields.io/badge/-javadoc-blue.svg)](https://javadoc.io/doc/com.powsybl/powsybl-core/latest/com/powsybl/cgmes/conversion/CgmesModelExtension.html)

This extension provides access to the PowSyBl CGMES Network Model implemented with a triplestore.

Note that in order for this extension to be present, the corresponding property
`iidm.import.cgmes.store-cgmes-model-as-network-extension` must be set to true.

Exemple of code to read the extension and retrieve the substations in the CGMES input files:

```java
CgmesModelExtension cgmesModel = network.getExtension(CgmesModelExtension.class);
PropertyBags substationBags = cgmesModel.getCgmesModel().substations();
```

This extension is provided by the `com.powsybl:powsybl-cgmes-conversion` module.

(cgmes-conversion-context-import)=
### CGMES conversion context

[![Javadoc](https://img.shields.io/badge/-javadoc-blue.svg)](https://javadoc.io/doc/com.powsybl/powsybl-core/latest/com/powsybl/cgmes/conversion/CgmesConversionContextExtension.html)

This extension is useful for external validation of the mapping made between CGMES and IIDM.

Note that in order for this extension to be present, the corresponding property
`iidm.import.cgmes.store-cgmes-conversion-context-as-network-extension` must be set to true.

Exemple of code to read the extension and retrieve the naming strategy:

```java
CgmesConversionContextExtension cgmesConversionContext = network.getExtension(CgmesConversionContextExtension.class);
NamingStrategy namingStrategy = cgmesConversionContext.getContext().namingStrategy();
```

This extension is provided by the `com.powsybl:powsybl-cgmes-conversion` module.

(cgmes-import-options)=
## Options

These properties can be defined in the configuration file in the [import-export-parameters-default-value](../../user/configuration/import-export-parameters-default-value.md) module.


**iidm.import.cgmes.boundary-location**<br>
Optional property that defines the directory path where the CGMES importer can find the boundary files (`EQBD` and `TPBD` profiles) if they are not present in the imported zip file. By default, its value is `<ITOOLS_CONFIG_DIR>/CGMES/boundary`.
This property can also be used at CGMES export if the network was not imported from a CGMES to indicate the boundary files that should be used for reference.

**iidm.import.cgmes.convert-boundary**<br>
Optional property that defines if the equipment located inside the boundary is imported as part of the network. Used for debugging purposes. `false` by default.

**iidm.import.cgmes.convert-sv-injections**<br>
Optional property that defines if `SvInjection` objects are converted to IIDM loads. `true` by default.

**iidm.import.cgmes.create-active-power-control-extension**<br>
Optional property that defines if active power control extensions are created for the converted generators. `true` by default. If `true`, the extension will be created for the CGMES `SynchronousMachines` with the attribute `normalPF` defined. For these generators, the `normalPF` value will be saved as the `participationFactor` and the flag `participate` set to `true`.

**iidm.import.cgmes.create-busbar-section-for-every-connectivity-node**<br>
Optional property that defines if the CGMES importer creates an [IIDM Busbar Section](../../grid_model/network_subnetwork.md#busbar-section) for each CGMES connectivity node. Used for debugging purposes. `false` by default.

**iidm.import.cgmes.create-fictitious-switches-for-disconnected-terminals-mode**<br>
Optional property that defines if fictitious switches are created when terminals are disconnected in CGMES node-breaker networks.
Three modes are available:
- `ALWAYS`: fictitious switches are created at every disconnected terminal.
- `ALWAYS_EXCEPT_SWITCHES`: fictitious switches are created at every disconnected terminal that is not a terminal of a switch.
- `NEVER`: no fictitious switches are created at disconnected terminals.

The default value is `ALWAYS`.

**iidm.import.cgmes.decode-escaped-identifiers**<br>
Optional property that defines if identifiers containing escaped characters are decoded when CGMES files are read. `true` by default.

**iidm.import.cgmes.ensure-id-alias-unicity**<br>
Optional property that defines if IDs' and aliases' unicity is ensured during CGMES import. If it is set to `true`, identical CGMES IDs will be modified to be unique. If it is set to `false`, identical CGMES IDs will throw an exception. `false` by default.

**iidm.import.cgmes.import-control-areas**<br>
Optional property that defines if control areas must be imported or not. `true` by default.

**iidm.import.cgmes.naming-strategy**<br>
Optional property that defines which naming strategy is used to transform CGMES identifiers to IIDM identifiers. Currently, all naming strategies assign CGMES Ids directly to IIDM Ids during import, without any transformation. The default value is `identity`.
You can also define a custom naming strategy by implementing the `NamingStrategy` interface on your own project and declare
a `NamingStrategyProvider` that can be automatically discovered. Then in this parameter, you can specify the name of the provider.

**iidm.import.cgmes.post-processors**<br>
Optional property that defines all the CGMES post-processors which will be activated after import.
By default, it is an empty list.
One implementation of such a post-processor is available in PowSyBl in the [powsybl-diagram](https://github.com/powsybl/powsybl-diagram) repository, named [CgmesDLImportPostProcessor](./post_processor.md#cgmesdlimportpostprocessor).

**iidm.import.cgmes.powsybl-triplestore**<br>
Optional property that defines which Triplestore implementation is used. `rdf4j` by default, the in-memory
[RDF4J](https://rdf4j.org/) store. `rdf4j-sparql` writes the statements into a configured remote SPARQL database
instead, see [RDF database](rdf_database.md).

**iidm.import.cgmes.source-for-iidm-id**<br>
Optional property that defines if IIDM IDs must be obtained from the CGMES `mRID` (master resource identifier) or the CGMES `rdfID` (Resource Description Framework identifier). The default value is `mRID`.

**iidm.import.cgmes.store-cgmes-model-as-network-extension**<br>
Optional property that defines if the whole CGMES model is stored in the imported IIDM network as an [extension](import.md#cgmes-model) of the IIDM output network.
The default value is `false`.

The CGMES model triplestore is not closed after CGMES import when this option is enabled.
To reclaim memory, manually close the triplestore via the extension.

**iidm.import.cgmes.store-cgmes-conversion-context-as-network-extension**<br>
Optional property that defines if the CGMES conversion context is stored as an [extension](import.md#cgmes-conversion-context) of the IIDM output network.
It is useful for external validation of the mapping made between CGMES and IIDM.
Its default value is `false`.

The CGMES model triplestore is not closed after CGMES import when this option is enabled.
To reclaim memory, manually close the triplestore via the extension.

**iidm.import.cgmes.use-detailed-dc-model**<br>
Optional property that defines which IIDM DC model should be populated at import. Set to `true` to import DC objects into the detailed DC model, `false` to import into the reduced DC model. The default value is `false`.

**iidm.import.cgmes.import-node-breaker-as-bus-breaker**<br>
Optional property that forces CGMES model to be in topology bus/breaker in IIDM. This is a key feature when some models do not have all the breakers to connect and disconnect equipments in IIDM. In bus/breaker topology, connect and disconnect equipment only rely on terminal statuses and not on breakers. Its default value is `false`.

**iidm.import.cgmes.disconnect-boundary-line-if-boundary-side-is-disconnected**  
Optional property used at CGMES import that disconnects the IIDM boundary line if in the CGMES model the line is open at the boundary side. As IIDM does not have any equivalence for that, this is an approximation. Its default value is `false`.

**iidm.import.cgmes.missing-permanent-limit-percentage**<br>
Optional property used when in operational limits, temporary limits are present and the permanent limit is missing as it is forbidden in IIDM. The missing permanent limit is equal to a percentage of the lowest temporary limit, with the percentage defined by the value of this property if present, `100` by default.

**iidm.import.cgmes.cgm-with-subnetworks**<br>
Optional property to define if subnetworks must be added to the network when importing a Common Grid Model (CGM). Each subnetwork will model an Individual Grid Model (IGM). By default `true`: subnetworks are added, and the merging is done at IIDM level, with a main IIDM network representing the CGM and containing a set of subnetworks, one for each IGM. If the value is set to `false` all the CGMES data will be flattened in a single network and information about the ownership of each equipment will be lost.

**iidm.import.cgmes.cgm-with-subnetworks-defined-by**<br>
If `iidm.import.cgmes.cgm-with-subnetworks` is set to `true`, use this property to specify how the set of input files should be split by IGM: based on their filenames (use the value `FILENAME`) or by its modeling authority, read from the header (use the value `MODELING_AUTHORITY`).
Its default value is `MODELING_AUTHORITY`.

**iidm.import.cgmes.create-fictitious-voltage-level-for-every-node**<br>
Optional property that defines the fictitious voltage levels created by line container. If it is set to `true`, a fictitious voltage level is created for each connectivity node inside the line container.
If it is set to `false`, only one fictitious voltage level is created for each line container.
`true` by default.

**iidm.import.cgmes.diff.check-reverse**<br>
Optional property that defines whether the reverse differences and the preconditions of a [difference model](#cgmes-import-difference-model) are checked against the network before the difference is applied.
`off` by default: applying a difference means replacing the value of a property, not merging a delta into an unknown state.
With `warn` every mismatch is reported and the difference is applied anyway; with `fail` the difference is refused and the network is left untouched.

**iidm.import.cgmes.diff.check-supersedes**<br>
Optional property that defines whether the `md:Model.Supersedes` of a [difference model](#cgmes-import-difference-model) has to name the model the network currently holds for that profile.
`true` by default, because applying a delta on the wrong base corrupts the network silently.
The check is skipped when the difference declares no `Supersedes` or the network holds no model of that profile.

**iidm.import.cgmes.use-previous-values-during-update**<br>
Optional property that defines whether the CGMES importer should use previous values to fill in missing SSH attributes during an update.
When EQ and one or more SSH files are imported separately, and this property is set to `true`, the importer will use values from previously imported SSH files to complete missing attributes in the SSH file currently being imported.
If set to `false`, missing SSH attributes will be filled using default values. 
This property does not apply to SV data. SV data is handled as a complete dataset, with no support for partial updates of the SV file.
`false` by default.

**iidm.import.cgmes.remove-properties-and-aliases-after-import**<br>
Properties and aliases are generated during the EQ import process and are used both in the initial import and in subsequent network updates.
When this option is set to `true`, all generated properties and aliases are removed after the import/update process.
If the option is set to `true` during the initial import, then both the EQ and SSH files must be provided to obtain a valid network at the steady-state hypothesis level.
Cgmes importer will import the EQ file, create the properties and aliases, perform the update by importing the SSH file, and finally remove the properties and aliases.
If only the EQ file is provided, the properties and aliases will be deleted immediately after the import, not allowing any future update.<br>
In this case, the imported network will only be valid at the Equipment level.
If the option is set to `true` during an update, the update will be performed and then the properties and aliases will be removed.
Removing properties and aliases invalidates all subsequent updates but reduces the size of the IIDM network during serialization,
thereby improving performance. This option is suitable when the user does not need to preserve CGMES data for persistency purposes
or does not intend to perform further network updates.
`false` by default.

**iidm.import.cgmes.silence-frequent-issues-warnings**  
Optional property that defines whether a warning log should be issued for the following issues that happen frequently on real cases:
- cim:OperationalLimit-s which could not be imported into IIDM:
  - cim:OperationalLimit-s of type CurrentLimit, ActivePowerLimit and ApparentPowerLimit can be imported in
    IIDM only if they relate to Branches (Lines, Tie-Lines, Two Windings Transformers), Three Windings Transformers,
    and Boundary Lines (at network side, limits at boundary side can not be imported).
  - For all other equipment types, no convertion is done. This is the case for example for Switches, Generators, Loads, etc...
- cim:Switch-es not imported because the import is Bus/Breaker and the switch from Bus and to Bus are the same Bus 
- missing minQ/maxQ for cim:EquivalentInjection-s and cim:SynchronousMachine-s 

If the option is set to `false`, a warning is logged for every occurrence of the above issues, which may lead to excessive logging in real cases.  
If the option is set to `true`, no warning is logged for any of the above issues.  
 
`false` by default.
