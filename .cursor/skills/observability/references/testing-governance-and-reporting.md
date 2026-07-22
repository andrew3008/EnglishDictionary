# Observability Testing, Governance, and Reporting

## Testing

Observability behavior requires layered tests.

### Unit tests

Use for:

- sampling policy
- scrubbing rules
- context/value conversion
- result invariants
- no-op behavior
- naming/attribute policy
- mutation gating

### Spring tests

Use for:

- bean conditions
- property binding
- diagnostics
- Actuator state
- servlet/reactive adapter activation
- missing optional classpaths

### Integration/E2E

Use for:

- real propagation
- Java agent behavior
- Collector/Jaeger export
- resource identity
- JMX wire/control
- Reactor context
- servlet/WebFlux
- database/Kafka integrations
- sampling and scrubbing effects visible in exported spans

A skipped E2E test is not runtime evidence.

## Trace Assertions

Do not assert only that “some span exists”.

Where relevant, verify:

- expected span count
- span name
- parent/child relationship
- links
- trace ID continuity
- attributes
- resource identity
- status
- events
- sampling result
- scrubbing result
- absence of duplicate spans

Use unique correlation identifiers to query backend state.

## Metric Assertions

Verify:

- registration
- type
- initial state
- increment/update behavior
- tags
- bounded tag values
- disabled behavior
- duplicate registration behavior

Avoid asserting implementation-specific registry internals when public behavior is enough.

## Log Assertions

Test logs only when logging is part of the operational contract.

Verify:

- level
- stable reason/status fields
- no sensitive data
- deduplication/rate limiting
- actionable content

Do not make broad tests brittle by asserting exact prose when a machine-readable code exists.

## Golden and Contract Tests

Use golden/spec-as-test coverage for:

- wire protocol examples
- configuration metadata
- public result codes
- semantic mapping tables
- supported operations/keys
- public API inventories

Golden tests must validate meaningful contract data, not merely snapshot entire unstable files.

## Architecture Fitness Rules

Protect at least:

- API does not depend on core
- API does not depend on Spring/JMX/OpenMBean/OTel SDK implementation
- core does not depend on Spring
- webmvc/webflux isolation
- exact public surface for sensitive packages
- internal decoder/schema types not public
- no legacy control-protocol paths
- no runtime apply from raw wire payload
- no domain rules in wire decoder
- no wildcard imports
- no custom W3C parser if the approved OTel implementation is required
- no unsafe telemetry labels where statically enforceable
- no mutation enabled by default

Do not weaken architecture rules to make generated code compile.

## Documentation

Document:

- public tracing API usage
- automatic vs manual instrumentation
- span naming
- attribute/cardinality policy
- sampling behavior
- force-sampling trust model
- scrubbing scope
- runtime-control operations
- mutation default
- diagnostics
- collector/exporter topology
- servlet/reactive behavior
- known limitations
- E2E execution instructions

Documentation must distinguish:

- current supported behavior
- historical design
- future proposals
- startup desired state
- live applied state
- application API
- agent/JMX integration

## Imports and Generated Code

For Cursor, Codex, and Perplexity-generated observability changes:

- read project context, API, Spring, testing, security, and Testcontainers skills
- inspect existing instrumentation before adding telemetry
- follow `.editorconfig`
- use explicit imports
- do not use wildcard imports
- do not create import-only churn
- do not add duplicate spans or metrics
- do not add telemetry without an operator use case
- state cardinality and privacy assumptions
- add negative and disabled-path tests
- run the narrowest affected compile/test tasks
- run opt-in E2E when runtime behavior crosses boundaries
- report whether E2E executed or was skipped

Generated code must not add “observability magic” through hidden aspects, global state, or silent runtime mutation.

## Pre-Production Breaking-Change Policy

Because the tracing solution is pre-production, prefer direct cleanup when it improves the final telemetry model.

Breaking changes are justified when they:

- remove duplicate spans
- correct span relationship semantics
- remove unsafe attributes
- rename ambiguous public tracing concepts
- eliminate custom propagation/parser logic
- narrow public API
- move domain rules out of API
- remove speculative runtime introspection
- change unsafe defaults
- remove stale metrics/log fields
- eliminate dual runtime paths

Default migration:

1. change to the intended final behavior
2. update all repository consumers
3. update tests/docs/samples
4. delete old path
5. add guards
6. run full verification
7. do not add aliases by default

## Anti-Patterns

Forbidden:

- telemetry without a concrete operational use case
- duplicate auto/manual spans
- high-cardinality metric labels
- trace/request/user IDs as metric labels
- raw paths as labels
- raw PII in spans, events, logs, metrics, baggage, or diagnostics
- logging secrets or control payloads
- using trace context for authorization
- custom tracing protocols without approval
- custom W3C parsing when OTel implementation is approved
- blocking telemetry export on application threads
- unbounded exporter queues/retries
- mutation enabled by default
- runtime apply before decode/domain validation
- public internal schema/validator
- silent unknown rule/configuration handling
- unbounded warning deduplication
- hidden retries
- liveness depending on optional telemetry backend
- dynamic metric names
- one span per loop item without a volume budget
- test-only telemetry behavior in production code
- skipped E2E reported as pass
- wildcard imports

## Required Verification

Select the applicable gates:

```powershell
.\gradlew.bat :platform-tracing-api:test --no-daemon
.\gradlew.bat :platform-tracing-otel:test --no-daemon
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --no-daemon
.\gradlew.bat :platform-tracing-autoconfigure-webmvc:test --no-daemon
.\gradlew.bat :platform-tracing-autoconfigure-webflux:test --no-daemon
.\gradlew.bat :platform-tracing-otel-extension:test --no-daemon
.\gradlew.bat :platform-tracing-api:javadoc --warning-mode all --no-daemon
.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
.\gradlew.bat build --warning-mode all --no-daemon
```

For runtime boundaries:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

Verify test reports when E2E is required:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

Run scans appropriate to the change:

- removed/legacy tracing symbols
- duplicate instrumentation names
- wildcard imports
- forbidden dependencies
- unsafe labels/attributes
- raw sensitive logging patterns
- BOM
- stale Javadoc links
- deprecated bridges/aliases

## Required Observability Report

A final observability-related implementation report must include:

```text
Operational question solved:
Signal(s) added or changed:
Owner module:
Span/metric/log/diagnostic names:
Cardinality budget:
Sensitive-data assessment:
Sampling behavior:
Disabled/no-op behavior:
Runtime-control impact:
Failure/degradation behavior:
Tests executed:
E2E executed or skipped:
Architecture fitness:
Performance evidence:
Residual risks:
```

Use:

- `PASS` only when required gates executed and passed
- `PASS_WITH_WARNINGS` when code is green and only documented non-blocking operational evidence remains
- `INSUFFICIENT_EVIDENCE` when a required runtime signal was not executed or observed
- `FAIL` when telemetry correctness, privacy, cardinality, architecture, or required gates fail

