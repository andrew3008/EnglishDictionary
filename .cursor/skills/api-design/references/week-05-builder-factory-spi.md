# Неделя 05 — Builder, factory object, SPI, registry

## Builder

### Must использовать, когда

- много optional parameters
- readable named-style configuration
- immutable result
- межполевые invariants в одной точке
- blueprint reuse / copy-modify
- constructor signature error-prone

### Must not

- builder для mutually exclusive modes (→ named factories / variant builders)
- builder без documented contract (reuse, last-write-wins, thread safety, validation point)
- universal builder скрывающий sum type

### Required values in entry point

**Must:**

```java
ExportPipeline.builder(SpanExporter exporter, ExportQueue queue)
        .batchSize(256)
        .build();
```

### Defaults builder

**Must:** совпадать с convenience factories и configuration defaults **или** концептуально объяснённое различие.

Defaults = compatibility contract.

### Fluent traps

**Must not:** fluent chain, где `endpoint()`/`compression()` неприменимы к текущему mode без compile-time check.

**Must:** variant-specific builders или factories.

### Collections on builder

**Must define:** `add` vs replace-all, ordering, duplicates, copying.

## Staged builder / typestate

**Must:** короткий стабильный протокол, высокая цена sequencing error, compiler предотвращает реальный класс ошибок.

**Must not:** обычная конфигурация; branching protocol; только чтобы избежать validation exception в `build()`.

## Self-typing builder hierarchies

**Must prefer:** composition (immutable options value) over public builder inheritance.

**Must not:** public inheritance builders без явного SPI — name clashes, generic complexity, evolution cost.

## Copy: `toBuilder()` vs `withX()`

| Форма | Когда |
|---|---|
| `withTimeout(x)` | одна независимая замена; atomic valid result или reject |
| `toBuilder()` | несколько изменений; invalid до `build()` допустим |

**Must define:** deep vs shallow copy, shared references, derived fields recalculation.

`copyOf` may return same instance if canonical immutable — must not нарушать expectations silently.

## Factory object (injectable)

**Must:** creation зависит от injected deps, policy, environment, cache, runtime context.

```java
interface SpanFactory {
    SpanExecution start(SpanSpec spec);
}
```

Factory = capability, не utility class — **must** документировать scope, thread safety, lifecycle.

**Must not:** factory принимает `SdkSpanBuilder` — abstraction leaks.

### Failure taxonomy

**Must not:** один `CreationException` для programming error, policy rejection, environmental failure.

## Abstract factory (семейства)

**Must:** objects в family из-за общего invariant, не потому что в одном модуле.

**Must not:** god factory с несвязанными methods.

Evolution: sub-factories, default methods (осторожно), immutable component records, versioned SPI.

## Registry

```java
ExporterProvider provider = registry.require(mode);
SpanExporter exporter = provider.create(configuration);
```

**Must define:** key grammar, case, namespace, duplicates, ordering, unknown key diagnostics, enumerate providers, thread safety, **freeze point**.

**Must not:** registration после first lookup без explicit policy.

## SPI и ServiceLoader

### Provider contract must

- accessible constructor или framework-specific creation
- `name()`, `supports()`, `create(config, context)`
- lifecycle, threading, error propagation в docs + contract tests

### ServiceLoader must define

- lazy vs eager discovery
- broken provider handling
- duplicate capabilities
- ordering
- classloader boundary
- caching
- module-path requirements
- observability discovered providers

**Must not:** полагаться на ServiceLoader для selection policy.

### ProviderContext

**Must:** minimal stable set для callback.

**Must not:** lookup всего container.

### Test kit

Extension author **must** иметь contract tests: lifecycle, failures, thread safety, metrics, compatibility fixtures.

## Caching и identity

**Must:** document, если factory returns cached instance; consumer must not rely on `==` unless promised.

**Must not:** скрытый cache resource-owning runtimes (classloader leaks, tenant isolation, close semantics).

## Ownership inputs

| Semantics | Must define |
|---|---|
| borrow | ссылка без close |
| copy | independent representation |
| take ownership | creator closes |
| share | joint protocol |

```java
builder.borrowedExecutor(executor)
builder.ownedExecutor(executor)
```

**Must:** ownership transfer point + cleanup on creation failure.

## Async creation

`CompletionStage<Client> connectAsync(...)` — **must define:** executor, cancellation, timeout, partial resources, context propagation, ownership after exceptional completion.
