# Span Processors Local Dossier

> **Scope:** Local repository evidence only. No production/test changes.  
> **Date:** 2026-06-20  
> **Package:** `space.br1440.platform.tracing.otel.extension.processor` (+ related factory, exporter, tests, docs)  
> **Related:** [enriching-span-processor-db-semconv-local-dossier.md](./enriching-span-processor-db-semconv-local-dossier.md)

---

## 1. Executive Summary

The `platform-tracing-otel-extension` module implements **nine** span-processor classes under `processor/`: seven enrichment/validation pipeline delegates wrapped by `PlatformCompositeSpanProcessor`, plus a separate export processor `PlatformDropOldestExportSpanProcessor`. Transport export is further wrapped by `SafeSpanExporter` (in `exporter/`, not `processor/`).

**Registration path:** OTel Java Agent → `PlatformAutoConfigurationCustomizer` (SPI) → `PlatformSpanProcessorFactory.registerSpanProcessors()` → single `PlatformCompositeSpanProcessor` on `SdkTracerProviderBuilder`. Export processor registered separately via `PlatformExportProcessorFactory`.

**Deterministic factory order** (when all domains enabled): Baggage → Enriching → Scrubbing → Validating → Classification → Watchdog → Metrics. Documented and partially tested in `PipelinePolicyCharacterizationTest`.

**Hot-path pattern:** Most pipeline processors use `ExtendedSpanProcessor.onEnding` (mutable span, before export). `PlatformCompositeSpanProcessor` isolates delegate failures (§37 non-blocking). `TracingValidationException` is the only intentional fail-fast escape from composite `onEnding`.

**Local gaps for Deep Research:** OTel `ExtendedSpanProcessor` lifecycle guarantees, industry processor ordering, scrubbing security patterns, agent extension classloader hygiene, generated semconv vs raw keys.

---

## 2. Repository Scope Inspected

| Area | Paths |
|---|---|
| Production processors | `platform-tracing-otel-extension/src/main/java/.../processor/*.java` (10 files) |
| Factory wiring | `.../factory/PlatformSpanProcessorFactory.java`, `PlatformExportProcessorFactory.java`, `PlatformAutoConfigurationCustomizer.java` |
| SPI | `src/main/resources/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider` |
| Exporter (related) | `.../exporter/SafeSpanExporter.java` |
| Config | `.../configuration/*ExtensionConfig.java`, `ExtensionConfig.java` |
| Tests | `.../processor/*Test*.java` (30+ files), `PlatformAutoConfigurationCustomizer*Test.java` |
| ArchUnit | `.../arch/OtelDirectIntegrationExtensionSpiRules.java`, `ResourceKeysNotInSpanProcessorsArchTest.java` |
| JMH | `platform-tracing-bench/src/jmh/.../CompositePipelineBenchmark.java`, `ValidatingSpanProcessorBenchmark.java`, `ScrubbingEngineBenchmark.java`, `QueueOfferBenchmark.java` |
| Docs/ADR | `docs/decisions/ADR-*.md`, `docs/semconv-mapping.md`, `docs/SUPPORTED.md` |
| Gradle | `gradle.properties`, `platform-tracing-bom/build.gradle`, `platform-tracing-otel-extension/build.gradle` |

---

## 3. Processor Inventory

| Class | File | Interface | Lifecycle Methods | Primary Responsibility | Hot Path? |
|---|---|---|---|---|---|
| `PlatformCompositeSpanProcessor` | `processor/PlatformCompositeSpanProcessor.java` | `ExtendedSpanProcessor` | onStart, onEnding, onEnd, shutdown, forceFlush, close | §37 isolation + per-delegate error counters | **Yes** (wraps all delegates) |
| `BaggageSpanProcessor` | `processor/BaggageSpanProcessor.java` | `SpanProcessor` | onStart (required), shutdown | Copy allowlisted baggage → `baggage.*` span attrs | Yes (onStart) |
| `EnrichingSpanProcessor` | `processor/EnrichingSpanProcessor.java` | `ExtendedSpanProcessor` | onStart, onEnding (required) | platform.type/result, DB reclass, remote service, baggage attrs | **Yes** |
| `ScrubbingSpanProcessor` | `processor/ScrubbingSpanProcessor.java` | `ExtendedSpanProcessor` | onEnding (required), shutdown, forceFlush | Sensitive-data scrubbing all attr types | **Yes** (heaviest) |
| `ValidatingSpanProcessor` | `processor/ValidatingSpanProcessor.java` | `ExtendedSpanProcessor` | onEnding (required) | Required platform.type/result; strict fail-fast | Yes |
| `ClassificationSpanProcessor` | `processor/ClassificationSpanProcessor.java` | `ExtendedSpanProcessor` | onEnding (required) | duration_class + priority for tail sampling | Yes |
| `SpanWatchdogProcessor` | `processor/SpanWatchdogProcessor.java` | `ExtendedSpanProcessor` | onStart, onEnd (required), forceFlush | Span/trace timeout force-close | Mixed (bg scheduler + onStart/onEnd) |
| `MetricsSpanProcessor` | `processor/MetricsSpanProcessor.java` | `SpanProcessor` | onEnd (required) | Internal counters (ended/error/timeout/dropped) | Yes (onEnd) |
| `PlatformDropOldestExportSpanProcessor` | `processor/PlatformDropOldestExportSpanProcessor.java` | `SpanProcessor` | onEnd, shutdown, forceFlush | Bounded drop-oldest export queue + worker | **Yes** (onEnd offer) |

**Support class (not a processor):** `ValidationPolicyHolder.java` — CAS policy snapshots for `ValidatingSpanProcessor`.

---

## 4. Processor-by-Processor Details

### 4.1 PlatformCompositeSpanProcessor

#### Responsibility
Single SDK registration point for enrichment pipeline; per-delegate exception isolation; `TracingValidationException` re-thrown from `onEnding` (strict validation).

#### Runtime Lifecycle
- `onStart`: calls delegates where `isStartRequired()`
- `onEnding`: calls `ExtendedSpanProcessor` delegates where `isOnEndingRequired()`
- `onEnd`: calls delegates where `isEndRequired()`
- Order: **fixed list order** from constructor (factory `ArrayList` append order)

#### Attributes Read/Written
None directly — delegates mutate span.

#### External Side Effects
Rate-limited WARN logs; `AtomicLong` error counters exposed via `getProcessorErrorCounts()` for JMX.

#### Exception Handling
Swallows non-fatal throwables from delegates; propagates `TracingValidationException`; `PlatformThrowables.propagateIfFatal`.

#### Config Inputs
Delegate list built at startup by factory — not dynamically reordered.

#### Tests
`PipelinePolicyCharacterizationTest`, composite used across many processor tests via `SpanProcessorHarness`.

#### Risks
**MEDIUM** — `onEnding` order matters (Enriching before Scrubbing before Validating). Mis-order would break scrubbing of enriched attrs or validation of scrubbed state.

#### Questions for Deep Research
DR-01 (ExtendedSpanProcessor lifecycle vs MultiSpanProcessor).

---

### 4.2 BaggageSpanProcessor

#### Responsibility
Copy allowlisted W3C baggage entries to span attributes prefixed `baggage.`

#### Runtime Lifecycle
- `onStart` only (`isStartRequired=true`)
- Reads `Baggage.fromContext(parentContext)`
- Deny patterns: compiled `Pattern` list (case-insensitive)

#### Attributes Written
`baggage.<originalKey>` (dynamic keys from allowlist)

#### Exception Handling
None explicit — no try/catch in hot path.

#### Config Inputs
Startup: `BaggageExtensionConfig.allowlistKeys()`, `denyPatterns()`. Skipped if allowlist empty.

#### Tests
Limited direct unit tests in processor package; configured via factory tests.

#### Risks
**MEDIUM** — regex deny on every baggage key at onStart; **HIGH** cardinality if allowlist misconfigured.

---

### 4.3 EnrichingSpanProcessor

#### Responsibility
Default `platform.trace.type` from `SpanKind`; `platform.trace.result` from status; DB CLIENT reclassification; ERROR CLIENT remote service + MDC; baggage-derived request id / policy version.

#### Runtime Lifecycle
- `onStart`: type default, baggage attrs
- `onEnding`: result, DB override (`DbSemanticAttributeKeys`), remote service

#### Key Attributes
See §6. DB detection: `db.system.name` OR `db.system` (see [enriching-span-processor-db-semconv-local-dossier.md](./enriching-span-processor-db-semconv-local-dossier.md)).

#### External Side Effects
`RemoteServiceMdc.putIfPresent` on ERROR CLIENT spans.

#### Performance Notes
`span.toSpanData().getStatus()` and `getTraceId()` on onEnding; IP parsing without DNS (by design).

#### Tests
`EnrichingSpanProcessorTest`, `EnrichingSpanProcessorAdvancedTest`, `EnrichmentPolicyCharacterizationTest`.

#### Risks
**LOW–MEDIUM** on DB branch; **MEDIUM** on remote service string parsing.

#### Questions for Deep Research
DR-02, DR-03.

---

### 4.4 ScrubbingSpanProcessor

#### Responsibility
Apply `SensitiveDataRule` list on all span attribute types at `onEnding`; MASK/DROP/HASH/TRUNCATE; runtime policy via `ScrubbingPolicyHolder`.

#### Runtime Lifecycle
- Iterates all attributes on span at onEnding
- `ThreadLocal<Mac>` for HMAC when key configured
- Circuit breaker per rule (`RuleExecutionWrapper`)

#### Attributes Read/Written
Reads all attribute keys dynamically; overwrites values (DROP → empty string / sentinel).

#### Config Inputs
Startup rules from `ScrubbingRulesLoader`; runtime toggle via JMX (`tryApplyPolicyUpdate`).

#### Tests
`ScrubbingSpanProcessorTest`, `ScrubbingSpanProcessorAdvancedTest`, `ScrubbingSpanProcessorCharacterizationTest`, `ScrubbingSecurityCharacterizationTest`, `ScrubbingRuleMatrixCharacterizationTest`, JMH `ScrubbingEngineBenchmark`, `ScrubbingPerRuleBenchmark`.

#### Risks
**HIGH** — full attribute scan every span; regex rules; HMAC alloc on HASH path.

#### Questions for Deep Research
DR-06, DR-07.

---

### 4.5 ValidatingSpanProcessor

#### Responsibility
Ensure `platform.trace.type` and `platform.trace.result` present before export; STRICT throws `TracingValidationException`; LENIENT sets `platform.validation.missing` + throttled WARN.

#### Runtime Lifecycle
- `onEnding` only
- `isPresent` checks span attr OR resource fallback via `span.toSpanData().getResource()`

#### Config Inputs
Startup strict/strictRuntimeAllowed; runtime JMX updates via `ValidationPolicyHolder`.

#### Tests
`ValidatingSpanProcessorTest`, `ValidatingSpanProcessorCharacterizationTest`, `ValidationPolicy*Test`, JMH `ValidatingSpanProcessorBenchmark`.

#### Risks
**MEDIUM** — `toSpanData()` on validation path; strict mode throws on app thread.

#### Questions for Deep Research
DR-05.

---

### 4.6 ClassificationSpanProcessor

#### Responsibility
Set `platform.trace.duration_class` (fast/normal/slow) and `platform.trace.priority` (high/normal) for Collector tail sampling.

#### Runtime Lifecycle
- `onEnding`: uses `span.getLatencyNanos()` and `span.toSpanData().getStatus()`
- Does not overwrite existing `platform.trace.priority`

#### Tests
`ClassificationSpanProcessorTest`, `ClassificationCharacterizationTest`.

#### Risks
**LOW** — `toSpanData()` on hot path.

#### Questions for Deep Research
DR-01 (onEnding ordering vs enrichment).

---

### 4.7 SpanWatchdogProcessor

#### Responsibility
Force-close spans exceeding spanTimeout (default 30s) or traces exceeding traceTimeout (default 60s).

#### Runtime Lifecycle
- `onStart`: register in `ConcurrentHashMap`
- `onEnd`: unregister
- Background `ScheduledExecutorService` + `forceFlush` scan
- Sets `platform.timeout`, `platform.result=timeout`, ERROR status, calls `span.end()`

#### Tests
`SpanWatchdogProcessorTest`, concurrency tests referenced in javadoc.

#### Risks
**HIGH** — mutable shared state; background thread; calls `span.end()` from watchdog thread (NEEDS_PERPLEXITY_DEEP_RESEARCH for thread safety).

---

### 4.8 MetricsSpanProcessor

#### Responsibility
Count ended/error/timeout spans and SDK-dropped attrs/events/links.

#### Runtime Lifecycle
- `onEnd`: `span.toSpanData()` for totals vs present sizes

#### Tests
Indirect via JMX/actuator wiring; limited dedicated unit tests in processor package.

#### Risks
**MEDIUM** — `toSpanData()` every ended span.

---

### 4.9 PlatformDropOldestExportSpanProcessor

#### Responsibility
Replace stock BSP with guaranteed drop-oldest bounded queue; async worker export.

#### Runtime Lifecycle
- `onEnd`: enqueue `SpanData` snapshot (non-blocking)
- Worker thread batch export with timeout
- **Not** inside `PlatformCompositeSpanProcessor`

#### Tests
`PlatformDropOldestExportSpanProcessor*Test`, `BspReplacementSpikeTest`, JMH `QueueOfferBenchmark`.

#### Risks
**HIGH** — queue lock contention; span dropping without priority (documented GAP in ADR-performance-model).

#### Questions for Deep Research
DR-04, DR-07.

---

## 5. Registration and Ordering

```
ServiceLoader
  META-INF/services/...AutoConfigurationCustomizerProvider
    → PlatformAutoConfigurationCustomizer.customize()
         addPropertiesCustomizer → ExtensionConfig (once)
         addTracerProviderCustomizer → PlatformSpanProcessorFactory.registerSpanProcessors()
              delegates list (deterministic append order)
              → PlatformCompositeSpanProcessor(delegates)
              → tpBuilder.addSpanProcessor(composite)
         addSpanProcessorCustomizer → PlatformExportProcessorFactory (DROP_OLDEST opt-in)
```

| Property | Behavior |
|---|---|
| Processor order | **Deterministic** — `ArrayList` insertion order in `PlatformSpanProcessorFactory` |
| Order tested? | **Partial** — `PipelinePolicyCharacterizationTest.documented_factory_processor_order` (class list only); composite interaction tests for Enriching→Scrubbing→Validating |
| Classloader | Agent extension isolated classloader (`agentExtensionJar` self-contained) |
| App configuration | Spring `TracingProperties` → env/`OTEL_*` via dual-channel; extension reads `ConfigProperties` at startup |
| Dynamic config | **Scrubbing** + **Validation** policy via JMX (`PlatformTracingControlMBean`); others startup-only |
| Processor list rebuild | **No** — delegate list fixed at startup; runtime toggles affect policy holders inside processors |

---

## 6. Attribute / Semconv Inventory

| Attribute | R/W | Owner Class | Purpose | Local Status | Needs Deep Research? |
|---|---|---|---|---|---|
| `platform.trace.type` | W (Enriching), R (Validating) | EnrichingSpanProcessor, ValidatingSpanProcessor | Platform category | Custom (maps to SpanCategory) | No |
| `platform.trace.result` | W | EnrichingSpanProcessor, SpanWatchdogProcessor | success/failure/timeout | Custom | No |
| `platform.remote.service` | W | EnrichingSpanProcessor | ERROR CLIENT upstream name | Custom | DR-03 |
| `platform.request.id` | W | EnrichingSpanProcessor | From baggage | Custom | No |
| `platform.policy.version` | W | EnrichingSpanProcessor | From baggage | Custom | No |
| `platform.trace.duration_class` | W | ClassificationSpanProcessor | fast/normal/slow | Custom | No |
| `platform.trace.priority` | W | ClassificationSpanProcessor | high/normal for tail sampling | Custom | No |
| `platform.timeout` | W | SpanWatchdogProcessor | span/trace timeout marker | Custom | No |
| `platform.validation.missing` | W | ValidatingSpanProcessor | Diagnostic missing keys | Custom | No |
| `baggage.*` | W | BaggageSpanProcessor | Propagated baggage | Custom prefix | No |
| `db.system.name` | R | EnrichingSpanProcessor | DB span detection | Local doc: stable ≥1.28 (ADR-db-semconv-detection) | DR-02 |
| `db.system` | R | EnrichingSpanProcessor | DB span detection | Local doc: legacy ≤1.27 (ADR-db-semconv-detection) | DR-02 |
| `peer.service`, `rpc.service`, `server.address` | R | EnrichingSpanProcessor | Remote service extraction | Local doc: OTel semconv (DEFAULT_REMOTE_SERVICE_PRIORITY) | DR-03 |
| `http.*`, `url.*` (dynamic) | R/W | ScrubbingSpanProcessor | Scrubbing targets | Mixed — rules-driven | DR-06 |
| All span attributes | R/W | ScrubbingSpanProcessor | Rule matching | Dynamic | DR-06 |
| Resource keys (`service.name`, etc.) | R | ValidatingSpanProcessor | Fallback for missing check | OTel resource semconv (local ADR) | No |

**Not used in processor package:** `SemanticAttributes`, `IncubatingAttributes`, `opentelemetry-semconv` artifact.

---

## 7. Policy and Classification Logic

| Concern | Owner | Rule (summary) | Tests | Gaps |
|---|---|---|---|---|
| platform.type default | EnrichingSpanProcessor | SpanKind → category; CLIENT+db.* → database if type null or http_client | EnrichingSpanProcessorTest, EnrichmentPolicyCharacterizationTest | CLIENT without DB explicit negative test |
| platform.result | EnrichingSpanProcessor | status ERROR → failure else success; no overwrite | EnrichingSpanProcessorTest | — |
| platform.remote.service | EnrichingSpanProcessor | CLIENT+ERROR only; priority list; skip IP-like server.address | EnrichingSpanProcessorTest, AdvancedTest | — |
| DB classification | EnrichingSpanProcessor | `DbSemanticAttributeKeys` presence OR | DB tests + dual-key test | — |
| duration/priority | ClassificationSpanProcessor | latency thresholds + ERROR → high priority | ClassificationSpanProcessorTest | — |
| scrubbing | ScrubbingSpanProcessor | First matching rule by priority; runtime enabled flag | Many Scrubbing*Test | Events/links not scrubbed in processor |
| validation strict/lenient | ValidatingSpanProcessor | Missing type/result → throw or warn | ValidationPolicy*Test | Resource identity not per-span |
| span/trace timeout | SpanWatchdogProcessor | 30s/60s defaults | SpanWatchdogProcessorTest | Cross-thread end() behavior |
| sampling | CompositeSampler (not processor) | Head sampling separate path | Sampler tests | — |
| safe export | SafeSpanExporter | Catch all export throwables | SafeSpanExporter tests | Not in processor package |
| drop-oldest | PlatformDropOldestExportSpanProcessor | FIFO eviction oldest | BSP spike tests | No priority-aware drop |

---

## 8. Tests and Coverage Matrix

| Scenario | Covered? | Test Class | Notes | Gap |
|---|---|---|---|---|
| CLIENT span enrichment | **Yes** | EnrichingSpanProcessorTest, EnrichmentPolicyCharacterizationTest | type/result/remote | — |
| SERVER span enrichment | **Yes** | EnrichingSpanProcessorTest | http_server | — |
| PRODUCER enrichment | **Yes** | EnrichingSpanProcessorAdvancedTest | kafka_producer | — |
| CONSUMER enrichment | **Yes** | EnrichingSpanProcessorAdvancedTest | kafka_consumer | — |
| INTERNAL enrichment | **Yes** | EnrichingSpanProcessorAdvancedTest | internal | — |
| database span detection | **Yes** | EnrichingSpanProcessorTest | stable + legacy | — |
| stable DB semconv | **Yes** | EnrichingSpanProcessorTest | db.system.name | — |
| legacy DB semconv | **Yes** | EnrichingSpanProcessorTest | db.system | — |
| dual DB semconv | **Yes** | EnrichingSpanProcessorTest | both attrs | — |
| explicit platform.type not overwritten | **Yes** | EnrichingSpanProcessorTest | rpc preserved | — |
| platform.result success/failure | **Yes** | EnrichingSpanProcessorTest | | — |
| baggage propagation | **Partial** | EnrichingSpanProcessorTest | request id from baggage | BaggageSpanProcessor direct tests sparse |
| remote service extraction | **Yes** | EnrichingSpanProcessorTest | peer/rpc/server.address/IP rules | — |
| IP address rejection | **Yes** | EnrichingSpanProcessorTest | IPv4/IPv6/DNS:port | — |
| exception safety (composite) | **Partial** | PlatformCompositeSpanProcessor tests indirect | delegate isolation | No dedicated fault-injection per processor |
| malformed/blank attributes | **Partial** | EnrichingSpanProcessorAdvancedTest | blank remote priority names | Scrubbing blank values |
| high-cardinality attributes | **Partial** | SpanLimitsVerificationTest | SDK limits | Processor behavior under limit overflow |
| sensitive attribute handling | **Yes** | ScrubbingSecurityCharacterizationTest, ScrubbingSpanProcessorTest | | — |
| validation strict/lenient/disabled | **Yes** | ValidationPolicyCharacterizationTest, ValidatingSpanProcessorBenchmark | | — |
| processor ordering | **Partial** | PipelinePolicyCharacterizationTest | documented order + 2 composite tests | Full pipeline integration test limited |
| shutdown/forceFlush | **Partial** | SpanWatchdogProcessorTest, PlatformDropOldest*Test | | Composite shutdown aggregation |
| exporter error handling | **Yes** | SafeSpanExporter tests (exporter package) | | — |
| concurrency | **Partial** | SpanWatchdog concurrency tests (referenced) | | Composite concurrent onEnding |

**Test file count (processor package tests):** 30+ test classes including characterization, security, runtime JMX policy, BSP builder validation.

**JMH:** `CompositePipelineBenchmark`, `ValidatingSpanProcessorBenchmark`, `ScrubbingEngineBenchmark`, `QueueOfferBenchmark`.

**ArchUnit:** `OtelDirectIntegrationExtensionSpiRules` (each processor implements SpanProcessor); `ResourceKeysNotInSpanProcessorsArchTest`.

**E2E:** `TracingE2ETest`, `DbSemconvAgentSmokeTest`, `PlatformExtensionAgentSmokeTest`, `CollectorProductionPolicyE2ETest` (indirect processor outcomes).

---

## 9. Docs / ADR Evidence

| Doc | Relevant Decision | Code Alignment | Staleness Risk |
|---|---|---|---|
| [ADR-composite-processor.md](../decisions/ADR-composite-processor.md) | Custom composite vs SDK MultiSpanProcessor for §37 isolation | Aligned — `PlatformCompositeSpanProcessor` | Low |
| [ADR-db-semconv-detection.md](../decisions/ADR-db-semconv-detection.md) | Dual db.system / db.system.name detection | Aligned — `DbSemanticAttributeKeys` | Low |
| [ADR-safe-span-exporter-v1.md](../decisions/ADR-safe-span-exporter-v1.md) | SafeSpanExporter; no SDK priority queue | Aligned | Low |
| [ADR-drop-oldest-export-processor-v1.md](../decisions/ADR-drop-oldest-export-processor-v1.md) | Opt-in DROP_OLDEST export processor | Aligned | Low |
| [ADR-semconv-validation-modes.md](../decisions/ADR-semconv-validation-modes.md) | STRICT/WARN/DISABLED validation | Aligned — ValidatingSpanProcessor | Low |
| [ADR-scrubbing-cost.md](../decisions/ADR-scrubbing-cost.md) | Scrubbing performance constraints | Aligned — circuit breaker, JMH | Low |
| [ADR-performance-model.md](../decisions/ADR-performance-model.md) | Priority retention vs SDK eviction GAP | Aligned — documented backlog | Low |
| [ADR-processor-errors-metric.md](../decisions/ADR-processor-errors-metric.md) | Processor error counters | Aligned — composite errorCounts | Low |
| [docs/semconv-mapping.md](../semconv-mapping.md) | SpanCategory ↔ semconv | Aligned with Enriching/Classification | Low |
| [docs/SUPPORTED.md](../SUPPORTED.md) | Agent 2.28.1 / SDK 1.62.0 | Aligned with gradle.properties | Low |
| [platform-tracing-scrubbing-validation-characterization.md](../architecture/platform-tracing-scrubbing-validation-characterization.md) | Pipeline characterization | Mostly aligned | Medium — check after processor changes |

---

## 10. Dependency and Version Inventory

| Dependency | Version | Module | Scope | Source |
|---|---|---|---|---|
| `opentelemetry-bom` | **1.62.0** | platform-tracing-bom | BOM import | `gradle.properties` `openTelemetryBomVersion` |
| `opentelemetry-instrumentation-bom` | **2.28.1** | platform-tracing-bom | BOM import | `gradle.properties` |
| `opentelemetry-javaagent-extension-api` | **2.28.1-alpha** | platform-tracing-otel-extension | compileOnly | BOM constraint |
| `opentelemetry-sdk` | 1.62.0 (BOM) | otel-extension | compileOnly / testImplementation | BOM-managed |
| `opentelemetry-sdk-extension-autoconfigure-spi` | 1.62.0 (BOM) | otel-extension | compileOnly / testImplementation | BOM-managed |
| `opentelemetry-sdk-testing` | 1.62.0 (BOM) | otel-extension | testImplementation | BOM-managed |
| `opentelemetry-semconv` | **Not declared** | — | — | Grep: no Gradle reference |
| `opentelemetry-instrumentation-api-semconv` | **Not declared** | — | — | Grep: no Gradle reference |
| `archunit-junit5` | **1.3.0** | platform-tracing-bom | api constraint | `gradle.properties` |
| `junit-jupiter` | Spring Boot BOM | otel-extension | testImplementation | BOM-managed |
| `assertj-core` | Spring Boot BOM | otel-extension | testImplementation | BOM-managed |
| OTel Java Agent (runtime) | **2.28.1** (supported) | — | runtime infra | `docs/SUPPORTED.md` — not Gradle dep |

---

## 11. Runtime / Performance / Classloader Risk Register

| Risk | Affected Class | Level | Local Evidence | Needs Deep Research? |
|---|---|---|---|---|
| `span.toSpanData()` on hot path | ValidatingSpanProcessor, ClassificationSpanProcessor, MetricsSpanProcessor, EnrichingSpanProcessor | **MEDIUM** | Direct calls in onEnding/onEnd | DR-07 |
| Full attribute scan per span | ScrubbingSpanProcessor | **HIGH** | onEnding iterates all attrs | DR-06, DR-07 |
| Regex on scrubbing/baggage | ScrubbingSpanProcessor, BaggageSpanProcessor | **MEDIUM** | Pattern.compile at startup; match per key/attr | DR-07 |
| Background scheduler + span.end() | SpanWatchdogProcessor | **HIGH** | `ScheduledExecutorService`, ends from scan thread | **Yes** DR-01 |
| Mutable shared state | SpanWatchdogProcessor, ValidatingSpanProcessor (warn cache), ScrubbingSpanProcessor (counters) | **MEDIUM** | ConcurrentHashMap, LongAdder | No |
| Exception swallowing | PlatformCompositeSpanProcessor | **LOW** (by design) | §37 isolation | No |
| Strict validation on app thread | ValidatingSpanProcessor | **MEDIUM** | Throws TracingValidationException | DR-05 |
| MDC side effect | EnrichingSpanProcessor | **LOW** | RemoteServiceMdc on ERROR only | No |
| No generated semconv dep | All processors | **LOW** (policy) | Raw AttributeKey.stringKey | DR-09 |
| Agent extension classloader | All extension classes | **MEDIUM** | Self-contained agent JAR | DR-08 |
| Drop-oldest without priority | PlatformDropOldestExportSpanProcessor | **MEDIUM** | ADR-performance-model GAP | DR-04 |
| Accidental attr overwrite | EnrichingSpanProcessor | **LOW** | Guards on type/result/remote/priority | No |

---

## 12. Improvement Backlog Candidates

| ID | Title | Affected Classes | Evidence | Next Action |
|---|---|---|---|---|
| IMP-01 | Share DB semconv keys with api `SemconvKeys` without new dep | EnrichingSpanProcessor | Duplicate string keys vs SemconvKeys | **ARCHITECTURE_AUDIT** |
| IMP-02 | Explicit CLIENT-without-DB negative test | EnrichingSpanProcessor | Coverage matrix gap | **LOCAL_TEST_SLICE** |
| IMP-03 | BaggageSpanProcessor direct unit tests | BaggageSpanProcessor | Sparse test coverage | **LOCAL_TEST_SLICE** |
| IMP-04 | Document Watchdog cross-thread span.end() contract | SpanWatchdogProcessor | HIGH risk register | **DEEP_RESEARCH** (DR-01) |
| IMP-05 | Reduce toSpanData() calls on onEnding | Classification, Enriching, Validating | JMH notes in CompositePipelineBenchmark | **DEEP_RESEARCH** + **IMPLEMENTATION_SPIKE** |
| IMP-06 | Priority-aware export queue | PlatformDropOldestExportSpanProcessor | ADR-performance-model GAP | **DEEP_RESEARCH** — DO NOT DO without board |
| IMP-07 | Scrub span events/links | ScrubbingSpanProcessor javadoc says not modified | Security gap vs attrs | **DEEP_RESEARCH** (DR-06) |
| IMP-08 | MetricsSpanProcessor dedicated tests | MetricsSpanProcessor | Few direct tests | **LOCAL_TEST_SLICE** |
| IMP-09 | Full pipeline ordering integration test | PlatformCompositeSpanProcessor | Only partial PipelinePolicyCharacterizationTest | **LOCAL_TEST_SLICE** |
| IMP-10 | Remove legacy db.system after fleet opt-in | EnrichingSpanProcessor.DbSemanticAttributeKeys | ADR-db-semconv-detection v1.1 backlog | **DO_NOT_DO** until migration complete |

---

## 13. Perplexity Deep Research Prompt Backlog

### DR-01 — OpenTelemetry SpanProcessor ordering and ExtendedSpanProcessor lifecycle

**Objective:** Verify OTel SDK 1.62.0 guarantees for `ExtendedSpanProcessor.onEnding` vs `onEnd`, thread calling `span.end()`, and whether Watchdog may call `span.end()` from background thread.

**Local evidence to paste:**
- `PlatformCompositeSpanProcessor` javadoc (onEnding before onEnd)
- `SpanWatchdogProcessor.scan()` calls `span.end()` from scheduler thread
- Factory order: Baggage → Enriching → Scrubbing → Validating → Classification → Watchdog → Metrics

**Questions:**
1. In OTel Java SDK 1.62.0, on which thread is `onEnding` invoked relative to `onEnd`?
2. Is it safe for a SpanProcessor to call `span.end()` from a non-application thread?
3. Does SDK `MultiSpanProcessor` provide equivalent §37 isolation to custom composite?
4. Recommended processor ordering for enrichment vs scrubbing vs validation?

**Required sources:** OTel Java SDK 1.62.0 source/javadoc, OTel PR #6367, Java Agent 2.28.x docs.

**Expected output:** Threading model diagram + ordering recommendation + safe/unsafe patterns.

**Decision options:** Keep custom composite; adopt MultiSpanProcessor; restrict Watchdog to app thread only.

---

### DR-02 — EnrichingSpanProcessor DB semconv / database span detection

**Objective:** Validate dual-key `db.system.name` / `db.system` approach vs alternatives.

**Local evidence to paste:** Full content of [enriching-span-processor-db-semconv-local-dossier.md](./enriching-span-processor-db-semconv-local-dossier.md) §3–§10.

**Questions:** See enriching dossier §11 (8 questions).

**Required sources:** OTel semconv registry, Java Instrumentation 2.28.x JDBC instrumentation, semconv stability opt-in docs.

**Decision options:** KEEP_BOTH_KEYS; stable-only after opt-in; add instrumentation scope detection.

---

### DR-03 — Remote service extraction attributes

**Objective:** Industry practice for `peer.service` vs `rpc.service` vs `server.address` priority on ERROR CLIENT spans.

**Local evidence to paste:**
- `EnrichingSpanProcessor.DEFAULT_REMOTE_SERVICE_PRIORITY = [peer.service, rpc.service, server.address]`
- IP rejection heuristics in `looksLikeIpAddress`

**Questions:**
1. OTel semconv recommended priority for upstream service identity?
2. Should `server.address` with hostname:port be used as service name?
3. Interaction with service mesh / k8s DNS names?

**Required sources:** OTel semconv service discovery attributes, Honeycomb/Datadog guidance.

---

### DR-04 — SafeSpanExporter / exporter error handling

**Objective:** Compare `SafeSpanExporter` thin wrapper vs industry patterns for export failure isolation.

**Local evidence to paste:**
- `SafeSpanExporter.export()` catch-all + counters
- ADR-safe-span-exporter-v1 (no SDK circuit breaker)
- `PlatformDropOldestExportSpanProcessor` drop-oldest without priority

**Questions:**
1. Standard pattern for non-blocking export failure in Java Agent extensions?
2. When is drop-oldest without priority acceptable?
3. Relationship between SafeSpanExporter and BSP retry?

---

### DR-05 — ValidatingSpanProcessor policy design

**Objective:** STRICT vs LENIENT validation on application thread — industry norms.

**Local evidence to paste:**
- `ValidatingSpanProcessor.onEnding` throws `TracingValidationException` in strict mode
- Resource fallback via `toSpanData().getResource()`
- Rate-limited WARN with MAX_TRACKED_KEYS cap

**Questions:**
1. Do production tracing SDKs throw from SpanProcessor on validation failure?
2. Is resource fallback for span-specific keys valid?
3. Recommended validation placement: processor vs exporter vs collector?

---

### DR-06 — ScrubbingSpanProcessor sensitive attribute handling

**Objective:** Security best practices for in-process span attribute scrubbing.

**Local evidence to paste:**
- ScrubbingSpanProcessor scrubs attrs only, not events/links (javadoc)
- Rule priority + circuit breaker
- ADR-scrubbing-cost, second-line Collector redaction

**Questions:**
1. Should events/links be scrubbed in SDK processor?
2. Regex denial-of-service mitigations beyond circuit breaker?
3. HASH vs MASK vs DROP tradeoffs for compliance?

---

### DR-07 — SpanProcessor hot-path performance in Agent extensions

**Objective:** Microbudget guidance for onEnding pipeline cost.

**Local evidence to paste:**
- JMH `CompositePipelineBenchmark`, `ValidatingSpanProcessorBenchmark`
- ADR-performance-model P1–P5 invariants
- `toSpanData()` usage inventory from §11

**Questions:**
1. Acceptable ns/op budget per processor at 10k spans/sec?
2. Alternatives to `toSpanData()` for status/resource reads on onEnding?
3. Scrubbing full-scan vs targeted key scan?

---

### DR-08 — Java Agent extension classloader isolation

**Objective:** Dependency hygiene for self-contained agent JAR.

**Local evidence to paste:**
- `agentExtensionJar` embeds api + core + slf4j
- `ExtensionNoSpringDependencyArchTest`
- ADR-db-semconv-detection deploy section

**Questions:**
1. What dependencies must never be bundled in agent extension?
2. Shading vs embedding for slf4j/api?
3. Impact of adding opentelemetry-semconv to agent JAR?

---

### DR-09 — Generated semconv constants vs raw AttributeKey

**Objective:** Whether agent extensions should use `opentelemetry-semconv` generated constants.

**Local evidence to paste:**
- No opentelemetry-semconv in Gradle
- `SemconvKeys` javadoc: avoid unstable generated-semconv in public API
- Processors use raw `AttributeKey.stringKey`

**Questions:**
1. Semconv artifact versioning aligned with Agent 2.28.x?
2. Shading semconv in isolated classloader?
3. Recommended pattern for extension-only keys vs api-module keys?

---

### DR-10 — Processor test strategy

**Objective:** Benchmark unit vs characterization vs e2e vs JMH vs ArchUnit for processor pipelines.

**Local evidence to paste:**
- Test inventory §8
- `PipelinePolicyCharacterizationTest`, `SpanProcessorHarness`
- JMH suite in platform-tracing-bench

**Questions:**
1. Minimum test pyramid for OTel extension processors?
2. When is Agent e2e mandatory vs SDK unit tests sufficient?
3. Characterization test maintenance patterns?

---

## 14. Open Questions

1. Is `BaggageSpanProcessor` missing `forceFlush()` override intentional? (class ends at `shutdown()` only)
2. Should `MetricsSpanProcessor` run before or after Watchdog in factory order? (currently last)
3. When will platform-wide `otel.semconv-stability.opt-in=database` happen? (legacy db.system removal trigger)
4. Are span events scrubbed anywhere in SDK path, or only Collector second line?
5. Thread safety of Watchdog calling `span.end()` — **NEEDS_PERPLEXITY_DEEP_RESEARCH**

---

## 15. Commands Run

```powershell
.\gradlew.bat :platform-tracing-otel-extension:compileJava --no-daemon
# BUILD SUCCESSFUL

.\gradlew.bat :platform-tracing-otel-extension:test --tests "*SpanProcessor*" --no-daemon
# BUILD SUCCESSFUL
```

---

## 16. Blockers

None.
