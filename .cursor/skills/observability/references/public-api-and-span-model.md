# Public Tracing API and Span Model

## Public Tracing API

The application-facing entry point should remain small and capability-oriented.

Current intended usage:

```java
TraceOperations traceOperations;

traceOperations.traceContext();
traceOperations.spans().operation("payment.process");
traceOperations.spans().transport();
traceOperations.spans().fromSpec(spec);
```

Do not expose:

- OpenTelemetry `SpanBuilder`
- SDK providers
- exporters
- processors
- mutable runtime state
- JMX handlers
- sampler implementations
- raw context mutation primitives

The API should describe what application code needs to do, not how OpenTelemetry implements it.

## Span Naming

Span names must be:

- stable
- low cardinality
- concise
- based on operation or normalized route
- independent from runtime IDs
- useful in backend aggregation

Preferred:

```text
payment.process
portfolio.recalculate
GET /orders/{id}
kafka.consume
database.query
```

Forbidden:

```text
payment.process.user-938274
GET /orders/128991
query for account@example.com
kafka.consume.customer-83934
```

Do not include:

- trace ID
- span ID
- request ID
- user/account ID
- raw URL
- query string
- payload data
- exception text
- dynamic timestamps

Use route templates, not raw paths.

## Span Granularity

Create spans for meaningful operations and boundaries.

Prefer:

- inbound/outbound boundaries
- messaging produce/consume operations
- database operations where standard instrumentation is absent or enriched safely
- important application operations
- batch steps
- scheduled jobs
- cross-process or cross-thread boundaries

Avoid:

- getter/setter spans
- every internal method
- loops producing one span per item without a volume budget
- excessive nesting
- spans around trivial computations
- duplicate transport spans

A span should represent work an operator may reasonably investigate.

## Span Relationship and Context

Parenting, rooting, detachment, and links must be explicit.

The platform's relationship model must not be confused with OpenTelemetry `SpanKind`.

Tests must cover:

- child behavior
- root behavior
- detached behavior
- links
- repeated mutually exclusive relationship calls
- context scope close behavior
- remote link conversion
- invalid combinations

Do not silently fall back to a default relationship after an explicit conflicting choice.

## Span Lifecycle

Span lifecycle must be deterministic.

Requirements:

- start exactly once
- end exactly once
- scope close is idempotent or fails according to the documented contract
- exceptions do not leak open spans/scopes
- terminal operations are clear
- no-op implementation preserves lifecycle expectations
- builder reuse rules are explicit
- spans are not shared across unsafe threads unless supported

Use `try/finally`, scoped helpers, or structured APIs to guarantee closure.

Do not rely on finalizers or garbage collection.

## No-Op Behavior

Disabled/no-op tracing is a supported runtime state.

No-op behavior must:

- preserve public method availability
- avoid network calls
- avoid hidden allocations where practical
- not mutate runtime state
- not throw merely because tracing is disabled
- preserve documented builder/lifecycle validation
- return safe trace-context views
- not create false diagnostics claiming telemetry was exported

Test no-op behavior independently from active runtime behavior.

## Span Status and Errors

Use span status intentionally.

Do not mark every exception as `ERROR` automatically without considering operation semantics and existing instrumentation behavior.

Record exception events only when:

- they add diagnostic value
- they do not duplicate an existing agent event
- sensitive exception content is controlled
- event count is bounded

Do not attach raw exception messages blindly.

Prefer:

- exception type
- bounded/sanitized message where policy permits
- mapped status
- operation-specific failure classification

Avoid duplicate exception events.

## Span Events

Events are not a replacement for logs.

Use events for important span-local milestones, such as:

- retry exhausted
- fallback selected
- message batch partially processed
- controlled state transition

Do not create events for every loop iteration.

Do not attach:

- request/response bodies
- message payloads
- credentials
- unbounded maps
- raw stack traces without policy
- sensitive business data

Event names and attributes must be low cardinality.

## Span Links

Use links when work relates to traces that are not the active parent, such as:

- batch consumption
- fan-in
- async aggregation
- retry/replay relationship where parentage is not correct

Links must use validated trace context.

Do not attach arbitrary baggage or user data to link metadata.

Test:

- valid remote link
- malformed traceparent
- zero IDs
- flags
- link count limits
- batch link behavior

Bound the number of links per span.

## Attributes

Attributes must be:

- operationally useful
- type-safe
- bounded
- low cardinality where used for aggregation
- semantically stable
- scrubbed according to policy

Do not use user input as an attribute key.

Prefer approved semantic conventions and platform-owned namespaces.

Examples of safer attributes:

- normalized operation category
- normalized route
- transport type
- bounded result enum
- service identity
- semconv version marker
- platform decision code

High-risk attributes include:

- user/account IDs
- emails
- IP addresses
- full URLs
- SQL statements
- message payloads
- headers
- cookies
- tokens
- exception messages
- baggage values

Every custom attribute should answer:

- Who consumes it?
- Is it safe?
- Is it bounded?
- Is it low cardinality?
- Is it scrubbed?
- Is its name stable?
- What happens when absent?

## Attribute Limits

Define or inherit limits for:

- maximum string length
- maximum array length
- maximum attributes per span
- maximum events
- maximum links
- maximum route-ratio entries
- maximum diagnostic entries

Do not silently create unbounded collections on a hot path.

If truncation is used, it must be:

- deterministic
- documented
- observable where appropriate
- safe for UTF-8/content semantics
- unable to leak the truncated raw suffix through logs

## Semantic Conventions

Use OpenTelemetry semantic conventions deliberately.

Rules:

- pin or document the semantic convention version used by a builder/integration
- do not mix incompatible versions silently
- keep transport/database/Kafka attribute names consistent
- do not redefine standard attributes with different semantics
- do not claim a semconv type if required fields are not satisfied
- validate mandatory combinations before span start or export when practical

Semconv validation mode and domain behavior belong in core/runtime policy, not generic wire decoding.

When semconv changes are breaking and the solution is pre-production, prefer direct migration to the intended final model rather than compatibility aliases.

## Resource Identity

Resource attributes identify the emitting service/runtime.

Resource identity must be:

- stable during process lifetime unless dynamic change is explicitly supported
- independent from individual requests
- free of secrets
- bounded
- consistent across agent/SDK/export paths

At minimum, consider:

- service name
- service namespace
- service version
- deployment environment
- instance identity where safe
- telemetry SDK/agent identity according to OTel behavior

Do not use request-specific values as resource attributes.

Test resource identity in agent and non-agent paths when both are supported.

