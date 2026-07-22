# Platform Tracing: каталог архитектурных решений

Этот каталог указывает действующие источники истины после завершения Slices E-M. Наличие ADR в каталоге не означает готовность к production rollout.

## Канонические решения

| Область | ADR | Статус |
|---|---|---|
| Итоговая топология | [ADR-platform-tracing-final-architecture](./ADR-platform-tracing-final-architecture.md) | Accepted |
| OTel-free API и propagation port | [ADR-api-otel-free-facade](./ADR-api-otel-free-facade.md) | Accepted |
| Явная композиция | [ADR-explicit-composition-no-static-sl](./ADR-explicit-composition-no-static-sl.md) | Accepted |
| Runtime ownership | [ADR-sdk-mode-detection](./ADR-sdk-mode-detection.md) | Accepted |
| Identity model | [ADR-identity-model-trace-request-correlation](./ADR-identity-model-trace-request-correlation.md) | Accepted, release gate open |
| Sampling policy | [ADR-cp-2-sampling-policy-extension-contract](./ADR-cp-2-sampling-policy-extension-contract.md) | Accepted, sealed internal |
| OTel implementation module | [ADR-platform-tracing-otel-module-identity](./ADR-platform-tracing-otel-module-identity.md) | Accepted |
| Public API governance | [ADR-public-api-allowlist](./ADR-public-api-allowlist.md) | Accepted |
| Control protocol | [ADR-control-protocol-version-model](./ADR-control-protocol-version-model.md) | Accepted |

Остальные Accepted ADR уточняют отдельные контракты, включая lifecycle, propagation, semconv, exporter и Micrometer boundaries. При противоречии со старым target-architecture документом применяется [финальная архитектура](./ADR-platform-tracing-final-architecture.md).

## Superseded и historical

- [ADR-platform-tracing-target-architecture](./ADR-platform-tracing-target-architecture.md) и [ADR-platform-tracing-clean-core-hybrid](./ADR-platform-tracing-clean-core-hybrid.md) сохранены как история выбора topology.
- [ADR-request-id-correlation-id](./ADR-request-id-correlation-id.md) заменён финальной трёхуровневой identity model.
- [ADR-request-id-equals-trace-id](./ADR-request-id-equals-trace-id.md) остаётся superseded; alias `requestId = traceId` запрещён.
- [ADR-platform-tracing-otel-api-exposure](./ADR-platform-tracing-otel-api-exposure.md) сохраняет историческое обоснование implementation-module exposure; граница публичного facade теперь задаётся ADR OTel-free API.

## Release gates

- [RG-IDENTITY-TRUST](../architecture/rg-identity-trust-release-gate.md): **OPEN**.
- [RG-CONTROLLED-AGENT](../architecture/rg-controlled-agent-release-gate.md): **OPEN**.
- **PRODUCTION ROLLOUT FORBIDDEN** до закрытия обоих gate.
