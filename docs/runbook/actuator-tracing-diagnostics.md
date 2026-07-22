# Runbook: диагностика через `/actuator/tracing`

Операторский гайд для SRE: как читать секции `otelEffective` и `otelEnvHints`, и что делать при расхождении Spring-конфига с фактическим OTel Agent.

## Эндпоинт

```http
GET /actuator/tracing
```

Требует включённого Spring Boot Actuator и exposure эндпоинта `tracing` (см. `management.endpoints.web.exposure.include`).

## Две секции OTel-конфига

| Секция | Что показывает | Источник данных |
|--------|----------------|-----------------|
| **`otelEffective`** | Фактически применённые (или ожидаемые по JVM) значения OTel SDK | `-Dotel.*`, `OTEL_*`, платформенные дефолты extension SPI, дефолты OTel SDK |
| **`otelEnvHints`** | Рекомендуемые `OTEL_*` env vars из текущего `platform.tracing.*` | `TracingProperties` + `DurationToMillis` (integer-ms для duration) |

**Важно:** `otelEnvHints` — подсказка для Helm/Deployment, **не** автоматический bridge. Agent classloader изолирован от Spring; изменение `application.yml` без выставления `OTEL_*` не меняет BSP/limits в agent.

## Секция `sdk` (режим работы, Фаза 15)

`GET /actuator/tracing` содержит секцию `sdk` — резолв режима относительно OpenTelemetry SDK:

```json
"sdk": {
  "mode": "AGENT",
  "configuredMode": "AGENT",
  "configuredEnabled": true,
  "agentDetected": true,
  "runtimeState": "AGENT_READY",
  "extensionFailureCode": ""
}
```

| Поле | Значение |
|------|----------|
| `mode` | production mode: `AGENT` или `DISABLED` |
| `configuredMode` | значение `platform.tracing.sdk.mode` как задал оператор |
| `configuredEnabled` | значение `platform.tracing.enabled`; обязано согласовываться с mode |
| `agentDetected` | обнаружен ли OTel Java Agent в JVM |
| `runtimeState` | проверенное startup-состояние Controlled Agent |
| `extensionFailureCode` | безопасный машинный код; полный Agent failure message не раскрывается |

**Назначение — диагностика, не создание SDK** (agent-first): starter не создаёт собственный SDK ни в одном
режиме. `AGENT` требует compatible `READY` и полный mandatory capability profile.
`NoopTraceOperations` активен только при корректном `DISABLED` и полном отсутствии Agent/runtime.
Application `OpenTelemetry` bean запрещён. Подробности —
[ADR-sdk-mode-detection.md](../decisions/ADR-sdk-mode-detection.md).

**Типичная диагностика:**

- startup failure `AGENT_MISSING`/`EXTENSION_MISSING` → проверить Controlled Agent distribution и verifier.
- `CAPABILITY_MISSING`/`EXTENSION_FAILED` → deployment не готов; не обходить ошибку stock Agent'ом.
- `mode=DISABLED` → одновременно нужны `enabled=false`, отсутствие Agent и любого application SDK.
- `agentDetected=false` при ожидаемом агенте → проверить путь `-javaagent` и `otel.javaagent.extensions`.

## Иерархия приоритетов (`otelEffective.source`)

1. `system-property` — `-Dotel.bsp.max.queue.size=8192`
2. `env-var` — `OTEL_BSP_MAX_QUEUE_SIZE=8192`
3. `default-platform` — дефолт из `platform-tracing-otel-javaagent-extension` (`PlatformTracingDefaultsProvider`)
4. `default-otel-sdk` — встроенный дефолт OTel SDK (значение `null` в JSON)

Секреты (`headers`, `token`, `password`, …) маскируются как `***`.

## Типичный сценарий: «в YAML одно, в проде другое»

**Симптом:** `queue.export-timeout: 100ms` в `application.yml`, но traces «медленно» экспортируются или таймауты другие.

**Диагностика:**

```json
"otelEffective": {
  "otel.bsp.export.timeout": {
    "source": "default-platform",
    "value": "5000",
    "envVarName": "OTEL_BSP_EXPORT_TIMEOUT"
  }
},
"otelEnvHints": {
  "OTEL_BSP_EXPORT_TIMEOUT": {
    "otelProperty": "otel.bsp.export.timeout",
    "suggestedValue": "100",
    "springProperty": "platform.tracing.queue.export-timeout"
  }
}
```

**Интерпретация:** Spring говорит 100ms (`otelEnvHints`), agent использует 5000ms (`default-platform` из extension). Расхождение ожидаемо (dual-channel R1).

**Исправление для prod:** добавить в Helm/Deployment:

```yaml
env:
  - name: OTEL_BSP_EXPORT_TIMEOUT
    value: "100"
```

Перезапуск pod → `otelEffective` должен показать `source: env-var`, `value: "100"`.

## Синхронизация Helm с `application.yml`

1. `GET /actuator/tracing` → скопировать `otelEnvHints`.
2. Для каждого ключа `OTEL_*` выставить `suggestedValue` в chart values.
3. После деплоя убедиться, что `otelEffective` совпадает с hints (или с осознанным override через env).

Покрытые свойства в hints (Wave 3+):

- `OTEL_BSP_MAX_QUEUE_SIZE` ← `platform.tracing.queue.max-size`
- `OTEL_BSP_MAX_EXPORT_BATCH_SIZE` ← `platform.tracing.queue.export-batch-size`
- `OTEL_BSP_EXPORT_TIMEOUT` ← `platform.tracing.queue.export-timeout` (integer-ms)
- `OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT` ← `platform.tracing.limits.max-attributes`
- `OTEL_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT` ← `platform.tracing.limits.max-attribute-value-length`
- `OTEL_SPAN_EVENT_COUNT_LIMIT` ← `platform.tracing.limits.max-events`

## Связанные документы

- [traceability.md](../tracing/traceability.md) — PR-1.A/B/C закрыты
- [MIGRATION.md](../MIGRATION.md) — MDC / bridge-otel
- [mdc-logging-production.md](./mdc-logging-production.md) — MDC rollout

## Dual-channel WARN: когда и почему

С v0.1.0 (Wave R1+) на старте приложения может появляться WARN от
`DualChannelDriftDiagnostics`:

```
Dual-channel drift: Spring platform.tracing.queue.export-timeout=100
vs OTel agent effective otel.bsp.export.timeout=5000 (source=env-var).
Spring-сторона не пробрасывается в Agent автоматически
(см. ADR-dual-channel-properties-v0.1).
Если требуется одинаковое значение — выровняйте через OTEL_BSP_EXPORT_TIMEOUT=100
(см. otelEnvHints в /actuator/tracing).
WARN does not mean application misconfiguration.
It means two independent config channels (Spring vs OTel Agent) differ.
```

### Когда WARN появляется

Ровно при выполнении ВСЕХ трёх условий по одной из whitelist shared properties:

1. `effective.source` — `system-property` или `env-var` (override действительно задан);
2. `effective.value != springValue` (значения двух каналов реально отличаются);
3. property входит в whitelist shared values:
   - `OTEL_BSP_MAX_QUEUE_SIZE` ↔ `platform.tracing.queue.max-size`
   - `OTEL_BSP_MAX_EXPORT_BATCH_SIZE` ↔ `platform.tracing.queue.export-batch-size`
   - `OTEL_BSP_EXPORT_TIMEOUT` ↔ `platform.tracing.queue.export-timeout`
   - `OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT` ↔ `platform.tracing.limits.max-attributes`
   - `OTEL_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT` ↔ `platform.tracing.limits.max-attribute-value-length`
   - `OTEL_SPAN_EVENT_COUNT_LIMIT` ↔ `platform.tracing.limits.max-events`

Если `source=default-platform` (override отсутствует) — WARN **не появляется**, даже если
Spring-значение формально отличается. Это by design: значения по умолчанию выровнены
в Wave R1+ (см. CHANGELOG.md, MIGRATION.md).

### Что делать

- **WARN — diagnostic-only сигнал, не misconfiguration.** Само наличие двух каналов
  с разными значениями — нормальное состояние dual-channel контракта v0.1
  (см. [ADR-dual-channel-properties-v0.1.md](../decisions/ADR-dual-channel-properties-v0.1.md)).
- Если расхождение нежелательно — выровняйте каналы:
  - либо привести Spring YAML к значению `OTEL_*` env;
  - либо привести `OTEL_*` env к Spring-значению (см. `otelEnvHints` секцию выше).

### Отключение WARN

```yaml
platform:
  tracing:
    diagnostics:
      dual-channel-warn: false
```

Применяйте только когда расхождение осознанное и постоянное (например, в тестовых
окружениях с намеренно агрессивными OTel env).

## Troubleshooting

| Проблема | Проверка |
|----------|----------|
| `otelEffective` пустой / 404 | Actuator exposure, `platform.tracing.enabled=true` |
| Все BSP — `default-platform`, env не применяется | Имя env: `OTEL_BSP_*` (underscore), не `otel.bsp.*` |
| `export-timeout` не число | OTel SPEC: только integer-ms, без суффикса `ms` |
| Контракт extension ↔ actuator | CI: `OtelEffectiveConfigSnapshotDefaultsContractTest` |
