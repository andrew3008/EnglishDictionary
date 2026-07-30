# P6.4 — Legacy Actuator write deletion

**Status:** COMPLETED  
**Date:** 2026-07-29  
**Plan task:** `p6-delete-actuator-write`

## Deleted

| Artifact | Disposition |
|---|---|
| `TracingActuatorEndpoint.updateTracing` `@WriteOperation` | Deleted |
| `platform.tracing.actuator.mutation-enabled` / `TracingProperties.Actuator` | Deleted |
| Read-model `actuator.mutationEnabled` projection | Deleted |
| Binding tests for mutation-enabled | Deleted |

## Preserved

- Read-only `/actuator/tracing` diagnostics endpoint
- Exactly 8 typed DEV_ACTUATOR leaf commands under `configuration.actuator` (LOCAL only)

## Detector

- `legacy-names-v1.json` bans `platform.tracing.actuator.mutation-enabled` (12 names)

## Verification

| Gate | Result |
|---|---|
| `TracingActuatorEndpointTest` | PASS |
| `TracingPropertiesBindingTest` | PASS |
| `P6LegacyNameFailFastTest` | PASS |
| `ProductionControlPlaneNotMigratedArchTest` | PASS |

## Residual (sibling tasks)

- `p6-production-actuator-zero-write-proof` — **CLOSED** by [p64-actuator-zero-write-proof.md](./p64-actuator-zero-write-proof.md) (descriptor + bean + in-process HTTP; Docker matrix remains PARTIAL)
- Inventory regenerate (CONFIG_PROPERTY count drop after mutation-enabled field removal)
