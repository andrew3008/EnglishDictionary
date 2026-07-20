---
name: testing
description: Defines enterprise testing and executable-evidence standards for the Platform Tracing repository. Use when Codex designs, reviews, refactors, implements, runs, or reports unit, integration, Spring Boot context, architecture fitness, public API, control protocol, runtime control, sampling, scrubbing, servlet, WebFlux, JMX, classloader, E2E, Testcontainers, concurrency, or performance tests.
---

# Testing Standards

## Context

This repository contains an enterprise platform tracing solution for Spring Boot microservices.

The solution is currently **pre-production**. Breaking changes are allowed when they materially improve architecture, safety, maintainability, observability, or production readiness.

Testing must prove production-grade behavior, not preserve accidental compatibility with the current pre-production implementation.

## Primary Testing Goals

Tests must protect:

- public API contracts
- module boundaries
- Spring Boot autoconfiguration behavior
- OpenTelemetry integration behavior
- servlet and reactive instrumentation paths
- runtime control safety
- sampling policy correctness
- scrubbing / PII safety
- JMX and classloader-neutral wire contracts
- operator diagnostics
- low startup overhead
- minimal and intentional dependencies

Every non-trivial refactoring must include tests or architecture checks that make the intended boundary harder to regress.

## Unit Testing

Use:

- JUnit 5
- AssertJ
- Mockito only when necessary
- Awaitility for asynchronous verification

Prefer:

- state-based testing
- deterministic assertions
- narrow tests for pure logic
- explicit edge cases
- table-driven / parameterized tests for protocol and policy rules
- named test methods that describe behavior, not implementation steps

Avoid:

- over-mocking domain logic
- asserting private implementation details
- copying production logic into tests
- broad snapshot assertions that hide intent
- tests that pass only because defaults silently mask missing behavior

## Test Naming

Test names should describe the externally visible behavior.

Preferred style:

```java
emptyApplyMutationIsRejectedByDomainValidator()
readAppliedStateDoesNotRequireMutationPermission()
unknownWireKeyIsRejectedByDecoder()
domainRejectedMutationDoesNotCallApplier()
```

Avoid names tied to removed or legacy APIs. Do not keep method names that match deleted APIs such as `validateRuntimePolicy`, `schema`, or `validator` unless the test is explicitly verifying their absence.

## Integration Testing

Use Testcontainers when a real dependency boundary matters.

Allowed containers:

- PostgreSQL
- KeyDB
- Redis
- Kafka
- MinIO
- OpenTelemetry Collector
- Jaeger or tracing backend test substitute when needed

Container rules:

- use dynamic ports
- avoid fixed localhost assumptions
- avoid depending on a developer workstation path
- isolate test state
- make startup readiness explicit
- do not use sleeps
- use Awaitility or container health/readiness probes
- skip only behind explicit opt-in profiles when the test is genuinely environment-dependent

When an e2e test is opt-in, a skipped run is not evidence of runtime correctness. Reports must distinguish:

- compiled but skipped
- executed and passed
- executed and failed

## Spring Boot Starter Testing

Use:

- `ApplicationContextRunner` for starter and auto-configuration tests
- `@SpringBootTest` only when full application behavior is required
- `FilteredClassLoader` or equivalent techniques for optional classpath scenarios
- Spring Boot configuration metadata checks when properties are public adoption surface

Verify:

- conditional bean registration
- property binding
- defaults
- disabled behavior
- missing optional dependencies
- absence of unwanted beans
- diagnostics when configuration is invalid or incomplete
- no accidental eager initialization
- no unexpected startup overhead

Prefer lightweight context validation over full application startup.

## AutoConfiguration Testing

Every auto-configuration change must answer:

- What beans are created?
- What beans are intentionally absent?
- What properties control behavior?
- What is the default?
- What happens when the feature is disabled?
- What happens when the dependency is absent?
- What diagnostics does the service owner receive?

For adoption-critical properties, tests must cover both Java-side binding and documented examples.

## Architecture Fitness Tests

Architecture tests are mandatory for non-trivial refactoring.

Use ArchUnit or existing architecture fitness tasks to protect:

- API modules do not depend on core implementation modules
- API modules do not depend on Spring, JMX, OTel SDK, Jackson, or application runtime frameworks unless explicitly approved
- core modules own runtime behavior and domain validation
- Spring autoconfiguration owns wiring and properties
- OTel / JMX modules own transport-specific integration
- implementation helpers are not accidentally public
- deleted legacy packages do not return
- forbidden symbols do not return
- public API surface remains intentional
- runtime mutation cannot bypass decode and domain validation

When a refactoring deletes an accidental API, add or update a test that fails if the deleted API returns.

## Public API Testing

Public API tests must protect intentional contracts, not accidental legacy shape.

Verify:

- public entry points
- record invariants
- null / invalid input behavior
- immutable outputs
- meaningful exception or violation models
- absence of accidental public helpers
- no deprecated bridges unless explicitly approved by ADR

For pre-production cleanup, prefer direct migration and deletion over compatibility shims.

## Control Protocol / Wire Contract Testing

For tracing control protocol changes, test the full chain:

```text
wire payload -> decode -> domain validate -> apply/read
```

Required checks:

- valid payloads decode successfully
- invalid payloads produce machine-readable violations
- invalid decode results do not expose usable partial payload for apply
- unknown keys are rejected
- non-string keys are rejected
- Java enum instances are rejected as wire values
- type normalization is deterministic
- operation-specific schemas are enforced
- read operations do not mutate
- validate operations do not mutate
- apply operations require domain validation
- rejected mutations do not call applier
- rejected mutations do not change last-known-good state, version, source, or applied snapshot

Golden wire tests should keep documentation and decoder behavior synchronized. When a wire spec contains examples, at least representative examples must be executable as tests or mirrored by fixture-based tests.

## Runtime Control Testing

Runtime mutation must fail closed unless explicitly enabled.

Tests must cover:

- default mutation disabled
- explicit mutation enabled
- read allowed when mutation disabled
- validate allowed when mutation disabled
- apply rejected when mutation disabled
- no applier call on decode rejection
- no applier call on domain rejection
- no applier call on mutation-policy rejection
- applied state unchanged after rejected mutation
- operator-visible rejection reason

For JMX/control-plane paths, include both unit-level handler tests and at least one e2e or integration test proving the real boundary.

## Sampling Policy Testing

Sampling tests must cover:

- default ratio
- per-route ratios
- route precedence
- boundary values
- invalid values
- disabled mode
- runtime updates
- last-applied-state consistency
- deterministic behavior under repeated updates

Microbenchmarks must not be the only evidence for a sampling decision. Use macro or integration evidence when behavior affects production rollout.

## Scrubbing / PII Testing

Scrubbing tests must prove safety, not only configuration parsing.

Cover:

- known rule activation
- unknown rule diagnostics
- active rule names or safe fingerprints
- skipped rule reporting
- attribute removal or masking behavior
- no PII leakage through diagnostics
- denylist / allowlist behavior if present
- behavior when scrubbing is disabled

Do not expose raw sensitive values in test names, logs, assertions, or generated documentation.

## Servlet / Reactive Instrumentation Testing

Instrumentation tests should cover both servlet and reactive stacks when behavior is shared.

Verify:

- inbound span creation
- outbound propagation
- no duplicate spans
- MDC behavior
- error status mapping
- sampling decisions
- disabled instrumentation
- interaction with existing OTel instrumentation
- expected behavior under missing optional dependencies

Use separate tests for servlet and WebFlux when the execution model differs.

## JMX / Classloader Boundary Testing

JMX and agent-adjacent tests must verify classloader-neutral behavior.

Use only wire-safe types across JMX / Map boundaries:

- String
- Integer / Long / Double
- Boolean
- String[]
- Map<String, Double>
- CompositeData / TabularData only in JMX adapter modules

The API module must not depend on JMX/OpenMBean types.

Tests must prove:

- OpenMBean types stay outside API
- Map wire payload is decoded before domain/application behavior
- unsupported or malformed payloads are rejected at the boundary
- direct runtime mutation is guarded

## E2E Testing

E2E tests are required when behavior crosses module boundaries or depends on real integration.

E2E tests must state:

- what runtime boundary they prove
- whether they require opt-in
- what environment variables or Gradle properties are required
- whether the test executed or was skipped

A skipped e2e test is not a pass. Reports must explicitly say `SKIPPED` or `INSUFFICIENT_EVIDENCE` for runtime behavior if the test did not execute.

## Performance / Startup Testing

Performance-sensitive changes must avoid:

- hidden eager initialization
- heavy classpath scanning
- unnecessary transitive dependencies
- startup-time network calls
- blocking calls on reactive paths
- unbounded allocations on hot paths

Tests or benchmarks should be used when a change affects:

- startup overhead
- span creation hot paths
- attribute enrichment
- sampling decisions
- scrubbing rules
- export processor behavior

JMH results must be treated as evidence, not absolute truth. No production decision should rely on a single noisy benchmark.

## Testcontainers Rules

Containers must:

- use dynamic ports
- be isolated between tests unless reuse is intentionally configured
- expose readiness explicitly
- avoid fixed host assumptions
- avoid real cloud services
- avoid developer-specific paths
- be compatible with CI and documented remote Docker use

Allowed external dependencies in tests must be explicit and reproducible.

## Async Testing

Forbidden:

- `Thread.sleep`
- timing-dependent assertions
- polling loops without timeouts
- shared mutable state between tests

Use:

- Awaitility
- explicit latches only when necessary
- bounded timeouts
- deterministic signals
- test-specific executors if needed

## Imports and Generated Code

For Cursor, Codex, and Perplexity-generated changes:

- follow repository `.editorconfig`
- use explicit Java imports only
- do not use wildcard imports
- do not use static wildcard imports
- do not create import-only churn
- do not reorder imports in unrelated files
- run the narrowest affected compile/test task after Java edits

Generated tests must meet the same quality bar as handwritten tests.

## Anti-patterns

Forbidden:

- `Thread.sleep`
- shared mutable test state
- tests that depend on real cloud services
- fixed ports
- fixed localhost assumptions
- production behavior hidden behind test-only branches
- compatibility shims added only to make old tests compile
- tests that assert implementation details instead of behavior
- broad `@SpringBootTest` where `ApplicationContextRunner` is enough
- wildcard imports
- unverified e2e skip reported as pass

## Required Reporting

For every major refactoring or production-hardening PR, final report must include:

- tests added or updated
- commands executed
- skipped tests, if any
- environment assumptions
- architecture fitness result
- known residual risks
- whether e2e behavior was executed or only compiled

Use `PASS_WITH_WARNINGS` when tests are green but runtime evidence is incomplete.
Use `INSUFFICIENT_EVIDENCE` when a claim depends on an unexecuted e2e or unavailable environment.

