# P6.2 — Private writer production activation

**Status:** COMPLETED (Agent + CONFIG_SERVER / DEV_ACTUATOR Spring discovery)  
**Date:** 2026-07-29  
**Plan tasks:** `p6-private-writer-production-activation`  
**Blocker noted:** formal P6 PASS still requires pending `p5-verification-package` (P5.9)

## Agent

| Change | Detail |
|---|---|
| `AcceptedPolicyAccess.activatePrivateControlPlane()` | Creates `PrivateControlMBeanServerOwner` on the same `AcceptedPolicyStateStore` |
| `PlatformAutoConfigurationCustomizer` | Activates private marker/writer immediately after accepted-state init; shutdown hook closes owner |
| HELM | Agent still hosts private writer; Spring discovery remains absent |

## Spring

| Authority | Behavior |
|---|---|
| HELM | No `PrivateControlLocator` / snapshot client |
| CONFIG_SERVER | `ConfigServerTracingPolicyAutoConfiguration` — locator, codec, client, manifest, coalescer, adapter, readiness `deliverInitial()` |
| ACTUATOR + LOCAL | Existing `TracingActuatorAutoConfiguration` path unchanged |
| DISABLED / `configuration.enabled=false` | No Config Server private-control beans |

## Verification (executed)

| Gate | Result |
|---|---|
| `PrivateControlBoundaryTest` (incl. activatePrivateControlPlane) | PASS |
| `P56AuthorityPostureMatrixTest` | PASS |
| `P56DisabledNoDiscoveryTest` | PASS |

## Sibling residual

- `PlatformControlReadProjection` platform MBean registration → `p6-platform-read-projection-activation`
- Legacy platform JMX mutations still present until P6.6
