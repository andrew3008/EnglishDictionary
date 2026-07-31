# Consolidated Post-P6 Autoconfigure Refactoring Audit

| Field | Value |
|-------|-------|
| Audit date | 2026-07-31 |
| Auditor role | Independent post-implementation (adversarial) |
| Repository | `E:/Platform_Traces` |
| HEAD | `bdf0b6170e3125a86b6d105296e25ac02ec519ed` (`P6 Part_4`) |
| Branch | `master` (tracks `english-dict/master`) |
| Working tree | **DIRTY** — A0–A6 + Wave2 present only as uncommitted changes |
| Java | 21.0.6 LTS |
| Gradle | 8.14 |
| Docker | `DOCKER_HOST=tcp://192.168.100.70:2375` — Gentoo Linux Engine **28.0.4** (initially refused; re-probe later in audit succeeded) |
| Plans / evidence used | `docs/research/evidence/autoconfigure-post-p6-simplify/committee-packet.md`; dead-class + overengineering audits; Wave2 census; ADR-spring-owns-only-what-spring-applies; Wave2 plan rev 6 (external Cursor plan) |

**This audit does not trust prior LLM `PASS`/`COMPLETED` labels without re-execution or code proof.**

---

## 1. Executive verdict

**Verdict: `FAIL`**

Primary blockers:

1. **Silent acceptance of removed Spring Category A mirror keys** (`platform.tracing.sampling.*`, `validation.*`, `log-level`) — proven by production binding defaults + `TracingPropertiesBindingTest.deletedCategoryASpringMirrorsAreNotBound` which asserts `hasNotFailed()`. Conflicts with this audit’s Phase 3 mandatory fail-fast requirement (and with committee risk of operators believing YAML still drives Agent reconcile).
2. **Stale S-01 owner inventory evidence after A1 rename** — root `.\gradlew.bat test` **FAILED** first run: `S01OwnerInventoryEvidenceTest` expects `LegacyTracingDiagnosticsEndpoint.tracing()` in committed evidence JSON; inventory still has zero matches (rename not reflected in S-01 corrective evidence).

Docker E2E (`:platform-tracing-e2e-tests:test -PrunE2e`) was initially **NOT_RUN** (daemon refused), then re-executed after Gentoo Docker became available — **PASS** (55/0/0/0 in ~10 min). Local autoconfigure module gates are also green (see §13). Remaining blockers still force verdict `FAIL`.

---

## 2. Audited repository state

| Item | Result |
|------|--------|
| Branch | `master` |
| HEAD | `bdf0b61` — last committed work is P6 cutover; **no commit contains A0–A6 or Wave2** |
| Dirty | Yes — large staged/unstaged mix: renames, deletes, docs, inventory regen, Wave2 files |
| Untracked | `docs/analysis/*-dead-class-audit.md`, `*-overengineering-audit.md`, `docs/research/evidence/autoconfigure-post-p6-simplify/`, `docs/research/evidence/overhead-simplify-wave2/`, `LegacyTracingDiagnosticsEndpointMapContractTest.java` |
| Plans vs HEAD | Committee packet + Wave2 census describe working-tree state, not immutable SHA |
| `git diff --check` | No whitespace errors (CRLF warnings only) |

A0–A6 and Wave2 are **implementation-present in the working tree only**.

---

## 3. Actual implementation delta

### A0 — SAFE_DELETE (verified absent from `src/main`)

| Symbol | Fate | Verdict |
|--------|------|---------|
| `PlatformTracingJmxOperationException` | DELETED | Acceptable cleanup (zero prod refs) |
| `SamplingControlUnavailableException` | DELETED | Acceptable cleanup |
| `TracingActuatorControlEndpoint` (tombstone) | DELETED | Acceptable cleanup |
| `SamplingRuntimeConfig` / `ValidationRuntimeConfig` / `SamplingRouteRatiosWire` | DELETED | Acceptable post-`RuntimeConfigApplier` cleanup |
| Matching unit tests | DELETED | Consistent |

### A1 — Rename dual Actuator surfaces

| Before | After | Endpoint id |
|--------|-------|-------------|
| `actuator.TracingActuatorEndpoint` | `LegacyTracingDiagnosticsEndpoint` | `tracing` |
| `configuration.actuator.TracingActuatorEndpoint` | `AcceptedPolicyActuatorEndpoint` | `tracingPolicy` |
| `TracingActuatorAutoConfiguration` | `TracingDiagnosticsActuatorAutoConfiguration` | — |
| `configuration.actuator.TracingActuatorAutoConfiguration` | `TracingPolicyControlAutoConfiguration` | — |

`AutoConfiguration.imports` lists new FQCNs + `PrivateJmxControlAutoConfiguration`.  
**Stale evidence:** S-01 committed access-site inventory not updated for rename → root test FAIL (§14).

### A2 — Bind-only Category A Spring mirrors

| Item | Fate |
|------|------|
| `TracingProperties.Sampling` / validation mirror / `logLevel` | REMOVED from POJO (confirmed: no matches in `TracingProperties.java`) |
| `OtelAgentLogLevel` | DELETED |
| Desired sampling/validation on `/actuator/tracing` | REMOVED; live JMX-only remains |

### A3 — Speculative apply diagnostics

| Item | Fate |
|------|------|
| `TracingApplyDiagnostics` | DELETED |
| `AcceptedPolicyProjection` | Now `(AgentReadinessView, AcceptedPolicyLeaves)` only — no diagnostics field |
| `/tracingPolicy` `applyDiagnostics` | ABSENT from `AcceptedPolicyActuatorEndpoint` |

### A4 — Shared private JMX autoconfig

| Item | Fate |
|------|------|
| `PrivateJmxControlAutoConfiguration` | **ADDED** — `AnyNestedCondition` ACTUATOR \| CONFIG_SERVER; beans: Locator, Codec, `PrivateJmxTracingPolicySnapshotClient`, SnapshotClient + SnapshotReader aliases |
| `AcceptedTracingPolicyReader` alias | Registered only in `TracingPolicyControlAutoConfiguration` (ACTUATOR path) |
| ConfigServer auto-config | Slimmed; uses shared client; retains adapter + coalescer + provenance |
| HELM / DISABLED | Condition excludes HELM; P56 asserts no `PrivateControlLocator` under HELM |

### A5 — Public JMX status trim

| Item | Fate |
|------|------|
| `getMBeansStatus` | DELETED (arch test asserts absence) |
| `TracingControlDomain` | DELETED |

### A6 — Diagnostics mapping inline

| Item | Fate |
|------|------|
| `TracingDiagnosticsMapper` / `TracingDiagnosticsView` | DELETED |
| `SpanFactoryDiagnostics.toActuatorMap()` | Sole mapping entry; used by endpoint + several tests |

### Wave2 — HTTP disclosure hardening

| Item | Fate |
|------|------|
| `getLiveRouteRatios` / `getLiveDropPathPrefixes` / `getLiveForceRecordValues` | **DELETED** after census |
| `getLiveRouteRatioCount` / `getLiveDropPathCount` / `getLiveForceRecordValueCount` | **ADDED** — `Optional<Integer>`; empty ≠ 0 |
| HTTP raw fields | REMOVED |
| HTTP count fields | ADDED in `sampling` |
| Scrubbing hardcoded strings | REMOVED |
| New production classes | **0** (Wave2) |
| New MBean attributes | **0** |

**Important code fact (census §2):** post-P6.6 platform SAMPLING MBean no longer exposes `DropPathPrefixes` / `ForceRecordValues`. Count methods for those attributes therefore return `Optional.empty()` → endpoint `null` against current Agent. `RouteRatios` remains and count works.

---

## 4. Useful-functionality preservation

| Capability | Status | Classification |
|------------|--------|----------------|
| `GET /actuator/tracing` | Present (`@Endpoint(id="tracing")`) | PRESERVED |
| `GET /actuator/tracingPolicy` | Present | PRESERVED |
| `config.auditTrail` + `getConfigAuditTrail` | Present | PRESERVED |
| Agent 32-entry audit ring | Present (`ConfigReloadDiagnostics.AUDIT_CAPACITY=32`) | PRESERVED |
| `scrubbing.liveRuleCount` via `getScrubbingMetrics().get("rules.loaded")` | Present | PRESERVED |
| `sampling.liveRatio` / `liveSamplerEnabled` / `controlAvailable` / `configVersion` / `configSource` | Present | PRESERVED |
| `validation.live*` / config version/source | Present | PRESERVED |
| `agent.ready` | Present (`agentReady` → JSON `ready`) | PRESERVED |
| `helmBootstrapDefaults` | Present | PRESERVED |
| Typed Servlet/WebFlux request DTOs | Present | PRESERVED |
| `ConfigServerTracingPolicyAdapter` + coalescer | Present | PRESERVED |
| Authority/posture guard | Present (P56 coverage) | PRESERVED |
| Config Server provenance | Present | PRESERVED |
| Private JMX discovery / `readAcceptedComplete` / `applySnapshot` | Present (P4/PRIV-R07 path) | PRESERVED |
| Exactly eight Category A leaves | Unchanged model | PRESERVED |
| P64 zero-write | Tests executed PASS in this audit | PRESERVED |
| Raw HTTP route/drop/force values | Intentionally removed | **INTENTIONAL_SAFE_REMOVAL** (disclosure) |
| Live drop-path / force-record **counts** as real Agent signal | Always `null` vs current MBean | **EVIDENCE_MISSING / under-signal** — not a secret leak; operators cannot observe Category B list cardinality via `/tracing` |
| Spring YAML mirrors driving Agent | Never worked post-reconcile delete; now unbound | **CONTRACT_BREAK_WITH_MIGRATION** incomplete — silent ignore (§6) |

---

## 5. Removed and renamed public contracts

| Symbol | Artifact | Visibility | Replacement | In-repo consumers | Docs | Classification | Verdict |
|--------|----------|------------|-------------|-------------------|------|----------------|---------|
| Old Actuator endpoint / auto-config FQCNs | autoconfigure | public | Renamed types | Tests/docs partially updated; **S-01 evidence stale** | Partial | Breaking rename | **REMEDIATE evidence** |
| `getMBeansStatus` / `TracingControlDomain` | autoconfigure | public | none | none found | none | Intentional delete | ACCEPT |
| Dead exceptions / reconcile DTOs | autoconfigure | public/pkg | none | none | historical | Intentional delete | ACCEPT |
| Raw JMX getters (3) | autoconfigure | public | count methods | endpoint only before delete | census | Intentional delete | ACCEPT (pre-prod) |
| Spring bind keys sampling/validation/log-level | properties | — | `platform.tracing.policy.*` (+ Agent bootstrap keys) | Binding test asserts ignore | ADR says ignore | Silent break | **BLOCKER vs Phase 3** |

External-consumer status for public client methods: **unknown outside repo** (census correctly notes this).

---

## 6. Legacy property fail-fast behavior

**Result: SILENT_IGNORE — BLOCKER under this audit’s Phase 3 criteria.**

Evidence:

1. `TracingPropertiesBindingTest.deletedCategoryASpringMirrorsAreNotBound` — sets obsolete keys; expects `ctx.hasNotFailed()`.
2. Spring Boot `@ConfigurationProperties` default ignores unknown fields.
3. ADR explicitly documents ignore of deleted YAML keys.
4. `P6LegacyNameFailFastTest` is a **source banlist** of other names — does **not** reject these runtime keys.
5. Agent `ExtensionPropertyNames` still KEEps `platform.tracing.sampling.*` / `validation.*` for Agent bootstrap — dual-channel tension: Spring fail-fast on the same names would collide with Agent-owned keys if both channels share `application.yml`.

**Committee note:** ADR intentional silence ≠ Phase 3 expected deterministic fail-fast. Remediation options (minimal): EnvironmentPostProcessor / Banlist for Spring-only obsolete keys that are **not** Agent KEEP; or documented dual-channel exception with executable diagnostic (WARN/metric) rather than pure silence for `log-level` (Agent-dead) and clear operator docs for Agent KEEP keys.

Representative keys:

| Key | Spring startup | Proven |
|-----|----------------|--------|
| `platform.tracing.sampling.enabled` | succeeds | binding test |
| `platform.tracing.validation.strict` | succeeds | binding test |
| `platform.tracing.log-level` | succeeds | binding test |

---

## 7. PrivateJmxControlAutoConfiguration activation matrix

Code condition: authority ∈ {`ACTUATOR`,`CONFIG_SERVER`} only.

| Posture (from P56 + code) | Locator/Codec/Client | Reader `/tracingPolicy` | ConfigServer bridge | Actuator write adapter |
|---------------------------|----------------------|-------------------------|---------------------|------------------------|
| HELM + * | ABSENT (P56 asserts) | ABSENT | ABSENT | ABSENT |
| CONFIG_SERVER + * | PRESENT (shared) | Reader alias **not** registered in ConfigServer AC (only Snapshot*) | PRESENT | ABSENT |
| ACTUATOR + LOCAL | PRESENT | PRESENT | ABSENT | PRESENT (env LOCAL) |
| ACTUATOR + non-LOCAL | Guard fail-closed before private beans (P56) | — | — | — |
| DISABLED + CONFIG_SERVER/ACTUATOR | Guard failure (P56) | — | — | — |
| DISABLED + HELM | Allowed; no private beans | ABSENT | ABSENT | ABSENT |

**Duplication:** Shared AC + `@ConditionalOnMissingBean` — single Locator/Codec/client set.  
**Servlet/WebFlux child-context tests:** executed in this audit via focused filters — **PASS** (module test run including `*TracingControlManagementChildContext*` / `*TracingControlWebFluxManagementChildContext*` / P56 / P64 / P55).

Full combinatorial matrix for “Agent marker absent/ambiguous/incompatible” was **not** re-proven end-to-end in this audit beyond existing readiness probe / P56 coverage — residual risk: **MEDIUM / EVIDENCE_PARTIAL**.

---

## 8. A6 SpanFactoryDiagnostics layering review

| Question | Evidence |
|----------|----------|
| Owner | `...autoconfigure.diagnostics.SpanFactoryDiagnostics` in autoconfigure module |
| Nature | Javadoc: “Actuator-facing diagnostics”; method named `toActuatorMap()` returns `Map<String,Object>` |
| Consumers | `LegacyTracingDiagnosticsEndpoint` + multiple tests (`SpringBootContextMatrixTest`, `DiagnosticsBoundaryTest`, JSON contract test) — **no second production consumer** |
| Actuator coupling | Semantic: yes (method name + Map projection). Technical Spring Actuator dependency: **no** |
| Complexity | Mapper/View stack removed; mapping is small and local |

**Verdict: `KEEP_AS_IMPLEMENTED`**

Rationale: type already lived in autoconfigure diagnostics and was Actuator-facing before A6; inlining removed accidental types without introducing a second module boundary violation severe enough to force endpoint-private mapping. Optional cleanup (non-blocking): rename `toActuatorMap` → `toMap` / move package-private helper into endpoint if committee wants stricter purity.

---

## 9. Wave2 census and raw-getter outcomes

Census file present: `docs/research/evidence/overhead-simplify-wave2/contract-consumer-census.md` (working tree / untracked folder).

| Method | Visibility | In-repo prod consumers after Wave A | Deleted? | Count impl | Verdict |
|--------|------------|--------------------------------------|----------|------------|---------|
| `getLiveRouteRatios` | was public | none | YES | `getAttribute(RouteRatios)` → `size` | APPROVED |
| `getLiveDropPathPrefixes` | was public | none | YES | `getStringArrayAttribute` → `length` | APPROVED (always empty vs Agent) |
| `getLiveForceRecordValues` | was public | none | YES | same | APPROVED (always empty vs Agent) |

Count semantics (code + Map contract test):

| Case | Expected | Implementation |
|------|----------|----------------|
| Empty `RouteRatios` map | `Optional.of(0)` | YES |
| Unavailable / missing attr / exception / wrong type | `Optional.empty()` | YES |
| Endpoint | key present, value `null` | YES (`orElse(null)`) |
| Coerce empty→0 | Forbidden | Not done |

No new JMX framework / production class.

---

## 10. Endpoint Map contract before/after

Verified against current `LegacyTracingDiagnosticsEndpoint.tracing()`:

**Removed:** `liveRouteRatios`, `liveDropPaths`, `liveForceRecordHeaderValues`, `customRulesSource`, `customRulesVisible`, `note`.  
**Added:** `routeRatioCount`, `dropPathCount`, `forceRecordValueCount`.  
**Preserved:** `auditTrail`, `liveRuleCount`, live sampling/validation signals, `agent.ready` on `/tracingPolicy`.

Executable Map contract test class present: `LegacyTracingDiagnosticsEndpointMapContractTest` (included in module 555 PASS).

Platform JMX raw maps for route ratios **still exist** on Agent MBean — Wave2 only hardened HTTP. Drop/force attributes already gone at Agent (P6.6).

---

## 11. Security and disclosure findings

| Finding | Severity | Notes |
|---------|----------|-------|
| HTTP raw route/drop/force values removed | INFORMATIONAL / positive | Matches Wave2 intent |
| Compatibility aliases not introduced | INFORMATIONAL / positive | |
| Platform JMX `RouteRatios` still readable | INFORMATIONAL | Out of Wave2 scope; not a regression of this package |
| Silent obsolete Spring keys | **BLOCKER** | Operator may believe Spring YAML still configures Category A mirrors |
| Hardcoded scrubbing strings removed | LOW positive | No diagnostic loss |

---

## 12. Overengineering and underengineering findings

| Finding | Severity | Type |
|---------|----------|------|
| `PrivateJmxControlAutoConfiguration` extract | INFORMATIONAL | Justified wiring dedup (1 new class) |
| Count methods for permanently deleted MBean attrs (`DropPath`/`ForceRecord`) | **MEDIUM** | Underengineering / false diagnostics — always-null fields in HTTP |
| `SpanFactoryDiagnostics.toActuatorMap` naming | LOW | Mild Actuator semantic coupling; KEEP |
| Boolean DTO collapse / adapter inline / agentReady removal | N/A | Correctly NOT done (architect REJECT/DEFER) |
| Dual Reader/SnapshotReader/SnapshotClient | INFORMATIONAL | KEEP_JUSTIFIED ISP |
| Speculative `TracingApplyDiagnostics` removed | INFORMATIONAL | Positive |

---

## 13. Verification results

| Command | First result | Notes |
|---------|--------------|-------|
| `:platform-tracing-spring-boot-autoconfigure:compileJava` + `compileTestJava --rerun-tasks` | **PASS** | ~15s |
| `:platform-tracing-spring-boot-autoconfigure:test --rerun-tasks` | **PASS** | **555** tests, **0** failures, **0** errors, **0** skipped |
| Focused P55/P56/P64 + Servlet/WebFlux child-context filters | **PASS** | Nested in module test |
| `tracingConfigurationInventoryVerify --rerun-tasks` | **PASS** | |
| `jmxManagementContractVerify` | **PASS** | |
| `jmxReadOnlyDeltaVerify` | **PASS** | |
| `privateMBeanServerVerify` | **PASS** | Ran successfully despite earlier `docker version` refusal |
| `.\gradlew.bat test` (aggregate) | **FAIL** (first run) | `:platform-tracing-bench:test` — S01 owner inventory rename drift |
| Docker E2E `-PrunE2e` (first attempt) | **NOT_RUN** | Docker host refused connection at initial pre-flight |
| Docker E2E `-PrunE2e` (re-run 2026-07-31 ~08:22–08:32) | **PASS** | **55** tests, **0** failures, **0** errors, **0** skipped; **25** suites; XML stamped `2026-07-31 08:31:43`; ~10.1 min wall; remote Gentoo Docker |
| `git diff --check` | **PASS** (warnings only) | |

Published starter consumer verify / full architecture fitness suite: **NOT_RUN** in this audit session (time/scope); not claimed PASS.

**Note:** An older P5.9 E2E log (`docs/research/evidence/p5-execution/p59-p6cutover-rerun/e2e-runE2e.log`, 2026-07-30, exit 0, 9m21s) exists but is **not** used as SHA-matched evidence for this working-tree audit; the 2026-07-31 re-run is the audit’s Docker gate.

---

## 14. First-run failures and flaky behavior

1. **`.\gradlew.bat test` FIRST RUN FAIL**  
   - Task: `:platform-tracing-bench:test`  
   - Test: `S01OwnerInventoryEvidenceTest.jmxAndSpringDeliveryPathsAreComplete`  
   - Message: `SafeSpanExporter must inventory LegacyTracingDiagnosticsEndpoint.tracing() exactly once` — Expected size 1 but was 0  
   - Root cause: A1 rename without updating committed S-01 corrective access-site inventory JSON.  
   - **Not erased** by module-level green runs.

2. No flaky re-pass of that failure was attempted/accepted as green.

3. Earlier implementation-time inventory drift was regenerated in the working tree before this audit; inventory verify PASS here does not heal S-01 bench evidence.

---

## 15. Required remediation

### BLOCKER

1. **Legacy Spring key posture** — Choose and implement one committee-approved behavior:  
   - deterministic fail-fast / migration diagnostic for Spring-obsolete keys that are **not** Agent KEEP; and  
   - explicit dual-channel documentation + diagnostic for Agent KEEP names appearing in Spring Environment without Spring bind.  
   Update/remove the binding test that currently encodes silent success as the desired outcome if fail-fast is chosen.

2. **Update S-01 owner inventory evidence** so `S01OwnerInventoryEvidenceTest` passes after `LegacyTracingDiagnosticsEndpoint` rename; re-run `.\gradlew.bat test` to green.

### HIGH

3. Commit or discard working tree coherently; do not merge dirty A0–A6/Wave2 without SHA-locked evidence.

### MEDIUM

4. Either remove always-null `dropPathCount`/`forceRecordValueCount` from HTTP **or** restore approved read attributes / alternative Category B summary source — do not leave perpetual `null` pretending to be live counts.

### LOW

5. Optional rename `toActuatorMap` if layering purity desired.

### CLOSED IN THIS AUDIT

6. Docker E2E `-PrunE2e` — re-run **PASS** after Gentoo Docker became available (see §13).

---

## 16. Committee recommendation

**Do not ACKNOWLEDGE as fully closed.**

Recommended committee actions:

1. Treat Wave2 HTTP disclosure hardening as **technically sound** and aligned with architect-reviewed plan, **subject to** remediation of always-null Category B counts.  
2. Treat A0/A1/A3/A4/A5/A6 cleanup as **mostly sound**, with mandatory fix for S-01 evidence rename drift and explicit decision on obsolete Spring key fail-fast vs ADR silence.  
3. Require clean commit + green aggregate `test` before merge (Docker E2E now re-proven PASS on this working tree).  
4. Do **not** treat prior LLM “PASS” or committee-packet optimism as verification.

---

## 17. Commit and push status

| Action | Status |
|--------|--------|
| Commit | **NOT PERFORMED** (audit read-only; tree remains dirty) |
| Push | **NOT PERFORMED** |

---

## Final response summary (machine-readable)

```text
HEAD: bdf0b6170e3125a86b6d105296e25ac02ec519ed
Working tree: DIRTY (A0–A6 + Wave2 uncommitted)
A0–A6 implementation: PRESENT in working tree (not at HEAD)
Wave2 implementation: PRESENT in working tree (not at HEAD)
Useful functionality preserved: YES for auditTrail/liveRuleCount/agent.ready/control-plane/P64; PARTIAL for Category B counts (always null)
Public contract removals: intentional (raw getters, dead types, renames); S-01 evidence stale
Legacy key fail-fast: FAIL — silent ignore proven (BLOCKER vs Phase 3)
Private-JMX activation matrix: HELM absent / CONFIG_SERVER+ACTUATOR shared — consistent with P56 (PASS focused)
Servlet/WebFlux parity: PASS (focused child-context + control tests)
Endpoint raw-value disclosure: REMOVED from HTTP (PASS intent)
Full module tests: PASS (555/0/0/0)
Full repository tests: FAIL (bench S01 inventory rename)
Docker E2E: PASS (55/0/0/0, ~10.1m, re-run after Gentoo available; first attempt NOT_RUN)
First-run failures: gradlew test → S01OwnerInventoryEvidenceTest
Blockers: (1) silent obsolete Spring keys (2) stale S-01 evidence
Verdict: FAIL
Commit: NOT PERFORMED
Push: NOT PERFORMED
```

