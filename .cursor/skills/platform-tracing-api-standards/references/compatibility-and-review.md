# Compatibility and Review

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
.\gradlew.bat :platform-tracing-otel:test --no-daemon
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

