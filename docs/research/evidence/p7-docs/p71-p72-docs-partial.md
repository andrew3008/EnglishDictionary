# P7.1 / P7.2 — Documentation rewrite (PARTIAL)

**Date:** 2026-07-30  
**Tasks:** `p7-supported-migration-changelog`, `p7-public-contracts`  
**Verdict:** PARTIAL — core operator docs + contract stale-ref cleanup done; runbooks / examples / JMX consumer migration remain open

## Scope completed in this slice

| Artifact | Change |
|---|---|
| `docs/SUPPORTED.md` | Replaced legacy RuntimeConfigApplier / Actuator write guidance with P6 authority matrix, private `applySnapshot`, read-only platform JMX, Category B `javaagent.*` namespaces, no-alias policy, whole-train rollback. Noted formal P5.9/P6 PASS not claimed; Docker E2E infrastructure-blocked. |
| `docs/MIGRATION.md` | Added P6 cutover section (authorities, deleted writers, final Category B names, rollback). Updated TracingProperties purge rows that implied legacy Agent dual-bind / control-protocol migration. |
| `CHANGELOG.md` (repo root) | New `[Unreleased] — P6 cutover` breaking section. |
| `docs/contracts/public-contract-inventory.yaml` | Confirmed `tracing-control-protocol` already retired (P6.5). Fixed stale `otel/control/protocol/ValidationModePolicyValidatorTest` verification path; removed misplaced BLOCKED_UNTIL_P6 notes on `tracing-mdc-keys`; dropped deleted protocol decoder/schema from package-private guarantee. |
| `docs/contracts/semantic-contract-map.yaml` | Confirmed `tracing-control-protocol` concept already retired. Replaced `TracingControlProtocolKeys.SCRUBBING_*` with `platform.tracing.javaagent.scrubbing.*`. |

## Verification basis (code, not invented)

Claims checked against production `src/main` before documenting:

- `TracingConfigurationAuthority` / `TracingConfigurationAuthorityGuard` — HELM / CONFIG_SERVER / ACTUATOR; DISABLED fail-fast matrix
- `ConfigServerTracingPolicyAutoConfiguration` — CONFIG_SERVER complete manifest; HELM/DISABLED never load
- `TracingControlMvcController` — eight typed ACTUATOR+LOCAL leaves
- `AcceptedPolicyAccess` / `PrivateControlWriter.applySnapshot` — sole private mutation
- Platform domain MBean interfaces — read-only javadoc + P6.6 evidence
- `ExtensionPropertyNames` — final Category B `platform.tracing.javaagent.*` keys
- Arch/absence tests + `docs/research/evidence/p6-cutover/*` for deleted legacy writers

## Explicitly not claimed

- Formal **P5.9 PASS**
- Formal full-**P6 PASS** (zero-legacy verification remains PARTIAL; Docker/multi-node Actuator HTTP matrix and coherent-train gates still open per `p610-zero-legacy-verification-partial.md`)
- Dual-path / property aliases
- Completeness of historical ADRs under `docs/decisions/` (left largely untouched; some may still describe pre-cutover writers)

## Follow-up (P7.3–P7.5)

Closed in `p73-p75-runbooks-examples.md`: runbooks, `docs/examples/*`, JMX consumer migration.

## Still open (downstream P7 todos)

| Task id | Remaining work |
|---|---|
| `p7-public-contracts` (residual) | Telemetry attribute migration docs (e.g. `applied-policy-version` resource attribute) if not already covered elsewhere |
| `p7-deletion-scorecard` | Measured scorecard |
| `p7-final-verification` / `p7-release-readiness-review` | Full gates + committee acceptance |

## Residual doc drift (out of this slice)

Historical analysis/plan docs under `docs/analysis/` and some ADRs still mention deleted writers; not rewritten here.
