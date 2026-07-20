---
name: backend-engineering
description: Defines umbrella backend-engineering standards for the enterprise Platform Tracing repository. Use when Codex designs, reviews, refactors, implements, or validates cross-cutting Java changes spanning architecture, modules, public API, runtime control, propagation, sampling, scrubbing, observability, security, Spring Boot, reactive/concurrent execution, lifecycle, performance, distributed systems, Kubernetes, dependencies, testing, Gradle, documentation, or production readiness.
---

# Platform Tracing Backend Engineering Standards

## Objective

Coordinate cross-cutting backend work across Platform Tracing while preserving explicit ownership, runtime safety, public API integrity, security, operability, and executable evidence.

This is the umbrella engineering skill. Use specialized skills for detailed domain rules.

The solution is pre-production. Breaking source, binary, package, bean, configuration, protocol, SPI, module, artifact, and build changes are allowed when they materially improve correctness, architecture, security, operability, performance, testability, or adoption.

Do not preserve accidental APIs, ambiguous names, obsolete packages, false extension points, deprecated bridges, aliases, duplicate execution paths, unsafe defaults, or stale tests without an approved requirement.

Architects will not accept cosmetic refactoring. Every substantial backend change must close a concrete architecture, correctness, security, operability, performance, or adoption defect.

## Skill coordination

Use the most specific applicable skill together with this umbrella skill:

- API and SPI design: `platform-tracing-api-standards`
- Spring Boot integration: `spring-boot`
- security and privacy: `security-review`
- telemetry semantics and diagnostics: `observability`
- general test strategy: `testing`
- Docker-backed integration and E2E: `testcontainers`
- Gradle, dependencies, modules, and publications: `gradle`
- Redis/KeyDB integration: `redis`
- evidence-based final audit: `post-implementation-audit`

Prefer the specialized skill for its domain, provided it does not violate repository architecture or the pre-production policy defined here.

Do not copy a solution from one domain into another without verifying ownership and runtime semantics.

## Priority

When requirements conflict, prefer:

1. correctness
2. security and privacy
3. runtime-state safety
4. module and classloader boundaries
5. public API and SPI integrity
6. deterministic behavior
7. dependency and publication correctness
8. executable verification
9. operator diagnostics
10. bounded resources and telemetry
11. reactive and concurrency correctness
12. startup and hot-path performance
13. maintainability
14. developer ergonomics
15. compatibility with pre-production behavior

## Core workflow

1. Read applicable repository instructions, authoritative plans, ADRs, previous audits, and specialized skills.
2. Inspect Git state, module graph, source sets, supported modes, and affected runtime paths.
3. Establish repository facts before accepting plan or implementation claims.
4. Identify owners, consumers, trust boundaries, classloaders, processes, lifecycle, and operational responsibilities.
5. Inventory affected API, SPI, packages, beans, properties, protocols, dependencies, publications, tests, and documentation.
6. Load the applicable files from `references/`.
7. Describe the current defect and the intended non-cosmetic improvement.
8. Evaluate success, disabled, unavailable, incompatible, invalid, degraded, failed, concurrent, shutdown, recovery, and rollback paths.
9. Implement the smallest coherent architectural slice.
10. Remove obsolete paths rather than adding compatibility layering without a requirement.
11. Update all repository consumers, generated code, examples, docs, metadata, and architecture rules.
12. Run narrow verification first, then required broader and packaged gates.
13. Distinguish compiled, skipped, cached, executed, passed, failed, and environment-blocked evidence.
14. Perform a post-implementation audit for large or generated changes.
15. Report decisions, facts, changes, tests, risks, and the exact next checkpoint.

## Mandatory invariants

- Verify repository facts before changing architecture.
- Keep module dependency direction explicit and executable.
- Do not leak Spring, OpenTelemetry SDK/Agent, JMX, transport, Redis, or implementation types into application-facing API.
- Give every public type and extension point a concrete consumer and owner.
- Use immutable values and explicit invariants at public and protocol boundaries.
- Separate structural decoding, domain validation, policy, and state application.
- Keep runtime state transitions atomic, observable, and concurrency-safe.
- Do not use shared mutable statics or unsafe cross-classloader injection.
- Preserve context across async/reactive boundaries without ThreadLocal-only behavior.
- Define lifecycle ownership, startup, shutdown, cleanup, and rollback.
- Keep observability bounded, safe, and subordinate to business correctness.
- Do not expose secrets or PII through telemetry, errors, logs, metrics, diagnostics, or control payloads.
- Make disabled/no-op behavior intentional and distinct from unavailable, incompatible, failed, or insecure states.
- Use bounded retries, queues, caches, collections, payloads, timeouts, and cardinality.
- Keep dependencies and publications minimal, intentional, and runtime-complete.
- Compile every affected source set and test actual packaged artifacts where packaging matters.
- Do not report opt-in E2E as passed when it was skipped.
- Do not weaken architecture gates or regenerate baselines merely to hide violations.
- Preserve unrelated user changes and avoid broad formatting/import churn.
- Do not commit, push, deploy, or mutate external systems unless explicitly requested.

## Reference selection

Read only references relevant to the task, except for required combinations.

### Repository context, skill coordination, modules, and ownership

Read [context-skill-coordination-and-architecture.md](references/context-skill-coordination-and-architecture.md).

Use it for repository discovery, module responsibilities, dependency direction, and cross-skill ownership.

### Java design and public API

Read [java-design-and-public-api.md](references/java-design-and-public-api.md).

Use it for Java/toolchain choices, visibility, immutability, nullability, DI, Lombok, naming, facades, builders, errors, and result models.

### Control, propagation, sampling, and scrubbing

Read [control-propagation-sampling-and-scrubbing.md](references/control-propagation-sampling-and-scrubbing.md).

Use it for wire protocols, runtime mutation, trace context, sampling, PII policy, and fail-closed behavior.

### Observability, security, and Spring Boot

Read [observability-security-and-spring.md](references/observability-security-and-spring.md).

Use it for telemetry, logging, trust boundaries, configuration, bean composition, and starter behavior.

### Reactive execution, concurrency, lifecycle, and performance

Read [reactive-concurrency-lifecycle-and-performance.md](references/reactive-concurrency-lifecycle-and-performance.md).

Use it for Reactor, thread safety, atomic state, startup/shutdown, hot paths, and allocation.

### Distributed systems, Redis, Kubernetes, and dependencies

Read [distributed-systems-kubernetes-and-dependencies.md](references/distributed-systems-kubernetes-and-dependencies.md).

Use it for coordination, retries, idempotency, Redis applicability, workload behavior, deployment, and dependency scopes.

### Testing, E2E, Gradle, Javadoc, imports, and generated code

Read [testing-build-and-code-quality.md](references/testing-build-and-code-quality.md).

Use it for test layers, packaged runtime evidence, build behavior, documentation, imports, formatting, and generated changes.

### Breaking changes, audits, anti-patterns, and reports

Read [breaking-changes-review-and-reporting.md](references/breaking-changes-review-and-reporting.md).

Use it for pre-production migration, review standards, verification commands, prohibited behavior, and final reports.

## Required reference combinations

For every cross-cutting backend change, read:

1. `context-skill-coordination-and-architecture.md`
2. `breaking-changes-review-and-reporting.md`
3. every domain reference touched by the change

For public API changes, also read `java-design-and-public-api.md`.

For tracing-runtime changes, also read:

1. `control-propagation-sampling-and-scrubbing.md`
2. `observability-security-and-spring.md`
3. `reactive-concurrency-lifecycle-and-performance.md`

For infrastructure or deployment changes, also read:

1. `distributed-systems-kubernetes-and-dependencies.md`
2. `testing-build-and-code-quality.md`

## Completion standard

Do not report completion until:

- the concrete defect and intended architecture decision are explicit
- owners, consumers, boundaries, and dependency direction are verified
- obsolete paths and stale consumers are removed
- public API/SPI and protocol deltas are documented
- success and failure state matrices are verified
- concurrency, lifecycle, security, telemetry, and dependency risks are addressed
- all affected source sets compile
- required tests and packaged E2E actually execute
- architecture and publication gates pass
- implementation claims are reconciled with evidence
- migration, rollout, rollback, assumptions, and residual risks are explicit
- the final report states the exact next slice or checkpoint

