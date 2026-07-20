# SPI and Control Contracts

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

