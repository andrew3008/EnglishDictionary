# Architecture Foundations, Decisions, and ADRs

## Context

This repository contains an enterprise platform tracing solution for Spring Boot servlet and reactive microservices.

The solution includes:

- `platform-tracing-api`
- `platform-tracing-otel`
- Spring Boot auto-configuration
- servlet and WebFlux adapters
- starters
- OpenTelemetry agent/SDK integration
- sampling and scrubbing policies
- runtime control
- JMX/OpenMBean adapters
- collector configuration
- architecture fitness rules
- test utilities, samples, benchmarks, and Docker-backed E2E tests

The tracing solution is currently **pre-production**.

Breaking source, binary, package, module, bean, configuration, SPI, wire, publication, and build changes are allowed when they materially improve:

- architectural integrity
- correctness
- runtime safety
- public API clarity
- dependency governance
- privacy and security
- operator diagnostics
- performance
- testability
- production readiness
- adoption across many internal services

Backward compatibility with the current pre-production implementation is **not** a primary architectural goal.

Do not preserve accidental APIs, obsolete packages, aliases, deprecated bridges, false extension points, dual implementations, unsafe defaults, or stale integration paths merely because they already exist.

Architects will not accept cosmetic refactoring. Every substantial architecture change must:

- solve a concrete architectural or production problem
- identify the correct owner
- reduce coupling or operational risk
- make invalid states harder to represent
- remove accidental public surface
- strengthen executable verification
- result in a simpler final mental model

## Role of This Skill

This is the highest-level architecture skill for the tracing repository.

For specialized decisions, also follow:

- `project-context.md`
- `backend.md`
- `platform-api.md`
- `spring.md`
- `security.md`
- `observability.md`
- `testing.md`
- `testcontainers.md`
- `gradle.md`
- `redis.md`
- `code-review.md`

This file defines:

- system boundaries
- module ownership
- dependency direction
- classloader boundaries
- public API governance
- runtime-control principles
- architectural decision and verification policy

Specialized skills define implementation details inside those boundaries.

A specialized skill must not override the module ownership and pre-production policy defined here.

## Architecture Priority

When concerns conflict, prefer in this order:

1. correctness
2. security and privacy
3. runtime-state safety
4. module and classloader boundaries
5. public API integrity
6. deterministic behavior
7. dependency and publication correctness
8. executable verification
9. operator diagnostics
10. bounded telemetry volume and resource use
11. reactive/concurrency correctness
12. startup and hot-path performance
13. maintainability
14. developer ergonomics
15. compatibility with pre-production behavior

Do not weaken an intended architecture boundary to avoid updating repository callers, tests, samples, fixtures, docs, properties, or build logic.

## Repository Facts Before Architecture

Architecture decisions must be based on the current repository, not on generic assumptions.

Before proposing a change:

1. inspect the current branch and working tree
2. inspect actual module dependencies
3. locate all production/test/custom-source-set consumers
4. inspect existing architecture tests
5. inspect ADRs, warning registers, and current docs
6. distinguish current code from historical analysis
7. verify Gradle dependency scopes
8. identify runtime and classloader boundaries
9. identify real external consumers
10. mark unverified assumptions explicitly

Use:

- `VERIFIED`
- `PARTIALLY_VERIFIED`
- `INSUFFICIENT_EVIDENCE`
- `FALSE_POSITIVE`
- `HISTORICAL_ONLY`

Do not invent:

- call sites
- public consumers
- runtime providers
- extension requirements
- configuration defaults
- supported deployment topologies
- green test results

## Architectural Mission

The architecture must let application teams use a small, safe tracing API without depending on:

- OpenTelemetry SDK internals
- Spring Boot wiring internals
- Java agent implementation classes
- JMX/OpenMBean types
- exporter/processors
- runtime mutation mechanisms
- classloader-specific bridges

The platform team must be able to change runtime implementation, sampling, scrubbing, integrations, and diagnostics without forcing service code to depend on internal mechanics.

The system must be understandable as:

```text
application code
    -> platform-tracing-api
    -> platform-tracing-otel
    -> runtime/integration adapters
    -> OpenTelemetry / Collector / backend
```

Cross-cutting configuration and transport adapters must not collapse these boundaries.

## Core Architecture Principles

Use:

- explicit ownership
- dependency inversion
- narrow public contracts
- package-private implementation
- immutable value models
- deterministic runtime behavior
- classloader-neutral wire boundaries
- fail-closed privileged operations
- explicit failure/result models
- safe defaults
- architecture fitness functions
- executable evidence
- minimal magic

Avoid:

- hidden side effects
- implicit runtime coupling
- compatibility-first preservation of architectural debt
- speculative extension points
- duplicate sources of truth
- framework types in domain/public contracts
- global holders
- service locators
- giant shared utility modules
- one-interface/one-implementation ceremony without a boundary
- broad public helpers
- accidental transitive dependencies
- silent fallback behavior
- opaque background execution

## Architectural Decision Quality

A serious architecture decision must state:

```text
Problem:
Why the current design is insufficient:
Repository evidence:
Target architecture:
Owner module:
Public surface:
Dependency direction:
Runtime/classloader boundary:
Failure model:
Security/privacy impact:
Alternatives rejected:
Migration:
Verification:
Residual risks:
```

A change is not architectural merely because it:

- renames classes
- moves packages
- adds interfaces
- splits files
- creates a new module
- introduces a pattern
- copies an industry example

It is architectural when it changes ownership, contracts, safety, dependency direction, runtime behavior, or the system mental model.

## Architecture Decision Records

Create or update an ADR when a change affects:

- public API/SPI
- module ownership
- dependency contract
- classloader boundary
- runtime control
- wire protocol
- security default
- sampling/scrubbing policy ownership
- Spring property contract
- publication coordinates
- supported deployment topology

An ADR must include:

- status
- context
- decision
- consequences
- alternatives rejected
- compatibility policy
- verification
- follow-up decisions
- known residual risks

Do not create an ADR for trivial implementation detail.

Do not leave an accepted architecture decision only in an LLM transcript or temporary plan file.

