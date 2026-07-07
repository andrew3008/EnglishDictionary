# Backend Engineering Standards

## Context

This repository contains enterprise-grade Spring Boot platform starters, infrastructure modules, distributed coordination services, and reusable backend platform components.

Generated backend code must be:

* production-ready
* backward-compatible
* observable
* cloud-native
* operationally predictable
* easy to maintain long-term

Target consumers:

* internal backend teams
* Kubernetes workloads
* distributed microservices
* platform engineering teams

---

# Priority

This skill has very high priority for:

* backend services
* Spring Boot infrastructure
* platform starters
* distributed systems
* Redis/KeyDB integrations
* Kubernetes workloads

When conflicts occur:

* backend correctness has priority
* operational stability has priority
* maintainability has priority

---

# Java Standards

Use:

* Java 21
* immutable objects where possible
* records for DTOs
* constructor injection only
* explicit APIs

Avoid:

* mutable shared state
* reflection-heavy implementations
* framework-specific business logic
* hidden runtime behavior

Prefer:

* deterministic runtime behavior
* explicit dependencies
* low cognitive complexity
* infrastructure observability

---

# Dependency Injection

Use:

* constructor injection only

Forbidden:

* field injection
* static dependency access
* service locators
* ApplicationContext lookups in business code

Prefer:

* explicit dependency graphs
* immutable dependency wiring

---

# Lombok

Allowed:

* @RequiredArgsConstructor
* @Getter
* @Builder
* @Slf4j where appropriate

Avoid:

* excessive Lombok magic
* implicit runtime behavior

Forbidden:

* @Data on domain entities
* @SneakyThrows
* hidden mutable state generation

---

# API Design

Public APIs must:

* remain stable
* expose minimal surface area
* preserve backward compatibility
* support gradual evolution

Prefer:

* explicit DTOs
* additive API evolution
* version-tolerant payloads

Avoid:

* leaking infrastructure details
* exposing internal entities directly
* unstable extension points

---

# Naming Standards

Use:

* meaningful domain-oriented naming
* explicit infrastructure naming
* deterministic naming conventions

Examples:

* UserCacheService
* DistributedLockManager
* RedisLeaderElectionCoordinator

Avoid:

* generic utility naming
* ambiguous abbreviations
* overloaded infrastructure terminology

Forbidden:

* Utils
* CommonService
* GenericManager

---

# Error Handling

Failures must:

* preserve root cause
* expose operational context
* support troubleshooting

Prefer:

* explicit exception hierarchies
* actionable failure messages
* infrastructure-aware retry handling

Avoid:

* swallowed exceptions
* hidden retries
* silent degradation

Never:

* catch Exception broadly without justification
* suppress infrastructure failures
* ignore interruption handling

---

# Logging

Use:

* structured logging
* correlation IDs
* trace IDs
* parameterized logging

Never log:

* secrets
* credentials
* tokens
* sensitive payloads

Prefer:

* operationally actionable logs
* infrastructure-aware diagnostics
* deterministic log structure

Avoid:

* noisy logs
* stack trace spam
* hidden failures

---

# Redis Namespace Convention

All Redis keys must follow: <environment>:<service>:<domain>:<entity>:<identifier>

Examples:
prod:billing:lock:invoice:123
prod:platform:leader-election:instance:node-1

Namespaces must:

* remain deterministic
* avoid collisions
* support distributed deployments

---

# Distributed Systems

Distributed infrastructure must:

* tolerate retries
* tolerate partial failures
* tolerate rolling deployments
* support horizontal scaling

Prefer:

* idempotent operations
* externalized coordination
* observable retry behavior

Avoid:

* singleton assumptions
* local synchronization assumptions
* hidden background coordination

---

# Kubernetes Compatibility

Backend infrastructure must:

* tolerate pod restarts
* support rolling deployments
* remain stateless where possible
* avoid local filesystem assumptions

Prefer:

* readiness-aware initialization
* graceful degradation
* container-aware runtime behavior

Avoid:

* static topology assumptions
* startup deadlocks
* mutable runtime infrastructure

---

# Performance

Generated code must:

* minimize allocations
* avoid blocking operations unnecessarily
* support scalable concurrency

Prefer:

* lazy initialization
* immutable data structures
* bounded resource usage

Avoid:

* excessive synchronization
* unbounded caches
* hidden background threads

---

# Observability

All backend infrastructure must support:

* metrics
* tracing
* structured logging
* health diagnostics

Prefer:

* Micrometer
* OpenTelemetry
* Spring Boot Actuator

Avoid:

* hidden retries
* invisible async execution
* silent infrastructure degradation

---

# Security

Never:

* hardcode credentials
* disable SSL validation
* expose secrets in logs
* trust infrastructure implicitly

Prefer:

* externalized configuration
* least privilege
* secure-by-default infrastructure

Avoid:

* insecure serialization
* hidden trust assumptions
* permissive runtime defaults

---

# Testing

Use:

* JUnit 5
* AssertJ
* Testcontainers
* ApplicationContextRunner for starters

Prefer:

* deterministic tests
* infrastructure-aware testing
* integration testing for distributed systems

Avoid:

* Thread.sleep
* fixed port assumptions
* embedded infrastructure

---

# Maintainability

Generated code must:

* be easy to debug
* be easy to remove
* remain operationally understandable
* avoid framework lock-in

Prefer:

* explicit runtime behavior
* infrastructure transparency
* deterministic execution flow

Avoid:

* hidden infrastructure magic
* implicit runtime coupling
* reflection abuse

---

# Anti-Conflict Rules

## Dependency Ownership

Dependency governance belongs ONLY to:

* version catalogs
* platform BOMs
* dependency governance infrastructure

Application modules must NOT:

* redefine shared dependency versions
* introduce unmanaged transitive overrides
* bypass dependency governance

---

## Infrastructure Ownership

Infrastructure behavior belongs ONLY to:

* platform starters
* infrastructure modules
* distributed systems infrastructure

Business modules must NOT:

* implement infrastructure orchestration manually
* bypass infrastructure observability
* redefine infrastructure lifecycle behavior

---

## Redis Ownership

Redis namespace semantics belong ONLY to:

* Redis platform modules
* distributed coordination infrastructure
* shared cache infrastructure

Feature modules must NOT:

* invent incompatible namespace structures
* redefine distributed lock semantics
* bypass TTL governance

---

## Security Ownership

Security behavior belongs ONLY to:

* authentication infrastructure
* authorization governance
* platform security modules

Application modules must NOT:

* bypass security enforcement
* weaken TLS behavior
* expose hidden administrative access

---

## Observability Ownership

Observability conventions belong ONLY to:

* telemetry infrastructure
* logging governance
* tracing infrastructure

Feature modules must NOT:

* redefine logging formats
* suppress infrastructure metrics
* bypass trace propagation

---

## Kubernetes Ownership

Kubernetes deployment semantics belong ONLY to:

* infrastructure modules
* deployment systems
* GitOps workflows

Application modules must NOT:

* assume fixed topology
* self-manage deployment lifecycle
* hardcode infrastructure assumptions

---

## Runtime Ownership

Runtime lifecycle behavior belongs ONLY to:

* Spring infrastructure
* platform lifecycle modules
* infrastructure orchestration layers

Application code must NOT:

* mutate runtime infrastructure dynamically
* introduce hidden background lifecycle behavior
* bypass graceful shutdown handling

---

## API Ownership

Public API contracts belong ONLY to:

* shared API modules
* platform contracts
* enterprise compatibility governance

Internal refactoring must NOT:

* leak into public APIs
* break extension semantics
* force consumer rewrites unexpectedly

---

# Enterprise Rules

Generated backend infrastructure must:

* support long-term maintenance
* remain cloud-native
* support operational debugging
* avoid vendor lock-in
* minimize migration risk

Prefer explicit backend architecture over hidden framework behavior.

---

# Anti-Patterns

Forbidden:

* field injection
* static mutable state
* hidden retries
* reflection abuse
* singleton deployment assumptions
* local synchronization assumptions
* startup side effects
* invisible async execution
* hardcoded infrastructure topology
* Java native serialization
* blocking startup loops
* hidden background threads
* dynamic dependency versions
* unbounded resource usage
* implicit runtime coupling

Avoid backend infrastructure magic.
