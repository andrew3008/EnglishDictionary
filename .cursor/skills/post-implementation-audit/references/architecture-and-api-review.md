# Architecture and API Review

## Architecture Review

Review dependency direction.

Expected principles:

```text
platform-tracing-core -> platform-tracing-api

spring-boot-autoconfigure
    -> api
    -> core

otel-extension
    -> approved api/core boundaries

webmvc and webflux
    -> independently isolated adapters

starters
    -> thin aggregation of intended modules
```

Reject:

- `api -> core`
- `core -> Spring`
- API exposure of JMX/OpenMBean
- API exposure of OTel SDK implementation types
- webmvc -> webflux
- webflux -> servlet
- production -> test/sample/e2e
- cycles solved by changing `implementation` to `api`
- domain validation moved into wire decoding
- Spring auto-configuration owning runtime algorithms

Architecture ownership must be expressed through code, Gradle, and fitness rules.

## Public API Review

For every public type or method, ask:

1. Who is the consumer?
2. Is it application-facing API, external SPI, wire contract, or internal bridge?
3. Is public visibility intentional?
4. Does the name express architectural role?
5. Are invariants enforced?
6. Does it expose third-party types?
7. What is the dependency contract?
8. Is JavaDoc complete?
9. Is thread safety/lifecycle clear?
10. Is a negative architecture guard required?

The default decision for a new type is not public.

Reject:

- speculative public query methods
- public `Impl` types
- public internal schema/validator
- vague helpers/managers
- accidental constructors
- implementation registries
- public types added only to cross subpackages
- API aliases in pre-production
- deprecated bridges without production consumers

Before production, direct removal and repository-wide migration are preferred.

## API Surface Diff

For API-changing PRs, require:

```text
Old public types:
New public types:
Types removed:
Types added:
Methods removed:
Methods added:
Packages changed:
External types exposed:
Configuration keys changed:
Bean names changed:
SPI descriptors changed:
```

Use reflection, ArchUnit, `javap`, API inventory, or artifact inspection where appropriate.

Do not rely only on source diff.

## Naming Review

Names must describe role.

Prefer:

- `TraceOperations`
- `SpanFactory`
- `RuntimePolicyControlHandler`
- `SpanAttributeScrubbingRule`
- `TraceControlHeaderInjector`
- `ActiveTraceContextView`

Challenge:

- `Manager`
- `Helper`
- `Utils`
- `Impl`
- `Base`
- `Common`
- repeated namespace terms
- names based on history rather than responsibility
- names implying broader scope than implementation provides

A rename is not cosmetic when it fixes a misleading contract or domain taxonomy.

A rename is cosmetic when behavior, ownership, and mental model remain unchanged.

## Compatibility Review

Current phase:

- source compatibility is not required
- binary compatibility is not required
- configuration aliases are not required
- bean aliases are not required
- deprecated bridges are not required

Reject automatic compatibility preservation.

Accept breaking changes only when:

- the final contract is materially better
- all repository consumers are migrated
- obsolete paths are deleted
- docs/tests/samples are updated
- architecture guards prevent regression

Do not approve random churn under the label “pre-production”.

## SPI and ServiceLoader Review

A real SPI requires:

- genuine external provider use case
- documented lifecycle
- ordering
- classloader behavior
- failure semantics
- missing/duplicate provider behavior
- descriptor tests
- governance

Reject ServiceLoader for:

- one built-in implementation
- deterministic utilities
- package access workaround
- mandatory safety component
- static holder lookup
- test substitution only

Check:

- old descriptor removed
- new descriptor path exact
- implementation FQNs correct
- source and generated descriptors
- custom test JARs
- classloader visibility
- no bridge/alias remains

