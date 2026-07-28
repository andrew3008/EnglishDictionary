# JMX management contract baseline (S-03 / E1)

## Result

The baseline was captured on commit
`62158867d9309eab29ce8de3f05db8e3b9300b1b` with Java `21.0.6` by registering
the production MBeans through `PlatformTracingJmxRegistrar` on the actual
`ManagementFactory.getPlatformMBeanServer()` and reading `MBeanInfo`.

| Metric | Live MBeanInfo | Council method inventory | Difference |
|---|---:|---:|---:|
| ObjectNames | 8 | 8 | 0 |
| Management members | 63 | 71 | -8 |
| Mutating members | 16 | 16 | 0 |
| Read-only members | 47 | 55 | -8 |

The difference is representational, not a missing contract. The council count
treated every Java getter and setter as a separate method. Standard MBean
introspection folds eight getter/setter pairs into eight writable attributes.
Consequently both total and read-only method counts decrease by eight, while
the semantic mutation count remains 16.

## ObjectNames

1. `space.br1440.platform.tracing:name=PlatformControlProtocol,type=Control`
2. `space.br1440.platform.tracing:name=PlatformDiagnosticsControl,type=Diagnostics`
3. `space.br1440.platform.tracing:name=PlatformExportControl,type=Export`
4. `space.br1440.platform.tracing:name=PlatformExtension,type=Readiness`
5. `space.br1440.platform.tracing:name=PlatformProcessorMetricsControl,type=Metrics`
6. `space.br1440.platform.tracing:name=PlatformSamplingControl,type=Sampling`
7. `space.br1440.platform.tracing:name=PlatformScrubbingControl,type=Scrubbing`
8. `space.br1440.platform.tracing:name=PlatformValidationControl,type=Validation`

## Current mutating surface

Writable attributes:

- Diagnostics: `PlatformLogLevel`, `PropagationEnabled`
- Export: `ExportEnabled`
- Sampling: `DropPathPrefixes`, `ForceRecordValues`, `RouteRatios`,
  `SamplerEnabled`, `SamplingRatio`

Mutating operations:

- Control: `applyPolicy(CompositeData)`
- Sampling: both `updateSamplingPolicy(...)` overloads and
  `resetSamplerCounters()`
- Scrubbing: both `updateScrubbingPolicy(...)` overloads
- Validation: both `updateValidationPolicy(...)` overloads

Only `applyPolicy` passes through `RuntimeControlMutationPolicy`. The other
15 mutations remain direct platform-server JMX writes.

## Consumer and duplication findings

The exhaustive preliminary inventory is
[`jmx-consumer-inventory.md`](jmx-consumer-inventory.md). Members without a
named repository consumer are marked `NO_NAMED_CONSUMER_FOUND`, not presumed
unused.

The Spring side duplicates seven of eight Agent ObjectNames. It intentionally
does not declare `CONTROL_PROTOCOL_OBJECT_NAME_STR`. The consistency test now
compares the complete reflected sets, reports missing/extra keys, validates all
shared values, and pins this one known omission.

## Commands

Verify without modifying the baseline:

```powershell
.\gradlew.bat jmxManagementContractVerify --no-daemon
```

Regenerate explicitly after an approved contract change:

```powershell
.\gradlew.bat jmxManagementContractVerify -PupdateJmxContractBaseline --no-daemon
```

Normal test execution never regenerates the file.

## Scope boundary

Capturing the baseline does not approve removal of any member.
It only closes the evidence prerequisite for E7.

The baseline does not approve production implementation, private-server
cutover, legacy deletion, mutation relocation, or public API changes.
