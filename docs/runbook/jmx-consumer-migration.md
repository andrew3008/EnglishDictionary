# JMX consumer migration (post-P6.6 / E7)

| Field | Value |
|-------|-------|
| Audience | Dashboard / ops script / Actuator client owners |
| Evidence | `docs/research/evidence/jmx-read-only-delta/` (S-07 / E7) |
| Related | [runtime-policy-control.md](runtime-policy-control.md), [MIGRATION.md](../MIGRATION.md) |

Formal **P5.9 PASS** / full-**P6 PASS** are **not** claimed here. This note records the **post-P6.6 live JMX posture** from committed E7/S-07 evidence.

---

## Target posture (verified evidence)

From `decision-summary.json` / `mutation-deletion-map.json` / `objectname-decision.json`:

| Plane | ObjectNames | Mutations | Role |
|-------|------------:|----------:|------|
| **Platform** MBeanServer | 8 | **0** | Read-only domain + Control projection |
| **Private** MBeanServer | 2 | **1** (`applySnapshot`) | Private writer + marker; plus `readAcceptedComplete` |

| Metric | Value |
|--------|------:|
| Platform mutations after P6.6 | **0** |
| Platform read-only members (target) | 60 |
| Private mutations | 1 (`PRIV-W01` `applySnapshot`) |
| Private complete reader | `PRIV-R07` `readAcceptedComplete` |
| Legacy partial `applyPolicy` | **Deleted** (not relocated) |

Verdict in evidence: `APPROVE_EXACT_JMX_DELTA` / `READY_TO_CLOSE_E7` (spike). Production rollout still lists external consumer survey + P6 zero-write proof as blockers in that evidence set — do not treat this note as a release gate PASS.

---

## What consumers must stop doing

| Stop | Why |
|------|-----|
| Domain JMX setters / mutating ops (`setSamplingRatio`, `updateSamplingPolicy`, `updateScrubbingPolicy`, …) | Platform mutations = 0 |
| Platform `applyPolicy` / public control protocol writes | Deleted; replacement is private `applySnapshot` only |
| Actuator `POST /actuator/tracing/{property}/{value}` | Deleted with `mutation-enabled` |
| Treating Control MBean as a writer | Platform Control projection is **read-only** (`setAttribute` unsupported) |
| Calling private `applySnapshot` from dashboards | Private plane is for CONFIG_SERVER / ACTUATOR adapters only |

---

## What consumers may keep / migrate to

### Platform (read-only) ObjectNames

Retained names (see `objectname-decision.json`):

```text
space.br1440.platform.tracing:name=PlatformSamplingControl,type=Sampling
space.br1440.platform.tracing:name=PlatformScrubbingControl,type=Scrubbing
space.br1440.platform.tracing:name=PlatformValidationControl,type=Validation
space.br1440.platform.tracing:name=PlatformExportControl,type=Export
space.br1440.platform.tracing:name=PlatformDiagnosticsControl,type=Diagnostics
space.br1440.platform.tracing:name=PlatformProcessorMetricsControl,type=Metrics
space.br1440.platform.tracing:name=PlatformExtension,type=Readiness
space.br1440.platform.tracing:name=PlatformControlProtocol,type=Control
```

Use these for **read** dashboards / alerts only (ratio, enabled flags, readiness, last apply outcome, etc.).

Control projection attributes include (among others): accepted-state fields, `LastApplyOutcome`, `LastFailureCode`, `StructuralDrift`, `RestartRequired`. Do not write them.

### Mutation path (not JMX dashboards)

| Authority | How to mutate |
|-----------|----------------|
| HELM | Restart with new Agent bootstrap env/`-D` |
| CONFIG_SERVER | Complete Category A manifest → Spring → private `applySnapshot` |
| ACTUATOR + LOCAL | Eight typed `/tracingControl/...` leaves → private `applySnapshot` |

---

## Migration checklist

1. Inventory scripts/dashboards that invoke platform MBean **operations** or set **writable attributes** → delete or rewrite to reads.
2. Replace Actuator write URLs with typed `/tracingControl/...` leaves (LOCAL only) or Config Server manifests.
3. Point alerting at Control projection **outcomes** / readiness, not mutation success of deleted ops.
4. Confirm ObjectName strings still match the table above (same logical Control name on platform vs private servers are **different registrations** — server-scoped).
5. Do not add compatibility shims for deleted mutators.

---

## Residual risks (from evidence)

- `externalConsumerRisk`: possible unsurveyed pre-production consumers (`consumer-map.json`).
- Production rollout blockers listed in `external-consumer-risk.json`: consumer survey + P6 zero-reference/zero-write proof.

Coordinate with [runtime-policy-control.md](runtime-policy-control.md) §8 for whole-train rollback.
