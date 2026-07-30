# P6.5 residual — public/partial control-protocol deletion

**Status:** COMPLETED  
**Date:** 2026-07-29  
**Task:** atomically delete orphaned `api.control.protocol` / `otel.control.protocol` packages and companion harnesses

## Decision

Public Map-wire control protocol types under `platform-tracing-api` and the orphaned `platform-tracing-otel` control-protocol handler stack are deleted without dual paths or aliases. Runtime mutation ownership remains private (`AcceptedPolicyAccess` / private applySnapshot / platform read projection / OpenType codecs under `javaagent.control`).

## Deleted production

| Path | Notes |
|---|---|
| `platform-tracing-api/.../api/control/protocol/*.java` | Entire public protocol package (14 types) |
| `platform-tracing-otel/.../otel/control/protocol/*.java` | Entire orphaned handler/validator package (8 types) |

## Deleted tests / harness

| Path | Notes |
|---|---|
| `platform-tracing-api/.../api/control/protocol/**` | Unit + ArchUnit purity tests |
| `platform-tracing-otel/.../otel/control/protocol/**` | Handler/validator unit tests |
| `SamplingControlClientWireContractTest.java` | Autoconfigure wire contract |
| `e2e/.../wire/WireRoundTripInProcessTest.java` | In-process Map wire |
| `e2e/.../wire/MapWireRoundTripMain.java` | Child-JVM main |
| `e2e/.../wire/MapWireRoundTripE2ETest.java` | Agent E2E |
| `platform-tracing-e2e-tests/src/jmxWireExtension/**` | Test-only OTel extension harness |

## Companion edits

| File | Change |
|---|---|
| `platform-tracing-e2e-tests/build.gradle` | Removed `jmxWireExtension` source set, deps, `testJmxWireExtensionJar`, smoke property |
| `ArchitectureFitnessArchRules.java` | Retired FF-01/FF-02/protocol naming rules targeting deleted package; kept FF-09a |
| `docs/contracts/public-contract-inventory.yaml` | Removed `tracing-control-protocol` entry |
| `docs/contracts/semantic-contract-map.yaml` | Removed `tracing-control-protocol` concept |
| `PublicContractInventoryContractTest` / `SemanticContractMapContractTest` | Dropped required-id |
| `TracingConfigurationInventoryGeneratorTest` | Removed `"control protocol"` mutationPath strings |

## Kept (explicit)

- `AcceptedPolicyAccess` (+ private `applySnapshot` / `readAcceptedComplete`)
- `PrivateControlMBeanServerOwner`
- `PlatformControlReadProjection`
- OpenType codecs under `javaagent.control`

## Verification (executed 2026-07-29)

| Gate | Result |
|---|---|
| `:platform-tracing-api:compileJava` / `compileTestJava` (clean, `--no-build-cache`) | PASS |
| `:platform-tracing-otel:compileJava` / `compileTestJava` (clean, `--no-build-cache`) | PASS |
| `:platform-tracing-api:test --tests "*PublicContract*" --tests "*SemanticContract*"` | PASS (`PublicContractInventoryContractTest` 6/0/0, `SemanticContractMapContractTest` 4/0/0) |
| `:platform-tracing-test:compileJava` / `compileTestJava` | PASS |
| `:platform-tracing-e2e-tests:compileJava` / `compileTestJava` (clean) | PASS |
| `:platform-tracing-spring-boot-autoconfigure:compileTestJava` | PASS |
| `:platform-tracing-spring-boot-autoconfigure:test --tests "*ProductionControlPlaneNotMigratedArchTest*"` | PASS |

Post-clean class scan: no `TracingControlProtocol*` / `RuntimePolicyControl*` classes under api/otel `build/classes`.

## Residual risks

- Historical docs under `docs/architecture/` and `docs/analysis/` still mention the deleted protocol/harness; not updated in this slice.
- `SafeBoundaryArchTest` comment still references `jmxWireExtension` path as historical context.
