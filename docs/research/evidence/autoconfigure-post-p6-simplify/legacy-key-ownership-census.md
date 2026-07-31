# Exact legacy-key ownership census (post-audit remediation)

| Field | Value |
|-------|-------|
| As of | 2026-07-31 |
| HEAD baseline | `bdf0b6170e3125a86b6d105296e25ac02ec519ed` (+ dirty A0–A6 / Wave2 / remediation) |
| Sources | `ExtensionPropertyNames`, `TracingProperties`, `TracingConfigurationAuthorityGuard`, `legacy-names-v1.json`, ADR-spring-owns-only-what-spring-applies, inventory |

Classification vocabulary: `OBSOLETE_SPRING_ONLY` | `AGENT_BOOTSTRAP_KEEP` | `ACTIVE_FINAL_KEY` | `DOCUMENTATION_ONLY` | `UNKNOWN_CONTRACT_DRIFT`.

---

## Summary table

| Exact key | Classification | Spring bind after P6 | Agent reader | Runtime Spring behavior (remediation) |
|-----------|----------------|----------------------|--------------|---------------------------------------|
| `platform.tracing.sampling.enabled` | AGENT_BOOTSTRAP_KEEP | none | `SamplingExtensionConfig` via `ExtensionPropertyNames.SAMPLING_ENABLED` | WARN if present in Spring `Environment`; startup OK |
| `platform.tracing.sampling.ratio` | AGENT_BOOTSTRAP_KEEP | none | `SAMPLING_RATIO` | WARN; startup OK |
| `platform.tracing.sampling.route-ratios` | AGENT_BOOTSTRAP_KEEP | none | `SAMPLING_ROUTE_RATIOS` | WARN; startup OK |
| `platform.tracing.sampling.force-record-header` | AGENT_BOOTSTRAP_KEEP | none | `SAMPLING_FORCE_RECORD_HEADER` | WARN; startup OK |
| `platform.tracing.sampling.qa-header` | AGENT_BOOTSTRAP_KEEP | none | `SAMPLING_QA_HEADER` | WARN; startup OK |
| `platform.tracing.javaagent.sampling.drop-paths` | AGENT_BOOTSTRAP_KEEP | none | `SAMPLING_DROP_PATHS` | WARN; startup OK |
| `platform.tracing.javaagent.sampling.force-record-values` | AGENT_BOOTSTRAP_KEEP | none | `SAMPLING_FORCE_RECORD_VALUES` | WARN; startup OK |
| `platform.tracing.sampling.drop-paths` | DOCUMENTATION_ONLY / banned rename | none (P6 rename) | none (banned → javaagent name) | Source banlist `legacy-names-v1.json`; not in AuthorityGuard lists |
| `platform.tracing.sampling.force-record-header-values` | DOCUMENTATION_ONLY / banned rename | none | none | Source banlist |
| `platform.tracing.validation.enabled` | AGENT_BOOTSTRAP_KEEP | none | `VALIDATION_ENABLED` | WARN; startup OK |
| `platform.tracing.validation.strict` | AGENT_BOOTSTRAP_KEEP | none | `VALIDATION_STRICT` (legacy fallback for mode) | WARN; startup OK |
| `platform.tracing.validation.mode` | AGENT_BOOTSTRAP_KEEP | none | `VALIDATION_MODE` | WARN; startup OK |
| `platform.tracing.validation.strict-runtime-allowed` | AGENT_BOOTSTRAP_KEEP | none | `VALIDATION_STRICT_RUNTIME_ALLOWED` | WARN; startup OK |
| `platform.tracing.log-level` | OBSOLETE_SPRING_ONLY | none (deleted `OtelAgentLogLevel`) | **none** | **FAIL-FAST** via `TracingConfigurationAuthorityGuard` |
| `platform.tracing.policy.sampling.*` / `policy.validation.*` / `policy.diagnostics.log-level` | ACTIVE_FINAL_KEY | Category A Config Server / Actuator policy plane | Agent via accepted policy apply | Unchanged |
| `platform.tracing.policy.diagnostics.log-level` | ACTIVE_FINAL_KEY | policy leaf | Agent accepted policy | Replacement for obsolete Spring `log-level` mirror |

No `UNKNOWN_CONTRACT_DRIFT` rows for the audited minimum set.

---

## Per-key detail (minimum set)

### `platform.tracing.sampling.enabled`

| Field | Value |
|-------|-------|
| Inventory / disposition | Agent KEEP bootstrap; Spring mirror DELETE |
| Spring binding before | `TracingProperties.Sampling` |
| Spring binding after | unbound |
| Agent parser | `SamplingExtensionConfig` ← `ExtensionPropertyNames.SAMPLING_ENABLED` |
| Env / system property | Standard Spring/`ConfigProperties` relaxed binding; Agent config reader |
| `application.yml` effectiveness | Effective for **Agent bootstrap** when agent reads JVM/system/env overlay; **not** applied by Spring `TracingProperties` |
| Helm / Config Server | Not Category A policy leaves; do not use for Spring reconcile |
| Approved disposition | AGENT_BOOTSTRAP_KEEP |
| Remediation | `TracingConfigurationAuthorityGuard.warnOnAgentBootstrapKeepKeysPresentInSpringEnvironment` |

(Same pattern for `ratio`, `route-ratios`, `force-record-header`, `qa-header`.)

### `platform.tracing.validation.enabled` / `strict` / `mode` / `strict-runtime-allowed`

| Field | Value |
|-------|-------|
| Agent parser | `ValidationExtensionConfig` |
| Spring after | unbound |
| Disposition | AGENT_BOOTSTRAP_KEEP |
| Remediation | WARN list in AuthorityGuard |

### `platform.tracing.log-level`

| Field | Value |
|-------|-------|
| Spring before | `TracingProperties` / `OtelAgentLogLevel` |
| Spring after | unbound; type deleted |
| Agent reader | **absent** from `ExtensionPropertyNames` |
| Replacement | Category A `platform.tracing.policy.diagnostics.log-level` (runtime policy), not a Spring YAML mirror |
| Disposition | OBSOLETE_SPRING_ONLY |
| Remediation | `failFastOnObsoleteSpringOnlyKeys` → `TracingConfigurationGuardException` naming exact key |

---

## Mechanism choice

| Option | Used? | Why |
|--------|-------|-----|
| Extend `P6LegacyNameFailFastTest` / `legacy-names-v1.json` | No for runtime | Source banlist only; does not reject `application.yml` at startup |
| New EnvironmentPostProcessor / framework | No | Budget: 0 new production classes |
| Extend `TracingConfigurationAuthorityGuard.from(Environment)` | **Yes** | Existing startup guard already owns Environment inspection; HELM/CONFIG_SERVER/ACTUATOR all pass through it |

New production classes: **0**.

---

## Executable tests

| Test | Proves |
|------|--------|
| `TracingPropertiesBindingTest.obsoleteSpringOnlyLogLevelKeyFailsStartupFast` | OBSOLETE → fail-fast + key in message |
| `TracingPropertiesBindingTest.agentBootstrapKeepKeysInSpringEnvironmentSucceedButAreNotSilent` | AGENT KEEP → startup OK + WARN contains exact keys |
| `TracingPropertiesBindingTest.deletedCategoryASpringMirrorsAreNotBound` | Unbound (no Spring POJO bind) for sampling/validation without claiming silent ops success for production guard path |
| `P6LegacyNameFailFastTest` | Unchanged source banlist for rename/delete names |
