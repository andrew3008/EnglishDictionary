# API, Public Surface, Dependencies, and Packages

## API and Core Separation

The API/core split is a production boundary, not a naming preference.

It protects:

- application teams from implementation coupling
- runtime implementation freedom
- classloader separation
- dependency hygiene
- Spring/agent integration isolation
- architecture fitness rules
- test separation between contracts and behavior

API is not limited to interfaces. It may include value objects, enums, annotations, specifications, and intentional SPIs.

Core is not merely an `impl` package. It owns runtime behavior and domain policy.

Do not merge API and core merely to reduce module count unless evidence proves the boundary has no independent consumers or architectural value.

## Public Surface Governance

Every public type must be classified:

1. application-facing API
2. external SPI
3. wire/control contract
4. public-for-compilation internal bridge
5. accidental public type to remove

The default decision for a new type is **not public**.

Before adding public surface, prove:

- concrete consumer
- stable domain role
- lifecycle
- failure model
- thread-safety
- dependency contract
- misuse resistance
- JavaDoc
- architecture guard

Do not add speculative:

- schema accessors
- registries
- query APIs
- extension points
- providers
- builders
- aliases

Public visibility does not automatically make a type supported. If a type is public only for cross-package compilation, mark it clearly and restrict access with ArchUnit.

## Root API

The root application API must remain small and capability-oriented.

Intended style:

```java
TraceOperations traceOperations;

traceOperations.traceContext();
traceOperations.spans().operation("payment.process");
traceOperations.spans().transport();
traceOperations.spans().fromSpec(spec);
```

Do not turn the root API into a god object.

Do not add:

- Spring beans/configuration operations
- JMX/runtime-control methods
- exporter controls
- sampler implementation controls
- mutable live state access
- arbitrary enrichment mechanisms
- low-level OTel SDK methods

Capability namespaces should reduce ambiguity without redundant naming.

## External Type Exposure

A third-party type in a public signature creates an architectural dependency contract.

Generally forbidden in application-facing API:

- Spring Framework/Boot
- JMX/OpenMBean
- OTel SDK implementation
- servlet/WebFlux implementation outside adapters
- Jackson databind
- Redis/Lettuce/Redisson
- infrastructure clients

An OpenTelemetry API type may be exposed only when:

- the boundary is deliberately OTel-native
- repository evidence justifies it
- the dependency contract is documented
- the Gradle scope reflects the runtime model
- an ADR approves the coupling
- a platform abstraction would add no real value

Do not create a platform-owned wrapper that merely clones a mature third-party type without adding policy or isolation.

## Dependency Governance

Dependency scope is architectural.

Use:

- `api` for intentionally exposed compile contracts
- `implementation` for internal runtime dependencies
- `compileOnly` for a genuine provided-runtime contract
- `runtimeOnly` for runtime-only dependencies

For every external dependency, review:

```text
Artifact:
Version owner:
Scope:
Public API exposure:
Runtime provider:
Transitive impact:
Classloader impact:
License/governance:
Security status:
Reason:
```

A `compileOnly` dependency used by executable main code still represents a runtime requirement. Document who provides it.

Do not add a broad artifact when a narrow one is sufficient.

## Package Taxonomy

Packages must express stable domain ownership.

Preferred examples:

- `api.span`
- `api.span.spec`
- `api.propagation`
- `api.control`
- `api.spi`
- `core.runtime`
- `core.sampling`
- `core.scrubbing`
- `core.control`

Avoid:

- `impl`
- `misc`
- `common`
- `util`
- `shared`
- `base`
- `internal` without an established governance convention

Flatten legacy one-file subpackages when they exist only to force implementation helpers to become public.

Do not introduce a new package convention inside a focused PR unless the convention itself is approved.

