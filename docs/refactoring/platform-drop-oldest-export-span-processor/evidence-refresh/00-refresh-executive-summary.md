# 00 — Correction Dossier: Refresh Executive Summary

> **Evidence-first.** All claims cite a repository file and line, or are marked `Evidence missing`.
> Investigation date: 2026-06-30.
> Sources read: plan file, dossier files 00–03/06, all production Java source, key test files, ADR-drop-oldest-export-processor-v1.md, ADR-jmx-wire-map-contract.md.

---

## Overall Verdict

**NEEDS CORRECTION** — The V6-clean-lite plan is well-reasoned in its decomposition intent and the identified risks are real (R1–R4 confirmed in source). However, the plan overstates certainty in five specific areas, contains one outright factual error about rollback behavior, and treats two semantic changes as "clean refactoring" without requiring an ADR for them.

---

## Architect Objections: Evidence Status Table

| # | Objection | Evidence Status | Impact | Required Correction |
|---|-----------|-----------------|--------|---------------------|
| 1 | "Target architecture" stated without ADR proof | **PARTIALLY SUPPORTED** — plan §12 has a draft ADR inline, but no committed `ADR-drop-oldest-export-processor-v6-clean-lite.md` file exists in `docs/decisions/`. The existing `ADR-drop-oldest-export-processor-v1.md` (status: Accepted) covers v1 design only and does not authorize V6-clean-lite. | High — calling it "target architecture" before ADR approval treats a proposal as a decision. | Promote §12 draft to a committed ADR file. Status must be "Proposed" until architecture committee approves. Wording in plan §1 must change to "candidate architecture subject to ADR approval." |
| 2 | JMX/Builder treated as freely breakable | **PARTIALLY SUPPORTED** — `06-refactoring-constraints.md` lists all six getter signatures and all Builder methods as **hard constraints** (explicit table). Plan overrides these by invoking "pre-production mode." The referenced `ADR-jmx-wire-map-contract.md` is about a **different** JMX subsystem (Map-based wire protocol for cross-classloader control), NOT about the six export processor getter-derived JMX attributes. No ADR explicitly authorizes breaking these getter names. | High — prior hard constraints exist; override requires explicit supersession, not assumption. | Either (a) explicitly supersede `06-refactoring-constraints.md` constraints in the new ADR, or (b) preserve getter signatures in the facade through PR-5. Plan must cite the mechanism by which prior hard constraints are invalidated. |
| 3 | Factory wiring rollback gate missing | **SUPPORTED** — plan says rollback = "unset env-var" (plan §12 Rollback, ADR-v1 §Rollback). **Critical factual error:** `ExtensionDefaults.java:48` shows `DEFAULT_QUEUE_OVERFLOW_POLICY = "DROP_OLDEST"`. Test `PlatformAutoConfigurationCustomizerExportProcessorTest.defaultIsPlatformDropOldestWhenOverflowPolicyNotSet()` confirms: unset policy activates DROP_OLDEST (not UPSTREAM). Rollback now requires explicitly setting `UPSTREAM`, not just unsetting. | Critical — rollback claim in plan and ADR-v1 is factually wrong for current codebase. | Correct rollback documentation: "Set `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=UPSTREAM` explicitly (unset restores DROP_OLDEST default, not stock BSP)." Update ADR-v1 which still says "v1.x default = UPSTREAM." Post-PR-4, must verify that factory compiles and explicit UPSTREAM still routes to stock BSP. |
| 4 | Backward compat + temp bridge = contradiction | **PARTIALLY SUPPORTED** — PR-2 says facade "may temporarily preserve old getter methods delegating to metrics.snapshot()" removed in PR-5. This is internally consistent but the plan simultaneously says "backward compatibility for Builder/JMX is not a hard constraint" AND introduces a migration bridge. The contradiction is in terminology, not design. | Low — primarily a documentation/communication clarity issue. | Rename "backward compat" in PR-2 to "migration bridge (removed in PR-5)." Explicitly state bridge lifetime = PR-2 through PR-4, removed in PR-5. |
| 5 | Bounded shutdown = semantic change needing ADR | **SUPPORTED** — current `shutdown()` calls `exporter.shutdown()` at line 168 with **no timeout** (`PlatformDropOldestExportSpanProcessor.java:168`). `shutdownResult` completes only via `exporterShutdown.whenComplete(...)` (line 176). If exporter never completes, `shutdownResult` is permanently incomplete. PR-3 proposes to add a bounded timeout here — this changes observable behavior for any caller awaiting `shutdown().join()`. Plan §8 PR-3 acceptance criteria say "semantic change documented in ADR" but no such ADR exists. | High — behavior change must be in an ADR. | Require an explicit ADR decision (or PR-3 ADR amendment) approving bounded exporter.shutdown() timeout. Cannot be labeled "clean refactoring." |
| 6 | ExportBuffer vs ProcessorLifecycle dual ownership risk | **PARTIALLY SUPPORTED** — flush promises live in ExportBuffer (under queueLock), lifecycle state lives in ProcessorLifecycle. The R3 race (confirmed: see `02-concurrency-model.md` §Pending forceFlush) requires these two classes to interlock correctly. No concrete interlock protocol specified in plan. Plan says "ProcessorLifecycle owns public operations" but doesn't specify the API between ProcessorLifecycle and ExportBuffer for the SHUTTING_DOWN → pendingFlushes drain transition. | Medium — dual state machine risk is real but plan acknowledges lock ownership principle; needs concrete API spec. | Plan must specify the API/protocol: which component owns pendingFlushes drain during SHUTTING_DOWN? Document the exact call sequence in §7 component responsibilities. |
| 7 | ForceFlush/shutdown race not a strict gate | **SUPPORTED** — R3 confirmed: `forceFlush()` adds to `pendingFlushes` between two lock acquisitions (lines 119-124); if worker drains between forceFlush's shutdownRequested read (line 114) and its pendingFlushes.add (line 121), promise may not be completed (`02-concurrency-model.md` §Pending forceFlush). Plan says PR-3 must fix M1/M2 but doesn't specify the exact synchronization primitive closing this race. | High — plan says "M1/M2 green at end of PR-3" but the mechanism to guarantee this is not specified. | Plan must specify the protocol change: either (a) forceFlush() must hold lock through shutdownRequested check AND pendingFlushes.add, or (b) shutdownResult-completion triggers completion of all outstanding pendingFlushes. This must be a PR-3 implementation requirement, not just an acceptance criterion. |
| 8 | logExportFailureOnce observable status unclear | **SUPPORTED** — current implementation: `AtomicBoolean exportFailureLogged` set once, never reset (lines 385–390). After first failure, all subsequent failures produce no log. Plan says "can become rate-limited/structured" — optional wording with no commitment. SRE relying on "one WARN in startup logs" pattern sees different observable behavior if this changes. | Low–Medium — optional wording creates ambiguity for SRE monitoring contracts. | If log throttle policy changes: (a) it must be explicitly listed as a semantic change, (b) ADR note or PR-3/4 acceptance criteria must document the new observable policy. Cannot remain "can" if the change is actually made. |
| 9 | Disabled tests hiding risk | **PARTIALLY SUPPORTED** — Plan correctly handles M3 (R1 known failure) as `@Disabled` with ADR ref. M1 (R3 — concurrent forceFlush+shutdown) has no existing test; plan says M1 is a new test in PR-0. Current `PlatformDropOldestExportSpanProcessorLifecycleTest.java` has 5 tests but none for concurrent forceFlush+shutdown race or for exporter.shutdown() hang. Tests `M1–M10` from plan §5 are not yet written. `Evidence missing` for whether M1/M2 will pass or require @Disabled. | Medium — if M1 reveals R3 is present (and it is, based on code analysis), PR-3 gates depend on fixing this first. | PR-0 must be completed before labeling plan phases. After PR-0, Phase 0 Failure Decision Policy must be applied: if M1 is @Disabled (known failure), PR-3 must be its explicit fix. |
| 10 | Factory opt-in/multi-exporter fallback under-specified | **SUPPORTED** — multi-exporter detection: `exporterCount.get() > 1` (line 61 `PlatformExportProcessorFactory.java`). Counter incremented in `captureExporter()` per SPI call. After PR-4 changes the factory, the detection mechanism must be preserved exactly. `PlatformAutoConfigurationCustomizerExportProcessorTest.dropOldestWithTwoExportersBeforePlatformFallsBack()` test contains a comment: "В обоих случаях процессор должен быть валидным" — the assertion is weak (non-null). | Medium — multi-exporter fallback is not strongly asserted in existing tests; PR-4 mandatory gate (explicit multi-exporter → stock BSP assertion) is required. | PR-4 must add explicit multi-exporter test that asserts `BatchSpanProcessor` (not PlatformDropOldestExportSpanProcessor) is returned when two exporters are registered before the platform customizer. |

---

## Critical Factual Error (Must Fix Before Perplexity Correction)

**Rollback claim is wrong.** Plan §12 and ADR-v1 both say rollback = "unset env-var → restores stock BSP."

Evidence against:
- `ExtensionDefaults.java:48`: `DEFAULT_QUEUE_OVERFLOW_POLICY = "DROP_OLDEST"` 
- `PlatformAutoConfigurationCustomizerExportProcessorTest.defaultIsPlatformDropOldestWhenOverflowPolicyNotSet()`: confirms unset policy = DROP_OLDEST active
- ADR-v1 §Opt-in table says "v1.x default = Stock BSP (upstream)" — this is OUTDATED; the default has since changed to DROP_OLDEST

**Correct rollback:** Operator must explicitly set `platform.tracing.queue.overflow-policy=UPSTREAM` (or env `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=UPSTREAM`).

---

## What Must Change Before Perplexity Correction

1. Fix rollback documentation factual error (affects plan §12, ADR-v1 rollback section, §6 rollback strategy)
2. Promote plan §12 draft ADR to a committed `ADR-drop-oldest-export-processor-v6-clean-lite.md` with status "Proposed" 
3. Explicitly state bounded shutdown and deterministic forceFlush as **ADR-level decisions**, not refactoring choices
4. Clarify ADR-jmx-wire-map-contract.md is about a different subsystem; identify the correct authority for the 6 export-processor JMX attributes
5. Specify the concrete interlock protocol between ExportBuffer and ProcessorLifecycle for the SHUTTING_DOWN state
6. Upgrade multi-exporter fallback test from "non-null" assertion to explicit type assertion

---

## Recommended Next Step

1. **Immediate:** Correct factual rollback error in plan and ADR-v1.
2. **Before PR-0:** Commit ADR with status "Proposed" covering V6-clean-lite, bounded shutdown, and deterministic forceFlush as separate decisions.
3. **PR-0 output:** Run M1–M10, classify each as PASS / @Disabled known-failure / @Disabled pending-design; apply Phase 0 Failure Decision Policy from plan §5.
4. **Architecture review gate:** Submit the committed ADR + PR-0 results to architecture committee before starting PR-1.
