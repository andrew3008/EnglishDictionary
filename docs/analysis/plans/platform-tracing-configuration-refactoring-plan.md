# Platform Tracing Configuration Refactoring Plan

| Status | Value |
|---|---|
| Architecture direction | APPROVED |
| Plan authoring status | COMPLETE DRAFT |
| Implementation GO | NOT_GRANTED |
| Legacy deletion GO | BLOCKED |
| Product phase | Pre-production; breaking cutover permitted |
| Primary decision | [Category A binding decision](../platform-tracing-configuration/14-category-a-binding-decision.md) |
| Historical plan | `immutable_config_namespace_split_ba8da442.plan.md` is superseded for configuration architecture |

Closed gates: **E1, E2, E4, E5, E9**.

Open gates: **E3, E6, E7, E8**.

This document is the architecture committee's primary implementation plan. It
does not grant implementation or deletion authority.

## 1. Executive decision

The platform SHALL retain an Agent-first production topology and establish one
Agent-owned immutable accepted policy snapshot. Runtime policy SHALL contain
exactly eight typed leaves. Spring SHALL classify configuration authority,
prove Config Server provenance, build complete desired snapshots, and invoke
one private process-local JMX writer using JDK Open Types. The public platform
MBeanServer SHALL expose read-only diagnostics after cutover. Startup-only
Agent configuration SHALL remain outside runtime policy and SHALL participate
only in structural drift detection. All legacy writers and names SHALL be
removed in one unreleased train after the remaining gates close.

## 2. Scope and non-goals

### 2.1 Scope

The implementation SHALL:

- consolidate runtime policy ownership in the Agent extension;
- establish one atomic accepted-state owner selected by E3;
- productionize the private MBeanServer boundary proven by S-03;
- implement Config Server and developer adapters against one complete-snapshot
  writer;
- migrate all 78 `platform.tracing.*` properties and 29 directly read
  environment variables by exact-name disposition;
- remove six legacy mutation families after the approved JMX delta;
- preserve Agent-first runtime and classloader isolation.

### 2.2 Non-goals

- No SDK-only production support.
- No compatibility aliases.
- No new Gradle module.
- No generic configuration framework.
- No HTTP mutation.
- No long-lived dual paths.
- No migration shims.
- No public control DTOs.
- No second SDK, alternate writer, polling Config Server client, or patch
  apply.
- No production implementation before E8.

## 3. Confirmed current repository state

The committed executable evidence establishes:

- 16 active Gradle modules.
- Agent-first production topology.
- 78 `platform.tracing.*` configuration properties.
- 29 directly read environment variables.
- 17 duplicate declarations.
- 2 type-drift groups.
- 2 default/ownership-drift groups.
- 8 JMX ObjectNames.
- 63 live Standard MBean members.
- 16 semantic mutations and 47 read-only members.
- 15 direct ungated platform-server mutations; only aggregate
  `applyPolicy(CompositeData)` uses `RuntimeControlMutationPolicy`.
- The current partial protocol recognizes more fields than
  `JmxRuntimePolicyApplier` applies.
- `TracingRefreshScopeAutoConfiguration` performs ungated live mutation.
- The published BOM combines Boot 3.5.5 with
  `spring-cloud-context:4.1.5` as an API constraint; this is a misaligned,
  end-of-life Cloud train.

S-03 proves that a factory-retained private MBeanServer can be discovered
across Agent/App classloaders by a strict marker protocol while remaining
invisible to the platform server's remote connectors. S-02 proves per-key
Config Server provenance and refresh observation. S-04 is the authoritative
name inventory.

## 4. Target ownership model

| Owner | Exclusive responsibilities |
|---|---|
| Agent extension | accepted runtime policy; private writer; strict Agent codec; structural drift status; apply diagnostics; bounded audit; read-only platform JMX projections |
| Spring autoconfigure | authority/posture guard; Config Server provenance and desired-state builder; event coalescing; application codec/client; DEV JMX endpoint; application readiness and Actuator read model |
| `platform-tracing-otel` | only generic reusable runtime primitives that survive E3; no Spring, JMX discovery, or authority policy |
| `platform-tracing-api` | application tracing API only; no control-plane DTOs, state, codec, or Open Types |

The accepted runtime state exists only in the Agent classloader. Application
objects SHALL NOT cross to the Agent. The classloader boundary carries only
JDK `CompositeData`, `CompositeType`, primitive wrappers, strings, arrays, and
maps representable by approved Open Types. Application and Agent codecs SHALL
be independent implementations of one versioned wire contract.

## 5. Authority model

Authority selects the sole source permitted to write `policy.*`; it does not
govern ordinary Spring adapter properties or external `otel.*` settings.

| Mode | Authority | Required behavior |
|---|---|---|
| AGENT | HELM | Agent initializes final policy from env/`-D`; Spring performs no policy write |
| AGENT | CONFIG_SERVER | non-optional Config Client provenance and complete-snapshot writer are required |
| AGENT | DEV_ACTUATOR | local typed JMX commands are enabled only by explicit developer posture |
| DISABLED | HELM | no Agent writer discovery; Spring tracing facade remains disabled |
| DISABLED | CONFIG_SERVER | fail-fast startup |
| DISABLED | DEV_ACTUATOR | fail-fast startup |

Exactly one authority is selected at startup. Missing, conflicting, or
unsupported authority configuration SHALL fail closed. No authority fallback
is permitted.

## 6. Configuration namespaces

| Namespace | Binding meaning | Owner |
|---|---|---|
| `platform.tracing.policy.*` | the eight runtime Category A leaves only | Agent accepted policy |
| `platform.tracing.javaagent.*` | Agent bootstrap, topology, structural policy, trust and safety envelopes | Agent |
| `platform.tracing.configuration.*` | authority, provenance, refresh orchestration and delivery posture | Spring |
| `platform.tracing.dev-control.*` | explicitly local developer control posture | Spring |
| existing retained `platform.tracing.*` | Spring adapter properties identified by S-04 | Spring |
| `platform.environment` | imported trust signal | deployment platform |
| `otel.*` / `OTEL_*` | external OpenTelemetry contract | OpenTelemetry/deployment |

### 6.1 Exact conflict dispositions

- `platform.tracing.policy.version` is currently a resource attribute, not
  runtime policy. It SHALL be renamed to
  `platform.tracing.resource.applied-policy-version` after the S-04 technical
  checkpoint confirms the complete resource contract.
- The two baggage families
  `platform.tracing.baggage.*` and
  `platform.tracing.propagation.baggage.*` SHALL be consolidated under one
  Agent-owned startup namespace. Exact winning names remain a technical
  checkpoint from S-04; they are not Category A.
- Spring `force-record-header-values` and Agent `force-record-values` SHALL
  converge on
  `platform.tracing.javaagent.sampling.force-record-values`.
- The invalid published contract key
  `platform.tracing.semconv.validation.mode` SHALL be removed from the public
  contract inventory; the active
  `platform.tracing.semantic.validation-mode` remains the Spring semantic-layer
  property unless its S-04 row directs deletion.
- Fixture-only `enabled`, historical mode aliases, removed async aliases, and
  `service.k8s-pod-uid` SHALL be deleted from fixtures and documentation.
- `platform.tracing.propagation.w3c.diagnostics-only` SHALL be deleted because
  it has no reader.
- Five Agent outbound readers with no caller SHALL be deleted after exact
  source tests prove Spring outbound adapters remain authoritative.

No item in this subsection reopens Category A.

## 7. Final Category A contract

<!-- CATEGORY_A_BEGIN -->
1. `platform.tracing.policy.sampling.enabled`
2. `platform.tracing.policy.sampling.ratio`
3. `platform.tracing.policy.sampling.route-ratios`
4. `platform.tracing.policy.validation.enabled`
5. `platform.tracing.policy.validation.strict`
6. `platform.tracing.policy.exporter.enabled`
7. `platform.tracing.policy.propagation.trace-control-headers.enabled`
8. `platform.tracing.policy.diagnostics.log-level`
<!-- CATEGORY_A_END -->

The schema SHALL contain no maps of arbitrary property names, no unknown
fields, no structural values, no secrets, no file paths, and no
pipeline-composition rules.

Canonical types are `boolean`, bounded `double`, immutable
`Map<String, Double>`, and the closed log-level enum
`OFF|ERROR|WARN|INFO|DEBUG|TRACE`. Route maps retain numeric values and
deterministic ordering; they SHALL NOT be flattened into strings. Existing
ratio validation remains `[0.0, 1.0]`. Collection/key bounds not yet proved
are `BOUND_TO_BE_DERIVED_FROM_EXISTING_VALIDATOR`.

`validation.strict` is valid only under the startup envelope
`platform.tracing.javaagent.validation.strict-runtime-allowed`. The envelope
is Category B and SHALL NOT be removed or weakened.

The following are explicitly excluded from Category A:

- `platform.tracing.javaagent.sampling.drop-paths`;
- `platform.tracing.javaagent.sampling.force-record-values`;
- `platform.tracing.javaagent.scrubbing.built-in-rules`;
- `platform.tracing.javaagent.scrubbing.enabled`.

## 8. Category B and structural drift

Category B values are effective only from startup environment variables or
system properties read by the Agent. Config Server MAY carry non-secret
desired values solely for drift comparison. Raw secrets have no
desired-state, digest, audit, or drift representation.

Initial startup mismatch between effective and authoritative desired Category
B SHALL block application readiness. A mismatch observed after readiness SHALL
retain the accepted runtime policy, set `restartRequired=true`, expose bounded
key names, and require rollout/restart. It SHALL NOT introduce a new
`DEGRADED` Agent lifecycle state.

Structural drift is orthogonal to runtime apply outcome:

- policy unchanged + drift: `UNCHANGED`, sequence unchanged;
- policy changed + drift: `APPLIED`, sequence incremented;
- invalid desired policy: accepted policy retained and `REJECTED`.

## 9. Accepted-state model

The Agent SHALL own one:

```text
AcceptedPolicyStateStore
    exact implementation selected by Gate E3 / S-01
```

The implementation-independent contract is:

- one loaded immutable reference on the hot path;
- one expected-base commit operation;
- one winner for each expected base;
- losing concurrent commit returns `STALE`;
- `acceptedSequence` increments only on `APPLIED`;
- no retry or rebase inside the store;
- no duplicate version owner;
- current accepted state is the logical last-known-good state;
- attempt metadata, source revision, audit, and failure text are outside the
  CAS payload.

E3 SHALL select and prove the exact primitive, API, placement, memory
visibility, allocation behavior, and conflict semantics before P1 begins.

## 10. Apply outcome contract

| Outcome | CAS performed | `acceptedSequence` | LKG | Audit | Metric | Retry |
|---|---|---|---|---|---|---|
| `APPLIED` | yes, one expected-base CAS | `+1` | replaced by committed policy | metadata-only entry | `apply_total{outcome="applied"}` | none |
| `UNCHANGED` | no | unchanged | unchanged | metadata-only entry | fixed `unchanged` outcome | none |
| `REJECTED` | no | unchanged | unchanged | bounded validation summary | fixed `rejected` outcome | none |
| `STALE` | attempted precondition/commit loses; no state change | unchanged | unchanged | expected/actual sequence metadata | fixed `stale` outcome | store: none; Config Server adapter: one bounded full rebuild/retry |
| `FAILED` | no successful CAS | unchanged | unchanged | bounded internal failure class/code | fixed `failed` outcome | none |

Every attempt receives an `attemptId`. Identical normalized policy digest
returns `UNCHANGED`, even when source revision changes. Source revision MAY
update delivery diagnostics but SHALL NOT create a new accepted policy version.

## 11. Private JMX boundary

The production boundary SHALL implement the S-03 mechanism:

1. Agent calls `MBeanServerFactory.createMBeanServer`.
2. Agent registers the exact marker before the writer.
3. Spring calls `MBeanServerFactory.findMBeanServer(null)`, filters by the
   marker, validates protocol/version/signature/runtime id/writer/lifecycle,
   and requires exactly one valid marker.
4. The same logical Control ObjectName is registered only on the private
   server.
5. Platform MBeanServer projections are read-only.
6. Shutdown explicitly unregisters writer, marker, projections, and remaining
   private MBeans, then calls `releaseMBeanServer`, clears references, and
   verifies disappearance.
7. `mode=DISABLED` performs no discovery.

Production tests SHALL preserve all M1-M12 and N1-N7 invariants: writer absent
from platform server; remote connector isolation; exactly-one marker;
cross-classloader Open Types; deterministic shutdown; two Spring contexts;
Agent-only operation; disabled no-op; management-agent isolation; repeated
lifecycle; capability ordering; read-only platform projection; duplicate
initializer safety; invalid/duplicate marker rejection; same-name isolation;
wrong protocol rejection; wrong runtime `STALE`; malformed payload rejection;
and proof that the old remote platform mutation is gone.

## 12. Config Server lifecycle

`CONFIG_SERVER` authority SHALL require:

- a non-optional authoritative Config Data import;
- the modern `configClient` marker;
- a complete manifest of all eight Category A leaves;
- per-key effective and remote-desired source evidence;
- fail-closed rejection of command-line, system, environment, or local-file
  overrides of `policy.*`;
- `EnvironmentChangeEvent` as the observation trigger;
- one generation/dirty coalescer and one worker;
- complete snapshot rebuilding, never patch apply;
- one bounded rebuild/retry after `STALE`;
- no polling client and no HELM fallback.

Initial fetch, provenance, manifest, validation, private-writer discovery, and
initial apply MUST succeed before application configuration readiness. Runtime
Config Server outage retains LKG, records delivery failure, and does not erase
accepted policy. Removal of a remote key rebuilds the complete snapshot using
the canonical default owner; it is not interpreted as a partial update.

Exact Boot/Cloud versions and refresh fan-out mechanism remain under E6.

## 13. HELM lifecycle

In HELM authority:

- Agent premain reads final `policy.*` and `javaagent.*` values from env/`-D`;
- initial accepted state is built and validated before instrumentation hooks
  become ready;
- Spring performs no policy write or private-writer discovery;
- YAML-only Agent policy is invalid because Spring YAML cannot configure the
  isolated Agent bootstrap;
- no runtime writer is exposed to Spring or remote management.

## 14. DEV_ACTUATOR lifecycle

DEV_ACTUATOR SHALL expose typed commands only for the eight Category A leaves.
It SHALL expose no Category B command and no generic raw snapshot command.
Every command:

1. reads runtime instance id and expected accepted sequence;
2. copies the complete accepted policy;
3. changes exactly one typed leaf;
4. validates the complete candidate;
5. sends the complete snapshot through the private JMX writer;
6. does not retry `STALE`.

The endpoint is local developer posture, not a production remote writer.
Production startup SHALL reject DEV_ACTUATOR authority.

## 15. Readiness and health

Four states SHALL remain distinct:

| State | Meaning |
|---|---|
| Agent readiness | controlled extension, required processors/hooks, private writer and accepted store are ready |
| Application tracing-configuration readiness | selected authority, provenance/manifest, writer discovery and initial apply are valid |
| Runtime delivery diagnostics | latest Spring-to-Agent attempt/outcome, revision, conflict and outage evidence |
| Structural drift diagnostics | effective versus desired Category B comparison and restart requirement |

Capability `CONFIG_SNAPSHOT_V1` means protocol support only. It does not imply
that initial apply succeeded. Health SHALL never infer accepted truth from
Spring properties or delivery status.

## 16. Security

The target SHALL enforce:

- private writer isolation;
- read-only platform JMX;
- exact bounded Open Types;
- schema allowlist and unknown-field rejection;
- secret exclusion;
- bounded strings, collections, payloads, audit records, and failure text;
- per-key Config Server provenance;
- no signed snapshots without an independent signer and key owner;
- no remote writer fallback;
- no generic raw snapshot developer command.

Confirmed current debt to remove in the earliest safe implementation train:

- 15 direct ungated platform-server mutations;
- successful remote invocation of a current platform mutation;
- ungated RefreshScope mutation;
- partial-protocol silent success where recognized fields are not applied.

P2 creates the isolated writer before any legacy deletion. P4 switches all
supported writers. P6 removes all old platform-server writes and RefreshScope
mutation atomically with namespace cutover.

## 17. Diagnostics and audit

Diagnostics SHALL have four owners:

| Truth category | Owner |
|---|---|
| accepted policy, digest, runtime id, sequence | Agent accepted store |
| structural drift and restart requirement | Agent drift projection based on effective startup values and supplied non-secret desired values |
| Agent apply-attempt evidence | Agent apply diagnostics |
| Spring delivery/provenance evidence | Spring delivery diagnostics |

The Agent audit is a 32-entry bounded in-memory metadata-only ring. It is not
replay, persistence, recovery, or a compliance store. It stores attempt id,
outcome, authority/source category, bounded revision metadata, accepted
sequence, policy digest, restart flag, timestamp, and bounded reason code. It
stores no raw policy values, routes, headers, secrets, stack traces, or
unbounded messages.

The committed consumer inventory distinguishes `CLIENT`, `ACTUATOR`, `HEALTH`,
`TEST`, and `DOC` consumers from `NO_NAMED_CONSUMER_FOUND`. E7 SHALL decide the
member-level read projection; absence of a named repository consumer is not
deletion approval.

## 18. Module-by-module change plan

| Existing module | ADD | REWRITE | RETAIN | DELETE |
|---|---|---|---|---|
| `platform-tracing-bom` | approved Cloud train constraints after E6 | Cloud constraint alignment | Boot/OTel platform imports | 4.1.5 API constraint |
| `platform-tracing-api` | nothing for control plane | public contract inventory semconv correction only | tracing facade, span API, application SPI | current public control protocol after internal replacement and contract review |
| `platform-tracing-otel` | E3-approved generic store primitive only if it remains domain-neutral | `VersionedStateHolder` contract only if S-01 selects reuse | sampling/validation domain validators and immutable snapshots | duplicate domain version owners selected by E3 |
| `platform-tracing-spring-boot-autoconfigure` | authority guard, provenance classifier, full desired builder, coalescer, application codec/client, readiness and delivery model | `TracingProperties`, `TracingActuatorEndpoint`, `PlatformTracingJmxClient` | ordinary Spring adapter configuration | `RuntimeConfigApplier`, write operations, `TracingRefreshScopeAutoConfiguration` mutation path |
| `platform-tracing-autoconfigure-webmvc` | no control writer | readiness exposure only if existing web health requires it | MVC tracing adapters | any accidental policy mutation |
| `platform-tracing-autoconfigure-webflux` | no control writer | readiness exposure only if existing reactive health requires it | WebFlux tracing adapters | any accidental policy mutation |
| `platform-tracing-spring-boot-starter-servlet` | no new dependency | metadata only after E6 | composition role | transitive Config Client/Agent dependencies if found |
| `platform-tracing-spring-boot-starter-reactive` | no new dependency | metadata only after E6 | composition role | transitive Config Client/Agent dependencies if found |
| `platform-tracing-otel-javaagent-extension` | accepted policy model/store, strict codec, private server owner/marker/writer, apply diagnostics, audit, drift projection | `PlatformTracingJmxRegistrar`, sampler/export/validation/propagation readers | Agent SPI and processors | 16 legacy writes and obsolete partial protocol after E7/P6 |
| `platform-tracing-collector-config` | no control-plane component | docs only if renamed deployment keys are referenced | Collector pipeline | stale tracing property references |
| `platform-tracing-test` | reusable consumer architecture rules only where existing conventions require | contract fixtures after cutover | test support | obsolete legacy fixtures |
| `platform-tracing-samples` | final configuration examples | all exact renamed keys | application API samples | legacy names and mutation examples |
| `platform-tracing-bench` | accepted-state read/apply microbenchmarks if E3 places them here | benchmark names/fixtures | existing JMH harness | legacy holder benchmarks after replacement |
| `platform-tracing-e2e-tests` | authority matrix, Config Server delivery, private JMX production fixture, refresh storm, secret canary | S-02/S-03 fixtures into final contract tests | isolated evidence source sets | obsolete legacy writer fixtures after replacement |
| `platform-tracing-perf-tests` | M10/M10c/M10d scenarios for final flow | property manifests and writer calls | macro scenarios | legacy mutation scenarios |
| `platform-tracing-perf-harness` | measured P5 acceptance outputs | configuration names and scenario wiring | reference tier | obsolete property aliases |

Every new production type SHALL be mapped in its implementation PR to module,
requirement, prevented failure, replaced code, and proving test. This plan
authorizes no unmapped type.

## 19. Implementation trains

### P0 - Remaining evidence closure

- Preconditions: E5 closed.
- Additions: S-01 state proof, S-06 dependency/refresh matrix, E7 exact JMX
  delta and implementation checklist.
- Rewrites: plan only if evidence falsifies a stated contract.
- Deletions: none.
- Tests: S-01 concurrency/allocation; C1-C7 dependency matrix; JMX delta
  verifier.
- Exit: E3, E6, E7 closed and E8 explicitly granted.
- Rollback unit: evidence commits independently revertible.

### P1 - Accepted-state foundation

- Preconditions: E3 and E8 closed.
- Additions: accepted policy, store, validator, digest, outcomes,
  diagnostics, audit.
- Rewrites: existing generic primitive only as approved by E3.
- Deletions: duplicate state/version owner only after equivalence proof.
- Tests: race, stale winner, unchanged, LKG, memory visibility, allocation,
  validation and digest determinism.
- Exit: one state owner and no production writer switched.
- Rollback unit: P1 commit set.

### P2 - Private control boundary productionization

- Preconditions: P1 green; S-03 contract accepted.
- Additions: private server owner, marker, strict codec, aggregate writer.
- Rewrites: registrar lifecycle and read projection registration.
- Deletions: none.
- Tests: M1-M12, N1-N7, malformed Open Types, attach/RMI isolation, repeated
  lifecycle.
- Exit: private writer works while legacy path remains unchanged.
- Rollback unit: P2 commit set.

### P3 - Agent reader migration

- Preconditions: P2 green.
- Additions: none beyond adapters from accepted policy to existing consumers.
- Rewrites: sampler, validation, exporter, propagation and diagnostics readers
  to one loaded accepted snapshot.
- Deletions: three domain holders and duplicate version ownership selected by
  E3.
- Tests: hot-path parity, golden sampling, strict envelope, exporter and
  propagation toggles, log-level visibility.
- Exit: all eight leaves read from one accepted owner; old writers may still
  feed only a controlled transitional adapter inside the unreleased branch.
- Rollback unit: P3 commit set plus P1/P2.

### P4 - Config Server and DEV adapters

- Preconditions: E6 closed; P3 green.
- Additions: authority guard, provenance, full builder, coalescer, client,
  typed DEV commands, application readiness.
- Rewrites: Actuator read model and JMX client.
- Deletions: no writer deletion yet.
- Tests: complete manifest, local override, refresh deletion, outage, one
  stale retry, DEV no-retry, mode-authority matrix.
- Exit: each approved authority has exactly one behavior.
- Rollback unit: P4 commit set.

### P5 - Failure, performance and security proof

- Preconditions: P4 green.
- Additions: secret canary, cardinality checks, JMH and M10/M10c/M10d evidence.
- Rewrites: bounds only when evidence requires.
- Deletions: none.
- Tests: refresh storm, malformed/oversized input, attach/RMI, concurrency,
  latency/allocation, readiness, Config Server outage.
- Exit: budgets and all security invariants pass.
- Rollback unit: evidence-driven P5 adjustments.

### P6 - Coherent namespace and writer cutover

- Preconditions: P5 green; E7 approved member delta.
- Additions: final exact names and fail-fast legacy-name detector.
- Rewrites: Agent parser, Spring binding, Helm/Config Server manifests,
  fixtures, docs and wire schema in one train.
- Deletions: all six legacy write families, RefreshScope mutation, partial
  protocol, old names and aliases.
- Tests: generated 78-property/29-env disposition, deleted-key behavior,
  JMX delta, published consumers, full build.
- Exit: no legacy writer/name remains in supported release.
- Rollback unit: entire unreleased train, not individual property/state/writer.

### P7 - Documentation and measured scorecard

- Preconditions: P6 green.
- Additions: final runbooks, authority examples, measured diff scorecard.
- Rewrites: SUPPORTED, MIGRATION, CHANGELOG, contracts and operational docs.
- Deletions: historical active guidance replaced by final references.
- Tests: documentation plan verifier, samples, public contract and BOM
  consumer gates.
- Exit: committee acceptance and rollout gates remain independently visible.
- Rollback unit: P7 docs; code rollback remains whole train.

## 20. Legacy deletion plan

The six current mutation families are:

1. aggregate Control `applyPolicy`;
2. Diagnostics writable `PlatformLogLevel` and `PropagationEnabled`;
3. Export writable `ExportEnabled`;
4. Sampling writable attributes, two update operations, and counter reset;
5. Scrubbing update operations;
6. Validation update operations.

E7 SHALL approve the exact 63-member before/after delta. P6 SHALL delete every
legacy write from the public platform MBeanServer, including all 16 semantic
mutations, and SHALL retain only approved reads. `resetSamplerCounters` is a
mutation and does not survive merely because it is diagnostic.

`RuntimeConfigApplier`, ungated RefreshScope application, partial protocol
apply, old Spring write operations, and direct domain setters SHALL be deleted.
No legacy write survives the supported release. Member deletion is blocked
until E7 and the private writer/read projections are green.

## 21. Property migration plan

The S-04 generated inventory is the source of truth for all 78 properties and
29 environment variables. P6 SHALL provide one exact disposition row per name:
retain, rename, move owner, external keep, or delete. Wildcard migration,
aliases, relaxed compatibility, and dual-read precedence are forbidden.

Legacy names SHALL fail fast with an exact replacement or deletion message.
Agent parser and Spring bindings SHALL switch together. Config Server manifest
and Helm values SHALL switch in the same release train. Fixtures, examples,
contracts, documentation, test resources, and perf manifests SHALL be scanned
and migrated by exact name.

## 22. Testing strategy

Required proof:

- accepted-state concurrency, one-winner CAS, stale and unchanged semantics;
- strict allowlist codec and deterministic digest;
- private JMX lifecycle, marker ambiguity, attach/RMI isolation;
- full mode-authority matrix;
- S-02 Config Server provenance and non-optional import;
- complete eight-leaf manifest and removed-key default rebuild;
- deleted legacy key behavior with no aliases;
- refresh storm coalescing and one bounded stale retry;
- secret canary proving no secret in snapshots, drift, JMX, logs, audit, or
  metrics;
- exact JMX contract delta from the committed baseline;
- fixed metrics cardinality;
- separate Agent/application readiness;
- M10/M10c/M10d macro scenarios;
- JMH/P5 accepted read and apply budgets;
- published starter consumer and BOM dependency matrix.

Skipped opt-in tests SHALL be reported as `SKIPPED/NOT_RUN`, never PASS.

## 23. Architecture fitness rules

The build SHALL execute invariants that:

- inventory remains deterministic at 78 properties and 29 env variables until
  the approved cutover baseline changes;
- the runtime schema contains exactly the eight E5 leaves and rejects unknown
  fields;
- `platform-tracing-api` contains no control DTO or OpenTelemetry type;
- no new Gradle module is introduced;
- only Agent code owns accepted state and the private writer;
- platform MBeanServer has no writable attributes or mutating operations after
  cutover;
- Spring has no direct domain setter or HTTP write path;
- Config Client and Agent extension do not leak through starter publication;
- every legacy name is absent after P6;
- published BOM and consumer graphs match E6.

Rules SHALL inspect compiled/publication behavior where source text alone is
insufficient.

## 24. Dependency and BOM plan

Current baseline is Boot 3.5.5 with
`spring-cloud-context:4.1.5` published as an API constraint. Cloud 4.1.x belongs
to the misaligned/EOL train and is not an acceptable production bridge
baseline.

E6 SHALL select the corporate Boot patch and compatible Cloud train, execute
C1-C7 dependency resolution, remove the 4.1.5 API constraint, prove no Config
Client or Agent extension leaks through either starter, update SUPPORTED and
the BOM atomically, and rerun S-02 on the approved production stack. E6 SHALL
also select the authorized refresh fan-out mechanism. No Config Server
production bridge implementation begins before E6 closes.

## 25. Cutover and rollback

Before P6, P1-P5 commits are revertible by train while no supported release
uses them. After property, state, and writer cutover, the rollback unit is the
entire unreleased release train. State, property names, writers, manifests, or
readers SHALL NOT be rolled back independently.

Rollback restores the previous unreleased commit set and redeploys/restarts;
it does not add aliases or dual paths. Accepted state is process-local and is
rebuilt from the selected authority after restart.

## 26. Measured deletion scorecard

P7 SHALL calculate from the final Git diff, without estimated LOC:

| Measure | Baseline | Target/result source |
|---|---:|---|
| runtime writers | 16 semantic JMX mutations plus ungated refresh path | executable JMX/HTTP/Spring scans |
| mutable owners | current domain holders plus protocol/applier paths | class and constructor inventory |
| platform JMX writes | 16 | live `MBeanInfo`, target 0 |
| HTTP writes | current Actuator mutation surface | endpoint introspection, target 0 |
| properties/env renamed/deleted | 78/29 inventory | exact disposition diff |
| files/types/tests added/deleted | not estimated | `git diff --numstat` and path classification |
| production/test LOC | not estimated | final diff by source set |
| active module count | 16 | `settings.gradle`, target 16 |

The scorecard SHALL distinguish additions required for safety from deleted
legacy complexity.

## 27. Remaining gates

| Gate | Status | Required closure |
|---|---|---|
| E3 | OPEN | S-01 selects and proves exact accepted-state primitive/API |
| E6 | OPEN | compatible Boot/Cloud/BOM matrix and refresh delivery selected |
| E7 | OPEN | exact read/diagnostic JMX delta approved against 63-member baseline |
| E8 | OPEN | architecture committee explicitly grants implementation GO |

E1, E2, E4, E5, and E9 are CLOSED. No other research gate may be used to
silently broaden scope.

## 28. Implementation GO checklist

- [ ] E3 closed with executable state-concurrency and hot-path evidence.
- [ ] E6 closed with C1-C7, published BOM consumer, and approved refresh
  fan-out evidence.
- [ ] E7 closed with exact member-level JMX retain/delete/add decision.
- [ ] Category A remains exactly the eight E5 leaves.
- [ ] All unknown fields and generic maps are rejected.
- [ ] Category B names and strict-validation envelope are fixed.
- [ ] Exact Agent/App Open Types and bounds are approved.
- [ ] Initial/readiness, refresh, stale, invalid, outage, and drift behavior is
  accepted.
- [ ] P0-P7 rollback units and whole-train cutover are accepted.
- [ ] Current JMX and RefreshScope security debt is assigned to P2/P6.
- [ ] No new module, compatibility alias, public DTO, HTTP writer, second SDK,
  or generic framework is proposed.
- [ ] Architecture committee records closure of E8 in a separate decision.

Until the final checklist item is recorded, implementation GO remains
**NOT_GRANTED** and legacy deletion remains **BLOCKED**.
