# Week 02 — Ubiquitous Language and Conceptual Roles

## Ubiquitous language

Library has its own domain: tracing context, Kafka retry, MDC masking, leader election, resilience policies.

If team says "manual span", docs say "custom span", class is `SpecifiedSpan`, property is `additional-span` — the concept is unstable.

Language must appear in: packages/modules, public types/methods, Javadoc, configuration metadata, validation messages, public bean names, metrics/health, migration guides, contract tests.

## Words mean nothing without bounded context

`Context` is dangerous — may mean propagation context, request metadata, Spring `ApplicationContext`, execution context, attribute bag, thread-local receiver.

`TracingContext` is too broad. Narrow with role:

- `ActiveTraceContextView` — observe current active context
- `RequestTraceContextSnapshot` — captured request-level values
- `TracePropagationContext` — carrier-independent propagation data
- `SpanCreationContext` — inputs for span creation

Qualifiers must narrow meaning, not add length.

## One term — one primary concept

Avoid one word for multiple roles in same API:

- `TracingConfiguration` as value, `configure()` as action, `@Configuration` as Spring class

Prefer:

- `TracingProperties` — external bindable values
- `TracerProviderCustomizer` — extension operation
- `TracingAutoConfiguration` — Spring registration
- `TracingRuntimeSettings` — normalized runtime settings (if needed)

## Synonym discipline

`strategy`, `policy`, `provider`, `resolver`, `selector` must mean distinct things or be removed.

Example glossary:

| Term | Meaning in library | Does NOT mean |
|---|---|---|
| Policy | decision by rules | orchestration service |
| Strategy | replaceable execution algorithm | |
| Provider | supplies instance/value, possibly lazy | |
| Resolver | incomplete input → unambiguous result | |
| Selector | picks one from known set | |

Definitions are local to the library; consistency matters more than industry universality.

## Semantic glossary template

| Term | Exact meaning | Does NOT mean |
|---|---|---|
| SpanSpec | immutable description of not-yet-started span | active span, builder, runtime handle |
| SpanExecution | started execution requiring completion | spec, exported span |
| SamplingPolicy | record/drop decision before recording span | exporter filter, collector tail sampling |
| Snapshot | autonomous immutable point-in-time value | live view |
| Runtime | assembled working components + lifecycle | configuration properties |

Essential when wrapping external frameworks — separates published language from implementation terms.

## Seven conceptual roles

| Role | Represents | Key question |
|---|---|---|
| Entity | identity + history over time | Same object after change? |
| Value | content-defined, interchangeable | Equal instances interchangeable? |
| Operation | action or process | Effect and completion? |
| Policy | decision rule | Behavior for same inputs? |
| State | lifecycle position | Allowed transitions? |
| Result | fixed operation outcome | What already happened? |
| View | limited perspective on foreign state | Live or snapshot? |

If a type is configuration + runtime + result + service locator — problem is in the model.

## Role notes

**Entity:** identity matters; states; ownership/lifecycle essential. Public `record` pushes value semantics — may be wrong for managed exporter.

**Value:** immutable, interchangeable, validated at creation, no hidden lifecycle, meaningful equality.

**Operation:** define effects, blocking, async, idempotency, repeat, completion, partial success, cleanup. Prefer domain verbs over `execute/process/handle`.

**Policy:** returns decision value; no heavy lifecycle; composable; clear determinism. Not `Manager`/`Handler`.

**State:** prefer enum + defined transitions over contradictory booleans.

**Result:** sealed hierarchy beats `boolean` when failure modes differ.

**View:** explicit live vs snapshot. `View` often live; `Snapshot` immutable point-in-time.

## Classification workflow

For each public type: one role word + key invariant. If role unclear, investigate deeper before stabilizing API.
