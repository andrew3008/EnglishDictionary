# Platform Tracing — Core policy extraction readiness (PR-9B)

Evidence-based module boundary inventory and extraction candidate table after PR-6/7/8 runtime policy work.
**No class moves in PR-9B** — documentation and guardrails only.

Related:

- [runtime-policy-control-architecture.md](../tracing/runtime-policy-control-architecture.md)
- [platform-tracing-module-taxonomy.md](platform-tracing-module-taxonomy.md)
- [platform-tracing-preservation-first-migration-plan.md](platform-tracing-preservation-first-migration-plan.md)

## PR-9B status

```text
PR-9B_STATUS=COMPLETED_DOCS_AND_GUARDS
Safe class moves: none (deferred to PR-9C+)
Behavior changes: none
```

## PR-9C status

```text
PR-9C_STATUS=COMPLETED_VALIDATION_CORE_EXTRACTION
Moved to core.validation: ValidationSnapshot, ValidationPolicyUpdate
Stayed agent-side: ValidationPolicyHolder, ValidatingSpanProcessor, PlatformTracingControl
Scrubbing extraction: deferred (RuleExecutionWrapper coupling)
Behavior changes: none
```

## PR-9D status

```text
PR-9D_STATUS=COMPLETED_SCRUBBING_EXTRACTION_PREP
Pure helper extracted (agent-side, core-ready): ScrubbingRuleResolution, ScrubbingRuleResolutionResult
BuiltInSensitiveDataRules.lookup() added (name → enum, no rule instantiation)
ScrubbingPolicyUpdate delegates rule-name validation/selection to pure helper
Stayed agent-side: ScrubbingSnapshot, RuleExecutionWrapper, ScrubbingPolicyHolder, ScrubbingSpanProcessor
core.scrubbing move: deferred (registry still coupled to BuiltInSensitiveDataRules enum + rule classes)
Behavior changes: none
```

---

## 1. Module boundary inventory

| Module | Current responsibility | Runtime policy role |
|--------|------------------------|---------------------|
| `platform-tracing-api` | Public API, SPI, `DomainConfigHolder` / `Versioned` shared CAS primitive | No runtime control logic; control protocol in `api.control.protocol` (JDK-only) |
| `platform-tracing-core` | PlatformTracing facade, typed span builders, **pure sampling policy** (`core.sampling.*`), **pure validation policy** (`core.validation.*`), semconv | Sampling **decision engine** extracted (PR-6B); validation snapshot/update extracted (PR-9C); facade still OTel API–coupled |
| `platform-tracing-otel-extension` | Agent SPI, samplers, processors, holders/snapshots, JMX `PlatformTracingControl`, scrubbing engine | Agent runtime source of truth for all three policy domains |
| `platform-tracing-spring-boot-autoconfigure` | `TracingProperties`, `RuntimeConfigApplier`, `*RuntimeConfig`, `SamplingControlClient`, Actuator, refresh | Spring input/reconciliation only |
| `platform-tracing-autoconfigure-webmvc` / `-webflux` | Stack-specific wiring | No policy holders |
| Starters (servlet/reactive) | BOM + dependency aggregation | No implementation |
| `platform-tracing-test` | Harnesses, shared ArchUnit rules | Test-only |
| `platform-tracing-bench` | JMH | Bench-only |
| `platform-tracing-e2e-tests` | E2E chain | Test-only |
| `platform-tracing-perf-tests` | Macro perf M0–M10 | Test-only |

### Gradle dependency facts (verified PR-9B)

- `otel-extension` → `implementation core` (sampling policy in agent JAR via `verifyAgentJarContents`)
- `spring-boot-autoconfigure` → `api core`; **main** must not depend on `otel-extension` impl (ArchUnit FF)
- `core` main: **no** Spring, **no** JMX; `opentelemetry-api` allowed (documented MIGRATION_RISK for facade)
- `core.sampling.*`: **no** OTel/Spring/JMX imports (ArchUnit `CORE_POLICY_PACKAGES_NO_OTEL_OR_SPRING`)
- `core.validation.*`: **no** OTel/Spring/JMX imports (PR-9C; same ArchUnit rule)

---

## 2. Boundary violation scan

| Category | Scan result | Risk | Action PR-9B |
|----------|-------------|------|--------------|
| **A. Spring in core main** | None found | — | Guardrail exists (`CORE_POLICY_PACKAGES_*`) |
| **A. Spring in otel-extension main** | None (`ExtensionNoSpringDependencyArchTest`) | — | Keep existing test |
| **B. JMX in core main** | None found | — | **Added** `CORE_MAIN_NO_JMX` ArchUnit rule |
| **B. JMX in otel-extension** | Only `jmx/` + `jmx/spike/` packages | OK | Spike isolated by FF-08 |
| **C. OTel in core policy** | `core.sampling` clean; facade/span builders use OTel API | MEDIUM (known) | Documented; not policy extraction blocker for sampling engine |
| **D. Runtime control leakage** | `RuntimeConfigApplier` only in autoconfigure; `PlatformTracingControl` only in extension | OK | Documented |
| **E. Spring DTO as SoT** | Holders in agent; Spring views are thin reconcilers | OK | Documented in PR-9A |
| **F. API pollution** | No `*RuntimeConfig` in api; wire package JDK-only (FF-01/02) | OK | Keep FF |

No blocking violations requiring immediate refactor.

---

## 3. Extraction candidate table

| Class / package | Current module | Responsibility | Target module | Blockers | Risk | Recommended PR | Tests |
|-----------------|----------------|----------------|---------------|----------|------|----------------|-------|
| `core.sampling.*` | **core** | Pure sampling policy engine + rules | core (done) | — | LOW | ✅ PR-6B | `SamplingPolicyEngineTest` |
| `api.config.DomainConfigHolder` | api | CAS/LKG primitive | api (stay) | Shared across CL | LOW | — | holder tests in extension |
| `scrubbing.engine.MergeEngine` | otel-extension | Pure merge semantics | core.scrubbing | `RuleExecutionWrapper` OTel-path coupling | MEDIUM | PR-9C | `MergeEngineTest` |
| `scrubbing.policy.ScrubbingRuleResolution` | otel-extension | Pure rule-name validate/select | **core.scrubbing** (future) | registry enum + rule classes in extension | LOW | ✅ PR-9D prep | `ScrubbingRuleResolutionTest` |
| `scrubbing.ScrubbingSnapshot` compile | otel-extension | Snapshot + regex compile | extension (stay) | `RuleExecutionWrapper`, regex compile in rule ctors | MEDIUM | PR-10+ | characterization tests |
| `scrubbing.ScrubbingPolicyUpdate` | otel-extension | Validate/build next snapshot | extension (stay); delegates selection to `ScrubbingRuleResolution` | compile path agent-side | LOW | ✅ PR-9D prep | `ScrubbingPolicyUpdateTest` |
| `processor.ValidationSnapshot` | **core** (`core.validation`) | Immutable validation flags | core.validation | ✅ moved PR-9C | LOW | ✅ PR-9C | `ValidationSnapshotTest` (core) |
| `processor.ValidationPolicyUpdate` | **core** (`core.validation`) | buildNext + source normalize | core.validation | ✅ moved PR-9C | LOW | ✅ PR-9C | `ValidationPolicyUpdateTest` (core) |
| `processor.ValidationPolicyHolder` | otel-extension | CAS publish + rate-limited LKG warn | extension (stay) | agent runtime + diagnostics | LOW | — | `ValidationPolicyHolderTest` |
| `sampler.SamplerState` / holder | otel-extension | OTel adapter state + CAS | extension (stay) | OTel sampler types | HIGH | — | JMX/runtime tests |
| `processor.*SpanProcessor` | otel-extension | OTel `SpanProcessor` adapters | extension (stay) | OTel SDK | HIGH | — | characterization |
| `jmx.PlatformTracingControl` | otel-extension | JMX bridge | extension (stay) | JMX | HIGH | — | `*RuntimeUpdateJmxTest` |
| `*RuntimeConfig` (Spring) | autoconfigure | Reconciliation views | autoconfigure (stay) | Spring | HIGH | — | `RuntimeConfigApplierTest` |
| `SamplingControlClient` | autoconfigure | JMX client (all domains) | autoconfigure; rename PR-9D | 35+ refs | MEDIUM | PR-9D | client + applier tests |
| `EnrichingSpanProcessor` policy | otel-extension | Enrichment (no runtime schema yet) | TBD | no PR-6/7/8 schema | HIGH | PR-10A+ | enrichment tests |

---

## 4. Safe moves performed (PR-9B / PR-9C)

**PR-9B:** None — inventory and guardrails only.

**PR-9C (validation only):**

| Class | From | To |
|-------|------|-----|
| `ValidationSnapshot` | `otel-extension.processor` | `core.validation` |
| `ValidationPolicyUpdate` | `otel-extension.processor` | `core.validation` |

**PR-9D (scrubbing prep — no core moves):**

| Class | Location | Notes |
|-------|----------|-------|
| `ScrubbingRuleResolution` | `otel-extension.scrubbing.policy` | Pure name validate/select; core-ready |
| `ScrubbingRuleResolutionResult` | `otel-extension.scrubbing.policy` | Resolution result value object |
| `BuiltInSensitiveDataRules.lookup()` | otel-extension | Pure enum lookup without `create()` |

**Intentionally agent-side:** `ScrubbingSnapshot`, `RuleExecutionWrapper`, holders, processors, JMX bridge.

**ScrubbingSnapshot blockers for core:** compiled `RuleExecutionWrapper` list, regex compile in rule constructors, `RuleCircuitBreaker` wiring.

---

## 5. Architecture guardrails added (PR-9B)

| Rule | Module | Purpose |
|------|--------|---------|
| `CORE_POLICY_PACKAGES_NO_OTEL_OR_SPRING` (extended) | core | Also forbids `javax.management..` in future `core.{sampling,scrubbing,validation,enrichment}` |
| `CORE_MAIN_NO_JMX` | core | Entire core main must not depend on JMX |

Existing guardrails unchanged: autoconfigure↛otel-extension main, extension↛Spring, api wire JDK-only, FF-09 production control plane.

---

## 6. Runtime policy ownership (summary)

| Layer | Owns | Does not own |
|-------|------|--------------|
| **core** | Pure policy decisions (sampling engine; validation snapshot/update) | Runtime holders on hot path, JMX, Spring properties |
| **otel-extension** | Holders, snapshots, processors, samplers, JMX, agent config | Spring reconciliation |
| **spring-autoconfigure** | Properties, reconciler, JMX client, Actuator read aggregation | Authoritative runtime state |
| **api** | Stable contracts, `DomainConfigHolder`, SPI | Domain-specific policy implementations |

Write path and read path: see [runtime-policy-control-architecture.md](../tracing/runtime-policy-control-architecture.md).

---

## 7. Deferred moves and follow-ups

1. **PR-9E / PR-10** — Move `ScrubbingRuleResolution` + JDK-only built-in name registry to `core.scrubbing`; keep rule impls + `RuleExecutionWrapper` in extension.
2. **PR-9D (rename)** — Rename `SamplingControlClient` → `PlatformTracingControlClient` (optional `@Deprecated` alias period).
3. **Scrubbing snapshot split** — extract compile adapter after core registry move.
4. **PR-10A** — Enrichment characterization / runtime foundation (separate domain).
5. **Scrubbing live rule names** — optional MBean read getter (PR-8D follow-up).
6. **Core facade OTel coupling** — separate from policy extraction; documented MIGRATION_RISK in module taxonomy.
7. **Phase 17** — Performance evidence / full JMH baseline before large hot-path moves.

---

## 8. Risks

- Moving scrubbing snapshot compile without splitting `RuleExecutionWrapper` risks agent JAR bloat or circular deps — extract merge/decision first.
- Renaming `SamplingControlClient` touches docs, metrics binders, and migration plan references — dedicated PR only.
- `core` still exposes `opentelemetry-api` on its API classpath for span facade — distinct from pure policy package rules.
