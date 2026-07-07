# ADR: composite span processor — native vs `PlatformCompositeSpanProcessor`

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-05-23 |
| Контекст | Step 0.4 (spike D9), Step 6 |
| Стек | OTel SDK **1.61.0** (BOM), OTel Java Agent **2.27.x** |

## Проблема

Pipeline SDK-extension регистрирует несколько span processor'ов (Enriching, Scrubbing, Validating, Watchdog). Нужно выбрать между:

- **A:** `SpanProcessor.create()` / `MultiSpanProcessor` из OTel SDK
- **B:** собственный `PlatformCompositeSpanProcessor`

## Spike (readonly, OTel SDK 1.61.0)

Исходник: [`MultiSpanProcessor.java`](https://github.com/open-telemetry/opentelemetry-java/blob/v1.61.0/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/MultiSpanProcessor.java)

| Критерий | `MultiSpanProcessor` (SDK 1.61.0) | `PlatformCompositeSpanProcessor` |
|----------|-----------------------------------|----------------------------------|
| `implements ExtendedSpanProcessor` | **Да** | **Да** |
| `onEnding()` delegation | **Да** (только delegates с `isOnEndingRequired`) | **Да** |
| Exception isolation (§37) | **Нет** — исключение в delegate прерывает цепочку | **Да** — try/catch per delegate |
| Self-diagnostic error counters | **Нет** | **Да** — `AtomicLong` per processor name |
| JMX / actuator exposure | **Нет** | **Да** — `getProcessorErrorCounts()` |

**Коррекция относительно раннего research:** в OTel SDK ≤1.49 `MultiSpanProcessor` не реализовывал `ExtendedSpanProcessor`. В **1.61.0** (наш BOM) — **реализует**. Блокер «нет onEnding» **снят**.

Оставшийся блокер для Scenario A: **§37 non-blocking** — сбой Scrubbing/Validating не должен блокировать экспорт span'а; native composite этого не гарантирует.

## Решение

**Scenario B — `PlatformCompositeSpanProcessor` (сохраняем).**

Причины v1.0:

1. **Fault isolation** — ошибка одного processor'а не прерывает pipeline (Enriching → Scrubbing → Validating → Watchdog).
2. **Operational visibility** — cumulative counters ошибок доступны через JMX (`PlatformTracingControlMBean`) и `/actuator/tracing` (`processorErrors`).
3. **Bootstrap classloader** — processor живёт в Agent extension; счётчики без зависимости от Spring `MeterRegistry`.

## Будущее (v1.1+)

При появлении в OTel SDK официального composite с per-delegate exception handling — рассмотреть миграцию или thin-wrapper вокруг `MultiSpanProcessor` с сохранением counters.

## Связанные артефакты

- `PlatformCompositeSpanProcessor.java`
- `PlatformTracingControlMBean` / `TracingActuatorEndpoint`
- [ADR-processor-errors-metric.md](./ADR-processor-errors-metric.md)
