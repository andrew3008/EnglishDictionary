# Post-Audit Remediation Report

| Field | Value |
|-------|-------|
| Report date | 2026-07-31 |
| Audit baseline HEAD | `bdf0b6170e3125a86b6d105296e25ac02ec519ed` (`P6 Part_4`) |
| Closure SHA | `f572df6ba33e531537f22f1a5b34a189725de436` (`Refactoring`) |
| Branch | `master` → `english-dict/master` (in sync) |
| Working tree at rewrite | Clean at `f572df6` (this report rewrite may dirty it again) |
| Prior audit | [`consolidated-post-implementation-audit.md`](./consolidated-post-implementation-audit.md) (verdict `FAIL`) |
| Key census | [`legacy-key-ownership-census.md`](./legacy-key-ownership-census.md) |
| New production classes | **0** |
| Java / Gradle | 21.0.6 / 8.14 |
| Docker | `DOCKER_HOST=tcp://192.168.100.70:2375` (Gentoo Engine 28.0.4) |

Verification for WP1–WP3 and Docker E2E was executed on the dirty working tree that became commit `f572df6`. Gates below refer to that tree; the commit message is `Refactoring`.

---

## 1. Executive verdict

**`PASS`**

Post-P6 autoconfigure cleanup (A0–A6), Wave2 disclosure hardening, and the three audit remediation packages are landed on `master` at `f572df6` and pushed to `english-dict/master`.

| Package | Outcome |
|---------|---------|
| WP1 — stale S-01 owner inventory after A1 rename | Fixed; `LegacyTracingDiagnosticsEndpoint.tracing()` inventoried exactly once |
| WP2 — always-null Category B count surfaces | Removed; `routeRatioCount` retained |
| WP3 — silent obsolete Spring Category A mirrors | Exact-key census + deterministic Spring startup behavior |

Mandatory local gates and Docker E2E on the post-remediation tree were green before push. First-run failures (inventory drift; Docker daemon down) remain recorded and do not overturn later successful runs.

---

## 2. Repository state before remediation

| Item | Value |
|------|-------|
| HEAD | `bdf0b61` — last committed work was P6 cutover |
| Working tree | Dirty — A0–A6 + Wave2 uncommitted |
| Audit baseline drift | None — remediation started from the audited tree |
| Pre-production policy | Breaking cleanup allowed; no compatibility aliases reintroduced |

---

## 3. Existing changes preserved

Left intact through remediation and included in `f572df6`:

- Waves A0–A6 (dead SAFE_DELETE, Actuator rename, Category A Spring mirror removal, shared `PrivateJmxControlAutoConfiguration`, `getMBeansStatus` / `TracingControlDomain` deletion, `SpanFactoryDiagnostics.toActuatorMap()`)
- Wave2 disclosure hardening (raw HTTP route/drop/force values removed; scrubbing hardcoded strings removed)
- Control-plane surfaces: `agentReady`, `helmBootstrapDefaults()`, `config.auditTrail`, `getConfigAuditTrail`, `getScrubbingMetrics`, typed Servlet/WebFlux DTOs, ConfigServer adapter + coalescer, `readAcceptedComplete` / `applySnapshot`, eight Category A leaves

Not restored: `RuntimeConfigApplier`, Spring bind-only Category A mirrors, raw HTTP sampling lists, deleted MBean attributes.

---

## 4. S-01 inventory remediation

| Item | Detail |
|------|--------|
| Defect | Aggregate `test` failed: evidence still named `TracingActuatorEndpoint`; test required `LegacyTracingDiagnosticsEndpoint.tracing()` |
| Canonical evidence | `docs/research/evidence/accepted-state-primitive/repository-symbol-inventory.json` |
| Workflow | Update committed corrective evidence (no separate generator task); do not weaken `S01OwnerInventoryEvidenceTest` |
| Change | Access-site `class` values and related narratives → `LegacyTracingDiagnosticsEndpoint` |
| Acceptance | Old class name absent from active access sites; new `tracing()` site exactly once; unrelated entries stable |

**Proof:** `:platform-tracing-bench:test --tests "*S01OwnerInventoryEvidenceTest*"` PASS; included in green aggregate `test` before push. Present in `f572df6`.

---

## 5. Always-null count surface remediation

Post-P6.6 platform SAMPLING MBean no longer exposes `DropPathPrefixes` / `ForceRecordValues`. Wave2 count wrappers over those attributes were permanently `Optional.empty()` → HTTP `null`.

| Surface | Disposition |
|---------|-------------|
| `getLiveDropPathCount()` / `getLiveForceRecordValueCount()` | Removed |
| HTTP `sampling.dropPathCount` / `forceRecordValueCount` | Removed |
| `getLiveRouteRatioCount()` / HTTP `sampling.routeRatioCount` | Kept (`RouteRatios` still exists) |
| Raw `liveRouteRatios` / `liveDropPaths` / `liveForceRecordHeaderValues` | Remain absent (Wave2 security objective) |
| New MBean attributes / summary source | Not added |

`routeRatioCount` semantics unchanged: empty map → `0`; unavailable/malformed → key present with `null` (never coerced to `0`).

Docs updated in the same commit: runbook, Wave2 census, ADR-spring-owns-only-what-spring-applies, overengineering audit residual notes.

---

## 6. Exact legacy-key classification

Full census: [`legacy-key-ownership-census.md`](./legacy-key-ownership-census.md) (in `f572df6`).

| Exact keys | Classification |
|------------|----------------|
| `platform.tracing.sampling.enabled`, `.ratio`, `.route-ratios`, `.force-record-header`, `.qa-header` | `AGENT_BOOTSTRAP_KEEP` |
| `platform.tracing.javaagent.sampling.drop-paths`, `.force-record-values` | `AGENT_BOOTSTRAP_KEEP` |
| `platform.tracing.validation.enabled`, `.strict`, `.mode`, `.strict-runtime-allowed` | `AGENT_BOOTSTRAP_KEEP` |
| `platform.tracing.log-level` | `OBSOLETE_SPRING_ONLY` |
| `platform.tracing.policy.sampling.*` / `validation.*` / `diagnostics.log-level` | `ACTIVE_FINAL_KEY` |
| Pre-rename spellings in `legacy-names-v1.json` | Source banlist / documentation |

No `UNKNOWN_CONTRACT_DRIFT`. No `BLOCKED_COMMITTEE_KEY_OWNERSHIP_DECISION`.

---

## 7. Legacy-key runtime behavior

Implemented by extending existing `TracingConfigurationAuthorityGuard.from(Environment)` — **zero new production classes**, no EnvironmentPostProcessor framework, no dual binding.

| Classification | Startup behavior |
|----------------|------------------|
| `OBSOLETE_SPRING_ONLY` (`platform.tracing.log-level`) | Fail-fast `TracingConfigurationGuardException`; message names the exact key and states it is unbound / has no Agent bootstrap replacement |
| `AGENT_BOOTSTRAP_KEEP` | Startup succeeds; one WARN lists exact keys present in the Spring `Environment` and states Spring does not bind or apply them |
| `ACTIVE_FINAL_KEY` | Unchanged Category A policy plane |

Executable coverage:

- `TracingPropertiesBindingTest.obsoleteSpringOnlyLogLevelKeyFailsStartupFast`
- `TracingPropertiesBindingTest.agentBootstrapKeepKeysInSpringEnvironmentSucceedButAreNotSilent`
- `P6LegacyNameFailFastTest` (source banlist unchanged)

---

## 8. Useful-functionality preservation

Verified via focused control-plane tests (P55 / P56 / P64, Servlet & WebFlux management child contexts), Map contract tests, and the green module suite before push:

| Capability | Status |
|------------|--------|
| `GET /actuator/tracing`, `GET /actuator/tracingPolicy` | Preserved |
| `config.auditTrail`, Agent 32-entry audit ring, `getConfigAuditTrail` | Preserved |
| `scrubbing.liveRuleCount` via `getScrubbingMetrics` | Preserved |
| Live sampling / validation JMX projections | Preserved |
| `agent.ready`, `helmBootstrapDefaults()` | Preserved |
| Typed Servlet / WebFlux DTOs; ConfigServer adapter + coalescer | Preserved |
| Authority/posture guard; Config Server provenance; private JMX ISP | Preserved |
| `readAcceptedComplete` / `applySnapshot`; eight Category A leaves; P64 zero-write | Preserved |
| `SpanFactoryDiagnostics.toActuatorMap()` | Untouched (`KEEP_AS_IMPLEMENTED`) |

---

## 9. Files changed

Landed in `f572df6` (`Refactoring`). Remediation-relevant production/test/evidence surfaces:

| Area | Paths |
|------|-------|
| Production | `TracingConfigurationAuthorityGuard.java`; `PlatformTracingJmxClient.java`; `LegacyTracingDiagnosticsEndpoint.java`; A0–A6 / Wave2 autoconfigure delta |
| Tests | `TracingPropertiesBindingTest`; `LegacyTracingDiagnosticsEndpoint*`; `PlatformTracingJmxClientTest`; S-01 bench assertion |
| S-01 evidence | `docs/research/evidence/accepted-state-primitive/repository-symbol-inventory.json` |
| Config inventory | `docs/research/evidence/tracing-configuration-inventory/*` |
| Package evidence | `committee-packet.md`, consolidated audit, `legacy-key-ownership-census.md`, this report |
| Docs | runbook, Wave2 census, ADR, dead-class / overengineering audits |

---

## 10. Tests and evidence changed

| Artifact | Change |
|----------|--------|
| Binding tests | Obsolete key fail-fast; Agent KEEP WARN capture |
| Endpoint Map contract / endpoint / JMX client tests | Drop/force counts removed; `routeRatioCount` scenarios kept |
| S-01 evidence JSON | Rename alignment |
| Configuration inventory | Regenerated after AuthorityGuard / fixture references |
| Key census + committee / audit packets | Under `docs/research/evidence/autoconfigure-post-p6-simplify/` |

---

## 11. Commands executed

Executed against the dirty tree that became `f572df6` (not re-run after push unless noted).

| Command | First result | Notes |
|---------|--------------|-------|
| `:platform-tracing-spring-boot-autoconfigure:compileJava` + `compileTestJava --rerun-tasks` | PASS | |
| Focused filters (endpoint / JMX / binding / P55 / P56 / P64 / child contexts) | PASS | |
| `:platform-tracing-spring-boot-autoconfigure:test --rerun-tasks` | **FAIL** | Inventory drift — see §12 |
| `tracingConfigurationInventoryVerify -PupdateTracingConfigurationInventory` | PASS | Regen |
| Module `:test --rerun-tasks` (after regen) | PASS | **553** / 0 fail / 0 error / 0 skipped |
| `tracingConfigurationInventoryVerify`, `jmxManagementContractVerify`, `jmxReadOnlyDeltaVerify`, `privateMBeanServerVerify --rerun-tasks` | PASS | |
| Aggregate `test --rerun-tasks` | PASS | e2e module skipped without `-PrunE2e` (by design) |
| `:platform-tracing-bench:test --tests "*S01OwnerInventoryEvidenceTest*"` | PASS | |
| `:platform-tracing-e2e-tests:test -PrunE2e --rerun-tasks` | **FAIL** then **PASS** | See §12–§13 |
| `git diff --check` | PASS | CRLF warnings only |

---

## 12. First-run failures

These remain on record; later green runs do not erase them.

1. **Configuration inventory drift** — `TracingConfigurationInventoryGeneratorTest.generatedInventoryMatchesCommittedEvidence` failed after AuthorityGuard / fixture references touched `platform.tracing.log-level`. Resolved by regenerating committed inventory via `-PupdateTracingConfigurationInventory`.

2. **Docker E2E (first remediation attempt)** — 18 Testcontainers initialization errors: `Previous attempts to find a Docker environment failed` while `DOCKER_HOST=tcp://192.168.100.70:2375` was unreachable. Environmental; not a product assertion failure under a healthy daemon.

---

## 13. Rerun results

| Gate | Result |
|------|--------|
| Autoconfigure module test | PASS — 553 / 0 / 0 / 0 |
| Aggregate `test` | PASS (includes S-01) |
| Inventory + JMX + private MBean verifiers | PASS |
| Docker E2E after Gentoo available (`2026-07-31 ~09:10–09:19`) | PASS — **55** tests / 0 failures / 0 errors / 0 skipped; 25 suites; ~9m 11s; XML stamped `2026-07-31 09:19:57` |

---

## 14. Skipped or not-run gates

| Gate | Status |
|------|--------|
| e2e under plain aggregate `test` | SKIPPED by design (requires `-PrunE2e`) |
| `c3PublishedMetadataConsumerVerify` / `c6PublishedReactiveConsumerVerify` | NOT_RUN — outside mandatory remediation closure set |
| Post-push re-execution of full gate matrix | NOT_RUN — evidence is from the pre-push verified tree that became `f572df6` |

---

## 15. Remaining blockers

None for the audited remediation scope.

---

## 16. Final verdict

```text
PASS
```

---

## 17. Commit and push status

| Action | Status |
|--------|--------|
| Commit | **PERFORMED** — `f572df6` (`Refactoring`), 2026-07-31 09:32:09 +0300 |
| Push | **PERFORMED** — `master` in sync with `english-dict/master` at `f572df6` |

---

## Final summary

```text
Audit baseline HEAD: bdf0b6170e3125a86b6d105296e25ac02ec519ed
Closure SHA: f572df6ba33e531537f22f1a5b34a189725de436 (Refactoring)
Branch: master == english-dict/master
Working tree at rewrite: clean at f572df6 (report edit may dirty again)
S-01 inventory: FIXED (LegacyTracingDiagnosticsEndpoint.tracing() ×1; old class absent)
routeRatioCount: KEPT
dropPathCount: REMOVED
forceRecordValueCount: REMOVED
Legacy key classifications: OBSOLETE log-level; AGENT_BOOTSTRAP_KEEP sampling/validation; ACTIVE policy.*
Legacy key runtime behavior: fail-fast log-level; WARN Agent KEEP; 0 new production classes
Useful functionality preserved: YES
Spring module tests: PASS on rerun (553/0/0/0); first run FAIL inventory drift
Aggregate tests: PASS
Inventory: PASS after regen
JMX verifiers: PASS
Private MBean verifier: PASS
Servlet/WebFlux parity: PASS (focused)
Docker E2E: PASS on rerun (55/0/0/0, ~9m11s); first attempt FAIL (daemon down, retained)
First-run failures: inventory drift; Docker daemon
Skipped gates: e2e in plain test; c3/c6 NOT_RUN; post-push full re-run NOT_RUN
Blockers: none
Verdict: PASS
Commit: PERFORMED (f572df6)
Push: PERFORMED (english-dict/master)
```
