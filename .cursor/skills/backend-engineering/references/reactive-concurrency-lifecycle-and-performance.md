# Reactive, Concurrency, Lifecycle, and Performance

## Reactive Code

Reactive paths must not:

- block event-loop threads
- assume thread-local propagation
- leak MDC/context
- create hidden subscriptions
- use unbounded retries/repeats
- duplicate spans
- make synchronous exporter/JMX/Redis/network calls without isolation

Tests must cover:

- thread hops
- cancellation
- errors
- retries
- context restore
- duplicate spans
- outbound propagation

## Concurrency and Runtime State

Runtime state must be:

- immutable or safely published
- atomically updated
- readable without partial state
- idempotent where needed
- protected against conflicting concurrent applies
- recoverable through last-known-good state when applicable

Test:

- concurrent reads during apply
- repeated apply
- rejected apply
- partial failure rollback
- registration rollback
- no static test leakage

Do not assume singleton beans are thread-safe.

## Startup and Lifecycle

Startup must avoid:

- network calls from ordinary auto-configuration
- Docker/file-system side effects
- unbounded waits
- hidden threads
- eager optional integrations
- global OTel mutation without ownership
- JMX mutation
- system-property changes
- silent fallback after critical failure

Side effects must be:

- explicit
- lifecycle-managed
- idempotent where needed
- reversible where practical
- tested

## Performance

Review performance in context.

### Hot path

Look for:

- per-span allocations
- repeated maps/lists
- regex compilation
- unnecessary string formatting
- synchronization
- reflection
- eager attribute construction
- context conversion
- logging overhead
- unbounded state

### Startup

Look for:

- eager beans
- classpath scanning
- external calls
- exporter construction
- optional integration loading
- background threads

Correctness, privacy, and architecture take priority over speculative micro-optimization.

Use JMH only as one source of evidence.

