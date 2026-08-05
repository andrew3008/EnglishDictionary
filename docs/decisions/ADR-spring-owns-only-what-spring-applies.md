# ADR: Spring владеет только тем, что Spring применяет

| Поле | Значение |
|------|----------|
| Статус | **Принято**; раздел «Actuator read-model» amended — см. [ADR-remove-dual-channel-diagnostics.md](./ADR-remove-dual-channel-diagnostics.md) (2026-08-02) |
| Дата | 2026-07-23 |
| Контекст | Purge overhead `TracingProperties`; pre-production |
| Related | [ADR-dual-channel-properties-v0.1.md](./ADR-dual-channel-properties-v0.1.md), [ADR-remove-dual-channel-diagnostics.md](./ADR-remove-dual-channel-diagnostics.md) |

## Контекст

`TracingProperties` (~850 строк, 23 nested-группы) дублировал agent-конфигурацию, которую Spring физически не применяет. Единственные потребители зеркальных полей — actuator read-model, `OtelEnvHintsBuilder` и drift-диагностика (`DualChannelDriftDiagnostics`, `DropOldestAspirationDiagnostics`).

## Решение

Удалить из Spring `TracingProperties` все C/D/E поля. Оставить только:

- **A** — Spring применяет (mode, request-id-header, semantic, propagation outbound/platform-headers, kafka trusted topics).
- **Category A runtime policy** — не зеркалируется в `TracingProperties`. Живой namespace:
  `platform.tracing.policy.*` (Config Server / ACTUATOR private JMX → Agent accepted state).
  Export kill-switch: `platform.tracing.policy.exporter.enabled`.
  Inbound platform-header kill-switch:
  `platform.tracing.policy.propagation.trace-control-headers.enabled`.
  Sampling / validation / diagnostics.log-level — только `platform.tracing.policy.*` (+ Agent
  bootstrap keys `platform.tracing.sampling.*` / `validation.*` where still declared by the extension).

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
| `service.name` | overhead (dual-channel) | Удалить из Spring; app-plane: `spring.application.name`; agent: `platform.tracing.service.name` / `OTEL_SERVICE_NAME` |
| `sampling.forceRecordHeader/qaForceHeader` | C (дубль `propagation.platformHeaders.*`) | Удалить |
| `scrubbing.rulesConfig` | C | Удалить; agent-side loader |
| `validation.strictRuntimeAllowed` | C | Удалить из Spring; agent: `platform.tracing.validation.strict-runtime-allowed` |
| `diagnostics.dualChannelWarn/dropOldestAspiration*` | D | Удалить вместе с diagnostics-классами |
| `aop.mode` (`CHILD_SPAN`) | overhead (dead legacy path) | Удалить весь `Aop`-property-класс; `TracedAspect` хардкодит единственное реально применяемое поведение (обогащение активного span'а). `CHILD_SPAN` не имел production-потребителей и воссоздавал дублирование span'ов с OTel Java Agent, от которого платформа защищается в остальных местах |

## Удалённые классы

- `DualChannelDriftDiagnostics`, `DropOldestAspirationDiagnostics`, `OtelEnvHintsBuilder`
- `SharedDefaultsAlignmentTest`, `DurationToMillis`

## Actuator read-model

`GET /actuator/tracing` больше не содержит секций `limits`, `queue`, `exporter`, `enriching`, `watchdog`, `control`, `otelEnvHints`, `resourceSpringConfig`. Agent-effective состояние — через `otelEffective` и `resourceEffective`.

Секции `sampling` / `validation` на `/actuator/tracing` проецируют **только live JMX** (без Spring desired mirrors).
Для sampling overrides HTTP отдаёт только bounded count (`routeRatioCount`),
не raw route/drop/force values (wave2 disclosure hardening). `dropPathCount` / `forceRecordValueCount` удалены
(post-audit remediation): post-P6.6 platform SAMPLING MBean больше не экспонирует `DropPathPrefixes` /
`ForceRecordValues`, поэтому эти counts были всегда `null`. Secret-safe accepted policy — `GET /actuator/tracingPolicy`.

## Последствия

- Breaking change read-model actuator (не wire/API контракт приложения).
- Downstream YAML с удалёнными ключами игнорируется Spring Boot; оператор должен мигрировать на agent-канал где нужно.
- Drift-диагностика dual-channel удалена — расхождение каналов больше не порождается зеркальным слоем.
