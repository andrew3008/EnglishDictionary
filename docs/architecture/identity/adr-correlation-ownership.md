# ADR: единый владелец X-Correlation-Id

Статус: ACCEPTED
Дата: 2026-08-19
Обновлено: 2026-08-20 — закрыты все открытые решения; канонический joint-режим
отделён от legacy standalone error-handling.

## Решение

Один канонический заголовок `X-Correlation-Id`, один семантический владелец,
одна canonical-политика валидации, один MDC-ключ `correlationId`.

Владелец контракта request identity и HTTP-транспорта — **platform tracing**.
Error-handling в совместном (joint) режиме является потребителем уже
resolved effective correlation ID и не владеет lifecycle.

Второй correlation-заголовок (`X-Platform-Correlation-Id` и аналоги) запрещён.
Имя wire-заголовка не конфигурируется: единственная константа —
`PlatformHeaders.X_CORRELATION_ID`. Сравнение имён HTTP-заголовков остаётся
регистронезависимым; строковая константа, документация и тестовые snapshots
используют написание `X-Correlation-Id`. Текущее расхождение
`PlatformHeaders` (`X-Correlation-ID`) и `ErrorHandlingHeaders` (`X-Correlation-Id`)
закрывается тем, что `ErrorHandlingHeaders.X_CORRELATION_ID` ссылается на
`PlatformHeaders.X_CORRELATION_ID`, а значение константы приводится к
`X-Correlation-Id`. Configurable property вида `correlationIdHeader` не вводится.

`FailClosedCorrelationBaggagePropagator` остаётся неизменным: baggage не является
транспортом business correlation.

Квалификационный тест `trustedCorrelationHopRestoresDownstreamEffectiveCorrelation`
остаётся известным `FAIL_PRODUCT_CONTRACT`, пока последующие slice этого workstream
не реализуют inbound/outbound transport. Этот ADR фиксирует контракт, а не
закрывает дефект сам по себе.

## Режимы

Joint-режим определяется **присутствием bean** `tracingRequestContextSupplier`
(`Supplier<RequestContext>` из
`ErrorHandlingTracingRequestContextAutoConfiguration`), а не
`@ConditionalOnClass`. Tracing API транзитивно присутствует на classpath
error-handling всегда; classpath-проверка бессмысленна. Error-handling не
инжектирует `TracingRuntime`, `OtelIdentityStorage` и другие impl-типы.

- joint (bean присутствует): единственный владелец всего correlation lifecycle —
  tracing identity boundary. Error-handling читает
  `requestContextSupplier.get().correlationId()` (nullable: `null` означает
  отсутствие активной корреляции) и не парсит request, не валидирует, не
  генерирует, не пишет MDC, не решает response echo.
- standalone (bean отсутствует): legacy error-handling сохраняет текущее
  поведение без изменений HTTP-контракта, включая собственную grammar,
  генерацию при отсутствии заголовка, запись MDC и запись response-заголовка.

Один validator не обслуживает оба режима. Canonical validator обязателен только
в joint-режиме. Standalone не переводится на canonical grammar.

## Грамматика и валидация

Canonical grammar — уже реализованная в `OtelIdentityStorage` /
`ThreadLocalIdentityStorage` проверка `[A-Za-z0-9_-]{1,128}`:

- длина `1..128`;
- алфавит `[A-Za-z0-9_-]`;
- пробелы не обрезать и не нормализовать — значение либо целиком валидно,
  либо отклоняется;
- регистр значения сохранять;
- `.`, `:`, whitespace, CR/LF/NUL, Unicode control/bidi отклонять;
- сырое отклонённое значение не писать в лог.

Дублированная реализация `requireCanonical` извлекается в один
framework-neutral класс `CorrelationIdSupport` в
`platform-tracing-runtime-policy` рядом с `RequestIdSupport`. Это не новый
validator и не тип в `tracing-api`: API владеет контрактами, domain-валидация
остаётся в runtime-policy. В отличие от `RequestIdSupport`, correlation-вариант
не генерирует UUID при отсутствии значения.

`openCorrelationScope` и HTTP inbound assessment tracing используют этот
canonical policy. Legacy standalone продолжает
`CorrelationIdAllowedPatternCompilation` / `CorrelationIdGenerator`.

## Отсутствующее, невалидное и дублированное значение

Correlation ID опционален. Tracing не генерирует его автоматически.

- 0 значений заголовка — absent, effective `null`;
- ровно 1 атомарное значение — validate по canonical grammar;
- `>1` значений, включая уже склеенное контейнером comma-joined поле, —
  reject как duplicate целиком; `getFirst()` / `getHeader()` запрещены
  как источник истины в canonical inbound;
- невалидное или пустое значение — drop, запрос не падает, effective `null`.

Servlet читает `HttpServletRequest.getHeaders(X_CORRELATION_ID)` (все значения).
WebFlux читает `HttpHeaders.get(X_CORRELATION_ID)` (список). Регистро-разные
имена (`X-Correlation-Id` и `x-correlation-id`) — один и тот же заголовок и
при двух значениях дают duplicate.

Отказ фиксируется low-cardinality метрикой по причине (`length`, `charset`,
`duplicate`) без самого correlation ID как label.

## Trust boundary

Defaults:

```yaml
platform:
  tracing:
    propagation:
      correlation:
        accept-inbound: false
      outbound:
        propagate-correlation-id: false
```

Outbound дополнительно требует уже существующие условия: `outbound.enabled=true`,
совпадение destination с `trusted-host-patterns`, запрет IP literal без явного
разрешения, повторную canonical-проверку значения перед записью.

`accept-inbound=true` допустим только за ingress/gateway/service-mesh, который
удаляет клиентский `X-Correlation-Id` и ставит доверенное значение, либо в
закрытом service-to-service сегменте с аутентифицированным workload identity.
Это deployment precondition, а не предположение Java-кода. Публичный сервис
без доказанного ingress stripping не получает release PASS с
`accept-inbound=true`.

## Scope precedence

Inbound accepted correlation образует базовый request scope.

Servlet: локальный `openCorrelationScope(...)` временно переопределяет
inbound-значение; после close восстанавливается inbound либо `null`.

WebFlux: `openCorrelationScope` — blocking API и не является механизмом
локального override. Canonical reactive override — уже существующий
`ReactiveCorrelationOperations.withCorrelationId(...)`, который пишет в тот же
Reactor-ключ `ReactiveIdentityContextPropagation.KEY`, что и inbound
`contextWrite`. Outbound WebFlux читает этот ключ через `Mono.deferContextual`,
не через ThreadLocal / `TracingRuntime`. Nested `contextWrite` scoped к
поддереву подписки: внутри override исходящий hop видит внутреннее значение,
после выхода — inbound.

Outbound adapter читает effective значение в момент подписки/выполнения
HTTP-вызова, а не при создании клиента.

## Response echo

В joint/canonical режиме response echo **всегда выключен**. Runtime-переключатель
`responseEchoCorrelationId` не вводится: подтверждённого потребителя нет,
спекулятивная точка расширения запрещена.

- joint: effective correlation ID принадлежит tracing; если контракт ошибки
  включает correlation в теле — то же effective значение; заголовок ответа
  `X-Correlation-Id` отсутствует;
- standalone: текущая семантика error-handling не меняется, включая запись
  response-заголовка.

Если позже понадобится echo-on, это отдельный slice после подтверждённого
требования.

## Inbound и outbound ownership

Единственная точка canonical-парсинга — tracing inbound boundary:
`TraceResponseHeaderServletFilter` и `TraceResponseHeaderWebFilter`.

Единственный владелец исходящей инъекции — app-plane explicit outbound port
(`PlatformOutboundPropagation.resolve(decision, control)` после transports
workstream). Hidden `Context.current()`, MDC и baggage как источник
correlation запрещены. Remove-before-apply канонического заголовка обязателен:
application code не обходит trust policy, заранее поставив `X-Correlation-Id`.
При untrusted/disabled/invalid заголовок отсутствует.

W3C `traceparent` остаётся во владении OTel instrumentation. `traceId`,
`requestId` и `correlationId` — раздельные семантики. Приоритет
`traceparent → correlationId` в canonical-режиме запрещён.

## Error-handling и MDC

В joint-режиме один lifecycle owner — tracing identity boundary: inbound
assessment, effective value, scope, MDC-проекция (`IdentityMdcProjection`) и
response-echo policy. Tracing identity boundary создаётся раньше
`CorrelationIdFilter` / `ReactiveGlobalExceptionHandler`.

Чтобы статический ArchUnit-запрет `org.slf4j.MDC` был совместим с сохранённым
standalone-писателем, существующая MDC/generation/echo-логика error-handling
живёт в ровно одном явно allowlisted legacy-коллабораторе. Canonical/joint
классы от `MDC` не зависят. Strategy/SPI/registry не вводятся.

## Владение модулем

- `spring-boot-starter-platform-tracing-api` — константа заголовка и публичные
  контракты identity/outbound;
- `platform-tracing-runtime-policy` — `CorrelationIdSupport` (извлечение
  существующей grammar);
- `spring-boot-starter-platform-tracing-otel` — lifecycle identity-scope и
  MDC-проекция (`IdentityMdcProjection`);
- web autoconfigure — inbound/outbound adapters;
- error-handling — потребитель в joint-режиме, владелец только legacy
  standalone-пути;
- Java agent — fail-closed stripping `platform.correlation.id` из baggage,
  без app-plane транспорта correlation.

Зависимость `platform-error-handling-core → spring-boot-starter-platform-tracing-api`
уже существует; нового shared-correlation модуля нет.

## Закрытые решения

Бывший перечень «Открытые решения до реализации» закрыт так:

- владелец заголовка — tracing; error-handling — потребитель в joint-режиме;
- ingress/gateway strip — deployment precondition для `accept-inbound=true`,
  не Java-инвариант;
- грамматика — `[A-Za-z0-9_-]{1,128}`, без нормализации, без `.`/`:`;
- невалидный вход — drop; generate только в legacy standalone;
- дубликаты — reject по всем значениям заголовка, включая comma-joined и
  регистро-разные имена;
- приоритет над локальным scope — inbound base, затем Servlet
  `openCorrelationScope` / WebFlux `ReactiveCorrelationOperations`, затем restore;
- echo в ответе — всегда выключен в joint, без toggle;
- единственная точка парсинга — tracing inbound filters;
- владелец outbound-инъекции — explicit app-plane port, не agent и не baggage;
- порядок стартеров — tracing identity boundary раньше error-handling
  filter/handler; joint определяется bean `tracingRequestContextSupplier`.
