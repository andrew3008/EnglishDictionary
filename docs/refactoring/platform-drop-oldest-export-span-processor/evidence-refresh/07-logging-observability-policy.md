# 07 — Logging and Observability Policy

> Evidence refresh. All line references are to `PlatformDropOldestExportSpanProcessor.java`.
> Do NOT modify production code on the basis of this file.

---

## logExportFailureOnce — Field and All Usage Locations

### Field Declaration

| Line | Field name | Type | Initial value | Semantics |
|------|-----------|------|---------------|-----------|
| 54 | `exportFailureLogged` | `final AtomicBoolean` | `false` | One-shot gate: once set to `true`, subsequent calls to `logExportFailureOnce()` are silently suppressed. The flag is never reset. |

### Method

```
Lines 385–390:
private void logExportFailureOnce(String message) {
    if (exportFailureLogged.compareAndSet(false, true)) {
        log.warn("PlatformDropOldestExportSpanProcessor: {} (subsequent failures throttled, см. exportFailures)",
                message);
    }
}
```

`compareAndSet(false, true)` — only the first thread to succeed emits a WARN. All subsequent calls are no-ops. The flag is permanent for the lifetime of the processor instance.

### All Call Sites

| Line | Method context | Message template |
|------|---------------|-----------------|
| 100 | `onEnd()` | `"toSpanData() failed: " + e.getMessage()` |
| 171 | `shutdown()` (inside terminator thread) | `"exporter.shutdown() failed: " + e.getMessage()` |
| 357 | `exportBatch()` | `"exporter.export() threw: " + e.getMessage()` |
| 364 | `exportBatch()` | `"exporter.export() timed out after N ms"` |
| 370 | `exportBatch()` | `"exporter.export() returned failure"` |

**Consequence:** if an export timeout fires first (line 364), all subsequent `exporter.export()` throws (line 357), `exporter.shutdown()` failures (line 171), and `toSpanData()` failures (line 100) are silently swallowed. SRE teams relying on log alerting for export failures may see exactly one WARN per processor instance lifetime, regardless of how many failures occur.

---

## Counter Fields

| Line | Field | Type | What is counted | Mutated by | Read by |
|------|-------|------|-----------------|-----------|---------|
| 49 | `droppedSpansOverflow` | `AtomicLong` | Spans evicted from queue head by drop-oldest policy (queue full at `enqueueWithDropOldest`) | Producer thread (line 199, inside `queueLock`) | JMX via `getDroppedSpansOverflow()` |
| 50 | `droppedSpansAfterShutdown` | `AtomicLong` | Spans rejected after `shutdownRequested=true`: fast-path in `onEnd` (line 92) + double-check inside `enqueueWithDropOldest` (line 193) + terminator queue-clear on timeout (line 159) | Producer thread + terminator thread | JMX via `getDroppedSpansAfterShutdown()` |
| 51 | `exportFailures` | `AtomicLong` | Any unsuccessful `exporter.export()`: thrown exception (line 356) + timeout (line 362) + failure result (line 369) + `exporter.shutdown()` throw (line 170) + unexpected worker RuntimeException (line 257) | Worker thread + terminator thread | JMX via `getExportFailures()` |
| 52 | `exportTimeouts` | `AtomicLong` | Subset of `exportFailures`: cases where `resultCode.join(exportTimeoutNanos).isDone() == false` (line 361) | Worker thread | JMX via `getExportTimeouts()` |

**Invariant documented in plan (§4):** `exportTimeouts ⊆ exportFailures` — both counters are incremented for the same timeout event (lines 362–363). A refactoring that increments one without the other breaks the documented invariant.

---

## Public Getter Methods

| Line | Method | Return type | Source field | Description |
|------|--------|-------------|-------------|-------------|
| 397 | `getDroppedSpansOverflow()` | `long` | `droppedSpansOverflow.get()` | Spans evicted by drop-oldest overflow policy |
| 402 | `getDroppedSpansAfterShutdown()` | `long` | `droppedSpansAfterShutdown.get()` | Spans rejected post-shutdown |
| 407 | `getExportFailures()` | `long` | `exportFailures.get()` | Total export failures (any cause) |
| 412 | `getExportTimeouts()` | `long` | `exportTimeouts.get()` | Export timeouts (subcategory of failures) |
| 417 | `getQueueCapacity()` | `int` | `maxQueueSize` (config, immutable) | Configured queue capacity |
| 422 | `getQueueSize()` | `int` | `queueSizeSafe()` (acquires `queueLock`) | Live queue depth |

These six getters are the current JMX wire contract. `PlatformExportControl` reads them. Replacing them with `MetricsSnapshot` in PR-4 changes the JMX read path and requires `PlatformExportControl` to be updated in the same PR.

---

## Three Options for logExportFailureOnce Behavior

### Option 1: Preserve One-Shot (No Change)

**Mechanism:** keep `AtomicBoolean exportFailureLogged` as-is. Emit exactly one WARN per processor lifetime.

**Observable delta:** none. Current behavior is preserved.

**Risk:** none from a contract perspective. The existing M9 characterization test (log throttling) documents the one-shot behavior and will continue to pass.

**When appropriate:** default choice if no SRE coordination is scheduled. Preserves the characterization test baseline without requiring any operational notification.

---

### Option 2: Rate-Limited (Replace AtomicBoolean with rate limiter)

**Mechanism:** replace `AtomicBoolean` with a rate limiter (e.g., Guava `RateLimiter`, or a manual `AtomicLong` tracking last-logged timestamp). Emit at most one WARN per N seconds (configurable or fixed).

**Observable delta:** **multiple WARN log entries are now possible** for the same processor instance. SRE teams with alert rules matching `"PlatformDropOldestExportSpanProcessor:.*subsequent failures throttled"` will see new alerts. Alert counts increase. Dashboards counting WARN occurrences will change.

**Risk:** medium. This is a **logging behavior change observable by SRE**. Any monitoring system that suppresses pages based on "only one alert expected per instance" may fire repeatedly after this change.

**Requires SRE coordination:** yes. Must document the new rate and update runbooks before deploying.

---

### Option 3: Structured (Last-Failure in MetricsSnapshot)

**Mechanism:** remove or suppress the WARN log. Instead, add `lastExportFailureReason` (String) and `lastExportFailureTimestamp` (Instant) fields to `DropOldestProcessorMetricsSnapshot`. Expose through JMX as additional attributes.

**Observable delta:** **log behavior changes completely** (no WARN emitted on export failure). SRE teams that page on the WARN message will lose their alert. JMX monitoring is enriched instead.

**Risk:** high from an observability continuity perspective. Teams relying on log-based alerting for export failures will be silently blind until they migrate to JMX-based alerting.

**Requires SRE coordination:** yes. Requires advance notice, runbook update, and parallel validation period.

---

## Explicit Policy Statement

> **Changing `logExportFailureOnce` behavior is an observable change requiring SRE coordination, not a refactoring decision.**

The current one-shot log throttle is operator-observable: it produces exactly one WARN per processor instance lifetime, regardless of failure frequency. SRE teams and monitoring infrastructure may depend on:
- Presence of the WARN message as an alerting signal
- Absence of repeated WARNs as a noise-suppression guarantee

Any change to this behavior — whether to rate-limited, suppressed, or structured — alters the observable signal contract. This decision must be made explicitly by the platform team with SRE coordination and must not be subsumed under "clean lifecycle refactoring" or "logging cleanup."

The correct place for this decision is a named ADR section or an explicit clause in the PR-3/PR-4 acceptance criteria, not a silent line change in the implementation.

**Default recommendation:** preserve Option 1 (no change) through PR-0 to PR-5. Document the option in the plan as a deferred decision with an explicit gate: "changing logExportFailureOnce behavior requires SRE coordination and a separate PR."
