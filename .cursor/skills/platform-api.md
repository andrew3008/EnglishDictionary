# Platform Tracing API Standards

## Context

This repository contains an enterprise platform tracing solution for Spring Boot servlet and reactive microservices.

The solution is currently **pre-production**.

Breaking API, SPI, package, bean, and configuration changes are allowed when they materially improve:

- architectural integrity
- runtime safety
- public API clarity
- dependency governance
- testability
- operator diagnostics
- production readiness
- adoption across many internal services

Backward compatibility with the current pre-production API shape is **not** a primary goal.

Do not preserve accidental APIs, ambiguous names, obsolete packages, deprecated bridges, aliases, or dual execution paths merely because existing tests or internal callers still use them.

Architects will not accept cosmetic refactoring. Every substantial public API change must:

- remove an architectural defect
- reduce production or adoption risk
- clarify ownership
- narrow accidental public surface
- eliminate external dependency leakage
- strengthen invariants
- improve the mental model for service teams
- add executable verification

## Priority

When requirements conflict, prefer in this order:

1. correctness and runtime safety
2. clear ownership between API, core, Spring, and OTel/JMX layers
3. minimal and intentional public surface
4. misuse resistance
5. dependency hygiene
6. testability and architecture fitness
7. operator and developer clarity
8. implementation simplicity
9. compatibility with pre-production behavior

Do not weaken an intended API boundary to avoid updating tests, samples, docs, generated code, or internal consumers.

## API Mission

`platform-tracing-api` defines the language by which application code and other platform modules interact with tracing.

It may own:

- narrow public facades
- capability interfaces
- immutable value objects
- builders and specifications
- public annotations
- public violation/result models
- intentional SPI contracts
- classloader-neutral control/propagation contracts
- stable wire vocabulary required by callers

It must not become:

- a container for implementation classes
- a mirror of OpenTelemetry SDK internals
- a Spring Boot module
- a JMX/OpenMBean module
- a dumping ground for utilities
- a compatibility archive
- a second runtime implementation
- a general-purpose observability SDK

## API and Core Separation

### `platform-tracing-api`

Owns contracts.

It must not depend on `platform-tracing-core`.

Application-facing code should compile against API contracts, not implementation classes.

### `platform-tracing-core`

Owns:

- implementations of public contracts
- tracing runtime behavior
- lifecycle
- state
- sampling and scrubbing policy
- domain validation
- no-op behavior
- safety invariants
- OpenTelemetry adaptation where approved

Core may depend on API.

### Spring Boot auto-configuration

Owns:

- bean wiring
- conditional registration
- typed startup properties
- startup diagnostics
- Actuator integration
- translation from startup configuration to core contracts

Spring types must not leak into API or core contracts.

### OTel/JMX integration

Owns:

- agent/SDK integration
- JMX/OpenMBean adapters
- exporter/sampler/processor-specific glue
- classloader-sensitive bridges

OTel SDK implementation types and JMX/OpenMBean types must not leak into application-facing API.

## Public Surface Classification

Every type in an API module must be classified as exactly one of:

1. **Application-facing public API**
2. **External extension SPI**
3. **Wire/control contract**
4. **Internal implementation helper**
5. **Public-for-compilation internal bridge**
6. **Test-only type**

Do not leave public visibility unclassified.

### Application-facing API

Application teams are expected to import and call it directly.

It requires:

- clear JavaDoc
- stable semantics
- misuse-resistant naming
- tests
- architecture review
- dependency review

### External SPI

External platform/service teams may implement it.

It requires:

- documented lifecycle
- ordering
- failure behavior
- threading model
- classloader assumptions
- provider discovery model
- compatibility policy
- tests for missing, duplicate, and failing providers

### Wire/control contract

Used across module/process/classloader boundaries.

It requires:

- JDK-safe types
- explicit schema
- versioning
- machine-readable failures
- strict validation
- documentation and golden tests

### Internal helper

Must be package-private when possible.

Do not make a helper public merely to cross Java subpackages. Prefer:

- one coherent package
- internal package restructuring
- narrow package-private collaboration
- a deliberate internal bridge only when unavoidable

### Public-for-compilation internal bridge

Occasionally a type must be public because Java visibility or module layout requires cross-package access.

Such a type must have:

- strict JavaDoc stating it is not an extension API
- an ArchUnit access restriction
- an explicit dependent allowlist
- no promotion in application-facing docs
- no user-facing examples

Public visibility alone must not silently make it part of the supported API.

## Public API Minimization

The default decision for a new type is **not public**.

Before adding a public type or method, answer:

1. Who is the concrete consumer?
2. Why can the behavior not be expressed through an existing capability?
3. Is it a stable domain concept or an implementation mechanism?
4. Does it expose a third-party type?
5. Will service teams need to understand it?
6. Can its invariants be enforced?
7. What must remain compatible after production?
8. What architecture rule will prevent accidental expansion?

If no real consumer exists, keep the type internal.

Do not add speculative query methods, extension points, registries, schema accessors, or providers.

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

## Dependency Governance

For API modules:

- `api` only when a dependency type is intentionally exposed in supported public signatures
- `implementation` for internal code
- `compileOnly` only for a genuine provided-runtime contract
- no broad dependency for a narrow need
- no dependency added only to silence a warning unless it reflects the real contract

Every external dependency visible to consumers requires:

- artifact and license review
- version/BOM strategy
- transitive dependency review
- Java/Spring compatibility
- runtime provider ownership
- architecture documentation

Do not add schema or utility libraries when a small, bounded implementation already exists and is safer for the classloader boundary.

## SPI Design

Use SPI only for a real external extension requirement.

A valid SPI must have at least one plausible external provider and a documented reason that dependency injection or an internal strategy is insufficient.

Do not use SPI/ServiceLoader for:

- pure deterministic functions
- one built-in implementation
- mandatory safety components
- package access workarounds
- hidden singleton lookup
- global holders
- utilities
- test substitution only

SPI rules:

- interface in the intended contract module
- implementation outside the API module
- no static holder unless explicitly justified
- descriptor paths tested
- provider ordering deterministic
- missing-provider behavior explicit
- duplicate-provider behavior explicit
- classloader behavior tested
- provider failure cannot bypass mandatory safety rules

If an extension point is not genuinely external, use an internal interface or static utility in the owning module.

## ServiceLoader

ServiceLoader is a deployment/discovery mechanism, not a general dependency-injection mechanism.

Use it only when:

- providers arrive from independent artifacts
- runtime discovery is a product requirement
- classloader visibility is understood
- provider lifecycle is documented

Do not add a ServiceLoader bridge between API and its own core implementation.

Before keeping an existing ServiceLoader path, prove:

- external provider need
- more than one meaningful implementation
- safe missing-provider behavior
- safe initialization behavior
- no eager class-initialization trap

## Public Schema and Introspection

Internal programmatic schema validation may remain internal.

Do not expose an internal decoder/schema model through:

- public `schema()`
- public `validator()`
- speculative `READ_SCHEMA`
- public field descriptors
- public implementation registries

Prefer:

- documented wire specification
- public keys/operations/codes required by callers
- immutable decode result
- package-private schema/decoder
- golden spec-as-test coverage
- standard JMX metadata in the adapter layer when needed

Public introspection requires a proven production consumer and a separate API decision.

## Control Protocol API

The control protocol is a classloader-neutral contract.

Protect the intended public surface:

- protocol facade
- protocol version
- operation vocabulary
- key vocabulary
- decode result
- violation
- violation code

Implementation helpers must remain package-private.

The API decoder owns:

- structural wire validation
- operation parsing
- required/allowed keys
- unknown-key rejection
- safe type normalization
- immutable decode result

Core owns:

- sampling bounds
- validation modes
- cross-field rules
- empty-mutation rejection
- runtime mutation policy
- apply behavior

Do not move domain rules back into API.

## Runtime Control APIs

Runtime mutation is privileged behavior.

Public application API must not expose arbitrary mutation primitives.

Required semantics:

- mutation disabled by default
- read remains available when safe
- validation does not mutate
- apply only after decode and domain validation
- rejected mutation does not change state
- result statuses are machine-readable
- JMX/Actuator authorization remains outside API contracts

Do not expose an implementation applier, JMX handler, or live mutable state through `platform-tracing-api`.

## Configuration as External Contract

Spring configuration keys are an external adoption contract, but current pre-production keys may be renamed or removed when architecture improves.

Current policy:

- direct migration
- update tests, docs, samples, metadata, Helm/env mappings
- no aliases by default
- no deprecated property bridge by default
- no dual binding path

Every public property must have:

- clear owner
- type
- default
- safety semantics
- configuration metadata
- tests
- documentation

After production, property compatibility must follow an explicit versioning/deprecation policy. That future policy must not block justified pre-production cleanup now.

## Bean Names

Bean names are not automatically public API.

Treat a bean name as supported contract only when:

- users reference it by qualifier/name
- an integration contract documents it
- tests and docs intentionally protect it

Before production, rename ambiguous bean names directly and update all consumers.

Do not keep duplicate bean aliases unless an ADR requires them.

## No-Op and Disabled Behavior

No-op behavior is part of the API contract when tracing can be disabled.

Requirements:

- same public entry points remain safe
- no unexpected exceptions
- no hidden side effects
- no network calls
- no mutation
- returned handles/results obey documented lifecycle
- behavior is tested independently from the active runtime

Do not let no-op behavior silently diverge from active semantics where callers depend on lifecycle or validation.

## Thread Safety and Lifecycle

Public contracts must document thread-safety and lifecycle when relevant.

Clarify:

- singleton safety
- builder thread confinement
- handle close semantics
- idempotent close/apply behavior
- whether objects may be reused
- callback execution context
- blocking/non-blocking behavior
- reactive context behavior

Do not imply thread safety by omission for mutable builders or runtime handles.

## Security and Privacy

Public APIs must make unsafe behavior difficult.

Do not expose APIs that encourage:

- raw PII attributes
- unbounded high-cardinality values
- arbitrary attribute keys
- raw request/response payloads
- trace metadata as authorization
- runtime mutation without guardrails
- exporter endpoint mutation without threat review

If an escape hatch exists, it requires:

- explicit naming
- narrow visibility
- safe default
- diagnostics
- tests
- scrubbing/governance integration

## Javadoc

All application-facing public types and methods require useful JavaDoc.

JavaDoc must explain:

- purpose
- consumer
- behavior
- lifecycle
- thread-safety when relevant
- failure behavior
- nullability
- sensitive-data/cardinality constraints
- whether a type is internal despite public visibility

Avoid:

- links from API JavaDoc to core implementation classes
- links to Lombok-generated methods that Javadoc cannot resolve
- implementation history
- vague statements such as “platform helper”
- stale names
- promises of compatibility not yet approved

Javadoc must compile without warnings in supported modules.

## Documentation

For a breaking API change, update:

- ADR
- changelog
- public API inventory
- architecture docs
- usage guides
- samples
- configuration metadata
- tests
- rollout docs where relevant

Historical documents may retain old names only when clearly marked historical.

Do not leave current-looking analysis documents with stale API names.

## API Testing

Public API tests must protect intentional behavior and absence of accidental behavior.

Required tools may include:

- unit tests
- reflection tests
- ArchUnit
- `javap`
- Javadoc
- dependency reports
- golden wire tests
- integration/e2e tests

Test:

- exact public type surface where appropriate
- exact facade method set
- visibility of implementation helpers
- immutable result/value objects
- builder invariants
- removed old FQNs
- no compatibility aliases
- no deprecated bridges
- no forbidden external dependencies
- no legacy packages
- disabled/no-op behavior
- Javadoc links

When deleting a public type before production, add a negative guard if the symbol is likely to return accidentally.

## Architecture Fitness Rules

Protect at least:

- API does not depend on core
- API does not depend on Spring/JMX/OpenMBean/OTel SDK implementation
- core does not depend on Spring
- exact public API surface for sensitive packages
- implementation helpers are not public
- deleted legacy packages/symbols do not return
- no accidental ServiceLoader holders
- no public internal schema/validator
- no domain validation in wire API
- no runtime apply from raw wire payload
- webmvc/webflux package isolation
- no wildcard imports

Rules should encode the architecture decision, not the temporary implementation.

Do not weaken a rule merely to make generated code compile.

## Pre-Production Breaking-Change Policy

Before production, breaking changes are preferred when they remove architectural debt.

Approved reasons include:

- ambiguous naming
- accidental public API
- wrong package ownership
- external type leakage
- duplicate API/runtime concepts
- false SPI
- ServiceLoader holder anti-pattern
- unsafe parser
- public implementation schema
- dual old/new path
- unsafe mutation surface
- stale configuration model
- no-op semantic divergence

Default migration policy:

1. change the intended contract
2. update every internal consumer
3. update tests/docs/samples
4. delete the old symbol
5. add architecture/negative guards
6. run full verification
7. do not add an alias

## Post-Production Compatibility Policy

After production adoption, compatibility becomes an explicit product concern.

A future production compatibility policy should define:

- supported version window
- source vs binary compatibility
- semantic compatibility
- configuration compatibility
- deprecation duration
- migration tooling
- release notes
- API diff tooling

Do not prematurely impose that future policy on current pre-production cleanup.

## Deprecation

Current pre-production default:

- do not deprecate before removal
- remove directly
- update all repository consumers
- document the breaking decision

Use `@Deprecated` before removal only when:

- production consumers already exist
- staged migration is required
- an ADR defines the migration window
- the old API is safe enough to remain temporarily
- tests guarantee the bridge does not become permanent

Never deprecate an unsafe mutation or security bypass merely for compatibility.

## Configuration Compatibility

Current pre-production default:

- configuration keys may be renamed or removed
- no aliases by default
- no dual-read compatibility
- update metadata/docs/samples directly

After production, configuration compatibility requires an explicit policy and migration period.

Do not treat configuration keys as permanently stable merely because they exist in source.

## Source and Binary Compatibility

Current pre-production phase:

- source compatibility is not required
- binary compatibility is not required
- public package names may change
- constructors and methods may be removed
- records/interfaces may be redesigned

Every break still requires architectural justification and complete repository migration.

Do not make random churn. Breaking changes must produce a materially better final contract.

## Imports and Generated Code

For Cursor, Codex, and Perplexity-generated API changes:

- read project context and API skills first
- inspect current consumers before changing surface
- follow `.editorconfig`
- use explicit imports
- do not use wildcard imports
- do not create import-only churn
- do not add aliases automatically
- do not assume public means supported
- identify exact public surface changes
- run Javadoc and architecture checks
- distinguish verified consumers from hypothetical consumers

Generated code must not preserve accidental API for convenience.

## Review Checklist

Before approving a public API change, answer:

```text
Problem being solved:
Why this is architectural, not cosmetic:
Concrete consumers:
Old public surface:
New public surface:
Types added:
Types removed:
Methods added:
Methods removed:
Packages changed:
External types exposed:
Dependency impact:
Nullability:
Thread safety:
Failure model:
Security/cardinality implications:
No-op behavior:
SPI/ServiceLoader implications:
Configuration/bean implications:
Tests:
Architecture rules:
Docs/ADR:
Residual risks:
```

## Anti-Patterns

Forbidden:

- public types without a real consumer
- compatibility aliases by default
- deprecated bridges in pre-production
- public `Impl` classes
- public utility/helper classes without a contract
- god-object root facade
- exposing Spring/JMX/OTel SDK implementation types
- public internal schema/validator
- speculative `READ_SCHEMA`
- ServiceLoader for one built-in implementation
- static holders for mandatory implementations
- mutable public result collections
- boolean-only failure results
- raw wire maps applied without domain validation
- configuration aliases kept indefinitely
- duplicated bean aliases
- API Javadoc linking to core implementation
- test-only APIs in production surface
- package moves without updating architecture tests/docs
- broad public constructors
- wildcard imports
- breaking changes without material architectural value

## Required Verification

For a non-trivial API change, run the applicable gates:

```powershell
.\gradlew.bat :platform-tracing-api:compileJava --no-daemon
.\gradlew.bat :platform-tracing-api:compileTestJava --no-daemon
.\gradlew.bat :platform-tracing-api:test --no-daemon
.\gradlew.bat :platform-tracing-api:javadoc --warning-mode all --no-daemon
.\gradlew.bat :platform-tracing-core:test --no-daemon
.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
.\gradlew.bat build --warning-mode all --no-daemon
```

Run runtime E2E when API behavior crosses JMX, agent, Spring, servlet, or reactive boundaries.

Run scans appropriate to the change:

- removed public symbols
- compatibility aliases
- deprecated bridges
- legacy packages
- forbidden dependencies
- public implementation helpers
- wildcard imports
- stale JavaDoc links
- stale current documentation

## Required API Change Report

A final report for an API-changing PR must include:

```text
Decision:
Why the change is non-cosmetic:
Old API:
New API:
Removed aliases/bridges:
Public surface diff:
Dependency contract:
Migration performed:
Tests executed:
Javadoc:
Architecture fitness:
E2E executed or skipped:
Docs/ADR:
Residual risks:
Merge readiness:
```

Use:

- `PASS` when required compile/test/Javadoc/architecture gates pass
- `PASS_WITH_WARNINGS` when code is green and only documented non-blocking evidence remains
- `INSUFFICIENT_EVIDENCE` when runtime behavior was not executed
- `FAIL` when the intended API boundary or required gates are not satisfied
