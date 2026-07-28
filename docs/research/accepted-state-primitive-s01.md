# S-01 — Accepted-State Primitive Spike (Gate E3)

| Field | Value |
| --- | --- |
| Spike | S-01, accepted-state primitive |
| Gate | E3 |
| Repository | `E:\Platform_Traces` |
| Branch | `refactor/cursor-rules` |
| HEAD at spike start | `8bf1bfdc127853fe4ea3a5918dba13d5592948db` |
| Toolchain | JDK 21.0.6+8-LTS-188 HotSpot, Gradle 8.14, Windows 10 amd64 |
| Production code changed | **NO** |
| Implementation GO | `NOT_GRANTED` — unchanged by this spike |
| Legacy deletion GO | `BLOCKED` — unchanged by this spike |
| Corrective pass | **S-01-C1**, correcting commit `fdf81df4daa764e2310f10586b29285491788fd4` |
| Overall result | **PASS** |

This spike decides only the E3 primitive question. It does not modify Category A, the authority
model, the JMX boundary, the namespace taxonomy, or phases P0–P7, and it does not start P1.

> **Corrective pass S-01-C1.** An independent review of the original S-01 commit raised three
> blockers: **B1** an incomplete production owner and caller inventory, **B2** Category B scrubbing
> incorrectly mapped into the accepted runtime envelope, and **B3** a generic payload type that
> cannot guarantee deep immutability. All three are closed. Sections that carried a falsified
> statement have been corrected
> in place and the superseded statement is quoted, so no contradiction remains in this document. The
> verdict is unchanged and was re-derived rather than restated. No production code was modified.
> The Cursor plan was corrected only to move `ScrubbingPolicyHolder` deletion from P3 to the coherent
> P6 Category B cutover.

---

## 1. Executive verdict

**`IMPLEMENT_AGENT_SPECIFIC_PRIMITIVE`**

The existing generic primitive `VersionedStateHolder` cannot express the E3 contract, and this is a
semantic incompatibility rather than a gap that a small refactor closes. `VersionedStateHolder`
implements last-known-good publication with an **internal CAS-retry loop that rebases the candidate
onto the winner's state**. Its committed contract test,
`VersionedStateHolderTest.concurrentUpdatesAreSerializedWithoutLoss`, asserts that all 16 000
concurrent updates succeed and that the version advances 16 000 times. E3 requires that one expected
base admits exactly one winner, that the accepted sequence advances by exactly one, and that every
loser is told `STALE`. The repository's own asserted contract is the precise opposite of the
requirement.

The accepted-state store must therefore be a new primitive owned by
**`platform-tracing-otel-javaagent-extension`**. Placement there is not a stylistic preference: two
pre-existing architecture gates already assert that this module never reaches the application
classpath, which makes Agent-classloader exclusivity structurally enforced rather than merely
documented.

The selected design is a **`PROPOSED` concrete Agent-specific one-shot accepted-policy store backed
by `AtomicReference<AcceptedPolicyEnvelope>`**. It holds **one immutable envelope in one
`AtomicReference`**, carrying `runtimeInstanceId`, `acceptedSequence` and a **deeply immutable
`EffectiveTracingPolicy`** together, and performs **exactly one `compareAndSet` per commit with no
loop and no candidate-builder function**. Rebase is not merely forbidden, it is unrepresentable: the
store receives an already-built candidate and has nothing it could re-invoke.

The payload type is concrete, not generic. The spike's `S01AcceptedPolicyStore<S>` proved the
mechanism, but an arbitrary `S` may be mutable, so it is scaffolding rather than the production
contract (§11.1, correcting **B3**).

Weighted scores after correction: **Candidate D 97.8** (was 98.2), G1 63.8, X 56.0, G0 40.4. The
single change is `migration risk` 3 → 2, justified in §10.

Measured: hot-path read **0.890 ns/op** at **`gc.alloc.rate.norm` ≈ 10⁻⁶ B/op**, corroborated by an
exact HotSpot per-thread counter showing **0 bytes over 2 000 000 reads**. One-winner semantics proven
over 250 rounds × 8 concurrent writers on the generic store and re-proven over 100 rounds × 8 writers
on the concrete store. **54 tests, 0 failures, 0 skipped** — 27 original plus 27 corrective.

---

## 2. Gate E3 status recommendation

**`READY_TO_CLOSE_E3`**

E3 closure conditions, each mapped to executed evidence:

| Condition | Status | Evidence |
| --- | --- | --- |
| Exactly one primitive selected | MET | Candidate D, §11 |
| Module placement unambiguous | MET | `platform-tracing-otel-javaagent-extension`, §12 |
| One-winner proof | **PASS** | `S01OneWinnerCommitTest`, 250 rounds × 8 writers |
| `runtimeInstanceId` mismatch proof | **PASS** | `S01ExpectedBaseMismatchTest.runtimeInstanceMismatchIsStale` |
| `acceptedSequence` proof | **PASS** | `S01ExpectedBaseMismatchTest` sequence-behind and sequence-ahead cases |
| No retry/rebase proof | **PASS** | sequence advances +1 for 8 attempts; store surface has no builder parameter |
| Memory visibility proof | **PASS** | `S01PublicationVisibilityTest`, 20 000 commits × 6 readers, 0 violations |
| Hot-path allocation proof | **PASS** | exact counter 0 B over 2M reads; JMH ≈ 10⁻⁶ B/op |
| Duplicate state/version owners listed | MET | §4 |
| Future deletion map complete | MET | §14 |
| Production code unchanged | MET | §16, `git diff --stat` |

Conditions added by the corrective pass:

| Condition | Status | Evidence |
| --- | --- | --- |
| Complete production owner and caller inventory | MET | §4.5, machine-checked by `S01OwnerInventoryEvidenceTest` |
| All eight Category A leaves have a current and a target owner | MET | §4.6 — five current owners, one target owner |
| Category B scrubbing excluded from the accepted envelope | MET | §4.7, `S01CategoryALeafContractTest.policyDeclaresNoCategoryBLeaf` |
| Concrete deeply immutable payload contract fixed | MET | §11.1, §11.2 |
| Deep-immutability proofs | **PASS** | `S01DeepImmutabilityTest`, 6 tests; 1 200 000 concurrent observations, 0 violations |
| Existing CAS / JMM / allocation proofs still pass | **PASS** | §7.1 — all 27 original tests re-executed with `--rerun-tasks` |

The committee or plan owner closes E3. This spike recommends only.

The plan-backlog inconsistency found during the corrective pass is resolved: P3 deletes only the
Category A holders, while `ScrubbingPolicyHolder` remains Category B and is deleted only in the
coherent P6 Category B cutover.

One caveat that does **not** block E3 but must be carried into P5: the JMH figures were produced with
the repository's `-PjmhDev` profile, which `platform-tracing-bench/build.gradle` documents as
indicative on a developer host. The allocation metric is counter-derived and therefore sound at this
profile; the `ns/op` figures are feasibility evidence only. S-01 deliberately sets no production
latency budget.

---

## 3. Repository inventory

Scope: all `src/main/java` across all 16 modules in `settings.gradle`, plus test and benchmark source
sets where relevant. Full machine-readable form:
`docs/research/evidence/accepted-state-primitive/repository-symbol-inventory.json`.

### 3.1 The generic primitive

| Field | Value |
| --- | --- |
| Symbol | `VersionedStateHolder<T extends VersionedState>` |
| Module | `platform-tracing-otel` |
| Package | `space.br1440.platform.tracing.otel.runtime.versioned` |
| File | `platform-tracing-otel/src/main/java/space/br1440/platform/tracing/otel/runtime/versioned/VersionedStateHolder.java` |
| API | `current()`, `version()`, `tryUpdate(UnaryOperator<T>)`, `tryUpdate(UnaryOperator<T>, Predicate<T>)` |
| Concurrency model | single `AtomicReference<T>`; `for(;;)` CAS-retry loop at lines 46–77 |
| Version ownership | **none** — `version()` delegates to `ref.get().version()`; the increment is the caller's job |
| Allocation on read | zero (`ref.get()`) |
| Allocation on write | one candidate per **builder invocation**, and the builder is re-invoked on every CAS failure |
| Direct production consumers | `SamplerStateHolder`, `ScrubbingPolicyHolder`, `ValidationPolicyHolder` |

`VersionedState` (same package) declares only `long version()`, and its Javadoc states the convention
`prev.version() + 1` — that is, the contract explicitly delegates version arithmetic to callers.

### 3.2 Domain holders

All three wrap `VersionedStateHolder` by composition, expose `current()` / `version()` /
`tryUpdate(...)` / `tryApplyPolicyUpdate(...)`, and return `boolean`.

| Holder | Module | Package | Wrapped state |
| --- | --- | --- | --- |
| `SamplerStateHolder` | `platform-tracing-otel-javaagent-extension` | `...javaagent.sampler` | `SamplerState` |
| `ScrubbingPolicyHolder` | `platform-tracing-otel-javaagent-extension` | `...javaagent.scrubbing` | `ScrubbingSnapshot` |
| `ValidationPolicyHolder` | `platform-tracing-otel-javaagent-extension` | `...javaagent.processor` | `ValidationSnapshot` |

Production writers reaching these holders:

| Holder | Production write call sites |
| --- | --- |
| `SamplerStateHolder` | `PlatformSamplingControl` lines 37, 80, 112, 161, 209 (per-field setters, each an independent CAS) and line 281 (`updateSamplingPolicy`) |
| `ScrubbingPolicyHolder` | `ScrubbingSpanProcessor:453`, reached from `ScrubbingSpanProcessor:439` and `PlatformScrubbingControl:63` |
| `ValidationPolicyHolder` | `ValidatingSpanProcessor:245`, reached from `ValidatingSpanProcessor:236`, `PlatformValidationControl:43`, `JmxRuntimePolicyApplier:166`, `PlatformSpanProcessorFactory:119` |

Production readers are the hot paths: `CompositeSampler.shouldSample`,
`ScrubbingSpanProcessor.onEnding`, `ValidatingSpanProcessor.onEnding`, plus
`ReadAppliedStateHandler.read()` and the JMX getters.

### 3.3 Concurrency primitive census across all 16 modules

Zero production occurrences of `VarHandle`, `AtomicStampedReference`, `ReadWriteLock`, `StampedLock`,
`compareAndExchange`, `getAndSet`, `weakCompareAndSet`. Consequences for S-01: there is no existing
`VarHandle` idiom to match, and nothing in the repository already performs a stamped or dual-value
atomic update, so the "two atomics for logically atomic policy and sequence" anti-pattern has no
precedent to inherit.

Relevant existing state mechanisms:

| Module | Symbol | Mechanism | Path |
| --- | --- | --- | --- |
| `platform-tracing-otel` | `VersionedStateHolder.ref` | `AtomicReference` + retry loop | config |
| `platform-tracing-otel` | `OwningSpanScope.closed` | `AtomicBoolean` CAS | hot |
| `platform-tracing-otel` | `DefaultTraceOperations.active` | `volatile` reference | hot read / config write |
| `platform-tracing-otel` | `RemoteServiceTraceMirror` | `synchronized` methods | hot |
| extension | `PlatformDropOldestExportSpanProcessor.queueLock` | `ReentrantLock` | hot enqueue |
| extension | `CircuitBreaker`, `RuleCircuitBreaker` | `AtomicReference` state machine | hot |
| extension | `ScrubbingSpanProcessor.threadLocalMac` | `ThreadLocal` | hot |
| spring autoconfigure | `RuntimeConfigApplier.lastConfigApplyResult` | `volatile` | config |

No module outside `platform-tracing-otel` and the agent extension holds tracing runtime policy state.

### 3.4 Concepts that do not yet exist in production

| Concept | Production status |
| --- | --- |
| `runtimeInstanceId` | **Absent.** Appears only in E2E fixtures (`PrivateMBeanServerProbeMain`, `PrivateMBeanServerExtension`) and in planning documents. `PlatformResourceProvider:41` mentions the OTel SDK's `ServiceInstanceIdResourceProvider` in a comment only, which is a different concept. |
| `acceptedSequence` / `acceptedState` / `AcceptedPolicy` | **Absent from production Java entirely.** Present only in `docs/`. |

This matters for the G1 assessment: `runtimeInstanceId` is a new Agent-lifecycle concept, so a
domain-neutral generic primitive would have to acquire it from scratch, not merely expose something it
already had.

### 3.5 Mutation path

```text
JMX caller
  -> PlatformControlProtocolMBean.applyPolicy(CompositeData)      [extension:66]
    -> TracingControlProtocol.decode(Map)                         [api]
    -> RuntimePolicyControlHandler.handle(DecodeResult)            [otel:59]
      -> RuntimePolicyControlDomainValidator.validate(payload)     [otel]
      -> RuntimeControlMutationPolicy.evaluate(operation)          [otel:89]
      -> JmxRuntimePolicyApplier.apply(op, payload, source)        [extension:54]
        -> PlatformSamplingControl.updateSamplingPolicy(...)       [extension:133 -> 281]
          -> SamplerStateHolder.tryApplyPolicyUpdate(...)          [extension:85]
            -> VersionedStateHolder.tryUpdate  (CAS + retry loop)  [otel:74]
        -> PlatformValidationControl.updateValidationPolicy(...)   [extension:166 -> 43]
          -> ValidatingSpanProcessor -> ValidationPolicyHolder -> VersionedStateHolder CAS
```

Two structural observations that S-01 relies on:

1. `JmxRuntimePolicyApplier.apply` returns `void`. `RuntimePolicyControlHandler` reports `SUCCESS`
   after the applier returns, so the protocol result is decided **without knowing whether the
   underlying CAS succeeded**. Failure surfaces only as an MBean-level counter increment and a log
   line. An expected-base store makes this reportable, because the commit outcome becomes a value.
2. There are multiple concurrent entry points into the same `SamplerStateHolder`: the unified
   `applyPolicy` protocol, five per-field MBean setters, and the Spring `PlatformTracingJmxClient`.
   Each per-field setter performs its own independent CAS. This is precisely the multi-writer
   situation that expected-base semantics exist to arbitrate.

---

## 4. Current state owners and version owners

### 4.1 Accepted-state owners today

There is no single accepted state. There are **six independent mutable owners** of runtime tracing
policy state in production. Three are versioned policy holders, each with its own version line, none
coordinated with the others:

| # | Owner | Version line |
| --- | --- | --- |
| 1 | `SamplerStateHolder` → `SamplerState.version` | starts at 1 (`SamplerStateHolder` ctor line 40) |
| 2 | `ScrubbingPolicyHolder` → `ScrubbingSnapshot.version` | starts at 1 (`ScrubbingSpanProcessor` ctor line 111) |
| 3 | `ValidationPolicyHolder` → `ValidationSnapshot.version` | independent |

Three more are **unversioned bare atomics**, each mutated directly with no expected base and no
version at all:

| # | Owner | State field | Version line |
| --- | --- | --- | --- |
| 4 | `SafeSpanExporter` | `AtomicBoolean exportEnabled` | **none** |
| 5 | `PlatformPropagationGate` | `static final AtomicBoolean enabled` | **none** |
| 6 | `PlatformLogControl` | `static final AtomicReference<LogLevel> level` | **none** |

Owners 4–6 were missing from the original inventory. They are inventoried in full in §4.5, which
closes blocker **B1**. Their absence understated the migration: three of the eight Category A leaves
live in owners that have no version, no expected base and no read-back path, which is a stronger
argument for the selected primitive than the original report made, and simultaneously more migration
work (§10).

`ReadAppliedStateHandler.read()` exposes `_meta.samplingConfigVersion` and
`_meta.validationConfigVersion` as separate values, and does not read scrubbing state, export state,
propagation state or log level at all. An operator therefore cannot name "the" accepted configuration
with one number, and for three leaves cannot read the accepted value back through the protocol at all.

### 4.2 Version-increment owners — the duplicate-ownership finding

`VersionedStateHolder` does **not** increment. The increment `prev.version() + 1` is computed in
**four distinct places**:

| # | Increment owner | Location |
| --- | --- | --- |
| 1 | `SamplerPolicyUpdate.buildNext` | extension, line 61 |
| 2 | `ScrubbingPolicyUpdate.buildNext` | extension, lines 38 and 47 |
| 3 | `ValidationPolicyUpdate.buildNext` | `platform-tracing-otel`, line 20 |
| 4 | `PlatformSamplingControl` **inline lambdas** | extension, lines 43, 86, 118, 167, 215 — five separate copies inside the JMX MBean, bypassing `SamplerPolicyUpdate` entirely |

E3 invariant 7 requires exactly one owner of `acceptedSequence`. The repository currently has four
owners and eight increment sites.

### 4.3 Unconditional-set path

`SamplerStateHolder.update(SamplerState)` line 64:

```java
holder.tryUpdate(prev -> newState);
```

The lambda ignores `prev` completely. Combined with the retry loop, this is an unconditional set that
always eventually wins, discarding whatever a concurrent writer published. No production code calls it
today — only tests — but it is public API on a production class, and it is exactly the misuse case E3
forbids.

### 4.4 Cost amplification of the retry loop

`SamplerState`'s constructor calls `SamplingPolicySnapshotFactory.create(...)`, which normalises and
compiles the sampling policy snapshot. Because `VersionedStateHolder` re-invokes the builder on every
CAS failure, **each retry re-normalises and re-compiles a full policy snapshot**. Retry amplification
here is allocation- and CPU-heavy, not just a repeated compare-and-set. Quantifying this is P5 scope;
it is recorded because it strengthens the case against keeping a retry loop in the accepted-state path.

### 4.5 Complete production owner and caller inventory — corrects B1

The original inventory covered only the three `VersionedStateHolder`-backed holders. That was
incomplete: it understated the accepted-state migration by three owners and overstated it by one.
This section is the authoritative inventory. Every entry below was read from `src/main/java` at the
cited line; **no caller was assumed**. Where a claimed caller does not exist, it is recorded with a
`NOT_*` role rather than omitted.

Full machine-readable form, carrying all twelve required fields per site — module, package, class,
method, state field, read/write role, current mutation entry point, current gate, target Category A
leaf, future P1 replacement, future deletion/adaptation phase, zero-reference proof — is
`repository-symbol-inventory.json` → `correctiveEvidence.accessSites`, **48 sites over 6 owners**.
`S01OwnerInventoryEvidenceTest` fails the build if any site omits any of the twelve fields, so the
JSON is the checked source of truth and the tables below are a readable projection of it. The test
also requires the complete exporter JMX read-back chain and the Spring refresh delivery trigger,
so a populated but incomplete site list cannot pass silently.

#### 4.5.1 Exporter state — `SafeSpanExporter.exportEnabled` (18 sites)

Module `platform-tracing-otel-javaagent-extension`, package `...javaagent.exporter`. Field declared at
line 33, `AtomicBoolean`, initialised `true`, no version and no expected base.

| Class · method | Role | Gate today |
| --- | --- | --- |
| `SafeSpanExporter` field, line 33 | `OWNER_FIELD` | none — writable with no mutation policy |
| `SafeSpanExporter.export(...)`, line 50 | `READER_HOT` | n/a — per export **batch**, not per span; gated batches are counted into `gatedSpans` at line 51 and reported successful at line 52 |
| `SafeSpanExporter.isExportEnabled()`, line 152 | `READER` | n/a |
| `SafeSpanExporter.metricsSnapshot()`, line 174 | `READER` | n/a — emits `EXPORT_ENABLED` as 1/0; today the **only** way the Spring side can observe the effective gate |
| `PlatformExportControl.getSafeExporterMetrics()`, lines 81–84 | `READER_JMX` | n/a — projects `EXPORT_ENABLED` into the EXPORT MBean |
| `PlatformExportControlMBean.getSafeExporterMetrics()`, line 24 | `READER_JMX_CONTRACT` | process-local JMX read contract |
| `SafeSpanExporter.setExportEnabled(boolean)`, line 156 | `WRITER` | **none** — unconditional set, no expected base, no version |
| `PlatformExportControl.isExportEnabled()`, lines 28–31 | `READER` | n/a — `false` when no exporter is registered |
| `PlatformExportControl.setExportEnabled(boolean)`, lines 34–42 | `WRITER` | **none** — no `RuntimeControlMutationPolicy`; records `JmxConfigReloadRecorder.record("export", true, -1)` at line 41 |
| `PlatformTracingJmxRegistrar.setSafeExporter/getSafeExporter`, lines 140, 169 | `HOLDER` | lifecycle only |
| `PlatformExportProcessorFactory` | `HOLDER_CREATOR` | lifecycle only — startup pipeline construction |
| `PlatformTracingJmxClient.setExportEnabled(boolean)`, lines 353–357 | `WRITER_INDIRECT` | `requireDomain(EXPORT)` only — fails closed if the MBean is absent, but no authorization and no expected base |
| `PlatformTracingJmxClient.getExportMetrics()/getSafeExporterMetrics()`, lines 315–349 | `READER_INDIRECT` | JMX read-back; empty result on unavailable MBean/attribute |
| `TracingActuatorEndpoint.tracing()`, line 149 | `READER_DELIVERY` | GET actuator delivery of the accepted exporter projection |
| `TracingActuatorEndpoint.updateTracing("exportEnabled", …)`, lines 249–259 | `WRITER_INDIRECT` | `assertMutationAllowed()` line 204 — `platform.tracing.actuator.mutation-enabled`, **default false** |
| `RuntimeConfigApplier.applyAll(...)`, lines 87–88 | `WRITER_INDIRECT` | `client.allMBeansAvailable()` only |
| `TracingRefreshScopeAutoConfiguration.tracingEnvironmentChangeListener(...)`, lines 55–60 | `WRITER_DELIVERY_TRIGGER` | `EnvironmentChangeEvent` is the sole production caller of `applyAll` |
| `JmxRuntimePolicyApplier.apply(...)`, lines 53–69 | **`NOT_ROUTED`** | the mutation policy gates this path, but the exporter leaf **never reaches an owner** |

Zero-reference proof: `SafeSpanExporter` matches exactly **five** files under `**/src/main/java` —
itself, `PlatformExportControl`, `PlatformTracingJmxRegistrar`, `PlatformExportProcessorFactory`, and
`RateLimitedLogger` (Javadoc mention only). No Spring module references the type.

#### 4.5.2 Propagation state — `PlatformPropagationGate.enabled` (11 sites)

Package `...javaagent.propagation`. `AtomicBoolean` at line 31, reached in production only through the
**static `SHARED` singleton at line 29** — the process has exactly one mutable propagation policy, with
no version and no gate.

| Class · method | Role | Gate today |
| --- | --- | --- |
| `PlatformPropagationGate` field, line 31 / `SHARED`, line 29 | `OWNER_FIELD` | none |
| `PlatformPropagationGate.isEnabled()`, line 39 | `READER` | n/a |
| `PlatformPropagationGate.setEnabled(boolean)`, line 43 | `WRITER` | **none** — unconditional set |
| `InboundTraceControlPropagator.extract(...)` line 68, `.inject(...)` line 90 | `READER_HOT` | n/a — **per request**, both directions; verified to contain no write |
| `PlatformDiagnosticsControl.isPropagationEnabled()`, lines 23–26 | `READER` | n/a |
| `PlatformDiagnosticsControl.setPropagationEnabled(boolean)`, lines 28–32 | `WRITER` | **none** — writes the shared singleton directly, then records `JmxConfigReloadRecorder.record("propagation", true, -1)` |
| `PlatformTracingJmxClient.setPropagationEnabled(boolean)`, lines 438–441 | `WRITER_INDIRECT` | `requireDomain(DIAGNOSTICS)` only |
| `TracingActuatorEndpoint.updateTracing("propagationEnabled", …)`, lines 261–271 | `WRITER_INDIRECT` | `assertMutationAllowed()`, default false |
| `RuntimeConfigApplier.applyAll(...)`, lines 90–91 | `WRITER_INDIRECT` | `client.allMBeansAvailable()` only |
| `TracingRefreshScopeAutoConfiguration.tracingEnvironmentChangeListener(...)`, lines 55–60 | `WRITER_DELIVERY_TRIGGER` | sole production caller of `applyAll` |
| `JmxRuntimePolicyApplier.apply(...)`, lines 53–69 | **`NOT_ROUTED`** | the propagation leaf never reaches an owner |

Zero-reference proof: `PlatformPropagationGate` matches exactly **three** files under
`**/src/main/java` — itself, `InboundTraceControlPropagator`, `PlatformDiagnosticsControl`. No Spring
module references the type.

#### 4.5.3 Diagnostics log-level state — `PlatformLogControl.level` (11 sites)

Package `...javaagent.safety`. `AtomicReference<LogLevel>` at line 54, reached in production only
through the **static `SHARED` singleton at line 51**.

| Class · method | Role | Gate today |
| --- | --- | --- |
| `PlatformLogControl` field, line 54 / `SHARED`, line 51 | `OWNER_FIELD` | none |
| `PlatformLogControl.getLevel()` line 60, `isEnabled(LogLevel)` line 89 | `READER` | n/a |
| `PlatformLogControl.setLevel(LogLevel)` line 64, `setLevel(String)` line 76 | `WRITER` | **none** — a `null` level is ignored at line 65 and an unparseable string returns `false` at line 83; that is last-known-good input handling, not a mutation gate |
| `RateLimitedLogger.warn(...)` line 87, `logRecoveryOnce(...)` line 126 | `READER` | n/a — verified to contain no write |
| `PlatformDiagnosticsControl.getPlatformLogLevel()`, lines 44–47 | `READER` | n/a |
| `PlatformDiagnosticsControl.setPlatformLogLevel(String)`, lines 49–60 | `WRITER` | **none** — an unknown value increments the invalid-config counter and throws `IllegalArgumentException` (lines 52–56); input validation, not a mutation policy |
| `PlatformTracingJmxClient.setPlatformLogLevel(String)`, lines 444–447 | `WRITER_INDIRECT` | `requireDomain(DIAGNOSTICS)` only |
| `TracingActuatorEndpoint.updateTracing("logLevel", …)`, lines 273–285 | `WRITER_INDIRECT` | `assertMutationAllowed()`, default false |
| `RuntimeConfigApplier.applyAll(...)`, lines 94–99 | `WRITER_INDIRECT` | `client.allMBeansAvailable()` only |
| `TracingRefreshScopeAutoConfiguration.tracingEnvironmentChangeListener(...)`, lines 55–60 | `WRITER_DELIVERY_TRIGGER` | sole production caller of `applyAll` |
| `TracingControlProtocolKeys` constants | **`NOT_IN_PROTOCOL`** | the leaf has **no wire key at all** |

Zero-reference proof: `PlatformLogControl` matches exactly **three** files under `**/src/main/java` —
itself, `RateLimitedLogger`, `PlatformDiagnosticsControl`. No Spring module references the type.

#### 4.5.4 Four findings that only the complete inventory exposes

1. **Three leaves have no version and no expected base at all.** Owners 4–6 are bare atomics. For
   these, "accepted state" does not merely lack coordination — it does not exist as a concept.
2. **Two of the three are process-wide `static` singletons.** Any writer anywhere in the Agent
   extension mutates global process state with no gate. This is a stronger argument for a single
   owning store than the original report made.
3. **Write-only asymmetry.** `PlatformTracingJmxClient` can write propagation and log level but has no
   read-back method for either, and `ReadAppliedStateHandler` exposes neither. An operator can change
   these two leaves through Actuator and then cannot read back what was accepted.
4. **The protocol validates leaves it never applies.** `JmxRuntimePolicyApplier.apply` routes only
   sampling and validation (lines 44–50, 67–68). `export.enabled` and `propagation.enabled` are
   accepted by the schema and then silently dropped, and `diagnostics.log-level` has no protocol key
   at all. Combined with §3.5 finding 1 — the applier returns `void` and the handler reports `SUCCESS`
   regardless — the unified protocol today can return `SUCCESS` for a leaf that reached no owner.

Only the gated path is `TracingActuatorEndpoint`, via `assertMutationAllowed()`, default `false`. Every
other write path into the three new owners is ungated.

### 4.6 The eight Category A leaves — current owner and target owner — corrects B2

The accepted runtime envelope contains **exactly eight** Category A leaves. Five distinct current
owners collapse into **one** target owner.

| # | Category A leaf | Current owner · state field | Target |
| --- | --- | --- | --- |
| 1 | `sampling.enabled` | `SamplerStateHolder` → `SamplerState.enabled` (line 36) | `EffectiveTracingPolicy.samplingEnabled` |
| 2 | `sampling.ratio` | `SamplerStateHolder` → `SamplerState.defaultRatio` (line 40) | `EffectiveTracingPolicy.samplingRatio` |
| 3 | `sampling.route-ratios` | `SamplerStateHolder` → `SamplerState.routeRatios` (line 39) | `EffectiveTracingPolicy.samplingRouteRatios`, deeply immutable key-sorted map |
| 4 | `validation.enabled` | `ValidationPolicyHolder` → `ValidationSnapshot.enabled` (line 22) | `EffectiveTracingPolicy.validationEnabled` |
| 5 | `validation.strict` | `ValidationPolicyHolder` → `ValidationSnapshot.strict` (line 23) | `EffectiveTracingPolicy.validationStrict`, bounded by the Category B startup envelope `…javaagent.validation.strict-runtime-allowed` |
| 6 | `exporter.enabled` | `SafeSpanExporter.exportEnabled` (line 33) | `EffectiveTracingPolicy.exporterEnabled` |
| 7 | `propagation.trace-control-headers.enabled` | `PlatformPropagationGate.enabled` (line 31, static `SHARED`) | `EffectiveTracingPolicy.propagationTraceControlHeadersEnabled` |
| 8 | `diagnostics.log-level` | `PlatformLogControl.level` (line 54, static `SHARED`) | `EffectiveTracingPolicy.diagnosticsLogLevel`, closed enum `OFF\|ERROR\|WARN\|INFO\|DEBUG\|TRACE` |

Target owner for all eight: `PROPOSED AcceptedPolicyStateStore` →
`AcceptedPolicyEnvelope.policy()`. Names stay `PROPOSED` until P1.

### 4.7 Excluded from the accepted runtime envelope — corrects B2

The envelope must **not** carry these. Each is Category B or is never representable as accepted state.

| Excluded leaf | Why | Current owner | Replacement |
| --- | --- | --- | --- |
| `scrubbing.enabled` | startup-only; the plan's Category B list excludes it from Category A | `ScrubbingPolicyHolder` → `ScrubbingSnapshot` | immutable Agent startup Category B configuration |
| `scrubbing.built-in-rules` | rule-set composition **is** pipeline composition, which the Category A schema forbids | `ScrubbingPolicyHolder` → `ScrubbingSnapshot` | immutable Agent startup Category B configuration |
| `sampling.drop-paths` | startup-only | `SamplerState.droppedRoutes` (line 37) | immutable Agent startup Category B configuration |
| `sampling.force-record-values` | startup-only | `SamplerState.forceRecordValues` (line 38) | immutable Agent startup Category B configuration |
| any secret | no desired-state, digest, audit or drift representation is safe for a raw secret | none — no accepted-state owner holds a secret today | startup environment only; never in the envelope |
| any pipeline-composition field | processor/exporter topology is startup structure; changing it at runtime replaces components, not values | `PlatformSpanProcessorFactory` / `PlatformExportProcessorFactory` startup wiring | immutable Agent startup wiring |

Note the asymmetry that the original report missed: `SamplerState` holds **both** Category A leaves
(1–3) and Category B leaves (`droppedRoutes`, `forceRecordValues`). Its P3 migration therefore splits
one record across two namespaces rather than moving it wholesale.

`ScrubbingPolicyHolder` is **not** hidden from this inventory. It is recorded as mutable owner #6 in
§4.1 and as a first-class entry in `correctiveEvidence.mutableOwners`, with these five facts fixed:

- it **is** a current mutable production owner;
- it does **not** migrate to accepted Category A state;
- its replacement is **immutable Agent startup Category B configuration**;
- it is **not** a field of `EffectiveTracingPolicy` and is not carried by the accepted envelope;
- its exact deletion phase must be agreed with the **coherent Category B namespace cutover**, not with
  the accepted-state migration.

The Cursor plan now reflects this split: P3 removes only Category A holders, and
`ScrubbingPolicyHolder` deletion is attached to the coherent P6 Category B cutover.

---

## 5. Candidate definitions

### Candidate G0 — reuse `VersionedStateHolder` unchanged

Use the existing generic primitive with no production change.

**Rejected. Falsified executably, not by argument.** `S01VersionedStateHolderContrastTest`
demonstrates, deterministically:

- **No expected base can be presented.** No `tryUpdate` overload accepts one, so `runtimeInstanceId`
  and `acceptedSequence` cannot participate in the commit decision at all.
- **`STALE` is inexpressible.** Every overload returns `boolean`; `false` already means "candidate was
  null, or threw, or failed validation", so it cannot also mean "your base was stale".
- **A stale caller is silently rebased and overwrites the winner.** With a latch-controlled handoff:
  builder invocations = 2, bases handed to the builder = `[0, 1]`, the stale caller was told it
  **succeeded**, and the final state is the stale caller's value — the winner was lost.
- **The sequence advances per attempt, not per accepted commit.** Eight writers on one logical base:
  all eight report success, version advances +8. E3 requires 1 and +1.

It also fails ownership: `VersionedStateHolder` lives in `platform-tracing-otel`, which is on the
Spring application classpath (§12).

For the avoidance of doubt, this is not a defect report against `VersionedStateHolder`. Its
last-known-good-with-retry contract is correct and appropriate for the three domain holders it serves
today. It is simply a different contract from E3.

### Candidate G1 — constrained refactor of the generic primitive

Add expected-base semantics to `VersionedStateHolder` while keeping it domain-neutral.

**Rejected.** Three independent reasons:

1. **It breaks the consumers that depend on rebase.** All three holders and the five inline
   `PlatformSamplingControl` sites pass `prev -> buildNext(prev, ...)` and derive the version from
   whatever `prev` the retry loop hands them. Removing the loop changes the meaning of all eight call
   sites: they would begin failing where they currently retry. Keeping the loop alongside a new
   expected-base method produces two mutation modes on one primitive — a dual old/new execution path,
   which the pre-production policy forbids by default.
2. **`runtimeInstanceId` cannot be added domain-neutrally without drifting into a framework.** It does
   not exist in production yet (§3.4) and it is an Agent-lifecycle concept. A domain-neutral primitive
   could only carry it as an opaque token plus a pluggable identity notion, which is the "generic
   configuration framework" outcome the constraints explicitly exclude.
3. **Ownership is wrong.** See §12: `platform-tracing-otel` is on the Spring application classpath.

It would also invalidate a committed contract test
(`VersionedStateHolderTest.concurrentUpdatesAreSerializedWithoutLoss`) in order to serve one domain's
requirement.

### Candidate D — Agent-specific accepted-state primitive — **SELECTED**

A small store owned by `platform-tracing-otel-javaagent-extension`, holding one immutable envelope in
one `AtomicReference`, performing exactly one `compareAndSet` per commit.

Chosen on merit, not on locality. See §10 for scoring and §11 for the design.

### Candidate X — bare `AtomicReference<ImmutableState>` with no abstraction

Used as the complexity and performance baseline, as instructed.

**Not selected, but it sets the bar that Candidate D must not exceed.** Its value is that it proves
Candidate D costs nothing on the hot path: D's read *is* a single `AtomicReference.get()`, identical to
X, measured at 0.890 ns/op and 0 bytes. D therefore buys expected-base semantics, single version
ownership and misuse resistance for **zero hot-path cost** over the bare baseline.

X is rejected because `AtomicReference` exposes `set`, `getAndSet` and raw `compareAndSet` to every
caller, so the unconditional-set path documented in §4.3 stays trivially reachable and the sequence has
no owner at all. It scores 0/5 on misuse resistance.

---

## 6. Executable proof design

All proofs live in test and benchmark source sets of `platform-tracing-bench`. No production Java was
touched. The `S01*` prefix marks every artefact as spike evidence, not an approved production name.

| Location | Contents |
| --- | --- |
| `platform-tracing-bench/src/test/java/.../bench/acceptedstate/` | candidate primitive (6 types), orchestration harness (2 types), 7 proof classes |
| `platform-tracing-bench/src/jmh/java/.../bench/acceptedstate/` | 2 JMH benchmark classes |

The primitive lives in the **test** source set and the benchmarks consume it from there. This works
with zero build wiring because the `me.champeau.jmh` plugin's default `includeTests = true` already
places test output on the JMH classpath. One copy of the primitive serves both JUnit and JMH, and no
cross-project source-set access was needed. An initial attempt to host the primitive in the `jmh`
source set and have `test` consume it produced a `compileJmhJava` ↔ `compileTestJava` circular
dependency and was abandoned.

Two design decisions carry most of the proof strength:

**Reference identity as the "no CAS occurred" observable.** A successful commit always installs a
*new* envelope instance. So `assertThat(after).isSameAs(before)` proves no compare-and-set succeeded.
This is strictly stronger than value equality, which cannot distinguish "not committed" from
"committed an equal value", and it removes any need for a spy or an observability hook inside the
store. The `UNCHANGED`, `REJECTED`, `FAILED` and mismatch proofs all use it.

**Sequence arithmetic as the rebase detector.** In the one-winner test, eight writers race one base per
round. If the store retried internally, the sequence would advance by eight per round. Asserting it
advances by exactly one is a direct, non-statistical rebase proof.

Proof-to-invariant mapping:

| Proof class | Invariants covered |
| --- | --- |
| `S01OneWinnerCommitTest` | 3, 4, 5, 6, 8 |
| `S01ExpectedBaseMismatchTest` | 5, 10, 11 |
| `S01UnchangedBoundaryTest` | 9, 10, 13 |
| `S01PublicationVisibilityTest` | 1, 2, 12, 14 |
| `S01HotPathAllocationTest` | 14 |
| `S01ApiMisuseTest` | 6, 7, 13, 15 |
| `S01VersionedStateHolderContrastTest` | falsifies G0 against 3–8 |

Corrective proofs added by pass S-01-C1, over the **concrete** types of §11.1:

| Proof class | Covers |
| --- | --- |
| `S01CategoryALeafContractTest` | exactly eight Category A fields, no Category B field, immutable field types, deterministic route-map order, concrete store surface |
| `S01DeepImmutabilityTest` | defensive ingress copy, unmodifiable exposed map, stable object graph under concurrent readers |
| `S01ConcreteStoreCommitTest` | one-winner, no-rebase, `runtimeInstanceId` and `acceptedSequence` semantics re-proven on the concrete store |
| `S01OwnerInventoryEvidenceTest` | the committed inventory itself: twelve fields per access site, the eight-leaf map, Category B exclusion |

---

## 7. Test and benchmark results

Full transcripts: `docs/research/evidence/accepted-state-primitive/test-results.txt` and
`benchmark-results.txt`.

### 7.1 Tests — `acceptedStatePrimitiveVerify`

Corrective pass, `--rerun-tasks`, so every original proof was re-executed rather than reported from
cache: `BUILD SUCCESSFUL`, **54 tests, 0 failures, 0 errors, 0 skipped** — the 27 original plus 27
corrective. The original pass was 27/0/0/0.

Original proofs, all still passing:

| Proof | Result | Observed |
| --- | --- | --- |
| One-winner expected-base commit | **PASS** | 250 rounds × 8 writers: exactly 1 `APPLIED` and 7 `STALE` every round; sequence +1 every round; winner's candidate survives; 2000 attempts → 250 accepted commits |
| No retry / no rebase | **PASS** | candidate constructions = 2000 = one per attempt; sequence +1 per round, not +8; store surface has no builder parameter |
| `runtimeInstanceId` mismatch | **PASS** | `STALE`; envelope identical by reference; sequence and policy unchanged |
| `acceptedSequence` mismatch | **PASS** | both stale-behind and stale-ahead → `STALE`, no state change |
| `UNCHANGED` boundary | **PASS** | identical candidate → `UNCHANGED`, envelope identical by reference, sequence unchanged; `REJECTED` and `FAILED` likewise never enter the store |
| Publication and visibility | **PASS** | 20 000 commits × 6 unsynchronised readers, 0 torn or partial observations |
| Hot-path allocation (exact) | **PASS** | **0 bytes** over 2 000 000 reads (HotSpot per-thread counter) |
| API misuse resistance | **PASS** | 8 surface assertions, all held |
| G0 falsification | **PASS** | builder invoked twice, bases `[0, 1]`, stale caller reported success and overwrote the winner |

Corrective proofs, 26 tests over the concrete types:

| Proof | Result | Observed |
| --- | --- | --- |
| Exactly eight Category A fields | **PASS** | reflection over the policy record: 8 components, in the fixed leaf order |
| No Category B field | **PASS** | no component name matches `scrub*`, `dropPath*`, `droppedRoutes`, `forceRecord*`, `secret*`, `password*`, `token*`, `processor*`, `exporterClass*` |
| All field types immutable | **PASS** | 7 primitives/closed enum + 1 `NavigableMap`; no mutable collection or mutable reference component |
| Defensive ingress copy | **PASS** | mutating the caller's source map after construction, including `clear()`, leaves the accepted policy byte-for-byte unchanged |
| Unmodifiable exposed map | **PASS** | `put`, `remove`, `clear`, `putAll`, `merge`, `compute`, `replaceAll`, `entrySet().iterator().remove()`, `values()`, `keySet()`, `descendingMap()`, `headMap()`, `tailMap()`, `subMap()` — every mutator throws `UnsupportedOperationException` |
| Deterministic key order | **PASS** | two policies built from differently ordered source maps iterate identically; order is ascending key order, not insertion order |
| Stable object graph under concurrent readers | **PASS** | 6 readers × 200 000 observations = **1 200 000** reads across commits, **0** violations of leaf-set, ordering or sequence/policy coherence |
| Concrete one-winner | **PASS** | 100 rounds × 8 writers: exactly 1 `APPLIED`, 7 `STALE`, sequence +1 per round |
| Concrete no-rebase | **PASS** | sequence advances by rounds, not by attempts; store has no builder parameter |
| Concrete store surface | **PASS** | one `AtomicReference` field; surface exactly `{current, commit}`; payload type is not generic |
| Inventory completeness | **PASS** | all 48 access sites declare all 12 required fields; exporter JMX read-back and Spring refresh delivery are mandatory; 8 leaves mapped with current and target owner; 6 excluded leaves recorded; `ScrubbingPolicyHolder` present with `migratesToAcceptedState=false` |

### 7.2 Benchmarks — JMH

Profile `-PjmhDev`: 1 fork, 3 × 500 ms warm-up, 5 × 500 ms measurement, `gc` profiler.

| Benchmark | ns/op (indicative) | `gc.alloc.rate.norm` |
| --- | --- | --- |
| `readAcceptedRatio` | 0.890 ± 0.028 | **≈ 10⁻⁶ B/op** |
| `readAcceptedRouteRatioCount` | 1.040 ± 0.015 | **≈ 10⁻⁶ B/op** |
| `readAcceptedSequenceAndPolicy` | 1.193 ± 0.035 | **≈ 10⁻⁶ B/op** |
| `readWhileCommit:contendedRead` (7 threads) | 8.146 | see note |
| `readWhileCommit:contendedCommit` (1 thread) | 266.722 | see note |

`gc.count` is ≈ 0 for all three read benchmarks: no collection was triggered.

Two honest qualifications:

- The group-level figure `readWhileCommit:·gc.alloc.rate.norm = 1.021 B/op` is **not** reader
  allocation. It is the group aggregate, amortising the writer's two allocations per commit (one
  candidate policy, one envelope) across all eight threads' operations. Per-commit allocation is
  cold-path and is exactly how the design buys a zero-allocation read.
- `contendedCommit` at 266.722 ns/op **includes** building a fresh candidate policy record and its
  one-entry immutable map on every call. It is an upper bound on candidate construction plus one
  compare-and-set, not the cost of the store.

**Corrective pass: JMH was `NOT_RUN`, and is not claimed as re-executed.** The read mechanism is
unchanged — one `AtomicReference.get()` followed by plain final-field reads — and the corrective work
changed only the *static type* of the payload, not the read path or the field layout of the fields that
were benchmarked. The original benchmark evidence therefore remains in force for the read mechanism and
is retained unmodified in `benchmark-results.txt`. Re-running JMH on a reference lab is P5 scope.

The one figure that the concrete payload could in principle move is `contendedCommit`, because a
`TreeMap`-backed defensive copy is not free. That is **cold-path candidate construction**, it is
excluded from all read benchmarks, and §7.1's exact per-thread allocation counter still shows 0 bytes
on the read path. No latency budget is asserted here either way.

### 7.3 Full `test`

`.\gradlew.bat test --no-daemon` → `BUILD SUCCESSFUL` in both passes.

Executed in the corrective run: `:platform-tracing-bench:test` (recompiled and re-executed, since the
corrective sources land in that module's test source set). Other module `test` tasks were `UP-TO-DATE`
under Gradle incrementality, since neither pass changed any source in those modules.

**Reported as `SKIPPED/NOT_RUN`, never as PASS:**

| Task | State | Reason |
| --- | --- | --- |
| `:platform-tracing-e2e-tests:test` | **SKIPPED** | opt-in Docker-backed E2E module |
| `:platform-tracing-samples:test` | **SKIPPED** | no test sources |
| `:platform-tracing-spring-boot-starter-servlet:test` | NO-SOURCE | no test sources |
| `:platform-tracing-spring-boot-starter-reactive:test` | NO-SOURCE | no test sources |
| `:platform-tracing-perf-tests:test` | NO-SOURCE | no test sources |

None of these gates is required for E3, and none is claimed as evidence here.

---

## 8. Java Memory Model analysis

**Mechanism.** One `final AtomicReference<S01AcceptedEnvelope<S>>` field. `get()` is a volatile read;
`compareAndSet` is an atomic read-modify-write with volatile-write semantics, specified as
`VarHandle.compareAndSet` behaviour.

**Safe publication.** The envelope and the policy are immutable records with all-final fields, fully
constructed before the `compareAndSet`. The successful `compareAndSet` is a volatile write; every
subsequent `get()` that observes the new reference is a volatile read of that write. The write
therefore *happens-before* the read, so all final-field writes performed during construction are
visible. **Readers need no synchronisation whatsoever**, and cannot observe a partially constructed
envelope or policy.

**Logical atomicity of the triple.** Because `runtimeInstanceId`, `acceptedSequence` and `policy` live
in one object behind one reference, a single `get()` yields a mutually consistent triple. This is the
structural reason the forbidden "two `AtomicReference`s for logically atomic policy and sequence"
pattern is unnecessary, and it is what `S01PublicationVisibilityTest` verifies directly by asserting
`acceptedSequence == policy.seed()` on every one of many millions of reader observations across
20 000 commits.

**One-winner argument.** For a fixed witnessed envelope `W`, `compareAndSet(W, next)` succeeds for at
most one thread, because the first success replaces `W` and every later attempt compares against a
reference that is no longer current. Both failure routes — the pre-check finding a different base, and
the `compareAndSet` losing — return `STALE` without any write. No thread that did not perform a
successful `compareAndSet` can affect accepted state.

**Sequence monotonicity.** `acceptedSequence` is only ever computed as `witnessed.acceptedSequence() + 1`
inside `commit`, on the path guarded by a successful `compareAndSet`. Since each success consumes a
distinct predecessor, the sequence is strictly monotonic with no gaps and no duplicates. No caller can
supply or mutate it.

**Why not `VarHandle`.** A `VarHandle` with `getAcquire`/`compareAndSet` could relax the read to
acquire semantics. It is not proposed: the repository has zero production `VarHandle` usage (§3.3), the
measured read is already 0.890 ns/op at zero allocation, and `AtomicReference` is markedly easier to
reason about and review. Preferring obvious JMM semantics over a micro-optimisation with no
demonstrated need is the correct trade here.

**Why not `AtomicStampedReference`.** It would split policy and sequence into a reference plus a
separate stamp, reintroduce a boxed pair on the write path, and add nothing: the sequence already lives
inside the immutable envelope.

---

## 9. API misuse analysis

Each forbidden operation, and how the selected design excludes it. All eight rows are asserted by
`S01ApiMisuseTest`.

| Misuse case | Status in selected design | Assertion |
| --- | --- | --- |
| Increment the sequence manually | **Impossible.** No caller-visible sequence mutator; the store computes it | `acceptedSequenceHasNoMutator` |
| Unconditional `set` | **Impossible.** Surface is exactly `{current, commit}`; no `set*`/`put*`/`replace*`/`reset*`/`update*`/`getAndSet*`/`force*` | `storeSurfaceIsTwoOperations`, `noUnconditionalSetExists` |
| Install a hand-built envelope | **Impossible.** No method accepts an envelope | `noEnvelopeInjectionPointExists` |
| Mutate accepted state in place | **Impossible.** Envelope and policy are records with all-final fields | `acceptedSequenceHasNoMutator` |
| Bypass `runtimeInstanceId` | **Impossible.** It is a mandatory, non-null expected-base component | `runtimeInstanceIdCannotBeBypassed` |
| Rebase | **Unrepresentable.** `commit` takes a built candidate, not a builder; there is nothing to re-invoke | `storeSurfaceIsTwoOperations` |
| Write diagnostics into the CAS payload | **Impossible.** Payload components are exactly `(runtimeInstanceId, acceptedSequence, policy)` | `acceptedStateCarriesNoDiagnostics` |
| Generic `Map<String, Object>` state, or copy-on-read | **Excluded.** Policy is a typed immutable record; consecutive reads return the same instance | `acceptedStateIsTyped` |

Contrast with what exists today: `SamplerStateHolder.update(SamplerState)` is a public unconditional
set (§4.3), and `PlatformSamplingControl` hand-writes the version increment in five places (§4.2).
Both classes of misuse become unrepresentable.

### Answer to the boundary question

> Can the store remain a primitive for atomic commit only, without owning validation, digest
> comparison, audit or diagnostics?

**Yes, and it is proven.** The store contains no validation, no digest comparison, no audit, no
diagnostics, no metrics and no logging. `S01ApplyOrchestrator` shows the responsibilities living
outside it, and `S01UnchangedBoundaryTest` proves the boundary holds by reference identity:

| Outcome | Decided by | Reaches the store? |
| --- | --- | --- |
| `REJECTED` | domain validator, before the store | **No** |
| `FAILED` | candidate construction or validation throwing | **No** |
| `UNCHANGED` | semantic digest comparison against accepted policy | **No** |
| `APPLIED` | the store | Yes |
| `STALE` | the store | Yes |

The exact boundary the brief asked to be proven is therefore: **the store owns `APPLIED` and `STALE`
and nothing else.** The other three outcomes are orchestration concerns and are decided before any
compare-and-set is attempted, which is what keeps invariants 9, 10 and 13 satisfiable.

---

## 10. Weighted scoring

Score 0–5 per criterion; `weighted = Σ(score / 5 × weight)`.

| Criterion | Weight | G0 | G1 | **D** | X |
| --- | --: | --: | --: | --: | --: |
| E3 contract conformance | 20 | 0 | 3 | **5** | 2 |
| Correctness under concurrency | 20 | 2 | 4 | **5** | 2 |
| Java Memory Model clarity | 10 | 4 | 4 | **5** | 5 |
| Hot-path allocation / latency | 15 | 5 | 5 | **5** | 5 |
| Ownership and classloader alignment | 10 | 1 | 1 | **5** | 3 |
| Misuse resistance | 10 | 0 | 2 | **5** | 0 |
| Deletion / consolidation value | 5 | 0 | 3 | **5** | 2 |
| API and cognitive simplicity | 5 | 3 | 1 | **4** | 5 |
| Testability | 3 | 4 | 4 | **5** | 2 |
| Migration risk | 2 | 5 | 1 | **3** | 2 |
| **Weighted total** | **100** | **40.4** | **63.8** | **98.2** | **56.0** |

Evidence for each score:

**E3 contract conformance** — G0 = 0: no expected-base parameter exists and `boolean` cannot express
`STALE` (`surfaceCannotExpressExpectedBase`). G1 = 3: reachable in principle, but `runtimeInstanceId`
can only be an opaque token in a domain-neutral primitive. D = 5: all 17 invariants proven. X = 2:
achievable only if every call site hand-writes the check, with nothing enforcing it.

**Correctness under concurrency** — G0 = 2: internally correct for its own LKG contract, but produces
lost updates under E3 semantics (`staleCallerIsRebasedAndOverwritesWinner`). D = 5: 250 rounds × 8
writers, exactly one winner every round. X = 2: no structural guarantee.

**JMM clarity** — All candidates use `AtomicReference` publication, so all are sound. D and X score 5
for a single reference with no retry loop to reason about; G0 and G1 score 4 because the side-effect-free
builder contract, on which correctness depends, is enforced only by documentation.

**Hot-path allocation / latency** — All 5. The read is one `AtomicReference.get()` in every candidate.
Measured for D: 0.890 ns/op, 0 bytes over 2M reads. **This criterion does not discriminate**, which is
exactly why X is valuable as a baseline: D adds no hot-path cost over bare `AtomicReference`.

**Ownership and classloader alignment** — G0 and G1 = 1: `platform-tracing-otel` is on the Spring
application classpath (`platform-tracing-spring-boot-autoconfigure/build.gradle:9`), so accepted state
would be constructible in the application classloader with no gate preventing a second owner. D = 5:
the extension module's absence from the application classpath is already asserted by
`build.gradle:472` and `gradle/c3-published-consumer/build.gradle:44,46`. X = 3: placement is possible
but ownership is unexpressed.

**Misuse resistance** — G0 = 0: `SamplerStateHolder.update` is a public unconditional set; five inline
version increments in `PlatformSamplingControl`. G1 = 2: retaining `tryUpdate` beside a new `commit`
keeps the unconditional path alive. D = 5: eight surface assertions. X = 0: `AtomicReference.set` and
`getAndSet` are public to every caller.

**Deletion / consolidation value** — D = 5: replaces 3 holders and 4 version-increment owners across 8
increment sites. G0 = 0: consolidates nothing.

**API and cognitive simplicity** — X = 5 (zero new types). D = 4: five small types, no interface, one
logical operation. G1 = 1: two mutation modes, or a generic identity abstraction — framework drift.

**Migration risk** — G0 = 5 (no change). G1 = 1: breaks a committed contract test plus 8 call sites for
a benefit only one domain needs. D = 3: real P1/P3 work, but confined to one module and one rollback
unit.

### Simplicity cross-check

The brief requires choosing the simpler option at equal correctness, and forbids choosing
domain-specific merely because it is local. Applying that test honestly:

- Correctness is **not** equal. D is the only candidate that satisfies E3, so the simplicity tiebreak
  never becomes decisive between D and the others.
- On raw type count, X wins and D adds five types. But D **removes** three holder classes, four
  version-increment owners, and the `UnaryOperator` + `Predicate` + side-effect-free-builder contract.
  Net production types and net mutable owners both **decrease**.
- D introduces **no interface**, no factory, no registry, no adapter, and no SPI. The store is a final
  class with two methods.
- D was not chosen for locality. It was chosen because it is the only candidate that satisfies the
  contract, and because the module boundary it needs is already enforced by existing gates.

---

## 11. Selected primitive

Conceptual API. **Names are illustrative; production naming is P1 work.**

```java
// One immutable unit of accepted state: identity, sequence and policy inseparable.
record AcceptedEnvelope<S>(String runtimeInstanceId, long acceptedSequence, S policy) { }

// Optimistic base a caller must present.
record ExpectedBase(String runtimeInstanceId, long acceptedSequence) { }

enum CommitOutcome { APPLIED, STALE }

record CommitResult<S>(CommitOutcome outcome, AcceptedEnvelope<S> accepted) { }

final class AcceptedPolicyStateStore<S> {

    private final AtomicReference<AcceptedEnvelope<S>> accepted;

    AcceptedEnvelope<S> current();                                   // hot path

    CommitResult<S> commit(ExpectedBase expectedBase, S candidate);  // single shot
}
```

**State fields.** Exactly one: `final AtomicReference<AcceptedEnvelope<S>>`. Asserted by
`storeHoldsSingleAtomicReference`.

**Accepted-state fields.** Exactly `(runtimeInstanceId, acceptedSequence, policy)`. No attempt
metadata, audit, source revision or failure detail.

**Commit semantics.**

1. One volatile read of the accepted envelope (the witness).
2. Reject unless `witness.runtimeInstanceId == expected.runtimeInstanceId` **and**
   `witness.acceptedSequence == expected.acceptedSequence` → `STALE`, no write.
3. Build the successor envelope with `acceptedSequence + 1`, carrying the witnessed identity forward.
4. Exactly one `compareAndSet`. Success → `APPLIED`. Failure → `STALE`, **no loop, no rebuild**.
5. Return the accepted state in force, so a loser learns the fresh base without a second read. This is
   why the store never needs to retry: the decision to recompute belongs to the caller.

**Memory visibility mechanism.** `AtomicReference` volatile read / `compareAndSet` volatile write over
immutable all-final-field records. See §8.

**Hot-path mechanism.** `accepted.get()` followed by plain final-field reads. Zero allocation, zero
lock, zero JMX, one immutable reference load.

**Replaced holders and version owners.**

| Replaced | Kind |
| --- | --- |
| `SamplerStateHolder` | state owner + version line |
| `ValidationPolicyHolder` | state owner + version line |
| `SafeSpanExporter.exportEnabled` | mutable Category A owner without a version |
| `PlatformPropagationGate.enabled` | mutable Category A owner without a version |
| `PlatformLogControl.level` | mutable Category A owner without a version |
| `SamplerPolicyUpdate.buildNext:61` | version increment owner |
| `ValidationPolicyUpdate.buildNext:20` | version increment owner |
| `PlatformSamplingControl:43,86,118,167,215` | five inline version increment sites |
| `SamplerState.version`, `ValidationSnapshot.version` | two Category A version lines → one `acceptedSequence` |

`ScrubbingPolicyHolder`, `ScrubbingSnapshot.version` and `ScrubbingPolicyUpdate` remain outside the
accepted-state migration. They disappear only with the immutable Agent startup Category B cutover.

---

## 12. Exact module placement

**`platform-tracing-otel-javaagent-extension`.** No new Gradle module.

The decisive argument is enforced classloader exclusivity, not proximity:

| Module | On Spring application classpath? | Evidence |
| --- | --- | --- |
| `platform-tracing-otel` | **Yes** | `platform-tracing-spring-boot-autoconfigure/build.gradle:9` — `implementation project(':platform-tracing-otel')` |
| `platform-tracing-otel-javaagent-extension` | **No, and this is gated** | root `build.gradle:472` asserts "not on application classpath via either starter"; `gradle/c3-published-consumer/build.gradle:44,46` assert absence from published consumer compile and runtime module sets; the Spring module references it only as `testImplementation` (line 69) |

Placing accepted state in `platform-tracing-otel` would make it constructible inside the Spring
application classloader, creating a latent second runtime-policy owner — invariant 15 — with no gate
preventing it. Placing it in the extension module makes Agent-classloader exclusivity a property the
build already verifies on every run.

Secondary support: the extension module already owns all three domain holders being replaced, all
policy-writing MBeans, and `JmxRuntimePolicyApplier`. Ownership and dependency direction both improve.

`platform-tracing-otel` retains `VersionedStateHolder` and `VersionedState` unchanged for its existing
last-known-good consumers until P3 decides their fate.

---

## 13. Future P1 change map

Planning input only. **Implementation GO is `NOT_GRANTED`; nothing below may start now.**

| # | Change | Module |
| --- | --- | --- |
| 1 | Add accepted-envelope, expected-base, commit-outcome, commit-result types and the store | extension |
| 2 | Introduce a `runtimeInstanceId` owner bound to Agent extension lifecycle | extension |
| 3 | Bind the accepted policy type to the Category A effective-policy model fixed by E5 | extension |
| 4 | Add the orchestration layer owning `REJECTED`, `UNCHANGED`, `FAILED` around the store | extension |
| 5 | Make `JmxRuntimePolicyApplier.apply` return a commit outcome instead of `void`, so a failed commit stops being invisible to the protocol result (§3.5 observation 1) | extension |
| 6 | Propagate the outcome through `RuntimePolicyControlHandleResult` | `platform-tracing-otel` |
| 7 | Route the five per-field `PlatformSamplingControl` setters through the single expected-base commit path, removing all five inline increments | extension |
| 8 | Migrate `CompositeSampler`, `ValidatingSpanProcessor`, `SafeSpanExporter`, `InboundTraceControlPropagator` and `RateLimitedLogger` reads to the single accepted envelope | extension |
| 9 | Replace Category A `_meta.*ConfigVersion` values in `ReadAppliedStateHandler` with one `acceptedSequence`; do not add Category B scrubbing to accepted runtime state | extension |
| 10 | Keep audit, diagnostics and attempt metadata strictly outside the CAS payload | extension |

Items 5, 6 and 9 change observable behaviour and wire content, so they need the E7 wire-schema decision
and the P0 wire schema contract before implementation.

---

## 14. Future P3 deletion map

Planning input only. **Legacy deletion GO is `BLOCKED`.**

| # | Deletion | Location | Prerequisite |
| --- | --- | --- | --- |
| 1 | `SamplerStateHolder` | extension, `...javaagent.sampler` | all 6 production write sites migrated |
| 2 | `ValidationPolicyHolder` | extension, `...javaagent.processor` | `ValidatingSpanProcessor` migrated |
| 3 | `SamplerStateHolder.update(SamplerState)` unconditional-set path | extension, line 59 | test-only callers migrated |
| 4 | `version` field and `version()` on `SamplerState`, `ValidationSnapshot` | extension + otel | single `acceptedSequence` in force |
| 5 | Five inline `prev.version() + 1` lambdas | `PlatformSamplingControl:43,86,118,167,215` | P1 item 7 |
| 6 | Version arithmetic in `SamplerPolicyUpdate:61`, `ValidationPolicyUpdate:20` | extension + otel | store owns the sequence |
| 7 | `VersionedStateHolder`, `VersionedState`, `VersionedStateHolderTest`, `ADR-versioned-state-runtime-package` | `platform-tracing-otel` | **conditional** — only after the Category B cutover removes the final `ScrubbingPolicyHolder` consumer. See below. |

On item 7: after P3, `ScrubbingPolicyHolder` remains a Category B consumer of
`VersionedStateHolder`. The generic primitive can become dead code only after the coherent P6
Category B cutover removes that holder. Deleting it remains a separate decision from E3 because it
is domain-neutral. **S-01 does not recommend deleting it as part of E3.**

---

## 15. Risks and rollback

| # | Risk | Severity | Mitigation |
| --- | --- | --- | --- |
| 1 | Callers that legitimately need to overwrite regardless of base now receive `STALE` | Medium | This is the intended contract change. P1 must define retry policy in the orchestration layer, never in the store. `CommitResult` returns the fresh base so a caller can rebuild deliberately and visibly |
| 2 | Merging three independent version lines into one `acceptedSequence` changes observable JMX values | Medium | Requires the E7 wire-schema decision and the P0 wire schema contract before P1 items 5, 6, 9 |
| 3 | `runtimeInstanceId` has no production owner yet (§3.4) | Medium | P1 item 2 is a prerequisite for the store; the store must not invent identity itself |
| 4 | Higher `STALE` rate than operators expect, given the five per-field MBean setters each committing independently | Low–Medium | P1 item 7 routes them through one commit path, reducing the contention surface |
| 5 | `S01*` spike types mistaken for approved production names | Low | Prefix, Javadoc on every type, and confinement to test/benchmark source sets of a non-published module |
| 6 | JMH figures cited as a performance budget | Low | `-PjmhDev` caveat recorded in §2, §7.2 and the benchmark evidence file; P5 owns budgets |
| 7 | Scrubbing could accidentally leak into accepted runtime state during holder consolidation | Medium | P1/P3 maps explicitly exclude it; `ScrubbingPolicyHolder` is removed only during the coherent P6 Category B cutover |

**Rollback unit.** One unit: the accepted-state store, its envelope types, and the orchestration layer,
all inside `platform-tracing-otel-javaagent-extension`. Because P3 deletions are gated behind
`Legacy deletion GO`, the three existing holders remain intact throughout P1, so rollback is reverting
one module's addition with no data migration and no wire-format reversal — provided P1 items 5, 6 and 9
are sequenced after the wire-schema contract, which risk 2 already requires.

**S-01 rollback.** Deleting `docs/research/accepted-state-primitive-s01.md`, the evidence directory, the
`acceptedstate` test and jmh packages, and the `acceptedStatePrimitiveVerify` task block in root
`build.gradle` returns the repository to its pre-spike state. No production file is involved.

---

## 16. Evidence index

| Artefact | Path |
| --- | --- |
| This report | `docs/research/accepted-state-primitive-s01.md` |
| Candidate scorecard | `docs/research/evidence/accepted-state-primitive/candidate-scorecard.json` |
| Symbol inventory | `docs/research/evidence/accepted-state-primitive/repository-symbol-inventory.json` |
| Test transcript | `docs/research/evidence/accepted-state-primitive/test-results.txt` |
| Benchmark transcript | `docs/research/evidence/accepted-state-primitive/benchmark-results.txt` |
| Decision summary | `docs/research/evidence/accepted-state-primitive/decision-summary.json` |
| Raw JMH JSON | `platform-tracing-bench/build/results/jmh/results.json` (build output, not committed) |

Spike sources — test and benchmark source sets only:

| Path | Contents |
| --- | --- |
| `platform-tracing-bench/src/test/java/space/br1440/platform/tracing/bench/acceptedstate/` | `S01ExpectedBase`, `S01AcceptedEnvelope`, `S01CommitOutcome`, `S01CommitResult`, `S01AcceptedPolicyStore`, `S01Policy`, `S01ApplyOutcome`, `S01ApplyOrchestrator`, and 7 proof classes |
| `platform-tracing-bench/src/jmh/java/space/br1440/platform/tracing/bench/acceptedstate/` | `S01AcceptedStateReadBenchmark`, `S01AcceptedStateContendedBenchmark` |

Build wiring — the only non-doc, non-test change:

| File | Change |
| --- | --- |
| `build.gradle` (root) | added `acceptedStatePrimitiveTest` and `acceptedStatePrimitiveVerify`, opt-in, **not** wired into `check` or `build` |

Verification commands:

```powershell
.\gradlew.bat acceptedStatePrimitiveVerify --no-daemon
.\gradlew.bat :platform-tracing-bench:jmh "-PjmhInclude=S01Accepted.*Benchmark" -PjmhDev --no-daemon
.\gradlew.bat test --no-daemon
```

**Production Java, public API, MBean interfaces, configuration names, BOM, `SUPPORTED.md`,
`MIGRATION.md`, the Cursor plan, the Category A decision and production manifests were not modified.**
No legacy code was deleted.

---

## 17. Remaining blockers

**For E3: none.** Every closure condition in §2 is met by executed evidence.

Open items that E3 does not depend on, recorded so they are not lost:

| # | Item | Owner |
| --- | --- | --- |
| 1 | E3 closure decision itself | architecture committee / plan owner |
| 2 | Category A effective-policy field set that the accepted policy type will carry | E5 (closed) → P1 binding |
| 3 | Wire schema contract and bounds for exposing one `acceptedSequence` | E7 + P0 wire schema contract |
| 4 | Readiness package and Implementation GO | E8, currently two-step and open |
| 5 | Private JMX writer transition sequencing | E6, open |
| 6 | Production naming of the accepted-state types | P1 |
| 7 | Reference-lab JMH baseline and any latency budget | P5 — explicitly out of S-01 scope |
| 8 | Whether `VersionedStateHolder` is deleted or retained as a domain-neutral primitive once its last consumer is gone | P3, §14 item 8 |

**Implementation GO remains `NOT_GRANTED`. Legacy deletion GO remains `BLOCKED`. P1 has not started.**
