# ADR: владение MDC-проекцией trace context

Статус: ACCEPTED
Дата: 2026-08-19
Обновлено: 2026-08-20 — владение MDC-ключами разведено по классам-владельцам (см. «Один писатель
на ключ» и «Reactor-хопы и Hooks» ниже), устранён дефект R1/R2 остаточного прогона Gentoo.
Обновлено: 2026-08-23 — `TracingReactorContextPropagationInitializer` удалён как дублирующий
штатный механизм Boot `ReactorAutoConfiguration`; активация хука теперь — дефолт свойства через
`@PropertySource`, см. «Reactor-хопы и Hooks» ниже.

## Решение

Ширина контракта: **A0-ii, ограниченный**.

`ContextStorageProvider` / `ContextStorage.addWrapper` не приняты: под реальным Java Agent
`opentelemetry-api` живёт в bootstrap classloader, а SPI приложения не является доказанным
механизмом проекции. Контракт не шире реализации.

Platform tracing владеет application-visible SLF4J MDC для:

- `traceId`
- `spanId`
- `traceFlags`
- `requestId`
- `correlationId`

Инструментация OTel Java Agent `logback-mdc` владеет только обогащением logging event и обязана
использовать те же канонические имена. Agent enrichment **не гарантирует** `MDC.get()`.

## Канонические значения

```text
traceId    = SpanContext.getTraceId()
spanId     = SpanContext.getSpanId()
traceFlags = SpanContext.getTraceFlags().asHex()
```

`toString()` и представления вида `sampled` / `not_sampled` запрещены.

`ActiveTraceContextView` не расширяется ради `traceFlags`. Источник — `Span.current().getSpanContext()`
внутри `spring-boot-starter-platform-tracing-otel`.

## Один писатель на ключ

Владение пятью ключами разведено по двум классам пакета `otel.mdc`, без пересечений и без
дублирования логики записи:

- `traceId`, `spanId`, `traceFlags` — единственный писатель `SpanContextMdcProjection`,
  источник `Span.current().getSpanContext()`;
- `requestId`, `correlationId` — единственный писатель `IdentityMdcProjection`, источник —
  identity текущего OTel `Context` (тот же `ContextKey`, которым владеет `IdentityMdcProjection`,
  а не потребители).

`IdentityMdcProjection` **композирует** `SpanContextMdcProjection`, а не дублирует её: составной
`installCurrent()` вызывает span-проекцию и добавляет свои два ключа, возвращая snapshot, который
восстанавливает обе части в обратном порядке установки. `OtelIdentityStorage` и
`OtelPlatformContextPropagation` используют композитную проекцию; `OtelTracingRuntime.startSpan`
продолжает использовать только `SpanContextMdcProjection`, потому что identity-ключами там владеет
вызывающая сторона, а не span.

## Reactor-хопы и Hooks — гарантия, размен и её пределы

Гарантия на choke point «Reactor hops» условна: `ReactiveIdentityContextPropagation` материализует
identity в MDC на переключении Reactor-планировщика только пока в JVM включён
`Hooks.enableAutomaticContextPropagation()`.

Хук включает не платформенный код, а штатная Boot `ReactorAutoConfiguration` — по свойству
`spring.reactor.context-propagation`. `TracingReactorEagerInitConfiguration` не дублирует эту логику
собственным вызовом `Hooks.enableAutomaticContextPropagation()` (до 2026-08-23 дублировала —
`TracingReactorContextPropagationInitializer` удалён как overhead поверх штатного механизма Boot),
а лишь поставляет дефолт `spring.reactor.context-propagation=AUTO` через
`@PropertySource` с наименьшим приоритетом: явное значение в приложении всегда побеждает. Spring
Boot намеренно держит дефолт `LIMITED` из-за накладных расходов автоматического режима
(spring-projects/spring-boot#34201) — платформа осознанно меняет этот размен в пользу предсказуемой
identity/MDC-семантики, когда приложение не задало свойство явно. Если приложение явно выставит
`LIMITED` (в решении такого пока нет ни одного случая), Boot просто не включит хук — платформенного
WARN об этом больше нет: диагностика этого редкого случая признана overhead, не оправдывающим
собственный класс ради него одного.

Проекция span-ключей внутри identity-scope читает `Span.current()`, поэтому на Reactor-хопах она
корректна только если accessor OTel `Context` в Micrometer `ContextRegistry` отработал раньше
нашего identity-accessor'а. Этот порядок не закреплён контрактом (ArchUnit-неприменимо: порядок
регистрации внешних accessor'ов платформе не подконтролен) — это диагностический гейт: если после
включения `Hooks` `mdc.traceId` на `publishOn`/`subscribeOn` остаётся `null` при валидном
`active.traceId`, это отдельный дефект ordering, требующий RCA по фактическому составу
`ContextRegistry`, а не обходного механизма в этом ADR.

## Закрытый перечень choke points

Проекция гарантирована только на управляемых платформой границах:

- Servlet inbound — `TraceResponseHeaderServletFilter` через `OtelIdentityStorage`
- WebFlux inbound — `TraceResponseHeaderWebFilter` и `ReactiveIdentityContextPropagation`
- ручной span платформы — `OtelTracingRuntime.startSpan`
- `@Async` — `AsyncRequestIdentityMdcProjectionInterceptor`
- Reactor hops — `ReactiveIdentityContextPropagation`
- ручной executor — `OtelPlatformContextPropagation`
- носитель save/restore — `OtelIdentityStorage` и `ThreadLocalIdentityStorage`

Известное непокрытие: `@WithSpan`, сторонняя инструментация, span в обход `OtelTracingRuntime`.
Добавление новой точки требует правки этого ADR.

## Владение модулем

Единственный модуль, который одновременно видит `io.opentelemetry.context` и SLF4J MDC —
`spring-boot-starter-platform-tracing-otel`. Представление identity в OTel `Context` (включая
`ContextKey`) и его проекция в MDC владеются пакетом `otel.mdc` (`SpanContextMdcProjection`,
`IdentityMdcProjection`); пакет `runtime.otel` (`OtelIdentityStorage`, `OtelTracingRuntime`) владеет
жизненным циклом identity-scope (открытие/закрытие, канонизация, baggage), делегируя представление
и проекцию в `otel.mdc`, чтобы не дублировать `ContextKey` и не создавать цикл зависимостей между
пакетами.
`platform-tracing-runtime-policy` остаётся logging-agnostic.
Web autoconfigure не вводит новый OTel Context API ради MDC.
