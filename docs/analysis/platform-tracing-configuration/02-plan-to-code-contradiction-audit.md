# Plan-to-Code Contradiction Audit
## Corrected Repository-Grounded Report

## Review status

| Field | Value |
|---|---|
| Report status | **PASS_WITH_MAJOR_CORRECTIONS** |
| Intended use | Input for Prompt 3 — Radical Architecture Alternative Search |
| Repository authority | `docs/research/hybrid-tracing-configuration-codebase-context.md` |
| External evidence input | `docs/research/perplexity/hybrid-config/01-external-industry-evidence.md` |
| Implementation GO | **NOT GRANTED** |
| Legacy mutation deletion | **BLOCKED** |

This corrected report removes the prompt text accidentally embedded in the original output and separates:

- **current-code absence**, which is expected for an unimplemented target;
- **actual design contradiction**;
- **unresolved implementation-GO decision**;
- **transition/cutover risk**.

The original report incorrectly classified several proposed-but-not-yet-implemented target types as contradictions merely because they do not exist in the repository. Absence is not a contradiction when the plan explicitly remains `NOT GO`.

---

# A. Overall consistency verdict

## Verdict

**The target direction is internally plausible but not yet implementation-ready.**

The plan does not contain six code-versus-plan contradictions merely because the following do not exist yet:

- `ConfigurationAuthority`;
- `platform.tracing.policy.*`;
- `TracingConfigServerBridge`;
- `CONFIG_SNAPSHOT_V1`;
- an aggregate runtime-policy state;
- production Config Client dependencies.

These are target components and decisions.

The real blockers are different:

1. The supported topology is Agent-first, while parts of the plan still imply Local SDK production parity.
2. The exact aggregate state ownership and atomic commit design are unresolved.
3. The production Boot/Spring Cloud dependency decision is unresolved.
4. The full 64-key namespace/property disposition is unresolved.
5. The current partial control protocol and five other legacy mutation paths do not yet have one proven cutover target.
6. The JMX baseline has not been captured.
7. `scrubbing.rules-config` must be formally classified as structural Category B.
8. Capability semantics and the first concrete Open-Type codec are not yet defined.

Therefore:

```text
Plan architecture direction: CONDITIONALLY CONSISTENT
Step 0 evidence: PASS
General implementation: NOT GO
Legacy mutation deletion: BLOCKED
```

---

# B. Analytical corrections to the original report

## B1. Missing target code is not itself a contradiction

The original report treated the absence of the authority enum, new namespaces, bridge and capability as runtime contradictions.

Correct classification:

| Target element | Current status | Correct classification |
|---|---|---|
| `ConfigurationAuthority` | Not implemented | Planned addition; design decision required |
| `platform.tracing.policy.*` | Not implemented | Breaking namespace migration; full map required |
| `TracingConfigServerBridge` | Not implemented | Planned production adapter; dependency decision required |
| `CONFIG_SNAPSHOT_V1` | Not implemented | Deferred protocol capability; define with codec |
| Aggregate state owner | Not implemented | Core architecture blocker |
| Config Client production dependency | Absent | Dependency-governance blocker |

A contradiction exists only when two target requirements cannot both be satisfied, or when the target design conflicts with a fixed repository/module invariant.

## B2. The state holders are not separated from one another by classloaders

The original report described three "classloader-separated holders." That is incorrect.

Confirmed topology:

- `VersionedStateHolder<T>` is defined in `platform-tracing-otel`.
- `SamplerStateHolder`, `ScrubbingPolicyHolder` and `ValidationPolicyHolder` are instantiated in the Java Agent extension plane.
- The three holders are in the Agent-side runtime, not in three independent classloaders.
- Spring is in a separate application classloader and reaches Agent-side state through JMX/Open Types.

Therefore, an Agent-side aggregate state is technically possible.

The real design question is:

- retain the generic primitive and introduce one aggregate tracing policy value;
- refactor it into a domain-specific state component;
- or retain per-domain state with a formally coordinated commit.

It is not physically impossible to place the state owner in `platform-tracing-otel` and instantiate/wire it from the Agent extension.

## B3. The aggregate writer must not fan out through legacy MBean setters

The original "radical correction" proposed:

```text
aggregate JMX operation
    -> fan-out to three holders
    -> rollback on partial failure
```

when expressed through current domain MBeans.

That is not the desired architecture.

Correct target:

```text
Spring adapter
    -> one CompositeData snapshot
    -> one Agent aggregate apply handler
    -> prepare and validate complete candidate
    -> one accepted-state commit
    -> runtime consumers read committed state
```

Legacy domain MBeans may remain as read contracts during cutover, but they are not the internal composition API of the new writer.

## B4. Capability support and successful apply are different facts

The original report proposed marking `CONFIG_SNAPSHOT_V1` only after the first successful apply.

That creates a circular dependency:

1. Spring checks capability before apply.
2. Agent marks capability only after apply.
3. Apply can never start.

Correct semantics:

- `CONFIG_SNAPSHOT_V1` means that the Agent extension has installed and can decode/apply protocol version 1.
- It is published when the aggregate writer and codec are installed successfully.
- Successful initial policy application is a separate readiness/diagnostic condition.
- Application readiness in `CONFIG_SERVER` mode may require both:
  - Agent capability present;
  - initial snapshot outcome accepted or unchanged.

## B5. The Config Server listener must not be conditioned on marker presence

The bridge may be conditionally created by:

- authority=`CONFIG_SERVER`;
- required Spring Cloud classes present.

At runtime/startup it validates:

- non-optional Config Server import succeeded;
- `configClient` marker exists;
- remote policy provenance is valid.

The marker should not be treated as a static bean-registration condition. Missing or ambiguous marker in authoritative mode is a fail-closed startup/readiness error.

---

# C. True implementation-GO blockers

## C1. Supported topology boundary

### Fact

Production supports Agent-first Spring Boot and Agent-only operation. SDK-only production is explicitly unsupported.

### Plan risk

`LocalSdkTracingPolicyApplier` and `platform.tracing.sdk.*` create a new production topology without a confirmed requirement.

### Required decision

Default recommendation:

```text
DROP production Local SDK parity from the hybrid configuration plan.
```

Keep test-only SDK fixtures outside the supported production control architecture.

### Deletion enabled

- remove `LocalSdkTracingPolicyApplier` from the target;
- remove mandatory `sdk.*` namespace;
- simplify authority and state ownership;
- reduce tests and failure modes.

### Reversal condition

Only restore this scope if the architecture committee explicitly approves Spring-owned SDK production support.

---

## C2. Aggregate state ownership and commit model

### Current fact

- Three versioned domains are independently committed.
- Export, propagation and diagnostics log level use separate mutable components.
- Cross-domain updates are sequential.

### Target requirement

One logical runtime policy update must not expose a mixed accepted state.

### Required decision

Choose one:

1. `RETAIN_GENERIC`:
   - keep `VersionedStateHolder<T>`;
   - use it with an aggregate immutable tracing policy state.

2. `REFACTOR_DOMAIN_SPECIFIC`:
   - evolve the primitive into a tracing-specific accepted state component.

3. Coordinated multi-component prepare/commit:
   - only if an aggregate hot-path state is demonstrably unsuitable.

### Required evidence

- concrete target class/module map;
- state-transition specification;
- hot-path benchmark;
- concurrency and stale-base tests;
- exact disposition of current holders.

---

## C3. Production Boot/Spring Cloud dependency governance

### Current fact

Production uses:

- Boot 3.5.5;
- `spring-cloud-context` 4.1.5;
- no Config Client.

Step 0B uses:

- Boot 3.5.6;
- Cloud BOM 2025.0.0;
- Config Client/Context 4.3.0.

### Required decision

Approve or reject:

- production Boot update;
- Cloud BOM alignment;
- optional Config Client dependency;
- conditional auto-configuration;
- starter-consumer dependency behavior.

### Required evidence

- production-classpath smoke test;
- dependency convergence;
- configuration-cache/build checks;
- starter dependency smoke;
- no accidental Config Server requirement in HELM mode.

---

## C4. Complete namespace and property disposition

### Current fact

64 current tracing properties exist. The target namespaces are plan-only.

### Required decision

Produce one complete table:

| Current key | Current owner | Target key or DELETE | Runtime/structural | Authority | Allowed sources | Final consumer |

### Required invariants

- no aliases;
- no long-lived dual readers;
- no duplicate defaults;
- no supported mixed old/new namespace state;
- `platform.tracing.policy.version` resource attribute must not be confused with configuration namespace;
- `platform.environment` remains an imported platform-wide signal.

---

## C5. Cutover from six mutation paths to one write path

### Current paths

1. RefreshScope.
2. Actuator HTTP POST.
3. Partial control protocol.
4. Direct domain JMX.
5. PerfAdminController.
6. Test/benchmark direct mutation.

### Required target

- one Agent aggregate write operation;
- Config Server adapter in production;
- JMX-only dev adapter;
- no HTTP mutation endpoint;
- no direct domain writes;
- perf uses the production Config Server path;
- benchmarks access state through explicit test/benchmark fixtures only.

### Required cutover rule

Do not remove active legacy paths before the aggregate path is executable and proven.

Do not retain both architectures beyond the controlled cutover phase.

---

## C6. JMX baseline

Before operation removal, capture:

- all eight ObjectNames;
- attributes;
- Open Types;
- operation signatures;
- notification metadata.

ObjectNames remain unchanged.

The approved delta must explicitly list:

- removed domain writes;
- added aggregate writer;
- preserved reads;
- deleted partial protocol operations.

---

## C7. `scrubbing.rules-config`

### Confirmed representation

- Agent-only;
- startup-only;
- path to Java Properties;
- classpath/file/filesystem.

### Binding recommendation

```text
Category B
runtime apply forbidden
remote desired comparison allowed
mismatch -> restartRequired
```

This decision should be closed before snapshot schema design.

---

## C8. Protocol capability and wire contract

Before adding `CONFIG_SNAPSHOT_V1`, define:

- concrete aggregate operation;
- CompositeType fields;
- required versus optional fields;
- protocol mismatch behavior;
- payload size limits;
- redaction;
- status Open Type;
- application/Agent codec tests.

Capability indicates installed support, not successful initial apply.

---

# D. Major transition risks

## D1. Partial control protocol overlap

The existing protocol is active but incomplete.

Risk:

- new aggregate operation is added while old protocol remains callable;
- two control semantics coexist;
- diagnostics and versioning diverge.

Required disposition:

- reuse transport/ObjectName only if justified;
- replace semantics;
- delete old protocol operations and API types after cutover.

Do not "finish" the partial generic protocol as a separate long-lived layer.

## D2. Ungated direct JMX

Direct domain setters are the current primary runtime mutation path and have no domain-layer production gate.

Risk:

- new authority rules are bypassed;
- Config Server and direct JMX race;
- state provenance becomes false.

Required disposition:

- preserve until aggregate path is proven;
- then remove all domain write operations;
- keep approved read attributes.

## D3. Fixed headers versus current startup overrides

Current code contains fixed `PlatformHeaders` constants and startup configuration for header names.

This is not a logical contradiction; it is a breaking migration.

Required target decision:

- remove header-name configuration;
- use `PlatformHeaders` everywhere;
- fail fast on removed header-name keys;
- update web, Kafka, tests and documentation in one supported cutover.

## D4. Diagnostics duality during cutover

Per-domain versions and last-updated source currently exist.

A new aggregate accepted sequence would temporarily duplicate them.

Required disposition:

- define temporary compatibility only inside the implementation branch;
- do not expose two supported sources of truth;
- approve the KEEP/REMOVE/MERGE/REWRITE matrix;
- remove obsolete per-domain configuration versions after aggregate cutover.

## D5. Perf path mismatch

M10/M10c/M10d currently mutate through `PerfAdminController -> JMX`.

Target production mutation is Config Server.

Required action:

- rewire performance scenarios to the production bridge;
- do not use DEV_ACTUATOR for production performance proof;
- preserve P5 and config-storm behavior where still relevant.

---

# E. Illegal target states

| ID | Illegal state | Failure |
|---|---|---|
| IS-1 | CONFIG_SERVER authority active without Config Client/import | Fail-closed startup/readiness error |
| IS-2 | New policy namespace supplied while old readers remain authoritative | Empty/default or split effective policy |
| IS-3 | Direct JMX setters remain callable after Config Server authority cutover | Multiple writers and false provenance |
| IS-4 | New aggregate operation calls legacy domain setters sequentially | Mixed accepted state |
| IS-5 | Capability required before Agent publishes support, but support published after first apply | Readiness deadlock |
| IS-6 | Structural desired state differs on initial Config Server load | Application must not become ready |
| IS-7 | Runtime structural drift marks every replica NOT_READY simultaneously | Fleet-wide removal from service |
| IS-8 | Rules file path treated as runtime policy | Unsafe reload or inconsistent rule compilation |
| IS-9 | Local SDK parity implemented without supported topology | Dead code and unsupported behavior |
| IS-10 | Old and new namespace readers remain supported together | Duplicate defaults and ownership |
| IS-11 | Source revision read through winning Environment lookup | Marker spoofing |
| IS-12 | Secret/raw rules payload included in JMX/audit | Information disclosure |

---

# F. Corrected ownership matrix

| Concern | Current owner | Recommended target owner | Notes |
|---|---|---|---|
| Agent runtime policy truth | Three Agent holders plus mutable gates | One Agent-side accepted policy state | Exact generic/domain-specific choice pending |
| Generic CAS primitive | `platform-tracing-otel` | Retain or evolve in `platform-tracing-otel` | Instantiated/wired by Agent extension |
| Aggregate JMX operation | None; partial protocol exists | `platform-tracing-otel-javaagent-extension` MBean adapter | Open Types only |
| Snapshot builder | None | `platform-tracing-spring-boot-autoconfigure` | Internal |
| Config Server bridge | None | `platform-tracing-spring-boot-autoconfigure` | Conditional |
| DEV_ACTUATOR endpoint | None | `platform-tracing-spring-boot-autoconfigure` | JMX-only |
| Readiness capability | Existing Agent readiness | Existing readiness descriptor extended with concrete support | No new module |
| Provenance classifier | Step 0B test implementation | Spring autoconfigure internal component | Production implementation after GO |
| Diagnostics | Multiple MBeans/Actuator | Agent state + diagnostics/audit, read through JMX | Matrix pending |
| Local SDK production applier | None | **Do not add by default** | Unsupported topology |

---

# G. Proposals to remove from the target plan

1. `LocalSdkTracingPolicyApplier` as a mandatory production component.
2. Mandatory `platform.tracing.sdk.*` namespace without approved SDK-only support.
3. New aggregate writer implemented over legacy domain MBean setters.
4. Long-lived coexistence of old control protocol and new snapshot protocol.
5. Runtime reload of `scrubbing.rules-config`.
6. Compatibility aliases for old property names.
7. Mechanical JMX ObjectName rename.
8. HTTP mutation API in the target.
9. Any assumption that Config Server itself supplies atomicity.
10. Any requirement to publish `CONFIG_SNAPSHOT_V1` after rather than before first apply.
11. Any generic schema/policy framework or new Gradle module.

---

# H. Mandatory corrections before implementation GO

| Gate | Required artifact/decision |
|---|---|
| Supported topology | Explicitly remove or approve Local SDK production |
| State architecture | RETAIN_GENERIC or REFACTOR_DOMAIN_SPECIFIC |
| State placement | Existing module and Agent wiring |
| Atomicity | Prepare/validate/commit specification |
| Dependencies | Production Boot/Cloud/Config Client decision |
| Properties | Full 64-key disposition |
| Authority | Binding and source truth table |
| Protocol | Concrete CompositeData schema and capability semantics |
| JMX | Baseline fixture and approved delta |
| Rules config | Category B ADR/decision |
| Diagnostics | Full retention/consolidation matrix |
| Security | DEV_ACTUATOR and JMX gates |
| Perf | Config Server M10/M10c/M10d plan |
| Cutover | One-writer transition and legacy deletion sequence |
| Governance | Explicit implementation GO |

---

# I. Handoff contract for Prompt 3

The next model must treat these findings as accepted:

```text
1. Missing target classes are not contradictions by themselves.
2. The plan remains NOT GO because key architecture and dependency decisions are unresolved.
3. SDK-only production is unsupported; Local SDK parity should be excluded unless explicitly approved.
4. The three existing versioned holders are in the Agent-side runtime; they are not separated from each other by classloaders.
5. An aggregate Agent-side state is physically possible.
6. The aggregate writer must not call legacy domain MBean setters.
7. Config Server is a desired-state source, not an atomicity mechanism.
8. The current partial protocol is not a strategic foundation.
9. JMX/Open Types remain the preferred cross-classloader transport.
10. Existing ObjectNames remain unchanged.
11. scrubbing.rules-config is structural Category B.
12. CONFIG_SNAPSHOT_V1 means protocol support installed; initial apply success is separate.
13. Production Config Client/BOM adoption is an implementation-GO blocker.
14. One coherent namespace cutover is required; no aliases or long-lived dual readers.
15. JMX baseline and diagnostics matrix are mandatory before deletion.
```

The next model must not inherit these incorrect conclusions from the original Prompt 2 output:

```text
1. That the absence of an enum/class/property binding is itself a plan contradiction.
2. That the three Agent state holders are classloader-separated from one another.
3. That an aggregate Agent-side state is physically impossible.
4. That the only radical correction is sequential fan-out with rollback through domain MBeans.
5. That CONFIG_SNAPSHOT_V1 should be published only after first successful apply.
6. That the Config Server listener should only be registered when a marker already exists.
7. That target implementation code should be added before explicit implementation GO.
```

Prompt 3 should generate alternatives from the corrected blocker set, not from the original report's false impossibility claims.

---

# J. Final verdict

```text
Current plan direction: CONDITIONALLY CONSISTENT
Step 0 evidence: PASS
Implementation GO: NOT GRANTED
Legacy deletion: BLOCKED
Next analysis: Radical Architecture Alternative Search
```

The architecture can proceed to alternative generation, but the alternatives must resolve the true blockers listed in this report rather than treating unimplemented target components as contradictions.
