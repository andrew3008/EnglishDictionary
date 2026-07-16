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

## Линии защиты

### 1) Agent-флаг (primary guard)

```text
-Dotel.instrumentation.experimental.span-suppression-strategy=semconv
```

Подавляет дубли вложенных span'ов одного типа на уровне Агента — основной механизм. См.
[SUPPORTED.md](../SUPPORTED.md), раздел рекомендованных agent-флагов.

### 2) Явная топология и generic enrichment

> **Важно (актуально после PR-5/PR-2b):** runtime anti-double guard модели B
> (`NonOwningSpanScope`, деградация builder'а в enrich при re-entry, `forceNewSpan()`) **удалён**
> вместе с legacy `core.span.legacy` / `api.span.builder.*` — см.
> [ADR-legacy-span-builder-stack-removal.md](../decisions/ADR-legacy-span-builder-stack-removal.md).
> V3 `TracingRuntime`/`OtelTracingRuntime` **всегда** создаёт новый span при явном вызове
> `spans().*()` — топология явная (`child()` / `root()` / `detached()`), автоматической
> деградации в enrich при повторном входе платформы больше нет.

Marker-based category enrichment удалён решением
[ADR-api-span-package-boundary](../decisions/ADR-api-span-package-boundary.md). Runtime больше не
записывает category marker в OTel `Context`. Семантические атрибуты, известные до старта, задаются
через typed builders или governed `SpanSpec`; runtime enrichment ограничен именованными
platform-safe атрибутами.

Если нужно избежать двойной инструментации при повторном вызове `spans().*()` в рамках одной
операции — контролируйте это на уровне вызывающего кода (не вызывайте builder повторно) или
используйте enrichment вместо создания нового span'а.

### 3) ArchUnit + аннотация (historical — removed in Slice 1B)

> **Slice 1B:** правило `TracingArchRules.ESCAPE_HATCH_BUILDERS_REQUIRE_SUPPRESSION` удалено вместе
> с v1 `Facade*SpanBuilder` factory-методами на `TraceOperations`. Transport governance для
> `spans().transport()` вернётся в Slice 3. Primary guard остаётся agent-флаг (§1); §2 описывает
> текущий marker-based enrichment механизм (runtime anti-double guard модели B удалён, см. выше).

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
        .result(SpanResult.SUCCESS));
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
`DuplicateSpansRegressionMatrixTest` (Servlet/WebFlux × suppress on/off). Generic enrichment
покрыт `SpanEnricherTest` и `SpanEnricherV3CharacterizationTest`; удалённые category-marker FQN
защищены ArchUnit/API-shape проверками.

> `EscapeHatchSpanBuilderTest` (legacy unit-тест degradation-в-enrich / `forceNewSpan`) удалён
> вместе с `core.span.legacy` в PR-5 — контракт, который он проверял, больше не существует.

## Связанные документы

- [ADR-typed-span-api-semantic-layer.md](../decisions/ADR-typed-span-api-semantic-layer.md)
- [semconv-mapping.md](../semconv-mapping.md)
- [SUPPORTED.md](../SUPPORTED.md)
