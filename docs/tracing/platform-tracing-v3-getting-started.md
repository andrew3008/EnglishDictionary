# TraceOperations v3 — Getting Started

> The service dependency is one stack-specific starter. Production execution additionally requires the Controlled Agent distribution. `RG-IDENTITY-TRUST` and `RG-CONTROLLED-AGENT` are open; production rollout is forbidden.

TraceOperations v3 is the public span-factory API for Spring Boot services on the platform tracing stack. Auto-instrumentation (OpenTelemetry Java Agent, Spring/Micrometer Observation conventions, `@Traced`) is the **default**. Use `traceOperations.spans()` **only** when automatic instrumentation does not cover your use case.

## Dependencies

Add the appropriate platform tracing starter for your stack (Servlet or Reactive). The `TraceOperations` bean is wired automatically when tracing is enabled.

```gradle
implementation 'space.br1440.platform.tracing:platform-tracing-spring-boot-starter-servlet'
// or
implementation 'space.br1440.platform.tracing:platform-tracing-spring-boot-starter-reactive'
```

## Public API surface

The current `TraceOperations` surface contains governed tracing and synchronous business-correlation operations:

| Method | Purpose |
|--------|---------|
| `traceContext()` | Read-only correlation IDs for logging and error models |
| `spans()` | Governed span creation |
| `openCorrelationScope(correlationId)` | Explicit synchronous correlation scope |
| `withCorrelationId(correlationId, action)` | Scoped `Runnable` or checked-returning action |

There is no v1 wide facade (`startSpan`, `inSpan`, `SpanRelation`, transport factory methods on the root interface). See the [migration guide](./platform-tracing-v3-migration-guide.md).

## Read the active trace id

Use `traceContext()` for correlation. It does not expose OpenTelemetry SDK types.

```java
String traceId = traceOperations.traceContext()
        .traceId()
        .orElse("unknown");
```

Optional fields: `spanId()`, `requestId()`, `correlationId()`.

`traceId`, `requestId` and `correlationId` are distinct identities. Do not derive one from another. For WebFlux, use the injected `ReactiveCorrelationOperations`; synchronous scope methods must not be used as a ThreadLocal-only reactive propagation mechanism.

## Manual operation spans

### Run a void action

```java
traceOperations.spans()
        .operation("recalculate-pricing")
        .run(() -> pricingService.recalculate(orderId));
```

### Return a value

```java
Price price = traceOperations.spans()
        .operation("calculate-price")
        .call(() -> pricingService.calculate(orderId));
```

### Checked exceptions

```java
Order order = traceOperations.spans()
        .operation("load-order")
        .callChecked(() -> repository.load(orderId));
```

Default topology is **CHILD** when an active trace context exists; otherwise the platform creates an appropriate root. Use `.root()` or `.detached()` explicitly when governance requires it — see [SpanFactory API reference](./platform-tracing-v3-span-factory-api.md).

## Transport semantic builders

When you need semconv-aligned HTTP, database, RPC, or Kafka spans, use transport builders instead of generic `operation(name)`:

```java
traceOperations.spans()
        .transport()
        .database()
        .system("postgresql")
        .operation("SELECT")
        .collection("orders")
        .run(() -> repository.findAll());
```

See [SpanFactory API reference](./platform-tracing-v3-span-factory-api.md) for HTTP, RPC, and Kafka builders.

## When **not** to call `spans()`

- Incoming HTTP handled by the OTel Agent or Spring Observation — do not create a duplicate server span.
- Database/RPC/Kafka already instrumented by the Agent — prefer agent spans unless you need platform semconv attributes the agent does not emit.
- Method already annotated with `@Traced` — the aspect routes through `spans().operation(...)`; do not double-wrap.

See [Observability and diagnostics](./platform-tracing-v3-observability-and-diagnostics.md) and [ADR — Micrometer Observation Boundary](../decisions/ADR-platform-tracing-micrometer-observation-boundary.md).

## Compilable examples

The `platform-tracing-samples` module contains documentation-as-code:

`platform-tracing-samples/src/main/java/space/br1440/platform/tracing/samples/TraceOperationsV3Samples.java`

Verify compilation:

```powershell
.\gradlew.bat :platform-tracing-samples:compileJava
```

## Related documents

- [Final architecture](../architecture/platform-tracing-final-architecture.md)
- [Identity model](../decisions/ADR-identity-model-trace-request-correlation.md)
- [Migration guide](./platform-tracing-v3-migration-guide.md)
- [SpanFactory API reference](./platform-tracing-v3-span-factory-api.md)
- [Kafka batch links](./platform-tracing-v3-kafka-batch-links.md)
- [Production readiness](./platform-tracing-v3-production-readiness.md)
