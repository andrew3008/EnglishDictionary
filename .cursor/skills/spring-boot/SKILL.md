---
name: spring-boot
description: Defines enterprise Spring Boot integration standards for the Platform Tracing repository. Use when Codex designs, reviews, refactors, implements, or validates starters, auto-configuration, ConfigurationProperties, bean conditions, servlet or WebFlux adapters, Actuator/JMX integration, optional classpaths, startup diagnostics, runtime modes, AOT/native-image behavior, dependency scopes, configuration metadata, or Spring context tests.
---

# Spring Boot Platform Standards

## Objective

Build predictable, minimal, diagnosable, and production-safe Spring Boot integration for Platform Tracing across servlet and reactive services.

The solution is pre-production. Breaking bean, property, package, starter, module, or auto-configuration changes are allowed when they materially improve architecture, runtime safety, adoption, dependency hygiene, operability, or production readiness.

Do not preserve accidental bean names, property aliases, hidden fallback behavior, duplicate execution paths, or obsolete package placement without an approved requirement.

Architects will not accept cosmetic Spring refactoring. Every substantial change must clarify ownership, remove coupling, simplify adoption, strengthen failure behavior, or add executable verification.

## Priority

When requirements conflict, prefer:

1. correct runtime composition
2. explicit module and bean ownership
3. deterministic conditions and startup behavior
4. safe defaults and fail-closed risky capabilities
5. optional-classpath isolation
6. simple adoption across many services
7. actionable diagnostics
8. servlet/reactive semantic parity
9. dependency and publication hygiene
10. AOT and configuration-cache compatibility
11. implementation convenience
12. compatibility with pre-production behavior

## Core workflow

1. Read applicable repository instructions, authoritative plans, ADRs, and Spring Boot version constraints.
2. Verify current and target module names from repository evidence.
3. Identify supported deployment modes, web stacks, optional technologies, and composition roots.
4. Inventory starters, auto-configurations, properties, bean names, conditions, dependencies, metadata, and application fixtures.
5. Load the applicable files from `references/`.
6. Trace bean creation for enabled, disabled, missing-class, missing-runtime, duplicate-runtime, invalid-property, and failure states.
7. Verify ownership of every bean and side effect.
8. Check that conditions are deterministic and applied at the narrowest safe boundary.
9. Check servlet and WebFlux behavior independently and verify shared semantics.
10. Check optional dependencies using minimal consumer classpaths.
11. Implement the smallest coherent composition change without moving domain logic into Spring modules.
12. Add ApplicationContextRunner, packaged-consumer, web-stack, architecture, and runtime E2E tests proportional to risk.
13. Verify configuration metadata, Javadoc, publication scopes, AOT/native behavior, and Gradle configuration cache where applicable.
14. Report bean/property delta, runtime matrix, tests, warnings, migration, and residual risks.

## Mandatory invariants

- Keep public API and domain/runtime implementation free of Spring annotations, `ApplicationContext`, bean conditions, servlet, WebFlux, Actuator, and `ConfigurationProperties`.
- Keep domain validation, sampling, scrubbing, protocols, and runtime policy outside auto-configuration.
- Register auto-configuration through supported Spring Boot mechanisms.
- Make bean creation deterministic, idempotent, and free of hidden duplicate runtimes.
- Use `@ConditionalOnMissingBean` only for intentional, documented extension points.
- Do not let user-replaceable beans bypass SDK/runtime ownership or security invariants.
- Bind startup configuration through typed validated properties; avoid scattered direct `Environment` reads.
- Treat property names, defaults, bean names, and condition semantics as external contracts.
- Keep disabled/no-op behavior explicit, observable, and semantically distinct from unavailable, incompatible, or failed runtime states.
- Do not silently degrade when mandatory security, runtime, or policy components are absent.
- Isolate optional technologies so their classes are never loaded when absent.
- Keep servlet-only types out of reactive paths and WebFlux-only types out of servlet paths.
- Preserve per-execution context across async/reactive boundaries without ThreadLocal-only behavior.
- Avoid network calls, threads, exporters, blocking waits, and irreversible mutations during bean-definition evaluation.
- Give startup side effects explicit ownership and lifecycle cleanup.
- Keep Actuator and JMX adapters separate from domain control semantics.
- Provide actionable diagnostics without leaking secrets or sensitive configuration.
- Verify actual dependency scopes and minimal starter consumer classpaths.
- Keep auto-configuration compatible with AOT/native-image constraints where supported.
- Add architecture gates preventing Spring leakage into API and implementation contracts.

## Reference selection

Read only the references relevant to the task, except for required combinations.

### Architecture, ownership, starters, and registration

Read [architecture-starters-and-registration.md](references/architecture-starters-and-registration.md).

Use it for module responsibilities, starter composition, web adapters, Agent extension ownership, and auto-configuration discovery.

### Conditions, properties, defaults, beans, and initialization

Read [conditions-properties-and-beans.md](references/conditions-properties-and-beans.md).

Use it for conditional registration, property namespaces, validation, defaults, bean design, overrides, and lazy/eager initialization.

### Runtime control, Actuator/JMX, diagnostics, and web stacks

Read [runtime-actuator-and-web-stacks.md](references/runtime-actuator-and-web-stacks.md).

Use it for runtime reconciliation, endpoints, management boundaries, starter observability, WebMVC/WebFlux separation, and optional classpaths.

### Dependencies, environment, startup side effects, and failure

Read [dependencies-startup-and-failure.md](references/dependencies-startup-and-failure.md).

Use it for Gradle scopes, direct environment access, initialization side effects, lifecycle, fail-fast, degradation, and disabled behavior.

### Tests, metadata, AOT, Gradle, documentation, and fitness rules

Read [testing-build-and-governance.md](references/testing-build-and-governance.md).

Use it for context matrices, packaged consumers, configuration metadata, native image/AOT, configuration cache, documentation, imports, architecture rules, anti-patterns, and verification commands.

## Required reference combinations

For every starter or auto-configuration refactoring, read:

1. `architecture-starters-and-registration.md`
2. `conditions-properties-and-beans.md`
3. `dependencies-startup-and-failure.md`
4. `testing-build-and-governance.md`

Also read `runtime-actuator-and-web-stacks.md` whenever the change affects runtime modes, Actuator, JMX, diagnostics, servlet, WebFlux, or an optional integration.

## Completion standard

Do not report completion until:

- module and bean ownership are explicit
- all relevant condition combinations are tested
- enabled, disabled, unavailable, incompatible, failed, and duplicate-runtime states are distinguished
- property names, defaults, validation, metadata, and diagnostics agree
- optional-classpath tests use realistic minimal consumers
- servlet and reactive paths are independently verified where affected
- no Spring or external implementation types leak into API/domain contracts
- dependency and publication scopes are verified
- required context, packaged, architecture, and E2E tests actually execute
- startup side effects and lifecycle cleanup are verified
- AOT/configuration-cache limitations are classified
- migration and residual risks are explicit

