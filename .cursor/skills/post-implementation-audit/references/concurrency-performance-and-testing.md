# Concurrency, Performance, and Testing Review

## Concurrency Review

Review:

- immutability
- publication
- atomic state update
- race conditions
- lock scope
- idempotency
- rejection behavior
- retry behavior
- concurrent reads during mutation
- partial failure
- rollback
- static mutable state

Runtime state must not expose partially applied policy.

Do not assume singleton beans are automatically thread-safe.

## Reactive Review

Reject:

- blocking calls on Reactor event-loop threads
- `ThreadLocal` assumptions
- context leakage
- MDC leakage
- hidden subscriptions
- duplicate instrumentation
- unbounded retry/repeat
- synchronous exporter/network call
- blocking bridge to JMX/Redis/HTTP without isolation

Require tests for:

- thread hops
- errors
- cancellation
- retries
- context restore
- duplicate spans
- outbound propagation

## Performance Review

Review performance only in the relevant path.

### Hot path

Look for:

- allocations per span
- temporary collections
- regex compilation
- string formatting when disabled
- synchronization
- reflection
- map copies
- context conversions
- logging argument construction
- expensive policy evaluation

### Startup

Look for:

- eager beans
- network calls
- classpath scanning
- JMX side effects
- exporter creation
- optional integration loading
- file/Docker access
- background threads

### Build

Look for:

- eager task realization
- broad filesystem scan
- configuration-time external access
- non-deterministic generation
- cache-unsafe custom tasks

Performance findings must include expected impact and evidence.

Do not block correctness/privacy improvements for speculative micro-optimization.

## Test Review

Tests must prove behavior.

Review:

- happy path
- boundary values
- invalid input
- disabled/no-op behavior
- failure behavior
- state preservation
- optional classpath
- concurrency where relevant
- architecture boundary
- docs/golden synchronization
- E2E execution

Reject tests that:

- copy production logic
- assert private implementation
- use `Thread.sleep`
- depend on execution order
- hide missing behavior behind defaults
- add shims to make old tests compile
- use broad `@SpringBootTest` unnecessarily
- claim PASS after skip
- use probability for deterministic behavior

## Testcontainers and E2E Review

Verify:

- pinned images
- dynamic ports
- no fixed localhost
- correct host/container addressing
- remote Docker compatibility
- explicit readiness
- bounded Awaitility
- no Windows bind mount to remote Gentoo Docker
- unique test identifiers
- actual test execution

For required E2E, verify:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

A compiled harness is not runtime evidence.

