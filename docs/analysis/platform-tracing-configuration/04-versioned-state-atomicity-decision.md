# Versioned State and Atomicity Decision
## Corrected Repository-Grounded Binding Recommendation

## Review status

| Field | Value |
|---|---|
| Report status | **PASS_WITH_CRITICAL_CORRECTIONS** |
| Binding state-primitive decision | **RETAIN_GENERIC** |
| Aggregate policy value | **Required, Agent-side and domain-specific** |
| Implementation GO | **NOT GRANTED** |
| Legacy mutation deletion | **BLOCKED** |
| Intended handoff | Prompt 5 — Config Server Production Control |

The original report selected the correct high-level direction—retain the proven generic CAS primitive and converge on one accepted runtime policy—but it contained several important errors:

- it placed Agent-specific policy and diagnostics types in `platform-tracing-otel`;
- it overstated the number of hot-path reads eliminated;
- it claimed one aggregate state prevents all mixed-policy behavior across a span lifecycle;
- it placed all source revision metadata outside accepted state;
- it treated every CAS miss as `FAILED`;
- it assumed the exact current `VersionedStateHolder` API without a focused code-level review;
- it prematurely fixed the full Category A payload;
- it used names such as `PlatformLogLevel` in a module where the type may not legally exist.

This corrected report preserves the binding choice while fixing module ownership and semantics.

---

# 1. Binding recommendation

## Decision

```text
RETAIN_GENERIC
```

Retain the existing generic:

```java
VersionedStateHolder<T>
```

in `platform-tracing-otel`.

Create exactly one Agent-side instance parameterized with one immutable aggregate accepted-state value:

```text
VersionedStateHolder<AcceptedTracingPolicyState>
```

The aggregate value is domain-specific, but the CAS owner remains the existing generic primitive.

## Important distinction

`RETAIN_GENERIC` means:

- retain the generic CAS mechanism;
- create one accepted aggregate state;
- remove independent mutable ownership from the three current domain holders;
- do not create a second tracing-specific CAS engine.

It does **not** mean:

- retain three mutable domain holders;
- add an aggregate state above them;
- project accepted state sequentially into them;
- expose two accepted-state representations.

---

# 2. Correct module ownership

## 2.1 Generic primitive

| Type | Module | Decision |
|---|---|---|
| `VersionedStateHolder<T>` | `platform-tracing-otel` | RETAIN |
| generic `VersionedState` contract, if currently required | `platform-tracing-otel` | RETAIN/ADAPT only after API review |

The generic module owns only reusable state mechanics.

It must not acquire dependencies on:

- Java Agent extension classes;
- Spring;
- JMX;
- tracing configuration property names;
- Config Server;
- diagnostics MBeans;
- Agent-specific enums.

## 2.2 Aggregate tracing policy state

The canonical aggregate state should live in:

```text
platform-tracing-otel-javaagent-extension
```

Reason:

- supported production state is Agent-owned;
- current sampler, processors, exporter gate, propagation gate and diagnostics logging are Agent-extension runtime concerns;
- Local SDK production parity is excluded;
- there is no need to move Agent-specific policy domain objects into the generic OTel runtime module.

Representative internal types:

```text
AcceptedTracingPolicyState
EffectiveTracingPolicy
SamplingRuntimePolicy
ScrubbingRuntimePolicy
ValidationRuntimePolicy
TracingPolicyApplyDiagnostics
TracingPolicyAuditBuffer
```

Names are illustrative, not public API commitments.

## 2.3 Spring-side types

Application-side types remain internal to:

```text
platform-tracing-spring-boot-autoconfigure
```

Examples:

- snapshot builder;
- Config Server bridge;
- provenance classifier;
- DEV_ACTUATOR command model;
- application-side Open-Type codec;
- JMX client adapter.

No Agent policy Java DTO crosses the JMX boundary.

---

# 3. Correct aggregate state model

## 3.1 Separate policy content from accepted-state metadata

Do not put version metadata directly into the policy domain object.

Preferred shape:

```java
record AcceptedTracingPolicyState(
    UUID runtimeInstanceId,
    long acceptedSequence,
    String payloadDigest,
    Optional<String> acceptedSourceRevision,
    ConfigurationAuthority acceptedAuthority,
    ApplyTrigger acceptedTrigger,
    EffectiveTracingPolicy policy
) implements VersionedState {
}
```

`EffectiveTracingPolicy` contains only accepted runtime behavior.

This separation avoids confusing:

- policy equality;
- state version;
- source provenance;
- attempt diagnostics.

## 3.2 Metadata that should switch atomically with accepted policy

The following belongs in the accepted CAS state when available:

- `runtimeInstanceId`;
- `acceptedSequence`;
- `payloadDigest`;
- accepted source revision;
- accepted authority;
- accepted trigger;
- effective runtime policy.

Reason: these fields describe the currently accepted runtime truth and must not lag behind the policy they describe.

## 3.3 Metadata outside accepted CAS state

The following belongs in diagnostics/audit:

- `attemptId`;
- attempt counter;
- last observed source revision;
- last rejected source revision;
- last rejected authority/trigger;
- failure/rejection summary;
- validation violations;
- stale count;
- failed count;
- rejected count;
- unchanged count;
- bounded audit entries;
- restart-required keys for the latest attempt.

There are two distinct revision concepts:

```text
acceptedSourceRevision
```

describes the current accepted state and belongs in the CAS envelope.

```text
lastObservedSourceRevision
```

describes the latest attempt/event and belongs in diagnostics.

The original report incorrectly placed all source revision data outside accepted state.

---

# 4. Runtime policy content

## 4.1 Required aggregate ownership

Every value that must participate in one atomic runtime policy transition must be represented in the one accepted state.

Candidate domains:

- sampling;
- runtime-safe scrubbing subset;
- runtime-safe validation subset;
- platform-managed exporter kill switch;
- platform trace-control-header gate;
- Agent-extension diagnostic log level.

## 4.2 Category A is still provisional

The report must not freeze the full Category A list before:

- security review;
- SRE review;
- property migration review;
- runtime-safety review;
- performance evidence.

Confirmed Category B includes:

- `scrubbing.rules-config`;
- HMAC secret/key material;
- key-id rotation unless a dedicated protocol is approved;
- extension/class composition;
- exporter topology;
- queue/BSP topology;
- resource/service identity;
- propagator composition.

The later prompts may narrow Category A.

## 4.3 Deep immutability

The accepted policy must be deeply immutable.

Required:

- defensive copies of maps/lists/sets;
- deterministic ordering;
- no mutable collections exposed;
- no lazy file/class loading from accepted state;
- no secrets in string representations;
- bounded collection sizes;
- validated normalized values.

A Java `record` is not sufficient by itself when its components contain mutable collections.

---

# 5. Correct consistency guarantee

## 5.1 What one CAS guarantees

The aggregate state provides:

```text
one linearizable accepted-state publication point
```

Every individual reader invocation sees one coherent immutable policy version.

It prevents:

- sampling from seeing a partially built sampling policy;
- a single apply operation publishing sampling v2 while scrubbing remains v1 as separate accepted owners;
- sequential domain setter visibility during one commit.

## 5.2 What one CAS does not guarantee

It does not automatically guarantee that one span uses the same policy version across its entire lifecycle.

Example:

1. span starts under policy v10;
2. sampler reads v10;
3. policy v11 is accepted;
4. span ends;
5. scrubbing/validation/export readers observe v11.

This is not a torn read. It is a temporal policy transition across different lifecycle stages.

The architecture must choose and document one consistency model.

### Recommended default

```text
Hook-level snapshot consistency
```

Each hook reads one coherent current policy. A long-lived span may cross a policy-version boundary.

This matches common dynamic-configuration semantics and avoids per-span state retention.

### Stronger optional model

```text
Per-span policy consistency
```

Capture policy version or policy reference at span start and reuse it at span end.

This has additional memory, propagation and instrumentation cost and should not be introduced without a confirmed requirement.

Prompt 4's original statement that aggregate state removes all mixed-state behavior was too broad.

---

# 6. Runtime reader behavior and cost

## 6.1 Correct read count

The aggregate design does not mean "one AtomicReference read for the entire trace."

It means one accepted-state owner.

Each runtime component may read current state when invoked:

- sampler at span start;
- scrubbing processor at span end;
- validation processor at span end;
- exporter gate during export;
- propagation hook during extraction/injection;
- diagnostics logger when logging.

Therefore, the comparison is not simply:

```text
three current reads versus one aggregate read
```

The real comparison is:

- one reference owner instead of several owners;
- one coherent aggregate snapshot per hook invocation;
- potentially more object traversal from aggregate state;
- potentially improved publication and reduced ownership complexity.

Performance claims require benchmarks.

## 6.2 Reader migration

Current mutable owners should become one of:

- deleted;
- pure stateless helpers;
- immutable domain view types;
- adapters reading the aggregate state.

They must not retain independent mutable references.

---

# 7. Correct optimistic concurrency semantics

## 7.1 Attempt flow

1. Decode wire snapshot.
2. Validate protocol and bounds.
3. Read current accepted state.
4. Check expected runtime instance and accepted sequence.
5. Build normalized candidate.
6. Validate full runtime candidate.
7. Compare runtime policy equality/digest.
8. Detect structural desired-state drift.
9. Attempt one accepted-state CAS when runtime state changed.
10. Publish diagnostics/audit after outcome.

## 7.2 Outcome semantics

| Situation | `runtimeOutcome` | Accepted sequence |
|---|---|---:|
| Runtime policy changed and CAS succeeds | `APPLIED` | +1 |
| Runtime policy equivalent | `UNCHANGED` | unchanged |
| Candidate invalid | `REJECTED` | unchanged |
| Expected base differs before commit | `STALE` | unchanged |
| CAS loses to concurrent accepted update | `STALE` or bounded retry, not `FAILED` | unchanged |
| Internal exception/irrecoverable commit error | `FAILED` | unchanged |
| Structural-only drift | `UNCHANGED` + `restartRequired=true` | unchanged |
| Runtime change plus structural drift | `APPLIED` + `restartRequired=true` | +1 |

The original report incorrectly mapped a normal concurrent CAS miss to `FAILED`.

A CAS miss means the base became stale. The handler should:

- return `STALE`; or
- perform one bounded rebuild/retry from the new accepted base.

`FAILED` is reserved for internal errors, not normal optimistic-concurrency conflict.

## 7.3 `UNCHANGED`

`UNCHANGED` should be determined from normalized policy equality or a canonical digest.

It must still:

- evaluate structural drift;
- record an attempt;
- update last-observed diagnostics;
- avoid incrementing accepted sequence.

---

# 8. Canonical digest requirements

The original report named SHA-256 but did not define canonicalization.

Required contract:

- digest covers normalized Category A runtime policy only;
- deterministic field order;
- deterministic collection order;
- explicit null/default representation;
- explicit protocol version;
- no raw secrets;
- no `toString()` serialization;
- no JVM-dependent object serialization;
- same logical policy produces the same digest in Spring and Agent codecs.

The digest is for:

- equality;
- idempotency;
- integrity diagnostics.

It is not for:

- ordering;
- authorization;
- replay prevention by itself.

A symmetric codec/digest contract test is mandatory.

---

# 9. LKG semantics

## Decision

No duplicate full LKG payload by default.

The current accepted state is logical LKG because:

- rejected candidate does not commit;
- stale candidate does not commit;
- failed candidate does not commit.

Separate payload storage is justified only if a later lifecycle permits:

```text
current transient state != last accepted recoverable state
```

No such state is currently required.

Diagnostics may expose:

- LKG sequence;
- LKG digest;
- LKG source revision;

which normally equal the current accepted state metadata.

---

# 10. Diagnostics and audit placement

## 10.1 Module placement

Agent operational diagnostics should normally live in:

```text
platform-tracing-otel-javaagent-extension
```

not in `platform-tracing-otel`, unless an existing reusable diagnostics abstraction is already proven.

Reason:

- diagnostics are produced by Agent apply attempts;
- JMX MBeans expose them;
- SRE consumers observe the Agent extension;
- the generic runtime module should not gain Agent/JMX operational responsibility.

## 10.2 Component split

```text
VersionedStateHolder<AcceptedTracingPolicyState>
    runtime truth only

TracingPolicyApplyDiagnostics
    counters and last attempt/result

TracingPolicyAuditBuffer
    bounded metadata-only history

JMX projection
    read aggregation
```

## 10.3 Audit restrictions

- bounded capacity;
- in-memory;
- bounded strings;
- no raw payload;
- no secrets;
- no state recovery;
- no replay authority.

---

# 11. Correct class and module disposition

## 11.1 `platform-tracing-otel`

| Component | Decision |
|---|---|
| `VersionedStateHolder<T>` | RETAIN |
| current generic versioned-state contract | RETAIN/ADAPT after exact API review |
| Agent-specific accepted policy classes | DO NOT PLACE HERE by default |
| JMX diagnostics/audit | DO NOT PLACE HERE by default |

## 11.2 `platform-tracing-otel-javaagent-extension`

| Current/target component | Decision |
|---|---|
| `AcceptedTracingPolicyState` | NEW internal |
| `EffectiveTracingPolicy` | NEW internal |
| one `VersionedStateHolder<AcceptedTracingPolicyState>` instance | NEW wiring |
| `SamplerStateHolder` | DELETE after reader cutover |
| `ScrubbingPolicyHolder` | DELETE after reader cutover |
| `ValidationPolicyHolder` | DELETE after reader cutover |
| `CompositeSampler` | REWRITE reader |
| `ScrubbingSpanProcessor` | REWRITE reader |
| `ValidatingSpanProcessor` | REWRITE reader |
| `SafeSpanExporter` | REWRITE gate read if included in Category A |
| propagation gate | REWRITE to read accepted policy if included |
| diagnostics log-level controller | REWRITE to accepted-policy read if included |
| aggregate apply handler | NEW |
| Agent Open-Type codec/MBean operation | NEW/REPLACE old protocol semantics |
| domain MBean writes | DELETE after cutover |
| current partial control handler/applier | DELETE after cutover |
| apply diagnostics | NEW/MERGE |
| bounded audit buffer | NEW/MERGE |

## 11.3 `platform-tracing-spring-boot-autoconfigure`

| Component | Decision |
|---|---|
| Config Server bridge | NEW after dependency GO |
| full snapshot builder | NEW internal |
| provenance classifier | PROMOTE/REIMPLEMENT from Step 0B evidence |
| application-side Open-Type codec | NEW internal |
| DEV_ACTUATOR JMX endpoint | NEW after security/authority decision |
| `RuntimeConfigApplier` | DELETE after cutover |
| `TracingRefreshScopeAutoConfiguration` | REPLACE, not simply rename |
| Actuator HTTP write operations | DELETE |
| per-domain JMX mutators | DELETE after aggregate cutover |
| read diagnostics endpoint | RETAIN/REWRITE |

## 11.4 Test/benchmark modules

Retain or rewrite:

- generic holder tests;
- provenance T1–T10;
- readiness/attestation tests;
- sampling decision characterization;
- scrubbing/validation behavior tests;
- M10/P5 semantics on production bridge.

Delete after cutover:

- tests asserting old domain setters;
- partial generic protocol wire tests;
- old Actuator HTTP mutation tests;
- PerfAdminController-specific control tests.

---

# 12. Required focused API review

The binding choice is conditional on confirming the actual current `VersionedStateHolder` API.

Before implementation GO, inspect and document:

1. exact generic bound on `T`;
2. exact `current()` return type;
3. exact update method signatures;
4. whether update is one CAS or an internal retry loop;
5. validation hooks;
6. update-source/version assumptions;
7. equality/identity assumptions;
8. whether the API reports stale versus failure;
9. whether generic tests cover ABA-like scenarios;
10. whether `AcceptedTracingPolicyState` can use it without tracing-specific changes.

Do not code against the pseudocode signature:

```java
tryUpdate(current, next)
```

unless the repository API actually provides that exact contract.

---

# 13. Benchmark and test gates

## 13.1 Microbenchmarks

Required:

1. sampler read before/after aggregate state;
2. concurrent accepted-state update under sampling load;
3. scrubbing/validation read overhead;
4. exporter gate overhead;
5. aggregate policy object allocation during apply;
6. canonical digest cost off hot path.

Do not claim aggregate state is faster before measurement.

## 13.2 Concurrency tests

Required:

- two concurrent valid updates from same base;
- stale expected base;
- bounded retry behavior;
- identical payload;
- revision-only refresh;
- invalid candidate;
- runtime plus structural drift;
- runtime instance mismatch after restart;
- no mutation on rejected/failed/stale;
- accepted sequence monotonicity.

## 13.3 Consistency tests

Required:

- every individual reader sees a valid immutable policy;
- no independently mutable domain owner remains;
- old domain setters cannot mutate accepted state;
- policy update during a long-lived span follows documented hook-level consistency;
- optional per-span consistency is not accidentally assumed.

---

# 14. Migration and cutover order

Implementation remains blocked until explicit GO.

After GO:

1. Capture JMX baseline.
2. Finalize Category A/B list.
3. Confirm `VersionedStateHolder` API suitability.
4. Add Agent-side accepted-state value and aggregate holder wiring.
5. Add aggregate apply handler and internal codec.
6. Add capability indicating installed protocol support.
7. Migrate runtime readers one domain at a time inside the development branch.
8. Prove no independent mutable owner remains.
9. Add Config Server and DEV_ACTUATOR adapters.
10. Rewire perf/E2E.
11. Remove old domain writes, old protocol operations, RefreshScope applier and HTTP mutation in one controlled cutover.
12. Update JMX contract tests and architecture fitness rules.
13. Do not declare intermediate mixed state a supported release.

---

# 15. Reversal condition

Change the binding decision from `RETAIN_GENERIC` to `REFACTOR_DOMAIN_SPECIFIC` only when the focused API review proves at least one of:

1. the generic API cannot express optimistic expected-base commit without tracing-specific changes;
2. accepted state metadata cannot be represented cleanly through its generic type contract;
3. the holder's current update semantics cannot distinguish stale conflict from internal failure;
4. composition requires duplicate version fields or competing sources of truth;
5. the generic API must gain Config Server, digest, authority or diagnostics concepts;
6. the resulting use is harder to understand and test than a dedicated state component.

Even then, prefer a domain-specific wrapper that composes the generic primitive before deleting the primitive.

---

# 16. Corrected binding verdict

```text
State primitive: RETAIN_GENERIC
Accepted state: one Agent-side aggregate immutable value
Current per-domain mutable holders: delete after cutover
State module:
    generic primitive -> platform-tracing-otel
    Agent policy state -> platform-tracing-otel-javaagent-extension
Atomicity:
    one linearizable accepted-state CAS
Consistency:
    hook-level snapshot consistency by default
LKG:
    current accepted state
Source revision:
    accepted revision inside CAS metadata;
    last observed revision in diagnostics
CAS conflict:
    STALE or bounded retry, not FAILED
Implementation GO:
    NOT GRANTED
Legacy deletion:
    BLOCKED
```

---

# 17. Handoff contract for Prompt 5

Prompt 5 must treat these findings as accepted:

```text
1. RETAIN_GENERIC is the binding recommendation, subject to an exact API review.
2. There is one Agent-side VersionedStateHolder<AcceptedTracingPolicyState>.
3. Agent-specific accepted policy and diagnostics should not be moved into platform-tracing-otel by default.
4. Current per-domain mutable holders must not survive as independent state owners.
5. Export/propagation/log-level join aggregate policy only if retained in final Category A.
6. One CAS provides linearizable accepted-state publication, not automatic per-span lifecycle consistency.
7. Hook-level snapshot consistency is the recommended default.
8. Accepted source revision/authority/trigger belong with accepted state metadata.
9. Last observed/rejected attempt metadata belongs in diagnostics.
10. Normal CAS conflict is STALE or bounded retry, not FAILED.
11. UNCHANGED does not advance accepted sequence.
12. Structural drift is independent from runtime outcome.
13. Digest requires canonical normalization and is not an ordering mechanism.
14. Current accepted state is logical LKG; no duplicate payload by default.
15. Diagnostics and audit are Agent operational components, separate from runtime truth.
16. No implementation GO is granted.
```

Prompt 5 must not inherit these claims from the original Prompt 4 output:

```text
1. That AcceptedTracingPolicy and Agent diagnostics belong in platform-tracing-otel.
2. That the aggregate design performs only one AtomicReference read for an entire trace.
3. That a single state CAS guarantees one policy version across a span's complete lifecycle.
4. That all sourceRevision data belongs outside CAS state.
5. That any CAS miss is FAILED.
6. That the exact tryUpdate(current,next) signature is confirmed.
7. That the full proposed Category A list is already approved.
8. That PlatformLogLevel and other Agent types can be placed in the generic module.
9. That the generic holder must remain completely unchanged before focused API review.
```

---

# 18. Final status

```text
Prompt 4 result: PASS_WITH_CRITICAL_CORRECTIONS
Binding state decision: RETAIN_GENERIC
Next prompt: Config Server Production Control
General implementation: NOT GO
Legacy mutation deletion: BLOCKED
```
