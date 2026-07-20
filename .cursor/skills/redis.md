# Redis and KeyDB Platform Standards

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

## Spring Boot Integration

Use typed configuration:

- `@ConfigurationProperties`
- explicit defaults
- configuration metadata
- validation
- `ApplicationContextRunner` tests
- optional-classpath tests

Avoid:

- scattered `@Value`
- direct `Environment` reads
- static connection holders
- eager connections during ordinary auto-configuration
- unconditional Redis beans when the capability is disabled
- hidden fallback from distributed to local behavior

Disabled behavior must be explicit.

If Redis is optional:

- unrelated tracing functionality must still start
- no Redis classes may be eagerly loaded when absent
- condition reports should explain why the integration backed off

If Redis is mandatory for a capability:

- fail startup clearly when the capability is enabled but unusable
- do not silently downgrade to unsafe local coordination

## Connection Management

Connection lifecycle belongs to Spring/infrastructure integration.

Requirements:

- bounded connect timeout
- bounded command timeout
- bounded reconnect behavior
- explicit shutdown
- health/readiness diagnostics
- no per-operation connection creation
- no infinite retry loop
- no hidden thread creation without lifecycle ownership

Use pooling only when the client/command model benefits from it. Lettuce connections are thread-safe for many use cases; do not add a pool mechanically.

Do not expose the native client through public platform APIs.

## Topology and Failover

Do not assume a single permanent Redis node.

Support level must be explicit:

- standalone
- Sentinel
- cluster
- managed service
- KeyDB multi-master/active-replica

For each supported topology, define:

- discovery
- failover behavior
- timeout behavior
- retry behavior
- read preference
- write routing
- health semantics
- test evidence

Avoid:

- hard-coded node addresses
- direct node targeting
- manual failover logic in application code
- topology assumptions in key design
- assuming a retry always reaches the same primary

## Namespace Governance

Every key must have an explicit, documented namespace.

Recommended logical structure:

```text
<platform>:<environment>:<service-or-owner>:<domain>:<entity>:<identifier>
```

Example:

```text
platform:prod:tracing:control:lease:collector-1
platform:stage:tracing:cache:service-policy:orders
```

The exact format must match repository standards. Do not introduce a second convention.

Namespace components must be:

- deterministic
- bounded
- lower-case where practical
- free of secrets and raw PII
- stable across restarts
- safe for multi-environment deployments

Do not place raw:

- trace IDs
- span IDs
- request IDs
- user IDs
- email addresses
- access tokens
- arbitrary URLs
- exception messages

into Redis key names unless an explicit threat/cardinality review approves it.

## Key Design

Keys should be:

- human-debuggable
- collision-resistant by construction
- bounded in length
- stable
- compatible with the supported topology

Avoid:

- flat keys
- random prefixes
- unbounded user-controlled suffixes
- timestamps as uniqueness strategy without ownership
- dynamic namespace structures
- opaque abbreviations without documentation

For Redis Cluster multi-key operations, either:

- design keys to share an intentional hash slot using a documented hash tag, or
- avoid cross-key atomicity assumptions

Do not add hash tags casually; they can create hot slots.

## TTL and Lifecycle

Every ephemeral key must have an explicit lifecycle.

TTL is normally required for:

- locks
- leases
- idempotency markers
- caches
- temporary coordination state
- deduplication markers
- rate-limit windows
- transient snapshots

For each key family, document:

- TTL
- refresh policy
- deletion policy
- ownership
- behavior after expiry
- behavior after eviction
- maximum expected cardinality

Avoid:

- immortal temporary keys
- forgotten lock keys
- unbounded deduplication sets
- refresh-on-read without analysis
- synchronized expiry storms

Add TTL jitter when many keys may expire simultaneously and a stampede is possible.

## Atomicity

Do not model a multi-step correctness invariant as separate Redis commands unless partial execution is safe.

Use one of:

- a single atomic command
- `SET` options such as `NX`, `XX`, `EX`, `PX`
- a reviewed Lua script
- a transaction when its semantics are sufficient
- a mature library primitive
- an external database transaction instead of Redis

Lua scripts must be:

- bounded
- deterministic
- documented
- tested
- free of unbounded key scans
- versioned with the calling code
- safe under retries

Do not assume `MULTI/EXEC` gives database-style rollback or isolation.

## Distributed Locks

A Redis lock is not automatically sufficient protection for an external resource.

Required analysis:

- ownership token
- TTL
- renewal behavior
- crash behavior
- failover behavior
- stale-owner behavior
- maximum critical-section duration
- whether fencing is required

Minimum safe release pattern:

- store a unique owner token
- delete only when the stored token matches
- perform check-and-delete atomically

Never release a lock with an unconditional `DEL`.

For critical external side effects, use fencing tokens or another system that rejects stale owners.

Avoid:

- indefinite locks
- lock acquisition without timeout
- renewal without ownership checks
- assuming synchronized clocks
- local JVM locks as distributed protection
- retrying forever
- hiding lock contention

Redlock or multi-node lock algorithms require a separate architectural review. Do not introduce them as a default.

## Fencing Tokens

Use fencing when an old lock holder could continue operating after lease expiry.

Requirements:

- monotonically increasing token
- downstream resource validates the token
- stale token is rejected
- token generation is atomic
- wraparound and reset behavior are considered

A token that is generated but not checked by the protected resource is not fencing.

## Caching

Caches must not own correctness.

Applications must tolerate:

- misses
- eviction
- stale data
- partial invalidation
- Redis unavailability
- reconnects

Prefer:

- cache-aside
- read-through only with explicit ownership
- idempotent recomputation
- bounded values
- explicit invalidation
- TTL plus versioning when needed

Avoid:

- cache-dependent business correctness
- giant values
- unbounded keys
- synchronized refresh storms
- caching failures indefinitely
- storing mutable Java object graphs
- making a cache key from raw high-cardinality tracing data

For stampede protection, choose explicitly among:

- request coalescing
- probabilistic early refresh
- short lease
- stale-while-revalidate
- bounded local cache in front of Redis

## Serialization

Do not use Java native serialization.

Allowed formats must be explicitly chosen.

Possible choices:

- UTF-8 strings
- JSON with explicit models
- compact binary protocol with an approved schema
- Redis hashes for simple field models

Serialization must define:

- schema/version
- required and optional fields
- unknown-field behavior
- size limits
- migration strategy
- security constraints

The tracing solution is pre-production, so breaking serialization changes are allowed when justified. Prefer a direct format migration and deletion of obsolete readers/writers over permanent dual-read/dual-write compatibility unless an ADR requires staged migration.

Do not claim backward compatibility as a default goal in this phase.

Avoid:

- reflection-heavy polymorphic deserialization
- arbitrary class names
- default Java object mappers with hidden typing
- storing framework implementation classes
- serializing secrets into cache values

## Data Size and Cardinality

Define limits for:

- key length
- value size
- collection length
- hash field count
- stream length
- pub/sub payload size
- route/policy map cardinality
- number of keys per service/environment

Reject or truncate only according to explicit policy. Silent truncation is usually unsafe.

Do not store unbounded telemetry data in Redis.

Redis must not become a secondary trace backend.

Do not store complete spans, logs, stack traces, request bodies, or arbitrary baggage in Redis unless a separately approved feature explicitly requires it.

## Pub/Sub

Redis Pub/Sub is ephemeral.

Use it only for:

- best-effort notifications
- cache invalidation hints
- non-critical operational broadcasts
- events that can be reconstructed

Consumers must tolerate:

- message loss
- subscriber restart
- duplicate handling at the application level
- periods of disconnection

Do not use Pub/Sub for:

- durable workflow
- audit trail
- exactly-once delivery
- critical policy application
- guaranteed configuration propagation

Use Kafka, a durable stream, database outbox, or another approved system for durable requirements.

## Streams

If Redis Streams are used, define:

- consumer group ownership
- pending-entry recovery
- acknowledgment policy
- retry/dead-letter policy
- retention/max length
- duplicate handling
- observability
- failover behavior

Do not introduce Streams as a “more durable Pub/Sub” without an operational model.

## Rate Limiting

A Redis-backed rate limiter must define:

- algorithm
- time source assumptions
- atomicity
- burst semantics
- failure policy
- key cardinality
- TTL
- tenant isolation
- observability

Do not use raw user identifiers in metric labels or logs.

Choose fail-open vs fail-closed explicitly according to the protected capability.

For security-sensitive mutation or admin operations, fail-closed is usually preferred.

For non-critical telemetry enrichment, degradation may be safer than blocking the application.

## Tracing and Observability

Redis instrumentation must not recursively destabilize the tracing system.

Rules:

- do not create custom spans when approved Redis client instrumentation already provides them unless a gap is proven
- prevent duplicate spans
- use low-cardinality span names
- do not attach full Redis keys by default
- do not record command payloads or values
- do not expose credentials
- normalize or redact key namespaces if attributes are emitted
- keep metric labels low cardinality
- bound command/error diagnostics

Acceptable attributes may include:

- operation/command category
- database index when safe
- outcome
- server endpoint according to platform policy
- bounded namespace category, not full key

Do not use:

- full Redis key
- raw Lua script
- request ID
- user/account ID
- value content
- auth data

as span attributes or metric labels.

The tracing platform must not rely on Redis observability to function.

## Failure Semantics

Choose failure behavior per capability.

### Cache

Usually degrade to miss/recompute when safe.

### Distributed lock / coordination

Usually fail the protected operation or fall back only to an explicitly safe path.

### Rate limiting

Fail-open or fail-closed must be documented and tested.

### Pub/Sub notification

Usually tolerate loss if the event is reconstructable.

### Runtime-control state

Do not place last-known-good tracing control state solely in Redis unless a separate architecture decision defines durability, consistency, and recovery.

Do not hide Redis outages behind success responses.

## Retries

Retries must be:

- bounded
- observable
- classified by error type
- safe for the operation
- jittered where appropriate
- stopped on non-retryable errors

Do not retry non-idempotent operations unless idempotency is guaranteed.

Avoid retry multiplication across:

- Redis client
- Spring Retry/Resilience4j
- application loop
- Kubernetes restart
- load balancer

One layer should own the retry policy.

## Circuit Breaking and Degradation

Circuit breakers may protect the application from repeated Redis failures.

Requirements:

- capability-specific fallback
- metrics
- bounded open/half-open behavior
- no hidden correctness loss
- test coverage

Do not apply one global fallback to caches, locks, rate limiters, and coordination; their safety requirements differ.

## Security

Redis deployments and clients must support:

- authentication
- TLS where required
- certificate validation
- credential rotation
- least-privilege ACLs
- restricted network exposure
- safe diagnostics

Never:

- hard-code passwords
- log credentials
- expose connection URLs with secrets
- use trust-all TLS
- disable hostname verification
- expose Redis publicly
- dump full configuration/environment

Credentials must not appear in:

- tracing attributes
- metrics
- Actuator endpoints
- JMX
- exception messages
- test reports
- snapshots

## Kubernetes and Deployment

Redis integration must tolerate:

- pod restart
- rolling deployment
- DNS updates
- failover
- transient disconnection
- rescheduling

Do not assume:

- local Redis
- stable pod IP
- one availability zone
- infinite startup wait
- privileged access

Readiness semantics must match the capability.

Do not make the entire application unready because an optional cache is temporarily unavailable unless that policy is explicitly approved.

## Testcontainers

Use real Redis/KeyDB containers for integration behavior.

Do not use embedded Redis.

Tests must cover the supported engine/topology claims.

At minimum, depending on the capability:

- connect/startup
- TTL and expiry
- serialization
- namespace
- reconnect
- failover simulation where feasible
- lock ownership and release
- stale lock behavior
- script behavior
- cache miss/eviction behavior
- pub/sub loss assumptions
- rate-limit boundaries

Use:

- pinned image versions
- dynamic ports
- `container.getHost()`
- `getMappedPort(...)`
- explicit readiness
- bounded Awaitility

Do not use:

- fixed localhost
- fixed ports
- developer-specific Docker paths
- `Thread.sleep`
- real shared Redis
- test order dependence

For the known remote Docker environment:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
```

Test code must still resolve host/ports through Testcontainers.

A skipped opt-in integration test is not runtime evidence.

## Spring Testing

Use `ApplicationContextRunner` for:

- disabled integration
- enabled integration
- missing Redis classes
- custom bean override
- property defaults
- invalid configuration
- no eager connection
- bean absence

Use `@SpringBootTest` only when the full lifecycle or real integration is required.

Use `FilteredClassLoader` for optional classpath tests.

Do not allow a user-provided client bean to bypass mandatory namespace, timeout, serialization, or security policies unless the extension contract explicitly permits it.

## Architecture Fitness Rules

Protect at least:

- public API does not expose Redis client/framework types
- core tracing code does not depend directly on Spring Data Redis/Redisson
- Redis integration lives in the approved module
- no Java native serialization
- no hard-coded endpoints or credentials
- no fixed TTL-free lock implementation
- no unmanaged native connections
- no wildcard key scans such as production `KEYS *`
- no Redis dependency when the feature is disabled/absent
- servlet/reactive adapters do not own Redis infrastructure logic

Do not weaken architecture rules to accommodate generated code.

## Dangerous Commands and Patterns

Avoid in production paths:

- `KEYS`
- unbounded `SCAN`
- `FLUSHALL`
- `FLUSHDB`
- `EVAL` with unreviewed scripts
- broad deletes by pattern
- blocking list operations without bounded timeout
- transactions over unrelated keys
- unrestricted `MONITOR`
- synchronous administrative commands from request paths

Administrative operations require a separate operational/security review.

## Imports and Generated Code

For Cursor, Codex, and Perplexity-generated Redis changes:

- read project context, Spring, security, testing, and Testcontainers skills first
- inspect existing Redis support before adding abstractions
- verify Redis is actually required
- follow `.editorconfig`
- use explicit imports
- do not use wildcard imports
- do not create import-only churn
- do not duplicate connection factories, serializers, namespaces, or retry policies
- add negative/failure tests
- run affected compile/test tasks
- state whether Testcontainers tests executed or were skipped

Generated code must not add Redis as a speculative dependency.

## Breaking-Change Policy

Because the tracing solution is pre-production, prefer cleanup over compatibility preservation.

Breaking changes are justified when they:

- remove unsafe serializers
- replace ambiguous key namespaces
- remove hidden local fallbacks
- delete false extension points
- eliminate duplicated retry or connection ownership
- replace unsafe locks
- move Redis types out of public API
- introduce explicit TTL/size limits
- remove unsupported engine/topology claims

Do not keep dual key formats or dual serializers indefinitely. If migration is required, define a bounded migration window and deletion criteria.

## Anti-Patterns

Forbidden:

- adding Redis without a proven distributed-state requirement
- treating Redis as primary irreplaceable storage
- Java native serialization
- fixed host/port assumptions
- hard-coded credentials
- `latest` image tags
- infinite retries
- infinite locks
- unconditional lock release
- locks without ownership token
- critical external side effects without fencing where stale owners are possible
- cache-dependent correctness
- unbounded keys or values
- raw PII/trace IDs in key names
- full keys as telemetry labels
- unbounded warning-deduplication state
- hidden local fallback for distributed coordination
- Redis Pub/Sub for durable workflows
- blocking Redis calls on reactive event-loop threads
- startup network calls for optional features
- production `KEYS *`
- tests that require container reuse
- skipped integration tests reported as pass
- speculative Redis abstractions in tracing modules

## Required Verification

For a Redis-related change, run the narrowest applicable tasks:

```powershell
.\gradlew.bat :<affected-module>:compileJava --no-daemon
.\gradlew.bat :<affected-module>:test --no-daemon
.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
```

For Docker-backed integration:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :<affected-module>:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

Run relevant scans:

- wildcard imports
- Java native serialization
- `KEYS`/`FLUSHALL`/`FLUSHDB`
- hard-coded Redis endpoints
- hard-coded credentials
- missing TTL in lock/cache paths where statically detectable
- forbidden Redis client types in public API

## Required Report

A final Redis-related implementation report must include:

```text
Use case:
Why Redis/KeyDB is required:
Owner module:
Supported engine/topology:
Key namespace:
TTL/lifecycle:
Serialization format/version:
Failure policy:
Retry owner:
Security assumptions:
Observability:
Tests executed:
Testcontainers executed or skipped:
Architecture fitness:
Residual risks:
```

Use:

- `PASS` only when required tests executed and passed
- `PASS_WITH_WARNINGS` when code is green and only documented non-blocking operational risk remains
- `INSUFFICIENT_EVIDENCE` when a claimed engine/topology/failover behavior was not executed
- `FAIL` when correctness, security, TTL, ownership, or required integration gates are not satisfied
