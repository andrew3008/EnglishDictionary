# Spring Architecture, Starters, and Registration

## Context

This repository contains an enterprise platform tracing solution delivered through Spring Boot starters, auto-configuration modules, servlet/reactive adapters, and runtime integrations.

The tracing solution is currently **pre-production**.

Breaking changes are allowed when they materially improve architecture, runtime safety, maintainability, operational clarity, dependency hygiene, or production readiness.

Do not preserve accidental bean names, property names, package locations, aliases, or starter behavior only for backward compatibility unless an ADR explicitly requires it.

Architects will not accept cosmetic refactoring. Every substantial Spring change must:

- reduce production risk
- clarify ownership between modules
- simplify adoption for service teams
- improve failure diagnostics
- strengthen tests or architecture fitness rules
- remove accidental coupling or hidden behavior

## Primary Goals

Spring Boot integration must provide:

- predictable behavior across many Spring Boot services
- minimal and intentional startup work
- safe defaults
- explicit enablement for risky capabilities
- clear disabled/no-op behavior
- deterministic bean registration
- strong optional-classpath isolation
- actionable startup diagnostics
- no leakage of Spring concerns into API or core modules
- equivalent behavior for servlet and reactive stacks where semantics are shared
- a straightforward adoption path for internal platform teams

## Module Ownership

### `platform-tracing-api`

Owns:

- public tracing contracts
- public value objects
- annotations
- narrow extension contracts
- classloader-neutral protocol types

Must not depend on:

- Spring Framework
- Spring Boot
- Actuator
- JMX/OpenMBean
- OpenTelemetry SDK implementation
- application runtime infrastructure

### `platform-tracing-core`

Owns:

- tracing runtime behavior
- domain validation
- sampling and scrubbing policies
- lifecycle and state
- no-op behavior
- control handlers
- implementation of public API contracts

Must not contain:

- Spring annotations
- `ApplicationContext` access
- conditional bean logic
- `@ConfigurationProperties`
- servlet or WebFlux types

### `platform-tracing-spring-boot-autoconfigure`

Owns:

- Spring Boot wiring
- `@ConfigurationProperties`
- conditional bean registration
- startup diagnostics
- Actuator integration
- translation from properties into core/domain configuration
- reconciliation between desired startup configuration and live runtime state

Must not own:

- tracing domain logic
- wire decoding rules
- sampling algorithms
- scrubbing implementation
- JMX transport logic
- public API models that should live in `platform-tracing-api`

### Web adapter modules

`platform-tracing-autoconfigure-webmvc` owns servlet-specific integration.

`platform-tracing-autoconfigure-webflux` owns reactive/WebFlux-specific integration.

Do not introduce cross-dependencies between servlet and reactive adapters.

Shared behavior belongs in API/core/autoconfigure only when it is genuinely framework-neutral.

### `platform-tracing-otel-extension`

Owns:

- OpenTelemetry agent/SDK integration
- JMX/OpenMBean adapters
- classloader-sensitive bridges
- exporter/sampler/processor-specific integration

Spring Boot auto-configuration must not absorb agent/JMX implementation details.

## Starter Design

A starter should primarily aggregate dependencies and activate auto-configuration.

Starters may provide:

- dependency alignment
- the correct auto-configuration module
- platform-approved defaults through configuration metadata/documentation
- servlet or reactive integration selection

Starters must not:

- contain business or runtime logic
- define duplicate auto-configuration
- expose internal implementation classes
- force both servlet and reactive stacks
- pull large optional integrations unconditionally
- hide mutable global state
- perform network calls during startup

Keep starter modules thin enough that their dependency graph is easy to audit.

## Auto-Configuration Registration

Use current Spring Boot conventions:

- `@AutoConfiguration`
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- explicit ordering only when technically required
- `before`, `after`, `beforeName`, or `afterName` only with documented rationale

Do not use legacy `spring.factories` for new auto-configuration registration unless a supported compatibility requirement explicitly demands it.

Each auto-configuration class should have one clear responsibility.

Avoid a single large auto-configuration class that wires unrelated concerns such as:

- tracing facade
- web instrumentation
- JMX registration
- sampling
- scrubbing
- diagnostics

Split by capability when this improves conditional isolation and testability.

