# 03 — JMX Snapshot Migration Evidence

> Evidence-first. All citations are to actual repository files read on 2026-06-30.
> "Evidence missing" marks claims without repository support.

---

## Current JMX Access Path

```
PlatformTracingJmxRegistrar.setExportProcessor(PlatformDropOldestExportSpanProcessor)
    → stores processor reference
    → PlatformExportControl holds Supplier<PlatformDropOldestExportSpanProcessor>

JMX client (MBeanServer) calls MBean attribute getter
    → PlatformExportControl.getExportDroppedOverflowTotal()
        → exportProcessorOrNull()  [PlatformExportControl.java:46]
        → proc.getDroppedSpansOverflow()  [PlatformExportControl.java:47]
        → AtomicLong.get()  [PlatformDropOldestExportSpanProcessor.java:398]
```

Source: `PlatformExportControl.java:19–25` (supplier pattern), `PlatformExportControl.java:45–78` (six getter delegation chain).

---

## Current JMX Attribute Names (All Six — from MBean Interface)

Source: `PlatformExportControlMBean.java` (the JMX interface) and `PlatformExportControl.java`.

| MBean attribute method | Return type | Calls on processor | Processor source line |
|------------------------|-------------|--------------------|-----------------------|
| `getExportDroppedOverflowTotal()` | `long` | `proc.getDroppedSpansOverflow()` | 397–399 |
| `getExportDroppedAfterShutdownTotal()` | `long` | `proc.getDroppedSpansAfterShutdown()` | 401–403 |
| `getExportFailuresTotal()` | `long` | `proc.getExportFailures()` | 407–409 |
| `getExportTimeoutsTotal()` | `long` | `proc.getExportTimeouts()` | 411–413 |
| `getExportQueueCapacity()` | `int` | `proc.getQueueCapacity()` | 417–419 |
| `getExportQueueSize()` | `int` | `proc.getQueueSize()` | 423–424 |

Additional MBean attributes (not from processor getters):
- `isExportEnabled()` / `setExportEnabled(boolean)` — from `SafeSpanExporter`
- `getSafeExporterMetrics()` → `Map<String, Long>` — from `SafeSpanExporter.metricsSnapshot()`

**Key finding:** the processor's getter method names (`getDroppedSpansOverflow`, etc.) and the JMX MBean attribute names (`getExportDroppedOverflowTotal`, etc.) are **NOT the same**. `PlatformExportControl` acts as a translation layer. Changing the processor getter names only breaks `PlatformExportControl.java` — the JMX wire names can be kept stable independently.

---

## ADR/Doc Status for JMX Wire Contract

**Referenced ADR in `06-refactoring-constraints.md`:** "`ADR-jmx-wire-map-contract.md` in `docs/architecture/`" — cited as authority for JMX attribute wire contract.

**Actual content of `ADR-jmx-wire-map-contract.md`:** This ADR covers a **completely different subsystem** — a Map-based wire protocol (`Map<String, Object>`) for cross-classloader JMX control plane communication (sampler/scrubbing runtime configuration via JMX invoke). Status: "Superseded (production purge) — wire schema retained, JMX spike transport removed from production." It does NOT cover the six export-counter observability attributes.

**Evidence missing:** No ADR, no architecture doc, and no wire-contract specification exists for the six export-processor JMX MBean attributes (`getExportDroppedOverflowTotal`, etc.). The `06-refactoring-constraints.md` citation to `ADR-jmx-wire-map-contract.md` is misleading in this context.

**Implication:** There is no formal authority blocking renaming these JMX attributes. However, there is also no formal authority declaring them changeable. This is an unresolved governance gap that the new ADR must address.

---

## Three Migration Options with Risks

### Option A: Preserve Old MBean Attribute Names (JMX getter methods in MBean unchanged)

**Mechanism:** `PlatformExportControl` keeps the same method names (`getExportDroppedOverflowTotal()`, etc.) but implements them by reading from `DropOldestProcessorMetricsSnapshot` instead of calling processor getters directly.

```java
// Current: proc.getDroppedSpansOverflow()
// New:     metrics.snapshot().droppedSpansOverflow()
// MBean attribute name: unchanged → getExportDroppedOverflowTotal()
```

**Risks:**
- Zero wire breakage for JMX clients — safest operator impact
- `PlatformExportControl` must hold a `Supplier<DropOldestProcessorMetrics>` instead of `Supplier<PlatformDropOldestExportSpanProcessor>`
- `PlatformTracingJmxRegistrar.setExportProcessor()` must be updated to accept metrics supplier instead of (or in addition to) processor reference

**Required tests:** JMX attribute value round-trip test: after N spans, `getExportDroppedOverflowTotal()` returns N dropped spans (snapshot-derived value matches expected).

### Option B: New MBean Schema Derived from MetricsSnapshot (New Operator Contract)

**Mechanism:** `PlatformExportControlMBean` gets new method names aligned with snapshot field names. Plan §7 proposes:

```
DroppedSpansOverflow, DroppedSpansAfterShutdown, ExportFailures, ExportTimeouts,
QueueCapacity, QueueSize, LifecycleState, WorkerState,
LastExportFailureReason, LastExportFailureTimestamp, LastSuccessfulExportTimestamp
```

**Risks:**
- JMX clients using current attribute names (`ExportDroppedOverflowTotal`, etc.) will break silently — they will query the old MBean name and get a JMX `AttributeNotFoundException`
- No operator inventory of JMX clients exists (**Evidence missing**)
- Without a formal wire contract ADR for these attributes, there is no official "breaking change" to announce
- New attributes (`LifecycleState`, `WorkerState`) require implementation in `DropOldestProcessorMetricsSnapshot` — these do not exist in current code

**Required tests:** `DomainMBeanJmxComplianceTest.java` must be updated to assert new attribute names; old attribute name requests must produce `AttributeNotFoundException`.

### Option C: Dual Schema — Temporary Bridge Period

**Mechanism:** `PlatformExportControlMBean` exposes both old attribute names (delegating to snapshot for backward compat) and new snapshot-derived attributes simultaneously. Old attributes deprecated in PR-4, removed in PR-5.

**Risks:**
- Doubles JMX surface during bridge period
- Risk of attribute value inconsistency if snapshot is taken at different moments for old vs new attributes
- Complicates `DomainMBeanJmxComplianceTest.java` compliance assertions
- Only justified if operator JMX client migration window is needed — but no operator client inventory exists

---

## What the MetricsSnapshot → JMX Adapter Must Guarantee

Regardless of option chosen:

| Guarantee | Source invariant | Verification |
|-----------|-----------------|--------------|
| `droppedSpansOverflow` is atomically consistent with actual evictions | `enqueueWithDropOldest()` line 199: `droppedSpansOverflow.incrementAndGet()` called under `queueLock` | Snapshot read must be from `AtomicLong.get()` (already thread-safe) |
| `exportTimeouts ⊆ exportFailures` | Lines 362–363: both incremented on timeout; line 369: only `exportFailures` on non-success | Snapshot must include both fields; adapter must not expose one without the other |
| `queueSize` is a point-in-time read | `queueSizeSafe()` at line 377: acquires `queueLock` | Snapshot `queueSize` must be captured under lock or be an `AtomicInteger` if moved to `ExportBuffer` |
| `queueCapacity` is immutable | `maxQueueSize` is `private final` | Snapshot field must be a constant from config |
| JMX reads must not block producers | `getQueueSize()` acquires `queueLock` briefly | Snapshot approach can decouple JMX reads from lock acquisition by caching latest snapshot |

---

## Tests Needed

| Test | What it verifies | When |
|------|-----------------|------|
| JMX attribute value round-trip (overflow) | After N overflow evictions, MBean attribute returns N | PR-4 |
| JMX attribute value round-trip (export failures) | After M failed exports, `exportFailures ≥ M` and `exportTimeouts ≤ exportFailures` | PR-4 |
| JMX attribute type compliance | All MBean attributes return declared types (`long`/`int`/`Map`) | PR-4 (via `DomainMBeanJmxComplianceTest.java`) |
| Snapshot atomicity | `queueSize` in snapshot is consistent (≤ `queueCapacity`) | PR-1 unit test of `DropOldestProcessorMetricsSnapshot` |
| Old attribute backward compat (if Option A) | Existing JMX attribute names still reachable after migration | PR-4 |
| New attribute availability (if Option B) | New snapshot-derived attributes reachable via MBeanServer | PR-4 |

---

## Evidence Missing Items

1. **No operator JMX client inventory** — no document lists which monitoring tools, Grafana dashboards, or operator scripts query the six export-processor JMX attributes. Without this, risk of operator breakage from Option B cannot be quantified.
2. **No formal ADR for current six JMX attribute names** — `ADR-jmx-wire-map-contract.md` is about a different subsystem (see above). The governance gap must be closed in the new V6-clean-lite ADR.
3. **`LifecycleState` and `WorkerState` snapshot fields** — mentioned in plan §7 JMX schema as new attributes, but these fields do not exist in any current class. Their values (`RUNNING`, `SHUTTING_DOWN`, `TERMINATED`, etc.) must be defined in `DropOldestProcessorMetricsSnapshot` before PR-4 can expose them.
4. **`LastExportFailureReason`, `LastExportFailureTimestamp`, `LastSuccessfulExportTimestamp`** — plan §7 lists these as new JMX attributes. Current code has no timestamp fields in the processor. These require new state in `DropOldestProcessorMetrics` and are not covered by any existing test.
5. **`PlatformTracingJmxRegistrar.setExportProcessor()` contract** — this method is called by `PlatformExportProcessorFactory.java:102`. After PR-4, this call either needs a new overload accepting `DropOldestProcessorMetrics` or the factory must extract metrics from the new processor facade. No migration path is specified in the plan.
