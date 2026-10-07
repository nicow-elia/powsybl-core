(cgmes-mapping)=
# Mapping

The steady state hypothesis of an IIDM network is described by one mapping, read by every export that writes it (the
full SSH export, the {ref}`partial SSH export <cgmes-partial-ssh-export>`, the
{ref}`difference model export <cgmes-difference-model-export>` and the database sink) and by the
{ref}`in-place import of a difference model <cgmes-import-difference-model>`, which completes a group of properties from
the description of its subject. The importer's update of loads, control areas and switches reads its values through it too.

The mapping is organised in families, one per kind of IIDM equipment. A *plain* family is data: rows of a CGMES
property, the IIDM attribute it is and the quantity that says its unit, sign and spelling. A *hand-written* family
states its rules in code (signs that depend on the regulating terminal, shared regulating controls, the two models of
an HVDC link, operational limits) and declares the keys it is asked for and the blocks it writes.

Everything below the following line is generated from the code by `MappingPageTest` (module `cgmes-conversion`),
which fails when this page is stale. Regenerate it with
`mvn -pl cgmes/cgmes-conversion test -Dtest=MappingPageTest -Dpowsybl.docs.regenerate=true`.

<!-- BEGIN GENERATED MAPPING: regenerate with MappingPageTest, do not edit by hand -->

## Plain families

A plain family is data: each row is one CGMES property and the IIDM attribute it is. The export writes the rows, the importer's update sets the rows it reads, and the in-place import describes a load with the same rows.

### `ENERGY_CONSUMER`

Update query `energyConsumers`, CIM classes `EnergyConsumer`, `ConformLoad`, `NonConformLoad`, `StationSupply`.

| CGMES property | query variable | IIDM attribute | quantity | read by the import |
| --- | --- | --- | --- | --- |
| `EnergyConsumer.p` | `p` | `p0` | `MW_LOAD` | yes |
| `EnergyConsumer.q` | `q` | `q0` | `MVAR_LOAD` | yes |

### `ENERGY_SOURCE`

Update query `energySources`, CIM classes `EnergySource`.

| CGMES property | query variable | IIDM attribute | quantity | read by the import |
| --- | --- | --- | --- | --- |
| `EnergySource.activePower` | `p` | `p0` | `MW_LOAD` | yes |
| `EnergySource.reactivePower` | `q` | `q0` | `MVAR_LOAD` | yes |

### `ASYNCHRONOUS_MACHINE`

Update query `asynchronousMachines`, CIM classes `AsynchronousMachine`.

| CGMES property | query variable | IIDM attribute | quantity | read by the import |
| --- | --- | --- | --- | --- |
| `RotatingMachine.p` | `p` | `p0` | `MW_LOAD` | yes |
| `RotatingMachine.q` | `q` | `q0` | `MVAR_LOAD` | yes |
| `RegulatingCondEq.controlEnabled` | `controlEnabled` | – (a constant) | `FLAG` | no |
| `AsynchronousMachine.asynchronousMachineType` | `type` | `p0` | `ASYNCHRONOUS_MACHINE_KIND` | no |

### Quantities

| quantity | unit multiplier | sign | sign of the regulating terminal | enumeration |
| --- | --- | --- | --- | --- |
| `MW_LOAD` | – | + | no | – |
| `MVAR_LOAD` | – | + | no | – |
| `FLAG` | – | + | no | – |
| `ASYNCHRONOUS_MACHINE_KIND` | – | + | no | AsynchronousMachineKind |
| `KV_TARGET` | k | + | no | – |
| `MVAR_TARGET` | M | + | yes | – |
| `MVAR_MACHINE_TARGET` | M | − | yes | – |
| `MW_TARGET` | M | + | yes | – |
| `AMPERE_LIMITER` | none | + | no | – |

## Hand-written families

A hand-written family states the rules of its equipment in code. Its keys are the IIDM attributes a recorded change is dispatched to it by; its blocks are what the CGMES update reads together: every required property has to be stated for any of them to be read.

### `SwitchAndTerminalFamily`

Keys:

* switch, DC switch: `open`

| block | update query | CIM classes | required | optional |
| --- | --- | --- | --- | --- |
| `SWITCH` | `switches` | `Switch`, `Breaker`, `Disconnector`, `LoadBreakSwitch`, `ProtectedSwitch`, `GroundDisconnector`, `Jumper` | `Switch.open` | – |
| `TERMINAL` | `terminals` | `Terminal` | `ACDCTerminal.connected` | – |
| `DC_TERMINAL` | `dcTerminals` | `DCTerminal`, `ACDCConverterDCTerminal` | `ACDCTerminal.connected` | – |

### `LoadFamily`

Keys:

* load: `p0`, `q0`, the keys of the plain rows

### `MachineFamily`

Keys:

* generator: `VoltageRegulation.TargetValue`, `VoltageRegulation.isRegulating`, `localTargetQ`, `localTargetV`, `targetP`
* boundary line: `p0`, `q0`, `targetP`, `targetQ`, `targetV`, `voltageRegulationOn`

| block | update query | CIM classes | required | optional |
| --- | --- | --- | --- | --- |
| `SYNCHRONOUS_MACHINE` | `synchronousMachinesForUpdate` | `SynchronousMachine` | `RotatingMachine.p`, `RotatingMachine.q` | `SynchronousMachine.referencePriority`, `SynchronousMachine.operatingMode`, `RegulatingCondEq.controlEnabled` |
| `EXTERNAL_NETWORK_INJECTION` | `externalNetworkInjections` | `ExternalNetworkInjection` | `ExternalNetworkInjection.p`, `ExternalNetworkInjection.q`, `ExternalNetworkInjection.referencePriority`, `RegulatingCondEq.controlEnabled` | – |
| `EQUIVALENT_INJECTION` | `equivalentInjections` | `EquivalentInjection` | `EquivalentInjection.p`, `EquivalentInjection.q` | `EquivalentInjection.regulationStatus`, `EquivalentInjection.regulationTarget` |
| `GENERATING_UNIT` | `generatingUnits` | `GeneratingUnit`, `ThermalGeneratingUnit`, `HydroGeneratingUnit`, `NuclearGeneratingUnit`, `SolarGeneratingUnit`, `WindGeneratingUnit` | `GeneratingUnit.normalPF` | – |

### `TapChangerAndShuntFamily`

Keys:

* shunt: `VoltageRegulation.TargetDeadband`, `VoltageRegulation.TargetValue`, `VoltageRegulation.isRegulating`, `localTargetV`, `sectionCount`
* static var compensator: `VoltageRegulation.TargetValue`, `VoltageRegulation.isRegulating`, `localTargetQ`, `localTargetV`
* phase tap changer, after the name a change gives it: `.tapPosition`, `.regulating`, `.regulationValue`, `.targetDeadband`
* ratio tap changer, after the name a change gives it: `.tapPosition`, `.VoltageRegulation.isRegulating`, `.VoltageRegulation.TargetValue`, `.VoltageRegulation.TargetDeadband`

| block | update query | CIM classes | required | optional |
| --- | --- | --- | --- | --- |
| `STATIC_VAR_COMPENSATOR` | `staticVarCompensators` | `StaticVarCompensator` | `StaticVarCompensator.q`, `RegulatingCondEq.controlEnabled` | – |
| `SHUNT_COMPENSATOR` | `shuntCompensators` | `LinearShuntCompensator`, `NonlinearShuntCompensator` | `ShuntCompensator.sections`, `RegulatingCondEq.controlEnabled` | – |
| `RATIO_TAP_CHANGER` | `ratioTapChangers` | `RatioTapChanger` | `TapChanger.step`, `TapChanger.controlEnabled` | – |
| `PHASE_TAP_CHANGER` | `phaseTapChangers` | `PhaseTapChangerLinear`, `PhaseTapChangerAsymmetrical`, `PhaseTapChangerSymmetrical`, `PhaseTapChangerNonLinear`, `PhaseTapChangerTabular` | `TapChanger.step`, `TapChanger.controlEnabled` | – |

### `RegulatingControlFamily`

Keys:

* holder: `VoltageRegulation.isRegulating` (echoed by `voltageRegulatorOn`, `regulating`), `VoltageRegulation.RegulationMode` (echoed by `regulationMode`), `VoltageRegulation.TargetDeadband` (echoed by `targetDeadband`), `VoltageRegulation.Terminal` (echoed by `regulatingTerminal`), `VoltageRegulation.Slope`, `localTargetV` (echoed by `targetV`, `voltageSetpoint`, `reactivePowerSetpoint`), `localTargetQ` (echoed by `targetV`, `voltageSetpoint`, `reactivePowerSetpoint`), `VoltageRegulation.TargetValue` (echoed by `targetV`, `voltageSetpoint`, `reactivePowerSetpoint`)
* ratio tap changer: `VoltageRegulation.isRegulating` (echoed by `regulating`), `VoltageRegulation.RegulationMode` (echoed by `regulationMode`), `VoltageRegulation.TargetDeadband` (echoed by `targetDeadband`), `VoltageRegulation.Terminal` (echoed by `regulationTerminal`), `VoltageRegulation.TargetValue` (echoed by `regulationValue`)
* generator: `VoltageRegulation.TargetValue`, `VoltageRegulation.isRegulating`, `localTargetV`

| block | update query | CIM classes | required | optional |
| --- | --- | --- | --- | --- |
| `REGULATING_CONTROL` | `regulatingControls` | `RegulatingControl`, `TapChangerControl` | `RegulatingControl.enabled`, `RegulatingControl.targetValue`, `RegulatingControl.targetValueUnitMultiplier`, `RegulatingControl.discrete` | `RegulatingControl.targetDeadband` |

### `HvdcFamily`

Keys:

* line: `activePowerSetpoint`, `convertersMode`
* control: `VoltageRegulation.RegulationMode`, `VoltageRegulation.TargetValue`, `VoltageRegulation.isRegulating`, `localTargetQ`, `localTargetV`
* converter: `VoltageRegulation.RegulationMode`, `VoltageRegulation.TargetValue`, `VoltageRegulation.isRegulating`, `controlMode`, `localTargetQ`, `localTargetV`, `powerFactor`, `targetP`, `targetVdc`

| block | update query | CIM classes | required | optional |
| --- | --- | --- | --- | --- |
| `CS_CONVERTER` | `acDcConverters` | `CsConverter` | `CsConverter.operatingMode`, `CsConverter.pPccControl` | – |
| `VS_CONVERTER` | `acDcConverters` | `VsConverter` | `VsConverter.pPccControl`, `VsConverter.qPccControl` | `VsConverter.targetQpcc`, `VsConverter.targetUpcc` |

The query `acDcConverters` reads the setpoints of a converter with its block, and of both converters of a line: `ACDCConverter.targetPpcc`, `ACDCConverter.targetUdc`, `ACDCConverter.p`, `ACDCConverter.q`.

### `ControlAreaFamily`

Keys:

* control area: `interchangeTarget`

| block | update query | CIM classes | required | optional |
| --- | --- | --- | --- | --- |
| `CONTROL_AREA` | `controlAreas` | `ControlArea` | `ControlArea.netInterchange` | `ControlArea.pTolerance` |

### `LimitFamily`

Keys:

* voltage level: `highVoltageLimit`, `lowVoltageLimit`
* line: `b1`, `b2`, `g1`, `g2`, `r`, `x`
* boundary line: `b`, `g`, `r`, `x`
* operational limits of a branch, a leg or a boundary line: every key starting with `limits`

| block | update query | CIM classes | required | optional |
| --- | --- | --- | --- | --- |
| `CURRENT_LIMIT` | `operationalLimits` | `CurrentLimit` | `CurrentLimit.value` | – |
| `ACTIVE_POWER_LIMIT` | `operationalLimits` | `ActivePowerLimit` | `ActivePowerLimit.value` | – |
| `APPARENT_POWER_LIMIT` | `operationalLimits` | `ApparentPowerLimit` | `ApparentPowerLimit.value` | – |
| `VOLTAGE_LIMIT_VALUE` | `operationalLimits` | `VoltageLimit` | `VoltageLimit.value` | – |
| `AC_LINE_SEGMENT` | – (IIDM setters) | `ACLineSegment` | `ACLineSegment.r`, `ACLineSegment.x`, `ACLineSegment.gch`, `ACLineSegment.bch` | – |
| `SERIES_COMPENSATOR` | – (IIDM setters) | `SeriesCompensator` | `SeriesCompensator.r`, `SeriesCompensator.x` | – |
| `EQUIVALENT_BRANCH` | – (IIDM setters) | `EquivalentBranch` | `EquivalentBranch.r`, `EquivalentBranch.x`, `EquivalentBranch.r21`, `EquivalentBranch.x21` | – |
| `VOLTAGE_LEVEL` | – (IIDM setters) | `VoltageLevel` | `VoltageLevel.highVoltageLimit`, `VoltageLevel.lowVoltageLimit` | – |

## In-place import of a difference

The families of `FastRouteCapabilities`, one per block above. A family without update query is applied with IIDM setters; the variant safety says whether applying it stays inside one network variant.

| family | CIM classes | update query | profiles | variant safety |
| --- | --- | --- | --- | --- |
| `SWITCH` | `Breaker`, `Disconnector`, `GroundDisconnector`, `Jumper`, `LoadBreakSwitch`, `ProtectedSwitch`, `Switch` | `switches` | STEADY_STATE_HYPOTHESIS | SAFE |
| `TERMINAL` | `Terminal` | `terminals` | STEADY_STATE_HYPOTHESIS | NETWORK_DEPENDENT |
| `DC_TERMINAL` | `ACDCConverterDCTerminal`, `DCTerminal` | `dcTerminals` | STEADY_STATE_HYPOTHESIS | SAFE |
| `ENERGY_CONSUMER` | `ConformLoad`, `EnergyConsumer`, `NonConformLoad`, `StationSupply` | `energyConsumers` | STEADY_STATE_HYPOTHESIS | SAFE |
| `ENERGY_SOURCE` | `EnergySource` | `energySources` | STEADY_STATE_HYPOTHESIS | SAFE |
| `ASYNCHRONOUS_MACHINE` | `AsynchronousMachine` | `asynchronousMachines` | STEADY_STATE_HYPOTHESIS | SAFE |
| `SYNCHRONOUS_MACHINE` | `SynchronousMachine` | `synchronousMachinesForUpdate` | STEADY_STATE_HYPOTHESIS | NETWORK_DEPENDENT |
| `EXTERNAL_NETWORK_INJECTION` | `ExternalNetworkInjection` | `externalNetworkInjections` | STEADY_STATE_HYPOTHESIS | NETWORK_DEPENDENT |
| `EQUIVALENT_INJECTION` | `EquivalentInjection` | `equivalentInjections` | STEADY_STATE_HYPOTHESIS | NETWORK_DEPENDENT |
| `GENERATING_UNIT` | `GeneratingUnit`, `HydroGeneratingUnit`, `NuclearGeneratingUnit`, `SolarGeneratingUnit`, `ThermalGeneratingUnit`, `WindGeneratingUnit` | `generatingUnits` | STEADY_STATE_HYPOTHESIS | NETWORK_DEPENDENT |
| `STATIC_VAR_COMPENSATOR` | `StaticVarCompensator` | `staticVarCompensators` | STEADY_STATE_HYPOTHESIS | SAFE |
| `SHUNT_COMPENSATOR` | `LinearShuntCompensator`, `NonlinearShuntCompensator` | `shuntCompensators` | STEADY_STATE_HYPOTHESIS | SAFE |
| `RATIO_TAP_CHANGER` | `RatioTapChanger` | `ratioTapChangers` | STEADY_STATE_HYPOTHESIS | NETWORK_DEPENDENT |
| `PHASE_TAP_CHANGER` | `PhaseTapChangerAsymmetrical`, `PhaseTapChangerLinear`, `PhaseTapChangerNonLinear`, `PhaseTapChangerSymmetrical`, `PhaseTapChangerTabular` | `phaseTapChangers` | STEADY_STATE_HYPOTHESIS | NETWORK_DEPENDENT |
| `REGULATING_CONTROL` | `RegulatingControl`, `TapChangerControl` | `regulatingControls` | STEADY_STATE_HYPOTHESIS | NETWORK_DEPENDENT |
| `CS_CONVERTER` | `CsConverter` | `acDcConverters` | STEADY_STATE_HYPOTHESIS | UNSAFE |
| `VS_CONVERTER` | `VsConverter` | `acDcConverters` | STEADY_STATE_HYPOTHESIS | NETWORK_DEPENDENT |
| `CONTROL_AREA` | `ControlArea` | `controlAreas` | STEADY_STATE_HYPOTHESIS | SAFE |
| `CURRENT_LIMIT` | `CurrentLimit` | `operationalLimits` | EQUIPMENT, STEADY_STATE_HYPOTHESIS | UNSAFE |
| `ACTIVE_POWER_LIMIT` | `ActivePowerLimit` | `operationalLimits` | EQUIPMENT, STEADY_STATE_HYPOTHESIS | UNSAFE |
| `APPARENT_POWER_LIMIT` | `ApparentPowerLimit` | `operationalLimits` | EQUIPMENT, STEADY_STATE_HYPOTHESIS | UNSAFE |
| `VOLTAGE_LIMIT` | `VoltageLimit` | `operationalLimits` | EQUIPMENT, STEADY_STATE_HYPOTHESIS | UNSAFE |
| `AC_LINE_SEGMENT` | `ACLineSegment` | – | EQUIPMENT | UNSAFE |
| `SERIES_COMPENSATOR` | `SeriesCompensator` | – | EQUIPMENT | UNSAFE |
| `EQUIVALENT_BRANCH` | `EquivalentBranch` | – | EQUIPMENT | UNSAFE |
| `VOLTAGE_LEVEL` | `VoltageLevel` | – | EQUIPMENT | UNSAFE |

(cgmes-mapping-refusals)=
## Refusals

A change the mapping cannot state is refused with its cause and a remedy. A refusal that protects a receiver of changes (partial SSH, difference model, database) is not honoured by a full steady state hypothesis, which states the whole state.

| rule | refusal | honoured by | remedy |
| --- | --- | --- | --- |
| `echo-3` | `ECHO_3` | receivers of changes | give the equipment its VoltageRegulation before recording the change set, and change it through the VoltageRegulation and the local target setters |
| `local-target` | `LOCAL_TARGET` | receivers of changes | export the equipment model with the change, or change the target of the regulation only (VoltageRegulation.setTargetValue; Generator.setTargetV(v, local) reports a local target in any case) |
| `own-terminal` | `OWN_TERMINAL` | receivers of changes | regulate the converter's own terminal (VoltageRegulation.setTerminal(converter terminal, target)), or none |
| `terminal-eq` | `TERMINAL_EQ` | receivers of changes | export the equipment model with the change (a full CGMES export), or keep the regulating terminal the import set |
| `mode-eq` | `MODE_EQ` | receivers of changes | export the equipment model with the change (a full CGMES export), or keep the mode the import set |
| `slope-no-property` | `SLOPE_NO_PROPERTY` | receivers of changes | leave the slope as the import set it |
| `deadband-not-read` | `DEADBAND_NOT_READ` | receivers of changes | leave the deadband of this regulation as the import set it |
| `import-gives-regulation` | `IMPORT_GIVES_REGULATION` | receivers of changes | give it a VoltageRegulation (not regulating, if it must not regulate) before recording the change set |
| `vsc-no-control-flag` | `VSC_NO_CONTROL_FLAG` | receivers of changes | let it regulate, in REACTIVE_POWER mode with its reactive power target for a converter that must not regulate voltage |
| `no-control` | `NO_CONTROL` | receivers of changes | keep its regulation as the import left it; to change it, give it a VoltageRegulation (not regulating) first and export the full model (EQ and SSH): a full export writes a RegulatingControl only for equipment that has a VoltageRegulation |
| `no-tap-changer-control` | `NO_TAP_CHANGER_CONTROL` | receivers of changes | keep its regulation as the equipment model defines it, or export the equipment model with the change |
| `equivalent-shunt` | `EQUIVALENT_SHUNT` | every export | keep it as the equipment model defines it, or export the equipment model with the change |
| `no-mode` | `NO_MODE` | every export | set the mode of its VoltageRegulation in this variant |
| `cgmes-mode` | `CGMES_MODE` | receivers of changes | keep the mode the import set, or export the equipment model with the change |
| `rtc-reactive-power` | `RTC_REACTIVE_POWER` | receivers of changes | export the full steady state hypothesis instead |
| `tap-changers-disagree` | `TAP_CHANGERS_DISAGREE` | receivers of changes | switch the regulation of every tap changer of the control together |
| `no-regulation-capability` | `NO_REGULATION_CAPABILITY` | receivers of changes | keep its regulation off, or export the equipment model with a regulation capability |
| `ptc-no-terminal` | `PTC_NO_TERMINAL` | receivers of changes | give it a regulation terminal before recording the change set, or keep its regulation as the import left it |
| `undescribed-user` | `UNDESCRIBED_USER` | receivers of changes | make every user of the shared control describable |

Attributes refused by name, because they are equipment data or have no CGMES property: `VoltageRegulation.RegulationMode`, `VoltageRegulation.Slope`, `VoltageRegulation.Terminal`.

<!-- END GENERATED MAPPING -->
