# EnrichingSpanProcessor DB Semconv Local Dossier

> **Scope:** Local repository evidence only. No production/test code changes.  
> **Date:** 2026-06-20  
> **Class:** `space.br1440.platform.tracing.otel.extension.processor.EnrichingSpanProcessor`  
> **Focus:** `DB_SYSTEM_NAME_KEY` / `DB_SYSTEM_KEY` and `hasDbSystemAttribute()`

---

## 1. Executive Summary

`EnrichingSpanProcessor` runs inside the OTel Java Agent extension pipeline. On `onEnding`, for `SpanKind.CLIENT` spans it reclassifies `platform.trace.type` from the default `http_client` to `database` when either `db.system.name` (stable) **or** `db.system` (legacy) is present.

Repository evidence strongly supports **dual-key detection**:

- Accepted ADR: [ADR-db-semconv-detection.md](../decisions/ADR-db-semconv-detection.md)
- Platform API contract mirrors both keys: `SemconvKeys.DB_SYSTEM_NAME` + `SemconvKeys.DB_SYSTEM_LEGACY`
- Unit tests cover stable and legacy paths separately
- E2e agent smoke tests (`DbSemconvAgentSmokeTest`) cover default legacy, stable opt-in, and dup mode

The project does **not** depend on `opentelemetry-semconv` or `SemanticAttributes` / `IncubatingAttributes` artifacts. DB keys are raw `AttributeKey.stringKey(...)` in the processor; canonical string constants live in `SemconvKeys` (api module).

**Local-only conclusion:** `KEEP_BOTH_KEYS_LOCALLY_REASONABLE`

---

## 2. Class Under Review

| Field | Value |
|---|---|
| Path | `platform-tracing-otel-extension/src/main/java/space/br1440/platform/tracing/otel/extension/processor/EnrichingSpanProcessor.java` |
| SPI | Implements `io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor` (extends span processor contract with `onEnding`) |
| Visibility | `public final` |
| Config gate | `platform.tracing.enriching.enabled` (default `true` via `ExtensionDefaults.DEFAULT_ENABLED`) |
| Constructor params | Optional `remoteServicePriority` list (from `EnrichingExtensionConfig.remoteServicePriority()`) |

Key constants (lines 35–38):

```java
private static final AttributeKey<String> DB_SYSTEM_NAME_KEY = AttributeKey.stringKey("db.system.name");
private static final AttributeKey<String> DB_SYSTEM_KEY = AttributeKey.stringKey("db.system");
```

---

## 3. Current DB Detection Logic

### 3.1 Lifecycle

| Phase | Method | DB-related behavior |
|---|---|---|
| `onStart` | Sets default `platform.trace.type` from `SpanKind` | `CLIENT` → `http_client` (not DB-aware yet) |
| `onEnding` | DB reclassification + `platform.trace.result` + optional `platform.remote.service` | See below |
| `onEnd` | Empty | — |

### 3.2 DB reclassification (`onEnding`, lines 91–96)

```java
if (span.getKind() == SpanKind.CLIENT && hasDbSystemAttribute(span)) {
    String currentType = span.getAttribute(PLATFORM_TYPE_KEY);
    if (currentType == null || SpanCategory.HTTP_CLIENT.value().equals(currentType)) {
        span.setAttribute(PLATFORM_TYPE_KEY, SpanCategory.DATABASE.value());
    }
}
```

### 3.3 `hasDbSystemAttribute()` (lines 110–113)

```java
return span.getAttribute(DB_SYSTEM_NAME_KEY) != null
        || span.getAttribute(DB_SYSTEM_KEY) != null;
```

**Notes from code:**

- Only `SpanKind.CLIENT` is considered.
- Overwrite allowed only when `platform.trace.type` is `null` or `http_client`.
- Explicit non-`http_client` types (e.g. `rpc`) are preserved even if DB attrs exist.
- Presence check only (`!= null`); attribute **values** are not parsed.
- Two attribute lookups on `onEnding` hot path (O(1) map reads).

### 3.4 Interaction with other enrichment

On the same `onEnding` call:

1. `platform.trace.result` set if absent (from span status).
2. DB type override (above).
3. `platform.remote.service` for `CLIENT` + `ERROR` only (independent of DB detection).

DB classification does not set `platform.remote.service`.

---

## 4. Registration / Runtime Path

```
OTel Java Agent (runtime)
  └─ ServiceLoader: PlatformAutoConfigurationCustomizer
       └─ AutoConfigurationCustomizerProvider.customize()
            └─ addTracerProviderCustomizer → PlatformSpanProcessorFactory.registerSpanProcessors()
                 └─ if extConfig.enriching().enabled()
                      delegates.add(new EnrichingSpanProcessor(remoteServicePriority))
                 └─ PlatformCompositeSpanProcessor(delegates)
                      └─ tpBuilder.addSpanProcessor(composite)
```

| Question | Answer (evidence) |
|---|---|
| Where registered? | `PlatformSpanProcessorFactory.java` lines 61–63 |
| Inside Agent extension? | Yes — `platform-tracing-otel-extension` loaded via `otel.javaagent.extensions` (`build.gradle` description, `verifyExtensionSpiRegistration`) |
| Sees span before export? | Yes — registered as SDK `SpanProcessor` on `SdkTracerProviderBuilder`; `onEnding` runs before export batching |
| Span type at callback | `ReadWriteSpan` in `onStart` / `onEnding`; `ReadableSpan` in `onEnd` (unused) |
| Processor order | First delegate after optional `BaggageSpanProcessor`; before Scrubbing, Validating, Classification, Watchdog, Metrics |
| Composite wrapper | `PlatformCompositeSpanProcessor` — swallows delegate exceptions per platform safety model |

ArchUnit: `EnrichingSpanProcessor` must be assignable to `SpanProcessor` (`OtelDirectIntegrationExtensionSpiRules.ENRICHING_SPAN_PROCESSOR_IMPLEMENTS_SPI`).

---

## 5. Attribute Inventory

| Attribute | File | Prod/Test/Doc | Usage | Semconv class (local label) |
|---|---|---|---|---|
| `db.system.name` | `EnrichingSpanProcessor.java` | Production | DB detection (`DB_SYSTEM_NAME_KEY`) | Documented as stable (1.28+) in comment |
| `db.system` | `EnrichingSpanProcessor.java` | Production | DB detection (`DB_SYSTEM_KEY`) | Documented as legacy (≤1.27) in comment |
| `db.system.name` | `SemconvKeys.java` | Production (api) | `DB_SYSTEM_NAME` constant | Stable |
| `db.system` | `SemconvKeys.java` | Production (api) | `DB_SYSTEM_LEGACY` constant | Legacy |
| `db.system.name` | `CategoryContracts.java` | Production (api) | DATABASE `requiredAnyOf` group | Stable |
| `db.system` | `CategoryContracts.java` | Production (api) | DATABASE `requiredAnyOf` group | Legacy |
| `db.system.name` | `PlatformAttributes.java` | Production (api) | String constant `DB_SYSTEM_NAME` | Stable |
| `db.operation.name` | `PlatformAttributes.java`, `SemconvKeys.java`, `DatabaseSpanBuilder.java` | Production | Typed builder / contracts | Stable |
| `db.collection.name` | `SemconvKeys.java`, `CategoryContracts.java` | Production | DATABASE allowed attrs | Stable |
| `db.namespace` | `SemconvKeys.java`, `CategoryContracts.java` | Production | DATABASE allowed attrs | Stable |
| `db.statement` | `DatabaseSpanBuilder.java`, `SqlSanitizer.java` | Production | Escape-hatch builder; incubating, not in `SemconvKeys` | Incubating (doc comment) |
| `db.query.text` | — | — | **Not found in repository** | — |
| `db.system.name` | `EnrichingSpanProcessorTest.java` | Test | Stable override scenario | Test |
| `db.system` | `EnrichingSpanProcessorTest.java` | Test | Legacy override scenario | Test |
| `db.system` | `DbSemconvAgentSmokeTest.java` | Test (e2e) | Agent default / dup smoke | Test |
| `db.system.name` | `DbSemconvAgentSmokeTest.java` | Test (e2e) | Agent stable opt-in smoke | Test |
| `db.system` / `db.system.name` | `ADR-db-semconv-detection.md` | Doc | Formal decision + spike table | Doc |
| `db.system.name` / `db.system` | `docs/semconv-mapping.md` | Doc | Reverse mapping table | Doc |
| `SemanticAttributes` | — | — | **No Java usages in repository** | — |
| `IncubatingAttributes` | — | — | **No Java usages in repository** | — |

**Duplication note:** Processor uses **private** `DB_SYSTEM_*_KEY` constants. Shared canonical keys exist in `SemconvKeys` but are **not imported** by `EnrichingSpanProcessor` (intentional module boundary: extension depends on api for `PlatformAttributes` / `SpanCategory`, not on importing `SemconvKeys` for these two lookups).

---

## 6. Test Coverage

| Scenario | Covered? | Test Class | Notes |
|---|---|---|---|
| CLIENT + `db.system.name` | **COMPLETE** | `EnrichingSpanProcessorTest.overridesPlatformTypeToDatabaseWhenStableDbSystemNamePresent` | Asserts `platform.trace.type=database` |
| CLIENT + `db.system` (legacy) | **COMPLETE** | `EnrichingSpanProcessorTest.overridesPlatformTypeToDatabaseWhenLegacyDbSystemPresent` | Asserts `platform.trace.type=database` |
| CLIENT + both DB attributes | **PARTIAL** | `DbSemconvAgentSmokeTest` (e2e, `database/dup` scenario) | Not covered in unit/processor harness tests |
| CLIENT without DB attributes | **PARTIAL** | `EnrichmentPolicyCharacterizationTest` (`ENR-CLIENT-ERR` → `http_client`) | Indirect; no dedicated unit test named for plain CLIENT+OK without DB |
| CLIENT HTTP not misclassified as DATABASE | **PARTIAL** | `ENR-CLIENT-ERR` (has `peer.service`, no DB attrs) | Does not assert absence of DB attrs explicitly in a JDBC-negative case |
| Existing `platform.trace.type` not overwritten | **COMPLETE** | `EnrichingSpanProcessorTest.doesNotOverridePlatformTypeWhenSetExplicitlyByApplication`; `doesNotOverwriteExistingPlatformType` (onStart) | Explicit `rpc` preserved with `db.system.name` present |
| `SpanKind` other than CLIENT (SERVER + DB attrs) | **COMPLETE** | `EnrichingSpanProcessorTest.doesNotOverridePlatformTypeForServerSpanWithDbAttributes` | Stays `http_server` |
| Interaction with `platform.result` | **COMPLETE** | `EnrichingSpanProcessorTest.setsPlatformResultFailureOnStatusCodeError`; `EnrichingSpanProcessorAdvancedTest.platform_result_не_перезаписывается_если_уже_задан_приложением` | Independent of DB path |
| Interaction with `platform.remote.service` | **COMPLETE** | Multiple tests in `EnrichingSpanProcessorTest` + `EnrichmentPolicyCharacterizationTest` | DB override cases expect `remoteService=null` on OK paths |
| Characterization matrix DB override | **PARTIAL** | `EnrichmentPolicyCharacterizationTest` (`ENR-DB-OVERRIDE`) | Uses **`db.system.name` only**, not legacy key |
| Agent + extension JDBC enrichment e2e | **COMPLETE** | `PlatformExtensionAgentSmokeTest`, `DbSemconvAgentSmokeTest` | Requires `-PrunE2e` |

### Proposed test names (not implemented)

- `EnrichingSpanProcessorTest.overridesPlatformTypeToDatabaseWhenBothDbSystemAttributesPresent`
- `EnrichingSpanProcessorTest.doesNotReclassifyClientSpanWithoutDbSystemAttributes`
- `EnrichmentPolicyCharacterizationTest` case `ENR-DB-OVERRIDE-LEGACY` with `db.system` only
- `EnrichingSpanProcessorTest.dbDetectionDoesNotSetPlatformRemoteServiceOnSuccessfulDbClientSpan`

---

## 7. Dependency / Version Inventory

Resolved from `gradle.properties` and `platform-tracing-bom/build.gradle` (no separate semconv artifact declared).

| Component | Version | Source File | Notes |
|---|---|---|---|
| OpenTelemetry SDK BOM | **1.62.0** | `gradle.properties` → `openTelemetryBomVersion` | `api platform(...)` in BOM |
| OpenTelemetry Instrumentation BOM | **2.28.1** | `gradle.properties` → `openTelemetryInstrumentationBomVersion` | Agent train alignment |
| OpenTelemetry Java Agent (supported) | **2.28.1** | `docs/SUPPORTED.md`, `docs/tracing/otel-compatibility-matrix.md` | Runtime artifact, not Gradle compile dep |
| `opentelemetry-javaagent-extension-api` | **2.28.1-alpha** | `gradle.properties` → `openTelemetryInstrumentationAlphaVersion` | `compileOnly` in otel-extension |
| OpenTelemetry API | From BOM **1.62.0** | `platform-tracing-otel-extension/build.gradle` | `compileOnly` / `testImplementation` sdk |
| OpenTelemetry SDK | From BOM **1.62.0** | same | |
| `opentelemetry-semconv` artifact | **Not declared** | Grep across `*.gradle` | No generated semconv constants dependency |
| Spring Boot | **3.5.5** | `gradle.properties` | |
| Test: `opentelemetry-sdk-testing` | From BOM **1.62.0** | otel-extension `build.gradle` | In-memory exporter tests |
| Test: e2e Agent image | **2.28.1** (documented) | `docs/SUPPORTED.md` | `DbSemconvAgentSmokeTest` |

BOM alignment task: `verifyOtelBomAlignment` enforces `openTelemetryInstrumentationAlphaVersion == openTelemetryInstrumentationBomVersion + "-alpha"`.

---

## 8. Docs / ADR Evidence

| Document | Relevance | Supports dual keys? |
|---|---|---|
| [ADR-db-semconv-detection.md](../decisions/ADR-db-semconv-detection.md) | **Primary** — decision, spike, production expectations, backlog v1.1 removal of legacy | **Yes — explicit** |
| [docs/semconv-mapping.md](../semconv-mapping.md) | Reverse mapping CLIENT → database via either attr | **Yes** |
| [ADR-platform-resource-override.md](../decisions/ADR-platform-resource-override.md) | `platform.trace.type` span-level naming | Neutral |
| [ADR-typed-span-api-semantic-layer.md](../decisions/ADR-typed-span-api-semantic-layer.md) | Agent-first; typed builders escape hatch | Neutral for DB detection |
| [ADR-semconv-governance-weaver.md](../decisions/ADR-semconv-governance-weaver.md) | Java-native `CategoryContract` as SoT; no Weaver yet | Aligns with raw strings + `SemconvKeys` |
| [docs/SUPPORTED.md](../SUPPORTED.md) | Agent 2.28.x production path | Compatible target |
| [platform-tracing-scrubbing-validation-characterization.md](../architecture/platform-tracing-scrubbing-validation-characterization.md) | ENR-DB-OVERRIDE matrix row | Uses `db.system.name` |
| [CHANGELOG.md](../../CHANGELOG.md) | Notes legacy `db.system` default in e2e | **Yes** |

**Contradictions:** None found in local docs. ADR backlog v1.1 suggests **future** removal of legacy fallback after platform-wide `otel.semconv-stability.opt-in=database` — not a contradiction with current dual-key design.

---

## 9. Local Risk Assessment

| Risk | Level | Evidence | Needs External Research? |
|---|---|---|---|
| False positive DATABASE classification | **Low–Medium** | Only `CLIENT` + presence of `db.system*`; any CLIENT span with those attrs reclassified. Could affect non-JDBC CLIENT spans if instrumentation sets DB attrs incorrectly | No (local logic clear). Edge cases: **NEEDS_PERPLEXITY_DEEP_RESEARCH** for exotic instrumentations |
| False negative DATABASE classification | **Medium without legacy key** | ADR states prod-default Agent writes **`db.system` only** without opt-in. Removing legacy → JDBC stays `http_client` | No — documented in ADR + e2e |
| Supporting both keys | **Low** | Two null-check lookups; ADR + tests + api contract aligned | No |
| Raw string `AttributeKey` vs generated semconv | **Low (local policy)** | `SemconvKeys` javadoc: avoid unstable generated-semconv in public API; no `opentelemetry-semconv` dep in extension | **NEEDS_PERPLEXITY_DEEP_RESEARCH** for industry preference in agent extensions |
| Semconv version mismatch | **Medium (operational)** | Dual-mode bridges Agent opt-in states; values differ per DB (`h2` vs `h2database`) but detection ignores values | Partially external: **NEEDS_PERPLEXITY_DEEP_RESEARCH** for OTel semconv release timeline |
| Agent 2.28.x compatibility | **Low (local)** | ADR re-validated 2.28.1; `DbSemconvAgentSmokeTest` in repo | No |
| Performance on `onEnding` | **Low** | Two attribute map reads + kind check; no I/O; ADR/h1 JMH lists `ClassificationSpanProcessor.onEnding` not Enriching — Enriching does more work (remote service extraction on ERROR) but DB branch is minimal | No |

---

## 10. Local-Only Conclusion

### `KEEP_BOTH_KEYS_LOCALLY_REASONABLE`

**Rationale (repository evidence only):**

1. **Formal ADR** accepts dual detection as v1.0 final behavior.
2. **Production-default Agent path** (per ADR + e2e smoke) emits legacy `db.system` — stable-only detection would fail locally documented default.
3. **Platform semconv contract** (`CategoryContracts` DATABASE) requires **either** key (`requiredAnyOf`).
4. **Tests** cover stable and legacy separately at unit level; e2e covers Agent emission modes.
5. **No local alternative implementation** (instrumentation scope, span name heuristics) exists in codebase.
6. **Duplication** with `SemconvKeys` is a maintainability nit, not a behavioral defect — refactor could share constants without changing detection logic.

**Not recommended locally (without ADR amendment):** stable-only (`db.system.name`) while Agent default remains legacy.

---

## 11. Questions for Perplexity Deep Research

1. What is the current OTel semconv **stability status** of `db.system.name` vs deprecated/legacy status of `db.system` in the official registry?
2. For **OpenTelemetry Java Instrumentation 2.28.x**, which DB attributes are emitted by default on JDBC spans without `OTEL_SEMCONV_STABILITY_OPT_IN`?
3. Is dual-key presence detection (`db.system.name` OR `db.system`) an **accepted migration bridge** in OTel ecosystem extensions, or is there a recommended SDK/Agent API for span kind/category detection?
4. Should Java Agent extensions prefer **`io.opentelemetry.semconv`** generated constants over raw strings when the extension JAR does not currently depend on semconv artifacts?
5. After platform-wide `otel.semconv-stability.opt-in=database`, is **`db.system.name` alone** sufficient for Agent 2.28.x+ without breaking mixed-version fleets?
6. Are there **false-positive** cases where non-database CLIENT spans carry `db.system` or `db.system.name` in 2.28.x instrumentations?
7. Should detection use **`InstrumentationScopeInfo` / span name / `db.operation.name`** instead of or in addition to system attributes?
8. What is the recommended pattern for **sharing semconv key constants** between `platform-tracing-api` (`SemconvKeys`) and `platform-tracing-otel-extension` without adding semconv JAR weight to the agent extension fat JAR?

---

## Appendix: Validation Run (2026-06-20)

```powershell
.\gradlew.bat compileJava --no-daemon
# BUILD SUCCESSFUL

.\gradlew.bat :platform-tracing-otel-extension:test --tests "*EnrichingSpanProcessor*" --tests "*EnrichmentPolicyCharacterization*" --no-daemon
# BUILD SUCCESSFUL
```
