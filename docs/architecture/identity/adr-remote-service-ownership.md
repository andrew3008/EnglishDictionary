# ADR: владение remote-service и error DTO `domain`

Статус: ACCEPTED
Дата: 2026-08-21

## Решение

`platform.remote.service` и error DTO `domain` — два независимых потока с разными владельцами.

`domain` в error DTO владеет **ErrorHandling**. Источник — поле `domain` конверта
upstream-ответа (`UpstreamResponseReader` → `UpstreamIdentityEnricher`). Если конверт
не дал безопасного токена, backfill — `spring.application.name`. Safety-net `internal`
остаётся отдельным fatal-путём ErrorHandling и не является телеметрией Tracing.

`platform.remote.service` владеет **Tracing Java Agent**. Это атрибут ERROR CLIENT span'а:
какой peer уронил исходящий вызов. Единственный писатель — `EnrichingSpanProcessor`
в `platform-tracing-otel-javaagent-extension`. Приоритет источника:
`peer.service` → `rpc.service` → `server.address`; IP-литерал в `server.address`
отбрасывается. Логическое имя появляется только при peer-mapping уровня агента.
В SDK-only развёртывании атрибута нет: барьер
`AUTOCONFIGURE_MAIN_NO_OTEL_EXTENSION_IMPL` запрещает application classloader видеть
агентский mapping.

Связки «атрибут → JSON `domain`» и «атрибут → ключ MDC» не существуют и не вводятся.

## Канонические поверхности

```text
span  platform.remote.service  = EnrichingSpanProcessor on ERROR CLIENT
MDC   ключи                    = только TracingMdcKeys
                                      (traceId, spanId, traceFlags, requestId, correlationId)
JSON  domain                   = upstream envelope, иначе spring.application.name
snapshot RequestTraceContextSnapshot = requestId, correlationId, traceId, spanId
```

`RequestTraceContextSnapshot` и `ActiveTraceContextView` не расширяются ради remote-service.
Error-handling читает из снимка только `correlationId`, `traceId`, `spanId`.

## Отклонённые альтернативы

### MDC-проекция `platform.remote.service`

Потребовала бы scoped ThreadLocal, реактивный мост через
`ReactiveIdentityContextPropagation` и дисциплину очистки. `processorCallbacksDoNotDependOnMdc`
уже запрещает span-processor'ам владеть MDC. Значение уже видно в span'е и связывается
с логами через `traceId` / `spanId`. Скрытое состояние ради дубля запрещено.

### Поле `remoteService` в `RequestTraceContextSnapshot`

Ломающее изменение публичного record без потребителя: ErrorHandling не читает remote-service
для `domain`. Расширять snapshot «на всякий случай» запрещено.

### Собственный host → logical-name mapping на SDK-стороне

Второй источник истины рядом с агентским `peer.service`. SDK-only и agent-развёртывания
давали бы разные имена. Барьер класслоадеров не позволяет «подсмотреть» агентский mapping.
Дубликат mapping не вводится.

## Последствия

Документация `PlatformAttributes.PLATFORM_REMOTE_SERVICE` и портальный `span_model`
описывают только телеметрию. Квалификационный контур наблюдает два независимых факта —
распространение upstream-конверта и атрибут ERROR CLIENT — и не трактует их как причинную
связь. Fitness-функции запрещают новым классам читать константу как MDC- или domain-контракт.
