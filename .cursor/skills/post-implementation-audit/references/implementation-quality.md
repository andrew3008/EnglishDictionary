# Implementation Quality Review

## Documentation and ADR Review

Require docs when a change affects:

- public API
- wire contract
- runtime-control behavior
- properties/defaults
- JMX/Actuator
- sampling/scrubbing
- security assumptions
- rollout
- known warning/risk

Review that docs distinguish:

- current behavior
- historical behavior
- proposed future work
- startup desired state
- live applied state
- code-level protection
- external JVM/network/RBAC protection

Historical documents may keep old names only when clearly marked.

## Warning Register Review

When a warning/risk register exists:

- update only with evidence
- do not close a warning because one path is fixed if other paths remain
- identify owner
- mitigation
- residual risk
- trigger for closure
- test evidence

Do not turn warning registers into permanent excuses for unbounded technical debt.

## Generated Code Review

Cursor, Codex, and Perplexity output must meet the same standard as handwritten code.

Review generated changes for:

- invented call sites
- false claims
- broad unrelated edits
- stale imports
- wildcard imports
- import-only churn
- compatibility aliases
- fake extension points
- duplicated logic
- missing negative tests
- skipped verification
- confident but unsupported conclusions

Require agents to distinguish verified facts from assumptions.

## Imports and Formatting Review

Follow repository `.editorconfig`.

Reject:

- wildcard imports
- static wildcard imports
- import-only churn in unrelated files
- locally invented import grouping
- broad formatting-only diffs
- ambiguous import guesses

Static imports may be used explicitly in tests.

Do not let import/style noise hide semantic changes.

## Static Scan Review

Scans should target architecture, not arbitrary text.

Review:

- scope
- regex precision
- allowed exceptions
- false positives
- false negatives
- whether the scan runs in CI
- whether the scan checks active source only or historical docs too

Examples:

- `network.protocol.version` is not a legacy Java package
- a test method name may match a removed method without calling it

Narrow bad scans instead of changing correct domain vocabulary.

## Review of Error Handling

Reject:

- swallowed exceptions
- empty catches
- success after failed apply
- generic `RuntimeException` without contract
- raw input in exceptions
- missing root cause
- silent fallback to permissive behavior
- partial state after failure

Prefer:

- explicit result model
- machine-readable code
- bounded sanitized reason
- clear rollback
- exact failure owner

## Review of Reflection

Reflection is not automatically forbidden.

Accept only when:

- required by framework/integration
- isolated
- bounded
- tested
- failure is explicit
- public API does not depend on reflected implementation details

Reject reflection used to:

- bypass module boundaries
- access private implementation
- implement ordinary mapping
- hide optional dependency ownership
- avoid designing a contract
- deserialize arbitrary untrusted classes

## Review of Abstractions

An abstraction is justified when it:

- represents a stable domain concept
- isolates a real dependency
- supports multiple meaningful implementations
- enforces invariants
- reduces duplication across owners
- enables testing without hiding production behavior

Reject:

- one-interface/one-impl ceremony
- `Manager`/`Helper` wrappers
- SPI for one implementation
- factories that only call constructors
- holders/service locators
- duplicate platform wrappers around mature library types without added policy

Do not remove a useful boundary merely to reduce class count.

## Review of Side Effects

Hidden side effects are blockers.

Review:

- bean construction
- static initialization
- class loading
- JMX registration
- global OTel mutation
- system properties
- background threads
- exporter creation
- filesystem writes
- Docker access
- network calls

Side effects must be:

- owned
- explicit
- lifecycle-managed
- idempotent where needed
- reversible where practical
- tested

## Review of Defaults

Defaults are product behavior.

Review all changes to:

- sampling
- scrubbing
- export
- runtime mutation
- diagnostics
- instrumentation enablement
- retries
- queues
- timeouts
- failure policy

Unsafe defaults are P0/P1 findings.

Do not accept a default merely because Spring or OTel provides it.

## Review of No-Op / Disabled Behavior

Verify:

- API remains safe
- no network calls
- no state mutation
- lifecycle valid
- diagnostics accurate
- no false export claims
- no unexpected bean creation
- no optional dependency loading
- no exception solely because feature disabled

No-op behavior must not bypass builder or domain invariants where callers rely on them.

## Review of Migration Completeness

After a breaking refactor, scan:

- main sources
- tests
- custom source sets
- E2E fixtures
- generated provider JARs
- ServiceLoader descriptors
- samples
- benchmarks
- docs
- changelogs
- architecture tests
- configuration metadata
- CI scripts

A refactor is incomplete if only main source compiles.

