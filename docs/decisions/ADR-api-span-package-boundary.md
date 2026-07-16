# ADR: граница публичного API пакета `api.span`

| Поле | Значение |
|---|---|
| Статус | **Принято** |
| Дата | 2026-07-16 |
| Контекст | Pre-production очистка публичного tracing API |
| Дополняет | `ADR-typed-span-api-semantic-layer.md`, `ADR-legacy-span-builder-stack-removal.md`, `ADR-platform-tracing-clean-core-hybrid.md` |

## Контекст

В `platform-tracing-api` одновременно находились:

- стабильные application-facing контракты создания и lifecycle span;
- OTel-specific `SpanEnrichment`, раскрывающий `AttributeKey`;
- `SemconvKeys`, используемый только OTel-backed кодом core;
- санитайзеры, являвшиеся деталями единственного internal caller;
- marker-based category enrichment, не имевший production callers.

Такое размещение создавало ложные extension points, связывало enrichment API с OpenTelemetry и
оставляло произвольный `businessTag` без достаточного PII/cardinality governance. Платформа ещё не
вышла в production, поэтому compatibility aliases и deprecated bridges не требуются.

Отдельно обнаружен correctness-дефект: `SpanSpec` attributes записывались после
`SpanBuilder.startSpan()`, не были видны sampler'у и теряли list type из-за преобразования в строку.

## Решение

1. Сохранить без FQN/shape изменений `SpanFactory`, доменные span-типы и весь `api.span.spec`.
2. Удалить публичный OTel-based `SpanEnrichment` и marker-based category path.
3. Оставить runtime enrichment как отдельный публичный контракт
   `api.span.enrich.SpanEnricher`, принимающий только `GenericSpanEnrichment`.
4. Ограничить `GenericSpanEnrichment` именованными методами `requestId`, `userHash`, `result`;
   удалить произвольный `businessTag`.
5. Реализацию разместить в `core.enrichment.DefaultSpanEnricher`; Spring публикует bean по
   API-interface.
6. Переместить `SemconvKeys` в `core.semconv`. Публичным реестром имён остаётся
   `PlatformAttributes`; новый `SpanAttributeKey<T>` не вводится.
7. Удалить неиспользуемый `SqlSanitizer`; переместить `UrlSanitizer` в `core.manual` и сделать
   package-private.
8. Передавать все scalar/list `SpanSpec` attributes в OTel `SpanBuilder` до `startSpan()`.
   Канонический `platform.trace.type` устанавливать последним.
9. Удалить `opentelemetry-api` из compile classpath `platform-tracing-api`.
   `opentelemetry-context` остаётся `compileOnly` для утверждённых propagation/control контрактов.
10. Не расширять `TraceOperations`: он сохраняет только `traceContext()` и `spans()`.

## Почему capability не удалена полностью

Приложения по-прежнему могут обогащать активный agent/manual span через инжектируемый
`SpanEnricher`. Удалён только недоказанный category-specific OTel escape hatch. Семантические
атрибуты, известные до старта, задаются через governed `SpanSpec` и typed manual builders.

## Отклонённые альтернативы

- **Сохранить public `AttributeKey`.** Оставляет OTel type leak и ложный category API без callers.
- **Ввести `SpanAttributeKey<T>`.** Создаёт третий реестр рядом с `PlatformAttributes` и
  `SemconvKeys` без подтверждённого use case.
- **Добавить `TraceOperations.enrich()`.** Раздувает узкий root facade; отдельный Spring bean
  `SpanEnricher` лучше выражает optional capability.
- **Сделать enrichment полностью internal.** Лишает приложения безопасного способа дополнить
  agent-created span разрешёнными platform attributes.
- **Сохранить compatibility aliases.** Платформа pre-production; aliases закрепили бы удаляемый
  архитектурный долг.

## Последствия

Положительные:

- enrichment API больше не раскрывает `io.opentelemetry.api`;
- category marker и скрытая allowlist-семантика удалены;
- sampler видит `SpanSpec` attributes в момент принятия решения;
- list attributes сохраняют точный OTel type;
- Spring consumers зависят от API-interface, а не от concrete core class;
- PII/cardinality surface сокращён за счёт удаления arbitrary business tags.

Отрицательные:

- старые FQN удалены без переходного периода;
- `SemconvKeys` больше недоступен прикладному коду;
- новый runtime enrichment use case требует отдельного именованного метода и governance review.

## Изменённые предыдущие решения

- `ADR-typed-span-api-semantic-layer.md`: `SemconvKeys` больше не public API; marker-based category
  enrichment удалён; generic enrichment остаётся.
- `ADR-legacy-span-builder-stack-removal.md`: утверждение о поддерживаемом
  `PLATFORM_SPAN_CATEGORY` path отменено.
- `ADR-platform-tracing-clean-core-hybrid.md`: `SemconvKeys` относится к OTel-backed core, а не к
  public contracts.

`ADR-trace-operations-root-api.md`, SpanSpec ADR и naming ADR остаются действующими.

## Follow-up

- Новые runtime enrichment attributes добавлять только как именованные методы после проверки PII,
  cardinality и минимум двух реальных consumers.
- Полное удаление `opentelemetry-context` из API рассматривается отдельным ADR, поскольку затрагивает
  propagation/control contracts и не относится к enrichment boundary.
