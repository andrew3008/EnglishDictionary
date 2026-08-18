# Trace pipeline SPI attestation

Дата: 2026-08-18.
Статус: ACCEPTED.

## Ownership

`TracePipelineSpiAttestation` — runtime security invariant:

```text
неизвестный ACP binary name отвергается;
ровно один platform provider с order=100;
SdkTracerProviderConfigurer отсутствует.
```

Полнота upstream inventory — packaging/supply-chain invariant controlled distribution:

```text
A_upstream == B_pin == C_upstream
```

`SafeSpanExporter` остаётся export boundary. Attestation не ходит по собранному `SdkTracerProvider`.

## Trust source

Reviewed baseline:

[`pins/opentelemetry-javaagent-2.30.0-acp-pin.json`](pins/opentelemetry-javaagent-2.30.0-acp-pin.json)

`upstreamSha256` берётся из published Maven Central checksum, не из первого локального resolve.
Обычный build (`verifyUpstreamAcpPin`) сравнивает resolved bytes с baseline и только затем
генерирует Java `PinnedUpstreamAcpProviders`. Runtime читает только этот generated class.
JSON не упаковывается в production JAR.

Update: `-PexpectedUpstreamAgentSha256=<hex>` + `generateUpstreamAcpPinCandidate`.
Флаг не записывает `SHA256(localJar)` как trust anchor.

## Режимы

- default: Variant B (`discovered ⊆ pin`).
- `CHARACTERIZE_EVIDENCE`: публикует `C_upstream`, не сравнивает с pin. Не release gate.
- `EXACT_EVIDENCE`: Variant B + `C_upstream == NAMES`. Packaged qualification.

Marker `PTA-BOOT-010` пишется через `PlatformAgentLogger.error`. Требуется `EmitResult.EMITTED`.
Ровно один marker. `exitCode == 0` недостаточен.

## Residual risk

Attestation отвергает неизвестные binary names. Origin байткода provider не доказывается.
Same-FQCN overlay — follow-up `provider-origin attestation`.

## Loader contract

[`pins/opentelemetry-javaagent-2.30.0-loader-contract.md`](pins/opentelemetry-javaagent-2.30.0-loader-contract.md)

OTel Agent 2.30.0: один descriptor `inst/META-INF/services/<ACP>`.
`AgentClassLoader` читает `inst/`. Nested JAR нет. `META-INF/versions` не применяется к SPI.
