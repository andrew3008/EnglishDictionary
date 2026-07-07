# ADR: Reactor / WebFlux — без `inSpan(Mono/Flux)` в v0.1.0

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-05-24 |
| Контекст | Фаза 2 — Wave 2/3; `platform-tracing-autoconfigure-webflux`, `TracingReactorEagerInitConfiguration`, e2e WebFlux suite |
| Стек | Reactor 3.5+, Spring Boot 3.x, Micrometer Context Propagation, OpenTelemetry Java Agent (agent-first) |

## Проблема

В imperative-коде идиома `try (SpanScope scope = tracing.startInternal("op")) { ... }` гарантирует, что span открывается и закрывается в одном потоке, а scope корректно восстанавливается через LIFO-стек.

В Reactor этот паттерн неприменим: `Mono`/`Flux` lazy. Если обернуть assembly в span:

```java
// АНТИПАТТЕРН
try (SpanScope scope = tracing.startInternal("op")) {
    return service.callMono();
}
```

то span будет закрыт **до** фактической subscription — выполнение `callMono()` произойдёт позже, возможно на другом потоке, после выхода из метода. Это приводит к:
- span создаётся на assembly time, а не на subscription time;
- span закрывается до выполнения операций (premature close);
- context теряется между operators;
- cancellation/error-семантика искажается;
- появляются duplicate spans поверх Agent WebFlux instrumentation;
- API становится сложно изменить совместимо.

Текущая архитектура уже содержит WebFlux-specific слой:
- отдельный `platform-tracing-autoconfigure-webflux`,
- `TraceResponseHeaderWebFilter`,
- `RemoteServiceContextPropagation` (Reactor Context mirror),
- suppress Micrometer для WebFlux,
- agent-first instrumentation.

## Решение

### v0.1.0

Платформа **не вводит** reactive API `inSpan(Mono/Flux)`. WebFlux/Reactor покрывается:

1. **Agent-first instrumentation** — OpenTelemetry Java Agent создаёт HTTP server/client spans автоматически.
2. **WebFilter** — `TraceResponseHeaderWebFilter` проставляет response headers (`x-trace-id`, `x-request-id`).
3. **Reactor Context mirror** — `RemoteServiceContextPropagation` переносит платформенные ключи через Reactor Context.
4. **`Hooks.enableAutomaticContextPropagation()`** (Reactor 3.5+) — критичный hook для `flatMap`/`publishOn` сценариев. Spring Boot 3.x включает его автоматически при наличии `micrometer-context-propagation` в classpath. В платформенной авто-конфигурации `TracingReactorEagerInitConfiguration` мы дополнительно убеждаемся, что хук вызывается на старте JVM (через `LazyInitializationExcludeFilter` для `ReactorAutoConfiguration`); сам факт исключения покрыт `TracingReactorEagerInitConfigurationTest`. Reactor не выставляет публичного `Hooks.isAutomaticContextPropagationEnabled()`, поэтому фактический перенос контекста через `publishOn` валидируется в e2e suite (subprocess Spring Boot application с полной micrometer-context-bridge цепочкой или OTel Java Agent) — backlog G2-05-e2e. In-process unit-тест (`ReactorContextPropagationIntegrationTest`) ограничен sanity-проверкой того, что OTel Context корректно виден внутри `Mono.fromCallable` на одном потоке: micrometer-context-bridge для OTel Context недоступен в зависимостях autoconfigure-webflux, что делает полный publishOn-тест ложно-красным без production-окружения.
5. **Developer guide** — `docs/tracing/context-propagation.md` содержит явный запрет imperative `SpanScope` вокруг `Mono`/`Flux` assembly и описание корректных reactive паттернов (через `@Traced` на методах, возвращающих `Mono`/`Flux` — обрабатывается агентом на уровне operator instrumentation).

### v1.1 (backlog)

`inSpan(Mono/Flux)` рассматривается в v1.1 как отдельный reactive-модуль (`platform-tracing-reactor` или внутри `platform-tracing-autoconfigure-webflux`) при появлении реальных кейсов:
- ручная трассировка reactive business operations,
- typed reactive API для внутренних reactive stages,
- agent instrumentation недостаточно выразительна для business spans.

API будет реактив-нативным, не через `SpanScope`:

```java
<T> Mono<T> inSpan(String name, SpanCategory category, Mono<T> source);
<T> Flux<T> inSpan(String name, SpanCategory category, Flux<T> source);
```

Lifecycle реализуется через **`Mono.using()`** (или эквивалент `Flux.using()`) — официальный Reactor паттерн для resource lifecycle с гарантированным cleanup при cancel:

```java
return Mono.using(
    () -> tracing.startInternal(name),    // start on subscription
    scope -> source
        .doOnSuccess(v -> scope.setResult(SpanResult.SUCCESS))
        .doOnError(e -> scope.recordException(e)),
    SpanScope::close                       // close on complete/error/cancel
);
```

Это критично зафиксировать сейчас, чтобы при реализации в v1.1 не использовать `Mono.create()` или `doOnSubscribe + doFinally` (последний не гарантирует cleanup при immediate cancel).

Семантика результата:
- `onComplete` → `platform.trace.result=success`
- `onError` → `platform.trace.result=failure`
- `cancel` → `platform.trace.result=cancelled`

## Альтернативы

| Альтернатива | Отклонено, потому что |
|---|---|
| Реализовать `inSpan(Mono/Flux)` в v0.1.0 как extension `SpanScope` | `SpanScope` основан на ThreadLocal-scope с LIFO-семантикой, что несовместимо с lazy/multithread природой Reactor. Приведёт к premature close, потере context, duplicate spans. |
| Использовать `Mono.create()` для реактивного span | Не позволяет корректно обработать cancel; ломает backpressure; антипаттерн в Reactor 3.5+. |
| Полностью отказаться от reactive API и в v1.1 | Снимает roadmap-видимость; теряем сценарии business-spans на внутренних reactive stages. |

## Последствия

**Плюсы:**
- Минимальное вмешательство в WebFlux runtime в v0.1.0; полностью agent-first.
- Зафиксированный backlog с правильным паттерном (`Mono.using()`) предотвращает архитектурные ошибки при реализации v1.1.
- E2E assertion `Hooks.isAutomaticContextPropagationEnabled()` исключает регрессию context propagation в `publishOn`/`flatMap` сценариях.

**Минусы:**
- Прикладной код не получает удобного API для business-spans внутри Reactor pipeline в v0.1.0. Митигировано agent instrumentation для HTTP/RPC/DB boundary и `@Traced` для блокирующих stages.

## Связанные документы

- `docs/tracing/context-propagation.md` — developer guide, секция Reactor.
- `ADR-async-task-decorator-opt-in.md` — стратегия для `@Async`.
- `ADR-remote-service-mdc-webflux.md` — Reactor Context mirror для remote service.
