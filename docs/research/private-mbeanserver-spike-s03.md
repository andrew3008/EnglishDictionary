# S-03: JMX contract baseline and private process-local MBeanServer spike

## 1. Scope and verdict

S-03 was executed as test/evidence work only. No production source, public API,
MBean interface, ObjectName, mutation gate, tracing property, capability enum
or architecture plan was changed.

**Verdict: PASS. Gate E1 CLOSED. Gate E2 CLOSED.**

This verdict approves neither production implementation nor legacy deletion.

## 2. Starting repository state

- Branch: `refactor/cursor-rules`
- Requested start: `5abd5d1`
- Actual pre-S-03 HEAD:
  `62158867d9309eab29ce8de3f05db8e3b9300b1b`
- Difference: `5abd5d1` is present as the S-02 parent; `6215886` is a later
  analysis commit.
- Worktree before S-03: clean
- Java: Oracle JDK `21.0.6`
- Gradle: `8.14`
- Part A commit: `939e95c test(jmx): capture management contract baseline`

## 3. Part A: JMX baseline

`DomainMBeanJmxComplianceTest` now registers the production MBeans through the
actual `PlatformTracingJmxRegistrar` on
`ManagementFactory.getPlatformMBeanServer()`, captures live `MBeanInfo`, and
compares it with a committed deterministic JSON snapshot.

Normal execution is read-only. Regeneration requires:

```powershell
.\gradlew.bat jmxManagementContractVerify -PupdateJmxContractBaseline --no-daemon
```

## 4. Exact current ObjectNames/member counts

- ObjectNames: 8
- Live `MBeanInfo` members (attributes + operations): 63
- Mutating: 16
- Read-only: 47
- Notifications: 0

The council's 71/16/55 inventory counted Java interface methods. Live Standard
MBean introspection folds eight getter/setter pairs into eight writable
attributes. This accounts exactly for the -8 total and -8 read-only
difference; no ObjectName or callable semantic member is missing.

The complete names and metadata are stored in
`platform-tracing-otel-javaagent-extension/src/test/resources/`
`jmx-management-contract-baseline.json`.

## 5. Current mutating surface

Eight writable attributes:

- Diagnostics: `PlatformLogLevel`, `PropagationEnabled`
- Export: `ExportEnabled`
- Sampling: `DropPathPrefixes`, `ForceRecordValues`, `RouteRatios`,
  `SamplerEnabled`, `SamplingRatio`

Eight mutating operations:

- Control: `applyPolicy(CompositeData)`
- Sampling: two `updateSamplingPolicy` overloads, `resetSamplerCounters`
- Scrubbing: two `updateScrubbingPolicy` overloads
- Validation: two `updateValidationPolicy` overloads

Only `applyPolicy` is governed by `RuntimeControlMutationPolicy`. The other 15
are direct platform-server mutations.

## 6. Named consumer inventory

The per-member inventory is in
`docs/research/evidence/jmx-contract/jmx-consumer-inventory.md`.
It covers production Java, Spring health, `PlatformTracingJmxClient`, Actuator
read models, tests/E2E/performance fixtures and documentation. Absence is
recorded as `NO_NAMED_CONSUMER_FOUND`, never inferred as permission to remove.

## 7. Baseline contract-test design

The snapshot includes:

- canonical ObjectName and MBean class;
- deterministic descriptor fields;
- attribute name, description, Java/OpenType descriptor, read/write/is flags;
- operation name, argument names/types, return type, descriptor and impact;
- notification names and sorted notification types.

ObjectNames, attributes, signatures, descriptors and notifications are sorted.
The test emits the first differing JSON line and the explicit regeneration
command.

The Spring/Agent consistency test reflects both constant registries, validates
all shared values, reports missing/extra keys and pins the confirmed state:
Spring duplicates seven of eight names and omits Control.

## 8. Part B: private-server mechanism

A dedicated test-only Agent extension loaded by the real controlled
OpenTelemetry Java Agent creates a factory-retained private MBeanServer with
`MBeanServerFactory.createMBeanServer`.

It registers:

- a marker with protocol, version, runtime id, writer name, lifecycle and
  marker signature;
- a strict writer receiving/returning `CompositeData`;
- a test-only read-only projection on the platform MBeanServer.

No platform DTO crosses the boundary. The fixture is not packaged into any
production artifact.

## 9. Discovery algorithm

Exact App-CL algorithm:

1. Call `MBeanServerFactory.findMBeanServer(null)`.
2. For every retained server, test exact marker ObjectName registration.
3. Read marker attributes through `MBeanServer`.
4. Require exact protocol id/version/signature, nonblank runtime instance id,
   exact writer ObjectName, lifecycle `WRITER_READY`, and registered writer.
5. Require exactly one valid match.
6. Zero or multiple matches is fatal; no arbitrary selection occurs.

`findMBeanServer(defaultDomain)` is not used. The process test confirms it
matches `MBeanServerId`, not default domain.

## 10. Lifecycle algorithm

Initialization order:

1. create private server;
2. register marker;
3. initialize codec;
4. register writer;
5. register/readiness-test state store and read projection;
6. publish lifecycle `WRITER_READY`.

Shutdown order:

1. unregister writer;
2. unregister marker;
3. unregister test read projection and any remaining private fixture MBeans;
4. call `MBeanServerFactory.releaseMBeanServer`;
5. clear the owner reference;
6. verify factory discovery no longer returns the server;
7. independently verify marker and writer are unregistered.

The experiment confirms that `releaseMBeanServer` alone neither unregisters
MBeans nor destroys the server.

## 11. Cross-classloader wire proof

- Agent codec/writer:
  `io.opentelemetry.javaagent.tooling.ExtensionClassLoader`
- Application codec/client: JDK `AppClassLoader`
- `CompositeData` / OpenType classes: bootstrap loader
- Shared platform control DTO: none

The App side encoded request V1, the Agent side decoded/applied/encoded response
V1, and App decoded `APPLIED` with exact `CompositeType` equality. Malformed
types were rejected without state mutation.

## 12. M1-M12 result matrix

| Property | Result | Executable evidence |
|---|---|---|
| M1 writer absent from platform server | PASS | platform registration assertion |
| M2 remote connector isolation | PASS | separate RMI client; `InstanceNotFoundException` |
| M3 exactly one marker | PASS | factory enumeration and strict locator |
| M4 cross-classloader Open Types | PASS | controlled-Agent fork |
| M5 correct shutdown | PASS | unregister/release/discovery assertions |
| M6 two Spring contexts | PASS | two `GenericApplicationContext` instances |
| M7 Agent-only | PASS | three JDK-only classpath launches; Spring absent |
| M8 mode disabled | PASS | zero locator/discovery/invocation counters |
| M9 management-agent activation | PASS | `jcmd ManagementAgent.start_local` + attach client |
| M10 repeated lifecycle | PASS | five in-process cycles + three Agent forks |
| M11 capability ordering | PASS | ordered event assertions |
| M12 target platform projection read-only | PASS | zero writable attributes/operations |

## 13. N1-N7 adversarial result matrix

| Scenario | Result | Evidence |
|---|---|---|
| N1 duplicate initializer | PASS | existing owner returned; no second server |
| N2 invalid/duplicate marker | PASS | invalid ignored; two valid is fatal |
| N3 same writer name on platform | PASS | marker-selected private server |
| N4 wrong protocol | PASS | `REJECTED`, no mutation |
| N5 wrong runtime instance | PASS | `STALE`, no mutation |
| N6 malformed CompositeData | PASS | wrong/missing/unknown/null/type/size rejected |
| N7 current platform write remotely invoked | PASS | `resetSamplerCounters` invoked remotely |

## 14. Remote JMX isolation evidence

An RMI connector bound and advertised only on `127.0.0.1` exposed the platform
server. A separate client process observed the read-only projection, could not
see the private writer, and received `InstanceNotFoundException` on invocation.

The same assertions passed after activating the standard local management
agent with `jcmd <pid> ManagementAgent.start_local` and resolving its connector
through the Attach API.

## 15. Current platform-writer exposure evidence

The separate remote client successfully invoked the current production
platform-server operation `resetSamplerCounters`.

**CURRENT_SECURITY_RISK_CONFIRMED**

This mutation ran only in an isolated child JVM on loopback.

## 16. Generated artifacts

Stable text evidence:

- `execution-environment.txt`
- `jmx-current-surface-summary.txt`
- `remote-jmx-query-output.txt`
- `private-server-discovery-output.txt`
- `private-server-lifecycle-output.txt`
- `cross-classloader-roundtrip.txt`
- `m1-m12-results.txt`
- `n1-n7-results.txt`
- `gradle-console.txt`
- `disabled-mode-output.txt`

No generated HTML or binary dump is committed.

## 17. What the spike proves

- Factory-retained private MBeanServer discovery works across the real
  Agent/App classloader topology.
- A marker-qualified exactly-one locator safely ignores the platform server.
- JDK Open Types cross the boundary without shared DTO identity.
- Platform remote connectors and the local management agent do not expose the
  private writer.
- Correct unregister-then-release lifecycle avoids factory/server leaks.
- Duplicate ownership and marker ambiguity can fail deterministically.

## 18. What it does not prove

- Final Category A policy schema or final aggregate writer semantics.
- Production wiring, support capability, authentication or authorization.
- Legacy member disposition or removal safety.
- Multi-process coordination beyond one JVM.
- Behavior on every supported JDK/vendor/container platform.
- Production implementation GO or rollout readiness.

## 19. Gate E1 status

**CLOSED.** The complete current live contract is versioned, deterministic,
consumer-inventoried and guarded by an explicit verification task.

Capturing the baseline does not approve removal of any member.
It only closes the evidence prerequisite for E7.

## 20. Gate E2 status

**CLOSED.** M1-M12 and N1-N7 executed and passed, including M9 attach.

## 21. Redesign conditions

None of the mandatory redesign triggers occurred:

- private writer was not remotely visible;
- exactly-one discovery was enforced;
- duplicate writer ownership was prevented;
- no shared application DTO was required;
- disabled mode performed no discovery/mutation;
- shutdown removed registrations and factory references;
- management-agent activation did not expose the writer.

**Redesign required: NO.**

## 22. Recommended next spike

**S-04.** E1/E2 evidence now permits the next repository spike, but no
production implementation, state refactoring, property migration or legacy
deletion may begin without its own gate.
