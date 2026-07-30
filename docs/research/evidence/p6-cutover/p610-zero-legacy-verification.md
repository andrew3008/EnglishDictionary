# P6.10 — Zero-legacy verification

**Date:** 2026-07-30  
**Task:** `p6-zero-legacy-verification`  
**Verdict:** PASS (production zero-legacy + P5.9 Docker precondition)

Supersedes `p610-zero-legacy-verification-partial.md` (2026-07-29 PARTIAL).

## Preconditions closed

| Gate | Result | Evidence |
|---|---|---|
| P5.9 verification package | PASS | `docs/research/evidence/p5-execution/p59-p6cutover-rerun/verdict.md` |
| Docker E2E (`-PrunE2e`) | PASS | 55 tests, BUILD SUCCESSFUL |
| M10 / M10c / M10d | PASS | summary.json under p59-p6cutover-rerun |

## Production scans (2026-07-30)

Roots: `platform-tracing-{api,otel,otel-javaagent-extension,spring-boot-autoconfigure,spring-boot-starter,spring-boot-starter-servlet,spring-boot-starter-webflux,perf-tests}/src/main`

Machine results: `p610-scan-results.json` · log: `p610-scans-2026-07-30.log`

| Check | Hits | Notes |
|---|---:|---|
| `RuntimeConfigApplier` | 0 | |
| `updateScrubbingPolicy` | 0 | |
| `ScrubbingPolicyHolder` | 0 | |
| `setExportEnabled` | 0 | |
| `/perf/admin` | 0 | |
| `api.control.protocol` / `otel.control.protocol` packages | 0 | deleted P6.5 |
| `platform.tracing.scrubbing.enabled` / `.built-in-rules` | 0 | |
| `platform.tracing.sampling.drop-paths` | 0 | |
| `platform.tracing.actuator.mutation-enabled` | 0 | |
| `@WriteOperation` | 0 | |
| dual-read / compatibility alias prose in main | 0 | |
| `PlatformControlProtocolMBean` | 1 | javadoc migration note only in `PlatformTracingJmxRegistrar` |

## Executable proofs

| Proof | Result | Log |
|---|---|---|
| `P6LegacyNameFailFastTest` (14 banned names) | PASS | `p610-failfast-actuator.log` |
| `P64ActuatorZeroWriteProofTest` | PASS | same |
| Platform domain JMX mutations (S07) | 0 | post-P6.6 evidence |
| Deployable banned-name sync (P6.9) | recorded | `p69-deployment-manifest-cutover.md` |

## Must-prove checklist (§20 P6.10)

```text
legacy production readers = 0          PASS
legacy production writers = 0          PASS
aliases/dual reads = 0                 PASS
RuntimeConfigApplier refs = 0          PASS (outside migration evidence)
legacy generic Actuator writers = 0    PASS
platform JMX semantic mutations = 0    PASS
production/shared HTTP writes = 0      PASS (repo-scoped + P64)
public control DTOs = 0                PASS
old names in deployable manifests = 0  PASS (P6.9)
supported writer paths = private applySnapshot by authority adapters  PASS
```

## Residual (does not reopen P6.10)

1. Machine `cutover-manifest.json` per-row `status` strings may still say `PENDING_CUTOVER` for historically deferred IDs — authoritative disposition is `cutover-manifest.md` + `p61-property-disposition-refresh.md` (17/17 APPLIED).
2. Formal **P6 PASS** still requires `p6-coherent-cutover` (P6.11) command package on this train.
3. Optional unused `VersionedStateHolder` cleanup remains non-blocking.

## Verdict

**P6.10 PASS.** Zero-legacy production scans and fail-fast proofs are green with P5.9 Docker precondition satisfied.
