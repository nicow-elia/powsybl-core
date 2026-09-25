# Export

There are two main use-cases supported:
 * Export IGM (Individual Grid Model) instance files. There is a single network and a unique CGMES modeling authority.
 * Export CGM (Common Grid Model) instance files. A network composed of multiple subnetworks, where each subnetwork is an IGM.

In both cases, the metadata model information in the exported files is built from metadata information read from the input files and stored in IIDM or received through parameters.
Information received through parameters takes precedence over information available from original metadata models.

For a quick CGM export, the user may rely on the parameter **iidm.export.cgmes.cgm_export** to write in a single export multiple updated SSH files (one for each IGM) and a single SV for the whole common grid model. Specifics about this option are explained in the section [below](#cgm-common-grid-model-quick-export).
If you need complete control over the exported files in a CGM scenario, you may prefer to iterate through the subnetworks and make multiple calls to the export function. This is described in detail in the section [below](#cgm-common-grid-model-manual-export).

Please note that when exporting equipment, PowSyBl always uses the CGMES node/breaker level of detail, without considering the topology
level of the PowSyBl network.

The user can specify the profiles to be exported using the parameter **iidm.export.cgmes.profiles**. The list of currently supported export instance files are: EQ, SSH, SV, TP.

If the IIDM network has at least one voltage level with node/breaker topology level, and the SSH or SV is requested in the export, and the TP is not requested, an error will be logged, as there could be missing references in the SSH, SV files to Topological Nodes calculated automatically by IIDM that are not present in the output.

If the dependencies have to be updated automatically (see parameter **iidm.export.cgmes.update-dependencies** below), the exported instance files will contain metadata models where:
* TP and SSH depend on EQ.
* SV depends on TP and SSH.
* EQ depends on EQ_BD (if present). EQ_BD is the profile for the boundary equipment definitions.
* SV depends on TP_BD (if present). TP_BD is the profile for the boundary topology. Only for CGMES 2.4.

The output filenames will follow the pattern `<baseName>_<profile>.xml`. The basename is determined from the parameters, or the basename of the export data source or the main network name.

(cgmes-cgm-quick-export)=
## CGM (Common Grid Model) quick export

When exporting a CGM, we need an IIDM network (CGM) that contains multiple subnetworks (one for each IGM).
Only the CGMES instance files corresponding to SSH and SV profiles are exported:
an updated SSH file for every subnetwork (for every IGM) and a single SV file for the main network that represents the CGM.

When exporting, it is verified that the main network and all subnetworks have the same scenario time (network case date). If they are different, an error is logged.

If a version number is given as a parameter, it is used for the exported files. If not, the versions of the input CGM SV and IGM SSHs are obtained from their metadata, and their maximum value calculated. The output version is then set to 1 + this maximum value.

The quick CGM export will always write updated SSH files for IGMs and a single SV for the CGM. The parameter for selecting which profiles to export is ignored in this kind of export.

If the dependencies have to be updated automatically (see parameter **iidm.export.cgmes.update-dependencies** below), the exported instance files will contain metadata models where:
* Updated SSH for IGMs supersede the original ones, and depend on the original EQ from IGMs.
* Updated SV for the CGM depends on the updated SSH from IGMs and on the original TP and TP_BD from IGMs.

The filenames of the exported instance files will follow the pattern:
* For the CGM SV: `<basename>_SV.xml`.
* For the IGM SSHs: `<basename>_<IGM name>_SSH.xml`. The IGM name is built from the country code of the first substation or the IIDM name if no country is present.

The basename is determined from the parameters, or the basename of the export data source or the main network name.

As an example, you can export one of the test configurations that have been provided by ENTSO-E. It is available in the cgmes-conformity module of the powsybl-core repository. If you run the following code:

```java
Network cgmNetwork = Network.read(CgmesConformity1Catalog.microGridBaseCaseAssembled().dataSource());

Properties exportParams = new Properties();
exportParams.put(CgmesExport.EXPORT_BOUNDARY_POWER_FLOWS, true);
exportParams.put(CgmesExport.NAMING_STRATEGY, "cgmes");
exportParams.put(CgmesExport.CGM_EXPORT, true);
exportParams.put(CgmesExport.UPDATE_DEPENDENCIES, true);
exportParams.put(CgmesExport.MODELING_AUTHORITY_SET, "MAS1");

cgmNetwork.write("CGMES", exportParams, new FileDataSource(Path.of("/exampleFolder"), "exampleBase"));
```

You will obtain the following files in your `exampleFolder`:

```
exampleBase_BE_SSH.xml
exampleBase_NL_SSH.xml
exampleBase_SV.xml
```

where the updated SSH files will supersede the original ones and depend on the original EQs, and the SV will contain the correct dependencies of new SSH and original TPs and TP_BD.

(cgmes-cgm-manual-export)=
## CGM (Common Grid Model) manual export

If you want to intervene in how the updated IGM SSH files or the CGM SV are exported, you can make multiple calls to the CGMES export function.

You can use the following code for reference:

```java
Network cgmNetwork = Network.read(CgmesConformity1Catalog.microGridBaseCaseAssembled().dataSource());

// We decide which version we want to export
int exportedVersion = 18;

// Common export parameters
Properties exportParams = new Properties();
exportParams.put(CgmesExport.EXPORT_BOUNDARY_POWER_FLOWS, true);
exportParams.put(CgmesExport.NAMING_STRATEGY, "cgmes");
// We do not want a quick CGM export
exportParams.put(CgmesExport.CGM_EXPORT, false);
exportParams.put(CgmesExport.UPDATE_DEPENDENCIES, false);

Path outputPath = Path.of("/manualExampleFolder");
String basename = "manualExampleBasename";

// For each subnetwork, prepare the metadata for SSH and export it
for (Network n : cgmNetwork.getSubnetworks()) {
    String country = n.getSubstations().iterator().next().getCountry().orElseThrow().toString();
    CgmesMetadataModel sshModel = n.getExtension(CgmesMetadataModels.class).getModelForSubset(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();
        sshModel.clearDependencies()
                .addDependentOn("myDependency")
                .addSupersedes("mySupersede")
                .setVersion(exportedVersion)
                .setModelingAuthoritySet("myModellingAuthority");
        exportParams.put(CgmesExport.PROFILES, List.of("SSH"));
    n.write("CGMES", exportParams, new FileDataSource(outputPath, basename + "_" + country));
}

// In the main network, CREATE the metadata for SV and export it
cgmNetwork.newExtension(CgmesMetadataModelsAdder.class)
    .newModel()
        .setSubset(CgmesSubset.STATE_VARIABLES)
        .addProfile("http://entsoe.eu/CIM/StateVariables/4/1")
        .setId("mySvId")
        .setVersion(exportedVersion)
        .setModelingAuthoritySet("myModellinAuthority")
        .addDependentOn("mySvDependency1")
        .addDependentOn("mySvDependency2")
    .add()
    .add();
exportParams.put(CgmesExport.PROFILES, List.of("SV"));
cgmNetwork.write("CGMES", exportParams, new FileDataSource(outputPath, basename));
```

The file `manualExampleBasename_BE_SSH.xml` inside `/manualExampleFolder` will have the following contents for the metadata:

```xml
...
<md:Model.description>CGMES Conformity Assessment ...</md:Model.description>
<md:Model.version>18</md:Model.version>
<md:Model.DependentOn rdf:resource="myDependency"/>
<md:Model.Supersedes rdf:resource="mySupersede"/>
<md:Model.profile>http://entsoe.eu/CIM/SteadyStateHypothesis/1/1</md:Model.profile>
<md:Model.modelingAuthoritySet>myModellingAuthority</md:Model.modelingAuthoritySet>
...
```

And the file `manualExampleBasename_SV.xml` will contain:

```xml
...
<md:Model.version>18</md:Model.version>
<md:Model.DependentOn rdf:resource="mySvDependency1"/>
<md:Model.DependentOn rdf:resource="mySvDependency2"/>
<md:Model.profile>http://entsoe.eu/CIM/StateVariables/4/1</md:Model.profile>
<md:Model.modelingAuthoritySet>myModelingAuthority</md:Model.modelingAuthoritySet>
...
```

Remember that, in addition to setting the info for metadata models in the IIDM extensions, you could also rely on parameters passed to the export methods.

(cgmes-partial-ssh-export)=
## Partial SSH export from recorded changes

The exports described above always write the complete state of the network. When two processes already share the same base model and only need to exchange small changes to that base model, writing and reading a full grid model is unnecessary. `PartialSshExport` writes a *partial* SSH instance file, containing only the objects
affected by a list of changes recorded on the network:

```java
NetworkEventRecorder recorder = new NetworkEventRecorder();
network.addListener(recorder);

network.getGenerator("GEN").setTargetP(120.0);
network.getSwitch("BREAKER").setOpen(true);

// to a file
PartialSshExport.write(network, recorder.getEvents(), Path.of("update_SSH.xml"),
        UnsupportedChangeBehavior.FAIL);
// or to a string, for an in-memory exchange
String ssh = PartialSshExport.toString(network, recorder.getEvents(),
        UnsupportedChangeBehavior.FAIL);
```

The result is a regular SSH instance file that the receiving side applies through the usual update workflow:

```java
Properties parameters = new Properties();
parameters.put("iidm.import.cgmes.use-previous-values-during-update", "true");
receiver.update(new GenericReadOnlyDataSource(directory, "update"), parameters);
```

Setting `iidm.import.cgmes.use-previous-values-during-update` is required: it tells the import to keep its current value for everything the partial file does not mention.

A partial file says what the network looks like now. When the previous state has to travel as well, so that the change can be undone, replayed or chained, use the [difference model export](#cgmes-difference-model-export) instead: it shares this mapping and writes both directions.

### Supported changes

| Equipment | IIDM attribute or extension | CGMES object.property |
| --- | --- | --- |
| `Switch` | `open` | `Switch.open`, or the `ACDCTerminal.connected` of both terminals when the CGMES equipment is an `ACLineSegment`, `EquivalentBranch` or `SeriesCompensator` |
| `DcSwitch` | `open` | `ACDCTerminal.connected` of both DC terminals |
| `Load` | `p0`, `q0` | `EnergyConsumer.p/q`, `EnergySource.activePower/reactivePower`, or `RotatingMachine.p/q` + `RegulatingCondEq.controlEnabled` + `AsynchronousMachine.asynchronousMachineType` |
| `BoundaryLine` | `p0`, `q0`, and of its `Generation` `targetP`, `targetQ`, `targetV`, `voltageRegulationOn` | `EquivalentInjection.p/q/regulationStatus/regulationTarget` of the boundary |
| `Generator` (`SynchronousMachine`) | `targetP`, `targetQ`, `targetV`, `voltageRegulatorOn` | `RotatingMachine.p/q` + `RegulatingCondEq.controlEnabled` + `SynchronousMachine.referencePriority/operatingMode`, and `RegulatingControl` |
| `Generator` (`ExternalNetworkInjection`) | `targetP`, `targetQ`, and `targetV`, `voltageRegulatorOn` when the injection has a `RegulatingControl` | `ExternalNetworkInjection.p/q/referencePriority` + `RegulatingCondEq.controlEnabled`, and `RegulatingControl` |
| `Generator` (`EquivalentInjection`) | `targetP`, `targetQ`, `targetV`, `voltageRegulatorOn` | `EquivalentInjection.p/q/regulationStatus/regulationTarget` |
| `Generator`, extension `referencePriorities` | `referencePriority` | `SynchronousMachine.referencePriority` or `ExternalNetworkInjection.referencePriority` |
| `Generator`, extension `activePowerControl` | `participationFactor` | `GeneratingUnit.normalPF` |
| `Generator`, extension `generatorRemoteReactivePowerControl` | `targetQ`, `enabled` | `RegulatingControl.targetValue/enabled` and the machine block |
| `TwoWindingsTransformer`, `ThreeWindingsTransformer` | `ratioTapChanger[end].tapPosition`, `phaseTapChanger[end].tapPosition` | `TapChanger.step` + `TapChanger.controlEnabled` |
| `TwoWindingsTransformer`, `ThreeWindingsTransformer` | `ratioTapChanger[end].regulating` / `.regulationValue` / `.targetDeadband`, and the same of `phaseTapChanger[end]` | the tap changer block and `TapChangerControl` |
| `ShuntCompensator` | `sectionCount`, `targetV`, `voltageRegulatorOn`, `targetDeadband` | `ShuntCompensator.sections` + `RegulatingCondEq.controlEnabled`, and `RegulatingControl` for the three regulation attributes |
| `StaticVarCompensator` | `voltageSetpoint`, `reactivePowerSetpoint`, `regulating` | `StaticVarCompensator.q` + `RegulatingCondEq.controlEnabled`, and `RegulatingControl` |
| `HvdcLine` | `activePowerSetpoint`, `convertersMode` | `ACDCConverter.targetPpcc/targetUdc/p/q` of both converters, plus `CsConverter.operatingMode/pPccControl` or `VsConverter.pPccControl/qPccControl` |
| `LccConverterStation` | `powerFactor` | the two converter blocks of its line, where the factor is carried by `ACDCConverter.p` and `q` |
| `VscConverterStation` | `voltageSetpoint`, `reactivePowerSetpoint`, `voltageRegulatorOn` | `VsConverter.pPccControl/qPccControl/targetUpcc/targetQpcc` |
| `LineCommutatedConverter`, `VoltageSourceConverter` (detailed DC model) | `targetP`, `targetVdc`, `controlMode`, `voltageRegulatorOn`, `voltageSetpoint`, `reactivePowerSetpoint`, `powerFactor` | `ACDCConverter.targetPpcc/targetUdc/p/q` plus `CsConverter.operatingMode/pPccControl` or `VsConverter.pPccControl/qPccControl/targetUpcc/targetQpcc` |
| `Branch`, `ThreeWindingsTransformer` leg, `BoundaryLine`, **CGMES 3 only** | permanent and temporary limit values of its `OperationalLimitsGroup`s | `CurrentLimit.value`, `ActivePowerLimit.value`, `ApparentPowerLimit.value` of the stored mRID |
| `VoltageLevel`, **CGMES 3 only** | `highVoltageLimit`, `lowVoltageLimit`, when the voltage level was built from `VoltageLimit` objects | `VoltageLimit.value` of every stored identifier |

CGMES 3 operational limit values are steady state data and *are* exported, as `CurrentLimit.value`, `ActivePowerLimit.value`, `ApparentPowerLimit.value` and `VoltageLimit.value`; CGMES 2.4.15 limit values and all branch impedances belong to the equipment profile and need a [difference model](#cgmes-difference-model-export). Anything else, in particular the creation or the removal of equipment and changes of terminal connection status, has no representation in the SSH profile. Every export method takes an `UnsupportedChangeBehavior` saying what to do with such a change: `FAIL` rejects it, so that it is never silently lost, and `IGNORE` logs a warning and leaves it out of the file. Using the `write` endpoints return value, you can obtain a log of changes that made it to the file for tracking the effect of `IGNORE`.

### Header options

`PartialSshExport.ExportOptions` sets every value of the model header. Each has a default derived from the model the network was imported from: the identifier is generated, the version is the one of the source SSH incremented by one, and the new model supersedes the source SSH and depends on whatever the source SSH depended on, that is on the equipment model that both sides share.

| Option | Effect |
| --- | --- |
| `setUnsupportedChangeBehavior` | `FAIL` (the default) or `IGNORE` for a change the SSH profile cannot express |
| `setModelId` | the identifier of the exported model, instead of a generated one |
| `setDescription` | `Model.description` |
| `setVersion` | `Model.version`, instead of the source version incremented by one |
| `setModelingAuthoritySet` | `Model.modelingAuthoritySet` |
| `setScenarioTime` | `Model.scenarioTime`, instead of the case date of the network |
| `setCreated` | `Model.created`, instead of the time at which the file is written. Setting it makes an export reproducible |
| `clearDependencies`, `addDependentOn` | `Model.DependentOn` |
| `setSupersedePreviousSshModel`, `addSupersedes` | `Model.Supersedes` |

### Regulating controls

A CGMES `RegulatingControl` is shared: a generator, a shunt compensator and a tap changer can all point at the same one, and the profile then carries a single enabled flag, a single target and a single deadband for all of them. A partial export therefore never writes the view of the one piece of equipment that changed: it writes the combination of the views of every user of that control, as a full export does, so that a receiver reading a partial file and a receiver reading a full one end up in the same state. Which piece of equipment actually regulates is still distinguished by its own `RegulatingCondEq.controlEnabled`.

A partial export differs from a full one in exactly two places, both because a partial file is applied on top of a state the receiver already holds:

* A phase tap changer in current limiter mode is described with the values it really has, where a full export writes zeros. Writing zeros would reset a regulation that never changed.
* The `RegulatingCondEq.controlEnabled` of a generator whose CGMES control regulates reactive power reports the state of its `generatorRemoteReactivePowerControl`, where a full export always reports `isVoltageRegulatorOn()`. The import combines that flag with `RegulatingControl.enabled`, so reporting the voltage flag, which is `false` in reactive power mode, would make such a control impossible to switch on again.

Two consequences are worth knowing:

* The target of a control regulating reactive power is written with the sign of the regulating terminal that the import recorded, because the import reads it back with that same sign. The same correction is now applied by the full export.
* Tap changers are the exception: the CGMES update derives the state of a tap changer from `RegulatingControl.enabled` alone and ignores `TapChanger.controlEnabled`. Two tap changers sharing a `TapChangerControl` but disagreeing on whether they regulate therefore cannot be described, and such a change is reported as unsupported.

A phase tap changer in current limiter mode is described with the values it really has, where a full export writes zeros. A partial file is applied on top of a state the receiver already holds, so writing zeros would reset a regulation that never changed.

Equipment that has no `RegulatingControl` in the model both sides share cannot carry a regulation change: a generated identifier would resolve to nothing on the receiving side, so the change is reported as unsupported.

### Limitations

Some changes are accepted but are not observable in the file, because CGMES holds a single value where IIDM holds two:

* A `StaticVarCompensator` carries one target on its `RegulatingControl`, the one matching the mode it is in. A change of the setpoint of the other mode is exported as the currently active value.
* A `VscConverterStation` carries `targetUpcc` and `targetQpcc`, but the import applies only the one matching `qPccControl` and resets the other to zero. The setpoint of the inactive mode is therefore not transportable. Functional equality is preserved because a later change of the regulation mode re-exports the then active value.

Other changes are reported as unsupported, because the SSH profile cannot express them:

* The regulation mode of a `StaticVarCompensator` or of a tap changer: it is `RegulatingControl.mode`, which belongs to the EQ profile.
* A change of the `generatorRemoteReactivePowerControl` of a generator whose CGMES control regulates voltage: the single target of that control means a voltage, so there is nowhere to put a reactive power target.
* A regulation change of a ratio tap changer that does not regulate voltage: the CGMES update only reads voltage regulation of ratio tap changers.
* The power factor of a line commutated converter whose line carries no power: the factor is carried by `ACDCConverter.p` and `q`, which are then zero.
* The converters mode of an HVDC line of voltage source converters whose setpoint is zero: a `VsConverter` has no operating mode, the mode is only derived from a non zero `targetPpcc`.
* Switching the regulation of a generator imported from an `EquivalentInjection` on when the equipment model gives it no regulation capability: the CGMES update keeps its regulation off whatever the file says.
* A change recorded on a variant other than the working one: the values written are read from the network as it currently stands, so such a change would be exported with the values of the working variant, describing a state that never existed.
* The participation factor of a generator that belongs to no CGMES `GeneratingUnit`, and the reference priority of a generator exported as an `EquivalentInjection`.

Finally, a few round trips are exact in value but not in every detail of the model:

* The `maxP` of an `HvdcLine` becomes `1.2 * activePowerSetpoint` on the receiving side, which is how the import derives it.
* The sign of the power factor of a line commutated converter is derived by the import from the sign of the active power, negative on the inverter. The round trip is exact in magnitude, and exact in sign when the sender follows that convention, which any imported network does.
* A boundary line with a `Generation` always comes back with `p0` and `q0` at zero and the whole injection in the generation, which is how the import splits the single CGMES `EquivalentInjection`.
* Two boundary lines sharing one `EquivalentInjection`, or two generators sharing one `GeneratingUnit`, are written last value wins, exactly as a full export writes them.

### Importer requirements

The receiving side reads a partial file through the usual update workflow, with these import parameters:

| Parameter | Why |
| --- | --- |
| `iidm.import.cgmes.use-previous-values-during-update` | required: it tells the import to keep its current value for everything the partial file does not mention |
| `iidm.import.cgmes.create-active-power-control-extension` | needed on both sides to exchange participation factors |
| `iidm.import.cgmes.use-detailed-dc-model` | needed on both sides to exchange the setpoints of the converters of the detailed DC model |

### Fixed import and export defects

Four defects of the CGMES import and of the full SSH export were fixed along the way, because a partial file could not survive them:

* [#4027](https://github.com/powsybl/powsybl-core/issues/4027): the full SSH export wrote `VsConverter.targetQpcc` without the sign of the regulating terminal, which the import applies when reading it back.
* [#4028](https://github.com/powsybl/powsybl-core/issues/4028): the CGMES update read an `ACDCConverter.targetPpcc` of zero on the rectifier side as no value at all, so a line brought down to no power kept the power it had.
* [#4029](https://github.com/powsybl/powsybl-core/issues/4029): the steady state hypothesis of an `AsynchronousMachine` was written without its kind and its control flag, and the update query demanded both, so the setpoints of such a load could never be read back.
* [#4034](https://github.com/powsybl/powsybl-core/issues/4034): the update query did not select `PhaseTapChangerSymmetrical`, so a position written for one was silently dropped on import.

(cgmes-difference-model-export)=
## Difference model export from recorded changes

A partial SSH file says what the network looks like now. An [IEC 61970-552 difference model](https://webstore.iec.ch/publication/2496) also says what it looked like before, which is what a change history needs: a difference can be undone, replayed and chained, and it can be stored as a pair of statement sets rather than as a document that only makes sense on top of exactly the right base model. `CgmesDiffExport` writes one from the same recorded changes as `PartialSshExport`:

```java
NetworkEventRecorder recorder = new NetworkEventRecorder();
network.addListener(recorder);

network.getLoad("LOAD").setP0(12.5);

// the statements, without any I/O
CgmesDiffExport.Result result = CgmesDiffExport.toDifferences(network, recorder.getEvents(),
        new CgmesDiffExport.ExportOptions());
DifferenceModel ssh = result.differences().get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow();

// one document per touched profile, into a directory or a zip
CgmesDiffExport.write(network, recorder.getEvents(), dataSource, "case",
        new CgmesDiffExport.ExportOptions());

// or through a sink of your own, for instance a triple store
CgmesDiffExport.export(network, recorder.getEvents(), myDifferenceSink,
        new CgmesDiffExport.ExportOptions());
```

### Pipeline and the statement step

The export is three steps, and a caller can stop after any of them:

1. the recorded `NetworkEvent`s are compacted to one change per attribute, keeping what the *first* change of each attribute replaced;
2. `toDifferences` translates them into `CgmesStatement`s, in both directions, without writing anything;
3. a `DifferenceSink` stores the result, as a document per profile through `DifferenceModelWriter`, in a triple store, or in an RDF database through `RdfDbDifferenceSink` &mdash; see [storing difference models](rdf_database.md#storing-difference-models), which also versions them and advances the sender. A database export may address the version and the timestep it writes, see [versioning](rdf_database.md#versioning-snapshots-versions-and-timesteps) and [timesteps](rdf_database.md#timesteps-the-outer-dimension).

`CgmesStatement` is one RDF triple about an object that already exists in the model the difference applies to: a subject identifier, a CIM property, a value and how that value is written (a literal, a CIM enumeration literal or a reference). Statements of one profile are bundled in a `DifferenceModel`, and the models of one change set in a `DifferenceModelSet`. Two statements are equal when they say the same thing about the same object, whatever CIM class the producer believed the subject to have, so a generated statement and one parsed back from a document compare equal.

### Document layout

A difference model document holds one `dm:DifferenceModel` whose header is written exactly like the `md:FullModel` of a complete instance file, followed by the two statement containers:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<rdf:RDF xmlns:eu="http://iec.ch/TC57/CIM100-European#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
         xmlns:dm="http://iec.ch/TC57/61970-552/DifferenceModel/1#">
    <dm:DifferenceModel rdf:about="urn:uuid:5b6f0c1e-3c1a-4f0e-9d53-0e2d6c3b7a11">
        <md:Model.scenarioTime>2024-02-21T11:00:00Z</md:Model.scenarioTime>
        <md:Model.created>2026-09-17T08:00:00Z</md:Model.created>
        <md:Model.description>Load redispatch</md:Model.description>
        <md:Model.version>2</md:Model.version>
        <md:Model.DependentOn rdf:resource="urn:uuid:the-equipment-model"/>
        <md:Model.Supersedes rdf:resource="urn:uuid:the-model-this-applies-on"/>
        <md:Model.profile>http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/3.0</md:Model.profile>
        <md:Model.modelingAuthoritySet>https://www.powsybl.org/</md:Model.modelingAuthoritySet>
        <dm:reverseDifferences rdf:parseType="Statements">
            <rdf:Description rdf:about="#_EnergyConsumer">
                <cim:EnergyConsumer.p>10</cim:EnergyConsumer.p>
                <cim:EnergyConsumer.q>5</cim:EnergyConsumer.q>
            </rdf:Description>
        </dm:reverseDifferences>
        <dm:forwardDifferences rdf:parseType="Statements">
            <rdf:Description rdf:about="#_EnergyConsumer">
                <cim:EnergyConsumer.p>12.5</cim:EnergyConsumer.p>
                <cim:EnergyConsumer.q>5</cim:EnergyConsumer.q>
            </rdf:Description>
        </dm:forwardDifferences>
    </dm:DifferenceModel>
</rdf:RDF>
```

* All the models of one change set carry the same `md:Model.created`, so a receiver can tell the profiles of one change apart from those of another.
* `dm:forwardDifferences` is the new state, `dm:reverseDifferences` the previous one. Both are always present, empty when the model says nothing in that direction, and both carry `rdf:parseType="Statements"`, which tells an RDF parser that their content is a list of statements about objects described elsewhere.
* Every subject is described by exactly one `rdf:Description rdf:about="#_<mRID>"`, placed where the subject is first mentioned, and carries **no** `rdf:type`: a difference states properties of objects the receiver already holds, it never introduces one.
* An enumeration is an empty element with `rdf:resource="<cimNamespace><Enumeration>.<literal>"`, a reference an empty element with a local `rdf:resource="#_<mRID>"`, and a literal is the text of the element with no RDF datatype.
* The namespace declarations are the European extension of the CIM version (`eu` for CIM100, `entsoe` for CIM16), `rdf`, `cim`, `md` and `dm`. `dm:preconditions` is a third container the format allows; this release never writes it.
* Reverse is written before forward. That is a readability choice of this writer: IEC 61970-552 leaves the order of the containers free, and a reader must not rely on it.

### Granularity

By default every object a change touches is described in full, in both directions (`DiffGranularity.FULL_OBJECT`). The reason is the same one that makes the partial SSH export write whole blocks: the CGMES update reads some properties only as a group — the active and the reactive power of an injection, the section count and the control flag of a shunt compensator, the four quantities of a converter. A consumer that applies a difference by replacing the properties it is given then keeps those groups consistent, and the forward statements of a difference are exactly the partial SSH description of the same change.

`DiffGranularity.CHANGED_ONLY` drops every statement that says the same thing in both directions, which gives a true delta at the price of a consumer that has to merge property by property. `DifferenceModel.minimized()` does the same to a model that was already produced, so the two are interchangeable.

Whole objects that did not change at all are dropped in both modes: if the forward and the reverse description of a subject are identical, the subject is left out of the difference entirely. A change and its undo therefore produce an empty difference, and a shared `RegulatingControl` that keeps its combined state while one of its users stops regulating does not appear.

### How reverse values are obtained

The previous state is an overlay on top of the live network: an attribute the change set touched reads the value that the **first** recorded change of it replaced, and every other attribute reads the network as it currently stands, because a value nothing changed is the same before and after. The same mapping code then runs twice, so derived quantities — the operating mode of a machine, the sign of a setpoint, the combined state of a shared regulating control, the DC voltage of an inverter — are computed consistently in both directions instead of being patched together from event payloads.

Two things follow:

* **The recorder has to have been attached for the whole change set**, and the network must not have been modified behind its back. A change the recorder did not see makes the reverse statements describe a state that never existed.
* **The values are IIDM values translated by the same mapping as the forward direction**, not the values the CGMES file the network was imported from originally held. They are as faithful as that import; where the import normalises something, the reverse statement carries the normalised value.

A previous value that was not recorded is never guessed: the change is reported as unsupported instead, exactly as a change the profile cannot express.

Consumers should apply a difference with replace semantics — for every statement, set that property of that subject to that value — rather than by matching the reverse statements against what they hold. The reverse direction is what lets them undo the change, not a precondition to check.

### One difference model per profile

A change set that touches several CGMES profiles produces **one difference model per profile**, bundled in memory as a `DifferenceModelSet` and written as one file per profile, named `<baseName>_<SUBSET>_DIFF.xml`, into any `DataSource`: a directory, or a `ZipArchiveDataSource` for one zip with one entry per profile. Never several models in one document, and never one model declaring several profiles, although CGMES 2.5 would allow the latter. Three reasons:

* the `Supersedes` and the version of a combined model are undefined against the per-profile full models it applies on;
* a database layer needs one named graph per model;
* the profiles of one change set are applied in profile order, which a set makes explicit.

The single document methods (`toString` and the `write` overloads taking an `OutputStream` or a `Path`) take the profile to write. When it is given, a change describing another profile becomes an unsupported change — it throws with `FAIL` and is skipped, and left out of the returned events, with `IGNORE`. When it is `null`, the changes have to touch at most one profile; touching more throws. A change set that produced no difference at all is still written, as a well formed document with an empty forward and an empty reverse container.

When a change set touches the equipment model as well as the steady state hypothesis, the steady state difference applies on top of the equipment difference rather than on the equipment model it was derived from, so its `Model.DependentOn` on the source equipment model is replaced by the identifier of the equipment difference. Clearing the dependencies of that header switches this off.

### Header options

Every header value has the same default as a partial file: the identifier is generated and reproducible, the version is the version of the source model of that profile incremented by one, and the difference supersedes that source model and inherits its dependencies. The identifier is derived from the same references as the one of a partial file plus one saying that this is a difference model, so the two never collide while both stay reproducible.

```java
CgmesDiffExport.ExportOptions options = new CgmesDiffExport.ExportOptions()
        .setGranularity(DiffGranularity.CHANGED_ONLY)
        .setCreated(ZonedDateTime.now());
options.header(CgmesSubset.STEADY_STATE_HYPOTHESIS)
        .setDescription("Load redispatch")   // otherwise the description of the source model is inherited
        .setModelingAuthoritySet("https://www.powsybl.org/");
```

Two header values are inherited from the source model of the profile and are worth overriding: the description, which otherwise repeats the description of the model the network was imported from word for word (a conformity model's legal disclaimer, for instance) rather than saying what the change was, and the modeling authority set, when the sender is not the authority that produced the source model.

An export never modifies the metadata of the network it reads, so a second export from the same network supersedes the same source model as the first one. A sender building a chain says so explicitly:

```java
CgmesDiffExport.Result first = CgmesDiffExport.toDifferences(network, firstChanges, new ExportOptions());
CgmesDiffExport.ExportOptions next = new CgmesDiffExport.ExportOptions();
next.header(CgmesSubset.STEADY_STATE_HYPOTHESIS)
        .chainAfter(first.differences().get(CgmesSubset.STEADY_STATE_HYPOTHESIS).orElseThrow().header());
```

`chainAfter` makes the new difference supersede the previous one and carry the next version. `DifferenceModel.compose` folds such a chain back into the single difference with the same net effect, and `DifferenceModel.inverted` produces the difference that undoes one.

### Supported changes per profile

Everything [the partial SSH export supports](#supported-changes) reaches the forward statements of a difference unchanged: both exports share the mapping. A difference model additionally carries the equipment values below, which a partial SSH file cannot express.

| IIDM change | CGMES object / property | CGMES 2.4.15 | CGMES 3 | Reverse exactness |
| --- | --- | --- | --- | --- |
| Permanent and temporary limit values of current, active power and apparent power limits (branches, three windings transformer legs, boundary lines) | `CurrentLimit\|ActivePowerLimit\|ApparentPowerLimit.value` of the stored mRID | EQ | SSH, also carried by a partial SSH file | exact |
| `VoltageLevel` high / low limit of a voltage level built from `VoltageLimit` objects | `VoltageLimit.value` of every stored identifier | EQ | SSH | exact for one identifier, aggregate for several |
| `VoltageLevel` high / low limit of a voltage level with no `VoltageLimit` objects | `VoltageLevel.highVoltageLimit\|lowVoltageLimit` | EQ | EQ | exact |
| `Line` `r`, `x` | `ACLineSegment\|SeriesCompensator\|EquivalentBranch.r\|.x` | EQ | EQ | exact (lossless formatting) |
| `Line` `g1` = `g2`, `b1` = `b2` | `ACLineSegment.gch` = `g1 + g2`, `.bch` = `b1 + b2` | EQ | EQ | exact while symmetric; the import splits the total equally |
| `BoundaryLine` `r`, `x`, `g`, `b` | `ACLineSegment.r\|.x\|.gch\|.bch`, or `EquivalentBranch.r\|.x` | EQ | EQ | exact |

#### Equipment changes and their limits

* **Which CGMES object a limit is.** CGMES models every limit value as an `OperationalLimit` object of its own, and the import stores the master resource identifier of each of them as a property of the `OperationalLimitsGroup` it filled (`CGMES.OperationalLimit_<Class>_patl` and `..._tatl_<duration>`). The export writes the stored identifier. A network that was never imported from CGMES has none, and then the identifier a full equipment export would write is used instead, so that a receiver which read such an export resolves it. A limit of a CGMES network that carries no identifier was created after the import and has no CGMES object at all: that change is reported as unsupported.
* **The consistency group is the whole `LoadingLimits` object.** IIDM reports every permanent limit of a side under one attribute name and every temporary limit under a second one, so a change says neither which group nor which duration it means. Both are taken from the payload of the event, the whole set of loading limits is described, and the difference then keeps only the limits whose two directions really differ.
* **Which profile carries a limit value** is decided by the CIM version: the equipment profile in CGMES 2.4.15, the steady state hypothesis in CGMES 3, where the equipment model keeps a `normalValue` a difference never touches.
* **A voltage level with several `VoltageLimit` objects** receives the IIDM value on all of them, in both directions: IIDM holds one aggregate, not the individual values. The state of the receiver after applying and after undoing is exact; the reverse statements of the objects that were not the binding ones are derived from IIDM rather than remembered.
* **A limit the import synthesized never travels.** When a CGMES `OperationalLimitSet` carries no permanent limit, the import creates one (`missing-permanent-limit-percentage`), and that limit is not a CGMES object at all. A change of the other limits of such a set is exported normally and the synthesized permanent limit is simply left out, because the receiver derives its own from the same option; a change of the synthesized limit itself is refused, because there is nothing to write it on.
* **Several CGMES limits folded into one IIDM limit.** When a terminal carries more than one permanent limit, or more than one temporary limit of the same duration, the import keeps the lowest one and stores only its identifier. A difference therefore moves the kept object and leaves the others at their base value; a full re-import of the receiving model may then pick one of those others if the new value rose above it.
* **An `EquivalentBranch` states the impedance of both directions.** IIDM holds a single value, so a difference writes `EquivalentBranch.r21`/`.x21` next to `.r`/`.x`. Without them a store-level application would leave a base file whose `r21` no longer matches its `r`, and the conversion refuses such a branch entirely.
* **Impedances are written with a lossless formatter.** The shared formatter keeps fourteen decimals, which rounds a susceptance of 1e-15 S to zero; a difference has to restore the value the sender held exactly, so an impedance literal is the shortest decimal that parses back to the same double.

#### Not supported

These equipment changes are reported as unsupported, for the same reason as every other one: a receiver could not act on them.

* Selecting, creating or removing an operational limits group, and adding or removing a limit: CGMES models these with objects, not with values, so they are a structural change.
* A whole replacement of a set of loading limits that changes its structure, that is the presence of the permanent limit or the set of acceptable durations.
* A limit of a network imported from CGMES that carries no `CGMES.OperationalLimit_*` identifier, which means it was created after the import.
* A limit value that is not finite or is negative.
* A change of one side only of a limit whose CGMES `OperationalLimitSet` is attached to the *equipment* of a line: one CGMES value describes both sides, so both have to take the same value.
* A voltage limit that is not strictly inside the range the `VoltageLevel.highVoltageLimit` and `.lowVoltageLimit` attributes declare, or that would put the low limit above the high one: the receiving conversion filters those out and would keep its previous value.
* A voltage limit of a voltage level whose IIDM limit *is* the `VoltageLevel` attribute, because no `VoltageLimit` object narrowed the range. The change could be applied but not undone: the reverse statement would carry a value the receiver filters out again, so it is refused with that reason.
* A high or low limit of a voltage level that several CGMES `VoltageLevel` objects were merged into.
* The impedance of a `TwoWindingsTransformer` or of a `ThreeWindingsTransformer`, see the cut below.
* An impedance of a line whose CGMES class is not `ACLineSegment`, `SeriesCompensator` or `EquivalentBranch`, of a branch the import represented as a switch, or a zero impedance that would make the receiver create a switch (both ends in one voltage level).
* An asymmetric shunt admittance of a line (`g1 != g2` or `b1 != b2`, relative tolerance 1e-9 &mdash; relative, because an absolute tolerance of 1e-9 S would accept a difference in the fifth significant digit of a susceptance of 4.6e-4 S): CGMES holds one total that the import splits equally.
* A shunt admittance of a `SeriesCompensator` or of an `EquivalentBranch`, which have none in CGMES, and any impedance of an `EquivalentBranch` whose two ends have different nominal voltages, because the import folds the ratio between them into the IIDM value.

#### Cut, with the reason

* **Transformer impedances** (`r`, `x`, `g`, `b`, `ratedU*`, `ratedS` of a two or three windings transformer). CGMES holds them per `PowerTransformerEnd`, plus the corrections of the tap steps; the import folds both ends and the structural ratio into one IIDM value, depending on import options the network does not remember, and a full export writes everything on end 1 scaled by `(ratedU1/ratedU2)²`. Neither the split between the ends nor the options can be recovered from IIDM, so a reverse statement set equal to the base file cannot be produced and a forward set would silently rewrite the other end to zero. Such a change needs a re-import.
* **`TieLine`**: it has no impedance setters and therefore fires no event. Its halves are `BoundaryLine`s and are supported.
* **The `normalValue` of a CGMES 3 limit**: the update workflow does not read it and IIDM has no attribute for it.
* **The acceptable duration and the name of a temporary limit**: IIDM has no setter for either, so no change of them is ever recorded. A change of the name of a permanent limit is recorded, says the same thing in both directions and is therefore dropped as a no-op.
* **`VoltageLevel.nominalV` and `BaseVoltage`**: out of scope of this release.

### Exporting one variant of a network

`CgmesDiffExport.ExportOptions.setVariant(id)` — and `PartialSshExport.ExportOptions.setVariant(id)` — export the
state of one IIDM variant: changes recorded on another variant are dropped, because naming a variant *is* the
selection, and the export runs with that variant selected so the values written are its values. The working
variant of the calling thread is restored afterwards, including when the export throws.

That selects the *values*. It does not change what the header says the difference **supersedes**, which is the
model the network as a whole is at. A network whose variants stand for stored snapshots therefore wraps the call
in `RdfDbProvenance.inVariant(network, id, …)` (see [the RDF database](rdf_database.md)), which installs that
variant's identity for the duration of the export as well:

```java
RdfDbProvenance.inVariant(network, "08:30",
        () -> CgmesDiffExport.toString(network, events, CgmesSubset.STEADY_STATE_HYPOTHESIS, FAIL));
```

`setRejectSharedChanges(true)` turns a change IIDM does not store per variant into an unsupported change. Such a
change is recorded *without* a variant identifier, which is how it is recognised; with more than one variant in
the network it describes the state of all of them, so writing it into the history of one would be a lie about the
others. Both options are off by default, and the golden files of this exporter are what pins that.

See [the RDF database](rdf_database.md) for `RdfDbExport.exportVariant` and `exportPerVariant`, which write one
history per variant.

### Limitations

Everything the partial SSH export reports as unsupported is unsupported here too. A difference model adds these, all of them about the state *before* the change:

* **A previous value that was not recorded.** An event carrying no old value, or an old value of an unexpected type, makes the change unsupported rather than wrong: an unset section count or tap position, or an `UpdateNetworkEvent` built by hand without an old value.
* **The creation of an extension.** A partial SSH file describes the state that follows a creation and is happy with it, but the values the extension carried before it existed are not recorded anywhere. A recorded creation of an `activePowerControl` or of a `generatorRemoteReactivePowerControl` is therefore not exportable as a difference. Reference priorities are the exception: an absent one *is* a priority of zero, by definition of `ReferencePriority.get`, so their creation is exportable.
* **A change is exported only if both directions succeed.** The reported reason is the first failure, the forward one when both fail.

Creations and removals of equipment are out of scope of this release, in both directions, and so is the content of `dm:preconditions`. A difference model produced here is read back by the [difference model update](import.md#cgmes-import-difference-model), which applies it to a loaded network in place and can undo it again.

## Topology kind

The elements written in the exported files depend on the topology kind of the export and on the CIM version.
By default, the export topology kind is computed from the IIDM network's `VoltageLevel` [connectivity level](../../grid_model/network_subnetwork.md#voltage-level) as follows:
* If all `VoltageLevel` of the network are at `node/breaker` connectivity level, then the export topology kind is `NODE_BREAKER`
* If all `VoltageLevel` of the network are at `bus/breaker` connectivity level, then the export topology kind is `BUS_BRANCH`
* If some `VoltageLevel` of the network are at `node/breaker` and some other at `bus/breaker` connectivity level, then the export's topology kind depends on the CIM version for export:
it is `BUS_BRANCH` for CIM 16 and `NODE_BREAKER` for CIM 100

It is however possible to ignore the computed export topology kind and force it to be `NODE_BREAKER` or `BUS_BRANCH` by setting the parameter [`iidm.export.cgmes.topology-kind`](#options).

The various configurations and the differences in what's written are summarized in the following table:

| CIM version | Export<br/>topology kind | Connectivity elements<br/>are written | CIM 16 Equipment Operation<br/>elements are written |
|-------------|--------------------------|---------------------------------------|-----------------------------------------------------|
| 16          | `NODE_BREAKER`           | Yes (*)                               | Yes                                                 |
| 16          | `BUS_BRANCH`             | No                                    | No                                                  |
| 100         | `NODE_BREAKER`           | Yes (*)                               | Yes                                                 |
| 100         | `BUS_BRANCH`             | Yes (**)                              | Yes                                                 |

### Connectivity elements
* Non-retained `Switch` are always written in the case of a `NODE_BREAKER` export, and never written in the case of a `BUS_BRANCH` export.
  * Having non-retained open switches in a node/breaker network that is exported as bus/branch may result in multiple connectivity components in the exported network.
  * To avoid this, it would be best to close all non-retained switches in the case before exporting it.
  * Then, the maximum amount of connectivity will be preserved in the export, and the bus/branch exported files can more easily be used for later calculations.
* `ConnectivityNode` are:
  * Never exported in the case of a CIM 16 `BUS_BRANCH` export
  * (*) Always exported in the case of a `NODE_BREAKER` export. If the VoltageLevel's connectivity level is `node/breaker`,
they are exported from nodes, and if the VoltageLevel's connectivity level is `bus/breaker`, they are exported from buses
  * (**) Exported from buses of the BusBreakerView in case of a CIM 100 `BUS_BRANCH` export

### CIM 16 Equipment Operation elements
If the version is CIM 16, a `BUS_BRANCH` export is intrinsically linked to not writing the _operation_ stereotype elements.

This means the following classes are not written:
* `ConnectivityNode`
* `StationSupply`
* `GroundDisconnector`
* `ActivePowerLimit`
* `ApparentPowerLimit`
* `LoadArea`
* `SubLoadArea`
* `SvStatus`

As well as the following attributes:
* `LoadGroup.SubLoadArea`
* `ControlArea.EnergyArea`

In CIM 100 these elements have been integrated in the core equipment profile and can be written even if the export is `BUS_BRANCH`.

## Conversion from PowSyBl grid model to CGMES

The following sections describe in detail how each supported PowSyBl network model object is converted to CGMES network components.

(cgmes-battery-export)=
### Battery

PowSyBl [`Battery`](../../grid_model/network_subnetwork.md#battery) is exported as `SynchronousMachine` with `HydroGeneratingUnit`.

<span style="color: red">TODO details</span>

(cgmes-busbar-section-export)=
### BusbarSection

PowSyBl [`BusbarSection`](../../grid_model/network_subnetwork.md#busbar-section) is exported as CGMES `BusbarSection`.

<span style="color: red">TODO details</span>

(cgmes-boundary-line-export)=
### BoundaryLine

PowSyBl [`BoundaryLine`](../../grid_model/network_subnetwork.md#boundary-line) is exported as several CGMES network objects.
Each boundary line will be exported as one `EquivalentInjection` and one `ACLineSegment`.

<span style="color: red">TODO details</span>

### Detailed DC model

#### DC node

PowSyBl [`DC Node`](../../grid_model/network_subnetwork.md#dc-node) is exported as CGMES `DCNode`, with attribute:
- EQ `DCEquipmentContainer` is a CGMES `DCConverterUnit`, which is the container of the closest converter.

#### DC Line

PowSyBl [`DC Line`](../../grid_model/network_subnetwork.md#dc-line) is exported as CGMES `DCLineSegment`, with attribute:
- EQ `resistance` is copied from `R`.

#### DC Switch

PowSyBl [`DC Switch`](../../grid_model/network_subnetwork.md#dc-switch) is exported as:
- CGMES `DCBreaker` if attribute `Kind` is `BREAKER`.
- CGMES `DCDisconnector` if attribute `Kind` is `DISCONNECTOR`.

#### DC Ground

PowSyBl [`DC Ground`](../../grid_model/network_subnetwork.md#dc-ground) is exported as CGMES `DCGround`, with attribute:
- EQ `r` is copied from `R`.

#### AC/DC Converter (Line Commutated Converter, Voltage Source Converter)
PowSyBl [`Line Commutated Converter`](../../grid_model/network_subnetwork.md#line-commutated-converter) is exported as CGMES `CsConverter`,
and PowSyBl [`Voltage Source Converter`](../../grid_model/network_subnetwork.md#voltage-source-converter) as CGMES `VsConverter`.
They share the following attributes:
- EQ `idleLoss` is copied from `IdleLoss`.
- EQ `switchingLoss` is copied from `SwitchingLoss`.
- EQ `resistiveLoss` is copied from `ResistiveLoss`.
- EQ `ratedUdc` is copied from the `NominalV` of the associated `DC Node`.
- EQ `PccTerminal` is copied from `PccTerminal`.
- SSH `targetPpcc` is copied from `TargetP`.
- SSH `targetUdc` is copied from `TargetVdc`.
- SSH `p` is the PCC terminal's `P` value.
- SSH `q` is the PCC terminal's `Q` value.

Specific `Line Commutated Converter` attributes:
- SSH `pPccControl` is `CsPpccControlKind.activePower` if `ControlMode` is `P_PCC`, else it is `CsPpccControlKind.dcVoltage`.
- SSH `operatingMode` is `CsOperatingModeKind.rectifier` if the `TargetP` is greater than 0, else it is `CsOperatingModeKind.inverter`.
- SSH `targetAlpha` is defaulted to 0.
- SSH `targetGamma` is defaulted to 0.
- SSH `targetIdc` is defaulted to 0.

Specific `Voltage Source Converter` attributes:
- SSH `pPccControl` is `VsPpccControlKind.pPcc` if `ControlMode` is `P_PCC`, else it is `VsPpccControlKind.udc`.
- SSH `qPccControl` is `VsQpccControlKind.voltagePcc` if `VoltageRegulatorOn` is set to `true`, else it is `VsQpccControlKind.reactivePcc`.
- SSH `targetUpcc` is copied from `VoltageSetpoint`.
- SSH `targetQpcc` is copied from `ReactivePowerSetpoint`.
- SSH `droop` is defaulted to 0.
- SSH `droopCompensation` is defaulted to 0.
- SSH `qShare` is defaulted to 0.

(cgmes-generator-export)=
### Generator

PowSyBl [`Generator`](../../grid_model/network_subnetwork.md#generator) is exported as CGMES `SynchronousMachine`.

#### Regulating control

If the network comes from a CIM-CGMES model and a generator initially has a `RegulatingControl`, it will still have one at export.
Otherwise, a `RegulatingControl` is always exported for generators, except if it has no regulating capabilities because
$minQ = maxQ$.

A `RegulatingControl` is exported with `RegulatingControl.mode` set to `RegulatingControlModeKind.reactivePower` when a
generator has the extension [`RemoteReactivePowerControl`](../../grid_model/extensions.md#remote-reactive-power-control)
with the `enabled` activated and the generator attribute `voltageRegulatorOn` set to `false`. In all other cases, a
`RegulatingControl` is exported with `RegulatingControl.mode` set to `RegulatingControlModeKind.voltage`.

#### SynchronousMachine type (EQ) and operatingMode (SSH)

The `SynchronousMachine.type` is exported in the EQ profile depending on the [reactive limits](../../grid_model/additional.md#reactive-limits) of the
generator or battery and its capacity to behave like a condenser (a battery can behave like a condenser but does not have the flag `isCondenser` so we consider it as `true`):
- if the flag `isCondenser` is `true`:
  - if the minimum and the maximum active power limit are positive, then the generator or battery will be exported as `generatorOrCondenser`,
  - if the minimum and the maximum active power limit are negative, then the generator or battery will be exported as `motorOrCondenser`,
  - if the minimum and the maximum active power limit are both equal to zero, then the generator or battery will be exported as `condenser`,
  - otherwise, the generator or battery will be exported as `generatorOrCondenserOrMotor`.
- if the flag `isCondenser` is `false`:
  - if the minimum active power limit is positive, then the generator or battery will be exported as `generator`,
  - if the maximum active power limit is negative, then the generator or battery will be exported as `motor`,
  - otherwise, the generator will be exported as `generatorOrMotor`.

The `SynchronousMachine.operatingMode` is exported in the SSH profile depending on the target active
power of the generator or battery and on fact that it is regulating or not:
- if the target active power is positive, then the generator or battery will be exported as `generator`,
- if the target active power is negative, then the generator or battery will be exported as `motor`,
- if the target active power is zero and the generator or battery is regulating, then the operating mode will be `condenser` if it is allowed by its `SynchronousMachine.type`.
- otherwise, the generator or battery will be exported as `generator` if is allowed by its `SynchronousMachine.type`,
otherwise `motor` and otherwise `condenser`.
To know if the generator or battery is behaving as a condenser, its `targetV`, `targetQ` and `voltageRegulatorOn` attributes are used.

(cgmes-hvdc-export)=
### HVDC line and HVDC converter stations

A PowSyBl [`HVDCLine`](../../grid_model/network_subnetwork.md#hvdc-line) and its two [`HVDCConverterStations`](../../grid_model/network_subnetwork.md#hvdc-converter-station)
represents a monopole with ground return configuration. As such, it is exported to CGMES EQ as a `DCLineSegment` with two `DCConverterUnits`, where each unit contains:
- A specialization of `ACDCConverter`, depending on the `HvdcConverterStation.HvdcType`:
  - A `CsConverter` if the type is `LCC`.
  - A `VsConverter` if the type is `VSC`.
- A `DCGround`

Both unit `operationMode` is `DCConverterOperatingModeKind.monopolarGroundReturn`.

Both converter `ratedUdc` is `NominalV` of the `HvdcLine`.

As for the CGMES SSH export:

The converter `pPccControl` is:
- Active power control kind at rectifier side:
  - `CsPpccControlKind.activePower` for `CsConverter`.
  - `VsPpccControlKind.pPcc` for `VsConverter`.
- DC voltage control kind at inverter side:
  - `CsPpccControlKind.dcVoltage` for `CsConverter`.
  - `VsPpccControlKind.udc` for `VsConverter`.

The corresponding targets are exported as follows:
- At rectifier side, `ACDCConverter.targetPpcc` is equal to `HvdcLine`'s `ActivePowerSetpoint`.
- At inverter  side, `ACDCConverter.targetUdc` is equal to $NominalV - U_{R}$, where $U_{R}$ is the voltage drop caused by the line's resistance
and is equal to: $U_{R} = R \times \frac{ActivePowerSetpoint}{NominalV}$

For VSC lines, the `qPccControl` is the same for both rectifier and inverter, and depends on the regulation mode:
- It is `VsQpccControlKind.voltagePcc` if VSC voltage regulation is on.
- It is `VsQpccControlKind.reactivePcc` if VSC voltage regulation is off.

The corresponding targets are:
- In case of voltage regulation, `targetUpcc` is set to `VscConverterStation.VoltageSetpoint`.
- In case of reactive power regulation, `targetQpcc` is set to `VscConverterStation.ReactivePowerSetpoint`.

(cgmes-line-export)=
### Line

PowSyBl [`Line`](../../grid_model/network_subnetwork.md#line) is exported as `ACLineSegment`.
The attribute `ConductingEquipment.BaseVoltage` is written from the `nominalV` of the voltage level on both sides of the line:
- if the nominal voltage is the same on both sides of the `Line`, then the `BaseVoltage` is set to this nominal voltage,
- otherwise, it is set to the highest nominal voltage.

<span style="color: red">TODO details</span>

(cgmes-load-export)=
### Load

PowSyBl [`Load`](../../grid_model/network_subnetwork.md#load) is exported as `ConformLoad`, `NonConformLoad`, `EnergyConsumer`, `EnergySource`.

The CGMES class used for the export is determined as follows:
- If the `Load` has a [`LoadDetail`](../../grid_model/extensions.md#load-detail) extension:
  - it is exported as `ConformLoad` if the fixed part is zero and the variable part is non-zero,
  - it is exported as `NonConformLoad` if the variable part is zero and the fixed part is non-zero,
- If the `Load` has no [`LoadDetail`](../../grid_model/extensions.md#load-detail) extension, it is exported as `EnergyConsumer`.
- If the active power `P0` is negative, the load is exported as `EnergySource` regardless of the extension.

If the `Load` had a CGMES original class stored as a property (i.e., it comes from a CGMES import), it is preserved at export as long as it remains consistent with the sign of `P0` and the [`LoadDetail extension`](../../grid_model/extensions.md#load-detail). An IIDM `Load` can be created from the following CGMES classes: `AsynchronousMachine`, `EnergySource`, `EnergyConsumer`, `ConformLoad`, `NonConformLoad`, `StationSupply`.

In the EQ profile, the exported element references the `EquipmentContainer` of the voltage level and a `LoadGroup` (`ConformLoadGroup` or `NonConformLoadGroup` depending on the class). 
A single `LoadArea` and `SubLoadArea` are created for the whole network.

If the `Load` has a load model (see [load model](../../grid_model/network_subnetwork.md#load)), a `LoadResponseCharacteristic` is exported in EQ and referenced by the load:
- For an exponential load model, the `exponentModel` attribute is set to `true`, and `pVoltageExponent` and `qVoltageExponent` are set from the model's `np` and `nq`.
- For a ZIP load model, the `exponentModel` attribute is set to `false`, and the constant power, current, and impedance parts are set accordingly.

In the SSH profile:
- For `ConformLoad`, `NonConformLoad` and `EnergyConsumer`, the attributes `EnergyConsumer.p` and `EnergyConsumer.q` are written from the IIDM `P0` and `Q0`.
- For `EnergySource`, the attributes `EnergySource.activePower` and `EnergySource.reactivePower` are written from the IIDM `P0` and `Q0`.
- For `AsynchronousMachine`, the attributes `RotatingMachine.p` and `RotatingMachine.q` are written from the IIDM `P0` and `Q0`. 
NOTE: SSH attributes for active and reactive power of `EnergySource` and `AsynchronousMachine` in CGMES must be given with load sign convention, so no sign change has to be made from IIDM `P0, Q0` values.
In the SV profile, a `SvPowerFlow` is written for the terminal of the load with the terminal `P` and `Q` values.

(cgmes-fictitious-injections-export)=
### Fictitious injections (fictitiousP0/fictitiousQ0)

The fictitious injections on buses (bus-branch topology) or on nodes (node-breaker topology) are created using:
- Bus topology: `Bus.setFictitiousP0(double)` and `Bus.setFictitiousQ0(double)`
- Node-breaker: `VoltageLevel.getNodeBreakerView().setFictitiousP0(int node, double)` and `setFictitiousQ0(int node, double)`

These fictitious injections are exported in CGMES as either a `NonConformLoad` or an `EnergySource`, depending on the sign of `fictitiousP0`, 
with values written to SSH and connectivity/topology bindings set according to the network topology and CIM version. 
A corresponding 'SvPowerFlow' is written for the terminal in the SV.
In case of a node-breaker or CIM100 export, the terminal will refer to a `ConnectivityNode`.

If the EQ profile is not exported and the network contains fictitious injections, note that the references to equipment in the SSH and SV will be invalid.

(cgmes-shunt-compensator-export)=
### Shunt compensator

PowSyBl [`ShuntCompensator`](../../grid_model/network_subnetwork.md#shunt-compensator) is exported as `LinearShuntCompensator` or `NonlinearShuntCompensator` depending on their models.
The CGMES SSH `sections` is written from the IIDM `SectionCount`, and the CGMES SV `SvShuntCompensatorSections.sections`
is written from the IIDM `SolvedSectionCount` if present, otherwise `SectionCount`.

#### Regulating control

If the network comes from a CIM-CGMES model and a shunt compensator initially has a `RegulatingControl`, it will still
have one at export.

A shunt compensator with local voltage control (i.e., the regulating terminal is the same of the terminal of connection)
and no valid voltage target will not have any exported regulating control. In all other cases, a `RegulatingControl`
is exported with `RegulatingControl.mode` set to `RegulatingControlModeKind.voltage`.

(cgmes-static-var-compensator-export)=
### StaticVarCompensator

PowSyBl [`StaticVarCompensator`](../../grid_model/network_subnetwork.md#static-var-compensator) is exported as `StaticVarCompensator`.

#### Regulating control

If the network comes from a CIM-CGMES model and a static VAR compensator initially has a `RegulatingControl`, it will still
have one at export.

A static VAR compensator which voltage control is local (i.e., the regulating terminal is the same of the terminal of
connection) and no valid voltage or reactive power target will not have any exported regulating control.

A `RegulatingControl` is exported with `RegulatingControl.mode` set to `RegulatingControlModeKind.reactivePower` when
the static VAR compensator mode is `REACTIVE_POWER`. A `RegulatingControl` is exported with `RegulatingControl.mode` set
to `RegulatingControlModeKind.voltage` when the static VAR compensator mode is `VOLTAGE`. When the static VAR compensator
is `OFF`, the exported regulating control mode will be reactive power only if the voltage target is not valid but the
reactive power target is. Otherwise, the exported mode will be voltage.

(cgmes-substation-export)=
### Substation

PowSyBl [`Substation`](../../grid_model/network_subnetwork.md#substation) is exported as `Substation`.

<span style="color: red">TODO details</span>

(cgmes-switch-export)=
### Switch

PowSyBl [`Switch`](../../grid_model/network_subnetwork.md#breakerswitch) is exported as CGMES `Breaker`, `Disconnector` or `LoadBreakSwitch` depending on its `SwitchKind`.

<span style="color: red">TODO details</span>

(cgmes-three-winding-transformer-export)=
### ThreeWindingsTransformer

PowSyBl [`ThreeWindingsTransformer`](../../grid_model/network_subnetwork.md#three-winding-transformer) is exported as `PowerTransformer` with three `PowerTransformerEnds`.
If the transformer has a `TapChanger`, the CGMES SSH `step` is written from the IIDM `TapPosition` and the CGMES SV
`SVtapStep` is written from the IIDM `SolvedTapPosition` if it is not null, otherwise `TapPosition`.

#### Tap changer control

If the network comes from a CIM-CGMES model and the tap changer has initially a `TapChangerControl`, it always has at export
too. Otherwise, a `TapChangerControl` is exported for the tap changer if it is considered as defined. A `RatioTapChanger`
is considered as defined if it has a valid regulation value, a valid target deadband and a non-null regulating terminal.
A `PhaseTapChanger` is considered as defined if it has a valid regulation value, a valid target deadband, and a non-null regulating terminal.

In a `RatioTapChanger`, the `TapChangerControl` is exported with `RegulatingControl.mode` set to `RegulatingControlModeKind.reactivePower` when
`RatioTapChanger` `regulationMode` is set to `REACTIVE_POWER`, and with `RegulatingControl.mode` set to `RegulatingControlModeKind.voltage` when
`RatioTapChanger` `regulationMode` is set to `VOLTAGE`.

In a `PhaseTapChanger`, the `TapChangerControl` is always exported with `RegulatingControl.mode` set to `RegulatingControlModeKind.activePower`.
If the original `PhaseTapChanger` `regulationMode` is `CURRENT_LIMITER`, the `TapChangerControl` regulation is disabled,
the regulation target value and deadband are set to 0, and an `OperationalLimitSet` with a `CurrentLimit` is created
at the regulated terminal with the regulation value.

(cgmes-two-winding-transformer-export)=
### TwoWindingsTransformer

PowSyBl [`TwoWindingsTransformer`](../../grid_model/network_subnetwork.md#two-winding-transformer) is exported as `PowerTransformer` with two `PowerTransformerEnds`.

If the IIDM `TwoWindingsTransformer` does not have a `ratedS`, then a default value of `100` is exported for `PowerTransformerEnd.ratedS` as this field is mandatory.
The `ratedS` will be the same on both ends of the `PowerTransformer`.

If the transformer has a `TapChanger`, the CGMES SSH `step` is written from the IIDM `TapPosition` and the CGMES SV
`SVtapStep` is written from the IIDM `SolvedTapPosition` if it is not null, otherwise `TapPosition`.

Tap changer controls for two-winding transformers are exported following the same rules explained in the previous section about three-winding transformers. See [tap changer control](#tap-changer-control).

(cgmes-operational-limits-export)=
### Operational limits

PowSyBl exports IIDM loading limits to CGMES OperationalLimit elements as follows:
- Permanent limits are exported with type PATL (Permanent Allowable Transmission Limit) and a corresponding OperationalLimit value.
- Temporary limits are exported with type TATL (Temporary Allowable Transmission Limit), parameterized by the acceptable duration in seconds.
If a temporary limit name is empty in IIDM, a fallback name is written in EQ as: TATL <acceptableDurationInSeconds> (for example: TATL 600).

This applies to CurrentLimits, ActivePowerLimits, and ApparentPowerLimits.


(cgmes-voltage-level-export)=
### Voltage level

PowSyBl [`VoltageLevel`](../../grid_model/network_subnetwork.md#voltage-level) is exported as `VoltageLevel`.

<span style="color: red">TODO details</span>

## Extensions

(cgmes-control-areas-export)=
### Control areas

PowSyBl [`ControlAreas`](import.md#control-areas) are exported as several `ControlArea`.

<span style="color: red">TODO details</span>

(cgmes-export-options)=
## Options

These properties can be defined in the configuration file in the [import-export-parameters-default-value](../../user/configuration/import-export-parameters-default-value.md#import-export-parameters-default-value) module.

Note that if you are exporting a network that does not come from CGMES, you can use the [`iidm.import.cgmes.boundary-location`](#options) property to define the location of the boundary files to use as reference.

**iidm.export.cgmes.base-name**<br>
Optional property that defines the base name of the exported files. Exported CGMES files' names will look like this:
```
<base_name>_EQ.xml
<base_name>_TP.xml
<base_name>_SSH.xml
<base_name>_SV.xml
```
By default, the base name is the network's name if it exists, or else the network's ID.

**iidm.export.cgmes.boundary-eq-id**<br>
Optional property that defines the ID of the EQ-BD model if there is any.
Its default value is `null`: we consider there is no EQ-BD model to consider.
If this property is defined, then this ID will be written in the header of the exported EQ file.

**iidm.export.cgmes.boundary-tp-id**<br>
Optional property that defines the ID of the TP-BD model if there is any.
Its default value is `null`: we consider there is no TP-BD model to consider.
If this property is defined, then this ID will be written in the header of the exported SV file.

**iidm.export.cgmes.cim-version**<br>
Optional property that defines the CIM version number in which the user wants the CGMES files to be exported.
CIM versions 16 and 100 are supported i.e. its valid values are `16` and `100`.
If not defined, and the network has the extension `CimCharacteristics`, the CIM version will be the one indicated in the extension. If not, its default value is `16`.
CIM version 16 corresponds to CGMES 2.4.15.
CIM version 100 corresponds to CGMES 3.0.

**iidm.export.cgmes.encode-ids**<br>
Optional property that must be used if IIDM IDs that are not compliant with CGMES requirements are to be used as CGMES IDs. `true` by default. Used for debugging purposes.

**iidm.export.cgmes.export-boundary-power-flows**<br>
Optional property that defines if power flows at boundary nodes are to be exported in the SV file or not. `true` by default.

**iidm.export.cgmes.export-power-flows-for-switches**<br>
Optional property that defines if power flows of switches are exported in the SV file. `true` by default.

**iidm.export.cgmes.naming-strategy**<br>
Optional property that defines which naming strategy is used to transform IIDM identifiers to CGMES identifiers.
Available naming strategies are:
- `identity`: For IIDM objects that have an ID (e.g. TwoWindingTransformer), the CGMES ID is identical to the IIDM ID.
For IIDM objects that don't have an ID (e.g. TapChanger), either the CGMES ID is contained in a property or an alias
(this is typically the case when the network is the result of a CGMES import) and is exported as such, 
or there is no such property or alias and a combination of IIDM properties and constants is used to generate the CGMES ID.
- `cgmes`: The ID that would be generated by the identity naming strategy serves as basis.
When that ID is CGMES compliant (against IEC 61970-552), it is exported as such.
Otherwise, that ID is used to generate a compliant CGMES one in a deterministic way.

Its default value is `identity`.
You can also define a custom naming strategy by implementing the `NamingStrategy` interface on your own project and declare
a `NamingStrategyProvider` that can be automatically discovered. Then in this parameter, you can specify the name of the provider.

**iidm.export.cgmes.uuid-namespace**<br>
Optional property related to the naming strategy specified in `iidm.export.cgmes.naming-strategy`. When new CGMES IDs have to be generated, a mechanism that ensures creation of new, stable identifiers based on IIDM IDs is used (see [RFC 4122](https://datatracker.ietf.org/doc/html/rfc4122)). These new IDs are guaranteed to be unique inside a namespace given by this UUID. By default, it is the name-based UUID fo the text "powsybl.org" in the empty namespace.

**iidm.export.cgmes.profiles**<br>
Optional property that determines which instance files will be exported.
By default, it is a full CGMES export: the instance files for the profiles EQ, TP, SSH and SV are exported.

**iidm.export.cgmes.topology-kind**<br>
Optional property that defines the topology kind of the export. Allowed values are: `NODE_BREAKER` and `BUS_BRANCH`.
By default, the export topology kind reflects the network's voltage levels connectivity level detail: node/breaker or bus/breaker.
This property is used to bypass the natural export topology kind and force a desired one (e.g. export as bus/branch a node/breaker network).

**iidm.export.cgmes.modeling-authority-set**<br>
Optional property allowing to write a custom modeling authority set in the exported file headers. `powsybl.org` by default.
If a Boundary set is given with the property `iidm.import.cgmes.boundary-location` and the network sourcing actor is found inside it, then the modeling authority set will be obtained from the boundary file without the need to set this property.
The sourcing actor can be specified using the parameter `iidm.export.cgmes.sourcing-actor`.

**iidm.export.cgmes.model-description**<br>
Optional property allowing to write a custom model description in the file headers.
By default, the model description is `EQ model` for the EQ file, `TP model` for the TP file, `SSH model` for the SSH
file and `SV model` for the SV file.

**iidm.export.cgmes.export-transformers-with-highest-voltage-at-end1**<br>
Optional property defining whether the transformers should be exported with the highest voltage at end 1, even if it might not be the case in the IIDM model.
This property is set to `false` by default.

**iidm.export.cgmes.export-load-flow-status**<br>
Optional property that indicates whether the load flow status (`converged` or `diverged`) should be
written for the `TopologicalIslands` in the SV file. If `true`, the status will be computed by checking, for every bus,
if the voltage and angle are valid, and if the bus is respecting Kirchhoff's first law. For the latter, we check that
the sums of active power and reactive power at the bus are higher than a threshold defined by the properties
`iidm.export.cgmes.max-p-mismatch-converged` and `iidm.export.cgmes.max-q-mismatch-converged`.
This property is set to `true` by default.

**iidm.export.cgmes.export-all-limits-group**<br>
Optional property that defines whether all OperationalLimitsGroup should be exported, or only the selected (active) ones.
This property is set to `true` by default, which means all groups are exported (not only the active ones).

**iidm.export.cgmes.export-generators-in-local-regulation-mode**<br>
Optional property that allows to export voltage regulating generators in local regulation mode. This doesn't concern reactive power regulating generators.
If set to true, the generator's regulating terminal is set to the generator's own terminal and the target voltage is rescaled accordingly.
This property is set to `false` by default.

**iidm.export.cgmes.max-p-mismatch-converged**<br>
Optional property that defines the threshold below which a bus is considered to be balanced for the load flow status of the `TopologicalIsland` in active power. If the sum of all the active power of the terminals connected to the bus is greater than this threshold, then the load flow is considered to be divergent. Its default value is `0.1`, and it should be used only if the `iidm.export.cgmes.export-load-flow-status` property is set to `true`.

**iidm.export.cgmes.max-q-mismatch-converged**<br>
Optional property that defines the threshold below which a bus is considered to be balanced for the load flow status of the `TopologicalIsland` in reactive power. If the sum of all the reactive power of the terminals connected to the bus is greater than this threshold, then the load flow is considered to be divergent. Its default value is `0.1`, and it should be used only if the `iidm.export.cgmes.export-load-flow-status` property is set to `true`.

**iidm.export.cgmes.export-sv-injections-for-slacks**<br>
Optional property to specify if the total mismatch left after power flow calculation at IIDM slack buses should be exported as an SvInjection.
This property is set to `true` by default.

**iidm.export.cgmes.sourcing-actor**<br>
Optional property allowing to specify a custom sourcing actor. If a Boundary set with reference data is provided for the export through the parameter `iidm.import.cgmes.boundary-location`, the value of this property will be used to look for the modeling authority set and the geographical region to be used in the export.
No default value is given.
If this property is not given, the export process will still try to determine the sourcing actor from the IIDM network if it only contains one country.

**iidm.export.cgmes.model-version**<br>
Optional property defining the version of the exported CGMES file. It will be used if the version is not already available in the network.
The version will be written in the header of each exported file and will also be used to generate a unique UUID for the `FullModel` field.
Its default value is 1.

**iidm.export.cgmes.business-process**<br/>
The business process in which the export takes place. This is used to generate unique UUIDs for the EQ, TP, SSH and SV file `FullModel`.
Its default value is `1D`.

**iidm.export.cgmes.cgm_export**<br>
Optional property to specify the export use-case: IGM (Individual Grid Model) or CGM (Common Grid Model).
To export instance files of a CGM, set the value to `True`. The default value is `False` to export network as an IGM.

**iidm.export.cgmes.update-dependencies**<br>
Optional property to determine if dependencies in the exported instance files should be managed automatically. The default value is `True`.
