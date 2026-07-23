# ADR: Spring владеет только тем, что Spring применяет

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-07-23 |
| Контекст | Purge overhead `TracingProperties`; pre-production |
| Related | [ADR-dual-channel-properties-v0.1.md](./ADR-dual-channel-properties-v0.1.md) |

## Контекст

`TracingProperties` (~850 строк, 23 nested-группы) дублировал agent-конфигурацию, которую Spring физически не применяет. Единственные потребители зеркальных полей — actuator read-model, `OtelEnvHintsBuilder` и drift-диагностика (`DualChannelDriftDiagnostics`, `DropOldestAspirationDiagnostics`).

## Решение

Удалить из Spring `TracingProperties` все C/D/E поля. Оставить только:

- **A** — Spring применяет (facade wiring, propagation outbound, semantic, kafka, response, aop, suppression, service.name)
- **B** — JMX reconcile (sampling, scrubbing, validation, exporter.enabled, propagation.enabled, diagnostics.logLevel)

Agent-канал (`OTEL_*`, `PLATFORM_TRACING_*`, `PlatformTracingDefaultsProvider`, `ExtensionPropertyNames`) остаётся единственным источником истины для SDK limits/queue/exporter/resource/enriching/watchdog/baggage.

## Таблица удалённых групп

| Группа / поле | Классификация | Действие |
|---------------|---------------|----------|
| `Facade` | E (мёртвый код) | Удалить из Spring |
| `Resource` | C (actuator mirror) | Удалить; agent: `platform.tracing.resource.*` |
| `Limits` | C+D | Удалить; agent: `OTEL_SPAN_*`, `PlatformTracingDefaultsProvider` |
| `Queue` | D (drift mirror) | Удалить; agent: `OTEL_BSP_*`, `platform.tracing.queue.overflow-policy` |
| `ServiceNames` | E (мёртвый код) | Удалить |
| `Enriching` | C | Удалить; agent: `platform.tracing.enriching.*` |
| `Watchdog` | C | Удалить; agent: `platform.tracing.watchdog.*` |
| `Control` | C | Удалить; agent: `platform.tracing.control.*` |
| `Propagation.Baggage` | C/E | Удалить; agent: `platform.tracing.propagation.baggage.*` |
| `Exporter.Otlp` | C | Удалить; agent/env: `OTEL_EXPORTER_OTLP_*` |
| `service.version/environment/c-group/container-id` | C | Удалить из Spring; agent: `platform.tracing.service.*` |
| `sampling.forceRecordHeader/qaForceHeader` | C (дубль `propagation.platformHeaders.*`) | Удалить |
| `scrubbing.rulesConfig` | C | Удалить; agent-side loader |
| `validation.strictRuntimeAllowed` | C | Удалить из Spring; agent: `platform.tracing.validation.strict-runtime-allowed` |
| `diagnostics.dualChannelWarn/dropOldestAspiration*` | D | Удалить вместе с diagnostics-классами |

## Удалённые классы

- `DualChannelDriftDiagnostics`, `DropOldestAspirationDiagnostics`, `OtelEnvHintsBuilder`
- `SharedDefaultsAlignmentTest`, `DurationToMillis`

## Actuator read-model

`GET /actuator/tracing` больше не содержит секций `limits`, `queue`, `exporter`, `enriching`, `watchdog`, `control`, `otelEnvHints`, `resourceSpringConfig`. Agent-effective состояние — через `otelEffective` и `resourceEffective`.

## Последствия

- Breaking change read-model actuator (не wire/API контракт приложения).
- Downstream YAML с удалёнными ключами игнорируется Spring Boot; оператор должен мигрировать на agent-канал где нужно.
- Drift-диагностика dual-channel удалена — расхождение каналов больше не порождается зеркальным слоем.
