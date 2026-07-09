# ADR: exposure `opentelemetry-api` в `platform-tracing-core`

## Статус

Принято (PR-6, pre-production).

## Контекст

`platform-tracing-core/build.gradle` объявляет:

```gradle
api 'io.opentelemetry:opentelemetry-api'
```

Кандидат на снижение: `implementation`, если OTel типы не попадают в публичные сигнатуры артефакта.

## Инвентаризация публичного OTel exposure (после PR-3..PR-5)

| Класс | Пакет | OTel в public API |
|---|---|---|
| `DefaultPlatformTracing` | `core.facade` | **Да** — конструкторы `(OpenTelemetry, ...)`, `(OpenTelemetry, AttributePolicy, ...)` |
| `NoOpPlatformTracing` | `core.facade` | Нет прямых OTel типов в сигнатурах |
| `OtelPlatformContextPropagation` | `core.propagation` | Нет OTel типов в public методах (`PlatformContextPropagation`) |
| `SpanEnricher` | `core.enrichment` | Нет OTel в public сигнатурах (OTel внутри) |
| `AttributePolicy` | `core.semconv.policy` | `Attributes`, `AttributeKey` в методах валидации |

## Решение (Outcome B)

**Оставить `api 'io.opentelemetry:opentelemetry-api'`.**

Причины:

1. `DefaultPlatformTracing(OpenTelemetry, ...)` — публичный конструктор для Spring Boot autoconfigure и прямой интеграции; потребители передают `OpenTelemetry` bean.
2. `AttributePolicy.validateAndNormalize(..., Attributes, ...)` — публичный класс с OTel `Attributes` в сигнатуре; используется autoconfigure и bench.
3. Downgrade сломает компиляцию модулей, которые не объявляют прямую зависимость на `opentelemetry-api`, но вызывают эти API.

## Будущая работа (не в scope PR-6)

- Factory-интерфейс без OTel в `core.facade` (инъекция только через `TracingRuntime` SPI).
- Перенос `AttributePolicy` OTel-типов за package-private адаптер.

## Проверка

После любой будущей попытки downgrade:

```bash
./gradlew :platform-tracing-spring-boot-autoconfigure:compileJava
./gradlew :platform-tracing-bench:compileJava
```
