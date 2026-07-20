# Java Design and Public API

## Java Toolchain

Use the repository-configured Java toolchain.

If the repository currently targets Java 21, use Java 21 features deliberately.

Do not rely only on local `JAVA_HOME`.

Do not introduce a higher language level in one module without a repository-level decision.

## Java Design

Prefer:

- immutable objects
- records for immutable value/result models
- final fields
- constructor injection
- explicit factories
- package-private implementation helpers
- narrow interfaces at real boundaries
- sealed types only when they materially improve the model
- defensive copies
- unmodifiable collections
- explicit nullability
- deterministic control flow

Avoid:

- mutable shared state
- service locators
- static application context access
- reflection-heavy ordinary logic
- hidden fallback behavior
- global holders
- one-interface/one-implementation ceremony
- abstractions that only rename third-party APIs
- raw maps outside explicit wire boundaries

## Visibility

Use the narrowest visibility:

1. private
2. package-private
3. protected only for an intentional inheritance contract
4. public only for supported API/SPI/wire contracts

Public visibility must be classified:

- application-facing API
- external SPI
- wire contract
- public-for-compilation internal bridge
- test-only artifact

Do not make a helper public merely to cross Java subpackages.

Prefer package restructuring or a deliberate internal bridge with ArchUnit restrictions.

## Immutability and Invariants

Public value objects and runtime snapshots must be immutable.

Use:

- records
- defensive copies
- compact constructors
- explicit factories
- immutable maps/lists
- constructor validation

Runtime state must be safely published and updated atomically.

Rejected operations must not expose partially applied state.

Do not return mutable collections or partially usable failure payloads.

## Nullability

Public and module-boundary contracts must state null behavior.

Rules:

- reject null early when it is a programming error
- return empty collections, not null collections
- use `Optional` only for genuine optional return values
- do not use null to encode multiple failure modes
- do not add nullable parameters without documented behavior
- use the repository's approved nullability annotations consistently

## Dependency Injection

Use constructor injection.

Forbidden:

- field injection
- static dependency access
- service locators
- `ApplicationContext` lookup from domain/runtime code
- optional dependencies hidden behind raw `Object`
- mandatory dependencies hidden in `ObjectProvider`

`ObjectProvider` is acceptable for deliberate optional/lazy integration, not to hide an unclear graph.

## Lombok

Lombok may be used where it reduces boilerplate without hiding the contract.

Potentially acceptable:

- `@RequiredArgsConstructor`
- `@Getter` for internal immutable models
- `@Slf4j`
- `@Builder` for carefully reviewed value models

Avoid in sensitive public/wire packages when generated shape obscures:

- public surface
- constructor semantics
- Javadoc
- invariants
- package-private intent

Forbidden:

- `@Data` on domain/runtime state
- `@SneakyThrows`
- Lombok-generated mutable state
- generated methods referenced by Javadoc when Javadoc cannot resolve them

For public protocol/API types, explicit Java is often preferable.

## Naming

Names must describe architectural role.

Prefer names that identify the type as a:

- operation
- factory
- builder
- specification
- policy
- validator
- decoder
- adapter
- runtime
- snapshot
- view
- result
- violation
- applier

Avoid:

- `Utils`
- `Helper`
- `Manager`
- `Common`
- `Base`
- `Generic` without a real domain distinction
- `Impl` in public types
- names based on project history
- names broader than implementation scope
- redundant names inside a clear namespace

A rename is architectural when it corrects the mental model or ownership.

A rename is cosmetic when behavior, ownership, and domain meaning remain unchanged.

## Public API

Public API must remain minimal and intentional.

Before adding a public type/method, answer:

```text
Concrete consumer:
Why an existing capability is insufficient:
Stable domain concept or implementation mechanism:
External types exposed:
Lifecycle:
Thread safety:
Failure model:
Dependency contract:
Security/cardinality impact:
Architecture guard:
```

The default for a new type is not public.

Do not add:

- speculative schema accessors
- query methods without a consumer
- public registries
- false SPIs
- compatibility aliases
- deprecated bridges
- public implementation helpers

Because the solution is pre-production, direct removal and repository-wide migration are preferred when the final API becomes materially better.

## API and Root Facade

The root tracing API should remain a small capability entry point.

Intended style:

```java
TraceOperations traceOperations;

traceOperations.traceContext();
traceOperations.spans().operation("payment.process");
traceOperations.spans().transport();
traceOperations.spans().fromSpec(spec);
```

Do not turn the root facade into a god object.

Do not add runtime-control, Spring, exporter, JMX, sampler implementation, or mutable state methods to the application-facing root API.

Inside a capability namespace, avoid redundant names such as `operationSpan(...)` when `spans().operation(...)` is clear.

## Builders and Specifications

Public builders/specifications must:

- use domain-oriented method names
- reject invalid combinations
- enforce terminal-state rules
- avoid ambiguous booleans
- avoid implementation types in signatures
- validate complete state before execution
- document reuse/thread-safety
- avoid hidden mutable global state
- preserve no-op lifecycle semantics

Do not silently select a default after the caller explicitly made conflicting choices.

Specifications should be immutable and independent from Spring/JMX implementation.

## Error and Result Models

Use exceptions for:

- programming errors
- strict builder misuse
- impossible states
- construction-time invariant violations

Use explicit result/violation models for:

- untrusted wire input
- validation
- control operations
- batch parsing
- recoverable operator errors

Result models should provide:

- machine-readable status/code
- bounded sanitized diagnostics
- immutable data
- clear success/failure invariants

Avoid:

- boolean-only failures
- raw input in exceptions
- generic `RuntimeException` without contract
- swallowed exceptions
- success after partial failure
- one result type mixing structural and domain ownership

