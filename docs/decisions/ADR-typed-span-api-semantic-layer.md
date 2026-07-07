# ADR: typed span API как semantic layer (pragmatic agent-first)

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-06-09 |
| Контекст | Фаза 13 (Typed Span API и Semantic Layer) |
| Стек | OTel BOM **1.62.0**, OTel instrumentation/agent **2.28.1**, Spring Boot **3.5.5** |

## Проблема

Низкоуровневый span API (`SpanScope.setAttribute(String, ...)`) допускает semantic drift: произвольные имена span'ов (high-cardinality), произвольные ключи атрибутов, рассинхрон с semconv и платформенными `platform.*`-конвенциями. Требование Фазы 13 — превратить его в **управляемый semantic layer**: типизированные builder'ы направляют к корректной семантике и валидируют контракт до старта span'а.

Главный риск — **double instrumentation**: в agent-first архитектуре HTTP/DB/RPC/Kafka span'ы уже создаёт OTel Java Agent. Ручные typed-builder'ы для тех же категорий создавали бы дубли (стратегия подавления `semconv` не подавляет ручные span'ы из app-classloader).

## Решение

**Pragmatic agent-first (вариант A).**

1. **Реализуем полноценно (нулевой риск дублей):** `InternalSpanBuilder` (единственный kind, не покрытый Агентом), `SpanEnricher` / `enrichCurrentSpan()` (обогащение span'а Агента — главная потребность), `ExceptionRecorder` (стандартизация записи исключения), `AttributePolicy` (governance без создания span'ов).
2. **Escape-hatch only (с guard'ом):** `HttpServerSpanBuilder`/`HttpClientSpanBuilder`/`DatabaseSpanBuilder`/`RpcServerSpanBuilder`/`RpcClientSpanBuilder`/`KafkaProducerSpanBuilder`/`KafkaConsumerSpanBuilder` — только для операций, НЕ покрытых Агентом.
3. **Anti-double-instrumentation guard (модель B):** default = создать новый span; авто-деградация в enrich ТОЛЬКО при собственном platform-маркере той же категории в `Context` (re-entry платформы), override `.forceNewSpan()`. Маркер — internal `ContextKey<SpanCategory>` (`PlatformSpanContextKeys`, core, не public API); Агентский span его не несёт. Агентские дубли ловятся defense-in-depth (ArchUnit + e2e no-dup).
4. **Direct OTel integration** ([ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md)): builder'ы используют только официальный `Tracer`/`SpanBuilder`, без копирования SDK-классов и без Micrometer Observation.

### SpanCategory: разделение RPC + Kafka (pre-prod breaking-rename)

`SpanCategory.RPC` заменён на пару `RPC_SERVER`/`RPC_CLIENT` (консистентно с `HTTP_SERVER`/`HTTP_CLIENT`), добавлены `KAFKA_PRODUCER`/`KAFKA_CONSUMER`. Это позволяет `CategoryContract` различать required-поля server/client и messaging. Решение не вышло в продакшен — breaking-rename допустим (без `@Deprecated`). Синхронно обновлены `DefaultPlatformTracing.toSpanKind` и reverse-mapping `EnrichingSpanProcessor` (PRODUCER/CONSUMER -> `kafka_producer`/`kafka_consumer`).

### SemconvViolationException = unchecked, timing = до startSpan()

`SemconvViolationException extends RuntimeException` (**unchecked, намеренно**): иначе `start()` builder'а объявлял бы `throws`, что протекло бы в бизнес-код. Бросается **до `startSpan()`** (на build-фазе), только в режиме `STRICT`.

### Схема имён span по категориям (low-cardinality, контракт)

| Категория | Имя span |
|-----------|----------|
| HTTP | `{method} {route}` (route template, не raw URL) |
| DB | `{operation} {collection}` (НИКОГДА не raw SQL с литералами) |
| RPC | `{service}/{method}` |
| Kafka | `{destination} {operation}` (`operation ∈ {publish, process, receive}`; high-cardinality суффиксы топиков недопустимы) |
| Internal | явное низко-кардинальное имя |

### platform.policy.version

`platform.policy.version` = версия **semantic policy contract** (allowlist/required rules), а **НЕ** Maven-версия артефакта. Значение static/manual. Artifact/fleet versioning и `registry diff` — out of scope (YAGNI: нет флота и версий для сравнения).

## Размещение по модулям

| Артефакт | Модуль |
|----------|--------|
| Контракты: `ValidationMode`, `SemconvViolation(Exception)`, `SemconvKeys`, `CategoryContract`/`CategoryContracts` | `platform-tracing-api` (semconv-пакет) |
| Движок: `AttributePolicy`, builder'ы, `SpanEnricher`, `ExceptionRecorder`, san-утилиты | `platform-tracing-core` |
| Бины policy/mode + `TracingProperties.Semantic` + метрика | `platform-tracing-spring-boot-autoconfigure` |
| STRICT auto-binding | `platform-tracing-test` |

`io.opentelemetry.api` в `platform-tracing-api` остаётся `compileOnly` (инвариант: api не тянет OTel в runtime; OTel предоставляется Agent/стартером/SDK — как уже сделано для propagation-control API).

## Альтернативы

| Альтернатива | Почему отвергнута |
|--------------|-------------------|
| Полный набор builder'ов для всех категорий | Double instrumentation под Агентом (дубли HTTP/DB/RPC/Kafka span'ов) |
| Эвристика «любой активный span -> enrich» | Слишком грубо: непокрытая операция теряла бы span; из app-classloader не виден `SpanKind` Агента |
| Micrometer Observation для builder'ов | Противоречит [ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md) и direct-integration |
| Оборачивать `Mono`/`Flux` | Противоречит [ADR-reactor-no-inspan-v0.1.0.md](./ADR-reactor-no-inspan-v0.1.0.md): builder'ы только sync/imperative |

## Связанные артефакты

- [ADR-semconv-validation-modes.md](./ADR-semconv-validation-modes.md)
- [ADR-semconv-governance-weaver.md](./ADR-semconv-governance-weaver.md) — открытый вопрос Weaver-first (на SRE)
- [ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md)
- [ADR-db-semconv-detection.md](./ADR-db-semconv-detection.md)
- [semconv-mapping.md](../semconv-mapping.md)
