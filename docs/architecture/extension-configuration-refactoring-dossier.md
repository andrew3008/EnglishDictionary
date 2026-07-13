# Extension Configuration Refactoring Dossier

> **Статус:** Information gathering only — без рефакторинга, без изменений Java-кода  
> **Дата:** 2026-06-16  
> **Пакет:** `space.br1440.platform.tracing.otel.extension.configuration`  
> **Фокус:** `ExtensionConfig` и смежные agent-side configuration types  
> **Цель:** входные данные для Perplexity / Claude Sonnet Deep Research — сравнение 8 архитектурных вариантов со scoring

---

## 1. Executive Summary

Пакет `space.br1440.platform.tracing.otel.extension.configuration` — agent-side слой конфигурации OTel Java Agent extension. Он содержит **11 production-классов** (constants, defaults, enums, env-bridge, OTel SDK defaults, facade) и **5 test-классов**.

**Ключевое наблюдение (repository evidence):** `ExtensionConfig` — публичный typed facade над `ConfigProperties`, но **не используется ни одним production-классом**. Все factories (`PlatformSpanProcessorFactory`, `PlatformSamplerBuilder`, `PlatformExportProcessorFactory`, resource providers) читают `ExtensionPropertyNames` + `ExtensionDefaults` напрямую через `ConfigProperties`.

**Следствие:** `ExtensionConfig` сегодня — документированный контракт defaults + unit-test surface, а не фактический single source of truth runtime-пути. Любой рефакторинг должен либо **подключить** facade к production, либо явно переопределить его роль.

**Dual-channel:** Spring `TracingProperties` и agent `ExtensionPropertyNames`/`ExtensionDefaults` — два независимых канала (ADR-dual-channel-properties-v0.1). Agent конфигурируется **до** Spring context. `ExtensionConfig` относится только к agent-каналу.

**Runtime mutability:** большинство свойств, читаемых через `ExtensionConfig`, — **startup-only**. Runtime-mutable subset sampling/scrubbing/validation управляется через JMX/`RuntimeConfigApplier`, не через повторное чтение `ExtensionConfig`.

**Arch constraint:** `platform-tracing-otel-extension` **не должен** зависеть от Spring (`ExtensionNoSpringDependencyArchTest`).

---

## 2. Scope and Non-Goals

### In scope

- Inventory всех классов пакета `configuration`
- Детальный разбор `ExtensionConfig` (nested sections, property mapping, defaults, fallback styles)
- Production и test usages
- Property key catalog для `platform.tracing.*` в agent extension
- Границы startup / runtime / Spring / JMX
- Architecture constraints из ADR, ArchUnit, migration docs
- Pain points, risk map, 8 candidate architecture families, scoring criteria

### Non-goals (этот документ)

- Рефакторинг Java-кода
- Выбор победившей архитектуры
- Scoring 8 вариантов (делает следующий LLM-pass)
- Изменение `TracingProperties`, JMX API, `RuntimeConfigApplier`

---

## 3. Package Inventory

| Class | Path | Responsibility | Visibility | Main dependencies | Main users | Tests | Risk |
|---|---|---|---|---|---|---|---|
| `ExtensionConfig` | `platform-tracing-otel-extension/src/main/java/.../configuration/ExtensionConfig.java` | Typed facade: 11 nested sections читают `ConfigProperties` | public | `ConfigProperties`, `ExtensionPropertyNames`, `ExtensionDefaults` | **NOT FOUND IN REPOSITORY (production)**; только `ExtensionConfigTest` | `ExtensionConfigTest` | **HIGH** — facade drift vs factories |
| `ExtensionPropertyNames` | `.../ExtensionPropertyNames.java` | Stable string keys `platform.tracing.*` (+ `otel.javaagent.extensions`) | public | JDK only | All agent factories, tests, Spring actuator diagnostics | косвенно через integration tests | **HIGH** — external contract |
| `ExtensionDefaults` | `.../ExtensionDefaults.java` | Platform default values for agent properties | public | `PlatformHeaders`, `BuiltInSpanAttributeScrubbingRules` | Factories, `ExtensionConfig`, tests | `ExtensionConfigTest`, `ExtensionEnumsTest`, `SharedDefaultsAlignmentTest` (partial) | **HIGH** — default drift breaks startup |
| `ExtensionEnums` | `.../ExtensionEnums.java` | Typed string enums (queue, scrubbing, sdk mode) | public | JDK only | `PlatformSpanProcessorFactory`, `PlatformExportProcessorFactory` | `ExtensionEnumsTest` | MEDIUM |
| `ExtensionEnvironmentVariables` | `.../ExtensionEnvironmentVariables.java` | `PLATFORM_TRACING_*` env key constants | package-private | JDK only | `PlatformTracingDefaultsProvider`, `JavaAgentExtensionPaths` | `PlatformTracingDefaultsProviderResourceEnvTest` | MEDIUM |
| `PlatformTracingDefaultsProvider` | `.../PlatformTracingDefaultsProvider.java` | SPI `addPropertiesSupplier`: OTel BSP/limits defaults + env bridge | public | `OtelSdkDefaults`, `ExtensionEnvironmentVariables`, `PlatformResourceProvider` | `PlatformAutoConfigurationCustomizer` | `PlatformTracingDefaultsProviderTest`, `PlatformTracingDefaultsProviderResourceEnvTest` | **HIGH** — affects SDK bootstrap |
| `OtelSdkDefaults` | `.../OtelSdkDefaults.java` | Internal OTel SDK numeric defaults (BSP, span limits) | package-private | JDK only | `PlatformTracingDefaultsProvider`, `DropOldestExportProcessorDefaults` | `SharedDefaultsAlignmentTest`, `DropOldestExportProcessorDefaultsTest` | **HIGH** |
| `DropOldestExportProcessorDefaults` | `.../DropOldestExportProcessorDefaults.java` | Bridge: expose `OtelSdkDefaults` to `processor` package | public | `OtelSdkDefaults` | `PlatformDropOldestExportSpanProcessor`, Spring `SharedDefaultsAlignmentTest` | `DropOldestExportProcessorDefaultsTest` | MEDIUM |
| `JavaAgentExtensionPaths` | `.../JavaAgentExtensionPaths.java` | Resolve `otel.javaagent.extensions` from ConfigProperties/sysprop/env | public | `ConfigProperties`, `ExtensionPropertyNames`, `ExtensionEnvironmentVariables` | `PlatformSpanProcessorFactory` (scrubbing loader), scrubbing tests | `JavaAgentExtensionPathsTest` | MEDIUM |
| `AutoConfigurationCustomizerOrdering` | `.../AutoConfigurationCustomizerOrdering.java` | SPI order constant (`100`) | public | Lombok `@UtilityClass` | `PlatformAutoConfigurationCustomizer` | NOT FOUND IN REPOSITORY (dedicated test) | LOW |
| `ExtensionConfig.Sampling` … `ExtensionConfig.Sdk` | nested in `ExtensionConfig.java` | Per-domain readers | public static nested; ctor package-private | `ConfigProperties` | только через `ExtensionConfig` accessors | `ExtensionConfigTest` | HIGH if wired to production |

**Test-only classes in package:** `ExtensionConfigTest`, `ExtensionEnumsTest`, `PlatformTracingDefaultsProviderTest`, `PlatformTracingDefaultsProviderResourceEnvTest`, `JavaAgentExtensionPathsTest`, `DropOldestExportProcessorDefaultsTest`.

---

## 4. ExtensionConfig Structure

### 4.1 Top-Level Facade

```java
public final class ExtensionConfig {
    private final ConfigProperties config;
    public ExtensionConfig(ConfigProperties config) { ... }
    public Sampling sampling() { return new Sampling(config); }
    // ... enriching(), scrubbing(), metrics(), validation(), resource(),
    // classification(), watchdog(), queue(), baggage(), sdk()
}
```

| Аспект | Значение |
|---|---|
| Строк кода | 329 (`ExtensionConfig.java`) |
| Nested sections | 11 |
| Production usages | **NOT FOUND IN REPOSITORY** |
| Allocation pattern | Каждый вызов `sampling()` и т.д. создаёт **новый** nested instance |
| Thread safety | Stateless readers; `ConfigProperties` assumed immutable snapshot at startup |
| Null contract | Constructor rejects null `config` (`Objects.requireNonNull`) |

### 4.2 Nested Sections

| Section | Methods | Responsibility | Properties read | Defaults used | Pain points |
|---|--:|---|---|---|---|
| `Sampling` | 7 | Head sampling startup policy | 7 `SAMPLING_*` keys | `ExtensionDefaults` (+ `Map.of()` for route ratios) | Not used by `PlatformSamplerBuilder`; ratio default **drifts** (см. §11) |
| `Enriching` | 2 | Enriching processor toggle + attribute priority | 2 keys | `DEFAULT_ENABLED`, `DEFAULT_REMOTE_SERVICE_PRIORITY` | Duplicated in `PlatformSpanProcessorFactory` |
| `Scrubbing` | 7 | Scrubbing toggle, rules, HMAC, validation mode | 7 keys | mixed nullable + defaults | Duplicated + extra parsing in `PlatformSpanProcessorFactory.collectScrubbingRules` |
| `Metrics` | 1 | Metrics span processor toggle | 1 key | `DEFAULT_ENABLED` | Duplicated in factory |
| `Validation` | 3 | Validation processor strict flags | 3 keys | validation defaults | Duplicated; `strictRuntimeAllowed` startup-only but affects runtime enforcement flag |
| `Resource` | 4 | Resource policy metadata | 4 keys | resource defaults | Partial overlap with `ResourceAttributeResolver`; service identity keys **outside** `ExtensionConfig` |
| `Classification` | 3 | Slow/normal thresholds | 3 keys | duration defaults | Duplicated in factory |
| `Watchdog` | 4 | Watchdog timeouts | 4 keys | duration defaults | Duplicated in factory |
| `Queue` | 1 | Export overflow policy string | 1 key | `DEFAULT_QUEUE_OVERFLOW_POLICY` | Duplicated in `PlatformExportProcessorFactory` |
| `Baggage` | 3 | Baggage propagation allow/deny | 3 keys | baggage defaults | Duplicated in factory |
| `Sdk` | 1 | Diagnostic sdk mode string | 1 key | **no default** (nullable) | Read also directly in `PlatformAutoConfigurationCustomizer` for logging |

**Coherence issues (evidence):**

- `Resource` section covers policy-version/normalize/validation/detect-container, но **не** covers `platform.tracing.service.*` keys used by `PlatformResourceProvider`.
- `Sampling` includes header names (`forceRecordHeader`, `qaHeader`) not consumed by `PlatformSamplerBuilder` (headers handled in propagation layer).
- `Sdk` is diagnostic-only; same property read again in `PlatformAutoConfigurationCustomizer`.

### 4.3 Method-Level Property Mapping

| Section | Method | Property key | Type | Default | Reader | Fallback style | Nullable? |
|---|---|---|---|---|---|---|---|
| Sampling | `enabled()` | `platform.tracing.sampling.enabled` | boolean | `true` (`DEFAULT_ENABLED`) | `getBoolean(key, default)` | ConfigProperties default arg | no |
| Sampling | `ratio()` | `platform.tracing.sampling.ratio` | double | `0.1` (`DEFAULT_SAMPLING_RATIO`) | `getDouble(key, default)` | ConfigProperties default arg | no |
| Sampling | `forceRecordHeader()` | `platform.tracing.sampling.force-record-header` | String | `X-Trace-On` (`DEFAULT_FORCE_HEADER`) | `getString` | manual null → default | no |
| Sampling | `qaHeader()` | `platform.tracing.sampling.qa-header` | String | `X-QA-Trace` (`DEFAULT_QA_HEADER`) | `getString` | manual null → default | no |
| Sampling | `forceRecordValues()` | `platform.tracing.sampling.force-record-values` | `List<String>` | `["on"]` | `getList` | manual null → default list | no |
| Sampling | `dropPaths()` | `platform.tracing.sampling.drop-paths` | `List<String>` | actuator paths list | `getList` | manual null → default list | no |
| Sampling | `routeRatios()` | `platform.tracing.sampling.route-ratios` | `Map<String,String>` | `Map.of()` (empty) | `getMap` | manual null → empty immutable map | no |
| Enriching | `enabled()` | `platform.tracing.enriching.enabled` | boolean | `true` | `getBoolean` | ConfigProperties default arg | no |
| Enriching | `remoteServicePriority()` | `platform.tracing.enriching.remote-service-priority` | `List<String>` | peer.service list | `getList` | manual null → default | no |
| Scrubbing | `enabled()` | `platform.tracing.scrubbing.enabled` | boolean | `true` | `getBoolean` | ConfigProperties default arg | no |
| Scrubbing | `builtInRules()` | `platform.tracing.scrubbing.built-in-rules` | `List<String>` | built-in rule names | `getList` | manual null → default | no |
| Scrubbing | `hmacKey()` | `platform.tracing.scrubbing.hmac-key` | String | none | `getString` | no fallback | **yes** |
| Scrubbing | `missingKeyPolicy()` | `platform.tracing.scrubbing.missing-key-policy` | String | `"mask"` | `getString` | manual null → default | no |
| Scrubbing | `hashKeyId()` | `platform.tracing.scrubbing.hash.key-id` | String | none | `getString` | no fallback | **yes** |
| Scrubbing | `rulesConfig()` | `platform.tracing.scrubbing.rules-config` | String | none | `getString` | no fallback | **yes** |
| Scrubbing | `rulesExtensions()` | `platform.tracing.scrubbing.rules.extensions` | String | none | `getString` | no fallback | **yes** |
| Scrubbing | `rulesValidationMode()` | `platform.tracing.scrubbing.rules.validation-mode` | String | `"LENIENT"` | `getString` | manual null → default | no |
| Metrics | `enabled()` | `platform.tracing.metrics.enabled` | boolean | `true` | `getBoolean` | ConfigProperties default arg | no |
| Validation | `enabled()` | `platform.tracing.validation.enabled` | boolean | `true` | `getBoolean` | ConfigProperties default arg | no |
| Validation | `strict()` | `platform.tracing.validation.strict` | boolean | `false` | `getBoolean` | ConfigProperties default arg | no |
| Validation | `strictRuntimeAllowed()` | `platform.tracing.validation.strict-runtime-allowed` | boolean | `false` | `getBoolean` | ConfigProperties default arg | no |
| Resource | `policyVersion()` | `platform.tracing.resource.policy-version` | String | `"2026.06.08"` | `getString` | manual null → default | no |
| Resource | `normalizeEnvironment()` | `platform.tracing.resource.normalize-environment` | boolean | `true` | `getBoolean` | ConfigProperties default arg | no |
| Resource | `validationMode()` | `platform.tracing.resource.validation-mode` | String | `"LENIENT"` | `getString` | manual null → default | no |
| Resource | `detectContainerId()` | `platform.tracing.resource.detect-container-id` | boolean | `false` | `getBoolean` | ConfigProperties default arg | no |
| Classification | `enabled()` | `platform.tracing.classification.enabled` | boolean | `true` | `getBoolean` | ConfigProperties default arg | no |
| Classification | `slowThreshold()` | `platform.tracing.classification.slow-threshold` | Duration | 5s | `getDuration` | manual null → default | no |
| Classification | `normalThreshold()` | `platform.tracing.classification.normal-threshold` | Duration | 1s | `getDuration` | manual null → default | no |
| Watchdog | `enabled()` | `platform.tracing.watchdog.enabled` | boolean | `true` | `getBoolean` | ConfigProperties default arg | no |
| Watchdog | `spanTimeout()` | `platform.tracing.watchdog.span-timeout` | Duration | 30s | `getDuration` | manual null → default | no |
| Watchdog | `traceTimeout()` | `platform.tracing.watchdog.trace-timeout` | Duration | 60s | `getDuration` | manual null → default | no |
| Watchdog | `scanInterval()` | `platform.tracing.watchdog.scan-interval` | Duration | 5s | `getDuration` | manual null → default | no |
| Queue | `overflowPolicy()` | `platform.tracing.queue.overflow-policy` | String | `"DROP_OLDEST"` | `getString` | manual null → default | no |
| Baggage | `enabled()` | `platform.tracing.baggage.enabled` | boolean | `false` | `getBoolean` | ConfigProperties default arg | no |
| Baggage | `allowlistKeys()` | `platform.tracing.baggage.allowlist-keys` | `List<String>` | empty list | `getList` | manual null → default | no |
| Baggage | `denyPatterns()` | `platform.tracing.baggage.deny-patterns` | `List<String>` | password/secret/token | `getList` | manual null → default | no |
| Sdk | `mode()` | `platform.tracing.sdk.mode` | String | none | `getString` | no fallback | **yes** |

**Collection mutability notes:**

- Defaults from `ExtensionDefaults` use `List.of()` → **immutable**.
- `routeRatios()` default `Map.of()` → **immutable**.
- When `config.getList()` / `getMap()` returns non-null, mutability **unknown** (depends on OTel `ConfigProperties` implementation).
- Production factories often defensively copy (`new HashSet<>(allowlist)` in `PlatformSpanProcessorFactory`).

**Naming consistency:**

- Section accessor names: domain nouns (`sampling()`, `scrubbing()`).
- Method names: mostly adjectives/nouns (`enabled`, `ratio`, `overflowPolicy`).
- Inconsistency: `forceRecordHeader` vs property `force-record-header`; `qaHeader` vs `qa-header`.

---

## 5. Property and Default Ownership

### Responsibility map

| Concern | Owner class(es) | Channel |
|---|---|---|
| Property key strings | `ExtensionPropertyNames` | Agent (+ mirrored in Spring YAML prefix `platform.tracing`) |
| Platform defaults | `ExtensionDefaults` | Agent |
| OTel SDK defaults (`otel.bsp.*`, span limits) | `OtelSdkDefaults` → `PlatformTracingDefaultsProvider` | Agent SPI supplier |
| Enum string contracts | `ExtensionEnums` | Agent (+ documentation) |
| Env var names | `ExtensionEnvironmentVariables` | Agent env bridge |
| Typed reading facade | `ExtensionConfig` | Agent (test-only usage today) |
| Path / extension JAR resolution | `JavaAgentExtensionPaths` | Agent internal |
| Queue/export numeric defaults bridge | `DropOldestExportProcessorDefaults` | Agent (+ Spring alignment test) |
| SPI ordering | `AutoConfigurationCustomizerOrdering` | Agent |
| Spring UX properties | `TracingProperties` | Spring (`platform.tracing.*`) |
| Runtime sampling/scrubbing/validation | `SamplingRuntimeConfig`, `ScrubbingRuntimeConfig`, `ValidationRuntimeConfig` + JMX | Spring input → agent via `RuntimeConfigApplier` |
| Control plane API | `PlatformTracingControlMBean`, `SamplingControlClient` | JMX / Actuator |

### Agent vs Spring vs JMX vs OTel SDK

| Layer | What it configures | Lifecycle |
|---|---|---|
| **Agent extension (`ExtensionConfig` path)** | Sampler, processors, resource provider, export processor, propagators | Startup (`AutoConfigurationCustomizerProvider`) |
| **Spring `TracingProperties`** | UX: facade, response headers, AOP, diagnostics, actuator read model | After Spring context; partial RefreshScope |
| **JMX / `updateSamplingPolicy`** | Sampling schema v1 fields; scrubbing rule names; validation strict flags | Runtime atomic updates to agent holders |
| **OTel SDK native (`otel.*`)** | BSP, exporters, propagators, service name via OTEL_* | Agent autoconfigure; env/system properties |
| **Collector / SRE** | Tail sampling, k8s attributes | Outside JVM agent scope |

### Properties in agent extension NOT exposed via `ExtensionConfig`

| Property key | Owner | Notes |
|---|---|---|
| `platform.tracing.service.name` | `PlatformResourceProvider.PROP_SERVICE_NAME` | Resource identity |
| `platform.tracing.service.version` | `PlatformResourceProvider.PROP_SERVICE_VERSION` | Resource identity |
| `platform.tracing.service.environment` | `PlatformResourceProvider.PROP_ENVIRONMENT` | Resource identity |
| `platform.tracing.service.c-group` | `PlatformResourceProvider.PROP_C_GROUP` | Resource identity |
| `platform.tracing.service.id` | `PlatformResourceProvider.PROP_ID` | Resource identity |
| `platform.tracing.service.host` | `PlatformResourceProvider.PROP_HOST` | Resource identity |
| `platform.tracing.service.container-id` | `PlatformResourceProvider.PROP_CONTAINER_ID` | Resource identity |
| `platform.tracing.policy.version` | `ExtensionPropertyNames.RESOURCE_POLICY_VERSION_ATTR` | Resource attribute key (not config key) |
| `otel.bsp.max.queue.size` etc. | `PlatformTracingDefaultsProvider` via `OtelSdkDefaults` | OTel SDK defaults supplier |
| `otel.javaagent.extensions` | `ExtensionPropertyNames.OTEL_JAVAAGENT_EXTENSIONS` | Scrubbing classpath guard |

### Complete `platform.tracing.*` property catalog (agent package + adjacent)

| Property key | Domain | Type | Default (agent) | Reader in production | Runtime mutable? | Spring equivalent | JMX equivalent | Documented | Tested |
|---|---|---|---|---|---|---|---|---|---|
| `platform.tracing.sampling.enabled` | sampling | boolean | `true` | `PlatformSamplerBuilder` | **yes** (JMX v1) | `TracingProperties.Sampling.enabled` | `updateSamplingPolicy` | yes (TracingProperties javadoc) | yes |
| `platform.tracing.sampling.ratio` | sampling | double | **drift:** facade `0.1`, builder `1.0` if absent | `PlatformSamplerBuilder` | **yes** | `Sampling.ratio` | JMX | yes | partial |
| `platform.tracing.sampling.route-ratios` | sampling | map | `{}` | `PlatformSamplerBuilder.parseRouteRatios` | **yes** | `Sampling.routeRatios` | JMX | yes | yes |
| `platform.tracing.sampling.force-record-header` | sampling | string | `X-Trace-On` | **NOT in PlatformSamplerBuilder** | startup-only | `Sampling.forceRecordHeader` | NOT in JMX v1 | yes | ExtensionConfigTest only |
| `platform.tracing.sampling.force-record-values` | sampling | list | `["on"]` | `PlatformSamplerBuilder` | **yes** | `Sampling.forceRecordHeaderValues` | JMX | yes | yes |
| `platform.tracing.sampling.qa-header` | sampling | string | `X-QA-Trace` | **NOT in PlatformSamplerBuilder** | startup-only | `Sampling.qaForceHeader` | NOT in JMX v1 | yes | ExtensionConfigTest only |
| `platform.tracing.sampling.drop-paths` | sampling | list | actuator paths | `PlatformSamplerBuilder` | **yes** | `Sampling.dropPaths` | JMX | yes | yes |
| `platform.tracing.enriching.enabled` | enriching | boolean | `true` | `PlatformSpanProcessorFactory` | startup-only | `TracingProperties.Enriching` | NOT FOUND IN REPOSITORY | partial | integration |
| `platform.tracing.enriching.remote-service-priority` | enriching | list | peer.service list | `PlatformSpanProcessorFactory` | startup-only | enriching section | NOT FOUND | partial | integration |
| `platform.tracing.scrubbing.*` (7 keys) | scrubbing | mixed | see ExtensionDefaults | `PlatformSpanProcessorFactory` | partial runtime via JMX rule names | `TracingProperties.Scrubbing` | scrubbing JMX ops | yes | yes |
| `platform.tracing.metrics.enabled` | metrics | boolean | `true` | `PlatformSpanProcessorFactory` | startup-only | metrics section | NOT FOUND | partial | integration |
| `platform.tracing.validation.*` (3 keys) | validation | boolean | see defaults | `PlatformSpanProcessorFactory` | `strictRuntimeAllowed` startup flag; strict via JMX | `TracingProperties.Validation` | JMX | yes (`runtime-policy-control-architecture.md`) | yes |
| `platform.tracing.resource.*` (4 keys) | resource | mixed | see defaults | `ResourceAttributeResolver` | startup-only | `TracingProperties.Resource` | NOT FOUND | yes (ADR-resource) | `PlatformResourceProviderTest` |
| `platform.tracing.classification.*` | classification | bool+duration | see defaults | `PlatformSpanProcessorFactory` | startup-only | classification section | NOT FOUND | partial | integration |
| `platform.tracing.watchdog.*` | watchdog | bool+duration | see defaults | `PlatformSpanProcessorFactory` | startup-only | watchdog section | JMX read via MBean | partial | integration |
| `platform.tracing.queue.overflow-policy` | queue | string | `DROP_OLDEST` | `PlatformExportProcessorFactory` | startup-only (+ env bridge) | `TracingProperties.Queue` | diagnostics only | yes (ADR) | yes |
| `platform.tracing.baggage.*` | baggage | bool+lists | see defaults | `PlatformSpanProcessorFactory` | startup-only | baggage section | NOT FOUND | partial | integration |
| `platform.tracing.sdk.mode` | sdk | string | null | `PlatformAutoConfigurationCustomizer` (log only) | startup-only diagnostic | `TracingProperties.Sdk.mode` | actuator read model | yes (ADR-dual-channel) | `SdkModeDetectionAutoConfigurationTest` |
| `platform.tracing.service.*` (7 keys) | resource | string | none | `PlatformResourceProvider`, env bridge | startup-only | `TracingProperties.Service` | NOT FOUND | yes | `PlatformResourceProviderTest` |
| `platform.tracing.diagnostics.dual-channel-warn` | other | boolean | NOT in ExtensionPropertyNames | Spring `DualChannelDriftDiagnostics` | Spring startup | Spring-only | NOT FOUND | yes (ADR) | partial |

---

## 6. Usage Map

### 6.1 Production usages of `ExtensionConfig`

**NOT FOUND IN REPOSITORY** — ни один production `.java` файл не содержит `new ExtensionConfig(` или `ExtensionConfig.`.

`Inference:` `ExtensionConfig` был добавлен как typed facade / migration artifact, но factories не были переведены на него.

### 6.2 Production usages of configuration package (direct property reading)

| Caller | Module | Method / context | Section / API used | Startup/runtime/hot path | Risk if changed |
|---|---|---|---|---|---|
| `PlatformAutoConfigurationCustomizer` | otel-extension | `customize()` | `PlatformTracingDefaultsProvider`, `ExtensionPropertyNames.SDK_MODE` | startup | HIGH — breaks agent bootstrap |
| `PlatformSpanProcessorFactory` | otel-extension | `registerSpanProcessors()` | baggage, enriching, scrubbing, validation, classification, watchdog, metrics | startup (processors on hot path after init) | **CRITICAL** — span start path |
| `PlatformSamplerBuilder` | otel-extension | `build()` | sampling keys | startup; sampler **hot path** after init | **CRITICAL** |
| `PlatformExportProcessorFactory` | otel-extension | `maybeReplaceExportProcessor()` | `QUEUE_OVERFLOW_POLICY` | startup; export path | HIGH |
| `ResourceAttributeResolver` | otel-extension | `resolve()` | resource keys | startup | MEDIUM |
| `PlatformResourceProvider` | otel-extension | `createResource()` | resource + `platform.tracing.service.*` | startup | MEDIUM |
| `ScrubbingRulesLoader` / `PlatformSpanProcessorFactory.collectScrubbingRules` | otel-extension | scrubbing load | scrubbing keys + `JavaAgentExtensionPaths` | startup | HIGH — security |
| `PlatformDropOldestExportSpanProcessor` | otel-extension | builder defaults | `ExtensionDefaults.DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT` | startup + export | MEDIUM |
| `DropOldestAspirationDiagnostics` | spring-autoconfigure | actuator diagnostics | reads agent defaults via bridge | startup/read | LOW |
| `OtelEffectiveConfigSnapshot` | spring-autoconfigure | actuator | references `ExtensionPropertyNames` | read-only | LOW |

### 6.3 Test usages of `ExtensionConfig`

| Test class | What it validates |
|---|---|
| `ExtensionConfigTest` | All section defaults + selective overrides (17 tests) |
| Migration docs reference | Listed as guard test in preservation-first migration plan |

**Gap:** no test proves `ExtensionConfig` readings match `PlatformSpanProcessorFactory` / `PlatformSamplerBuilder` behavior.

---

## 7. Runtime Mutability Boundaries

| Configuration area | Startup source | Runtime mutable? | Mechanism | Notes |
|---|---|---|---|---|
| Sampling enabled/ratio/dropPaths/forceValues/routeRatios | `PlatformSamplerBuilder` reads `ConfigProperties` | **yes** | JMX `updateSamplingPolicy` → `SamplerStateHolder` CAS | Agent holder is source of truth |
| Sampling header names | `ExtensionConfig` only (unused prod) | startup-only | NOT in JMX v1 | Propagation layer uses platform headers |
| Scrubbing rules / HMAC | factories at startup | partial | JMX scrubbing rule list updates | HMAC key startup-only |
| Validation strict / strictRuntimeAllowed | factory at startup | partial | JMX + `liveStrictRuntimeAllowed` read model | docs warn Spring vs agent drift |
| Processors toggles (metrics/classification/watchdog/enriching/baggage) | factories | startup-only | NOT FOUND IN REPOSITORY (runtime toggle) | Requires agent restart to change |
| Resource identity | `PlatformResourceProvider` | startup-only | NOT FOUND | |
| Queue overflow policy | factory + env bridge | startup-only | NOT FOUND | |
| OTel BSP / span limits | `PlatformTracingDefaultsProvider` | startup-only via OTEL_* | Change requires env/-D | Spring YAML override **does not** apply to agent |
| Sdk mode | diagnostic log | startup-only | NOT FOUND | |

**Inference:** `ExtensionConfig` should remain a **startup-only read facade** if wired to production; it must not imply runtime refresh unless explicit snapshot rebuild is designed.

---

## 8. Spring / Agent / JMX Boundary Analysis

```
┌──────────────────────────── Agent startup (before Spring) ────────────────────────────┐
│ ConfigProperties / OTEL_* / -Dotel.*                                                  │
│   → PlatformTracingDefaultsProvider (otel.bsp.*, span limits, env bridge)             │
│   → PlatformSamplerBuilder (sampling startup snapshot → SamplerStateHolder)         │
│   → PlatformSpanProcessorFactory / PlatformExportProcessorFactory                      │
│   → PlatformResourceProvider                                                          │
│   → ExtensionConfig (exists but NOT wired)                                            │
└───────────────────────────────────────────────────────────────────────────────────────┘
                                        │
                                        ▼
┌──────────────────────────── Spring context ───────────────────────────────────────────┐
│ TracingProperties (platform.tracing.* YAML)                                           │
│   → RuntimeConfigApplier → JMX push to agent                                          │
│   → Actuator read models (configured view vs live agent view)                          │
│   → DualChannelDriftDiagnostics (whitelist WARN)                                      │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

**Evidence sources:**

- `docs/decisions/ADR-dual-channel-properties-v0.1.md` — dual channel by design; no Spring→Agent bridge
- `docs/tracing/runtime-policy-control-architecture.md` — agent vs Spring validation drift
- `SamplingRuntimeConfig.java` — JMX v1 field list
- `ExtensionNoSpringDependencyArchTest` — agent must not import Spring

**Known drift example (repository evidence):**

`PlatformSamplerBuilder.build()` lines 50–55: if `SAMPLING_RATIO` property absent, uses `defaultRatio = 1.0`.  
`ExtensionConfig.Sampling.ratio()` uses `getDouble(..., ExtensionDefaults.DEFAULT_SAMPLING_RATIO)` → **0.1**.

---

## 9. Existing Tests and Safety Net

| Test | Module | Behavior protected | Gaps |
|---|---|---|---|
| `ExtensionConfigTest` | otel-extension | Facade defaults + overrides per section | No production wiring; no cross-check with factories |
| `ExtensionEnumsTest` | otel-extension | Enum string contracts match defaults | Does not test parsing in factories |
| `PlatformTracingDefaultsProviderTest` | otel-extension | OTel SDK default supplier map | Does not cover all platform.tracing keys |
| `PlatformTracingDefaultsProviderResourceEnvTest` | otel-extension | Env bridge for resource + queue | Narrow scope |
| `JavaAgentExtensionPathsTest` | otel-extension | Extension JAR path resolution order | |
| `DropOldestExportProcessorDefaultsTest` | otel-extension | Bridge to OtelSdkDefaults | |
| `SharedDefaultsAlignmentTest` | spring-autoconfigure | BSP queue + span limits Spring vs agent | Whitelist only; not universal |
| `TracingPropertiesBindingTest` | spring-autoconfigure | Spring property binding | Agent path not covered |
| `PlatformSamplerProviderTest` | otel-extension | Sampler SPI integration | May not assert all default values |
| `PlatformSpiAutoconfigureIntegrationTest` | otel-extension | End-to-end agent autoconfigure | |
| `ExtensionNoSpringDependencyArchTest` | otel-extension | No Spring in extension | |
| `ArchitectureFitnessArchRules` | platform-tracing-test | Wire/control-plane guardrails | Not specific to ExtensionConfig |
| `ProductionControlPlaneNotMigratedArchTest` | spring-autoconfigure | Production vs spike separation | |

**After decomposition:** tests that assert nested `ExtensionConfig` API will need migration if sections become top-level types. Factory integration tests must become the primary safety net.

---

## 10. Architecture Constraints from Repository

| Constraint | Source | Impact on refactoring |
|---|---|---|
| `otel-extension` must not depend on Spring | `ExtensionNoSpringDependencyArchTest`, `ADR-dual-channel-properties-v0.1.md`, migration plan | No Spring types in configuration package; no `@ConfigurationProperties` import |
| Dual-channel: agent reads ConfigProperties/env only | ADR-dual-channel, `PlatformAutoConfigurationCustomizer` javadoc | Refactor must not introduce Spring Environment bridge in extension |
| No full Spring→`-Dotel.*` bridge | ADR-dual-channel variant C rejected | Alignment tests stay whitelist-based |
| `ExtensionPropertyNames` strings are stable external contract | `ExtensionPropertyNames` class javadoc | Renaming keys is breaking; refactor internal only |
| Agent extension loaded in isolated ClassLoader | `ExtensionNoSpringDependencyArchTest` javadoc | Keep configuration package lightweight; avoid heavy deps |
| Core module pure Java (no OTel/Spring) | `CorePolicyPackagePurityArchTest`, migration plan | Configuration stays in otel-extension, not core |
| No OTel internal source copy | `ADR-otel-direct-integration.md` (cited in other ADRs) | Token bucket / SDK patterns reimplement, not copy |
| JMX surface expansion requires care | `ArchitectureFitnessArchRules`, runtime ADR | Config refactor should not accidentally add JMX getters |
| Shared defaults alignment whitelist | `SharedDefaultsAlignmentTest` | Any default change must update both Spring and agent defaults |
| Preservation-first: keep `ExtensionConfigTest` | `platform-tracing-preservation-first-migration-plan.md` | Decomposition must preserve or replace test coverage |
| Performance: sampler hot path | `CompositeSamplerBenchmark`, performance budgets | Startup config refactor must not add per-span allocation |
| SPI ordering | `AutoConfigurationCustomizerOrdering.PLATFORM_EXTENSION_ORDER = 100` | Ordering class likely unchanged |

---

## 11. Pain Points and Maintainability Problems

| Pain point | Evidence | Impact |
|---|---|---|
| **Facade not wired to production** | `rg ExtensionConfig` → only `ExtensionConfig.java` + `ExtensionConfigTest.java` | Duplicate reading logic; facade misleading for discoverability |
| **Large monolithic file** | `ExtensionConfig.java` 329 lines, 11 nested classes | Hard to navigate; high merge conflict surface |
| **Repeated null-fallback boilerplate** | Every string/list/duration method: `v = config.getX(); return v != null ? v : DEFAULT` | Error-prone when adding properties |
| **Inconsistent fallback styles** | Mix of `getBoolean(key, default)` vs manual null checks | Hard to audit defaults |
| **Default drift vs factories** | Sampling ratio 0.1 (facade) vs 1.0 (PlatformSamplerBuilder when absent) | Silent behavioral divergence |
| **Partial domain coverage** | `ExtensionConfig.Resource` missing `platform.tracing.service.*` | False sense of complete resource config |
| **Header properties dead in sampler path** | `forceRecordHeader`/`qaHeader` in ExtensionConfig but not PlatformSamplerBuilder | Confusing ownership |
| **Per-call nested instance allocation** | `sampling()` returns `new Sampling(config)` | Minor startup cost; bad pattern if ever called repeatedly |
| **No typed property abstraction** | Raw strings duplicated across facade + factories | Typo risk; no single parse validation |
| **Dual-channel duplication with Spring** | Parallel structures in `TracingProperties` nested classes | Maintenance burden; drift diagnostics needed |
| **Test surface ≠ production surface** | `ExtensionConfigTest` passes; factories untested against facade | Refactor can break production while tests green |
| **Scrubbing complexity split** | Facade reads 7 keys; factory adds loader/validation logic | Section boundary incoherent |

---

## 12. Refactoring Risk Map

| Risk | Severity | Evidence | Mitigation |
|---|---:|---|---|
| Breaking startup property reading | **Critical** | Factories directly on hot path | Characterization tests facade vs factory; incremental wiring |
| Breaking default values | **High** | `ExtensionDefaults` used in 10+ places | Contract tests; update `SharedDefaultsAlignmentTest` whitelist |
| Breaking collection mutability assumptions | **Medium** | Factories copy some lists to `HashSet` | Document immutable snapshots; defensive copy at boundary |
| Accidental runtime coupling | **High** | JMX updates bypass ConfigProperties | Keep startup snapshot immutable; separate runtime DTOs |
| Spring dependency in otel-extension | **Critical** | ArchUnit test fails build | Code review + ArchUnit gate |
| Over-splitting into tiny classes | **Medium** | 11 sections × 1–7 methods | Prefer domain sections over per-property classes |
| Premature code generation (A5) | **Medium** | Small finite property set (~40 keys) | Evaluate ROI before codegen |
| Breaking nested API tests | **Medium** | `ExtensionConfigTest` uses nested accessors | Migration shims or parallel test period |
| Reduced discoverability | **Medium** | Today devs grep `ExtensionPropertyNames` in factories | Unified entry point docs / IDE navigation |
| Duplicating TracingProperties structure | **Low–Medium** | Spring has richer sections (facade, kafka, aop) | Align only shared subset per ADR whitelist |
| Sampling ratio default drift regression | **High** | Documented 0.1 vs 1.0 mismatch | Explicit decision + single default source |

---

## 13. Candidate Architecture Families for Later Scoring

### A0 — Keep ExtensionConfig facade, extract nested classes to package-private top-level section classes

**What would change:** Move `ExtensionConfig.Sampling` etc. to `SamplingExtensionConfig` (package-private) files; facade delegates.

**What would preserve:** Public `ExtensionConfig` API; test structure mostly intact.

**Modules likely touched:** `platform-tracing-otel-extension` only.

**Expected complexity:** Low–medium.

**Main risk:** Still does not fix production unwired facade.

**Questions for Perplexity:** Does file split alone improve maintainability enough? When to wire factories?

---

### A1 — Domain section classes with shared typed property reader

**What would change:** Introduce small `ExtensionPropertyReader` helpers (boolean/string/list/duration with unified fallback); section classes use reader.

**What would preserve:** Property keys in `ExtensionPropertyNames`; defaults in `ExtensionDefaults`.

**Modules likely touched:** otel-extension configuration + factories (if migrated).

**Expected complexity:** Medium.

**Main risk:** Abstraction leakage; reader API design churn.

**Questions for Perplexity:** Optimal reader API shape? How to type-check enum properties?

---

### A2 — Immutable snapshot records built once at startup

**What would change:** `ExtensionStartupSnapshot` record built once from `ConfigProperties`; factories consume snapshot not raw config.

**What would preserve:** Startup-only semantics; clear immutability boundary.

**Modules likely touched:** otel-extension configuration, all factories.

**Expected complexity:** Medium–high.

**Main risk:** Large blast radius across factories; snapshot rebuild temptation for runtime.

**Questions for Perplexity:** One snapshot vs per-domain snapshots? How to handle nullable scrubbing secrets?

---

### A3 — Hybrid facade + immutable domain snapshots

**What would change:** Facade wraps pre-built immutable section snapshots (cached at construction).

**What would preserve:** `ExtensionConfig` entry point; eliminates per-call `new Sampling()`.

**Modules likely touched:** otel-extension.

**Expected complexity:** Medium.

**Main risk:** Two layers (facade + snapshot) unless factories migrated.

**Questions for Perplexity:** Is facade still needed if snapshots exist?

---

### A4 — Property descriptor registry / typed key model

**What would change:** Static registry mapping key → type → default → parser; sections iterate registry subset.

**What would preserve:** External property strings unchanged.

**Modules likely touched:** otel-extension configuration; possibly tests.

**Expected complexity:** Medium–high.

**Main risk:** Over-engineering for ~40 properties; reflection/metadata complexity.

**Questions for Perplexity:** Registry vs enum-of-keys tradeoff? Testability gains?

---

### A5 — Generated or declarative config metadata model

**What would change:** YAML/annotation spec generates PropertyNames, Defaults, readers, docs.

**What would preserve:** Generated output committed or built at compile time.

**Modules likely touched:** otel-extension + build plugin (forbidden without explicit approval?).

**Expected complexity:** High upfront.

**Main risk:** Build complexity; team workflow change.

**Questions for Perplexity:** Worth it at current property count? Dual-channel codegen with Spring?

---

### A6 — Align with Spring `TracingProperties` structure while keeping agent-side pure Java

**What would change:** Mirror Spring nested section names/fields in agent snapshot types (not Spring deps).

**What would preserve:** Dual-channel ADR; operator mental model alignment.

**Modules likely touched:** otel-extension + alignment tests in spring-autoconfigure.

**Expected complexity:** Medium–high.

**Main risk:** Spring has extra sections (kafka, aop) not in agent — incomplete mirror.

**Questions for Perplexity:** Which sections must mirror vs stay agent-only?

---

### A7 — Minimal cleanup only: helper methods + grouping + tests, no structural split

**What would change:** Extract private static helpers for fallback; fix ratio drift; add factory↔facade contract tests; wire factories to facade incrementally.

**What would preserve:** Class structure; lowest blast radius.

**Modules likely touched:** otel-extension only.

**Expected complexity:** Low.

**Main risk:** Does not solve long-term growth of nested classes.

**Questions for Perplexity:** Is incremental wiring sufficient pre-prod? Stop condition?

---

## 14. Proposed Scoring Criteria for Later Deep Research

| Criterion | Weight | Why it matters |
|---|---:|---|
| Readability / maintainability | 15 | Primary motivation for refactor |
| Architecture fit (dual-channel, no Spring in extension) | 15 | Hard repository constraint |
| Startup / runtime separation clarity | 10 | Avoid accidental mutable config |
| Low blast radius | 12 | Pre-prod but hot-path sensitive |
| Testability | 10 | Must catch facade/factory drift |
| Property / default safety | 12 | Defaults affect sampling/scrubbing/security |
| Avoidance of overengineering | 10 | Small team; ~40 properties |
| Discoverability for new contributors | 5 | Onboarding cost |
| Future extensibility (new domains) | 5 | Rate-limit F3-style properties coming |
| Performance / allocation neutrality | 6 | Sampler/export hot paths |
| Cognitive load (number of concepts) | 5 | Facade + snapshots + registry cost |
| Migration effort / deliverable size | 5 | Time-to-value |

**Total:** 100 points.

---

## 15. Open Questions for Perplexity Deep Research

1. Should `ExtensionConfig` become the **mandatory** production entry point, or be deprecated in favor of startup snapshots?
2. How to resolve **sampling ratio default drift** (0.1 vs 1.0) — which is authoritative for agent behavior?
3. Should `platform.tracing.service.*` keys join `ExtensionConfig.Resource` or a separate `ServiceIdentity` section?
4. Is one immutable `ExtensionStartupSnapshot` preferable to per-factory localized reading?
5. How to test dual-channel alignment beyond `SharedDefaultsAlignmentTest` whitelist without universal fail-fast?
6. Should nullable scrubbing secrets (`hmacKey`) live in snapshot objects or separate secure config type?
7. What is the minimum contract test suite to safe refactor (facade vs factory parity)?
8. Does A5 codegen pay off before property count reaches N?
9. How should future **startup-only** properties (e.g. rate-limit F3 spike) be added — ExtensionPropertyNames only vs facade section?
10. Can A6 mirror help operators without creating false equivalence with Spring RefreshScope?

---

## 16. Recommended Next Prompt Inputs

Attach to Perplexity / Deep Research together with this dossier:

**Primary source files:**

- `platform-tracing-otel-extension/src/main/java/space/br1440/platform/tracing/otel/extension/configuration/ExtensionConfig.java`
- `platform-tracing-otel-extension/src/main/java/space/br1440/platform/tracing/otel/extension/configuration/ExtensionPropertyNames.java`
- `platform-tracing-otel-extension/src/main/java/space/br1440/platform/tracing/otel/extension/configuration/ExtensionDefaults.java`
- `platform-tracing-otel-extension/src/main/java/space/br1440/platform/tracing/otel/extension/configuration/PlatformTracingDefaultsProvider.java`
- `platform-tracing-otel-extension/src/main/java/space/br1440/platform/tracing/otel/extension/factory/PlatformSpanProcessorFactory.java`
- `platform-tracing-otel-extension/src/main/java/space/br1440/platform/tracing/otel/extension/sampler/PlatformSamplerBuilder.java`
- `platform-tracing-otel-extension/src/main/java/space/br1440/platform/tracing/otel/extension/factory/PlatformExportProcessorFactory.java`
- `platform-tracing-otel-extension/src/test/java/space/br1440/platform/tracing/otel/extension/configuration/ExtensionConfigTest.java`

**Spring / runtime boundary:**

- `platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/TracingProperties.java`
- `platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/sampling/SamplingRuntimeConfig.java`
- `platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/RuntimeConfigApplier.java`
- `platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/SharedDefaultsAlignmentTest.java`

**Architecture docs:**

- `docs/decisions/ADR-dual-channel-properties-v0.1.md`
- `docs/tracing/runtime-policy-control-architecture.md`
- `docs/architecture/platform-tracing-preservation-first-migration-plan.md` (configuration row)
- `platform-tracing-otel-extension/src/test/java/space/br1440/platform/tracing/otel/extension/arch/ExtensionNoSpringDependencyArchTest.java`

---

## 17. Appendix: Search Commands / Evidence Collection

Commands used (adapt paths for Gentoo/Linux; executed via repository search tools on Windows workspace):

```bash
rg "ExtensionConfig" --glob "*.java"
rg "new ExtensionConfig" --glob "*.java"
rg "ExtensionPropertyNames" --glob "*.java"
rg "ExtensionDefaults" --glob "*.java"
rg "ConfigProperties" platform-tracing-otel-extension platform-tracing-core platform-tracing-spring-boot-autoconfigure
rg "platform\.tracing\." platform-tracing-otel-extension/src/main/java
rg "updateSamplingPolicy|RuntimeConfigApplier|PlatformTracingControlMBean|SamplingRuntimeConfig" .
rg "TracingProperties" platform-tracing-spring-boot-autoconfigure
rg "ArchUnit|ArchitectureFitness|ExtensionNoSpring" .
glob "**/otel/extension/configuration/*.java"
```

**Key grep results:**

- `ExtensionConfig` production usages: **0 files** (only definition + test)
- `ExtensionPropertyNames` production usages: **15+ files** across otel-extension and spring-autoconfigure diagnostics
- Configuration package Java sources: **11 main + 6 test** files under `platform-tracing-otel-extension/.../configuration/`

---

*End of dossier. No Java code, tests, or build files were modified.*
