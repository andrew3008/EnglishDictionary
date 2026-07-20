# State, Concurrency, Side Effects, Failure, and No-Op

## State Architecture

Runtime state must be:

- immutable or safely published
- atomically updated
- versioned where needed
- readable without partial state
- protected from conflicting mutations
- recoverable through LKG where applicable

Test:

- rejected mutation
- concurrent read during apply
- repeated apply
- partial failure
- rollback
- idempotency

Do not represent live state only through Spring property objects.

## Concurrency Architecture

Do not assume singleton means thread-safe.

Clarify:

- ownership
- publication
- synchronization
- idempotency
- blocking behavior
- callback execution context
- lifecycle

Reactive paths must not depend on ordinary `ThreadLocal` behavior.

Do not block Reactor event-loop threads.

## Side-Effect Architecture

Side effects must be explicit and owned.

Review:

- static initialization
- bean construction
- JMX registration
- global OTel mutation
- background threads
- exporter creation
- file access
- Docker access
- network access
- system properties

Side effects must be:

- lifecycle-managed
- idempotent where needed
- reversible where practical
- observable
- tested

No network/Docker/file mutation during ordinary auto-configuration unless explicitly required.

## Failure Architecture

Choose one:

- fail closed
- fail startup
- degrade safely
- no-op intentionally

Do not silently fall back to permissive behavior.

Examples:

### Fail closed

- disabled mutation
- invalid control payload
- domain-invalid policy
- unsafe exporter endpoint mutation

### Fail startup

- mandatory safety invariant missing
- mutually exclusive critical configuration
- required security component absent

### Degrade safely

- optional diagnostics unavailable
- exporter/collector outage under approved policy
- tracing explicitly disabled

Failure result must identify the owner and leave runtime state consistent.

## No-Op Architecture

No-op tracing is an intentional capability.

No-op behavior must:

- preserve public API shape
- preserve lifecycle contracts
- avoid network calls
- avoid mutation
- avoid false export diagnostics
- remain low overhead
- remain independently tested

Do not use no-op behavior to bypass builder/domain validation if callers depend on those invariants.

