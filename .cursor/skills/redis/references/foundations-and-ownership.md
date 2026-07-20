# Redis Foundations and Ownership

## Context

This repository contains an enterprise platform tracing solution for Spring Boot microservices.

This skill applies **only** when a task touches an existing or explicitly approved Redis/KeyDB integration, such as:

- distributed coordination
- caching
- rate limiting
- short-lived runtime state
- pub/sub notifications
- leader election
- synchronization between platform components

Do not introduce Redis or KeyDB merely because distributed storage might be convenient.

The tracing solution is currently **pre-production**.

Breaking changes are allowed when they materially improve:

- distributed correctness
- operational predictability
- security
- namespace governance
- failure handling
- testability
- dependency hygiene
- production readiness

Do not preserve accidental key formats, serializers, holders, SPIs, retry loops, or client abstractions only for backward compatibility unless an ADR explicitly requires it.

Architects will not accept cosmetic refactoring. Every substantial Redis change must close a real correctness, scalability, security, operability, or ownership problem.

## Applicability Gate

Before proposing Redis-related code, verify:

1. A repository module already uses Redis/KeyDB, or the task explicitly requires it.
2. The problem genuinely needs distributed shared state.
3. A local in-memory structure, database, Kafka, Kubernetes lease, or existing control-plane state is not a better owner.
4. Redis failure semantics are acceptable for the business/platform requirement.
5. The data is reconstructable or safely ephemeral unless persistence is explicitly designed.
6. The operational owner of the Redis deployment is known.

If these facts are not verified, mark the proposal `INSUFFICIENT_EVIDENCE` instead of adding Redis.

## Primary Principles

Treat Redis-compatible infrastructure as:

- remote distributed infrastructure
- failure-prone and latency-sensitive
- usually ephemeral
- eventually consistent for many coordination patterns
- operationally shared
- capable of eviction, failover, reconnect, and partial availability

Do not treat Redis as:

- a local map
- a strongly consistent database
- a primary source of irreplaceable state
- an exactly-once messaging system
- an authorization system
- an unlimited cache
- an invisible implementation detail

Prefer explicit distributed semantics over “magic” helpers.

## Ownership

Redis concerns belong in dedicated infrastructure or integration modules.

### Allowed owners

- Redis/KeyDB infrastructure modules
- platform cache modules
- distributed coordination modules
- resilience/integration modules
- Spring Boot auto-configuration dedicated to Redis-backed capabilities

### Forbidden ownership

Public tracing API must not expose:

- `RedisTemplate`
- Lettuce types
- Redisson types
- connection factories
- Redis commands
- Redis key formats
- serializer implementation types

Tracing core domain logic must not create Redis connections or read Spring configuration directly.

Spring auto-configuration may wire Redis-backed implementations, but the distributed policy and invariants must remain in the appropriate core/infrastructure layer.

## Engine Support

Supported engines must be explicit.

Potentially supported:

- Redis OSS
- KeyDB

Do not claim compatibility with:

- Redis Cluster
- Sentinel
- KeyDB active-active
- managed cloud variants
- Valkey
- enterprise modules

unless executable tests or documented evidence exist.

If Redis and KeyDB differ in command behavior, replication, eviction, pub/sub, or scripting, document and test the supported subset.

## Client Selection

Prefer the client already standardized by the repository.

Typical choices:

- Lettuce for Spring Data Redis and reactive/non-blocking integration
- Redisson when a mature distributed primitive materially reduces correctness risk

Do not add Redisson only to avoid writing a few commands.

Do not implement a custom Redis client.

### Lettuce

Use Lettuce when:

- Spring Data Redis is already in use
- command-level access is sufficient
- reactive support is required
- connection lifecycle is managed by Spring

### Redisson

Use Redisson only when:

- distributed locks, semaphores, maps, or fencing-like primitives are genuinely required
- its behavior has been reviewed against failure scenarios
- dependency and operational cost are justified

Do not assume Redisson primitives provide linearizability or protect an external resource without additional fencing.

