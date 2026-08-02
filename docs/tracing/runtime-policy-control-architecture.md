# Runtime Policy Control Architecture (PR-9A+)

Unified architecture for **sampling**, **scrubbing**, and **validation** runtime policy after PR-6/7/8,
extended with the **six-domain JMX control plane** reset (pre-production, 2026-06-18).

No new runtime policy domains in this document — enrichment/export/propagation are separate policy
surfaces but share the same JMX infrastructure.

Normative ADRs and runbooks:

- [ADR-runtime-sampling-policy.md](../decisions/ADR-runtime-sampling-policy.md)
- [../runbook/runtime-policy-control.md](../runbook/runtime-policy-control.md) — **canonical** post-P6 operator runbook
- [runtime-sampling-control.md](runtime-sampling-control.md) — filename retained for links; superseded pointer only
- [../runbook/jmx-consumer-migration.md](../runbook/jmx-consumer-migration.md) — E7/P6.6 JMX consumer migration
- [platform-tracing-sampling-characterization.md](../architecture/platform-tracing-sampling-characterization.md)
- [platform-tracing-scrubbing-validation-characterization.md](../architecture/platform-tracing-scrubbing-validation-characterization.md)

> **Cutover note (updated 2026-08-02).** Разделы ниже описывают PR-9A+ архитектуру и сохранены как
> исторический architecture record. Перечисленное больше не существует в коде и не должно
> использоваться операторами:
>
> - Spring-writer `RuntimeConfigApplier` (+ `ConfigApplyResult`, `TracingControlDomain`, `*RuntimeConfig` views);
> - agent-side holders `SamplerStateHolder` / `ScrubbingPolicyHolder` / `ValidationPolicyHolder` и `DomainConfigHolder`;
> - мутаторы шести domain MBean (post-P6.6 они **read-only**) и мутирующие методы `PlatformTracingJmxClient`;
> - Actuator `@WriteOperation` `POST /actuator/tracing/{property}/{value}` и флаг `platform.tracing.actuator.mutation-enabled`;
> - legacy read-endpoint `GET /actuator/tracing` (актуальный read-model — `GET /actuator/tracingStatus`).
>
> Действующая мутация — только private control plane `applySnapshot` через выбранный authority
> (HELM / CONFIG_SERVER / DEV_ACTUATOR typed leaves). Единственный accepted-state owner на стороне
> агента — `AcceptedPolicyStateStore` (доступ через `AcceptedPolicyAccess`).
>
> Нормативные источники текущего состояния: [../runbook/runtime-policy-control.md](../runbook/runtime-policy-control.md)
> (операции), [../SUPPORTED.md](../SUPPORTED.md) (поддерживаемая поверхность),
> [../runbook/tracing-status-diagnostics.md](../runbook/tracing-status-diagnostics.md) (диагностика).

## Three policy domains

Колонки «Agent holder» и «Spring schema v1 view» — историческая PR-9A модель: per-domain holders и
`*RuntimeConfig` удалены, их заменил единственный accepted-state owner агента. Колонка потребителей
актуальна.

| Domain | Agent holder (удалён) | Processor / sampler consumer | Spring schema v1 view (удалён) |
|--------|-----------------------|------------------------------|--------------------------------|
| Sampling | `SamplerStateHolder` | `CompositeSampler` → `SamplingPolicyEngine` | `SamplingRuntimeConfig` |
| Scrubbing | `ScrubbingPolicyHolder` | `ScrubbingSpanProcessor` | `ScrubbingRuntimeConfig` |
| Validation | `ValidationPolicyHolder` | `ValidatingSpanProcessor` | `ValidationRuntimeConfig` |

Validation degradation (annotate / warn / exception) is **not** dropped-span loss — no `VALIDATION_REJECTED` in span-loss taxonomy.

**PR-9F strict-mode guard:** Runtime updates that set `strict=true` are **rejected by default** unless startup flag `platform.tracing.validation.strict-runtime-allowed=true` (agent-side). Rejected update keeps LKG; version/source unchanged; `InvalidConfigCount` increments. Strict mode remains a CI/test/pre-prod diagnostic — not a safe default production runtime policy while it can throw from `Span.end()`.

**PR-9J access hardening (W-007 / W-013) — historical:**

| Surface | Guard | Status |
|---------|-------|--------|
| **Actuator write** (`POST /actuator/tracing/{property}/{value}`) | `platform.tracing.actuator.mutation-enabled=false` (default) | **Deleted.** Endpoint и флаг удалены (`P64ActuatorWriteAbsenceTest`); write-канал — private `applySnapshot` |
| **Direct JMX** (six domain MBeans) | **Deployment / JVM / platform security** | Мутаторов нет: MBean read-only post-P6.6; защита JVM/сети по-прежнему обязательна |
| **Startup strict** (`platform.tracing.validation.strict=true`) | Ops policy + one-time startup WARN | Актуально; can still throw from `Span.end()` at startup strict |

Production recommendation (актуальная): ограничивать JMX через JVM `-Dcom.sun.management.jmxremote.*`,
firewall, management port policy и container RBAC; `ACTUATOR` authority допускается только при
`platform.environment=LOCAL` (иначе fail-fast).

## Six-domain JMX control plane

The monolithic `PlatformTracingControl` / `PlatformTracingControlMBean` and `jmx.operations` package
were **removed** (pre-production breaking change). Runtime control is now split into six focused
Standard MBeans, each with its own `ObjectName`:

| Domain | MBean interface | Implementation | ObjectName |
|--------|-----------------|----------------|------------|
| Sampling | `PlatformSamplingControlMBean` | `PlatformSamplingControl` | `space.br1440.platform.tracing:type=Sampling,name=PlatformSamplingControl` |
| Scrubbing | `PlatformScrubbingControlMBean` | `PlatformScrubbingControl` | `space.br1440.platform.tracing:type=Scrubbing,name=PlatformScrubbingControl` |
| Validation | `PlatformValidationControlMBean` | `PlatformValidationControl` | `space.br1440.platform.tracing:type=Validation,name=PlatformValidationControl` |
| Export | `PlatformExportControlMBean` | `PlatformExportControl` | `space.br1440.platform.tracing:type=Export,name=PlatformExportControl` |
| Processor metrics | `PlatformProcessorMetricsControlMBean` | `PlatformProcessorMetricsControl` | `space.br1440.platform.tracing:type=Metrics,name=PlatformProcessorMetricsControl` |
| Diagnostics | `PlatformDiagnosticsControlMBean` | `PlatformDiagnosticsControl` | `space.br1440.platform.tracing:type=Diagnostics,name=PlatformDiagnosticsControl` |

**Old monolith ObjectName** (`type=Control,name=PlatformTracingControl`) is **no longer registered**.

Constants are duplicated across classloaders:

- Agent: `PlatformTracingObjectNames` (`platform-tracing-otel-javaagent-extension`)
- Spring: `PlatformTracingJmxObjectNames` (`platform-tracing-spring-boot-autoconfigure`)

Equality is enforced by `ObjectNameConstantsConsistencyTest`.

### Registration (agent-side)

`PlatformTracingJmxRegistrar` is the **sole owner** of JMX registration:

- **Batch all-or-nothing:** all six MBeans register in one synchronized pass; failure triggers reverse-order rollback.
- **REPLACE_EXISTING:** if an ObjectName is already registered, it is unregistered before re-register.
- **Idempotent:** concurrent `tryRegisterMBeans()` calls register exactly once (6× `registerMBean`).
- **Shared `LongAdder invalidConfigCounter`** passed to sampling, scrubbing, validation, and diagnostics MBeans.
- **Early-registration timing unchanged:** registration triggers on first non-null `configHolder`; export suppliers remain late-bound.
- **Success log** (after all six succeed): lists all six ObjectName strings.
- **Failure:** `PlatformTracingJmxRegistrationException` with rollback failures attached via `addSuppressed`.

```text
PlatformTracingJmxRegistrar.tryRegisterMBeans()
  → register sampling   [ok] → push SAMPLING
  → register scrubbing  [ok] → push SCRUBBING
  → register validation [ok] → push VALIDATION
  → register export     [FAIL]
  → rollback: unregister VALIDATION, SCRUBBING, SAMPLING
  → throw PlatformTracingJmxRegistrationException(cause + suppressed rollback errors)
```

### Client (Spring-side)

`PlatformTracingJmxClient` replaces the deleted `SamplingControlClient`:

| Method | Semantics |
|--------|-----------|
| `allMBeansAvailable()` | `true` only when all six ObjectNames are registered |
| Read-only (`getCurrentRatio`, metrics, …) | **Graceful degradation:** `Optional.empty()` / empty collections; no throw |

Post-P6.6 клиент **целиком read-only**: мутирующие методы (`setRatio`, `updateSamplingPolicy`, …),
`getMBeansStatus()` и enum `TracingControlDomain` удалены вместе с `RuntimeConfigApplier`. Клиент
используется только как источник live-значений для read-model `tracingStatus` и метрик.

## Write path (PR-9A: one domain = one JMX call) — **DELETED**

Описанный ниже путь удалён целиком (`RuntimeConfigApplier`, per-domain JMX мутаторы, holders).
Действующий write path: выбранный authority → полный Category A snapshot → private
`applySnapshot` → единственный accepted-state owner агента (`AcceptedPolicyStateStore`).

| Authority | Spring writer | Канал |
|-----------|---------------|-------|
| `HELM` | нет | Agent bootstrap (env / `-D`), рестарт для изменения |
| `CONFIG_SERVER` | locator / codec / snapshot client / `TracingPolicyRefreshCoalescer` | полный восьмилистовой Category A манифест → private `applySnapshot` |
| `ACTUATOR` + `platform.environment=LOCAL` | `TracingControlMvcController` / WebFlux router → `TracingPolicyMutationService` | восемь типизированных leaf-команд `/tracingControl/...` → private `applySnapshot` (agent source `DEV_ACTUATOR`) |

Операционные детали, outcomes (`APPLIED` / `UNCHANGED` / `STALE` / `REJECTED` / `FAILED`), LKG и
bounded retry — в [../runbook/runtime-policy-control.md](../runbook/runtime-policy-control.md).

Исторический PR-9A путь:

```text
TracingProperties (Spring Environment)
  → RuntimeConfigApplier.applyAll()
      gate: client.allMBeansAvailable()  →  [REJECTED] if false
  → PlatformTracingJmxClient.update{Domain}Policy(*RuntimeConfig)
  → Platform{Domain}Control MBean (agent classloader, domain ObjectName)
  → *PolicyHolder.tryApplyPolicyUpdate / tryUpdate (CAS + last-known-good)
  → immutable *Snapshot (version++, source recorded)
  → sampler/processor hot path: holder.current() lock-free
```

Apply order in `RuntimeConfigApplier` (explicit, not enum iteration):

1. sampling → 2. scrubbing → 3. validation → 4. export → 5. diagnostics (propagation) → 6. log level (if non-blank)

Spring reconciliation uses `source = "spring-runtime-config"` on every domain update.
JMX 2-arg overloads delegate to 3-arg with `source = "JMX"`.
Blank/null `source` normalizes to `"JMX"` on the agent.

**Refresh:** `RefreshScopeRefreshedEvent` → `RuntimeConfigApplier` (not `@RefreshScope`) reads fresh `TracingProperties` proxy — no stale-read on refresh.

### RuntimeConfigApplier reliability (Spring-side diagnostics) — **DELETED**

Метрика `platform.tracing.config.apply.result` и счётчики ниже больше не публикуются.

| Outcome | Log | Counters / state |
|---------|-----|------------------|
| Gate failed (any MBean missing) | `[REJECTED]` | `rejectedApplyCount++`; `lastConfigApplyResult` stays `null` |
| Gate passed, all domains OK | per-domain `[DOMAIN] applied` (debug) | `lastConfigApplyResult` with full `applied` set, empty `failed` |
| Gate passed, ≥1 domain failed | `[PARTIAL applied=N failed=M]` | `partialApplyCount++`; `lastConfigApplyResult` with exact `applied`/`failed` sets |

`ConfigApplyResult` record: `(Set<TracingControlDomain> applied, Set<TracingControlDomain> failed, Instant timestamp)`.

Micrometer: `platform.tracing.config.apply.result{domain,result}` per domain call (null-safe when `meterRegistry` absent).

**No rollback of already-applied domains** — JMX is non-transactional; partial state is recorded for operator diagnosis.

## Read path (Actuator + JMX)

Единственный актуальный read-model — **`GET /actuator/tracingStatus`** (`TracingStatusEndpoint` /
`TracingStatusAssembler`), read-only. Legacy `GET /actuator/tracing` удалён вместе со своим
Spring-desired слоем и dual-channel секциями (`otelEffective`, `resourceEffective`, `otelEnvHints`):
см. [ADR-remove-dual-channel-diagnostics.md](../decisions/ADR-remove-dual-channel-diagnostics.md).

| Секция | Содержание |
|--------|------------|
| `agent` | readiness Controlled Agent: `state`, `markerVisible`, `readinessEndpointPresent`, `protocolVersion`, `lifecycle`, `failureCode`, `capabilities` |
| `runtime` | live JMX через `PlatformTracingJmxClient`: `sampling`, `validation`, `scrubbing`, `export`, `processorErrors*`, `configurationReload`, плюс `mode` / `implementation` / `domainMBeansAvailable` |
| `policy` | `AcceptedPolicyProjection` либо явная `PolicyAbsenceReason` |

Per-domain `configVersion` / `configSource` (`runtime.sampling`, `runtime.validation`,
`runtime.scrubbing`) остаются авторитетной runtime-диагностикой и читаются **live** из domain MBean —
без Spring-desired зеркала. Legacy top-level `config.lastUpdatedSource` удалён вместе со старым
endpoint'ом.

Структура ответа и операторские сценарии: [../runbook/tracing-status-diagnostics.md](../runbook/tracing-status-diagnostics.md).

**Agent-vs-Spring drift (validation):** агент читает `strictRuntimeAllowed` из своей startup-конфигурации
(`ExtensionConfig` / `ConfigProperties`, ключи `ExtensionPropertyNames`), которая может не совпадать со
Spring-биндингом. В `runtime.validation` публикуется значение, прочитанное из MBean агента
(`PlatformTracingJmxClient#isValidationStrictRuntimeAllowed`) — именно оно отражает фактический
enforcement.

## JMX access model (post-P6.6)

Шесть domain MBean в classloader'е расширения OTel Java Agent — **read-only проекции**: операций
мутации у них нет. Мутация идёт исключительно через private control plane (`PrivateControlWriter`
в приватном `MBeanServer`, `PrivateControlMBeanServerOwner`), доступный только выбранному Spring
authority-писателю.

| Caller | Доступ к platform domain MBean | Доступ к private `applySnapshot` |
|--------|-------------------------------|----------------------------------|
| Operator (jconsole, jmxterm) | чтение | не предназначен для ручного вызова |
| Spring authority writer (CONFIG_SERVER coalescer / DEV_ACTUATOR typed leaves) | чтение (диагностика, метрики) | да — единственный write-канал |

Production JMX access must be restricted by JVM JMX configuration, firewall, restricted management
port, and platform security controls outside this codebase. Ранее существовавшая проблема
«JMX-мутации в обход Actuator-флага» снята структурно: мутаторов в platform MBean больше нет.

## Module boundaries

| Module | Responsibility | Must not |
|--------|----------------|----------|
| `platform-tracing-otel` | Pure Java domain/policy logic (sampling engine, scrubbing merge, enrichers, …) | Spring, JMX, OTel agent runtime |
| `platform-tracing-otel-javaagent-extension` | OTel adapters: samplers, span processors, six read-only domain MBeans, `PlatformTracingJmxRegistrar`, accepted-state owner (`AcceptedPolicyStateStore` / `AcceptedPolicyAccess`), private control plane | Spring imports on hot path |
| `platform-tracing-spring-boot-autoconfigure` | `TracingProperties`, authority-писатели (Config Server adapter, typed management controller), read-only `PlatformTracingJmxClient`, `tracingStatus` read-model | Second authoritative runtime snapshot |

Accepted-state owner ровно один и живёт на стороне агента. Общий `DomainConfigHolder` в
`platform-tracing-api` удалён вместе с per-domain holders.

## Consistency checklist (PR-6D–8C) — historical

Таблица описывает удалённые per-domain JMX-мутаторы и Spring-реконсиляторы. Действующие гарантии
(LKG, монотонная версия, `InvalidConfigCount`, outcomes) обеспечивает private `applySnapshot`
поверх единственного accepted-state owner.

| Concern | Sampling | Scrubbing | Validation |
|---------|----------|-----------|------------|
| Atomic JMX update | `updateSamplingPolicy(..., source)` | `updateScrubbingPolicy(..., source)` | `updateValidationPolicy(..., source)` |
| Spring reconciler | PR-6E | PR-7C | PR-8C |
| LKG on invalid update | yes | yes | yes (CAS/build failure) |
| Invalid counter | `InvalidConfigCount++` (shared `LongAdder`) | yes | yes |
| Version on success | monotonic | monotonic | monotonic |
| Unknown rule names | N/A | skipped (startup parity) | N/A |

## Startup-only vs runtime-mutable

Runtime-mutable — восемь Category A leaf'ов, применяемых **одним полным snapshot'ом** через private
`applySnapshot` (не по одному JMX-вызову на домен):

- **Sampling:** `enabled`, `ratio`, `route-ratios`
- **Validation:** `enabled`, `strict`
- **Exporter:** `enabled`
- **Propagation:** `trace-control-headers.enabled`
- **Diagnostics:** `log-level`

Полные имена ключей и правила доставки — §2 канонического
[runbook](../runbook/runtime-policy-control.md).

Startup-only (Category B / topology, требуют рестарта): sampling `drop-paths` и header **names**,
scrubbing rules (`platform.tracing.javaagent.scrubbing.*`), baggage-настройки, semantic
`ValidationMode`, export endpoint topology.

## Known follow-ups

1. **Scrubbing live rule names** — MBean exposes `liveRuleCount` only; name-level read getter optional future work.
2. **`config.lastUpdatedSource`** — закрыт удалением legacy endpoint'а; в `tracingStatus` доступен только per-domain `configSource`.
3. **Route ratio longest-prefix-wins** — **PR-9G complete** (deterministic compile-time sort in `SamplingPolicySnapshot.normalizeRouteRatios`; see [platform-tracing-sampling-characterization.md](../architecture/platform-tracing-sampling-characterization.md)).
4. **Enrichment runtime config** — not part of PR-6/7/8; separate future PR.
5. **Implementation layering** - sampling engine remains in `platform-tracing-otel` under historical `core.sampling`; validation uses `core.validation`; Agent-specific scrubbing remains in `platform-tracing-otel-javaagent-extension`. No future module extraction is implied.

## PR-9B — Core extraction readiness

Historical inventory: [platform-tracing-core-extraction-readiness.md](../architecture/platform-tracing-core-extraction-readiness.md).

- No class moves in PR-9B; ArchUnit extended (`CORE_MAIN_NO_JMX`, JMX forbidden in core policy packages).
- **In core today:** `core.sampling.*`, `core.validation.*` (pure Java, no OTel/Spring/JMX).
- **PR-9C completed (validation):** `ValidationSnapshot`, `ValidationPolicyUpdate` → `core.validation`.
- **PR-9D completed (scrubbing prep):** `ScrubbingRuleResolution` isolates pure rule-name validate/select; `ScrubbingSnapshot` / `RuleExecutionWrapper` stay agent-side.
- **Stay agent-side:** accepted-state owner, OTel processors/samplers, six read-only domain MBeans, compiled scrubbing snapshots (per-domain holders удалены).
- **Stay Spring-side:** authority-писатели и read-only `PlatformTracingJmxClient` (`*RuntimeConfig` / `RuntimeConfigApplier` удалены).

## Related architecture docs

- [platform-tracing-control-refactoring-dossier.md](../architecture/platform-tracing-control-refactoring-dossier.md) — Phase 1 (WINNER_INTERNAL_DELEGATES, superseded)
- [platform-tracing-control-second-stage-refactoring-dossier.md](../architecture/platform-tracing-control-second-stage-refactoring-dossier.md) — Phase 2 analysis → implemented as six-domain reset
