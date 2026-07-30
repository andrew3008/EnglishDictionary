# P6.4 / P6.10 — Production Actuator zero-write proof

**Status:** COMPLETED (in-process Spring; Docker matrix PARTIAL)  
**Date:** 2026-07-29  
**Plan task:** `p6-production-actuator-zero-write-proof`  
**Sibling:** [p64-actuator-write-deletion.md](./p64-actuator-write-deletion.md) (unit deletion)

## Goal

Prove physical absence of the legacy Actuator write path on production/shared surfaces — not merely a business reject behind a still-registered `@WriteOperation`.

Approved DEV_ACTUATOR typed leaf commands under `ACTUATOR+LOCAL` (`/management/tracingControl/...`) remain; this proof only shows the deleted generic write is gone.

## Surfaces under proof

| Surface | Expected |
|---|---|
| `actuator.TracingActuatorEndpoint` (`/actuator/tracing`) | Read-only; zero `@WriteOperation` |
| `configuration.actuator.TracingActuatorEndpoint` (`tracingPolicy`) | Read-only; zero `@WriteOperation` |
| Legacy `POST/PUT /actuator/tracing/{property}/{value}` | Physically absent → HTTP 404/405 |
| `SpringActuatorTracingPolicyAdapter` on HELM / CONFIG_SERVER / DISABLED | Bean absent |
| DEV_ACTUATOR typed leaves (`ACTUATOR+LOCAL`) | Preserved |

## Executable evidence

| Gate | What it proves | Result |
|---|---|---|
| `P64ActuatorWriteAbsenceTest` | Unit/source: zero `@WriteOperation`, no `mutation-enabled` binding | PASS |
| `P64ActuatorZeroWriteProofTest#helmProductionContext_registersReadOnlyTracingEndpoint` | Minimal HELM/PRODUCTION context: endpoint bean present, no write adapter/controller | PASS |
| `P64ActuatorZeroWriteProofTest#productionSharedPostures_noWriteAdapterBean` | HELM + CONFIG_SERVER × LOCAL/SHARED/PRODUCTION: no write adapter | PASS |
| `P64ActuatorZeroWriteProofTest#helmDisabled_noWriteSurfaceBeans` | HELM+DISABLED: no write adapter/controller | PASS |
| `P64ActuatorZeroWriteProofTest#helmProduction_legacyWriteHttpRoutesAbsent` | Real servlet boot: GET `/actuator/tracing` 2xx; PathMappedEndpoints WRITE=0; legacy POST/PUT → 404/405 (not business reject) | PASS |
| `P64ActuatorZeroWriteProofTest#actuatorLocal_legacyWriteGone_typedLeafRemains` | ACTUATOR+LOCAL: legacy write 404/405; typed `/management/tracingControl/sampling/enabled` still 2xx | PASS |
| `P56AuthorityPostureMatrixTest` (+ `helmDisabled_actuatorWriteAdapterPhysicallyAbsent`) | Authority posture matrix zero-write | PASS |

## Command

```powershell
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --tests "*P64*" --tests "*Actuator*" --tests "*P56Authority*" --no-daemon
```

## PARTIAL / residual

| Gap | Why |
|---|---|
| Full Docker / multi-node HTTP matrix | Not required for this residual; in-process servlet + MockMvc-equivalent RestTemplate proves route absence without Docker |
| External k8s/ingress rewrite of `/actuator/**` | Environment-specific; out of repository scope |
| Inventory regenerate after mutation-enabled removal | Sibling residual from deletion evidence |

## Verdict

**PASS** for repository-scoped physical absence (descriptor + bean + in-process HTTP).  
**PARTIAL** only for a full Docker/deployed HTTP matrix across all postures.
