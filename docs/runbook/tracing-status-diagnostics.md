# Диагностика платформенного трейсинга через `tracingStatus`

Операторский гайд для SRE: как читать read-model `tracingStatus` (agent readiness, runtime state, live sampling/validation/scrubbing/export, accepted policy).

> Generic dual-channel diagnostics (`otelEffective`, `resourceEffective`, `otelEnvHints`) удалена без восстановления — см. [ADR-remove-dual-channel-diagnostics.md](../decisions/ADR-remove-dual-channel-diagnostics.md). Этот runbook описывает единственный актуальный read-model endpoint.

## Эндпоинт

```http
GET /actuator/tracingStatus
```

Требует включённого Spring Boot Actuator и exposure эндпоинта `tracingStatus` (см. `management.endpoints.web.exposure.include`).

Secret-safe accepted Category A policy проецируется прямо в секции `policy` этого же read-model (см. ниже) — отдельного endpoint'а не требуется.

## Структура ответа

`TracingStatusSnapshot` (`schemaVersion`, `tracingEnabled`, `authority`, `agent`, `runtime`, `policy`):

```json
{
  "schemaVersion": 1,
  "tracingEnabled": true,
  "authority": "SPRING",
  "agent": {
    "state": "AGENT_READY",
    "markerVisible": true,
    "readinessEndpointPresent": true,
    "extensionVersion": "...",
    "protocolVersion": "...",
    "lifecycle": "...",
    "failureCode": "",
    "capabilities": ["..."]
  },
  "runtime": {
    "implementation": "space.br1440.platform.tracing.otel.core...",
    "mode": "AGENT",
    "reason": null,
    "currentTraceId": "...",
    "currentSpanId": "...",
    "domainMBeansAvailable": true,
    "sampling": { "...": "..." },
    "validation": { "...": "..." },
    "scrubbing": { "...": "..." },
    "export": { "...": "..." },
    "processorErrorsTotal": 0,
    "processorErrorsByName": { "...": "..." },
    "configurationReload": { "...": "..." }
  },
  "policy": { "...": "..." }
}
```

## Секция `agent`

| Поле | Назначение |
|------|------------|
| `state` | `AgentRuntimeState` (`AGENT_READY` и т.д.) — готовность Controlled Agent |
| `markerVisible` | обнаружен ли маркер OTel Java Agent |
| `readinessEndpointPresent` | доступен ли readiness endpoint расширения |
| `extensionVersion` / `protocolVersion` | версии platform extension / JMX-протокола |
| `lifecycle` | lifecycle-стадия расширения |
| `failureCode` | безопасный машинный код диагностики startup-сбоя |
| `capabilities` | заявленные capability расширения |

Подробности состояний — [ADR-sdk-mode-detection.md](../decisions/ADR-sdk-mode-detection.md).

## Секция `runtime`

| Поле | Назначение |
|------|------------|
| `implementation` | FQCN реализации `TraceOperations` (facade vs NoOp) |
| `mode` / `reason` | режим `TracingRuntime` и причина (если применимо) |
| `currentTraceId` / `currentSpanId` | текущий контекст трассировки (если есть активный span) |
| `domainMBeansAvailable` | доступны ли все domain MBean (tri-state registration snapshot → агрегат) |
| `sampling` / `validation` / `scrubbing` / `export` / `processorMetrics` | live decode шести domain observation через grouped accessors `PlatformTracingJmxClient#sampling()`…; при absent owner поля владельца — `null`/не экспортируются, а не sentinel |
| `configurationReload` | метрики reload конфигурации из Diagnostics observation |
| `jmxContractDiagnostics` | lifetime/unrecovered снимки contract violation и execution failure по `JmxContractPoint` |

Все значения читаются **live** через композиционный фасад `PlatformTracingJmxClient` → `*JmxView` → `JmxReadExecutor` из реальных domain MBean агента — без Spring desired mirrors и без generic dump эффективной SDK/agent конфигурации.

### Семантика Micrometer / presence (см. ADR)

- Point-in-time gauges: значение при успехе, `NaN` при недоступном чтении (`Absent` / `ContractViolation` / `ExecutionFailure`).
- `platform.tracing.jmx.domain.registered{domain}` — tri-state `1` / `0` / `NaN`.
- `platform.tracing.jmx.domain.readable{domain}` — успех decode **целого** domain observation.
- `platform.tracing.jmx.owner.present{domain,owner}` — `1` / `0` / `NaN` (NaN когда сам домен нечитаем).
- `platform.tracing.jmx.domain.last_success_age_seconds{domain}` — `NaN` до первого успеха.
- Различение причин отказа — в `jmxContractDiagnostics` и registration/readable, не в значении основной gauge-серии.

См. [ADR-jmx-observation-wire-contract](../decisions/ADR-jmx-observation-wire-contract.md), [ADR-jmx-source-identity-model](../decisions/ADR-jmx-source-identity-model.md), [ADR-jmx-absent-owner-encoding](../decisions/ADR-jmx-absent-owner-encoding.md).

## Секция `policy`

Отражает `AcceptedPolicyProjection`, если он доступен (`AcceptedTracingPolicyReader`), либо явную причину его отсутствия (`PolicyAbsenceReason`: `TRACING_DISABLED`, `AUTHORITY_HAS_NO_ACCEPTED_POLICY`, `AGENT_UNAVAILABLE`, `POLICY_NOT_ACCEPTED`).

## Что этот read-model не публикует

- `otelEffective` / `resourceEffective` — generic dump эффективной OTel SDK/Agent конфигурации (удалён, [ADR-remove-dual-channel-diagnostics.md](../decisions/ADR-remove-dual-channel-diagnostics.md)).
- `otelEnvHints` — Spring→Agent hint bridge (удалён ранее, [ADR-spring-owns-only-what-spring-applies.md](../decisions/ADR-spring-owns-only-what-spring-applies.md)).
- Raw route/drop/force-record значения sampling (wave2 disclosure hardening; см. историю удаления в предыдущей редакции этого документа через `git log`).
- Write-операции — `tracingStatus` **read-only**: изменение runtime policy идёт через JMX/Config Server канал, не через этот endpoint.

## Типичный сценарий: «в YAML одно, в проде другое»

**Симптом:** ожидаемый BSP timeout/limits/exporter endpoint не совпадает с фактическим поведением экспорта.

**Диагностика:** Spring `application.yml` **не** пробрасывается в Agent classloader автоматически (Agent конфигурируется до старта Spring `ApplicationContext`). `tracingStatus` не публикует generic сравнение обоих каналов — только текущее live-состояние специализированных domain MBean (sampling/validation/scrubbing/export). Для startup-only topology (endpoint, limits, BSP queue) проверяйте фактический `OTEL_*` env в Helm/Deployment манифесте деплоя, а не `tracingStatus`.

**Исправление для prod:** выставить нужный `OTEL_*` в Helm/Deployment и перезапустить pod.

## Связанные документы

- [ADR-remove-dual-channel-diagnostics.md](../decisions/ADR-remove-dual-channel-diagnostics.md)
- [ADR-spring-owns-only-what-spring-applies](../decisions/ADR-spring-owns-only-what-spring-applies.md)
- [Final architecture](../architecture/platform-tracing-final-architecture.md)
- [Runtime policy control](./runtime-policy-control.md)
- [mdc-logging-production.md](./mdc-logging-production.md)
