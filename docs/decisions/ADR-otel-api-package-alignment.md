# ADR: OTel API Package Alignment (PA-0)

## Status

Accepted — PA-0 through PA-3 complete (plan v3.4).

## Context

После CP-3 R2 (`core.*` → `otel.*`) реализация span pipeline остаётся в `otel.manual..` (30 Java-файлов), что нарушает симметрию с `platform-tracing-api` (`api.span.*`). План выравнивания v3.4 утверждён: symmetric `otel.span.*`, два intentional public bridge, единый governed pipeline, `otel.exception` KEEP.

Baseline: `master@9b7f573`, public allowlist = **65 FQCN**, target после PA-1 = **63 FQCN** (65 − 6 + 4).

## Decision

### 1. Target taxonomy

```text
api.span           <-> otel.span
api.span.builder   <-> otel.span.builder
api.span.spec      <-> otel.span.spec
api.span.enrich    <-> otel.span.enrich
api.context        <-> otel.context        (PA-2)
```

**KEEP:** `otel.exception.*`  
**DELETE (PA-1):** `otel.manual*`, `otel.enrichment`, `otel.naming`, `ScopedExecution`

### 2. Preferred composition

```text
DefaultSpanFactory
  -> new DefaultSpanSpecFactory(runtime, policy)
  -> new DefaultSpanBuilderFactory(specFactory, traceparentReader)
```

Concrete `*BuilderImpl` **не хранят** `TracingRuntime` или `AttributePolicy`. Lifecycle делегируется в `DefaultSpanSpecFactory` → `SpanExecution`.

### 3. Allowed dependency graph

```text
otel.facade -> otel.span.DefaultSpanFactory

otel.span.DefaultSpanFactory
  -> otel.span.builder.DefaultSpanBuilderFactory
  -> otel.span.spec.DefaultSpanSpecFactory

otel.span.builder
  -> api.span.*
  -> otel.span.spec.DefaultSpanSpecFactory
  -> otel.propagation
  -X-> otel.runtime
  -X-> otel.semconv.policy

otel.span.spec
  -> api.span.spec.*
  -> otel.runtime
  -> otel.semconv.policy

otel.runtime -> otel.exception
otel.runtime -X-> otel.span..*
```

### 4. Bridge ABI (frozen PA-0)

#### Construction ABI (public ctors)

```java
// otel.span.spec.DefaultSpanSpecFactory
public DefaultSpanSpecFactory(@Nonnull TracingRuntime runtime,
                              @Nonnull AttributePolicy policy)

// otel.span.builder.DefaultSpanBuilderFactory
public DefaultSpanBuilderFactory(@Nonnull DefaultSpanSpecFactory specFactory,
                                 @Nonnull OtelTraceparentReader traceparentReader)

// otel.span.DefaultSpanFactory
public DefaultSpanFactory(@Nonnull TracingRuntime runtime,
                          @Nonnull AttributePolicy policy)
```

#### Operational ABI (no runtime/policy per call)

```java
// DefaultSpanSpecFactory
@Nonnull SpanExecution fromSpec(@Nonnull SpanSpec spec)
@Nonnull SpanExecution fromBuilderRawSpec(@Nonnull SpanSpec rawSpec,
                                          @Nonnull String builderName)

// DefaultSpanBuilderFactory
@Nonnull OperationSpanBuilder operation(@Nonnull String name)
@Nonnull TransportTracing transport()

// DefaultSpanFactory (SpanFactory)
@Nonnull OperationSpanBuilder operation(@Nonnull String name)
@Nonnull TransportTracing transport()
@Nonnull SpanExecution fromSpec(@Nonnull SpanSpec spec)
```

**Запрещено на public surface:**

- `executionFromGovernedSpec(SpanSpec)` или любой bypass «already governed»
- operational overloads с `TracingRuntime` / `AttributePolicy` параметрами

### 5. OPERATION-GOVERNANCE — Option A

**Approved:** единый governed pipeline для всех путей, включая INTERNAL operation.

| Path (baseline) | Count |
|---|---|
| `fromSpec` | 1 |
| semantic builders | 1 |
| INTERNAL operation (без governance сегодня) | 0 call sites, но path существует |

PA-1 intentionally исправляет operation path: `OperationSpanBuilderImpl` проходит через `DefaultSpanSpecFactory`, governance применяется один раз.

Delta evidence: см. `docs/analysis/platform-tracing-otel-api-alignment-evidence.md`.

### 6. Exact 63-FQCN target (frozen)

**Remove (6):**

```text
space.br1440.platform.tracing.otel.manual.DefaultSpanFactory
space.br1440.platform.tracing.otel.manual.DefaultTransportTracing
space.br1440.platform.tracing.otel.manual.spec.OperationSpanSpecs
space.br1440.platform.tracing.otel.manual.spec.SemanticSpanSpecs
space.br1440.platform.tracing.otel.naming.PlatformSpanNameBuilder
space.br1440.platform.tracing.otel.enrichment.DefaultSpanEnricher
```

**Add (4):**

```text
space.br1440.platform.tracing.otel.span.DefaultSpanFactory
space.br1440.platform.tracing.otel.span.builder.DefaultSpanBuilderFactory
space.br1440.platform.tracing.otel.span.spec.DefaultSpanSpecFactory
space.br1440.platform.tracing.otel.span.enrich.DefaultSpanEnricher
```

**Visibility demotions (4):** `DefaultTransportTracing`, `OperationSpanSpecs`, `SemanticSpanSpecs`, `PlatformSpanNameBuilder` → package-private.

Move manifest: [platform-tracing-otel-api-alignment-move-manifest.md](../analysis/platform-tracing-otel-api-alignment-move-manifest.md).

### 7. SAFE-BRIDGE-ABI verification (PA-0)

- Proposed-ABI fixture: `platform-tracing-otel` source set `pa0ProposedAbi`, jar task `pa0ProposedAbiJar`
- External consumer: `gradle/pa0-proposed-abi-consumer`, root task `pa0ProposedAbiConsumerVerify`
- Positive: operational ABI compiles без runtime/policy на вызове
- Negative: `executionFromGovernedSpec` не компилируется

## Consequences

- PA-1 unblocked после закрытия PA-0 exit block
- Production moves только в PA-1 по frozen manifest
- Builder isolation ArchUnit gates — PA-1
- Provenance-published JAR consumer — PA-1 (не PA-0 fixture)

## References

- [Move manifest](../analysis/platform-tracing-otel-api-alignment-move-manifest.md)
- [PA-0 evidence](../analysis/platform-tracing-otel-api-alignment-evidence.md)
- [ADR-public-api-allowlist](./ADR-public-api-allowlist.md)
- [ADR-platform-tracing-otel-api-exposure](./ADR-platform-tracing-otel-api-exposure.md)
