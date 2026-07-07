# Platform tracing metrics (v1.0)

Самонаблюдательные метрики модуля `platform-tracing-spring-boot-autoconfigure`.
Регистрируются в общем `MeterRegistry` (starter-platform-metrics).

## Scope

Метрики покрывают **только facade spans** — вызовы через `MeteredPlatformTracing` / `PlatformTracing.startSpan*`.
**Не** включают:

- HTTP spans от OpenTelemetry Java Agent
- Micrometer Observation spans (особенно при `suppress-micrometer-tracing=true`)
- DB/client spans от Agent auto-instrumentation

Duration и latency — из **OTel trace backend**, не из Micrometer Timer (см. [ADR-metrics-duration.md](./decisions/ADR-metrics-duration.md)).

## Метрики

### `platform_tracing_spans_started_total`

| Поле | Значение |
|------|----------|
| Тип | Counter |
| Tag `category` | 6 bounded values — см. `SpanCategory` enum |
| Описание | Количество span'ов, стартованных через платформенный фасад |

Допустимые значения tag `category`:

- `http_server`
- `http_client`
- `database`
- `rpc`
- `internal`

**Запрещено** добавлять high-cardinality tags (`name`, `uri`, `method`, `status`).

### `platform_tracing_exceptions_recorded_total`

| Поле | Значение |
|------|----------|
| Тип | Counter |
| Tags | **нет** (намеренно — каждый increment = failure) |
| Описание | Исключения, зафиксированные через `PlatformTracing.recordException()` |

Декомпозиция по типу исключения — на уровне span attributes / trace backend, не метрики.

## Processor errors (не Micrometer в v1.0)

Ошибки span processor pipeline (Enriching, Scrubbing, …) — **не** Micrometer metrics в v1.0.

Exposure:

- JMX: `PlatformTracingControlMBean.getProcessorErrorCounts()`
- Actuator: `GET /actuator/tracing` → `processorErrors`

См. [ADR-processor-errors-metric.md](./decisions/ADR-processor-errors-metric.md).

## Связанные ADR

- [ADR-metrics-duration.md](./decisions/ADR-metrics-duration.md) — Timer deferred
- [ADR-suppress-micrometer-tracing.md](./decisions/ADR-suppress-micrometer-tracing.md) — HTTP metrics trade-off
