# P7.3 / P7.4 / P7.5 — Runbooks, consumer examples, JMX migration

**Date:** 2026-07-30  
**Tasks:** `p7-runbooks`, `p7-consumer-examples`, `p7-jmx-consumer-migration`  
**Verdict:** COMPLETE for documentation slice (code-verified claims; no formal P5.9 / full-P6 PASS claimed)

## Artifacts

| Task | Artifact |
|------|----------|
| P7.3 | `docs/runbook/runtime-policy-control.md` — HELM, CONFIG_SERVER, DEV_ACTUATOR+LOCAL, readiness, LKG, STALE, drift/restart, whole-train rollback |
| P7.4 | `docs/examples/*` — helm bootstrap env, Config Server Category A YAML, ACTUATOR+LOCAL YAML + typed-leaf curl script; superseded stale `docs/tracing/runtime-sampling-control.md` |
| P7.5 | `docs/runbook/jmx-consumer-migration.md` — post-P6.6 / E7 posture from `docs/research/evidence/jmx-read-only-delta/` |

Also updated pointers in `docs/SUPPORTED.md`, `docs/MIGRATION.md`, `docs/tracing/runtime-policy-control-architecture.md`.

## Code verification basis

Claims checked against production `src/main` (not invented):

| Claim | Source |
|-------|--------|
| HELM never loads Config Server / Actuator private-control writers | `ConfigServerTracingPolicyAutoConfiguration` `@ConditionalOnProperty(... CONFIG_SERVER)`; `TracingActuatorAutoConfiguration` ACTUATOR-gated locator |
| CONFIG_SERVER complete seven required keys + route-ratios map | `ConfigServerPolicyManifestBuilder.REQUIRED_KEYS` + `parseRouteRatios` |
| CONFIG_SERVER readiness = `readAcceptedComplete` then `deliverInitial` | `ConfigServerAgentReadinessProbe` |
| STALE: one bounded retry then LKG (Config Server) | `TracingPolicyRefreshCoalescer.doApply` |
| ACTUATOR typed eight leaves; no STALE auto-retry | `TracingControlMvcController`; `SpringActuatorTracingPolicyAdapter` javadoc |
| ACTUATOR requires LOCAL posture | `TracingControlManagementContextConfiguration` + `TracingConfigurationAuthorityGuard` |
| Sole private mutation `applySnapshot` | `PrivateControlWriter`; Spring `PrivateJmxTracingPolicySnapshotClient` |
| Platform Control projection read-only; `StructuralDrift`/`RestartRequired` attributes | `PlatformControlReadProjection` |
| Projection suppliers currently stub `false` | `PrivateControlMBeanServerOwner` (`() -> false`, `() -> false`) — documented accurately in runbook §7 |
| Category B final names | `ExtensionPropertyNames` `javaagent.scrubbing.*`, `javaagent.sampling.drop-paths` / `force-record-values`, `javaagent.propagation.baggage.*` |

E7/S-07 evidence cited (not re-executed in this slice):

- `jmx-read-only-delta/decision-summary.json` — platform mutations 0; private `applySnapshot` only
- `mutation-deletion-map.json` — `platformMutationsAfterP66: 0`
- `objectname-decision.json` — 8 platform ObjectNames read-only; private writer ObjectName

## Consumer example scan

| Target | Result |
|--------|--------|
| `samples/` | **Absent** in repository (no sample module tree) |
| `*.yaml` / `*.yml` / `*.properties` / `*.env` with banned `legacy-names-v1.json` exact names | **0** matches outside allowlisted historical docs |
| `docs/` operator examples | Added `docs/examples/`; replaced stale mutation recipes in `docs/tracing/runtime-sampling-control.md` with supersession pointer |
| `docs/research/**` historical evidence | **Not rewritten** (constraint) |
| Perf harness / k8s ConfigMap | Already on final names per P6.9 evidence (`javaagent.scrubbing`, mutation-enabled deleted) — no further edit required this slice |
| `docs/runbook/collector-pipeline-production.md` | Already uses `platform.tracing.javaagent.sampling.drop-paths` |

## Explicitly not claimed

- Formal **P5.9 PASS**
- Formal full-**P6 PASS** / zero-legacy gate closure
- Live non-stub `StructuralDrift` / `RestartRequired` detectors (attributes exist; suppliers currently constant `false`)
- Completeness of all historical ADRs / `docs/analysis/**` narrative (left as historical)

## Residual

| Item | Note |
|------|------|
| `docs/analysis/**`, older ADRs | May still describe deleted writers |
| External JMX consumer survey | Still listed as production rollout blocker in E7 evidence |
| `p7-deletion-scorecard` / `p7-final-verification` | Downstream todos |
