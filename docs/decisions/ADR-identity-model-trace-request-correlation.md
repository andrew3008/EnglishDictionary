# ADR: identity model traceId, requestId и correlationId

| Поле | Значение |
|---|---|
| Статус | **Accepted, RG-IDENTITY-TRUST OPEN** |
| Основание | CP-1 APPROVED (R2), Slice M |
| Заменяет | [ADR-request-id-correlation-id](./ADR-request-id-correlation-id.md) |

## Решение

- `traceId` - W3C/OpenTelemetry identity распределённого trace.
- `requestId` - техническая edge-stable identity цепочки downstream request/message execution.
- `correlationId` - identity бизнес-процесса или workflow, которая может переживать запросы, сообщения, retry, job и несколько trace.

Aliasing `correlationId` к `traceId` или `requestId` запрещён. Request и business correlation имеют разные lifetime и source of truth.

Authoritative identity хранится per execution в platform-owned context. Headers, baggage, MDC и span attributes являются проекциями. Shared mutable singleton, global static current value и ThreadLocal-only WebFlux model запрещены. RequestId не читается из baggage.

## Проекции

| Surface | Ключи |
|---|---|
| MDC | `traceId`, `spanId`, `traceFlags`, `requestId`, `correlationId` |
| Span attributes | `platform.request_id`, `platform.correlation_id` |
| Baggage business correlation | `platform.correlation.id` |
| HTTP boundary | `X-Request-Id`; optional approved bridge `X-Correlation-ID` |

RequestId и correlationId имеют высокую cardinality и запрещены как metric dimensions.

Span attributes settable с last-write-wins, но non-removable, поэтому не являются reversibly scoped. Approved birth-time projection фиксирует identity на создаваемом span. Late correlation assignment изменяет logical context, baggage, MDC и будущие child spans, но не переписывает уже созданный span.

## Transport lifecycle

- WebMVC: request scope открывается и закрывается filter boundary.
- WebFlux: `ReactiveCorrelationOperations` использует Reactor Context и per-subscription storage; ThreadLocal-only propagation запрещена.
- Kafka producer: outbound port проецирует текущие approved identity values в headers.
- Kafka consumer: requestId привязывается к listener execution; untrusted correlation baggage удаляется до consumer processing/span creation.
- Retry/redelivery сохраняют business correlation только через approved outbound projection; новый execution не разделяет mutable request state.
- Отдельный scheduler identity adapter в master не доказан и не заявляется реализованным.

## Trust boundary

F0 fail-closed: ingress считается untrusted. `platform.correlation.id` очищается pre-span/pre-consumer; hostname, IP, topic или произвольный header не доказывают trust. Trusted inbound business correlation не включается до закрытия [RG-IDENTITY-TRUST](../architecture/rg-identity-trust-release-gate.md).

## API

Synchronous API: `openCorrelationScope`, две overload `withCorrelationId` и read-only `traceContext()`. Reactive API находится в WebFlux module как `ReactiveCorrelationOperations`. Internal binder/storage types не входят в public API.

## Verification

CP-1 packet, Slice M unit/context/concurrency tests, controlled-Agent WebMVC/WebFlux/Kafka E2E, API negative surface tests и identity release gate.

## Последствия

`RG-IDENTITY-TRUST OPEN`, `RG-CONTROLLED-AGENT OPEN`, **PRODUCTION ROLLOUT FORBIDDEN**.
