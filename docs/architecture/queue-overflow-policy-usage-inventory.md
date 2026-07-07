# QueueOverflowPolicy Usage Inventory

## 1. Executive Summary

`QueueOverflowPolicy` — top-level enum в пакете `space.br1440.platform.tracing.otel.extension.configuration.enums`. Содержит две константы с каноническими строковыми значениями через `value()`:

| Constant | `value()` |
|---|---|
| `UPSTREAM` | `"UPSTREAM"` |
| `DROP_OLDEST` | `"DROP_OLDEST"` |

**Единственный production-потребитель enum'а:** `PlatformExportProcessorFactory` (метод `isExplicitUpstream`). Enum используется только для сравнения строк из `QueueExtensionConfig.overflowPolicy()` с каноническими значениями; тип `QueueOverflowPolicy` в runtime не хранится.

**Ключ свойства конфигурации:** `platform.tracing.queue.overflow-policy` (`ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY`).

**Platform default (код):** `"DROP_OLDEST"` (`ExtensionDefaults.DEFAULT_QUEUE_OVERFLOW_POLICY`), поставляется через `PlatformTracingDefaultsProvider` SPI supplier и читается `QueueExtensionConfig` при bootstrap.

**Контракт конфигурации:** enum экспонирует строковые значения наружу через `value()`; property key и string values — отдельный контракт, не зависящий от Java-имён enum-констант при сохранении `value()`.

**Нужны ли комментарии сейчас:** да. Имена `UPSTREAM` и `DROP_OLDEST` не раскрывают полностью runtime-поведение без комментариев/Javadoc и без чтения `PlatformExportProcessorFactory` / `PlatformDropOldestExportSpanProcessor`. Комментарии enum-констант ссылаются на `BatchSpanProcessor` OTel SDK и `PlatformDropOldestExportSpanProcessor`, но сами имена констант этого не передают.

Финальные имена для rename **не предлагаются** в этом документе.

---

## 2. Scope

### Inspected modules

| Module | Root inspected |
|---|---|
| `platform-tracing-otel-extension` | `src/main/java`, `src/test/java` |
| `platform-tracing-spring-boot-autoconfigure` | `src/main/java`, `src/test/java` |
| Documentation | `docs/` |

Build-артефакты (`build/`, `bin/`) в evidence appendix не включались — только `src/` и `docs/`.

### Searches run

Эквиваленты требуемых `rg`-команд (поиск по workspace, фильтр `src/` + `docs/`):

```text
rg "QueueOverflowPolicy" .
rg "DROP_OLDEST" .
rg "UPSTREAM" .
rg "queue.overflow-policy|QUEUE_OVERFLOW_POLICY|DEFAULT_QUEUE_OVERFLOW_POLICY" .
rg "PlatformExportProcessorFactory" platform-tracing-otel-extension/src/main/java platform-tracing-otel-extension/src/test/java
rg "PlatformDropOldestExportSpanProcessor|DropOldestExportProcessorDefaults|BatchSpanProcessor" platform-tracing-otel-extension/src/main/java platform-tracing-otel-extension/src/test/java docs
rg "platform.tracing.queue.overflow-policy" .
rg "configuration.enums.QueueOverflowPolicy" .
```

### Git availability

```text
Git unavailable; used rg-based repository search.
```

(`git status` / `git grep` вернули `fatal: not a git repository`.)

---

## 3. Current Enum Contract

**Файл:** `platform-tracing-otel-extension/src/main/java/space/br1440/platform/tracing/otel/extension/configuration/enums/QueueOverflowPolicy.java`

| Constant | `value()` | Current Comment | Behavioral Meaning From Code | Rename Risk |
|---|---|---|---|---|
| `UPSTREAM` | `"UPSTREAM"` | «Использовать стандартный `BatchSpanProcessor` OTel SDK.» | В `PlatformExportProcessorFactory.isExplicitUpstream`: если нормализованное значение property равно `"UPSTREAM"`, метод возвращает `true` → `maybeReplaceExportProcessor` **не заменяет** processor, stock `BatchSpanProcessor` сохраняется. В `DropOldestAspirationDiagnostics` (autoconfigure, строковое сравнение): `"UPSTREAM"` трактуется как «Agent использует stock BSP (drop-new по факту)». | **MEDIUM** — имя используется в operator-facing логах и env-var документации; `value()` string `"UPSTREAM"` — внешний контракт |
| `DROP_OLDEST` | `"DROP_OLDEST"` | «Платформенный `PlatformDropOldestExportSpanProcessor` (§2.5 Traces Requests.txt).» | В `isExplicitUpstream`: если значение равно `"DROP_OLDEST"`, возвращает `false` → активируется путь замены BSP на `PlatformDropOldestExportSpanProcessor` (при single-exporter, `instanceof BatchSpanProcessor`, captured exporter != null). Неизвестное значение property → WARN + fallback как `DROP_OLDEST`. Default property (`ExtensionDefaults.DEFAULT_QUEUE_OVERFLOW_POLICY`) = `"DROP_OLDEST"`. | **MEDIUM** — platform default string, §2.5 traceability, operator docs |

### Паттерн `value()` vs `name()`

Enum хранит каноническую строку в приватном поле `value` и отдаёт её через `public String value()`. Это **намеренный контракт**: Java-имя константы (`UPSTREAM`, `DROP_OLDEST`) может совпадать со строкой, но сравнение в production идёт через `.value()`, не через `Enum.name()`.

**Замена `value()` на `name()` без отдельного одобрения запрещена** — поведение не изменится только если строки совпадают; для других enum'ов платформы (`mask`/`fail-fast`) это уже не так.

---

## 4. Property Contract Mapping

| Class | Constant | Value | Meaning | Used By |
|---|---|---|---|---|
| `ExtensionPropertyNames` | `QUEUE_OVERFLOW_POLICY` | `"platform.tracing.queue.overflow-policy"` | Имя свойства OTel ConfigProperties для agent overflow-policy | `QueueExtensionConfig`, `PlatformTracingDefaultsProvider`, tests, docs |
| `ExtensionDefaults` | `DEFAULT_QUEUE_OVERFLOW_POLICY` | `"DROP_OLDEST"` | Platform default string при отсутствии явного значения в ConfigProperties | `QueueExtensionConfig` ctor (`reader.stringValue(..., DEFAULT)`), `PlatformTracingDefaultsProvider.supply()` fallback |
| `ExtensionEnvironmentVariables` (package-private, `.spi`) | `QUEUE_OVERFLOW_POLICY` | `"PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY"` | Env-var для override overflow-policy до merge в ConfigProperties | `PlatformTracingDefaultsProvider.supply()` — если env непустой, подставляет в map под ключ `platform.tracing.queue.overflow-policy` |
| `QueueExtensionConfig` | field `overflowPolicy` | `String` (не enum) | Snapshot overflow-policy на bootstrap; читается из ConfigProperties | `PlatformExportProcessorFactory.maybeReplaceExportProcessor(..., queueConfig, ...)` |
| `PlatformTracingDefaultsProvider` | map entry key | `ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY` | SPI defaults supplier: выставляет platform default или env override | `PlatformAutoConfigurationCustomizer` → `addPropertiesSupplier(defaultsProvider::supply)` |
| `DropOldestAspirationDiagnostics` | `AGENT_OVERFLOW_POLICY_PROPERTY` | `"platform.tracing.queue.overflow-policy"` | Agent-side property name (строковая константа в autoconfigure, **без импорта** `QueueOverflowPolicy`) | Spring startup diagnostics |
| `DropOldestAspirationDiagnostics` | `AGENT_OVERFLOW_POLICY_ENV` | `"PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY"` | Env-var hint / resolution | `resolveAgentOverflowPolicy()` |
| `TracingProperties.Queue` | Spring property | `platform.tracing.queue.policy` | **Отдельный** Spring UX enum (`DROP_OLDEST`, `DROP_NEWEST`) — не `QueueOverflowPolicy` | `DropOldestAspirationDiagnostics`, Spring metadata |

### Dual-channel note (evidence)

- **Agent runtime key:** `platform.tracing.queue.overflow-policy` → values `"UPSTREAM"` \| `"DROP_OLDEST"` (строки).
- **Spring desired key:** `platform.tracing.queue.policy` → отдельный Spring enum; autoconfigure **не импортирует** `QueueOverflowPolicy`.
- Согласование Spring vs Agent — через `DropOldestAspirationDiagnostics`, сравнивающий строки `"UPSTREAM"` / `"DROP_OLDEST"` литералами.

### ADR vs code default (finding)

В `docs/decisions/ADR-drop-oldest-export-processor-v1.md` таблица конфигурации (§ «Конфигурация») указывает default v1.x для Agent: `UPSTREAM` (= unset). **Текущий код** (`ExtensionDefaults.DEFAULT_QUEUE_OVERFLOW_POLICY`, `PlatformTracingDefaultsProvider`) поставляет `"DROP_OLDEST"` через SPI supplier. Это расхождение документации и кода — зафиксировано как finding; поведение описано по **коду**.

---

## 5. Production Usage Map

### 5.1 Direct `QueueOverflowPolicy` usage

| File | Class | Usage | Branch / Condition | Behavior |
|---|---|---|---|---|
| `.../factory/PlatformExportProcessorFactory.java:151` | `PlatformExportProcessorFactory` | `QueueOverflowPolicy.UPSTREAM.value().equals(normalized)` | `isExplicitUpstream()` | `true` → stock BSP path |
| `.../factory/PlatformExportProcessorFactory.java:154` | same | `QueueOverflowPolicy.DROP_OLDEST.value().equals(normalized)` | `isExplicitUpstream()` | `false` → replacement path eligible |
| `.../factory/PlatformExportProcessorFactory.java:159-161` | same | `.value()` в WARN message | unknown overflow-policy value | log expected `UPSTREAM`/`DROP_OLDEST`, fallback treat as `DROP_OLDEST` |

**Других production import'ов `QueueOverflowPolicy` в репозитории NOT FOUND IN REPOSITORY** (autoconfigure использует string literals).

### 5.2 Related string / behavior paths (without enum import)

| File | Class | Usage | Branch / Condition | Behavior |
|---|---|---|---|---|
| `.../configuration/QueueExtensionConfig.java:16` | `QueueExtensionConfig` | `reader.stringValue(QUEUE_OVERFLOW_POLICY, DEFAULT_QUEUE_OVERFLOW_POLICY)` | bootstrap read | `overflowPolicy` string, default `"DROP_OLDEST"` |
| `.../configuration/spi/PlatformTracingDefaultsProvider.java:41-45` | `PlatformTracingDefaultsProvider` | env override or `DEFAULT_QUEUE_OVERFLOW_POLICY` | SPI `supply()` | merged ConfigProperties получает overflow-policy default/override |
| `.../PlatformAutoConfigurationCustomizer.java:50,88-89` | `PlatformAutoConfigurationCustomizer` | `addPropertiesSupplier` + `addSpanProcessorCustomizer` → `maybeReplaceExportProcessor` | bootstrap | defaults applied before factory reads `QueueExtensionConfig` |
| `.../processor/PlatformDropOldestExportSpanProcessor.java` | `PlatformDropOldestExportSpanProcessor` | Javadoc: activated via `platform.tracing.queue.overflow-policy=DROP_OLDEST`; `pollFirst()` on overflow | runtime queue full | drop-oldest span eviction + export |
| `.../autoconfigure/actuator/DropOldestAspirationDiagnostics.java:105,120` | `DropOldestAspirationDiagnostics` | `"UPSTREAM".equals(agentPolicy)` | Spring `queue.policy=DROP_OLDEST` | WARN if Agent explicitly UPSTREAM; INFO if aligned |
| `.../autoconfigure/sampling/SamplingControlClient.java:618` | `SamplingControlClient` | Javadoc mention UPSTREAM | export processor inactive | diagnostic comment only |
| `.../autoconfigure/actuator/OtelEnvHintsBuilder.java:63-64` | `OtelEnvHintsBuilder` | env hint `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY` → property | actuator hints | documentation for operators |

---

## 6. Behavior Reconstruction

Источник: production code + контрактные тесты. Inference без code evidence не использовался.

### 6.1 What does `UPSTREAM` actually mean?

По коду `PlatformExportProcessorFactory`:

1. **`isExplicitUpstream()` возвращает `true`** только когда `queueConfig.overflowPolicy()` после `trim().toUpperCase(Locale.ROOT)` равен `"UPSTREAM"`.
2. **`maybeReplaceExportProcessor`** при `isExplicitUpstream == true` **немедленно возвращает входной `processor` без изменений** (строка 95-97: «stock BSP сохраняется»).
3. Javadoc фабрики (строки 89-90, 147-148): явный `UPSTREAM` = сознательный выбор оператора **не заменять** stock `BatchSpanProcessor`.
4. `DropOldestAspirationDiagnostics` (autoconfigure): `"UPSTREAM"` на Agent = «stock BatchSpanProcessor → drop-new по факту» (строка 120-121).

**Вывод из evidence:** `UPSTREAM` = **использовать стандартный OTel SDK `BatchSpanProcessor` без замены на платформенный processor**. Overflow semantics — те, что у stock BSP (в документации/platform probe — drop-new, не drop-oldest).

`UPSTREAM` **не означает** напрямую «делегировать overflow OTel SDK» как абстракцию — это **конкретное решение не вызывать `PlatformDropOldestExportSpanProcessor`**.

### 6.2 What does `DROP_OLDEST` actually mean?

По коду `PlatformExportProcessorFactory.maybeReplaceExportProcessor` (когда `isExplicitUpstream == false`):

1. Проверки guard: single effective exporter (`exporterCount <= 1`), captured exporter != null, `processor instanceof BatchSpanProcessor`.
2. При успехе: `processor.shutdown()` stock BSP, затем `PlatformDropOldestExportSpanProcessor.builder(exporter).readBspConfigFrom(config).build()`.
3. Fallback без замены (но policy всё ещё «не UPSTREAM»): multi-exporter WARN «Fallback: stock BatchSpanProcessor (UPSTREAM)»; non-BSP processor WARN passthrough.

По коду `PlatformDropOldestExportSpanProcessor`:

- Bounded FIFO queue of `SpanData` snapshots.
- On overflow (`size >= maxQueueSize`): **`pollFirst()`** — evict oldest, increment `droppedSpansOverflow`, enqueue new span.

**Вывод из evidence:** `DROP_OLDEST` = **активировать платформенный export processor `PlatformDropOldestExportSpanProcessor` вместо stock BSP**, с **гарантированной drop-oldest overflow semantics** на bounded queue (при выполнении guard-условий).

Property value `"DROP_OLDEST"` также является **platform default** (`ExtensionDefaults`, `PlatformTracingDefaultsProvider`), т.е. отсутствие явного override → тот же runtime path (если guards pass).

### 6.3 Behavioral difference

| Aspect | `UPSTREAM` | `DROP_OLDEST` |
|---|---|---|
| Export `SpanProcessor` | Stock OTel `BatchSpanProcessor` (unchanged) | `PlatformDropOldestExportSpanProcessor` replaces BSP |
| Overflow semantics (documented in code/docs) | Stock BSP drop-new (per probe / diagnostics text) | Platform bounded queue, evict oldest (`pollFirst`) |
| Activation | Explicit `"UPSTREAM"` string **or** fallback paths (multi-exporter, non-BSP) while policy ≠ UPSTREAM | Default string + explicit `"DROP_OLDEST"` + unknown-value fallback |
| Operator intent (Javadoc) | Explicit opt-out of platform replacement | Platform §2.5 drop-oldest mode (default in current code) |

---

## 7. Test Coverage Map

### 7.1 Tests referencing `QueueOverflowPolicy` enum

| Test Class | Assertion / Scenario | Constant Covered | Behavioral Meaning Protected |
|---|---|---|---|
| `QueueOverflowPolicyTest` | `UPSTREAM.value()` == `"UPSTREAM"`; `DROP_OLDEST.value()` == `"DROP_OLDEST"` | both | String contract via `value()` |
| `QueueOverflowPolicyTest` | `DROP_OLDEST.value()` == `ExtensionDefaults.DEFAULT_QUEUE_OVERFLOW_POLICY` | `DROP_OLDEST` | Enum default aligns with platform default constant |
| `PlatformTracingDefaultsProviderTest` | default map contains `QUEUE_OVERFLOW_POLICY` → `QueueOverflowPolicy.DROP_OLDEST.value()` | `DROP_OLDEST` | SPI supplier default = DROP_OLDEST string |
| `PlatformTracingDefaultsProviderTest` | env override → `"UPSTREAM"` in map (uses string literal, not enum constant for override value) | `UPSTREAM` (string) | Env bridge priority over default |

### 7.2 Tests referencing strings / factory behavior (no enum import)

| Test Class | Assertion / Scenario | Constant Covered | Behavioral Meaning Protected |
|---|---|---|---|
| `ExtensionConfigTest` | default `queue().overflowPolicy()` == `"DROP_OLDEST"`; explicit `"UPSTREAM"` round-trip | strings | Facade reads property correctly |
| `ExtensionConfigFacadeVsFactoryParityCharacterizationTest$QueueParity` | absent property → facade `"DROP_OLDEST"` == default; documents factory treats absent as non-UPSTREAM | `DROP_OLDEST` (default path) | Facade/factory parity on default |
| `PlatformAutoConfigurationCustomizerExportProcessorTest` | default → `PlatformDropOldestExportSpanProcessor`; explicit `DROP_OLDEST` → replacement; explicit `UPSTREAM` → stock BSP; unknown → DROP_OLDEST fallback; multi-exporter → no replacement | `UPSTREAM`, `DROP_OLDEST` | End-to-end SPI customizer export processor selection |
| `BspReplacementSpikeTest` | ConfigProperties reads `platform.tracing.queue.overflow-policy`; SPI supplier default DROP_OLDEST | strings | SPI/property naming spike |
| `BspDropOldestNoDoubleExportTest` | opt-in DROP_OLDEST: no double export | `DROP_OLDEST` | Safety after BSP replacement |
| `PlatformDropOldestExportSpanProcessorOverflowPolicyTest` | drop-oldest queue semantics | processor behavior | Overflow action (oldest evicted), not enum names |
| `PlatformDropOldestExportSpanProcessorLifecycleTest` | shutdown/flush lifecycle | processor behavior | Replacement processor correctness |
| `PlatformDropOldestExportSpanProcessorBuilderValidationTest` | builder defaults from `DropOldestExportProcessorDefaults` | BSP defaults | Builder validation (indirect queue sizing) |
| `DropOldestAspirationDiagnosticsTest` (autoconfigure) | Agent unset/DROP_OLDEST → INFO; Agent UPSTREAM → WARN | `UPSTREAM`, `DROP_OLDEST` strings | Spring vs Agent alignment diagnostics |

### 7.3 Tests referencing `PlatformExportProcessorFactory` (partial)

| Test Class | Notes |
|---|---|
| `PlatformExportProcessorFactorySafeWrapTest` | `captureExporter` only; overflow policy not covered |

---

## 8. Documentation Mentions

Поиск в `docs/` (ключевые документы; полный список совпадений >80 строк — см. Evidence Appendix).

| Document | Mention | Meaning | Is It Current? |
|---|---|---|---|
| `docs/decisions/ADR-drop-oldest-export-processor-v1.md` | `UPSTREAM`, `DROP_OLDEST`, `platform.tracing.queue.overflow-policy`, `BatchSpanProcessor`, `PlatformDropOldestExportSpanProcessor` | Canonical ADR for opt-in/replacement design, config table, diagnostics matrix | **Partially** — default table says `UPSTREAM`; code default is `DROP_OLDEST` |
| `docs/MIGRATION.md` | disable via `UPSTREAM`; env `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY` | Operator migration / rollback to stock BSP | Yes (operator-facing) |
| `docs/tracing/traceability.md` | v1.x default DROP_OLDEST via SPI supplier; UPSTREAM rollback | Requirements traceability §2.5 | Aligns with **code** default |
| `docs/architecture/extension-configuration-package-inventory.md` | `QueueExtensionConfig`, `PlatformExportProcessorFactory`, enum mention (legacy `ExtensionEnums` reference in inventory text) | Architecture inventory | **Partially stale** on enum container name post-decomposition |
| `docs/architecture/extension-configuration-refactoring-dossier.md` | `overflowPolicy()` mapping | Refactoring dossier | Yes for property mapping |
| `CHANGELOG.md` | UPSTREAM rollback, DROP_OLDEST default | Release notes | Yes |

Docs explicitly describing `BatchSpanProcessor` drop-new vs platform drop-oldest: `ADR-drop-oldest-export-processor-v1.md`, `docs/decisions/ADR-bsp-overflow-policy-finding.md` (referenced from ADR, not fully quoted here).

---

## 9. Naming Problem Analysis

### `UPSTREAM`

- **Too generic:** «upstream» не указывает домен (export queue? OTel SDK? collector?).
- **Upstream of what:** в коде это «не заменять processor, оставить stock BSP», а не abstract upstream delegation.
- **Does not say BatchSpanProcessor:** поведение stock BSP (drop-new) видно только из связанных Javadoc (`DropOldestAspirationDiagnostics`, ADR), не из имени константы.
- **Dual meaning risk:** в логах multi-exporter fallback текст говорит «Fallback: stock BatchSpanProcessor (UPSTREAM)» даже когда policy **не** было явно `UPSTREAM` — overload термина «UPSTREAM» как «stock path» vs explicit config value.

### `DROP_OLDEST`

- **Closer to behavior:** строка совпадает с overflow action в `PlatformDropOldestExportSpanProcessor` (`pollFirst`).
- **Policy vs implementation:** имя описывает overflow **действие**, но runtime activation — **замена SpanProcessor implementation**, не только switch overflow algorithm inside BSP.
- **Implies dropping spans vs processor swap:** оператор может прочитать как «BSP с drop-oldest», тогда как код **заменяет** BSP на другой processor class.
- **Default coupling:** как platform default string широко используется в traceability/docs; rename Java constant без изменения `value()` менее рискован, чем rename string.

---

## 10. Naming Dimensions for Deep Research

Нейтральные оси для последующего Perplexity Deep Research pass (без выбора финальных имён):

1. **Behavior-first naming** — имя отражает overflow action (drop oldest vs reject new).
2. **Implementation-first naming** — имя отражает выбор processor class (`BatchSpanProcessor` vs `PlatformDropOldestExportSpanProcessor`).
3. **OTel SDK compatibility naming** — подчёркивание совместимости/сохранения stock BSP.
4. **Queue overflow policy naming** — фокус на policy property semantics (`platform.tracing.queue.overflow-policy`).
5. **Exporter processor strategy naming** — «strategy» выбора export pipeline processor.
6. **Config-string stability vs Java enum readability** — разделение external string (`value()`) и Java constant identifier.
7. **Short enum constants vs self-explanatory enum constants** — trade-off для call sites в `PlatformExportProcessorFactory`.
8. **Explicit opt-in vs default path naming** — как назвать режим, который является platform default в текущем коде.
9. **Fallback path naming** — multi-exporter / non-BSP fallback reuse слова «UPSTREAM» в logs.
10. **Spring vs Agent vocabulary alignment** — `queue.policy` (Spring) vs `queue.overflow-policy` (Agent).
11. **Operator-facing log/message readability** — WARN/INFO strings in `DropOldestAspirationDiagnostics`.
12. **Negative vs positive framing** — «USE_STOCK_BSP» vs «DROP_OLDEST» style.
13. **Industry pattern alignment** — Kafka/Netty/Reactor buffer overflow discard policies.
14. **OpenTelemetry ecosystem terminology** — BatchSpanProcessor, export processor, backpressure.
15. **Comment-removal goal** — имена должны быть достаточны без Javadoc на enum constants (committee requirement).

---

## 11. Candidate Name Requirements

Future names (Java enum constants) should satisfy:

1. **Self-explanatory without comments** — reader understands processor/overflow choice without enum Javadoc.
2. **Distinguish stock OTel BSP path from platform drop-oldest path** — two distinct runtime modes proven in §6.
3. **Preserve external string values** (`"UPSTREAM"`, `"DROP_OLDEST"`) unless separately approved — property/env/operator docs depend on strings, not Java names.
4. **No unproven runtime claims** — names must not imply behavior not demonstrated in code (e.g. «ERROR_PRIORITY»).
5. **Avoid collision/confusion with OpenTelemetry SDK type names** — clarity vs `BatchSpanProcessor` as class name.
6. **Readable at call sites** — `QueueOverflowPolicy.<NAME>.value()` in `isExplicitUpstream` branches and WARN templates.
7. **Readable in factory branch conditions** — especially `isExplicitUpstream` boolean semantics.
8. **Compatible with dual-channel diagnostics** — Spring-side strings may remain separate; Agent enum names should not worsen mismatch.
9. **Stable for log grep** — operators grep `UPSTREAM`/`DROP_OLDEST` today in logs/docs.
10. **Rename Java constants without changing `value()`** should remain viable default strategy.

---

## 12. Rename Impact Surface

Renaming Java enum constants while preserving `value()` strings:

| Affected Area | Expected Change | Risk |
|---|---|---|
| `QueueOverflowPolicy.java` | enum constant identifiers + possibly Javadoc | LOW if `value()` unchanged |
| `PlatformExportProcessorFactory` | import unchanged; qualified constant names in 5 lines | LOW |
| `QueueOverflowPolicyTest` | constant references | LOW |
| `PlatformTracingDefaultsProviderTest` | `QueueOverflowPolicy.DROP_OLDEST` reference | LOW |
| Spring autoconfigure | **No enum import today** — string literals `"UPSTREAM"`/`"DROP_OLDEST"` unaffected by Java enum rename | NONE for Java rename-only |
| Operator property values | unchanged if `value()` preserved | NONE |
| Env vars (`PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY`) | unchanged | NONE |
| Documentation mentioning Java constant names | update if rename published | MEDIUM |
| Log messages using `.value()` strings | unchanged if `value()` preserved | NONE |
| ArchUnit / fitness | no rules target enum constant names | NONE expected |
| Serialization | property is string in ConfigProperties, not Java enum serialized by name | LOW |

```text
Renaming Java enum constants does not necessarily require changing external config string values if value() remains unchanged.
```

Changing `value()` strings **would** be breaking for: `platform.tracing.queue.overflow-policy`, env vars, ADR tables, migration docs, diagnostics, e2e JVM props — **HIGH risk**, out of scope unless explicitly approved.

---

## 13. Questions for Perplexity Deep Research

1. How do Java platform libraries name enum constants meaning «use default/upstream SDK implementation instead of custom wrapper»?
2. Is `UPSTREAM` an acceptable industry term when behavior specifically means «keep OpenTelemetry SDK `BatchSpanProcessor` unchanged»?
3. Should queue overflow enum constants name the **overflow action** (drop-oldest) or the **processor selection** (platform export processor)?
4. When overflow policy triggers **processor class replacement**, should the enum live under `QueueOverflowPolicy` or `ExportProcessorStrategy`?
5. Should `DROP_OLDEST` be expanded to `DROP_OLDEST_ON_OVERFLOW` for clarity without changing external string via `value()`?
6. For the stock BSP path, are names like `OTEL_SDK_DEFAULT`, `STANDARD_BATCH_PROCESSOR`, or `STOCK_BATCH_PROCESSOR` clearer than `UPSTREAM`?
7. Should default-mode and opt-in-mode be symmetric in naming (both behavior-first or both implementation-first)?
8. How should naming reflect that multi-exporter fallback logs say «UPSTREAM» even when config was `DROP_OLDEST`?
9. What naming patterns do OpenTelemetry Java autoconfigure extensions use for behavior-changing opt-ins?
10. How do Kafka producer buffer policies name `DROP` vs `FAIL` vs default paths?
11. How does Netty/Reactor name overflow/backpressure discard strategies in public enums?
12. Does Micrometer or Resilience4j use separate external string contracts decoupled from Java enum constant names?
13. For dual-channel configs (Spring desired vs Agent effective), should Agent enum names align lexically with Spring `OverflowPolicy`?
14. What is the readability trade-off in boolean helpers like `isExplicitUpstream()` vs `usesStockBatchSpanProcessor()` at call sites?
15. If comments must be removable, what minimum semantic words must appear in each constant name (processor? overflow? otel? platform?)?
16. Should external config values `"UPSTREAM"`/`"DROP_OLDEST"` be renamed in a later breaking change, or only Java identifiers?
17. How do operators discover misconfiguration today — by string grep or Java API — and how does that constrain rename?
18. Are there precedents in commercial APM/tracing products for naming «guaranteed drop-oldest export queue» modes?

---

## 14. Evidence Appendix

### 14.1 `QueueOverflowPolicy` usages (src only)

```text
platform-tracing-otel-extension/src/main/java/.../configuration/enums/QueueOverflowPolicy.java
platform-tracing-otel-extension/src/main/java/.../factory/PlatformExportProcessorFactory.java (import + lines 151,154,159-161)
platform-tracing-otel-extension/src/test/java/.../configuration/enums/QueueOverflowPolicyTest.java
platform-tracing-otel-extension/src/test/java/.../configuration/spi/PlatformTracingDefaultsProviderTest.java (import + line 69)
```

`platform-tracing-spring-boot-autoconfigure/src`: **0 matches** for `QueueOverflowPolicy`.

### 14.2 `DROP_OLDEST` usages (src + docs, abbreviated)

**Production / test src (representative):**

```text
ExtensionDefaults.DEFAULT_QUEUE_OVERFLOW_POLICY = "DROP_OLDEST"
QueueExtensionConfig → default overflowPolicy
PlatformExportProcessorFactory → isExplicitUpstream false path; unknown fallback
PlatformDropOldestExportSpanProcessor Javadoc
PlatformTracingDefaultsProvider → default map entry
TracingProperties.Queue.OverflowPolicy.DROP_OLDEST (Spring, separate enum)
DropOldestAspirationDiagnostics → alignment messages
PlatformAutoConfigurationCustomizerExportProcessorTest (multiple scenarios)
BspReplacementSpikeTest, BspDropOldestNoDoubleExportTest
ExtensionConfigTest, ExtensionConfigFacadeVsFactoryParityCharacterizationTest
DropOldestAspirationDiagnosticsTest
```

**Docs:** `ADR-drop-oldest-export-processor-v1.md`, `MIGRATION.md`, `traceability.md`, `CHANGELOG.md`, architecture inventories.

### 14.3 `UPSTREAM` usages (src + docs, abbreviated)

**Production / test src (representative):**

```text
QueueOverflowPolicy.UPSTREAM.value() in PlatformExportProcessorFactory
PlatformExportProcessorFactory Javadoc/comments (stock BSP path)
DropOldestAspirationDiagnostics → "UPSTREAM".equals(agentPolicy)
PlatformAutoConfigurationCustomizerExportProcessorTest → explicit UPSTREAM scenario
PlatformTracingDefaultsProviderTest → env override "UPSTREAM"
BspReplacementSpikeTest comments (multi-exporter fallback)
e2e: MapWireRoundTripE2ETest, AgentHttpSpringSmokeProcessRunner (JVM -D...UPSTREAM)
```

### 14.4 `platform.tracing.queue.overflow-policy` usages (src)

```text
ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY
QueueExtensionConfig
PlatformTracingDefaultsProvider
DropOldestAspirationDiagnostics.AGENT_OVERFLOW_POLICY_PROPERTY
OtelEnvHintsBuilder
Multiple tests setting System property or ConfigProperties key
```

### 14.5 Tests found (summary count)

| Area | Test classes (src) |
|---|---|
| Direct enum contract | `QueueOverflowPolicyTest` |
| Defaults SPI | `PlatformTracingDefaultsProviderTest` |
| ExtensionConfig | `ExtensionConfigTest`, `ExtensionConfigFacadeVsFactoryParityCharacterizationTest` |
| Export processor integration | `PlatformAutoConfigurationCustomizerExportProcessorTest` |
| Spike / safety | `BspReplacementSpikeTest`, `BspDropOldestNoDoubleExportTest` |
| Processor semantics | `PlatformDropOldestExportSpanProcessorOverflowPolicyTest`, `PlatformDropOldestExportSpanProcessorLifecycleTest`, `PlatformDropOldestExportSpanProcessorBuilderValidationTest` |
| Spring diagnostics | `DropOldestAspirationDiagnosticsTest` |

### 14.6 Docs found (summary)

Primary: `docs/decisions/ADR-drop-oldest-export-processor-v1.md`, `docs/MIGRATION.md`, `docs/tracing/traceability.md`, `docs/architecture/extension-configuration-package-inventory.md`, `docs/architecture/extension-configuration-refactoring-dossier.md`, `CHANGELOG.md`.

---

## 15. Non-Findings / Unknowns

| Item | Status |
|---|---|
| Production consumer of `QueueOverflowPolicy` outside `PlatformExportProcessorFactory` | **NOT FOUND IN REPOSITORY** |
| Import of `QueueOverflowPolicy` in `platform-tracing-spring-boot-autoconfigure` | **NOT FOUND IN REPOSITORY** |
| Runtime storage of `QueueOverflowPolicy` as typed field (vs `String`) | **NOT FOUND IN REPOSITORY** — only `String overflowPolicy` in `QueueExtensionConfig` |
| Git history / blame for naming rationale | **NOT FOUND IN REPOSITORY** (git unavailable) |
| Exact OTel SDK 1.62.0 internal BSP overflow implementation | Described via docs/probe references; **probe source not re-analyzed in this inventory** |
| Consistency ADR default table (`UPSTREAM`) vs code default (`DROP_OLDEST`) | **Documented discrepancy** — requires separate ADR/code reconciliation, not resolved here |

---

## 16. Final Status

```text
Inventory status: COMPLETED
No refactoring performed.
Ready for Perplexity Deep Research naming pass.
```
