# Lifecycle, Coordination, and Cache

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

