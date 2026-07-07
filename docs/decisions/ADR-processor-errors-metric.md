# ADR: метрика ошибок span processor'ов — AtomicLong vs Micrometer Counter

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-05-23 |
| Контекст | Step 11.3 (D6) |
| Стек | OTel Agent bootstrap classloader + Spring Boot **3.5.5** |

## Проблема

`PlatformCompositeSpanProcessor` должен сигнализировать об ошибках в pipeline (Enriching, Scrubbing, Validating, Watchdog) без блокировки экспорта span'ов (§37).

## Решение v1.0

**`AtomicLong` cumulative counters** в composite processor + exposure через:

- JMX: `PlatformTracingControlMBean.getProcessorErrorCounts()`
- Actuator: `GET /actuator/tracing` → секция `processorErrors`

**Не** Micrometer `Counter platform.tracing.processor.errors.total{processor, phase}` в v1.0.

## Обоснование

| Фактор | AtomicLong | Micrometer Counter |
|--------|------------|-------------------|
| Classloader | Bootstrap (Agent extension) — **OK** | `MeterRegistry` в ApplicationClassLoader — **blocked** |
| Bridge complexity | Minimal | Требует `OtelMeterRegistry` bridge или JMX lazy injection |
| Operational sufficiency | JMX + actuator snapshot | Richer Prometheus integration |

Для v1.0 операционной достаточности JMX + actuator **достаточно** (D6).

## Backlog v1.1

Миграция на Micrometer Counter при решении classloader bridge:

- `OtelMeterRegistry` (OTel Metrics SDK в bootstrap), или
- Lazy registry injection через shared JMX state из autoconfigure

Candidate metric: `platform_tracing_processor_errors_total{processor="EnrichingSpanProcessor"}`.

## Связанные артефакты

- `PlatformCompositeSpanProcessor.getProcessorErrorCounts()`
- `TracingActuatorEndpointProcessorErrorsTest`
- [ADR-composite-processor.md](./ADR-composite-processor.md)
