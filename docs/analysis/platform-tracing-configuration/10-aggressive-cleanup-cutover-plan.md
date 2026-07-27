# Aggressive Pre-Production Cleanup and Cutover
## Corrected Repository-Grounded Plan

## Review status

| Field | Value |
|---|---|
| Report status | **PARTIAL_PASS_WITH_CRITICAL_CORRECTIONS** |
| Target architecture | One Agent-side accepted state, one private aggregate writer |
| Supported dual architecture | **Forbidden** |
| Temporary unreleased coexistence | Allowed only for proof, never dual-write |
| Implementation GO | **NOT GRANTED** |
| Legacy deletion GO | **BLOCKED** |
| Property/env disposition | **Incomplete and blocking** |
| JMX baseline | **Not captured and blocking deletion** |
| Private MBeanServer spike | **Critical pre-GO gate** |
| Intended handoff | Prompt 11 — Multi-Criteria Architecture Scoring |

The original report correctly aimed to eliminate all six mutation paths and avoid compatibility aliases. It nevertheless contains several material errors:

1. It places `AcceptedTracingPolicyState` in `platform-tracing-otel`, contradicting the accepted Agent-specific ownership.
2. It sends HELM bootstrap through the aggregate application path, although HELM state is read by the Agent before Spring exists.
3. It omits the private-process-local `MBeanServer` feasibility spike from Wave 0.
4. It calls state and `scrubbing.rules-config` decisions unresolved even though the corrected analyses already selected `RETAIN_GENERIC` conditionally and Category B.
5. It proposes deleting “all ~64 current keys”; many Spring application-owned and Agent structural keys remain supported.
6. It calls a wildcard summary a property migration plan even though the exact leaf-key/env disposition is still missing.
7. It assigns `PerfAdminController` and several protocol types to modules without sufficient repository evidence.
8. It treats a large single PR as atomicity. Git/PR size does not create architectural atomicity.
9. It allows independent rollback of deletion waves after namespace/state cutover, which can restore incompatible readers and writers.
10. It estimates files, APIs, tests and LOC without a repository diff or symbol inventory.
11. It lists proposed target types as existing classes to delete.
12. It says stale documentation can be deleted immediately even though current behavior still exists.
13. It allows `AcceptedTracingPolicyState` in either the generic or extension package through a fitness rule, weakening the decided ownership.
14. It treats all current mutation properties as deletions rather than distinguishing delete, rename and retained ownership.

This corrected plan defines evidence gates, an unreleased cutover train, exact disposition categories and measurable—not invented—deletion accounting.

---

# 1. Cutover principles

## 1.1 No supported dual architecture

No published or supported artifact may contain two active runtime-policy writers.

Forbidden supported state:

```text
Config Server aggregate writer
    plus RefreshScope writer
    plus Actuator HTTP writer
    plus domain JMX setters
```

## 1.2 Temporary coexistence on an unreleased branch

During implementation, old and new code may coexist only when:

- the new writer is inaccessible from supported runtime configuration;
- tests invoke the new path explicitly;
- there is no dual-write;
- hot-path readers have exactly one active owner;
- no intermediate artifact is released or documented as supported;
- every temporary component has a removal wave and executable gate.

## 1.3 One linearization point

The target accepted runtime state is committed through one Agent-side CAS.

No:

- per-domain fan-out;
- rollback across holders;
- aggregate-plus-projection mutable state;
- writer on the platform `MBeanServer`.

## 1.4 Cutover unit

The cutover unit is a coherent **release train**, not necessarily one giant PR.

A release train may contain several reviewable commits/PRs on an unreleased integration branch, but the final supported artifact must simultaneously contain:

- new authority and namespace contract;
- new Agent state/readers;
- private aggregate writer;
- Config Server and DEV adapters;
- read-only platform JMX;
- no legacy writers;
- no old property readers or aliases;
- updated tests, deployment examples and documentation.

---

# 2. Pre-GO evidence gates

These activities are evidence collection or design closure. They do not require production implementation GO.

## Gate E1 — JMX baseline and consumer inventory

Capture:

```text
jmx-management-contract-baseline.json
```

Including:

- all eight ObjectNames;
- attributes and Open Types;
- readable/writable flags;
- operations and signatures;
- notifications;
- sample shapes;
- known runbooks, dashboards and clients.

No JMX member deletion before this gate closes.

## Gate E2 — Private `MBeanServer` feasibility spike

Prove:

- Agent creates one process-local private server;
- Spring discovers exactly one server;
- private aggregate operation works across classloaders;
- platform remote JMX cannot see the writer;
- platform server is read-only after target registration;
- no lifecycle leak on restart/tests;
- Agent-only topology remains valid;
- `mode=DISABLED` does not discover or mutate the writer.

This is a security-critical architecture gate.

## Gate E3 — Exact `VersionedStateHolder` API review

The binding recommendation is already:

```text
RETAIN_GENERIC
```

The gate confirms that the actual holder API can support:

- one aggregate Agent-side accepted-state value;
- expected-base CAS;
- stale conflict semantics;
- no tracing-specific pollution of the generic primitive.

Reversal remains possible only under the conditions defined in corrected Prompt 4.

## Gate E4 — Complete property and environment disposition

Produce:

```text
docs/research/tracing-configuration-key-disposition.md
```

One row per exact property and environment name—no wildcard rows.

Required columns:

| Current key/env | Declaration | Readers | Consumer | Current default owner | Target/DELETE | Target owner | A/B | Authority/source | Tests |
|---|---|---|---|---|---|---|---|---|---|

This gate replaces all approximate property counts in the original report.

## Gate E5 — Final Category A and safety bounds

Approve:

- exact runtime-mutable leaf keys;
- exact structural leaf keys;
- collection/string limits;
- secret classification;
- canonical normalization/digest contract.

Already binding:

```text
scrubbing.rules-config = Category B / startup-only
```

## Gate E6 — Production Config Server dependency and refresh delivery

Approve:

- Boot/Cloud versions;
- Config Client dependency policy;
- production refresh trigger;
- dependency/classpath smoke tests.

## Gate E7 — Diagnostics/JMX approved delta

Using the baseline, approve exact:

- KEEP;
- REMOVE;
- MERGE;
- REWRITE;

for every read attribute and metric.

The conceptual diagnostics model is already accepted; exact members remain baseline-gated.

## Gate E8 — Explicit architecture implementation GO

Only after E1–E7 are reviewed may target-path implementation begin.

---

# 3. Dependency-aware implementation train

## Train T1 — Agent accepted-state foundation

Module:

```text
platform-tracing-otel-javaagent-extension
```

Add internal:

- immutable effective runtime policy;
- accepted-state envelope;
- one `VersionedStateHolder<AcceptedTracingPolicyState>` instance;
- aggregate validator;
- apply outcome model;
- structural drift status;
- apply diagnostics;
- 32-entry audit ring.

Retain in:

```text
platform-tracing-otel
```

- generic `VersionedStateHolder<T>`;
- only genuinely reusable generic state contracts.

Do not move Agent policy types or JMX diagnostics into the generic module.

### T1 proof

- exact holder API tests;
- one-CAS concurrency;
- deep immutability;
- LKG semantics;
- no independent mutable domain owner in target wiring;
- hot-path benchmarks.

Old production readers remain active on the unreleased branch until T2 switching; no dual-write.

## Train T2 — Private internal control boundary

In the Agent extension:

- create private process-local `MBeanServer`;
- register versioned aggregate writer and internal accepted-state read;
- publish protocol support capability when installed;
- create read-only platform-server projection;
- implement strict bounded Open-Type codecs.

In Spring autoconfigure:

- add private-server locator/client;
- no dependency on Agent Java types;
- no platform-server write fallback.

### T2 proof

- isolation spike converted to permanent E2E;
- protocol mismatch tests;
- malformed/oversized payload corpus;
- remote platform JMX negative write test;
- Agent-only and multiple-context lifecycle tests.

## Train T3 — Migrate Agent runtime readers

Rewrite:

- sampler;
- scrubbing processor;
- validation processor;
- exporter gate, if in Category A;
- trace-control-header gate, if in Category A;
- diagnostics level reader, if in Category A.

Each reads the one accepted aggregate state or immutable view from it.

After every target reader is active in tests:

- remove mutable ownership from domain holders;
- do not project from aggregate state into mutable holders.

### T3 proof

- no target reader uses old holders;
- hook-level consistency documented;
- performance gates pass;
- behavior characterization remains correct.

## Train T4 — Build application-side adapters

### Config Server

Add:

- conditional bridge;
- provenance classifier;
- complete desired-state builder;
- generation/dirty worker;
- initial readiness coordination;
- private aggregate client invocation.

### DEV_ACTUATOR

Add:

- typed `@JmxEndpoint`;
- posture guard;
- read-modify-validate command service;
- no raw snapshot command;
- no HTTP route.

### HELM

Do not invoke the Spring aggregate writer.

HELM behavior:

```text
Agent premain/bootstrap
    -> parse env/-D
    -> construct initial accepted state directly inside Agent
```

Spring observes read-only state.

### T4 proof

- HELM works without Config Client or Spring write;
- CONFIG_SERVER initial and refresh E2E;
- DEV JMX-only E2E;
- mode/authority truth table;
- no HTTP mutation;
- no Config Server/dev adapter active together.

## Train T5 — Production-path performance and failure proof

Rewire:

- M10;
- M10c;
- M10d;
- P5/hot-path tests;

to the actual Config Server and private-writer path.

Keep separate local DEV tests.

Required:

- event storm converges to latest state;
- bounded audit/diagnostics;
- runtime failure retains LKG;
- initial failure blocks readiness;
- no platform JMX writer;
- secret canary passes.

## Train T6 — Coherent contract cutover

On the unreleased integration branch:

1. switch to final property/env names;
2. update Agent bootstrap parser and Spring binding/guard together;
3. update Helm/Config Server examples and fixtures;
4. activate target authority postures;
5. make platform JMX read-only;
6. remove all old mutation paths;
7. remove old state owners;
8. remove old property/env readers;
9. remove compatibility-free obsolete tests/docs;
10. verify zero legacy references.

No release is created between these steps.

## Train T7 — Final simplification and governance

- minimize duplicate diagnostics;
- delete static-zero/dead code;
- update ADRs and `SUPPORTED.md`;
- regenerate API/JMX/property inventories;
- compute deletion scorecard from the actual diff;
- obtain explicit production/deletion GO.

---

# 4. Binding classification of mandatory candidates

## 4.1 Spring/autoconfiguration control paths

| Candidate | Decision | Trigger | Replacement |
|---|---|---|---|
| `TracingRefreshScopeAutoConfiguration` | **DELETE AFTER NEW PATH PROVEN** | Config Server bridge passes initial/refresh E2E | `TracingConfigServerBridge` |
| `RuntimeConfigApplier` | **DELETE AFTER NEW PATH PROVEN** | Aggregate client is sole application writer | Config Server/dev adapters → private writer |
| `TracingActuatorEndpoint` write operations | **DELETE AFTER DEV PATH PROVEN** | JMX-only typed endpoint passes no-HTTP tests | `TracingDevControlJmxEndpoint` |
| `TracingActuatorEndpoint` read operations | **REWRITE** | Diagnostics model implemented | read-only status model |
| `PlatformTracingJmxClient` per-domain mutators | **DELETE AFTER PRIVATE CLIENT PROVEN** | all adapters use aggregate client | private aggregate client |
| `PlatformTracingJmxClient` read methods | **REWRITE/SPLIT** | platform JMX read contract approved | read-only platform client |
| `RuntimePolicyControlHandler` | **DELETE AFTER AGGREGATE HANDLER PROVEN** | old protocol has no caller | Agent aggregate handler |
| `JmxRuntimePolicyApplier` | **DELETE AFTER AGGREGATE HANDLER PROVEN** | no per-domain fan-out remains | private aggregate client/handler |
| Config Server bridge/source classifier | **NEW, RETAIN** | after GO | production adapter |
| DEV command service/JMX endpoint | **NEW, RETAIN** | after GO | local adapter |

Exact module/path must be confirmed from repository symbols before editing. Do not copy module guesses from the original report.

## 4.2 JMX and protocol

| Candidate | Decision | Notes |
|---|---|---|
| Current platform `PlatformControlProtocolMBean` write operations | **DELETE AFTER PRIVATE WRITER PROVEN** | platform ObjectName retained as read-only projection |
| Current platform Control read members | **REWRITE per baseline** | accepted-state metadata only |
| Private aggregate writer MBean | **NEW, RETAIN INTERNAL** | private server only; not SRE contract |
| `TracingControlProtocol` old string-coercion types | **DELETE AFTER NEW CODEC PROVEN** | exact class/module inventory required |
| New aggregate Open-Type codec | **NEW, RETAIN INTERNAL** | symmetric, versioned, bounded |
| Direct domain JMX setters | **DELETE AFTER TARGET CUTOVER** | all sampling/scrubbing/validation/export/propagation/log writes |
| Existing eight ObjectNames | **RETAIN** | names unchanged; members may change after baseline |
| JMX baseline fixture | **NEW, RETAIN AS CONTRACT EVIDENCE** | approved-delta test |

Do not create public control DTOs in `platform-tracing-api`.

## 4.3 State ownership

| Candidate | Decision |
|---|---|
| `VersionedStateHolder<T>` | **RETAIN** subject to focused API gate |
| `SamplerStateHolder` | **DELETE AFTER READER CUTOVER** |
| `ScrubbingPolicyHolder` | **DELETE AFTER READER CUTOVER** |
| `ValidationPolicyHolder` | **DELETE AFTER READER CUTOVER** |
| export mutable singleton/gate | **REWRITE to aggregate-state read; delete independent mutable setter** if final Category A |
| propagation mutable singleton/gate | **REWRITE to aggregate-state read; delete independent mutable setter** if final Category A |
| diagnostics log mutable singleton | **REWRITE to aggregate-state read; delete independent mutable setter** if final Category A |
| per-domain runtime DTOs | **MERGE or DELETE** based on whether they remain immutable views |
| Agent accepted policy state | **NEW, RETAIN in Agent extension** |

`RETAIN_GENERIC` is no longer an open binary decision. Only the focused API validation remains.

## 4.4 Diagnostics

| Candidate | Decision |
|---|---|
| `ConfigReloadMetrics` | **MERGE/REWRITE** into bounded apply and delivery metrics |
| `ConfigAuditTrail` | **REWRITE** as 32-entry Agent metadata ring |
| `InvalidConfigCount` | **REWRITE after producer inventory** |
| per-domain config versions | **DELETE after baseline/cutover** |
| per-domain `lastUpdatedSource` | **DELETE after baseline/cutover** |
| runtime behavior metrics | **RETAIN/SEMANTIC REVIEW** |
| static-zero partial-protocol metrics | **DELETE after baseline confirms no consumer** |
| Spring bridge delivery diagnostics | **NEW, RETAIN** |
| structural drift status | **NEW, RETAIN** |

Do not merge Spring delivery evidence into Agent runtime truth.

## 4.5 Perf and test-only paths

| Candidate | Decision |
|---|---|
| `PerfAdminController` | **REWRITE SCENARIOS, THEN DELETE MUTATION PATH** |
| M10/M10c/M10d legacy JMX route | **REWRITE** to Config Server/private writer |
| benchmark direct state setup | **RETAIN only as explicit benchmark fixture** |
| tests of legacy mutation behavior | **REPLACE, THEN DELETE** |
| dead `platform.tracing.sdk.mode` fixture | **DELETE NOW if repository search confirms no production consumer** |
| proposed `LocalSdkTracingPolicyApplier` | **REMOVE FROM PLAN; delete class only if it actually exists** |

A plan-only type is not a file deletion.

## 4.6 Documentation

| Documentation | Decision |
|---|---|
| Current docs describing still-active old paths | **RETAIN until cutover, then REWRITE/DELETE in T6** |
| Dead SDK-only claims/fixtures | **DELETE NOW after repository confirmation** |
| New authority/property/JMX/security docs | **NEW/REWRITE during T6** |
| Historical research reports | **RETAIN as evidence, mark superseded where necessary** |
| Current operational runbooks | **REWRITE from approved JMX delta** |

---

# 5. Property and environment cutover

## 5.1 Do not delete all current tracing keys

The correct categories are:

```text
DELETE
RENAME
KEEP
MOVE OWNERSHIP
EXTERNAL STANDARD KEEP
```

Many current keys remain as:

- Spring application-owned configuration;
- Agent structural/bootstrap configuration;
- imported platform signals;
- OTel standard configuration.

## 5.2 Already accepted removals

Subject to exact inventory:

- dead `platform.tracing.sdk.mode`;
- configurable platform header-name keys;
- HTTP mutation gate;
- old partial-control mutation gate;
- obsolete old names replaced in the coherent namespace cutover.

## 5.3 Accepted new/renamed families

- `platform.tracing.policy.*`;
- `platform.tracing.javaagent.*`;
- `platform.tracing.configuration.authority`;
- `platform.tracing.dev-control.*`.

No `platform.tracing.sdk.*`.

## 5.4 Cutover invariants

- no old/new aliases;
- no dual readers;
- no hidden Spring defaults for Agent policy;
- Agent and Spring/env mappings switch together;
- Config Server complete manifest updated with code;
- authority/bootstrap keys cannot be sourced remotely;
- old env names are removed with old property names;
- fail-fast diagnostics list every legacy key detected.

The exact cutover cannot be implemented until Gate E4 is complete.

---

# 6. Test replacement order

Tests are not deleted merely because the plan dislikes the old architecture.

For every legacy behavior:

```text
add target test
    -> prove target behavior
    -> switch production wiring
    -> delete legacy test and implementation together
```

## Required target suites

### State

- one-CAS atomicity;
- stale/idempotent outcomes;
- deep immutability;
- LKG;
- hook-level consistency.

### Private control boundary

- private server discovery;
- writer absent from platform server;
- exact codec;
- malformed/oversized payloads;
- protocol capability/version.

### Authorities

- HELM bootstrap with no Spring write;
- Config Server initial/refresh;
- DEV JMX typed commands;
- invalid mode/authority combinations;
- no HTTP mutation.

### Security

- secret canary;
- local override fail-closed;
- remote platform JMX cannot write;
- disabled facade does not mutate Agent;
- rules path lifecycle.

### Diagnostics

- read-only platform contract;
- audit capacity 32;
- bounded metric labels;
- readiness/drift matrix;
- baseline approved delta.

### Performance

- actual production Config Server path;
- refresh storms;
- hot-path readers;
- no audit/diagnostic unbounded growth.

---

# 7. Architecture fitness and build gates

Use executable rules grounded in real symbols. Do not paste speculative ArchUnit APIs.

## Required invariants

1. `platform-tracing-api` contains no control-plane DTOs.
2. Spring autoconfigure has no compile dependency on Agent extension types.
3. Exactly one target aggregate writer is registered, only on the private server.
4. Platform MBeanServer tracing MBeans have no write operations after cutover.
5. No production code calls legacy domain setters.
6. No independently mutable policy holder remains.
7. `VersionedStateHolder<AcceptedTracingPolicyState>` is instantiated only by Agent wiring.
8. Agent-specific accepted policy and diagnostics remain outside `platform-tracing-otel`.
9. No HTTP tracing mutation operation exists.
10. No production SDK-only applier or `sdk.*` binding exists.
11. No compatibility alias registry or dual property reader exists.
12. No platform header literals/configurable names outside `PlatformHeaders`.
13. Metric labels use only approved bounded enums.
14. No raw secret crosses projection/codec/audit/log boundaries.
15. Gradle project count remains unchanged.
16. Complete key/env disposition has one row per discovered name.
17. Legacy symbol/reference scan returns zero after T6.

Implement with the appropriate combination of:

- ArchUnit;
- reflection/JMX contract tests;
- source/resource scans;
- Spring context tests;
- Gradle verification tasks;
- process E2E.

---

# 8. Rollback strategy for a pre-production product

## 8.1 Before contract cutover

T1–T5 can be reverted independently because the supported old artifact has not changed and the new path is unreleased.

## 8.2 After coherent T6 cutover

Do not revert only one deletion/state/property commit.

A partial revert could restore:

- old writer without old properties;
- old reader with new aggregate state;
- platform JMX write without its guard;
- old tests against new wire contract.

Rollback unit after T6 is:

```text
the entire cutover release train
```

including:

- code;
- Agent distributive;
- BOM/starter versions;
- Helm values;
- Config Server manifest;
- documentation and runbooks.

Because the solution is pre-production, the preferred rollback is to the previous known branch/tag, not runtime compatibility code.

## 8.3 No terminal “no rollback” wave

Property/docs/test cleanup is part of the cutover artifact and must be reversible through the full release-train revert/tag.

---

# 9. Deletion scorecard

The original numerical estimates are not trustworthy without repository analysis.

## 9.1 Required measured scorecard

After T6, generate from Git and symbol inventories:

| Metric | Measurement method | Result |
|---|---|---|
| Files deleted | `git diff --summary <base>...<cutover>` | TBD |
| Production Java types deleted | source/symbol scan | TBD |
| Public/management operations removed | API/JMX baseline delta | TBD |
| Exact properties/env names deleted | disposition table | TBD |
| Properties/env names renamed | disposition table | TBD |
| Tests deleted | Git diff by test source set | TBD |
| Tests rewritten | explicit mapping table | TBD |
| Production LOC added/deleted | diff excluding generated/research files | TBD |
| Test LOC added/deleted | diff | TBD |
| Legacy mutation entry points | before/after inventory | target: 6 → 0 |
| Runtime policy writers | before/after inventory | target: many → 1 private writer |
| Mutable policy owners | before/after inventory | target: multiple → 1 |
| Platform JMX write operations | contract diff | target: >0 → 0 |
| New Gradle modules | settings diff | target: 0 |

## 9.2 Net-code-growth acceptance

Net growth is allowed only when each new long-lived component:

- replaces named old components;
- has a confirmed consumer;
- prevents a named failure;
- has tests;
- remains in an existing module.

Do not set a fabricated `−2500 to −3500 LOC` target. Prefer deletion quality and ownership reduction over raw line counts.

---

# 10. Temporary code that must not be created

Automatic rejection:

1. Old-to-new property alias.
2. Old-to-new API adapter.
3. Dual write to aggregate and domain holders.
4. Aggregate state projected into independently mutable holders.
5. Aggregate writer on platform `MBeanServer`.
6. Platform-server fallback if private server discovery fails.
7. Config Server bridge falling back silently to HELM.
8. Spring aggregate write in HELM authority.
9. Public control DTOs.
10. Local SDK production applier.
11. New Gradle module.
12. Generic configuration/security/policy framework.
13. Multi-holder rollback coordinator.
14. Raw snapshot operation on developer `@JmxEndpoint`.
15. HTTP mutation route.
16. Runtime `rules-config` or HMAC key mutation.
17. Duplicate LKG payload.
18. New Agent `DEGRADED` readiness state.
19. Compatibility-only per-domain version projection without a named approved consumer.
20. Unbounded audit/violation/payload structures.
21. High-cardinality metric labels.
22. `TODO remove later` without a train/gate and executable removal check.
23. A release containing both old and new supported writers.
24. Partial rollback instructions that restore only one side of the namespace/state/writer cutover.

---

# 11. Corrected blocker status

| Decision/gate | Status |
|---|---|
| Agent-first/no SDK parity | Resolved |
| `RETAIN_GENERIC` direction | Resolved conditionally; focused API check remains |
| `scrubbing.rules-config` Category B | Resolved |
| Authority prefix taxonomy | Resolved |
| Private writer architecture | Selected; feasibility spike open |
| Platform JMX read-only target | Resolved |
| Diagnostics conceptual model | Resolved |
| Exact JMX member disposition | Baseline-blocked |
| Exact property/env disposition | Open/blocking |
| Final Category A and limits | Open |
| Config Server dependencies/refresh delivery | Open |
| Security/secret projection implementation | Open |
| Target-path E2E/perf | Open |
| Explicit implementation GO | Not granted |
| Legacy deletion GO | Blocked |

The original report's “five blockers remaining” list is incomplete and contains already-resolved items.

---

# 12. Handoff contract for Prompt 11

Prompt 11 must treat these findings as accepted:

```text
1. The target has one Agent-side accepted state and one private aggregate writer.
2. HELM initializes accepted state directly in Agent bootstrap; Spring does not apply HELM policy through JMX.
3. Agent policy and operational diagnostics live in the Agent extension, not the generic platform-tracing-otel module.
4. VersionedStateHolder is retained subject to exact API verification.
5. Current per-domain holders are deleted after target readers are proven.
6. Platform MBeanServer becomes read-only; private MBeanServer owns writes.
7. No supported artifact contains old and new writers together.
8. Temporary unreleased coexistence is permitted only without dual-write or target activation.
9. The coherent cutover is a release train, not necessarily one giant PR.
10. Exact JMX baseline and consumer inventory precede member deletion.
11. Private MBeanServer feasibility precedes implementation GO.
12. The complete exact property/env disposition is still blocking.
13. Not all 64 current keys are deleted; each is DELETE, RENAME, KEEP, MOVE OWNERSHIP or EXTERNAL KEEP.
14. No aliases or dual readers survive cutover.
15. Config Server and DEV adapters use the same private writer.
16. HTTP mutation, RefreshScope mutation, partial protocol and domain setters are removed after proof.
17. Diagnostics are split into accepted truth, structural drift, Agent attempts/audit and Spring delivery evidence.
18. Audit capacity remains 32 metadata-only entries.
19. Rollback after contract cutover reverts the whole release train.
20. Deletion counts and LOC must be measured from the actual diff, not estimated.
21. Implementation GO and deletion GO remain ungranted.
```

Prompt 11 must not inherit these claims from the original Prompt 10 output:

```text
1. That AcceptedTracingPolicyState belongs in platform-tracing-otel.
2. That HELM bootstrap must pass through the aggregate JMX path.
3. That one large PR is required for architectural atomicity.
4. That every one of the 64 current keys should be deleted.
5. That the approximate property table is sufficient for cutover.
6. That state choice and rules-config classification remain fully unresolved.
7. That PerfAdminController or protocol classes reside in the guessed modules.
8. That a plan-only LocalSdkTracingPolicyApplier is necessarily an existing file.
9. That stale docs can be deleted before the corresponding current behavior is removed.
10. That the shown ArchUnit code is implementation-ready.
11. That Wave 4 can be reverted independently after state cutover.
12. That property/doc cleanup is non-reversible.
13. That −2500 to −3500 LOC or any file/API/test count is grounded evidence.
14. That the blocker count is exactly five.
15. That cleanup is ready for implementation GO decision before the private-writer and key-disposition gates.
```

---

# 13. Final status

```text
Prompt 10 result: PARTIAL_PASS_WITH_CRITICAL_CORRECTIONS
Cleanup architecture: ACCEPTED
Cutover mechanism: UNRELEASED COHERENT RELEASE TRAIN
Supported dual architecture: FORBIDDEN
Private writer feasibility: BLOCKING
Exact property/env disposition: BLOCKING
JMX baseline: BLOCKING DELETION
Implementation GO: NOT GRANTED
Legacy deletion GO: BLOCKED
Next prompt: Multi-Criteria Architecture Scoring
```
