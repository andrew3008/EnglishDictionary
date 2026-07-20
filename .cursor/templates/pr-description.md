# Pull Request Description Template

## Problem

Describe the concrete repository or production-readiness problem.

Include:

- current behavior;
- architectural or operational defect;
- affected modules and consumers;
- why the problem matters.

## Why This Is Non-Cosmetic

Explain the material improvement.

Examples:

- ownership corrected;
- invalid states prevented;
- unsafe default removed;
- public API narrowed;
- classloader boundary repaired;
- duplicate runtime path eliminated;
- diagnostics or verification strengthened.

Do not describe formatting, renaming, or class splitting alone as architecture.

## Architecture Decision

### Target ownership

```text
API:
Core:
Spring Boot:
WebMVC:
WebFlux:
OTel/JMX:
Starter:
Collector/deployment:
```

### Dependency direction

List edges added and removed.

### Runtime/classloader boundary

Describe JDK-safe wire types, agent/application interaction, and adapter ownership where applicable.

### Control pipeline

When runtime control is affected:

```text
wire payload
    -> structural decode
    -> domain validation
    -> mutation policy
    -> apply or read
```

## Changes

### Production code

-

### Tests and fixtures

-

### Build and dependencies

-

### Documentation and ADRs

-

### Removed code

-

## Public API and SPI

```text
Public types added:
Public types removed:
Methods added:
Methods removed:
Packages renamed:
SPIs added/removed:
ServiceLoader descriptors:
Third-party types exposed:
Bean/property changes:
```

## Breaking Changes and Migration

The tracing solution is pre-production.

Describe intentional breaking changes:

- old API/property/module;
- new API/property/module;
- repository consumers migrated;
- obsolete aliases/bridges removed;
- migration instructions;
- rollback considerations.

Do not claim backward compatibility when it was intentionally removed.

## Default Behavior

Describe:

- tracing active/no-op behavior;
- runtime mutation default;
- sampling default;
- scrubbing default;
- optional integration behavior;
- startup failure/back-off behavior.

## Runtime and State Safety

Explain:

- state owner;
- atomicity;
- LKG behavior;
- rejection behavior;
- rollback;
- concurrency;
- desired startup configuration versus live applied state.

## Security and Privacy

Describe:

- trust boundary;
- untrusted input validation;
- PII/scrubbing impact;
- logging and diagnostic safety;
- JMX/Actuator exposure;
- TLS/secrets;
- external JVM/network/RBAC controls still required.

## Observability

Describe:

- signals added/changed/removed;
- operator use case;
- cardinality budget;
- duplicate telemetry prevention;
- diagnostics;
- exporter/drop/retry visibility.

## Dependency and Publication Impact

```text
Dependencies added/removed:
Scopes:
Version owner:
Runtime provider:
POM/module metadata:
Starter graph:
Artifact coordinates:
```

## Tests and Verification

| Command | Result | Tests | Skipped | Notes |
|---|---|---:|---:|---|

Include:

- focused tests;
- Javadoc;
- architecture fitness;
- module taxonomy;
- full build;
- static scans.

## E2E Evidence

```text
Docker environment:
Containers/images:
Network paths:
Selected tests:
Tests:
Skipped:
Failures:
Errors:
Runtime assertions:
```

Do not report E2E PASS unless:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

## Performance and Operational Impact

Describe:

- startup impact;
- hot-path allocation/latency;
- telemetry volume;
- queue/retry behavior;
- rollout impact;
- operator actions.

## Residual Risks

| Risk | Impact | Mitigation | Owner | Blocking |
|---|---|---|---|---|

## Reviewer Focus

Ask reviewers to focus on specific decisions, for example:

- API/Core ownership;
- mutation state safety;
- classloader wire contract;
- optional classpath;
- servlet/WebFlux parity;
- sampling/scrubbing semantics;
- publication scopes.

## Checklist

- [ ] Public surface intentionally reviewed
- [ ] All repository consumers migrated
- [ ] No compatibility aliases added by default
- [ ] Negative tests added
- [ ] Architecture gates passed
- [ ] Javadoc passed
- [ ] Required E2E executed
- [ ] Docs/ADR updated
- [ ] No unrelated changes
- [ ] Residual risks documented
