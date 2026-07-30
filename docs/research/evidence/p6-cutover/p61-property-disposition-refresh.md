# P6.1 property-disposition refresh (post P6.1–P6.9)

**Gate:** `p6-property-disposition` evidence refresh  
**As of:** 2026-07-30  
**HEAD:** `dca9f946918ef81ec4b9eb82b2b3844a3648d6e6` (`master`)  
**Production code changed by this note:** no  
**Verdict:** all **17/17** non-KEEP `CONFIG_PROPERTY` disposition rows are **APPLIED** in `src/main`

## Why not regenerate cutover-manifest.json

`generate-cutover-manifest.ps1` still emits `status=PENDING_CUTOVER` for these rows and hardcodes the old `deferredToDownstreamTask` block in `decision-summary.json`. A full regenerate would rewrite ~612 rows and incorrectly re-mark applied cutovers as pending. Measured status is recorded here and in [cutover-manifest.md](./cutover-manifest.md); machine JSON row statuses remain historical until a deliberate generator update.

## Verification method

Exact-string / symbol checks against production `**/src/main/**` only (no docs/tests as proof of ownership).

| Family | inventoryIds | Disposition | Measured production evidence | Owning cutover |
|---|---|---|---|---|
| Baggage | CONFIG_PROPERTY-002/003/004/019/020/021 | RENAME_TO_JAVAAGENT | `ExtensionPropertyNames` + `PropagationDefaults` read only `platform.tracing.javaagent.propagation.baggage.{enabled,allowlist-keys,allowed-keys,deny-patterns}`. Zero `src/main` readers of legacy `platform.tracing.baggage.*` or `platform.tracing.propagation.baggage.*`. | `p6-namespace-conflicts` + `p6-agent-parser-cutover` |
| Outbound Agent mirrors | CONFIG_PROPERTY-024/025/026/027/028 | DELETE_AGENT_KEEP_SPRING | Agent `PropagationDefaults` has **no** outbound `PROP_*` / `isOutboundEnabled` / `getTrustedHostPatterns` / `isPropagate*` APIs. Spring `TracingProperties.Propagation.Outbound` remains the sole `src/main` binder (`enabled`, `trustedHostPatterns`, `propagateForceTrace`, `propagateQaTrace`, `propagateRequestId`). | `p6-agent-parser-cutover` + `p6-spring-binding-cutover` |
| Sampling Category B | CONFIG_PROPERTY-040/043/044 | RENAME_TO_JAVAAGENT / RENAME_SPELLING_AND_OWNER | Agent: `ExtensionPropertyNames.SAMPLING_DROP_PATHS` / `SAMPLING_FORCE_RECORD_VALUES` → `platform.tracing.javaagent.sampling.{drop-paths,force-record-values}` (`SamplingExtensionConfig`). Spring: `TracingProperties.Sampling` has **no** `dropPaths` / `forceRecordHeaderValues` fields (comment documents Agent Category B ownership). Legacy Spring `force-record-header-values` absent from `src/main`. KEEP sibling `force-record-header` remains Agent-owned Category A. | `p6-agent-parser-cutover` + `p6-spring-binding-cutover` |
| Scrubbing Category B | CONFIG_PROPERTY-048/049 | RENAME_TO_JAVAAGENT (Spring binding DELETE) | Agent KEEP: `ExtensionPropertyNames.SCRUBBING_ENABLED` / `SCRUBBING_BUILT_IN_RULES` → `platform.tracing.javaagent.scrubbing.{enabled,built-in-rules}` (`ScrubbingExtensionConfig`). Spring: `TracingProperties` has **no** nested `Scrubbing` class; comment cites P6.7 move. | `p6-scrubbing-category-b-deletion` (P6.7) |
| Dead W3C diagnostics | CONFIG_PROPERTY-032 | DELETE | Already applied in prior P6.1 pass (`PROP_W3C_DIAGNOSTICS_ONLY` removed). | prior `p6-property-disposition` |

## APPLIED inventoryIds (17)

1. CONFIG_PROPERTY-002  
2. CONFIG_PROPERTY-003  
3. CONFIG_PROPERTY-004  
4. CONFIG_PROPERTY-019  
5. CONFIG_PROPERTY-020  
6. CONFIG_PROPERTY-021  
7. CONFIG_PROPERTY-024  
8. CONFIG_PROPERTY-025  
9. CONFIG_PROPERTY-026  
10. CONFIG_PROPERTY-027  
11. CONFIG_PROPERTY-028  
12. CONFIG_PROPERTY-032  
13. CONFIG_PROPERTY-040  
14. CONFIG_PROPERTY-043  
15. CONFIG_PROPERTY-044  
16. CONFIG_PROPERTY-048  
17. CONFIG_PROPERTY-049  

## Still open from this disposition set

**None.** All previously deferred-16 rows are applied.

ENV_VARIABLE section remains `EXTERNAL_KEEP` (29/29) — no disposition work required.

## Residual (out of disposition scope)

- `cutover-manifest.json` per-row `status` fields for these IDs still say `PENDING_CUTOVER` (stale machine checklist; superseded by this note + `cutover-manifest.md`).
- Formal P6 PASS may still be gated by separate todos (e.g. P5.9 verification package) — not a property-disposition leftover.
