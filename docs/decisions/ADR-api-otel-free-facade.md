# ADR: OTel-free facade и propagation port

| Поле | Значение |
|---|---|
| Статус | **Accepted** |
| Основание | CP-C2, Slices C1-C3, I и M |

## Решение

`platform-tracing-api` не зависит от `io.opentelemetry.*`, Reactor, Spring или implementation modules и не публикует эти типы в ABI. `SpanFactory` не возвращает OTel reader/context types.

Outbound web integration использует узкий OTel-free port из [CP-C2 ADR](./ADR-cp-c2-otel-free-outbound-propagation-port.md): typed `OutboundPropagationRequest`, immutable `OutboundPropagationHeaders` и `PlatformContextPropagation`. Generic `Object` carrier, untyped `Map` API и clone `TextMapSetter` запрещены.

OTel-specific reader, current-context access и carrier adaptation принадлежат `platform-tracing-otel` и composition root. Public API не выполняет static discovery через `ServiceLoader` или `ClassLoader`.

## Последствия

- `opentelemetry-api` может быть намеренно виден через infrastructure modules, но не через metadata или ABI `platform-tracing-api`.
- WebMVC/WebFlux adapters зависят от approved port, а не от OTel `Context`.
- Любая новая public signature требует ABI review и обновления snapshot/negative dependency gates.

## Verification

`API_MAIN_NO_OTEL_API`, `API_MAIN_NO_OTEL_OR_FRAMEWORK_TYPES`, `API_NO_SERVICE_LOADER`, C3 published-consumer fixture и API protocol purity tests.

## Связанные ADR

- [CP-C2 exact ABI](./ADR-cp-c2-otel-free-outbound-propagation-port.md)
- [explicit composition](./ADR-explicit-composition-no-static-sl.md)
- [public API allowlist](./ADR-public-api-allowlist.md)
