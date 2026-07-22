# ADR: Целевая архитектура Platform Tracing (Conservative Hardening Hybrid)

| Поле | Значение |
|------|----------|
| Статус | **Superseded** → см. [ADR-platform-tracing-clean-core-hybrid](./ADR-platform-tracing-clean-core-hybrid.md) |
| Дата | 2026-06-11 |
| Контекст | Архитектурный комитет; pre-production agent-first Platform Tracing |
| Связанные ADR | [ADR-runtime-config-policy-vs-topology](./ADR-runtime-config-policy-vs-topology.md), [ADR-otel-direct-integration](./ADR-otel-direct-integration.md), [ADR-collector-boundary](./ADR-collector-boundary.md), [ADR-dual-channel-properties-v0.1](./ADR-dual-channel-properties-v0.1.md) |
| Анализ вариантов | [platform-tracing-architecture-options.md](../architecture/platform-tracing-architecture-options.md) |

---

## 1. Status

**Proposed** — ожидает решения архитектурного комитета. Код не изменён; migration plan описан как серия PR.

---

## 2. Context

Platform Tracing — production-grade Java tracing platform для крупной коммерческой компании. Архитектурная модель **agent-first**:

- OpenTelemetry Java Agent собирает телеметрию;
- `platform-tracing-otel-javaagent-extension` (Agent CL) кастомизирует OTel SDK через public SPI;
- `platform-tracing-spring-boot-autoconfigure` (Application CL) предоставляет Spring integration, **не создавая собственный SDK**;
- связь между classloader'ами — **JMX MBean** (`PlatformTracingControl`) через `SamplingControlClient`;
- shared contracts — `platform-tracing-api` (`DomainConfigHolder`, semconv, propagation).

Ключевые классы:

| Домен | Extension | Autoconfigure |
|-------|-----------|---------------|
| SDK wiring | `PlatformAutoConfigurationCustomizer` | `TracingCoreAutoConfiguration` |
| Sampling | `CompositeSampler`, `SamplerStateHolder`, `PlatformSamplerFactory` | `SamplingControlClient` |
| Processors | `PlatformCompositeSpanProcessor`, `ScrubbingSpanProcessor`, `ValidatingSpanProcessor` | — |
| Export | `PlatformDropOldestExportSpanProcessor`, `SafeSpanExporter` | diagnostics |
| Control | `PlatformTracingControlMBean` | `TracingActuatorEndpoint` |
| Drift | — | `DualChannelDriftDiagnostics`, `SdkModeResolver` |

**Факты pre-production:**

- M5 perf FAIL: +48.1% CPU, +25.4% RSS vs бюджет 3%/10% (`architecture-committee-review.md`);
- degraded modes (M6, M8): устойчивость подтверждена (no OOM, p99 delta < 1 ms);
- policy vs topology ADR принят;
- e2e smoke runtime sampling control реализован.

---

## 3. Problem

1. **Архитектура baseline корректна**, но **не формализована** как long-term target — JMX contract informal, drift diagnostics WARN-only.
2. **Perf budget не выполнен** — mandatory rollout заблокирован.
3. **Dual-channel configuration** (`TracingProperties` vs agent runtime) создаёт operational risk.
4. Необходимо выбрать target architecture среди 12 реалистичных вариантов **без big bang rewrite**.
5. Инварианты classloader isolation и agent-first не должны быть нарушены.

---

## 4. Decision

Принять целевую архитектуру **Conservative Hardening Hybrid**:

> Сохранить agent-first topology (V1), усилить control-plane contracts в `platform-tracing-api` (V4/V5), выполнить программу hardening: perf, tests, drift metrics, rollback (V12). Не менять fundamental module split.

---

## 5. Alternatives considered

| ID | Variant | Verdict |
|----|---------|---------|
| V1 | Current baseline | Reference — insufficient for prod mandate |
| V2 | Pure agent extension | Rejected — Spring ops regression |
| V3 | Pure Spring Boot starter | **Rejected** — violates agent-first ADR |
| V4 | Hybrid improved bridge | **Adopted** (component) |
| V5 | Shared control API in api | **Adopted** (component) |
| V6 | External control plane | Deprioritized — future |
| V7 | Collector-first policy | Complementary after M5 pass |
| V8 | Minimal Java + Collector tail | **Rejected** — PII/semconv risk |
| V9 | Split core/domain | Adopted incrementally (optional PR-6) |
| V10 | Multi-extension modular | **Rejected** — ops complexity |
| V11 | Codegen contracts | Adopt after DTO stabilization |
| V12 | Conservative hardening | **Adopted** (primary frame) |

Подробный анализ: [platform-tracing-architecture-options.md](../architecture/platform-tracing-architecture-options.md).

---

## 6. Decision drivers

| Driver | Weight | How decision addresses |
|--------|--------|------------------------|
| Production safety | Critical | Same proven topology; LKG; degraded-mode evidence |
| OpenTelemetry compatibility | High | Public SPI only; no internal API on hot path |
| Classloader correctness | High | Invariants preserved; ArchUnit gates |
| Performance | Critical | Dedicated perf PR track; not architectural pivot |
| Migration feasibility | High | 8 small PRs; additive contracts |
| Backward compatibility | High | Legacy JMX path until deprecation window |
| Telemetry contract stability | High | api-owned DTOs + contract tests |
| Operational simplicity | Medium | Drift metrics; Actuator/MBean parity |

---

## 7. Chosen architecture

### 7.1. Module boundaries

```
platform-tracing-api          ← shared DTOs, DomainConfigHolder, semconv
platform-tracing-otel           ← pure domain (optional incremental extract)
platform-tracing-otel-javaagent-extension ← Agent CL, OTel SPI adapters
platform-tracing-spring-boot-autoconfigure ← App CL, Spring + JMX client
```

### 7.2. Allowed dependencies

- `otel-extension` → `platform-tracing-api`
- `spring-boot-autoconfigure` → `platform-tracing-api`, `platform-tracing-otel`
- `otel-extension` test → autoconfigure (test only)

### 7.3. Forbidden dependencies

- extension → Spring
- autoconfigure → extension implementation (main)
- api → OTel SDK / Spring
- Runtime topology mutation APIs

### 7.4. Runtime control flow

```
TracingProperties → PolicyDtoMapper → SamplingControlClient
  → PlatformTracingControl.updateSamplingPolicy(SamplingPolicyUpdate)
  → validate → SamplerStateHolder.tryUpdate() → CompositeSampler reads current()
```

### 7.5. Policy vs topology

| Category | Runtime mutable | Examples |
|----------|-----------------|----------|
| Policy | Yes | sampling ratio, route-ratios, scrub/validation toggles |
| Topology | No (redeploy) | exporter endpoint, BSP queue, processor chain, propagators |

Policy DTO **must not** contain topology fields.

### 7.6. OpenTelemetry extension points

- `AutoConfigurationCustomizerProvider` → `PlatformAutoConfigurationCustomizer`
- `ConfigurableSamplerProvider` → `PlatformSamplerProvider`
- `ConfigurablePropagatorProvider` → `PlatformTraceControlPropagatorProvider`
- `ResourceProvider` → `PlatformResourceProvider`

---

## 8. Consequences

### Positive

- Committee gets clear target without rewrite risk.
- Drift and contract gaps closable in 4 foundation/safety PRs.
- Perf work decoupled but gated.
- Rollback per PR and per agent JAR pin.

### Negative

- Dual-channel model persists until PR-4 fully migrates client to DTO path.
- Perf FAIL not solved by architecture decision alone — requires PR-7/PR-8 evidence.
- Optional core split (PR-6) adds short-term refactor cost.

### Risks

| Risk | Mitigation |
|------|------------|
| Perf budget unreachable | Tiered rollout; partial V7 tail; processor toggles |
| DTO bypass by ops | Deprecation timeline; contract tests |
| OTel Agent upgrade break | Pin matrix CI job |

---

## 9. Migration plan

| PR | Title | Type | Behavior change |
|----|-------|------|-----------------|
| PR-1 | Control DTOs in api | Foundation | No |
| PR-2 | MBean DTO operation | Foundation | Additive |
| PR-3 | ArchUnit gates | Safety | No |
| PR-4 | Client + drift metrics | Safety | Yes |
| PR-5 | Policy/topology validator | Boundary | Yes |
| PR-6 | Core extract (incremental) | Boundary | No |
| PR-7 | Sampler perf | Performance | No semantic |
| PR-8 | Processor audit toggles | Performance | Additive |
| PR-9 | ADR + docs sync | Documentation | No |

Детали: [§9 Migration plan](../architecture/platform-tracing-architecture-options.md#9-migration-plan-8-prs) в options doc.

---

## 10. Compatibility strategy

- Additive `SamplingPolicyUpdate` DTO with `contractVersion`
- Legacy MBean methods supported until 2-release deprecation
- `TracingProperties` keys frozen; new keys require schema bump
- OTel Agent 2.28.x pin; matrix extended per PR-B
- Dashboard migration checklist on semconv breaking changes

---

## 11. Performance strategy

1. JMH gates on `CompositeSampler` (existing benchmarks)
2. M5 macro re-run after PR-7/PR-8 on Gentoo perf lab
3. Processor cost attribution document
4. Scrubbing rule bounds + circuit breaker (ADR `scrubbing-cost`)
5. Optional Collector tail sampling (V7) only after Java M5 pass

**Target:** M5 Δ CPU < 3%, Δ RSS < 10% vs M0 (committee decision Q1 may tier this).

---

## 12. Security/PII strategy

- Java first-line scrubbing via `ScrubbingSpanProcessor` — **not delegated to Collector-only**
- Policy DTO bounds rule count and regex validation at `tryUpdate`
- Forbidden attributes in contract tests
- Actuator/JMX write operations audit-logged (existing runbook)

---

## 13. Testing strategy

| Tier | Scope |
|------|-------|
| ArchUnit | No Spring in extension; no extension in autoconfigure main |
| Contract | Spans, attributes, semconv, forbidden keys |
| Policy | LKG, concurrent update, kill switch |
| JMX/Actuator | MBean present/absent, DTO round-trip, drift |
| Perf | JMH + M5 macro |
| Compatibility | Agent versions, Java, Spring conditions, Kafka, async |

Full matrix: [§10 Required tests](../architecture/platform-tracing-architecture-options.md#10-required-tests-recommended-architecture).

---

## 14. Open questions

| # | Question |
|---|----------|
| Q1 | M5 budget hard gate vs tiered by service class? |
| Q2 | Timeline for deprecating legacy JMX Map API? |
| Q3 | Adopt V7 tail sampling default for which routes? |
| Q4 | PR-B Alibaba matrix scope and owner? |
| Q5 | Fleet-wide OTel Agent pin policy? |
| Q6 | V11 codegen in 2026 H2? |
| Q7 | V6 external control plane on 2027 roadmap? |

---

## Mandatory invariants (acceptance checklist)

- [ ] extension has no Spring dependency
- [ ] autoconfigure does not import extension implementation classes
- [ ] shared stable contracts live in `platform-tracing-api`
- [ ] runtime policy is mutable via CAS/LKG
- [ ] runtime topology is startup/redeploy-only
- [ ] hot path config read is lock-free (`SamplerStateHolder.current()`)
- [ ] invalid runtime policy update keeps last-known-good state
- [ ] telemetry schema changes are compatibility-managed
- [ ] PII/high-cardinality policy is explicit and testable
- [ ] dashboards/alerts protected by contract tests
- [ ] production rollback via feature flag + agent JAR pin

---

*Draft for architecture committee review. Status → Accepted after committee sign-off and PR-1 kickoff approval.*
