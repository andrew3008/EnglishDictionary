# TraceOperations Post-Remediation Review Package

## Executive summary

TraceOperations Slice 7 remediation B01–B10 is complete. The remediation closes the post-Slice 7 review items without changing the public `TraceOperations` API and without restoring v1 compatibility.

The default build is GREEN. Module tests and ArchUnit checks are GREEN. Grep gates confirm no regression of removed v1 symbols and no raw OTel span creation in the Kafka aspect package.

Git commit `a02bb94` on branch `master` captures the remediation state. Slice 8 has not started.

## What changed after post-Slice 7 review

| Area | Change |
|---|---|
| B01 Metrics | `MeteredTracingImplementation.recordException` no longer increments `exceptionsRecorded`; `MeteredSpanHandle` is the single increment point |
| B05 Topology | `AbstractSemanticSpanBuilder` now throws on repeated explicit topology setter (matches `DefaultSpanSpecBuilder`) |
| B06 Converter | `SpanAttributeValueConverter` rejects mixed/unsupported list values; documents empty-list type loss |
| B07 Semconv | `@DatabaseSemconvVersion("1.28.0")` added to `DatabaseTracing` |
| B03 Kafka aspect | `KafkaBatchLinksAspect` migrated from raw OTel span creation to v3 manual batch API |
| B02 Tests | Real `InMemorySpanExporter` integration tests for HTTP, DB, RPC builders |
| B04 E2E | Confirmed intentional `-PrunE2e` gating; no CI wiring change |
| B08 Kafka builder | Removed shadow `implementation`/`policy` fields in `KafkaConsumerSpanBuilderImpl` |
| B09/B10 Docs | Probe-span Javadoc, `TracingImplementation` extension caveat, R01/review package updates |

## Remediation B01–B10 status table

| ID | Item | Status | Evidence |
|---|---|---|---|
| B01 | Metrics double-count cleanup | **DONE** | `MeteredSpanHandleDoubleCountTest`, `MeteredMetricsCountTest` |
| B05 | Topology repeated setter consistency | **DONE** | `AbstractSemanticSpanBuilderTopologyRepeatedCallTest` |
| B06 | SpanAttributeValueConverter hardening | **DONE** | `SpanAttributeValueConverterMixedListTypeTest`, `SpanAttributeValueConverterEmptyListRoundTripTest` |
| B07 | `@DatabaseSemconvVersion` | **DONE** | `DatabaseTracingSemconvVersionMarkerTest` |
| B03 | KafkaBatchLinksAspect migration | **DONE** | `KafkaBatchAspectMigrationTest`; grep gate clean |
| B02 | HTTP/DB/RPC integration tests | **DONE** | `HttpSpanBuilderIntegrationTest`, `DatabaseSpanBuilderIntegrationTest`, `RpcSpanBuilderIntegrationTest` |
| B04 | E2E gating | **CONFIRMED INTENTIONAL** | `platform-tracing-e2e-tests/build.gradle` line 199 |
| B08 | Kafka shadow fields | **DONE** | grep gate: zero shadow assignments in `DefaultKafkaTracing.java` |
| B09 | Probe-span Javadoc | **DONE** | `TracingCoreAutoConfiguration.isFunctional` |
| B10 | Extension-point Javadoc | **DONE** | `TracingCoreAutoConfiguration` class Javadoc |
| — | TracedAspect | **CONFIRMED COMPLIANT** | Uses `manual().operation(...).start()`; no change |

## Architecture invariants preserved

```text
traceOperations = traceContext() + manual()
Single creation boundary: TracingImplementation.startSpan(SpanSpec)
No v1 API restored
No SpanRelation
No MeteredPlatformTracing
No Facade*SpanBuilder
No compatibility shim
No behavioral default methods added on public/SPI interfaces
SpanOptions.validateTopologyLinks unchanged
Frozen Slice 7 tests untouched (ObservationCoexistenceTest, BeanTopologyTest, DiagnosticsBoundaryTest, TracingDiagnosticsViewJsonContractTest)
```

## Verification command log

| Command | Result |
|---|---|
| `:platform-tracing-spring-boot-autoconfigure:test --tests "*MeteredSpanHandleDoubleCountTest*"` | **GREEN** |
| `:platform-tracing-core:test --tests "*AbstractSemanticSpanBuilderTopologyRepeatedCallTest*"` | **GREEN** |
| `:platform-tracing-core:test --tests "*SpanAttributeValueConverter*"` | **GREEN** |
| `:platform-tracing-api:test --tests "*DatabaseTracingSemconvVersionMarkerTest*"` | **GREEN** |
| `:platform-tracing-spring-boot-autoconfigure:test --tests "*KafkaBatchAspectMigrationTest*"` | **GREEN** |
| `:platform-tracing-core:test --tests "*HttpSpanBuilderIntegrationTest*" --tests "*DatabaseSpanBuilderIntegrationTest*" --tests "*RpcSpanBuilderIntegrationTest*"` | **GREEN** |
| `:platform-tracing-api:test :platform-tracing-core:test :platform-tracing-spring-boot-autoconfigure:test` | **GREEN** |
| `:platform-tracing-core:check :platform-tracing-spring-boot-autoconfigure:check` | **GREEN** |
| `.\gradlew.bat build` | **GREEN** |

E2E (`-PrunE2e`): **Not run locally**; requires Docker/Testcontainers and is intentionally gated.

## Grep gates

| Gate | Expected | Actual |
|---|---|---|
| `class MeteredPlatformTracing` | zero | **zero** |
| `enum SpanRelation` | zero | **zero** |
| `Facade*SpanBuilder` | zero | **zero** |
| Kafka raw OTel span creation (`Tracer`/`SpanBuilder`/`.startSpan(` in kafka package) | zero | **zero** |
| `this.implementation = implementation` in `DefaultKafkaTracing.java` | zero | **zero** |

Note: OTel `Context` / `SpanContext` reads for configured propagator extraction in `KafkaBatchLinksAspect` are intentional and allowed.

## KafkaBatchLinksAspect migration details

`KafkaBatchLinksAspect` no longer creates spans through raw OTel `Tracer`/`SpanBuilder`.

It still uses OTel `Context`/`SpanContext` for configured propagator extraction from `ConsumerRecord` headers via `OpenTelemetry.getPropagators().getTextMapPropagator()`.

Span creation now routes through:

```text
manual().transport().kafka().consumer().batch(destination).root().linkedTo(...)
```

Semantic change (intentional, Option A):

- Category: `KAFKA_CONSUMER` (was INTERNAL via raw OTel)
- Name: `"<destination> process"` (was `"<method> process batch"`)
- Attributes: `messaging.system`, `messaging.destination.name`, `messaging.operation=process`

TraceState is preserved into `SpanLinkContext` via full record constructor (W3C serialization from extracted `SpanContext.getTraceState()`).

Multi-topic destination fallback: listener id if non-blank, else advised method name. Never uses first record topic for multi-topic batches.

Wiring: `PlatformKafkaAutoConfiguration` injects `OpenTelemetry` + `TraceOperations`; `@ConditionalOnBean(OpenTelemetry.class)` retained for propagators.

## R01 status after remediation

R01 remains structurally closed:

- Slice 6: `MeteredTracingImplementation` on SPI boundary (not public facade)
- Slice 7: diagnostics DTO boundary + Observation coexistence
- **Remediation B03:** aspect single-creation boundary closed (`KafkaBatchLinksAspect` no longer uses raw OTel span creation)

See `docs/known-issues/R01.md` for updated proof gates including `KafkaBatchAspectMigrationTest`.

## Known limitations

1. **Patch unavailable:** Local git history starts at commit `a02bb94` (root). A meaningful patch against pre-refactor base is unavailable from this workspace.
2. **Kafka aspect test scope:** `KafkaBatchAspectMigrationTest` is assembly-level via `AspectJProxyFactory`, not full Spring Kafka listener container e2e.
3. **E2E gating:** Full container/agent validation remains under `-PrunE2e` (Docker/Testcontainers required).
4. **Minor Gradle note:** `testImplementation spring-kafka` added in remediation for aspect migration test compilation only.

## Open questions for Perplexity reviewer

1. Is assembly-level `KafkaBatchAspectMigrationTest` sufficient pre-production proof, given `-PrunE2e` exists for container validation?
2. Is the intentional semantic drift (INTERNAL → KAFKA_CONSUMER, new span name/attributes) acceptable for batch listeners?
3. Is `SpanAttributeValueConverter` homogeneity guard appropriately scoped (SPI boundary only, no public API change)?
4. Are HTTP/DB/RPC integration tests sufficient, or should Kafka batch get a similar exporter-level test outside the aspect?
5. Any remaining risk that `MeteredTracingImplementation.recordException` two-arg path could reintroduce double-count via future callers?

## Suggested Perplexity prompt

Act as a principal Java platform architect, Spring Boot observability expert, OpenTelemetry/Micrometer reviewer, and adversarial code reviewer.

Review the attached post-remediation TraceOperations package.

Focus on:

- whether B01–B10 remediation correctly fixes the post-Slice 7 review findings;
- whether KafkaBatchLinksAspect migration truly removes raw OTel span creation while preserving propagator extraction and tracestate;
- whether metrics double-count risk is closed;
- whether topology repeated setter policy is consistent;
- whether SpanAttributeValueConverter guard is correct and not over-broad;
- whether `@DatabaseSemconvVersion` is consistent with existing semconv version markers;
- whether real exporter integration tests are meaningful;
- whether e2e gating is acceptable;
- whether any v1 API or compatibility shim was accidentally restored;
- whether R01 remains structurally closed.

Return:

- executive verdict;
- blockers;
- high-risk issues;
- test gaps;
- architecture drift;
- required patch list;
- go/no-go for Slice 8.

## Post-Perplexity hardening

Following Sonnet 5 review (`ACCEPT WITH CHANGES`), the following non-blocking but useful hardening items were completed before Slice 8:

- H2: added exporter-level `KafkaBatchSpanBuilderIntegrationTest`.
- H3: strengthened `MeteredTracingImplementation.recordException(...)` double-count regression coverage.
- M3: completed `SpanAttributeValueConverter` list coverage for String/Long/Double/Boolean and invalid list cases.

The remaining high-risk item H1 — full Spring Kafka container / Agent validation — remains intentionally gated under `-PrunE2e` and is required before production sign-off, not before Slice 8.

## Scope confirmation

- No production Java code changed for this packaging task.
- No tests changed for this packaging task.
- No Gradle changed for this packaging task.
- Slice 8 not started.

## Attached artifacts

| Artifact | Path |
|---|---|
| Review package (this file) | `docs/analysis/perplexity-review/platform-tracing-post-remediation-review-package.md` |
| File index | `docs/analysis/perplexity-review/platform-tracing-post-remediation-file-index.md` |
| Changed sources (102 Java files) | `docs/analysis/perplexity-review/platform-tracing-post-remediation-changed-sources.md` |
| Diff stat | `docs/analysis/perplexity-review/platform-tracing-post-remediation-diff-stat.txt` (patch unavailable notice) |
| Name status | `docs/analysis/perplexity-review/platform-tracing-post-remediation-name-status.txt` (best-effort remediation list) |
| Patch | **not generated** (no meaningful git base) |
| Prior Slice 7 bundle | `docs/analysis/perplexity-review/platform-tracing-post-slice-7-changed-sources.md` |
| R01 | `docs/known-issues/R01.md` |
| Refactoring plan | `docs/analysis/platform-tracing-refactoring-plan.md` |
| Micrometer ADR | `docs/decisions/ADR-platform-tracing-micrometer-observation-boundary.md` |
