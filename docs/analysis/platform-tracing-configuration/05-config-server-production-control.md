# Config Server Production Control
## Corrected Repository-Grounded Architecture Decision

## Review status

| Field | Value |
|---|---|
| Report status | **PASS_WITH_CRITICAL_CORRECTIONS** |
| Config Server target | **Accepted conditionally as production desired-state authority** |
| Production dependency decision | **Still requires platform governance approval** |
| Refresh delivery mechanism | **Unresolved operational decision** |
| Implementation GO | **NOT GRANTED** |
| Legacy mutation deletion | **BLOCKED** |
| Intended handoff | Prompt 6 — Authority Modes and Namespace Migration |

The original report correctly accepted the Config Server direction and reused Step 0B evidence. It nevertheless made several premature or unsafe decisions:

- it declared Boot 3.5.6 mandatory without a compatibility comparison;
- it prescribed Gradle syntax without validating the repository's actual BOM conventions;
- it used `ConfigServerConfigDataLoader` as an activation type, coupling production code to an implementation class;
- it allowed the Spring bridge to "publish" an Agent readiness capability;
- it proposed `AtomicBoolean` coalescing that can lose the final refresh;
- it did not decide who triggers refresh in production;
- it used current `TracingProperties` field defaults as canonical defaults for the new namespace;
- it treated a missing Config Server marker as a bean-registration concern rather than a runtime fail-closed condition;
- it blurred Config Server outage, refresh failure and `STALE` apply outcome;
- it called unresolved governance items "resolved."

This corrected report preserves the viable design and restores the unresolved gates.

---

# 1. Binding architecture direction

## Decision

Config Server is accepted as the target production **desired-state authority** for tracing runtime policy, subject to:

1. production dependency/BOM approval;
2. a supported refresh-delivery mechanism;
3. one aggregate Agent-side apply path;
4. one accepted runtime-policy state;
5. complete authority and namespace rules;
6. readiness and drift semantics;
7. security and operational testing.

Config Server does not own:

- Java Agent bootstrap;
- structural pipeline construction;
- atomicity;
- state versioning;
- JMX transport;
- runtime policy validation.

The target flow is:

```text
Spring Cloud Config Client
    -> Spring Environment
    -> provenance-aware full desired-state build
    -> internal Open-Type snapshot
    -> one Agent aggregate apply operation
    -> one accepted-state CAS
```

---

# 2. Production dependency decision

## 2.1 Dependency roles

Use three distinct dependency concepts.

### Version alignment

The platform BOM may align a selected Spring Cloud release train after governance approval.

This manages versions; it does not put Config Client on every service classpath.

### Autoconfiguration compilation

`platform-tracing-spring-boot-autoconfigure` may use compile-only dependencies for:

- Spring Cloud Context event APIs;
- Config Client public configuration APIs needed by conditional code.

Do not prescribe `spring-cloud-starter-config` as the compile-only artifact without validating the current build conventions. Prefer the smallest compile-time artifacts.

### Consumer runtime dependency

A service using:

```text
platform.tracing.configuration.authority=CONFIG_SERVER
```

must have the standard Spring Cloud Config Client runtime dependency, normally through the company's approved application dependency stack.

The tracing servlet/reactive starters should not silently force Config Client onto services using HELM or DISABLED mode unless the architecture committee explicitly accepts that footprint.

## 2.2 Recommended dependency policy

```text
HELM / DISABLED:
    Config Client not required.

CONFIG_SERVER:
    standard Spring Cloud Config Client required and verified at startup.
```

The bridge auto-configuration is conditional on:

- `authority=CONFIG_SERVER`;
- required public Config Client/Cloud Context types present.

Do not condition on `ConfigServerConfigDataLoader`, because it is a reference-implementation class rather than the desired public activation contract.

## 2.3 Boot 3.5.5 versus 3.5.6

The Step 0B evidence proves the selected stack:

- Boot 3.5.6;
- Cloud BOM 2025.0.0;
- Config Client/Context 4.3.0.

It does not prove that Boot 3.5.5 is incompatible.

Therefore:

```text
Boot 3.5.6 is the tested target, not automatically a mandatory upgrade.
```

Before the production decision:

1. run dependency resolution and smoke tests on the current Boot 3.5.5 baseline with the selected Cloud train;
2. run the same tests on Boot 3.5.6;
3. choose the corporate supported patch level;
4. pin exact resolved Cloud project versions;
5. update `docs/SUPPORTED.md` and the BOM together.

If corporate governance already requires Boot 3.5.6, use it. The tracing plan must not manufacture that requirement from the isolated spike alone.

## 2.4 Build-governance gates

Required:

- dependency convergence;
- starter dependency smoke;
- HELM mode without Config Client on classpath;
- CONFIG_SERVER mode with Config Client;
- configuration-cache checks;
- no transitive Agent-extension dependency in starters;
- published BOM consumer test.

---

# 3. Bridge activation and lifecycle

## 3.1 Bridge responsibilities

`TracingConfigServerBridge` is an application-side adapter.

It is responsible for:

- reading Config Server-backed desired state from the current `Environment`;
- classifying per-key provenance;
- constructing a complete internal desired-state snapshot;
- comparing structural desired state with Agent bootstrap effective state;
- sending one Open-Type apply request;
- serializing/coalescing refresh processing;
- recording application-side delivery diagnostics;
- participating in initial readiness gating.

It is not responsible for:

- making HTTP calls to Config Server;
- polling Config Server;
- creating a second Config Client;
- constructing the Agent pipeline;
- publishing Agent capabilities;
- mutating domain MBeans;
- implementing runtime truth.

## 3.2 Initial lifecycle

Config Data import occurs before normal bean creation.

The recommended startup sequence is:

```text
1. Spring Config Data import succeeds through standard Config Client.
2. Application context creates the conditional bridge.
3. Existing AgentExtensionObserver obtains the Agent readiness descriptor.
4. Agent descriptor must advertise aggregate snapshot protocol support.
5. Bridge validates remote authority and provenance.
6. Bridge builds the initial complete desired-state snapshot.
7. Bridge sends one aggregate Open-Type apply.
8. Runtime outcome must be APPLIED or UNCHANGED.
9. Initial structural mismatch must be absent.
10. Tracing configuration readiness becomes satisfied.
```

Do not claim that `SmartLifecycle` alone guarantees "before first traffic."

Use an explicit bounded startup/readiness coordinator integrated with:

- the existing Agent readiness observer;
- Spring startup failure handling;
- the tracing health/readiness component.

The exact Spring extension point—runner, lifecycle callback or dedicated initialization bean—must be selected through integration tests. The architectural contract is the readiness gate, not a specific interface.

## 3.3 Capability ownership

The Agent extension publishes:

```text
CONFIG_SNAPSHOT_V1
```

when:

- the aggregate MBean operation is registered;
- the version-1 codec is installed;
- the apply handler is ready.

The Spring bridge only reads and validates this capability.

It must never publish or satisfy an Agent capability.

Initial apply success is a separate condition from protocol support.

---

# 4. Provenance and authority algorithm

Step 0B evidence is binding for the modern Config Data path.

## 4.1 Required per-key views

For every managed key resolve:

```text
effective value
effective winning source
remote desired value
remote desired source
local/system override
conflict
```

A winning-value lookup alone is insufficient.

## 4.2 Trust signals

Use a multi-signal classifier:

- concrete `configClient` marker source for successful fetch metadata;
- modern `configserver:*` source structure;
- public `OriginLookup` when available;
- `OriginTrackedMapPropertySource` as supporting container information.

Do not:

- import package-private `ConfigServiceOrigin`;
- rely only on source-name matching;
- accept legacy `configService` bootstrap;
- read canonical revision through `Environment.getProperty`.

## 4.3 Revision

Read directly from the concrete marker source:

```java
PropertySource<?> configClient =
    environment.getPropertySources().get("configClient");
```

Then read:

- `config.client.version`;
- `config.client.state`.

Semantics:

- marker present, version absent is valid;
- accepted source revision is optional;
- no synthetic revision from source names;
- local/system properties cannot spoof the directly read marker value.

## 4.4 Policy overrides in CONFIG_SERVER mode

For `platform.tracing.policy.*`:

- remote Config Server desired state is authoritative;
- local YAML override is forbidden;
- command-line/system/env override is forbidden unless the architecture committee explicitly defines an emergency override contract;
- ambiguity is fail-closed.

Agent structural bootstrap keys are different:

- effective bootstrap values may come from env/`-D`;
- Config Server may carry desired structural values;
- a difference produces `restartRequired`, not a policy-provenance violation.

---

# 5. Full snapshot and missing-key semantics

## 5.1 No patch application

`EnvironmentChangeEvent.getKeys()` is a trigger/evidence set, not an apply payload.

On every processing cycle:

- reread the current `Environment`;
- resolve every managed desired key;
- construct the complete snapshot;
- validate the complete snapshot.

## 5.2 Do not use current `TracingProperties` defaults as the automatic target contract

The target namespace and ownership differ from current `TracingProperties`.

Using current Spring field defaults as canonical defaults would preserve duplicate ownership and could diverge from Agent bootstrap defaults.

## 5.3 Recommended Config Server contract

For authoritative CONFIG_SERVER mode, require a **complete managed runtime-policy manifest**.

Returning to a default is done by publishing the explicit documented default value in Config Server, not by deleting the key.

Therefore:

- missing required managed Category A key on initial load -> reject and block readiness;
- missing required managed Category A key after refresh -> reject the candidate and retain current accepted policy;
- no silent inheritance from the previous accepted snapshot;
- no implicit reset using a Spring-local default.

This is simpler and safer than maintaining two independent default catalogs.

## 5.4 Alternative only if committee requires delete-to-default

If key deletion must reset to default, define one canonical target default catalog first and prove both Spring and Agent codecs use equivalent values.

Do not implement delete-to-default from ad hoc `TracingProperties` defaults.

---

# 6. Refresh delivery mechanism

## 6.1 Critical unresolved decision

Spring Cloud Config Client does not continuously push changes by itself.

`EnvironmentChangeEvent` is sufficient as the bridge's input event, but it does not answer:

```text
Who causes the environment refresh in production?
```

The production architecture must select and test one supported delivery mechanism.

Candidates:

1. Spring Cloud Bus plus Config Monitor/webhook.
2. A secured operational invocation of `/actuator/refresh`.
3. A corporate platform refresh orchestrator using standard Spring Cloud APIs.
4. Another approved Spring Cloud mechanism.

The tracing library must not build a custom polling client.

## 6.2 Bridge remains trigger-agnostic

The bridge listens to the standard environment-change event.

It should not:

- invoke refresh itself;
- expose a tracing-specific refresh HTTP endpoint;
- poll Config Server;
- depend on one SRE transport unless required.

## 6.3 GO impact

Config Server initial-load support can be designed before the refresh-delivery choice.

Production runtime mutation cannot be declared operationally complete until the refresh-delivery mechanism is approved and E2E tested.

---

# 7. Correct single-flight and coalescing design

## 7.1 Reject event-dropping `AtomicBoolean`

This pattern is unsafe:

```text
if worker already running:
    drop event
```

If a final event arrives during processing and no later event arrives, the bridge can finish with stale desired state.

## 7.2 Required semantics

Use a serialized worker with a dirty/generation mechanism.

Conceptual algorithm:

```text
on EnvironmentChangeEvent:
    increment requestedGeneration
    schedule worker if not already scheduled

worker:
    loop:
        observedGeneration = requestedGeneration
        rebuild full snapshot from current Environment
        apply or record outcome
        if requestedGeneration == observedGeneration:
            stop
        otherwise:
            repeat from latest Environment
```

Equivalent implementations:

- single-thread executor plus dirty flag;
- serial queue with coalescing;
- generation counter and one active worker.

Requirements:

- no unbounded parallel apply;
- no loss of the latest state;
- intermediate revisions may be coalesced;
- full state always reread;
- bounded stale retry;
- shutdown-safe executor lifecycle;
- metrics for coalesced events and apply attempts.

## 7.3 Initial apply

Initial apply is synchronous with the tracing readiness gate and is not submitted as a best-effort background refresh.

---

# 8. Apply and stale semantics

The Agent apply handler owns optimistic concurrency.

## 8.1 STALE

A concurrent accepted-state change or expected-base mismatch produces:

```text
STALE
```

not `FAILED`.

The bridge may:

1. read the latest accepted base;
2. rebuild from the latest current `Environment`;
3. retry once.

No unbounded retry loop.

## 8.2 FAILED

Reserve `FAILED` for:

- codec/internal errors;
- unexpected handler exceptions;
- inability to complete a commit after internal invariant failure.

## 8.3 Revision-only refresh

If normalized runtime policy and structural desired state are unchanged:

```text
runtimeOutcome=UNCHANGED
```

Accepted sequence does not advance.

Last-observed revision may update in diagnostics.

---

# 9. Readiness and structural drift

## 9.1 Initial Config Server load

Readiness is blocked when:

- non-optional Config Server import fails;
- marker/provenance is invalid;
- protocol capability is unsupported;
- initial policy is rejected/failed/stale after bounded retry;
- initial structural desired state differs from bootstrap effective state;
- complete required policy manifest is missing.

No silent fallback to HELM state in CONFIG_SERVER authority.

## 9.2 Runtime refresh

When runtime policy applies successfully but structural drift exists:

```text
runtimeOutcome=APPLIED or UNCHANGED
restartRequired=true
```

The existing pipeline remains active.

Recommended default:

```text
service remains READY
```

Expose:

- restart-required health detail;
- metric;
- JMX/Actuator read diagnostics;
- affected keys;
- desired/effective fingerprints.

Do not automatically mark all replicas NOT_READY.

If the corporate health model already supports a custom `DEGRADED` status and SRE explicitly wants it, map the diagnostic there. Do not introduce a new health status solely from this tracing plan.

---

# 10. Failure behavior

| Situation | Runtime state | Readiness/health | Required behavior |
|---|---|---|---|
| Config Server unavailable during initial import | No Config Server apply | Startup fails or remains not ready | Standard non-optional import/fail-fast |
| Config Server unavailable during a later refresh request | Current accepted policy retained | Remains ready | Record refresh failure if observable; no empty snapshot |
| No refresh event because remote server is unavailable | Current accepted policy retained | Remains ready | Staleness age/revision diagnostics only |
| Marker missing in CONFIG_SERVER mode | Current state not accepted as remote authority | Initial: not ready; runtime: reject attempt | Fail closed |
| Version absent but marker valid | Apply permitted | Normal | Accepted revision optional |
| Local override of runtime policy | Candidate rejected | Initial: not ready; runtime: current state retained | Report source conflict |
| Initial structural mismatch | No ready state | Not ready | Require rollout/bootstrap alignment |
| Runtime structural mismatch | Runtime Category A may apply | Ready with diagnostic | `restartRequired=true` |
| Invalid policy candidate | Current state retained | Initial: not ready; runtime: ready | `REJECTED` |
| Concurrent state change | Current/newer accepted state retained | Unchanged | `STALE`, one bounded retry |
| Internal Agent apply error | Current accepted state retained | Initial: not ready; runtime health diagnostic | `FAILED` |
| Legacy bootstrap `configService` detected | Unsupported | Startup failure in CONFIG_SERVER mode | Migration message |

Do not label Config Server transport failure as an apply `STALE` outcome. These are different failure domains.

---

# 11. Repository changes

## 11.1 `platform-tracing-spring-boot-autoconfigure`

Potential target components after GO:

| Component | Role |
|---|---|
| `TracingConfigServerAutoConfiguration` | Conditional bean wiring |
| `TracingConfigServerBridge` | Initial delivery and event consumer |
| `TracingConfigurationPropertySourceClassifier` | Per-key provenance |
| `TracingDesiredStateSnapshotBuilder` | Complete desired-state construction |
| application-side Open-Type codec | Internal wire adapter |
| tracing configuration readiness state | Initial-apply gate |
| refresh worker/coalescer | Serialized latest-state processing |

Disposition after proven cutover:

| Existing component | Decision |
|---|---|
| `TracingRefreshScopeAutoConfiguration` | DELETE/REPLACE |
| `RuntimeConfigApplier` | DELETE |
| per-domain JMX mutators | DELETE |
| Actuator HTTP mutation operations | DELETE |
| read-only diagnostics endpoint | RETAIN/REWRITE |

Do not describe the classifier as replacing "inline logic in RuntimeConfigApplier"; the current applier does not implement Config Server provenance.

## 11.2 `platform-tracing-otel-javaagent-extension`

After GO:

| Component | Role |
|---|---|
| aggregate accepted policy state | Runtime truth |
| aggregate apply handler | Validate and commit |
| Agent Open-Type codec | Decode/encode |
| aggregate MBean operation | Single write boundary |
| capability publication | Advertise installed protocol support |
| Agent apply diagnostics/audit | Operational evidence |

After cutover:

- remove domain write operations;
- remove partial old protocol semantics;
- remove independent mutable state owners;
- preserve approved read contracts and ObjectNames.

## 11.3 BOM/build configuration

Do not commit a specific Gradle snippet until current `java-platform`/BOM conventions are inspected.

The plan must specify outcomes:

- exact Spring Boot version;
- exact Spring Cloud train;
- exact resolved Config Client/Context versions;
- managed, not duplicated, constraints;
- optional runtime presence;
- consumer smoke tests.

---

# 12. Required tests

## 12.1 Dependency/classpath

- HELM mode without Config Client.
- CONFIG_SERVER mode without Config Client -> clear startup failure.
- CONFIG_SERVER mode with approved client.
- starter dependency smoke.
- published BOM consumer.
- exact Boot/Cloud compatibility matrix.

## 12.2 Initial lifecycle

- import success + capability + APPLIED -> ready;
- import success + UNCHANGED -> ready;
- marker missing -> not ready;
- protocol unsupported -> not ready;
- structural mismatch -> not ready;
- missing complete policy key -> not ready;
- Agent readiness timeout -> not ready/fail startup.

## 12.3 Refresh/coalescing

- event sees updated environment;
- two events during one apply cause one rerun;
- latest event is never lost;
- event storm coalesces;
- revision-only refresh -> UNCHANGED;
- key removal -> rejected under complete-manifest contract;
- executor shutdown/cleanup;
- bounded stale retry.

## 12.4 Provenance

Retain T1–T10 and add production bridge integration assertions.

## 12.5 Failure

- Config Server unavailable at initial import;
- refresh invocation failure;
- no event on failed remote reload;
- local/system override;
- version absent;
- legacy bootstrap;
- invalid candidate;
- Agent apply failure.

## 12.6 Performance

- M10/P5 through the production Config Server flow;
- M10c/M10d refresh storm;
- bridge worker overhead;
- no hot-path impact from event processing;
- bounded audit/diagnostics under storm.

---

# 13. Conditions for abandoning runtime Config Server control

Use rollout-only production configuration if one or more binding conditions are met:

1. SRE withdraws the requirement for runtime sampling/policy changes.
2. No supported refresh-delivery mechanism is approved.
3. Config Server bridge cannot meet M10/P5 budgets.
4. Authority/provenance rules cause unacceptable availability failures in real deployment.
5. Structural desired/effective drift is routinely unmanaged.
6. Production dependency governance rejects the required Cloud stack.
7. The final runtime-safe Category A is too small to justify the bridge.
8. Security review rejects the control surface.

A missing optional `config.client.version` alone is not a reason to abandon Config Server.

---

# 14. Corrected decision table

| Decision | Status |
|---|---|
| Config Server as target desired-state authority | ACCEPTED CONDITIONALLY |
| Standard Config Client, no custom HTTP client | ACCEPTED |
| Bridge replaces RefreshScope mutation path | ACCEPTED after cutover |
| EnvironmentChangeEvent as bridge trigger | ACCEPTED |
| Production refresh delivery mechanism | UNRESOLVED |
| Complete runtime-policy manifest | RECOMMENDED |
| Delete-to-default semantics | NOT APPROVED without canonical catalog |
| Boot 3.5.6 mandatory | NOT YET PROVEN |
| Cloud BOM 2025.0.0 production adoption | GOVERNANCE GATE |
| Config Client transitively in all starters | NOT RECOMMENDED by default |
| Event-dropping AtomicBoolean coalescing | REJECTED |
| Generation/dirty serialized coalescing | ACCEPTED |
| Bridge publishes Agent capability | REJECTED |
| Agent publishes protocol capability | ACCEPTED |
| Initial structural drift | BLOCK READINESS |
| Runtime structural drift | KEEP READY + DIAGNOSTIC by default |
| Config Server outage after startup | KEEP ACCEPTED POLICY |
| Legacy bootstrap | UNSUPPORTED |
| Implementation GO | NOT GRANTED |

---

# 15. Handoff contract for Prompt 6

Prompt 6 must treat these findings as accepted:

```text
1. Config Server is conditionally accepted as production desired-state authority.
2. It does not provide atomicity; the Agent aggregate state does.
3. Standard Config Client is used; no custom Config Server HTTP client or polling client.
4. The tracing bridge consumes EnvironmentChangeEvent but does not trigger refresh itself.
5. A production refresh-delivery mechanism is still an unresolved operational decision.
6. Config Client should not be forced transitively into HELM/DISABLED services by default.
7. Boot 3.5.6 is the tested target, not automatically mandatory without governance/compatibility evidence.
8. Bridge activation uses authority plus required public framework types; not ConfigServerConfigDataLoader.
9. The Agent publishes CONFIG_SNAPSHOT_V1 when codec/handler support is installed.
10. Initial apply success is separate from capability support.
11. The bridge never publishes Agent capabilities.
12. Effective and remote desired values are resolved separately.
13. Revision is read directly from the configClient marker source and is optional.
14. CONFIG_SERVER runtime policy forbids ambiguous/local overrides unless an explicit emergency policy is approved.
15. Structural bootstrap values may come from env/-D while Config Server carries desired structural state.
16. Full snapshot rebuild is required.
17. Event-dropping AtomicBoolean coalescing is invalid.
18. Use serialized generation/dirty coalescing so the latest state cannot be lost.
19. Complete remote runtime-policy manifest is recommended; missing keys reject rather than silently reset.
20. Initial structural mismatch blocks readiness.
21. Runtime structural drift keeps service ready with restart diagnostics by default.
22. Post-start Config Server outage keeps the current accepted policy.
23. Implementation GO remains blocked.
```

Prompt 6 must not inherit these claims from the original Prompt 5 output:

```text
1. That Boot 3.5.6 is necessarily required solely because Step 0B used it.
2. That the proposed Gradle dependencyManagement syntax is validated for this repository.
3. That spring-cloud-starter-config compileOnly alone provides runtime Config Client to consumers.
4. That ConfigServerConfigDataLoader is the appropriate public activation type.
5. That SmartLifecycle guarantees initial apply before traffic.
6. That the Spring bridge publishes CONFIG_SNAPSHOT_V1.
7. That dropping concurrent refresh events with AtomicBoolean is safe.
8. That refresh delivery through Actuator/Bus need not be decided.
9. That current TracingProperties defaults are the canonical defaults for the target namespace.
10. That a Config Server outage is an apply STALE outcome.
11. That all dependency and refresh blockers are already resolved.
```

---

# 16. Final status

```text
Prompt 5 result: PASS_WITH_CRITICAL_CORRECTIONS
Config Server target: CONDITIONALLY ACCEPTED
Refresh delivery: UNRESOLVED
Production dependency adoption: GOVERNANCE GATE
Implementation GO: NOT GRANTED
Legacy mutation deletion: BLOCKED
Next prompt: Authority Modes and Namespace Migration
```
