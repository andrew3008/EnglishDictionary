# Extension Configuration Package Inventory

## 1. Executive Summary

В пакете `space.br1440.platform.tracing.otel.javaagent.configuration` обнаружено **22 production Java-класса** в **одном плоском (flat) пакете** без подпакетов.

После рефакторинга A3' пакет содержит следующие функциональные группы:

| Группа | Классы (кол-во) |
|--------|-----------------|
| Facade | `ExtensionConfig` (1) |
| Domain config (immutable startup DTO) | 11 классов `*ExtensionConfig` |
| Reader / bootstrap helper | `ExtensionConfigReader` (1) |
| Property contract | `ExtensionPropertyNames`, `ExtensionDefaults`, `ExtensionEnvironmentVariables` (3) |
| Typed enum constants | `ExtensionEnums` (1) |
| OTel SDK / BSP defaults | `OtelSdkDefaults`, `DropOldestExportProcessorDefaults` (2) |
| SPI defaults supplier | `PlatformTracingDefaultsProvider` (1) |
| Path / ordering helpers | `JavaAgentExtensionPaths`, `AutoConfigurationCustomizerOrdering` (2) |

**Package-private ограничения существенны:** конструкторы всех 11 domain config-классов, весь класс `ExtensionConfigReader`, а также `ExtensionEnvironmentVariables` и `OtelSdkDefaults` доступны только внутри пакета. Перенос domain config или reader в подпакет без изменения видимости **сломает** `ExtensionConfig` и потребует widening visibility или module-internal API.

**Внешние потребители (cross-module):** модуль `platform-tracing-spring-boot-autoconfigure` импортирует `DropOldestExportProcessorDefaults` и `PlatformTracingDefaultsProvider` **только из тестов**. Production cross-package imports идут из `platform-tracing-otel-javaagent-extension` (`factory`, `sampler`, `resource`, `processor`, `scrubbing`, bootstrap SPI).

Перенос пакета рискован из-за: (1) package-private якорей, (2) ArchUnit guardrails с hard-coded package path `..configuration..`, (3) same-package tests, обращающихся к package-private типам, (4) cross-module alignment-тестов на публичные defaults API.

Финальная архитектура подпакетов **не выбиралась** в этом документе.

---

## 2. Scope

**Инспектированный пакет:**

```text
space.br1440.platform.tracing.otel.javaagent.configuration
```

**Путь в репозитории:**

```text
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/
```

**Инспектированные модули (production + test imports):**

| Модуль | Путь |
|--------|------|
| `platform-tracing-otel-javaagent-extension` | `src/main/java`, `src/test/java` |
| `platform-tracing-spring-boot-autoconfigure` | `src/main/java`, `src/test/java` |

**Команды / поиски (выполнены эквиваленты через Glob + Grep + Read; ОС Windows, shell PowerShell):**

```bash
# Glob эквивалент find ... -name "*.java" | sort
Glob: platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/**/*.java

rg "package space\.br1440\.platform\.tracing\.otel\.extension\.configuration" platform-tracing-otel-javaagent-extension/src/main/java
rg "import space\.br1440\.platform\.tracing\.otel\.extension\.configuration\." \
   platform-tracing-otel-javaagent-extension/src/main/java platform-tracing-otel-javaagent-extension/src/test/java \
   platform-tracing-spring-boot-autoconfigure/src/main/java platform-tracing-spring-boot-autoconfigure/src/test/java

rg "new ExtensionConfig|new [A-Za-z]+ExtensionConfig|ExtensionConfig\(" platform-tracing-otel-javaagent-extension/src/main/java platform-tracing-otel-javaagent-extension/src/test/java

rg "ExtensionPropertyNames|ExtensionDefaults|ExtensionEnums|ExtensionConfigReader|PlatformTracingDefaultsProvider|OtelSdkDefaults|JavaAgentExtensionPaths|AutoConfigurationCustomizerOrdering|DropOldestExportProcessorDefaults" \
   platform-tracing-otel-javaagent-extension/src/main/java platform-tracing-otel-javaagent-extension/src/test/java

rg "ConfigProperties" platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration \
   platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent

rg "public final class .*ExtensionConfig|final class .*ExtensionConfig|class ExtensionConfigReader|class ExtensionPropertyNames|class ExtensionDefaults|enum ExtensionEnums" \
   platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration

rg "platform\.tracing\." platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration

git status --short
git ls-files "platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/*.java"
```

**Git:** `NOT FOUND IN REPOSITORY` — команды `git status` / `git ls-files` завершились с `fatal: not a git repository`.

**Дата инвентаризации:** 2026-06-16 18:24:10 +03:00 (локальное время среды выполнения).

**Ограничение задачи:** production Java, tests и build files **не изменялись**. Создан только этот Markdown-файл.

---

## 3. Class Inventory

| Class | Kind | Visibility | Main Responsibility | Holds State? | Depends on ConfigProperties? | External Consumers? | Candidate Group |
|-------|------|------------|---------------------|:------------:|:----------------------------:|:-------------------:|-----------------|
| `ExtensionConfig` | facade DTO | public | Единый bootstrap facade; строит все domain configs из `ConfigProperties` один раз | Yes (immutable refs) | Yes (constructor only) | Yes | facade |
| `SamplingExtensionConfig` | domain config | public class, package-private ctor | Startup snapshot sampling-домена | Yes | No | Yes | domain-config |
| `ScrubbingExtensionConfig` | domain config | public class, package-private ctor | Startup snapshot scrubbing-домена | Yes | No | Yes | domain-config |
| `EnrichingExtensionConfig` | domain config | public class, package-private ctor | Startup snapshot enriching-домена | Yes | No | Yes (via facade) | domain-config |
| `BaggageExtensionConfig` | domain config | public class, package-private ctor | Startup snapshot baggage-домена | Yes | No | Yes (via facade) | domain-config |
| `ClassificationExtensionConfig` | domain config | public class, package-private ctor | Startup snapshot classification-домена | Yes | No | Yes (via facade) | domain-config |
| `WatchdogExtensionConfig` | domain config | public class, package-private ctor | Startup snapshot watchdog-домена | Yes | No | Yes (via facade) | domain-config |
| `ValidationExtensionConfig` | domain config | public class, package-private ctor | Startup snapshot validation-домена | Yes | No | Yes (via facade) | domain-config |
| `MetricsExtensionConfig` | domain config | public class, package-private ctor | Startup snapshot metrics-домена | Yes | No | Yes (via facade) | domain-config |
| `QueueExtensionConfig` | domain config | public class, package-private ctor | Startup snapshot queue overflow policy | Yes | No | Yes | domain-config |
| `ResourceExtensionConfig` | domain config | public class, package-private ctor | Startup snapshot resource policy flags | Yes | No | No (production direct) | domain-config |
| `SdkExtensionConfig` | domain config | public class, package-private ctor | Startup snapshot `platform.tracing.sdk.mode` | Yes | No | Yes (via facade) | domain-config |
| `ExtensionConfigReader` | reader/helper | package-private class | Typed read helpers over `ConfigProperties` | Yes (holds ref) | Yes | No (in-package only) | reader/helper |
| `ExtensionPropertyNames` | constants | public `@UtilityClass` | Canonical property key strings (`platform.tracing.*`, `otel.javaagent.extensions`) | No | No | Yes | property-contract |
| `ExtensionDefaults` | constants | public `@UtilityClass` | Platform default values for extension properties | No | No | Yes | defaults |
| `ExtensionEnvironmentVariables` | constants | package-private `@UtilityClass` | Env var names for defaults fallback | No | No | No (in-package only) | env |
| `ExtensionEnums` | enums holder | public final class | Typed string enums for queue/scrubbing/sdk modes | No | No | Yes | enums |
| `OtelSdkDefaults` | constants | package-private `@UtilityClass` | Internal OTel SDK BSP/span limit defaults | No | No | No (in-package + same-package tests) | sdk-defaults |
| `DropOldestExportProcessorDefaults` | facade over SDK defaults | public `@UtilityClass` | Public BSP defaults for DROP_OLDEST processor | No | No | Yes (cross-module test) | otel-defaults-provider |
| `PlatformTracingDefaultsProvider` | SPI supplier | public | Supplies merged platform + OTel SDK defaults map for agent bootstrap | Yes (env supplier) | No | Yes (cross-module test) | otel-defaults-provider |
| `JavaAgentExtensionPaths` | path helper | public `@UtilityClass` | Resolves `otel.javaagent.extensions` from ConfigProperties/sysprop/env | No | Yes (method param) | Yes | rule/path helper |
| `AutoConfigurationCustomizerOrdering` | ordering constant | public `@UtilityClass` | OTel SPI order for `PlatformAutoConfigurationCustomizer` | No | No | Yes | ordering |

---

## 4. Detailed Class Cards

### ExtensionConfig

- **File:** `platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/ExtensionConfig.java`
- **Kind:** facade DTO
- **Visibility:** public final class
- **Constructor visibility:** public `ExtensionConfig(ConfigProperties config)`
- **Public API:** Lombok `@Getter` + `@Accessors(fluent = true)` → `sampling()`, `enriching()`, `scrubbing()`, `metrics()`, `validation()`, `resource()`, `classification()`, `watchdog()`, `queue()`, `baggage()`, `sdk()`; public constructor
- **Package-private API:** none
- **Main responsibility:** Thin facade; reads `ConfigProperties` once via `ExtensionConfigReader`, constructs and caches 11 domain config objects
- **State / immutability:** Immutable after construction; fields `final`
- **Reads ConfigProperties:** Yes (constructor only)
- **Uses ExtensionPropertyNames:** Indirectly via domain configs / reader
- **Uses ExtensionDefaults:** Indirectly via domain configs / reader
- **Used by:** `PlatformAutoConfigurationCustomizer`, `PlatformSamplerProvider` (production); multiple tests (see §10)
- **Uses:** `ExtensionConfigReader`, all 11 `*ExtensionConfig` classes, `ConfigProperties`, Lombok
- **Test coverage:** `ExtensionConfigTest`, `ExtensionConfigFacadeVsFactoryParityCharacterizationTest`, factory adoption tests, `PlatformSamplerProviderTest`, ArchUnit G4
- **Package move risk:** **HIGH** — bootstrap anchor; ArchUnit restricts `new ExtensionConfig(ConfigProperties)` to 2 production classes; heavily imported
- **Notes:** Documented A3' thin facade; единственная авторизованная точка сборки domain configs (кроме SPI exception `PlatformSamplerProvider`)

### SamplingExtensionConfig

- **File:** `.../configuration/SamplingExtensionConfig.java`
- **Kind:** domain config
- **Visibility:** public final class; constructor package-private
- **Constructor visibility:** package-private `SamplingExtensionConfig(ExtensionConfigReader reader)`
- **Public API:** Lombok fluent getters: `enabled()`, `ratio()`, `forceRecordHeader()`, `qaHeader()`, `forceRecordValues()`, `dropPaths()`, `routeRatios()`
- **Package-private API:** constructor
- **Main responsibility:** Immutable startup sampling configuration
- **State / immutability:** Immutable `final` fields; lists/maps from reader are copied or default immutable (`List.copyOf`/`Map.copyOf` in reader)
- **Reads ConfigProperties:** No (via reader)
- **Uses ExtensionPropertyNames:** Yes (static import)
- **Uses ExtensionDefaults:** Yes (static import)
- **Used by:** `ExtensionConfig`, `PlatformSamplerFactory`, `PlatformSamplerBuilder`, `PlatformSamplerProvider` (via facade), tests
- **Uses:** `ExtensionConfigReader`, static imports of property names/defaults
- **Test coverage:** `ExtensionConfigTest`, parity characterization, sampler tests
- **Package move risk:** **HIGH** — package-private ctor tied to `ExtensionConfig`; cross-package production imports from `factory`/`sampler`
- **Notes:** PR-5 migrated sampler path to domain config

### ScrubbingExtensionConfig

- **File:** `.../configuration/ScrubbingExtensionConfig.java`
- **Kind:** domain config
- **Visibility:** public; package-private ctor
- **Constructor visibility:** package-private
- **Public API:** `enabled()`, `builtInRules()`, `hmacKey()`, `missingKeyPolicy()`, `hashKeyId()`, `rulesConfig()`, `rulesExtensions()`, `rulesValidationMode()`
- **Package-private API:** constructor
- **Main responsibility:** Immutable startup scrubbing configuration including nullable rule paths
- **State / immutability:** Immutable
- **Reads ConfigProperties:** No
- **Uses ExtensionPropertyNames:** Yes
- **Uses ExtensionDefaults:** Yes
- **Used by:** `ExtensionConfig`, `PlatformSpanProcessorFactory` (direct param + via facade), tests
- **Uses:** `ExtensionConfigReader`
- **Test coverage:** `ExtensionConfigTest`, scrubbing adoption tests, parity tests
- **Package move risk:** **HIGH** — direct cross-package import in `PlatformSpanProcessorFactory`; package-private ctor
- **Notes:** PR-4/PR-4B; `rulesConfig` nullable when property absent

### EnrichingExtensionConfig

- **File:** `.../configuration/EnrichingExtensionConfig.java`
- **Kind:** domain config
- **Visibility:** public; package-private ctor
- **Public API:** `enabled()`, `remoteServicePriority()`
- **Package-private API:** constructor
- **Main responsibility:** Startup enriching flags and remote service attribute priority list
- **State / immutability:** Immutable
- **Reads ConfigProperties:** No
- **Uses ExtensionPropertyNames:** Yes
- **Uses ExtensionDefaults:** Yes
- **Used by:** `ExtensionConfig`, `PlatformSpanProcessorFactory` (via `extConfig.enriching()`), tests
- **Uses:** `ExtensionConfigReader`
- **Test coverage:** `ExtensionConfigTest`, parity tests, `PlatformSpanProcessorFactoryExtensionConfigAdoptionTest`
- **Package move risk:** **MEDIUM** — only consumed via facade in production; package-private ctor constraint remains
- **Notes:** PR-3 adoption

### BaggageExtensionConfig

- **File:** `.../configuration/BaggageExtensionConfig.java`
- **Kind:** domain config
- **Visibility:** public; package-private ctor
- **Public API:** `enabled()`, `allowlistKeys()`, `denyPatterns()`
- **Package-private API:** constructor
- **Main responsibility:** Startup baggage propagation allow/deny configuration
- **State / immutability:** Immutable
- **Reads ConfigProperties:** No
- **Uses ExtensionPropertyNames:** Yes
- **Uses ExtensionDefaults:** Yes
- **Used by:** `ExtensionConfig`, `PlatformSpanProcessorFactory`, tests
- **Uses:** `ExtensionConfigReader`
- **Test coverage:** `ExtensionConfigTest`, adoption tests, parity tests
- **Package move risk:** **MEDIUM**
- **Notes:** Default `enabled=false` unlike most subsystems (`DEFAULT_BAGGAGE_ENABLED`)

### ClassificationExtensionConfig

- **File:** `.../configuration/ClassificationExtensionConfig.java`
- **Kind:** domain config
- **Visibility:** public; package-private ctor
- **Public API:** `enabled()`, `slowThreshold()`, `normalThreshold()`
- **Package-private API:** constructor
- **Main responsibility:** Startup latency classification thresholds
- **State / immutability:** Immutable (`Duration`)
- **Reads ConfigProperties:** No
- **Uses ExtensionPropertyNames:** Yes
- **Uses ExtensionDefaults:** Yes
- **Used by:** `ExtensionConfig`, `PlatformSpanProcessorFactory`, tests
- **Uses:** `ExtensionConfigReader`
- **Test coverage:** `ExtensionConfigTest`, adoption tests, parity tests
- **Package move risk:** **MEDIUM**

### WatchdogExtensionConfig

- **File:** `.../configuration/WatchdogExtensionConfig.java`
- **Kind:** domain config
- **Visibility:** public; package-private ctor
- **Public API:** `enabled()`, `spanTimeout()`, `traceTimeout()`, `scanInterval()`
- **Package-private API:** constructor
- **Main responsibility:** Startup watchdog timeouts
- **State / immutability:** Immutable
- **Reads ConfigProperties:** No
- **Uses ExtensionPropertyNames:** Yes
- **Uses ExtensionDefaults:** Yes
- **Used by:** `ExtensionConfig`, `PlatformSpanProcessorFactory`, tests
- **Uses:** `ExtensionConfigReader`
- **Test coverage:** `ExtensionConfigTest`, adoption tests, parity tests
- **Package move risk:** **MEDIUM**

### ValidationExtensionConfig

- **File:** `.../configuration/ValidationExtensionConfig.java`
- **Kind:** domain config
- **Visibility:** public; package-private ctor
- **Public API:** `enabled()`, `strict()`, `strictRuntimeAllowed()`
- **Package-private API:** constructor
- **Main responsibility:** Startup validation processor flags
- **State / immutability:** Immutable
- **Reads ConfigProperties:** No
- **Uses ExtensionPropertyNames:** Yes
- **Uses ExtensionDefaults:** Yes
- **Used by:** `ExtensionConfig`, `PlatformSpanProcessorFactory`, tests
- **Uses:** `ExtensionConfigReader`
- **Test coverage:** `ExtensionConfigTest`, adoption tests, parity tests
- **Package move risk:** **MEDIUM**

### MetricsExtensionConfig

- **File:** `.../configuration/MetricsExtensionConfig.java`
- **Kind:** domain config
- **Visibility:** public; package-private ctor
- **Public API:** `enabled()`
- **Package-private API:** constructor
- **Main responsibility:** Startup metrics span processor toggle
- **State / immutability:** Immutable
- **Reads ConfigProperties:** No
- **Uses ExtensionPropertyNames:** Yes
- **Uses ExtensionDefaults:** Yes
- **Used by:** `ExtensionConfig`, `PlatformSpanProcessorFactory`, tests
- **Uses:** `ExtensionConfigReader`
- **Test coverage:** `ExtensionConfigTest`, adoption tests, parity tests
- **Package move risk:** **MEDIUM**

### QueueExtensionConfig

- **File:** `.../configuration/QueueExtensionConfig.java`
- **Kind:** domain config
- **Visibility:** public; package-private ctor
- **Public API:** `overflowPolicy()`
- **Package-private API:** constructor
- **Main responsibility:** Startup queue overflow policy string (`DROP_OLDEST` / `UPSTREAM`)
- **State / immutability:** Immutable
- **Reads ConfigProperties:** No
- **Uses ExtensionPropertyNames:** Yes
- **Uses ExtensionDefaults:** Yes
- **Used by:** `ExtensionConfig`, `PlatformExportProcessorFactory`, tests
- **Uses:** `ExtensionConfigReader`
- **Test coverage:** `ExtensionConfigTest`, parity tests, export processor tests
- **Package move risk:** **HIGH** — direct cross-package import in `PlatformExportProcessorFactory`
- **Notes:** Interpreted with `ExtensionEnums.QueueOverflowPolicy` in factory

### ResourceExtensionConfig

- **File:** `.../configuration/ResourceExtensionConfig.java`
- **Kind:** domain config
- **Visibility:** public; package-private ctor
- **Public API:** `policyVersion()`, `normalizeEnvironment()`, `validationMode()`, `detectContainerId()`
- **Package-private API:** constructor
- **Main responsibility:** Startup resource policy snapshot in facade
- **State / immutability:** Immutable
- **Reads ConfigProperties:** No
- **Uses ExtensionPropertyNames:** Yes
- **Uses ExtensionDefaults:** Yes
- **Used by:** `ExtensionConfig` only in production; **resource SPI reads `ExtensionPropertyNames`/`ExtensionDefaults` directly, not this class**
- **Uses:** `ExtensionConfigReader`
- **Test coverage:** `ExtensionConfigTest`, parity tests
- **Package move risk:** **MEDIUM** — no direct production consumer outside facade; SPI exception pattern documented elsewhere
- **Notes:** `ResourceExtensionConfig` exists in facade but `PlatformResourceProvider` / `ResourceAttributeResolver` remain OTel SPI exception (direct `ConfigProperties` reads)

### SdkExtensionConfig

- **File:** `.../configuration/SdkExtensionConfig.java`
- **Kind:** domain config
- **Visibility:** public; package-private ctor
- **Public API:** `mode()` (nullable)
- **Package-private API:** constructor
- **Main responsibility:** Diagnostic `platform.tracing.sdk.mode` at agent bootstrap
- **State / immutability:** Immutable
- **Reads ConfigProperties:** No
- **Uses ExtensionPropertyNames:** Yes (`SDK_MODE` only; no defaults class import)
- **Uses ExtensionDefaults:** No
- **Used by:** `ExtensionConfig`, `PlatformAutoConfigurationCustomizer` (`sdk().mode()` log), tests
- **Uses:** `ExtensionConfigReader`
- **Test coverage:** `ExtensionConfigTest` (nullable passthrough)
- **Package move risk:** **MEDIUM**

### ExtensionConfigReader

- **File:** `.../configuration/ExtensionConfigReader.java`
- **Kind:** reader/helper
- **Visibility:** package-private final class
- **Constructor visibility:** package-private
- **Public API:** none
- **Package-private API:** `booleanValue`, `doubleValue`, `stringValue`, `nullableString`, `listValue`, `mapValue`, `durationValue`
- **Main responsibility:** Single typed adapter over `ConfigProperties` with default/nullable semantics
- **State / immutability:** Holds `ConfigProperties` ref; methods stateless
- **Reads ConfigProperties:** Yes
- **Uses ExtensionPropertyNames:** No (callers pass keys)
- **Uses ExtensionDefaults:** No (callers pass defaults)
- **Used by:** `ExtensionConfig`, all domain configs
- **Uses:** `ConfigProperties`
- **Test coverage:** Indirect via `ExtensionConfigTest` and parity tests; no dedicated unit test class found
- **Package move risk:** **HIGH** — must stay co-located with domain config ctors or become public API
- **Notes:** `@SuppressWarnings("SameParameterValue")` on `doubleValue`, `mapValue`

### ExtensionPropertyNames

- **File:** `.../configuration/ExtensionPropertyNames.java`
- **Kind:** property contract (`@UtilityClass`)
- **Visibility:** public final class; all constants public static final
- **Constructor visibility:** Lombok utility — no instances
- **Public API:** ~30 property key constants incl. `platform.tracing.*` and `OTEL_JAVAAGENT_EXTENSIONS`
- **Package-private API:** none
- **Main responsibility:** External-stable property name contract for agent extension
- **State / immutability:** Stateless
- **Reads ConfigProperties:** No
- **Uses ExtensionPropertyNames:** N/A
- **Uses ExtensionDefaults:** No
- **Used by:** All domain configs (static import), `resource.*`, `scrubbing.ScrubbingRulesLoader`, `PlatformTracingDefaultsProvider`, tests
- **Uses:** Lombok `@UtilityClass`
- **Test coverage:** Indirect via config/resource/scrubbing tests; no dedicated test class
- **Package move risk:** **HIGH** — widely imported; external contract surface
- **Notes:** Does **not** define `platform.tracing.service.*` keys (those live on `PlatformResourceProvider`)

### ExtensionDefaults

- **File:** `.../configuration/ExtensionDefaults.java`
- **Kind:** defaults (`@UtilityClass`)
- **Visibility:** public
- **Constructor visibility:** utility
- **Public API:** public static final default constants for all platform domains + `DEFAULT_ENABLED`
- **Package-private API:** none
- **Main responsibility:** Platform default values aligned with property contract
- **State / immutability:** Stateless; some defaults are immutable lists
- **Reads ConfigProperties:** No
- **Uses ExtensionPropertyNames:** No
- **Uses ExtensionDefaults:** N/A
- **Used by:** Domain configs, `PlatformTracingDefaultsProvider`, `resource.*`, `PlatformDropOldestExportSpanProcessor`, tests, parity tests
- **Uses:** `PlatformHeaders`, `BuiltInSpanAttributeScrubbingRules`
- **Test coverage:** Parity characterization, resource tests, scrubbing adoption tests
- **Package move risk:** **HIGH** — cross-package imports from `resource` and `processor`
- **Notes:** `DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT` used outside domain config surface

### ExtensionEnvironmentVariables

- **File:** `.../configuration/ExtensionEnvironmentVariables.java`
- **Kind:** env constants
- **Visibility:** package-private `@UtilityClass`
- **Constructor visibility:** utility (package-private class)
- **Public API:** none
- **Package-private API:** `SERVICE_NAME`, `SERVICE_VERSION`, `SERVICE_ENVIRONMENT`, `SERVICE_C_GROUP`, `RESOURCE_POLICY_VERSION`, `QUEUE_OVERFLOW_POLICY`, `OTEL_JAVAAGENT_EXTENSIONS`
- **Main responsibility:** Env var names for defaults supplier and path resolution
- **State / immutability:** Stateless
- **Reads ConfigProperties:** No
- **Used by:** `PlatformTracingDefaultsProvider`, `JavaAgentExtensionPaths`; same-package tests
- **Uses:** Lombok
- **Test coverage:** `PlatformTracingDefaultsProviderTest`, `PlatformTracingDefaultsProviderResourceEnvTest`, `JavaAgentExtensionPathsTest` (same-package access)
- **Package move risk:** **HIGH** if moved without widening visibility — tests rely on same-package access
- **Notes:** Intentionally package-private env layer

### ExtensionEnums

- **File:** `.../configuration/ExtensionEnums.java`
- **Kind:** enums holder
- **Visibility:** public final class; nested public enums
- **Constructor visibility:** private on holder; enum ctors private
- **Public API:** `QueueOverflowPolicy`, `ScrubbingMissingKeyPolicy`, `ScrubbingRulesValidationMode`, `SdkMode` with `value()` methods
- **Package-private API:** none
- **Main responsibility:** Canonical string values for policy/mode comparisons
- **State / immutability:** Stateless
- **Reads ConfigProperties:** No
- **Used by:** `PlatformExportProcessorFactory`, `PlatformSpanProcessorFactory`, `PlatformTracingDefaultsProvider` (test), tests
- **Uses:** none in-package
- **Test coverage:** `ExtensionEnumsTest`
- **Package move risk:** **MEDIUM** — factory imports; no package-private constraint
- **Notes:** Javadoc states parsing logic unchanged — string compare preserved in factories

### OtelSdkDefaults

- **File:** `.../configuration/OtelSdkDefaults.java`
- **Kind:** sdk-defaults
- **Visibility:** package-private `@UtilityClass`
- **Constructor visibility:** utility (package-private class)
- **Public API:** none
- **Package-private API:** package-private static final BSP and span limit constants
- **Main responsibility:** Internal OTel SDK numeric defaults not exposed as platform property contract
- **State / immutability:** Stateless
- **Reads ConfigProperties:** No
- **Used by:** `DropOldestExportProcessorDefaults`, `PlatformTracingDefaultsProvider`; same-package tests
- **Uses:** Lombok
- **Test coverage:** `DropOldestExportProcessorDefaultsTest`, `PlatformTracingDefaultsProviderTest` (same package)
- **Package move risk:** **HIGH** if visibility not widened — hidden implementation detail intentionally
- **Notes:** Javadoc in `ExtensionDefaults` references this split

### DropOldestExportProcessorDefaults

- **File:** `.../configuration/DropOldestExportProcessorDefaults.java`
- **Kind:** public BSP defaults facade
- **Visibility:** public `@UtilityClass`
- **Public API:** `defaultMaxQueueSize()`, `defaultMaxExportBatchSize()`, `defaultScheduleDelay()`, `defaultExportTimeout()`
- **Main responsibility:** Public access to BSP defaults without exposing `OtelSdkDefaults`
- **State / immutability:** Stateless
- **Reads ConfigProperties:** No
- **Used by:** `PlatformDropOldestExportSpanProcessor`, `SharedDefaultsAlignmentTest` (spring autoconfigure module), tests
- **Uses:** `OtelSdkDefaults`
- **Test coverage:** `DropOldestExportProcessorDefaultsTest`, `PlatformDropOldestExportSpanProcessorBuilderValidationTest`, cross-module alignment test
- **Package move risk:** **HIGH** — cross-module test dependency on public API
- **Notes:** Dual-channel alignment contract (ADR-dual-channel-properties)

### PlatformTracingDefaultsProvider

- **File:** `.../configuration/PlatformTracingDefaultsProvider.java`
- **Kind:** SPI defaults supplier
- **Visibility:** public final class
- **Constructor visibility:** public (default env) and public `(EnvSupplier)`
- **Public API:** `supply()`, nested public `EnvSupplier` interface
- **Main responsibility:** Provides property defaults map for OTel agent `addPropertiesSupplier` bootstrap
- **State / immutability:** Holds `EnvSupplier`; `supply()` creates new map each call
- **Reads ConfigProperties:** No (reads env via supplier)
- **Uses ExtensionPropertyNames:** Yes
- **Uses ExtensionDefaults:** Yes
- **Used by:** `PlatformAutoConfigurationCustomizer`, `OtelEffectiveConfigSnapshotDefaultsContractTest` (spring module)
- **Uses:** `OtelSdkDefaults`, `ExtensionEnvironmentVariables`, `PlatformResourceProvider.PROP_*` constants
- **Test coverage:** `PlatformTracingDefaultsProviderTest`, `PlatformTracingDefaultsProviderResourceEnvTest`, spike reference in `BspReplacementSpikeTest`
- **Package move risk:** **HIGH** — bootstrap SPI integration + cross-module contract test
- **Notes:** Maps env vars to `platform.tracing.service.*` property keys via `PlatformResourceProvider` constants

### JavaAgentExtensionPaths

- **File:** `.../configuration/JavaAgentExtensionPaths.java`
- **Kind:** path helper
- **Visibility:** public `@UtilityClass`
- **Public API:** `resolveRawValue(ConfigProperties config)`
- **Main responsibility:** Resolves raw `otel.javaagent.extensions` from ConfigProperties → system property → env
- **State / immutability:** Stateless
- **Reads ConfigProperties:** Yes (method parameter)
- **Uses ExtensionPropertyNames:** Yes (`OTEL_JAVAAGENT_EXTENSIONS`)
- **Uses ExtensionDefaults:** No
- **Used by:** `PlatformSpanProcessorFactory` (scrubbing rules classpath scan), tests
- **Uses:** `ExtensionEnvironmentVariables`
- **Test coverage:** `JavaAgentExtensionPathsTest`
- **Package move risk:** **MEDIUM** — depends on package-private `ExtensionEnvironmentVariables` unless widened
- **Notes:** OTel SPI path; retained `ConfigProperties` in span processor factory per PR-4 comments

### AutoConfigurationCustomizerOrdering

- **File:** `.../configuration/AutoConfigurationCustomizerOrdering.java`
- **Kind:** ordering constant
- **Visibility:** public `@UtilityClass`
- **Public API:** `PLATFORM_EXTENSION_ORDER` (= 100)
- **Main responsibility:** Stable OTel SPI ordering offset after stock providers
- **State / immutability:** Stateless
- **Reads ConfigProperties:** No
- **Used by:** `PlatformAutoConfigurationCustomizer.order()`
- **Uses:** none
- **Test coverage:** Indirect via customizer tests; no dedicated unit test
- **Package move risk:** **LOW** — single consumer, public constant
- **Notes:** Small, isolated

---

## 5. Current Dependency Map

### In-package dependency graph

```text
ExtensionConfig
  -> ExtensionConfigReader
  -> SamplingExtensionConfig
  -> EnrichingExtensionConfig
  -> ScrubbingExtensionConfig
  -> MetricsExtensionConfig
  -> ValidationExtensionConfig
  -> ResourceExtensionConfig
  -> ClassificationExtensionConfig
  -> WatchdogExtensionConfig
  -> QueueExtensionConfig
  -> BaggageExtensionConfig
  -> SdkExtensionConfig

SamplingExtensionConfig -> ExtensionConfigReader, ExtensionPropertyNames, ExtensionDefaults
EnrichingExtensionConfig -> ExtensionConfigReader, ExtensionPropertyNames, ExtensionDefaults
ScrubbingExtensionConfig -> ExtensionConfigReader, ExtensionPropertyNames, ExtensionDefaults
MetricsExtensionConfig -> ExtensionConfigReader, ExtensionPropertyNames, ExtensionDefaults
ValidationExtensionConfig -> ExtensionConfigReader, ExtensionPropertyNames, ExtensionDefaults
ResourceExtensionConfig -> ExtensionConfigReader, ExtensionPropertyNames, ExtensionDefaults
ClassificationExtensionConfig -> ExtensionConfigReader, ExtensionPropertyNames, ExtensionDefaults
WatchdogExtensionConfig -> ExtensionConfigReader, ExtensionPropertyNames, ExtensionDefaults
QueueExtensionConfig -> ExtensionConfigReader, ExtensionPropertyNames, ExtensionDefaults
BaggageExtensionConfig -> ExtensionConfigReader, ExtensionPropertyNames, ExtensionDefaults
SdkExtensionConfig -> ExtensionConfigReader, ExtensionPropertyNames

ExtensionConfigReader -> ConfigProperties

DropOldestExportProcessorDefaults -> OtelSdkDefaults

PlatformTracingDefaultsProvider
  -> OtelSdkDefaults
  -> ExtensionPropertyNames
  -> ExtensionDefaults
  -> ExtensionEnvironmentVariables
  -> PlatformResourceProvider (external: PROP_* constants only)

JavaAgentExtensionPaths
  -> ExtensionPropertyNames
  -> ExtensionEnvironmentVariables
  -> ConfigProperties

ExtensionDefaults -> PlatformHeaders, BuiltInSpanAttributeScrubbingRules (external packages)
ExtensionPropertyNames -> (no in-package deps)
ExtensionEnums -> (no in-package deps)
AutoConfigurationCustomizerOrdering -> (no deps)
ExtensionEnvironmentVariables -> (no deps)
OtelSdkDefaults -> (no deps)
```

### External edges (production)

```text
PlatformAutoConfigurationCustomizer
  -> ExtensionConfig (construct once)
  -> PlatformTracingDefaultsProvider (addPropertiesSupplier)
  -> AutoConfigurationCustomizerOrdering
  -> passes ExtensionConfig.sampling() -> PlatformSamplerFactory
  -> passes ExtensionConfig -> PlatformSpanProcessorFactory
  -> passes ExtensionConfig.queue() -> PlatformExportProcessorFactory
  -> reads ExtensionConfig.sdk().mode() (diagnostic log)

PlatformSamplerProvider (OTel SPI exception)
  -> new ExtensionConfig(config).sampling() -> PlatformSamplerBuilder

PlatformSamplerFactory -> SamplingExtensionConfig
PlatformSamplerBuilder -> SamplingExtensionConfig

PlatformSpanProcessorFactory
  -> ExtensionConfig (baggage/enriching/validation/classification/watchdog/metrics domains)
  -> ScrubbingExtensionConfig (direct param in collectScrubbingRules)
  -> ExtensionEnums (policy/mode string compare)
  -> JavaAgentExtensionPaths + ConfigProperties (scrubbing classpath SPI path)

PlatformExportProcessorFactory
  -> QueueExtensionConfig
  -> ExtensionEnums

ScrubbingRulesLoader -> ExtensionPropertyNames.SCRUBBING_RULES_CONFIG + ConfigProperties

PlatformDropOldestExportSpanProcessor
  -> DropOldestExportProcessorDefaults
  -> ExtensionDefaults.DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT

PlatformResourceProvider / ResourceAttributeResolver / SafeResourceProvider / ResourceStartupDiagnostics
  -> ExtensionPropertyNames, ExtensionDefaults (NOT ResourceExtensionConfig)

(resource package = documented OTel SPI exception for direct ConfigProperties reads)
```

### External edges (tests + cross-module)

```text
platform-tracing-spring-boot-autoconfigure (test only)
  -> DropOldestExportProcessorDefaults (SharedDefaultsAlignmentTest)
  -> PlatformTracingDefaultsProvider (OtelEffectiveConfigSnapshotDefaultsContractTest)

configuration same-package tests
  -> package-private OtelSdkDefaults, ExtensionEnvironmentVariables
```

---

## 6. Package-Private Constraints

| Element | Declaring Class | Visibility | Used By | Move Impact |
|---------|-----------------|------------|---------|-------------|
| Class `ExtensionConfigReader` | `ExtensionConfigReader` | package-private | `ExtensionConfig`, all 11 domain configs | **HIGH** — moving to subpackage breaks construction unless public or same package retained |
| Constructor `ExtensionConfigReader(ConfigProperties)` | `ExtensionConfigReader` | package-private | `ExtensionConfig` | **HIGH** |
| Methods `booleanValue`, `doubleValue`, `stringValue`, `nullableString`, `listValue`, `mapValue`, `durationValue` | `ExtensionConfigReader` | package-private | All domain configs | **HIGH** |
| Constructor `SamplingExtensionConfig(ExtensionConfigReader)` | `SamplingExtensionConfig` | package-private | `ExtensionConfig` only | **HIGH** — widening required if class moves out |
| Constructor `ScrubbingExtensionConfig(ExtensionConfigReader)` | `ScrubbingExtensionConfig` | package-private | `ExtensionConfig` | **HIGH** |
| Constructor `EnrichingExtensionConfig(ExtensionConfigReader)` | `EnrichingExtensionConfig` | package-private | `ExtensionConfig` | **HIGH** |
| Constructor `BaggageExtensionConfig(ExtensionConfigReader)` | `BaggageExtensionConfig` | package-private | `ExtensionConfig` | **HIGH** |
| Constructor `ClassificationExtensionConfig(ExtensionConfigReader)` | `ClassificationExtensionConfig` | package-private | `ExtensionConfig` | **HIGH** |
| Constructor `WatchdogExtensionConfig(ExtensionConfigReader)` | `WatchdogExtensionConfig` | package-private | `ExtensionConfig` | **HIGH** |
| Constructor `ValidationExtensionConfig(ExtensionConfigReader)` | `ValidationExtensionConfig` | package-private | `ExtensionConfig` | **HIGH** |
| Constructor `MetricsExtensionConfig(ExtensionConfigReader)` | `MetricsExtensionConfig` | package-private | `ExtensionConfig` | **HIGH** |
| Constructor `QueueExtensionConfig(ExtensionConfigReader)` | `QueueExtensionConfig` | package-private | `ExtensionConfig` | **HIGH** |
| Constructor `ResourceExtensionConfig(ExtensionConfigReader)` | `ResourceExtensionConfig` | package-private | `ExtensionConfig` | **HIGH** |
| Constructor `SdkExtensionConfig(ExtensionConfigReader)` | `SdkExtensionConfig` | package-private | `ExtensionConfig` | **HIGH** |
| Class `ExtensionEnvironmentVariables` | `ExtensionEnvironmentVariables` | package-private | `PlatformTracingDefaultsProvider`, `JavaAgentExtensionPaths`, same-package tests | **HIGH** — tests use same-package access explicitly |
| All fields of `ExtensionEnvironmentVariables` | `ExtensionEnvironmentVariables` | package-private static | See above | **HIGH** if class stays hidden |
| Class `OtelSdkDefaults` | `OtelSdkDefaults` | package-private | `DropOldestExportProcessorDefaults`, `PlatformTracingDefaultsProvider`, same-package tests | **HIGH** — intentional encapsulation of OTel SDK literals |
| All constants in `OtelSdkDefaults` | `OtelSdkDefaults` | package-private static | See above | **MEDIUM** within package |

**Same-package test pattern (evidence):**

- `DropOldestExportProcessorDefaultsTest.java` — comment: `// Same package — can access package-private OtelSdkDefaults`
- `JavaAgentExtensionPathsTest.java` — comment: `// Same package — can access package-private ExtensionEnvironmentVariables`

---

## 7. Public API Surface

### ExtensionConfig

| Member | Classification |
|--------|----------------|
| `ExtensionConfig(ConfigProperties)` | internal but public for bootstrap + SPI exception + tests |
| `sampling()` … `sdk()` (Lombok fluent getters) | internal but public for cross-package factory usage |

### Domain configs (`*ExtensionConfig`)

| Pattern | Classification |
|---------|----------------|
| Lombok `@Getter` `@Accessors(fluent = true)` generated methods | internal but public for cross-package production usage (where imported) |
| No public constructors | enforced encapsulation — only `ExtensionConfig` constructs |

**Direct cross-package domain type imports (production evidence):**

- `SamplingExtensionConfig` — `PlatformSamplerFactory`, `PlatformSamplerBuilder`
- `ScrubbingExtensionConfig` — `PlatformSpanProcessorFactory`
- `QueueExtensionConfig` — `PlatformExportProcessorFactory`

All other domains accessed only via `ExtensionConfig` facade in production.

### ExtensionPropertyNames

| Member group | Classification |
|--------------|----------------|
| All `public static final String` constants | **external contract** (property keys stable across versions per class Javadoc) |

### ExtensionDefaults

| Member group | Classification |
|--------------|----------------|
| All `public static final` defaults | **external contract** (platform defaults; referenced from resource SPI) |

### ExtensionEnums

| Nested enums + `value()` | internal but public for factory string matching |

### PlatformTracingDefaultsProvider

| `supply()`, `EnvSupplier`, constructors | SPI-related bootstrap API; cross-module test contract |

### DropOldestExportProcessorDefaults

| four `default*()` methods | **external contract** for dual-channel BSP alignment |

### JavaAgentExtensionPaths

| `resolveRawValue(ConfigProperties)` | internal but public; OTel SPI-related path resolution |

### AutoConfigurationCustomizerOrdering

| `PLATFORM_EXTENSION_ORDER` | SPI-related ordering constant |

### Classes with no public API outside package

| Class | Classification |
|-------|----------------|
| `ExtensionConfigReader` | internal implementation (package-private) |
| `ExtensionEnvironmentVariables` | internal implementation (package-private) |
| `OtelSdkDefaults` | internal implementation (package-private) |

---

## 8. Property Contract Surface

| Class | Constant / Method Group | Property Prefix / Keys | Used By | Move Risk |
|-------|-------------------------|------------------------|---------|-----------|
| `ExtensionPropertyNames` | `SAMPLING_*` (7 keys) | `platform.tracing.sampling.*` | Domain config, parity tests | HIGH |
| `ExtensionPropertyNames` | `ENRICHING_*` | `platform.tracing.enriching.*` | Domain config, factories via facade | HIGH |
| `ExtensionPropertyNames` | `SCRUBBING_*` (8 keys) | `platform.tracing.scrubbing.*` | Domain config, `ScrubbingRulesLoader`, span processor factory | HIGH |
| `ExtensionPropertyNames` | `METRICS_ENABLED` | `platform.tracing.metrics.*` | Domain config | MEDIUM |
| `ExtensionPropertyNames` | `VALIDATION_*` | `platform.tracing.validation.*` | Domain config | MEDIUM |
| `ExtensionPropertyNames` | `RESOURCE_*` (4 config keys + `RESOURCE_POLICY_VERSION_ATTR`) | `platform.tracing.resource.*`, attr `platform.tracing.policy.version` | Domain config, `resource.*` SPI, defaults provider | HIGH |
| `ExtensionPropertyNames` | `CLASSIFICATION_*` | `platform.tracing.classification.*` | Domain config | MEDIUM |
| `ExtensionPropertyNames` | `WATCHDOG_*` | `platform.tracing.watchdog.*` | Domain config | MEDIUM |
| `ExtensionPropertyNames` | `QUEUE_OVERFLOW_POLICY` | `platform.tracing.queue.*` | Domain config, defaults provider | HIGH |
| `ExtensionPropertyNames` | `BAGGAGE_*` | `platform.tracing.baggage.*` | Domain config | MEDIUM |
| `ExtensionPropertyNames` | `SDK_MODE` | `platform.tracing.sdk.mode` | Domain config, bootstrap log | MEDIUM |
| `ExtensionPropertyNames` | `OTEL_JAVAAGENT_EXTENSIONS` | `otel.javaagent.extensions` | `JavaAgentExtensionPaths`, span processor factory | HIGH |
| `ExtensionDefaults` | sampling defaults | aligns with `SAMPLING_*` | Domain config, parity tests | HIGH |
| `ExtensionDefaults` | scrubbing defaults | aligns with `SCRUBBING_*` | Domain config, span processor tests | HIGH |
| `ExtensionDefaults` | resource defaults | aligns with `RESOURCE_*` | Domain config, `ResourceAttributeResolver` | HIGH |
| `ExtensionDefaults` | queue / baggage / classification / watchdog / validation | respective prefixes | Domain configs, parity tests | MEDIUM–HIGH |
| `ExtensionDefaults` | `DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT` | not a ConfigProperties key in `ExtensionPropertyNames` | `PlatformDropOldestExportSpanProcessor` | MEDIUM |
| `ExtensionDefaults` | `DEFAULT_ENABLED` | generic `*.enabled` | Most domain configs | HIGH |
| `ExtensionEnvironmentVariables` | `PLATFORM_TRACING_*`, `OTEL_JAVAAGENT_EXTENSIONS` | maps to property keys in defaults provider / paths | `PlatformTracingDefaultsProvider`, `JavaAgentExtensionPaths` | HIGH (package-private) |
| `PlatformTracingDefaultsProvider` | `supply()` map entries | `otel.bsp.*`, `otel.span.*`, `platform.tracing.queue.overflow-policy`, `platform.tracing.service.*` via env fallback, `platform.tracing.resource.policy-version` | Bootstrap customizer, actuator contract test | HIGH |
| `OtelSdkDefaults` | BSP + span limit ints/durations | standard OTel SDK keys (not platform.tracing) | `PlatformTracingDefaultsProvider`, `DropOldestExportProcessorDefaults` | HIGH (package-private) |
| `DropOldestExportProcessorDefaults` | `defaultMaxQueueSize()` etc. | `otel.bsp.*` semantics | Processor builder, spring alignment test | HIGH |
| `JavaAgentExtensionPaths` | `resolveRawValue` | `otel.javaagent.extensions` | Span processor factory scrubbing classpath | MEDIUM |
| `AutoConfigurationCustomizerOrdering` | `PLATFORM_EXTENSION_ORDER` | n/a (SPI ordering) | `PlatformAutoConfigurationCustomizer` | LOW |
| `ExtensionEnums` | enum `value()` strings | policy values for queue/scrubbing/sdk | Export/span processor factories | MEDIUM |

### `platform.tracing.service.*` location

**NOT in `configuration` package.**

Evidence: `PlatformResourceProvider` defines:

```text
platform.tracing.service.name
platform.tracing.service.version
platform.tracing.service.environment
platform.tracing.service.c-group
platform.tracing.service.id
platform.tracing.service.host
platform.tracing.service.container-id
```

`PlatformTracingDefaultsProvider` maps env vars (`ExtensionEnvironmentVariables.SERVICE_*`) to these keys via `PlatformResourceProvider.PROP_*` constants — not via `ExtensionPropertyNames`.

---

## 9. Domain Config Surface

| Domain Config | Properties Represented | Production Consumers | Runtime Mutable? | Notes |
|---------------|------------------------|----------------------|:----------------:|-------|
| `SamplingExtensionConfig` | `platform.tracing.sampling.*` (7 keys) | `PlatformSamplerFactory`, `PlatformSamplerBuilder`; bootstrap via `PlatformAutoConfigurationCustomizer`; SPI via `PlatformSamplerProvider` | startup seed for runtime holder | JMX/runtime updates via `SamplerStateHolder` / `PlatformTracingControl` (outside this package) |
| `ScrubbingExtensionConfig` | `platform.tracing.scrubbing.*` (8 keys) | `PlatformSpanProcessorFactory` | startup seed for runtime holder | Factory still uses `ConfigProperties` + `JavaAgentExtensionPaths` for rules classpath (SPI path) |
| `EnrichingExtensionConfig` | `platform.tracing.enriching.*` | `PlatformSpanProcessorFactory` via facade | startup-only | Processor registered at bootstrap |
| `BaggageExtensionConfig` | `platform.tracing.baggage.*` | `PlatformSpanProcessorFactory` via facade | startup-only | Default disabled |
| `ClassificationExtensionConfig` | `platform.tracing.classification.*` | `PlatformSpanProcessorFactory` via facade | startup-only | Thresholds fixed at processor creation |
| `WatchdogExtensionConfig` | `platform.tracing.watchdog.*` | `PlatformSpanProcessorFactory` via facade | startup-only | |
| `ValidationExtensionConfig` | `platform.tracing.validation.*` | `PlatformSpanProcessorFactory` via facade | startup seed for runtime holder | Runtime policy via JMX holders |
| `MetricsExtensionConfig` | `platform.tracing.metrics.enabled` | `PlatformSpanProcessorFactory` via facade | startup-only | |
| `QueueExtensionConfig` | `platform.tracing.queue.overflow-policy` | `PlatformExportProcessorFactory` | startup-only | Export processor choice at bootstrap |
| `ResourceExtensionConfig` | `platform.tracing.resource.*` (4 keys) | **None direct** — only `ExtensionConfig.resource()` in tests | startup-only in facade | **OTel SPI exception:** `PlatformResourceProvider` reads `ConfigProperties` + `ExtensionPropertyNames`/`ExtensionDefaults` directly |
| `SdkExtensionConfig` | `platform.tracing.sdk.mode` | `PlatformAutoConfigurationCustomizer` (diagnostic log) | startup-only | Nullable; no `ExtensionDefaults` entry |

---

## 10. External Consumer Matrix

| Consumer Class | Consumed Configuration Class(es) | Purpose | Import Type | Package Move Impact |
|----------------|----------------------------------|---------|-------------|---------------------|
| `PlatformAutoConfigurationCustomizer` | `ExtensionConfig`, `PlatformTracingDefaultsProvider`, `AutoConfigurationCustomizerOrdering` | Bootstrap: defaults supplier, single ExtensionConfig, SPI ordering | explicit import | HIGH |
| `PlatformSamplerProvider` | `ExtensionConfig` | OTel SPI exception: build sampler from `SamplingExtensionConfig` | explicit import | HIGH (ArchUnit G4) |
| `PlatformSamplerFactory` | `SamplingExtensionConfig` | Build composite sampler from startup config | explicit import | HIGH |
| `PlatformSamplerBuilder` | `SamplingExtensionConfig` | Parse route ratios / policy from domain config | explicit import | HIGH |
| `PlatformSpanProcessorFactory` | `ExtensionConfig`, `ScrubbingExtensionConfig`, `ExtensionEnums`, `JavaAgentExtensionPaths` | Register span processors; scrubbing rules collection | explicit import | HIGH |
| `PlatformExportProcessorFactory` | `QueueExtensionConfig`, `ExtensionEnums` | DROP_OLDEST vs UPSTREAM export processor | explicit import | HIGH |
| `ScrubbingRulesLoader` | `ExtensionPropertyNames` | Load rules from `rules-config` path | explicit import | MEDIUM |
| `PlatformDropOldestExportSpanProcessor` | `DropOldestExportProcessorDefaults`, `ExtensionDefaults` | BSP builder defaults + shutdown timeout | explicit import | HIGH |
| `PlatformResourceProvider` | `ExtensionPropertyNames`, `ExtensionDefaults` | Resource SPI attribute resolution | explicit import | HIGH |
| `ResourceAttributeResolver` | `ExtensionPropertyNames`, `ExtensionDefaults` | Per-key resource attribute logic | explicit import | HIGH |
| `ResourceStartupDiagnostics` | `ExtensionPropertyNames` | Diagnostics logging | explicit import | MEDIUM |
| `SafeResourceProvider` | `ExtensionPropertyNames` | Strict mode property read | explicit import | MEDIUM |
| `ExtensionConfigTest` | all via `ExtensionConfig` | Unit tests facade + domains | same package | MEDIUM (same-package) |
| `ExtensionConfigFacadeVsFactoryParityCharacterizationTest` | `ExtensionConfig`, `ExtensionPropertyNames`, `ExtensionDefaults` | Parity vs factory defaults | same package | MEDIUM |
| `PlatformSpanProcessorFactoryExtensionConfigAdoptionTest` | `ExtensionConfig` | Factory adoption regression | explicit import | LOW (test) |
| `PlatformSpanProcessorFactoryScrubbingAdoptionTest` | `ExtensionConfig`, `ExtensionDefaults` | Scrubbing adoption regression | explicit import | LOW (test) |
| `PlatformSamplerProviderTest` | `ExtensionConfig`, `SamplingExtensionConfig` | SPI sampler provider test | explicit import | LOW (test) |
| `ExtensionConfigBootstrapGuardrailsArchTest` | `ExtensionConfig` | ArchUnit G3/G4 | explicit import | HIGH (rule paths) |
| `ExtensionNoSpringDependencyArchTest` | configuration package (ArchRule scope) | No Spring; no ConfigProperties in domain configs | ArchRule on package | HIGH |
| `DropOldestExportProcessorDefaultsTest` | `OtelSdkDefaults`, `DropOldestExportProcessorDefaults` | Defaults alignment | same package | HIGH if `OtelSdkDefaults` moved |
| `JavaAgentExtensionPathsTest` | `ExtensionEnvironmentVariables`, `JavaAgentExtensionPaths` | Path resolution | same package | HIGH |
| `PlatformTracingDefaultsProviderTest` | `PlatformTracingDefaultsProvider`, `OtelSdkDefaults`, `ExtensionEnums`, `ExtensionEnvironmentVariables` | Defaults map contract | same package | HIGH |
| `PlatformTracingDefaultsProviderResourceEnvTest` | `ExtensionEnvironmentVariables` | Env → property mapping | same package | HIGH |
| `ExtensionEnumsTest` | `ExtensionEnums` | Enum value contract | same package | LOW |
| `PlatformDropOldestExportSpanProcessorBuilderValidationTest` | `DropOldestExportProcessorDefaults` | Builder validation | explicit import | MEDIUM |
| `PlatformResourceProviderTest` | `ExtensionPropertyNames`, `ExtensionDefaults` | Resource provider tests | explicit import | MEDIUM |
| `ResourceAttributeResolverTest` | `ExtensionPropertyNames`, `ExtensionDefaults` | Resolver tests | explicit import | MEDIUM |
| `SharedDefaultsAlignmentTest` (spring-boot-autoconfigure) | `DropOldestExportProcessorDefaults` | Cross-module BSP defaults alignment | explicit import | **HIGH cross-module** |
| `OtelEffectiveConfigSnapshotDefaultsContractTest` (spring-boot-autoconfigure) | `PlatformTracingDefaultsProvider` | Actuator snapshot vs extension defaults | explicit import | **HIGH cross-module** |

**Production imports in `platform-tracing-spring-boot-autoconfigure/src/main/java`:** `NOT FOUND IN REPOSITORY` (zero explicit imports of configuration package).

---

## 11. Existing Architecture Guardrails

| Guardrail | Test Class | What It Enforces | Affected Classes | Move Impact |
|-----------|------------|------------------|------------------|-------------|
| No Spring in otel-extension | `ExtensionNoSpringDependencyArchTest` | Production classes in `space.br1440.platform.tracing.otel.javaagent` must not depend on `org.springframework..` | Entire extension module incl. all configuration classes | LOW for package move itself; unchanged if deps unchanged |
| Domain configs no direct `ConfigProperties` | `ExtensionNoSpringDependencyArchTest.domain_configs_do_not_depend_on_ConfigProperties` | `*ExtensionConfig` in `..configuration..` except `ExtensionConfig` must not depend on `ConfigProperties` | All 11 domain configs | **HIGH** — rule uses `.resideInAPackage("...configuration")` + name suffix; subpackage move requires test update |
| Allowed `ExtensionConfig` construction sites | `ExtensionConfigBootstrapGuardrailsArchTest.только_разрешённые_классы_строят_ExtensionConfig` | Only `PlatformAutoConfigurationCustomizer` and `PlatformSamplerProvider` may call `new ExtensionConfig(ConfigProperties)` in production | `ExtensionConfig` | MEDIUM — class name based, not package based |
| No `*PropertyKey` classes | `ExtensionConfigBootstrapGuardrailsArchTest.нет_ProductionKey_классов_в_otel_extension` | No descriptor registry pattern | whole extension | LOW |
| No `*PropertyRegistry` classes | `ExtensionConfigBootstrapGuardrailsArchTest.нет_PropertyRegistry_классов_в_otel_extension` | No registry framework | whole extension | LOW |
| OTel SPI implementation rules | `OtelDirectIntegrationArchTest` | Processors/providers implement OTel SPI interfaces | bootstrap classes consuming config indirectly | LOW |
| Safe boundary (no std streams) | `SafeBoundaryArchTest` | No System.out/err in processor/sampler packages | factories consuming config | LOW |
| Migrated factories no direct property reads | Partially enforced by adoption/parity tests | `ExtensionConfigFacadeVsFactoryParityCharacterizationTest`, factory adoption tests | factories vs facade defaults | MEDIUM — documentation/tests, not ArchUnit for every property |

---

## 12. Move Risk Analysis

### 12.1 Low-risk move candidates

| Class | Why |
|-------|-----|
| `AutoConfigurationCustomizerOrdering` | Single consumer; no package-private deps; small constant |
| `ExtensionEnums` | Public; no package-private coupling; moderate import count but self-contained |

### 12.2 Medium-risk move candidates

| Class | Why |
|-------|-----|
| `JavaAgentExtensionPaths` | Public API but depends on package-private `ExtensionEnvironmentVariables` |
| `EnrichingExtensionConfig`, `BaggageExtensionConfig`, `ClassificationExtensionConfig`, `WatchdogExtensionConfig`, `ValidationExtensionConfig`, `MetricsExtensionConfig`, `SdkExtensionConfig` | Production access mostly via facade; still blocked by package-private ctors + reader coupling |
| `ResourceExtensionConfig` | No direct production consumer outside facade; SPI exception elsewhere reduces coupling |
| `ExtensionEnums` (if grouped with factories) | Factory imports would need update |

### 12.3 High-risk move candidates

| Class | Why |
|-------|-----|
| `ExtensionConfig` | Bootstrap anchor; ArchUnit G4; many dependents |
| `ExtensionConfigReader` | Package-private hub; all domain configs depend on it |
| All domain configs with package-private ctors | Move without facade co-move forces public ctors or factory in same package |
| `ExtensionPropertyNames`, `ExtensionDefaults` | External contract; imported from `resource` and tests across modules |
| `PlatformTracingDefaultsProvider` | Bootstrap SPI; cross-module actuator contract test |
| `DropOldestExportProcessorDefaults` | Cross-module `SharedDefaultsAlignmentTest` |
| `OtelSdkDefaults`, `ExtensionEnvironmentVariables` | Package-private; same-package tests; hidden from public API by design |
| `SamplingExtensionConfig`, `ScrubbingExtensionConfig`, `QueueExtensionConfig` | Direct cross-package production imports |

### 12.4 Classes likely to remain in root package

Evidence-based candidates for staying at `...configuration` root (not a decision):

| Class | Rationale |
|-------|-----------|
| `ExtensionConfig` | Facade entry point; bootstrap owner wiring |
| `ExtensionPropertyNames` | Property contract anchor heavily imported |
| `ExtensionDefaults` | Defaults contract anchor; resource SPI dependency |
| `ExtensionConfigReader` | Must share package with domain ctors unless visibility changes |
| `PlatformTracingDefaultsProvider` | Bootstrap defaults supplier tied to customizer |
| `AutoConfigurationCustomizerOrdering` | SPI ordering tied to customizer |
| Domain configs (if any subpackaging) | Package-private ctor pattern strongly favors same package as `ExtensionConfig` + reader |

---

## 13. Possible Grouping Axes for Future Scoring

Neutral axes only — no scoring:

```text
- by domain: sampling / scrubbing / resource / queue / sdk / baggage / enriching / classification / watchdog / validation / metrics
- by role: facade / domain-config / property-contract / defaults / env / enums / reader / otel-sdk-defaults / spi-defaults-supplier / path-helper / ordering
- by lifecycle: startup-only / startup-seed-for-runtime-holder / OTel-SPI-exception-path / diagnostic-only
- by stability: external property contract / cross-module test contract / internal package-private / public factory-facing DTO
- by consumers: bootstrap-customizer-facing / sampler-factory-facing / span-processor-factory-facing / export-processor-factory-facing / resource-SPI-facing (property names only) / spring-actuator-test-facing
- by property prefix: platform.tracing.sampling.* vs resource.* vs queue.* vs otel.bsp.* (via defaults provider)
- by ConfigProperties touchpoint: reads-in-facade-only vs reads-in-helper-method vs no-ConfigProperties (domain DTO)
- by visibility coupling: package-private anchor cluster (reader + env + otel sdk defaults + domain ctors) vs fully public utilities
- by test placement: same-package tests accessing package-private vs cross-package explicit imports
- by ArchUnit sensitivity: classes referenced in resideInAPackage rules vs name-based constructor rules
```

---

## 14. Questions for Perplexity Scoring Pass

1. Должны ли все 11 классов `*ExtensionConfig` переехать в подпакет `configuration.domain`, если `ExtensionConfig` и `ExtensionConfigReader` остаются в root?
2. Должен ли `ExtensionConfigReader` переехать в `configuration.internal` с сохранением package-private доступа — или это невозможно без widening visibility?
3. Должны ли `ExtensionPropertyNames` и `ExtensionDefaults` остаться в root пакета как единый property contract, даже при domain subpackaging?
4. Как сохранить package-private конструкторы domain configs при любом subpackaging — один internal package для facade+reader+domains?
5. Должен ли `ResourceExtensionConfig` переезжать вместе с resource-доменом, учитывая что `PlatformResourceProvider` — OTel SPI exception и не использует этот DTO?
6. Следует ли `PlatformTracingDefaultsProvider` группировать с `OtelSdkDefaults` / `DropOldestExportProcessorDefaults` в `configuration.spi` или `configuration.defaults`?
7. Должен ли package-private `ExtensionEnvironmentVariables` стать public при subpackaging, или остаться в root/internal пакете рядом с `PlatformTracingDefaultsProvider`?
8. Как subpackaging повлияет на ArchUnit правило `domain_configs_do_not_depend_on_ConfigProperties` с hard-coded `resideInAPackage("...configuration")`?
9. Нужно ли обновлять cross-module тесты (`SharedDefaultsAlignmentTest`, `OtelEffectiveConfigSnapshotDefaultsContractTest`) при смене пакета public defaults API?
10. Должны ли same-package tests (`DropOldestExportProcessorDefaultsTest`, `JavaAgentExtensionPathsTest`) переехать в mirror subpackages или остаться co-located с package-private типами?
11. Какие moves потребуют widening visibility (`public` constructors для domain configs или public `ExtensionConfigReader`)?
12. Какие moves создадут circular dependencies между subpackages (например, defaults provider → resource constants → configuration)?
13. Должен ли `JavaAgentExtensionPaths` жить рядом со scrubbing domain или с generic `configuration.paths`?
14. Стоит ли `ExtensionEnums` держать рядом с property contract или рядом с factories, которые их интерпретируют?
15. Как subpackaging повлияет на discoverability для новых domain configs (flat 22 files vs nested tree)?
16. Должен ли `SdkExtensionConfig` быть в отдельном diagnostic/debug subpackage из-за nullable-only семантики?
17. При группировке by lifecycle — выделять ли отдельный subpackage для классов, связанных с runtime JMX seed (sampling/scrubbing/validation)?
18. Нужен ли public API jar / module boundary между property contract и domain DTO для внешних модулей?
19. Как minimizировать import churn в `PlatformSpanProcessorFactory` (4 configuration imports today)?
20. Следует ли сохранить flat package для property contract + facade даже при domain subpackaging (hybrid layout)?

---

## 15. Evidence Appendix

### Class count

```text
22 production Java files in configuration package
11 domain *ExtensionConfig classes (+ 1 ExtensionConfig facade)
```

### Sorted file list (Glob equivalent)

```text
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/AutoConfigurationCustomizerOrdering.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/BaggageExtensionConfig.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/ClassificationExtensionConfig.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/DropOldestExportProcessorDefaults.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/EnrichingExtensionConfig.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/ExtensionConfig.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/ExtensionConfigReader.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/ExtensionDefaults.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/ExtensionEnums.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/ExtensionEnvironmentVariables.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/ExtensionPropertyNames.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/JavaAgentExtensionPaths.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/MetricsExtensionConfig.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/OtelSdkDefaults.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/PlatformTracingDefaultsProvider.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/QueueExtensionConfig.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/ResourceExtensionConfig.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/SamplingExtensionConfig.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/ScrubbingExtensionConfig.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/SdkExtensionConfig.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/ValidationExtensionConfig.java
platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/configuration/WatchdogExtensionConfig.java
```

### External explicit imports (full grep, all modules in scope)

```text
ExtensionConfig:
  PlatformAutoConfigurationCustomizer, PlatformSamplerProvider,
  ExtensionConfigBootstrapGuardrailsArchTest, PlatformSamplerProviderTest,
  ExtensionConfigFacadeVsFactoryParityCharacterizationTest (same package),
  PlatformSpanProcessorFactoryScrubbingAdoptionTest,
  PlatformSpanProcessorFactoryExtensionConfigAdoptionTest,
  ExtensionConfigTest (same package)

SamplingExtensionConfig:
  PlatformSamplerFactory, PlatformSamplerBuilder, PlatformSamplerProviderTest

ScrubbingExtensionConfig:
  PlatformSpanProcessorFactory

QueueExtensionConfig:
  PlatformExportProcessorFactory

ExtensionEnums:
  PlatformSpanProcessorFactory, PlatformExportProcessorFactory

JavaAgentExtensionPaths:
  PlatformSpanProcessorFactory

ExtensionPropertyNames:
  ScrubbingRulesLoader, PlatformResourceProvider, ResourceAttributeResolver,
  ResourceStartupDiagnostics, SafeResourceProvider,
  PlatformResourceProviderTest, ResourceAttributeResolverTest

ExtensionDefaults:
  PlatformDropOldestExportSpanProcessor, PlatformResourceProvider, ResourceAttributeResolver,
  PlatformSpanProcessorFactoryScrubbingAdoptionTest,
  PlatformResourceProviderTest, ResourceAttributeResolverTest

DropOldestExportProcessorDefaults:
  PlatformDropOldestExportSpanProcessor, PlatformDropOldestExportSpanProcessorBuilderValidationTest,
  SharedDefaultsAlignmentTest (spring-boot-autoconfigure)

PlatformTracingDefaultsProvider:
  PlatformAutoConfigurationCustomizer,
  OtelEffectiveConfigSnapshotDefaultsContractTest (spring-boot-autoconfigure)

AutoConfigurationCustomizerOrdering:
  PlatformAutoConfigurationCustomizer
```

### Package-private elements (summary)

```text
Classes (package-private):
  ExtensionConfigReader
  ExtensionEnvironmentVariables
  OtelSdkDefaults

Constructors (package-private):
  All 11 *ExtensionConfig(ExtensionConfigReader)

Methods (package-private on ExtensionConfigReader):
  booleanValue, doubleValue, stringValue, nullableString, listValue, mapValue, durationValue
```

### ConfigProperties occurrences in configuration package

```text
ExtensionConfig.java          — constructor parameter + field via reader
ExtensionConfigReader.java    — field + all read methods
JavaAgentExtensionPaths.java  — resolveRawValue(ConfigProperties) parameter only
```

Domain configs: **zero** direct `ConfigProperties` references (confirmed by ArchUnit PR-2 rule scope).

### `new ExtensionConfig(` occurrences

**Production (2 sites):**

```text
PlatformAutoConfigurationCustomizer.java:56   extensionConfig.compareAndSet(null, new ExtensionConfig(config));
PlatformSamplerProvider.java:43             PlatformSamplerBuilder.build(new ExtensionConfig(config).sampling());
```

**Tests:** `ExtensionConfigTest`, `ExtensionConfigFacadeVsFactoryParityCharacterizationTest`, `PlatformSamplerProviderTest`, `PlatformSpanProcessorFactoryScrubbingAdoptionTest`, `PlatformSpanProcessorFactoryExtensionConfigAdoptionTest` — numerous instances (see grep output in §15).

### Git status

```text
NOT FOUND IN REPOSITORY
```

---

## 16. Non-Findings / Unknowns

| Item | Status |
|------|--------|
| Git working tree cleanliness | `NOT FOUND IN REPOSITORY` |
| Production imports of configuration package from `platform-tracing-spring-boot-autoconfigure` | `NOT FOUND IN REPOSITORY` |
| Dedicated unit test for `ExtensionConfigReader` | `NOT FOUND IN REPOSITORY` |
| Dedicated unit test for `ExtensionPropertyNames` | `NOT FOUND IN REPOSITORY` |
| Dedicated unit test for `AutoConfigurationCustomizerOrdering` | `NOT FOUND IN REPOSITORY` |
| Production consumer of `ResourceExtensionConfig` outside `ExtensionConfig` facade | `NOT FOUND IN REPOSITORY` |
| `platform.tracing.service.*` constants inside configuration package | `NOT FOUND IN REPOSITORY` (defined on `PlatformResourceProvider`) |
| ArchUnit rule explicitly forbidding direct `ExtensionPropertyNames` reads in factories post-A3' | `NOT FOUND IN REPOSITORY` (only parity/ adoption tests) |
| Java Platform Module System (`module-info`) restrictions on package exports | `NOT FOUND IN REPOSITORY` (no module-info inspected in scope) |

---

## 17. Final Status

```text
Inventory status: COMPLETED
No refactoring performed.
Ready for Perplexity 8-variant architecture scoring pass.
```
