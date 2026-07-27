# Architecture Multi-Criteria Scoring
## Corrected Repository-Grounded Decision Analysis

## Review status

| Field | Value |
|---|---|
| Report status | **PASS_WITH_CRITICAL_METHODOLOGY_CORRECTIONS** |
| State finalists scored | **F1 RETAIN_GENERIC**, **F2 REFACTOR_DOMAIN_SPECIFIC** |
| Narrow Category A | **Sensitivity overlay, not a third architecture** |
| Weighted winner | **F1 — Generic Aggregate Accepted State** |
| Runner-up | **F2 — Domain-Specific Aggregate State** |
| Implementation GO | **NOT GRANTED** |
| Legacy deletion GO | **BLOCKED** |
| Intended handoff | Prompt 12 — Adversarial Architecture Falsification |

The original scoring reached the likely correct winner but used an invalid methodology.

Critical defects:

1. It treated F3 Narrow Category A as an independent architecture, although corrected Prompt 3 defined it as an orthogonal product-scope decision applicable to F1 or F2.
2. It mapped the requested weights incorrectly:
   - Config Server feasibility was assigned the highest weight;
   - ownership coherence and failure safety were omitted;
   - dev debugging and complexity were elevated to high weight;
   - deletion, maintainability and 80-service rollout were underweighted.
3. It gave `no-new-module` zero weight instead of treating violation as a hard disqualifier.
4. It did not actually apply the requested negative penalties.
5. It scored unproven gates as if they were implemented facts.
6. It used false numerical precision for sensitivity, worst-case and regret without a defined model.
7. It claimed to rely on all prior analyses while explicitly saying only selected documents were sufficient.
8. It cited obsolete assumptions:
   - aggregate writer on an existing platform MBeanServer;
   - Agent policy types in `platform-tracing-otel`;
   - no private-writer isolation requirement.
9. It rewarded F2 for “better diagnostics” without repository evidence; diagnostics are deliberately separate from the accepted-state primitive.
10. It penalized F3 as if narrow runtime scope changed the state architecture.
11. It proposed a “sealed record”; Java records are final and cannot be declared sealed.
12. It proposed one static codec, although the accepted classloader design requires symmetric application-side and Agent-side codecs around one Open-Type contract.
13. It used arbitrary reversal thresholds such as a 5% latency regression without an approved performance budget.
14. It claimed a net-negative architecture overhead without measured repository diff evidence.

This corrected report uses hard gates, transparent weights, evidence confidence and separate sensitivity for runtime mutation breadth.

---

# 1. Candidates being scored

## F1 — Generic Aggregate Accepted State

```text
VersionedStateHolder<AcceptedTracingPolicyState>
```

Binding characteristics:

- generic holder remains in `platform-tracing-otel`;
- one Agent-specific immutable accepted-state value lives in the Agent extension;
- one Agent-side accepted-state owner;
- current per-domain mutable holders are deleted after reader cutover;
- one private process-local aggregate writer;
- platform JMX becomes read-only;
- diagnostics, drift and audit remain outside accepted policy CAS.

## F2 — Domain-Specific Aggregate State

```text
VersionedTracingPolicyState
```

Binding characteristics:

- one Agent-side domain-specific state component;
- may compose the generic holder internally;
- must not duplicate generic CAS/version semantics;
- same private writer, adapters, diagnostics, namespaces and cleanup target as F1.

## Runtime breadth overlay

The following is **not** a third state architecture:

```text
Broad Category A
versus
Narrow Category A
```

It can be applied to either F1 or F2.

The final runtime allowlist remains blocked by:

- exact property/env disposition;
- SRE approval;
- security constraints;
- runtime-safety and performance evidence.

---

# 2. Correct scoring methodology

## 2.1 Numbered criteria weights

The numbered 20 criteria are weighted as follows.

| # | Criterion | Weight | Rationale |
|---:|---|---:|---|
| 1 | Correctness | 5 | Highest |
| 2 | Agent lifecycle fit | 5 | Highest |
| 3 | Classloader fit | 5 | Highest |
| 4 | Atomicity | 5 | Highest |
| 5 | Config Server feasibility | 2 | Implementation feasibility |
| 6 | Dev debugging | 1 | Useful, not production-defining |
| 7 | SRE operations | 3 | High |
| 8 | Security | 3 | High |
| 9 | Current supported topology fit | 3 | High |
| 10 | Complexity | 2 | Implementation/maintenance cost |
| 11 | Code deletion | 3 | High |
| 12 | Net code cost | 2 | Medium |
| 13 | Testability | 2 | Medium |
| 14 | Failure diagnostics | 2 | Important, but separate from failure safety |
| 15 | Public API risk | 2 | Negative-risk criterion; higher is safer |
| 16 | Speculative generality risk | 2 | Negative-risk criterion; higher is safer |
| 17 | 80-service rollout | 3 | High |
| 18 | No-new-module fit | Hard gate | Violation disqualifies |
| 19 | Breaking-change leverage | 1 | Compatibility itself has zero weight |
| 20 | Long-term maintainability | 3 | High |

## 2.2 Required dimensions missing from the numbered list

The prompt names two highest-priority concerns not represented explicitly in its numbered criteria.

They are added as derived dimensions:

| Derived dimension | Weight |
|---|---:|
| Ownership coherence | 5 |
| Failure safety | 5 |

## 2.3 Backward compatibility

Backward compatibility receives:

```text
weight = 0
```

No candidate receives points for preserving old properties, APIs or JMX write operations.

Criterion 19 rewards effective use of the allowed breaking cutover; it does not reward compatibility.

## 2.4 Hard disqualifiers

A candidate is removed from ranking if it introduces:

- a new Gradle module;
- unsupported Local SDK production parity;
- public control-plane DTOs;
- multiple accepted-state owners;
- a writer on the platform `MBeanServer`;
- long-lived old and new control paths;
- aggregate fan-out through domain setters;
- an unproven runtime Category A treated as binding;
- inability to delete legacy writers;
- a generic policy/configuration/security framework.

These are not compensated by a high numeric score.

## 2.5 Evidence confidence

Scores describe architectural fit under stated assumptions.

Open proof gates reduce confidence but are not silently treated as implemented facts.

Key assumptions:

- private process-local MBeanServer is feasible;
- exact `VersionedStateHolder` API supports F1 without tracing-specific pollution;
- final Category A is operationally sufficient;
- production Config Server dependency and refresh delivery are approved.

Failure of a hard assumption invokes a reversal or NO-GO rather than an arbitrary score subtraction.

---

# 3. Corrected scoring matrix

Scale:

```text
0 = incompatible
5 = viable with major drawbacks
10 = strongly evidenced and minimal-risk
```

| # | Criterion | Weight | F1 | F2 |
|---:|---|---:|---:|---:|
| 1 | Correctness | 5 | 8 | 8 |
| 2 | Agent lifecycle fit | 5 | 9 | 9 |
| 3 | Classloader fit | 5 | 8 | 8 |
| 4 | Atomicity | 5 | 9 | 9 |
| 5 | Config Server feasibility | 2 | 7 | 7 |
| 6 | Dev debugging | 1 | 8 | 8 |
| 7 | SRE operations | 3 | 8 | 8 |
| 8 | Security | 3 | 7 | 7 |
| 9 | Supported topology fit | 3 | 9 | 9 |
| 10 | Complexity | 2 | 8 | 6 |
| 11 | Code deletion | 3 | 9 | 8 |
| 12 | Net code cost | 2 | 8 | 6 |
| 13 | Testability | 2 | 8 | 7 |
| 14 | Failure diagnostics | 2 | 8 | 8 |
| 15 | Public API risk | 2 | 9 | 8 |
| 16 | Speculative generality risk | 2 | 8 | 6 |
| 17 | 80-service rollout | 3 | 8 | 7 |
| 18 | No-new-module fit | Gate | PASS | PASS |
| 19 | Breaking-change leverage | 1 | 9 | 9 |
| 20 | Long-term maintainability | 3 | 8 | 8 |
| — | Ownership coherence | 5 | 9 | 8 |
| — | Failure safety | 5 | 8 | 8 |

Maximum weighted score:

```text
640
```

Results:

| Candidate | Weighted score | Normalized |
|---|---:|---:|
| **F1** | **531** | **83.0 / 100** |
| **F2** | **504** | **78.8 / 100** |

The difference is meaningful but not large enough to bypass F1's focused API gate.

---

# 4. Evidence register by criterion

## 1. Correctness

### F1 = 8

FACT:

- current generic CAS primitive exists and has real consumers;
- corrected Prompt 4 selected `RETAIN_GENERIC`;
- one aggregate accepted value removes cross-domain accepted-state fragmentation.

ASSUMPTION:

- exact holder API can express expected-base CAS and stale semantics without modification that contaminates the generic abstraction.

The open API gate prevents a 9 or 10.

### F2 = 8

FACT:

- a domain-specific component can express the required state transitions explicitly;
- it can provide one accepted-state CAS.

RISK:

- it may duplicate or wrap an already sufficient generic primitive.

Correctness is not inherently higher merely because the class name is domain-specific.

## 2. Agent lifecycle fit

### F1 = 9, F2 = 9

FACT:

- state ownership is Agent-side;
- HELM initializes state during Agent bootstrap;
- Spring does not own SDK lifecycle;
- Config Server and DEV are later adapters.

Both fit the Agent-first topology equally.

## 3. Classloader fit

### F1 = 8, F2 = 8

FACT:

- Spring and Agent exchange JDK Open Types only;
- Agent-specific types remain in the extension;
- platform JMX becomes read-only;
- writer is intended for a private process-local MBeanServer.

ASSUMPTION:

- private-server discovery and isolation still require the feasibility spike.

Neither receives 9 before that proof.

## 4. Atomicity

### F1 = 9, F2 = 9

FACT:

- both use one immutable accepted state and one CAS;
- multi-holder prepare/rollback is rejected;
- no mutable projection holders survive.

Both provide the same linearization property.

## 5. Config Server feasibility

### F1 = 7, F2 = 7

FACT:

- Step 0B proves provenance primitives;
- production dependencies and refresh delivery remain unresolved;
- both state models receive the same aggregate snapshot.

State primitive choice does not materially change Config Server feasibility.

## 6. Dev debugging

### F1 = 8, F2 = 8

FACT:

- developer-facing typed JMX commands are converted into a full candidate;
- diagnostics are separate from state implementation;
- both expose the same accepted-state and attempt evidence.

The original report's claim that F2 inherently provides better debugging is unsupported.

## 7. SRE operations

### F1 = 8, F2 = 8

FACT:

- same read-only platform JMX contract;
- same audit, drift, metrics and health model;
- same Config Server authority.

Runtime Category A breadth, not F1 versus F2, determines SRE flexibility.

## 8. Security

### F1 = 7, F2 = 7

FACT:

- both depend on the same private writer, exact codec, secret exclusions and provenance guard.

OPEN:

- private-server security/isolation is not yet proven.

State wrapper choice does not improve security.

## 9. Supported topology fit

### F1 = 9, F2 = 9

FACT:

- Agent-first only;
- no SDK-only production parity;
- `mode=DISABLED` does not mutate an external Agent;
- no new module.

## 10. Complexity

### F1 = 8

It reuses the existing state primitive and adds one aggregate value.

### F2 = 6

It adds a domain-specific state layer that must either:

- wrap the generic primitive; or
- replace it and reimplement/prove CAS semantics.

This extra layer is justified only if the F1 API gate fails.

## 11. Code deletion

### F1 = 9

Enables deletion of:

- three mutable domain holders;
- sequential appliers;
- old protocol handlers;
- direct domain writers.

No replacement state abstraction beyond the required aggregate value is introduced.

### F2 = 8

Enables the same deletions, but may leave both:

- generic holder mechanics;
- a long-lived domain wrapper.

## 12. Net code cost

### F1 = 8

Lower expected long-lived responsibility count.

### F2 = 6

Potential duplicated state API and test surface.

No LOC claim is made before an actual diff.

## 13. Testability

### F1 = 8

The generic primitive already has tests; new tests focus on the aggregate value and one-CAS behavior.

### F2 = 7

Requires all aggregate tests plus dedicated wrapper/state-machine tests.

## 14. Failure diagnostics

### F1 = 8, F2 = 8

FACT:

- diagnostics, structural drift and audit are separate components;
- state implementation does not own attempt diagnostics.

No score difference is evidence-based.

## 15. Public API risk

### F1 = 9

Uses an existing internal generic primitive and Agent-internal policy types.

### F2 = 8

Adds a named domain state abstraction that must be kept internal and out of API/JMX DTO contracts.

## 16. Speculative generality risk

### F1 = 8

Risk exists only if developers expand the generic holder with tracing concepts; the reversal gate explicitly forbids that.

### F2 = 6

Risk of becoming:

- a policy framework;
- a state-machine god object;
- a second diagnostics/audit owner.

## 17. 80-service rollout

### F1 = 8

Smaller long-lived implementation surface and simpler controlled distributive.

### F2 = 7

Same external contract but more internal code and failure surface to validate across the distributive.

## 18. No-new-module fit

Both PASS.

A failure would disqualify the candidate rather than subtract points.

## 19. Breaking-change leverage

Both = 9.

Both permit:

- one coherent namespace cutover;
- deletion of all old writers;
- removal of per-domain state owners;
- no compatibility aliases.

## 20. Long-term maintainability

Both = 8.

F1 is simpler while sufficient.

F2 may become equally maintainable only when a domain-specific contract is demonstrably necessary.

## Ownership coherence

### F1 = 9

- one Agent state owner;
- existing generic mechanic;
- diagnostics and drift separated;
- adapters do not own runtime truth.

### F2 = 8

Still one Agent owner, but risks overlapping responsibility between wrapper and generic primitive.

## Failure safety

### F1 = 8, F2 = 8

Both provide:

- one accepted-state CAS;
- fail-LKG;
- bounded stale handling;
- same private writer and readiness gates.

Open security and integration spikes cap both scores.

---

# 5. Runtime breadth sensitivity

Broad/Narrow Category A modifies the winning architecture; it does not replace it.

## Broad Category A overlay

Potential effects:

- better SRE runtime flexibility;
- larger validation and security surface;
- more hot-path consumers;
- larger test matrix.

## Narrow Category A overlay

Potential effects:

- simpler state and validation;
- more `restartRequired` outcomes;
- less incident-time control;
- lower runtime mutation risk.

## Decision rule

```text
Choose F1 first.
Then choose broad or narrow Category A through Gate E5.
```

F1 remains the preferred state architecture under either overlay.

F3 is therefore removed from the architecture ranking.

---

# 6. Sensitivity analysis

## Scenario S1 — Generic API gate passes

Result:

```text
F1 remains winner.
```

This is the expected path.

## Scenario S2 — Generic API gate fails

Failure means the existing primitive cannot support expected-base/stale semantics without tracing-specific pollution or duplicated version truth.

Result:

```text
F1 is disqualified.
F2 becomes winner.
```

Most work remains reusable:

- immutable effective policy;
- Open-Type schema;
- codecs;
- private writer;
- adapters;
- tests for outcomes and concurrency;
- diagnostics;
- property model.

The original report overstated the loss as “all T1 progress.”

## Scenario S3 — Private MBeanServer spike fails

Result:

```text
Both finalists are NO-GO.
```

A new enforceable write boundary must be designed and rescored.

No numeric penalty is meaningful because a security hard gate failed.

## Scenario S4 — Narrow Category A approved

Result:

- F1 remains winner;
- complexity and test burden decrease;
- SRE score depends on explicit approval.

## Scenario S5 — Broad Category A required

Result:

- F1 remains winner;
- more security/performance proof is required;
- no reason appears to prefer F2 solely from breadth.

## Scenario S6 — Domain state invariants require a dedicated API

If a focused prototype shows the generic use is harder to understand or test than a dedicated state wrapper, F2 can overtake F1.

The evidence must be concrete code/API comparison, not preference for domain naming.

---

# 7. Worst-case analysis

## F1 worst case

The generic holder fails Gate E3.

Consequence:

- introduce F2 wrapper/state component;
- preserve most aggregate architecture artifacts;
- rewrite only state ownership/commit adapter and associated tests.

Worst-case risk is a controlled internal refactor, not architecture abandonment.

## F2 worst case

The generic holder was sufficient.

Consequence:

- permanent extra wrapper and tests;
- duplicated or obscured version semantics;
- future pressure to expand a domain-specific framework;
- lower deletion efficiency.

## Shared worst case

Private writer isolation or Config Server production feasibility fails.

Consequence:

```text
Neither state finalist receives implementation GO.
```

---

# 8. Regret analysis

Regret is expressed ordinally by long-lived architectural responsibility, not invented LOC or points.

| Choice | Future evidence | Regret | Reason |
|---|---|---|---|
| Choose F1 | F1 sufficient | None | Minimal correct design |
| Choose F1 | F2 required | Low | Add one dedicated state layer; most artifacts reused |
| Choose F2 | F2 required | None | Correct domain-specific design |
| Choose F2 | F1 sufficient | Medium–High | Unnecessary permanent abstraction and test surface |

Minimax-regret choice:

```text
F1
```

---

# 9. Complexity-adjusted ranking

The weighted model already includes:

- complexity;
- net code cost;
- testability;
- speculative generality;
- maintainability.

Applying another arbitrary numeric “complexity penalty” would double-count the same concerns.

The correct complexity adjustment is an overhead-budget gate.

## Result

- F1 stays within the budget.
- F2 stays within the budget only when it replaces a proven deficiency in the generic design.
- Without that proof, F2 exceeds the allowed abstraction budget.

Complexity-adjusted ranking:

1. **F1**
2. **F2 conditionally**

---

# 10. Architecture overhead budget

The winner may introduce only the following long-lived responsibilities.

## Agent extension

1. One immutable accepted policy model.
2. One accepted-state envelope.
3. One generic holder instance.
4. One aggregate validator/apply handler.
5. One Agent-side Open-Type codec.
6. One private `MBeanServer` registrar/marker.
7. One structural drift status component.
8. One apply diagnostics component.
9. One fixed 32-entry audit ring.
10. Read-only platform JMX projections.

## Spring autoconfigure

1. One private-server locator/client.
2. One application-side Open-Type codec.
3. One Config Server bridge.
4. One provenance classifier/snapshot builder.
5. One generation/dirty refresh worker.
6. One typed DEV JMX endpoint/command service.
7. One posture/authority guard.
8. One read-only Actuator/health composition.

## Forbidden overhead

- new Gradle module;
- generic policy framework;
- second accepted-state owner;
- separate LKG payload;
- public DTO layer;
- platform-server writer;
- raw snapshot DEV endpoint;
- Local SDK parity;
- compatibility layer;
- duplicate state wrapper without proven need.

---

# 11. Winner

## F1 — Generic Aggregate Accepted State

Binding choice:

```text
RETAIN_GENERIC
```

Subject to Gate E3.

Required simplifications:

1. `AcceptedTracingPolicyState` remains Agent-internal.
2. Use one ordinary package-private/final record or immutable class; do not use the invalid phrase “sealed record.”
3. Keep policy content separate from accepted-state metadata.
4. Keep drift, diagnostics and audit outside the CAS payload.
5. Do not retain mutable domain holders or projection state.
6. Use one Agent-side codec and one application-side codec against one exact Open-Type contract.
7. Do not put the writer on the platform `MBeanServer`.
8. Do not bind broad/narrow Category A into the state-mechanism decision.
9. Do not add interfaces and implementation hierarchies without a second real implementation.
10. Do not claim net-negative LOC until measured.

---

# 12. Runner-up

## F2 — Domain-Specific Aggregate State

F2 becomes the binding choice only when Gate E3 proves F1 would require:

- tracing-specific changes in the generic holder;
- duplicate sequence/version truth;
- awkward or unsafe stale semantics;
- a less comprehensible API than one dedicated state component.

Preferred F2 form:

```text
VersionedTracingPolicyState
    composes VersionedStateHolder<AcceptedTracingPolicyState>
```

Delete or replace the generic primitive only when repository usage proves it has no remaining value.

Narrow Category A is not the runner-up.

---

# 13. Reversal conditions

## F1 → F2

Reversal requires executable evidence of at least one:

1. Generic API cannot express expected-base CAS cleanly.
2. Generic API cannot distinguish stale conflict from internal failure.
3. F1 creates duplicate version or source metadata.
4. Generic primitive needs Config Server, authority, digest or diagnostics concepts.
5. F1 code is materially harder to understand/test than the dedicated wrapper.

## Broad → Narrow Category A

Requires:

- exact key classification;
- SRE approval;
- proof that removed runtime controls are operationally unnecessary;
- updated rollout/runbook contract.

## Narrow → Broad Category A

Requires:

- named incident/runtime-control requirement;
- security and performance proof;
- exact addition to the aggregate schema;
- no structural or secret value entering runtime mutation.

Do not use an arbitrary “more than 5% latency” reversal threshold. Use repository-approved benchmark budgets.

---

# 14. Proof gates before implementation GO

| Gate | Evidence | Status |
|---|---|---|
| E1 | JMX baseline and named consumer inventory | OPEN |
| E2 | Private process-local MBeanServer feasibility/isolation | OPEN / CRITICAL |
| E3 | Exact `VersionedStateHolder` API comparison/prototype | OPEN / F1 binding |
| E4 | Complete exact property/env disposition | OPEN / CRITICAL |
| E5 | Final Category A and safety bounds | OPEN |
| E6 | Production Boot/Cloud/Config Client and refresh delivery | OPEN |
| E7 | Exact diagnostics/JMX approved delta and secret inventory | OPEN |
| E8 | Architecture committee implementation GO | NOT GRANTED |

Recommended order:

```text
E1 + E2 + E3 + E4 + E5 + E6 + E7
    -> adversarial falsification
    -> E8
```

Spikes and evidence collection may occur before GO. Production target implementation and legacy deletion do not.

---

# 15. Handoff contract for Prompt 12

Prompt 12 must treat these findings as accepted:

```text
1. Only F1 and F2 are state-architecture finalists.
2. Narrow Category A is an overlay, not a third architecture.
3. F1 is the weighted and minimax-regret winner.
4. F2 is the conditional runner-up.
5. F1 and F2 have equal potential atomicity, lifecycle and classloader correctness.
6. F1 wins through lower complexity, higher deletion efficiency and lower speculative abstraction.
7. Diagnostics do not make F2 inherently superior because diagnostics are outside accepted state.
8. Private MBeanServer feasibility is a hard gate for both.
9. Exact VersionedStateHolder API suitability is the binding F1-specific gate.
10. If E3 fails, F2 becomes winner and most architecture artifacts remain reusable.
11. If E2 fails, both finalists are NO-GO.
12. No-new-module, multiple writers, public DTOs, Local SDK parity and long-lived dual paths are disqualifiers.
13. Backward compatibility has zero weight.
14. The architecture overhead budget is responsibility-based, not LOC-estimate-based.
15. Broad/Narrow Category A must be decided separately through E5.
16. No implementation GO is granted.
```

Prompt 12 must not inherit these claims from the original Prompt 11 output:

```text
1. That F3 is an independent architecture or general runner-up.
2. That Config Server feasibility belongs in the highest-weight group.
3. That dev debugging and complexity belong in the high-weight group while deletion/rollout/maintainability are underweighted.
4. That no-new-module has zero significance rather than being a hard gate.
5. That ownership coherence and failure safety can be omitted.
6. That unproven gates can be represented as 9/10 evidence.
7. That F2 inherently provides better diagnostics.
8. That all finalists use a writer on the existing platform MBeanServer.
9. That a sealed record is a valid Java design.
10. That one static codec crosses both classloaders.
11. That arbitrary sensitivity deltas, regret points and 5% thresholds are evidence.
12. That F1 failure loses all T1 work.
13. That net type/LOC reduction is proven without a repository diff.
14. That only E1/E2/E4/E8 matter before GO.
15. That the scoring result itself grants conditional implementation GO.
```

---

# 16. Final status

```text
Prompt 11 result: PASS_WITH_CRITICAL_METHODOLOGY_CORRECTIONS
Weighted winner: F1 RETAIN_GENERIC
Runner-up: F2 REFACTOR_DOMAIN_SPECIFIC
Narrow Category A: SEPARATE SENSITIVITY OVERLAY
Private writer gate: OPEN / CRITICAL
Generic API gate: OPEN / BINDING
Implementation GO: NOT GRANTED
Legacy deletion GO: BLOCKED
Next prompt: Adversarial Architecture Falsification
```
