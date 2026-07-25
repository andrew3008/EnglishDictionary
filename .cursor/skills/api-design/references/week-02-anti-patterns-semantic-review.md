# Week 02 — Anti-patterns, Semantic Review, Contract Map

## Anti-pattern: premature generalization

```java
interface PlatformComponent<C, R> { R execute(C configuration); }
```

Unifies sampler, exporter, masker by form only — lifecycles, effects, errors differ.

**Signs:** generic `T/C/R`, `execute/handle/process`, downcasts, optional methods, per-implementation docs, consumers know concrete subtype.

**Generalize by laws:** shared invariants, variation points, substitutability — not method shape repetition.

Rule of three is heuristic; two implementations may suffice if invariants clear; ten may not.

## Anti-pattern: false symmetry

Pairs like start/stop, register/unregister help only when truly reversible with comparable guarantees.

Examples:

- `unregister(id)` ≠ inverse of `register` after replacement
- `start/stop` asymmetric if runtime cannot restart
- encode/decode not strict inverses after normalization
- enable/disable mutates state when config should be immutable after startup

Prefer: `Registration registration = registry.register(provider); registration.close();`

## Anti-pattern: leaky abstraction

Consumer must know hidden mechanism to use public contract.

Examples: domain span with SDK lifecycle leaks; property values are internal FQCNs; no-op depends on internal bean name; async interface requires knowing internal queue size.

**Visible framework type may be intentional** when extension is explicitly SDK-coupled (e.g. `SdkTracerProviderCustomizer`).

Mark levels: stable domain API, framework integration API, advanced SDK SPI, internal.

Abstraction chooses responsibility — does not hide latency, failures, or resource limits.

## Anti-pattern: feature-oriented interface

```java
interface TracingManager {
    void enableBaggage();
    void enableNewSampler();
    void useAsyncExporter();
}
```

Methods encode backlog, not model. Signs: many booleans, new/legacy/advanced names, hidden feature dependencies, facade-only extension.

**Fix:** independent semantic axes — `PropagationFormat`, `SamplingPolicy`, `ExportTarget`, etc. Presets as convenience layer only.

## Anti-pattern: god facade

Facade becomes sole point for configuration, factories, registry, lifecycle, queries, commands, diagnostics, extensions.

Costs: excess authority, huge test doubles, every extension touches central type, Spring injection hides real dependency.

**Good facade:** one coherent scenario — e.g. `ManualTracing` with `start(SpanSpec)` and `activeContext()` only.

## Rename analysis method

For each rename document:

1. interpretations allowed by old name
2. which is actual
3. which word in new name removes ambiguity
4. new obligations created
5. implementation matches obligations

## Tracing rename examples

| Old | Ambiguity | New | Resolved |
|---|---|---|---|
| TraceContextView | which context, when | ActiveTraceContextView | current active vs arbitrary |
| TracingRequestContext | live/wrapper/data | RequestTraceContextSnapshot | immutable snapshot vs live |
| PlatformSpanBuilder | org origin vs role | ManualSpanBuilder | manual vs auto instrumentation |
| SpecifiedSpan | spec vs active | SpanExecution | lifecycle handle vs value |
| TracingImplementation | implementation of what | TracingRuntime | assembled runtime vs detail |

Rename without lifecycle/properties/thread-safety alignment is cosmetic.

## Semantic review — 8 steps

1. **Context statement** — one sentence purpose without class names
2. **Vocabulary** — nouns/verbs from types, methods, properties, beans, metrics, docs; group synonyms/ homonyms
3. **Role assignment** — role + key invariant per public type
4. **State/lifecycle diagrams** — map methods to transitions
5. **Scenario sentences** — describe use cases in public terms only
6. **Negative space** — what each major type is NOT
7. **Cross-surface integrity** — one concept across Java, config, Spring, ops
8. **Evolution check** — new impl without concept change?, names with new/legacy/v2?, rename migration cost

## Semantic Contract Map

Extends Public Contract Inventory with conceptual model.

Per concept record:

| Field | Content |
|---|---|
| Canonical term | primary name |
| Definition | one precise sentence |
| Role | entity/value/operation/policy/state/result/view |
| Invariants | always true properties |
| Lifecycle | states and transitions |
| Java expressions | types and methods |
| Configuration expressions | keys, defaults, validation |
| Spring expressions | beans, conditions, ordering |
| Operational expressions | metrics, health, logs |
| Exclusions | what concept is NOT |
| Evolution rule | compatible changes |

Focus on main concepts, extension points, lifecycle objects — not bureaucratic hundreds of rows.

## Typical semantic review mistakes

- rename-only review without model fix
- seeking universal industry glossary instead of local definitions
- copying framework terms without lifecycle check
- equating one Java type with one concept
- ignoring configuration language
- organizational names (Platform, Core, Common, V2)
- fixing terms too late — after adoption, names become compatibility contracts

## Relationship to Week 01

- **Public Contract Inventory** — what is published
- **Semantic Contract Map** — model linking published elements

Binary compatible changes can be semantic breaking (default change, rename with alias confusion).
