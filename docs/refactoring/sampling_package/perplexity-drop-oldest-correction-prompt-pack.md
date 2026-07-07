# Perplexity Correction Prompt Pack: V6-clean-lite Plan Review for `PlatformDropOldestExportSpanProcessor`

Цель этого prompt pack — выжать максимум из Perplexity-моделей для корректировки текущего плана V6-clean-lite после замечаний архитектора и evidence-refresh прохода.

Это НЕ prompt pack для генерации нового “красивого” плана с нуля.  
Это prompt pack для **evidence-grounded correction / redline / ADR governance review**.

---

## 0. Что обязательно прикреплять к каждому запуску

Прикрепить:

1. `drop-oldest_refactoring_plan_262d2bc7.plan.md` — текущий V6-clean-lite план
2. `00-refresh-executive-summary.md`
3. `01-v6-clean-lite-evidence-status.md`
4. `02-contracts-and-migration-boundaries.md`
5. `03-jmx-snapshot-migration-evidence.md`
6. `04-builder-config-factory-wiring-evidence.md`
7. `05-lifecycle-semantics-adr-evidence.md`
8. `06-buffer-lifecycle-state-ownership.md`
9. `07-logging-observability-policy.md`
10. `08-test-and-release-gates-refresh.md`
11. `09-plan-redline-input.md`
12. `10-perplexity-correction-input.md`

Опционально, если Perplexity context позволяет:

13. `PlatformDropOldestExportSpanProcessor.java`
14. `PlatformExportProcessorFactory.java`
15. `PlatformExportControl.java`
16. `PlatformAutoConfigurationCustomizerExportProcessorTest.java`
17. `BspDropOldestNoDoubleExportTest.java`
18. `ADR-drop-oldest-export-processor-v1.md`

---

## 1. Общие правила для всех моделей

В каждый prompt можно вставлять этот блок.

```text
Repository evidence is the source of truth. 
Do not treat previous LLM outputs as proof.
Do not invent repository facts.
If evidence is missing, say "Evidence missing".
Separate:
- repository facts
- inferred risks
- architectural recommendations
- wording/redline proposals

The current goal is not to generate a new architecture from scratch.
The goal is to correct the existing V6-clean-lite plan so that it is acceptable for principal architect review.

Focus especially on:
1. rollback factual error: rollback requires explicit UPSTREAM, not unset env-var, because current default is DROP_OLDEST;
2. V6-clean-lite must be downgraded from confirmed "target architecture" to "candidate target architecture subject to Phase 0–3 validation and ADR approval";
3. bounded shutdown and deterministic forceFlush are observable semantic changes and require ADR-level decision;
4. PR-3 cannot merge while M1/M2 are disabled;
5. PR-4 factory wiring must prove explicit UPSTREAM rollback, opt-in DROP_OLDEST, multi-exporter fallback, no-double-export;
6. JMX schema can change only with documented operator migration path and adapter tests;
7. Builder/JMX/factory are migration surfaces, not arbitrary internal details;
8. ExportBuffer owns queue + pendingFlushes + lock; ProcessorLifecycle must not become a hidden second coordinator;
9. logExportFailureOnce is operator-observable; changing it requires SRE coordination;
10. disabled tests cannot be used to hide unresolved risks.
```

---

# Prompt 1 — Sonar Deep Research / Sonar 2  
## External Governance + Best Practices Validation

**Use when:** нужен source-grounded external validation: ADR practices, JMX migration, OpenTelemetry lifecycle, observable semantic changes.

**Expected output file:** `P01-governance-external-validation.md`

```text
You are Perplexity Sonar Deep Research acting as a source-grounded architecture governance researcher.

Role:
- Principal software architecture reviewer
- Java/OpenTelemetry lifecycle expert
- JMX / operator-contract migration reviewer
- ADR governance reviewer

Task:
Evaluate the current V6-clean-lite plan for `PlatformDropOldestExportSpanProcessor` against architecture governance and industry practices.

Attached evidence:
- current V6-clean-lite plan
- evidence-refresh files 00–10
- source files if attached

Repository facts to honor:
- The rollback claim was found to be factually wrong: current default overflow policy is DROP_OLDEST, so rollback requires explicit UPSTREAM, not unsetting the variable.
- V6-clean-lite is not yet approved by committed ADR.
- JMX processor getters and JMX MBean attributes are different surfaces; `PlatformExportControl` translates processor values to MBean attributes.
- `exporter.shutdown()` has no timeout today; adding timeout is observable behavior change.
- `forceFlush + shutdown` race can leave `CompletableResultCode` incomplete; fixing it is observable behavior change.
- queue and pendingFlushes are protected by one lock and must not be split across owners.

Research questions:
1. How should architecture plans distinguish "target architecture", "candidate target architecture", and "design hypothesis"?
2. What do ADR practices recommend before treating a proposed decomposition as approved architecture?
3. When does a refactoring become an observable semantic change requiring an ADR?
4. What is the minimum migration path for a JMX MBean schema change?
5. How should pre-production breaking changes be governed when operator-facing contracts already exist?
6. What release gates are appropriate for factory wiring with opt-in, fallback, rollback, and no-double-export guarantees?
7. What do OpenTelemetry shutdown / forceFlush contracts imply for bounded shutdown and deterministic forceFlush?

Source requirements:
- Prefer primary sources:
  - OpenTelemetry Java docs/source
  - JDK/JMX documentation
  - architecture decision record guidance from recognized sources
  - JMX best-practice references
- Cite external claims.
- Do not rely on blogs for primary API behavior unless official docs are unavailable.

Output in Russian:

## 1. Executive Verdict
- Is current V6-clean-lite plan approvable as-is?
- What must be changed before principal approval?
- What is the correct architecture certainty wording?

## 2. External Governance Findings
For each topic:
- evidence / source
- implication for this plan
- recommended wording

Topics:
- target vs candidate architecture
- ADR requirement for semantic changes
- JMX schema migration
- pre-production breaking changes
- OpenTelemetry shutdown/forceFlush behavior
- release gates for factory rollback

## 3. Redline Recommendations
Create table:
| Current plan language | Governance problem | Replacement wording | Source/evidence | Severity |

Mandatory rows:
- "target architecture"
- "backward compatibility is not hard constraint"
- "JMX schema may be redesigned"
- "clean lifecycle semantics"
- "rollback by unsetting policy"
- "PR-3 deterministic forceFlush"
- "PR-4 factory wiring"

## 4. Required ADR Sections
Write required sections for:
- V6-clean-lite candidate architecture decision
- bounded shutdown
- deterministic forceFlush
- JMX schema migration
- rollback behavior correction

## 5. Minimum Release Gates
Separate:
- PR-0
- PR-1
- PR-2
- PR-3
- PR-4
- PR-5
- PR-6

For PR-3 and PR-4, list must-pass tests and which tests cannot be disabled.

## 6. Final Correction Checklist
Return a numbered checklist of plan edits.

Be strict, source-grounded, and do not invent repository facts.
```

---

# Prompt 2 — Claude Sonnet 4.6 / Claude Thinking  
## Principal Architect Redline Review

**Use when:** нужен осторожный, инженерно-консервативный redline плана.

**Expected output file:** `P02-principal-architect-redline.md`

```text
You are Claude Sonnet 4.6 acting as a conservative principal Java platform architect.

Task:
Perform a redline review of the current V6-clean-lite refactoring plan for `PlatformDropOldestExportSpanProcessor`.

Attached:
- current plan
- evidence refresh files 00–10
- source files if available

Your stance:
The plan is promising but currently not ready for unconditional approval.
Do not reject the plan wholesale.
Correct it so it becomes acceptable for staff/principal architecture review.

Mandatory corrections to consider:
1. Downgrade "target architecture" → "candidate target architecture subject to Phase 0–3 validation and ADR approval."
2. Fix rollback factual error: unset env-var is NOT rollback if default is DROP_OLDEST. Correct rollback is explicit UPSTREAM.
3. Separate hard constraints from migration decisions.
4. Treat Builder/JMX/factory as migration surfaces.
5. Treat bounded shutdown and deterministic forceFlush as ADR-level semantic changes.
6. Add PR-3 blocker: M1/M2 cannot remain disabled.
7. Add PR-4 blockers: explicit UPSTREAM rollback, opt-in activation, multi-exporter fallback, no-double-export.
8. Specify ExportBuffer/ProcessorLifecycle state ownership protocol.
9. Preserve or explicitly decide `logExportFailureOnce`; do not hide it as cleanup.
10. Disabled tests must not hide risk.

Output in Russian:

## 1. Principal Verdict
- approve / approve with redlines / reject / rewrite?
- exact reason

## 2. Redline by Section
For sections:
- Executive Summary
- Evidence Base
- Non-Negotiable Invariants
- Characterization Tests
- Refactoring Strategy
- Package Layout
- Anti-Overengineering Rules
- PR Plan
- Risk Register
- Migration Decisions
- ADR Draft
- Implementation Prompt

For each:
| Section | Problem | Exact replacement text | Reason | Severity |

## 3. Mandatory Wording Replacements
Write exact replacement paragraphs for:
- architecture certainty
- pre-production breaking changes
- JMX migration
- Builder/factory migration
- rollback semantics
- bounded shutdown ADR
- deterministic forceFlush ADR
- log throttling policy
- disabled test policy

## 4. Corrected PR Gates
Write PR gates for PR-0 through PR-6.
PR-3 and PR-4 must be especially strict.

## 5. Corrected ADR Decision Draft
Write a corrected ADR draft with:
- Status: Proposed
- Decision: candidate V6-clean-lite, not confirmed target
- Semantic decisions separated from structural refactoring
- Rollback correction
- JMX migration path
- Test gates
- Benchmark gates

## 6. Final Recommendation to Architects
Short decision memo.

Do not produce code. Do not invent facts. Use repository evidence.
```

---

# Prompt 3 — GPT-5.4  
## Decision Matrix + Redline Prioritization

**Use when:** нужно строго приоритизировать redlines, разделить critical/high/medium, сделать decision table.

**Expected output file:** `P03-correction-decision-matrix.md`

```text
You are GPT-5.4 acting as a structured architecture decision analyst.

Task:
Build a correction decision matrix for the V6-clean-lite refactoring plan.

Inputs:
- current plan
- evidence-refresh files 00–10

Goal:
Do not rewrite the entire plan. Instead:
1. classify each architect objection;
2. decide whether it is blocking;
3. define exact plan correction;
4. define required evidence/test gate;
5. define owner PR.

Key evidence facts:
- rollback requires explicit UPSTREAM, not unset env-var;
- V6-clean-lite lacks committed ADR approval;
- JMX and Builder are migration surfaces;
- bounded shutdown and deterministic forceFlush are observable semantic changes;
- queue and pendingFlushes share one lock;
- PR-3 cannot merge with M1/M2 disabled;
- PR-4 needs opt-in/fallback/no-double-export/rollback gates;
- logExportFailureOnce is operator-observable.

Output in Russian:

## 1. Executive Decision Matrix
Table:
| Objection | Evidence status | Severity | Blocking? | Correction | Owner PR | Required test/ADR |

## 2. Blocking Corrections
List corrections that must be applied before the plan can be approved.

## 3. Non-Blocking Improvements
List corrections that can wait until PR-1/PR-2.

## 4. Hard Constraints vs Migration Decisions
Create table:
| Surface | Hard constraint? | Migration decision? | Required plan wording | Test/docs gate |

Include:
- Builder API
- readBspConfigFrom
- processor getters
- JMX schema
- factory wiring
- opt-in policy
- multi-exporter fallback
- rollback policy
- logExportFailureOnce
- shutdownResult behavior
- forceFlush behavior

## 5. Corrected PR Gate Matrix
Rows: PR-0 to PR-6.
Columns:
- goals
- allowed changes
- forbidden changes
- must-pass tests
- disabled tests allowed?
- ADR requirement
- rollback requirement

## 6. Plan Language Risk Matrix
Classify current phrases:
- overconfident
- ambiguous
- factually wrong
- acceptable
- strong and should keep

## 7. Final Scored Verdict
Score current plan before correction and after applying recommended corrections.
Use 1–10 categories:
- evidence grounding
- migration safety
- architectural clarity
- overengineering control
- release gate rigor
- semantic-change governance
- operator safety
- rollback correctness

Give final recommendation.
```

---

# Prompt 4 — Gemini 3.1 Pro  
## Broad Migration Strategy + Operator/JMX Review

**Use when:** нужен широкий взгляд на operator migration, JMX schema, runbooks, dashboards, deployment gates.

**Expected output file:** `P04-jmx-operator-migration-review.md`

```text
You are Gemini 3.1 Pro acting as a broad-context observability and platform migration architect.

Task:
Review the V6-clean-lite plan specifically from the perspective of operator-facing migration:
- JMX schema
- dashboards
- runbooks
- factory wiring
- rollback
- migration bridge vs hard break
- pre-production but operationally visible components

Inputs:
- current plan
- evidence refresh files 00–10

Key context:
The team now agrees breaking changes are possible, but architects warned that pre-production is not blanket permission to break operator-facing contracts without migration plan.

Important repository facts:
- Processor getters and JMX MBean attribute names are different surfaces.
- `PlatformExportControl` translates processor getters into JMX attributes.
- There is no formal ADR for the export-counter JMX attribute schema.
- The existing ADR-jmx-wire-map-contract is about a different subsystem.
- Old JMX names may be changed, but only with a documented operator migration path.
- JMX capability and semantic observability must be preserved.

Task:
Produce a migration strategy for JMX + Builder + factory that avoids hidden operational breakage.

Output in Russian:

## 1. Operator Migration Verdict
- Should old JMX names be preserved, dual-exposed, or replaced immediately?
- What is the safest path given pre-production and unknown operator client inventory?

## 2. JMX Migration Options
Compare:
A. preserve old MBean names, snapshot-backed
B. new snapshot-derived schema only
C. dual schema bridge for one migration window

For each:
- pros
- cons
- operator risk
- test requirements
- documentation requirements
- recommended use case

## 3. Recommended JMX Plan
Include:
- exact JMX adapter behavior
- mapping document requirements
- tests
- SRE/operator notification
- whether old names should be kept temporarily

## 4. Builder / Config Migration Plan
- config-first builder
- factory update
- `otel.bsp.*` key compatibility
- invalid config fallback
- null exporter fail-fast
- tests

## 5. Factory / Rollback Plan
Very important:
- corrected rollback: explicit UPSTREAM, not unset env-var, if current default is DROP_OLDEST
- opt-in DROP_OLDEST gate
- explicit UPSTREAM gate
- multi-exporter fallback gate
- no-double-export gate
- no stale JMX registration when UPSTREAM

## 6. Release Checklist for PR-4
Table:
| Gate | Test | Must be automated? | Failure impact |

## 7. Plan Redlines for Operator Safety
Exact replacement language for the plan.

Be broad, practical, and operator-conscious.
```

---

# Prompt 5 — Kimi K2.6  
## Adversarial Race / Release Gate Attack

**Use when:** нужно атаковать план на гонки, loopholes, слабые gates, disabled tests.

**Expected output file:** `P05-adversarial-race-gates-review.md`

```text
You are Kimi K2.6 acting as an adversarial concurrency and release-gate reviewer.

Task:
Try to break the V6-clean-lite correction plan.

Inputs:
- current plan
- evidence refresh files 00–10

Primary attack surfaces:
1. forceFlush + shutdown race
2. queue + pendingFlushes ownership
3. ProcessorLifecycle becoming hidden second coordinator
4. ExportBuffer leaking lock ownership
5. PR-2 structural migration merged before PR-3 semantics fix
6. disabled tests hiding R1/R3
7. PR-4 factory wiring breaks rollback
8. JMX schema migration breaks operators
9. logExportFailureOnce change creates alerting noise or silence
10. class-count guardrail becomes KPI

Output in Russian:

## 1. Adversarial Verdict
- top 10 ways the corrected plan can still fail
- which failures are most likely
- which failures are most dangerous

## 2. Race Scenarios
For each scenario:
- actor threads
- step-by-step sequence
- bad outcome
- which PR can introduce it
- which test should catch it
- release gate required

Mandatory scenarios:
- forceFlush added after shutdown state changes but before worker exit observed
- shutdown drains queue while forceFlush promise is not yet in pendingFlushes
- lifecycle completes shutdownResult while pendingFlushes still exist
- ExportWorker returns ExportWork without all flush requests
- ExportBuffer signal lost
- JMX reads queue size while buffer is in transition
- factory UPSTREAM path still starts worker thread accidentally

## 3. Disabled Test Abuse Cases
List ways a team could misuse `@Disabled` to bypass risk.
Define hard rules that prevent it.

## 4. State Ownership Red Flags
List code smells that must be rejected in review:
- ProcessorLifecycle fields it must not have
- ExportWorker lock access it must not have
- ExportBuffer lifecycle decisions it must not make
- TimedSpanExporter thread/executor it must not create

## 5. PR-3 Release Gate Hardening
Write strict acceptance criteria:
- M1 active green
- M2 active green
- M3 active green if bounded exporter.shutdown is claimed
- M6 active green
- no incomplete CompletableResultCode under stress
- bounded timeout tests cannot hang

## 6. PR-4 Release Gate Hardening
Write strict acceptance criteria:
- explicit UPSTREAM rollback
- DROP_OLDEST activation
- multi-exporter fallback to BatchSpanProcessor
- no double export
- no stale JMX
- JMX adapter fields verified

## 7. Final Redline Recommendations
Exact plan text changes.

Be skeptical. Assume engineers under schedule pressure will exploit ambiguous wording.
```

---

# Prompt 6 — Nemotron 3 Ultra  
## Systems / Performance / Simplicity Review

**Use when:** нужно проверить clean-lite против overengineering и performance risks.

**Expected output file:** `P06-systems-performance-clean-lite-review.md`

```text
You are Nemotron 3 Ultra acting as a Java systems performance and simplicity reviewer.

Task:
Evaluate the corrected V6-clean-lite plan for:
- overengineering risk
- hot-path performance risk
- class boundary usefulness
- benchmark gates
- stress validation

Inputs:
- current plan
- evidence refresh files 00–10

Key plan elements:
- V6-clean-lite targets ~7–8 production classes, not mini-framework
- no speculative Strategy/Runtime/Classifier/Service/Coordinator
- nested records/private methods for local value objects
- ExportBuffer owns queue + pendingFlushes + lock
- TimedSpanExporter must not create executor/thread
- onEnd hot path must remain non-blocking and low overhead
- JMH baseline exists / PR-0 captures it

Output in Russian:

## 1. Systems Verdict
Is V6-clean-lite lean enough?
Where does overengineering risk remain?

## 2. Hot Path Review
Analyze:
- current onEnd path
- new facade → lifecycle → buffer path
- allocation risk
- lock contention
- snapshot metrics impact
- JMX read impact

## 3. Component Boundary Review
For each planned production class:
- justified?
- responsibility removed from old processor?
- testable?
- could be nested/private instead?

Classes:
- PlatformDropOldestExportSpanProcessor
- DropOldestExportProcessorConfig
- ExportBuffer
- ExportWorker
- TimedSpanExporter
- ProcessorLifecycle
- DropOldestProcessorMetrics
- DropOldestProcessorMetricsSnapshot

## 4. Anti-Overengineering Review
Evaluate §7a rules. Add missing rules if needed.
Identify any rules that could become harmful KPIs.

## 5. Benchmark Gate Review
Evaluate:
- JMH steady onEnd
- saturated eviction
- exporter blocked
- forceFlush pressure
- shutdown drain
- stress mixed load

Define:
- must-have benchmarks
- nice-to-have benchmarks
- unnecessary benchmarks

## 6. Performance Release Gates
For PR-1, PR-2, PR-3, PR-6:
- what must be measured
- what must not regress
- what can be reviewed manually

## 7. Final Plan Corrections
Exact text replacements to avoid overengineering and performance ambiguity.

Be pragmatic. Do not propose Disruptor, virtual threads, or extra dependencies unless overwhelmingly justified.
```

---

# Prompt 7 — Sonar Pro / Fact-Check  
## Repository Evidence Consistency QA

**Use when:** нужно проверить factual correctness всех утверждений перед финальной правкой плана.

**Expected output file:** `P07-fact-check-correction-qa.md`

```text
You are Sonar Pro acting as a strict fact-checker.

Task:
Validate the facts in the V6-clean-lite plan and evidence-refresh dossier. Your goal is to catch factual errors before the plan is corrected.

Inputs:
- current plan
- evidence-refresh files 00–10
- source files if attached

Do NOT propose broad architecture changes.
Focus only on factual correctness.

Check these claims:
1. rollback requires explicit UPSTREAM, not unset env-var;
2. current default policy is DROP_OLDEST;
3. ADR-jmx-wire-map-contract does not cover export processor JMX attributes;
4. processor getter names differ from JMX MBean method names;
5. factory sole production caller of builder;
6. multi-exporter fallback tests are weak;
7. exporter.shutdown has no timeout;
8. forceFlush race window exists;
9. queue and pendingFlushes share same lock;
10. logExportFailureOnce is one-shot;
11. PR-4 must preserve opt-in/fallback/no-double-export;
12. no committed ADR approves V6-clean-lite.

Output in Russian:

## 1. Fact-Check Summary
Table:
| Claim | Status: confirmed / partially confirmed / incorrect / unsupported | Evidence | Correction |

## 2. Critical Factual Errors
List errors that must be fixed before Perplexity correction can be trusted.

## 3. Unsupported Claims
List claims that should be downgraded or removed.

## 4. Evidence-Backed Claims
List claims safe to keep.

## 5. Corrections to Redline Table
Review `09-plan-redline-input.md`.
Add/remove/change redlines if factual evidence requires.

## 6. Final QA Verdict
Can the correction pack be used to modify the plan?
- yes
- yes with corrections
- no

Do not hallucinate. Use only attached evidence or primary public docs if needed.
```

---

# Prompt 8 — Final Synthesis / Claude Sonnet 4.6 or GPT-5.4  
## Corrected Plan Redline + Final ADR Wording

**Use when:** есть outputs P01–P07. Это финальная консолидация. Если Model Council недоступен, запускать на Claude Sonnet 4.6; потом проверить GPT-5.4.

**Expected output file:** `P08-final-correction-redline-and-adr.md`

```text
You are the final architecture correction synthesizer.

Preferred model:
Claude Sonnet 4.6 for main synthesis.
GPT-5.4 for follow-up critique if available.

Inputs:
- current V6-clean-lite plan
- evidence-refresh files 00–10
- outputs P01–P07

Task:
Produce the final redline and corrected wording for the plan.

Do not create a new plan from scratch.
Do not reject V6-clean-lite wholesale unless evidence requires it.
Correct the plan so it can be accepted by principal architects.

Required final stance:
- V6-clean-lite is a candidate target architecture, not a confirmed target, until Phase 0–3 validation and ADR approval.
- Breaking changes are allowed for internal implementation details, but operator-facing and factory surfaces require explicit migration plan.
- Rollback correction: explicit UPSTREAM, not unset env-var, if default is DROP_OLDEST.
- Bounded shutdown and deterministic forceFlush are ADR-level semantic changes.
- PR-3 cannot merge while M1/M2 are disabled.
- PR-4 must prove opt-in, explicit UPSTREAM rollback, multi-exporter fallback, no-double-export, and JMX adapter semantics.
- JMX schema redesign requires documented operator migration path.
- logExportFailureOnce change requires SRE coordination.
- ExportBuffer owns queue + pendingFlushes + lock; ProcessorLifecycle owns lifecycle state; no hidden second coordinator.
- Anti-overengineering rules remain.

Output in Russian:

## 1. Final Corrected Verdict
- approved as-is / approved with redlines / downgrade / rewrite
- exact status wording

## 2. Redline Patch Table
For every high/critical phrase:
| Section | Current phrase | Replacement phrase | Why | Evidence |

Mandatory replacement phrases:
- target architecture
- backward compatibility not hard constraint
- rollback by unset env-var
- JMX schema may be redesigned
- clean lifecycle semantics
- PR-3 gates
- PR-4 gates
- log throttle
- disabled tests
- class count

## 3. Corrected Executive Summary
Write the corrected Executive Summary section.

## 4. Corrected Non-Negotiable Invariants Section
Write the corrected section with:
- hard constraints
- migration decisions
- semantic ADR decisions
- operator migration surfaces

## 5. Corrected PR Plan
Write corrected PR-0 through PR-6 with precise gates.

## 6. Corrected ADR Draft
Write ADR draft:
- Status: Proposed
- Decision: Candidate V6-clean-lite
- ADR-level semantic changes
- rollback correction
- JMX migration path
- PR gates
- Benchmark gates
- Alternatives considered

## 7. Final Implementation Prompt for Cursor Composer
Write corrected PR-0 prompt only.
It must include:
- no production changes
- no assertion weakening
- disabled test policy
- bounded timeout for hang tests
- M1/M2/M3/M6 handling
- no factory/JMX changes in PR-0

## 8. Follow-Up Checklist
List exact next steps:
1. update plan
2. create ADR
3. run PR-0
4. send PR-0 results to architects
5. only then proceed PR-1

Be decisive but evidence-grounded.
```

---

## 9. Recommended Execution Order

1. **P07 Sonar Pro fact-check first**  
   Проверить, что correction dossier не содержит новых ошибок.

2. **P01 Sonar Deep Research**  
   Внешние governance/source-backed findings.

3. **P02 Claude Sonnet redline**  
   Основной principal architecture redline.

4. **P03 GPT-5.4 matrix**  
   Приоритизация corrections и release gates.

5. **P04 Gemini JMX/operator migration**  
   Отдельно проверить JMX/Builder/factory migration.

6. **P05 Kimi adversarial**  
   Атаковать PR gates, disabled tests, races.

7. **P06 Nemotron systems/performance**  
   Проверить clean-lite и anti-overengineering.

8. **P08 Claude Sonnet final synthesis**  
   Финальный redline + ADR wording.

9. Optional final follow-up on **GPT-5.4**:
```text
Review the final corrected plan from P08 as a skeptical principal architect.
Find unsupported claims, weak gates, ambiguous migration language, and any remaining contradiction with the evidence-refresh dossier.
Return only blocking and high-severity corrections.
```

---

## 10. Минимальный expected outcome после серии запусков

После этих запусков должны быть:

1. Исправленная формулировка статуса V6-clean-lite.
2. Исправленная rollback-semantics.
3. Чёткое разделение internal breaking changes vs operator/factory migration surfaces.
4. JMX migration path.
5. ADR wording для bounded shutdown / deterministic forceFlush.
6. PR-3 hard blockers.
7. PR-4 factory/rollback/JMX gates.
8. Policy для disabled tests.
9. Policy для logExportFailureOnce.
10. Redline-ready patch для текущего плана.
