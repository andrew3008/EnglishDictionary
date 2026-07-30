# P6.11 — Coherent cutover gate

**Date:** 2026-07-30
**Verdict:** PASS

## Policy

Docker E2E and M10/M10c/M10d are **not re-executed** for this gate. They already PASS under P5.9 (docs/research/evidence/p5-execution/p59-p6cutover-rerun/). Further E2E reruns are reserved for production-code changes that touch agent packaging, e2e fixtures, or private-control boundaries.

## Defect fixed in this train

`TracingActuatorAutoConfiguration.tracingActuatorEndpoint` now requires `@ConditionalOnBean(AcceptedTracingPolicyReader.class)` so HELM/DISABLED published consumers do not fail startup when the policy reader is absent. c3/c6 fixtures drop dead `platform.tracing.enabled`.

## Local / published-consumer commands

See `gate-local-final.log`. Inventory regenerated via `tracingConfigurationInventoryVerify -PupdateTracingConfigurationInventory`.

## Reused Docker evidence (P5.9)

| Gate | Evidence |
|---|---|
| E2E `-PrunE2e` | `p59-p6cutover-rerun/e2e-runE2e.log` |
| M10 | `m10/2026-07-30_131557` |
| M10c | `m10c/2026-07-30_132818` |
| M10d | `m10d/2026-07-30_140735` |

## Rollback unit

Whole unreleased train.
