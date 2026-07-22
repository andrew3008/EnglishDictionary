# ADR: governance публичного API Platform Tracing

| Поле | Значение |
|---|---|
| Статус | **Accepted** |
| Основание | Slices C1-C3, M и I |

## Решение

Public API определяется фактическим allowlist `platform-tracing-api` и ABI snapshots, а не наличием `public` type в implementation artifact.

Application-facing root `TraceOperations` содержит:

- `traceContext()`;
- `spans()`;
- `openCorrelationScope(String)`;
- `withCorrelationId(String, Runnable)`;
- `withCorrelationId(String, ThrowingSupplier<T>)`.

Последние три метода - intentional Slice M ABI delta, утверждённая CP-1 R2. CP-C2 отдельно владеет ABI propagation port. Reactive identity API принадлежит WebFlux module и не переносит Reactor в `platform-tracing-api`.

Запрещено публиковать identity storage/binder/accessor implementation, OTel Context/Span/reader, Spring/Reactor types, schema/validator introspection control protocol, `SamplingPolicyRule`, enrichment implementation или static discovery holder.

`platform-tracing-otel` содержит межмодульные public implementation types, но они не являются автоматически application API или extension SPI. Его exact surface защищён отдельным allowlist snapshot.

## Approval rule

Новый public type или signature требует architecture decision, указанного ABI owner, intentional snapshot diff, negative dependency review и обновления consumer documentation. Compatibility aliases и deprecated shims не добавляются до production без отдельного решения.

## Verification

- API purity and taxonomy ArchUnit rules;
- `PublicSurfaceAllowlistTest`;
- `AbiSnapshotTest` и `platform-tracing-api-otel.txt`;
- control protocol exact-surface tests;
- C3 external published-consumer verification.

## Связанные ADR

- [TraceOperations v3 API](./ADR-platform-tracing-v3-public-api.md)
- [CP-C2](./ADR-cp-c2-otel-free-outbound-propagation-port.md)
- [identity model](./ADR-identity-model-trace-request-correlation.md)
