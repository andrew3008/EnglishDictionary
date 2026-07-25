# Неделя 06 — Generics, variance, erasure

## Generic parameter as relation

**Must:** type parameter связывает несколько позиций.

```java
interface Codec<T> {
    byte[] encode(T value);
    T decode(byte[] bytes);
}
```

**Must not:** decorative `Context<T>` without operations tying T — hides casts.

## Type parameter vs concrete type

**Must not:** `Exporter<Span>` if library only ever uses `Span` — false universality, inference pain.

## Bounds

**Must:** minimal bound necessary for operation.

**Must not:** public generic puzzle for internal reuse.

## Invariance (Java)

`List<Dog>` **is not** subtype of `List<Animal>` — mutation safety.

## Wildcards (use-site variance)

| Wildcard | Role |
|---|---|
| `? extends T` | producer — read T, limited write |
| `? super T` | consumer — write T, read as Object |
| `?` | unknown argument when relation unnecessary |

## PECS

Producer Extends, Consumer Super:

```java
void copy(List<? extends T> source, List<? super T> target)
```

**Must not:** PECS as rule for **return types** — returning wildcard forces consumer uncertainty.

**Must:** library returns concrete parameterized type when possible.

## Wildcard capture

**Must:** hide capture complexity in private helpers.

**Must not:** force consumers to solve capture for routine operations.

## Generic methods

Prefer generic **method** over generic **class** when relation needed for one operation:

```java
static <T> Optional<T> first(List<T> values)
```

### Inference failures

**Must not:** signatures requiring regular explicit type witnesses (`Factory.<A,B>create(...)`) — simplify signature or improve inference context.

## Generic varargs

**Must:** `@SafeVarargs` only with proof of safety.

**Must not:** suppress warnings without heap pollution analysis.

## Type erasure — API consequences

**Must account for:**

- no overload by type arguments only
- no `new T()` / `new T[]` ordinarily
- no `instanceof List<String>`
- reflection/DI/serialization may need type tokens
- bridge methods on override

## Reifiable vs non-reifiable

Reifiable: primitives, non-generic classes, raw types, unbounded `<?>`, some arrays.

Non-reifiable: `List<String>`.

## Heap pollution

Raw types, unchecked casts, generic varargs → late `ClassCastException`.

**Must not:** raw types in new public API.

**Must:** localize unchecked cast:

- single private helper
- runtime key/type invariant
- tests
- narrow suppression with explanation
- must not leak to public signature

## Type tokens

`Class<T>` — reifiable classes only.

Parameterized types — `Type`, `ParameterizedType`, anonymous subclass token, `TypeReference<T>`.

**Must not:** build reflective type system unless API requires universal serialization.

## Heterogeneous container pattern

```java
interface Key<T> {}
<T> void put(Key<T> key, T value);
<T> Optional<T> get(Key<T> key);
```

Safe only if all writes through controlled `put` and key fixes T.

## Key rules

1. Generic proves relation, not decoration.
2. Variance at usage boundaries; concrete returns.
3. Erasure limits runtime type checks — plan tokens.
4. Raw/unchecked at controlled private boundaries only.
5. Complexity in signatures must pay usability dividend.
