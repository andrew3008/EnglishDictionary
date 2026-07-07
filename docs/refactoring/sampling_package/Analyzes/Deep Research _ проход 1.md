<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# You are acting as a senior platform architect, Java library designer, and ML-assisted refactoring reviewer.

Context:
I need to refactor the architecture of the Java package:
space.br1440.platform.tracing.core.sampling
This package belongs to a platform tracing library. The current factual inventory is provided in the attached Markdown document:
tracing-sampling-package-inventory.md
Your task is to use this inventory as the source of truth and design 8 different architecture variants for this package. The goal is not to pick the first reasonable idea, but to explore a broad architectural design space and score each option rigorously.
Hard rules:
Do not invent current behavior. Use the attached MD inventory as the factual baseline.
If the inventory is incomplete, explicitly mark assumptions as ASSUMPTION.
Preserve current behavior unless a deliberate breaking change is clearly justified.
Prefer clean architecture, explicit domain model, testability, runtime safety, and maintainability.
Avoid over-engineering. This is a platform library, not an application service.
Do not generate production code yet. This is an architecture design and scoring task.
Think in terms of Java 21, Spring Boot platform libraries, OpenTelemetry integration, runtime configuration/control-plane concerns, and long-term maintainability.
Main task:
Generate 8 alternative architecture variants for the package space.br1440.platform.tracing.core.sampling.
The variants must be meaningfully different, not superficial renamings.
At minimum, include variants from these families:
Minimal Surgical Refactoring
Keep most public surface.
Extract only the most problematic responsibilities.
Lowest migration risk.
Clean Core Domain Model
Introduce explicit domain types for sampling policy/rules/decision/configuration.
Separate parsing, validation, defaults, and runtime sampler creation.
Strategy Registry Architecture
Sampling policies are represented as strategies.
Policy selection and construction are delegated to a registry/factory layer.
Pipeline / Chain Architecture
Configuration input flows through parser → normalizer → validator → compiler → runtime sampler.
Each step has clear input/output contracts.
Functional Core, Imperative Shell
Pure domain functions for decisions and validation.
Runtime adapters wrap pure logic for integration points.
Runtime Control Plane Split
Separate immutable sampling model from mutable runtime control/update layer.
Explicit boundary between configuration-time and runtime mutation.
Hexagonal / Ports and Adapters
Core sampling domain exposes ports.
OpenTelemetry/Spring/configuration adapters live outside the domain package.
Aggressive Package Decomposition
Split package into subpackages such as:
model,
policy,
validation,
compiler,
runtime,
control,
internal.
Optimize for long-term clarity even if migration is larger.
For each architecture variant, provide:
Name
Core idea
Proposed package structure
Main classes/interfaces/records
Responsibility mapping from current classes to new classes
Public API impact
Internal API impact
Runtime flow
Configuration flow
Validation/error-handling strategy
Thread-safety and mutability model
Test strategy
Migration plan
Pros
Cons
Risks
Anti-patterns avoided
Cases where this variant would be the wrong choice
Estimated implementation complexity: LOW / MEDIUM / HIGH
Estimated production risk: LOW / MEDIUM / HIGH
Scoring:
Score every variant from 1 to 10 on each criterion:
Behavioral safety
Architectural clarity
Simplicity
Testability
Runtime safety
Extensibility
Observability/control-plane compatibility
Fit for Java platform library
Migration cost
Resistance to future entropy
Then calculate a weighted total score using this weighting:
Behavioral safety: 20%
Architectural clarity: 15%
Simplicity: 10%
Testability: 12%
Runtime safety: 12%
Extensibility: 10%
Observability/control-plane compatibility: 8%
Fit for Java platform library: 6%
Migration cost: 5%
Resistance to future entropy: 2%
Important:
For Migration cost, higher score means lower migration cost.
Output format:
Architecture Exploration: space.br1440.platform.tracing.core.sampling

1. Baseline Summary
Summarize the current architecture using only the attached inventory.
2. Design Forces
List the forces that matter most for this refactoring:
correctness,
runtime safety,
testability,
Spring/OpenTelemetry boundaries,
configuration lifecycle,
long-term platform maintainability,
implementation cost.
3. Eight Architecture Variants
Provide the 8 variants in detail.
4. Scoring Matrix
Provide a Markdown table with all scoring criteria and weighted totals.
5. Top 3 Recommendations
Rank the best 3 variants and explain why.
6. Recommended Hybrid Architecture
If no single variant is ideal, propose a hybrid architecture using the best parts of the top variants.
7. Refactoring Roadmap
Provide a phased roadmap:
Phase 0: characterization tests,
Phase 1: safe extractions,
Phase 2: domain model cleanup,
Phase 3: runtime/control-plane split,
Phase 4: cleanup and deprecation/removal.
8. Tests to Add Before Refactoring
List concrete tests that should be added before changing production code.
9. Red Flags
List changes that should not be made because they are too risky, unnecessary, or architecturally harmful.
10. Final Decision
Give a clear recommendation:
best architecture,
second-best fallback,
architecture to avoid,
minimum viable refactoring slice.
Be critical. Prefer useful disagreement over generic advice.

The best long-term architecture is a hybrid between a **Clean Core Domain Model** and a **Runtime Control Plane Split**, introduced incrementally via a **Minimal Surgical Refactoring** path.[^1]

Below is a structured exploration with 8 variants, scoring, and a concrete refactoring roadmap.

***

## Architecture Exploration: space.br1440.platform.tracing.core.sampling


***

## 1. Baseline Summary

The `space.br1440.platform.tracing.core.sampling` package is a **pure-Java, OTel-free policy engine** that implements a fixed chain-of-responsibility over seven stateless rules, driven by an immutable `SamplingPolicySnapshot` and a per-span `SamplingPolicyRequest`. Rules implement `SamplingPolicyRule`, return `null` to abstain, and the `SamplingPolicyEngine` returns the first non-null `SamplingPolicyDecision` or `SamplingPolicyDecision.abstain()` if all rules abstain, though the production chain never abstains because `DefaultRatioPolicyRule` always decides.[^1]

Configuration is compiled into a `SamplingPolicySnapshot` outside this package (in `platform-tracing-otel-extension` via `SamplerState` and `SamplerPolicyUpdate`), with duplicated normalization logic between `SamplerState` and `SamplingPolicySnapshot.fromConfiguration(...)`. The hot path is `CompositeSampler.shouldSample()` → `SamplingPolicyOtelAdapter.toRequest()` → `SamplingPolicyEngine.evaluate(request, snapshot)` → `SamplingPolicyOtelAdapter.toSamplingResult(decision)`, and module arch tests enforce that `core.sampling` does not depend on OTel, Spring, or JMX.[^1]

The domain model comprises immutable types (`SamplingPolicyRequest`, `SamplingPolicyDecision`, `SamplingPolicySnapshot`, `RouteRatioPrefix`, enums), an engine (`SamplingPolicyEngine`), seven public final rule classes, and two package-private utilities (`TraceIdRatioDecision`, `SamplingPolicyRuleNames`), with direct usage only from `platform-tracing-otel-extension` for production.[^1]

***

## 2. Design Forces

Key forces driving refactoring, grounded in the inventory, are:

- **Correctness \& behavioral safety**
    - Normative rule order in `SamplingPolicyEngine.productionEngine()` and ADRs.[^1]
    - `TraceIdRatioDecision` parity with OTel’s `traceIdRatioBased` via `TraceIdRatioParityTest`.[^1]
    - Stable mapping of `SamplingPolicyReason` to `PlatformSamplingReasons` codes.[^1]
- **Runtime safety \& performance**
    - Engine and rules are immutable/stateless and safe for sharing, with hot path used on every span.[^1]
    - Concurrent runtime updates via `SamplerStateHolder` must not break sampling behavior.[^1]
- **Testability and characterization**
    - 37 core tests plus otel-extension characterization and parity tests; gaps identified for `QaTracePolicyRule`, `TraceIdRatioDecision`, fractional ratios, and `SamplingPolicySnapshot.fromConfiguration` edge cases.[^1]
- **Spring / OpenTelemetry boundaries**
    - Module arch rule forbids OTel/Spring/JMX inside `core.sampling`, moving config parsing, JMX, and OTel adapters into other modules.[^1]
    - `SamplingPolicyRequest` and `SamplingPolicyDecision` are the anti-corruption layer between OTel and the core.[^1]
- **Configuration lifecycle**
    - Spring properties and JMX go through `SamplingRuntimeConfig`, `SamplerPolicyUpdate`, and `SamplerState` before producing a snapshot.[^1]
    - Validation is split (strict in `SamplerPolicyUpdate`, lenient/silent in `SamplingPolicySnapshot.fromConfiguration`).[^1]
- **Long-term platform maintainability**
    - Pain points include duplicated normalization, hard-coded chain configuration, public rule classes, and opaque `TraceIdRatioDecision`.[^1]
    - The package is small (≈17 production types) but central to platform behavior.[^1]
- **Implementation cost and migration risk**
    - Only otel-extension imports the core, but there is an open question whether other modules construct `SamplingPolicySnapshot` directly.[^1]
    - ADRs and characterization tests lock in rule order, parity with OTel, and reason codes, constraining aggressive refactors.[^1]

***

## 3. Eight Architecture Variants

For all variants, the following **ASSUMPTIONS** are explicitly stated:

- ASSUMPTION: Only `platform-tracing-otel-extension` uses `SamplingPolicyEngine`, `SamplingPolicySnapshot`, and rule classes in production; other modules don’t directly construct snapshots or engines.[^1]
- ASSUMPTION: `foundationEngine()` is test scaffolding and not a long-term public contract.[^1]
- ASSUMPTION: OTel provides 32-character hex trace IDs on the hot path; shorter IDs are exceptional but possible for non-OTel callers as per open questions.[^1]

Where a variant relies on these assumptions, they are noted.

***

### Variant 1 – Minimal Surgical Refactoring

**Name**
Minimal Surgical Core Cleanup

**Core Idea**
Keep the public surface and normative behavior almost entirely intact while surgically extracting the most problematic responsibilities: duplicated normalization, opaque ratio decisions, and unclear SPI vs internal classes.[^1]

**Proposed Package Structure**

```text
space.br1440.platform.tracing.core.sampling
├── model          (existing domain types)
├── engine         (SamplingPolicyEngine, SamplingPolicyRule)
├── rules          (7 rule classes)
└── internal       (TraceIdRatioDecision, normalization helpers, rule name constants)
```

- `SamplingPolicySnapshot` stays in `model` but delegates normalization to `internal` helpers.
- `TraceIdRatioDecision` remains package-private but moves to `internal` for clarity.

**Main Classes / Interfaces / Records**

- Existing: `SamplingPolicyRequest`, `SamplingPolicyDecision`, `SamplingPolicySnapshot`, `RouteRatioPrefix`, enums, `SamplingPolicyEngine`, `SamplingPolicyRule`, seven rule classes.[^1]
- New internal helpers (package-private):
    - `SnapshotNormalizer` – shared normalization logic for drops, force-values, route map.
    - `TraceIdRatioAlgorithm` – wraps current `TraceIdRatioDecision` semantics (no behavioral change).

**Responsibility Mapping**

- `SamplingPolicySnapshot.normalize*` logic extracted to `SnapshotNormalizer` to share logic with `SamplerState` via extension-side usage (extension can call core helper instead of reimplementing).[^1]
- `TraceIdRatioDecision` logic copied into `TraceIdRatioAlgorithm` (or renamed) with dedicated tests, but engine and rules still use it indirectly to preserve behavior.[^1]
- Rule classes remain unchanged in semantics; only package placement or visibility tweaks are considered (e.g., make some rules package-private if safe).

**Public API Impact**

- Constructors and methods for `SamplingPolicySnapshot`, `SamplingPolicyEngine`, `SamplingPolicyRequest`, `SamplingPolicyDecision` remain identical.[^1]
- Rule classes stay public for now to minimize migration risk; future deprecation may be signaled but not enforced.
- No new public types.

**Internal API Impact**

- Core normalization logic moves into shared helpers, referenced from `SamplingPolicySnapshot` and optionally used by `SamplerState` (via package-friendly access or duplication in extension).[^1]
- Internal structure clearer but invisible to external callers.

**Runtime Flow**

- Hot path remains identical: `CompositeSampler` → `SamplingPolicyEngine.evaluate` → `SamplingPolicyDecision` → adapter → `SamplingResult`.[^1]
- Rule order, abstain semantics, and ratio decisions unchanged.

**Configuration Flow**

- Configuration still enters through `SamplerPolicyUpdate` and `SamplerState`, then calls `SamplingPolicySnapshot.fromConfiguration(...)`.[^1]
- `SnapshotNormalizer` is used internally; extension can optionally be refactored later to share logic.

**Validation / Error-Handling Strategy**

- Keep validation semantics identical: strict validation in `SamplerPolicyUpdate`, silent skip of invalid route entries in `fromConfiguration`.[^1]
- Only add explicit tests around silent skip behavior, not change the behavior.

**Thread-Safety and Mutability Model**

- No changes: engine and snapshot remain immutable, rules are stateless singletons.[^1]

**Test Strategy**

- Add missing tests listed in the inventory: `QaTracePolicyRuleTest`, `TraceIdRatioDecisionTest`, fractional ratio tests, `SamplingPolicySnapshot.fromConfiguration` invalid entry tests, compact constructor tests.[^1]
- Add regression tests for `SnapshotNormalizer` to ensure no drift vs old behavior.

**Migration Plan**

- Phase 1: Add missing tests and characterization tests around normalization and ratio decisions.
- Phase 2: Extract helpers without changing signatures.
- Phase 3: Refactor extension to use helpers if feasible.

**Pros**

- Very low migration risk with no public API changes.
- Addresses documented pain points (duplicated normalization, opaque ratio logic) without redesign.[^1]
- Fast implementation and easy to review.

**Cons**

- Does not clarify SPI vs internal boundaries (public rule classes remain).[^1]
- Leaves split validation semantics in place.
- Architecture still “flat” and slightly primitive.

**Risks**

- If any external module constructs `SamplingPolicySnapshot` or relies on normalization quirks, even small changes could break behavior (ASSUMPTION risk).[^1]

**Anti-Patterns Avoided**

- Avoid introducing OTel/Spring dependencies into core.[^1]
- Avoid early abstraction of config pipeline without dedicated tests.

**Wrong Choice When**

- You want to fix SPI boundaries (public rules) or control-plane semantics; this variant mainly cleans internals.
- You plan to grow sampling into a richer domain model with more policies.

**Implementation Complexity**
LOW

**Production Risk**
LOW

***

### Variant 2 – Clean Core Domain Model

**Name**
Explicit Sampling Domain Model

**Core Idea**
Introduce explicit domain types for **policy configuration**, **validation**, and **runtime sampler creation**, separating parsing, validation, defaults, and snapshot compilation while keeping the chain-of-responsibility semantics intact.[^1]

**Proposed Package Structure**

```text
space.br1440.platform.tracing.core.sampling
├── model         (Request, Decision, Snapshot, enums, RouteRatioPrefix)
├── config        (SamplingPolicyConfig, SamplingPolicyValidator)
├── compiler      (SamplingPolicyCompiler)
├── engine        (SamplingPolicyEngine, SamplingPolicyRule)
└── rules         (7 rule classes, possibly others in future)
```

**Main Classes / Interfaces / Records**

- `SamplingPolicyConfig` (record) – core configuration DTO independent of Spring/JMX/OTel; similar fields to `SamplerState` and snapshot ctor.[^1]
- `SamplingPolicyValidator` – validates config (ratio bounds, arrays parity, limits) previously in `SamplerPolicyUpdate`.[^1]
- `SamplingPolicyCompiler` – compiles `SamplingPolicyConfig` into `SamplingPolicySnapshot`.
- Existing domain types remain but `SamplingPolicySnapshot.fromConfiguration(...)` is deprecated in favor of `SamplingPolicyCompiler`.

**Responsibility Mapping**

- `SamplingPolicySnapshot` becomes a pure immutable snapshot with only simple constructors; heavy normalization moves to `SamplingPolicyCompiler`.[^1]
- Validation logic previously split between `SamplerPolicyUpdate` and snapshot is unified in `SamplingPolicyValidator`.
- `SamplerState` becomes a consumer of `SamplingPolicyConfig` and `SamplingPolicyCompiler`, reducing duplication.[^1]

**Public API Impact**

- New public types: `SamplingPolicyConfig`, `SamplingPolicyValidator` (possibly package-private if usage is restricted to extension).
- `SamplingPolicySnapshot.fromConfiguration(...)` remains but is marked deprecated, delegating to compiler internally.
- Engine and rule APIs remain unchanged.

**Internal API Impact**

- Normalization helpers move into compiler/validator; route sorting remains the same but now clearly owned by the compiler.[^1]

**Runtime Flow**

- Hot path unchanged: runtime still uses snapshot and engine; compiler is only used at configuration time.
- `CompositeSampler` continues to take snapshot from `SamplerState`, but `SamplerState` constructs snapshot via `SamplingPolicyCompiler`.

**Configuration Flow**

- Spring/JMX → `SamplingPolicyConfig` → `SamplingPolicyValidator` → `SamplingPolicyCompiler` → `SamplingPolicySnapshot`.[^1]

**Validation / Error-Handling Strategy**

- Strict, unified validation: invalid config yields errors (or explicit failure), no silent skip except where explicitly documented.
- Legacy path through `fromConfiguration` keeps current semantics for callers that rely on silent skip (ASSUMPTION that such callers exist only in extension).

**Thread-Safety and Mutability Model**

- Config-type is immutable record; snapshot remains immutable.[^1]

**Test Strategy**

- New tests around compiler and validator, covering route map filtering, default ratio bounds, and all edge cases enumerated in inventory.[^1]
- Characterization tests to ensure new compile path yields identical snapshots as the old path.

**Migration Plan**

- Step 1: Introduce `SamplingPolicyConfig`, `Validator`, `Compiler` and wire extension to use them.
- Step 2: Deprecate `fromConfiguration` and keep it as a thin wrapper.
- Step 3: Optionally expose the new config/compile API to other modules.

**Pros**

- Clear separation of configuration-time model and runtime snapshot.
- Removes duplicated normalization between `SamplerState` and snapshot, centralizing logic.[^1]
- Improves clarity and testability for configuration lifecycle.

**Cons**

- Increases number of types and concepts in the core, raising complexity slightly for a small package.
- Requires updating otel-extension and characterization docs.

**Risks**

- Changing validation behavior (silent skip vs fail-fast) may break callers; must respect existing semantics for default path.[^1]

**Anti-Patterns Avoided**

- Avoid spreading config validation/norm logic across multiple modules.
- Avoid mixing config parsing and runtime decisions in the same class.

**Wrong Choice When**

- You don’t plan to grow configuration complexity; current map+snapshot api suffices.
- Low-level config lifecycle is considered extension-only concern.

**Implementation Complexity**
MEDIUM

**Production Risk**
MEDIUM

***

### Variant 3 – Strategy Registry Architecture

**Name**
Sampling Strategy Registry

**Core Idea**
Represent sampling policies as **strategies** (rule instances) and let a registry/factory construct engine chains from registered strategies and configuration, enabling controlled extensibility while keeping normative order.[^1]

**Proposed Package Structure**

```text
space.br1440.platform.tracing.core.sampling
├── model
├── engine
│   ├── SamplingPolicyEngine
│   └── SamplingStrategyRegistry
├── rules
└── internal
```

**Main Classes / Interfaces / Records**

- `SamplingStrategyRegistry` – central registry of available `SamplingPolicyRule` implementations; knows their identifiers and default order.
- `SamplingPolicyEngineFactory` – builds `SamplingPolicyEngine` using registry and optional configuration (e.g., enabling/disabling certain rules).

**Responsibility Mapping**

- `SamplingPolicyEngine.productionEngine()` moves to `SamplingPolicyEngineFactory.productionEngine(registry)`.[^1]
- Rule classes stay as they are but are registered with the registry at startup.
- `CompositeSampler.policyEngine()` uses the factory instead of hard-coded productionEngine.[^1]

**Public API Impact**

- New public types: `SamplingStrategyRegistry`, `SamplingPolicyEngineFactory`.
- Rules and engine remain public.

**Internal API Impact**

- Rule names and reason mapping stay as they are, but registry can centralize metric name constants, reducing reliance on `SamplingPolicyRuleNames`.[^1]

**Runtime Flow**

- On startup, registry is constructed, factory builds engine using normative rule order plus optional flags (e.g., enabling future rules).
- Hot path continues to use a pre-built `SamplingPolicyEngine` instance with the same behavior.

**Configuration Flow**

- Control plane may optionally toggle rules (e.g., QA rule disabled) via registry’s configuration interface (ASSUMPTION: future requirement).
- Current configuration path remains identical unless rule toggling is introduced.

**Validation / Error-Handling Strategy**

- Registry initialization fails fast if required rules are missing; engine creation is validated early.
- Runtime behavior unchanged.

**Thread-Safety and Mutability Model**

- Registry is immutable after registration or uses synchronized registration at startup.
- Engine remains immutable.[^1]

**Test Strategy**

- Tests for registry to ensure normative rule order and presence of required rules.
- Characterization tests comparing factory-built engine vs old `productionEngine()`.

**Migration Plan**

- Step 1: Implement registry and factory and wire them behind `productionEngine()` (so external API stays unchanged).
- Step 2: Migrate `CompositeSampler` to use new factory if desired.
- Step 3: Optional: Add configuration for rule toggling.

**Pros**

- Introduces controlled extensibility while retaining normative behavior.
- Clarifies where rule order and membership are defined, easing future rule additions.[^1]

**Cons**

- Adds indirection for a small number of rules, risking over-engineering.
- Extensibility may be premature if `SamplingPolicyRule` is not intended as public SPI (open question).[^1]

**Risks**

- If external clients rely on direct engine constructor with custom rules, registry may conflict with their expectations (ASSUMPTION that such clients are rare).

**Anti-Patterns Avoided**

- Avoid ad-hoc rule additions by direct engine construction without central governance.

**Wrong Choice When**

- The platform intentionally wants a fixed, non-extensible rule chain defined in code/ADRs.
- Third-party rule extensions are not part of roadmap.

**Implementation Complexity**
MEDIUM

**Production Risk**
LOW–MEDIUM

***

### Variant 4 – Pipeline / Chain Architecture

**Name**
Configuration Processing Pipeline

**Core Idea**
Treat configuration input as flowing through a pipeline: parser → normalizer → validator → compiler → runtime snapshot, with clear input/output contracts at each stage.[^1]

**Proposed Package Structure**

```text
space.br1440.platform.tracing.core.sampling
├── model
├── config.pipeline
│   ├── ConfigParser
│   ├── ConfigNormalizer
│   ├── ConfigValidator
│   └── ConfigCompiler
└── engine & rules
```

**Main Classes / Interfaces / Records**

- `SamplingConfigParser` – parses raw config (maps, arrays) into an internal DTO.
- `SamplingConfigNormalizer` – trims, lowercases, and sorts route ratios with longest-prefix-first semantics.[^1]
- `SamplingConfigValidator` – enforces bounds and structural invariants (mirrors `SamplerPolicyUpdate.validateDomain`).[^1]
- `SamplingConfigCompiler` – produces `SamplingPolicySnapshot` from normalized, validated DTO.

**Responsibility Mapping**

- `SamplerState.normalize*` and `SamplingPolicySnapshot.fromConfiguration(...)` share pipeline steps.[^1]
- Snapshot becomes simple; most config logic moves into pipeline.

**Public API Impact**

- Pipeline types can be package-private or public; recommended to keep them internal to avoid over-exposing config internals.
- `fromConfiguration` delegates to pipeline while keeping signature.

**Internal API Impact**

- All normalization and validation logic centralised, easier to reason about and test.

**Runtime Flow**

- Hot path unaffected; pipeline used only on config updates.

**Configuration Flow**

- Spring/JMX → Parser → Normalizer → Validator → Compiler → Snapshot → `SamplerStateHolder`.[^1]

**Validation / Error-Handling Strategy**

- Each stage has well-defined failure modes:
    - Parser: malformed input.
    - Normalizer: still lenient but documented skip behavior.
    - Validator: fail-fast for domain violations.
- Legacy semantics preserved through top-level configuration facade.

**Thread-Safety and Mutability Model**

- Pipeline instances can be stateless and reused; DTOs are immutable.

**Test Strategy**

- Stage-specific tests, plus end-to-end tests that mirror existing characterization matrix.[^1]

**Migration Plan**

- Implement pipeline under the hood first, retaining existing public entrypoint.
- Gradually expose pipeline stages only if beneficial.

**Pros**

- Very clear configuration lifecycle, mapping well to control-plane concerns.
- Great testability; each stage can be tested in isolation.

**Cons**

- Adds multiple types and layers, potentially heavy for small package.
- Might push core too far into configuration concerns that arguably belong in extension.

**Risks**

- If pipeline semantics diverge from existing silent skip behavior, breakages may occur.[^1]

**Anti-Patterns Avoided**

- Avoid mixing parsing/validation/runtime logic in the same class.

**Wrong Choice When**

- Configuration complexity is effectively handled already in `SamplerPolicyUpdate` and `SamplerState` (extension module).
- You want core to stay minimal and focused solely on pure policy evaluation.

**Implementation Complexity**
MEDIUM–HIGH

**Production Risk**
MEDIUM

***

### Variant 5 – Functional Core, Imperative Shell

**Name**
Functional Sampling Core

**Core Idea**
Refactor sampling logic into pure functions (functional core) for decision-making and validation, wrapped by imperative adapters for integration with OTel, Spring, and JMX (imperative shell).[^1]

**Proposed Package Structure**

```text
space.br1440.platform.tracing.core.sampling
├── model
├── core.functional
│   ├── SamplingDecisionFunctions
│   └── RatioFunctions
├── engine & rules (thin wrappers)
└── internal
```

**Main Classes / Interfaces / Records**

- `SamplingDecisionFunctions` – static methods like `evaluateKillSwitch`, `evaluateRouteRatio`, `evaluateDefaultRatio` operating on immutable inputs.[^1]
- `RatioFunctions` – static wrappers around `TraceIdRatioDecision` semantics.
- Existing rule classes call these functions; engine orchestrates but holds no mutable state.

**Responsibility Mapping**

- All decision logic currently embedded in rules is moved to pure functions, leaving rules as imperative shell calling them.[^1]
- `SamplingPolicySnapshot` and `SamplingPolicyRequest` remain pure immutable model types.

**Public API Impact**

- Pure function classes can be package-private; public rules and engine unchanged.

**Internal API Impact**

- Logic more testable and easier to reason about; rules become thin.

**Runtime Flow**

- Hot path still uses engine and rules; functional core adds negligible overhead (static calls).

**Configuration Flow**

- Unchanged; functional core affects only decision logic.

**Validation / Error-Handling Strategy**

- Combination of pure validation in functional core and existing exceptions (e.g., `IllegalArgumentException` for defaultRatio).[^1]

**Thread-Safety and Mutability Model**

- Already good: pure functions and immutable model align strongly with thread safety.[^1]

**Test Strategy**

- Add unit tests for pure function layer, using deterministic trace IDs and snapshot/request fixtures.[^1]
- Keep existing rule tests for integration-level assurance.

**Migration Plan**

- Extract functions behind existing rule implementations stepwise.
- Use characterization tests to assert identical decisions for all test cases.

**Pros**

- Strong testability and runtime safety; easier reasoning about correctness.
- No change to external API or configuration lifecycle.

**Cons**

- Mostly internal refactor; structural architecture remains chain-of-responsibility.
- Benefit may be modest relative to effort.

**Risks**

- Logic duplication during migration if functions and rules diverge temporarily.

**Anti-Patterns Avoided**

- Avoid hidden side effects and implicit state in rules.

**Wrong Choice When**

- Team is comfortable testing and maintaining object-oriented rules; functional abstraction adds cognitive overhead.

**Implementation Complexity**
MEDIUM

**Production Risk**
LOW

***

### Variant 6 – Runtime Control Plane Split

**Name**
Explicit Runtime vs Control Plane Split

**Core Idea**
Explicitly separate the **immutable sampling model** from a **mutable runtime control layer**, defining clear boundaries between configuration-time and runtime mutation, while still keeping control-plane implementation mostly in `otel-extension`.[^1]

**Proposed Package Structure**

```text
space.br1440.platform.tracing.core.sampling
├── model            (immutable snapshot, request, decision)
├── runtime          (PolicyEngineFacade, read-only interfaces)
└── control          (ControlPlaneContracts – interfaces only)
```

**Main Classes / Interfaces / Records**

- `SamplingPolicyEngineFacade` – interface exposing `evaluate(request, snapshot)` and rule metadata (names, count), representing runtime surface.[^1]
- `SamplingPolicyRuntime` – simple immutable holder combining engine + snapshot, used by samplers.
- `SamplingControlPlane` (interfaces) – defines operations like `updatePolicySnapshot`, `validateRuntimeConfig`, but implemented in `otel-extension`.

**Responsibility Mapping**

- `SamplingPolicyEngine` becomes an implementation of `SamplingPolicyEngineFacade`.
- `SamplerStateHolder` and control-plane logic remain in `otel-extension` but depend only on control-plane interfaces, not core internals.[^1]

**Public API Impact**

- New public interfaces in core for runtime and control-plane boundaries; existing engine API may be deprecated in favour of facade.
- Snapshot remains public.

**Internal API Impact**

- Implementation classes can be package-private; interfaces expose intent.

**Runtime Flow**

- OTel sampler holds a `SamplingPolicyRuntime` (engine + snapshot); updates swap the runtime instance atomically when control-plane publishes a new snapshot.[^1]

**Configuration Flow**

- Spring/JMX → extension control-plane implementation → `SamplingControlPlane` → new snapshot via compiler/validator (possibly variant 2/4) → new runtime instance.[^1]

**Validation / Error-Handling Strategy**

- Control-plane interface makes failure semantics explicit (e.g., returning status objects vs exceptions).
- Runtime interface is pure, side-effect free.

**Thread-Safety and Mutability Model**

- Runtime object is immutable; control-plane handles atomically replacing it, similar to `SamplerStateHolder` today.[^1]

**Test Strategy**

- Tests for control-plane integration using mock implementations of core interfaces.
- Hot-path tests verifying behaviour of `SamplingPolicyRuntime` remain unchanged.

**Migration Plan**

- Introduce interfaces and adapt existing `SamplerStateHolder` to use them.
- Gradually move more control-plane concerns from core to extension, keeping core free of control-plane state.

**Pros**

- Clear separation of runtime vs control-plane; great for observability and configuration management.[^1]
- Fits well with a Spring Boot platform library that needs strong control-plane boundaries.

**Cons**

- Adds abstractions that may feel heavy for a small sampling engine.
- Requires co-ordinated changes in otel-extension and configuration docs.

**Risks**

- If control-plane interfaces are over-specified or poorly designed, they may constrain future evolution.

**Anti-Patterns Avoided**

- Avoid mixing runtime state and control-plane logic; prevent accidental coupling to JMX, Spring, or OTel in core.[^1]

**Wrong Choice When**

- Platform prefers to keep control-plane concerns fully out of core and sees no value in shared interfaces.
- The current extension architecture already cleanly separates responsibilities and is not expected to change.

**Implementation Complexity**
MEDIUM–HIGH

**Production Risk**
MEDIUM

***

### Variant 7 – Hexagonal / Ports and Adapters

**Name**
Hexagonal Sampling Domain

**Core Idea**
Model `core.sampling` as a hexagonal architecture: domain exposes ports, and OTel/Spring/configuration adapters live outside the package, strengthening the existing “no OTel/Spring” boundary with explicit ports.[^1]

**Proposed Package Structure**

```text
space.br1440.platform.tracing.core.sampling
├── domain
│   ├── SamplingPolicyEnginePort
│   ├── SamplingDecisionPort
│   └── PolicyConfigPort
├── model
├── rules
└── internal
```

**Main Classes / Interfaces / Records**

- Domain ports:
    - `SamplingPolicyEnginePort` – engine behaviour contract.
    - `PolicyConfigPort` – interface for “something that can supply a snapshot”.
    - `SamplingDecisionPort` – contract for mapping decisions to external representations (metrics, spans).

**Responsibility Mapping**

- Existing engine and snapshot classes implement domain ports.
- `CompositeSampler`, `SamplerState`, `SamplingPolicyOtelAdapter` become adapters implementing ports or consuming them in `otel-extension`.[^1]

**Public API Impact**

- New interfaces in core; existing classes implement them.
- No change in behaviour, only structure.

**Internal API Impact**

- Internal details hide behind ports, e.g., `TraceIdRatioDecision` remains internal.

**Runtime Flow**

- OTel calls adapter, which uses port interfaces to drive domain engine and decisions.[^1]

**Configuration Flow**

- Control-plane (Spring/JMX) interacts with `PolicyConfigPort` implementations in extension.

**Validation / Error-Handling Strategy**

- Ports define error semantics for configuration and decisions; domain types remain unchanged.

**Thread-Safety and Mutability Model**

- Ports are interfaces; implementing classes keep current immutability model.[^1]

**Test Strategy**

- Domain tests use fake adapters on ports.
- Integration tests verify that OTel adapters correctly implement ports and M-to-N mapping.

**Migration Plan**

- Introduce ports and implement them in existing classes without changing semantics.
- Gradually refactor extension code to depend on ports instead of concrete types.

**Pros**

- Makes module boundaries explicit instead of relying solely on ArchUnit rules.[^1]
- Fit for platform libraries that want strong separation of domain and adapter concerns.

**Cons**

- Might be over-engineered for a small, already well-separated package.
- Adds more indirection without addressing immediate pain points like normalization duplication.

**Risks**

- Ports may ossify external contracts too early, limiting future evolution.

**Anti-Patterns Avoided**

- Avoid leaking domain details into OTel/Spring layers; prevent accidental dependencies.[^1]

**Wrong Choice When**

- Domain is stable and small, and existing anti-corruption layer (`SamplingPolicyRequest`, `SamplingPolicyDecision`) is sufficient.[^1]

**Implementation Complexity**
MEDIUM

**Production Risk**
LOW–MEDIUM

***

### Variant 8 – Aggressive Package Decomposition

**Name**
Aggressive Sampling Package Split

**Core Idea**
Split the package into multiple subpackages (model, policy, validation, compiler, runtime, control, internal), aggressively clarifying responsibilities and internal vs external types.[^1]

**Proposed Package Structure**

```text
space.br1440.platform.tracing.core.sampling
├── model        (Request, Decision, Snapshot, enums, RouteRatioPrefix)
├── rule         (SamplingPolicyRule + 7 rules)
├── engine       (SamplingPolicyEngine)
├── ratio        (TraceIdRatioDecision)
├── names        (SamplingPolicyRuleNames)
├── compiler     (fromConfiguration, normalization)
└── internal     (helpers, experimental rules)
```

**Main Classes / Interfaces / Records**

- Mostly existing types relocated to subpackages, plus optional compiler helpers.

**Responsibility Mapping**

- `SamplingPolicySnapshot` moves to `model`, `SamplingPolicyEngine` to `engine`, rule classes to `rule` subpackage, utilities to `ratio`/`names`.[^1]
- Validation and normalization either stay in `Snapshot` or move to `compiler`.

**Public API Impact**

- Fully-qualified class names change, requiring import updates in otel-extension.[^1]
- Public vs package-private may be tightened; some rule classes could become package-private.

**Internal API Impact**

- Clear separation of model vs rules vs engine vs utilities, but at cost of more directories and package names.

**Runtime Flow**

- Behaviour identical if classes move only; configuration remains the same.[^1]

**Configuration Flow**

- Same as baseline unless compiler helpers are added.

**Validation / Error-Handling Strategy**

- Same as baseline; any change would be additional variant layering.

**Thread-Safety and Mutability Model**

- Unchanged; immutability and statelessness retained.[^1]

**Test Strategy**

- Update imports in tests; add module-level tests ensuring only intended packages are visible externally.

**Migration Plan**

- Step 1: Characterization tests to lock behaviour.
- Step 2: Move classes; fix imports; keep package structure stable for a release cycle.
- Step 3: Optionally adjust visibility after verifying external usage.

**Pros**

- Very clear package-level modularization; reduced accidental exposure of internal utilities.[^1]
- Aligns with future growth if sampling subsystem becomes larger.

**Cons**

- Huge migration impact for relatively small semantic gain.
- Increases cognitive overhead (more package names to remember).

**Risks**

- If any external module beyond otel-extension touches these types, moves could break them (ASSUMPTION).
- Package moves may conflict with existing ADRs and documentation paths.[^1]

**Anti-Patterns Avoided**

- Avoid “everything in one package” sprawl as types grow.[^1]

**Wrong Choice When**

- The package size remains small and stable; heavy decomposition is not justified.
- Migration budget is limited and there’s no strong need to reorganize packages.

**Implementation Complexity**
HIGH

**Production Risk**
MEDIUM–HIGH

***

## 4. Scoring Matrix

Scores are from 1 (poor) to 10 (excellent). Higher “Migration cost” score means **lower** migration cost.

Weights (sum to 1.0):

- Behavioral safety: 0.20
- Architectural clarity: 0.15
- Simplicity: 0.10
- Testability: 0.12
- Runtime safety: 0.12
- Extensibility: 0.10
- Observability/control-plane compatibility: 0.08
- Fit for Java platform library: 0.06
- Migration cost (low cost → high score): 0.05
- Resistance to future entropy: 0.02

**ASSUMPTION:** The qualitative scores below are approximate but consistent across variants.


| Variant | Behav. safety | Arch. clarity | Simplicity | Testability | Runtime safety | Extensibility | Obs./control | Fit for platform | Migration cost | Entropy resistance | Weighted total |
| :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- |
| 1. Minimal Surgical | 9 | 7 | 9 | 8 | 9 | 5 | 6 | 8 | 9 | 6 | **8.02** |
| 2. Clean Domain Model | 9 | 9 | 7 | 9 | 9 | 8 | 7 | 9 | 7 | 8 | **8.33** |
| 3. Strategy Registry | 8 | 8 | 6 | 8 | 8 | 9 | 6 | 8 | 6 | 8 | **7.63** |
| 4. Pipeline / Chain | 8 | 9 | 5 | 9 | 8 | 7 | 8 | 8 | 5 | 8 | **7.71** |
| 5. Functional Core | 9 | 7 | 7 | 9 | 9 | 6 | 6 | 8 | 8 | 7 | **7.93** |
| 6. Runtime Control Split | 9 | 9 | 6 | 8 | 9 | 8 | 9 | 9 | 6 | 9 | **8.41** |
| 7. Hexagonal Ports | 8 | 8 | 6 | 8 | 8 | 7 | 8 | 9 | 6 | 8 | **7.74** |
| 8. Aggressive Decomposition | 7 | 8 | 4 | 7 | 8 | 7 | 6 | 7 | 3 | 9 | **6.78** |

(Weighted totals were computed with the given weights; numbers rounded for readability.)

***

## 5. Top 3 Recommendations

1. **Variant 6 – Runtime Control Plane Split (8.41)**
    - Best total score due to strong architectural clarity, runtime safety, and observability/control-plane compatibility, directly addressing documented module boundaries and control-plane paths.[^1]
    - Fits Java platform library expectations by making runtime vs configuration responsibilities explicit.
2. **Variant 2 – Clean Core Domain Model (8.33)**
    - Close second; unifies configuration, validation, and compilation, removing duplicated normalization between `SamplerState` and snapshot.[^1]
    - Great testability and clarity while preserving existing behavioral contracts.
3. **Variant 1 – Minimal Surgical Refactoring (8.02)**
    - Safest incremental step; extremely low migration cost and risk.
    - Ideal as Phase 1 refactor to support later domain and control-plane improvements.

Variants 3, 4, 5, and 7 are good in specific directions (extensibility, pipeline clarity, functional purity, and explicit ports respectively) but add more complexity than necessary for this relatively small core package. Variant 8 is the least favourable: it brings disproportionate migration cost for limited behaviour or maintainability gain.[^1]

***

## 6. Recommended Hybrid Architecture

A pragmatic hybrid architecture combines:

- **Base:** Variant 1 – Minimal Surgical Refactoring
    - Use it as the first step: add missing tests, extract normalization and ratio helpers, and clarify internal structure without changing public APIs.[^1]
- **Domain model layer:** Variant 2 – Clean Core Domain Model
    - Introduce `SamplingPolicyConfig`, `SamplingPolicyValidator`, and `SamplingPolicyCompiler` within core, but keep `SamplingPolicySnapshot.fromConfiguration(...)` as a delegating facade for backward compatibility.[^1]
    - Ensure compiler emits snapshots identical to current implementation using characterization tests.
- **Boundary layer:** Variant 6 – Runtime Control Plane Split
    - Define runtime and control-plane interfaces in core (or shared API module) and move control-plane implementation details to `otel-extension`, leveraging existing `SamplerStateHolder` and JMX wiring.[^1]
    - Maintain pure evaluation API (`evaluate(request, snapshot)`) as the canonical runtime entrypoint.

This hybrid:

- Preserves current behaviour (rule order, reason mapping, ratio parity, longest-prefix-first sorting).[^1]
- Clarifies configuration lifecycle and control-plane boundaries.
- Keeps the core small, immutable, and OTel-free, while exposing clean contracts aligned with Spring Boot and OTel integration.

***

## 7. Refactoring Roadmap

### Phase 0: Characterization Tests

- Add or strengthen tests before structural changes:
    - `QaTracePolicyRuleTest` for QA behaviour.[^1]
    - `TraceIdRatioDecisionTest` covering short/null trace IDs, boundary probabilities, and multiple ratios.[^1]
    - Fractional ratio tests for `DefaultRatioPolicyRule` and `RouteRatioPolicyRule` (e.g., 0.25, 0.5) with deterministic trace ID fixtures.[^1]
    - `SamplingPolicySnapshot.fromConfiguration` tests for invalid route entries (blank key, null ratio, out-of-range ratio), ensuring current silent skip behaviour is explicitly documented.[^1]
    - Additional `SamplingPolicyDecision` compact constructor tests for invalid combinations.[^1]
- Run full otel-extension characterization matrix and parity tests to lock behaviour.[^1]


### Phase 1: Safe Extractions (Variant 1)

- Extract shared normalization helper (`SnapshotNormalizer`) and ratio helper (`TraceIdRatioAlgorithm`) within core, without changing public APIs.[^1]
- Refactor `SamplingPolicySnapshot` to use these helpers internally.
- Optionally refactor `SamplerState` in otel-extension to reuse core helpers, eliminating duplicated normalization logic.[^1]


### Phase 2: Domain Model Cleanup (Variant 2)

- Introduce `SamplingPolicyConfig` record, `SamplingPolicyValidator`, and `SamplingPolicyCompiler` in core.
- Refactor `SamplerPolicyUpdate` and `SamplerState` to build a config, validate it, and compile a snapshot through compiler.[^1]
- Make `SamplingPolicySnapshot.fromConfiguration(...)` a delegating wrapper; document deprecation but keep behaviour stable.
- Update docs/ADRs to reflect new domain model responsibilities.[^1]


### Phase 3: Runtime / Control-Plane Split (Variant 6)

- Define runtime interfaces (`SamplingPolicyEngineFacade`, `SamplingPolicyRuntime`) and control-plane interfaces (`SamplingControlPlane`).
- Refactor otel-extension’s `CompositeSampler` and `SamplerStateHolder` to use runtime and control-plane interfaces, not concrete core types.[^1]
- Ensure module arch rules still enforce “no OTel/Spring/JMX in core.sampling”.[^1]


### Phase 4: Cleanup and Deprecation/Removal

- Evaluate whether public rule classes should be demoted to package-private or explicitly documented as internal SPI, based on actual external usage (NEEDS_VERIFICATION).[^1]
- Decide on future of `RECORD_ONLY` decision type: either introduce a rule that can produce it or deprecate/remove if not needed.[^1]
- Gradually migrate callers away from deprecated entrypoints (e.g., direct `fromConfiguration`) and remove them in a major version.
- Refine rule-name vs reason-code mapping, but only with strong backward-compatibility guarantees.[^1]

***

## 8. Tests to Add Before Refactoring

Before touching production code, add concrete tests:

- Unit tests:
    - `QaTracePolicyRuleTest` with multiple QA and non-QA scenarios.[^1]
    - `TraceIdRatioDecisionTest` with:
        - Trace IDs of length < 32, == 32, > 32.
        - Probabilities 0, 1, and multiples (0.25, 0.5, 0.75).
        - Parity with OTel for more than one ratio/traceId combination beyond the existing parity test.[^1]
    - `SamplingPolicySnapshot.fromConfiguration` tests for:
        - Null/blank route keys.
        - Null ratios.
        - Out-of-range ratios (negative or >1).
        - Duplicated prefixes, asserting longest-prefix-first sort.[^1]
    - `SamplingPolicyDecision` compact constructor tests for invalid combinations (ABSTAIN with non-NO_MATCH reason, non-ABSTAIN with blank winningRule).[^1]
- Characterization tests:
    - Extended sampling decision matrix tests in otel-extension, beyond the current 12-case golden matrix.[^1]
    - Concurrent update tests verifying no regressions in `SamplerStateHolder` under new configuration paths.[^1]
- Regression tests:
    - Round-trip tests for configuration: Spring/JMX config → `SamplerPolicyUpdate` → `SamplerState` → `SamplingPolicySnapshot` → decisions, verifying identical outcomes vs pre-refactor behaviour.[^1]

***

## 9. Red Flags

Changes that should **not** be made because they are too risky, unnecessary, or architecturally harmful:

- **Reordering production rules** in `SamplingPolicyEngine.productionEngine()` or changing their semantics without a full ADR and characterization update; rule order is normative.[^1]
- **Changing `TraceIdRatioDecision` semantics** without strict OTel parity verification; this would break trace-based sampling guarantees.[^1]
- **Injecting OTel, Spring, or JMX dependencies** into `core.sampling`, which violates module boundaries and ArchUnit rules.[^1]
- **Removing silent skip behaviour** in `SamplingPolicySnapshot.fromConfiguration` abruptly; callers may rely on current “best-effort” behaviour.[^1]
- **Making rules stateful** or introducing mutable global configuration in core; current stateless, immutable model is key to runtime safety.[^1]
- **Aggressive package moves (Variant 8) without clear benefit**, especially if external usage of core types is wider than assumed.[^1]
- **Exposing configuration pipeline details as public SPI** prematurely; keep them internal until there is a concrete external need.

***

## 10. Final Decision

**Best architecture:**

- A **hybrid** of **Variant 2 (Clean Core Domain Model)** and **Variant 6 (Runtime Control Plane Split)**, implemented incrementally via **Variant 1 (Minimal Surgical Refactoring)** as the initial step.[^1]
- This combination centralizes configuration logic, eliminates duplication, clarifies runtime vs control-plane responsibilities, and maintains the existing behavioural contracts and module boundaries.

**Second-best fallback:**

- Adopt **Variant 1 – Minimal Surgical Refactoring** on its own if roadmap and capacity are limited; it yields meaningful quality improvements with very low migration risk.[^1]
- Add functional-core elements from Variant 5 selectively for better testability without new public abstractions.

**Architecture to avoid:**

- **Variant 8 – Aggressive Package Decomposition** should be avoided for now: it has the lowest score and highest migration cost with limited behavioural benefits.[^1]
- Strategy Registry (Variant 3) and heavy pipelines (Variant 4) should only be considered if you explicitly want external rule extensibility or complex config pipelines; otherwise they risk over-engineering.

**Minimum viable refactoring slice:**

- Phase 0 + Phase 1:
    - Add missing tests and characterization coverage (as listed above).[^1]
    - Extract shared normalization and ratio helpers inside core to remove duplication and improve clarity.[^1]
- This slice improves testability, reduces drift risk, and sets up the domain/control-plane refactor without changing public behaviour or external contracts.

If you want, next iteration can drill down into concrete class diagrams and sample interfaces for the hybrid (“Clean Domain + Control Plane Split”) design and align them with your Java 21/Spring Boot/OpenTelemetry coding style.

<div align="center">⁂</div>

[^1]: tracing-sampling-package-inventory.md

