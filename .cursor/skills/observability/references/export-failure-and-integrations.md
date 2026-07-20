# Export, Failure, Async, and Integration Observability

## Exporter and Collector Behavior

Export must not block application request threads.

Prefer:

- batch processing
- bounded queues
- bounded retries
- asynchronous export
- explicit drop behavior
- observable failure and queue pressure

Avoid:

- synchronous export on request path
- unbounded queues
- unbounded retries
- silent drops
- startup network calls from ordinary auto-configuration
- credential logging

Metrics/logs should make queue pressure, drop, and exporter failures visible without high cardinality.

## Failure and Degradation

Choose failure behavior intentionally.

### Tracing disabled

Application continues with safe no-op API.

### Collector/exporter unavailable

Usually degrade without blocking business traffic, while emitting bounded diagnostics and metrics.

### Invalid startup tracing configuration

Fail startup if continuing would violate a mandatory safety invariant; otherwise disable only the affected optional capability with an actionable warning.

### Invalid runtime policy

Reject without state change.

### Scrubbing failure

Follow the approved critical/non-critical policy. Never silently export unreviewed sensitive data after a mandatory scrubbing failure.

### Agent/SDK mismatch

Fail or degrade explicitly. Do not silently run two competing runtimes.

## Retries

Retry ownership must be explicit.

Avoid retry multiplication across:

- exporter SDK
- collector
- HTTP client
- Spring Retry/Resilience4j
- application loop
- Kubernetes restart

Retries must be:

- bounded
- jittered where appropriate
- observable
- classified by error
- safe for idempotency
- stopped for non-retryable errors

Do not create a span per retry attempt unless this is an intentional, bounded design.

## Asynchronous and Reactive Observability

Reactive paths must avoid:

- blocking calls
- `ThreadLocal` assumptions
- hidden context loss
- duplicate subscription instrumentation
- MDC leakage

Tests must cover:

- subscription context
- cancellation
- errors
- retries
- thread hops
- context restoration
- duplicate spans
- outbound propagation

Instrumentation must not change reactive semantics.

## Messaging and Kafka

Messaging observability must distinguish:

- producer send
- consumer receive/process
- batch processing
- retries/replays
- dead-letter handling

Use links for batch/fan-in semantics where parent-child is incorrect.

Do not put message keys, payloads, customer IDs, or raw topics from untrusted input into high-cardinality metric labels.

Topic names may be attributes only if the organization's telemetry policy permits them and cardinality is bounded.

Avoid duplicate spans when agent instrumentation already exists.

## Database Observability

Prefer standard client/agent instrumentation.

Manual database spans should exist only for an identified semantic gap.

Do not record:

- raw SQL with sensitive values
- bind parameters
- credentials
- full connection strings
- unbounded statement text

Use semconv-compatible database attributes and documented version markers.

## Kubernetes Observability

Deployment guidance should support:

- Prometheus scraping
- structured stdout/stderr logs
- collector/agent topology visibility
- resource identity
- readiness/liveness semantics
- rollout diagnostics
- network-policy-aware exporter paths

Do not assume:

- one cluster topology
- local collector
- fixed pod IP
- unrestricted JMX
- environment-specific instrumentation code

