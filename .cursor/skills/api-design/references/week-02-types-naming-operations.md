# Week 02 — Types, Naming, and Operations

## When a concept deserves its own type

Justified if it:

- forbids invalid states
- distinguishes same technical representation
- fixes measurement unit
- defines ownership or lifecycle
- separates input, state, and result
- creates stable extension point
- expresses invariant once
- prevents parameter swap

Value types when distinction affects correctness, documentation, or evolution — not wrap every primitive.

## Primitive obsession and stringly typed API

Strings hide closed sets and grammars: typos late, no IDE completion, parsing becomes contract, order semantics unclear.

Not always `enum`:

- enum — truly closed set
- sealed hierarchy — closed variants with data
- value type + validation — open namespace
- registry key — third-party extensions
- parser boundary — config string → typed runtime model

## Make invalid unrepresentable

Prefer sealed variants over boolean combinations:

```java
sealed interface ExportTarget {
    record Otlp(URI endpoint) implements ExportTarget {}
    record InMemory(ExporterId id) implements ExportTarget {}
    record Disabled() implements ExportTarget {}
}
```

`@ConfigurationProperties` may differ from normalized runtime model — translation/validation step is normal.

## Do not publish parser model as domain model

Properties classes: nullable fields, setters, technical defaults = incomplete user input.

Separate:

- `TracingProperties` — external input, incomplete until validation
- `TracingSettings` — normalized immutable value
- `TracingRuntime` — assembled working components
- `TracingRuntimeView` — read-only observation if needed

## Semantic name density

Dense name: each word narrows interpretation. Density ≠ length.

Ask:

- what object/action?
- what role?
- what scope?
- what temporal semantics?
- what is excluded?
- does name match top invariant?

**Truth scope:** name must hold for all supported implementations/states.

Test: write "X is ..." in one sentence. Missing words in name → name under-specified.

## Naming axes

| Axis | Examples |
|---|---|
| Scope | Request, Application, Thread, Global |
| Time | Active, Pending, Completed, Snapshot |
| Role | Policy, Result, View, Builder, Factory |
| Ownership | Managed, Borrowed, Detached |
| Mechanism | Manual, AutoConfigured, Delegating |
| Domain | Trace, Span, Sampling, Propagation |

Include only axes that remove real ambiguity.

## Nouns and verbs

Nouns = stable concepts. Verbs = observable effects.

Weak verbs unless domain-defined: `process`, `handle`, `execute`, `apply`, `manage`, `do`, bare `configure`.

## Suffix promises

| Suffix | Expected semantics |
|---|---|
| Builder | mutable staged construction; result not yet created |
| Factory | creates instances; usually no business state |
| Provider | supplies value/instance, possibly lazy |
| Registry | named registrations + conflict rules |
| Customizer | limited modification of framework object |
| Policy | decision by explicit rules |
| Result | completed operation outcome |
| Snapshot | immutable point-in-time copy |
| View | limited observation, often live |
| Runtime | assembled components + lifecycle |
| Properties | external bindable configuration |

If suffix promise is violated, rename or fix behavior.

## Negative space

Good names exclude wrong interpretations:

- `ManualSpanBuilder` excludes auto-instrumentation, started execution, global tracer builder
- `ActiveTraceContextView` excludes ownership, mutation, snapshot semantics

## Operation semantics

Define:

- **preconditions** — true before call
- **postconditions** — guaranteed after success
- **invariants** — true in all observable states

## Command vs query

`getOrCreateContext()` mixes observation and effect. Split:

```java
Optional<ActiveTraceContextView> currentContext();
SpanExecution startRootSpan(SpanSpec spec);
```

## Temporal semantics

Clarify: return before completion?, snapshot vs live?, callback timing?, timeout scope?, `close` vs `shutdown` vs `awaitTermination`?

## Idempotence and repeat

Define explicitly: repeated `close()`, duplicate registration, retry ownership, partial failure repeat, callback multiplicity, registration conflict policy.

## Errors in the model

Classify by consumer action: fixable config, retryable, absent capability, programming error, internal provider failure with stable external type.

## Null, absence, empty, disabled

Distinguish when consumer action differs:

- `null`
- `Optional.empty()`
- empty collection
- disabled
- unavailable
- unsupported
