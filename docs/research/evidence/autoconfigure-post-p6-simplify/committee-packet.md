# Post-P6 autoconfigure simplify — Architecture Committee Packet

| Field | Value |
|-------|-------|
| Subject | Упрощение `platform-tracing-spring-boot-autoconfigure` после cutover конфигурационного плана |
| Window | После исполнения плана `immutable_config_namespace_split_ba8da442` (E1–E9 / P0–P6) и **до** исполнения плана `overhead_simplify_wave2_d260b1ba` |
| Dates | 2026-07-30 (анализ + applied waves); plan wave2 authored 2026-07-30, **not executed** |
| Module | `platform-tracing-spring-boot-autoconfigure` |
| Status | **IMPLEMENTED (uncommitted working tree)** for Waves A0–A6 below; Wave2 plan is **PLAN ONLY** |
| Pre-production | Breaking property/read-model changes allowed; no compatibility aliases added |
| Decision owner | Human Architecture Committee |
| LLM status authority | none — this packet does **not** approve Wave2 |

Sibling audits (evidence sources):

- [dead-class audit](../../../analysis/platform-tracing-spring-boot-autoconfigure-dead-class-audit.md)
- [overengineering audit](../../../analysis/platform-tracing-spring-boot-autoconfigure-overengineering-audit.md)
- [ADR-spring-owns-only-what-spring-applies](../../../decisions/ADR-spring-owns-only-what-spring-applies.md)

---

## 1. Decision requested

Комитету предлагается **подтвердить** (acknowledge) уже выполненный cleanup как согласованный с утверждённой архитектурой control-plane (Category A via `platform.tracing.policy.*`, private JMX, P64 zero-write), **без** отдельного Implementation GO на следующий план.

| Decision | Meaning |
|----------|---------|
| **ACKNOWLEDGE** | Applied Waves A0–A6 accepted as correct post-P6 cleanup; no rollback required |
| **REMEDIATE** | Named applied change must be reverted or reworked before Wave2 |
| **NO-OP on Wave2** | Wave2 (`overhead_simplify_wave2`) остаётся отдельным планом; этот packet **не** утверждает его исполнение |

Wave2 (HTTP raw-value disclosure trim на `/actuator/tracing`) требует отдельного pre-flight census и собственной реализации — см. §8.

---

## 2. Почему этот интервал работы понадобился

После исполнения конфигурационного плана (immutable namespace split / control-plane cutover):

1. Spring reconcile path (`RuntimeConfigApplier` и связанный кластер) был удалён.
2. Category A runtime policy живёт только в `platform.tracing.policy.*` → private JMX → Agent accepted state.
3. Public Actuator write surface удалён (P64).
4. В `platform-tracing-spring-boot-autoconfigure` остались **post-cutover leftovers**: мёртвые DTO/exceptions, bind-only Spring mirrors Category A, коллизии имён dual Actuator, speculative projection diagnostics, дублирование private-JMX wiring.

Это не новый продуктовый scope и не откат P0–P6. Это **выравнивание Spring surface** с уже принятым ADR «Spring владеет только тем, что Spring применяет» и с фактическим ownership после cutover.

---

## 3. Что сделано (applied)

### Wave A0 — Dead-class SAFE_DELETE

| Deleted | Justification |
|---------|---------------|
| `PlatformTracingJmxOperationException` | Исключение для удалённых mutating JMX ops; zero production refs |
| `SamplingControlUnavailableException` | HTTP-503 для удалённого sampling write bridge; zero production refs |
| `TracingActuatorControlEndpoint.java` | Tombstone после замены на `TracingControlMvcController` |
| `SamplingRuntimeConfig`, `ValidationRuntimeConfig`, `SamplingRouteRatiosWire` | Post-`RuntimeConfigApplier` reconcile cluster; zero production consumers |
| Unit tests `SamplingRuntimeConfigTest`, `ValidationRuntimeConfigTest` | Consumers of deleted types |

**Evidence:** repo-wide `*.java` reference scan; arch test `ProductionControlPlaneNotMigratedArchTest` updated.

### Wave A1 — Rename dual Actuator surfaces (P0)

| Before | After | Endpoint id preserved |
|--------|-------|------------------------|
| `actuator.TracingActuatorEndpoint` | `LegacyTracingDiagnosticsEndpoint` | `tracing` |
| `configuration.actuator.TracingActuatorEndpoint` | `AcceptedPolicyActuatorEndpoint` | `tracingPolicy` |
| `TracingActuatorAutoConfiguration` (diagnostics) | `TracingDiagnosticsActuatorAutoConfiguration` | — |
| `configuration.actuator.TracingActuatorAutoConfiguration` | `TracingPolicyControlAutoConfiguration` | — |

**Justification:** одинаковые simple names в соседних пакетах создавали постоянный wrong-import risk в тестах/docs/review (P64 уже вынужден был различать «Configuration TracingActuatorEndpoint»). Runtime endpoint ids **не** менялись — P64 GET `/actuator/tracing` сохранён.

### Wave A2 — Remove bind-only Category A Spring mirrors (P0)

Removed from `TracingProperties` and related types:

- nested `Sampling`, `MandatorySpanAttributes` (validation mirror), `logLevel`
- enum `OtelAgentLogLevel`
- desired sampling/validation projection from `/actuator/tracing` (live JMX only remains)

Also removed earlier-class leftover mirrors already covered by ADR (Exporter / Suppression / etc. were prior cutover; this wave closes the remaining bind-only Category A set).

**Justification:**

- После удаления reconcile `getSampling()` / `getValidation()` / `getLogLevel()` либо отображали Spring desired как «активный» конфиг, либо имели **ноль** production call sites.
- YAML `platform.tracing.sampling.*` / `validation.*` / `log-level` выглядел операторски активным, фактически был silent no-op.
- Живой namespace Category A — только `platform.tracing.policy.*` (Config Server / ACTUATOR → private JMX).
- Соответствует ADR-spring-owns-only-what-spring-applies.

**Actuator consequence (intentional):**

- `GET /actuator/tracing` — live JMX sampling/validation (agent-applied), без Spring desired mirrors.
- `GET /actuator/tracingPolicy` — secret-safe accepted policy (unchanged ownership).

### Wave A3 — Remove speculative `TracingApplyDiagnostics` (P1)

| Change | Justification |
|--------|---------------|
| Deleted `TracingApplyDiagnostics` | `fromApply(...)` never called in production; every GET rendered `applyDiagnostics.available=false` |
| Removed field from `AcceptedPolicyProjection` | Projection must not advertise absent speculative diagnostics |
| `/actuator/tracingPolicy` no longer renders `applyDiagnostics` | Avoid permanent false operator signal |

**Not done:** last-apply cache from `applySnapshot` — would be a new feature, not cleanup.

### Wave A4 — Extract shared private-JMX auto-config (P1)

| Change | Justification |
|--------|---------------|
| Added `PrivateJmxControlAutoConfiguration` | Identical Locator/Codec/PrivateJmx client beans were duplicated in ACTUATOR and CONFIG_SERVER auto-configs |
| Slimmed `TracingPolicyControlAutoConfiguration` / `ConfigServerTracingPolicyAutoConfiguration` | Authority-specific adapters only; shared wiring once |
| `AcceptedTracingPolicyReader` bean retained for ACTUATOR (`/tracingPolicy`) | Secret-safe read surface remains; ISP readers kept |

**KEEP_JUSTIFIED (not collapsed):** ConfigServer provenance + coalescer, authority guard, OpenType codec, dual Reader/SnapshotReader/SnapshotClient ISP.

### Wave A5 — Trim unused public-JMX status surface (P1, partial)

| Deleted | Justification |
|---------|---------------|
| `PlatformTracingJmxClient.getMBeansStatus()` | No production consumer (only unit + arch «method exists») |
| `TracingControlDomain` | Sole consumer was `getMBeansStatus()` |

Metrics binders and live diagnostics getters used by `/actuator/tracing` were **not** removed in this window.

### Wave A6 — Inline SpanFactory diagnostics mapping (P2)

| Deleted | Justification |
|---------|---------------|
| `TracingDiagnosticsMapper`, `TracingDiagnosticsView` | Three types for one consumer (`toActuatorMap()` in legacy endpoint) |
| Mapping inlined into `SpanFactoryDiagnostics.toActuatorMap()` | Same public mode mapping, fewer types |

---

## 4. Что сознательно **не** трогали (KEEP_JUSTIFIED)

| Item | Why kept |
|------|----------|
| Dual endpoints `/tracing` + `/tracingPolicy` | P64 requires GET `tracing`; secret-safe policy must stay separate from ops diagnostics |
| `AcceptedTracingPolicyReader` ≠ `SnapshotReader` ≠ `SnapshotClient` | ISP / secret-safe vs complete route-ratios / write isolation |
| `ConfigServerTracingPolicyAdapter` + `TracingPolicyRefreshCoalescer` | Spring event transport ≠ deterministic refresh orchestration |
| Typed request DTOs (`SetSamplingEnabledRequest`, …) | Route → type identity for Servlet/WebFlux parity |
| `agentReady` on `/tracingPolicy` | Explicit operational JSON contract; absence≠unavailable is insufficient |
| `helmBootstrapDefaults()` | Sole canonical reusable default snapshot source |
| `getConfigAuditTrail` + `config.auditTrail` | Intentional 32-entry incident audit ring (`ConfigReloadDiagnostics.AUDIT_CAPACITY`) |
| `getScrubbingMetrics` (used for `liveRuleCount`) | Rich metrics map; not reduced to scalar in this window |
| Full retire of `/actuator/tracing` | Product DEFER; P64 constraint |

---

## 5. Architectural principles applied

| Principle | How applied |
|-----------|-------------|
| Spring owns only what Spring applies | Removed bind-only Category A mirrors from `TracingProperties` |
| Minimal intentional public surface | Deleted zero-consumer exceptions, tombstones, speculative diagnostics |
| No speculative extension points | Removed always-absent `TracingApplyDiagnostics` |
| No dual old/new paths | No compatibility aliases for deleted property keys |
| Classloader / authority boundaries | Shared private-JMX wiring extracted without merging ACTUATOR/CONFIG_SERVER adapters |
| Secret-safe vs complete policy | ISP readers preserved; `/tracingPolicy` leaf keys unchanged except removal of absent diagnostics block |
| Deterministic / fail-closed control plane | Authority guard, provenance, coalescer untouched |

---

## 6. Breaking / operator-visible effects

| Surface | Effect | Mitigation |
|---------|--------|------------|
| Spring bind keys `platform.tracing.sampling.*`, `validation.*`, `log-level` | No longer bind on `TracingProperties` (were silent no-ops for reconcile) | Operators must use `platform.tracing.policy.*` / Agent bootstrap keys |
| `/actuator/tracing` JSON | No Spring desired sampling/validation; no `applyDiagnostics` on `/tracingPolicy` | Runbooks + ADR updated; live JMX fields remain |
| Type renames (endpoints/auto-configs) | Compile break for external code importing old FQCNs | Pre-production; endpoint ids unchanged |
| `getMBeansStatus` / `TracingControlDomain` | API removal | Zero known production consumers |

No new production modules, no new MBean attributes, no new wire schemas in this window.

---

## 7. Verification (executed in this window)

Narrow gates reported during applied waves (module `platform-tracing-spring-boot-autoconfigure`):

| Gate | Result |
|------|--------|
| `compileJava` / `compileTestJava` | PASS (after test context alignment for `PrivateJmxControlAutoConfiguration`) |
| Legacy endpoint / JMX client / P64 / P55 / P56 / binding / inventory-related tests | PASS (narrow re-runs used during waves) |
| `tracingConfigurationInventoryVerify` | PASS after inventory key fix for deleted `log-level` mirror |
| Full module `:test` as single uninterrupted green run after all waves | **INSUFFICIENT_EVIDENCE** if not re-run as one batch at packet assembly time — recommend committee require full module test before merge |

Evidence docs committed/added as untracked:

- `docs/analysis/platform-tracing-spring-boot-autoconfigure-dead-class-audit.md`
- `docs/analysis/platform-tracing-spring-boot-autoconfigure-overengineering-audit.md`
- ADR update: `docs/decisions/ADR-spring-owns-only-what-spring-applies.md`

---

## 8. Out of scope / next plan (NOT part of this packet)

Written but **not executed**: plan `overhead_simplify_wave2_d260b1ba` (rev 6, after 4 architect review rounds).

Intended Wave2 scope (summary only — for awareness):

1. Pre-flight HTTP + Java/JMX consumer census → evidence file `docs/research/evidence/overhead-simplify-wave2/contract-consumer-census.md`
2. Add `Optional<Integer>` count methods on `PlatformTracingJmxClient`
3. Replace HTTP raw fields on `/actuator/tracing` (`liveRouteRatios`, `liveDropPaths`, `liveForceRecordHeaderValues`) with bounded counts in **sampling** section; remove hardcoded scrubbing strings
4. Executable endpoint **Map** contract test (null present, not absent; canary non-disclosure)
5. Conditionally delete raw getters after census; retarget counts to private JMX helpers
6. Explicitly **does not** delete: `auditTrail`, `getScrubbingMetrics`, `agentReady`, typed DTOs, ConfigServer adapter, `helmBootstrapDefaults`

Wave2 status at packet time: **READY_FOR_IMPLEMENTATION** per architect reviews, but **not started**. Separate committee acknowledgment optional; implementation follows plan gates.

Also DEFER from overengineering audit (unchanged):

- Full retire of endpoint id `tracing` (P64)
- Collapse boolean request DTOs (low value / WebFlux parity noise)

---

## 9. Net delta (this window)

| Metric | Approx. effect |
|--------|----------------|
| Production classes deleted | Exceptions, tombstone, reconcile DTO cluster, ApplyDiagnostics, Mapper/View, TracingControlDomain, OtelAgentLogLevel |
| Production classes added | 1 — `PrivateJmxControlAutoConfiguration` (wiring extract, not new domain concept) |
| New MBean attributes | 0 |
| New wire / OpenType schemas | 0 |
| Category A policy leaves | Unchanged (8 leaves) |
| Private control mutation surface | Unchanged (`applySnapshot` only) |
| P64 zero-write invariant | Preserved |

---

## 10. Recommended committee actions

1. **ACKNOWLEDGE** Waves A0–A6 as post-P6 cleanup aligned with ADR ownership and cutover architecture.
2. Require **full** `:platform-tracing-spring-boot-autoconfigure:test` + `tracingConfigurationInventoryVerify` + `git diff --check` green before merge of this working tree.
3. Treat Wave2 as a **separate** execution package; do not conflate ACKNOWLEDGE of this packet with approval to delete HTTP raw sampling values without census evidence.
4. Optionally schedule product decision on eventual retirement of `/actuator/tracing` (DEFER #7) outside this cleanup track.

---

## 11. Traceability

| Artifact | Role |
|----------|------|
| Conversation / agent work leading to audits + waves | [Post-P6 autoconfigure simplify](e19bfded-d693-4aaf-b37b-b097dd0d280c) (Cursor agent transcript) |
| Config plan (prior, executed) | `immutable_config_namespace_split_ba8da442` |
| Next simplify plan (authored, not executed) | `overhead_simplify_wave2_d260b1ba` (rev 6) |
| Dead-class audit | `docs/analysis/platform-tracing-spring-boot-autoconfigure-dead-class-audit.md` |
| Overengineering audit | `docs/analysis/platform-tracing-spring-boot-autoconfigure-overengineering-audit.md` |
| Ownership ADR | `docs/decisions/ADR-spring-owns-only-what-spring-applies.md` |
