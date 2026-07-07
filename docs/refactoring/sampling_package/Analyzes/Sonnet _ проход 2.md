<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# You are one member of a multi-model architecture review panel.

Context:
We are refactoring the Java package:
space.br1440.platform.tracing.core.sampling
The factual baseline is provided in the attached file:
tracing-sampling-package-inventory.md
Your task is not to produce a generic refactoring plan. Your task is to review the package from one specific architectural perspective and produce a strong, opinionated recommendation.
Use the attached inventory as the source of truth.
Perspective for this run:
<INSERT_ONE_OF_THE_PERSPECTIVES_BELOW>
Available perspectives:
Conservative Maintainer
Optimize for minimum safe change.
Preserve behavior.
Avoid clever abstractions.
Identify only refactorings with high confidence.
Clean Architecture Purist
Optimize for domain purity.
Separate model, validation, compilation, runtime, and adapters.
Identify boundary violations.
Runtime Safety Reviewer
Focus on mutability, concurrency, runtime updates, atomicity, invalid configuration, fallback behavior, and operational safety.
Java Library API Designer
Focus on API surface, package-private boundaries, naming, binary/source compatibility, extensibility, and accidental public contracts.
Testing Architect
Focus on characterization tests, property-style tests, contract tests, mutation testing candidates, and test seams.
OpenTelemetry Integration Reviewer
Focus on whether sampling domain is incorrectly coupled to OpenTelemetry concepts.
Propose adapter boundaries.
Spring Boot Platform Reviewer
Focus on configuration binding, defaults, validation, actuator/control-plane integration, and autoconfigure boundaries.
Adversarial Reviewer
Attack the proposed refactoring ideas.
Find hidden risks, over-engineering, missing behavior, migration traps, and unclear ownership.
Required output:
Review Perspective: <PERSPECTIVE>

1. Baseline Interpretation
Summarize only the facts from the inventory that matter for this perspective.
2. Main Architectural Problems
List the most important problems, ordered by severity.
3. Recommended Architecture
Describe the architecture you would recommend from this perspective.
4. Classes to Keep
List current classes that should remain mostly unchanged and explain why.
5. Classes to Split / Rename / Move
List current classes that should be changed and explain why.
6. New Types to Introduce
List proposed new classes/interfaces/records and their responsibilities.
7. Package Structure
Propose the target package structure.
8. Migration Plan
Give a safe phased migration plan.
9. Tests Required Before Refactoring
List concrete tests.
10. Scoring
Score your recommended architecture from 1 to 10:
behavioral safety,
architectural clarity,
simplicity,
testability,
runtime safety,
extensibility,
migration cost,
long-term maintainability.
For migration cost, higher score means lower cost.
11. Objections to Your Own Recommendation
Explain where your recommendation may be wrong.
12. Final Verdict
Give a clear recommendation:
proceed,
proceed only after tests,
avoid,
or defer.

Review Perspective: **Java Library API Designer**

## 1. Baseline Interpretation

`core.sampling` is currently a small, pure-Java package with a public engine, public request/decision/snapshot model, public rule classes, and two package-private utilities. Only `platform-tracing-otel-extension` imports these production types directly, and the hot path is already cleanly isolated from Spring and OTel inside the core package.[^1]

The main API facts that matter here are: `SamplingPolicyEngine` has a public varargs constructor plus `foundationEngine()` and `productionEngine()`, `SamplingPolicyRule` is a public extension point returning `null` for abstain, `SamplingPolicySnapshot` is both immutable policy state and a configuration compiler, and all seven rules are public final classes. The package also exposes some accidental surface area: `RouteRatioPrefix` is public but appears primarily as an implementation detail, `RECORD_ONLY` exists in `SamplingPolicyDecisionType` but is not produced by any rule, and `TraceIdRatioDecision` is package-private but untested.[^1]

The strongest API concern is boundary ambiguity: it is not obvious which types are stable public contracts, which are SPI, and which are incidental implementation details. There is also API duplication in the form of configuration normalization and validation being split between core and otel-extension, which makes long-term compatibility harder to reason about.[^1]

## 2. Main Architectural Problems

1. **Public surface is too broad for the actual usage model.** All seven rule classes are public, but the inventory says only the otel-extension uses the package in production, and individual rules can be bypassed outside the engine.[^1]
2. **Stable API vs internal implementation is unclear.** `RouteRatioPrefix` and probably several rule classes look accidental rather than intentional public contracts.[^1]
3. **`SamplingPolicySnapshot` mixes two roles.** It is both the immutable runtime policy and a compiler/normalizer for configuration input, which is awkward for binary compatibility and future evolution.[^1]
4. **`SamplingPolicyEngine` exposes more factory surface than needed.** `foundationEngine()` and `productionEngine()` are convenient, but they hard-code policy assembly into the core API and narrow future flexibility.[^1]
5. **`SamplingPolicyRule` is a weak SPI contract.** Returning `null` for abstain is workable, but it is easy to misuse and makes implementor expectations less explicit than a dedicated decision type or sealed result.[^1]
6. **Dead or ambiguous API elements exist.** `RECORD_ONLY` is currently unused by core rules, so it increases API area without serving the current model.[^1]
7. **Normalization ownership is split.** The duplicated normalization between `SamplerState` and `SamplingPolicySnapshot` increases the odds of API drift and incompatible semantics.[^1]

## 3. Recommended Architecture

I would recommend a **narrowed public API with a clearer SPI boundary**, while preserving all current behavior. In practice, that means keeping the core runtime model public, but demoting implementation-detail types to package-private or at least “internal by convention,” and moving configuration compilation into a dedicated compiler/validator type rather than leaving it inside `SamplingPolicySnapshot`.[^1]

Concretely, the library should present three stable public concepts: `SamplingPolicyRequest`, `SamplingPolicyDecision`, and `SamplingPolicySnapshot` as immutable runtime artifacts; a minimal `SamplingPolicyEngine` as the runtime evaluator; and a clearly documented SPI for policy rules if extension is truly intended. Everything else should become either internal helpers or explicit support types with tight scope.[^1]

From an API design perspective, this is the best compromise because it preserves the current hot-path contract while reducing accidental exposure, clarifying versioning expectations, and making future binary compatibility easier to manage.[^1]

## 4. Classes to Keep

- **`SamplingPolicyRequest`** should remain mostly unchanged because it is already a compact, immutable per-span input model and is a clean public boundary type.[^1]
- **`SamplingPolicyDecision`** should remain mostly unchanged because it is the central typed outcome and already enforces invariant checks in its constructor.[^1]
- **`SamplingPolicyReason`** should remain public because it maps to platform reason codes and likely forms part of downstream observability contracts.[^1]
- **`ParentContextState`** should remain unchanged because it is a small, explicit enum that captures a meaningful runtime distinction.[^1]
- **`SamplingPolicyEngine`** should mostly remain intact as the runtime evaluator, but its factory surface should be reduced or better segregated.[^1]


## 5. Classes to Split / Rename / Move

- **`SamplingPolicySnapshot`** should be split conceptually into runtime policy state plus configuration compilation/normalization responsibility.[^1]
- **`SamplingPolicyEngine`** should keep evaluation but lose some construction responsibility, especially if alternative policy assembly paths are introduced later.[^1]
- **`SamplingPolicyRule`** should be tightened as a SPI: keep it public only if you truly want external implementations; otherwise make it package-private and expose only the engine.[^1]
- **All seven rule classes** should likely be moved to internal/package-private status unless third-party rule composition is a deliberate goal.[^1]
- **`RouteRatioPrefix`** should be moved or demoted if it is only a compiled internal representation; its current public status looks accidental.[^1]
- **`SamplingPolicyDecisionType`** should be reviewed for removal or at least narrowing if `RECORD_ONLY` is not a real core behavior path.[^1]
- **`TraceIdRatioDecision`** should stay package-private, but it needs a dedicated test seam and clearer ownership if the ratio algorithm is considered part of the library contract.[^1]


## 6. New Types to Introduce

- **`SamplingPolicyConfig`**: immutable configuration DTO representing the normalized inputs before compilation.[^1]
- **`SamplingPolicyValidator`**: validates configuration eagerly and consistently, instead of splitting validation semantics across modules.[^1]
- **`SamplingPolicyCompiler`**: converts validated config into `SamplingPolicySnapshot` and owns normalization rules.[^1]
- **`InternalSamplingRules`** or similar package-private registry/factory if rule composition should stay centralized but hidden.[^1]
- **`SamplingPolicyRuleResult`** only if you decide the `null` abstain SPI is too fragile; otherwise do not introduce it just for abstraction’s sake.[^1]


## 7. Package Structure

A good target shape would be:

```text
space.br1440.platform.tracing.core.sampling
├── model
│   ├── SamplingPolicyRequest
│   ├── SamplingPolicyDecision
│   ├── SamplingPolicySnapshot
│   ├── SamplingPolicyReason
│   ├── ParentContextState
│   └── RouteRatioPrefix
├── engine
│   └── SamplingPolicyEngine
├── policy
│   ├── SamplingPolicyRule
│   ├── KillSwitchPolicyRule
│   ├── HardDropPolicyRule
│   ├── ForceHeaderPolicyRule
│   ├── QaTracePolicyRule
│   ├── ParentSampledPolicyRule
│   ├── RouteRatioPolicyRule
│   └── DefaultRatioPolicyRule
├── compiler
│   ├── SamplingPolicyConfig
│   ├── SamplingPolicyValidator
│   └── SamplingPolicyCompiler
└── internal
    ├── TraceIdRatioDecision
    └── SamplingPolicyRuleNames
```

This structure is not about engineering fashion; it is about making it obvious which names are stable contracts and which names are implementation detail.[^1]

## 8. Migration Plan

1. **Freeze current behavior with tests.** Lock down rule order, parity with current ratio sampling, snapshot normalization, and invalid-input behavior first.[^1]
2. **Introduce compiler/validator types behind existing APIs.** Make `SamplingPolicySnapshot.fromConfiguration(...)` delegate to them without changing observable results.[^1]
3. **Reduce visibility of implementation details.** Move `RouteRatioPrefix`, rules, and utility constants toward package-private/internal scope after ensuring no external production usage depends on them.[^1]
4. **Deprecate accidental public APIs.** Mark `foundationEngine()`, maybe `productionEngine()` if appropriate, and any unused or accidental types for eventual removal only after a transition period.[^1]
5. **Re-run downstream compatibility tests.** Verify the otel-extension imports and characterization tests still pass unchanged.[^1]

## 9. Tests Required Before Refactoring

- `SamplingPolicyEngine` tests for exact production order and non-abstain behavior.[^1]
- `SamplingPolicySnapshot` tests for normalization, route sorting, immutability, and invalid route entry handling.[^1]
- `TraceIdRatioDecision` tests for short trace IDs, null trace IDs, and boundary probabilities.[^1]
- `QaTracePolicyRule` tests, since the inventory says it has no dedicated core coverage.[^1]
- `SamplingPolicyDecision` invariant tests for all constructor edge cases.[^1]
- Characterization tests covering the otel-extension adapter path and `TraceIdRatioParityTest` parity behavior.[^1]
- A test that proves `RECORD_ONLY` is either intentionally unsupported in core or intentionally preserved for future adapter use.[^1]


## 10. Scoring

- **Behavioral safety:** 9/10
- **Architectural clarity:** 8/10
- **Simplicity:** 7/10
- **Testability:** 8/10
- **Runtime safety:** 9/10
- **Extensibility:** 6/10
- **Migration cost:** 7/10
- **Long-term maintainability:** 9/10


## 11. Objections to Your Own Recommendation

The main weakness in my recommendation is that it may be too conservative if `SamplingPolicyRule` is intended as a real public SPI. In that case, reducing rule visibility too aggressively would damage extensibility and break external composition patterns that are not visible in the current inventory.[^1]

Another possible mistake is moving `SamplingPolicySnapshot` compilation into a separate compiler type when the package is still relatively small. That improves conceptual purity, but it may add ceremony that the current users do not need if no direct external snapshot construction exists beyond otel-extension.[^1]

Finally, if the library’s real stability promise is “keep behavior stable, not API shape,” then narrowing the API surface may create needless churn for little practical benefit. That is why I would only proceed after characterization tests are complete and after confirming no hidden external consumers exist.[^1]

## 12. Final Verdict

**Proceed only after tests.**

The current package is already behaviorally solid, but the API surface is too wide and too ambiguous for a library intended to remain stable long-term. The right move is a careful boundary cleanup, not a rewrite: keep the runtime model, narrow incidental public types, and introduce explicit compiler/validator ownership only after locking down behavior with tests.[^1]

<div align="center">⁂</div>

[^1]: tracing-sampling-package-inventory.md

