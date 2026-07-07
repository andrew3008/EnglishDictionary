<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# You are acting as a senior ML Engineer, principal Java platform engineer, and migration planning specialist.

Your task is to analyze the attached Platform Tracing documents and produce a conservative, evidence-driven, preservation-first migration plan.
This is not an architecture review.
The target architecture is already approved by architects.
Your job is to help migrate the existing codebase to the approved architecture without losing valuable existing implementation, tests, benchmarks, performance evidence, and developer-facing module ergonomics.
Attached documents
Primary source of truth:
platform-tracing-current-codebase-inventory.md
Supporting architecture documents:
ADR-platform-tracing-clean-core-hybrid.md
platform-tracing-target-architecture.md
platform-tracing-pr-roadmap.md
platform-tracing-fitness-functions.md
platform-tracing-evidence-before-committee.md
Operating mode
Think like a professional ML Engineer working on a high-value production platform migration.
Treat the current codebase inventory as the factual dataset.
Do not hallucinate classes, modules, tests, benchmarks, or production behavior.
If information is missing, write:
Requires manual review

Do not invent implementation details.
Do not propose a new architecture.
Do not reopen architecture decisions.
Do not recommend a rewrite from scratch.
Do not recommend collapsing modules in the first migration wave.
Do not delete or devalue existing tests, benchmarks, E2E tests, perf tests, or operational smoke tests.
The correct migration philosophy is:
Preserve existing assets first, refactor second.
Tests before moves.
Benchmarks before optimization.
Small PRs before structural extraction.

Approved target architecture
The approved target is:
Clean Core Hybrid Architecture;
Desired State Configuration Layer;
Production Read-only Actuator;
Dev-only Actuator Mutation;
platform-tracing-api — public contracts, semconv, propagation, wire schema;
platform-tracing-core — narrow pure-Java policy engine without OTel/Spring dependencies;
platform-tracing-otel-extension — thin OTel SPI adapter + private JMX;
platform-tracing-spring-boot-autoconfigure — common Spring adapter, TracingConfigReconciler, Actuator READ, JMX client;
platform-tracing-autoconfigure-webmvc — WebMVC-specific auto-configuration;
platform-tracing-autoconfigure-webflux — WebFlux-specific auto-configuration;
platform-tracing-spring-boot-starter-servlet — public Spring MVC / Servlet starter;
platform-tracing-spring-boot-starter-reactive — public WebFlux / Reactive starter;
Config Server — runtime desired policy authority;
Helm/env/system properties — startup topology and bootstrap defaults;
Agent runtime state — applied state, not source of truth;
JMX — private in-process transport with validated Map/OpenMBean-compatible wire format;
baseline pipeline — sampling + mandatory scrubbing + export;
optional tier — validation, enrichment, diagnostics.
Current codebase facts to preserve
Use the inventory document as the factual source of truth.
Important known facts:
14 active Gradle modules;
279 production Java classes;
213 test Java classes;
16 JMH benchmark classes;
platform-tracing-otel-extension currently contains highly valuable production runtime logic;
CompositeSampler, sampling rules and SamplerStateHolder are preservation-critical;
ScrubbingSpanProcessor and the scrubbing rule engine are preservation-critical;
PlatformDropOldestExportSpanProcessor and SafeSpanExporter are preservation-critical;
TracingProperties is preservation-critical;
current JMX control path must be preserved until validated Map/OpenMBean wire migration is proven;
current RefreshScope / RuntimeConfigApplier behavior must be preserved until TracingConfigReconciler is proven equivalent;
WebMVC/WebFlux stack isolation must be preserved;
servlet and reactive starters must remain the developer-facing entry points;
E2E tests, JMH benchmarks, macro perf scenarios M0–M10 and M5 baseline evidence must be preserved.
Non-negotiable constraints
Do not break the developer-facing dependency model:
Application teams use BOM + exactly one starter.
Spring MVC services use platform-tracing-spring-boot-starter-servlet.
WebFlux services use platform-tracing-spring-boot-starter-reactive.
Application teams must not directly depend on internal autoconfigure/core/otel-extension modules.

Do not introduce OTel dependencies into the pure policy core.
Do not introduce Spring dependencies into the pure policy core.
Do not introduce direct spring-boot-autoconfigure -> otel-extension main dependency.
Do not introduce otel-extension -> Spring dependency.
Do not pass raw Java DTOs across the Application ClassLoader / Agent ClassLoader boundary.
Do not expose Actuator MUTATION in production.
Do not make scrubbing optional.
Do not change hot-path behavior without test and benchmark protection.
Do not change benchmark names, benchmark parameters, or macro perf scenario semantics before PR-0 baseline capture.
Do not collapse modules during the first migration wave.
Main task
Produce a PR-by-PR migration plan from the current codebase to the approved Clean Core Hybrid architecture.
The plan must preserve:
existing production behavior;
public API / semconv contracts;
sampling semantics;
scrubbing behavior;
validation/enrichment behavior;
export safety behavior;
Spring property binding;
WebMVC behavior;
WebFlux behavior;
starter developer experience;
JMX runtime update behavior;
Config Server / RefreshScope behavior until reconciler is proven;
tests;
benchmarks;
E2E smoke tests;
macro perf evidence.
Required output
Write the answer in Russian.
Keep module names, class names, package names, method names and technical terms in English.
Use precise engineering language.
Do not use marketing language.
Do not produce generic advice.
Every meaningful PR must reference concrete modules/classes/tests/benchmarks from the inventory.
Platform Tracing Preservation-First Migration Plan

1. Executive summary
Provide a concise executive summary.
It must explain:
why this migration must be preservation-first;
why no big-bang rewrite is acceptable;
why module collapse must be deferred;
why PR-0 must capture behavior/performance baselines;
why critical tests must be duplicated or strengthened before extraction;
why platform-tracing-core cannot become pure until OTel-coupled current behavior is safely split;
why TracingConfigReconciler should evolve from existing Spring config/refresh assets, not replace them blindly.
2. Migration principles
List 10–15 migration principles.
Mandatory principles:
Preserve existing assets first, refactor second.
No big-bang rewrite.
No first-wave module collapse.
Tests before code moves.
Benchmarks before optimization.
Preserve starter developer experience.
Preserve WebMVC/WebFlux isolation.
Preserve current JMX behavior until validated wire migration is proven.
Preserve RefreshScope / RuntimeConfigApplier behavior until reconciler is proven.
Keep scrubbing mandatory.
Keep Actuator MUTATION disabled in production.
Keep OTel/Spring out of pure core.
Treat Agent state as applied state, not source of truth.
3. Recommended PR sequence
Create a concrete PR-by-PR migration plan.
Use small, independently reviewable PRs.
Use this baseline, but refine it using the inventory:
PR-0 — Preserve current behavior and performance baseline
PR-1 — Module taxonomy, developer-facing module guide, dependency guardrails
PR-2 — Wire schema v1 / validated Map contract in platform-tracing-api
PR-3 — Cross-CL JMX wire migration spike
PR-4 — ArchUnit fitness functions and dependency enforcement
PR-5 — Duplicate critical tests before extraction
PR-6 — Extract sampling policy to pure core
PR-7 — Extract scrubbing rule engine to pure core
PR-8 — Extract validation/enrichment policy to pure core
PR-9 — Thin OTel adapters refactor
PR-10 — Desired State Config Reconciler
PR-11 — Production READ-only Actuator and dev-only mutation guardrails
PR-12 — Tiered pipeline defaults and perf validation
PR-13 — Final cleanup and deferred module simplification review
For every PR include:
PR title:
Goal:
Why this PR exists:
Modules touched:
Concrete classes/packages touched:
Existing tests to preserve:
Tests to duplicate before moving code:
Tests to add or adapt:
Benchmarks to run:
E2E/perf scenarios to run:
Behavior change: yes/no
Telemetry change: yes/no
Risk level: low/medium/high/critical
Rollback strategy:
Acceptance criteria:
Must not be done in this PR:
Should this PR be split further: yes/no
4. Critical class/package migration table
Create a table:
| Current class/package | Current module | Current role | Target module | Migration action | Tests required before move | Benchmarks required | Risk | Recommended PR |

Use only classes/packages present in the inventory.
Use migration actions:
KEEP_AS_IS
MOVE_TO_CORE
SPLIT_CORE_AND_ADAPTER
KEEP_IN_OTEL_EXTENSION_ADAPTER
KEEP_IN_SPRING_AUTOCONFIGURE
KEEP_IN_WEBMVC_AUTOCONFIGURE
KEEP_IN_WEBFLUX_AUTOCONFIGURE
KEEP_IN_STARTER
KEEP_IN_TEST_SUPPORT
KEEP_IN_BENCH
KEEP_IN_PERF_TESTS
DEFER
TEST_BEFORE_MOVE
REVIEW_REQUIRED

Mandatory components to cover:
CompositeSampler
sampling rule classes
SamplerStateHolder
DomainConfigHolder
ScrubbingSpanProcessor
scrubbing rule engine
ValidatingSpanProcessor
EnrichingSpanProcessor
PlatformCompositeSpanProcessor
PlatformDropOldestExportSpanProcessor
SafeSpanExporter
PlatformTracingControl
PlatformTracingControlMBean
SamplingControlClient
TracingActuatorEndpoint
TracingProperties
DualChannelDriftDiagnostics
RuntimeConfigApplier
TracingRefreshScopeAutoConfiguration
PlatformAutoConfigurationCustomizer
PlatformSamplerFactory
PlatformSpanProcessorFactory
DefaultPlatformTracing
AttributePolicy
WebMVC autoconfigure classes
WebFlux autoconfigure classes
starter modules
benchmark modules
perf test modules
If a class is missing or ambiguous, write:
Requires manual review

5. Module migration strategy
For every current module, explain the strategy:
platform-tracing-api
platform-tracing-autoconfigure-webflux
platform-tracing-autoconfigure-webmvc
platform-tracing-bench
platform-tracing-bom
platform-tracing-core
platform-tracing-e2e-tests
platform-tracing-otel-extension
platform-tracing-perf-tests
platform-tracing-spring-boot-autoconfigure
platform-tracing-spring-boot-starter-reactive
platform-tracing-spring-boot-starter-servlet
platform-tracing-test
For each module specify:
Current role:
Target role:
Public/internal/verification:
Keep module: yes/no
Hide behind starter: yes/no
Collapse now: no by default
Migration risk:
Required guardrails:
Recommended PRs:

Do not recommend module collapse in the first migration wave.
6. Test preservation plan
Group existing tests by domain.
For each group specify:
Existing tests to keep:
Tests to duplicate before moving behavior:
Tests to adapt after split:
New tests required:
PR where this must happen:
Risk if skipped:

Groups:
sampling tests;
scrubbing tests;
validation/enrichment tests;
export safety tests;
JMX/control-plane tests;
Spring property binding tests;
Config refresh / RuntimeConfigApplier tests;
WebMVC tests;
WebFlux tests;
starter smoke tests;
E2E tests;
ArchUnit tests;
JMH benchmarks;
macro perf tests.
Mandatory rule:
No critical behavior moves before its tests exist in the target layer or are duplicated in a safe temporary form.

7. Benchmark and performance preservation plan
Explain how to preserve benchmark comparability.
Cover:
existing 16 JMH benchmarks;
platform-tracing-bench;
platform-tracing-perf-tests;
M0–M10 macro scenarios;
M5 documented FAIL baseline;
PR-0 baseline capture;
PR-12 / E6 validation after tiered pipeline.
Specify:
Benchmarks that must run before extraction:
Benchmarks that must run after extraction:
Benchmarks that must not be renamed before baseline capture:
Macro perf scenarios that gate rollout:
How to compare old vs new behavior:
8. Desired State Configuration Layer migration strategy
Explain how to evolve the current codebase toward:
Config Server -> Spring Environment -> TracingConfigReconciler -> SamplingControlClient -> private JMX -> Agent applied state

Use current assets from inventory:
TracingProperties
TracingRefreshScopeAutoConfiguration
RuntimeConfigApplier
DualChannelDriftDiagnostics
SamplingControlClient
TracingActuatorEndpoint
Explain:
what can be reused;
what should be adapted;
what should be added;
what must not be broken;
how to preserve current refresh behavior;
how to make Actuator READ production-safe;
how to disable Actuator MUTATION in prod;
how to avoid Config Server fighting emergency/debug overrides.
9. ClassLoader and JMX migration strategy
Explain how to migrate the current JMX control path to the target validated Map/OpenMBean-compatible wire contract.
Cover:
what current design already does well;
what needs to change;
how to preserve invoke-by-name decoupling;
how to avoid raw Java DTO crossing;
what tests are required before and after migration;
what fallback should exist if Map proves too loose;
how to preserve current runtime sampling update behavior.
10. High-risk migration areas
Rank the top risks.
Mandatory risks:
loss of sampling semantics;
loss of PII scrubbing behavior;
loss of mandatory span attributes;
breakage of export safety;
breakage of Spring property binding;
breakage of WebMVC behavior;
breakage of WebFlux behavior;
breakage of starter developer experience;
breakage of JMX runtime updates;
breakage of RefreshScope / RuntimeConfigApplier behavior;
accidental production exposure of Actuator MUTATION;
OTel types leaking into pure core;
Spring types leaking into pure core;
benchmark comparability loss;
premature module collapse.
For each risk include:
Why risky:
Affected modules:
Affected classes:
Existing tests/benchmarks protecting it:
Missing protection:
Recommended PR:
Mitigation:

11. Implementation guardrails for Cursor Composer
Produce a short list of implementation rules that should be used later when giving PR-scoped prompts to Cursor Composer.
Include rules such as:
implement only one PR at a time;
do not delete tests;
do not collapse modules;
no production behavior change unless the PR explicitly allows it;
preserve public starters;
preserve public API unless explicitly approved;
do not add OTel/Spring dependencies to pure core;
do not introduce autoconfigure -> otel-extension main dependency;
do not expose Actuator MUTATION in prod;
run listed tests/benchmarks before and after changes.
12. Open implementation questions
List only questions that materially affect implementation.
Do not reopen architecture.
Questions may include:
exact policy/topology field classification;
exact Map wire schema strictness;
exact prod behavior for disabled Actuator MUTATION: 404 vs 403;
Config Server refresh debounce policy;
Config Server unavailable behavior;
Agent unavailable behavior;
benchmark baseline environment;
compatibility policy for existing public API with compileOnly OTel in platform-tracing-api;
whether platform-tracing-core should keep facade implementation or be split into policy core plus facade adapter.
13. Final recommendation
Provide a final recommendation:
whether migration can start;
which PR should be first;
which PRs can run in parallel;
which PRs must not run in parallel;
which PRs need staff/principal review;
which PRs require SRE review;
which PRs require security review.
Quality bar
Your answer must be concrete.
Every PR should reference real modules/classes/tests/benchmarks from the inventory.
Avoid generic statements.
Avoid optimistic unsupported claims.
Be conservative.
Prefer smaller PRs.
Prefer behavior preservation.
Prefer measurable acceptance criteria.
When uncertain, say:
Requires manual review
Результаты анализа и содержимое выходных документов напиши на русском

Документ инвентаризации полностью прочитан. Всей информации достаточно для составления плана. Перехожу к написанию детального ответа.

***

# Platform Tracing — Preservation-First Migration Plan


***

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

## 3. PR-by-PR план миграции


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

**Should this PR be split further:** нет

***

### PR-2 — Wire schema v1: validated Map contract в `platform-tracing-api`

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
    - `ScrubbingEngineCharacterizationTest` — тестирует scrubbing rule engine через `SensitiveDataRule` SPI
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

**Почему этот PR существует:** Scrubbing engine — policy logic, не зависящая от OTel SpanProcessor callback. `SensitiveDataRule` SPI уже в `platform-tracing-api`. Engine может тестироваться pure Java.

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
- `MergeEngineTest`, `RuleCircuitBreakerTest`, `ExtensionRuleLoaderTest`, `ServiceLoaderSensitiveDataRuleTest` — MOVE копии в core, оригиналы остаются

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
| `scrubbing.loader.*` | otel-extension | YAML/ServiceLoader rule loading | platform-tracing-core | MOVE_TO_CORE | `ExtensionRuleLoaderTest`, `ServiceLoaderSensitiveDataRuleTest` | нет | HIGH | PR-7 |
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
| `SensitiveDataRule` SPI | api | Scrubbing SPI | api | KEEP_AS_IS | `ServiceLoaderSensitiveDataRuleTest` | нет | LOW | — |
| `DegradedModeController`, `CircuitBreaker`, `TokenBucketRateLimiter` | otel-extension | Safety infrastructure | otel-extension | KEEP_IN_OTEL_EXTENSION_ADAPTER | `DegradedModeControllerTest` | нет | HIGH | — |
| `ExtensionPropertyNames` / `ExtensionDefaults` / `PlatformTracingDefaultsProvider` | otel-extension | Agent-side configuration | otel-extension | KEEP_IN_OTEL_EXTENSION_ADAPTER | `ExtensionConfigTest`, `PlatformTracingDefaultsProviderTest`, `SharedDefaultsAlignmentTest` | нет | HIGH | DONE |
| `ClassLoaderVisibilitySpikeProbe` | otel-extension | CL spike | otel-extension (spike) | DEFER | `ClassLoaderVisibilitySpikeE2ETest` | нет | LOW | PR-3 |


***

## 5. Module migration strategy

### `platform-tracing-api`

**Current role:** Публичные контракты — `PlatformTracing` interface, typed span builders, semconv keys, propagation, `DomainConfigHolder`, `SensitiveDataRule` SPI. 59 main классов.

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

### Sampling тесты

**Existing tests to keep:**

- `CompositeSamplerTest`, `CompositeSamplerEdgeCasesTest` (otel-extension) — MUST_KEEP
- `RouteRatioTest`, `SamplerStateHolderTest`, `SamplerRuntimeUpdateConcurrencyTest` (otel-extension) — MUST_KEEP
- `PlatformSamplerProviderTest` — MUST_KEEP

**Tests to duplicate before moving behavior (PR-5):**

- `SamplingRuleChainCharacterizationTest` в `platform-tracing-core` — pure Java, без OTel SDK

**Tests to adapt after split (PR-6):**

- `CompositeSamplerTest` — обновить imports для core rule classes
- `PlatformSamplerProviderTest` — адаптировать для factory delegation

**New tests required (PR-6):**

- `SamplerStateCoreTest` — unit в core context

**PR:** PR-5 (duplicate), PR-6 (adapt)

**Risk if skipped:** Sampling regression undetected при extraction — опасность изменения ordered rule chain.

***

### Scrubbing тесты

**Existing tests to keep:**

- `ScrubbingSpanProcessorTest`, `ScrubbingSpanProcessorAdvancedTest`, `ScrubbingSecurityNegativeTest` — MUST_KEEP
- `MergeEngineTest`, `RuleCircuitBreakerTest`, `ExtensionRuleLoaderTest`, `ServiceLoaderSensitiveDataRuleTest` — MUST_KEEP
- `BuiltInRulesTest`, `KeyMatcherTest` — MUST_KEEP

**Tests to duplicate before moving behavior (PR-5):**

- `ScrubbingEngineCharacterizationTest` в `platform-tracing-core`

**Tests to adapt after split (PR-7):**

- `MergeEngineTest` копия в core module
<span style="display:none">[^1]</span>

<div align="center">⁂</div>

[^1]: platform-tracing-current-codebase-inventory.md

