# ADR — TraceOperations Topology and Links

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-07-07 |
| Context | TraceOperations v3 refactoring |

## Context

v1 `SpanRelation` mixed topology intent with an overloaded API surface. Post-start `addLink()` allowed links after span start, complicating metering and governance. Platform batch processing requires correlating one batch span with many message traces.

## Decision

v3 replaces `SpanRelation` with explicit topology setters on builders:

```text
.child()    — CHILD (default when active context exists)
.root()     — new trace, ignore active parent
.detached() — new trace, no parent, no links
```

Links are **pre-start only** via `.linkedTo(RemoteSpanLink...)` or `.fromRemoteContext(String... traceparents)`.

## Topology + links policy

| Topology | Links allowed | Notes |
|----------|:-------------:|-------|
| ROOT | Yes | Primary pattern for Kafka batch processing |
| DETACHED | No | Fail fast if links present |
| CHILD | No | Fail fast if links present (v3) |

Repeated explicit topology setter throws `IllegalStateException` (builder final-state semantics).

## Why ROOT / DETACHED / CHILD semantics

- **CHILD** — default for business logic inside an active request trace.
- **ROOT** — scheduled jobs, batch processing, or intentional new trace while ignoring thread-local parent.
- **DETACHED** — fire-and-forget work that must not inherit or link to upstream context (audit, background isolation).

## Post-start links removed

v1 `addLink(...)` had no v3 equivalent. Links MUST be configured before `start()` / scoped execution. This keeps `SpanSpec` immutable at the creation boundary and simplifies metering/topology proof (`MeteredTopologyMatrixTest`).

## Consequences

### Positive

- Predictable, testable topology matrix.
- Immutable spec at creation time.

### Negative

- Call sites that added links after start must restructure to pre-start configuration.

## References

- [platform-tracing-v3-manual-api.md](../tracing/platform-tracing-v3-manual-api.md)
- [ADR-platform-tracing-kafka-batch-links.md](./ADR-platform-tracing-kafka-batch-links.md)
