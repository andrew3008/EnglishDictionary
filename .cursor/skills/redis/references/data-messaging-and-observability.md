# Data, Messaging, Rate Limiting, and Observability

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

