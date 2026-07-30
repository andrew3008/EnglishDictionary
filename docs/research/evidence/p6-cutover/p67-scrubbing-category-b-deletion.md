# P6.7 — Category B scrubbing holder and Spring binding deletion

**Date:** 2026-07-29  
**Tasks:** `p6-remove-scrubbing-policy-holder`, Spring Category B scrubbing binding cutover  
**Verdict:** PASS (focused gates)

## Scope completed

### Agent
- `ScrubbingSpanProcessor` holds `private final ScrubbingSnapshot policy` (startup version=1, source=`startup`)
- Deleted `ScrubbingPolicyHolder`, `ScrubbingPolicyUpdate`, and mutation APIs (`updateScrubbingPolicy` / `tryApplyPolicyUpdate` / `validatePolicyUpdateDomain`)
- `PlatformScrubbingControl` remains read-only (P6.6)
- Mutation-only unit tests deleted; loader characterization rewritten for startup immutability
- `VersionedStateHolder` production consumers = **0**

### Spring
- Deleted `TracingProperties.Scrubbing` (`platform.tracing.scrubbing.enabled`, `.built-in-rules`)
- Deleted orphan `ScrubbingRuntimeConfig` / `ScrubbingRuleNamesWire`
- Actuator scrubbing section projects live JMX only (no Spring desired copies)
- S-04 inventory regenerated; category-A scrubbing rows use `javaagent.scrubbing.*` + `EXCLUDE_CATEGORY_B`

### Evidence / detectors
- S-01 inventory: ScrubbingPolicyHolder marked `futureDeletionPhase=COMPLETED P6.7`, `isCurrentMutableProductionOwner=false`
- `legacy-names-v1.json`: bans Spring scrubbing.enabled / built-in-rules (14 names)
- E2E fail-closed secure profile uses `platform.tracing.javaagent.scrubbing.enabled=false`

## Verification (focused)

| Gate | Result |
|---|---|
| Agent compileJava / compileTestJava | PASS |
| Agent focused scrubbing tests | PASS |
| Spring compileJava / compileTestJava | PASS |
| TracingPropertiesBinding + Actuator + Inventory generator | PASS |

## Residual / not this slice

- Full Docker E2E suite not re-run here
- P5.9 verification package still pending (formal P6 PASS blocker)
- Deployment/sample manifests with stale scrubbing names → P6.9
- Public control-protocol API types residual → `p6-delete-partial-control-protocol`
- Optional follow-on: delete unused `VersionedStateHolder` type itself
