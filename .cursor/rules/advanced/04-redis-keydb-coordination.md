# Redis + KeyDB Coordination Policies

## Context
Redis/KeyDB is used for distributed coordination, caching, and ephemeral state.

---

## Core Principle

Redis is:
NOT a database
NOT a source of truth
ONLY an ephemeral coordination layer

---

## Key Design Rules

All keys MUST follow:

env:service:domain:entity:id

Example:
prod:billing:lock:invoice:123

---

## TTL Rules

All keys MUST have TTL unless explicitly persistent cache.

Forbidden:
- infinite TTL locks
- orphan keys without expiration

---

## Distributed Lock Rules

Locks MUST:
- have TTL
- have ownership semantics
- be retry-safe

Forbidden:
- infinite locks
- local lock assumptions
- lock without expiration

---

## Serialization Rules

Allowed:
- JSON
- versioned DTOs

Forbidden:
- Java serialization
- unsafe binary formats
- class-based deserialization

---

## Cluster Safety Rules

Must:
- avoid cross-slot issues
- support cluster failover
- tolerate node loss

---

## Observability Rules

Redis operations MUST expose:
- latency metrics
- lock contention metrics
- retry metrics

---

## Failure Rules

Redis failures MUST:
- degrade gracefully
- not block system startup
- not crash application

---

## Anti-Patterns
- cache as source of truth
- distributed coordination without TTL
- hidden retry loops
- silent lock failures