# Versioned Runtime State Refactor — Implementation Report

**Date:** 2026-07-02  
**Plan:** `versioned_runtime_state_refactor_5cd4e192.plan.md`

## 1. Summary

Completed relocation of runtime-state primitives from `api.config` to `api.runtime.state`, full removal of dead version chain in sampling (`SamplingPolicySnapshot` / `SamplingPolicyProperties`), decoupling of `SamplingPolicySnapshot` from the marker, ArchUnit guardrails, and deletion of legacy `api.config` package. Runtime behavior preserved.

## 2. Phases completed

| Phase | Status | Notes |
|-------|--------|-------|
| P0 Read-only audit | Done | No live wire/asset deps on `SamplingPolicySnapshot.version`; `api.config` only in src to be migrated |
| P1 Marker decoupling | Done | Removed `implements Versioned`; kept `version()` temporarily |
| P2 Dead version chain | Done | 5-arg snapshot, 5-component properties record |
| P3 Introduce new API | Done | `VersionedState`, `VersionedStateHolder`, `VersionedStateHolderTest` |
| P4 Migrate usages | Done | 3 states + 3 holders + bench javadoc |
| P5 Delete + guardrails | Done | `api.config` deleted; ArchUnit rules added; stale globs fixed |
| P6 Docs + acceptance | Done | ADR + this report |

## 3. External Opus usage

### Phase 1
- **generate_tests_with_opus:** Requested marker-decoupling tests.
- **Accepted:** None (wrong constructor assumptions).
- **Composer:** Added `doesNotImplementVersionedMarker` test manually.
- **generate_code_with_opus:** Not called (trivial 2-line production change).

### Phase 2–5 (post-hoc, 2026-07-02)

#### Phase 2 — dead version chain
- **generate_tests_with_opus:** NEEDS_MORE_CONTEXT (MCP input rejected twice; manual review used).
- **Manual grep:** 0 six-arg `SamplingPolicyProperties`, 0 `SamplingPolicySnapshot.version()`, factory tests already cover lenient routes + defaultRatio bounds.
- **Accepted:** None from Opus.
- **Rejected Opus concern:** `0L` was not a domain sentinel — it was a removed `version` field on the properties record; `validateDomain` unchanged semantically.
- **Composer:** No additional tests needed beyond existing `SamplingPolicySnapshotTest` + `SamplingPolicySnapshotFactoryTest`.

#### Phase 3 — VersionedStateHolder
- **generate_tests_with_opus:** OK — identified 7 contract gaps vs ported 7-test suite.
- **Accepted (added to `VersionedStateHolderTest`):**
  - `initialNullRejected`
  - `builderFatalErrorRethrown` / `validatorFatalErrorRethrown`
  - `builderInterruptedSetsFlagReturnsFalse` / `validatorInterruptedSetsFlagReturnsFalse` (interrupt flag + false, **not** rethrow — Opus G4a was wrong)
  - `versionDelegatesToCurrent`
  - `singleArgOverloadEquivalentToNullValidator`
- **Rejected:** Opus CAS-rebuild thread choreography (G8a/b) — redundant with existing 8×2000 concurrent test; Opus InterruptedException rethrow assumption.

#### Phase 4 — migration
- **generate_tests_with_opus:** NEEDS_MORE_CONTEXT (MCP input rejected).
- **Manual review:** `doesNotImplementVersionedMarker`, `SamplerStateHolderTest`, `SamplerStateCharacterizationTest`, `ValidationSnapshotTest.version()` sufficient; ArchUnit allowlist enforces implementer set.

#### Phase 5 — ArchUnit
- **generate_tests_with_opus:** OK — checklist of aspirational rules (mutable-collection-on-final-field, spring.factories scan, deferred `NO_RAW_VERSIONED_STATE_HOLDER_USAGE`).
- **Accepted:** None (rules already wired; see §7).
- **Rejected Opus concern:** `SAMPLING_MODEL` unwired — **false positive**; wired in `CorePolicyPackagePurityArchTest.samplingModelNotDependOnRuntimeState`. `APP_MODULES` in `AutoconfigureNoOtelExtensionMainDepArchTest`.

#### generate_code_with_opus (Phases 2–5 holistic)
- **Accepted:** Edge-case test gaps in `VersionedStateHolderTest` (implemented above).
- **Rejected:** Wire-format/equality regression on `SamplingPolicySnapshot` (not serialized); ABA test (not applicable to monotonic long version); package-absence vs emptiness (`NO_API_CONFIG` uses `dependOnClassesThat().resideInAPackage("..api.config..")` — sufficient).

## 4. Files changed (production)

- `api/runtime/state/VersionedState.java` (new)
- `api/runtime/state/VersionedStateHolder.java` (new)
- `api/config/Versioned.java` (deleted)
- `api/config/DomainConfigHolder.java` (deleted)
- `core/sampling/model/SamplingPolicySnapshot.java`
- `core/sampling/properties/SamplingPolicyProperties.java`
- `core/sampling/properties/SamplingPolicySnapshotFactory.java`
- `core/validation/ValidationSnapshot.java`
- `otel-extension/sampler/SamplerState.java`, `SamplerStateHolder.java`
- `otel-extension/scrubbing/ScrubbingSnapshot.java`, `ScrubbingPolicyHolder.java`
- `otel-extension/processor/ValidationPolicyHolder.java`
- `otel-extension/sampler/SamplerPolicyUpdate.java`
- `test/arch/ModuleTaxonomyArchRules.java`

## 5. Behavior invariants preserved

- CAS/LKG in holder (covered by `VersionedStateHolderTest` — full port + 7 post-hoc Opus edge-case tests)
- `SamplerState.version`, `ScrubbingSnapshot.version`, `ValidationSnapshot.version` unchanged
- `SamplerPolicyUpdate.buildNext(... previous.version() + 1 ...)` unchanged
- No JMX/wire schema changes

## 6. Tests run

- `:platform-tracing-api:test` — green
- `:platform-tracing-core:test` — green
- `:platform-tracing-otel-extension:test` — green
- `:platform-tracing-spring-boot-autoconfigure:test` — green
- `build` + `javadoc` — green
- `:platform-tracing-otel-extension:verifyAgentJarContents` — green
- `:platform-tracing-otel-extension:verifyExtensionSpiRegistration` — green

## 7. ArchUnit guardrails added

- `VERSIONED_STATE_IMPLS_ALLOWLIST` (FQN allowlist, test sources excluded)
- `SAMPLING_MODEL_NOT_DEPEND_ON_RUNTIME_STATE`
- `APP_MODULES_NOT_DEPEND_ON_RUNTIME_STATE`
- `NO_API_CONFIG_PACKAGE`
- `SNAPSHOT_FIELDS_ARE_FINAL` (+ scrubbing/validation)
- P5.1: `..core.sampling.config..` → `..core.sampling.properties..` in sampling layer rules

## 8. Acceptance grep results (live `src/`)

| Check | Result |
|-------|--------|
| `api.config` in production/test src | 0 (only ArchUnit rule text) |
| `DomainConfigHolder` | 0 |
| `implements Versioned` (legacy) | 0 |
| `SamplingPolicySnapshot.version()` | 0 |
| `new SamplingPolicyProperties(..., 1L)` | 0 |
| `implements VersionedState` | Only `SamplerState`, `ScrubbingSnapshot`, `ValidationSnapshot` |

## 9. Known exclusions / historical docs

Historical docs (`Migration Plan/*`, `*-options`, `*-characterization`, inventory snapshots) may still mention `api.config` — intentionally not rewritten.

## 10. Remaining risks

- Low: Javadoc in older architecture docs still reference old FQN (non-live).
- `NO_RAW_VERSIONED_STATE_HOLDER_USAGE` / holder type-arg allowlist deferred (ArchUnit generic metadata complexity); mitigated by FQN implementer allowlist + code review discipline.
