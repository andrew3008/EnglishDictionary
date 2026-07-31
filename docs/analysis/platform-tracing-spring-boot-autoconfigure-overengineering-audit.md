# Ревизия overengineering: `platform-tracing-spring-boot-autoconfigure`

| Поле | Значение |
|------|----------|
| Дата | 2026-07-30 |
| Модуль | `platform-tracing-spring-boot-autoconfigure` |
| Метод | Static call-site scan, dual-path compare, сверка с ADR и [dead-class audit](./platform-tracing-spring-boot-autoconfigure-dead-class-audit.md) |
| Статус | **Applied** (2026-07-30) — backlog #1–6; #7–8 DEFER без изменений |
| Исключено | SAFE_DELETE уже applied (RuntimeConfig DTO, exceptions, tombstone) — не дублируются как primary findings |

## Вердикт

Остаточный overengineering сконцентрирован в четырёх зонах:

1. Spring bind-only mirrors Category A (`Sampling` / `MandatorySpanAttributes` / `logLevel`) после удаления reconcile — **ложный operator contract**.
2. Коллизия имён dual Actuator surfaces (`TracingActuatorEndpoint` ×2, `TracingActuatorAutoConfiguration` ×2).
3. Legacy `/actuator/tracing` + широкий public-JMX client как параллельная плоскость к private accepted policy.
4. Speculative projection (`TracingApplyDiagnostics` always-absent) и тонкий diagnostics mapper/view.

Control-plane ISP (secret-safe vs complete snapshot), ConfigServer fail-closed и capability auto-configs в основном **KEEP_JUSTIFIED**.

---

## Сводка

| Severity | Кол-во | Disposition |
|----------|--------|-------------|
| P0 | 2 | `SIMPLIFY_NOW` |
| P1 | 5 | `SIMPLIFY_NOW` / `DEFER` (product) |
| P2 | 2 | `SIMPLIFY_NOW` / `DEFER` |
| KEEP_JUSTIFIED | см. ниже | Без действия |

---

## Findings

### 1. Коллизия имён dual Actuator — P0 / SIMPLIFY_NOW

| | |
|---|---|
| Paths | `...autoconfigure.actuator.TracingActuatorEndpoint` (`id=tracing`)<br>`...configuration.actuator.TracingActuatorEndpoint` (`id=tracingPolicy`)<br>`TracingActuatorAutoConfiguration` в обоих пакетах |
| Почему | Одинаковые simple names в соседних пакетах — постоянный риск wrong-import в тестах/docs/review. P64 уже вынужден различать «Configuration TracingActuatorEndpoint (tracingPolicy)». |
| Упрощение | Rename: `LegacyTracingDiagnosticsEndpoint` + `AcceptedPolicyActuatorEndpoint`; auto-config → `TracingDiagnosticsActuatorAutoConfiguration` / `TracingPolicyControlAutoConfiguration`. Runtime endpoint ids можно сохранить. |
| Risk | Rename + imports/tests only. |

### 2. Bind-only Spring mirrors Category A — P0 / SIMPLIFY_NOW

| | |
|---|---|
| Paths | `TracingProperties.Sampling`, `MandatorySpanAttributes` (поле `validation`), `logLevel` + `OtelAgentLogLevel`<br>Живой namespace: `platform.tracing.policy.*` (`ConfigServerPolicyManifestBuilder`) |
| Почему | После удаления `RuntimeConfigApplier`:<br>• `getSampling()` / `getValidation()` — только legacy `/actuator/tracing` desired display<br>• `getLogLevel()` — **ноль** production call sites (bind-only tests)<br>YAML `platform.tracing.sampling.*` / `validation.*` / `log-level` выглядит «активным», фактически no-op для reconcile. Dual key space с `platform.tracing.policy.*`. |
| Упрощение | Удалить nested types + `logLevel`/`OtelAgentLogLevel`; убрать desired sampling/validation из `/actuator/tracing`; inventory/docs → policy + Agent keys. |
| Risk | Binding break для YAML, задающего старые ключи (сейчас silent no-op). Нужен docs/Helm sweep. |
| Связь | Совпадает с LIKELY_DEAD #7–8 в dead-class audit. |

### 3. Dual Actuator payloads `/tracing` vs `/tracingPolicy` — P1 / DEFER (product)

| | |
|---|---|
| Почему | Два read-model с разными источниками истины: Spring desired + public JMX live vs Agent accepted (secret-safe). Оператору приходится сверять три плоскости. P64 требует сохранить GET `/actuator/tracing`, но не текущий объём полей. |
| Упрощение | Product decision: свернуть diagnostics в секции `tracingPolicy` + thin health, либо deprecated `/tracing` с урезанным payload (sdk/mode/spanFactory/otelEffective). |
| Risk | Runbooks, P64, `docs/runbook/actuator-tracing-diagnostics.md`. |
| Disposition | **DEFER** endpoint retirement; payload trim можно делать раньше вместе с finding 2. |

### 4. Дублирование Private-control wiring — P1 / SIMPLIFY_NOW

| | |
|---|---|
| Paths | `configuration.actuator.TracingActuatorAutoConfiguration`<br>`configuration.configserver.ConfigServerTracingPolicyAutoConfiguration` |
| Почему | ~идентичные beans (Locator, Codec, PrivateJmx client, 3 aliases, почти одинаковые readiness probes) при взаимоисключающих authorities. |
| Упрощение | `PrivateJmxControlAutoConfiguration` (authority ∈ {ACTUATOR, CONFIG_SERVER}) + тонкие adapter auto-configs. Убрать регистрацию `AcceptedTracingPolicyReader` из ConfigServer, если `/tracingPolicy` там не нужен. |
| Risk | Ошибка shared conditions → private plane в HELM; нужен arch-тест authority guard. |

### 5. `TracingApplyDiagnostics` always-absent — P1 / SIMPLIFY_NOW

| | |
|---|---|
| Paths | `TracingApplyDiagnostics`, поле в `AcceptedPolicyProjection`<br>`PrivateJmxTracingPolicySnapshotClient.toProjection` → всегда `absent()` |
| Почему | `fromApply(...)` **нигде не вызывается** в production. Каждый GET `/tracingPolicy` рендерит `applyDiagnostics.available=false`. Тип существует «на будущее», но apply response не кэшируется в projection. |
| Упрощение | Убрать `diagnostics` из projection/endpoint **или** кэшировать last-apply из `applySnapshot` и заполнять `fromApply`. Не оставлять вечный false. |
| Risk | Если продукт ждёт digest в GET — нужен явный last-apply cache. |

### 6. `PlatformTracingJmxClient` широкий surface — P1 / SIMPLIFY_NOW (частично)

| | |
|---|---|
| Paths | `jmx.PlatformTracingJmxClient` (~400 LOC), `TracingControlDomain` |
| Почему | ~25 public read methods. Metrics binders используют узкий subset. `getMBeansStatus()` / `TracingControlDomain` — **нет production consumers** (только unit + arch «method exists»). В `/tracing`: `samplerEnabled` и `liveSamplerEnabled` дублируют один `isSamplerEnabled()`. |
| Упрощение | Удалить unused status API + alias; убрать duplicate fields в `/tracing`. Полный split metrics vs diagnostics — после product decision по `/tracing`. |
| Risk | Урезание JSON `/tracing`; metrics polling contract сохранить. |

### 7. Diagnostics Mapper/View — P2 / SIMPLIFY_NOW

| | |
|---|---|
| Paths | `SpanFactoryDiagnostics` → `TracingDiagnosticsMapper` → `TracingDiagnosticsView` |
| Почему | Единственный prod call: `toActuatorMap()` в legacy endpoint. `view()` не вызывается нигде кроме маппинга в map. Три файла ради одного consumer. |
| Упрощение | Inline map в `SpanFactoryDiagnostics.toActuatorMap()`; удалить Mapper/View или оставить View без Mapper. |
| Risk | Низкий; Slice-7 mode-mapping tests. |

### 8. Dual policy readers — KEEP_JUSTIFIED (ISP); wiring P2

| | |
|---|---|
| Paths | `AcceptedTracingPolicyReader`, `AcceptedTracingPolicySnapshotReader`, `TracingPolicySnapshotClient`<br>все реализованы `PrivateJmxTracingPolicySnapshotClient` |
| Почему оправдано | Secret-safe projection vs complete snapshot (route ratios) — security boundary против утечки в public Actuator. Write client отдельно от readers. |
| Accidental | Три `@Bean` alias на один объект; ConfigServer регистрирует Reader без prod consumer (кроме `/tracingPolicy` endpoint). |
| Упрощение | KEEP интерфейсы; упростить alias wiring (см. finding 4). |

### 9. Request DTO proliferation — P2 / DEFER

| | |
|---|---|
| Paths | 8 request records под `configuration.actuator` (7 однополевых) |
| Упрощение | Nested records в controller или один holder-файл; JSON shape сохранить. |
| Disposition | **DEFER** (шум PR) — допустимо вместе с rename endpoint. |

### 10. Config-server + Actuator control plane — KEEP_JUSTIFIED

Намеренная граница (не overengineering core):

- Authority isolation ACTUATOR vs CONFIG_SERVER
- Provenance fail-closed + refresh coalescing
- MVC вместо `@WebEndpoint` (8 unambiguous paths)
- `TracingConfigurationAuthorityGuard` BFPP
- OpenType codec + Agent digest parity

Упрощать только wiring/projection noise (findings 4–5), не сливать adapters.

---

## KEEP_JUSTIFIED (выглядит сложно, но оправдано)

| Item | Почему |
|------|--------|
| Dual Reader ≠ SnapshotReader | Secret-safe vs complete; ISP |
| `TracingPolicySnapshotClient` отдельно | Write не должен инжектиться в read endpoint |
| ConfigServer provenance + fail-closed manifest | Authority/security |
| `TracingPolicyRefreshCoalescer` | Refresh storm / ordering |
| `TracingControlMvcController` | Документированный trade-off HTTP paths |
| `TracingConfigurationAuthorityGuard` | Fail-closed до private clients |
| `PrivateControlOpenTypeCodec` + `CandidateDigest` | Classloader-safe + Agent parity |
| Kafka / async / outbound / semantic auto-configs | Реальные Spring-applied capabilities |
| `AgentExtensionObserver` / `SdkModeDiagnostics` | Startup readiness |
| Metrics binders via JMX polling | Agent CL ≠ Micrometer |

---

## Приоритетный backlog

| # | Action | Severity | Disposition |
|---|--------|----------|-------------|
| 1 | Rename dual `TracingActuatorEndpoint` / AutoConfiguration | P0 | **APPLIED** → `LegacyTracingDiagnosticsEndpoint` / `AcceptedPolicyActuatorEndpoint` + renamed auto-configs |
| 2 | Remove bind-only `Sampling` / `MandatorySpanAttributes` / `logLevel`; stop projecting desired на `/tracing` | P0 | **APPLIED** — live JMX-only на `/actuator/tracing`; `OtelAgentLogLevel` deleted |
| 3 | Remove or wire `TracingApplyDiagnostics` | P1 | **APPLIED** — type + `applyDiagnostics` payload removed |
| 4 | Extract shared private-JMX auto-config; trim unused Reader bean | P1 | **APPLIED** → `PrivateJmxControlAutoConfiguration`; Reader только ACTUATOR |
| 5 | Trim `PlatformTracingJmxClient` (status API, aliases, duplicate actuator fields) | P1 | **APPLIED** — `getMBeansStatus` / `TracingControlDomain` deleted; samplerEnabled dup removed |
| 6 | Inline SpanFactory diagnostics mapper/view | P2 | **APPLIED** — mapping in `SpanFactoryDiagnostics` |
| 7 | Product decision: merge/retire `/actuator/tracing` | P1 | DEFER |
| 8 | Collapse single-field request DTOs | P2 | DEFER |

---

## Связь с dead-class audit

| Dead-class item | Overengineering follow-up |
|-----------------|---------------------------|
| SAFE_DELETE applied | Закрыто; не повторять |
| LIKELY_DEAD `logLevel` / Sampling / MandatorySpanAttributes | Finding 2 — основной P0 simplify |
| Dual actuator KEEP | Finding 1 (rename now) + Finding 3 (product retire later) |

---

## Evidence quality

| Claim | Status |
|-------|--------|
| `getSampling`/`getValidation` только legacy actuator | PASS |
| `getLogLevel` zero prod refs | PASS |
| `TracingApplyDiagnostics.fromApply` unused; always `absent()` | PASS |
| Dual endpoints / dual AutoConfiguration both imported | PASS |
| `getMBeansStatus` no prod consumer | PASS |
| Runtime behavior после упрощения | PASS (narrow compile + P56/P64/P55/binding/legacy endpoint/inventory) |

## Residual Wave v6 (2026-07-31) — HTTP disclosure hardening

Plan: `overhead_simplify_wave2_d260b1ba` rev 6 (4 architect review rounds).  
Evidence: [contract-consumer-census.md](../research/evidence/overhead-simplify-wave2/contract-consumer-census.md).

| Item | Disposition |
|------|-------------|
| Remove HTTP raw `liveRouteRatios` / `liveDropPaths` / `liveForceRecordHeaderValues` | **APPLIED** → replaced by `routeRatioCount` in `sampling`; `dropPathCount` / `forceRecordValueCount` were added then removed as always-null dead diagnostics (post-audit remediation; underlying MBean attributes gone post-P6.6) |
| Remove hardcoded scrubbing strings | **APPLIED** |
| `getConfigAuditTrail` / `config.auditTrail` | **KEEP** |
| `getScrubbingMetrics` (for `liveRuleCount`) | **KEEP** |
| `agentReady`, typed DTOs, ConfigServer adapter, `helmBootstrapDefaults` | **KEEP** / **REJECT** removal |
| Delete raw JMX client getters after census | **APPLIED** — retargeted count methods to private helpers |
| New scrubbingMetrics JSON block / new digest | **REJECTED** (out of scope) |
| Full retire `/actuator/tracing` | **DEFER** (#7) |
| Collapse boolean request DTOs | **DEFER** (#8) |

## Следующий шаг

Product DEFER: #7 retire `/actuator/tracing`; #8 collapse request DTOs.
