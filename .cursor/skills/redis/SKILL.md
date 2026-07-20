---
name: redis
description: Defines enterprise Redis and KeyDB architecture, correctness, lifecycle, security, resilience, Spring integration, testing, and operational standards. Use when Codex designs, reviews, refactors, implements, or validates an existing or explicitly approved Redis/KeyDB integration for caching, distributed coordination, locking, rate limiting, short-lived state, pub/sub, streams, leader election, or platform synchronization. Do not introduce Redis or KeyDB for a general distributed-state problem unless the repository already uses it or the task explicitly approves it.
---

# Redis and KeyDB Platform Standards

## Objective

Apply production-grade Redis and KeyDB standards to existing or explicitly approved integrations.

Treat Redis-compatible infrastructure as remote, failure-prone, latency-sensitive, operationally shared distributed infrastructure. Do not model it as a local map, a strongly consistent database, an exactly-once messaging system, an authorization system, or an unlimited cache.

The Platform Tracing solution is pre-production. Breaking key, serializer, configuration, SPI, package, bean, or module changes are allowed when they materially improve distributed correctness, security, lifecycle, operability, testability, ownership, or production readiness.

Do not preserve accidental key formats, unsafe defaults, serializers, holders, retry loops, client abstractions, or compatibility paths without an approved requirement.

## Applicability gate

Before proposing Redis-related code, verify:

1. the repository already contains a Redis/KeyDB integration or the task explicitly requires one
2. the problem genuinely needs distributed shared state
3. an in-memory structure, database, Kafka, Kubernetes Lease, or existing control-plane owner is not more appropriate
4. Redis failure and consistency semantics satisfy the requirement
5. the data is reconstructable or intentionally ephemeral unless persistence is explicitly designed
6. the deployment and operational owner are known

If these facts are not verified, return `INSUFFICIENT_EVIDENCE` instead of introducing Redis.

## Priority

When requirements conflict, prefer:

1. distributed correctness and safety
2. explicit ownership and failure semantics
3. security and tenant isolation
4. bounded data, TTL, and cardinality
5. atomicity and concurrency correctness
6. topology and failover correctness
7. operability and diagnostics
8. dependency and configuration hygiene
9. testability with real Redis-compatible infrastructure
10. performance
11. implementation convenience
12. compatibility with pre-production behavior

## Core workflow

1. Read applicable repository instructions, ADRs, module rules, and deployment assumptions.
2. Pass the applicability gate.
3. Identify the capability owner, operational owner, clients, topology, and supported engines.
4. Define consistency, availability, durability, ordering, and failure requirements.
5. Inventory keys, namespaces, serializers, TTLs, cardinality, commands, scripts, retries, locks, and listeners.
6. Load the relevant files from `references/`.
7. Identify split-brain, stale-owner, eviction, reconnect, retry-storm, hot-key, and partial-failure risks.
8. Define atomicity boundaries and idempotency.
9. Define fail-open, fail-closed, degraded, recovery, and rollback behavior for each capability.
10. Implement the smallest coherent integration without leaking Redis client types into public domain API.
11. Add unit, integration, failover, concurrency, negative, and architecture tests proportional to risk.
12. Run real Redis/KeyDB tests where runtime semantics matter.
13. Report verified facts, assumptions, topology, key contract, failure matrix, tests, and residual risks.

## Mandatory invariants

- Do not expose `RedisTemplate`, Lettuce, Redisson, connection factories, commands, serializers, or key formats through public tracing API.
- Keep connection creation and Spring configuration outside tracing domain logic.
- Give every key a governed namespace, version, ownership, TTL/lifecycle, cardinality bound, and serialization contract.
- Do not use Redis as the sole store for irreplaceable state unless persistence and recovery are explicitly designed.
- Use atomic commands, transactions, or Lua/functions only with explicitly documented correctness boundaries.
- Do not treat a distributed lock without fencing as sufficient protection for external side effects.
- Bound lock leases, waits, retries, cache entries, values, collections, streams, and metrics cardinality.
- Define behavior for timeout, disconnect, reconnect, failover, eviction, replica lag, partial execution, and stale ownership.
- Make retries bounded, classified, jittered, and idempotency-aware.
- Do not put credentials, secrets, raw PII, or unbounded user input into keys, values, logs, metrics, or traces.
- Do not run destructive Redis commands against broad or unresolved targets.
- Do not replace integration or failover evidence with mocks when Redis semantics are material.
- Keep engine-specific behavior explicit; do not assume Redis and KeyDB are identical without tests.
- Add architecture rules preventing Redis implementation leakage into API and domain modules.

## Reference selection

Read only the references relevant to the task, except for required combinations.

### Foundations, ownership, engines, and clients

Read [foundations-and-ownership.md](references/foundations-and-ownership.md).

Use it for applicability, platform ownership, Redis versus KeyDB support, client selection, and architectural boundaries.

### Spring integration, connections, topology, namespace, and keys

Read [spring-topology-and-keys.md](references/spring-topology-and-keys.md).

Use it for Spring Boot configuration, connection factories, pooling, cluster/sentinel/replication behavior, failover, namespaces, and key design.

### TTL, atomicity, locks, fencing, caching, and serialization

Read [lifecycle-coordination-and-cache.md](references/lifecycle-coordination-and-cache.md).

Use it for expiration, lifecycle, distributed coordination, lock safety, fencing tokens, cache policy, and serialization evolution.

### Data bounds, pub/sub, streams, rate limiting, and telemetry

Read [data-messaging-and-observability.md](references/data-messaging-and-observability.md).

Use it for data size/cardinality, notification semantics, stream ownership, rate limiting, tracing, metrics, and diagnostics.

### Failure semantics, retries, security, Kubernetes, and Testcontainers

Read [resilience-security-and-deployment.md](references/resilience-security-and-deployment.md).

Use it for fail-open/fail-closed behavior, retry policy, circuit breaking, degradation, credentials, TLS, ACLs, deployment topology, readiness, failover, and container-backed tests.

### Spring tests, architecture governance, dangerous operations, and reports

Read [testing-governance-and-verification.md](references/testing-governance-and-verification.md).

Use it for Spring testing, fitness rules, prohibited commands/patterns, imports, breaking changes, anti-patterns, required verification, and the final report.

## Required reference combinations

For every Redis/KeyDB implementation or refactoring, read:

1. `foundations-and-ownership.md`
2. `spring-topology-and-keys.md`
3. `resilience-security-and-deployment.md`
4. `testing-governance-and-verification.md`

Also read:

- `lifecycle-coordination-and-cache.md` for caching, TTL, locks, atomicity, or serialization
- `data-messaging-and-observability.md` for pub/sub, streams, rate limiting, telemetry, or high-cardinality data

## Completion standard

Do not report completion until:

- the applicability gate is satisfied
- capability and operational ownership are explicit
- topology and supported engine behavior are explicit
- key namespace, serialization, TTL, and cardinality are governed
- consistency, atomicity, idempotency, and failure semantics are documented
- retries and degradation behavior are bounded
- security and privacy requirements are verified
- real integration/failover/concurrency tests run where required
- architecture and dependency gates pass
- rollout, recovery, and rollback are explicit
- assumptions and residual risks are reported

