# Platform Tracing: OTel API Alignment — Move Manifest (PA-0 freeze)

> Baseline: `master@9b7f573`  
> Enforce: PA-1  
> Evidence criterion: `planned moves = actual moves`, `unexpected moves = 0`

## Census summary

| Area | Main sources | Test sources | Cross-module imports |
|---|---|---|---|
| `otel.manual..` | 14 | 16 | 0 |
| `otel.enrichment` | 2 | 2 | via facade/runtime only |
| `otel.naming` | 1 | 0 | internal to manual.spec |
| `otel.exception` | 2 | — | **KEEP** (runtime/Spring cycle) |

## Production moves (PA-1)

| Source FQCN / file | Target package | Visibility PA-1 | ABI action | Owner dependents |
|---|---|---|---|---|
| `manual.DefaultSpanFactory` | `otel.span` | public | relocate FQCN | `DefaultTraceOperations`, `NoopTraceOperations` |
| `manual.DefaultTransportTracing` | `otel.span.builder` | **package-private** | demote | `DefaultSpanFactory` only |
| `manual.spec.OperationSpanSpecs` | `otel.span.spec` | **package-private** | demote | builders via `DefaultSpanSpecFactory` |
| `manual.spec.SemanticSpanSpecs` | `otel.span.spec` | **package-private** | demote | builders via `DefaultSpanSpecFactory` |
| `naming.PlatformSpanNameBuilder` | `otel.span.spec` | **package-private** | demote | `SemanticSpanSpecs` |
| `enrichment.DefaultSpanEnricher` | `otel.span.enrich` | public | relocate FQCN | Spring autoconfigure |
| `enrichment.DefaultGenericSpanEnrichment` | `otel.span.enrich` | package-private | move | `DefaultSpanEnricher` |
| `manual.AbstractSemanticSpanBuilder` | `otel.span.builder` | package-private | move | semantic builders |
| `manual.OperationSpanBuilderImpl` | `otel.span.builder` | package-private | move | `DefaultSpanBuilderFactory` |
| `manual.DatabaseSpanBuilderImpl` | `otel.span.builder` | package-private | move | transport |
| `manual.DefaultHttpTracing` | `otel.span.builder` | package-private | move | transport |
| `manual.DefaultRpcTracing` | `otel.span.builder` | package-private | move | transport |
| `manual.DefaultKafkaTracing` | `otel.span.builder` | package-private | move | transport |
| `manual.SpanExecutionImpl` | `otel.span.spec` | package-private | move | spec factory |
| `manual.SpanSpecGovernance` | `otel.span.spec` | package-private | move | spec factory |
| `manual.UrlSanitizer` | `otel.span.builder` | package-private | move | HTTP builder |
| `manual.ScopedExecution` | — | — | **DELETE** | inline lifecycle in execution/builders |

## New public bridges (PA-1)

| FQCN | Package | Role |
|---|---|---|
| `DefaultSpanBuilderFactory` | `otel.span.builder` | builder bridge; no runtime/policy fields |
| `DefaultSpanSpecFactory` | `otel.span.spec` | spec/runtime/policy bridge; governed pipeline |

## Test moves (PA-1, follow production packages)

| Current test package | Target |
|---|---|
| `...otel.manual.*` (16 files) | `...otel.span.builder` / `...otel.span.spec` |
| `...otel.characterization.*` (manual refs) | update imports only |
| `...otel.span.SpanEnricherTest` | `...otel.span.enrich` |

## Explicit non-moves

| FQCN / area | Verdict | Reason |
|---|---|---|
| `otel.exception.*` | KEEP | cycle if moved under `otel.span` |
| `otel.facade.*` | KEEP | update import to `otel.span.DefaultSpanFactory` only |
| `otel.runtime..*` | KEEP (PA-3 audit) | no span back-deps |
| `api.context` types | PA-2 **DONE** | `CorrelationScope`, `ActiveTraceContextView` → `api.context` |

## File-level inventory (`otel.manual` main)

1. `AbstractSemanticSpanBuilder.java` → `otel/span/builder/`
2. `DatabaseSpanBuilderImpl.java` → `otel/span/builder/`
3. `DefaultHttpTracing.java` → `otel/span/builder/`
4. `DefaultKafkaTracing.java` → `otel/span/builder/`
5. `DefaultRpcTracing.java` → `otel/span/builder/`
6. `DefaultSpanFactory.java` → `otel/span/` (replaced by new composition)
7. `DefaultTransportTracing.java` → `otel/span/builder/`
8. `OperationSpanBuilderImpl.java` → `otel/span/builder/`
9. `ScopedExecution.java` → DELETE
10. `SpanExecutionImpl.java` → `otel/span/spec/`
11. `SpanSpecGovernance.java` → `otel/span/spec/`
12. `UrlSanitizer.java` → `otel/span/builder/`
13. `spec/OperationSpanSpecs.java` → `otel/span/spec/`
14. `spec/SemanticSpanSpecs.java` → `otel/span/spec/`
