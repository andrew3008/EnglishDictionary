# P6.0/P6.1 — Cutover execution manifest

**Gate:** P6.0 (manifest) / P6.1 (property disposition partial + namespace conflicts resolved)
**Verdict:** `MANIFEST_RECORDED`; property disposition `PARTIAL_CUTOVER`; namespace conflicts `CONFLICTS_RESOLVED`
**As of:** 2026-07-29
**Production code changed:** yes — dead-code DELETE (w3c.diagnostics-only) + resource attribute rename + published semconv contract correction

Namespace conflict resolution: [namespace-conflict-resolution.md](./namespace-conflict-resolution.md) · [p61-namespace-conflicts-decision.json](./p61-namespace-conflicts-decision.json)

Machine-readable: [cutover-manifest.json](./cutover-manifest.json)
Summary: [decision-summary.json](./decision-summary.json)
Generator: [generate-cutover-manifest.ps1](./generate-cutover-manifest.ps1)

## Required counts (PASS, updated after CONFIG_PROPERTY-032 cutover)

| Section | Required | Actual |
|---|---:|---:|
| CONFIG_PROPERTY | 77 (was 78) | 77 |
| ENV_VARIABLE | 29 | 29 |
| SECTION_6_1_OVERRIDE | 29 (was 30) | 29 |
| MUTATION_FAMILY | 6 | 6 |
| JMX_E1_E7_DELTA | 63 | 63 |

Of the 78 original CONFIG_PROPERTY rows, 61 were already `KEEP` (no change). Of the
remaining 17 requiring a disposition, this pass completed **1** and deferred **16**
to sibling P6 tasks (rationale below). ENV_VARIABLE: all 29 rows are `EXTERNAL_KEEP`
(OTel/Platform ambient env vars) — zero work required for that section.

## P6.1 property-disposition execution log

### Completed (1/17)

| inventoryId | property | action | evidence |
|---|---|---|---|
| CONFIG_PROPERTY-032 | `platform.tracing.propagation.w3c.diagnostics-only` | DELETE | Removed dead `PropagationDefaults.PROP_W3C_DIAGNOSTICS_ONLY` constant (`NO_READER`, zero production/test consumers). S-04 inventory regenerated (78→77 CONFIG_PROPERTY, `TracingConfigurationInventoryGeneratorTest` PASS). `platform-tracing-otel-javaagent-extension:compileJava`/`compileTestJava` PASS. |

### Deferred to sibling P6 tasks (16/17) — with rationale

| inventoryIds | disposition | why deferred now | owning task |
|---|---|---|---|
| CONFIG_PROPERTY-002/003/004/019/020/021 | RENAME_TO_JAVAAGENT (baggage families) | Two independent, both-live production code paths currently answer to overlapping final names: `BaggageExtensionConfig` (span-attribute allow/deny list, consumed by `PlatformSpanProcessorFactory`) and `PropagationDefaults`/`BaggagePropagationCustomizer` (W3C baggage propagation). The manifest's final names for `-enabled`/`-deny-patterns` are identical across both families — resolving that family conflict (which code path becomes canonical, whether to merge) is a semantic decision explicitly scoped to `p6-namespace-conflicts`, not a mechanical string rename. | `p6-namespace-conflicts` (§20 P6.1) |
| CONFIG_PROPERTY-024/025/026/027/028 | DELETE_AGENT_KEEP_SPRING (outbound) | Agent-side `PropagationDefaults.isOutboundEnabled`/`getTrustedHostPatterns`/`isPropagate*` mirror Spring's `TracingProperties.Outbound`. Deleting the Agent side in isolation, before the coordinated Agent+Spring switch, would violate the plan's "one unreleased train, no intermediate dual-path" invariant. | `p6-agent-parser-cutover` / `p6-spring-binding-cutover` (§20 P6.1–P6.2) |
| CONFIG_PROPERTY-040/043/044 | RENAME_TO_JAVAAGENT / RENAME_SPELLING_AND_OWNER (sampling drop-paths, force-record) | Same atomic Agent+Spring dependency as outbound, plus a spelling/ownership merge (`force-record-header-values` → `force-record-values`, Spring→Agent) that also depends on `p6-namespace-conflicts` resolution. | `p6-agent-parser-cutover` / `p6-spring-binding-cutover` / `p6-namespace-conflicts` |
| CONFIG_PROPERTY-048/049 | RENAME_TO_JAVAAGENT (scrubbing built-in-rules, enabled) | Confirmed by call-graph inspection: Spring's `TracingProperties.Scrubbing` feeds the still-active Category B legacy runtime-mutation path — `RuntimeConfigApplier.updateScrubbingPolicy()` → `PlatformScrubbingControl` (JMX) → `ScrubbingPolicyHolder`. Removing the Spring binding now, before that path is deleted, would silently break live runtime mutation. The plan explicitly reserves this deletion for a dedicated later task. | `p6-scrubbing-category-b-deletion` (§20 P6.7) |

None of the 16 deferred rows were left with dual/aliased/relaxed bindings — they are
simply untouched (original single-owner behavior preserved) pending their designated
atomic-switch task, consistent with "no relaxed dual binding, no silent fallback."

## Sources

- S-04 `docs/research/evidence/tracing-configuration-inventory/inventory.json` (regenerated)
- Plan section 6.1 (29 remaining closed overrides after CONFIG_PROPERTY-032 removal)
- Plan section 21.1 (6 mutation families)
- S-07 `docs/research/evidence/jmx-read-only-delta/member-inventory.json`
- `docs/research/evidence/public-control-protocol-consumers/consumer-inventory.json`

## Row schema

Every row has: `inventoryId`, `currentNameOrSymbol`, `finalNameOrDisposition`, `disposition`, `ownerBefore`/`ownerAfter`, `productionReadersBefore`/`After`, `filesOrModules`, `replacementOrDeletionDiagnostic`, `verificationRule`, `status`.

Wildcard completion is forbidden. §6.1 overrides supersede S-04 for matching inventory IDs.

## Blockers before full P6 code cutover

1. `p5-verification-package` (P5.9) still pending
2. Legacy-name detector not implemented (`LEGACY_DETECTOR-001`)
3. Macro perf M10/PerfAdmin rewrite residual from P5.8
4. 16 remaining property-disposition rows require `p6-namespace-conflicts`,
   `p6-agent-parser-cutover`, `p6-spring-binding-cutover`, and `p6-scrubbing-category-b-deletion`
   to execute as a single coordinated train (see execution log above)

P6.0 itself only records the checklist; it does not authorize production deletion.
P6.1 (this pass) applied the one disposition that was safe to execute independently.
