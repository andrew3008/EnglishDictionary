# Platform Tracing — Architecture fitness functions (PR-4)

## Document status

```text
Status: PR-4 — Architecture fitness functions / dependency enforcement
Scope: ArchUnit + Gradle verification; no production behavior changes.
PR-4 does not refactor code.
PR-4 does not move classes.
PR-4 does not collapse modules.
PR-4 does not migrate runtime JMX to Map wire.
PR-4 does not implement TracingConfigReconciler.
```

**Related documents:**

- [platform-tracing-module-taxonomy.md](platform-tracing-module-taxonomy.md) — PR-1
- [platform-tracing-wire-schema-v1.md](platform-tracing-wire-schema-v1.md) — PR-2
- [ADR-jmx-wire-map-contract.md](ADR-jmx-wire-map-contract.md) — PR-3
- [platform-tracing-preservation-first-migration-plan.md](platform-tracing-preservation-first-migration-plan.md)

---

## 1. Purpose

PR-4 formalizes **architecture fitness functions** that protect decisions from PR-1 (module taxonomy), PR-2 (wire schema), and PR-3 (JMX Map wire spike). Rules enforce forward boundaries; known legacy violations remain **MIGRATION_RISK** until extraction PRs (PR-6+).

> **Update (protocol refactor):** production control contract lives in `api.control.protocol` with unified `TracingControlProtocol*` type names. Entry point: `TracingControlProtocol.current()`. Legacy `api.control.wire` package removed.

> **Update (JMX spike production purge / NEW_HYBRID):** the PR-3 JMX wire spike transport was removed from production code. FF-07 and FF-08 (which guarded the production spike MBean and its property gate) are **retired** and replaced by FF-08b, a production ban on `..spike..` packages and `*Spike*` class names in `src/main`. The protocol validator (`TracingControlProtocolValidator`) remains a stable contract; the JMX round-trip harness now lives only in `platform-tracing-e2e-tests/src/jmxWireExtension` as a test-only OTel extension.

---

## 2. Scope of PR-4

| In scope | Out of scope |
|----------|--------------|
| ArchUnit rules in `platform-tracing-test` | Hot-path refactoring |
| Module-level ArchUnit tests | Fixing `platform-tracing-core` OTel coupling |
| `pr4ArchitectureFitnessVerify` Gradle task | Map wire production migration |
| Fitness documentation | `TracingConfigReconciler` (PR-10) |
| Spike / production-path guardrails | Full JMH baseline execution |

---

## 3. Gradle verification

| Task | Role |
|------|------|
| `./gradlew pr4ArchitectureFitnessVerify` | PR-4 gate: `pr1ModuleTaxonomyVerify` + `:platform-tracing-api:test` |
| `./gradlew pr1ModuleTaxonomyVerify` | PR-1 taxonomy + starter smoke (included in PR-4) |
| `./gradlew pr0StarterDependencySmoke` | FF-10 starter dependency expectations |

---

## 4. Fitness function table

| ID | Fitness function | Module | Rule / test / task | Status |
|----|------------------|--------|-------------------|--------|
| **FF-01** | API protocol package JDK-only | `platform-tracing-api` | `ArchitectureFitnessArchRules.API_PROTOCOL_PACKAGE_JDK_ONLY` → `ApiProtocolPackagePurityArchTest` | **ENFORCED** |
| **FF-02** | API protocol no implementation deps | `platform-tracing-api` | `API_PROTOCOL_NO_IMPLEMENTATION_MODULES` → `ApiProtocolPackagePurityArchTest`, `ApiProtocolNoImplementationDependencyArchTest` | **ENFORCED** |
| **FF-01b** | Protocol API unified naming prefix | `platform-tracing-api` | `PROTOCOL_API_TYPES_USE_UNIFIED_PREFIX` → `ApiProtocolPackagePurityArchTest` | **ENFORCED** |
| **FF-01c** | Protocol API no Wire in type names | `platform-tracing-api` | `PROTOCOL_API_TYPES_DO_NOT_USE_WIRE_NAMING` → `ApiProtocolPackagePurityArchTest` | **ENFORCED** |
| **FF-03** | No Spring in otel-extension | `platform-tracing-otel-extension` | `ExtensionNoSpringDependencyArchTest` (PR-1 era) | **ENFORCED** (reused) |
| **FF-04** | Autoconfigure → no otel-extension impl | `platform-tracing-spring-boot-autoconfigure` | `ModuleTaxonomyArchRules.AUTOCONFIGURE_MAIN_NO_OTEL_EXTENSION_IMPL` → `AutoconfigureNoOtelExtensionMainDepArchTest` | **ENFORCED** (reused) |
| **FF-05** | WebMVC / WebFlux isolation | webmvc, webflux | `WEBMVC_MAIN_NO_WEBFLUX_STACK`, `WEBFLUX_MAIN_NO_SERVLET_STACK` → PR-1 ArchTests | **ENFORCED** (reused) |
| **FF-06** | Core future policy purity | `platform-tracing-core` | `CORE_POLICY_PACKAGES_NO_OTEL_OR_SPRING` → `CorePolicyPackagePurityArchTest` | **FORWARD_LOOKING** |
| **FF-07** | ~~Spike MBean property-gated~~ | `platform-tracing-otel-extension` | ~~`SpikeMBeanPropertyGateArchTest`~~ | **RETIRED** (production spike removed; gate `platform.tracing.spike.jmx.wire` deleted) |
| **FF-08** | ~~Spike MBean validation-only~~ | `platform-tracing-otel-extension` | ~~`SPIKE_MBEAN_VALIDATION_ONLY` → `SpikeMBeanValidationOnlyArchTest`~~ | **RETIRED** (production spike MBean removed) |
| **FF-08b** | No spike code in production | `platform-tracing-otel-extension` | `SafeBoundaryArchTest.no_jmx_spike_package_in_production`, `no_spike_named_classes_in_production` | **ENFORCED** (replaces FF-07/FF-08 with a production ban) |
| **FF-09** | Production not on Map wire validator | `platform-tracing-spring-boot-autoconfigure` | `ProductionControlPlaneNotMigratedArchTest` (`PRODUCTION_AUTOCONFIGURE_NO_WIRE_VALIDATOR`) | **ENFORCED** |
| **FF-10** | Starters expose no otel-extension | starters | `pr0StarterDependencySmoke` | **ENFORCED** (reused) |
| **FF-11** | No module collapse | repo | [platform-tracing-module-taxonomy.md](platform-tracing-module-taxonomy.md) | **DOCUMENTED_ONLY** |
| **FF-12** | JMH gate before extraction | PR-5/6/7/8 | [pr-0-baseline-results-2026-06-12-complete-or-waived.md](pr-0-baseline-results-2026-06-12-complete-or-waived.md) | **DOCUMENTED_ONLY** |

Shared rules: `platform-tracing-test/src/main/java/.../ArchitectureFitnessArchRules.java`

---

## 5. Known pre-existing violations (MIGRATION_RISK)

Documented; **not** fixed in PR-4.

| Violation | Location | PR-4 action |
|-----------|----------|-------------|
| `platform-tracing-core` has `api opentelemetry-api` | `platform-tracing-core/build.gradle` | FF-06 forward-looking only; global core OTel ban deferred |
| `platform-tracing-api` has `compileOnly` OTel | `platform-tracing-api` | Outside `api.control.protocol`; documented |
| Core facade OTel-coupled (`DefaultPlatformTracing`) | `platform-tracing-core` | Extraction PR-6+ |
| Actuator MUTATION prod guard | `TracingActuatorEndpoint` | PR-11 |
| `TracingConfigReconciler` not in codebase | target-only | PR-10 |
| Full JMH baseline deferred | PR-0 waivers | FF-12 documented gate before PR-5/6/7/8 |

---

## 6. Protocol package note (FF-01)

`space.br1440.platform.tracing.api.control.protocol` uses **plain JDK types** only. `javax.management.openmbean` is **not** used in the API module — payloads are `Map<String, Object>` with open-type values validated in Java. All public top-level production types use the `TracingControlProtocol*` prefix; `Wire` does not appear in production type names.

---

## 7. Explicit non-goals

```text
PR-4 does not refactor code.
PR-4 does not move classes.
PR-4 does not collapse modules.
PR-4 does not migrate runtime JMX to Map wire.
PR-4 does not implement TracingConfigReconciler.
Full JMH baseline remains required before extraction PRs.
```

---

*PR-4 complete. Proceed to PR-5 (test duplication) only after full JMH baseline per PR-0 waivers.*
