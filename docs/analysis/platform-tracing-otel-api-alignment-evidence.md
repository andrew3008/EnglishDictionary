# Platform Tracing: OTel API Alignment — PA-0 Evidence

> Date: 2026-07-23  
> Branch: `feature/otel-api-alignment-pa0-census`  
> Base: `master@9b7f573`  
> Worktree: `E:\Platform_Traces_Otel_Api_Alignment`

## 1. Результат PA-0

Slice PA-0 завершён. Production moves **не выполнялись**. Закрыты census, move manifest, ADR, SAFE-BRIDGE-ABI fixture, baseline E2E.

## 2. PA-0 exit block

| Checkpoint | Status |
|---|---|
| SAFE-BRIDGE-ABI CLOSED | PASS |
| PROPOSED-ABI FIXTURE POSITIVE COMPILE GREEN | PASS |
| PROPOSED-ABI FIXTURE BYPASS NEGATIVE COMPILE GREEN | PASS |
| BRIDGE CONSTRUCTION ABI FROZEN | PASS (ADR) |
| BRIDGE OPERATIONAL ABI FROZEN | PASS (ADR) |
| PUBLIC GOVERNANCE BYPASS ABSENT | PASS |
| OPERATION-GOVERNANCE APPROVED: OPTION A | PASS |
| NARROW BUILDER DEPENDENCY GRAPH APPROVED | PASS (ADR) |
| PREFERRED COMPOSITION APPROVED | PASS (ADR) |
| EXACT 63-FQCN SET FROZEN | PASS (ADR) |
| OLD-TO-NEW MOVE MANIFEST APPROVED | PASS |
| SPAN DEPENDENCY GRAPH APPROVED | PASS (ADR) |
| BASELINE E2E GREEN | PASS (0/0/0) |
| PA-1 UNBLOCKED | YES |

## 3. Census (verified)

| Metric | Value |
|---|---|
| `otel.manual` main files | 14 |
| `otel.manual` test files | 16 |
| Cross-module `otel.manual` imports | 0 |
| Public allowlist (baseline) | 65 FQCN |
| Target allowlist (PA-1) | 63 FQCN |
| Accidental public types to demote | 4 |
| `otel.exception` | KEEP |

## 4. OPERATION-GOVERNANCE Option A — delta evidence

| Path | Baseline behavior | PA-1 intent |
|---|---|---|
| `fromSpec` | governance via `SpanSpecGovernance` | unchanged pipeline via `DefaultSpanSpecFactory` |
| semantic builders | governance via `SemanticSpanSpecs.build(..., policy, ...)` | builder → spec factory → governance once |
| INTERNAL operation | `OperationSpanSpecs.from` **без** `AttributePolicy` | **fix:** uniform governance через spec factory |

Approved intentional behavior change for operation path (Option A).

## 5. SAFE-BRIDGE-ABI verification

Root task: `pa0ProposedAbiConsumerVerify`

- Fixture: `platform-tracing-otel` source set `pa0ProposedAbi`, jar `pa0ProposedAbiJar`
- Isolated repo: `build/pa0-proposed-abi-maven-repository` (BOM + API + OTEL)
- Positive consumer: `gradle/pa0-proposed-abi-consumer` — operational ABI без runtime/policy на вызове
- Negative consumer: `executionFromGovernedSpec` — **expected compile failure**

## 6. ArchUnit fix

`TracingImplementationArchTest.runtimeMustNotDependOnRootFacade` — regex обновлён с vacuous `core.*` на post-CP-3 `otel.facade` / `otel.propagation` FQCN.

## 7. Verification matrix

| Gate | Result |
|---|---|
| `:platform-tracing-api:test` | PASS |
| `:platform-tracing-otel:test` | PASS |
| `:platform-tracing-spring-boot-autoconfigure:test` | PASS |
| `:platform-tracing-autoconfigure-webmvc:test` | PASS |
| `:platform-tracing-autoconfigure-webflux:test` | PASS |
| `:platform-tracing-otel-javaagent-extension:test` | PASS |
| `pr0StarterDependencySmoke` | PASS |
| `pr1ModuleTaxonomyVerify` | PASS |
| `pr4ArchitectureFitnessVerify` | PASS |
| `cp3LegacyPackageVerify` | PASS |
| `pa0ProposedAbiConsumerVerify` | PASS |
| `build` | PASS |

## 8. Baseline E2E

```powershell
$env:DOCKER_HOST='tcp://192.168.100.70:2375'
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --rerun-tasks --no-daemon
```

| Metric | Value |
|---|---|
| tests | 56 |
| failures | 0 |
| errors | 0 |
| skipped | 0 |

## 9. Deliverables

- [ADR-otel-api-package-alignment.md](../decisions/ADR-otel-api-package-alignment.md)
- [platform-tracing-otel-api-alignment-move-manifest.md](./platform-tracing-otel-api-alignment-move-manifest.md)
- Proposed-ABI fixture + consumer (`gradle/pa0-proposed-abi-consumer/`)

## 10. Release gates (unchanged)

```text
RG-IDENTITY-TRUST OPEN
RG-CONTROLLED-AGENT OPEN
PRODUCTION ROLLOUT FORBIDDEN
```
