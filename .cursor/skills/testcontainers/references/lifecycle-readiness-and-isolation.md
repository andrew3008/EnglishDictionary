# Lifecycle, Readiness, Timeouts, and Isolation

## Container Lifecycle

Prefer static shared containers only when:

- startup cost is material
- state is isolated per test
- tests do not depend on execution order
- parallel execution is safe
- container state is reset or uniquely namespaced

Do not use a singleton/shared container merely to make tests faster.

For mutable backends:

- use unique trace IDs, topics, database names, buckets, or namespaces
- clean state between tests when necessary
- never assert against unfiltered global backend state
- do not reuse an old trace/span name as the only lookup discriminator

Container reuse:

- may be enabled intentionally for local development
- must not be required for correctness
- must not hide initialization defects
- should normally be disabled in CI
- must not be assumed by tests

Do not use fixed container names.

## Networks

Prefer one explicit Testcontainers network per integration suite when container-to-container communication is required.

Rules:

- network aliases must be unique and intentional
- do not assume Docker's default bridge behavior
- do not depend on host DNS for container-to-container calls
- close custom networks with the test lifecycle
- avoid sharing a mutable network across unrelated parallel suites

If using the Gentoo DNS workaround, document it in the support helper and test runbook.

## Readiness

Container started does not mean service ready.

Use the strongest practical readiness signal.

Preferred:

- `Wait.forHttp(...)`
- `Wait.forHealthcheck()`
- `Wait.forLogMessage(...)` with a stable, version-pinned message
- service-specific probe
- functional API readiness check

Use `Wait.forListeningPort()` only when a listening socket is sufficient evidence.

Examples:

- Collector: configuration loaded and receivers started
- Jaeger: query/OTLP endpoint is responsive
- Kafka: broker is ready for metadata/topic operations
- PostgreSQL: connection and simple query succeed

Never use:

```java
Thread.sleep(...)
```

Use bounded Awaitility for eventual backend visibility after export.

Separate these waits:

1. container process readiness
2. application/exporter readiness
3. eventual trace visibility in the backend

## Timeouts

Every wait must be bounded.

Rules:

- use explicit startup timeouts for slow images
- use explicit Awaitility timeouts for exported trace visibility
- include useful failure messages
- avoid excessively long global timeouts that hide hangs
- do not retry forever

Timeout values must be justified by the service and environment, not copied blindly.

## Test Isolation

Tests must be safe under parallel execution or explicitly serialized.

Avoid:

- shared mutable static state
- global backend queries without unique correlation
- common Kafka topic names across tests
- fixed database schemas
- fixed bucket names
- fixed trace names as the only identifier

Prefer:

- UUID/correlation-based identifiers
- per-test resource names
- unique request IDs
- query filters using trace ID plus expected span data
- resource cleanup in `finally` / lifecycle hooks

If a test cannot run in parallel, document and enforce that constraint.

