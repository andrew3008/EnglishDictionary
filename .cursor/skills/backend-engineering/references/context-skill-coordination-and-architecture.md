# Context, Skill Coordination, and Architecture

## Context

This repository contains an enterprise platform tracing solution for Spring Boot servlet and reactive microservices.

The solution includes:

- `platform-tracing-api`
- `platform-tracing-core`
- Spring Boot auto-configuration
- servlet and WebFlux adapters
- starters
- OpenTelemetry agent/SDK integration
- sampling and scrubbing policies
- runtime control
- JMX/OpenMBean adapters
- collector configuration
- test utilities, samples, benchmarks, and Docker-backed E2E tests
- architecture fitness checks

The solution is currently **pre-production**.

Breaking source, binary, package, bean, configuration, wire, SPI, module, and build changes are allowed when they materially improve:

- correctness
- architecture
- runtime safety
- public API quality
- privacy and security
- dependency governance
- operator diagnostics
- performance
- testability
- production readiness
- adoption across many internal services

Backward compatibility with the current pre-production implementation is **not** a primary goal.

Do not preserve accidental APIs, ambiguous names, obsolete packages, deprecated bridges, aliases, dual execution paths, false extension points, unsafe defaults, or stale tests merely because they already exist.

Architects will not accept cosmetic refactoring. Every substantial backend change must solve a concrete architectural, correctness, operability, security, performance, or adoption problem.

## Skill Coordination

This file is the umbrella backend skill.

For specialized work, also follow the relevant repository skills:

- `project-context.md`
- `platform-api.md`
- `spring.md`
- `security.md`
- `observability.md`
- `testing.md`
- `testcontainers.md`
- `gradle.md`
- `redis.md`
- `code-review.md`

When rules overlap, prefer the more specialized skill for its domain, provided it does not violate the architecture and pre-production policy defined here.

Examples:

- API surface decisions → `platform-api.md`
- Spring Boot wiring/properties → `spring.md`
- telemetry semantics/cardinality → `observability.md`
- security/privacy/trust boundaries → `security.md`
- Gradle/dependency/publication changes → `gradle.md`
- Testcontainers/remote Docker → `testcontainers.md`
- Redis/KeyDB → `redis.md`
- audits and merge decisions → `code-review.md`

Do not copy a generic solution from one domain into another without checking ownership.

## Priority

When requirements conflict, prefer in this order:

1. correctness
2. security and privacy
3. runtime-state safety
4. module and classloader boundaries
5. public API integrity
6. deterministic behavior
7. dependency and publication correctness
8. executable verification
9. operator diagnostics
10. bounded resource usage and telemetry volume
11. reactive/concurrency correctness
12. startup and hot-path performance
13. maintainability
14. developer ergonomics
15. compatibility with pre-production behavior

Do not weaken a safety or architecture invariant to keep an old test, alias, bean name, property, SPI, or package compiling.

## Repository Facts First

Before changing code:

1. inspect the current branch and working tree
2. locate the real call sites
3. inspect module dependencies
4. inspect existing architecture tests
5. inspect specialized skills and ADRs
6. distinguish current code from historical docs
7. distinguish verified facts from assumptions

Useful commands:

```powershell
git status --short --branch
git log --oneline -10
git diff --stat
git diff --check
```

Do not invent:

- call sites
- public consumers
- runtime providers
- extension requirements
- configuration defaults
- supported topologies
- test results

Use `INSUFFICIENT_EVIDENCE` when a material fact cannot be verified.

## Architecture Ownership

### `platform-tracing-api`

Owns:

- public tracing contracts
- capability interfaces
- immutable value objects
- specifications and builders
- public annotations
- intentional SPI contracts
- classloader-neutral wire/control contracts
- public result and violation models

Must not own:

- runtime implementation
- Spring Boot wiring
- JMX/OpenMBean implementation
- OTel SDK implementation
- exporters/processors
- domain validation
- mutable live state
- infrastructure clients
- application context access

### `platform-tracing-core`

Owns:

- implementation of public contracts
- runtime behavior
- lifecycle
- context interpretation
- sampling and scrubbing policies
- domain validation
- runtime-control handlers
- last-known-good state
- no-op behavior
- safety invariants

Must not contain:

- Spring annotations
- `ApplicationContext` access
- `@ConfigurationProperties`
- servlet or WebFlux types
- JMX/OpenMBean types unless explicitly approved for an internal adapter boundary

### Spring Boot auto-configuration

Owns:

- bean wiring
- typed startup properties
- conditional registration
- startup diagnostics
- Actuator integration
- desired startup configuration
- mapping configuration into core contracts

Must not own:

- tracing algorithms
- wire decoding rules
- domain validation
- sampling logic
- scrubbing implementation
- JMX transport behavior

### Servlet and WebFlux adapters

Own framework-specific integration.

Rules:

- servlet adapter does not depend on WebFlux
- WebFlux adapter does not depend on servlet APIs
- shared framework-neutral behavior belongs in API/core/autoconfigure
- reactive behavior must not rely on ordinary thread-local semantics

### `platform-tracing-otel-extension`

Owns:

- OTel agent/SDK bridges
- sampler/processor/exporter integration
- JMX/OpenMBean adapters
- classloader-sensitive behavior
- runtime wiring to approved core contracts

### Starters

Starters are thin dependency aggregators.

They must not contain runtime logic, business policy, duplicate auto-configuration, or unrelated optional integrations.

### Collector configuration

Owns collector-side pipeline configuration:

- receivers
- processors
- batching
- queues
- retry/export
- collector-side filtering/governance

Do not move application/core domain policy into collector YAML merely for deployment convenience.

## Module Dependency Direction

Expected direction:

```text
platform-tracing-core -> platform-tracing-api

platform-tracing-spring-boot-autoconfigure
    -> platform-tracing-api
    -> platform-tracing-core

platform-tracing-autoconfigure-webmvc
    -> approved api/core/autoconfigure modules

platform-tracing-autoconfigure-webflux
    -> approved api/core/autoconfigure modules

platform-tracing-otel-extension
    -> approved api/core modules

starters
    -> matching auto-configuration/integration modules

tests/samples/bench/e2e
    -> production modules under test
```

Forbidden:

- `api -> core`
- `core -> Spring`
- servlet adapter -> WebFlux adapter
- WebFlux adapter -> servlet adapter
- production module -> test/sample/e2e module
- public API -> JMX/OpenMBean implementation
- public API -> OTel SDK implementation without explicit approval
- cycles “fixed” by changing `implementation` to `api`

Fix ownership instead of hiding a dependency cycle.

