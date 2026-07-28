# SRE Diagnostics and JMX Contract
## Corrected Repository-Grounded Retention Decision

## Review status

| Field | Value |
|---|---|
| Report status | **PARTIAL_PASS_WITH_CRITICAL_CORRECTIONS** |
| Diagnostics ownership model | **Accepted** |
| Platform JMX contract | **Read-only after cutover** |
| Private control JMX | **Internal; not an SRE surface** |
| Separate LKG payload | **Rejected** |
| Audit capacity | **32 fixed in-memory entries per process** |
| Health `DEGRADED` status | **Not introduced by this design** |
| Exact per-attribute retention | **Blocked by missing JMX baseline** |
| Implementation GO | **NOT GRANTED** |
| Legacy mutation deletion | **BLOCKED** |
| Intended handoff | Prompt 10 — Aggressive Cleanup and Cutover |

The original report established useful concepts but contained critical inconsistencies:

- it kept `applyConfigurationSnapshot` on the platform `MBeanServer`, contradicting the private-writer security decision;
- it treated `readAcceptedState()` as both a JMX attribute and an operation;
- it concentrated most diagnostics in the Control MBean and risked making the Diagnostics MBean obsolete;
- it introduced a new `DEGRADED` health/readiness state without an approved corporate health contract;
- it made `restartRequired` part of runtime truth instead of a separate structural-drift observation;
- it defined “last successful apply” ambiguously and ignored successful `UNCHANGED` evaluations;
- it mapped all invalid configuration to one rejected counter without verifying current producers;
- it exposed a full audit buffer and accepted policy too broadly;
- it used arbitrary bounded-violation and HTTP-history limits without an explicit contract;
- it claimed a complete per-attribute retention matrix before the JMX baseline had been captured;
- it omitted bridge/coalescing/staleness diagnostics required for Config Server operations.

This corrected document defines the target diagnostic model and a provisional JMX disposition. Exact removal of current attributes remains gated by the baseline and named-consumer inventory.

---

# 1. Diagnostic model and ownership

The target separates four kinds of data.

## 1.1 Accepted runtime truth

Owned by the one Agent-side accepted-state holder:

```text
AcceptedTracingPolicyState
```

Contains:

- `runtimeInstanceId`;
- `acceptedSequence`;
- `payloadDigest`;
- optional `acceptedSourceRevision`;
- `acceptedAuthority`;
- `acceptedTrigger`;
- accepted effective runtime policy.

This state is immutable and changes only through one accepted-state CAS.

## 1.2 Structural drift truth

Owned by a separate Agent-side or coordinated diagnostics component:

```text
StructuralDriftStatus
```

Contains:

- whether restart is required;
- bounded set of reason/key codes;
- effective structural fingerprint;
- desired structural fingerprint;
- desired source revision;
- observation timestamp.

Structural drift is not part of the accepted runtime-policy CAS because it describes desired-versus-effective bootstrap state rather than current runtime behavior.

It must persist until a later successful structural evaluation clears or replaces it. It must not be overwritten by an unrelated failed attempt.

## 1.3 Apply-attempt evidence

Owned by:

```text
TracingPolicyApplyDiagnostics
```

Contains bounded current evidence:

- latest attempt identity and timestamp;
- latest outcome;
- latest observed source revision;
- latest authority and trigger;
- latest bounded reason/violation codes;
- last successful evaluation timestamp;
- last accepted-change timestamp;
- last unsuccessful attempt timestamp;
- counters by bounded outcome;
- refresh/coalescing delivery diagnostics where applicable.

Attempt evidence is not runtime truth and does not participate in CAS.

## 1.4 Bounded audit

Owned by:

```text
TracingPolicyAuditBuffer
```

A fixed-capacity process-local ring buffer containing metadata-only apply history.

It is:

- not a source of truth;
- not durable;
- not a replay log;
- not a recovery mechanism;
- not a compliance audit store.

---

# 2. Binding LKG decision

## Decision

```text
Current accepted state is the logical LKG.
No separate full LKG payload.
```

Reason:

- `REJECTED`, `STALE` and `FAILED` candidates do not commit;
- a failed delivery does not replace accepted state;
- a separate duplicate payload would create competing recovery truth.

Expose LKG metadata as aliases of accepted-state metadata only when an SRE consumer needs the term:

```text
lkgAcceptedSequence = acceptedSequence
lkgPayloadDigest = payloadDigest
```

Do not store another policy copy.

Reversal condition:

A separate LKG payload is justified only if the design later permits a transient current state that is not itself the last accepted recoverable state. No such state is currently approved.

---

# 3. Retention matrix

## 3.1 Existing configuration-management diagnostics

| Current diagnostic | Current producer | Known/current consumer | Decision | Target replacement/source | Target surfaces | Retention/classification |
|---|---|---|---|---|---|---|
| `ConfigReloadMetrics` | Current diagnostics/control path | Actuator/JMX, exact consumer inventory pending | **MERGE + REWRITE** | Apply-attempt counters and delivery metrics | metrics, Diagnostics JMX, Actuator read | process lifetime; management data |
| `ConfigAuditTrail` | Current diagnostics/control path | Actuator, exact consumer inventory pending | **REWRITE** | `TracingPolicyAuditBuffer` | dedicated bounded Actuator/JMX read | 32 entries; metadata-only |
| `InvalidConfigCount` | `PlatformTracingJmxRegistrar`/current invalid-config producers | Diagnostics projection | **REWRITE after producer inventory** | bounded validation counters split by phase/outcome | metric + Diagnostics JMX | monotonic process lifetime |
| Per-domain config version | Current domain holders | JMX consumers unknown until baseline/runbook inventory | **REMOVE after baseline unless named consumer requires a temporary derived view** | global `acceptedSequence` | Control status JMX + Actuator | current accepted truth |
| Per-domain `lastUpdatedSource` | Current domain holders | JMX consumers unknown | **REMOVE after baseline** | `acceptedAuthority` + `acceptedTrigger` | Control status JMX + Actuator | current accepted truth |
| Static-zero partial-protocol metrics | Partial protocol | No named consumer | **REMOVE** | none | none | delete |
| Old partial-protocol applied-state projection | Partial protocol | No confirmed target consumer | **REMOVE/REPLACE** | accepted-state metadata and domain read projections | platform read-only JMX | current |
| Existing effective-config map | Spring Actuator | Operators | **REWRITE** | secret-safe source-aware status model | Actuator read | current management data |

`InvalidConfigCount` must not be mechanically renamed to `rejectedCount` until all current producers are classified. Bootstrap parsing failures, protocol decode failures and policy validation rejection are distinct phases.

## 3.2 Runtime behavior diagnostics

These remain useful independently of configuration versioning.

| Diagnostic family | Decision | Reason |
|---|---|---|
| Sampling decision counts | **KEEP** | Shows actual sampled/dropped behavior |
| Export success/drop/failure counts | **KEEP** | Shows observability delivery health |
| Processor throughput/error counts | **KEEP** | Shows runtime processor behavior |
| Scrubbing/validation decision or violation counts | **KEEP/REWRITE** | Useful if bounded and semantically defined |
| Queue/BSP operational metrics | **KEEP** | Structural/runtime health, not control-plane versioning |
| Per-domain configuration versions | **REMOVE/MERGE** | Replaced by one accepted sequence |

Runtime behavior metrics must not be merged into configuration-apply counters.

---

# 4. Target concept disposition

| Concept | Decision | Truth class | Source of truth | Surface |
|---|---|---|---|---|
| `runtimeInstanceId` | KEEP | accepted runtime truth | accepted state | Control status JMX, Actuator |
| `acceptedSequence` | KEEP | accepted runtime truth | accepted state | Control status JMX, Actuator |
| `payloadDigest` | KEEP | accepted runtime truth | accepted state | Control status JMX, Actuator |
| `acceptedSourceRevision` | KEEP optional | accepted runtime truth metadata | accepted state | Control status JMX, Actuator |
| `lastObservedSourceRevision` | KEEP optional | evidence | apply diagnostics | Diagnostics JMX, Actuator |
| `acceptedAuthority` | KEEP | accepted truth metadata | accepted state | Control status JMX, Actuator |
| `acceptedTrigger` | KEEP | accepted truth metadata | accepted state | Control status JMX, Actuator |
| latest attempt authority/trigger | KEEP | evidence | apply diagnostics | Diagnostics JMX, audit |
| `attemptId` | KEEP | evidence | apply diagnostics/audit | Diagnostics JMX, Actuator, logs |
| `runtimeOutcome` | KEEP | evidence | apply diagnostics/audit | Diagnostics JMX, metric, Actuator |
| `restartRequired` | KEEP | structural drift truth | drift status | Diagnostics JMX, Actuator, health detail |
| `restartRequiredKeys/reasonCodes` | KEEP bounded | drift evidence | drift status | Diagnostics JMX, authorized Actuator |
| last successful evaluation | KEEP | evidence | apply diagnostics | Diagnostics JMX, Actuator |
| last accepted change | KEEP | evidence | apply diagnostics/accepted state | Diagnostics JMX, Actuator |
| last unsuccessful attempt | KEEP | evidence | apply diagnostics | Diagnostics JMX, Actuator |
| current accepted policy projection | KEEP filtered | convenience over truth | accepted state | domain JMX reads, Actuator |
| logical LKG | KEEP concept | accepted truth | current accepted state | documented/metadata only |
| bounded violations | KEEP | evidence | latest diagnostics/audit | Diagnostics JMX, authorized Actuator |
| bounded audit | KEEP | evidence | audit buffer | dedicated read operation |

## Timestamp semantics

Use distinct timestamps:

```text
lastAttemptAt
lastSuccessfulEvaluationAt   // APPLIED or UNCHANGED
lastAcceptedChangeAt         // APPLIED only
lastUnsuccessfulAttemptAt    // REJECTED, STALE or FAILED
```

Do not call every successful `UNCHANGED` evaluation an accepted-state change.

---

# 5. Platform JMX contract

## 5.1 Security boundary

After cutover:

```text
Platform MBeanServer:
    read-only operational contract

Private process-local MBeanServer:
    aggregate writer and internal accepted-state read
```

`applyConfigurationSnapshot` must not appear on the platform MBeanServer.

The private server is not an SRE or remote-management surface.

## 5.2 Existing ObjectNames

All existing eight platform ObjectNames remain unchanged.

Exact attribute and operation changes require:

1. captured baseline;
2. named-consumer inventory;
3. approved contract delta.

## 5.3 Recommended responsibility split

### Existing Control ObjectName

Read-only current accepted control status:

- protocol support/version;
- `runtimeInstanceId`;
- `acceptedSequence`;
- `payloadDigest`;
- optional accepted source revision;
- accepted authority;
- accepted trigger.

Do not expose:

- aggregate write operation;
- full audit buffer;
- failure stack traces;
- raw policy payload.

Use primitive/string attributes where practical. If a composite read is retained, define it as a read operation, not ambiguously as an attribute.

### Existing Diagnostics ObjectName

Owns apply and drift diagnostics:

- latest attempt metadata;
- latest outcome and bounded reason code;
- outcome counters;
- last-success/change/failure timestamps;
- Config Server delivery/coalescing status;
- structural drift status;
- bounded recent-audit read operation.

Do not merge this MBean into Control merely to reduce class count. It has a distinct SRE consumer and incident role.

### Sampling/Scrubbing/Validation/Export/Metrics ObjectNames

- retain approved read-only effective behavior/configuration attributes;
- retain runtime behavior counters;
- remove write operations;
- remove obsolete per-domain configuration version/source attributes after baseline approval;
- redact or omit sensitive values.

### Readiness ObjectName

Keep the existing Agent readiness state machine and current attributes unless baseline review identifies dead data.

Add capability support only for installed protocol functionality:

```text
CONFIG_SNAPSHOT_V1
```

Do not encode Config Server drift or apply failure into the Agent extension's sticky readiness state.

---

# 6. Minimal Actuator read model

The existing tracing endpoint becomes read-only.

Recommended top-level model:

```text
TracingStatusReadModel
    integration
    acceptedState
    applyStatus
    structuralDrift
    readiness
    runtimeBehaviorSummary
```

## `integration`

- mode;
- authority;
- Agent observed/present;
- Config Server bridge state;
- protocol compatibility.

## `acceptedState`

- runtime instance ID;
- accepted sequence;
- payload digest;
- accepted source revision;
- accepted authority;
- accepted trigger;
- last accepted change timestamp;
- filtered effective runtime policy.

## `applyStatus`

- last attempt ID/time;
- last outcome;
- last observed source revision;
- bounded reason code;
- last successful evaluation;
- last unsuccessful attempt;
- outcome counters;
- coalesced/pending refresh status.

## `structuralDrift`

- restart required;
- bounded reason/key codes;
- effective/desired fingerprints;
- last successful drift evaluation time.

## `readiness`

- Agent readiness state;
- capabilities;
- application-side configuration readiness;
- protocol supported;
- initial policy accepted.

## `runtimeBehaviorSummary`

Only compact, already-aggregated counters/status required by current operators.

## Secrecy

The read model must not expose:

- raw HMAC key;
- HMAC key digest;
- raw rules content;
- full rules file path;
- raw snapshot;
- unbounded route/path collections;
- stack traces;
- unrestricted validation messages.

`runtimeInstanceId`, revision, route patterns and policy values are management-sensitive, not public data. Endpoint authorization remains an application/infrastructure requirement.

## Audit access

Do not embed the entire audit buffer into the default status response.

Provide a separate bounded read operation/resource for recent audit entries.

---

# 7. Metrics contract

## 7.1 Configuration control metrics

Prefer one bounded-label outcome counter instead of five independent families:

```text
platform.tracing.policy.apply.attempts
    counter
    labels:
        outcome = applied|unchanged|rejected|stale|failed
        authority = helm|config_server|dev_actuator
        trigger = bootstrap|refresh|dev_command
```

All label values are closed enums.

Additional minimal metrics:

```text
platform.tracing.policy.apply.duration
    timer
    labels: outcome, authority, trigger

platform.tracing.policy.restart_required
    gauge 0|1

platform.tracing.policy.refresh.events
    counter
    labels: disposition = processed|coalesced

platform.tracing.policy.refresh.pending
    gauge 0|1

platform.tracing.policy.last_successful_evaluation.timestamp
    gauge
```

A last-success timestamp gauge is preferable to a high-cardinality staleness label.

Optional only with SRE consumer:

```text
platform.tracing.policy.accepted_sequence
platform.tracing.policy.config_server.staleness
platform.tracing.policy.audit.overwrites
```

## 7.2 Validation metrics

Use bounded phase/reason enums:

```text
platform.tracing.configuration.validation.rejected
    labels:
        phase = bootstrap|decode|provenance|runtime_policy|structural
        reason_code = closed bounded enum
```

Do not label metrics with:

- attempt ID;
- runtime instance ID;
- source revision;
- property key;
- path;
- exception message/class;
- arbitrary validation text.

## 7.3 Runtime metrics

Keep existing sampling/export/processor metrics after semantic review.

Counters are monotonic for the process lifetime, not a “rolling window.”

---

# 8. Health and readiness model

## 8.1 Preserve separate concerns

### Agent readiness

Existing Agent extension state:

```text
INITIALIZING -> READY | FAILED
```

Keep it as the Agent bootstrap/attestation contract.

### Application tracing configuration readiness

Separate application-side readiness checks:

- required Agent and protocol observed;
- initial Config Server policy successfully evaluated/applied;
- no initial structural mismatch;
- no unsupported topology/version.

Do not add `DEGRADED` to the Agent readiness enum.

## 8.2 Startup behavior

In `CONFIG_SERVER` authority:

- initial import/provenance/protocol/apply failure -> application not ready or startup failure;
- initial structural mismatch -> application not ready;
- successful `APPLIED` or `UNCHANGED` -> configuration ready.

In `HELM`:

- health does not require a Config Server apply sequence;
- Agent bootstrap accepted state is sufficient.

Do not use a generic condition such as:

```text
acceptedSequence == 0
```

to infer failure.

## 8.3 Runtime behavior

Runtime apply failure or Config Server outage:

- retain LKG;
- remain ready by default;
- health may report an additional diagnostic/detail;
- alert through metrics.

Runtime structural drift:

- remain ready by default;
- expose `restartRequired`;
- do not remove all replicas from service.

Use a custom `DEGRADED` Spring health status only when the corporate platform already defines and operationalizes it. This tracing design does not introduce a new status by itself.

## 8.4 Health detail exposure

Kubernetes readiness/liveness must not expose sensitive property keys or policy details.

Detailed restart reasons belong to authenticated management diagnostics, not unauthenticated probe payloads.


---

# 9. Bounded audit contract

## 9.1 Capacity

Binding default:

```text
32 entries per process
```

Rationale:

- enough for recent incident reconstruction;
- small bounded memory footprint;
- naturally overwritten during refresh storms;
- not intended for durable compliance history.

The capacity is an internal constant, not a public tracing property.

Revisit only with measured SRE incident requirements.

## 9.2 Entry shape

```text
timestamp
attemptId
runtimeInstanceId
acceptedSequenceBefore
acceptedSequenceAfter
authority
trigger
outcome
restartRequired
boundedReasonCode
optionalSourceRevision
```

For non-`APPLIED` attempts, `acceptedSequenceAfter` equals the current accepted sequence. Do not use `0` as a sentinel.

## 9.3 Excluded data

Never include:

- raw payload;
- raw secrets;
- HMAC key or digest;
- raw rules content;
- full file paths;
- stack traces;
- arbitrary exception messages;
- unbounded violations;
- full accepted policy.

## 9.4 Read surfaces

- dedicated read-only Diagnostics JMX operation with a server-capped maximum;
- dedicated read-only Actuator audit resource/operation;
- no inclusion in default health/probe response.

The JMX representation may use `TabularData`, subject to baseline and payload-bound tests.

---

# 10. Runbook fields

For “policy did not update”:

1. mode and authority;
2. Agent readiness state;
3. protocol capability/version;
4. runtime instance ID;
5. accepted sequence and digest;
6. accepted authority/trigger/source revision;
7. last attempt ID/time/outcome;
8. last observed source revision;
9. last successful evaluation time;
10. last accepted change time;
11. last unsuccessful attempt time and bounded reason code;
12. pending/coalesced refresh state;
13. restart-required status and authorized reason codes;
14. effective/desired structural fingerprints;
15. Config Server bridge/client state;
16. current audit tail;
17. controlled Agent/starter distributive versions.

For “trace volume changed unexpectedly” additionally inspect:

- sampling decision counters;
- accepted sampling projection;
- export state and counters;
- processor/queue counters;
- recent accepted policy audit entries.

For “service requires restart” inspect:

- drift observation timestamp;
- desired source revision;
- structural fingerprints;
- rollout version/Agent artifact;
- authorized drift reason codes.

---

# 11. Diagnostics code and contracts to delete

## 11.1 Remove without target replacement

After baseline confirms no named consumer:

- static-zero partial-protocol metrics;
- obsolete partial-protocol applied-state fields;
- dead SDK namespace fixtures;
- stale metrics that never change;
- duplicate convenience projections with no incident use case.

## 11.2 Remove after aggregate cutover

- per-domain configuration version owners;
- per-domain `lastUpdatedSource`;
- legacy `ConfigReloadMetrics` implementation;
- legacy `ConfigAuditTrail` implementation;
- partial protocol handler/applier diagnostics;
- domain write-operation diagnostics;
- HTTP mutation diagnostics;
- `RuntimeConfigApplier` path diagnostics;
- PerfAdminController mutation diagnostics;
- old write-oriented JMX client methods;
- tests asserting legacy mutation behavior.

## 11.3 Preserve/rewrite

- readiness/attestation reads;
- sampling/export/processor behavior metrics;
- approved domain read-only configuration;
- invalid configuration evidence after producer reclassification;
- read-only status endpoint.

---

# 12. Required JMX baseline artifact

The original report cannot close the exact JMX retention matrix until this artifact exists.

Capture:

```text
ObjectName
attribute name
attribute Java/OpenType
readable/writable
operation name
operation signature
impact
notification metadata
sample value shape
known consumers/runbooks
```

Required output:

```text
jmx-management-contract-baseline.json
```

Then produce an approved delta:

| Existing member | KEEP / REMOVE / REWRITE | Replacement | Consumer impact | Test |
|---|---|---|---|---|

ObjectNames remain unchanged, but that does not imply every current attribute survives.

The baseline is read-only evidence collection and should be performed before implementation GO.

---

# 13. Exact target component placement

## `platform-tracing-otel-javaagent-extension`

Own:

- accepted state;
- structural drift status;
- apply diagnostics;
- bounded audit buffer;
- read-only platform JMX projections;
- private control MBeanServer diagnostics required in-process;
- runtime behavior metrics.

Do not place operational JMX diagnostics in the generic `platform-tracing-otel` module by default.

## `platform-tracing-spring-boot-autoconfigure`

Own:

- source/bridge delivery status;
- generation/coalescing worker diagnostics;
- read-only Actuator projection;
- Spring health/readiness composition;
- platform-JMX read client;
- private in-process control client.

Application diagnostics must not become a second runtime truth.

## Test/E2E modules

Own:

- JMX contract baseline tests;
- process-level read-only platform JMX test;
- metric-cardinality tests;
- secret canary tests;
- audit boundedness;
- health/readiness matrix;
- refresh-storm diagnostics.

---

# 14. Corrected retention decision summary

| Concern | Binding decision |
|---|---|
| Current accepted state as LKG | KEEP |
| Separate LKG payload | REMOVE / do not create |
| Global accepted sequence | KEEP |
| Per-domain config versions | REMOVE after baseline/cutover |
| Per-domain update source | REWRITE as global authority/trigger |
| Runtime behavior metrics | KEEP |
| Apply metrics | MERGE/REWRITE |
| Invalid config counter | REWRITE after producer inventory |
| Audit | REWRITE, 32-entry ring |
| Raw accepted policy in default status | REMOVE |
| Filtered accepted projection | KEEP |
| Full audit in default status | REMOVE |
| Platform JMX write operation | REMOVE |
| Private internal write operation | KEEP internal |
| Agent readiness state machine | KEEP |
| New Agent `DEGRADED` state | REJECT |
| Runtime drift readiness removal | REJECT |
| Runtime drift diagnostics | KEEP |
| Static-zero metrics | REMOVE |
| Config Server bridge metrics | ADD minimal bounded set |
| High-cardinality metric labels | REJECT |

---

# 15. Remaining blockers

| Blocker | Status |
|---|---|
| Conceptual diagnostics ownership/retention | Resolved by this report |
| Exact current JMX member disposition | **Blocked by missing baseline** |
| Named current JMX consumers/runbooks | **Open** |
| Private MBeanServer feasibility | **Critical open security gate** |
| Complete property/env disposition | **Open** |
| Final Category A and bounds | **Open** |
| Secret-safe projection implementation | **Open** |
| Config Server dependency/refresh delivery | **Open** |
| Corporate health status decision | Use standard statuses unless separately approved |
| Implementation GO | **Not granted** |
| Legacy deletion | **Blocked** |

---

# 16. Handoff contract for Prompt 10

Prompt 10 must treat these findings as accepted:

```text
1. Platform MBeanServer is read-only after cutover.
2. Aggregate writes exist only on the private process-local MBeanServer.
3. Existing eight platform ObjectNames remain unchanged.
4. Control MBean owns accepted-state metadata; Diagnostics MBean owns attempts, drift and audit.
5. Do not merge Diagnostics into Control without a separate consumer decision.
6. Runtime truth, structural drift and attempt evidence are separate state categories.
7. Current accepted state is logical LKG; no separate full LKG payload.
8. acceptedSourceRevision belongs to accepted state; lastObservedSourceRevision belongs to diagnostics.
9. restartRequired belongs to structural drift status, not the runtime policy CAS.
10. Successful evaluation and accepted state change have separate timestamps.
11. Per-domain configuration versions/source are removed after baseline and cutover.
12. Runtime behavior metrics remain independently useful.
13. Configuration apply metrics use bounded enum labels only.
14. No metric labels contain IDs, revisions, keys, paths or free-form reasons.
15. Audit capacity is a fixed 32 entries per process.
16. Audit entries contain metadata only and are not a replay or recovery log.
17. The default Actuator status does not embed the full audit buffer.
18. Health/readiness does not infer failure from acceptedSequence=0.
19. Agent readiness remains INITIALIZING/READY/FAILED; this plan does not add DEGRADED.
20. Runtime failure retains LKG and normally remains ready.
21. Runtime structural drift remains ready and exposes restartRequired diagnostics.
22. Detailed drift keys are not exposed in unauthenticated probe payloads.
23. Static-zero and obsolete protocol diagnostics are deleted.
24. Exact JMX member removal remains gated by a captured baseline and consumer inventory.
25. Implementation GO remains not granted.
```

Prompt 10 must not inherit these claims from the original Prompt 9 output:

```text
1. That applyConfigurationSnapshot remains on the platform Control MBean.
2. That the private writer is part of the SRE JMX contract.
3. That readAcceptedState is simultaneously an attribute and operation.
4. That Diagnostics should be merged into Control if it becomes small.
5. That restartRequired is accepted runtime-policy truth.
6. That every InvalidConfigCount event is a runtime policy rejection.
7. That counters use rolling-window retention.
8. That acceptedSequence=0 or an arbitrary age implies DEGRADED.
9. That a new DEGRADED Agent/readiness state should be introduced.
10. That the complete audit buffer belongs in the default Actuator status.
11. That non-APPLIED audit entries should record sequence zero.
12. That violations are capped at 16 without a defined contract.
13. That every JMX read attribute has already received a final binding disposition.
14. That full policy values are public rather than management-sensitive.
15. That the diagnostics blocker is fully closed without the JMX baseline.
```

---

# 17. Final status

```text
Prompt 9 result: PARTIAL_PASS_WITH_CRITICAL_CORRECTIONS
Diagnostics model: ACCEPTED
Platform JMX: READ-ONLY
Private writer: INTERNAL ONLY
LKG: CURRENT ACCEPTED STATE
Audit: 32-ENTRY BOUNDED RING
Agent DEGRADED state: NOT INTRODUCED
Exact JMX retention: BASELINE-BLOCKED
Implementation GO: NOT GRANTED
Legacy deletion: BLOCKED
Next prompt: Aggressive Cleanup and Cutover
```
