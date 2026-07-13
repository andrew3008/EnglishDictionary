# Platform Tracing — Module taxonomy and dependency guardrails

## Document status

```text
Status: PR-1 — Module taxonomy documentation + dependency guardrails
Scope: Document module categories and enforce dependency boundaries via ArchUnit / Gradle smoke.
This PR does not collapse modules.
This PR does not move production classes.
This PR does not change runtime behavior.
```

**Related documents:**

- [platform-tracing-preservation-first-migration-plan.md](platform-tracing-preservation-first-migration-plan.md)
- [platform-tracing-current-codebase-inventory.md](platform-tracing-current-codebase-inventory.md)
- [pr-0-baseline-lock.md](pr-0-baseline-lock.md)
- [pr-0-baseline-results-2026-06-12-complete-or-waived.md](pr-0-baseline-results-2026-06-12-complete-or-waived.md)

---

## 1. Purpose

PR-1 fixes the **module taxonomy** and **developer-facing dependency model** before any extraction or refactoring (PR-5+). It adds architecture guardrails so new dependency violations fail in CI, while **known pre-existing violations** remain documented—not forcibly refactored in PR-1.

PR-0 baseline status: `COMPLETE_WITH_WAIVERS`. Full JMH baseline is **deferred** and **required before PR-5/PR-6/PR-7/PR-8** (hot-path extraction). PR-1 may proceed because it is documentation and guardrails only.

---

## 2. Module categories

### Public consumption modules

Application teams depend on these (via BOM + exactly one starter):

| Module | Role |
|--------|------|
| `platform-tracing-bom` | Version alignment (OTel, Spring Boot, platform modules) |
| `platform-tracing-api` | Public API: interfaces, annotations, SPI extension points |
| `platform-tracing-spring-boot-starter-servlet` | Servlet / Spring MVC entry point |
| `platform-tracing-spring-boot-starter-reactive` | WebFlux / Reactive entry point |

### Internal runtime modules

Not for direct application dependency:

| Module | Role |
|--------|------|
| `platform-tracing-core` | TraceOperations facade over OTel API (current state: OTel-coupled) |
| `platform-tracing-otel-extension` | OTel Java Agent extension (Sampler, SpanProcessor, scrubbing runtime) |
| `platform-tracing-spring-boot-autoconfigure` | Spring Boot autoconfigure (properties, actuator, MDC, core wiring) |
| `platform-tracing-autoconfigure-webmvc` | Servlet-specific autoconfigure |
| `platform-tracing-autoconfigure-webflux` | Reactive-specific autoconfigure |

### Verification / evidence modules

Internal CI/lab tools; not published for application runtime:

| Module | Role |
|--------|------|
| `platform-tracing-test` | Test utilities + shared ArchUnit rules |
| `platform-tracing-e2e-tests` | E2E chain (SDK → Collector → Jaeger) |
| `platform-tracing-bench` | JMH micro-benchmarks |
| `platform-tracing-perf-tests` | Macro perf scenarios M0–M10 |
| `platform-tracing-perf-harness` | Hybrid SMOKE/REFERENCE macro evidence harness (PR-9H-C+) |

### Supporting modules

| Module | Role |
|--------|------|
| `platform-tracing-collector-config` | Versioned OTel Collector YAML (SRE/infrastructure) |

### Requires manual review

| Module | Status |
|--------|--------|
| `platform-tracing-semconv-lint` | Scaffold present in tree; **commented out** in `settings.gradle` — not active in build |

### Composite build (conditional)

| Include | Condition |
|---------|-----------|
| `spring-boot-platform-starters-master` (composite) | When `../Platform_SpringBoot_Starters/...` exists on disk |

---

## 3. Developer-facing dependency model

```text
Application teams use BOM + exactly one starter.

Servlet / Spring MVC  → platform-tracing-spring-boot-starter-servlet
WebFlux / Reactive    → platform-tracing-spring-boot-starter-reactive

Application teams must NOT directly depend on internal runtime modules:
  platform-tracing-core
  platform-tracing-otel-extension
  platform-tracing-spring-boot-autoconfigure
  platform-tracing-autoconfigure-webmvc
  platform-tracing-autoconfigure-webflux
```

Gradle coordinates are managed via `platform-tracing-bom`.

---

## 4. Allowed dependency directions (high level)

```text
Application
  → BOM
  → exactly one starter (servlet OR reactive)
    → autoconfigure (+ webmvc OR webflux)
      → core, api
    → spring-boot-starter-web OR spring-boot-starter-webflux

Agent (separate CL)
  → platform-tracing-otel-extension (via javaagent.extensions)

Verification modules
  → may depend on production modules for testing (testImplementation)
  → must not become transitive runtime deps of starters
```

Starters must not expose `platform-tracing-otel-extension` on application compile/runtime classpath (verified by `pr0StarterDependencySmoke`).

---

## 5. Forbidden dependency directions

| From | To | Reason |
|------|-----|--------|
| Application | `otel-extension`, `core`, `autoconfigure*` directly | Use starter + BOM only |
| `platform-tracing-spring-boot-autoconfigure` main | `platform-tracing-otel-extension` impl classes | App CL vs Agent CL boundary |
| `platform-tracing-otel-extension` main | `org.springframework.*` | Extension loads without Spring |
| `platform-tracing-autoconfigure-webmvc` main | WebFlux / Reactor types | Stack isolation |
| `platform-tracing-autoconfigure-webflux` main | Servlet / MVC types | Stack isolation |
| Future `core.{sampling,scrubbing,validation,enrichment}` | `io.opentelemetry.*`, `org.springframework.*`, `javax.management.*` | Clean Core Hybrid pure policy target (PR-9B: `core.sampling` active; JMX added) |

---

## 6. Guardrails (PR-1)

### Gradle verification

| Task | What it verifies |
|------|------------------|
| `./gradlew pr0StarterDependencySmoke` | Servlet/reactive starter dependency expectations |
| `./gradlew pr1ModuleTaxonomyVerify` | ArchUnit taxonomy tests + starter smoke |
| `./gradlew pr0BaselineLock` | Full automated PR-0 gate (includes smoke + unit tests) |

### ArchUnit rules (`ModuleTaxonomyArchRules`)

| Rule | Test class | Module |
|------|------------|--------|
| `AUTOCONFIGURE_MAIN_NO_OTEL_EXTENSION_IMPL` | `AutoconfigureNoOtelExtensionMainDepArchTest` | `spring-boot-autoconfigure` |
| `WEBMVC_MAIN_NO_WEBFLUX_STACK` | `WebMvcNoWebFluxMainDepArchTest` | `autoconfigure-webmvc` |
| `WEBFLUX_MAIN_NO_SERVLET_STACK` | `WebFluxNoServletMainDepArchTest` | `autoconfigure-webflux` |
| `CORE_POLICY_PACKAGES_NO_OTEL_OR_SPRING` | `CorePolicyPackagePurityArchTest` | `core` (`core.sampling` active; +JMX PR-9B) |
| `CORE_MAIN_NO_JMX` | `CorePolicyPackagePurityArchTest` | `core` (PR-9B) |

PR-9B extraction inventory: [platform-tracing-core-extraction-readiness.md](platform-tracing-core-extraction-readiness.md).

### Existing guardrails (reused, not duplicated)

| Check | Location |
|-------|----------|
| No Spring in otel-extension | `ExtensionNoSpringDependencyArchTest` |
| Web stack bean isolation | `WebStackIsolationTest` |
| OTel direct integration | `OtelDirectIntegrationArchTest`, `OtelDirectIntegrationRules` |
| Starter smoke | `pr0StarterDependencySmoke` |

Source: `platform-tracing-test/src/main/java/.../ModuleTaxonomyArchRules.java`

---

## 7. Known pre-existing violations (MIGRATION_RISK)

Documented; **not** fixed in PR-1. Forward guardrails prevent **new** violations.

| Violation | Location | PR-1 action |
|-----------|----------|-------------|
| `platform-tracing-core` has `api opentelemetry-api` | `platform-tracing-core/build.gradle` | Documented; extraction deferred to PR-6+ |
| `platform-tracing-api` may have `compileOnly` OTel | `platform-tracing-api` | Documented; not enforced globally yet |
| Core facade is OTel-coupled, not pure policy | `DefaultTraceOperations`, span builders | Documented; `CorePolicyPackagePurityArchTest` applies only to **new** policy packages |
| Actuator MUTATION without prod guard | `TracingActuatorEndpoint` | Documented; PR-11 |
| `TracingConfigReconciler` not in codebase | target-only | PR-10 |

---

## 8. Rules for future PRs

| PR range | Allowed without full JMH baseline |
|----------|-----------------------------------|
| PR-1 – PR-4 | Documentation, guardrails, wire schema spikes — **no hot-path moves** |
| PR-5+ | Test duplication only (still no extraction) |
| PR-6 – PR-8 | **Blocked** until full JMH baseline captured (see PR-0 waivers) |
| PR-10+ | Reconciler / Actuator guards — preserve existing tests |

Do not add:

- `platform-tracing-spring-boot-autoconfigure` → `platform-tracing-otel-extension` on **main** runtime classpath
- Spring dependencies in `platform-tracing-otel-extension` main sources
- Direct application dependencies on internal runtime modules

---

## 9. Relation to PR-0 baseline

PR-0 captured:

- Workspace fingerprint (non-git baseline identity)
- Dependency snapshot (`docs/architecture/baselines/pr-0/dependency-snapshot/`)
- Starter smoke expectations
- `RuntimeSamplingControlSmokeTest` PASS
- `performanceReleaseGate` FAIL_EXPECTED_PENDING_BUDGETS
- Official macro perf session `2026-06-10_official`

PR-1 adds taxonomy documentation and ArchUnit guardrails **on top of** PR-0 artifacts. Re-run `./gradlew pr0BaselineLock` after PR-1 to confirm no regression.

---

## 10. Explicit non-goals (PR-1)

```text
This PR does not collapse modules.
This PR does not move production classes.
This PR does not change runtime behavior.
This PR does not start sampling/scrubbing/validation/enrichment extraction.
Full JMH baseline remains required before PR-5/PR-6/PR-7/PR-8.
```

---

*PR-1 scope: taxonomy + guardrails only. Proceed to PR-2 (wire schema) per preservation-first migration plan.*
