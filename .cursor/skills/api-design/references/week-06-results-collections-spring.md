# Неделя 06 — Optional, results, collections, Spring types

## Optional

**Must:** zero-or-one result when absence is normal and reason irrelevant.

```java
Optional<Route> findRoute(RouteId id);
```

### Must not Optional

- method parameters (two APIs in one)
- configuration property fields
- serializable DTO components
- collections of Optional
- failure representation
- complex absence with multiple causes

### Must not replace domain variants

`Optional<Duration>` **must not** express inherit/disabled/explicit — use sealed setting.

## Nullability policy

Java types without extra tooling **must not** assume null distinction.

**Must:** choose consistent policy: non-null by default, explicit boundary annotations, runtime checks, tooling alignment.

**Must not:** mix incompatible annotation ecosystems without documented mapping.

## Result types

**Must sealed result**, when outcomes expected and consumer must analyze branches:

```java
sealed interface PublishResult {
    record Published(MessageId id) implements PublishResult {}
    record Rejected(Rejection reason) implements PublishResult {}
    record Deferred(Duration retryAfter) implements PublishResult {}
}
```

### Exceptions

**Must:** exceptions remain behavioral contract.

**Must not:** generic `Result<T,E>` if domain variants have distinct data/evolution — prefer domain-specific sealed result.

**Must not:** Result for programming errors and unrecoverable internal defects.

### Failure data

**Must:** stable domain failure types; technical cause for logs/diagnostics, not necessarily public third-party exception types.

## Collections in public API

### Interface choice

**Must match operations contract:**

| Type | Promises |
|---|---|
| `Collection` | no order guarantee |
| `List` | order, duplicates allowed |
| `Set` | uniqueness |
| `Map` | key association |

**Must not:** mechanically «most general interface» without semantics.

### Mutability

Return `List<T>` **must not** silently mean mutable/snapshot/live.

**Must:** `List.copyOf` for unmodifiable snapshot; document live views.

### Empty vs null

**Must:** empty collection for «computed but empty».

**Must not:** null for empty list cardinality.

`Optional<List<T>>` only if must distinguish «not computed» vs «computed empty».

### Variance in signatures

- bulk read inputs: `Collection<? extends T>`
- callbacks accepting writes: `? super T`
- returns: concrete parameterization without wildcard when possible

## Arrays and primitives

**Must understand:** arrays covariant + reified vs generics invariant + erased.

**Must not:** expose mutable arrays without copy/ownership contract.

Prefer immutable lists for object collections.

**Must not:** proliferate primitive specializations without measured need.

Boxed `Boolean`/`Integer` for tri-state — **must** prefer explicit enum/setting in runtime model if null is domain meaning.

## Marker interfaces vs annotations

Marker interface — when marking affects statically available operations.

Annotation — metadata; **must not** assume processor active.

Meta-annotation contract (`@Target`, `@Retention`) changes = compatibility concern.

**Must:** keep framework annotations in adapter module if core independence matters.

## Phantom types / typestate

`Request<Draft>` → `Request<Validated>` — high cost: erasure, verbosity, inference, casts.

**Must:** only short linear protocols, high sequencing error cost, static known states.

**Must not:** phantom typestate for long-lived Spring beans — dynamic state machine honest.

## Composition vs inheritance

**Must prefer:** delegating wrapper for added behavior.

Decorator **must** preserve interface laws: lifecycle, exceptions, ordering, thread safety.

Sealed inheritance for **taxonomy**, not code reuse — **must not** mix reuse + taxonomy without need.

## Dependency leakage

Public signature with vendor type (`io.opentelemetry...`) **must** be deliberate dependency contract.

**Must not:** one-to-one wrapper without own semantics — conversion burden without abstraction.

Adapter module layout:

```text
platform-api     → domain types
platform-otel    → OTel adapters
platform-spring  → config/beans
```

## Spring configuration types

### Bindable vs runtime

**Must not:** use `@ConfigurationProperties` as runtime dependency with weak nullability.

**Must:** resolver → normalized sealed/value model.

### External enum

Convenient for metadata; **must** unknown strategy if namespace open or cross-version config.

### Type metadata

Generics, nested props, enum allowed values, deprecations, defaults — **must** complete for IDE API.

## Bean types

Injection type **must** express intended capability; implementation class as bean type may become effective API.

Multiple candidates — **must** explicit model: `List`, `Map`, `ObjectProvider`, qualifier, `@Primary`, ordering.

`@ConditionalOnMissingBean` on interface — replacement/SPI contract; nominal substitution insufficient.

Marker bean — **must not** unless presence is compositional capability; prefer property/intent annotation.

## Key rules

1. Optional = cardinality, not failure/state.
2. Sealed results for expected branches.
3. Collection type encodes order/uniqueness/mutability/snapshot.
4. Vendor types in signature = dependency contract or leakage.
5. Bindable Spring types ≠ strong runtime types.
