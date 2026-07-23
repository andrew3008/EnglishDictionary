# Platform Tracing: OTel API Alignment — PA-1 Evidence

> Date: 2026-07-23  
> Branch: `feature/otel-api-alignment-pa1-span`  
> Base: merged PA-0 (`02a9a93`)  
> Worktree: `E:\Platform_Traces_Otel_Api_Alignment`

## 1. Результат PA-1

Slice PA-1 завершён: production moves `otel.manual*` / `otel.enrichment` / `otel.naming` → symmetric `otel.span.*`, preferred composition, builder isolation, exact **63-FQCN** allowlist.

## 2. Move manifest compliance

| Criterion | Result |
|---|---|
| Planned moves = actual moves | PASS |
| Unexpected moves | 0 |
| `otel.manual` in active sources | 0 (`otelManualPackageVerify`) |
| `otel.enrichment` / `otel.naming` in active sources | 0 |

## 3. Architecture

**Preferred composition (production):**

```text
DefaultSpanFactory
  -> DefaultSpanSpecFactory(runtime, policy)
  -> DefaultSpanBuilderFactory(specFactory, traceparentReader)
```

**Builder isolation:** concrete builders в `otel.span.builder` не хранят `TracingRuntime` / `AttributePolicy`; lifecycle через `DefaultSpanSpecFactory`.

**OPERATION-GOVERNANCE Option A:** INTERNAL operation path проходит governed pipeline (`fromBuilderRawSpec` → `SpanSpecGovernance`).

**Deleted:** `ScopedExecution` → inlined `SpanLifecycle` в `otel.span.spec`.

**Removed:** PA-0 spike `pa0ProposedAbi` (заменён production-кодом).

## 4. Public surface (63 FQCN)

Removed 6 / added 4 per ADR. Gate: `PublicSurfaceAllowlistTest` exact set equality.

## 5. Verification gates

| Gate | Result |
|---|---|
| `:platform-tracing-otel:test` | PASS |
| `SpanImplementationTaxonomyArchTest` | PASS |
| `otelManualPackageVerify` | PASS |
| `pa1PublishedArtifactConsumerVerify` | PASS (provenance JAR + SHA-256 + commit SHA) |
| `pr4ArchitectureFitnessVerify` | PASS |
| `cp3LegacyPackageVerify` | PASS |
| Full module test matrix | PASS |
| E2E `-PrunE2e` | see §6 |

## 6. PA-1 published-artifact provenance

Task: `pa1PublishedArtifactConsumerVerify`

- Isolated repo: `build/pa1-published-artifact-maven-repository`
- Consumer: `gradle/pa1-published-artifact-consumer`
- Positive + negative compile against **same production JAR**
- No fallback to Maven Local / remote cache

## 7. E2E

```powershell
$env:DOCKER_HOST='tcp://192.168.100.70:2375'
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --rerun-tasks --no-daemon
```

## 8. PA-2 next

- `api.context` atomic moves (`CorrelationScope`, `ActiveTraceContextView`)
- Regression gates + mandatory E2E
