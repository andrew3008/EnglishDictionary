# PlatformTracing v3 — Getting Started

PlatformTracing v3 is the public manual-tracing API for Spring Boot services on the platform tracing stack. Auto-instrumentation (OpenTelemetry Java Agent, Spring/Micrometer Observation conventions, `@Traced`) is the **default**. Use `PlatformTracing.manual()` **only** when automatic instrumentation does not cover your use case.

## Dependencies

Add the appropriate platform tracing starter for your stack (Servlet or Reactive). The `PlatformTracing` bean is wired automatically when tracing is enabled.

```gradle
implementation 'space.br1440.platform.tracing:platform-tracing-spring-boot-starter-servlet'
// or
implementation 'space.br1440.platform.tracing:platform-tracing-spring-boot-starter-reactive'
```

## Public API surface

v3 exposes exactly two entry points on `PlatformTracing`:

| Method | Purpose |
|--------|---------|
| `traceContext()` | Read-only correlation IDs for logging and error models |
| `manual()` | Governed manual span creation |

There is no v1 wide facade (`startSpan`, `inSpan`, `SpanRelation`, transport factory methods on the root interface). See the [migration guide](./platform-tracing-v3-migration-guide.md).

## Read the active trace id

Use `traceContext()` for correlation. It does not expose OpenTelemetry SDK types.

```java
String traceId = platformTracing.traceContext()
        .traceId()
        .orElse("unknown");
```

Optional fields: `spanId()`, `correlationId()`.

## Manual operation spans

### Run a void action

```java
platformTracing.manual()
        .operation("recalculate-pricing")
        .run(() -> pricingService.recalculate(orderId));
```

### Return a value

```java
Price price = platformTracing.manual()
        .operation("calculate-price")
        .call(() -> pricingService.calculate(orderId));
```

### Checked exceptions

```java
Order order = platformTracing.manual()
        .operation("load-order")
        .callChecked(() -> repository.load(orderId));
```

Default topology is **CHILD** when an active trace context exists; otherwise the platform creates an appropriate root. Use `.root()` or `.detached()` explicitly when governance requires it — see [Manual API reference](./platform-tracing-v3-manual-api.md).

## Transport semantic builders

When you need semconv-aligned HTTP, database, RPC, or Kafka spans, use transport builders instead of generic `operation(name)`:

```java
platformTracing.manual()
        .transport()
        .database()
        .system("postgresql")
        .operation("SELECT")
        .collection("orders")
        .run(() -> repository.findAll());
```

See [Manual API reference](./platform-tracing-v3-manual-api.md) for HTTP, RPC, and Kafka builders.

## When **not** to call `manual()`

- Incoming HTTP handled by the OTel Agent or Spring Observation — do not create a duplicate server span.
- Database/RPC/Kafka already instrumented by the Agent — prefer agent spans unless you need platform semconv attributes the agent does not emit.
- Method already annotated with `@Traced` — the aspect routes through `manual().operation(...)`; do not double-wrap.

See [Observability and diagnostics](./platform-tracing-v3-observability-and-diagnostics.md) and [ADR — Micrometer Observation Boundary](../decisions/ADR-platform-tracing-micrometer-observation-boundary.md).

## Compilable examples

The `platform-tracing-samples` module contains documentation-as-code:

`platform-tracing-samples/src/main/java/space/br1440/platform/tracing/samples/PlatformTracingV3Samples.java`

Verify compilation:

```powershell
.\gradlew.bat :platform-tracing-samples:compileJava
```

## Related documents

- [Migration guide](./platform-tracing-v3-migration-guide.md)
- [Manual API reference](./platform-tracing-v3-manual-api.md)
- [Kafka batch links](./platform-tracing-v3-kafka-batch-links.md)
- [Production readiness](./platform-tracing-v3-production-readiness.md)
