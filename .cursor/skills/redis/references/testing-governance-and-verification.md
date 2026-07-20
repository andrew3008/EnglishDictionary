# Testing, Governance, and Verification

## Spring Testing

Use `ApplicationContextRunner` for:

- disabled integration
- enabled integration
- missing Redis classes
- custom bean override
- property defaults
- invalid configuration
- no eager connection
- bean absence

Use `@SpringBootTest` only when the full lifecycle or real integration is required.

Use `FilteredClassLoader` for optional classpath tests.

Do not allow a user-provided client bean to bypass mandatory namespace, timeout, serialization, or security policies unless the extension contract explicitly permits it.

## Architecture Fitness Rules

Protect at least:

- public API does not expose Redis client/framework types
- core tracing code does not depend directly on Spring Data Redis/Redisson
- Redis integration lives in the approved module
- no Java native serialization
- no hard-coded endpoints or credentials
- no fixed TTL-free lock implementation
- no unmanaged native connections
- no wildcard key scans such as production `KEYS *`
- no Redis dependency when the feature is disabled/absent
- servlet/reactive adapters do not own Redis infrastructure logic

Do not weaken architecture rules to accommodate generated code.

## Dangerous Commands and Patterns

Avoid in production paths:

- `KEYS`
- unbounded `SCAN`
- `FLUSHALL`
- `FLUSHDB`
- `EVAL` with unreviewed scripts
- broad deletes by pattern
- blocking list operations without bounded timeout
- transactions over unrelated keys
- unrestricted `MONITOR`
- synchronous administrative commands from request paths

Administrative operations require a separate operational/security review.

## Imports and Generated Code

For Cursor, Codex, and Perplexity-generated Redis changes:

- read project context, Spring, security, testing, and Testcontainers skills first
- inspect existing Redis support before adding abstractions
- verify Redis is actually required
- follow `.editorconfig`
- use explicit imports
- do not use wildcard imports
- do not create import-only churn
- do not duplicate connection factories, serializers, namespaces, or retry policies
- add negative/failure tests
- run affected compile/test tasks
- state whether Testcontainers tests executed or were skipped

Generated code must not add Redis as a speculative dependency.

## Breaking-Change Policy

Because the tracing solution is pre-production, prefer cleanup over compatibility preservation.

Breaking changes are justified when they:

- remove unsafe serializers
- replace ambiguous key namespaces
- remove hidden local fallbacks
- delete false extension points
- eliminate duplicated retry or connection ownership
- replace unsafe locks
- move Redis types out of public API
- introduce explicit TTL/size limits
- remove unsupported engine/topology claims

Do not keep dual key formats or dual serializers indefinitely. If migration is required, define a bounded migration window and deletion criteria.

## Anti-Patterns

Forbidden:

- adding Redis without a proven distributed-state requirement
- treating Redis as primary irreplaceable storage
- Java native serialization
- fixed host/port assumptions
- hard-coded credentials
- `latest` image tags
- infinite retries
- infinite locks
- unconditional lock release
- locks without ownership token
- critical external side effects without fencing where stale owners are possible
- cache-dependent correctness
- unbounded keys or values
- raw PII/trace IDs in key names
- full keys as telemetry labels
- unbounded warning-deduplication state
- hidden local fallback for distributed coordination
- Redis Pub/Sub for durable workflows
- blocking Redis calls on reactive event-loop threads
- startup network calls for optional features
- production `KEYS *`
- tests that require container reuse
- skipped integration tests reported as pass
- speculative Redis abstractions in tracing modules

## Required Verification

For a Redis-related change, run the narrowest applicable tasks:

```powershell
.\gradlew.bat :<affected-module>:compileJava --no-daemon
.\gradlew.bat :<affected-module>:test --no-daemon
.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
```

For Docker-backed integration:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :<affected-module>:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

Run relevant scans:

- wildcard imports
- Java native serialization
- `KEYS`/`FLUSHALL`/`FLUSHDB`
- hard-coded Redis endpoints
- hard-coded credentials
- missing TTL in lock/cache paths where statically detectable
- forbidden Redis client types in public API

## Required Report

A final Redis-related implementation report must include:

```text
Use case:
Why Redis/KeyDB is required:
Owner module:
Supported engine/topology:
Key namespace:
TTL/lifecycle:
Serialization format/version:
Failure policy:
Retry owner:
Security assumptions:
Observability:
Tests executed:
Testcontainers executed or skipped:
Architecture fitness:
Residual risks:
```

Use:

- `PASS` only when required tests executed and passed
- `PASS_WITH_WARNINGS` when code is green and only documented non-blocking operational risk remains
- `INSUFFICIENT_EVIDENCE` when a claimed engine/topology/failover behavior was not executed
- `FAIL` when correctness, security, TTL, ownership, or required integration gates are not satisfied

