# Platform Architecture Standards

## Context

This repository provides enterprise platform infrastructure for internal services.

Generated platform code must be:

* production-grade
* backward-compatible
* observable
* cloud-native
* operationally predictable
* easy to maintain long-term

Target consumers:

* internal backend teams
* Kubernetes workloads
* Spring Boot microservices
* platform engineering teams

---

# Priority

This skill has very high priority for:

* platform starters
* infrastructure modules
* Gradle convention plugins
* shared APIs
* distributed systems infrastructure

When conflicts occur:

* architectural consistency has priority
* backward compatibility has priority
* operational maintainability has priority

---

# Architecture Principles

Use:

* convention over configuration
* backward compatibility first
* explicit over implicit
* minimal magic
* observable by default
* infrastructure as reusable modules
* deterministic runtime behavior

Avoid:

* hidden side effects
* framework lock-in
* implicit runtime coupling
* opaque infrastructure behavior

---

# Modularization

Modules must:

* have clear ownership
* avoid cyclic dependencies
* expose minimal public API
* isolate infrastructure concerns
* preserve clear architectural boundaries

Prefer:

* infrastructure-focused modules
* composable platform components
* explicit extension points

Avoid:

* giant shared utility modules
* deep transitive coupling
* hidden module dependencies

---

# Layering

Architecture layers must remain explicit.

Preferred layering:

* API layer
* service layer
* infrastructure layer
* platform integration layer

Avoid:

* cross-layer leakage
* infrastructure logic inside APIs
* business logic inside platform starters

Never:

* bypass architectural boundaries
* introduce hidden runtime coupling
* mix deployment logic with runtime logic

---

# Gradle DSL

Convention plugins must:

* be deterministic
* support configuration cache
* avoid project.afterEvaluate when possible
* support reproducible builds

Prefer:

* lazy configuration APIs
* Provider API
* version catalogs
* convention plugins over shared scripts

Avoid:

* eager configuration
* mutable build state
* hidden task realization

---

# Dependency Management

Use:

* Gradle Version Catalogs
* BOM-based dependency alignment
* platform dependency governance

Avoid:

* hardcoded versions
* dynamic dependency versions
* incompatible transitive dependency overrides

Dependencies must:

* remain centrally governed
* support security scanning
* support reproducible builds

---

# Public API Design

Public APIs must:

* remain stable
* support backward compatibility
* expose minimal surface area
* preserve extension semantics

Prefer:

* explicit contracts
* additive evolution
* compatibility adapters

Avoid:

* leaking implementation details
* unstable extension points
* unnecessary abstractions

---

# Platform Starters

Each starter must:

* solve one infrastructure concern
* expose minimal configuration
* support enterprise observability
* remain operationally predictable
* support Kubernetes deployments

Starters must NOT:

* contain business logic
* enforce deployment assumptions
* mutate infrastructure unexpectedly

Prefer:

* infrastructure composition
* conditional auto-configuration
* explicit observability hooks

---

# Distributed Systems

Distributed infrastructure must:

* tolerate retries
* tolerate partial failure
* tolerate rolling deployments
* remain horizontally scalable

Prefer:

* idempotent operations
* externalized coordination
* explicit retry behavior

Avoid:

* singleton assumptions
* local in-memory coordination
* synchronized deployment assumptions

---

# Kubernetes Compatibility

Generated infrastructure must:

* support rolling deployments
* support stateless scaling
* tolerate pod restart
* support immutable infrastructure

Avoid:

* local filesystem assumptions
* static node assumptions
* startup deadlocks

Prefer:

* readiness-aware initialization
* graceful degradation
* observable startup behavior

---

# Observability

All infrastructure modules must support:

* metrics
* tracing
* structured logging
* health diagnostics

Prefer:

* Micrometer
* OpenTelemetry
* Spring Boot Actuator

Avoid:

* hidden runtime behavior
* silent failure handling
* opaque retries

---

# Security

Never:

* hardcode credentials
* disable SSL validation
* log secrets
* bypass authorization boundaries

Prefer:

* externalized secrets
* least privilege
* secure-by-default infrastructure

Avoid:

* hidden trust assumptions
* insecure serialization
* privileged infrastructure behavior

---

# Maintainability

Generated code must:

* be easy to debug
* be easy to remove
* avoid framework lock-in
* remain operationally understandable
* support long-term maintenance

Prefer:

* explicit infrastructure behavior
* deterministic runtime behavior
* low cognitive complexity

Avoid:

* infrastructure magic
* hidden startup behavior
* reflection-heavy infrastructure

---

# Runtime Behavior

Infrastructure runtime behavior must:

* remain deterministic
* expose operational visibility
* support graceful degradation

Avoid:

* hidden retries
* startup-time mutation
* invisible background execution

Prefer:

* explicit lifecycle management
* observable infrastructure state
* controlled retry policies

---

# Anti-Conflict Rules

## Architecture Ownership

Core architecture rules belong ONLY to:

* platform architecture modules
* infrastructure governance
* enterprise platform standards

Feature modules must NOT:

* redefine architectural boundaries
* bypass platform infrastructure
* introduce incompatible runtime models

---

## Dependency Ownership

Dependency governance belongs ONLY to:

* platform BOM modules
* version catalogs
* dependency governance infrastructure

Application modules must NOT:

* redefine shared dependency versions
* introduce unmanaged dependency overrides
* bypass dependency governance

---

## Build Ownership

Build logic belongs ONLY to:

* Gradle convention plugins
* platform build infrastructure
* centralized build governance

Application modules must NOT:

* duplicate convention plugin logic
* redefine shared compiler configuration
* bypass build governance rules

---

## Infrastructure Ownership

Infrastructure behavior belongs ONLY to:

* platform starters
* infrastructure modules
* Kubernetes infrastructure layers

Business modules must NOT:

* implement custom infrastructure orchestration
* mutate shared infrastructure dynamically
* bypass infrastructure observability

---

## API Ownership

Public API contracts belong ONLY to:

* shared API modules
* platform integration contracts
* enterprise compatibility governance

Internal refactoring must NOT:

* leak into public APIs
* break extension points
* force consumer rewrites unexpectedly

---

## Security Ownership

Security boundaries belong ONLY to:

* security infrastructure
* centralized authentication systems
* authorization governance layers

Application modules must NOT:

* bypass security enforcement
* redefine authentication semantics
* weaken transport security

---

## Observability Ownership

Observability standards belong ONLY to:

* telemetry infrastructure
* logging governance
* tracing infrastructure

Feature modules must NOT:

* redefine logging formats
* suppress infrastructure telemetry
* introduce incompatible metrics

---

## Kubernetes Ownership

Kubernetes deployment behavior belongs ONLY to:

* infrastructure modules
* deployment systems
* GitOps workflows

Application code must NOT:

* assume static topology
* self-manage infrastructure dynamically
* bypass deployment governance

---

## Distributed Coordination Ownership

Distributed coordination belongs ONLY to:

* Redis/KeyDB infrastructure
* distributed lock modules
* platform synchronization services

Feature modules must NOT:

* implement incompatible coordination semantics
* rely on local singleton assumptions
* bypass distributed observability

---

# Enterprise Rules

Generated platform architecture must:

* support long-term evolution
* remain cloud-native
* support operational debugging
* avoid vendor lock-in
* minimize migration risk

Prefer explicit architecture over hidden framework behavior.

---

# Anti-Patterns

Forbidden:

* hidden runtime behavior
* reflection abuse
* global static registries
* startup side effects
* cyclic dependencies
* infrastructure magic
* implicit runtime coupling
* mutable shared global state
* hidden retries
* opaque background execution
* singleton deployment assumptions
* local coordination assumptions
* hardcoded infrastructure topology
* deployment-specific business logic
* environment-specific branching logic

Avoid architectural magic.
