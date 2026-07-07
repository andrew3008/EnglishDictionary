# Anti-double instrumentation: typed span builders + OTel Java Agent

Руководство по предотвращению **двойной инструментации** (один и тот же вызов порождает два span'а:
один — от OTel Java Agent, второй — от прикладного кода через typed span API Фазы 13).

## Контекст: agent-first модель

Платформа работает в **agent-first** режиме (см. [SUPPORTED.md](../SUPPORTED.md)): сетевые, БД и
messaging span'ы создаёт **OTel Java Agent** автоматически (`http.*`, `db.*`, `rpc.*`,
`messaging.*` по semconv). Это правильный default — ручное создание таких span'ов:

- дублирует span от Агента (двойные узлы в Jaeger, искажённые метрики latency/error-rate);
- ломает иерархию и приводит к «split trace».

Поэтому typed builders (`httpClientSpan()`, `databaseSpan()`, `kafkaProducerSpan()` и т.д.) — это
**escape-hatch**, а не основной способ создания span'ов.

## Когда escape-hatch builder оправдан

Только если операция **не покрыта Агентом**, например:

- legacy/самописный драйвер БД без OTel-инструментации;
- кастомный транспорт (не HTTP/gRPC/Kafka), который вы хотите представить как RPC/messaging;
- бизнес-операция, которую нужно явно выделить в trace (обычно для этого достаточно
  `internalSpan()` или enrichment, см. ниже).

Во всех остальных случаях **не создавайте span вручную** — обогащайте уже существующий (агентский).

## Три линии защиты

### 1) Agent-флаг (primary guard)

```text
-Dotel.instrumentation.experimental.span-suppression-strategy=semconv
```

Подавляет дубли вложенных span'ов одного типа на уровне Агента — основной механизм. См.
[SUPPORTED.md](../SUPPORTED.md), раздел рекомендованных agent-флагов.

### 2) Runtime anti-double guard (модель B)

Каждый platform-builder помечает свой span в OTel Context маркером категории
(`PlatformSpanContextKeys.PLATFORM_SPAN_CATEGORY`). При попытке создать platform-span той же
категории, когда в Context уже активен platform-маркер этой категории (re-entry платформы), builder
**деградирует в enrich** существующего span'а вместо создания нового (`NonOwningSpanScope`).

Важно: агентский span маркера НЕ несёт, поэтому guard срабатывает только на повторный вход самой
платформы — он не подавляет легитимный child-span под агентским родителем. Чтобы принудительно
создать новый span, используйте `forceNewSpan()`.

### 3) ArchUnit + аннотация (historical — removed in Slice 1B)

> **Slice 1B:** правило `TracingArchRules.ESCAPE_HATCH_BUILDERS_REQUIRE_SUPPRESSION` удалено вместе
> с v1 `Facade*SpanBuilder` factory-методами на `PlatformTracing`. Transport governance для
> `manual().transport()` вернётся в Slice 3. Primary guards остаются agent-флаг (§1) и runtime
> anti-double guard (§2).

Ранее (до Slice 1B) правило запрещало вызовы escape-hatch builder'ов без явной аннотации
`@SuppressAgentInstrumentation` на методе или классе:

```java
@SuppressAgentInstrumentation("legacy JDBC-драйвер не инструментируется Агентом")
public void queryLegacy() {
    // historical v1 API — Facade*SpanBuilder удалены в Slice 1B
}
```

Подключение правила в тесте сервиса-потребителя (historical):

```java
// REMOVED in Slice 1B — ESCAPE_HATCH_BUILDERS_REQUIRE_SUPPRESSION no longer exists
```

Аннотация `@SuppressAgentInstrumentation` остаётся в API для будущих transport builders.

## Предпочтительная альтернатива: enrichment

Чаще всего нужно не создать span, а **обогатить** уже созданный Агентом:

```java
// Платформенно-безопасные атрибуты на любом активном span'е:
spanEnricher.enrichCurrentSpan(s -> s
        .requestId(requestId)
        .businessTag("order.status", "PAID")
        .result(SpanResult.SUCCESS));

// Категорийное обогащение — применяется ТОЛЬКО если активный span помечен платформой
// как HTTP_SERVER (иначе no-op):
spanEnricher.enrichCurrentSpanIfPlatformCategory(SpanCategory.HTTP_SERVER, s -> s
        .attribute(SemconvKeys.HTTP_ROUTE, "/orders/{id}"));
```

Enrichment не создаёт span — двойная инструментация невозможна by design.

## Collector backstop

На стороне Collector'а `transform/platform-semconv-backstop` (OTTL) нормализует
`platform.trace.type` по `SpanKind` и снимает `url.full` с SERVER-спанов — последний рубеж, если
span пришёл из источника вне платформенного контроля. См.
`platform-tracing-collector-config/.../otel-collector-gateway-tail-sampling.yaml`.

## E2E проверка отсутствия дублей

Регрессия на дубли HTTP-span'ов покрыта существующей e2e-инфраструктурой
(`DuplicateHttpSpanAgentSmokeTest`, профиль `-PrunE2e`, Agent 2.28.1) и startup-матрицей
`DuplicateSpansRegressionMatrixTest` (Servlet/WebFlux × suppress on/off). Для typed builders основной
контракт degradation-в-enrich проверяется unit-тестом `EscapeHatchSpanBuilderTest` (anti-double guard,
`forceNewSpan` override).

## Связанные документы

- [ADR-typed-span-api-semantic-layer.md](../decisions/ADR-typed-span-api-semantic-layer.md)
- [semconv-mapping.md](../semconv-mapping.md)
- [SUPPORTED.md](../SUPPORTED.md)
