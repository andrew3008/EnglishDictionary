# Extensions, Supply Chain, and Resource Limits

## SPI and ServiceLoader Security

Use SPI/ServiceLoader only for a real, documented extension point.

Do not use ServiceLoader for:

- pure deterministic utilities
- single built-in implementation
- hidden initialization order
- mandatory safety components with no fallback

For a security-sensitive SPI:

- define trust assumptions
- validate provider output
- define provider ordering
- define failure behavior
- restrict classloader visibility
- test missing, duplicate, and failing providers
- do not let a provider bypass core safety policies

A custom scrubbing rule provider may extend detection logic, but must not disable mandatory platform rules unless explicitly approved.

## Dependency and Supply-Chain Security

Dependencies must:

- come from approved repositories
- use pinned/BOM-managed versions
- be reproducible
- be subject to vulnerability scanning
- use the narrowest artifact needed
- have intentional Gradle scope

Prefer:

- `jackson-annotations` over `jackson-databind` when only metadata is needed
- `implementation` for internal runtime code
- `compileOnly` only for genuine provided-runtime contracts
- `api` only when a dependency type appears in supported public signatures

Avoid:

- dynamic versions
- unmaintained libraries
- broad utility dependencies for trivial logic
- duplicate protocol/schema libraries
- adding JSON Schema/OpenAPI runtimes to a JDK-only internal control protocol without a proven need

Do not add a dependency solely to silence a warning unless the dependency accurately reflects the compile/Javadoc/runtime contract.

## Serialization Security

The control boundary uses explicit, constrained types.

Allowed classloader-neutral types must be documented.

Never use:

- Java native serialization
- unrestricted polymorphic deserialization
- arbitrary class names from input
- reflection-based construction from untrusted maps
- raw object graphs in JMX responses

If JSON is introduced in another boundary:

- use explicit models
- disable unsafe polymorphic typing
- reject unknown fields where appropriate
- bound payload size and nesting
- separate structural decode from domain validation

## Denial-of-Service and Resource Limits

Protect the tracing system from amplification.

Bound:

- header lengths
- baggage entry count and total size
- request ID length
- string attribute length
- string-array length
- route-ratio map size
- number of custom attributes
- event count
- link count
- diagnostic list size
- exporter queue size
- retry count and timeout
- concurrent runtime mutations

Avoid:

- unbounded maps/sets used for warning deduplication
- user-controlled cache keys without limits
- attacker-controlled span names
- unbounded exception or payload capture
- regexes vulnerable to catastrophic backtracking on untrusted input

Hot-path security checks must be allocation-aware but correctness has priority.

