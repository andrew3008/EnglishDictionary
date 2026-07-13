# ADR: метрика длительности span'ов — `platform_tracing_span_duration`

| Поле | Значение |
|------|----------|
| Статус | **Принято — отложено (defer v1.0)** |
| Дата | 2026-05-23 |
| Контекст | Step 11.2 |
| Стек | Micrometer + OTel Java Agent **2.27.x** |

## Проблема

Рассматривалась Micrometer `Timer` метрика `platform_tracing_span_duration` для измерения длительности span'ов, создаваемых платформенным фасадом (`MeteredPlatformTracing`).

## Анализ

### Что уже есть в v1.0

| Метрика | Тип | Scope |
|---------|-----|-------|
| `platform_tracing_spans_started_total` | Counter (`category` tag) | Facade spans only |
| `platform_tracing_exceptions_recorded_total` | Counter (без tags) | Facade `recordException` |

### Откуда брать duration в production

| Источник | Покрытие | Cardinality |
|----------|----------|-------------|
| **OTel backend** (Jaeger/Tempo/Grafana) | Все spans: Agent HTTP + facade + DB | Backend-managed |
| **OTel Agent metrics** | HTTP server/client (semconv opt-in) | Bounded semconv attrs |
| **Micrometer Timer на facade** | Только `TraceOperations.startSpan*` | Риск high-cardinality при неправильных tags |

### Риски Timer в v1.0

1. **Дублирование** — Agent уже экспортирует span duration в trace backend; Timer на facade даёт второй канал с другой семантикой (wall-clock фасада vs OTel span end time).
2. **Cardinality** — любой tag кроме `category` (6 значений) опасен; `span.name` / `operation` запрещены platform standard.
3. **Incomplete picture** — Timer покрывает только facade spans, не Agent HTTP/DB — dashboard confusion.

## Решение

**v1.0: НЕ добавлять `platform_tracing_span_duration`.**

Duration для SRE/observability:

- **Primary:** OTel trace backend (span duration — native signal)
- **Secondary:** OTel Agent HTTP metrics (при включённом metrics pipeline)
- **Facade counters:** `platform_tracing_spans_started_total` + backend queries для latency percentiles

## Backlog v1.1

Рассмотреть Timer **только если**:

- [ ] Product requirement на Micrometer/Prometheus histogram без query к trace backend
- [ ] Tags ограничены `category` (6 values) — без `name`, `uri`, `status`
- [ ] SLO dashboard spec согласован с SRE

Кандидатное имя (reserved): `platform_tracing_span_duration_seconds` (Prometheus naming convention).

## Связанные артефакты

- `PlatformTracingMetrics.java`
- `docs/metrics.md`
