# ADR: MDC `platform.remote.service` в WebFlux

## Статус

Принято (Wave 1, PR-GAP-1).

## Контекст

Платформа записывает логическое имя upstream-сервиса в span-атрибут `platform.remote.service`
и дублирует его в MDC (`TracingMdcKeys.REMOTE_SERVICE`) для error-handling:
`PlatformRemoteServiceNameProvider` → поле `domain` в DTO ошибки.

Запись MDC выполняется в `EnrichingSpanProcessor.onEnding` через `RemoteServiceMdc`
на потоке, где вызывается `span.end()`.

## Решение

| Стек | Гарантия MDC `platform.remote.service` |
|------|----------------------------------------|
| **WebMVC + blocking HTTP/gRPC client** | **Гарантирован** — CLIENT-span завершается на request-thread до `@ControllerAdvice` |
| **WebFlux + blocking client на request-thread** | **Гарантирован** — тот же механизм ThreadLocal MDC |
| **WebFlux + async client (Reactor scheduler)** | **Частично** — MDC не гарантирован; fallback: `RemoteServiceTraceMirror` по traceId + Micrometer context propagation |

Primary contract для всех стеков: span-атрибут `platform.remote.service` на ERROR CLIENT-span.

MDC — дополнительный канал для blocking Servlet/WebMVC и синхронных вызовов в WebFlux.

Очистка MDC: `RemoteServiceMdc.clear()` в `finally` / `doFinally` outermost response-фильтров.

## Последствия

- Error-handling в WebFlux при async upstream-вызовах может получить `Optional.empty()` из
  `PlatformRemoteServiceNameProvider` — это ожидаемо; используется sentinel `DOMAIN_UNSPECIFIED`.
- Reactor Context mirror для `platform.remote.service` — **реализовано (Wave 4, G-02b)**:
  - `RemoteServiceTraceMirror` — trace-scoped fallback для WebFlux error-handling;
  - `RemoteServiceContextPropagation` — Micrometer `ThreadLocalAccessor` для scheduler switch;
  - `RemoteServiceReactorContext` — явный `contextWrite` для ручного проброса в reactive chain.

## Альтернативы

- Отдельный `RemoteServiceMdcSpanProcessor` в autoconfigure — отклонено: otel-extension не
  должен зависеть от Spring; MDC-мост вынесен в `platform-tracing-api`.
