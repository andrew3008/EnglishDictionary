# P6.9 — Deployment artifact synchronization (final config names)

**Date:** 2026-07-29  
**Task:** P6.9 deployment / sample / e2e / perf / helm / docker / scripts cutover  
**Verdict:** PASS (focused gates)

## Goal

Update live deployable and sample configuration so banned legacy names from
`legacy-names-v1.json` no longer appear as live config. No dual-read aliases.

## Renames / deletions applied

| File | Change |
|---|---|
| `platform-tracing-perf-tests/scenarios/m4.env` | `platform.tracing.scrubbing.enabled` → `platform.tracing.javaagent.scrubbing.enabled` |
| `platform-tracing-perf-harness/src/main/resources/application.yaml` | DELETE `platform.tracing.actuator.mutation-enabled` (and empty `actuator` block) |
| `platform-tracing-perf-harness/config/k8s/perf-app-configmap.yaml` | DELETE `PLATFORM_TRACING_ACTUATOR_MUTATION_ENABLED` and nested `actuator.mutation-enabled` |
| `platform-tracing-perf-harness/src/main/resources/application-s4-production.yaml` | `platform.tracing.scrubbing.enabled` → `platform.tracing.javaagent.scrubbing.enabled` |
| `platform-tracing-perf-harness/config/profiles/s4-production.yaml` | same scrubbing rename |
| `platform-tracing-perf-harness/config/profiles/s6-soak.yaml` | same scrubbing rename |
| `docs/runbook/collector-pipeline-production.md` | `platform.tracing.sampling.drop-paths` → `platform.tracing.javaagent.sampling.drop-paths` |
| `docs/contracts/semantic-contract-map.yaml` | `force-record-header-values` → `javaagent.sampling.force-record-values` |

## Scan coverage (no further live hits)

- Helm charts: none in repository
- Docker / compose / e2e fixtures: no banned live keys (e2e already uses `javaagent.scrubbing.*`)
- `platform-tracing-samples`: no banned keys
- Spring autoconfigure `src/main/resources` metadata: clean
- Support scripts (perf-tests / perf-harness): clean
- Config Server complete Category A manifest (`ConfigServerPolicyManifestBuilder.REQUIRED_KEYS`): already final `platform.tracing.policy.*` leaf names; Category B javaagent keys are not Config Server required leaves (Agent-owned)

## Residual intentional mentions (not rewritten)

| Location | Why retained |
|---|---|
| `docs/research/evidence/p6-cutover/**` | Historical P6 cutover evidence / detector inventory |
| `docs/research/evidence/tracing-configuration-inventory/**` | Committed S-04 inventory (P6.7 regenerated; not rewritten here) |
| `docs/analysis/**`, `CHANGELOG.md`, `MIGRATION.md` | Plans / historical notes (out of scope) |
| `docs/decisions/**`, `docs/architecture/**`, `docs/tracing/**` (non-runbook) | Historical ADRs / characterization / architecture dossiers |
| `docs/tracing/perf-results/**/m4/summary.json` | Frozen historical perf evidence snapshots |
| `docs/jira/subtasks-wiki.md` | Historical wiki dump |
| `P6LegacyNameFailFastTest` / `legacy-names-v1.json` | Detector allowlist of banned names |
| `TracingPropertiesBindingTest` | Negative binding test: proves Spring does **not** bind legacy Category B sampling keys |
| `TracingConfigurationInventoryGeneratorTest` | Inventory generator notes / deleted-entry bookkeeping |
| `ExtensionPropertyNames` Javadoc | Migration note for `policy.version` → resource attr (literal-only ban) |
| `PlatformScrubbingControl` `"policy.version"` snapshot map key | JMX diagnostic field name, not config property `platform.tracing.policy.version` |

## Verification

```powershell
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --tests "*P6LegacyNameFailFastTest*" --no-daemon
```

No deployable Java fixtures required compile beyond the fail-fast gate (YAML/env/docs-only edits in this slice).
