# Runbook: диагностика через `/actuator/tracing`

> `/actuator/tracing` is diagnostic only. It does not close `RG-CONTROLLED-AGENT` or `RG-IDENTITY-TRUST`; **PRODUCTION ROLLOUT FORBIDDEN** while either gate is open.

Операторский гайд для SRE: как читать live-диагностику Agent/JMX и секцию `otelEffective`.

## Эндпоинт

```http
GET /actuator/tracing
```

Требует включённого Spring Boot Actuator и exposure эндпоинта `tracing` (см. `management.endpoints.web.exposure.include`).

Secret-safe accepted Category A policy — отдельный эндпоинт:

```http
GET /actuator/tracingPolicy
```

## Что показывает `/actuator/tracing`

| Секция | Назначение |
|--------|------------|
| `mode` / `sdk` / `spanFactory` | Режим starter и готовность Controlled Agent |
| `otelEffective` / `resourceEffective` | Фактические JVM/env значения OTel SDK (секреты маскируются как `***`) |
| `sampling` | Live Agent/JMX: `liveRatio`, `liveSamplerEnabled`, **count** override (`routeRatioCount`), `configVersion`, `configSource` |
| `validation` / `scrubbing` | Live Agent/JMX состояние (без Spring desired mirrors) |
| `export` / `processors` / `config` | Ops metrics; `config.auditTrail` — bounded incident history |

**Удалено (wave2 disclosure hardening):** raw `liveRouteRatios` / `liveDropPaths` / `liveForceRecordHeaderValues` values и hardcoded scrubbing strings (`customRulesSource` / `customRulesVisible` / `note`). Compatibility aliases запрещены.

**Удалено ранее:** секция `otelEnvHints` (Spring→Agent hint bridge). Agent-конфигурация задаётся только через `OTEL_*` / Agent bootstrap keys.

## Секция `sdk`

```json
"sdk": {
  "mode": "AGENT",
  "configuredMode": "AGENT",
  "agentDetected": true,
  "runtimeState": "AGENT_READY",
  "extensionFailureCode": ""
}
```

| Поле | Значение |
|------|----------|
| `mode` | production mode: `AGENT` или `DISABLED` |
| `configuredMode` | значение `platform.tracing.mode` |
| `agentDetected` | обнаружен ли OTel Java Agent |
| `runtimeState` | startup-состояние Controlled Agent |
| `extensionFailureCode` | безопасный машинный код |

Подробности — [ADR-sdk-mode-detection.md](../decisions/ADR-sdk-mode-detection.md).

## Иерархия приоритетов (`otelEffective.source`)

1. `system-property` — `-Dotel.bsp.max.queue.size=8192`
2. `env-var` — `OTEL_BSP_MAX_QUEUE_SIZE=8192`
3. `default-platform` — дефолт из `platform-tracing-otel-javaagent-extension`
4. `default-otel-sdk` — встроенный дефолт OTel SDK (значение `null` в JSON)

Секреты (`headers`, `token`, `password`, …) маскируются как `***`.

## Типичный сценарий: «в YAML одно, в проде другое»

**Симптом:** ожидаемый BSP timeout не совпадает с фактическим поведением экспорта.

**Диагностика:** смотреть `otelEffective` — поле `source` показывает, откуда Agent реально взял значение. Spring `application.yml` **не** пробрасывается в Agent classloader автоматически.

**Исправление для prod:** выставить нужный `OTEL_*` в Helm/Deployment и перезапустить pod; после деплоя `otelEffective` должен показать `source: env-var`.

## Sampling counts (wave2)

| Поле | Семантика |
|------|-----------|
| `routeRatioCount` | Число route-ratio overrides; `0` = успешно прочитана пустая карта; `null` = JMX unavailable |

`dropPathCount` / `forceRecordValueCount` **удалены** (post-P6.6: platform SAMPLING MBean больше не
экспонирует `DropPathPrefixes` / `ForceRecordValues`, поэтому эти counts всегда были `null` и не несли
диагностической ценности). Raw route patterns / drop paths / force-record values **не** экспонируются через HTTP.

## Связанные документы

- [Final architecture](../architecture/platform-tracing-final-architecture.md)
- [ADR-spring-owns-only-what-spring-applies](../decisions/ADR-spring-owns-only-what-spring-applies.md)
- [Runtime policy control](./runtime-policy-control.md)
- [Wave2 consumer census](../research/evidence/overhead-simplify-wave2/contract-consumer-census.md)
- [mdc-logging-production.md](./mdc-logging-production.md)
