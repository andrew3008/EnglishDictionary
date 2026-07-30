# P6.0/P6.1 — Cutover execution manifest

**Gate:** P6.0 (manifest) / P6.1 (property disposition)  
**Verdict:** `MANIFEST_RECORDED`; property disposition `APPLIED` (17/17 non-KEEP rows); namespace conflicts `CONFLICTS_RESOLVED`  
**As of:** 2026-07-30 (disposition refresh; original manifest 2026-07-29)  
**Production code changed by this refresh:** no — evidence-only update after P6.1–P6.9 cutovers

Namespace conflict resolution: [namespace-conflict-resolution.md](./namespace-conflict-resolution.md) · [p61-namespace-conflicts-decision.json](./p61-namespace-conflicts-decision.json)

Machine-readable: [cutover-manifest.json](./cutover-manifest.json) *(row `status` fields may still say `PENDING_CUTOVER` — superseded by measured refresh below)*  
Summary: [decision-summary.json](./decision-summary.json)  
Generator: [generate-cutover-manifest.ps1](./generate-cutover-manifest.ps1)  
Disposition refresh evidence: [p61-property-disposition-refresh.md](./p61-property-disposition-refresh.md)

## Required counts (PASS, updated after CONFIG_PROPERTY-032 cutover)

| Section | Required | Actual |
|---|---:|---:|
| CONFIG_PROPERTY | 77 (was 78) | 77 |
| ENV_VARIABLE | 29 | 29 |
| SECTION_6_1_OVERRIDE | 29 (was 30) | 29 |
| MUTATION_FAMILY | 6 | 6 |
| JMX_E1_E7_DELTA | 63 | 63 |

Of the 78 original CONFIG_PROPERTY rows, 61 were already `KEEP` (no change). Of the
remaining 17 requiring a disposition, **all 17 are now APPLIED** in production `src/main`
(verified 2026-07-30). ENV_VARIABLE: all 29 rows are `EXTERNAL_KEEP`
(OTel/Platform ambient env vars) — zero work required for that section.

## P6.1 property-disposition execution log

### Completed (17/17)

| inventoryId | property (final / disposition) | action | evidence |
|---|---|---|---|
| CONFIG_PROPERTY-032 | `platform.tracing.propagation.w3c.diagnostics-only` | DELETE | Removed dead `PropagationDefaults.PROP_W3C_DIAGNOSTICS_ONLY` (prior P6.1 pass). |
| CONFIG_PROPERTY-002 | `…javaagent.propagation.baggage.allowlist-keys` | RENAME_TO_JAVAAGENT | `ExtensionPropertyNames.BAGGAGE_ALLOWLIST_KEYS` — Agent only. |
| CONFIG_PROPERTY-003 | `…javaagent.propagation.baggage.deny-patterns` | RENAME_TO_JAVAAGENT | Shared under javaagent family (`ExtensionPropertyNames` / `PropagationDefaults`). |
| CONFIG_PROPERTY-004 | `…javaagent.propagation.baggage.enabled` | RENAME_TO_JAVAAGENT | Same. |
| CONFIG_PROPERTY-019 | `…javaagent.propagation.baggage.allowed-keys` | RENAME_TO_JAVAAGENT | `PropagationDefaults.PROP_BAGGAGE_ALLOWED_KEYS`. |
| CONFIG_PROPERTY-020 | `…javaagent.propagation.baggage.deny-patterns` | RENAME_TO_JAVAAGENT | Consolidated with -003 under javaagent. |
| CONFIG_PROPERTY-021 | `…javaagent.propagation.baggage.enabled` | RENAME_TO_JAVAAGENT | Consolidated with -004 under javaagent. |
| CONFIG_PROPERTY-024 | `platform.tracing.propagation.outbound.enabled` | DELETE_AGENT_KEEP_SPRING | Agent outbound `PROP_*` mirrors deleted; Spring `TracingProperties.Outbound` KEEP. |
| CONFIG_PROPERTY-025 | `…outbound.propagate-force-trace` | DELETE_AGENT_KEEP_SPRING | Same. |
| CONFIG_PROPERTY-026 | `…outbound.propagate-qa-trace` | DELETE_AGENT_KEEP_SPRING | Same. |
| CONFIG_PROPERTY-027 | `…outbound.propagate-request-id` | DELETE_AGENT_KEEP_SPRING | Same. |
| CONFIG_PROPERTY-028 | `…outbound.trusted-host-patterns` | DELETE_AGENT_KEEP_SPRING | Same. |
| CONFIG_PROPERTY-040 | `…javaagent.sampling.drop-paths` | RENAME_TO_JAVAAGENT | Agent `SAMPLING_DROP_PATHS`; Spring `Sampling.dropPaths` removed. |
| CONFIG_PROPERTY-043 | `…javaagent.sampling.force-record-values` | RENAME_SPELLING_AND_OWNER | Spring `force-record-header-values` binding removed; Agent owns final name. |
| CONFIG_PROPERTY-044 | `…javaagent.sampling.force-record-values` | RENAME_TO_JAVAAGENT | Agent `SAMPLING_FORCE_RECORD_VALUES`. |
| CONFIG_PROPERTY-048 | `…javaagent.scrubbing.built-in-rules` | RENAME_TO_JAVAAGENT | Agent KEEP; Spring `TracingProperties.Scrubbing` deleted (P6.7). |
| CONFIG_PROPERTY-049 | `…javaagent.scrubbing.enabled` | RENAME_TO_JAVAAGENT | Same. |

### Previously deferred (16/17) — now APPLIED

The block below replaces the former “Deferred to sibling P6 tasks” table. Those tasks have executed; measured `src/main` ownership matches the planned dispositions.

| inventoryIds | disposition | status | completed via |
|---|---|---|---|
| CONFIG_PROPERTY-002/003/004/019/020/021 | RENAME_TO_JAVAAGENT (baggage → `javaagent.propagation.baggage.*`) | **APPLIED** | `p6-namespace-conflicts` + `p6-agent-parser-cutover` |
| CONFIG_PROPERTY-024/025/026/027/028 | DELETE_AGENT_KEEP_SPRING (outbound) | **APPLIED** | `p6-agent-parser-cutover` + `p6-spring-binding-cutover` |
| CONFIG_PROPERTY-040/043/044 | RENAME_TO_JAVAAGENT / RENAME_SPELLING_AND_OWNER (sampling Category B) | **APPLIED** | `p6-agent-parser-cutover` + `p6-spring-binding-cutover` |
| CONFIG_PROPERTY-048/049 | RENAME_TO_JAVAAGENT (scrubbing; Spring binding deleted) | **APPLIED** | `p6-scrubbing-category-b-deletion` (P6.7) |

**Still open from this disposition set:** none.

Detail and verification commands: [p61-property-disposition-refresh.md](./p61-property-disposition-refresh.md).

## Sources

- S-04 `docs/research/evidence/tracing-configuration-inventory/inventory.json` (regenerated)
- Plan section 6.1 (29 remaining closed overrides after CONFIG_PROPERTY-032 removal)
- Plan section 21.1 (6 mutation families)
- S-07 `docs/research/evidence/jmx-read-only-delta/member-inventory.json`
- `docs/research/evidence/public-control-protocol-consumers/consumer-inventory.json`
- Production `src/main` readers (2026-07-30 refresh)

## Row schema

Every row has: `inventoryId`, `currentNameOrSymbol`, `finalNameOrDisposition`, `disposition`, `ownerBefore`/`ownerAfter`, `productionReadersBefore`/`After`, `filesOrModules`, `replacementOrDeletionDiagnostic`, `verificationRule`, `status`.

Wildcard completion is forbidden. §6.1 overrides supersede S-04 for matching inventory IDs.

## Blockers / residuals

1. Machine `cutover-manifest.json` per-row statuses for the 16 former deferred IDs may still read `PENDING_CUTOVER` — do not regenerate via the current script (it would re-hardcode deferred progress). Treat this markdown + [p61-property-disposition-refresh.md](./p61-property-disposition-refresh.md) as authoritative for disposition status.
2. Formal full-P6 PASS may still depend on separate gates (e.g. P5.9 verification package) — not a leftover property-disposition inventoryId.
3. Legacy-name detector / deployment cutovers are tracked by sibling P6 todos (`p6-legacy-name-fail-fast`, `p6-deployment-manifest-cutover`), not by open disposition rows.

P6.0 itself only records the checklist. Property disposition (this gate) is now complete for all 17 non-KEEP CONFIG_PROPERTY rows.
