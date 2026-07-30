# P6.5 / P6.6 — Platform Control + domain JMX mutations

**Status:** COMPLETED (Agent platform surface)  
**Date:** 2026-07-29  
**Tasks:** `p6-platform-read-projection-activation`, `p6-delete-partial-control-protocol` (Agent), `p6-delete-domain-jmx-writes`

## P6.2/P6.6 platform Control projection

- `PrivateControlMBeanServerOwner` registers E7 `PlatformControlReadProjection` on the **platform** MBeanServer under the Control ObjectName.
- Private server keeps sole `applySnapshot` + `readAcceptedComplete`.
- `PlatformTracingJmxRegistrar` no longer registers `PlatformControlProtocolMBean`.

## P6.5 Agent deletions

| Deleted | Notes |
|---|---|
| `PlatformControlProtocolMBean` / `MXBean` | platform `applyPolicy` gone |
| `JmxRuntimePolicyApplier` | orphaned after registrar cutover |
| `ReadAppliedStateHandler` | protocol-only |
| `RuntimeControlMutationWireRoundTripE2ETest` | exercised deleted wire |

**Residual:** public API control-protocol types under `platform-tracing-api` / `platform-tracing-otel` remain for a later coherent public-contract deletion slice (plan P6.5/P7).

## P6.6 domain MBeans

Platform Sampling / Diagnostics / Export / Scrubbing / Validation MBeans are **read-only**:

- writable attributes → 0 (setters removed)
- mutating operations → 0 (`update*` / `resetSamplerCounters` removed)
- DELETE_OBSOLETE_READ: sampling DropPathPrefixes / ForceRecordValues attributes removed

Spring `PlatformTracingJmxClient` mutation methods deleted.

## Verification (executed)

| Gate | Result |
|---|---|
| agent + Spring `compileJava` / `compileTestJava` | PASS |
| `PrivateControlBoundaryTest` | PASS |
| `PlatformTracingJmxRegistrarTest` | PASS |
| `DomainMBeanJmxComplianceTest` | PASS |
| `jmxManagementContractVerify` (baseline regenerated: 0 mutating) | PASS (per test rewrite agent) |
| `P64ActuatorWriteAbsenceTest` / `ProductionControlPlaneNotMigratedArchTest` | PASS |

## Blockers noted

- Formal P6 PASS still blocked on pending `p5-verification-package` (P5.9).
- P6.7 ScrubbingPolicyHolder + Spring scrubbing binding still open.
