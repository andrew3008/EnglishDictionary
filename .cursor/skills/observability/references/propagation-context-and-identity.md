# Propagation, Context, and Identity

## Propagation

Use W3C Trace Context.

Propagation must be tested for:

- HTTP servlet
- WebFlux
- messaging/Kafka
- async executors
- scheduled jobs
- remote span links
- agent/application boundaries

Incoming propagation data is untrusted.

Do not use trace context as authentication or authorization.

Do not maintain a custom `traceparent` parser when the approved OTel implementation is available.

Malformed propagation input should:

- be rejected or ignored according to the contract
- not throw uncontrolled exceptions
- not log the full raw header
- not create invalid remote context
- not mutate unrelated state

## Baggage

Baggage is cross-service data and may leave the trust boundary.

Rules:

- allowlist platform-owned keys
- impose entry count and total size limits
- do not put secrets or raw PII in baggage
- do not copy all baggage into span attributes or logs
- do not trust baggage for authorization
- do not create metric labels from baggage
- document propagation and stripping behavior

## Request and Correlation IDs

Request/correlation IDs are distinct from trace IDs.

They must be:

- bounded
- character-restricted
- safe for logs and headers
- generated when absent or invalid according to platform policy
- stable for the intended request boundary

Do not use request ID as a metric label.

Do not echo raw invalid input.

Request ID support should be a deterministic core capability, not a speculative SPI unless external implementations are genuinely required.

## Context Propagation

Context propagation must remain correct across:

- servlet threads
- Reactor operators
- executor pools
- `CompletableFuture`
- scheduled tasks
- messaging callbacks
- virtual threads if supported
- child processes in agent smoke tests

Do not assume `ThreadLocal` automatically propagates.

Use the approved OTel/Spring/Reactor context mechanisms.

Do not implement custom context copying without a demonstrated gap.

Tests must prove:

- active trace context visible at the intended point
- context does not leak to another request
- scope is restored after completion
- error paths restore context
- Reactor subscriptions do not share mutable context
- MDC and OTel context remain aligned where expected

## MDC

MDC is a logging integration, not the source of truth for tracing context.

Rules:

- derive MDC fields from validated/current trace context
- restore previous values
- remove values in `finally`/reactive lifecycle
- do not assume thread confinement in Reactor
- do not write raw baggage or secrets
- keep field set small and stable

Typical fields may include:

- trace ID
- span ID
- request/correlation ID
- bounded platform status/category

Do not mutate trace context from MDC.

