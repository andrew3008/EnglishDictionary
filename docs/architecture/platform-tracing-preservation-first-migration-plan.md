# Platform Tracing — Preservation-First Migration Plan

## Document status

```text
Status: Implementation planning document.
Scope: Preservation-first migration from current Platform Tracing codebase to approved Clean Core Hybrid Architecture.
This document is not an architecture review.
This document is not a request for big-bang rewrite.
```

**Snapshot date:** 2026-06-11  
**Sources:** `platform-tracing-current-codebase-inventory.md`; Perplexity migration plan passes (Migration Plan.md, pass-1, pass-2, pass-3); approved target architecture docs (ADR Clean Core Hybrid, target architecture, PR roadmap, fitness functions, evidence gates).

**Position:** `Preserve existing assets first, refactor second.`

**Approved target (reference only, not reopened here):** Clean Core Hybrid Architecture; Desired State Configuration Layer; production READ-only Actuator; dev-only Actuator MUTATION; module taxonomy per target architecture; Config Server as runtime policy authority; Helm/env as startup topology; JMX validated Map wire; baseline pipeline = sampling + mandatory scrubbing + export.

**Inventory facts (source of truth for classes/tests/benchmarks):** 14 active Gradle modules; 279 production Java classes; 213 test classes; 16 JMH benchmarks; `TracingConfigReconciler` **Not found** in current codebase.

---

## 1. Резюме

### Почему миграция должна быть preservation-first

Кодовая база содержит **279 production классов**, **213 тестовых классов**, **16 JMH benchmark-классов** и задокументированные macro perf результаты M0–M10. Все это — производственный капитал, создававшийся итеративно. `platform-tracing-otel-extension` содержит наиболее ценный production runtime: `CompositeSampler` с цепочкой из 7 правил, `ScrubbingSpanProcessor` с полноценным rule engine, `PlatformDropOldestExportSpanProcessor` и `SafeSpanExporter`. Перепис этих компонентов с нуля неприемлем: ни один big-bang rewrite не воспроизведёт семантику без регрессий.

### Почему нельзя делать big-bang rewrite

Текущая система имеет задокументированный FAIL по M5 (+48% CPU, +25% RSS). Это означает, что производительность хрупкая — любое незаметное изменение поведения на hot path (`CompositeSampler`, `ScrubbingSpanProcessor`) без JMH/macro perf protection может ухудшить ситуацию. Без `CompositeSamplerBenchmark`, `ScrubbingEngineBenchmark` и M5 delta gates нет способа отличить улучшение от деградации.

### Почему нельзя коллапсировать модули в первой волне

Текущая изоляция `platform-tracing-autoconfigure-webmvc` и `platform-tracing-autoconfigure-webflux` защищена `WebStackIsolationTest` и `DuplicateSpansRegressionMatrixTest`. Стартеры (`platform-tracing-spring-boot-starter-servlet`, `platform-tracing-spring-boot-starter-reactive`) — публичные точки входа для команд-потребителей. Их коллапс немедленно поломает classpath гарантии потребителей, которые задекларировали конкретный артефакт в своём `build.gradle`.

### Почему PR-0 обязан захватить baseline

`PerformanceReleaseGateTest` читает `performance-budgets.yaml`. До первого коммита который меняет границы классов необходимо зафиксировать baseline `jmhSaveBaseline` и результаты M0/M5 в `perf-results/`. Без этого снимка нельзя сравнить поведение до и после PR-6 (`SPLIT_CORE_AND_ADAPTER` для sampler/scrubbing).

### Почему критические тесты нужно дублировать до переноса кода

`ScrubbingSecurityNegativeTest`, `CompositeSamplerConcurrentUpdateBenchmark`, `RuntimeSamplingControlSmokeTest` сейчас тестируют поведение через OTel adapter layer. После того как policy переедет в `platform-tracing-core`, тесты должны покрывать её и там, и в extension. Если перенести классы, не продублировав тесты, останется "тёмная зона" регрессии на стыке split.

### Почему `platform-tracing-core` не может стать pure сразу

Сейчас `platform-tracing-core` имеет `api opentelemetry-api` как транзитивную зависимость. `DefaultPlatformTracing`, `OtelPlatformContextPropagation`, `*SpanBuilderImpl` используют OTel types. Выдернуть OTel API из core одним коммитом означает сломать `DefaultPlatformTracing` и всю инфраструктуру span builders одновременно. Правильный путь: сначала извлечь pure policy (sampling rules, scrubbing engine), затем постепенно отделить OTel facade.

### Почему `TracingConfigReconciler` должен эволюционировать из существующих assets

`TracingConfigReconciler` **не найден в текущей кодовой базе**. Существующий путь — `TracingRefreshScopeAutoConfiguration` → `RuntimeConfigApplier` → `SamplingControlClient` → JMX → `PlatformTracingControl` — рабочий и покрыт тестами `RuntimeConfigApplierTest`. Новый reconciler должен создаваться как слой поверх этих компонентов, а не вместо них, с двойной работой (старый + новый путь) до тех пор пока equivalence не будет доказана тестами.

***

## 2. Принципы миграции

1. **Preserve existing assets first, refactor second.** Ни один production класс не удаляется без замены и доказательства эквивалентности.
2. **No big-bang rewrite.** Каждый PR должен быть независимо ревьюируемым и откатываемым.
3. **No first-wave module collapse.** Все 14 активных Gradle модулей остаются в первой волне.
4. **Tests before code moves.** Тест на поведение должен существовать в target layer до перемещения кода.
5. **Benchmarks before optimization.** `jmhSaveBaseline` перед любым рефактором hot path; `jmhCompareBaseline` после.
6. **Preserve starter developer experience.** `platform-tracing-spring-boot-starter-servlet` и `platform-tracing-spring-boot-starter-reactive` — неизменные публичные контракты для потребителей.
7. **Preserve WebMVC/WebFlux isolation.** `WebStackIsolationTest` должен проходить зелёным в каждом PR.
8. **Preserve current JMX behavior until validated wire migration is proven.** `PlatformTracingControl` + `SamplingControlClient` invoke-by-name path не меняется до тех пор пока PR-3 spike не будет принят.
9. **Preserve RefreshScope / RuntimeConfigApplier behavior until reconciler is proven equivalent.** `RuntimeConfigApplierTest` + `RuntimeSamplingControlSmokeTest` должны проходить во всех PR до PR-10.
10. **Keep scrubbing mandatory.** `ScrubbingSpanProcessor` не делается optional ни в каком PR.
11. **Keep Actuator MUTATION disabled in production.** PR-11 добавляет guard; до PR-11 WriteOperation остаётся как есть, но явно документируется как MIGRATION_RISK.
12. **Keep OTel/Spring out of pure core.** После PR-6 ArchUnit gate запрещает `opentelemetry-api` и `spring-*` в `platform-tracing-core` policy packages.
13. **Treat Agent state as applied state, not source of truth.** Agent configuration (`ExtensionPropertyNames`, `ExtensionDefaults`, `PlatformTracingDefaultsProvider`) reflects applied state, not Spring as source of truth; `DomainConfigHolder` в агенте хранит только последнее успешно применённое состояние.
14. **Zero net test deletion in migration wave 1.** 213 тестовых классов — пол. Можно только добавлять.
15. **Benchmark names and parameters frozen until PR-0 baseline is captured.** `CompositeSamplerBenchmark`, `ScrubbingEngineBenchmark` и другие 14 JMH классов — имена и параметры не меняются до фиксации baseline.

***

## 3. Recommended PR sequence (baseline plan PR-0 … PR-13)

> **Note:** Раздел 3 — исходная baseline-последовательность из Perplexity output (PR-0 … PR-13). Раздел 15 содержит **скорректированную** последовательность после adversarial self-review (split PR-5/6/7/8/10, renumbering PR-4/PR-12). Оба раздела сохранены без схлопывания.

***

### PR-0 — Baseline lock: behavior, performance, dependency snapshot

**Цель:** Зафиксировать все существующие baseline'ы перед любыми изменениями. Ни одна строка production кода не меняется.

**Почему этот PR существует:** Без зафиксированного baseline невозможно доказать, что последующие PR не деградировали производительность или поведение. M5 documented FAIL (+48% CPU, +25% RSS) должен быть воспроизводимо зафиксирован как отправная точка.

**Модули:**

- `platform-tracing-bench` (run + save baseline)
- `platform-tracing-perf-tests` (M0, M5 capture)
- `platform-tracing-e2e-tests` (smoke run)
- Все модули (Gradle dependency lock)

**Конкретные классы/пакеты:** Только скрипты и Gradle tasks — никакого production кода.

**Существующие тесты для сохранения:** Все 213 — запустить и зафиксировать что они зелёные.

**Тесты для дублирования до переноса кода:** Нет — этот PR ничего не переносит.

**Тесты для добавления:**

- Smoke-тест что `platform-tracing-spring-boot-starter-servlet` и `platform-tracing-spring-boot-starter-reactive` транзитивно не экспонируют `platform-tracing-otel-extension` или `platform-tracing-core` как direct compile dependency для потребителей

**Benchmarks to run:**

- `./gradlew :platform-tracing-bench:jmh` — все 16 JMH классов
- `./gradlew :platform-tracing-bench:jmhSaveBaseline` — записать в `baselines/`
- `PerformanceReleaseGateTest` — убедиться что budgets файл актуален

**E2E/perf scenarios:**

- M0 (host calibration) — записать результат
- M5 (agent+extension+export) — записать FAIL результат как официальный baseline
- `RuntimeSamplingControlSmokeTest` — должен пройти зелёным

**Behavior change:** нет
**Telemetry change:** нет
**Risk level:** low

**Rollback strategy:** N/A — PR без production изменений.

**Acceptance criteria:**

- Все 213 тестов зелёные
- JMH baseline зафиксирован в `baselines/`
- M0/M5 результаты зафиксированы в `perf-results/`
- `PerformanceReleaseGateTest` зелёный
- `RuntimeSamplingControlSmokeTest` зелёный

**Must NOT be done in this PR:** Любые изменения production кода, зависимостей, package структуры.

**Should this PR be split further:** нет

***

### PR-1 — Module taxonomy documentation + dependency guardrails (ArchUnit baseline)

**Цель:** Задокументировать текущую module taxonomy в виде `DEVELOPERS.md` и добавить ArchUnit правила, запрещающие неправильные зависимости уже сейчас. Никакого рефактора кода.

**Почему этот PR существует:** Текущее состояние нарушает target dependency direction (`platform-tracing-core` имеет `api opentelemetry-api`; `platform-tracing-api` имеет `compileOnly OTel`). Нужно зафиксировать текущие нарушения как `@ArchIgnore` или `allowedViolations`, одновременно запрещая добавление новых.

**Модули:**

- `platform-tracing-test` (добавить новые ArchUnit rules)
- Все модули (читаемые constraints)

**Конкретные классы/пакеты:**

- `space.br1440.platform.tracing.test.arch.TracingArchRules` — расширить
- `space.br1440.platform.tracing.test.arch.OtelSdkArchRules` — расширить
- Новые правила: `CoreNoDependencyOnOtelRule`, `CoreNoDependencyOnSpringRule`, `StarterNoInternalExposureRule`

**Существующие тесты для сохранения:**

- `TracingArchRulesTest`, `OtelSdkArchRulesTest`, `OtelDirectIntegrationRulesTest`, `EscapeHatchArchRuleTest`, `ExtensionNoSpringDependencyArchTest`, `OtelDirectIntegrationArchTest` (core и autoconfigure)

**Тесты для дублирования:** Нет.

**Тесты для добавления:**

- `CoreNoDependencyOnOtelRuleTest` — текущие нарушения в `@ArchIgnore`, новые — fail
- `StarterNoInternalExposureRuleTest` — starter не экспонирует extension/core как compile dep
- `AutoconfigureNoOtelExtensionMainDepRuleTest` — autoconfigure не импортирует extension main классы напрямую

**Benchmarks to run:** Нет.

**E2E/perf scenarios:** Нет.

**Behavior change:** нет
**Telemetry change:** нет
**Risk level:** low

**Rollback strategy:** revert новые ArchUnit тесты.

**Acceptance criteria:**

- Все существующие ArchUnit тесты зелёные
- Новые ArchUnit тесты зелёные (с `@ArchIgnore` для текущих нарушений)
- `DEVELOPERS.md` описывает module taxonomy, кто может от чего зависеть, developer experience guide

**Must NOT be done in this PR:** Изменение production кода; удаление `@ArchIgnore`; рефактор dependency graph.

**Implemented taxonomy doc:** [platform-tracing-module-taxonomy.md](platform-tracing-module-taxonomy.md)

**Should this PR be split further:** нет

***

### PR-2 — Wire schema v1: validated Map contract в `platform-tracing-api`

**Implemented wire schema doc:** [platform-tracing-wire-schema-v1.md](platform-tracing-wire-schema-v1.md)

**Цель:** Определить типизированную wire schema для JMX boundary как set of constants и validation utilities в `platform-tracing-api`. Никакого изменения `PlatformTracingControl` или `SamplingControlClient` — только спецификация.

**Почему этот PR существует:** Текущий `PlatformTracingControl` использует Map-based операции частично. Нужно зафиксировать что считается валидным wire payload до начала CL-boundary migration. `platform-tracing-api` — правильное место для CL-neutral контракта.

**Модули:**

- `platform-tracing-api` (новые классы)

**Конкретные классы/пакеты:**

- Новый package `space.br1440.platform.tracing.api.jmx` (или `api.wire`)
- `TracingControlWireKeys` — string constants для Map keys
- `TracingControlWireValidator` — validation logic, JDK-only types
- `TracingControlWireSchema` — immutable schema descriptor

**Существующие тесты для сохранения:** Все 8 тестов в `platform-tracing-api`.

**Тесты для дублирования:** Нет.

**Тесты для добавления:**

- `TracingControlWireValidatorTest` — валидация всех known keys, boundary conditions, rejection of unknown keys
- `TracingControlWireSchemaTest` — schema immutability, required/optional fields

**Benchmarks to run:** Нет.

**E2E/perf scenarios:** Нет.

**Behavior change:** нет
**Telemetry change:** нет
**Risk level:** low

**Rollback strategy:** revert новые классы в api.

**Acceptance criteria:**

- `TracingControlWireKeys` содержит все текущие JMX operation parameters (сверить с `PlatformTracingControl` методами)
- `TracingControlWireValidator` покрыт тестами на happy path и rejection
- Нет OTel или Spring зависимостей в новых классах
- PR-1 ArchUnit rules для api package проходят

**Must NOT be done in this PR:** Изменение `PlatformTracingControl`, `SamplingControlClient`, `PlatformTracingControlMBean`.

**Should this PR be split further:** нет

***

### PR-3 — Cross-CL JMX wire migration spike

**Spike ADR:** [ADR-jmx-wire-map-contract.md](ADR-jmx-wire-map-contract.md)

**Цель:** Провести технический spike и зафиксировать результат как ADR. Доказать или опровергнуть что validated Map/OpenMBean-compatible wire contract работает через CL boundary корректно в runtime. Существующее invoke-by-name поведение не трогается.

**Почему этот PR существует:** Инвентарь фиксирует `ClassLoaderVisibilitySpikeE2ETest` и `ClassLoaderVisibilitySpikeProbe` как уже существующие spike assets. PR-3 формализует их результаты и добавляет конкретные Map wire round-trip тесты.

**Модули:**

- `platform-tracing-otel-extension` (spike probe расширение)
- `platform-tracing-e2e-tests` (spike e2e тест расширение)

**Конкретные классы/пакеты:**

- `space.br1440.platform.tracing.otel.extension.factory.spike.ClassLoaderVisibilitySpikeProbe` — расширить
- Новый `MapWireRoundTripSpikeTest` в e2e-tests — тест что Map с validated keys проходит через MBeanServer.invoke без ClassCastException
- ADR документ: `ADR-jmx-wire-map-contract.md`

**Существующие тесты для сохранения:**

- `ClassLoaderVisibilitySpikeE2ETest` — ADAPT (добавить Map wire assertion)
- `PlatformTracingControlTest`, `SamplingControlClientTest` — MUST_KEEP без изменений

**Тесты для дублирования:** Нет.

**Тесты для добавления:**

- `MapWireRoundTripSpikeTest` — Map<String, Object> с primitives/String crosses MBean boundary
- `CompositeDataRoundTripSpikeTest` — альтернатива с CompositeData (для ADR сравнения)

**Benchmarks to run:** Нет (cold path).

**E2E/perf scenarios:** `RuntimeSamplingControlSmokeTest` — убедиться что не сломан.

**Behavior change:** нет (spike — production код не меняется)
**Telemetry change:** нет
**Risk level:** low

**Rollback strategy:** revert spike тесты. Существующий JMX путь не затронут.

**Acceptance criteria:**

- ADR зафиксирован с verdict: Map wire safe / unsafe / needs wrapper
- `ClassLoaderVisibilitySpikeE2ETest` зелёный
- `RuntimeSamplingControlSmokeTest` зелёный
- `PlatformTracingControlTest` зелёный

**Must NOT be done in this PR:** Изменение `PlatformTracingControl` или `SamplingControlClient` production кода.

**Should this PR be split further:** нет

***

### PR-4 — ArchUnit fitness functions: dependency enforcement

**Fitness functions doc:** [platform-tracing-fitness-functions-implementation.md](platform-tracing-fitness-functions-implementation.md)

**Цель:** Убрать `@ArchIgnore` на новые нарушения из PR-1 там где это уже можно сделать без рефактора кода. Добавить fitness function тесты для target dependency direction.

**Почему этот PR существует:** После PR-1, PR-2, PR-3 становится известна точная карта нарушений. PR-4 формализует enforcement.

**Модули:**

- `platform-tracing-test`
- `platform-tracing-core` (добавить `OtelDirectIntegrationArchTest` расширение)
- `platform-tracing-spring-boot-autoconfigure` (`OtelDirectIntegrationArchTest`)

**Конкретные классы/пакеты:**

- `TracingArchRules` — новые rules для target direction
- `OtelSdkArchRules` — расширение
- `space.br1440.platform.tracing.core.arch.OtelDirectIntegrationArchTest` — расширить (уже существует)
- `space.br1440.platform.tracing.autoconfigure.arch.OtelDirectIntegrationArchTest` — расширить

**Существующие тесты для сохранения:** Все ArchUnit тесты из PR-1.

**Тесты для добавления:**

- `AutoconfigureNoExtensionMainDepTest` — `platform-tracing-spring-boot-autoconfigure` не зависит от extension main классов
- `StarterExposesOnlyPublicApiTest` — проверка через Gradle dependency tree (Gradle task, не ArchUnit)

**Benchmarks to run:** Нет.

**E2E/perf scenarios:** `WebStackIsolationTest` — должен пройти.

**Behavior change:** нет
**Telemetry change:** нет
**Risk level:** low

**Rollback strategy:** revert новые ArchUnit rules.

**Acceptance criteria:**

- `AutoconfigureNoExtensionMainDepTest` зелёный
- `WebStackIsolationTest` зелёный
- Все существующие тесты зелёные
- `DuplicateSpansRegressionMatrixTest` (webmvc + webflux) зелёные

**Must NOT be done in this PR:** Изменение production code; удаление существующих `@ArchIgnore`.

**Should this PR be split further:** нет

***

### PR-5 — Дублирование критических тестов перед extraction

**Цель:** Создать дублирующий тестовый слой для sampling rules, scrubbing engine и validation policy в `platform-tracing-core` и `platform-tracing-test`. Эти тесты будут работать через pure Java interfaces (без OTel SDK), чтобы служить safety net при extraction.

**Почему этот PR существует:** Текущие тесты `CompositeSamplerTest`, `ScrubbingSpanProcessorTest`, `ValidatingSpanProcessorTest` работают через OTel Sampler/SpanProcessor API. После SPLIT_CORE_AND_ADAPTER policy классы переедут в core — нужны тесты которые проверяют policy logic без OTel callbacks.

**Модули:**

- `platform-tracing-core` (новые unit тесты на policy logic)
- `platform-tracing-test` (расширить `SamplerHarness`, `SpanProcessorHarness`)

**Конкретные классы/пакеты:**

- Новые тесты в `platform-tracing-core/src/test/java`:
    - `SamplingRuleChainCharacterizationTest` — тестирует `KillSwitchRule`, `ForceHeaderRule`, `QaTraceRule`, `RouteRatioRule`, `DefaultRatioRule`, `HardDropRule`, `ParentDecisionRule` через pure Java inputs (без OTel SDK)
    - `ScrubbingEngineCharacterizationTest` — тестирует scrubbing rule engine через `SpanAttributeScrubbingRule` SPI
    - `ValidationPolicyCharacterizationTest` — тестирует validation policy через `CategoryContracts`, `ValidationMode`
- `SamplerHarness` в `platform-tracing-test` — новые assertion helpers для rule chain

**Существующие тесты для сохранения:**

- `CompositeSamplerTest`, `CompositeSamplerEdgeCasesTest`, `RouteRatioTest`, `SamplerStateHolderTest` в otel-extension — MUST_KEEP без изменений
- `ScrubbingSpanProcessorTest`, `ScrubbingSecurityNegativeTest`, `MergeEngineTest` — MUST_KEEP
- `ValidatingSpanProcessorTest`, `ValidationPolicyRuntimeTest` — MUST_KEEP

**Тесты для добавления:** Перечислены выше — это и есть цель PR-5.

**Benchmarks to run:** `CompositeSamplerBenchmark`, `ScrubbingEngineBenchmark` — убедиться что baseline не изменился.

**E2E/perf scenarios:** `RuntimeSamplingControlSmokeTest`.

**Behavior change:** нет
**Telemetry change:** нет
**Risk level:** low-medium

**Rollback strategy:** revert новые тестовые классы.

**Acceptance criteria:**

- `SamplingRuleChainCharacterizationTest` покрывает все 7 правил включая их порядок
- `ScrubbingEngineCharacterizationTest` покрывает RegEx, circuit breaker, YAML loader, ServiceLoader
- `ScrubbingSecurityNegativeTest` в otel-extension проходит
- `CompositeSamplerBenchmark` baseline не изменился

**Must NOT be done in this PR:** Перемещение production классов; изменение package structure.

**Should this PR be split further:** да, если объём больше 400 строк тестового кода — split на PR-5A (sampling) и PR-5B (scrubbing/validation).

**PR-5A status (2026-06-12):** sampling characterization completed in otel-extension — see [platform-tracing-sampling-characterization.md](platform-tracing-sampling-characterization.md). Core-side duplication deferred until PR-6.

**PR-5B status (2026-06-12):** scrubbing/validation/enrichment characterization completed in otel-extension — see [platform-tracing-scrubbing-validation-characterization.md](platform-tracing-scrubbing-validation-characterization.md). Core-side duplication deferred until PR-7/PR-8.

***

### PR-6 — Extract sampling policy to pure core

**Цель:** Перенести pure policy классы (`KillSwitchRule`, `ForceHeaderRule`, `QaTraceRule`, `RouteRatioRule`, `DefaultRatioRule`, `HardDropRule`, `ParentDecisionRule`, `SamplerState`, `SamplerStateHolder`) в `platform-tracing-core`. `CompositeSampler` и `PlatformManagedSampler` остаются в `platform-tracing-otel-extension` как OTel adapters. `PlatformSamplerFactory` — `SPLIT_CORE_AND_ADAPTER`.

**Почему этот PR существует:** Target architecture требует pure policy core. Sampling rules — самый чистый кандидат для первого split: они не зависят от OTel SpanProcessor/Sampler interface напрямую, только от `SamplerState` и `DomainConfigHolder`.

**Модули:**

- `platform-tracing-core` (target для rule classes)
- `platform-tracing-otel-extension` (adapter остаётся, rule classes удаляются после переноса)

**Конкретные классы/пакеты:**

- MOVE to `space.br1440.platform.tracing.core.sampling`:
    - `KillSwitchRule`, `ForceHeaderRule`, `QaTraceRule`, `RouteRatioRule`, `DefaultRatioRule`, `HardDropRule`, `ParentDecisionRule`
    - `SamplerState`, `SamplerStateHolder` (если нет OTel deps — Requires manual review)
- KEEP in `platform-tracing-otel-extension`:
    - `CompositeSampler` (OTel Sampler callback — adapter)
    - `PlatformManagedSampler`, `PlatformManagedSamplers`
    - `PlatformSamplerProvider`, `PlatformSamplerBuilder` (OTel SPI)
    - `ForwardingSamplingResult` (OTel type)

**Существующие тесты для сохранения:**

- `CompositeSamplerTest` — MUST_KEEP в otel-extension, адаптировать импорты
- `RouteRatioTest`, `CompositeSamplerEdgeCasesTest`, `SamplerStateHolderTest` — MOVE копии в core, оригиналы остаются в extension
- `SamplerRuntimeUpdateConcurrencyTest` — MUST_KEEP в extension
- `CompositeSamplerConcurrentUpdateBenchmark` — MUST_KEEP в bench

**Тесты для дублирования до переноса кода:**

- `SamplingRuleChainCharacterizationTest` (из PR-5) должен быть зелёным ДО переноса

**Тесты для добавления:**

- `SamplerStateCoreTest` — unit test на `SamplerState` в core context без OTel SDK

**Benchmarks to run:**

- `CompositeSamplerBenchmark` — до и после
- `CompositeSamplerPolicyBranchesBenchmark` — до и после
- `CompositeSamplerConcurrentUpdateBenchmark` — до и после
- `jmhCompareBaseline` — delta должен быть в пределах noise

**E2E/perf scenarios:**

- `RuntimeSamplingControlSmokeTest` — MUST pass
- M5 macro scenario — запустить, сравнить с PR-0 baseline

**Behavior change:** нет (рефактор — policy logic идентична)
**Telemetry change:** нет
**Risk level:** high

**Rollback strategy:** git revert всего PR-6. Оригинальные rule classes в otel-extension восстанавливаются. Тесты PR-5 остаются.

**Acceptance criteria:**

- Все 213 тестов зелёные
- `CompositeSamplerBenchmark` delta ≤ noise vs PR-0 baseline
- `RuntimeSamplingControlSmokeTest` зелёный
- ArchUnit: `CoreNoDependencyOnOtelRule` зелёный для новых core.sampling классов
- `platform-tracing-otel-extension` `CompositeSampler` делегирует в core rule classes

**Must NOT be done in this PR:** Перемещение `ScrubbingSpanProcessor`; изменение `SamplingControlClient`; изменение JMX wire.

**Should this PR be split further:** да — PR-6A (перемещение rule value objects + state), PR-6B (адаптация CompositeSampler для делегирования).

***

### PR-7 — Extract scrubbing rule engine to pure core

**Цель:** Перенести scrubbing engine (`scrubbing.engine.*`, `scrubbing.loader.*`, `BuiltInRules`, `ScrubbingPolicyHolder`, `ScrubbingSnapshot`) в `platform-tracing-core`. `ScrubbingSpanProcessor` остаётся в `platform-tracing-otel-extension` как OTel SpanProcessor adapter.

**Почему этот PR существует:** Scrubbing engine — policy logic, не зависящая от OTel SpanProcessor callback. `SpanAttributeScrubbingRule` SPI уже в `platform-tracing-api`. Engine может тестироваться pure Java.

**Модули:**

- `platform-tracing-core` (новый `core.scrubbing` package)
- `platform-tracing-otel-extension` (processor adapter остаётся)

**Конкретные классы/пакеты:**

- MOVE to `space.br1440.platform.tracing.core.scrubbing`:
    - `scrubbing.engine.*` — весь rule evaluation engine
    - `scrubbing.loader.*` — YAML и ServiceLoader loaders
    - `BuiltInRules`, `ScrubbingPolicyHolder`, `ScrubbingSnapshot`
    - `RuleCircuitBreaker`, `KeyMatcher`
- KEEP in `platform-tracing-otel-extension`:
    - `ScrubbingSpanProcessor` (OTel SpanProcessor callback)

**Существующие тесты для сохранения:**

- `ScrubbingSpanProcessorTest`, `ScrubbingSpanProcessorAdvancedTest`, `ScrubbingSecurityNegativeTest` — MUST_KEEP в otel-extension
- `MergeEngineTest`, `RuleCircuitBreakerTest`, `ExtensionRuleLoaderTest`, `ServiceLoaderSpanAttributeScrubbingRuleTest` — MOVE копии в core, оригиналы остаются

**Тесты для дублирования до переноса кода:**

- `ScrubbingEngineCharacterizationTest` (из PR-5) зелёный

**Тесты для добавления:**

- `ScrubbingEngineCoreTest` — повторяет `MergeEngineTest` + `RuleCircuitBreakerTest` в core context

**Benchmarks to run:**

- `ScrubbingEngineBenchmark` — до и после
- `ScrubbingPerRuleBenchmark` — до и после
- `jmhCompareBaseline`

**E2E/perf scenarios:**

- `ExceptionEventScrubbingE2ETest` — MUST pass
- `RuntimeSamplingControlSmokeTest` — MUST pass
- M5 delta check

**Behavior change:** нет
**Telemetry change:** нет
**Risk level:** critical

**Rollback strategy:** revert PR-7. `ScrubbingSpanProcessor` остаётся working так как engine классы не удалены из extension до validation.

**Acceptance criteria:**

- `ScrubbingSecurityNegativeTest` зелёный
- `ScrubbingEngineBenchmark` delta ≤ noise vs PR-0
- `ExceptionEventScrubbingE2ETest` зелёный
- ArchUnit `CoreNoDependencyOnOtelRule` зелёный для `core.scrubbing`
- scrubbing остаётся mandatory — никакого conditional флага в этом PR

**Must NOT be done in this PR:** Изменение mandatory/optional статуса `ScrubbingSpanProcessor`; перемещение `ValidatingSpanProcessor`; любые изменения hot path без benchmark.

**Should this PR be split further:** да — PR-7A (engine/loader/policyHolder), PR-7B (ScrubbingSpanProcessor adapter clean-up).

***

### PR-8 — Extract validation/enrichment policy to pure core

**Цель:** По аналогии с PR-6 и PR-7 — перенести `ValidationPolicyHolder`, `ValidationSnapshot` и enrichment policy в `platform-tracing-core`. `ValidatingSpanProcessor`, `EnrichingSpanProcessor` остаются в extension как adapters.

**Почему этот PR существует:** Validation и enrichment — optional tier, менее рискованный для split чем sampling/scrubbing. PR-8 завершает трёхэтапную policy extraction.

**Модули:**

- `platform-tracing-core` (новые `core.validation`, `core.enrichment` packages)
- `platform-tracing-otel-extension` (processor adapters остаются)

**Конкретные классы/пакеты:**

- MOVE to `core.validation`: `ValidationPolicyHolder`, `ValidationSnapshot` (если без OTel deps — Requires manual review)
- MOVE to `core.enrichment`: enrichment policy classes
- KEEP in extension: `ValidatingSpanProcessor`, `EnrichingSpanProcessor`, `ClassificationSpanProcessor`

**Существующие тесты для сохранения:**

- `ValidatingSpanProcessorTest`, `ValidationPolicyRuntimeTest` — MUST_KEEP в extension
- `EnrichingSpanProcessorTest` — MUST_KEEP в extension

**Тесты для дублирования до переноса кода:**

- `ValidationPolicyCharacterizationTest` (из PR-5) зелёный

**Benchmarks to run:**

- `CompositePipelineBenchmark` — до и после (pipeline включает validate/enrich)
- `AttributePolicyBenchmark`

**E2E/perf scenarios:**

- `RuntimeSamplingControlSmokeTest`
- M5 delta

**Behavior change:** нет
**Telemetry change:** нет
**Risk level:** medium

**Rollback strategy:** revert PR-8.

**Acceptance criteria:**

- Все тесты зелёные
- `CompositePipelineBenchmark` delta ≤ noise
- ArchUnit core purity rules зелёные

**Must NOT be done in this PR:** Изменение tiered pipeline defaults (это PR-12); отключение validation по умолчанию.

**Should this PR be split further:** нет, если объём умеренный.

***

### PR-9 — Thin OTel adapters refactor: factory clean-up

**Цель:** После PR-6/7/8 оtel-extension содержит тонкие adapters. PR-9 очищает `PlatformSamplerFactory`, `PlatformSpanProcessorFactory`, `PlatformExportProcessorFactory` от embedded policy logic, делая их pure wiring. Добавляет `platform-tracing-otel-extension` зависимость от `platform-tracing-core` для policy classes.

**Почему этот PR существует:** После split factories должны делегировать в core, а не содержать inline policy. Сейчас `PlatformSamplerFactory` создаёт и конфигурирует rule chain — это должно остаться в core.

**Модули:**

- `platform-tracing-otel-extension`
- `platform-tracing-core` (добавить как compile dependency в extension build.gradle)

**Конкретные классы/пакеты:**

- `PlatformSamplerFactory` — refactor (SPLIT_CORE_AND_ADAPTER)
- `PlatformSpanProcessorFactory` — refactor
- `PlatformExportProcessorFactory` — refactor
- `PlatformAutoConfigurationCustomizer` — minor update для delegation

**Существующие тесты для сохранения:**

- `PlatformSamplerProviderTest` — ADAPT (delegation chain)
- `PlatformAutoConfigurationCustomizerTest` — MUST_KEEP

**Benchmarks to run:**

- `CompositeSamplerBenchmark`, `ScrubbingEngineBenchmark`, `CompositePipelineBenchmark`

**E2E/perf scenarios:**

- `BspDropOldestSafetyAgentSmokeTest` — MUST pass
- `RuntimeSamplingControlSmokeTest`

**Behavior change:** нет
**Telemetry change:** нет
**Risk level:** medium

**Rollback strategy:** revert PR-9.

**Acceptance criteria:**

- `verifyAgentJarContents` Gradle task зелёный
- `verifyExtensionSpiRegistration` Gradle task зелёный
- Все 78 тестов в otel-extension зелёные
- SPI классы зарегистрированы

**Must NOT be done in this PR:** Изменение JMX wire; добавление Spring deps в extension.

**Should this PR be split further:** нет.

***

### PR-10 — Desired State Config Reconciler (TracingConfigReconciler skeleton)

**Цель:** Создать `TracingConfigReconciler` как новый класс в `platform-tracing-spring-boot-autoconfigure`, работающий параллельно с существующим `RuntimeConfigApplier`. Новый reconciler — дополнительный слой, не замена. RefreshScope путь не трогается.

**Почему этот PR существует:** `TracingConfigReconciler` не найден в кодовой базе. Это единственный target компонент требующий создания с нуля. PR-10 создаёт skeleton с минимальной функциональностью и интегрирует его рядом с `RuntimeConfigApplier`.

**Модули:**

- `platform-tracing-spring-boot-autoconfigure`

**Конкретные классы/пакеты:**

- Новый `space.br1440.platform.tracing.autoconfigure.configsource.TracingConfigReconciler`
- Новый `TracingDesiredState` value object
- Новый `TracingApplyResult` value object
- Интеграция с `SamplingControlClient` (через инъекцию, без изменения клиента)
- `TracingRefreshScopeAutoConfiguration` — добавить conditional bean для reconciler

**Существующие тесты для сохранения:**

- `RuntimeConfigApplierTest` — MUST_KEEP без изменений
- `DualChannelDriftDiagnosticsTest` — MUST_KEEP

**Тесты для добавления:**

- `TracingConfigReconcilerTest` — unit test через мок `SamplingControlClient`
- `TracingDesiredStateTest` — validation, immutability
- `ReconcilerParallelPathTest` — RefreshScope path и reconciler path оба работают независимо

**Benchmarks to run:** Нет (cold path).

**E2E/perf scenarios:**

- `RuntimeSamplingControlSmokeTest` — MUST pass (RefreshScope path не сломан)

**Behavior change:** нет (reconciler default-disabled при первом merge)
**Telemetry change:** нет
**Risk level:** medium

**Rollback strategy:** `@ConditionalOnProperty(name = "platform.tracing.reconciler.enabled", havingValue = "true")` — default false. Revert просто убирает класс.

**Acceptance criteria:**

- `RuntimeConfigApplierTest` зелёный без изменений
- `TracingConfigReconcilerTest` зелёный
- Reconciler по умолчанию не активен в prod (conditional off)
- `DualChannelDriftDiagnosticsTest` зелёный

**Must NOT be done in this PR:** Удаление `RuntimeConfigApplier`; изменение RefreshScope поведения; активация reconciler по умолчанию.

**Should this PR be split further:** нет.

***

### PR-11 — Production READ-only Actuator + dev-only mutation guard

**Цель:** Добавить production guard для `TracingActuatorEndpoint` WriteOperation. Mutation endpoint должен быть отключён в prod profile.

**Почему этот PR существует:** Инвентарь явно фиксирует: `"Current Actuator MUTATION exposure: YES — no prod disable guard found — MIGRATION_RISK"`. `TracingActuatorEndpoint` регистрирует `@WriteOperation` без conditional guard.

**Модули:**

- `platform-tracing-spring-boot-autoconfigure`

**Конкретные классы/пакеты:**

- `space.br1440.platform.tracing.autoconfigure.actuator.TracingActuatorEndpoint` — добавить `@ConditionalOnProperty` или Spring profile guard на WriteOperation bean
- Новый `TracingActuatorMutationAutoConfiguration` — отдельный `@AutoConfiguration` только для mutation, с `@ConditionalOnNotProductionProfile` или property guard
- `TracingActuatorAutoConfiguration` — оставить только READ часть безусловной

**Существующие тесты для сохранения:**

- `TracingActuatorEndpointTest` — ADAPT (проверить что WriteOperation недоступен в prod)
- `TracingActuatorEndpointProcessorErrorsTest` — MUST_KEEP
- `DropOldestAspirationDiagnosticsTest` — MUST_KEEP

**Тесты для добавления:**

- `TracingActuatorMutationDisabledInProdTest` — Spring Boot test с `spring.profiles.active=production`, Assert что POST `/actuator/tracing/*` возвращает 404 или 403

**Benchmarks to run:** Нет.

**E2E/perf scenarios:**

- `RuntimeSamplingControlSmokeTest` — MUST pass (если smoke использует Actuator write, адаптировать для dev profile)

**Behavior change:** да — Actuator mutation недоступна в prod profile
**Telemetry change:** нет
**Risk level:** high (security change)

**Rollback strategy:** revert guard, mutation возвращается как сейчас. SRE должны быть уведомлены.

**Acceptance criteria:**

- `TracingActuatorMutationDisabledInProdTest` зелёный
- GET `/actuator/tracing` (READ) работает в prod
- POST `/actuator/tracing/*` возвращает 404 или 403 в prod
- `OtelEffectiveConfigSnapshotTest` зелёный (READ shape не изменился)

**Must NOT be done in this PR:** Изменение JMX path; изменение READ endpoint shape; удаление mutation endpoint полностью.

**Should this PR be split further:** нет, но требует security review.

***

### PR-12 — Tiered pipeline defaults + perf validation (E6 gate)

**Цель:** Явно закодировать tiered pipeline: baseline (sampling + mandatory scrubbing + export) vs optional tier (validation, enrichment, diagnostics). Heavy processors (`SpanWatchdogProcessor`, `MetricsSpanProcessor`, `ClassificationSpanProcessor`) должны быть `default-off` и активироваться через `platform.tracing.*.enabled=true`.

**Почему этот PR существует:** Текущая инициализация pipeline в `PlatformSpanProcessorFactory` и `PlatformAutoConfigurationCustomizer` не имеет явного разграничения baseline vs optional. PR-12 делает это разграничение видимым в коде и конфигурации.

**Модули:**

- `platform-tracing-otel-extension`
- `platform-tracing-spring-boot-autoconfigure` (property defaults)

**Конкретные классы/пакеты:**

- `PlatformSpanProcessorFactory` — explicit conditional для optional processors
- `PlatformAutoConfigurationCustomizer` — порядок processor registration
- `ExtensionPropertyNames` / `ExtensionDefaults` / `PlatformTracingDefaultsProvider` — verify default values для validation.enabled, enriching.enabled, watchdog.enabled
- `TracingProperties` — verify default values (должны совпасть с agent extension defaults)

**Существующие тесты для сохранения:**

- `CompositePipelineBenchmark` — MUST pass с проверкой что baseline pipeline быстрее full pipeline
- `TracingAutoConfigurationTest` — MUST_KEEP
- `SharedDefaultsAlignmentTest` — MUST pass (alignment между TracingProperties и agent extension defaults)

**Тесты для добавления:**

- `TieredPipelineDefaultsTest` — Spring Boot test: без explicit config собирается только baseline pipeline
- `TieredPipelineOptInTest` — с property enabled=true подключается optional processor

**Benchmarks to run:**

- Все 16 JMH benchmarks — полный прогон
- `jmhCompareBaseline` vs PR-0 baseline — GATE
- `PerformanceReleaseGateTest` — GATE

**E2E/perf scenarios:**

- M5 macro scenario — сравнить с PR-0 FAIL baseline, задокументировать delta
- M0 calibration run
- `RuntimeSamplingControlSmokeTest`

**Behavior change:** да — heavy processors по умолчанию off (если они были on до этого)
**Telemetry change:** возможно (меньше spans/attributes при default config)
**Risk level:** high

**Rollback strategy:** revert processor defaults. `ExistingDefaultsPreservationTest` — добавить перед PR-12 что фиксирует текущие defaults.

**Acceptance criteria:**

- `PerformanceReleaseGateTest` зелёный
- `CompositePipelineBenchmark` baseline pipeline ≤ budget из `performance-budgets.yaml`
- `SharedDefaultsAlignmentTest` зелёный
- M5 delta документирован (улучшение или объяснение почему нет)
- scrubbing включён в baseline pipeline при любых настройках

**Must NOT be done in this PR:** Удаление любого processor; изменение sampling chain; изменение export queue behavior.

**Should this PR be split further:** да — PR-12A (property defaults audit), PR-12B (pipeline conditional wiring), PR-12C (E6 perf validation run).

***

### PR-13 — Final clean-up + deferred collapse review

**Цель:** Удалить temporary дублирование тестов (если оно было создано как `*Characterization*` в PR-5 и оригиналы безопасно покрыты), очистить `@ArchIgnore` где нарушения исправлены, провести review решения о module collapse.

**Почему этот PR существует:** После 12 PR накапливается technical debt от осторожного подхода: временные тесты, `@ArchIgnore`, TODO комментарии. PR-13 — cleanup pass.

**Behavior change:** нет
**Telemetry change:** нет
**Risk level:** low

**Acceptance criteria:**

- Все ArchUnit tests зелёные без `@ArchIgnore` на новые нарушения
- `DEVELOPERS.md` обновлён
- Module collapse decision documented (даже если решение "не коллапсировать сейчас")

**Must NOT be done in this PR:** Коллапс модулей без явного committee approval.

***

## 4. Таблица миграции классов/пакетов

| Current class/package | Current module | Current role | Target module | Migration action | Tests required before move | Benchmarks required | Risk | Recommended PR |
| :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- |
| `CompositeSampler` | otel-extension | Head sampling OTel adapter | otel-extension (adapter остаётся) | KEEP_IN_OTEL_EXTENSION_ADAPTER | `CompositeSamplerTest` дублирован в core | `CompositeSamplerBenchmark` | HIGH | PR-6 |
| `KillSwitchRule`, `ForceHeaderRule`, `QaTraceRule`, `RouteRatioRule`, `DefaultRatioRule`, `HardDropRule`, `ParentDecisionRule` | otel-extension | Sampling policy rules | platform-tracing-core | MOVE_TO_CORE | `SamplingRuleChainCharacterizationTest` (PR-5) | `CompositeSamplerPolicyBranchesBenchmark` | HIGH | PR-6 |
| `SamplerState` | otel-extension | Sampling state value object | platform-tracing-core | MOVE_TO_CORE | `SamplerStateHolderTest` существует | `CompositeSamplerConcurrentUpdateBenchmark` | HIGH | PR-6 |
| `SamplerStateHolder` | otel-extension | Atomic config state | platform-tracing-core (Requires manual review — проверить OTel deps) | SPLIT_CORE_AND_ADAPTER | `SamplerStateHolderTest`, `SamplerRuntimeUpdateConcurrencyTest` | `CompositeSamplerConcurrentUpdateBenchmark` | HIGH | PR-6 |
| `DomainConfigHolder` | api | Versioned atomic holder | api (уже на месте) | KEEP_AS_IS | `DomainConfigHolderTest` | нет | LOW | — |
| `ScrubbingSpanProcessor` | otel-extension | OTel SpanProcessor callback | otel-extension (adapter) | KEEP_IN_OTEL_EXTENSION_ADAPTER | `ScrubbingSpanProcessorTest` MUST_KEEP | `ScrubbingEngineBenchmark` | CRITICAL | PR-7 |
| `scrubbing.engine.*` | otel-extension | Scrubbing rule evaluation | platform-tracing-core | MOVE_TO_CORE | `ScrubbingEngineCharacterizationTest` (PR-5) | `ScrubbingEngineBenchmark`, `ScrubbingPerRuleBenchmark` | CRITICAL | PR-7 |
| `scrubbing.loader.*` | otel-extension | YAML/ServiceLoader rule loading | platform-tracing-core | MOVE_TO_CORE | `ExtensionRuleLoaderTest`, `ServiceLoaderSpanAttributeScrubbingRuleTest` | нет | HIGH | PR-7 |
| `BuiltInRules` | otel-extension | Built-in scrubbing rules | platform-tracing-core | MOVE_TO_CORE | `BuiltInRulesTest` | нет | HIGH | PR-7 |
| `ScrubbingPolicyHolder`, `ScrubbingSnapshot` | otel-extension | Scrubbing config state | platform-tracing-core | MOVE_TO_CORE | `MergeEngineTest` | нет | HIGH | PR-7 |
| `RuleCircuitBreaker` | otel-extension | Per-rule safety | platform-tracing-core | MOVE_TO_CORE | `RuleCircuitBreakerTest` | нет | HIGH | PR-7 |
| `ValidatingSpanProcessor` | otel-extension | OTel adapter для validation | otel-extension (adapter) | KEEP_IN_OTEL_EXTENSION_ADAPTER | `ValidatingSpanProcessorTest` | `CompositePipelineBenchmark` | MEDIUM | PR-8 |
| `ValidationPolicyHolder`, `ValidationSnapshot` | otel-extension | Validation config state | platform-tracing-core (Requires manual review — OTel deps) | SPLIT_CORE_AND_ADAPTER | `ValidationPolicyRuntimeTest` | нет | MEDIUM | PR-8 |
| `EnrichingSpanProcessor` | otel-extension | OTel adapter для enrichment | otel-extension (adapter) | KEEP_IN_OTEL_EXTENSION_ADAPTER | `EnrichingSpanProcessorTest` | `CompositePipelineBenchmark` | MEDIUM | PR-8 |
| `PlatformCompositeSpanProcessor` | otel-extension | Processor chain orchestration | otel-extension | KEEP_IN_OTEL_EXTENSION_ADAPTER | `CompositePipelineBenchmark` | `CompositePipelineBenchmark` | MEDIUM | PR-9 |
| `PlatformDropOldestExportSpanProcessor` | otel-extension | Export queue drop-oldest | otel-extension | KEEP_IN_OTEL_EXTENSION_ADAPTER | `PlatformDropOldestExportSpanProcessorTest` | `QueueOfferBenchmark` | HIGH | — |
| `SafeSpanExporter` | otel-extension | Fail-safe export wrapper | otel-extension | KEEP_IN_OTEL_EXTENSION_ADAPTER | `SafeSpanExporterTest` | нет | HIGH | — |
| `PlatformTracingControl` | otel-extension | JMX MBean server (agent side) | otel-extension | KEEP_IN_OTEL_EXTENSION_ADAPTER | `PlatformTracingControlTest` | нет | HIGH | PR-3 spike, затем post-PR-3 wire |
| `PlatformTracingControlMBean` | otel-extension | MBean interface | otel-extension (+ api.wire schema) | KEEP_IN_OTEL_EXTENSION_ADAPTER | `PlatformTracingControlTest` | нет | HIGH | PR-2, PR-3 |
| `SamplingControlClient` | autoconfigure | JMX client App CL | platform-tracing-spring-boot-autoconfigure | KEEP_IN_SPRING_AUTOCONFIGURE | `SamplingControlClientTest` | нет | HIGH | — |
| `TracingActuatorEndpoint` | autoconfigure | GET + POST actuator | platform-tracing-spring-boot-autoconfigure | KEEP_IN_SPRING_AUTOCONFIGURE + refactor | `TracingActuatorEndpointTest` ADAPT | нет | HIGH | PR-11 |
| `TracingProperties` | autoconfigure | All platform.tracing.* | platform-tracing-spring-boot-autoconfigure | KEEP_IN_SPRING_AUTOCONFIGURE | `TracingPropertiesBindingTest` | нет | HIGH | — |
| `DualChannelDriftDiagnostics` | autoconfigure | Spring vs agent drift | platform-tracing-spring-boot-autoconfigure | KEEP_IN_SPRING_AUTOCONFIGURE | `DualChannelDriftDiagnosticsTest` | нет | MEDIUM | PR-10 |
| `RuntimeConfigApplier` | autoconfigure | RefreshScope → JMX apply | platform-tracing-spring-boot-autoconfigure | KEEP_IN_SPRING_AUTOCONFIGURE | `RuntimeConfigApplierTest` | нет | HIGH | PR-10 |
| `TracingRefreshScopeAutoConfiguration` | autoconfigure | @RefreshScope wiring | platform-tracing-spring-boot-autoconfigure | KEEP_IN_SPRING_AUTOCONFIGURE | `RuntimeConfigApplierTest` | нет | HIGH | — |
| `TracingConfigReconciler` | — | — | platform-tracing-spring-boot-autoconfigure | DEFER (new — не существует) | N/A | нет | — | PR-10 |
| `PlatformAutoConfigurationCustomizer` | otel-extension | Agent SPI entry point | otel-extension | KEEP_IN_OTEL_EXTENSION_ADAPTER | `PlatformAutoConfigurationCustomizerTest` | нет | HIGH | PR-9 |
| `PlatformSamplerFactory` | otel-extension | Builds CompositeSampler | otel-extension (тонкий adapter) | SPLIT_CORE_AND_ADAPTER | `PlatformSamplerProviderTest` | `CompositeSamplerBenchmark` | HIGH | PR-6/PR-9 |
| `PlatformSpanProcessorFactory` | otel-extension | Builds processor chain | otel-extension | KEEP_IN_OTEL_EXTENSION_ADAPTER | processor tests | `CompositePipelineBenchmark` | MEDIUM | PR-9 |
| `DefaultPlatformTracing` | core | Main PlatformTracing impl | platform-tracing-core (OTel facade — clean-up defer) | TEST_BEFORE_MOVE | `DefaultPlatformTracingTest`, `DefaultPlatformTracingInSpanTest` | `TypedBuilderBenchmark` | HIGH | DEFER (post PR-13) |
| `AttributePolicy` | core | Attribute allow/deny/eager | platform-tracing-core | KEEP_AS_IS | `AttributePolicyTest` | `AttributePolicyBenchmark` | LOW | — |
| `ServletTracingAutoConfiguration` | webmvc | Servlet stack autoconfig | platform-tracing-autoconfigure-webmvc | KEEP_IN_WEBMVC_AUTOCONFIGURE | `WebStackIsolationTest`, `DuplicateSpansRegressionMatrixTest` | нет | MEDIUM | — |
| `ReactiveTracingAutoConfiguration` | webflux | Reactive stack autoconfig | platform-tracing-autoconfigure-webflux | KEEP_IN_WEBFLUX_AUTOCONFIGURE | `ReactorContextPropagationIntegrationTest` | нет | MEDIUM | — |
| `TracingReactorEagerInitConfiguration` | webflux | Reactor Hooks eager init | platform-tracing-autoconfigure-webflux | KEEP_IN_WEBFLUX_AUTOCONFIGURE | `TracingReactorEagerInitConfigurationTest` | нет | HIGH | — |
| `TraceResponseHeaderServletFilter` | webmvc | X-Trace-* response headers | platform-tracing-autoconfigure-webmvc | KEEP_IN_WEBMVC_AUTOCONFIGURE | `TraceResponseHeaderServletFilterTest` | нет | MEDIUM | — |
| `TraceResponseHeaderWebFilter` | webflux | Reactive response headers | platform-tracing-autoconfigure-webflux | KEEP_IN_WEBFLUX_AUTOCONFIGURE | `MdcPropagationWebFluxIntegrationTest` | нет | MEDIUM | — |
| Стартеры (build.gradle) | starter-servlet, starter-reactive | Dependency aggregators | starter-servlet, starter-reactive | KEEP_IN_STARTER | starter classpath smoke | нет | HIGH | — |
| JMH benchmarks (16 классов) | bench | Micro perf | bench | KEEP_IN_BENCH | `PerformanceReleaseGateTest` | все 16 JMH | HIGH | PR-0 freeze |
| E2E тесты (42 класса) | e2e-tests | Integration evidence | e2e-tests | KEEP_IN_E2E_TESTS | — | — | HIGH | — |
| Perf SUT + scripts | perf-tests | Macro perf M0–M10 | perf-tests | KEEP_IN_PERF_TESTS | — | M0–M10 | HIGH | — |
| `InMemorySpanExporter`, `SamplerHarness`, `SpanProcessorHarness`, ArchUnit rules | test | Test support | test | KEEP_IN_TEST_SUPPORT | — | — | MEDIUM | — |
| `PerfAdminController` | perf-tests | /perf/admin → JMX for M10 | perf-tests | KEEP_IN_PERF_TESTS | — | M10 scenarios | MEDIUM | — |
| `CategoryContracts`, `SemconvKeys`, `PlatformAttributes`, `PlatformSamplingReasons` | api | Public semconv | api | KEEP_AS_IS | `CategoryContractsTest` | нет | LOW | — |
| `SpanAttributeScrubbingRule` SPI | api | Scrubbing SPI | api | KEEP_AS_IS | `ServiceLoaderSpanAttributeScrubbingRuleTest` | нет | LOW | — |
| `DegradedModeController`, `CircuitBreaker`, `TokenBucketRateLimiter` | otel-extension | Safety infrastructure | otel-extension | KEEP_IN_OTEL_EXTENSION_ADAPTER | `DegradedModeControllerTest` | нет | HIGH | — |
| `ExtensionPropertyNames` / `ExtensionDefaults` / `PlatformTracingDefaultsProvider` | otel-extension | Agent-side configuration | otel-extension | KEEP_IN_OTEL_EXTENSION_ADAPTER | `ExtensionConfigTest`, `PlatformTracingDefaultsProviderTest`, `SharedDefaultsAlignmentTest` | нет | HIGH | DONE |
| `ClassLoaderVisibilitySpikeProbe` | otel-extension | CL spike (removed) | — | **DONE** (2026-06-17) | `ClassLoaderVisibilityE2ETest` (F1), `CustomRuleSmokeE2ETest` (F3) | нет | LOW | TEST_ONLY_PROBE |


***

## 5. Module migration strategy

### `platform-tracing-api`

**Current role:** Публичные контракты — `PlatformTracing` interface, typed span builders, semconv keys, propagation, `DomainConfigHolder`, `SpanAttributeScrubbingRule` SPI. 59 main классов.

**Target role:** Без изменений плюс добавление wire schema (PR-2).

**Public/internal/verification:** public

**Keep module:** да

**Hide behind starter:** нет — прямая зависимость для app-code нужна при явном использовании API

**Collapse now:** нет

**Migration risk:** HIGH — backward compatibility; `compileOnly OTel` — MIGRATION_RISK (потребует осторожного split если OTel types уберут из public API)

**Required guardrails:** binary compatibility check при каждом PR; ArchUnit rule что api не тянет Spring

**Recommended PRs:** PR-2 (wire schema), PR-13 (OTel compileOnly review)

***

### `platform-tracing-core`

**Current role:** `DefaultPlatformTracing` facade, typed span builder implementations, `AttributePolicy`. 30 main классов. OTel-coupled сегодня — НЕ pure policy core.

**Target role:** Narrow pure-Java policy engine: sampling rules, scrubbing engine, validation policy, enrichment policy. OTel facade временно остаётся.

**Public/internal/verification:** internal (транзитивный через autoconfigure)

**Keep module:** да

**Hide behind starter:** нет напрямую — но app teams не должны deklarировать прямую зависимость

**Collapse now:** нет

**Migration risk:** HIGH — OTel `api` dep нарушает target direction; требует SPLIT_CORE_AND_ADAPTER

**Required guardrails:** `CoreNoDependencyOnOtelRule` (новые packages); `CoreNoDependencyOnSpringRule`

**Recommended PRs:** PR-5 (dup tests), PR-6 (sampling), PR-7 (scrubbing), PR-8 (validation/enrichment), PR-13 (OTel facade clean-up review)

***

### `platform-tracing-otel-extension`

**Current role:** Java Agent extension — `CompositeSampler`, processor chain, `SafeSpanExporter`, JMX MBean server, SPI. 99 main классов. Наиболее ценный production runtime.

**Target role:** Thin OTel SPI adapters + private JMX. Policy logic переедет в core.

**Public/internal/verification:** internal (agent deployment)

**Keep module:** да

**Hide behind starter:** нет — agent jar деплоится отдельно через `otel.javaagent.extensions`

**Collapse now:** нет

**Migration risk:** HIGH — 99 классов, 78 тестов, agent jar build (`verifyAgentJarContents`, `verifyExtensionSpiRegistration`)

**Required guardrails:** `verifyAgentJarContents` task в каждом PR касающемся extension; `ExtensionNoSpringDependencyArchTest`

**Recommended PRs:** PR-3 (JMX spike), PR-6 (sampler split), PR-7 (scrubbing split), PR-8 (validation split), PR-9 (factory clean-up)

***

### `platform-tracing-spring-boot-autoconfigure`

**Current role:** `TracingProperties`, 13 AutoConfiguration классов, Actuator, `SamplingControlClient`, RefreshScope. 46 main классов.

**Target role:** Плюс `TracingConfigReconciler` (новый), Actuator READ prod-safe, JMX client.

**Public/internal/verification:** internal (транзитивный через стартеры)

**Keep module:** да

**Hide behind starter:** да — app teams не должны объявлять прямую dep

**Collapse now:** нет

**Migration risk:** HIGH — `TracingProperties` 700+ строк; Actuator mutation exposure; RefreshScope behavior

**Required guardrails:** `TracingPropertiesBindingTest` в каждом PR; `SharedDefaultsAlignmentTest`; prod mutation guard (PR-11)

**Recommended PRs:** PR-10 (reconciler), PR-11 (mutation guard), PR-12 (defaults)

***

### `platform-tracing-autoconfigure-webmvc`

**Current role:** Servlet stack — `ServletTracingAutoConfiguration`, filter, MVC conventions, outbound interceptor. 7 main классов.

**Target role:** Без изменений.

**Public/internal/verification:** internal

**Keep module:** да

**Hide behind starter:** да — через `platform-tracing-spring-boot-starter-servlet`

**Collapse now:** нет

**Migration risk:** MEDIUM — `WebStackIsolationTest` защищает; не трогать до PR-13 review

**Required guardrails:** `WebStackIsolationTest`, `DuplicateSpansRegressionMatrixTest` в каждом PR

**Recommended PRs:** PR-4 (ArchUnit), PR-13 (collapse review)

***

### `platform-tracing-autoconfigure-webflux`

**Current role:** Reactive stack — `ReactiveTracingAutoConfiguration`, `TracingReactorEagerInitConfiguration`, WebFilter, Reactor context propagation. 11 main классов.

**Target role:** Без изменений.

**Public/internal/verification:** internal

**Keep module:** да

**Hide behind starter:** да — через `platform-tracing-spring-boot-starter-reactive`

**Collapse now:** нет

**Migration risk:** MEDIUM-HIGH — `TracingReactorEagerInitConfiguration` на hot path Reactor context

**Required guardrails:** `ReactorContextPropagationIntegrationTest`, `TracingReactorEagerInitConfigurationTest`

**Recommended PRs:** PR-4 (ArchUnit), PR-13

***

### `platform-tracing-spring-boot-starter-servlet`

**Current role:** Public starter для Spring MVC/Servlet. 0 Java классов.

**Target role:** Без изменений.

**Public/internal/verification:** public

**Keep module:** да

**Hide behind starter:** N/A — это и есть starter

**Collapse now:** нет

**Migration risk:** HIGH (потребители зависят явно)

**Required guardrails:** Smoke test что dependency graph не изменился

**Recommended PRs:** PR-0 (snapshot dependency graph)

***

### `platform-tracing-spring-boot-starter-reactive`

**Current role:** Public starter для WebFlux. 0 Java классов.

**Target role:** Без изменений.

**Public/internal/verification:** public

**Keep module:** да

**Migration risk:** HIGH

**Required guardrails:** Smoke test dependency graph

**Recommended PRs:** PR-0

***

### `platform-tracing-bench`

**Current role:** 16 JMH benchmark классов + 3 contract tests. `PerformanceReleaseGateTest`, `jmhSaveBaseline`/`jmhCompareBaseline`.

**Target role:** Без изменений плюс поддержка новых core package imports после split.

**Public/internal/verification:** verification

**Keep module:** да

**Collapse now:** нет

**Migration risk:** HIGH — benchmark comparability критична; package moves в core меняют JMH class paths

**Required guardrails:** Benchmark names не меняются; `jmhCompareBaseline` gate в PR-6, PR-7, PR-8, PR-12

**Recommended PRs:** PR-0 (baseline), PR-6 (update imports post-split), PR-12 (E6 gate)

***

### `platform-tracing-bom`

**Current role:** Version alignment BOM.

**Target role:** Без изменений.

**Migration risk:** LOW

**Required guardrails:** нет изменений без явного reason

**Recommended PRs:** PR-0 (snapshot versions)

***

### `platform-tracing-e2e-tests`

**Current role:** 42 тестовых класса — Testcontainers, Agent smoke, `RuntimeSamplingControlSmokeTest`.

**Target role:** Без изменений плюс новые smoke tests для reconciler (post PR-10).

**Migration risk:** HIGH — E1/E2 evidence gates

**Required guardrails:** `-PrunE2e` tag — все E2E должны проходить до каждого release PR

**Recommended PRs:** PR-0 (run all), PR-3 (CL spike e2e), PR-10 (reconciler smoke), PR-12 (E6)

***

### `platform-tracing-perf-tests`

**Current role:** SUT + docker-compose + M0–M10 PowerShell scenarios + `PerfAdminController`.

**Target role:** Без изменений — `PerfAdminController` остаётся perf-only.

**Migration risk:** HIGH — M5 FAIL baseline должен быть воспроизводимым

**Required guardrails:** М0/M5 re-run при любом изменении hot path

**Recommended PRs:** PR-0 (baseline capture), PR-12 (E6 delta)

***

### `platform-tracing-test`

**Current role:** Shared test fixtures — `InMemorySpanExporter` harness, JUnit5 extensions, `SamplerHarness`, `SpanProcessorHarness`, ArchUnit rules.

**Target role:** Без изменений плюс расширение harness для core policy (PR-5).

**Migration risk:** MEDIUM — harness изменения могут cascade в 200+ тестов

**Required guardrails:** Harness изменения только additive; backward compatible

**Recommended PRs:** PR-5 (extend harness), PR-6, PR-7 (add core assertions)

***

## 6. Plan сохранения тестов

### Принципы

1. **Zero net test deletion в волне 1.** Существующие тесты допустимо адаптировать (переместить, изменить импорты, обновить зависимость на новый модуль), но не удалять.
2. **Tests before moves.** Ни одно поведение не переносится в новый модуль до тех пор, пока его тесты не существуют в целевом слое или не продублированы во временной форме.
3. **DUPLICATE_BEFORE_MOVE** — единственная допустимая стратегия для кода горячего пути (sampling, scrubbing).
4. Адаптированные тесты сохраняют оригинальное намерение; изменение допустимо только в части импорта и вспомогательных зависимостей.

---

### 6.1. Sampling тесты

**Текущий модуль:** `platform-tracing-otel-extension` (78 test classes total; sampling subset)  
**Целевое разделение:** policy rules + `SamplerState` → `platform-tracing-core`; OTel `Sampler` callback → `platform-tracing-otel-extension`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `CompositeSamplerTest`, `CompositeSamplerEdgeCasesTest` (инвентарь: `EdgeCasesTest`), `RouteRatioTest`, `SamplerStateHolderTest`, `SamplerRuntimeUpdateConcurrencyTest`, `PlatformSamplerProviderTest` |
| **Продублировать до переноса** | `CompositeSamplerTest` → дублировать в `platform-tracing-core` тестовый источник с использованием JDK-only harness (без OTel SDK); `SamplerStateHolderTest` → дублировать в core с использованием `DomainConfigHolder` напрямую; `RouteRatioTest` для каждого `*Rule` класса |
| **Адаптировать после split** | `CompositeSamplerTest` в `otel-extension` — обновить зависимость: `CompositeSampler` → thin OTel adapter, policy delegated from core; `SamplerRuntimeUpdateConcurrencyTest` — адаптировать, если state holder переехал |
| **Новые тесты** | Characterization test для sampling decision chain output (сравнение до/после split на идентичных входных данных); тест на отсутствие OTel-зависимостей в core sampling policy |
| **PR** | PR-5 (дублирование) → PR-6 (extraction + адаптация) |
| **Риск при пропуске** | Изменение порядка правил в цепочке KillSwitch→ForceHeader→QaTrace→RouteRatio→DefaultRatio→HardDrop→ParentDecision без регрессии. Неверный sampling rate в production. |

---

### 6.2. Scrubbing тесты

**Текущий модуль:** `platform-tracing-otel-extension` (scrubbing package + engine sub-packages)  
**Целевое разделение:** rule evaluation engine → `platform-tracing-core`; OTel `SpanProcessor` callback → `platform-tracing-otel-extension`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `ScrubbingSpanProcessorTest`, `ScrubbingSpanProcessorAdvancedTest` (инвентарь: `AdvancedTest` в scrubbing package), `ScrubbingSecurityNegativeTest`, `MergeEngineTest`, `RuleCircuitBreakerTest`, `ExtensionRuleLoaderTest`, `ServiceLoaderSpanAttributeScrubbingRuleTest`, `ExceptionEventScrubbingE2ETest`, `BuiltInRulesTest` |
| **Продублировать до переноса** | Rule engine unit tests (`MergeEngineTest`, `RuleCircuitBreakerTest`, `BuiltInRulesTest`) → дублировать в `platform-tracing-core` test source до PR-7; `ScrubbingSecurityNegativeTest` → обязательно, критический security тест ReDoS/injection |
| **Адаптировать после split** | `ScrubbingSpanProcessorTest` — адаптировать: processor остаётся в otel-extension, но делегирует engine из core; `ExtensionRuleLoaderTest` — адаптировать: loader может быть в extension, engine в core |
| **Новые тесты** | Тест граничного поведения fail-open: engine exception → span export продолжается (не только на уровне processor, но и на уровне core engine); тест на отсутствие OTel-зависимостей в core scrubbing policy |
| **PR** | PR-5 (дублирование security + rule engine тестов в core) → PR-7 (extraction + адаптация processor) |
| **Риск при пропуске** | Потеря mandatory baseline scrubbing → compliance incident. Регрессия ReDoS защиты (`ScrubbingSecurityNegativeTest` отсутствует в core → уязвимость не обнаруживается). |

---

### 6.3. Validation / enrichment тесты

**Текущий модуль:** `platform-tracing-otel-extension`  
**Целевое разделение:** policy → core; processor adapter → otel-extension

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `ValidatingSpanProcessorTest`, `ValidationPolicyRuntimeTest`, `EnrichingSpanProcessorTest`, `CategoryContractsTest` (в `platform-tracing-api`), `SpanEnricherTest` |
| **Продублировать до переноса** | `ValidationPolicyRuntimeTest` → дублировать в core до PR-8; enrichment policy unit тесты (`SpanEnricherTest`) → дублировать в core |
| **Адаптировать после split** | `ValidatingSpanProcessorTest` — adapter делегирует в core policy; `EnrichingSpanProcessorTest` — аналогично |
| **Новые тесты** | Тест на `ValidationMode.STRICT` vs `LENIENT` в изолированном core без OTel; тест, что enrichment отключается при `enriching.enabled=false` на уровне policy (не processor) |
| **PR** | PR-5 (дублирование до начала extraction) → PR-8 (extraction) |
| **Риск при пропуске** | Потеря semconv validation enforcement; неверный режим LENIENT/STRICT после split. |

---

### 6.4. Export safety тесты

**Текущий модуль:** `platform-tracing-otel-extension` (processor + safety package)

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `PlatformDropOldestExportSpanProcessorTest` (overflow, lifecycle, builder validation — все варианты), `SafeSpanExporterTest`, `DegradedModeControllerTest`, `BspDropOldestSafetyAgentSmokeTest` (e2e), `BspOverflowSafetyAgentSmokeTest` (e2e) |
| **Продублировать до переноса** | Не требуется — `PlatformDropOldestExportSpanProcessor` и `SafeSpanExporter` остаются в `otel-extension` (не являются целевым SPLIT_CORE_AND_ADAPTER) |
| **Адаптировать после split** | Нет изменений в волне 1; тесты остаются в otel-extension |
| **Новые тесты** | Тест, что `drop-oldest` поведение неизменно при изменении конфигурации queue size через `TracingProperties`; сквозной тест `overflow → drop metric` |
| **PR** | PR-0 (зафиксировать как baseline); тесты не требуют движения в волне 1 |
| **Риск при пропуске** | Потеря export safety → BSP переполнение без backpressure, silent span loss. |

---

### 6.5. JMX / control-plane тесты

**Текущие модули:** `platform-tracing-otel-extension` (server) + `platform-tracing-spring-boot-autoconfigure` (client)

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `PlatformTracingControlTest`, `SamplingControlClientTest`, `RuntimeSamplingControlSmokeTest` (e2e — CRITICAL) |
| **Продублировать до переноса** | `RuntimeSamplingControlSmokeTest` → зафиксировать в PR-0 как baseline; никаких дублирований не требуется — классы остаются на месте до PR-3 (JMX wire spike) |
| **Адаптировать после split** | `PlatformTracingControlTest` → адаптировать после PR-3 (Cross-CL JMX wire spike): заменить typed MBean invoke тесты на Map-based wire тесты; `SamplingControlClientTest` → адаптировать: изменить assertions на новый wire format |
| **Новые тесты** | Тест: JMX client возвращает `Optional.empty()` при недоступном agent (не exception); тест: invalid config через MBean → `invalidConfigCounter` инкрементируется, LKG состояние сохраняется; тест Map wire contract (PR-3) |
| **PR** | PR-0 (baseline lock на `RuntimeSamplingControlSmokeTest`) → PR-3 (wire spike + новые тесты) → PR-10 адаптация |
| **Риск при пропуске** | Нарушение cross-CL boundary semantics → ClassCastException или classloading failure при runtime sampling control. Ops не может управлять sampling ratio через Actuator/JMX. |

---

### 6.6. Spring property binding тесты

**Текущий модуль:** `platform-tracing-spring-boot-autoconfigure`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `TracingPropertiesBindingTest`, `TracingAutoConfigurationTest`, `SharedDefaultsAlignmentTest`, `PlatformTracingDefaultsProviderTest`, `ExtensionConfigTest`, `SdkModeDetectionAutoConfigurationTest` |
| **Продублировать до переноса** | Не требуется — `TracingProperties` не переносится; остаётся в autoconfigure |
| **Адаптировать после split** | После PR-10 (Desired State Config Reconciler): адаптировать `TracingAutoConfigurationTest` — добавить assertions для `TracingConfigReconciler` bean presence/absence; `SharedDefaultsAlignmentTest` — расширить для новых reconciler defaults |
| **Новые тесты** | Тест, что `platform.tracing.*` binding не нарушается при добавлении reconciler beans; тест на topology vs policy property separation (PR-10) |
| **PR** | PR-0 (зафиксировать baseline binding tests) → PR-10 (адаптировать для reconciler) |
| **Риск при пропуске** | Silent config bind failure → настройки sampling/scrubbing игнорируются в production без ошибок. |

---

### 6.7. Config refresh / RuntimeConfigApplier тесты

**Текущий модуль:** `platform-tracing-spring-boot-autoconfigure`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `RuntimeConfigApplierTest`, `DualChannelDriftDiagnosticsTest`, `TracingRefreshScopeAutoConfiguration` тест (Requires manual review — точное имя не подтверждено в инвентаре) |
| **Продублировать до переноса** | `RuntimeConfigApplierTest` → до введения `TracingConfigReconciler` зафиксировать поведение RefreshScope→JMX batch apply как characterization тест |
| **Адаптировать после split** | После PR-10: `RuntimeConfigApplierTest` адаптировать — reconciler принимает `TracingDesiredState`; assert что JMX apply через `SamplingControlClient` сохраняет поведение; `DualChannelDriftDiagnosticsTest` → расширить для reconciler drift detection |
| **Новые тесты** | Тест, что при недоступном Config Server reconciler использует last-known-good state, а не сбрасывает конфигурацию; тест ReconcileResult (apply succeeded / drift detected / noop) |
| **PR** | PR-0 (зафиксировать baseline) → PR-10 (reconciler introduction + адаптация) |
| **Риск при пропуске** | Потеря RefreshScope semantics при переходе к reconciler → partial config apply, drift без уведомления. |

---

### 6.8. WebMVC тесты

**Текущий модуль:** `platform-tracing-autoconfigure-webmvc`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `WebStackIsolationTest`, `DuplicateSpansRegressionMatrixTest` (webmvc часть), `TraceResponseHeaderServletFilterTest`, `ServletOutboundNoSpanArchTest` |
| **Продублировать до переноса** | `WebStackIsolationTest` → запускать в CI на каждом PR — тест должен быть зелёным во все PR от PR-0 до PR-13 |
| **Адаптировать после split** | Нет адаптаций в волне 1 — `platform-tracing-autoconfigure-webmvc` не изменяется структурно |
| **Новые тесты** | Тест, что starter-servlet не тянет `webflux` типы на classpath; тест, что `TraceResponseHeaderServletFilter` не регистрируется в reactive контексте |
| **PR** | PR-0 (baseline), PR-1 (taxonomy guardrails), все последующие PR — тест в CI |
| **Риск при пропуске** | Утечка WebFlux beans в Servlet стек → runtime error в deployment без WebFlux; дублирование HTTP spans в MVC приложении. |

---

### 6.9. WebFlux тесты

**Текущий модуль:** `platform-tracing-autoconfigure-webflux`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `TracingReactorEagerInitConfigurationTest`, `ReactorContextPropagationIntegrationTest`, `MdcPropagationWebFluxIntegrationTest`, `DuplicateSpansRegressionMatrixTest` (webflux часть) |
| **Продублировать до переноса** | `TracingReactorEagerInitConfigurationTest` → критичен для hot path (Reactor Hooks); зафиксировать в PR-0 |
| **Адаптировать после split** | Нет адаптаций в волне 1 |
| **Новые тесты** | Тест, что `BridgeOtelReactorContextPropagation` не вызывается в Servlet контексте; тест eager init при отсутствии Reactor на classpath (conditional bean) |
| **PR** | PR-0 (baseline), CI на всех PR |
| **Риск при пропуске** | Потеря Reactor context propagation → trace ID не пробрасывается через `publishOn`/`subscribeOn`; MDC пуст в реактивных цепочках. |

---

### 6.10. Starter smoke тесты

**Текущие модули:** `platform-tracing-spring-boot-starter-servlet`, `platform-tracing-spring-boot-starter-reactive`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `DuplicateHttpSpanAgentSmokeTest` (e2e), `PlatformExtensionAgentSmokeTest` (e2e), `ForceSamplingAgentSmokeTest` (e2e) — smoke-тесты через полный стартер |
| **Продублировать до переноса** | Нет необходимости — стартеры являются dependency-only модулями (0 Java классов) |
| **Адаптировать после split** | При изменении dependency graph стартера (если добавляется новый модуль) — обновить smoke тест на проверку classpath |
| **Новые тесты** | Автоматизированный тест: стартер-servlet не содержит webflux типов в compile classpath; стартер-reactive не содержит servlet типов |
| **PR** | PR-1 (module taxonomy guardrails — enforced by ArchUnit) |
| **Риск при пропуске** | Случайное добавление transitive dependency в стартер ломает BOM-only consumer experience. |

---

### 6.11. E2E тесты

**Текущий модуль:** `platform-tracing-e2e-tests` (42 test classes; требует `-PrunE2e` + Docker)

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | Все 42 класса. Приоритет: `RuntimeSamplingControlSmokeTest`, `ExceptionEventScrubbingE2ETest`, `BspDropOldestSafetyAgentSmokeTest`, `BspOverflowSafetyAgentSmokeTest`, `CustomRuleSmokeE2ETest`, `ReactorContextPropagationAgentE2ETest`, `ClassLoaderVisibilitySpikeE2ETest` |
| **Продублировать до переноса** | `RuntimeSamplingControlSmokeTest` → зафиксировать expected behavior в PR-0 как characterization; `ClassLoaderVisibilitySpikeE2ETest` → результат спайка документировать до PR-3 |
| **Адаптировать после split** | После PR-3 (JMX wire spike): адаптировать тесты, проверяющие JMX invoke semantics; после PR-6/PR-7: адаптировать `RuntimeSamplingControlSmokeTest` если изменится точка сборки sampler |
| **Новые тесты** | E2E тест для dev-only Actuator mutation guard (PR-11): запустить SUT с prod profile, убедиться что POST `/actuator/tracing` возвращает 404/403 |
| **PR** | PR-0 (baseline run задокументирован), PR-3 (CL spike), PR-6 (sampling e2e re-run), PR-7 (scrubbing e2e re-run), PR-11 (actuator prod guard e2e) |
| **Риск при пропуске** | Невидимые regression в agent deployment. E2E — единственный уровень, где тестируется связка SDK→Collector→Jaeger под настоящим Agent. |

---

### 6.12. ArchUnit тесты

**Текущие модули:** `platform-tracing-test` (14 main classes ArchUnit rules), `platform-tracing-spring-boot-autoconfigure` (arch tests), `platform-tracing-autoconfigure-webmvc`

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `TracingArchRulesTest`, `OtelSdkArchRulesTest`, `EscapeHatchArchRuleTest`, `OtelDirectIntegrationArchTest` (autoconfigure), `KafkaOutboundNoSpanArchTest`, `ServletOutboundNoSpanArchTest` |
| **Продублировать до переноса** | Нет дублирования — ArchUnit тесты расширяются, не дублируются |
| **Адаптировать после split** | PR-1: добавить fitness function правила для целевых dependency directions (`core` не должен зависеть от OTel, Spring); PR-4: добавить ArchUnit fitness functions как отдельный PR (PR-4 по roadmap) |
| **Новые тесты** | Правило: `platform-tracing-core` classes do not import OTel packages; правило: `platform-tracing-core` classes do not import Spring packages; правило: `platform-tracing-otel-extension` does not import Spring packages; правило: mutation-capable Actuator operations not reachable from `@ConditionalOnMissingBean(type="dev-profile")` (после PR-11) |
| **PR** | PR-1 (module taxonomy + initial guardrails), PR-4 (ArchUnit fitness functions) |
| **Риск при пропуске** | Тихое нарушение dependency direction → OTel типы просачиваются в core, Spring типы в extension, ломая ClassLoader isolation. |

---

### 6.13. JMH benchmark тесты (контрактные)

**Текущий модуль:** `platform-tracing-bench` (16 JMH классов + 3 contract tests)

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `PerformanceReleaseGateTest` (hard budget contract), `ScrubbingFixtures` (contract test), `PerformanceBudgetsContractTest` (Requires manual review — точное имя не подтверждено) |
| **Продублировать до переноса** | `PerformanceReleaseGateTest` → не дублировать; зафиксировать что он проходит в PR-0 baseline |
| **Адаптировать после split** | После PR-6/PR-7/PR-8: обновить benchmark dependencies в `platform-tracing-bench/build.gradle` — если sampling/scrubbing policy переехали в `core`, JMH должен зависеть от `core`, а не только от `otel-extension` |
| **Новые тесты** | Нет новых contract тестов в волне 1; после PR-12 добавить сравнение `jmhCompareBaseline` |
| **PR** | PR-0 (baseline capture), PR-6/PR-7/PR-8 (обновление deps), PR-12 (final comparison) |
| **Риск при пропуске** | `PerformanceReleaseGateTest` проходит на старом коде, падает на разделённом — регрессия не обнаружена до release. |

---

### 6.14. Macro perf тесты (M0–M10)

**Текущий модуль:** `platform-tracing-perf-tests` (3 SUT classes + scripts/docker; сценарии M0–M10)

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | Все M0–M10 сценарии: `m0.env`, `m5.env`, `m6.env`, `m8.env`, `m10.env`, `m10c.env`, `m10d.env`; `steady-state.js`; `PerfAdminController`; documented M5 FAIL baseline (+48% CPU, +25% RSS) |
| **Продублировать до переноса** | `PerfAdminController` → не переносить в production-подобный код; использовать только внутри `perf-tests`; M0 baseline run → выполнить до PR-0 merge |
| **Адаптировать после split** | После PR-12 (tiered pipeline defaults): перезапустить M5 сценарий, зафиксировать delta vs M0; при изменении JMX bridge (PR-3): обновить `PerfAdminController` если API MBean меняется |
| **Новые тесты** | Нет новых сценариев в волне 1; M5 re-run обязателен после PR-12 |
| **PR** | PR-0 (M0 baseline run), PR-12 (M5 re-run + delta measurement) |
| **Риск при пропуске** | М5 FAIL (+48% CPU, +25% RSS) — уже задокументированный fail — может ухудшиться после tiered pipeline изменений без обнаружения. |

---

### 6.15. Test fixtures / test support

**Текущий модуль:** `platform-tracing-test` (14 main + 17 test classes)

| Критерий | Содержимое |
|----------|-----------|
| **Существующие тесты (сохранить)** | `InMemorySpanExporter` harness, `JUnit5` extensions (`OtelSdkExtension*`), `SpanProcessorHarness`, `SamplerHarness`, `SemconvStrictTestAutoConfiguration`, `TracingArchRules`, `OtelSdkArchRules` |
| **Продублировать до переноса** | Не требуется — `platform-tracing-test` является shared test infrastructure |
| **Адаптировать после split** | После PR-6/PR-7: при необходимости расширить `SamplerHarness` и `SpanProcessorHarness` для тестирования pure policy в `platform-tracing-core` (без OTel SDK); dependency `api platform-tracing-core` в test module должна оставаться корректной после split |
| **Новые тесты** | Harness без OTel для тестирования core policy (JDK-only); добавить в `platform-tracing-test` если применяется как shared fixture |
| **PR** | PR-5 (перед extraction — расширить harness); PR-6/PR-7 (использовать расширенный harness) |
| **Риск при пропуске** | Тесты core policy вынуждены использовать OTel SDK → нарушение Clean Core изоляции; слабое покрытие pure policy без OTel. |

---

### Сводная таблица по тестовым группам

| Группа | Текущих тестов | Дублировать до | Адаптировать после | PR дублирования | Zero-delete |
|--------|---------------|---------------|-------------------|-----------------|-------------|
| Sampling | ~8 классов | PR-5 | PR-6 | PR-5 | ✅ |
| Scrubbing | ~9 классов | PR-5 | PR-7 | PR-5 | ✅ |
| Validation/enrichment | ~5 классов | PR-5 | PR-8 | PR-5 | ✅ |
| Export safety | ~5 классов + 2 e2e | Не нужно | Нет изменений | — | ✅ |
| JMX / control-plane | ~3 + 1 e2e | PR-0 baseline | PR-3, PR-10 | PR-0 | ✅ |
| Spring property binding | ~9 классов | PR-0 baseline | PR-10 | PR-0 | ✅ |
| Config refresh | ~2 классов | PR-0 baseline | PR-10 | PR-0 | ✅ |
| WebMVC | ~4 класса | CI на всех PR | Нет в волне 1 | — | ✅ |
| WebFlux | ~4 класса | CI на всех PR | Нет в волне 1 | — | ✅ |
| Starter smoke | ~3 e2e | — | PR-1 (ArchUnit) | PR-1 | ✅ |
| E2E (42 класса) | 42 класса | PR-0 baseline | PR-3, PR-6, PR-11 | PR-0 | ✅ |
| ArchUnit | ~10 классов | — | PR-1, PR-4 | PR-4 | ✅ |
| JMH contract | 3 класса | PR-0 baseline | PR-6/7/8 deps | PR-0 | ✅ |
| Macro perf M0–M10 | скрипты | PR-0 (M0 run) | PR-12 (M5 re-run) | PR-0 | ✅ |
| Test fixtures | 14+17 классов | — | PR-5/6/7 расширение | PR-5 | ✅ |

---

## 7. Benchmark and performance preservation plan

### 7.1. Принципы сохранения benchmark'ов

1. **Имена и параметры benchmark'ов не меняются до PR-0 baseline capture.** Переименование JMH класса или изменение `@Param` значений делает baseline несравнимым. Любые такие изменения разрешены только после PR-0 и только с новым baseline capture.

2. **Сравнимость важнее чистоты во время волны 1.** Перемещение кода в другой модуль не должно изменять package import в JMH классе до тех пор, пока не зафиксирован before/after baseline.

3. **JMH baseline capture обязателен до extraction.** Порядок: `./gradlew jmhSaveBaseline` (на зафиксированном hardware профиле) → extraction PR → `./gradlew jmhCompareBaseline`.

4. **Macro perf M0/M5 обязательны до и после tiered pipeline.** M0 = calibration. M5 = production-realistic delta. Оба должны быть выполнены в PR-0 и PR-12.

5. **`PerformanceReleaseGateTest` должен проходить на каждом PR.** Это контрактный тест, не micro benchmark. Failure = block merge.

6. **Зависимости `platform-tracing-bench` обновляются после, а не до extraction PR.** Если `CompositeSampler` переехал в `core`, `build.gradle` в `bench` обновляется только в PR-6, не в PR-5.

---

### 7.2. Benchmark'и, которые должны быть запущены до extraction

#### Таблица обязательных pre-extraction benchmark'ов

| JMH Benchmark | Что измеряет | Связанный production класс | Критичность | Должен выполниться до PR |
|---------------|-------------|---------------------------|-------------|--------------------------|
| `CompositeSamplerBenchmark` | Overhead вызова `CompositeSampler.shouldSample()` на типичном запросе | `CompositeSampler` | **HIGH** | PR-6 (sampling extraction) |
| `CompositeSamplerPolicyBranchesBenchmark` | Стоимость каждой ветки rule chain (KillSwitch, ForceHeader, RouteRatio и т.д.) | `*Rule` классы | **HIGH** | PR-6 |
| `CompositeSamplerConcurrentUpdateBenchmark` | Latency + throughput при concurrent reads и periodic atomic state update | `SamplerStateHolder`, `DomainConfigHolder` | **HIGH** | PR-6 (state holder split риск) |
| `ScrubbingEngineBenchmark` | Throughput rule evaluation engine для типичного span | `scrubbing.engine.*`, `MergeEngine` | **CRITICAL** | PR-7 (scrubbing extraction) |
| `ScrubbingPerRuleBenchmark` | Стоимость каждого `SpanAttributeScrubbingRule` при обработке span attribute | `BuiltInSpanAttributeScrubbingRules`, regex rules | **HIGH** | PR-7 |
| `QueueOfferBenchmark` | Offer throughput: `PlatformDropOldestExportSpanProcessor` vs standard BSP | `PlatformDropOldestExportSpanProcessor` | **HIGH** | PR-0 (export safety baseline) |
| `CompositePipelineBenchmark` | Полная цепочка: scrub → validate → enrich → export; latency per span | `PlatformCompositeSpanProcessor` + chain | **HIGH** | PR-8 (pipeline defaults) |
| `AttributePolicyBenchmark` | Стоимость attribute allow/deny/eager policy eval | `AttributePolicy` | **MEDIUM** | PR-6/PR-8 (core split) |
| `TypedBuilderBenchmark` | Allocation + latency typed span builder usage | `*SpanBuilderImpl` | **MEDIUM** | PR-0 (builder baseline) |
| `HeaderPropagationBenchmark` | inject/extract latency `PlatformTraceControlPropagator` | propagation classes | **MEDIUM** | PR-0 |
| `MdcCorrelationBenchmark` | MDC bridge overhead per span | `RemoteServiceMdc` | **MEDIUM** | PR-0 |
| `TracedAspectBenchmark` | AOP overhead для `@Traced` методов | `TracingAspect` | **MEDIUM** | PR-0 |
| `PerformanceReleaseGateTest` | Контрактная проверка: hard perf budgets из `performance-budgets.yaml` | все hot path классы | **HIGH** | PR-0 и каждый PR после |

> **Примечание:** `StartSpanBenchmark`, `SpanLimitsBenchmark`, `ContextScopeBenchmark` — не упомянуты в приоритетном списке, но присутствуют в инвентаре. **Requires manual review** — уточнить что именно они измеряют перед PR-0.

---

### 7.3. Benchmark'и, которые должны быть запущены после extraction

#### Mapping benchmark → PR

| PR | Benchmark'и для re-run | Ожидаемый результат |
|----|----------------------|---------------------|
| **PR-6** (sampling extraction) | `CompositeSamplerBenchmark`, `CompositeSamplerPolicyBranchesBenchmark`, `CompositeSamplerConcurrentUpdateBenchmark`, `AttributePolicyBenchmark` | `jmhCompareBaseline` — delta в пределах noise; `PerformanceReleaseGateTest` pass |
| **PR-7** (scrubbing extraction) | `ScrubbingEngineBenchmark`, `ScrubbingPerRuleBenchmark`, `CompositePipelineBenchmark` (scrubbing path) | `jmhCompareBaseline` — delta в пределах noise; `PerformanceReleaseGateTest` pass |
| **PR-8** (validation/enrichment extraction) | `CompositePipelineBenchmark` (full pipeline), `AttributePolicyBenchmark`, `TypedBuilderBenchmark` | `jmhCompareBaseline` — delta в пределах noise |
| **PR-12** (tiered pipeline defaults) | **Полный benchmark suite** (`./gradlew jmh`) + `PerformanceReleaseGateTest` + M5 macro perf re-run | E6 evidence: `CompositePipelineBenchmark` delta vs PR-0 baseline документируется как evidence для committee |

#### Правило обновления dependencies bench модуля

```text
PR-6: обновить platform-tracing-bench/build.gradle:
  - добавить jmh dependency на platform-tracing-core (sampling policy)
  - сохранить dependency на platform-tracing-otel-extension (OTel adapter)
  - NOT изменять benchmark class names или @Param values

PR-7: обновить platform-tracing-bench/build.gradle:
  - добавить jmh dependency на platform-tracing-core (scrubbing engine)

PR-8: обновить platform-tracing-bench/build.gradle:
  - добавить jmh dependency на platform-tracing-core (validation/enrichment policy)
```

---

### 7.4. Macro perf сценарии

#### M0 — Host calibration baseline

```text
Назначение: измерение ambient noise текущей машины; устранение HW/OS variance из результатов
Когда запускать: обязательно до PR-0 merge, на dedicated CI runner или тестовой машине
Сценарий: минимальная нагрузка без agent extension — baseline CPU/RSS/latency
Результат: perf-results/m0-<date>.json — зафиксировать в репозитории
Пересчёт: при каждом изменении hardware профиля
Использование: denominator для M5 delta calculation
```

#### M5 — Agent + Extension + Export delta

```text
Назначение: измерение реального overhead platform-tracing agent extension в production-realistic условиях
Когда запускать: до PR-0 (зафиксировать текущий FAIL baseline), после PR-12 (re-run для E6 gate)
Текущий задокументированный результат: FAIL — +48% CPU overhead vs M0, +25% RSS vs M0
Сценарий: полная нагрузка с agent extension — CompositeSampler + ScrubbingSpanProcessor + full processor chain + export
Результат ожидаемый после PR-12: tiered pipeline должен снизить overhead до budget (точные пороги в performance-budgets.yaml)
Требует: docker-compose.perf.yml + k6 steady-state.js + Collector
Использование: основное evidence E6 для migration committee
```

#### M6 / M8 — Degraded mode сценарии

```text
Назначение: проверка поведения при Collector failure / backpressure
Когда запускать: Requires manual review — точные сценарии m6.env и m8.env требуют ручной проверки содержимого
M6: предположительно — Collector недоступен; SafeSpanExporter должен не блокировать application
M8: предположительно — BSP queue saturation; PlatformDropOldestExportSpanProcessor drop-oldest path
Обязательность в волне 1: нет обязательного re-run; должны быть выполнены если PR-12 изменяет export/queue behavior
Использование: деградированный режим evidence; DegradedModeController / CircuitBreaker поведение
```

#### M10 / M10c / M10d — Config reload под нагрузкой

```text
Назначение: проверка runtime config mutation через JMX при live traffic
Когда запускать: до PR-3 (JMX wire spike baseline), после PR-10 (reconciler introduction)
M10: JMX setSamplingRatio под нагрузкой — latency spike допустимый?
M10c: Requires manual review — точный сценарий не задокументирован в инвентаре
M10d: Requires manual review — точный сценарий не задокументирован в инвентаре
Связанные классы: PerfAdminController → JMX invoke → PlatformTracingControl.setSamplingRatio()
Критическое поведение: DomainConfigHolder LKG semantics — invalid config не нарушает текущий трафик
Использование: runtime control plane evidence; основание для PR-3 wire migration decision
```

---

### 7.5. Performance acceptance criteria

Критерии сформулированы консервативно: во время волны 1 цель — сохранить поведение, а не улучшить.

| Критерий | Правило |
|----------|---------|
| **Имена benchmark'ов до PR-0** | Имена JMH классов и `@Param` значения не изменяются до завершения PR-0 baseline capture. Нарушение = блокировка PR-0. |
| **Hot-path extraction без JMH evidence** | Запрещено. Любой PR, перемещающий sampling или scrubbing policy, обязан содержать `jmhCompareBaseline` результат как часть PR description. |
| **PR-12 без E6 macro perf** | PR-12 не мержится без задокументированного M5 re-run результата. Ожидаемое improvement должно достичь budget из `performance-budgets.yaml`. |
| **M5 re-run после tiered pipeline** | Обязателен. M5 FAIL (+48% CPU, +25% RSS) — текущий задокументированный baseline; tiered pipeline defaults (PR-12) — основная возможность его исправить. |
| **Regression выше шума** | Любой `jmhCompareBaseline` результат с delta >5% по throughput или latency p99 требует manual review перед merge. Конкретный threshold определяется профилем в `performance-budgets.yaml`. |
| **`PerformanceReleaseGateTest`** | Должен проходить на каждом PR от PR-0 до PR-13. Failure = block merge, не warning. |
| **Macro perf runner** | `run-perf-scenario.ps1` и `run-official-matrix.ps1` не изменяются в волне 1. Изменения в SUT (`PerfAdminController`) допустимы только если меняется JMX API (PR-3, PR-10). |
| **Hardware профиль** | Все benchmark'и одной волны должны выполняться на одном и том же hardware профиле. Смена машины между PR-0 и PR-12 invalidates baseline comparison. |

---

## 8. Desired State Configuration Layer — стратегия миграции

### 8.1. Текущее состояние

#### `TracingProperties`

`TracingProperties` — центральный `@ConfigurationProperties` класс с префиксом `platform.tracing` в модуле `platform-tracing-spring-boot-autoconfigure`. Содержит ~700+ строк вложенных классов: `Sampling`, `Scrubbing`, `Validation`, `Enriching`, `Queue`, `Exporter`, `Sdk`, `Resource` и другие. Является единственным источником конфигурации на стороне Spring Application CL.

Текущая ответственность:
- Связывает весь блок `platform.tracing.*` из `application.yaml`, env vars, Helm charts
- Является `@ConfigurationProperties` bean — создаётся при старте
- Мутация части полей разрешена через `@RefreshScope` + `SamplingControlClient` → JMX
- Не разделяет **topology** (startup-only) и **policy** (runtime-mutable) поля явно на уровне типов

#### `TracingRefreshScopeAutoConfiguration`

Условно регистрирует `TracingProperties` bean под `@RefreshScope`, если на classpath присутствует `spring-cloud-context`. При получении события `RefreshEvent` Spring пересоздаёт `TracingProperties` bean, после чего `RuntimeConfigApplier` считывает обновлённые значения и применяет их через JMX.

#### `RuntimeConfigApplier`

Применяет diff между текущими `TracingProperties` и последним known-good состоянием agent через `SamplingControlClient`. Является **предшественником** `TracingConfigReconciler` в target архитектуре. Текущее поведение:
- Слушает `EnvironmentChangeEvent` (или вызывается из `RefreshScope` пути)
- Вызывает `SamplingControlClient.updateSamplingRatio(double)` и родственные методы
- Не возвращает structured result; ошибки логируются как WARN
- Не поддерживает LKG feedback loop на уровне Spring layer (LKG живёт в agent-side `DomainConfigHolder`)

#### `SamplingControlClient`

JMX-клиент на стороне Application CL. Не импортирует типы из `platform-tracing-otel-extension` по дизайну — cross-CL boundary пересекается через `MBeanServerConnection.invoke()`. Текущие методы возвращают `Optional<Double>` / `Optional<SamplingControlUnavailableException>`. При недоступном MBean возвращает `Optional.empty()` без exception propagation (fail-silent design). Этот паттерн необходимо сохранить при миграции.

#### JMX agent-side состояние

`PlatformTracingControl` (Agent CL) реализует `PlatformTracingControlMBean` и является единственной точкой входа для runtime мутаций состояния в agent. Хранит ссылку на `SamplerStateHolder` (который обёртывает `DomainConfigHolder<SamplerState>`). При невалидном update инкрементирует `invalidConfigCounter`; хранит last-known-good состояние через `DomainConfigHolder.replace()` семантику. Текущие payload-типы: примитивы (`double`), `String[]`, частичные `Map`-операции — не все операции переведены на `Map`-based wire.

#### `DualChannelDriftDiagnostics`

Детектирует drift между Spring-side желаемым состоянием (`TracingProperties`) и agent-side applied состоянием (agent configuration via `PlatformTracingDefaultsProvider` + JMX read-back). Является **прямым предшественником** `TracingConfigDriftStatus` в target архитектуре. Текущий механизм: периодическое сравнение с эмиссией Micrometer метрики при обнаружении расхождения.

#### Гэп: `TracingConfigReconciler` не найден

`TracingConfigReconciler` **отсутствует в текущей кодовой базе** — существует только в target architecture docs. Функцию reconciler сейчас выполняет `RuntimeConfigApplier` в паре с `DualChannelDriftDiagnostics`, но без явного desired state model и без structured apply result.

---

### 8.2. Целевое состояние

```
Config Server (runtime policy authority)
  │   platform.tracing.sampling.*, scrubbing.*, validation.*, enriching.*
  ↓
Spring Environment (TracingProperties — RefreshScope)
  ↓
TracingConfigReconciler
  │   принимает TracingDesiredState (policy fields only)
  │   реджектит topology fields
  │   валидирует policy
  ↓
SamplingControlClient
  │   private in-process JMX
  │   validated Map / OpenMBean-compatible wire
  ↓
PlatformTracingControl (Agent CL, private)
  │   validates payload
  │   applies to SamplerStateHolder / policy holders
  │   LKG via DomainConfigHolder
  ↓
Agent applied state (source of truth = agent, not Spring)
```

**Источники конфигурации по типу:**

| Тип | Источник | Изменяемость в runtime | Пример полей |
|-----|----------|----------------------|--------------|
| Runtime policy | Config Server → Spring Environment | Да (`@RefreshScope`) | `sampling.ratio`, `scrubbing.rules`, `validation.mode` |
| Startup topology | Helm / env vars / system properties | Нет (требует редеплой) | `exporter.endpoint`, `sdk.mode`, `queue.size` |
| Bootstrap defaults | `PlatformTracingDefaultsProvider` | Нет | начальные значения |
| Agent applied state | `SamplerStateHolder`, policy holders в Agent CL | Изменяется только через JMX | текущий active sampler state |

**Actuator разделение:**
- `GET /actuator/tracing` — production READ endpoint: возвращает effective state, drift status, apply results
- `POST /actuator/tracing/{property}` — **dev/debug-only**: guard через `@Profile` или `@ConditionalOnProperty(platform.tracing.actuator.mutation.enabled=false)` в production

---

### 8.3. Стратегия переиспользования существующих классов

#### `TracingProperties` — сохранить

`TracingProperties` сохраняется без структурного изменения в волне 1. Reconciler читает policy fields из неё; topology fields остаются в ней как startup-only. Разделение topology vs policy **не требует переименования полей** — достаточно того, что `TracingConfigReconciler` знает, какие поля являются policy, и реджектит попытку применить topology field через reconciler path.

#### `RuntimeConfigApplier` — сохранить, эволюционировать в precursor

`RuntimeConfigApplier` сохраняется и остаётся рабочим пока reconciler в disabled режиме. После введения `TracingConfigReconciler` (PR-10) `RuntimeConfigApplier` продолжает работать как fallback path — параллельный путь на время rollout. Его логика apply через `SamplingControlClient` переиспользуется reconciler'ом, а не заменяется.

#### `TracingRefreshScopeAutoConfiguration` — сохранить

Механизм `@RefreshScope` для `TracingProperties` сохраняется. `TracingConfigReconciler` слушает те же `EnvironmentChangeEvent` / `RefreshEvent` что и `RuntimeConfigApplier` сейчас. Никаких изменений в `TracingRefreshScopeAutoConfiguration` до PR-10.

#### `DualChannelDriftDiagnostics` — сохранить, расширить

`DualChannelDriftDiagnostics` сохраняется как drift detection mechanism и становится input для `TracingConfigDriftStatus`. В PR-10 reconciler читает drift status из него как часть `TracingConfigApplyResult`.

#### `SamplingControlClient` — сохранить без изменений интерфейса

Интерфейс `SamplingControlClient` не меняется. Reconciler делегирует ему apply операции. После PR-3 (JMX wire spike) внутренняя реализация адаптируется под Map-based wire, но вызывающий код (reconciler, actuator) остаётся неизменным.

---

### 8.4. Новые target-only классы

Все нижеперечисленные классы **предполагаются target architecture**; они не существуют в текущей кодовой базе. Вводятся только в PR-10.

#### `TracingDesiredState`

```java
// platform-tracing-spring-boot-autoconfigure
// autoconfigure.configsource package
public record TracingDesiredState(
    double samplingRatio,
    Map<String, Double> routeRatios,
    boolean killSwitchEnabled,
    boolean qaTraceEnabled,
    boolean scrubbingEnabled,
    String scrubbingMode,
    boolean validationEnabled,
    String validationMode,
    boolean enrichingEnabled,
    String sourceType  // TracingConfigSourceType
) {}
```

- Содержит **только policy fields** (не topology)
- Неизменяемый record (не JavaBean)
- Строится из `TracingProperties` reconciler'ом; topology поля в `TracingProperties` игнорируются

#### `TracingConfigSourceType`

```java
public enum TracingConfigSourceType {
    CONFIG_SERVER,      // получено через Spring Cloud Config Server
    REFRESH_SCOPE,      // получено через @RefreshScope EnvironmentChangeEvent
    ACTUATOR_DEV,       // применено через Actuator (dev-only mutation)
    BOOTSTRAP_DEFAULT   // стартовые defaults
}
```

#### `TracingConfigApplyResult`

```java
public record TracingConfigApplyResult(
    boolean applied,
    TracingConfigSourceType sourceType,
    String rejectedReason,   // null если applied=true
    boolean agentUnavailable,
    boolean topologyFieldRejected,
    Instant appliedAt
) {}
```

#### `TracingConfigDriftStatus`

```java
public record TracingConfigDriftStatus(
    boolean driftDetected,
    Map<String, Object> desiredValues,  // из TracingDesiredState
    Map<String, Object> appliedValues,  // из JMX read-back
    Instant lastCheckedAt
) {}
```

#### `TracingConfigReconciler`

Основной новый класс. Регистрируется как `@Bean` в `platform-tracing-spring-boot-autoconfigure` только при `platform.tracing.reconciler.enabled=true` (default: `false` до PR-12).

```
Ответственность:
  1. Слушать EnvironmentChangeEvent / RefreshEvent
  2. Построить TracingDesiredState из TracingProperties
  3. Реджектить topology fields (reject + log + return rejected result)
  4. Валидировать policy (samplingRatio in [0,1], non-null modes)
  5. При невалидном state — НЕ применять, вернуть TracingConfigApplyResult(applied=false)
  6. Делегировать apply в SamplingControlClient
  7. Обновить TracingConfigDriftStatus через DualChannelDriftDiagnostics
  8. Эмитировать Micrometer metric platform.tracing.config.apply.result

Не реализует:
  - Логику JMX wire (делегируется SamplingControlClient)
  - Startup bootstrap (RuntimeConfigApplier остаётся для startup path)
  - Actuator READ (TracingActuatorEndpoint читает состояние независимо)
```

---

### 8.5. Поэтапный rollout

#### Этап 1: Skeleton, default disabled (PR-10 начало)

- Добавить `TracingDesiredState`, `TracingConfigApplyResult`, `TracingConfigDriftStatus`, `TracingConfigSourceType` как пустые record/enum классы
- Добавить `TracingConfigReconciler` с `@ConditionalOnProperty("platform.tracing.reconciler.enabled")` (default `false`)
- `RuntimeConfigApplier` продолжает работать на всех инсталляциях
- Тесты: класс существует, bean не создаётся при `reconciler.enabled=false`

#### Этап 2: Параллельный путь (PR-10 основная работа)

- `TracingConfigReconciler` регистрируется рядом с `RuntimeConfigApplier` при `reconciler.enabled=true`
- Оба пути слушают `EnvironmentChangeEvent`; reconciler логирует apply result без side effects на `RuntimeConfigApplier`
- `DualChannelDriftDiagnostics` обновляется для передачи drift data в reconciler
- Тесты: оба пути работают независимо; reconciler не нарушает `RuntimeConfigApplierTest`

#### Этап 3: Read-only диагностика (PR-10 финал)

- `TracingActuatorEndpoint` расширяется: `GET /actuator/tracing` возвращает `TracingConfigDriftStatus` и последний `TracingConfigApplyResult`
- Reconciler пишет apply results в in-memory buffer (последние N результатов)
- `RuntimeConfigApplier` остаётся работающим; reconciler — опциональный диагностический слой

#### Этап 4: Production Actuator READ-only guard (PR-11)

- `TracingActuatorEndpoint.WriteOperation` (`POST`) guards через `@ConditionalOnProperty("platform.tracing.actuator.mutation.enabled")` (default `false`)
- В production deployment mutation endpoint физически недоступен
- Dev profile: `platform.tracing.actuator.mutation.enabled=true` в `application-dev.yaml`
- E2E тест: SUT без dev profile → `POST /actuator/tracing/samplingRatio` → 404 или 405

#### Этап 5: Controlled enablement (PR-12)

- Default `reconciler.enabled` меняется на `true` только после E6 evidence
- `RuntimeConfigApplier` помечается `@Deprecated` (не удаляется в волне 1)
- Config Server refresh integration проверяется M10 macro perf сценарием

---

### 8.6. Обязательные тесты

Все тесты ниже относятся к PR-10 и PR-11.

| Тест | Поведение | PR |
|------|-----------|----|
| `TracingConfigReconcilerTest#configServerRefreshUpdatesDesiredState` | Публикация `EnvironmentChangeEvent` c новым `samplingRatio=0.5` → reconciler строит корректный `TracingDesiredState` | PR-10 |
| `TracingConfigReconcilerTest#topologyFieldRejected` | Попытка применить `exporter.endpoint` через reconciler → `TracingConfigApplyResult(applied=false, topologyFieldRejected=true)` | PR-10 |
| `TracingConfigReconcilerTest#invalidPolicyPreservesLkg` | Невалидный `samplingRatio=-1` → reconciler возвращает `applied=false`, JMX не вызывается, LKG в agent не нарушен | PR-10 |
| `TracingConfigReconcilerTest#agentAbsentDegradedApplyStatus` | `SamplingControlClient` недоступен → `TracingConfigApplyResult(agentUnavailable=true, applied=false)` | PR-10 |
| `TracingConfigReconcilerTest#desiredNotEqualAppliedDriftMetric` | Reconciler apply success, но JMX read-back отличается → drift metric эмитируется | PR-10 |
| `TracingActuatorEndpointTest#readReturnsDriftStatus` | `GET /actuator/tracing` → body содержит `driftDetected`, `lastCheckedAt`, последний `applyResult` | PR-10 |
| `TracingActuatorEndpointTest#mutationDisabledInProd` | SUT без `platform.tracing.actuator.mutation.enabled=true` → `POST /actuator/tracing/samplingRatio` → 404/405 | PR-11 |
| `ActuatorMutationProdGuardE2ETest` | E2E: agent deployment без dev profile → Actuator mutation endpoint недоступен | PR-11 |
| `RuntimeConfigApplierTest#remainsFunctionalWhileReconcilerEnabled` | При включённом reconciler `RuntimeConfigApplierTest` продолжает зеленеть без изменений | PR-10 |

---

## 9. ClassLoader и JMX — стратегия миграции

### 9.1. Текущее состояние

#### Топология ClassLoader'ов

```
┌──────────────────────────────────────────────────────┐
│  JVM Process                                         │
│                                                      │
│  ┌─────────────────────────────────────────────┐     │
│  │  Agent ClassLoader (isolated)               │     │
│  │  platform-tracing-otel-extension (agent jar)│     │
│  │  platform-tracing-api (embedded copy)       │     │
│  │  OTel SDK + SPI                             │     │
│  │                                             │     │
│  │  PlatformTracingControl (MBean server)      │     │
│  │  SamplerStateHolder                         │     │
│  │  DomainConfigHolder<SamplerState>           │     │
│  └─────────────────────────────────────────────┘     │
│                           ↑ JMX MBeanServer           │
│                           │ (in-process, no network) │
│  ┌─────────────────────────────────────────────┐     │
│  │  Application ClassLoader                    │     │
│  │  Spring Boot fat JAR                        │     │
│  │  platform-tracing-api (app copy)            │     │
│  │  platform-tracing-core                      │     │
│  │  platform-tracing-spring-boot-autoconfigure │     │
│  │                                             │     │
│  │  SamplingControlClient (JMX client)         │     │
│  │  TracingProperties                          │     │
│  └─────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────┘
```

**Ключевые факты о текущем дизайне:**

- `platform-tracing-api` загружается в **обоих** ClassLoader'ах: в App CL из fat JAR и в Agent CL как embedded copy внутри `agentExtensionJar`
- Это нормально: `platform-tracing-api` является CL-neutral (JDK-only зависимости)
- `SamplingControlClient` (App CL) **не импортирует** типы из `platform-tracing-otel-extension` — cross-CL boundary пересекается через `MBeanServer.invoke()` по имени операции
- Текущие payload-типы через JMX: примитивы (`double`), `String[]`, частичные `Map<String, Object>` операции
- Raw Java DTO через CL boundary **не передаётся** — текущий дизайн специально избегает этого
- `ClassLoaderVisibilitySpikeE2ETest` и Gradle task `verifyExtensionDeps` охраняют этот инвариант

#### Текущие MBean операции (частичный список, `PlatformTracingControl`)

| Операция | Payload | Тип | Статус |
|----------|---------|-----|--------|
| `setSamplingRatio(double)` | примитив | policy | primitive wire |
| `getSamplingRatio()` | `double` | read | primitive wire |
| `getExtensionStatus()` | `Map<String, Object>` | read | Map wire |
| `reloadConfig(Map<String, Object>)` | `Map` | policy | partial Map — **MIGRATION_RISK** |
| `getInvalidConfigCounter()` | `int` | diagnostics | primitive wire |

Частичная `Map`-based реализация в `PlatformTracingControl` методах для reload операций указана в инвентаре как **Map payloads partial** — не все операции переведены на единый wire формат.

---

### 9.2. Целевое состояние

**Принципы target wire:**

1. **Private in-process JMX only** — MBean зарегистрирован в `ManagementFactory.getPlatformMBeanServer()` с именем из константы в `platform-tracing-api`; никакого remote JMX exposure в production
2. **Validated Map / OpenMBean-compatible wire** — все payload'ы через JMX передаются как `Map<String, Object>` с явной схемой; типы значений — только JMX open types (String, Integer, Double, Long, Boolean, String[])
3. **Нет raw Java DTO через CL boundary** — нет `instanceof` проверок на extension-side типы из App CL и наоборот
4. **`PlatformTracingControl` остаётся приватным agent-side адаптером** — не является частью public API; его имя MBean и операции определяются константами в `platform-tracing-api`
5. **`SamplingControlClient` остаётся app-side клиентом** — интерфейс не меняется для вызывающего кода (reconciler, actuator)
6. **`CompositeData` как fallback** — если Map-based wire окажется слишком loose (слабая типизация, нет schema enforcement), переход на `CompositeData`/`CompositeType` (OpenMBean standard) документируется как запланированный follow-up

**Target wire schema (PR-2, `platform-tracing-api`):**

```java
// Константы схемы в platform-tracing-api
public final class TracingControlWireSchema {
    // Key names
    public static final String KEY_SAMPLING_RATIO      = "samplingRatio";
    public static final String KEY_ROUTE_RATIOS        = "routeRatios";     // Map<String,Double>
    public static final String KEY_KILL_SWITCH         = "killSwitch";
    public static final String KEY_QA_TRACE            = "qaTrace";
    public static final String KEY_CONTRACT_VERSION    = "contractVersion"; // String, semantic versioning
    public static final String KEY_POLICY_VERSION      = "policyVersion";   // String, content hash/timestamp
    
    // Validation helpers (используются в обоих CL)
    public static boolean isTopologyKey(String key) { ... }
    public static boolean isPolicyKey(String key) { ... }
}
```

Схема живёт в `platform-tracing-api` (доступен в обоих CL), что позволяет и `SamplingControlClient` (App CL), и `PlatformTracingControl` (Agent CL) использовать одни и те же строковые константы без cross-CL type reference.

---

### 9.3. Шаги миграции

#### PR-2: Wire schema v1 в `platform-tracing-api`

**Объём:**
- Создать `TracingControlWireSchema` — только константы и validation helpers
- Создать `TracingWireSchemaVersion` — semantic version string (e.g., `"1.0"`)
- Добавить unit тесты: `TracingControlWireSchemaTest` — schema constants not null, topology/policy key classification

**Критичное ограничение:** PR-2 **не изменяет** поведение `PlatformTracingControl` или `SamplingControlClient`. Это только additive — новые классы в api. Обратная совместимость полная.

**Верификация:**
- `ArchUnit` правило в PR-4: `autoconfigure` не импортирует `otel-extension` типы (кроме `platform-tracing-api`)
- `TracingControlWireSchemaTest` — зелёный

#### PR-3: Cross-CL JMX wire spike

**Объём:**
- Spike: реализовать одну операцию (`setSamplingRatio`) через Map wire в параллель к существующему primitive wire
- `PlatformTracingControl` добавляет `updatePolicy(Map<String, Object>)` операцию
- `SamplingControlClient` добавляет `updatePolicy(Map<String, Object>)` метод
- Оба пути работают; новый путь прикрыт feature flag (`platform.tracing.jmx.wire.v1=false` default)
- E2E: `ClassLoaderVisibilitySpikeE2ETest` остаётся зелёным
- `RuntimeSamplingControlSmokeTest` остаётся зелёным на старом пути

**Документирует:**
- Режим `CompositeData` как альтернативу если Map слишком loose
- Поведение при неизвестном ключе в Map (log + ignore vs reject)
- Поведение при неверном типе значения (reject + `invalidConfigCounter` increment)

#### Последующие PR (после PR-6/PR-7/PR-8): Перевод существующих методов

После extraction sampling/scrubbing policy в core (PR-6/PR-7) возникает возможность унифицировать wire под Map-based формат. Существующие primitive методы (`setSamplingRatio(double)`) мигрируют внутрь `updatePolicy(Map)`:

```
PR-6 (sampling extraction): SamplerState → Map serialization helper (в core)
PR-7 (scrubbing extraction): ScrubbingSnapshot → Map serialization helper (в core)  
PR-8 (validation/enrichment): ValidationSnapshot, EnrichingSnapshot → Map helpers
```

Каждый из этих PR добавляет Map serialization в core (без OTel зависимостей) и обновляет `PlatformTracingControl` на использование deserialized policy objects из Map.

#### PR-10: Полный Map wire для всех операций

После reconciler introduction все runtime-mutable операции идут через `updatePolicy(Map)`. Primitive `setSamplingRatio(double)` помечается `@Deprecated` но не удаляется в волне 1.

---

### 9.4. Обязательные тесты

| Тест | Покрываемое поведение | PR |
|------|-----------------------|----|
| `TracingControlWireSchemaTest#validMapPayloadRoundTrip` | Map с `KEY_SAMPLING_RATIO=0.5` → `PlatformTracingControl.updatePolicy()` → `SamplerStateHolder.current().ratio == 0.5` | PR-3 |
| `PlatformTracingControlTest#invalidValueTypeRejected` | Map с `KEY_SAMPLING_RATIO="not-a-double"` → `invalidConfigCounter` инкрементируется; LKG не нарушен | PR-3 |
| `PlatformTracingControlTest#unknownKeyIgnoredPerPolicy` | Map с `"unknownKey"="value"` → не бросает exception; операция применяется (остальные ключи валидны) | PR-3 |
| `PlatformTracingControlTest#topologyFieldRejected` | Map с `"exporter.endpoint"="http://..."` → rejected, `invalidConfigCounter` инкрементируется | PR-3 |
| `SamplingControlClientTest#rawDtoFailureModeDocumented` | Попытка передать non-open-type значение через JMX → `SamplingControlUnavailableException` или `Optional.empty()`; не ClassCastException | PR-3 |
| `RuntimeSamplingControlSmokeTest` | E2E: sampling control через JMX работает после введения wire schema | PR-3, обязательно зелёный |
| `PlatformTracingControlTest` | Адаптировать существующие тесты для `updatePolicy(Map)` параллельного пути | PR-3 |
| `SamplingControlClientTest` | Адаптировать: добавить `updatePolicy(Map)` тест cases | PR-3 |
| `ClassLoaderVisibilitySpikeE2ETest` | App CL не видит Agent CL типы при работе с Map wire | PR-3, зелёный |
| `TracingControlWireSchemaTest#contractVersionPresent` | Map payload содержит `KEY_CONTRACT_VERSION`; agent проверяет версию | PR-3 |

---

## 10. Высокорискованные области миграции

Таблица упорядочена по убыванию severity: `CRITICAL` → `HIGH` → `MEDIUM`.

---

### Риск 1: Потеря sampling семантики

| Атрибут | Значение |
|---------|---------|
| **Риск** | Изменение порядка или логики rule chain `KillSwitch → ForceHeader → QaTrace → RouteRatio → DefaultRatio → HardDrop → ParentDecision` при extraction в core |
| **Почему рискованно** | Sampling — горячий путь каждого root span. Изменение порядка правил → неверный sampling rate в production → compliance / observability incident. Rule chain тестируется as-a-whole в `CompositeSamplerTest`, но не каждая перестановка |
| **Затронутые модули** | `platform-tracing-otel-extension`, `platform-tracing-core` (после PR-6) |
| **Затронутые классы** | `CompositeSampler`, `KillSwitchRule`, `ForceHeaderRule`, `QaTraceRule`, `RouteRatioRule`, `DefaultRatioRule`, `HardDropRule`, `ParentDecisionRule`, `SamplerStateHolder` |
| **Существующие защиты** | `CompositeSamplerTest`, `CompositeSamplerEdgeCasesTest`, `RouteRatioTest`, `SamplerRuntimeUpdateConcurrencyTest`, `RuntimeSamplingControlSmokeTest` (e2e), `CompositeSamplerBenchmark`, `CompositeSamplerPolicyBranchesBenchmark` |
| **Отсутствующие защиты** | Нет characterization test на полный порядок правил с explicit ordering assertion; нет теста pure policy в JDK-only окружении (без OTel) до PR-5 |
| **Рекомендуемый PR** | PR-5 (дублировать тесты правил в core), PR-6 (extraction) |
| **Mitigation** | DUPLICATE_BEFORE_MOVE: все rule тесты дублировать в core test source до PR-6; добавить ordering characterization test; `CompositeSamplerBenchmark` baseline до/после |
| **Требует review** | Security review: нет; SRE review: **да** — sampling rate изменение влияет на ingestion cost |

---

### Риск 2: Потеря обязательного PII scrubbing

| Атрибут | Значение |
|---------|---------|
| **Риск** | Нарушение mandatory baseline scrubbing при extraction rule engine в core; fail-open семантика теряется или инвертируется |
| **Почему рискованно** | Scrubbing является **mandatory** — не optional tier. Если scrubbing engine бросает exception и он не перехвачен, span экспортируется с PII данными. Rule circuit breaker должен продолжать работу после extraction |
| **Затронутые модули** | `platform-tracing-otel-extension`, `platform-tracing-core` (после PR-7) |
| **Затронутые классы** | `ScrubbingSpanProcessor`, `ScrubbingPolicyHolder`, `scrubbing.engine.*`, `BuiltInRules`, `RuleCircuitBreaker` |
| **Существующие защиты** | `ScrubbingSpanProcessorTest`, `ScrubbingSecurityNegativeTest`, `MergeEngineTest`, `RuleCircuitBreakerTest`, `ExceptionEventScrubbingE2ETest`, `ScrubbingEngineBenchmark` |
| **Отсутствующие защиты** | Нет изолированного теста fail-open: engine exception → span export continues — на уровне pure core без OTel; нет теста что `ScrubbingSecurityNegativeTest` (ReDoS) проходит в core без OTel context |
| **Рекомендуемый PR** | PR-5 (обязательно дублировать `ScrubbingSecurityNegativeTest` в core), PR-7 (extraction) |
| **Mitigation** | MUST_KEEP all scrubbing tests; `ScrubbingEngineBenchmark` before/after PR-7; circuit breaker тесты в core до extraction |
| **Требует review** | Security review: **да** — PII compliance; SRE review: **да** |

---

### Риск 3: Потеря обязательных span атрибутов

| Атрибут | Значение |
|---------|---------|
| **Риск** | После extraction `ValidatingSpanProcessor` policy в core — mandatory attributes (`platform.trace.type`, `service.name`, `platform.cgroup`) перестают валидироваться или `ValidationMode.STRICT` перестаёт работать |
| **Почему рискованно** | Mandatory attributes — контрактное требование для downstream processing (routing, alerting). Silent loss не обнаруживается в unit тестах если adapter неправильно делегирует в core policy |
| **Затронутые модули** | `platform-tracing-otel-extension`, `platform-tracing-core` (после PR-8) |
| **Затронутые классы** | `ValidatingSpanProcessor`, `ValidationPolicyHolder`, `CategoryContracts`, `ValidationMode` |
| **Существующие защиты** | `ValidatingSpanProcessorTest`, `ValidationPolicyRuntimeTest`, `CategoryContractsTest` |
| **Отсутствующие защиты** | Нет теста `ValidationMode.STRICT` vs `LENIENT` в core без OTel context |
| **Рекомендуемый PR** | PR-5 (дублировать `ValidationPolicyRuntimeTest` в core), PR-8 |
| **Mitigation** | Тест: validation enforcement результат идентичен до/после extraction; adapter тест: OTel processor делегирует в core policy корректно |
| **Требует review** | Security review: нет; SRE review: **да** |

---

### Риск 4: Поломка export safety

| Атрибут | Значение |
|---------|---------|
| **Риск** | Изменение поведения `PlatformDropOldestExportSpanProcessor` drop-oldest семантики или `SafeSpanExporter` fail-safe при изменении зависимостей в PR-9 / PR-12 |
| **Почему рискованно** | Export overflow → silent span loss. BSP без custom processor → export storm при Collector failure |
| **Затронутые модули** | `platform-tracing-otel-extension` |
| **Затронутые классы** | `PlatformDropOldestExportSpanProcessor`, `SafeSpanExporter`, `DegradedModeController`, `CircuitBreaker` |
| **Существующие защиты** | `PlatformDropOldestExportSpanProcessorTest` (overflow, lifecycle, builder), `SafeSpanExporterTest`, `BspDropOldestSafetyAgentSmokeTest`, `QueueOfferBenchmark` |
| **Отсутствующие защиты** | Нет теста: queue size изменение через `TracingProperties` → processor rebuilds correctly (не restart artifact) |
| **Рекомендуемый PR** | PR-0 (зафиксировать baseline), нет изменений в волне 1 без явного PR |
| **Mitigation** | Не трогать `PlatformDropOldestExportSpanProcessor` в волне 1; `QueueOfferBenchmark` baseline до PR-12 |
| **Требует review** | Security review: нет; SRE review: **да** |

---

### Риск 5: Поломка Spring property binding

| Атрибут | Значение |
|---------|---------|
| **Риск** | Silent bind failure при рефакторинге `TracingProperties` или добавлении reconciler beans — `platform.tracing.*` перестаёт биндиться без exception |
| **Почему рискованно** | Silent failure — application стартует с default конфигурацией вместо configured; sampling ratio 0.0 вместо 0.1 не обнаруживается до production incident |
| **Затронутые модули** | `platform-tracing-spring-boot-autoconfigure` |
| **Затронутые классы** | `TracingProperties`, все 13 `AutoConfiguration` классов |
| **Существующие защиты** | `TracingPropertiesBindingTest`, `TracingAutoConfigurationTest`, `SharedDefaultsAlignmentTest` |
| **Отсутствующие защиты** | Нет negative test: неизвестный `platform.tracing.unknown.key` не вызывает startup failure (или наоборот — вызывает, если так задумано) |
| **Рекомендуемый PR** | PR-0 (baseline), PR-10 (reconciler addition) |
| **Mitigation** | `TracingPropertiesBindingTest` запускать в CI на каждом PR; при добавлении reconciler bean проверять что `@ConditionalOnProperty` не нарушает существующий binding |
| **Требует review** | Security review: нет; SRE review: **да** |

---

### Риск 6: Поломка WebMVC поведения

| Атрибут | Значение |
|---------|---------|
| **Риск** | Утечка WebFlux beans в Servlet stack или нарушение duplicate span suppression при изменении dependency graph в PR-1/PR-4 |
| **Почему рискованно** | Дублирование HTTP spans в MVC приложении. В Servlet deployment без WebFlux — `ClassNotFoundException` при lazy init |
| **Затронутые модули** | `platform-tracing-autoconfigure-webmvc`, `platform-tracing-spring-boot-starter-servlet` |
| **Затронутые классы** | `ServletTracingAutoConfiguration`, `TraceResponseHeaderServletFilter`, `PlatformServerRequestObservationConvention` |
| **Существующие защиты** | `WebStackIsolationTest`, `DuplicateSpansRegressionMatrixTest` (webmvc часть), `TraceResponseHeaderServletFilterTest` |
| **Отсутствующие защиты** | Нет автоматизированного classpath isolation test: стартер не тянет `reactor-core` |
| **Рекомендуемый PR** | PR-1 (taxonomy guardrails), CI на всех PR |
| **Mitigation** | `WebStackIsolationTest` обязателен в CI gate на каждом PR; ArchUnit rule: `webmvc` не импортирует `reactor.*` |
| **Требует review** | Security review: нет; SRE review: нет |

---

### Риск 7: Поломка WebFlux поведения

| Атрибут | Значение |
|---------|---------|
| **Риск** | Потеря Reactor context propagation → trace ID не пробрасывается через `publishOn`/`subscribeOn`; MDC пуст в реактивных цепочках |
| **Почему рискованно** | Silent failure: spans создаются, но trace correlation теряется в reactive стеке; обнаруживается только через distributed tracing review |
| **Затронутые модули** | `platform-tracing-autoconfigure-webflux` |
| **Затронутые классы** | `TracingReactorEagerInitConfiguration`, `BridgeOtelReactorContextPropagation`, `TracingReactorContextPropagationStartupRunner` |
| **Существующие защиты** | `TracingReactorEagerInitConfigurationTest`, `ReactorContextPropagationIntegrationTest`, `MdcPropagationWebFluxIntegrationTest` |
| **Отсутствующие защиты** | Нет теста `BridgeOtelReactorContextPropagation` не инициализируется в Servlet-only context |
| **Рекомендуемый PR** | PR-0 (baseline), CI на всех PR |
| **Mitigation** | `TracingReactorEagerInitConfigurationTest` в CI gate; ArchUnit rule: `webflux` не импортирует `servlet.*` |
| **Требует review** | Security review: нет; SRE review: нет |

---

### Риск 8: Поломка starter developer experience

| Атрибут | Значение |
|---------|---------|
| **Риск** | Accidental transitive dependency добавляется в стартер → consumer dependency graph ломается; BOM version conflict |
| **Почему рискованно** | Любое изменение transitive dependency стартера требует coordination с 50+ downstream consumers. Startup failure у consumers — критический инцидент |
| **Затронутые модули** | `platform-tracing-spring-boot-starter-servlet`, `platform-tracing-spring-boot-starter-reactive` |
| **Затронутые классы** | `build.gradle` файлы стартеров (0 Java классов) |
| **Существующие защиты** | Dependency-only модули (нет Java кода); `DuplicateHttpSpanAgentSmokeTest`, `PlatformExtensionAgentSmokeTest` (e2e) |
| **Отсутствующие защиты** | Нет automated test: стартер-servlet не содержит `reactor-core` в compile classpath |
| **Рекомендуемый PR** | PR-1 (ArchUnit classpath isolation rules для стартеров) |
| **Mitigation** | ArchUnit rule: starter dependencies проверяются на запрещённые transitive imports; BOM pin на каждый external dependency |
| **Требует review** | Security review: нет; SRE review: нет; **Platform team lead review: да** |

---

### Риск 9: Поломка JMX runtime updates

| Атрибут | Значение |
|---------|---------|
| **Риск** | Cross-CL boundary нарушается при введении Map wire (PR-3) — `ClassCastException` или `IncompatibleClassChangeError` при JMX invoke |
| **Почему рискованно** | Ops теряет возможность менять sampling ratio через Actuator/JMX в production без redeploy. M10 macro scenario поломан |
| **Затронутые модули** | `platform-tracing-otel-extension`, `platform-tracing-spring-boot-autoconfigure` |
| **Затронутые классы** | `PlatformTracingControl`, `SamplingControlClient`, `TracingActuatorEndpoint` |
| **Существующие защиты** | `PlatformTracingControlTest`, `SamplingControlClientTest`, `RuntimeSamplingControlSmokeTest` (e2e, CRITICAL) |
| **Отсутствующие защиты** | Нет теста Map round-trip: payload serialized в App CL, deserialized в Agent CL — ни один объект не пересекает CL boundary как Java type |
| **Рекомендуемый PR** | PR-2 (wire schema), PR-3 (spike — обязательно) |
| **Mitigation** | `RuntimeSamplingControlSmokeTest` зелёный — жёсткий gate для PR-3; `ClassLoaderVisibilitySpikeE2ETest` зелёный; feature flag на новом wire пути до подтверждения в E2E |
| **Требует review** | Security review: нет; SRE review: **да** — операционный control plane |

---

### Риск 10: Поломка RefreshScope / RuntimeConfigApplier

| Атрибут | Значение |
|---------|---------|
| **Риск** | При введении `TracingConfigReconciler` (PR-10) `RuntimeConfigApplier` перестаёт вызываться или вызывается дважды при одном RefreshEvent |
| **Почему рискованно** | Двойной apply → `invalidConfigCounter` инкрементируется без причины; частичный apply → drift без detection |
| **Затронутые модули** | `platform-tracing-spring-boot-autoconfigure` |
| **Затронутые классы** | `RuntimeConfigApplier`, `TracingRefreshScopeAutoConfiguration`, `TracingConfigReconciler` (новый) |
| **Существующие защиты** | `RuntimeConfigApplierTest`, `DualChannelDriftDiagnosticsTest` |
| **Отсутствующие защиты** | Нет теста: оба bean слушают одно `EnvironmentChangeEvent` → только одна JMX операция вызывается |
| **Рекомендуемый PR** | PR-10 |
| **Mitigation** | Reconciler и `RuntimeConfigApplier` должны быть mutual-exclusive или reconciler явно предотвращает double-apply; `RuntimeConfigApplierTest` должен оставаться зелёным при включённом reconciler |
| **Требует review** | Security review: нет; SRE review: **да** |

---

### Риск 11: Случайное exposure Actuator MUTATION в production

| Атрибут | Значение |
|---------|---------|
| **Риск** | `TracingActuatorEndpoint.WriteOperation` доступна в production deployment → оператор или атакующий может изменить sampling ratio до 0.0 (drop all spans) или включить aggressive scrubbing mode |
| **Почему рискованно** | Нет prod/dev guard в текущем коде — `WriteOperation` регистрируется unconditionally при наличии actuator на classpath. Инвентарь явно отмечает: `MIGRATION_RISK — Current Actuator MUTATION exposure: YES, no prod disable guard found` |
| **Затронутые модули** | `platform-tracing-spring-boot-autoconfigure` |
| **Затронутые классы** | `TracingActuatorEndpoint`, `TracingActuatorAutoConfiguration` |
| **Существующие защиты** | `TracingActuatorEndpointTest` (но не тестирует prod guard — guard отсутствует) |
| **Отсутствующие защиты** | Нет теста: production profile → `WriteOperation` недоступна; нет E2E теста для этого |
| **Рекомендуемый PR** | PR-11 (production READ-only Actuator + dev-only mutation guard) |
| **Mitigation** | `@ConditionalOnProperty("platform.tracing.actuator.mutation.enabled", havingValue="true", matchIfMissing=false)` на `WriteOperation`; PR-11 добавляет E2E тест для этого |
| **Требует review** | Security review: **да** — mutation as attack vector; SRE review: **да** |

---

### Риск 12: Утечка OTel типов в pure core

| Атрибут | Значение |
|---------|---------|
| **Риск** | После PR-6/PR-7/PR-8 extraction в `platform-tracing-core` туда случайно попадают `import io.opentelemetry.*` — нарушение Clean Core изоляции |
| **Почему рискованно** | Core с OTel dependency невозможно тестировать без OTel SDK; future reuse в non-OTel context невозможен; ClassLoader изоляция нарушена концептуально |
| **Затронутые модули** | `platform-tracing-core` |
| **Затронутые классы** | Любые новые policy классы в core после extraction |
| **Существующие защиты** | `OtelSdkArchRulesTest` (частичный) |
| **Отсутствующие защиты** | Нет ArchUnit rule специфически для `platform-tracing-core`: «no OTel imports» — до PR-4 |
| **Рекомендуемый PR** | PR-4 (ArchUnit fitness functions) |
| **Mitigation** | PR-4: добавить `noOtelInCore` ArchUnit rule; CI gate blocks extraction PR если rule fails |
| **Требует review** | Security review: нет; SRE review: нет |

---

### Риск 13: Утечка Spring типов в pure core

| Атрибут | Значение |
|---------|---------|
| **Риск** | Policy классы в `platform-tracing-core` начинают импортировать `org.springframework.*` — нарушение Clean Core изоляции |
| **Почему рискованно** | Core становится Spring-dependent → не может быть протестирован без Spring context; agent-side reuse невозможен (agent не имеет Spring) |
| **Затронутые модули** | `platform-tracing-core`, `platform-tracing-otel-extension` |
| **Затронутые классы** | Любые новые policy классы |
| **Существующие защиты** | `TracingArchRulesTest` (общие правила) |
| **Отсутствующие защиты** | Нет правила `noSpringInCore`; нет правила `noSpringInExtension` |
| **Рекомендуемый PR** | PR-4 (ArchUnit fitness functions) |
| **Mitigation** | PR-4: `noSpringInCore`, `noSpringInExtension` ArchUnit rules; PR-1: `build.gradle` dependency declarations не добавляют Spring в core/extension |
| **Требует review** | Security review: нет; SRE review: нет |

---

### Риск 14: Потеря benchmark comparability

| Атрибут | Значение |
|---------|---------|
| **Риск** | JMH benchmark классы переименовываются или `@Param` значения изменяются до PR-0 baseline capture → baseline несравним с post-extraction результатами |
| **Почему рискованно** | Нет доказательной базы для E6 gate; performance regression незаметна; committee review не проходит |
| **Затронутые модули** | `platform-tracing-bench` |
| **Затронутые классы** | `CompositeSamplerBenchmark`, `ScrubbingEngineBenchmark`, `QueueOfferBenchmark`, все 16 JMH классов |
| **Существующие защиты** | `PerformanceReleaseGateTest` (hard budget contract) |
| **Отсутствующие защиты** | Нет CI check на: benchmark class names unchanged from baseline; нет frozen `@Param` contract test |
| **Рекомендуемый PR** | PR-0 (baseline capture) |
| **Mitigation** | Правило: JMH class names и `@Param` заморожены до PR-0 завершения; обновление deps в bench module только после extraction — никогда до |
| **Требует review** | Security review: нет; SRE review: нет |

---

### Риск 15: Преждевременный module collapse

| Атрибут | Значение |
|---------|---------|
| **Риск** | В процессе extraction предлагается объединить `platform-tracing-core` и `platform-tracing-otel-extension` или другие модули — нарушение CL isolation |
| **Почему рискованно** | Agent-side код и Application-side код оказываются в одном artifact → классы из Application CL загружаются в Agent CL или наоборот → `ClassCastException` при runtime |
| **Затронутые модули** | Любые два модуля из Internal Runtime группы |
| **Затронутые классы** | Все |
| **Существующие защиты** | Инвентарь явно запрещает collapse в волне 1: `DONOTCOLLAPSENOW`; отдельные `settings.gradle` записи |
| **Отсутствующие защиты** | Нет автоматизированного теста что `agentExtensionJar` не содержит Spring классов; нет теста что app jar не содержит OTel agent extension SPI |
| **Рекомендуемый PR** | PR-1 (dependency guardrails) |
| **Mitigation** | Явное правило: module collapse рассматривается только в PR-13 review (deferred simplification); ArchUnit: `otel-extension` не зависит от Spring; `autoconfigure` не зависит от `otel-extension` в main scope |
| **Требует review** | Security review: нет; SRE review: **да** — ClassLoader topology is ops concern |

---

### Сводная таблица рисков

| # | Риск | Severity | PR | Security | SRE |
|---|------|----------|----|----------|-----|
| 1 | Потеря sampling семантики | **CRITICAL** | PR-5, PR-6 | нет | **да** |
| 2 | Потеря PII scrubbing | **CRITICAL** | PR-5, PR-7 | **да** | **да** |
| 3 | Потеря mandatory span атрибутов | HIGH | PR-5, PR-8 | нет | **да** |
| 4 | Поломка export safety | HIGH | PR-0, PR-12 | нет | **да** |
| 5 | Поломка Spring property binding | HIGH | PR-0, PR-10 | нет | **да** |
| 6 | Поломка WebMVC поведения | HIGH | PR-1, CI | нет | нет |
| 7 | Поломка WebFlux поведения | HIGH | PR-0, CI | нет | нет |
| 8 | Поломка starter experience | MEDIUM | PR-1 | нет | нет |
| 9 | Поломка JMX runtime updates | HIGH | PR-2, PR-3 | нет | **да** |
| 10 | Поломка RefreshScope/RuntimeConfigApplier | MEDIUM | PR-10 | нет | **да** |
| 11 | Actuator MUTATION exposure в prod | **CRITICAL** | PR-11 | **да** | **да** |
| 12 | OTel типы в pure core | MEDIUM | PR-4 | нет | нет |
| 13 | Spring типы в pure core | MEDIUM | PR-4 | нет | нет |
| 14 | Потеря benchmark comparability | MEDIUM | PR-0 | нет | нет |
| 15 | Преждевременный module collapse | HIGH | PR-1, PR-13 | нет | **да** |

---

## 11. Implementation guardrails for Cursor Composer

### 11.1. Глобальные правила

Эти правила обязательны для **любой** Cursor Composer-сессии по этому репозиторию:

- Реализовывать **только один PR за раз**; не собирать несколько PR в один change-set.
- **Никогда** не реализовывать несколько PR в одном Composer run; каждый PR — отдельная ветка и отдельный diff.
- Не удалять существующие тесты, benchmarks, e2e tests или perf scenarios; в крайнем случае — только переносить и обновлять, сохраняя поведение.[^1]
- Не коллапсировать Gradle-модули (no module collapse) в первой волне; не объединять `core`, `otel-extension`, `autoconfigure`, `webmvc`, `webflux`, `bench`, `perf-tests`.[^1]
- Не менять production behavior, если конкретный PR этого явно не допускает (PR-0–PR-4 должны быть строго behavior-preserving).[^2][^1]
- Сохранять существующие публичные стартеры `platform-tracing-spring-boot-starter-servlet` и `platform-tracing-spring-boot-starter-reactive` без ломки их зависимостей и контракта.[^1]
- Сохранять публичный `platform-tracing-api` бинарно совместимым, если архитектура/комитет явно не разрешили breaking change.[^2][^1]
- Не добавлять OTel-зависимости в пакеты чистого core (`space.br1440.platform.tracing.core.*`); core должен двигаться в сторону JDK-only, OTel — только в adapter-слоях.[^2][^1]
- Не добавлять Spring-зависимости в пакеты чистого core; Spring должен оставаться только в autoconfigure/стартер модулях.[^2][^1]
- Не вводить зависимость `spring-boot-autoconfigure` → `otel-extension` в main-источниках (никаких main `import` из `space.br1440.platform.tracing.otel.extension` в `autoconfigure`).[^1][^2]
- Не вводить зависимость `otel-extension` → Spring (никаких `org.springframework.*` в `platform-tracing-otel-extension`).[^2][^1]
- Не экспонировать Actuator MUTATION (`WriteOperation`) в production; любые новые mutation endpoints должны быть `dev-only` с явным флагом.[^1]
- Не делать scrubbing опциональным: `ScrubbingSpanProcessor` и связанные политики остаются mandatory baseline.[^1]
- Не переименовывать JMH benchmarks и не менять их `@Param` до завершения PR-0 baseline lock.[^1]
- Обязательно запускать указанные для PR тесты до и после изменений (unit, ArchUnit, e2e, JMH/Perf, где требуется).[^2][^1]

Дополнительный операционный guardrail:

- Для любого PR с JMX/Actuator/Config Server изменениями — **обязательно** запускать `RuntimeSamplingControlSmokeTest`, `TracingActuatorEndpointTest` и `RuntimeConfigApplierTest` до merge.[^2][^1]

***

### 11.2. PR-specific Composer guardrails

#### PR-0 — Baseline lock

- Не вносить **никаких** production code изменений; только фиксация baseline-метрик, конфигураций и ADR.[^2][^1]
- Не переименовывать JMH benchmark классы, пакеты, `@Param`; не менять их business-логику.[^1]
- Не менять Gradle зависимости, кроме случаев, когда это необходимо для запуска baseline (plugins/tasks/docs).
- Обязательно запускать: все JMH из `platform-tracing-bench`, `PerformanceReleaseGateTest`, `RuntimeSamplingControlSmokeTest`, e2e из `platform-tracing-e2e-tests`, M0/M5 perf (по возможности).[^2][^1]
- Документировать baseline (CPU, RSS, p99), сохранить артефакты в отдельный каталог/артефакт хранилища (например, `perf-baselines/2026-06-11`).[^1][^2]


#### PR-1 — Module taxonomy + dependency guardrails

- Не менять runtime поведение; фокус — Gradle и ArchUnit guardrails.[^2][^1]
- Не добавлять новых зависимостей между модулями; только усиливать запреты (ArchUnit, `enforcedPlatform`, dependency verification).[^1][^2]
- Явно зафиксировать, что `platform-tracing-core` не зависит от Spring/OTel SDK в новом коде; OTel остаётся в `otel-extension`.[^2][^1]
- Добавить ArchUnit тесты:
    - `ExtensionNoSpringDependencyArchTest` — `otel-extension` без Spring.[^2]
    - `AutoconfigureNoExtensionImplementationArchTest` — `autoconfigure` не импортирует `otel-extension` main-классы.[^2]
- Не изменять `settings.gradle` структуру (не удалять модули, не менять имена).


#### PR-2 — Wire schema v1 (platform-tracing-api)

- Не менять поведение JMX/MBean; добавлять только новые DTO/wire-контракты (Map/OpenMBean schema) в `platform-tracing-api`.[^1][^2]
- Не добавлять runtime зависимостей в `platform-tracing-api` кроме JDK и существующего `compileOnly OTel contextapi`.[^1]
- Не перемещать существующие классы API; только добавление новых `control`-пакетов.[^2]
- Обязательно добавить tests на константы, ключи, версию контракта; не связывать их с `otel-extension`.[^2]


#### PR-3 — Cross-CL JMX wire spike

- Не удалять существующие primitive операции `setSamplingRatio(double)` и др.; новый Map wire — **параллельный** путь под feature flag.[^1][^2]
- Не ломать `RuntimeSamplingControlSmokeTest`; он должен проходить на старом пути при выключенном флаге.[^1][^2]
- Не добавлять прямые типы из App CL в Agent CL и наоборот; всё cross-CL взаимодействие — только через JMX open types.[^1][^2]
- Обязательно протестировать: Map round-trip, invalid types, unknown keys (согласно выбранной политике).[^2][^1]


#### PR-4 — ArchUnit fitness functions

- Не менять production код, кроме добавления `@ArchTest` и вспомогательной тестовой инфраструктуры.[^2]
- Не ослаблять существующие ArchUnit правила; только усиливать (no-Spring-in-core, no-OTel-in-core, forbidden dependencies).[^1][^2]
- Обязательно запускать все ArchUnit тесты (`TracingArchRules`, `OtelSdkArchRules`, resource-model ArchUnit) до merge.[^3][^1]


#### PR-5 — Duplicate critical tests before extraction

- Не начинать extraction policy в core, пока **все** критические tests не продублированы в целевых модулях.[^1]
- Не модифицировать существующие тесты так, чтобы они перестали защищать текущее поведение; duplication ≠ re-interpretation.[^1]
- Обязательно дублировать:
    - sampling: `CompositeSamplerTest`, `SamplerRuntimeUpdateConcurrencyTest` (в test harness, пригодный для core).[^1]
    - scrubbing: `ScrubbingSecurityNegativeTest`, `MergeEngineTest`, `ExceptionEventScrubbingE2ETest` (минимально).[^1]
    - validation: `ValidationPolicyRuntimeTest`.[^1]
- Не переносить JMH в core; benchmarks остаются в `platform-tracing-bench`.[^1]


#### PR-6 — Extract sampling policy to core

- Не удалять `CompositeSampler` из `otel-extension`; сначала ввести адаптер, затем постепенно делегировать на core.[^1]
- Не менять семантику rule chain (порядок, функции, fallback); любые изменения допускаются только после JMH и e2e подтверждения.[^1]
- Не добавлять OTel types в новый core policy слой; adapter в `otel-extension` отвечает за bridging.[^2][^1]
- Обязательно:
    - Запуск duplicated tests из core + старых tests в extension.[^1]
    - Запуск sampler JMH (минимум `CompositeSamplerBenchmark`, `CompositeSamplerPolicyBranchesBenchmark`).[^1]


#### PR-7 — Extract scrubbing rule engine to core

- Не делать scrubbing optional; baseline pipeline должен всегда включать scrubbing.[^2][^1]
- Не переносить Spring или OTel типов в core rule engine; scrubbing policy должна быть чистым Java-кодом.[^2][^1]
- Не изменять `ScrubbingSecurityNegativeTest` семантику (особенно ReDoS) без согласования с Security.[^2][^1]
- Обязательно:
    - Дублированные scrubbing tests должны зелёно проходить в core и extension.[^1]
    - Запустить `ScrubbingEngineBenchmark` до/после изменений.[^2][^1]


#### PR-8 — Extract validation/enrichment policy to core

- Не изменять `ValidationMode.STRICT`/`LENIENT` семантику.[^2][^1]
- Не переносить MDC/логические зависимости в core; enrichment policy остаётся независимой от конкретных logging libs.[^1]
- Не менять `CategoryContracts` публичный API.[^1]
- Обязательно:
    - Запускаать `ValidatingSpanProcessorTest`, `CategoryContractsTest`, `EnrichingSpanProcessorTest` и дублированные core tests.[^1]


#### PR-9 — Thin OTel adapters

- Не переносить политику обратно в `otel-extension`; PR-9 только упрощает adapters поверх core.[^2][^1]
- Не менять список SpanProcessor'ов/Exporter'ов без согласованного perf evidence.[^1]
- Обязательно:
    - Прогнать `CompositePipelineBenchmark`, `QueueOfferBenchmark`.[^1]
    - Убедиться, что `PlatformDropOldestExportSpanProcessor` и `SafeSpanExporter` ведут себя идентично baseline.[^1]


#### PR-10 — Desired State Config Reconciler

- Не выключать `RuntimeConfigApplier` до доказанной стабильности `TracingConfigReconciler` (default: reconciler disabled).[^2][^1]
- Не позволять reconciler применять topology поля; topology fields должны реджектиться или игнорироваться по контракту.[^2][^1]
- Не добавлять Actuator WRITE-path без dev-only guard (будет активирован в PR-11).[^1]
- Обязательно:
    - `TracingConfigReconcilerTest`, `TracingActuatorEndpointTest` (READ) зелёные.[^1]
    - `RuntimeConfigApplierTest` остаётся зелёным; double-apply исключён.[^2][^1]


#### PR-11 — Production READ-only Actuator + dev-only mutation

- Не оставлять `WriteOperation` доступной в production без явного флага `platform.tracing.actuator.mutation.enabled=true`.[^1]
- Не полагаться только на документацию; guard должен быть enforce'нут кодом (`@ConditionalOnProperty`, профили).[^2][^1]
- Обязательно:
    - E2E: prod-профиль → mutation endpoint возвращает 404/403 или отсутствует.[^1]
    - Dev-профиль → mutation endpoint работает и управляет JMX так же, как сегодня.[^1]


#### PR-12 — Tiered pipeline defaults + perf validation

- Не менять default pipeline (scrubbing/validation/enrichment) без perf evidence под E6 gate.[^2][^1]
- Не снижать PII baseline ради perf; выключение scrubbing возможно только для optional extra rules, не для baseline.[^2][^1]
- Обязательно:
    - Снова запустить M0/M5/M10 сценарии (macro perf) и все JMH для sampler/scrubbing/queue.[^2][^1]
    - Документировать любые изменения default pipeline.[^2]


#### PR-13 — Final cleanup + deferred module simplification review

- Не делать module collapse в этом PR; он только фиксирует docs/ADR/комментарии, удаляет deprecated флаги, но не меняет CL topology.[^2][^1]
- Не удалять fallback пути (старый wire, старый control) без отдельной ADR и согласования.[^2]
- Обязательно:
    - Обновить ADR/target-architecture документы для соответствия фактической реализации.[^2]
    - Оставить явные TODO/NOTE, если какие-либо временные флаги остаются включёнными.

***

## 12. Open implementation questions

### 12.1. Policy/topology classification

**Вопрос 1:** Как точно классифицировать `platform.tracing.sampling.*` поля между policy и topology?

- Почему важно: Неверная классификация может привести к runtime изменению topology (например, переключение sampler implementation), что нарушает ADR о policy vs topology.[^2][^1]
- Блокирует какие PR: PR-6 (sampling extraction), PR-10 (reconciler), PR-12 (tiered pipeline).
- Рекомендуемый владелец: Staff engineer (Platform Tracing), совместно с SRE.
- Предлагаемый default:
    - policy: ratio, route-ratios, killSwitch, qaTrace, forceHeaders;
    - topology: выбор sampler implementation (если вынесен в конфиг), queue-size/threads.

**Вопрос 2:** Какие поля `platform.tracing.scrubbing.*` остаются строго policy и никогда topology?

- Почему важно: Scrubbing — mandatory; возможность выключить core scrubbing через Config Server в runtime может стать compliance-риском.[^2][^1]
- Блокирует: PR-7, PR-10, PR-12.
- Владелец: Security + Platform Tracing.
- Default:
    - `scrubbing.enabled` — policy (но baseline всегда true, dev/debug-only может временно выключать);
    - `rules`, `mode` — policy.

**Вопрос 3:** Как классифицировать поля `validation`/`enriching` (особенно `enabled` flags)?

- Почему важно: Validation может быть optional tier, но некоторые проверки (mandatory attributes) завязаны на SLO.[^1]
- Блокирует: PR-8, PR-12.
- Владелец: Observability/семантика (semconv) owner.
- Default:
    - `validation.enabled` — policy;
    - `enriching.enabled` — policy;
    - topology — только конфиги, влияющие на pipeline structure.

**Вопрос 4:** Какие `exporter.*` поля являются topology vs policy?

- Почему важно: exporter endpoint/queue-size — явно topology; неправильно сделать их runtime-mutated.[^2][^1]
- Блокирует: PR-9/PR-12 (pipeline tuning), PR-10 (reconciler фильтры).
- Владелец: SRE + Platform.
- Default:
    - Endpoint, protocol, queue-size — topology;
    - soft toggles (например, enable metrics processor) — policy.

**Вопрос 5:** Граница между `queue.*` как topology vs policy (например, drop policy)?

- Почему важно: Drop policy (drop-oldest vs block) влияет на runtime safety; switching in prod может нарушить perf assumptions.[^1]
- Блокирует: PR-9/PR-12.
- Владелец: SRE.
- Default:
    - queue-size — topology;
    - drop-policy — policy, но изменение требует Perf review.

**Вопрос 6:** Классификация resource attributes (service.name/version/env, platform.cgroup/id) между policy/topology.

- Почему важно: Resource model ADR уже задаёт правила, но desired state layer должен их соблюдать; иначе drift и inconsistent tags.[^3][^2]
- Блокирует: PR-10 (reconciler), PR-0/PR-2/PR-3 resource-model PRs.
- Владелец: Resource Model owner.
- Default: resource attributes — topology (startup-only), кроме `policy-version` (policy).

**Вопрос 7:** Классификация propagators и span limits (max attributes/events) как topology или policy.

- Почему важно: Изменение этих параметров в runtime может ломать perf и downstream expectations.[^2][^1]
- Блокирует: PR-12.
- Владелец: Platform Tracing + Perf.
- Default: propagators и span limits — topology (только через redeploy).

***

### 12.2. JMX wire schema

**Вопрос 8:** Что делать с неизвестными ключами в Map wire: строгое отклонение или ignore-with-metric?

- Почему важно: Strict reject ломает forward compatibility; ignore-with-metric может скрыть ошибку.[^2][^1]
- Блокирует: PR-2, PR-3, PR-10.
- Владелец: Platform + SRE.
- Default: ignore-with-metric для policy keys, strict reject для topology keys.

**Вопрос 9:** Какой numeric тип использовать в Map wire: `Double` vs `Integer` vs `Long`?

- Почему важно: OpenMBean типизация и cross-CL serialization; ошибки приведут к `ClassCastException` на Agent CL.[^2][^1]
- Блокирует: PR-2, PR-3.
- Владелец: Platform + Java/Ops.
- Default: `Double` для ratio-полей; `Integer`/`Long` — только там, где сейчас уже primitive.

**Вопрос 10:** Стратегия String parsing: где разрешены string-представления чисел (например, `"0.5"` → 0.5)?

- Почему важно: Actuator может отправлять JSON с числами; но некоторые клиенты могут передавать string, что требует fallback.[^1]
- Блокирует: PR-3, PR-10.
- Владелец: Platform.
- Default: принимать числовые типы, optionally поддерживать string → number с metric об ошибке.

**Вопрос 11:** Когда использовать `CompositeData` fallback вместо Map?

- Почему важно: Если Map окажется слишком loose, CompositeData даёт строгую схему; но миграция добавляет сложность.[^2]
- Блокирует: PR-3 (spike decision).
- Владелец: Architecture committee.
- Default: начать с Map; планировать CompositeData как v2, если Map недостаточен.

**Вопрос 12:** Стратегия `contractVersion`: где хранить и как валидировать?

- Почему важно: Нужна возможность развивать wire schema без breaking изменений; version mismatch должен быть detectible.[^2]
- Блокирует: PR-2, PR-3, PR-10.
- Владелец: platform-tracing-api owner.
- Default: `contractVersion` хранится в Map; agent отвергает неизвестные major версии, допускает minor.

***

### 12.3. Actuator behavior

**Вопрос 13:** Какое поведение для disabled mutation в prod: 404 vs 403 vs 405?

- Почему важно: 404 скрывает наличие endpoint, 403/405 явнее; выбор влияет на ops runbooks и security posture.[^1]
- Блокирует: PR-11.
- Владелец: Security + SRE.
- Default: 404 (endpoint не зарегистрирован) в prod; 200-ответы только в dev/profilях.

**Вопрос 14:** Mutation endpoint должен быть **не зарегистрирован** в prod или зарегистрирован, но запрещён?

- Почему важно: Не зарегистрированный endpoint уменьшает поверхностную площадь атак, но усложняет observability; registered-but-forbidden даёт лучший DX для ops.[^1][^2]
- Блокирует: PR-11.
- Владелец: Security.
- Default: не регистрировать WriteOperation в prod (условие на bean).

**Вопрос 15:** Какой уровень security для READ endpoint (`GET /actuator/tracing`)?

- Почему важно: READ может содержать чувствительную информацию (policyVersion, внутренние параметры).[^1]
- Блокирует: PR-10/PR-11.
- Владелец: Security + SRE.
- Default: READ endpoint доступен только на management port и защищён стандартным Spring security (role-based).

**Вопрос 16:** Какой management port/экспозиция для tracing Actuator?

- Почему важно: Один и тот же порт для всех actuator endpoints или отдельный? Это влияет на firewall и ops tooling.[^2]
- Блокирует: PR-11.
- Владелец: SRE.
- Default: использовать existing management port; не вводить новый порт, но требовать restricted exposure (internal only).

***

### 12.4. Config Server / desired state

**Вопрос 17:** Нужен ли debounce для Config Server refresh events (объединение burst-изменений)?

- Почему важно: Без debounce reconciler может вызвать множество JMX updates подряд, перегрузив control plane.[^2][^1]
- Блокирует: PR-10, PR-12.
- Владелец: Platform + SRE.
- Default: минимальный debounce (например, 1–5 секунд) с configurable параметром.

**Вопрос 18:** Как вести себя, если Config Server недоступен (fallback behavior)?

- Почему важно: Без чёткой стратегии можно потерять ability to update policy или сломать startup.[^2]
- Блокирует: PR-10.
- Владелец: SRE.
- Default: использовать last-known-good config; не падать при недоступности Config Server; drift metric сигнализирует проблему.

**Вопрос 19:** Что делать при невалидном refresh (invalid policy)?

- Почему важно: Важно сохранить LKG и предотвратить partial apply; также нужно продумать observability (metrics/logs).[^1][^2]
- Блокирует: PR-10.
- Владелец: Platform + Security.
- Default: reject apply, сохранить LKG, поднять metric и log; никакой partial apply.

**Вопрос 20:** Как вести себя, если agent отсутствует (например, локальная dev-среда без javaagent)?

- Почему важно: В dev возможно Config Server + actuator, но без агентного control plane; нужна деградация без ошибок.[^1][^2]
- Блокирует: PR-10/PR-11.
- Владелец: Platform.
- Default: reconciler возвращает `agentUnavailable=true`, записывает degraded status, но не бросает exceptions в приложении.

**Вопрос 21:** Emergency/debug override TTL — сколько времени dev override может жить?

- Почему важно: Без TTL временный override может остаться навсегда, нарушая Config Server authority.[^2]
- Блокирует: PR-11/PR-12.
- Владелец: SRE.
- Default: emergency override имеет configurable TTL (например, 1–4 часа) и auto-expire.

**Вопрос 22:** Приоритет между Config Server, Helm defaults и emergency override?

- Почему важно: Нужен строгий порядок (`Helm/env` → default → `Config Server` → emergency override), иначе drift и несогласованность.[^1][^2]
- Блокирует: PR-10/PR-11.
- Владелец: Architecture committee.
- Default: Helm/env bootstrap < Config Server < emergency override (с TTL).

***

### 12.5. Performance baseline

**Вопрос 23:** Какой hardware profile считать reference для JMH baseline (CPU/cores, RAM, OS)?

- Почему важно: Разные машины дают разные baseline; без стандарта сравнение невозможно.[^2][^1]
- Блокирует: PR-0, PR-6, PR-12.
- Владелец: Perf team.
- Default: использовать уже применяемый Gentoo perf lab профиль (описан в perf docs) как baseline.[^2]

**Вопрос 24:** Какой acceptable noise threshold для JMH (±X%)?

- Почему важно: Без порога любой шум выглядит как regression; слишком высокий порог маскирует реальные деградации.[^1][^2]
- Блокирует: PR-6/PR-7/PR-8/PR-12.
- Владелец: Perf team.
- Default: ±5–10% в зависимости от теста, с отдельным порогом для hot path benchmarks.

**Вопрос 25:** Каковы точные M0/M5 environment параметры (load, data volume, traffic mix)?

- Почему важно: E6 perf gate опирается на эти сценарии; их нужно фиксировать как контракт.[^1][^2]
- Блокирует: PR-0, PR-12.
- Владелец: Perf + SRE.
- Default: использовать существующие M0/M5 сценарии из `platform-tracing-perf-tests`, фиксируя их как "недотрогаемые" до PR-12.[^1]

**Вопрос 26:** Каков E6 CPU/RSS budget и остаётся ли он прежним (3 CPU, 10 RSS)?

- Почему важно: План миграции основывается на этом бюджете; без подтверждения нельзя принимать perf-related решения.[^2]
- Блокирует: PR-12.
- Владелец: SRE.
- Default: принять текущий бюджет как обязательный gate, пока не будет изменён комитетом.

**Вопрос 27:** Где и как хранятся baseline artifacts (лог JMH, perf результаты)?

- Почему важно: Необходим reproducible evidence; локальные машины разработчиков не подходят.[^2][^1]
- Блокирует: PR-0, PR-6, PR-12.
- Владелец: Platform + SRE.
- Default: централизованное хранилище (например, артефакты CI, отдельный S3/Arifactory bucket).

***

### 12.6. Public API compatibility

**Вопрос 28:** Какой уровень совместимости для `platform-tracing-api` с текущим `compileOnly OTel contextapi`?

- Почему важно: Переход к JDK-only API может быть breaking для downstream consumers.[^1][^2]
- Блокирует: PR-2, любые изменения API.
- Владелец: Platform + Developer Experience.
- Default: сохранить `compileOnly OTel` как есть в первой волне; breaking изменения — только после отдельного ADR.

**Вопрос 29:** Должен ли `platform-tracing-core` продолжать содержать facade implementation в первой волне?

- Почему важно: Полное разделение facade и core может быть слишком агрессивным для первой волны.[^2][^1]
- Блокирует: PR-6–PR-9.
- Владелец: Architecture committee.
- Default: facade остаётся в core в первой волне, с постепенным выделением чистого policy.

**Вопрос 30:** Где должна жить будущая имплементация facade (core vs отдельный adapter module) в долгосрочной перспективе?

- Почему важно: Влияет на public/internal разделение модулей и DX.[^1][^2]
- Блокирует: PR-13 (cleanup review).
- Владелец: Architecture committee.
- Default: core как policy + facade adapter, но без изменений в первой волне.

**Вопрос 31:** Какова binary compatibility policy: semantic versioning, backward compatibility, депрекейшн?

- Почему важно: Нельзя ломать binary API без согласования; нужен официальный policy.[^2][^1]
- Блокирует: PR-2, PR-9, PR-13.
- Владелец: Platform + DX.
- Default: соблюдение backward binary compatibility для `platform-tracing-api` (additive-only) в первой волне.

***

## 13. Final recommendation

### 13.1. Можно ли начинать миграцию?

Да, миграцию можно начинать **при выполнении условий**:

- PR-0 baseline lock выполнен: JMH, perf, e2e, ArchUnit, resource-model tests задокументированы и зафиксированы.[^1][^2]
- Архитектура Clean Core Hybrid принята в статус `Accepted` (что уже отражено в ADR).[^2]
- Основные открытые вопросы (минимальный набор из раздела 12 — policy/topology, JMX unknown key policy, Actuator prod guard) имеют согласованные defaults.


### 13.2. Первый implementation PR

Рекомендуемый первый PR:

> **PR-0 — Baseline lock: behavior, performance, dependency snapshot**

Почему именно PR-0:

- Без baseline нельзя доказать, что последующие extraction/optimization PR не изменили поведение или perf (E6 gate).[^1][^2]
- PR-0 не меняет production поведение; минимальный риск, можно выполнить сразу.[^2][^1]
- PR-0 фиксирует dependency graph, resource model и Control Plane baseline, что упрощает дальнейший review.[^1][^2]


### 13.3. PRs, которые можно выполнять параллельно (консервативно)

При строгом соблюдении guardrails возможны следующие ограниченно параллельные PR:

- PR-0 и PR-1: baseline lock и taxonomy/ArchUnit guardrails могут идти почти параллельно, если PR-1 не меняет deps/behavior, а только добавляет проверки.[^2][^1]
- PR-2 (wire schema v1 в `api`) и PR-4 (ArchUnit fitness functions): оба добавляют схемы и тесты, не меняя runtime; допускается параллельная работа при строгом CI gate.[^2]

Любая параллельность должна быть синхронизирована через rebase/merge — никакого "склеивания" PR.

### 13.4. PRs, которые **нельзя** выполнять параллельно

Эти цепочки должны быть строго последовательны:

- **PR-5 → PR-6/PR-7/PR-8**
    - PR-5 (duplicate critical tests) должен завершиться до начала extraction (sampling/scrubbing/validation), иначе потеря тестового покрытия.[^1][^2]
- **PR-6/PR-7 → PR-9**
    - PR-9 (thin OTel adapters) зависит от того, что политика уже вынесена в core; нельзя упрощать adapters до завершения extraction.[^1][^2]
- **PR-10 → полный desired-state rollout**
    - `TracingConfigReconciler` должен быть внедрён и проверен до включения по умолчанию; rollout (включение по умолчанию) — отдельный шаг, завязанный на PR-12.[^2][^1]
- **PR-11 → production rollout**
    - Продакшн rollout невозможен до того, как Actuator MUTATION будет защищён dev-only guard (PR-11).[^1][^2]
- **PR-12 → после extraction и baseline**
    - Tiered pipeline defaults и perf validation возможны только после завершения extraction (PR-6/7/8/9) и наличия стабильного baseline из PR-0.[^2][^1]


### 13.5. Review ownership

Высокоуровневая разбивка:

- **Staff/principal review (архитектура/платформа)**
    - PR-1 (taxonomy/guardrails), PR-2 (wire schema), PR-3 (JMX spike), PR-4 (ArchUnit), PR-6/7/8 (extractions), PR-10 (reconciler), PR-13 (cleanup).[^2]
- **SRE review**
    - PR-0 (baseline), PR-3 (JMX wire), PR-9 (export pipeline adapters), PR-10 (desired state), PR-11 (Actuator guards), PR-12 (perf gates).[^1][^2]
- **Security review**
    - PR-5/7 (scrubbing and security tests duplication), PR-11 (Actuator MUTATION policies), любые изменения scrubbing/PII behavior.[^1][^2]
- **Perf review**
    - PR-0, PR-6/7/8 (sampling/scrubbing/validation perf), PR-9 (pipeline perf), PR-12 (tiered defaults + E6 gate).[^2][^1]
- **Developer experience (DX) review**
    - PR-1 (dependency graph, starters), PR-2/PR-9 (public API), PR-11 (operator workflows через Actuator), PR-13 (финальные docs/ADR).[^1][^2]

***

## 14. Adversarial self-review

### Issue 1: PR-6/7/8 слишком крупные (extraction)

- **Issue:** PR-6 (sampling), PR-7 (scrubbing), PR-8 (validation/enrichment) охватывают большие подсистемы и могут быть слишком объёмными.[^2][^1]
- **Почему важно:** Большие PR сложно ревьюить; возрастает риск пропустить изменение поведения.[^1]
- **Affected PR:** PR-6, PR-7, PR-8.
- **Correction:** Разбить каждый на минимум два PR:
    - sampling: core policy extraction vs adapter wiring;
    - scrubbing: rule model vs loader/engine;
    - validation/enrichment: policy vs adapter.


### Issue 2: Недостаточно ранняя дубликация tests

- **Issue:** В планах PR-5 может недооцениваться объём тестов, которые надо продублировать до extraction (особенно e2e).[^1]
- **Почему важно:** Без duplication e2e тестов для новых core policy слоёв сложно заметить регрессии.[^1]
- **Affected PR:** PR-5, PR-6/7/8.
- **Correction:** Добавить отдельный PR `PR-5b` для e2e duplication (например, core-based harness в `platform-tracing-test`).


### Issue 3: Benchmarks запускаются слишком поздно

- **Issue:** План может подразумевать запуск JMH только на PR-6/7/8/12; но любое изменение hot path должно сопровождаться JMH ранее.[^2][^1]
- **Почему важно:** Hot path изменения без JMH могут ухудшить уже failing M5.[^2]
- **Affected PR:** PR-6, PR-7, PR-8, PR-9.
- **Correction:** Требовать запуск sampler/scrubbing JMH уже в PR-5 (duplication), а не только в extraction PR.


### Issue 4: WebMVC/WebFlux isolation риски недооценены

- **Issue:** Стек-изоляция полагается только на существующие tests; нет явных новых guardrails для изменений, связанных с core/adapters.[^1]
- **Почему важно:** Любые изменения в autoconfigure могут привести к accidental cross-stack wiring.[^1]
- **Affected PR:** PR-1, PR-9, PR-12.
- **Correction:** Добавить отдельный PR для WebStack isolation ArchUnit/CI усиления до любых adapter изменений.


### Issue 5: Starter DX риски

- **Issue:** PR-1/PR-9 могут неявно изменить transitive dependency граф стартеров.[^1]
- **Почему важно:** Starter consumers зависят от стабильной BOM/starter; ломка DX приведёт к массовым проблемам.[^1]
- **Affected PR:** PR-1, PR-9, PR-13.
- **Correction:** Ввести отдельные smoke-проекты (sample apps) для обоих стартеров и запускать их в CI.


### Issue 6: OTel/Spring leakage в core

- **Issue:** Архитектура полагается на ArchUnit, но конкретные extraction PR могут временно добавить OTel/Spring imports в core.[^2][^1]
- **Почему важно:** Нарушение Clean Core делает архитектуру менее устойчивой и усложняет тестирование.[^2]
- **Affected PR:** PR-6, PR-7, PR-8.
- **Correction:** Разбить extraction на два PR: один — перенос логики, второй — детач OTel/Spring; добавить более строгие ArchUnit rules до extraction.


### Issue 7: Actuator MUTATION prod exposure

- **Issue:** PR-10 может добавить новые пути к Actuator до того, как PR-11 введёт guard, что создаёт временное окно.[^1]
- **Почему важно:** В это окно mutation endpoint может быть доступен в prod.[^2][^1]
- **Affected PR:** PR-10, PR-11.
- **Correction:** Перенести минимальный guard (например, require dev profile) прямо в PR-10 и усилить в PR-11.


### Issue 8: JMX runtime update behavior риски

- **Issue:** PR-3 (Map wire spike) может слишком сильно изменять JMX behavior без достаточного fallback.[^2][^1]
- **Почему важно:** Control plane — критическая часть; нельзя рисковать runtime tuning.[^1]
- **Affected PR:** PR-3.
- **Correction:** Разделить PR-3 на schema-only spike и behavior change PR; оставить feature flag выключенным до E2E evidence.


### Issue 9: Config Server / RuntimeConfigApplier migration risks

- **Issue:** PR-10 может усложнить path `Config Server → RefreshScope → RuntimeConfigApplier` без достаточной изоляции от reconciler.[^2][^1]
- **Почему важно:** Двойные apply/неполные apply возможны.[^1]
- **Affected PR:** PR-10.
- **Correction:** Разделить PR-10 на `PR-10a` (types + skeleton) и `PR-10b` (hooking into events), с отдельным focus на double-apply prevention.


### Issue 10: План слишком оптимистичен в части параллелизма

- **Issue:** Допустимый параллелизм (PR-0/1, PR-2/4) может быть слишком оптимистичен для реального CI/merge потока.[^2]
- **Почему важно:** Merge конфликт и order-dependent эффекты повышают риск.[^2]
- **Affected PR:** PR-0, PR-1, PR-2, PR-4.
- **Correction:** Ограничить параллельность: выполнять PR-1 только после PR-0, PR-4 — только после PR-2.

***

## 15. Final corrected PR sequence

> **Note:** Это adversarial-corrected sequence. Baseline PR-0 … PR-13 без split — в разделе 3. При конфликте нумерации PR-4/PR-12 между разделами 3 и 15 — следовать разделу 15 для implementation, раздел 3 — для traceability к исходному Perplexity output.

С учётом adversarial self-review предлагается следующая скорректированная последовательность (с разбиением крупных PR):

1. **PR-0 — Baseline lock**
Зафиксировать текущие behavior/perf/dependency baseline; собрать JMH и perf артефакты, обновить ADR/документы.[^2][^1]
2. **PR-1 — Module taxonomy + dependency guardrails**
Зафиксировать dependency направления, добавить ArchUnit тесты для CL и модульных границ (включая starter rules).[^1][^2]
3. **PR-2 — Wire schema v1 in platform-tracing-api**
Добавить Map/OpenMBean wire schema и constants в `platform-tracing-api` без изменения поведения.[^2]
4. **PR-3 — ArchUnit fitness functions**
Усилить ArchUnit (no-OTel-in-core, no-Spring-in-core, forbidden deps) до extraction; CI gate.[^1][^2]
5. **PR-4 — JMX wire spike (schema-only)**
Добавить `updatePolicy(Map)` сигнатуру и tests без включения нового пути по умолчанию; feature flag выключен.[^2][^1]
6. **PR-5 — Duplicate critical tests (unit)**
Дублировать ключевые unit tests (sampling, scrubbing, validation) в core-friendly harness, не меняя behavior.[^1]
7. **PR-5b — Duplicate critical tests (e2e)**
Добавить e2e/core-based harness для sampling/scrubbing/validation, не меняя существующие e2e tests.[^2][^1]
8. **PR-6a — Extract sampling policy to core (logic)**
Вынести pure sampling policy в core, оставить adapters в `otel-extension`; сохранить behavior.[^1]
9. **PR-6b — Adapter wiring and perf verification**
Перевести `CompositeSampler` в adapter поверх core; прогнать JMH и e2e; убедиться в отсутствии behavior-regression.[^1]
10. **PR-7a — Extract scrubbing rule engine to core (logic)**
Вынести scrubbing engine/правила в core, сохранив mandatory nature.[^2][^1]
11. **PR-7b — Scrubbing adapter wiring + perf**
Перевести `ScrubbingSpanProcessor` на core; запустить `ScrubbingEngineBenchmark`, e2e security tests.[^1]
12. **PR-8a — Extract validation/enrichment policy to core (logic)**
Вынести validation/enrichment policy в core (без OTel/Spring), сохранить public API.[^1]
13. **PR-8b — Validation/enrichment adapters + perf**
Перевести соответствующие processors; прогнать tests и perf.[^1]
14. **PR-9 — Thin OTel adapters**
Упростить `otel-extension` до тонкого adapters поверх core policy; не менять pipeline topology.[^2][^1]
15. **PR-10a — Desired State types + skeleton (reconciler disabled)**
Ввести `TracingDesiredState`, `TracingConfigReconciler` skeleton с disabled по умолчанию; добавить tests.[^2][^1]
16. **PR-10b — Reconciler integration (read-only diagnostics)**
Подключить reconciler к RefreshScope/Config Server, использовать только для diagnostics; `RuntimeConfigApplier` остаётся активным.[^2][^1]
17. **PR-11 — Actuator mutation guards**
Ввести prod/dev guard для Actuator mutation (write endpoint disabled в prod, dev-only), плюс E2E tests.[^2][^1]
18. **PR-12 — JMX Map wire enablement**
Включить Map-based JMX wire для всех операций под контролем feature flag; сохранить backwards compatibility.[^1][^2]
19. **PR-13 — Tiered pipeline defaults + perf validation**
Настроить tiered defaults (optional validation/enrichment), подтвердить E6 perf; не трогать scrubbing baseline.[^2][^1]
20. **PR-14 — Final cleanup + documentation**
Синхронизировать ADR, удалить deprecated пути/флаги (где безопасно), обновить module docs; без module collapse.[^1][^2]
---

## Next executable step

**PR-0 — Baseline lock: behavior, performance, dependency snapshot.**

- No production code changes.
- Run all 213 unit/integration tests green.
- Run all 16 JMH benchmarks; `./gradlew :platform-tracing-bench:jmhSaveBaseline`.
- Run `PerformanceReleaseGateTest`.
- Run `RuntimeSamplingControlSmokeTest` (e2e, `-PrunE2e`).
- Capture M0/M5 macro perf results to `docs/tracing/perf-results/`.
- Snapshot Gradle dependency graph for starters (servlet + reactive).

---

*Merged from: `platform-tracing-current-codebase-inventory.md`, `Migration Plan/Migration Plan.md`, `migration-plan-pass-1-tests-benchmarks.md`, `migration-plan-pass-2-reconciler-jmx-risks.md`, `migration-plan-pass-3-guardrails-final-review.md`. No production code changed during document assembly.*