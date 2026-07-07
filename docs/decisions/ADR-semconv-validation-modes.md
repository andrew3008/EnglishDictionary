# ADR: режимы валидации semconv-контракта (STRICT / WARN / DISABLED)

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-06-09 |
| Контекст | Фаза 13, `AttributePolicy` |
| Стек | OTel BOM **1.62.0**, Spring Boot **3.5.5** |

## Проблема

Runtime-валидация semconv-контракта должна одновременно: (1) НЕ ломать application runtime (OTel Error Handling spec: «MUST NOT throw unhandled exceptions at runtime»), (2) давать fail-fast в CI/test, (3) иметь честный аварийный выход без «тихого ремонта». Нужна модель режимов без неоднозначного «мягкого» промежуточного режима.

## Решение

Три режима, каждый решает ровно одну задачу:

| Режим | Назначение | Поведение при нарушении |
|-------|-----------|--------------------------|
| **STRICT** | CI/test fail-fast | `throw SemconvViolationException` **до `startSpan()`** (build-фаза). Ломает тест, не бизнес-логику. |
| **WARN** (prod default) | production | span создаётся; safe-defaults ТОЛЬКО для безопасных platform-required полей (факт подстановки = нарушение); rate-limited лог + метрика. |
| **DISABLED** | явный аварийный opt-out | атрибуты as-is, дефолтов/per-span логов/метрик нет; one-time startup WARN + gauge. |

### Почему НЕТ «мягкого LENIENT»

Отвергнут третий «мягкий» режим (тихая правка значений без сигнала). Обоснование индустриальное: silent repair и «migration mode без условия выхода» — анти-паттерн. Аналоги бинарных/явных моделей: Oracle API Gateway (ENFORCING/PERMISSIVE/**DISABLED**), Ajv strict-mode (не меняет результаты валидации), `OTEL_SDK_DISABLED`, Datadog `DD_TRACE_ENABLED`. Правильная миграция legacy: `WARN` (метрики нарушений) → report → fix backlog → `STRICT` в CI, а не silent defaulting.

### STRICT в production

STRICT **не поддерживается** в production runtime (только CI/test). Технически не запрещаем, но при `semantic.validation-mode=STRICT` вне test-autoconfigure на старте логируем `WARN` («STRICT is intended for test/CI only; production should use WARN») — явный сигнал unsupported runtime mode без жёсткого запрета конфигурации. В тестах включается автоматически (`platform.tracing.test.semconv-strict`, `matchIfMissing=true`).

### DISABLED — границы

- Это **opt-out**, НЕ migration mode. Желательно требовать `disabled-reason` (owner/причина/expiry) в конфиге — он попадает в startup WARN.
- Сигналы выставляются один раз при инициализации бина, НЕ на каждый span/builder: `WARN` + gauge `platform.tracing.semantic.validation.disabled=1`.
- **DISABLED отключает ТОЛЬКО semantic-валидацию, НЕ PII-scrubbing.** `ScrubbingSpanProcessor` работает независимо от `ValidationMode` (security-инвариант не зависит от governance-режима).

### SemconvViolationException

`extends RuntimeException` (**unchecked**), чтобы `start()` не объявлял `throws`. Бросается только в `STRICT`, до `startSpan()`. См. [ADR-typed-span-api-semantic-layer.md](./ADR-typed-span-api-semantic-layer.md).

### Лимиты объёма — не дублируем

Жёсткие лимиты (≤50 атрибутов, value ≤1000, ≤10 events) остаются на уровне SDK `SpanLimits`. `AttributePolicy` их НЕ дублирует — проверяет только semconv-контракт (allowlist/required/low-cardinality/имена).

## Метрики

- `platform.tracing.semconv.violations{rule, builder}` — low-cardinality (id правила + имя builder'а, без значений).
- `platform.tracing.unsafe_attributes{key_class}` — `key_class ∈ {known, unknown, rejected}` (произвольный key НЕ кладётся в тег во избежание cardinality-взрыва; в лог — через sanitize/hash).
- `platform.tracing.semantic.validation.disabled` — gauge (1 при DISABLED).

Метрики эмитятся через абстракцию в core (`SemconvMetrics`), Micrometer-реализация — в autoconfigure (инвариант: core без Micrometer).

## Альтернативы

| Альтернатива | Почему отвергнута |
|--------------|-------------------|
| LENIENT как мягкий режим | Silent repair; migration mode без exit-условия (анти-паттерн) |
| STRICT по умолчанию в prod | Ломает application runtime (нарушает OTel error-handling) |
| Бросать checked-exception | Протекает `throws` в бизнес-код |
| Дублировать SpanLimits в policy | Двойной источник истины лимитов |

## Связанные артефакты

- [ADR-typed-span-api-semantic-layer.md](./ADR-typed-span-api-semantic-layer.md)
- `Lenient.md` (анализ замены LENIENT на DISABLED)
- `TracingProperties.Semantic`, `ValidationMode`, `AttributePolicy`
