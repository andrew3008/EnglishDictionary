# Architecture and Boundaries

## Context

This repository contains an enterprise platform tracing solution for Spring Boot servlet and reactive microservices.

The solution is currently **pre-production**.

Breaking API, SPI, package, bean, and configuration changes are allowed when they materially improve:

- architectural integrity
- runtime safety
- public API clarity
- dependency governance
- testability
- operator diagnostics
- production readiness
- adoption across many internal services

Backward compatibility with the current pre-production API shape is **not** a primary goal.

Do not preserve accidental APIs, ambiguous names, obsolete packages, deprecated bridges, aliases, or dual execution paths merely because existing tests or internal callers still use them.

Architects will not accept cosmetic refactoring. Every substantial public API change must:

- remove an architectural defect
- reduce production or adoption risk
- clarify ownership
- narrow accidental public surface
- eliminate external dependency leakage
- strengthen invariants
- improve the mental model for service teams
- add executable verification

## Priority

When requirements conflict, prefer in this order:

1. correctness and runtime safety
2. clear ownership between API, core, Spring, and OTel/JMX layers
3. minimal and intentional public surface
4. misuse resistance
5. dependency hygiene
6. testability and architecture fitness
7. operator and developer clarity
8. implementation simplicity
9. compatibility with pre-production behavior

Do not weaken an intended API boundary to avoid updating tests, samples, docs, generated code, or internal consumers.

## API Mission

`platform-tracing-api` defines the language by which application code and other platform modules interact with tracing.

It may own:

- narrow public facades
- capability interfaces
- immutable value objects
- builders and specifications
- public annotations
- public violation/result models
- intentional SPI contracts
- classloader-neutral control/propagation contracts
- stable wire vocabulary required by callers

It must not become:

- a container for implementation classes
- a mirror of OpenTelemetry SDK internals
- a Spring Boot module
- a JMX/OpenMBean module
- a dumping ground for utilities
- a compatibility archive
- a second runtime implementation
- a general-purpose observability SDK

## API and Core Separation

### `platform-tracing-api`

Owns contracts.

It must not depend on `platform-tracing-core`.

Application-facing code should compile against API contracts, not implementation classes.

### `platform-tracing-core`

Owns:

- implementations of public contracts
- tracing runtime behavior
- lifecycle
- state
- sampling and scrubbing policy
- domain validation
- no-op behavior
- safety invariants
- OpenTelemetry adaptation where approved

Core may depend on API.

### Spring Boot auto-configuration

Owns:

- bean wiring
- conditional registration
- typed startup properties
- startup diagnostics
- Actuator integration
- translation from startup configuration to core contracts

Spring types must not leak into API or core contracts.

### OTel/JMX integration

Owns:

- agent/SDK integration
- JMX/OpenMBean adapters
- exporter/sampler/processor-specific glue
- classloader-sensitive bridges

OTel SDK implementation types and JMX/OpenMBean types must not leak into application-facing API.

## Public Surface Classification

Every type in an API module must be classified as exactly one of:

1. **Application-facing public API**
2. **External extension SPI**
3. **Wire/control contract**
4. **Internal implementation helper**
5. **Public-for-compilation internal bridge**
6. **Test-only type**

Do not leave public visibility unclassified.

### Application-facing API

Application teams are expected to import and call it directly.

It requires:

- clear JavaDoc
- stable semantics
- misuse-resistant naming
- tests
- architecture review
- dependency review

### External SPI

External platform/service teams may implement it.

It requires:

- documented lifecycle
- ordering
- failure behavior
- threading model
- classloader assumptions
- provider discovery model
- compatibility policy
- tests for missing, duplicate, and failing providers

### Wire/control contract

Used across module/process/classloader boundaries.

It requires:

- JDK-safe types
- explicit schema
- versioning
- machine-readable failures
- strict validation
- documentation and golden tests

### Internal helper

Must be package-private when possible.

Do not make a helper public merely to cross Java subpackages. Prefer:

- one coherent package
- internal package restructuring
- narrow package-private collaboration
- a deliberate internal bridge only when unavoidable

### Public-for-compilation internal bridge

Occasionally a type must be public because Java visibility or module layout requires cross-package access.

Such a type must have:

- strict JavaDoc stating it is not an extension API
- an ArchUnit access restriction
- an explicit dependent allowlist
- no promotion in application-facing docs
- no user-facing examples

Public visibility alone must not silently make it part of the supported API.

## Public API Minimization

The default decision for a new type is **not public**.

Before adding a public type or method, answer:

1. Who is the concrete consumer?
2. Why can the behavior not be expressed through an existing capability?
3. Is it a stable domain concept or an implementation mechanism?
4. Does it expose a third-party type?
5. Will service teams need to understand it?
6. Can its invariants be enforced?
7. What must remain compatible after production?
8. What architecture rule will prevent accidental expansion?

If no real consumer exists, keep the type internal.

Do not add speculative query methods, extension points, registries, schema accessors, or providers.

