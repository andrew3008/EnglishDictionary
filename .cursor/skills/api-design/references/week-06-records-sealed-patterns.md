# Неделя 06 — Records, enum, sealed, pattern matching

## Records

### Must утверждает record

Header = полное состояние; fields, accessors, canonical constructor, equals, hashCode, toString — mechanical.

**Must:** validation, normalization, domain methods, interface implementation — допустимы.

**Must not:** record для случайной internal structure.

### Shallow immutability

**Must:** `List.copyOf` / defensive copy; clarify accessor mutability of elements.

### Equality и canonicalization

Compact constructor normalization → equality on canonical state.

**Must not:** silent loss of distinctions important to consumer.

### Evolution cost

Adding component → major change (constructor, accessors, equality, patterns, serialization).

## Record patterns (Java 21)

```java
if (result instanceof Found(User user, Metadata metadata)) { ... }
```

**Must understand:** patterns increase coupling to representation and component order.

**Must not:** side effects or exceptions in overridden accessors used by patterns.

Nested patterns — expressive but **must not** для frequently changing models; prefer methods/polymorphism.

## Enum

**Must enum, когда:** closed set без variant-specific runtime payload; behavior parameterized uniformly.

**Must not:** enum + nullable conditional fields = hidden sum type.

### Enum с данными

Constant-specific fields — only if set closed and data static.

Runtime-specific payload per variant → **must** sealed records.

### Adding constant

**Must treat as compatibility decision:** exhaustive switch, serializers, DB constraints, default semantics — not automatic minor.

### External unknown values

**Must not:** direct parse to enum if forward compatibility needed.

**Must:** `Known`/`Unknown(wireValue)` or preserve raw value.

## Sealed hierarchies (sum types)

```java
public sealed interface LookupResult<T>
        permits Found, NotFound, Failed {
    record Found<T>(T value) implements LookupResult<T> {}
    record NotFound<T>() implements LookupResult<T> {}
    record Failed<T>(LookupFailure failure) implements LookupResult<T> {}
}
```

**Must sealed, когда:** mutually exclusive variants, different data/invariants, exhaustive processing, single concept evolution.

### `final` / `sealed` / `non-sealed`

Each permitted subtype **must** continue extensibility policy.

**Must not:** `non-sealed` branch without accepting loss of global exhaustiveness below that point.

### Package/module locality

Permitted subtypes — same module (named) or same package (unnamed). **Must** plan modular placement.

### Must not sealed

- third-party implementations are goal
- open variant set by nature
- routine extension of variants expected
- consumer must not know implementation taxonomy

## Pattern matching for switch

```java
return switch (result) {
    case Found<?> f -> "found";
    case NotFound<?> n -> "not-found";
    case Failed<?> f -> "failed: " + f.failure();
};
```

Compiler checks exhaustiveness for sealed hierarchies.

### Expression problem

| Need | Prefer |
|---|---|
| Add operations to closed data variants | pattern switch |
| Add implementations to stable behavioural interface | dynamic dispatch |

### `default` case

**Must not:** `default` on closed model if exhaustiveness is the point — hides new variants.

Use `default` only with explicit forward fallback policy.

### `null` case

Explicit `null` handling **must not** replace modeling absence as domain variant when semantics differ.

## Product vs sum (алгебра)

| Form | Meaning | Combinatorics |
|---|---|---|
| Product (record/class) | A **и** B | multiply |
| Sum (sealed/enum) | A **или** B | add |

```java
// Must not if only Disabled + Http + InMemory valid
record Config(boolean enabled, URI endpoint, String channel) {}

// Must
sealed interface Config {
    record Disabled() implements Config {}
    record Http(URI endpoint) implements Config {}
    record InMemory(String channel) implements Config {}
}
```

**Must:** algebraic review при state explosion.

## Anti-patterns

| Anti-pattern | Must not |
|---|---|
| Interface for everything | indirection without extensibility scenario |
| Record for everything | unstable representation as API |
| Enum + nullable payload cluster | hidden sum |
| Sealed SPI | forbids third-party impl while named extension point |
| Catch-all enum `OTHER` | destroys diagnostics |
