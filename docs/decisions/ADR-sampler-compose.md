# ADR: compose sampler — platform ratio vs OTel existing

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-05-23 |
| Контекст | PR-P1 production logic, `PlatformAutoConfigurationCustomizer` |
| Стек | OTel Java Agent **2.27.0**, OTel SDK **1.61.0** |

## Проблема

`addSamplerCustomizer((existing, config) -> buildSampler(config))` игнорировал параметр `existing` — sampler от OTel autoconfigure (`OTEL_TRACES_SAMPLER`, canary deploy) молча замещался платформенным `MutableRatioSampler(0.1)`.

## Решение

### Compose-логика

```text
platform.tracing.sampling.ratio явно задан (non-blank)
  → rootSampler = MutableRatioSampler(ratio)   // JMX ratio доступен
иначе
  → rootSampler = existing                     // respect OTEL_TRACES_SAMPLER

внешняя обёртка = CompositeSampler (force / QA / drop-paths)
```

`MutableRatioSampler` **уже** содержит `Sampler.parentBased(traceIdRatioBased)` внутри — дополнительная обёртка ParentBased **не нужна**.

### Edge case `ratio=0`

Строка `"0"` / `"0.0"` — явно заданная конфигурация → `MutableRatioSampler(0.0)`, JMX регистрируется. Оператор явно запросил drop-all head sampling; force/QA headers пробивают через `CompositeSampler`.

### traceState на force/QA shortcut

`CompositeSampler` возвращает `SamplingResult.recordAndSample()` / `drop()` на shortcut-ветках. OTel Java SDK 1.x вызывает default `getUpdatedTraceState(parentTraceState)`, который сохраняет tracestate без изменений. Дополнительный override не требуется, если tracestate не модифицируется.

### Industry reference

Официальный OTel Spring Boot Starter: `RuleBasedRoutingSampler.builder(SpanKind.SERVER, fallback)` — тот же compose-паттерн с `fallback` = `existing`. Наш `CompositeSampler` архитектурно идентичен, но добавляет force/QA sampling.

## HTTP header attributes (см. PR-P4) — **SUPERSEDED**

> **Superseded by [ADR-context-first-propagation.md](./ADR-context-first-propagation.md)** (и закреплено Фазой 12).
> Описанный ниже подход (чтение `http.request.header.<key>` + `capture-request-headers`) **отменён**:
> `CompositeSampler` больше не читает span-атрибуты, а получает `PlatformTraceControl` из Context
> (через `PlatformTraceControlPropagator`). Это работает для HTTP и Kafka и не привязано к транспорту.
> `otel.instrumentation.http.server.capture-request-headers` для сэмплирования **не требуется**.

~~Force/QA sampling читает `http.request.header.<key>` по [OTel HTTP Semconv](https://opentelemetry.io/docs/specs/semconv/registry/attributes/http/): `<key>` = lowercase, `-` → `_`.~~

~~SPI default: `otel.instrumentation.http.server.capture-request-headers=X-Trace-On,X-QA-Trace`.~~

## Связанные артефакты

- `PlatformAutoConfigurationCustomizer.java` — `buildSampler(Sampler existing, ConfigProperties config)`
- `CompositeSampler.java` — force/QA/drop wrapper
- `MutableRatioSampler.java` — runtime ratio + JMX
