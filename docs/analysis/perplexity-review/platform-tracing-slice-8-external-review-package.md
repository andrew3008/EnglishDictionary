# TraceOperations Slice 8 External Review Package

> **Self-contained bundle.** This single Markdown file contains the **full inline text** of every Slice 8 documentation file, ADR, sample source, and build snippet. **No separate attachments or repository access is required.**

| Field | Value |
|---|---|
| Git commit | `b80d5d9` |
| Branch | `master` |
| Inline file count | 16 |
| `:platform-tracing-samples:compileJava` | GREEN |
| `.\gradlew.bat build` | GREEN |
| Cursor pre-review verdict | ACCEPT WITH DOC FIXES (M1, M2) |

## How to read this file

1. Read the executive summary below.
2. Use the table of contents to jump to any embedded file section.
3. Each section labeled `INLINE COPY:` contains the **complete file contents**, not a filesystem link.
4. Cross-references like `[Migration guide](./platform-tracing-v3-migration-guide.md)` inside embedded docs refer to other sections in **this same bundle** (search by filename).

## Executive summary

TraceOperations v3 refactoring is complete through Slice 8. Production tracing code was not changed in Slice 8.

```text
TraceOperations public API = traceContext() + manual()
Auto-instrumentation is the default; manual() only when auto-instrumentation is not enough
TracingImplementation.startSpan(SpanSpec) is the single creation boundary
No v1 API / SpanRelation / MeteredPlatformTracing / Facade*SpanBuilder / compatibility shim
Kafka batch = ROOT + links; -PrunE2e gated, not default build
```

## Known doc fixes to verify (M1, M2)

| ID | Embedded section | Issue | Expected fix |
|---|---|---|---|
| M1 | Kafka batch links doc | Says `@Traced` batch aspect | `KafkaBatchLinksAspect` around `@KafkaListener(batch="true")` |
| M2 | Kafka batch links doc + ADR | Fallback `@KafkaBatchLinks` method name | advised Java method name |

## Table of contents — full inline copies
- [docs/analysis/perplexity-review/platform-tracing-slice-8-lightweight-review.md](#file-docs-analysis-perplexity-review-platform-tracing-slice-8-lightweight-review-md)
- [docs/tracing/platform-tracing-v3-getting-started.md](#file-docs-tracing-platform-tracing-v3-getting-started-md)
- [docs/tracing/platform-tracing-v3-migration-guide.md](#file-docs-tracing-platform-tracing-v3-migration-guide-md)
- [docs/tracing/platform-tracing-v3-manual-api.md](#file-docs-tracing-platform-tracing-v3-manual-api-md)
- [docs/tracing/platform-tracing-v3-kafka-batch-links.md](#file-docs-tracing-platform-tracing-v3-kafka-batch-links-md)
- [docs/tracing/platform-tracing-v3-observability-and-diagnostics.md](#file-docs-tracing-platform-tracing-v3-observability-and-diagnostics-md)
- [docs/tracing/platform-tracing-v3-production-readiness.md](#file-docs-tracing-platform-tracing-v3-production-readiness-md)
- [docs/decisions/ADR-platform-tracing-v3-public-api.md](#file-docs-decisions-adr-platform-tracing-v3-public-api-md)
- [docs/decisions/ADR-platform-tracing-span-spec-governance.md](#file-docs-decisions-adr-platform-tracing-span-spec-governance-md)
- [docs/decisions/ADR-platform-tracing-topology-links.md](#file-docs-decisions-adr-platform-tracing-topology-links-md)
- [docs/decisions/ADR-platform-tracing-metering-spi-boundary.md](#file-docs-decisions-adr-platform-tracing-metering-spi-boundary-md)
- [docs/decisions/ADR-platform-tracing-kafka-batch-links.md](#file-docs-decisions-adr-platform-tracing-kafka-batch-links-md)
- [docs/decisions/ADR-platform-tracing-micrometer-observation-boundary.md](#file-docs-decisions-adr-platform-tracing-micrometer-observation-boundary-md)
- [platform-tracing-samples/src/main/java/space/br1440/platform/tracing/samples/PlatformTracingV3Samples.java](#file-platform-tracing-samples-src-main-java-space-br1440-platform-tracing-samples-platformtracingv3samples-java)
- [platform-tracing-samples/build.gradle](#file-platform-tracing-samples-build-gradle)
- [settings.gradle (excerpt)](#file-settings-gradle-excerpt)

## External review prompt

```text
Act as a principal Java platform architect, Spring Boot observability expert,
OpenTelemetry/Micrometer reviewer, and senior technical documentation reviewer.

Review the attached TraceOperations Slice 8 documentation/sample package.
This file IS the package - all docs/ADRs/sample sources are inline below.
Focus on Slice 8 docs, ADRs, and sample module only.
Verify M1/M2 doc fixes. Return Executive verdict, Blockers, Required doc fixes,
architecture consistency, Kafka batch assessment, sample assessment, production readiness assessment.
```

---

# Full inline file copies

<a id="file-docs-analysis-perplexity-review-platform-tracing-slice-8-lightweight-review-md"></a>
## INLINE COPY: `docs/analysis/perplexity-review/platform-tracing-slice-8-lightweight-review.md`

# TraceOperations Slice 8 Lightweight Review

**Review date:** 2026-07-07  
**Scope:** Slice 8 docs, ADRs, and `platform-tracing-samples` only (read-only review)  
**Authoritative references:** `platform-tracing-refactoring-plan.md` v3.4.2, `ADR-platform-tracing-micrometer-observation-boundary.md`, `R01.md`, post-remediation review package

---

## Executive verdict

**ACCEPT WITH DOC FIXES**

Slice 8 documentation, ADRs, and the compilable sample module are architecturally consistent with v3.4.2 invariants. No blockers were found. Two minor factual inaccuracies in the Kafka batch doc/ADR should be corrected before external publication, but they do not invalidate the Slice 8 deliverable or block doc/sample acceptance.

---

## Summary

Slice 8 successfully delivers:

- Six user-facing v3 documents covering getting started, migration, manual API, Kafka batch links, observability/diagnostics, and production readiness.
- Five accepted ADRs documenting v3 public API, SpanSpec governance, topology/links, metering SPI boundary, and Kafka batch links.
- A compilable `platform-tracing-samples` module demonstrating valid v3 API usage without external services.

Grep checks within Slice 8 scope are clean: removed v1 API appears only in migration tables, historical/R01 context, or internal SPI references (`TracingImplementation.startSpan`). Getting started uses v3 examples only. Production readiness correctly leaves e2e and operational gates unchecked. `-PrunE2e` is documented as gated, not default build.

**Out of scope note:** Legacy docs outside Slice 8 (`docs/tracing/links-kafka.md`, `docs/tracing/traceability.md`) still describe v1 API as current. These were not part of Slice 8 changed files and are not blockers for this review, but fleet onboarding should prefer the new `platform-tracing-v3-*` doc set.

---

## Files reviewed

| # | File | Type |
|---|------|------|
| 1 | `docs/tracing/platform-tracing-v3-getting-started.md` | Doc |
| 2 | `docs/tracing/platform-tracing-v3-migration-guide.md` | Doc |
| 3 | `docs/tracing/platform-tracing-v3-manual-api.md` | Doc |
| 4 | `docs/tracing/platform-tracing-v3-kafka-batch-links.md` | Doc |
| 5 | `docs/tracing/platform-tracing-v3-observability-and-diagnostics.md` | Doc |
| 6 | `docs/tracing/platform-tracing-v3-production-readiness.md` | Doc |
| 7 | `docs/decisions/ADR-platform-tracing-v3-public-api.md` | ADR |
| 8 | `docs/decisions/ADR-platform-tracing-span-spec-governance.md` | ADR |
| 9 | `docs/decisions/ADR-platform-tracing-topology-links.md` | ADR |
| 10 | `docs/decisions/ADR-platform-tracing-metering-spi-boundary.md` | ADR |
| 11 | `docs/decisions/ADR-platform-tracing-kafka-batch-links.md` | ADR |
| 12 | `docs/decisions/ADR-platform-tracing-micrometer-observation-boundary.md` | ADR (cross-check) |
| 13 | `platform-tracing-samples/src/main/java/.../PlatformTracingV3Samples.java` | Sample |
| 14 | `platform-tracing-samples/build.gradle` | Build |
| 15 | `settings.gradle` | Build |

**Total:** 15 files reviewed

---

## Grep findings

Scope: `docs/tracing/platform-tracing-v3-*.md`, `docs/decisions/ADR-platform-tracing-v3-public-api.md`, `ADR-platform-tracing-span-spec-governance.md`, `ADR-platform-tracing-topology-links.md`, `ADR-platform-tracing-metering-spi-boundary.md`, `ADR-platform-tracing-kafka-batch-links.md`, `platform-tracing-samples`

| Pattern group | Matches (Slice 8 scope) | Classification | Verdict |
|---|---:|---|---|
| v1 methods (`currentTraceId`, `inSpan`, `startSpanWithLinks`, …) | 14 | MIGRATION_TABLE_ALLOWED (11), CURRENT_V3_USAGE / SPI (3: `TracingImplementation.startSpan`) | PASS |
| Removed symbols (`SpanRelation`, `MeteredPlatformTracing`, `Facade*`) | 12 | MIGRATION_TABLE_ALLOWED / HISTORICAL_ALLOWED / REJECTED_NAME_ALLOWED | PASS |
| Legacy escape hatches (`advanced`, `rawSpan`, `internalSpan`, …) | 4 | MIGRATION_TABLE_ALLOWED / REJECTED_NAME_ALLOWED (ADR justification) | PASS |
| v3 API (`manual()`, `traceContext()`, `spanFromSpec`, topology, links) | 79+ | CURRENT_V3_USAGE | PASS |
| E2e / sign-off (`-PrunE2e`, Docker, Testcontainers) | 7 | CURRENT_V3_USAGE (gated documentation) | PASS |
| Micrometer / metering / SPI | 25+ | CURRENT_V3_USAGE | PASS |
| Sample module v1 patterns | 0 | — | PASS |

**Slice 8 scope grep verdict:** PASS — no VIOLATION matches.

**Legacy docs outside Slice 8 (informational only):**

| File | Issue | Classification |
|------|-------|----------------|
| `docs/tracing/links-kafka.md` | Shows `startSpanWithLinks`, `SpanRelation` as usage | VIOLATION (legacy, not Slice 8) |
| `docs/tracing/traceability.md` | References v1 `inSpan`, `SpanRelation` as OK | VIOLATION (legacy, not Slice 8) |

---

## Documentation review

| Document | Verdict | Notes |
|---|---|---|
| Getting started | PASS | States auto-instrumentation default; `manual()` only when needed. v3-only examples. Lists removed v1 facade names only as negation. |
| Migration guide | PASS | v1 clearly labeled removed; no shim; breaking change intentional; full mapping table; v1 in `// v1` blocks only. |
| Manual API reference | PASS | All required types documented. Topology/links policy correct (ROOT+links, DETACHED+links forbidden, CHILD+links forbidden in v3). `SpanSpecReason` mandatory; `TEMPORARY_WORKAROUND` requires reference; no `attribute(String, Object)`. |
| Kafka batch links | PASS WITH FIX | ROOT+links, propagator extraction, tracestate, semantic drift, aspect migration all correct. **M1/M2:** minor annotation/fallback naming errors (see Issues). |
| Observability and diagnostics | PASS | MeteredTracingImplementation on SPI; no facade decorator; R01 addressed; TracingDiagnosticsView DTO; Observation coexistence; `-PrunE2e` gated. |
| Production readiness | PASS | Does not claim production complete. Unchecked gates for e2e, Kafka container e2e, dashboard notification, suppress-micrometer-tracing, TEMPORARY_WORKAROUND audit. |

---

## ADR review

| ADR | Verdict | Notes |
|---|---|---|
| ADR-platform-tracing-v3-public-api | PASS | Status Accepted. Justifies `traceContext()`, `manual()`, `operation(name)`, `transport()`. No shim. References Micrometer ADR. |
| ADR-platform-tracing-span-spec-governance | PASS | Status Accepted. Justifies `spanFromSpec`, mandatory `SpanSpecReason`, TEMPORARY_WORKAROUND reference, no free-text justification. |
| ADR-platform-tracing-topology-links | PASS | Status Accepted. ROOT/CHILD/DETACHED, ROOT+links, forbidden DETACHED/CHILD+links, pre-start links only. |
| ADR-platform-tracing-metering-spi-boundary | PASS | Status Accepted. Metering on TracingImplementation; no MeteredPlatformTracing; R01 prevention; consistent with Micrometer ADR. |
| ADR-platform-tracing-kafka-batch-links | PASS WITH FIX | Status Accepted. ROOT+links, aspect migration, semantic drift, propagator/tracestate. **M2:** fallback naming inaccuracy. |
| ADR-platform-tracing-micrometer-observation-boundary (cross-check) | PASS | New Slice 8 ADRs do not contradict Option C hybrid model. Historical MeteredPlatformTracing references are factual context only. |

---

## Sample module review

| Check | Verdict | Notes |
|---|---|---|
| Uses only v3 API | PASS | No v1 method calls |
| `traceContext().traceId()` | PASS | `currentTraceIdForLogging()` |
| `manual().operation().run()` / `.call()` / `.callChecked()` | PASS | |
| `root()` and `detached()` | PASS | Both demonstrated without links on detached |
| Database builder with semantic fields | PASS | `system`, `operation`, `collection` |
| RPC client builder with semantic fields | PASS | `system`, `service`, `method`, `serverAddress` |
| Kafka batch ROOT+links | PASS | `linkedTo` and `fromRemoteContext` variants |
| `spanFromSpec` with governance | PASS | LEGACY_INTEGRATION + TEMPORARY_WORKAROUND with reference |
| No external DB/Kafka/network | PASS | Stub interfaces only |
| HTTP transport example | OPTIONAL GAP | Not in sample; mentioned in getting-started doc only |
| Compiles | PASS | `:platform-tracing-samples:compileJava` GREEN |
| Module wiring | PASS | `settings.gradle` include; api-only dependency on `platform-tracing-api`; tests disabled |

---

## Architecture consistency matrix

| Invariant | Evidence | Verdict |
|---|---|---|
| Public API = `traceContext()` + `manual()` | Getting started, manual API ref, v3 public API ADR | PASS |
| Auto-instrumentation default; manual() for gaps | Getting started L3, v3 public API ADR | PASS |
| Single creation boundary `TracingImplementation.startSpan(SpanSpec)` | Manual API ref, all ADRs | PASS |
| No v1 API restored | Migration guide, grep (Slice 8 scope) | PASS |
| No SpanRelation / MeteredPlatformTracing / Facade* in active docs | Only migration/historical mentions | PASS |
| No compatibility shim | Migration guide, v3 public API ADR | PASS |
| MeteredTracingImplementation decorates TracingImplementation | Observability doc, metering ADR | PASS |
| Spring/Micrometer owns auto-observation | Observability doc, Micrometer ADR cross-check | PASS |
| Kafka batch ROOT + links | Kafka doc, kafka ADR, sample | PASS |
| DETACHED + links forbidden | Manual API ref, topology ADR | PASS |
| CHILD + links forbidden | Manual API ref (v3), migration guide, topology ADR | PASS |
| Links pre-start only | Manual API ref, migration guide, topology ADR | PASS |
| SpanSpecReason mandatory | Manual API ref, span spec ADR, sample | PASS |
| TEMPORARY_WORKAROUND requires reference | Manual API ref, sample `temporaryWorkaround()` | PASS |
| `-PrunE2e` not default build | Observability doc, production readiness | PASS |
| Production sign-off not claimed complete | Production readiness checklist (5 unchecked items) | PASS |

---

## Issues

### Blockers

| ID | File | Issue | Required fix |
|---|---|---|---|
| — | — | None | — |

### Medium/low

| ID | File | Issue | Suggested fix |
|---|---|---|---|
| M1 | `docs/tracing/platform-tracing-v3-kafka-batch-links.md` L18 | Text says "`@Traced` batch aspect (`KafkaBatchLinksAspect`)" — aspect advises `@KafkaListener(batch="true")`, not `@Traced` | Replace with "`KafkaBatchLinksAspect` (around `@KafkaListener(batch=\"true\")` methods)" |
| M2 | `docs/tracing/platform-tracing-v3-kafka-batch-links.md` L60; `ADR-platform-tracing-kafka-batch-links.md` L52 | Fallback destination documented as "`@KafkaBatchLinks` method name" — no such annotation exists; implementation uses advised Java method name (`pjp.getSignature().getName()`) | Replace with "advised method name" |
| L1 | `platform-tracing-samples/.../PlatformTracingV3Samples.java` | No HTTP transport builder example | Optional: add `manual().transport().http().client()...` method for parity with getting-started |
| L2 | `docs/tracing/` (legacy) | `links-kafka.md`, `traceability.md` still recommend v1 API | Future doc hygiene pass: add deprecation banner pointing to v3 doc set (out of Slice 8 scope) |
| L3 | v3 doc set | No single index/README linking all six v3 docs | Optional: add `docs/tracing/platform-tracing-v3-index.md` |

---

## Verification

| Command | Result |
|---|---|
| `.\gradlew.bat :platform-tracing-samples:compileJava` | GREEN |
| `.\gradlew.bat build` | GREEN (e2e tests SKIPPED; Docker unavailable — expected) |

---

## Final recommendation

**Slice 8 is ready** for documentation and sample acceptance.

- **Doc/sample deliverable:** ACCEPT WITH DOC FIXES (M1, M2 — non-blocking factual corrections).
- **Production fleet sign-off:** NOT READY — requires unchecked operational gates (`-PrunE2e`, Kafka container e2e, dashboard notification, suppress-micrometer-tracing fleet default, TEMPORARY_WORKAROUND audit). Production readiness doc correctly reflects this.

Do not proceed to **final production sign-off** until operational gates are completed. Slice 8 implementation itself does not require code changes for acceptance.

---

## Scope confirmation

- No production tracing code changed during this review.
- Only this review report was created; Slice 8 implementation files were not modified.
- Sample code, Gradle, and docs from Slice 8 commit `b80d5d9` were reviewed as-is.
---

<a id="file-docs-tracing-platform-tracing-v3-getting-started-md"></a>
## INLINE COPY: `docs/tracing/platform-tracing-v3-getting-started.md`

# TraceOperations v3 — Getting Started

TraceOperations v3 is the public manual-tracing API for Spring Boot services on the platform tracing stack. Auto-instrumentation (OpenTelemetry Java Agent, Spring/Micrometer Observation conventions, `@Traced`) is the **default**. Use `traceOperations.manual()` **only** when automatic instrumentation does not cover your use case.

## Dependencies

Add the appropriate platform tracing starter for your stack (Servlet or Reactive). The `TraceOperations` bean is wired automatically when tracing is enabled.

```gradle
implementation 'space.br1440.platform.tracing:platform-tracing-spring-boot-starter-servlet'
// or
implementation 'space.br1440.platform.tracing:platform-tracing-spring-boot-starter-reactive'
```

## Public API surface

v3 exposes exactly two entry points on `TraceOperations`:

| Method | Purpose |
|--------|---------|
| `traceContext()` | Read-only correlation IDs for logging and error models |
| `manual()` | Governed manual span creation |

There is no v1 wide facade (`startSpan`, `inSpan`, `SpanRelation`, transport factory methods on the root interface). See the [migration guide](./platform-tracing-v3-migration-guide.md).

## Read the active trace id

Use `traceContext()` for correlation. It does not expose OpenTelemetry SDK types.

```java
String traceId = traceOperations.traceContext()
        .traceId()
        .orElse("unknown");
```

Optional fields: `spanId()`, `correlationId()`.

## Manual operation spans

### Run a void action

```java
traceOperations.manual()
        .operation("recalculate-pricing")
        .run(() -> pricingService.recalculate(orderId));
```

### Return a value

```java
Price price = traceOperations.manual()
        .operation("calculate-price")
        .call(() -> pricingService.calculate(orderId));
```

### Checked exceptions

```java
Order order = traceOperations.manual()
        .operation("load-order")
        .callChecked(() -> repository.load(orderId));
```

Default topology is **CHILD** when an active trace context exists; otherwise the platform creates an appropriate root. Use `.root()` or `.detached()` explicitly when governance requires it — see [Manual API reference](./platform-tracing-v3-manual-api.md).

## Transport semantic builders

When you need semconv-aligned HTTP, database, RPC, or Kafka spans, use transport builders instead of generic `operation(name)`:

```java
traceOperations.manual()
        .transport()
        .database()
        .system("postgresql")
        .operation("SELECT")
        .collection("orders")
        .run(() -> repository.findAll());
```

See [Manual API reference](./platform-tracing-v3-manual-api.md) for HTTP, RPC, and Kafka builders.

## When **not** to call `manual()`

- Incoming HTTP handled by the OTel Agent or Spring Observation — do not create a duplicate server span.
- Database/RPC/Kafka already instrumented by the Agent — prefer agent spans unless you need platform semconv attributes the agent does not emit.
- Method already annotated with `@Traced` — the aspect routes through `manual().operation(...)`; do not double-wrap.

See [Observability and diagnostics](./platform-tracing-v3-observability-and-diagnostics.md) and [ADR — Micrometer Observation Boundary](../decisions/ADR-platform-tracing-micrometer-observation-boundary.md).

## Compilable examples

The `platform-tracing-samples` module contains documentation-as-code:

`platform-tracing-samples/src/main/java/space/br1440/platform/tracing/samples/PlatformTracingV3Samples.java`

Verify compilation:

```powershell
.\gradlew.bat :platform-tracing-samples:compileJava
```

## Related documents

- [Migration guide](./platform-tracing-v3-migration-guide.md)
- [Manual API reference](./platform-tracing-v3-manual-api.md)
- [Kafka batch links](./platform-tracing-v3-kafka-batch-links.md)
- [Production readiness](./platform-tracing-v3-production-readiness.md)
---

<a id="file-docs-tracing-platform-tracing-v3-migration-guide-md"></a>
## INLINE COPY: `docs/tracing/platform-tracing-v3-migration-guide.md`

# TraceOperations v3 — Migration Guide

This guide maps the **removed v1 public API** to the v3 replacement. TraceOperations v3 is a **breaking, intentional** redesign. The project was **pre-production** when the cutover happened; there is **no compatibility shim** and no deprecate-first migration path.

Facade decorators (`MeteredPlatformTracing`, `Facade*SpanBuilder`) were removed to prevent [R01](../known-issues/R01.md)-class bugs where partial decorators silently dropped ROOT/DETACHED/links semantics.

## v1 → v3 mapping

| Removed v1 API | v3 replacement |
|---|---|
| `currentTraceId()` | `traceContext().traceId()` |
| `currentSpanId()` | `traceContext().spanId()` |
| `startSpan(name, category)` | `manual().operation(name).start()` / `.run()` / `.call()` |
| `startRootSpan(...)` | `manual().operation(name).root().start()` |
| `startDetachedSpan(...)` | `manual().operation(name).detached().start()` |
| `startSpanWithLinks(...)` | `.root().linkedTo(...).start()` |
| `addLink(...)` | pre-start `.linkedTo(...)`; **no post-start replacement** |
| `inSpan(...)` | `.run()` / `.call()` / `.callChecked()` |
| `SpanRelation` | `Topology` through `.child()` / `.root()` / `.detached()` |
| `internalSpan()` / `businessSpan()` | `manual().operation(name)` |
| transport factory methods on `TraceOperations` | `manual().transport().http()/database()/rpc()/kafka()` |

## Breaking change policy

- **No compatibility shim.** v1 methods are not available on `TraceOperations`.
- **No `MeteredPlatformTracing` public decorator.** Metering is internal on `TracingImplementation` ([ADR — Metering SPI Boundary](../decisions/ADR-platform-tracing-metering-spi-boundary.md)).
- **No `Facade*SpanBuilder`.** Semantic builders live under `manual().transport()`.
- **Links are pre-start only.** There is no v3 equivalent of post-start `addLink(...)`.
- **Governed escape hatch:** `manual().spanFromSpec(spec)` requires `SpanSpecReason` and, for `TEMPORARY_WORKAROUND`, a `reference`.

## Common migration patterns

### Correlation in logs

```java
// v1
String id = traceOperations.currentTraceId();

// v3
String id = traceOperations.traceContext().traceId().orElse("unknown");
```

### Scoped business logic

```java
// v1
traceOperations.inSpan("process-order", SpanCategory.INTERNAL, () -> service.process(orderId));

// v3
traceOperations.manual()
        .operation("process-order")
        .run(() -> service.process(orderId));
```

### Root span with links (Kafka batch)

```java
// v1 (removed)
traceOperations.startSpanWithLinks("batch", SpanCategory.INTERNAL, links);

// v3
traceOperations.manual()
        .transport()
        .kafka()
        .consumer()
        .batch("orders")
        .root()
        .linkedTo(links)
        .run(() -> processor.processBatch(records));
```

See [Kafka batch links](./platform-tracing-v3-kafka-batch-links.md).

### Database / RPC transport spans

```java
// v1
traceOperations.databaseSpan().system("postgresql").operation("SELECT").start();

// v3
traceOperations.manual()
        .transport()
        .database()
        .system("postgresql")
        .operation("SELECT")
        .start();
```

## SpanRelation → Topology

v1 `SpanRelation` enum mapped to v3 explicit topology setters on builders:

| v1 SpanRelation | v3 builder |
|-----------------|------------|
| CHILD (default) | `.child()` or omit when default applies |
| ROOT | `.root()` |
| DETACHED | `.detached()` |

Links policy: **ROOT + links allowed**; **DETACHED + links forbidden**; **CHILD + links forbidden**. See [ADR — Topology and Links](../decisions/ADR-platform-tracing-topology-links.md).

## Advanced / raw span escape hatches

v1 `advanced()`, `rawSpan()`, and similar wide APIs are replaced by governed `spanFromSpec`:

```java
SpanSpec spec = SpanSpec.builder("vendor-integration")
        .category(SpanCategory.INTERNAL)
        .child()
        .reason(SpanSpecReason.UNSUPPORTED_LIBRARY)
        .reference("PLATFORM-1234")
        .build();

traceOperations.manual().spanFromSpec(spec).run(action);
```

See [ADR — SpanSpec Governance](../decisions/ADR-platform-tracing-span-spec-governance.md).

## Operational notes after migration

- **Kafka batch semantic drift** is intentional v3 behavior (span kind, name, messaging attributes). Notify dashboard/alert owners before production cutover.
- **Micrometer Observation coexistence** — do not combine `@Traced` and `@Observed` on the same method; see [Observability and diagnostics](./platform-tracing-v3-observability-and-diagnostics.md).
- **Production sign-off** requires `-PrunE2e` in a Docker/Testcontainers environment — not part of the default build.

## Related documents

- [Getting started](./platform-tracing-v3-getting-started.md)
- [Manual API reference](./platform-tracing-v3-manual-api.md)
- [ADR — v3 Public API](../decisions/ADR-platform-tracing-v3-public-api.md)
---

<a id="file-docs-tracing-platform-tracing-v3-manual-api-md"></a>
## INLINE COPY: `docs/tracing/platform-tracing-v3-manual-api.md`

# TraceOperations v3 — Manual API Reference

Reference for the v3 manual tracing surface. All span creation routes internally through `TracingImplementation.startSpan(SpanSpec)`; application code uses the public builders below.

## Entry points

### `TraceOperations`

| Method | Returns | Description |
|--------|---------|-------------|
| `traceContext()` | `TraceContextView` | Read-only active context |
| `manual()` | `ManualTracing` | Manual span creation |

### `TraceContextView`

Read-only correlation view. Does **not** expose OpenTelemetry `Context`, `Span`, or `SpanContext`.

| Method | Returns |
|--------|---------|
| `traceId()` | `Optional<String>` — hex trace id |
| `spanId()` | `Optional<String>` — hex span id |
| `correlationId()` | `Optional<String>` — platform correlation id when present |

### `ManualTracing`

| Method | Returns | Description |
|--------|---------|-------------|
| `operation(name)` | `OperationSpanBuilder` | Generic application-level span |
| `transport()` | `TransportTracing` | HTTP / DB / RPC / Kafka semconv builders |
| `spanFromSpec(spec)` | `SpecifiedSpan` | Governed escape hatch |

## `PlatformSpanBuilder` (common builder contract)

Implemented by `OperationSpanBuilder`, transport builders, and `KafkaBatchSpanBuilder`.

### Topology (explicit, single assignment)

| Method | Effect |
|--------|--------|
| `child()` | CHILD topology — join active trace when context exists |
| `root()` | ROOT topology — new trace, ignore active parent |
| `detached()` | DETACHED topology — new trace, no parent, **no links** |

Repeated explicit topology setter throws `IllegalStateException`.

### Links (pre-start only)

| Method | Effect |
|--------|--------|
| `linkedTo(SpanLinkContext... links)` | Add pre-start span links |
| `fromRemoteContext(String... traceparents)` | Parse W3C traceparent into links (strict) |

**Policy:**

- **ROOT + links** — allowed (primary Kafka batch pattern)
- **DETACHED + links** — forbidden (fail fast at build/start)
- **CHILD + links** — forbidden in v3
- **No post-start `addLink`** — links must be configured before `start()` / scoped execution

### Scoped execution

| Method | Description |
|--------|-------------|
| `start()` | Returns `SpanHandle` (try-with-resources) |
| `run(Runnable)` | Start span, run action, end span |
| `call(Supplier<T>)` | Start span, return value, end span |
| `callChecked(ThrowingSupplier<T>)` | Same with checked exceptions |

## `OperationSpanBuilder`

`manual().operation(name)` — generic internal/business operations. Category defaults to `INTERNAL` at implementation layer unless overridden via `spanFromSpec`.

## `TransportTracing`

Groups protocol-specific builders under `manual().transport()`:

| Method | Returns |
|--------|---------|
| `http()` | `HttpTracing` → `client()` / `server()` |
| `database()` | `DatabaseTracing` (extends `DatabaseSpanBuilder`) |
| `rpc()` | `RpcTracing` → `client()` / `server()` |
| `kafka()` | `KafkaTracing` → `producer()` / `consumer()` |

Transport builders carry semconv version markers (`@DatabaseSemconvVersion`, `@KafkaSemconvVersion`, `@RpcSemconvVersion`).

### HTTP builders

- `HttpClientSpanBuilder` — `url()`, `method()`, `serverAddress()`, …
- `HttpServerSpanBuilder` — `route()`, `method()`, …

### Database builder

`DatabaseSpanBuilder` methods: `system()`, `operation()`, `collection()`.

### RPC builders

- `RpcClientSpanBuilder` — `system()`, `service()`, `method()`, `serverAddress()`
- `RpcServerSpanBuilder` — `system()`, `service()`, `method()`

### Kafka builders

- `KafkaProducerSpanBuilder` — `destination()`, `operation()`
- `KafkaConsumerSpanBuilder` — `destination()`, `operation()`, `batch(destination)` → `KafkaBatchSpanBuilder`

Batch entry: `consumer().batch("orders")` returns `KafkaBatchSpanBuilder` for ROOT+links batch processing. See [Kafka batch links](./platform-tracing-v3-kafka-batch-links.md).

## `SpanSpec` and governance

Immutable governed specification for `manual().spanFromSpec(spec)`.

### `SpanSpecBuilder`

| Method | Notes |
|--------|-------|
| `category(SpanCategory)` | Required semantic category |
| `child()` / `root()` / `detached()` | Topology |
| `linkedTo(...)` / `fromRemoteContext(...)` | Pre-start links |
| `attribute(key, typedValue)` | Typed scalar attributes only |
| `stringListAttribute` / `longListAttribute` / … | Homogeneous lists |
| `reason(SpanSpecReason)` | **Mandatory** |
| `reference(String)` | Required when `reason == TEMPORARY_WORKAROUND` |
| `build()` | Validates final state |

There is **no** `attribute(String, Object)` — only typed overloads and list helpers.

### `SpanSpecReason`

| Value | Use when |
|-------|----------|
| `UNSUPPORTED_PROTOCOL` | Protocol not covered by transport builders |
| `UNSUPPORTED_LIBRARY` | Third-party library cannot be instrumented otherwise |
| `LEGACY_INTEGRATION` | Bridging legacy integration code |
| `PLATFORM_EDGE_CASE` | Documented platform edge case |
| `TEMPORARY_WORKAROUND` | Short-term workaround — **requires `reference`** |

Generic catch-all reasons (`OTHER`, `UNKNOWN`, …) are forbidden.

### `SpanOptions`

Immutable value model: `topology()` + `links()`. Returned by `SpanSpec.options()`. Prefer builder convenience methods (`.child()`, `.root()`, …) over constructing `SpanOptions` directly.

### `SpanAttributeValue`

Whitelist sealed type for spec attributes: string, long, double, boolean, and homogeneous lists. Factory methods: `SpanAttributeValue.of(...)`, `stringList(...)`, etc.

## Lifecycle types

### `SpecifiedSpan`

Terminal surface from `spanFromSpec(spec)`: `start()`, `run()`, `call()`, `callChecked()`.

### `SpanHandle`

Minimal started-span handle (`AutoCloseable`): `recordException(Throwable)`, `close()`.

## Design ADRs

- [ADR — v3 Public API](../decisions/ADR-platform-tracing-v3-public-api.md)
- [ADR — SpanSpec Governance](../decisions/ADR-platform-tracing-span-spec-governance.md)
- [ADR — Topology and Links](../decisions/ADR-platform-tracing-topology-links.md)
---

<a id="file-docs-tracing-platform-tracing-v3-kafka-batch-links-md"></a>
## INLINE COPY: `docs/tracing/platform-tracing-v3-kafka-batch-links.md`

# TraceOperations v3 — Kafka Batch Links

Kafka batch consumer processing uses **ROOT + pre-start links** to correlate a single batch span with individual message traces. This is the primary public example for span links in v3.

## Recommended pattern

```java
traceOperations.manual()
        .transport()
        .kafka()
        .consumer()
        .batch("orders")
        .root()
        .linkedTo(messageContexts)
        .run(() -> processor.processBatch(records));
```

`messageContexts` is a `SpanLinkContext[]` (varargs) built from record headers. The platform `@Traced` batch aspect (`KafkaBatchLinksAspect`) uses this same API internally.

## Building link contexts

### From structured link contexts

```java
SpanLinkContext link = SpanLinkContext.sampled(traceId, spanId);
// or with tracestate:
SpanLinkContext link = new SpanLinkContext(traceId, spanId, (byte) 1, traceState);
```

### From W3C traceparent headers

OTel propagator extraction remains legitimate for **reading** remote context from headers. Use builder `fromRemoteContext` when you have traceparent strings:

```java
.fromRemoteContext(traceparent1, traceparent2)
```

For lenient extraction loops (skip malformed headers), parse with `RemoteContext.parseTraceparent(header)` and collect valid links before calling `linkedTo(...)`.

**Tracestate is preserved** when present on the extracted link context.

## Topology rules

| Topology | Links | Batch use case |
|----------|-------|----------------|
| ROOT | Allowed | **Required pattern** for batch processing |
| CHILD | Forbidden | Do not attach links to child spans |
| DETACHED | Forbidden | Fail fast |

`.root()` ensures the batch span starts a new trace even when a parent context is active on the listener thread.

## Batch destination naming

The batch builder `batch(destination)` argument drives span naming and `messaging.destination.name`:

| Batch contents | Destination value |
|----------------|-------------------|
| Single topic | Topic name |
| Multiple topics | Kafka listener id |
| Fallback | `@KafkaBatchLinks` method name |

This matches `KafkaBatchLinksAspect` destination resolution in autoconfigure.

## Semantic drift (intentional v3 behavior)

v3 batch spans differ from the old raw OTel aspect spans:

| Aspect | v1 / raw OTel aspect | v3 platform batch API |
|--------|----------------------|------------------------|
| Span kind | Often `INTERNAL` | `KAFKA_CONSUMER` |
| Span name | `<method> process batch` | `<destination> process` |
| Messaging attributes | Often absent | `messaging.system`, `messaging.destination.name`, `messaging.operation=process` |
| Category | Generic internal | `KAFKA_CONSUMER` / `platform.trace.type=kafka_consumer` |

**Operational action:** notify dashboard and alert owners about this drift before production rollout. Existing queries filtering on old span names or kinds will need updates.

## What changed in the aspect boundary

- **Removed:** raw OTel `Tracer` / `SpanBuilder` span creation in `KafkaBatchLinksAspect`.
- **Kept:** OTel propagator extraction from record headers to build remote link contexts.
- **Added:** routing through `manual().transport().kafka().consumer().batch(...).root().linkedTo(...)`.

Proof: `KafkaBatchAspectMigrationTest`, `KafkaBatchSpanBuilderIntegrationTest`.

## Related documents

- [Manual API reference](./platform-tracing-v3-manual-api.md)
- [ADR — Kafka Batch Links](../decisions/ADR-platform-tracing-kafka-batch-links.md)
- [Production readiness](./platform-tracing-v3-production-readiness.md)
---

<a id="file-docs-tracing-platform-tracing-v3-observability-and-diagnostics-md"></a>
## INLINE COPY: `docs/tracing/platform-tracing-v3-observability-and-diagnostics.md`

# TraceOperations v3 — Observability and Diagnostics

How platform manual tracing coexists with Micrometer Observation, how metering works internally, and how to inspect runtime state.

## Three intentional telemetry paths

| Path | Owner | Typical spans |
|------|-------|---------------|
| OpenTelemetry Java Agent | Agent bytecode instrumentation | HTTP, JDBC, gRPC, Kafka (production default) |
| Spring / Micrometer Observation | Framework conventions, `@Observed` | HTTP server/client observations |
| TraceOperations `manual()` | `TracingImplementation` SPI | Governed manual and semantic transport spans |

TraceOperations does **not** replace Agent or Observation auto-instrumentation. Call `manual()` only for gaps. See [ADR — Micrometer Observation Boundary](../decisions/ADR-platform-tracing-micrometer-observation-boundary.md) (Option C hybrid model, **Accepted**).

## Metering boundary (R01 fix)

- **`MeteredTracingImplementation`** decorates **`TracingImplementation`** at the SPI boundary.
- There is **no public-facade decorator** (`MeteredPlatformTracing` was removed in Slice 1B).
- Metering records platform self-metrics around span creation; it does **not** create spans directly.
- R01 (partial facade decorator losing ROOT/DETACHED/links) is **structurally addressed** — see [R01.md](../known-issues/R01.md).

Proof tests: `BeanTopologyTest`, `MeteredTopologyMatrixTest`, `MeteredSpanHandleDoubleCountTest`.

## Micrometer Observation coexistence

When Micrometer Observation is on the classpath:

- Framework HTTP observations remain owned by Spring/Micrometer.
- Platform customizes conventions (`Platform*ObservationConvention`).
- Opt-in `platform.tracing.suppression.suppress-micrometer-tracing` prevents duplicate HTTP spans when the Agent is present ([ADR-suppress-micrometer-tracing](../decisions/ADR-suppress-micrometer-tracing.md)).
- **`ObservationCoexistenceTest`** proves one HTTP request / application operation does not produce two unsynchronized root spans.
- Manual `manual().operation(...)` inside an auto-observed request may create a **child** span sharing the active trace id.
- Intentional `manual().root()` creates a separate trace when explicitly requested.

**Do not** combine `@Traced` and `@Observed` on the same method (ArchUnit rule `NO_TRACED_AND_OBSERVED_ON_SAME_METHOD`).

## Diagnostics

The Actuator tracing endpoint exposes a **stable DTO** (`TracingDiagnosticsView`), not raw internal `TracingState` or OpenTelemetry SDK types.

Typical fields include:

- Tracing mode (enabled / disabled / unavailable) and reason
- Implementation class name
- SDK mode / agent detection flags
- Metered decoration active indicator

Proof: `DiagnosticsBoundaryTest`, `TracingDiagnosticsViewJsonContractTest`.

## Bridge-otel dev path

`io.micrometer.tracing` bridge-otel is an **optional dev/staging** path (see `MicrometerTracingMdcBridgeSmokeTest`). It is **not** the platform manual tracing engine and does not replace `TracingImplementation`.

## Agent / container e2e gate

Full agent and Testcontainers e2e validation is gated by **`-PrunE2e`**. It is a **production sign-off gate**, not part of the default `.\gradlew.bat build`.

Do not mark production sign-off complete unless `-PrunE2e` was actually run in a Docker environment.

## Related documents

- [Getting started](./platform-tracing-v3-getting-started.md)
- [Production readiness](./platform-tracing-v3-production-readiness.md)
- [ADR — Metering SPI Boundary](../decisions/ADR-platform-tracing-metering-spi-boundary.md)
- [anti-double-instrumentation.md](./anti-double-instrumentation.md)
---

<a id="file-docs-tracing-platform-tracing-v3-production-readiness-md"></a>
## INLINE COPY: `docs/tracing/platform-tracing-v3-production-readiness.md`

# TraceOperations v3 — Production Readiness

Checklist for production sign-off after the v3 refactoring (Slices 0A–7, remediation B01–B10, post-Perplexity hardening). **Do not mark unchecked items as done** unless verified in your environment.

## Production readiness checklist

- [x] `.\gradlew.bat build` GREEN
- [x] targeted TraceOperations tests GREEN
- [x] grep gates clean (no v1 API in active docs/code paths)
- [ ] `-PrunE2e` run in Docker/Testcontainers environment
- [ ] Kafka batch listener container e2e passed
- [ ] dashboard/alert owners notified about Kafka span semantic drift
- [ ] suppress-micrometer-tracing fleet default decided
- [ ] temporary workaround references audited (`SpanSpecReason.TEMPORARY_WORKAROUND`)

## Build verification

```powershell
.\gradlew.bat build
.\gradlew.bat :platform-tracing-samples:compileJava
```

## Targeted test suites (reference)

| Suite | Module | Purpose |
|-------|--------|---------|
| `TracingImplementationRoutingTest` | core | Single creation boundary |
| `BeanTopologyTest` | autoconfigure | One SPI chain, metered decoration |
| `MeteredTopologyMatrixTest` | autoconfigure | ROOT/DETACHED/links + metering |
| `ObservationCoexistenceTest` | autoconfigure | No duplicate unsynchronized roots |
| `DiagnosticsBoundaryTest` | autoconfigure | Stable diagnostics DTO |
| `KafkaBatchAspectMigrationTest` | autoconfigure | Aspect uses v3 batch API |
| `KafkaBatchSpanBuilderIntegrationTest` | core | Exported batch span semantics |

## E2E sign-off (`-PrunE2e`)

E2e tests require Docker. Enable explicitly:

```powershell
.\gradlew.bat build -PrunE2e
```

Includes agent/container scenarios such as `DuplicateHttpSpanAgentSmokeTest`. **Not wired into default CI/build** by design.

## Operational decisions before fleet rollout

### Kafka batch semantic drift

v3 batch spans use `KAFKA_CONSUMER` kind, `<destination> process` naming, and messaging semconv attributes. Update dashboards, alerts, and SLO queries. See [Kafka batch links](./platform-tracing-v3-kafka-batch-links.md).

### suppress-micrometer-tracing

Decide fleet default for `platform.tracing.suppression.suppress-micrometer-tracing` when OTel Agent instruments HTTP. See [ADR-suppress-micrometer-tracing](../decisions/ADR-suppress-micrometer-tracing.md).

### TEMPORARY_WORKAROUND audit

Search for `SpanSpecReason.TEMPORARY_WORKAROUND` usages and verify each has a tracked `reference` with an expiry owner.

## Scope confirmation (v3 refactoring)

- Public API: `traceContext()` + `manual()` only
- No v1 API restored
- No compatibility shim
- No `MeteredPlatformTracing` public decorator
- Single creation boundary: `TracingImplementation.startSpan(SpanSpec)`
- Kafka aspect boundary closed (B03)

## Related documents

- [Getting started](./platform-tracing-v3-getting-started.md)
- [Observability and diagnostics](./platform-tracing-v3-observability-and-diagnostics.md)
- [platform-tracing-refactoring-plan.md](../analysis/platform-tracing-refactoring-plan.md)
---

<a id="file-docs-decisions-adr-platform-tracing-v3-public-api-md"></a>
## INLINE COPY: `docs/decisions/ADR-platform-tracing-v3-public-api.md`

# ADR — TraceOperations v3 Public API

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-07-07 |
| Context | TraceOperations v3 refactoring |

## Context

TraceOperations v1 exposed a wide facade: correlation helpers, many `startSpan*` variants, `SpanRelation`, transport factories, `inSpan`, post-start `addLink`, and facade decorators. Partial decorators (`MeteredPlatformTracing`) caused silent semantic loss ([R01](../known-issues/R01.md)). The project was pre-production; a narrow, governed public API was required.

## Decision

The v3 public `TraceOperations` interface exposes **exactly two** methods:

```text
traceContext()  — read-only correlation
manual()        — governed manual span creation
```

All span creation routes through `TracingImplementation.startSpan(SpanSpec)` internally. No v1 methods remain on the public interface. No compatibility shim.

## Why `traceContext()` over `current()` / `currentContext()`

- **`traceContext()`** names a **read-only view** (`TraceContextView`) without implying mutable context manipulation or OTel SDK ownership.
- **`current()`** suggests imperative context switching (conflicts with OTel `Context.current()` semantics and encourages SDK leakage).
- **`currentContext()`** collides with Micrometer/OTel naming and blurs read vs write responsibilities.
- The view returns `Optional` correlation fields only — no `Context`, `Span`, or `SpanContext` in public API.

## Why `manual()` is acceptable next to auto-instrumentation

Auto-instrumentation (OTel Agent, Spring/Micrometer Observation, `@Traced`) remains the default. `manual()` is the **explicit, opt-in** counterpart for cases auto-instrumentation cannot cover (custom business operations, governed transport spans, batch links). Hybrid coexistence is defined in [ADR-platform-tracing-micrometer-observation-boundary.md](./ADR-platform-tracing-micrometer-observation-boundary.md) (Option C, Accepted).

## Why `operation(name)` replaced `internalSpan` / `businessSpan`

- v1 category-split factories (`internalSpan()`, `businessSpan()`) duplicated entry points without adding topology or semconv value.
- v3 uses a single **`operation(name)`** for generic manual work; semantic category defaults to `INTERNAL` at the implementation layer.
- Transport-specific semconv is expressed through **`manual().transport()...`**, not parallel root-level factories.

## Why transport builders are grouped under `transport()`

- Groups HTTP, database, RPC, and Kafka under one namespace, separating **protocol semconv** from generic **application operations**.
- Replaces v1 transport factory methods on `TraceOperations` root interface.
- Enables semconv version markers per transport (`@DatabaseSemconvVersion`, etc.) without polluting `operation(name)`.

## Consequences

### Positive

- Minimal public surface; hard to bypass governance accidentally.
- Clear documentation story: auto by default, `manual()` when needed.
- Facade decorator anti-pattern eliminated.

### Negative

- Breaking migration for any v1 call sites (acceptable — pre-production).
- Developers must learn transport builder nesting.

## References

- [platform-tracing-v3-getting-started.md](../tracing/platform-tracing-v3-getting-started.md)
- [platform-tracing-v3-migration-guide.md](../tracing/platform-tracing-v3-migration-guide.md)
- [R01.md](../known-issues/R01.md)
- [ADR-platform-tracing-micrometer-observation-boundary.md](./ADR-platform-tracing-micrometer-observation-boundary.md)
---

<a id="file-docs-decisions-adr-platform-tracing-span-spec-governance-md"></a>
## INLINE COPY: `docs/decisions/ADR-platform-tracing-span-spec-governance.md`

# ADR — TraceOperations SpanSpec Governance

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-07-07 |
| Context | TraceOperations v3 refactoring |

## Context

v1 escape hatches (`advanced()`, raw span builders, untyped attributes) became dumping grounds for ungoverned instrumentation. Risk R03 in the refactoring plan: uncontrolled `spanFromSpec` usage bypasses semconv validation.

## Decision

Governed manual spans that semantic builders cannot express MUST use:

```text
manual().spanFromSpec(SpanSpec spec)
```

with mandatory governance metadata on every spec.

## Why `spanFromSpec(spec)` replaced `advanced` / `rawSpan` / `escapeHatch`

- Single governed entry point instead of multiple ambiguous wide APIs.
- Immutable `SpanSpec` validates **final state** before span creation.
- All paths still route to `TracingImplementation.startSpan(SpanSpec)` — one creation boundary.
- ArchUnit and runtime validation can enforce reason/reference policy centrally.

## Why `reason(SpanSpecReason)` + `reference` replaced `justification`

- v1 free-text `justification` was not machine-auditable.
- `SpanSpecReason` enum provides **closed, reviewable categories**:
  - `UNSUPPORTED_PROTOCOL`
  - `UNSUPPORTED_LIBRARY`
  - `LEGACY_INTEGRATION`
  - `PLATFORM_EDGE_CASE`
  - `TEMPORARY_WORKAROUND`
- Generic catch-all values (`OTHER`, `UNKNOWN`, `CUSTOM`, `MISC`) are **forbidden**.
- `TEMPORARY_WORKAROUND` **requires** a non-blank `reference` (ticket, ADR id, or tracked work item).

## Attribute typing

- `SpanSpecBuilder` exposes typed scalar and homogeneous list attribute methods only.
- **No** `attribute(String, Object)` — prevents uncontrolled type coercion and OTel wire surprises.
- `SpanAttributeValue` sealed whitelist mirrors OTel-compatible types.

## Consequences

### Positive

- Auditable escape hatch with searchable reason/reference.
- Temporary workarounds are explicitly tagged for fleet review.

### Negative

- More ceremony for edge cases (intentional friction).

## References

- [platform-tracing-v3-manual-api.md](../tracing/platform-tracing-v3-manual-api.md)
- [platform-tracing-refactoring-plan.md](../analysis/platform-tracing-refactoring-plan.md) — R03
---

<a id="file-docs-decisions-adr-platform-tracing-topology-links-md"></a>
## INLINE COPY: `docs/decisions/ADR-platform-tracing-topology-links.md`

# ADR — TraceOperations Topology and Links

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-07-07 |
| Context | TraceOperations v3 refactoring |

## Context

v1 `SpanRelation` mixed topology intent with an overloaded API surface. Post-start `addLink()` allowed links after span start, complicating metering and governance. Platform batch processing requires correlating one batch span with many message traces.

## Decision

v3 replaces `SpanRelation` with explicit topology setters on builders:

```text
.child()    — CHILD (default when active context exists)
.root()     — new trace, ignore active parent
.detached() — new trace, no parent, no links
```

Links are **pre-start only** via `.linkedTo(SpanLinkContext...)` or `.fromRemoteContext(String... traceparents)`.

## Topology + links policy

| Topology | Links allowed | Notes |
|----------|:-------------:|-------|
| ROOT | Yes | Primary pattern for Kafka batch processing |
| DETACHED | No | Fail fast if links present |
| CHILD | No | Fail fast if links present (v3) |

Repeated explicit topology setter throws `IllegalStateException` (builder final-state semantics).

## Why ROOT / DETACHED / CHILD semantics

- **CHILD** — default for business logic inside an active request trace.
- **ROOT** — scheduled jobs, batch processing, or intentional new trace while ignoring thread-local parent.
- **DETACHED** — fire-and-forget work that must not inherit or link to upstream context (audit, background isolation).

## Post-start links removed

v1 `addLink(...)` had no v3 equivalent. Links MUST be configured before `start()` / scoped execution. This keeps `SpanSpec` immutable at the creation boundary and simplifies metering/topology proof (`MeteredTopologyMatrixTest`).

## Consequences

### Positive

- Predictable, testable topology matrix.
- Immutable spec at creation time.

### Negative

- Call sites that added links after start must restructure to pre-start configuration.

## References

- [platform-tracing-v3-manual-api.md](../tracing/platform-tracing-v3-manual-api.md)
- [ADR-platform-tracing-kafka-batch-links.md](./ADR-platform-tracing-kafka-batch-links.md)
---

<a id="file-docs-decisions-adr-platform-tracing-metering-spi-boundary-md"></a>
## INLINE COPY: `docs/decisions/ADR-platform-tracing-metering-spi-boundary.md`

# ADR — TraceOperations Metering SPI Boundary

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-07-07 |
| Context | TraceOperations v3 refactoring |

## Context

v1 registered `MeteredPlatformTracing` as `@Primary` `TraceOperations` bean. The decorator overrode only a subset of the wide facade; relation-aware and link-aware calls fell back to interface defaults on the decorator instance, silently degrading ROOT/DETACHED/links ([R01](../known-issues/R01.md)).

## Decision

Platform self-metrics are recorded by **`MeteredTracingImplementation`**, which decorates **`TracingImplementation`** at the internal SPI boundary.

Rules:

- Metering MUST NOT decorate the public `TraceOperations` facade.
- Metering MUST NOT create spans except by delegating to the wrapped `TracingImplementation`.
- Metering MUST NOT replace or wrap Spring `TracingObservationHandler` beans.
- When Micrometer is present, exactly one active `TracingImplementation` chain exists (`BeanTopologyTest`).

## Why metering is on `TracingImplementation`, not `TraceOperations`

- All manual span creation already converges on `TracingImplementation.startSpan(SpanSpec)` — one interception point.
- Decorating the full SPI preserves ROOT/DETACHED/links semantics (`MeteredTopologyMatrixTest`).
- Public facade stays narrow (`traceContext()` + `manual()`) with no override surface for partial delegation bugs.
- Separates **platform self-metrics** from **Micrometer Observation lifecycle** ([ADR-platform-tracing-micrometer-observation-boundary.md](./ADR-platform-tracing-micrometer-observation-boundary.md)).

## Relationship to Micrometer Observation API

- `MeteredTracingImplementation` counts platform manual tracing operations at the SPI boundary.
- Micrometer Observation handlers remain responsible for framework observations (HTTP, `@Observed`).
- The two systems coexist under Option C hybrid model; they MUST NOT double-decorate each other's span creation paths.

## Consequences

### Positive

- R01 structurally addressed; durable topology + metering proof.
- Clear separation: metrics decorator vs span lifecycle owner.

### Negative

- Historical `MeteredPlatformTracingKnownDefectTest` archived; v1 pattern must not return.

## References

- [R01.md](../known-issues/R01.md)
- [platform-tracing-v3-observability-and-diagnostics.md](../tracing/platform-tracing-v3-observability-and-diagnostics.md)
- [ADR-platform-tracing-micrometer-observation-boundary.md](./ADR-platform-tracing-micrometer-observation-boundary.md)
---

<a id="file-docs-decisions-adr-platform-tracing-kafka-batch-links-md"></a>
## INLINE COPY: `docs/decisions/ADR-platform-tracing-kafka-batch-links.md`

# ADR — TraceOperations Kafka Batch Links

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-07-07 |
| Context | TraceOperations v3 refactoring |

## Context

Kafka batch listeners process many records that may originate from different producer traces. A single batch span should correlate to all message traces via **span links**, not by picking one message as parent. v1 used raw OTel span creation in `KafkaBatchLinksAspect`; v3 requires routing through the governed platform API.

## Decision

Kafka batch processing uses:

```java
traceOperations.manual()
        .transport()
        .kafka()
        .consumer()
        .batch(destination)
        .root()
        .linkedTo(messageContexts)
        .run(() -> processor.processBatch(records));
```

## Why Kafka batch uses ROOT + links

- **ROOT** — batch processing is a new logical operation; it must not become a CHILD of an arbitrary message trace or listener thread context.
- **Links** — each record's producer trace is referenced without forcing a single parent span.
- **DETACHED + links** is forbidden — links require ROOT topology per [ADR-platform-tracing-topology-links.md](./ADR-platform-tracing-topology-links.md).

## Propagator extraction

- OTel propagator extraction from record headers remains legitimate for **reading** remote context.
- **`tracestate` is preserved** on extracted link contexts when present.
- Lenient parsing: `RemoteContext.parseTraceparent` for batch loops; strict: `fromRemoteContext` on builders.

## Aspect migration (B03)

- **Removed:** raw OTel `Tracer` / `SpanBuilder` creation in `KafkaBatchLinksAspect`.
- **Added:** injection of `TraceOperations`; batch spans via v3 batch builder API.
- `TracedAspect` already used `manual().operation(...).start()` — unchanged.

## Destination naming

| Batch contents | `batch(destination)` value |
|----------------|----------------------------|
| Single topic | Topic name |
| Multiple topics | Kafka listener id |
| Fallback | `@KafkaBatchLinks` method name |

## Intentional semantic drift

v3 emits `KAFKA_CONSUMER` spans with messaging semconv attributes and `<destination> process` naming. This replaces old `INTERNAL` / `<method> process batch` aspect spans. Dashboard and alert owners must be notified before production rollout.

## Consequences

### Positive

- Single creation boundary for batch spans; aspect boundary closed.
- Semconv-aligned Kafka consumer category for batch work.

### Negative

- Breaking change for queries keyed on old span names/kinds.

## References

- [platform-tracing-v3-kafka-batch-links.md](../tracing/platform-tracing-v3-kafka-batch-links.md)
- [ADR-platform-tracing-topology-links.md](./ADR-platform-tracing-topology-links.md)
- `KafkaBatchAspectMigrationTest`, `KafkaBatchSpanBuilderIntegrationTest`
---

<a id="file-docs-decisions-adr-platform-tracing-micrometer-observation-boundary-md"></a>
## INLINE COPY: `docs/decisions/ADR-platform-tracing-micrometer-observation-boundary.md`

# ADR — TraceOperations Micrometer Observation Boundary

| Поле | Значение |
|------|----------|
| Статус | **Accepted** |
| Дата | 2026-07-06 |
| Контекст | Mandatory gate before Slice 1A ([platform-tracing-refactoring-plan.md](../analysis/platform-tracing-refactoring-plan.md) v3.4.2) |
| Связанные ADR | [ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md), [ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md), [ADR-typed-span-api-semantic-layer.md](./ADR-typed-span-api-semantic-layer.md) |
| Стек (repo) | Spring Boot **3.5.5**, OTel instrumentation BOM **2.28.1**, OTel SDK via Agent / bridge-otel (см. `gradle.properties`, ADR-otel-direct-integration) |

> **Accepted. This ADR unblocks the Micrometer Observation API Boundary gate for Slice 1A. Slice 1A is limited to additive API skeleton and architecture tests only.**

## Acceptance note

- Status: Accepted
- Accepted option: Option C — Hybrid model
- Approval source: Architect approval communicated for v3.4.2 Slice 1A gate before implementation.
- Approval date: 2026-07-06
- Scope of acceptance: Micrometer Observation API Boundary and Slice 1A gate decisions
- Slice impact: unblocks Slice 1A only; does not authorize Slice 1B, Slice 2, or R01 production fix

---

## Context

TraceOperations v3.4.2 redesign introduces an internal `TracingImplementation` boundary, a narrow public facade (`traceContext()` + `manual()`), and `MeteredTracingImplementation` for platform self-metrics. The repository already runs **three parallel telemetry paths**:

1. **OTel Java Agent** — bytecode HTTP/DB/RPC/Kafka spans (agent-first, [ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md)).
2. **Spring / Micrometer Observation** — framework HTTP observations, `@Observed`, conventions ([ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md)).
3. **TraceOperations manual API** — `DefaultTraceOperations` over OpenTelemetry API `Tracer` / `SpanBuilder` ([DefaultTraceOperations.java](../../platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/DefaultTraceOperations.java)).

Without an explicit boundary decision, manual platform spans and Spring auto-observation can produce **duplicate or unsynchronized root spans** — structurally the same failure class as **R01** (two paths that silently diverge; see [R01.md](../known-issues/R01.md)).

This ADR decides ownership per path. It does **not** implement code.

---

## Problem

The refactoring plan requires answering before Slice 1A:

1. Which component owns trace lifecycle for **manual platform spans**?
2. Which component owns trace lifecycle for **Spring auto-instrumented spans**?
3. How are **duplicate spans** prevented?
4. How does the Micrometer tracing bridge coexist with **`MeteredTracingImplementation`**?
5. Should any Spring tracing handlers be **disabled, customized, or left untouched**?
6. Which **tests** prove coexistence?

Risk **R24** in the plan: *Micrometer Observation and TraceOperations create duplicate or inconsistent spans* (High, Slices 1A/7).

---

## Repository facts

Classification: **FACT** = directly observed in repository; **ASSUMPTION** = inferred; **OPEN QUESTION** = not resolved from repo alone.

| # | Finding | Class |
|---|---------|-------|
| 1 | **OpenTelemetry API/SDK:** `DefaultTraceOperations` creates spans via `OpenTelemetry.getTracer(...).spanBuilder(...)`, `Context`, `Span` ([DefaultTraceOperations.java](../../platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/DefaultTraceOperations.java)). Typed semantic builders in core use `Tracer`/`SpanBuilder` ([ADR-typed-span-api-semantic-layer.md](./ADR-typed-span-api-semantic-layer.md)). SPI extension in `platform-tracing-otel-extension`. | FACT |
| 2 | **MeterRegistry:** `TracingMetricsAutoConfiguration` registers `PlatformTracingMetrics` and `@Primary` `MeteredPlatformTracing` when `MeterRegistry` bean exists ([TracingMetricsAutoConfiguration.java](../../platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/TracingMetricsAutoConfiguration.java)). | FACT |
| 3 | **ObservationRegistry / Observation API:** `io.micrometer:micrometer-observation` is an **api** dependency in autoconfigure, webmvc, webflux. Production code registers `Platform*ObservationConvention` beans and `ObservationRegistryCustomizer` suppressors ([ServletTracingAutoConfiguration.java](../../platform-tracing-autoconfigure-webmvc/src/main/java/space/br1440/platform/tracing/autoconfigure/servlet/ServletTracingAutoConfiguration.java), [WebMvcSuppressMicrometerTracingAutoConfiguration.java](../../platform-tracing-autoconfigure-webmvc/src/main/java/space/br1440/platform/tracing/autoconfigure/servlet/WebMvcSuppressMicrometerTracingAutoConfiguration.java)). `TracingObservationAutoConfiguration` registers startup diagnostics only, not span creation ([TracingObservationAutoConfiguration.java](../../platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/TracingObservationAutoConfiguration.java)). | FACT |
| 4 | **`io.micrometer.tracing`:** `compileOnly` in autoconfigure; **no production usage in core**. Test-only smoke: `MicrometerTracingMdcBridgeSmokeTest` registers `Tracer`, `TracingObservationHandler`, `ObservationRegistry` when Spring Actuator tracing autoconfig + bridge-otel are on classpath ([MicrometerTracingMdcBridgeSmokeTest.java](../../platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/mdc/MicrometerTracingMdcBridgeSmokeTest.java)). | FACT |
| 5 | **MeteredPlatformTracing:** Decorates delegate; overrides only `startSpan(name, category)`, builder factories, `currentTraceId`/`currentSpanId`, `recordException`. Does **not** override relation/links/`inSpan` — R01 ([MeteredPlatformTracing.java](../../platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/metrics/MeteredPlatformTracing.java), [R01.md](../known-issues/R01.md)). | FACT |
| 6 | **Spring bean wiring:** `TracingCoreAutoConfiguration` exposes `TraceOperations` (`DefaultTraceOperations` or `NoopTraceOperations`). `TracingMetricsAutoConfiguration` wraps it in `@Primary` `MeteredPlatformTracing` when Micrometer present. | FACT |
| 7 | **Existing coexistence / duplicate tests:** `DuplicateSpansRegressionMatrixTest` (webmvc + webflux), `SuppressMicrometerTracingMetricsTest`, `TracingObservationSuppressStartupTest`, e2e `DuplicateHttpSpanAgentSmokeTest`. **No `ObservationCoexistenceTest` class exists yet** (planned Slice 7). | FACT |
| 8 | **Web / aspect auto-instrumentation:** `ServletTracingAutoConfiguration`, `ReactiveTracingAutoConfiguration`, platform Observation conventions, `@Traced` via `TracedAspect`, ArchUnit rule `NO_TRACED_AND_OBSERVED_ON_SAME_METHOD` ([TracingArchRules.java](../../platform-tracing-test/src/main/java/space/br1440/platform/tracing/test/arch/TracingArchRules.java)). | FACT |
| 9 | **Actuator diagnostics:** `TracingActuatorEndpoint` exposes `implementation` class name, SDK mode, agent detection ([TracingActuatorEndpoint.java](../../platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/actuator/TracingActuatorEndpoint.java)). Plan v3.4.2 adds future `TracingDiagnostics` → `TracingDiagnosticsView` mapped DTO (not yet implemented). | FACT |
| 10 | **R01 evidence:** Slice 0B RED tests + [R01.md](../known-issues/R01.md); `knownDefectTest` tasks in core/autoconfigure Gradle. | FACT |
| 11 | **Accepted prior decision:** Typed span builders MUST NOT use Micrometer Observation ([ADR-typed-span-api-semantic-layer.md](./ADR-typed-span-api-semantic-layer.md) rejects Observation for builders). | FACT |
| 12 | **Accepted prior decision:** HTTP duplicate spans with Agent mitigated by opt-in `platform.tracing.suppression.suppress-micrometer-tracing` ([ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md)). | FACT |
| 13 | **ASSUMPTION:** Post-refactor `DefaultTracingImplementation` will continue direct OTel `Tracer` usage consistent with ADR-otel-direct-integration and typed-span ADR. | ASSUMPTION |
| 14 | **OPEN QUESTION:** Exact production rollout matrix for `suppress-micrometer-tracing` vs bridge-otel dev path vs Agent-only — documented in MIGRATION.md but fleet-specific defaults are operational, not code. | OPEN QUESTION |

---

## Decision drivers

1. Preserve **OTel topology semantics** (ROOT, DETACHED, CHILD, pre-start **links**) through `SpanSpec` governance (plan v3.4.2).
2. Prevent **silent semantic loss** via partial decorators (R01 lesson).
3. Maintain **agent-first** production model ([ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md)).
4. Avoid **double instrumentation** on HTTP and business methods (existing duplicate-span suite + ArchUnit).
5. Keep **one creation boundary** for manual platform spans (`TracingImplementation.startSpan(SpanSpec)`).
6. Separate **platform self-metrics** (`MeteredTracingImplementation`) from **Spring Observation lifecycle**.
7. **Testability** with falsifiable gates (plan named test suites).

---

## Options considered

### Option A — TraceOperations manual spans use direct OpenTelemetry SDK; Spring auto-observation remains separate

**Description:** Manual platform tracing stays on OTel API/SDK (`TracingImplementation` → `Tracer.spanBuilder`). Spring/Micrometer Observation continues independently for framework observations. Coexistence enforced by configuration (suppress), conventions, and tests.

| Aspect | Assessment |
|--------|------------|
| **Pros** | Aligns with ADR-otel-direct-integration and typed-span ADR; full OTel links/ROOT/DETACHED; `SpanSpec` maps cleanly to `SpanBuilder`; matches current `DefaultTraceOperations` implementation. |
| **Cons** | Two lifecycle owners remain; requires strict duplicate-prevention rules and discipline; easy to mis-wire if future code bypasses ADR. |
| **R01 impact** | Fixes R01 by moving metering to `MeteredTracingImplementation` on full abstract SPI — not by patching facade decorator. |
| **Duplicate span risk** | Medium without suppress + ArchUnit + coexistence tests; **mitigated** by existing suppress ADR and planned `ObservationCoexistenceTest`. |
| **OTel links** | Native via `SpanBuilder.addLink` (current `DefaultTraceOperations` behavior). |
| **SpanSpec governance** | Strong — direct mapping at creation boundary. |
| **Spring auto-observation** | Unchanged; platform customizes conventions only. |
| **MeteredTracingImplementation** | Natural: decorate OTel-backed implementation, count at SPI boundary. |
| **Testability** | High — InMemorySpanExporter pattern already used in core/autoconfigure tests. |

### Option B — TraceOperations implemented as a bridge over Micrometer Observation API

**Description:** All manual platform spans created via `ObservationRegistry` / `Observation.start()` / Micrometer Tracing handlers.

| Aspect | Assessment |
|--------|------------|
| **Pros** | Single Micrometer-centric API for app code; aligns with Spring Boot 3 observation idioms (`@Observed`). |
| **Cons** | **Contradicts** accepted typed-span ADR ("Micrometer Observation for builders — rejected"); Observation lifecycle less expressive for DETACHED/ROOT/links; weakens planned `TracingImplementation.startSpan(SpanSpec)` as single OTel topology boundary; bridge-otel is compileOnly/opt-in, not production default. |
| **R01 impact** | Does not address root cause (partial delegation); may recreate similar defects via Observation handler partial overrides. |
| **Duplicate span risk** | **High** — Observation handlers already create spans for HTTP; manual Observation spans risk stacking with Agent + platform paths. |
| **OTel links** | **Uncertain** — Micrometer Tracing link support not evidenced in platform production code; would require mapping layer not present in repo. |
| **SpanSpec governance** | **Weak** — Observation conventions ≠ platform semconv builder validation model. |
| **Spring auto-observation** | Collapses distinction between framework and platform manual paths. |
| **MeteredTracingImplementation** | Ambiguous — metrics vs Observation handlers overlap. |
| **Testability** | Medium — bridge path only covered by smoke test, not topology matrix. |

### Option C — Hybrid: Spring/Micrometer owns auto-observation; platform manual tracing governed by TracingImplementation (direct OTel)

**Description:** **Separate intentional telemetry paths** with explicit ownership:

- **Auto path:** OTel Agent (production spans) + Spring Micrometer Observation (framework observations, conventions, optional bridge-otel dev/staging) — platform does **not** re-wrap every Observation into `TraceOperations`.
- **Manual path:** `traceOperations.manual()` → `TracingImplementation.startSpan(SpanSpec)` → OTel SDK — platform-owned topology, governance, links.
- **Metrics path:** `MeteredTracingImplementation` decorates `TracingImplementation` only — never public facade, never Observation handlers.

| Aspect | Assessment |
|--------|------------|
| **Pros** | Matches repository reality and accepted ADRs; preserves OTel semantics; clarifies ownership; extends existing suppress/convention/ArchUnit patterns; directly supports v3.4.2 invariants. |
| **Cons** | Requires developer education ("when to use manual() vs rely on Agent/Observation"); more moving parts documented in ADR + tests. |
| **R01 impact** | Structural fix at SPI; hybrid keeps Observation out of manual creation boundary. |
| **Duplicate span risk** | **Lowest** when combined with suppress policy, `@Traced`/`@Observed` mutual exclusion, and `ObservationCoexistenceTest`. |
| **OTel links** | Full via OTel at `TracingImplementation` layer. |
| **SpanSpec governance** | Strong at single creation boundary. |
| **Spring auto-observation** | Preserved and customized (conventions), not replaced. |
| **MeteredTracingImplementation** | Clear scope — SPI decorator only. |
| **Testability** | Highest — separates concerns; plan named suites map cleanly. |

---

## Scoring (1 = poor, 5 = excellent)

| Criterion | A — Direct OTel | B — Observation bridge | C — Hybrid |
|-----------|:--:|:--:|:--:|
| Preserves OTel topology/link semantics | 5 | 2 | 5 |
| Minimizes duplicate-span risk | 3 | 2 | 5 |
| Fits SpanSpec governance | 5 | 2 | 5 |
| Keeps TracingImplementation as single creation boundary | 5 | 2 | 5 |
| Works with Spring Boot auto-observation | 4 | 4 | 5 |
| Testability | 4 | 3 | 5 |
| Implementation complexity | 4 | 3 | 3 |
| Long-term maintainability | 4 | 2 | 5 |
| **Total** | **34** | **20** | **38** |

---

## Decision

**Recommend Option C — Hybrid model.**

Option A is a subset of Option C (direct OTel manual path) but **without explicit ownership rules** for Spring auto-observation and metering. Option B is **rejected** because it conflicts with accepted ADRs, lacks repository evidence for links/topology mapping, and increases duplicate-span risk.

---

## Required decision answers

| Question | Decision |
|----------|----------|
| **1. Manual platform span lifecycle owner** | **`TracingImplementation`** (concrete: `DefaultTracingImplementation` or successor) using OpenTelemetry API `Tracer` / `SpanBuilder` at a **single** `TracingImplementation.startSpan(SpanSpec)` boundary. Public `traceOperations.manual()` (`manual().operation(...)`, `manual().transport()...`, `manual().spanFromSpec(spec)`) MUST NOT create spans except by routing through this boundary. |
| **2. Spring auto-instrumented span lifecycle owner** | **OTel Java Agent** (production bytecode spans) and **Spring / Micrometer Observation** (framework observations: HTTP server/client conventions, `@Observed`, Actuator observation pipeline). TraceOperations MUST NOT assume ownership of these spans. |
| **3. Duplicate span prevention** | See [Duplicate-span prevention rules](#duplicate-span-prevention-rules) and [Implementation guardrails](#implementation-guardrails). Existing `suppress-micrometer-tracing` remains the HTTP-level guard when Agent is present ([ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md)). `ObservationCoexistenceTest` (Slice 7) proves no competing unsynchronized roots for one operation. |
| **4. Micrometer tracing bridge vs MeteredTracingImplementation** | **`io.micrometer.tracing` bridge-otel** is an **optional dev/staging** Spring Actuator path ([MicrometerTracingMdcBridgeSmokeTest.java](../../platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/mdc/MicrometerTracingMdcBridgeSmokeTest.java)), **not** the platform manual tracing engine. **`MeteredTracingImplementation`** records platform counters/histograms around **`TracingImplementation`** delegation only; it MUST NOT replace `TracingObservationHandler` and MUST NOT create spans. |
| **5. Spring tracing handlers** | **Leave framework handlers in place.** Platform MAY **customize** Observation conventions (`Platform*ObservationConvention`). Platform MAY **suppress** duplicate HTTP observations via opt-in `platform.tracing.suppression.suppress-micrometer-tracing` when Agent creates HTTP spans. Platform MUST NOT globally disable Observation for non-HTTP concerns without ADR amendment. Platform MUST NOT auto-wrap all Observations into `TraceOperations` spans. |
| **6. Coexistence tests** | **Existing:** `DuplicateSpansRegressionMatrixTest`, suppress tests, ArchUnit `NO_TRACED_AND_OBSERVED_ON_SAME_METHOD`. **Required (plan v3.4.2):** `ObservationCoexistenceTest`, `BeanTopologyTest`, `TracingImplementationRoutingTest`, `MeteredTopologyMatrixTest`, `DiagnosticsBoundaryTest`. |

---

## Implementation guardrails

These rules are normative for Slice 1A onward. Violations require ADR amendment or explicit architect sign-off.

- Manual platform spans MUST be created only through **`TracingImplementation.startSpan(SpanSpec)`**.
- Public entry points **`manual().operation(...)`**, **`manual().transport()...`**, **`manual().spanFromSpec(spec)`** MUST route to that boundary; they MUST NOT call OpenTelemetry `Tracer` / `SpanBuilder` directly.
- Public builders and facade types MUST NOT use OpenTelemetry `Tracer` directly (enforced by `TracingImplementationRoutingTest` + ArchUnit in Slice 2).
- **`MeteredTracingImplementation`** MUST decorate **`TracingImplementation`**; it MUST NOT create spans directly (delegate-only).
- **`MeteredTracingImplementation`** MUST NOT replace or wrap Spring **`TracingObservationHandler`** beans.
- **`MeteredTracingImplementation`** MUST NOT decorate the public **`TraceOperations`** facade (R01 lesson; no `@Primary` metered facade pattern).
- **`TraceOperations`** MUST NOT auto-wrap every Micrometer **`Observation`** into a platform manual span.
- Spring/Micrometer auto-observation MUST remain the owner of framework-level observations (HTTP server/client, `@Observed`, Observation handlers) unless a **later ADR** explicitly changes this.
- Explicit **`manual().operation(...)`** inside an auto-observed request MAY create a child/manual span; it MUST be consistently related to the active trace via OTel `Context.current()` unless `SpanSpec` explicitly requests ROOT/DETACHED under governance rules.
- No public API MUST expose OpenTelemetry SDK types.
- **`SpanSpec` / `SpanOptions` topology** (ROOT, DETACHED, CHILD, pre-start links) MUST remain intact per plan v3.4.2; no compatibility shim for v1 wide `TraceOperations` API.
- Durable fix for R01 is **structural SPI boundary**, not a patch to **`MeteredPlatformTracing`**.

---

## Detailed design rules

### Manual platform spans

All v3 manual entry points:

```text
manual().operation(...)
manual().transport()...
manual().spanFromSpec(spec)
```

MUST route to:

```text
TracingImplementation.startSpan(SpanSpec)
```

Rules:

- MUST use OpenTelemetry API at the implementation layer ([ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md)).
- MUST NOT use Micrometer Observation API inside `TracingImplementation` or semantic transport builders ([ADR-typed-span-api-semantic-layer.md](./ADR-typed-span-api-semantic-layer.md)).
- MUST NOT expose OpenTelemetry SDK types in public API.
- MAY create **child** manual spans inside an active auto-observed request trace (shared trace id via OTel `Context.current()`).
- MUST NOT create a **second unsynchronized root** for the same HTTP request / application operation unless `SpanSpec` explicitly requests ROOT and tests cover that scenario.

### Spring auto-observation

Spring Boot / Micrometer remain responsible for:

```text
HTTP server observations (WebMVC / WebFlux conventions)
HTTP client observations
framework-level @Observed observations
Micrometer Observation metrics handlers (when not suppressed)
```

Platform responsibilities on the auto path:

- Register / customize `Platform*ObservationConvention` beans (platform.type, platform.result).
- Apply suppress policy for Agent environments ([ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md)).
- Emit startup diagnostics (`TracingObservationSuppressStartupRunner`, dual-channel drift).

Platform MUST NOT:

- Replace all Spring Observation with `traceOperations.manual()`.
- Create platform manual spans for every Observation event.

### MeteredTracingImplementation

Future internal decorator MUST:

- Decorate **`TracingImplementation`** only (not public `TraceOperations` facade).
- Increment platform metrics on manual tracing operations at SPI boundary.
- Preserve ROOT / DETACHED / CHILD / links topology (delegate-only span creation).
- MUST NOT create spans except via delegation to wrapped `TracingImplementation`.
- MUST NOT decorate or replace Spring `TracingObservationHandler` beans.
- MUST NOT reintroduce `@Primary` public-facade decorator pattern (R01).

### Duplicate-span prevention rules

1. **Separate intentional paths:** Agent/Observation auto spans vs platform manual spans — different owners, shared trace context when appropriate.
2. **HTTP Agent + Micrometer:** use `suppress-micrometer-tracing=true` when Agent instruments HTTP (accepted ADR).
3. **Method-level:** MUST NOT combine `@Traced` and `@Observed` on the same method (ArchUnit).
4. **@Traced aspect:** MUST NOT create HTTP server spans duplicating Agent/Observation ([TracedAspect.java](../../platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/aspect/TracedAspect.java) documents this).
5. **Explicit manual only:** platform manual spans require developer call to `manual()` — no implicit wrapping of all Observations.
6. **Child inside request:** manual span inside auto-observed request SHOULD be CHILD unless `SpanSpec` requests ROOT/DETACHED with governance.
7. **No competing roots:** one HTTP request / one application operation MUST NOT yield two unsynchronized root spans (asserted by `ObservationCoexistenceTest`).

### Diagnostics and Actuator implications

Diagnostics MUST expose (via future `TracingDiagnostics` → `TracingDiagnosticsView`):

- Manual platform tracing state (`TracingState` mapped — not raw internal object).
- Whether metered implementation decoration is active.
- Whether Micrometer / Observation integration is present on classpath (boolean flags, not classloader dump).
- SDK mode / agent detection (extend existing `TracingActuatorEndpoint` patterns).

Diagnostics MUST NOT:

- Leak OpenTelemetry SDK types to Actuator JSON.
- Serialize internal `TracingState` directly without mapping.

---

## Test requirements

Future slices MUST implement (from plan v3.4.2):

### ObservationCoexistenceTest (Slice 7)

- With Spring/Micrometer Observation present, **one HTTP request or one application operation** MUST NOT produce **two unsynchronized root spans**.
- Explicit `manual()` operation inside auto-observed request MAY produce a **child/manual** span with consistent trace relationship to active context.
- Disabling/unavailable platform manual tracing MUST NOT disable unrelated Spring auto-observation unless explicitly configured.
- `MeteredTracingImplementation` MUST NOT create spans directly.
- No public-facade metered decorator path (`MeteredPlatformTracing` pattern forbidden post-refactor).

### BeanTopologyTest (Slice 2, autoconfigure)

- Exactly **one** active `TracingImplementation` chain.
- Micrometer classpath produces **`MeteredTracingImplementation`** decoration of that chain.
- No competing `@Primary` / `@Order` bypass of metered chain.

### TracingImplementationRoutingTest (Slice 2, core)

- All manual span creation paths route through **`TracingImplementation.startSpan(SpanSpec)`**.
- Public builders MUST NOT call OpenTelemetry `Tracer` directly (ArchUnit complement).

### MeteredTopologyMatrixTest (Slice 6)

- Metering preserves **ROOT**, **DETACHED**, **ROOT + links**.
- Invalid DETACHED + links fails fast (governance).
- Metric-count assertions **separate** from topology assertions (R12).

### DiagnosticsBoundaryTest (Slice 7)

- Actuator returns **`TracingDiagnosticsView`**, not internal `TracingState`.
- JSON contract stable for support tooling.

**Existing tests retained:** `DuplicateSpansRegressionMatrixTest`, suppress autoconfig tests, `MicrometerTracingMdcBridgeSmokeTest` (bridge-otel path only).

---

## Consequences

### Positive

- Clear ownership ends R01-class silent divergence for manual tracing.
- Compatible with agent-first and typed-span ADRs without reopening public API naming.
- Duplicate HTTP mitigation already proven; coexistence test closes R24 gap for v3 manual path.
- `MeteredTracingImplementation` scope is narrow and testable.

### Negative / trade-offs

- Developers must understand **three paths** (Agent, Observation, manual platform) and when to call `manual()`.
- Hybrid model requires Slice 7 matrix tests before production sign-off.
- Bridge-otel dev path remains distinct from production Agent path — documentation burden.

### Neutral

- Does not fix R01 in current v1 code (Slice 2/6).
- Does not implement v3 API (Slice 1A+).

---

## Non-goals

- No implementation of v3 API in this ADR.
- No production code changes.
- No decision to expose OpenTelemetry SDK types in public API.
- No decision to replace all Spring Observation with TraceOperations.
- No backward-compatibility wrapper for v1 `TraceOperations` API.
- No fix for R01 in this ADR (evidence only in Slice 0B).

---

## Open questions

These items are **operational or slice-scoped**. They do **not** block Slice 1A if Option C ownership (this ADR) is accepted. They MUST be resolved before the mapped slice ships.

| ID | Question | Blocks Slice 1A? | Resolve by slice |
|----|----------|:----------------:|------------------|
| OQ-1 | Fleet-default for `suppress-micrometer-tracing` in production Helm / deployment charts | No | Slice 7 / deployment policy |
| OQ-2 | Primary matrix for `ObservationCoexistenceTest`: Agent-on vs bridge-otel | No | Slice 7 (recommend Agent-on as primary production gate; bridge-otel as secondary smoke) |
| OQ-3 | Exact `TracingDiagnosticsView` JSON field names | No | Slice 7 |

### Open questions before Accepted status

Architects MAY accept Option C while leaving OQ-1–OQ-3 open, provided:

- ownership answers in [Required decision answers](#required-decision-answers) are signed off;
- [Acceptance checklist](#acceptance-checklist) is completed;
- no open question requires changing Slice 1A public API surface (none currently do).

If a future revision of OQ-2 or OQ-3 contradicts Option C ownership, this ADR MUST be re-opened before the affected slice merges.

---

## Impact on next slices

Aligned with [platform-tracing-refactoring-plan.md](../analysis/platform-tracing-refactoring-plan.md) v3.4.2. No contradiction: manual paths, SPI boundary, internal metering, and test ownership match the plan.

### Slice 1A

- **Blocked** until this ADR status is **Accepted** and [Acceptance checklist](#acceptance-checklist) is signed off.
- MAY add **additive** public API skeleton (`TraceContextView`, `ManualTracing`, `SpanSpec`, builders) only after acceptance.
- MUST NOT introduce Micrometer Observation as the implementation of public manual builders.
- MUST preserve `SpanSpec` / `SpanOptions` / links topology required by the plan.
- MUST preserve no OTel SDK leak in public API.
- MUST NOT reintroduce wide v1 `TraceOperations` behavioral defaults or `MeteredPlatformTracing` as durable metering path.

### Slice 2

- MUST introduce **`TracingImplementation.startSpan(SpanSpec)`** as the single manual span creation boundary.
- MUST include **`TracingImplementationRoutingTest`** (core).
- MUST include **`BeanTopologyTest`** (Spring Boot autoconfigure).
- MUST prove **`MeteredTracingImplementation`** cannot be bypassed when Micrometer is present (one active chain; no competing `@Primary` / `@Order` bypass).
- MUST migrate anti-double guard and kill-switch to internal `TracingState` per plan.

### Slice 6

- MUST prove **`MeteredTracingImplementation`** preserves ROOT, DETACHED, ROOT+links.
- MUST prove invalid DETACHED+links fails fast (governance).
- MUST keep metric-count assertions separate from topology assertions (R12; `MeteredTopologyMatrixTest`).

### Slice 7

- MUST include **`ObservationCoexistenceTest`**.
- MUST prove Spring auto-observation and TraceOperations manual spans do not create two unsynchronized root spans for the same operation.
- MUST include **`DiagnosticsBoundaryTest`** and mapped **`TracingDiagnosticsView`** contract.
- MAY extend Spring Boot matrix beyond Slice 2 `BeanTopologyTest` (FilteredClassLoader, webmvc/webflux, `@Traced` against new facade).

### Slice 0A / 0B / 8

| Slice | Impact |
|-------|--------|
| **0A / 0B** | No change; R01 evidence stands ([R01.md](../known-issues/R01.md)) |
| **8** | Reference this ADR in platform docs; no API behavior change |

---

## Cross-check against plan v3.4.2

| Plan invariant | ADR alignment |
|----------------|---------------|
| `manual().operation(...)`, `manual().transport()`, `manual().spanFromSpec(spec)` | Confirmed as platform manual paths → `TracingImplementation.startSpan(SpanSpec)` |
| Single creation boundary | Confirmed |
| `MeteredTracingImplementation` decorates internal SPI, not public facade | Confirmed; `MeteredPlatformTracing` explicitly rejected as durable fix |
| `SpanOptions` topology/links | Confirmed intact |
| `ObservationCoexistenceTest` | Slice 7 requirement |
| `BeanTopologyTest` | Slice 2 autoconfigure proof |
| No v1 compatibility shim | Confirmed in guardrails |
| R01 fix via SPI, not facade patch | Confirmed |

---

## Acceptance checklist

- [x] Option C hybrid ownership model accepted.
- [x] Manual platform span lifecycle owner accepted: `TracingImplementation.startSpan(SpanSpec)`.
- [x] Spring auto-observation lifecycle owner accepted: OTel Agent + Spring/Micrometer Observation path.
- [x] `MeteredTracingImplementation` role accepted: metrics decorator only; no direct span creation; no replacement of Observation handlers.
- [x] Duplicate-span prevention rules accepted (suppress policy, ArchUnit, separate intentional paths, `ObservationCoexistenceTest`).
- [x] Spring tracing handlers policy accepted: leave in place; customize conventions; opt-in suppress only where documented.
- [x] Required tests accepted:
  - [x] `ObservationCoexistenceTest` (Slice 7)
  - [x] `BeanTopologyTest` (Slice 2)
  - [x] `TracingImplementationRoutingTest` (Slice 2)
  - [x] `MeteredTopologyMatrixTest` (Slice 6)
  - [x] `DiagnosticsBoundaryTest` (Slice 7)
- [x] Open questions OQ-1–OQ-3 classified as non-blocking for Slice 1A (or resolved).
- [x] Slice 1A unblock decision recorded (2026-07-06; approval source: architect approval communicated for v3.4.2 Slice 1A gate before implementation).

---

## Slice impact (summary table)

| Slice | Impact |
|-------|--------|
| **0A / 0B** | No change; R01 evidence stands |
| **1A** | **Unblocked** for additive API skeleton only (ADR Accepted) |
| **2** | `TracingImplementation`, routing + `BeanTopologyTest` |
| **6** | `MeteredTracingImplementation`, `MeteredTopologyMatrixTest` |
| **7** | `ObservationCoexistenceTest`, diagnostics DTO, extended matrix |
| **8** | Docs reference |

---

## References

- [platform-tracing-refactoring-plan.md](../analysis/platform-tracing-refactoring-plan.md) v3.4.2 — § Architecture Decision Required Before Slice 1A; R24; named test suites
- [platform-tracing-plan-final-architecture-review.md](../analysis/platform-tracing-plan-final-architecture-review.md) — P3 Observation ADR requirement
- [R01.md](../known-issues/R01.md) — MeteredPlatformTracing known defect
- [ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md)
- [ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md)
- [ADR-typed-span-api-semantic-layer.md](./ADR-typed-span-api-semantic-layer.md)
- [docs/tracing/anti-double-instrumentation.md](../tracing/anti-double-instrumentation.md)
- [docs/SUPPORTED.md](../SUPPORTED.md) — duplicate spans regression suite status
---

<a id="file-platform-tracing-samples-src-main-java-space-br1440-platform-tracing-samples-platformtracingv3samples-java"></a>
## INLINE COPY: `platform-tracing-samples/src/main/java/space/br1440/platform/tracing/samples/PlatformTracingV3Samples.java`

```java
package space.br1440.platform.tracing.samples;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.RemoteContext;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;

import java.util.List;

/**
 * Compilable v3 API examples referenced by {@code docs/tracing/platform-tracing-v3-*.md}.
 * <p>
 * This class is documentation-as-code: it is not executed in production and does not require
 * external services. Inject a {@link TraceOperations} bean (or test double) at runtime.
 */
public final class PlatformTracingV3Samples {

    private final TraceOperations traceOperations;

    public PlatformTracingV3Samples(@Nonnull TraceOperations traceOperations) {
        this.traceOperations = traceOperations;
    }

    /** {@code traceContext().traceId()} for logging and correlation. */
    public String currentTraceIdForLogging() {
        return traceOperations.traceContext()
                .traceId()
                .orElse("unknown");
    }

    /** Standard manual operation with void body. */
    public void recalculatePricing(long orderId, PricingService pricingService) {
        traceOperations.manual()
                .operation("recalculate-pricing")
                .run(() -> pricingService.recalculate(orderId));
    }

    /** Manual operation returning a value. */
    public Price calculatePrice(long orderId, PricingService pricingService) {
        return traceOperations.manual()
                .operation("calculate-price")
                .call(() -> pricingService.calculate(orderId));
    }

    /** Manual operation with checked exception. */
    public Order loadOrder(long orderId, OrderRepository repository) throws Exception {
        return traceOperations.manual()
                .operation("load-order")
                .callChecked(() -> repository.load(orderId));
    }

    /** Explicit ROOT span for scheduled or background work without an active parent. */
    public void runScheduledReconciliation(ReconciliationService service) {
        traceOperations.manual()
                .operation("nightly-reconciliation")
                .root()
                .run(service::reconcileAll);
    }

    /** DETACHED span without links (allowed topology). */
    public void runDetachedAudit(AuditService auditService) {
        traceOperations.manual()
                .operation("compliance-audit")
                .detached()
                .run(auditService::runOnce);
    }

    /** Database semantic builder under {@code manual().transport().database()}. */
    public List<Order> queryOrders(OrderRepository repository) {
        return traceOperations.manual()
                .transport()
                .database()
                .system("postgresql")
                .operation("SELECT")
                .collection("orders")
                .call(repository::findAll);
    }

    /** RPC client semantic builder under {@code manual().transport().rpc().client()}. */
    public Order fetchOrderViaRpc(OrderRpcClient client, long orderId) {
        return traceOperations.manual()
                .transport()
                .rpc()
                .client()
                .system("grpc")
                .service("orders.OrderService")
                .method("GetOrder")
                .serverAddress("orders.example.com")
                .call(() -> client.getOrder(orderId));
    }

    /**
     * Kafka batch consumer: ROOT + pre-start links (primary public links example).
     * {@code messageContexts} would normally be extracted from record headers via OTel propagator.
     */
    public void processKafkaBatch(
            List<SpanLinkContext> messageContexts,
            BatchProcessor processor,
            List<Record> records) {
        traceOperations.manual()
                .transport()
                .kafka()
                .consumer()
                .batch("orders")
                .root()
                .linkedTo(messageContexts.toArray(SpanLinkContext[]::new))
                .run(() -> processor.processBatch(records));
    }

    /** Alternative batch links via W3C traceparent strings (tracestate preserved when present). */
    public void processKafkaBatchFromTraceparents(
            List<String> traceparents,
            BatchProcessor processor,
            List<Record> records) {
        traceOperations.manual()
                .transport()
                .kafka()
                .consumer()
                .batch("orders")
                .root()
                .fromRemoteContext(traceparents.toArray(String[]::new))
                .run(() -> processor.processBatch(records));
    }

    /** Governed escape hatch: {@code spanFromSpec} with mandatory reason and reference. */
    public void legacyIntegrationCall(LegacyClient legacyClient) {
        SpanSpec spec = SpanSpec.builder("legacy-bridge-call")
                .category(SpanCategory.INTERNAL)
                .child()
                .reason(SpanSpecReason.LEGACY_INTEGRATION)
                .reference("PLATFORM-TRACING-1234")
                .attribute("integration.vendor", "acme")
                .build();

        traceOperations.manual()
                .spanFromSpec(spec)
                .run(legacyClient::invoke);
    }

    /** {@code TEMPORARY_WORKAROUND} requires a non-blank reference. */
    public void temporaryWorkaround(TemporaryClient client) {
        SpanSpec spec = SpanSpec.builder("vendor-sdk-workaround")
                .category(SpanCategory.INTERNAL)
                .child()
                .reason(SpanSpecReason.TEMPORARY_WORKAROUND)
                .reference("JIRA-5678")
                .build();

        traceOperations.manual()
                .spanFromSpec(spec)
                .call(client::callOnce);
    }

    /** Example of lenient traceparent parsing for batch header extraction loops. */
    public List<SpanLinkContext> extractLinksFromHeaders(List<String> traceparentHeaders) {
        return traceparentHeaders.stream()
                .map(RemoteContext::parseTraceparent)
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    // --- Stub types (no external dependencies) ---

    public interface PricingService {
        void recalculate(long orderId);

        Price calculate(long orderId);
    }

    public interface OrderRepository {
        Order load(long orderId) throws Exception;

        List<Order> findAll();
    }

    public interface ReconciliationService {
        void reconcileAll();
    }

    public interface AuditService {
        void runOnce();
    }

    public interface OrderRpcClient {
        Order getOrder(long orderId);
    }

    public interface BatchProcessor {
        void processBatch(List<Record> records);
    }

    public interface LegacyClient {
        void invoke();
    }

    public interface TemporaryClient {
        String callOnce();
    }

    public record Price(long orderId, java.math.BigDecimal amount) {
    }

    public record Order(long id, String status) {
    }

    public record Record(String topic, int partition, long offset) {
    }
}
```

---

<a id="file-platform-tracing-samples-build-gradle"></a>
## INLINE COPY: `platform-tracing-samples/build.gradle`

```gradle
description = 'Compilable TraceOperations v3 API usage examples for documentation (Slice 8). ' +
        'Not published to Nexus; no external services or Docker required.'

dependencies {
    api platform(project(':platform-tracing-bom'))
    implementation project(':platform-tracing-api')
}

// Samples are compile-only documentation artifacts; no tests required for Slice 8 gate.
tasks.named('test') {
    enabled = false
}
```

---

<a id="file-settings-gradle-excerpt"></a>
## INLINE COPY: ``settings.gradle`` (lines 60-67)

```gradle
// Тестовая инфраструктура для интеграционного тестирования прикладного кода (in-memory span exporter, JUnit 5 extension).
include 'platform-tracing-test'

// Compilable v3 API usage examples for documentation (Slice 8). Not published to Nexus.
include 'platform-tracing-samples'

// JMH micro-benchmark'и: внутренний инструмент для замера runtime overhead основных операций
// платформенной трассировки. Не публикуется в Nexus, не входит в релизный артефакт-набор.
```
