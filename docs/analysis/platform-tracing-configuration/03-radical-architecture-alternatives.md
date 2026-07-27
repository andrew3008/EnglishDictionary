# Radical Architecture Alternatives
## Corrected Repository-Grounded Report

## Review status

| Field | Value |
|---|---|
| Report status | **PASS_WITH_MAJOR_CORRECTIONS** |
| Intended use | Input for Prompt 4 — Binding Decision: VersionedStateHolder and Atomic State |
| Repository authority | `docs/research/hybrid-tracing-configuration-codebase-context.md` |
| Prior corrected audits | `01-external-industry-evidence.md`, `02-plan-to-code-contradiction-audit.md` |
| Implementation GO | **NOT GRANTED** |
| Legacy mutation deletion | **BLOCKED** |

The original Perplexity output contained useful material, but it did not actually produce eight independent end-to-end architectures:

- A1 and A8 were the same Agent-only architecture.
- A6 was a JMX transport choice, not a complete architecture.
- A2 confused retaining the generic CAS primitive with retaining all current per-domain state.
- A4 did not provide true atomicity.
- A7 was an intentionally rejected topology.
- A1 left the decisive state model unresolved.

This corrected report separates orthogonal design dimensions and presents three genuine finalist architectures.

---

# 1. Confirmed constraints

The alternatives must respect these repository facts and binding constraints:

1. Production topology is Agent-first.
2. SDK-only production is unsupported.
3. The three current policy holders execute in the Agent-side runtime; they are not separated from one another by different classloaders.
4. Spring and the Agent extension are separated by a classloader/JMX boundary.
5. `VersionedStateHolder<T>` is a working generic CAS primitive in `platform-tracing-otel`.
6. Current updates are atomic only per domain.
7. Export, propagation and diagnostics log-level controls are currently outside the versioned holders.
8. Config Server is the target production desired-state source, but it does not provide atomicity.
9. DEV_ACTUATOR is a local JMX adapter, not a separate state model.
10. Existing JMX ObjectNames remain unchanged.
11. The current partial control protocol is not a strategic foundation.
12. `scrubbing.rules-config` is Category B / restart-required.
13. No new Gradle module.
14. No public control-plane DTOs.
15. No long-lived dual writer architecture.
16. General implementation remains NOT GO.

---

# 2. Correct design dimensions

The original report mixed several independent dimensions.

## 2.1 Supported topology

Binding default:

```text
Agent-first production only
No Local SDK production parity
```

This is common to all viable finalists.

## 2.2 State model

This is the main unresolved decision for Prompt 4:

1. Generic aggregate holder.
2. Domain-specific aggregate state.
3. Coordinated existing domain holders.

## 2.3 Runtime mutation breadth

Independent from the state implementation:

1. Broad runtime-policy allowlist.
2. Narrow runtime-policy allowlist with most values structural/restart-required.

## 2.4 JMX transport shape

Common target option:

```text
one aggregate CompositeData operation
existing ObjectName preserved
old protocol/domain write operations removed after cutover
```

This is not a separate state architecture.

## 2.5 Source adapters

Common target adapters:

- Config Server bridge for production.
- DEV_ACTUATOR JMX endpoint for local development.
- HELM/env/-D for Agent bootstrap.

Adapters must converge on the same aggregate apply contract.

---

# 3. Alternatives rejected before final scoring

## R1. Spring-owned SDK/state architecture

**REJECTED.**

Reasons:

- contradicts Agent-first production;
- Agent starts before Spring;
- competing application OpenTelemetry beans are rejected;
- creates unsupported SDK ownership;
- violates established module/classloader boundaries.

## R2. Local SDK production parity

**REJECTED unless the architecture committee explicitly changes supported topology.**

Do not add:

- `LocalSdkTracingPolicyApplier`;
- mandatory `platform.tracing.sdk.*`;
- parity tests;
- Local SDK-specific control contracts.

## R3. Complete the current partial generic control protocol

**REJECTED.**

The current validator/applier domain mismatch is evidence against treating it as the strategic model.

Permitted reuse:

- JMX transport;
- Open Types;
- existing Control ObjectName, subject to baseline and contract review.

Rejected reuse:

- current generic protocol semantics;
- current partial handler/applier chain;
- old string-map policy model as the new domain model.

## R4. Aggregate writer implemented through legacy domain MBean setters

**REJECTED.**

This preserves sequential, externally visible mixed state.

## R5. Coordinated CAS across current holders with rollback

**REJECTED as a true-atomic target.**

A coordinator that performs:

```text
CAS sampling
CAS scrubbing
CAS validation
update export gate
update propagation gate
update log level
```

cannot provide a single linearization point for lock-free readers.

Rollback is another sequence of state changes and can itself race.

A global writer lock does not make readers atomic when readers do not acquire the same lock.

This variant may be useful only as a temporary implementation experiment, not as the target architecture.

## R6. Aggregate state plus retained mutable projection holders

**REJECTED as the target.**

The original A2 proposed:

```text
aggregate state CAS
    -> project into SamplerStateHolder
    -> project into ScrubbingPolicyHolder
    -> project into ValidationPolicyHolder
```

This creates two accepted-state representations and a projection window.

`RETAIN_GENERIC` must not mean "retain every existing holder."

## R7. Long-lived old and new writers

**REJECTED.**

Old mutation paths may exist only during a controlled cutover branch/slice. They must not remain supported after target activation.

---

# 4. Finalist F1 — Generic Aggregate Accepted State

## Summary

Retain `VersionedStateHolder<T>` as the generic CAS primitive and use one instance containing the complete accepted runtime policy.

```text
VersionedStateHolder<AcceptedTracingPolicy>
```

Current per-domain holders are deleted or converted into immutable views/helpers, not retained as separately mutable accepted state.

## Module placement

| Component | Existing module |
|---|---|
| `VersionedStateHolder<T>` | `platform-tracing-otel` |
| `AcceptedTracingPolicy` or `EffectiveTracingPolicy` | `platform-tracing-otel` |
| Agent aggregate apply handler | `platform-tracing-otel-javaagent-extension` |
| Open-Type codec/MBean adapter | `platform-tracing-otel-javaagent-extension` |
| Config Server bridge/snapshot builder | `platform-tracing-spring-boot-autoconfigure` |
| DEV_ACTUATOR endpoint | `platform-tracing-spring-boot-autoconfigure` |

No new module.

## State ownership

One Agent-side holder is the runtime truth:

```text
VersionedStateHolder<AcceptedTracingPolicy>
```

The accepted value contains all runtime-mutable Category A state required to switch coherently.

Potential policy domains:

- sampling;
- runtime-safe scrubbing subset;
- runtime-safe validation subset;
- exporter kill switch;
- platform trace-control propagation gate;
- Agent diagnostics log level.

The exact allowlist remains a separate decision.

## Runtime readers

Runtime consumers read the aggregate accepted value or immutable domain views derived directly from the same accepted object.

Examples:

```text
CompositeSampler
    -> policyState.current().sampling()

ScrubbingSpanProcessor
    -> policyState.current().scrubbing()

ValidatingSpanProcessor
    -> policyState.current().validation()

SafeSpanExporter
    -> policyState.current().exportEnabled()
```

There are no independently mutable domain holders.

## Atomicity

1. Read current accepted state/version.
2. Decode complete candidate.
3. Validate all runtime domains.
4. Compare optimistic base.
5. Commit one accepted policy/version through one generic holder CAS.
6. Publish diagnostics/audit after commit.

One CAS is the linearization point.

## LKG

The current accepted value is logical LKG.

Rejected, failed and stale attempts do not mutate it.

No duplicate full LKG payload is necessary unless a later state machine proves current and LKG must diverge.

## Advantages

- reuses a proven generic primitive;
- true single accepted-state commit;
- deletes three mutable domain holder abstractions;
- minimal new concurrency machinery;
- keeps state mechanism in `platform-tracing-otel`;
- straightforward stale/idempotency tests.

## Costs

- existing hot-path readers must be changed;
- aggregate policy may be larger than current domain states;
- careful immutable data design is required;
- generic holder API may need version metadata evolution.

## Main risk

`VersionedStateHolder<T>` may be too minimal for:

- runtime instance identity;
- accepted sequence;
- expected-base comparison;
- payload digest;
- result semantics.

If extending it makes the generic primitive tracing-policy-specific, F2 becomes preferable.

## Winning conditions

Choose F1 when the existing generic holder can support the required compare-and-commit semantics without:

- tracing-specific branches;
- control-protocol coupling;
- diagnostic/audit responsibility;
- a breaking generic API that is harder to understand than a domain-specific component.

---

# 5. Finalist F2 — Domain-Specific Aggregate Accepted State

## Summary

Create an internal tracing-specific accepted-state component.

Representative name:

```text
VersionedTracingPolicyState
```

The name is not binding; the domain-specific responsibility is.

The component may internally use:

- `AtomicReference`;
- the existing generic `VersionedStateHolder`;
- or extracted generic CAS utilities.

## Module placement

| Component | Existing module |
|---|---|
| `VersionedTracingPolicyState` | `platform-tracing-otel` |
| Immutable accepted policy model | `platform-tracing-otel` |
| Agent apply handler | `platform-tracing-otel-javaagent-extension` |
| Open-Type MBean/codec | `platform-tracing-otel-javaagent-extension` |
| Spring adapters | `platform-tracing-spring-boot-autoconfigure` |

## State ownership

The domain-specific component owns:

- current accepted runtime policy;
- `runtimeInstanceId`;
- `acceptedSequence`;
- `payloadDigest`;
- accepted source metadata that must switch atomically;
- optimistic compare-and-commit.

It does not own:

- attempt counters;
- audit history;
- failure strings;
- rejected candidate payloads;
- JMX codec.

Those belong to diagnostics/audit/adapters.

## Atomicity

One accepted-state CAS.

All runtime consumers read one committed policy or views derived from it.

## Disposition of existing generic primitive

Prompt 4 must choose one of these sub-options:

### F2a — Compose the generic primitive

```text
VersionedTracingPolicyState
    contains VersionedStateHolder<AcceptedTracingPolicy>
```

This preserves generic CAS mechanics while making policy semantics explicit.

### F2b — Replace the generic primitive

Delete `VersionedStateHolder<T>` only if:

- it has no remaining consumers;
- composition produces awkward duplicate version semantics;
- the domain state needs a fundamentally different compare-and-commit contract.

The original report incorrectly assumed F2 always requires deleting the generic primitive.

## Advantages

- explicit tracing-policy invariants;
- accepted sequence and optimistic-base semantics are discoverable;
- easier domain-specific validation and tests;
- avoids making a generic holder responsible for tracing concepts;
- clean ownership for one accepted state.

## Costs

- more new code than F1;
- may duplicate generic CAS mechanics;
- architecture fitness rules and ADRs require updates;
- risk of a large state "god object" if diagnostics/audit are included incorrectly.

## Main risk

The component can become an unnecessary wrapper around a still-valid generic primitive.

## Winning conditions

Choose F2 when the required policy semantics cannot be expressed cleanly through the current generic API without contaminating it with tracing-specific concepts.

---

# 6. Finalist F3 — Narrow Runtime Policy with Aggregate State

## Summary

Use the same one-state/one-writer architecture as F1 or F2, but reduce the runtime-mutable allowlist aggressively.

This is the genuine radical simplification candidate.

## Important correction

The original A5 retained simplified legacy setters and `RuntimeConfigApplier`. That is rejected.

F3 still uses:

- one aggregate writer;
- one accepted state;
- Config Server bridge;
- DEV_ACTUATOR adapter;
- no legacy domain writes after cutover.

The simplification is the size of Category A, not the control architecture.

## Candidate runtime Category A

A minimal candidate:

- sampling enabled;
- sampling ratio;
- exporter kill switch;
- optionally diagnostics log level.

Everything else is Category B until separately proven runtime-safe:

- scrubbing rule selection;
- validation policy;
- route rules;
- force/drop path rules;
- propagation gates;
- file-based rules config;
- keys/secrets;
- exporter topology;
- processor composition;
- queue/BSP settings;
- resource identity.

The exact list is not binding and must be decided from code/perf/security evidence.

## Advantages

- smaller atomic state;
- fewer runtime invariants;
- easier validation;
- less refresh-storm risk;
- easier rollout and incident response;
- lower test burden;
- larger deletion potential.

## Costs

- reduced runtime operational flexibility;
- more changes produce `restartRequired`;
- SRE may reject a narrow allowlist;
- current runtime-safe sampling/scrubbing/validation capabilities may be intentionally removed.

## Main risk

It may under-serve the confirmed centralized management requirement if SRE expects runtime changes beyond sampling/export.

## Winning conditions

Choose F3 if the committee distinguishes:

```text
centralized desired-state management
```

from:

```text
runtime hot mutation of every policy domain
```

and approves rollout for most structural/policy changes.

---

# 7. Common target transport and adapter architecture

The following is common to F1, F2 and F3 and should not be scored as a separate architecture.

## 7.1 JMX boundary

Preferred target:

```text
one aggregate applyConfigurationSnapshot(CompositeData) operation
```

Possible placement:

- reuse the existing Control ObjectName;
- replace old protocol operations on that MBean;
- preserve all ObjectNames;
- keep approved read attributes on existing domain MBeans.

Final operation placement requires the JMX baseline and contract decision.

## 7.2 Config Server adapter

```text
EnvironmentChangeEvent
    -> single-flight/coalescing
    -> full snapshot rebuild
    -> provenance validation
    -> aggregate Open-Type apply
```

Config Server does not call current domain setters.

## 7.3 DEV_ACTUATOR adapter

```text
typed local JMX command
    -> read current accepted state/version
    -> build full candidate snapshot
    -> aggregate Open-Type apply
```

No HTTP mutation representation.

## 7.4 HELM

Agent reads bootstrap configuration from env/-D.

Spring does not reapply the same HELM values post-start when the Agent owns bootstrap.

## 7.5 Capability

`CONFIG_SNAPSHOT_V1` means:

- aggregate MBean operation installed;
- codec version supported;
- handler ready to decode and apply.

It is published before the first application-side apply.

Initial snapshot success is a separate readiness condition.

---

# 8. Comparison matrix

| Criterion | F1 Generic aggregate | F2 Domain-specific aggregate | F3 Narrow aggregate |
|---|---:|---:|---:|
| True atomic accepted state | Strong | Strong | Strong |
| Reuses current generic primitive | Strong | Optional | Depends on F1/F2 base |
| Domain semantics clarity | Medium | Strong | Medium–strong |
| New concurrency code | Low | Medium | Low–medium |
| Current holder deletion | High | High | High |
| Runtime feature breadth | Broad | Broad | Narrow |
| Validation complexity | High | High | Lower |
| Hot-path migration | Medium | Medium | Lower |
| Config Server complexity | High | High | Medium |
| SRE flexibility | High | High | Lower |
| Risk of generic abstraction leakage | Medium | Low | Depends on base |
| Risk of domain god-object | Low–medium | Medium | Low |
| Architecture-fitness impact | Medium | Medium–high | Medium |
| Best fit | Existing generic API remains clean | Domain invariants justify dedicated state | Operational simplification approved |

No numeric LOC estimates are included because the original report's estimates were not grounded in repository diffs.

---

# 9. Required Prompt 4 decision frame

Prompt 4 must not choose among all original A1–A8 cards.

It must answer two binding questions.

## Question 1 — State primitive

Choose:

```text
RETAIN_GENERIC
```

or:

```text
REFACTOR_DOMAIN_SPECIFIC
```

Definitions:

### RETAIN_GENERIC

- one `VersionedStateHolder<AcceptedTracingPolicy>`;
- current per-domain mutable holders deleted or made non-owning;
- all runtime consumers read the one accepted policy;
- no aggregate-plus-projection dual state.

### REFACTOR_DOMAIN_SPECIFIC

- one tracing-specific accepted state;
- may compose the generic holder internally;
- delete generic holder only if it has no remaining value/consumer;
- all runtime consumers read one accepted policy.

## Question 2 — Runtime mutation breadth

Recommend:

- broad Category A;
- or narrow Category A.

Prompt 4 may advise, but final property classification is also informed by later namespace, security and SRE prompts.

---

# 10. Evidence required from Prompt 4

The next report must provide:

1. Exact current `VersionedStateHolder` API limitations.
2. Exact state fields in the single accepted CAS payload.
3. Exact fields outside CAS.
4. Current holder disposition:
   - DELETE;
   - MERGE;
   - RETAIN AS NON-OWNER.
5. Runtime reader migration:
   - `CompositeSampler`;
   - `ScrubbingSpanProcessor`;
   - `ValidatingSpanProcessor`;
   - export gate;
   - propagation gate;
   - diagnostics log-level reader.
6. `APPLIED`, `UNCHANGED`, `REJECTED`, `FAILED`, `STALE` transitions.
7. `restartRequired` as an independent result dimension.
8. Logical LKG semantics.
9. Optimistic concurrency pseudocode.
10. Hot-path benchmark plan.
11. Concurrency test plan.
12. Exact existing module placement.
13. ArchUnit/ADR changes.
14. Reversal condition.

---

# 11. Handoff contract for Prompt 4

The next model must treat these findings as accepted:

```text
1. A1 and A8 were duplicates and are merged into the common Agent-only topology.
2. A6 is a transport decision, not a separate state architecture.
3. SDK-only production parity is excluded.
4. Spring-owned state is rejected.
5. The current partial protocol is not the strategic domain model.
6. The aggregate writer must not call legacy domain MBean setters.
7. Coordinated multi-holder CAS/rollback is not truly atomic for lock-free readers.
8. RETAIN_GENERIC does not mean retaining three independently mutable domain holders.
9. A valid RETAIN_GENERIC design uses one VersionedStateHolder<AcceptedTracingPolicy>.
10. A valid REFACTOR_DOMAIN_SPECIFIC design uses one accepted tracing-policy state.
11. The three current holders execute in the Agent-side runtime, so an aggregate Agent-side state is physically possible.
12. Config Server and DEV_ACTUATOR are adapters to the same writer, not separate state architectures.
13. JMX/Open Types are the common boundary for all viable finalists.
14. scrubbing.rules-config is structural Category B.
15. CONFIG_SNAPSHOT_V1 indicates installed protocol support, not initial apply success.
16. No implementation GO is granted.
```

The next model must not inherit these claims from the original Prompt 3 output:

```text
1. That aggregate state plus three projected mutable holders is an acceptable atomic design.
2. That RETAIN_GENERIC requires retaining current domain holders.
3. That REFACTOR_DOMAIN_SPECIFIC necessarily deletes VersionedStateHolder.
4. That A1, A6 and A8 are three independent finalists.
5. That rollback across multiple CAS holders provides true atomicity.
6. That the restart-heavy alternative should retain RuntimeConfigApplier or legacy setters.
7. That rough LOC estimates are evidence.
8. That all currently mutable domains must remain Category A.
```

---

# 12. Final verdict

```text
Architecture alternative search: PASS_WITH_MAJOR_CORRECTIONS
Viable state finalists: F1 and F2
Viable product simplification: F3
Implementation GO: NOT GRANTED
Legacy deletion: BLOCKED
Next prompt: VersionedStateHolder and Atomic State
```

Prompt 4 should now make the binding `RETAIN_GENERIC | REFACTOR_DOMAIN_SPECIFIC` recommendation using the corrected definitions above.
