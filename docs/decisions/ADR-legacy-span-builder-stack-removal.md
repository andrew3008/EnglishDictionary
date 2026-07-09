# ADR: удаление legacy span builder stack

## Статус

Принято (pre-production, PR-5 рефакторинга `platform-tracing-core`).

## Контекст

До рефакторинга платформа имела два пути создания span'ов:

1. **Legacy** — прямые OTel builder'ы в `core.span.legacy` + `api.span.builder.*`
2. **V3** — `PlatformTracing.manual()` → `TracingRuntime` / `OtelTracingRuntime`

После PR-2b v3 runtime проставляет `PLATFORM_SPAN_CATEGORY`, и typed enrichment работает без legacy.

## Решение

Удалены полностью:

- `space.br1440.platform.tracing.core.span.legacy`
- `space.br1440.platform.tracing.api.span.builder.*`
- `NonOwningSpanScope` (anti-double re-entry enrich)

## Намеренно снятое поведение (не портировано в v3)

| Поведение legacy | Решение |
|---|---|
| Anti-double guard (re-entry → enrich) | Снято. V3 всегда создаёт span; nested `manual().operation()` — отдельные span'ы |
| `lazyAttribute` после `startSpan` | Снято. Атрибуты задаются через `SpanSpec` / fluent v3 builders до старта |
| `unsafeAttribute` escape-hatch | Снято. Используйте governed `SpanSpec` / allowlist enrichment |
| `forceNewSpan()` | Снято. Явная топология через `child()` / `root()` / `detached()` |
| Прямой `api.span.builder.*` | Удалён |

## Обоснование

- Agent-first модель: транспортные span'ы создаёт OTel Java Agent; приложение использует `manual()` и enrichment.
- Единая точка создания span'ов — `OtelTracingRuntime.startSpan(SpanSpec)`.
- Characterization tests PR-0 зафиксировали legacy-поведение до удаления; v3-покрытие — `SpanEnricherV3CharacterizationTest`, `V3MarkerPortCharacterizationTest`.

## Последствия

- Потребители `api.span.builder.*` (если были внутри платформы) переходят на `api.manual.*`.
- `AttributePolicy.auditUnsafeAttribute` остаётся для метрик/тестов политики, но публичного builder API для unsafe больше нет.
- `allowUnsafeAttributes` в autoconfigure properties остаётся до отдельной чистки конфигурации.

## Проверка

```bash
rg "api\.span\.builder" -t java E:\Platform_Traces
rg "core\.span\.legacy" -t java E:\Platform_Traces
```

Ожидается: нулевые совпадения в production/test коде (кроме ADR и архивной документации).
