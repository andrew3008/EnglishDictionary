# platform-tracing-api Batch A Post-Audit

Date: 2026-07-11  
Repository: `E:\Platform_Traces`  
Auditor role: strict senior Java API architecture auditor  
Scope: Batch A naming refactor only (Batch B/C must remain untouched)

---

## 1. Verdict

**PASS WITH WARNINGS**

Batch A is correctly implemented in Java source, guards, ADR, and CHANGELOG. Targeted Gradle compile/tests are green. No stale Batch A symbols remain in production Java sources.

Warnings are limited to documentation drift in user-facing tracing reference docs and residual historical analysis text. These do not block code commit but should be fixed before external API publication.

---

## 2. Architecture Checks

| Check | Evidence | Result |
| --- | --- | --- |
| Relationship family renamed atomically | `SpanRelationship.java`, `SpanRelationshipSpec.java`, `ImmutableSpanRelationshipSpec.java`; `SpanSpec.relationship()` in `SpanSpec.java:27`; runtime uses `spec.relationship().kind()` in `OtelTracingRuntime.java:58-69` | PASS |
| Builder base renamed | `ManualSpanBuilder.java`; 9 extending interfaces (`DatabaseSpanBuilder`, `OperationSpanBuilder`, HTTP/RPC/Kafka builders) | PASS |
| Execution surface renamed | `SpanExecution.java`; `SpanExecutionImpl.java` in core; `DefaultManualTracing.spanFromSpec()` returns `SpanExecution` | PASS |
| Enrichment DSL renamed | `SpanEnrichment.java`, `GenericSpanEnrichment.java`; core `DefaultSpanEnrichment`, `DefaultGenericSpanEnrichment` | PASS |
| Semconv validation mode renamed in API | `SemconvValidationMode.java` exists; `ValidationMode.java` absent from `platform-tracing-api` | PASS |
| `DatabaseTracing` merged | `DatabaseTracing.java` not found; `TransportTracing.database()` returns `DatabaseSpanBuilder` (`TransportTracing.java:14`) | PASS |
| `@DatabaseSemconvVersion` on API interface | `DatabaseSpanBuilder.java:9` has `@DatabaseSemconvVersion("1.28.0")` | PASS |
| Public `SpanScope` removed | `SpanScope.java` not found; `SpanScopeRemovalTest` asserts `ClassNotFoundException` | PASS |
| Internal lifecycle fallback | Core uses `OwningSpanScope` (`core/runtime/otel/scope/OwningSpanScope.java`) wired via `SpanHandleImpl`; no public API `SpanScope` | PASS |
| Batch B/C names untouched | `TraceContextView`, `TracingRequestContext`, `RemoteContext`, `SensitiveDataRule`, `PlatformOutboundInjector`, `fromRemoteContext()` still present | PASS |
| Forbidden renames untouched | `SpanCategory`, `SpanResult`, `SemconvKeys`, `SemconvViolation`, `TracingControlProtocolKeys` unchanged | PASS |

---

## 3. Stale Name Search

Search method: workspace `Grep` across `*.java` in `platform-tracing-api/src`, `platform-tracing-core/src`, `platform-tracing-spring-boot-autoconfigure/src`, `platform-tracing-otel-extension/src`, `platform-tracing-e2e-tests/src`, `platform-tracing-bench/src`.

| Old Name | Result | Notes |
| --- | --- | --- |
| `Topology` | CLEAN in Java src | Remains only in docs/history (`docs/decisions/ADR-platform-tracing-topology-links.md`, analysis archives) |
| `SpanTopologySpec` | CLEAN in Java src | Docs/history only |
| `ImmutableSpanTopologySpec` | CLEAN in Java src | Docs/history only |
| `SpecifiedSpan` | CLEAN in Java src | Stale in `docs/tracing/platform-tracing-v3-manual-api.md` |
| `EnrichScope` | CLEAN in Java src | Stale in `docs/tracing/traceability.md` |
| `GenericEnrichScope` | CLEAN in Java src | Stale in `docs/tracing/traceability.md` |
| `PlatformSpanBuilder` | CLEAN in Java src | Stale in `docs/tracing/platform-tracing-v3-manual-api.md`; class renamed to `AbstractSemanticSpanBuilder` in core (acceptable, not Batch A API symbol) |
| `ValidationMode` (API) | CLEAN in API/core/autoconfigure Java src | Separate `otel.extension.configuration.ValidationMode` enum exists in otel-extension — different type, pre-existing, not Batch A regression |
| `SpanScope` (API) | CLEAN except guard test | Only `SpanScopeRemovalTest` references FQN intentionally; core uses `OwningSpanScope` / `OtelSpanScope` in comments |
| `DatabaseTracing` | CLEAN in Java src | Deleted; marker test renamed |
| `.options()` | CLEAN in Java src | Replaced by `.relationship()` |

---

## 4. New API Shape

### Relationship model

```java
// SpanSpec.java
SpanRelationshipSpec relationship();

// SpanRelationshipSpec.java
SpanRelationship kind();
List<SpanLinkContext> links();
```

Runtime chain in `OtelTracingRuntime`:

```java
spec.relationship().kind()
spec.relationship().links()
```

Factory/builder grammar preserved: `child()`, `root()`, `detached()`, `linkedTo(...)`.

### Manual builder hierarchy

- Base: `ManualSpanBuilder<B>`
- Transport return type: `TransportTracing.database()` → `DatabaseSpanBuilder`
- Escape hatch: `ManualTracing.spanFromSpec(spec)` → `SpanExecution`

### Structural removals

| Item | State |
| --- | --- |
| `DatabaseTracing.java` | Deleted |
| `SpanScope.java` (API) | Deleted |
| `SpanTopologySpecTopologyTest.java` | Deleted; replaced by `SpanRelationshipSpecTest` |
| `DatabaseTracingSemconvVersionMarkerTest` | Replaced by `DatabaseSpanBuilderSemconvVersionMarkerTest` |

### Internal lifecycle (no public `SpanScope`)

Implementation chose direct `OwningSpanScope` in `platform-tracing-core` rather than package-private `SpanScope` interface fallback. This satisfies the Batch A goal of removing public API `SpanScope`.

---

## 5. Forbidden Change Check

| Forbidden item | Evidence | Result |
| --- | --- | --- |
| `SpanCategory` rename | `SpanCategory.java` unchanged | PASS |
| `SpanResult` rename | `SpanResult.java` unchanged; wire values `success`, `failure`, etc. preserved | PASS |
| `SemconvKeys` rename | `SemconvKeys.java` unchanged | PASS |
| `SemconvViolation` rename | `SemconvViolation.java` unchanged | PASS |
| `TracingControlProtocol` keys | `TracingControlProtocolKeys.java` unchanged (`contractVersion`, `operation`, `source`, ...) | PASS |
| Batch B: `TraceContextView` | Still `api.manual.TraceContextView` | PASS |
| Batch B: `TracingRequestContext` | Not prematurely renamed | PASS |
| Batch B: `RemoteContext` / `fromRemoteContext()` | Still present (`DefaultSpanSpecBuilder.java`, `ManualSpanBuilder.java`) | PASS |
| Batch B: `SensitiveDataRule` | Still `api.spi.SensitiveDataRule` | PASS |
| Batch B: propagation control types | `PlatformOutboundInjector`, `PlatformPropagationDecision` unchanged | PASS |

---

## 6. Docs / ADR / Changelog Review

| Artifact | Expected | Evidence | Result |
| --- | --- | --- | --- |
| ADR | `docs/decisions/ADR-api-naming-refactor-batch-a.md` | Exists; old→new table; `SpanRelationshipSpec.kind()` vs OTel `SpanKind` clarified | PASS |
| CHANGELOG | `platform-tracing-api/CHANGELOG.md` | Exists; "Breaking Changes - Pre-Production Rename" table complete | PASS |
| Inventory update | `docs/analysis/platform-tracing-api-class-hierarchy-inventory.md` | Footer "Batch A Accepted Update - 2026-07-11" added; body still contains pre-refactor analysis | WARN |
| Naming options update | `docs/analysis/platform-tracing-api-model-naming-options.md` | Footer "Batch A Accepted Update - 2026-07-11" added; body still contains pre-decision scoring | WARN |
| User-facing API reference | No stale Batch A names | `docs/tracing/platform-tracing-v3-manual-api.md` still documents `PlatformSpanBuilder`, `SpecifiedSpan`, `DatabaseTracing`, `SpanSpec.options()`, `SpanOptions` | FAIL (docs only) |
| Traceability matrix | No stale Batch A names | `docs/tracing/traceability.md` still references `EnrichScope`, `GenericEnrichScope` | WARN |

### Javadoc notes

- `SpanHandle.java` — no broken `SpanScope` links (clean)
- `SpanRelationship.java` — enum Javadoc still says "Топология (вид) связи" (semantic drift from new name; cosmetic)
- `PlatformTracingMetrics.java` — references `OtelSpanScope` in comments (internal naming, not API `SpanScope`)

---

## 7. Test / Guard Review

| Guard / Test | Expected | Evidence | Result |
| --- | --- | --- | --- |
| `V3ManualApiArchTest` allowlist | `"SpanRelationshipSpec"` | `V3ManualApiArchTest.java:31-35` | PASS |
| Relationship runtime gate | `SpanRelationshipSpecTest` | `platform-tracing-core/.../SpanRelationshipSpecTest.java` exists; old `SpanTopologySpecTopologyTest` deleted | PASS |
| Database semconv marker | `DatabaseSpanBuilderSemconvVersionMarkerTest` | Asserts `@DatabaseSemconvVersion("1.28.0")` on `DatabaseSpanBuilder` | PASS |
| Public `SpanScope` absence | `SpanScopeRemovalTest` | Asserts `ClassNotFoundException` for API FQN | PASS |
| Javadoc broken links to API `SpanScope` | None | No `@link` to `api.span.SpanScope` in production sources | PASS |

---

## 8. Gradle Verification

Commands executed:

```bash
.\gradlew.bat compileJava compileTestJava :platform-tracing-bench:compileJmhJava :platform-tracing-otel-extension:compileJava :platform-tracing-api:test :platform-tracing-core:test :platform-tracing-spring-boot-autoconfigure:test
```

| Step | Result |
| --- | --- |
| `compileJava` / `compileTestJava` | BUILD SUCCESSFUL |
| `:platform-tracing-bench:compileJmhJava` | UP-TO-DATE / SUCCESS |
| `:platform-tracing-otel-extension:compileJava` | UP-TO-DATE / SUCCESS |
| `:platform-tracing-api:test` | UP-TO-DATE / SUCCESS |
| `:platform-tracing-core:test` | UP-TO-DATE / SUCCESS |
| `:platform-tracing-spring-boot-autoconfigure:test` | UP-TO-DATE / SUCCESS |

Full `./gradlew build` was not run; targeted verification is sufficient for Batch A scope.

---

## 9. Findings

### P0

None. All Batch A Java renames, structural actions, and guard tests are correctly implemented. Production Java sources are clean.

### P1

1. **`docs/tracing/platform-tracing-v3-manual-api.md` advertises pre-Batch-A API names** — documents `PlatformSpanBuilder`, `SpecifiedSpan`, `DatabaseTracing`, `SpanSpec.options()`, `SpanOptions`. This is the primary user-facing manual API reference and will mislead consumers after commit.

2. **`docs/tracing/traceability.md` references old enrichment names** — still lists `EnrichScope` / `GenericEnrichScope` in traceability matrix rows P13-05.

### P2

1. **Analysis docs body not rewritten** — `platform-tracing-api-class-hierarchy-inventory.md` and `platform-tracing-api-model-naming-options.md` have accepted-update footers but retain extensive pre-refactor narrative. Acceptable as historical analysis; confusing if read without scrolling to footer.

2. **`SpanRelationship` enum Javadoc still uses "Топология" wording** — `SpanRelationship.java:4` contradicts the rename rationale (relationship vs topology metaphor).

3. **`otel-extension.configuration.ValidationMode` coexists with `SemconvValidationMode`** — not a Batch A defect (separate scrubbing/config enum predating rename), but naming collision risk for future contributors. Out of Batch A scope; note for Batch B+ hygiene.

---

## 10. Recommendation

**Batch A is ready to commit** from a code and test perspective. The implementation matches the approved arbitration plan for all in-scope renames and structural actions.

**Before external API publication**, fix P1 documentation:

- Update `docs/tracing/platform-tracing-v3-manual-api.md` to `ManualSpanBuilder`, `SpanExecution`, `DatabaseSpanBuilder`, `SpanRelationshipSpec`, `SpanSpec.relationship()`, `SpanEnrichment` / `GenericSpanEnrichment`.
- Update `docs/tracing/traceability.md` enrichment row to new names.

**Batch B can start** after Batch A is merged to `master`. Prerequisite is satisfied in working tree: `ManualSpanBuilder`, `SpanSpecBuilder`, and `fromRemoteContext()` are in their post-Batch-A state and Batch B target types remain untouched.

Suggested commit message scope: `refactor(api): Batch A pre-production naming — SpanRelationship family, ManualSpanBuilder, remove SpanScope/DatabaseTracing`.
