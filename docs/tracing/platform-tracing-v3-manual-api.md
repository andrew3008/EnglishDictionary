# PlatformTracing v3 — Manual API Reference

Reference for the v3 manual tracing surface. All span creation routes internally through `TracingImplementation.startSpan(SpanSpec)`; application code uses the public builders below.

## Entry points

### `PlatformTracing`

| Method | Returns | Description |
|--------|---------|-------------|
| `traceContext()` | `ActiveTraceContextView` | Read-only active context |
| `manual()` | `ManualTracing` | Manual span creation |

### `ActiveTraceContextView`

Read-only correlation view. Does **not** expose OpenTelemetry `Context`, `Span`, or `SpanContext`.

| Method | Returns |
|--------|---------|
| `traceId()` | `Optional<String>` — hex trace id |
| `spanId()` | `Optional<String>` — hex span id |
| `correlationId()` | `Optional<String>` — platform correlation id when present |

### `ManualTracing`

| Method | Returns | Description |
|--------|---------|-------------|
| `operation(name)` | `OperationSpanBuilder` | Generic application-level span |
| `transport()` | `TransportTracing` | HTTP / DB / RPC / Kafka semconv builders |
| `spanFromSpec(spec)` | `SpanExecution` | Governed escape hatch |

## `ManualSpanBuilder` (common builder contract)

Implemented by `OperationSpanBuilder`, transport builders, and `KafkaBatchSpanBuilder`.

### Span relationship (explicit, single assignment)

| Method | Effect |
|--------|--------|
| `child()` | `CHILD` — join active trace when context exists |
| `root()` | `ROOT` — new trace, ignore active parent |
| `detached()` | `DETACHED` — new trace, no parent, **no links** |

Repeated explicit relationship setter throws `IllegalStateException`.

### Links (pre-start only)

| Method | Effect |
|--------|--------|
| `linkedTo(RemoteSpanLink... links)` | Add pre-start span links |
| `fromTraceparent(String... traceparents)` | Parse W3C traceparent into links (strict) |

**Policy:**

- **ROOT + links** — allowed (primary Kafka batch pattern)
- **DETACHED + links** — forbidden (fail fast at build/start)
- **CHILD + links** — forbidden in v3
- **No post-start `addLink`** — links must be configured before `start()` / scoped execution

### Scoped execution

| Method | Description |
|--------|-------------|
| `start()` | Returns `SpanHandle` (try-with-resources) |
| `run(Runnable)` | Start span, run action, end span |
| `call(Supplier<T>)` | Start span, return value, end span |
| `callChecked(ThrowingSupplier<T>)` | Same with checked exceptions |

## `OperationSpanBuilder`

`manual().operation(name)` — generic internal/business operations. Category defaults to `INTERNAL` at implementation layer unless overridden via `spanFromSpec`.

## `TransportTracing`

Groups protocol-specific builders under `manual().transport()`:

| Method | Returns |
|--------|---------|
| `http()` | `HttpTracing` → `client()` / `server()` |
| `database()` | `DatabaseSpanBuilder` |
| `rpc()` | `RpcTracing` → `client()` / `server()` |
| `kafka()` | `KafkaTracing` → `producer()` / `consumer()` |

Transport builders carry semconv version markers (`@DatabaseSemconvVersion`, `@KafkaSemconvVersion`, `@RpcSemconvVersion`).

### HTTP builders

- `HttpClientSpanBuilder` — `url()`, `method()`, `serverAddress()`, …
- `HttpServerSpanBuilder` — `route()`, `method()`, …

### Database builder

`DatabaseSpanBuilder` methods: `system()`, `operation()`, `collection()`.

### RPC builders

- `RpcClientSpanBuilder` — `system()`, `service()`, `method()`, `serverAddress()`
- `RpcServerSpanBuilder` — `system()`, `service()`, `method()`

### Kafka builders

- `KafkaProducerSpanBuilder` — `destination()`, `operation()`
- `KafkaConsumerSpanBuilder` — `destination()`, `operation()`, `batch(destination)` → `KafkaBatchSpanBuilder`

Batch entry: `consumer().batch("orders")` returns `KafkaBatchSpanBuilder` for ROOT+links batch processing. See [Kafka batch links](./platform-tracing-v3-kafka-batch-links.md).

## `SpanSpec` and governance

Immutable governed specification for `manual().spanFromSpec(spec)`.

### `SpanSpecBuilder`

| Method | Notes |
|--------|-------|
| `category(SpanCategory)` | Required semantic category |
| `child()` / `root()` / `detached()` | Span relationship (`SpanRelationship`) |
| `linkedTo(...)` / `fromTraceparent(...)` | Pre-start links |
| `attribute(key, typedValue)` | Typed scalar attributes only |
| `stringListAttribute` / `longListAttribute` / … | Homogeneous lists |
| `reason(SpanSpecReason)` | **Mandatory** |
| `reference(String)` | Required when `reason == TEMPORARY_WORKAROUND` |
| `build()` | Validates final state |

There is **no** `attribute(String, Object)` — only typed overloads and list helpers.

### `SpanSpecReason`

| Value | Use when |
|-------|----------|
| `UNSUPPORTED_PROTOCOL` | Protocol not covered by transport builders |
| `UNSUPPORTED_LIBRARY` | Third-party library cannot be instrumented otherwise |
| `LEGACY_INTEGRATION` | Bridging legacy integration code |
| `PLATFORM_EDGE_CASE` | Documented platform edge case |
| `TEMPORARY_WORKAROUND` | Short-term workaround — **requires `reference`** |

Generic catch-all reasons (`OTHER`, `UNKNOWN`, …) are forbidden.

### `SpanRelationshipSpec`

Immutable value model: `kind()` + `links()`. Returned by `SpanSpec.relationship()`. Prefer builder convenience methods (`.child()`, `.root()`, …) over constructing `SpanRelationshipSpec` directly.

`SpanRelationshipSpec.kind()` returns `SpanRelationship` — the new span's relationship to the current/parent trace context. This is **not** OpenTelemetry `SpanKind`; protocol/client-server kind is derived separately from `SpanCategory`.

### `SpanSpecAttributeValue`

Whitelist sealed type for spec attributes: string, long, double, boolean, and homogeneous lists. Factory methods: `SpanSpecAttributeValue.of(...)`, `stringList(...)`, etc.

## Lifecycle types

### `SpanExecution`

Terminal surface from `spanFromSpec(spec)`: `start()`, `run()`, `call()`, `callChecked()`.

### `SpanHandle`

Minimal started-span handle (`AutoCloseable`): `recordException(Throwable)`, `close()`.

## Design ADRs

- [ADR — v3 Public API](../decisions/ADR-platform-tracing-v3-public-api.md)
- [ADR — SpanSpec Governance](../decisions/ADR-platform-tracing-span-spec-governance.md)
- [ADR — Topology and Links](../decisions/ADR-platform-tracing-topology-links.md)
