# Compatibility, LLM Agents, Anti-Patterns, and Reporting

## Pre-Production Breaking-Change Policy

Breaking changes are preferred when they remove architecture debt.

Approved reasons:

- accidental public API
- misleading naming
- wrong module ownership
- third-party type leakage
- duplicate runtime/validation paths
- false SPI/ServiceLoader/holder
- unsafe parser
- public internal schema
- unsafe default
- stale configuration model
- duplicate instrumentation
- publication/dependency contract defect
- no-op semantic divergence

Default migration:

1. define the intended final architecture
2. update all repository consumers
3. update tests and custom source sets
4. update samples/bench/E2E
5. update docs/ADR/config metadata/CI
6. delete old path
7. add negative and architecture guards
8. run full verification
9. do not add aliases by default

Do not use pre-production status to justify random churn.

## Post-Production Compatibility Policy

After production adoption, compatibility becomes an explicit product concern.

A future policy must define:

- supported release/version window
- source compatibility
- binary compatibility
- semantic compatibility
- configuration compatibility
- wire compatibility
- deprecation period
- migration tooling
- release notes
- API diff tooling

Do not impose that future policy on current architecture cleanup without production consumers.

## Architecture and LLM Agents

Cursor, Codex, and Perplexity must:

- read relevant skills
- inspect real repository state
- distinguish facts from assumptions
- preserve accepted architecture
- avoid compatibility aliases by default
- avoid fake extension points
- avoid invented call sites
- avoid duplicate signals/logic
- add negative tests
- run narrow then broad verification
- report skipped tests honestly
- avoid import-only churn
- follow `.editorconfig`
- not commit/push unless explicitly requested

Any implementation prompt should include:

- exact branch/base
- authoritative plan/ADR
- approved decision
- do-not-touch list
- public surface constraints
- dependency constraints
- verification commands
- E2E execution requirement
- final report format

## Imports and Formatting

Use deterministic repository imports.

Rules:

- explicit imports only
- no wildcard imports
- static imports separated
- no import-only churn
- no unrelated formatting-only diff
- use `.editorconfig` as source of truth

Formatting is not an architecture improvement.

Do not let import churn hide semantic changes.

## Anti-Patterns

Forbidden:

- compatibility-first preservation of pre-production debt
- accidental public API
- public `Impl`/helper/manager types
- giant root facade
- false SPI
- ServiceLoader for one built-in implementation
- static holders/service locators
- duplicate sources of truth
- dual decoder/runtime paths
- public internal schema/validator
- raw wire payload apply
- mutation enabled by default
- domain validation in API wire decoder
- API -> core
- core -> Spring
- servlet/reactive cross-dependency
- JMX/OpenMBean in API
- OTel SDK implementation in API
- duplicate auto/manual spans
- tracing metadata used for authorization
- PII/high-cardinality telemetry without governance
- hidden side effects
- hidden retries/background threads
- mutable global state
- Java native serialization
- trust-all TLS
- dynamic dependencies
- speculative Redis/distributed infrastructure
- fixed topology assumptions
- required E2E silently skipped
- architecture rules weakened to pass generated code
- cosmetic refactoring presented as architecture
- unsupported confident claims

## Required Architecture Verification

Choose the applicable gates.

Typical:

```powershell
.\gradlew.bat :platform-tracing-api:test --no-daemon
.\gradlew.bat :platform-tracing-otel:test --no-daemon
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --no-daemon
.\gradlew.bat :platform-tracing-autoconfigure-webmvc:test --no-daemon
.\gradlew.bat :platform-tracing-autoconfigure-webflux:test --no-daemon
.\gradlew.bat :platform-tracing-otel-javaagent-extension:test --no-daemon
.\gradlew.bat :platform-tracing-api:javadoc --warning-mode all --no-daemon
.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
.\gradlew.bat build --warning-mode all --no-daemon
```

For runtime/classloader/JMX/export boundaries:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

For required E2E evidence verify:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

Run static scans appropriate to the change:

- removed/legacy symbols
- public surface
- forbidden dependencies
- compatibility aliases/deprecated bridges
- ServiceLoader descriptors
- wildcard imports
- BOM
- stale Javadoc
- hard-coded credentials/endpoints
- trust-all TLS
- Java native serialization
- false-positive-prone regex rules

## Required Architecture Change Report

A final report for a non-trivial architecture change must include:

```text
Decision:
Problem:
Why non-cosmetic:
Repository evidence:
Old architecture:
Target architecture:
Module ownership:
Public API/SPI diff:
Dependency direction:
Classloader/runtime boundary:
Default behavior:
Failure behavior:
State behavior on rejection:
Security/privacy:
Migration:
Tests executed:
E2E executed or skipped:
Architecture fitness:
Javadoc/build:
Docs/ADR:
Residual risks:
Merge readiness:
```

Use:

- `PASS` when required gates executed and passed
- `PASS_WITH_WARNINGS` when code is green and only documented non-blocking operational risk remains
- `INSUFFICIENT_EVIDENCE` when required runtime/consumer evidence is unavailable
- `FAIL` when correctness, architecture, security, state safety, dependency contract, or required gates fail

