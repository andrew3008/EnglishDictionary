# platform-tracing-api PR-B1 Post-Audit

Date: 2026-07-11  
Repository: `E:\Platform_Traces`  
Auditor role: strict senior Java API architecture auditor  
Scope: PR-B1 context/propagation rename only (Batch B2/C must remain untouched)

---

## 1. Verdict

**PASS WITH WARNINGS**

PR-B1 is correctly implemented in Java source, SPI wiring, guards, ADR, and CHANGELOG. Targeted compile/tests are green. All seven expected public renames are present; old API symbols are absent from production Java.

Warnings are limited to stale references in historical/operational documentation (`docs/tracing/`, legacy ADRs) that still cite pre-PR-B1 propagator and context type names. Current user-facing manual API docs are updated.

---

## 2. Architecture Checks

| Check | Evidence | Result |
| --- | --- | --- |
| Context snapshot rename | `RequestTraceContextSnapshot` record in `api.context`; supplier `RequestTraceContextSnapshotSupplier` | PASS |
| Active context view rename | `ActiveTraceContextView` in `api.manual`; impl `DefaultActiveTraceContextView` in core | PASS |
| Inbound trace-control model | `DefaultInboundTraceControlExtractor.fromHeaders(...)` extracts from inbound carrier (`core.propagation.control`); `InboundTraceControl` record in api (no static factory) | PASS |
| Outbound propagation decision | `OutboundPropagationDecision` used by HTTP/Kafka outbound interceptors (`PlatformOutboundHttpInterceptor`, `PlatformKafkaProducerInterceptor`) | PASS |
| Trace-control header injector | `TraceControlHeaderInjector.inject()` writes outbound headers using `OutboundPropagationDecision` + `InboundTraceControl` from Context | PASS |
| Traceparent utility moved + renamed | `api.propagation.TraceparentParser` (`@UtilityClass`, static `parseTraceparent` / `requireTraceparent`) | PASS |
| Builder strict input rename | `ManualSpanBuilder.fromTraceparent(...)`, `SpanSpecBuilder.fromTraceparent(...)` | PASS |
| Extension propagator alignment | `InboundTraceControlPropagator`, `InboundTraceControlPropagatorProvider`; SPI service file updated | PASS |
| Old API files deleted | No `TraceContextView.java`, `TracingRequestContext.java`, `RemoteContext.java`, `PlatformTraceControl.java`, etc. | PASS |

---

## 3. Stale Name Search

Search method: workspace `Grep` across all `*.java` files.

| Old Name | Result | Notes |
| --- | --- | --- |
| `TracingRequestContext` | CLEAN in Java src | Replaced by `RequestTraceContextSnapshot` |
| `TraceContextView` | CLEAN in Java src | Replaced by `ActiveTraceContextView` |
| `PlatformTraceControl` | CLEAN in Java src | Replaced by `InboundTraceControl` |
| `PlatformPropagationDecision` | CLEAN in Java src | Replaced by `OutboundPropagationDecision` |
| `PlatformOutboundInjector` | CLEAN in Java src | Replaced by `TraceControlHeaderInjector` |
| `RemoteContext` | CLEAN in Java src | Replaced by `TraceparentParser` in `api.propagation` |
| `fromRemoteContext(...)` | CLEAN in Java src | Replaced by `fromTraceparent(...)` |
| `PlatformTraceControlPropagator*` | CLEAN in Java src | Extension classes renamed to `InboundTraceControlPropagator*` (consistent with inbound model) |
| `TracingRequestContextSupplier` | CLEAN in Java src | Renamed to `RequestTraceContextSnapshotSupplier` |

Stale names remain only in historical docs/archives (`docs/analysis/perplexity-review/`, legacy ADRs, some `docs/tracing/` operational notes).

---

## 4. New API Shape

### Context pair (active vs snapshot)

```java
// Live read-only view — traceOperations.traceContext()
public interface ActiveTraceContextView {
    Optional<String> traceId();
    Optional<String> spanId();
    Optional<String> correlationId();
}

// Nullable captured snapshot — error-handling integration
public record RequestTraceContextSnapshot(
    @Nullable String correlationId,
    @Nullable String traceId,
    @Nullable String spanId) {}
```

Semantic separation is correct: snapshot is request-scoped captured data for error models; active view is the live correlation surface.

### Inbound / outbound propagation model

```java
// Inbound extraction from carrier headers (core.propagation.control)
public interface InboundTraceControlExtractor {
    InboundTraceControl fromHeaders(String traceOn, String qaTrace, String requestId);
}

// Outbound trusted-destination policy result (api.propagation.control)
public record OutboundPropagationDecision(
    boolean propagateForceTrace,
    boolean propagateQaTrace,
    boolean propagateRequestId) {}

// Outbound writer — trace-control headers only (api interface; core Default* impl)
public interface TraceControlHeaderInjector {
    <C> void inject(Context context, C carrier, TextMapSetter<C> setter);
}
```

Direction semantics are correct:
- **Inbound:** `InboundTraceControlPropagator.extract()` populates Context from incoming carrier.
- **Outbound:** client interceptors compute `OutboundPropagationDecision`, then `TraceControlHeaderInjector` writes allowed headers.

### Traceparent parsing

```java
// api.propagation — parser utility, not a value object
@UtilityClass
public final class TraceparentParser {
    public static Optional<SpanLinkContext> parseTraceparent(@Nullable String traceparent);
    public static SpanLinkContext requireTraceparent(@Nonnull String traceparent);
}
```

`TraceparentParser` is **not** a misnamed value object:
- `@UtilityClass` + `final` class with private ctor pattern
- Only static parse/validate methods
- Output type is `SpanLinkContext` (the actual link value)
- Javadoc explicitly distinguishes soft `parseTraceparent` vs strict `requireTraceparent` for builder `fromTraceparent(...)`

Package move is correct: `api.propagation`, not `api.span`.

---

## 5. Forbidden Change Check

| Forbidden item | Evidence | Result |
| --- | --- | --- |
| Batch B2: `SensitiveDataRule` rename | `api.spi.SensitiveDataRule` still present; no `SpanAttributeScrubbingRule` | PASS |
| Batch C: `SpanLinkContext` → `RemoteSpanLink` | `SpanLinkContext` unchanged | PASS |
| Batch C: `SpanAttributeValue` → `SpanSpecAttributeValue` | `SpanAttributeValue` unchanged | PASS |
| Control protocol topology names | `TracingControlProtocolFieldCategory.STARTUP_TOPOLOGY` etc. untouched | PASS |
| Batch A relationship names | `SpanRelationship`, `SpanRelationshipSpec`, `fromTraceparent` coexist correctly | PASS |

---

## 6. Docs / ADR / Changelog Review

| Artifact | Expected | Evidence | Result |
| --- | --- | --- | --- |
| PR-B1 ADR | Exists with rationale | `docs/decisions/ADR-api-naming-refactor-pr-b1.md` | PASS |
| CHANGELOG PR-B1 section | old → new table | `platform-tracing-api/CHANGELOG.md` lines 22-34 | PASS |
| User-facing manual API doc | Post-B1 names | `docs/tracing/platform-tracing-v3-manual-api.md` uses `ActiveTraceContextView`, `fromTraceparent` | PASS |
| Kafka batch links doc | `TraceparentParser` | `docs/tracing/platform-tracing-v3-kafka-batch-links.md` updated | PASS |
| Inventory footer | PR-B1 accepted update | `platform-tracing-api-class-hierarchy-inventory.md` § PR-B1 Accepted Update | PASS |
| Naming-options footer | PR-B1 semantic verdicts | `platform-tracing-api-model-naming-options.md` § PR-B1 Accepted Update | PASS |
| Operational tracing docs | No stale API refs | `docs/tracing/otel-compatibility-matrix.md`, `requirements-coverage-dossier.md`, `phase-12-propagation-research-review.md` still cite `PlatformTraceControlPropagatorProvider`, `PlatformTraceControl` | WARN |
| Legacy ADRs | May contain archaeology | `ADR-context-first-propagation.md`, `ADR-named-spi-sampler-propagator.md`, `ADR-sampler-compose.md` still use old type names | WARN (acceptable as historical, but confusing) |
| Inventory body package note | `TraceparentParser` location | Line 283 lists `TraceparentParser` under `span` utility; actual package is `api.propagation` | WARN |

---

## 7. Test / Guard Review

| Guard / Test | Expected | Evidence | Result |
| --- | --- | --- | --- |
| Traceparent parser tests | `TraceparentParserTest` in `api.propagation` | Exists; tests `parseTraceparent` / `requireTraceparent` | PASS |
| Snapshot supplier test | `RequestTraceContextSnapshotSupplierTest` | Exists in autoconfigure errorhandling tests | PASS |
| Header injector test | `TraceControlHeaderInjectorTest` | Exists in `api.propagation.control` tests | PASS |
| Propagator tests | `InboundTraceControlPropagatorTest`, `InboundTraceControlPropagatorProviderTest` | Exist; SPI name `platform-trace-control` preserved | PASS |
| Builder integration | `KafkaConsumerBatchLinksTest`, `KafkaBatchSpanBuilderIntegrationTest` | Use `fromTraceparent` + `TraceparentParser` | PASS |
| Metered topology matrix | Spring bean topology test | `MeteredTopologyMatrixTest` uses `fromTraceparent` — span relationship, not renamed (correct) | PASS |

---

## 8. Gradle Verification

Commands executed:

```bash
.\gradlew.bat compileJava compileTestJava \
  :platform-tracing-api:test \
  :platform-tracing-core:test \
  :platform-tracing-spring-boot-autoconfigure:test \
  :platform-tracing-otel-extension:test \
  :platform-tracing-autoconfigure-webmvc:test \
  :platform-tracing-autoconfigure-webflux:test
```

| Step | Result |
| --- | --- |
| `compileJava` / `compileTestJava` | BUILD SUCCESSFUL |
| `:platform-tracing-api:test` | SUCCESS |
| `:platform-tracing-core:test` | SUCCESS |
| `:platform-tracing-spring-boot-autoconfigure:test` | SUCCESS |
| `:platform-tracing-otel-extension:test` | SUCCESS |
| `:platform-tracing-autoconfigure-webmvc:test` | SUCCESS |
| `:platform-tracing-autoconfigure-webflux:test` | SUCCESS |

---

## 9. Findings

### P0

None. All PR-B1 public renames are implemented; Java sources are clean; semantics and direction model are correct; `TraceparentParser` is a legitimate parser utility rename.

### P1

1. **Operational tracing docs still reference pre-PR-B1 propagator class names** — `docs/tracing/otel-compatibility-matrix.md` and `docs/tracing/requirements-coverage-dossier.md` cite `PlatformTraceControlPropagatorProvider` / `PlatformTraceControlPropagatorTest`; Java now uses `InboundTraceControlPropagatorProvider`. SPI name `platform-trace-control` is unchanged, but class-path references in ops docs are stale.

2. **`docs/tracing/phase-12-propagation-research-review.md` still lists `PlatformTraceControl`** as current API type. Misleading for onboarding after PR-B1.

### P2

1. **Legacy ADRs retain old vocabulary** — `ADR-context-first-propagation.md`, `ADR-named-spi-sampler-propagator.md`, `ADR-sampler-compose.md`. Acceptable as decision archaeology per PR-B1 ADR note, but cross-links should eventually point to `ADR-api-naming-refactor-pr-b1.md`.

2. **Inventory body package typo** — `platform-tracing-api-class-hierarchy-inventory.md` lists `TraceparentParser` under `span` utilities; actual FQN is `api.propagation.TraceparentParser`.

3. **Analysis doc bodies still contain pre-decision rename scoring** — `platform-tracing-api-model-naming-options.md` body vs accepted footer; not blocking.

---

## 10. Recommendation

**PR-B1 is ready to commit** from a code, SPI, and test perspective.

Before external publication or onboarding new teams, fix P1 operational doc drift in `docs/tracing/` (propagator provider class names and `InboundTraceControl` references). User-facing API reference docs are already correct.

**Batch B2 can start** after PR-B1 merge. `SensitiveDataRule` and other Batch B2/Batch C symbols remain untouched as required.

Suggested commit message scope: `refactor(api): PR-B1 context/propagation naming — ActiveTraceContextView, InboundTraceControl, TraceparentParser`.
