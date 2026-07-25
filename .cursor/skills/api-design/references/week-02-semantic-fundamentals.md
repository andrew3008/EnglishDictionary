# Week 02 — Semantic Fundamentals

## Context

Week 01 defined API as contract surfaces. Week 02 asks: **what conceptual model does the API teach the consumer?**

Signature says what can be called. Semantics says what the call means.

## Three design levels

| Level | Question | Typical decisions |
|---|---|---|
| Syntax | How does consumer express the call? | methods, parameters, generics, builders |
| Semantics | What does the call mean? | concepts, invariants, states, effects, errors |
| Pragmatics | How is API used in real context? | discoverability, Spring integration, diagnostics, migration |

Serious review starts with semantics, not builder ergonomics.

## API as executable ontology

API fixes: which concepts exist, how they differ, allowed operations, possible states, valid transitions, observable facts.

Bad: one `TracingManager` with `configure`, `create`, `process`, `handle`, `getContext` — complexity hidden behind uninformative surface.

Good: distinct concepts — active trace context, request snapshot, span spec, span execution, sampling policy, tracing runtime.

## Rule of the week

**Define concepts and boundaries first, then choose Java form.**

`interface`, `record`, `enum`, builder, annotation encode the model; they do not create it.

## Semantic defects cost more than syntax defects

Wrong concept split propagates into: public types, configuration keys, bean topology, extension points, tests, metrics, logs, documentation, mental models.

Example: naming a lifecycle handle `Span` when it is `SpanExecution` forces rename plus ownership, thread-safety, and completion review.

## Conceptual integrity

Parts express a coherent set of concepts, rules, and design decisions. Consumer can transfer expectations across API areas.

Coherent API answers consistently:

- what is absence of value
- how errors are expressed
- who owns lifecycle
- where policy applies
- which objects are mutable
- how snapshots and views are named
- whether `create` means description or resource start
- what `enabled=false` means (no beans, passive bean, or no-op)

**Integrity ≠ regularity.** Same verb for different lifecycles hides semantics:

```java
// False symmetry
createSpan(...); createSampler(...); createExporter(...);

// Integrity over form
startSpan(...);
samplingPolicy();
openExporter(...);
```

## Local vs global integrity

Global integrity spans Java API, configuration, Spring, and operational surfaces.

Bad: `SamplingPolicy` in Java, `sampler-strategy` in properties, `traceDecisionProvider` bean, `tracing_sampler_choices_total` metric, "sampling mode" in docs — five vocabularies.

Good: one root term `sampling` with explicit roles per surface.

## Transfer-of-expectation test

Derive a rule from one API area and apply elsewhere:

- all `*Snapshot` types are immutable
- all `start*` return closable handles
- all `*Policy` types own no I/O resources
- all `enabled=false` disable entire capability
- all `*View` are read-only live observations

If rules break often, suffixes are decorative, not language.

## Key theses

1. API is a small language, not only signatures.
2. Semantics before syntax.
3. Conceptual integrity beats external symmetry.
4. Semantic errors fixate across all surfaces.
