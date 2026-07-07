# 10 — Perplexity Deep Research Correction Input

> Self-contained prompt for Perplexity Deep Research.
> No repository access is required — all necessary context is included below.
> Paste this document in full as the Perplexity prompt.

---

## Context: Java SpanProcessor Pre-Production Refactoring

A Java `SpanProcessor` (~575 lines, `PlatformDropOldestExportSpanProcessor`) implements a drop-oldest queue overflow policy for distributed tracing. It is pre-production (not yet in a production deployment). A refactoring plan called **V6-clean-lite** was proposed, reviewed by a multi-model ensemble, and tentatively approved. Principal architects have raised 10 objections. The goal of this Deep Research session is to evaluate those objections and produce replacement wording for overconfident phrases in the plan.

---

## Plan Summary (V6-clean-lite)

**Decomposition target (~7–8 production classes):**
- `PlatformDropOldestExportSpanProcessor` — thin SpanProcessor facade (no queue/worker/shutdown/timeout/builder logic)
- `ExportBuffer` — owns `ReentrantLock`, `Condition`, `ArrayDeque<SpanData>`, and `List<CompletableResultCode>` (pending flush requests); drop-oldest accounting
- `ExportWorker` — owns the worker thread; calls `ExportBuffer.awaitWork()` which returns a ready `ExportWork` value with lock already released
- `ProcessorLifecycle` — owns processor state machine (`RUNNING` / `SHUTTING_DOWN` / `TERMINATED`), `shutdownResult`, and public `forceFlush`/`shutdown` policy (shutdown coordination as private methods)
- `TimedSpanExporter` — synchronous (no new threads); wraps `exporter.export(batch)` with a timeout; classifies success/failure/timeout as private methods
- `DropOldestExportProcessorConfig` — immutable config + validation + `otel.bsp.*` key reading
- `DropOldestProcessorMetrics` + `DropOldestProcessorMetricsSnapshot` — observability counters + immutable snapshot

**JMX:** `PlatformExportControl` (existing class) adapts `MetricsSnapshot` to JMX attributes; JMX schema may be redesigned.

**PR sequence:**
- PR-0: characterization tests + JMH baseline (no production changes)
- PR-1: `Config` / `TimedSpanExporter` / `Metrics` unit tests only
- PR-2: `ExportBuffer` / `ExportWorker` / `ProcessorLifecycle` + thin facade migration
- PR-3: clean shutdown / forceFlush semantics (bounded shutdown, deterministic forceFlush)
- PR-4: replace `Builder` API, migrate 6 getters to `MetricsSnapshot`, JMX adapter, update factory wiring
- PR-5: remove legacy / transitional debris
- PR-6: JMH + stress validation

**Breaking changes acknowledged:**
- `Builder` API replaced by config-first builder
- 6 public JMX getter methods (`getDroppedSpansOverflow`, `getDroppedSpansAfterShutdown`, `getExportFailures`, `getExportTimeouts`, `getQueueCapacity`, `getQueueSize`) replaced by `MetricsSnapshot`
- JMX attribute schema redesign allowed (old attribute names declared not a constraint)

---

## 10 Principal Architect Objections

1. **"Target architecture" stated without ADR proof.** The plan states "V6-clean-lite is the target architecture, not a conditional future option." No repository ADR formally approves this decomposition. The correct framing is "design hypothesis pending Phase 0–3 validation," not a confirmed target.

2. **Pre-production mode does not exempt operator-facing contracts from migration coordination.** The plan's "backward compatibility not a hard constraint" applies to internal implementation details, but the `Builder` API is consumed by `PlatformExportProcessorFactory`, and the JMX schema is consumed by operators and monitoring automation. These require migration coordination regardless of pre-production status.

3. **JMX and Builder are consumed by factory and operators, not internal details.** The plan groups the `Builder` API and JMX schema redesign as freely breakable under "pre-production mode," but factory wiring is a critical deployment path and JMX dashboards are operational tooling. Breaking them silently risks deployment failures and monitoring gaps.

4. **Factory opt-in gating, multi-exporter fallback, rollback-by-unsetting-policy are safety-critical factory behaviors.** If the env var `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=DROP_OLDEST` is unset, the system must route to stock BSP. This rollback path is the primary production safety valve. It must have an explicit test gate in PR-4.

5. **PR-4 factory changes lack an explicit rollback integration gate.** The plan lists "update factory wiring in the same PR" as a PR-4 task but does not require a test that proves: unsetting the opt-in policy → stock BSP is used, not the custom processor.

6. **Contradiction: "backward compat not required" + "temporarily keep legacy getters."** PR-2 acceptance criteria say the facade "may temporarily keep old getter methods delegating to `metrics.snapshot()`" to avoid breaking tests. This contradicts the "backward compatibility not a hard constraint" stance. The plan has not resolved whether this is an explicit migration bridge (acceptable) or an unintentional contradiction (not acceptable).

7. **Bounded shutdown + deterministic forceFlush are observable semantic changes needing an ADR.** Currently, `exporter.shutdown()` has no timeout. The terminator thread waits for `exporter.shutdown()` without a bound — if it hangs, `shutdownResult` is never completed and the OTel SDK hangs indefinitely. PR-3 proposes adding a bounded timeout. This changes what `shutdown().join()` callers observe (it now completes within a deadline). That is an observable behavioral change, not a neutral "clean lifecycle" refactoring.

8. **ExportBuffer + ProcessorLifecycle dual ownership risk (hidden second coordinator).** Both `ExportBuffer` (owns the lock and pending flush list) and `ProcessorLifecycle` (owns the state machine and shutdown result) need to coordinate during shutdown and forceFlush. If `ProcessorLifecycle` accumulates secondary responsibilities over buffer state (reading `pendingFlushes`, signaling the condition, checking queue size), it becomes a hidden second coordinator — recreating the current class's complexity in a different form.

9. **M1/M2 forceFlush/shutdown race must be a strict PR-3 gate, not accepted as `@Disabled`.** The Phase 0 Failure Decision Policy allows M1 and M2 to remain `@Disabled` as "known failures" with PR-1/PR-2 proceeding in parallel. The plan does not state that M1/M2 being `@Disabled` **blocks PR-3 from merging**. A team under schedule pressure could merge PR-3 with those tests still disabled.

10. **`logExportFailureOnce` is operator-observable; changing it needs SRE coordination.** The plan's migration decisions table says the one-shot log throttle "can transition to rate-limited/structured logging." This is not a neutral cleanup — it changes the number and format of WARN log entries observable by SRE monitoring. It requires advance coordination, not an autonomous refactoring choice.

---

## Key Evidence Facts

All facts below are directly verified from the source file (`PlatformDropOldestExportSpanProcessor.java`, ~575 lines):

- **Single shared lock:** `queue` (line 40, `ArrayDeque<SpanData>`) and `pendingFlushes` (line 56, `List<CompletableResultCode>`) are protected by the **same** `queueLock` (line 41, `ReentrantLock`, non-fair). They cannot be separated without introducing a new dual-lock protocol.
- **`exporter.shutdown()` has no timeout** (current code, lines 166–182): `exporter.shutdown()` is called without any timeout bound. If it hangs, the terminator thread leaks and `shutdownResult` (line 45) is never completed. Adding a timeout in PR-3 is an observable behavioral change.
- **`shutdownResult` completed via `exporter.shutdown().whenComplete()`** (lines 176–182): the completion of the processor's shutdown promise is chained to the exporter's shutdown result. Changing this chain changes the observable completion semantics.
- **`logExportFailureOnce` is `AtomicBoolean` one-shot** (line 54, `exportFailureLogged`): `compareAndSet(false, true)` at lines 386–389. Exactly one WARN emitted per processor instance lifetime. Not formally documented as a contract but operator-observable via monitoring.
- **No repository ADR formally approves V6-clean-lite decomposition.** The plan references an ADR draft in §12 but it has "Status: Proposed." The plan itself says "Статус: Proposed (для staff/principal review)."
- **PR-2 "temporarily keep legacy getters" contradicts "backward compat not required":** plan §6 PR-2 says "facade в PR-2 **может временно сохранить** старые getter-методы." Plan §1 says "Backward compatibility … **не является hard constraint**." These are irreconcilable without an explicit design decision on bridge strategy.
- **Existing test for post-shutdown forceFlush:** `PlatformDropOldestExportSpanProcessorLifecycleTest` has `forceFlushDrainsQueueAndCompletesSuccessfully`, `shutdownIsIdempotent`, `shutdownDrainsQueue`, `onEndAfterShutdownIncrementsAfterShutdownCounter`, `exportTimeoutIncrementsCounter`, `exporterExceptionIsolatedAndCounted`. No test for concurrent forceFlush + shutdown (M1), multiple concurrent forceFlush (M2), or exporter.shutdown() hang (M3/M4).

---

## Unresolved Design Decisions

1. **Is bounded shutdown a semantic or refactoring change?** If `exporter.shutdown()` currently has no timeout and PR-3 adds one, does the bounded shutdown constitute an observable semantic change requiring an ADR decision? What is the specific observable delta that distinguishes "semantic change" from "implementation detail"?

2. **Is forceFlush-during-SHUTTING_DOWN joining drain a semantic change?** Currently, a `forceFlush()` called while shutdown is in progress may never complete (R3). The PR-3 plan says "forceFlush during SHUTTING_DOWN — join shutdown drain; fail only if cannot be included." This changes the observable completion behavior for `forceFlush()` callers. Is this a semantic change requiring an ADR?

3. **What is the correct JMX schema migration path?** The plan proposes redesigning the JMX attribute schema (new names derived from `MetricsSnapshot`). The old six getters become the new snapshot fields (same semantics, potentially same names). What is the minimum acceptable migration path: immediate break, parallel schema with deprecation window, or operator notification + new documentation?

4. **Is the PR-2 legacy getter bridge an explicit migration or a contradiction?** If the plan intends PR-2 to be a migration bridge (preserve getters temporarily for test compatibility), that is a valid engineering choice. If it is accidental backward-compatibility preservation that contradicts the breaking-changes stance, it signals an unresolved design decision. Which is it?

5. **If M1/M2 reveal an unfixable race in PR-0, do PR-1/PR-2 proceed?** The plan says yes: "PR-1/PR-2 могут стартовать параллельно." But if the root cause of M1/M2 failure turns out to require architectural changes in PR-2 (e.g., the `ExportBuffer.awaitWork()` design), does it make sense to proceed with PR-1 (low-risk components) while the PR-2 design is blocked on the race resolution?

---

## 7 Questions for Perplexity Deep Research

**Question 1: Architecture certainty framing**
Should V6-clean-lite be described as "target architecture," "candidate target architecture subject to validation," or "architecture hypothesis"? What criteria distinguish these three framings in rigorous architecture review practice? At what point in a PR-sequence-driven refactoring does a candidate architecture earn the status of confirmed target architecture?

**Question 2: Pre-production breaking changes — internal vs operator-facing**
How should an architecture plan phrase "pre-production mode allows breaking changes" in a way that clearly distinguishes: (a) internal implementation details freely changeable; (b) operator-facing contracts (`Builder` API consumed by factory, JMX schema consumed by operators) that require migration coordination regardless of pre-production status? What explicit language prevents engineers from interpreting "backward compat not required" as blanket permission to break operator-facing contracts?

**Question 3: JMX schema migration path**
For a monitoring MBean schema redesign (attribute name changes) in a component that is pre-production but already consumed by operator dashboards: what is the minimum acceptable migration path? Options: (a) immediate break with documentation; (b) parallel old/new schema with deprecation window; (c) operator notification + new documentation before activation. What do OpenTelemetry SDK conventions and JMX best practices recommend?

**Question 4: Factory wiring release gates**
What are the minimum hard release gates for a factory wiring change that implements: opt-in activation via environment variable, multi-exporter fallback to stock implementation, rollback-by-unsetting-env-var semantics, and a no-double-export guarantee? Which of these must have explicit automated tests (not just review assertions) before the factory PR merges?

**Question 5: Observable semantic change threshold for shutdown behavior**
When does changing shutdown timeout behavior and forceFlush completion guarantees cross from "implementation detail" to "observable semantic change requiring an ADR"? Specifically: the current implementation has no timeout on `exporter.shutdown()`; adding a bounded timeout changes what `shutdown().join()` callers observe (it now completes within a deadline). What is the specific observable delta that makes this a semantic change? What is the minimum ADR content required to document it?

**Question 6: PR sequencing for structural vs semantic changes**
How should a PR sequence be structured so that structural PRs (new class boundaries, lock ownership migration) and semantic PRs (lifecycle correctness guarantees) are reviewed as an architectural batch, not as independent incremental changes? What is the risk of merging structural PRs (PR-2) while semantic PRs (PR-3) are deferred, and how do architecture review processes handle this dependency?

**Question 7: Language of epistemic humility in architecture plans**
Which specific phrases in an architecture plan signal overconfident certainty about design hypotheses? Examples from this plan: "This is the target architecture, not a conditional future option," "backward compatibility is not a hard constraint," "JMX schema may be redesigned." What replacement wording conveys appropriate epistemic humility (acknowledging validation dependencies) while remaining actionable for engineers implementing the plan?

---

## Closing Directive

Evaluate all 10 principal architect objections listed above. Apply redlines to the plan summary. Provide specific replacement wording for the overconfident phrases identified in Questions 1 and 7. Focus your response on four areas:

1. **V6-clean-lite certainty level:** what framing is correct for a plan that is "proposed" but asserts a confirmed target architecture?
2. **Internal vs operator-facing contract migration:** how to distinguish and phrase the two categories of breaking changes in the plan?
3. **ADR requirements for observable semantic changes:** which changes in PR-3 (bounded shutdown, deterministic forceFlush) require an ADR before merging, and what must the ADR contain?
4. **Minimum release gates for PR-3 and PR-4:** which tests must be active and green (not `@Disabled`) before each PR merges?
