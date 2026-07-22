# Security Testing, Governance, and Reporting

## Test Security

Security-sensitive behavior requires negative tests.

Required examples:

- mutation disabled by default
- mutation rejected without state change
- validate operation does not apply
- read operation does not mutate
- unknown control keys rejected
- malformed trace context rejected
- force-sampling header invalid value rejected
- PII removed/masked before export
- secrets absent from diagnostics
- oversized input rejected
- optional provider failure handled safely
- JMX/OpenMBean types absent from API
- historical unsafe path tracked if not yet removed

Use Testcontainers/E2E when the security property depends on a real boundary.

A skipped E2E test is `INSUFFICIENT_EVIDENCE`, not a pass.

Never use production credentials in tests.

## Security Architecture Fitness Rules

Protect at least:

- API does not depend on core implementation
- API does not depend on Spring/JMX/OpenMBean/OTel SDK implementation
- core does not depend on Spring
- JMX types stay in the adapter module
- internal schema/decoder helpers are not public
- legacy control packages and symbols do not return
- runtime apply cannot accept raw wire maps outside approved adapters
- mutation is disabled by default
- no trust-all TLS or disabled hostname verification
- no Java native serialization
- no wildcard CORS in platform admin surfaces
- no secrets in configuration metadata or docs
- no forbidden telemetry keys/namespaces where statically enforceable

Do not weaken a security rule to make generated code compile.

## Imports and Generated Code

For Cursor, Codex, and Perplexity-generated security changes:

- read repository context and security skills first
- follow `.editorconfig`
- use explicit imports
- do not use wildcard imports
- do not introduce import-only churn
- identify the trust boundary being changed
- list assumptions
- add negative tests
- run the narrowest affected security and architecture gates
- do not claim a threat is mitigated without executable evidence
- distinguish code protection from external JVM/network/RBAC protection

Generated code must not add a permissive fallback merely to pass tests.

## Breaking-Change Policy

Because the tracing solution is pre-production, prefer removing unsafe abstractions over preserving them.

Breaking changes are justified when they:

- remove accidental public control APIs
- eliminate duplicate validation paths
- remove insecure ServiceLoader/holder patterns
- move security/domain rules to the correct module
- remove unsafe mutation paths
- rename ambiguous properties that hide security semantics
- delete unsupported compatibility aliases
- narrow public API surface
- replace custom security-sensitive parsers with mature implementations

Document intentional breaking security changes in an ADR and update all tests/docs directly.

## Anti-Patterns

Forbidden:

- mutation enabled by default
- public `schema()`/`validator()` for internal control implementation
- `READ_SCHEMA` as a speculative production operation
- direct apply of raw `Map<String, Object>`
- force sampling bypassing scrubbing/export policy
- using tracing metadata for authorization
- raw baggage copied to attributes/logs
- user input used as attribute key or metric label
- unbounded trace-control input
- raw PII in spans/events/logs/metrics
- secrets in applied-state snapshots
- arbitrary runtime exporter endpoint mutation
- trust-all TLS
- disabled hostname verification
- Java native serialization
- unrestricted polymorphic deserialization
- static application-context holders
- custom authentication inside tracing modules
- broad security warning suppression
- compatibility shims that preserve insecure behavior
- skipped security E2E reported as pass

## Required Verification

For non-trivial security changes, select the applicable gates:

```powershell
.\gradlew.bat :platform-tracing-api:test --no-daemon
.\gradlew.bat :platform-tracing-otel:test --no-daemon
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --no-daemon
.\gradlew.bat :platform-tracing-otel-extension:test --no-daemon
.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
.\gradlew.bat build --warning-mode all --no-daemon
```

Run targeted opt-in E2E when a runtime boundary changes:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

Run scans appropriate to the change:

- removed/legacy API symbols
- forbidden dependencies
- wildcard imports
- BOM
- trust-all TLS
- disabled certificate/hostname verification
- Java native serialization
- hard-coded credentials
- sensitive logging patterns

## Required Security Report

A final security-related implementation report must include:

```text
Threat addressed:
Trust boundary:
Default behavior:
Failure mode:
State-change behavior on rejection:
Sensitive data handled:
Tests executed:
E2E executed or skipped:
Architecture fitness:
External controls still required:
Residual risks:
```

Use:

- `PASS` only when required gates executed and passed
- `PASS_WITH_WARNINGS` when code is green and only documented non-blocking external controls remain
- `INSUFFICIENT_EVIDENCE` when a required runtime/security boundary was not executed
- `FAIL` when a required security invariant is not enforced or a required gate fails

