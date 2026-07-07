# Redis and KeyDB Platform Standards

## Context

This repository contains enterprise-grade Spring Boot platform starters, infrastructure modules, distributed coordination components, and Redis/KeyDB integrations.

Redis-compatible infrastructure is used for:

* distributed locks
* caching
* coordination
* rate limiting
* pub/sub
* ephemeral state
* platform synchronization

Supported engines:

* Redis OSS
* KeyDB

Generated Redis-related code must be:

* horizontally scalable
* observable
* failure-tolerant
* namespace-safe
* Kubernetes-compatible
* operationally predictable

---

# Priority

This skill has very high priority for:

* Redis integrations
* KeyDB integrations
* distributed locks
* distributed coordination
* cache infrastructure
* platform synchronization modules

When conflicts occur:

* distributed safety has priority
* namespace stability has priority
* operational visibility has priority

---

# Redis Principles

Redis must be treated as:

* distributed infrastructure
* eventually consistent coordination storage
* ephemeral infrastructure component

Avoid:

* treating Redis as primary persistent storage
* relying on strong consistency guarantees
* storing critical irreplaceable state only in Redis

Prefer:

* short-lived coordination data
* cacheable data
* distributed synchronization primitives
* infrastructure state with TTL

---

# Namespace Standards

All Redis keys must use explicit namespaces.

Required format: <environment>:<service>:<domain>:<entity>:<identifier>

Examples:
prod:billing:lock:invoice:123
prod:auth:cache:user:456
stage:platform:leader-election:instance:node-1

Namespaces must:

* be stable
* be deterministic
* avoid collisions
* support multi-tenant environments

Avoid:

* flat keys
* ambiguous prefixes
* dynamically generated namespace structures

---

# Key Naming

Keys must:

* use lowercase
* use colon separators
* remain human-readable
* expose operational meaning

Avoid:

* spaces
* random prefixes
* opaque abbreviations
* environment-specific inconsistencies

Forbidden:
cache123
temp-key
lock_abc

Preferred:
prod:payments:cache:customer:42

---

# TTL Policy

All ephemeral Redis entries must define TTLs.

Required for:

* locks
* cache entries
* coordination state
* temporary tokens

Avoid:

* infinite TTLs for temporary state
* forgotten lock expiration
* orphaned coordination keys

Prefer:

* explicit expiration policies
* conservative TTLs
* automatic cleanup

---

# Distributed Locks

Distributed locks must:

* support expiration
* tolerate process crashes
* expose observability hooks
* support retry policies

Prefer:

* SET NX EX patterns
* Redisson where justified
* fencing-token patterns for critical coordination

Avoid:

* indefinite locks
* local in-memory lock assumptions
* lock ownership ambiguity

Locks must NEVER:

* assume node permanence
* rely on synchronized clocks
* block indefinitely

---

# Serialization

Redis serialization must:

* remain backward compatible
* support schema evolution
* tolerate unknown fields

Prefer:

* JSON serialization
* version-tolerant payloads
* explicit serializers

Avoid:

* Java native serialization
* incompatible binary formats
* reflection-heavy serialization

Forbidden:

* ObjectOutputStream serialization
* platform-dependent payload formats

---

# Cache Design

Caches must:

* tolerate eviction
* tolerate cache misses
* tolerate partial invalidation

Applications must NOT:

* depend on cache existence
* assume cache consistency
* rely on synchronized cache updates

Prefer:

* read-through patterns
* explicit invalidation
* idempotent recomputation

Avoid:

* giant cache entries
* unbounded cache growth
* cache stampede risks

---

# Pub/Sub

Redis pub/sub must:

* tolerate message loss
* tolerate subscriber restart
* avoid critical business guarantees

Prefer:

* lightweight notifications
* ephemeral signaling
* operational broadcasts

Avoid:

* critical guaranteed delivery
* transactional assumptions
* durable workflow orchestration

For durable messaging:

* prefer Kafka or dedicated messaging infrastructure

---

# Redis Cluster Compatibility

Generated Redis logic must:

* tolerate cluster topology changes
* avoid cross-slot assumptions
* support failover

Prefer:

* cluster-safe key patterns
* hash tags only when justified
* topology-aware clients

Avoid:

* hardcoded node assumptions
* direct node targeting
* manual failover logic

---

# Kubernetes Compatibility

Redis integrations must:

* tolerate pod restarts
* tolerate transient connection failures
* support rolling deployments

Avoid:

* startup hard dependency loops
* blocking startup indefinitely
* local Redis assumptions

Prefer:

* resilient reconnect behavior
* readiness-aware initialization
* infrastructure retries with observability

---

# Connection Management

Redis clients must:

* use pooling where appropriate
* expose timeout configuration
* support reconnect behavior

Prefer:

* Lettuce
* reactive-safe integrations
* connection health monitoring

Avoid:

* unmanaged connections
* blocking connection creation
* infinite retry loops

---

# Observability

Redis integrations must expose:

* connection metrics
* command latency
* lock metrics
* retry metrics
* timeout metrics

Prefer:

* Micrometer integration
* distributed tracing
* structured logs

Avoid:

* silent retries
* hidden lock contention
* invisible reconnect loops

---

# Error Handling

Redis failures must:

* preserve operational context
* expose retry behavior
* support graceful degradation

Prefer:

* fallback behavior
* explicit timeout handling
* infrastructure-aware retries

Avoid:

* swallowing Redis exceptions
* hidden degradation
* infinite reconnect behavior

---

# Testing

Redis integrations must be tested using:

* Testcontainers
* KeyDB containers
* Redis OSS containers

Tests must validate:

* TTL behavior
* lock expiration
* reconnect behavior
* serialization compatibility
* namespace correctness

Avoid:

* embedded Redis
* environment-dependent Redis tests
* fixed port assumptions

---

# Security

Redis deployments must:

* support authentication
* support TLS where applicable
* avoid exposing public endpoints

Never:

* log Redis credentials
* expose secret configuration
* hardcode passwords

Prefer:

* Kubernetes Secrets
* externalized configuration
* secure credential rotation

---

# Anti-Conflict Rules

## Namespace Ownership

Redis namespace structure belongs ONLY to:

* platform Redis infrastructure
* shared coordination modules
* platform cache conventions

Application modules must NOT:

* invent incompatible namespace patterns
* redefine shared prefixes
* create conflicting key hierarchies

---

## Lock Ownership

Distributed lock semantics belong ONLY to:

* coordination infrastructure
* distributed lock modules
* platform synchronization services

Application code must NOT:

* implement incompatible lock semantics
* bypass lock observability
* redefine lock ownership rules

---

## Cache Ownership

Cache infrastructure belongs ONLY to:

* cache platform modules
* Redis infrastructure
* shared cache conventions

Feature modules must NOT:

* redefine shared cache semantics
* implement hidden invalidation behavior
* introduce incompatible TTL policies

---

## Serialization Ownership

Redis serialization formats belong ONLY to:

* shared serialization infrastructure
* platform protocol modules

Application modules must NOT:

* introduce incompatible payload formats
* bypass serializer governance
* use platform-dependent serialization

---

## Connection Ownership

Redis connection lifecycle belongs ONLY to:

* Redis infrastructure modules
* connection management layers
* platform resilience modules

Application code must NOT:

* create unmanaged connections
* bypass infrastructure retry handling
* implement custom reconnect loops

---

## Retry Ownership

Retry semantics belong ONLY to:

* resilience infrastructure
* Redis platform modules
* observability-aware retry layers

Application modules must NOT:

* implement invisible retries
* suppress retry telemetry
* create unbounded retry behavior

---

## Infrastructure Ownership

Redis infrastructure behavior belongs ONLY to:

* platform infrastructure modules
* Kubernetes infrastructure layers
* deployment systems

Application modules must NOT:

* assume Redis topology
* mutate infrastructure dynamically
* hardcode Redis deployment behavior

---

# Enterprise Rules

Generated Redis-related code must:

* support long-term operational maintenance
* remain horizontally scalable
* support distributed deployments
* avoid vendor lock-in
* remain operationally debuggable

Prefer explicit distributed coordination over hidden synchronization magic.

---

# Anti-Patterns

Forbidden:

* infinite locks
* missing TTLs
* Java native serialization
* hardcoded Redis endpoints
* blocking reconnect loops
* hidden retries
* cache-dependent correctness
* cluster topology assumptions
* local singleton coordination assumptions
* cross-slot unsafe operations
* unbounded key growth
* flat namespace structures
* embedded Redis in production testing
* startup deadlocks on Redis availability
* silent Redis degradation

Avoid distributed systems magic.
