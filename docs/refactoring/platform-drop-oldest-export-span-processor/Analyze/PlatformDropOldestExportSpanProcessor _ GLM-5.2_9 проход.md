<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

## Critical

- **`P08` overcommits to V5 as the “recommended target architecture.”** The repository evidence says V5 is a *low-risk extraction bundle* and the decomposition doc explicitly marks decomposition as analysis only, not endorsed; that is weaker than a target-architecture approval.[^1][^2]
- **`P08` converts “Phase 2 hardening” into a concrete plan to make `shutdown()` succeed even if `exporter.shutdown()` times out.** The current code and evidence say `shutdownResult` completes from `exporter.shutdown().whenComplete(...)`; changing timeout handling to force success is a semantic change, not just a fix.[^3][^4]
- **`P08` treats `workerCrashes` as a recommended observability addition without repository backing.** The repository defines four AtomicLong counters and six getters; adding a new counter is possible but not evidenced as required.[^5][^3]
- **`P08` frames V6 as a valid long-term target, but the repo never endorses full decomposition.** The responsibility decomposition file only assigns risk and extraction order; it does not recommend full C1–C8 split as a target state.[^2]
- **`P08` implies a “one-size-fits-all” extraction order across C6/C3/C7/C8.** The repo supports C6 and C3 as low-risk, but C7 and C8 are lower-value helpers, not separately validated architecture milestones.[^2]


## High

- **`P08` misses that `PlatformExportProcessorFactory` is the sole production factory and that multi-exporter fallback behavior is part of the contract.** Any ADR approval should explicitly preserve factory wiring and fallback to stock BSP for multi-exporter paths.[^6][^7][^5]
- **`P08` under-specifies `Builder` shape constraints.** The repo requires `Builder` to remain a `public static final class`; “extract config” must not imply weakening that API surface.[^5][^3]
- **`P08` does not clearly preserve the fast-path `shutdownRequested` / `droppedSpansAfterShutdown` race semantics.** Evidence says the unsynchronized fast-path plus locked double-check is accepted behavior, not something to “clean up” casually.[^4][^3]
- **`P08` does not call out that `pendingFlushes` and queue remain lock-coupled.** The concurrency model explicitly says both are under the same `queueLock`; any plan that suggests independent flush coordination before queue redesign is inconsistent with the repo.[^4][^5]
- **`P08` doesn’t explicitly carry forward the permanent nature of `logExportFailureOnce`.** Refactoring notes should preserve that observable behavior unless SRE signs off on log-volume changes.[^3][^5]


## Medium

- **The benchmark gate threshold in `P08` is arbitrary.** The repo requires “no regression / materially no regression” against baseline; it does not define a `1.20x` threshold.[^7][^5]
- **`P08` treats JMX getter delegation as a refactoring detail, but the repo frames them as wire-contract endpoints.** That distinction matters because delegation is fine, but only if signatures and semantics remain untouched.[^8][^5]
- **`P08` repeats “Phase 0 must happen before anything else” in several places without adding new gating value.** This is consistent, but duplicated emphasis obscures the actual approval blockers: test gaps and benchmark baseline.[^8][^7]
- **`P08` does not explicitly preserve the “null exporter is the only fail-fast case” rule in the ADR decision text.** That is a hard builder contract and should be written into the final decision statement.[^5][^3]


## Low

- **`P08` duplicates the same recommendation in multiple forms: V5 first, C6 first, Phase 1 first.** This is directionally consistent, but the repetition can make the ADR read less decisive than necessary.[^1][^2]
- **`P08` repeats that stock BSP is drop-new and therefore unusable.** Correct, but the same fact appears across several source files; in the ADR it should be stated once and moved on.[^6][^1][^8]
- **`P08` repeats that `exporter.export()` must stay outside the producer lock.** Correct, but this should be a single non-negotiable invariant rather than a recurring bullet in multiple sections.[^3][^5]
- **`P08` mentions `workerCrashes` and `queueAndWorkerLockOwnershipInvariant` as if both are equally necessary.** They are not equally supported by evidence; both are optional additions unless justified by Phase 0 findings.[^7][^2]


## Missing invariants to add

- **Preserve the exact six JMX getter names, return types, and semantics.** This is a hard wire contract, not an implementation preference.[^8][^5]
- **Preserve `Builder.readBspConfigFrom(ConfigProperties)` and BSP key compatibility.** The repo says `otel.bsp.*` keys must continue to work.[^5][^3]
- **Preserve `PlatformExportProcessorFactory.isExplicitUpstream` gating.** Opt-in behavior must remain rollback-friendly.[^6][^5]
- **Preserve `forceFlush()` after shutdown returning `ofSuccess()` immediately.** This is already an established behavioral invariant.[^3][^5]
- **Preserve atomic eviction accounting: `pollFirst()` then `offerLast()` under lock with counter increment in the same critical section.** This is central to drop-oldest correctness.[^5][^3]


## Final correction list for ADR approval

1. Downgrade V6 from “recommended long-term target” to “conditional future option only after Phase 0–2 and a strong justification.”[^1][^2]
2. Remove or qualify the claim that shutdown hardening should force success after `exporter.shutdown()` timeout; define this as a separate semantic decision, not a refactoring detail.[^4][^3]
3. Remove the implicit endorsement of new counters like `workerCrashes` unless required by a test or operational need.[^3][^5]
4. Add explicit preservation statements for factory wiring, opt-in gating, JMX wire contract, Builder shape, and BSP key compatibility.[^6][^8][^5]
5. Replace the arbitrary benchmark threshold with “no material regression vs captured baseline,” because that is what the repo actually requires.[^7][^5]
6. Keep `pendingFlushes` and queue lock-coupling explicitly in scope; do not imply flush coordination can be independently extracted before queue/worker redesign.[^2][^4]
7. Keep `logExportFailureOnce` permanent unless SRE approves a logging-policy change.[^5][^3]
8. Treat `C7` and `C8` as optional cleanup, not as key architecture milestones.[^2]

<div align="center">⁂</div>

[^1]: 07-llm-research-input.md

[^2]: 04-responsibility-decomposition.md

[^3]: 01-current-behavior.md

[^4]: 02-concurrency-model.md

[^5]: 06-refactoring-constraints.md

[^6]: 03-adjacent-code-map.md

[^7]: 05-test-coverage-and-gaps.md

[^8]: 00-executive-summary.md

