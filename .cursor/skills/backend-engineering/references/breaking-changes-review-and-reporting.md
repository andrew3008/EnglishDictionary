# Breaking Changes, Review, and Reporting

## Pre-Production Breaking-Change Policy

Breaking changes are justified when they:

- remove accidental public API
- correct misleading names
- fix module ownership
- remove external type leakage
- eliminate duplicate runtime/validation paths
- remove false SPI/ServiceLoader/holder patterns
- replace unsafe parsers
- remove public internal schema
- change unsafe defaults
- delete stale properties/bean aliases
- remove duplicate instrumentation
- fix no-op semantic divergence
- correct dependency/publication contracts

Default migration:

1. change to the intended final model
2. migrate all repository consumers
3. update tests, custom source sets, samples, benchmarks, docs, metadata, and CI
4. delete old path
5. add negative/architecture guards
6. run full verification
7. do not add aliases by default

Do not use “pre-production” to justify random churn.

## Review Expectations

Every non-trivial backend change must state:

```text
Problem:
Why non-cosmetic:
Owner module:
Architecture boundary:
Public API impact:
Dependency impact:
Security/privacy impact:
Runtime-state impact:
Failure mode:
No-op/disabled behavior:
Tests:
E2E:
Architecture fitness:
Docs/ADR:
Residual risks:
```

Follow `code-review.md` for audit and merge decisions.

## Anti-Patterns

Forbidden:

- accidental public API
- compatibility aliases by default
- deprecated bridges before production
- public `Impl`/helper/manager types
- false SPI/ServiceLoader
- static application context
- service locators
- mutable global state
- hidden retries
- hidden background threads
- hidden startup side effects
- direct raw wire payload apply
- mutation enabled by default
- trace metadata used for authorization
- PII/secrets in telemetry
- duplicate spans/metrics
- high-cardinality labels
- custom tracing protocols/parsers without approval
- blocking calls on reactive event loops
- Java native serialization
- trust-all TLS
- disabled hostname verification
- dynamic dependencies
- unbounded resources
- Redis added speculatively
- fixed ports/localhost in integration tests
- skipped E2E reported as pass
- wildcard imports
- architecture gates weakened to pass generated code
- cosmetic refactoring presented as architecture

## Required Verification

Choose the narrowest relevant tasks first.

Typical module gates:

```powershell
.\gradlew.bat :<affected-module>:compileJava --no-daemon
.\gradlew.bat :<affected-module>:compileTestJava --no-daemon
.\gradlew.bat :<affected-module>:test --no-daemon
```

Architecture and full build:

```powershell
.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
.\gradlew.bat build --warning-mode all --no-daemon
```

API/Javadoc when public contracts change:

```powershell
.\gradlew.bat :platform-tracing-api:test --no-daemon
.\gradlew.bat :platform-tracing-api:javadoc --warning-mode all --no-daemon
```

Runtime E2E when behavior crosses runtime/module boundaries:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

Run scans appropriate to the change:

- removed/legacy symbols
- compatibility aliases/deprecated bridges
- forbidden dependencies
- wildcard imports
- BOM
- stale Javadoc links
- hard-coded secrets/endpoints
- trust-all TLS
- Java native serialization
- false-positive-prone regex quality gates

## Required Final Report

A final implementation report must include:

```text
Decision:
Problem solved:
Why non-cosmetic:
Branch/commit:
Files/modules changed:
Public API diff:
Dependency changes:
Default behavior:
Failure behavior:
State behavior on rejection:
Security/privacy:
Tests executed:
E2E executed or skipped:
Architecture fitness:
Javadoc/build:
Git status/push:
Residual risks:
Merge readiness:
```

Use:

- `PASS` when required compile/test/Javadoc/architecture/E2E gates executed and passed
- `PASS_WITH_WARNINGS` when code is green and only documented non-blocking operational risk remains
- `INSUFFICIENT_EVIDENCE` when required runtime behavior did not execute
- `FAIL` when correctness, architecture, security, state safety, or required gates fail

