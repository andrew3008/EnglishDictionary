# Public API Design

## Root API Design

The root API must remain a small capability entry point, not a god object.

Current intended style:

```java
TraceOperations traceOperations;

traceOperations.traceContext();
traceOperations.spans().operation("payment.process");
traceOperations.spans().transport();
traceOperations.spans().fromSpec(spec);
```

Principles:

- root methods expose cohesive capabilities
- capability objects create a clear namespace
- names inside a namespace should not redundantly repeat the namespace
- unrelated runtime/configuration/control concerns must not be added to the root facade
- internal implementation details must remain hidden

Example:

```java
traceOperations.spans().operation("recalculate")
```

is preferable to:

```java
traceOperations.spans().operationSpan("recalculate")
```

because the `spans()` namespace already supplies the domain.

Do not flatten all builder/factory methods onto the root API merely for fewer calls.

## Naming

Names must describe architectural role, not origin, implementation style, or team ownership.

Prefer role-based names:

- `TraceOperations`
- `SpanFactory`
- `ActiveTraceContextView`
- `RequestTraceContextSnapshot`
- `SpanAttributeScrubbingRule`
- `TraceControlHeaderInjector`

Avoid vague or accidental names:

- `PlatformTracing`
- `Manager`
- `Helper`
- `Utils`
- `Impl`
- `Base`
- `Common`
- `ManualTracing` when the type is actually a span factory
- names that repeat the enclosing namespace without adding meaning

Names must answer:

- what is this?
- who uses it?
- what lifecycle does it have?
- is it a value, factory, operation, policy, parser, validator, adapter, or runtime?

Renaming is allowed before production when it improves this model.

Do not keep old aliases.

## Packages

Package names must reflect stable domain taxonomy.

Prefer packages such as:

- `api.span`
- `api.span.spec`
- `api.propagation`
- `api.control`
- `api.spi`
- `core.runtime`
- `core.sampling`
- `core.scrubbing`
- `core.control`

Avoid package names that express only implementation status:

- `impl`
- `misc`
- `common`
- `util`
- `internal` without a defined governance convention

Do not introduce a new package convention in the middle of a focused refactoring unless the convention itself is approved.

Flatten one-file legacy subpackages when they exist only to expose package-private implementation types as public.

## Visibility

Prefer the narrowest visibility:

1. private
2. package-private
3. protected only for an intentional inheritance contract
4. public only for supported API/SPI/wire contracts

Avoid public constructors for runtime implementation classes.

Use factory methods or dependency injection for implementation creation.

Do not use protected members as an undocumented extension mechanism.

Do not expose mutable fields.

## Immutability

Public value objects should be immutable.

Prefer:

- records
- final fields
- defensive copies
- unmodifiable collections
- explicit factory methods
- constructor invariants

Collections returned by public APIs must not expose mutable internal state.

For records with non-trivial invariants, use compact constructors and tests.

Example result invariants:

```text
valid result:
- operation present
- no violations
- normalized payload immutable

invalid result:
- violations present
- no usable payload for apply
```

Do not return partially usable mutable state after failure.

## Nullability

Public contracts must state null behavior.

Use the repository's approved nullability annotations consistently.

Guidelines:

- reject null early when null is programming error
- use `Optional` only for a genuinely optional return value
- do not use `Optional` for fields/parameters mechanically
- do not return null collections
- do not accept nullable parameters without documented behavior
- do not use null to encode multiple failure modes

Choose one of:

- exception
- empty optional
- explicit result/violation model
- no-op behavior

according to the API contract.

## Result and Error Models

Use exceptions for:

- programming errors
- impossible states
- strict builder misuse
- construction-time invariant violations

Use explicit result/violation models for:

- untrusted wire input
- validation
- control-plane operations
- batch parsing
- recoverable operator errors

Results should provide:

- machine-readable status/code
- bounded, sanitized diagnostics
- immutable data
- clear success/failure invariants

Avoid:

- booleans without reasons
- exceptions as normal wire-validation flow
- raw input echoed in messages
- one result type mixing structural and domain violations without ownership

Wire and domain failure types should remain distinct.

## Builders and Specifications

Builders are public APIs and must be reviewed as such.

Builder rules:

- method names describe domain action
- repeated mutually exclusive choices fail clearly
- invalid state is rejected before runtime behavior
- final build/start operation validates complete state
- no hidden mutable global state
- no reuse after terminal operation unless documented
- no ambiguous booleans
- no implementation types in signatures
- no silent fallback after invalid input

Specifications should be:

- immutable
- serializable only when explicitly needed
- independent of Spring/JMX implementation
- safe to validate before runtime execution

Do not expose raw OpenTelemetry `SpanBuilder` or runtime handles as application API.

## External Type Exposure

A third-party type in a public signature creates a dependency contract.

Before exposing one, require explicit justification.

### Generally forbidden in application-facing API

- Spring Framework types
- Spring Boot types
- JMX/OpenMBean types
- OpenTelemetry SDK implementation types
- Jackson databind types
- Redis/Lettuce/Redisson types
- servlet/WebFlux implementation types outside their adapters

### Conditional exception

An OpenTelemetry **API** type may be exposed only when:

- the tracing platform is deliberately OTel-native at that boundary
- the dependency contract is documented
- the benefit is greater than a platform-owned abstraction
- the type is stable enough
- an ADR approves the coupling
- Gradle scope reflects the real compile/runtime contract

Do not leak an external type accidentally through annotations, generic parameters, exceptions, records, or Javadoc.

Use the narrowest dependency artifact and Gradle scope.

